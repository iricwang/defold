;; Copyright 2020-2026 The Defold Foundation
;; Copyright 2014-2020 King
;; Copyright 2009-2014 Ragnar Svensson, Christian Murray
;; Licensed under the Defold License version 1.0 (the "License"); you may not use
;; this file except in compliance with the License.
;;
;; You may obtain a copy of the License, together with FAQs at
;; https://www.defold.com/license
;;
;; Unless required by applicable law or agreed to in writing, software distributed
;; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
;; CONDITIONS OF ANY KIND, either express or implied. See the License for the
;; specific language governing permissions and limitations under the License.

(ns editor.craft-binding
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.dialogs :as dialogs]
            [editor.editor-extensions.server :as ext.server]
            [editor.future :as future]
            [editor.handler :as handler]
            [editor.localization :as localization]
            [editor.scene :as scene]
            [editor.ui :as ui]
            [editor.web-server :as web-server]
            [util.coll :as coll]
            [util.http-server :as http-server])
  (:import [java.io ByteArrayInputStream File]
           [java.net HttpURLConnection InetAddress InetSocketAddress Socket URI]
           [java.nio.charset StandardCharsets]
           [java.nio.file FileVisitResult Files OpenOption Path SimpleFileVisitor StandardOpenOption]
           [java.nio.file.attribute BasicFileAttributes]
           [java.security DigestInputStream MessageDigest]
           [java.util Arrays Base64]
           [java.util.concurrent CompletableFuture]
           [java.util.function BiConsumer]))

(set! *warn-on-reflection* true)

(defonce ^:private current (atom nil))

(defn- fail! [message]
  (throw (http-server/error (http-server/response 409 message))))

(defn- read-limited [input limit]
  (let [bytes (.readNBytes ^java.io.InputStream input (int (inc limit)))]
    (when (< limit (alength bytes))
      (fail! "Peer request exceeds size limit"))
    bytes))

(defn- body-json [request]
  (json/read-str (String. ^bytes (read-limited (:body request) (* 90 1024 1024)) StandardCharsets/UTF_8) :key-fn keyword))

(defn- bearer [request]
  (let [value (get-in request [:headers "authorization"] "")]
    (when (string/starts-with? value "Bearer ")
      (subs value 7))))

(defn- endpoint [address default-port]
  (when-not (and (string? address) (re-matches #"[0-9a-fA-F:.\[\]]+" address))
    (fail! "Enter an IP address, optionally followed by a port"))
  (let [uri (URI. (str "http://" address))]
    (when-not (.getHost uri)
      (fail! "Invalid peer IP address"))
    (str (.getHost uri) ":" (if (neg? (.getPort uri)) default-port (.getPort uri)))))

(defn- call! [address route token body]
  (let [connection ^HttpURLConnection (.openConnection (.toURL (URI. (str "http://" address route))))
        data (.getBytes ^String (json/write-str body) StandardCharsets/UTF_8)]
    (try
      (.setConnectTimeout connection 900)
      (.setReadTimeout connection 30000)
      (.setRequestMethod connection "POST")
      (.setRequestProperty connection "Authorization" (str "Bearer " token))
      (.setDoOutput connection true)
      (.setFixedLengthStreamingMode connection (alength data))
      (with-open [out (.getOutputStream connection)]
        (.write out data))
      (when-not (= 200 (.getResponseCode connection))
        (fail! "PhotoCraft rejected the binding request"))
      (with-open [input (.getInputStream connection)]
        (json/read-str (String. ^bytes (read-limited input 65536) StandardCharsets/UTF_8) :key-fn keyword))
      (finally
        (.disconnect connection)))))

(defn- relative-path [path]
  (when-not (and (string? path)
                (not (string/blank? path))
                (not (re-find #"[\\:\x00]" path))
                (coll/every? #(and (not (string/blank? %)) (not (string/starts-with? % ".")))
                        (string/split (string/replace-first path #"^/" "") #"/")))
    (fail! "Invalid resource path"))
  (string/replace-first path #"^/" ""))

(defn- project-file
  ^File [service path]
  (let [root (.toPath (.getCanonicalFile ^File (:root service)))
        target (.normalize (.resolve root ^String (relative-path path)))]
    (when-not (.startsWith target root)
      (fail! "Resource is outside the project"))
    (loop [parent target]
      (when (and parent (not= root parent))
        (when (Files/isSymbolicLink parent)
          (fail! "Symlink resources are not transferred"))
        (recur (.getParent parent))))
    (.toFile target)))

(defn- authorize! [service request]
  (let [state @(:state service)
        token (bearer request)]
    (when-not (and token (= token (get-in state [:active :token])) (not (:incoming state)) (not (:outgoing state)))
      (fail! "This application is not the bound peer"))))

(defn- manifest [service]
  (let [root (.toPath (.getCanonicalFile ^File (:root service)))
        files (volatile! [])
        total-size (volatile! 0)]
    (Files/walkFileTree
      root
      (proxy [SimpleFileVisitor] []
        (preVisitDirectory [path _attrs]
          (let [^Path path path
                name (str (.getFileName path))]
            (if (and (not= root path)
                     (or (string/starts-with? name ".") (contains? #{"build" "target"} name)))
              FileVisitResult/SKIP_SUBTREE
              (do
                (when (< 128 (.getNameCount (.relativize root path)))
                  (fail! "Resource nesting exceeds limit"))
                FileVisitResult/CONTINUE))))
        (visitFile [path attrs]
          (let [^Path path path
                ^BasicFileAttributes attrs attrs
                relative (string/replace (str (.relativize root path)) "\\" "/")]
            (when (and (.isRegularFile attrs) (not (string/ends-with? relative ".pcraft")))
              (when (or (<= 20000 (count @files)) (< (* 64 1024 1024) (.size attrs)))
                (fail! "Project resource count or file size exceeds limit"))
              (vswap! total-size + (.size attrs))
              (when (< (* 1024 1024 1024) @total-size)
                (fail! "Project exceeds 1 GB transfer limit"))
              (let [digest (MessageDigest/getInstance "SHA-256")]
                (with-open [input (DigestInputStream. (io/input-stream (.toFile path)) digest)]
                  (.transferTo input (java.io.OutputStream/nullOutputStream)))
                (vswap! files conj {:path relative
                                   :size (.size attrs)
                                   :sha256 (apply str (into [] (map #(format "%02x" (bit-and 255 %))) (.digest digest)))}))))
          FileVisitResult/CONTINUE)))
    {:project (:project-id service) :files @files}))

(defn- pending! [service request]
  (let [offer (:incoming @(:state service))]
    (when-not (and offer (= (:ticket offer) (bearer request)) (< (System/currentTimeMillis) (:expires offer)))
      (fail! "Binding request expired or was cancelled"))
    offer))

(defn- request-binding! [service request]
  (let [{:keys [id address]} (body-json request)
        address (endpoint address 17655)
        ticket (web-server/make-token)
        state-atom (:state service)]
    ;; PhotoCraft uses a 64-character random instance identity, never a filesystem path.
    (when-not (and (string? id) (re-matches #"[0-9a-f]{64}" id))
      (fail! "Invalid PhotoCraft identity"))
    (locking state-atom
      (swap! state-atom (fn [state]
                          (if (< (get-in state [:incoming :expires] Long/MAX_VALUE) (System/currentTimeMillis))
                            (dissoc state :incoming)
                            state)))
      (when (or (:incoming @(:state service)) (:outgoing @(:state service)) (pos? (:file-operations @(:state service) 0)))
        (fail! "Wait for the current binding request or project file operation to finish"))
      (swap! (:state service) assoc :incoming {:id id :address address :ticket ticket :phase "confirm" :expires (+ (System/currentTimeMillis) 180000)}))
    (ui/run-later
      ((:prepare! service)
       {:address address :current (get-in @(:state service) [:active :address])
        :local (when-let [source (:remote-address request)] (.isLoopbackAddress (InetAddress/getByName source)))}
       (fn [accepted]
         (swap! (:state service)
                (fn [state]
                  (if (= ticket (get-in state [:incoming :ticket]))
                    (assoc-in state [:incoming :phase] (if accepted "ready" "rejected"))
                    state))))))
    (http-server/json-response {:ticket ticket :id (:id service)})))

(defn- replace-peer! [service peer]
  (let [state (:state service)
        previous (:active @state)]
    (swap! state #(-> % (assoc :active peer) (dissoc :error)))
    (when (and previous (not= (:id previous) (:id peer)))
      (future/io
        (try
          (call! (:address previous) "/binding/release" (:token previous) {})
          (catch Exception _))))))

(defn- base-routes [service]
  (let [eval-handler (get-in (ext.server/routes (:project service) (:editor-token service)) ["/eval" "POST"])]
    {"/binding/status" {"POST" (fn [_] (http-server/json-response {:app "Defold" :id (:id service) :bound (get-in @(:state service) [:active :id])}))}
     "/binding/request" {"POST" #(request-binding! service %)}
     "/binding/result" {"POST" (fn [request] (http-server/json-response (select-keys (pending! service request) [:phase])))}
     "/binding/cancel" {"POST" (fn [request]
                                 (pending! service request)
                                 (swap! (:state service) dissoc :incoming)
                                 (http-server/json-response {:cancelled true}))}
     "/binding/commit" {"POST" (fn [request]
                                 (ui/run-now
                                   ;; Recheck the ticket on the UI thread: cancellation can arrive while this request waits.
                                   (let [offer (pending! service request)]
                                     (when-not (and (= "ready" (:phase offer)) (zero? (:file-operations @(:state service) 0)) ((:idle? service)))
                                       (fail! "Close all workspace files and wait for file operations before switching"))
                                     (replace-peer! service {:id (:id offer) :address (:address offer) :token (:ticket offer)})
                                     (swap! (:state service) dissoc :incoming)))
                                 (http-server/json-response {:phase "bound" :id (:id service)}))}
     "/binding/committed" {"POST" (fn [request]
                                    (http-server/json-response {:phase (if (and (bearer request) (= (bearer request) (get-in @(:state service) [:active :token]))) "bound" "pending")}))}
     "/binding/release" {"POST" (fn [request]
                                  (swap! (:state service)
                                         (fn [state]
                                           (if (= (bearer request) (get-in state [:active :token]))
                                             (dissoc state :active)
                                             state)))
                                  (http-server/json-response {:released true}))}
     "/binding/manifest" {"POST" (fn [request] (authorize! service request) (http-server/json-response (manifest service)))}
     "/binding/file" {"POST" (fn [request]
                               (authorize! service request)
                               (let [file (project-file service (:path (body-json request)))]
                                 (when (or (not (.isFile file)) (< (* 64 1024 1024) (.length file)))
                                   (fail! "Missing or oversized resource"))
                                 (with-open [input (io/input-stream file)]
                                   (http-server/response 200 (read-limited input (* 64 1024 1024))))))}
     "/binding/upload" {"POST" (fn [request]
                                 (authorize! service request)
                                 (let [{:keys [path data]} (body-json request)
                                       relative (relative-path path)]
                                   (when-not (string/starts-with? relative "photocraft_generated/")
                                     (fail! "Only generated PhotoCraft assets may be uploaded"))
                                   (let [file (project-file service relative)
                                         bytes (.decode (Base64/getDecoder) ^String data)]
                                     (when (< (* 64 1024 1024) (alength bytes))
                                       (fail! "Generated resource exceeds size limit"))
                                     (io/make-parents file)
                                     (project-file service relative)
                                     (if (.exists file)
                                       (when-not (Arrays/equals ^bytes bytes ^bytes (Files/readAllBytes (.toPath file)))
                                         (fail! "A different generated asset already exists"))
                                       (Files/write (.toPath file) bytes ^"[Ljava.nio.file.OpenOption;" (into-array OpenOption [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE])))))
                                 (http-server/json-response {:uploaded true}))}
     "/binding/preview" {"POST" (fn [request]
                                  (authorize! service request)
                                  (let [{:keys [path width height]} (body-json request)
                                        path (relative-path path)]
                                    ((get-in (scene/routes (:project service) (:app-view service)) ["/preview/{*path}" "GET"])
                                     {:method "GET" :path-params {:path path} :query (str "width=" width "&height=" height)})))}
     "/binding/eval" {"POST" (fn [request]
                               (authorize! service request)
                               (eval-handler (assoc request
                                               :body (ByteArrayInputStream. (read-limited (:body request) (* 4 1024 1024)))
                                               :headers {"authorization" (str "Bearer " (:editor-token service))})))}}))

(defn- routes [service]
  (let [state (:state service)]
    (into {}
        (map (fn [[path methods]]
               [path (if-not (contains? #{"/binding/manifest" "/binding/file" "/binding/upload" "/binding/preview" "/binding/eval"} path)
                       methods
                       (update methods "POST"
                               (fn [handler]
                                 (fn [request]
                                   (locking state
                                     (authorize! service request)
                                     (swap! (:state service) update :file-operations (fnil inc 0)))
                                   (try
                                     (let [result (handler request)]
                                       (if (instance? CompletableFuture result)
                                         (.whenComplete ^CompletableFuture result
                                                        (reify BiConsumer
                                                          (accept [_ _value _error]
                                                            (swap! (:state service) update :file-operations dec))))
                                         (do
                                           (swap! (:state service) update :file-operations dec)
                                           result)))
                                     (catch Throwable error
                                       (swap! (:state service) update :file-operations dec)
                                       (throw error)))))))]))
          (base-routes service))))

(defn- own-address [target port]
  (with-open [socket (Socket.)]
    (let [uri (URI. (str "http://" target))]
      (.connect socket (InetSocketAddress. (.getHost uri) (.getPort uri)) 900)
      (str (.getHostAddress (.getLocalAddress socket)) ":" port))))

(defn- bind! [service target automatic]
  (let [operation (web-server/make-token)
        disabled (atom false)]
    (future/io
      (try
        (let [target (endpoint target 17655)
              state (:state service)
              status (call! target "/binding/status" "" {})]
          (when-not (= "PhotoCraft" (:app status))
            (fail! "This address is not PhotoCraft"))
          (when-not (and (= (:id status) (get-in @state [:active :id])) (= (:bound status) (:id service)))
            (locking state
              (when (or (:incoming @state) (:outgoing @state) (pos? (:file-operations @state 0)))
                (fail! "Wait for the current binding request or project file operation to finish"))
              (swap! state assoc :outgoing operation))
            (let [{:keys [ticket id]} (call! target "/binding/request" "" {:id (:id service) :address (own-address target (:port service))})
                  expires (+ (System/currentTimeMillis) 170000)]
              (try
                (loop []
                  (let [{:keys [phase]} (call! target "/binding/result" ticket {})]
                    (cond
                      (= phase "ready") nil
                      (or (= phase "rejected") (< expires (System/currentTimeMillis))) (fail! "PhotoCraft binding was cancelled or timed out")
                      :else (do (Thread/sleep 300) (recur)))))
                (let [answer (promise)]
                  (ui/run-later
                    ((:prepare! service) {:address target :current (get-in @state [:active :address]) :local true} #(deliver answer %)))
                  (when-not (deref answer 170000 false)
                    (fail! "Defold binding was cancelled or timed out")))
                (when-not (ui/run-now
                            (when (and (zero? (:file-operations @state 0)) ((:idle? service)))
                              (ui/disable-ui!)
                              (reset! disabled true)))
                  (fail! "Defold workspace is busy"))
                ;; A lost acknowledgement must be reconciled against the session before returning failure.
                (try (call! target "/binding/commit" ticket {}) (catch Exception _))
                (let [deadline (+ (System/currentTimeMillis) 15000)]
                  (loop []
                    (when-not (= "bound" (:phase (try (call! target "/binding/committed" ticket {}) (catch Exception _))))
                      (when (< deadline (System/currentTimeMillis))
                        (fail! "Cannot confirm whether PhotoCraft completed the binding; check its binding status before retrying"))
                      (Thread/sleep 100)
                      (recur))))
                (replace-peer! service {:id id :address target :token ticket})
                (catch Exception e
                  (try (call! target "/binding/cancel" ticket {}) (catch Exception _))
                  (throw e)))))
          true)
        (catch Exception e
          (swap! (:state service) assoc :error (ex-message e))
          (when-not automatic
            (ui/run-later
              (dialogs/make-info-dialog (:localization service)
                                       {:title (localization/message "dialog.craft-binding.title")
                                        :header (localization/message "dialog.craft-binding.failed")
                                        :content (ex-message e)})))
          false)
        (finally
          (swap! (:state service) (fn [state] (if (= operation (:outgoing state)) (dissoc state :outgoing) state)))
          (when @disabled
            (ui/run-later (ui/enable-ui!))))))))

(defn local-available? []
  (when-let [service @current]
    (let [{:keys [local-peer incoming outgoing]} @(:state service)]
      (and local-peer
           (< (- (System/currentTimeMillis) (:seen-at local-peer)) 6500)
           (not incoming)
           (not outgoing)))))

(defn- discover-local! [service]
  (let [status (try (call! "127.0.0.1:17655" "/binding/status" "" {}) (catch Exception _))
        peer (when (= "PhotoCraft" (:app status))
               {:id (:id status) :seen-at (System/currentTimeMillis)})

        state (swap! (:state service) assoc :local-peer peer)]
    (when (and peer (not (:bound status))
               (not (:active state)) (not (:incoming state)) (not (:outgoing state)))
      (bind! service "127.0.0.1:17655" true))))

(defn open-local! [path on-opened!]
  (when-let [service @current]
    (future/io
      (when (.get ^CompletableFuture (bind! service "127.0.0.1:17655" false))
        (try
          (let [{:keys [address token]} (:active @(:state service))]
            (call! address "/binding/open" token {:path path})
            (ui/run-later (on-opened!)))
          (catch Exception error
            (swap! (:state service) assoc :error (ex-message error))
            (ui/run-later
              (dialogs/make-info-dialog (:localization service)
                                       {:title (localization/message "dialog.craft-binding.title")
                                        :header (localization/message "dialog.craft-binding.failed")
                                        :content (ex-message error)}))))))))

(handler/defhandler :photocraft.bind :global
  (run [localization user-data]
    (when-let [service @current]
      (when-let [target (or (:address user-data) (dialogs/make-target-ip-dialog "127.0.0.1:17655" (localization (localization/message "dialog.craft-binding.ip")) localization))]
        (bind! service target false)))))

(handler/defhandler :photocraft.status :global
  (run [localization]
    (when-let [service @current]
      (dialogs/make-info-dialog localization
                                {:title (localization/message "dialog.craft-binding.title")
                                 :header (or (get-in @(:state service) [:active :address])
                                             (localization (localization/message "dialog.craft-binding.disconnected")))
                                 :content (or (:error @(:state service))
                                              (localization (localization/message "dialog.craft-binding.ready")))}))))

(handler/defhandler :photocraft.open :global
  (run []
    (when-let [service @current]
      (when-let [{:keys [address token]} (:active @(:state service))]
        (when-let [path ((:selected-path service))]
          (future/io (call! address "/binding/open" token {:path path})))))))

(defn start! [project app-view localization root editor-token prepare! idle? selected-path]
  (let [service {:project project
                 :app-view app-view
                 :localization localization
                 :root (io/file root)
                 :project-id (.getCanonicalPath (io/file root))
                 :editor-token editor-token
                 :id (apply str (into [] (map #(format "%02x" (bit-and 255 %))) (.decode (Base64/getUrlDecoder) ^String (web-server/make-token))))
                 :state (atom {})
                 :prepare! prepare!
                 :idle? idle?
                 :selected-path selected-path}
        port (Integer/parseInt (System/getProperty "defold.craft.port" "17654"))
        server (http-server/start! (http-server/router-handler (routes service)) :host "0.0.0.0" :port port)
        service (assoc service :port port :server server)]
    (reset! current service)
    (let [timer (java.util.Timer. "PhotoCraft local discovery" true)]
      (.schedule timer
                 (proxy [java.util.TimerTask] []
                   (run []
                     (when (= service @current)
                       (discover-local! service))))
                 0 5000)
      (swap! (:state service) assoc :discovery-timer timer))
    service))

(defn stop! [service]
  (when service
    (when-let [^java.util.Timer timer (:discovery-timer @(:state service))]
      (.cancel timer))
    (http-server/stop! (:server service))
    (compare-and-set! current service nil)))
