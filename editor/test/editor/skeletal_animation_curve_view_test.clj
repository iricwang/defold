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

(ns editor.skeletal-animation-curve-view-test
  (:require [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-curve-view :as curve]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [javafx.scene.canvas Canvas]
           [javafx.scene.input MouseButton MouseEvent]
           [javafx.scene.layout StackPane]))

(defn- near? [expected actual]
  (< (Math/abs (- (double expected) (double actual))) 1.0e-8))

(defn- state []
  {:keys [{:time 0.0 :value 20.0 :interpolation "bezier" :curve [0.25 20.0 0.75 80.0] :extra "retain"}
          {:time 1.0 :value 80.0 :interpolation "linear"}]
   :selected-time 0.0 :time 0.4 :duration 1.0})

(defn- mouse-event [type x y]
  (MouseEvent. type x y x y MouseButton/PRIMARY 1 false false false false
               (= type MouseEvent/MOUSE_DRAGGED) false false false false false nil))

(defn- make-editor [state calls]
  (let [{:keys [node] :as editor} (curve/make-view #(deref state) #(swap! calls conj [%1 %2]))]
    (.resize ^StackPane node 600.0 200.0)
    (assoc editor :canvas (first (.getChildren ^StackPane node)))))

;; Coordinate conversion is invertible and includes negative values, overshoot and zero-duration clips.
(deftest graph-coordinate-test
  (doseq [duration [0.0 1.0 10.0]
          size [[600.0 200.0] [10.0 10.0]]]
    (let [bounds (curve/curve-bounds (first size) (second size) (:keys (state)) duration)]
      (doseq [value [[0.0 -20.0] [0.5 35.0] [1.0 150.0]]]
        (is (coll/every? true? (mapv near? value (curve/point->value bounds (curve/value->point bounds value)))))))))

;; Handle dragging previews continuously, preserves key metadata and commits once on release.
(deftest handle-commit-test
  (ui/run-now
    (let [state (atom (state)) calls (atom [])
          {:keys [canvas]} (make-editor state calls)
          bounds (curve/curve-bounds 600.0 200.0 (:keys @state) 1.0)
          [x y] (curve/value->point bounds [0.25 20.0])
          [tx ty] (curve/value->point bounds [0.4 55.0])]
      (.handle (.getOnMousePressed ^Canvas canvas) (mouse-event MouseEvent/MOUSE_PRESSED x y))
      (.handle (.getOnMouseDragged ^Canvas canvas) (mouse-event MouseEvent/MOUSE_DRAGGED tx ty))
      (is (= [] @calls))
      (.handle (.getOnMouseReleased ^Canvas canvas) (mouse-event MouseEvent/MOUSE_RELEASED tx ty))
      (.handle (.getOnMouseReleased ^Canvas canvas) (mouse-event MouseEvent/MOUSE_RELEASED tx ty))
      (is (= 1 (count @calls)))
      (is (= (first (:keys @state)) (ffirst @calls)))
      (is (near? 0.4 (first (second (first @calls)))))
      (is (near? 55.0 (second (second (first @calls)))))
      (is (= [0.75 80.0] (subvec (second (first @calls)) 2 4))))))

;; Unselected keys and clicks without movement cannot mutate the document.
(deftest no-selection-no-commit-test
  (ui/run-now
    (doseq [selected [nil 0.0]]
      (let [state (atom (assoc (state) :selected-time selected)) calls (atom [])
            {:keys [canvas]} (make-editor state calls)
            bounds (curve/curve-bounds 600.0 200.0 (:keys @state) 1.0)
            [x y] (curve/value->point bounds [0.25 20.0])]
        (.handle (.getOnMousePressed ^Canvas canvas) (mouse-event MouseEvent/MOUSE_PRESSED x y))
        (.handle (.getOnMouseReleased ^Canvas canvas) (mouse-event MouseEvent/MOUSE_RELEASED x y))
        (is (= [] @calls))
        (when (nil? selected)
          (.handle (.getOnMouseDragged ^Canvas canvas) (mouse-event MouseEvent/MOUSE_DRAGGED (+ x 50.0) y))
          (.handle (.getOnMouseReleased ^Canvas canvas) (mouse-event MouseEvent/MOUSE_RELEASED (+ x 50.0) y))
          (is (= [] @calls)))))))

;; Time controls cannot leave their key segment even when the pointer moves beyond the plot.
(deftest handle-time-clamp-test
  (ui/run-now
    (let [state (atom (state)) calls (atom [])
          {:keys [canvas]} (make-editor state calls)
          bounds (curve/curve-bounds 600.0 200.0 (:keys @state) 1.0)
          [x y] (curve/value->point bounds [0.25 20.0])]
      (.handle (.getOnMousePressed ^Canvas canvas) (mouse-event MouseEvent/MOUSE_PRESSED x y))
      (.handle (.getOnMouseDragged ^Canvas canvas) (mouse-event MouseEvent/MOUSE_DRAGGED -2000.0 y))
      (.handle (.getOnMouseReleased ^Canvas canvas) (mouse-event MouseEvent/MOUSE_RELEASED -2000.0 y))
      (is (= 0.0 (first (second (first @calls))))))))
