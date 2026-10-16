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

(ns integration.craft-project-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [dynamo.graph :as g]
            [editor.app-view :as app-view]
            [editor.craft-project :as craft-project]
            [editor.resource :as resource]
            [editor.workspace :as workspace]
            [integration.test-util :as test-util]
            [util.digest :as digest]))

;; Snapshotting a scene that has no editor tab must not create a tab or an undo entry.
(deftest gui-snapshot-does-not-open-or-select-a-tab
  (test-util/with-temp-project-content {"/test.gui" {:material "/builtins/materials/gui.material"}}
    (let [service {:project project :id "snapshot-test" :root (workspace/project-directory workspace)}
          tabs (g/node-value app-view :open-views)
          history (g/undo-stack-revisions)
          snapshot (craft-project/snapshot service "/test.gui")]
      (is (= "/test.gui" (:path snapshot)))
      (is (string? (:source snapshot)))
      (is (re-matches #"sha256:[0-9a-f]{64}" (:diskHash snapshot)))
      (is (= (craft-project/scene-hash (:source snapshot)) (:sceneHash snapshot)))
      (is (= (str "sha256:" (digest/string->sha256-hex (:source snapshot))) (:editorGraphRevision snapshot)))
      (is (= tabs (g/node-value app-view :open-views)))
      (is (= history (g/undo-stack-revisions))))))

;; Explicit reveal permits only existing native project resources and never external open plans.
(deftest reveal-resource-is-scoped-to-native-project-tabs
  (test-util/with-temp-project-content {"/test.gui" {:material "/builtins/materials/gui.material"}}
    (let [service {:project project :app-view app-view :root (workspace/project-directory workspace)}
          opened (atom [])
          plan-type (atom :open-new-tab)]
      (spit (io/file (:root service) "unknown.unsafe") "not a Defold asset")
      (workspace/resource-sync! workspace)
      (with-redefs [app-view/make-open-resource-plan (fn [_ _ actual-project asset opts _]
                                                     (is (= project actual-project))
                                                     (is (= {:use-custom-editor false} opts))
                                                     {:type @plan-type :path (resource/proj-path asset)})
                    app-view/perform-open-resource-plan! (fn [plan _] (swap! opened conj (:path plan)) true)]
        (is (= {:schemaVersion 1 :path "/test.gui" :opened true} (craft-project/open-resource! service "/test.gui")))
        (doseq [[path code] [["/missing.gui" "RESOURCE_MISSING"]
                            ["/.internal/editor.token" "PATH_INVALID"]
                            ["/../test.gui" "PATH_INVALID"]
                            ["C:/test.gui" "PATH_INVALID"]
                            ["/unknown.unsafe" "UNSUPPORTED_GUI_FEATURE"]]]
          (is (= code (try (craft-project/open-resource! service path)
                           (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))))
        (doseq [external-plan [:os-open :os-execute :resource-error]]
          (reset! plan-type external-plan)
          (is (= "UNSUPPORTED_GUI_FEATURE" (try (craft-project/open-resource! service "/test.gui")
                                                (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))))
        (is (= ["/test.gui"] @opened))))))
