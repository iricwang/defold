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

(ns editor.gui-animation-timeline
  "Timeline navigation and recording, independent of the scene and controls."
  (:require [editor.gui-animation-data :as animation]
            [util.coll :as coll]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn frames-per-second [document]
  (let [fps (get-in document [:data :metadata :fps])]
    (if (and (number? fps) (Double/isFinite (double fps)) (pos? (double fps)))
      (double fps)
      60.0)))

(defn tracks [document animation-id]
  (let [property-order (into {} (map-indexed (fn [index property] [property index])) animation/supported-properties)]
    (vec (sort-by (fn [[node-name property-id]] [node-name (get property-order property-id)])
                  (into []
                        (comp (filter #(and (contains? #{"tween" 1} (:key_type %))
                                            (contains? property-order (:property_id %))))
                              (map (juxt :node_id :property_id))
                              (distinct))
                        (:animation_keys
                          (coll/first-where #(= animation-id (:animation_id %))
                                           (get-in document [:data :animations]))))))))

(defn display-keyframes [document animation-id node-name property-id]
  ;; Complex imported tracks remain readable without inviting a lossy edit.
  (try
    (animation/keyframes document animation-id node-name property-id)
    (catch clojure.lang.ExceptionInfo _ [])))

(defn rows [track-list collapsed]
  (into []
        (mapcat (fn [node-name]
                  (into [{:node-name node-name}]
                        (when-not (contains? collapsed node-name)
                          (eduction (filter #(= node-name (first %)))
                                    (map (fn [[node-name property-id]]
                                           {:node-name node-name :property-id property-id}))
                                    track-list)))))
        (into [] (comp (map first) (distinct)) track-list)))

(defn snap-time [document animation-id time]
  (let [fps (double (frames-per-second document))
        duration (double (animation/animation-duration document animation-id))]
    (max 0.0 (min duration (/ (double (Math/round (* (double time) fps))) fps)))))

(defn navigate [document animation-id time direction]
  (let [time (double time)
        duration (double (animation/animation-duration document animation-id))
        fps (double (frames-per-second document))
        key-times (into (sorted-set)
                        (comp (mapcat (fn [[node-name property-id]]
                                        (display-keyframes document animation-id node-name property-id)))
                              (map :time))
                        (tracks document animation-id))]
    (case direction
      :first 0.0
      :last duration
      :previous-frame (max 0.0 (/ (dec (long (Math/ceil (- (* time fps) 1.0e-9)))) fps))
      :next-frame (min duration (/ (inc (long (Math/floor (+ (* time fps) 1.0e-9)))) fps))
      :previous-key (reduce (fn [previous t] (if (< (double t) (- time 1.0e-9)) t previous)) 0.0 key-times)
      :next-key (or (coll/first-where #(> (double %) (+ time 1.0e-9)) key-times) duration))))

(defn advance [time duration dt loop-enabled]
  (let [duration (double duration)
        next-time (+ (double time) (max 0.0 (double dt)))]
    (if (and loop-enabled (pos? duration))
      {:time (mod next-time duration) :playing true}
      {:time (min duration next-time) :playing (< next-time duration)})))

(defn record-value [document animation-id node-name property-id time value baseline easing]
  (let [document (if (and (pos? (double time))
                          (zero? (count (animation/keyframes document animation-id node-name property-id))))
                   (animation/set-keyframe document animation-id node-name property-id 0.0 baseline "linear")
                   document)]
    (animation/set-keyframe document animation-id node-name property-id time value easing)))
