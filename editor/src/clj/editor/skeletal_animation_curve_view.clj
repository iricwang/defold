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
  "Scalar animation curves with absolute Bezier handles and batch selection."
  (:require [clojure.string :as string]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [javafx.beans.property DoubleProperty]
           [javafx.beans.value ChangeListener]
           [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.input KeyCode MouseButton MouseEvent ScrollEvent]
           [javafx.scene.control Button Label ScrollPane Slider TextField ToggleButton ToolBar Tooltip]
           [javafx.scene.layout BorderPane HBox Priority StackPane VBox]
           [javafx.scene.paint Color]
           [javafx.scene.shape Circle SVGPath]
           [javafx.scene.text Font]))

(set! *warn-on-reflection* true)

(defn curve-bounds
  "Stable graph extents, including overshooting Bezier handles."
  [width height keys duration]
  (let [values (coll/into-> keys (mapv :value keys)
                 (mapcat (fn [key] (when (= "bezier" (:interpolation key))
                                     [(nth (:curve key) 1) (nth (:curve key) 3)]))))
        low (reduce min 0.0 values)
        high (reduce max 0.0 values)
        margin (* 0.15 (max 1.0 (- high low)))]
    {:left 16.0 :top 30.0 :width (max 1.0 (- width 80.0)) :height (max 1.0 (- height 48.0))
     :duration (max 0.001 (double duration)) :low (- low margin) :high (+ high margin)}))

(defn value->point [{:keys [left top width height duration low high time-start] :or {time-start 0.0}} [time value]]
  [(+ left (* width (/ (- time time-start) duration))) (+ top (* height (- 1.0 (/ (- value low) (- high low)))))])

(defn point->value [{:keys [left top width height duration low high time-start] :or {time-start 0.0}} [x y]]
  [(+ time-start (* duration (/ (- x left) width))) (+ low (* (- high low) (- 1.0 (/ (- y top) height))))])

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
    (.fillRect gc (- x radius) (- y radius) (* 2.0 radius) (* 2.0 radius))
    (.setStroke gc (Color/web "#171717")) (.setLineWidth gc 1.0)
    (.strokeRect gc (- x radius) (- y radius) (* 2.0 radius) (* 2.0 radius))))

(defn- draw-channel! [^Canvas canvas state drag]
  (let [^GraphicsContext gc (.getGraphicsContext2D canvas)
        width (.getWidth canvas) height (.getHeight canvas)
        keys (get state :keys [])
        bounds (or (:bounds drag) (:bounds state) (curve-bounds width height keys (or (:duration state) 1.0)))
        {:keys [left top low high] graph-width :width graph-height :height} bounds
        segment (selected-segment state)
        selected-time (get-in segment [:key :time])
        keys (if drag
               (mapv (fn [key]
                       (if-let [curve (get (:curves drag) [(:active-track state) (:time key)])]
                         (assoc key :curve curve) key)) keys) keys)]
    (when-not (:overlay state)
      (.setFill gc (Color/web "#242424")) (.fillRect gc 0.0 0.0 width height)
      (let [[x0 _] (value->point bounds [0.0 0.0])
            [x1 _] (value->point bounds [(:duration state 1.0) 0.0])]
        (.setFill gc (Color/web "#333333")) (.fillRect gc (max 0.0 x0) top (max 0.0 (- (min width x1) (max 0.0 x0))) graph-height))
      (let [span (* (:fps state 60.0) (:duration bounds))
            step (max 1.0 (Math/pow 10.0 (Math/floor (Math/log10 (/ span (max 2.0 (/ graph-width 80.0)))))))
            first-frame (* step (Math/ceil (/ (* (:fps state 60.0) (:time-start bounds 0.0)) step)))]
        (.setStroke gc (Color/web "#262626")) (.setLineWidth gc 1.0)
        (doseq [frame (range first-frame (+ first-frame span step) step)
                :let [[x _] (value->point bounds [(/ frame (:fps state 60.0)) 0.0])]]
          (.strokeLine gc x top x (+ top graph-height))))
      (.setFill gc (Color/web "#454649")) (.fillRect gc 0.0 0.0 width top)
      (.setFont gc (Font/font 11.0)) (.setLineWidth gc 1.0)
      (when-not (:external-ruler state)
        (let [fps (double (:fps state 60.0))
              frame-span (* fps (:duration bounds))
              step (max 1.0 (Math/pow 10.0 (Math/ceil (Math/log10 (/ frame-span (max 2.0 (/ graph-width 90.0)))))))
              first-frame (* step (Math/ceil (/ (* fps (:time-start bounds 0.0)) step)))]
          (doseq [frame (range first-frame (+ first-frame frame-span step) step)
                  :let [[x _] (value->point bounds [(/ frame fps) 0.0])]
                  :when (<= left x (+ left graph-width))]
            (.setStroke gc (Color/web "#343434")) (.strokeLine gc x top x (+ top graph-height))
            (.setFill gc (Color/web "#b7b8ba")) (.fillText gc (str (long frame)) (+ x 3.0) 13.0)
            (dotimes [minor 10]
              (let [mx (+ x (* (/ step 10.0) minor (/ graph-width frame-span)))]
                (when (<= left mx (+ left graph-width))
                  (.setStroke gc (Color/web "#77787b")) (.strokeLine gc mx (- top 7.0) mx top)))))))
      (dotimes [index 5]
        (let [value (- high (* (/ index 4.0) (- high low)))
              [_ y] (value->point bounds [0.0 value])]
          (.setStroke gc (Color/web "#303030")) (.strokeLine gc left y (+ left graph-width) y)
          (.setFill gc (Color/web "#aeb3b8")) (.fillText gc (format "%.0f" value) (+ left graph-width 5.0) (+ y 3.0))))
      (let [[_ zero-y] (value->point bounds [0.0 0.0])]
        (when (<= top zero-y (+ top graph-height))
          (.setStroke gc Color/BLACK) (.strokeLine gc left zero-y (+ left graph-width) zero-y))))
    (.save gc)
    (.beginPath gc) (.rect gc left top graph-width graph-height) (.clip gc)
    (when (pos? (count keys))
      (.setStroke gc (Color/web (get state :color "#77b8f5"))) (.setLineWidth gc 1.0) (.beginPath gc)
      (let [[x y] (value->point bounds [(:time (first keys)) (:value (first keys))])] (.moveTo gc x y))
      (dotimes [index (dec (count keys))]
        (let [key (nth keys index) next-key (nth keys (inc index))
              [x y] (value->point bounds [(:time next-key) (:value next-key)])]
          (case (:interpolation key)
            "stepped" (let [[_ sy] (value->point bounds [(:time key) (:value key)])] (.lineTo gc x sy) (.lineTo gc x y))
            "bezier" (let [[[x1 y1] [x2 y2]] (handle-points bounds (:curve key))] (.bezierCurveTo gc x1 y1 x2 y2 x y))
            (.lineTo gc x y))))
      (let [[x y] (value->point bounds [(+ (:time-start bounds 0.0) (:duration bounds)) (:value (peek keys))])] (.lineTo gc x y))
      (.stroke gc))
    (doseq [key keys]
      (draw-key! gc bounds key (if (or (contains? (:selected-refs state) [(:active-track state) (:time key)])
                                       (and selected-time (== (double selected-time) (double (:time key))))) (Color/web "#b8f4f0") (Color/web (get state :color "#77b8f5"))) 2.5))
    (when segment
      (let [key (:key segment) right (:right segment)
            curve (or (get (:curves drag) [(:active-track state) (:time key)]) (:curve key))
            handles (handle-points bounds curve)
            endpoints (mapv #(value->point bounds [(:time %) (:value %)]) [key right])]
        (.setStroke gc (Color/web "#8d949d")) (.setLineWidth gc 1.0)
        (doseq [[[x y] [hx hy]] (mapv vector endpoints handles)]
          (.strokeLine gc x y hx hy)
          (.setFill gc (Color/web "#b9e69b")) (.fillOval gc (- hx 3.5) (- hy 3.5) 7.0 7.0))))
    (when-let [time (:time state)]
      (let [[x _] (value->point bounds [time 0.0])]
        (.setStroke gc (Color/web "#76d7d4")) (.setLineWidth gc 1.0)
        (.strokeLine gc x top x (+ top graph-height))))
    (when (zero? (count keys))
      (.setFill gc (Color/web "#aeb3b8"))
      (.fillText gc (get state :empty-text "") (+ left 12.0) (+ top 22.0)))
    (.restore gc)))

(defn graph-bounds [canvas-width canvas-height state]
  (let [bounds (curve-bounds canvas-width canvas-height
                             (if (contains? state :channels) (coll/into-> (:channels state) [] (remove :discrete) (mapcat :keys)) (:keys state))
                             (or (:duration state) 1.0))
        bounds (cond-> bounds (:external-ruler state) (assoc :top 0.0 :height (max 1.0 (- canvas-height 18.0))))]
    (if-let [[start end low high] (:view-range state)]
      (assoc bounds :time-start start :duration (- end start) :low low :high high)
      bounds)))

(defn pan-range [bounds [dx dy]]
  (let [dt (* (/ (- dx) (:width bounds)) (:duration bounds))
        dv (* (/ dy (:height bounds)) (- (:high bounds) (:low bounds)))
        start (+ (:time-start bounds 0.0) dt)]
    [start (+ start (:duration bounds)) (+ (:low bounds) dv) (+ (:high bounds) dv)]))

(defn zoom-range [bounds point [x-factor y-factor]]
  (let [[time value] (point->value bounds point)
        start (:time-start bounds 0.0)
        x-factor (max 0.05 (min 20.0 x-factor))
        y-factor (max 0.05 (min 20.0 y-factor))
        span (max 0.001 (min 100000.0 (/ (:duration bounds) x-factor)))
        value-span (max 0.0001 (min 1.0e9 (/ (- (:high bounds) (:low bounds)) y-factor)))
        left (- time (* span (/ (- time start) (:duration bounds))))
        low (- value (* value-span (/ (- value (:low bounds)) (- (:high bounds) (:low bounds)))))]
    [left (+ left span) low (+ low value-span)]))

(defn- draw! [^Canvas canvas state drag]
  (if-not (contains? state :channels)
    (draw-channel! canvas (assoc state :bounds (graph-bounds (.getWidth canvas) (.getHeight canvas) state)) drag)
    (let [bounds (or (:bounds drag) (graph-bounds (.getWidth canvas) (.getHeight canvas) state))
          colors ["#77b8f5" "#e8ab65" "#80db38" "#ce91dc" "#ee7e7e"]]
      (draw-channel! canvas (assoc state :keys [] :bounds bounds :empty-text "") nil)
      (doseq [channel (:stored-channels state) :when (not (:discrete channel))]
        (draw-channel! canvas (assoc state :keys (:keys channel) :bounds bounds :overlay true
                                     :selected-refs #{} :selected-key nil :selected-time nil :time nil :color "#62676b") nil))
      (doseq [[index channel] (coll/into-> (:channels state) [] (map-indexed vector))
              :when (not (:discrete channel))]
        (let [active (= (:active-track state) (:property channel))]
          (draw-channel! canvas
                         (assoc state :keys (:keys channel) :bounds bounds :overlay true
                                :selected-time (when active (:selected-time state))
                                :selected-key nil :active-track (:property channel)
                                :color (get {"rotation" "#80db38" "x" "#dc7373" "y" "#83c97f"} (:property-name channel (:property channel)) (nth colors (mod index (count colors)))))
                         drag))))))

(defn- channels [state]
  (if (contains? state :channels) (filterv #(not (:discrete %)) (:channels state)) [{:property nil :keys (:keys state)}]))

(defn- graph-keys [state]
  (coll/into-> (channels state) []
    (mapcat (fn [channel]
              (mapv #(assoc % :track (:property channel)) (:keys channel))))))

(defn- selected-segments [state]
  (coll/into-> (channels state) []
    (mapcat (fn [{:keys [property keys]}]
              (coll/into-> (range (max 0 (dec (count keys)))) []
                (keep (fn [index]
                        (let [key (nth keys index)]
                          (when (contains? (:selected-refs state) [property (:time key)])
                            {:key (assoc key :track property) :right (nth keys (inc index))
                             :previous (get keys (dec index) key)
                             :following (get keys (+ index 2) (nth keys (inc index)))})))))))))

(defn- tangent-curve [{:keys [key right previous following]} mode]
  (let [dt (/ (- (:time right) (:time key)) 3.0)
        slope (fn slope [left right]
                (if (== (:time left) (:time right)) 0.0 (/ (- (:value right) (:value left)) (- (:time right) (:time left)))))
        outgoing (case mode (:flat :ease-out) 0.0 :smooth (slope previous right) (slope key right))
        incoming (case mode (:flat :ease-in) 0.0 :smooth (slope key following) (slope key right))]
    [(+ (:time key) dt) (+ (:value key) (* dt outgoing))
     (- (:time right) dt) (- (:value right) (* dt incoming))]))

(defn- make-view-impl [source-state commit-curves!]
  (let [canvas (doto (Canvas. 600.0 180.0) (.setId "skeletal-curve-canvas") (.setFocusTraversable true))
        node (doto (StackPane.) (.setId "skeletal-curve-editor") (.setMinSize 100.0 80.0))
        drag (atom nil)
        marquee (atom nil)
        key-drag (atom nil)
        navigation (atom nil)
        view-range (atom nil)
        source-token (atom nil)
        selection (atom nil)
        active (atom nil)
        read-state! (fn read-curve-state []
                      (let [state (source-state)
                            token (:selection-token state)
                            _ (when (not= token @source-token)
                                (reset! source-token token) (reset! selection nil) (reset! active nil) (reset! view-range nil))
                            [track time] (or (:active-ref state) @active [(:property (first (channels state))) (or (get-in state [:selected-key :time]) (:selected-time state))])
                            channel (coll/first-where #(= track (:property %)) (channels state))
                            selected (if (contains? state :selected-refs) (:selected-refs state)
                                         (if (some? @selection) @selection (if time #{[track time]} #{})))]
                        (-> state (dissoc :selected-key)
                            (assoc :view-range @view-range :keys (:keys channel) :active-track track :selected-time (when (contains? selected [track time]) time) :selected-refs selected))))
        last-token (atom nil)
        refresh (fn refresh-curves []
                  (let [original (read-state!)
                        previews (:changes @key-drag)
                        moved (fn moved [keys track]
                                (mapv (fn [key]
                                        (if-let [change (coll/first-where #(= [track (:time key)] [(get-in % [:key :track]) (get-in % [:key :time])]) previews)]
                                          (cond-> (assoc key :time (:time change) :value (:value change))
                                            (= "bezier" (:interpolation key))
                                            (update :curve (fn move-controls [controls]
                                                             (let [dt (- (:time change) (:time key)) dv (- (:value change) (:value key))]
                                                               (mapv + controls [dt dv dt dv]))))) key)) keys))
                        state (cond-> original
                                previews (update :keys moved (:active-track original))
                                (and previews (:channels original)) (update :channels #(mapv (fn [channel] (update channel :keys moved (:property channel))) %)))
                        token [(dissoc state :on-selection! :on-time! :on-edit-keys!) @drag @marquee (.getWidth canvas) (.getHeight canvas)]]
                    (when (not= token @last-token)
                      (draw! canvas state (or @drag (when @key-drag {:bounds (:bounds @key-drag)})))
                      (when-let [{:keys [start end]} @marquee]
                        (let [^GraphicsContext gc (.getGraphicsContext2D canvas)
                              x (min (first start) (first end)) y (min (second start) (second end))
                              width (abs (- (first start) (first end))) height (abs (- (second start) (second end)))]
                          (.setFill gc (Color/color 0.3 0.6 1.0 0.12)) (.fillRect gc x y width height)
                          (.setStroke gc (Color/web "#77b8f5")) (.setLineWidth gc 1.0) (.strokeRect gc x y width height)))
                      (reset! last-token token))))
        publish-selection! (fn publish-selection []
                             (when (some? @selection)
                               (when-let [f (:on-selection! (source-state))] (f @selection @active))))
        select-all! (fn select-all-curves []
                      (let [refs (mapv (juxt :track :time) (graph-keys (read-state!)))]
                        (reset! selection (set refs)) (reset! active (first refs)) (publish-selection!) (refresh)))
        interpolate! (fn interpolate-curves [kind]
                       (let [changes (mapv (fn [segment]
                                             {:key (:key segment) :curve (if (= "bezier" kind) (tangent-curve segment :linear) kind)})
                                           (selected-segments (read-state!)))]
                         (when (pos? (count changes)) (commit-curves! changes) (refresh))))
        tangents! (fn edit-tangents [mode]
                    (let [changes (mapv (fn [segment] {:key (:key segment) :curve (tangent-curve segment mode)})
                                        (selected-segments (read-state!)))]
                      (when (pos? (count changes)) (commit-curves! changes) (refresh))))]
    (.add (.getChildren node) canvas)
    (.bind (.widthProperty canvas) (.widthProperty node))
    (.bind (.heightProperty canvas) (.heightProperty node))
    (doseq [^DoubleProperty property [(.widthProperty canvas) (.heightProperty canvas)]]
      (.addListener property (reify ChangeListener (changed [_ _ _ _] (refresh)))))
    (.setOnMousePressed canvas
                        (ui/event-handler event
                          (when (#{MouseButton/SECONDARY MouseButton/MIDDLE} (.getButton ^MouseEvent event))
                            (reset! navigation {:point [(.getX ^MouseEvent event) (.getY ^MouseEvent event)]
                                                :bounds (graph-bounds (.getWidth canvas) (.getHeight canvas) (read-state!))
                                                :zoom (.isAltDown ^MouseEvent event)})
                            (.consume ^MouseEvent event))
                          (when (= MouseButton/PRIMARY (.getButton ^MouseEvent event))
                            (.requestFocus canvas)
                            (let [state (read-state!) bounds (graph-bounds (.getWidth canvas) (.getHeight canvas) state)
                                  point [(.getX ^MouseEvent event) (.getY ^MouseEvent event)]
                                  {:keys [key]} (selected-segment state)
                                  handle (when key
                                           (reduce-kv (fn [_ index [x y]]
                                                        (when (< (Math/hypot (- x (first point)) (- y (second point))) 10.0) (reduced index)))
                                                      nil (handle-points bounds (:curve key))))
                                  hit (coll/first-where (fn [key]
                                                          (let [[x y] (value->point bounds [(:time key) (:value key)])]
                                                            (< (Math/hypot (- x (first point)) (- y (second point))) 9.0))) (graph-keys state))
                                  extend (or (.isShiftDown ^MouseEvent event) (.isMetaDown ^MouseEvent event) (.isControlDown ^MouseEvent event))]
                              (cond
                                (< (second point) (:top bounds))
                                (do (when-let [f (:on-time! state)] (f (max 0.0 (first (point->value bounds point)))))
                                    (reset! navigation {:scrub true}))
                                handle (let [segments (filterv #(= "bezier" (get-in % [:key :interpolation])) (selected-segments state))]
                                         (reset! drag {:key (assoc key :track (:active-track state)) :handle handle :bounds bounds
                                                       :segments segments :origin (subvec (:curve key) (* handle 2) (+ (* handle 2) 2))
                                                       :curves (coll/into-> segments {} (map (fn [{:keys [key]}] [[(:track key) (:time key)] (:curve key)])))})
                                         (.consume ^MouseEvent event))
                                hit (let [ref [(:track hit) (:time hit)]]
                                      (reset! active ref)
                                      (when-let [f (:on-time! state)] (f (:time hit)))
                                      (swap! selection (fn [selected]
                                                         (if extend (let [selected (or selected (:selected-refs state))]
                                                                      ((if (contains? selected ref) disj conj) selected ref))
                                                             (if (contains? (:selected-refs state) ref) (:selected-refs state) #{ref}))))
                                      (when (and (not extend) (:on-edit-keys! state))
                                        (reset! key-drag {:point point :bounds bounds :hit hit
                                                          :keys (let [selected (filterv #(contains? @selection [(:track %) (:time %)]) (graph-keys state))]
                                                                  (if (or (:shift state) (.isAltDown ^MouseEvent event))
                                                                    (let [starts (reduce (fn [m key] (update m (:track key) (fn [t] (min (or t (:time key)) (:time key))))) {} selected)]
                                                                      (filterv #(when-let [t (get starts (:track %))] (>= (:time %) t)) (graph-keys state)))
                                                                    selected))})))
                                :else (do (reset! marquee {:start point :end point :baseline (:selected-refs state) :extend extend})
                                          (when-not extend (reset! selection #{}) (reset! active nil))))
                              (publish-selection!) (refresh)))))
    (.setOnMouseDragged canvas
                        (ui/event-handler event
                          (let [point [(.getX ^MouseEvent event) (.getY ^MouseEvent event)]]
                            (cond
                              @navigation
                              (let [{start :point bounds :bounds zoom :zoom scrub :scrub} @navigation]
                                (if scrub
                                  (when-let [f (:on-time! (read-state!))]
                                    (f (max 0.0 (first (point->value (graph-bounds (.getWidth canvas) (.getHeight canvas) (read-state!)) point)))))
                                  (reset! view-range (if zoom
                                                       (zoom-range bounds start [(Math/exp (* 0.005 (- (first point) (first start))))
                                                                                 (Math/exp (* -0.005 (- (second point) (second start))))])
                                                       (pan-range bounds (mapv - point start)))))
                                (refresh) (.consume ^MouseEvent event))
                              @key-drag
                              (let [{:keys [bounds hit keys] origin :point} @key-drag
                                    [time value] (point->value bounds point)
                                    [start-time start-value] (point->value bounds origin)
                                    fps (double (:fps (read-state!) 60.0))
                                    target (+ (:time hit) (- time start-time))
                                    target (if (.isShiftDown ^MouseEvent event) target (/ (Math/round (* target fps)) fps))
                                    dt (if (:offset (read-state!)) (- target (:time hit))
                                           (max (- (reduce min (mapv :time keys))) (- target (:time hit))))
                                    dv (- value start-value)]
                                (when (> (Math/hypot (- (first point) (first origin)) (- (second point) (second origin))) 3.0)
                                  (swap! key-drag assoc :changes (mapv (fn [key] {:key key :time (+ (:time key) dt) :value (+ (:value key) dv)}) keys)))
                                (refresh) (.consume ^MouseEvent event))
                              @drag (let [{:keys [bounds handle origin segments]} @drag
                                          target (point->value bounds point)
                                          delta (mapv - target origin)]
                                      (swap! drag assoc :curves
                                             (coll/into-> segments {}
                                               (map (fn [{:keys [key right]}]
                                                      (let [curve (:curve key) index (* handle 2)]
                                                        [[(:track key) (:time key)]
                                                         (assoc curve index (max (:time key) (min (:time right) (+ (nth curve index) (first delta))))
                                                                (inc index) (+ (nth curve (inc index)) (second delta)))])))))
                                      (refresh) (.consume ^MouseEvent event))
                              @marquee (let [{:keys [start baseline extend]} @marquee
                                             state (read-state!) bounds (graph-bounds (.getWidth canvas) (.getHeight canvas) state)
                                             selected (coll/into-> (graph-keys state) (if extend baseline #{})
                                                        (keep (fn [key]
                                                                (let [[x y] (value->point bounds [(:time key) (:value key)])]
                                                                  (when (and (<= (min (first start) (first point)) x (max (first start) (first point)))
                                                                             (<= (min (second start) (second point)) y (max (second start) (second point))))
                                                                    [(:track key) (:time key)])))))]
                                         (swap! marquee assoc :end point) (reset! selection selected)
                                         (reset! active (first (sort-by pr-str selected))) (publish-selection!) (refresh) (.consume ^MouseEvent event))))))
    (.setOnMouseReleased canvas
                         (ui/event-handler event
                           (when-let [changes (:changes @key-drag)]
                             (when-let [f (:on-edit-keys! (read-state!))] (f changes))
                             (let [refs (mapv (fn [change] [(get-in change [:key :track]) (:time change)]) changes)]
                               (reset! selection (set refs)) (reset! active (first refs)) (publish-selection!)))
                           (reset! key-drag nil)
                           (when-let [{:keys [segments curves]} @drag]
                             (let [changes (coll/into-> segments []
                                             (keep (fn [{:keys [key]}]
                                                     (let [curve (get curves [(:track key) (:time key)])]
                                                       (when (not= (:curve key) curve) {:key key :curve curve})))))]
                               (reset! drag nil) (when (pos? (count changes)) (commit-curves! changes)) (.consume ^MouseEvent event)))
                           (reset! marquee nil) (reset! navigation nil) (refresh)))
    (.setOnScroll canvas
                  (ui/event-handler event
                    (let [state (read-state!)
                          bounds (graph-bounds (.getWidth canvas) (.getHeight canvas) state)
                          factor (Math/exp (* 0.002 (.getDeltaY ^ScrollEvent event)))]
                      (reset! view-range (zoom-range bounds [(.getX ^ScrollEvent event) (.getY ^ScrollEvent event)] [factor factor]))
                      (refresh) (.consume ^ScrollEvent event))))
    (.setOnKeyPressed canvas
                      (ui/event-handler event
                        (cond
                          (= KeyCode/ESCAPE (.getCode ^javafx.scene.input.KeyEvent event))
                          (do (when @marquee (reset! selection (:baseline @marquee)))
                              (reset! drag nil) (reset! key-drag nil) (reset! marquee nil) (reset! navigation nil) (publish-selection!) (refresh) (.consume ^javafx.scene.input.KeyEvent event))
                          (and (= KeyCode/A (.getCode ^javafx.scene.input.KeyEvent event))
                               (or (.isMetaDown ^javafx.scene.input.KeyEvent event) (.isControlDown ^javafx.scene.input.KeyEvent event)))
                          (do (select-all!) (.consume ^javafx.scene.input.KeyEvent event)))))
    (refresh)
    {:node node :refresh! refresh :read-state! read-state!
     :frame! (fn frame-curves []
               (let [state (read-state!)
                     keys (filterv #(contains? (:selected-refs state) [(:track %) (:time %)]) (graph-keys state))]
                 (if (< (count keys) 2)
                   (reset! view-range nil)
                   (let [start (reduce min (mapv :time keys)) end (reduce max (mapv :time keys))
                         margin (* 0.1 (max 0.1 (- end start)))
                         bounds (curve-bounds (.getWidth canvas) (.getHeight canvas) keys (:duration state))]
                     (reset! view-range [(- start margin) (+ end margin) (:low bounds) (:high bounds)]))))
               (refresh))
     :auto! (fn auto-curves [enabled]
              (if enabled (reset! view-range nil)
                  (let [bounds (graph-bounds (.getWidth canvas) (.getHeight canvas) (read-state!))]
                    (reset! view-range [(:time-start bounds 0.0) (+ (:time-start bounds 0.0) (:duration bounds)) (:low bounds) (:high bounds)])))
              (refresh))
     :zoom! (fn zoom-horizontal [factor]
              (let [bounds (graph-bounds (.getWidth canvas) (.getHeight canvas) (read-state!))
                    point [(+ (:left bounds) (* 0.5 (:width bounds))) (+ (:top bounds) (* 0.5 (:height bounds)))]]
                (reset! view-range (zoom-range bounds point [factor 1.0])) (refresh)))
     :view-range (fn current-range [] @view-range) :interpolate! interpolate! :tangents! tangents! :select-all! select-all!
     :selected-refs (fn selected-refs [] (:selected-refs (read-state!)))
     :set-selection! (fn set-selection [refs]
                       (reset! selection (set refs)) (reset! active (first refs)) (publish-selection!) (refresh))}))

(defn make-view
  "Legacy callback receives key/curve pairs. Use make-batch-view to commit a
  multi-selection as one undoable document change."
  [read-state! commit-curve!]
  (make-view-impl read-state!
                  (fn commit-legacy-curves [changes] (run! (fn [{:keys [key curve]}] (commit-curve! (cond-> key (nil? (:track key)) (dissoc :track)) curve)) changes))))

(defn make-batch-view
  "Native curve canvas with modifier-click/marquee multi-selection, batch
  interpolation/tangents and handle dragging. commit-curves! receives one
  vector of {:key key-with-track :curve controls-or-kind} per completed edit."
  [read-state! commit-curves!]
  (make-view-impl read-state! commit-curves!))

(defn- channel-row [id title kind selected toggle! select!]
  (let [icon (doto (SVGPath.)
               (.setContent (case kind
                              :animation "M1 6 L7 1 L13 6 L7 11 Z M1 6 L7 11 L7 15 M13 6 L13 11 L7 15"
                              :bone "M7 0 L7 14 M0 7 L14 7 M4 4 L10 4 L10 10 L4 10 Z"
                              "rotation" "M11 3 A6 6 0 1 0 13 9 M11 3 L6 3 M11 3 L11 8"
                              "M1 7 L13 7 M10 4 L13 7 L10 10 M7 1 L7 13 M4 4 L7 1 L10 4"))
               (.setFill Color/TRANSPARENT)
               (.setStroke (Color/web (case kind "rotation" "#92cd4d" (:bone :animation) "#c4e5e5" "#8cc9e0")))
               (.setStrokeWidth 1.0))
        label (doto (Label. title icon) (.setMaxWidth Double/MAX_VALUE))
        dot (doto (Circle. 3.0) (.setFill (if selected (Color/web "#dddddd") (Color/web "#515151"))))
        visibility (doto (Button. "" dot) (.setFocusTraversable false) (ui/add-style! "curve-visible-dot"))
        row (doto (HBox. 4.0 (into-array javafx.scene.Node [label visibility]))
              (.setId id) (.setAlignment javafx.geometry.Pos/CENTER_LEFT)
              (ui/add-style! (case kind :animation "curve-animation-row" :bone "curve-bone-row" "curve-property-row")))]
    (HBox/setHgrow label Priority/ALWAYS)
    (.setOnAction visibility (ui/event-handler _ (toggle!)))
    (.setOnMouseClicked label (ui/event-handler _ (select!)))
    row))

(defn make-multichannel-batch-view [read-state! commit-curves!]
  (let [hidden (atom #{}) query (atom "") preset (atom nil)
        strip (ToolBar.) transport-host (VBox. 0.0) rows (VBox. 1.0)
        labels (:labels (read-state!))
        title (fn title [id] (get labels id id))
        filter-field (doto (TextField.) (.setId "skeletal-curve-filter")
                           (.setPromptText (title "filter")) (.setPrefWidth 168.0))
        row-scroll (doto (ScrollPane. rows) (.setFitToWidth true) (.setPrefWidth 230.0) (.setMinWidth 230.0) (.setMaxWidth 230.0) (.setId "skeletal-curve-row-scroll"))
        zoom (doto (Slider. -4.0 4.0 0.0) (.setId "skeletal-curve-zoom") (.setMinWidth 20.0) (.setMaxWidth Double/MAX_VALUE))
        zoom-value (atom 0.0)
        zoom-out (doto (Button. "−") (.setFocusTraversable false))
        zoom-in (doto (Button. "+") (.setFocusTraversable false))
        zoom-fit (doto (Button. "↔") (.setFocusTraversable false))
        zoom-row (doto (HBox. 1.0 (into-array javafx.scene.Node [zoom-out zoom zoom-in zoom-fit]))
                   (.setId "skeletal-curve-zoom-bar") (.setAlignment javafx.geometry.Pos/CENTER_LEFT))
        left (doto (BorderPane.) (.setCenter row-scroll) (.setBottom zoom-row) (.setMinWidth 230.0) (.setPrefWidth 230.0) (.setMaxWidth 230.0))
        pane (doto (BorderPane.) (.setId "skeletal-multichannel-curves"))
        order (atom nil)
        view (make-batch-view (fn read-visible-curves []
                                (let [state (read-state!)]
                                  (update state :channels
                                          #(filterv (fn [channel]
                                                      (and (not (contains? @hidden (:property channel)))
                                                           (string/includes? (string/lower-case (get (:labels state) (:property channel) (:property channel))) @query))) %)))) commit-curves!)
        buttons (mapv
                  (fn [[kind path]]
                    (let [icon (doto (SVGPath.) (.setContent path) (.setFill Color/TRANSPARENT)
                                     (.setStroke (Color/web "#b5d2a1")) (.setStrokeWidth 1.4))
                          control (doto (ToggleButton.) (.setId (str "skeletal-curve-" kind))
                                        (.setGraphic icon) (.setFocusTraversable false) (.setTooltip (Tooltip. (title kind))))]
                      (.setOnAction control
                                    (ui/event-handler _
                                      (if (contains? #{"flat" "smooth" "ease-in" "ease-out"} kind)
                                        ((:tangents! view) (keyword kind))
                                        ((:interpolate! view) kind))
                                      (reset! preset kind)))
                      control))
                  [["stepped" "M1,14 L9,14 L9,2 L17,2"]
                   ["linear" "M1,14 L17,2"] ["bezier" "M1,14 C13,14 4,2 17,2"]
                   ["smooth" "M1,14 C6,14 5,2 10,2 C14,2 13,10 17,10"]
                   ["flat" "M1,8 L17,8 M9,5 L9,11"]
                   ["ease-in" "M1,14 C2,3 7,2 17,2"]
                   ["ease-out" "M1,14 C11,14 16,13 17,2"]])
        frame (doto (Button. (title "frame")) (.setId "skeletal-curve-frame") (.setFocusTraversable false))
        auto (doto (ToggleButton. (title "auto")) (.setId "skeletal-curve-auto") (.setSelected true) (.setFocusTraversable false))
        refresh (fn refresh-multichannel []
                  (let [state (read-state!) ids (mapv :property (:channels state))
                        row-token [(:selection-token state) ids @hidden (:selected-refs state)]]
                    (when (not= row-token @order)
                      (reset! order row-token) (reset! preset nil) (.clear (.getChildren rows))
                      (let [all-ids (set ids)
                            toggle-ids! (fn toggle-ids [ids]
                                          (swap! hidden (fn [hidden]
                                                          (if (coll/every? #(contains? hidden %) ids)
                                                            (reduce disj hidden ids) (into hidden ids))))
                                          (reset! order nil))
                            overview (channel-row "skeletal-curve-overview" (:animation-name state "") :animation
                                                  (not (coll/every? #(contains? @hidden %) all-ids))
                                                  #(toggle-ids! all-ids) (:select-all! view))]
                        (.add (.getChildren rows) overview)
                        (doseq [channels (coll/into-> (:channels state) [] (partition-by :owner))
                                :let [owner (:owner (first channels) (:entity-name state ""))
                                      group-ids (mapv :property channels)]]
                          (.add (.getChildren rows)
                                (channel-row (str "skeletal-curve-owner-" owner) owner :bone
                                             (not (coll/every? #(contains? @hidden %) group-ids))
                                             #(toggle-ids! group-ids)
                                             (fn select-owner []
                                               ((:set-selection! view) (coll/into-> channels []
                                                                         (mapcat (fn [channel] (mapv #(vector (:property channel) (:time %)) (:keys channel)))))))))
                          (doseq [{:keys [property property-name keys label]} channels]
                            (.add (.getChildren rows)
                                  (channel-row (str "skeletal-channel-" property)
                                               (or label (get (:labels state) property-name (get (:labels state) property property)))
                                               property-name (not (contains? @hidden property))
                                               #(toggle-ids! [property])
                                               (fn select-channel [] ((:set-selection! view) (mapv #(vector property (:time %)) keys)))))))))
                    (let [curve-state ((:read-state! view))
                          segments (selected-segments curve-state)
                          kinds (set (mapv #(get-in % [:key :interpolation] "linear") segments))]
                      (doseq [^ToggleButton control buttons]
                        (let [kind (subs (.getId control) (count "skeletal-curve-"))]
                          (.setDisable control (zero? (count segments)))
                          (.setSelected control (if @preset (= kind @preset) (= #{kind} kinds))))))
                    (.setSelected auto (nil? ((:view-range view))))
                    ((:refresh! view))))]
    (.setOnAction frame (ui/event-handler _ ((:frame! view)) (refresh)))
    (.setOnAction auto (ui/event-handler _ ((:auto! view) (.isSelected auto)) (refresh)))
    (.addAll (.getItems strip) ^java.util.Collection (into buttons [frame auto]))
    (.addListener (.textProperty filter-field)
                  (reify ChangeListener (changed [_ _ _ value]
                                          (reset! query (string/lower-case (str value))) (refresh))))
    (HBox/setHgrow zoom Priority/ALWAYS)
    (.setOnAction zoom-out (ui/event-handler _ (.setValue zoom (max -4.0 (- (.getValue zoom) 0.25)))))
    (.setOnAction zoom-in (ui/event-handler _ (.setValue zoom (min 4.0 (+ (.getValue zoom) 0.25)))))
    (.setOnAction zoom-fit (ui/event-handler _ ((:frame! view))))
    (.addListener (.valueProperty zoom)
                  (reify ChangeListener
                    (changed [_ _ _ value]
                      (let [value (double value)]
                        ((:zoom! view) (Math/pow 2.0 (- value @zoom-value)))
                        (reset! zoom-value value)))))
    (.setId rows "skeletal-curve-rows")
    (.add (.getChildren transport-host) strip)
    (.setTop pane transport-host) (.setLeft pane left) (.setCenter pane (:node view))
    (assoc view :node pane :transport-host transport-host :refresh! refresh)))

(defn make-multichannel-view [read-state! commit-curve!]
  (make-multichannel-batch-view read-state!
                                (fn commit-legacy-curves [changes]
                                  (run! (fn [{:keys [key curve]}] (commit-curve! (cond-> key (nil? (:track key)) (dissoc :track)) curve)) changes))))
