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

(ns editor.skeletal-animation-tools
  "Coordinate conversion and transient poses used by the authoring tools.
  Saved animation channels always remain in the skeleton's local space."
  (:require [editor.skeletal-animation-geometry :as geometry]
            [editor.skeletal-animation-timeline :as timeline]))

(defn- linear [matrix]
  (assoc matrix 4 0.0 5 0.0))

(defn- inverse! [matrix]
  (or (geometry/inverse-transform matrix)
      (throw (ex-info "Cannot edit through a collapsed coordinate axis" {}))))

(defn axes [bone transforms space tool]
  (let [matrix (if (or (= "local" space) (= "scale" tool) (= "shear" tool))
                 (get transforms (:id bone))
                 (if (= "parent" space)
                   (get transforms (:parent bone) geometry/identity-transform)
                   geometry/identity-transform))]
    (mapv (fn [[x y]]
            (let [length (Math/hypot x y)]
              (if (> length 1.0e-12) [(/ x length) (/ y length)] [0.0 0.0])))
          [(subvec matrix 0 2) (subvec matrix 2 4)])))

(defn displayed-transform [bone transforms space]
  (let [[a b c d x y :as matrix] (get transforms (:id bone))
        parent (get transforms (:parent bone) geometry/identity-transform)
        position (case space
                   "world" [x y]
                   "local" (geometry/transform-point (inverse! (linear matrix))
                                                     [(- x (nth parent 4)) (- y (nth parent 5))])
                   [(:x bone) (:y bone)])]
    (cond-> (assoc bone :x (first position) :y (second position))
      (= "world" space)
      (assoc :rotation (mod (Math/toDegrees (Math/atan2 b a)) 360.0)
             :scale-x (* (if (neg? (:scale-x bone)) -1.0 1.0) (Math/hypot a b))
             :scale-y (* (if (neg? (:scale-y bone)) -1.0 1.0) (Math/hypot c d))))))

(defn local-changes [bone transforms space property value]
  (when-not (geometry/finite-number? value)
    (throw (ex-info "Transform value must be finite" {:property property})))
  (let [matrix (get transforms (:id bone))
        parent (get transforms (:parent bone) geometry/identity-transform)]
    (cond
      (and (#{"x" "y"} property) (not= "parent" space))
      (let [displayed (assoc (displayed-transform bone transforms space) (keyword property) value)
            xy [(:x displayed) (:y displayed)]
            world (if (= "world" space) xy
                      (mapv + (geometry/transform-point (linear matrix) xy) (subvec parent 4 6)))
            [x y] (geometry/transform-point (inverse! parent) world)]
        {:x x :y y})

      (and (= "rotation" property) (= "world" space))
      (let [no-scale (and (:parent bone) (contains? #{"noScale" "noScaleOrReflection"} (:inherit bone)))
            handedness (if (and (= "noScale" (:inherit bone))
                                (neg? (- (* (nth parent 0) (nth parent 3)) (* (nth parent 1) (nth parent 2))))) -1.0 1.0)
            effective (if no-scale (linear parent)
                          (geometry/compose-transforms (linear matrix) (inverse! (linear (geometry/local-transform bone)))))
            angle (Math/toRadians (- value (if no-scale (* handedness (:shear-x bone 0.0)) 0.0)
                                     (if (and no-scale (neg? (:scale-x bone))) 180.0 0.0)))
            [x y] (geometry/transform-point (inverse! effective) [(Math/cos angle) (Math/sin angle)])
            target (- (Math/toDegrees (Math/atan2 y x)) (if no-scale 0.0 (:shear-x bone 0.0))
                      (if (and (not no-scale) (neg? (:scale-x bone))) 180.0 0.0))
            delta (- (mod (+ (- target (:rotation bone)) 180.0) 360.0) 180.0)]
        {:rotation (+ (:rotation bone) delta)})

      :else {(keyword property) value})))

(defn numeric-value [text previous]
  (let [text (.trim ^String text)
        operation (when (pos? (count text)) (first text))
        relative (contains? #{\+ \* \/} operation)
        operand (Double/parseDouble (if relative (subs text 1) text))
        result (if relative
                 (case operation \+ (+ previous operand) \* (* previous operand) \/ (/ previous operand))
                 operand)]
    (when-not (Double/isFinite result)
      (throw (NumberFormatException. "Value must be finite")))
    result))

(defn cursor [state]
  (select-keys state [:mode :animation :time]))

(defn pending-document [state baseline]
  (let [pending (:pending-pose state)]
    (when (and (= baseline (:baseline pending)) (= (cursor state) (:cursor pending)))
      (:document pending))))

(defn stage-pose [state baseline document]
  (let [before (pending-document state baseline)
        pending {:baseline baseline :cursor (cursor state) :document document}]
    (if (= document (or before baseline))
      (dissoc state :draft)
      (-> state
          (assoc :pending-pose pending :pose-redo [])
          (update :pose-undo (fnil conj []) (:pending-pose state))
          (dissoc :draft :discarded-pose)))))

(defn undo-pose [state redo]
  (let [source (if redo :pose-redo :pose-undo)
        target (if redo :pose-undo :pose-redo)
        history (get state source)]
    (if (zero? (count history))
      state
      (-> state
          (assoc :pending-pose (peek history) source (pop history))
          (update target (fnil conj []) (:pending-pose state))
          (dissoc :draft)))))

(defn restore-discarded [state]
  (if-let [{:keys [baseline document cursor]} (:discarded-pose state)]
    (let [animation (:animation cursor)
          existing (set (timeline/all-key-refs baseline animation))
          changed (filterv (fn [ref]
                             (or (not (contains? existing ref))
                                 (not= (mapv :entries (:tracks (timeline/copy-keys baseline animation [ref])))
                                       (mapv :entries (:tracks (timeline/copy-keys document animation [ref]))))))
                           (filterv #(== (:time cursor) (:time %)) (timeline/all-key-refs document animation)))
          clipboard (timeline/copy-keys document animation changed)
          restored (timeline/paste-keys baseline animation clipboard (:time state) {})]
      (-> (stage-pose state baseline restored) (dissoc :discarded-pose)))
    state))
