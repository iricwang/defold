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

(ns integration.collection-animation-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [dynamo.graph :as g]
            [editor.gui-animation-data :as animation]
            [editor.gui-animation-properties :as properties]
            [editor.pose :as pose]
            [editor.scene :as scene]
            [integration.test-util :as test-util]
            [util.coll :as coll])
  (:import [javax.vecmath Matrix4d]))

;; GO exports must select create_go while existing GUI callback templates remain unchanged.
(deftest callback-target-test
  (let [go-document (animation/new-document "/main.collection")
        gui-document (animation/new-document "/main.gui")]
    (is (= "/main.collection" (get-in go-document [:data :metadata :gui_path])))
    (is (string/includes? (animation/callback-template go-document "default" "main_panthera") "create_go_animation(animation_data)"))
    (is (string/includes? (animation/callback-template gui-document "default" "main_panthera") "panthera.create_gui(animation_data)"))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"position, rotation and scale only"
                         (properties/go-node-overrides nil {"color_a" 0.5})))))

;; Parent and child preview transforms must compose locally without changing scene resources.
(deftest collection-local-transform-preview-test
  (test-util/with-loaded-project
    (let [node (test-util/resource-node project "/logic/hierarchy.collection")
          ids (g/node-value node :go-inst-ids)
          parent (get ids "parent_id")
          child (get ids "child_id")
          original (g/node-value node :save-value)
          source (g/node-value node :scene)
          overrides {parent (properties/go-node-overrides parent {"position_x" 10.0 "rotation_z" 90.0 "scale_x" 2.0 "scale_y" 2.0 "scale_z" 2.0})
                     child (properties/go-node-overrides child {"position_y" 5.0 "scale_y" 0.5})}
          flattened (#'scene/flatten-scene source overrides #{} #{} #{} (doto (Matrix4d.) (.setIdentity)))
          renderables (into [] cat (coll/vals (:renderables flattened)))
          matrix ^Matrix4d (:world-transform (coll/first-where #(= child (:node-id %)) renderables))]
      (let [nested (test-util/resource-node project "/collection/sub_props.collection")]
        (is (pos? (count (g/node-value nested :go-inst-ids))))
        (is (= (g/node-value nested :go-inst-ids) (properties/collection-node-ids nested))))
      (is (= (set properties/go-channels) (set (properties/numeric-channels parent))))
      (is (= [100.0 5.0 0.0] (:position (get overrides child))))
      (is (= [1.0 0.5 1.0] (:scale (get overrides child))))
      (is (< (Math/abs (.m03 matrix)) 1.0e-8))
      (is (< (Math/abs (- 200.0 (.m13 matrix))) 1.0e-8))
      (is (= original (g/node-value node :save-value)))
      (is (= 100.0 (get (properties/node-values child) "position_x")))
      (is (= 0.0 (get (properties/node-values parent) "rotation_z")))
      (let [restored (#'scene/flatten-scene source {} #{} #{} #{} (doto (Matrix4d.) (.setIdentity)))
            renderables (into [] cat (coll/vals (:renderables restored)))
            matrix ^Matrix4d (:world-transform (coll/first-where #(= child (:node-id %)) renderables))]
        (is (= 100.0 (.m03 matrix)))
        (is (= 0.0 (.m13 matrix)))))))

;; Nested Collection transforms must be baked once, while GO children retain their local poses.
(deftest nested-collection-preview-test
  (test-util/with-loaded-project
    (let [node (test-util/resource-node project "/collection/animation_nested.collection")
          ids (properties/collection-node-ids node)
          source (g/node-value node :scene)
          original (g/node-value node :save-value)
          context (properties/collection-context source ids)
          compiled (into {} (map (fn [data] [(get-in data [:instance-msg :id]) (:pose data)]))
                         (:game-object-instance-datas (first (g/node-value node :build-targets))))
          overrides (properties/collection-preview-overrides context {})
          flattened (#'scene/flatten-scene source overrides #{} #{} #{} (doto (Matrix4d.) (.setIdentity)))
          renderables (into [] cat (coll/vals (:renderables flattened)))]
      (is (= 6 (count ids)))
      (is (= 6 (count (set (into [] (map second) ids)))))
      (doseq [[name id] ids]
        (is (= (compiled name) (:pose (context id))) name))
      (doseq [prefix ["left/branch/" "right/branch/"]]
        (let [parent-name (str prefix "parent_id")
              child-name (str prefix "child_id")
              parent (ids parent-name)
              child (ids child-name)
              expected (doto (Matrix4d. (pose/matrix (compiled parent-name)))
                         (.mul (pose/matrix (compiled child-name))))
              actual ^Matrix4d (:world-transform (coll/first-where #(= child (:node-id %)) renderables))]
          (is (:runtime-root (context parent)))
          (is (not (:runtime-root (context child))))
          (is (.epsilonEquals expected actual 1.0e-8) (str "Expected " expected "actual " actual))
          (is (= [100.0 0.0 0.0] (:position (overrides child))))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"position, rotation and scale only"
                           (properties/collection-preview-overrides context {(val (first ids)) {"color_a" 0.5}})))
      (is (= original (g/node-value node :save-value))))))
