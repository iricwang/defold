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

(ns editor.skeletal-animation-data
  "First-party 2D skeleton documents. Coordinates are pixels, Y points up,
  angles are degrees, and key values are absolute local bone values. Bones
  inherit ordinary affine parent transforms. Slots render in vector order.
  Unsupported Spine features remain in the source JSON and produce preview
  warnings, so editing supported data never silently discards them."
  (:require [clojure.data.json :as json]
            [clojure.string :as string]
            [util.coll :as coll]))

(set! *warn-on-reflection* true)

(def supported-properties ["x" "y" "rotation" "scale-x" "scale-y"])
(def supported-interpolations ["linear" "stepped" "bezier"])

(defn- require-value! [valid message context]
  (when-not valid
    (throw (ex-info message context))))

(defn- finite-number? [value]
  (and (number? value) (Double/isFinite (double value))))

(defn- valid-id? [value]
  (and (string? value) (not (string/blank? value))))

(defn- validate-numbers! [value fields context]
  (run! (fn [field]
          (require-value! (finite-number? (get value field))
                          (str "Expected a finite number for " (name field) ".")
                          (assoc context :field field)))
        fields))

(defn- validate-ids! [items context]
  (require-value! (vector? items) "Expected an array." context)
  (reduce (fn [ids item]
            (let [id (:id item)]
              (require-value! (and (map? item) (valid-id? id) (not (contains? ids id)))
                              "Names must be nonempty and unique." (assoc context :id id))
              (conj ids id)))
          #{}
          items))

(defn- validate-bones! [bones]
  (let [ids (validate-ids! bones {:section :bones})
        parents (into {} (map (juxt :id :parent)) bones)]
    (run! (fn [bone]
            (let [id (:id bone)
                  parent (:parent bone)]
              (require-value! (or (nil? parent) (contains? ids parent))
                              "A bone parent does not exist." {:bone id :parent parent})
              (validate-numbers! bone [:x :y :rotation :scale-x :scale-y :length] {:bone id})
              (require-value! (not (neg? (double (:length bone))))
                              "Bone length cannot be negative." {:bone id})
              (loop [ancestor parent
                     visited #{id}]
                (when ancestor
                  (require-value! (not (contains? visited ancestor))
                                  "Bone parents form a cycle." {:bone id :parent ancestor})
                  (recur (get parents ancestor) (conj visited ancestor))))))
          bones)
    ids))

(defn- validate-color! [color context]
  (require-value! (and (vector? color)
                       (= 4 (count color))
                       (coll/every? #(and (finite-number? %) (<= 0.0 (double %) 1.0)) color))
                  "Color must contain four finite values between zero and one." context))

(defn- validate-mesh! [{:keys [vertices uvs triangles weights]} bone-ids context]
  (require-value! (and (vector? vertices) (even? (count vertices)) (>= (count vertices) 6)
                       (coll/every? finite-number? vertices)
                       (vector? uvs) (= (count vertices) (count uvs)) (coll/every? finite-number? uvs)
                       (vector? triangles) (zero? (mod (count triangles) 3))
                       (coll/every? #(and (integer? %) (<= 0 % (dec (quot (count vertices) 2)))) triangles))
                  "Mesh vertices, UVs and triangle indices are invalid." context)
  (when weights
    (require-value! (and (vector? weights) (= (count weights) (quot (count vertices) 2)))
                    "Mesh weights must contain an influence array for each vertex." context)
    (run! (fn [influences]
            (require-value! (and (vector? influences) (pos? (count influences))) "Every weighted vertex needs an influence." context)
            (reduce (fn [seen {:keys [bone weight x y]}]
                      (require-value! (and (contains? bone-ids bone) (not (contains? seen bone))
                                           (finite-number? weight) (<= 0.0 (double weight) 1.0)
                                           (finite-number? x) (finite-number? y))
                                      "Mesh influences need unique existing bones and finite positions/weights." context)
                      (conj seen bone)) #{} influences)
            (require-value! (< (Math/abs (- 1.0 (double (transduce (map :weight) + 0.0 influences)))) 0.001)
                            "Mesh vertex weights must sum to one." context))
          weights)))

(defn- validate-keys! [keys duration context value-valid]
  (require-value! (vector? keys) "Keys must be an array." context)
  (reduce (fn [previous {:keys [time interpolation curve] :as key}]
            (require-value! (and (finite-number? time) (<= 0.0 (double time) (double duration))
                                 (< (double previous) (double time))
                                 (coll/any? #{interpolation} supported-interpolations)
                                 (value-valid key)
                                 (or (not= "bezier" interpolation)
                                     (and (vector? curve) (= 4 (count curve))
                                          (coll/every? finite-number? curve)
                                          (<= (double time) (double (nth curve 0)) (double duration))
                                          (<= (double time) (double (nth curve 2)) (double duration)))))
                            "Keys need ordered unique times, finite values and a valid interpolation curve." context)
            time)
          -1.0 keys)
  (run! (fn [index]
          (let [key (nth keys index)
                next-time (:time (nth keys (inc index)))]
            (when (= "bezier" (:interpolation key))
              (require-value! (and (<= (double (nth (:curve key) 0)) (double next-time))
                                   (<= (double (nth (:curve key) 2)) (double next-time)))
                              "Bezier time controls must remain within their key interval." context))))
        (range (max 0 (dec (count keys))))))

(defn deform-size
  "Number of scalar offsets: two per vertex, or two per weighted influence."
  [attachment]
  (if-let [weights (:weights attachment)]
    (* 2 (transduce (map count) + 0 weights))
    (count (:vertices attachment))))

(defn validate-document
  "Validate and return a document without discarding extension fields. Throws
  ex-info with the affected section/id/field for malformed data."
  [document]
  (require-value! (and (map? document)
                       (= "defold.skeleton" (:schema document))
                       (= 1 (:version document))
                       (valid-id? (:name document)))
                  "Expected a defold.skeleton version 1 document with a name." {})
  (let [bone-ids (validate-bones! (:bones document))]
    (validate-ids! (:slots document) {:section :slots})
    (run! (fn [{:keys [id bone attachment color]}]
            (require-value! (contains? bone-ids bone) "A slot bone does not exist." {:slot id :bone bone})
            (when color (validate-color! color {:slot id}))
            (when attachment
              (let [path (:path attachment)]
                (require-value! (and (map? attachment)
                                     (string? path)
                                     (string/starts-with? path "/")
                                     (string/ends-with? (string/lower-case path) ".png")
                                     (not (string/includes? path "\\"))
                                     (not (coll/any? #{"." ".." ""} (subvec (string/split path #"/" -1) 1))))
                                "An attachment needs an absolute project PNG path without traversal." {:slot id :path path}))
              (validate-numbers! attachment [:x :y :rotation :scale-x :scale-y :width :height] {:slot id})
              (require-value! (and (pos? (double (:width attachment))) (pos? (double (:height attachment))))
                              "Attachment dimensions must be positive." {:slot id})
              (validate-color! (:color attachment) {:slot id})
              (require-value! (contains? #{"region" "mesh"} (get attachment :type "region")) "Unsupported attachment type." {:slot id})
              (when (= "mesh" (:type attachment)) (validate-mesh! attachment bone-ids {:slot id}))))
          (:slots document))
    (validate-ids! (get document :ik []) {:section :ik})
    (run! (fn [{:keys [id bones target mix bend-positive]}]
            (require-value! (and (vector? bones) (<= 1 (count bones) 2)
                                 (= (count bones) (count (set bones)))
                                 (coll/every? bone-ids bones) (contains? bone-ids target)
                                 (not (contains? (set bones) target))
                                 (finite-number? mix) (<= 0.0 (double mix) 1.0)
                                 (boolean? bend-positive))
                            "IK requires one or two existing bones, a separate target and a finite mix." {:ik id})
            (require-value! (:parent (coll/first-where #(= (first bones) (:id %)) (:bones document)))
                            "IK cannot constrain a root bone. Add a parent bone above the chain first." {:ik id})
            (when (= 2 (count bones))
              (require-value! (= (first bones) (:parent (coll/first-where #(= (second bones) (:id %)) (:bones document))))
                              "Two-bone IK requires a direct parent-child chain." {:ik id})))
          (:ik document))
    (validate-ids! (:animations document) {:section :animations})
    (run! (fn [{:keys [id duration tracks]}]
            (require-value! (and (finite-number? duration) (not (neg? (double duration))))
                            "Animation duration must be finite and nonnegative." {:animation id})
            (require-value! (vector? tracks) "Animation tracks must be an array." {:animation id})
            (reduce (fn [seen {:keys [bone property keys]}]
                      (let [context {:animation id :bone bone :property property}]
                        (require-value! (and (contains? bone-ids bone)
                                             (coll/any? #{property} supported-properties)
                                             (not (contains? seen [bone property])))
                                        "Animation tracks need an existing bone and a unique supported property." context)
                        (validate-keys! keys duration context #(finite-number? (:value %)))
                        (conj seen [bone property])))
                    #{}
                    tracks)
            (let [animation (coll/first-where #(= id (:id %)) (:animations document))]
              (require-value! (vector? (get animation :deforms [])) "Deform tracks must be an array." {:animation id})
              (reduce (fn [seen {:keys [slot keys]}]
                        (let [attachment (:attachment (coll/first-where #(= slot (:id %)) (:slots document)))]
                          (require-value! (and (= "mesh" (:type attachment)) (not (contains? seen slot))) "Deform tracks need a unique mesh slot." {:animation id :slot slot})
                          (validate-keys! keys duration {:animation id :slot slot}
                                          #(and (vector? (:vertices %)) (= (deform-size attachment) (count (:vertices %)))
                                                (coll/every? finite-number? (:vertices %))))
                          (conj seen slot))) #{} (:deforms animation))
              (require-value! (vector? (get animation :ik-tracks [])) "IK tracks must be an array." {:animation id})
              (let [ik-ids (into #{} (map :id) (:ik document))]
                (reduce (fn [seen {:keys [ik keys]}]
                          (require-value! (and (contains? ik-ids ik) (not (contains? seen ik))) "IK timeline references a missing or duplicate constraint." {:animation id :ik ik})
                          (validate-keys! keys duration {:animation id :ik ik}
                                          #(and (finite-number? (:mix %)) (<= 0.0 (double (:mix %)) 1.0) (boolean? (:bend-positive %))))
                          (conj seen ik)) #{} (:ik-tracks animation)))))
          (:animations document)))
  document)

(defn new-document
  "Create a root bone and an empty one-second animation."
  [name]
  (validate-document
    {:schema "defold.skeleton"
     :version 1
     :name name
     :bones [{:id "root" :parent nil :x 0.0 :y 0.0 :rotation 0.0 :scale-x 1.0 :scale-y 1.0 :length 80.0}]
     :slots []
     :animations [{:id "default" :duration 1.0 :tracks []}]}))

(defn read-json [text]
  (validate-document (json/read-str text :key-fn keyword)))

(defn write-json [document]
  (str (json/write-str (validate-document document)
                       :key-fn #(if (keyword? %) (subs (str %) 1) %)
                       :escape-slash false
                       :indent true)
       "\n"))

(defn- item-index [document section id]
  (or (reduce-kv (fn [_ index item] (when (= id (:id item)) (reduced index))) nil (get document section))
      (throw (ex-info (str "Unknown " (name section) " item: " id) {:section section :id id}))))

(defn- rename-map-key [value before after]
  (if (contains? value before) (assoc (dissoc value before) after (get value before)) value))

(defn- rename-source-bone [text before after]
  (let [rename #(if (= before %) after %)
        source (json/read-str text)
        source (-> source
                   (update "bones" #(mapv (fn [bone] (cond-> (update bone "name" rename) (get bone "parent") (update "parent" rename))) %))
                   (update "slots" #(mapv (fn [slot] (update slot "bone" rename)) %))
                   (update "skins" #(mapv (fn [skin] (cond-> skin (get skin "bones") (update "bones" (fn [bones] (mapv rename bones))))) %))
                   (update "animations" #(into {} (map (fn [[id animation]] [id (cond-> animation (get animation "bones") (update "bones" rename-map-key before after))])) %)))
        source (reduce (fn [source section]
                         (if-not (contains? source section)
                           source
                           (update source section #(mapv (fn [constraint]
                                                           (cond-> constraint
                                                             (get constraint "bones") (update "bones" (fn [bones] (mapv rename bones)))
                                                             (get constraint "bone") (update "bone" rename)
                                                             (and (not= "path" section) (get constraint "target")) (update "target" rename))) %))))
                       source ["ik" "transform" "path" "physics"])]
    (json/write-str source)))

(defn- rename-source-slot [text before after]
  (let [source (json/read-str text)
        rename #(if (= before %) after %)]
    (json/write-str
      (-> source
          (update "slots" #(mapv (fn [slot] (update slot "name" rename)) %))
          (update "skins" #(mapv (fn [skin] (update skin "attachments" rename-map-key before after)) %))
          (update "path" #(mapv (fn [constraint] (update constraint "target" rename)) %))
          (update "animations"
                  #(into {} (map (fn [[id animation]]
                                   [id (cond-> animation
                                         (get animation "slots") (update "slots" rename-map-key before after)
                                         (get animation "attachments") (update "attachments" (fn [skins] (into {} (map (fn [[skin slots]] [skin (rename-map-key slots before after)])) skins)))
                                         (get animation "drawOrder") (update "drawOrder" (fn [frames] (mapv (fn [frame] (cond-> frame (get frame "offsets") (update "offsets" (fn [offsets] (mapv (fn [offset] (update offset "slot" rename)) offsets))))) frames))))])) %))))))

(defn- require-source-removal-safe! [document deleted-bones deleted-slots]
  (when-let [text (:spine-source document)]
    (let [source (json/read-str text)]
      (run! (fn [section]
              (run! (fn [constraint]
                      (require-value!
                        (not (or (contains? deleted-bones (get constraint "bone"))
                                 (coll/any? deleted-bones (get constraint "bones" []))
                                 (contains? (if (= "path" section) deleted-slots deleted-bones) (get constraint "target"))))
                        (str "Cannot remove an item referenced by preserved " section " constraints. Edit that constraint in Spine first.")
                        {:constraint (get constraint "name")}))
                    (get source section [])))
            ["transform" "path" "physics"])
      (run! (fn [skin]
              (require-value! (not (coll/any? deleted-bones (get skin "bones" [])))
                              "Cannot remove a bone required by a preserved skin." {:skin (get skin "name")}))
            (get source "skins" []))
      (when (pos? (count deleted-slots))
        (run! (fn [[id animation]]
                (require-value! (and (zero? (count (get animation "drawOrder"))) (zero? (count (get animation "draworder"))))
                                "Cannot remove slots while preserved draw-order animation is present. Edit that timeline in Spine first." {:animation id}))
              (get source "animations" {}))))))

(defn add-bone [document bone]
  (validate-document
    (update (validate-document document) :bones conj
            (merge {:parent nil :x 0.0 :y 0.0 :rotation 0.0 :scale-x 1.0 :scale-y 1.0 :length 80.0} bone))))

(defn update-bone
  "Update setup pose or reparent a bone. Renaming updates child, slot and track references."
  [document bone-id changes]
  (let [document (validate-document document)
        index (item-index document :bones bone-id)
        new-id (get changes :id bone-id)
        rename-reference #(if (= bone-id %) new-id %)]
    (validate-document
      (-> (cond-> document
            (and (not= bone-id new-id) (:spine-source document))
            (update :spine-source rename-source-bone bone-id new-id))
          (update-in [:bones index] merge changes)
          (update :bones #(mapv (fn [bone] (update bone :parent rename-reference)) %))
          (update :slots #(mapv (fn [slot]
                                 (cond-> (update slot :bone rename-reference)
                                   (get-in slot [:attachment :weights])
                                   (update-in [:attachment :weights] (fn [weights] (mapv (fn [influences] (mapv (fn [influence] (update influence :bone rename-reference)) influences)) weights))))) %))
          (update :ik #(mapv (fn [constraint] (-> constraint (update :bones (fn [bones] (mapv rename-reference bones))) (update :target rename-reference))) %))
          (update :animations #(mapv (fn [animation]
                                       (update animation :tracks
                                               (fn [tracks] (mapv (fn [track] (update track :bone rename-reference)) tracks)))) %))))))

(defn remove-bone
  "Delete a bone, all descendants, their slots and their animation tracks."
  [document bone-id]
  (let [document (validate-document document)
        _ (item-index document :bones bone-id)
        deleted (loop [ids #{bone-id}]
                  (let [expanded (into ids (comp (filter #(contains? ids (:parent %))) (map :id)) (:bones document))]
                    (if (= ids expanded) ids (recur expanded))))
        removed-slots (into #{} (comp (filter #(contains? deleted (:bone %))) (map :id)) (:slots document))
        removed-ik (into #{} (comp (filter #(or (contains? deleted (:target %)) (coll/any? deleted (:bones %)))) (map :id)) (:ik document))]
    (require-source-removal-safe! document deleted removed-slots)
    (require-value! (not (coll/any? (fn [slot]
                                     (and (not (contains? removed-slots (:id slot)))
                                          (coll/any? #(contains? deleted (:bone %)) (into [] cat (get-in slot [:attachment :weights])))))
                                   (:slots document)))
                    "A surviving mesh is weighted to this bone. Remove those influences before deleting it." {:bone bone-id})
    (-> document
        (update :bones #(filterv (fn [bone] (not (contains? deleted (:id bone)))) %))
        (update :slots #(filterv (fn [slot] (not (contains? deleted (:bone slot)))) %))
        (update :ik #(filterv (fn [constraint] (not (or (contains? deleted (:target constraint)) (coll/any? deleted (:bones constraint))))) %))
        (update :animations #(mapv (fn [animation]
                                     (-> animation
                                         (update :ik-tracks (fn [tracks] (filterv (fn [track] (not (contains? removed-ik (:ik track)))) tracks)))
                                         (update :deforms (fn [deforms] (filterv (fn [track] (not (contains? removed-slots (:slot track)))) deforms)))
                                         (update :tracks (fn [tracks] (filterv (fn [track] (not (contains? deleted (:bone track)))) tracks))))) %)))))

(defn- normalize-slot [slot]
  (cond-> slot
    (:attachment slot)
    (update :attachment #(merge {:x 0.0 :y 0.0 :rotation 0.0 :scale-x 1.0 :scale-y 1.0
                                :width 100.0 :height 100.0 :color [1.0 1.0 1.0 1.0]} %))))

(defn- remap-deform-weights [vertices before after slot-bone]
  (let [old-influences (or (:weights before) (vec (repeat (quot (count (:vertices before)) 2) [{:bone slot-bone}])))]
    (if (= (:weights before) (:weights after))
      vertices
      (do
        (require-value! (= (count (:vertices before)) (count (:vertices after)))
                        "Changing mesh topology requires removing existing deform keys first." {})
        (require-value! (:weights after) "Unbinding a mesh with deform animation requires removing its deform keys first." {})
        (loop [vertex 0 offset 0 result []]
          (if (= vertex (count old-influences))
            result
            (let [old (nth old-influences vertex)
                  offsets (into {} (map-indexed (fn [index influence] [(:bone influence) (subvec vertices (+ offset (* index 2)) (+ offset (* index 2) 2))])) old)
                  values (into [] (mapcat #(get offsets (:bone %) [0.0 0.0])) (nth (:weights after) vertex))]
              (recur (inc vertex) (long (+ offset (* 2 (count old)))) (into result values)))))))))

(defn add-slot [document slot]
  (validate-document (update (validate-document document) :slots conj (normalize-slot slot))))

(defn update-slot
  "Update a slot, merging attachment fields. Weight edits remap existing FFD
  keys by vertex/bone identity. Removing the mesh removes its deform tracks."
  [document slot-id changes]
  (let [document (validate-document document)
        index (item-index document :slots slot-id)
        original (get-in document [:slots index])
        changes (if (map? (:attachment changes))
                  (update changes :attachment #(merge (:attachment original) %))
                  changes)
        updated (cond-> (normalize-slot (merge original changes))
                  (contains? changes :attachment) (dissoc :unsupported-attachment))
        update-track (fn [track]
                       (if (not= slot-id (:slot track))
                         track
                         (when (= "mesh" (get-in updated [:attachment :type]))
                           (-> track
                               (assoc :slot (get changes :id slot-id))
                               (update :keys (fn [keys]
                                               (mapv (fn [key] (update key :vertices remap-deform-weights
                                                                       (:attachment original) (:attachment updated) (:bone original))) keys)))))))
        document (cond-> document
                   (and (:spine-source document) (contains? changes :id) (not= slot-id (:id changes)))
                   (update :spine-source rename-source-slot slot-id (:id changes)))]
    (validate-document
      (-> document
          (assoc-in [:slots index] updated)
          (update :animations (fn [animations]
                                (mapv (fn [animation] (update animation :deforms #(into [] (keep update-track) %))) animations)))))))

(defn remove-slot [document slot-id]
  (let [document (validate-document document)]
    (item-index document :slots slot-id)
    (require-source-removal-safe! document #{} #{slot-id})
    (-> document
        (update :slots #(filterv (fn [slot] (not= slot-id (:id slot))) %))
        (update :animations #(mapv (fn [animation] (update animation :deforms (fn [deforms] (filterv (fn [track] (not= slot-id (:slot track))) deforms)))) %)))))

(defn add-animation [document animation]
  (let [document (validate-document document)
        updated (validate-document (update document :animations conj (merge {:duration 1.0 :tracks []} animation)))]
    (cond-> updated
      (:spine-source document)
      (update :spine-source #(json/write-str (update (json/read-str %) "animations" dissoc (:id animation)))))))

(defn copy-animation
  "Copy editable tracks and preserved Spine timelines into a new animation ID."
  [document animation-id new-id]
  (let [document (validate-document document)
        animation (get-in document [:animations (item-index document :animations animation-id)])
        copied (add-animation document (assoc animation :id new-id))]
    (if-let [text (:spine-source document)]
      (let [source (json/read-str text)
            original (get-in source ["animations" animation-id])]
        (cond-> copied
          original (update :spine-source #(json/write-str (assoc-in (json/read-str %) ["animations" new-id] original)))))
      copied)))

(defn update-animation
  "Update animation metadata. Shortening past an existing key is rejected."
  [document animation-id changes]
  (let [document (validate-document document)
        renamed (cond-> document
                  (and (:spine-source document) (contains? changes :id) (not= animation-id (:id changes)))
                  (update :spine-source #(json/write-str (update (json/read-str %) "animations"
                                                               (fn [animations]
                                                                 (rename-map-key (dissoc animations (:id changes)) animation-id (:id changes)))))))]
    (validate-document (update-in renamed [:animations (item-index document :animations animation-id)] merge changes))))

(defn remove-animation [document animation-id]
  (let [document (validate-document document)]
    (item-index document :animations animation-id)
    (cond-> (update document :animations #(filterv (fn [animation] (not= animation-id (:id animation))) %))
      (:spine-source document)
      (update :spine-source #(json/write-str (update (json/read-str %) "animations" dissoc animation-id))))))

(defn set-keyframe
  "Insert/replace a key. Extends the animation duration when necessary."
  [document animation-id bone-id property time value interpolation]
  (let [document (validate-document document)
        index (item-index document :animations animation-id)
        tracks (get-in document [:animations index :tracks])
        same-track #(and (= bone-id (:bone %)) (= property (:property %)))
        track (or (coll/first-where same-track tracks) {:bone bone-id :property property :keys []})
        key (if (map? interpolation)
              {:time time :value value :interpolation (:type interpolation) :curve (:curve interpolation)}
              {:time time :value value :interpolation interpolation})]
    (require-value! (and (finite-number? time) (not (neg? (double time)))) "Key time must be finite and nonnegative." {})
    (validate-document
      (-> document
          (update-in [:animations index :duration] max time)
          (assoc-in [:animations index :tracks]
                    (conj (filterv (complement same-track) tracks)
                          (update track :keys #(vec (sort-by :time (conj (filterv (fn [existing] (not= (double time) (double (:time existing)))) %) key))))))))))

(defn remove-keyframe [document animation-id bone-id property time]
  (require-value! (finite-number? time) "Key time must be finite." {})
  (let [document (validate-document document)
        index (item-index document :animations animation-id)]
    (update-in document [:animations index :tracks]
               #(into []
                      (comp (map (fn [track]
                                   (if (and (= bone-id (:bone track)) (= property (:property track)))
                                     (update track :keys (fn [keys] (filterv (fn [key] (not (== time (:time key)))) keys)))
                                     track)))
                            (filter (fn [track] (pos? (count (:keys track))))))
                      %))))

(defn- move-track-keyframe [document animation-id section match-track old-time new-time]
  (require-value! (finite-number? old-time) "Key time must be finite." {})
  (let [document (validate-document document)
        index (item-index document :animations animation-id)
        tracks (get-in document [:animations index section])
        track-index (or (reduce-kv (fn [_ i track] (when (match-track track) (reduced i))) nil tracks)
                        (throw (ex-info "Unknown animation track." {:section section})))
        keys (:keys (nth tracks track-index))
        key (coll/first-where #(== old-time (:time %)) keys)]
    (require-value! key "Unknown animation key." {:time old-time})
    (require-value! (and (finite-number? new-time) (not (neg? (double new-time)))) "Key time must be finite and nonnegative." {})
    (let [moved (vec (sort-by :time (conj (filterv #(and (not (== old-time (:time %))) (not (== new-time (:time %)))) keys) (assoc key :time new-time))))
          duration (max (get-in document [:animations index :duration]) new-time)
          adjusted (mapv (fn [i current]
                           (if (not= "bezier" (:interpolation current))
                             current
                             (let [source-time (if (== new-time (:time current)) old-time (:time current))
                                   old-right (:time (coll/first-where #(> (double (:time %)) (double source-time)) keys))
                                   right (:time (get moved (inc i)))
                                   [t1 v1 t2 v2] (:curve current)
                                   remap (fn [t]
                                           (if (and old-right right (> (double old-right) (double source-time)))
                                             (+ (:time current) (* (/ (- t source-time) (- old-right source-time)) (- right (:time current))))
                                             (max (:time current) (min duration (+ t (- (:time current) source-time))))))]
                               (cond-> (assoc current :curve [(remap t1) v1 (remap t2) v2])
                                 (:spine-frame-source current)
                                 (update :spine-frame-source
                                         #(let [frame (json/read-str %)]
                                            (json/write-str
                                              (cond-> frame
                                                (vector? (get frame "curve"))
                                                (update "curve" (fn [curve]
                                                                  (mapv (fn [index value]
                                                                          (if (and (>= index 4) (even? index)) (remap value) value))
                                                                        (range (count curve)) curve)))))))))))
                         (range (count moved)) moved)]
      (validate-document (-> document
                             (assoc-in [:animations index :duration] duration)
                             (assoc-in [:animations index section track-index :keys] adjusted))))))

(defn move-keyframe
  "Move a bone key, rescaling adjacent Bezier controls; collisions replace keys."
  [document animation-id bone-id property old-time new-time]
  (move-track-keyframe document animation-id :tracks
                       #(and (= bone-id (:bone %)) (= property (:property %))) old-time new-time))

(defn move-deform-keyframe [document animation-id slot-id old-time new-time]
  (move-track-keyframe document animation-id :deforms #(= slot-id (:slot %)) old-time new-time))

(defn- key-spine-frame [key originals]
  (if (contains? key :spine-frame-source)
    (when-let [source (:spine-frame-source key)] (json/read-str source))
    (coll/first-where #(== (double (:time key)) (double (get % "time" 0.0))) originals)))

(defn move-ik-keyframe [document animation-id ik-id old-time new-time]
  (let [document (validate-document document)
        index (item-index document :animations animation-id)
        document (if-let [source (:spine-source document)]
                   (let [originals (get-in (json/read-str source) ["animations" animation-id "ik" ik-id])]
                     (update-in document [:animations index :ik-tracks]
                                #(mapv (fn [track]
                                         (if (not= ik-id (:ik track)) track
                                             (update track :keys (fn [keys]
                                                                  (mapv (fn [key]
                                                                          (assoc key :spine-frame-source
                                                                                 (some-> (key-spine-frame key originals) json/write-str))) keys))))) %)))
                   document)]
    (move-track-keyframe document animation-id :ik-tracks #(= ik-id (:ik %)) old-time new-time)))

(defn remove-ik-keyframe [document animation-id ik-id time]
  (require-value! (finite-number? time) "Key time must be finite." {})
  (let [document (validate-document document)
        index (item-index document :animations animation-id)]
    (update-in document [:animations index :ik-tracks]
               #(into [] (comp (map (fn [track]
                                      (if (not= ik-id (:ik track)) track
                                          (update track :keys (fn [keys] (filterv (fn [key] (not (== time (:time key)))) keys))))))
                               (filter (fn [track] (pos? (count (:keys track)))))) %))))

(defn remove-deform-keyframe [document animation-id slot-id time]
  (require-value! (finite-number? time) "Key time must be finite." {})
  (let [document (validate-document document)
        index (item-index document :animations animation-id)]
    (update-in document [:animations index :deforms]
               #(into [] (comp (map (fn [track]
                                      (if (not= slot-id (:slot track)) track
                                          (update track :keys (fn [keys] (filterv (fn [key] (not (== time (:time key)))) keys))))))
                               (filter (fn [track] (pos? (count (:keys track)))))) %))))

(defn set-ik-keyframe [document animation-id ik-id time mix bend-positive interpolation]
  (require-value! (and (finite-number? time) (not (neg? (double time)))) "Key time must be finite and nonnegative." {})
  (let [document (validate-document document)
        index (item-index document :animations animation-id)
        tracks (get-in document [:animations index :ik-tracks] [])
        track (or (coll/first-where #(= ik-id (:ik %)) tracks) {:ik ik-id :keys []})
        existing (coll/first-where #(== time (:time %)) (:keys track))
        originals (when-let [source (:spine-source document)] (get-in (json/read-str source) ["animations" animation-id "ik" ik-id]))
        original-frame (when existing (key-spine-frame existing originals))
        key (merge {:time time :mix mix :bend-positive bend-positive}
                   (if (map? interpolation) {:interpolation (:type interpolation) :curve (:curve interpolation)} {:interpolation interpolation}))
        key (cond-> key
              (or (:spine-source document) (contains? existing :spine-frame-source))
              (assoc :spine-frame-source (some-> original-frame json/write-str)))]
    (require-value! (or (not (and (vector? (get original-frame "curve")) (> (count (get original-frame "curve")) 4)))
                       (= "bezier" (:interpolation key)))
                    "This IK key has a preserved softness Bezier channel. Keep Bezier interpolation; linear or stepped would discard the softness curve."
                    {:animation animation-id :ik ik-id :time time})
    (validate-document (-> document
                           (update-in [:animations index :duration] max time)
                           (assoc-in [:animations index :ik-tracks]
                                     (conj (filterv #(not= ik-id (:ik %)) tracks)
                                           (update track :keys #(vec (sort-by :time (conj (filterv (fn [key] (not (== time (:time key)))) %) key))))))))))

(defn- cubic-value [a b c d t]
  (let [u (- 1.0 t)]
    (+ (* u u u a) (* 3.0 u u t b) (* 3.0 u t t c) (* t t t d))))

(defn- curve-value [left right time]
  (let [[t1 v1 t2 v2] (:curve left)
        parameter (loop [low 0.0 high 1.0 iteration 0]
                    (let [middle (* 0.5 (+ low high))]
                      (if (= 40 iteration)
                        middle
                        (if (< (double (cubic-value (:time left) t1 t2 (:time right) middle)) (double time))
                          (recur middle high (inc iteration))
                          (recur low middle (inc iteration))))))]
    (cubic-value (:value left) v1 v2 (:value right) parameter)))

(defn- sample-track-impl [keys time setup-value]
  (if (or (zero? (count keys)) (< (double time) (double (:time (first keys)))))
    setup-value
    (let [left (reduce (fn [previous key] (if (> (double (:time key)) (double time)) (reduced previous) key)) (first keys) keys)
          right (coll/first-where #(> (double (:time %)) (double time)) keys)]
      (cond
        (or (nil? right) (= "stepped" (:interpolation left))) (:value left)
        (= "bezier" (:interpolation left)) (curve-value left right time)
        :else (+ (double (:value left))
                 (* (/ (- (double time) (double (:time left))) (- (double (:time right)) (double (:time left))))
                    (- (double (:value right)) (double (:value left)))))))))

(defn set-deform-keyframe
  "Record native Spine offsets (per influence for weighted meshes)."
  [document animation-id slot-id time vertices interpolation]
  (let [document (validate-document document)
        index (item-index document :animations animation-id)
        slot (get-in document [:slots (item-index document :slots slot-id)])
        deforms (get-in document [:animations index :deforms] [])
        track (or (coll/first-where #(= slot-id (:slot %)) deforms)
                  {:slot slot-id :attachment (get-in slot [:attachment :name] slot-id)
                   :skin (get slot :spine-skin "default") :keys []})
        key (if (map? interpolation)
              {:time time :vertices vertices :interpolation (:type interpolation) :curve (:curve interpolation)}
              {:time time :vertices vertices :interpolation interpolation})]
    (require-value! (and (finite-number? time) (not (neg? (double time)))) "Deform time must be finite and nonnegative." {})
    (validate-document
      (-> document
          (update-in [:animations index :duration] max time)
          (assoc-in [:animations index :deforms]
                    (conj (filterv #(not= slot-id (:slot %)) deforms)
                          (update track :keys #(vec (sort-by :time (conj (filterv (fn [key] (not (== time (:time key)))) %) key))))))))))

(defn sample-deforms [document animation-id time]
  (validate-document document)
  (require-value! (finite-number? time) "Sample time must be finite." {})
  (if (nil? animation-id)
    {}
    (let [animation (get-in document [:animations (item-index document :animations animation-id)])
          time (max 0.0 (min (double time) (double (:duration animation))))]
      (into {}
            (map (fn [{:keys [slot keys]}]
                   (let [left (reduce (fn [previous key] (if (> (double (:time key)) time) (reduced previous) key)) nil keys)
                         right (coll/first-where #(> (double (:time %)) time) keys)
                         size (deform-size (:attachment (coll/first-where #(= slot (:id %)) (:slots document))))]
                     [slot (cond
                             (nil? left) (vec (repeat size 0.0))
                             (or (nil? right) (= "stepped" (:interpolation left))) (:vertices left)
                             :else (let [alpha (if (= "bezier" (:interpolation left))
                                                 (curve-value (assoc left :value 0.0) (assoc right :value 1.0) time)
                                                 (/ (- time (double (:time left))) (- (double (:time right)) (double (:time left)))))]
                                     (mapv (fn [a b] (+ a (* alpha (- b a)))) (:vertices left) (:vertices right))))])))
            (:deforms animation)))))

(defn add-ik [document constraint]
  (validate-document (update (validate-document document) :ik (fnil conj [])
                            (merge {:mix 1.0 :bend-positive true} constraint))))

(defn- rename-source-ik [text before after]
  (let [rename #(if (= before %) after %)
        source (json/read-str text)]
    (json/write-str
      (-> source
          (update "ik" #(mapv (fn [constraint] (update constraint "name" rename)) %))
          (update "animations" #(coll/map-vals (fn [animation] (cond-> animation (get animation "ik") (update "ik" rename-map-key before after))) %))
          (update "skins" #(mapv (fn [skin] (cond-> skin (get skin "ik") (update "ik" (fn [ids] (mapv rename ids))))) %))))))

(defn update-ik [document ik-id changes]
  (let [document (validate-document document)
        new-id (get changes :id ik-id)
        renamed (cond-> document
                  (and (:spine-source document) (not= ik-id new-id))
                  (update :spine-source rename-source-ik ik-id new-id))
        update-track #(if (= ik-id (:ik %)) (assoc % :ik new-id) %)]
    (validate-document
      (-> renamed
          (update-in [:ik (item-index document :ik ik-id)] merge changes)
          (update :animations (fn [animations]
                                (mapv (fn [animation] (update animation :ik-tracks #(mapv update-track %))) animations)))))))

(defn remove-ik [document ik-id]
  (let [document (validate-document document)]
    (item-index document :ik ik-id)
    (-> document
        (update :ik #(filterv (fn [constraint] (not= ik-id (:id constraint))) %))
        (update :animations #(mapv (fn [animation] (update animation :ik-tracks (fn [tracks] (filterv (fn [track] (not= ik-id (:ik track))) tracks)))) %)))))

(defn sample-pose
  "Return local bones at time, clamped to duration. nil animation-id returns setup pose."
  [document animation-id time]
  (validate-document document)
  (require-value! (finite-number? time) "Sample time must be finite." {})
  (if (nil? animation-id)
    (:bones document)
    (let [animation (get-in document [:animations (item-index document :animations animation-id)])
          time (max 0.0 (min (double time) (double (:duration animation))))
          tracks (group-by :bone (:tracks animation))]
      (mapv (fn [bone]
              (reduce (fn [bone {:keys [property keys]}]
                        (let [field (keyword property)]
                          (assoc bone field (sample-track-impl keys time (get bone field)))))
                      bone
                      (get tracks (:id bone))))
            (:bones document)))))

(defn- multiply-transform [[a b c d tx ty] [e f g h ux uy]]
  [(+ (* a e) (* c f)) (+ (* b e) (* d f))
   (+ (* a g) (* c h)) (+ (* b g) (* d h))
   (+ (* a ux) (* c uy) tx) (+ (* b ux) (* d uy) ty)])

(defn local-transform
  "Affine [a b c d tx ty], where world-x=a*x+c*y+tx and world-y=b*x+d*y+ty."
  [{:keys [x y rotation scale-x scale-y] :as local}]
  (validate-numbers! local [:x :y :rotation :scale-x :scale-y] {})
  (let [angle (Math/toRadians (double rotation))
        cosine (Math/cos angle)
        sine (Math/sin angle)]
    [(* cosine scale-x) (* sine scale-x) (* (- sine) scale-y) (* cosine scale-y) x y]))

(defn world-transforms
  "Return bone-id to affine matrix. Accepts any bone order; rejects cycles and
  missing parents before evaluating. Reflection and nonuniform scale are supported."
  [bones]
  (validate-bones! bones)
  (loop [pending bones
         result {}]
    (if (zero? (count pending))
      result
      (let [ready (filterv #(or (nil? (:parent %)) (contains? result (:parent %))) pending)
            ready-ids (into #{} (map :id) ready)]
        (recur (filterv #(not (contains? ready-ids (:id %))) pending)
               (reduce (fn [result bone]
                         (assoc result (:id bone)
                                (multiply-transform (get result (:parent bone) [1.0 0.0 0.0 1.0 0.0 0.0])
                                                    (local-transform bone))))
                       result
                       ready))))))

(defn transform-point [[a b c d tx ty] [x y]]
  [(+ (* a x) (* c y) tx) (+ (* b x) (* d y) ty)])


(defn- decode-color [value]
  (require-value! (and (string? value) (boolean (re-matches #"(?i)[0-9a-f]{6}([0-9a-f]{2})?" value))) "Spine color must be hexadecimal RGB or RGBA." {:color value})
  (let [value (if (= 6 (count value)) (str value "ff") value)]
    (mapv #(/ (Integer/parseInt (subs value % (+ % 2)) 16) 255.0) [0 2 4 6])))

(defn- encode-color [value]
  (apply str (mapv #(format "%02x" (Math/round (* 255.0 (double %)))) value)))

(defn- import-bone [bone]
  (cond-> {:id (get bone "name") :parent (get bone "parent")
           :x (get bone "x" 0.0) :y (get bone "y" 0.0) :rotation (get bone "rotation" 0.0)
           :scale-x (get bone "scaleX" 1.0) :scale-y (get bone "scaleY" 1.0) :length (get bone "length" 0.0)}
    (get bone "color") (assoc :spine-color (get bone "color"))))

(defn- import-curve [frame channel convert]
  (let [curve (get frame "curve")]
    (cond
      (= "stepped" curve) {:interpolation "stepped"}
      (vector? curve) (do
                        (require-value! (>= (count curve) (+ channel 4)) "A Spine Bezier curve needs four controls per channel." {})
                        {:interpolation "bezier"
                         :curve [(nth curve channel) (convert (nth curve (+ channel 1)))
                                 (nth curve (+ channel 2)) (convert (nth curve (+ channel 3)))]})
      (or (nil? curve) (= "linear" curve)) {:interpolation "linear"}
      :else (throw (ex-info "Unsupported Spine curve representation." {:curve curve})))))

(defn- import-timeline [bone timeline frames]
  (let [channels (get {"rotate" [["rotation" "value" 0.0]]
                      "translate" [["x" "x" 0.0] ["y" "y" 0.0]]
                      "translatex" [["x" "value" 0.0]] "translatey" [["y" "value" 0.0]]
                      "scale" [["scale-x" "x" 1.0] ["scale-y" "y" 1.0]]
                      "scalex" [["scale-x" "value" 1.0]] "scaley" [["scale-y" "value" 1.0]]} timeline)]
    (mapv (fn [channel [property field fallback]]
            (let [setup (get bone (keyword property))
                  convert (if (string/starts-with? property "scale-") #(* setup %) #(+ setup %))]
              {:bone (:id bone) :property property
               :keys (mapv (fn [frame]
                             (let [relative (get frame field fallback)]
                               (require-value! (finite-number? relative) "Spine key value must be finite." {:bone (:id bone)})
                               (merge {:time (get frame "time" 0.0) :value (convert relative)}
                                      (import-curve frame (* channel 4) convert)))) frames)}))
          (range (count channels)) channels)))

(defn- import-weights [vertices count-vertices bones]
  (loop [vertex 0 cursor 0 weights []]
    (if (= vertex count-vertices)
      (do (require-value! (= cursor (count vertices)) "Weighted mesh has trailing vertex data." {}) weights)
      (let [count-influences (get vertices cursor)
            _ (require-value! (and (integer? count-influences) (pos? count-influences)
                                   (<= (+ cursor 1 (* 4 count-influences)) (count vertices)))
                              "Weighted mesh contains invalid influence data." {})
            influences (mapv (fn [index]
                               (let [offset (+ cursor 1 (* index 4))
                                     bone-index (nth vertices offset)
                                     bone (when (integer? bone-index) (get bones bone-index))]
                                 (require-value! bone "Mesh weight references an unknown bone index." {:index bone-index})
                                 {:bone (:id bone) :x (nth vertices (+ offset 1)) :y (nth vertices (+ offset 2)) :weight (nth vertices (+ offset 3))}))
                             (range count-influences))]
        (recur (inc vertex) (long (+ cursor 1 (* 4 count-influences))) (conj weights influences))))))

(defn- import-attachment [name region bones worlds slot-bone]
  (let [type (get region "type" "region")
        path (get region "path" (get region "name" name))
        path (str (when-not (string/starts-with? path "/") "/") path)
        path (if (string/ends-with? (string/lower-case path) ".png") path (str path ".png"))
        attachment {:name name :type type :path path
                    :x (get region "x" 0.0) :y (get region "y" 0.0) :rotation (get region "rotation" 0.0)
                    :scale-x (get region "scaleX" 1.0) :scale-y (get region "scaleY" 1.0)
                    :width (get region "width" 100.0) :height (get region "height" 100.0)
                    :color (decode-color (get region "color" "ffffffff"))}]
    (if (not= "mesh" type)
      attachment
      (let [uvs (get region "uvs")
            raw-vertices (get region "vertices")
            _ (require-value! (and (vector? uvs) (vector? raw-vertices)) "Mesh UVs and vertices must be arrays." {:attachment name})
            weights (when (not= (count uvs) (count raw-vertices)) (import-weights raw-vertices (quot (count uvs) 2) bones))
            vertices (if (nil? weights)
                       raw-vertices
                       (let [[a b c d tx ty] (get worlds slot-bone)
                             determinant (- (* a d) (* b c))]
                         (require-value! (not (zero? (double determinant))) "Cannot edit a weighted mesh under a zero-scale slot bone." {:bone slot-bone})
                         (into []
                               (mapcat (fn [influences]
                                         (let [[wx wy] (reduce (fn [[wx wy] {:keys [bone x y weight]}]
                                                                 (let [[px py] (transform-point (get worlds bone) [x y])]
                                                                   [(+ wx (* px weight)) (+ wy (* py weight))])) [0.0 0.0] influences)
                                               x (- wx tx) y (- wy ty)]
                                           [(/ (- (* d x) (* c y)) determinant) (/ (- (* a y) (* b x)) determinant)]))) weights)))]
        (assoc attachment :vertices vertices :uvs uvs :triangles (get region "triangles" []) :weights weights)))))

(defn- import-deform [slot skin attachment frames]
  (let [size (deform-size (:attachment slot))]
    {:slot (:id slot) :skin skin :attachment attachment
     :keys (mapv (fn [frame]
                   (let [offset (get frame "offset" 0)
                         sparse (get frame "vertices" [])]
                     (require-value! (and (integer? offset) (<= 0 offset) (vector? sparse) (<= (+ offset (count sparse)) size))
                                     "Mesh deform offsets exceed the attachment's vertex count." {:slot (:id slot)})
                     (merge {:time (get frame "time" 0.0)
                             :vertices (reduce-kv (fn [result index value] (assoc result (+ offset index) value))
                                                  (vec (repeat size 0.0)) sparse)}
                            (import-curve frame 0 identity)))) frames)}))

(defn- timeline-duration [source]
  (cond
    (map? source) (reduce max (double (get source "time" 0.0)) (mapv timeline-duration (coll/vals (dissoc source "time"))))
    (vector? source) (reduce max 0.0 (mapv timeline-duration source))
    :else 0.0))

(defn- spine-deform-tracks [animation]
  (into []
        (mapcat (fn [[skin slots]]
                  (into []
                        (mapcat (fn [[slot attachments]]
                                  (into [] (keep (fn [[attachment timelines]]
                                                   (when-let [frames (get timelines "deform")]
                                                     {:skin skin :slot slot :attachment attachment :frames frames}))) attachments)))
                        slots)))
        (get animation "attachments" {})))

(defn- warn-preserved! [warnings message]
  (run! #(conj! warnings %) [message]))

(defn import-spine-json
  "Import Spine 4.2 JSON, retaining the complete source for lossless overlay
  export. Ordinary transform, region/mesh, weights, deform and IK data become
  editable. Other skins, attachments and advanced features remain in source;
  :warnings explicitly identifies content not represented in the preview."
  [text]
  (let [source (json/read-str text)
        version (get-in source ["skeleton" "spine"] "")
        _ (require-value! (and (string? version) (boolean (re-matches #"4\.2(?:\..*)?" version))) "Only Spine 4.2 JSON is supported." {:version version})
        bones (mapv import-bone (get source "bones" []))
        _ (validate-bones! bones)
        bone-map (into {} (map (juxt :id identity)) bones)
        worlds (world-transforms bones)
        skins (get source "skins" [])
        _ (require-value! (vector? skins) "Spine 4.2 skins must be an array." {})
        skin (or (coll/first-where #(= "default" (get % "name")) skins) (first skins) {"name" "default" "attachments" {}})
        skin-name (get skin "name")
        attachments (get skin "attachments" {})
        warnings (transient [])
        _ (when (or (> (count skins) 1) (not= "default" skin-name))
            (conj! warnings "Only the selected setup skin is editable; all other skins remain intact in the exported JSON."))
        _ (run! (fn [section] (when (pos? (count (get source section)))
                               (conj! warnings (str section " are preserved in JSON but are not evaluated by this editor."))))
                ["transform" "path" "physics" "events"])
        _ (run! (fn [bone]
                  (when (or (not= "normal" (get bone "inherit" (get bone "transform" "normal")))
                            (not (zero? (double (get bone "shearX" 0)))) (not (zero? (double (get bone "shearY" 0)))) (get bone "skin"))
                    (conj! warnings (str "Bone " (get bone "name") " has advanced inheritance/shear/skin data; it is preserved but previewed as an ordinary bone."))))
                (get source "bones"))
        slots (mapv (fn [slot]
                      (let [id (get slot "name")
                            name (get slot "attachment")
                            regions (get attachments id {})
                            region (get regions name)
                            supported (contains? #{"region" "mesh"} (get region "type" "region"))]
                        (when (> (count regions) 1) (conj! warnings (str "Slot " id " has additional attachments preserved in JSON; only its setup attachment is editable.")))
                        (when (or (get slot "dark") (not= "normal" (get slot "blend" "normal")))
                          (conj! warnings (str "Slot " id " uses tint/blending preserved in JSON but not previewed.")))
                        (when (and region (not supported)) (conj! warnings (str "Attachment " name " uses " (get region "type") "; it remains in JSON but is not editable.")))
                        (when (get region "sequence")
                          (conj! warnings (str "Attachment " name " contains an image sequence preserved in JSON; sequence frames are not previewed.")))
                        (when (and name (nil? region))
                          (conj! warnings (str "Slot " id " has no setup attachment in the selected skin; its original assignment remains in JSON.")))
                        (cond-> {:id id :bone (get slot "bone") :spine-skin skin-name}
                          (get slot "color") (assoc :color (decode-color (get slot "color")))
                          (and name (or (nil? region) (not supported))) (assoc :unsupported-attachment true)
                          (and region supported) (assoc :attachment (import-attachment name region bones worlds (get slot "bone"))))))
                    (get source "slots" []))
        slot-map (into {} (map (juxt :id identity)) slots)
        ik (mapv (fn [constraint]
                   (when (or (get constraint "compress") (get constraint "stretch") (get constraint "uniform") (pos? (double (get constraint "softness" 0))))
                     (conj! warnings (str "IK " (get constraint "name") " has softness/stretch options preserved but not previewed.")))
                   {:id (get constraint "name") :bones (get constraint "bones") :target (get constraint "target")
                    :mix (get constraint "mix" 1.0) :bend-positive (get constraint "bendPositive" true)}) (sort-by #(get % "order" 0) (get source "ik" [])))
        animations (mapv
                     (fn [[id animation]]
                       (let [tracks (into []
                                          (mapcat (fn [[bone-id timelines]]
                                                    (let [bone (get bone-map bone-id)]
                                                      (require-value! bone "Animation references a missing bone." {:bone bone-id})
                                                      (into [] (mapcat (fn [[timeline frames]]
                                                                        (if (contains? #{"rotate" "translate" "translatex" "translatey" "scale" "scalex" "scaley"} timeline)
                                                                          (import-timeline bone timeline frames)
                                                                          (warn-preserved! warnings (str "Animation " id "/" bone-id "/" timeline " is preserved but not editable."))))) timelines))))
                                          (get animation "bones" {}))
                             deforms (into []
                                           (keep (fn [{:keys [skin slot attachment frames]}]
                                                   (let [target (get slot-map slot)]
                                                     (if (and (= skin skin-name) (= attachment (get-in target [:attachment :name])) (= "mesh" (get-in target [:attachment :type])))
                                                       (import-deform target skin attachment frames)
                                                       (warn-preserved! warnings (str "Deform " id "/" slot "/" attachment " is retained for an inactive attachment."))))))
                                           (spine-deform-tracks animation))
                             ik-tracks (mapv (fn [[ik-id frames]]
                                               (when (coll/any? #(or (contains? % "softness") (contains? % "compress") (contains? % "stretch")) frames)
                                                 (conj! warnings (str "IK animation " id "/" ik-id " has softness/stretch options preserved but not previewed.")))
                                               {:ik ik-id :keys (mapv (fn [frame]
                                                                       (merge {:time (get frame "time" 0.0) :mix (get frame "mix" 1.0) :bend-positive (get frame "bendPositive" true)
                                                                               :spine-frame-source (json/write-str frame)}
                                                                              (import-curve frame 0 identity))) frames)})
                                             (get animation "ik" {}))]
                         (run! (fn [section] (when (pos? (count (get animation section)))
                                              (conj! warnings (str "Animation " id "/" section " is preserved in JSON but not previewed."))))
                               ["slots" "drawOrder" "draworder" "events" "path" "transform" "physics"])
                         (when (coll/any? (fn [timelines] (coll/any? #(not= "deform" %) (coll/keys timelines)))
                                          (into [] (comp (mapcat coll/vals) (mapcat coll/vals)) (coll/vals (get animation "attachments" {}))))
                           (conj! warnings (str "Attachment sequence timelines in " id " are preserved but not previewed.")))
                         {:id id :duration (timeline-duration animation) :tracks tracks :deforms deforms :ik-tracks ik-tracks}))
                     (get source "animations" {}))]
    (validate-document {:schema "defold.skeleton" :version 1 :name "Imported skeleton"
                        :spine-source text :warnings (into [] (distinct) (persistent! warnings))
                        :bones bones :slots slots :ik ik :animations animations})))

(defn sample-ik
  "Sample imported animated IK mix/bend into the document's constraints."
  [document animation-id time]
  (validate-document document)
  (require-value! (finite-number? time) "Sample time must be finite." {})
  (let [animation (when animation-id (get-in document [:animations (item-index document :animations animation-id)]))
        time (if animation (max 0.0 (min (double time) (double (:duration animation)))) 0.0)
        tracks (into {} (map (juxt :ik identity)) (:ik-tracks animation))]
    (mapv (fn [constraint]
            (if-let [keys (:keys (get tracks (:id constraint)))]
              (let [key (reduce (fn [previous key] (if (> (double (:time key)) time) (reduced previous) key)) nil keys)]
                (cond-> (assoc constraint :mix (sample-track-impl (mapv #(assoc % :value (:mix %)) keys) time (:mix constraint)))
                  key (assoc :bend-positive (:bend-positive key))))
              constraint)) (:ik document))))

(defn- export-curve [key convert]
  (case (:interpolation key)
    "stepped" {"curve" "stepped"}
    "bezier" (let [[t1 v1 t2 v2] (:curve key)] {"curve" [t1 (convert v1) t2 (convert v2)]})
    {}))

(defn- overlay-spine-frame [original frame]
  (let [old-curve (get original "curve")
        new-curve (get frame "curve")]
    (cond-> (merge (dissoc original "curve") frame)
      (and (vector? old-curve) (> (count old-curve) 4) (vector? new-curve))
      (assoc "curve" (into new-curve (subvec old-curve 4))))))

(defn- export-timeline [bone {:keys [property keys]} duration]
  (let [setup (get bone (keyword property))
        scale (string/starts-with? property "scale-")
        _ (require-value! (or (not scale) (not (zero? (double setup))) (coll/every? #(zero? (double (:value %))) keys))
                          "Cannot export animated nonzero scale from a zero setup scale to Spine." {:bone (:id bone) :property property})
        convert (if scale (if (zero? (double setup)) (constantly 1.0) #(/ % setup)) #(- % setup))
        keys (if (and (pos? (count keys)) (< (double (:time (peek keys))) (double duration)))
               (conj (update keys (dec (count keys)) assoc :interpolation "stepped") (assoc (peek keys) :time duration :interpolation "linear"))
               keys)]
    (mapv (fn [{:keys [time value] :as key}] (merge {"time" time "value" (convert value)} (export-curve key convert))) keys)))

(defn- export-attachment [attachment original bone-indices]
  (let [{:keys [path x y rotation scale-x scale-y width height color type vertices weights uvs triangles]} attachment
        base (merge original {"type" (or type "region") "path" (subs path 1 (- (count path) 4))
                              "x" x "y" y "rotation" rotation "scaleX" scale-x "scaleY" scale-y
                              "width" width "height" height "color" (encode-color color)})]
    (if (= "mesh" type)
      (assoc base "uvs" uvs "triangles" triangles
             "vertices" (if weights
                          (into [] (mapcat (fn [influences]
                                             (into [(count influences)]
                                                   (mapcat (fn [{:keys [bone x y weight]}] [(get bone-indices bone) x y weight])) influences))) weights)
                          vertices))
      (dissoc base "uvs" "triangles" "vertices" "hull" "edges" "parent" "skin" "deform"))))

(defn- reindex-spine-attachment [attachment old-bones bone-indices]
  (let [vertices (get attachment "vertices")
        vertex-count (or (get attachment "vertexCount") (quot (count (get attachment "uvs")) 2))]
    (if-not (and (vector? vertices) (pos? vertex-count) (not= (count vertices) (* 2 vertex-count)))
      attachment
      (let [weights (import-weights vertices vertex-count old-bones)
            encode-influence (fn [{:keys [bone x y weight]}]
                               (let [index (get bone-indices bone)]
                                 (require-value! index "An inactive attachment still references a removed bone." {:bone bone})
                                 [index x y weight]))]
        (assoc attachment "vertices"
               (into [] (mapcat (fn [influences] (into [(count influences)] (mapcat encode-influence) influences))) weights))))))

(defn- reindex-spine-skins [skins old-bones bone-indices slot-ids]
  (let [old-bones (mapv import-bone old-bones)
        rewrite-attachments #(coll/map-vals (fn [attachment] (reindex-spine-attachment attachment old-bones bone-indices)) %)]
    (mapv (fn [skin]
            (update skin "attachments" #(coll/map-vals rewrite-attachments (select-keys % slot-ids)))) skins)))

(defn export-spine-json
  "Export Spine 4.2 JSON by overlaying editable data onto the original source.
  Unknown skins, attachments, curves and constraint/timeline fields survive.
  Source image paths are project relative without the PNG extension."
  [document]
  (validate-document document)
  (let [source (if-let [text (:spine-source document)] (json/read-str text) {})
        bones (:bones document)
        bone-map (into {} (map (juxt :id identity)) bones)
        old-bones (into {} (map (juxt #(get % "name") identity)) (get source "bones" []))
        ordered-bones (loop [pending bones result [] seen #{}]
                        (if (zero? (count pending)) result
                            (let [ready (coll/first-where #(or (nil? (:parent %)) (contains? seen (:parent %))) pending)]
                              (recur (filterv #(not= (:id ready) (:id %)) pending) (conj result ready) (conj seen (:id ready))))))
        bone-indices (into {} (map-indexed (fn [index bone] [(:id bone) index])) ordered-bones)
        old-slots (into {} (map (juxt #(get % "name") identity)) (get source "slots" []))
        current-slot-ids (into #{} (map :id) (:slots document))
        animations (into {}
                         (map (fn [{:keys [id duration tracks deforms ik-tracks]}]
                                (let [original (get-in source ["animations" id] {})
                                      bone-tracks (into {} (comp (filter (fn [[bone-id]] (contains? bone-map bone-id)))
                                                                (map (fn [[bone-id timelines]]
                                                                       [bone-id (apply dissoc timelines ["rotate" "translate" "translatex" "translatey" "scale" "scalex" "scaley"])])))
                                                        (get original "bones" {}))
                                      tracks (filterv #(pos? (count (:keys %))) tracks)
                                      tracks (if (and (zero? (count tracks)) (pos? (count bones)))
                                               [{:bone (:id (first bones)) :property "rotation"
                                                 :keys [{:time duration :value (:rotation (first bones)) :interpolation "linear"}]}] tracks)
                                      _ (require-value! (or (pos? (count bones)) (zero? duration)) "An empty skeleton cannot export a timed animation." {:animation id})
                                      bone-tracks (reduce (fn [result track]
                                                            (assoc-in result [(:bone track) (get {"x" "translatex" "y" "translatey" "rotation" "rotate" "scale-x" "scalex" "scale-y" "scaley"} (:property track))]
                                                                      (export-timeline (get bone-map (:bone track)) track duration))) bone-tracks tracks)
                                      raw-deforms (into {} (map (fn [[skin slots]]
                                                                 [skin (into {} (comp (filter (fn [[slot]] (contains? current-slot-ids slot)))
                                                                                     (map (fn [[slot attachments]]
                                                                                            [slot (into {} (map (fn [[name timelines]]
                                                                                                                 [name (if (coll/any? #(and (= slot (:id %))
                                                                                                                                             (= skin (get % :spine-skin "default"))
                                                                                                                                             (= name (get-in % [:attachment :name] (:id %)))) (:slots document))
                                                                                                                         (dissoc timelines "deform") timelines)])) attachments)]))) slots)]))
                                                        (get original "attachments" {}))
                                      deform-tracks (reduce (fn [result {:keys [skin slot attachment keys]}]
                                                              (assoc-in result [(or skin "default") slot attachment "deform"]
                                                                        (mapv (fn [{:keys [time vertices] :as key}] (merge {"time" time "vertices" vertices} (export-curve key identity))) keys)))
                                                            raw-deforms deforms)
                                      ik-tracks (reduce (fn [result {:keys [ik keys]}]
                                                          (assoc result ik (mapv (fn [{:keys [time mix bend-positive] :as key}]
                                                                                  (overlay-spine-frame (key-spine-frame key (get-in original ["ik" ik]))
                                                                                                       (merge {"time" time "mix" mix "bendPositive" bend-positive} (export-curve key identity)))) keys)))
                                                        {} ik-tracks)]
                                  [id (cond-> (assoc (dissoc original "attachments" "ik") "bones" bone-tracks)
                                        (get original "slots") (assoc "slots" (select-keys (get original "slots") current-slot-ids))
                                        (pos? (count deform-tracks)) (assoc "attachments" deform-tracks)
                                        (pos? (count ik-tracks)) (assoc "ik" ik-tracks))]))) (:animations document))
        skins (reduce (fn [skins {:keys [id attachment spine-skin]}]
                        (if (nil? attachment)
                          skins
                          (let [skin-name (or spine-skin "default")
                                index (or (reduce-kv (fn [_ index skin] (when (= skin-name (get skin "name")) (reduced index))) nil skins) (count skins))
                                skins (if (= index (count skins)) (conj skins {"name" skin-name "attachments" {}}) skins)
                                path [index "attachments" id (get attachment :name id)]]
                            (assoc-in skins path (export-attachment attachment (get-in skins path {}) bone-indices)))))
                      (reindex-spine-skins (get source "skins" []) (get source "bones" []) bone-indices (into #{} (map :id) (:slots document))) (:slots document))
        slots (mapv (fn [{:keys [id bone attachment color unsupported-attachment]}]
                      (cond-> (assoc (if unsupported-attachment (get old-slots id {}) (dissoc (get old-slots id {}) "attachment")) "name" id "bone" bone)
                        attachment (assoc "attachment" (get attachment :name id))
                        color (assoc "color" (encode-color color)))) (:slots document))
        old-ik (into {} (map (juxt #(get % "name") identity)) (get source "ik" []))
        skins (mapv (fn [skin] (cond-> skin
                                (get skin "ik") (update "ik" #(filterv (into #{} (map :id) (:ik document)) %)))) skins)
        maximum-ik-order (reduce max -1 (mapv #(get % "order" 0) (get source "ik" [])))
        output (assoc source
                      "skeleton" (merge {"spine" "4.2.0"} (get source "skeleton" {}))
                      "bones" (mapv (fn [{:keys [id parent x y rotation scale-x scale-y length spine-color]}]
                                      (cond-> (assoc (dissoc (get old-bones id {}) "parent") "name" id "x" x "y" y "rotation" rotation "scaleX" scale-x "scaleY" scale-y "length" length)
                                        parent (assoc "parent" parent) spine-color (assoc "color" spine-color))) ordered-bones)
                      "slots" slots "skins" skins "animations" animations
                      "ik" (mapv (fn [index {:keys [id bones target mix bend-positive]}]
                                   (assoc (get old-ik id {}) "name" id "bones" bones "target" target "mix" mix "bendPositive" bend-positive
                                          "order" (get (get old-ik id) "order" (+ maximum-ik-order 1 index))))
                                 (range (count (:ik document))) (:ik document)))]
    (str (json/write-str output :escape-slash false :indent true) "\n")))
