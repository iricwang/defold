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

;; Shared graph bounds must include every visible channel, so selecting another
;; channel does not change the coordinate system used by its handle hit tests.
(deftest shared-channel-bounds-test
  (let [channels [{:property "x" :keys [{:time 0.0 :value -100.0} {:time 1.0 :value 10.0}]}
                  {:property "y" :keys [{:time 0.0 :value 200.0} {:time 1.0 :value 300.0}]}]
        state {:duration 1.0 :channels channels}
        bounds (curve/graph-bounds 800.0 250.0 state)]
    (is (< (:low bounds) -100.0))
    (is (> (:high bounds) 300.0))
    (is (= bounds (curve/graph-bounds 800.0 250.0 (assoc state :active-track "y"))))))

;; Multi-channel selection commits interpolation and tangents together,
;; preserving one undo transaction and leaving end keys unchanged.
(deftest batch-selection-commit-test
  (ui/run-now
    (let [source (atom {:duration 1.0 :selected-time 0.0
                        :channels [{:property "x" :keys (:keys (state))}
                                   {:property "y" :keys [{:time 0.0 :value 100.0 :interpolation "bezier" :curve [0.25 100.0 0.75 140.0]}
                                                         {:time 1.0 :value 140.0 :interpolation "linear"}]}]})
          calls (atom [])
          editor (curve/make-batch-view #(deref source) #(swap! calls conj %))]
      (.resize ^StackPane (:node editor) 600.0 200.0)
      ((:select-all! editor))
      (is (= 4 (count ((:selected-refs editor)))))
      ((:tangents! editor) :flat)
      (is (= 1 (count @calls)))
      (is (= ["x" "y"] (mapv #(get-in % [:key :track]) (first @calls))))
      (is (= [20.0 80.0] (mapv #(nth (get-in @calls [0 0 :curve]) %) [1 3])))
      ((:set-selection! editor) [["y" 0.0]])
      ((:interpolate! editor) "stepped")
      (is (= 2 (count @calls)))
      (is (= [{:key (assoc (get-in @source [:channels 1 :keys 0]) :track "y") :curve "stepped"}] (second @calls))))))

;; Zoom keeps the cursor's time/value fixed; panning is independent of key data.
(deftest graph-navigation-test
  (let [bounds (curve/graph-bounds 800.0 300.0 (state))
        point [300.0 120.0]
        anchor (curve/point->value bounds point)
        range (curve/zoom-range bounds point [2.0 3.0])
        zoomed (curve/graph-bounds 800.0 300.0 (assoc (state) :view-range range))]
    (is (coll/every? true? (mapv near? anchor (curve/point->value zoomed point))))
    (is (near? (/ (:duration bounds) 2.0) (:duration zoomed)))
    (is (near? (/ (- (:high bounds) (:low bounds)) 3.0) (- (:high zoomed) (:low zoomed))))
    (let [panned (curve/graph-bounds 800.0 300.0 (assoc (state) :view-range (curve/pan-range bounds [70.0 25.0])))]
      (is (coll/every? true? (mapv near? [370.0 145.0] (curve/value->point panned anchor)))))))

;; Smooth commits real Bezier controls in one batch, including flat-valued keys.
(deftest smooth-tangent-test
  (ui/run-now
    (let [source (atom {:duration 2.0 :selected-time 1.0
                        :keys [{:time 0.0 :value 0.0 :interpolation "linear"}
                               {:time 1.0 :value 1.0 :interpolation "linear"}
                               {:time 2.0 :value 0.0 :interpolation "linear"}]})
          calls (atom []) editor (curve/make-batch-view #(deref source) #(swap! calls conj %))]
      ((:set-selection! editor) [[nil 0.0] [nil 1.0]])
      ((:tangents! editor) :smooth)
      (is (= 1 (count @calls)))
      (is (= 2 (count (first @calls))))
      (is (near? 1.0 (nth (get-in @calls [0 0 :curve]) 3)))
      (is (near? 1.0 (nth (get-in @calls [0 1 :curve]) 1)))
      (is (< (nth (get-in @calls [0 0 :curve]) 0) (nth (get-in @calls [0 0 :curve]) 2))))))

;; Right-drag changes the view only and commits no curve/key edits.
(deftest graph-right-pan-test
  (ui/run-now
    (let [source (atom (state)) calls (atom [])
          {:keys [canvas] :as editor} (make-editor source calls)
          event (fn event [type x y]
                  (MouseEvent. type x y x y MouseButton/SECONDARY 1 false false false false
                               false false true false false false nil))]
      (.handle (.getOnMousePressed ^Canvas canvas) (event MouseEvent/MOUSE_PRESSED 200.0 100.0))
      (.handle (.getOnMouseDragged ^Canvas canvas) (event MouseEvent/MOUSE_DRAGGED 250.0 120.0))
      (.handle (.getOnMouseReleased ^Canvas canvas) (event MouseEvent/MOUSE_RELEASED 250.0 120.0))
      (is (vector? ((:view-range editor))))
      (is (= [] @calls))
      (is (= (state) @source)))))

;; A key drag is preview-only until release, then reports a single batch.
(deftest graph-key-drag-test
  (ui/run-now
    (let [edits (atom [])
          source (atom {:duration 2.0 :fps 60.0 :selected-time 0.5
                        :keys [{:time 0.5 :value 20.0 :interpolation "linear"}
                               {:time 1.5 :value 80.0 :interpolation "linear"}]
                        :on-edit-keys! (fn record-key-edit [changes] (swap! edits conj changes))})
          calls (atom []) {:keys [canvas]} (make-editor source calls)
          bounds (curve/graph-bounds 600.0 200.0 @source)
          [x y] (curve/value->point bounds [0.5 20.0])
          [tx ty] (curve/value->point bounds [0.75 30.0])]
      (.handle (.getOnMousePressed ^Canvas canvas) (mouse-event MouseEvent/MOUSE_PRESSED x y))
      (.handle (.getOnMouseDragged ^Canvas canvas) (mouse-event MouseEvent/MOUSE_DRAGGED tx ty))
      (is (= [] @edits))
      (.handle (.getOnMouseReleased ^Canvas canvas) (mouse-event MouseEvent/MOUSE_RELEASED tx ty))
      (is (= 1 (count @edits)))
      (is (near? 0.75 (get-in @edits [0 0 :time])))
      (is (near? 30.0 (get-in @edits [0 0 :value])))
      (is (= [] @calls)))))
