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

(ns editor.gui-animation-view
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [dynamo.graph :as g]
            [editor.code.data :as code-data]
            [editor.code.resource :as code-resource]
            [editor.colors :as colors]
            [editor.defold-project :as project]
            [editor.gui-animation-data :as animation]
            [editor.gui-animation-preview :as preview]
            [editor.gui-animation-timeline :as timeline]
            [editor.localization :as localization]
            [editor.math :as math]
            [editor.resource :as resource]
            [editor.ui :as ui]
            [editor.workspace :as workspace]
            [util.coll :as coll])
  (:import [java.nio.file Files OpenOption StandardOpenOption]
           [javafx.geometry Insets Pos Side]
           [javafx.scene Cursor Node Parent]
           [javafx.scene.canvas Canvas]
           [javafx.scene.control Button CheckBox ComboBox Label MenuButton MenuItem ScrollBar ScrollPane Separator Slider Tab TabPane TabPane$TabClosingPolicy TextField ToggleButton Tooltip]
           [javafx.scene.input KeyCode KeyEvent MouseButton MouseEvent]
           [javafx.scene.layout BorderPane ColumnConstraints FlowPane GridPane HBox Pane Priority Region StackPane VBox]
           [javafx.scene.paint Color]
           [javafx.scene.shape SVGPath]
           [javafx.scene.text Font]
           [javafx.util StringConverter]
           [javafx.stage FileChooser FileChooser$ExtensionFilter]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(def ^:private properties
  ["position_x" "position_y" "position_z"
   "rotation_x" "rotation_y" "rotation_z"
   "scale_x" "scale_y" "scale_z" "color_a"])

(defn- paint
  ^Color [[r g b a]]
  (Color/color (double r) (double g) (double b) (double a)))

(defn- finite-number
  ^double [^TextField field]
  (let [value (try (Double/parseDouble (.getText field))
                   (catch NumberFormatException _ Double/NaN))]
    (when-not (Double/isFinite value)
      (throw (ex-info "Enter a finite number." {})))
    value))

(defn- node-values [node-id]
  (let [position (g/node-value node-id :position)
        rotation (math/clj-quat->euler (g/node-value node-id :rotation))
        scale (g/node-value node-id :scale)]
    (zipmap properties (conj (into [] cat [position rotation scale])
                             (g/node-value node-id :alpha)))))

(defn- preview-overrides [node-ids values]
  (into {}
        (keep (fn [[node-name channels]]
                (when-let [node-id (get node-ids node-name)]
                  (let [values (merge (node-values node-id) channels)]
                    [node-id (cond-> {}
                               (coll/any? #(contains? channels %) ["position_x" "position_y" "position_z"])
                               (assoc :position (mapv values ["position_x" "position_y" "position_z"]))

                               (coll/any? #(contains? channels %) ["rotation_x" "rotation_y" "rotation_z"])
                               (assoc :rotation (math/vecmath->clj
                                                  (math/euler->quat
                                                    (mapv values ["rotation_x" "rotation_y" "rotation_z"]))))

                               (coll/any? #(contains? channels %) ["scale_x" "scale_y" "scale_z"])
                               (assoc :scale (mapv values ["scale_x" "scale_y" "scale_z"]))

                               (contains? channels "color_a")
                               (assoc :alpha (get values "color_a")))]))))
        values))

(defn- time-x
  ^double [^Canvas canvas state time]
  (timeline/time->x time (:offset state) (:zoom state) (.getWidth canvas)))

(defn- x-time
  ^double [^Canvas canvas state x]
  (double (timeline/snap-time (:document state) (:animation-id state)
                              (timeline/x->time x (:offset state) (:zoom state) (.getWidth canvas)))))

(defn- replace-items! [^java.util.List items values]
  (.clear items)
  (.addAll items ^java.util.Collection values))

(defn- glyph [path]
  (doto (SVGPath.) (.setContent path) (ui/add-style! "animation-glyph")))

(defn- draw-timeline! [^Canvas canvas state]
  (let [{:keys [document animation-id time selected active-track zoom offset dragging-key frame-display rows ruler-y]} state
        fps (double (timeline/frames-per-second document))
        gc (.getGraphicsContext2D canvas)
        width (.getWidth canvas)
        height (max (double (get state :viewport-height 264.0)) (+ 32.0 (* 28.0 (count rows))))
        ruler-y (double (or ruler-y 0.0))]
    (.setHeight canvas height)
    (.setFill gc (Color/web "#181818"))
    (.fillRect gc 0.0 0.0 width height)
    (.setFont gc (Font/font 13.0))
    (doseq [[index {:keys [node-name property-id kind]}] (eduction (map-indexed vector) rows)]
      (let [top (+ 32.0 (* 28.0 (double index)))
            y (+ top 14.0)]
        (.setFill gc (Color/web (cond
                                  (= active-track [node-name property-id]) "#174779"
                                  (= kind :node) "#2b2b2b"
                                  (odd? (long index)) "#181818"
                                  :else "#181818")))
        (.fillRect gc 0.0 top width 28.0)
        (when property-id
          (doseq [keyframe (timeline/display-keyframes document animation-id node-name property-id)]
            (let [key-id [node-name property-id (:time keyframe)]
                  dragging (= dragging-key key-id)
                  x (time-x canvas state (if dragging time (:time keyframe)))
                  active (or dragging (= selected key-id))]
              (.setFill gc (Color/web (if active "#d4e8ff" "#83b7fb")))
              (.fillPolygon gc (double-array [x (+ x 5.0) x (- x 5.0)])
                            (double-array [(- y 5.0) y (+ y 5.0) y]) 4))))))
    (.setFill gc (Color/web "#181818"))
    (.fillRect gc 0.0 ruler-y width 32.0)
    (let [visible-frames (* (timeline/visible-span zoom) fps)
          tick-step (double (timeline/ruler-step visible-frames width))
          start-frame (* tick-step (Math/ceil (/ (* (double offset) fps) tick-step)))]
      (doseq [tick (range (inc (long (Math/ceil (/ visible-frames tick-step)))))]
        (let [frame (+ start-frame (* (double tick) tick-step))
              t (/ frame fps)
              x (time-x canvas state t)]
          (.setStroke gc (Color/web "#343434" 0.55))
          (.setLineWidth gc 1.0)
          (.strokeLine gc x (+ ruler-y 22.0) x height)
          (.setFill gc (Color/web "#c8c8c8"))
          (.fillText gc (if frame-display (format "%.0f" frame) (format "%.2f" t)) (- x 5.0) (+ ruler-y 15.0)))))
    (let [x (time-x canvas state time)
          label (if frame-display (str (Math/round (* (double time) fps))) (format "%.2f" (double time)))
          badge-width (max 26.0 (+ 12.0 (* 7.0 (count label))))]
      (.setStroke gc (Color/web "#579b8b"))
      (.setLineWidth gc 1.0)
      (.strokeLine gc x (+ ruler-y 28.0) x height)
      (.setFill gc (Color/web "#356a61"))
      (.fillRoundRect gc (- x (/ badge-width 2.0)) (+ ruler-y 20.0) badge-width 18.0 4.0 4.0)
      (.setFill gc (Color/web "#fff0de"))
      (.fillText gc label (- x (* 3.5 (count label))) (+ ruler-y 33.0)))))

(defn- draw-curve! [^Canvas canvas state easing detail]
  (let [gc (.getGraphicsContext2D canvas)
        width (.getWidth canvas)
        height (.getHeight canvas)
        x0 16.0 y0 (- height 16.0)
        span-x (- width 32.0) span-y (- height 32.0)
        {:keys [document animation-id active-track]} state
        [node-name property-id] active-track
        duration (double (animation/animation-duration document animation-id))
        samples (mapv (fn [index]
                        (let [t (/ (double index) 64.0)]
                          (if (and detail node-name property-id)
                            (double (get-in (animation/evaluate document animation-id (* duration t)) [node-name property-id] 0.0))
                            (animation/ease easing t))))
                      (range 65))
        low (if detail (reduce min samples) 0.0)
        high (if detail (reduce max samples) 1.0)
        span (max 0.001 (- (double high) (double low)))]
    (.setFill gc (Color/web "#181818"))
    (.fillRoundRect gc 0.0 0.0 width height 4.0 4.0)
    (.setLineWidth gc 1.0)
    (.setStroke gc (Color/web "#303030"))
    (doseq [index (range 6)]
      (let [fraction (/ (double index) 5.0)]
        (.strokeLine gc (+ x0 (* span-x fraction)) 16.0 (+ x0 (* span-x fraction)) y0)
        (.strokeLine gc x0 (- y0 (* span-y fraction)) (+ x0 span-x) (- y0 (* span-y fraction)))))
    (.setStroke gc (Color/web "#69adfa"))
    (.setLineWidth gc 2.0)
    (.beginPath gc)
    (doseq [[index value] (eduction (map-indexed vector) samples)]
      (let [x (+ x0 (* span-x (/ (double index) 64.0)))
            y (- y0 (* span-y (/ (- (double value) (double low)) span)))]
        (if (zero? (long index)) (.moveTo gc x y) (.lineTo gc x y))))
    (.stroke gc)
    (.setFill gc (Color/web "#d3e6ff"))
    (.fillOval gc (- x0 3.5) (- y0 3.5) 7.0 7.0)
    (.fillOval gc (- (+ x0 span-x) 3.5) (- (- y0 span-y) 3.5) 7.0 7.0)))

(defn- timeline-key [^Canvas canvas state x y]
  (let [{:keys [document animation-id rows]} state
        row (int (Math/floor (/ (- (double y) 32.0) 28.0)))
        {:keys [node-name property-id]} (get rows row)]
    (when (and property-id (>= (double y) (+ (double (get state :ruler-y 0.0)) 32.0)))
      (when-let [keyframe (coll/first-where
                            #(< (Math/abs (- (double x) (time-x canvas state (:time %)))) 9.0)
                            (timeline/display-keyframes document animation-id node-name property-id))]
        [node-name property-id keyframe]))))

(defn- animation-resource-node [project path]
  (when-let [node-id (project/get-resource-node project path)]
    (code-resource/ensure-loaded! node-id (g/make-evaluation-context))
    node-id))

(defn- read-document [node-id]
  (animation/read-json (code-data/lines->string (g/node-value node-id :save-value))))

(defn- edit-document! [project path edit-fn]
  (when-let [node-id (animation-resource-node project path)]
    (let [document (edit-fn (read-document node-id))]
      (g/transact
        (into [] cat
              [(g/operation-label "Edit GUI animation")
               (g/set-property node-id :modified-lines
                 (code-data/string->lines (animation/write-json document)))])))))

(defn attach!
  "Adds a Panthera timeline to a GUI scene. Only the JSON resource is edited;
  playback state and scene overrides belong to the view and are not saved."
  [^Parent parent view-id resource-node {:keys [project workspace localization] :as opts}]
  (let [gui-resource (g/node-value resource-node :resource)
        gui-path (resource/proj-path gui-resource)
        path (str (subs gui-path 0 (- (count gui-path) 4)) "_panthera.json")
        state (atom {:document (animation/new-document gui-path)
                     :animation-id "default" :time 0.0 :playing false :preview false
                     :recording false :draft {} :collapsed #{} :zoom 1.0 :offset 0.0
                     :muted #{} :frame-display true :direction 1 :rows []
                     :selected nil :last-lines nil :last-selection nil :exists false})
        updating (atom false)
        tool-controller-id (g/node-value view-id :tool-controller-id)
        manipulation-handler (atom nil)
        scene-content (first (.getChildrenUnmodifiable parent))
        root (BorderPane.)
        content (doto (VBox. 0.0) (.setMinWidth 0.0) (ui/add-style! "gui-animation-workspace") (.setPrefHeight 468.0))
        workspace-tab (Tab.)
        workspace-tabs (doto (TabPane.) (.setId "gui-animation-tabs") (.setMinWidth 0.0)
                             (.setTabClosingPolicy TabPane$TabClosingPolicy/UNAVAILABLE))
        title (doto (StackPane.) (.setId "gui-animation-panel") (.setMinWidth 0.0))
        status (doto (Label.) (.setId "gui-animation-status") (.setWrapText true))
        canvas (doto (Canvas. 700.0 100.0) (.setId "gui-animation-timeline") (.setFocusTraversable true)
                     (.setAccessibleText "Animation timeline. Select a key to edit its time and value."))
        canvas-pane (doto (Pane.) (.setMinWidth 0.0) (.setPrefWidth 0.0) (ui/children! [canvas]))
        track-pane (doto (VBox.) (.setMinWidth 280.0) (.setMaxWidth 460.0))
        track-fields (atom {})
        track-content (doto (HBox.) (.setMinWidth 0.0) (ui/children! [track-pane canvas-pane]))
        scroll (doto (ScrollPane. track-content) (.setMinWidth 0.0) (.setPrefWidth 0.0) (.setFitToWidth true) (.setPrefViewportHeight 232.0) (.setMinHeight 160.0) (ui/add-style! "animation-tracks-scroll"))
        animation-choice (doto (ComboBox.) (.setId "gui-animation-choice") (.setPrefWidth 140.0))
        node-choice (doto (ComboBox.) (.setId "gui-animation-node") (.setPrefWidth 150.0))
        property-choice (doto (ComboBox.) (.setId "gui-animation-property") (.setPrefWidth 130.0)
                              (ui/items! properties) (.setValue "position_x"))
        easing-choice (doto (ComboBox.) (.setId "gui-animation-easing") (.setPrefWidth 120.0)
                            (ui/items! animation/supported-easings) (.setValue "linear"))
        time-field (doto (TextField. "0.000") (.setId "gui-animation-time") (.setPrefWidth 70.0))
        frame-field (doto (TextField. "0") (.setId "gui-animation-frame") (.setPrefWidth 65.0))
        value-field (doto (TextField. "0") (.setId "gui-animation-value") (.setPrefWidth 85.0))
        duration-field (doto (TextField. "1.0") (.setId "gui-animation-duration") (.setPrefWidth 65.0))
        name-field (doto (TextField.) (.setId "gui-animation-name") (.setPrefWidth 110.0))
        preview-check (doto (CheckBox.) (.setId "gui-animation-preview"))
        record-button (doto (ToggleButton.) (.setId "gui-animation-record"))
        loop-check (doto (ToggleButton.) (.setId "gui-animation-loop"))
        mode-label (doto (Label.) (.setId "gui-animation-mode") (.setWrapText true))
        fps-choice (doto (ComboBox.) (.setId "gui-animation-fps") (ui/items! [24 30 60 120]) (.setValue (int 60)))
        frame-display (doto (ToggleButton.) (.setId "gui-animation-frame-display") (.setSelected true))
        playback-mode (doto (ComboBox.) (.setId "gui-animation-playback-mode") (ui/items! [:normal :reverse :ping-pong]) (.setValue :normal))
        curve (doto (Canvas. 200.0 80.0) (.setId "gui-animation-easing-curve"))
        detail-curve (doto (Canvas. 700.0 112.0) (.setId "gui-animation-detail-curve"))
        curve-pane (doto (Pane.) (.setMinWidth 0.0) (ui/children! [detail-curve]))
        event-list (doto (VBox. 6.0) (.setPadding (Insets. 12.0)))
        zoom-choice (doto (ComboBox.) (.setId "gui-animation-zoom-choice") (ui/items! [1.0 5.0 10.0 12.5 25.0 50.0 100.0 150.0 200.0 400.0 800.0]) (.setValue 100.0))
        zoom-slider (doto (Slider. 0.001 8.0 1.0) (.setId "gui-animation-zoom") (.setPrefWidth 100.0))
        time-scroll (doto (ScrollBar.) (.setId "gui-animation-scroll") (.setMin 0.0))
        play-button (doto (Button.) (.setId "gui-animation-play") (ui/add-style! "button-small"))
        drag (atom nil)
        pan (atom nil)
        localize! (fn [control suffix]
                    (localization/localize! control localization
                                            (localization/message (str "gui-animation." suffix))))
        guarded (fn [f]
                  (fn [_]
                    (try (f)
                         (catch Exception error
                           (.setText status (.getMessage error))))))
        button (fn ^Button [suffix id f]
                 (doto (Button.) (.setId id) (ui/add-style! "button-small") (localize! suffix)
                       (.setOnAction (ui/event-handler _ ((guarded f) nil)))))
        stop-preview! (fn []
                        (swap! state assoc :playing false :preview false :recording false :draft {})
                        (.setSelected preview-check false)
                        (.setSelected record-button false)
                        (g/transact {:undoable false}
                          (into [] cat
                                [(g/set-property view-id :animation-preview-overrides {})
                                 (g/set-property view-id :animation-preview-active false)
                                 (g/set-property view-id :animation-manipulation-enabled false)
                                 (g/set-property tool-controller-id :animation-manipulation nil)])))
        update-preview! (fn []
                          (let [{:keys [document animation-id time preview draft]} @state
                                node-ids (g/node-value resource-node :node-ids)
                                issues (when preview
                                         (into (animation/preview-issues document animation-id)
                                               (comp (map first) (distinct)
                                                     (remove #(contains? node-ids %))
                                                     (map #(str "GUI node is missing: " %)))
                                               (timeline/tracks document animation-id)))
                                active (and preview (zero? (count issues)))
                                overrides (if active
                                            (preview-overrides node-ids
                                                               (reduce (fn [values [node-name property-id]] (update values node-name dissoc property-id))
                                                                       (merge-with merge (animation/evaluate document animation-id time) draft)
                                                                       (:muted @state)))
                                            {})]
                            (when (not= overrides (g/node-value view-id :animation-preview-overrides))
                              (g/transact {:undoable false}
                                (g/set-property view-id :animation-preview-overrides overrides)))
                            (when (not= active (g/node-value view-id :animation-preview-active))
                              (g/transact {:undoable false}
                                (g/set-property view-id :animation-preview-active active)))
                            (let [handler (when active @manipulation-handler)]
                              (when (not= handler (g/node-value tool-controller-id :animation-manipulation))
                                (g/transact {:undoable false}
                                  (concat (g/set-property view-id :animation-manipulation-enabled (boolean handler))
                                          (g/set-property tool-controller-id :animation-manipulation handler)))))
                            (when (pos? (count issues))
                              (swap! state assoc :playing false)
                              (.setText status (coll/join-to-string " · " issues)))))
        set-time! (fn [time]
                    (let [{:keys [document animation-id offset zoom]} @state
                          duration (double (animation/animation-duration document animation-id))
                          time (max 0.0 (min duration (double time)))
                          span (timeline/visible-span zoom)
                          offset (if (or (< time (double offset)) (> time (+ (double offset) span)))
                                   (max 0.0 (min (- duration span) time))
                                   offset)]
                      (swap! state assoc :time time :offset offset :preview true :draft {} :selected nil)
                      (.setSelected preview-check true)
                      (update-preview!)
                      (draw-timeline! canvas @state)))
        sync-fields! (fn []
                       (let [{:keys [document animation-id time draft]} @state
                             node-name (.getValue node-choice)
                             property-id (.getValue property-choice)
                             node-id (get (g/node-value resource-node :node-ids) node-name)
                             context [document animation-id time node-name property-id draft]]
                         (when (not= context (:field-context @state))
                           (swap! state assoc :field-context context)
                           (when-not (.isFocused time-field) (.setText time-field (format "%.3f" (double time))))
                           (when-not (.isFocused frame-field)
                             (.setText frame-field (str (Math/round (* (double time) (double (timeline/frames-per-second document)))))))
                           (when (and node-id (not (.isFocused value-field)))
                             (let [value (get-in (merge-with merge (animation/evaluate document animation-id time) draft) [node-name property-id]
                                                 (get (node-values node-id) property-id))]
                               (.setText value-field (format "%.4f" (double value))))))))
        refresh-document! (fn []
                            (if-let [node-id (animation-resource-node project path)]
                              (let [lines (g/node-value node-id :save-value)]
                                (when (not= lines (:last-lines @state))
                                  (let [document (animation/read-json (code-data/lines->string lines))
                                        ids (animation/animation-ids document)
                                        _ (when (zero? (count ids))
                                            (throw (ex-info "The imported file contains no animations." {})))
                                        animation-id (if (contains? (set ids) (:animation-id @state))
                                                       (:animation-id @state) (first ids))]
                                    (swap! state assoc :document document :animation-id animation-id :draft {} :selected nil
                                           :time (min (double (:time @state)) (double (animation/animation-duration document animation-id)))
                                           :exists true :last-lines lines)
                                    (reset! updating true)
                                    (try (ui/items! animation-choice ids)
                                         (.setValue animation-choice animation-id)
                                         (.setText duration-field (str (animation/animation-duration document animation-id)))
                                         (.setText name-field animation-id)
                                         (.setValue fps-choice (int (timeline/frames-per-second document)))
                                         (finally (reset! updating false)))
                                    (.setText status path))))
                              (when (:exists @state)
                                (stop-preview!)
                                (swap! state assoc :exists false :last-lines nil)
                                (.setText status "Animation file is missing. Create or import one to continue."))))
        commit! (fn [edit-fn]
                  (when-not (:exists @state) (throw (ex-info "Create an animation file first." {})))
                  (edit-document! project path edit-fn)
                  (refresh-document!)
                  (update-preview!)
                  (draw-timeline! canvas @state))
        select-track! (fn [node-name property-id]
                        (swap! state assoc :active-track [node-name property-id])
                        (.setValue node-choice node-name)
                        (.setValue property-choice property-id)
                        (when-let [keyframe (coll/first-where
                                              #(< (Math/abs (- (double (:time %)) (double (:time @state)))) 1.0e-9)
                                              (timeline/display-keyframes (:document @state) (:animation-id @state) node-name property-id))]
                          (.setValue easing-choice (:easing keyframe)))
                        (sync-fields!))
        record-value! (fn [node-name property-id value]
                        (let [node-id (get (g/node-value resource-node :node-ids) node-name)
                              {:keys [animation-id time]} @state]
                          (when-not node-id (throw (ex-info "Select a GUI node." {})))
                          (commit! #(timeline/record-value % animation-id node-name property-id time value
                                                           (get (node-values node-id) property-id) (.getValue easing-choice)))
                          (set-time! time)
                          (swap! state assoc :selected [node-name property-id time])))
        edit-value! (fn [node-name property-id value]
                      (select-track! node-name property-id)
                      (swap! state assoc :playing false :preview true)
                      (.setSelected preview-check true)
                      (if (:recording @state)
                        (record-value! node-name property-id value)
                        (do (swap! state assoc-in [:draft node-name property-id] value)
                            (update-preview!)))
                      (sync-fields!))
        animation-manipulation
        {:make-manipulation-fn
         (fn [property node-id]
           (swap! state assoc :playing false)
           (let [initial (get-in (g/node-value view-id :animation-preview-overrides) [node-id property]
                                 (g/node-value node-id property))]
             #(preview/transform-value property initial %)))
         :apply!
         (fn [phase overrides]
           (when (:preview @state)
             (let [id->name (into {} (map (fn [[name id]] [id name])) (g/node-value resource-node :node-ids))
                   {:keys [document animation-id time recording]} @state
                   values (into {}
                                (keep (fn [[id properties]]
                                        (when-let [name (get id->name id)]
                                          [name (reduce-kv (fn [channels property value]
                                                             (merge channels (preview/transform-channels property value)))
                                                           {} properties)])))
                                overrides)
                   changes (into []
                                 (mapcat (fn [[name channels]]
                                           (let [initial (merge (node-values (get (g/node-value resource-node :node-ids) name))
                                                                (get (animation/evaluate document animation-id time) name))]
                                             (into [] (keep (fn [[property value]]
                                                              (when (> (Math/abs (- (double value) (double (get initial property)))) 1.0e-7)
                                                                [name property value]))) channels))))
                                 values)]
               (swap! state update :draft #(merge-with merge % values))
               (swap! state update :muted #(reduce (fn [muted [name property _]] (disj muted [name property])) % changes))
               (when (and (= :commit phase) recording (pos? (count changes)))
                 (commit! (fn [document]
                            (reduce (fn [document [name property value]]
                                      (timeline/record-value document animation-id name property time value
                                                             (get (node-values (get (g/node-value resource-node :node-ids) name)) property)
                                                             (.getValue easing-choice)))
                                    document changes)))
                 (swap! state assoc :draft {}))
               (update-preview!)
               (sync-fields!))))}
        navigate! (fn [direction]
                    (swap! state assoc :playing false)
                    (set-time! (timeline/navigate (:document @state) (:animation-id @state) (:time @state) direction)))
        delete-key! (fn []
                      (when-let [[node-name property-id time] (:selected @state)]
                        (commit! #(animation/remove-keyframe % (:animation-id @state) node-name property-id time))
                        (swap! state assoc :selected nil)))
        create! (fn []
                  (let [file (io/file (workspace/project-directory workspace) (subs path 1))]
                    (when-not (.exists file)
                      (Files/writeString (.toPath file) (animation/write-json (animation/new-document gui-path))
                                         (into-array OpenOption [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE])))
                    (workspace/resource-sync! workspace)
                    (refresh-document!)))
        save! #(ui/execute-command (ui/contexts (.getScene root) true) :file.save-all nil)
        add-property! (fn []
                        (let [node-name (.getValue node-choice)
                              property-id (.getValue property-choice)
                              node-id (get (g/node-value resource-node :node-ids) node-name)
                              animation-id (:animation-id @state)]
                          (when-not node-id (throw (ex-info "Select a GUI node." {})))
                          (when (zero? (count (animation/keyframes (:document @state) animation-id node-name property-id)))
                            (commit! #(animation/set-keyframe % animation-id node-name property-id 0.0
                                                              (get (node-values node-id) property-id) "linear")))
                          (swap! state update :collapsed disj node-name)
                          (select-track! node-name property-id)))
        filesbar (doto (GridPane.) (.setHgap 8.0) (.setVgap 6.0) (ui/add-style! "animation-file-actions")
                       (ui/children! [(button "create" "gui-animation-create" create!)
                                      (button "import" "gui-animation-import"
                                              #(let [chooser (doto (FileChooser.) (.setTitle "Import Panthera JSON"))]
                                                 (.add (.getExtensionFilters chooser)
                                                       (FileChooser$ExtensionFilter. "Panthera JSON" (java.util.ArrayList. ["*.json"])))
                                                 (when-let [file (.showOpenDialog chooser (.getWindow (.getScene root)))]
                                                   (let [document (animation/read-json (slurp file :encoding "UTF-8"))]
                                                     (create!) (commit! (constantly document))))))
                                      (button "export-json" "gui-animation-export-json"
                                              #(let [chooser (doto (FileChooser.) (.setTitle "Export Panthera JSON")
                                                                   (.setInitialFileName (.getName (io/file path))))]
                                                 (.add (.getExtensionFilters chooser)
                                                       (FileChooser$ExtensionFilter. "Panthera JSON" (java.util.ArrayList. ["*.json"])))
                                                 (when-let [file (.showSaveDialog chooser (.getWindow (.getScene root)))]
                                                   (spit file (animation/write-json (:document @state)) :encoding "UTF-8")
                                                   (workspace/resource-sync! workspace))))
                                      (button "export" "gui-animation-export"
                                              #(let [chooser (doto (FileChooser.) (.setTitle "Export Panthera Lua")
                                                                   (.setInitialFileName (str (subs (.getName (io/file path)) 0 (- (count (.getName (io/file path))) 5)) ".lua")))]
                                                 (.add (.getExtensionFilters chooser)
                                                       (FileChooser$ExtensionFilter. "Lua animation" (java.util.ArrayList. ["*.lua"])))
                                                 (when-let [file (.showSaveDialog chooser (.getWindow (.getScene root)))]
                                                   (spit file (animation/write-lua (:document @state)) :encoding "UTF-8")
                                                   (workspace/resource-sync! workspace))))
                                      (button "save" "gui-animation-save" save!)]))
        grid (fn [pairs]
               (let [pane (doto (GridPane.) (.setHgap 8.0) (.setVgap 5.0))]
                 (doseq [[index [suffix ^Node field]] (eduction (map-indexed vector) pairs)]
                   (.add pane (doto (Label.) (localize! suffix)) 0 (int index))
                   (.add pane field 1 (int index))
                   (GridPane/setHgrow field Priority/ALWAYS)
                   (when (instance? Region field) (.setMaxWidth ^Region field Double/MAX_VALUE)))
                 pane))
        bottom-loop (doto (CheckBox.) (localize! "loop-playback"))
        ^GridPane settingsbar (grid [["name" name-field] ["duration" duration-field]
                                     ["playback-mode" (doto (HBox. 12.0) (.setAlignment Pos/CENTER_LEFT)
                                                            (ui/children! [playback-mode bottom-loop]))]])
        key-actions (doto (HBox. 8.0) (.setAlignment Pos/CENTER_RIGHT)
                          (ui/children! [(doto ^Button (button "key" "gui-animation-add-key"
                                                               #(record-value! (.getValue node-choice) (.getValue property-choice) (finite-number value-field)))
                                           (ui/add-style! "animation-primary"))
                                         (button "delete" "gui-animation-delete-key" delete-key!)]))
        editbar (doto (VBox. 8.0)
                  (ui/children! [(grid [["current-property" (doto (HBox. 8.0) (ui/children! [property-choice]))]
                                        ["value" value-field]]) key-actions]))
        easing-panel (doto (VBox. 8.0) (ui/add-style! "animation-easing-panel")
                           (ui/children! [(doto (HBox. 10.0) (.setAlignment Pos/CENTER_LEFT)
                                                (ui/children! [(doto (Label.) (localize! "interpolation")) easing-choice])) curve]))
        data-panel (doto (VBox. 10.0) (ui/add-style! "animation-data-panel")
                         (ui/children! [(doto (Label.) (localize! "data")) filesbar]))
        settings-content (doto (GridPane.) (.setHgap 16.0) (.setMinWidth 1280.0) (.setPadding (Insets. 8.0 12.0 8.0 12.0)))
        settings-scroll (doto (ScrollPane. settings-content) (.setFitToHeight true) (.setFitToWidth true) (.setMinWidth 0.0) (.setPrefWidth 0.0)
                              (ui/add-style! "animation-settings-scroll"))
        settings (doto (TabPane.) (.setId "gui-animation-settings") (.setSide Side/TOP)
                       (.setTabClosingPolicy TabPane$TabClosingPolicy/UNAVAILABLE)
                       (.setMinHeight 160.0) (.setPrefHeight 160.0) (.setMaxHeight 160.0))
        path-label (doto (Label. path) (.setAlignment Pos/CENTER_RIGHT) (.setMinWidth 0.0) (.setId "gui-animation-path") (.setMaxWidth Double/MAX_VALUE))
        more-menu (doto (MenuButton. "…") (.setId "gui-animation-more") (ui/add-style! "button-icon"))
        header (doto (HBox. 8.0) (.setMinWidth 0.0) (.setMaxHeight 26.0) (.setAlignment Pos/CENTER_LEFT) (ui/add-style! "animation-header")
                     (ui/children! [animation-choice path-label
                                    (button "save-file" "gui-animation-save-file" save!) more-menu]))
        welcome (doto (FlowPane. 8.0 6.0) (ui/add-style! "animation-welcome")
                      (ui/children! [(doto (Label.) (localize! "empty"))
                                     (button "create" "gui-animation-start" create!)]))
        icon-button (fn ^Button [suffix id text f]
                      (doto (Button. text) (.setId id) (ui/add-style! "animation-icon-button")
                            (.setTooltip (doto (Tooltip.) (localize! suffix)))
                            (.setOnAction (ui/event-handler _ ((guarded f) nil)))))
        transport-button (fn [suffix id path f]
                           (doto ^Button (icon-button suffix id "" f) (.setGraphic (glyph path)) (ui/add-style! "animation-transport")))
        zoom-out (icon-button "zoom-out" "gui-animation-zoom-out" "−" #(.setValue zoom-slider (max 0.001 (/ (.getValue zoom-slider) 1.5))))
        zoom-in (icon-button "zoom-in" "gui-animation-zoom-in" "+" #(.setValue zoom-slider (min 8.0 (* (.getValue zoom-slider) 1.5))))
        fit (button "fit" "gui-animation-fit"
                    #(.setValue zoom-slider (max 0.001 (min 8.0 (/ 1.0 (max 0.001 (double (animation/animation-duration (:document @state) (:animation-id @state)))))))))
        fullscreen (doto (ToggleButton.) (.setId "gui-animation-fullscreen")
                         (.setGraphic (glyph "M0 6 L0 0 L6 0 M12 0 L18 0 L18 6 M18 12 L18 18 L12 18 M6 18 L0 18 L0 12")))
        toolbar-spacer (Region.)
        toolbar (doto (HBox. 8.0) (.setMinWidth 1040.0) (.setAlignment Pos/CENTER_LEFT) (ui/add-style! "animation-toolbar")
                      (ui/children! [(transport-button "first" "gui-animation-first" "M18 0 L7 8 L18 16 Z M7 0 L0 8 L7 16 Z" #(navigate! :first))
                                     play-button
                                     (transport-button "next" "gui-animation-next" "M0 0 L11 8 L0 16 Z M13 0 L15 0 L15 16 L13 16 Z" #(navigate! :next-key))
                                     (transport-button "last" "gui-animation-last" "M0 0 L11 8 L0 16 Z M11 0 L20 8 L11 16 Z" #(navigate! :last))
                                     frame-field (Label. "s") time-field fps-choice (Separator.)
                                     preview-check record-button (Separator.) loop-check frame-display toolbar-spacer
                                     zoom-out zoom-in zoom-slider fit zoom-choice fullscreen]))
        toolbar-scroll (doto (ScrollPane. toolbar) (.setFitToHeight true) (.setFitToWidth true) (.setMinWidth 0.0) (.setPrefWidth 0.0)
                             (ui/add-style! "animation-toolbar-scroll"))
        track-header (doto (HBox. 6.0) (.setAlignment Pos/CENTER_LEFT) (ui/add-style! "animation-track-header"))
        track-rows (atom {})
        refresh-tracks! (fn []
                          (let [{:keys [document animation-id collapsed time draft active-track muted]} @state
                                child-counts (frequencies (eduction (map :parent) (:nodes (g/node-value resource-node :save-value))))
                                rows (timeline/editor-rows (timeline/tracks document animation-id) collapsed (.getValue node-choice) child-counts)
                                context [animation-id rows]
                                values (merge-with merge (animation/evaluate document animation-id time) draft)]
                            (let [viewport-height (.getHeight (.getViewportBounds scroll))
                                  ruler-y (* (.getVvalue scroll) (max 0.0 (- (.getHeight canvas) viewport-height)))]
                              (swap! state assoc :rows rows :viewport-height viewport-height :ruler-y ruler-y)
                              (.setTranslateY track-header ruler-y))
                            (when (not= context (:rows-context @state))
                              (swap! state assoc :rows-context context)
                              (reset! track-fields {})
                              (reset! track-rows {})
                              (ui/children! track-pane
                                            (into [track-header]
                                                  (map-indexed
                                                    (fn [index {:keys [node-name property-id kind child-count]}]
                                                      (case kind
                                                        :node (doto (Button. (str (if (contains? collapsed node-name) "▸  " "▾  ") "◇  " node-name))
                                                                (.setMnemonicParsing false) (ui/add-style! "animation-node-row")
                                                                (.setMaxWidth Double/MAX_VALUE) (.setAlignment Pos/CENTER_LEFT)
                                                                (.setOnAction (ui/event-handler _
                                                                                (swap! state update :collapsed
                                                                                       #(if (contains? % node-name) (disj % node-name) (conj % node-name))))))
                                                        :children (doto (Label. (str "▸  Child Objects (" child-count ")"))
                                                                    (ui/add-style! "animation-children-row") (.setMaxWidth Double/MAX_VALUE))
                                                        (let [field (doto (TextField.) (.setPrefWidth 92.0) (.setMinWidth 72.0)
                                                                          (.setId (str "gui-animation-track-value-" index))
                                                                          (.setAccessibleText (str node-name " " property-id)))
                                                              original-text (atom nil)
                                                              apply-value! (guarded #(when (not= @original-text (.getText field))
                                                                                       (edit-value! node-name property-id (finite-number field))
                                                                                       (reset! original-text (.getText field))))
                                                              enabled (doto (CheckBox.) (.setSelected (and (contains? (set (timeline/tracks document animation-id)) [node-name property-id]) (not (contains? muted [node-name property-id]))))
                                                                            (.setAccessibleText (str "Preview " node-name " " property-id)))
                                                              key-button (doto (Button. "◇") (ui/add-style! "animation-key-button")
                                                                               (.setAccessibleText (str "Set key " node-name " " property-id))
                                                                               (.setOnAction (ui/event-handler _
                                                                                               (select-track! node-name property-id)
                                                                                               ((guarded #(record-value! node-name property-id (finite-number field))) nil))))
                                                              property-label (doto (Button. (if (= "color_a" property-id) "opacity" property-id))
                                                                               (ui/add-style! "animation-property-label") (.setMnemonicParsing false)
                                                                               (.setMaxWidth Double/MAX_VALUE) (.setAlignment Pos/CENTER_LEFT)
                                                                               (.setOnAction (ui/event-handler _ (select-track! node-name property-id))))
                                                              row (doto (HBox. 8.0) (.setAlignment Pos/CENTER_LEFT) (ui/add-style! "animation-property-row")
                                                                        (ui/children! [enabled property-label field key-button]))]
                                                          (swap! track-fields assoc [node-name property-id] field)
                                                          (swap! track-rows assoc [node-name property-id] row)
                                                          (HBox/setHgrow property-label Priority/ALWAYS)
                                                          (.setOnAction enabled (ui/event-handler _
                                                                                  (swap! state update :muted
                                                                                         #(if (.isSelected enabled) (disj % [node-name property-id]) (conj % [node-name property-id])))
                                                                                  (update-preview!)))
                                                          (.setOnAction field (ui/event-handler _ (apply-value! nil)))
                                                          (ui/observe (.focusedProperty field)
                                                                      (fn [_ _ focused]
                                                                        (if focused
                                                                          (do (reset! original-text (.getText field)) (select-track! node-name property-id))
                                                                          (apply-value! nil))))
                                                          row))))
                                                  rows)))
                            (doseq [[[node-name property-id] ^TextField field] @track-fields]
                              (let [track [node-name property-id]
                                    node-id (get (g/node-value resource-node :node-ids) node-name)
                                    value (get-in values track (when node-id (get (node-values node-id) property-id)))
                                    ^HBox row (get @track-rows track)
                                    ^Button key-button (last (.getChildren row))]
                                (.setDisable field (:playing @state))
                                (ui/set-style! row "active" (= active-track track))
                                (.setText key-button (if (coll/any? #(< (Math/abs (- (double time) (double (:time %)))) 1.0e-9)
                                                                    (timeline/display-keyframes document animation-id node-name property-id)) "◆" "◇"))
                                (when (and (number? value) (not (.isFocused field)))
                                  (.setText field (format "%.4f" (double value))))))))]
    (localize! workspace-tab "title")
    (.setGraphic workspace-tab (glyph "M10 0 L20 5 L20 16 L10 21 L0 16 L0 5 Z M0 5 L10 10 L20 5 M10 10 L10 21"))
    (.setContent workspace-tab content)
    (.add (.getTabs workspace-tabs) workspace-tab)
    (ui/children! title [workspace-tabs header])
    (StackPane/setAlignment header Pos/TOP_RIGHT)
    (StackPane/setMargin header (Insets. 0.0 4.0 0.0 200.0))
    (HBox/setHgrow path-label Priority/ALWAYS)
    (HBox/setHgrow toolbar-spacer Priority/ALWAYS)
    (HBox/setHgrow settingsbar Priority/ALWAYS)
    (HBox/setHgrow editbar Priority/ALWAYS)
    (.setMinWidth settingsbar 285.0)
    (.setMinWidth editbar 340.0)
    (doseq [[index ^Node button] (map-indexed vector (.getChildren filesbar))]
      (GridPane/setColumnIndex button (int (mod (long index) 3)))
      (GridPane/setRowIndex button (int (quot (long index) 3))))
    (.setPrefWidth data-panel 320.0)
    (.setMinWidth data-panel 280.0)
    (.bindBidirectional (.selectedProperty bottom-loop) (.selectedProperty loop-check))
    (doseq [[index ^Node panel percent] [[0 settingsbar 25.0] [1 editbar 32.0] [2 easing-panel 20.0] [3 data-panel 23.0]]]
      (.add settings-content panel (int index) 0)
      (.add (.getColumnConstraints settings-content) (doto (ColumnConstraints.) (.setPercentWidth (double percent)))))
    (ui/add-style! editbar "animation-edit-panel")
    (replace-items! (.getItems more-menu) [(doto (MenuItem.) (localize! "add-clip")
                                                 (.setOnAction (ui/event-handler _
                                                                 ((guarded #(let [ids (set (animation/animation-ids (:document @state)))
                                                                                  name (loop [index 1]
                                                                                         (let [name (str "animation_" index)]
                                                                                           (if (contains? ids name) (recur (inc index)) name)))]
                                                                              (commit! (fn [document] (animation/add-animation document name 1.0)))
                                                                              (.setValue animation-choice name))) nil))))])
    (ui/children! track-header [(doto (Label.) (localize! "properties") (.setMaxWidth Double/MAX_VALUE))
                                (icon-button "add-property" "gui-animation-add-property" "+" add-property!)])
    (HBox/setHgrow (first (.getChildren track-header)) Priority/ALWAYS)
    (.setViewOrder track-header -1.0)
    (.bind (.prefWidthProperty track-pane) (.multiply (.widthProperty scroll) 0.21))
    (replace-items! (.getTabs settings) [(doto (Tab.) (localize! "settings") (.setContent settings-scroll))
                                         (doto (Tab.) (localize! "curves") (.setContent curve-pane))
                                         (doto (Tab.) (localize! "events") (.setContent (doto (ScrollPane. event-list) (.setFitToWidth true))))])
    (.bind (.widthProperty detail-curve) (.widthProperty curve-pane))
    (.setConverter fps-choice (proxy [StringConverter] []
                                (toString [value] (str value " fps"))
                                (fromString [_] nil)))
    (.setConverter zoom-choice (proxy [StringConverter] []
                                 (toString [value] (if (nil? value) "" (str (if (== (double value) (Math/rint (double value))) (long value) value) "%")))
                                 (fromString [_] nil)))
    (.setConverter playback-mode (proxy [StringConverter] []
                                   (toString [value] (localization (localization/message (case value :reverse "gui-animation.mode-reverse" :ping-pong "gui-animation.mode-ping-pong" "gui-animation.mode-normal"))))
                                   (fromString [_] nil)))
    (.setConverter easing-choice (proxy [StringConverter] []
                                   (toString [value] (if (= value "linear") "Linear" (str value)))
                                   (fromString [_] nil)))
    (localize! frame-display "frame-display")
    (.setGraphic frame-display (glyph "M0 0 L0 17 L20 17 M4 5 L4 12 M9 2 L9 12 M14 6 L14 12 M19 3 L19 12"))
    (.setGraphic loop-check (glyph "M2 5 C6 0 15 0 19 5 L15 5 M19 5 L19 1 M18 13 C14 18 5 18 1 13 L5 13 M1 13 L1 17"))
    (.bind (.widthProperty curve) (.widthProperty easing-panel))
    (.setMinWidth easing-panel 0.0)
    (.setOnAction frame-display (ui/event-handler _ (swap! state assoc :frame-display (.isSelected frame-display))))
    (.setOnAction fullscreen
                  (ui/event-handler _
                    (if (.isSelected fullscreen)
                      (do (.setBottom root nil) (.setCenter root title))
                      (do (.setCenter root scene-content) (.setBottom root title)))))
    (.setTooltip fullscreen (doto (Tooltip.) (localize! "fullscreen")))
    (.setOnAction fps-choice (ui/event-handler _
                               (when-not @updating
                                 ((guarded #(commit! (fn [document] (assoc-in document [:data :metadata :fps] (.getValue fps-choice))))) nil))))
    (.setOnAction zoom-choice (ui/event-handler _
                                (when-let [value (.getValue zoom-choice)]
                                  (.setValue zoom-slider (/ (double value) 100.0)))))
    (.setOnAction name-field
                  (ui/event-handler _
                    ((guarded #(let [new-name (string/trim (.getText name-field))
                                     old-name (:animation-id @state)]
                                 (when (or (string/blank? new-name)
                                           (and (not= old-name new-name)
                                                (contains? (set (animation/animation-ids (:document @state))) new-name)))
                                   (throw (ex-info "Animation names must be nonempty and unique." {})))
                                 (commit! (fn [document]
                                            (update-in document [:data :animations]
                                                       (fn [clips] (mapv (fn [clip] (if (= old-name (:animation_id clip)) (assoc clip :animation_id new-name) clip)) clips)))))
                                 (.setValue animation-choice new-name))) nil)))
    (localize! preview-check "preview")
    (localize! record-button "record")
    (localize! loop-check "loop")
    (.setText play-button "")
    (.setGraphic play-button (glyph "M0 0 L16 9 L0 18 Z"))
    (ui/add-styles! play-button ["animation-transport" "animation-play"])
    (.setTooltip play-button (doto (Tooltip.) (localize! "play")))
    (.setPromptText name-field "Animation name")
    (doseq [[^Node control text] [[animation-choice "Animation"] [node-choice "GUI node"]
                                  [property-choice "Animated property"] [easing-choice "Easing"]
                                  [time-field "Time in seconds"] [duration-field "Duration in seconds"]
                                  [name-field "New animation name"] [value-field "Keyframe value"]]]
      (.setAccessibleText control text))
    (.setTooltip preview-check (Tooltip. "Preview does not change the GUI layout. Turn off to edit the layout."))
    (.setTooltip record-button (doto (Tooltip.) (localize! "record-help")))
    (ui/children! parent [])
    (.setCenter root scene-content)
    (.setBottom root title)
    (reset! manipulation-handler animation-manipulation)
    (ui/fill-control root)
    (ui/children! parent [root])
    (ui/children! content [welcome toolbar-scroll scroll time-scroll settings status])
    (HBox/setHgrow canvas-pane Priority/ALWAYS)
    (HBox/setHgrow time-scroll Priority/ALWAYS)
    (VBox/setVgrow scroll Priority/ALWAYS)
    (.bind (.widthProperty canvas) (.widthProperty canvas-pane))
    (.bind (.prefHeightProperty canvas-pane) (.heightProperty canvas))
    (.setText status "")
    (.setDisable content (resource/read-only? gui-resource))
    (ui/observe (.valueProperty animation-choice)
                (fn [_ _ value]
                  (when (and value (not @updating))
                    (swap! state assoc :animation-id value :time 0.0 :selected nil :playing false :draft {} :offset 0.0)
                    (.setText duration-field (str (animation/animation-duration (:document @state) value)))
                    (.setText name-field value)
                    (update-preview!))))
    (.setOnAction duration-field
                  (ui/event-handler _
                    ((guarded #(commit! (fn [document]
                                          (animation/set-duration document (:animation-id @state) (finite-number duration-field))))) nil)))
    (.setOnAction easing-choice
                  (ui/event-handler _
                    (when-let [[node-name property-id time :as selected] (:selected @state)]
                      ((guarded #(when-let [key (coll/first-where (fn [key] (= time (:time key)))
                                                                  (animation/keyframes (:document @state) (:animation-id @state) node-name property-id))]
                                   (when (not= (:easing key) (.getValue easing-choice))
                                     (commit! (fn [document] (animation/set-keyframe document (:animation-id @state) node-name property-id time (:value key) (.getValue easing-choice))))
                                     (swap! state assoc :selected selected)))) nil))))
    (.setOnAction time-field (ui/event-handler _ ((guarded #(set-time! (finite-number time-field))) nil)))
    (.setOnAction frame-field (ui/event-handler _ ((guarded #(set-time! (/ (finite-number frame-field)
                                                                           (double (timeline/frames-per-second (:document @state)))))) nil)))
    (.setOnAction value-field (ui/event-handler _ ((guarded #(edit-value! (.getValue node-choice) (.getValue property-choice) (finite-number value-field))) nil)))
    (ui/observe (.valueProperty zoom-slider)
                (fn [_ _ value]
                  (swap! state assoc :zoom (double value) :offset 0.0)
                  (.setValue zoom-choice (* 100.0 (double value)))))
    (ui/observe (.valueProperty time-scroll)
                (fn [_ _ value] (swap! state assoc :offset (double value))))
    (.setOnAction record-button
                  (ui/event-handler _
                    (swap! state assoc :recording (.isSelected record-button) :playing false :preview true)
                    (.setSelected preview-check true)
                    ((guarded update-preview!) nil)))
    (.setOnAction preview-check
                  (ui/event-handler _
                    (if (.isSelected preview-check)
                      (do (swap! state assoc :preview true :playing false) ((guarded update-preview!) nil))
                      (stop-preview!))))
    (.setOnAction play-button
                  (ui/event-handler _
                    ((guarded (fn []
                                (swap! state update :playing not)
                                (when (:playing @state)
                                  (let [time (double (:time @state))
                                        duration (double (animation/animation-duration (:document @state) (:animation-id @state)))
                                        reverse (= :reverse (.getValue playback-mode))]
                                    (swap! state assoc :direction (if reverse -1 1))
                                    (set-time! (if reverse (if (<= time 0.0) duration time) (if (>= time duration) 0.0 time))))))) nil)))
    (.addEventFilter scroll MouseEvent/MOUSE_PRESSED
      (ui/event-handler event
        (when (= MouseButton/MIDDLE (.getButton ^MouseEvent event))
          (reset! pan {:x (.getSceneX ^MouseEvent event) :y (.getSceneY ^MouseEvent event)
                       :offset (:offset @state) :vertical (.getVvalue scroll)})
          (swap! state assoc :playing false)
          (.setCursor scroll Cursor/CLOSED_HAND)
          (.consume ^MouseEvent event))))
    (.addEventFilter scroll MouseEvent/MOUSE_DRAGGED
      (ui/event-handler event
        (when-let [{:keys [x y offset vertical]} @pan]
          (let [{:keys [document animation-id zoom]} @state
                duration (animation/animation-duration document animation-id)
                delta-x (- (.getSceneX ^MouseEvent event) (double x))
                delta-y (- (.getSceneY ^MouseEvent event) (double y))
                vertical-range (- (.getHeight (.getLayoutBounds track-content))
                                  (.getHeight (.getViewportBounds scroll)))]
            (swap! state assoc :offset (timeline/pan-offset duration zoom (.getWidth canvas) offset delta-x))
            (when (pos? vertical-range)
              (.setVvalue scroll (max 0.0 (min 1.0 (- (double vertical) (/ delta-y vertical-range))))))
            (draw-timeline! canvas @state)
            (.consume ^MouseEvent event)))))
    (.addEventFilter scroll MouseEvent/MOUSE_RELEASED
      (ui/event-handler event
        (when (and @pan (= MouseButton/MIDDLE (.getButton ^MouseEvent event)))
          (reset! pan nil)
          (.setCursor scroll Cursor/DEFAULT)
          (.consume ^MouseEvent event))))
    (.setOnMousePressed canvas
                        (ui/event-handler event
                          (when (= MouseButton/PRIMARY (.getButton ^MouseEvent event))
                            ((guarded
                               #(do
                                  (.requestFocus canvas)
                                  (swap! state assoc :playing false)
                                  (let [x (.getX ^MouseEvent event)
                                        y (.getY ^MouseEvent event)]
                                    (if-let [[node-name property-id keyframe] (timeline-key canvas @state x y)]
                                      (do (set-time! (:time keyframe))
                                          (swap! state assoc :selected [node-name property-id (:time keyframe)])
                                          (select-track! node-name property-id)
                                          (.setValue easing-choice (:easing keyframe))
                                          (reset! drag {:node-name node-name :property-id property-id :keyframe keyframe :x x}))
                                      (do (reset! drag nil)
                                          (set-time! (x-time canvas @state x))
                                          (when-let [{:keys [node-name property-id]}
                                                     (get (:rows @state)
                                                          (int (Math/floor (/ (- y 32.0) 28.0))))]
                                            (when (and property-id (>= y (+ (double (get @state :ruler-y 0.0)) 32.0)))
                                              (select-track! node-name property-id)))))))) nil)
                            ;; Keep the enclosing ScrollPane from stealing keyboard focus.
                            (.consume ^MouseEvent event))))
    (.setOnMouseDragged canvas
                        (ui/event-handler event
                          (when (.isPrimaryButtonDown ^MouseEvent event)
                            (when-let [{:keys [node-name property-id keyframe]} @drag]
                              (swap! state assoc :dragging-key [node-name property-id (:time keyframe)]))
                            ((guarded #(set-time! (x-time canvas @state (.getX ^MouseEvent event)))) nil))))
    (.setOnMouseReleased canvas
                         (ui/event-handler event
                           (when-let [{:keys [node-name property-id keyframe x]} @drag]
                             (reset! drag nil)
                             (swap! state dissoc :dragging-key)
                             (when (> (Math/abs (- (.getX ^MouseEvent event) (double x))) 4.0)
                               ((guarded #(let [new-time (:time @state)]
                                            (commit! (fn [document]
                                                       (animation/move-keyframe document (:animation-id @state) node-name property-id (:time keyframe) new-time)))
                                            (swap! state assoc :selected [node-name property-id new-time]))) nil)))))
    (.setOnKeyPressed canvas
                      (ui/event-handler event
                        (cond
                          (= KeyCode/DELETE (.getCode ^KeyEvent event)) (do ((guarded delete-key!) nil) (.consume event))
                          (= KeyCode/SPACE (.getCode ^KeyEvent event)) (do (.fire play-button) (.consume event))
                          (= KeyCode/COMMA (.getCode ^KeyEvent event)) (do ((guarded #(navigate! (if (.isAltDown ^KeyEvent event) :previous-key :previous-frame))) nil) (.consume event))
                          (= KeyCode/PERIOD (.getCode ^KeyEvent event)) (do ((guarded #(navigate! (if (.isAltDown ^KeyEvent event) :next-key :next-frame))) nil) (.consume event))
                          (= KeyCode/HOME (.getCode ^KeyEvent event)) (do ((guarded #(navigate! :first)) nil) (.consume event))
                          (= KeyCode/END (.getCode ^KeyEvent event)) (do ((guarded #(navigate! :last)) nil) (.consume event)))))
    (let [refresh! (fn [dt]
                     (when (.isVisible title)
                       (try
                         (refresh-document!)
                         (let [node-ids (g/node-value resource-node :node-ids)
                               names (vec (sort (into [] (coll/keys node-ids))))
                               selection (g/node-value (:app-view opts) :selected-node-ids)]
                           (when (not= names (vec (.getItems node-choice)))
                             (ui/items! node-choice names))
                           (when (not= selection (:last-selection @state))
                             (swap! state assoc :last-selection selection)
                             (when-let [[name _] (coll/first-where #(contains? (set selection) (second %)) node-ids)]
                               (.setValue node-choice name))))
                         (when (:playing @state)
                           (let [duration (double (animation/animation-duration (:document @state) (:animation-id @state)))
                                 next-state (timeline/advance-playback (:time @state) duration dt (.isSelected loop-check) (.getValue playback-mode) (:direction @state))]
                             (set-time! (:time next-state))
                             (swap! state assoc :playing (:playing next-state) :direction (:direction next-state))))
                         (when (not= (:playing @state) (:play-label @state))
                           (swap! state assoc :play-label (:playing @state))
                           (.setGraphic play-button (glyph (if (:playing @state) "M0 0 L5 0 L5 18 L0 18 Z M10 0 L15 0 L15 18 L10 18 Z" "M0 0 L16 9 L0 18 Z")))
                           (localize! (.getTooltip play-button) (if (:playing @state) "pause" "play")))
                         (sync-fields!)
                         (refresh-tracks!)
                         (let [{:keys [document animation-id zoom offset recording preview draft exists]} @state
                               duration (double (animation/animation-duration document animation-id))
                               maximum (max 0.0 (- duration (timeline/visible-span zoom)))
                               mode (cond recording "recording" (pos? (count draft)) "draft" preview "previewing" :else "layout")]
                           (.setMax time-scroll maximum)
                           (.setVisibleAmount time-scroll (timeline/visible-span zoom))
                           (.setUnitIncrement time-scroll (/ 1.0 (double (timeline/frames-per-second document))))
                           (.setBlockIncrement time-scroll (* 0.9 (timeline/visible-span zoom)))
                           (.setValue time-scroll (min maximum (double offset)))
                           (.setDisable time-scroll (zero? maximum))
                           (.setVisible time-scroll (pos? maximum))
                           (.setManaged time-scroll (pos? maximum))
                           (let [message (.getText status)]
                             (.setVisible status (not (or (string/blank? message) (= message path))))
                             (.setManaged status (.isVisible status)))
                           (draw-curve! curve @state (.getValue easing-choice) false)
                           (draw-curve! detail-curve @state (.getValue easing-choice) true)
                           (let [keys (into [] (remove #(contains? #{"tween" 1} (:key_type %)))
                                            (:animation_keys (coll/first-where #(= animation-id (:animation_id %)) (get-in document [:data :animations]))))]
                             (when (not= keys (:event-keys @state))
                               (swap! state assoc :event-keys keys)
                               (ui/children! event-list
                                             (if (zero? (count keys))
                                               [(doto (Label.) (localize! "events-empty"))]
                                               (mapv #(Label. (str (:start_time %) " s  ·  " (pr-str %))) keys)))))
                           (.setDisable toolbar (not exists))
                           (.setVisible welcome (not exists))
                           (.setManaged welcome (not exists))
                           (.setDisable editbar (not exists))
                           (.setDisable (.lookup editbar "#gui-animation-delete-key") (nil? (:selected @state)))
                           (.setDisable canvas (not exists))
                           (when (not= mode (:mode-label @state))
                             (swap! state assoc :mode-label mode)
                             (localize! mode-label mode)
                             (.setTextFill mode-label (paint (cond recording colors/defold-orange preview colors/defold-light-blue :else colors/bright-grey-light)))))
                         (update-preview!)
                         (draw-timeline! canvas @state)
                         (catch Exception error
                           (stop-preview!)
                           (.setText status (.getMessage error))))))]
      {:refresh-fn refresh!
       :dispose-fn #(swap! state assoc :playing false :preview false)})))
