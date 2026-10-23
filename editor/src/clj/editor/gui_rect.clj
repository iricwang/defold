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

(ns editor.gui-rect
  "Parent-relative rectangles shared by the inspector, scene and device preview.")

(defn resolve-position [position pivot anchor-min anchor-max reference parent]
  (reduce (fn [position axis]
            (let [anchor (+ (double (anchor-min axis))
                            (* (- (double (anchor-max axis)) (double (anchor-min axis))) (double (pivot axis))))
                  delta (- (* (- anchor (double (parent (+ (long axis) 2)))) (double (parent axis)))
                           (* (- anchor (double (reference (+ (long axis) 2)))) (double (reference axis))))]
              (update position axis + delta)))
          position [0 1]))

(defn resolve-size [size anchor-min anchor-max reference parent]
  (reduce (fn [size axis]
            (assoc size axis (max 0.0 (+ (double (size axis))
                                         (* (- (double (anchor-max axis)) (double (anchor-min axis)))
                                            (- (double (parent axis)) (double (reference axis))))))))
          size [0 1]))

(defn resolve-geometry [position size pivot anchor-min anchor-max reference parent]
  {:position (resolve-position position pivot anchor-min anchor-max reference parent)
   :size (resolve-size size anchor-min anchor-max reference parent)})

(defn offsets [position size pivot anchor-min anchor-max parent]
  (let [minimum (mapv (fn [axis]
                        (- (double (position axis)) (* (double (pivot axis)) (double (size axis)))
                           (* (- (double (anchor-min axis)) (double (parent (+ axis 2)))) (double (parent axis)))))
                      [0 1])
        maximum (mapv (fn [axis]
                        (- (* (- (double (anchor-max axis)) (double (parent (+ axis 2)))) (double (parent axis)))
                           (double (position axis)) (* (- 1.0 (double (pivot axis))) (double (size axis)))))
                      [0 1])]
    (into minimum maximum)))

(defn from-offsets [offsets position size pivot anchor-min anchor-max parent]
  (reduce (fn [geometry axis]
            (let [low (+ (double (offsets axis))
                         (* (- (double (anchor-min axis)) (double (parent (+ axis 2)))) (double (parent axis))))
                  high (- (* (- (double (anchor-max axis)) (double (parent (+ axis 2)))) (double (parent axis)))
                          (double (offsets (+ axis 2))))
                  length (max 0.0 (- high low))]
              (-> geometry
                  (assoc-in [:size axis] length)
                  (assoc-in [:position axis] (+ low (* length (double (pivot axis))))))))
          {:position position :size size}
          [0 1]))

(def presets
  (mapv (fn [index]
          (let [column (mod (long index) 4)
                row (quot (long index) 4)
                x (case column 0 0.0 1 0.5 2 1.0 0.0)
                y (case row 0 1.0 1 0.5 2 0.0 0.0)]
            {:column column :row row
             :anchor-min [x y 0.0 0.0]
             :anchor-max [(if (= 3 column) 1.0 x) (if (= 3 row) 1.0 y) 0.0 0.0]
             :pivot [(if (= 3 column) 0.5 x) (if (= 3 row) 0.5 y)]}))
        (range 16)))
