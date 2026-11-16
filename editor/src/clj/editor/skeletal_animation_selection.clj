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

(ns editor.skeletal-animation-selection
  "Texture-aware picking and reusable authoring selection outlines."
  (:require [util.coll :as coll])
  (:import [javafx.scene.image Image PixelReader]))

(set! *warn-on-reflection* true)

(defn- barycentric [[x y] [[ax ay] [bx by] [cx cy]]]
  (let [denominator (- (* (- by cy) (- ax cx)) (* (- bx cx) (- ay cy)))]
    (when (> (abs (double denominator)) 1.0e-12)
      (let [a (/ (+ (* (- by cy) (- x cx)) (* (- cx bx) (- y cy))) denominator)
            b (/ (+ (* (- cy ay) (- x cx)) (* (- ax cx) (- y cy))) denominator)]
        [a b (- 1.0 a b)]))))

(defn- inside? [weights]
  (and weights (coll/every? #(<= -1.0e-7 % 1.0000001) weights)))

(defn- interpolate [weights points]
  (reduce (fn [result [weight point]] (mapv + result (mapv #(* weight %) point)))
          [0.0 0.0] (mapv vector weights points)))

(defn pick-slot
  "Pick the frontmost visible pixel from a clipped render plan."
  [plan position]
  (when-let [slot
             (coll/first-where
               (fn [{:keys [points uvs triangles ^Image image opacity]}]
                 (and (> (double opacity) 0.01)
                      (coll/any?
                        (fn [indices]
                          (let [weights (barycentric position (mapv points indices))]
                            (and (inside? weights)
                                 (if-let [reader (when image (.getPixelReader image))]
                                   (let [[u v] (interpolate weights (mapv uvs indices))
                                         x (int (max 0 (min (dec (.getWidth image)) (* u (.getWidth image)))))
                                         y (int (max 0 (min (dec (.getHeight image)) (* v (.getHeight image)))))]
                                     (> (* opacity (bit-and 255 (unsigned-bit-shift-right (.getArgb ^PixelReader reader x y) 24))) 2.55))
                                   (nil? image))))) triangles)))
               (rseq (vec plan)))]
    (:id slot)))

(defn box-slots
  "Select visible attachments wholly inside a screen-space marquee."
  [plan [x0 y0] [x1 y1]]
  (coll/into-> plan #{} (comp (filter #(and (> (double (:opacity %)) 0.01)
                                            (pos? (count (:points %)))
                                            (coll/every? (fn [[x y]] (and (<= (min x0 x1) x (max x0 x1))
                                                                          (<= (min y0 y1) y (max y0 y1)))) (:points %))))
                              (map :id))))

(defn- texture-edges [^Image image]
  (let [reader (.getPixelReader image)
        scale (min 1.0 (/ 1024.0 (max (.getWidth image) (.getHeight image))))
        width (max 1 (int (Math/ceil (* scale (.getWidth image)))))
        height (max 1 (int (Math/ceil (* scale (.getHeight image)))))
        stride (+ width 2)
        alpha (int-array (* stride (+ height 2)))
        crossing (fn alpha-crossing [threshold x0 y0 a x1 y1 b]
                   (let [t (/ (- threshold a) (- b a))]
                     [(/ (max 0.0 (min (double width) (+ 0.5 x0 (* t (- x1 x0))))) width)
                      (/ (max 0.0 (min (double height) (+ 0.5 y0 (* t (- y1 y0))))) height)]))]
    (when reader
      (dotimes [y height]
        (dotimes [x width]
          (aset-int alpha (+ (inc x) (* (inc y) stride))
                    (bit-and 255 (unsigned-bit-shift-right
                                   (.getArgb reader (int (* (+ x 0.5) (/ (.getWidth image) width)))
                                             (int (* (+ y 0.5) (/ (.getHeight image) height)))) 24)))))
      ;; March between pixel centers using actual alpha, rather than tracing
      ;; binary pixel squares. Half of peak opacity follows coverage and also
      ;; preserves translucent assets. Shared crossings close islands.
      (let [threshold (* 0.5 (areduce alpha i maximum 0 (max maximum (aget alpha i))))]
        (persistent!
          (reduce (fn [result y]
                    (reduce (fn [result x]
                              (let [index (+ (inc x) (* (inc y) stride))
                                    a (aget alpha index) b (aget alpha (inc index))
                                    c (aget alpha (+ index stride 1)) d (aget alpha (+ index stride))
                                    bits (+ (if (> a threshold) 1 0) (if (> b threshold) 2 0)
                                            (if (> c threshold) 4 0) (if (> d threshold) 8 0))]
                                (if (or (= 0 bits) (= 15 bits))
                                  result
                                  (let [edge (fn cell-edge [side]
                                               (case side
                                                 :top (crossing threshold x y a (inc x) y b)
                                                 :right (crossing threshold (inc x) y b (inc x) (inc y) c)
                                                 :bottom (crossing threshold x (inc y) d (inc x) (inc y) c)
                                                 :left (crossing threshold x y a x (inc y) d)))
                                        pairs (case bits
                                                1 [[:left :top]] 2 [[:top :right]]
                                                3 [[:left :right]] 4 [[:right :bottom]]
                                                5 [[:left :top] [:right :bottom]]
                                                6 [[:top :bottom]] 7 [[:left :bottom]]
                                                8 [[:bottom :left]] 9 [[:bottom :top]]
                                                10 [[:top :right] [:bottom :left]]
                                                11 [[:bottom :right]] 12 [[:right :left]]
                                                13 [[:right :top]] 14 [[:top :left]])]
                                    (reduce (fn [result [a b]]
                                              (let [a (edge a) b (edge b)]
                                                (if (= a b) result (conj! result [a b])))) result pairs)))))
                            result (range -1 width)))
                  (transient []) (range -1 height)))))))

(defn- simplify-contour [points tolerance]
  (let [[ax ay] (first points) [bx by] (peek points)
        dx (- bx ax) dy (- by ay) length (Math/hypot dx dy)
        [index distance] (reduce-kv
                           (fn [[best-index best-distance] index [x y]]
                             (let [distance (if (< length 1.0e-12)
                                              (Math/hypot (- x ax) (- y ay))
                                              (/ (abs (- (* dx (- ay y)) (* (- ax x) dy))) length))]
                               (if (> distance best-distance) [index distance] [best-index best-distance])))
                           [0 0.0] points)]
    (if (or (<= (count points) 2) (<= distance tolerance))
      [(first points) (peek points)]
      (into (pop (simplify-contour (subvec points 0 (inc index)) tolerance))
            (simplify-contour (subvec points index) tolerance)))))

(defn- contour-segments [edges tolerance]
  (loop [remaining (reduce (fn [result [a b]] (update result a (fnil conj []) b)) {} edges)
         result []]
    (if (zero? (count remaining))
      result
      (let [start (key (first remaining))
            [remaining contour]
            (loop [remaining remaining point start contour [start]]
              (if-let [next-point (peek (get remaining point))]
                (let [destinations (pop (get remaining point))
                      remaining (if (zero? (count destinations)) (dissoc remaining point) (assoc remaining point destinations))
                      contour (conj contour next-point)]
                  (if (= start next-point) [remaining contour]
                      (recur remaining next-point contour)))
                [remaining contour]))
            simplified (simplify-contour contour tolerance)]
        (recur remaining (into result (mapv vector (pop simplified) (subvec simplified 1))))))))

(defn- clipped-weights [a b]
  (when (and a b)
    (when-let [[start end]
               (reduce (fn [[start end] [a b]]
                         (let [delta (- b a)]
                           (if (< (abs (double delta)) 1.0e-12)
                             (if (< a -1.0e-7) (reduced nil) [start end])
                             (let [crossing (/ (- a) delta)
                                   start (if (pos? delta) (max start crossing) start)
                                   end (if (neg? delta) (min end crossing) end)]
                               (if (> start end) (reduced nil) [start end])))))
                       [0.0 1.0] (mapv vector a b))]
      (when (> (- end start) 1.0e-7)
        (mapv (fn [t] (mapv #(+ %1 (* t (- %2 %1))) a b)) [start end])))))

(defn bind-outline
  "Bind simplified alpha contours to triangles, splitting at mesh boundaries."
  [{:keys [uvs triangles ^Image image]}]
  (if (nil? image)
    (coll/into-> (frequencies (coll/into-> triangles [] (mapcat (fn [[a b c]] (mapv #(vec (sort %)) [[a b] [b c] [c a]])))))
      [] (comp (filter #(= 1 (val %))) (map (fn [[indices _]] [indices [[1.0 0.0] [0.0 1.0]]]))))
    (let [contour (contour-segments (texture-edges image)
                                    (/ 0.2 (min 1024.0 (max (.getWidth image) (.getHeight image)))))]
      (coll/into-> triangles []
        (mapcat (fn [indices]
                  (let [triangle (mapv uvs indices)]
                    (coll/into-> contour []
                      (keep (fn [[a b]]
                              (when-let [weights (clipped-weights (barycentric a triangle) (barycentric b triangle))]
                                [indices weights])))))))))))

(defn project-outline [binding points]
  (mapv (fn [[indices endpoints]]
          (let [triangle (mapv points indices)]
            (mapv #(interpolate % triangle) endpoints))) binding))

(defn wireframe-edges
  "Unique screen-space mesh edges. Shared edges are dimmer than the hull."
  [triangles]
  (reduce-kv (fn [result edge count]
               (update result (if (= 1 count) :boundary :interior) conj edge))
             {:boundary [] :interior []}
             (frequencies
               (coll/into-> triangles []
                 (mapcat (fn [[a b c]]
                           (mapv (fn [[a b]] (if (< a b) [a b] [b a]))
                                 [[a b] [b c] [c a]])))))))
