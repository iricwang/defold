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
            [editor.skeletal-animation-interpolation :as interpolation]
            [util.coll :as coll])
  (:import [java.util Map]
           [org.codehaus.jackson.map ObjectMapper]))

(set! *warn-on-reflection* true)

(defonce ^:private document-cache (atom []))

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
          (let [values (case type "alpha" [(get frame "value" 0.0)]
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

(defn- compile-document [document]
  (data/validate-document document)
  (let [text (:spine-source document)
        source (if text (json/read-str text) {})
        ordered (when text (.readValue (ObjectMapper.) ^String text Map))
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
          (let [[_ slot name] key raw (get-in skins key)]
            (require-value! (not (get raw "sequence")) "Image sequence attachments are not previewed.")
            (if-let [attachment (get overrides key)]
              {:attachment attachment :key key :owner key :editable true}
              (do
                (require-value! raw "Animation attachment is missing from the selected and default skins.")
                (if (= "linkedmesh" (get raw "type"))
                  (let [parent (resolve-attachment [(get raw "skin" "default") slot (get raw "parent")] (conj visited key))
                        _ (require-value! (= "mesh" (get-in parent [:attachment :type])) "A linked attachment parent must be a mesh.")
                        display (data/decode-spine-attachment name (assoc raw "type" "region") bones worlds (:bone (slot-map slot)))]
                    {:attachment (merge (:attachment parent) (select-keys display [:name :path :width :height :color]))
                     :key key :owner (if (get raw "deform" true) (:owner parent) key) :editable false})
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
                                                                         {:type type :frames (if (contains? #{"rgba" "rgb" "alpha"} type)
                                                                                               (decode-color-track type (get tracks type))
                                                                                               (frame-pairs (get tracks type)))}
                                                                         (catch Exception e {:type type :frames [] :warnings [(.getMessage e)]}))) types)]))) slots)
                              draw (try {:frames (mapv #(assoc % :order (draw-order slots %)) (frame-pairs (get raw "drawOrder" [])))}
                                        (catch Exception e {:frames [] :warnings [(.getMessage e)]}))
                              deforms (into {}
                                            (mapcat (fn [[slot-id {:keys [entries]}]]
                                                      (into [] (keep (fn [[name {:keys [attachment owner]}]]
                                                                       (when (contains? #{"mesh" "path"} (:type attachment))
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
                          [id {:duration (:duration animation) :timelines timelines :draw draw :deforms deforms}]))) (:animations document))]
    {:slots slots :raw-slots raw-slots :attachments attachments :animations animations}))

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
  (let [{:keys [slots raw-slots attachments animations]} (compiled-document document)
        animation (when animation-id (get animations animation-id))
        _ (require-value! (or (nil? animation-id) animation) "Unknown slot preview animation.")
        time (max 0.0 (min time (:duration animation 0.0)))
        sampled (mapv (fn [slot]
                        (let [id (:id slot) setup-color (:color slot [1.0 1.0 1.0 1.0])
                              initial {:name (get-in attachments [id :setup-name]) :color setup-color :warnings []}
                              state (reduce (fn [state {:keys [type frames warnings]}]
                                              (cond
                                                warnings (-> state (update :warnings into warnings) (assoc :unsupported true))
                                                (= "attachment" type) (if-let [index (left-index frames time)] (assoc state :name (get (nth frames index) "name")) state)
                                                (contains? #{"rgba" "rgb" "alpha"} type)
                                                (let [indices (case type "rgba" [0 1 2 3] "rgb" [0 1 2] "alpha" [3])
                                                      values (sample-values frames time (mapv setup-color indices))]
                                                  (update state :color #(reduce (fn [color [index value]] (assoc color index (max 0.0 (min 1.0 value)))) % (mapv vector indices values))))
                                                :else (if (some? (left-index frames time))
                                                        (-> state (assoc :unsupported true) (update :warnings conj (str "Unsupported slot timeline: " type))) state)))
                                            initial (get-in animation [:timelines id]))
                              entry (when (coll/not-empty (:name state)) (get-in attachments [id :entries (:name state)]))
                              deform (get-in animation [:deforms [id (:name state)]])
                              tint (or (get-in raw-slots [id "dark"]) (not= "normal" (get-in raw-slots [id "blend"] "normal")))
                              warnings (into (into (:warnings state) (:warnings entry)) (:warnings deform))
                              warnings (cond-> warnings
                                         (and (coll/not-empty (:name state)) (nil? entry)) (conj (str "Missing animation attachment: " id "/" (:name state)))
                                         tint (conj (str "Unsupported slot blending or dark tint: " id)))
                              attachment (when-not (or (:unsupported state) tint (pos? (count warnings))) (:attachment entry))]
                          {:slot (assoc slot :attachment attachment :color (:color state)
                                       :spine-skin (first (:key entry))
                                       :preview-attachment-key (when entry [(first (:key entry)) (:name state)])
                                       :preview-editable-attachment (boolean (:editable entry)))
                           :deform (when (and attachment deform) (sample-deform (:frames deform) time))
                           :warnings warnings})) slots)
        order (if-let [index (left-index (get-in animation [:draw :frames]) time)]
                (get-in animation [:draw :frames index :order]) (vec (range (count slots))))]
    {:slots (mapv #(get-in sampled [% :slot]) order)
     :deforms (into {} (keep (fn [{:keys [slot deform]}] (when deform [(:id slot) deform]))) sampled)
     :warnings (into (vec (get-in animation [:draw :warnings])) (mapcat :warnings) sampled)}))
