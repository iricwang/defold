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

(ns editor.skeletal-animation-playback-test
  (:require [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-events :as events]
            [editor.skeletal-animation-mixing :as mixing]
            [editor.skeletal-animation-playback :as playback]))

(defn- rig []
  (-> (data/new-document "events")
      (events/add-definition {:name "step" :int 7 :float 0.25 :string "left"})
      (events/set-keyframe "default" {:name "step" :time 0.0})
      (events/set-keyframe "default" {:name "step" :time 0.5 :int 8})))

;; Playback fires zero once, carries payloads, and preserves loop crossings.
(deftest playback-event-crossings-test
  (let [doc (rig)
        initial {:animation "default" :time 0.0 :playing true :loop true :speed 1.0}
        started (playback/advance initial doc 10.0)
        midway (playback/advance started doc 10.6)
        wrapped (playback/advance midway doc 11.1)]
    (is (= [7] (mapv :int (:event-log started))))
    (is (= [7 8] (mapv :int (:event-log midway))))
    (is (= [7 8 7] (mapv :int (:event-log wrapped))))
    (is (< (abs (- 0.1 (:time wrapped))) 1e-8))))

;; Seeking and resuming must not replay skipped events; nonloop stops at end.
(deftest playback-seek-stop-test
  (let [doc (rig)
        stopped (playback/advance {:animation "default" :time 0.7 :playing false :loop false} doc 5.0)
        resumed (playback/advance (assoc stopped :playing true :last-clock nil) doc 6.0)
        ended (playback/advance resumed doc 7.0)]
    (is (= [] (:event-log stopped)))
    (is (= [] (:event-log resumed)))
    (is (false? (:playing ended)))
    (is (= [] (:event-log ended))))
  (let [doc (rig)
        initial {:animation "default" :time 0.0 :playing true :loop false :speed 1.0}
        ended (-> initial (playback/advance doc 0.0) (playback/advance doc 2.0))]
    (is (false? (:playing ended)))
    (is (= [7 8] (mapv :int (:event-log ended))))))

;; Long loops keep a bounded transient log without growing document state.
(deftest playback-log-bound-test
  (let [doc (rig)
        result (-> {:animation "default" :time 0.0 :playing true :loop true :speed 1.0}
                   (playback/advance doc 0.0)
                   (playback/advance doc 80.0))]
    (is (= 100 (count (:event-log result))))
    (is (= doc (rig)))))

;; Each track has an independent clock; base loops cannot rewind overlay events.
(deftest overlay-clock-and-transition-test
  (let [doc (-> (rig)
                (data/add-animation {:id "overlay" :duration 3.0 :tracks []})
                (events/set-keyframe "overlay" {:name "step" :time 2.5 :int 99}))
        initial {:animation "default" :time 0.0 :playing true :loop true
                 :mix-enabled true :mix-overlay "overlay"}
        result (-> initial
                   (playback/advance doc 0.0)
                   (playback/advance doc 1.1)
                   (playback/advance doc 2.6))]
    (is (< (abs (- 0.6 (:time result))) 1e-8))
    (is (= 2.6 (:preview-time result)))
    (is (= [99] (mapv :int (filterv #(= 1 (:track %)) (:event-log result)))))
    (let [result (-> (assoc initial :mix-transition {:from "overlay" :elapsed 0.0 :duration 0.25})
                     (playback/advance doc 0.0)
                     (playback/advance doc 0.1)
                     (playback/advance doc 0.3))]
      (is (nil? (:mix-transition result))))))

;; Pausing after the base clip loops must not rewind the longer overlay track.
(deftest paused-overlay-preserves-clock-test
  (let [doc (-> (rig)
                (data/add-animation {:id "overlay" :duration 3.0 :tracks []})
                (events/set-keyframe "overlay" {:name "step" :time 1.2 :int 99}))
        state (-> {:animation "default" :time 0.0 :playing true :loop true
                   :mix-enabled true :mix-overlay "overlay"}
                  (playback/advance doc 0.0)
                  (playback/advance doc 1.5))
        paused (playback/advance (assoc state :playing false) doc 2.0)
        resumed (-> (assoc paused :playing true :last-clock nil)
                    (playback/advance doc 3.0)
                    (playback/advance doc 3.1))
        sought (playback/advance (assoc resumed :playing false :time 0.1) doc 4.0)]
    (is (= 1.5 (:preview-time paused)))
    (is (= [99] (mapv :int (filterv #(= 1 (:track %)) (:event-log resumed)))))
    (is (< (abs (- 1.6 (:preview-time resumed))) 1e-8))
    (is (= 0.1 (:preview-time sought)))))

;; Frame notifications are consumable once even when the retained log is full;
;; seeking or pausing never plays the latest sound a second time.
(deftest frame-events-test
  (let [document (rig)
        started (playback/advance {:animation "default" :time 0.0 :playing true :loop true} document 1.0)
        advanced (playback/advance started document 1.6)
        paused (playback/advance (assoc advanced :playing false) document 1.7)
        sought (playback/advance (assoc advanced :time 0.1) document 1.8)]
    (is (= [7] (mapv :int (:frame-events started))))
    (is (= [8] (mapv :int (:frame-events advanced))))
    (is (= [] (:frame-events paused)))
    (is (= [] (:frame-events sought)))
    (is (= (:event-log advanced) (:event-log paused)))))

;; The transport follows queued clips to completion instead of stopping at
;; the original clip duration, and shows the current track's local cursor.
(deftest mixer-queue-transport-test
  (let [document (-> (rig)
                     (data/add-animation {:id "next" :duration 2.0})
                     (events/set-keyframe "next" {:name "step" :time 0.0 :int 10})
                     (events/set-keyframe "next" {:name "step" :time 1.5 :int 11}))
        mixer (mixing/create-state document {:default-mix 0.25
                                             :tracks [{:animation "default" :queue [{:animation "next"}]}]})
        initial {:mode "animate" :mixer-enabled true :mixer-state mixer :animation "default" :time 0.0 :playing true}
        started (playback/advance-mixer initial document 10.0)
        queued (playback/advance-mixer started document 11.5)
        ended (playback/advance-mixer queued document 13.0)]
    (is (true? (:playing queued)))
    (is (= "next" (:animation queued)))
    (is (< (abs (- 0.75 (:time queued))) 1e-8))
    (is (= 1.5 (:preview-time queued)))
    (is (false? (:playing ended)))
    (is (= 2.0 (:time ended)))
    (is (= 2.75 (:preview-time ended)))
    (is (= [7 8 10 11] (mapv :int (:event-log ended))))))

;; Pausing keeps the same Physics state, seeking reconstructs it without
;; firing skipped sounds, and switching to setup cannot advance playback.
(deftest mixer-pause-seek-transport-test
  (let [document (-> (rig)
                     (data/add-physics {:id "spring" :bone "root" :x 1.0})
                     (data/set-keyframe "default" "root" "x" 0.0 0.0 "linear")
                     (data/set-keyframe "default" "root" "x" 1.0 100.0 "linear"))
        initial {:mode "animate" :mixer-enabled true :animation "default" :time 0.0 :playing true :loop true}
        playing (-> initial (playback/advance-mixer document 0.0) (playback/advance-mixer document 0.6))
        paused (playback/advance-mixer (assoc playing :playing false) document 50.0)
        sought (playback/advance-mixer (assoc paused :time 0.2) document 51.0)
        expected (mixing/seek-state document (:mixer-state paused) 0.2)
        setup (playback/advance-mixer (assoc sought :mode "setup" :playing true) document 52.0)]
    (is (identical? (:mixer-state playing) (:mixer-state paused)))
    (is (= (:pose expected) (get-in sought [:mixer-sample :pose])))
    (is (= [] (:frame-events paused) (:frame-events sought) (:frame-events setup)))
    (is (= (:event-log playing) (:event-log sought)))
    (is (false? (:playing setup)))
    (is (= (:mixer-state sought) (:mixer-state setup)))))

;; UI commands may sample immediately while paused. Their zero-frame events
;; remain pending until Play and are consumed exactly once by audio/logging.
(deftest mixer-pending-events-test
  (let [document (rig)
        frame (mixing/advance-state document (mixing/create-state document {:tracks [{:animation "default" :loop true}]}) 0.0)
        initial {:mode "animate" :mixer-enabled true :mixer-clock-initialized true :animation "default" :time 0.0 :playing false
                 :mixer-state (:state frame) :mixer-sample frame :mixer-pending-events (:events frame)}
        paused (playback/advance-mixer initial document 1.0)
        playing (playback/advance-mixer (assoc paused :playing true :last-clock nil) document 2.0)
        next-frame (playback/advance-mixer playing document 2.1)
        later (playback/advance-mixer next-frame document 60.0)]
    (is (= [] (:frame-events paused)))
    (is (= [7] (mapv :int (:mixer-pending-events paused))))
    (is (= [7] (mapv :int (:frame-events playing))))
    (is (= [] (:mixer-pending-events playing) (:frame-events next-frame)))
    (is (= 100 (count (:event-log later))))))

;; Authoring edits invalidate Physics, preserve valid track/queue selections,
;; pause playback, and remove stale event payloads from the previous document.
(deftest mixer-document-change-test
  (let [document (-> (rig) (data/add-animation {:id "next" :duration 2.0}))
        mixer (mixing/create-state document {:tracks [{:animation "default" :loop true}
                                                      {:animation "next" :alpha 0.5 :queue [{:animation "default"}]}]})
        initial {:mode "animate" :mixer-enabled true :mixer-state mixer :animation "default" :time 0.0 :playing true}
        started (-> initial (playback/advance-mixer document 0.0) (playback/advance-mixer document 0.6))
        changed (data/update-bone document "root" {:x 40.0})
        rebuilt (playback/advance-mixer started changed 1.0)
        removed (data/remove-animation changed "next")
        pruned (playback/advance-mixer rebuilt removed 2.0)]
    (is (false? (:playing rebuilt)))
    (is (= changed (get-in rebuilt [:mixer-state :document])))
    (is (= ["default" "next"] (mapv :animation (get-in rebuilt [:mixer-state :tracks]))))
    (is (= ["default"] (mapv :animation (get-in rebuilt [:mixer-state :tracks 1 :queue]))))
    (is (= [] (:event-log rebuilt) (:frame-events rebuilt)))
    (is (nil? (get-in pruned [:mixer-state :tracks 1])))))

;; A delayed final clip and per-track speeds determine the finish clock;
;; Physics must stop at the same instant regardless of display-frame gaps.
(deftest mixer-finish-clock-test
  (let [document (-> (rig)
                     (data/add-physics {:id "spring" :bone "root" :x 1.0})
                     (data/add-animation {:id "next" :duration 2.0})
                     (data/set-keyframe "default" "root" "x" 0.0 0.0 "linear")
                     (data/set-keyframe "default" "root" "x" 1.0 100.0 "linear"))
        mixer (mixing/create-state document {:tracks [{:animation "default" :time-scale 0.5
                                                       :queue [{:animation "next" :time-scale 2.0 :delay 1.5 :mix-duration 0.25}]}]})
        started (playback/advance-mixer {:mode "animate" :mixer-state mixer :playing true :time 0.0} document 0.0)
        one-frame (playback/advance-mixer started document 10.0)
        many-frames (reduce (fn [state time] (playback/advance-mixer state document time)) started [1.0 2.0 3.0 3.5 4.1])]
    (is (= 4.0 (:preview-time one-frame) (:preview-time many-frames)))
    (is (false? (:playing one-frame)))
    (is (= (get-in one-frame [:mixer-sample :pose]) (get-in many-frames [:mixer-sample :pose])))))
