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

(ns editor.skeletal-animation-deform-review-test
  (:require [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-geometry :as geometry]
            [editor.skeletal-animation-interpolation :as interpolation]
            [editor.skeletal-animation-slots :as slots]
            [util.coll :as coll]))

(defn- near? [a b]
  (< (abs (double (- a b))) 1.0e-8))

(defn- preview-points [doc time]
  (let [sampled (slots/sample-slots doc "default" time)
        slot (first (:slots sampled))
        worlds (data/world-transforms (:bones doc))]
    (vec (sort (partitionv 2 (geometry/mesh-world-vertices (:attachment slot) (worlds (:bone slot)) worlds
                                                        (get-in sampled [:deforms "mesh"])))))))

(defn- fixture [weighted]
  (let [attachment (cond-> {:type "mesh" :name "mesh" :path "/image.png" :width 10.0 :height 10.0
                            :vertices [-5.0 -5.0 5.0 -5.0 -5.0 5.0]
                            :uvs [0.0 1.0 1.0 1.0 0.0 0.0] :triangles [0 1 2]}
                     weighted (assoc :weights [[{:bone "root" :weight 1.0 :x -5.0 :y -5.0}]
                                               [{:bone "root" :weight 1.0 :x 5.0 :y -5.0}]
                                               [{:bone "root" :weight 1.0 :x -5.0 :y 5.0}]]))]
    (-> (data/new-document "insertion")
        (data/add-slot {:id "mesh" :bone "root" :attachment attachment})
        (data/set-deform-keyframe "default" "mesh" 1.0 [900.0 500.0 700.0 400.0 500.0 300.0] "linear")
        (data/set-deform-keyframe "default" "mesh" 0.0 [0.0 0.0 0.0 0.0 0.0 0.0]
                                 {:type "bezier" :curve [0.1 0.8 0.9 0.2]}))))

;; De Casteljau splitting retains the cubic in absolute coordinates and keeps
;; both halves inside the new key intervals; invalid split endpoints are rejected.
(deftest absolute-bezier-split-test
  (let [{:keys [value left right]} (interpolation/split-bezier 0.0 0.0 1.0 1.0 [(/ 1.0 3.0) 0.25 (/ 2.0 3.0) 0.75] 0.5)]
    (is (near? 0.5 value))
    (is (coll/every? true? (mapv near? [(/ 1.0 6.0) 0.125 (/ 1.0 3.0) 0.3125] left)))
    (is (coll/every? true? (mapv near? [(/ 2.0 3.0) 0.6875 (/ 5.0 6.0) 0.875] right)))
    (is (thrown? clojure.lang.ExceptionInfo (interpolation/split-bezier 0.0 0.0 1.0 1.0 [0.1 0.8 0.9 0.2] 0.0)))
    (is (thrown? clojure.lang.ExceptionInfo (interpolation/split-bezier 0.0 0.0 1.0 1.0 [0.1 0.8 0.9 0.2] Double/NaN)))))

;; Mid-segment FFD insertion is legal for weighted and unweighted meshes and
;; preserves the displayed key values through native save and Spine JSON export.
(deftest deform-bezier-insertion-test
  (doseq [weighted [false true]]
    (let [doc (fixture weighted)
          time 0.233
          displayed (get-in (slots/sample-slots doc "default" time) [:deforms "mesh"])
          changed (data/set-deform-keyframe doc "default" "mesh" time displayed "linear")
          keys (get-in changed [:animations 0 :deforms 0 :keys])
          reopened (data/read-json (data/write-json changed))
          imported (data/import-spine-json (data/export-spine-json changed))]
      (is (= 3 (count keys)))
      (is (<= 0.0 (get-in keys [0 :curve 0]) (get-in keys [0 :curve 2]) time))
      (is (= displayed (get-in (slots/sample-slots changed "default" time) [:deforms "mesh"])))
      (is (= changed reopened))
      ;; Spine export intentionally moves hull vertices first, including their
      ;; FFD offsets. Compare the deformed geometry rather than vertex indices.
      (is (= (preview-points changed time) (preview-points imported time)))
      (is (= (get-in doc [:slots 0 :attachment :weights]) (get-in changed [:slots 0 :attachment :weights]))))))

;; Successive inserts split only the affected predecessor, while replacement
;; at an existing time preserves imported frame metadata and unrelated keys.
(deftest repeated-deform-insertion-and-metadata-test
  (let [doc (assoc-in (fixture false) [:animations 0 :deforms 0 :keys 0 :future] "preserved")
        insert (fn [doc time]
                 (data/set-deform-keyframe doc "default" "mesh" time
                                          (get-in (slots/sample-slots doc "default" time) [:deforms "mesh"]) "linear"))
        changed (-> doc (insert 0.233) (insert 0.1))
        replaced (data/set-deform-keyframe changed "default" "mesh" 0.0 [1 2 3 4 5 6] "stepped")
        keys (get-in changed [:animations 0 :deforms 0 :keys])]
    (is (= [0.0 0.1 0.233 1.0] (mapv :time keys)))
    (is (= "preserved" (:future (first keys))))
    (is (<= (get-in keys [0 :curve 2]) 0.1))
    (is (= "preserved" (get-in replaced [:animations 0 :deforms 0 :keys 0 :future])))
    (is (= (subvec keys 1) (subvec (get-in replaced [:animations 0 :deforms 0 :keys]) 1)))))

;; A non-flat easing returning to zero has no representable single FFD segment
;; with identical endpoints. Reject that split explicitly instead of erasing motion.
(deftest deform-return-to-zero-split-test
  (let [doc (data/set-deform-keyframe (fixture false) "default" "mesh" 0.0 [0 0 0 0 0 0]
                                     {:type "bezier" :curve [(/ 1.0 3.0) 1.0 (/ 2.0 3.0) (- (/ 4.0 3.0))]})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"return to its starting value"
                         (data/set-deform-keyframe doc "default" "mesh" 0.5 [0 0 0 0 0 0] "linear")))
    (is (= 2 (count (get-in doc [:animations 0 :deforms 0 :keys]))))))
