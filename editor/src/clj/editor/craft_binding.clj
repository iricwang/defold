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
            [editor.automation-editor :as automation-editor]
            [editor.craft-project :as craft-project]
            [editor.craft-publish :as craft-publish]
            [editor.dialogs :as dialogs]
            [editor.disk :as disk]
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
           [java.nio.file AtomicMoveNotSupportedException CopyOption FileVisitResult Files OpenOption Path SimpleFileVisitor StandardCopyOption StandardOpenOption]
           [java.nio.file.attribute BasicFileAttributes]
           [java.security DigestInputStream MessageDigest]
           [java.util Arrays Base64 UUID]
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

(defn- peers [state]
  (cond-> (:peers state {})
    (:active state) (assoc (get-in state [:active :id]) (:active state))))

(defn- peer-for-token [state token]
  (when-not (string/blank? token)
    (coll/first-where #(= token (:token %)) (coll/vals (peers state)))))

(defn- without-peer [state id]
  (let [remaining (dissoc (peers state) id)]
    (cond-> (assoc state :peers remaining)
      (= id (get-in state [:active :id])) (assoc :active (first (sort-by :address (into [] (coll/vals remaining))))))))

(defn- authorize! [service request]
  (when-not (peer-for-token @(:state service) (bearer request))
    (fail! "This application is not a bound peer")))

(defn- require-loopback! [request]
  (when-not (when-let [address (:remote-address request)]
              (.isLoopbackAddress (InetAddress/getByName address)))
    (throw (http-server/error (http-server/response 403 "This editor adapter is only available on loopback")))))

(defn- structured-routes [service]
  (into {}
        (map (fn [[path handler]]
               [path {"POST" (fn [request]
                               (require-loopback! request)
                               (web-server/require-authorized! request (:editor-token service))
                               (try
                                 (let [body (json/read-str (String. ^bytes (read-limited (:body request) (* 4 1024 1024)) StandardCharsets/UTF_8) :key-fn keyword)]
                                   (http-server/json-response (handler body)))
                                 (catch clojure.lang.ExceptionInfo error
                                   (http-server/json-response {:error (merge {:code "RECOVERY_REQUIRED" :message (ex-message error)} (ex-data error))} 409))
                                 (catch Exception _
                                   (http-server/json-response {:error {:code "PROTOCOL_UNSUPPORTED" :message "Invalid structured adapter request"}} 400))))}]))
        {"/automation/v1/snapshot" (fn [request] (ui/run-now (automation-editor/snapshot service (:path request))))
         "/automation/v1/edit" (fn [request] (ui/run-now (automation-editor/edit! service request)))
         "/automation/v1/history" (fn [request] (ui/run-now (automation-editor/history! service request)))
         "/craft/v1/project" (fn [_] (ui/run-now (craft-project/describe service)))
         "/craft/v1/snapshot" (fn [request] (ui/run-now (craft-project/snapshot service (:path request))))
         "/craft/v1/resource/open" (fn [request] (ui/run-now (craft-project/open-resource! service (:path request))))
         "/craft/v1/publish/prepare" #(craft-publish/prepare! service %)
         "/craft/v1/publish/apply" #(craft-publish/apply! service %)
         "/craft/v1/publish/status" #(craft-publish/status service %)
         "/craft/v1/publish/cancel" #(craft-publish/cancel! service %)}))

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
            (when (and (.isRegularFile attrs)
                       (not (string/starts-with? (str (.getFileName path)) "."))
                       (not (string/ends-with? relative ".pcraft")))
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
  (swap! (:state service)
         (fn [state]
           ;; An endpoint can only host one live instance; reconnecting it must
           ;; revoke its old credentials without releasing other endpoints.
           (let [state (reduce (fn [state old]
                                 (if (= (:address old) (:address peer))
                                   (without-peer state (:id old))
                                   state))
                               state (coll/vals (peers state)))]
             (-> state
                 (assoc-in [:peers (:id peer)] peer)
                 (assoc :active peer)
                 (dissoc :error))))))

(defn- referenced-resource? [service resource]
  (let [root (.toPath (.getCanonicalFile ^File (:root service)))
        found (volatile! false)]
    (Files/walkFileTree
      root
      (proxy [SimpleFileVisitor] []
        (preVisitDirectory [path _attrs]
          (if (and (not= root path)
                   (or (string/starts-with? (str (.getFileName ^Path path)) ".")
                       (contains? #{"build" "target"} (str (.getFileName ^Path path)))))
            FileVisitResult/SKIP_SUBTREE
            FileVisitResult/CONTINUE))
        (visitFile [path attrs]
          (let [^Path path path
                ^BasicFileAttributes attrs attrs
                relative (string/replace (str (.relativize root path)) "\\" "/")
                metadata (and (string/starts-with? relative "photocraft_generated/")
                              (or (string/includes? relative "/manifests/") (string/ends-with? relative "/assets.json")))]
            (when (or (.isSymbolicLink attrs)
                      (and (not metadata)
                           (not= resource (str "/" relative))
                           (re-find #"\.(gui|atlas|tilesource|sprite|collection|go|script|gui_script|lua|particlefx|json|material)$" relative)
                           (or (< (* 16 1024 1024) (.size attrs))
                               (try
                                 (let [text (Files/readString path StandardCharsets/UTF_8)]
                                   (or (string/includes? text resource) (string/includes? text (subs resource 1))))
                                 (catch Exception _ true)))))
              (vreset! found true)))
          (if @found FileVisitResult/TERMINATE FileVisitResult/CONTINUE))))
    @found))

(defn- managed-upload! [service body]
  (let [{:keys [path expected remove data]} body
        relative (relative-path path)
        file (project-file service relative)
        state-atom (:state service)]
    (when-not (string/starts-with? relative "photocraft_generated/")
      (fail! "Only generated PhotoCraft assets may be updated"))
    (locking state-atom
      (when (< (* 64 1024 1024) (.length file)) (fail! "Generated resource exceeds size limit"))
      (let [before (when (.isFile file) (Files/readAllBytes (.toPath file)))
            actual (when before
                     (apply str (into [] (map #(format "%02x" (bit-and 255 %)))
                                      (.digest (MessageDigest/getInstance "SHA-256") ^bytes before))))]
        (when-not (= expected actual)
          (fail! "Generated asset changed before update"))
        (if remove
          (if (referenced-resource? service (str "/" relative))
            {:retained true}
            (do (Files/deleteIfExists (.toPath file)) {:removed true}))
          (let [bytes (.decode (Base64/getDecoder) ^String data)]
            (when (< (* 64 1024 1024) (alength bytes)) (fail! "Generated resource exceeds size limit"))
            (io/make-parents file)
            (project-file service relative)
            (let [temp (Files/createTempFile (.toPath (.getParentFile file)) ".photocraft-" ".tmp" (make-array java.nio.file.attribute.FileAttribute 0))]
              (try
                (Files/write temp bytes ^"[Ljava.nio.file.OpenOption;" (make-array OpenOption 0))
                (try
                  (Files/move temp (.toPath file) (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
                  (catch AtomicMoveNotSupportedException _
                    (Files/move temp (.toPath file) (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))
                (finally (Files/deleteIfExists temp))))
            {:uploaded true}))))))

(defn- base-routes [service]
  (let [eval-handler (get-in (ext.server/routes (:project service) (:editor-token service)) ["/eval" "POST"])]
    {"/binding/status" {"POST" (fn [_] (http-server/json-response {:app "Defold" :id (:id service) :bindingVersion 2
                                                                     :bound (get-in @(:state service) [:active :id])
                                                                     :boundPeers (mapv :id (coll/vals (peers @(:state service))))}))}
     "/binding/project" {"POST" (fn [request]
                                  (let [token (bearer request)
                                        state @(:state service)]
                                    (when-not (and token (or (peer-for-token state token)
                                                             (= token (get-in state [:incoming :ticket]))
                                                             (= token (:outgoing-ticket state))))
                                      (fail! "Project metadata requires a binding ticket")))
                                  (http-server/json-response {:project (:project-id service)}))}
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
                                       (fail! "Wait for project file operations before connecting"))
                                     (replace-peer! service {:id (:id offer) :address (:address offer) :token (:ticket offer)})
                                     (swap! (:state service) dissoc :incoming)))
                                 (http-server/json-response {:phase "bound" :id (:id service)}))}
     "/binding/committed" {"POST" (fn [request]
                                    (http-server/json-response {:phase (if (and (bearer request) (peer-for-token @(:state service) (bearer request))) "bound" "pending")}))}
     "/binding/release" {"POST" (fn [request]
                                  (swap! (:state service)
                                         (fn [state]
                                           (if-let [peer (peer-for-token state (bearer request))]
                                             (without-peer state (:id peer))
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
                                 (let [body (body-json request)]
                                   (if (:managed body)
                                     (http-server/json-response (managed-upload! service body))
                                     (let [{:keys [path data]} body
                                           relative (relative-path path)]
                                       (when-not (string/starts-with? relative "photocraft_generated/")
                                         (fail! "Only generated PhotoCraft assets may be uploaded"))
                                       (let [file (project-file service relative)
                                             bytes (.decode (Base64/getDecoder) ^String data)]
                                         (when (< (* 64 1024 1024) (alength bytes)) (fail! "Generated resource exceeds size limit"))
                                         (io/make-parents file)
                                         (project-file service relative)
                                         (if (.exists file)
                                           (when-not (Arrays/equals ^bytes bytes ^bytes (Files/readAllBytes (.toPath file)))
                                             (fail! "A different generated asset already exists"))
                                           (Files/write (.toPath file) bytes ^"[Ljava.nio.file.OpenOption;" (into-array OpenOption [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE]))))
                                       (http-server/json-response {:uploaded true})))))}
     "/binding/preview" {"POST" (fn [request]
                                  (authorize! service request)
                                  (let [{:keys [path width height]} (body-json request)
                                        path (relative-path path)]
                                    ((get-in (scene/routes (:project service) (:app-view service)) ["/preview/{*path}" "GET"])
                                     {:method "GET" :path-params {:path path} :query (str "width=" width "&height=" height)})))}
     "/binding/eval" {"POST" (fn [request]
                               (require-loopback! request)
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
                                     (when (and (contains? #{"/binding/upload" "/binding/eval"} path)
                                                (disk/managed-write-active?))
                                       (fail! "Wait for the current managed publication before using the legacy writer"))
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
        (merge (base-routes service) (structured-routes service)))))

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
          (when-not (and (contains? (peers @state) (:id status))
                         (if (= 2 (:bindingVersion status))
                           (contains? (set (:boundPeers status)) (:id service))
                           (= (:bound status) (:id service))))
            (locking state
              (when (or (:incoming @state) (:outgoing @state) (pos? (:file-operations @state 0)))
                (fail! "Wait for the current binding request or project file operation to finish"))
              (swap! state assoc :outgoing operation))
            (let [{:keys [ticket id]} (call! target "/binding/request" "" {:id (:id service) :address (own-address target (:port service)) :project (:project-id service)})
                  expires (+ (System/currentTimeMillis) 170000)]
              (swap! state assoc :outgoing-ticket ticket)
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
          (swap! (:state service) (fn [state] (if (= operation (:outgoing state)) (dissoc state :outgoing :outgoing-ticket) state)))
          (when @disabled
            (ui/run-later (ui/enable-ui!))))))))

(defn local-available? []
  (when-let [service @current]
    (let [{:keys [local-peer]} @(:state service)]
      (or (and local-peer (< (- (System/currentTimeMillis) (:seen-at local-peer)) 6500))
          (coll/any? #(string/starts-with? (:address %) "127.0.0.1:") (coll/vals (peers @(:state service))))))))

(defn- discover-local! [service]
  (let [status (try (call! "127.0.0.1:17655" "/binding/status" "" {}) (catch Exception _))
        peer (when (= "PhotoCraft" (:app status))
               {:id (:id status) :seen-at (System/currentTimeMillis)})

        _state (swap! (:state service)
                      (fn [state]
                        (let [active (coll/first-where #(= "127.0.0.1:17655" (:address %)) (coll/vals (peers state)))
                              state (assoc state :local-peer peer)]
                          (if (and peer
                                   (= "127.0.0.1:17655" (:address active))
                                   (not= (:id peer) (:id active))
                                   (or (= 2 (:bindingVersion status)) (not (:bound status)))
                                   (not (:incoming state))
                                   (not (:outgoing state))
                                   (zero? (:file-operations state 0)))
                            ;; A new instance owns the same listening socket; the previous one is gone.
                            (without-peer state (:id active))
                            state))))]
    ;; PhotoCraft initiates discovery offers to avoid simultaneous requests.
    nil))

(declare ^:private select-peer)

(defn open-local! [path on-opened!]
  (when-let [service @current]
    (let [known (pos? (count (peers @(:state service))))
          selected (when known (select-peer service (:localization service) nil))]
      (when (or (not known) selected)
        (future/io
          (try
            (let [target (or (:address selected) "127.0.0.1:17655")]
              (when (.get ^CompletableFuture (bind! service target false))
                (let [{:keys [address token]} (coll/first-where #(= target (:address %)) (coll/vals (peers @(:state service))))]
                (call! address "/binding/open" token {:path path})
                (ui/run-later (on-opened!)))))
            (catch Exception error
              (swap! (:state service) assoc :error (ex-message error))
              (ui/run-later
                (dialogs/make-info-dialog (:localization service)
                                         {:title (localization/message "dialog.craft-binding.title")
                                          :header (localization/message "dialog.craft-binding.failed")
                                          :content (ex-message error)})))))))))

(handler/defhandler :photocraft.bind :global
  (run [localization user-data]
    (when-let [service @current]
      (when-let [target (or (:address user-data) (dialogs/make-target-ip-dialog "127.0.0.1:17655" (localization (localization/message "dialog.craft-binding.ip")) localization))]
        (bind! service target false)))))

(defn- select-peer [service localization peer-id]
  (let [connections (vec (sort-by :address (into [] (coll/vals (peers @(:state service))))))]
    (cond
      peer-id (get (peers @(:state service)) peer-id)
      (= 1 (count connections)) (first connections)
      (pos? (count connections))
      (let [labels (mapv #(str (:address %) " · " (subs (:id %) 0 (min 8 (count (:id %))))) connections)
            selected (first (dialogs/make-select-list-dialog labels localization
                                                            {:title (localization/message "dialog.craft-binding.select")
                                                             :cell-fn (fn [label _localization] {:text label})}))]
        (coll/first-where #(= selected (str (:address %) " · " (subs (:id %) 0 (min 8 (count (:id %)))))) connections)))))

(handler/defhandler :photocraft.status :global
  (run [localization]
    (when-let [service @current]
      (let [state @(:state service)
            connections (sort-by :address (into [] (coll/vals (peers state))))
            lines (into [(localization (localization/message "dialog.craft-binding.listener" {"port" (:port service)}))]
                        (map :address) connections)]
        (dialogs/make-info-dialog localization
                                  {:title (localization/message "dialog.craft-binding.title")
                                   :header (if (pos? (count connections))
                                             (localization/message "dialog.craft-binding.count" {"count" (count connections)})
                                             (localization/message "dialog.craft-binding.disconnected"))
                                   :content (coll/join-to-string "\n" (cond-> lines (:error state) (conj (:error state))))})))))

(handler/defhandler :photocraft.unbind :global
  (run [localization user-data]
    (when-let [service @current]
      (when-let [peer (select-peer service localization (:peer-id user-data))]
        (swap! (:state service) without-peer (:id peer))
        (future/io
          (try (call! (:address peer) "/binding/release" (:token peer) {}) (catch Exception _)))))))

(handler/defhandler :photocraft.open :global
  (run [localization user-data]
    (when-let [service @current]
      (when-let [{:keys [address token]} (select-peer service localization (:peer-id user-data))]
        (when-let [path ((:selected-path service))]
          (future/io (call! address "/binding/open" token {:path path})))))))

(defn- discovery-file
  ^File [service]
  (let [root (.getCanonicalFile (io/file (:root service)))
        file (.getCanonicalFile (io/file root ".internal" "photocraft" "editor-adapter.json"))]
    (when-not (.startsWith (.toPath file) (.toPath root))
      (fail! "Editor discovery resolves outside the project"))
    file))

(defn- write-discovery! [service]
  (let [file (discovery-file service)]
    (io/make-parents file)
    (let [temp (Files/createTempFile (.toPath (.getParentFile file)) ".adapter-" ".tmp" (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (spit (.toFile temp) (json/write-str {:schemaVersion 1 :port (:port service) :editorSessionId (craft-project/session-id service)}))
        (try
          (Files/move temp (.toPath file) (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
          (catch AtomicMoveNotSupportedException _
            (Files/move temp (.toPath file) (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))
        (finally (Files/deleteIfExists temp))))))

(defn- remove-discovery! [service]
  (let [file (discovery-file service)]
    (when (and (.isFile file) (= (craft-project/session-id service) (:editorSessionId (json/read-str (slurp file) :key-fn keyword))))
      (Files/deleteIfExists (.toPath file)))))

(defn start! [project app-view prefs localization root editor-token prepare! idle? selected-path]
  (let [service {:project project
                 :app-view app-view
                 :prefs prefs
                 :localization localization
                 :root (io/file root)
                 :project-id (.getCanonicalPath (io/file root))
                 :editor-token editor-token
                 :id (apply str (into [] (map #(format "%02x" (bit-and 255 %))) (.decode (Base64/getUrlDecoder) ^String (web-server/make-token))))
                 :editor-session-id (str (UUID/randomUUID))
                 :state (atom {})
                 :publish-state (atom {})
                 :automation-state (atom {})
                 :prepare! prepare!
                 :idle? idle?
                 :selected-path selected-path}
        port (Integer/parseInt (System/getProperty "defold.craft.port" "17654"))
        server (try
                 (http-server/start! (http-server/router-handler (routes service)) :host "0.0.0.0" :port port)
                 (catch java.net.BindException error
                   (if (System/getProperty "defold.craft.port")
                     (throw error)
                     (http-server/start! (http-server/router-handler (routes service)) :host "0.0.0.0" :port 0))))
        service (assoc service :port (http-server/port server) :server server)]
    (try
      (write-discovery! service)
      (catch Throwable error
        (http-server/stop! server)
        (throw error)))
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
    (remove-discovery! service)
    (compare-and-set! current service nil)))
