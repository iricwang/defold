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
  "Batched textured slot meshes. No per-triangle clipping or offscreen canvases."
  (:require [util.coll :as coll])
  (:import [javafx.application ConditionalFeature Platform]
           [javafx.scene AmbientLight DepthTest Group ParallelCamera SceneAntialiasing SubScene]
           [javafx.scene.control Label]
           [javafx.scene.image Image]
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

(defn- update-slot! [{:keys [^TriangleMesh mesh ^PhongMaterial material] :as entry}
                     {:keys [points uvs triangles ^Image image opacity tint] :or {tint [1.0 1.0 1.0]} :as slot}]
  (when-not (= (count points) (count uvs))
    (throw (ex-info "Mesh UV count must match its vertex count." {:slot (:id slot)})))
  (when-not (and (number? opacity) (Double/isFinite (double opacity)) (<= 0.0 (double opacity) 1.0))
    (throw (ex-info "Mesh opacity must be between zero and one." {:slot (:id slot)})))
  (when-not (and (= 3 (count tint)) (coll/every? #(and (number? %) (Double/isFinite (double %)) (<= 0.0 (double %) 1.0)) tint))
    (throw (ex-info "Mesh tint needs three finite channels between zero and one." {:slot (:id slot)})))
  (when (not= points (:last-points entry))
    (.setAll (.getPoints mesh) (point-buffer points)))
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
  (assoc entry :last-points points :last-uvs uvs :last-triangles triangles :last-image image :last-opacity opacity :last-tint tint))

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
          previous-order (atom [])]
      (.add (.getChildren root) light)
      {:node scene
       :render! (fn [width height render-slots]
                  (let [ids (mapv :id render-slots)]
                    (when (not= (count ids) (count (set ids)))
                      (throw (ex-info "Rendered skeleton slots need unique IDs." {})))
                    (.setWidth scene (max 1.0 (double width)))
                    (.setHeight scene (max 1.0 (double height)))
                    (let [next-entries (reduce (fn [result slot]
                                                 (assoc result (:id slot) (update-slot! (or (get @entries (:id slot)) (make-slot)) slot)))
                                               {} render-slots)]
                      (when (not= ids @previous-order)
                        ;; With no depth buffer, equal viewOrder and disabled
                        ;; depth testing, child order is the Spine slot order.
                        (.setAll (.getChildren root) ^java.util.Collection (into [light] (map #(get-in next-entries [% :view])) ids))
                        (reset! previous-order ids))
                      (reset! entries next-entries))))})))
