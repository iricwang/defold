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

(ns editor.skeletal-animation-graph-edit
  "Scalar curve operations used by the animation controls."
  (:require [util.coll :as coll]))

(defn- lerp-point [a b t]
  (mapv #(+ %1 (* t (- %2 %1))) a b))

(defn- split-segment [left right time]
  (let [kind (:interpolation left)]
    (if (= "bezier" kind)
      (let [[x1 y1 x2 y2] (:curve left)
            a [(:time left) (:value left)] b [x1 y1] c [x2 y2] d [(:time right) (:value right)]
            t (loop [lo 0.0 hi 1.0 n 0]
                (let [t (* 0.5 (+ lo hi))
                      ab (lerp-point a b t) bc (lerp-point b c t) cd (lerp-point c d t)
                      p (lerp-point (lerp-point ab bc t) (lerp-point bc cd t) t)]
                  (if (= n 48) t
                      (if (< (first p) time) (recur t hi (inc n)) (recur lo t (inc n))))))
            ab (lerp-point a b t) bc (lerp-point b c t) cd (lerp-point c d t)
            abc (lerp-point ab bc t) bcd (lerp-point bc cd t)
            p (lerp-point abc bcd t)]
        [(assoc left :curve (into ab abc))
         {:time time :value (second p) :interpolation "bezier" :curve (into bcd cd)}])
      [left {:time time :value (if (= "stepped" kind) (:value left)
                                   (+ (:value left) (* (/ (- time (:time left)) (- (:time right) (:time left)))
                                                       (- (:value right) (:value left)))))
             :interpolation (or kind "linear")}])))

(defn offset-loop
  "Cyclic shift of a closed scalar curve. Splits the seam exactly, retaining
  Bezier handles and equal first/last values rather than dropping wrapped keys."
  [keys duration delta]
  (when-not (and (>= (count keys) 2) (pos? duration)
                 (< (abs (double (:time (first keys)))) 1.0e-8)
                 (< (abs (- (double (:time (peek keys))) duration)) 1.0e-8)
                 (< (abs (- (double (:value (first keys))) (:value (peek keys)))) 1.0e-8))
    (throw (ex-info "Offset requires matching first and last keys at the loop boundaries." {})))
  (let [delta (mod delta duration)
        cut (mod (- delta) duration)
        existing (coll/any? #(< (abs (- (:time %) cut)) 1.0e-8) keys)
        keys (if existing keys
                 (loop [index 0 result []]
                   (let [left (nth keys index) right (get keys (inc index))]
                     (if (and right (< (:time left) cut (:time right)))
                       (into (into result (split-segment left right cut)) (subvec keys (inc index)))
                       (recur (inc index) (conj result left))))))
        shifted (mapv (fn [key]
                        (let [time (mod (+ (:time key) delta) duration)
                              time (if (or (< time 1.0e-8) (< (- duration time) 1.0e-8)) 0.0 time)
                              dt (- time (:time key))]
                          (cond-> (assoc key :time time)
                            (= "bezier" (:interpolation key))
                            (update :curve (fn [[x1 y1 x2 y2]] [(+ x1 dt) y1 (+ x2 dt) y2]))))) (pop keys))
        shifted (vec (sort-by :time shifted))]
    (conj shifted (assoc (dissoc (first shifted) :curve) :time duration :interpolation "linear"))))
