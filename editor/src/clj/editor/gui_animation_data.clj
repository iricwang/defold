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

(ns editor.gui-animation-data
  "Lossless Panthera JSON storage and scalar GUI animation editing. Times are
  seconds and rotation values are Euler degrees, as in Panthera's GUI adapter."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.gui-animation-lua :as lua]
            [util.coll :as coll]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(def supported-properties
  ["position_x" "position_y" "position_z"
   "rotation_x" "rotation_y" "rotation_z"
   "scale_x" "scale_y" "scale_z"
   "size_x" "size_y" "size_z"
   "color_r" "color_g" "color_b" "color_a"
   "outline_r" "outline_g" "outline_b" "outline_a"
   "shadow_r" "shadow_g" "shadow_b" "shadow_a"
   "slice9_left" "slice9_top" "slice9_right" "slice9_bottom"
   "inner_radius" "fill_angle" "text_tracking" "text_leading"])

(def trigger-properties
  ["text" "layer" "texture" "enabled" "visible" "inherit_alpha" "pivot"
   "blend_mode" "size_mode" "clipping_mode" "clipping_visible" "clipping_inverted"
   "line_break" "outer_bounds" "perimeter_verticies"])

(def supported-easings
  (into ["linear"]
        (mapcat (fn [family] (mapv #(str % family) ["in" "out" "inout" "outin"])))
        ["quad" "cubic" "quart" "quint" "sine" "expo" "circ" "back" "bounce" "elastic"]))

(defn key-kind [key]
  (get {0 "trigger" 1 "tween" 2 "event" 3 "animation"} (:key_type key) (:key_type key)))

(defn key-easing [key]
  (or (:easing_custom key) (:easing key) "linear"))

(defn- require-value! [valid message data]
  (when-not valid
    (throw (ex-info message data))))

(defn- finite-number? [value]
  (and (number? value) (Double/isFinite (double value))))

(defn- nonnegative-number? [value]
  (and (finite-number? value) (not (neg? (double value)))))

(defn- same-time? [^double a ^double b]
  (< (Math/abs (- a b)) 1.0e-9))

(defn- key-order [key]
  ;; Panthera sorts ties by duration, property, node, and then end value.
  [(double (get key :start_time 0))
   (double (get key :duration 0))
   (get key :property_id "")
   (get key :node_id "")
   (if (finite-number? (:end_value key)) (double (:end_value key)) 0.0)])

(defn- animation-index [document animation-id]
  (or (reduce-kv (fn [_ index animation]
                   (when (= animation-id (:animation_id animation))
                     (reduced index)))
                 nil
                 (get-in document [:data :animations]))
      (throw (ex-info (str "Animation not found: " animation-id)
                      {:animation-id animation-id}))))

(defn- get-animation [document animation-id]
  (get-in document [:data :animations (animation-index document animation-id)]))

(defn- validate-document [document]
  (require-value! (and (map? document)
                       (= "animation_editor" (:type document))
                       (map? (:data document))
                       (vector? (get-in document [:data :animations])))
                  "Expected a Panthera animation_editor JSON document."
                  {})
  (reduce (fn [ids animation]
            (let [id (:animation_id animation)]
              (require-value! (and (string? id) (not (string/blank? id))
                                   (not (contains? ids id)))
                              "Animation names must be nonempty and unique."
                              {:animation-id id})
              (require-value! (nonnegative-number? (:duration animation))
                              "Animation duration must be a finite nonnegative number."
                              {:animation-id id})
              (require-value! (vector? (:animation_keys animation))
                              "Animation keys must be an array."
                              {:animation-id id})
              (run! (fn [key]
                      (require-value! (map? key) "An animation key must be an object."
                                      {:animation-id id})
                      (run! (fn [field]
                              (when (contains? key field)
                                (require-value! (nonnegative-number? (get key field))
                                                "Key times must be finite nonnegative numbers."
                                                {:animation-id id :field field})))
                            [:start_time :duration]))
                    (:animation_keys animation))
              (conj ids id)))
          #{}
          (get-in document [:data :animations]))
  document)

(defn new-document
  "Create a Panthera project containing an empty one-second default animation."
  [gui-path]
  (require-value! (string? gui-path) "A GUI resource path is required." {})
  {:type "animation_editor"
   :format "json"
   :version 1
   :data {:name "GUI animations"
          :nodes {}
          :animations [{:animation_id "default"
                        :duration 1.0
                        :animation_keys []}]
          :metadata {:gui_path gui-path
                     :fps 60
                     :settings {}
                     :gizmo_steps {}
                     :template_animation_paths {}}}})

(defn read-json
  "Read Panthera JSON without discarding extensions or unsupported tracks."
  [text]
  (validate-document (json/read-str text :key-fn keyword)))

(defn read-source [text resolve-template]
  (if (string/starts-with? (string/triml text) "{")
    (read-json text)
    (let [document (lua/read-data text resolve-template)]
      (validate-document
        (update-in (assoc document :format "json") [:data :animations]
                   (fn [animations]
                     (mapv #(update % :animation_keys (fn [keys] (if (= {} keys) [] keys)))
                           (if (= {} animations) [] animations))))))))

(defn write-json [document]
  (str (json/write-str (validate-document document)
                       :key-fn #(if (keyword? %) (subs (str %) 1) %)
                       :escape-slash false
                       :indent true)
       "\n"))

(defn animation-ids [document]
  (mapv :animation_id (get-in document [:data :animations])))

(defn animation-duration [document animation-id]
  (:duration (get-animation document animation-id)))

(defn add-animation [document animation-id duration]
  (require-value! (and (string? animation-id) (not (string/blank? animation-id))
                       (not (contains? (set (animation-ids document)) animation-id)))
                  "Animation names must be nonempty and unique."
                  {:animation-id animation-id})
  (require-value! (nonnegative-number? duration)
                  "Animation duration must be a finite nonnegative number." {})
  (update-in document [:data :animations] conj
             {:animation_id animation-id :duration duration :animation_keys []}))

(defn set-duration [document animation-id duration]
  (require-value! (nonnegative-number? duration)
                  "Animation duration must be a finite nonnegative number." {})
  (let [index (animation-index document animation-id)
        keys (get-in document [:data :animations index :animation_keys])
        last-time (reduce (fn [^double result key]
                            (max result (+ (double (get key :start_time 0))
                                           (double (get key :duration 0)))))
                          0.0
                          keys)]
    (require-value! (>= (double duration) (double last-time))
                    "Duration cannot end before an existing animation key."
                    {:last-key-time last-time})
    (assoc-in document [:data :animations index :duration] duration)))

(defn- easing-valid? [easing]
  (if-not (vector? easing)
    (contains? (set supported-easings) easing)
    (and (<= 2 (count easing)) (not (coll/any? (complement finite-number?) easing)))))

(defn- key-issues [key]
  (let [kind (key-kind key)]
    (cond-> []
      (not (contains? #{"trigger" "tween" "event" "animation"} kind))
      (conj "Unknown animation key type.")

      (and (contains? #{"tween" "event"} kind)
           (not (and (finite-number? (get key :start_value 0))
                     (finite-number? (get key :end_value 0)))))
      (conj "Tween start and end values must be finite numbers for preview and editing.")

      (and (= "tween" kind) (not (contains? (set supported-properties) (:property_id key))))
      (conj (str "Unsupported GUI property: " (:property_id key)))

      (and (= "trigger" kind) (not (contains? (set trigger-properties) (:property_id key))))
      (conj (str "Unsupported trigger property: " (:property_id key)))

      (and (contains? #{"tween" "trigger"} kind)
           (or (not (string? (:node_id key))) (string/blank? (:node_id key))))
      (conj "A value key must identify a GUI node.")

      (not (easing-valid? (key-easing key)))
      (conj "Unsupported easing or invalid custom curve."))))

(defn animation-keys [document animation-id]
  (:animation_keys (get-animation document animation-id)))

(defn template-document [document node-id]
  (let [templates (get-in document [:data :metadata :template_animation_paths])]
    (get templates (keyword node-id) (get templates node-id))))

(defn- reference-issues-impl [document animation-id visited]
  (let [identity [document animation-id]]
    (cond
      (contains? visited identity) ["Animation references form a cycle."]
      (> (count visited) 16) ["Animation references exceed 16 levels."]
      (not (contains? (set (animation-ids document)) animation-id)) [(str "Animation reference is missing: " animation-id)]
      :else
      (let [animation (get-animation document animation-id)
            visited (conj visited identity)
            initial (:initial_state animation)
            issues (if (string/blank? initial) [] (reference-issues-impl document initial visited))]
        (into issues
              (mapcat (fn [key]
                        (into (key-issues key)
                              (when (= "animation" (key-kind key))
                                (let [child (if (string/blank? (:node_id key)) document (template-document document (:node_id key)))]
                                  (if (map? child)
                                    (reference-issues-impl child (:property_id key) visited)
                                    [(str "Template animation must be bound and loaded: " (:node_id key))]))))))
              (:animation_keys animation))))))

(defn preview-issues
  "Validate keys, templates and references before sampling. Events are inspected,
  never executed as game callbacks in the editor."
  [document animation-id]
  (into [] (distinct) (reference-issues-impl document animation-id #{})))

(defn- resolve-templates-impl [document load-template visited]
  (update-in document [:data :metadata :template_animation_paths]
             (fn [templates]
               (into {} (map (fn [[node-id target]]
                               [node-id (if (map? target)
                                          (resolve-templates-impl target load-template visited)
                                          (do
                                            (require-value! (and (string? target) (not (contains? visited target)) (< (count visited) 16))
                                                            "Invalid or cyclic template animation file reference." {:path target})
                                            (resolve-templates-impl (load-template target) load-template (conj visited target))))])) templates))))

(defn resolve-templates [document load-template]
  (resolve-templates-impl document load-template #{}))

(defn- resolve-preview-impl [document animation-id load-template visited identity]
  (let [token [identity animation-id]]
    (require-value! (and (not (contains? visited token)) (< (count visited) 16)) "Invalid or cyclic animation reference." {})
    (let [clip (get-animation document animation-id)
          visited (conj visited token)
          local-refs (into (if (string/blank? (:initial_state clip)) [] [(:initial_state clip)])
                           (comp (filter #(and (= "animation" (key-kind %)) (string/blank? (:node_id %)))) (map :property_id))
                           (:animation_keys clip))
          document (reduce #(resolve-preview-impl %1 %2 load-template visited identity) document local-refs)]
      (reduce (fn [document key]
                (if (and (= "animation" (key-kind key)) (not (string/blank? (:node_id key))))
                  (let [node (:node_id key)
                        target (template-document document node)
                        child (if (map? target) target (load-template target))
                        child (resolve-preview-impl child (:property_id key) load-template visited [identity node])]
                    (assoc-in document [:data :metadata :template_animation_paths (keyword node)] child))
                  document)) document (:animation_keys clip)))))

(defn resolve-preview
  "Load only templates reachable from the active clip, keeping unrelated missing files harmless."
  [document animation-id load-template]
  (resolve-preview-impl document animation-id load-template #{} :root))

(defn bind-template [document node-id path]
  (require-value! (and (not (string/blank? node-id)) (string? path) (string/starts-with? path "/")
                       (or (string/ends-with? path ".json") (string/ends-with? path ".lua")))
                  "Bind a template node to a project .json or .lua animation resource." {})
  (assoc-in document [:data :metadata :template_animation_paths (keyword node-id)] path))

(defn- track-keys [animation node-id property-id]
  (into []
        (sort-by key-order
                 (into []
                       (filter #(and (= node-id (:node_id %))
                                     (= property-id (:property_id %))))
                       (:animation_keys animation)))))

(defn- editable-points [animation node-id property-id]
  (let [keys (track-keys animation node-id property-id)]
    (run! (fn [key]
            (require-value! (= "tween" (key-kind key))
                            "This track includes non-tween segments; edit it in Events / Segments." {})
            (when-let [issue (first (key-issues key))]
              (throw (ex-info issue {:node-id node-id :property-id property-id}))))
          keys)
    (if-let [first-key (first keys)]
      (reduce (fn [points key]
                (let [start (double (get key :start_time 0))
                      end (+ start (double (get key :duration 0)))
                      previous (peek points)]
                  (require-value! (and (same-time? start (double (:time previous)))
                                       (== (double (get key :start_value 0))
                                           (double (:value previous))))
                                  "This imported track contains gaps, overlapping keys or discontinuities; it can be previewed but cannot be edited as keyframes."
                                  {:node-id node-id :property-id property-id})
                  (let [point {:time end
                               :value (get key :end_value 0)
                               :easing (key-easing key)
                               :key key}]
                    (conj (if (same-time? start end) (pop points) points) point))))
              [{:time (get first-key :start_time 0)
                :value (get first-key :start_value 0)
                :easing "linear"
                :key nil}]
              keys)
      [])))

(defn keyframes
  "Return scalar keyframes as {:time seconds :value number :easing string}.
  Easing belongs to the interval ending at the keyframe. Complex imported tracks
  are deliberately read-only instead of being silently flattened."
  [document animation-id node-id property-id]
  (mapv #(dissoc % :key)
        (editable-points (get-animation document animation-id) node-id property-id)))

(defn- replace-track [document animation-id node-id property-id points]
  (let [index (animation-index document animation-id)
        old-keys (get-in document [:data :animations index :animation_keys])
        new-keys (if (= 1 (count points))
                   (let [{:keys [time value easing key]} (first points)]
                     [(assoc (dissoc key :easing_custom) :key_type "tween" :node_id node-id :property_id property-id
                             :start_time time :duration 0.0 :start_value value :end_value value :easing (if (vector? easing) "linear" easing)
                             :easing_custom (when (vector? easing) easing))])
                   (reduce-kv (fn [result point-index {:keys [time value easing key]}]
                                (if (zero? (long point-index))
                                  result
                                  (let [previous (nth points (dec (long point-index)))]
                                    (conj result
                                          (assoc (dissoc key :easing_custom) :key_type "tween" :node_id node-id :property_id property-id
                                                 :start_time (:time previous)
                                                 :duration (- (double time) (double (:time previous)))
                                                 :start_value (:value previous) :end_value value :easing (if (vector? easing) "linear" easing)
                             :easing_custom (when (vector? easing) easing))))))
                              (if-let [key (:key (first points))]
                                (let [{:keys [time value easing]} (first points)]
                                  [(assoc (dissoc key :easing_custom) :key_type "tween" :node_id node-id :property_id property-id
                                          :start_time time :duration 0.0 :start_value value :end_value value :easing (if (vector? easing) "linear" easing)
                             :easing_custom (when (vector? easing) easing))])
                                [])
                              points))
        combined (into (into []
                             (remove #(and (= node-id (:node_id %))
                                           (= property-id (:property_id %))))
                             old-keys)
                       (mapv #(if (nil? (:easing_custom %)) (dissoc % :easing_custom) %) new-keys))]
    (-> document
        (assoc-in [:data :animations index :animation_keys]
                  (into [] (sort-by key-order combined)))
        (update-in [:data :animations index :duration]
                   #(max (double %) (double (or (:time (peek points)) 0)))))))

(defn set-keyframe
  "Insert or replace a scalar keyframe, extending duration when needed. Capture
  an initial key at time zero before recording later poses. Other tracks and
  imported key metadata are preserved."
  [document animation-id node-id property-id time value easing]
  (require-value! (and (string? node-id) (not (string/blank? node-id)))
                  "Select a GUI node before recording a keyframe." {})
  (require-value! (contains? (set supported-properties) property-id)
                  "Unsupported GUI animation property." {:property-id property-id})
  (require-value! (nonnegative-number? time) "Key time must be finite and nonnegative." {})
  (require-value! (finite-number? value) "Key value must be finite." {})
  (require-value! (easing-valid? easing)
                  "Unsupported animation easing." {:easing easing})
  (let [points (editable-points (get-animation document animation-id) node-id property-id)
        existing (reduce (fn [_ point]
                           (when (same-time? (double time) (double (:time point)))
                             (reduced point)))
                         nil
                         points)
        point (assoc existing :time time :value value :easing easing)
        updated (conj (into [] (remove #(same-time? (double time) (double (:time %)))) points) point)]
    (replace-track document animation-id node-id property-id
                   (into [] (sort-by :time updated)))))

(defn remove-keyframe [document animation-id node-id property-id time]
  (require-value! (nonnegative-number? time) "Key time must be finite and nonnegative." {})
  (replace-track document animation-id node-id property-id
                 (into []
                       (remove #(same-time? (double time) (double (:time %))))
                       (editable-points (get-animation document animation-id) node-id property-id))))

(defn move-keyframe
  "Move a complete keyframe, preserving its easing and imported metadata. A key
  already at the destination is replaced. Moving to the start keeps an explicit
  zero-duration initial key so its metadata still has a serialized owner."
  [document animation-id node-id property-id old-time new-time]
  (require-value! (and (nonnegative-number? old-time) (nonnegative-number? new-time))
                  "Key times must be finite and nonnegative." {})
  (let [points (editable-points (get-animation document animation-id) node-id property-id)
        point (reduce (fn [_ point]
                        (when (same-time? (double old-time) (double (:time point)))
                          (reduced point)))
                      nil
                      points)]
    (require-value! point "The keyframe to move no longer exists."
                    {:node-id node-id :property-id property-id :time old-time})
    (let [updated (conj (into []
                              (remove #(or (same-time? (double old-time) (double (:time %)))
                                           (same-time? (double new-time) (double (:time %)))))
                              points)
                        (assoc point :time new-time))]
      (replace-track document animation-id node-id property-id
                     (into [] (sort-by :time updated))))))

(def ^:private engine-easings
  (edn/read-string (slurp (io/resource "gui_animation_easings.edn"))))

(defn runtime-document
  "Bake named engine curves into Panthera's portable sampled easing format.
  Panthera's tweener-based seek and event paths otherwise use different curves
  from gui.animate, including non-finite elastic samples in tweener 6. Editing
  documents keep their named curves; existing custom curves are preserved."
  [document]
  (update-in (validate-document document) [:data :animations]
             (fn [animations]
               (mapv #(update % :animation_keys
                              (fn [keys]
                                (mapv (fn [key]
                                        (if (and (nil? (:easing_custom key))
                                                 (not= "linear" (:easing key))
                                                 (contains? #{"tween" "event" "animation"} (key-kind key)))
                                          (if-let [samples (get engine-easings (:easing key))]
                                            (assoc key :easing_custom samples)
                                            key)
                                          key))
                                      keys)))
                     animations))))

(defn ease
  "Sample the same 64-point built-in curves used by Defold's GUI runtime.
  Custom curves retain their own samples, including non-unit endpoints."
  ^double [easing ^double t]
  (if (= "linear" easing) t
    (let [samples (if (vector? easing) easing (get engine-easings easing))
        position (* (max 0.0 (min 1.0 t)) (dec (count samples)))
        index (min (- (count samples) 2) (long position))
        a (double (nth samples index))
        b (double (nth samples (inc index)))]
    (+ a (* (- b a) (- position index))))))

(defn- sample-key [key ^double time]
  (let [start (double (get key :start_time 0))]
    (if (= "trigger" (key-kind key))
      (if (< time start) (:start_data key) (:data key))
      (let [duration (double (get key :duration 0))
            from (double (get key :start_value 0))
            to (double (get key :end_value 0))]
        (cond
          (< time start) from
          (or (zero? duration) (> time (+ start duration))) to
          :else (+ from (* (- to from) (ease (key-easing key) (/ (- time start) duration)))))))))

(defn- started? [key ^double time]
  (let [start (double (get key :start_time 0))]
    (if (and (= "animation" (key-kind key)) (pos? (double (get key :duration 0))) (pos? start))
      (< start time)
      (<= start time))))

(defn- collect-values-impl [document animation-id time context]
  (let [{:keys [offset scale depth prefix]} context
        animation (get-animation document animation-id)
        time (min (double time) (double (:duration animation)))
        groups (sort-by (comp key-order first)
                        (mapv #(into [] (sort-by key-order %)) (coll/vals (group-by (juxt :node_id :property_id) (:animation_keys animation)))))
        initial (:initial_state animation)
        initial-values (if (or (neg? time) (string/blank? initial)) []
                         (collect-values-impl document initial (animation-duration document initial)
                                              (assoc context :scale 0.0 :depth (inc (long depth)))))
        own (into initial-values
                  (mapcat (fn [keys]
                            (let [first-key (first keys)
                                  active (reduce (fn [active key] (if (started? key time) key (reduced active))) nil keys)
                                  key (or active first-key)
                                  kind (key-kind key)]
                              (if (and (contains? #{"tween" "trigger"} kind)
                                       (or active (neg? time) (= 1 (long depth))))
                                (let [value (if active (sample-key key time)
                                                (if (= "trigger" kind) (:start_data key) (double (get key :start_value 0))))]
                                  (if (nil? value) []
                                    [{:node (str prefix (:node_id key)) :property (:property_id key) :value value
                                      :root-start (+ (double offset) (* (double scale) (double (if active (get key :start_time 0) 0))))
                                      :source (if active 1 0)}])) [])))) groups)]
    (into own
          (mapcat (fn [keys]
                    (let [key (first keys)]
                      (when (= "animation" (key-kind key))
                        (let [active (reduce (fn [active key] (if (started? key time) key (reduced active))) nil keys)
                              node (get key :node_id "")
                              child-document (if (string/blank? node) document (template-document document node))
                              child (:property_id key)
                              child-duration (double (animation-duration child-document child))
                              key-duration (double (get active :duration 0))
                              key-start (double (get active :start_time 0))
                              local-time (if-not active -1.0
                                           (if (or (zero? key-duration) (zero? child-duration)) child-duration
                                             (* child-duration (ease (key-easing active)
                                                                        (min 1.0 (/ (- time key-start) key-duration))))))
                              child-context {:prefix (str prefix (when-not (string/blank? node) (str node "/")))
                                             :offset (+ (double offset) (* key-start (double scale)))
                                             :scale (if (or (nil? active) (zero? key-duration) (zero? child-duration)) 0.0
                                                      (* (double scale) (/ key-duration child-duration)))
                                             :depth (inc (long depth))}]
                          (collect-values-impl child-document child local-time child-context)))))) groups)))

(defn evaluate
  "Sample Panthera's last-started root-time precedence, including initial,
  nested and template clips. Events never execute game callbacks in the editor."
  [document animation-id time]
  (require-value! (finite-number? time) "Preview time must be finite." {})
  (when-let [issue (first (preview-issues document animation-id))]
    (throw (ex-info issue {:animation-id animation-id})))
  (reduce (fn [values {:keys [node property value]}] (assoc-in values [node property] value))
          {} (sort-by (juxt :root-start :source)
                      (collect-values-impl document animation-id (max 0.0 (double time))
                                           {:offset 0.0 :scale 1.0 :depth 1 :prefix ""}))))

(defn rename-animation [document old-id new-id]
  (require-value! (and (not (string/blank? new-id))
                       (or (= old-id new-id) (not (contains? (set (animation-ids document)) new-id))))
                  "Animation names must be nonempty and unique." {})
  (animation-index document old-id)
  (update-in document [:data :animations]
             (fn [clips]
               (mapv (fn [clip]
                       (cond-> (update clip :animation_keys
                                        (fn [keys] (mapv #(if (and (= "animation" (key-kind %)) (string/blank? (:node_id %)) (= old-id (:property_id %)))
                                                           (assoc % :property_id new-id) %) keys)))
                         (= old-id (:animation_id clip)) (assoc :animation_id new-id)
                         (= old-id (:initial_state clip)) (assoc :initial_state new-id))) clips))))

(defn duplicate-animation [document animation-id new-id]
  (let [document (add-animation document new-id 1.0)]
    (assoc-in document [:data :animations (animation-index document new-id)]
              (assoc (get-animation document animation-id) :animation_id new-id))))

(defn remove-animation [document animation-id]
  (require-value! (< 1 (count (animation-ids document))) "Keep at least one animation." {})
  (require-value! (not (coll/any? #(or (= animation-id (:initial_state %))
                                      (coll/any? (fn [key] (and (= "animation" (key-kind key)) (string/blank? (:node_id key))
                                                                                (= animation-id (:property_id key)))) (:animation_keys %)))
                                (into [] (remove #(= animation-id (:animation_id %))) (get-in document [:data :animations]))))
                  "This animation is referenced by another animation." {})
  (animation-index document animation-id)
  (update-in document [:data :animations] #(into [] (remove (fn [clip] (= animation-id (:animation_id clip)))) %)))

(defn set-initial-state [document animation-id initial-id]
  (let [updated (assoc-in document [:data :animations (animation-index document animation-id) :initial_state] initial-id)]
    (require-value! (not (coll/any? #(not (string/starts-with? % "Template animation must be bound")) (reference-issues-impl updated animation-id #{}))) "Invalid or cyclic initial-state reference." {})
    updated))

(defn replace-key
  "Edit a raw Panthera segment by index, preserving extensions and discontinuities."
  [document animation-id index key]
  (let [index-path [:data :animations (animation-index document animation-id) :animation_keys]
        keys (get-in document index-path)
        updated (assoc-in document index-path (if (nil? index) (conj keys key) (assoc keys index key)))
        end (+ (double (get key :start_time 0)) (double (get key :duration 0)))
        updated (update-in updated [:data :animations (animation-index document animation-id) :duration] #(if (> end (double %)) end %))]
    (validate-document updated)
    (when-let [issue (first (key-issues key))] (throw (ex-info issue {})))
    (require-value! (not (coll/any? #(not (string/starts-with? % "Template animation must be bound")) (reference-issues-impl updated animation-id #{}))) "Invalid or cyclic animation reference." {})
    updated))

(defn remove-key [document animation-id index]
  (update-in document [:data :animations (animation-index document animation-id) :animation_keys]
             #(into (subvec % 0 index) (subvec % (inc (long index))))))

(defn event-samples [document animation-id time]
  (into []
        (comp (filter #(and (= "event" (key-kind %)) (<= (double (get % :start_time 0)) (double time))
                           (or (zero? (double (get % :duration 0)))
                               (<= (double time) (+ (double (get % :start_time 0)) (double (get % :duration 0)))))))
              (map #(assoc % :value (sample-key % (double time)))))
        (animation-keys document animation-id)))

(defn selected-keyframes [document animation-id selection]
  (into []
        (mapcat (fn [[node-id property-id]]
                  (into [] (comp (filter #(contains? selection [node-id property-id (:time %)]))
                                 (map #(assoc % :node-id node-id :property-id property-id)))
                        (editable-points (get-animation document animation-id) node-id property-id))))
        (into #{} (map (fn [[node-id property-id _]] [node-id property-id])) selection)))

(defn delete-keyframes [document animation-id selection]
  (reduce (fn [document [node-id property-id]]
            (replace-track document animation-id node-id property-id
                           (into [] (remove #(contains? selection [node-id property-id (:time %)]))
                                 (editable-points (get-animation document animation-id) node-id property-id))))
          document (into #{} (map (fn [[node-id property-id _]] [node-id property-id])) selection)))

(defn paste-keyframes [document animation-id points offset]
  (require-value! (and (finite-number? offset)
                       (not (coll/any? #(neg? (+ (double (:time %)) (double offset))) points)))
                  "Pasted keyframes must have nonnegative times." {})
  (reduce-kv (fn [document [node-id property-id] points]
               (let [moved (mapv #(update % :time (fn [time] (+ (double time) (double offset)))) points)
                     destinations (into #{} (map :time) moved)
                     kept (into [] (remove #(contains? destinations (:time %)))
                                (editable-points (get-animation document animation-id) node-id property-id))]
                 (replace-track document animation-id node-id property-id
                                (into [] (sort-by :time (into kept moved))))))
             document (group-by (juxt :node-id :property-id) points)))

(defn move-keyframes [document animation-id selection offset]
  (let [points (selected-keyframes document animation-id selection)]
    (paste-keyframes (delete-keyframes document animation-id selection) animation-id points offset)))

(defn copy-selection [document animation-id points indices]
  {:points (selected-keyframes document animation-id points)
   :segments (mapv #(nth (animation-keys document animation-id) %) (sort indices))})

(defn delete-selection [document animation-id points indices]
  (delete-keyframes (reduce #(remove-key %1 animation-id %2) document (sort > indices)) animation-id points))

(defn paste-selection [document animation-id selection offset]
  (let [document (paste-keyframes document animation-id (:points selection) offset)]
    (reduce (fn [document key]
              (replace-key document animation-id nil (update key :start_time #(+ (double (or % 0)) (double offset)))))
            document (:segments selection))))

(defn move-selection [document animation-id points indices offset]
  (let [selection (copy-selection document animation-id points indices)]
    (paste-selection (delete-selection document animation-id points indices) animation-id selection offset)))

(defn- lua-string [value]
  (str "\""
       (string/escape value
                      (fn [character]
                        (case character
                          \" "\\\""
                          \\ "\\\\"
                          \newline "\\n"
                          \return "\\r"
                          \tab "\\t"
                          (when (< (long (int character)) 32)
                            (format "\\%03d" (int character))))))
       "\""))

(defn- lua-value [value]
  (cond
    (nil? value) "nil"
    (string? value) (lua-string value)
    (keyword? value) (lua-string (subs (str value) 1))
    (boolean? value) (str value)
    (number? value) (do
                      (require-value! (finite-number? value) "Lua numbers must be finite." {})
                      (str value))
    (vector? value) (str "{" (transduce (interpose ", ") str (mapv lua-value value)) "}")
    (map? value) (str "{"
                      (transduce (comp (map (fn [[key item]]
                                              (str "[" (lua-value key) "] = " (lua-value item))))
                                       (interpose ", "))
                                 str
                                 (sort-by (comp str key) value))
                      "}")
    :else (throw (ex-info "Unsupported value in Panthera Lua export." {:value value}))))

(defn write-lua
  "Export a portable data module accepted by panthera.create_gui(require(...)).
  Named curves are sampled without changing the JSON editing source."
  [document]
  (str "return " (lua-value (assoc (runtime-document document) :format "lua")) "\n"))
