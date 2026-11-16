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

(ns editor.skeletal-animation-clipping-test
  (:require [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-clipping :as clipping]
            [editor.skeletal-animation-geometry :as geometry]))

(def ^:private square
  {:id "art" :points [[0.0 0.0] [4.0 0.0] [4.0 4.0] [0.0 4.0]]
   :uvs [[0.0 0.0] [1.0 0.0] [1.0 1.0] [0.0 1.0]] :triangles [[0 1 2] [0 2 3]]})

(defn- area [mesh]
  (reduce + 0.0 (mapv (fn [[a b c]]
                        (let [[[ax ay] [bx by] [cx cy]] (mapv (:points mesh) [a b c])]
                          (* 0.5 (abs (- (* (- bx ax) (- cy ay)) (* (- by ay) (- cx ax))))))) (:triangles mesh))))

;; Areas and interpolated UVs match official Spine 4.2.120 SkeletonClipping,
;; including concave contours, reversed winding and partial intersection.
(deftest clipping-oracle-test
  (doseq [[polygon expected] [[[[0 0] [4 0] [4 1] [1 1] [1 4] [0 4]] 7.0]
                              [[[0 0] [0 4] [1 4] [1 1] [4 1] [4 0]] 7.0]
                              [[[-2 -2] [2 -2] [2 2] [-2 2]] 4.0]
                              [[[1 1] [3 1] [3 3] [1 3]] 4.0]]]
    (let [mesh (clipping/clip-mesh square polygon (clipping/triangulate polygon))]
      (is (< (abs (- expected (area mesh))) 1.0e-8))
      (doseq [[[x y] [u v]] (mapv vector (:points mesh) (:uvs mesh))]
        (is (< (abs (- (/ x 4.0) u)) 1.0e-8))
        (is (< (abs (- (/ y 4.0) v)) 1.0e-8))))))

;; End slots are included, empty end slots work, and clips do not nest.
(deftest clipping-order-and-end-test
  (let [clip {:id "clip" :clip-polygon [[0 0] [1 0] [1 1] [0 1]] :clip-end "end"}
        nested {:id "nested" :clip-polygon [[0 0] [3 0] [3 3] [0 3]]}]
    (is (= [1.0 1.0 16.0]
           (mapv area (clipping/clip-slots [clip nested square (assoc square :id "end") (assoc square :id "after")]))))
    (is (= [1.0 16.0]
           (mapv area (clipping/clip-slots [clip square {:id "end" :marker true} (assoc square :id "after")]))))
    (is (= [1.0] (mapv area (clipping/clip-slots [(dissoc clip :clip-end) square]))))))

;; Polygon overlays share weighted/deformed vertices; point axes reflect bones.
(deftest attachment-overlay-geometry-test
  (let [transforms {"a" [1.0 0.0 0.0 1.0 10.0 20.0] "b" [1.0 0.0 0.0 1.0 30.0 40.0]}
        attachment {:type "clipping" :end "end" :vertices [0.0 0.0 1.0 0.0 0.0 1.0]
                    :weights [[{:bone "a" :weight 0.5 :x 0.0 :y 0.0} {:bone "b" :weight 0.5 :x 0.0 :y 0.0}]
                              [{:bone "a" :weight 1.0 :x 1.0 :y 0.0}]
                              [{:bone "b" :weight 1.0 :x 0.0 :y 1.0}]]}
        clip (geometry/attachment-world-geometry attachment transforms "a" [1.0 2.0 0.0 0.0 0.0 0.0])
        point (geometry/attachment-world-geometry {:type "point" :x 2.0 :y 3.0 :rotation 0.0}
                                                  {"bone" [-2.0 0.0 0.0 3.0 10.0 20.0]} "bone" [])]
    (is (= [[21.0 32.0] [11.0 20.0] [30.0 41.0]] (:points clip)))
    (is (= "end" (:end clip)))
    (is (= [[6.0 29.0] [-42.0 29.0]] (:points point)))
    (is (= 180.0 (:rotation point)))))

;; Invalid polygons fail before allocating meshes or issuing GPU commands.
(deftest invalid-clipping-test
  (doseq [polygon [[] [[0 0] [1 1]] [[0 0] [1 1] [2 2]] [[0 0] [1 ##NaN] [2 0]]]]
    (is (thrown? clojure.lang.ExceptionInfo (clipping/triangulate polygon)))))

;; A clip may collapse while scale/deform animates; the preview must stay live
;; and resume visibility at its end slot instead of disabling its timer.
(deftest collapsed-clipping-test
  (let [clip {:id "clip" :clip-polygon [[0 0] [0 0] [0 0]] :clip-end "end"}
        result (clipping/clip-slots [clip square {:id "end" :marker true} (assoc square :id "after")])]
    (is (= [0.0 16.0] (mapv area result)))))
