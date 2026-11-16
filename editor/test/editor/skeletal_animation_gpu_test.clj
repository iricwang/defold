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

(ns editor.skeletal-animation-gpu-test
  (:require [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-render :as render]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [java.awt.image BufferedImage]
           [javafx.scene Group SubScene]
           [javafx.scene.image ImageView WritableImage]))

(defn- solid [argb]
  (let [image (WritableImage. 2 2)]
    (doseq [x (range 2) y (range 2)] (.setArgb (.getPixelWriter image) x y (unchecked-int argb)))
    image))

(defn- quad [id image]
  {:id id :points [[0.0 0.0] [16.0 0.0] [16.0 16.0] [0.0 16.0]]
   :uvs [[0.0 0.0] [1.0 0.0] [1.0 1.0] [0.0 1.0]]
   :triangles [[0 1 2] [0 2 3]] :image image :opacity 1.0})

(defn- rgba [^BufferedImage image x y]
  (let [argb (.getRGB image x y)]
    (mapv #(bit-and 255 (unsigned-bit-shift-right argb %)) [16 8 0 24])))

(defn- near-pixel? [expected actual]
  (coll/every? #(<= (abs (long %)) 2) (mapv - expected actual)))

;; GLSL readback matches official Spine PMA shader and blendFuncSeparate,
;; including multiply over transparent destinations.
(deftest gpu-blend-and-two-color-pixels-test
  (let [back (quad "back" (solid 0xff336699))
        front (assoc (quad "front" (solid 0xffcc331a)) :opacity 0.5)]
    (doseq [[mode expected] [["normal" [128 77 90 255]] ["additive" [153 128 166 255]]
                             ["multiply" [46 61 84 255]] ["screen" [133 117 158 255]]]]
      (is (near-pixel? expected (rgba (render/render-image 16 16 [back (assoc front :blend mode)]) 8 8)) mode))
    (is (near-pixel? [0 0 0 128] (rgba (render/render-image 16 16 [(assoc front :blend "multiply")]) 8 8)))
    (is (near-pixel? [153 109 108 255]
                     (rgba (render/render-image 16 16 [(assoc (quad "tint" (solid 0xff804020))
                                                         :tint [1.0 0.8 0.6] :dark-tint [0.2 0.3 0.4])]) 8 8)))))

;; Concave clipping removes the notch without triangle seams.
(deftest gpu-clipping-pixels-test
  (let [clip {:id "clip" :clip-polygon [[0 0] [16 0] [16 4] [4 4] [4 16] [0 16]] :clip-end "end"}
        result (render/render-image 16 16 [clip (quad "art" (solid 0xff00ff00)) {:id "end" :marker true}])]
    (is (= [0 255 0 255] (rgba result 2 8)))
    (is (= [0 255 0 255] (rgba result 8 2)))
    (is (= [0 0 0 0] (rgba result 8 8)))))

;; Live preview shares GPU pixels and reuses its surface on unchanged frames.
(deftest gpu-preview-surface-cache-test
  (ui/run-now
    (let [{:keys [node render!]} (render/make-renderer)
          slot (assoc (quad "art" (solid 0xff804020)) :dark-tint [0.2 0.3 0.4])]
      (render! 16.0 16.0 [slot])
      (let [^ImageView surface (.get (.getChildren ^Group (.getRoot ^SubScene node)) 1)
            image (.getImage surface)]
        (is (instance? ImageView surface))
        (is (= (unchecked-int 0xff997979) (.getArgb (.getPixelReader image) 8 8)))
        (render! 16.0 16.0 [slot])
        (is (identical? image (.getImage surface)))
        (is (identical? surface (.get (.getChildren ^Group (.getRoot ^SubScene node)) 1)))))))

;; Texture upload and framebuffer readback preserve top-left orientation,
;; straight-color values, premultiplied alpha, and transparent dark tint edges.
(deftest gpu-texture-orientation-and-alpha-test
  (let [image (WritableImage. 2 2)
        writer (.getPixelWriter image)]
    (.setArgb writer 0 0 (unchecked-int 0xffff0000))
    (.setArgb writer 1 0 (unchecked-int 0xff00ff00))
    (.setArgb writer 0 1 (unchecked-int 0xff0000ff))
    (.setArgb writer 1 1 (unchecked-int 0xffffffff))
    (let [result (render/render-image 16 16 [(quad "image" image)])]
      (is (= [255 0 0 255] (rgba result 1 1)))
      (is (= [0 255 0 255] (rgba result 14 1)))
      (is (= [0 0 255 255] (rgba result 1 14)))
      (is (= [255 255 255 255] (rgba result 14 14)))))
  (let [slot (assoc (quad "alpha" (solid 0x80804020)) :opacity 0.5 :dark-tint [0.2 0.3 0.4])]
    (is (near-pixel? [153 121 121 64] (rgba (render/render-image 16 16 [slot]) 8 8))))
  (is (= [0 0 0 0]
         (rgba (render/render-image 16 16 [(assoc (quad "clear" (solid 0x00804020)) :dark-tint [0.4 0.3 0.2])]) 8 8))))
