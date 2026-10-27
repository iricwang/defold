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

(ns editor.skeletal-animation-interpolation
  "Shared piecewise Bezier lookup matching Spine 4.2 timeline sampling."
  (:require [util.coll :as coll]))

(set! *warn-on-reflection* true)

(defn- cubic [a b c d t]
  (let [u (- 1.0 t)]
    (+ (* u u u a) (* 3.0 u u t b) (* 3.0 u t t c) (* t t t d))))

(defn bezier-points
  "Compile absolute controls into the ten parameter segments used at runtime."
  [from-time from-value to-time to-value curve]
  (when-not (and (vector? curve) (= 4 (count curve))
                 (coll/every? #(and (number? %) (Double/isFinite (double %)))
                              (into [from-time from-value to-time to-value] curve))
                 (< from-time to-time)
                 (<= from-time (nth curve 0) (nth curve 2) to-time))
    (throw (ex-info "Invalid timeline Bezier controls." {})))
  (let [[x0 y0 x1 y1] curve]
    (mapv (fn [step]
            (let [t (/ step 10.0)]
              [(cubic from-time x0 x1 to-time t)
               (cubic from-value y0 y1 to-value t)]))
          (range 11))))

(defn sample-bezier
  "Sample previously compiled Bezier points, clamping outside the segment."
  [points time]
  (let [index (or (reduce-kv (fn [_ index [x _]] (when (>= x time) (reduced index))) nil points)
                  (dec (count points)))
        [x0 y0] (nth points (max 0 (dec index)))
        [x1 y1] (nth points index)]
    (if (= x0 x1)
      y1
      (+ y0 (* (max 0.0 (min 1.0 (/ (- time x0) (- x1 x0)))) (- y1 y0))))))

(defn split-bezier
  "Split an absolute cubic at an interior time. Both control arrays remain
  absolute; callers can normalize their values for an FFD progress curve."
  [from-time from-value to-time to-value curve time]
  (bezier-points from-time from-value to-time to-value curve)
  (when-not (and (number? time) (Double/isFinite (double time)) (< from-time time to-time))
    (throw (ex-info "Bezier split time must lie inside the key interval." {})))
  (let [[x0 y0 x1 y1] curve
        parameter (loop [low 0.0 high 1.0 iteration 0]
                    (let [middle (* 0.5 (+ low high))]
                      (if (= 48 iteration)
                        middle
                        (if (< (cubic from-time x0 x1 to-time middle) time)
                          (recur middle high (inc iteration))
                          (recur low middle (inc iteration))))))
        interpolate (fn [a b] (mapv #(+ %1 (* parameter (- %2 %1))) a b))
        a (interpolate [from-time from-value] [x0 y0])
        b (interpolate [x0 y0] [x1 y1])
        c (interpolate [x1 y1] [to-time to-value])
        d (interpolate a b)
        e (interpolate b c)
        [_ value] (interpolate d e)]
    {:value value
     :left [(max from-time (min time (a 0))) (a 1) (max from-time (min time (d 0))) (d 1)]
     :right [(max time (min to-time (e 0))) (e 1) (max time (min to-time (c 0))) (c 1)]}))
