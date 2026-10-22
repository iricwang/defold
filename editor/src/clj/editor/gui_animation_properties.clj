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

(ns editor.gui-animation-properties
  "Panthera GUI channels mapped to existing editor graph properties."
  (:require [clojure.string :as string]
            [dynamo.graph :as g]
            [editor.gui-animation-data :as animation]
            [editor.math :as math]
            [editor.pose :as pose]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(def go-channels
  ["position_x" "position_y" "position_z"
   "rotation_x" "rotation_y" "rotation_z"
   "scale_x" "scale_y" "scale_z"])

(defn collection-node-ids
  "All GO instances, addressed by the same nested paths as Panthera create_go."
  [resource-node]
  (g/node-value resource-node :go-inst-ids))

(defn collection-context
  "Runtime-local GO poses. Collection transforms are baked, inner to outer, only
  into root GOs; actual GO parenting remains intact, as in collection-common."
  [scene node-ids]
  (let [ids (into #{} (map second) node-ids)
        result (volatile! {})]
    (letfn [(visit! [scene collections parent]
              (let [id (:node-id scene)
                    go (and (contains? ids id) (not= parent id))
                    collection (= :editor.collection/CollectionInstanceNode (g/node-type-kw id))
                    collections (cond-> collections collection (conj (:pose scene)))
                    local (when go (if parent (:pose scene)
                                       (reduce pose/pre-multiply (:pose scene) (rseq collections))))]
                (when go
                  (vswap! result assoc id {:pose local
                                          :runtime-root (and (nil? parent) (pos? (count collections)))}))
                (run! #(visit! % collections (if go id parent)) (:children scene))))]
      (visit! scene [] nil))
    @result))

(defn go-pose-values [local]
  (zipmap go-channels (into [] cat [(:translation local) (math/clj-quat->euler (:rotation local)) (:scale local)])))

(defn collection-preview-overrides [context values]
  (doseq [[id channels] values
          [channel _] channels]
    (when-not (contains? (set go-channels) channel)
      (throw (ex-info (str "GO preview currently supports position, rotation and scale only: " channel)
                      {:node-id id :channel channel}))))
  (into {}
        (map (fn [[id {:keys [pose runtime-root]}]]
               (let [animated (get values id)
                     channels (merge (go-pose-values pose) animated)
                     xyz (fn [prefix] (mapv #(get channels (str prefix "_" %)) ["x" "y" "z"]))]
                 [id (cond-> {:position (xyz "position")
                              :rotation (if (or (contains? animated "rotation_x")
                                                (contains? animated "rotation_y")
                                                (contains? animated "rotation_z"))
                                          (math/vecmath->clj (math/euler->quat (xyz "rotation")))
                                          (:rotation pose))
                              :scale (xyz "scale")}
                       runtime-root (assoc :animation-runtime-root true))])))
        context))

(def channel-specs
  (into {"color_a" [:alpha nil] "outline_a" [:outline-alpha nil] "shadow_a" [:shadow-alpha nil]
         "inner_radius" [:inner-radius nil] "fill_angle" [:pie-fill-angle nil]
         "text_tracking" [:text-tracking nil] "text_leading" [:text-leading nil]}
        (mapcat (fn [[prefix property components]]
                  (mapv (fn [component index] [(str prefix "_" component) [property index]]) components (range))))
        [["position" :position ["x" "y" "z"]] ["rotation" :rotation ["x" "y" "z"]]
         ["scale" :scale ["x" "y" "z"]] ["size" :manual-size ["x" "y" "z"]]
         ["color" :color ["r" "g" "b"]] ["outline" :outline ["r" "g" "b"]]
         ["shadow" :shadow ["r" "g" "b"]] ["slice9" :slice9 ["left" "top" "right" "bottom"]]]))

(def trigger-specs
  {"text" [:text :string] "layer" [:layer :string] "texture" [:texture :string]
   "enabled" [:enabled :boolean] "visible" [:visible :boolean] "inherit_alpha" [:inherit-alpha :boolean]
   "line_break" [:line-break :boolean] "clipping_visible" [:clipping-visible :boolean]
   "clipping_inverted" [:clipping-inverted :boolean] "pivot" [:pivot :pivot]
   "blend_mode" [:blend-mode :blend-mode] "size_mode" [:size-mode :size-mode]
   "clipping_mode" [:clipping-mode :clipping-mode] "outer_bounds" [:outer-bounds :bounds]
   "perimeter_verticies" [:perimeter-vertices :integer]})

(defn numeric-channels [node-id]
  (let [type (g/node-type* node-id)]
    (into [] (filter #(g/has-property? type (first (get channel-specs %)))) animation/supported-properties)))

(defn node-values [node-id]
  (into {}
        (map (fn [channel]
               (let [[property index] (get channel-specs channel)
                     value (g/node-value node-id property)
                     value (if (= :rotation property) (math/clj-quat->euler value) value)]
                 [channel (if (nil? index) value (nth value index))])))
        (numeric-channels node-id)))

(defn- trigger-value [type value]
  (case type
    :boolean (case value "true" true "false" false true true false false
                   (throw (ex-info "Boolean triggers require true or false." {:value value})))
    :integer (Long/parseLong (str value))
    :pivot (keyword (string/replace (str value) "_" "-"))
    :blend-mode (get {"alpha" :blend-mode-alpha "add" :blend-mode-add "multiply" :blend-mode-mult "screen" :blend-mode-screen} value)
    :size-mode (get {"auto" :size-mode-auto "manual" :size-mode-manual} value)
    :clipping-mode (get {"none" :clipping-mode-none "stencil" :clipping-mode-stencil} value)
    :bounds (get {"ellipse" :pie-bounds-ellipse "rectangle" :pie-bounds-rectangle} value)
    (str value)))

(defn graph-properties [node-id channels]
  (let [type (g/node-type* node-id)]
    (reduce-kv (fn [result channel value]
                 (let [[property index] (get channel-specs channel)
                       [trigger-property trigger-type] (get trigger-specs channel)
                       property (or property trigger-property)]
                   (when-not (and property (g/has-property? type property))
                     (throw (ex-info (str "Property is unavailable on this GUI node: " channel) {:node-id node-id})))
                   (if trigger-property
                     (let [value (trigger-value trigger-type value)]
                       (when (nil? value) (throw (ex-info "Unknown trigger option." {:channel channel})))
                       (assoc result property value))
                     (if (nil? index)
                       (assoc result property value)
                       (let [original (get result property (g/node-value node-id property))
                             original (if (and (= :rotation property) (not (contains? result property)))
                                        (math/clj-quat->euler original) original)]
                         (assoc result property (assoc original index value)))))))
               {} channels)))

(defn node-overrides [node-id channels]
  (let [properties (graph-properties node-id channels)
        properties (cond-> properties
                     (:rotation properties) (update :rotation #(math/vecmath->clj (math/euler->quat %))))
        extended (dissoc properties :position :rotation :scale :alpha)
        result (select-keys properties [:position :rotation :scale :alpha])]
    (cond-> result
      (pos? (count extended)) (assoc :graph-properties properties))))

(defn go-node-overrides
  "GO transforms use local space and Euler degrees, matching Panthera create_go.
  Reject component channels rather than silently previewing incompatible data."
  [node-id channels]
  (doseq [[channel _] channels]
    (when-not (contains? (set go-channels) channel)
      (throw (ex-info (str "GO preview currently supports position, rotation and scale only: " channel)
                      {:node-id node-id :channel channel}))))
  (node-overrides node-id channels))
