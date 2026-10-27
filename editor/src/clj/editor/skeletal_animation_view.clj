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
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [dynamo.graph :as g]
            [editor.dialogs :as dialogs]
            [editor.localization :as localization]
            [editor.skeletal-animation-constraints :as constraints]
            [editor.skeletal-animation-curve-view :as curve-view]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-geometry :as geometry]
            [editor.skeletal-animation-io :as animation-io]
            [editor.skeletal-animation-mesh :as mesh]
            [editor.skeletal-animation-physics :as physics]
            [editor.skeletal-animation-render :as render]
            [editor.skeletal-animation-slots :as slots]
            [editor.skeletal-animation-timeline :as timeline]
            [editor.skeletal-animation-tools :as tools]
            [editor.ui :as ui]
            [editor.view :as view]
            [editor.workspace :as workspace]
            [util.coll :as coll])
  (:import [java.io File]
           [javafx.beans.value ChangeListener]
           [javafx.collections FXCollections ListChangeListener]
           [javafx.geometry Insets Orientation Pos]
           [javafx.scene Node]
           [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.control Button CheckBox ComboBox CustomMenuItem Label ListView MenuButton SelectionMode ScrollPane ScrollPane$ScrollBarPolicy Separator SplitPane Tab TabPane TabPane$TabClosingPolicy TextField ToggleButton ToolBar Tooltip TreeItem TreeView]
           [javafx.scene.image Image]
           [javafx.scene.input KeyCode MouseButton MouseEvent ScrollEvent]
           [javafx.scene.layout AnchorPane BorderPane HBox Priority StackPane VBox]
           [javafx.scene.paint Color]
           [javafx.scene.text Font]
           [javafx.stage FileChooser FileChooser$ExtensionFilter]))

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

(defn- choice [id values selected action]
  (let [box (identify! (ComboBox. (FXCollections/observableArrayList ^java.util.Collection (vec values))) id)]
    (.setMaxWidth box Double/MAX_VALUE)
    (.setValue box selected)
    (.setOnAction box (ui/event-handler _ (action (.getValue box))))
    box))

(defn- number-field [id value action]
  (let [field (identify! (TextField. (format "%.3f" (double value))) id)
        last-text (atom (.getText field))
        commit (fn []
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
    (.setOnScroll field
                  (ui/event-handler event
                    (when (= field (.getFocusOwner (.getScene field)))
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
                      (when (and before (not after)) (commit)))))
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

(defn- commit! [context doc]
  (when-not (= doc (g/node-value (:resource-node context) :document))
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

(defn- selected-slot [context doc]
  (when (= :slot (:selected-kind @(:state context)))
    (entity (:slots doc) (:selected @(:state context)))))

(defn- sampled-slots [context doc]
  (or (:sampled-slots context)
      (let [state @(:state context)]
        (if (= "animate" (:mode state))
          (slots/sample-slots doc (:id (animation context doc)) (:time state))
          {:slots (:slots doc) :deforms {} :warnings []}))))

(defn- editable-preview-attachment [context doc]
  (or (not= "animate" (:mode @(:state context)))
      (:preview-editable-attachment (entity (:slots (sampled-slots context doc)) (:selected @(:state context))))))

(defn- change! [context f]
  (attempt! context
            #(let [doc (g/node-value (:resource-node context) :document)]
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

(defn- commit-pose! [context doc]
  (if (and (= "animate" (:mode @(:state context))) (false? (:auto-key @(:state context))))
    (do
      (data/validate-document doc)
      (swap! (:state context) tools/stage-pose (g/node-value (:resource-node context) :document) doc))
    (commit! context doc)))

(defn- pose-change! [context f]
  (attempt! context #(let [doc (document context)]
                       (when (and (:attachment-edit-slot context) (not (editable-preview-attachment context doc)))
                         (throw (ex-info (tr context "preview-attachment-readonly") {})))
                       (commit-pose! context (f doc)))))

(defn- evaluated-pose [context doc]
  (let [{:keys [mode time]} @(:state context)]
    (or (:evaluated-pose context)
        (if-let [cache (:pose-cache context)]
          (let [evaluated (data/evaluate-pose-with-slots-state doc (when (= "animate" mode) (:id (animation context doc))) time @cache slots/sample-slots)]
            (reset! cache (:state evaluated))
            (:pose evaluated))
          (data/evaluate-pose-with-slots doc (when (= "animate" mode) (:id (animation context doc))) time slots/sample-slots)))))

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
      (or (nil? attachment) (not (#{"region" "mesh" "path"} (get attachment :type "region")))) []
      (#{"mesh" "path"} (:type attachment))
      (geometry/mesh-world-vertices attachment matrix transforms
                                    (when (= "animate" (:mode @(:state context)))
                                      (get (if (contains? context :sampled-deforms)
                                             (:sampled-deforms context)
                                             (:deforms (sampled-slots context doc)))
                                           (:id slot))))
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
    (let [file (io/file (:project-root context) (string/replace path #"^/" ""))
          key [(.getPath file) (.lastModified file)]]
      (if (contains? @(:images context) key)
        (get @(:images context) key)
        (let [image (when (.isFile file) (Image. (str (.toURI file)) false))
              image (when (and image (not (.isError image))) image)]
          (swap! (:images context) (fn [images]
                                    (assoc (into {} (remove (fn [[old-key _]] (= (first old-key) (first key)))) images) key image)))
          image)))))

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
        render-slots (into []
                           (comp
                             (filter #(and (:attachment %) (#{"region" "mesh"} (get-in % [:attachment :type] "region"))))
                             (map (fn [slot]
                                    (let [attachment (:attachment slot)]
                                      {:id (:id slot)
                                       :points (mapv #(screen context %) (partitionv 2 (attachment-points context doc slot transforms)))
                                       :uvs (vec (partitionv 2 (if (= "mesh" (:type attachment)) (:uvs attachment) [0.0 0.0 1.0 0.0 1.0 1.0 0.0 1.0])))
                                       :triangles (vec (partitionv 3 (if (= "mesh" (:type attachment)) (:triangles attachment) [0 1 2 0 2 3])))
                                       :image (image! context (:path attachment))
                                       :tint (mapv * (subvec (get attachment :color [1.0 1.0 1.0 1.0]) 0 3)
                                                    (subvec (get slot :color [1.0 1.0 1.0 1.0]) 0 3))
                                       :opacity (* (double (get (:color attachment) 3 1)) (double (get (:color slot) 3 1)))}))))
                           (:slots sampled))]
    (swap! (:state context) assoc :preview-warnings (:warnings sampled))
    (.setTransform gc 1.0 0.0 0.0 1.0 0.0 0.0)
    (.clearRect gc 0.0 0.0 width height)
    (.setFill grid (Color/web "#606063")) (.fillRect grid 0.0 0.0 width height)
    (.setFill grid (Color/web "#666669"))
    (doseq [x (range 0.0 width 16.0)
            y (range 0.0 height 16.0)
            :when (even? (+ (long (/ x 16.0)) (long (/ y 16.0))))]
      (.fillRect grid x y 16.0 16.0))
    (.setStroke grid (Color/web "#414144")) (.setLineWidth grid 1.0)
    (.strokeLine grid (:pan-x state) 0.0 (:pan-x state) height)
    (.strokeLine grid 0.0 (:pan-y state) width (:pan-y state))
    ((:render! (:renderer context)) width height (if (false? (:show-images state)) [] render-slots))
    (doseq [{:keys [id points triangles]} render-slots
            :when (and (= :slot (:selected-kind state)) (= selected id) (editable-preview-attachment context doc))]
      (when (#{"mesh" "weights"} (:tool state))
        (doseq [indices triangles]
          (let [triangle (mapv points indices)]
            (.setStroke gc (Color/web "#77a6c8"))
            (.strokePolygon gc (double-array (mapv first triangle)) (double-array (mapv second triangle)) 3))))
      (when (and (= "mesh" (get-in (selected-slot context doc) [:attachment :type]))
                 (#{"mesh" "weights"} (:tool state)))
        (doseq [[index [x y]] (map-indexed vector points)]
          (.setFill gc (if (contains? (:vertices state #{(:vertex state)}) index) Color/ORANGE (Color/web "#73cae5")))
          (.fillOval gc (- x 3.5) (- y 3.5) 7.0 7.0)
          (when (= "weights" (:tool state))
            (let [influences (get-in (selected-slot context doc) [:attachment :weights index])
                  radius (if (contains? (:vertices state) index) 7.0 4.5)]
              (reduce (fn [angle influence]
                        (let [extent (* 360.0 (:weight influence))]
                          (.setFill gc (Color/hsb (double (mod (hash (:bone influence)) 360)) 0.65 0.95))
                          (.fillArc gc (- x radius) (- y radius) (* 2 radius) (* 2 radius) angle extent javafx.scene.shape.ArcType/ROUND)
                          (+ angle extent))) 0.0 influences)))))
      (when (and (pos? (count points)) (not (#{"mesh" "weights"} (:tool state))))
        (let [xs (mapv first points) ys (mapv second points)
              left (reduce min xs) top (reduce min ys)]
          (.setStroke gc (Color/web "#77a6c8"))
          (.setLineWidth gc 1.0)
          (.strokeRect gc left top (- (reduce max xs) left) (- (reduce max ys) top)))))
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
    (when-let [{:keys [start current]} (or (:selection-box state) (:bone-selection-box state))]
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
    (when (:show-bones state)
      (doseq [bone bones
              :let [matrix (get transforms (:id bone))
                    [x y] (screen context (point matrix [0.0 0.0]))
                    [ex ey] (screen context (point matrix [(double (:length bone)) 0.0]))
                    active (or (contains? (:selected-bones state) (:id bone)) (= (:id bone) (:id (selected-bone context doc))))]]
        (.setStroke gc (if active (Color/web "#72b5f4") (Color/web "#c0c7cb")))
        (.setLineWidth gc (if active 3.0 1.5))
        (.strokeLine gc x y ex ey)
        (.setFill gc (if active (Color/web "#72b5f4") (Color/web "#889398")))
        (.strokeLine gc (- x 4.0) y (+ x 4.0) y)
        (.strokeLine gc x (- y 4.0) x (+ y 4.0))
        (when (or active (:show-names state))
          (.setFont gc (Font/font 11.0))
          (.fillText gc (:id bone) (+ ex 6.0) (- ey 4.0)))))
    (when (and (not (:playing state)) (#{"move" "scale" "shear"} (:tool state)))
      (when-let [selected (selected-bone context doc)]
        (let [bone (entity bones (:id selected))
              matrix (get transforms (:id bone))
              [x y] (screen context (point matrix [0.0 0.0]))
              axes (tools/axes bone transforms (:coordinate-space state "parent") (:tool state))]
          (doseq [[index [dx dy]] (map-indexed vector axes)]
            (let [ex (+ x (* 56.0 dx)) ey (- y (* 56.0 dy))]
              (.setStroke gc (Color/web (if (zero? index) "#e67770" "#84c779")))
              (.setFill gc (Color/web (if (zero? index) "#e67770" "#84c779")))
              (.setLineWidth gc 2.0)
              (.strokeLine gc x y ex ey)
              (.fillRect gc (- ex 3.0) (- ey 3.0) 6.0 6.0)
              (.fillText gc (if (zero? index) "X" "Y") (+ ex 5.0) (- ey 5.0)))))))
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
  (let [state @(:state context)
        old-bones (pose context before)
        old-world (data/world-transforms old-bones)
        new-bones (pose context after)
        changed (into #{} (keep (fn [bone] (when (not= bone (entity old-bones (:id bone))) (:id bone)))) new-bones)
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
                            local (into [] (mapcat #(geometry/transform-point inverse %)) (partitionv 2 points))
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
      corrected)))

(defn- change-displayed-value [context doc bone property value]
  (let [evaluated (evaluated-pose context doc)
        sampled (entity (:bones evaluated) (:id bone))
        space (:coordinate-space @(:state context) "parent")
        changes (tools/local-changes sampled (:transforms evaluated) space property value)]
    (compensate-edit context doc (reduce-kv (fn [doc property value] (change-bone-value context doc (:id bone) (name property) value)) doc changes))))

(defn- select-tool! [context tool]
  (let [state @(:state context)]
    (if (and (= "create" tool) (not= "setup" (:mode state)))
      (message! context (tr context "create-setup-only"))
      (when (not= tool (:tool state))
        (swap! (:state context) assoc :previous-tool (:tool state) :tool tool)))))

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

(defn- set-selection! [context kind id]
  (swap! (:state context) assoc :selected-kind kind :selected id :selected-bones (if (= :bone kind) #{id} #{}) :vertex 0 :vertices #{} :bind-bones nil :selected-key nil :selected-keys #{} :inspector-token nil))

(defn- rename-entity! [context kind id new-id update-fn]
  (when (change! context #(update-fn % id {:id new-id}))
    (set-selection! context kind new-id)
    (when (= id (:weight-bone @(:state context)))
      (swap! (:state context) assoc :weight-bone new-id))))

(defn- repair-selection! [context doc]
  (let [{:keys [selected-kind selected weight-bone]} @(:state context)
        entities (case selected-kind :bone (:bones doc) :slot (:slots doc) :ik (:ik doc) :transform (:transform doc) :path (:path doc) :physics (:physics doc) nil)]
    (when (and selected (nil? (entity entities selected)))
      (set-selection! context :bone (:id (first (:bones doc)))))
    (when-let [slot (selected-slot context doc)]
      (let [count (quot (count (get-in slot [:attachment :vertices])) 2)]
        (swap! (:state context) update :vertices #(into #{} (filter (fn [index] (< index count))) %))))
    (when (and weight-bone (nil? (entity (:bones doc) weight-bone)))
      (swap! (:state context) dissoc :weight-bone))))

(defn- attach-image! [context]
  (attempt! context
    (fn []
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

(defn- drag-vertices [context doc selection start target]
  (when-not (editable-preview-attachment context doc)
    (throw (ex-info (tr context "preview-attachment-readonly") {})))
  (let [slot (selected-slot context doc)
        attachment (:attachment slot)
        delta (mapv - target start)
        state @(:state context)]
    (if (= "setup" (:mode state))
      (let [binding (mesh-binding doc slot)
            origin (inverse-point (:slot-transform binding) [0.0 0.0])
            end (inverse-point (:slot-transform binding) delta)]
        (when-not (and origin end) (throw (ex-info (tr context "singular-bone") {})))
        (let [[dx dy] (mapv - end origin)]
          (if (= "path" (:type attachment))
            (let [updated (update attachment :vertices
                                  (fn [values] (reduce (fn [values index] (-> values (update (* 2 index) + dx) (update (inc (* 2 index)) + dy))) values selection)))
                  updated (if-not (:weights attachment)
                            updated
                            (update updated :weights
                                    (fn [weights]
                                      (reduce (fn [weights index]
                                                (update weights index
                                                        (fn [influences]
                                                          (mapv (fn [influence]
                                                                  (let [matrix (get (:bone-transforms binding) (:bone influence))
                                                                        world (mapv + (point matrix [(:x influence) (:y influence)]) delta)
                                                                        [x y] (geometry/inverse-transform-point matrix world)]
                                                                    (assoc influence :x x :y y))) influences)))) weights selection))))]
              (data/update-slot doc (:id slot) {:attachment updated}))
            (data/apply-mesh-edit doc (:id slot)
                                 (mesh/transform-selected attachment selection [1.0 0.0 0.0 1.0 dx dy] binding)))))
      (let [transforms (:transforms (evaluated-pose context doc))
            weighted (coll/not-empty (:weights attachment))
            animation-id (:id (animation context doc))
            values (or (get (:deforms (sampled-slots context doc)) (:id slot))
                       (vec (repeat (data/deform-size attachment) 0.0)))
            influences (if weighted weighted (mapv (fn [_] [{:bone (:bone slot)}]) (partitionv 2 (:vertices attachment))))
            [values _] (reduce-kv
                         (fn [[values offset] index vertex-influences]
                           [(if-not (contains? selection index)
                              values
                              (reduce-kv (fn [values influence-index influence]
                                           (let [transform (get transforms (:bone influence))
                                                 origin (inverse-point transform [0.0 0.0])
                                                 end (inverse-point transform delta)
                                                 index (+ offset (* 2 influence-index))]
                                             (when-not (and origin end) (throw (ex-info (tr context "singular-bone") {})))
                                             (let [[dx dy] (mapv - end origin)]
                                               (-> values (update index + dx) (update (inc index) + dy)))))
                                         values vertex-influences))
                            (+ offset (* 2 (count vertex-influences)))])
                         [values 0] influences)
            track (coll/first-where #(= (:id slot) (:slot %)) (:deforms (animation context doc)))]
        (data/set-deform-keyframe doc animation-id (:id slot) (:time state) values
                                 (interpolation-at (:keys track) (:time state) (:interpolation state)))))))

(defn- brush-document [context doc target strength]
  (when-not (= "setup" (:mode @(:state context)))
    (throw (ex-info (tr context "weights-setup-only") {})))
  (let [state @(:state context) slot (selected-slot context doc)
        result (mesh/brush-weights (:attachment slot)
                                  {:mode (keyword (:brush-mode state)) :center target
                                   :radius (/ (:brush-radius state) (:zoom state))
                                   :strength strength :bone-id (or (:weight-bone state) (:bone slot))
                                   :falloff 2.0 :locked-bones (:locked-bones state #{})}
                                  (mesh-binding doc slot))]
    (data/apply-mesh-edit doc (:id slot) result)))

(defn- binding-list [context doc slot]
  (let [ids (mapv :id (:bones doc))
        list-view (doto (identify! (ListView. (FXCollections/observableArrayList ^java.util.Collection ids)) "bind-bones")
                    (.setPrefHeight 88.0) (.setMinHeight 60.0))
        selection (.getSelectionModel list-view)
        selected (or (:bind-bones @(:state context))
                     (into #{(:bone slot)} (comp (mapcat identity) (map :bone)) (get-in slot [:attachment :weights])))]
    (.setSelectionMode selection SelectionMode/MULTIPLE)
    (doseq [id selected] (.select selection ^Object id))
    (.addListener (.getSelectedItems selection)
                  (reify ListChangeListener
                    (onChanged [_ _]
                      (swap! (:state context) assoc :bind-bones (set (.getSelectedItems selection))))))
    list-view))

(declare background-io!)

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

(defn- mesh-tools [context doc slot field]
  (let [state @(:state context) attachment (:attachment slot)
        selection #(mesh-selection context attachment)
        locked (:locked-bones state #{})
        weight-bone (or (:weight-bone state) (:bone slot))
        run-edit (fn [f] (attempt! context #(edit-mesh! context f)))]
    [(label (tr context "edit-mesh"))
     (choice "mesh-mode" ["modify" "create" "delete"] (:mesh-mode state "modify")
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
           (button context "reset-mesh" "reset-mesh" #(rebuild-mesh! context :reset))])
     (field "trace-threshold" "trace-threshold" (:trace-threshold state 16)
            #(swap! (:state context) assoc :trace-threshold (max 0 (min 255 (int %)))))
     (field "trace-simplify" "trace-simplify" (:trace-simplify state 1.0)
            #(swap! (:state context) assoc :trace-simplify (max 0.0 %)))
     (field "trace-padding" "trace-padding" (:trace-padding state 0)
            #(swap! (:state context) assoc :trace-padding (max 0 (min 128 (int %)))))
     (button context "trace-mesh" "trace-mesh" #(rebuild-mesh! context :trace))
     (doto (label (tr context "mesh-rebuild-notice")) (.setWrapText true))
     (label (tr context "weight-tools"))
     (label (tr context "binding-bones"))
     (binding-list context doc slot)
     (choice "brush-mode" ["direct" "add" "subtract" "replace" "smooth"] (:brush-mode state "direct")
             #(swap! (:state context) assoc :brush-mode % :tool "weights"))
     (field "brush-radius" "brush-radius" (:brush-radius state 40.0)
            #(swap! (:state context) assoc :brush-radius (max 1.0 (min 500.0 %))))
     (field "brush-strength" "brush-strength" (:brush-strength state 0.25)
            #(swap! (:state context) assoc :brush-strength (max 0.0 (min 1.0 %))))
     (doto (identify! (CheckBox. (tr context "lock-weight-bone")) "lock-weight-bone")
       (.setSelected (contains? locked weight-bone))
       (.setOnAction (ui/event-handler event
                       (swap! (:state context) update :locked-bones
                              (if (.isSelected ^CheckBox (.getSource event)) (fnil conj #{}) disj) weight-bone))))
     (row [(button context "auto-weights" "auto-weights"
                   #(run-edit (fn [attachment binding]
                                (mesh/bind-weights attachment (vec (sort (or (:bind-bones @(:state context)) #{(:bone slot)}))) binding
                                                   {:max-influences 4 :falloff 2.0 :locked-bones locked}))))
           (button context "smooth-weights" "smooth-weights"
                   #(run-edit (fn [attachment binding] (mesh/smooth-weights attachment (selection) 0.5 binding locked))))])
     (row [(button context "prune-weights" "prune-weights"
                   #(run-edit (fn [attachment _] (mesh/prune-weights attachment (selection)
                                                                 {:threshold 0.02 :max-influences 4 :locked-bones locked}))))
           (button context "normalize-weights" "normalize-weights"
                   #(run-edit (fn [attachment _] (mesh/normalize-weights attachment (selection) locked))))])
     (row [(choice "mirror-axis" ["x" "y"] (:mirror-axis state "x") #(swap! (:state context) assoc :mirror-axis %))
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
                                                 mapping (assoc binding :locked-bones locked))))))]))

(defn- path-inspector [context doc slot field]
  (let [attachment (:attachment slot)
        state @(:state context)
        points (partitionv 2 (attachment-points context doc slot (:transforms (evaluated-pose context doc))))
        index (min (dec (count points)) (:vertex state))
        update-attachment (fn [changes] (change! context #(data/update-slot % (:id slot) {:attachment (merge attachment changes)})))]
    (into [(label (tr context "path-attachment"))
           (text-field "slot-name" (:id slot) #(rename-entity! context :slot (:id slot) % data/update-slot))
           (choice "slot-bone" (mapv :id (:bones doc)) (:bone slot)
                   #(change! context (fn [d] (data/update-slot d (:id slot) {:bone %}))))
           (doto (identify! (CheckBox. (tr context "closed-path")) "path-closed")
             (.setSelected (boolean (:closed attachment)))
             (.setDisable (= "animate" (:mode state)))
             (.setOnAction (ui/event-handler event (update-attachment {:closed (.isSelected ^CheckBox (.getSource event))}))))
           (doto (identify! (CheckBox. (tr context "constant-speed")) "path-constant-speed")
             (.setSelected (boolean (:constant-speed attachment)))
             (.setDisable (= "animate" (:mode state)))
             (.setOnAction (ui/event-handler event (update-attachment {:constant-speed (.isSelected ^CheckBox (.getSource event))}))))
           (field "vertex-index" "vertex" index #(swap! (:state context) assoc :vertex (max 0 (min (dec (count points)) (long %))) :vertices #{(max 0 (min (dec (count points)) (long %)))} :inspector-token nil))
           (button context "edit-path" "edit-path" #(swap! (:state context) assoc :tool "path"))]
          (mapv (fn [[axis property]]
                  (field (str "path-point-" property) property (get (nth points index) axis)
                         #(change! context (fn [d]
                                             (let [slot (selected-slot context d)
                                                   current (nth (partitionv 2 (attachment-points context d slot (:transforms (evaluated-pose context d)))) index)]
                                               (drag-vertices context d #{index} current (assoc current axis %)))))))
                [[0 "x"] [1 "y"]]))))

(defn- entity-inspector [context doc]
  (let [{:keys [selected-kind selected vertex]} @(:state context)
        context (cond-> context (= :slot selected-kind) (assoc :attachment-edit-slot selected))
        field (fn [id title value action] (column [(label (tr context title)) (number-field id value #(attempt! context (fn [] (action %))))]))]
    (case (when selected selected-kind)
      :physics
      (let [state @(:state context)
            animated (= "animate" (:mode state))
            constraint (entity (data/sample-physics doc (when animated (:id (animation context doc))) (:time state)) selected)
            update-constraint (fn [changes] (change! context #(data/update-physics % selected changes)))
            channel-id (if (= "global" (:physics-scope state)) "" selected)]
        (into [(label (tr context "physics-constraint"))
               (text-field "physics-name" selected #(rename-entity! context :physics selected % data/update-physics))
               (label (tr context "bone"))
               (choice "physics-bone" (mapv :id (:bones doc)) (:bone constraint) #(update-constraint {:bone %}))
               (field "physics-order" "constraint-order" (:order constraint) #(update-constraint {:order (long %)}))
               (label (tr context "physics-scope"))
               (choice "physics-scope" ["constraint" "global"] (:physics-scope state "constraint")
                       #(swap! (:state context) assoc :physics-scope % :selected-key nil :selected-keys #{} :inspector-token nil))
               (button context "physics-reset-key" "physics-reset-key"
                       #(change! context (fn [d] (let [d (if (pos? (count (:animations d))) d (data/add-animation d {:id "default" :duration 1.0 :tracks []}))]
                                                   (data/set-physics-reset-keyframe d (:id (animation context d)) channel-id (:time @(:state context)))))))]
              (into (mapv (fn [property]
                            (doto (field (str "physics-" property) property (get constraint (keyword property))
                                         #(update-constraint {(keyword property) %}))
                              (.setDisable animated)))
                          ["x" "y" "rotate" "scale-x" "shear-x" "limit" "fps"])
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
            sampled (entity (if (= "animate" (:mode @(:state context)))
                              (data/sample-pose doc (:id (animation context doc)) (:time @(:state context)))
                              (:bones doc)) selected)]
        (into [(label (tr context "bone"))
               (text-field "bone-name" (:id bone)
                           #(rename-entity! context :bone selected % data/update-bone))
               (label (tr context "parent"))
               (choice "parent" (into ["—"] (comp (map :id) (remove #{selected})) (:bones doc))
                       (or (:parent bone) "—")
                       #(change! context (fn [d] (data/update-bone d selected {:parent (when-not (= "—" %) %)}))))]
              (into
                (mapv (fn [property]
                        (field (str "bone-" property) property (get sampled (keyword property) 0.0)
                               #(pose-change! context (fn [d] (change-bone-value context d selected property %)))))
                      transform-properties)
                [(label (tr context "inherit"))
                 (choice "bone-inherit" ["normal" "onlyTranslation" "noRotationOrReflection" "noScale" "noScaleOrReflection"]
                         (get bone :inherit "normal")
                         #(change! context (fn [d] (data/update-bone d selected {:inherit %}))))
                 (field "bone-length" "length" (:length bone)
                        #(change! context (fn [d] (data/update-bone d selected {:length %}))))])))
      :slot
      (if-not (editable-preview-attachment context doc)
        [(doto (label (tr context "preview-attachment-readonly")) (.setWrapText true))]
      (if (= "path" (get-in (selected-slot context doc) [:attachment :type]))
        (path-inspector context doc (selected-slot context doc) field)
      (let [slot (selected-slot context doc)
            attachment (:attachment slot)
            mesh (= "mesh" (:type attachment))
            update-attachment (fn [changes] (change! context #(data/update-slot % selected {:attachment (merge attachment changes)})))
            vertex-count (quot (count (:uvs attachment)) 2)]
        (into [(label (tr context "attachment"))
               (text-field "slot-name" (:id slot) #(rename-entity! context :slot selected % data/update-slot))
               (label (tr context "bone"))
               (choice "slot-bone" (mapv :id (:bones doc)) (:bone slot)
                       #(change! context (fn [d] (data/update-slot d selected {:bone %}))))
               (label (tr context "image"))
               (text-field "image-path" (:path attachment) #(update-attachment {:path %}))]
              (into
                (vec (when (and attachment (= "region" (get attachment :type "region")))
                  (into (mapv (fn [property] (field (str "attachment-" property) property (get attachment (keyword property))
                                                     #(update-attachment {(keyword property) %})))
                              ["x" "y" "rotation" "scale-x" "scale-y" "width" "height"])
                        [(button context "make-mesh" "make-mesh"
                                 #(do (update-attachment (geometry/region-to-mesh attachment 3 3))
                                      (swap! (:state context) assoc :tool "mesh")))])))
                (when mesh
                  (into [(label (str (tr context "vertices") ": " vertex-count))
                   (field "vertex-index" "vertex" vertex #(let [index (max 0 (min (dec vertex-count) (long %)))]
                                                            (swap! (:state context) assoc :vertex index :vertices #{index} :inspector-token nil)))
                   (label (tr context "weight-bone"))
                   (choice "weight-bone" (mapv :id (:bones doc)) (or (:weight-bone @(:state context)) (:bone slot))
                           #(swap! (:state context) assoc :weight-bone % :inspector-token nil))
                   (field "weight-value" "weight" (:weight @(:state context)) #(swap! (:state context) assoc :weight (min 1.0 (max 0.0 %))))
                   (button context "assign-weight" "assign-weight"
                           #(change! context (fn [d] (reduce (fn [d index] (apply-weight! context d index (or (:weight-bone @(:state context)) (:bone slot)) (:weight @(:state context))))
                                             d (or (coll/not-empty (:vertices @(:state context))) #{vertex})))))
                   (label (str (tr context "weights") "\n"
                               (coll/join-to-string "\n" (mapv #(format "%s  %.3f" (:bone %) (double (:weight %)))
                                                          (get (:weights attachment) vertex)))))
                   (label (tr context "mesh-help"))]
                        (mesh-tools context doc slot field))))))))
      :path
      (let [constraint (entity (data/sample-paths doc (when (= "animate" (:mode @(:state context))) (:id (animation context doc))) (:time @(:state context))) selected)
            update-constraint (fn [changes] (change! context #(data/update-path % selected changes)))]
        (into [(label (tr context "path-constraint"))
               (text-field "path-name" selected #(rename-entity! context :path selected % data/update-path))
               (label (tr context "target-slot"))
               (choice "path-target" (into [] (comp (filter #(= "path" (get-in % [:attachment :type]))) (map :id)) (:slots doc)) (:target constraint) #(update-constraint {:target %}))
               (label (tr context "constrained-bones"))
               (text-field "path-bones" (coll/join-to-string ", " (:bones constraint)) #(update-constraint {:bones (into [] (comp (map string/trim) (remove string/blank?)) (string/split % #","))}))
               (field "path-order" "constraint-order" (:order constraint) #(update-constraint {:order (long %)}))
               (choice "path-position-mode" ["fixed" "percent"] (:position-mode constraint) #(update-constraint {:position-mode %}))
               (choice "path-spacing-mode" ["length" "fixed" "percent" "proportional"] (:spacing-mode constraint) #(update-constraint {:spacing-mode %}))
               (choice "path-rotate-mode" ["tangent" "chain" "chainScale"] (:rotate-mode constraint) #(update-constraint {:rotate-mode %}))
               (field "path-rotation" "rotation" (:rotation constraint) #(update-constraint {:rotation %}))]
              (mapv (fn [property]
                      (field (str "path-" property) property (get constraint (keyword property))
                             #(pose-change! context (fn [d]
                                                 (if (= "animate" (:mode @(:state context)))
                                                   (data/set-path-keyframe d (:id (animation context d)) selected property (:time @(:state context)) %
                                                                          (interpolation-at (:keys (coll/first-where (fn [track] (and (= selected (:path track)) (= property (:property track)))) (:path-tracks (animation context d))))
                                                                                            (:time @(:state context)) (:interpolation @(:state context))))
                                                   (data/update-path d selected {(keyword property) %}))))))
                    ["position" "spacing" "mix-rotate" "mix-x" "mix-y"])))
      :transform
      (let [constraint (entity (data/sample-transforms doc (when (= "animate" (:mode @(:state context))) (:id (animation context doc)))
                                                       (:time @(:state context))) selected)
            update-constraint (fn [changes] (change! context #(data/update-transform % selected changes)))
            mix-fields ["mix-rotate" "mix-x" "mix-y" "mix-scale-x" "mix-scale-y" "mix-shear-y"]]
        (into [(label (tr context "transform-constraint"))
               (text-field "transform-name" selected #(rename-entity! context :transform selected % data/update-transform))
               (label (tr context "target"))
               (choice "transform-target" (into [] (remove (set (:bones constraint))) (mapv :id (:bones doc))) (:target constraint)
                       #(update-constraint {:target %}))
               (label (tr context "constrained-bones"))
               (text-field "transform-bones" (coll/join-to-string ", " (:bones constraint))
                           #(update-constraint {:bones (into [] (comp (map string/trim) (remove string/blank?)) (string/split % #","))}))
               (field "transform-order" "constraint-order" (:order constraint 0) #(update-constraint {:order (long %)}))
               (choice "transform-space" ["world" "local"] (if (:local constraint) "local" "world")
                       #(update-constraint {:local (= "local" %)}))
               (choice "transform-mode" ["absolute" "relative"] (if (:relative constraint) "relative" "absolute")
                       #(update-constraint {:relative (= "relative" %)}))]
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
                    (mapv (fn [property]
                            (field (str "transform-offset-" property) property (get constraint (keyword property) 0.0)
                                   #(update-constraint {(keyword property) %})))
                          ["rotation" "x" "y" "scale-x" "scale-y" "shear-y"]))))
      :ik
      (let [ik (entity (if (= "animate" (:mode @(:state context)))
                         (data/sample-ik doc (:id (animation context doc)) (:time @(:state context)))
                         (:ik doc)) selected)
            update-ik (fn [changes]
                        (pose-change! context
                                 (fn [d]
                                   (if (= "animate" (:mode @(:state context)))
                                     (let [time (:time @(:state context))
                                           keys (:keys (coll/first-where #(= selected (:ik %)) (:ik-tracks (animation context d))))]
                                       (if (contains? changes :softness)
                                         (data/set-ik-softness-keyframe d (:id (animation context d)) selected time (:softness changes)
                                                                        (interpolation-at (mapv #(assoc % :interpolation (:softness-interpolation %) :curve (:softness-curve %)) keys)
                                                                                          time (:interpolation @(:state context))))
                                         (data/set-ik-keyframe-values d (:id (animation context d)) selected time changes
                                                                      (interpolation-at keys time (:interpolation @(:state context))))))
                                     (data/update-ik d selected changes)))))]
        [(label "IK")
         (text-field "ik-name" selected #(rename-entity! context :ik selected % data/update-ik))
         (label (tr context "target"))
         (choice "ik-target" (mapv :id (:bones doc)) (:target ik)
                 #(change! context (fn [d] (data/update-ik d selected {:target %}))))
         (label (tr context "constrained-bones"))
         (text-field "ik-bones" (coll/join-to-string ", " (:bones ik))
                     #(change! context (fn [d] (data/update-ik d selected {:bones (into [] (comp (map string/trim) (remove string/blank?)) (string/split % #","))}))))
         (field "ik-order" "constraint-order" (:order ik 0) #(change! context (fn [d] (data/update-ik d selected {:order (long %)}))))
         (field "ik-mix" "mix" (:mix ik) #(update-ik {:mix %}))
         (field "ik-softness" "softness" (:softness ik 0.0) #(update-ik {:softness %}))
         (doto (identify! (CheckBox. (tr context "compress")) "ik-compress")
           (.setSelected (boolean (:compress ik)))
           (.setOnAction (ui/event-handler event (update-ik {:compress (.isSelected ^CheckBox (.getSource event))}))))
         (doto (identify! (CheckBox. (tr context "stretch")) "ik-stretch")
           (.setSelected (boolean (:stretch ik)))
           (.setOnAction (ui/event-handler event (update-ik {:stretch (.isSelected ^CheckBox (.getSource event))}))))
         (doto (identify! (CheckBox. (tr context "uniform")) "ik-uniform")
           (.setSelected (boolean (:uniform ik)))
           (.setDisable (= "animate" (:mode @(:state context))))
           (.setOnAction (ui/event-handler event
                           (change! context (fn [d] (data/update-ik d selected {:uniform (.isSelected ^CheckBox (.getSource event))}))))))
         (choice "ik-bend" ["+" "−"] (if (:bend-positive ik) "+" "−")
                 #(update-ik {:bend-positive (= "+" %)}))])
      [(label (tr context "select-help"))])))

(defn- filter-tree! [^TreeItem item query]
  (let [children (filterv #(filter-tree! % query) (vec (.getChildren item)))
        matches (string/includes? (string/lower-case (:id (.getValue item))) query)]
    (when-not matches (.setAll (.getChildren item) ^java.util.Collection children))
    (when (pos? (count children)) (.setExpanded item true))
    (or matches (pos? (count children)))))

(defn- refresh-tree! [context doc]
  (let [previous (loop [pending (if-let [root (.getRoot ^TreeView (:tree context))] [root] []) result {}]
                   (if-let [^TreeItem item (peek pending)]
                     (recur (into (pop pending) (.getChildren item)) (assoc result (.getValue item) (.isExpanded item)))
                     result))
        items (atom {})
        add-bone (fn add-bone [bone]
                   (let [item (TreeItem. {:kind :bone :id (:id bone)})]
                     (swap! items assoc [:bone (:id bone)] item)
                     (.setExpanded item (get previous (.getValue item) false))
                     (.addAll (.getChildren item) ^java.util.Collection
                              (into (mapv add-bone (filterv #(= (:id bone) (:parent %)) (:bones doc)))
                                    (map (fn [slot]
                                           (let [child (TreeItem. {:kind :slot :id (:id slot)})]
                                             (swap! items assoc [:slot (:id slot)] child)
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
    (doseq [[kind title values] [[:slot "draw-order" (mapv :id (:slots doc))]
                                [:skin "skins" (data/skin-names doc)]
                                [:animation "animations" (mapv :id (:animations doc))]
                                [:event "events" (mapv :name (:events doc))]]]
      (let [group (TreeItem. {:kind :group :id (tr context title)})]
        (.setExpanded group (get previous (.getValue group) false))
        (.addAll (.getChildren group) ^java.util.Collection
                 (mapv #(TreeItem. {:kind kind :id %}) values))
        (.add (.getChildren root) group)))
    (let [query (string/lower-case (string/trim (:tree-search @(:state context) "")))]
      (when-not (string/blank? query) (filter-tree! root query)))
    (.setExpanded root true)
    (swap! (:state context) assoc :refreshing true)
    (.setRoot ^TreeView (:tree context) root)
    (when-let [item (get @items selected)] (.select (.getSelectionModel ^TreeView (:tree context)) ^TreeItem item))
    (swap! (:state context) assoc :refreshing false)))

(defn- timeline-tracks [context doc]
  (let [bone (selected-bone context doc)
        slot (selected-slot context doc)
        animation (animation context doc)]
    (cond-> (mapv (fn [property]
                    {:bone (:id bone) :property property
                     :keys (:keys (coll/first-where #(and (= (:id bone) (:bone %)) (= property (:property %))) (:tracks animation)))})
                  (if (#{:transform :ik :path :physics} (:selected-kind @(:state context))) [] transform-properties))
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
      (#{"mesh" "path"} (get-in slot [:attachment :type]))
      (as-> tracks
            ;; Put the selected attachment's FFD lane before its parent bone
            ;; channels so it remains visible in a compact timeline.
            (into [{:slot (:id slot) :property "deform"
                    :keys (:keys (coll/first-where #(= (:id slot) (:slot %)) (:deforms animation)))}] tracks))
      (= :ik (:selected-kind @(:state context)))
      (into (mapv (fn [property]
                    {:ik (:selected @(:state context)) :property property
                     :keys (cond->> (:keys (coll/first-where #(= (:selected @(:state context)) (:ik %)) (:ik-tracks animation)))
                             (= "IK softness" property)
                             (mapv #(assoc % :value (:softness % 0.0) :interpolation (:softness-interpolation % "linear") :curve (:softness-curve %))))})
                  ["IK mix" "IK softness"])))))

(defn- timeline-x [context time]
  (+ 110.0 (:timeline-pan @(:state context)) (* time 120.0 (:timeline-zoom @(:state context)))))

(defn- timeline-time [context x]
  (let [state @(:state context)
        duration (or (:duration (animation context (document context))) 1.0)
        value (/ (- x 110.0 (:timeline-pan state)) (* 120.0 (:timeline-zoom state)))
        fps (double (:fps state))]
    (max 0.0 (min duration (/ (Math/round (* fps value)) fps)))))

(defn- key-ref [track key]
  (cond-> {:type (cond (:physics track) :physics (:transform track) :transform (:path track) :path (:ik track) :ik (:slot track) :deform :else :bone)
           :id (or (:physics track) (:transform track) (:path track) (:ik track) (:slot track) (:bone track)) :time (double (:time key))}
    (not (or (:ik track) (:slot track))) (assoc :property (:property track))))

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
        duration (or (:duration (animation context doc)) 1.0)
        tracks (timeline-tracks context doc)]
    (.setFill gc (Color/web "#242424")) (.fillRect gc 0.0 0.0 width height)
    (.setFont gc (Font/font 11.0))
    (doseq [[i track] (map-indexed vector tracks)]
      (.setFill gc (Color/web (if (even? i) "#292929" "#2d2d2d")))
      (.fillRect gc 0.0 (+ 25.0 (* 23.0 i)) width 23.0)
      (.setFill gc (Color/web "#c8c8c8")) (.fillText gc (:property track) 8.0 (+ 41.0 (* 23.0 i)))
      (doseq [key (:keys track)
              :let [x (timeline-x context (:time key)) y (+ 36.0 (* 23.0 i))]]
        (.setFill gc (if (or (contains? (:selected-keys state) (key-ref track key)) (= [(:property track) (:time key)] (:selected-key state))) Color/ORANGE (Color/web "#70ade6")))
        (.fillPolygon gc (double-array [x (+ x 4.0) x (- x 4.0)]) (double-array [(- y 4.0) y (+ y 4.0) y]) 4)))
    (let [step (Math/pow 10.0 (Math/ceil (/ (Math/log (/ 0.5 (:timeline-zoom state))) (Math/log 10.0))))]
      (doseq [t (range 0.0 (+ duration step) step)
              :let [x (timeline-x context t)] :when (<= 110.0 x width)]
        (.setStroke gc (Color/web "#3b3e42")) (.strokeLine gc x 22.0 x height)
        (.setFill gc (Color/web "#b2b6ba")) (.fillText gc (str (Math/round (* t (:fps state 60.0)))) (+ x 3.0) 16.0)))
    (let [x (timeline-x context (:time state))]
      (.setStroke gc (Color/web "#6bc4bf")) (.setLineWidth gc 1.0) (.strokeLine gc x 0.0 x height))
    (when-let [{[x0 y0] :start [x1 y1] :current} (:key-selection-box state)]
      (.setStroke gc (Color/web "#70ade6"))
      (.strokeRect gc (min x0 x1) (min y0 y1) (abs (- x1 x0)) (abs (- y1 y0))))
    (.setStroke gc (Color/web "#50545a")) (.strokeLine gc 110.0 0.0 110.0 height)))

(defn- delete-selection! [context]
  (let [{:keys [selected-kind selected]} @(:state context)]
    (change! context #(case selected-kind
                        :bone (data/remove-bone % selected)
                        :slot (data/remove-slot % selected)
                        :ik (data/remove-ik % selected)
                        :transform (data/remove-transform % selected)
                        :path (data/remove-path % selected)
                        :physics (data/remove-physics % selected)
                        %))
    (set-selection! context nil nil)))

(defn- nearest-vertex [context doc x y]
  (when-let [slot (selected-slot context doc)]
    (when (and (editable-preview-attachment context doc) (#{"mesh" "path"} (get-in slot [:attachment :type])))
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
              rotation (+ initial (Math/toDegrees (Math/atan2 (Math/sin (- angle1 angle0)) (Math/cos (- angle1 angle0)))))
              rotation (if (:snap-rotation @(:state context)) (* 15.0 (Math/round (/ rotation 15.0))) rotation)]
          (if world-space
            (change-displayed-value context doc bone "rotation" rotation)
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
        (let [[dx dy] (mapv - p1 p0)]
          (-> (change-bone-value context doc (:id bone) "x" (+ (:x sampled) dx))
              (#(change-bone-value context % (:id bone) "y" (+ (:y sampled) dy)))))))))

(defn- drag-pose-chain [context doc bone target]
  (let [selected (:selected-bones @(:state context))
        sampled (pose context doc)
        by-id (into {} (map (juxt :id identity)) sampled)
        chain (loop [id (:id bone) chain []]
                (if (and id (contains? selected id))
                  (recur (:parent (get by-id id)) (conj chain id))
                  (vec (rseq chain))))]
    (when (> (count chain) 1)
      (let [solved (geometry/solve-pose-chain sampled chain target)]
        (reduce (fn [result solved-bone]
                  (if (contains? selected (:id solved-bone))
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

(defn- gizmo-axis [context doc x y]
  (when-let [bone (selected-bone context doc)]
    (when (#{"move" "scale" "shear"} (:tool @(:state context)))
      (let [transforms (:transforms (evaluated-pose context doc))
            [px py] (screen context (point (get transforms (:id bone)) [0.0 0.0]))
            axes (tools/axes bone transforms (:coordinate-space @(:state context) "parent") (:tool @(:state context)))]
        (coll/first-where some?
                         (mapv (fn [index [dx dy]]
                                 (let [along (+ (* (- x px) dx) (* (- py y) dy))
                                       across (- (* (- x px) dy) (* (- py y) dx))]
                                   (when (and (< 12.0 along 65.0) (< (abs across) 8.0)) index)))
                               [0 1] axes))))))

(declare key-properties!)

(defn- key-edited! [context]
  (when-let [pending (tools/pending-document @(:state context) (g/node-value (:resource-node context) :document))]
    (attempt! context #(commit! context pending))))

(defn- canvas-events! [context]
  (let [^Canvas canvas (:canvas context)]
    (.setFocusTraversable canvas true)
    (.setOnMouseMoved canvas
      (ui/event-handler event
        (swap! (:state context) assoc :pointer [(.getX ^MouseEvent event) (.getY ^MouseEvent event)])))
    (.setOnMouseExited canvas
      (ui/event-handler _ (swap! (:state context) dissoc :pointer)))
    (.setOnMousePressed canvas
      (ui/event-handler event
        (.requestFocus canvas)
        (let [doc (document context) state @(:state context)
              x (.getX ^MouseEvent event) y (.getY ^MouseEvent event)
              middle (= MouseButton/MIDDLE (.getButton ^MouseEvent event))
              pan (and middle (.isAltDown ^MouseEvent event))
              box-select (and middle (not pan))
              primary (= MouseButton/PRIMARY (.getButton ^MouseEvent event))
              axis (when primary (gizmo-axis context doc x y))
              create-bone (and primary (= "create" (:tool state)) (= "setup" (:mode state)))
              slot (selected-slot context doc)
              mesh-tool (and (editable-preview-attachment context doc)
                             (or (and (= "mesh" (get-in slot [:attachment :type])) (#{"mesh" "weights"} (:tool state)))
                                 (and (= "path" (get-in slot [:attachment :type])) (= "path" (:tool state)))))
              vertex (when mesh-tool (nearest-vertex context doc x y))
              additive (or (.isShortcutDown ^MouseEvent event) (.isShiftDown ^MouseEvent event))
              selection (:vertices state #{})
              brush (and mesh-tool (= "weights" (:tool state)) (not= "direct" (:brush-mode state)))
              create (and mesh-tool (= "mesh" (:tool state)) (= "create" (:mesh-mode state)) (nil? vertex))
              delete (and mesh-tool (= "mesh" (:tool state)) (some? vertex)
                          (or (= "delete" (:mesh-mode state)) (= 2 (.getClickCount ^MouseEvent event))))]
          (cond
            (= MouseButton/SECONDARY (.getButton ^MouseEvent event))
            (select-tool! context (:previous-tool state "move"))

            (or middle primary)
            (do
              (when primary (swap! (:state context) assoc :playing false))
              (cond
                pan nil
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
                mesh-tool (swap! (:state context) assoc :selection-box {:start [x y] :current [x y] :original (if additive selection #{})}
                                 :vertices (if additive selection #{}))
                (and (nil? axis) (not mesh-tool))
                (let [transforms (:transforms (evaluated-pose context doc))
                      nearest (first (sort-by second
                                             (mapv (fn [bone]
                                                     (let [[px py] (screen context (point (get transforms (:id bone)) [0.0 0.0]))]
                                                       [(:id bone) (Math/hypot (- px x) (- py y))])) (:bones doc))))]
                  (if (and nearest (< (double (second nearest)) 16.0))
                    (let [id (first nearest) previous (:selected-bones state #{})]
                      (swap! (:state context) assoc :selected id :selected-kind :bone)
                      (when (or additive (not (contains? previous id)))
                        (set-selection! context :bone id)
                        (when additive
                          (swap! (:state context) assoc :selected-bones
                                 ((if (contains? previous id) disj conj) previous id)))))
                    (swap! (:state context) assoc :bone-selection-box
                           {:start [x y] :current [x y] :original (if additive (:selected-bones state #{}) #{})}))))
              (when-not (or create delete)
                (swap! (:state context) assoc :drag {:document doc :start [x y] :world (world context x y)
                                                    :last-point [x y] :brush brush :axis axis
                                                    :create-id (when create-bone (unique-id (:bones doc) "bone"))
                                                    :keep-parent (.isShiftDown ^MouseEvent event)
                                                    :pose-origin (when (= "pose" (:tool state))
                                                                   (when-let [bone (selected-bone context doc)]
                                                                     (let [[bx by] (screen context (point (get (:transforms (evaluated-pose context doc)) (:id bone)) [0.0 0.0]))]
                                                                       (< (Math/hypot (- x bx) (- y by)) 12.0))))
                                                    :pan pan :pan-origin [(:pan-x state) (:pan-y state)]
                                                    :vertices (:vertices @(:state context))
                                                    :vertex vertex :mesh-mode (:mesh-mode state) :bone (selected-bone context doc) :tool (:tool state)}))))
          (.consume ^MouseEvent event))))
    (.setOnMouseDragged canvas
      (ui/event-handler event
        (when-let [{:keys [document start pan pan-origin vertex vertices bone tool brush last-point] :as drag} (:drag @(:state context))]
          (let [x (.getX ^MouseEvent event) y (.getY ^MouseEvent event)
                target (world context x y) state @(:state context)]
            (swap! (:state context) assoc :pointer [x y] :snap-rotation (.isShiftDown ^MouseEvent event))
            (cond
              pan (swap! (:state context) assoc
                         :auto-fit false :pan-x (+ (first pan-origin) (- x (first start)))
                         :pan-y (+ (second pan-origin) (- y (second start))))
              (:bone-selection-box state)
              (let [{[sx sy] :start original :original} (:bone-selection-box state)
                    transforms (:transforms (evaluated-pose context document))
                    hits (into original (keep (fn [bone]
                                               (let [[px py] (screen context (point (get transforms (:id bone)) [0.0 0.0]))]
                                                 (when (and (<= (min sx x) px (max sx x)) (<= (min sy y) py (max sy y))) (:id bone))))) (:bones document))]
                (swap! (:state context) assoc :selected-bones hits :selected-kind :bone :selected (first (sort hits))
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
                                  (:create-id drag)
                                  (create-bone-drag context document bone (:world drag) target (:create-id drag))
                                  (and bone (= "pose" tool))
                                  (if-let [solved (drag-pose-chain context document bone target)]
                                    solved
                                    (if (:pose-origin drag)
                                    (drag-bone context document bone "move" (:world drag) target)
                                    (let [transforms (:transforms (evaluated-pose context document))
                                          origin (point (get transforms (:id bone)) [0.0 0.0])
                                          angle (Math/toDegrees (Math/atan2 (- (second target) (second origin)) (- (first target) (first origin))))]
                                      (change-displayed-value (assoc context :state (atom (assoc state :coordinate-space "world"))) document bone "rotation" angle))))
                                  (and (#{"mesh" "path"} tool) (or (= "path" tool) (not= "create" (:mesh-mode drag))) (some? vertex) (contains? vertices vertex))
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
                                  (drag-bone context document bone tool (:world drag) target))]
                    (when changed (swap! (:state context) assoc :draft (compensate-edit context document changed)))))))
            (swap! (:state context) assoc-in [:drag :last-point] [x y])
            (.consume ^MouseEvent event)))))
    (.setOnMouseReleased canvas
      (ui/event-handler event
        (let [state @(:state context) drag (:drag state)]
          (when-let [id (:create-id drag)]
            (attempt! context #(commit! context (or (:draft state)
                                                    (create-bone-drag context (:document drag) (:bone drag) (:world drag) (:world drag) id))))
            (set-selection! context :bone (if (:keep-parent drag) (:id (:bone drag)) id)))
          (when (and (= "mesh" (:tool drag)) (= "create" (:mesh-mode drag)) (some? (:vertex drag)))
            (when-let [target (nearest-vertex context (document context) (.getX ^MouseEvent event) (.getY ^MouseEvent event))]
              (when (not= target (:vertex drag))
                (attempt! context #(edit-mesh! context (fn [attachment _] (mesh/connect-vertices attachment [(:vertex drag) target])))))))
          (when-not (:create-id drag)
            (when-let [draft (:draft state)] (attempt! context #(commit-pose! context draft)))))
        (swap! (:state context) dissoc :drag :selection-box :bone-selection-box)
        (when (and (= 2 (.getClickCount ^MouseEvent event)) (not (#{"mesh" "weights" "path"} (:tool @(:state context)))))
          (set-selection! context nil nil))
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
              mesh-tool (#{"mesh" "weights" "path"} (:tool state))
              code (.getCode ^javafx.scene.input.KeyEvent event)]
          (cond
            (.isDigitKey code)
            (let [digit (.getName code)]
              (if (.isShortcutDown ^javafx.scene.input.KeyEvent event)
                (swap! (:state context) assoc-in [:selection-groups digit] (select-keys state [:selected-kind :selected :selected-bones]))
                (when-let [saved (get-in state [:selection-groups digit])]
                  (swap! (:state context) merge saved))))
            (#{KeyCode/PAGE_UP KeyCode/PAGE_DOWN} code)
            (let [history (:selection-history state [])
                  index (max 0 (min (dec (count history)) (+ (:selection-index state (dec (count history))) (if (= KeyCode/PAGE_UP code) -1 1))))]
              (when (pos? (count history))
                (swap! (:state context) #(merge % (nth history index) {:selection-index index :selection-replay true}))))
            (and (not mesh-tool) (= KeyCode/A code) (.isShortcutDown ^javafx.scene.input.KeyEvent event))
            (swap! (:state context) assoc :selected-bones (into #{} (map :id) (:bones (document context))))
            (and mesh-tool (= KeyCode/A code) (.isShortcutDown ^javafx.scene.input.KeyEvent event))
            (swap! (:state context) assoc :vertices (set (range (quot (count (get-in (selected-slot context (document context)) [:attachment :vertices])) 2))))
            (= KeyCode/ESCAPE code) (do (swap! (:state context) dissoc :draft :drag :selection-box :bone-selection-box) (set-selection! context nil nil))
            (= KeyCode/SPACE code) (do (set-selection! context nil nil)
                                     (swap! (:state context) assoc :vertices #{}))
            (and (= KeyCode/L code) (.isShortcutDown ^javafx.scene.input.KeyEvent event) (.isShiftDown ^javafx.scene.input.KeyEvent event))
            (key-properties! context (mapv :property (timeline-tracks context (document context))))
            (and (= KeyCode/K code) (not (.isShortcutDown ^javafx.scene.input.KeyEvent event))) (key-edited! context)
            (and (not (.isShortcutDown ^javafx.scene.input.KeyEvent event))
                 (contains? {KeyCode/C "rotate" KeyCode/V "move" KeyCode/X "scale" KeyCode/Z "shear" KeyCode/G "weights" KeyCode/B "pose" KeyCode/N "create"} code))
            (select-tool! context (get {KeyCode/C "rotate" KeyCode/V "move" KeyCode/X "scale" KeyCode/Z "shear" KeyCode/G "weights" KeyCode/B "pose" KeyCode/N "create"} code))
            (#{KeyCode/DELETE KeyCode/BACK_SPACE} code) (cond (= "mesh" (:tool state)) (delete-vertices! context)
                                                                       (#{"weights" "path"} (:tool state)) nil
                                                                       :else (delete-selection! context))
            :else nil))))))

(defn- timeline-events! [context]
  (let [^Canvas canvas (:timeline context)]
    (.setFocusTraversable canvas true)
    (.setOnMousePressed canvas
      (ui/event-handler event
        (.requestFocus canvas)
        (let [x (.getX ^MouseEvent event) y (.getY ^MouseEvent event)
              pan (= MouseButton/MIDDLE (.getButton ^MouseEvent event))
              additive (or (.isShortcutDown ^MouseEvent event) (.isShiftDown ^MouseEvent event))
              doc (g/node-value (:resource-node context) :document)
              track (get (timeline-tracks context doc) (long (Math/floor (/ (- y 25.0) 23.0))))
              key (when track (coll/first-where #(< (Math/abs (- x (timeline-x context (:time %)))) 7.0) (:keys track)))
              ref (when key (key-ref track key))
              selection (set (selected-key-refs context doc))
              box (and (not pan) (nil? key) (> y 25.0))
              time (timeline-time context x)]
          (when (#{MouseButton/PRIMARY MouseButton/MIDDLE} (.getButton ^MouseEvent event))
            (swap! (:state context) assoc :timeline-drag {:x x :pan pan :box box :origin (:timeline-pan @(:state context)) :track track :key key})
            (when-not pan
              (swap! (:state context) assoc :playing false :mode "animate" :time (if key (:time key) time)
                     :selected-key (when key [(:property track) (:time key)])
                     :selected-keys (cond (and ref additive) ((if (contains? selection ref) disj conj) selection ref)
                                          (contains? selection ref) selection
                                          ref #{ref}
                                          additive selection
                                          :else #{}))
              (when box
                (swap! (:state context) assoc :key-selection-box {:start [x y] :current [x y] :original (if additive selection #{})})))))
        (.consume ^MouseEvent event)))
    (.setOnMouseDragged canvas
      (ui/event-handler event
        (when-let [{:keys [x pan box origin]} (:timeline-drag @(:state context))]
          (cond
            pan (swap! (:state context) assoc :timeline-pan (min 0.0 (+ origin (- (.getX ^MouseEvent event) x))))
            box (let [[x0 y0] (:start (:key-selection-box @(:state context)))
                      x1 (.getX ^MouseEvent event) y1 (.getY ^MouseEvent event)
                      refs (into #{} (mapcat (fn [[index track]]
                                              (let [y (+ 36.0 (* 23.0 index))]
                                                (into [] (comp (filter #(and (<= (min y0 y1) y (max y0 y1))
                                                                            (<= (min x0 x1) (timeline-x context (:time %)) (max x0 x1))))
                                                               (map #(key-ref track %))) (:keys track)))))
                                 (map-indexed vector (timeline-tracks context (document context))))]
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
        (cond
          (.isControlDown ^ScrollEvent event)
          (do
            (swap! (:state context) update :timeline-zoom #(max 0.1 (min 20.0 (* % (Math/pow 1.002 (.getDeltaY ^ScrollEvent event))))))
            (.consume ^ScrollEvent event))

          (or (.isShiftDown ^ScrollEvent event)
              (> (Math/abs (.getDeltaX ^ScrollEvent event)) (Math/abs (.getDeltaY ^ScrollEvent event))))
          (do
            (swap! (:state context) update :timeline-pan #(min 0.0 (+ % (.getDeltaX ^ScrollEvent event)
                                                                      (if (.isShiftDown ^ScrollEvent event) (.getDeltaY ^ScrollEvent event) 0.0))))
            (.consume ^ScrollEvent event)))))))

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

(defn- finish-import! [context before result]
  (when-not (= before (document context))
    (throw (ex-info "The document changed during import. Retry after saving your edits." {})))
  (commit! context (ensure-animation (:document result)))
  (workspace/resource-sync! (:workspace context))
  (reset! (:images context) {})
  (swap! (:state context) assoc :fitted false :auto-fit true :playing false :time 0.0
         :selected-kind :bone :selected (:id (first (:bones (:document result))))
         :animation (:id (first (:animations (:document result))))
         :selected-key nil :selected-keys #{} :vertices #{} :vertex 0 :edge-start nil :inspector-token nil
         :locked-bones #{} :weight-bone nil :bind-bones nil :physics-scope "constraint")
  (message! context (str (when (get-in result [:document :native-project]) (str (tr context "native-notice") " · "))
                        (count (:warnings result)) " · " (tr context "warnings"))))

(defn- import-file! [context ^File file]
  (let [before (document context)]
    (if-not (string/ends-with? (string/lower-case (.getName file)) ".spine")
      (background-io! context #(animation-io/read-spine-file! file (:project-root context))
                      #(finish-import! context before %))
      (background-io! context #(animation-io/read-independent-project! file)
                      (fn [project]
                        (let [skeletons (:skeletons project)
                              names (mapv :name skeletons)
                              selected (if (= 1 (count names)) (first names)
                                           (first (dialogs/make-select-list-dialog names (:localization context)
                                                                                  {:title (tr context "select-skeleton")})))]
                          (when selected
                            (background-io! context #(animation-io/read-independent-skeleton! file (:project-root context) project selected)
                                            #(finish-import! context before %)))))))))

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
      (background-io! context #(animation-io/write-independent-project! doc (:project-root context) file)
                      (fn [output]
                        (workspace/resource-sync! (:workspace context))
                        (message! context (str (tr context "exported") " " output " · " (tr context "native-notice"))))))))

(defn- set-key! [context]
  (let [doc (ensure-animation (document context)) bone (selected-bone context doc) state @(:state context)
        slot (selected-slot context doc)
        sampled (entity (if (= "animate" (:mode @(:state context)))
                           (data/sample-pose doc (:id (animation context doc)) (:time @(:state context)))
                           (:bones doc)) (:id bone))]
    (when bone
      (change! (assoc context :preserve-pending true)
               (fn [base]
                 (let [preview doc
                       doc (ensure-animation base)]
                 (cond
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
                   (and (#{"mesh" "path"} (:tool state)) (#{"mesh" "path"} (get-in slot [:attachment :type])))
                   (do
                     (when-not (editable-preview-attachment context doc)
                       (throw (ex-info (tr context "preview-attachment-readonly") {})))
                     (data/set-deform-keyframe doc (:id (animation context doc)) (:id slot) (:time state)
                                            (or (when (= "animate" (:mode state))
                                                  (get (:deforms (sampled-slots context preview)) (:id slot)))
                                                (vec (repeat (data/deform-size (:attachment slot)) 0.0)))
                                            (interpolation-at (:keys (coll/first-where #(= (:id slot) (:slot %)) (:deforms (animation context doc)))) (:time state) (:interpolation state))))
                   :else (change-bone-value (assoc context :state (atom (assoc state :mode "animate"))) doc (:id bone)
                                      (:property state) (get sampled (keyword (:property state)) 0.0)))))))))

(defn- key-properties! [context properties]
  (let [property (:property @(:state context))
        context (assoc context :operation-sequence (gensym "skeletal-keys"))]
    (try
      (doseq [property properties]
        (swap! (:state context) assoc :property property)
        (set-key! context))
      (finally (swap! (:state context) assoc :property property)))))

(defn- delete-single-key! [context]
  (let [doc (document context) bone (selected-bone context doc) state @(:state context)
        slot (selected-slot context doc)
        [property time] (or (:selected-key state) [(:property state) (:time state)])]
    (when bone
      (change! context
               #(case (:selected-kind state)
                  :physics (data/remove-physics-keyframe % (:id (animation context doc))
                                                        (if (= "global" (:physics-scope state)) "" (:selected state)) property time)
                  :path (data/remove-path-keyframe % (:id (animation context doc)) (:selected state) property time)
                  :transform (data/remove-transform-keyframe % (:id (animation context doc)) (:selected state) property time)
                  (case property
                    "deform" (data/remove-deform-keyframe % (:id (animation context doc)) (:id slot) time)
                    ("IK mix" "IK softness") (data/remove-ik-keyframe % (:id (animation context doc)) (:selected state) time)
                    (data/remove-keyframe % (:id (animation context doc)) (:id bone) property time)))))))

(defn- delete-key! [context]
  (if (pos? (count (selected-key-refs context (document context))))
    (batch-keys! context timeline/delete-keys nil)
    (delete-single-key! context)))

(defn- set-curve! [context interpolation]
  (swap! (:state context) assoc :interpolation (if (= "bezier" interpolation) "linear" interpolation))
  (let [doc (document context)
        [property time] (:selected-key @(:state context))
        track (coll/first-where #(= property (:property %)) (timeline-tracks context doc))
        key (coll/first-where #(= time (:time %)) (:keys track))
        next-key (coll/first-where #(> (:time %) (double (or time 0.0))) (:keys track))]
    (when (and key (not= "reset" property))
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
                     "deform" (data/set-deform-keyframe d (:id (animation context d)) (:slot track) time (:vertices key) curve)
                     (data/set-keyframe d (:id (animation context d)) (:bone track) property time (:value key) curve)))))))))

(defn- curve-state [context]
  (let [doc (g/node-value (:resource-node context) :document)
        state @(:state context)
        [property time] (or (:selected-key state) [(:property state) nil])
        track (coll/first-where #(= property (:property %)) (timeline-tracks context doc))
        keys (case property ("deform" "reset") [] "IK mix" (mapv #(assoc % :value (:mix %)) (:keys track)) (vec (:keys track)))]
    {:channels (into [] (keep (fn [track]
                               (when (and (not (#{"deform" "reset"} (:property track)))
                                          (pos? (count (:keys track))))
                                 {:property (:property track)
                                  :keys (if (= "IK mix" (:property track)) (mapv #(assoc % :value (:mix %)) (:keys track)) (:keys track))})))
                     (timeline-tracks context doc))
     :keys keys :duration (or (:duration (animation context doc)) 1.0)
     :time (:time state) :selected-time (or time (:time (first keys)))}))

(defn- commit-curve! [context key curve]
  (change! context
           (fn [doc]
             (let [state @(:state context)
                   [property _] (if-let [track (:track key)] [track nil] (or (:selected-key state) [(:property state) nil]))
                   track (coll/first-where #(= property (:property %)) (timeline-tracks context doc))
                   id (:id (animation context doc))]
               (cond
                 (:physics track) (data/set-physics-keyframe doc id (:physics track) property (:time key) (:value key) (if (vector? curve) {:type "bezier" :curve curve} curve))
                 (:path track) (data/set-path-keyframe doc id (:path track) property (:time key) (:value key) (if (vector? curve) {:type "bezier" :curve curve} curve))
                 (:transform track) (data/set-transform-keyframe doc id (:transform track) property (:time key) (:value key) (if (vector? curve) {:type "bezier" :curve curve} curve))
                 (= "IK softness" property)
                 (data/set-ik-softness-keyframe doc id (:ik track) (:time key) (:value key) (if (vector? curve) {:type "bezier" :curve curve} curve))
                 (= "IK mix" property)
                 (data/set-ik-keyframe doc id (:ik track) (:time key) (:mix key) (:bend-positive key) (if (vector? curve) {:type "bezier" :curve curve} curve))
                 :else (data/set-keyframe doc id (:bone track) property (:time key) (:value key) (if (vector? curve) {:type "bezier" :curve curve} curve)))))))

(defn- add-physics! [context]
  (change! context
           (fn [doc]
             (let [bone (selected-bone context doc)
                   id (unique-id (:physics doc) "physics")]
               (when-not bone (throw (ex-info (tr context "select-bone-first") {})))
               (set-selection! context :physics id)
               (data/add-physics doc {:id id :bone (:id bone) :x 1.0 :y 1.0 :rotate 1.0})))))

(defn- add-ik! [context]
  (change! context
           (fn [doc]
             (let [bone (or (selected-bone context doc) (last (:bones doc)))
                   parent (entity (:bones doc) (:parent bone))
                   _ (when-not parent (throw (ex-info (tr context "ik-root") {})))
                   chain (if (and parent (:parent parent) (pos? (double (:length parent)))) [(:id parent) (:id bone)] [(:id bone)])
                   transforms (data/world-transforms (:bones doc))
                   [x y] (point (get transforms (:id bone)) [(:length bone) 0.0])
                   target (unique-id (:bones doc) "target")
                   id (unique-id (:ik doc) "ik")
                   order (inc (reduce max -1 (into [] (map #(get % :order 0)) (into (into (into (:ik doc) (:transform doc)) (:path doc)) (:physics doc)))))]
               (set-selection! context :ik id)
               (-> (data/add-bone doc {:id target :parent nil :x (+ x 30.0) :y (+ y 30.0) :length 20.0})
                   (data/add-ik {:id id :bones chain :target target :order order :mix 1.0 :bend-positive true}))))))

(defn- add-transform! [context]
  (change! context
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
  (change! context
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

(defn- refresh! [context elapsed]
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
        inspector-token [doc preview-attachment (:selected-kind state) (:selected state) (:vertex state) (:vertices state) (:weight-bone state) (:mesh-mode state) (:brush-mode state) (:mode state) (:animation state) (when-not (:playing state) (:time state))]]
    (when (and doc-changed (> (:time state) duration))
      (swap! (:state context) assoc :time duration))
    (when (and (:playing state) (:last-clock state))
      (let [time (+ (:time state) (* (- elapsed (:last-clock state)) (:speed state)))]
        (swap! (:state context) assoc :time (if (:loop state) (mod time (max 0.001 duration)) (min duration time))
               :playing (or (:loop state) (< time duration)))))
    (swap! (:state context) assoc :last-clock elapsed)
    (when (and (or doc-changed animation-changed) (nil? (:drag state)))
      (refresh-tree! context doc)
      (let [^ComboBox combo (:animation-choice context) ids (mapv :id (:animations doc))]
        (swap! (:state context) assoc :refreshing true)
        (.setAll (.getItems combo) ^java.util.Collection ids)
        (.setValue combo (:id animation))
        (.setAll (.getItems ^ComboBox (:skin-choice context)) ^java.util.Collection (data/skin-names doc))
        (.setValue ^ComboBox (:skin-choice context) (get doc :active-skin "default"))
        (refresh-number-field! (:duration-field context) duration)
        (swap! (:state context) assoc :refreshing false :animation (:id animation))))
    (when (and (or doc-changed animation-changed) (nil? (:drag state)))
      (refresh-number-field! (:duration-field context) duration)
      (.setText ^TextField (:clip-name-field context) (or (:id animation) "")))
    (when (and (not= inspector-token (:inspector-token state)) (nil? (:drag state)))
      (.setAll (.getChildren ^VBox (:inspector context)) ^java.util.Collection (entity-inspector context doc))
      (swap! (:state context) assoc :inspector-token inspector-token))
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
               :timeline-zoom (max 0.1 (min 20.0 (/ (- (.getWidth pane) 130.0) (* 120.0 (max 0.001 duration)))))))
      (.setMinHeight pane (+ 28.0 (* 23.0 (count (timeline-tracks context doc)))))
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
            sampled (displayed-bone context doc bone)]
        (doseq [[property ^TextField field] (:palette-fields context)]
          (.setDisable field (nil? bone))
          (when-not (= field (.getFocusOwner (.getScene field)))
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
      (let [focus (.getFocusOwner (.getScene ^Node (:root context)))]
        (swap! (:state context) assoc :editing-local-scale
               (contains? (set (mapv (:palette-fields context) ["scale-x" "scale-y"])) focus))))
    (doseq [^ToggleButton control (:space-buttons context)]
      (.setSelected control (= (.getId control) (str "skeletal-space-" (:coordinate-space @(:state context)))))
      (.setStyle control (if (and (= "skeletal-space-local" (.getId control)) (:editing-local-scale @(:state context))
                                  (= "world" (:coordinate-space @(:state context)))) "-fx-text-fill: #eda151;" "")))
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
          properties (mapv :property tracks)
          property (or (first (:selected-key state)) (when (coll/any? #{(:property state)} properties) (:property state)) (first properties))
          track (coll/first-where #(= property (:property %)) tracks)
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
    (when-let [refresh-curve (:refresh-curve context)] (refresh-curve))
    (.setText ^Label (:time-label context) (format "%.3f s" (double (:time @(:state context)))))
    (.setText ^Button (:play-button context) (tr context (if (:playing @(:state context)) "pause" "play")))
    (let [state @(:state context)
          token [doc (select-keys state [:selected-kind :selected :vertex :vertices :selection-box :pointer :brush-mode :brush-radius :weight-bone :tool :coordinate-space :mode :time :zoom :pan-x :pan-y :show-bones :show-images :show-names :animation])
                 (.getWidth ^Canvas (:canvas context)) (.getHeight ^Canvas (:canvas context))]
          timeline-token [doc (select-keys state [:selected-kind :selected :animation :time :timeline-pan :timeline-zoom :selected-key :selected-keys :key-selection-box :physics-scope])
                          (.getWidth ^Canvas (:timeline context)) (.getHeight ^Canvas (:timeline context))]]
      (when (not= token (:draw-token state))
        (draw! context)
        (swap! (:state context) assoc :draw-token token))
      (when (not= timeline-token (:timeline-token state))
        (draw-timeline! context)
        (swap! (:state context) assoc :timeline-token timeline-token)))
    (swap! (:state context) assoc :last-document doc :last-animation (:id animation))))

(g/defnode SkeletalAnimationView
  (inherits view/WorkbenchView)
  (output sidebar-panes g/Any (g/constantly [])))

(defn- make-view [^AnchorPane parent resource-node {:keys [workspace localization ^Tab tab]}]
  (let [doc (g/node-value resource-node :document)
        state (atom {:selected-kind :bone :selected (:id (first (:bones doc))) :mode "setup" :tool "move" :previous-tool "rotate" :coordinate-space "parent" :auto-key true
                     :animation (:id (first (:animations doc))) :time 0.0 :fps 60.0 :speed 1.0 :loop true :playing false
                     :selected-keys #{} :key-scale 1.0 :property "rotation" :interpolation "linear" :show-bones true :vertex 0 :vertices #{} :weight 1.0
                     :mesh-mode "modify" :brush-mode "direct" :brush-radius 40.0 :brush-strength 0.25 :locked-bones #{}
                     :zoom 1.0 :pan-x 300.0 :pan-y 300.0 :timeline-zoom 1.0 :timeline-pan 0.0})
        root (doto (identify! (BorderPane.) "workspace") (.setMinSize 0.0 0.0))
        background (doto (Canvas. 600.0 400.0) (.setMouseTransparent true))
        renderer (render/make-renderer)
        canvas (identify! (Canvas. 600.0 400.0) "canvas")
        viewport (identify! (StackPane.) "viewport")
        tree (identify! (TreeView.) "tree")
        inspector (doto (identify! (VBox. 6.0) "inspector") (.setPadding (Insets. 8.0)))
        status (identify! (Label.) "status")
        timeline (identify! (Canvas. 600.0 143.0) "timeline")
        timeline-pane (StackPane. (into-array Node [timeline]))
        time-label (identify! (Label. "0.000 s") "time")
        context-base {:resource-node resource-node :workspace workspace :localization localization :state state
                      :project-root (workspace/project-directory workspace) :root root :canvas canvas :background background :renderer renderer :viewport viewport
                      :tree tree :inspector inspector :status status :timeline timeline :timeline-pane timeline-pane
                      :time-label time-label :images (atom {}) :pose-cache (atom nil)}
        context-ref (atom context-base)
        context #(deref context-ref)
        play-button (button context-base "play" "play"
                            #(do (change! (context) ensure-animation)
                                 (swap! state (fn [s] (assoc s :playing (not (:playing s)) :mode "animate" :last-clock nil)))))
        mode-choice (choice "mode" ["setup" "animate"] "setup"
                            #(when-not (:refreshing @state)
                               (when (= "animate" %) (change! (context) ensure-animation))
                               (swap! state assoc :mode % :playing false :inspector-token nil)))
        tool-choice (choice "tool" ["move" "rotate" "scale" "shear" "mesh" "weights" "path" "pose" "create"] "move"
                            #(when-not (:refreshing @state) (select-tool! (context) %)))
        animation-choice (choice "animation" (mapv :id (:animations doc)) (:animation @state)
                                 #(when-not (:refreshing @state) (swap! state assoc :animation % :time 0.0 :playing false :selected-key nil :selected-keys #{})))
        skin-choice (choice "skin" (data/skin-names doc) (get doc :active-skin "default")
                            #(when (and % (not (:refreshing @state)))
                               (swap! state assoc :playing false :selected-key nil :selected-keys #{} :vertices #{} :vertex 0 :edge-start nil)
                               (change! (context) (fn [d] (data/select-skin d %)))))
        duration-field (number-field "duration" (or (:duration (first (:animations doc))) 1.0)
                                     #(when-not (:refreshing @state)
                                        (change! (context) (fn [d] (data/update-animation d (:animation @state) {:duration %})))))
        property-choice (choice "property" transform-properties "rotation"
                                #(when-not (:refreshing @state) (swap! state assoc :property % :selected-key nil)))
        interpolation-choice (choice "interpolation" ["linear" "stepped" "bezier"] "linear"
                                     #(when-not (:refreshing @state) (set-curve! (context) %)))
        import-path (identify! (TextField.) "import-path")
        export-path (identify! (TextField. "skeleton_export") "export-path")
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
                      (button context-base "save-spine" "save-spine" #(save-native-project! (context) (.getText export-path)))
                      (button context-base "export" "export"
                              #(when-let [result (attempt! (context) (fn [] (animation-io/export-project! (g/node-value resource-node :document) (:project-root (context)) (.getText export-path))))]
                                 (workspace/resource-sync! workspace)
                                 (message! (context) (str (tr (context) "exported") " " (:spinescene result)))) )])
        hierarchy-tools (row [(button context-base "add-bone" "add-bone"
                                      #(change! (context)
                                                (fn [d] (let [id (unique-id (:bones d) "bone") bone (selected-bone (context) d)]
                                                          (set-selection! (context) :bone id)
                                                          (data/add-bone d {:id id :parent (:id bone) :x (double (or (:length bone) 0.0)) :length 80.0})))))
                              (button context-base "attach-image" "attach-image" #(attach-image! (context)))
                              (button context-base "delete" "delete" #(delete-selection! (context)))])
        skin-tools (row [(label (tr context-base "skin")) skin-choice
                         (button context-base "copy-skin" "copy-skin"
                                 #(change! (context)
                                           (fn [d]
                                             (let [id (unique-id (mapv (fn [name] {:id name}) (data/skin-names d)) "skin")]
                                               (swap! state assoc :playing false :selected-key nil :selected-keys #{} :vertices #{} :vertex 0 :edge-start nil)
                                               (data/duplicate-skin d id)))))])
        constraint-tools (row [(button context-base "add-ik" "add-ik" #(add-ik! (context)))
                            (button context-base "add-transform" "add-transform" #(add-transform! (context)))
                            (button context-base "add-path" "add-path" #(add-path! (context)))
                            (button context-base "add-physics" "add-physics" #(add-physics! (context)))])
        search (doto (identify! (TextField.) "tree-search") (.setPromptText (tr context-base "search-hierarchy")))
        left (column [(label (tr context-base "hierarchy")) search tree])
        right (doto (ScrollPane. inspector) (.setFitToWidth true) (.setMinWidth 180.0))
        side (doto (VBox. 2.0) (.setMinHeight 0.0))
        center (doto (SplitPane.) (.setOrientation Orientation/HORIZONTAL))
        playback (tool-strip [play-button (button context-base "rewind" "rewind" #(swap! state assoc :time 0.0 :playing false))
                       animation-choice
                       (button context-base "add-animation" "add-animation"
                               #(change! (context) (fn [d] (let [id (unique-id (:animations d) "animation")]
                                                           (swap! state assoc :animation id :time 0.0)
                                                           (data/add-animation d {:id id :duration 1.0 :tracks []})))))
                       (label (tr context-base "duration")) duration-field time-label
                       (button context-base "key" "key" #(set-key! (context)))
                       (button context-base "delete-key" "delete-key" #(delete-key! (context)))
                       property-choice interpolation-choice])
        clip-name-field (text-field "clip-name" (:animation @state)
                                    #(attempt! (context)
                                               (fn [] (change! (context) (fn [doc] (data/update-animation doc (:animation @state) {:id %})))
                                                 (swap! state assoc :animation %))))
        clip-tools (tool-strip [(label (tr context-base "clip-name")) clip-name-field
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
        curve (curve-view/make-multichannel-view #(curve-state (context)) #(commit-curve! (context) %1 %2))
        timeline-scroll (doto (ScrollPane. timeline-pane)
                          (.setFocusTraversable false)
                          (.setFitToWidth true) (.setFitToHeight true)
                          (.setHbarPolicy ScrollPane$ScrollBarPolicy/NEVER))
        timeline-tabs (doto (identify! (TabPane. (into-array Tab [(Tab. (tr context-base "timeline") timeline-scroll)
                                                               (Tab. (tr context-base "curves") (:node curve))])) "timeline-tabs")
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
        file-menu (doto (identify! (MenuButton. (tr context-base "project-menu")) "project-menu")
                    (.setFocusTraversable false))
        hierarchy-menu (doto (identify! (MenuButton. (tr context-base "new-menu")) "new-menu")
                         (.setFocusTraversable false))
        clip-menu (doto (identify! (MenuButton. (tr context-base "animation-settings")) "animation-settings")
                   (.setFocusTraversable false))
        details (doto (identify! (ToggleButton. (str "▸ " (tr context-base "properties"))) "details")
                  (.setMaxWidth Double/MAX_VALUE)
                  (.setAlignment Pos/CENTER_LEFT)
                  (.setFocusTraversable false)
                  (.setOnAction (ui/event-handler event
                                  (let [control ^ToggleButton (.getSource event)]
                                    (.setText control (str (if (.isSelected control) "▾ " "▸ ") (tr context-base "properties")))))))
        details-pane (doto (column [details right]) (.setSpacing 0.0))
        tools (mapv (fn [[id glyph]]
                      (doto (identify! (ToggleButton. (str glyph " " (tr context-base (str "tool-" id)))) (str "tool-" id))
                        (.setMaxWidth Double/MAX_VALUE)
                        (.setFocusTraversable false)
                        (.setOnAction (ui/event-handler _ (select-tool! (context) id)))))
                    [["rotate" "↻"] ["move" "✥"] ["scale" "⤢"] ["shear" "⌁"] ["mesh" "◇"] ["weights" "◉"] ["path" "∿"] ["pose" "✥"] ["create" "＋"]])
        palette-fields (into {} (map (fn [property]
                                       [property (doto (number-field (str "palette-" property) 0.0
                                                          (fn [value]
                                                            (pose-change! (context)
                                                                     (fn [doc]
                                                                       (if-let [bone (selected-bone (context) doc)]
                                                                         (change-displayed-value (context) doc bone property value)
                                                                         doc)))))
                                                   (.setPrefWidth 54.0) (.setMinWidth 35.0) (.setMaxWidth 62.0))]))
                             ["rotation" "x" "y" "scale-x" "scale-y" "shear-x" "shear-y"])
        group-properties [["rotation"] ["x" "y"] ["scale-x" "scale-y"] ["shear-x" "shear-y"]]
        group-keys (mapv (fn [id properties]
                          (doto (identify! (Button. "◆") (str "key-" id))
                            (.setFocusTraversable false)
                            (.setTooltip (Tooltip. (str (tr context-base "key") " · " (coll/join-to-string ", " properties))))
                            (.setOnAction (ui/event-handler _ (key-properties! (context) properties)))))
                        ["rotation" "translation" "scale" "shear"] group-properties)
        transform-tools (doto (column (mapv (fn [control properties key-button]
                                             (row (conj (into [control] (map palette-fields) properties) key-button)))
                                           (subvec tools 0 4)
                                           group-properties group-keys))
                          (.setSpacing 2.0))
        attachment-tools (doto (column (subvec tools 4 7)) (.setSpacing 2.0))
        space-buttons (mapv (fn [space]
                              (doto (identify! (ToggleButton. (tr context-base (str "space-" space))) (str "space-" space))
                                (.setFocusTraversable false)
                                (.setOnAction (ui/event-handler _ (swap! state assoc :coordinate-space space)))))
                            ["local" "parent" "world"])
        auto-key (doto (identify! (ToggleButton. (tr context-base "auto-key")) "auto-key")
                   (.setFocusTraversable false)
                   (.setSelected true)
                   (.setOnAction (ui/event-handler event
                                   (swap! state assoc :auto-key (.isSelected ^ToggleButton (.getSource event))))))
        compensation (mapv (fn [[id title]]
                             (doto (identify! (CheckBox. title) id)
                               (.setFocusTraversable false)
                               (.setOnAction (ui/event-handler event
                                               (swap! state assoc (keyword id) (.isSelected ^CheckBox (.getSource event)))))))
                           [["compensate-bones" "Bones"] ["compensate-images" "Images (Setup)"]])
        key-edited (button context-base "key-edited" "key-edited" #(key-edited! (context)))
        overlay (doto (identify! (row [(column (subvec tools 7)) transform-tools (column space-buttons) attachment-tools (column compensation) (column [auto-key key-edited (button context-base "key-shown" "key-shown" #(key-properties! (context) (mapv :property (timeline-tracks (context) (document (context))))))])]) "tool-palette")
                  (.setMaxSize javafx.scene.layout.Region/USE_PREF_SIZE javafx.scene.layout.Region/USE_PREF_SIZE))
        mode-overlay (doto (identify! (row [mode-choice]) "mode-overlay")
                       (.setMaxSize javafx.scene.layout.Region/USE_PREF_SIZE javafx.scene.layout.Region/USE_PREF_SIZE))
        bottom (doto (column [timeline-tabs status]) (.setSpacing 0.0))
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
    (let [step-frame (fn [offset]
                       (let [duration (:duration (animation (context) (document (context))))]
                         (swap! state assoc :playing false :mode "animate"
                                :time (max 0.0 (min duration (+ (:time @state) (/ offset (:fps @state))))))))
          step-key (fn [forward]
                     (let [time (:time @state)
                           times (into [] (comp (mapcat :keys) (map :time)
                                                (filter (if forward #(> % time) #(< % time))))
                                       (timeline-tracks (context) (document (context))))]
                       (when (pos? (count times))
                         (swap! state assoc :playing false :mode "animate" :time (reduce (if forward min max) times)))))
          transport (mapv (fn [[id glyph title action]]
                            (doto (button context-base id title action)
                              (.setText glyph)
                              (.setTooltip (Tooltip. (tr context-base title)))))
                          [["previous-key" "│◀" "previous-key" #(step-key false)]
                           ["previous-frame" "◀" "previous-frame" #(step-frame -1.0)]
                           ["next-frame" "▶│" "next-frame" #(step-frame 1.0)]
                           ["next-key" "▶▶" "next-key" #(step-key true)]])]
      (.addAll (.getItems ^ToolBar playback) 2 ^java.util.Collection transport))
    ;; Keep file and authoring commands in named popovers, not a permanent input bar.
    (let [file-controls (vec (.getItems ^ToolBar toolbar))]
      (.clear (.getItems ^ToolBar toolbar))
      (.add (.getItems file-menu) (doto (CustomMenuItem. (column (subvec file-controls 5)) false)))
      (.addAll (.getItems ^ToolBar toolbar) ^java.util.Collection
               [file-menu (label (:name doc)) hierarchy-menu clip-menu]))
    (.add (.getItems hierarchy-menu) (CustomMenuItem. (column [hierarchy-tools constraint-tools skin-tools]) false))
    (.add (.getItems clip-menu) (CustomMenuItem. clip-tools false))
    ;; Put tool controls over the bottom of the canvas, matching the reference workspace.
    (.addAll (.getChildren overlay) ^java.util.Collection
             [(column [(button context-base "fit" "fit" #(do (swap! state assoc :auto-fit true) (fit! (context))))
                       (doto (identify! (CheckBox. (tr context-base "bones")) "show-bones")
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
                 (let [fields [:selected-kind :selected :selected-bones]
                       old (select-keys before fields) selected (select-keys after fields)]
                   (when (not= old selected)
                     (if (:selection-replay after)
                       (swap! state dissoc :selection-replay)
                       (swap! state (fn [current]
                                      (let [history (:selection-history current [old])
                                            index (:selection-index current (dec (count history)))
                                            history (conj (subvec (vec history) 0 (inc index)) selected)]
                                        (assoc current :selection-history history :selection-index (dec (count history)))))))))))
    (reset! context-ref (assoc context-base :tool-buttons tools :space-buttons space-buttons :group-keys (mapv vector group-keys group-properties) :auto-key-button auto-key :key-edited-button key-edited :palette-fields palette-fields :animation-choice animation-choice :skin-choice skin-choice :duration-field duration-field :play-button play-button :mode-choice mode-choice :tool-choice tool-choice :property-choice property-choice :interpolation-choice interpolation-choice :clip-name-field clip-name-field :refresh-curve (:refresh! curve)))
    (doseq [property ["scale-x" "scale-y"]
            :let [field ^TextField (get palette-fields property)]]
      (.setOnMousePressed field
                          (ui/event-handler _
                            (when (= "world" (:coordinate-space @state))
                              (let [doc (document (context))
                                    bone (selected-bone (context) doc)
                                    sampled (entity (pose (context) doc) (:id bone))]
                                (swap! state assoc :editing-local-scale true)
                                (refresh-number-field! field (get sampled (keyword property) 1.0))))))
      (.addListener (.focusedProperty field)
                    (reify ChangeListener
                      (changed [_ _ _ focused]
                        (swap! state assoc :editing-local-scale focused)
                        (when (and focused (= "world" (:coordinate-space @state)))
                          (let [doc (document (context))
                                bone (selected-bone (context) doc)
                                sampled (entity (pose (context) doc) (:id bone))]
                            (refresh-number-field! field (get sampled (keyword property) 1.0))))))))
    (.setPrefWidth import-path 180.0) (.setPromptText import-path (tr context-base "import-path"))
    (.setPrefWidth export-path 150.0)
    (.setMinWidth skin-choice 60.0) (.setPrefWidth skin-choice 110.0)
    (HBox/setHgrow skin-choice Priority/ALWAYS)
    (.setPrefWidth left 260.0) (.setMinWidth left 180.0)
    (.setPrefWidth right 260.0)
    (.setMinWidth side 230.0) (.setPrefWidth side 360.0)
    (VBox/setVgrow tree Priority/ALWAYS)
    (.setShowRoot tree true)
    (.setCellFactory tree
      (reify javafx.util.Callback
        (call [_ _]
          (proxy [javafx.scene.control.TreeCell] []
            (updateItem [item empty?]
              (proxy-super updateItem item empty?)
              (.setText ^javafx.scene.control.TreeCell this
                        (if (boolean empty?) "" (str (case (:kind item) :root "♧ " :group "▸ " :skin "◇ " :animation "▶ " :event "◆ " :bone "◇ " :slot "▧ " :ik "IK · " :transform "↔ " :path "∿ " :physics "≈ " "") (:id item)))))))))
    (.addListener (.selectedItemProperty (.getSelectionModel tree))
                  (reify ChangeListener
                    (changed [_ _ _ item]
                      (when (and item (not (:refreshing @state)))
                        (let [{:keys [kind id]} (.getValue ^TreeItem item)]
                          (case kind
                            :animation (swap! state assoc :animation id :time 0.0 :playing false :selected-key nil :selected-keys #{})
                            :skin (change! (context) #(data/select-skin % id))
                            (:bone :slot :ik :transform :path :physics) (set-selection! (context) kind id)
                            nil))))))
    (.addAll (.getChildren viewport) ^java.util.Collection [background (:node renderer) canvas mode-overlay overlay])
    (.setManaged background false)
    (.setManaged canvas false)
    (.setManaged ^Node (:node renderer) false)
    (.setMinSize viewport 120.0 60.0)
    (.bind (.visibleProperty right) (.selectedProperty details))
    (.bind (.managedProperty right) (.selectedProperty details))
    (.setPrefHeight right 220.0)
    (.setMinHeight right 80.0)
    (.setMinHeight left 80.0)
    (.addAll (.getChildren side) ^java.util.Collection [left details-pane])
    (VBox/setVgrow left Priority/ALWAYS)
    (.addAll (.getItems center) ^java.util.Collection [viewport side])
    (.setDividerPositions center (double-array [0.70]))
    ;; The resource dock menu overlays the bottom-right corner of the editor.
    ;; Reserve only footer space so it cannot intercept the toolbar overflow.
    (.setPadding toolbar (Insets. 2.0)) (.setPadding left (Insets. 3.0)) (.setPadding bottom (Insets. 0.0 20.0 0.0 0.0))
    (.setTop root toolbar)
    (let [split (doto (SplitPane.) (.setOrientation Orientation/VERTICAL) (.setMinSize 0.0 0.0))]
      (.setMinHeight center 100.0)
      (.setMinHeight bottom 160.0)
      (.addAll (.getItems split) ^java.util.Collection [center bottom])
      (.setDividerPositions split (double-array [0.51]))
      (.setCenter root split))
    (let [timeline-content (doto (column [playback timeline-scroll]) (.setSpacing 0.0))
          curve-content (doto (column [(:node curve)]) (.setSpacing 0.0))]
      (.setContent ^Tab (.get (.getTabs timeline-tabs) 0) timeline-content)
      (.setContent ^Tab (.get (.getTabs timeline-tabs) 1) curve-content)
      (VBox/setVgrow timeline-scroll Priority/ALWAYS)
      (VBox/setVgrow (:node curve) Priority/ALWAYS)
      (.addListener (.selectedIndexProperty (.getSelectionModel timeline-tabs))
                    (reify ChangeListener
                      (changed [_ _ _ index]
                        (.remove (.getChildren timeline-content) playback)
                        (.remove (.getChildren curve-content) playback)
                        (.add (.getChildren (if (zero? (int index)) timeline-content curve-content)) 0 playback)))))
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
      (ui/on-closed! tab (fn [_] (remove-watch state ::selection-history) (remove-watch state ::cursor) (dispose) (reset! (:images (context)) {}) (.remove (.getChildren parent) root))))
    (ui/user-data! root ::context (context))
    view-node))

(defn register-view-types [workspace]
  (workspace/register-view-type workspace
    :id :skeletal-animation
    :label (localization/message "resource.view.skeletal-animation")
    :make-view-fn #'make-view))
