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

(ns editor.play-build-test
  (:require [cljfx.api]
            [clojure.test :refer [deftest is]]
            [dynamo.graph :as g]
            [editor.app-view :as app-view]
            [editor.build-errors-view :as build-errors-view]
            [editor.disk-availability :as disk-availability]
            [editor.future :as future]
            [editor.play-build :as play-build]
            [editor.targets :as targets]
            [editor.ui :as ui]
            [editor.workspace :as workspace]
            [internal.invalidate-counters :as ic])
  (:import [java.io File]
           [javafx.stage Stage]))

(defn- snapshot [platform]
  {:counters (ic/add-nodes (ic/make) [1] 1)
   :options [platform "engine-sha" false {} {} nil]
   :endpoints [(g/endpoint 1 :save-data) (g/endpoint 1 :resource-snapshot)]})

;; UI-only changes retain readiness; content, external files and resets
;; invalidate it.
(deftest content-invalidation
  (let [before (snapshot "x86_64-win32")]
    (is (play-build/current? before before))
    (is (not (play-build/current? nil before)))
    (is (play-build/current? before (update before :counters ic/bump [(g/endpoint 1 :selection)])))
    (doseq [endpoint (:endpoints before)]
      (is (not (play-build/current? before (update before :counters ic/bump [endpoint]))))))
  (let [before (snapshot "arm64-macos")]
    (is (not (play-build/current? before (update before :counters ic/full-invalidate))))
    (is (not (play-build/current? before (assoc before :endpoints [(g/endpoint 2 :save-data)]))))))

;; Windows, Intel Mac and Apple Silicon must not reuse another
;; engine/platform's result.
(deftest platform-and-options-isolation
  (doseq [platform ["x86_64-win32" "x86_64-macos" "arm64-macos"]]
    (let [before (snapshot platform)]
      (is (play-build/current? before before))
      (doseq [other ["x86_64-win32" "x86_64-macos" "arm64-macos"]
              :when (not= platform other)]
        (is (not (play-build/current? before (assoc-in before [:options 0] other)))))
      (doseq [[index value] [[1 "new-engine"] [2 true] [3 {:lint-code true}]
                             [4 {:build-server "http://localhost:9011"}] [5 "/custom/dmengine"]]]
        (is (not (play-build/current? before (assoc-in before [:options index] value))))))))

;; Multiple saves debounce, failed attempts do not loop, and close/waiting
;; Play suppress work.
(deftest background-debounce
  (let [first (snapshot "arm64-macos")
        second (update first :counters ic/bump [(g/endpoint 1 :save-data)])
        state (play-build/observe {} first 0)]
    (is (not (play-build/due? state first 999)))
    (is (play-build/due? state first 1000))
    (let [state (play-build/observe state second 900)]
      (is (not (play-build/due? state second 1000)))
      (is (play-build/due? state second 1900))
      (doseq [guard [{:attempt second} {:preparing :future} {:launching :future} {:closed true}]]
        (is (not (play-build/due? (merge state guard) second 5000)))))))

;; Deleting or replacing compiled resources or the engine prevents a stale
;; fast launch.
(deftest output-integrity
  (let [resource (File/createTempFile "play-resource" ".scriptc")
        engine (File/createTempFile "play-engine" ".bin")]
    (try
      (spit resource "compiled-resource")
      (spit engine "engine")
      (let [[mtime size] (play-build/file-stamp resource)
            result {:artifacts [{:resource resource :mtime mtime :size size}]
                    :engine {:dmengine engine}
                    ::play-build/engine-stamp (play-build/file-stamp engine)}]
        (is (play-build/files-valid? result))
        (spit resource "changed")
        (is (not (play-build/files-valid? result)))
        (is (not (play-build/files-valid? (assoc result :artifacts []))))
        (spit resource "compiled-resource")
        (.setLastModified resource mtime)
        (is (play-build/files-valid? result))
        (spit engine "different-engine")
        (is (not (play-build/files-valid? result)))
        (.delete resource)
        (is (not (play-build/files-valid? result))))
      (finally
        (.delete resource)
        (.delete engine)))))

(defn- with-preparation [f]
  ;; Control completion/error events at the asynchronous build boundary; the
  ;; separate integration suite exercises the real compiler and project graph.
  (let [state (atom {})
        current (atom (snapshot "arm64-macos"))
        builds (atom [])
        status (atom [])]
    #_{:clj-kondo/ignore [:discouraged-var]}
    (with-redefs-fn
      {#'app-view/play-build-state (constantly state)
       #'app-view/begin-task-progress! (constantly [(constantly nil) (constantly false)])
       #'app-view/show-play-build-status! (fn status! [_ value] (swap! status conj value))
       #'app-view/handle-build-results! (fn handle! [_ _ result] (nil? (:error result)))
       #'workspace/artifact-map (constantly {})
       #'play-build/snapshot (fn snapshot! [_ _ _] @current)
       #'app-view/async-build! (fn build! [& _]
                                 (let [result (future/make)]
                                   (swap! builds conj result)
                                   result))}
      (fn run-preparation-test! []
        (f {:state state :current current :builds builds :status status})))))

;; Play during a pending background build shares one task; only current
;; success becomes ready.
(deftest shared-preparation
  (with-preparation
    (fn check-shared! [{:keys [state current builds status]}]
      (let [first-task (#'app-view/prepare-play-build! 1 2 nil true)
            second-task (#'app-view/ensure-play-build! 1 2 nil true)
            result {::play-build/snapshot @current :project-build-successful true}]
        (is (identical? first-task second-task))
        (is (= 1 (count @builds)))
        (future/complete! (first @builds) result)
        (is (= result @first-task))
        (is (= result (:ready @state)))
        (is (nil? (:preparing @state)))
        (is (= [:preparing :ready] @status))))))

;; A save during compilation cannot promote old resources, and failed builds
;; discard readiness.
(deftest changed-and-failed-preparation
  (doseq [failure [false true]]
    (with-preparation
      (fn check-rejected! [{:keys [state current builds status]}]
        (let [before @current
              task (#'app-view/prepare-play-build! 1 2 nil true)]
          (swap! current update :counters ic/bump [(g/endpoint 1 :save-data)])
          (future/complete! (first @builds)
                            (cond-> {::play-build/snapshot before}
                              failure (assoc :error :compile-error)))
          @task
          (is (nil? (:ready @state)))
          (is (= (if failure :failed :changed) (last @status)))
          (is (nil? (:preparing @state))))))))

;; Exceptional completion releases the task so the next explicit Play can
;; retry.
(deftest preparation-exception-and-close
  (with-preparation
    (fn check-exception! [{:keys [state builds]}]
      (let [task (#'app-view/prepare-play-build! 1 2 nil true)]
        (future/fail! (first @builds) (ex-info "build failed" {}))
        (is (thrown? Exception @task))
        (is (nil? (:preparing @state)))
        (let [retry (#'app-view/prepare-play-build! 1 2 nil true)]
          (swap! state assoc :closed true)
          (future/complete! (second @builds) {})
          @retry
          (is (nil? (:ready @state)))
          (is (= 2 (count @builds))))))))

;; Cached launch validates files without invoking any build; missing files
;; re-enter preparation.
(deftest reuse-or-repair
  (doseq [valid [true false]]
    (with-preparation
      (fn check-reuse! [{:keys [state current builds]}]
        (let [ready {::play-build/snapshot @current}]
          (swap! state assoc :ready ready)
          #_{:clj-kondo/ignore [:discouraged-var]}
          (with-redefs [play-build/files-valid? (constantly valid)]
            (let [task (ui/run-now (#'app-view/ensure-play-build! 1 2 nil true))]
              (if valid
                (do (is (= ready (deref task 10000 :timeout)))
                    (is (= 0 (count @builds))))
                (let [started (promise)
                      watch-key ::started]
                  (add-watch builds watch-key (fn started! [_ _ _ value] (deliver started (first value))))
                  (when-let [build (first @builds)] (deliver started build))
                  (try
                    (let [build (deref started 10000 :timeout)]
                      (is (not= :timeout build))
                      (when (not= :timeout build)
                        (ui/run-now (future/complete! build ready))
                        (is (= ready (deref task 10000 :timeout)))))
                    (finally (remove-watch builds watch-key))))))))))))

;; Repeated Play shares a single launch, and edits during preparation never
;; launch stale code.
(deftest launch-coalescing-and-stale-guard
  (doseq [edited [false true]]
    (with-preparation
      (fn check-launch! [{:keys [current builds]}]
        (let [stage (ui/run-now (Stage.))
              launches (atom 0)
              before @current]
          #_{:clj-kondo/ignore [:discouraged-var]}
          (with-redefs-fn
            {#'app-view/make-render-build-error (constantly (constantly nil))
             #'app-view/show-console! (constantly nil)
             #'app-view/launch-built-project! (fn launch! [& _]
                                                (swap! launches inc)
                                                {:target :started})
             #'build-errors-view/clear-build-errors (constantly nil)
             #'disk-availability/available? (constantly true)
             #'targets/selected-target (constantly nil)
             #'workspace/project-directory (constantly (File. "."))}
            (fn run-launch-test! []
              (let [task (ui/run-now (#'app-view/build-handler 1 2 nil nil nil stage nil true false))
                    repeated (ui/run-now (#'app-view/build-handler 1 2 nil nil nil stage nil true false))]
                (is (identical? task repeated))
                (is (= 1 (count @builds)))
                (when edited
                  (swap! current update :counters ic/bump [(g/endpoint 1 :save-data)]))
                (ui/run-now (future/complete! (first @builds) {::play-build/snapshot before :engine {:dmengine "engine"}}))
                (let [result (deref task 10000 :timeout)]
                  (is (not= :timeout result))
                  (is (= (if edited 0 1) @launches))
                  (is (= edited (some? (:error result)))))))))))))
