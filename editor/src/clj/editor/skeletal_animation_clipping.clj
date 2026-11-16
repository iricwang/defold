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

(ns editor.skeletal-animation-clipping
  "Independent concave polygon tessellation and UV-preserving mesh clipping."
  (:require [util.coll :as coll]))

(defn- cross [[ax ay] [bx by] [cx cy]]
  (- (* (- bx ax) (- cy ay)) (* (- by ay) (- cx ax))))

(defn- inside-triangle? [p a b c]
  (and (>= (cross a b p) -1.0e-9)
       (>= (cross b c p) -1.0e-9)
       (>= (cross c a p) -1.0e-9)))

(defn triangulate
  "Return triangle indices for a simple polygon, in either winding order.
  Repeated closing points are accepted. Degenerate inputs fail."
  [points]
  (let [points (if (and (> (count points) 1) (= (first points) (peek points))) (pop points) points)
        n (count points)
        _ (when-not (and (>= n 3)
                         (coll/every? #(and (= 2 (count %))
                                            (coll/every? (fn [v] (and (number? v) (Double/isFinite (double v)))) %)) points))
            (throw (ex-info "Clipping requires at least three finite XY vertices." {})))
        area (reduce + 0.0 (mapv (fn [i] (let [[x y] (points i) [nx ny] (points (mod (inc i) n))] (- (* x ny) (* nx y)))) (range n)))
        _ (when (< (abs area) 1.0e-10) (throw (ex-info "Clipping polygon has zero area." {})))
        indices (if (pos? area) (vec (range n)) (vec (reverse (range n))))]
    (loop [remaining indices triangles []]
      (if (= 3 (count remaining))
        (conj triangles remaining)
        (let [size (count remaining)
              ear (coll/first-where
                    (fn [i]
                      (let [a (remaining (mod (dec i) size)) b (remaining i) c (remaining (mod (inc i) size))]
                        (and (> (cross (points a) (points b) (points c)) 1.0e-10)
                             (coll/not-any? #(and (not (contains? #{a b c} %))
                                                  (inside-triangle? (points %) (points a) (points b) (points c))) remaining))))
                    (range size))]
          (if (nil? ear)
            (throw (ex-info "Clipping polygon must be simple and nondegenerate." {}))
            (let [triangle [(remaining (mod (dec ear) size)) (remaining ear) (remaining (mod (inc ear) size))]]
              (recur (into (subvec remaining 0 ear) (subvec remaining (inc ear))) (conj triangles triangle)))))))))

(defn- intersection [a b edge-a edge-b]
  (let [da (cross edge-a edge-b a) db (cross edge-a edge-b b)
        t (/ da (- da db))]
    (mapv (fn [x y] (+ x (* t (- y x)))) a b)))

(defn- clip-edge [vertices edge-a edge-b]
  (if (zero? (count vertices))
    []
    (:output
      (reduce (fn [{:keys [previous output]} current]
                (let [previous-in (>= (cross edge-a edge-b previous) -1.0e-9)
                      current-in (>= (cross edge-a edge-b current) -1.0e-9)]
                  {:previous current
                   :output (cond-> output
                             (not= previous-in current-in) (conj (intersection previous current edge-a edge-b))
                             current-in (conj current))}))
              {:previous (peek vertices) :output []} vertices))))

(defn- bounds [points]
  (reduce (fn [[min-x min-y max-x max-y] [x y]]
            [(min min-x x) (min min-y y) (max max-x x) (max max-y y)])
          [Double/POSITIVE_INFINITY Double/POSITIVE_INFINITY Double/NEGATIVE_INFINITY Double/NEGATIVE_INFINITY] points))

(defn clip-mesh
  "Intersect a textured mesh with a concave polygon, retaining interpolated UVs.
  Output remains a single batched mesh, with no per-triangle scene nodes."
  [slot polygon polygon-triangles]
  (let [vertices (mapv (fn [[x y] [u v]] [x y u v]) (:points slot) (:uvs slot))
        parts (mapv (fn [indices] (let [points (mapv polygon indices)] {:points points :bounds (bounds points)})) polygon-triangles)
        pieces (coll/into-> (:triangles slot) []
                 (mapcat (fn [indices]
                           (let [source (mapv vertices indices)
                                 [sx sy ex ey] (bounds source)]
                             (coll/into-> parts []
                               (keep (fn [{[cx cy dx dy] :bounds [a b c] :points}]
                                       (when (and (< sx dx) (< sy dy) (> ex cx) (> ey cy))
                                         (if (coll/every? #(inside-triangle? % a b c) source)
                                           source
                                           (-> source (clip-edge a b) (clip-edge b c) (clip-edge c a))))))
                               (filter #(>= (count %) 3)))))))]
    (reduce (fn [result piece]
              (let [offset (count (:points result))]
                (-> result
                    (update :points into (mapv #(subvec % 0 2) piece))
                    (update :uvs into (mapv #(subvec % 2 4) piece))
                    (update :triangles into (mapv #(vector offset (+ offset %) (+ offset % 1)) (range 1 (dec (count piece))))))))
            (assoc slot :points [] :uvs [] :triangles []) pieces)))

(defn clip-slots
  "Process Spine draw order. End slot is included; an absent end lasts to the
  final slot. Active clips ignore nested clips, matching Spine 4.2. Marker-only
  slots still terminate a clip even when they have no visible attachment."
  [slots]
  (:result
    (reduce (fn [{:keys [polygon triangles end result] :as state} slot]
              (let [clip (:clip-polygon slot)
                    state (cond
                            (and clip (nil? polygon))
                            ;; Animated clips can collapse at scale zero.
                            (assoc state :polygon clip :triangles (try (triangulate clip)
                                                                       (catch clojure.lang.ExceptionInfo _ [])) :end (:clip-end slot))

                            (and (not clip) (pos? (count (:triangles slot))))
                            (assoc state :result (conj result (if polygon (clip-mesh slot polygon triangles) slot)))

                            :else state)]
                (if (and (not clip) (= (:id slot) end)) (dissoc state :polygon :triangles :end) state)))
            {:result []} slots)))
