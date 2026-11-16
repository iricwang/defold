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

(ns editor.skeletal-animation-render
  "Batched textured slots with shared clipping, color and export plans."
  (:require [editor.skeletal-animation-clipping :as clipping]
            [editor.skeletal-animation-gpu :as gpu]
            [util.coll :as coll])
  (:import [javafx.application ConditionalFeature Platform]
           [javafx.scene AmbientLight DepthTest Group ParallelCamera SceneAntialiasing SubScene]
           [javafx.scene.control Label]
           [javafx.scene.image Image ImageView]
           [javafx.scene.paint Color PhongMaterial]
           [javafx.scene.shape CullFace MeshView TriangleMesh]))

(set! *warn-on-reflection* true)

(defn hardware-supported? []
  (Platform/isSupported ConditionalFeature/SCENE3D))

(defn- checked-float [value]
  (when-not (and (number? value) (Double/isFinite (double value))
                 (<= (- (double Float/MAX_VALUE)) (double value) (double Float/MAX_VALUE)))
    (throw (ex-info "Skeleton mesh coordinates must fit in finite GPU floats." {:value value})))
  (float value))

(defn point-buffer
  "Pack screen-space XY vertices into the GPU's XYZ float buffer."
  ^floats [points]
  (let [result (float-array (* 3 (count points)))]
    (reduce-kv (fn [_ index xy]
                 (when-not (= 2 (count xy)) (throw (ex-info "Mesh vertices need two coordinates." {:vertex index})))
                 (aset-float result (* index 3) (checked-float (nth xy 0)))
                 (aset-float result (inc (* index 3)) (checked-float (nth xy 1)))) nil points)
    result))

(defn uv-buffer
  "Pack one UV pair per vertex without changing image orientation."
  ^floats [uvs]
  (let [result (float-array (* 2 (count uvs)))]
    (reduce-kv (fn [_ index uv]
                 (when-not (= 2 (count uv)) (throw (ex-info "Mesh UVs need two coordinates." {:vertex index})))
                 (aset-float result (* index 2) (checked-float (nth uv 0)))
                 (aset-float result (inc (* index 2)) (checked-float (nth uv 1)))) nil uvs)
    result))

(defn face-buffer
  "Pack triangles as alternating point/UV indices for POINT_TEXCOORD meshes."
  ^ints [triangles vertex-count]
  (let [result (int-array (* 6 (count triangles)))]
    (reduce-kv (fn [_ triangle-index triangle]
                 (when-not (= 3 (count triangle)) (throw (ex-info "Mesh faces must be triangles." {:face triangle-index})))
                 (reduce-kv (fn [_ corner index]
                              (when-not (and (integer? index) (<= 0 index) (< index vertex-count))
                                (throw (ex-info "Mesh face references an invalid vertex." {:index index :vertices vertex-count})))
                              (let [offset (+ (* triangle-index 6) (* corner 2))]
                                (aset-int result offset (int index))
                                (aset-int result (inc offset) (int index)))) nil triangle)) nil triangles)
    result))

(defn- make-slot []
  (let [mesh (TriangleMesh.)
        material (doto (PhongMaterial.) (.setSpecularColor Color/BLACK))
        view (doto (MeshView. mesh)
               (.setMaterial material) (.setCullFace CullFace/NONE)
               (.setDepthTest DepthTest/DISABLE) (.setMouseTransparent true))]
    {:mesh mesh :material material :view view :last-image ::unset}))

(defn- update-point-buffer! [entry points]
  ;; Validate before mutating the reusable scratch, including failed updates.
  (doseq [xy points]
    (when-not (= 2 (count xy)) (throw (ex-info "Mesh vertices need two coordinates." {})))
    (checked-float (xy 0))
    (checked-float (xy 1)))
  (let [size (* 3 (count points))
        ^floats previous (:point-buffer entry)
        resized (or (nil? previous) (not= size (alength previous)))
        ^floats buffer (if resized (float-array size) previous)
        changed (loop [index 0 first-changed size last-changed 0]
                  (if (= index (count points))
                    [first-changed last-changed]
                    (let [xy (points index)
                          x (float (xy 0)) y (float (xy 1))
                          offset (* 3 index)
                          different (or (not= x (aget buffer offset)) (not= y (aget buffer (inc offset))))]
                      (aset-float buffer offset x)
                      (aset-float buffer (inc offset) y)
                      (recur (inc index) (if different (min first-changed offset) first-changed)
                             (if different (+ offset 2) last-changed)))))
        [from to] changed
        ^TriangleMesh mesh (:mesh entry)]
    ;; Publish only after every coordinate passed validation. Size changes
    ;; replace the array; fixed topology uploads the smallest changed interval.
    (if resized
      (.setAll (.getPoints mesh) buffer)
      (when (< from to) (.set (.getPoints mesh) (int from) buffer (int from) (int (- to from)))))
    (assoc entry :point-buffer buffer)))

(defn- update-slot! [{:keys [^TriangleMesh mesh ^PhongMaterial material] :as entry}
                     {:keys [points uvs triangles ^Image image opacity tint] :or {tint [1.0 1.0 1.0]} :as slot}]
  (when-not (= (count points) (count uvs))
    (throw (ex-info "Mesh UV count must match its vertex count." {:slot (:id slot)})))
  (when-not (and (number? opacity) (Double/isFinite (double opacity)) (<= 0.0 (double opacity) 1.0))
    (throw (ex-info "Mesh opacity must be between zero and one." {:slot (:id slot)})))
  (when-not (and (= 3 (count tint)) (coll/every? #(and (number? %) (Double/isFinite (double %)) (<= 0.0 (double %) 1.0)) tint))
    (throw (ex-info "Mesh tint needs three finite channels between zero and one." {:slot (:id slot)})))
  (let [entry (if (= points (:last-points entry)) entry (update-point-buffer! entry points))]
    (when (not= uvs (:last-uvs entry))
      (.setAll (.getTexCoords mesh) (uv-buffer uvs)))
    (when (or (not= triangles (:last-triangles entry)) (not= (count points) (count (:last-points entry))))
      (.setAll (.getFaces mesh) (face-buffer triangles (count points))))
    (when (not (identical? image (:last-image entry)))
      (.setDiffuseMap material image))
    (when (or (not (identical? image (:last-image entry))) (not= opacity (:last-opacity entry)) (not= tint (:last-tint entry)))
      ;; Material alpha multiplies texture alpha directly on the GPU. Node opacity
      ;; can create an intermediate surface, defeating the batched mesh renderer.
      (.setDiffuseColor material (if image (Color/color (tint 0) (tint 1) (tint 2) opacity) (Color/web "#584455" opacity))))
    (assoc entry :last-points points :last-uvs uvs :last-triangles triangles :last-image image :last-opacity opacity :last-tint tint)))

(defn render-plan
  "Return validated, draw-ordered texture meshes with Spine clipping applied.
  Clip records use :clip-polygon and :clip-end; :marker records retain empty
  slots needed to end clipping. Textures add :dark-tint RGB and :blend."
  [slots]
  (let [ids (mapv :id slots)]
    (when (not= (count ids) (count (set ids)))
      (throw (ex-info "Rendered skeleton slots need unique IDs." {})))
    (mapv (fn [slot]
            (let [slot (merge {:tint [1.0 1.0 1.0] :dark-tint [0.0 0.0 0.0] :opacity 1.0 :blend "normal"} slot)
                  slot (update slot :dark-tint #(or % [0.0 0.0 0.0]))]
              (when-not (and (contains? #{"normal" "additive" "multiply" "screen"} (:blend slot))
                             (number? (:opacity slot)) (<= 0.0 (double (:opacity slot)) 1.0)
                             (= (count (:points slot)) (count (:uvs slot))))
                (throw (ex-info "Invalid skeleton render slot." {:slot (:id slot)})))
              (doseq [color [(:tint slot) (:dark-tint slot)]]
                (when-not (and (= 3 (count color))
                               (coll/every? #(and (number? %) (Double/isFinite (double %)) (<= 0.0 (double %) 1.0)) color))
                  (throw (ex-info "Slot tint requires three finite channels in 0..1." {:slot (:id slot)}))))
              (doseq [coordinates (into (:points slot) (:uvs slot))]
                (when-not (= 2 (count coordinates))
                  (throw (ex-info "Rendered mesh coordinates must be XY or UV pairs." {})))
                (run! checked-float coordinates))
              (doseq [triangle (:triangles slot)]
                (when-not (and (= 3 (count triangle))
                               (coll/every? #(and (integer? %) (<= 0 %) (< % (count (:points slot)))) triangle))
                  (throw (ex-info "Rendered triangles must reference existing vertices." {}))))
              slot))
          (clipping/clip-slots slots))))

(defn render-image
  "Render the same clipped, tinted plan as preview into BufferedImage ARGB.
  Calls may run on export workers; the shared GPU context serializes access."
  ^java.awt.image.BufferedImage [width height slots]
  (gpu/render-image width height (render-plan slots)))

(defn make-renderer
  "Return {:node :render!}. render! accepts width, height and ordered slots:
  {:id :points [[screen-x screen-y] ...] :uvs [[u v] ...]
   :triangles [[a b c] ...] :image Image-or-nil :opacity 0..1 :tint [r g b]}.
  Calls belong on the JavaFX thread. Missing hardware support displays an
  explicit message; it never falls back to costly per-triangle Canvas clips."
  []
  (if-not (hardware-supported?)
    (let [message (doto (Label. "Skeleton preview requires hardware-accelerated 3D graphics. Enable GPU acceleration to continue.")
                    (.setId "skeletal-render-unavailable") (.setWrapText true) (.setMouseTransparent true))]
      {:node message
       :render! (fn [width height _] (.resize message (max 1.0 (double width)) (max 1.0 (double height))))})
    (let [root (doto (Group.) (.setDepthTest DepthTest/DISABLE))
          light (AmbientLight. Color/WHITE)
          scene (doto (SubScene. root 1.0 1.0 false SceneAntialiasing/BALANCED)
                  (.setId "skeletal-mesh-layer") (.setFill Color/TRANSPARENT)
                  (.setCamera (ParallelCamera.)) (.setMouseTransparent true))
          entries (atom {})
          previous-order (atom [])
          advanced-view (doto (ImageView.) (.setMouseTransparent true) (.setSmooth false))
          previous-advanced (atom nil)]
      (.add (.getChildren root) light)
      {:node scene
       :render! (fn [width height render-slots]
                  (let [width (max 1.0 (double width)) height (max 1.0 (double height))
                        advanced (coll/any? #(or (:clip-polygon %)
                                                 (not= "normal" (get % :blend "normal"))
                                                 (coll/any? pos? (:dark-tint %))) render-slots)]
                    (.setWidth scene width)
                    (.setHeight scene height)
                    (if advanced
                      (let [frame {:width width :height height :slots render-slots}]
                        (when (not= frame @previous-advanced)
                          (let [image (render-image (int (Math/ceil width)) (int (Math/ceil height)) render-slots)]
                            (.setImage advanced-view (gpu/update-fx-image! image (.getImage advanced-view))))
                          (reset! previous-advanced frame))
                        (when (not= [::advanced] @previous-order)
                          (let [^java.util.Collection children [light advanced-view]]
                            (.setAll (.getChildren root) children))
                          (reset! previous-order [::advanced])))
                      (let [slots (render-plan render-slots)
                            ids (mapv :id slots)
                            next-entries (reduce (fn [result slot]
                                                   (assoc result (:id slot) (update-slot! (or (get @entries (:id slot)) (make-slot)) slot)))
                                                 {} slots)]
                        (when (not= ids @previous-order)
                          (let [^java.util.Collection children (coll/into-> ids [light] (map #(get-in next-entries [% :view])))]
                            (.setAll (.getChildren root) children))
                          (reset! previous-order ids))
                        (reset! entries next-entries)))))})))
