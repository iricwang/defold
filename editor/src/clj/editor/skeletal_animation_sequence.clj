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

(ns editor.skeletal-animation-sequence
  "Spine 4.2 numbered image sequences and discrete sequence timelines."
  (:require [clojure.string :as string]
            [editor.skeletal-animation-geometry :as geometry]
            [util.coll :as coll]))

(def modes ["hold" "once" "loop" "pingpong" "onceReverse" "loopReverse" "pingpongReverse"])

(defn normalize [value]
  (let [value (merge {:start 1 :digits 0 :setup-index 0} value)
        {:keys [count start digits setup-index]} value]
    (when-not (and (integer? count) (<= 1 count 100000) (integer? start) (<= 0 start)
                   (integer? digits) (<= 0 digits 16) (integer? setup-index) (<= 0 setup-index (dec count)))
      (throw (ex-info "Sequence count/start/digits/setup index are invalid." {:sequence value})))
    value))

(defn decode [source]
  (when source (normalize {:count (get source "count") :start (get source "start" 1)
                           :digits (get source "digits" 0) :setup-index (get source "setup" 0)})))

(defn encode [value original]
  (let [{:keys [count start digits setup-index]} (normalize value)]
    (assoc (or original {}) "count" count "start" start "digits" digits "setup" setup-index)))

(defn frame-path [path value index]
  (let [{:keys [count start digits]} (normalize value)
        _ (when-not (and (integer? index) (<= 0 index (dec count)))
            (throw (ex-info "Sequence index is out of range." {:index index})))
        png (string/ends-with? (string/lower-case path) ".png")
        stem (if png (subs path 0 (- (clojure.core/count path) 4)) path)
        number (str (+ start index))]
    (str stem (apply str (repeat (max 0 (- digits (clojure.core/count number))) "0")) number (when png ".png"))))

(defn frame-paths [path value]
  (mapv #(frame-path path value %) (range (:count (normalize value)))))

(defn prepare-frames [frames]
  (first
    (reduce (fn [[result delay previous] frame]
              (let [time (get frame "time" 0.0)
                    delay (get frame "delay" delay)
                    mode (get frame "mode" "hold")
                    index (get frame "index" 0)]
                (when-not (and (geometry/finite-number? time) (<= 0 time) (< previous time)
                               (coll/any? #{mode} modes) (integer? index) (<= (if (= "hold" mode) -1 0) index)
                               (geometry/finite-number? delay) (if (= "hold" mode) (<= 0 delay) (pos? delay)))
                  (throw (ex-info "Sequence keys need ordered times, a valid mode/index and positive playback delay." {:frame frame})))
                [(conj result (assoc frame "time" time "delay" delay "mode" mode "index" index)) delay time]))
            [[] 0.0 -1.0] frames)))

(defn sample-index [value prepared time]
  (let [{:keys [count setup-index]} (normalize value)
        frame (reduce (fn [last frame] (if (<= (get frame "time") time) frame (reduced last))) nil prepared)]
    (if-not frame setup-index
            (let [mode (get frame "mode")
                  index (+ (get frame "index") (if (= "hold" mode) 0 (long (+ 0.00001 (/ (- time (get frame "time")) (get frame "delay"))))))
                  period (max 1 (- (* 2 count) 2))
                  phase (mod (if (= "pingpongReverse" mode) (+ index (dec count)) index) period)]
              (case mode
                "once" (min (dec count) index)
                "loop" (mod index count)
                ("pingpong" "pingpongReverse") (if (>= phase count) (- period phase) phase)
                "onceReverse" (max 0 (- (dec count) index))
                "loopReverse" (- (dec count) (mod index count))
                (if (= -1 index) setup-index (min (dec count) index)))))))
