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

(ns editor.engine.local-extensions-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [editor.engine.local-extensions :as local-extensions]
            [editor.os :as os]
            [editor.process :as process])
  (:import [java.net ServerSocket URI]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(set! *warn-on-reflection* true)

(defn- fixture []
  (let [port (with-open [socket (ServerSocket. 0)] (.getLocalPort socket))
        directory (.toFile (Files/createTempDirectory "local-extension-test" (make-array FileAttribute 0)))
        url (str "http://127.0.0.1:" port)
        python (or (System/getProperty "test.python")
                   (string/trim (process/exec! (if (os/is-win32?) "python" "python3")
                                               "-c" "import sys; print(sys.executable)")))
        profile {:version 1 :url url :sdk "test-sdk" :platform "x86_64-win32"
                 :directory (.getAbsolutePath directory)
                 :command [python "-u"
                           (.getAbsolutePath (io/file "test/resources/local_extension_builder.py"))
                           (str port) "test-sdk"]}]
    (spit (io/file directory (str port ".json")) (json/write-str profile))
    {:port port :directory directory :url url :profile profile}))

(defn- request [url path]
  (let [connection (.openConnection (.toURL (URI. (str url path))))]
    (.setConnectTimeout connection 1000)
    (.setReadTimeout connection 1000)
    (with-open [stream (.getInputStream connection)] (slurp stream))))

;; Without Remote Manager, concurrent requests must start and share one service.
(deftest independent-concurrent-start-test
  (let [{:keys [directory url]} (fixture)
        start (fn start-builder [] (local-extensions/ensure-server! url "test-sdk" "x86_64-win32" directory))]
    (try
      (let [first-start (future (start))
            second-start (future (start))]
        (is (nil? (deref first-start 45000 :timeout)))
        (is (nil? (deref second-start 45000 :timeout))))
      (is (= "test-sdk" (:sdk (json/read-str (request url "/health") :key-fn keyword))))
      (is (nil? (start)))
      (is (= "started\n" (slurp (io/file directory "starts.txt"))))
      (finally (request url "/shutdown")))))

;; Unrelated servers and platforms must not start helpers.
(deftest unrelated-server-test
  (let [{:keys [directory url]} (fixture)]
    (is (nil? (local-extensions/ensure-server! "https://build.defold.com" "test-sdk" "x86_64-win32" directory)))
    (is (nil? (local-extensions/ensure-server! url "test-sdk" "arm64-macos" directory)))
    (is (nil? (local-extensions/ensure-server! "http://127.0.0.1:1" "test-sdk" "x86_64-win32" directory)))
    (is (not (.exists (io/file directory "starts.txt"))))))

;; Stale SDK registrations fail explicitly before launching the wrong compiler.
(deftest stale-sdk-test
  (let [{:keys [directory url]} (fixture)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"stale or invalid"
                          (local-extensions/ensure-server! url "other-sdk" "x86_64-win32" directory)))
    (is (not (.exists (io/file directory "starts.txt"))))))

;; Startup failures must retain a useful log path instead of an empty error.
(deftest startup-failure-test
  (let [{:keys [directory url port profile]} (fixture)]
    (spit (io/file directory (str port ".json"))
          (json/write-str (assoc profile :command [(first (:command profile)) "-c" "raise RuntimeError('SDK unavailable')"])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"failed to start.*startup.log"
                          (local-extensions/ensure-server! url "test-sdk" "x86_64-win32" directory)))
    (is (re-find #"SDK unavailable" (slurp (io/file directory "startup.log"))))))

;; An occupied endpoint serving a different SDK must not be reused or replaced.
(deftest live-sdk-mismatch-test
  (let [{:keys [directory url port profile]} (fixture)]
    (try
      (local-extensions/ensure-server! url "test-sdk" "x86_64-win32" directory)
      (spit (io/file directory (str port ".json")) (json/write-str (assoc profile :sdk "other-sdk")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"SDK/platform mismatch"
                            (local-extensions/ensure-server! url "other-sdk" "x86_64-win32" directory)))
      (is (= "test-sdk" (:sdk (json/read-str (request url "/health") :key-fn keyword))))
      (finally (request url "/shutdown")))))
