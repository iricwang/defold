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

(ns editor.skeletal-animation-spine-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [editor.fs :as fs]
            [editor.process :as process]
            [editor.skeletal-animation-spine :as spine]))

;; A CLI failure must not create the destination; an existing project must
;; never be replaced, even when a native export has otherwise succeeded.
(deftest native-write-staging-and-no-overwrite-test
  (let [directory (fs/create-temp-directory! "spine-native-write-test")
        target (io/file directory "character.spine")
        skeletons [{:name "one" :source {"bones" []}}]]
    (try
      (with-redefs [spine/run-cli! (fn [_ _ _] (throw (ex-info "conversion failed" {})))]
        (is (thrown? clojure.lang.ExceptionInfo (spine/write-project! skeletons target {:timeout-seconds 1})))
        (is (not (.exists target))))
      (spit target "original native project")
      (with-redefs [spine/run-cli! (fn [_ _ _] (throw (AssertionError. "Must not invoke CLI for an existing destination")))]
        (is (thrown? clojure.lang.ExceptionInfo (spine/write-project! skeletons target {:timeout-seconds 1})))
        (is (= "original native project" (slurp target))))
      (finally (fs/delete-directory! directory {:fail :silently})))))

;; Multi-skeleton projects are staged as one file. Skeleton names are passed as
;; literal arguments, including whitespace and shell characters, never paths.
(deftest native-multiple-skeleton-write-test
  (let [directory (fs/create-temp-directory! "spine-native-multi-test")
        target (io/file directory "new.spine")
        names ["hero body" "../$(echo unexpected)"]
        calls (atom [])]
    (try
      (with-redefs [spine/run-cli! (fn [_ _ arguments]
                                   (swap! calls conj arguments)
                                   (spit (nth arguments 3) "staged native bytes"))]
        (is (= (.getCanonicalFile target) (spine/write-project! (mapv #(hash-map :name % :source {}) names) target {:timeout-seconds 1})))
        (is (= names (mapv peek @calls)))
        (is (= "staged native bytes" (slurp target)))
        (is (not= (str target) (get-in @calls [0 3]))))
      (finally (fs/delete-directory! directory {:fail :silently})))))

;; Successful exit without an output is not success, and duplicate names must
;; not accidentally merge or replace skeletons in the destination project.
(deftest native-missing-output-and-duplicate-names-test
  (let [directory (fs/create-temp-directory! "spine-native-empty-test")
        target (io/file directory "new.spine")]
    (try
      (with-redefs [spine/run-cli! (fn [_ _ _] nil)]
        (is (thrown? clojure.lang.ExceptionInfo (spine/write-project! [{:name "a" :source {}}] target {:timeout-seconds 1})))
        (is (thrown? clojure.lang.ExceptionInfo (spine/write-project! [{:name "a" :source {}} {:name "a" :source {}}] target {:timeout-seconds 1})))
        (is (not (.exists target))))
      (finally (fs/delete-directory! directory {:fail :silently})))))

;; Native reads request nonessential editing data and return all skeletons in
;; stable order; paths still belong to the source project, not staging.
(deftest native-read-all-skeletons-test
  (let [directory (fs/create-temp-directory! "spine-native-read-test")
        source (io/file directory "source.spine")
        settings (atom nil)]
    (try
      (spit source "original")
      (with-redefs [spine/run-cli! (fn [_ _ arguments]
                                   (reset! settings (json/read-str (slurp (nth arguments 5))))
                                   (doseq [name ["z" "a"]]
                                     (spit (io/file (nth arguments 3) (str name ".json"))
                                           (json/write-str {"skeleton" {"spine" "4.2.43" "images" "images/"} "bones" []}))))]
        (let [result (spine/read-project! source {:timeout-seconds 1})]
          (is (= ["a" "z"] (mapv :name result)))
          (is (true? (get @settings "nonessential")))
          (is (= "images/" (get-in result [0 :source "skeleton" "images"])))
          (is (= "original" (slurp source)))))
      (finally (fs/delete-directory! directory {:fail :silently})))))

;; JavaFX's CFProcessPath points at Defold. Passing it to Spine makes Cocoa
;; wait for GUI events, so the external converter must receive a clean value.
(deftest native-process-does-not-inherit-editor-launcher-test
  (let [directory (fs/create-temp-directory! "spine-native-env-test")
        options (atom nil)
        child (proxy [Process] []
                (getOutputStream [] (java.io.ByteArrayOutputStream.))
                (waitFor ([] 0) ([_ _] true))
                (exitValue [] 0)
                (isAlive [] false))]
    (try
      (with-redefs [process/start! (fn [opts & _] (reset! options opts) child)]
        (#'spine/run-cli! {:executable "Spine" :version "4.2.43" :timeout-seconds 1} directory ["--help"]))
      (is (contains? (:env @options) "CFProcessPath"))
      (is (nil? (get-in @options [:env "CFProcessPath"])))
      (is (nil? (get-in @options [:env "JAVA_TOOL_OPTIONS"])))
      (finally (fs/delete-directory! directory {:fail :silently})))))
