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

(ns editor.engine.local-extensions
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [editor.process :as process]
            [util.coll :as coll])
  (:import [java.io File]
           [java.lang ProcessBuilder$Redirect]
           [java.net ConnectException HttpURLConnection URI]))

(set! *warn-on-reflection* true)

(defonce ^:private startup-lock (Object.))

(defn- profile-file [url platform directory]
  (when (= "x86_64-win32" platform)
    (let [uri (URI. url)]
      (when (and (= "http" (.getScheme uri))
                 (= "127.0.0.1" (.getHost uri))
                 (pos? (.getPort uri))
                 (contains? #{"" "/"} (.getPath uri))
                 (nil? (.getUserInfo uri))
                 (nil? (.getQuery uri))
                 (nil? (.getFragment uri)))
        (io/file directory (str (.getPort uri) ".json"))))))

(defn- read-profile [url sdk platform directory]
  (when-let [^File file (profile-file url platform directory)]
    (when (.isFile file)
      (let [{:keys [version command directory] :as profile} (json/read-str (slurp file) :key-fn keyword)]
        (when-not (and (= 1 version)
                       (= sdk (:sdk profile))
                       (= platform (:platform profile))
                       (contains? #{(:url profile) (str (:url profile) "/")} url)
                       (vector? command)
                       (coll/not-empty command)
                       (coll/every? string? command)
                       (.isFile (io/file (first command)))
                       (string? directory)
                       (.isDirectory (io/file directory)))
          (throw (ex-info (str "Local extension builder configuration is stale or invalid: " file
                               ". Register the matching SDK with extensions-serve.") {})))
        profile))))

(defn- healthy? [url sdk platform]
  (let [^HttpURLConnection connection (.openConnection (.toURL (URI. (str url "/health"))))]
    (try
      (.setConnectTimeout connection 750)
      (.setReadTimeout connection 1500)
      (.setInstanceFollowRedirects connection false)
      (with-open [reader (io/reader (.getInputStream connection))]
        (let [health (json/read reader :key-fn keyword)]
          (when-not (and (= sdk (:sdk health)) (= platform (:platform health)))
            (throw (ex-info (str "Local extension builder SDK/platform mismatch at " url) {})))
          true))
      (catch ConnectException _ false)
      (finally (.disconnect connection)))))

(defn- await-server! [^Process child url sdk platform ^File log-file]
  (let [deadline (+ (System/nanoTime) 30000000000)]
    (loop []
      (when-not (healthy? url sdk platform)
        (when (or (not (.isAlive child)) (>= (System/nanoTime) deadline))
          (throw (ex-info (str "Local extension builder failed to start at " url ". See " log-file) {})))
        (Thread/sleep 100)
        (recur)))))

(defn ensure-server!
  "Start only an explicitly registered local SDK, on the build worker thread.
  Cached engines and unregistered/remote servers do not need this service."
  [url sdk platform registry-directory]
  (when-let [{:keys [command directory] canonical-url :url} (read-profile url sdk platform registry-directory)]
    (locking startup-lock
      (when-not (healthy? canonical-url sdk platform)
        (let [log-file (io/file registry-directory "startup.log")
              ^Process child (apply process/start! {:dir directory
                                                    :out (ProcessBuilder$Redirect/appendTo log-file)
                                                    :err :stdout}
                                    command)]
          (try
            (await-server! child canonical-url sdk platform log-file)
            (catch Exception error
              (.destroy child)
              (throw error))))))))
