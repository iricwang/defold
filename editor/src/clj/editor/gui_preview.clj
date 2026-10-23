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

(ns editor.gui-preview
  (:require [clojure.string :as str]
            [dynamo.graph :as g]
            [editor.gui-animation-preview :as gui-animation-preview]
            [editor.pose :as pose]
            [editor.scene :as scene]
            [internal.cache :as cache]
            [util.coll :as coll]))

(defn safe-rect
  "Matches dmGui::ComputeSafeAreaAdjust; coordinates have a bottom-left origin."
  [[width height] [top right bottom left] mode]
  (let [landscape (>= width height)
        mode (str/lower-case (or mode "none"))
        horizontal (or (= mode "both") (and (= mode "long") landscape) (and (= mode "short") (not landscape)))
        vertical (or (= mode "both") (and (= mode "long") (not landscape)) (and (= mode "short") landscape))
        left (if horizontal left 0)
        right (if horizontal right 0)
        top (if vertical top 0)
        bottom (if vertical bottom 0)]
    [left bottom (max 0 (- width left right)) (max 0 (- height top bottom))]))

(defn select-layout
  "Matches GetOptimalDisplayProfile at scene creation (DPI 0, Windows device model empty).
  Restrict candidates to layouts declared by this GUI; prefer the same orientation."
  [profiles layouts [width height]]
  (let [matches
        (reduce (fn [matches {:keys [name qualifiers]}]
                  (if-not (contains? layouts name)
                    matches
                    (reduce (fn [matches {:keys [width height device-models] :as qualifier}]
                              (if (or (not (pos? (or width 0))) (not (pos? (or height 0)))
                                      (coll/not-empty device-models))
                                matches
                                (update matches (<= width height) conj [name qualifier])))
                            matches qualifiers)))
                {true [] false []} profiles)
        candidates (or (coll/not-empty (get matches (<= width height)))
                       (get matches (> width height)))
        score (fn [[_ {w :width h :height}]]
                (+ (Math/abs (- 1.0 (/ (* (double width) height) (* w h))))
                   (Math/abs (- 1.0 (/ (/ (double width) height) (/ (double w) h))))))]
    (or (first (reduce (fn [best candidate]
                         (if (or (nil? best) (< (score candidate) (score best))) candidate best))
                       nil candidates))
        "")))

(defn adjust-pose
  "Runtime AdjustPosScale before the parent reference scale is cancelled.
  Return the adjusted pose and local adjust scale separately to preserve rotations."
  [node-pose {:keys [adjust-mode x-anchor y-anchor rect-transform]} reference root-dims rect parent-scale]
  (cond
    (and rect-transform parent-scale) [node-pose [1.0 1.0 1.0]]
    (and rect-transform (not= reference :adjust-reference-disabled))
    (let [[offset-x offset-y width height] rect
          [scene-width scene-height] root-dims
          fit (min (/ (double width) scene-width) (/ (double height) scene-height))
          [x y z] (:translation node-pose)]
      [(assoc node-pose :translation [(+ offset-x (* x fit)) (+ offset-y (* y fit)) z]
                       :scale (mapv * (:scale node-pose) [fit fit 1.0]))
       [fit fit 1.0]])
    (or (= reference :adjust-reference-disabled)
        (and parent-scale (= reference :adjust-reference-legacy)))
    [node-pose [1.0 1.0 1.0]]
    :else
    (let [[offset-x offset-y width height] rect
          [scene-width scene-height] root-dims
          [rx ry] (or parent-scale [(/ (double width) scene-width) (/ (double height) scene-height)])
          [sx sy] (case adjust-mode
                    :adjust-mode-stretch [rx ry]
                    :adjust-mode-zoom (let [s (max rx ry)] [s s])
                    (let [s (min rx ry)] [s s]))
          [x y z] (:translation node-pose)
          anchored-x (contains? #{:xanchor-left :xanchor-right} x-anchor)
          anchored-y (contains? #{:yanchor-top :yanchor-bottom} y-anchor)
          x (+ (* x (if anchored-x rx sx))
               (if parent-scale 0.0 (+ offset-x (if anchored-x 0.0 (* 0.5 (- width (* scene-width sx)))))))
          y (+ (* y (if anchored-y ry sy))
               (if parent-scale 0.0 (+ offset-y (if anchored-y 0.0 (* 0.5 (- height (* scene-height sy)))))))]
      [(assoc node-pose :translation [x y z] :scale (mapv * (:scale node-pose) [sx sy 1.0]))
       [sx sy 1.0]])))

(defn- adapt-scene-impl [value reference root-dims rect parent-scale template-pose overrides]
  (let [layout (when (contains? value :pose) (:gui-layout value))
        node-overrides (get overrides (:node-id value))
        node-pose (cond-> (get value :pose pose/default)
                    (:position node-overrides) (assoc :translation (:position node-overrides))
                    (:rotation node-overrides) (assoc :rotation (:rotation node-overrides))
                    (:scale node-overrides) (assoc :scale (:scale node-overrides)))
        node-pose (pose/pre-multiply node-pose template-pose)]
    (cond
      (= :type-template (:type layout))
      ;; The GUI compiler removes template nodes and bakes their poses into the
      ;; imported roots. Keep the grouping only for visibility and clipping.
      (assoc value :pose pose/default
             :children (mapv #(adapt-scene-impl % reference root-dims rect parent-scale node-pose overrides)
                             (:children value)))

      layout
      (let [[adjusted local-scale] (adjust-pose node-pose layout reference root-dims rect parent-scale)
            result (assoc value :pose adjusted
                          :children (mapv #(adapt-scene-impl % reference root-dims rect local-scale pose/default overrides)
                                          (:children value)))]
        (if (and (not (:rect-transform layout)) parent-scale (= reference :adjust-reference-parent))
          ;; S^-1 * R * S cannot be represented by one pose when rotated. A
          ;; separate scale parent reproduces UpdateLocalTransform exactly.
          {:node-id (:node-id value)
           :pose (assoc pose/default :scale (mapv #(if (zero? %) 0.0 (/ 1.0 %)) parent-scale))
           :children [result]}
          result))

      :else
      (update value :children
              (fn [children]
                (mapv #(adapt-scene-impl % reference root-dims rect parent-scale template-pose overrides) children))))))

(defn adapt-scene [value reference root-dims rect overrides]
  (adapt-scene-impl value reference root-dims rect nil pose/default overrides))

(defn render-data
  "Evaluate the selected runtime layout in a private basis, then adapt its geometry.
  Neither preview layout changes nor animation properties reach undo or disk."
  [resource-node view config physical-size insets auto-layout evaluation-context]
  (let [layout (if auto-layout (select-layout (:profiles config) (:layouts config) physical-size) "")
        animation (g/node-value view :animation-preview-overrides evaluation-context)
        overrides (merge-with coll/merge animation (g/node-value view :preview-overrides evaluation-context))
        steps (into (vec (g/set-property resource-node :visible-layout layout))
                    (gui-animation-preview/graph-property-steps overrides evaluation-context layout))
        report (g/transact {:dry-run true} steps)
        context (g/make-evaluation-context {:basis (:basis report)
                                           :cache (cache/cache-invalidate (g/cache) (:outputs-modified report))})
        {:keys [width height]} (g/node-value resource-node :scene-dims context)
        rect (safe-rect physical-size insets (get-in config [:settings ["gui" "safe_area_mode"]]))
        fit (max 1.0e-9 (min (/ (double (nth rect 2)) (max 1 width))
                             (/ (double (nth rect 3)) (max 1 height))))
        rect-dims (if (= :adjust-reference-disabled (:adjust-reference config))
                    {:width width :height height}
                    {:width (/ (double (nth rect 2)) fit) :height (/ (double (nth rect 3)) fit)})
        report (g/transact {:dry-run true}
                           (conj steps (g/set-property resource-node :preview-rect-dims rect-dims)))
        context (g/make-evaluation-context {:basis (:basis report)
                                           :cache (cache/cache-invalidate (g/cache) (:outputs-modified report))})
        value (gui-animation-preview/apply-preview (g/node-value resource-node :scene context) overrides)
        value (adapt-scene value (:adjust-reference config) [(max 1 width) (max 1 height)] rect overrides)]
    (assoc (scene/produce-scene-render-data
      {;; Geometry and alpha have already been evaluated and adapted above.
       ;; Reapplying the scene callback would discard the simulated device size.
       :scene (dissoc value :animation-preview-fn)
       :preview-overrides (into {} (map (fn [[id props]] [id (dissoc props :position :rotation :scale)])) overrides)
       :animation-preview-overrides {}
       :selection []
       :hidden-renderable-tags #{}
       :hidden-node-outline-key-paths #{}
       :local-camera (g/node-value view :local-camera evaluation-context)})
      :gui-preview-info {:layout layout
                         :reference-size [width height]
                         :device-size physical-size
                         :safe-rect rect})))
