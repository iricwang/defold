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
            [clojure.string :as string]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(def supported-properties
  ["position_x" "position_y" "position_z"
   "rotation_x" "rotation_y" "rotation_z"
   "scale_x" "scale_y" "scale_z" "color_a"])

(def supported-easings
  ["linear" "inquad" "outquad" "inoutquad"
   "incubic" "outcubic" "inoutcubic"
   "inquart" "outquart" "inoutquart"
   "inquint" "outquint" "inoutquint"
   "insine" "outsine" "inoutsine"])

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

(defn- key-issues [key]
  (cond-> []
    (not (contains? #{"tween" 1} (:key_type key)))
    (conj "Only numeric tween tracks can be previewed and edited.")

    (and (contains? #{"tween" 1} (:key_type key))
         (not (and (finite-number? (get key :start_value 0))
                   (finite-number? (get key :end_value 0)))))
    (conj "Tween start and end values must be finite numbers for preview and editing.")

    (not (contains? (set supported-properties) (:property_id key)))
    (conj (str "Unsupported GUI property: " (:property_id key)))

    (or (not (string? (:node_id key))) (string/blank? (:node_id key)))
    (conj "A tween must identify a GUI node.")

    (not (contains? (set supported-easings) (get key :easing "linear")))
    (conj (str "Unsupported easing: " (:easing key)))

    (:easing_custom key)
    (conj "Custom easing curves are preserved but cannot be previewed or edited.")

    (:is_editor_only key)
    (conj "Editor-only keys are preserved but cannot be previewed or edited.")))

(defn preview-issues
  "Return reasons why preview would differ from Panthera. Unsupported imported
  data remains intact; callers must display these reasons instead of previewing."
  [document animation-id]
  (let [animation (get-animation document animation-id)]
    (into (if (or (nil? (:initial_state animation)) (= "" (:initial_state animation)))
            []
            ["Initial-state animation references are preserved but cannot be previewed."])
          (comp (mapcat key-issues) (distinct))
          (:animation_keys animation))))

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
                               :easing (get key :easing "linear")
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
                     [(assoc key :key_type "tween" :node_id node-id :property_id property-id
                             :start_time time :duration 0.0 :start_value value :end_value value :easing easing)])
                   (reduce-kv (fn [result point-index {:keys [time value easing key]}]
                                (if (zero? (long point-index))
                                  result
                                  (let [previous (nth points (dec (long point-index)))]
                                    (conj result
                                          (assoc key :key_type "tween" :node_id node-id :property_id property-id
                                                 :start_time (:time previous)
                                                 :duration (- (double time) (double (:time previous)))
                                                 :start_value (:value previous) :end_value value :easing easing)))))
                              (if-let [key (:key (first points))]
                                (let [{:keys [time value easing]} (first points)]
                                  [(assoc key :key_type "tween" :node_id node-id :property_id property-id
                                          :start_time time :duration 0.0 :start_value value :end_value value :easing easing)])
                                [])
                              points))
        combined (into (into []
                             (remove #(and (= node-id (:node_id %))
                                           (= property-id (:property_id %))))
                             old-keys)
                       new-keys)]
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
  (require-value! (contains? (set supported-easings) easing)
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

(defn- ease
  ^double [easing ^double t]
  (case easing
    "linear" t
    "insine" (- 1.0 (Math/cos (* t (/ Math/PI 2.0))))
    "outsine" (Math/sin (* t (/ Math/PI 2.0)))
    "inoutsine" (* 0.5 (- 1.0 (Math/cos (* Math/PI t))))
    (let [power (cond
                  (string/ends-with? easing "quad") 2.0
                  (string/ends-with? easing "cubic") 3.0
                  (string/ends-with? easing "quart") 4.0
                  :else 5.0)]
      (cond
        (string/starts-with? easing "inout")
        (if (< t 0.5)
          (* 0.5 (Math/pow (* 2.0 t) power))
          (- 1.0 (* 0.5 (Math/pow (* 2.0 (- 1.0 t)) power))))

        (string/starts-with? easing "out")
        (- 1.0 (Math/pow (- 1.0 t) power))

        :else
        (Math/pow t power)))))

(defn- sample-key
  ^double [key ^double time]
  (let [start (double (get key :start_time 0))
        duration (double (get key :duration 0))
        from (double (get key :start_value 0))
        to (double (get key :end_value 0))]
    (cond
      (< time start) from
      (or (zero? duration) (>= time (+ start duration))) to
      :else (+ from (* (- to from) (ease (get key :easing "linear") (/ (- time start) duration)))))))

(defn evaluate
  "Return {node-id {Panthera-property-id number}} at time, without mutating the
  GUI or document. Matches Panthera's first-start-value and last-started-key
  semantics, including delayed keys, gaps and overlapping tweens."
  [document animation-id time]
  (require-value! (finite-number? time) "Preview time must be finite." {})
  (when-let [issue (first (preview-issues document animation-id))]
    (throw (ex-info issue {:animation-id animation-id})))
  (let [animation (get-animation document animation-id)
        time (max 0.0 (min (double time) (double (:duration animation))))
        groups (group-by (juxt :node_id :property_id) (:animation_keys animation))]
    (reduce-kv (fn [values [node-id property-id] keys]
                 (let [ordered (into [] (sort-by key-order keys))
                       active (reduce (fn [selected key]
                                        (if (> (double (get key :start_time 0)) time)
                                          (reduced selected)
                                          key))
                                      (first ordered)
                                      ordered)]
                   (assoc-in values [node-id property-id] (sample-key active time))))
               {}
               groups)))

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
  "Export a Lua data module accepted by panthera.create_gui(require(...)). Lua
  input is never executed or parsed by this editor; JSON is the editing source."
  [document]
  (str "return " (lua-value (assoc (validate-document document) :format "lua")) "\n"))
