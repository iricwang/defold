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

(ns editor.skeletal-animation-navigation
  "Timeline scope and shared keyboard/toolbar transport."
  (:require [editor.skeletal-animation-events :as events]
            [editor.skeletal-animation-slots :as slots]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [javafx.event EventHandler]
           [javafx.scene Node]
           [javafx.scene.control ComboBoxBase Spinner TextInputControl TreeView]
           [javafx.scene.input KeyCode KeyEvent]))

(set! *warn-on-reflection* true)

(defn single-node? [{:keys [selected selected-kind selected-bones selected-slots]}]
  (and (some? selected)
       (contains? #{:bone :slot :ik :transform :path :physics :event} selected-kind)
       (<= (+ (count (disj (set selected-bones) nil)) (count (disj (set selected-slots) nil))) 1)))

(defn channel-id [track]
  (or (:channel-id track) (:property track)))

(defn all-tracks [document animation-id]
  (let [document (slots/materialize-tracks (events/materialize-document document))
        animation (coll/first-where #(= animation-id (:id %)) (:animations document))]
    (coll/into-> [[:tracks :bone] [:inherit-tracks :inherit] [:attachment-deform-tracks :deform-target] [:deforms :slot] [:slot-tracks :slot]
                  [:ik-tracks :ik] [:transform-tracks :transform] [:path-tracks :path]
                  [:physics-tracks :physics] [:event-tracks :event]
                  [:draw-order-tracks :draw-order] [:sequence-tracks :sequence]] []
      (mapcat (fn [[section owner]]
                (coll/into-> (get animation section) []
                  (filter #(pos? (count (:keys %))))
                  (mapcat (fn [track]
                            (case section
                              :ik-tracks [(assoc track :property "IK mix")
                                          (assoc track :property "IK softness" :keys
                                                 (mapv #(assoc % :value (:softness % 0.0)
                                                               :interpolation (:softness-interpolation % "linear")
                                                               :curve (:softness-curve %)) (:keys track)))]
                              [(case section
                                 :deforms (assoc track :property "deform")
                                 :attachment-deform-tracks (assoc track :property "deform")
                                 :slot-tracks (assoc track :slot-animation true)
                                 :event-tracks (assoc track :property (str "◆ " (:event track)))
                                 :draw-order-tracks (assoc track :property "drawOrder")
                                 :sequence-tracks (assoc track :property (str "sequence · " (:skin track) "/" (:attachment track)))
                                 track)])))
                  (map (fn [track]
                         (assoc track :owner (get track owner)
                                :channel-id (pr-str [owner (get track owner) (:property track)]))))))))))

(defn transport-state [state command duration key-times]
  (let [duration (max 0.0 (double duration))
        time (max 0.0 (min duration (double (:time state 0.0))))
        fps (max 1.0 (double (:fps state 60.0)))
        forward (= :next-key command)
        candidates (filterv (if forward #(> (- % time) 1.0e-7) #(< (- % time) -1.0e-7)) key-times)
        target (case command
                 :play (if (and (not (:playing state)) (>= time duration)) 0.0 time)
                 :start 0.0 :end duration
                 :previous-frame (/ (dec (Math/ceil (- (* time fps) 1.0e-7))) fps)
                 :next-frame (/ (inc (Math/floor (+ (* time fps) 1.0e-7))) fps)
                 :back-ten (- time (/ 10.0 fps)) :forward-ten (+ time (/ 10.0 fps))
                 (:previous-key :next-key) (if (pos? (count candidates)) (reduce (if forward min max) candidates) (if forward duration 0.0)))
        target (max 0.0 (min duration target))]
    (assoc state :mode "animate" :playing (and (= :play command) (not (:playing state)) (pos? duration))
           :time target :preview-time target :mix-transition nil :last-clock nil)))

(defn- ancestor? [target predicate]
  (loop [node target]
    (when (instance? Node node)
      (or (predicate node) (recur (.getParent ^Node node))))))

(defn shortcut-command [^KeyEvent event]
  (let [code (.getCode event) target (.getTarget event)]
    (when-not (or (.isShortcutDown event) (.isControlDown event) (.isMetaDown event)
                  (ancestor? target #(or (instance? TextInputControl %) (instance? ComboBoxBase %) (instance? Spinner %))))
      (cond
        (and (= KeyCode/SPACE code) (not (.isAltDown event)) (not (.isShiftDown event))) :play
        ;; Keep arrows and Home/End available for hierarchy navigation.
        (ancestor? target #(instance? TreeView %)) nil
        (#{KeyCode/LEFT KeyCode/RIGHT} code)
        (cond (.isAltDown event) (if (= KeyCode/LEFT code) :previous-key :next-key)
              (.isShiftDown event) (if (= KeyCode/LEFT code) :back-ten :forward-ten)
              :else (if (= KeyCode/LEFT code) :previous-frame :next-frame))
        (and (not (.isAltDown event)) (not (.isShiftDown event)))
        (condp = code KeyCode/HOME :start KeyCode/END :end nil)))))

(defn install-shortcuts! [^Node root action!]
  (when-let [[pressed released] (ui/user-data root ::handlers)]
    (.removeEventFilter root KeyEvent/KEY_PRESSED ^EventHandler pressed)
    (.removeEventFilter root KeyEvent/KEY_RELEASED ^EventHandler released))
  (let [space-down (atom false)
        pressed (ui/event-handler event
                  (when-let [command (shortcut-command event)]
                    ;; Space toggles once on release, so OS key repeat cannot
                    ;; oscillate playback. Frame keys intentionally repeat.
                    (if (= :play command) (reset! space-down true) (action! command))
                    (.consume ^KeyEvent event)))
        released (ui/event-handler event
                   (when (= KeyCode/SPACE (.getCode ^KeyEvent event))
                     (when (and @space-down (= :play (shortcut-command event)))
                       (action! :play) (.consume ^KeyEvent event))
                     (reset! space-down false)))]
    (.addEventFilter root KeyEvent/KEY_PRESSED pressed)
    (.addEventFilter root KeyEvent/KEY_RELEASED released)
    (ui/user-data! root ::handlers [pressed released])))
