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
            [editor.skeletal-animation-curve-view :as curve-view]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-geometry :as geometry]
            [editor.skeletal-animation-io :as animation-io]
            [editor.skeletal-animation-render :as render]
            [editor.ui :as ui]
            [editor.view :as view]
            [editor.workspace :as workspace]
            [util.coll :as coll])
  (:import [java.io File]
           [javafx.beans.value ChangeListener]
           [javafx.collections FXCollections]
           [javafx.geometry Insets Orientation Pos]
           [javafx.scene Node]
           [javafx.scene.canvas Canvas GraphicsContext]
           [javafx.scene.control Button CheckBox ComboBox Label ScrollPane ScrollPane$ScrollBarPolicy Separator SplitPane Tab TabPane TabPane$TabClosingPolicy TextField ToolBar TreeItem TreeView]
           [javafx.scene.image Image WritableImage]
           [javafx.scene.input KeyCode MouseButton MouseEvent ScrollEvent]
           [javafx.scene.layout AnchorPane BorderPane HBox Priority StackPane VBox]
           [javafx.scene.paint Color]
           [javafx.scene.text Font]))

(def ^:private transform-properties ["x" "y" "rotation" "scale-x" "scale-y"])

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
                   (let [n (Double/parseDouble (.getText field))]
                     (if (Double/isFinite n)
                       (do (.setStyle field "")
                           (when (not= @last-text (.getText field))
                             (action n)
                             (reset! last-text (.getText field))))
                       (.setStyle field "-fx-border-color: #d85b62;")))
                   (catch NumberFormatException _
                     (.setStyle field "-fx-border-color: #d85b62;"))))]
    (ui/user-data! field ::last-number-text last-text)
    (.setPrefColumnCount field 6)
    (.setOnAction field (ui/event-handler _ (commit)))
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
  (or (:draft @(:state context)) (g/node-value (:resource-node context) :document)))

(defn- commit! [context doc]
  (when-not (= doc (g/node-value (:resource-node context) :document))
    (data/validate-document doc)
    (g/transact (g/operation-label (localization/message "skeletal.edit"))
                (g/set-property (:resource-node context) :document doc)))
  (swap! (:state context) dissoc :draft))

(defn- change! [context f]
  (attempt! context #(commit! context (f (document context)))))

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
      nil)))

(defn- selected-slot [context doc]
  (when (= :slot (:selected-kind @(:state context)))
    (entity (:slots doc) (:selected @(:state context)))))

(defn- pose [context doc]
  (let [{:keys [mode time]} @(:state context)
        bones (if (= "animate" mode)
                (data/sample-pose doc (:id (animation context doc)) time)
                (:bones doc))]
    (geometry/solve-ik bones (if (= "animate" mode) (data/sample-ik doc (:id (animation context doc)) time) (:ik doc)))))

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
      (or (nil? attachment) (not (#{"region" "mesh"} (get attachment :type "region")))) []
      (= "mesh" (:type attachment))
      (geometry/mesh-world-vertices attachment matrix transforms
                                    (when (= "animate" (:mode @(:state context)))
                                      (get (if (contains? context :sampled-deforms)
                                             (:sampled-deforms context)
                                             (data/sample-deforms doc (:id (animation context doc)) (:time @(:state context))))
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

(defn- image! [context path tint]
  (when-not (string/blank? path)
    (let [file (io/file (:project-root context) (string/replace path #"^/" ""))
          key [(.getPath file) (.lastModified file) tint]]
      (if (contains? @(:images context) key)
        (get @(:images context) key)
        (let [image (when (.isFile file) (Image. (str (.toURI file)) false))
              image (when (and image (not (.isError image))) image)
              image (if (and image (not= [1.0 1.0 1.0] tint))
                      (let [width (int (.getWidth image)) height (int (.getHeight image))
                            tinted (WritableImage. width height)
                            reader (.getPixelReader image) writer (.getPixelWriter tinted)
                            [r g b] tint]
                        (dotimes [y height]
                          (dotimes [x width]
                            (let [color (.getColor reader x y)]
                              (.setColor writer x y (Color/color (* r (.getRed color)) (* g (.getGreen color))
                                                                 (* b (.getBlue color)) (.getOpacity color))))))
                        tinted)
                      image)]
          (swap! (:images context) assoc key image)
          image)))))

(defn- draw! [context]
  (let [doc (document context)
        state @(:state context)
        context (if (= "animate" (:mode state))
                  (assoc context :sampled-deforms (data/sample-deforms doc (:id (animation context doc)) (:time state)))
                  context)
        ^Canvas canvas (:canvas context)
        ^GraphicsContext gc (.getGraphicsContext2D canvas)
        ^Canvas background (:background context)
        ^GraphicsContext grid (.getGraphicsContext2D background)
        width (.getWidth canvas) height (.getHeight canvas)
        bones (pose context doc)
        transforms (geometry/world-transforms bones)
        selected (:selected state)
        zoom (double (:zoom state))
        render-slots (into []
                           (comp
                             (filter #(and (:attachment %) (#{"region" "mesh"} (get-in % [:attachment :type] "region"))))
                             (map (fn [slot]
                                    (let [attachment (:attachment slot)]
                                      {:id (:id slot)
                                       :points (mapv #(screen context %) (partitionv 2 (attachment-points context doc slot transforms)))
                                       :uvs (vec (partitionv 2 (if (= "mesh" (:type attachment)) (:uvs attachment) [0.0 0.0 1.0 0.0 1.0 1.0 0.0 1.0])))
                                       :triangles (vec (partitionv 3 (if (= "mesh" (:type attachment)) (:triangles attachment) [0 1 2 0 2 3])))
                                       :image (image! context (:path attachment)
                                                      (mapv * (subvec (get attachment :color [1.0 1.0 1.0 1.0]) 0 3)
                                                            (subvec (get slot :color [1.0 1.0 1.0 1.0]) 0 3)))
                                       :opacity (* (double (get (:color attachment) 3 1)) (double (get (:color slot) 3 1)))}))))
                           (:slots doc))]
    (.setTransform gc 1.0 0.0 0.0 1.0 0.0 0.0)
    (.clearRect gc 0.0 0.0 width height)
    (.setFill grid (Color/web "#202124")) (.fillRect grid 0.0 0.0 width height)
    (.setStroke grid (Color/web "#2b2d30")) (.setLineWidth grid 1.0)
    (let [step (* zoom (Math/pow 10.0 (Math/ceil (/ (Math/log (/ 24.0 zoom)) (Math/log 10.0)))))]
      (doseq [x (range (mod (double (:pan-x state)) step) width step)] (.strokeLine grid x 0.0 x height))
      (doseq [y (range (mod (double (:pan-y state)) step) height step)] (.strokeLine grid 0.0 y width y)))
    (.setStroke grid (Color/web "#4b5156"))
    (.strokeLine grid (:pan-x state) 0.0 (:pan-x state) height)
    (.strokeLine grid 0.0 (:pan-y state) width (:pan-y state))
    ((:render! (:renderer context)) width height render-slots)
    (doseq [{:keys [id points triangles]} render-slots
            :when (and (= :slot (:selected-kind state)) (= selected id))]
      (when (#{"mesh" "weights"} (:tool state))
        (doseq [indices triangles]
          (let [triangle (mapv points indices)]
            (.setStroke gc (Color/web "#77a6c8"))
            (.strokePolygon gc (double-array (mapv first triangle)) (double-array (mapv second triangle)) 3))))
      (when (and (= "mesh" (get-in (selected-slot context doc) [:attachment :type]))
                 (#{"mesh" "weights"} (:tool state)))
        (doseq [[index [x y]] (map-indexed vector points)]
          (.setFill gc (if (= index (:vertex state)) Color/ORANGE (Color/web "#73cae5")))
          (.fillOval gc (- x 3.5) (- y 3.5) 7.0 7.0)))
      (when (and (pos? (count points)) (not (#{"mesh" "weights"} (:tool state))))
        (let [xs (mapv first points) ys (mapv second points)
              left (reduce min xs) top (reduce min ys)]
          (.setStroke gc (Color/web "#77a6c8"))
          (.setLineWidth gc 1.0)
          (.strokeRect gc left top (- (reduce max xs) left) (- (reduce max ys) top)))))
    (when (:show-bones state)
      (doseq [bone bones
              :let [matrix (get transforms (:id bone))
                    [x y] (screen context (point matrix [0.0 0.0]))
                    [ex ey] (screen context (point matrix [(double (:length bone)) 0.0]))
                    active (= (:id bone) (:id (selected-bone context doc)))]]
        (.setStroke gc (if active (Color/web "#72b5f4") (Color/web "#c0c7cb")))
        (.setLineWidth gc (if active 3.0 1.5))
        (.strokeLine gc x y ex ey)
        (.setFill gc (if active (Color/web "#72b5f4") (Color/web "#889398")))
        (.fillOval gc (- x 4.0) (- y 4.0) 8.0 8.0)
        (.setFont gc (Font/font 11.0))
        (.fillText gc (:id bone) (+ ex 6.0) (- ey 4.0))))
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

(defn- fit! [context]
  (let [doc (document context)
        transforms (geometry/world-transforms (pose context doc))
        points (into (mapv #(point (get transforms (:id %)) [0.0 0.0]) (:bones doc))
                     (mapcat #(partitionv 2 (attachment-points context doc % transforms))) (:slots doc))
        xs (mapv first points) ys (mapv second points)
        left (reduce min -100.0 xs) right (reduce max 100.0 xs)
        bottom (reduce min -100.0 ys) top (reduce max 100.0 ys)
        ^Canvas canvas (:canvas context)
        zoom (max 0.05 (min 8.0 (/ (- (.getWidth canvas) 80.0) (max 1.0 (- right left)))
                                 (/ (- (.getHeight canvas) 80.0) (max 1.0 (- top bottom)))))]
    (swap! (:state context) assoc :zoom zoom
           :pan-x (- (/ (.getWidth canvas) 2.0) (* zoom (/ (+ left right) 2.0)))
           :pan-y (+ (/ (.getHeight canvas) 2.0) (* zoom (/ (+ bottom top) 2.0))))))

(defn- set-selection! [context kind id]
  (swap! (:state context) assoc :selected-kind kind :selected id :vertex 0 :selected-key nil :inspector-token nil))

(defn- rename-entity! [context kind id new-id update-fn]
  (attempt! context
    #(do (commit! context (update-fn (document context) id {:id new-id}))
         (set-selection! context kind new-id)
         (when (= id (:weight-bone @(:state context)))
           (swap! (:state context) assoc :weight-bone new-id)))))

(defn- repair-selection! [context doc]
  (let [{:keys [selected-kind selected weight-bone]} @(:state context)
        entities (case selected-kind :bone (:bones doc) :slot (:slots doc) :ik (:ik doc) nil)]
    (when (and selected (nil? (entity entities selected)))
      (set-selection! context :bone (:id (first (:bones doc)))))
    (when (and weight-bone (nil? (entity (:bones doc) weight-bone)))
      (swap! (:state context) dissoc :weight-bone))))

(defn- attach-image! [context]
  (attempt! context
    (fn []
      (let [doc (document context)
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
        transforms (geometry/world-transforms (pose context doc))
        points (partitionv 2 (attachment-points context doc slot transforms))
        weights (or (:weights attachment)
                    (mapv (fn [xy] [{:bone (:bone slot) :weight 1.0 :x (first xy) :y (second xy)}])
                          (partitionv 2 (:vertices attachment))))
        current (nth weights vertex)
        old-weight (double (get (entity (mapv #(assoc % :id (:bone %)) current) bone-id) :weight 0.0))
        bind (inverse-point (get transforms bone-id) (nth points vertex))]
    (when-not bind (throw (ex-info (tr context "singular-bone") {})))
    (data/update-slot doc (:id slot)
                      {:attachment (assoc attachment :weights
                                          (assoc weights vertex (geometry/paint-influences current bone-id (- weight old-weight) bind)))})))

(defn- replace-vertex [context doc vertex target]
  (let [slot (selected-slot context doc)
        attachment (:attachment slot)
        transforms (geometry/world-transforms (pose context doc))
        weighted (coll/not-empty (:weights attachment))
        animate (= "animate" (:mode @(:state context)))
        animation-id (:id (animation context doc))
        time (:time @(:state context))
        current-deform (get (data/sample-deforms doc animation-id time) (:id slot))
        offset (if weighted (* 2 (reduce + 0 (mapv count (subvec weighted 0 vertex)))) (* 2 vertex))
        local-points (if weighted
                       (mapv #(inverse-point (get transforms (:bone %)) target) (nth weighted vertex))
                       [(inverse-point (get transforms (:bone slot)) target)])]
    (when (coll/any? nil? local-points) (throw (ex-info (tr context "singular-bone") {})))
    (if animate
      (let [count (if weighted (* 2 (reduce + 0 (mapv count weighted))) (count (:vertices attachment)))
            values (or current-deform (vec (repeat count 0.0)))
            base (if weighted (into [] (mapcat (juxt :x :y)) (nth weighted vertex))
                     (subvec (:vertices attachment) offset (+ offset 2)))
            deltas (mapv - (into [] cat local-points) base)
            values (reduce-kv #(assoc %1 (+ offset %2) %3) values deltas)
            track (coll/first-where #(= (:id slot) (:slot %)) (:deforms (animation context doc)))]
        (data/set-deform-keyframe doc animation-id (:id slot) time values
                                 (interpolation-at (:keys track) time (:interpolation @(:state context)))))
      (data/update-slot doc (:id slot)
                        {:attachment
                         (if weighted
                           (assoc attachment :weights
                                  (assoc weighted vertex (mapv (fn [influence [x y]] (assoc influence :x x :y y))
                                                               (nth weighted vertex) local-points)))
                           (update attachment :vertices #(-> % (assoc offset (ffirst local-points))
                                                               (assoc (inc offset) (second (first local-points))))))}))))

(defn- entity-inspector [context doc]
  (let [{:keys [selected-kind selected vertex]} @(:state context)
        field (fn [id title value action] (column [(label (tr context title)) (number-field id value #(attempt! context (fn [] (action %))))]))]
    (case (when selected selected-kind)
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
                        (field (str "bone-" property) property (get sampled (keyword property))
                               #(change! context (fn [d] (change-bone-value context d selected property %)))))
                      transform-properties)
                [(field "bone-length" "length" (:length bone)
                        #(change! context (fn [d] (data/update-bone d selected {:length %}))))])))
      :slot
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
                (when (and attachment (= "region" (get attachment :type "region")))
                  (into (mapv (fn [property] (field (str "attachment-" property) property (get attachment (keyword property))
                                                     #(update-attachment {(keyword property) %})))
                              ["x" "y" "rotation" "scale-x" "scale-y" "width" "height"])
                        [(button context "make-mesh" "make-mesh"
                                 #(do (update-attachment (geometry/region-to-mesh attachment 3 3))
                                      (swap! (:state context) assoc :tool "mesh")))]))
                (when mesh
                  [(label (str (tr context "vertices") ": " vertex-count))
                   (field "vertex-index" "vertex" vertex #(swap! (:state context) assoc :vertex (max 0 (min (dec vertex-count) (long %))) :inspector-token nil))
                   (label (tr context "weight-bone"))
                   (choice "weight-bone" (mapv :id (:bones doc)) (or (:weight-bone @(:state context)) (:bone slot))
                           #(swap! (:state context) assoc :weight-bone % :inspector-token nil))
                   (field "weight-value" "weight" (:weight @(:state context)) #(swap! (:state context) assoc :weight (min 1.0 (max 0.0 %))))
                   (button context "assign-weight" "assign-weight"
                           #(change! context (fn [d] (apply-weight! context d vertex (or (:weight-bone @(:state context)) (:bone slot)) (:weight @(:state context))))))
                   (label (str (tr context "weights") "\n"
                               (coll/join-to-string "\n" (mapv #(format "%s  %.3f" (:bone %) (double (:weight %)))
                                                          (get (:weights attachment) vertex)))))
                   (label (tr context "mesh-help"))]))))
      :ik
      (let [ik (entity (if (= "animate" (:mode @(:state context)))
                         (data/sample-ik doc (:id (animation context doc)) (:time @(:state context)))
                         (:ik doc)) selected)
            update-ik (fn [changes]
                        (change! context
                                 (fn [d]
                                   (if (= "animate" (:mode @(:state context)))
                                     (let [value (merge ik changes)]
                                       (data/set-ik-keyframe d (:id (animation context d)) selected
                                                             (:time @(:state context)) (:mix value) (:bend-positive value)
                                                             (interpolation-at (:keys (coll/first-where #(= selected (:ik %)) (:ik-tracks (animation context d))))
                                                                               (:time @(:state context)) (:interpolation @(:state context)))))
                                     (data/update-ik d selected changes)))))]
        [(label "IK")
         (text-field "ik-name" selected #(rename-entity! context :ik selected % data/update-ik))
         (label (tr context "target"))
         (choice "ik-target" (mapv :id (:bones doc)) (:target ik)
                 #(change! context (fn [d] (data/update-ik d selected {:target %}))))
         (field "ik-mix" "mix" (:mix ik) #(update-ik {:mix %}))
         (choice "ik-bend" ["+" "−"] (if (:bend-positive ik) "+" "−")
                 #(update-ik {:bend-positive (= "+" %)}))])
      [(label (tr context "select-help"))])))

(defn- refresh-tree! [context doc]
  (let [items (atom {})
        add-bone (fn add-bone [bone]
                   (let [item (TreeItem. {:kind :bone :id (:id bone)})]
                     (swap! items assoc [:bone (:id bone)] item)
                     (.setExpanded item true)
                     (.addAll (.getChildren item) ^java.util.Collection
                              (into (mapv add-bone (filterv #(= (:id bone) (:parent %)) (:bones doc)))
                                    (map (fn [slot]
                                           (let [child (TreeItem. {:kind :slot :id (:id slot)})]
                                             (swap! items assoc [:slot (:id slot)] child)
                                             child)))
                                    (filterv #(= (:id bone) (:bone %)) (:slots doc))))
                     item))
        root (TreeItem. {:kind :root :id "Skeleton"})
        selected [(:selected-kind @(:state context)) (:selected @(:state context))]]
    (.addAll (.getChildren root) ^java.util.Collection (mapv add-bone (filterv #(nil? (:parent %)) (:bones doc))))
    (doseq [ik (:ik doc)]
      (let [item (TreeItem. {:kind :ik :id (:id ik)})]
        (swap! items assoc [:ik (:id ik)] item)
        (.add (.getChildren root) item)))
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
                  transform-properties)
      (= "mesh" (get-in slot [:attachment :type]))
      (conj {:slot (:id slot) :property "deform"
             :keys (:keys (coll/first-where #(= (:id slot) (:slot %)) (:deforms animation)))})
      (= :ik (:selected-kind @(:state context)))
      (conj {:ik (:selected @(:state context)) :property "IK mix"
             :keys (:keys (coll/first-where #(= (:selected @(:state context)) (:ik %)) (:ik-tracks animation)))}))))

(defn- timeline-x [context time]
  (+ 110.0 (:timeline-pan @(:state context)) (* time 120.0 (:timeline-zoom @(:state context)))))

(defn- timeline-time [context x]
  (let [state @(:state context)
        duration (or (:duration (animation context (document context))) 1.0)
        value (/ (- x 110.0 (:timeline-pan state)) (* 120.0 (:timeline-zoom state)))
        fps (double (:fps state))]
    (max 0.0 (min duration (/ (Math/round (* fps value)) fps)))))

(defn- draw-timeline! [context]
  (let [^Canvas canvas (:timeline context) gc (.getGraphicsContext2D canvas)
        width (.getWidth canvas) height (.getHeight canvas)
        doc (document context) state @(:state context)
        duration (or (:duration (animation context doc)) 1.0)
        tracks (timeline-tracks context doc)]
    (.setFill gc (Color/web "#1d1f21")) (.fillRect gc 0.0 0.0 width height)
    (.setFont gc (Font/font 11.0))
    (doseq [[i track] (map-indexed vector tracks)]
      (.setFill gc (Color/web (if (even? i) "#23262a" "#282b2f")))
      (.fillRect gc 0.0 (+ 25.0 (* 23.0 i)) width 23.0)
      (.setFill gc (Color/web "#c8c8c8")) (.fillText gc (:property track) 8.0 (+ 41.0 (* 23.0 i)))
      (doseq [key (:keys track)
              :let [x (timeline-x context (:time key)) y (+ 36.0 (* 23.0 i))]]
        (.setFill gc (if (= [(:property track) (:time key)] (:selected-key state)) Color/ORANGE (Color/web "#70ade6")))
        (.fillPolygon gc (double-array [x (+ x 4.0) x (- x 4.0)]) (double-array [(- y 4.0) y (+ y 4.0) y]) 4)))
    (let [step (Math/pow 10.0 (Math/ceil (/ (Math/log (/ 0.5 (:timeline-zoom state))) (Math/log 10.0))))]
      (doseq [t (range 0.0 (+ duration step) step)
              :let [x (timeline-x context t)] :when (<= 110.0 x width)]
        (.setStroke gc (Color/web "#3b3e42")) (.strokeLine gc x 22.0 x height)
        (.setFill gc (Color/web "#b2b6ba")) (.fillText gc (format "%.2f" (double t)) (+ x 3.0) 16.0)))
    (let [x (timeline-x context (:time state))]
      (.setStroke gc (Color/web "#f0934e")) (.setLineWidth gc 1.0) (.strokeLine gc x 0.0 x height))
    (.setStroke gc (Color/web "#50545a")) (.strokeLine gc 110.0 0.0 110.0 height)))

(defn- delete-selection! [context]
  (let [{:keys [selected-kind selected]} @(:state context)]
    (change! context #(case selected-kind
                        :bone (data/remove-bone % selected)
                        :slot (data/remove-slot % selected)
                        :ik (data/remove-ik % selected)
                        %))
    (set-selection! context nil nil)))

(defn- nearest-vertex [context doc x y]
  (when-let [slot (selected-slot context doc)]
    (when (= "mesh" (get-in slot [:attachment :type]))
      (let [points (partitionv 2 (attachment-points context doc slot (geometry/world-transforms (pose context doc))))
            nearest (first (sort-by second
                                   (mapv (fn [i xy]
                                           (let [[px py] (screen context xy)]
                                             [i (Math/hypot (- x px) (- y py))]))
                                         (range (count points)) points)))]
        (when (and nearest (< (double (second nearest)) 14.0)) (first nearest))))))

(defn- drag-bone [context doc bone tool start target]
  (let [transforms (geometry/world-transforms (pose context doc))
        parent (get transforms (:parent bone) [1.0 0.0 0.0 1.0 0.0 0.0])
        p0 (inverse-point parent start)
        p1 (inverse-point parent target)
        sampled (entity (if (= "animate" (:mode @(:state context)))
                           (data/sample-pose doc (:id (animation context doc)) (:time @(:state context)))
                           (:bones doc)) (:id bone))]
    (when (and p0 p1)
      (case tool
        "rotate"
        (let [angle0 (Math/atan2 (- (second p0) (:y sampled)) (- (first p0) (:x sampled)))
              angle1 (Math/atan2 (- (second p1) (:y sampled)) (- (first p1) (:x sampled)))]
          (change-bone-value context doc (:id bone) "rotation"
                             (+ (:rotation sampled) (Math/toDegrees (Math/atan2 (Math/sin (- angle1 angle0)) (Math/cos (- angle1 angle0)))))))
        "scale"
        (let [d0 (Math/hypot (- (first p0) (:x sampled)) (- (second p0) (:y sampled)))
              d1 (Math/hypot (- (first p1) (:x sampled)) (- (second p1) (:y sampled)))
              ratio (/ d1 (max 1.0 d0))]
          (-> (change-bone-value context doc (:id bone) "scale-x" (* (:scale-x sampled) ratio))
              (#(change-bone-value context % (:id bone) "scale-y" (* (:scale-y sampled) ratio)))))
        (let [[dx dy] (mapv - p1 p0)]
          (-> (change-bone-value context doc (:id bone) "x" (+ (:x sampled) dx))
              (#(change-bone-value context % (:id bone) "y" (+ (:y sampled) dy)))))))))

(defn- canvas-events! [context]
  (let [^Canvas canvas (:canvas context)]
    (.setFocusTraversable canvas true)
    (.setOnMousePressed canvas
      (ui/event-handler event
        (when (#{MouseButton/PRIMARY MouseButton/MIDDLE} (.getButton ^MouseEvent event))
          (.requestFocus canvas)
          (when (= MouseButton/PRIMARY (.getButton ^MouseEvent event))
            (swap! (:state context) assoc :playing false))
        (let [doc (document context)
              x (.getX ^MouseEvent event) y (.getY ^MouseEvent event)
              state @(:state context)
              pan (= MouseButton/MIDDLE (.getButton ^MouseEvent event))
              vertex (when (#{"mesh" "weights"} (:tool state)) (nearest-vertex context doc x y))]
          (when (and (not pan) (nil? vertex) (= :bone (:selected-kind state)))
            (let [transforms (geometry/world-transforms (pose context doc))
                  nearest (first (sort-by second
                                         (mapv (fn [bone]
                                                 (let [[px py] (screen context (point (get transforms (:id bone)) [0.0 0.0]))]
                                                   [(:id bone) (Math/hypot (- px x) (- py y))])) (:bones doc))))]
              (when (and nearest (< (double (second nearest)) 16.0)) (set-selection! context :bone (first nearest)))))
          (when (some? vertex) (swap! (:state context) assoc :vertex vertex :inspector-token nil))
          (swap! (:state context) assoc :drag {:document doc :start [x y] :world (world context x y)
                                              :pan pan :pan-origin [(:pan-x state) (:pan-y state)]
                                              :vertex vertex :bone (selected-bone context doc) :tool (:tool state)})
          (.consume ^MouseEvent event)))))
    (.setOnMouseDragged canvas
      (ui/event-handler event
        (when-let [{:keys [document start pan pan-origin vertex bone tool] :as drag} (:drag @(:state context))]
          (let [x (.getX ^MouseEvent event)
                y (.getY ^MouseEvent event)
                target (world context x y)]
            (if pan
              (swap! (:state context) assoc
                     :auto-fit false :pan-x (+ (first pan-origin) (- x (first start)))
                     :pan-y (+ (second pan-origin) (- y (second start))))
              (attempt! context
                (fn []
                  (let [changed (cond
                                  (and (= "mesh" tool) (some? vertex))
                                  (replace-vertex context document vertex target)
                                  (and (= "weights" tool) (some? vertex))
                                  (apply-weight! context document vertex
                                                 (or (:weight-bone @(:state context)) (:id bone))
                                                 (:weight @(:state context)))
                                  (and bone (#{"move" "rotate" "scale"} tool)) (drag-bone context document bone tool (:world drag) target)
                                  :else nil)]
                    (when changed (swap! (:state context) assoc :draft changed))))))
            (.consume ^MouseEvent event)))))
    (.setOnMouseReleased canvas
      (ui/event-handler event
        (when-let [draft (:draft @(:state context))] (attempt! context #(commit! context draft)))
        (swap! (:state context) dissoc :drag)
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
        (condp = (.getCode ^javafx.scene.input.KeyEvent event)
          KeyCode/ESCAPE (swap! (:state context) dissoc :draft :drag)
          KeyCode/SPACE (swap! (:state context) #(assoc % :playing (not (:playing %)) :mode "animate" :last-clock nil))
          KeyCode/DELETE (delete-selection! context)
          nil)))))

(defn- timeline-events! [context]
  (let [^Canvas canvas (:timeline context)]
    (.setOnMousePressed canvas
      (ui/event-handler event
        (let [x (.getX ^MouseEvent event) y (.getY ^MouseEvent event)
              pan (= MouseButton/MIDDLE (.getButton ^MouseEvent event))
              doc (document context)
              track (get (timeline-tracks context doc) (long (Math/floor (/ (- y 25.0) 23.0))))
              key (when track (coll/first-where #(< (Math/abs (- x (timeline-x context (:time %)))) 7.0) (:keys track)))
              time (timeline-time context x)]
          (when (#{MouseButton/PRIMARY MouseButton/MIDDLE} (.getButton ^MouseEvent event))
            (swap! (:state context) assoc :timeline-drag {:x x :pan pan :origin (:timeline-pan @(:state context)) :track track :key key})
            (when-not pan
              (swap! (:state context) assoc :playing false :mode "animate" :time (if key (:time key) time)
                     :selected-key (when key [(:property track) (:time key)])))))))
    (.setOnMouseDragged canvas
      (ui/event-handler event
        (when-let [{:keys [x pan origin]} (:timeline-drag @(:state context))]
          (if pan
            (swap! (:state context) assoc :timeline-pan (min 0.0 (+ origin (- (.getX ^MouseEvent event) x))))
            (when (> (Math/abs (- (.getX ^MouseEvent event) x)) 3.0)
              (swap! (:state context) assoc :time (timeline-time context (.getX ^MouseEvent event)))
              (swap! (:state context) assoc-in [:timeline-drag :moved] true))))))
    (.setOnMouseReleased canvas
      (ui/event-handler _
        (when-let [{:keys [pan track key moved]} (:timeline-drag @(:state context))]
          (when (and key moved (not pan) (not= (:time key) (:time @(:state context))))
            (attempt! context
                      (fn []
                        (let [doc (document context) id (:id (animation context doc)) time (:time @(:state context))]
                          (commit! context
                                   (case (:property track)
                                     "deform" (data/move-deform-keyframe doc id (:slot track) (:time key) time)
                                     "IK mix" (data/move-ik-keyframe doc id (:ik track) (:time key) time)
                                     (data/move-keyframe doc id (:bone track) (:property track) (:time key) time)))
                          (swap! (:state context) assoc :selected-key [(:property track) time])))))
          (swap! (:state context) dissoc :timeline-drag))))
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

(defn- set-key! [context]
  (let [doc (ensure-animation (document context)) bone (selected-bone context doc) state @(:state context)
        slot (selected-slot context doc)
        sampled (entity (if (= "animate" (:mode @(:state context)))
                           (data/sample-pose doc (:id (animation context doc)) (:time @(:state context)))
                           (:bones doc)) (:id bone))]
    (when bone
      (change! context
               (fn [_]
                 (cond
                   (= :ik (:selected-kind state))
                   (let [ik (entity (if (= "animate" (:mode state))
                                      (data/sample-ik doc (:id (animation context doc)) (:time state)) (:ik doc)) (:selected state))]
                     (data/set-ik-keyframe doc (:id (animation context doc)) (:id ik) (:time state) (:mix ik) (:bend-positive ik)
                                            (interpolation-at (:keys (coll/first-where #(= (:id ik) (:ik %)) (:ik-tracks (animation context doc))))
                                                              (:time state) (:interpolation state))))
                   (and (= "mesh" (:tool state)) (= "mesh" (get-in slot [:attachment :type])))
                   (data/set-deform-keyframe doc (:id (animation context doc)) (:id slot) (:time state)
                                            (or (when (= "animate" (:mode state))
                                                  (get (data/sample-deforms doc (:id (animation context doc)) (:time state)) (:id slot)))
                                                (vec (repeat (data/deform-size (:attachment slot)) 0.0)))
                                            (interpolation-at (:keys (coll/first-where #(= (:id slot) (:slot %)) (:deforms (animation context doc)))) (:time state) (:interpolation state)))
                   :else (change-bone-value (assoc context :state (atom (assoc state :mode "animate"))) doc (:id bone)
                                      (:property state) (get sampled (keyword (:property state))))))))))

(defn- delete-key! [context]
  (let [doc (document context) bone (selected-bone context doc) state @(:state context)
        slot (selected-slot context doc)
        [property time] (or (:selected-key state) [(:property state) (:time state)])]
    (when bone
      (change! context #(case property
                          "deform" (data/remove-deform-keyframe % (:id (animation context doc)) (:id slot) time)
                          "IK mix" (data/remove-ik-keyframe % (:id (animation context doc)) (:selected state) time)
                          (data/remove-keyframe % (:id (animation context doc)) (:id bone) property time))))))

(defn- set-curve! [context interpolation]
  (swap! (:state context) assoc :interpolation (if (= "bezier" interpolation) "linear" interpolation))
  (let [doc (document context)
        [property time] (:selected-key @(:state context))
        track (coll/first-where #(= property (:property %)) (timeline-tracks context doc))
        key (coll/first-where #(= time (:time %)) (:keys track))
        next-key (coll/first-where #(> (:time %) (double (or time 0.0))) (:keys track))]
    (when key
      (change! context
               (fn [d]
                 (let [curve (if (and (= "bezier" interpolation) next-key)
                               (let [t0 (:time key) t1 (:time next-key)
                                     v0 (case property "deform" 0.0 "IK mix" (:mix key) (:value key))
                                     v1 (case property "deform" 1.0 "IK mix" (:mix next-key) (:value next-key))]
                                 {:type "bezier" :curve [(+ t0 (* (- t1 t0) 0.33)) v0
                                                        (+ t0 (* (- t1 t0) 0.67)) v1]})
                               (if (= "bezier" interpolation) "linear" interpolation))]
                   (case property
                     "IK mix" (data/set-ik-keyframe d (:id (animation context d)) (:ik track) time (:mix key) (:bend-positive key) curve)
                     "deform" (data/set-deform-keyframe d (:id (animation context d)) (:slot track) time (:vertices key) curve)
                     (data/set-keyframe d (:id (animation context d)) (:bone track) property time (:value key) curve))))))))

(defn- curve-state [context]
  (let [doc (document context)
        state @(:state context)
        [property time] (or (:selected-key state) [(:property state) nil])
        track (coll/first-where #(= property (:property %)) (timeline-tracks context doc))
        keys (case property "deform" [] "IK mix" (mapv #(assoc % :value (:mix %)) (:keys track)) (vec (:keys track)))]
    {:keys keys :duration (or (:duration (animation context doc)) 1.0)
     :time (:time state) :selected-time (or time (:time (first keys)))}))

(defn- commit-curve! [context key curve]
  (change! context
           (fn [doc]
             (let [state @(:state context)
                   [property _] (or (:selected-key state) [(:property state) nil])
                   track (coll/first-where #(= property (:property %)) (timeline-tracks context doc))
                   id (:id (animation context doc))]
               (if (= "IK mix" property)
                 (data/set-ik-keyframe doc id (:ik track) (:time key) (:mix key) (:bend-positive key) {:type "bezier" :curve curve})
                 (data/set-keyframe doc id (:bone track) property (:time key) (:value key) {:type "bezier" :curve curve}))))))

(defn- add-ik! [context]
  (change! context
           (fn [doc]
             (let [bone (or (selected-bone context doc) (last (:bones doc)))
                   parent (entity (:bones doc) (:parent bone))
                   _ (when-not parent (throw (ex-info (tr context "ik-root") {})))
                   chain (if (and parent (:parent parent) (pos? (double (:length parent)))) [(:id parent) (:id bone)] [(:id bone)])
                   transforms (geometry/world-transforms (:bones doc))
                   [x y] (point (get transforms (:id bone)) [(:length bone) 0.0])
                   target (unique-id (:bones doc) "target")
                   id (unique-id (:ik doc) "ik")]
               (set-selection! context :ik id)
               (-> (data/add-bone doc {:id target :parent nil :x (+ x 30.0) :y (+ y 30.0) :length 20.0})
                   (data/add-ik {:id id :bones chain :target target :mix 1.0 :bend-positive true}))))))

(defn- refresh! [context elapsed]
  (repair-selection! context (document context))
  (let [doc (document context) state @(:state context)
        animation (animation context doc)
        duration (double (or (:duration animation) 1.0))
        doc-changed (not= doc (:last-document state))
        animation-changed (not= (:id animation) (:last-animation state))
        inspector-token [doc (:selected-kind state) (:selected state) (:vertex state) (:weight-bone state) (:mode state) (:animation state) (when-not (:playing state) (:time state))]]
    (when (and (:playing state) (:last-clock state))
      (let [time (+ (:time state) (* (- elapsed (:last-clock state)) (:speed state)))]
        (swap! (:state context) assoc :time (if (:loop state) (mod time (max 0.001 duration)) (min duration time))
               :playing (or (:loop state) (< time duration)))))
    (swap! (:state context) assoc :last-clock elapsed)
    (when (and doc-changed (nil? (:drag state)))
      (refresh-tree! context doc)
      (let [^ComboBox combo (:animation-choice context) ids (mapv :id (:animations doc))]
        (swap! (:state context) assoc :refreshing true)
        (.setAll (.getItems combo) ^java.util.Collection ids)
        (.setValue combo (:id animation))
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
        (when (not= false (:auto-fit state)) (fit! context) (swap! (:state context) assoc :fitted true))))
    (let [^Canvas timeline (:timeline context) ^StackPane pane (:timeline-pane context)]
      (.setWidth timeline (max 1.0 (.getWidth pane)))
      (.setMinHeight pane (+ 28.0 (* 23.0 (count (timeline-tracks context doc)))))
      (.setHeight timeline (max (.getHeight pane) (.getMinHeight pane))))
    (when-not (= (:mode @(:state context)) (.getValue ^ComboBox (:mode-choice context)))
      (swap! (:state context) assoc :refreshing true)
      (.setValue ^ComboBox (:mode-choice context) (:mode @(:state context)))
      (swap! (:state context) assoc :refreshing false))
    (when-let [refresh-curve (:refresh-curve context)] (refresh-curve))
    (.setText ^Label (:time-label context) (format "%.3f s" (double (:time @(:state context)))))
    (.setText ^Button (:play-button context) (tr context (if (:playing @(:state context)) "pause" "play")))
    (let [state @(:state context)
          token [doc (select-keys state [:selected-kind :selected :vertex :weight-bone :tool :mode :time :zoom :pan-x :pan-y :show-bones :animation])
                 (.getWidth ^Canvas (:canvas context)) (.getHeight ^Canvas (:canvas context))]
          timeline-token [doc (select-keys state [:selected-kind :selected :animation :time :timeline-pan :timeline-zoom :selected-key])
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
        state (atom {:selected-kind :bone :selected (:id (first (:bones doc))) :mode "setup" :tool "move"
                     :animation (:id (first (:animations doc))) :time 0.0 :fps 60.0 :speed 1.0 :loop true :playing false
                     :property "rotation" :interpolation "linear" :show-bones true :vertex 0 :weight 1.0
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
                      :time-label time-label :images (atom {})}
        context-ref (atom context-base)
        context #(deref context-ref)
        play-button (button context-base "play" "play"
                            #(do (change! (context) ensure-animation)
                                 (swap! state (fn [s] (assoc s :playing (not (:playing s)) :mode "animate" :last-clock nil)))))
        mode-choice (choice "mode" ["setup" "animate"] "setup"
                            #(when-not (:refreshing @state)
                               (when (= "animate" %) (change! (context) ensure-animation))
                               (swap! state assoc :mode % :playing false :inspector-token nil)))
        animation-choice (choice "animation" (mapv :id (:animations doc)) (:animation @state)
                                 #(when-not (:refreshing @state) (swap! state assoc :animation % :time 0.0 :playing false)))
        duration-field (number-field "duration" (or (:duration (first (:animations doc))) 1.0)
                                     #(when-not (:refreshing @state)
                                        (change! (context) (fn [d] (data/update-animation d (:animation @state) {:duration %})))))
        import-path (identify! (TextField.) "import-path")
        export-path (identify! (TextField. "skeleton_export") "export-path")
        toolbar (tool-strip [mode-choice
                      (choice "tool" ["move" "rotate" "scale" "mesh" "weights"] "move" #(swap! state assoc :tool %))
                      (button context-base "fit" "fit" #(do (swap! state assoc :auto-fit true) (fit! (context))))
                      (doto (identify! (CheckBox. (tr context-base "bones")) "show-bones")
                        (.setSelected true)
                        (.setOnAction (ui/event-handler event (swap! state assoc :show-bones (.isSelected ^CheckBox (.getSource event))))))
                      (Separator. Orientation/VERTICAL)
                      import-path
                      (button context-base "import" "import"
                              #(when-let [file (if (string/blank? (.getText import-path))
                                                 (dialogs/make-file-dialog (tr (context) "import") [["Spine JSON" "*.json" "*.spinejson"]]
                                                                           (io/file (:project-root (context)) "skeleton.json") (.getWindow (.getScene root)))
                                                 (io/file (.getText import-path)))]
                                 (when-let [result (attempt! (context) (fn [] (animation-io/read-spine-file! file (:project-root (context)))))]
                                   (change! (context) (constantly (ensure-animation (:document result))))
                                   (message! (context) (when (pos? (count (:warnings result)))
                                                        (str (count (:warnings result)) " · " (tr (context) "warnings"))))
                                   (workspace/resource-sync! workspace)
                                   (reset! (:images (context)) {})
                                   (swap! state assoc :fitted false :playing false :time 0.0
                                          :selected-kind :bone :selected (:id (first (:bones (:document result))))
                                          :animation (:id (first (:animations (:document result))))))))
                      export-path
                      (button context-base "export" "export"
                              #(when-let [result (attempt! (context) (fn [] (animation-io/export-project! (document (context)) (:project-root (context)) (.getText export-path))))]
                                 (workspace/resource-sync! workspace)
                                 (message! (context) (str (tr (context) "exported") " " (:spinescene result)))) )])
        hierarchy-tools (row [(button context-base "add-bone" "add-bone"
                                      #(change! (context)
                                                (fn [d] (let [id (unique-id (:bones d) "bone") bone (selected-bone (context) d)]
                                                          (set-selection! (context) :bone id)
                                                          (data/add-bone d {:id id :parent (:id bone) :x (double (or (:length bone) 0.0)) :length 80.0})))))
                              (button context-base "attach-image" "attach-image" #(attach-image! (context)))
                              (button context-base "delete" "delete" #(delete-selection! (context)))])
        left (column [(label (tr context-base "hierarchy")) hierarchy-tools tree
                      (button context-base "add-ik" "add-ik" #(add-ik! (context)))])
        right (doto (ScrollPane. inspector) (.setFitToWidth true) (.setMinWidth 180.0))
        side (doto (SplitPane.) (.setOrientation Orientation/VERTICAL))
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
                       (choice "property" transform-properties "rotation" #(swap! state assoc :property %))
                       (choice "interpolation" ["linear" "stepped" "bezier"] "linear" #(set-curve! (context) %))])
        clip-name-field (text-field "clip-name" (:animation @state)
                                    #(attempt! (context)
                                               (fn [] (commit! (context) (data/update-animation (document (context)) (:animation @state) {:id %}))
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
                                 #(message! (context) (coll/join-to-string " · " (:warnings (document (context))))))])
        curve (curve-view/make-view #(curve-state (context)) #(commit-curve! (context) %1 %2))
        timeline-scroll (doto (ScrollPane. timeline-pane)
                          (.setFitToWidth true) (.setFitToHeight true)
                          (.setHbarPolicy ScrollPane$ScrollBarPolicy/NEVER))
        timeline-tabs (doto (identify! (TabPane. (into-array Tab [(Tab. (tr context-base "timeline") timeline-scroll)
                                                               (Tab. (tr context-base "curves") (:node curve))])) "timeline-tabs")
                        (.setTabClosingPolicy TabPane$TabClosingPolicy/UNAVAILABLE))
        bottom (column [playback timeline-tabs clip-tools status])
        view-node (first (g/tx-nodes-added (g/transact {:undoable false} (g/make-node SkeletalAnimationView))))]
    (reset! context-ref (assoc context-base :animation-choice animation-choice :duration-field duration-field :play-button play-button :mode-choice mode-choice :clip-name-field clip-name-field :refresh-curve (:refresh! curve)))
    (.setPrefWidth import-path 180.0) (.setPromptText import-path (tr context-base "import-path"))
    (.setPrefWidth export-path 150.0)
    (.setPrefWidth left 260.0) (.setMinWidth left 180.0)
    (.setPrefWidth right 260.0)
    (.setMinWidth side 230.0) (.setPrefWidth side 300.0) (.setMaxWidth side 480.0)
    (SplitPane/setResizableWithParent side false) (VBox/setVgrow tree Priority/ALWAYS)
    (.setShowRoot tree false)
    (.setCellFactory tree
      (reify javafx.util.Callback
        (call [_ _]
          (proxy [javafx.scene.control.TreeCell] []
            (updateItem [item empty?]
              (proxy-super updateItem item empty?)
              (.setText ^javafx.scene.control.TreeCell this
                        (if (boolean empty?) "" (str (case (:kind item) :bone "◇ " :slot "▧ " :ik "IK · " "") (:id item)))))))))
    (.addListener (.selectedItemProperty (.getSelectionModel tree))
                  (reify ChangeListener
                    (changed [_ _ _ item]
                      (when (and item (not (:refreshing @state)))
                        (let [{:keys [kind id]} (.getValue ^TreeItem item)] (set-selection! (context) kind id))))))
    (.addAll (.getChildren viewport) ^java.util.Collection [background (:node renderer) canvas])
    (.setManaged background false)
    (.setManaged canvas false)
    (.setManaged ^Node (:node renderer) false)
    (.setMinSize viewport 120.0 60.0)
    (.addAll (.getItems side) ^java.util.Collection [left right])
    (.setDividerPositions side (double-array [0.55]))
    (.addAll (.getItems center) ^java.util.Collection [viewport side])
    (.setDividerPositions center (double-array [0.76]))
    (.setPadding toolbar (Insets. 5.0)) (.setPadding left (Insets. 6.0)) (.setPadding bottom (Insets. 5.0))
    (.setTop root toolbar)
    (let [split (doto (SplitPane.) (.setOrientation Orientation/VERTICAL) (.setMinSize 0.0 0.0))]
      (.setMinHeight center 100.0)
      (.setMinHeight bottom 160.0)
      (.addAll (.getItems split) ^java.util.Collection [center bottom])
      (.setDividerPositions split (double-array [0.65]))
      (.setCenter root split))
    (VBox/setVgrow timeline-tabs Priority/ALWAYS)
    (.setMinHeight timeline-tabs 140.0)
    (.setManaged timeline false)
    (.setWrapText status true)
    (AnchorPane/setTopAnchor root 0.0) (AnchorPane/setRightAnchor root 0.0)
    (AnchorPane/setBottomAnchor root 0.0) (AnchorPane/setLeftAnchor root 0.0)
    (.add (.getChildren parent) root)
    (canvas-events! (context)) (timeline-events! (context))
    (let [dispose (ui/node-timer! root nil "skeletal-animation-editor" #(refresh! (context) %))]
      (ui/on-closed! tab (fn [_] (dispose) (reset! (:images (context)) {}) (.remove (.getChildren parent) root))))
    (ui/user-data! root ::context (context))
    view-node))

(defn register-view-types [workspace]
  (workspace/register-view-type workspace
    :id :skeletal-animation
    :label (localization/message "resource.view.skeletal-animation")
    :make-view-fn #'make-view))
