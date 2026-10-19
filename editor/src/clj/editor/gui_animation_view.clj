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
            [editor.dock-windows :as dock-windows]
            [editor.gui-animation-data :as animation]
            [editor.gui-animation-inspector :as inspector]
            [editor.gui-animation-io :as animation-io]
            [editor.gui-animation-properties :as animation-properties]
            [editor.gui-animation-preview :as preview]
            [editor.gui-animation-timeline :as timeline]
            [editor.localization :as localization]
            [editor.resource :as resource]
            [editor.ui :as ui]
            [editor.workspace :as workspace]
            [util.coll :as coll])
  (:import [java.nio.file Files OpenOption StandardOpenOption]
           [javafx.geometry Insets Orientation Pos Side]
           [javafx.scene Cursor Node Parent]
           [javafx.scene.canvas Canvas]
           [javafx.scene.control Button CheckBox ComboBox ContextMenu Label MenuButton MenuItem ScrollBar ScrollPane Separator Slider SplitPane Tab TabPane TabPane$TabClosingPolicy TextField ToggleButton Tooltip]
           [javafx.scene.control.skin SplitPaneSkin]
           [javafx.scene.input Clipboard ClipboardContent KeyCode KeyEvent MouseButton MouseEvent]
           [javafx.scene.layout BorderPane ColumnConstraints FlowPane GridPane HBox Pane Priority Region StackPane VBox]
           [javafx.scene.paint Color]
           [javafx.scene.shape SVGPath]
           [javafx.scene.text Font]
           [javafx.util StringConverter]
           [javafx.stage FileChooser FileChooser$ExtensionFilter]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(def ^:private properties animation/supported-properties)

(defn- cache-last [f]
  ;; Keep only the latest inputs: scrubbing must not retain a pose for every time.
  (let [cache (volatile! nil)]
    (fn [& args]
      (let [entry @cache]
        (if (and entry (= args (first entry)))
          (second entry)
          (let [result (apply f args)]
            (vreset! cache [args result])
            result))))))

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
  (animation-properties/node-values node-id))

(defn- preview-overrides [node-ids values]
  (into {} (keep (fn [[name channels]]
                   (when-let [id (get node-ids name)]
                     [id (animation-properties/node-overrides id channels)]))) values))

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
  (let [{:keys [document time selected selected-keys active-track zoom offset dragging-key frame-display rows ruler-y marquee]} state
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
      (let [row (get rows index)
            top (+ 32.0 (* 28.0 (double index)))
            y (+ top 14.0)]
        (.setFill gc (Color/web (cond
                                  (= active-track [node-name property-id]) "#174779"
                                  (= kind :node) "#2b2b2b"
                                  (odd? (long index)) "#181818"
                                  :else "#181818")))
        (.fillRect gc 0.0 top width 28.0)
        (when (= kind :segment)
          (let [key (:key row)
                x0 (time-x canvas state (get key :start_time 0))
                x1 (time-x canvas state (+ (double (get key :start_time 0)) (double (get key :duration 0))))]
            (.setFill gc (Color/web (if (or (= (:selected-raw state) (:key-index row)) (contains? (:selected-raws state) (:key-index row))) "#83b7fb" "#41798c")))
            (.fillRoundRect gc x0 (+ top 6.0) (max 8.0 (- x1 x0)) 16.0 3.0 3.0)
            (.setFill gc (Color/web "#eeeeee"))
            (.fillText gc (get key :event_id (get key :property_id "")) (+ x0 7.0) (+ y 4.0))))
        (when property-id
          (doseq [keyframe (:keyframes row)]
            (let [key-id [node-name property-id (:time keyframe)]
                  dragging (= dragging-key key-id)
                  x (time-x canvas state (if dragging time (:time keyframe)))
                  active (or dragging (= selected key-id) (contains? selected-keys key-id))]
              (.setFill gc (Color/web (if active "#d4e8ff" "#83b7fb")))
              (.fillPolygon gc (double-array [x (+ x 5.0) x (- x 5.0)])
                            (double-array [(- y 5.0) y (+ y 5.0) y]) 4))))))
    (when-let [[x0 y0 x1 y1] marquee]
      (.setStroke gc (Color/web "#83b7fb"))
      (.strokeRect gc (min (double x0) (double x1)) (min (double y0) (double y1))
                      (Math/abs (- (double x1) (double x0))) (Math/abs (- (double y1) (double y0)))))
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
                            (double (get-in (animation/evaluate (get state :preview-document document) animation-id (* duration t)) [node-name property-id] 0.0))
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
        {:keys [node-name property-id kind key key-index]} (get rows row)]
    (when (>= (double y) (+ (double (get state :ruler-y 0.0)) 32.0))
      (if (= kind :segment)
        (let [start (time-x canvas state (get key :start_time 0))
              end (time-x canvas state (+ (double (get key :start_time 0)) (double (get key :duration 0))))]
          (when (<= (- start 4.0) (double x) (+ (max (+ start 8.0) end) 4.0))
            [node-name nil {:raw-index key-index :time (get key :start_time 0) :easing (get key :easing "linear") :raw-key key}]))
        (when property-id
          (when-let [keyframe (coll/first-where #(< (Math/abs (- (double x) (time-x canvas state (:time %)))) 9.0)
                                              (timeline/display-keyframes document animation-id node-name property-id))]
            [node-name property-id keyframe]))))))

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
                     :selected nil :selected-keys #{} :selected-raws #{} :clipboard {:points [] :segments []} :speed 1.0 :last-lines nil :last-selection nil :exists false})
        updating (atom false)
        tool-controller-id (g/node-value view-id :tool-controller-id)
        manipulation-handler (atom nil)
        scene-content (first (.getChildrenUnmodifiable parent))
        root (BorderPane.)
        content (doto (VBox. 0.0) (.setMinWidth 0.0) (ui/add-style! "gui-animation-workspace") (.setPrefHeight 468.0))
        content-scroll (doto (ScrollPane. content) (.setFitToWidth true) (.setFitToHeight true)
                             (.setMinSize 0.0 0.0) (ui/add-style! "animation-workspace-scroll"))
        workspace-tab (Tab.)
        workspace-tabs (doto (TabPane.) (.setId "gui-animation-tabs") (.setMinSize 0.0 0.0)
                             (.setTabClosingPolicy TabPane$TabClosingPolicy/UNAVAILABLE))
        title (doto (StackPane.) (.setId "gui-animation-panel") (.setMinSize 0.0 120.0))
        editor-toggle (doto (ToggleButton.) (.setId "gui-animation-toggle") (.setSelected true))
        scene-pane (doto (StackPane.) (.setMinSize 0.0 64.0) (ui/children! [scene-content editor-toggle]))
        scene-split (doto (SplitPane.) (.setId "gui-animation-scene-split") (.setOrientation Orientation/VERTICAL)
                          (.setMinSize 0.0 0.0))
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
        fps-choice (doto (ComboBox.) (.setId "gui-animation-fps") (ui/items! (mapv int [24 30 60 120])) (.setValue (int 60)))
        frame-display (doto (ToggleButton.) (.setId "gui-animation-frame-display") (.setSelected true))
        playback-mode (doto (ComboBox.) (.setId "gui-animation-playback-mode") (ui/items! [:normal :reverse :ping-pong]) (.setValue :normal))
        curve (doto (Canvas. 200.0 80.0) (.setId "gui-animation-easing-curve"))
        detail-curve (doto (Canvas. 700.0 112.0) (.setId "gui-animation-detail-curve"))
        curve-pane (doto (Pane.) (.setMinWidth 0.0) (ui/children! [detail-curve]))
        initial-choice (doto (ComboBox.) (.setId "gui-animation-initial-state"))
        speed-choice (doto (ComboBox.) (.setId "gui-animation-speed") (ui/items! [0.1 0.25 0.5 1.0 1.5 2.0 4.0]) (.setValue 1.0))
        curve-drag (atom nil)
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
        source-cache (atom {})
        load-source! (fn [source-path]
                       (letfn [(load-impl [source-path visited]
                                 (when (or (contains? visited source-path) (> (count visited) 16))
                                   (throw (ex-info "Cyclic template animation file reference." {:path source-path})))
                                 (if-let [node-id (animation-resource-node project source-path)]
                                   (let [lines (g/node-value node-id :save-value)
                                         cached (get @source-cache source-path)]
                                     (if (and (= lines (:lines cached))
                                              (not (coll/any? (fn [[path expected]]
                                                                (not= expected (when-let [id (animation-resource-node project path)] (g/node-value id :save-value))))
                                                              (:dependencies cached))))
                                       (:document cached)
                                       (let [dependencies (atom {})
                                             document (animation/read-source (code-data/lines->string lines)
                                                                             (fn [path]
                                                                               (let [child (load-impl path (conj visited source-path))
                                                                                     entry (get @source-cache path)]
                                                                                 (swap! dependencies merge {path (:lines entry)} (:dependencies entry))
                                                                                 child)))]
                                         (swap! source-cache assoc source-path {:lines lines :document document :dependencies @dependencies})
                                         document)))
                                   (throw (ex-info (str "Template animation resource is missing: " source-path) {}))))]
                         (load-impl source-path #{})))
        resolved-document! (fn [document] (animation/resolve-preview document (:animation-id @state) load-source!))
        evaluate-cached (cache-last animation/evaluate)
        preview-issues-cached (cache-last
                               (fn [document animation-id node-ids]
                                 (into (animation/preview-issues document animation-id)
                                       (comp (map first) (distinct)
                                             (remove #(contains? node-ids %))
                                             (map #(str "GUI node is missing: " %)))
                                       (timeline/tracks document animation-id))))
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
                                document (resolved-document! document)
                                _ (swap! state assoc :preview-document document)
                                node-ids (g/node-value resource-node :node-ids)
                                issues (when preview
                                         (preview-issues-cached document animation-id node-ids))
                                active (and preview (zero? (count issues)))
                                overrides (if active
                                            (preview-overrides node-ids
                                                               (reduce (fn [values [node-name property-id]] (update values node-name dissoc property-id))
                                                                       (merge-with merge (evaluate-cached document animation-id time) draft)
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
                      ;; Mouse events only publish the newest time. The editor pulse
                      ;; evaluates and draws it once, without a backlog of old poses.
                      (.setSelected preview-check true)))
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
                             (let [value (or (get-in (merge-with merge (evaluate-cached (resolved-document! document) animation-id time) draft) [node-name property-id])
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
                                           :selected-keys #{} :selected-raws #{} :selected-raw nil
                                           :time (min (double (:time @state)) (double (animation/animation-duration document animation-id)))
                                           :exists true :last-lines lines)
                                    (reset! updating true)
                                    (try (ui/items! animation-choice ids)
                                         (.setValue animation-choice animation-id)
                                         (ui/items! initial-choice (into [""] (remove #{animation-id}) ids))
                                         (.setValue initial-choice (get (coll/first-where #(= animation-id (:animation_id %)) (get-in document [:data :animations])) :initial_state ""))
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
                                                                (get (animation/evaluate (resolved-document! document) animation-id time) name))]
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
        selection (fn [] (if (pos? (count (:selected-keys @state))) (:selected-keys @state) (if-let [key (:selected @state)] #{key} #{})))
        raw-selection (fn [] (if (pos? (count (:selected-raws @state))) (:selected-raws @state)
                              (if-let [index (:selected-raw @state)] #{index} #{})))
        delete-key! (fn []
                      (let [points (selection) indices (raw-selection)]
                        (when (pos? (+ (count points) (count indices)))
                          (commit! #(animation/delete-selection % (:animation-id @state) points indices))
                          (swap! state assoc :selected nil :selected-keys #{} :selected-raw nil :selected-raws #{}))))
        copy-keys! (fn [] (swap! state assoc :clipboard (animation/copy-selection (:document @state) (:animation-id @state) (selection) (raw-selection))))
        paste-keys! (fn []
                      (let [{:keys [points segments] :as clipboard} (:clipboard @state)
                            times (into (mapv #(double (:time %)) points) (map #(double (get % :start_time 0))) segments)]
                        (when (pos? (count times))
                          (let [offset (- (double (:time @state)) (double (reduce min times)))]
                            (commit! #(animation/paste-selection % (:animation-id @state) clipboard offset))
                            (swap! state assoc :selected-keys (into #{} (map (fn [point] [(:node-id point) (:property-id point) (+ (double (:time point)) offset)])) points)
                                   :selected-raw nil
                                   :selected-raws (let [total (count (animation/animation-keys (:document @state) (:animation-id @state)))]
                                                    (set (range (- total (count segments)) total))))))))
        commit-drafts! (fn []
                         (let [{:keys [animation-id time draft]} @state]
                           (commit! (fn [document]
                                      (reduce-kv (fn [document name channels]
                                                   (reduce-kv (fn [document property value]
                                                                (timeline/record-value document animation-id name property time value
                                                                                       (get (node-values (get (g/node-value resource-node :node-ids) name)) property)
                                                                                       (.getValue easing-choice))) document channels)) document draft)))
                           (swap! state assoc :draft {})
                           (update-preview!)))
        reset-drafts! (fn [] (swap! state assoc :draft {}) (update-preview!))
        key-inspector (inspector/create! {:state state :commit! commit! :set-time! set-time! :localize! localize! :guard guarded :resolve-document! resolved-document!
                                          :node-names #(vec (sort (into [] (coll/keys (g/node-value resource-node :node-ids)))))
                                          :template-document! (fn [name]
                                                                (let [target (animation/template-document (:document @state) name)]
                                                                  (cond (map? target) target (string? target) (load-source! target) :else (:document @state))))})
        export-label (doto (Label.) (.setId "gui-animation-export-status") (.setWrapText true))
        export-warning (doto (Label.) (.setId "gui-animation-export-warning") (.setWrapText true)
                             (localize! "export-stale") (.setVisible false) (.setManaged false))
        export-status-cached (cache-last animation-io/export-status)
        event-list (doto (VBox. 4.0) (.setId "gui-animation-event-log"))
        event-log (doto (VBox. 6.0)
                    (ui/children!
                      [(doto (HBox. 8.0)
                         (ui/children!
                           [(button "clear-event-log" "gui-animation-clear-event-log"
                                    #(swap! state assoc :event-log []))
                            (button "copy-callback" "gui-animation-copy-callback"
                                    #(let [module-name (-> path (subs 1) (string/replace #"\.json$" "") (string/replace "/" "."))
                                           text (animation/callback-template (:document @state) (:animation-id @state) module-name)]
                                       (.setContent (Clipboard/getSystemClipboard)
                                                    (doto (ClipboardContent.) (.putString text)))
                                       (localize! status "callback-copied")))]))
                       export-label
                       (doto (Label.) (localize! "event-log-hint") (.setWrapText true))
                       (doto (ScrollPane. event-list) (.setFitToWidth true) (.setPrefViewportHeight 100.0))]))
        refresh-event-log! (fn []
                             (let [entries (:event-log @state)
                                   context (:event-context @state)
                                   export-status (export-status-cached (:document @state))]
                               (when (not= export-status (:export-status @state))
                                 (swap! state assoc :export-status export-status)
                                 (localize! export-label (name export-status))
                                 (.setVisible export-warning (= :export-stale export-status))
                                 (.setManaged export-warning (= :export-stale export-status)))
                               (when (not= entries (:displayed-events @state))
                                 (swap! state assoc :displayed-events entries)
                                 (ui/children! event-list
                                   (into []
                                         (map (fn [{:keys [key-index time event-id data direction]}]
                                                (doto (Button. (format "%s  %.3f s  |  %s  |  %s"
                                                                      (if (neg? (long direction)) "←" "→")
                                                                      (double time) event-id (pr-str data)))
                                                  (.setMnemonicParsing false) (.setWrapText true)
                                                  (.setMaxWidth Double/MAX_VALUE) (.setAlignment Pos/CENTER_LEFT)
                                                  (.setOnAction (ui/event-handler _
                                                                  (when (= context [(:document @state) (:animation-id @state)])
                                                                    (swap! state assoc :playing false)
                                                                    ((:select! key-inspector) key-index)))))))
                                         entries)))))
        timeline-menu (ContextMenu.)
        add-segment! (fn [kind time node-name]
                       (let [{:keys [document animation-id]} @state
                             index (count (animation/animation-keys document animation-id))
                             key (merge {:key_type kind :node_id (or node-name "")
                                         :start_time time :duration 0.0}
                                        (case kind
                                          "event" {:event_id "event" :data "" :start_value 0.0 :end_value 1.0 :easing "linear"}
                                          "trigger" {:property_id "visible" :start_data "true" :data "true"}))]
                         (swap! state assoc :playing false)
                         (commit! #(animation/replace-key % animation-id nil key))
                         ((:select! key-inspector) index)
                         ((:refresh! key-inspector))))
        create! (fn []
                  (let [file (io/file (workspace/project-directory workspace) (subs path 1))]
                    (when-not (.exists file)
                      (Files/writeString (.toPath file) (animation/write-json (animation/new-document gui-path))
                                         (into-array OpenOption [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE])))
                    (workspace/resource-sync! workspace)
                    (refresh-document!)))
        save! #(ui/execute-command (ui/contexts (.getScene root) true) :file.save-all nil)
        mark-exported! (fn []
                         (let [signature (animation-io/export-signature (:document @state))]
                           (commit! #(assoc-in % [:data :metadata :editor_export_signature] signature))
                           (save!)))
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
                                              #(let [chooser (doto (FileChooser.) (.setTitle "Import Panthera animation"))]
                                                 (.add (.getExtensionFilters chooser)
                                                       (FileChooser$ExtensionFilter. "Panthera animation" (java.util.ArrayList. ["*.json" "*.lua"])))
                                                 (when-let [file (.showOpenDialog chooser (.getWindow (.getScene root)))]
                                                   (let [document (animation-io/read-document-file file load-source!)]
                                                     (create!) (commit! (constantly document))))))
                                      (button "export-json" "gui-animation-export-json"
                                              #(let [chooser (doto (FileChooser.) (.setTitle "Export Panthera JSON")
                                                                   (.setInitialFileName (.getName (io/file path))))]
                                                 (.add (.getExtensionFilters chooser)
                                                       (FileChooser$ExtensionFilter. "Panthera animation" (java.util.ArrayList. ["*.json" "*.lua"])))
                                                 (when-let [file (.showSaveDialog chooser (.getWindow (.getScene root)))]
                                                   (animation-io/write-document-file! file :json (:document @state))
                                                   (mark-exported!)
                                                   (workspace/resource-sync! workspace))))
                                      (button "export" "gui-animation-export"
                                              #(let [chooser (doto (FileChooser.) (.setTitle "Export Panthera Lua")
                                                                   (.setInitialFileName (str (subs (.getName (io/file path)) 0 (- (count (.getName (io/file path))) 5)) ".lua")))]
                                                 (.add (.getExtensionFilters chooser)
                                                       (FileChooser$ExtensionFilter. "Lua animation" (java.util.ArrayList. ["*.lua"])))
                                                 (when-let [file (.showSaveDialog chooser (.getWindow (.getScene root)))]
                                                   (animation-io/write-document-file! file :lua (:document @state))
                                                   (mark-exported!)
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
        ^GridPane settingsbar (grid [["name" (doto (HBox. 6.0) (ui/children! [name-field initial-choice]))]
                                     ["duration" (doto (HBox. 6.0) (ui/children! [duration-field speed-choice]))]
                                     ["playback-mode" (doto (HBox. 12.0) (.setAlignment Pos/CENTER_LEFT)
                                                            (ui/children! [playback-mode bottom-loop]))]])
        key-more (doto (MenuButton. "…") (.setId "gui-animation-key-more") (ui/add-style! "button-icon"))
        key-actions (doto (HBox. 8.0) (.setAlignment Pos/CENTER_RIGHT)
                          (ui/children! [(doto ^Button (button "key" "gui-animation-add-key"
                                                               #(record-value! (.getValue node-choice) (.getValue property-choice) (finite-number value-field)))
                                           (ui/add-style! "animation-primary"))
                                         (button "delete" "gui-animation-delete-key" delete-key!) key-more]))
        editbar (doto (VBox. 8.0)
                  (ui/children! [(grid [["current-property" (doto (HBox. 8.0) (ui/children! [node-choice property-choice]))]
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
        editor-rows-cached (cache-last
                            (fn [document animation-id collapsed node-name child-counts]
                              (into []
                                    (map (fn [row]
                                           (if-not (:property-id row)
                                             row
                                             (assoc row :keyframes (timeline/display-keyframes document animation-id (:node-name row) (:property-id row))))))
                                    (into (timeline/editor-rows (timeline/tracks document animation-id) collapsed node-name child-counts)
                                          (timeline/segment-rows document animation-id)))))
        refresh-tracks! (fn []
                          (let [{:keys [document animation-id collapsed time draft active-track muted]} @state
                                child-counts (frequencies (eduction (map :parent) (:nodes (g/node-value resource-node :save-value))))
                                rows (editor-rows-cached document animation-id collapsed (.getValue node-choice) child-counts)
                                context [animation-id rows]
                                values (merge-with merge (evaluate-cached (resolved-document! document) animation-id time) draft)
                                node-ids (g/node-value resource-node :node-ids)
                                base-values (cache-last node-values)
                                keyframes (into {} (keep (fn [row]
                                                          (when (:property-id row)
                                                            [[(:node-name row) (:property-id row)] (:keyframes row)]))) rows)]
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
                                                    (fn [index {:keys [node-name property-id kind child-count label key-index]}]
                                                      (case kind
                                                        :segment (doto (Button. label) (.setMnemonicParsing false) (ui/add-style! "animation-node-row")
                                                                       (.setMaxWidth Double/MAX_VALUE) (.setAlignment Pos/CENTER_LEFT)
                                                                       (.setOnAction (ui/event-handler _ ((:select! key-inspector) key-index))))
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
                                    node-id (get node-ids node-name)
                                    value (or (get-in values track) (when node-id (get (base-values node-id) property-id)))
                                    ^HBox row (get @track-rows track)
                                    ^Button key-button (last (.getChildren row))]
                                (.setDisable field (:playing @state))
                                (ui/set-style! row "active" (= active-track track))
                                (.setText key-button (if (coll/any? #(< (Math/abs (- (double time) (double (:time %)))) 1.0e-9)
                                                                    (get keyframes track)) "◆" "◇"))
                                (when (and (number? value) (not (.isFocused field)))
                                  (.setText field (format "%.4f" (double value))))))))]
    (localize! workspace-tab "title")
    (.setGraphic workspace-tab (glyph "M10 0 L20 5 L20 16 L10 21 L0 16 L0 5 Z M0 5 L10 10 L20 5 M10 10 L10 21"))
    (.setContent workspace-tab content-scroll)
    (.add (.getTabs workspace-tabs) workspace-tab)
    (ui/children! title [workspace-tabs header])
    (StackPane/setAlignment header Pos/TOP_RIGHT)
    (StackPane/setMargin header (Insets. 0.0 0.0 0.0 0.0))
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
    (doseq [[^MenuButton menu actions] [[more-menu [["duplicate-clip" "gui-animation-duplicate-clip"
                                                                  #(let [id (:animation-id @state)
                                                                         ids (set (animation/animation-ids (:document @state)))
                                                                         name (loop [index 1] (let [name (str id "_copy" index)] (if (contains? ids name) (recur (inc index)) name)))]
                                                                     (commit! (fn [document] (animation/duplicate-animation document id name)))
                                                                     (.setValue animation-choice name))]
                                                                 ["delete-clip" "gui-animation-delete-clip"
                                                                  #(commit! (fn [document] (animation/remove-animation document (:animation-id @state))))]]]
                                       [key-more [["copy-keys" "gui-animation-copy-keys" copy-keys!]
                                                  ["paste-keys" "gui-animation-paste-keys" paste-keys!]
                                                  ["commit-all" "gui-animation-commit-all" commit-drafts!]
                                                  ["reset-all" "gui-animation-reset-all" reset-drafts!]
                                                  ["custom-curve" "gui-animation-custom-curve"
                                                   #(when-let [[name property time] (:selected @state)]
                                                      (let [points (mapv (fn [index] (/ (double index) 16.0)) (range 17))
                                                            key (coll/first-where (fn [key] (= time (:time key))) (animation/keyframes (:document @state) (:animation-id @state) name property))]
                                                        (commit! (fn [document] (animation/set-keyframe document (:animation-id @state) name property time (:value key) points)))
                                                        (.setValue easing-choice points)
                                                        (swap! state assoc :selected [name property time])))]]]]]
      (doseq [[suffix id f] actions]
        (.add (.getItems menu) (doto (MenuItem.) (.setId id) (localize! suffix)
                                (.setOnAction (ui/event-handler _ ((guarded f) nil)))))))
    (ui/children! track-header [(doto (Label.) (localize! "properties") (.setMaxWidth Double/MAX_VALUE))
                                (icon-button "add-property" "gui-animation-add-property" "+" add-property!)])
    (HBox/setHgrow (first (.getChildren track-header)) Priority/ALWAYS)
    (.setViewOrder track-header -1.0)
    (.bind (.prefWidthProperty track-pane) (.multiply (.widthProperty scroll) 0.21))
    (replace-items! (.getTabs settings) [(doto (Tab.) (localize! "settings") (.setContent settings-scroll))
                                         (doto (Tab.) (localize! "curves") (.setContent curve-pane))
                                         (doto (Tab.) (localize! "events") (.setContent (doto (ScrollPane. (:node key-inspector)) (.setFitToWidth true) (.setFitToHeight true))))
                                         (doto (Tab.) (localize! "event-log") (.setContent event-log))])
    (.bind (.widthProperty detail-curve) (.widthProperty curve-pane))
    (.setConverter fps-choice (proxy [StringConverter] []
                                (toString [value] (str value " fps"))
                                (fromString [_] nil)))
    (.setConverter zoom-choice (proxy [StringConverter] []
                                 (toString [value] (if (nil? value) "" (format "%.0f%%" (double value))))
                                 (fromString [_] nil)))
    (.setConverter playback-mode (proxy [StringConverter] []
                                   (toString [value] (localization (localization/message (case value :reverse "gui-animation.mode-reverse" :ping-pong "gui-animation.mode-ping-pong" "gui-animation.mode-normal"))))
                                   (fromString [_] nil)))
    (.setConverter easing-choice (proxy [StringConverter] []
                                   (toString [value] (cond (vector? value) (localization (localization/message "gui-animation.custom-curve")) (= value "linear") "Linear" :else (str value)))
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
                      (do (swap! state assoc :scene-divider (aget (.getDividerPositions scene-split) 0))
                          (.remove (.getItems scene-split) title)
                          (.setCenter root title))
                      (do (.setCenter root nil)
                          (.add (.getItems scene-split) title)
                          (.setDividerPositions scene-split (double-array [(:scene-divider @state)]))
                          (.setCenter root scene-split)))))
    (localize! editor-toggle "toggle-editor")
    (.setTooltip editor-toggle (doto (Tooltip.) (localize! "toggle-editor-help")))
    (StackPane/setAlignment editor-toggle Pos/TOP_LEFT)
    (StackPane/setMargin editor-toggle (Insets. 20.0 0.0 0.0 20.0))
    (.setOnAction editor-toggle
                  (ui/event-handler _
                    ((guarded
                       (fn []
                         (if (.isSelected editor-toggle)
                           (do (.setVisible title true)
                               (.add (.getItems scene-split) title)
                               (.setDividerPositions scene-split (double-array [(double (get @state :scene-divider 0.45))]))
                               (refresh-document!)
                               (when (:reopen-preview @state)
                                 (swap! state assoc :preview true)
                                 (.setSelected preview-check true)
                                 (update-preview!)))
                           (do (when-not (.isSelected fullscreen)
                                 (swap! state assoc :scene-divider (aget (.getDividerPositions scene-split) 0)))
                               (swap! state assoc :reopen-preview (:preview @state))
                               (stop-preview!)
                               (.setSelected fullscreen false)
                               (.setCenter root scene-split)
                               (.remove (.getItems scene-split) title)
                               (.setVisible title false))))) nil)))
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
                                 (commit! (fn [document] (animation/rename-animation document old-name new-name)))
                                 (.setValue animation-choice new-name))) nil)))
    (.setTooltip initial-choice (doto (Tooltip.) (localize! "initial-state")))
    (.setConverter initial-choice (proxy [StringConverter] []
                                    (toString [value] (if (string/blank? value) (localization (localization/message "gui-animation.initial-state")) value))
                                    (fromString [_] nil)))
    (.setOnAction initial-choice (ui/event-handler _
                                  (when-not @updating
                                    ((guarded #(commit! (fn [document] (animation/set-initial-state document (:animation-id @state) (.getValue initial-choice))))) nil))))
    (.setConverter speed-choice (proxy [StringConverter] []
                                  (toString [value] (str value "×")) (fromString [_] nil)))
    (.setOnAction speed-choice (ui/event-handler _ (swap! state assoc :speed (double (.getValue speed-choice)))))
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
    ;; Toolbars are registered before the first CSS pulse, so scene descendants must already be attached.
    (.setSkin scene-split (SplitPaneSkin. scene-split))
    (.add (.getItems scene-split) scene-pane)
    (.add (.getItems scene-split) title)
    (.setDividerPositions scene-split (double-array [0.45]))
    (.setCenter root scene-split)
    (reset! manipulation-handler animation-manipulation)
    (ui/fill-control root)
    (ui/children! parent [root])
    (ui/children! content [welcome toolbar-scroll scroll time-scroll settings export-warning status])
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
                    (reset! updating true)
                    (try (ui/items! initial-choice (into [""] (remove #{value}) (animation/animation-ids (:document @state))))
                         (.setValue initial-choice (get (coll/first-where #(= value (:animation_id %)) (get-in (:document @state) [:data :animations])) :initial_state ""))
                         (finally (reset! updating false)))
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
    (doseq [^ComboBox control [node-choice property-choice]]
      (.setOnAction control (ui/event-handler _
                             (when-not @updating
                               (swap! state assoc :active-track [(.getValue node-choice) (.getValue property-choice)])))))
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
    (.setTooltip easing-choice (doto (Tooltip.) (localize! "curve-help")))
    (let [edit-curve! (fn [^MouseEvent event]
                        (when-let [selection @curve-drag]
                          (let [points (get selection :points)
                                x (max 0.0 (min 1.0 (/ (- (.getX event) 16.0) (max 1.0 (- (.getWidth curve) 32.0)))))
                                y (max -1.0 (min 2.0 (/ (- (- (.getHeight curve) 16.0) (.getY event)) (max 1.0 (- (.getHeight curve) 32.0)))))
                                index (max 1 (min (- (count points) 2) (long (Math/round (* x (dec (count points)))))))
                                points (assoc points index y)]
                            (swap! curve-drag assoc :points points)
                            (draw-curve! curve @state points false))))]
      (.setOnMousePressed curve (ui/event-handler event
                                 (when (= MouseButton/PRIMARY (.getButton ^MouseEvent event))
                                   (when-let [[name property time] (:selected @state)]
                                     (let [key (coll/first-where #(= time (:time %)) (animation/keyframes (:document @state) (:animation-id @state) name property))
                                           easing (:easing key)
                                           points (if (vector? easing) easing (mapv #(animation/ease easing (/ (double %) 16.0)) (range 17)))]
                                       (reset! curve-drag {:selection [name property time] :points points :value (:value key)})
                                       (edit-curve! event))))))
      (.setOnMouseDragged curve (ui/event-handler event (edit-curve! event)))
      (.setOnMouseReleased curve (ui/event-handler _
                                  (when-let [{:keys [selection points value]} @curve-drag]
                                    (reset! curve-drag nil)
                                    ((guarded #(let [[name property time] selection]
                                                 (commit! (fn [document] (animation/set-keyframe document (:animation-id @state) name property time value points)))
                                                 (.setValue easing-choice points)
                                                 (swap! state assoc :selected selection))) nil)))))
    (let [all-keys (fn []
                     (into #{} (mapcat (fn [[name property]]
                                          (mapv (fn [key] [name property (:time key)])
                                                (timeline/display-keyframes (:document @state) (:animation-id @state) name property))))
                           (timeline/tracks (:document @state) (:animation-id @state))))]
      (.setOnKeyPressed canvas
        (ui/event-handler event
          (let [code (.getCode ^KeyEvent event)
                shortcut (.isShortcutDown ^KeyEvent event)]
            (cond
              (and shortcut (= KeyCode/A code)) (do (swap! state assoc :selected-keys (all-keys) :selected-raws (into #{} (map :key-index) (timeline/segment-rows (:document @state) (:animation-id @state)))) (.consume event))
              (and shortcut (= KeyCode/C code)) (do ((guarded copy-keys!) nil) (.consume event))
              (and shortcut (= KeyCode/X code)) (do ((guarded #(do (copy-keys!) (delete-key!))) nil) (.consume event))
              (and shortcut (= KeyCode/V code)) (do ((guarded paste-keys!) nil) (.consume event))
              (and (.isShiftDown ^KeyEvent event) (= KeyCode/ENTER code)) (do ((guarded commit-drafts!) nil) (.consume event))
              (= KeyCode/U code) (do ((guarded reset-drafts!) nil) (.consume event))
              (contains? #{KeyCode/DELETE KeyCode/BACK_SPACE} code) (do ((guarded delete-key!) nil) (.consume event))
              (= KeyCode/SPACE code) (do (.fire play-button) (.consume event))
              (= KeyCode/M code) (do (.fire preview-check) (.consume event))
              (and (not shortcut) (contains? #{KeyCode/A KeyCode/D} code))
              (do (if (.isShiftDown ^KeyEvent event)
                    ((guarded #(navigate! (if (= KeyCode/A code) :previous-frame :next-frame))) nil)
                    (do (.setValue playback-mode (if (= KeyCode/A code) :reverse :normal)) (.fire play-button)))
                  (.consume event))
              (contains? #{KeyCode/Q KeyCode/E} code)
              (do (let [times (mapv #(double (nth % 2)) (selection))]
                    (when (pos? (count times)) (set-time! (reduce (if (= KeyCode/Q code) min max) times))))
                  (.consume event))
              (= KeyCode/COMMA code) (do ((guarded #(navigate! (if (.isAltDown ^KeyEvent event) :previous-key :previous-frame))) nil) (.consume event))
              (= KeyCode/PERIOD code) (do ((guarded #(navigate! (if (.isAltDown ^KeyEvent event) :next-key :next-frame))) nil) (.consume event))
              (contains? #{KeyCode/HOME KeyCode/R} code) (do ((guarded #(navigate! (if (.isShiftDown ^KeyEvent event) :last :first))) nil) (.consume event))
              (= KeyCode/END code) (do ((guarded #(navigate! :last)) nil) (.consume event))
              (contains? #{KeyCode/LEFT KeyCode/RIGHT} code)
              (do ((guarded #(let [fps (timeline/frames-per-second (:document @state))
                                  delta (/ (* (if (= KeyCode/LEFT code) -1.0 1.0) (if (.isShiftDown ^KeyEvent event) 10.0 1.0)) (double fps))
                                  keys (selection) raws (raw-selection)]
                              (when (pos? (+ (count keys) (count raws)))
                                (commit! (fn [document] (animation/move-selection document (:animation-id @state) keys raws delta)))
                                (swap! state assoc :selected-keys (into #{} (map (fn [[name property time]] [name property (+ (double time) delta)])) keys)
                                       :selected-raw nil
                                       :selected-raws (let [total (count (animation/animation-keys (:document @state) (:animation-id @state)))]
                                                        (set (range (- total (count raws)) total))))))) nil)
                  (.consume event)))))))
    (.setOnMousePressed canvas
                        (ui/event-handler event
                          (when (= MouseButton/SECONDARY (.getButton ^MouseEvent event))
                            (.hide timeline-menu)
                            (when (>= (.getY ^MouseEvent event) (+ (double (get @state :ruler-y 0.0)) 32.0))
                              (let [row (get (:rows @state) (int (Math/floor (/ (- (.getY ^MouseEvent event) 32.0) 28.0))))
                                    node-name (if (string/blank? (:node-name row)) (.getValue node-choice) (:node-name row))
                                    time (x-time canvas @state (.getX ^MouseEvent event))]
                                (replace-items! (.getItems timeline-menu)
                                                (mapv (fn [kind]
                                                        (doto (MenuItem.)
                                                          (.setId (str "gui-animation-add-" kind))
                                                          (localize! (str "add-" kind))
                                                          (.setDisable (and (= "trigger" kind) (string/blank? node-name)))
                                                          (.setOnAction (ui/event-handler _
                                                                          ((guarded #(do (add-segment! kind time node-name)
                                                                                         (.select (.getSelectionModel settings) (int 2)))) nil)))))
                                                      ["event" "trigger"]))
                                (.show timeline-menu canvas (.getScreenX ^MouseEvent event) (.getScreenY ^MouseEvent event))))
                            (.consume ^MouseEvent event))
                          (when (= MouseButton/PRIMARY (.getButton ^MouseEvent event))
                            (.hide timeline-menu)
                            ((guarded
                               #(do
                                  (.requestFocus canvas)
                                  (swap! state assoc :playing false)
                                  (let [x (.getX ^MouseEvent event)
                                        y (.getY ^MouseEvent event)]
                                    (if-let [[node-name property-id keyframe] (timeline-key canvas @state x y)]
                                      (do (let [id [node-name property-id (:time keyframe)]
                                                keys (if (.isShiftDown ^MouseEvent event)
                                                       (if (contains? (:selected-keys @state) id) (disj (:selected-keys @state) id) (conj (:selected-keys @state) id))
                                                       (if (contains? (:selected-keys @state) id) (:selected-keys @state) #{id}))]
                                            (set-time! (:time keyframe))
                                            (swap! state assoc :selected id :selected-keys keys))
                                          (if-let [index (:raw-index keyframe)]
                                            (let [previous (:selected-raws @state)
                                                  raw-keys (if (.isShiftDown ^MouseEvent event)
                                                             (if (contains? previous index) (disj previous index) (conj previous index))
                                                             (if (contains? previous index) previous #{index}))]
                                              ((:select! key-inspector) index)
                                              (swap! state assoc :selected nil :selected-keys #{} :selected-raw index :selected-raws raw-keys))
                                            (do (swap! state assoc :selected-raw nil :selected-raws #{}) (select-track! node-name property-id)
                                                (.setValue easing-choice (:easing keyframe))))
                                          (reset! drag {:node-name node-name :property-id property-id :keyframe keyframe :x x :keys (selection) :raws (raw-selection)}))
                                      (do (reset! drag (when (.isShiftDown ^MouseEvent event) {:marquee true :x x :y y :keys (selection)}))
                                          (when-not (.isShiftDown ^MouseEvent event)
                                            (swap! state assoc :selected-keys #{} :selected-raws #{} :selected-raw nil)
                                            (set-time! (x-time canvas @state x)))
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
          (if-let [{:keys [x y keys]} (and (:marquee @drag) @drag)]
            (let [x1 (.getX ^MouseEvent event) y1 (.getY ^MouseEvent event)
                  selected (into keys
                                 (mapcat (fn [[index {:keys [node-name property-id]}]]
                                           (let [cy (+ 46.0 (* 28.0 (double index)))]
                                             (if (and property-id (<= (min (double y) y1) cy (max (double y) y1)))
                                               (into [] (comp (filter #(<= (min (double x) x1) (time-x canvas @state (:time %)) (max (double x) x1)))
                                                              (map #(vector node-name property-id (:time %))))
                                                     (timeline/display-keyframes (:document @state) (:animation-id @state) node-name property-id)) []))))
                                 (mapv vector (range) (:rows @state)))]
              (swap! state assoc :marquee [x y x1 y1] :selected-keys selected)
              (draw-timeline! canvas @state))
            (do (when-let [{:keys [node-name property-id keyframe]} @drag]
                  (swap! state assoc :dragging-key [node-name property-id (:time keyframe)]))
                ((guarded #(set-time! (x-time canvas @state (.getX ^MouseEvent event)))) nil))))))
    (.setOnMouseReleased canvas
      (ui/event-handler event
        (when-let [{:keys [node-name property-id keyframe x keys raws marquee]} @drag]
          (reset! drag nil)
          (swap! state dissoc :dragging-key :marquee)
          (when (and (not marquee) (> (Math/abs (- (.getX ^MouseEvent event) (double x))) 4.0))
            ((guarded #(let [new-time (:time @state) offset (- (double new-time) (double (:time keyframe)))]
                         (commit! (fn [document] (animation/move-selection document (:animation-id @state) keys raws offset)))
                         (swap! state assoc :selected (when property-id [node-name property-id new-time])
                                :selected-keys (into #{} (map (fn [[name property time]] [name property (+ (double time) offset)])) keys)
                                :selected-raws (let [total (count (animation/animation-keys (:document @state) (:animation-id @state)))]
                                                 (set (range (- total (count raws)) total)))))) nil)))))
    (ui/user-data! (:tab opts) :editor.dock-windows/restore-content!
                   #(do (dock-windows/restore-slot! scene-pane)
                        (dock-windows/restore-slot! title)))
    (let [floating-count (atom 0)
          floating! (fn [floating]
                      (swap! floating-count (if floating inc dec))
                      (dock-windows/set-tab-empty! (:tab opts) (zero? (count (.getItems scene-split))))
                      (.setDisable fullscreen (pos? (long @floating-count)))
                      (.setDisable editor-toggle (pos? (long @floating-count))))
          floating-panels (mapv (fn [[slot key menu-items]]
                                 (dock-windows/register! slot
                                                        (str (resource/proj-path gui-resource) " — " (localization (localization/message key)))
                                                        {:persistent-key [:gui (resource/proj-path gui-resource) key]
                                                         :focus! (:focus-view! opts) :floating! floating!
                                                         :home-visible! (fn [visible]
                                                                          (when (and (not visible) (.isSelected fullscreen))
                                                                            (.fire fullscreen))
                                                                          (dock-windows/set-home-visible! slot visible))
                                                         :menu-items menu-items}))
                               [[scene-pane "dock.gui-scene" nil] [title "dock.gui-animation" (.getItems more-menu)]])
          refresh! (fn [dt]
                     (when (.isVisible title)
                       (try
                         (refresh-document!)
                         (let [context [(:document @state) (:animation-id @state)]]
                           (when (not= context (:event-context @state))
                             (swap! state assoc :event-context context :event-log [] :event-position nil)))
                         (when-let [^Node tab (coll/first-where
                                               #(identical? workspace-tabs (some-> ^Node % .getParent .getParent .getParent))
                                               (.lookupAll workspace-tabs ".tab"))]
                           (let [width (.getWidth (.getLayoutBounds tab))]
                             (StackPane/setMargin header (Insets. 0.0 0.0 0.0 width))))
                         ((:refresh! key-inspector))
                         (let [selection [(:animation-id @state) (:selected @state) (:selected-raw @state) (:selected-raws @state)]]
                           (when (not= selection (:inspected-selection @state))
                             (swap! state assoc :inspected-selection selection)
                             (cond
                               (or (:selected-raw @state) (pos? (count (:selected-raws @state))))
                               (.select (.getSelectionModel settings) (int 2))

                               (:selected @state)
                               (.select (.getSelectionModel settings) (int 1)))))
                         (let [node-ids (g/node-value resource-node :node-ids)
                               names (vec (sort (into [] (coll/keys node-ids))))
                               selection (g/node-value (:app-view opts) :selected-node-ids)]
                           (when (not= names (vec (.getItems node-choice)))
                             (ui/items! node-choice names))
                           (when-let [id (get node-ids (.getValue node-choice))]
                             (let [channels (animation-properties/numeric-channels id)]
                               (when (not= channels (vec (.getItems property-choice)))
                                 (let [selected (.getValue property-choice)]
                                   (ui/items! property-choice channels)
                                   (.setValue property-choice (if (contains? (set channels) selected) selected (first channels)))))))
                           (when (not= selection (:last-selection @state))
                             (swap! state assoc :last-selection selection)
                             (when-let [[name _] (coll/first-where #(contains? (set selection) (second %)) node-ids)]
                               (.setValue node-choice name))))
                         (when (:playing @state)
                           (let [duration (double (animation/animation-duration (:document @state) (:animation-id @state)))
                                 next-state (timeline/advance-playback (:time @state) duration (* (double dt) (double (:speed @state))) (.isSelected loop-check) (.getValue playback-mode) (:direction @state))]
                             (let [entries (timeline/playback-events (:document @state) (:animation-id @state)
                                                                    (:time @state) (* (double dt) (double (:speed @state)))
                                                                    (.isSelected loop-check) (.getValue playback-mode)
                                                                    (:direction @state)
                                                                    (not= [(:animation-id @state) (:time @state)] (:event-position @state)))]
                               (when (pos? (count entries))
                                 (swap! state update :event-log #(into [] (take 200) (into entries %)))))
                             (set-time! (:time next-state))
                             (swap! state assoc :playing (:playing next-state) :direction (:direction next-state)
                                    :event-position (when (:playing next-state) [(:animation-id @state) (:time next-state)]))))
                         (refresh-event-log!)
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
                           (let [curve-context [document (:preview-document @state) animation-id (:active-track @state) (.getValue easing-choice)
                                                (.getWidth curve) (.getWidth detail-curve)]]
                             (when (and (not @curve-drag) (not= curve-context (:curve-context @state)))
                               (swap! state assoc :curve-context curve-context)
                               (draw-curve! curve @state (.getValue easing-choice) false)
                               (draw-curve! detail-curve @state (.getValue easing-choice) true)))
                           (.setDisable toolbar (not exists))
                           (.setVisible welcome (not exists))
                           (.setManaged welcome (not exists))
                           (.setDisable editbar (not exists))
                           (.setDisable (.lookup editbar "#gui-animation-delete-key") (and (nil? (:selected @state)) (nil? (:selected-raw @state)) (zero? (count (:selected-raws @state))) (zero? (count (:selected-keys @state)))))
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
       :visible? (:visible? (second floating-panels))
       :dispose-fn #(do (doseq [panel floating-panels] ((:dispose! panel)))
                         (.hide timeline-menu)
                         (swap! state assoc :playing false :preview false))})))
