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

(ns editor.skeletal-animation-curve-view
  "Canvas graph for scalar animation keys with editable absolute Bezier handles."
  (:require [editor.ui :as ui]
            [util.coll :as coll])
  (:import [javafx.beans.property DoubleProperty]
           [javafx.beans.value ChangeListener]
           [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.input KeyCode MouseButton MouseEvent]
           [javafx.scene.layout StackPane]
           [javafx.scene.paint Color]
           [javafx.scene.text Font]))

(set! *warn-on-reflection* true)

(defn curve-bounds
  "Stable graph extents, including overshooting Bezier handles."
  [width height keys duration]
  (let [values (into (mapv :value keys)
                     (mapcat (fn [key] (when (= "bezier" (:interpolation key))
                                        [(nth (:curve key) 1) (nth (:curve key) 3)]))) keys)
        low (reduce min 0.0 values)
        high (reduce max 0.0 values)
        margin (* 0.15 (max 1.0 (- high low)))]
    {:left 52.0 :top 22.0 :width (max 1.0 (- width 68.0)) :height (max 1.0 (- height 48.0))
     :duration (max 0.001 (double duration)) :low (- low margin) :high (+ high margin)}))

(defn value->point [{:keys [left top width height duration low high]} [time value]]
  [(+ left (* width (/ time duration))) (+ top (* height (- 1.0 (/ (- value low) (- high low)))))])

(defn point->value [{:keys [left top width height duration low high]} [x y]]
  [(* duration (/ (- x left) width)) (+ low (* (- high low) (- 1.0 (/ (- y top) height))))])

(defn- selected-segment [{:keys [keys selected-time selected-key]}]
  (let [selected-time (or (:time selected-key) selected-time)
        key (when selected-time (coll/first-where #(== (double selected-time) (double (:time %))) keys))
        right (when key (coll/first-where #(> (double (:time %)) (double (:time key))) keys))]
    (when (and key right (= "bezier" (:interpolation key))) {:key key :right right})))

(defn- handle-points [bounds curve]
  [(value->point bounds (subvec curve 0 2)) (value->point bounds (subvec curve 2 4))])

(defn- draw-key! [^GraphicsContext gc bounds key color radius]
  (let [[x y] (value->point bounds [(:time key) (:value key)])]
    (.setFill gc color)
    (.fillOval gc (- x radius) (- y radius) (* 2.0 radius) (* 2.0 radius))))

(defn- draw! [^Canvas canvas state drag]
  (let [^GraphicsContext gc (.getGraphicsContext2D canvas)
        width (.getWidth canvas) height (.getHeight canvas)
        keys (get state :keys [])
        bounds (or (:bounds drag) (curve-bounds width height keys (or (:duration state) 1.0)))
        {:keys [left top low high] graph-width :width graph-height :height} bounds
        segment (selected-segment state)
        selected-time (get-in segment [:key :time])
        keys (if drag (mapv #(if (== (double (:time %)) (double (get-in drag [:key :time]))) (assoc % :curve (:curve drag)) %) keys) keys)]
    (.setFill gc (Color/web "#202124")) (.fillRect gc 0.0 0.0 width height)
    (.setFont gc (Font/font 10.0)) (.setLineWidth gc 1.0)
    (dotimes [index 6]
      (let [ratio (/ index 5.0) x (+ left (* ratio graph-width)) y (+ top (* ratio graph-height))]
        (.setStroke gc (Color/web "#34373b"))
        (.strokeLine gc x top x (+ top graph-height)) (.strokeLine gc left y (+ left graph-width) y)
        (.setFill gc (Color/web "#aeb3b8"))
        (.fillText gc (format "%.2f" (* ratio (:duration bounds))) (- x 9.0) (- height 8.0))
        (.fillText gc (format "%.2f" (- high (* ratio (- high low)))) 3.0 (+ y 3.0))))
    (when (pos? (count keys))
      (.setStroke gc (Color/web "#77b8f5")) (.setLineWidth gc 2.0) (.beginPath gc)
      (let [[x y] (value->point bounds [(:time (first keys)) (:value (first keys))])] (.moveTo gc x y))
      (dotimes [index (dec (count keys))]
        (let [key (nth keys index) next-key (nth keys (inc index))
              [x y] (value->point bounds [(:time next-key) (:value next-key)])]
          (case (:interpolation key)
            "stepped" (let [[_ sy] (value->point bounds [(:time key) (:value key)])] (.lineTo gc x sy) (.lineTo gc x y))
            "bezier" (let [[[x1 y1] [x2 y2]] (handle-points bounds (:curve key))] (.bezierCurveTo gc x1 y1 x2 y2 x y))
            (.lineTo gc x y))))
      (let [[x y] (value->point bounds [(:duration bounds) (:value (peek keys))])] (.lineTo gc x y))
      (.stroke gc))
    (doseq [key keys]
      (draw-key! gc bounds key (if (and selected-time (== (double selected-time) (double (:time key)))) Color/ORANGE (Color/web "#77b8f5")) 3.5))
    (when segment
      (let [key (:key segment) right (:right segment)
            curve (or (:curve drag) (:curve key))
            handles (handle-points bounds curve)
            endpoints (mapv #(value->point bounds [(:time %) (:value %)]) [key right])]
        (.setStroke gc (Color/web "#8d949d")) (.setLineWidth gc 1.0)
        (doseq [[[x y] [hx hy]] (mapv vector endpoints handles)]
          (.strokeLine gc x y hx hy)
          (.setFill gc (Color/web "#efad66")) (.fillOval gc (- hx 4.5) (- hy 4.5) 9.0 9.0))))
    (when-let [time (:time state)]
      (let [[x _] (value->point bounds [time 0.0])]
        (.setStroke gc (Color/web "#ef944c")) (.setLineWidth gc 1.0)
        (.strokeLine gc x top x (+ top graph-height))))
    (when (zero? (count keys))
      (.setFill gc (Color/web "#aeb3b8"))
      (.fillText gc (get state :empty-text "Select a scalar animation track") (+ left 12.0) (+ top 22.0)))))

(defn make-view
  "read-state! returns {:keys :time :duration :selected-time :selected-key}.
  commit-curve! receives [existing-key absolute-controls] exactly once on a
  changed handle release. Dragging is a preview until release; Escape cancels."
  [read-state! commit-curve!]
  (let [canvas (doto (Canvas. 600.0 180.0) (.setId "skeletal-curve-canvas") (.setFocusTraversable true))
        node (doto (StackPane.) (.setId "skeletal-curve-editor") (.setMinSize 100.0 80.0))
        drag (atom nil)
        last-token (atom nil)
        refresh (fn []
                  (let [state (read-state!)
                        token [state @drag (.getWidth canvas) (.getHeight canvas)]]
                    (when (not= token @last-token)
                      (draw! canvas state @drag)
                      (reset! last-token token))))]
    (.add (.getChildren node) canvas)
    (.bind (.widthProperty canvas) (.widthProperty node))
    (.bind (.heightProperty canvas) (.heightProperty node))
    (doseq [^DoubleProperty property [(.widthProperty canvas) (.heightProperty canvas)]]
      (.addListener property (reify ChangeListener (changed [_ _ _ _] (refresh)))))
    (.setOnMousePressed canvas
      (ui/event-handler event
        (when (= MouseButton/PRIMARY (.getButton ^MouseEvent event))
          (let [state (read-state!)
                {:keys [key right]} (selected-segment state)]
            (when key
              (let [bounds (curve-bounds (.getWidth canvas) (.getHeight canvas) (:keys state) (or (:duration state) 1.0))
                    index (reduce-kv (fn [_ i [x y]]
                                       (when (< (Math/hypot (- x (.getX ^MouseEvent event)) (- y (.getY ^MouseEvent event))) 10.0) (reduced i)))
                                     nil (handle-points bounds (:curve key)))]
                (when index
                  (.requestFocus canvas)
                  (reset! drag {:key key :curve (:curve key) :handle index :bounds bounds :right-time (:time right)})
                  (.consume ^MouseEvent event))))))))
    (.setOnMouseDragged canvas
      (ui/event-handler event
        (when-let [{:keys [bounds key right-time handle]} @drag]
          (let [[time value] (point->value bounds [(.getX ^MouseEvent event) (.getY ^MouseEvent event)])]
            (swap! drag update :curve #(-> % (assoc (* handle 2) (max (:time key) (min right-time time))) (assoc (inc (* handle 2)) value)))
            (refresh)
            (.consume ^MouseEvent event)))))
    (.setOnMouseReleased canvas
      (ui/event-handler event
        (when-let [{:keys [key curve]} @drag]
          (reset! drag nil)
          (when (not= (:curve key) curve) (commit-curve! key curve))
          (refresh)
          (.consume ^MouseEvent event))))
    (.setOnKeyPressed canvas
      (ui/event-handler event
        (when (= KeyCode/ESCAPE (.getCode ^javafx.scene.input.KeyEvent event))
          (reset! drag nil) (refresh) (.consume ^javafx.scene.input.KeyEvent event))))
    (refresh)
    {:node node :refresh! refresh}))
