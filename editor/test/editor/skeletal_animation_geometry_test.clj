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

(ns editor.skeletal-animation-geometry-test
  (:require [clojure.test :refer [deftest is testing]]
            [editor.skeletal-animation-geometry :as geometry]
            [util.coll :as coll]))

(defn- near? [expected actual]
  (and (= (count expected) (count actual))
       (coll/every? #(< (abs (double %)) 1.0e-5) (mapv - expected actual))))

(defn- endpoint [bones id]
  (let [bone (coll/first-where #(= id (:id %)) bones)]
    (geometry/transform-point ((geometry/world-transforms bones) id) [(:length bone 0.0) 0.0])))

;; Affine composition and inversion must preserve reflected, rotated hierarchies
;; and reject malformed graphs before they produce invalid viewport geometry.
(deftest transform-hierarchy-test
  (let [bones [{:id "child" :parent "root" :x 4.0 :y 3.0 :rotation 27.0 :scale-x -2.0}
               {:id "root" :x 8.0 :y -5.0 :rotation 40.0 :scale-y 2.0}]
        transforms (geometry/world-transforms bones)
        point [7.0 -2.0]
        expected (geometry/transform-point (geometry/local-transform (bones 1))
                                           (geometry/transform-point (geometry/local-transform (bones 0)) point))]
    (is (near? expected (geometry/transform-point (transforms "child") point)))
    (is (near? point (geometry/inverse-transform-point (transforms "child") expected)))
    (is (nil? (geometry/inverse-transform (geometry/local-transform {:scale-x 0.0}))))
    (is (thrown? clojure.lang.ExceptionInfo (geometry/world-transforms [{:id "a" :parent "b"} {:id "b" :parent "a"}])))
    (is (thrown? clojure.lang.ExceptionInfo (geometry/world-transforms [{:id "a" :parent "missing"}])))
    (is (thrown? clojure.lang.ExceptionInfo (geometry/world-transforms [{:id "a"} {:id "a"}])))
    (is (thrown? clojure.lang.ExceptionInfo (geometry/local-transform {:x ##NaN})))))

;; Weighted deforms are indexed per influence, including zero weights; merging
;; bones or dropping entries during evaluation would shift subsequent offsets.
(deftest mesh-deformation-test
  (let [worlds {"a" geometry/identity-transform "b" (geometry/local-transform {:x 10.0 :y 20.0})}
        mesh {:vertices [0.0 0.0 2.0 3.0]
              :weights [[{:bone "a" :weight 0.25 :x 0.0 :y 0.0}
                         {:bone "b" :weight 0.75 :x -10.0 :y -20.0}]
                        [{:bone "a" :weight 0.0 :x 99.0 :y 99.0}
                         {:bone "a" :weight 1.0 :x 2.0 :y 3.0}]]}]
    (is (near? [0.0 0.0 2.0 3.0] (geometry/mesh-world-vertices mesh geometry/identity-transform worlds [])))
    (is (near? [3.5 5.0 5.0 7.0]
               (geometry/mesh-world-vertices mesh geometry/identity-transform worlds [2.0 2.0 4.0 6.0 100.0 200.0 3.0 4.0])))
    (is (near? [1.0 2.0 5.0 7.0]
               (geometry/mesh-world-vertices mesh geometry/identity-transform worlds [1.0 2.0 3.0 4.0])))
    (is (near? [11.0 22.0 15.0 27.0]
               (geometry/mesh-world-vertices {:vertices [0.0 0.0 2.0 3.0]} (worlds "b") worlds [1.0 2.0 3.0 4.0])))
    (is (thrown? clojure.lang.ExceptionInfo (geometry/mesh-world-vertices mesh geometry/identity-transform worlds [1.0])))
    (is (thrown? clojure.lang.ExceptionInfo (geometry/mesh-world-vertices mesh geometry/identity-transform {} [])))))

;; Region conversion preserves the rendered corners and creates consistently
;; oriented indexed triangles with UVs bounded by the original texture region.
(deftest region-grid-test
  (let [mesh (geometry/region-to-mesh {:type "region" :width 20.0 :height 10.0 :x 4.0 :y 6.0 :rotation 90.0} 2 2)]
    (is (= "mesh" (:type mesh)))
    (is (= 18 (count (:vertices mesh)) (count (:uvs mesh))))
    (is (= 24 (count (:triangles mesh))))
    (is (near? [9.0 -4.0] (subvec (:vertices mesh) 0 2)))
    (is (near? [-1.0 16.0] (subvec (:vertices mesh) 16 18)))
    (is (coll/every? #(<= 0.0 % 1.0) (:uvs mesh)))
    (is (coll/every? #(<= 0 % 8) (:triangles mesh)))
    (is (not (contains? mesh :rotation)))
    (is (thrown? clojure.lang.ExceptionInfo (geometry/region-to-mesh {:width 0 :height 1} 1 1)))
    (is (thrown? clojure.lang.ExceptionInfo (geometry/region-to-mesh {:width 1 :height 1} 0 1)))))

;; Painting keeps weights normalized and the other influences proportional,
;; while duplicate influences preserve their original blended bind position.
(deftest weight-paint-test
  (let [influences [{:bone "a" :weight 2.0 :x 0.0 :y 0.0}
                    {:bone "b" :weight 1.0 :x 10.0 :y 20.0}]
        painted (geometry/paint-influences influences "c" 0.25 [3.0 4.0])]
    (is (near? [0.5 0.25 0.25] (mapv :weight painted)))
    (is (= {:bone "c" :weight 0.25 :x 3.0 :y 4.0} (peek painted)))
    (is (= [{:bone "c" :weight 1.0 :x 3.0 :y 4.0}]
           (geometry/paint-influences painted "c" 1.0 [3.0 4.0])))
    (is (near? [1.0 5.0 10.0]
               ((juxt :weight :x :y) (first (geometry/normalize-influences [{:bone "a" :weight 1.0 :x 0.0 :y 0.0}
                                                                          {:bone "a" :weight 1.0 :x 10.0 :y 20.0}])))))
    (is (= [] (geometry/normalize-influences [])))
    (is (thrown? clojure.lang.ExceptionInfo (geometry/normalize-influences [{:bone "a" :weight -1.0 :x 0.0 :y 0.0}])))))

;; One-bone constraints aim in parent coordinates, honor reflection and blend
;; rotation rather than moving the authored bone or changing its length.
(deftest one-bone-ik-test
  (let [bones [{:id "root" :x 5.0 :y 7.0 :rotation 30.0 :scale-x 2.0 :scale-y 0.5}
               {:id "arm" :parent "root" :length 10.0}
               {:id "target" :parent "root" :y 10.0}]
        constraint {:bones ["arm"] :target "target"}
        solved (geometry/solve-ik bones [constraint])
        half (geometry/solve-ik bones [(assoc constraint :mix 0.5)])]
    (is (near? [90.0] [(:rotation (solved 1))]))
    (is (near? [45.0] [(:rotation (half 1))]))
    (is (near? (geometry/transform-point ((geometry/world-transforms solved) "target") [0.0 0.0]) (endpoint solved "arm")))
    (is (= bones (geometry/solve-ik bones [(assoc constraint :mix 0.0)])))
    (is (near? [0.0 10.0]
               (endpoint (geometry/solve-ik [{:id "arm" :length 10.0 :scale-x -1.0} {:id "target" :y 10.0}]
                                            [constraint]) "arm")))))

;; Two-bone IK reaches both bend directions and transformed parents. Invalid,
;; collapsed and unreachable chains remain deterministic and contain no NaNs.
(deftest two-bone-ik-test
  (doseq [bend [true false]
          [tx ty] [[10.0 10.0] [-7.0 11.0] [4.0 -6.0]]]
    (let [bones [{:id "root" :x 10.0 :y 20.0 :rotation 30.0 :scale-x 1.5 :scale-y 0.8}
                 {:id "upper" :parent "root" :length 10.0}
                 {:id "lower" :parent "upper" :x 10.0 :length 10.0}
                 {:id "target" :parent "root" :x tx :y ty}]
          solved (geometry/solve-ik bones [{:bones ["upper" "lower"] :target "target" :bend-positive bend}])
          target (geometry/transform-point ((geometry/world-transforms solved) "target") [0.0 0.0])]
      (is (near? target (endpoint solved "lower")))
      (is (= bend (pos? (:rotation (solved 2)))))))
  (testing "unreachable and degenerate targets"
    (doseq [[first-length second-length target-x target-y] [[10.0 10.0 100.0 0.0]
                                                           [0.0 10.0 3.0 4.0]
                                                           [10.0 0.0 3.0 4.0]
                                                           [0.0 0.0 0.0 0.0]
                                                           [10.0 10.0 0.0 0.0]]]
      (let [bones [{:id "a" :length first-length}
                   {:id "b" :parent "a" :x first-length :length second-length}
                   {:id "target" :x target-x :y target-y}]
            solved (geometry/solve-ik bones [{:bones ["a" "b"] :target "target"}])]
        (is (coll/every? geometry/finite-number? (mapv #(:rotation % 0.0) solved)))
        (when (= 100.0 target-x)
          (is (near? [20.0 0.0] (endpoint solved "b")))))))
  (is (thrown? clojure.lang.ExceptionInfo (geometry/solve-ik [{:id "a"}] [{:bones ["a"] :target "missing"}]))))

;; A nonuniform or reflected scale on the constrained bone makes the reachable
;; chain elliptical; the refinement must reach it without invalid rotations.
(deftest scaled-chain-ik-test
  (doseq [scale-x [0.5 1.0 2.0 -1.0]
          scale-y [0.5 2.0 -1.0]
          bend [true false]]
    (let [bones [{:id "a" :length 10.0 :scale-x scale-x :scale-y scale-y}
                 {:id "b" :parent "a" :x 10.0 :length 10.0}
                 {:id "target" :x 4.0 :y 6.0}]
          solved (geometry/solve-ik bones [{:bones ["a" "b"] :target "target" :bend-positive bend}])]
      (is (near? [4.0 6.0] (endpoint solved "b")))))
  (let [bones [{:id "root" :scale-x 0.0}
               {:id "a" :parent "root" :length 10.0}
               {:id "b" :parent "a" :x 10.0 :length 10.0}
               {:id "target" :x 4.0 :y 6.0}]
        solved (geometry/solve-ik bones [{:bones ["a" "b"] :target "target"}])]
    (is (coll/every? geometry/finite-number? (endpoint solved "b")))))
