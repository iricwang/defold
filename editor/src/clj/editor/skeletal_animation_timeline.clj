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
  "Atomic timeline edits. References use {:type supported-type :id string
  :time seconds}, plus :property for scalar and slot tracks. Incoming keys
  replace resident keys by default. Colliding incoming keys are resolved by
  latest original time, independently of selection order."
  (:refer-clojure :exclude [select-keys])
  (:require [clojure.data.json :as json]
            [clojure.string :as string]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-events :as events]
            [editor.skeletal-animation-geometry :as geometry]
            [editor.skeletal-animation-slots :as slots]
            [util.coll :as coll]))

(set! *warn-on-reflection* true)

(def ^:private track-sections
  {:bone [:tracks :bone] :transform [:transform-tracks :transform]
   :ik [:ik-tracks :ik] :deform [:deforms :slot] :path [:path-tracks :path]
   :physics [:physics-tracks :physics] :event [:event-tracks :event]
   :slot [:slot-tracks :slot] :draw-order [:draw-order-tracks :draw-order]
   :sequence [:sequence-tracks :sequence]
   :inherit [:inherit-tracks :inherit] :attachment-deform [:attachment-deform-tracks :deform-target]})

(def ^:private scalar-types #{:bone :transform :path :physics :slot})

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
  (let [document (slots/materialize-tracks (events/materialize-document document))
        animation (get-in document [:animations (animation-index document animation-id)])
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
  (let [document (slots/materialize-tracks (events/materialize-document document))
        animation (get-in document [:animations (animation-index document animation-id)])]
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
                                   (= :attachment-deform (:type descriptor))
                                   (assoc :mesh-signature (slots/deform-target-attachment document (:track track)))
                                   (= :deform (:type descriptor))
                                   (assoc :mesh-signature
                                     (let [slot (coll/first-where #(= (:id descriptor) (:id %)) (:slots document))]
                                       {:attachment (:attachment slot) :skin (get slot :spine-skin "default")})))))))
                     (sort-by (comp pr-str first) (animation-entries document animation-id)))
        times (mapv :time (ordered-refs selected))]
    {:version 1 :origin (if (zero? (count times)) 0.0 (reduce min times))
     :duration (if (zero? (count times)) 0.0 (- (reduce max times) (reduce min times)))
     :tracks tracks}))

(defn- discrete-entry? [entry]
  (let [{:keys [type property]} (:ref entry)]
    (or (contains? #{:event :draw-order :sequence :inherit} type)
        (and (= :slot type) (= "attachment" property))
        (and (= :physics type) (= "reset" property)))))

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
                      (and (not (discrete-entry? entry)) (vector? (get frame "curve")))
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
  (let [original-document document
        document (slots/materialize-tracks (events/materialize-document document))
        index (animation-index document animation-id)
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
                        (assoc animation :physics-order order)))
        document (assoc-in document [:animations index] animation)]
    (if (zero? (count affected))
      original-document
      (if (coll/any? #(contains? #{:slot :draw-order :sequence :inherit :attachment-deform} (first %)) affected)
        (slots/persist-tracks document animation-id)
        (data/validate-document
          (update document :animations #(mapv (fn [animation] (dissoc animation :slot-tracks :draw-order-tracks :sequence-tracks :inherit-tracks :attachment-deform-tracks)) %)))))))

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
    (when (= :attachment-deform (:type descriptor))
      (require-value! (= mesh-signature (slots/deform-target-attachment document track))
                      "The deformation clipboard belongs to a different attachment geometry." {}))
    (when (= :deform (:type descriptor))
      (require-value! (= mesh-signature (let [slot (coll/first-where #(= (:id descriptor) (:id %)) (:slots document))]
                                          {:attachment (:attachment slot) :skin (get slot :spine-skin "default")}))
                      "The deformation clipboard belongs to a different mesh or skin." {})))
  (let [delta (- at-time (:origin clipboard))
        tracks (mapv #(update % :entries (fn [entries] (mapv (fn [entry] (update-in entry [:key :time] + delta)) entries))) (:tracks clipboard))]
    (apply-batch document animation-id {} tracks collision)))

(defn- reverse-entry [entry previous type start end]
  (let [new-time (- (+ start end) (:origin-time entry))]
    (if (or (nil? previous) (discrete-entry? entry))
      (assoc-in entry [:key :time] new-time)
      (let [reflect #(- (+ start end) %)
            reverse-curve (fn [[t1 v1 t2 v2]] [(reflect t2) (if (contains? #{:deform :attachment-deform} type) (- 1.0 v2) v2)
                                               (reflect t1) (if (contains? #{:deform :attachment-deform} type) (- 1.0 v1) v1)])
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

(defn matching-refs
  "Find keys by :types, :ids, :properties, case-insensitive :query and an
  inclusive :time-range [start end]. Empty filters include every track."
  [document animation-id {:keys [types ids properties query time-range]}]
  (when time-range
    (require-value! (and (vector? time-range) (= 2 (count time-range))
                         (coll/every? geometry/finite-number? time-range)
                         (<= 0.0 (first time-range) (second time-range))) "Invalid timeline filter range." {}))
  (require-value! (or (nil? query) (string? query)) "Track filter text must be a string." {})
  (let [query (string/lower-case (or query ""))]
    (filterv (fn [{:keys [type id property time]}]
               (and (or (zero? (count types)) (contains? (set types) type))
                    (or (zero? (count ids)) (contains? (set ids) id))
                    (or (zero? (count properties)) (contains? (set properties) property))
                    (or (nil? time-range) (<= (first time-range) time (second time-range)))
                    (string/includes? (string/lower-case (str (name type) " " id " " property)) query)))
             (all-key-refs document animation-id))))

(defn repeat-keys
  "Repeat a selection atomically. repetitions counts additional copies;
  interval is the distance between copies. Existing collision policy applies."
  [document animation-id refs repetitions interval options]
  (require-value! (and (integer? repetitions) (<= 1 repetitions 1000)
                       (geometry/finite-number? interval) (pos? interval))
                  "Repeats need 1–1000 copies and a finite positive interval." {})
  (let [clipboard (copy-keys document animation-id refs)
        _ (require-value! (<= (* repetitions (count refs)) 100000) "Too many repeated keys." {})
        tracks (mapv (fn [track]
                       (update track :entries
                               (fn [entries]
                                 (coll/into-> (range 1 (inc repetitions)) []
                                   (mapcat (fn [index]
                                             (mapv (fn [entry]
                                                     (-> entry
                                                         (update-in [:key :time] + (* index interval))
                                                         (assoc :source-order (+ (* index interval) (:origin-time entry))))) entries)))))))
                     (:tracks clipboard))]
    (apply-batch document animation-id {} tracks (get options :collision :overwrite))))

(defn- key-channel-values [descriptor key end]
  (let [type (:type descriptor) property (:property descriptor)
        hex-values (fn hex-values [value]
                     (mapv #(/ (Integer/parseInt (subs value % (+ % 2)) 16) 255.0) (range 0 (count value) 2)))]
    (cond
      (contains? #{:deform :attachment-deform} type) [(if end 1.0 0.0)]
      (= :ik type) [(:mix key) (get key :softness 0.0)]
      (and (= :slot type) (contains? #{"rgba" "rgb" "rgba2" "rgb2"} property))
      (into (hex-values (:color key)) (hex-values (get key :dark-color "")))
      :else [(:value key)])))

(defn- slope [left right left-value right-value]
  (if (== (:time left) (:time right)) 0.0 (/ (- right-value left-value) (- (:time right) (:time left)))))

(defn- curve-controls [descriptor entries index duration {:keys [preset weight] :or {preset :linear weight (/ 1.0 3.0)}}]
  (let [left (get-in entries [index :key])
        right (or (get-in entries [(inc index) :key]) (assoc left :time duration))
        previous (get-in entries [(dec index) :key] left)
        following (get-in entries [(+ index 2) :key] right)
        left-values (key-channel-values descriptor left false)
        right-values (key-channel-values descriptor right true)
        before-values (key-channel-values descriptor previous false)
        after-values (key-channel-values descriptor following true)
        dt (* weight (- (:time right) (:time left)))]
    (mapv (fn [a b previous-value next-value]
            (let [linear (slope left right a b)
                  outgoing (case preset (:flat :ease-in :ease-in-out) 0.0
                                 :smooth (slope previous right previous-value b) linear)
                  incoming (case preset (:flat :ease-out :ease-in-out) 0.0
                                 :smooth (slope left following a next-value) linear)]
              [(+ (:time left) dt) (+ a (* outgoing dt))
               (- (:time right) dt) (- b (* incoming dt))]))
          left-values right-values before-values after-values)))

(defn- edit-curve-key [descriptor key controls defaults kind handles]
  (let [original (when-let [text (:spine-frame-source key)] (json/read-str text))
        raw-curve (get original "curve")
        curves (mapv (fn [index curve]
                       (let [old (cond
                                   (and (= :ik (:type descriptor)) (= index 1)) (get key :softness-curve (nth defaults index))
                                   (zero? index) (get key :curve (nth defaults index))
                                   (and (vector? raw-curve) (<= (* 4 (inc index)) (count raw-curve)))
                                   (subvec raw-curve (* index 4) (* (inc index) 4))
                                   :else (nth defaults index))]
                         (reduce (fn [result handle]
                                   (assoc result (* 2 handle) (nth curve (* 2 handle))
                                          (inc (* 2 handle)) (nth curve (inc (* 2 handle))))) old handles)))
                     (range (count controls)) controls)
        key (cond-> (dissoc (assoc key :interpolation kind) :curve)
              (= "bezier" kind) (assoc :curve (first curves))
              (= :ik (:type descriptor)) (assoc :softness-interpolation kind)
              (= :ik (:type descriptor)) (dissoc :softness-curve)
              (and (= :ik (:type descriptor)) (= "bezier" kind)) (assoc :softness-curve (second curves)))]
    (if-not original key
            (assoc key :spine-frame-source
                   (json/write-str (cond-> (dissoc original "curve")
                                     (= "stepped" kind) (assoc "curve" "stepped")
                                     (= "bezier" kind) (assoc "curve" (coll/into-> curves [] (mapcat identity)))))))))

(defn- edit-curves [document animation-id requested specification]
  (let [{:keys [type preset weight] :or {type "bezier" preset :linear weight (/ 1.0 3.0)}} specification
        _ (require-value! (and (contains? #{"linear" "stepped" "bezier"} type)
                               (contains? #{:linear :flat :smooth :ease-in :ease-out :ease-in-out} preset)
                               (geometry/finite-number? weight) (< 0.0 weight 0.5))
                          "Invalid curve type, tangent preset or handle weight." {})
        entries (animation-entries document animation-id)
        duration (get-in document [:animations (animation-index document animation-id) :duration])
        selected (set (select-keys document animation-id (coll/keys requested)))
        tracks (coll/into-> (coll/vals entries) []
                 (keep (fn [{:keys [descriptor entries] :as track}]
                         (let [keys (coll/into-> (range (count entries)) []
                                      (keep (fn [index]
                                              (let [entry (nth entries index) ref (:ref entry)]
                                                (when (contains? selected ref)
                                                  (require-value! (not (discrete-entry? entry))
                                                                  "Discrete event, attachment, order and sequence keys have no curve." ref)
                                                  (assoc entry :key
                                                         (edit-curve-key descriptor (:key entry)
                                                                         (curve-controls descriptor entries index duration specification)
                                                                         (curve-controls descriptor entries index duration {:preset :linear})
                                                                         type (get requested ref))))))))]
                           (when (pos? (count keys)) (assoc track :entries keys))))))
        removals (coll/into-> selected {} (map (fn [ref] [(track-id ref) #{}])))
        removals (reduce (fn [result ref] (update result (track-id ref) conj (:time ref))) removals selected)]
    (apply-batch document animation-id removals tracks :overwrite)))

(defn set-curves
  "Set every selected outgoing curve in one edit. specification accepts
  :type linear/stepped/bezier, :preset linear/flat/smooth/ease-in/ease-out/
  ease-in-out and :weight between zero and one half."
  [document animation-id refs specification]
  (let [refs (select-keys document animation-id refs)]
    (edit-curves document animation-id (zipmap refs (repeat #{0 1})) specification)))

(defn set-tangents
  "Edit incoming/outgoing handles around selected keys. :side is :in, :out
  or :both; :mode is :flat, :linear or :smooth. The whole edit is atomic."
  [document animation-id refs {:keys [side mode weight] :or {side :both mode :smooth weight (/ 1.0 3.0)}}]
  (require-value! (and (contains? #{:in :out :both} side) (contains? #{:flat :linear :smooth} mode))
                  "Invalid tangent side or mode." {})
  (let [refs (select-keys document animation-id refs)
        tracks (animation-entries document animation-id)
        requested (reduce (fn [result ref]
                            (let [entries (get-in tracks [(track-id ref) :entries])
                                  previous (peek (filterv #(< (get-in % [:key :time]) (:time ref)) entries))]
                              (cond-> result
                                (contains? #{:out :both} side) (update ref (fnil conj #{}) 0)
                                (and previous (contains? #{:in :both} side)) (update (:ref previous) (fnil conj #{}) 1)))) {} refs)]
    (edit-curves document animation-id requested {:type "bezier" :preset mode :weight weight})))
