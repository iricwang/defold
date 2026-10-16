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

(ns editor.gui-animation-preview
  (:require [dynamo.graph :as g]
            [editor.math :as math])
  (:import [javax.vecmath Quat4d]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn- apply-preview-impl [scene node-overrides ^double parent-alpha]
  (let [user-data (get-in scene [:renderable :user-data])
        color (:color user-data)
        overrides (get node-overrides (:node-id scene))
        local-alpha (double (if color
                              (get overrides :alpha (::local-alpha scene (get color 3 1.0)))
                              1.0))
        inherit-alpha (get user-data :inherit-alpha true)
        alpha (if-let [alpha-factors (and color (::alpha-factors scene))]
                ;; Templates can arrive with alpha already composed. Preserve
                ;; the original factors instead of multiplying that result by
                ;; its ancestors again or dividing by a potentially zero alpha.
                (reduce (fn [^double product [node-id value]]
                          (* product (double (get-in node-overrides [node-id :alpha] value))))
                        1.0
                        alpha-factors)
                (if inherit-alpha (* parent-alpha local-alpha) local-alpha))
        color (when color
                (assoc (get overrides :color color) 3 alpha))]
    (cond-> scene
      color
      (assoc-in [:renderable :user-data :color] color)

      (and (:text-data user-data) (contains? overrides :alpha))
      (assoc-in [:renderable :user-data :text-data :color 3] local-alpha)

      (and (:text-data user-data) (contains? overrides :color))
      (update-in [:renderable :user-data :text-data :color]
                 (fn [text-color]
                   (assoc (:color overrides) 3 (get text-color 3 1.0))))

      (contains? scene :children)
      (update :children (fn [children]
                          (mapv #(apply-preview-impl % node-overrides alpha) children))))))

(defn apply-preview
  "Recomputes inherited GUI alpha in a scene value without changing resource nodes.
  Local alpha is retained before normal scene composition, so fully transparent
  nodes can be previewed without dividing by their original alpha."
  [scene node-overrides]
  (apply-preview-impl scene node-overrides 1.0))

(defn apply-full-preview
  "Re-evaluate geometry/text/trigger changes against a disposable graph basis.
  No transaction is committed and the preview context never enters the system cache."
  [scene node-overrides]
  (let [steps (into []
                    (mapcat (fn [[node-id overrides]]
                              (into [] (mapcat (fn [[property value]] (g/set-property node-id property value)))
                                    (:graph-properties overrides))))
                    node-overrides)]
    (if (zero? (count steps))
      (apply-preview scene node-overrides)
      (let [report (g/transact {:dry-run true} steps)
            context (g/make-evaluation-context {:basis (:basis report)})
            preview (g/node-value (:node-id scene) :scene context)]
        (if (g/error? preview)
          (throw (ex-info "The animated GUI properties cannot be rendered." {}))
          preview)))))

(defn transform-value
  "Applies a scene gizmo delta to the displayed local pose, never the GUI resource."
  [property value delta]
  (case property
    :position (mapv + value (math/vecmath->clj delta))
    :scale (mapv * value (math/vecmath->clj delta))
    :rotation (math/vecmath->clj (doto (Quat4d.) (math/clj->vecmath value) (.mul ^Quat4d delta) (.normalize)))))

(defn transform-channels [property value]
  (let [[prefix components] (case property
                              :position ["position" value]
                              :scale ["scale" value]
                              :rotation ["rotation" (math/clj-quat->euler value)])]
    (zipmap (mapv #(str prefix "_" %) ["x" "y" "z"]) components)))
