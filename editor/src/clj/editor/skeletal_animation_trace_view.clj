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

(ns editor.skeletal-animation-trace-view
  (:require [clojure.java.io :as io]
            [editor.ui :as ui])
  (:import [javafx.animation PauseTransition]
           [javafx.beans.value ChangeListener]
           [javafx.geometry Insets Pos]
           [javafx.scene Node]
           [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.control Button CheckBox Label ProgressIndicator Slider TextField ToggleButton Tooltip]
           [javafx.scene.image Image]
           [javafx.scene.input KeyCode MouseButton]
           [javafx.scene.layout FlowPane HBox Priority StackPane VBox]
           [javafx.scene.paint Color]
           [javafx.util Duration]))

(def defaults {"detail" 20 "concavity" 50 "refinement" 50 "uniform-percent" 30 "alpha" 8 "padding" 0})

(defn normalize-settings
  "Integer controls, with a lossless scale migration for legacy uniform ratios."
  [settings]
  (let [settings (merge defaults (cond-> settings
                                   (and (contains? settings "uniform") (not (contains? settings "uniform-percent")))
                                   (assoc "uniform-percent" (* 100.0 (get settings "uniform")))))]
    (reduce (fn [settings [id minimum maximum]]
              (if-let [value (get settings id)]
                (assoc settings id (max minimum (min maximum (Math/round (double value)))))
                settings))
            (dissoc settings "uniform")
            [["detail" 0 100] ["concavity" 0 100] ["refinement" 0 100] ["uniform-percent" 0 100]
             ["alpha" 1 255] ["padding" 0 32] ["bone-count" 2 32] ["control-count" 2 8]])))

(def ^:private style-sheet (io/resource "skeletal-trace.css" (clojure.lang.RT/baseLoader)))

(defn- paint-track! [^Slider slider]
  (when-let [^Node track (.lookup slider ".track")]
    (let [percent (* 100.0 (/ (- (.getValue slider) (.getMin slider)) (- (.getMax slider) (.getMin slider))))]
      (.setStyle track (format "-fx-background-color: linear-gradient(to right, #788b8d 0%%, #788b8d %.3f%%, #242526 %.3f%%, #242526 100%%);" percent percent)))))

(defn show!
  "Isolated UV preview: only Apply commits. Async results are versioned so old
  parameter results and closed previews can never replace the current result."
  [{:keys [host image-url settings propose revise apply-result tr breadcrumb rig-mode]}]
  (let [host ^StackPane host
        image (Image. (str image-url))
        canvas (doto (Canvas.) (.setId "skeletal-trace-canvas"))
        overlay (doto (StackPane.) (.setId "skeletal-trace-preview") (.setFocusTraversable true))
        panel (doto (VBox. 6.0) (.setId "skeletal-trace-panel") (.setPadding (Insets. 7.0)) (.setMaxWidth 530.0) (.setMaxHeight VBox/USE_PREF_SIZE))
        group (doto (VBox. 8.0) (.setMaxWidth 530.0) (.setMaxHeight VBox/USE_PREF_SIZE) (.setAlignment Pos/CENTER))
        title (Label. (tr (if rig-mode "auto-rig" "trace-title")))
        exit (doto (Button. "×") (.setId "skeletal-trace-close") (.setMinWidth 24.0) (.setPrefWidth 24.0) (.setMaxWidth 24.0))
        adjust (doto (ToggleButton. (tr "trace-adjust")) (.setId "skeletal-trace-adjust") (.setSelected true))
        path (doto (Label. breadcrumb) (.setStyle "-fx-background-color: #393a3d; -fx-padding: 4 9; -fx-background-radius: 3;"))
        status (doto (Label.) (.setId "skeletal-trace-status") (.setWrapText true))
        accept (doto (Button. (tr "confirm")) (.setId "skeletal-trace-confirm") (.setDisable true))
        cancel (doto (Button. (tr "cancel")) (.setId "skeletal-trace-cancel"))
        refresh (doto (Button. (tr "trace-refresh")) (.setId "skeletal-trace-refresh"))
        triangles (doto (CheckBox. (tr "trace-triangles")) (.setId "skeletal-trace-triangles"))
        reverse-button (doto (CheckBox. (tr "auto-rig-reverse")) (.setId "skeletal-rig-reverse"))
        loading (doto (HBox. 8.0 (into-array Node [(doto (ProgressIndicator. -1.0) (.setPrefSize 18.0 18.0) (.setMaxSize 18.0 18.0))
                                                   (Label. (tr "trace-working"))]))
                  (.setId "skeletal-trace-loading") (.setAlignment Pos/CENTER)
                  (.setStyle "-fx-background-color: #303235; -fx-padding: 7 12; -fx-background-radius: 5;")
                  (.setMaxWidth HBox/USE_PREF_SIZE) (.setVisible false))
        state (atom {:settings (normalize-settings settings) :version 0 :closed false :busy false :zoom 1.0 :pan [0.0 0.0] :selected #{} :history [] :redo []})
        timer (PauseTransition. (Duration/millis 250.0))
        draw (fn draw-preview []
               (let [^GraphicsContext gc (.getGraphicsContext2D canvas)
                     w (.getWidth canvas) h (.getHeight canvas)
                     iw (.getWidth image) ih (.getHeight image)
                     {:keys [result zoom pan]} @state
                     available (max 60.0 (- h (.getHeight group) 55.0))
                     scale (* zoom (min (/ (max 1.0 (- w 70.0)) iw) (/ available ih)))
                     x (+ (* 0.5 (- w (* iw scale))) (first pan))
                     y (+ (* 0.5 (- available (* ih scale))) (second pan))
                     mesh (:preview result)
                     points (mapv (fn [[u v]] [(+ x (* u iw scale)) (+ y (* v ih scale))]) (partitionv 2 (or (:draft-uvs @state) (:uvs mesh))))]
                 (swap! state assoc :screen-points points :view-transform [x y (* iw scale) (* ih scale)])
                 (.setFill gc (Color/web "#5b5c5f")) (.fillRect gc 0.0 0.0 w h)
                 (.setFill gc (Color/web "#626366"))
                 (doseq [row (range (long (Math/ceil (/ h 20.0)))) col (range (long (Math/ceil (/ w 20.0)))) :when (even? (+ row col))]
                   (.fillRect gc (* col 20.0) (* row 20.0) 20.0 20.0))
                 (.setStroke gc (Color/web "#414144")) (.setLineWidth gc 1.0)
                 (.strokeLine gc (+ x (* iw scale 0.5)) 0.0 (+ x (* iw scale 0.5)) h)
                 (.strokeLine gc 0.0 (+ y (* ih scale 0.5)) w (+ y (* ih scale 0.5)))
                 (.drawImage gc image x y (* iw scale) (* ih scale))
                 (.setStroke gc (Color/web "#c4c5c6")) (.setLineWidth gc 1.0)
                 (.strokeRect gc x y (* iw scale) (* ih scale))
                 (.setStroke gc Color/WHITE) (.setLineWidth gc 2.0)
                 (doseq [[px py dx dy] [[x y 1 1] [(+ x (* iw scale)) y -1 1]
                                        [x (+ y (* ih scale)) 1 -1] [(+ x (* iw scale)) (+ y (* ih scale)) -1 -1]]]
                   (.strokeLine gc px py (+ px (* 12 dx)) py)
                   (.strokeLine gc px py px (+ py (* 12 dy))))
                 (when (and mesh (.isSelected triangles))
                   (.setStroke gc (Color/web "#a0c8cc" 0.5))
                   (doseq [[a b c] (partitionv 3 (:triangles mesh)) [i j] [[a b] [b c] [c a]]]
                     (let [[ax ay] (points i) [bx by] (points j)] (.strokeLine gc ax ay bx by))))
                 (.setStroke gc (Color/web "#8df0ee")) (.setFill gc (Color/web "#8df0ee")) (.setLineWidth gc 1.3)
                 (doseq [[a b] (:constrained-edges mesh)]
                   (let [[ax ay] (points a) [bx by] (points b)] (.strokeLine gc ax ay bx by)))
                 (doseq [[index [px py]] (map-indexed vector points)]
                   (let [selected (contains? (:selected @state) index) hover (= index (:hover @state))
                         radius (if (or selected hover) 4.0 2.2)]
                     (.setFill gc (if selected Color/WHITE (Color/web "#8df0ee")))
                     (.fillOval gc (- px radius) (- py radius) (* radius 2) (* radius 2))
                     (when hover (.strokeOval gc (- px 6) (- py 6) 12 12))))
                 (when rig-mode
                   (let [screen (fn to-screen [[u v]] [(+ x (* u iw scale)) (+ y (* v ih scale))])
                         controls (mapv screen (or (:draft-controls @state) (:rig-controls result)))
                         chain (mapv screen (:rig-guide result))]
                     (swap! state assoc :control-screen-points controls)
                     (.setStroke gc (Color/web "#ffc766")) (.setLineWidth gc 3.0)
                     (doseq [[[ax ay] [bx by]] (partitionv 2 1 chain)] (.strokeLine gc ax ay bx by))
                     (doseq [[i [px py]] (map-indexed vector controls)]
                       (.setFill gc (Color/web "#242526")) (.fillOval gc (- px 7) (- py 7) 14 14)
                       (.strokeOval gc (- px 7) (- py 7) 14 14)
                       (.setFill gc (Color/web "#ffc766")) (.fillText gc (str (inc i)) (+ px 10) (- py 8)))))
                 (when-let [[x0 y0 x1 y1] (:box @state)]
                   (.setFill gc (Color/web "#8df0ee" 0.12))
                   (.fillRect gc (min x0 x1) (min y0 y1) (abs (- x1 x0)) (abs (- y1 y0)))
                   (.strokeRect gc (min x0 x1) (min y0 y1) (abs (- x1 x0)) (abs (- y1 y0))))))
        nearest-control (fn nearest-control [x y]
                          (first (reduce-kv (fn [[_ best-distance :as best] index [px py]]
                                              (let [d (Math/hypot (- px x) (- py y))]
                                                (if (< d best-distance) [index d] best)))
                                            [nil 12.0] (:control-screen-points @state))))
        nearest (fn nearest-vertex [x y]
                  (first (reduce-kv (fn [[_ best-distance :as best] index [px py]]
                                      (let [distance (Math/hypot (- px x) (- py y))]
                                        (if (< distance best-distance) [index distance] best)))
                                    [nil 9.0] (:screen-points @state))))
        close (fn close-preview []
                (.stop timer)
                (swap! state assoc :closed true)
                (.remove (.getChildren host) overlay))
        request-ref (atom nil)
        request (fn request-preview []
                  (when-not (or (:closed @state) (:busy @state))
                    (let [{:keys [version settings manual-input]} @state]
                      (swap! state assoc :busy true :pending false)
                      (.setDisable accept true)
                      (.setText status (tr "trace-working"))
                      (future
                        (let [outcome (try {:result (if manual-input (apply revise manual-input) (propose settings))} (catch Exception error {:error error}))]
                          (ui/run-later
                            (swap! state assoc :busy false)
                            (when-not (:closed @state)
                              (if (not= version (:version @state))
                                (when (:pending @state) (@request-ref))
                                (if-let [error (:error outcome)]
                                  (do (swap! state dissoc :draft-uvs :manual-input) (.setText status (.getMessage ^Exception error)) (draw))
                                  (let [result (assoc (:result outcome) :proposal-settings settings) report (:report result)]
                                    (swap! state #(-> % (assoc :result result) (dissoc :draft-uvs :manual-input)))
                                    (.setDisable accept false)
                                    (.setText status (str (tr "vertices") ": " (get report "vertices") "   "
                                                          (if (get report "manual") (tr "trace-manual") (str (tr "trace-clipped") ": " (get report "clipped_pixels" 0)))
                                                          (when (pos? (get report "ignored_noise_pixels" 0))
                                                            (str "   " (tr "trace-noise") ": " (get report "ignored_noise_pixels")))
                                                          (when rig-mode (str "   " (tr "trace-bone-count") ": " (get report "bones") "   " (tr "trace-control-count") ": " (get report "controls")))))
                                    (draw)))))))))))
        rows (mapv (fn [[id maximum]]
                     (let [value (get (:settings @state) id 3.0)
                           slider (doto (Slider. 0.0 maximum value) (.setId (str "skeletal-trace-" id)))
                           number (doto (TextField.) (.setId (str "skeletal-trace-number-" id)) (.setPrefWidth 52.0) (.setMinWidth 52.0) (.setMaxWidth 52.0)
                                        (.setStyle "-fx-background-color: transparent; -fx-padding: 2 3; -fx-alignment: center-right;"))
                           title (doto (Label. (tr (str "trace-" id))) (.setMinWidth 66.0))
                           row (HBox. 2.0 (into-array Node [title number slider]))
                           changed (fn parameter-changed [value]
                                     (let [value (Math/round (double value))]
                                       (.setText number (str value))
                                       (paint-track! slider)
                                       (when (not= value (get-in @state [:settings id]))
                                         (swap! state #(-> % (assoc-in [:settings id] value) (update :version inc) (assoc :pending true :redo []) (dissoc :manual-input :draft-uvs)))
                                         (when (= "control-count" id) (swap! state update :settings dissoc "control-points"))
                                         (.setDisable accept true)
                                         (.playFromStart timer))))]
                       (.setStyle row "-fx-border-color: #77787a; -fx-border-width: 0 0 1 0; -fx-background-color: #555658; -fx-padding: 1 3;")
                       (.setMinHeight row 25.0)
                       (.setAlignment row Pos/CENTER_LEFT)
                       (HBox/setHgrow slider Priority/ALWAYS)
                       (.setTooltip slider (Tooltip. (tr (str "trace-" id "-help"))))
                       (.setMajorTickUnit slider 1.0) (.setMinorTickCount slider 0) (.setSnapToTicks slider true) (.setBlockIncrement slider 1.0)
                       (when (#{"bone-count" "control-count"} id) (.setMin slider 2.0))
                       (when (= "alpha" id) (.setMin slider 1.0))
                       (.setText number (str (long value)))
                       (.addListener (.valueProperty slider) (reify ChangeListener (changed [_ _ _ value] (changed value))))
                       (.setOnAction number (ui/event-handler _
                                              (try (let [n (Double/parseDouble (.getText number))]
                                                     (when-not (Double/isFinite n) (throw (NumberFormatException.)))
                                                     (.setValue slider (max (.getMin slider) (min maximum (Math/round n))))
                                                     (.setText number (str (long (.getValue slider)))))
                                                   (catch NumberFormatException _ (.setText number (str (long (.getValue slider))))))))
                       (.addListener (.focusedProperty number)
                                     (reify ChangeListener
                                       (changed [_ _ _ focused]
                                         (when-not focused
                                           (.handle (.getOnAction number) (javafx.event.ActionEvent.))))))
                       row))
                   (if rig-mode [["bone-count" 32.0] ["control-count" 8.0] ["detail" 100.0] ["refinement" 100.0]]
                       [["detail" 100.0] ["concavity" 100.0] ["refinement" 100.0] ["uniform-percent" 100.0] ["alpha" 255.0] ["padding" 32.0]]))]
    (when (.isError image) (throw (ex-info (tr "trace-image-error") {})))
    (add-watch state ::loading
               (fn update-loading [_ _ before after]
                 (when (not= (select-keys before [:busy :pending :closed]) (select-keys after [:busy :pending :closed]))
                   (.setVisible loading (boolean (and (not (:closed after)) (or (:busy after) (:pending after))))))))
    (reset! request-ref request)
    (.setStyle panel "-fx-background-color: #3d3e40; -fx-border-color: #737477; -fx-border-radius: 3; -fx-background-radius: 3;")
    (HBox/setHgrow title Priority/ALWAYS)
    (.setMaxWidth title Double/MAX_VALUE)
    (.setStyle exit "-fx-background-color: transparent; -fx-font-size: 20; -fx-padding: 0 5;")
    (.add (.getChildren panel) (HBox. 4.0 (into-array Node [title exit])))
    (.addAll (.getChildren panel) ^java.util.Collection (subvec rows 0 4))
    (when-not rig-mode
      (let [last-row (HBox. 6.0 (into-array Node (subvec rows 4)))]
        (doseq [^HBox row (subvec rows 4)]
          (HBox/setHgrow row Priority/ALWAYS)
          (.setMinWidth row 0.0)
          (let [^Label title (.get (.getChildren row) 0) ^TextField number (.get (.getChildren row) 1)]
            (.setMinWidth title 55.0) (.setPrefWidth number 32.0) (.setMinWidth number 32.0) (.setMaxWidth number 32.0)))
        (.add (.getChildren panel) last-row)))
    (when rig-mode
      (.addAll (.getChildren panel) ^java.util.Collection [reverse-button (doto (Label. (tr "auto-rig-help")) (.setWrapText true))]))
    (doseq [^Button button [cancel accept]] (.setMinWidth button 70.0))
    (.addAll (.getChildren panel) ^java.util.Collection
             [status (FlowPane. 6.0 4.0 (into-array Node (if rig-mode [refresh triangles cancel accept] [refresh adjust triangles cancel accept])))])
    (.addAll (.getChildren overlay) ^java.util.Collection [canvas group])
    (.addAll (.getChildren group) ^java.util.Collection [path loading panel])
    (StackPane/setAlignment group Pos/BOTTOM_CENTER)
    (StackPane/setMargin group (Insets. 12.0))
    (.bind (.widthProperty canvas) (.widthProperty host))
    (.bind (.heightProperty canvas) (.heightProperty host))
    (doseq [property [(.widthProperty canvas) (.heightProperty canvas) (.heightProperty group)]]
      (.addListener property (reify ChangeListener (changed [_ _ _ _] (draw)))))
    (.setOnFinished timer (ui/event-handler _ (request)))
    (.setOnAction refresh (ui/event-handler _ (request)))
    (.setOnAction reverse-button (ui/event-handler _
                                   (swap! state #(-> % (assoc-in [:settings "reverse"] (.isSelected reverse-button))
                                                     (update :settings dissoc "control-points") (update :version inc) (assoc :pending true)))
                                   (.setDisable accept true) (.playFromStart timer)))
    (.setOnAction triangles (ui/event-handler _ (draw)))
    (.setOnAction cancel (ui/event-handler _ (close)))
    (.setOnAction exit (ui/event-handler _ (close)))
    (.setOnAction accept (ui/event-handler _
                           (when-let [result (when (and (not (:pending @state))
                                                        (= (:settings @state) (get-in @state [:result :proposal-settings])))
                                               (:result @state))]
                             (try (apply-result result (:settings @state)) (close)
                                  (catch Exception error (.setText status (.getMessage error)) (.setDisable accept true))))))
    (.setOnKeyPressed overlay
                      (ui/event-handler event
                        (cond
                          (= KeyCode/ESCAPE (.getCode event)) (do (close) (.consume event))
                          (and (.isShortcutDown event) (= KeyCode/Z (.getCode event)))
                          (let [undo (not (.isShiftDown event)) from (if undo :history :redo) to (if undo :redo :history)]
                            (when-let [previous (peek (get @state from))]
                              (swap! state #(-> % (update to conj (:result %)) (update from pop) (assoc :result previous :selected #{} :pending false)
                                                (update :version inc) (dissoc :manual-input :draft-uvs)))
                              (when rig-mode (swap! state assoc :settings (:proposal-settings previous)))
                              (.stop timer)
                              (.setText status (str (tr "vertices") ": " (get-in previous [:report "vertices"])))
                              (.setDisable accept false) (draw))
                            (.consume event)))))
    (.setOnScroll canvas (ui/event-handler event
                           (swap! state update :zoom #(max 0.1 (min 12.0 (* % (Math/exp (* 0.002 (.getDeltaY event)))))))
                           (draw) (.consume event)))
    (.setOnMouseMoved canvas (ui/event-handler event
                               (swap! state assoc :hover (nearest (.getX event) (.getY event))) (draw)))
    (.setOnMouseExited canvas (ui/event-handler _ (swap! state dissoc :hover) (draw)))
    (.setOnMousePressed canvas
                        (ui/event-handler event
                          (.requestFocus overlay)
                          (let [x (.getX event) y (.getY event)]
                            (cond
                              (contains? #{MouseButton/SECONDARY MouseButton/MIDDLE} (.getButton event))
                              (swap! state assoc :drag [x y (:pan @state)])
                              (and rig-mode (= MouseButton/PRIMARY (.getButton event)) (not (:busy @state)))
                              (when-let [index (nearest-control x y)]
                                (swap! state assoc :control-drag index :control-original (:result @state)))
                              (and (not rig-mode) (= MouseButton/PRIMARY (.getButton event)) (.isSelected adjust) (not (:busy @state)))
                              (if-let [index (nearest x y)]
                                (let [selected (:selected @state)
                                      selected (cond (.isShiftDown event) ((if (contains? selected index) disj conj) selected index)
                                                     (contains? selected index) selected
                                                     :else #{index})]
                                  (swap! state assoc :selected selected :vertex-drag [x y (:result @state)]))
                                (swap! state assoc :box [x y x y] :selected (if (.isShiftDown event) (:selected @state) #{})))))
                          (draw) (.consume event)))
    (.setOnMouseDragged canvas
                        (ui/event-handler event
                          (cond
                            (:drag @state)
                            (let [[x y [px py]] (:drag @state)] (swap! state assoc :pan [(+ px (- (.getX event) x)) (+ py (- (.getY event) y))]))
                            (some? (:control-drag @state))
                            (let [[x y w h] (:view-transform @state)
                                  point [(max 0.0 (min 1.0 (/ (- (.getX event) x) w))) (max 0.0 (min 1.0 (/ (- (.getY event) y) h)))]]
                              (swap! state assoc :draft-controls (assoc (:rig-controls (:result @state)) (:control-drag @state) point))
                              (.setDisable accept true))
                            (:vertex-drag @state)
                            (let [[x y result] (:vertex-drag @state) [_ _ w h] (:view-transform @state)
                                  dx (/ (- (.getX event) x) w) dy (/ (- (.getY event) y) h)
                                  uvs (get-in result [:preview :uvs])]
                              (swap! state assoc :draft-uvs
                                     (reduce (fn [uvs index]
                                               (-> uvs (update (* 2 index) #(max 0.0 (min 1.0 (+ % dx))))
                                                   (update (inc (* 2 index)) #(max 0.0 (min 1.0 (+ % dy)))))) uvs (:selected @state)))
                              (.setDisable accept true))
                            (:box @state)
                            (let [[x y] (:box @state)] (swap! state assoc :box [x y (.getX event) (.getY event)])))
                          (draw) (.consume event)))
    (.setOnMouseReleased canvas
                         (ui/event-handler event
                           (when-let [[x0 y0 x1 y1] (:box @state)]
                             (swap! state update :selected into
                                    (keep-indexed (fn [i [x y]] (when (and (<= (min x0 x1) x (max x0 x1)) (<= (min y0 y1) y (max y0 y1))) i)) (:screen-points @state))))
                           (when-let [controls (:draft-controls @state)]
                             (swap! state #(-> % (update :history conj (:control-original %))
                                               (assoc-in [:settings "control-points"] controls)
                                               (assoc :redo [] :pending true) (update :version inc)))
                             (.stop timer) (request))
                           (when-let [uvs (:draft-uvs @state)]
                             (let [original (nth (:vertex-drag @state) 2)]
                               (swap! state #(-> % (update :history conj original) (assoc :redo [] :pending true :manual-input [original uvs]) (update :version inc)))
                               (.stop timer) (request)))
                           (swap! state dissoc :drag :vertex-drag :box :control-drag :control-original :draft-controls)
                           (draw) (.consume event)))
    ;; One preview per viewport. Closing/reopening cannot leave an invisible
    ;; pane intercepting selection or allow stale results to reach a new pane.
    (doseq [^Node previous (vec (.getChildren host)) :when (= "skeletal-trace-preview" (.getId previous))]
      (if-let [close-previous (ui/user-data previous ::close)] (close-previous) (.remove (.getChildren host) previous)))
    (ui/user-data! overlay ::close close)
    (ui/user-data! overlay ::state state)
    (when style-sheet (.add (.getStylesheets overlay) (str style-sheet)))
    (.add (.getChildren host) overlay)
    (.applyCss overlay)
    (doseq [^HBox row rows] (paint-track! (.get (.getChildren row) 2)))
    (.requestFocus overlay)
    (draw)
    (request)
    {:node overlay :state state :close close}))
