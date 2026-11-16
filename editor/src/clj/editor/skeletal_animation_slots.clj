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

(ns editor.skeletal-animation-slots
  "Single-animation slot playback from preserved Spine 4.2 timelines. Compiled
  caches belong to immutable documents; sampled slots never mutate setup data."
  (:require [clojure.data.json :as json]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-geometry :as geometry]
            [editor.skeletal-animation-inherit :as inherit]
            [editor.skeletal-animation-interpolation :as interpolation]
            [editor.skeletal-animation-sequence :as sequence]
            [util.coll :as coll])
  (:import [java.util Map]
           [org.codehaus.jackson.map ObjectMapper]))

(set! *warn-on-reflection* true)

(defonce ^:private document-cache (atom []))
(def ^:private parsed-source-cache (atom []))

(defn- parsed-source [text]
  (if-not text
    {:source {} :ordered nil}
    (if-let [entry (coll/first-where #(identical? text (:text %)) @parsed-source-cache)]
      (:parsed entry)
      (let [parsed {:source (json/read-str text)
                    :ordered (.readValue (ObjectMapper.) ^String text Map)}]
        ;; FFD drafts share the original JSON. Preserve its channel ordering,
        ;; without decoding the entire source again for every mouse move.
        (swap! parsed-source-cache #(coll/into-> % [{:text text :parsed parsed}] (take 3)))
        parsed))))

(defn- require-value! [value message]
  (when-not value (throw (ex-info message {}))))

(defn- color [text]
  (require-value! (and (string? text) (boolean (re-matches #"(?i)[0-9a-f]{6}([0-9a-f]{2})?" text))) "Invalid slot color.")
  (mapv #(/ (Integer/parseInt (subs text % (+ % 2)) 16) 255.0) (range 0 (count text) 2)))

(defn- frame-pairs [frames]
  (require-value! (vector? frames) "Slot timeline must be an array.")
  (let [times (mapv #(get % "time" 0.0) frames)]
    (require-value! (and (coll/every? #(and (geometry/finite-number? %) (not (neg? %))) times)
                         (or (< (count times) 2) (apply < times))) "Slot key times must increase and remain nonnegative.")
    (mapv (fn [frame time] (assoc frame :time time)) frames times)))

(defn- left-index [frames time]
  (loop [low 0 high (dec (count frames)) result nil]
    (if (> low high) result
        (let [middle (quot (+ low high) 2)]
          (if (<= (:time (nth frames middle)) time)
            (recur (inc middle) high middle)
            (recur low (dec middle) result))))))

(defn- prepare-curves [frames deformation]
  (mapv (fn [index left]
          (let [curve (get left "curve") right (get frames (inc index))]
            (require-value! (or (nil? curve) (= "stepped" curve) (vector? curve)) "Unsupported slot interpolation.")
            (if-not (and right (vector? curve)) left
                    (let [from (if deformation [0.0] (:values left)) to (if deformation [1.0] (:values right))]
                      (assoc left :bezier
                             (mapv (fn [channel from to]
                                     (let [offset (* 4 channel)
                                           _ (require-value! (<= (+ offset 4) (count curve)) "A slot Bezier curve is missing a channel.")]
                                       (interpolation/bezier-points (:time left) from (:time right) to
                                                                    (subvec curve offset (+ offset 4)))))
                                   (range (count from)) from to))))))
        (range (count frames)) frames))

(defn- segment-value [left right time from to channel]
  (let [curve (get left "curve") t0 (:time left) t1 (:time right)]
    (cond
      (= "stepped" curve) from
      (vector? curve) (interpolation/sample-bezier (get-in left [:bezier channel]) time)
      :else (+ from (* (/ (- time t0) (- t1 t0)) (- to from))))))

(defn- sample-values [frames time setup]
  (if-let [index (left-index frames time)]
    (let [left (nth frames index) right (get frames (inc index))]
      (if-not right (:values left)
              (mapv (fn [channel from to] (segment-value left right time from to channel))
                    (range (count setup)) (:values left) (:values right))))
    setup))

(defn- decode-color-track [type frames]
  (let [frames (mapv (fn [frame]
                       (let [values (case type "rgba2" (into (color (get frame "light")) (subvec (color (get frame "dark")) 0 3))
                                          "rgb2" (into (subvec (color (get frame "light")) 0 3) (subvec (color (get frame "dark")) 0 3))
                                          "alpha" [(get frame "value" 0.0)]
                                          "rgb" (subvec (color (get frame "color")) 0 3)
                                          "rgba" (let [value (color (get frame "color"))]
                                                   (if (= 3 (count value)) (conj value 1.0) value)))]
                         (require-value! (coll/every? geometry/finite-number? values) "Slot color keys must be finite.")
                         (assoc frame :values values))) (frame-pairs frames))]
    (prepare-curves frames false)))

(defn- draw-order [slots frame]
  (let [indices (into {} (map-indexed (fn [index slot] [(:id slot) index])) slots)
        offsets (get frame "offsets" [])
        pairs (mapv (fn [offset]
                      (let [index (get indices (get offset "slot")) delta (get offset "offset")]
                        (require-value! (and (some? index) (integer? delta) (<= 0 (+ index delta) (dec (count slots))))
                                        "Draw order references a missing slot or invalid offset.")
                        [index (+ index delta)])) offsets)
        moved (into {} pairs)
        targets (into {} (map (fn [[from to]] [to from])) pairs)
        unchanged (into [] (remove #(contains? moved %)) (range (count slots)))]
    (require-value! (and (= (count offsets) (count moved) (count targets))
                         (or (< (count pairs) 2) (apply < (mapv first pairs)))) "Draw order offsets must be unique and follow setup order.")
    (first (reduce (fn [[result cursor] index]
                     (if-let [source (get targets index)] [(conj result source) cursor]
                             [(conj result (nth unchanged cursor)) (inc cursor)]))
                   [[] 0] (range (count slots))))))

(defn- deform-frames [frames size]
  (let [frames (mapv (fn [frame]
                       (let [offset (get frame "offset" 0) values (get frame "vertices" [])]
                         (require-value! (and (integer? offset) (<= 0 offset) (vector? values)
                                              (<= (+ offset (count values)) size) (coll/every? geometry/finite-number? values))
                                         "Deformation does not match the sampled attachment.")
                         (assoc frame :vertices (reduce-kv #(assoc %1 (+ offset %2) %3) (vec (repeat size 0.0)) values))))
                     (frame-pairs frames))]
    (prepare-curves frames true)))

(defn- sample-deform [frames time]
  (when-let [index (left-index frames time)]
    (let [left (nth frames index) right (get frames (inc index))]
      (if-not right (:vertices left)
              (let [progress (segment-value left right time 0.0 1.0 0)]
                (mapv #(+ %1 (* progress (- %2 %1))) (:vertices left) (:vertices right)))))))

(defn- compiled-sequences [raw attachments]
  (reduce-kv
    (fn [result slot-id {:keys [entries]}]
      (reduce-kv
        (fn [result name {:keys [attachment key owner]}]
          (if-not (:sequence attachment) result
                  (assoc result [slot-id name]
                         (sequence/prepare-frames (let [frames (get-in raw (into ["attachments"] (conj key "sequence")) [])]
                                                    (if (pos? (count frames)) frames
                                                        (get-in raw (into ["attachments"] (conj owner "sequence")) [])))))))
        result entries))
    {} attachments))

(defn- compile-document [document]
  (data/validate-document document)
  (let [text (:spine-source document)
        {:keys [source ordered]} (parsed-source text)
        slots (:slots document) worlds (data/world-transforms (:bones document))
        bones (if text (mapv #(hash-map :id (get % "name")) (get source "bones")) (:bones document))
        raw-slots (into {} (map (juxt #(get % "name") identity)) (get source "slots" []))
        active (get document :active-skin "default")
        skins (into {} (map (juxt #(get % "name") #(get % "attachments" {}))) (get source "skins" []))
        overrides (into {} (keep (fn [slot]
                                   (when-let [attachment (:attachment slot)]
                                     [[(get slot :spine-skin "default") (:id slot) (get attachment :name (:id slot))] attachment]))) slots)
        slot-map (into {} (map (juxt :id identity)) slots)
        resolve-attachment
        (fn resolve-attachment [key visited]
          (require-value! (not (contains? visited key)) "Linked attachments contain a cycle.")
          (let [[_ slot name] key
                override (get overrides key)
                raw (cond-> (get-in skins key)
                      (= "linkedmesh" (:type override))
                      (merge {"type" "linkedmesh" "parent" (:parent override) "skin" (:skin override "default")
                              "timelines" (:deform override true) "path" (:path override)
                              "width" (:width override) "height" (:height override)}))
                raw (cond-> raw (:sequence override) (assoc "sequence" (sequence/encode (:sequence override) (get raw "sequence"))))]
            (if-let [attachment (when-not (= "linkedmesh" (:type override)) override)]
              {:attachment attachment :key key :owner key :editable true}
              (do
                (require-value! raw "Animation attachment is missing from the selected and default skins.")
                (if (= "linkedmesh" (get raw "type"))
                  (let [parent (resolve-attachment [(or (get raw "skin") "default") slot (get raw "parent")] (conj visited key))
                        _ (require-value! (= "mesh" (get-in parent [:attachment :type])) "A linked attachment parent must be a mesh.")
                        display (data/decode-spine-attachment name (assoc raw "type" "region") bones worlds (:bone (slot-map slot)))]
                    {:attachment (merge (dissoc (:attachment parent) :sequence) (select-keys display [:name :path :width :height :color :sequence]))
                     :key key :owner (if (get raw "timelines" (get raw "deform" true)) (:owner parent) key) :editable false})
                  {:attachment (data/decode-spine-attachment name raw bones worlds (:bone (slot-map slot)))
                   :key key :owner key :editable false})))))
        attachments
        (into {} (map (fn [slot]
                        (let [id (:id slot)
                              setup-name (if (:unsupported-attachment slot) (get-in raw-slots [id "attachment"])
                                             (when-let [attachment (:attachment slot)] (get attachment :name id)))
                              names (into (into #{} (coll/keys (get-in skins ["default" id]))) (coll/keys (get-in skins [active id])))
                              names (cond-> names setup-name (conj setup-name))]
                          [id {:setup-name setup-name
                               :entries (into {} (map (fn [name]
                                                        (let [skin (if (or (contains? (get-in skins [active id]) name) (contains? overrides [active id name])) active "default")]
                                                          [name (try (resolve-attachment [skin id name] #{})
                                                                     (catch Exception e {:warnings [(str id "/" name ": " (.getMessage e))]}))]))) names)}]))) slots)
        animations
        (into {} (map (fn [animation]
                        (let [id (:id animation) raw (get-in source ["animations" id] {})
                              timelines (into {}
                                              (map (fn [slot]
                                                     (let [slot-id (:id slot) tracks (get-in raw ["slots" slot-id] {})
                                                           java-tracks (get-in ordered ["animations" id "slots" slot-id])
                                                           types (if java-tracks (vec (.keySet ^Map java-tracks)) (vec (coll/keys tracks)))]
                                                       [slot-id (mapv (fn [type]
                                                                        (try
                                                                          {:type type :frames (if (contains? #{"rgba" "rgb" "alpha" "rgba2" "rgb2"} type)
                                                                                                (decode-color-track type (get tracks type))
                                                                                                (frame-pairs (get tracks type)))}
                                                                          (catch Exception e {:type type :frames [] :warnings [(.getMessage e)]}))) types)]))) slots)
                              draw (try {:frames (mapv #(assoc % :order (draw-order slots %)) (frame-pairs (get raw "drawOrder" [])))}
                                        (catch Exception e {:frames [] :warnings [(.getMessage e)]}))
                              deforms (into {}
                                            (mapcat (fn [[slot-id {:keys [entries]}]]
                                                      (into [] (keep (fn [[name {:keys [attachment owner]}]]
                                                                       (when (contains? #{"mesh" "path" "boundingbox" "clipping"} (:type attachment))
                                                                         (let [editable (get overrides owner)
                                                                               track (when editable (coll/first-where #(= slot-id (:slot %)) (:deforms animation)))
                                                                               size (data/deform-size attachment)]
                                                                           [[slot-id name]
                                                                            (try
                                                                              {:frames (if editable
                                                                                         (prepare-curves
                                                                                           (mapv (fn [key] (cond-> key (= "stepped" (:interpolation key)) (assoc "curve" "stepped")
                                                                                                             (= "bezier" (:interpolation key)) (assoc "curve" (:curve key)))) (:keys track)) true)
                                                                                         (deform-frames (get-in raw (into ["attachments"] (conj owner "deform")) []) size))
                                                                               :size size}
                                                                              (catch Exception e {:warnings [(.getMessage e)]}))])))) entries))) attachments)]
                          [id {:duration (:duration animation) :timelines timelines :draw draw :deforms deforms
                               :sequences (compiled-sequences raw attachments)}]))) (:animations document))]
    {:slots slots :raw-slots raw-slots :attachments attachments :animations animations :resolve-attachment resolve-attachment}))

(defn- compiled-document [document]
  (if-let [entry (coll/first-where #(identical? document (:document %)) @document-cache)]
    (:compiled entry)
    (let [compiled (compile-document document)]
      (swap! document-cache #(into [{:document document :compiled compiled}] (take 3) %))
      compiled)))

(defn sample-slots
  "Return {:slots draw-ordered-slots :deforms {slot-id offsets} :warnings []}.
  A nil animation returns edited setup state. Timelines use absolute seconds,
  clamp at the authoring duration, and perform no looping or cross-clip mixing.
  :preview-editable-attachment is false for alternate preserved attachments:
  viewport mesh tools must not write their offsets into the setup attachment.
  Unsupported attachment/tint content is hidden and explicitly reported."
  [document animation-id time]
  (require-value! (geometry/finite-number? time) "Slot preview time must be finite.")
  (let [{:keys [slots attachments animations]} (compiled-document document)
        animation (when animation-id (get animations animation-id))
        _ (require-value! (or (nil? animation-id) animation) "Unknown slot preview animation.")
        time (max 0.0 (min time (:duration animation 0.0)))
        sampled (mapv (fn [slot]
                        (let [id (:id slot) setup-color (:color slot [1.0 1.0 1.0 1.0])
                              initial {:name (get-in attachments [id :setup-name]) :color setup-color :dark-color (:dark-color slot) :warnings []}
                              state (reduce (fn [state {:keys [type frames warnings]}]
                                              (cond
                                                warnings (-> state (update :warnings into warnings) (assoc :unsupported true))
                                                (= "attachment" type) (if-let [index (left-index frames time)] (assoc state :name (get (nth frames index) "name")) state)
                                                (contains? #{"rgba" "rgb" "alpha" "rgba2" "rgb2"} type)
                                                (let [two (contains? #{"rgba2" "rgb2"} type)
                                                      indices (case type ("rgba" "rgba2") [0 1 2 3] ("rgb" "rgb2") [0 1 2] "alpha" [3])
                                                      setup (cond-> (mapv setup-color indices) two (into (:dark-color slot [0.0 0.0 0.0])))
                                                      values (mapv #(max 0.0 (min 1.0 %)) (sample-values frames time setup))]
                                                  (cond-> (update state :color #(reduce (fn [color [index value]] (assoc color index value)) % (mapv vector indices (take (count indices) values))))
                                                    two (assoc :dark-color (subvec values (count indices)))))
                                                :else (if (some? (left-index frames time))
                                                        (-> state (assoc :unsupported true) (update :warnings conj (str "Unsupported slot timeline: " type))) state)))
                                            initial (get-in animation [:timelines id]))
                              entry (when (coll/not-empty (:name state)) (get-in attachments [id :entries (:name state)]))
                              deform (get-in animation [:deforms [id (:name state)]])
                              warnings (into (into (:warnings state) (:warnings entry)) (:warnings deform))
                              warnings (cond-> warnings
                                         (and (coll/not-empty (:name state)) (nil? entry)) (conj (str "Missing animation attachment: " id "/" (:name state))))
                              attachment (when-not (or (:unsupported state) (pos? (count warnings))) (:attachment entry))
                              attachment (if-let [value (:sequence attachment)]
                                           (let [index (sequence/sample-index value (get-in animation [:sequences [id (:name state)]]) time)]
                                             (assoc attachment :sequence-base-path (:path attachment) :sequence-index index
                                                    :path (sequence/frame-path (:path attachment) value index)))
                                           attachment)]
                          {:slot (assoc slot :attachment attachment :color (:color state) :dark-color (:dark-color state) :blend (:blend slot "normal")
                                        :spine-skin (first (:key entry))
                                        :preview-attachment-key (when entry [(first (:key entry)) (:name state)])
                                        :preview-timeline-key (when entry [(first (:owner entry)) (last (:owner entry))])
                                        :preview-editable-attachment (boolean (:editable entry))
                                        :preview-deform-editable (contains? #{"mesh" "path" "boundingbox" "clipping"} (:type attachment)))
                           :deform (when (and attachment deform) (sample-deform (:frames deform) time))
                           :warnings warnings})) slots)
        order (if-let [index (left-index (get-in animation [:draw :frames]) time)]
                (get-in animation [:draw :frames index :order]) (vec (range (count slots))))]
    {:slots (mapv #(get-in sampled [% :slot]) order)
     :deforms (into {} (keep (fn [{:keys [slot deform]}] (when deform [(:id slot) deform]))) sampled)
     :warnings (into (vec (get-in animation [:draw :warnings])) (mapcat :warnings) sampled)}))

(def editable-properties #{"attachment" "rgba" "rgb" "alpha" "rgba2" "rgb2"})

(defn- animation-index [document animation-id]
  (or (reduce-kv (fn [_ index animation]
                   (when (= animation-id (:id animation)) (reduced index)))
                 nil (:animations document))
      (throw (ex-info "Unknown slot animation." {:animation animation-id}))))

(defonce ^:private authoring-source-cache (atom []))

(defn- source-data [document]
  ;; Writers still need a full source for the first authored preserved track.
  ;; Read-only timeline queries below inspect preserved data directly instead.
  (if-let [text (:spine-source document)]
    (:source (parsed-source text))
    (if-let [entry (coll/first-where #(identical? document (:document %)) @authoring-source-cache)]
      (:source entry)
      (let [source (json/read-str (data/export-spine-json document))]
        (swap! authoring-source-cache #(coll/into-> % [{:document document :source source}] (take 3)))
        source))))

(defn attachment-names
  "Available names in the current/default skins and edited setup attachment."
  [document slot-id]
  (data/validate-document document)
  (let [slot (coll/first-where #(= slot-id (:id %)) (:slots document))
        _ (require-value! slot "Unknown animation slot.")
        active (get document :active-skin "default")
        skins (get (:source (parsed-source (:spine-source document))) "skins" [])]
    (vec (sort (coll/into-> skins
                 (if-let [attachment (:attachment slot)] #{(get attachment :name slot-id)} #{})
                 (filter #(contains? (conj #{"default"} active) (get % "name")))
                 (mapcat #(coll/keys (get-in % ["attachments" slot-id]))))))))

(defn- decoded-key [property frame]
  (let [curve (get frame "curve")]
    (cond-> {:time (get frame "time" 0.0)
             :interpolation (cond (= "attachment" property) "stepped"
                                  (= "stepped" curve) "stepped"
                                  (vector? curve) "bezier"
                                  :else "linear")
             :spine-frame-source (json/write-str frame)}
      (= "attachment" property) (assoc :name (get frame "name"))
      (contains? #{"rgba" "rgb"} property) (assoc :color (get frame "color"))
      (contains? #{"rgba2" "rgb2"} property) (assoc :color (get frame "light") :dark-color (get frame "dark"))
      (= "alpha" property) (assoc :value (get frame "value" 0.0))
      (vector? curve) (assoc :curve (subvec curve 0 (min 4 (count curve)))))))

(defn tracks
  "Editable slot tracks, with complete curves retained in :spine-frame-source."
  [document animation-id slot-id]
  (data/validate-document document)
  (animation-index document animation-id)
  (require-value! (coll/any? #(= slot-id (:id %)) (:slots document)) "Unknown animation slot.")
  (coll/into-> (get-in (:source (parsed-source (:spine-source document))) ["animations" animation-id "slots" slot-id] {}) []
    (keep (fn [[property frames]]
            (when (contains? editable-properties property)
              {:slot slot-id :property property :keys (mapv #(decoded-key property %) frames)})))))

(defn- decoded-draw-order-track [document frames]
  {:draw-order "draw-order"
   :keys (mapv (fn [frame]
                 {:time (get frame "time" 0.0) :interpolation "stepped"
                  :order (mapv #(get-in document [:slots % :id]) (draw-order (:slots document) frame))
                  :spine-frame-source (json/write-str frame)}) frames)})

(defn draw-order-track
  "Global draw-order keys as complete slot-ID permutations."
  [document animation-id]
  (data/validate-document document)
  (animation-index document animation-id)
  (decoded-draw-order-track document
                            (get-in (:source (parsed-source (:spine-source document))) ["animations" animation-id "drawOrder"] [])))

(defn- source-sequence-tracks [source animation-id]
  (coll/into-> (get-in source ["animations" animation-id "attachments"]) []
    (mapcat (fn [[skin slots]]
              (coll/into-> slots []
                (mapcat (fn [[slot entries]]
                          (coll/into-> entries []
                            (keep (fn [[attachment timelines]]
                                    (when-let [frames (get timelines "sequence")]
                                      {:sequence (pr-str [skin slot attachment]) :skin skin :slot slot :attachment attachment
                                       :keys (mapv (fn [frame]
                                                     {:time (get frame "time") :index (get frame "index") :mode (get frame "mode")
                                                      :delay (get frame "delay") :interpolation "stepped"
                                                      :spine-frame-source (json/write-str frame)})
                                                   (sequence/prepare-frames frames))})))))))))))

(defn deform-track
  "Resolve the visible attachment to its deformation timeline owner."
  [document animation-id slot-id time]
  (let [slot (coll/first-where #(= slot-id (:id %)) (:slots (sample-slots document animation-id time)))
        [skin name] (:preview-timeline-key slot)
        setup (coll/first-where #(= slot-id (:id %)) (:slots document))
        canonical (and (= skin (:spine-skin setup "default")) (= name (get-in setup [:attachment :name] slot-id))
                       (not= "linkedmesh" (get-in setup [:attachment :type])))
        _ (require-value! (:preview-deform-editable slot) "The visible attachment has no deformable vertices.")
        animation (get-in document [:animations (animation-index document animation-id)])
        raw (get-in (:source (parsed-source (:spine-source document))) ["animations" animation-id "attachments" skin slot-id name "deform"] [])]
    (if canonical
      (or (coll/first-where #(= slot-id (:slot %)) (:deforms animation))
          {:slot slot-id :skin skin :attachment name :keys []})
      {:deform-target (pr-str [skin slot-id name]) :slot slot-id :skin skin :attachment name
       :keys (mapv (fn [frame]
                     (cond-> {:time (:time frame) :vertices (:vertices frame)
                              :interpolation (case (get frame "curve") "stepped" "stepped" nil "linear" "bezier")}
                       (vector? (get frame "curve")) (assoc :curve (get frame "curve"))))
                   (deform-frames raw (data/deform-size (:attachment slot))))})))

(defn deform-target-attachment
  "Resolve an explicit timeline owner, including attachments in inactive skins."
  [document track]
  (let [key [(:skin track) (:slot track) (:attachment track)]
        resolve-attachment (:resolve-attachment (compiled-document document))
        entry (resolve-attachment key #{})]
    (require-value! (= key (:owner entry)) "A linked mesh uses its parent's deformation timeline.")
    (:attachment entry)))

(defn- alternate-deform-tracks [document animation-id]
  (coll/into-> (get-in (:source (parsed-source (:spine-source document))) ["animations" animation-id "attachments"]) []
    (mapcat (fn [[skin slots]]
              (coll/into-> slots []
                (mapcat (fn [[slot entries]]
                          (coll/into-> entries []
                            (keep (fn [[name timelines]]
                                    (let [setup (coll/first-where #(= slot (:id %)) (:slots document))
                                          canonical (and (= skin (:spine-skin setup "default")) (= name (get-in setup [:attachment :name] slot))
                                                         (not= "linkedmesh" (get-in setup [:attachment :type])))
                                          frames (get timelines "deform")
                                          track {:deform-target (pr-str [skin slot name]) :slot slot :skin skin :attachment name}]
                                      (when (and (not canonical) (pos? (count frames)))
                                        ;; Ignore inherited linked-mesh timelines.
                                        (let [entry ((:resolve-attachment (compiled-document document)) [skin slot name] #{})]
                                          (when (= [skin slot name] (:owner entry))
                                            (assoc track :keys
                                                   (mapv (fn [frame]
                                                           (cond-> {:time (:time frame) :vertices (:vertices frame)
                                                                    :interpolation (case (get frame "curve") "stepped" "stepped" nil "linear" "bezier")}
                                                             (vector? (get frame "curve")) (assoc :curve (get frame "curve"))))
                                                         (deform-frames frames (data/deform-size (:attachment entry)))))))))))))))))))

(defn materialize-tracks
  "Expose preserved slot/draw-order keys to batch editing temporarily."
  [document]
  (data/validate-document document)
  (let [source (:source (parsed-source (:spine-source document)))]
    (update document :animations
            (fn [animations]
              (mapv (fn [animation]
                      (let [id (:id animation)]
                        (cond-> animation
                          (not (contains? animation :inherit-tracks))
                          (assoc :inherit-tracks (inherit/tracks source id))
                          (not (contains? animation :attachment-deform-tracks))
                          (assoc :attachment-deform-tracks (alternate-deform-tracks document id))
                          (not (contains? animation :slot-tracks))
                          (assoc :slot-tracks
                            (coll/into-> (:slots document) []
                              (mapcat (fn [slot]
                                        (coll/into-> (get-in source ["animations" id "slots" (:id slot)] {}) []
                                          (keep (fn [[property frames]]
                                                  (when (contains? editable-properties property)
                                                    {:slot (:id slot) :property property
                                                     :keys (mapv #(decoded-key property %) frames)}))))))))
                          (not (contains? animation :draw-order-tracks))
                          (assoc :draw-order-tracks [(decoded-draw-order-track document (get-in source ["animations" id "drawOrder"] []))])
                          (not (contains? animation :sequence-tracks))
                          (assoc :sequence-tracks (source-sequence-tracks source id)))))
                    animations)))))

(defn- valid-time! [time]
  (require-value! (and (geometry/finite-number? time) (not (neg? time)))
                  "Slot key time must be finite and nonnegative."))

(defn- encoded-key [property key preserve-source-curve]
  (valid-time! (:time key))
  (require-value! (contains? editable-properties property) "Unsupported editable slot timeline.")
  (let [original (if-let [text (:spine-frame-source key)] (json/read-str text) {})
        curve (if (and preserve-source-curve (vector? (get original "curve")))
                (get original "curve") (:curve key))
        frame (assoc (dissoc original "curve") "time" (:time key))]
    (cond-> (case property
              "attachment" (do (require-value! (or (nil? (:name key)) (and (string? (:name key)) (pos? (count (:name key)))))
                                               "Attachment key name must be a nonempty string or nil to hide the slot.")
                               (assoc frame "name" (:name key)))
              ("rgba2" "rgb2") (let [light (:color key) dark (:dark-color key)
                                     length (if (= "rgba2" property) 8 6)]
                                 (require-value! (and (string? light) (= length (count light)) (string? dark) (= 6 (count dark))
                                                      (boolean (re-matches #"(?i)[0-9a-f]+" light)) (boolean (re-matches #"(?i)[0-9a-f]+" dark)))
                                                 "Two-color keys need valid light and dark hexadecimal channels.")
                                 (assoc frame "light" light "dark" dark))
              ("rgba" "rgb") (let [text (:color key) length (if (= "rgba" property) 8 6)]
                               (require-value! (and (string? text) (= length (count text))
                                                    (boolean (re-matches #"(?i)[0-9a-f]+" text)))
                                               "Slot color needs RGB or RGBA hexadecimal channels.")
                               (assoc frame "color" text))
              "alpha" (do (require-value! (and (geometry/finite-number? (:value key)) (<= 0.0 (:value key) 1.0))
                                          "Slot alpha must be between zero and one.")
                          (assoc frame "value" (:value key))))
      (and (not= "attachment" property) (= "stepped" (:interpolation key))) (assoc "curve" "stepped")
      (and (not= "attachment" property) (= "bezier" (:interpolation key))) (assoc "curve" curve))))

(defn- encoded-draw-order [document key]
  (valid-time! (:time key))
  (let [setup (mapv :id (:slots document))
        order (:order key)
        _ (require-value! (and (vector? order) (= (count setup) (count order)) (= (set setup) (set order)))
                          "Draw order must contain every slot exactly once.")
        targets (coll/into-> order {} (map-indexed (fn [index id] [id index])))
        offsets (coll/into-> setup [] (keep-indexed (fn [index id]
                                                      (let [offset (- (get targets id) index)]
                                                        (when-not (zero? offset) {"slot" id "offset" offset})))))]
    (assoc (if-let [text (:spine-frame-source key)] (json/read-str text) {})
      "time" (:time key) "offsets" offsets)))

(defn- replace-frame [frames frame]
  (vec (sort-by #(get % "time" 0.0)
                (conj (filterv #(not (== (get % "time" 0.0) (get frame "time" 0.0))) frames) frame))))

(defn set-inherit-keyframe [document animation-id bone-id time mode]
  (valid-time! time)
  (let [authored-time time
        rounded (float time)
        time (double (if (> (double rounded) time) (Math/nextDown rounded) rounded))
        index (animation-index document animation-id)
        source (source-data document)
        tracks (inherit/tracks source animation-id)
        old (coll/first-where #(= bone-id (:inherit %)) tracks)
        track {:inherit bone-id :keys (vec (sort-by :time (conj (filterv #(and (not= (double authored-time) (double (:time %))) (not= (float time) (float (:time %)))) (:keys old))
                                                                {:time time :value mode})))}]
    (-> document
        (assoc :spine-source (json/write-str (inherit/persist document source animation-id (conj (filterv #(not= bone-id (:inherit %)) tracks) track))))
        (update-in [:animations index :duration] max time))))

(defn- encoded-deform-key [key]
  (cond-> {"time" (:time key) "vertices" (:vertices key)}
    (= "stepped" (:interpolation key)) (assoc "curve" "stepped")
    (= "bezier" (:interpolation key)) (assoc "curve" (:curve key))))

(defn set-deform-track-keyframe
  "Edit an explicit timeline owner even when another attachment is visible."
  [document animation-id track time vertices interpolation]
  (if-not (:deform-target track)
    (data/set-deform-keyframe document animation-id (:slot track) time vertices interpolation)
    (let [attachment (deform-target-attachment document track)
          _ (require-value! (and (= (:deform-target track) (pr-str [(:skin track) (:slot track) (:attachment track)]))
                                 (= (count vertices) (data/deform-size attachment))
                                 (coll/every? geometry/finite-number? vertices)) "Deform values must match the timeline attachment.")
          current (coll/first-where #(= (:deform-target track) (:deform-target %)) (alternate-deform-tracks document animation-id))
          keys (data/insert-deform-key (:keys current) time vertices interpolation)
          frames (mapv encoded-deform-key keys)
          _ (deform-frames frames (count vertices))
          index (animation-index document animation-id)]
      (-> document
          (update-in [:animations index :duration] max time)
          (assoc :spine-source (json/write-str (assoc-in (source-data document)
                                                         ["animations" animation-id "attachments" (:skin track) (:slot track) (:attachment track) "deform"] frames)))))))

(defn set-deform-keyframe
  "Key the visible attachment, honoring linked-mesh timeline ownership."
  [document animation-id slot-id time vertices interpolation]
  (set-deform-track-keyframe document animation-id (deform-track document animation-id slot-id time) time vertices interpolation))

(defn- validate-slot-track! [property frames]
  (if (= "attachment" property)
    (frame-pairs frames)
    (decode-color-track property frames)))

(defn set-keyframe
  "Insert/replace an attachment or color key, retaining custom frame fields."
  [document animation-id slot-id property key]
  (data/validate-document document)
  (let [slot (coll/first-where #(= slot-id (:id %)) (:slots document))
        document (if (and (contains? #{"rgba2" "rgb2"} property) (nil? (:dark-color slot)))
                   (data/update-slot document slot-id {:dark-color [0.0 0.0 0.0]}) document)
        index (animation-index document animation-id)
        _ (require-value! (coll/any? #(= slot-id (:id %)) (:slots document)) "Unknown animation slot.")
        _ (valid-time! (:time key))
        source (source-data document)
        path ["animations" animation-id "slots" slot-id property]
        frames (get-in source path [])
        previous (coll/first-where #(== (:time key) (get % "time" 0.0)) frames)
        preserve-source-curve (not (contains? key :curve))
        key (merge (when previous (decoded-key property previous)) key)
        encoded (encoded-key property key preserve-source-curve)
        frames (replace-frame frames encoded)]
    (validate-slot-track! property frames)
    (data/validate-document
      (-> document
          (update-in [:animations index :duration] max (:time key))
          (assoc :spine-source (json/write-str (assoc-in source path frames)))))))

(defn remove-keyframe [document animation-id slot-id property time]
  (data/validate-document document)
  (animation-index document animation-id)
  (require-value! (coll/any? #(= slot-id (:id %)) (:slots document)) "Unknown animation slot.")
  (require-value! (contains? editable-properties property) "Unsupported editable slot timeline.")
  (valid-time! time)
  (let [source (source-data document)
        path ["animations" animation-id "slots" slot-id]
        before (get-in source (conj path property) [])
        frames (filterv #(not (== time (get % "time" 0.0))) before)]
    (if (= before frames) document
        (assoc document :spine-source
               (json/write-str (if (pos? (count frames))
                                 (assoc-in source (conj path property) frames)
                                 (update-in source path dissoc property)))))))

(defn set-draw-order-keyframe [document animation-id time slot-ids]
  (data/validate-document document)
  (valid-time! time)
  (let [index (animation-index document animation-id)
        source (source-data document)
        path ["animations" animation-id "drawOrder"]
        frames (get-in source path [])
        previous (coll/first-where #(== time (get % "time" 0.0)) frames)
        frame (encoded-draw-order document (cond-> {:time time :order slot-ids}
                                             previous (assoc :spine-frame-source (json/write-str previous))))]
    (-> document
        (update-in [:animations index :duration] max time)
        (assoc :spine-source (json/write-str (assoc-in source path (replace-frame frames frame)))))))

(defn remove-draw-order-keyframe [document animation-id time]
  (data/validate-document document)
  (animation-index document animation-id)
  (valid-time! time)
  (let [source (source-data document)
        path ["animations" animation-id]
        before (get-in source (conj path "drawOrder") [])
        frames (filterv #(not (== time (get % "time" 0.0))) before)]
    (if (= before frames) document
        (assoc document :spine-source
               (json/write-str (if (pos? (count frames))
                                 (assoc-in source (conj path "drawOrder") frames)
                                 (update-in source path dissoc "drawOrder")))))))

(defn persist-tracks
  "Write batch edits to preserved JSON and remove temporary track projections."
  [document animation-id]
  (let [index (animation-index document animation-id)
        animation (get-in document [:animations index])
        source (source-data document)
        path ["animations" animation-id]
        source (if (contains? animation :inherit-tracks)
                 (inherit/persist document source animation-id (:inherit-tracks animation)) source)
        source (if-not (contains? animation :attachment-deform-tracks) source
                       (let [before (alternate-deform-tracks document animation-id)
                             source (reduce (fn [s track] (update-in s (into path ["attachments" (:skin track) (:slot track) (:attachment track)]) dissoc "deform")) source before)]
                         (reduce (fn [s track]
                                   (require-value! (= (:deform-target track) (pr-str [(:skin track) (:slot track) (:attachment track)])) "Invalid attachment deformation identity.")
                                   (assoc-in s (into path ["attachments" (:skin track) (:slot track) (:attachment track) "deform"])
                                             (let [frames (mapv encoded-deform-key (:keys track))]
                                               (deform-frames frames (data/deform-size (deform-target-attachment document track)))
                                               frames))) source (:attachment-deform-tracks animation))))
        slot-ids (coll/into-> (:slots document) #{} (map :id))
        source (if-not (contains? animation :slot-tracks) source
                       (let [source (update-in source (conj path "slots")
                                               (fn [slots]
                                                 (coll/into-> slots {} (map (fn [[id tracks]] [id (apply dissoc tracks editable-properties)])))))]
                         (reduce (fn [source {:keys [slot property keys]}]
                                   (require-value! (contains? slot-ids slot) "Unknown slot in timeline clipboard.")
                                   (let [frames (mapv #(encoded-key property % true) keys)]
                                     (validate-slot-track! property frames)
                                     (assoc-in source (into path ["slots" slot property]) frames)))
                                 source (:slot-tracks animation))))
        source (if-not (contains? animation :draw-order-tracks) source
                       (let [frames (coll/into-> (:draw-order-tracks animation) [] (mapcat (fn [track] (mapv #(encoded-draw-order document %) (:keys track)))))]
                         (frame-pairs frames)
                         (if (pos? (count frames)) (assoc-in source (conj path "drawOrder") frames)
                             (update-in source path dissoc "drawOrder"))))
        source (if-not (contains? animation :sequence-tracks) source
                       (let [source (update-in source (conj path "attachments")
                                               (fn [skins] (coll/map-vals (fn [slots] (coll/map-vals (fn [entries] (coll/map-vals #(dissoc % "sequence") entries)) slots)) skins)))
                             skins (coll/into-> (get source "skins") {} (map (juxt #(get % "name") #(get % "attachments"))))]
                         (reduce (fn [source {:keys [skin slot attachment keys] :as track}]
                                   (require-value! (= (:sequence track) (pr-str [skin slot attachment])) "Sequence clipboard identity is invalid.")
                                   (require-value! (get-in skins [skin slot attachment "sequence"]) "Sequence attachment no longer exists.")
                                   (let [frames (mapv (fn [key]
                                                        (merge (if-let [text (:spine-frame-source key)] (json/read-str text) {})
                                                               {"time" (:time key) "mode" (:mode key) "index" (:index key) "delay" (:delay key)})) keys)]
                                     (sequence/prepare-frames frames)
                                     (assoc-in source (into path ["attachments" skin slot attachment "sequence"]) frames)))
                                 source (:sequence-tracks animation))))]
    (data/validate-document
      (-> document
          (assoc :spine-source (json/write-str source))
          (update :animations #(mapv (fn [animation] (dissoc animation :slot-tracks :draw-order-tracks :sequence-tracks :inherit-tracks :attachment-deform-tracks)) %))))))
