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

(ns editor.skeletal-animation-view
  "Native authoring workspace for skeletons, region/mesh attachments and animation."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [dynamo.graph :as g]
            [editor.dialogs :as dialogs]
            [editor.localization :as localization]
            [editor.resource :as resource]
            [editor.skeletal-animation-atlas :as atlas]
            [editor.skeletal-animation-attachments :as attachments]
            [editor.skeletal-animation-audio :as audio]
            [editor.skeletal-animation-auto-rig :as auto-rig]
            [editor.skeletal-animation-constraints :as constraints]
            [editor.skeletal-animation-curve-view :as curve-view]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-events :as events]
            [editor.skeletal-animation-geometry :as geometry]
            [editor.skeletal-animation-inherit :as inherit]
            [editor.skeletal-animation-graph-edit :as graph-edit]
            [editor.skeletal-animation-io :as animation-io]
            [editor.skeletal-animation-mesh :as mesh]
            [editor.skeletal-animation-mixing :as mixing]
            [editor.skeletal-animation-navigation :as navigation]
            [editor.skeletal-animation-physics :as physics]
            [editor.skeletal-animation-playback :as playback]
            [editor.skeletal-animation-preview-export :as preview-export]
            [editor.skeletal-animation-project :as project]
            [editor.skeletal-animation-properties :as properties]
            [editor.skeletal-animation-render :as render]
            [editor.skeletal-animation-rig-ui :as rig-ui]
            [editor.skeletal-animation-selection :as selection]
            [editor.skeletal-animation-skin :as skin]
            [editor.skeletal-animation-slot-view :as slot-view]
            [editor.skeletal-animation-slots :as slots]
            [editor.skeletal-animation-smart-mesh :as smart-mesh]
            [editor.skeletal-animation-texture-view :as texture-view]
            [editor.skeletal-animation-textures :as textures]
            [editor.skeletal-animation-timeline :as timeline]
            [editor.skeletal-animation-toolbar :as animation-toolbar]
            [editor.skeletal-animation-tools :as tools]
            [editor.skeletal-animation-trace-view :as trace-view]
            [editor.skeletal-animation-workspace-view :as workspace-view]
            [editor.ui :as ui]
            [editor.view :as view]
            [editor.workspace :as workspace]
            [util.coll :as coll])
  (:import [java.io File]
           [javafx.beans.binding Bindings]
           [javafx.beans.value ChangeListener]
           [javafx.collections FXCollections ListChangeListener]
           [javafx.embed.swing SwingFXUtils]
           [javafx.geometry Insets Orientation Pos]
           [javafx.scene Node]
           [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.control Button ButtonType CheckBox ComboBox CustomMenuItem Dialog Label ListView MenuButton MenuItem SelectionMode ScrollPane ScrollPane$ScrollBarPolicy Separator Slider SplitPane Tab TabPane TabPane$TabClosingPolicy TextField ToggleButton ToolBar Tooltip TreeItem TreeView]
           [javafx.scene.image Image]
           [javafx.scene.input KeyCode MouseButton MouseEvent ScrollEvent]
           [javafx.scene.layout AnchorPane BorderPane GridPane HBox Priority StackPane VBox]
           [javafx.scene.paint Color]
           [javafx.scene.shape SVGPath]
           [javafx.scene.text Font Text]
           [javafx.stage FileChooser FileChooser$ExtensionFilter]
           [javafx.util StringConverter]
           [javax.imageio ImageIO]))

(def ^:private transform-properties data/supported-properties)

(defn- tr [context key]
  ((:localization context) (localization/message (str "skeletal." key))))

(defn- identify! [^Node node id]
  (.setId node (str "skeletal-" id))
  node)

(defn- button [context id label action]
  (doto (identify! (Button. (tr context label)) id)
    (.setFocusTraversable false)
    (.setOnAction (ui/event-handler _ (action)))))

(defn- label [text]
  (Label. (str text)))

(defn- row [nodes]
  (let [box (HBox. 5.0)]
    (.setAlignment box Pos/CENTER_LEFT)
    (.addAll (.getChildren box) ^java.util.Collection nodes)
    box))

(defn- tool-strip [nodes]
  (doto (ToolBar. (into-array Node nodes))
    (.setMinWidth 0.0)))

(defn- column [nodes]
  (let [box (VBox. 6.0)]
    (.addAll (.getChildren box) ^java.util.Collection nodes)
    box))

(defn- responsive-tool-host [viewport overlay palette]
  (let [host (doto (BorderPane.) (.setId "skeletal-viewport-host") (.setMinSize 120.0 100.0))
        dock (doto (ScrollPane.) (.setId "skeletal-tool-dock")
                   (.setFitToHeight true) (.setFitToWidth false) (.setPannable false)
                   (.setVbarPolicy ScrollPane$ScrollBarPolicy/NEVER)
                   (.setHbarPolicy ScrollPane$ScrollBarPolicy/AS_NEEDED)
                   (.setMinHeight 0.0) (.setMaxHeight javafx.scene.layout.Region/USE_PREF_SIZE))
        compact (atom nil)
        refresh (fn refresh-tool-layout []
                  (let [needed (+ 24.0 (.prefWidth ^javafx.scene.layout.Region overlay -1.0))
                        narrow (or (< (.getWidth host) needed) (< (.getHeight host) 420.0))]
                    (when (not= narrow @compact)
                      (reset! compact narrow)
                      (if narrow
                        (do (.remove (.getChildren ^StackPane viewport) overlay)
                            (.setContent dock overlay) (.setBottom host dock))
                        (do (.setContent dock nil) (.setBottom host nil)
                            (.add (.getChildren ^StackPane viewport) overlay))))
                    (.setPrefHeight dock (+ 22.0 (.prefHeight ^javafx.scene.layout.Region overlay -1.0)))))
        listener (reify ChangeListener (changed [_ _ _ _] (refresh)))]
    (.setCenter host viewport)
    (.setMinWidth ^javafx.scene.layout.Region overlay javafx.scene.layout.Region/USE_PREF_SIZE)
    (.setMaxWidth ^javafx.scene.layout.Region overlay javafx.scene.layout.Region/USE_PREF_SIZE)
    (.setMaxHeight ^javafx.scene.layout.Region palette javafx.scene.layout.Region/USE_PREF_SIZE)
    (.addListener (.widthProperty host) listener)
    (.addListener (.heightProperty host) listener)
    (.addListener (.layoutBoundsProperty ^Node overlay) listener)
    (ui/user-data! host ::refresh-tool-layout refresh)
    host))

(defn- authoring-icon [kind]
  (let [path (get {:bone "M3,2 L6,3 L12,9 L13,12 L10,13 L4,7 Z M3,2 L2,5 L4,7 M10,13 L13,12"
                   :slot "M8,2 A6,6 0 1 0 8,14 A6,6 0 1 0 8,2 M8,5 A3,3 0 1 0 8,11 A3,3 0 1 0 8,5"
                   :region "M2,3 L14,3 L14,13 L2,13 Z M3,11 L7,6 L10,10 L12,8 L14,11"
                   :linkedmesh "M2,3 L12,3 L12,13 L2,13 Z M2,3 L12,13 M12,3 L2,13 M10,1 L15,1 L15,6"
                   :point "M8,1 L8,15 M1,8 L15,8 M8,4 A4,4 0 1 0 8,12 A4,4 0 1 0 8,4"
                   :boundingbox "M2,3 L12,1 L15,12 L5,15 Z"
                   :clipping "M1,4 L11,1 L15,10 L6,15 Z M1,4 L15,10"
                   :ik "M2,13 L7,5 L13,9 M4,1 L9,1 L9,6 M11,11 L15,11 L15,15 L11,15 Z"
                   :event "M3,2 L13,2 L13,12 L8,12 L5,15 L5,12 L3,12 Z"
                   :mesh "M2,3 L13,2 L14,13 L3,14 Z M2,3 L8,8 L13,2 M3,14 L8,8 L14,13 M2,3 L3,14 M8,8 L14,13"
                   :move "M8,1 L8,15 M1,8 L15,8 M5,4 L8,1 L11,4 M5,12 L8,15 L11,12 M4,5 L1,8 L4,11 M12,5 L15,8 L12,11"
                   :rotate "M13,5 A6,6 0 1 0 14,10 M13,1 L13,5 L9,5"
                   :scale "M2,10 L2,14 L6,14 M2,14 L14,2 M10,2 L14,2 L14,6"
                   :shear "M5,3 L14,3 L11,13 L2,13 Z"
                   :weights "M3,4 L8,1 L13,4 L13,12 L8,15 L3,12 Z M3,4 L13,12 M13,4 L3,12"
                   :path "M1,13 C3,1 12,15 15,2 M1,11 L3,11 L3,13 L1,13 Z M13,2 L15,2 L15,4 L13,4 Z"
                   :pose "M8,1 L8,3 M3,5 L13,5 M8,4 L8,10 M8,10 L3,15 M8,10 L13,15"
                   :create "M8,2 L8,14 M2,8 L14,8"
                   :local "M3,4 L8,1 L13,4 L13,12 L8,15 L3,12 Z M3,4 L8,8 L13,4 M8,8 L8,15"
                   :parent "M3,2 L7,2 L7,6 L3,6 Z M5,6 L5,11 L10,11 M10,9 L14,9 L14,13 L10,13 Z"
                   :world "M8,1 A7,7 0 1 0 8,15 A7,7 0 1 0 8,1 M1,8 L15,8 M8,1 C3,5 3,11 8,15 C13,11 13,5 8,1"
                   :key "M8,2 L14,8 L8,14 L2,8 Z"
                   :animation "M4,2 L13,8 L4,14 Z"
                   :view "M1,8 Q8,-1 15,8 Q8,17 1,8 M10,8 A2,2 0 1 0 6,8 A2,2 0 1 0 10,8"
                   :texture "M2,2 L14,2 L14,14 L2,14 Z M3,11 L6,7 L9,10 L11,6 L13,11 M4,4 L6,4"
                   :root "M2,3 L6,3 L8,5 L14,5 L14,13 L2,13 Z"}
                  kind "M3,3 L13,3 L13,13 L3,13 Z")]
    (doto (SVGPath.)
      (.setContent path)
      (.setMouseTransparent true)
      (ui/add-style! "skeletal-icon")
      (ui/add-style! (str "skeletal-icon-" (name kind))))))

(defn- settings-menu-item [content width]
  (ui/add-style! content "skeletal-settings-content")
  (let [scroll (doto (ScrollPane. content)
                 (.setFitToWidth true)
                 (.setHbarPolicy ScrollPane$ScrollBarPolicy/NEVER)
                 (.setPrefWidth width)
                 (.setPrefViewportHeight 420.0)
                 (.setMaxHeight 460.0))]
    (ui/add-style! scroll "skeletal-settings-scroll")
    (doto (CustomMenuItem. scroll false)
      (-> .getStyleClass (.add "skeletal-settings-item")))))

(defn- choice [id values selected action]
  (let [box (identify! (ComboBox. (FXCollections/observableArrayList ^java.util.Collection (vec values))) id)]
    (properties/fit-choice-text! box)
    (.setValue box selected)
    (.setOnAction box (ui/event-handler _ (action (.getValue box))))
    box))

(defn- number-field [id value action]
  (let [field (identify! (TextField. (format "%.3f" (double value))) id)
        last-text (atom (.getText field))
        gesture (atom nil)
        commit (fn commit-number []
                 (try
                   (let [n (tools/numeric-value (.getText field) (Double/parseDouble @last-text))]
                     (if (Double/isFinite n)
                       (do (.setStyle field "")
                           (when (not= @last-text (.getText field))
                             (action n)
                             (.setText field (format "%.3f" n))
                             (reset! last-text (.getText field))))
                       (.setStyle field "-fx-border-color: #d85b62;")))
                   (catch NumberFormatException _
                     (.setStyle field "-fx-border-color: #d85b62;"))))]
    (ui/user-data! field ::last-number-text last-text)
    (.setPrefColumnCount field 6)
    (.setOnAction field (ui/event-handler _ (commit)))
    (let [cancel (fn cancel-number-drag []
                   (when-let [drag @gesture]
                     (when (:active drag)
                       (when-let [f (:cancel (ui/user-data field ::number-preview))] (f))
                       (.setText field (:text drag)))
                     (reset! gesture nil)))]
      (.addEventFilter field MouseEvent/MOUSE_PRESSED
        (ui/event-handler event
          (when (= MouseButton/PRIMARY (.getButton ^MouseEvent event))
            (try
              (reset! gesture {:start-y (.getSceneY ^MouseEvent event) :last-y (.getSceneY ^MouseEvent event)
                               :value (tools/numeric-value (.getText field) (Double/parseDouble @last-text))
                               :text (.getText field) :active false})
              (catch NumberFormatException _ (reset! gesture nil))))))
      (.addEventFilter field MouseEvent/MOUSE_DRAGGED
        (ui/event-handler event
          (when-let [drag @gesture]
            (let [y (.getSceneY ^MouseEvent event)
                  active (or (:active drag) (> (abs (- y (:start-y drag))) 3.0))]
              (when active
                (when-not (:active drag)
                  (when-let [f (:begin (ui/user-data field ::number-preview))] (f)))
                (let [drag (if (:active drag) drag
                               (assoc drag :text (.getText field)
                                      :value (tools/numeric-value (.getText field) (Double/parseDouble @last-text))))
                      step (if (.isShiftDown ^MouseEvent event) 0.1 1.0)
                      value (+ (:value drag) (* step (- (:last-y drag) y)))]
                  (when (Double/isFinite value)
                    (reset! gesture (assoc drag :last-y y :active true :value value))
                    (.setText field (format "%.3f" value))
                    (when-let [f (:preview (ui/user-data field ::number-preview))] (f value))))
                (.consume ^MouseEvent event))))))
      (.addEventFilter field MouseEvent/MOUSE_RELEASED
        (ui/event-handler event
          (when (:active @gesture)
            (if-let [f (:finish (ui/user-data field ::number-preview))]
              (do (f) (reset! last-text (.getText field)))
              (commit))
            (reset! gesture nil)
            (when-let [^Node target (ui/user-data field ::number-focus-target)]
              (.requestFocus target))
            (.consume ^MouseEvent event))
          (reset! gesture nil)))
      (.addEventFilter field javafx.scene.input.KeyEvent/KEY_PRESSED
        (ui/event-handler event
          (when (and (:active @gesture) (= KeyCode/ESCAPE (.getCode ^javafx.scene.input.KeyEvent event)))
            (cancel) (.consume ^javafx.scene.input.KeyEvent event))))
      (ui/user-data! field ::cancel-number-drag cancel))
    (.setOnScroll field
                  (ui/event-handler event
                    (when (= field (some-> field .getScene .getFocusOwner))
                      (try
                        (let [step (if (.isShiftDown ^ScrollEvent event) 0.1 1.0)
                              value (tools/numeric-value (.getText field) (Double/parseDouble @last-text))]
                          (.setText field (str (+ value (* step (Math/signum (.getDeltaY ^ScrollEvent event))))))
                          (commit))
                        (catch NumberFormatException _ (.setStyle field "-fx-border-color: #d85b62;")))
                      (.consume ^ScrollEvent event))))
    (.addListener (.focusedProperty field)
                  (reify ChangeListener
                    (changed [_ _ before after]
                      (when (and before (not after))
                        (if (:active @gesture)
                          ((ui/user-data field ::cancel-number-drag))
                          (commit))))))
    field))

(defn- refresh-number-field! [^TextField field value]
  (let [text (str value)]
    (.setText field text)
    (reset! (ui/user-data field ::last-number-text) text)))

(defn- text-field [id value action]
  (doto (identify! (TextField. (str value)) id)
    (.setOnAction (ui/event-handler event (action (.getText ^TextField (.getSource event)))))))

(defn- message! [context text]
  (.setText ^Label (:status context) (str text)))

(defn- attempt! [context f]
  (try
    (let [result (f)]
      (message! context "")
      result)
    (catch Exception e
      (message! context (.getMessage e))
      nil)))

(defn- document [context]
  (or (:draft @(:state context))
      (tools/pending-document @(:state context) (g/node-value (:resource-node context) :document))
      (g/node-value (:resource-node context) :document)))

(defn- require-editable-preview! [context]
  (let [state @(:state context)]
    (when (and (= "animate" (:mode state)) (or (:mixer-enabled state) (:mix-enabled state) (:mix-transition state)))
      (throw (ex-info (tr context "mix-readonly") {})))))

(defn- commit! [context doc]
  (when-not (= doc (g/node-value (:resource-node context) :document))
    (require-editable-preview! context)
    (data/validate-document doc)
    (g/transact (concat (g/operation-label (localization/message "skeletal.edit"))
                        (when-let [sequence (:operation-sequence context)] (g/operation-sequence sequence))
                        (g/set-property (:resource-node context) :document doc))))
  (swap! (:state context) dissoc :draft :pending-pose :pose-undo :pose-redo :discarded-pose))

(defn- entity [entities id]
  (coll/first-where #(= id (:id %)) entities))

(defn- unique-id [entities prefix]
  (let [ids (into #{} (map :id) entities)]
    (loop [i 1]
      (let [id (str prefix i)]
        (if (contains? ids id) (recur (inc i)) id)))))

(defn- animation [context doc]
  (or (entity (:animations doc) (:animation @(:state context))) (first (:animations doc))))

(defn- selected-bone [context doc]
  (let [{:keys [selected-kind selected]} @(:state context)]
    (case selected-kind
      :bone (entity (:bones doc) selected)
      :slot (entity (:bones doc) (:bone (entity (:slots doc) selected)))
      :ik (entity (:bones doc) (:target (entity (:ik doc) selected)))
      :transform (entity (:bones doc) (:target (entity (:transform doc) selected)))
      :path (entity (:bones doc) (first (:bones (entity (:path doc) selected))))
      :physics (entity (:bones doc) (:bone (entity (:physics doc) selected)))
      nil)))

(declare sampled-slots)

(defn- selected-slot [context doc]
  (when (= :slot (:selected-kind @(:state context)))
    (entity (:slots (if (= "animate" (:mode @(:state context))) (sampled-slots context doc) doc))
            (:selected @(:state context)))))

(defn- mixed-preview [context doc]
  (let [state @(:state context)]
    (when (and (= "animate" (:mode state)) (:mixer-enabled state)
               (= doc (get-in state [:mixer-state :document])))
      (:mixer-sample state))))

(defn- preview-value [context kind source key produce]
  (if-let [cache (:preview-cache context)]
    (if-let [entry (coll/first-where #(and (identical? source (:source %)) (= key (:key %))) (get @cache kind))]
      (:value entry)
      (let [value (produce)]
        ;; Keep only the current draft and its gesture baseline per category.
        (swap! cache update kind #(coll/into-> % [{:source source :key key :value value}] (take 1)))
        value))
    (produce)))

(defn- sampled-slots [context doc]
  (or (:sampled-slots context)
      (mixed-preview context doc)
      (let [state @(:state context)
            animation-id (when (= "animate" (:mode state)) (:id (animation context doc)))
            time (if animation-id (:time state) 0.0)]
        (preview-value context :slots doc [animation-id time]
                       (fn sample-preview-slots [] (slots/sample-slots doc animation-id time))))))

(defn- editable-preview-attachment [context doc]
  (or (not= "animate" (:mode @(:state context)))
      (:preview-deform-editable (entity (:slots (sampled-slots context doc)) (:selected @(:state context))))))

(defn- require-current-inspector! [context doc]
  (when-let [selection (:inspector-selection context)]
    (when-not (= selection (select-keys @(:state context) [:mode :animation :selected-kind :selected]))
      (throw (ex-info "The inspector selection has changed." {}))))
  (when-let [key (:attachment-edit-key context)]
    (when-not (= key (:preview-attachment-key (selected-slot context doc)))
      (throw (ex-info (tr context "preview-attachment-readonly") {})))))

(defn- change! [context f]
  (attempt! context
            #(let [_ (when (and (:setup-only context) (not= "setup" (:mode @(:state context))))
                       (throw (ex-info (tr context "setup-edit-only") {})))
                   doc (g/node-value (:resource-node context) :document)]
               (require-current-inspector! context doc)
               ;; A field can lose focus after playback switches the attachment.
               ;; Recheck at commit time, including controls from the previous inspector.
               (when-let [slot-id (:attachment-edit-slot context)]
                 (when-not (and (= :slot (:selected-kind @(:state context)))
                                (= slot-id (:selected @(:state context)))
                                (editable-preview-attachment context doc))
                   (throw (ex-info (tr context "preview-attachment-readonly") {}))))
               (let [pending (tools/pending-document @(:state context) doc)
                     changed (f doc)]
                 (commit! context changed)
                 (when (and (:preserve-pending context) pending (not= pending changed))
                   (swap! (:state context) tools/stage-pose changed pending))
                 changed))))

(defn- setup-change! [context f]
  (change! (assoc context :setup-only true) f))

(defn- commit-pose! [context doc]
  (if (and (= "animate" (:mode @(:state context))) (false? (:auto-key @(:state context))))
    (do
      (data/validate-document doc)
      (swap! (:state context) tools/stage-pose (g/node-value (:resource-node context) :document) doc))
    (commit! context doc)))

(defn- pose-change! [context f]
  (attempt! context #(let [doc (document context)]
                       (require-current-inspector! context doc)
                       (require-editable-preview! context)
                       (when (and (:attachment-edit-slot context) (not (editable-preview-attachment context doc)))
                         (throw (ex-info (tr context "preview-attachment-readonly") {})))
                       (commit-pose! context (f doc)))))

(defn- animation-key-change! [context f]
  (attempt! context
            (fn commit-animation-key []
              (require-current-inspector! context (document context))
              (require-editable-preview! context)
              (when-not (= "animate" (:mode @(:state context)))
                (throw (ex-info "Animation keys require animation mode." {})))
              (commit! context (f (document context))))))

(defn- evaluated-pose [context doc]
  (let [{:keys [mode time]} @(:state context)
        animation-id (when (= "animate" mode) (:id (animation context doc)))
        time (if animation-id time 0.0)]
    (or (:evaluated-pose context)
        (:pose (mixed-preview context doc))
        (preview-value context :pose doc [animation-id time]
                       (fn evaluate-preview-pose []
                         (if-let [cache (:pose-cache context)]
                           (let [evaluated (data/evaluate-pose-with-slots-state doc animation-id time @cache slots/sample-slots)]
                             (reset! cache (:state evaluated))
                             (:pose evaluated))
                           (data/evaluate-pose-with-slots doc animation-id time slots/sample-slots)))))))

(defn- pose [context doc]
  (:bones (evaluated-pose context doc)))

(defn- point [[a b c d tx ty] [x y]]
  [(+ (* a x) (* c y) tx) (+ (* b x) (* d y) ty)])

(defn- inverse-point [[a b c d tx ty] [x y]]
  (let [det (- (* a d) (* b c))]
    (when (> (Math/abs (double det)) 1.0e-10)
      (let [x (- x tx) y (- y ty)]
        [(/ (- (* d x) (* c y)) det) (/ (- (* a y) (* b x)) det)]))))

(defn- screen [context [x y]]
  (let [{:keys [zoom pan-x pan-y]} @(:state context)]
    [(+ pan-x (* zoom x)) (- pan-y (* zoom y))]))

(defn- world [context x y]
  (let [{:keys [zoom pan-x pan-y]} @(:state context)]
    [(/ (- x pan-x) zoom) (/ (- pan-y y) zoom)]))

(defn- attachment-points [context doc slot transforms]
  (let [attachment (:attachment slot)
        matrix (get transforms (:bone slot) [1.0 0.0 0.0 1.0 0.0 0.0])]
    (cond
      (or (nil? attachment) (not (#{"region" "mesh" "path" "boundingbox" "clipping" "point"} (get attachment :type "region")))) []
      (#{"mesh" "path" "boundingbox" "clipping"} (:type attachment))
      (let [deform (when (= "animate" (:mode @(:state context)))
                     (get (if (contains? context :sampled-deforms)
                            (:sampled-deforms context)
                            (:deforms (sampled-slots context doc)))
                          (:id slot)))]
        (preview-value context :mesh-points attachment [matrix transforms deform]
                       (fn mesh-world-points []
                         (geometry/mesh-world-vertices attachment matrix transforms deform))))
      (= "point" (:type attachment))
      (coll/into-> (:points (geometry/attachment-world-geometry attachment transforms (:bone slot) nil)) [] cat)
      :else
      (let [r (Math/toRadians (double (get attachment :rotation 0)))
            a (* (Math/cos r) (double (get attachment :scale-x 1)))
            b (* (Math/sin r) (double (get attachment :scale-x 1)))
            c (* (- (Math/sin r)) (double (get attachment :scale-y 1)))
            d (* (Math/cos r) (double (get attachment :scale-y 1)))
            local [a b c d (get attachment :x 0) (get attachment :y 0)]
            w (/ (double (:width attachment)) 2.0)
            h (/ (double (:height attachment)) 2.0)]
        (into [] cat (mapv #(point matrix (point local %)) [[(- w) h] [w h] [w (- h)] [(- w) (- h)]]))))))

(defn- image! [context path]
  (when-not (string/blank? path)
    (let [root (.getCanonicalFile (io/file (:project-root context)))
          file (.getCanonicalFile (io/file root (string/replace path #"^/" "")))]
      (when (.startsWith (.toPath file) (.toPath root))
        (let [factor (double (get context :texture-scale 1.0))
              key [(.getPath file) (.lastModified file) (.length file) factor]]
          (if (contains? @(:images context) key)
            (get @(:images context) key)
            (let [image (when (.isFile file)
                          (if (= 1.0 factor)
                            (Image. (str (.toURI file)) false)
                            (try
                              (when-let [source (ImageIO/read file)]
                                (SwingFXUtils/toFXImage (textures/resize-image source factor) nil))
                              (catch java.io.IOException _ nil))))
                  image (when (and image (not (.isError image))) image)]
              (swap! (:images context) (fn replace-cached-image [images]
                                         (assoc (coll/into-> images {} (remove (fn [[old-key _]] (= (first old-key) (first key))))) key image)))
              image)))))))

(defn- render-slots [context doc sampled evaluated]
  (let [context (assoc context :sampled-slots sampled :sampled-deforms (:deforms sampled))
        transforms (:transforms evaluated)
        active-bones (get evaluated :active-bone-ids (set (mapv :id (:bones evaluated))))]
    (mapv (fn [slot]
            (let [attachment (:attachment slot)
                  type (get attachment :type "region")
                  topology (when (#{"region" "mesh"} type)
                             (preview-value context :render-topology attachment nil
                                            (fn render-topology []
                                              {:uvs (vec (partitionv 2 (if (= "mesh" type) (:uvs attachment) [0.0 0.0 1.0 0.0 1.0 1.0 0.0 1.0])))
                                               :triangles (vec (partitionv 3 (if (= "mesh" type) (:triangles attachment) [0 1 2 0 2 3])))})))]
              (cond
                (or (not (contains? active-bones (:bone slot))) (nil? attachment))
                {:id (:id slot) :marker true}

                (= "clipping" type)
                (let [geometry (geometry/attachment-world-geometry attachment transforms (:bone slot) (get (:deforms sampled) (:id slot)))]
                  {:id (:id slot) :clip-polygon (mapv #(screen context %) (:points geometry)) :clip-end (:end geometry)})

                (#{"region" "mesh"} type)
                {:id (:id slot)
                 :points (mapv #(screen context %) (partitionv 2 (attachment-points context doc slot transforms)))
                 :uvs (:uvs topology)
                 :triangles (:triangles topology)
                 :image (image! (assoc context :texture-scale (textures/scale doc)) (:path attachment))
                 :tint (mapv * (subvec (get attachment :color [1.0 1.0 1.0 1.0]) 0 3)
                             (subvec (get slot :color [1.0 1.0 1.0 1.0]) 0 3))
                 :dark-tint (get slot :dark-color [0.0 0.0 0.0])
                 :blend (get slot :blend "normal")
                 :opacity (* (double (get (:color attachment) 3 1)) (double (get (:color slot) 3 1)))}

                :else {:id (:id slot) :marker true}))) (:slots sampled))))

(defn- ghost-pass [rendered pass tint]
  (let [positions (coll/into-> (range (count rendered)) {}
                    (map (fn [index] [(:id (nth rendered index)) index])))
        terminal [pass ::end]]
    (conj (mapv (fn [index slot]
                  (let [end-position (get positions (:clip-end slot))
                        end (if (and end-position (> end-position index)
                                     (not (:clip-polygon (nth rendered end-position))))
                              [pass (:clip-end slot)] terminal)]
                    (cond-> (update slot :id #(vector pass %))
                      (:clip-polygon slot) (assoc :clip-end end)
                      (:points slot) (assoc :tint tint :dark-tint [0.0 0.0 0.0]
                                            :opacity (* 0.2 (:opacity slot 1.0))))))
                (range (count rendered)) rendered)
          {:id terminal :marker true})))

(defn- ghost-slots [context doc]
  (let [{:keys [ghosting playing mode animation time fps] :as state} @(:state context)
        duration (:duration (entity (:animations doc) animation))]
    ;; Sample ghosts only while editing, so physics replay does not
    ;; multiply playback cost. Cache until the pose or camera changes.
    (when (and ghosting (not playing) (= "animate" mode) duration)
      (let [token [doc animation time fps (select-keys state [:zoom :pan-x :pan-y])]
            cache (:ghost-cache context)]
        (if (= token (:token @cache))
          (:slots @cache)
          (let [slots (coll/into-> [[:before -3.0 [0.4 0.7 1.0]] [:after 3.0 [1.0 0.5 0.3]]] []
                        (mapcat (fn [[pass offset tint]]
                                  (let [time (max 0.0 (min duration (+ time (/ offset fps))))
                                        sampled (slots/sample-slots doc animation time)
                                        evaluated (data/evaluate-pose-with-slots doc animation time slots/sample-slots)
                                        context (assoc context :state (atom (assoc state :time time)))]
                                    (ghost-pass (render-slots context doc sampled evaluated) pass tint)))))]
            (reset! cache {:token token :slots slots})
            slots))))))

(defn- workspace-preview [context doc]
  (let [state @(:state context)
        camera (assoc (select-keys state [:zoom :pan-x :pan-y])
                 :enabled (true? (:show-workspace-peers state))
                 :width (.getWidth ^Canvas (:canvas context))
                 :height (.getHeight ^Canvas (:canvas context)))
        token [doc camera]
        cache (:workspace-cache context)]
    (if (= token (:token @cache))
      (:preview @cache)
      (let [preview (workspace-view/preview doc (:name doc) camera
                                            (fn render-setup [document sampled evaluated camera]
                                              (render-slots (assoc context :state (atom (assoc camera :mode "setup" :time 0.0)))
                                                            document sampled evaluated)))]
        (reset! cache {:token token :preview preview})
        preview))))

(defn- mesh-manipulator [context doc]
  (let [state @(:state context)
        slot (selected-slot context doc)]
    (when (and (= "mesh" (get-in slot [:attachment :type]))
               (editable-preview-attachment context doc)
               (#{"move" "rotate" "scale" "shear"} (:tool state)))
      (let [transforms (:transforms (evaluated-pose context doc))
            deforms (when (= "animate" (:mode state))
                      (if (contains? context :sampled-deforms)
                        (:sampled-deforms context)
                        (:deforms (sampled-slots context doc))))]
        (preview-value context :mesh-manipulator doc
                       [slot transforms (get deforms (:id slot)) (select-keys state [:vertices :selected :mode :animation :time :tool :coordinate-space :mesh-pivot])]
                       (fn mesh-frame []
                         (let [points (vec (partitionv 2 (attachment-points context doc slot transforms)))
                               selection (or (coll/not-empty (:vertices state)) (set (range (count points))))
                               center-indices (if (coll/not-empty (:vertices state)) selection
                                                  (let [triangles (get-in slot [:attachment :triangles])]
                                                    (preview-value context :mesh-boundary triangles nil
                                                                   (fn mesh-boundary []
                                                                     (let [edges (coll/into-> (partitionv 3 triangles) []
                                                                                   (mapcat (fn [[a b c]] [(vec (sort [a b])) (vec (sort [b c])) (vec (sort [c a]))])))]
                                                                       (coll/into-> (frequencies edges) #{} (comp (filter #(= 1 (val %))) (mapcat key))))))))
                               center-indices (or (coll/not-empty center-indices) selection)
                               center (mapv #(/ % (count center-indices)) (reduce (fn [sum index] (mapv + sum (points index))) [0.0 0.0] center-indices))
                               token [(:selected state) (:preview-attachment-key slot) (:mode state) (:animation state) (:time state) selection]
                               bone (entity (:bones doc) (:bone slot))]
                           {:points points :selection selection :center center :token token
                            :pivot (if (= token (get-in state [:mesh-pivot :token])) (get-in state [:mesh-pivot :point]) center)
                            :axes (tools/axes bone transforms (:coordinate-space state "parent") (:tool state))})))))))

(defn- mesh-transform-matrix [frame tool start target axis snap]
  (let [[px py] (:pivot frame)
        [[ax ay] [bx by]] (:axes frame)
        basis [ax ay bx by px py]
        inverse (geometry/inverse-transform basis)
        [sx sy] (point inverse start)
        [tx ty] (point inverse target)
        transform (case tool
                    "move" [1.0 0.0 0.0 1.0 (if (= axis 1) 0.0 (- tx sx)) (if (= axis 0) 0.0 (- ty sy))]
                    "rotate" (let [angle (- (Math/atan2 ty tx) (Math/atan2 sy sx))
                                   angle (if snap (Math/toRadians (* 15.0 (Math/round (/ (Math/toDegrees angle) 15.0)))) angle)
                                   c (Math/cos angle) s (Math/sin angle)]
                               [c s (- s) c 0.0 0.0])
                    "scale" (let [ratio (/ (Math/hypot tx ty) (max 1.0e-8 (Math/hypot sx sy)))]
                              [(case axis 0 (if (> (abs sx) 1.0e-8) (/ tx sx) 1.0) 1 1.0 ratio)
                               0.0 0.0 (case axis 1 (if (> (abs sy) 1.0e-8) (/ ty sy) 1.0) 0 1.0 ratio) 0.0 0.0])
                    "shear" (if (= axis 0)
                              [1.0 (/ (- ty sy) (max 1.0 (abs sx))) 0.0 1.0 0.0 0.0]
                              [1.0 0.0 (/ (- tx sx) (max 1.0 (abs sy))) 1.0 0.0 0.0]))]
    (geometry/compose-transforms basis (geometry/compose-transforms transform inverse))))

(defn- rig-links [context doc]
  (preview-value context :rig-links doc nil
                 (fn collect-rig-links []
                   (into (mapv #(assoc (select-keys % [:id :bones]) :kind :ik :controls [(:target %)]) (:ik doc))
                         (mapv (fn [constraint]
                                 (let [slot (entity (:slots doc) (:target constraint))
                                       attachment (:attachment slot)
                                       controls (if (pos? (count (:weights attachment)))
                                                  (coll/into-> (:weights attachment) #{}
                                                    cat (filter #(pos? (:weight %))) (map :bone))
                                                  (if slot #{(:bone slot)} #{}))]
                                   (assoc (select-keys constraint [:id :bones :target]) :kind :path
                                          :controls (rig-ui/ordered-controls doc (:id constraint) controls)))) (:path doc))))))

(defn- control-at [context doc transforms x y]
  (when-not (false? (:show-bones @(:state context)))
    (first (first
             (sort-by second
                      (coll/into-> (rig-links context doc) []
                        (mapcat :controls) (distinct)
                        (filter (get (evaluated-pose context doc) :active-bone-ids (set (mapv :id (:bones doc)))))
                        (keep (fn [id]
                                (when-let [matrix (get transforms id)]
                                  (let [[ax ay] (screen context (point matrix [0.0 0.0]))
                                        distance (Math/hypot (- x ax) (- y ay))]
                                    (when (< distance 12.0)
                                      [id [distance (if (= id (:selected @(:state context))) 0 1) id]])))))))))))

(defn- bone-at [context doc bones transforms x y]
  ;; Visible controller handles win over ordinary shafts inside their fixed
  ;; screen-space radius, even when the shaft is geometrically closer.
  (or (control-at context doc transforms x y)
      (first (first (sort-by second
                             (coll/into-> bones []
                               (keep (fn [bone]
                                       (let [[ax ay] (screen context (point (get transforms (:id bone)) [0.0 0.0]))
                                             [bx by] (screen context (point (get transforms (:id bone)) [(:length bone) 0.0]))
                                             dx (- bx ax) dy (- by ay) size (+ (* dx dx) (* dy dy))
                                             t (if (< size 1.0e-8) 0.0 (max 0.0 (min 1.0 (/ (+ (* (- x ax) dx) (* (- y ay) dy)) size))))
                                             distance (Math/hypot (- x (+ ax (* t dx))) (- y (+ ay (* t dy))))]
                                         (when (< distance 8.0)
                                           [(:id bone) [distance (Math/hypot (- x ax) (- y ay)) (if (= (:id bone) (:selected @(:state context))) 0 1)]]))))))))))

(defn- weight-color [bone]
  (Color/hsb (double (mod (hash bone) 360)) 0.65 0.95))

(defn- draw-bone! [^GraphicsContext gc [x y] [ex ey] active hovered tint]
  (let [dx (- ex x) dy (- ey y) length (Math/hypot dx dy)
        color (or tint (Color/web (cond active "#78e8ed" hovered "#efffff" :else "#aeb7b9")))
        radius (if active 4.5 3.5)]
    (.setLineWidth gc 1.25)
    (when (> length 5.0)
      (let [ux (/ dx length) uy (/ dy length)
            half-width (min (if active 7.0 5.5) (max 3.0 (* length 0.16)))
            shoulder (min (* length 0.28) 15.0)
            bx (+ x (* ux shoulder)) by (+ y (* uy shoulder))
            xs (double-array [x (- bx (* uy half-width)) ex (+ bx (* uy half-width))])
            ys (double-array [y (+ by (* ux half-width)) ey (- by (* ux half-width))])]
        (.setFill gc (if (or active hovered tint) color (Color/web "#929b9dcc")))
        (.setStroke gc (Color/web "#303638"))
        (.fillPolygon gc xs ys 4)
        (.strokePolygon gc xs ys 4)
        (when (or active hovered)
          (.setStroke gc color)
          (.strokePolygon gc xs ys 4))))
    ;; A screen-sized origin remains readable for short and zero-length bones.
    (.setFill gc (Color/web "#353c3e"))
    (.fillOval gc (- x radius) (- y radius) (* 2.0 radius) (* 2.0 radius))
    (.setStroke gc color)
    (.setLineWidth gc (if active 1.75 1.25))
    (.strokeOval gc (- x radius) (- y radius) (* 2.0 radius) (* 2.0 radius))
    (when (<= length 5.0)
      (.strokeLine gc (- x radius 3.0) y (- x radius) y)
      (.strokeLine gc (+ x radius) y (+ x radius 3.0) y)
      (.strokeLine gc x (- y radius 3.0) x (- y radius))
      (.strokeLine gc x (+ y radius) x (+ y radius 3.0)))
    (.setFill gc color)))

(defn- draw-weight-overlay! [^GraphicsContext gc state points triangles weights]
  (when (and (:weight-overlay state) (pos? (count weights)))
    (let [colors (mapv (fn [influences]
                         (reduce (fn [rgb {:keys [bone weight]}]
                                   (let [^Color color (if (and (:weight-selected-only state) (not (contains? (or (coll/not-empty (:weight-bones state)) #{(:weight-bone state)}) bone)))
                                                        Color/BLACK (weight-color bone))]
                                     (mapv + rgb (mapv #(* weight %) [(.getRed color) (.getGreen color) (.getBlue color)]))))
                                 [0.0 0.0 0.0] influences)) weights)]
      (doseq [[a b c] triangles
              i (range 4) j (range (- 4 i))
              bary (cond-> [[[i j] [(inc i) j] [i (inc j)]]]
                     (< (+ i j) 3) (conj [[(inc i) j] [(inc i) (inc j)] [i (inc j)]]))]
        (let [coordinates (mapv (fn [[u v]] (mapv #(+ (* (- 1.0 (/ (+ u v) 4.0)) %1) (* (/ u 4.0) %2) (* (/ v 4.0) %3))
                                                  (points a) (points b) (points c))) bary)
              [u v] (mapv #(/ % 12.0) (reduce #(mapv + %1 %2) [0.0 0.0] bary))
              rgb (mapv #(+ (* (- 1.0 u v) %1) (* u %2) (* v %3)) (colors a) (colors b) (colors c))]
          (.setFill gc (Color/color (min 1.0 (rgb 0)) (min 1.0 (rgb 1)) (min 1.0 (rgb 2)) 0.65))
          (.fillPolygon gc (double-array (mapv first coordinates)) (double-array (mapv second coordinates)) 3))))))

(def ^:private transform-handle-length 76.0)

(defn- bone-rotation-handle [context doc]
  (let [state @(:state context)]
    (when (and (= "rotate" (:tool state)) (= :bone (:selected-kind state))
               (not (:playing state)))
      (when-let [bone (selected-bone context doc)]
        (let [matrix (get (:transforms (evaluated-pose context doc)) (:id bone))]
          {:center (screen context (point matrix [0.0 0.0])) :radius 48.0})))))

(defn- rotation-handle-hit? [handle x y]
  (when-let [{[px py] :center radius :radius} handle]
    (<= (abs (- (Math/hypot (- x px) (- y py)) radius)) 10.0)))

(defn- draw! [context]
  (let [doc (document context)
        state @(:state context)
        sampled (sampled-slots context doc)
        context (assoc context :sampled-slots sampled :sampled-deforms (:deforms sampled))
        context (assoc context :evaluated-pose (evaluated-pose context doc))
        ^Canvas canvas (:canvas context)
        ^GraphicsContext gc (.getGraphicsContext2D canvas)
        ^Canvas background (:background context)
        ^GraphicsContext grid (.getGraphicsContext2D background)
        width (.getWidth canvas) height (.getHeight canvas)
        bones (pose context doc)
        transforms (:transforms (evaluated-pose context doc))
        selected (:selected state)
        control-ids (coll/into-> (rig-links context doc) #{} (mapcat :controls))
        highlighted-bones (cond-> (conj (into (:selected-bones state #{}) (get-in state [:binding :bones]))
                                        (:id (selected-bone context doc)))
                            (= "weights" (:tool state)) (conj (:weight-bone state)))
        active-bones (get (evaluated-pose context doc) :active-bone-ids (set (mapv :id bones)))
        render-slots (render-slots context doc sampled (evaluated-pose context doc))
        peers (workspace-preview context doc)
        hovered-bone (when (and (:pointer state) (not (:drag state)))
                       (let [[x y] (:pointer state)] (bone-at context doc bones transforms x y)))]
    (swap! (:state context) assoc :preview-warnings (:warnings sampled))
    (.setTransform gc 1.0 0.0 0.0 1.0 0.0 0.0)
    (.clearRect gc 0.0 0.0 width height)
    (.setFill grid (Color/web "#606063")) (.fillRect grid 0.0 0.0 width height)
    (.setFill grid (Color/web "#666669"))
    (doseq [x (range 0.0 width 96.0)
            y (range 0.0 height 96.0)
            :when (even? (+ (long (/ x 96.0)) (long (/ y 96.0))))]
      (.fillRect grid x y 96.0 96.0))
    (.setStroke grid (Color/web "#414144")) (.setLineWidth grid 1.0)
    (.strokeLine grid (:pan-x state) 0.0 (:pan-x state) height)
    (.strokeLine grid 0.0 (:pan-y state) width (:pan-y state))
    (when-let [reference (image! context (:reference-image state))]
      (.setGlobalAlpha grid (double (:reference-opacity state 0.35)))
      (.drawImage grid reference (- (:pan-x state) (* (:zoom state) (/ (.getWidth reference) 2.0)))
                  (- (:pan-y state) (* (:zoom state) (/ (.getHeight reference) 2.0)))
                  (* (:zoom state) (.getWidth reference)) (* (:zoom state) (.getHeight reference)))
      (.setGlobalAlpha grid 1.0))
    ((:render! (:renderer context)) width height (if (false? (:show-images state)) [] (into (into (:slots peers) (ghost-slots context doc)) render-slots)))
    (when (not (false? (:show-bones state)))
      (.setStroke gc (Color/web "#a3a3a8"))
      (.setLineWidth gc 1.0)
      (doseq [{[x y] :from [tx ty] :to opacity :opacity} (:bone-lines peers)]
        (.setGlobalAlpha gc opacity)
        (draw-bone! gc [x y] [tx ty] false false nil))
      (.setGlobalAlpha gc 1.0))
    (.setFont gc (Font/font 12.0))
    (doseq [{:keys [name active] [x y width height] :bounds} (:labels peers)]
      (.setFill gc (Color/web (if active "#3e5065" "#39393c")))
      (.fillRoundRect gc x y width height 6.0 6.0)
      (.setFill gc (Color/web (if active "#e1e5e9" "#a5a5aa")))
      (.fillText gc name (+ x 8.0) (+ y 16.0) (- width 96.0)))
    (doseq [slot (:slots sampled)
            :let [shape (when (contains? active-bones (:bone slot))
                          (geometry/attachment-world-geometry (:attachment slot) transforms (:bone slot) (get (:deforms sampled) (:id slot))))]
            :when shape]
      (let [points (mapv #(screen context %) (:points shape))
            xs (double-array (mapv first points)) ys (double-array (mapv second points))]
        (.setStroke gc (Color/web (case (:type shape) "clipping" "#6da3d6" "boundingbox" "#65c98b" "#e9ab58")))
        (.setLineWidth gc (if (= selected (:id slot)) 2.0 1.0))
        (if (= "point" (:type shape))
          (when (= 2 (count points))
            (let [[[x y] [tx ty]] points] (.strokeOval gc (- x 4.0) (- y 4.0) 8.0 8.0) (.strokeLine gc x y tx ty)))
          (.strokePolygon gc xs ys (count points)))
        (when (and (= :slot (:selected-kind state)) (= selected (:id slot)) (= "path" (:tool state)))
          (doseq [[index [x y]] (map-indexed vector points)]
            (.setFill gc (if (contains? (:vertices state) index) Color/ORANGE (Color/web "#73cae5")))
            (.fillOval gc (- x 3.5) (- y 3.5) 7.0 7.0)))))
    (doseq [{:keys [id points triangles] :as rendered-slot} (render/render-plan render-slots)
            :let [mesh-selected (and (= :slot (:selected-kind state))
                                     (contains? (:selected-slots state #{selected}) id)
                                     (= "mesh" (get-in (entity (:slots sampled) id) [:attachment :type])))
                  editing-mesh (and (= id selected) (#{"move" "rotate" "scale" "shear" "mesh" "weights"} (:tool state)))]
            :when (and points (or (= id (:hover-slot state))
                                  (and (= :slot (:selected-kind state)) (contains? (:selected-slots state #{selected}) id)
                                       (editable-preview-attachment context doc))))]
      (when (and editing-mesh (= "weights" (:tool state)))
        (draw-weight-overlay! gc state points triangles (get-in (selected-slot context doc) [:attachment :weights])))
      (when mesh-selected
        (let [{:keys [boundary interior]} (preview-value context :wireframe triangles nil (fn mesh-wireframe [] (selection/wireframe-edges triangles)))]
          (.setLineWidth gc 1.0)
          (doseq [[edges color] [[interior "#85eceb55"] [boundary "#85eceb"]]]
            (.setStroke gc (Color/web color))
            (.beginPath gc)
            (doseq [[a b] edges]
              (let [[ax ay] (points a) [bx by] (points b)]
                (.moveTo gc ax ay) (.lineTo gc bx by)))
            (.stroke gc))))
      (when mesh-selected
        (doseq [[index [x y]] (map-indexed vector points)]
          (.setFill gc (if (and editing-mesh (contains? (:vertices state #{(:vertex state)}) index)) Color/WHITE (Color/web "#85eceb")))
          (.fillOval gc (- x 3.5) (- y 3.5) 7.0 7.0)
          (when (and editing-mesh (= index (:hover-vertex state)))
            (.setStroke gc (Color/web "#85eceb")) (.strokeOval gc (- x 6) (- y 6) 12 12))
          (when (and editing-mesh (= "weights" (:tool state)) (not= false (:weight-pies state))
                     (or (not (:weight-selected-only state)) (contains? (:vertices state) index)
                         (coll/any? #(and (contains? (or (coll/not-empty (:weight-bones state)) #{(:weight-bone state)}) (:bone %)) (pos? (:weight %)))
                                    (get-in (selected-slot context doc) [:attachment :weights index]))))
            (let [influences (get-in (selected-slot context doc) [:attachment :weights index])
                  radius (if (contains? (:vertices state) index) 7.0 4.5)]
              (reduce (fn [angle influence]
                        (let [extent (* 360.0 (:weight influence))]
                          (.setFill gc (weight-color (:bone influence)))
                          (.fillArc gc (- x radius) (- y radius) (* 2 radius) (* 2 radius) angle extent javafx.scene.shape.ArcType/ROUND)
                          (+ angle extent))) 0.0 influences)))))
      (when (pos? (count points))
        (let [xs (mapv first points) ys (mapv second points)
              left (reduce min xs) top (reduce min ys)]
          (.setStroke gc (Color/web (if (= id (:hover-slot state)) "#d1ffff" "#85eceb")))
          (.setLineWidth gc 1.0)
          (let [source (select-keys rendered-slot [:image :uvs :triangles])
                cache (get @(:selection-outlines context) id)
                binding (if (= source (:source cache)) (:binding cache) (selection/bind-outline rendered-slot))
                segments (if (and (= source (:source cache)) (= points (:points cache)))
                           (:segments cache)
                           (selection/project-outline binding points))
                right (reduce max xs) bottom (reduce max ys)]
            (when (not (identical? segments (:segments cache)))
              (swap! (:selection-outlines context) assoc id {:source source :binding binding :points points :segments segments}))
            (.setLineCap gc javafx.scene.shape.StrokeLineCap/ROUND)
            (.beginPath gc)
            (doseq [[[x0 y0] [x1 y1]] segments]
              (.moveTo gc x0 y0) (.lineTo gc x1 y1))
            (.stroke gc)
            (.setLineCap gc javafx.scene.shape.StrokeLineCap/BUTT)
            (.strokeRect gc left top (- right left) (- bottom top))
            (.setLineWidth gc 2.0)
            (doseq [[x y dx dy] [[left top 1 1] [right top -1 1] [left bottom 1 -1] [right bottom -1 -1]]]
              (.strokeLine gc x y (+ x (* dx 9.0)) y)
              (.strokeLine gc x y x (+ y (* dy 9.0))))))))
    (doseq [slot (:slots sampled) :when (= "path" (get-in slot [:attachment :type]))]
      (let [attachment (:attachment slot)
            curves (constraints/path-curves attachment (get transforms (:bone slot)) transforms (get (:sampled-deforms context) (:id slot)))
            active (and (= :slot (:selected-kind state)) (= selected (:id slot)) (editable-preview-attachment context doc))]
        (.setStroke gc (Color/web (if active "#f4b36c" "#a1774e")))
        (.setLineWidth gc (if active 2.0 1.0))
        (doseq [curve curves]
          (let [[[x y] [cx1 cy1] [cx2 cy2] [ex ey]] (mapv #(screen context %) curve)]
            (.beginPath gc) (.moveTo gc x y) (.bezierCurveTo gc cx1 cy1 cx2 cy2 ex ey) (.stroke gc)))
        (when active
          (doseq [[index xy] (map-indexed vector (partitionv 2 (attachment-points context doc slot transforms)))]
            (let [[x y] (screen context xy)]
              (.setFill gc (if (contains? (:vertices state) index) Color/ORANGE (Color/web "#73cae5")))
              (.fillOval gc (- x 4.0) (- y 4.0) 8.0 8.0))))))
    (when-let [{:keys [start current]} (or (:selection-box state) (:bone-selection-box state) (:slot-selection-box state))]
      (let [[x0 y0] start [x1 y1] current]
        (.setFill gc (Color/web "#4b9fe122"))
        (.fillRect gc (min x0 x1) (min y0 y1) (abs (- x1 x0)) (abs (- y1 y0)))
        (.setStroke gc (Color/web "#73cae5"))
        (.strokeRect gc (min x0 x1) (min y0 y1) (abs (- x1 x0)) (abs (- y1 y0)))))
    (when (and (= "weights" (:tool state)) (not= "direct" (:brush-mode state)) (:pointer state))
      (let [[x y] (:pointer state) radius (double (:brush-radius state 40.0))]
        (.setStroke gc (Color/web "#d9d9d9"))
        (.setLineWidth gc 1.0)
        (.strokeOval gc (- x radius) (- y radius) (* radius 2) (* radius 2))))
    (when (not (false? (:show-bones state)))
      (doseq [bone (sort-by #(contains? highlighted-bones (:id %)) bones)
              :when (contains? active-bones (:id bone))
              :let [matrix (get transforms (:id bone))
                    origin (screen context (point matrix [0.0 0.0]))
                    [ex ey :as tip] (screen context (point matrix [(double (:length bone)) 0.0]))
                    active (contains? highlighted-bones (:id bone))
                    hovered (= hovered-bone (:id bone))]]
        (draw-bone! gc origin tip active hovered
                    (when (and (= "weights" (:tool state))
                               (coll/any? #{(:id bone)} (mesh/binding-bones (:attachment (selected-slot context doc)))))
                      (weight-color (:id bone))))
        (when (and (not (contains? control-ids (:id bone))) (or active hovered (:show-names state)))
          (.setFont gc (Font/font 11.0))
          (.fillText gc (:id bone) (+ ex 8.0) (- ey 5.0)))))
    (when-not (false? (:show-bones state))
      (doseq [[id kind] (coll/into-> (rig-links context doc) {}
                          (mapcat (fn [{:keys [kind controls]}] (mapv #(vector % kind) controls))))
              :when (contains? active-bones id)
              :let [[x y] (screen context (point (get transforms id) [0.0 0.0]))
                    active (or (= selected id) (= hovered-bone id))
                    color (Color/web (if (= kind :ik) "#ffcc70" "#78e8ed"))]]
        (.setFill gc (Color/web "#35383a"))
        (.setStroke gc color)
        (.setLineWidth gc (if active 2.5 1.5))
        (.fillOval gc (- x 10.0) (- y 10.0) 20.0 20.0)
        (.strokeOval gc (- x 10.0) (- y 10.0) 20.0 20.0)
        (.strokeLine gc (- x 4.0) y (+ x 4.0) y)
        (.strokeLine gc x (- y 4.0) x (+ y 4.0))
        (.setFill gc color)
        (.setFont gc (Font/font 11.0))
        (let [text (str (tr context (if (= kind :ik) "rig-ik-controller" "rig-path-controller")) " · " id)
              text-width (preview-value context [:control-label id] id text
                                        (fn measure-controller-label []
                                          (.getWidth (.getLayoutBounds (doto (Text. text) (.setFont (Font/font 11.0)))))))
              label-x (max 4.0 (min (+ x 14.0) (- width text-width 4.0)))]
          (.fillText gc text label-x (max 12.0 (- y 14.0)) (max 1.0 (- width 8.0))))))
    (when (and (not (:playing state)) (#{"move" "scale" "shear"} (:tool state)))
      (when-let [selected (selected-bone context doc)]
        (let [bone (entity bones (:id selected))
              matrix (get transforms (:id bone))
              [x y] (screen context (or (:pivot (mesh-manipulator context doc)) (point matrix [0.0 0.0])))
              axes (tools/axes bone transforms (:coordinate-space state "parent") (:tool state))]
          (doseq [[index [dx dy]] (map-indexed vector axes)]
            (let [ex (+ x (* transform-handle-length dx)) ey (- y (* transform-handle-length dy))]
              (.setStroke gc (Color/web "#242424"))
              (.setLineWidth gc 5.0)
              (.strokeLine gc x y ex ey)
              (.setStroke gc (Color/web (if (zero? index) "#ff3028" "#8cff25")))
              (.setFill gc (Color/web (if (zero? index) "#ff3028" "#8cff25")))
              (.setLineWidth gc 3.0)
              (.strokeLine gc x y ex ey)
              (if (= "move" (:tool state))
                (let [bx (- ex (* 17.0 dx)) by (+ ey (* 17.0 dy))
                      xs (double-array [ex (+ bx (* 8.0 dy)) (- bx (* 8.0 dy))])
                      ys (double-array [ey (+ by (* 8.0 dx)) (- by (* 8.0 dx))])]
                  (.fillPolygon gc xs ys 3)
                  (.setStroke gc (Color/web "#303030"))
                  (.setLineWidth gc 1.0)
                  (.strokePolygon gc xs ys 3))
                (.fillRect gc (- ex 5.0) (- ey 5.0) 10.0 10.0))
              (.setFont gc (Font/font 13.0))
              (.fillText gc (if (zero? index) "X" "Y") (+ ex 8.0) (- ey 8.0)))))))
    (when-let [{[x y] :center radius :radius :as handle} (bone-rotation-handle context doc)]
      (let [[px py] (:pointer state [0.0 0.0])
            active (or (get-in state [:drag :rotation-handle]) (rotation-handle-hit? handle px py))]
        ;; Screen-space dimensions keep tiny bones usable at any zoom.
        (.setStroke gc (Color/web "#242424"))
        (.setLineWidth gc 5.0)
        (.strokeOval gc (- x radius) (- y radius) (* 2.0 radius) (* 2.0 radius))
        (.setStroke gc (Color/web (if active "#ffb18f" "#ee6e59")))
        (.setFill gc (Color/web (if active "#ffb18f" "#ee6e59")))
        (.setLineWidth gc (if active 3.0 2.0))
        (.strokeOval gc (- x radius) (- y radius) (* 2.0 radius) (* 2.0 radius))
        (doseq [direction [-1.0 1.0]]
          (let [ax (+ x (* direction radius))]
            (.fillPolygon gc (double-array [ax (- ax 6.0) (+ ax 6.0)])
                          (double-array [(+ y (* direction 10.0)) (- y (* direction 4.0)) (- y (* direction 4.0))]) 3)))
        (.strokeLine gc (- x 5.0) y (+ x 5.0) y)
        (.strokeLine gc x (- y 5.0) x (+ y 5.0))))
    (when-let [frame (mesh-manipulator context doc)]
      (let [[x y] (screen context (:pivot frame))]
        (.setStroke gc (Color/web "#d7eeee"))
        (.setLineWidth gc 1.5)
        (.strokeLine gc (- x 6.0) y (+ x 6.0) y)
        (.strokeLine gc x (- y 6.0) x (+ y 6.0))
        (when (#{"rotate" "scale"} (:tool state))
          (.strokeOval gc (- x 8.0) (- y 8.0) 16.0 16.0))
        (when (= "rotate" (:tool state))
          (.setStroke gc (Color/web "#ee6e59"))
          (.strokeOval gc (- x 38.0) (- y 38.0) 76.0 76.0))))
    ;; Reuse bindings when switching selections; cap retained image geometry.
    (when (> (count @(:selection-outlines context)) 32)
      (swap! (:selection-outlines context) select-keys (:selected-slots state #{selected})))
    (swap! (:state context) assoc :world-transforms transforms)))

(defn- interpolation-at [keys time fallback]
  (if-let [key (coll/first-where #(== time (:time %)) keys)]
    (if (= "bezier" (:interpolation key)) {:type "bezier" :curve (:curve key)} (:interpolation key))
    fallback))

(defn- change-bone-value [context doc bone-id property value]
  (let [{:keys [mode time interpolation]} @(:state context)]
    (if (= "animate" mode)
      (let [clip (animation context doc)
            track (coll/first-where #(and (= bone-id (:bone %)) (= property (:property %))) (:tracks clip))]
        (data/set-keyframe doc (:id clip) bone-id property time value (interpolation-at (:keys track) time interpolation)))
      (data/update-bone doc bone-id {(keyword property) value}))))

(defn- displayed-bone [context doc bone]
  (let [evaluated (evaluated-pose context doc)
        sampled (entity (:bones evaluated) (:id bone))]
    (when sampled
      (try (tools/displayed-transform sampled (:transforms evaluated) (:coordinate-space @(:state context) "parent"))
           (catch clojure.lang.ExceptionInfo _ sampled)))))

(defn- compensate-edit [context before after]
  (if-not (or (:compensate-bones @(:state context))
              (and (:compensate-images @(:state context)) (= "setup" (:mode @(:state context)))))
    after
    (let [state @(:state context)
          old-bones (pose context before)
          old-world (data/world-transforms old-bones)
          new-bones (pose context after)
          changed (coll/into-> new-bones #{} (keep (fn [bone] (when (not= bone (entity old-bones (:id bone))) (:id bone)))))
          corrected (if (:compensate-bones state)
                      (reduce (fn [result bone]
                                (if (and (contains? changed (:parent bone)) (not (contains? changed (:id bone))))
                                  (let [world (data/world-transforms (pose context result))
                                        fixed (constraints/compensate-bone bone (get old-world (:id bone)) (get world (:parent bone)))]
                                    (reduce (fn [d property] (change-bone-value context d (:id bone) (name property) (get fixed property)))
                                            result [:x :y :rotation :scale-x :scale-y :shear-x :shear-y]))
                                  result)) after new-bones)
                      after)]
      (if (and (:compensate-images state) (= "setup" (:mode state)))
        (let [world (data/world-transforms (:bones corrected))]
          (reduce (fn [result slot]
                    (let [attachment (:attachment slot)
                          old (get old-world (:bone slot))
                          new (get world (:bone slot))]
                      (if (or (nil? attachment) (= old new))
                        result
                        (let [inverse (or (geometry/inverse-transform new) (throw (ex-info "Cannot compensate a collapsed image transform" {})))
                              points (attachment-points context before slot old-world)
                              local (coll/into-> (partitionv 2 points) [] (mapcat #(geometry/transform-point inverse %)))
                              updated (if (= "region" (:type attachment "region"))
                                        (geometry/compensate-region attachment old new)
                                        (if (:weights attachment)
                                          (update attachment :weights
                                                  (fn [weights] (mapv (fn [influences]
                                                                        (mapv (fn [influence]
                                                                                (let [id (:bone influence)
                                                                                      xy (geometry/transform-point (get old-world id) [(:x influence) (:y influence)])
                                                                                      xy (geometry/inverse-transform-point (get world id) xy)]
                                                                                  (when-not xy (throw (ex-info "Cannot compensate collapsed weighted bone" {})))
                                                                                  (assoc influence :x (first xy) :y (second xy)))) influences)) weights)))
                                          (assoc attachment :vertices local)))]
                          (data/update-slot result (:id slot) {:attachment updated}))))) corrected (:slots before)))
        corrected))))

(defn- transform-selection [context doc primary]
  (let [state @(:state context)
        ids (coll/into-> (:slots doc) (:selected-bones state #{})
              (filter #(contains? (:selected-slots state #{}) (:id %))) (map :bone))]
    (conj ids (:id primary))))

(defn- transform-group [context before primary after]
  (let [ids (transform-selection context before primary)]
    (if (<= (count ids) 1)
      after
      (let [old-bones (pose context before)
            old-world (data/world-transforms old-bones)
            primary-world (data/world-transforms (pose context after))
            inverse (or (geometry/inverse-transform (get old-world (:id primary)))
                        (throw (ex-info "Cannot transform a group from a collapsed primary bone." {})))
            delta (geometry/compose-transforms (get primary-world (:id primary)) inverse)
            by-id (coll/into-> old-bones {} (map (juxt :id identity)))
            depth (fn bone-depth [bone]
                    (loop [parent (:parent bone) depth 0]
                      (if parent (recur (:parent (get by-id parent)) (inc depth)) depth)))
            ordered (sort-by depth (filterv #(contains? ids (:id %)) old-bones))
            rotation-delta (- (:rotation (entity (pose context after) (:id primary)))
                              (:rotation (get by-id (:id primary))))
            handedness (fn rotation-handedness [bone]
                         (if (or (nil? (:parent bone))
                                 (contains? #{"onlyTranslation" "noRotationOrReflection"} (:inherit bone)))
                           1.0
                           (let [[a b c d] (get old-world (:parent bone))]
                             (if (neg? (- (* a d) (* b c))) -1.0 1.0))))]
        (reduce (fn transform-selected-bone [result bone]
                  (let [worlds (data/world-transforms (pose context result))
                        desired (geometry/compose-transforms delta (get old-world (:id bone)))
                        current (entity (pose context result) (:id bone))
                        local (if (coll/every? #(< (abs (double %)) 1.0e-8)
                                               (mapv - desired (get worlds (:id bone))))
                                current
                                (constraints/compensate-bone current desired (get worlds (:parent bone))))
                        inherited (loop [child bone]
                                    (when (and (:parent child)
                                               (not (contains? #{"onlyTranslation" "noRotationOrReflection"} (:inherit child))))
                                      (or (contains? ids (:parent child)) (recur (get by-id (:parent child))))))
                        expected (+ (:rotation bone) (if inherited 0.0 (* rotation-delta (handedness (get by-id (:id primary))) (handedness bone))))
                        ;; Matrices lose turns. Restore the authored turn for
                        ;; each group root, including whole-turn edits.
                        local (if (and (= "animate" (:mode @(:state context)))
                                       (not= "world" (:coordinate-space @(:state context))))
                                (update local :rotation #(+ % (* 360.0 (Math/round (/ (- expected %) 360.0)))))
                                local)]
                    (reduce (fn apply-group-property [doc property]
                              (let [value (get local (keyword property))]
                                (if (< (abs (double (- value (get current (keyword property) 0.0)))) 1.0e-8)
                                  doc
                                  (change-bone-value context doc (:id bone) property value))))
                            result transform-properties))) after ordered)))))

(defn- change-displayed-value [context doc bone property value]
  (let [evaluated (evaluated-pose context doc)
        sampled (entity (:bones evaluated) (:id bone))
        space (:coordinate-space @(:state context) "parent")
        changes (tools/local-changes sampled (:transforms evaluated) space property value)]
    (compensate-edit context doc
                     (transform-group context doc bone
                                      (reduce-kv (fn [doc property value] (change-bone-value context doc (:id bone) (name property) value)) doc changes)))))

(declare change-mesh-value)

(defn- install-palette-number-preview! [context field property]
  (ui/user-data! field ::number-focus-target (:canvas context))
  (let [baseline (atom nil)]
    (ui/user-data! field ::number-preview
                   {:begin (fn begin-numeric-preview []
                             (reset! baseline (document context))
                             (swap! (:state context) assoc :drag {:numeric true :document @baseline}))
                    :preview (fn preview-numeric-value [value]
                               (when @baseline
                                 (attempt! context
                                           (fn apply-numeric-preview []
                                             (let [doc @baseline
                                                   result (if-let [frame (mesh-manipulator context doc)]
                                                            (change-mesh-value context doc frame property value)
                                                            (if-let [bone (selected-bone context doc)]
                                                              (change-displayed-value context doc bone property value) doc))]
                                               (swap! (:state context) assoc :draft result))))))
                    :finish (fn finish-numeric-preview []
                              (when-let [draft (:draft @(:state context))]
                                (attempt! context #(commit-pose! context draft)))
                              (reset! baseline nil)
                              (swap! (:state context) dissoc :drag :draft))
                    :cancel (fn cancel-numeric-preview []
                              (reset! baseline nil)
                              (swap! (:state context) dissoc :drag :draft))})))

(defn- select-tool! [context tool]
  (let [state @(:state context)]
    (if (and (= "create" tool) (not= "setup" (:mode state)))
      (message! context (tr context "create-setup-only"))
      (do
        (when (not= tool (:tool state))
          (swap! (:state context) dissoc :binding)
          (swap! (:state context) assoc :previous-tool (:tool state) :tool tool))
        (swap! (:state context) assoc :rigging-panel (if (= "weights" tool) :weights :properties) :inspector-token nil)))))

(defn- fit! [context]
  (let [doc (document context)
        sampled (sampled-slots context doc)
        context (assoc context :sampled-slots sampled :sampled-deforms (:deforms sampled))
        transforms (:transforms (evaluated-pose context doc))
        attachment-points (into [] (comp (filter :attachment) (mapcat #(partitionv 2 (attachment-points context doc % transforms)))) (:slots sampled))
        points (if (pos? (count attachment-points)) attachment-points
                   (mapv #(point (get transforms (:id %)) [0.0 0.0]) (:bones doc)))
        xs (mapv first points) ys (mapv second points)
        left (reduce min -100.0 xs) right (reduce max 100.0 xs)
        bottom (reduce min -100.0 ys) top (reduce max 100.0 ys)
        ^Canvas canvas (:canvas context)
        zoom (max 0.05 (min 8.0 (/ (- (.getWidth canvas) 80.0) (max 1.0 (- right left)))
                            (/ (- (.getHeight canvas) 150.0) (max 1.0 (- top bottom)))))]
    (swap! (:state context) assoc :zoom zoom
           :pan-x (- (/ (.getWidth canvas) 2.0) (* zoom (/ (+ left right) 2.0)))
           :pan-y (+ (/ (- (.getHeight canvas) 70.0) 2.0) (* zoom (/ (+ bottom top) 2.0))))))

(defn- select-weight-bones! [context ids]
  (let [doc (document context)
        state @(:state context)
        slot (or (selected-slot context doc) (entity (:slots doc) (:weight-slot state)))
        ids (filterv (set (mapv :id (:bones doc))) ids)
        bound (set (mesh/binding-bones (:attachment slot)))]
    (swap! (:state context) assoc
           :selected-kind :slot :selected (:id slot) :weight-slot (:id slot)
           :selected-bones (set ids) :selected-slots #{(:id slot)}
           :tree-selection (mapv #(vector :bone %) ids)
           :weight-picked-bones ids :weight-bone (first ids)
           :weight-bones (set (filterv bound ids)) :inspector-token nil)))

(defn- set-selection! [context kind id]
  (if (and (= :bone kind) (= :weights (:rigging-panel @(:state context)))
           (not (:binding @(:state context)))
           (= "mesh" (get-in (selected-slot context (document context)) [:attachment :type])))
    (select-weight-bones! context [id])
    (swap! (:state context) assoc :selected-kind kind :selected id :selected-attachment nil :tree-selection nil :selected-bones (if (= :bone kind) #{id} #{}) :selected-slots (if (= :slot kind) #{id} #{}) :vertex 0 :vertices #{} :mesh-pivot nil :bind-bones nil :selected-key nil :selected-keys #{} :weight-picked-bones nil :inspector-token nil)))

(declare tree-attachments select-tree-attachment! sync-tree-selection!)

(defn- rename-entity! [context kind id new-id update-fn]
  (if-let [[slot name skin] (when (= :slot kind) (:selected-attachment @(:state context)))]
    (when (setup-change! context #(attachments/rename-placeholder % slot name new-id))
      (select-tree-attachment! context {:slot slot :id new-id :skin skin}))
    (when (setup-change! context #(update-fn % id {:id new-id}))
      (set-selection! context kind new-id)
      (when (= id (:weight-bone @(:state context)))
        (swap! (:state context) assoc :weight-bone new-id)))))

(defn- repair-selection! [context doc]
  (let [{:keys [selected-kind selected weight-bone]} @(:state context)
        entities (case selected-kind :event (mapv #(assoc % :id (:name %)) (events/definitions doc)) :bone (:bones doc) :slot (:slots doc) :ik (:ik doc) :transform (:transform doc) :path (:path doc) :physics (:physics doc) nil)]
    (when (and selected (not= :attachment selected-kind) (nil? (entity entities selected)))
      (set-selection! context :bone (:id (first (:bones doc)))))
    (when-let [[slot-id name skin] (:selected-attachment @(:state context))]
      (let [slot (entity (:slots doc) slot-id)
            entry (when slot (coll/first-where #(and (= name (:id %)) (= skin (:skin %))) (tree-attachments context doc slot)))
            current (when slot (entity (:slots (if (= "animate" (:mode @(:state context))) (sampled-slots context doc) doc)) slot-id))
            active (boolean (and (:attachment current) (= name (get-in current [:attachment :name] slot-id))))]
        (cond
          (nil? entry) (set-selection! context (when slot :slot) (:id slot))
          (not= active (= :slot (:selected-kind @(:state context))))
          (let [selection (:tree-selection @(:state context))]
            (select-tree-attachment! context entry)
            (when selection
              (sync-tree-selection! context
                                    (mapv (fn selection-entry [[kind id]]
                                            (if (= :attachment kind)
                                              (let [[slot name skin] id] {:kind kind :slot slot :id name :skin skin})
                                              {:kind kind :id id})) selection)))))))
    (when-let [slot (selected-slot context doc)]
      (let [count (quot (count (get-in slot [:attachment :vertices])) 2)]
        (swap! (:state context) update :vertices #(into #{} (filter (fn [index] (< index count))) %))))
    (when (and weight-bone (nil? (entity (:bones doc) weight-bone)))
      (swap! (:state context) dissoc :weight-bone))))

(defn- attach-image! [context]
  (attempt! context
            (fn []
              (when-not (= "setup" (:mode @(:state context)))
                (throw (ex-info (tr context "setup-edit-only") {})))
              (let [doc (g/node-value (:resource-node context) :document)
                    bone (or (selected-bone context doc) (first (:bones doc)))
                    ^File file (dialogs/make-file-dialog (tr context "attach-image") [["PNG" "*.png"]]
                                                         (io/file (:project-root context) "image.png")
                                                         (.getWindow (.getScene ^Node (:root context))))]
                (when file
                  (let [folder (io/file (:project-root context) "skeleton_images")
                        target (io/file folder (.getName file))]
                    (.mkdirs folder)
                    (when-not (= (.getCanonicalFile file) (.getCanonicalFile target))
                      (when (and (.exists target) (not= (slurp file :encoding "ISO-8859-1") (slurp target :encoding "ISO-8859-1")))
                        (throw (ex-info (tr context "image-name-conflict") {})))
                      (io/copy file target))
                    (let [image (Image. (str (.toURI target)) false)
                          id (unique-id (:slots doc) "slot")]
                      (when (.isError image) (throw (ex-info (tr context "invalid-image") {})))
                      (commit! context (data/add-slot doc {:id id :bone (:id bone)
                                                           :attachment {:type "region" :path (str "/skeleton_images/" (.getName file))
                                                                        :width (.getWidth image) :height (.getHeight image)}}))
                      (set-selection! context :slot id)
                      (workspace/resource-sync! (:workspace context)))))))))

(defn- apply-weight! [context doc vertex bone-id weight]
  (when-not (= "setup" (:mode @(:state context)))
    (throw (ex-info (tr context "weights-setup-only") {})))
  (let [slot (selected-slot context doc)
        attachment (:attachment slot)
        transforms (data/world-transforms (:bones doc))
        attachment (if (:weights attachment) attachment
                       (assoc attachment :weights
                              (mapv (fn [[x y]] [{:bone (:bone slot) :weight 1.0 :x x :y y}])
                                    (partitionv 2 (:vertices attachment)))))
        result (mesh/set-weights attachment #{vertex} bone-id weight
                                 {:slot-transform (get transforms (:bone slot)) :bone-transforms transforms :bones (:bones doc)}
                                 (:locked-bones @(:state context) #{}))]
    (data/apply-mesh-edit doc (:id slot) result)))

(defn- mesh-selection [context attachment]
  (let [selection (:vertices @(:state context))]
    (if (pos? (count selection)) selection
        (set (range (quot (count (:vertices attachment)) 2))))))

(defn- mesh-binding [doc slot]
  (let [transforms (data/world-transforms (:bones doc))]
    {:slot-transform (get transforms (:bone slot)) :bone-transforms transforms :bones (:bones doc)}))

(defn- edit-mesh! [context operation]
  (when-not (= "setup" (:mode @(:state context)))
    (throw (ex-info (tr context "mesh-setup-only") {})))
  (let [doc (document context) slot (selected-slot context doc)
        result (operation (:attachment slot) (mesh-binding doc slot))]
    (commit! context (data/apply-mesh-edit doc (:id slot) result))
    (swap! (:state context) assoc :vertices (:selection result)
           :vertex (or (first (sort (:selection result))) 0) :inspector-token nil)))

(defn- delete-vertices! [context]
  (attempt! context #(edit-mesh! context (fn [attachment _]
                                           (mesh/delete-vertices attachment (:vertices @(:state context)))))))

(defn- transform-animation-vertices [context doc selection transform]
  (let [slot (selected-slot context doc)
        state @(:state context)
        transforms (:transforms (evaluated-pose context doc))
        sampled (sampled-slots context doc)
        animation-id (:id (animation context doc))
        frame (preview-value context :deform-buffer doc
                             [slot transforms selection animation-id (:time state) (:deforms sampled)]
                             (fn prepare-deform-buffer []
                               (let [attachment (:attachment slot)
                                     points (vec (partitionv 2 (attachment-points context doc slot transforms)))
                                     influences (or (coll/not-empty (:weights attachment))
                                                    (mapv (fn [_] [{:bone (:bone slot) :weight 1.0}]) points))
                                     base (double-array (or (get (:deforms sampled) (:id slot))
                                                            (data/deform-size attachment)))
                                     plan (loop [index 0 offset 0 result []]
                                            (if (= index (count points))
                                              result
                                              (let [bindings (influences index)
                                                    total (transduce (map :weight) + 0.0 bindings)
                                                    selected (contains? selection index)]
                                                (when (and selected (< (abs total) 1.0e-12))
                                                  (throw (ex-info "A vertex with zero total weight cannot be deformed. Bind it to a bone first." {:vertex index})))
                                                (recur (inc index) (+ offset (* 2 (count bindings)))
                                                       (reduce-kv
                                                         (fn [result binding-index {:keys [bone weight]}]
                                                           (if (or (not selected) (zero? weight))
                                                             result
                                                             (let [matrix (get transforms bone)
                                                                   origin (inverse-point matrix [0.0 0.0])
                                                                   x (inverse-point matrix [1.0 0.0])
                                                                   y (inverse-point matrix [0.0 1.0])]
                                                               (when-not (and origin x y) (throw (ex-info (tr context "singular-bone") {})))
                                                               (conj result [(+ offset (* 2 binding-index)) (points index)
                                                                             (mapv #(/ % total) (mapv - x origin))
                                                                             (mapv #(/ % total) (mapv - y origin))])))) result bindings)))))
                                     track (slots/deform-track doc animation-id (:id slot) (:time state))]
                                 {:base base :buffer (aclone base) :plan plan :track track})))
        ^doubles base (:base frame)
        ^doubles buffer (:buffer frame)]
    ;; The mutable scratch belongs to this view, never to an undo document.
    (System/arraycopy base 0 buffer 0 (alength base))
    (doseq [[offset [x y] [ax ay] [bx by]] (:plan frame)]
      (let [[tx ty] (point transform [x y])
            dx (- tx x) dy (- ty y)
            offset (long offset)]
        (aset-double buffer offset (+ (aget base offset) (* ax dx) (* bx dy)))
        (aset-double buffer (inc offset) (+ (aget base (inc offset)) (* ay dx) (* by dy)))))
    (slots/set-deform-track-keyframe doc animation-id (:track frame) (:time state) (vec buffer)
                                     (interpolation-at (:keys (:track frame)) (:time state) (:interpolation state)))))

(defn- transform-vertices [context doc selection transform]
  (when-not (editable-preview-attachment context doc)
    (throw (ex-info (tr context "preview-attachment-readonly") {})))
  (if (= "animate" (:mode @(:state context)))
    (transform-animation-vertices context doc selection transform)
    (let [slot (selected-slot context doc)
          attachment (:attachment slot)
          transforms (:transforms (evaluated-pose context doc))
          points (vec (partitionv 2 (attachment-points context doc slot transforms)))
          deltas (mapv #(mapv - (point transform %) %) points)
          influences (or (coll/not-empty (:weights attachment))
                         (mapv (fn [_] [{:bone (:bone slot) :weight 1.0}]) points))
          local-delta (fn local-delta [matrix delta]
                        (let [origin (inverse-point matrix [0.0 0.0]) end (inverse-point matrix delta)]
                          (when-not (and origin end) (throw (ex-info (tr context "singular-bone") {})))
                          (mapv - end origin)))
          offsets (mapv (fn [index bindings]
                          (if-not (contains? selection index)
                            (mapv (fn [_] [0.0 0.0]) bindings)
                            (let [total (transduce (map :weight) + 0.0 bindings)]
                              (when (< (abs total) 1.0e-12)
                                (throw (ex-info "A vertex with zero total weight cannot be deformed. Bind it to a bone first." {:vertex index})))
                              (mapv (fn [{:keys [bone weight]}]
                                      (if (zero? weight) [0.0 0.0]
                                          (local-delta (get transforms bone) (mapv #(/ % total) (deltas index))))) bindings))))
                        (range (count points)) influences)
          updated (assoc attachment :vertices
                         (coll/into-> (mapv (fn [index position]
                                              (if-not (contains? selection index) position
                                                      (mapv + position (local-delta (get transforms (:bone slot)) (deltas index)))))
                                            (range (count points)) (partitionv 2 (:vertices attachment))) [] cat))
          updated (cond-> updated
                    (:weights attachment)
                    (assoc :weights (mapv (fn [bindings changes]
                                            (mapv (fn [binding [dx dy]]
                                                    (-> binding (update :x + dx) (update :y + dy))) bindings changes))
                                          (:weights attachment) offsets)))]
      (if (= "mesh" (:type attachment))
        (data/apply-mesh-edit doc (:id slot)
                              {:mesh updated :selection selection
                               :vertex-map (mapv #(hash-map % 1.0) (range (count points))) :deform-policy :identity})
        (data/update-slot doc (:id slot) {:attachment updated})))))

(defn- drag-vertices [context doc selection start target]
  (let [[dx dy] (mapv - target start)]
    (transform-vertices context doc selection [1.0 0.0 0.0 1.0 dx dy])))

(defn- mesh-palette-values [context doc frame]
  (let [slot (selected-slot context doc)
        bone (entity (:bones doc) (:bone slot))
        transforms (:transforms (evaluated-pose context doc))
        coordinates (case (:coordinate-space @(:state context) "parent")
                      "world" [1.0 0.0 0.0 1.0 0.0 0.0]
                      "local" (get transforms (:id bone))
                      (get transforms (:parent bone) [1.0 0.0 0.0 1.0 0.0 0.0]))
        [x y] (inverse-point coordinates (:center frame))]
    {:x x :y y :rotation 0.0 :scale-x 1.0 :scale-y 1.0 :shear-x 0.0 :shear-y 0.0 :coordinates coordinates}))

(defn- change-mesh-value [context doc frame property value]
  (let [[px py] (:pivot frame)
        [[ax ay] [bx by]] (:axes frame)
        basis [ax ay bx by px py]
        values (mesh-palette-values context doc frame)
        angle (Math/toRadians value)
        transform (case property
                    "rotation" [(Math/cos angle) (Math/sin angle) (- (Math/sin angle)) (Math/cos angle) 0.0 0.0]
                    "scale-x" [value 0.0 0.0 1.0 0.0 0.0]
                    "scale-y" [1.0 0.0 0.0 value 0.0 0.0]
                    "shear-x" [1.0 (Math/tan angle) 0.0 1.0 0.0 0.0]
                    "shear-y" [1.0 0.0 (Math/tan angle) 1.0 0.0 0.0]
                    nil)
        transform (if transform
                    (geometry/compose-transforms basis (geometry/compose-transforms transform (geometry/inverse-transform basis)))
                    (let [[x y] (point (:coordinates values) [(if (= property "x") value (:x values))
                                                              (if (= property "y") value (:y values))])
                          [cx cy] (:center frame)]
                      [1.0 0.0 0.0 1.0 (- x cx) (- y cy)]))]
    (transform-vertices context doc (:selection frame) transform)))

(defn- brush-document [context doc target strength]
  (when-not (= "setup" (:mode @(:state context)))
    (throw (ex-info (tr context "weights-setup-only") {})))
  (let [state @(:state context) slot (selected-slot context doc)
        result (mesh/brush-weights (:attachment slot)
                                   {:mode (keyword (:brush-mode state)) :center target
                                    :radius (/ (:brush-radius state) (:zoom state))
                                    :strength strength :bone-id (or (:weight-bone state) (:bone slot))
                                    :bone-ids (coll/not-empty (:weight-bones state))
                                    :falloff 2.0 :feather (:brush-feather state 0.5) :selection (:vertices state) :locked-bones (:locked-bones state #{})}
                                   (mesh-binding doc slot))]
    (data/apply-mesh-edit doc (:id slot) result)))

(declare background-io! ensure-animation trace-mesh! set-key!)

(defn- rebuild-mesh! [context operation]
  (attempt! context
            #(let [before (document context)
                   slot (selected-slot context before)
                   attachment (:attachment slot)
                   state @(:state context)]
               (when-not (= "setup" (:mode state))
                 (throw (ex-info (tr context "mesh-setup-only") {})))
               (background-io!
                 context
                 (fn []
                   (case operation
                     :generate (mesh/generate attachment {:iterations (:generate-iterations state 1)})
                     :reset (let [w (* 0.5 (:width attachment)) h (* 0.5 (:height attachment))]
                              (mesh/reset-vertices attachment [(- w) (- h) w h]))
                     :trace (mesh/trace-alpha attachment (animation-io/read-image-alpha (:project-root context) (:path attachment))
                                              {:threshold (:trace-threshold state 16)
                                               :simplify (:trace-simplify state 1.0)
                                               :padding (:trace-padding state 0)
                                               :bounds [(* -0.5 (:width attachment)) (* -0.5 (:height attachment))
                                                        (* 0.5 (:width attachment)) (* 0.5 (:height attachment))]})))
                 (fn [result]
                   (when-not (= before (document context))
                     (throw (ex-info "The document changed while rebuilding the mesh. Retry the operation." {})))
                   (commit! context (data/apply-mesh-edit before (:id slot) result))
                   (swap! (:state context) assoc :vertices #{} :vertex 0 :edge-start nil :selected-key nil :selected-keys #{} :inspector-token nil))))))

(defn- property-section [context id expanded nodes]
  (let [setup-only (contains? #{"property-constraint-setup" "property-constraint-offset" "property-physics-solver"} id)
        available (not (and setup-only (= "animate" (:mode @(:state context)))))]
    (doto (properties/section {:state (:state context)
                               :scope (select-keys @(:state context) [:selected-kind :mode])
                               :tr #(tr context %)}
                              id expanded nodes)
      (.setVisible available)
      (.setManaged available)
      (.setDisable (not available)))))

(defn- finish-binding! [context]
  (attempt! context
            (fn finish-binding []
              (let [{:keys [slot bones document]} (:binding @(:state context))
                    current (g/node-value (:resource-node context) :document)
                    target (entity (:slots current) slot)]
                (when-not (and (= "setup" (:mode @(:state context))) (= document current)
                               (= "mesh" (get-in target [:attachment :type])) (pos? (count bones)))
                  (throw (ex-info (tr context "binding-incomplete") {})))
                (let [attachment (:attachment target)
                      binding (mesh-binding current target)
                      positive-bones (coll/into-> (:weights attachment) #{} cat
                                       (filter #(pos? (:weight %))) (map :bone))
                      initial-rigid (and (= #{(:bone target)} positive-bones)
                                         (coll/any? #(not= (:bone target) %) bones))
                      result (if initial-rigid
                               (mesh/bind-weights (:mesh (mesh/update-bindings attachment binding)) bones binding
                                                  {:max-influences (:weight-influence-limit @(:state context) 4)
                                                   :locked-bones (:locked-bones @(:state context) #{})})
                               (mesh/change-binding attachment bones binding))]
                  (commit! context (data/apply-mesh-edit current slot result)))
                (swap! (:state context) dissoc :binding)
                (set-selection! context :slot slot)
                (swap! (:state context) assoc :rigging-panel :weights :tool "weights" :weight-bone (first bones))))))

(defn- start-binding! [context]
  (attempt! context
            (fn start-binding []
              (let [doc (document context) state @(:state context)
                    slot (selected-slot context doc)
                    ids (if slot (mesh/binding-bones (:attachment slot))
                            (filterv (set (:selected-bones state)) (mapv :id (:bones doc))))]
                (when-not (and (= "setup" (:mode state))
                               (or (and slot (= "mesh" (get-in slot [:attachment :type]))
                                        (editable-preview-attachment context doc))
                                   (and (nil? slot) (pos? (count ids)))))
                  (throw (ex-info (tr context "binding-select-mesh") {})))
                (swap! (:state context) assoc :playing false :tool "weights" :rigging-panel :weights
                       :binding {:document doc :slot (:id slot) :bones ids} :inspector-token nil)))))

(defn- pick-binding! [context kind id]
  (let [state (:state context) binding (:binding @state)]
    (case kind
      :bone (swap! state update-in [:binding :bones]
                   (fn toggle-bone [ids]
                     (if (coll/any? #{id} ids) (filterv #(not= id %) ids) (conj ids id))))
      :slot (let [doc (document context) slot (entity (:slots doc) id)]
              (when (and (nil? (:slot binding)) (= "mesh" (get-in slot [:attachment :type])))
                (set-selection! context :slot id)
                (swap! state assoc-in [:binding :slot] id)))
      nil)
    (swap! state assoc :inspector-token nil)))

(defn- weight-setting [context id title value minimum maximum action]
  (let [^Slider slider (identify! (Slider. minimum maximum value) id)
        ^TextField field (number-field (str id "-value") value
                                       (fn set-weight-value [v] (.setValue slider (max minimum (min maximum v)))))]
    (.setMinWidth slider 24.0) (.setPrefWidth slider 80.0)
    (.setStyle field "-fx-min-width: 42; -fx-pref-width: 48; -fx-max-width: 48;")
    (.setMinWidth field 42.0)
    (.setPrefWidth field 48.0)
    (.setMaxWidth field 48.0)
    (HBox/setHgrow slider Priority/ALWAYS)
    (.addListener (.valueProperty slider)
                  (reify ChangeListener
                    (changed [_ _ _ v]
                      (.setText field (format "%.1f" (double v)))
                      (action (double v)))))
    (doto (row [(doto (label (tr context title)) (.setMinWidth 36.0)) field slider])
      (.setMinWidth 0.0) (HBox/setHgrow Priority/ALWAYS))))

(defn- prune-preview! [context]
  (let [before (document context) slot (selected-slot context before)
        selection (mesh-selection context (:attachment slot))
        options (atom {:threshold 0.02 :max-influences (:weight-influence-limit @(:state context) 4) :locked-bones (:locked-bones @(:state context) #{})})
        removed (label "")
        dialog (Dialog.)
        preview (fn preview-pruning []
                  (attempt! context
                            (fn []
                              (let [edit (mesh/prune-weights (:attachment slot) selection @options)
                                    after (data/apply-mesh-edit before (:id slot) edit)
                                    n (- (count (coll/into-> (get-in slot [:attachment :weights]) [] cat))
                                         (count (coll/into-> (get-in edit [:mesh :weights]) [] cat)))]
                                (.setText removed (str (tr context "prune-removed") " " n))
                                (swap! (:state context) assoc :draft after)))))]
    (.setTitle dialog (tr context "prune-weights"))
    (.initOwner dialog (.getWindow (.getScene ^Node (:canvas context))))
    (.addAll (.getStylesheets (.getDialogPane dialog)) ^java.util.Collection (.getStylesheets (.getScene ^Node (:canvas context))))
    (.setContent (.getDialogPane dialog)
                 (column [(weight-setting context "prune-bones" "bones" (double (:max-influences @options)) 1.0 64.0
                                          #(do (swap! options assoc :max-influences (long %)) (preview)))
                          (weight-setting context "prune-threshold" "weight-threshold" 2.0 0.0 100.0
                                          #(do (swap! options assoc :threshold (/ % 100.0)) (preview))) removed]))
    (.addAll (.getButtonTypes (.getDialogPane dialog)) ^java.util.Collection [ButtonType/OK ButtonType/CANCEL])
    (.setOnHidden dialog
                  (ui/event-handler _
                    (let [after (:draft @(:state context))]
                      (swap! (:state context) dissoc :draft)
                      (when (and (= ButtonType/OK (.getResult dialog)) after)
                        (attempt! context
                                  #(if (= before (g/node-value (:resource-node context) :document))
                                     (commit! context after)
                                     (throw (ex-info (tr context "weight-preview-stale") {}))))))))
    (preview)
    (.show dialog)))

(defn- weld-weights! [context]
  (let [before (document context) slot (selected-slot context before)
        ids (set (mesh/binding-bones (:attachment slot)))
        targets (filterv (fn [candidate]
                           (let [bound (set (mesh/binding-bones (:attachment candidate)))]
                             (and (not= (:id slot) (:id candidate)) (>= (count bound) 2) (coll/every? ids bound)))) (:slots before))]
    (if (zero? (count targets))
      (message! context (tr context "weld-no-target"))
      (let [dialog (Dialog.) target (choice "weld-target" (mapv :id targets) (:id (first targets)) (fn [_]))
            overlap (doto (CheckBox. (tr context "weld-overlap")) (.setSelected true))]
        (.setTitle dialog (tr context "weld-weights"))
        (.initOwner dialog (.getWindow (.getScene ^Node (:canvas context))))
        (.addAll (.getStylesheets (.getDialogPane dialog)) ^java.util.Collection (.getStylesheets (.getScene ^Node (:canvas context))))
        (.setContent (.getDialogPane dialog) (column [target overlap]))
        (.addAll (.getButtonTypes (.getDialogPane dialog)) ^java.util.Collection [ButtonType/OK ButtonType/CANCEL])
        (.setOnHidden dialog
                      (ui/event-handler _
                        (when (= ButtonType/OK (.getResult dialog))
                          (attempt! context
                                    #(let [source (entity targets (.getValue ^ComboBox target))]
                                       (when-not (= before (document context)) (throw (ex-info (tr context "weight-preview-stale") {})))
                                       (commit! context (data/apply-mesh-edit before (:id slot)
                                                                              (mesh/weld-weights (:attachment slot) (mesh-selection context (:attachment slot)) (:attachment source)
                                                                                                 (mesh-binding before slot) (mesh-binding before source) (.isSelected overlap)))))))))
        (.show dialog)))))

(defn- weights-inspector [context doc]
  (let [state @(:state context) binding (:binding state)
        slot (selected-slot context doc)
        attachment (:attachment slot)
        _ (when (not= (:id slot) (:weight-slot state))
            (swap! (:state context)
                   (fn change-weight-slot [s]
                     (-> s (assoc-in [:weight-locks (:weight-slot s)] (:locked-bones s #{}))
                         (assoc :weight-slot (:id slot) :locked-bones (get-in s [:weight-locks (:id slot)] #{}) :weight-bones #{} :weight-picked-bones nil)))))
        state @(:state context)
        editable (and (= "setup" (:mode state)) (= "mesh" (:type attachment))
                      (editable-preview-attachment context doc))
        ids (if binding (:bones binding) (mesh/binding-bones attachment))
        weighted (pos? (count ids))
        picked (filterv (set (mapv :id (:bones doc))) (:weight-picked-bones state []))
        selected-bound (filterv (set ids) picked)
        unbound (filterv (complement (set ids)) picked)
        bone (if (coll/any? #{(:weight-bone state)} ids) (:weight-bone state) (first ids))
        active-bones (if (:weight-picked-bones state)
                       (set selected-bound)
                       (or (coll/not-empty (set (filterv (set ids) (vec (:weight-bones state))))) #{bone}))
        ^ListView bones (doto (identify! (ListView. (FXCollections/observableArrayList ^java.util.Collection ids)) "weight-bone-list")
                          (.setPrefHeight 240.0) (.setMinHeight 120.0) (.setMaxHeight Double/MAX_VALUE))
        run-edit (fn run-weight-edit [operation]
                   (attempt! context #(edit-mesh! context operation)))
        selection (mesh-selection context attachment)
        average (if (zero? (count selection)) 0.0
                    (/ (transduce (map (fn [index] (transduce (comp (filter #(contains? active-bones (:bone %))) (map :weight)) + 0.0 (get (:weights attachment) index)))) + 0.0 selection)
                       (count selection)))
        assign! (fn assign-direct-weight [percent]
                  (let [weight (max 0.0 (min 1.0 (/ percent 100.0)))]
                    (swap! (:state context) assoc :weight weight)
                    (run-edit (fn [mesh binding] (mesh/set-weight-group mesh selection active-bones weight binding (:locked-bones @(:state context) #{}))))))
        ^Slider slider (identify! (Slider. 0.0 100.0 (* 100.0 average)) "direct-weight-slider")
        automatic (button context "auto-weights" "auto-weights"
                          #(run-edit (fn [mesh binding]
                                       (mesh/bind-weights mesh ids binding {:selection selection :max-influences (:weight-influence-limit @(:state context) 4)
                                                                            :selected-bones (:weight-bones @(:state context))
                                                                            :locked-bones (:locked-bones @(:state context) #{})}))))
        smooth (button context "smooth-weights" "smooth-weights"
                       #(run-edit (fn [mesh binding] (mesh/smooth-weights mesh selection 0.5 binding (:locked-bones @(:state context) #{})))))
        prune (button context "prune-weights" "prune-weights"
                      #(prune-preview! context))
        remove (button context "unbind-bone" "unbind-bone"
                       #(if binding (pick-binding! context :bone (.getSelectedItem (.getSelectionModel bones)))
                            (run-edit (fn [mesh binding]
                                        (mesh/change-binding mesh (filterv (complement (set selected-bound)) ids) binding)))))
        bind-selected (doto (button context "bind-selected-bones" "bind-mesh"
                                    #(run-edit (fn add-selected-bones [attachment binding]
                                                 (mesh/change-binding attachment (into (vec ids) unbound) binding))))
                        (.setVisible (and (not binding) (pos? (count unbound))))
                        (.setManaged (and (not binding) (pos? (count unbound))))
                        (.setDisable (not editable)))
        copy (button context "copy-weights" "copy-weights"
                     #(swap! (:state context) assoc :weight-clipboard
                             (mapv (fn [index] (coll/into-> (get (:weights attachment) index) {} (map (juxt :bone :weight)))) (sort selection))))
        paste (button context "paste-weights" "paste-weights"
                      #(run-edit (fn [mesh binding] (mesh/paste-weights mesh selection (:weight-clipboard @(:state context)) binding (:locked-bones @(:state context) #{})))))
        swap (button context "swap-weights" "swap-weights"
                     #(run-edit (fn [mesh binding]
                                  (let [[a b] (vec active-bones)
                                        copied (mapv (fn [index]
                                                       (let [values (coll/into-> (get (:weights mesh) index) {} (map (juxt :bone :weight)))]
                                                         (assoc values a (get values b 0.0) b (get values a 0.0)))) (sort selection))]
                                    (mesh/paste-weights mesh selection copied binding (:locked-bones @(:state context) #{}))))))
        update (doto (button context "update-bindings" "update-bindings" #(run-edit mesh/update-bindings))
                 (.setDisable (not (and editable weighted (not binding)))))
        weld (doto (button context "weld-weights" "weld-weights" #(weld-weights! context))
               (.setDisable (not (and editable weighted (not binding)))))
        ^ComboBox mode (choice "brush-mode" ["direct" "add" "subtract" "replace"] (:brush-mode state "direct")
                               #(swap! (:state context) assoc :brush-mode % :tool "weights" :inspector-token nil))]
    (when (:weight-picked-bones state)
      (swap! (:state context) assoc :weight-bones (set selected-bound)))
    (when (and (nil? (:weight-picked-bones state)) bone (not (contains? (or (coll/not-empty (:weight-bones state)) #{(:weight-bone state)}) bone)))
      (swap! (:state context) assoc :weight-bone bone))
    (properties/translate-choice! mode #(tr context %) {"direct" "weight-direct" "add" "weight-add" "subtract" "weight-subtract" "replace" "weight-replace" "smooth" "smooth-weights"})
    (.setMinWidth slider 30.0) (.setPrefWidth slider 90.0)
    (.setPrefWidth mode 84.0) (.setMinWidth mode 70.0)
    (HBox/setHgrow mode Priority/ALWAYS)
    (HBox/setHgrow slider Priority/ALWAYS)
    (.addListener (.valueChangingProperty slider)
                  (reify ChangeListener
                    (changed [_ _ was-changing changing]
                      (when (and was-changing (not changing)) (assign! (.getValue slider))))))
    (.addListener (.valueProperty slider)
                  (reify ChangeListener
                    (changed [_ _ old value]
                      (when (and (not (.isValueChanging slider)) (not= old value))
                        (assign! (double value))))))
    (.setSelectionMode (.getSelectionModel bones) SelectionMode/MULTIPLE)
    (doseq [id (if (:weight-picked-bones state) selected-bound (:weight-bones state)) :when (coll/any? #{id} ids)]
      (.select (.getSelectionModel bones) ^Object id))
    (when-let [id (first selected-bound)] (.scrollTo bones ^Object id))
    (.addListener (.getSelectedItems (.getSelectionModel bones))
                  (reify ListChangeListener
                    (onChanged [_ _]
                      (let [selected (set (.getSelectedItems (.getSelectionModel bones)))]
                        (select-weight-bones! context (filterv selected ids))))))
    (.setOnMouseClicked bones
                        (ui/event-handler event
                          (when (= MouseButton/SECONDARY (.getButton ^MouseEvent event))
                            (when-let [id (.getSelectedItem (.getSelectionModel bones))]
                              (set-selection! context :bone id)))))
    (.setCellFactory bones
                     (reify javafx.util.Callback
                       (call [_ _]
                         (proxy [javafx.scene.control.ListCell] []
                           (updateItem [item empty-cell]
                             (proxy-super updateItem item empty-cell)
                             (.setGraphic ^javafx.scene.control.ListCell this
                                          (when-not empty-cell
                                            (let [marker (Button. (if (contains? (:locked-bones state) item) "▣" "■"))
                                                  ^Color color (weight-color item)]
                                              (.setStyle marker (format "-fx-text-fill: rgb(%d,%d,%d); -fx-padding: 0 3; -fx-font-size: 12;"
                                                                        (int (* 255 (.getRed color))) (int (* 255 (.getGreen color))) (int (* 255 (.getBlue color)))))
                                              (.setOnAction marker (ui/event-handler _
                                                                     (swap! (:state context) update :locked-bones
                                                                            (if (contains? (:locked-bones @(:state context)) item) disj (fnil conj #{})) item)
                                                                     (swap! (:state context) assoc :inspector-token nil)))
                                              marker)))
                             (.setText ^javafx.scene.control.ListCell this
                                       (when-not empty-cell
                                         (let [values (mapv (fn [index] (:weight (coll/first-where #(= item (:bone %)) (get (:weights attachment) index)) 0.0))
                                                            (sort selection))
                                               value (first values)
                                               uniform (coll/every? #(= value %) values)]
                                           (str item (when (and (not binding) value)
                                                       (if uniform (format "   %.1f%%" (* 100.0 value)) "   *")))))))))))
    (doseq [^Button button [automatic smooth prune remove]]
      (.setDisable button (not (and editable weighted (not binding)))))
    (.setVisible remove (or (boolean binding) (pos? (count selected-bound))))
    (.setManaged remove (or (boolean binding) (pos? (count selected-bound))))
    (.setDisable remove (or (not weighted) (and (not binding) (not editable))))
    (VBox/setVgrow bones Priority/ALWAYS)
    (.setMinWidth bones 0.0)
    (doseq [^Button control [automatic smooth prune remove update weld]]
      (.setStyle control "-fx-min-width: 0; -fx-padding: 3 6;"))
    (doseq [[^Button control glyph] [[copy "▤"] [paste "▣"] [swap "⇄"]]]
      (.setTooltip control (Tooltip. (.getText control)))
      (.setStyle control "-fx-min-width: 24; -fx-pref-width: 24; -fx-max-width: 24; -fx-padding: 3;")
      (.setText control "")
      (.setGraphic control
                   (doto (SVGPath.)
                     (.setContent (case glyph
                                    "▤" "M3,1 L11,1 L11,11 L3,11 Z M1,4 L1,13 L9,13 M5,4 L9,4 M5,6 L9,6 M5,8 L9,8"
                                    "▣" "M4,3 L2,3 L2,13 L12,13 L12,3 L10,3 M4,1 L10,1 L10,5 L4,5 Z M5,8 L9,8 M5,10 L9,10"
                                    "M1,4 L12,4 L9,1 M12,10 L1,10 L4,13"))
                     (.setFill Color/TRANSPARENT) (.setStroke (Color/web "#dedede")) (.setStrokeWidth 1.2)))
      (.setMinWidth control 24.0) (.setPrefWidth control 24.0) (.setMaxWidth control 24.0))
    (.setDisable copy (not weighted))
    (.setDisable paste (not (and editable weighted (coll/not-empty (:weight-clipboard state)))))
    (.setDisable swap (not (and editable (= 2 (count active-bones)))))
    (into
      [(label (if binding (tr context "binding-pick-help")
                  (if slot (str (tr context "tool-weights") " · " (:id slot)) (tr context "binding-select-mesh"))))]
      (concat
        (when (not binding)
          [(row [(weight-setting context "weight-influences" "weight-influences" (double (:weight-influence-limit state 4)) 1.0 64.0
                                 #(swap! (:state context) assoc :weight-influence-limit (long %))) prune])
           (row [(label (tr context "weight-mode")) mode update])
           (if (= "direct" (:brush-mode state "direct"))
             (row [(label (tr context "weight-percent"))
                   (doto (number-field "direct-weight-percent" (* 100.0 average) assign!) (.setStyle "-fx-min-width: 42; -fx-pref-width: 48; -fx-max-width: 52;") (.setPrefWidth 48.0) (.setMinWidth 42.0) (.setMaxWidth 52.0) (.setDisable (not (and editable weighted (pos? (count active-bones))))))
                   (doto slider (.setDisable (not (and editable weighted (pos? (count active-bones))))))])
             (column [(weight-setting context "brush-strength" "weight-strength" (* 100.0 (:brush-strength state 0.25)) 0.0 100.0
                                      #(swap! (:state context) assoc :brush-strength (/ % 100.0)))
                      (weight-setting context "brush-radius" "weight-size" (:brush-radius state 40.0) 1.0 500.0
                                      #(swap! (:state context) assoc :brush-radius %))
                      (weight-setting context "brush-feather" "weight-feather" (* 100.0 (:brush-feather state 0.5)) 0.0 100.0
                                      #(swap! (:state context) assoc :brush-feather (/ % 100.0)))]))
           (row [smooth automatic weld])
           (row (mapv (fn [[key title default]]
                        (doto (identify! (CheckBox. (tr context title)) title)
                          (.setSelected (boolean (get state key default)))
                          (.setOnAction (ui/event-handler event
                                          (swap! (:state context) assoc key (.isSelected ^CheckBox (.getSource event)))))))
                      [[:weight-pies "weight-pies" true] [:weight-overlay "weight-overlay" false] [:weight-selected-only "weight-selected-only" false]]))])
        [(row [(doto (label (tr context "weight-bones-label")) (HBox/setHgrow Priority/ALWAYS) (.setMaxWidth Double/MAX_VALUE)) copy paste swap]) bones
         (row (if binding
                [(button context "finish-binding" "finish-binding" #(finish-binding! context))
                 (button context "cancel-binding" "cancel" #(swap! (:state context) dissoc :binding))]
                [(doto (button context "bind-mesh" "bind-mesh" #(start-binding! context))
                   (.setVisible (zero? (count picked))) (.setManaged (zero? (count picked)))
                   (.setDisable (not= "setup" (:mode state))))
                 bind-selected remove]))]))))

(defn- mesh-tools [context _doc slot field]
  (let [state @(:state context) attachment (:attachment slot)
        selection #(mesh-selection context attachment)
        locked (:locked-bones state #{})
        run-edit (fn [f] (attempt! context #(edit-mesh! context f)))]
    [(property-section context "edit-mesh" false
                       [(choice "mesh-mode" ["modify" "create" "delete"] (:mesh-mode state "modify")
                                #(swap! (:state context) assoc :mesh-mode % :tool "mesh"))
                        (row [(button context "split-edge" "split-edge"
                                      #(run-edit (fn [attachment _]
                                                   (let [indices (vec (sort (:vertices @(:state context))))]
                                                     (when-not (= 2 (count indices)) (throw (ex-info (tr context "select-edge") {})))
                                                     (mesh/split-edge attachment indices 0.5)))))
                              (button context "delete-vertices" "delete-vertices" #(delete-vertices! context))])
                        (row [(button context "connect-edge" "connect-edge"
                                      #(run-edit (fn [attachment _] (mesh/connect-vertices attachment (vec (sort (:vertices @(:state context))))))))
                              (button context "disconnect-edge" "disconnect-edge"
                                      #(run-edit (fn [attachment _] (mesh/disconnect-vertices attachment (vec (sort (:vertices @(:state context))))))))])
                        (field "generate-iterations" "generate-iterations" (:generate-iterations state 1)
                               #(swap! (:state context) assoc :generate-iterations (max 1 (min 8 (int %)))))
                        (row [(button context "generate-mesh" "generate-mesh" #(rebuild-mesh! context :generate))
                              (button context "reset-mesh" "reset-mesh" #(rebuild-mesh! context :reset))])])
     (property-section context "trace-mesh" false
                       [(button context "trace-mesh" "trace-title" #(trace-mesh! context))
                        (doto (label (tr context "smart-mesh-help")) (.setWrapText true))])
     (property-section context "mirror-weights" false
                       [(row [(choice "mirror-axis" ["x" "y"] (:mirror-axis state "x") #(swap! (:state context) assoc :mirror-axis %))
                              (number-field "mirror-origin" (:mirror-origin state 0.0) #(swap! (:state context) assoc :mirror-origin %))])
                        (label (tr context "mirror-bones"))
                        (text-field "mirror-bones" (:mirror-bones state "") #(swap! (:state context) assoc :mirror-bones %))
                        (button context "mirror-weights" "mirror-weights"
                                #(run-edit (fn [attachment binding]
                                             (let [state @(:state context)
                                                   pairs (into [] (comp (map string/trim) (remove string/blank?)
                                                                        (map (fn [pair]
                                                                               (let [ids (mapv string/trim (string/split pair #":"))]
                                                                                 (when-not (= 2 (count ids)) (throw (ex-info (tr context "mirror-pair-error") {})))
                                                                                 ids)))) (string/split (:mirror-bones state "") #","))
                                                   mapping (into {} (mapcat (fn [[a b]] [[a b] [b a]])) pairs)]
                                               (mesh/mirror-weights attachment (selection) (keyword (:mirror-axis state "x")) (:mirror-origin state 0.0)
                                                                    mapping (assoc binding :locked-bones locked))))))])]))

(defn- path-inspector [context doc slot field]
  (let [attachment (:attachment slot)
        state @(:state context)
        points (partitionv 2 (attachment-points context doc slot (:transforms (evaluated-pose context doc))))
        index (min (dec (count points)) (:vertex state))
        update-attachment (fn [changes] (setup-change! context #(data/update-slot % (:id slot) {:attachment (merge attachment changes)})))]
    (into [(label (tr context (if (= "path" (:type attachment)) "path-attachment" "vertices")))
           (text-field "slot-name" (or (second (:selected-attachment @(:state context))) (:id slot)) #(rename-entity! context :slot (:id slot) % data/update-slot))
           (choice "slot-bone" (mapv :id (:bones doc)) (:bone slot)
                   #(setup-change! context (fn [d] (data/update-slot d (:id slot) {:bone %}))))
           (doto (identify! (CheckBox. (tr context "closed-path")) "path-closed")
             (.setVisible (= "path" (:type attachment)))
             (.setManaged (= "path" (:type attachment)))
             (.setSelected (boolean (:closed attachment)))
             (.setDisable (= "animate" (:mode state)))
             (.setOnAction (ui/event-handler event (update-attachment {:closed (.isSelected ^CheckBox (.getSource event))}))))
           (doto (identify! (CheckBox. (tr context "constant-speed")) "path-constant-speed")
             (.setVisible (= "path" (:type attachment)))
             (.setManaged (= "path" (:type attachment)))
             (.setSelected (boolean (:constant-speed attachment)))
             (.setDisable (= "animate" (:mode state)))
             (.setOnAction (ui/event-handler event (update-attachment {:constant-speed (.isSelected ^CheckBox (.getSource event))}))))
           (field "vertex-index" "vertex" index #(swap! (:state context) assoc :vertex (max 0 (min (dec (count points)) (long %))) :vertices #{(max 0 (min (dec (count points)) (long %)))} :inspector-token nil))
           (button context "edit-path" (if (= "path" (:type attachment)) "edit-path" "edit-vertices") #(swap! (:state context) assoc :tool "path"))]
          (mapv (fn [[axis property]]
                  (field (str "path-point-" property) property (get (nth points index) axis)
                         #(pose-change! context (fn [d]
                                                  (let [slot (selected-slot context d)
                                                        current (nth (partitionv 2 (attachment-points context d slot (:transforms (evaluated-pose context d)))) index)]
                                                    (drag-vertices context d #{index} current (assoc current axis %)))))))
                [[0 "x"] [1 "y"]]))))

(defn- event-inspector [context doc field]
  (let [{:keys [selected time]} @(:state context)
        definition (coll/first-where #(= selected (:name %)) (events/definitions doc))
        frame (coll/first-where #(and (= selected (:name %)) (== time (:time %)))
                                (when-let [animation (animation context doc)] (events/keyframes doc (:id animation))))
        update-default (fn update-event-default [changes]
                         (change! context #(if (contains? changes :audio)
                                             (audio/assign-file % selected (:audio changes))
                                             (events/update-definition % selected changes))))
        update-key (fn update-event-key [changes]
                     (change! context #(events/set-keyframe % (:id (animation context %)) (merge frame changes))))
        fields (fn event-fields [prefix values update-values]
                 [(field (str prefix "int") "event-int" (:int values 0) #(update-values {:int (long %)}))
                  (field (str prefix "float") "event-float" (:float values 0.0) #(update-values {:float %}))
                  (label (tr context "event-string"))
                  (text-field (str prefix "string") (:string values "") #(update-values {:string %}))
                  (field (str prefix "volume") "audio-volume" (:volume values 1.0) #(update-values {:volume (max 0.0 %)}))
                  (field (str prefix "balance") "audio-balance" (:balance values 0.0) #(update-values {:balance (max -1.0 (min 1.0 %))}))])]
    (into [(label (tr context "events"))
           (text-field "event-name" selected
                       (fn rename-event [name]
                         (when (change! context #(events/update-definition % selected {:name name}))
                           (set-selection! context :event name))))
           (label (tr context "event-defaults"))]
          (into (fields "event-default-" definition update-default)
                (into [(button context "event-key" "event-key"
                               (fn insert-event-key []
                                 (change! context
                                          (fn [doc]
                                            (let [doc (ensure-animation doc)]
                                              (events/set-keyframe doc (:id (animation context doc))
                                                                   {:name selected :time (:time @(:state context))}))))))]
                      (cond-> [(label (tr context "audio-file"))
                               (text-field "event-audio" (:audio definition "") #(update-default {:audio %}))
                               (row [(button context "import-audio" "import-audio"
                                             (fn import-event-audio []
                                               (when-let [file (dialogs/make-file-dialog (tr context "import-audio") [["Audio" "*.wav" "*.mp3" "*.ogg" "*.aiff" "*.m4a"]]
                                                                                         (io/file (:project-root context) "event.wav") (.getWindow (.getScene ^Node (:root context))))]
                                                 (background-io! context #(audio/import-file! (:project-root context) file)
                                                                 (fn audio-imported [path] (update-default {:audio path}) (workspace/resource-sync! (:workspace context)))))))
                                     (button context "preview-audio" "preview-audio" #((:play! (:audio context)) doc (merge definition frame)))])]
                        frame
                        (into (into [(label (str (tr context "event-key-values") " · " (format "%.3f s" (double time))))]
                                    (fields "event-key-" (merge definition frame) update-key)))))))))

(declare delete-selection! add-ik! add-path!)

(defn- rig-selection-button [context id text kind target]
  (doto (identify! (Button. text) id)
    (.setMaxWidth Double/MAX_VALUE)
    (.setWrapText true)
    (.setTooltip (Tooltip. text))
    (.setMinHeight 28.0)
    (.setFocusTraversable false)
    (.setOnAction (ui/event-handler _
                    (set-selection! context kind target)
                    (when (= kind :bone) (select-tool! context "move"))))))

(defn- update-ik-pose! [context selected changes]
  (pose-change! context
                (fn update-ik-values [d]
                  (if-not (= "animate" (:mode @(:state context)))
                    (data/update-ik d selected changes)
                    (let [time (:time @(:state context))
                          keys (:keys (coll/first-where #(= selected (:ik %)) (:ik-tracks (animation context d))))]
                      (if (contains? changes :softness)
                        (data/set-ik-softness-keyframe d (:id (animation context d)) selected time (:softness changes)
                                                       (interpolation-at (mapv #(assoc % :interpolation (:softness-interpolation %) :curve (:softness-curve %)) keys)
                                                                         time (:interpolation @(:state context))))
                        (data/set-ik-keyframe-values d (:id (animation context d)) selected time changes
                                                     (interpolation-at keys time (:interpolation @(:state context))))))))))

(defn- constraint-length-controls [context doc kind id prefix]
  (let [{:keys [mode time]} @(:state context)
        animated (= "animate" mode)
        animation-id (when animated (:id (animation context doc)))
        constraint (entity (if (= kind :ik) (data/sample-ik doc animation-id time)
                               (data/sample-paths doc animation-id time)) id)]
    (if (= kind :ik)
      (column
        (into [(doto (label (tr context "rig-ik-length-help")) (.setWrapText true))]
              (mapv (fn [property]
                      (doto (identify! (CheckBox. (tr context (str "rig-allow-" (name property)))) (str prefix "-" (name property)))
                        (.setSelected (boolean (get constraint property)))
                        (.setDisable (and (= property :compress) (> (count (:bones constraint)) 1)))
                        (.setOnAction (ui/event-handler event
                                        (update-ik-pose! context id {property (.isSelected ^CheckBox (.getSource event))})))))
                    [:stretch :compress])))
      (let [length-mode (cond
                          (and (= "chain" (:rotate-mode constraint)) (= "length" (:spacing-mode constraint)) (zero? (:spacing constraint))) "fixed"
                          (and (= "chainScale" (:rotate-mode constraint)) (= "proportional" (:spacing-mode constraint)) (= 1.0 (:spacing constraint))) "fit"
                          :else "custom")
            options (cond-> ["fixed" "fit"] (= "custom" length-mode) (conj "custom"))
            labels (mapv #(tr context (str "rig-path-length-" %)) options)]
        (column [(label (tr context "rig-path-length"))
                 (doto (choice (str prefix "-length-mode") labels (nth labels (.indexOf options length-mode))
                               (fn set-path-length-mode [value]
                                 (when-let [changes (case (nth options (.indexOf labels value))
                                                      "fixed" {:rotate-mode "chain" :spacing-mode "length" :spacing 0.0}
                                                      "fit" {:rotate-mode "chainScale" :spacing-mode "proportional" :spacing 1.0}
                                                      nil)]
                                   (setup-change! context #(data/update-path % id changes)))))
                   (.setDisable animated))
                 (doto (label (tr context (if animated "rig-path-length-setup-help" "rig-path-length-short-help")))
                   (.setTooltip (Tooltip. (tr context "rig-path-length-help")))
                   (.setWrapText true))])))))

(defn- rig-navigation [context doc links]
  (coll/into-> links []
    (mapcat (fn [{:keys [kind id bones controls target]}]
              (let [configured (= [kind id] ((juxt :selected-kind :selected) @(:state context)))
                    grid (rig-ui/control-grid controls (:selected @(:state context))
                                              (fn select-rig-controller [bone-id]
                                                (set-selection! context :bone bone-id)
                                                (select-tool! context "move"))
                                              (fn controller-tooltip [index bone-id]
                                                (str (tr context "rig-control-index") " " (inc index) " · " bone-id)))
                    summary (doto (label (str (count bones) " " (tr context "rig-bone-count") " · "
                                              (count controls) " " (tr context "rig-control-count")))
                              (.setTooltip (Tooltip. (coll/join-to-string ", " bones))))]
                (cond-> [(label (tr context "rig-control-picker")) grid summary]
                  (not configured)
                  (conj (rig-selection-button context (str "rig-configure-" (name kind) "-" id)
                                              (tr context (if (= kind :ik) "rig-ik-settings" "rig-path-settings")) kind id))
                  (and target (not= target (:selected @(:state context))))
                  (conj (rig-selection-button context (str "rig-shape-" id) (tr context "rig-path-shape") :slot target))
                  (or configured (= kind :ik))
                  (conj (constraint-length-controls context doc kind id (str "rig-" (name kind) "-" id)))))))))

(defn- bone-rig-inspector [context doc bone]
  (let [links (filterv #(or (coll/any? #{(:id bone)} (:controls %))
                            (coll/any? #{(:id bone)} (:bones %))) (rig-links context doc))
        controller (coll/first-where #(coll/any? #{(:id bone)} (:controls %)) links)
        animated (= "animate" (:mode @(:state context)))
        role (if controller
               (str (tr context "rig-control-index") " " (inc (.indexOf ^java.util.List (:controls controller) (:id bone))))
               (tr context (if (pos? (count links)) "rig-driven-role" "rig-fk-role")))]
    (property-section context "property-rig-control" true
                      (into [(doto (identify! (label role) "rig-role") (.setWrapText true))]
                            (if (pos? (count links))
                              (rig-navigation context doc links)
                              [(doto (button context "rig-create-ik" "rig-create-ik" #(add-ik! context))
                                 (.setMaxWidth Double/MAX_VALUE) (.setWrapText true) (.setDisable animated))
                               (doto (button context "rig-create-path" "rig-create-path" #(add-path! context))
                                 (.setMaxWidth Double/MAX_VALUE) (.setWrapText true) (.setDisable animated))])))))

(defn- restore-image-button [context doc slot]
  (doto (button context "restore-image" "restore-image"
                (fn restore-selected-image []
                  (setup-change! context
                                 (fn restore-image-document [document]
                                   (attachments/restore-image document
                                                              (get slot :spine-skin (:active-skin doc "default"))
                                                              (:id slot) (get-in slot [:attachment :name] (:id slot)))))
                  (swap! (:state context) assoc :tool "move" :vertices #{} :vertex 0
                         :weight-bones #{} :weight-picked-bones nil :weight-slot nil :weight-bone nil
                         :inspector-token nil)))
    (.setTooltip (Tooltip. (tr context "restore-image-help")))))

(declare auto-rig!)

(defn- entity-inspector-content [context doc]
  (let [{:keys [selected-kind selected vertex]} @(:state context)
        context (cond-> (assoc context :inspector-selection (select-keys @(:state context) [:mode :animation :selected-kind :selected]))
                  (= :slot selected-kind) (assoc :attachment-edit-slot selected :attachment-edit-key (:preview-attachment-key (selected-slot context doc))))
        field (fn inspector-field [id title value action] (properties/field-row (tr context title) [(number-field id value #(attempt! context (fn [] (action %))))]))]
    (case (when selected selected-kind)
      :event (event-inspector context doc field)
      :physics
      (let [state @(:state context)
            animated (= "animate" (:mode state))
            constraint (entity (data/sample-physics doc (when animated (:id (animation context doc))) (:time state)) selected)
            update-constraint (fn [changes] (setup-change! context #(data/update-physics % selected changes)))
            channel-id (if (= "global" (:physics-scope state)) "" selected)]
        (into [(label (tr context "physics-constraint"))
               (doto (text-field "physics-name" selected #(rename-entity! context :physics selected % data/update-physics))
                 (.setDisable (= "animate" (:mode @(:state context)))))
               (property-section context "property-constraint-setup" false
                                 [(label (tr context "bone"))
                                  (choice "physics-bone" (mapv :id (:bones doc)) (:bone constraint) #(update-constraint {:bone %}))
                                  (field "physics-order" "constraint-order" (:order constraint) #(update-constraint {:order (long %)}))])
               (property-section context "property-physics-animation" true
                                 [(label (tr context "physics-scope"))
                                  (choice "physics-scope" ["constraint" "global"] (:physics-scope state "constraint")
                                          #(swap! (:state context) assoc :physics-scope % :selected-key nil :selected-keys #{} :inspector-token nil))
                                  (button context "physics-reset-key" "physics-reset-key"
                                          #(change! context (fn [d] (let [d (if (pos? (count (:animations d))) d (data/add-animation d {:id "default" :duration 1.0 :tracks []}))]
                                                                      (data/set-physics-reset-keyframe d (:id (animation context d)) channel-id (:time @(:state context)))))))])]
              (into [(property-section context "property-physics-solver" false
                                       (mapv (fn [property]
                                               (doto (field (str "physics-" property) property (get constraint (keyword property))
                                                            #(update-constraint {(keyword property) %}))
                                                 (.setDisable animated)))
                                             ["x" "y" "rotate" "scale-x" "shear-x" "limit" "fps"]))]
                    (mapv (fn [property]
                            (column [(field (str "physics-" property) property (get constraint (keyword property))
                                            #(pose-change! context (fn [d]
                                                                     (if (= "animate" (:mode @(:state context)))
                                                                       (let [track (coll/first-where (fn [track] (and (= channel-id (:physics track)) (= property (:property track)))) (:physics-tracks (animation context d)))]
                                                                         (data/set-physics-keyframe d (:id (animation context d)) channel-id property (:time @(:state context)) %
                                                                                                    (interpolation-at (:keys track) (:time @(:state context)) (:interpolation @(:state context)))))
                                                                       (data/update-physics d selected {(keyword property) %})))))
                                     (doto (identify! (CheckBox. (tr context "physics-global")) (str "physics-" property "-global"))
                                       (.setSelected (boolean (get constraint (keyword (str property "-global")))))
                                       (.setDisable animated)
                                       (.setOnAction (ui/event-handler event (update-constraint {(keyword (str property "-global")) (.isSelected ^CheckBox (.getSource event))}))))]))
                          physics/animated-properties))))
      :bone
      (let [bone (entity (:bones doc) selected)
            animated (= "animate" (:mode @(:state context)))
            sampled (entity (if animated
                              (data/sample-pose doc (:id (animation context doc)) (:time @(:state context)))
                              (:bones doc)) selected)
            transform-field (fn transform-field [property]
                              (doto (number-field (str "bone-" property) (get sampled (keyword property) 0.0)
                                                  #(pose-change! context (fn [d] (change-displayed-value (assoc context :state (atom (assoc @(:state context) :coordinate-space "parent"))) d bone property %))))
                                (HBox/setHgrow Priority/ALWAYS)))]
        (into [(row [(label (tr context "bone"))
                     (doto (button context "bone-delete" "delete" #(delete-selection! context))
                       (.setDisable animated))])
               (let [name-field (doto (text-field "bone-name" (:id bone)
                                                  #(rename-entity! context :bone selected % data/update-bone))
                                  (.setDisable animated))]
                 (if (coll/any? #(or (coll/any? #{selected} (:controls %)) (coll/any? #{selected} (:bones %))) (rig-links context doc))
                   (property-section context "rig-identity" false [name-field])
                   name-field))
               (bone-rig-inspector context doc bone)
               (property-section context "property-transform" true
                                 [(properties/field-row (tr context "position") [(properties/axis-fields [["X" (transform-field "x")] ["Y" (transform-field "y")]])])
                                  (properties/field-row (tr context "rotation") [(transform-field "rotation")])
                                  (properties/field-row (tr context "tool-scale") [(properties/axis-fields [["X" (transform-field "scale-x")] ["Y" (transform-field "scale-y")]])])
                                  (property-section context "property-shear" false
                                                    [(properties/field-row (tr context "property-shear") [(properties/axis-fields [["X" (transform-field "shear-x")] ["Y" (transform-field "shear-y")]])])])
                                  (doto (row [(label (tr context "inherit"))
                                              (choice "bone-inherit-animation" inherit/modes (get sampled :inherit "normal")
                                                      (fn inherit-change [value]
                                                        (pose-change! context #(slots/set-inherit-keyframe % (:id (animation context %)) selected (:time @(:state context)) value))))
                                              (button context "bone-inherit-key" "key"
                                                      (fn key-inherit []
                                                        (animation-key-change! context
                                                                               (fn [d] (slots/set-inherit-keyframe d (:id (animation context d)) selected (:time @(:state context))
                                                                                                                   (:inherit (entity (data/sample-pose d (:id (animation context d)) (:time @(:state context))) selected) "normal"))))))])
                                    (.setVisible animated)
                                    (.setManaged animated))])]
              (when-not animated
                [(property-section context "property-bone-setup" false
                                   [(label (tr context "parent"))
                                    (choice "parent" (into ["—"] (comp (map :id) (remove #{selected})) (:bones doc))
                                            (or (:parent bone) "—")
                                            #(setup-change! context (fn [d] (data/update-bone d selected {:parent (when-not (= "—" %) %)}))))
                                    (label (tr context "inherit"))
                                    (choice "bone-inherit" ["normal" "onlyTranslation" "noRotationOrReflection" "noScale" "noScaleOrReflection"]
                                            (get bone :inherit "normal")
                                            #(setup-change! context (fn [d] (data/update-bone d selected {:inherit %}))))
                                    (field "bone-length" "length" (:length bone)
                                           #(setup-change! context (fn [d] (data/update-bone d selected {:length %}))))])])))
      :slot
      (if-not (editable-preview-attachment context doc)
        (cond-> [(doto (label (tr context "preview-attachment-readonly")) (.setWrapText true))]
          (= "linkedmesh" (get-in (selected-slot context doc) [:attachment :type]))
          (conj (restore-image-button context doc (selected-slot context doc))))
        (if (#{"path" "boundingbox" "clipping"} (get-in (selected-slot context doc) [:attachment :type]))
          (if-let [rig (coll/first-where #(= selected (:target %)) (rig-ui/groups doc))]
            [(doto (label (tr context "rig-path-shape-help")) (.setWrapText true))
             (property-section context "property-rig-control" true
                               (rig-navigation context doc (filterv #(= (:id rig) (:id %)) (rig-links context doc))))
             (property-section context "rig-advanced-path" false
                               (path-inspector context doc (selected-slot context doc) field))]
            (path-inspector context doc (selected-slot context doc) field))
          (let [slot (selected-slot context doc)
                attachment (:attachment slot)
                mesh (contains? #{"mesh" "linkedmesh"} (:type attachment))
                update-attachment (fn update-attachment [changes]
                                    (setup-change! context #(data/update-slot % selected {:attachment (merge attachment changes)})))
                number (fn attachment-number [property]
                         (number-field (str "attachment-" property) (get attachment (keyword property))
                                       #(attempt! context (fn edit-attachment-number [] (update-attachment {(keyword property) %})))))
                pair (fn attachment-pair [title fields]
                       (properties/field-row (tr context title)
                                             [(properties/axis-fields (mapv (fn [[axis property]] [axis (number property)]) fields))]))
                vertex-count (quot (count (:uvs attachment)) 2)]
            (into [(properties/field-row (tr context "attachment")
                                         [(text-field "slot-name" (or (second (:selected-attachment @(:state context))) (:id slot)) #(rename-entity! context :slot selected % data/update-slot))])
                   (properties/field-row (tr context "bone")
                                         [(choice "slot-bone" (mapv :id (:bones doc)) (:bone slot)
                                                  #(setup-change! context (fn [d] (data/update-slot d selected {:bone %}))))])
                   (properties/field-row (tr context "image")
                                         [(text-field "image-path" (:path attachment) #(update-attachment {:path %}))])]
                  (coll/into-> [(when (= "region" (get attachment :type "region"))
                                  [(property-section context "property-transform" true
                                                     [(pair "position" [["X" "x"] ["Y" "y"]])
                                                      (properties/field-row (tr context "rotation") [(number "rotation")])
                                                      (pair "tool-scale" [["X" "scale-x"] ["Y" "scale-y"]])
                                                      (pair "size" [[(tr context "axis-width") "width"] [(tr context "axis-height") "height"]])])])
                                [(property-section context "property-mesh-tools" true
                                                   [(properties/action-grid
                                                      [(doto (button context "smart-mesh" "smart-mesh" #(trace-mesh! context))
                                                         (.setTooltip (Tooltip. (tr context "smart-mesh-help")))
                                                         (.setDisable (not (contains? #{"region" "mesh"} (:type attachment)))))
                                                       (doto (button context "auto-rig" "auto-rig" #(auto-rig! context))
                                                         (.setDisable (not (contains? #{"region" "mesh"} (:type attachment)))))
                                                       (if mesh
                                                         (restore-image-button context doc slot)
                                                         (button context "make-mesh" "make-mesh"
                                                                 #(do (update-attachment (geometry/region-to-mesh attachment 3 3))
                                                                      (swap! (:state context) assoc :tool "mesh" :vertex 0 :vertices #{} :inspector-token nil))))
                                                       (doto (button context "open-weights" "weights"
                                                                     #(do (select-tool! context "weights") (swap! (:state context) assoc :rigging-panel :weights :inspector-token nil)))
                                                         (.setDisable (not mesh)))])])]
                                (when (= "mesh" (:type attachment))
                                  [(property-section context "property-mesh-selection" false
                                                     (into [(label (str (tr context "vertices") ": " vertex-count))
                                                            (field "vertex-index" "vertex" vertex #(let [index (max 0 (min (dec vertex-count) (long %)))]
                                                                                                     (swap! (:state context) assoc :vertex index :vertices #{index} :inspector-token nil)))
                                                            (label (tr context "mesh-help"))]
                                                           (mesh-tools context doc slot field)))])] [] cat)))))
      :path
      (let [constraint (entity (data/sample-paths doc (when (= "animate" (:mode @(:state context))) (:id (animation context doc))) (:time @(:state context))) selected)
            update-constraint (fn [changes] (setup-change! context #(data/update-path % selected changes)))]
        (into [(property-section context "property-rig-control" true
                                 (rig-navigation context doc (filterv #(and (= :path (:kind %)) (= selected (:id %))) (rig-links context doc))))
               (doto (label (tr context "rig-path-follow-help")) (.setWrapText true))
               (property-section context "property-constraint-setup" false
                                 [(doto (text-field "path-name" selected #(rename-entity! context :path selected % data/update-path))
                                    (.setDisable (= "animate" (:mode @(:state context)))))
                                  (label (tr context "target-slot"))
                                  (choice "path-target" (into [] (comp (filter #(= "path" (get-in % [:attachment :type]))) (map :id)) (:slots doc)) (:target constraint) #(update-constraint {:target %}))
                                  (label (tr context "constrained-bones"))
                                  (text-field "path-bones" (coll/join-to-string ", " (:bones constraint)) #(update-constraint {:bones (into [] (comp (map string/trim) (remove string/blank?)) (string/split % #","))}))
                                  (field "path-order" "constraint-order" (:order constraint) #(update-constraint {:order (long %)}))
                                  (choice "path-position-mode" ["fixed" "percent"] (:position-mode constraint) #(update-constraint {:position-mode %}))
                                  (choice "path-spacing-mode" ["length" "fixed" "percent" "proportional"] (:spacing-mode constraint) #(update-constraint {:spacing-mode %}))
                                  (choice "path-rotate-mode" ["tangent" "chain" "chainScale"] (:rotate-mode constraint) #(update-constraint {:rotate-mode %}))
                                  (field "path-rotation" "rotation" (:rotation constraint) #(update-constraint {:rotation %}))])]
              [(property-section context "rig-advanced-follow" false
                                 (mapv (fn [property]
                                         (field (str "path-" property) property (get constraint (keyword property))
                                                #(pose-change! context (fn [d]
                                                                         (if (= "animate" (:mode @(:state context)))
                                                                           (data/set-path-keyframe d (:id (animation context d)) selected property (:time @(:state context)) %
                                                                                                   (interpolation-at (:keys (coll/first-where (fn [track] (and (= selected (:path track)) (= property (:property track)))) (:path-tracks (animation context d))))
                                                                                                                     (:time @(:state context)) (:interpolation @(:state context))))
                                                                           (data/update-path d selected {(keyword property) %}))))))
                                       ["position" "spacing" "mix-rotate" "mix-x" "mix-y"]))]))
      :transform
      (let [constraint (entity (data/sample-transforms doc (when (= "animate" (:mode @(:state context))) (:id (animation context doc)))
                                                       (:time @(:state context))) selected)
            update-constraint (fn [changes] (setup-change! context #(data/update-transform % selected changes)))
            mix-fields ["mix-rotate" "mix-x" "mix-y" "mix-scale-x" "mix-scale-y" "mix-shear-y"]]
        (into [(label (tr context "transform-constraint"))
               (doto (text-field "transform-name" selected #(rename-entity! context :transform selected % data/update-transform))
                 (.setDisable (= "animate" (:mode @(:state context)))))
               (property-section context "property-constraint-setup" false
                                 [(label (tr context "target"))
                                  (choice "transform-target" (into [] (remove (set (:bones constraint))) (mapv :id (:bones doc))) (:target constraint)
                                          #(update-constraint {:target %}))
                                  (label (tr context "constrained-bones"))
                                  (text-field "transform-bones" (coll/join-to-string ", " (:bones constraint))
                                              #(update-constraint {:bones (into [] (comp (map string/trim) (remove string/blank?)) (string/split % #","))}))
                                  (field "transform-order" "constraint-order" (:order constraint 0) #(update-constraint {:order (long %)}))
                                  (choice "transform-space" ["world" "local"] (if (:local constraint) "local" "world")
                                          #(update-constraint {:local (= "local" %)}))
                                  (choice "transform-mode" ["absolute" "relative"] (if (:relative constraint) "relative" "absolute")
                                          #(update-constraint {:relative (= "relative" %)}))])]
              (into (mapv (fn [property]
                            (field (str "transform-" property) property (get constraint (keyword property) 1.0)
                                   #(pose-change! context (fn [d]
                                                            (if (= "animate" (:mode @(:state context)))
                                                              (data/set-transform-keyframe d (:id (animation context d)) selected property
                                                                                           (:time @(:state context)) %
                                                                                           (interpolation-at (:keys (coll/first-where (fn [track] (and (= selected (:transform track)) (= property (:property track))))
                                                                                                                                      (:transform-tracks (animation context d))))
                                                                                                             (:time @(:state context)) (:interpolation @(:state context))))
                                                              (data/update-transform d selected {(keyword property) %})))))) mix-fields)
                    [(property-section context "property-constraint-offset" false
                                       (mapv (fn [property]
                                               (field (str "transform-offset-" property) property (get constraint (keyword property) 0.0)
                                                      #(update-constraint {(keyword property) %})))
                                             ["rotation" "x" "y" "scale-x" "scale-y" "shear-y"]))])))
      :ik
      (let [ik (entity (if (= "animate" (:mode @(:state context)))
                         (data/sample-ik doc (:id (animation context doc)) (:time @(:state context)))
                         (:ik doc)) selected)
            update-ik (partial update-ik-pose! context selected)]
        [(row [(label "IK")
               (button context "ik-select-target" "target"
                       #(do (set-selection! context :bone (:target ik)) (select-tool! context "move")))])
         (doto (text-field "ik-name" selected #(rename-entity! context :ik selected % data/update-ik))
           (.setDisable (= "animate" (:mode @(:state context)))))
         (property-section context "property-constraint-setup" false
                           [(label (tr context "target"))
                            (choice "ik-target" (mapv :id (:bones doc)) (:target ik)
                                    #(setup-change! context (fn [d] (data/update-ik d selected {:target %}))))
                            (label (tr context "constrained-bones"))
                            (text-field "ik-bones" (coll/join-to-string ", " (:bones ik))
                                        #(setup-change! context (fn [d] (data/update-ik d selected {:bones (into [] (comp (map string/trim) (remove string/blank?)) (string/split % #","))}))))
                            (field "ik-order" "constraint-order" (:order ik 0) #(setup-change! context (fn [d] (data/update-ik d selected {:order (long %)}))))])
         (field "ik-mix" "mix" (:mix ik) #(update-ik {:mix %}))
         (field "ik-softness" "softness" (:softness ik 0.0) #(update-ik {:softness %}))
         (property-section context "property-bone-length" true
                           [(constraint-length-controls context doc :ik selected "ik")])
         (doto (identify! (CheckBox. (tr context "uniform")) "ik-uniform")
           (.setSelected (boolean (:uniform ik)))
           (.setDisable (= "animate" (:mode @(:state context))))
           (.setOnAction (ui/event-handler event
                           (setup-change! context (fn [d] (data/update-ik d selected {:uniform (.isSelected ^CheckBox (.getSource event))}))))))
         (choice "ik-bend" ["+" "−"] (if (:bend-positive ik) "+" "−")
                 #(update-ik {:bend-positive (= "+" %)}))])
      [(label (tr context "select-help"))])))

(defn- object-inspector [context doc]
  (let [{:keys [selected-kind selected mode time]} @(:state context)
        context (assoc context :inspector-selection (select-keys @(:state context) [:mode :animation :selected-kind :selected]))
        slot (selected-slot context doc)
        controls (if (and slot (= "animate" mode))
                   [(properties/field-row (tr context "attachment")
                                          [(doto (text-field "slot-name" (or (second (:selected-attachment @(:state context))) (:id slot))
                                                             #(rename-entity! (assoc context :attachment-edit-slot selected) :slot selected % data/update-slot))
                                             (.setDisable true))])]
                   (entity-inspector-content context doc))
        controls (if (and slot (= "animate" mode)
                          (contains? #{"mesh" "path" "boundingbox" "clipping"} (get-in slot [:attachment :type]))
                          (editable-preview-attachment context doc))
                   (into controls
                         [(properties/field-row (tr context "mesh-animation")
                                                [(button context "edit-deform" "edit-mesh"
                                                         (fn edit-deform []
                                                           (swap! (:state context) assoc :tool (if (= "mesh" (get-in slot [:attachment :type])) "mesh" "path") :mesh-mode "modify"
                                                                  :property "deform" :playing false :inspector-token nil)
                                                           (when-let [root (:root context)]
                                                             (when-let [tabs (.lookup ^Node root "#skeletal-timeline-tabs")]
                                                               (.select (.getSelectionModel ^TabPane tabs) 0)))))
                                                 (properties/key-button! (button context "key-deform" "key-deform"
                                                                                 (fn key-deform []
                                                                                   (attempt! context
                                                                                             (fn record-deform []
                                                                                               (swap! (:state context) assoc :property "deform")
                                                                                               (set-key! context)))))
                                                                         (coll/any? #(and (= selected (:slot %))
                                                                                          (coll/any? (fn [key] (< (abs (- (double (:time key)) (double time))) 1.0e-6)) (:keys %)))
                                                                                    (:deforms (animation context (or (get-in @(:state context) [:pending-pose :baseline]) doc))))
                                                                         nil)])])
                   controls)
        controls (if slot
                   (into controls (slot-view/management-inspector
                                    {:document doc :animation-id (:id (animation context doc)) :slot-id selected :time time
                                     :pose-change! (fn preview-sequence [f] (pose-change! context f))
                                     :mode mode :sections {:state (:state context) :scope {:selected-kind selected-kind :mode mode} :tr #(tr context %)}
                                     :current-time (fn current-time [] (:time @(:state context))) :tr #(tr context %)
                                     :change! (fn edit-attachment [f]
                                                (if (= "setup" mode) (setup-change! context f) (animation-key-change! context f)))}))
                   controls)
        controls (if (and slot (= "animate" mode) (animation context doc))
                   (into controls
                         (slot-view/inspector {:document doc :key-document (or (get-in @(:state context) [:pending-pose :baseline]) doc) :animation-id (:id (animation context doc))
                                               :slot-id selected :time time :sections {:state (:state context) :scope {:selected-kind selected-kind :mode mode} :tr #(tr context %)}
                                               :current-time (fn current-playhead [] (:time @(:state context))) :tr #(tr context %)
                                               :pose-change! (fn preview-slot-animation [f] (pose-change! context f))
                                               :change! (fn edit-slot-animation [f] (animation-key-change! context f))}))
                   controls)]
    (if-not (and selected (not= "animate" mode) (contains? #{:bone :ik :transform :path :physics} selected-kind))
      controls
      (into controls
            [(property-section context "skin-membership" false
                               [(label (str (tr context "skin-membership") " · " (:active-skin doc "default")))
                                (doto (identify! (CheckBox. (tr context "skin-required")) "skin-required")
                                  (.setSelected (skin/required? doc selected-kind selected))
                                  (.setDisable (= "animate" mode))
                                  (.setOnAction
                                    (ui/event-handler event
                                      (let [enabled (.isSelected ^CheckBox (.getSource event))]
                                        (change! context
                                                 (fn [doc]
                                                   (skin/set-required (cond-> doc (nil? (:spine-source doc))
                                                                        (assoc :spine-source (data/export-spine-json doc)))
                                                                      selected-kind selected enabled)))))))
                                (doto (identify! (CheckBox. (tr context "skin-included")) "skin-included")
                                  (.setSelected (skin/included? doc selected-kind selected))
                                  (.setDisable (= "animate" mode))
                                  (.setOnAction
                                    (ui/event-handler event
                                      (let [enabled (.isSelected ^CheckBox (.getSource event))]
                                        (change! context
                                                 (fn [doc]
                                                   (skin/set-included (cond-> doc (nil? (:spine-source doc))
                                                                        (assoc :spine-source (data/export-spine-json doc)))
                                                                      selected-kind selected enabled)))))))])]))))

(defn- tree-attachments [context doc slot]
  (let [text (:spine-source doc)
        source (when text (preview-value context :tree-source text [] #(json/read-str text)))
        active (:active-skin doc "default")
        entries (reduce (fn collect-skin [result skin]
                          (if (or (= "default" (get skin "name")) (= active (get skin "name")))
                            (reduce-kv (fn collect-entry [result name raw]
                                         (assoc result name {:id name :slot (:id slot) :skin (get skin "name")
                                                             :kind :attachment :type (get raw "type" "region")}))
                                       result (get-in skin ["attachments" (:id slot)] {})) result))
                        {} (sort-by #(if (= active (get % "name")) 1 0) (get source "skins" [])))
        attachment (:attachment slot)
        name (get attachment :name (:id slot))
        entries (if attachment (assoc entries name (merge {:kind :attachment :id name :slot (:id slot) :skin active}
                                                          (get entries name) {:type (:type attachment)})) entries)]
    (mapv val (sort-by key entries))))

(defn- select-tree-attachment! [context {:keys [slot id skin]}]
  (let [doc (document context)
        current (entity (:slots (if (= "animate" (:mode @(:state context)))
                                  (sampled-slots context doc) doc)) slot)
        active (and (:attachment current) (= id (get-in current [:attachment :name] slot)))]
    (swap! (:state context) assoc :selected-attachment [slot id skin] :tree-selection nil
           :selected-kind (if active :slot :attachment)
           :selected (if active slot [slot id skin]) :selected-bones #{}
           :selected-slots (if active #{slot} #{}) :inspector-token nil
           :vertex 0 :vertices #{} :mesh-pivot nil :bind-bones nil
           :selected-key nil :selected-keys #{})))

(defn- sync-tree-selection! [context entries]
  (let [doc (document context)
        visible (:slots (if (= "animate" (:mode @(:state context)))
                          (sampled-slots context doc) doc))
        slots (coll/into-> visible {} (map (juxt :id identity)))
        entries (filterv #(contains? #{:bone :slot :attachment} (:kind %)) entries)]
    (when (and (= :weights (:rigging-panel @(:state context)))
               (not (:binding @(:state context)))
               (= "mesh" (get-in (selected-slot context doc) [:attachment :type]))
               (pos? (count entries)) (coll/every? #(= :bone (:kind %)) entries))
      (select-weight-bones! context (mapv :id entries)))
    (when (pos? (count entries))
      (swap! (:state context) assoc
             :tree-selection (mapv (fn tree-key [{:keys [kind id slot skin]}]
                                     [kind (if (= :attachment kind) [slot id skin] id)]) entries)
             :selected-bones (coll/into-> entries #{} (filter #(= :bone (:kind %))) (map :id))
             :selected-slots (coll/into-> entries #{}
                               (keep (fn selected-slot-id [{:keys [kind id slot]}]
                                       (case kind
                                         :slot id
                                         :attachment (let [current (get slots slot)]
                                                       (when (and (:attachment current)
                                                                  (= id (get-in current [:attachment :name] slot))) slot))
                                         nil))))))))

(defn- inactive-attachment-inspector [context doc]
  (let [[slot name skin] (:selected-attachment @(:state context))]
    (if (= "animate" (:mode @(:state context)))
      [(label (str (tr context "attachment-name") " · " name))
       (label (tr context "create-setup-only"))]
      (slot-view/management-inspector
        {:document doc :slot-id slot :selected-name name :selected-skin skin :mode "setup"
         :tr #(tr context %) :change! (fn edit-tree-attachment [f] (setup-change! context f))}))))

(defn- entity-inspector [context doc]
  (let [state (:state context)
        panel (if (:binding @state) :weights (:rigging-panel @state :properties))
        content (column
                  (case panel
                    :weights (weights-inspector context doc)
                    :skins (slot-view/skin-inspector
                             {:document doc :tr #(tr context %) :mode (:mode @state)
                              :change! (fn edit-skin [f] (change! context f))})
                    (if (= :attachment (:selected-kind @state))
                      (inactive-attachment-inspector context doc)
                      (object-inspector context doc))))
        lock (doto (identify! (ToggleButton. "") "inspector-lock")
               (.setGraphic (doto (SVGPath.)
                              (.setContent "M3,7 L3,4 C3,-1 11,-1 11,4 L11,7 M1,7 L13,7 L13,18 L1,18 Z")
                              (.setFill Color/TRANSPARENT) (.setStroke (Color/web "#cbd0d5")) (.setStrokeWidth 1.5)))
               (.setSelected (boolean (:inspector-locked @state)))
               (.setTooltip (Tooltip. (tr context "inspector-lock")))
               (.setAccessibleText (tr context "inspector-lock")))
        more (doto (identify! (MenuButton. "⋯") "inspector-menu") (.setFocusTraversable false))
        heading (doto (label (tr context "properties"))
                  (.setMaxWidth Double/MAX_VALUE) (HBox/setHgrow Priority/ALWAYS))
        header (doto (row [heading lock more]) (-> .getStyleClass (.add "inspector-heading")))
        tabs (doto (row (mapv (fn [[id title]]
                                (doto (identify! (ToggleButton. (tr context title)) (str "rigging-" (name id)))
                                  (.setSelected (= panel id))
                                  (.setFocusTraversable false)
                                  (.setMaxWidth Double/MAX_VALUE) (HBox/setHgrow Priority/ALWAYS)
                                  (.setOnAction (ui/event-handler _
                                                  (when (not= panel id) (swap! state dissoc :binding))
                                                  (swap! state assoc :rigging-panel id :inspector-token nil)
                                                  (when (and (= panel :weights) (not= id :weights))
                                                    (swap! state assoc :tool "move"))
                                                  (when (= id :weights) (select-tool! context "weights"))))))
                              [[:properties "properties"] [:weights "tool-weights"] [:skins "skins"]]))
               (-> .getStyleClass (.add "inspector-tabs")))
        animated (= "animate" (:mode @state))
        auto-key (doto (identify! (ToggleButton. "●") "inspector-auto-key")
                   (.setSelected (boolean (:auto-key @state)))
                   (.setAccessibleText (tr context "auto-key"))
                   (.setOnAction (ui/event-handler event
                                   (swap! state assoc :auto-key (.isSelected ^ToggleButton (.getSource event)) :inspector-token nil))))
        frame (doto (number-field "inspector-frame" (* (:fps @state 30.0) (:time @state 0.0))
                                  (fn set-inspector-frame [frame]
                                    (let [time (/ (max 0.0 frame) (:fps @state 30.0))]
                                      (swap! state assoc :time time :preview-time time :playing false :inspector-token nil))))
                (.setPrefWidth 54.0) (.setMinWidth 40.0) (.setMaxWidth 70.0))
        clock (doto (properties/field-row (tr context "auto-key")
                                          [auto-key (doto (label (tr context "current-frame")) (.setWrapText true) (.setMinWidth 38.0) (.setMaxWidth 58.0)) frame])
                (.setManaged (and animated (= :properties panel)))
                (.setVisible (and animated (= :properties panel))))
        body (column [clock content])
        box (doto (column [header tabs body])
              (.setId "skeletal-inspector-card")
              (-> .getStylesheets (.add (or (when-let [root (:root context)] (ui/user-data root ::inspector-stylesheet))
                                            (str (io/resource "skeletal-animation-inspector.css"))))))]
    (doseq [node [content body box]] (VBox/setVgrow node (if (= :weights panel) Priority/ALWAYS Priority/NEVER)))
    (.setDisable content (boolean (:inspector-locked @state)))
    (.setDisable clock (boolean (:inspector-locked @state)))
    (.setOnAction lock (ui/event-handler event
                         (let [locked (.isSelected ^ToggleButton (.getSource event))]
                           (swap! state assoc :inspector-locked locked)
                           (.setDisable content locked) (.setDisable clock locked))))
    (doseq [[expanded title] [[false "inspector-collapse"] [true "inspector-expand"]]]
      (let [item (MenuItem. (tr context title))]
        (.setOnAction item
                      (ui/event-handler _
                        (doseq [^Node node (.lookupAll content ".button")
                                :when (and (string/starts-with? (or (.getId node) "") "skeletal-properties-")
                                           (instance? VBox (.getParent node)))]
                          (let [^VBox parent (.getParent node)
                                children (.getChildren parent)]
                            (when (and (= 2 (.size children))
                                       (not= expanded (.isManaged ^Node (.get children 1))))
                              (.fire ^Button node))))))
        (.add (.getItems more) item)))
    [box]))

(defn- filter-tree! [^TreeItem item query]
  (let [children (filterv #(filter-tree! % query) (vec (.getChildren item)))
        matches (string/includes? (string/lower-case (str (:id (.getValue item)) " " (:label (.getValue item)))) query)]
    (when-not matches (.setAll (.getChildren item) ^java.util.Collection children))
    (when (pos? (count children)) (.setExpanded item true))
    (or matches (pos? (count children)))))

(defn- tree-structure [context doc]
  [(:name doc) (:tree-search @(:state context)) (:auto-rigs doc)
   (mapv #(select-keys % [:id :parent]) (:bones doc))
   (mapv #(vector (select-keys % [:id :bone]) (tree-attachments context doc %)) (:slots doc))
   (mapv #(mapv :id (get doc %)) [:ik :transform :path :physics :animations])
   (data/skin-names doc) (mapv :name (events/definitions doc))])

(defn- refresh-tree! [context doc]
  (let [previous (loop [pending (if-let [root (.getRoot ^TreeView (:tree context))] [root] []) result {}]
                   (if-let [^TreeItem item (peek pending)]
                     (recur (into (pop pending) (.getChildren item)) (assoc result (.getValue item) (.isExpanded item)))
                     result))
        items (atom {})
        add-bone (fn add-bone [bone]
                   (let [item (TreeItem. {:kind :bone :id (:id bone)})]
                     (swap! items assoc [:bone (:id bone)] item)
                     (.setExpanded item (get previous (.getValue item) true))
                     (.addAll (.getChildren item) ^java.util.Collection
                              (into (mapv add-bone (filterv #(= (:id bone) (:parent %)) (:bones doc)))
                                    (map (fn [slot]
                                           (let [child (TreeItem. {:kind :slot :id (:id slot)})]
                                             (swap! items assoc [:slot (:id slot)] child)
                                             (.setExpanded child (get previous (.getValue child) true))
                                             (doseq [entry (tree-attachments context doc slot)]
                                               (let [attachment (TreeItem. entry)]
                                                 (.add (.getChildren child) attachment)
                                                 (swap! items assoc [:attachment [(:id slot) (:id entry) (:skin entry)]] attachment)))
                                             child)))
                                    (filterv #(= (:id bone) (:bone %)) (:slots doc))))
                     item))
        root (TreeItem. {:kind :root :id (or (:name doc) "Skeleton")})
        selected [(:selected-kind @(:state context)) (:selected @(:state context))]]
    (.addAll (.getChildren root) ^java.util.Collection (mapv add-bone (filterv #(nil? (:parent %)) (:bones doc))))
    (doseq [ik (:ik doc)]
      (let [item (TreeItem. {:kind :ik :id (:id ik)})]
        (swap! items assoc [:ik (:id ik)] item)
        (.add (.getChildren root) item)))
    (doseq [constraint (:transform doc)]
      (let [item (TreeItem. {:kind :transform :id (:id constraint)})]
        (swap! items assoc [:transform (:id constraint)] item)
        (.add (.getChildren root) item)))
    (doseq [constraint (:path doc)]
      (let [item (TreeItem. {:kind :path :id (:id constraint)})]
        (swap! items assoc [:path (:id constraint)] item)
        (.add (.getChildren root) item)))
    (doseq [constraint (:physics doc)]
      (let [item (TreeItem. {:kind :physics :id (:id constraint)})]
        (swap! items assoc [:physics (:id constraint)] item)
        (.add (.getChildren root) item)))
    (doseq [{:keys [id parent slot bones controls target]} (rig-ui/groups doc)]
      (let [item (get @items [:path id])
            host (if parent (get @items [:bone parent]) root)
            driven (TreeItem. {:kind :group :id (str "rig-bones/" id)
                               :label (str (tr context "rig-driven-group") " · " (count bones))})
            advanced (TreeItem. {:kind :group :id (str "rig-data/" id) :label (tr context "rig-path-data")})
            move! (fn move-rig-item [^TreeItem child ^TreeItem destination]
                    (when-let [old-parent (.getParent child)] (.remove (.getChildren old-parent) child))
                    (.add (.getChildren destination) child))]
        (.setValue ^TreeItem item {:kind :path :id id :label (str slot " · " (tr context "rig-path-group"))})
        (.setExpanded ^TreeItem item (get previous (.getValue ^TreeItem item) true))
        (.setExpanded driven (get previous (.getValue driven) false))
        (.setExpanded advanced (get previous (.getValue advanced) false))
        (move! item host)
        (doseq [[index control] (mapv vector (range (count controls)) controls)]
          (let [child (get @items [:bone control])]
            (.setValue ^TreeItem child {:kind :bone :id control :label (str (tr context "rig-control-index") " " (inc index))})
            (move! child item)))
        (.add (.getChildren ^TreeItem item) driven)
        (doseq [bone bones] (move! (get @items [:bone bone]) driven))
        (.add (.getChildren ^TreeItem item) advanced)
        (let [child (get @items [:slot target])]
          (.setValue ^TreeItem child {:kind :slot :id target :label (tr context "rig-path-shape")})
          (move! child advanced))))
    (doseq [[kind title values] [[:slot "draw-order" (mapv :id (:slots doc))]
                                 [:skin "skins" (data/skin-names doc)]
                                 [:animation "animations" (mapv :id (:animations doc))]
                                 [:event "events" (mapv :name (events/definitions doc))]]]
      (let [group (TreeItem. {:kind :group :id (tr context title)})]
        (.setExpanded group (get previous (.getValue group) false))
        (.addAll (.getChildren group) ^java.util.Collection
                 (mapv (fn [id]
                         (let [item (TreeItem. (cond-> {:kind kind :id id} (= :slot kind) (assoc :draw-order true)))]
                           (swap! items #(if (contains? % [kind id]) % (assoc % [kind id] item)))
                           item)) values))
        (.add (.getChildren root) group)))
    (let [query (string/lower-case (string/trim (:tree-search @(:state context) "")))]
      (when-not (string/blank? query) (filter-tree! root query)))
    (.setExpanded root true)
    (swap! (:state context) assoc :refreshing true)
    (.setRoot ^TreeView (:tree context) root)
    (when-let [item (get @items selected)] (.select (.getSelectionModel ^TreeView (:tree context)) ^TreeItem item))
    (swap! (:state context) assoc :tree-items @items :tree-selection-token nil :refreshing false)))

(defn- single-node-timeline-tracks [context doc]
  (let [bone (selected-bone context doc)
        slot (selected-slot context doc)
        animation (animation context doc)]
    (cond-> (if (= :event (:selected-kind @(:state context)))
              (mapv (fn [definition]
                      {:event (:name definition) :property (str "◆ " (:name definition))
                       :keys (filterv #(= (:name definition) (:name %)) (if animation (events/keyframes doc (:id animation)) []))})
                    (events/definitions doc))
              (mapv (fn [property]
                      {:bone (:id bone) :property property
                       :keys (:keys (coll/first-where #(and (= (:id bone) (:bone %)) (= property (:property %))) (:tracks animation)))})
                    (if (#{:transform :ik :path :physics} (:selected-kind @(:state context))) [] transform-properties)))
      (and bone animation)
      (conj (or (coll/first-where #(= (:id bone) (:bone %)) (inherit/document-tracks doc (:id animation)))
                {:inherit (:id bone) :bone (:id bone) :property "inherit" :keys []}))
      (= :physics (:selected-kind @(:state context)))
      (into (mapv (fn [property]
                    (let [id (if (= "global" (:physics-scope @(:state context))) "" (:selected @(:state context)))]
                      {:physics id :property property
                       :keys (:keys (coll/first-where #(and (= id (:physics %)) (= property (:property %))) (:physics-tracks animation)))}))
                  (conj physics/animated-properties "reset")))
      (= :path (:selected-kind @(:state context)))
      (into (mapv (fn [property]
                    {:path (:selected @(:state context)) :property property
                     :keys (:keys (coll/first-where #(and (= (:selected @(:state context)) (:path %)) (= property (:property %))) (:path-tracks animation)))})
                  ["position" "spacing" "mix-rotate" "mix-x" "mix-y"]))
      (= :transform (:selected-kind @(:state context)))
      (into (mapv (fn [property]
                    {:transform (:selected @(:state context)) :property property
                     :keys (:keys (coll/first-where #(and (= (:selected @(:state context)) (:transform %)) (= property (:property %)))
                                                    (:transform-tracks animation)))})
                  ["mix-rotate" "mix-x" "mix-y" "mix-scale-x" "mix-scale-y" "mix-shear-y"]))
      (#{"mesh" "path" "boundingbox" "clipping"} (get-in slot [:attachment :type]))
      (as-> tracks
            ;; Put the selected attachment's FFD lane before its parent bone
            ;; channels so it remains visible in a compact timeline.
            (into [(assoc (slots/deform-track doc (:id animation) (:id slot) (:time @(:state context))) :property "deform")] tracks))
      (and slot animation)
      (as-> tracks
            (into tracks (conj (mapv #(assoc % :slot-animation true) (slots/tracks doc (:id animation) (:id slot)))
                               (assoc (slots/draw-order-track doc (:id animation)) :property "drawOrder")))
        (into tracks (coll/into-> (:sequence-tracks (entity (:animations (slots/materialize-tracks doc)) (:id animation))) []
                       (filter #(= (:id slot) (:slot %)))
                       (map #(assoc % :property (str "sequence · " (:skin %) "/" (:attachment %)))))))
      (= :ik (:selected-kind @(:state context)))
      (into (mapv (fn [property]
                    {:ik (:selected @(:state context)) :property property
                     :keys (cond->> (:keys (coll/first-where #(= (:selected @(:state context)) (:ik %)) (:ik-tracks animation)))
                             (= "IK softness" property)
                             (mapv #(assoc % :value (:softness % 0.0) :interpolation (:softness-interpolation % "linear") :curve (:softness-curve %))))})
                  ["IK mix" "IK softness"])))))

(defn- timeline-tracks [context doc]
  (let [state @(:state context)
        token [doc (:preview-attachment-key (selected-slot context doc)) (select-keys state [:animation :selected-kind :selected :selected-bones :selected-slots :physics-scope :graph-lock :graph-locked-channels :graph-locked-selection :graph-hidden-properties])]
        cached (::timeline-cache state)]
    (if (= token (:token cached))
      (:tracks cached)
      (let [tracks (cond
                     (:graph-lock state)
                     (let [locked-state (merge state (:graph-locked-selection state))]
                       (if (navigation/single-node? locked-state)
                         (single-node-timeline-tracks (assoc context :state (atom locked-state)) doc)
                         (navigation/all-tracks doc (:id (animation context doc)))))
                     (navigation/single-node? state) (single-node-timeline-tracks context doc)
                     :else (navigation/all-tracks doc (:id (animation context doc))))
            tracks (filterv #(not (contains? (:graph-hidden-properties state) (:property %))) tracks)]
        (swap! (:state context) assoc ::timeline-cache {:token token :tracks tracks})
        tracks))))

(defn- track-title [context track]
  (let [property (:property track)
        label (if (contains? #{"x" "y" "rotation" "scale-x" "scale-y" "shear-x" "shear-y"} property)
                (tr context (str "curve-" property)) property)]
    (cond
      (:deform-target track) (str (:slot track) " · " (:skin track) "/" (:attachment track) " · " (tr context "deform"))
      (:inherit track) (str (:bone track) " · " (tr context "inherit"))
      (:channel-id track) (str (:owner track) " · " label)
      :else label)))

(defn- transport! [context command]
  (if (:binding @(:state context))
    (when (= :play command) (finish-binding! context))
    (when-let [clip (animation context (document context))]
      (let [times (coll/into-> (timeline-tracks context (document context)) [] (mapcat :keys) (map :time))]
        (swap! (:state context) navigation/transport-state command (:duration clip) times)))))

(defn- install-transport-shortcuts! [context]
  (navigation/install-shortcuts! (:root context) (fn transport-command [command] (transport! context command))))

(defn- timeline-x [context time]
  (+ 180.0 (:timeline-pan @(:state context)) (* time 120.0 (:timeline-zoom @(:state context)))))

(defn- timeline-time [context x]
  (let [state @(:state context)
        duration (or (:duration (animation context (document context))) 1.0)
        value (/ (- x 180.0 (:timeline-pan state)) (* 120.0 (:timeline-zoom state)))
        fps (double (:fps state))]
    (max 0.0 (min duration (/ (Math/round (* fps value)) fps)))))

(defn- key-ref [track key]
  (cond-> {:type (cond (:inherit track) :inherit (:deform-target track) :attachment-deform (:sequence track) :sequence (:slot-animation track) :slot (:draw-order track) :draw-order (:event track) :event (:physics track) :physics (:transform track) :transform (:path track) :path (:ik track) :ik (:slot track) :deform :else :bone)
           :id (or (:inherit track) (:deform-target track) (:sequence track) (:draw-order track) (:event track) (:physics track) (:transform track) (:path track) (:ik track) (:slot track) (:bone track)) :time (double (:time key))}
    (or (:slot-animation track) (not (or (:inherit track) (:sequence track) (:draw-order track) (:event track) (:ik track) (:slot track)))) (assoc :property (:property track))))

(defn- selected-key-refs [context doc]
  (if-let [animation (animation context doc)]
    (let [valid (set (timeline/all-key-refs doc (:id animation)))]
      (filterv valid (:selected-keys @(:state context))))
    []))

(defn- copy-keys! [context]
  (attempt! context
            #(let [doc (document context) refs (selected-key-refs context doc)]
               (when (pos? (count refs))
                 (swap! (:state context) assoc :key-clipboard (timeline/copy-keys doc (:id (animation context doc)) refs))))))

(defn- paste-keys! [context]
  (when-let [clipboard (:key-clipboard @(:state context))]
    (change! context #(timeline/paste-keys % (:id (animation context %)) clipboard (:time @(:state context)) {}))))

(defn- batch-keys! [context operation remap-time]
  (attempt! context
            #(let [doc (g/node-value (:resource-node context) :document) refs (selected-key-refs context doc)]
               (when (pos? (count refs))
                 (let [updated (operation doc (:id (animation context doc)) refs)
                       valid (set (timeline/all-key-refs updated (:id (animation context doc))))
                       selected (if remap-time (into #{} (comp (map (fn [ref] (update ref :time remap-time))) (filter valid)) refs) #{})]
                   (commit! context updated)
                   (swap! (:state context) assoc :selected-keys selected :selected-key nil))))))

(defn- draw-timeline! [context]
  (let [^Canvas canvas (:timeline context) gc (.getGraphicsContext2D canvas)
        width (.getWidth canvas) height (.getHeight canvas)
        doc (g/node-value (:resource-node context) :document) state @(:state context)
        tracks (timeline-tracks context doc)]
    (.setFill gc (Color/web "#242424")) (.fillRect gc 0.0 0.0 width height)
    (.setFill gc (Color/web "#454649")) (.fillRect gc 0.0 0.0 width 28.0)
    (.setFont gc (Font/font 12.0))
    (doseq [[index title] [[0 (:id (animation context doc))] [1 (if (navigation/single-node? state) (:selected state) (tr context "all-nodes"))]]]
      (.setFill gc (Color/web "#587377")) (.fillRect gc 0.0 (+ 28.0 (* index 24.0)) 180.0 24.0)
      (.setFill gc (Color/web "#e1e6e6")) (.fillText gc (str title) (+ 8.0 (* index 10.0)) (+ 44.0 (* index 24.0)) 162.0))
    (doseq [[index track] (coll/into-> tracks [] (map-indexed vector))]
      (let [y (+ 76.0 (* 23.0 index)) center (+ y 11.0)
            color (Color/web (case (:property track) "rotation" "#80db38" "x" "#dc7373" "y" "#83c97f" "#91b4c8"))]
        (.setFill gc (Color/web (if (even? index) "#343434" "#383838"))) (.fillRect gc 0.0 y 180.0 23.0)
        (.setFill gc (Color/web "#c8c8c8")) (.fillText gc (track-title context track) 24.0 (+ y 16.0) 150.0)
        (.save gc) (.beginPath gc) (.rect gc 180.0 28.0 (max 0.0 (- width 180.0)) (- height 28.0)) (.clip gc)
        (doseq [[left right] (partitionv 2 1 (:keys track))
                :when (and (number? (:value left)) (number? (:value right)) (not= (:value left) (:value right)))
                :let [x0 (timeline-x context (:time left)) x1 (timeline-x context (:time right))]]
          (.setStroke gc color) (.setLineWidth gc 1.0)
          (.setLineDashes gc (double-array (if (= "stepped" (:interpolation left)) [2.0 3.0] [])))
          (.beginPath gc) (.moveTo gc x0 center)
          (if (= "bezier" (:interpolation left))
            (.bezierCurveTo gc (+ x0 (* 0.33 (- x1 x0))) (- center 5.0) (- x1 (* 0.33 (- x1 x0))) (+ center 5.0) x1 center)
            (.lineTo gc x1 center))
          (.stroke gc))
        (.setLineDashes gc (double-array []))
        (doseq [key (:keys track) :let [x (timeline-x context (:time key))]]
          (.setFill gc (if (contains? (:selected-keys state) (key-ref track key)) (Color/web "#bbefea") color))
          (.fillRect gc (- x 2.0) (+ y 3.0) 4.0 17.0))
        (.restore gc)))
    (let [fps (double (:fps state 60.0))
          step (max 1.0 (Math/pow 10.0 (Math/ceil (Math/log10 (/ (* fps 70.0) (* 120.0 (:timeline-zoom state)))))))
          start (Math/floor (/ (* fps (/ (- (:timeline-pan state)) (* 120.0 (:timeline-zoom state)))) step))]
      (doseq [frame (range (* start step) (+ (* start step) (* fps (/ width (* 120.0 (:timeline-zoom state)))) step) step)
              :let [x (timeline-x context (/ frame fps))] :when (<= 180.0 x width)]
        (.setStroke gc (Color/web "#343434")) (.strokeLine gc x 28.0 x height)
        (.setFill gc (Color/web "#aeb3b8")) (.fillText gc (str (long frame)) (+ x 3.0) 13.0)))
    (let [times (coll/into-> tracks #{} (mapcat #(mapv :time (:keys %))))]
      (doseq [time times :let [x (timeline-x context time)] :when (<= 180.0 x width)]
        (.setFill gc (Color/web "#e68e55"))
        (.fillPolygon gc (double-array [x (+ x 4.0) x (- x 4.0)]) (double-array [19.0 23.0 27.0 23.0]) 4)))
    (let [x (timeline-x context (:time state))]
      (when (<= 180.0 x width)
        (.setStroke gc (Color/web "#76d7d4")) (.setLineWidth gc 1.0) (.strokeLine gc x 0.0 x height)))
    (when-let [{[x0 y0] :start [x1 y1] :current} (:key-selection-box state)]
      (.setStroke gc (Color/web "#76d7d4"))
      (.strokeRect gc (min x0 x1) (min y0 y1) (abs (- x1 x0)) (abs (- y1 y0))))
    (.setStroke gc (Color/web "#50545a")) (.strokeLine gc 180.0 0.0 180.0 height)))

(defn- delete-selection! [context]
  (let [{:keys [selected-kind selected selected-bones selected-slots
                selected-attachment tree-selection mode]} @(:state context)
        tree-selection (or tree-selection (when selected-attachment [[:attachment selected-attachment]]))]
    (when (and selected (not= "animate" mode))
      (when (change! context
                     (fn delete-selected-objects [doc]
                       (let [bone-ids (if tree-selection
                                        (coll/into-> tree-selection #{} (filter #(= :bone (first %))) (map second))
                                        (cond-> (or selected-bones #{}) (= :bone selected-kind) (conj selected)))
                             slot-ids (if tree-selection
                                        (coll/into-> tree-selection #{} (filter #(= :slot (first %))) (map second))
                                        (cond-> (or selected-slots #{}) (= :slot selected-kind) (conj selected)))
                             children (coll/into-> tree-selection []
                                        (filter #(= :attachment (first %)))
                                        (map (fn attachment-key [[_ [slot name skin]]] [skin slot name])))
                             doc (if (pos? (count children)) (attachments/remove-entries doc children) doc)
                             doc (reduce (fn delete-bone [doc id]
                                           (if (entity (:bones doc) id) (data/remove-bone doc id) doc))
                                         doc (sort bone-ids))
                             doc (reduce (fn delete-slot [doc id]
                                           (if (entity (:slots doc) id) (data/remove-slot doc id) doc))
                                         doc (sort slot-ids))]
                         (case selected-kind
                           :ik (data/remove-ik doc selected)
                           :transform (data/remove-transform doc selected)
                           :path (data/remove-path doc selected)
                           :physics (data/remove-physics doc selected)
                           :event (events/remove-definition doc selected)
                           doc))))
        (if (and (= 1 (count tree-selection)) (= :attachment (ffirst tree-selection)))
          (set-selection! context :slot (first (second (first tree-selection))))
          (set-selection! context nil nil))))))

(defn- nearest-vertex [context doc x y]
  (when-let [slot (selected-slot context doc)]
    (when (and (editable-preview-attachment context doc) (#{"mesh" "path" "boundingbox" "clipping"} (get-in slot [:attachment :type])))
      (let [points (partitionv 2 (attachment-points context doc slot (:transforms (evaluated-pose context doc))))
            nearest (first (sort-by second
                                    (mapv (fn [i xy]
                                            (let [[px py] (screen context xy)]
                                              [i (Math/hypot (- x px) (- y py))]))
                                          (range (count points)) points)))]
        (when (and nearest (< (double (second nearest)) 14.0)) (first nearest))))))

(defn- drag-bone [context doc bone tool start target]
  (let [transforms (:transforms (evaluated-pose context doc))
        parent (get transforms (:parent bone) [1.0 0.0 0.0 1.0 0.0 0.0])
        axis (:axis (:drag @(:state context)))
        direction (when (some? axis) (nth (tools/axes bone transforms (:coordinate-space @(:state context) "parent") tool) axis))
        target (if (and direction (= "move" tool))
                 (let [delta (mapv - target start)
                       distance (reduce + (mapv * delta direction))]
                   (mapv + start (mapv #(* distance %) direction)))
                 target)
        p0 (inverse-point parent start)
        p1 (inverse-point parent target)
        sampled (entity (if (= "animate" (:mode @(:state context)))
                          (data/sample-pose doc (:id (animation context doc)) (:time @(:state context)))
                          (:bones doc)) (:id bone))]
    (when (and p0 p1)
      (case tool
        "rotate"
        (let [world-space (= "world" (:coordinate-space @(:state context)))
              origin (if world-space (subvec (get transforms (:id bone)) 4 6) [(:x sampled) (:y sampled)])
              [x0 y0] (mapv - (if world-space start p0) origin)
              [x1 y1] (mapv - (if world-space target p1) origin)
              angle0 (Math/atan2 y0 x0)
              angle1 (Math/atan2 y1 x1)
              initial (if world-space (:rotation (tools/displayed-transform sampled transforms "world")) (:rotation sampled))
              rotation (+ initial (or (get-in @(:state context) [:drag :rotation-delta])
                                      (Math/toDegrees (Math/atan2 (Math/sin (- angle1 angle0)) (Math/cos (- angle1 angle0))))))
              rotation (if (or world-space (= "setup" (:mode @(:state context)))) (mod rotation 360.0) rotation)
              rotation (if (:snap-rotation @(:state context)) (* 15.0 (Math/round (/ rotation 15.0))) rotation)]
          (if world-space
            (change-displayed-value (assoc context :state (atom (assoc @(:state context) :selected-bones #{} :selected-slots #{}))) doc bone "rotation" rotation)
            (change-bone-value context doc (:id bone) "rotation" rotation)))
        "shear"
        (let [origin [(:x sampled) (:y sampled)]
              [x0 y0] (mapv - p0 origin)
              [x1 y1] (mapv - p1 origin)
              delta (Math/toDegrees (- (Math/atan2 y1 x1) (Math/atan2 y0 x0)))
              property (if (= 0 axis) "shear-x" "shear-y")]
          (change-bone-value context doc (:id bone) property (+ (get sampled (keyword property) 0.0) delta)))
        "scale"
        (let [d0 (Math/hypot (- (first p0) (:x sampled)) (- (second p0) (:y sampled)))
              d1 (Math/hypot (- (first p1) (:x sampled)) (- (second p1) (:y sampled)))
              ratio (if direction
                      (let [origin (subvec (get transforms (:id bone)) 4 6)
                            before (reduce + (mapv * (mapv - start origin) direction))
                            after (reduce + (mapv * (mapv - target origin) direction))]
                        (if (> (abs before) 1.0e-8) (/ after before) 1.0))
                      (/ d1 (max 1.0 d0)))]
          (cond-> doc
            (not= 1 axis) (#(change-bone-value context % (:id bone) "scale-x" (* (:scale-x sampled) ratio)))
            (not= 0 axis) (#(change-bone-value context % (:id bone) "scale-y" (* (:scale-y sampled) ratio)))))
        (let [[dx dy] (mapv - p1 p0)
              x (+ (:x sampled) dx) y (+ (:y sampled) dy)]
          (if (= "setup" (:mode @(:state context)))
            (data/update-bone doc (:id bone) {:x x :y y})
            (-> (change-bone-value context doc (:id bone) "x" x)
                (#(change-bone-value context % (:id bone) "y" y)))))))))

(defn- ik-child-target [context doc bone]
  (let [state @(:state context)
        active (get-in (skin/activation doc) [:constraints :ik])
        ik (if (= "animate" (:mode state))
             (data/sample-ik doc (:id (animation context doc)) (:time state))
             (:ik doc))]
    (when-let [constraint (coll/first-where
                            #(and (= (:id bone) (peek (:bones %)))
                                  (contains? active (:id %)) (pos? (:mix % 1.0)))
                            (rseq (vec (sort-by :order ik))))]
      (entity (:bones doc) (:target constraint)))))

(defn- drag-ik-child [context doc bone target-bone start target]
  (if (contains? (:locked-bones @(:state context) #{}) (:id target-bone))
    doc
    (let [state @(:state context)
          axis (get-in state [:drag :axis])
          direction (when (some? axis)
                      (nth (tools/axes bone (:transforms (evaluated-pose context doc))
                                       (:coordinate-space state "parent") "move") axis))
          target (if direction
                   (let [distance (reduce + (mapv * (mapv - target start) direction))]
                     (mapv + start (mapv #(* distance %) direction))) target)
          context (assoc context :state (atom (update state :drag dissoc :axis)))]
      (drag-bone context doc target-bone "move" start target))))

(defn- drag-pose-chain [context doc bone target]
  (let [state @(:state context)
        selected (:selected-bones state)
        automatic (<= (count selected) 1)
        sampled (pose context doc)
        by-id (coll/into-> sampled {} (map (juxt :id identity)))
        children (frequencies (mapv :parent sampled))
        locked (:locked-bones state #{})
        chain (loop [id (:id bone) chain []]
                (let [current (get by-id id)]
                  (if (and current (not (contains? locked id))
                           (if automatic
                             (or (= id (:id bone))
                                 (and (pos? (:length current 0.0)) (= 1 (get children id 0))))
                             (contains? selected id)))
                    (recur (:parent current) (conj chain id))
                    (vec (rseq chain)))))
        members (set chain)]
    (when (> (count chain) 1)
      (let [solved (geometry/solve-pose-chain sampled chain target)]
        (reduce (fn apply-chain-rotation [result solved-bone]
                  (if (and (contains? members (:id solved-bone))
                           (> (abs (- (:rotation solved-bone) (:rotation (get by-id (:id solved-bone))))) 1.0e-8))
                    (change-bone-value context result (:id solved-bone) "rotation" (:rotation solved-bone))
                    result)) doc solved)))))

(defn- create-bone-drag [context doc parent start target id]
  (when-not (= "setup" (:mode @(:state context)))
    (throw (ex-info (tr context "create-setup-only") {})))
  (let [transforms (data/world-transforms (:bones doc))
        matrix (get transforms (:id parent) geometry/identity-transform)
        origin (inverse-point matrix start)
        end (inverse-point matrix target)]
    (when-not (and origin end) (throw (ex-info (tr context "singular-bone") {})))
    (let [[dx dy] (mapv - end origin)]
      (data/add-bone doc {:id id :parent (:id parent) :x (first origin) :y (second origin)
                          :rotation (Math/toDegrees (Math/atan2 dy dx)) :length (Math/hypot dx dy)}))))

(defn- creation-bone-at [context doc x y]
  (bone-at context doc (:bones doc) (data/world-transforms (:bones doc)) x y))

(defn- finish-ik! [context hit position]
  (attempt! context
            (fn finish-ik []
              (let [{:keys [document bones]} (:ik-pick @(:state context))
                    current (g/node-value (:resource-node context) :document)]
                (when-not (and (= "setup" (:mode @(:state context))) (= document current) (pos? (count bones)))
                  (throw (ex-info (tr context "ik-selection-changed") {})))
                (let [constrained (set bones)
                      existing (when-not (contains? constrained hit) hit)
                      worlds (data/world-transforms (:bones current))
                      tip-bone (when (contains? constrained hit) (entity (:bones current) hit))
                      position (if tip-bone (point (get worlds hit) [(:length tip-bone) 0.0]) position)
                      root (coll/first-where #(nil? (:parent %)) (:bones current))
                      target (or existing (unique-id (:bones current) "ik_target"))
                      xy (when-not existing (geometry/inverse-transform-point (get worlds (:id root)) position))
                      id (unique-id (:ik current) "ik")
                      child (entity (:bones current) (last bones))
                      updated (cond-> current
                                (nil? existing) (data/add-bone {:id target :parent (:id root) :x (first xy) :y (second xy) :length 0.0}))
                      updated (data/add-ik updated {:id id :bones bones :target target :mix 1.0
                                                    :bend-positive (not (neg? (Math/sin (Math/toRadians (:rotation child)))))})]
                  ;; Verify solver compatibility before the one undoable commit.
                  (data/evaluate-pose-with-slots updated nil 0.0 slots/sample-slots)
                  (commit! context updated)
                  (swap! (:state context) dissoc :ik-pick)
                  (set-selection! context :bone target)
                  (swap! (:state context) assoc :tool "move" :last-created-ik id)
                  id)))))

(defn- ik-events! [context]
  (let [^Canvas canvas (:canvas context) ^Node tree (:tree context) ^Node root (:root context)
        state (:state context)]
    (doseq [[^Node node type handler] (ui/user-data canvas ::ik-handlers)]
      (.removeEventFilter node type ^javafx.event.EventHandler handler))
    (let [pressed (ui/event-handler event
                    (when (and (:ik-pick @state) (= MouseButton/PRIMARY (.getButton ^MouseEvent event)))
                      (.consume ^MouseEvent event)
                      (.requestFocus canvas)
                      (let [doc (document context) x (.getX ^MouseEvent event) y (.getY ^MouseEvent event)]
                        (finish-ik! context (creation-bone-at context doc x y) (world context x y)))))
          tree-pressed (ui/event-handler event
                         (when (and (:ik-pick @state) (= MouseButton/PRIMARY (.getButton ^MouseEvent event)))
                           (.consume ^MouseEvent event)
                           (loop [node (.getTarget ^MouseEvent event)]
                             (when (instance? Node node)
                               (if (instance? javafx.scene.control.TreeCell node)
                                 (let [{:keys [kind id]} (.getItem ^javafx.scene.control.TreeCell node)]
                                   (when (= :bone kind) (finish-ik! context id nil)))
                                 (recur (.getParent ^Node node)))))))
          cancelled (ui/event-handler event
                      (when (and (:ik-pick @state) (= KeyCode/ESCAPE (.getCode ^javafx.scene.input.KeyEvent event)))
                        (.consume ^javafx.scene.input.KeyEvent event)
                        (swap! state dissoc :ik-pick)
                        (message! context "")))
          handlers (cond-> [[canvas MouseEvent/MOUSE_PRESSED pressed]]
                     tree (conj [tree MouseEvent/MOUSE_PRESSED tree-pressed])
                     root (conj [root javafx.scene.input.KeyEvent/KEY_PRESSED cancelled]))]
      (doseq [[^Node node type handler] handlers] (.addEventFilter node type handler))
      (ui/user-data! canvas ::ik-handlers handlers))))

(defn- creation-document [context {:keys [document bone world create-id recreate slots]} target]
  (let [affected-slots (if recreate
                         (filterv #(= (:id bone) (:bone %)) (:slots document))
                         (mapv #(entity (:slots document) %) slots))
        source-skins (when (and (pos? (count affected-slots)) (:spine-source document))
                       (get (json/read-str (:spine-source document)) "skins"))]
    ;; Preserved alternate skins need their own geometry and deform migration.
    ;; Refuse before authoring rather than silently moving hidden attachments.
    (doseq [slot affected-slots
            skin source-skins
            [name _] (get-in skin ["attachments" (:id slot)])]
      (when (not= [(get slot :spine-skin "default") (get-in slot [:attachment :name] (:id slot))]
                  [(get skin "name") name])
        (throw (ex-info (tr context "create-alternate-attachments") {:slot (:id slot)}))))
    (let [parent (if recreate (entity (:bones document) (:parent bone)) bone)
          generated (create-bone-drag context document parent world target create-id)]
      (if recreate
        (let [new-bone (entity (:bones generated) create-id)
              old-world (data/world-transforms (:bones document))
              desired (get (data/world-transforms (:bones generated)) create-id)
              replacement (assoc (constraints/compensate-bone bone desired (get old-world (:parent bone))) :length (:length new-bone))
              updated (data/update-bone document (:id bone) (select-keys replacement [:x :y :rotation :scale-x :scale-y :shear-x :shear-y :length]))]
          (compensate-edit (assoc context :state (atom (assoc @(:state context) :compensate-bones true :compensate-images true))) document updated))
        (reduce (fn reparent-created-slot [result slot-id]
                  (let [slot (entity (:slots document) slot-id)
                        attachment (:attachment slot)
                        transforms (data/world-transforms (:bones result))
                        old (get transforms (:bone slot)) new (get transforms create-id)
                        matrix (geometry/compose-transforms (or (geometry/inverse-transform new)
                                                                (throw (ex-info (tr context "singular-bone") {}))) old)
                        linear (assoc matrix 4 0.0 5 0.0)
                        updated (cond
                                  (nil? attachment) nil
                                  (:weights attachment) attachment
                                  (= "region" (:type attachment "region")) (geometry/compensate-region attachment old new)
                                  (:vertices attachment) (update attachment :vertices
                                                                 #(coll/into-> (partitionv 2 %) [] (mapcat (fn [xy] (geometry/transform-point matrix xy)))))
                                  :else (throw (ex-info (tr context "create-attachment-unsupported") {})))
                        result (data/update-slot result slot-id (cond-> {:bone create-id} updated (assoc :attachment updated)))]
                    (if (or (nil? (:vertices attachment)) (:weights attachment))
                      result
                      (update result :animations
                              (fn [animations]
                                (mapv (fn [animation]
                                        (update animation :deforms
                                                (fn [tracks]
                                                  (mapv (fn [track]
                                                          (if (not= slot-id (:slot track)) track
                                                              (update track :keys
                                                                      (fn [keys]
                                                                        (mapv #(update % :vertices
                                                                                       (fn [offsets] (coll/into-> (partitionv 2 offsets) []
                                                                                                       (mapcat (fn [xy] (geometry/transform-point linear xy)))))) keys))))) tracks)))) animations))))))
                generated slots)))))

(defn- creation-events! [context]
  (let [^Canvas canvas (:canvas context)
        state (:state context)]
    (when-let [handlers (ui/user-data canvas ::creation-handlers)]
      (doseq [[event-type handler] handlers] (.removeEventFilter canvas event-type ^javafx.event.EventHandler handler)))
    (let [pressed (ui/event-handler event
                    (when (and (= "create" (:tool @state)) (= MouseButton/PRIMARY (.getButton ^MouseEvent event)))
                      (.consume ^MouseEvent event) (.requestFocus canvas)
                      (if (not= "setup" (:mode @state))
                        (message! context (tr context "create-setup-only"))
                        (let [doc (document context) current @state
                              x (.getX ^MouseEvent event) y (.getY ^MouseEvent event)
                              parent (or (selected-bone context doc) (first (:bones doc)))
                              hit (creation-bone-at context doc x y)
                              shortcut (.isShortcutDown ^MouseEvent event)
                              recreate (.isAltDown ^MouseEvent event)
                              plan (when (and shortcut (:show-images current true))
                                     (render/render-plan (render-slots context doc (sampled-slots context doc) (evaluated-pose context doc))))
                              attachment (selection/pick-slot plan [x y])]
                          (cond
                            shortcut
                            (let [original (vec (:creation-slots current []))
                                  selected (if attachment
                                             (if (coll/any? #{attachment} original) (filterv #(not= attachment %) original) (conj original attachment)) original)]
                              (swap! state assoc :selected-attachment nil :tree-selection nil :selected-kind :bone :selected (:id parent) :selected-bones #{(:id parent)}
                                     :selected-slots (set selected) :creation-slots selected
                                     :drag {:creation true :pick true :start [x y] :original original :document doc :bone parent}))
                            (and (not recreate) hit (not= hit (:id parent)))
                            (do (set-selection! context :bone hit) (swap! state dissoc :creation-slots))
                            (and recreate (nil? (selected-bone context doc)))
                            (message! context (tr context "select-bone-first"))
                            :else
                            (let [slots (if recreate [] (vec (:creation-slots current [])))
                                  base (or (first slots) "bone")
                                  id (if (and (pos? (count slots)) (nil? (entity (:bones doc) base))) base (unique-id (:bones doc) base))]
                              (swap! state dissoc :draft :selection-box :bone-selection-box :slot-selection-box)
                              (swap! state assoc :playing false :auto-fit false
                                     :drag {:creation true :document doc :start [x y] :world (world context x y) :bone parent
                                            :create-id id :recreate recreate :slots slots :keep-parent (.isShiftDown ^MouseEvent event)})))))))
          dragged (ui/event-handler event
                    (when-let [drag (when (get-in @state [:drag :creation]) (:drag @state))]
                      (.consume ^MouseEvent event)
                      (let [x (.getX ^MouseEvent event) y (.getY ^MouseEvent event)]
                        (if (:pick drag)
                          (let [plan (render/render-plan (render-slots context (:document drag) (sampled-slots context (:document drag)) (evaluated-pose context (:document drag))))
                                hits (selection/box-slots plan (:start drag) [x y])
                                selected (coll/into-> (sort hits) (:original drag) (remove (set (:original drag))))]
                            (swap! state assoc :creation-slots selected :selected-slots (set selected)
                                   :slot-selection-box {:start (:start drag) :current [x y]}))
                          (when-let [draft (attempt! context #(creation-document context drag (world context x y)))]
                            (swap! state assoc :draft draft))))))
          released (ui/event-handler event
                     (when-let [drag (when (get-in @state [:drag :creation]) (:drag @state))]
                       (.consume ^MouseEvent event)
                       (when-not (:pick drag)
                         (let [x (.getX ^MouseEvent event) y (.getY ^MouseEvent event)
                               [sx sy] (:start drag)
                               target (if (< (Math/hypot (- x sx) (- y sy)) 3.0) (:world drag) (world context x y))]
                           (attempt! context
                                     (fn commit-created-bone []
                                       ;; Validate release; ignore stale draft.
                                       ;; Commit only a valid final position.
                                       (commit! context (creation-document context drag target))
                                       (set-selection! context :bone
                                                       (if (or (:recreate drag) (:keep-parent drag) (.isShiftDown ^MouseEvent event))
                                                         (:id (:bone drag)) (:create-id drag)))
                                       (swap! state dissoc :creation-slots)))))
                       (swap! state dissoc :draft :drag :slot-selection-box)))
          cancelled (ui/event-handler event
                      (when (and (get-in @state [:drag :creation])
                                 (= KeyCode/ESCAPE (.getCode ^javafx.scene.input.KeyEvent event)))
                        (swap! state dissoc :draft :drag :slot-selection-box)
                        (.consume ^javafx.scene.input.KeyEvent event)))
          handlers [[javafx.scene.input.KeyEvent/KEY_PRESSED cancelled] [MouseEvent/MOUSE_PRESSED pressed] [MouseEvent/MOUSE_DRAGGED dragged] [MouseEvent/MOUSE_RELEASED released]]]
      (doseq [[event-type handler] handlers] (.addEventFilter canvas event-type handler))
      (ui/user-data! canvas ::creation-handlers handlers))))

(defn- gizmo-axis [context doc x y]
  (when-let [bone (selected-bone context doc)]
    (when (#{"move" "scale" "shear"} (:tool @(:state context)))
      (let [transforms (:transforms (evaluated-pose context doc))
            [px py] (screen context (or (:pivot (mesh-manipulator context doc)) (point (get transforms (:id bone)) [0.0 0.0])))
            axes (tools/axes bone transforms (:coordinate-space @(:state context) "parent") (:tool @(:state context)))]
        (coll/first-where some?
                          (mapv (fn [index [dx dy]]
                                  (let [along (+ (* (- x px) dx) (* (- py y) dy))
                                        across (- (* (- x px) dy) (* (- py y) dx))]
                                    (when (and (< 12.0 along (+ transform-handle-length 10.0)) (< (abs across) 12.0)) index)))
                                [0 1] axes))))))

(declare key-properties! switch-workspace!)

(defn- key-edited! [context]
  (when-let [pending (tools/pending-document @(:state context) (g/node-value (:resource-node context) :document))]
    (attempt! context #(commit! context pending))))

(defn- rigging-events! [context]
  (let [state (:state context) ^Canvas canvas (:canvas context)
        ^Node tree (:tree context) ^Node root (:root context)]
    (doseq [[^Node node type handler] (ui/user-data canvas ::rigging-handlers)]
      (.removeEventFilter node type ^javafx.event.EventHandler handler))
    (let [pressed (ui/event-handler event
                    (when (= MouseButton/PRIMARY (.getButton ^MouseEvent event))
                      (let [doc (document context) current @state
                            x (.getX ^MouseEvent event) y (.getY ^MouseEvent event)
                            bone (creation-bone-at context doc x y)
                            binding (:binding current)]
                        (cond
                          binding
                          (do (.consume ^MouseEvent event) (.requestFocus canvas)
                              (if bone
                                (pick-binding! context :bone bone)
                                (when-not (:slot binding)
                                  (let [plan (render/render-plan (render-slots context doc (sampled-slots context doc) (evaluated-pose context doc)))]
                                    (when-let [slot (selection/pick-slot plan [x y])]
                                      (pick-binding! context :slot slot))))))
                          (and (= "weights" (:tool current)) bone
                               (= "mesh" (get-in (selected-slot context doc) [:attachment :type])))
                          (do (.consume ^MouseEvent event)
                              (select-weight-bones! context [bone]))))))
          tree-pressed (ui/event-handler event
                         (when (and (:binding @state) (= MouseButton/PRIMARY (.getButton ^MouseEvent event)))
                           (loop [node (.getTarget ^MouseEvent event)]
                             (when (instance? Node node)
                               (if (instance? javafx.scene.control.TreeCell node)
                                 (let [{:keys [kind id]} (.getItem ^javafx.scene.control.TreeCell node)]
                                   (.consume ^MouseEvent event)
                                   (when (#{:bone :slot} kind) (pick-binding! context kind id)))
                                 (recur (.getParent ^Node node)))))))
          escaped (ui/event-handler event
                    (when (and (:binding @state)
                               (= KeyCode/ESCAPE (.getCode ^javafx.scene.input.KeyEvent event)))
                      (.consume ^javafx.scene.input.KeyEvent event)
                      (if (and (get-in @state [:binding :slot]) (pos? (count (get-in @state [:binding :bones]))))
                        (finish-binding! context)
                        (swap! state dissoc :binding))))
          deleted (ui/event-handler event
                    (when (#{KeyCode/DELETE KeyCode/BACK_SPACE} (.getCode ^javafx.scene.input.KeyEvent event))
                      (let [target (.getTarget ^javafx.scene.input.KeyEvent event)
                            in-tree (loop [node target]
                                      (cond (instance? javafx.scene.control.TextInputControl node) false
                                            (= node tree) (some? tree)
                                            (instance? Node node) (recur (.getParent ^Node node))
                                            :else false))]
                        (when (or in-tree (and (= target canvas) (= :bone (:selected-kind @state))))
                          (.consume ^javafx.scene.input.KeyEvent event)
                          (delete-selection! context)))))
          handlers (cond-> [[canvas MouseEvent/MOUSE_PRESSED pressed]
                            [canvas javafx.scene.input.KeyEvent/KEY_PRESSED deleted]]
                     tree (conj [tree MouseEvent/MOUSE_PRESSED tree-pressed]
                                [tree javafx.scene.input.KeyEvent/KEY_PRESSED deleted])
                     root (conj [root javafx.scene.input.KeyEvent/KEY_PRESSED escaped]))]
      (doseq [[^Node node type handler] handlers] (.addEventFilter node type handler))
      (ui/user-data! canvas ::rigging-handlers handlers))))

(defn- accumulate-rotation! [context x y]
  (when-let [{:keys [rotation-basis rotation-origin rotation-last] :as drag} (:drag @(:state context))]
    (when rotation-basis
      (let [[px py] (point rotation-basis (world context x y))
            [ox oy] rotation-origin
            dx (- px ox) dy (- py oy)]
        ;; The pivot has no angle; ignore it instead of introducing a jump.
        (when (> (Math/hypot dx dy) 1.0e-8)
          (let [angle (Math/atan2 dy dx)
                delta (if rotation-last (Math/atan2 (Math/sin (- angle rotation-last)) (Math/cos (- angle rotation-last))) 0.0)]
            (swap! (:state context) update :drag assoc
                   :rotation-last angle :rotation-delta (+ (:rotation-delta drag 0.0) (Math/toDegrees delta)))))))))

(defn- flush-drag-input! [context]
  (when-let [pending (ui/user-data (:canvas context) ::drag-input)]
    (when-let [{:keys [event handler]} @pending]
      (reset! pending nil)
      (when (:drag @(:state context))
        (.handle ^javafx.event.EventHandler handler event)))))

(defn- coalesce-drag-events! [context]
  (let [^Canvas canvas (:canvas context)
        pending (atom nil)
        moved (volatile! false)
        press (.getOnMousePressed canvas)
        drag (.getOnMouseDragged canvas)
        release (.getOnMouseReleased canvas)]
    (ui/user-data! canvas ::drag-input pending)
    (.setOnMousePressed canvas
                        (ui/event-handler event
                          (reset! pending nil)
                          (vreset! moved false)
                          (.handle press event)))
    (.setOnMouseDragged canvas
                        (ui/event-handler event
                          (let [gesture (:drag @(:state context))]
                            (accumulate-rotation! context (.getX ^MouseEvent event) (.getY ^MouseEvent event))
                            ;; Remember out-and-back movement before coalescing.
                            (when (and (:secondary gesture) (not (:pan-moved gesture))
                                       (> (Math/hypot (- (.getX ^MouseEvent event) (first (:start gesture)))
                                                      (- (.getY ^MouseEvent event) (second (:start gesture)))) 3.0))
                              (swap! (:state context) assoc-in [:drag :pan-moved] true))
                            (when (and (:clear-on-click gesture)
                                       (> (Math/hypot (- (.getX ^MouseEvent event) (first (:start gesture)))
                                                      (- (.getY ^MouseEvent event) (second (:start gesture)))) 3.0))
                              (swap! (:state context) assoc-in [:drag :clear-on-click] false))
                            ;; Retain all brush and creation samples.
                            (if (and gesture (not (:brush gesture)) (not (:create-id gesture))
                                     (#{"move" "rotate" "scale" "shear" "mesh" "path" "pose"} (:tool gesture)))
                              (do (vreset! moved true)
                                  (reset! pending {:event event :handler drag})
                                  (.consume ^MouseEvent event))
                              (.handle drag event)))))
    (.setOnMouseReleased canvas
                         (ui/event-handler event
                           (accumulate-rotation! context (.getX ^MouseEvent event) (.getY ^MouseEvent event))
                           (when-let [gesture (:drag @(:state context))]
                             (when (and @moved
                                        (or @pending (not= (:last-point gesture) [(.getX ^MouseEvent event) (.getY ^MouseEvent event)])))
                               (reset! pending {:event (.copyFor ^MouseEvent event (.getSource ^MouseEvent event) (.getTarget ^MouseEvent event) MouseEvent/MOUSE_DRAGGED)
                                                :handler drag})))
                           (flush-drag-input! context)
                           (.handle release event)))))

(defn- canvas-events! [context]
  (let [^Canvas canvas (:canvas context)]
    (.setFocusTraversable canvas true)
    (.setOnMouseMoved canvas
                      (ui/event-handler event
                        (let [x (.getX ^MouseEvent event) y (.getY ^MouseEvent event)
                              doc (document context) state @(:state context)
                              plan (when (and (not (:drag state)) (:show-images state true))
                                     (render/render-plan (render-slots context doc (sampled-slots context doc) (evaluated-pose context doc))))]
                          (swap! (:state context) assoc :pointer [x y] :hover-slot (selection/pick-slot plan [x y])
                                 :hover-vertex (when (and (#{"move" "rotate" "scale" "shear" "mesh"} (:tool state)) (= "mesh" (get-in (selected-slot context doc) [:attachment :type])))
                                                 (nearest-vertex context doc x y))))))
    (.setOnMouseExited canvas
                       (ui/event-handler _ (swap! (:state context) dissoc :pointer :hover-slot :hover-vertex)))
    (.setOnMousePressed canvas
                        (ui/event-handler event
                          (.requestFocus canvas)
                          (let [doc (document context) state @(:state context)
                                x (.getX ^MouseEvent event) y (.getY ^MouseEvent event)
                                middle (= MouseButton/MIDDLE (.getButton ^MouseEvent event))
                                secondary (= MouseButton/SECONDARY (.getButton ^MouseEvent event))
                                pan (or secondary (and middle (.isAltDown ^MouseEvent event)))
                                box-select (and middle (not pan))
                                primary (= MouseButton/PRIMARY (.getButton ^MouseEvent event))
                                peer (when primary (workspace-view/hit-label (workspace-preview context doc) x y))
                                controller (when (and primary (#{"move" "pose" "rotate" "scale" "shear"} (:tool state)))
                                             (control-at context doc (:transforms (evaluated-pose context doc)) x y))
                                controller-switch (and controller (not= controller (:selected state)))
                                axis (when (and primary (not controller-switch)) (gizmo-axis context doc x y))
                                rotation-handle (and primary (not controller-switch) (rotation-handle-hit? (bone-rotation-handle context doc) x y))
                                create-bone (and primary (= "create" (:tool state)) (= "setup" (:mode state)))
                                slot (selected-slot context doc)
                                mesh-frame (when-not controller (mesh-manipulator context doc))
                                mesh-tool (and (editable-preview-attachment context doc)
                                               (or (and (= "mesh" (get-in slot [:attachment :type])) (#{"mesh" "weights"} (:tool state)))
                                                   (and (#{"path" "boundingbox" "clipping"} (get-in slot [:attachment :type])) (= "path" (:tool state)))))
                                vertex (when (and primary (not controller) (or mesh-tool (#{"move" "rotate" "scale" "shear"} (:tool state))))
                                         (nearest-vertex context doc x y))
                                other-slot (when (and primary (or mesh-frame mesh-tool) (nil? vertex) (nil? axis) (:show-images state true))
                                             (let [hit (selection/pick-slot (render/render-plan (render-slots context doc (sampled-slots context doc) (evaluated-pose context doc))) [x y])]
                                               (when (and hit (not= hit (:id slot))) hit)))
                                additive (or (.isShortcutDown ^MouseEvent event) (.isShiftDown ^MouseEvent event))
                                pivot-drag (and primary mesh-frame (#{"rotate" "scale"} (:tool state))
                                                (or (nil? vertex) (.isAltDown ^MouseEvent event))
                                                (let [[px py] (screen context (:pivot mesh-frame))]
                                                  (< (Math/hypot (- x px) (- y py)) 8.0)))
                                vertex-box (and (or mesh-frame mesh-tool) (or box-select (and primary additive (nil? vertex))))
                                mesh-transform (and primary mesh-frame (nil? vertex) (not additive) (not pivot-drag) (nil? other-slot))
                                selection (:vertices state #{})
                                brush (and primary (not additive) mesh-tool (= "weights" (:tool state)) (not= "direct" (:brush-mode state)))
                                create (and primary mesh-tool (= "mesh" (:tool state)) (= "create" (:mesh-mode state)) (nil? vertex))
                                delete (and primary mesh-tool (= "mesh" (:tool state)) (some? vertex)
                                            (or (= "delete" (:mesh-mode state)) (= 2 (.getClickCount ^MouseEvent event))))]
                            (cond
                              peer (when (not= peer (:name doc))
                                     (switch-workspace! context #(project/select % peer)))

                              (and primary (= "animate" (:mode state)) (or (:mixer-enabled state) (:mix-enabled state) (:mix-transition state)))
                              (message! context (tr context "mix-readonly"))

                              (or middle primary secondary)
                              (do
                                (when primary (swap! (:state context) assoc :playing false))
                                (cond
                                  pan nil
                                  rotation-handle nil
                                  pivot-drag nil
                                  vertex-box (swap! (:state context) assoc :selection-box {:start [x y] :current [x y] :original (if additive (:vertices state #{}) #{})}
                                                    :vertices (if additive (:vertices state #{}) #{}))
                                  mesh-transform nil
                                  box-select (swap! (:state context) assoc :bone-selection-box
                                                    {:start [x y] :current [x y] :original (if additive (:selected-bones state #{}) #{})})
                                  create-bone nil
                                  delete (do (swap! (:state context) assoc :vertices (if (contains? selection vertex) selection #{vertex}))
                                             (delete-vertices! context))
                                  create (attempt! context #(edit-mesh! context
                                                                        (fn [attachment binding]
                                                                          (mesh/insert-vertex attachment
                                                                                              (geometry/inverse-transform-point (:slot-transform binding) (world context x y))))))
                                  brush (when-let [draft (attempt! context #(brush-document context doc (world context x y) (:brush-strength state)))]
                                          (swap! (:state context) assoc :draft draft))
                                  (some? vertex) (swap! (:state context) assoc :vertex vertex
                                                        :vertices (cond additive ((if (contains? selection vertex) disj conj) selection vertex)
                                                                        (contains? selection vertex) selection
                                                                        :else #{vertex})
                                                        :inspector-token nil)
                                  other-slot (do (set-selection! context :slot other-slot)
                                                 (swap! (:state context) assoc :tool "move" :vertices #{} :inspector-token nil))
                                  mesh-tool (swap! (:state context) assoc :selection-box {:start [x y] :current [x y] :original (if additive selection #{})}
                                                   :vertices (if additive selection #{}))
                                  (and (nil? axis) (not mesh-tool))
                                  (let [transforms (:transforms (evaluated-pose context doc))
                                        hit (bone-at context doc (pose context doc) transforms x y)]
                                    (cond
                                      (and (not (false? (:show-bones state))) hit)
                                      (let [id hit previous (:selected-bones state #{})]
                                        (swap! (:state context) assoc :selected id :selected-attachment nil :tree-selection nil :selected-kind :bone)
                                        (when (or additive (not (contains? previous id)))
                                          (set-selection! context :bone id)
                                          (when additive
                                            (let [ids ((if (contains? previous id) disj conj) previous id)]
                                              (swap! (:state context) assoc :selected-bones ids
                                                     :selected (if (contains? ids id) id (first (sort ids))))))))
                                      :else
                                      (let [plan (when (:show-images state true)
                                                   (render/render-plan (render-slots context doc (sampled-slots context doc) (evaluated-pose context doc))))]
                                        (if-let [hit (selection/pick-slot plan [x y])]
                                          (let [previous (:selected-slots state #{})
                                                selected (cond additive ((if (contains? previous hit) disj conj) previous hit)
                                                               (contains? previous hit) previous
                                                               :else #{hit})]
                                            (set-selection! context :slot (if (contains? selected hit) hit (first (sort selected))))
                                            (swap! (:state context) assoc :selected-slots selected))
                                          (cond
                                            (and (not additive) (= :bone (:selected-kind state))
                                                 (selected-bone context doc)
                                                 (#{"move" "rotate" "scale" "shear"} (:tool state))) nil

                                            (or (= :bone (:selected-kind state)) (false? (:show-images state)))
                                            (swap! (:state context) assoc :bone-selection-box
                                                   {:start [x y] :current [x y] :original (if additive (:selected-bones state #{}) #{})}
                                                   :selected-bones (if additive (:selected-bones state #{}) #{}))

                                            :else
                                            (swap! (:state context) assoc :slot-selection-box
                                                   {:start [x y] :current [x y] :original (if additive (:selected-slots state #{}) #{})}
                                                   :selected-slots (if additive (:selected-slots state #{}) #{})
                                                   :selected-attachment nil :tree-selection nil :selected-kind :slot :selected nil :selected-bones #{})))))))
                                (when-not (or create delete other-slot)
                                  (swap! (:state context) assoc :drag {:document doc :start [x y] :world (world context x y)
                                                                       :clear-on-click (and primary (not additive) (not create-bone) (not brush)
                                                                                            (nil? vertex) (nil? axis) (not rotation-handle) (not pivot-drag)
                                                                                            (not controller) (not peer)
                                                                                            (or (false? (:show-bones state))
                                                                                                (nil? (bone-at context doc (pose context doc) (:transforms (evaluated-pose context doc)) x y)))
                                                                                            (or (false? (:show-images state))
                                                                                                (nil? (selection/pick-slot
                                                                                                        (render/render-plan (render-slots context doc (sampled-slots context doc) (evaluated-pose context doc))) [x y]))))
                                                                       :last-point [x y] :brush brush :axis axis :rotation-handle rotation-handle
                                                                       :pivot-drag pivot-drag :mesh-frame (when (or mesh-transform pivot-drag) mesh-frame)
                                                                       :create-id (when create-bone (unique-id (:bones doc) "bone"))
                                                                       :keep-parent (.isShiftDown ^MouseEvent event)
                                                                       :ik-target (when (and (contains? #{"move" "pose"} (:tool state))
                                                                                             (= :bone (:selected-kind @(:state context))))
                                                                                    (ik-child-target context doc (selected-bone context doc)))
                                                                       :pose-origin (when (= "pose" (:tool state))
                                                                                      (when-let [bone (selected-bone context doc)]
                                                                                        (let [[bx by] (screen context (point (get (:transforms (evaluated-pose context doc)) (:id bone)) [0.0 0.0]))]
                                                                                          (< (Math/hypot (- x bx) (- y by)) 12.0))))
                                                                       :pan pan :secondary secondary :pan-origin [(:pan-x state) (:pan-y state)]
                                                                       :vertices (:vertices @(:state context))
                                                                       :vertex vertex :mesh-mode (:mesh-mode state) :bone (selected-bone context doc) :tool (:tool state)}))))
                            (when-let [drag (:drag @(:state context))]
                              (when (and primary (= "rotate" (:tool drag)) (:bone drag)
                                         (not (:mesh-frame drag)) (nil? (:vertex drag))
                                         (not (:slot-selection-box @(:state context))) (not (:bone-selection-box @(:state context))))
                                (let [bone (:bone drag)
                                      transforms (:transforms (evaluated-pose context doc))
                                      basis (if (= "world" (:coordinate-space state)) geometry/identity-transform
                                                (geometry/inverse-transform (get transforms (:parent bone) geometry/identity-transform)))
                                      origin (when basis (point basis (point (get transforms (:id bone)) [0.0 0.0])))]
                                  (when basis
                                    (swap! (:state context) update :drag assoc :rotation-basis basis :rotation-origin origin)
                                    (accumulate-rotation! context x y)))))
                            (.consume ^MouseEvent event))))
    (.setOnMouseDragged canvas
                        (ui/event-handler event
                          (when-let [{:keys [document start pan pan-origin vertex vertices bone tool brush last-point] :as drag} (:drag @(:state context))]
                            (let [x (.getX ^MouseEvent event) y (.getY ^MouseEvent event)
                                  target (world context x y) state @(:state context)]
                              (swap! (:state context) assoc :pointer [x y] :snap-rotation (.isShiftDown ^MouseEvent event))
                              (cond
                                (:pivot-drag drag)
                                (let [frame (:mesh-frame drag)
                                      nearest (first (sort-by second (mapv (fn [position]
                                                                             (let [[px py] (screen context position)]
                                                                               [position (Math/hypot (- x px) (- y py))])) (:points frame))))
                                      position (if (and nearest (< (second nearest) 10.0)) (first nearest) target)]
                                  (swap! (:state context) assoc :mesh-pivot {:token (:token frame) :point position}))
                                pan (when (or (:pan-moved drag) (> (Math/hypot (- x (first start)) (- y (second start))) 3.0))
                                      (swap! (:state context) assoc
                                             :auto-fit false :pan-x (+ (first pan-origin) (- x (first start)))
                                             :pan-y (+ (second pan-origin) (- y (second start)))))
                                (:slot-selection-box state)
                                (let [{start :start original :original} (:slot-selection-box state)
                                      plan (if (:show-images state true)
                                             (render/render-plan (render-slots context document (sampled-slots context document) (evaluated-pose context document))) [])
                                      hits (into original (selection/box-slots plan start [x y]))]
                                  (swap! (:state context) assoc :selected-slots hits :selected-attachment nil :tree-selection nil :selected-kind :slot :selected (first (sort hits))
                                         :slot-selection-box {:start start :current [x y] :original original}))
                                (:bone-selection-box state)
                                (let [{[sx sy] :start original :original} (:bone-selection-box state)
                                      transforms (:transforms (evaluated-pose context document))
                                      hits (into original (keep (fn [bone]
                                                                  (let [[px py] (screen context (point (get transforms (:id bone)) [0.0 0.0]))]
                                                                    (when (and (<= (min sx x) px (max sx x)) (<= (min sy y) py (max sy y))) (:id bone))))) (:bones document))]
                                  (swap! (:state context) assoc :selected-bones hits :selected-attachment nil :tree-selection nil :selected-kind :bone :selected (first (sort hits))
                                         :bone-selection-box {:start [sx sy] :current [x y] :original original}))
                                (:selection-box state)
                                (let [{:keys [start original]} (:selection-box state)
                                      [sx sy] start
                                      slot (selected-slot context document)
                                      points (partitionv 2 (attachment-points context document slot (:transforms (evaluated-pose context document))))
                                      hits (into original
                                                 (keep-indexed (fn [index xy]
                                                                 (let [[px py] (screen context xy)]
                                                                   (when (and (<= (min sx x) px (max sx x)) (<= (min sy y) py (max sy y))) index)))) points)]
                                  (swap! (:state context) assoc :vertices hits :vertex (or (first (sort hits)) 0)
                                         :selection-box (assoc (:selection-box state) :current [x y])))
                                :else
                                (attempt! context
                                          (fn []
                                            (let [changed (cond
                                                            (:mesh-frame drag)
                                                            (transform-vertices context document (get-in drag [:mesh-frame :selection])
                                                                                (mesh-transform-matrix (:mesh-frame drag) tool (:world drag) target (:axis drag) (.isShiftDown ^MouseEvent event)))
                                                            (:create-id drag)
                                                            (create-bone-drag context document bone (:world drag) target (:create-id drag))
                                                            (:ik-target drag)
                                                            (drag-ik-child context document bone (:ik-target drag) (:world drag) target)
                                                            (and bone (= "pose" tool))
                                                            (if (:pose-origin drag)
                                                              (drag-bone context document bone "move" (:world drag) target)
                                                              (if-let [solved (drag-pose-chain context document bone target)]
                                                                solved
                                                                (let [transforms (:transforms (evaluated-pose context document))
                                                                      origin (point (get transforms (:id bone)) [0.0 0.0])
                                                                      angle (Math/toDegrees (Math/atan2 (- (second target) (second origin)) (- (first target) (first origin))))]
                                                                  (change-displayed-value (assoc context :state (atom (assoc state :coordinate-space "world"))) document bone "rotation" angle))))
                                                            (and (#{"move" "rotate" "scale" "shear" "mesh" "path"} tool)
                                                                 (or (not= "mesh" tool) (not= "create" (:mesh-mode drag)))
                                                                 (some? vertex) (contains? vertices vertex))
                                                            (drag-vertices context document vertices (:world drag) target)
                                                            brush
                                                            (brush-document context (or (:draft state) document) target
                                                                            (* (:brush-strength state) (min 1.0 (/ (Math/hypot (- x (first last-point)) (- y (second last-point)))
                                                                                                                   (max 1.0 (* 0.25 (:brush-radius state)))))))
                                                            (and (= "weights" tool) (some? vertex))
                                                            (let [slot (selected-slot context document)
                                                                  bone-id (or (:weight-bone state) (:bone slot))
                                                                  delta (/ (- (second start) y) 200.0)]
                                                              (if (contains? (:locked-bones state) bone-id)
                                                                document
                                                                (reduce (fn [doc index]
                                                                          (let [weight (get (coll/first-where #(= bone-id (:bone %))
                                                                                                              (get-in slot [:attachment :weights index])) :weight
                                                                                            (if (= bone-id (:bone slot)) 1.0 0.0))]
                                                                            (apply-weight! context doc index bone-id (max 0.0 (min 1.0 (+ weight delta))))))
                                                                        document vertices)))
                                                            (and bone (#{"move" "rotate" "scale" "shear"} tool))
                                                            (transform-group context document bone (drag-bone context document bone tool (:world drag) target)))]
                                              (when changed (swap! (:state context) assoc :draft (compensate-edit context document changed)))))))
                              (swap! (:state context) assoc-in [:drag :last-point] [x y])
                              (.consume ^MouseEvent event)))))
    (.setOnMouseReleased canvas
                         (ui/event-handler event
                           (let [state @(:state context) drag (:drag state)]
                             (when (and (:secondary drag) (not (:pan-moved drag))
                                        (<= (Math/hypot (- (.getX ^MouseEvent event) (first (:start drag)))
                                                        (- (.getY ^MouseEvent event) (second (:start drag)))) 3.0))
                               (if (and (= "mesh" (:tool state)) (= "setup" (:mode state)))
                                 (swap! (:state context) assoc :mesh-mode
                                        (case (:mesh-mode state "modify") "modify" "create" "create" "delete" "modify")
                                        :inspector-token nil)
                                 (select-tool! context
                                               (if (and (= "animate" (:mode state)) (= "create" (:previous-tool state)))
                                                 "move" (:previous-tool state "rotate")))))
                             (when-let [id (:create-id drag)]
                               (attempt! context #(commit! context (or (:draft state)
                                                                       (create-bone-drag context (:document drag) (:bone drag) (:world drag) (:world drag) id))))
                               (set-selection! context :bone (if (:keep-parent drag) (:id (:bone drag)) id)))
                             (when (and (= "mesh" (:tool drag)) (= "create" (:mesh-mode drag)) (some? (:vertex drag)))
                               (when-let [target (nearest-vertex context (document context) (.getX ^MouseEvent event) (.getY ^MouseEvent event))]
                                 (when (not= target (:vertex drag))
                                   (attempt! context #(edit-mesh! context (fn [attachment _] (mesh/connect-vertices attachment [(:vertex drag) target])))))))
                             (when-not (:create-id drag)
                               (when-let [draft (:draft state)] (attempt! context #(commit-pose! context draft))))
                             (when (and (:clear-on-click drag) (nil? (:draft state))
                                        (<= (Math/hypot (- (.getX ^MouseEvent event) (first (:start drag)))
                                                        (- (.getY ^MouseEvent event) (second (:start drag)))) 3.0))
                               (set-selection! context nil nil)
                               (swap! (:state context) assoc :weight-slot nil :weight-bone nil :weight-bones #{} :tree-selection-token nil)))
                           (swap! (:state context) dissoc :drag :selection-box :bone-selection-box :slot-selection-box)
                           (.consume ^MouseEvent event)))
    (.setOnScroll canvas
                  (ui/event-handler event
                    (let [x (.getX ^ScrollEvent event) y (.getY ^ScrollEvent event)
                          [wx wy] (world context x y)
                          zoom (max 0.05 (min 20.0 (* (:zoom @(:state context)) (Math/pow 1.0015 (.getDeltaY ^ScrollEvent event)))))]
                      (swap! (:state context) assoc :auto-fit false :zoom zoom :pan-x (- x (* wx zoom)) :pan-y (+ y (* wy zoom))))
                    (.consume ^ScrollEvent event)))
    (.setOnKeyPressed canvas
                      (ui/event-handler event
                        (let [state @(:state context)
                              mesh-tool (or (mesh-manipulator context (document context)) (#{"mesh" "weights" "path"} (:tool state)))
                              code (.getCode ^javafx.scene.input.KeyEvent event)]
                          (cond
                            (.isDigitKey code)
                            (let [digit (.getName code)]
                              (if (.isShortcutDown ^javafx.scene.input.KeyEvent event)
                                (swap! (:state context) assoc-in [:selection-groups digit] (select-keys state [:selected-kind :selected :selected-bones :selected-slots :selected-attachment :tree-selection]))
                                (when-let [saved (get-in state [:selection-groups digit])]
                                  (swap! (:state context) #(merge % {:selected-attachment nil :tree-selection nil :selected-bones #{} :selected-slots #{}} saved)))))
                            (#{KeyCode/PAGE_UP KeyCode/PAGE_DOWN} code)
                            (let [history (:selection-history state [])
                                  index (max 0 (min (dec (count history)) (+ (:selection-index state (dec (count history))) (if (= KeyCode/PAGE_UP code) -1 1))))]
                              (when (pos? (count history))
                                (swap! (:state context) #(merge % {:selected-attachment nil :tree-selection nil :selected-bones #{} :selected-slots #{}} (nth history index) {:selection-index index :selection-replay true}))))
                            (and (not mesh-tool) (= KeyCode/A code) (.isShortcutDown ^javafx.scene.input.KeyEvent event))
                            (swap! (:state context) assoc :selected-bones (into #{} (map :id) (:bones (document context))))
                            (and mesh-tool (= KeyCode/A code) (.isShortcutDown ^javafx.scene.input.KeyEvent event))
                            (swap! (:state context) assoc :vertices (set (range (quot (count (get-in (selected-slot context (document context)) [:attachment :vertices])) 2))))
                            (= KeyCode/ESCAPE code) (do (swap! (:state context) dissoc :draft :drag :selection-box :bone-selection-box :slot-selection-box :creation-slots) (set-selection! context nil nil))
                            (and (= KeyCode/L code) (.isShortcutDown ^javafx.scene.input.KeyEvent event) (.isShiftDown ^javafx.scene.input.KeyEvent event))
                            (key-properties! context (mapv :property (timeline-tracks context (document context))))
                            (and (= KeyCode/K code) (not (.isShortcutDown ^javafx.scene.input.KeyEvent event))) (key-edited! context)
                            (and (not (.isShortcutDown ^javafx.scene.input.KeyEvent event))
                                 (contains? {KeyCode/C "rotate" KeyCode/V "move" KeyCode/X "scale" KeyCode/Z "shear" KeyCode/G "weights" KeyCode/B "pose" KeyCode/N "create"} code))
                            (select-tool! context (get {KeyCode/C "rotate" KeyCode/V "move" KeyCode/X "scale" KeyCode/Z "shear" KeyCode/G "weights" KeyCode/B "pose" KeyCode/N "create"} code))
                            (#{KeyCode/DELETE KeyCode/BACK_SPACE} code) (cond (= "mesh" (:tool state)) (delete-vertices! context)
                                                                              (#{"weights" "path"} (:tool state)) nil
                                                                              (mesh-manipulator context (document context)) nil
                                                                              :else (delete-selection! context))
                            :else nil)))))
  (creation-events! context)
  (rigging-events! context)
  (ik-events! context)
  (coalesce-drag-events! context))

(defn- timeline-events! [context]
  (let [^Canvas canvas (:timeline context)]
    (.setFocusTraversable canvas true)
    (.setOnMousePressed canvas
                        (ui/event-handler event
                          (.requestFocus canvas)
                          (let [x (.getX ^MouseEvent event) y (.getY ^MouseEvent event)
                                pan (#{MouseButton/MIDDLE MouseButton/SECONDARY} (.getButton ^MouseEvent event))
                                additive (or (.isShortcutDown ^MouseEvent event) (.isShiftDown ^MouseEvent event))
                                doc (g/node-value (:resource-node context) :document)
                                track (get (timeline-tracks context doc) (long (Math/floor (/ (- y 76.0) 23.0))))
                                key (when track (coll/first-where #(< (Math/abs (- x (timeline-x context (:time %)))) 7.0) (:keys track)))
                                ref (when key (key-ref track key))
                                selection (set (selected-key-refs context doc))
                                box (and (not pan) (nil? key) (> y 76.0))
                                time (timeline-time context x)]
                            (when (#{MouseButton/PRIMARY MouseButton/MIDDLE MouseButton/SECONDARY} (.getButton ^MouseEvent event))
                              (swap! (:state context) assoc :timeline-drag {:x x :y y :scroll-origin (.getVvalue ^ScrollPane (:timeline-scroll context)) :pan pan :box box :origin (:timeline-pan @(:state context)) :track track :key key})
                              (when-not pan
                                (swap! (:state context) assoc :playing false :mode "animate" :time (if key (:time key) time)
                                       :preview-time (if key (:time key) time) :mix-transition nil
                                       :selected-key (when key [(navigation/channel-id track) (:time key)])
                                       :selected-keys (cond (and ref additive) ((if (contains? selection ref) disj conj) selection ref)
                                                            (contains? selection ref) selection
                                                            ref #{ref}
                                                            additive selection
                                                            :else #{}))
                                (when-let [name (:event track)]
                                  (swap! (:state context) assoc :selected-kind :event :selected name))
                                (when box
                                  (swap! (:state context) assoc :key-selection-box {:start [x y] :current [x y] :original (if additive selection #{})})))))
                          (.consume ^MouseEvent event)))
    (.setOnMouseDragged canvas
                        (ui/event-handler event
                          (when-let [{:keys [x y pan box origin scroll-origin]} (:timeline-drag @(:state context))]
                            (cond
                              pan (do (swap! (:state context) assoc :timeline-pan (+ origin (- (.getX ^MouseEvent event) x)))
                                      (let [scroll ^ScrollPane (:timeline-scroll context)
                                            span (max 1.0 (- (.getHeight canvas) (.getHeight (.getViewportBounds scroll))))]
                                        (.setVvalue scroll (max 0.0 (min 1.0 (- scroll-origin (/ (- (.getY ^MouseEvent event) y) span)))))))
                              box (let [[x0 y0] (:start (:key-selection-box @(:state context)))
                                        x1 (.getX ^MouseEvent event) y1 (.getY ^MouseEvent event)
                                        refs (coll/into-> (timeline-tracks context (document context)) #{}
                                               (comp (map-indexed vector)
                                                     (mapcat (fn [[index track]]
                                                               (let [y (+ 87.0 (* 23.0 index))]
                                                                 (coll/into-> (:keys track) []
                                                                   (comp (filter #(and (<= (min y0 y1) y (max y0 y1))
                                                                                       (<= (min x0 x1) (timeline-x context (:time %)) (max x0 x1))))
                                                                         (map #(key-ref track %)))))))))]
                                    (swap! (:state context) #(-> % (assoc-in [:key-selection-box :current] [x1 y1])
                                                                 (assoc :selected-keys (into (get-in % [:key-selection-box :original]) refs)))))
                              (> (Math/abs (- (.getX ^MouseEvent event) x)) 3.0)
                              (do (swap! (:state context) assoc :time (timeline-time context (.getX ^MouseEvent event)))
                                  (swap! (:state context) assoc-in [:timeline-drag :moved] true))))))
    (.setOnMouseReleased canvas
                         (ui/event-handler _
                           (when-let [{:keys [pan track key moved]} (:timeline-drag @(:state context))]
                             (when (and key moved (not pan) (not= (:time key) (:time @(:state context))))
                               (attempt! context
                                         (fn []
                                           (let [doc (g/node-value (:resource-node context) :document) id (:id (animation context doc)) time (:time @(:state context))
                                                 refs (selected-key-refs context doc) delta (- time (:time key))]
                                             (commit! context (timeline/move-keys doc id refs delta {}))
                                             (swap! (:state context) assoc :selected-key [(:property track) time]
                                                    :selected-keys (into #{} (map #(update % :time + delta)) refs))))))
                             (swap! (:state context) dissoc :timeline-drag :key-selection-box))))
    (.setOnKeyPressed canvas
                      (ui/event-handler event
                        (let [code (.getCode ^javafx.scene.input.KeyEvent event)
                              shortcut (.isShortcutDown ^javafx.scene.input.KeyEvent event)]
                          (cond
                            (and shortcut (= KeyCode/A code))
                            (swap! (:state context) assoc :selected-keys
                                   (into #{} (mapcat (fn [track] (mapv #(key-ref track %) (:keys track)))) (timeline-tracks context (document context))))
                            (and shortcut (= KeyCode/C code)) (copy-keys! context)
                            (and shortcut (= KeyCode/V code)) (paste-keys! context)
                            (#{KeyCode/DELETE KeyCode/BACK_SPACE} code) (batch-keys! context timeline/delete-keys nil)
                            (= KeyCode/ESCAPE code) (swap! (:state context) assoc :selected-keys #{} :selected-key nil)
                            :else nil)
                          (when (or (and shortcut (#{KeyCode/A KeyCode/C KeyCode/V} code)) (#{KeyCode/DELETE KeyCode/BACK_SPACE KeyCode/ESCAPE} code))
                            (.consume ^javafx.scene.input.KeyEvent event)))))
    (.setOnScroll canvas
                  (ui/event-handler event
                    (when (> (.getX ^ScrollEvent event) 180.0)
                      (if (or (.isShiftDown ^ScrollEvent event) (> (abs (.getDeltaX ^ScrollEvent event)) (abs (.getDeltaY ^ScrollEvent event))))
                        (swap! (:state context) update :timeline-pan + (.getDeltaX ^ScrollEvent event)
                               (if (.isShiftDown ^ScrollEvent event) (.getDeltaY ^ScrollEvent event) 0.0))
                        (swap! (:state context)
                               (fn zoom-timeline [state]
                                 (let [x (.getX ^ScrollEvent event)
                                       time (/ (- x 180.0 (:timeline-pan state)) (* 120.0 (:timeline-zoom state)))
                                       zoom (max 0.05 (min 100.0 (* (:timeline-zoom state) (Math/exp (* 0.002 (.getDeltaY ^ScrollEvent event))))))]
                                   (assoc state :timeline-zoom zoom :timeline-pan (- x 180.0 (* time 120.0 zoom)))))))
                      (.consume ^ScrollEvent event))))))

(defn- ensure-animation [doc]
  (if (seq (:animations doc)) doc (data/add-animation doc {:id "default" :duration 1.0 :tracks []})))

(defn- background-io! [context task complete]
  (when-not (:busy @(:state context))
    (swap! (:state context) assoc :busy true :playing false)
    (.setDisable ^Node (:root context) true)
    (message! context (tr context "converting"))
    (future
      (let [result (try {:result (task)} (catch Exception exception {:error exception}))]
        (ui/run-later
          (swap! (:state context) assoc :busy false)
          (.setDisable ^Node (:root context) false)
          (when (.getScene ^Node (:root context))
            (if-let [error (:error result)]
              (message! context (.getMessage ^Exception error))
              (try
                (message! context "")
                (complete (:result result))
                (catch Exception exception (message! context (.getMessage exception)))))))))))

(defn- trace-mesh! [context]
  (attempt! context
            (fn open-trace []
              (when-not (= "setup" (:mode @(:state context)))
                (throw (ex-info (tr context "mesh-setup-only") {})))
              (let [before (document context)
                    slot (selected-slot context before)
                    slot-id (:id slot)
                    file (io/file (:project-root context) (string/replace (get-in slot [:attachment :path]) #"^/" ""))]
                (trace-view/show!
                  {:host (:viewport context)
                   :image-url (str (.toURI file))
                   :breadcrumb (str "root  ›  " (:bone slot) "  ›  " slot-id "  ›  " (get-in slot [:attachment :name] slot-id))
                   :revise (fn revise-trace [result uvs] (smart-mesh/revise-trace result slot-id uvs))
                   :settings (:trace-settings @(:state context))
                   :tr #(tr context %)
                   :propose (smart-mesh/trace-proposer before (:project-root context) slot-id)
                   :apply-result (fn apply-trace [result settings]
                                   (when-not (= before (document context))
                                     (throw (ex-info (tr context "trace-document-changed") {})))
                                   (commit! context (:document result))
                                   (swap! (:state context) assoc :trace-settings settings :tool "move" :vertices #{} :vertex 0 :inspector-token nil)
                                   (message! context (str (tr context "vertices") ": " (get-in result [:report "vertices"])
                                                          " · " (tr context "remesh-preserved") " " (get-in result [:report "deform_keys_preserved"] 0)
                                                          " · " (tr context "remesh-boundary") " " (get-in result [:report "outside_vertices"] 0))))})))))

(defn- auto-rig! [context]
  (attempt! context
            (fn open-auto-rig []
              (when-not (= "setup" (:mode @(:state context)))
                (throw (ex-info (tr context "mesh-setup-only") {})))
              (let [before (document context)
                    slot (selected-slot context before)
                    slot-id (:id slot)
                    file (io/file (:project-root context) (string/replace (get-in slot [:attachment :path]) #"^/" ""))]
                (trace-view/show!
                  {:host (:viewport context) :rig-mode true
                   :image-url (str (.toURI file)) :breadcrumb (str slot-id " · " (tr context "auto-rig-tail"))
                   :settings (merge auto-rig/defaults (:trace-settings @(:state context)))
                   :tr #(tr context %) :propose (auto-rig/proposer before (:project-root context) slot-id)
                   :apply-result (fn apply-auto-rig [result _]
                                   (when-not (= before (document context))
                                     (throw (ex-info (tr context "trace-document-changed") {})))
                                   (commit! context (:document result))
                                   (swap! (:state context) assoc :tool "move" :vertices #{} :vertex 0 :inspector-token nil
                                          :selected-kind :bone :selected (peek (:rig-control-ids result))
                                          :selected-bones #{(peek (:rig-control-ids result))} :selected-slots #{} :selected-attachment nil)
                                   (message! context (tr context "auto-rig-complete")))})))))

(defn- finish-import! [context before result]
  (when-not (= before (document context))
    (throw (ex-info "The document changed during import. Retry after saving your edits." {})))
  (commit! context (ensure-animation (:document result)))
  (workspace/resource-sync! (:workspace context))
  (reset! (:images context) {})
  (swap! (:state context) assoc :fitted false :auto-fit true :playing false :time 0.0
         :selected-attachment nil :tree-selection nil :selected-kind :bone :selected (:id (first (:bones (:document result))))
         :animation (:id (first (:animations (:document result))))
         :selected-key nil :selected-keys #{} :vertices #{} :vertex 0 :edge-start nil :inspector-token nil
         :locked-bones #{} :weight-bone nil :weight-picked-bones nil :weight-slot nil :bind-bones nil :physics-scope "constraint")
  (message! context (str (when (get-in result [:document :native-project]) (str (if-let [version (get-in result [:document :native-project :version])] (str "Spine " version) (tr context "native-notice")) " · "))
                         (count (:warnings result)) " · " (tr context "warnings"))))

(defn- import-file! [context ^File file]
  (let [before (document context)]
    (if-not (string/ends-with? (string/lower-case (.getName file)) ".spine")
      (background-io! context #(animation-io/read-spine-file! file (:project-root context))
                      #(finish-import! context before %))
      (background-io! context #(try (animation-io/read-independent-project! file)
                                    (catch clojure.lang.ExceptionInfo error
                                      (if (= :editor.skeletal-animation-native/unsupported (:type (ex-data error)))
                                        {:smart-mesh-import (smart-mesh/open-project! file (:project-root context))}
                                        (throw error))))
                      (fn [project]
                        (if-let [result (:smart-mesh-import project)]
                          (finish-import! context before result)
                          (let [skeletons (:skeletons project)
                                names (mapv :name skeletons)
                                selected (if (= 1 (count names)) (first names)
                                             (first (dialogs/make-select-list-dialog names (:localization context)
                                                                                     {:title (tr context "select-skeleton")})))]
                            (when selected
                              (background-io! context
                                              (fn import-workspace []
                                                (let [results (mapv #(animation-io/read-independent-skeleton! file (:project-root context) project %) names)
                                                      skeletons (mapv (fn [result] {:name (get-in result [:document :name]) :document (:document result)}) results)]
                                                  {:document (project/assemble skeletons selected) :warnings (coll/into-> results [] (mapcat :warnings))}))
                                              #(finish-import! context before %))))))))))

(defn- switch-workspace! [context transform]
  (attempt! context
            (fn switch-skeleton []
              (let [document (transform (document context))
                    show-peers (:show-workspace-peers @(:state context))]
                (swap! (:state context) assoc :mode "setup" :playing false :mix-enabled false :mix-transition nil :mix-state nil :mixer-enabled false)
                (commit! context document)
                (reset! (:pose-cache context) nil)
                (reset! (:mix-cache context) nil)
                (reset! (:ghost-cache context) nil)
                (reset! (:workspace-cache context) nil)
                ((:stop! (:audio context)))
                (swap! (:state context) assoc :time 0.0 :preview-time 0.0 :animation (:id (first (:animations document)))
                       :selected-attachment nil :tree-selection nil :selected-kind :bone :selected (:id (first (:bones document))) :selected-keys #{} :selected-key nil
                       :vertices #{} :vertex 0 :event-log [] :inspector-token nil :last-document nil :auto-fit (nil? (:editor-view document)) :fitted (boolean (:editor-view document)))
                (swap! (:state context) merge (project/view-settings document)
                       {:show-workspace-peers show-peers :selected-bones #{(:id (first (:bones document)))} :locked-bones #{} :bind-bones nil :weight-bone nil :weight-picked-bones nil :weight-slot nil
                        :mirror-bones "" :selection-groups {} :selection-history [] :selection-index -1 :selection-replay false
                        :selection-box nil :bone-selection-box nil :drag nil :edge-start nil :timeline-pan 0.0 :timeline-zoom 1.0})
                (swap! (:state context) assoc :selection-history [] :selection-index -1)))))

(defn- export-preview! [context destination format width height]
  (let [doc (document context) state @(:state context)
        clip (animation context doc)
        animation (:id clip)
        duration (:duration clip)
        canvas ^Canvas (:canvas context)
        scale (min (/ width (.getWidth canvas)) (/ height (.getHeight canvas)))
        frozen (assoc context :state (atom (assoc state :mode "animate" :playing false :animation animation
                                                  :zoom (* scale (:zoom state)) :pan-x (* scale (:pan-x state)) :pan-y (* scale (:pan-y state))))
                      :pose-cache (atom nil))]
    (background-io! context
                    (fn export-frames []
                      (if (= "atlas" format)
                        (atlas/export! doc (:project-root context) (io/file (:project-root context) destination) {:page-width width :page-height height :padding 2 :extrude 1})
                        (preview-export/export! (io/file (:project-root context) destination)
                                                {:format (keyword format) :duration duration :fps (:fps state) :width width :height height :animation animation}
                                                (fn render-frame [time]
                                                  (swap! (:state frozen) assoc :time time)
                                                  (let [sampled (slots/sample-slots doc animation time)
                                                        result (data/evaluate-pose-with-slots-state doc animation time @(:pose-cache frozen) slots/sample-slots)]
                                                    (reset! (:pose-cache frozen) (:state result))
                                                    (render/render-image width height (render-slots frozen doc sampled (:pose result))))))))
                    (fn preview-exported [result]
                      (workspace/resource-sync! (:workspace context))
                      (message! context (str (tr context "exported") " " (:directory result)))))))

(defn- save-native-project! [context destination]
  (let [doc (g/node-value (:resource-node context) :document)
        chooser (doto (FileChooser.)
                  (.setTitle (tr context "save-spine"))
                  (.setInitialFileName (str (:name doc) ".spine")))]
    (.add (.getExtensionFilters chooser) (FileChooser$ExtensionFilter. "Spine project" (java.util.ArrayList. ["*.spine"])))
    (when-let [file (if (string/ends-with? (string/lower-case destination) ".spine")
                      (let [file (io/file destination)]
                        (if (.isAbsolute file) file (io/file (:project-root context) destination)))
                      (.showSaveDialog chooser (.getWindow (.getScene ^Node (:root context)))))]
      (background-io! context #(cond
                                 (smart-mesh/requires-cli? doc) (smart-mesh/write-project! doc (:project-root context) file)
                                 (contains? doc :workspace-skeletons) (project/write-native! doc (:project-root context) file)
                                 :else (animation-io/write-independent-project! doc (:project-root context) file))
                      (fn [output]
                        (workspace/resource-sync! (:workspace context))
                        (message! context (str (tr context "exported") " " output)))))))

(defn- set-key! [context]
  (when (or (navigation/single-node? @(:state context))
            (and (:selected @(:state context))
                 (contains? (set transform-properties) (:property @(:state context)))))
    (let [doc (ensure-animation (document context)) bone (selected-bone context doc) state @(:state context)
          slot (selected-slot context doc)]
      (when (or bone (= :event (:selected-kind state)))
        (change! (assoc context :preserve-pending true)
                 (fn [base]
                   (let [preview doc
                         doc (ensure-animation base)]
                     (cond
                       (= "inherit" (:property state))
                       (slots/set-inherit-keyframe doc (:id (animation context doc)) (:id bone) (:time state)
                                                   (:inherit (entity (data/sample-pose preview (:id (animation context doc)) (:time state)) (:id bone)) "normal"))
                       (and slot (:sequence (coll/first-where #(= (:property state) (:property %)) (timeline-tracks context doc))))
                       (let [track (coll/first-where #(= (:property state) (:property %)) (timeline-tracks context doc))
                             sampled (entity (:slots (sampled-slots context preview)) (:id slot))]
                         (attachments/set-sequence-keyframe doc (:id (animation context doc)) (:skin track) (:slot track) (:attachment track)
                                                            {:time (:time state) :index (get-in sampled [:attachment :sequence-index] 0) :mode "hold" :delay (/ 1.0 (:fps state))}))
                       (and slot (= "drawOrder" (:property state)))
                       (slots/set-draw-order-keyframe doc (:id (animation context doc)) (:time state)
                                                      (mapv :id (:slots (sampled-slots context preview))))
                       (and slot (contains? #{"attachment" "rgba" "rgb" "rgba2" "rgb2" "alpha"} (:property state)))
                       (let [sampled (entity (:slots (sampled-slots context preview)) (:id slot))
                             color (:color sampled [1.0 1.0 1.0 1.0])
                             property (:property state)
                             frame (merge {:time (:time state)}
                                          (case property
                                            "attachment" {:name (second (:preview-attachment-key sampled))}
                                            "alpha" {:value (nth color 3)}
                                            {:color (coll/join-to-string "" (mapv #(format "%02x" (Math/round (* 255.0 (double %))))
                                                                                  (subvec color 0 (if (#{"rgb" "rgb2"} property) 3 4))))}))]
                         (slots/set-keyframe doc (:id (animation context doc)) (:id slot) property
                                             (cond-> frame
                                               (#{"rgba2" "rgb2"} property)
                                               (assoc :dark-color (coll/join-to-string "" (mapv #(format "%02x" (Math/round (* 255.0 (double %))))
                                                                                                (or (:dark-color sampled) [0.0 0.0 0.0])))))))
                       (= :event (:selected-kind state))
                       (events/set-keyframe doc (:id (animation context doc))
                                            {:name (or (:event (coll/first-where #(= (:property state) (:property %)) (timeline-tracks context doc)))
                                                       (:selected state))
                                             :time (:time state)})
                       (= :physics (:selected-kind state))
                       (let [constraint (entity (data/sample-physics preview (when (= "animate" (:mode state)) (:id (animation context doc))) (:time state)) (:selected state))
                             id (if (= "global" (:physics-scope state)) "" (:id constraint))
                             property (if (contains? (set (conj physics/animated-properties "reset")) (:property state)) (:property state) "mix")
                             track (coll/first-where #(and (= id (:physics %)) (= property (:property %))) (:physics-tracks (animation context doc)))]
                         (if (= "reset" property)
                           (data/set-physics-reset-keyframe doc (:id (animation context doc)) id (:time state))
                           (data/set-physics-keyframe doc (:id (animation context doc)) id property (:time state) (get constraint (keyword property))
                                                      (interpolation-at (:keys track) (:time state) (:interpolation state)))))
                       (= :path (:selected-kind state))
                       (let [constraint (entity (data/sample-paths preview (when (= "animate" (:mode state)) (:id (animation context doc))) (:time state)) (:selected state))
                             property (if (#{"position" "spacing" "mix-rotate" "mix-x" "mix-y"} (:property state)) (:property state) "position")]
                         (data/set-path-keyframe doc (:id (animation context doc)) (:id constraint) property (:time state) (get constraint (keyword property))
                                                 (interpolation-at (:keys (coll/first-where #(and (= (:id constraint) (:path %)) (= property (:property %))) (:path-tracks (animation context doc))))
                                                                   (:time state) (:interpolation state))))
                       (= :transform (:selected-kind state))
                       (let [constraint (entity (data/sample-transforms preview (when (= "animate" (:mode state)) (:id (animation context doc))) (:time state)) (:selected state))
                             property (if (string/starts-with? (:property state) "mix-") (:property state) "mix-rotate")]
                         (data/set-transform-keyframe doc (:id (animation context doc)) (:id constraint) property (:time state)
                                                      (get constraint (keyword property))
                                                      (interpolation-at (:keys (coll/first-where #(and (= (:id constraint) (:transform %)) (= property (:property %))) (:transform-tracks (animation context doc))))
                                                                        (:time state) (:interpolation state))))
                       (= :ik (:selected-kind state))
                       (let [ik (entity (if (= "animate" (:mode state))
                                          (data/sample-ik preview (:id (animation context doc)) (:time state)) (:ik doc)) (:selected state))
                             track (coll/first-where #(= (:id ik) (:ik %)) (:ik-tracks (animation context doc)))
                             softness (= "IK softness" (:property state))
                             keys (cond->> (:keys track)
                                    softness (mapv #(assoc % :interpolation (:softness-interpolation % "linear") :curve (:softness-curve %))))
                             interpolation (interpolation-at keys (:time state) (:interpolation state))]
                         (if softness
                           (data/set-ik-softness-keyframe doc (:id (animation context doc)) (:id ik) (:time state) (:softness ik) interpolation)
                           (data/set-ik-keyframe-values doc (:id (animation context doc)) (:id ik) (:time state)
                                                        (select-keys ik [:mix :bend-positive :compress :stretch]) interpolation)))
                       (and (or (mesh-manipulator context doc) (#{"mesh" "path"} (:tool state)))
                            (#{"mesh" "path" "boundingbox" "clipping"} (get-in slot [:attachment :type])))
                       (do
                         (when-not (editable-preview-attachment context doc)
                           (throw (ex-info (tr context "preview-attachment-readonly") {})))
                         (slots/set-deform-keyframe preview (:id (animation context preview)) (:id slot) (:time state)
                                                    (or (when (= "animate" (:mode state))
                                                          (get (:deforms (sampled-slots context preview)) (:id slot)))
                                                        (vec (repeat (data/deform-size (:attachment slot)) 0.0)))
                                                    (interpolation-at (:keys (slots/deform-track preview (:id (animation context preview)) (:id slot) (:time state))) (:time state) (:interpolation state))))
                       :else
                       (let [ids (transform-selection context preview bone)
                             sampled-bones (if (= "animate" (:mode state))
                                             (data/sample-pose preview (:id (animation context preview)) (:time state))
                                             (:bones preview))]
                         (reduce (fn [result id]
                                   (change-bone-value (assoc context :state (atom (assoc state :mode "animate"))) result id
                                                      (:property state) (get (entity sampled-bones id) (keyword (:property state)) 0.0)))
                                 doc (sort ids)))))))))))

(defn- key-properties! [context properties]
  (let [property (:property @(:state context))
        doc (document context)
        primary (selected-bone context doc)
        ids (when primary (transform-selection context doc primary))
        baseline (g/node-value (:resource-node context) :document)
        before (when (> (count ids) 1) (pose context baseline))
        after (when (> (count ids) 1) (pose context doc))
        properties (coll/into-> transform-properties (vec properties)
                     (comp (remove (set properties))
                           (filter (fn [property]
                                     (coll/any? (fn [id]
                                                  (not= (get (entity before id) (keyword property))
                                                        (get (entity after id) (keyword property)))) ids)))))
        context (assoc context :operation-sequence (gensym "skeletal-keys"))]
    (try
      (if (mesh-manipulator context doc)
        (do (swap! (:state context) assoc :property "deform")
            (set-key! context))
        (doseq [property properties]
          (swap! (:state context) assoc :property property)
          (set-key! context)))
      (finally (swap! (:state context) assoc :property property)))))

(defn- delete-single-key! [context]
  (let [doc (document context) bone (selected-bone context doc) state @(:state context)
        slot (selected-slot context doc)
        [property time] (or (:selected-key state) [(:property state) (:time state)])]
    (when (or bone (= :event (:selected-kind state)))
      (change! context
               #(case (:selected-kind state)
                  :event (events/remove-keyframe % (:id (animation context doc))
                                                 (or (:event (coll/first-where (fn [track] (= property (:property track))) (timeline-tracks context doc)))
                                                     (:selected state)) time)
                  :physics (data/remove-physics-keyframe % (:id (animation context doc))
                                                         (if (= "global" (:physics-scope state)) "" (:selected state)) property time)
                  :path (data/remove-path-keyframe % (:id (animation context doc)) (:selected state) property time)
                  :transform (data/remove-transform-keyframe % (:id (animation context doc)) (:selected state) property time)
                  (case property
                    "drawOrder" (slots/remove-draw-order-keyframe % (:id (animation context doc)) time)
                    ("attachment" "rgba" "rgb" "rgba2" "rgb2" "alpha") (slots/remove-keyframe % (:id (animation context doc)) (:id slot) property time)
                    "deform" (data/remove-deform-keyframe % (:id (animation context doc)) (:id slot) time)
                    ("IK mix" "IK softness") (data/remove-ik-keyframe % (:id (animation context doc)) (:selected state) time)
                    (data/remove-keyframe % (:id (animation context doc)) (:id bone) property time)))))))

(defn- delete-key! [context]
  (if (pos? (count (selected-key-refs context (document context))))
    (batch-keys! context timeline/delete-keys nil)
    (let [doc (document context) state @(:state context)
          track (coll/first-where #(= (:property state) (:property %)) (timeline-tracks context doc))]
      (cond
        (or (:inherit track) (:deform-target track))
        (when-let [key (coll/first-where #(== (:time state) (:time %)) (:keys track))]
          (change! context #(timeline/delete-keys % (:id (animation context %)) [(key-ref track key)])))
        (:sequence track)
        (change! context #(attachments/remove-sequence-keyframe % (:id (animation context %)) (:skin track) (:slot track) (:attachment track) (:time state)))
        :else (delete-single-key! context)))))

(defn- set-curve! [context interpolation]
  (swap! (:state context) assoc :interpolation (if (= "bezier" interpolation) "linear" interpolation))
  (let [doc (document context)
        [property time] (:selected-key @(:state context))
        track (coll/first-where #(= property (navigation/channel-id %)) (timeline-tracks context doc))
        property (:property track)
        key (coll/first-where #(= time (:time %)) (:keys track))
        next-key (coll/first-where #(> (:time %) (double (or time 0.0))) (:keys track))]
    (when (and key (not (or (:inherit track) (:sequence track) (:event track) (:slot-animation track) (:draw-order track))) (not= "reset" property))
      (change! context
               (fn [d]
                 (let [curve (if (and (= "bezier" interpolation) next-key)
                               (let [t0 (:time key) t1 (:time next-key)
                                     v0 (case property "deform" 0.0 "IK mix" (:mix key) (:value key))
                                     v1 (case property "deform" 1.0 "IK mix" (:mix next-key) (:value next-key))]
                                 {:type "bezier" :curve [(+ t0 (* (- t1 t0) 0.33)) v0
                                                         (+ t0 (* (- t1 t0) 0.67)) v1]})
                               (if (= "bezier" interpolation) "linear" interpolation))]
                   (cond
                     (:physics track) (data/set-physics-keyframe d (:id (animation context d)) (:physics track) property time (:value key) curve)
                     (:path track) (data/set-path-keyframe d (:id (animation context d)) (:path track) property time (:value key) curve)
                     (:transform track)
                     (data/set-transform-keyframe d (:id (animation context d)) (:transform track) property time (:value key) curve)
                     :else (case property
                             "IK softness" (data/set-ik-softness-keyframe d (:id (animation context d)) (:ik track) time (:value key) curve)
                             "IK mix" (data/set-ik-keyframe d (:id (animation context d)) (:ik track) time (:mix key) (:bend-positive key) curve)
                             "deform" (slots/set-deform-track-keyframe d (:id (animation context d)) track time (:vertices key) curve)
                             (data/set-keyframe d (:id (animation context d)) (:bone track) property time (:value key) curve)))))))))

(defn- curve-state [context]
  (let [doc (g/node-value (:resource-node context) :document)
        state @(:state context)
        [property time] (or (:selected-key state) [(:property state) nil])
        track (coll/first-where #(= property (navigation/channel-id %)) (timeline-tracks context doc))
        property (:property track)
        keys (if (or (:inherit track) (:sequence track) (:event track) (:slot-animation track) (:draw-order track)) [] (case property ("deform" "reset") [] "IK mix" (mapv #(assoc % :value (:mix %)) (:keys track)) (vec (:keys track))))]
    {:active-ref (:selected-key state)
     :selection-token [(:animation state) (if (:graph-lock state) (:graph-locked-channels state)
                                              (select-keys state [:selected-kind :selected :selected-bones :selected-slots :selected-attachment :tree-selection]))]
     :external-ruler true
     :shift (:graph-shift state)
     :offset (:graph-offset state)
     :stored-channels (get-in state [:graph-stored (:animation state) :channels])
     :selected-refs (coll/into-> (timeline-tracks context doc) #{}
                      (mapcat (fn [track]
                                (coll/into-> (:keys track) []
                                  (keep (fn [key] (when (contains? (:selected-keys state) (key-ref track key))
                                                    [(navigation/channel-id track) (:time key)])))))))
     :animation-name (:id (animation context doc)) :entity-name (if (navigation/single-node? state) (:selected state) (tr context "all-nodes")) :fps (:fps state)
     :labels (into (coll/into-> ["linear" "stepped" "bezier" "smooth" "flat" "ease-in" "ease-out" "frame" "auto" "filter"
                                 "x" "y" "rotation" "scale-x" "scale-y" "shear-x" "shear-y"] {}
                     (map (fn [id] [id (tr context (str "curve-" id))])))
                   (mapv (fn [track] [(navigation/channel-id track) (track-title context track)]) (timeline-tracks context doc)))
     :channels (coll/into-> (timeline-tracks context doc) []
                 (keep (fn [track]
                         (when (pos? (count (:keys track)))
                           (let [property (:property track)
                                 slot (entity (:slots doc) (:slot track))
                                 discrete (or (:inherit track) (:sequence track) (:event track) (:slot-animation track) (:draw-order track)
                                              (contains? #{"deform" "reset"} property))]
                             {:property (navigation/channel-id track)
                              :property-name property
                              :label (if discrete (track-title context track) (tr context (str "curve-" property)))
                              :discrete (boolean discrete)
                              :owner (or (:bone track) (:bone slot) (:slot track) (:ik track) (:path track) (:transform track) (:physics track) "")
                              :keys (if (= "IK mix" property) (mapv #(assoc % :value (:mix %)) (:keys track)) (:keys track))})))))
     :keys keys :duration (or (:duration (animation context doc)) 1.0)
     :time (:time state) :selected-time (or time (:time (first keys)))}))

(defn- update-curve [context doc key curve]
  (let [state @(:state context)
        [property _] (if-let [track (:track key)] [track nil] (or (:selected-key state) [(:property state) nil]))
        track (coll/first-where #(= property (navigation/channel-id %)) (timeline-tracks context doc))
        property (:property track)
        id (:id (animation context doc))]
    (cond
      (:physics track) (data/set-physics-keyframe doc id (:physics track) property (:time key) (:value key) (if (vector? curve) {:type "bezier" :curve curve} curve))
      (:path track) (data/set-path-keyframe doc id (:path track) property (:time key) (:value key) (if (vector? curve) {:type "bezier" :curve curve} curve))
      (:transform track) (data/set-transform-keyframe doc id (:transform track) property (:time key) (:value key) (if (vector? curve) {:type "bezier" :curve curve} curve))
      (= "IK softness" property)
      (data/set-ik-softness-keyframe doc id (:ik track) (:time key) (:value key) (if (vector? curve) {:type "bezier" :curve curve} curve))
      (= "IK mix" property)
      (data/set-ik-keyframe doc id (:ik track) (:time key) (:mix key) (:bend-positive key) (if (vector? curve) {:type "bezier" :curve curve} curve))
      :else (data/set-keyframe doc id (:bone track) property (:time key) (:value key) (if (vector? curve) {:type "bezier" :curve curve} curve)))))

(defn- commit-curves! [context changes]
  (change! context (fn update-curves [doc]
                     (reduce (fn [doc {:keys [key curve]}] (update-curve context doc key curve)) doc changes))))

(defn- move-curve-keys! [context changes]
  (change! context
           (fn move-graph-keys [doc]
             (let [id (:id (animation context doc))
                   tracks (timeline-tracks context doc)
                   refs (mapv (fn [{:keys [key]}]
                                (key-ref (coll/first-where #(= (:track key) (navigation/channel-id %)) tracks) key)) changes)
                   dt (- (:time (first changes)) (get-in changes [0 :key :time]))
                   offset (:graph-offset @(:state context))
                   channel-ids (coll/into-> changes #{} (map (comp :track :key)))
                   identities (coll/into-> tracks #{} (filter #(contains? channel-ids (navigation/channel-id %)))
                                (map #(select-keys % [:bone :property])))
                   _ (when (and offset (coll/any? #(and (contains? channel-ids (navigation/channel-id %)) (nil? (:bone %))) tracks))
                       (throw (ex-info (tr context "offset-bone-curves") {})))
                   moved (if offset
                           (update doc :animations
                                   (fn [clips]
                                     (mapv (fn [clip]
                                             (if (not= id (:id clip)) clip
                                                 (update clip :tracks
                                                         (fn [tracks]
                                                           (mapv (fn [track]
                                                                   (if (contains? identities (select-keys track [:bone :property]))
                                                                     (update track :keys graph-edit/offset-loop (:duration clip) dt) track)) tracks))))) clips)))
                           (timeline/move-keys doc id refs dt {}))]
               (if offset moved
                   (reduce (fn [result {:keys [key time value]}]
                             (let [track (coll/first-where #(= (:track key) (navigation/channel-id %)) (timeline-tracks context result))
                                   moved-key (coll/first-where #(== time (:time %)) (:keys track))
                                   dv (- value (:value key))
                                   curve (when (= "bezier" (:interpolation moved-key))
                                           (-> (:curve moved-key) (update 1 + dv) (update 3 + dv)))
                                   key (assoc moved-key :track (:track key) :value value :mix value)]
                               (update-curve context result key (or curve (:interpolation moved-key) "linear"))))
                           moved changes))))))

(defn- make-curve-view [context]
  (let [state (:state (context))]
    (curve-view/make-multichannel-batch-view
      (fn read-graph []
        (assoc (curve-state (context))
          :on-edit-keys! (fn move-graph-keys [changes] (attempt! (context) #(move-curve-keys! (context) changes)))
          :on-time! (fn scrub-graph [time]
                      (swap! state assoc :time time :preview-time time :playing false :mode "animate"))
          :on-selection! (fn select-graph-keys [refs active]
                           (let [tracks (timeline-tracks (context) (document (context)))
                                 selected (coll/into-> tracks #{}
                                            (mapcat (fn [track]
                                                      (coll/into-> (:keys track) []
                                                        (keep (fn [key] (when (contains? refs [(navigation/channel-id track) (:time key)])
                                                                          (key-ref track key))))))))]
                             (swap! state assoc :selected-key active :selected-keys selected)))))
      #(commit-curves! (context) %))))

(defn- add-physics! [context]
  (setup-change! context
                 (fn [doc]
                   (let [bone (selected-bone context doc)
                         id (unique-id (:physics doc) "physics")]
                     (when-not bone (throw (ex-info (tr context "select-bone-first") {})))
                     (set-selection! context :physics id)
                     (data/add-physics doc {:id id :bone (:id bone) :x 1.0 :y 1.0 :rotate 1.0})))))

(defn- add-ik! [context]
  (attempt! context
            (fn begin-ik []
              (let [doc (document context) state @(:state context)
                    selected (or (coll/not-empty (:selected-bones state)) #{(:id (selected-bone context doc))})
                    bones (filterv #(contains? selected (:id %)) (:bones doc))
                    ordered (if (and (= 2 (count bones)) (= (:id (second bones)) (:parent (first bones))))
                              (vec (rseq bones)) bones)]
                (when-not (= "setup" (:mode state))
                  (throw (ex-info (tr context "ik-setup-only") {})))
                (when-not (and (<= 1 (count ordered) 2) (:parent (first ordered))
                               (or (= 1 (count ordered))
                                   (and (= (:id (first ordered)) (:parent (second ordered)))
                                        (coll/every? #(= "normal" (:inherit % "normal")) ordered))))
                  (throw (ex-info (tr context "ik-select-chain") {})))
                (swap! (:state context) dissoc :binding :draft :drag)
                (swap! (:state context) assoc :ik-pick {:document doc :bones (mapv :id ordered)}
                       :playing false :tool "move" :inspector-token nil)
                :choose-target)))
  (when (:ik-pick @(:state context)) (message! context (tr context "ik-pick-target"))))

(defn- add-transform! [context]
  (setup-change! context
                 (fn [doc]
                   (let [bone (or (selected-bone context doc) (first (:bones doc)))
                         target (unique-id (:bones doc) "target")
                         id (unique-id (:transform doc) "transform")
                         [x y] (point (get (data/world-transforms (:bones doc)) (:id bone)) [0.0 0.0])
                         order (inc (reduce max -1 (into [] (map #(get % :order 0)) (into (into (into (:ik doc) (:transform doc)) (:path doc)) (:physics doc)))))]
                     (set-selection! context :transform id)
                     (-> (data/add-bone doc {:id target :x (+ x 50.0) :y (+ y 50.0) :length 30.0})
                         (data/add-transform {:id id :bones [(:id bone)] :target target :order order}))))))

(defn- add-path! [context]
  (setup-change! context
                 (fn [doc]
                   (let [bone (or (selected-bone context doc) (first (:bones doc)))
                         guide (unique-id (:bones doc) "pathGuide")
                         slot-id (unique-id (:slots doc) "pathSlot")
                         id (unique-id (:path doc) "path")
                         order (inc (reduce max -1 (mapv #(get % :order 0) (into (into (into (into (:ik doc) (:transform doc)) (:path doc)) (:physics doc)) (:path doc)))))
                         attachment {:type "path" :name "curve" :vertices [-40.0 0.0 0.0 0.0 40.0 90.0 100.0 90.0 140.0 0.0 180.0 0.0]
                                     :closed false :constant-speed true :lengths [200.0] :color [1.0 0.5 0.0 1.0]}]
                     (set-selection! context :path id)
                     (-> (data/add-bone doc {:id guide :length 0.0})
                         (data/add-slot {:id slot-id :bone guide :attachment attachment})
                         (data/add-path {:id id :bones [(:id bone)] :target slot-id :order order}))))))

(defn- mixer-command! [context command]
  (attempt! context
            (fn edit-mixer []
              (let [doc (document context) state @(:state context)
                    base (if (and (:mixer-enabled state) (= doc (get-in state [:mixer-state :document])))
                           (:mixer-state state)
                           (mixing/create-state doc {:tracks [{:animation (:id (animation context doc)) :loop (:loop state)}]
                                                     :default-mix (:mix-duration state 0.25)}))
                    start-time (max 0.0 (:preview-time state (:time state 0.0)))
                    base (if (or (:mixer-enabled state) (zero? start-time)) base
                             (:state (mixing/seek-state doc base start-time)))
                    index (long (:mixer-index state 1))
                    entry {:animation (or (:mix-overlay state) (:id (animation context doc)))
                           :alpha (:mix-alpha state 1.0) :loop (:loop state) :blend (keyword (:mixer-blend state "replace"))
                           :reverse (:mixer-reverse state false) :hold-previous (:mixer-hold state false)
                           :shortest-rotation (:mixer-shortest state false)
                           :animation-start (:mixer-start state 0.0)
                           :animation-end (or (:mixer-end state)
                                              (:duration (entity (:animations doc) (or (:mix-overlay state) (:id (animation context doc))))))
                           :time-scale (:mixer-speed state 1.0) :mix-duration (:mix-duration state 0.25) :delay (:mixer-delay state 0.0)}
                    updated (case command
                              :set (mixing/set-track doc base index entry)
                              :queue (mixing/queue-track doc base index entry)
                              :clear (mixing/clear-track base index)
                              :fade (mixing/set-track doc base index {:empty true :mix-duration (:mix-duration state 0.25)}))
                    sample (mixing/advance-state doc updated 0.0)]
                (swap! (:state context) assoc :mixer-enabled true :mix-enabled false :mix-transition nil
                       :mixer-state (:state sample) :mixer-sample sample :mixer-pending-events (into (if (:mixer-enabled state) (:mixer-pending-events state []) []) (:events sample)) :mixer-clock-initialized true
                       :preview-time (:time (:state sample)) :last-clock nil :mode "animate")))))

(defn- refresh-mode-layout! [context]
  (when-let [root (:root context)]
    (doseq [^Node node (ui/user-data root ::setup-controls)]
      (.setDisable node (= "animate" (:mode @(:state context))))))
  (when-let [^SplitPane split (:layout-split context)]
    (let [animate (= "animate" (:mode @(:state context)))
          items (.getItems split)
          showing (= 2 (.size items))]
      (when (not= animate showing)
        (if-not animate
          (do (swap! (:state context) assoc :animation-divider (aget (.getDividerPositions split) 0))
              (.remove items (:animation-dock context)))
          (do (.add items (:animation-dock context))
              (.setDividerPositions split (double-array [(:animation-divider @(:state context) 0.67)]))))))))

(defn- refresh-weight-dock! [context]
  ;; Remove a legacy dock during reload. Weights now belong to the inspector.
  (when-let [scroll (:inspector-scroll context)]
    (.setFitToHeight ^ScrollPane scroll (= :weights (:rigging-panel @(:state context)))))
  (let [state @(:state context)]
    (when (and (= :weights (:rigging-panel state)) (= :bone (:selected-kind state))
               (not (:binding state))
               (= "mesh" (get-in (entity (:slots (document context)) (:weight-slot state)) [:attachment :type])))
      (select-weight-bones! context (vec (or (coll/not-empty (:selected-bones state)) #{(:selected state)})))))
  (when-let [dock (ui/user-data (:root context) ::weight-dock)]
    (when-let [parent (loop [node (.getParent ^Node (:panel dock))]
                        (when node (if (instance? SplitPane node) node (recur (.getParent ^Node node)))))]
      (when (instance? SplitPane parent)
        (.remove (.getItems ^SplitPane parent) (:panel dock))
        (.setDividerPositions ^SplitPane parent (double-array (or (ui/user-data (:root context) ::weight-dividers) [0.6 0.79]))))))
  (when-let [details (.lookup ^Node (:root context) "#skeletal-details")]
    (.setManaged details false)
    (.setVisible details false)))

(defn- refresh! [context elapsed]
  (flush-drag-input! context)
  (refresh-mode-layout! context)
  (refresh-weight-dock! context)
  (let [state @(:state context)
        baseline (g/node-value (:resource-node context) :document)
        pending (or (:pending-pose state) (:discarded-pose state))]
    (when (and pending (not= baseline (:baseline pending)))
      (swap! (:state context) dissoc :pending-pose :discarded-pose :pose-undo :pose-redo)))
  (repair-selection! context (document context))
  (let [doc (document context) state @(:state context)
        animation (animation context doc)
        duration (double (or (:duration animation) 1.0))
        doc-changed (not= doc (:last-document state))
        animation-changed (not= (:id animation) (:last-animation state))
        preview-attachment (when (= :slot (:selected-kind state))
                             (select-keys (entity (:slots (sampled-slots context doc)) (:selected state))
                                          [:preview-attachment-key :preview-editable-attachment]))
        inspector-token [doc preview-attachment (:selected-attachment state) (:selected-kind state) (:selected state) (:vertex state) (:vertices state) (:weight-bone state) (:weight-bones state) (:weight-picked-bones state) (:mesh-mode state) (:brush-mode state) (:auto-key state) (:weight-clipboard state) (:rigging-panel state) (:binding state) (:locked-bones state) (:mode state) (:animation state) (when-not (:playing state) (:time state))]]
    (when (and (nil? (:drag state)) (not= doc (:compatibility-document state)))
      (let [issues (coll/into-> (project/documents doc) [] (mapcat #(animation-io/export-compatibility (:document %))))]
        (swap! (:state context) assoc :export-compatibility issues :compatibility-document doc)
        (when-let [^Label status (:export-compatibility-label context)]
          (.setText status (coll/join-to-string "\n" (coll/into-> issues [] (map :code) (distinct) (map #(tr context (case % :default-material-dark "compat-default-dark")))))))))
    (when-let [^Label breadcrumb (:selection-path context)]
      (let [slot (when (= :slot (:selected-kind state)) (selected-slot context doc))
            bone-id (if slot (:bone slot) (when (= :bone (:selected-kind state)) (:selected state)))
            path (loop [id bone-id parts (if slot (list (:id slot)) '())]
                   (if-let [bone (entity (:bones doc) id)]
                     (recur (:parent bone) (conj parts (:id bone)))
                     parts))]
        (.setText breadcrumb (coll/join-to-string "  ›  " path))))
    (when (and doc-changed (> (:time state) duration))
      (swap! (:state context) assoc :time duration))
    (when (or doc-changed (and (:audio-playing state) (not (:playing state)))
              (not= (:time state) (:playback-time state (:time state)))
              (not= (:animation state) (:event-animation state (:animation state))))
      ((:stop! (:audio context)))
      (swap! (:state context) assoc :audio-playing false))
    (swap! (:state context) (if (:mixer-enabled state) playback/advance-mixer playback/advance) doc elapsed)
    (doseq [frame (:frame-events @(:state context))] ((:play! (:audio context)) doc frame))
    (swap! (:state context) assoc :audio-playing (:playing @(:state context)))
    (when doc-changed
      (let [refreshing (:refreshing @(:state context))]
        (swap! (:state context) assoc :refreshing true)
        (when-let [control (:workspace-choice context)]
          (.setAll (.getItems ^ComboBox control) ^java.util.Collection (mapv :name (project/documents doc)))
          (.setValue ^ComboBox control (:name doc)))
        (when-let [field (:workspace-name context)] (.setText ^TextField field (:name doc)))
        (swap! (:state context) assoc :refreshing refreshing)))
    (when-not (= (:event-log @(:state context)) (:displayed-event-log @(:state context)))
      (.setAll (.getItems ^ListView (:event-log context)) ^java.util.Collection
               (mapv (fn [frame]
                       (format "T%d  %.3f s  %s   int: %s   float: %s   %s"
                               (long (inc (:track frame 0))) (double (:time frame)) (:name frame) (:int frame) (:float frame) (:string frame)))
                     (reverse (:event-log @(:state context)))))
      (swap! (:state context) assoc :displayed-event-log (:event-log @(:state context))))
    (when (and (or doc-changed animation-changed (not= doc (:tree-document state))) (nil? (:drag state)))
      (let [structure (tree-structure context doc)]
        (when (not= structure (:tree-structure state))
          (refresh-tree! context doc)
          (swap! (:state context) assoc :tree-structure structure :tree-selection-token nil)))
      (swap! (:state context) assoc :tree-document doc)
      (let [^ComboBox combo (:animation-choice context) ids (mapv :id (:animations doc))]
        (swap! (:state context) assoc :refreshing true)
        (.setAll (.getItems combo) ^java.util.Collection ids)
        (.setValue combo (:id animation))
        (.setAll (.getItems ^ComboBox (:mix-overlay-choice context)) ^java.util.Collection ids)
        (.setValue ^ComboBox (:mix-overlay-choice context) (:mix-overlay @(:state context)))
        (.setAll (.getItems ^ComboBox (:skin-choice context)) ^java.util.Collection (data/skin-names doc))
        (.setValue ^ComboBox (:skin-choice context) (get doc :active-skin "default"))
        (refresh-number-field! (:duration-field context) duration)
        (swap! (:state context) assoc :refreshing false :animation (:id animation))))
    (when (and (or doc-changed animation-changed) (nil? (:drag state)))
      (refresh-number-field! (:duration-field context) duration)
      (.setText ^TextField (:clip-name-field context) (or (:id animation) "")))
    (.setDisable ^VBox (:inspector context)
                 (and (= "animate" (:mode state)) (boolean (or (:mixer-enabled state) (:mix-enabled state) (:mix-transition state)))))
    (let [state @(:state context)
          token (select-keys state [:selected-kind :selected :selected-bones :selected-slots :selected-attachment :tree-selection])]
      (when (not= token (:tree-selection-token state))
        (let [kind (:selected-kind state)
              items (if-let [selection (:tree-selection state)]
                      selection
                      (if-let [attachment (:selected-attachment state)]
                        [[:attachment attachment]]
                        (if (#{:bone :slot} kind)
                          (into (mapv #(vector :bone %) (:selected-bones state))
                                (mapv #(vector :slot %) (:selected-slots state)))
                          [[kind (:selected state)]])))
              tree ^TreeView (:tree context)
              model (.getSelectionModel tree)]
          (swap! (:state context) assoc :refreshing true)
          (.clearSelection model)
          (doseq [[kind id] items :let [item ^TreeItem (get (:tree-items state) [kind id])] :when item]
            (loop [parent (.getParent item)]
              (when parent (.setExpanded parent true) (recur (.getParent parent))))
            (.select model item))
          (when-let [item (get (:tree-items state) (if-let [attachment (:selected-attachment state)] [:attachment attachment] [kind (:selected state)]))]
            (.scrollTo tree (.getRow tree item)))
          (swap! (:state context) assoc :refreshing false :tree-selection-token token))))
    (when (and (not= inspector-token (:inspector-token state)) (nil? (:drag state))
               ;; During continuous seeking, rebuild on the first stationary
               ;; frame. Selection and attachment edits remain immediate.
               (or (not= (pop inspector-token) (:inspector-context-token state))
                   (= (:time state) (:inspector-sample-time state)))
               ;; Preserve open pickers while preview updates the document.
               (coll/not-any? #(.isShowing ^javafx.scene.control.ComboBoxBase %)
                              (.lookupAll ^VBox (:inspector context) ".combo-box-base")))
      (.setAll (.getChildren ^VBox (:inspector context)) ^java.util.Collection (entity-inspector context doc))
      (swap! (:state context) assoc :inspector-token inspector-token :inspector-context-token (pop inspector-token)))
    (let [^Canvas canvas (:canvas context) ^StackPane viewport (:viewport context)
          width (max 1.0 (.getWidth viewport)) height (max 1.0 (.getHeight viewport))]
      (when (or (not= width (.getWidth canvas)) (not= height (.getHeight canvas)))
        (.setWidth canvas width) (.setHeight canvas height)
        (.setWidth ^Canvas (:background context) width) (.setHeight ^Canvas (:background context) height)
        (when (not= false (:auto-fit state)) (fit! context) (swap! (:state context) assoc :fitted true)))
      (when-not (:fitted @(:state context))
        (fit! context)
        (swap! (:state context) assoc :fitted true)))
    (let [^Canvas timeline (:timeline context) ^StackPane pane (:timeline-pane context)]
      (.setWidth timeline (max 1.0 (.getWidth pane)))
      (when (and (not (:timeline-fitted @(:state context))) (> (.getWidth pane) 200.0))
        (swap! (:state context) assoc :timeline-fitted true :timeline-pan 0.0
               :timeline-zoom (max 0.1 (min 20.0 (/ (- (.getWidth pane) 200.0) (* 120.0 (max 0.001 duration)))))))
      (.setMinHeight pane (+ 77.0 (* 23.0 (count (timeline-tracks context doc)))))
      (.setHeight timeline (max (.getHeight pane) (.getMinHeight pane))))
    (when-not (= (:mode @(:state context)) (.getValue ^ComboBox (:mode-choice context)))
      (swap! (:state context) assoc :refreshing true)
      (.setValue ^ComboBox (:mode-choice context) (:mode @(:state context)))
      (swap! (:state context) assoc :refreshing false))
    (when (not= (:tool @(:state context)) (.getValue ^ComboBox (:tool-choice context)))
      (swap! (:state context) assoc :refreshing true)
      (.setValue ^ComboBox (:tool-choice context) (:tool @(:state context)))
      (swap! (:state context) assoc :refreshing false))
    (when (and (:palette-fields context) (nil? (:drag state)))
      (let [bone (selected-bone context doc)
            frame (mesh-manipulator context doc)
            sampled (if frame (mesh-palette-values context doc frame) (displayed-bone context doc bone))]
        (doseq [[property ^TextField field] (:palette-fields context)]
          (.setDisable field (or (nil? bone) (and (= "animate" (:mode state)) (boolean (or (:mixer-enabled state) (:mix-enabled state) (:mix-transition state))))))
          (let [text (when frame (if (#{"x" "y"} property)
                                   "网格选中顶点的中心位置（当前坐标空间）"
                                   "对选中顶点应用增量变形；旋转/倾斜为角度，缩放为倍率，应用后归零/一。"))]
            (when (not= text (some-> (.getTooltip field) .getText))
              (.setTooltip field (when text (Tooltip. text)))))
          (when-not (= field (some-> field .getScene .getFocusOwner))
            (let [value (double (get sampled (keyword property) 0.0))
                  text (format "%.2f" value)]
              (when (not= text (.getText field))
                (.setText field text)
                (reset! (ui/user-data field ::last-number-text) text)))))))
    (when (:group-keys context)
      (let [baseline (g/node-value (:resource-node context) :document)
            state @(:state context)
            bone (selected-bone context doc)
            current (entity (data/sample-pose doc (:id animation) (:time state)) (:id bone))
            saved (entity (data/sample-pose baseline (:id animation) (:time state)) (:id bone))
            tracks (:tracks (entity (:animations baseline) (:id animation)))]
        (doseq [[^Button control properties] (:group-keys context)]
          (.setDisable control (nil? bone))
          (let [edited (and (:pending-pose state) (coll/any? #(not= (get current (keyword %)) (get saved (keyword %))) properties))
                keyed (coll/any? (fn [track] (and (= (:id bone) (:bone track)) (contains? (set properties) (:property track))
                                                  (coll/any? #(== (:time state) (:time %)) (:keys track)))) tracks)]
            (.setStyle control (str "-fx-text-fill: " (cond edited "#eda151" keyed "#e67770" :else "#84c779") ";"))))))
    (when (:palette-fields context)
      (let [focus (some-> ^Node (:root context) .getScene .getFocusOwner)]
        (swap! (:state context) assoc :editing-local-scale
               (contains? (set (mapv (:palette-fields context) ["scale-x" "scale-y"])) focus))))
    (doseq [^ToggleButton control (:space-buttons context)]
      (.setSelected control (= (.getId control) (str "skeletal-space-" (:coordinate-space @(:state context)))))
      (.setStyle control (if (and (= "skeletal-space-local" (.getId control)) (:editing-local-scale @(:state context))
                                  (= "world" (:coordinate-space @(:state context)))) "-fx-text-fill: #eda151;" "")))
    (when-let [control (:mix-enabled-control context)]
      (.setSelected ^CheckBox control (boolean (:mix-enabled @(:state context)))))
    (when-let [control (:auto-key-button context)]
      (.setSelected ^ToggleButton control (boolean (:auto-key @(:state context)))))
    (when-let [control (:key-edited-button context)]
      (let [pending (tools/pending-document @(:state context) (g/node-value (:resource-node context) :document))]
        (.setDisable ^Button control (nil? pending))
        (.setStyle ^Button control (if pending "-fx-text-fill: #eda151;" ""))))
    (doseq [^ToggleButton control (:tool-buttons context)]
      (.setSelected control (= (.getId control) (str "skeletal-tool-" (:tool @(:state context)))))
      (.setDisable control (and (= "skeletal-tool-create" (.getId control)) (not= "setup" (:mode @(:state context))))))
    (let [state @(:state context)
          tracks (timeline-tracks context doc)
          properties (mapv navigation/channel-id tracks)
          property (or (first (:selected-key state)) (when (coll/any? #{(:property state)} properties) (:property state)) (first properties))
          track (coll/first-where #(= property (navigation/channel-id %)) tracks)
          key (coll/first-where #(= (second (:selected-key state)) (:time %)) (:keys track))
          interpolation (get key :interpolation (:interpolation state))]
      (swap! (:state context) assoc :refreshing true :property property)
      (when (not= properties (vec (.getItems ^ComboBox (:property-choice context))))
        (.setAll (.getItems ^ComboBox (:property-choice context)) ^java.util.Collection properties))
      (when (not= property (.getValue ^ComboBox (:property-choice context)))
        (.setValue ^ComboBox (:property-choice context) property))
      (when (not= interpolation (.getValue ^ComboBox (:interpolation-choice context)))
        (.setValue ^ComboBox (:interpolation-choice context) interpolation))
      (swap! (:state context) assoc :refreshing false))
    (when-let [refresh-curve (or (ui/user-data (:root context) ::refresh-curve) (:refresh-curve context))] (refresh-curve))
    (when-let [refresh-toolbar (ui/user-data (:root context) ::refresh-animation-toolbar)] (refresh-toolbar))
    (when-let [field ^TextField (:current-frame context)]
      (when-not (.isFocused field)
        (let [frame (* (:fps @(:state context)) (:time @(:state context)))
              text (format "%.1f" (double frame))]
          (when (not= text (.getText field)) (.setText field text)))))
    (when-let [field (.lookup ^Node (:inspector context) "#skeletal-inspector-frame")]
      (when-not (.isFocused field)
        (.setText ^TextField field (str (long (Math/round (* (:fps @(:state context) 30.0) (:time @(:state context)))))))))
    (.setText ^Label (:time-label context) (format "%.3f s" (double (:time @(:state context)))))
    (.setText ^Button (:play-button context) (tr context (if (:playing @(:state context)) "pause" "play")))
    (let [state @(:state context)
          token [doc (select-keys state [:selected-kind :selected :selected-bones :selected-slots :vertex :vertices :mesh-pivot :selection-box :bone-selection-box :slot-selection-box :pointer :brush-mode :brush-radius :weight-bone :weight-bones :weight-pies :weight-overlay :weight-selected-only :binding :tool :coordinate-space :mode :time :zoom :pan-x :pan-y :show-bones :show-images :show-names :animation :reference-image :reference-opacity :ghosting :show-workspace-peers :playing :mixer-enabled :mixer-state :mix-state :mix-enabled :mix-overlay :mix-alpha :mix-transition :preview-time])
                 (.getWidth ^Canvas (:canvas context)) (.getHeight ^Canvas (:canvas context))]
          timeline-token [doc (select-keys state [:selected-kind :selected :selected-bones :selected-slots :animation :time :timeline-pan :timeline-zoom :selected-key :selected-keys :key-selection-box :physics-scope])
                          (.getWidth ^Canvas (:timeline context)) (.getHeight ^Canvas (:timeline context))]]
      (when (not= token (:draw-token state))
        (draw! context)
        (swap! (:state context) assoc :draw-token token))
      (when (not= timeline-token (:timeline-token state))
        (draw-timeline! context)
        (swap! (:state context) assoc :timeline-token timeline-token)))
    (swap! (:state context) assoc :last-document doc :last-animation (:id animation) :inspector-sample-time (:time @(:state context)))))

(g/defnode SkeletalAnimationView
  (inherits view/WorkbenchView)
  (output sidebar-panes g/Any (g/constantly [])))

(defn- graph-toolbar-actions [context]
  (let [state (:state context)
        capture (fn capture-scope []
                  (let [unlocked (assoc context :state (atom (assoc @state :graph-lock false)))
                        ids (coll/into-> (timeline-tracks unlocked (document context)) #{} (map navigation/channel-id))]
                    (swap! state assoc :graph-locked-channels ids
                           :graph-locked-selection (select-keys @state [:selected-kind :selected :selected-bones :selected-slots :selected-attachment :tree-selection]))))
        store (fn store-curves []
                (let [id (:animation @state)]
                  (if (get-in @state [:graph-stored id])
                    (swap! state update :graph-stored dissoc id)
                    (swap! state assoc-in [:graph-stored id]
                           {:animation (animation context (document context))
                            :channels (:channels (curve-state context))}))))]
    {:lock capture
     :refresh capture
     :filter (fn focus-curve-filter []
               (when-let [field (.lookup ^Node (:root context) "#skeletal-curve-filter")]
                 (.requestFocus ^Node field)))
     :select (fn select-visible-bones []
               (let [ids (coll/into-> (timeline-tracks context (document context)) #{} (keep :bone))]
                 (when (pos? (count ids))
                   (swap! state assoc :selected-attachment nil :tree-selection nil :selected-kind :bone :selected (first (sort ids))
                          :selected-bones ids :selected-slots #{}))))
     :store store
     :swap (fn swap-stored-curves []
             (let [id (:animation @state)
                   stored (get-in @state [:graph-stored id])
                   current {:animation (animation context (document context)) :channels (:channels (curve-state context))}]
               (when stored
                 (change! context (fn restore-curves [doc]
                                    (update doc :animations (fn [clips] (mapv #(if (= id (:id %)) (:animation stored) %) clips)))))
                 (swap! state assoc-in [:graph-stored id] current))))
     :bias (fn bias-selected-keys [amount]
             (let [graph (curve-state context)
                   changes (coll/into-> (:channels graph) []
                             (remove :discrete)
                             (mapcat (fn [{:keys [property keys]}]
                                       (coll/into-> keys []
                                         (keep (fn [key]
                                                 (when (contains? (:selected-refs graph) [property (:time key)])
                                                   (let [neighbor (if (neg? amount)
                                                                    (peek (filterv #(< (:time %) (:time key)) keys))
                                                                    (coll/first-where #(> (:time %) (:time key)) keys))]
                                                     (when neighbor
                                                       {:key (assoc key :track property) :time (:time key)
                                                        :value (+ (:value key) (* (abs amount) (- (:value neighbor) (:value key))))})))))))))]
               (when (pos? (count changes)) (move-curve-keys! context changes))))}))

(defn- install-animation-toolbar! [context playback curve-strip]
  (let [state (:state context)
        result (animation-toolbar/install!
                 {:root (:root context) :state state :tr #(tr context %) :playback playback :curve-strip curve-strip
                  :animation-choice (:animation-choice context) :current-frame (:current-frame context) :time-label (:time-label context)
                  :read-ruler! (fn read-animation-ruler []
                                 (assoc (curve-state context) :view-range ((:view-range (:curve context)))))
                  :graph-visible? (fn graph-visible? []
                                    (= 0 (.getSelectedIndex (.getSelectionModel ^TabPane (first (.getChildren ^VBox (:animation-dock context)))))))
                  :actions (merge (graph-toolbar-actions context) {:cut (fn cut-keys []
                                                                          (when (pos? (count (selected-key-refs context (document context))))
                                                                            (copy-keys! context) (delete-key! context)))
                                                                   :key (fn key-current [] (attempt! context #(key-properties! context (mapv :property (timeline-tracks context (document context))))))
                                                                   :edited (fn key-changed [] (attempt! context #(key-edited! context)))
                                                                   :end (fn last-frame []
                                                                          (let [duration (or (:duration (animation context (document context))) 0.0)]
                                                                            (swap! state assoc :playing false :time duration :preview-time duration)))})})]
    (doseq [[id command] [["skeletal-play" :play] ["skeletal-rewind" :start]
                          ["skeletal-previous-frame" :previous-frame] ["skeletal-next-frame" :next-frame]
                          ["skeletal-previous-key" :previous-key] ["skeletal-next-key" :next-key]
                          ["skeletal-toolbar-end" :end]]]
      (let [control ^Button (.lookup ^Node (:rows result) (str "#" id))]
        ;; Newly built toolbar skins are not attached yet; locate in its rows.
        (when control (.setOnAction control (ui/event-handler _ (transport! context command))))))
    (ui/user-data! (:root context) ::refresh-animation-toolbar (:refresh! result))
    result))

(defn export-game-resources!
  "Export runtime assets, preserving undo for additions/stateless changes.
  Unrelated disk changes require an explicit normal project reload."
  [context target-dir]
  (when-let [result (attempt!
                      context
                      (fn export-runtime-assets []
                        (let [result (animation-io/export-project!
                                       (g/node-value (:resource-node context) :document)
                                       (:project-root context) target-dir)
                              paths (coll/into-> (into (:files result) (:texture-files result)) #{}
                                      (map #(resource/file->proj-path (:project-root context) %)))]
                          ((requiring-resolve 'editor.craft-project/safe-refresh!) (:project context) paths)
                          result)))]
    (message! context (str (tr context "exported") " " (:spinescene result)
                           (when (pos? (count (:warnings result)))
                             (str " · " (coll/join-to-string " · " (:warnings result))))))))

(defn install-tree-cells! [^TreeView tree]
  (.setCellFactory tree
                   (reify javafx.util.Callback
                     (call [_ _]
                       (proxy [javafx.scene.control.TreeCell] []
                         (updateItem [item empty-cell]
                           (proxy-super updateItem item empty-cell)
                           (.setId ^javafx.scene.control.TreeCell this
                                   (when-not empty-cell (str "skeletal-tree-" (name (:kind item)) "-" (when (:draw-order item) "order-") (:id item))))
                           (.setText ^javafx.scene.control.TreeCell this (when-not empty-cell (or (:label item) (:id item))))
                           (.setTooltip ^javafx.scene.control.TreeCell this (when-not empty-cell (Tooltip. (:id item))))
                           (.setGraphic ^javafx.scene.control.TreeCell this
                                        (when-not empty-cell (doto (authoring-icon (if (= :attachment (:kind item)) (keyword (:type item)) (:kind item)))
                                                               (.setStyle (str "-fx-stroke: " (case (:kind item) :slot "#c2c3c5" :attachment "#8bd8e2" :bone "#cecece" :animation "#a3ca71" "#b9b5cc") ";")))))))))))

(defn- make-view [^AnchorPane parent resource-node {:keys [workspace project localization ^Tab tab]}]
  (let [doc (g/node-value resource-node :document)
        state (atom {:selected-attachment nil :tree-selection nil :selected-kind :bone :selected (:id (first (:bones doc))) :mode "setup" :tool "move" :previous-tool "rotate" :coordinate-space "parent" :auto-key true
                     :animation (:id (first (:animations doc))) :mix-enabled false :mix-alpha 1.0 :mix-duration 0.25 :preview-time 0.0 :time 0.0 :fps 60.0 :speed 1.0 :loop true :playing false
                     :selected-keys #{} :key-scale 1.0 :property "rotation" :interpolation "linear" :show-bones true :vertex 0 :vertices #{} :weight 1.0
                     :mesh-mode "modify" :brush-mode "direct" :brush-radius 40.0 :brush-strength 0.25 :locked-bones #{}
                     :zoom 1.0 :pan-x 300.0 :pan-y 300.0 :timeline-zoom 1.0 :timeline-pan 0.0})
        _ (when (:editor-view doc) (swap! state merge (project/view-settings doc) {:auto-fit false :fitted true}))
        root (doto (identify! (BorderPane.) "workspace") (.setMinSize 0.0 0.0))
        background (doto (Canvas. 600.0 400.0) (.setMouseTransparent true))
        renderer (render/make-renderer)
        canvas (identify! (Canvas. 600.0 400.0) "canvas")
        viewport (identify! (StackPane.) "viewport")
        tree (identify! (TreeView.) "tree")
        inspector (doto (identify! (VBox. 6.0) "inspector") (.setPadding (Insets. 8.0)))
        status (identify! (Label.) "status")
        event-log (identify! (ListView.) "event-log")
        timeline (identify! (Canvas. 600.0 143.0) "timeline")
        timeline-pane (StackPane. (into-array Node [timeline]))
        time-label (identify! (Label. "0.000 s") "time")
        context-base {:resource-node resource-node :project project :workspace workspace :localization localization :state state
                      :project-root (workspace/project-directory workspace) :root root :canvas canvas :background background :renderer renderer :viewport viewport
                      :tree tree :inspector inspector :status status :event-log event-log :timeline timeline :timeline-pane timeline-pane
                      :time-label time-label :selection-outlines (atom {}) :images (atom {}) :preview-cache (atom {}) :pose-cache (atom nil) :mix-cache (atom nil) :ghost-cache (atom nil) :workspace-cache (atom nil)
                      :audio (audio/make-player (workspace/project-directory workspace) (fn report-audio-error [text] (.setText ^Label status text)))}
        context-ref (atom context-base)
        context #(deref context-ref)
        play-button (button context-base "play" "play"
                            #(do (change! (context) ensure-animation)
                                 (swap! state (fn [s] (assoc s :playing (not (:playing s)) :mode "animate" :last-clock nil)))))
        mode-choice (choice "mode" ["setup" "animate"] "setup"
                            #(when-not (:refreshing @state)
                               (when (= "animate" %) (change! (context) ensure-animation))
                               (swap! state (fn change-mode [state]
                                              (cond-> (assoc state :mode % :playing false :inspector-token nil)
                                                (= "animate" %) (assoc :mesh-mode "modify")
                                                (and (= "animate" %) (= "create" (:tool state))) (assoc :tool "move"))))))
        tool-choice (choice "tool" ["move" "rotate" "scale" "shear" "mesh" "weights" "path" "pose" "create"] "move"
                            #(when-not (:refreshing @state) (select-tool! (context) %)))
        animation-choice (choice "animation" (mapv :id (:animations doc)) (:animation @state)
                                 #(when-not (:refreshing @state) (swap! state assoc :animation % :time 0.0 :preview-time 0.0 :mix-transition nil :mixer-enabled false :mixer-sample nil :playing false :selected-key nil :selected-keys #{})))
        mix-overlay-choice (choice "mix-overlay" (mapv :id (:animations doc)) nil
                                   #(when-not (:refreshing @state)
                                      (swap! state assoc :mix-overlay % :mix-transition nil)))
        mix-tools (column [(label (tr context-base "mix-preview"))
                           (row [(label (tr context-base "mix-overlay")) mix-overlay-choice])
                           (row [(label (tr context-base "mix-alpha"))
                                 (number-field "mix-alpha" 1.0 #(swap! state assoc :mix-alpha (max 0.0 (min 1.0 %))))])
                           (row [(label (tr context-base "mix-duration"))
                                 (number-field "mix-duration" 0.25 #(swap! state assoc :mix-duration (max 0.0 %)))])])
        mixer-tools (column [(label (tr context-base "mixer-tracks"))
                             (row [(label (tr context-base "mixer-index")) (number-field "mixer-index" 1.0 #(swap! state assoc :mixer-index (long (max 0 (min 1023 %)))))])
                             (choice "mixer-blend" ["replace" "add"] "replace" #(swap! state assoc :mixer-blend %))
                             (row [(label (tr context-base "mixer-speed")) (number-field "mixer-speed" 1.0 #(swap! state assoc :mixer-speed (max 0.0 %)))])
                             (row [(label (tr context-base "mixer-delay")) (number-field "mixer-delay" 0.0 #(swap! state assoc :mixer-delay %))])
                             (row [(label (tr context-base "mixer-start")) (number-field "mixer-start" 0.0 #(swap! state assoc :mixer-start %))])
                             (row [(label (tr context-base "mixer-end"))
                                   (doto (identify! (TextField.) "mixer-end")
                                     (.setPromptText (tr context-base "mixer-end-auto"))
                                     (.setOnAction (ui/event-handler event
                                                     (attempt! (context)
                                                               (fn set-range-end []
                                                                 (let [text (.getText ^TextField (.getSource event))]
                                                                   (swap! state assoc :mixer-end
                                                                          (when-not (string/blank? text) (Double/parseDouble text)))))))))])
                             (row (mapv (fn [id]
                                          (doto (identify! (CheckBox. (tr context-base id)) id)
                                            (.setOnAction (ui/event-handler event
                                                            (swap! state assoc (keyword id) (.isSelected ^CheckBox (.getSource event)))))))
                                        ["mixer-reverse" "mixer-hold" "mixer-shortest"]))
                             (button context-base "mixer-fade" "mixer-fade" #(mixer-command! (context) :fade))
                             (row [(button context-base "mixer-set" "mixer-set" #(mixer-command! (context) :set))
                                   (button context-base "mixer-queue" "mixer-queue" #(mixer-command! (context) :queue))
                                   (button context-base "mixer-clear" "mixer-clear" #(mixer-command! (context) :clear))])
                             (button context-base "mixer-stop" "mixer-stop" #(swap! state assoc :mixer-enabled false :playing false :mixer-state nil :mixer-sample nil))])
        skin-choice (choice "skin" (data/skin-names doc) (get doc :active-skin "default")
                            #(when (and % (not (:refreshing @state)))
                               (swap! state assoc :playing false :selected-key nil :selected-keys #{} :vertices #{} :vertex 0 :edge-start nil)
                               (change! (context) (fn [d] (data/select-skin d %)))))
        duration-field (number-field "duration" (or (:duration (first (:animations doc))) 1.0)
                                     #(when-not (:refreshing @state)
                                        (change! (context) (fn [d] (data/update-animation d (:animation @state) {:duration %})))))
        property-choice (choice "property" transform-properties "rotation"
                                (fn select-key-property [property]
                                  (when-not (:refreshing @state)
                                    (swap! state assoc :property property :selected-key nil :selected-keys #{})
                                    (when-let [event (:event (coll/first-where #(= property (navigation/channel-id %))
                                                                               (timeline-tracks (context) (document (context)))))]
                                      (set-selection! (context) :event event)
                                      (swap! state assoc :last-document nil)))))
        interpolation-choice (choice "interpolation" ["linear" "stepped" "bezier"] "linear"
                                     #(when-not (:refreshing @state) (set-curve! (context) %)))
        import-path (identify! (TextField.) "import-path")
        export-path (identify! (TextField. "skeleton_export") "export-path")
        export-status (doto (identify! (label "") "export-compatibility") (.setWrapText true) (.setMaxWidth 420.0))
        toolbar (tool-strip [mode-choice
                             tool-choice
                             (button context-base "fit" "fit" #(do (swap! state assoc :auto-fit true) (fit! (context))))
                             (doto (identify! (CheckBox. (tr context-base "bones")) "show-bones")
                               (.setSelected true)
                               (.setOnAction (ui/event-handler event (swap! state assoc :show-bones (.isSelected ^CheckBox (.getSource event))))))
                             (Separator. Orientation/VERTICAL)
                             import-path
                             (button context-base "import" "import"
                                     #(when-let [file (if (string/blank? (.getText import-path))
                                                        (dialogs/make-file-dialog (tr (context) "import") [["Spine project / JSON" "*.spine" "*.json" "*.spinejson"]]
                                                                                  (io/file (:project-root (context)) "skeleton.json") (.getWindow (.getScene root)))
                                                        (io/file (.getText import-path)))]
                                        (import-file! (context) file)))
                             export-path
                             export-status
                             (button context-base "save-spine" "save-spine" #(save-native-project! (context) (.getText export-path)))
                             (button context-base "export" "export"
                                     #(export-game-resources! (context) (.getText export-path)))])
        texture-tools (texture-view/make-controls
                        {:document (fn texture-document [] (document (context)))
                         :inventory (fn texture-inventory [] (animation-io/texture-inventory (document (context)) (:project-root (context))))
                         :apply (fn apply-texture-scale [factor]
                                  (change! (context) #(textures/set-scale % factor)))
                         :translate (fn texture-translate [key] (tr (context) key))})
        texture-menu (doto (identify! (MenuButton. (tr context-base "texture-output")) "texture-output-menu")
                       (.setFocusTraversable false)
                       (-> .getItems (.add (settings-menu-item (:root texture-tools) 420.0))))
        hierarchy-tools (row [(button context-base "add-bone" "add-bone"
                                      #(setup-change! (context)
                                                      (fn [d] (let [id (unique-id (:bones d) "bone") bone (selected-bone (context) d)]
                                                                (set-selection! (context) :bone id)
                                                                (data/add-bone d {:id id :parent (:id bone) :x (double (or (:length bone) 0.0)) :length 80.0})))))
                              (button context-base "attach-image" "attach-image" #(attach-image! (context)))
                              (button context-base "delete" "delete" #(delete-selection! (context)))])

        constraint-tools (row [(button context-base "add-ik" "add-ik" #(add-ik! (context)))
                               (button context-base "add-transform" "add-transform" #(add-transform! (context)))
                               (button context-base "add-path" "add-path" #(add-path! (context)))
                               (button context-base "add-physics" "add-physics" #(add-physics! (context)))
                               (button context-base "add-event" "add-event"
                                       (fn add-event []
                                         (change! (context)
                                                  (fn [doc]
                                                    (let [name (unique-id (mapv #(assoc % :id (:name %)) (events/definitions doc)) "event")]
                                                      (set-selection! (context) :event name)
                                                      (events/add-definition doc {:name name :int 0 :float 0.0 :string ""}))))))])
        search (doto (identify! (TextField.) "tree-search") (.setPromptText (tr context-base "search-hierarchy")))
        left (doto (identify! (column [(label (tr context-base "hierarchy")) search tree]) "hierarchy-pane")
               (.setMinHeight 0.0))
        right (doto (identify! (ScrollPane. inspector) "inspector-scroll") (.setFitToWidth true)
                    (.setHbarPolicy ScrollPane$ScrollBarPolicy/NEVER) (.setMinWidth 0.0))
        side (doto (VBox. 2.0) (.setMinHeight 0.0))
        center (doto (SplitPane.) (.setOrientation Orientation/HORIZONTAL))
        current-frame (number-field "current-frame" 0.0
                                    (fn set-current-frame [frame]
                                      (let [time (/ (max 0.0 frame) (:fps @state))]
                                        (swap! state assoc :time time :preview-time time :playing false))))
        playback (tool-strip [play-button (button context-base "rewind" "rewind" #(swap! state assoc :time 0.0 :preview-time 0.0 :mix-transition nil :playing false))
                              animation-choice (label (tr context-base "current-frame")) current-frame time-label
                              (button context-base "copy-keys" "copy-keys" #(copy-keys! (context)))
                              (button context-base "paste-keys" "paste-keys" #(paste-keys! (context)))
                              (button context-base "delete-key" "delete-key" #(delete-key! (context)))])
        clip-name-field (text-field "clip-name" (:animation @state)
                                    #(attempt! (context)
                                               (fn [] (change! (context) (fn [doc] (data/update-animation doc (:animation @state) {:id %})))
                                                 (swap! state assoc :animation %))))
        clip-tools (tool-strip [(label (tr context-base "clip-name")) clip-name-field
                                (button context-base "add-animation" "add-animation"
                                        #(change! (context) (fn [d] (let [id (unique-id (:animations d) "animation")]
                                                                      (swap! state assoc :animation id :time 0.0)
                                                                      (data/add-animation d {:id id :duration 1.0 :tracks []})))))
                                (label (tr context-base "duration")) duration-field property-choice interpolation-choice
                                (button context-base "key" "key" #(set-key! (context)))
                                (button context-base "copy-animation" "copy-animation"
                                        #(change! (context) (fn [d] (let [clip (animation (context) d)
                                                                          id (unique-id (:animations d) "copy")]
                                                                      (swap! state assoc :animation id)
                                                                      (data/copy-animation d (:id clip) id)))))
                                (button context-base "remove-animation" "remove-animation"
                                        #(change! (context) (fn [d] (data/remove-animation d (:animation @state)))))
                                (doto (identify! (CheckBox. (tr context-base "loop")) "loop")
                                  (.setSelected true)
                                  (.setOnAction (ui/event-handler event (swap! state assoc :loop (.isSelected ^CheckBox (.getSource event))))))
                                (label "FPS") (number-field "fps" 60.0 #(swap! state assoc :fps (max 1.0 (min 240.0 %))))
                                (button context-base "warnings" "warnings"
                                        #(message! (context) (coll/join-to-string " · " (into (vec (:warnings (document (context)))) (:preview-warnings @state)))))])
        curve (make-curve-view context)
        timeline-scroll (doto (ScrollPane. timeline-pane)
                          (.setFocusTraversable false)
                          (.setFitToWidth true) (.setFitToHeight true)
                          (.setHbarPolicy ScrollPane$ScrollBarPolicy/NEVER))
        event-content (column [(row [(label (tr context-base "event-log"))
                                     (button context-base "clear-event-log" "clear-event-log"
                                             #(swap! state assoc :event-log []))])
                               (doto (label (tr context-base "event-log-notice")) (.setWrapText true))
                               event-log])
        timeline-tabs (doto (identify! (TabPane. (into-array Tab [(doto (Tab. (tr context-base "curves") (:node curve))
                                                                    (.setGraphic (doto (javafx.scene.shape.SVGPath.)
                                                                                   (.setContent "M1 12 L5 7 L9 9 L14 2 M1 3 L5 3 M9 13 L14 13")
                                                                                   (.setFill Color/TRANSPARENT) (.setStroke (Color/web "#8fce50")) (.setStrokeWidth 1.5))))
                                                                  (doto (Tab. (tr context-base "timeline") timeline-scroll)
                                                                    (.setGraphic (doto (javafx.scene.shape.SVGPath.)
                                                                                   (.setContent "M2 2 L2 14 M7 2 L7 14 M12 2 L12 14 M0 5 L14 5 M0 10 L14 10")
                                                                                   (.setFill Color/TRANSPARENT) (.setStroke (Color/web "#b4b4b4")) (.setStrokeWidth 1.0))))
                                                                  (Tab. (tr context-base "event-log") event-content)])) "timeline-tabs")
                        (.setTabClosingPolicy TabPane$TabClosingPolicy/UNAVAILABLE))
        batch-tools
        (tool-strip
          [(button context-base "copy-keys" "copy-keys" #(copy-keys! (context)))
           (button context-base "paste-keys" "paste-keys" #(paste-keys! (context)))
           (button context-base "reverse-keys" "reverse-keys"
                   (fn []
                     (let [refs (selected-key-refs (context) (document (context)))
                           times (mapv :time refs)]
                       (when (pos? (count times))
                         (let [start (reduce min times) end (reduce max times)]
                           (batch-keys! (context)
                                        (fn [doc id refs] (timeline/reverse-keys doc id refs start end {}))
                                        (fn [time] (- (+ start end) time))))))))
           (button context-base "snap-keys" "snap-keys"
                   (fn []
                     (let [fps (:fps @state)]
                       (batch-keys! (context)
                                    (fn [doc id refs] (timeline/snap-keys doc id refs fps {}))
                                    (fn [time] (/ (Math/round (* time fps)) fps))))))
           (label (tr context-base "key-scale"))
           (number-field "key-scale" 1.0 #(swap! state assoc :key-scale %))
           (button context-base "scale-keys" "scale-keys"
                   (fn []
                     (let [pivot (:time @state) factor (:key-scale @state)]
                       (batch-keys! (context)
                                    (fn [doc id refs] (timeline/scale-keys doc id refs pivot factor {}))
                                    (fn [time] (+ pivot (* factor (- time pivot))))))))])
        key-filter (text-field "key-filter" "" #(swap! state assoc :key-filter %))
        repeat-tools (tool-strip [(label (tr context-base "match-keys")) key-filter
                                  (button context-base "match-keys" "match-keys"
                                          #(swap! state assoc :selected-key nil :selected-keys
                                                  (set (timeline/matching-refs (document (context)) (:animation @state) {:query (.getText key-filter)}))))
                                  (label (tr context-base "repeat-count"))
                                  (number-field "repeat-count" 1.0 #(swap! state assoc :repeat-count (long (max 1 (min 1000 %)))))
                                  (label (tr context-base "repeat-interval"))
                                  (number-field "repeat-interval" 1.0 #(swap! state assoc :repeat-interval (max 0.001 %)))
                                  (button context-base "repeat-keys" "repeat-keys"
                                          #(batch-keys! (context)
                                                        (fn [d id refs] (timeline/repeat-keys d id refs (:repeat-count @state 1) (:repeat-interval @state 1.0) {})) nil))])
        file-menu (doto (identify! (MenuButton. (tr context-base "project-menu")) "project-menu")
                    (.setFocusTraversable false))
        workspace-choice (choice "skeleton-choice" (mapv :name (project/documents doc)) (:name doc)
                                 (fn choose-skeleton [name]
                                   (when (and name (not (:refreshing @state)) (not= name (:name (document (context)))))
                                     (switch-workspace! (context) #(project/select % name)))))
        workspace-name (text-field "skeleton-name" (:name doc) (fn rename-skeleton [name] (switch-workspace! (context) #(project/rename-active % name))))
        workspace-tools (column [(label (tr context-base "skeletons")) workspace-choice workspace-name
                                 (row [(button context-base "add-skeleton" "add-skeleton"
                                               #(switch-workspace! (context) (fn [d] (project/add d (unique-id (mapv (fn [s] {:id (:name s)}) (project/documents d)) "skeleton")))))
                                       (button context-base "remove-skeleton" "remove-skeleton" #(switch-workspace! (context) project/remove-active))])])
        export-format (choice "preview-format" ["png" "gif" "avi" "sheet" "atlas"] "png" (fn ignore-format [_]))
        preview-path (identify! (TextField. "skeleton_preview") "preview-path")
        preview-width (number-field "preview-width" 512.0 #(swap! state assoc :export-width (long (max 1 (min 4096 %)))))
        preview-height (number-field "preview-height" 512.0 #(swap! state assoc :export-height (long (max 1 (min 4096 %)))))
        preview-tools (column [(label (tr context-base "export-preview")) preview-path export-format
                               (row [(label (tr context-base "width")) preview-width (label (tr context-base "height")) preview-height])
                               (button context-base "export-preview" "export-preview"
                                       #(export-preview! (context) (.getText preview-path) (.getValue export-format) (:export-width @state 512) (:export-height @state 512)))])
        view-menu (doto (identify! (MenuButton. (tr context-base "view-settings")) "view-settings") (.setFocusTraversable false))
        reference-path (text-field "reference-image" (:reference-image @state "") #(swap! state assoc :reference-image %))
        view-tools (column [(doto (identify! (CheckBox. (tr context-base "workspace-peers")) "workspace-peers")
                              (.setSelected (boolean (:show-workspace-peers @state)))
                              (.setOnAction (ui/event-handler event (swap! state assoc :show-workspace-peers (.isSelected ^CheckBox (.getSource event))))))
                            (doto (identify! (CheckBox. (tr context-base "ghosting")) "ghosting")
                              (.setSelected (boolean (:ghosting @state)))
                              (.setOnAction (ui/event-handler event (swap! state assoc :ghosting (.isSelected ^CheckBox (.getSource event))))))
                            (doto (identify! (CheckBox. (tr context-base "mute-audio")) "mute-audio")
                              (.setOnAction (ui/event-handler event ((:mute! (:audio (context))) (.isSelected ^CheckBox (.getSource event))))))
                            (label (tr context-base "reference-image")) reference-path
                            (button context-base "import-reference" "import-reference"
                                    (fn choose-reference []
                                      (when-let [file (dialogs/make-file-dialog (tr (context) "import-reference") [["Images" "*.png" "*.jpg" "*.jpeg" "*.gif" "*.bmp"]]
                                                                                (io/file (:project-root (context)) "reference.png") (.getWindow (.getScene root)))]
                                        (background-io! (context) #(project/import-reference! (:project-root (context)) file)
                                                        (fn reference-imported [path]
                                                          (swap! state assoc :reference-image path)
                                                          (.setText reference-path path)
                                                          (workspace/resource-sync! workspace))))))
                            (row [(label (tr context-base "opacity"))
                                  (number-field "reference-opacity" (:reference-opacity @state 0.35) #(swap! state assoc :reference-opacity (max 0.0 (min 1.0 %))))])
                            (button context-base "save-view" "save-view"
                                    #(change! (context) (fn [d] (assoc d :editor-view (select-keys @state [:zoom :pan-x :pan-y :reference-image :reference-opacity :ghosting :show-workspace-peers :show-bones :show-images :show-names])))))])
        hierarchy-menu (doto (identify! (MenuButton. (tr context-base "new-menu")) "new-menu")
                         (.setFocusTraversable false))
        clip-menu (doto (identify! (MenuButton. (tr context-base "animation-settings")) "animation-settings")
                    (.setFocusTraversable false))
        details (doto (identify! (Label. (tr context-base "properties")) "details")
                  (.setMaxWidth Double/MAX_VALUE))
        details-pane (doto (identify! (column [details right]) "properties-pane")
                       (.setSpacing 0.0) (.setMinHeight 0.0))
        tools (mapv (fn [[id _glyph]]
                      (doto (identify! (ToggleButton. (tr context-base (str "tool-" id))) (str "tool-" id))
                        (.setGraphic (authoring-icon (keyword id)))
                        (.setTooltip (Tooltip. (str (tr context-base (str "tool-" id))
                                                    (when-let [key (get {"rotate" "C" "move" "V" "scale" "X" "shear" "Z"
                                                                         "weights" "G" "pose" "B" "create" "N"} id)]
                                                      (str " · " key)))))
                        (.setMaxWidth Double/MAX_VALUE)
                        (.setFocusTraversable false)
                        (.setOnAction (ui/event-handler _ (select-tool! (context) id)))))
                    [["rotate" "↻"] ["move" "✥"] ["scale" "⤢"] ["shear" "⌁"] ["mesh" "◇"] ["weights" "◉"] ["path" "∿"] ["pose" "✥"] ["create" "＋"]])
        palette-fields (into {} (map (fn [property]
                                       [property (doto (number-field (str "palette-" property) 0.0
                                                                     (fn [value]
                                                                       (pose-change! (context)
                                                                                     (fn [doc]
                                                                                       (if-let [frame (mesh-manipulator (context) doc)]
                                                                                         (change-mesh-value (context) doc frame property value)
                                                                                         (if-let [bone (selected-bone (context) doc)]
                                                                                           (change-displayed-value (context) doc bone property value)
                                                                                           doc))))))
                                                   (.setPrefWidth 64.0) (.setMinWidth 64.0) (.setMaxWidth Double/MAX_VALUE)
                                                   (.setAlignment Pos/CENTER_RIGHT))]))
                             ["rotation" "x" "y" "scale-x" "scale-y" "shear-x" "shear-y"])
        group-properties [["rotation"] ["x" "y"] ["scale-x" "scale-y"] ["shear-x" "shear-y"]]
        group-keys (mapv (fn [id properties]
                           (doto (identify! (Button.) (str "key-" id))
                             (.setFocusTraversable false)
                             (.setGraphic (authoring-icon :key))
                             (.setMinWidth 22.0) (.setPrefWidth 22.0) (.setMaxWidth 22.0)
                             (ui/add-style! "skeletal-key-button")
                             (.setTooltip (Tooltip. (str (tr context-base "key") " · " (coll/join-to-string ", " properties))))
                             (.setOnAction (ui/event-handler _ (key-properties! (context) properties)))))
                         ["rotation" "translation" "scale" "shear"] group-properties)
        transform-tools (doto (GridPane.) (.setHgap 2.0) (.setVgap 2.0))
        _ (doseq [[index properties] (coll/into-> group-properties [] (map-indexed vector))]
            (let [control ^ToggleButton (nth tools index)]
              (.setMinWidth control 76.0)
              (.setPrefWidth control 76.0)
              (.add transform-tools control 0 index)
              (if (= 1 (count properties))
                (.add transform-tools (get palette-fields (first properties)) 1 index 2 1)
                (doseq [[column property] (coll/into-> properties [] (map-indexed vector))]
                  (.add transform-tools (get palette-fields property) (inc column) index)))
              (.add transform-tools (nth group-keys index) 3 index)))
        space-buttons (mapv (fn [space]
                              (doto (identify! (ToggleButton. (tr context-base (str "space-" space))) (str "space-" space))
                                (.setGraphic (authoring-icon (keyword space)))
                                (.setMaxWidth Double/MAX_VALUE)
                                (.setFocusTraversable false)
                                (.setOnAction (ui/event-handler _ (swap! state assoc :coordinate-space space)))))
                            ["local" "parent" "world"])
        auto-key (doto (identify! (ToggleButton. (tr context-base "auto-key")) "auto-key")
                   (.setFocusTraversable false)
                   (.setSelected true)
                   (.setGraphic (authoring-icon :key))
                   (.setMaxWidth Double/MAX_VALUE)
                   (.setOnAction (ui/event-handler event
                                   (swap! state assoc :auto-key (.isSelected ^ToggleButton (.getSource event))))))
        compensation (mapv (fn [[id title]]
                             (doto (identify! (ToggleButton. title) id)
                               (.setGraphic (authoring-icon (if (= "compensate-bones" id) :bone :slot)))
                               (.setTooltip (Tooltip. (tr context-base (str id "-help"))))
                               (.setMaxWidth Double/MAX_VALUE)
                               (.setFocusTraversable false)
                               (.setOnAction (ui/event-handler event
                                               (swap! state assoc (keyword id) (.isSelected ^ToggleButton (.getSource event)))))))
                           [["compensate-bones" (tr context-base "compensate-bones")] ["compensate-images" (tr context-base "compensate-images")]])
        key-edited (button context-base "key-edited" "key-edited" #(key-edited! (context)))
        breadcrumb (doto (identify! (Label.) "selection-path") (.setMaxWidth javafx.scene.layout.Region/USE_PREF_SIZE))
        palette (doto (HBox. 4.0) (.setAlignment Pos/BOTTOM_CENTER))
        _ (.addAll (.getChildren palette) ^java.util.Collection
                   [(column [(nth tools 7) (nth tools 5) (nth tools 8)])
                    transform-tools (column space-buttons) (column (conj compensation auto-key))])
        overlay (doto (identify! (column [breadcrumb palette]) "tool-palette")
                  (.setAlignment Pos/CENTER)
                  (.setMaxHeight javafx.scene.layout.Region/USE_PREF_SIZE))
        mode-overlay (doto (identify! (row [mode-choice (button context-base "fit" "fit" #(do (swap! state assoc :auto-fit true) (fit! (context))))]) "mode-overlay")
                       (.setMaxSize javafx.scene.layout.Region/USE_PREF_SIZE javafx.scene.layout.Region/USE_PREF_SIZE))
        bottom (doto (identify! (column [timeline-tabs]) "animation-dock") (.setSpacing 0.0))
        layout-split (doto (identify! (SplitPane.) "layout-split") (.setOrientation Orientation/VERTICAL) (.setMinSize 0.0 0.0))
        view-node (first (g/tx-nodes-added (g/transact {:undoable false} (g/make-node SkeletalAnimationView))))]
    (add-watch state ::cursor
               (fn [_ state before after]
                 (when (not= (tools/cursor before) (tools/cursor after))
                   (swap! state (fn [current]
                                  (cond-> (dissoc current :pending-pose :draft :pose-undo :pose-redo)
                                    (and (= (:animation before) (:animation after))
                                         (= "animate" (:mode after))
                                         (:pending-pose before)
                                         (zero? (count (:pose-redo before))))
                                    (assoc :discarded-pose (:pending-pose before))))))))
    (.addEventFilter root javafx.scene.input.KeyEvent/KEY_PRESSED
      (ui/event-handler event
        (let [redo (.isShiftDown ^javafx.scene.input.KeyEvent event)
              history (get @state (if redo :pose-redo :pose-undo))]
          (when (and (= KeyCode/Z (.getCode ^javafx.scene.input.KeyEvent event))
                     (.isShortcutDown ^javafx.scene.input.KeyEvent event)
                     (not (instance? javafx.scene.control.TextInputControl (.getTarget ^javafx.scene.input.KeyEvent event)))
                     (or (pos? (count history)) (and (not redo) (:discarded-pose @state))))
            (if (pos? (count history))
              (swap! state tools/undo-pose redo)
              (swap! state tools/restore-discarded))
            (.consume ^javafx.scene.input.KeyEvent event)))))
    (let [items (vec (.getItems ^ToolBar batch-tools))]
      (.clear (.getItems ^ToolBar batch-tools))
      (.addAll (.getItems ^ToolBar clip-tools) ^java.util.Collection items))
    (let [transport (mapv (fn [[id command]]
                            (button context-base id id #(transport! (context) command)))
                          [["previous-key" :previous-key] ["previous-frame" :previous-frame]
                           ["next-frame" :next-frame] ["next-key" :next-key]])]
      (.addAll (.getItems ^ToolBar playback) 2 ^java.util.Collection transport))
    ;; Keep file and authoring commands in named popovers, not a permanent input bar.
    (let [file-controls (vec (.getItems ^ToolBar toolbar))]
      (.clear (.getItems ^ToolBar toolbar))
      (.add (.getItems file-menu) (settings-menu-item (column (into (subvec file-controls 5) [workspace-tools preview-tools])) 400.0))
      (.addAll (.getItems ^ToolBar toolbar) ^java.util.Collection
               [file-menu hierarchy-menu clip-menu view-menu texture-menu]))
    (ui/add-style! toolbar "skeletal-command-bar")
    (doseq [[^MenuButton menu icon] [[file-menu :root] [hierarchy-menu :create] [clip-menu :animation] [view-menu :view] [texture-menu :texture]]]
      (let [title (.getText menu)]
        (.setTooltip menu (Tooltip. title))
        (.setAccessibleText menu title)
        (.setText menu "")
        (.setGraphic menu (authoring-icon icon))
        (ui/add-style! menu "skeletal-command-menu")
        (.setOnShowing menu
                       (ui/event-handler _
                         (when (identical? menu texture-menu) ((:refresh! texture-tools)))
                         (doseq [^CustomMenuItem item (.getItems menu)
                                 :let [scroll ^ScrollPane (.getContent item)]]
                           (.setPrefViewportHeight scroll (min 420.0 (max 180.0 (- (.getHeight (.getScene root)) 110.0)))))))))
    (.add (.getItems view-menu) (settings-menu-item view-tools 400.0))
    (ui/user-data! root ::setup-controls [hierarchy-tools constraint-tools])
    (.add (.getItems hierarchy-menu) (settings-menu-item (column [(row [(nth tools 4) (nth tools 6)]) hierarchy-tools constraint-tools]) 440.0))
    (.add (.getItems clip-menu) (settings-menu-item (column [clip-tools (row [key-edited (button context-base "key-shown" "key-shown"
                                                                                                 #(key-properties! (context) (mapv :property (timeline-tracks (context) (document (context))))))])
                                                             repeat-tools mix-tools mixer-tools]) 700.0))
    ;; Put tool controls over the bottom of the canvas, matching the reference workspace.
    (.addAll (.getChildren palette) ^java.util.Collection
             [(column [(doto (identify! (CheckBox. (tr context-base "bone")) "show-bones")
                         (.setSelected true)
                         (.setOnAction (ui/event-handler event (swap! state assoc :show-bones (.isSelected ^CheckBox (.getSource event))))))
                       (doto (identify! (CheckBox. (tr context-base "images")) "show-images")
                         (.setSelected true)
                         (.setOnAction (ui/event-handler event (swap! state assoc :show-images (.isSelected ^CheckBox (.getSource event))))))
                       (doto (identify! (CheckBox. (tr context-base "names")) "show-names")
                         (.setOnAction (ui/event-handler event (swap! state assoc :show-names (.isSelected ^CheckBox (.getSource event))))))])])
    (StackPane/setAlignment overlay Pos/BOTTOM_CENTER)
    (StackPane/setMargin overlay (Insets. 0.0 8.0 8.0 8.0))
    (StackPane/setAlignment mode-overlay Pos/TOP_LEFT)
    (StackPane/setMargin mode-overlay (Insets. 6.0))
    (.setPickOnBounds viewport false)
    (.addListener (.textProperty search)
                  (reify ChangeListener
                    (changed [_ _ _ value]
                      (swap! state assoc :tree-search value)
                      (refresh-tree! (context) (document (context))))))
    (add-watch state ::selection-history
               (fn [_ _ before after]
                 (let [fields [:selected-kind :selected :selected-bones :selected-slots :selected-attachment :tree-selection]
                       old (select-keys before fields) selected (select-keys after fields)]
                   (when (not= old selected)
                     (if (:selection-replay after)
                       (swap! state dissoc :selection-replay)
                       (swap! state (fn [current]
                                      (let [history (:selection-history current [old])
                                            index (:selection-index current (dec (count history)))
                                            history (conj (subvec (vec history) 0 (inc index)) selected)]
                                        (assoc current :selection-history history :selection-index (dec (count history)))))))))))
    (reset! context-ref (assoc context-base :export-compatibility-label export-status :current-frame current-frame :timeline-scroll timeline-scroll :layout-split layout-split :animation-dock bottom :inspector-scroll right :selection-path breadcrumb :tool-buttons tools :space-buttons space-buttons :group-keys (mapv vector group-keys group-properties) :auto-key-button auto-key :key-edited-button key-edited :palette-fields palette-fields :mix-overlay-choice mix-overlay-choice :workspace-choice workspace-choice :workspace-name workspace-name :mix-enabled-control nil :animation-choice animation-choice :skin-choice skin-choice :duration-field duration-field :play-button play-button :mode-choice mode-choice :tool-choice tool-choice :property-choice property-choice :interpolation-choice interpolation-choice :clip-name-field clip-name-field :refresh-curve (:refresh! curve) :curve curve))
    (doseq [[property field] palette-fields]
      (install-palette-number-preview! (context) field property))
    (doseq [property ["scale-x" "scale-y"]
            :let [field ^TextField (get palette-fields property)]]
      (.setOnMousePressed field
                          (ui/event-handler _
                            (when (and (= "world" (:coordinate-space @state)) (not (mesh-manipulator (context) (document (context)))))
                              (let [doc (document (context))
                                    bone (selected-bone (context) doc)
                                    sampled (entity (pose (context) doc) (:id bone))]
                                (swap! state assoc :editing-local-scale true)
                                (refresh-number-field! field (get sampled (keyword property) 1.0))))))
      (.addListener (.focusedProperty field)
                    (reify ChangeListener
                      (changed [_ _ _ focused]
                        (swap! state assoc :editing-local-scale focused)
                        (when (and focused (= "world" (:coordinate-space @state)) (not (mesh-manipulator (context) (document (context)))))
                          (let [doc (document (context))
                                bone (selected-bone (context) doc)
                                sampled (entity (pose (context) doc) (:id bone))]
                            (refresh-number-field! field (get sampled (keyword property) 1.0))))))))
    (.setPrefWidth import-path 180.0) (.setPromptText import-path (tr context-base "import-path"))
    (.setPrefWidth export-path 150.0)
    (.setMinWidth skin-choice 60.0) (.setPrefWidth skin-choice 110.0)
    (HBox/setHgrow skin-choice Priority/ALWAYS)
    (.setPrefWidth left 260.0) (.setMinWidth left 180.0)
    (.setMinWidth side 210.0) (.setPrefWidth side 260.0)
    (.setMinWidth details-pane 260.0) (.setPrefWidth details-pane 330.0)
    (VBox/setVgrow right Priority/ALWAYS)
    (VBox/setVgrow tree Priority/ALWAYS)
    (.setShowRoot tree true)
    (.setSelectionMode (.getSelectionModel tree) SelectionMode/MULTIPLE)
    (install-tree-cells! tree)
    (.addListener (.selectedItemProperty (.getSelectionModel tree))
                  (reify ChangeListener
                    (changed [_ _ _ item]
                      (when (and item (not (:refreshing @state)))
                        (let [{:keys [kind id] :as entry} (.getValue ^TreeItem item)]
                          (case kind
                            :attachment (select-tree-attachment! (context) entry)
                            :animation (swap! state assoc :animation id :time 0.0 :preview-time 0.0 :mix-transition nil :mixer-enabled false :mixer-state nil :mixer-sample nil :playing false :selected-key nil :selected-keys #{})
                            :skin (do (change! (context) #(data/select-skin % id))
                                      (swap! state assoc :rigging-panel :skins :inspector-token nil))
                            (:bone :slot :ik :transform :path :physics :event) (set-selection! (context) kind id)
                            (set-selection! (context) nil nil))
                          (when (contains? #{:bone :slot :attachment} kind)
                            (sync-tree-selection! (context) (mapv #(.getValue ^TreeItem %) (.getSelectedItems (.getSelectionModel tree))))))))))
    (.addListener (.getSelectedItems (.getSelectionModel tree))
                  (reify ListChangeListener
                    (onChanged [_ _]
                      (when-not (:refreshing @state)
                        (ui/run-later
                          (let [items (mapv #(.getValue ^TreeItem %) (.getSelectedItems (.getSelectionModel tree)))]
                            (if (zero? (count items))
                              (set-selection! (context) nil nil)
                              (when (contains? #{:slot :bone :attachment} (:selected-kind @state))
                                (sync-tree-selection! (context) items)))))))))
    (.addAll (.getChildren viewport) ^java.util.Collection [background (:node renderer) canvas mode-overlay overlay])
    (.setManaged background false)
    (.setManaged canvas false)
    (.setManaged ^Node (:node renderer) false)
    (.setMinSize viewport 120.0 60.0)
    (.setMinHeight right 0.0)
    (.setMinHeight left 0.0)
    (.add (.getChildren side) left)
    (VBox/setVgrow left Priority/ALWAYS)
    (.addAll (.getItems center) ^java.util.Collection [(responsive-tool-host viewport overlay palette) side details-pane])
    (.setDividerPositions center (double-array [0.60 0.79]))
    ;; The resource dock menu overlays the bottom-right corner of the editor.
    ;; Reserve only footer space so it cannot intercept the toolbar overflow.
    (.setPadding toolbar (Insets. 2.0)) (.setPadding left (Insets. 3.0)) (.setPadding bottom (Insets. 0.0 20.0 0.0 0.0))
    (.add (.getChildren side) 0 toolbar)
    (.setBottom root status)
    (.setMinHeight center 200.0)
    (.setMinHeight bottom 180.0)
    (.add (.getItems layout-split) center)
    (.setCenter root layout-split)
    (doseq [group (.getChildren palette)]
      (ui/add-style! group "skeletal-tool-group")
      (when (instance? VBox group)
        (.setSpacing ^VBox group 2.0)
        (.setMaxHeight ^VBox group javafx.scene.layout.Region/USE_PREF_SIZE)))
    (.setConverter mode-choice
                   (proxy [StringConverter] []
                     (toString [value] (tr context-base (str "mode-" value)))
                     (fromString [value] value)))
    (let [timeline-content (doto (column [playback timeline-scroll]) (.setSpacing 0.0))
          curve-content (doto (column [(:node curve)]) (.setSpacing 0.0))]
      (.setContent ^Tab (.get (.getTabs timeline-tabs) 1) timeline-content)
      (.setContent ^Tab (.get (.getTabs timeline-tabs) 0) curve-content)
      (VBox/setVgrow timeline-scroll Priority/ALWAYS)
      (VBox/setVgrow (:node curve) Priority/ALWAYS)
      (VBox/setVgrow event-log Priority/ALWAYS)
      (.addListener (.selectedIndexProperty (.getSelectionModel timeline-tabs))
                    (reify ChangeListener
                      (changed [_ _ _ index]
                        (.remove (.getChildren timeline-content) playback)
                        (.remove (.getChildren ^VBox (:transport-host curve)) playback)
                        (.remove (.getChildren event-content) playback)
                        (.add (.getChildren ^VBox (nth [(:transport-host curve) timeline-content event-content] (int index))) (if (= 0 (int index)) 1 0) playback)))))
    (.setConverter property-choice
                   (proxy [StringConverter] []
                     (toString [value]
                       (if-let [track (coll/first-where #(= value (navigation/channel-id %))
                                                        (timeline-tracks (context) (document (context))))]
                         (track-title (context) track) (str value)))
                     (fromString [value] value)))
    (install-transport-shortcuts! (context))
    (install-animation-toolbar! (context) playback (first (.getChildren ^VBox (:transport-host curve))))
    ;; Switch through the other tab so the shared transport is parented correctly.
    (.select (.getSelectionModel timeline-tabs) 1)
    (.select (.getSelectionModel timeline-tabs) 0)
    (VBox/setVgrow timeline-tabs Priority/ALWAYS)
    (.setMinHeight timeline-tabs 140.0)
    (.setManaged timeline false)
    (.setWrapText status true)
    (.bind (.visibleProperty status) (.isNotEmpty (.textProperty status)))
    (.bind (.managedProperty status) (.visibleProperty status))
    (AnchorPane/setTopAnchor root 0.0) (AnchorPane/setRightAnchor root 0.0)
    (AnchorPane/setBottomAnchor root 0.0) (AnchorPane/setLeftAnchor root 0.0)
    (.add (.getChildren parent) root)
    (canvas-events! (context)) (timeline-events! (context))
    (let [dispose (ui/node-timer! root nil "skeletal-animation-editor" #(refresh! (context) %))]
      (ui/on-closed! tab (fn [_] (remove-watch state ::selection-history) (remove-watch state ::cursor) (dispose) ((:dispose! (:audio (context)))) (reset! (:images (context)) {}) (.remove (.getChildren parent) root))))
    (ui/user-data! root ::context (context))
    view-node))

(defn register-view-types [workspace]
  (workspace/register-view-type workspace
    :id :skeletal-animation
    :label (localization/message "resource.view.skeletal-animation")
    :make-view-fn #'make-view))
