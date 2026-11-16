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

(ns editor.skeletal-animation-graph-edit-test
  (:require [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-graph-edit :as edit]
            [editor.skeletal-animation-interpolation :as interpolation]
            [util.coll :as coll]))

(defn- design-value [keys time]
  (let [left (reduce (fn [previous key] (if (> (:time key) time) (reduced previous) key)) (first keys) keys)
        right (coll/first-where #(> (:time %) time) keys)]
    (if (and right (= "bezier" (:interpolation left)) (< (:time left) time))
      (:value (interpolation/split-bezier (:time left) (:value left) (:time right) (:value right) (:curve left) time))
      (#'data/sample-track-impl keys time 0.0))))

;; A cyclic shift preserves the authored cubic geometry at the seam. Runtime
;; ten-segment tessellation changes when a curve is split; it is independently
;; checked against Spine, not mistaken for the analytic curve in this test.
(deftest cyclic-offset-preserves-shape-test
  (doseq [kind ["linear" "bezier" "stepped"]
          delta [0.0 0.3 -0.7 4.2]]
    (let [keys [(cond-> {:time 0.0 :value 0.0 :interpolation kind}
                  (= kind "bezier") (assoc :curve [0.2 8.0 0.7 12.0]))
                (cond-> {:time 1.0 :value 10.0 :interpolation kind}
                  (= kind "bezier") (assoc :curve [1.2 11.0 1.8 -3.0]))
                {:time 2.0 :value 0.0 :interpolation "linear"}]
          shifted (edit/offset-loop keys 2.0 delta)]
      (is (= 0.0 (:time (first shifted))))
      (is (= 2.0 (:time (peek shifted))))
      (doseq [n (range 1 200)]
        (let [t (/ (+ n 0.37) 100.0)]
          (is (< (abs (- (design-value keys (mod (- t delta) 2.0))
                         (design-value shifted t))) 0.0001)))))))

;; Non-looping poses must be rejected instead of silently adding a visible pop.
(deftest cyclic-offset-rejects-open-curve-test
  (is (thrown? clojure.lang.ExceptionInfo
               (edit/offset-loop [{:time 0.0 :value 1.0} {:time 1.0 :value 3.0}] 1.0 0.3))))
