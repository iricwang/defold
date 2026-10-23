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

(ns integration.skeletal-animation-test
  (:require [clojure.test :refer [deftest is testing]]
            [dynamo.graph :as g]
            [editor.resource :as resource]
            [editor.skeletal-animation :as skeletal-animation]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-view :as skeletal-view]
            [editor.ui :as ui]
            [editor.workspace :as workspace]
            [integration.test-util :as test-util])
  (:import [javafx.event Event]
           [javafx.scene Scene]
           [javafx.scene.control Tab]
           [javafx.scene.layout AnchorPane]))

;; New skeleton documents must be discoverable from File > New and load as a
;; native resource without adding runtime build dependencies to the game.
(deftest skeleton-resource-template-test
  (test-util/with-scratch-project "test/resources/empty_project"
    (let [resource-type (workspace/get-resource-type workspace "skeleton")
          template (workspace/template workspace resource-type)
          document (data/read-json (workspace/replace-template-name template "hero"))
          source (test-util/make-resource! workspace "/hero.skeleton" document)]
      (is (workspace/has-template? workspace resource-type))
      (is (:editor-openable resource-type))
      (is (= "hero" (:name document)))
      (workspace/resource-sync! workspace)
      (let [node (test-util/resource-node project "/hero.skeleton")]
        (is (g/node-instance? skeletal-animation/SkeletalAnimationNode node))
        (is (= document (g/node-value node :document)))
        (is (= document (g/node-value node :save-value)))
        (is (false? (g/node-value node :dirty)))
        (is (= [] (g/node-value node :build-targets)))
        (is (= "skeleton" (resource/type-ext source)))))))

;; Bone edits participate in the editor's global undo history and save system;
;; save/reload must preserve the entire document, including future metadata.
(deftest skeleton-save-undo-redo-test
  (test-util/with-scratch-project "test/resources/empty_project"
    (let [document (assoc (data/new-document "hero") :custom-metadata {:reviewed true})
          source (test-util/make-resource! workspace "/hero.skeleton" document)]
      (workspace/resource-sync! workspace)
      (let [node (test-util/resource-node project "/hero.skeleton")
            edited (assoc-in document [:bones 0 :rotation] 45.0)]
        (g/reset-undo! :undo/global)
        (g/transact [(g/operation-label "Rotate skeleton bone")
                     (g/set-property node :document edited)])
        (is (= #{"/hero.skeleton"} (test-util/dirty-proj-paths project)))
        (g/undo! :undo/global)
        (is (= document (g/node-value node :document)))
        (is (false? (g/node-value node :dirty)))
        (g/redo! :undo/global)
        (is (= edited (g/node-value node :document)))
        (test-util/save-project! project)
        (is (false? (g/node-value node :dirty)))
        (is (= edited (data/read-json (slurp source))))
        (test-util/write-file-resource! workspace "/reloaded.skeleton" edited)
        (workspace/resource-sync! workspace)
        (is (= edited (g/node-value (test-util/resource-node project "/reloaded.skeleton") :document)))))))

;; Malformed skeleton data must stay isolated as a resource error. Correcting
;; the file should restore editing without recreating the editor project.
(deftest skeleton-malformed-resource-recovery-test
  (test-util/with-scratch-project "test/resources/empty_project"
    (let [source (test-util/make-resource! workspace "/hero.skeleton" (data/new-document "hero"))]
      (spit source "{ invalid JSON")
      (workspace/resource-sync! workspace)
      (testing "The broken authoring file is isolated"
        (let [node (test-util/resource-node project "/hero.skeleton")
              search-value-fn (:search-value-fn (workspace/get-resource-type workspace "skeleton"))]
          (is (g/error-value? (g/node-value node :save-value)))
          (is (g/error-value? (search-value-fn node source (g/make-evaluation-context))))))
      (testing "Correcting the source restores the resource"
        (test-util/write-file-resource! workspace "/hero.skeleton" (data/new-document "recovered"))
        (workspace/resource-sync! workspace)
        (let [node (test-util/resource-node project "/hero.skeleton")]
          (is (= "recovered" (:name (g/node-value node :document))))
          (is (false? (g/node-value node :dirty))))))))

;; Construct and repaint the registered native view, including a setup-only
;; document with an empty attachment, without creating edits or undo entries.
(deftest native-view-construction-test
  (test-util/with-scratch-project "test/resources/empty_project"
    (g/transact {:undoable false} (skeletal-view/register-view-types workspace))
    (test-util/make-png-resource! workspace "/images/body.png")
    (test-util/make-png-resource! workspace "/images/limb.png")
    (doseq [document [(data/new-document "default")
                     (-> (data/new-document "setup-only")
                         (assoc :animations [])
                         (data/add-slot {:id "empty" :bone "root"}))
                     (assoc (data/new-document "empty") :bones [] :animations [])
                     (data/read-json (slurp "examples/skeletal-animation/puppet.skeleton"))]]
      (test-util/make-resource! workspace "/view.skeleton" document)
      (workspace/resource-sync! workspace)
      (let [node (test-util/resource-node project "/view.skeleton")
            make-view (:make-view-fn (workspace/get-view-type workspace :skeletal-animation))]
        (g/reset-undo! :undo/global)
        (ui/run-now
          (let [parent (AnchorPane.)
                _ (Scene. parent 1100.0 700.0)
                tab (Tab.)
                view-node (make-view parent node {:workspace workspace :localization test-util/localization :tab tab})
                root (first (.getChildren parent))
                context (ui/user-data root :editor.skeletal-animation-view/context)]
            (try
              (.applyCss parent)
              (.layout parent)
              (#'skeletal-view/refresh! context 0.0)
              (swap! (:state context) assoc :mode "animate" :time 1.0)
              (#'skeletal-view/refresh! context 1.0)
              (is (some? (.lookup parent "#skeletal-canvas")))
              (is (some? (.lookup parent "#skeletal-timeline")))
              (is (= document (g/node-value node :document)))
              (is (false? (g/node-value node :dirty)))
              (is (not (g/has-undo? :undo/global)))
              (finally
                (Event/fireEvent tab (Event. Tab/CLOSED_EVENT))
                (g/transact {:undoable false} (g/delete-node view-node))))))))))
