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

(ns editor.skeletal-animation-playback
  "Transient editor playback. Seeking never dispatches gameplay events."
  (:require [editor.skeletal-animation-events :as events]
            [editor.skeletal-animation-geometry :as geometry]
            [editor.skeletal-animation-mixing :as mixing]
            [util.coll :as coll]))

(defn- track-events [document animation from to loop track]
  (let [frames (events/keyframes document (:id animation))
        ;; After suspension, retain the latest log window instead of hours
        ;; of missed notifications.
        cycles (max 1.0 (Math/ceil (/ 100.0 (max 1 (count frames)))))
        from (if (and loop (pos? (:duration animation)))
               (max from (- to (* cycles (:duration animation))))
               from)]
    (mapv #(assoc % :track track) (events/triggered document (:id animation) from to loop))))

(defn advance [state document elapsed]
  (let [animation (coll/first-where #(= (:animation state) (:id %)) (:animations document))
        duration (double (or (:duration animation) 1.0))
        changed (not= (:animation state) (:event-animation state))
        state (cond-> state changed (assoc :event-log [] :event-animation (:animation state)))
        time (min duration (double (:time state 0.0)))
        sought (not= time (:playback-time state time))
        state (cond-> state sought (assoc :preview-time time :mix-transition nil))
        start (nil? (:last-clock state))
        delta (if (or start sought) 0.0 (* (max 0.0 (- elapsed (:last-clock state))) (:speed state 1.0)))
        end (if (:loop state) (+ time delta) (min duration (+ time delta)))
        delta (if (:loop state) delta (- end time))
        preview-time (if changed time (:preview-time state time))
        next-preview-time (+ preview-time delta)
        initial-from (fn initial-from [time] (if (and start (zero? time)) -0.000001 time))
        fired (when (and animation (:playing state) (not sought))
                (track-events document animation (initial-from time) end (boolean (:loop state)) 0))
        overlay (when (and (:playing state) (not sought) (:mix-enabled state))
                  (coll/first-where #(= (:mix-overlay state) (:id %)) (:animations document)))
        fired (if-not overlay fired
                      (into (vec fired)
                            (track-events document overlay (initial-from preview-time) next-preview-time (boolean (:loop state)) 1)))]
    (cond-> (assoc state :last-clock elapsed :frame-events (vec fired)
                   :preview-time (if (:playing state) next-preview-time preview-time)
                   :playback-time time)
      (:playing state)
      (assoc :time (if (:loop state) (mod end (max 0.001 duration)) end)
             :playback-time (if (:loop state) (mod end (max 0.001 duration)) end)
             :playing (or (:loop state) (< end duration))
             :event-log (let [log (into (:event-log state []) fired)] (subvec log (max 0 (- (count log) 100)))))
      (and (:playing state) (:mix-transition state))
      (update :mix-transition
              (fn advance-transition [transition]
                (let [transition (update transition :elapsed (fnil + 0.0) delta)]
                  (when (< (:elapsed transition) (:duration transition)) transition)))))))

(defn- primary-entry [mixer]
  (coll/first-where some? (:tracks mixer)))

(defn- entry-time [entry]
  (let [time (:track-time entry 0.0) duration (:duration entry 0.0)]
    (if (and (:loop entry) (pos? duration)) (mod time duration) (min time duration))))

(defn- restore-mixer [state document]
  (let [ids (coll/into-> (:animations document) #{} (map :id))
        fields [:animation :loop :alpha :blend :time-scale :mix-duration :delay
                :attachment-threshold :event-threshold :draw-order-threshold]
        entry-specification (fn entry-specification [entry]
                              (when (contains? ids (:animation entry))
                                (assoc (select-keys entry fields) :time (:track-time entry 0.0)
                                       :queue (coll/into-> (:queue entry) []
                                                (filter #(contains? ids (:animation %)))
                                                (map #(assoc (select-keys % fields) :time (:track-time % 0.0)))))))
        old (:mixer-state state)
        tracks (if old (mapv entry-specification (:tracks old))
                   (if-let [animation (or (when (contains? ids (:animation state)) (:animation state))
                                          (:id (first (:animations document))))]
                     [{:animation animation :loop (boolean (:loop state))}] []))]
    (mixing/create-state document {:tracks tracks :default-mix (:mix-duration state 0.25)})))

(defn- remaining-track-time [entry]
  (loop [entry entry queue (:queue entry) total 0.0 incoming false]
    (if-not entry total
            (let [speed (:time-scale entry)
                  until (fn until [target]
                          (let [distance (max 0.0 (- target (:track-time entry)))]
                            (if (pos? speed) (/ distance speed)
                                (if (pos? distance) Double/POSITIVE_INFINITY 0.0))))]
              (if-let [next-entry (first queue)]
                (recur next-entry (subvec queue 1) (+ total (until (:delay next-entry))) true)
                (if (:loop entry) Double/POSITIVE_INFINITY
                    (+ total (max (until (:duration entry))
                                  (if (or incoming (:mixing-from entry))
                                    (max 0.0 (- (:mix-duration entry) (:mix-time entry))) 0.0)))))))))

(defn advance-mixer
  "Advance editor transport using the mixer's absolute clock. :time remains
  the primary track's local cursor; queue changes update :animation. Pauses
  preserve simulation, seeks suppress events, and document edits rebuild the
  active entries/remaining queues with fresh Physics state and pause playback.
  UI commands that apply a zero-time sample retain its events in
  :mixer-pending-events until the first playing refresh."
  [state document elapsed]
  (when-not (and (geometry/finite-number? elapsed)
                 (geometry/finite-number? (:speed state 1.0))
                 (not (neg? (:speed state 1.0))))
    (throw (ex-info "Mixer transport needs a finite clock and nonnegative speed." {})))
  (if (not= "animate" (:mode state))
    (assoc state :last-clock elapsed :frame-events [] :playing false)
    (let [old (:mixer-state state)
          changed (and old (not= document (:document old)))
          mixer (if (or (nil? old) changed) (restore-mixer state document) old)
          initialized (or (:mixer-clock-initialized state) changed)
          initial-time (max 0.0 (double (get state :preview-time (:time state 0.0))))
          mixer (if (and (not initialized) (pos? initial-time))
                  (:state (mixing/seek-state document mixer initial-time)) mixer)
          primary (primary-entry mixer)
          sought (and (not changed) initialized
                      (not= (:time state 0.0) (:playback-time state (:time state 0.0))))
          seek-time (if (and sought primary (pos? (:time-scale primary)))
                      (max 0.0 (+ (:time mixer) (/ (- (:time state) (entry-time primary)) (:time-scale primary))))
                      (:time mixer))
          playing (and (:playing state) (not changed))
          remaining (reduce max 0.0 (mapv remaining-track-time (:tracks mixer)))
          delta (if (or (not playing) sought changed (nil? (:last-clock state)))
                  0.0 (min remaining (* (max 0.0 (- elapsed (:last-clock state))) (:speed state 1.0))))
          cached (:mixer-sample state)
          frame (cond
                  sought (mixing/seek-state document mixer seek-time)
                  (and (zero? delta) (identical? mixer (:state cached))) (assoc cached :events [])
                  :else (mixing/advance-state document mixer delta))
          mixer (:state frame)
          primary (primary-entry mixer)
          local-time (entry-time primary)
          pending (if (or changed sought) [] (:mixer-pending-events state []))
          notifications (if sought [] (coll/into-> (into (vec pending) (:events frame)) [] (distinct)))
          fired (if playing notifications [])
          log (into (if changed [] (:event-log state [])) fired)
          animation (or (:animation primary) (:animation state))]
      (assoc state :mixer-state mixer :mixer-sample frame :mixer-clock-initialized true
             :mixer-pending-events (if playing [] notifications)
             :animation animation :event-animation animation :last-clock elapsed
             :preview-time (:time mixer) :time local-time :playback-time local-time
             :playing (boolean (and playing (coll/any? #(> (remaining-track-time %) 1.0e-9) (:tracks mixer))))
             :frame-events fired :event-log (subvec log (max 0 (- (count log) 100)))))))
