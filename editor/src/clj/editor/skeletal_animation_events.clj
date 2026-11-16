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

(ns editor.skeletal-animation-events
  "Editable Spine event definitions and discrete animation keys. Events are
  notifications, not interpolated values. Original JSON fields survive edits."
  (:require [clojure.data.json :as json]
            [clojure.string :as string]
            [editor.skeletal-animation-geometry :as geometry]
            [util.coll :as coll]))

(set! *warn-on-reflection* true)

(def ^:private definition-fields [:int :float :string :audio :volume :balance])
(def ^:private frame-fields [:int :float :string :volume :balance])

(defn- require-value! [valid message context]
  (when-not valid (throw (ex-info message context))))

(defn- animation-index [document animation-id]
  (or (reduce-kv (fn [_ index animation]
                   (when (= animation-id (:id animation)) (reduced index))) nil (:animations document))
      (throw (ex-info "Unknown animation." {:animation animation-id}))))

(defn- import-fields [source fields]
  (coll/into-> fields {} (keep (fn [field]
                                 (when (contains? source (name field)) [field (get source (name field))])))))

(defn materialize-document
  "Upgrade only missing event sections from the retained Spine JSON. Explicit
  empty sections mean deletion and must never resurrect stale source events."
  [document]
  (if (and (contains? document :events) (coll/every? #(contains? % :event-tracks) (:animations document)))
    document
    (let [source (if-let [text (:spine-source document)] (json/read-str text) {})
          document (if (contains? document :events)
                     document
                     (assoc document :events
                            (mapv (fn [[event-name definition]]
                                    (assoc (import-fields definition definition-fields)
                                      :name event-name :spine-event-source (json/write-str definition)))
                                  (sort-by first (get source "events" {})))))]
      (update document :animations
              (fn [animations]
                (mapv (fn [animation]
                        (if (contains? animation :event-tracks)
                          animation
                          (let [frames (mapv (fn [index frame]
                                               (assoc (import-fields frame frame-fields)
                                                 :name (get frame "name") :time (get frame "time" 0.0)
                                                 :spine-event-order index :spine-frame-source (json/write-str frame)))
                                             (range) (get-in source ["animations" (:id animation) "events"] []))]
                            (assoc animation :event-tracks
                                   (mapv (fn [[event-name frames]]
                                           {:event event-name :keys (mapv #(dissoc % :name) (sort-by :time frames))})
                                         (sort-by first (group-by :name frames))))))) animations))))))

(defn- validate-payload! [value context]
  (doseq [field [:int :float :string :audio :volume :balance]
          :when (contains? value field)]
    (let [field-value (get value field)]
      (require-value! (case field
                        :int (and (integer? field-value) (<= Integer/MIN_VALUE field-value Integer/MAX_VALUE))
                        (:float :volume :balance) (geometry/finite-number? field-value)
                        (:string :audio) (string? field-value))
                      (str "Invalid event " (name field) " value.") (assoc context :field field))))
  (when (contains? value :volume)
    (require-value! (not (neg? (:volume value))) "Event volume must be nonnegative." context))
  (when (contains? value :balance)
    (require-value! (<= -1.0 (:balance value) 1.0) "Event balance must be between -1 and 1." context))
  (doseq [field [:spine-event-source :spine-frame-source]
          :when (contains? value field)]
    (require-value! (and (string? (get value field)) (map? (json/read-str (get value field))))
                    "Preserved event source must be a JSON object." (assoc context :field field))))

(defn validate-document
  "Validate event names, payloads, references and strictly ordered per-event
  keys. Different events may share a time; duplicate keys of one event may not."
  [document]
  (let [materialized (materialize-document document)
        definitions (:events materialized)]
    (require-value! (vector? definitions) "Event definitions must be an array." {})
    (let [names (reduce (fn [names definition]
                          (let [event-name (:name definition)]
                            (require-value! (and (map? definition) (string? event-name)
                                                 (not (string/blank? event-name)) (not (contains? names event-name)))
                                            "Event names must be nonempty and unique." {:event event-name})
                            (validate-payload! definition {:event event-name})
                            (conj names event-name))) #{} definitions)]
      (doseq [{:keys [id duration event-tracks]} (:animations materialized)]
        (require-value! (and (geometry/finite-number? duration) (not (neg? duration)))
                        "Animation duration must be finite and nonnegative." {:animation id})
        (require-value! (vector? event-tracks) "Event tracks must be an array." {:animation id})
        (reduce (fn [seen {:keys [event keys]}]
                  (let [context {:animation id :event event}]
                    (require-value! (and (contains? names event) (not (contains? seen event)))
                                    "An event track requires an existing, unique event." context)
                    (require-value! (vector? keys) "Event keys must be an array." context)
                    (reduce (fn [previous frame]
                              (let [time (:time frame)]
                                (require-value! (and (map? frame) (geometry/finite-number? time)
                                                     (<= 0.0 time duration) (> time previous))
                                                "Event key times must increase, be finite and fit within the animation." context)
                                (require-value! (not (contains? frame :audio))
                                                "Audio paths belong to event definitions, not animation keys." context)
                                (validate-payload! frame context)
                                (when (contains? frame :spine-event-order)
                                  (require-value! (and (integer? (:spine-event-order frame)) (not (neg? (:spine-event-order frame))))
                                                  "Event source order must be nonnegative." context))
                                time)) -1.0 keys)
                    (conj seen event))) #{} event-tracks))))
  document)

(defn definitions [document]
  (validate-document document)
  (:events (materialize-document document)))

(defn keyframes
  "Return keys with :name and :time, retaining same-time source order. Optional
  payload fields remain absent when they inherit the event definition."
  [document animation-id]
  (validate-document document)
  (let [document (materialize-document document)
        animation (get-in document [:animations (animation-index document animation-id)])]
    (vec (sort-by (juxt :time #(get % :spine-event-order Long/MAX_VALUE) :name)
                  (coll/into-> (:event-tracks animation) []
                    (mapcat (fn [{:keys [event keys]}] (mapv #(assoc % :name event) keys))))))))

(defn add-definition [document definition]
  (validate-document document)
  (validate-document (update (materialize-document document) :events conj definition)))

(defn update-definition
  "Update defaults or rename an event, including all of its animation tracks."
  [document event-name changes]
  (validate-document document)
  (let [document (materialize-document document)
        definition (coll/first-where #(= event-name (:name %)) (:events document))
        _ (require-value! definition "Unknown event." {:event event-name})
        replacement (merge definition changes)
        new-name (:name replacement)]
    (validate-document
      (-> document
          (update :events #(mapv (fn [definition] (if (= event-name (:name definition)) replacement definition)) %))
          (update :animations #(mapv (fn [animation]
                                       (update animation :event-tracks
                                               (fn [tracks]
                                                 (mapv (fn [track] (if (= event-name (:event track)) (assoc track :event new-name) track)) tracks)))) %))))))

(defn remove-definition
  "Remove an event and its keys from every animation."
  [document event-name]
  (validate-document document)
  (let [document (materialize-document document)]
    (require-value! (coll/any? #(= event-name (:name %)) (:events document)) "Unknown event." {:event event-name})
    (-> document
        (update :events #(filterv (fn [definition] (not= event-name (:name definition))) %))
        (update :animations #(mapv (fn [animation]
                                     (update animation :event-tracks (fn [tracks] (filterv (fn [track] (not= event-name (:event track))) tracks)))) %)))))

(defn set-keyframe
  "Insert or update one event key; omitted payloads inherit defaults. Duration
  grows as needed, and replacing a key retains unknown original JSON fields."
  [document animation-id frame]
  (validate-document document)
  (let [document (materialize-document document)
        index (animation-index document animation-id)
        animation (get-in document [:animations index])
        {:keys [name time]} frame
        _ (require-value! (and (coll/any? #(= name (:name %)) (:events document))
                               (geometry/finite-number? time) (not (neg? time)))
                          "An event key requires an existing event and finite nonnegative time." {:event name})
        tracks (:event-tracks animation)
        track (or (coll/first-where #(= name (:event %)) tracks) {:event name :keys []})
        previous (coll/first-where #(== time (:time %)) (:keys track))
        key (dissoc (merge previous frame) :name)
        track (assoc track :keys (vec (sort-by :time (conj (filterv #(not (== time (:time %))) (:keys track)) key))))
        tracks (if (coll/any? #(= name (:event %)) tracks)
                 (mapv #(if (= name (:event %)) track %) tracks)
                 (conj tracks track))]
    (validate-document (assoc-in document [:animations index]
                                 (assoc animation :event-tracks tracks :duration (max (:duration animation) time))))))

(defn remove-keyframe [document animation-id event-name time]
  (validate-document document)
  (require-value! (and (geometry/finite-number? time) (not (neg? time))) "Event key time must be finite and nonnegative." {})
  (let [document (materialize-document document)
        index (animation-index document animation-id)]
    (require-value! (coll/any? #(= event-name (:name %)) (:events document)) "Unknown event." {:event event-name})
    (update-in document [:animations index :event-tracks]
               (fn [tracks]
                 (coll/into-> tracks []
                   (map (fn [track]
                          (if (= event-name (:event track))
                            (update track :keys #(filterv (fn [key] (not (== time (:time key)))) %))
                            track)))
                   (filter #(pos? (count (:keys %)))))))))

(defn- export-fields [value fields source-field]
  (let [source (if-let [text (get value source-field)] (json/read-str text) {})]
    (reduce (fn [source field]
              (if (contains? value field) (assoc source (name field) (get value field)) (dissoc source (name field))))
            source fields)))

(defn export-definitions [document]
  (coll/into-> (definitions document) {}
    (map (fn [definition]
           [(:name definition) (export-fields definition definition-fields :spine-event-source)]))))

(defn export-keyframes [document animation-id]
  (mapv (fn [frame]
          (assoc (export-fields frame frame-fields :spine-frame-source) "name" (:name frame) "time" (:time frame)))
        (keyframes document animation-id)))

(defn prepare-playback
  "Validate and resolve event defaults once for immutable playback. The result
  belongs to this document; rebuild it after any authoring edit."
  [document]
  (validate-document document)
  (let [document (materialize-document document)
        definitions (coll/into-> (:events document) {} (map (juxt :name identity)))]
    {:version 1
     :animations (coll/into-> (:animations document) {}
                   (map (fn [{:keys [id duration event-tracks]}]
                          [id {:duration duration
                               :frames (coll/into-> event-tracks []
                                         (mapcat (fn [{:keys [event keys]}]
                                                   (mapv (fn [frame]
                                                           (merge {:int 0 :float 0.0 :string "" :volume 1.0 :balance 0.0}
                                                                  (get definitions event) frame {:name event})) keys))))}])))}))

(defn triggered-playback
  "Query a prepared playback snapshot over (from, to]. Negative from includes
  frame zero. Loop boundaries emit end keys before the next cycle's zero keys."
  [playback animation-id from to loop]
  (let [{:keys [duration frames] :as animation} (get-in playback [:animations animation-id])]
    (require-value! (and (= 1 (:version playback)) animation)
                    "Unknown animation in prepared event playback." {:animation animation-id})
    (require-value! (and (geometry/finite-number? from) (geometry/finite-number? to)
                         (<= from to) (not (neg? to)) (boolean? loop))
                    "Event playback requires an increasing finite interval and boolean loop flag." {})
    (let [looping (and loop (pos? duration))
          first-cycle (if looping (Math/floor (/ (max 0.0 from) duration)) 0.0)
          last-cycle (if looping (Math/floor (/ to duration)) 0.0)]
      (require-value! (and (geometry/finite-number? last-cycle) (< last-cycle Long/MAX_VALUE)
                           (<= (* (inc (- last-cycle first-cycle)) (count frames)) 100000))
                      "Event playback interval contains too many occurrences. Advance in smaller intervals." {})
      (if (zero? (count frames)) []
          (vec (sort-by (juxt :absolute-time :cycle #(get % :spine-event-order Long/MAX_VALUE) :name)
                        (coll/into-> (range (long first-cycle) (inc (long last-cycle))) []
                          (mapcat (fn [cycle]
                                    (coll/into-> frames []
                                      (keep (fn [frame]
                                              (let [absolute-time (+ (* cycle duration) (:time frame))]
                                                (when (and (> absolute-time from) (<= absolute-time to))
                                                  (assoc frame :absolute-time absolute-time :cycle cycle)))))))))))))))

(defn triggered
  "Return resolved events in the absolute playback interval (from, to]. Start
  with a negative from time to include frame zero. Loop boundaries emit both
  the previous cycle's end keys and the new cycle's zero keys, in that order.
  :time remains local; :absolute-time and :cycle identify each occurrence.
  A zero-duration animation emits once instead of looping infinitely."
  [document animation-id from to loop]
  (triggered-playback (prepare-playback document) animation-id from to loop))
