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

(ns editor.gui-animation-timeline-test
  (:require [clojure.test :refer [deftest is testing]]
            [editor.gui-animation-data :as animation]
            [editor.gui-animation-timeline :as timeline]))

;; Imported sampling rates must drive snapping and frame steps without changing stored times.
(deftest frame-navigation-test
  (let [document (assoc-in (animation/new-document "/main.gui") [:data :metadata :fps] 30)]
    (is (= 30.0 (timeline/frames-per-second document)))
    (is (= (/ 1.0 30.0) (timeline/snap-time document "default" 0.04)))
    (is (= 0.0 (timeline/navigate document "default" 0.01 :previous-frame)))
    (is (= (/ 1.0 30.0) (timeline/navigate document "default" 0.01 :next-frame)))
    (is (= 0.0 (timeline/navigate document "default" (/ 1.0 30.0) :previous-frame)))
    (is (= (/ 2.0 30.0) (timeline/navigate document "default" (/ 1.0 30.0) :next-frame)))
    (is (= 0.0 (timeline/navigate document "default" 0 :previous-frame)))
    (is (= 1.0 (timeline/navigate document "default" 1 :next-frame)))
    (doseq [fps [nil 0 -1 "30" Double/NaN Double/POSITIVE_INFINITY]]
      (is (= 60.0 (timeline/frames-per-second (assoc-in document [:data :metadata :fps] fps)))))))

;; Key navigation considers all tracks and must skip the key already under the playhead.
(deftest key-navigation-test
  (let [document (-> (animation/new-document "/main.gui")
                     (timeline/record-value "default" "box" "position_x" 0.75 100 0 "linear")
                     (timeline/record-value "default" "label" "color_a" 0.25 0 1 "linear"))]
    (is (= 0.25 (timeline/navigate document "default" 0 :next-key)))
    (is (= 0.75 (timeline/navigate document "default" 0.25 :next-key)))
    (is (= 0.25 (timeline/navigate document "default" 0.75 :previous-key)))
    (is (= 0.0 (timeline/navigate document "default" 0 :previous-key)))
    (is (= 1.0 (timeline/navigate document "default" 0.75 :next-key)))
    (is (= 1.0 (timeline/navigate document "default" 0 :last)))
    (is (= 0.0 (timeline/navigate document "default" 1 :first)))))

;; Playback preserves a nonzero starting position, wraps overshoot, and handles zero-length clips.
(deftest playback-test
  (is (= {:time 0.6 :playing true} (timeline/advance 0.5 1 0.1 false)))
  (is (= {:time 1.0 :playing false} (timeline/advance 0.9 1 0.2 false)))
  (is (< (Math/abs (- 0.1 (double (:time (timeline/advance 0.9 1 0.2 true))))) 1.0e-9))
  (is (= {:time 0.0 :playing false} (timeline/advance 0 0 0.1 true)))
  (is (= {:time 0.5 :playing true} (timeline/advance 0.5 1 -1 false))))

;; Recording a first delayed key adds the original pose, while subsequent edits retain the existing origin.
(deftest record-value-test
  (let [document (timeline/record-value (animation/new-document "/main.gui") "default" "box" "position_x" 0.5 100 20 "outquad")
        updated (timeline/record-value document "default" "box" "position_x" 0.5 200 999 "linear")]
    (is (= [{:time 0.0 :value 20 :easing "linear"} {:time 0.5 :value 100 :easing "outquad"}]
           (animation/keyframes document "default" "box" "position_x")))
    (is (= [{:time 0.0 :value 20 :easing "linear"} {:time 0.5 :value 200 :easing "linear"}]
           (animation/keyframes updated "default" "box" "position_x")))
    (testing "extra Panthera fields survive a recorded value edit"
      (let [imported (assoc-in document [:data :animations 0 :animation_keys 0 :vendor] {:tag "keep"})
            recorded (timeline/record-value imported "default" "box" "position_x" 0.5 300 20 "linear")]
        (is (= {:tag "keep"} (get-in recorded [:data :animations 0 :animation_keys 0 :vendor])))))))

;; Collapsing an object hides only its rows; track order and other objects remain stable.
(deftest track-hierarchy-test
  (let [tracks [["box" "position_x"] ["label" "color_a"] ["box" "position_y"]]]
    (is (= [{:node-name "box"} {:node-name "box" :property-id "position_x"}
            {:node-name "box" :property-id "position_y"}
            {:node-name "label"} {:node-name "label" :property-id "color_a"}]
           (timeline/rows tracks #{})))
    (is (= [{:node-name "box"} {:node-name "label"} {:node-name "label" :property-id "color_a"}]
           (timeline/rows tracks #{"box"})))
    (is (= [] (timeline/rows [] #{})))))

;; Editing keys changes Panthera segment ordering; visible property rows must not jump during recording.
(deftest stable-property-order-test
  (let [document (-> (animation/new-document "/main.gui")
                     (timeline/record-value "default" "box" "position_x" 0.5 100 0 "linear")
                     (timeline/record-value "default" "box" "color_a" 0 1 1 "linear"))
        recorded (timeline/record-value document "default" "box" "color_a" 0.25 0 1 "linear")]
    (is (= [["box" "position_x"] ["box" "color_a"]] (timeline/tracks document "default")))
    (is (= (timeline/tracks document "default") (timeline/tracks recorded "default")))))

;; The property tree includes unkeyed channels and a child summary; collapsing keeps header alignment.
(deftest reference-workspace-rows-test
  (let [rows (timeline/editor-rows [["btn_confirm" "position_x"] ["btn_confirm" "position_z"]] #{} "btn_confirm" {"btn_confirm" 2})]
    (is (= :node (:kind (first rows))))
    (is (= ["position_x" "position_y" "scale_x" "scale_y" "rotation_z" "color_a" "position_z"]
           (into [] (keep :property-id) rows)))
    (is (= {:node-name "btn_confirm" :kind :children :child-count 2} (peek rows)))
    (is (= [{:node-name "btn_confirm" :kind :node}]
           (timeline/editor-rows [["btn_confirm" "position_x"]] #{"btn_confirm"} "btn_confirm" {})))
    (is (= 8 (count (timeline/editor-rows [] #{} "new_node" {}))))))

;; Ruler labels use predictable frame intervals across zoom levels instead of irregular rounding.
(deftest reference-ruler-spacing-test
  (is (= 4 (timeline/ruler-step 60 1500)))
  (is (= 10 (timeline/ruler-step 60 500)))
  (is (= 1 (timeline/ruler-step 10 1500))))

;; Reverse and ping-pong playback preserve overshoot and stop correctly when looping is disabled.
(deftest reference-playback-modes-test
  (is (= {:time 0.5 :playing true :direction -1} (timeline/advance-playback 0.6 1.0 0.1 false :reverse -1)))
  (is (= {:time 0.0 :playing false :direction -1} (timeline/advance-playback 0.05 1.0 0.1 false :reverse -1)))
  (is (< (Math/abs (- 0.9 (double (:time (timeline/advance-playback 0.9 1.0 0.2 false :ping-pong 1))))) 1.0e-9))
  (is (= {:time 0.0 :playing false :direction -1} (timeline/advance-playback 0.1 1.0 0.2 false :ping-pong -1)))
  (is (= {:time 0.0 :playing false :direction 1} (timeline/advance-playback 0.0 0.0 1.0 true :ping-pong 1))))

;; Extending the clip must extend its timeline instead of shrinking existing frame spacing.
(deftest fixed-time-scale-test
  (let [short (animation/new-document "/main.gui")
        extended (animation/set-duration short "default" 5.0)]
    (is (= 1.0 (timeline/visible-span 1.0)))
    (is (= 0.5 (timeline/visible-span 2.0)))
    (is (= 4.0 (timeline/visible-span 0.25)))
    (is (= 1016.0 (timeline/time->x (animation/animation-duration short "default") 0.0 1.0 1032.0)))
    (is (= 5016.0 (timeline/time->x (animation/animation-duration extended "default") 0.0 1.0 1032.0)))
    (is (= 516.0 (timeline/time->x 0.5 0.0 1.0 1032.0)))
    (doseq [zoom [0.25 1.0 2.0 8.0]
            offset [0.0 1.0 4.0]
            time [0.0 0.5 2.25 5.0]]
      (is (< (Math/abs (- (double time) (timeline/x->time (timeline/time->x time offset zoom 1032.0) offset zoom 1032.0))) 1.0e-9)))))

;; Middle-button panning translates the visible range and clamps to clip bounds at any zoom.
(deftest timeline-pan-offset-test
  (is (= 0.5 (timeline/pan-offset 5.0 1.0 1032.0 0.0 -500.0)))
  (is (= 1.5 (timeline/pan-offset 5.0 1.0 1032.0 2.0 500.0)))
  (is (= 0.0 (timeline/pan-offset 5.0 1.0 1032.0 0.0 500.0)))
  (is (= 4.0 (timeline/pan-offset 5.0 1.0 1032.0 0.0 -10000.0)))
  (is (= 0.25 (timeline/pan-offset 5.0 2.0 1032.0 0.0 -500.0)))
  (is (= 0.0 (timeline/pan-offset 0.5 1.0 1032.0 0.0 -500.0)))
  (is (= 0.0 (timeline/pan-offset 5.0 0.125 1032.0 0.0 -500.0))))
