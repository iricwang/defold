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

(ns editor.skeletal-animation-timeline
  "Atomic timeline edits. References use {:type :bone/:transform/:ik/:deform/:path/:physics
  :id string :time seconds}, plus :property for scalar tracks. Incoming keys
  replace resident keys by default. Colliding incoming keys are resolved by
  latest original time, independently of selection order."
  (:refer-clojure :exclude [select-keys])
  (:require [clojure.data.json :as json]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-geometry :as geometry]
            [util.coll :as coll]))

(set! *warn-on-reflection* true)

(def ^:private track-sections
  {:bone [:tracks :bone] :transform [:transform-tracks :transform]
   :ik [:ik-tracks :ik] :deform [:deforms :slot] :path [:path-tracks :path]
   :physics [:physics-tracks :physics]})

(def ^:private scalar-types #{:bone :transform :path :physics})

(defn- require-value! [value message context]
  (when-not value (throw (ex-info message context))))

(defn- animation-index [document animation-id]
  (or (reduce-kv (fn [_ index animation] (when (= animation-id (:id animation)) (reduced index))) nil (:animations document))
      (throw (ex-info "Unknown animation." {:animation animation-id}))))

(defn- canonical-ref [{:keys [type id property time]}]
  (require-value! (contains? track-sections type) "This timeline type does not support batch editing." {:type type})
  (require-value! (and (string? id) (or (= :physics type) (pos? (count id))) (geometry/finite-number? time) (not (neg? time))
                       (or (not (contains? scalar-types type)) (string? property)))
                  "A key reference needs an ID, property when applicable and a finite nonnegative time." {})
  (cond-> {:type type :id id :time (double time)}
    (contains? scalar-types type) (assoc :property property)))

(defn- track-id [ref]
  [(:type ref) (:id ref) (:property ref)])

(defn- ordered-refs [refs]
  (vec (sort-by (juxt #(name (:type %)) :id #(or (:property %) "") :time) refs)))

(defn- animation-entries [document animation-id]
  (let [animation (get-in document [:animations (animation-index document animation-id)])
        source (when-let [source (:spine-source document)] (json/read-str source))
        physics-order (into [] (distinct) (into (vec (:physics-order animation)) (into [""] (map :id) (:physics document))))
        physics-ranks (into {} (map-indexed (fn [index id] [id index])) physics-order)]
    (into {}
          (mapcat (fn [[type [section identity-field]]]
                    (mapv (fn [track]
                            (let [descriptor (cond-> {:type type :id (get track identity-field)}
                                               (contains? scalar-types type) (assoc :property (:property track)))
                                  originals (when (contains? #{:ik :transform :path :physics} type)
                                              (get-in source (cond-> ["animations" animation-id (name type) (:id descriptor)]
                                                               (= :physics type) (conj (:property track))
                                                               (= :path type) (conj (if (contains? #{"position" "spacing"} (:property track))
                                                                                     (:property track) "mix")))))]
                              [(track-id descriptor)
                               (cond-> {:descriptor descriptor :track (dissoc track :keys)
                                :entries (mapv (fn [index key]
                                                 (let [key (if (or (not (contains? #{:ik :transform :path :physics} type)) (contains? key :spine-frame-source))
                                                             key
                                                             (assoc key :spine-frame-source
                                                                    (some-> (coll/first-where #(== (:time key) (get % "time" 0.0)) originals) json/write-str)))]
                                                   {:key key :origin-time (:time key) :source-order (:time key)
                                                    :origin-end (get-in track [:keys (inc index) :time] (:duration animation))
                                                    :ref (canonical-ref (assoc descriptor :time (:time key)))}))
                                               (range (count (:keys track))) (:keys track))}
                                 (= :physics type) (assoc :physics-rank (get physics-ranks (:id descriptor))))])) (get animation section []))))
          track-sections)))

(defn all-key-refs
  "Return canonical references for every supported key in one animation."
  [document animation-id]
  (data/validate-document document)
  (let [animation (get-in document [:animations (animation-index document animation-id)])]
    (ordered-refs
      (into []
            (mapcat (fn [[type [section identity-field]]]
                      (into [] (mapcat (fn [track]
                                        (mapv #(canonical-ref {:type type :id (get track identity-field)
                                                               :property (:property track) :time (:time %)}) (:keys track))))
                            (get animation section []))))
            track-sections))))

(defn select-keys
  "Validate/deduplicate references and return stable order. Missing keys throw;
  UI caches can first intersect their selection with all-key-refs."
  [document animation-id refs]
  (let [available (set (all-key-refs document animation-id))
        selected (into #{} (map canonical-ref) refs)]
    (require-value! (coll/every? available selected) "The selection contains a missing key." {})
    (ordered-refs selected)))

(defn copy-keys
  "Capture selected keys, absolute curve intervals and mesh identity. Paste
  aligns the earliest captured time with its destination time."
  [document animation-id refs]
  (let [selected (set (select-keys document animation-id refs))
        tracks (into []
                     (keep (fn [[_ {:keys [descriptor entries] :as track}]]
                             (let [entries (filterv #(contains? selected (:ref %)) entries)]
                               (when (pos? (count entries))
                                 (cond-> (assoc track :entries entries)
                                   (= :deform (:type descriptor))
                                   (assoc :mesh-signature
                                          (let [slot (coll/first-where #(= (:id descriptor) (:id %)) (:slots document))]
                                            {:attachment (:attachment slot) :skin (get slot :spine-skin "default")})))))))
                     (sort-by (comp pr-str first) (animation-entries document animation-id)))
        times (mapv :time (ordered-refs selected))]
    {:version 1 :origin (if (zero? (count times)) 0.0 (reduce min times))
     :duration (if (zero? (count times)) 0.0 (- (reduce max times) (reduce min times)))
     :tracks tracks}))

(defn- retime-frame [entry new-end]
  (let [{:keys [key origin-time origin-end]} entry
        time (:time key)
        remap (fn [value]
                (if (> origin-end origin-time)
                  (+ time (* (/ (- value origin-time) (- origin-end origin-time)) (- new-end time)))
                  time))
        remap-curve (fn [[t1 v1 t2 v2]] [(remap t1) v1 (remap t2) v2])]
    (cond-> key
      (= "bezier" (:interpolation key)) (update :curve remap-curve)
      (= "bezier" (:softness-interpolation key)) (update :softness-curve remap-curve)
      (:spine-frame-source key)
      (update :spine-frame-source
              (fn [text]
                (let [frame (assoc (json/read-str text) "time" time)]
                  (json/write-str
                    (cond-> frame
                      (vector? (get frame "curve"))
                      (update "curve" (fn [curve] (mapv (fn [index value] (if (even? index) (remap value) value)) (range (count curve)) curve)))))))))))

(defn- merge-entries [resident incoming duration collision]
  (let [groups (group-by #(double (get-in % [:key :time]))
                        (into (mapv #(assoc % :incoming false) resident) (map #(assoc % :incoming true)) incoming))]
    (require-value! (or (= :overwrite collision) (coll/every? #(= 1 (count %)) (coll/vals groups)))
                    "Keys collide at the destination time." {})
    (let [entries (mapv (fn [[_ entries]] (peek (vec (sort-by (juxt :incoming :source-order) entries)))) (sort-by first groups))]
      (mapv (fn [index entry] (retime-frame entry (get-in entries [(inc index) :key :time] duration)))
            (range (count entries)) entries))))

(defn- apply-batch [document animation-id removals incoming collision]
  (require-value! (contains? #{:overwrite :reject} collision) "Unknown timeline collision policy." {:collision collision})
  (let [index (animation-index document animation-id)
        animation (get-in document [:animations index])
        original (animation-entries document animation-id)
        incoming-by-track (into {} (map (fn [track] [(track-id (:descriptor track)) track])) incoming)
        affected (into (set (coll/keys removals)) (coll/keys incoming-by-track))
        duration (reduce max (:duration animation)
                         (into [] (comp (mapcat :entries) (map #(get-in % [:key :time]))) incoming))
        _ (require-value! (and (geometry/finite-number? duration)
                              (coll/every? #(and (geometry/finite-number? (get-in % [:key :time]))
                                                 (not (neg? (get-in % [:key :time]))))
                                           (eduction (mapcat :entries) incoming)))
                          "Batch key times must remain finite and nonnegative." {})
        updates (into {}
                      (map (fn [id]
                             (let [old (get original id)
                                   new (get incoming-by-track id)
                                   removed (get removals id #{})
                                   resident (filterv #(not (contains? removed (double (get-in % [:key :time])))) (:entries old))]
                               [id (assoc (or (:track old) (:track new)) :keys
                                          (merge-entries resident (:entries new) duration collision))]))) affected)
        animation (reduce (fn [animation [type [section identity-field]]]
                            (let [existing (get animation section [])
                                  identify (fn [track] [type (get track identity-field) (when (contains? scalar-types type) (:property track))])
                                  existing-ids (into #{} (map identify) existing)
                                  added (into [] (comp (filter (fn [[id]] (and (= type (first id)) (not (contains? existing-ids id))))) (map second))
                                              (sort-by (comp pr-str first) updates))
                                  tracks (into (mapv #(get updates (identify %) %) existing) added)]
                              (if (and (not (contains? animation section)) (zero? (count tracks)))
                                animation
                                (assoc animation section (filterv #(or (not (contains? updates (identify %)))
                                                                        (pos? (count (:keys %)))) tracks)))))
                          (assoc animation :duration duration) track-sections)
        physics-incoming (filterv #(= :physics (get-in % [:descriptor :type])) incoming)
        animation (if (zero? (count physics-incoming)) animation
                      (let [incoming-order (into [] (comp (map #(get-in % [:descriptor :id])) (distinct))
                                                 (sort-by (juxt :physics-rank #(get-in % [:descriptor :id])) physics-incoming))
                            incoming-ids (set incoming-order)
                            order (into [] (distinct) (into (vec (:physics-order animation)) (into [""] (map :id) (:physics document))))
                            ;; Reorder only the incoming constraints' positions.
                            ;; Other constraints retain their relative precedence.
                            [order _] (reduce (fn [[result index] id]
                                                (if (contains? incoming-ids id)
                                                  [(conj result (nth incoming-order index)) (inc index)]
                                                  [(conj result id) index])) [[] 0] order)]
                        (assoc animation :physics-order order)))]
    (data/validate-document (assoc-in document [:animations index] animation))))

(defn- selected-batch [document animation-id refs]
  (let [clipboard (copy-keys document animation-id refs)]
    {:tracks (:tracks clipboard)
     :removals (into {} (map (fn [{:keys [descriptor entries]}]
                              [(track-id descriptor) (into #{} (map #(double (get-in % [:key :time]))) entries)])) (:tracks clipboard))}))

(defn- retime-selected [document animation-id refs time-fn collision]
  (let [{:keys [tracks removals]} (selected-batch document animation-id refs)
        tracks (mapv #(update % :entries (fn [entries] (mapv (fn [entry] (update-in entry [:key :time] time-fn)) entries))) tracks)]
    (apply-batch document animation-id removals tracks collision)))

(defn move-keys [document animation-id refs delta {:keys [collision] :or {collision :overwrite}}]
  (require-value! (geometry/finite-number? delta) "Move distance must be finite." {})
  (retime-selected document animation-id refs #(+ % delta) collision))

(defn scale-keys
  "Scale selected times around pivot. Use reverse-keys for negative scaling."
  [document animation-id refs pivot factor {:keys [collision] :or {collision :overwrite}}]
  (require-value! (and (geometry/finite-number? pivot) (geometry/finite-number? factor) (pos? factor))
                  "Timeline scale needs a finite pivot and positive factor." {})
  (retime-selected document animation-id refs #(+ pivot (* factor (- % pivot))) collision))

(defn snap-keys
  "Snap selected times to the nearest frame; half-frame ties round up."
  [document animation-id refs fps {:keys [collision] :or {collision :overwrite}}]
  (require-value! (and (geometry/finite-number? fps) (pos? fps)) "Frame rate must be finite and positive." {})
  (retime-selected document animation-id refs #(/ (Math/floor (+ 0.5 (* (double %) fps))) fps) collision))

(defn delete-keys [document animation-id refs]
  (let [{:keys [removals]} (selected-batch document animation-id refs)]
    (apply-batch document animation-id removals [] :overwrite)))

(defn paste-keys
  "Paste into the same document, retaining track identities. FFD pastes reject
  a changed mesh/skin instead of assigning channels to different vertices.
  Physics targets keep their source precedence within the target animation's
  existing target positions; unrelated targets keep their relative order."
  [document animation-id clipboard at-time {:keys [collision] :or {collision :overwrite}}]
  (data/validate-document document)
  (require-value! (and (= 1 (:version clipboard)) (vector? (:tracks clipboard))
                       (geometry/finite-number? (:origin clipboard))
                       (geometry/finite-number? at-time) (not (neg? at-time))) "Invalid timeline clipboard or paste time." {})
  (require-value! (= (count (:tracks clipboard)) (count (into #{} (map #(track-id (:descriptor %))) (:tracks clipboard))))
                  "Clipboard tracks must have unique identities." {})
  (doseq [{:keys [descriptor entries mesh-signature track physics-rank]} (:tracks clipboard)]
    (when (= :physics (:type descriptor))
      (require-value! (and (integer? physics-rank) (not (neg? physics-rank)))
                      "Physics clipboard tracks need their original constraint order." {}))
    (require-value! (and (vector? entries) (pos? (count entries))
                         (coll/every? #(geometry/finite-number? (get-in % [:key :time])) entries))
                    "Clipboard tracks need keys with finite times." {})
    (let [[_ identity-field] (get track-sections (:type descriptor))]
      (require-value! (and (= (:id descriptor) (get track identity-field))
                           (or (not (contains? scalar-types (:type descriptor))) (= (:property descriptor) (:property track)))
                           (= (count entries) (count (into #{} (map #(double (get-in % [:key :time]))) entries))))
                      "Clipboard key/track identities are inconsistent." {}))
    (doseq [entry entries]
      (canonical-ref (assoc descriptor :time (get-in entry [:key :time])))
      (require-value! (and (geometry/finite-number? (:origin-time entry)) (geometry/finite-number? (:origin-end entry))
                           (geometry/finite-number? (:source-order entry))
                           (<= (:origin clipboard) (:origin-time entry) (:origin-end entry))
                           (== (:origin-time entry) (get-in entry [:key :time]))) "Invalid clipboard curve interval." {}))
    (when (= :deform (:type descriptor))
      (require-value! (= mesh-signature (let [slot (coll/first-where #(= (:id descriptor) (:id %)) (:slots document))]
                                         {:attachment (:attachment slot) :skin (get slot :spine-skin "default")}))
                      "The deformation clipboard belongs to a different mesh or skin." {})))
  (let [delta (- at-time (:origin clipboard))
        tracks (mapv #(update % :entries (fn [entries] (mapv (fn [entry] (update-in entry [:key :time] + delta)) entries))) (:tracks clipboard))]
    (apply-batch document animation-id {} tracks collision)))

(defn- reverse-entry [entry previous type start end]
  (let [new-time (- (+ start end) (:origin-time entry))]
    (if-not previous
      (assoc-in entry [:key :time] new-time)
      (let [reflect #(- (+ start end) %)
            reverse-curve (fn [[t1 v1 t2 v2]] [(reflect t2) (if (= :deform type) (- 1.0 v2) v2)
                                              (reflect t1) (if (= :deform type) (- 1.0 v1) v1)])
            curve-key (:key previous)
            key (merge (dissoc (:key entry) :curve :softness-interpolation :softness-curve)
                       (clojure.core/select-keys curve-key [:interpolation :curve :softness-interpolation :softness-curve]))
            key (cond-> (assoc key :time new-time)
                  (= "bezier" (:interpolation key)) (update :curve reverse-curve)
                  (= "bezier" (:softness-interpolation key)) (update :softness-curve reverse-curve)
                  (:spine-frame-source key)
                  (update :spine-frame-source
                          (fn [text]
                            (let [frame (json/read-str text)
                                  curve (some-> (:spine-frame-source curve-key) json/read-str (get "curve"))]
                              (json/write-str (cond-> (dissoc frame "curve")
                                                (vector? curve) (assoc "curve" (into [] (mapcat reverse-curve) (into [] (partition-all 4) curve)))
                                                (= "stepped" curve) (assoc "curve" curve)))))))]
        (assoc entry :key key :origin-time new-time :origin-end (reflect (:origin-time previous)))))))

(defn reverse-keys
  "Reverse selected keys inside [start,end]. Outgoing curves transfer from the
  preceding selected key, including FFD progress and independent IK softness.
  Unselected keys remain and redivide intervals. Discrete/stepped values stay
  attached to their key, as in ordinary keyframe edits."
  [document animation-id refs start end {:keys [collision] :or {collision :overwrite}}]
  (require-value! (and (geometry/finite-number? start) (geometry/finite-number? end) (<= 0.0 start end))
                  "The reverse range must be finite, ordered and nonnegative." {})
  (let [{:keys [tracks removals]} (selected-batch document animation-id refs)
        tracks (mapv (fn [track]
                       (update track :entries
                               (fn [entries]
                                 (doseq [entry entries]
                                   (require-value! (<= start (:origin-time entry) end) "Selected keys lie outside the reverse range." {}))
                                 (mapv (fn [index entry] (reverse-entry entry (get entries (dec index)) (get-in track [:descriptor :type]) start end))
                                       (range (count entries)) entries)))) tracks)]
    (apply-batch document animation-id removals tracks collision)))
