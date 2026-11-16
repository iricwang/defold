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

(ns editor.scene-selection-cycle-test
  (:require [clojure.test :refer [deftest is]]
            [dynamo.graph :as g]
            [editor.gl :as gl]
            [editor.gl.pass :as pass]
            [editor.gui-layout :as layout]
            [editor.scene :as scene]
            [editor.scene-selection :as selection]
            [integration.test-util :as test-util]
            [support.test-support :refer [with-clean-system]]
            [util.coll :as coll]))

;; Repeated clicks skip visited objects; movement or external selection resets.
(deftest cycle-exclusions-test
  (let [previous {:point [20 30 0] :selected 2 :excluded #{1 2}}]
    (is (= #{1 2} (selection/cycle-exclusions previous [21 30 0] [2])))
    (is (= #{} (selection/cycle-exclusions previous [40 30 0] [2])))
    (is (= #{} (selection/cycle-exclusions previous [20 30 0] [1])))
    (is (= #{} (selection/cycle-exclusions nil [20 30 0] [])))))

;; A disposed view must not evaluate graph outputs during a queued refresh.
(deftest disposed-layout-refresh
  (with-clean-system
    (is (nil? (#'layout/selection-data nil nil nil)))
    (is (nil? (#'layout/selection-data 123 456 789)))))

;; GPU picking must expose fully occluded GUI nodes in render order and wrap.
(deftest overlapping-gui-picking
  (when (gl/supported?)
    (test-util/with-loaded-project
      (let [resource (test-util/resource-node project "/gui/layers.gui")
            ids (g/node-value resource :node-ids)
            front (get ids "box1")
            back (get ids "box")]
        (g/transact
          (into [(g/delete-node (get ids "text"))
                 (g/delete-node (get ids "pie"))]
                (coll/into-> [front back] []
                  (mapcat (fn [id]
                            [(g/set-property id :position [0.0 0.0 0.0])
                             (g/set-property id :pivot :pivot-center)
                             (g/set-property id :manual-size [2048.0 2048.0 0.0])])))))
        (let [[_ view] (test-util/open-scene-view! project app-view "/gui/layers.gui" 128 128)]
          (let [render-count (atom 0)
                render @#'scene/batch-render]
            (with-redefs-fn {#'scene/batch-render
                             (fn counted-render [& args]
                               (when (contains? #{pass/opaque-selection pass/selection}
                                                (:pass (second args)))
                                 (swap! render-count inc))
                               (apply render args))}
              (fn verify-bounded-picking []
                (doseq [expected [front back front]]
                  (reset! render-count 0)
                  (test-util/mouse-click! view 40 40)
                  (is (test-util/selected? app-view expected))
                  ;; Two selection passes; at most one retry for wraparound.
                  (is (<= @render-count 4)))))))))))

;; Selection names remain visible for GUI/GO nodes, including multi-selection.
(deftest selection-name-label
  (let [props (fn node-props [id] {:properties {:id {:value id}}})]
    (is (= "damage_flash" (scene/selection-info-text [(props "damage_flash")])))
    (is (= "renamed" (scene/selection-info-text [(props "renamed")])))
    (is (= "named-layer" (scene/selection-info-text
                           [{:properties {:id {:value ""} :name {:value "named-layer"}}}])))
    (is (= "a, b, c … (+1)" (scene/selection-info-text (mapv props ["a" "b" "c" "d"]))))
    (is (nil? (scene/selection-info-text [])))
    (is (nil? (scene/selection-info-text [(props nil)])))))
