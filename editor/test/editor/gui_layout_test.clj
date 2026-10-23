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

(ns editor.gui-layout-test
  (:require [clojure.test :refer [deftest is]]
            [dynamo.graph :as g]
            [editor.gui-layout :as layout]
            [editor.ui :as ui]
            [integration.test-util :as test-util])
  (:import [javafx.event ActionEvent]
           [javafx.scene Scene]
           [javafx.scene.control ToggleButton]
           [javafx.scene.input KeyCode KeyEvent MouseButton MouseEvent]
           [javafx.scene.layout VBox]))

;; Every native pivot preserves the rectangle when its position is compensated.
(deftest pivot-preserves-corners
  (doseq [[old] layout/pivot-points
          [new] layout/pivot-points]
    (let [size [120.0 75.0 0.0]
          delta (subvec (layout/pivot-delta size old new) 0 2)]
      (is (= (layout/rect-corners old size)
             (mapv #(mapv + delta %) (layout/rect-corners new size)))))))

;; All eight handles keep the opposite edge/corner fixed for all native pivots.
(deftest resize-keeps-opposite-edge
  (doseq [[pivot] layout/pivot-points
          [hx hy :as handle] [[-1 -1] [0 -1] [1 -1] [1 0] [1 1] [0 1] [-1 1] [-1 0]]]
    (let [old-size [120.0 75.0 0.0]
          {:keys [size delta]} (layout/resize-rect pivot old-size handle [17.0 -13.0] false)
          old-corners (layout/rect-corners pivot old-size)
          new-corners (mapv #(mapv + (subvec delta 0 2) %) (layout/rect-corners pivot size))]
      (is (= (if (neg? hx) (first (nth old-corners 1)) (ffirst old-corners))
             (if (neg? hx) (first (nth new-corners 1)) (ffirst new-corners))))
      (is (= (if (neg? hy) (second (nth old-corners 2)) (second (first old-corners)))
             (if (neg? hy) (second (nth new-corners 2)) (second (first new-corners))))))))

;; Degenerate sizes remain finite, and dragging past the opposite edge cannot mirror a node.
(deftest resize-minimum-and-aspect
  (is (= [1.0 1.0 0.0] (:size (layout/resize-rect :pivot-center [20.0 10.0 0.0] [1 1] [-100.0 -100.0] false))))
  (is (= [40.0 20.0 0.0] (:size (layout/resize-rect :pivot-center [20.0 10.0 0.0] [1 1] [20.0 3.0] true))))
  (is (= [1.0 1.0 0.0] (:size (layout/resize-rect :pivot-center [0.0 0.0 0.0] [1 1] [0.0 0.0] true)))))

;; Pivot compensation must use rotation and non-uniform scale, rather than adding world pixels.
(deftest rotated-scaled-compensation
  (let [a (/ Math/PI 4.0)
        result (layout/local-delta->parent [0.0 0.0 (Math/sin a) (Math/cos a)] [2.0 3.0 1.0] [10.0 4.0 0.0])]
    (is (< (Math/abs (+ 12.0 (double (first result)))) 1.0e-9))
    (is (< (Math/abs (- 20.0 (double (second result)))) 1.0e-9))
    (is (zero? (double (nth result 2))))))

;; Focus changes can rebuild the popup between input and action. Modifier state
;; must survive that rebuild so Shift/Alt still reach the preset transaction.
(deftest preset-modifiers-survive-render-test
  (ui/run-now
    (let [button (ToggleButton.)
          stage (ui/make-stage)
          received (atom nil)
          render-button (fn []
                          (->> (#'layout/rect-preset-popup 0 nil test-util/localization (constantly nil))
                               :content :children
                               (filterv #(= :small (:spacing %)))
                               first :children
                               (filterv #(= "gui-rect-preset-0-0" (:id %)))
                               first))]
      (.setScene stage (Scene. (doto (VBox.) (ui/children! [button]))))
      (with-redefs-fn {#'g/node-value (fn [_ property]
                                      (case property
                                        :rect-transform false
                                        :rect-anchor-min [0.0 0.0 0.0 0.0]
                                        :rect-anchor-max [0.0 0.0 0.0 0.0]))
                      #'g/make-evaluation-context (constantly nil)
                      #'layout/animation-preview-active? (constantly false)
                      #'layout/rect-preset-changes (fn [_ _ shift alt _] (reset! received [shift alt]) [])
                      #'layout/commit! (fn [_ _])}
        (fn []
          (doseq [input [:keyboard :mouse]
                  shift [false true]
                  alt [false true]
                  :when (or (= :keyboard input) shift alt)]
            (let [before (render-button)
                  event (.copyFor (KeyEvent. KeyEvent/KEY_PRESSED "" "" KeyCode/SPACE shift false alt false) button button)]
              (if (= :keyboard input)
                (do
                  ((:on-key-pressed before) event)
                  ((:on-action (render-button)) (ActionEvent. button button)))
                (let [mouse-event (.copyFor (MouseEvent. MouseEvent/MOUSE_CLICKED 0.0 0.0 0.0 0.0 MouseButton/PRIMARY 1
                                                        shift false alt false false false false false false true nil)
                                           button button)]
                  ((:on-mouse-pressed before) mouse-event)
                  ((:on-mouse-clicked (render-button)) mouse-event)))
              (is (= [shift alt] @received)))))))))
