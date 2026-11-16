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

(ns editor.skeletal-animation-attachments
  "Editable skin placeholders, alternate attachments and sequence keys."
  (:require [clojure.data.json :as json]
            [clojure.string :as string]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-geometry :as geometry]
            [editor.skeletal-animation-sequence :as sequence]
            [util.coll :as coll]))

(def types ["region" "mesh" "linkedmesh" "path" "point" "boundingbox" "clipping"])

(defn- require-value! [value message]
  (when-not value (throw (ex-info message {}))))

(defn- source-data [document]
  (json/read-str (data/export-spine-json document)))

(defn- skin-index [source name]
  (or (reduce-kv (fn [_ index skin] (when (= name (get skin "name")) (reduced index))) nil (get source "skins" []))
      (throw (ex-info "Unknown skin." {:skin name}))))

(defn- validate-links! [source]
  (let [skins (coll/into-> (get source "skins") {} (map (juxt #(get % "name") #(get % "attachments"))))]
    (letfn [(resolve-parent [key visited]
              (require-value! (not (contains? visited key)) "Linked mesh parents form a cycle.")
              (let [[_ slot _] key raw (get-in skins key)]
                (require-value! raw "Linked mesh parent does not exist.")
                (if (= "linkedmesh" (get raw "type"))
                  (resolve-parent [(or (get raw "skin") "default") slot (get raw "parent")] (conj visited key))
                  (require-value! (= "mesh" (get raw "type")) "Linked mesh parent must resolve to a mesh."))))]
      (doseq [[skin slots] skins [slot entries] slots [name raw] entries
              :when (= "linkedmesh" (get raw "type"))]
        (resolve-parent [skin slot name] #{})))))

(defn- restore [document source selected]
  (validate-links! source)
  (let [imported (data/import-spine-json (json/write-str source))
        imported (if (= selected (:active-skin imported)) imported (data/select-skin imported selected))]
    (merge document imported {:name (:name document)})))

(defn entries
  "Return explicit skin/slot entries with their editable JSON fields."
  [document skin slot]
  (let [source (source-data document)
        index (skin-index source skin)]
    (mapv (fn [[name raw]] {:name name :type (get raw "type" "region") :source raw})
          (sort-by first (get-in source ["skins" index "attachments" slot] {})))))

(defn placeholders [document slot]
  (vec (sort (coll/into-> (get (source-data document) "skins") #{}
               (mapcat #(coll/keys (get-in % ["attachments" slot])))))))

(defn put
  "Create/update an explicit attachment using Spine JSON field names.
  Existing unknown fields remain unless explicitly replaced."
  [document skin slot name changes]
  (require-value! (and (string? name) (not (string/blank? name)) (map? changes)) "Attachment needs a name and field map.")
  (require-value! (coll/any? #(= slot (:id %)) (:slots document)) "Unknown attachment slot.")
  (let [source (source-data document)
        index (skin-index source skin)
        path ["skins" index "attachments" slot name]
        raw (merge (get-in source path {}) changes)
        _ (require-value! (coll/any? #{(get raw "type" "region")} types) "Unsupported attachment type.")
        _ (when (get raw "sequence")
            (require-value! (contains? #{"region" "mesh" "linkedmesh"} (get raw "type" "region")) "Only image attachments support sequences."))
        source (assoc-in source path raw)
        ;; Validate alternates as setup entries before retaining them.
        probe (update source "slots" #(mapv (fn [entry] (if (= slot (get entry "name")) (assoc entry "attachment" name) entry)) %))]
    (restore document probe skin)
    (restore document source (:active-skin document "default"))))

(defn select-setup [document slot name]
  (let [source (source-data document)]
    (require-value! (or (nil? name) (coll/any? #{name} (placeholders document slot))) "Unknown attachment placeholder.")
    (restore document (update source "slots" #(mapv (fn [entry]
                                                      (if (= slot (get entry "name"))
                                                        (if name (assoc entry "attachment" name) (dissoc entry "attachment")) entry)) %))
             (:active-skin document "default"))))

(defn remove-entries
  "Delete [skin slot name] entries atomically, then validate remaining links."
  [document entries]
  (let [source (reduce (fn remove-attachment [source [skin slot name]]
                         (let [path ["skins" (skin-index source skin) "attachments" slot]]
                           (-> source
                               (update-in path dissoc name)
                               (update "animations" #(coll/map-vals
                                                       (fn [animation]
                                                         (if (get-in animation ["attachments" skin slot])
                                                           (update-in animation ["attachments" skin slot] dissoc name) animation)) %)))))
                       (source-data document) entries)]
    (restore document source (:active-skin document "default"))))

(defn remove-entry [document skin slot name]
  (remove-entries document [[skin slot name]]))

(defn rename-placeholder
  "Rename a slot placeholder across every skin and all attachment/deform keys."
  [document slot before after]
  (require-value! (coll/any? #{before} (placeholders document slot)) "Unknown attachment placeholder.")
  (require-value! (and (string? after) (not (string/blank? after))
                       (coll/not-any? #{after} (placeholders document slot))) "New placeholder name must be unique.")
  (let [source (source-data document)
        rename (fn rename-entry [entries]
                 (if-let [entry (get entries before)]
                   (assoc (dissoc entries before) after
                          (cond-> entry (contains? #{"region" "mesh" "linkedmesh"} (get entry "type" "region"))
                            (assoc "path" (get entry "path" (get entry "name" before))))) entries))
        source (-> source
                   (update "slots" #(mapv (fn [entry] (if (and (= slot (get entry "name")) (= before (get entry "attachment")))
                                                        (assoc entry "attachment" after) entry)) %))
                   (update "skins" #(mapv (fn [skin]
                                            (if-not (get-in skin ["attachments" slot]) skin
                                                    (update-in skin ["attachments" slot]
                                                               (fn [entries]
                                                                 (coll/map-vals (fn [raw] (if (= before (get raw "parent")) (assoc raw "parent" after) raw))
                                                                                (rename entries)))))) %))
                   (update "animations"
                           #(coll/map-vals
                              (fn [animation]
                                (cond-> animation
                                  (get-in animation ["slots" slot "attachment"])
                                  (update-in ["slots" slot "attachment"]
                                             (fn [frames] (mapv (fn [frame] (if (= before (get frame "name")) (assoc frame "name" after) frame)) frames)))
                                  (get animation "attachments")
                                  (update "attachments"
                                          (fn [skins] (coll/map-vals (fn [slots] (if (get slots slot) (update slots slot rename) slots)) skins))))) %)))]
    (restore document source (:active-skin document "default"))))

(defn create-skin
  "Create an empty named skin; shared default attachments remain fallback."
  [document name]
  (let [source (source-data document)]
    (require-value! (and (string? name) (not (string/blank? name))
                         (coll/not-any? #(= name (get % "name")) (get source "skins")))
                    "A skin needs a unique nonempty name.")
    (restore document (update source "skins" (fnil conj []) {"name" name "attachments" {}}) name)))

(defn compose-skin
  "Create a named combination. Later skins override matching placeholders;
  all required bones/constraints and winning attachment timelines are retained."
  [document name skin-names]
  (let [source (source-data document)
        _ (require-value! (and (string? name) (not (string/blank? name)) (pos? (count skin-names))
                               (coll/not-any? #(= name (get % "name")) (get source "skins"))) "Combined skin needs a unique name and at least one source skin.")
        skins (mapv #(get-in source ["skins" (skin-index source %)]) skin-names)
        combined (reduce (fn [result skin]
                           (-> (reduce (fn [result field]
                                         (assoc result field (coll/into-> (into (vec (get result field)) (get skin field)) [] (distinct))))
                                       result ["bones" "ik" "transform" "path" "physics"])
                               (update "attachments" #(merge-with merge % (get skin "attachments")))))
                         {"name" name "attachments" {}} skins)
        owners (reduce (fn [owners skin]
                         (reduce-kv (fn [owners slot entries]
                                      (reduce-kv (fn [owners placeholder _] (assoc owners [slot placeholder] (get skin "name"))) owners entries))
                                    owners (get skin "attachments"))) {} skins)
        source (-> source
                   (update "skins" conj combined)
                   (update "animations" #(coll/map-vals
                                           (fn [animation]
                                             (reduce-kv (fn [animation [slot placeholder] owner]
                                                          (if-let [tracks (get-in animation ["attachments" owner slot placeholder])]
                                                            (assoc-in animation ["attachments" name slot placeholder] tracks) animation))
                                                        animation owners)) %)))]
    (restore document source name)))

(defn remove-skin
  "Remove one skin and its private attachment timelines. Preserve shared files
  and switch away from a removed active skin within the same undo transaction."
  [document name]
  (let [source (source-data document)
        skins (get source "skins")
        remaining (filterv #(not= name (get % "name")) skins)]
    (skin-index source name)
    (require-value! (not= "default" name) "默认蒙皮不能删除。")
    (require-value! (pos? (count remaining)) "至少需要保留一个蒙皮。")
    (require-value!
      (coll/not-any? (fn references-deleted-skin [skin]
                       (coll/any? (fn references-in-slot [entries]
                                    (coll/any? #(and (= "linkedmesh" (get % "type"))
                                                     (= name (get % "skin" "default")))
                                               (coll/vals entries)))
                                  (coll/vals (get skin "attachments")))) remaining)
      "该蒙皮仍被其他蒙皮的链接网格引用，请先修改链接。")
    (let [source (-> source
                     (assoc "skins" remaining)
                     (update "animations"
                             #(coll/map-vals (fn remove-skin-timelines [animation]
                                               (reduce (fn remove-timelines [animation field]
                                                         (if (contains? animation field)
                                                           (update animation field dissoc name)
                                                           animation))
                                                       animation ["attachments" "deform"])) %)))
          active (:active-skin document "default")
          fallback (or (coll/first-where #(= "default" (get % "name")) remaining) (first remaining))]
      (restore document source (if (= active name) (get fallback "name") active)))))

(defn sequence-keys [document animation skin slot name]
  (let [source (source-data document)]
    (mapv (fn [frame] {:time (get frame "time") :index (get frame "index")
                       :mode (get frame "mode") :delay (get frame "delay") :interpolation "stepped"})
          (sequence/prepare-frames (get-in source ["animations" animation "attachments" skin slot name "sequence"] [])))))

(defn set-sequence-keyframe [document animation skin slot name frame]
  (require-value! (and (geometry/finite-number? (:time frame)) (<= 0 (:time frame))) "Sequence key time must be nonnegative and finite.")
  (let [source (source-data document)
        _ (require-value! (get-in source ["skins" (skin-index source skin) "attachments" slot name "sequence"]) "This attachment has no image sequence.")
        _ (require-value! (contains? (get source "animations") animation) "Unknown animation.")
        path ["animations" animation "attachments" skin slot name "sequence"]
        frames (get-in source path [])
        raw (merge (coll/first-where #(== (:time frame) (get % "time" 0.0)) frames)
                   (coll/into-> (select-keys frame [:time :index :mode :delay]) {} (map (fn [[key value]] [(clojure.core/name key) value]))))
        frames (vec (sort-by #(get % "time" 0.0) (conj (filterv #(not (== (:time frame) (get % "time" 0.0))) frames) raw)))]
    (sequence/prepare-frames frames)
    (restore document (assoc-in source path frames) (:active-skin document "default"))))

(defn remove-sequence-keyframe [document animation skin slot name time]
  (require-value! (and (geometry/finite-number? time) (<= 0 time)) "Sequence key time must be nonnegative and finite.")
  (let [source (source-data document)
        path ["animations" animation "attachments" skin slot name "sequence"]]
    (if-not (get-in source path)
      document
      (restore document (update-in source path #(filterv (fn [frame] (not (== time (get frame "time" 0.0)))) %))
               (:active-skin document "default")))))

(defn restore-image
  "Convert one mesh/linked mesh to a region. Detach dependent links and copy
  inherited deform keys before removing only the converted attachment's FFD."
  [document skin slot name]
  (let [source (source-data document)
        entries (coll/into-> (get source "skins") {}
                  (mapcat (fn [skin]
                            (mapv (fn [[name raw]] [[(get skin "name") slot name] raw])
                                  (vec (get-in skin ["attachments" slot]))))))
        key [skin slot name]
        _ (require-value! (contains? #{"mesh" "linkedmesh"} (get-in entries [key "type"])) "Select a mesh image to restore.")
        resolve-entry (fn resolve-entry [key]
                        (loop [key key chain []]
                          (let [raw (get entries key)]
                            (require-value! raw "Linked mesh parent does not exist.")
                            (require-value! (coll/not-any? #{key} chain) "Linked mesh parents form a cycle.")
                            (if (= "linkedmesh" (get raw "type"))
                              (recur [(get raw "skin" "default") slot (get raw "parent")] (conj chain key))
                              (reduce (fn [{:keys [raw owner]} child]
                                        (let [entry (get entries child)]
                                          {:raw (merge raw (dissoc entry "type" "parent" "skin" "timelines" "deform"))
                                           :owner (if (get entry "timelines" (get entry "deform" true)) owner child)}))
                                      {:raw raw :owner key} (rseq chain))))))
        depends (fn depends-on-target [entry]
                  (loop [key entry seen #{}]
                    (cond (= key [skin slot name]) true
                          (contains? seen key) false
                          (= "linkedmesh" (get-in entries [key "type"]))
                          (recur [(get-in entries [key "skin"] "default") slot (get-in entries [key "parent"])] (conj seen key))
                          :else false)))
        dependent (filterv #(and (not= key %) (depends %)) (vec (coll/keys entries)))
        source (reduce (fn detach-child [source [child-skin _ child-name :as child]]
                         (let [{:keys [raw owner]} (resolve-entry child)
                               [owner-skin _ owner-name] owner]
                           (-> source
                               (assoc-in ["skins" (skin-index source child-skin) "attachments" slot child-name] raw)
                               (update "animations"
                                       #(coll/map-vals
                                          (fn [animation]
                                            (let [frames (or (get-in animation ["attachments" owner-skin slot owner-name "deform"])
                                                             (get-in animation ["deform" owner-skin slot owner-name]))]
                                              (cond-> animation frames
                                                (assoc-in ["attachments" child-skin slot child-name "deform"] frames)))) %)))))
                       source dependent)
        raw (:raw (resolve-entry key))
        bones (:bones document)
        worlds (data/world-transforms bones)
        bone (:bone (coll/first-where #(= slot (:id %)) (:slots document)))
        attachment (data/decode-spine-attachment name raw bones worlds bone)
        vertices (coll/into-> (geometry/mesh-world-vertices attachment (get worlds bone) worlds []) []
                   (partition-all 2) (mapcat #(geometry/inverse-transform-point (get worlds bone) %)))
        region (geometry/mesh-to-region attachment vertices)
        image (merge (apply dissoc raw ["vertices" "uvs" "triangles" "hull" "edges" "vertexCount" "parent" "skin" "timelines" "deform"])
                     {"type" "region" "x" (:x region) "y" (:y region) "rotation" (:rotation region)
                      "scaleX" (:scale-x region) "scaleY" (:scale-y region)})
        source (-> source
                   (assoc-in ["skins" (skin-index source skin) "attachments" slot name] image)
                   (update "animations" #(coll/map-vals
                                           (fn [animation]
                                             (cond-> animation
                                               (get-in animation ["attachments" skin slot name "deform"])
                                               (update-in ["attachments" skin slot name] dissoc "deform")
                                               (get-in animation ["deform" skin slot name])
                                               (update-in ["deform" skin slot] dissoc name))) %)))]
    (merge (restore document source (:active-skin document "default"))
           (select-keys document [:bones :ik :transform :path :physics]))))
