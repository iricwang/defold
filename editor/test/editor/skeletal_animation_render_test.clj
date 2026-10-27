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

(ns editor.skeletal-animation-render-test
  (:require [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-render :as render]
            [editor.ui :as ui])
  (:import [javafx.collections ArrayChangeListener]
           [javafx.scene AmbientLight DepthTest Group ParallelCamera SubScene]
           [javafx.scene.control Label]
           [javafx.scene.image WritableImage]
           [javafx.scene.paint Color PhongMaterial]
           [javafx.scene.shape CullFace MeshView TriangleMesh]))

(defn- slot [id]
  {:id id :points [[10.0 20.0] [110.0 20.0] [110.0 120.0] [10.0 120.0]]
   :uvs [[0.0 0.0] [1.0 0.0] [1.0 1.0] [0.0 1.0]]
   :triangles [[0 1 2] [0 2 3]] :image nil :opacity 1.0})

;; Each vertex keeps the exact screen-space XY and texture UV pairing; faces
;; interleave vertex and UV indices without triangulation or orientation changes.
(deftest mesh-buffer-test
  (let [{:keys [points uvs triangles]} (slot "image")]
    (is (= [10.0 20.0 0.0 110.0 20.0 0.0 110.0 120.0 0.0 10.0 120.0 0.0]
           (vec (render/point-buffer points))))
    (is (= [0.0 0.0 1.0 0.0 1.0 1.0 0.0 1.0] (vec (render/uv-buffer uvs))))
    (is (= [0 0 1 1 2 2 0 0 2 2 3 3] (vec (render/face-buffer triangles 4))))
    (is (= [] (vec (render/point-buffer []))))
    (is (= [] (vec (render/face-buffer [] 0))))))

;; Invalid or overflowing input fails before uploading malformed buffers to
;; the graphics driver, including NaN, wrong arity and out-of-range indices.
(deftest invalid-mesh-buffer-test
  (doseq [points [[[##NaN 0.0]] [[##Inf 0.0]] [[1.0e300 0.0]] [[0.0]]]]
    (is (thrown? clojure.lang.ExceptionInfo (render/point-buffer points))))
  (is (thrown? clojure.lang.ExceptionInfo (render/uv-buffer [[0.0 ##NaN]])))
  (doseq [triangles [[[0 1 4]] [[0 -1 2]] [[0 1]] [[0 1 1.5]]]]
    (is (thrown? clojure.lang.ExceptionInfo (render/face-buffer triangles 4)))))

;; Missing GPU features produce an explanatory UI message with a safe no-op
;; renderer, so one unsupported preview cannot break the editor's event loop.
(deftest unsupported-renderer-test
  (ui/run-now
    (with-redefs [render/hardware-supported? (constantly false)]
      (let [{:keys [node render!]} (render/make-renderer)]
        (is (instance? Label node))
        (is (= "skeletal-render-unavailable" (.getId ^Label node)))
        (is (re-find #"hardware-accelerated" (.getText ^Label node)))
        (.setManaged ^Label node false)
        (is (nil? (render! 640.0 480.0 [(slot "image")])))
        (is (= [640.0 480.0] [(.getWidth ^Label node) (.getHeight ^Label node)]))
        (render! 0.0 -5.0 [])
        (is (= [1.0 1.0] [(.getWidth ^Label node) (.getHeight ^Label node)]))))))

;; Construction uses an unlit parallel projection and painter's slot order,
;; and removes unused mesh nodes without replacing surviving slot objects.
(deftest batched-renderer-construction-test
  (ui/run-now
    (with-redefs [render/hardware-supported? (constantly true)]
      (let [{:keys [node render!]} (render/make-renderer)
            ^SubScene scene node
            ^Group root (.getRoot scene)]
        (is (instance? ParallelCamera (.getCamera scene)))
        (is (= Color/TRANSPARENT (.getFill scene)))
        (is (false? (.isDepthBuffer scene)))
        (render! 640.0 480.0 [(slot "back") (slot "front")])
        (let [children (.getChildren root)
              back (.get children 1) front (.get children 2)]
          (is (instance? AmbientLight (first children)))
          (is (= [640.0 480.0] [(.getWidth scene) (.getHeight scene)]))
          (is (= CullFace/NONE (.getCullFace ^MeshView back)))
          (is (= DepthTest/DISABLE (.getDepthTest ^MeshView back)))
          (render! 640.0 480.0 [(slot "front") (slot "back")])
          (is (identical? front (.get (.getChildren root) 1)))
          (is (identical? back (.get (.getChildren root) 2)))
          (render! 320.0 240.0 [(slot "back")])
          (is (= 2 (count (.getChildren root))))
          (is (identical? back (.get (.getChildren root) 1)))
          (render! 320.0 240.0 [])
          (is (= 1 (count (.getChildren root)))))))))

;; Animation only uploads changed vertex positions. Stable UVs and faces keep
;; their GPU buffers, and alpha lives in the material rather than an offscreen
;; node-opacity layer. Image swaps reuse the mesh and material objects.
(deftest animated-renderer-cache-test
  (ui/run-now
    (with-redefs [render/hardware-supported? (constantly true)]
      (let [{:keys [node render!]} (render/make-renderer)
            image (WritableImage. 2 2)
            frame (assoc (slot "image") :image image :opacity 0.5)]
        (render! 640.0 480.0 [frame])
        (let [^MeshView view (.get (.getChildren ^Group (.getRoot ^SubScene node)) 1)
              ^TriangleMesh mesh (.getMesh view)
              ^PhongMaterial material (.getMaterial view)
              changes (atom {:points 0 :uvs 0 :faces 0})]
          (doseq [[key array] [[:points (.getPoints mesh)] [:uvs (.getTexCoords mesh)] [:faces (.getFaces mesh)]]]
            (.addListener ^javafx.collections.ObservableArray array
                          (reify ArrayChangeListener
                            (onChanged [_ _ _ _ _] (swap! changes update key inc)))))
          (is (= 0.5 (.getOpacity (.getDiffuseColor material))))
          (is (= 1.0 (.getOpacity view)))
          (is (identical? image (.getDiffuseMap material)))
          (render! 640.0 480.0 [frame])
          (is (= {:points 0 :uvs 0 :faces 0} @changes))
          (render! 640.0 480.0 [(assoc-in frame [:points 0] [15.0 25.0])])
          (is (= {:points 1 :uvs 0 :faces 0} @changes))
          ;; Color animation changes only the material, never texture identity or geometry.
          (render! 640.0 480.0 [(assoc frame :tint [0.25 0.5 0.75])])
          (is (identical? image (.getDiffuseMap material)))
          (is (= (Color/color 0.25 0.5 0.75 0.5) (.getDiffuseColor material)))
          (is (= {:points 2 :uvs 0 :faces 0} @changes))
          (is (thrown? clojure.lang.ExceptionInfo (render! 640.0 480.0 [(assoc frame :tint [1.0 ##NaN 0.0])])))
          (let [replacement (WritableImage. 1 1)]
            (render! 640.0 480.0 [(assoc frame :image replacement :opacity 0.25)])
            (is (identical? material (.getMaterial view)))
            (is (identical? mesh (.getMesh view)))
            (is (identical? replacement (.getDiffuseMap material)))
            (is (= 0.25 (.getOpacity (.getDiffuseColor material)))))
          (is (thrown? clojure.lang.ExceptionInfo (render! 640.0 480.0 [frame frame])))
          (is (thrown? clojure.lang.ExceptionInfo (render! 640.0 480.0 [(assoc frame :opacity ##NaN)])))
          ;; ui/run-now rethrows Throwable return values, including the result
          ;; returned by a successful thrown? assertion.
          nil)))))
