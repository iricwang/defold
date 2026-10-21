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

(ns editor.gui-layout-test
  (:require [clojure.test :refer [deftest is]]
            [editor.gui-layout :as layout]))

;; Every native pivot preserves the rectangle when its position is compensated.
(deftest pivot-preserves-corners
  (doseq [[old] layout/pivot-points
          [new] layout/pivot-points]
    (let [size [120.0 75.0 0.0]
          delta (subvec (layout/pivot-delta size old new) 0 2)]
      (is (= (layout/rect-corners old size)
             (mapv #(mapv + delta %) (layout/rect-corners new size)))))))

;; All eight handles keep the opposite edge/corner fixed for all native pivots.
(deftest resize-keeps-opposite-edge
  (doseq [[pivot] layout/pivot-points
          [hx hy :as handle] [[-1 -1] [0 -1] [1 -1] [1 0] [1 1] [0 1] [-1 1] [-1 0]]]
    (let [old-size [120.0 75.0 0.0]
          {:keys [size delta]} (layout/resize-rect pivot old-size handle [17.0 -13.0] false)
          old-corners (layout/rect-corners pivot old-size)
          new-corners (mapv #(mapv + (subvec delta 0 2) %) (layout/rect-corners pivot size))]
      (is (= (if (neg? hx) (first (nth old-corners 1)) (ffirst old-corners))
             (if (neg? hx) (first (nth new-corners 1)) (ffirst new-corners))))
      (is (= (if (neg? hy) (second (nth old-corners 2)) (second (first old-corners)))
             (if (neg? hy) (second (nth new-corners 2)) (second (first new-corners))))))))

;; Degenerate sizes remain finite, and dragging past the opposite edge cannot mirror a node.
(deftest resize-minimum-and-aspect
  (is (= [1.0 1.0 0.0] (:size (layout/resize-rect :pivot-center [20.0 10.0 0.0] [1 1] [-100.0 -100.0] false))))
  (is (= [40.0 20.0 0.0] (:size (layout/resize-rect :pivot-center [20.0 10.0 0.0] [1 1] [20.0 3.0] true))))
  (is (= [1.0 1.0 0.0] (:size (layout/resize-rect :pivot-center [0.0 0.0 0.0] [1 1] [0.0 0.0] true)))))

;; Pivot compensation must use rotation and non-uniform scale, rather than adding world pixels.
(deftest rotated-scaled-compensation
  (let [a (/ Math/PI 4.0)
        result (layout/local-delta->parent [0.0 0.0 (Math/sin a) (Math/cos a)] [2.0 3.0 1.0] [10.0 4.0 0.0])]
    (is (< (Math/abs (+ 12.0 (double (first result)))) 1.0e-9))
    (is (< (Math/abs (- 20.0 (double (second result)))) 1.0e-9))
    (is (zero? (double (nth result 2))))))
