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
            [editor.gui-animation-timeline :as timeline]
            [editor.localization :as localization]
            [editor.math :as math]
            [editor.resource :as resource]
            [editor.ui :as ui]
            [editor.workspace :as workspace]
            [util.coll :as coll])
  (:import [java.nio.file Files OpenOption StandardOpenOption]
           [javafx.geometry Insets Pos]
           [javafx.scene Node Parent]
           [javafx.scene.canvas Canvas]
           [javafx.scene.control Button CheckBox ComboBox Label ScrollBar ScrollPane Slider TextField TitledPane ToggleButton Tooltip]
           [javafx.scene.input KeyCode KeyEvent MouseEvent]
           [javafx.scene.layout BorderPane FlowPane HBox Pane Priority VBox]
           [javafx.scene.paint Color]
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
  (let [{:keys [document animation-id zoom offset]} state
        duration (animation/animation-duration document animation-id)]
    (+ 16.0 (* (/ (- (double time) (double offset)) (max 0.001 (double duration)))
               (double zoom) (max 1.0 (- (.getWidth canvas) 32.0))))))

(defn- x-time
  ^double [^Canvas canvas state x]
  (let [{:keys [document animation-id zoom offset]} state
        duration (animation/animation-duration document animation-id)
        time (+ (double offset)
                (* (/ (double duration) (double zoom))
                   (/ (- (double x) 16.0) (max 1.0 (- (.getWidth canvas) 32.0)))))]
    (double (timeline/snap-time document animation-id time))))

(defn- draw-timeline! [^Canvas canvas state]
  (let [{:keys [document animation-id time selected active-track recording collapsed zoom offset dragging-key]} state
        rows (timeline/rows (timeline/tracks document animation-id) collapsed)
        duration (double (animation/animation-duration document animation-id))
        fps (double (timeline/frames-per-second document))
        gc (.getGraphicsContext2D canvas)
        width (.getWidth canvas)
        height (max 100.0 (+ 32.0 (* 28.0 (count rows))))]
    (.setHeight canvas height)
    (.setFill gc (paint colors/dark-black))
    (.fillRect gc 0.0 0.0 width height)
    (doseq [[index {:keys [node-name property-id]}] (eduction (map-indexed vector) rows)]
      (let [top (+ 32.0 (* 28.0 (double index)))
            y (+ top 14.0)]
        (.setFill gc (paint (cond
                             (= active-track [node-name property-id]) colors/selected-blue
                             (nil? property-id) colors/bright-black
                             :else colors/dark-black)))
        (.fillRect gc 0.0 top width 28.0)
        (when property-id
          (let [keyframes (timeline/display-keyframes document animation-id node-name property-id)]
            (when (zero? (count keyframes))
              (.setFill gc (paint colors/bright-grey-light))
              (.fillText gc "Read-only imported track" 16.0 (+ y 4.0)))
            (doseq [keyframe keyframes]
              (let [key-id [node-name property-id (:time keyframe)]
                    dragging (= dragging-key key-id)
                    x (time-x canvas state (if dragging time (:time keyframe)))
                    active (or dragging (= selected key-id))]
                (.setFill gc (paint (if active colors/defold-yellow colors/defold-light-blue)))
                (.fillPolygon gc (double-array [x (+ x 5.0) x (- x 5.0)])
                              (double-array [(- y 5.0) y (+ y 5.0) y]) 4)))))))
    (let [visible-frames (* (/ duration (double zoom)) fps)
          tick-step (max 1.0 (Math/ceil (/ visible-frames (max 1.0 (Math/floor (/ width 80.0))))))
          start-frame (* tick-step (Math/ceil (/ (* (double offset) fps) tick-step)))]
      (doseq [tick (range (inc (long (Math/ceil (/ visible-frames tick-step)))))]
        (let [frame (+ start-frame (* (double tick) tick-step))
              t (/ frame fps)
              x (time-x canvas state t)]
        (.setStroke gc (paint colors/dark-grey))
        (.strokeLine gc x 24.0 x height)
        (.setFill gc (paint colors/bright-grey-light))
        (.fillText gc (format "%.0f" frame) (- x 7.0) 17.0))))
    (let [x (time-x canvas state time)]
      (.setStroke gc (paint (if recording colors/defold-orange colors/defold-turquoise)))
      (.strokeLine gc x 23.0 x height))))

(defn- timeline-key [^Canvas canvas state x y]
  (let [{:keys [document animation-id collapsed]} state
        row (int (Math/floor (/ (- (double y) 32.0) 28.0)))
        {:keys [node-name property-id]} (get (timeline/rows (timeline/tracks document animation-id) collapsed) row)]
    (when property-id
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
                     :selected nil :last-lines nil :last-selection nil :exists false})
        updating (atom false)
        scene-content (first (.getChildrenUnmodifiable parent))
        root (BorderPane.)
        content (doto (VBox. 6.0) (.setPadding (Insets. 8.0)))
        title (doto (TitledPane.) (.setId "gui-animation-panel") (.setContent content) (.setExpanded false))
        status (doto (Label.) (.setId "gui-animation-status") (.setWrapText true))
        canvas (doto (Canvas. 700.0 100.0) (.setId "gui-animation-timeline") (.setFocusTraversable true)
                 (.setAccessibleText "Animation timeline. Select a key to edit its time and value."))
        canvas-pane (doto (Pane.) (.setMinWidth 0.0) (ui/children! [canvas]))
        track-pane (doto (VBox.) (.setMinWidth 270.0) (.setMaxWidth 270.0))
        track-fields (atom {})
        track-content (doto (HBox.) (ui/children! [track-pane canvas-pane]))
        scroll (doto (ScrollPane. track-content) (.setFitToWidth true) (.setPrefViewportHeight 180.0))
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
        loop-check (doto (CheckBox.) (.setId "gui-animation-loop"))
        mode-label (doto (Label.) (.setId "gui-animation-mode") (.setWrapText true))
        sample-label (Label.)
        zoom-slider (doto (Slider. 1.0 8.0 1.0) (.setId "gui-animation-zoom") (.setPrefWidth 100.0))
        time-scroll (doto (ScrollBar.) (.setId "gui-animation-scroll") (.setMin 0.0))
        play-button (doto (Button.) (.setId "gui-animation-play") (ui/add-style! "button-small"))
        drag (atom nil)
        localize! (fn [control suffix]
                    (localization/localize! control localization
                                           (localization/message (str "gui-animation." suffix))))
        guarded (fn [f]
                  (fn [_]
                    (try (f)
                         (catch Exception error
                           (.setText status (.getMessage error))))))
        button (fn [suffix id f]
                 (doto (Button.) (.setId id) (ui/add-style! "button-small") (localize! suffix)
                   (.setOnAction (ui/event-handler _ ((guarded f) nil)))))
        stop-preview! (fn []
                        (swap! state assoc :playing false :preview false :recording false :draft {})
                        (.setSelected preview-check false)
                        (.setSelected record-button false)
                        (g/transact {:undoable false}
                                    (into [] cat
                                          [(g/set-property view-id :animation-preview-overrides {})
                                           (g/set-property view-id :animation-preview-active false)])))
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
                                                               (merge-with merge (animation/evaluate document animation-id time) draft))
                                            {})]
                            (when (not= overrides (g/node-value view-id :animation-preview-overrides))
                              (g/transact {:undoable false}
                                          (g/set-property view-id :animation-preview-overrides overrides)))
                            (when (not= active (g/node-value view-id :animation-preview-active))
                              (g/transact {:undoable false}
                                          (g/set-property view-id :animation-preview-active active)))
                            (when (pos? (count issues))
                              (swap! state assoc :playing false)
                              (.setText status (coll/join-to-string " · " issues)))))
        set-time! (fn [time]
                    (let [{:keys [document animation-id offset zoom]} @state
                          duration (double (animation/animation-duration document animation-id))
                          time (max 0.0 (min duration (double time)))
                          span (/ duration (double zoom))
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
                                         (into-array OpenOption [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE]))
                      (workspace/resource-sync! workspace))
                    (refresh-document!)))
        filesbar (doto (FlowPane. 6.0 6.0)
                   (ui/children! [(button "create" "gui-animation-create" create!)
                                  (button "import" "gui-animation-import"
                                          #(let [chooser (doto (FileChooser.) (.setTitle "Import Panthera JSON"))]
                                             (.add (.getExtensionFilters chooser)
                                                   (FileChooser$ExtensionFilter. "Panthera JSON" (java.util.ArrayList. ["*.json"])))
                                             (when-let [file (.showOpenDialog chooser (.getWindow (.getScene root)))]
                                               (let [document (animation/read-json (slurp file :encoding "UTF-8"))]
                                                 (create!)
                                                 (commit! (constantly document))))))
                                  (button "save" "gui-animation-save"
                                          #(ui/execute-command (ui/contexts (.getScene root) true) :file.save-all nil))
                                  (button "export" "gui-animation-export"
                                          #(let [node-id (or (animation-resource-node project path)
                                                             (throw (ex-info "Create an animation file first." {})))
                                                  document (read-document node-id)
                                                  chooser (doto (FileChooser.)
                                                           (.setTitle "Export Panthera Lua")
                                                           (.setInitialDirectory (.getParentFile (io/file (workspace/project-directory workspace) (subs path 1))))
                                                           (.setInitialFileName (str (subs (.getName (io/file path)) 0 (- (count (.getName (io/file path))) 5)) ".lua")))]
                                             (.add (.getExtensionFilters chooser)
                                                   (FileChooser$ExtensionFilter. "Lua animation" (java.util.ArrayList. ["*.lua"])))
                                             (when-let [file (.showSaveDialog chooser (.getWindow (.getScene root)))]
                                               (spit file (animation/write-lua document) :encoding "UTF-8")
                                               (workspace/resource-sync! workspace))))]))
        settingsbar (doto (FlowPane. 6.0 6.0)
                  (ui/children! [name-field
                                 (button "add-clip" "gui-animation-add-clip"
                                         #(let [name (string/trim (.getText name-field))]
                                            (commit! (fn [document] (animation/add-animation document name 1.0)))
                                            (.setValue animation-choice name)))
                                 (doto (Label.) (localize! "duration")) duration-field]))
        settings (doto (TitledPane.) (.setId "gui-animation-settings") (.setExpanded false)
                   (localize! "settings") (.setContent (doto (VBox. 6.0) (ui/children! [filesbar settingsbar]))))
        welcome (doto (FlowPane. 8.0 6.0)
                  (ui/children! [(doto (Label.) (localize! "empty"))
                                 (button "create" "gui-animation-start" create!)]))
        toolbar (doto (FlowPane. 6.0 6.0)
                  (ui/children! [animation-choice preview-check record-button
                                 (button "first" "gui-animation-first" #(navigate! :first))
                                 (button "previous" "gui-animation-previous" #(navigate! :previous-key))
                                 play-button
                                 (button "next" "gui-animation-next" #(navigate! :next-key))
                                 (button "last" "gui-animation-last" #(navigate! :last))
                                 loop-check
                                 (doto (Label.) (localize! "frame") (.setLabelFor frame-field)) frame-field
                                 (doto (Label. "s") (.setLabelFor time-field)) time-field sample-label]))
        editbar (doto (FlowPane. 6.0 6.0)
                  (ui/children! [node-choice property-choice
                                 (button "add-property" "gui-animation-add-property"
                                         #(let [node-name (.getValue node-choice)
                                                property-id (.getValue property-choice)
                                                node-id (get (g/node-value resource-node :node-ids) node-name)
                                                animation-id (:animation-id @state)]
                                            (when-not node-id (throw (ex-info "Select a GUI node." {})))
                                            (when (zero? (count (animation/keyframes (:document @state) animation-id node-name property-id)))
                                              (commit! (fn [document]
                                                         (animation/set-keyframe document animation-id node-name property-id 0.0
                                                                                 (get (node-values node-id) property-id) "linear"))))
                                            (swap! state update :collapsed disj node-name)
                                            (select-track! node-name property-id)))
                                 (doto (Label.) (localize! "value") (.setLabelFor value-field)) value-field
                                 easing-choice
                                 (button "key" "gui-animation-add-key"
                                         #(record-value! (.getValue node-choice) (.getValue property-choice) (finite-number value-field)))
                                 (button "delete" "gui-animation-delete-key" delete-key!)]))
        viewbar (doto (HBox. 8.0) (.setAlignment Pos/CENTER_LEFT)
                  (ui/children! [(doto (Label.) (localize! "zoom")) zoom-slider
                                 (button "fit" "gui-animation-fit" #(.setValue zoom-slider 1.0)) time-scroll]))
        refresh-tracks! (fn []
                          (let [{:keys [document animation-id collapsed time draft]} @state
                                rows (timeline/rows (timeline/tracks document animation-id) collapsed)
                                context [animation-id rows]
                                values (merge-with merge (animation/evaluate document animation-id time) draft)]
                            (when (not= context (:rows-context @state))
                              (swap! state assoc :rows-context context)
                              (reset! track-fields {})
                              (ui/children! track-pane
                                            (into [(doto (Label.) (localize! "properties") (.setMinHeight 32.0) (.setPrefHeight 32.0))]
                                                  (map-indexed
                                                    (fn [index {:keys [node-name property-id]}]
                                                      (if-not property-id
                                                        (doto (Button. (str (if (contains? collapsed node-name) "▸  " "▾  ") node-name))
                                                          (.setMnemonicParsing false)
                                                          (.setMinHeight 28.0) (.setPrefHeight 28.0) (.setMaxHeight 28.0) (.setMaxWidth Double/MAX_VALUE)
                                                          (.setAlignment Pos/CENTER_LEFT)
                                                          (.setOnAction (ui/event-handler _
                                                                          (swap! state update :collapsed
                                                                                 #(if (contains? % node-name) (disj % node-name) (conj % node-name))))))
                                                        (let [field (doto (TextField.) (.setPrefWidth 85.0) (.setMinWidth 60.0)
                                                                      (.setId (str "gui-animation-track-value-" index))
                                                                      (.setAccessibleText (str node-name " " property-id)))
                                                              original-text (atom nil)
                                                              apply-value! (guarded #(when (not= @original-text (.getText field))
                                                                                      (edit-value! node-name property-id (finite-number field))
                                                                                      (reset! original-text (.getText field))))]
                                                          (swap! track-fields assoc [node-name property-id] field)
                                                          (.setOnAction field (ui/event-handler _ (apply-value! nil)))
                                                          (ui/observe (.focusedProperty field)
                                                                      (fn [_ _ focused]
                                                                        (if focused
                                                                          (do (reset! original-text (.getText field)) (select-track! node-name property-id))
                                                                          (apply-value! nil))))
                                                          (doto (HBox. 4.0) (.setAlignment Pos/CENTER_LEFT)
                                                            (.setMinHeight 28.0) (.setPrefHeight 28.0) (.setMaxHeight 28.0)
                                                            (ui/children! [(doto (Button. property-id) (.setPrefWidth 180.0)
                                                                             (.setMnemonicParsing false)
                                                                             (.setAlignment Pos/CENTER_LEFT)
                                                                             (.setOnAction (ui/event-handler _ (select-track! node-name property-id)))) field]))))))
                                                  rows)))
                            (doseq [[[node-name property-id] ^TextField field] @track-fields]
                              (.setDisable field (or (:playing @state)
                                                     (zero? (count (timeline/display-keyframes document animation-id node-name property-id)))))
                              (when-not (.isFocused field)
                                (when-let [value (get-in values [node-name property-id])]
                                  (.setText field (format "%.4f" (double value))))))))]
    (localize! title "title")
    (localize! preview-check "preview")
    (localize! record-button "record")
    (localize! loop-check "loop")
    (localize! play-button "play")
    (.setPromptText name-field "Animation name")
    (doseq [[^Node control text] [[animation-choice "Animation"] [node-choice "GUI node"]
                                 [property-choice "Animated property"] [easing-choice "Easing"]
                                 [time-field "Time in seconds"] [duration-field "Duration in seconds"]
                                 [name-field "New animation name"] [value-field "Keyframe value"]]]
      (.setAccessibleText control text))
    (.setTooltip preview-check (Tooltip. "Preview does not change the GUI layout. Turn off to edit the layout."))
    (.setTooltip record-button (Tooltip. "Record edits made in the animation panel. Scene gizmo recording is not yet supported."))
    (doseq [[id text] [["gui-animation-first" "First frame (Home)"]
                       ["gui-animation-previous" "Previous key (Alt+,). Previous frame: ,"]
                       ["gui-animation-next" "Next key (Alt+.). Next frame: ."]
                       ["gui-animation-last" "Last frame (End)"]]]
      (let [^Button control (.lookup toolbar (str "#" id))]
        (.setTooltip control (Tooltip. text))
        (.setAccessibleText control text)))
    (ui/children! parent [])
    (.setCenter root scene-content)
    (.setBottom root title)
    (ui/fill-control root)
    (ui/children! parent [root])
    (ui/children! content [welcome toolbar mode-label scroll viewbar editbar settings status])
    (HBox/setHgrow canvas-pane Priority/ALWAYS)
    (HBox/setHgrow time-scroll Priority/ALWAYS)
    (VBox/setVgrow scroll Priority/ALWAYS)
    (.bind (.widthProperty canvas) (.widthProperty canvas-pane))
    (.bind (.prefHeightProperty canvas-pane) (.heightProperty canvas))
    (localize! status "empty")
    (.setDisable content (resource/read-only? gui-resource))
    (ui/observe (.valueProperty animation-choice)
                (fn [_ _ value]
                  (when (and value (not @updating))
                    (swap! state assoc :animation-id value :time 0.0 :selected nil :playing false :draft {} :offset 0.0)
                    (.setText duration-field (str (animation/animation-duration (:document @state) value)))
                    (update-preview!))))
    (.setOnAction duration-field
                  (ui/event-handler _
                    ((guarded #(commit! (fn [document]
                                          (animation/set-duration document (:animation-id @state) (finite-number duration-field))))) nil)))
    (.setOnAction time-field (ui/event-handler _ ((guarded #(set-time! (finite-number time-field))) nil)))
    (.setOnAction frame-field (ui/event-handler _ ((guarded #(set-time! (/ (finite-number frame-field)
                                                                           (double (timeline/frames-per-second (:document @state)))))) nil)))
    (.setOnAction value-field (ui/event-handler _ ((guarded #(edit-value! (.getValue node-choice) (.getValue property-choice) (finite-number value-field))) nil)))
    (ui/observe (.valueProperty zoom-slider)
                (fn [_ _ value] (swap! state assoc :zoom (double value) :offset 0.0)))
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
                    ((guarded #(do (swap! state update :playing not)
                                   (when (:playing @state)
                                     (let [time (:time @state)
                                           duration (animation/animation-duration (:document @state) (:animation-id @state))]
                                       (set-time! (if (>= (double time) (double duration)) 0.0 time)))))) nil)))
    (.setOnMousePressed canvas
                        (ui/event-handler event
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
                                                   (get (timeline/rows (timeline/tracks (:document @state) (:animation-id @state)) (:collapsed @state))
                                                        (int (Math/floor (/ (- y 32.0) 28.0))))]
                                          (when property-id (select-track! node-name property-id)))))))) nil)
                          ;; Keep the enclosing ScrollPane from stealing keyboard focus.
                          (.consume ^MouseEvent event)))
    (.setOnMouseDragged canvas
                        (ui/event-handler event
                          (when-let [{:keys [node-name property-id keyframe]} @drag]
                            (swap! state assoc :dragging-key [node-name property-id (:time keyframe)]))
                          ((guarded #(set-time! (x-time canvas @state (.getX ^MouseEvent event)))) nil)))
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
                     (when (.isExpanded title)
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
                                 next-state (timeline/advance (:time @state) duration dt (.isSelected loop-check))]
                             (set-time! (:time next-state))
                             (swap! state assoc :playing (:playing next-state))))
                         (when (not= (:playing @state) (:play-label @state))
                           (swap! state assoc :play-label (:playing @state))
                           (localize! play-button (if (:playing @state) "pause" "play")))
                         (sync-fields!)
                         (refresh-tracks!)
                         (let [{:keys [document animation-id zoom offset recording preview draft exists]} @state
                               duration (double (animation/animation-duration document animation-id))
                               maximum (max 0.0 (- duration (/ duration (double zoom))))
                               mode (cond recording "recording" (pos? (count draft)) "draft" preview "previewing" :else "layout")]
                           (.setMax time-scroll maximum)
                           (.setVisibleAmount time-scroll (/ duration (double zoom)))
                           (.setValue time-scroll (min maximum (double offset)))
                           (.setDisable time-scroll (zero? maximum))
                           (.setText sample-label (format "%.0f fps" (double (timeline/frames-per-second document))))
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
      (ui/observe (.expandedProperty title)
                  (fn [_ _ expanded]
                    (when-not expanded
                      (stop-preview!))))
      {:refresh-fn refresh!
       :dispose-fn #(swap! state assoc :playing false :preview false)})))
