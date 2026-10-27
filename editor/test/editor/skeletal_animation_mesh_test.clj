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

(ns editor.skeletal-animation-mesh-test
  (:require [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-geometry :as geometry]
            [editor.skeletal-animation-mesh :as mesh]
            [util.coll :as coll]))

(def square {:type "mesh" :path "/image.png" :vertices [0.0 0.0 10.0 0.0 10.0 10.0 0.0 10.0]
             :uvs [0.0 1.0 1.0 1.0 1.0 0.0 0.0 0.0] :triangles [0 1 2 0 2 3]})

(def context {:slot-transform geometry/identity-transform
              :bone-transforms {"a" geometry/identity-transform "b" [1.0 0.0 0.0 1.0 10.0 0.0] "c" [1.0 0.0 0.0 1.0 0.0 20.0]}
              :bones [{:id "a" :length 10.0} {:id "b" :length 10.0} {:id "c" :length 10.0}]})

(defn- weighted-square [a-weights]
  (assoc square :weights (mapv (fn [index]
                                 (let [x (get (:vertices square) (* 2 index)) y (get (:vertices square) (inc (* 2 index))) a (a-weights index)]
                                   [{:bone "a" :weight a :x x :y y} {:bone "b" :weight (- 1.0 a) :x (- x 10.0) :y y}])) (range 4))))

(defn- close? [expected actual]
  (< (abs (double (- expected actual))) 1.0e-7))

(defn- coordinates-close? [expected actual]
  (and (= (count expected) (count actual)) (coll/every? true? (mapv close? expected actual))))

(defn- weights-by-bone [attachment index]
  (into {} (map (juxt :bone :weight)) (get (:weights attachment) index)))

(defn- triangle-area [attachment]
  (let [points (into [] (partition-all 2) (:vertices attachment))]
    (transduce (map (fn [[a b c]]
                      (let [[ax ay] (points a) [bx by] (points b) [cx cy] (points c)]
                        (* 0.5 (abs (double (- (* (- bx ax) (- cy ay)) (* (- by ay) (- cx ax)))))))))
               + 0.0 (into [] (partition-all 3) (:triangles attachment)))))

;; Picking, rectangle selection and modifier selection remain deterministic,
;; and invalid indices cannot leak into later mesh or graph operations.
(deftest selection-test
  (is (= #{0 1} (mesh/vertices-in-rect square [11.0 1.0 -1.0 -1.0])))
  (is (= 0 (mesh/nearest-vertex square [5.0 0.0] 5.0)))
  (is (nil? (mesh/nearest-vertex square [20.0 20.0] 1.0)))
  (is (= #{0 1} (mesh/select-vertices square #{0} #{1} :add)))
  (is (= #{1} (mesh/select-vertices square #{0 1} #{0} :remove)))
  (is (= #{0 2} (mesh/select-vertices square #{0 1} #{1 2} :toggle)))
  (is (= #{3} (mesh/select-vertices square #{0 1} #{3} :replace)))
  (is (thrown? clojure.lang.ExceptionInfo (mesh/select-vertices square #{} #{5} :add))))

;; Selected vertices move together in local space. Weighted bind coordinates
;; move by the same setup-world displacement, while UV and FFD identity remain.
(deftest transform-selection-test
  (let [before (weighted-square [0.75 0.75 0.75 0.75])
        edit (mesh/transform-selected before #{0 2} [1.0 0.0 0.0 1.0 3.0 -2.0] context)
        after (:mesh edit)]
    (is (= [3.0 -2.0 10.0 0.0 13.0 8.0 0.0 10.0] (:vertices after)))
    (is (= (:uvs before) (:uvs after)))
    (is (= #{0 2} (:selection edit)))
    (is (= :identity (:deform-policy edit)))
    (is (coordinates-close? (:vertices after) (geometry/mesh-world-vertices after geometry/identity-transform (:bone-transforms context) [])))
    (is (= (mapv :weight (get (:weights before) 0)) (mapv :weight (get (:weights after) 0))))))

;; Ear clipping handles concave/reversed boundaries, then inserts interior
;; points. Crossed outlines are rejected instead of generating corrupt faces.
(deftest triangulation-test
  (let [concave (assoc square :vertices [0.0 0.0 4.0 0.0 4.0 4.0 2.0 2.0 0.0 4.0]
                             :uvs [0.0 0.0 1.0 0.0 1.0 1.0 0.5 0.5 0.0 1.0] :triangles [])
        a (:mesh (mesh/triangulate concave [0 1 2 3 4]))
        b (:mesh (mesh/triangulate concave [4 3 2 1 0]))
        interior (assoc square :vertices (conj (:vertices square) 5.0 5.0) :uvs (conj (:uvs square) 0.5 0.5) :triangles [])
        c (:mesh (mesh/triangulate interior [0 1 2 3]))]
    (is (= 9 (count (:triangles a))))
    (is (close? 12.0 (triangle-area a)))
    (is (close? 12.0 (triangle-area b)))
    (is (= 12 (count (:triangles c))))
    (is (close? 100.0 (triangle-area c)))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/triangulate square [0 2 1 3])))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/triangulate square [0 1 1 2])))))

;; Edge splitting updates every adjacent face, interpolates UVs, and provides
;; the exact original-vertex coefficients needed to migrate deformation keys.
(deftest split-edge-test
  (let [boundary (mesh/split-edge square [0 1] 0.25)
        shared (mesh/split-edge square [0 2] 0.5)]
    (is (= [2.5 0.0] (subvec (:vertices (:mesh boundary)) 8)))
    (is (= [0.25 1.0] (subvec (:uvs (:mesh boundary)) 8)))
    (is (= 9 (count (:triangles (:mesh boundary)))))
    (is (= 12 (count (:triangles (:mesh shared)))))
    (is (close? 100.0 (triangle-area (:mesh shared))))
    (is (= {0 0.5 2 0.5} (peek (:vertex-map shared))))
    (is (= #{4} (:selection shared)))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/split-edge square [1 3] 0.5)))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/split-edge square [0 1] 0.0)))))

;; Free vertex creation splits a face or its shared edge without changing the
;; mesh silhouette; existing positions and points outside the mesh are rejected.
(deftest insert-vertex-test
  (let [inside (:mesh (mesh/insert-vertex square [7.5 2.5]))
        edge (:mesh (mesh/insert-vertex square [5.0 5.0]))]
    (is (= [7.5 2.5] (subvec (:vertices inside) 8)))
    (is (= [0.75 0.75] (subvec (:uvs inside) 8)))
    (is (= 12 (count (:triangles inside))))
    (is (= 12 (count (:triangles edge))))
    (is (close? 100.0 (triangle-area inside)))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/insert-vertex square [0.0 0.0])))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/insert-vertex square [15.0 15.0])))))

;; Deletion repairs the local triangle fan and reindexes every surviving
;; vertex/UV. Disconnected fans and meshes with fewer than three points fail.
(deftest delete-vertices-test
  (let [with-center (:mesh (mesh/split-edge square [0 2] 0.5))
        interior (:mesh (mesh/delete-vertices with-center #{4}))
        boundary (mesh/delete-vertices square #{1})
        grid (geometry/region-to-mesh {:width 20.0 :height 20.0} 2 2)
        multiple (:mesh (mesh/delete-vertices grid #{1 4}))]
    (is (= (:vertices square) (:vertices interior)))
    (is (close? 100.0 (triangle-area interior)))
    (is (= [0.0 0.0 10.0 10.0 0.0 10.0] (:vertices (:mesh boundary))))
    (is (= [{0 1.0} {2 1.0} {3 1.0}] (:vertex-map boundary)))
    (is (close? 50.0 (triangle-area (:mesh boundary))))
    (is (= 14 (count (:vertices multiple))))
    (is (close? 400.0 (triangle-area multiple)))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/delete-vertices square #{0 1})))
    (let [disconnected (assoc with-center :triangles [0 1 2 0 3 4])]
      (is (thrown? clojure.lang.ExceptionInfo (mesh/delete-vertices disconnected #{0}))))))

;; Explicit UV projection uses image bounds, including Y inversion, while
;; preserving topology and rejecting zero-size projection rectangles.
(deftest rebuild-uvs-test
  (is (= (:uvs square) (:uvs (:mesh (mesh/rebuild-uvs square nil true)))))
  (is (= [0.0 1.0 0.5 1.0 0.5 0.5 0.0 0.5] (:uvs (:mesh (mesh/rebuild-uvs square [0.0 0.0 20.0 20.0] true)))))
  (is (= (:triangles square) (:triangles (:mesh (mesh/rebuild-uvs square nil false)))))
  (is (thrown? clojure.lang.ExceptionInfo (mesh/rebuild-uvs square [0.0 0.0 0.0 20.0] true))))

;; Unweighted FFD follows the vertex map through both creation and deletion,
;; so topology edits do not discard keys or transfer offsets to wrong indices.
(deftest unweighted-deform-migration-test
  (let [offsets [0.0 1.0 2.0 3.0 4.0 5.0 6.0 7.0]
        inserted (mesh/split-edge square [0 2] 0.5)
        removed (mesh/delete-vertices square #{1})]
    (is (= (conj offsets 2.0 3.0) (mesh/remap-deform square (:mesh inserted) (:vertex-map inserted) offsets)))
    (is (= [0.0 1.0 4.0 5.0 6.0 7.0] (mesh/remap-deform square (:mesh removed) (:vertex-map removed) offsets)))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/remap-deform square (:mesh inserted) (:vertex-map inserted) [0.0 0.0])))))

;; Weighted FFD is averaged per bone using the original influence weights.
;; The inserted point must match the interpolated original under multiple poses.
(deftest weighted-deform-migration-test
  (let [before (weighted-square [0.8 0.75 0.2 0.5])
        offsets [1.0 2.0 3.0 4.0 0.0 0.0 0.0 0.0 5.0 6.0 7.0 8.0 0.0 0.0 0.0 0.0]
        edit (mesh/split-edge before [0 2] 0.5) after (:mesh edit)
        migrated (mesh/remap-deform before after (:vertex-map edit) offsets)]
    (is (= {"a" 0.5 "b" 0.5} (weights-by-bone after 4)))
    (is (= 20 (count migrated)))
    (is (coordinates-close? [1.8 2.8 6.2 7.2] (subvec migrated 16)))
    (doseq [transforms [(:bone-transforms context)
                       {"a" [0.0 1.0 -1.0 0.0 3.0 5.0] "b" [-2.0 0.0 0.0 0.5 20.0 -4.0]}]]
      (let [original (geometry/mesh-world-vertices before geometry/identity-transform transforms offsets)
            updated (geometry/mesh-world-vertices after geometry/identity-transform transforms migrated)]
        (is (coordinates-close? [(* 0.5 (+ (original 0) (original 4))) (* 0.5 (+ (original 1) (original 5)))] (subvec updated 8)))))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/remap-deform before (assoc-in after [:weights 4 0 :weight] 0.7) (:vertex-map edit) offsets)))))

;; Zero-weight influence channels remain addressable after deleting an earlier
;; vertex, ready for later reweighting rather than losing their authored FFD.
(deftest zero-weight-deform-migration-test
  (let [before (weighted-square [1.0 0.5 0.0 0.5])
        offsets (mapv double (range 16))
        edit (mesh/delete-vertices before #{1})]
    (is (= [0.0 1.0 2.0 3.0 8.0 9.0 10.0 11.0 12.0 13.0 14.0 15.0]
           (mesh/remap-deform before (:mesh edit) (:vertex-map edit) offsets)))))

;; Normalization preserves locked weights exactly, including their bind points,
;; and refuses constraints that cannot be satisfied without unlocking a bone.
(deftest normalize-locked-test
  (let [raw (assoc-in (weighted-square [0.2 0.2 0.2 0.2]) [:weights 0] [{:bone "a" :weight 0.2 :x 0.0 :y 0.0}
                                                                                  {:bone "b" :weight 0.4 :x -10.0 :y 0.0}
                                                                                  {:bone "c" :weight 0.6 :x 0.0 :y -20.0}])
        after (:mesh (mesh/normalize-weights raw #{0} #{"a"}))]
    (is (= (get-in raw [:weights 0 0]) (get-in after [:weights 0 0])))
    (is (coordinates-close? [0.2 0.32 0.48] (mapv :weight (get (:weights after) 0))))
    (is (= (get (:weights raw) 1) (get (:weights after) 1)))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/normalize-weights (assoc-in raw [:weights 0 0 :weight] 1.2) #{0} #{"a"})))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/normalize-weights (assoc-in raw [:weights 0] [{:bone "a" :weight 0.7 :x 0.0 :y 0.0}]) #{0} #{"a"}))))
  (let [zeroes (assoc-in (weighted-square [0.0 0.0 0.0 0.0]) [:weights 0 1 :weight] 0.0)]
    (is (= {"a" 1.0 "b" 0.0} (weights-by-bone (:mesh (mesh/normalize-weights zeroes #{0} #{})) 0)))))

;; Automatic binding uses distance to bone segments, keeps setup-world geometry
;; unchanged, honors influence caps, and protects pre-existing locked bindings.
(deftest automatic-binding-test
  (let [after (:mesh (mesh/bind-weights square ["a" "b"] context {:max-influences 2}))
        before (weighted-square [0.75 0.75 0.75 0.75])
        locked (:mesh (mesh/bind-weights before ["a" "b" "c"] context {:max-influences 2 :locked-bones #{"a"}}))]
    (is (> (get (weights-by-bone after 0) "a") 0.99))
    (is (coll/every? #(close? 1.0 (reduce + (mapv :weight %))) (:weights after)))
    (is (coordinates-close? (:vertices square) (geometry/mesh-world-vertices after geometry/identity-transform (:bone-transforms context) [])))
    (is (coll/every? #(<= (count %) 2) (:weights locked)))
    (is (= (mapv first (:weights before)) (mapv first (:weights locked))))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/bind-weights square [] context {})))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/bind-weights square ["a"] (assoc-in context [:bone-transforms "a"] [0.0 0.0 0.0 0.0 0.0 0.0]) {})))))

;; Direct assignment respects every locked bone, clamps to the available free
;; budget, and initializes a newly introduced bone at the correct bind position.
(deftest direct-weight-assignment-test
  (let [before (weighted-square [0.75 0.75 0.75 0.75])
        after (:mesh (mesh/set-weights before #{0} "c" 0.2 context #{"a"}))]
    (is (coordinates-close? [0.75 0.05 0.2] (mapv :weight (get (:weights after) 0))))
    (is (= (get-in before [:weights 0 0]) (get-in after [:weights 0 0])))
    (is (= {:bone "c" :weight 0.2 :x 0.0 :y -20.0} (get-in after [:weights 0 2])))
    (is (= before (:mesh (mesh/set-weights before #{0} "a" 0.0 context #{"a"}))))
    (is (close? 0.25 (get (weights-by-bone (:mesh (mesh/set-weights before #{0} "b" 1.0 context #{"a"})) 0) "b")))))

;; Brush modes share radial falloff while keeping points outside the radius
;; untouched; replacing and subtracting weights retain a normalized budget.
(deftest brush-modes-test
  (let [before (weighted-square [0.75 0.75 0.75 0.75])
        options {:center [0.0 0.0] :radius 20.0 :strength 0.1 :bone-id "b" :falloff 2.0}
        add (:mesh (mesh/brush-weights before (assoc options :mode :add) context))
        subtract (:mesh (mesh/brush-weights before (assoc options :mode :subtract :radius 1.0) context))
        replace (:mesh (mesh/brush-weights before (assoc options :mode :replace :strength 0.8 :radius 1.0) context))]
    (is (close? 0.35 (get (weights-by-bone add 0) "b")))
    (is (close? 0.275 (get (weights-by-bone add 1) "b")))
    (is (close? 0.15 (get (weights-by-bone subtract 0) "b")))
    (is (= (get (:weights before) 1) (get (:weights subtract) 1)))
    (is (close? 0.8 (get (weights-by-bone replace 0) "b")))
    (is (= before (:mesh (mesh/brush-weights before (assoc options :locked-bones #{"b"}) context))))
    (is (= before (:mesh (mesh/brush-weights before (assoc options :mode :subtract :bone-id "c") context))))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/brush-weights before (assoc options :radius 0.0) context)))))

;; Numeric edge cases cannot create invalid bindings, and malformed triangle
;; indices are rejected without preventing temporary collinear vertex editing.
(deftest malformed-input-test
  (is (thrown? clojure.lang.ExceptionInfo (mesh/insert-vertex (assoc square :triangles [0 0 1]) [1.0 1.0])))
  (is (thrown? clojure.lang.ExceptionInfo (mesh/transform-selected square #{0} [1.0 0.0 0.0 1.0 ##NaN 0.0] context)))
  (is (thrown? clojure.lang.ExceptionInfo (mesh/bind-weights square ["a"] (assoc context :slot-transform [1.0e308 0.0 0.0 1.0 0.0 0.0]) {})))
  (let [collinear (assoc square :vertices [0.0 0.0 10.0 0.0 20.0 0.0 0.0 10.0])]
    (is (= #{2} (:selection (mesh/transform-selected collinear #{2} [1.0 0.0 0.0 1.0 0.0 1.0] context))))))

;; Smoothing uses the original neighboring vertices for a simultaneous update;
;; locked influences remain exact even when their neighbors have different rigs.
(deftest smoothing-test
  (let [before (weighted-square [1.0 0.0 0.0 0.0])
        after (:mesh (mesh/smooth-weights before #{0} 1.0 context #{}))
        locked (:mesh (mesh/smooth-weights before #{0} 1.0 context #{"a"}))
        brush (:mesh (mesh/brush-weights before {:mode :smooth :center [0.0 0.0] :radius 1.0 :strength 1.0} context))]
    (is (= {"a" 0.0 "b" 1.0} (weights-by-bone after 0)))
    (is (= (get-in before [:weights 0 0]) (get-in locked [:weights 0 0])))
    (is (= (get (:weights after) 0) (get (:weights brush) 0)))
    (is (= (:mesh (mesh/smooth-weights before [0 1] 0.5 context #{}))
           (:mesh (mesh/smooth-weights before [1 0] 0.5 context #{}))))))

;; Pruning removes tiny or excess unlocked influences and renormalizes only
;; the free budget; an impossible limit never silently removes a locked bone.
(deftest pruning-test
  (let [before (assoc-in (weighted-square [0.6 0.6 0.6 0.6]) [:weights 0]
                         [{:bone "a" :weight 0.6 :x 0.0 :y 0.0} {:bone "b" :weight 0.39 :x -10.0 :y 0.0} {:bone "c" :weight 0.01 :x 0.0 :y -20.0}])
        after (:mesh (mesh/prune-weights before #{0} {:threshold 0.02 :max-influences 2}))
        locked (:mesh (mesh/prune-weights before #{0} {:threshold 0.02 :max-influences 2 :locked-bones #{"c"}}))]
    (is (= #{"a" "b"} (set (coll/keys (weights-by-bone after 0)))))
    (is (close? (/ 0.6 0.99) (get (weights-by-bone after 0) "a")))
    (is (= {"c" 0.01 "a" 0.99} (weights-by-bone locked 0)))
    (is (= (get-in before [:weights 0 2]) (get-in locked [:weights 0 0])))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/prune-weights before #{0} {:max-influences 1 :locked-bones #{"a" "c"}})))))

(defn- mesh-edges [attachment]
  (into #{} (mapcat (fn [[a b c]] [#{a b} #{b c} #{c a}])) (into [] (partition-all 3) (:triangles attachment))))

;; Connecting opposite vertices replaces the crossed triangle corridor without
;; changing vertices, UVs, area or FFD mapping, and records the new constraint.
(deftest connect-vertices-test
  (let [edit (mesh/connect-vertices square [1 3]) after (:mesh edit)
        grid (geometry/region-to-mesh {:width 20.0 :height 20.0} 2 2)
        corridor (:mesh (mesh/connect-vertices grid [0 5]))
        chain (:mesh (mesh/connect-vertices grid [0 8]))]
    (is (contains? (mesh-edges after) #{1 3}))
    (is (not (contains? (mesh-edges after) #{0 2})))
    (is (= [[1 3]] (:constrained-edges after)))
    (is (= (:vertices square) (:vertices after)))
    (is (= (:uvs square) (:uvs after)))
    (is (= :identity (:deform-policy edit)))
    (is (close? 100.0 (triangle-area after)))
    (is (contains? (mesh-edges corridor) #{0 5}))
    (is (close? 400.0 (triangle-area corridor)))
    (is (= [[0 4] [4 8]] (:constrained-edges chain)))
    (is (contains? (mesh-edges (:mesh (mesh/connect-vertices (update square :vertices #(mapv (fn [v] (* v 0.001)) %)) [1 3]))) #{1 3}))))

;; Existing constraints cannot be crossed; disconnect removes the constraint
;; only, while edge splits and vertex deletion keep its surviving index mapping.
(deftest constrained-edge-lifecycle-test
  (let [protected (:mesh (mesh/connect-vertices square [0 2]))
        released (:mesh (mesh/disconnect-vertices protected [2 0]))
        split (:mesh (mesh/split-edge protected [0 2] 0.5))
        removed (:mesh (mesh/delete-vertices split #{4}))
        retriangulated (:mesh (mesh/triangulate (:mesh (mesh/connect-vertices square [1 3])) [0 1 2 3]))]
    (is (thrown? clojure.lang.ExceptionInfo (mesh/connect-vertices protected [1 3])))
    (is (= (:triangles protected) (:triangles released)))
    (is (= [] (:constrained-edges released)))
    (is (contains? (mesh-edges (:mesh (mesh/connect-vertices released [1 3]))) #{1 3}))
    (is (= #{#{0 4} #{2 4}} (into #{} (map set) (:constrained-edges split))))
    (is (= [] (:constrained-edges removed)))
    (is (contains? (mesh-edges retriangulated) #{1 3}))
    (is (= [[1 3]] (:constrained-edges retriangulated)))))

;; A connection cannot cut across an existing hole or operate on a shared edge
;; with more than two incident faces, even when both endpoints are valid.
(deftest connect-hole-and-nonmanifold-test
  (let [ring {:vertices [0.0 0.0 10.0 0.0 10.0 10.0 0.0 10.0 3.0 3.0 7.0 3.0 7.0 7.0 3.0 7.0]
              :uvs (vec (repeat 16 0.0))
              :triangles [0 1 5 0 5 4 1 2 6 1 6 5 2 3 7 2 7 6 3 0 4 3 4 7]}]
    (is (thrown? clojure.lang.ExceptionInfo (mesh/connect-vertices ring [4 6])))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/connect-vertices ring [0 2])))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/triangulate ring [0 1 2 3])))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/connect-vertices (update square :triangles into [0 2 1]) [1 3])))))

;; Mirroring transfers weights from an immutable source snapshot, swaps bone
;; IDs, reconstructs target bind coordinates and preserves custom metadata.
(deftest mirror-weights-test
  (let [before (-> (weighted-square [0.8 0.25 0.4 0.7])
                   (assoc :custom-metadata {:keep true})
                   (assoc-in [:weights 0 0 :artist-note] "keep"))
        edit (mesh/mirror-weights before #{0 1} :x 5.0 {"a" "b" "b" "a"} context)
        after (:mesh edit)
        locked (:mesh (mesh/mirror-weights before #{0} :x 5.0 {"a" "b" "b" "a"} (assoc context :locked-bones #{"a"})))
        fully-locked (:mesh (mesh/mirror-weights before #{0} :x 5.0 {"a" "b" "b" "a"} (assoc context :locked-bones #{"a" "b"})))]
    (is (close? 0.75 (get (weights-by-bone after 0) "a")))
    (is (close? 0.2 (get (weights-by-bone after 1) "a")))
    (is (= #{0 1} (:selection edit)))
    (is (= (:custom-metadata before) (:custom-metadata after)))
    (is (= "keep" (:artist-note (coll/first-where #(= "b" (:bone %)) (get (:weights after) 1)))))
    (is (coordinates-close? (:vertices square) (geometry/mesh-world-vertices after geometry/identity-transform (:bone-transforms context) [])))
    (is (= (get-in before [:weights 1 0]) (get-in locked [:weights 1 0])))
    (is (= before fully-locked))))

;; Ambiguous symmetry or bone mappings are refused before returning an edit,
;; rather than mirroring onto a random point or losing influence metadata.
(deftest mirror-rejection-test
  (let [before (weighted-square [0.8 0.25 0.4 0.7])]
    (is (thrown? clojure.lang.ExceptionInfo (mesh/mirror-weights before #{0} :x 100.0 {} context)))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/mirror-weights (assoc-in before [:vertices 5] 0.0) #{0} :x 5.0 {} context)))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/mirror-weights before #{0} :x 5.0 {"a" "b"} context)))))

;; Spine hull metadata refers to the first vertices. Appended boundary points
;; must move before interior points without losing UVs, constraints or faces.
(deftest spine-export-layout-test
  (let [interior (:mesh (mesh/insert-vertex square [5.0 5.0]))
        split (:mesh (mesh/split-edge interior [0 1] 0.5))
        before (:mesh (mesh/connect-vertices split [0 4]))
        layout (mesh/spine-export-layout (assoc before :custom-metadata {:preserved true}))
        after (:mesh layout)
        order [0 3 2 1 5 4]]
    (is (= 5 (:hull layout)))
    (is (= (mapv #(hash-map % 1.0) order) (:vertex-map layout)))
    (is (= (into [] (mapcat (into [] (partition-all 2) (:vertices before))) order) (:vertices after)))
    (is (= (into [] (mapcat (into [] (partition-all 2) (:uvs before))) order) (:uvs after)))
    (is (= [[0 5]] (:constrained-edges after)))
    (is (= [0 2 0 8 0 10 2 4 4 6 6 8] (:edges layout)))
    (is (close? (triangle-area before) (triangle-area after)))
    (is (= {:preserved true} (:custom-metadata after)))))

;; The same hull permutation must reorder complete unweighted FFD frames;
;; export consumers may not reuse the old frame order after vertex reordering.
(deftest spine-export-unweighted-deform-test
  (let [before (:mesh (mesh/split-edge (:mesh (mesh/insert-vertex square [5.0 5.0])) [0 1] 0.5))
        layout (mesh/spine-export-layout before)
        offsets [1.0 2.0 3.0 4.0 5.0 6.0 7.0 8.0 9.0 10.0 11.0 12.0]]
    (is (= [1.0 2.0 7.0 8.0 5.0 6.0 3.0 4.0 11.0 12.0 9.0 10.0]
           (mesh/remap-deform before (:mesh layout) (:vertex-map layout) offsets)))))

;; Weighted FFD channels follow influence order as well as the vertex order.
;; Hull reordering must preserve the deformed result under arbitrary bone poses.
(deftest spine-export-weighted-deform-test
  (let [before (:mesh (mesh/split-edge (weighted-square [1.0 0.0 0.4 0.8]) [0 1] 0.5))
        layout (mesh/spine-export-layout before)
        after (:mesh layout)
        offsets (mapv double (range (* 2 (reduce + (mapv count (:weights before))))))
        remapped (mesh/remap-deform before after (:vertex-map layout) offsets)
        poses {"a" [0.0 1.5 -1.0 0.0 20.0 40.0] "b" [-1.0 0.5 0.2 2.0 -30.0 10.0]}
        old-world (into [] (partition-all 2) (geometry/mesh-world-vertices before geometry/identity-transform poses offsets))
        order (mapv ffirst (:vertex-map layout))]
    (is (= (mapv (:weights before) order) (:weights after)))
    (is (coordinates-close? (into [] (mapcat old-world) order)
                            (geometry/mesh-world-vertices after geometry/identity-transform poses remapped)))
    (is (= (count offsets) (count remapped)))))

;; A malformed or holed surface cannot be represented by a single Spine hull.
;; Export refuses it instead of retaining stale source metadata or filling holes.
(deftest spine-export-invalid-topology-test
  (let [ring {:vertices [0.0 0.0 10.0 0.0 10.0 10.0 0.0 10.0 3.0 3.0 7.0 3.0 7.0 7.0 3.0 7.0]
              :uvs [0.0 0.0 1.0 0.0 1.0 1.0 0.0 1.0 0.3 0.3 0.7 0.3 0.7 0.7 0.3 0.7]
              :triangles [0 1 5 0 5 4 1 2 6 1 6 5 2 3 7 2 7 6 3 0 4 3 4 7]}]
    (is (thrown? clojure.lang.ExceptionInfo (mesh/spine-export-layout ring)))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/spine-export-layout (update square :triangles into [0 2 1]))))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/spine-export-layout (assoc square :constrained-edges [[1 3]]))))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/spine-export-layout (assoc square :uvs [0.0 0.0 1.0 1.0 0.0 1.0 1.0 0.0]))))))

;; Generate refines a rectangular mesh into increasingly dense grids without
;; moving old vertices, retaining manual edges but explicitly resetting FFD.
(deftest generate-test
  (let [before (assoc (weighted-square [0.5 0.5 0.5 0.5]) :constrained-edges [[0 2]])
        first-edit (mesh/generate before {})
        generated (:mesh first-edit)
        second (:mesh (mesh/generate generated {}))
        two (:mesh (mesh/generate before {:iterations 2}))]
    (is (= 9 (quot (count (:vertices generated)) 2)))
    (is (= 25 (quot (count (:vertices second)) 2)))
    (is (= second two))
    (is (= (:vertices square) (subvec (:vertices generated) 0 8)))
    (is (= (:uvs square) (subvec (:uvs generated) 0 8)))
    (is (close? 100.0 (triangle-area second)))
    (is (= 2 (count (:constrained-edges generated))))
    (is (nil? (:weights generated)))
    (is (= :reset (:deform-policy first-edit)))
    (is (true? (:clear-deforms first-edit)))
    (is (true? (:clear-weights first-edit)))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/generate square {:iterations 2 :max-vertices 16})))))

;; Reset means four image corners, not an inferred bounding box of deformed
;; geometry. Weights/manual topology disappear while attachment metadata stays.
(deftest reset-vertices-test
  (let [edit (mesh/reset-vertices (assoc (weighted-square [0.5 0.5 0.5 0.5]) :custom "keep") [-20.0 -10.0 20.0 10.0])
        reset (:mesh edit)]
    (is (= [-20.0 -10.0 20.0 -10.0 20.0 10.0 -20.0 10.0] (:vertices reset)))
    (is (= [0.0 1.0 1.0 1.0 1.0 0.0 0.0 0.0] (:uvs reset)))
    (is (= "keep" (:custom reset)))
    (is (nil? (:weights reset)))
    (is (= [] (:vertex-map edit)))
    (is (= :reset (:deform-policy edit)))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/reset-vertices square [0.0 0.0 0.0 1.0])))))

;; Trace follows the actual concave alpha contour instead of its convex hull.
;; UVs map top-left pixels into the requested local Y-up image rectangle.
(deftest trace-concave-test
  (let [image {:width 4 :height 4 :alpha [255 255 255 255
                                        255 255 0 0
                                        255 255 0 0
                                        255 255 0 0]}
        edit (mesh/trace-alpha square image {:simplify 0.0 :bounds [0.0 0.0 4.0 4.0]})
        traced (:mesh edit)
        points (into #{} (partition-all 2) (:vertices traced))]
    (is (= 6 (count points)))
    (is (contains? points [2.0 3.0]))
    (is (close? 10.0 (triangle-area traced)))
    (is (= 12 (count (:triangles traced))))
    (is (= :reset (:deform-policy edit)))
    (is (coll/every? #(<= 0.0 % 1.0) (:uvs traced)))
    (is (= 6 (count (into #{} (partition-all 2) (:vertices (:mesh (mesh/trace-alpha square image {:simplify 0.5})))))))))

;; Alpha threshold includes partially opaque pixels only when requested.
;; Unsigned byte input matches vector input without requiring JavaFX images.
(deftest trace-threshold-and-bytes-test
  (let [alpha [255 8 0 0]
        vector-image {:width 2 :height 2 :alpha alpha}
        byte-image (assoc vector-image :alpha (byte-array (mapv unchecked-byte alpha)))
        options {:threshold 16 :simplify 0.0 :bounds [0.0 0.0 2.0 2.0]}
        traced (:mesh (mesh/trace-alpha square vector-image options))]
    (is (close? 1.0 (triangle-area traced)))
    (is (= traced (:mesh (mesh/trace-alpha square byte-image options))))
    (is (close? 2.0 (triangle-area (:mesh (mesh/trace-alpha square vector-image (assoc options :threshold 1))))))))

;; Padding is a clipped pixel dilation. Simplification expands small concave
;; steps conservatively, keeping every original opaque pixel in the mesh.
(deftest trace-padding-and-simplification-test
  (let [image {:width 3 :height 3 :alpha [0 0 0 0 255 0 0 0 0]}
        options {:simplify 0.0 :bounds [0.0 0.0 3.0 3.0]}
        unpadded (:mesh (mesh/trace-alpha square image options))
        padded (:mesh (mesh/trace-alpha square image (assoc options :padding 1)))
        step {:width 3 :height 3 :alpha [255 255 255 255 255 0 255 0 0]}
        detailed (:mesh (mesh/trace-alpha square step {:simplify 0.0}))
        simplified (:mesh (mesh/trace-alpha square step {:simplify 0.8}))]
    (is (close? 1.0 (triangle-area unpadded)))
    (is (close? 9.0 (triangle-area padded)))
    (is (coll/every? #(<= 0.0 % 1.0) (:uvs padded)))
    (is (< (count (:vertices simplified)) (count (:vertices detailed))))
    (is (>= (triangle-area simplified) (triangle-area detailed)))
    (is (= simplified (:mesh (mesh/trace-alpha square step {:simplify 0.8}))))))

;; Empty alpha, multiple islands, true holes and corner-touching contours are
;; explicit errors. Padding must not silently merge islands or fill a hole.
(deftest trace-unsupported-topology-test
  (let [transparent {:width 2 :height 2 :alpha [0 0 0 0]}
        islands {:width 3 :height 1 :alpha [255 0 255]}
        ring {:width 3 :height 3 :alpha [255 255 255 255 0 255 255 255 255]}
        touching {:width 2 :height 2 :alpha [255 0 0 255]}]
    (doseq [options [{} {:threshold 0}]]
      (is (thrown? clojure.lang.ExceptionInfo (mesh/trace-alpha square transparent options))))
    (doseq [options [{} {:padding 3}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"separate opaque regions" (mesh/trace-alpha square islands options)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot contain holes" (mesh/trace-alpha square ring options))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"touches itself" (mesh/trace-alpha square touching {})))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/trace-alpha square (assoc islands :alpha [255]) {})))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/trace-alpha square islands {:threshold 256})))
    (is (thrown? clojure.lang.ExceptionInfo (mesh/trace-alpha square islands {:padding -1})))))
