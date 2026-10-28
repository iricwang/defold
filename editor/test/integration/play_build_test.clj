;; Copyright 2020-2026 The Defold Foundation
;; Copyright 2014-2020 King
;; Copyright 2009-2014 Ragnar Svensson, Christian Murray
;; Licensed under the Defold License version 1.0 (the "License"); you may
;; not use
;; this file except in compliance with the License.
;;
;; You may obtain a copy of the License, together with FAQs at
;; https://www.defold.com/license
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed
;; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
;; CONDITIONS OF ANY KIND, either express or implied. See the License for the
;; specific language governing permissions and limitations under the License.

(ns integration.play-build-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dynamo.graph :as g]
            [editor.app-view :as app-view]
            [editor.defold-project :as project]
            [editor.disk :as disk]
            [editor.play-build :as play-build]
            [editor.ui :as ui]
            [editor.workspace :as workspace]
            [integration.test-util :as test-util]
            [support.test-support :refer [with-clean-system]])
  (:import [javafx.scene Scene]
           [javafx.scene.control Button Label ProgressBar]
           [javafx.scene.layout HBox VBox]
           [javafx.stage Stage]))

(defn install-progress-ui! []
  ;; A real, hidden status bar lets release AOT code run without replacing Vars.
  ;; Direct-linked calls deliberately bypass with-redefs in the release build.
  (ui/run-now
    (let [root (doto (VBox.) (.setId "status-bar"))
          scene (Scene. root)
          stage (doto (Stage.) (.setScene scene))]
      (.addAll (.getChildren root)
               ^java.util.Collection [(doto (ProgressBar.) (.setId "progress-bar"))
                                      (doto (HBox.) (.setId "progress-hbox"))
                                      (doto (Label.) (.setId "progress-percentage-label"))
                                      (doto (Label.) (.setId "status-label"))
                                      (doto (Button.) (.setId "progress-cancel-button"))])
      (ui/user-data! scene :localization test-util/localization)
      (ui/set-main-stage stage)
      stage)))

(defn- await-build [task]
  (let [result (deref task 120000 ::timeout)]
    (is (not= ::timeout result))
    (is (nil? (:error result)) (pr-str (:error result)))
    result))

;; Real project graph and compiled artifacts: cached Play skips the
;; pipeline, then
;; external edits and deleted outputs force preparation again on the host
;; platform.
(deftest real-project-preparation-and-reuse
  (with-clean-system
    (let [workspace (test-util/setup-scratch-workspace! "test/resources/small_project")
          project (test-util/setup-project! workspace)
          prefs (test-util/make-test-prefs)
          builds (atom 0)
          state (ui/run-now (#'app-view/play-build-state project))
          stage (install-progress-ui!)]
      (add-watch state ::build-count
                 (fn count-build! [_ _ old value]
                   (when (and (:preparing value)
                              (not (identical? (:preparing old) (:preparing value))))
                     (swap! builds inc))))
      (try
        (let [started (System/nanoTime)
              result (await-build (ui/run-now (#'app-view/prepare-play-build! project workspace prefs false)))
              first-ms (/ (- (System/nanoTime) started) 1e6)]
          (is (pos? (count (:artifacts result))))
          (is (play-build/current? (::play-build/snapshot result) (play-build/snapshot project prefs false)))
          (is (play-build/files-valid? result))
          (testing "Unchanged resources reuse preparation without entering async-build"
            (let [started (System/nanoTime)
                  reused (await-build (ui/run-now (#'app-view/ensure-play-build! project workspace prefs false)))
                  reuse-ms (/ (- (System/nanoTime) started) 1e6)]
              (is (= (:artifact-map result) (:artifact-map reused)))
              (is (identical? result reused))
              (is (= 1 @builds))
              (println "PLAY_BUILD_TIMING" {:prepare-ms first-ms :reuse-ms reuse-ms :artifacts (count (:artifacts result))})))
          (testing "Removing an output triggers repair"
            (let [file (io/as-file (:resource (first (:artifacts result))))]
              (is (.delete file))
              (let [repaired (await-build (ui/run-now (#'app-view/ensure-play-build! project workspace prefs false)))]
                (is (play-build/files-valid? repaired))
                (is (= 2 @builds)))))
          (testing "External resource changes invalidate readiness"
            (let [before (play-build/snapshot project prefs false)
                  file (io/file (workspace/project-directory workspace) "main/main.collection")]
              (spit file (str (slurp file) "\n"))
              (ui/run-now (workspace/resource-sync! workspace))
              (is (not (play-build/current? before (play-build/snapshot project prefs false))))
              (await-build (ui/run-now (#'app-view/ensure-play-build! project workspace prefs false)))
              (is (= 3 @builds))))
          (testing "Dirty edits wait for save, then background preparation runs once"
            (let [node (project/get-resource-node project "/main/main.collection")
                  before (play-build/snapshot project prefs false)
                  saved (promise)]
              (ui/run-now (g/transact (g/set-property node :name "saved-preview")))
              (is (not (play-build/current? before (play-build/snapshot project prefs false))))
              (ui/run-now
                (#'app-view/background-play-build! project prefs 0)
                (#'app-view/background-play-build! project prefs 1001))
              (is (= 3 @builds) "Unsaved edits must not trigger automatic builds")
              (ui/run-now
                (disk/async-save! (constantly nil) (constantly nil) project/dirty-save-data project nil
                                  (fn saved! [success] (deliver saved success))))
              (is (true? (deref saved 10000 :timeout)))
              (ui/run-now
                (#'app-view/background-play-build! project prefs 2000)
                (#'app-view/background-play-build! project prefs 3001))
              (let [state @(#'app-view/play-build-state project)
                    result (if-let [task (:preparing state)] (await-build task) (:ready state))]
                (is (play-build/files-valid? result))
                (is (= 4 @builds))))))
        (finally
          (remove-watch state ::build-count)
          (ui/run-now (.close stage)))))))
