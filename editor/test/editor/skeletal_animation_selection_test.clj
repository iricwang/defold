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

(ns editor.skeletal-animation-selection-test
  (:require [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-render :as render]
            [editor.skeletal-animation-selection :as selection]
            [util.coll :as coll])
  (:import [javafx.scene.image WritableImage]
           [javafx.scene.paint Color]))

(defn- quad [id]
  {:id id :points [[0.0 0.0] [100.0 0.0] [100.0 100.0] [0.0 100.0]]
   :uvs [[0.0 0.0] [1.0 0.0] [1.0 1.0] [0.0 1.0]]
   :triangles [[0 1 2] [0 2 3]] :opacity 1.0})

;; Transparent pixels pass through to lower attachments in painter order.
(deftest transparent-picking-test
  (let [image (WritableImage. 2 2)
        _ (.setColor (.getPixelWriter image) 0 0 Color/WHITE)
        front (assoc (quad "front") :image image)
        back (quad "back")]
    (is (= "front" (selection/pick-slot [back front] [25.0 25.0])))
    (is (= "back" (selection/pick-slot [back front] [75.0 75.0])))
    (is (= "back" (selection/pick-slot [back (assoc front :opacity 0.0)] [25.0 25.0])))
    (is (nil? (selection/pick-slot [front] [75.0 75.0])))
    (is (nil? (selection/pick-slot [back] [-1.0 20.0])))
    (is (nil? (selection/pick-slot [(assoc back :points [[0 0] [0 0] [0 0] [0 0]])] [0 0])))))

;; Skin/clipping output is the picking geometry, never the unclipped bounds.
(deftest clipped-picking-test
  (let [plan (render/render-plan [{:id "clip" :clip-polygon [[0 0] [50 0] [50 100] [0 100]] :clip-end "front"}
                                  (quad "front") (assoc (quad "back") :opacity 0.0)])]
    (is (= "front" (selection/pick-slot plan [25.0 25.0])))
    (is (nil? (selection/pick-slot plan [75.0 25.0])))))

;; Marquee direction does not matter, and partial/hidden attachments stay out.
(deftest marquee-selection-test
  (let [plan [(quad "a") (quad "b") (assoc (quad "hidden") :opacity 0.0)]]
    (is (= #{"a" "b"} (selection/box-slots plan [-1 -1] [101 101])))
    (is (= #{"a" "b"} (selection/box-slots plan [101 101] [-1 -1])))
    (is (= #{} (selection/box-slots plan [10 10] [90 90])))))

;; Silhouettes follow texture alpha and reuse UV weights after mesh deformation.
(deftest texture-outline-test
  (let [image (WritableImage. 2 2)
        _ (.setColor (.getPixelWriter image) 0 0 Color/WHITE)
        attachment (assoc (quad "part") :image image)
        binding (selection/bind-outline attachment)
        edges (selection/project-outline binding (:points attachment))
        moved (selection/project-outline binding (mapv #(mapv + % [10.0 20.0]) (:points attachment)))]
    (is (>= (count edges) 4))
    (is (coll/every? #(coll/every? (fn [v] (<= 0.0 v 75.0)) %) (coll/into-> edges [] cat)))
    (is (coll/every? #(< (abs (double %)) 1.0e-8)
                     (map - (flatten (mapv (fn [edge] (mapv #(mapv + % [10.0 20.0]) edge)) edges))
                          (flatten moved))))
    (is (= 4 (count (selection/bind-outline (quad "missing-image")))))))

;; A shared diagonal is drawn once and stays distinct from the hull. Reversed
;; triangle winding must not duplicate edges or change the selected outline.
(deftest mesh-wireframe-edge-classification-test
  (doseq [triangles [[[0 1 2] [0 2 3]] [[2 1 0] [3 2 0]]]]
    (let [{:keys [boundary interior]} (selection/wireframe-edges triangles)]
      (is (= #{[0 1] [1 2] [2 3] [0 3]} (set boundary)))
      (is (= [[0 2]] interior))))
  (is (= {:boundary [] :interior []} (selection/wireframe-edges []))))

;; Alpha interpolation follows a curved antialiased silhouette instead of
;; introducing binary pixel stairs; binding remains reusable under deformation.
(deftest subpixel-curved-outline-test
  (let [image (WritableImage. 64 64) writer (.getPixelWriter image)]
    (dotimes [y 64]
      (dotimes [x 64]
        (let [distance (Math/hypot (- (+ x 0.5) 32.0) (- (+ y 0.5) 32.0))
              alpha (max 0.0 (min 1.0 (- 20.5 distance)))]
          (.setColor writer x y (Color/color 1.0 1.0 1.0 alpha)))))
    (let [edges (#'selection/contour-segments (#'selection/texture-edges image) (/ 0.2 64.0))
          distances (mapv (fn [[x y]] (Math/hypot (- (* x 64.0) 32.0) (- (* y 64.0) 32.0)))
                          (coll/into-> edges [] cat))]
      (is (> (count edges) 20))
      (is (< (- (reduce max distances) (reduce min distances)) 1.0))
      (is (coll/every? #(<= 19.8 % 20.2) distances)))))

;; Disconnected islands and transparent holes stay separate closed boundaries,
;; including opaque shapes touching the texture edge.
(deftest outline-islands-and-hole-test
  (let [image (WritableImage. 32 32) writer (.getPixelWriter image)]
    (dotimes [y 32]
      (dotimes [x 32]
        (when (or (and (< x 18) (< y 18) (not (and (<= 5 x 12) (<= 5 y 12))))
                  (and (<= 24 x 28) (<= 24 y 28)))
          (.setColor writer x y Color/WHITE))))
    (let [edges (#'selection/texture-edges image)
          starts (frequencies (mapv first edges))
          ends (frequencies (mapv second edges))]
      (is (= starts ends))
      (is (pos? (count edges)))
      (is (coll/every? #(coll/every? (fn [v] (<= 0.0 v 1.0)) %) (coll/into-> edges [] cat)))
      (is (coll/any? (fn [[[ax ay] [bx by]]] (and (< ax 0.5) (< bx 0.5) (> ay 0.1) (> by 0.1))) edges)))))

;; Fully translucent artwork still has a selectable outline; the threshold is
;; relative to the texture's peak coverage, not a global opacity cutoff.
(deftest translucent-outline-test
  (let [image (WritableImage. 4 4) writer (.getPixelWriter image)]
    (.setColor writer 1 1 (Color/color 1.0 1.0 1.0 0.2))
    (is (pos? (count (selection/bind-outline (assoc (quad "translucent") :image image))))))
  (is (zero? (count (selection/bind-outline (assoc (quad "empty") :image (WritableImage. 4 4)))))))
