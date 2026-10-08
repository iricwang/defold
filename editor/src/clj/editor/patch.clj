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

(ns editor.patch
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.system :as system])
  (:import [com.defold.editor Editor PatchInstaller]
           [java.io ByteArrayOutputStream File]
           [java.lang ProcessHandle]
           [java.net HttpURLConnection URI]
           [java.nio.file Files StandardCopyOption]
           [java.util UUID]
           [org.apache.commons.codec.digest DigestUtils]))

(set! *warn-on-reflection* true)

(defn configuration []
  (let [embedded (when-let [source (io/resource "patch/build.json")]
                   (json/read-str (slurp source) :key-fn keyword))
        local (when-let [directory (System/getProperty "defold.patch.directory")]
                (json/read-str (slurp (io/file directory "build.json")) :key-fn keyword))
        config (if (system/defold-dev?)
                 (or local embedded)
                 (cond-> embedded local (assoc :feed (:feed local))))]
    (when config
      (when-not (and (pos-int? (:revision config)) (string? (:version config))
                     (string? (:channel config)) (string? (:repository config)))
        (throw (ex-info "Invalid installed patch configuration" {})))
      config)))

(defn configured? []
  (boolean (or (System/getProperty "defold.patch.directory")
               (io/resource "patch/build.json"))))

(defn- allowed-url? [url]
  (and (string? url)
       (try
         (let [uri (URI. url)]
           (and (.getHost uri)
                (nil? (.getUserInfo uri))
                (or (= "https" (.getScheme uri))
                    (and (= "http" (.getScheme uri))
                         (contains? #{"127.0.0.1" "localhost" "[::1]"} (.getHost uri))))))
         (catch Exception _ false))))

(defn- transfer! [url output limit progress-fn]
  ;; Check each redirect as well as the initial URL; never downgrade to remote HTTP.
  (loop [url url
         redirects 0]
    (when-not (and (allowed-url? url) (< redirects 8))
      (throw (ex-info "Invalid patch URL or too many redirects" {:url url})))
    (let [^HttpURLConnection connection (.openConnection (io/as-url url))]
      (.setConnectTimeout connection 5000)
      (.setReadTimeout connection 30000)
      (.setInstanceFollowRedirects connection false)
      (let [next-url
            (try
              (let [status (.getResponseCode connection)]
                (cond
                  (contains? #{301 302 303 307 308} status)
                  (str (.resolve (URI. url) (.getHeaderField connection "Location")))

                  (= 200 status)
                  (with-open [input (.getInputStream connection)]
                    (let [buffer (byte-array 65536)]
                      (loop [received 0]
                        (let [n (.read input buffer)]
                          (when (pos? n)
                            (let [received (+ received n)]
                              (when (> received limit)
                                (throw (ex-info "Patch response exceeds expected size" {})))
                              (.write ^java.io.OutputStream output buffer 0 n)
                              (progress-fn received)
                              (recur received))))))
                    nil)

                  :else
                  (throw (ex-info (str "HTTP " status) {:status status}))))
              (finally (.disconnect connection)))]
        (when next-url
          (recur next-url (inc redirects)))))))

(defn check! [config platform]
  (loop [feed (:feed config) ceiling Long/MAX_VALUE remaining 100]
    (when (zero? remaining)
      (throw (ex-info "Patch history exceeds the supported update depth" {})))
    (let [out (ByteArrayOutputStream.)]
      (transfer! feed out 1048576 (fn [_]))
      (let [manifest (json/read-str (.toString out "UTF-8") :key-fn keyword)
            {:keys [schema channel revision version notes]} manifest
            delta (get-in manifest [:assets (keyword platform)])
            incremental (= (:revision config) (:base_revision delta))
            installer (get-in manifest [:installers (keyword platform)])
            asset (if incremental delta installer)]
        (when-not (and (= 1 schema)
                       (= (:channel config) channel)
                       (= (:repository config) (:repository manifest))
                       (pos-int? revision)
                       (< revision ceiling)
                       (string? version)
                       (not (string/blank? version))
                       (string? notes)
                       (map? (:assets manifest)))
          (throw (ex-info "Invalid patch manifest or wrong update channel" {})))
        (doseq [candidate [asset installer]
                :when candidate]
          (when-not (and (allowed-url? (:url candidate))
                         (string? (:sha256 candidate))
                         (re-matches #"[0-9a-f]{64}" (:sha256 candidate))
                         (pos-int? (:size candidate)))
            (throw (ex-info "Invalid patch package metadata" {}))))
        (if (and (> revision (:revision config))
                 (not incremental)
                 (nil? installer)
                 (:previous manifest))
          (recur (:previous manifest) revision (dec remaining))
          {:manifest manifest
           :asset asset
           :installer installer
           :kind (if incremental :patch :installer)
           :status (cond
                     (<= revision (:revision config)) :current
                     (nil? asset) :unsupported
                     :else :available)})))))

(defn download! [asset ^File directory progress-fn]
  (.mkdirs directory)
  (let [temporary (File/createTempFile "defold-patch-" ".part" directory)
        destination (io/file directory (str "Defold-patch-" (:sha256 asset) ".download"))]
    (try
      (with-open [output (io/output-stream temporary)]
        (transfer! (:url asset) output (:size asset) progress-fn))
      (when-not (= (:size asset) (.length temporary))
        (throw (ex-info "Patch download is incomplete" {})))
      (with-open [input (io/input-stream temporary)]
        (when-not (= (:sha256 asset) (DigestUtils/sha256Hex input))
          (throw (ex-info "Patch SHA-256 mismatch" {}))))
      (Files/move (.toPath temporary) (.toPath destination)
                  (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
      destination
      (finally
        (Files/deleteIfExists (.toPath temporary))))))

(defn prepare! [archive config manifest]
  (when-not (and (system/defold-resourcespath) (system/defold-version) system/mac?)
    (throw (ex-info "Install the macOS app bundle before applying patches. Development REPLs can only preview updates." {})))
  (let [app (.getCanonicalFile (io/file (system/defold-resourcespath) "../.."))]
    {:app app
     :staged (PatchInstaller/prepare archive app (:revision config) (:revision manifest))}))

(defn discard! [{:keys [^File staged]}]
  (when staged
    (PatchInstaller/deleteTree (.toPath staged))))

(defn restart! [{:keys [app staged]}]
  (let [directory (io/file (.toFile (Editor/getSupportPath)) "patches" (str "helper-" (UUID/randomUUID)))
        helper (io/file directory "com/defold/editor/PatchRestart.class")
        _ (io/make-parents helper)
        _ (with-open [input (io/input-stream (io/resource "com/defold/editor/PatchRestart.class"))]
            (io/copy input helper))
        command (into [(str (io/file (system/java-home) "bin/java"))
                       "-cp" (str directory) "com.defold.editor.PatchRestart"
                       (str (.pid (ProcessHandle/current))) (str app) (str staged)
                       (str (io/file directory "editor.log"))]
                      *command-line-args*)
        process (.start (doto (ProcessBuilder. ^java.util.List command)
                          (.redirectError (io/file directory "restart.log"))))
        ready (future (with-open [reader (io/reader (.getInputStream process))]
                        (.readLine ^java.io.BufferedReader reader)))]
    (when-not (= "READY" (deref ready 5000 nil))
      (.destroyForcibly process)
      (throw (ex-info (str "Could not start patch installer. See " (io/file directory "restart.log")) {})))
    (javafx.application.Platform/exit)))
