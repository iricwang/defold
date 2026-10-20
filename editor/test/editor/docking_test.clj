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

(ns editor.docking-test
  (:require [cljfx.api] ; Initialize JavaFX before loading AOT-compiled control classes.
            [clojure.test :refer [deftest is]]
            [editor.dock-windows :as dock]
            [editor.docking :as docking]))

;; Rejects missing, duplicate and unknown panels in saved layouts before creating JavaFX nodes.
(deftest saved-layout-validation
  (is (docking/valid-layout? docking/default-layout))
  (doseq [layout [nil {} {:left [:assets] :right [:preview] :bottom [:tools] :center [:game]}
                  (update docking/default-layout :left conj :preview)
                  (assoc docking/default-layout :bottom [:unknown])]]
    (is (not (docking/valid-layout? layout)))))

;; Moving between and within zones keeps every panel exactly once, including when a zone becomes empty.
(deftest move-panel-between-zones
  (let [layout (-> docking/default-layout
                   (docking/move-panel :assets :right)
                   (docking/move-panel :preview :bottom))]
    (is (= {:left [] :right [:outline :inspector :assets] :bottom [:tools :preview] :center [:game]} layout))
    (is (docking/valid-layout? layout))
    (is (= layout (docking/move-panel layout :preview :bottom)))
    (is (= [:preview :tools] (:bottom (docking/move-panel layout :tools :bottom))))))

;; Dropping onto a panel halves only its share and preserves the other target-zone panels.
(deftest split-target-panel
  (let [sizes {:left {:assets 1.0} :right {:outline 0.25 :inspector 0.25 :preview 0.5} :bottom {:tools 1.0}}
        result (docking/split-panel docking/default-layout sizes :assets :right :inspector true)]
    (is (= {:left [] :right [:outline :assets :inspector :preview] :bottom [:tools] :center [:game]} (:layout result)))
    (is (= {:outline 0.25 :assets 0.125 :inspector 0.125 :preview 0.5} (get-in result [:sizes :right])))
    (is (docking/valid-layout? (:layout result)))
    (is (= [:outline :inspector :assets :preview]
           (get-in (docking/split-panel docking/default-layout sizes :assets :right :inspector false)
                   [:layout :right])))))

;; Reordering in the same zone neither duplicates a panel nor loses its available space.
(deftest split-within-zone
  (let [sizes {:left {:assets 1.0} :right {:outline 0.25 :inspector 0.25 :preview 0.5} :bottom {:tools 1.0}}
        result (docking/split-panel docking/default-layout sizes :preview :right :inspector true)]
    (is (= [:outline :preview :inspector] (get-in result [:layout :right])))
    (is (= {:outline 0.5 :preview 0.25 :inspector 0.25} (get-in result [:sizes :right])))
    (is (= {:layout docking/default-layout :sizes sizes}
           (docking/split-panel docking/default-layout sizes :preview :right :preview false)))))

;; The outline describes exactly one half of the hovered panel, for both docking orientations.
(deftest drop-outline-geometry
  (is (= {:before true :x 0.0 :y 0.0 :width 300.0 :height 200.0}
         (docking/drop-half 300.0 400.0 false 20.0 100.0)))
  (is (= {:before false :x 0.0 :y 200.0 :width 300.0 :height 200.0}
         (docking/drop-half 300.0 400.0 false 20.0 200.0)))
  (is (= {:before true :x 0.0 :y 0.0 :width 150.0 :height 400.0}
         (docking/drop-half 300.0 400.0 true 10.0 300.0)))
  (is (= {:before false :x 150.0 :y 0.0 :width 150.0 :height 400.0}
         (docking/drop-half 300.0 400.0 true 290.0 300.0))))

;; Same-zone drag outlines include the space released by the source, matching the final split.
(deftest same-zone-outline-matches-planned-layout
  (let [sizes {:left {:assets 1.0} :right {:outline 0.25 :inspector 0.25 :preview 0.5} :bottom {:tools 1.0}}
        {layout :layout planned-sizes :sizes}
        (docking/split-panel docking/default-layout sizes :preview :right :inspector true)]
    (is (= {:x 0.0 :y 400.0 :width 300.0 :height 200.0}
           (docking/panel-rectangle (:right layout) (:right planned-sizes) :preview 300.0 800.0 false)))
    (is (= {:x 225.0 :y 0.0 :width 75.0 :height 800.0}
           (docking/panel-rectangle (:right layout) (:right planned-sizes) :inspector 300.0 800.0 true)))))

;; Legacy combined inspectors expand in place; repeated migration never duplicates either panel.
(deftest separate-outline-layout-migration
  (let [legacy {:left [:inspector :assets] :right [:preview] :bottom [:tools] :center [:game]}
        migrated (docking/migrate-layout legacy)]
    (is (= [:outline :inspector :assets] (:left migrated)))
    (is (docking/valid-layout? migrated))
    (is (= migrated (docking/migrate-layout migrated)))
    (is (= [:inspector :assets] (:left (docking/move-panel migrated :outline :bottom))))
    (is (= [:tools :outline] (:bottom (docking/move-panel migrated :outline :bottom)))))
  (is (nil? (docking/migrate-layout nil)))
  (is (= {} (docking/migrate-layout {}))))

;; Mixed-axis splits preserve all panels, survive serialization, and collapse emptied source branches.
(deftest mixed-axis-docked-layout
  (let [trees (-> (docking/layout-trees docking/default-layout)
                  (docking/split-trees :assets :right :outline :left)
                  (docking/split-trees :tools :right :assets :bottom))
        layout (into {} (map (fn [[zone tree]] [zone (dock/panel-ids tree)])) trees)]
    (is (docking/valid-layout? layout))
    (is (docking/valid-trees? trees layout))
    (is (nil? (:left trees)))
    (is (nil? (:bottom trees)))
    (is (= :vertical (get-in trees [:right :axis])))
    (is (= :horizontal (get-in trees [:right :children 0 :axis])))
    (is (= :vertical (get-in trees [:right :children 0 :children 0 :axis])))
    (is (= [:assets :tools :outline :inspector :preview] (:right layout)))
    (is (not (docking/valid-trees? (assoc-in trees [:right :dividers] [0.8 0.3]) layout)))
    (is (not (docking/valid-trees? (assoc-in trees [:right :axis] :diagonal) layout)))
    (is (not (docking/valid-trees? trees docking/default-layout)))
    (is (= {:tabs [:outline]}
           (-> (get-in trees [:right :children 0])
               (dock/remove-panel :assets)
               (dock/remove-panel :tools))))))

;; Every panel exposes four directional targets regardless of its containing region's orientation.
(deftest four-direction-panel-targets
  (doseq [[x y edge] [[5.0 200.0 :left] [295.0 200.0 :right]
                      [150.0 5.0 :top] [150.0 395.0 :bottom]]]
    (is (= edge (:edge (docking/drop-quarter 300.0 400.0 x y)))))
  (is (= :top (dock/drop-edge 300.0 400.0 30.0 5.0)))
  (is (= :left (dock/drop-edge 300.0 400.0 5.0 40.0))))
