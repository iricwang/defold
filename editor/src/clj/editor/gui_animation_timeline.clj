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

(def default-visible-properties ["position_x" "position_y" "scale_x" "scale_y" "rotation_z" "color_a"])

(defn editor-rows [track-list collapsed selected-node child-counts]
  (let [names (into [] (comp (map first) (distinct)) track-list)
        names (if (or (nil? selected-node) (contains? (set names) selected-node)) names (conj names selected-node))]
    (into []
          (mapcat (fn [node-name]
                    (if (contains? collapsed node-name)
                      [{:node-name node-name :kind :node}]
                      (into [{:node-name node-name :kind :node}]
                            cat
                            [(mapv (fn [property-id] {:node-name node-name :property-id property-id :kind :property})
                                   (into default-visible-properties
                                         (comp (filter #(= node-name (first %)))
                                               (map second)
                                               (remove (set default-visible-properties)))
                                         track-list))
                             [{:node-name node-name :kind :children :child-count (get child-counts node-name 0)}]]))))
          names)))

(defn ruler-step [visible-frames width]
  (let [desired (/ (double visible-frames) (max 1.0 (/ (double width) 76.0)))]
    (or (coll/first-where #(>= (double %) desired) [1 2 4 5 10 20 30 60 120 240 600 1200 3600])
        (* 3600.0 (Math/ceil (/ desired 3600.0))))))

(defn advance-playback [time duration dt loop-enabled mode direction]
  (let [duration (double duration)
        time (double time)
        dt (max 0.0 (double dt))]
    (if (zero? duration)
      {:time 0.0 :playing false :direction 1}
      (case mode
        :reverse (let [next-time (- time dt)]
                   {:time (if loop-enabled (mod next-time duration) (max 0.0 next-time))
                    :playing (or loop-enabled (pos? next-time)) :direction -1})
        :ping-pong (let [phase (+ (if (neg? (long direction)) (- (* 2.0 duration) time) time) dt)
                         phase (double (if loop-enabled (mod phase (* 2.0 duration)) (min phase (* 2.0 duration))))]
                     {:time (if (<= phase duration) phase (- (* 2.0 duration) phase))
                      :playing (or loop-enabled (< phase (* 2.0 duration)))
                      :direction (if (< phase duration) 1 -1)})
        (assoc (advance time duration dt loop-enabled) :direction 1)))))
