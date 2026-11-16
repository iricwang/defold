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

(ns editor.skeletal-animation-mixing
  "Deterministic authoring playback with ordered tracks, queues, additive
  mixing, event dispatch and persistent Physics simulation."
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [editor.skeletal-animation-constraints :as constraints]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-events :as events]
            [editor.skeletal-animation-geometry :as geometry]
            [editor.skeletal-animation-physics :as physics]
            [editor.skeletal-animation-skin :as skin]
            [editor.skeletal-animation-slots :as slots]
            [util.coll :as coll]))

(set! *warn-on-reflection* true)

(defn- require-value! [valid message]
  (when-not valid (throw (ex-info message {}))))

(defn- clip [document specification]
  (let [{:keys [animation time loop] :or {time 0.0 loop false}} specification
        definition (coll/first-where #(= animation (:id %)) (:animations document))]
    (require-value! (and (map? specification) definition (geometry/finite-number? time)
                         (not (neg? time)) (boolean? loop))
                    "A preview track needs an existing animation, nonnegative time and boolean loop flag.")
    {:animation animation
     :definition definition
     :time (if (and loop (pos? (:duration definition)))
             (mod time (:duration definition))
             (min time (:duration definition)))}))

(defn- keyed? [keys time]
  (and (pos? (count keys)) (<= (:time (first keys)) time)))

(defn- raw-keyed? [keys time]
  (and (pos? (count keys)) (<= (get (first keys) "time" 0.0) time)))

(defn- ownership [document source {:keys [animation time definition]} sampled active]
  (let [slot-bones (coll/into-> (:slots document) {} (map (juxt :id :bone)))
        source-animation (get-in source ["animations" animation])
        scalar-ownership (fn scalar-ownership [section id-field]
                           (reduce (fn [result track]
                                     (if (and (not= "reset" (:property track)) (keyed? (:keys track) time))
                                       (update result (get track id-field) (fnil conj #{}) (keyword (:property track)))
                                       result)) {} (get definition section)))
        slot-properties (coll/into-> (get source-animation "slots") {}
                          (keep (fn [[id properties]]
                                  (when (contains? active (slot-bones id))
                                    [id (coll/into-> properties #{}
                                          (keep (fn [[property frames]]
                                                  (when (raw-keyed? frames time) property))))]))))]
    {:bones (coll/into-> (reduce (fn [owned [id timelines]]
                                   (if (raw-keyed? (get timelines "inherit") time) (update owned id (fnil conj #{}) :inherit) owned))
                                 (scalar-ownership :tracks :bone) (get source-animation "bones")) {} (filter #(contains? active (first %))))
     :ik (coll/into-> (:ik-tracks definition) {}
           (keep (fn [track]
                   (when (keyed? (:keys track) time)
                     [(:ik track) #{:mix :softness :bend-positive :compress :stretch}]))))
     :transform (scalar-ownership :transform-tracks :transform)
     :path (scalar-ownership :path-tracks :path)
     :physics (scalar-ownership :physics-tracks :physics)
     :slot-colors (coll/into-> slot-properties {}
                    (keep (fn [[id properties]]
                            (let [channels (coll/into-> properties #{}
                                             (mapcat #(case % ("rgba" "rgba2") [0 1 2 3] ("rgb" "rgb2") [0 1 2] "alpha" [3] [])))]
                              (when (pos? (count channels)) [id channels])))))
     :slot-dark-colors (coll/into-> slot-properties {}
                         (keep (fn [[id properties]]
                                 (when (coll/any? #{"rgba2" "rgb2"} properties) [id #{0 1 2}]))))
     :sequences (coll/into-> (:slots sampled) #{}
                  (keep (fn [slot]
                          (let [[skin attachment] (:preview-attachment-key slot)
                                [owner-skin owner] (:preview-timeline-key slot)
                                own-frames (get-in source-animation ["attachments" skin (:id slot) attachment "sequence"])
                                frames (if (pos? (count own-frames)) own-frames
                                           (get-in source-animation ["attachments" owner-skin (:id slot) owner "sequence"]))]
                            (when (raw-keyed? frames time) (:id slot))))))
     :attachments (coll/into-> slot-properties #{} (keep (fn [[id properties]] (when (contains? properties "attachment") id))))
     :draw-order (raw-keyed? (get source-animation "drawOrder") time)
     :deforms (coll/into-> (:slots sampled) #{}
                (keep (fn [slot]
                        (let [[skin attachment] (or (:preview-timeline-key slot) (:preview-attachment-key slot))
                              frames (get-in source-animation ["attachments" skin (:id slot) attachment "deform"])
                              track (coll/first-where #(and (= (:id slot) (:slot %))
                                                            (= attachment (:attachment %))) (:deforms definition))]
                          (when (and (contains? active (:bone slot))
                                     (or (raw-keyed? frames time) (keyed? (:keys track) time)))
                            (:id slot))))))}))

(defn- snapshot [document source clip active]
  (let [{:keys [animation time]} clip
        sampled (slots/sample-slots document animation time)]
    (assoc sampled
      :bones (data/sample-pose document animation time)
      :ik (data/sample-ik document animation time)
      :transform (data/sample-transforms document animation time)
      :path (data/sample-paths document animation time)
      :physics (data/sample-physics document animation time)
      :owned (ownership document source clip sampled active))))

(defn- mix-value [from to alpha property direction]
  (cond
    (= :inherit property) to
    (== 1.0 alpha) to
    (= :rotation property)
    (let [difference (- to from)
          shortest (- difference (* 360.0 (Math/ceil (- (/ difference 360.0) 0.5))))]
      (+ from (* alpha shortest)))
    (contains? #{:scale-x :scale-y} property)
    (let [start (if (= :out direction) from (* (abs from) (Math/signum (double to))))
          target (if (= :out direction) (* (abs to) (Math/signum (double from))) to)]
      (+ start (* alpha (- target start))))
    (boolean? to) to
    :else (+ from (* alpha (- to from)))))

(defn- mix-items [before after ownership alpha direction]
  (let [targets (coll/into-> after {} (map (juxt :id identity)))]
    (mapv (fn [item]
            (reduce (fn [item property]
                      (assoc item property (mix-value (get item property 0.0) (get-in targets [(:id item) property] 0.0) alpha property direction)))
                    item (get ownership (:id item)))) before)))

(defn- combined-ownership [before after]
  (merge-with into before after))

(defn- mix-state [before after alpha transition attachment-threshold]
  (let [owned (:owned after)
        result (reduce (fn [result section]
                         (let [fields (if transition
                                        (combined-ownership (get-in before [:owned section]) (get owned section))
                                        (get owned section))]
                           (assoc result section (mix-items (get before section) (get after section) fields alpha :in))))
                       before [:bones :ik :transform :path])
        target-slots (coll/into-> (:slots after) {} (map (juxt :id identity)))
        owned-colors (if transition
                       (combined-ownership (get-in before [:owned :slot-colors]) (:slot-colors owned))
                       (:slot-colors owned))
        owned-dark (if transition
                     (combined-ownership (get-in before [:owned :slot-dark-colors]) (:slot-dark-colors owned))
                     (:slot-dark-colors owned))
        sequence-ids (if transition (coll/into-> (:slots after) #{} (map :id)) (:sequences owned))
        attachment-ids (if transition (coll/into-> (:slots after) #{} (map :id)) (:attachments owned))
        apply-attachments (or transition (>= alpha attachment-threshold))
        mixed-slots (mapv (fn [slot]
                            (let [target (get target-slots (:id slot))
                                  color (reduce (fn [color channel]
                                                  (assoc color channel (+ (get color channel)
                                                                          (* alpha (- (get (:color target) channel) (get color channel))))))
                                                (:color slot) (get owned-colors (:id slot)))
                                  slot (if (and apply-attachments (contains? attachment-ids (:id slot)))
                                         (merge slot (select-keys target [:attachment :spine-skin :preview-attachment-key :preview-timeline-key :preview-editable-attachment :preview-deform-editable]))
                                         slot)
                                  slot (if (and (contains? sequence-ids (:id slot))
                                                (= (:preview-attachment-key slot) (:preview-attachment-key target)))
                                         (assoc slot :attachment (:attachment target)) slot)
                                  dark (reduce (fn [values channel]
                                                 (assoc values channel (+ (get values channel)
                                                                          (* alpha (- (get (:dark-color target) channel) (get values channel))))))
                                               (or (:dark-color slot) [0.0 0.0 0.0]) (get owned-dark (:id slot)))]
                              (cond-> (assoc slot :color color)
                                (or (:dark-color slot) (pos? (count (get owned-dark (:id slot))))) (assoc :dark-color dark)))) (:slots before))
        slots-by-id (coll/into-> mixed-slots {} (map (juxt :id identity)))
        mixed-slots (if (or transition (:draw-order owned))
                      (mapv #(slots-by-id (:id %)) (:slots after)) mixed-slots)
        before-slots (coll/into-> (:slots before) {} (map (juxt :id identity)))
        deforms (reduce (fn [deforms slot]
                          (let [id (:id slot)
                                previous (get before-slots id)
                                target (get target-slots id)
                                same-before (= (:preview-timeline-key slot) (:preview-timeline-key previous))
                                same-target (= (:preview-timeline-key slot) (:preview-timeline-key target))
                                old (when same-before (get-in before [:deforms id]))
                                new (when same-target (get-in after [:deforms id]))
                                editable (or transition (contains? (:deforms owned) id))]
                            (cond
                              (and editable (or old new))
                              (let [size (count (or new old))
                                    old (or old (vec (repeat size 0.0)))
                                    new (or new (vec (repeat size 0.0)))]
                                (require-value! (= (count old) (count new)) "Cannot mix incompatible deformation layouts.")
                                (assoc deforms id (mapv #(+ %1 (* alpha (- %2 %1))) old new)))
                              old (assoc deforms id old)
                              :else deforms))) {} mixed-slots)]
    (assoc result :slots mixed-slots :deforms deforms
           :owned (merge-with (fn merge-ownership [a b]
                                (cond (map? a) (merge-with into a b)
                                      (set? a) (into a b)
                                      :else (or a b))) (:owned before) (:owned after))
           :warnings (into (vec (:warnings before)) (:warnings after)))))

(defn sample
  "Sample {:base {:animation id :time seconds :loop boolean}, optional
  :overlay {:animation id :time seconds :loop boolean :alpha 0..1}, and optional
  :transition {:from id :time seconds :elapsed seconds :duration seconds}}.
  Transition mixes from the previous base; overlay owns only keyed channels.
  Attachments/draw order switch discretely, with overlay :attachment-threshold
  defaulting to zero as in Spine. Rotation takes the shortest initial route;
  repeated-turn history, additive tracks and queues are outside this preview.
  Physics is retained for single clips and explicitly paused during mixing."
  [document {:keys [base overlay transition]}]
  (data/validate-document document)
  (let [source (if-let [text (:spine-source document)] (json/read-str text) {})
        active (:bone-ids (skin/activation document))
        base (clip document base)
        sampled (snapshot document source base active)
        sampled (if-not transition
                  sampled
                  (let [{:keys [from time elapsed duration] :or {time 0.0 elapsed 0.0 duration 0.0}} transition
                        _ (require-value! (and (geometry/finite-number? elapsed) (not (neg? elapsed))
                                               (geometry/finite-number? duration) (not (neg? duration)))
                                          "Mix duration and elapsed time must be finite and nonnegative.")
                        previous (clip document {:animation from :time time :loop (get transition :loop false)})
                        alpha (if (zero? duration) 1.0 (min 1.0 (/ elapsed duration)))]
                    (mix-state (snapshot document source previous active) sampled alpha true 0.0)))
        sampled (if-not overlay
                  sampled
                  (let [alpha (get overlay :alpha 1.0)
                        threshold (get overlay :attachment-threshold 0.0)]
                    (require-value! (and (geometry/finite-number? alpha) (<= 0.0 alpha 1.0)
                                         (geometry/finite-number? threshold) (<= 0.0 threshold 1.0))
                                    "Track alpha and attachment threshold must be between zero and one.")
                    (mix-state sampled (snapshot document source (clip document overlay) active) alpha false threshold)))
        mixing (or overlay transition)
        sampler (fn mixed-slot-sampler [_document _animation _time]
                  (select-keys sampled [:slots :deforms]))
        pose (if mixing
               (let [mixed-document (-> document
                                        (merge (select-keys sampled [:bones :ik :transform :path]))
                                        (assoc :physics [] :animations []))]
                 (data/evaluate-pose-with-slots mixed-document nil 0.0 sampler))
               (data/evaluate-pose-with-slots document (:animation base) (:time base) slots/sample-slots))
        warnings (cond-> (vec (:warnings sampled))
                   (and mixing (pos? (count (:physics document))))
                   (conj "Physics constraints are paused in mixed preview; single-animation preview includes Physics."))]
    {:pose pose :slots (:slots sampled) :deforms (:deforms sampled)
     :warnings (coll/into-> warnings [] (distinct))}))

(defn- normalize-entry [document specification default-mix serial]
  (let [animation (coll/first-where #(= (:animation specification) (:id %)) (:animations document))
        entry (merge {:loop false :alpha 1.0 :blend :replace :time-scale 1.0
                      :time 0.0 :mix-duration default-mix :attachment-threshold 0.0
                      :event-threshold 0.0 :draw-order-threshold 0.0
                      :reverse false :hold-previous false :shortest-rotation false
                      :animation-start 0.0 :animation-end (:duration animation 0.0)}
                     specification)]
    (require-value! (and (map? specification) (or animation (:empty entry)) (boolean? (:loop entry))
                         (contains? #{:replace :add} (:blend entry))) "Invalid animation track.")
    (require-value! (coll/every? #{:animation :empty :loop :alpha :blend :time-scale :time :mix-duration
                                   :attachment-threshold :event-threshold :draw-order-threshold :queue :delay
                                   :reverse :hold-previous :shortest-rotation :animation-start :animation-end}
                                 (coll/keys specification)) "Unsupported animation track option.")
    (doseq [field [:reverse :hold-previous :shortest-rotation]]
      (require-value! (boolean? (get entry field)) "Playback switches must be boolean."))
    (require-value! (and (geometry/finite-number? (:animation-start entry))
                         (geometry/finite-number? (:animation-end entry))
                         (<= 0.0 (:animation-start entry) (:animation-end entry) (:duration animation 0.0)))
                    "Animation range must lie within the clip.")
    (doseq [field [:alpha :attachment-threshold :event-threshold :draw-order-threshold]]
      (require-value! (and (geometry/finite-number? (get entry field)) (<= 0.0 (get entry field) 1.0))
                      "Track weights and thresholds must be between zero and one."))
    (doseq [field [:time :time-scale :mix-duration]]
      (require-value! (and (geometry/finite-number? (get entry field)) (not (neg? (get entry field))))
                      "Track time, speed and mix duration must be finite and nonnegative."))
    (assoc (dissoc entry :queue :time) :serial serial :track-time (:time entry)
           :duration (- (:animation-end entry) (:animation-start entry)) :source-duration (:duration animation 0.0) :mix-time 0.0 :queue [] :applied false)))

(defn- append-entry [document current specification default-mix serial]
  (let [last-entry (or (peek (:queue current)) current)
        entry (normalize-entry document specification default-mix serial)
        delay (get specification :delay 0.0)
        _ (require-value! (geometry/finite-number? delay) "Queue delay must be finite.")
        complete (if (and (:loop last-entry) (pos? (:duration last-entry)))
                   (* (:duration last-entry) (inc (Math/floor (/ (:track-time last-entry) (:duration last-entry)))))
                   (max (:duration last-entry) (:track-time last-entry)))
        delay (if (pos? delay) delay (max 0.0 (+ complete delay (- (:mix-duration entry)))))]
    (update current :queue conj (assoc entry :delay delay))))

(defn create-state
  "Create transient playback with any number of ordered tracks. Each track
  accepts :animation, :loop, :alpha, :blend (:replace/:add), :time-scale,
  :mix-duration, attachment/event/draw-order thresholds and a :queue vector.
  Results and Physics caches belong to this immutable document."
  [document {:keys [tracks default-mix step-mode] :or {tracks [] default-mix 0.0 step-mode :fixed} :as configuration}]
  (data/validate-document document)
  (require-value! (and (contains? #{:fixed :runtime} step-mode) (vector? tracks) (geometry/finite-number? default-mix) (not (neg? default-mix)))
                  "Playback needs a track vector and nonnegative default mix duration.")
  (let [[tracks next-id]
        (reduce (fn [[tracks serial] specification]
                  (if (nil? specification)
                    [(conj tracks nil) serial]
                    (let [entry (normalize-entry document specification default-mix serial)
                          [entry serial] (reduce (fn [[entry serial] queued]
                                                   [(append-entry document entry queued default-mix serial) (inc serial)])
                                                 [entry (inc serial)] (:queue specification))]
                      [(conj tracks entry) serial]))) [[] 0] tracks)
        source (if-let [text (:spine-source document)] (json/read-str text) {})
        activation (skin/activation document)
        setup (snapshot document source {:animation nil :time 0.0 :definition {}} (:bone-ids activation))
        core {:clock 0.0 :tracks tracks :physics {} :rotations {} :tick 0 :origin 0.0}
        fps (reduce max 60.0 (mapv #(get % :fps 60.0) (:physics document)))]
    {:version 1 :document document :configuration configuration :default-mix default-mix
     :source source :setup setup :activation activation :fps fps
     :event-playback (events/prepare-playback document)
     :time 0.0 :core core :preview-core core :tracks tracks :next-id next-id
     :journal [] :updates [] :started false}))

(defn- advance-entry-time [entry delta]
  (let [updated (-> entry (update :track-time + (* delta (:time-scale entry))) (update :mix-time + delta))]
    (cond-> updated
      (and (:mixing-from entry) (< (:mix-time entry) (:mix-duration entry))) (update :mixing-from #(advance-entry-time (assoc % :retained-by-mix true) delta))
      (and (:mixing-from entry) (not (:retained-by-mix entry)) (>= (:mix-time entry) (:mix-duration entry))) (dissoc :mixing-from))))

(defn- entry-chain [entry]
  (loop [entry entry result []]
    (if-not entry result (recur (:mixing-from entry) (conj result entry)))))

(defn- entry-events [event-playback entry delta start-clock track]
  (let [from (:track-time entry)
        to (+ from (* delta (:time-scale entry)))
        start (:animation-start entry)
        end (:animation-end entry)
        duration (:duration entry)
        local-time (fn local-time [time]
                     (+ start (if (and (:loop entry) (pos? duration)) (mod time duration) (min time duration))))
        last-time (if (:applied entry) (local-time from) -1.0)
        time (local-time to)
        frames (vec (sort-by (juxt :time #(get % :spine-event-order Long/MAX_VALUE))
                             (mapv #(update % :time (fn [time] (double (float time)))) (get-in event-playback [:animations (:animation entry) :frames]))))
        crossed (fn crossed [from to]
                  (filterv #(let [time (double (float (:time %)))] (< from time (Math/nextUp (double to)))) frames))
        fired (if (or (:reverse entry) (:empty entry)) []
                  (if (> last-time time)
                    (into (crossed last-time Double/POSITIVE_INFINITY) (crossed -1.0 time))
                    (crossed last-time time)))
        wrapped (if (pos? duration) (rem (if (:applied entry) from -1.0) duration) 0.0)
        [before-complete after-complete]
        (reduce (fn [[before after] event]
                  (if (or (pos? (count after)) (< (:time event) wrapped))
                    [before (conj after event)] [(conj before event) after])) [[] []] fired)
        fired (into (filterv #(<= (:time %) end) before-complete) (filterv #(>= (:time %) start) after-complete))
        fired (mapv (fn [event]
                      (assoc event :track track :entry (:serial entry) :animation (:animation entry)
                             :playback-time (+ start-clock delta))) fired)
        outgoing (:mixing-from entry)
        remaining (when outgoing (- (* (:mix-duration entry) (:event-threshold outgoing)) (:mix-time entry)))]
    (if-not (and outgoing (pos? remaining))
      fired
      (into (entry-events event-playback outgoing (min delta remaining) start-clock track) fired))))

(defn- advance-track [event-playback entry delta start-clock track]
  (if-not entry
    [nil []]
    (loop [entry entry delta delta clock start-clock fired [] transitions 0]
      (require-value! (< transitions 10000) "Too many queued transitions in one update.")
      (let [next-entry (first (:queue entry))
            until (when next-entry
                    (if (zero? (:time-scale entry))
                      (if (<= (:delay next-entry) (:track-time entry)) 0.0 Double/POSITIVE_INFINITY)
                      (max 0.0 (/ (- (:delay next-entry) (:track-time entry)) (:time-scale entry)))))
            switching (and until (<= until delta))
            step (if switching until delta)
            fired (into fired (entry-events event-playback entry step clock track))
            outgoing (-> (advance-entry-time entry step) (assoc :applied true))]
        (if-not switching
          [outgoing fired]
          (let [incoming (assoc next-entry :delay 0.0 :queue (subvec (:queue entry) 1)
                                :mixing-from (dissoc outgoing :queue) :mix-time 0.0)]
            (recur incoming (- delta step) (+ clock step) fired (inc transitions))))))))

(defn- rotation-value [history from to alpha]
  (if (== 1.0 alpha)
    [to history]
    (let [difference (- to from)
          difference (- difference (* 360.0 (Math/ceil (- (/ difference 360.0) 0.5))))
          last-total (get history :total 0.0)
          last-difference (get history :difference difference)
          turns (- last-total (rem last-total 360.0))
          total (+ difference turns)
          current (not (neg? difference))
          direction (not (neg? last-total))
          [total direction]
          (if (and (<= (abs last-difference) 90.0)
                   (not= (Math/signum (double last-difference)) (Math/signum (double difference))))
            (cond
              (> (abs (- last-total turns)) 180.0) [(+ total (* 360.0 (Math/signum (double last-total)))) current]
              (not (zero? turns)) [(- total (* 360.0 (Math/signum (double last-total)))) direction]
              :else [total current])
            [total direction])
          total (cond (zero? difference) last-total
                      (not= direction current) (+ total (* 360.0 (Math/signum (double last-total))))
                      :else total)]
      [(+ from (* total alpha)) {:total total :difference difference}])))

(defn- blend-entry [setup before after entry alpha rotations]
  (let [owned (:owned after)
        result (mix-state before after alpha false (:attachment-threshold entry))
        targets (coll/into-> (:bones after) {} (map (juxt :id identity)))
        bases (coll/into-> (:bones setup) {} (map (juxt :id identity)))
        additive (= :add (:blend entry))
        [bones rotations]
        (reduce (fn [[bones rotations] bone]
                  (let [id (:id bone)
                        [bone rotations]
                        (reduce (fn [[bone rotations] property]
                                  (let [target (get-in targets [id property])
                                        base (get-in bases [id property] 0.0)
                                        key [(:serial entry) id property]]
                                    (cond
                                      (= :inherit property) [(assoc bone :inherit target) rotations]
                                      (and (:fading entry) (zero? alpha)) [bone rotations]
                                      additive [(assoc bone property (+ (get bone property 0.0) (* alpha (- target base)))) rotations]
                                      (= :rotation property)
                                      (let [[value history] (if (:shortest-rotation entry)
                                                              [(+ (:rotation bone) (* alpha (- target (:rotation bone)))) nil]
                                                              (rotation-value (get rotations key) (:rotation bone) target alpha))]
                                        [(assoc bone property value) (assoc rotations key history)])
                                      :else [(assoc bone property (mix-value (get bone property 0.0) target alpha property :in)) rotations])))
                                [bone rotations] (get-in owned [:bones id]))]
                    [(conj bones bone) rotations])) [[] rotations] (:bones before))
        ;; Bone and deformation timelines are relative in Spine's add mode;
        ;; absolute color and constraint timelines keep their replace behavior.
        deforms (if-not additive (:deforms result)
                        (reduce (fn [deforms id]
                                  (let [target (get-in after [:deforms id])
                                        previous (get-in before [:deforms id])
                                        before-slot (coll/first-where #(= id (:id %)) (:slots before))
                                        after-slot (coll/first-where #(= id (:id %)) (:slots result))
                                        previous (when (= (:preview-timeline-key before-slot) (:preview-timeline-key after-slot)) previous)]
                                    (if-not target deforms
                                            (assoc deforms id (mapv #(+ %1 (* alpha %2))
                                                                    (or previous (vec (repeat (count target) 0.0))) target)))))
                                (:deforms result) (:deforms owned)))
        physics-owned (coll/into-> (:physics setup) {}
                        (map (fn [constraint]
                               [(:id constraint)
                                (coll/into-> (get-in owned [:physics ""]) (get-in owned [:physics (:id constraint)] #{})
                                  (filter #(get constraint (keyword (str (name %) "-global")))))])))]
    [(assoc result :bones bones :deforms deforms
            :physics (mix-items (:physics before) (:physics after) physics-owned alpha :in)) rotations]))

(defn- old-only-ownership [old incoming]
  (reduce-kv (fn [result section value]
               (assoc result section
                      (cond
                        (map? value) (coll/into-> value {}
                                       (keep (fn [[id fields]]
                                               (let [fields (set/difference fields (get-in incoming [section id] #{}))]
                                                 (when (pos? (count fields)) [id fields])))))
                        (set? value) (set/difference value (get incoming section #{}))
                        :else (and value (not (get incoming section))))))
             {} old))

(defn- entry-snapshot [state entry]
  (let [duration (:duration entry)
        local (if (and (:loop entry) (pos? duration))
                (mod (:track-time entry) duration)
                (min (:track-time entry) duration))
        time (+ (:animation-start entry) local)
        time (if (:reverse entry) (- (:source-duration entry) time) time)]
    (snapshot (:document state) (:source state)
              (if (:empty entry) {:animation nil :time 0.0 :definition {}}
                  (clip (:document state) {:animation (:animation entry) :time time :loop false}))
              (get-in state [:activation :bone-ids]))))

(defn- render-entry [state before entry rotations]
  (if-not entry
    [before rotations]
    (let [sampled (entry-snapshot state entry)
          from (:mixing-from entry)
          progress (if (zero? (:mix-duration entry)) 1.0 (min 1.0 (/ (:mix-time entry) (:mix-duration entry))))
          [previous rotations]
          (if (or (nil? from) (and (not (:hold-previous entry)) (>= progress 1.0)))
            [before rotations]
            (let [[outgoing rotations] (render-entry state before (assoc from :outgoing-alpha (:interrupt-alpha entry 1.0)) rotations)
                  ownership (if (:hold-previous entry) {}
                                (old-only-ownership (:owned (entry-snapshot state from)) (:owned sampled)))
                  ownership (assoc ownership
                              :attachments (if (< progress (:attachment-threshold from)) #{} (:attachments ownership))
                              :draw-order (and (:draw-order ownership) (>= progress (:draw-order-threshold from))))
                  target (assoc before :owned ownership)
                  fading-entry (assoc from :serial [:out (:serial from)] :blend :replace :attachment-threshold 0.0 :fading true)]
              (blend-entry (:setup state) outgoing target fading-entry progress rotations)))
          alpha (* (:alpha entry) (or (:outgoing-alpha entry) (if from progress 1.0)))]
      (blend-entry (:setup state) previous sampled entry alpha rotations))))

(defn- render-bone-entry [state before entry rotations]
  (if-not entry
    [before rotations]
    (let [to (:outgoing-to entry)
          later (cond-> (:later entry []) to (conj to))
          [before rotations] (if-let [from (:mixing-from entry)]
                               (render-bone-entry state before (assoc from :outgoing-to entry :later later) rotations)
                               [before rotations])
          sampled (entry-snapshot state entry)
          owned (get-in sampled [:owned :bones])
          targets (coll/into-> (:bones sampled) {} (map (juxt :id identity)))
          setup (coll/into-> (get-in state [:setup :bones]) {} (map (juxt :id identity)))
          next-owned (when to (get-in (entry-snapshot state to) [:owned :bones]))
          progress (fn progress [entry]
                     (if (zero? (:mix-duration entry)) 1.0 (min 1.0 (/ (:mix-time entry) (:mix-duration entry)))))
          additive (= :add (:blend entry))]
      (reduce-kv
        (fn [[result rotations] id properties]
          (reduce
            (fn [[result rotations] property]
              (let [subsequent (contains? (get-in before [:owned id]) property)
                    hold-mix (when (and to (not subsequent) (contains? (get next-owned id) property)
                                        (pos? (:mix-duration entry)))
                               (coll/first-where #(not (contains? (get-in (entry-snapshot state %) [:owned :bones id]) property)) (:later entry)))
                    alpha (* (:alpha entry)
                             (if to
                               (* (:interrupt-alpha to 1.0)
                                  (cond (:hold-previous to) 1.0
                                        additive (- 1.0 (progress to))
                                        subsequent (- 1.0 (progress to))
                                        (not (contains? (get next-owned id) property)) (- 1.0 (progress to))
                                        hold-mix (- 1.0 (progress hold-mix))
                                        :else 1.0))
                               (if (:mixing-from entry) (progress entry) 1.0)))
                    base (get-in setup [id property])
                    previous (get-in result [:bones id property])
                    from (if (or subsequent additive) previous base)
                    target (get-in targets [id property])
                    key [(:serial entry) id property]
                    [value history]
                    (cond
                      (= :inherit property) [(if to (if subsequent previous base) target) nil]
                      additive [(+ previous (* alpha (- target base))) nil]
                      (and (= :rotation property) (not (:shortest-rotation entry)))
                      (rotation-value (get rotations key) from target alpha)
                      (= :rotation property) [(+ from (* alpha (- target from))) nil]
                      :else [(mix-value from target alpha property (if to :out :in)) nil])]
                [(-> result (assoc-in [:bones id property] value) (update-in [:owned id] (fnil conj #{}) property))
                 (cond-> rotations history (assoc key history))]))
            [result rotations] properties))
        [before rotations] owned))))

(defn- reset-crossed? [time from to duration loop]
  (if (and loop (pos? duration))
    (let [cycle (max 0.0 (inc (Math/floor (/ (- from time) duration))))]
      (<= (+ time (* cycle duration)) to))
    (< from time (Math/nextUp (double to)))))

(defn- physics-resets [document previous current]
  (let [previous (coll/into-> (entry-chain previous) {} (map (juxt :serial identity)))]
    (coll/into-> (entry-chain current) #{}
      (mapcat (fn [entry]
                (let [animation (coll/first-where #(= (:animation entry) (:id %)) (:animations document))
                      old (get previous (:serial entry))
                      from (if (:applied old) (:track-time old) -1.0)
                      to (:track-time entry)]
                  (coll/into-> (:physics-tracks animation) []
                    (filter #(= "reset" (:property %)))
                    (filter #(and (not (:reverse entry))
                                  (coll/any? (fn [key]
                                               (and (<= (:animation-start entry) (:time key) (:animation-end entry))
                                                    (reset-crossed? (- (double (float (:time key))) (:animation-start entry)) from to (:duration entry) (:loop entry)))) (:keys %))))
                    (map :physics))))))))

(defn- solve-mixed-pose [state sampled core reset-ids]
  (let [active (:activation state)
        setup-bones (coll/into-> (get-in state [:document :bones]) {} (map (juxt :id identity)))
        bones (mapv (fn [bone]
                      (let [enabled (contains? (:bone-ids active) (:id bone))]
                        (assoc (if enabled bone (setup-bones (:id bone))) :active enabled))) (:bones sampled))
        sampled-slots (coll/into-> (:slots sampled) {} (map (juxt :id identity)))
        ordered (sort-by #(get (second %) :order 0)
                         (coll/into-> [:ik :transform :path :physics] []
                           (mapcat (fn [kind]
                                     (coll/into-> (get sampled kind) []
                                       (filter #(contains? (get-in active [:constraints kind]) (:id %)))
                                       (map #(vector kind %)))))))]
    (reduce (fn [{:keys [pose physics-state]} [kind constraint]]
              (case kind
                :ik {:pose (constraints/apply-ik-constraint pose constraint) :physics-state physics-state}
                :transform {:pose (constraints/apply-transform-constraint pose constraint) :physics-state physics-state}
                :path {:pose (constraints/apply-path-constraint pose constraint (sampled-slots (:target constraint))
                                                                (get-in sampled [:deforms (:target constraint)] []))
                       :physics-state physics-state}
                :physics (let [bone (coll/first-where #(= (:bone constraint) (:id %)) (:bones pose))
                               result (physics/step constraint (get physics-state (:id constraint))
                                                    {:matrix (get-in pose [:transforms (:bone constraint)])
                                                     :length (:length bone) :time (:clock core)
                                                     :reference-scale (get-in state [:document :reference-scale] 100.0)
                                                     :mode (if (or (contains? reset-ids "") (contains? reset-ids (:id constraint)))
                                                             :reset-event :update)})]
                           {:pose (constraints/apply-world-transform pose (:bone constraint) (:matrix result))
                            :physics-state (assoc physics-state (:id constraint) (:state result))})))
            {:pose (assoc (constraints/pose bones) :active-bone-ids (:bone-ids active)) :physics-state (:physics core)} ordered)))

(defn- advance-core [state core delta]
  (let [[tracks fired resets]
        (reduce-kv (fn [[tracks fired resets] index entry]
                     (let [[advanced events] (advance-track (:event-playback state) entry delta (:clock core) index)]
                       [(conj tracks advanced) (into fired events)
                        (into resets (physics-resets (:document state) entry advanced))])) [[] [] #{}] (:tracks core))
        core (assoc core :tracks tracks :clock (+ (:clock core) delta))
        [sampled rotations] (reduce (fn [[sampled rotations] entry] (render-entry state sampled entry rotations))
                                    [(:setup state) (:rotations core)] tracks)
        [bone-result bone-rotations]
        (reduce (fn [[result rotations] entry] (render-bone-entry state result entry rotations))
                [{:bones (or (:bone-sample core) (coll/into-> (get-in state [:setup :bones]) {} (map (juxt :id identity)))) :owned {}}
                 (:bone-rotations core {})] tracks)
        sampled (assoc sampled :bones (mapv #(get-in bone-result [:bones (:id %)]) (:bones sampled)))
        {:keys [pose physics-state]} (solve-mixed-pose state sampled core resets)
        active-ids (coll/into-> tracks #{} (mapcat entry-chain) (map :serial))
        rotations (coll/into-> rotations {} (filter (fn [[[serial] _]]
                                                      (contains? active-ids (if (vector? serial) (second serial) serial)))))
        core (assoc core :physics physics-state :rotations rotations :bone-rotations bone-rotations :bone-sample (:bones bone-result)
                    :sample {:pose pose :slots (:slots sampled) :deforms (:deforms sampled)
                             :warnings (coll/into-> (:warnings sampled) [] (distinct))})]
    [core fired]))

(defn advance-state
  "Advance all tracks and queues, emitting only this update's events. Physics
  and rotation history use canonical fixed steps; partial display frames do
  not mutate that cache. Passing zero seconds applies the initial pose/events."
  [document state delta]
  (require-value! (and (= 1 (:version state)) (= document (:document state))
                       (geometry/finite-number? delta) (not (neg? delta)))
                  "Playback requires its original document and a finite nonnegative delta.")
  (let [target (+ (:time state) delta)
        _ (require-value! (geometry/finite-number? target) "Playback time overflow.")
        [core initial-events] (if (:sample (:core state)) [(:core state) []] (advance-core state (:core state) 0.0))
        count-steps (long (Math/floor (+ (* (- target (:origin core)) (:fps state)) 1.0e-9)))
        _ (require-value! (<= (- count-steps (:tick core)) 100000) "Playback jump is too large; advance in smaller intervals.")
        [core fired]
        (if (= :runtime (get-in state [:configuration :step-mode]))
          (let [[advanced events] (advance-core state core delta)]
            [advanced (into initial-events events)])
          (loop [core core fired initial-events]
            (if (>= (:tick core) count-steps)
              [core fired]
              (let [tick (inc (:tick core)) next-time (+ (:origin core) (/ tick (:fps state)))
                    [core events] (advance-core state core (- next-time (:clock core)))]
                (recur (assoc core :tick tick) (into fired events))))))
        [preview remaining] (if (< (- target (:clock core)) 1.0e-10)
                              [core []] (advance-core state core (- target (:clock core))))
        fired (coll/into-> (into fired remaining) []
                (filter #(or (> (:playback-time %) (:time state))
                             (and (not (:started state)) (zero? (:playback-time %)))
                             (= (:playback-time %) (:event-boundary state))))
                (distinct))
        updated (assoc (dissoc state :event-boundary) :updates (cond-> (:updates state [])
                                                                 (= :runtime (get-in state [:configuration :step-mode])) (conj {:time target :delta delta})) :time target :core core :preview-core preview :tracks (:tracks preview) :started true)]
    (assoc (:sample preview) :state updated :events (vec (sort-by (juxt :playback-time :track) fired)))))

(defn- change-track [state index entry command]
  (require-value! (and (integer? index) (<= 0 index 1023)) "Track index must be between zero and 1023.")
  (let [core (:preview-core state)
        tracks (into (:tracks core) (repeat (max 0 (inc (- index (count (:tracks core))))) nil))
        core (assoc core :tracks (assoc tracks index entry) :tick 0 :origin (:time state))]
    (-> state
        (assoc :core (dissoc core :sample) :preview-core (dissoc core :sample) :tracks (:tracks core)
               :event-boundary (:time state))
        (update :journal conj (assoc command :time (:time state) :index index)))))

(defn set-track
  "Replace one track and its queue, mixing from its currently applied entry."
  [document state index specification]
  (require-value! (= document (:document state)) "Playback document changed; create a new state.")
  (let [previous (get (:tracks state) index)
        entry (normalize-entry document specification (:default-mix state) (:next-id state))
        previous (when (:applied previous) previous)
        entry (cond-> entry previous (assoc :mixing-from (dissoc previous :queue)
                                            :interrupt-alpha (if (and (:mixing-from previous) (pos? (:mix-duration previous)))
                                                               (* (:interrupt-alpha previous 1.0) (min 1.0 (/ (:mix-time previous) (:mix-duration previous))))
                                                               1.0)))
        state (change-track state index entry {:operation :set :specification specification})]
    (update state :next-id inc)))

(defn queue-track
  "Append a track entry. Nonpositive delay schedules its mix to finish at the
  previous clip's next completion; positive delay is previous-track seconds."
  [document state index specification]
  (require-value! (= document (:document state)) "Playback document changed; create a new state.")
  (if-let [entry (get (:tracks state) index)]
    (-> (change-track state index
                      (append-entry document entry specification (:default-mix state) (:next-id state))
                      {:operation :queue :specification specification})
        (update :next-id inc))
    (set-track document state index specification)))

(defn clear-track [state index]
  (change-track state index nil {:operation :clear}))

(defn seek-state
  "Rebuild playback/Physics from the initial schedule and recorded track
  edits. Seeking returns no events; later advancement resumes after the seek."
  [document state time]
  (require-value! (and (geometry/finite-number? time) (not (neg? time))) "Seek time must be finite and nonnegative.")
  (let [initial (create-state document (:configuration state))
        commands (filterv #(<= (:time %) time) (:journal state))
        commands (if (= :runtime (get-in state [:configuration :step-mode]))
                   (vec (sort-by (juxt :time #(if (= :advance (:operation %)) 0 1))
                                 (coll/into-> (:updates state) commands (filter #(<= (:time %) time)) (map #(assoc % :operation :advance)))))
                   commands)
        replayed (reduce (fn [current command]
                           (let [current (cond
                                           (= :advance (:operation command)) (:state (advance-state document current (:delta command)))
                                           (= (:time command) (:time current)) current
                                           :else (:state (advance-state document current (- (:time command) (:time current)))))]
                             (case (:operation command)
                               :set (set-track document current (:index command) (:specification command))
                               :queue (queue-track document current (:index command) (:specification command))
                               :clear (clear-track current (:index command))
                               :advance current)))
                         initial commands)
        result (if (and (= time (:time replayed)) (get-in replayed [:preview-core :sample]))
                 (assoc (get-in replayed [:preview-core :sample]) :state replayed)
                 (advance-state document replayed (- time (:time replayed))))]
    (assoc result :events [])))
