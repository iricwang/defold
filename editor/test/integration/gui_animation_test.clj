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

(ns integration.gui-animation-test
  (:require [clojure.test :refer [deftest is]]
            [dynamo.graph :as g]
            [editor.gui-animation-data :as animation]
            [editor.gui-animation-preview :as preview]
            [editor.gui-animation-properties :as channels]
            [integration.test-util :as test-util]
            [util.coll :as coll]))

(defn- scene-node [scene node-id]
  (if (= node-id (:node-id scene)) scene
    (reduce (fn [_ child] (when-let [found (scene-node child node-id)] (reduced found))) nil (:children scene))))

;; All scalar and trigger channels must render against a disposable graph, without changing GUI data or undo history.
(deftest gui-animation-graph-preview-test
  (test-util/with-loaded-project "test/resources/gui_animation_project"
    (let [resource (test-util/resource-node project "/features.gui")
          ids (g/node-value resource :node-ids)
          baseline (g/node-value resource :save-value)
          source (g/node-value resource :scene)
          basis (g/now)
          undo-count (g/undo-stack-count :undo/global)]
      (is (= (set animation/supported-properties) (into #{} (mapcat channels/numeric-channels) (coll/vals ids))))
      (doseq [channel animation/supported-properties
              :let [[_ node] (coll/first-where #(contains? (set (channels/numeric-channels (second %))) channel) ids)
                    original (double (get (channels/node-values node) channel))
                    result (preview/apply-full-preview source {node (channels/node-overrides node {channel (+ original 0.1)})})]]
        (is (not (g/error? result)) channel)
        (is (= baseline (g/node-value resource :save-value)) channel))
      (doseq [[node-name property value] [["box" "enabled" "false"] ["box" "visible" "false"]
                                          ["box" "inherit_alpha" "false"] ["box" "pivot" "pivot_nw"]
                                          ["box" "blend_mode" "add"] ["box" "size_mode" "manual"]
                                          ["box" "texture" "qa/particle_blob"] ["box" "layer" "front"]
                                          ["box" "clipping_mode" "stencil"] ["box" "clipping_visible" "false"]
                                          ["box" "clipping_inverted" "true"] ["label" "text" "Animated text"]
                                          ["label" "line_break" "true"] ["pie" "outer_bounds" "rectangle"]
                                          ["pie" "perimeter_verticies" "64"]]
              :let [node (get ids node-name)
                    result (preview/apply-full-preview source {node (channels/node-overrides node {property value})})]]
        (is (not (g/error? result)) property)
        (is (= baseline (g/node-value resource :save-value)) property))
      (let [box (get ids "box")
            result (preview/apply-full-preview source {box (channels/node-overrides box {"size_x" 400.0 "color_r" 1.0})})
            node (scene-node result box)]
        (is (= 1.0 (get-in node [:renderable :user-data :color 0])))
        (is (= 400.0 (- (reduce max (mapv first (get-in node [:renderable :user-data :geom-data])))
                         (reduce min (mapv first (get-in node [:renderable :user-data :geom-data])))))))
      (let [box (get ids "box")
            result (preview/apply-full-preview source {box (channels/node-overrides box {"visible" "false"})})]
        (is (false? (get-in (scene-node result box) [:renderable :visible-self?]))))
      (is (identical? basis (g/now)))
      (is (= undo-count (g/undo-stack-count :undo/global))))))
