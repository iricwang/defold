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
            [editor.skeletal-animation-constraints :as constraints]
            [editor.skeletal-animation-geometry :as geometry]
            [editor.skeletal-animation-interpolation :as interpolation]
            [editor.skeletal-animation-mesh :as mesh]
            [editor.skeletal-animation-physics :as physics]
            [util.coll :as coll])
  (:import [java.util Map]
           [org.codehaus.jackson.map ObjectMapper]))

(set! *warn-on-reflection* true)

(def supported-properties ["x" "y" "rotation" "scale-x" "scale-y" "shear-x" "shear-y"])
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
              (constraints/normalize-bone bone)
              (validate-numbers! (merge {:shear-x 0.0 :shear-y 0.0} bone) [:shear-x :shear-y] {:bone id})
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

(defn- validate-path-attachment! [attachment bone-ids context]
  (let [{:keys [vertices closed constant-speed lengths]} attachment
        curve-count (- (quot (count vertices) 6) (if closed 0 1))]
    (require-value! (and (vector? vertices) (zero? (mod (count vertices) 6)) (pos? curve-count)
                         (boolean? closed) (boolean? constant-speed)
                         (vector? lengths) (>= (count lengths) curve-count)
                         (coll/every? #(and (finite-number? %) (not (neg? %))) lengths)
                         (or constant-speed (apply < (into [-1.0] (take curve-count) lengths))))
                    "Path points, flags and cumulative curve lengths are invalid." context)
    (validate-mesh! (assoc attachment :uvs (vec (repeat (count vertices) 0.0)) :triangles []) bone-ids context)))

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
              (when-not (= "path" (:type attachment))
                (let [path (:path attachment)]
                  (require-value! (and (map? attachment) (string? path)
                                       (string/starts-with? path "/")
                                       (string/ends-with? (string/lower-case path) ".png")
                                       (not (string/includes? path "\\"))
                                       (not (coll/any? #{"." ".." ""} (subvec (string/split path #"/" -1) 1))))
                                  "An attachment needs an absolute project PNG path without traversal." {:slot id :path path}))
                (validate-numbers! attachment [:x :y :rotation :scale-x :scale-y :width :height] {:slot id})
                (require-value! (and (pos? (double (:width attachment))) (pos? (double (:height attachment))))
                                "Attachment dimensions must be positive." {:slot id}))
              (validate-color! (:color attachment) {:slot id})
              (require-value! (contains? #{"region" "mesh" "path"} (get attachment :type "region")) "Unsupported attachment type." {:slot id})
              (case (:type attachment)
                "mesh" (validate-mesh! attachment bone-ids {:slot id})
                "path" (validate-path-attachment! attachment bone-ids {:slot id})
                nil)))
          (:slots document))
    (validate-ids! (get document :ik []) {:section :ik})
    (run! (fn [{:keys [id bones target mix bend-positive order] :as constraint}]
            (constraints/normalize-ik constraint)
            (require-value! (not (neg? (get constraint :softness 0.0))) "IK softness must be nonnegative." {:ik id})
            (require-value! (and (integer? (or order 0)) (not (neg? (or order 0)))) "Constraint order must be a nonnegative integer." {:ik id})
            (require-value! (and (vector? bones) (<= 1 (count bones) 2)
                                 (= (count bones) (count (set bones)))
                                 (coll/every? bone-ids bones) (contains? bone-ids target)
                                 (not (contains? (set bones) target))
                                 (finite-number? mix) (<= 0.0 (double mix) 1.0)
                                 (boolean? bend-positive))
                            "IK requires one or two existing bones, a separate target and a finite mix." {:ik id})
            (require-value! (or (= 1 (count bones)) (coll/every? (fn [bone-id] (= "normal" (get (coll/first-where #(= bone-id (:id %)) (:bones document)) :inherit "normal"))) bones))
                            "Two-bone IK constrained bones must use normal inheritance." {:ik id})
            (require-value! (:parent (coll/first-where #(= (first bones) (:id %)) (:bones document)))
                            "IK cannot constrain a root bone. Add a parent bone above the chain first." {:ik id})
            (when (= 2 (count bones))
              (require-value! (= (first bones) (:parent (coll/first-where #(= (second bones) (:id %)) (:bones document))))
                              "Two-bone IK requires a direct parent-child chain." {:ik id})))
          (:ik document))
    (validate-ids! (get document :transform []) {:section :transform})
    (run! (fn [constraint]
            (constraints/normalize-transform constraint)
            (require-value! (and (integer? (get constraint :order 0)) (not (neg? (get constraint :order 0))))
                            "Constraint order must be a nonnegative integer." {:constraint (:id constraint)})
            (let [constrained (set (:bones constraint))
                  parents (into {} (map (juxt :id :parent)) (:bones document))]
              (require-value! (and (vector? (:bones constraint)) (pos? (count constrained))
                                   (= (count constrained) (count (:bones constraint)))
                                   (coll/every? bone-ids constrained) (contains? bone-ids (:target constraint)))
                              "Transform constraints require existing unique bones and a target." {:constraint (:id constraint)})
              (loop [ancestor (:target constraint)]
                (when ancestor
                  (require-value! (not (contains? constrained ancestor)) "Transform target cannot descend from a constrained bone." {:constraint (:id constraint)})
                  (recur (parents ancestor))))))
          (:transform document))
    (validate-ids! (get document :path []) {:section :path})
    (run! (fn [constraint]
            (constraints/normalize-path constraint)
            (require-value! (and (integer? (get constraint :order 0)) (not (neg? (get constraint :order 0))))
                            "Constraint order must be a nonnegative integer." {:path (:id constraint)})
            (let [constrained (set (:bones constraint))
                  target (coll/first-where #(= (:target constraint) (:id %)) (:slots document))
                  parents (into {} (map (juxt :id :parent)) (:bones document))]
              (require-value! (and (vector? (:bones constraint)) (pos? (count constrained))
                                   (= (count constrained) (count (:bones constraint))) (coll/every? bone-ids constrained) target)
                              "Path constraints need unique existing bones and a target slot." {:path (:id constraint)})
              (doseq [bone (into #{(:bone target)} (comp (mapcat identity) (map :bone)) (get-in target [:attachment :weights]))]
                (loop [ancestor bone]
                  (when ancestor
                    (require-value! (not (contains? constrained ancestor)) "Path attachment cannot depend on a constrained bone or descendant." {:path (:id constraint) :bone bone})
                    (recur (parents ancestor))))))) (:path document))
    (require-value! (and (finite-number? (get document :reference-scale 100.0)) (pos? (get document :reference-scale 100.0)))
                    "Reference scale must be finite and positive." {})
    (validate-ids! (get document :physics []) {:section :physics})
    (run! (fn [constraint]
            (physics/normalize-constraint constraint)
            (require-value! (and (contains? bone-ids (:bone constraint)) (integer? (get constraint :order 0)) (not (neg? (get constraint :order 0))))
                            "Physics constraints require an existing bone and nonnegative integer order." {:physics (:id constraint)})
            (doseq [property physics/animated-properties]
              (require-value! (boolean? (get constraint (keyword (str property "-global")) false))
                              "Physics global flags must be booleans." {:physics (:id constraint) :property property}))) (:physics document))
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
            (let [animation (coll/first-where #(= id (:id %)) (:animations document))
                  transform-ids (into #{} (map :id) (:transform document))]
              (require-value! (vector? (get animation :transform-tracks [])) "Transform tracks must be an array." {:animation id})
              (reduce (fn [seen {:keys [transform property keys]}]
                        (require-value! (and (contains? transform-ids transform)
                                             (coll/any? #{property} constraints/transform-properties)
                                             (not (contains? seen [transform property])))
                                        "Transform timeline requires a unique supported property and existing constraint." {:animation id :transform transform})
                        (validate-keys! keys duration {:animation id :transform transform :property property} #(finite-number? (:value %)))
                        (conj seen [transform property])) #{} (:transform-tracks animation))
              (let [path-ids (into #{} (map :id) (:path document))]
                (require-value! (vector? (get animation :path-tracks [])) "Path tracks must be an array." {:animation id})
                (reduce (fn [seen {:keys [path property keys]}]
                          (require-value! (and (contains? path-ids path) (coll/any? #{property} constraints/path-properties)
                                               (not (contains? seen [path property])))
                                          "Path timeline requires a unique supported property and existing constraint." {:animation id :path path})
                          (validate-keys! keys duration {:animation id :path path :property property} #(finite-number? (:value %)))
                          (conj seen [path property])) #{} (:path-tracks animation)))
              (let [physics-ids (conj (into #{} (map :id) (:physics document)) "")]
                (require-value! (vector? (get animation :physics-tracks [])) "Physics tracks must be an array." {:animation id})
                (reduce (fn [seen {:keys [physics property keys]}]
                          (require-value! (and (contains? physics-ids physics) (contains? (conj (set editor.skeletal-animation-physics/animated-properties) "reset") property)
                                               (not (contains? seen [physics property])))
                                          "Physics timelines need a unique supported property and existing constraint or global target." {:animation id :physics physics})
                          (validate-keys! keys duration {:animation id :physics physics :property property}
                                          #(and (finite-number? (:value %)) (case property "mass" (pos? (:value %)) "damping" (<= 0.0 (:value %) 1.0) "strength" (not (neg? (:value %))) "reset" (= "stepped" (:interpolation %)) true)))
                          (conj seen [physics property])) #{} (:physics-tracks animation)))
              (require-value! (vector? (get animation :deforms [])) "Deform tracks must be an array." {:animation id})
              (reduce (fn [seen {:keys [slot keys]}]
                        (let [attachment (:attachment (coll/first-where #(= slot (:id %)) (:slots document)))]
                          (require-value! (and (contains? #{"mesh" "path"} (:type attachment)) (not (contains? seen slot))) "Deform tracks need a unique mesh or path slot." {:animation id :slot slot})
                          (validate-keys! keys duration {:animation id :slot slot}
                                          #(and (vector? (:vertices %)) (= (deform-size attachment) (count (:vertices %)))
                                                (coll/every? finite-number? (:vertices %))))
                          (conj seen slot))) #{} (:deforms animation))
              (require-value! (vector? (get animation :ik-tracks [])) "IK tracks must be an array." {:animation id})
              (let [ik-ids (into #{} (map :id) (:ik document))]
                (reduce (fn [seen {:keys [ik keys]}]
                          (require-value! (and (contains? ik-ids ik) (not (contains? seen ik))) "IK timeline references a missing or duplicate constraint." {:animation id :ik ik})
                          (validate-keys! keys duration {:animation id :ik ik}
                                          #(and (finite-number? (:mix %)) (<= 0.0 (double (:mix %)) 1.0) (boolean? (:bend-positive %))
                                                (finite-number? (get % :softness 0.0)) (not (neg? (get % :softness 0.0)))
                                                (boolean? (get % :compress false)) (boolean? (get % :stretch false))))
                          (validate-keys! (mapv (fn [key] (assoc key :value (get key :softness 0.0)
                                                                              :interpolation (get key :softness-interpolation (:interpolation key))
                                                                              :curve (or (:softness-curve key) (:curve key)))) keys)
                                          duration {:animation id :ik ik :property "softness"} #(finite-number? (:value %)))
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

(declare upgrade-document)

(defn read-json [text]
  (validate-document (upgrade-document (json/read-str text :key-fn keyword))))

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
            (merge {:parent nil :x 0.0 :y 0.0 :rotation 0.0 :scale-x 1.0 :scale-y 1.0 :shear-x 0.0 :shear-y 0.0 :length 80.0} bone))))

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
          (update :transform #(mapv (fn [constraint] (-> constraint (update :bones (fn [bones] (mapv rename-reference bones))) (update :target rename-reference))) %))
          (update :path #(mapv (fn [constraint] (update constraint :bones (fn [bones] (mapv rename-reference bones)))) %))
          (update :physics #(mapv (fn [constraint] (update constraint :bone rename-reference)) %))
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
        removed-physics (into #{} (comp (filter #(contains? deleted (:bone %))) (map :id)) (:physics document))
        removed-path (into #{} (comp (filter #(or (contains? removed-slots (:target %)) (coll/any? deleted (:bones %)))) (map :id)) (:path document))
        removed-transform (into #{} (comp (filter #(or (contains? deleted (:target %)) (coll/any? deleted (:bones %)))) (map :id)) (:transform document))
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
        (update :transform #(filterv (fn [constraint] (not (contains? removed-transform (:id constraint)))) %))
        (update :path #(filterv (fn [constraint] (not (contains? removed-path (:id constraint)))) %))
        (update :physics #(filterv (fn [constraint] (not (contains? removed-physics (:id constraint)))) %))
        (update :animations #(mapv (fn [animation]
                                     (-> animation
                                         (update :physics-tracks (fn [tracks] (filterv (fn [track] (not (contains? removed-physics (:physics track)))) tracks)))
                                         (update :path-tracks (fn [tracks] (filterv (fn [track] (not (contains? removed-path (:path track)))) tracks)))
                                         (update :transform-tracks (fn [tracks] (filterv (fn [track] (not (contains? removed-transform (:transform track)))) tracks)))
                                         (update :ik-tracks (fn [tracks] (filterv (fn [track] (not (contains? removed-ik (:ik track)))) tracks)))
                                         (update :deforms (fn [deforms] (filterv (fn [track] (not (contains? removed-slots (:slot track)))) deforms)))
                                         (update :tracks (fn [tracks] (filterv (fn [track] (not (contains? deleted (:bone track)))) tracks))))) %)))))

(defn- normalize-slot [slot]
  (cond-> slot
    (:attachment slot)
    (update :attachment #(merge {:x 0.0 :y 0.0 :rotation 0.0 :scale-x 1.0 :scale-y 1.0
                                :width 100.0 :height 100.0 :color [1.0 1.0 1.0 1.0]} %))))

(defn- remap-deform-weights [vertices before after slot-bone worlds]
  (if (= (:weights before) (:weights after))
    vertices
    (let [old-influences (or (:weights before) (vec (repeat (quot (count (:vertices before)) 2) [{:bone slot-bone :weight 1.0}])))
          new-influences (or (:weights after) (vec (repeat (count old-influences) [{:bone slot-bone :weight 1.0}])))
          to-local (fn [bone world-delta]
                     (or (geometry/inverse-transform-point (assoc (get worlds bone) 4 0.0 5 0.0) world-delta)
                         (throw (ex-info "Cannot migrate deformation through a bone with zero scale." {:bone bone}))))]
      (require-value! (= (count (:vertices before)) (count (:vertices after)))
                      "Changing mesh topology requires a vertex remapping edit." {})
      (loop [vertex 0 offset 0 result []]
        (if (= vertex (count old-influences))
          result
          (let [old (nth old-influences vertex)
                offsets (into {} (map-indexed (fn [index influence] [(:bone influence) (subvec vertices (+ offset (* index 2)) (+ offset (* index 2) 2))])) old)
                world-delta (reduce (fn [sum {:keys [bone weight]}]
                                      (let [delta (geometry/transform-point (assoc (get worlds bone) 4 0.0 5 0.0) (offsets bone))]
                                        (mapv #(+ %1 (* weight %2)) sum delta))) [0.0 0.0] old)
                values (into [] (mapcat (fn [{:keys [bone]}]
                                         (if (and (:weights before) (:weights after) (contains? offsets bone))
                                           (offsets bone) (to-local bone world-delta)))) (nth new-influences vertex))]
            (recur (inc vertex) (long (+ offset (* 2 (count old)))) (into result values))))))))

(defn add-slot [document slot]
  (let [slot (merge {:spine-skin (get document :active-skin "default")} slot)]
    (validate-document (update (validate-document document) :slots conj (normalize-slot slot)))))

(defn update-slot
  "Update a slot, merging attachment fields. Weight edits remap existing FFD
  keys by vertex/bone identity. Removing the mesh removes its deform tracks."
  [document slot-id changes]
  (let [document (validate-document document)
        index (item-index document :slots slot-id)
        original (get-in document [:slots index])
        original (cond-> original (:attachment original) (update :attachment #(merge {:name slot-id} %)))
        changes (if (map? (:attachment changes))
                  (update changes :attachment #(merge (:attachment original) %))
                  changes)
        updated (cond-> (normalize-slot (merge original changes))
                  (contains? changes :attachment) (dissoc :unsupported-attachment))
        updated (if (and (= "path" (get-in updated [:attachment :type]))
                         (not= (select-keys (:attachment original) [:vertices :weights :closed])
                               (select-keys (:attachment updated) [:vertices :weights :closed])))
                  (let [worlds (constraints/world-transforms (:bones document))]
                    (assoc-in updated [:attachment :lengths]
                              (constraints/path-lengths (:attachment updated) (get worlds (:bone updated)) worlds))) updated)
        update-track (fn [track]
                       (if (not= slot-id (:slot track))
                         track
                         (when (contains? #{"mesh" "path"} (get-in updated [:attachment :type]))
                           (-> track
                               (assoc :slot (get changes :id slot-id))
                               (update :keys (fn [keys]
                                               (mapv (fn [key] (update key :vertices remap-deform-weights
                                                                       (:attachment original) (:attachment updated) (:bone original)
                                                                       (constraints/world-transforms (:bones document)))) keys)))))))
        document (cond-> document
                   (and (:spine-source document) (contains? changes :id) (not= slot-id (:id changes)))
                   (update :spine-source rename-source-slot slot-id (:id changes)))]
    (validate-document
      (-> document
          (assoc-in [:slots index] updated)
          (update :path #(mapv (fn [constraint] (update constraint :target (fn [target] (if (= slot-id target) (get changes :id slot-id) target)))) %))
          (update :animations (fn [animations]
                                (mapv (fn [animation] (update animation :deforms #(into [] (keep update-track) %))) animations)))))))

(defn remove-slot [document slot-id]
  (let [document (validate-document document)
        deleted-paths (into #{} (comp (filter #(= slot-id (:target %))) (map :id)) (:path document))]
    (item-index document :slots slot-id)
    (require-source-removal-safe! document #{} #{slot-id})
    (-> document
        (update :slots #(filterv (fn [slot] (not= slot-id (:id slot))) %))
        (update :path #(filterv (fn [constraint] (not (contains? deleted-paths (:id constraint)))) %))
        (update :animations #(mapv (fn [animation] (update animation :path-tracks (fn [tracks] (filterv (fn [track] (not (contains? deleted-paths (:path track)))) tracks)))) %))
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

(declare export-spine-json)

(defn update-animation
  "Update animation metadata. Shortening past an existing key is rejected."
  [document animation-id changes]
  (let [document (validate-document document)
        renamed (cond-> document
                  (and (:spine-source document) (contains? changes :id) (not= animation-id (:id changes)))
                  (update :spine-source #(json/write-str (update (json/read-str %) "animations"
                                                               (fn [animations]
                                                                 (rename-map-key (dissoc animations (:id changes)) animation-id (:id changes)))))))
        index (item-index document :animations animation-id)
        updated (validate-document (update-in renamed [:animations index] merge changes))]
      (when (and (:spine-source updated) (< (get-in updated [:animations index :duration]) (get-in document [:animations index :duration])))
        (export-spine-json updated))
      updated))

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
                           (if (and (not= "bezier" (:interpolation current)) (not= "bezier" (:softness-interpolation current)))
                             current
                             (let [source-time (if (== new-time (:time current)) old-time (:time current))
                                   old-right (:time (coll/first-where #(> (double (:time %)) (double source-time)) keys))
                                   right (:time (get moved (inc i)))
                                   remap (fn [t]
                                           (if (and old-right right (> (double old-right) (double source-time)))
                                             (+ (:time current) (* (/ (- t source-time) (- old-right source-time)) (- right (:time current))))
                                             (max (:time current) (min duration (+ t (- (:time current) source-time))))))
                                   remap-curve (fn [[t1 v1 t2 v2]] [(remap t1) v1 (remap t2) v2])]
                               (cond-> current
                                 (= "bezier" (:interpolation current)) (update :curve remap-curve)
                                 (= "bezier" (:softness-interpolation current)) (update :softness-curve remap-curve)
                                 (:spine-frame-source current)
                                 (update :spine-frame-source
                                         #(let [frame (json/read-str %)]
                                            (json/write-str
                                              (cond-> frame
                                                (vector? (get frame "curve"))
                                                (update "curve" (fn [curve]
                                                                  (mapv (fn [index value] (if (even? index) (remap value) value))
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

(defn- insert-animation-key [keys key]
  (let [time (:time key)
        inserted (vec (sort-by :time (conj (filterv #(not (== time (:time %))) keys) key)))]
    (mapv (fn [index frame]
            (let [new-right (get inserted (inc index))
                  old-right (coll/first-where #(> (double (:time %)) (:time frame)) keys)]
              (if (and (not (== time (:time frame))) new-right old-right
                       (not= (double (:time new-right)) (double (:time old-right))))
                (let [ratio (/ (- (:time new-right) (:time frame)) (- (:time old-right) (:time frame)))
                      remap #(+ (:time frame) (* ratio (- % (:time frame))))
                      remap-curve (fn [[t1 v1 t2 v2]] [(remap t1) v1 (remap t2) v2])]
                  (cond-> frame
                    (= "bezier" (:interpolation frame)) (update :curve remap-curve)
                    (= "bezier" (:softness-interpolation frame)) (update :softness-curve remap-curve))) frame)))
          (range (count inserted)) inserted)))

(declare sample-ik)

(defn set-ik-keyframe-values
  "Insert/replace IK values without resetting untouched channels. The optional
  :softness entry in the interpolation map specifies its own curve."
  [document animation-id ik-id time values interpolation]
  (require-value! (and (finite-number? time) (not (neg? (double time)))) "Key time must be finite and nonnegative." {})
  (require-value! (coll/every? #{:mix :softness :bend-positive :compress :stretch} (coll/keys values)) "Unsupported animated IK property." {})
  (let [document (validate-document document)
        index (item-index document :animations animation-id)
        tracks (get-in document [:animations index :ik-tracks] [])
        track (or (coll/first-where #(= ik-id (:ik %)) tracks) {:ik ik-id :keys []})
        existing (coll/first-where #(== time (:time %)) (:keys track))
        sampled (coll/first-where #(= ik-id (:id %)) (sample-ik document animation-id time))
        _ (require-value! sampled "Unknown IK constraint." {:ik ik-id})
        key (merge (select-keys sampled [:mix :softness :bend-positive :compress :stretch]) existing values
                   {:time time :spine-frame-source (:spine-frame-source existing)}
                   (if (map? interpolation) {:interpolation (:type interpolation) :curve (:curve interpolation)} {:interpolation interpolation}))
        softness (when (map? interpolation) (:softness interpolation))
        key (cond-> key
              softness (assoc :softness-interpolation (:type softness) :softness-curve (:curve softness))
              (and (not softness) (not (contains? key :softness-interpolation))) (assoc :softness-interpolation "linear"))]
    (validate-document (-> document
                           (update-in [:animations index :duration] max time)
                           (assoc-in [:animations index :ik-tracks]
                                     (conj (filterv #(not= ik-id (:ik %)) tracks)
                                           (update track :keys insert-animation-key key)))))))

(defn set-ik-keyframe [document animation-id ik-id time mix bend-positive interpolation]
  (set-ik-keyframe-values document animation-id ik-id time {:mix mix :bend-positive bend-positive} interpolation))

(defn set-ik-softness-keyframe
  "Edit softness with its own interpolation, retaining mix and discrete flags."
  [document animation-id ik-id time softness interpolation]
  (let [animation (get-in document [:animations (item-index document :animations animation-id)])
        track (coll/first-where #(= ik-id (:ik %)) (:ik-tracks animation))
        key (coll/first-where #(== time (:time %)) (:keys track))
        interpolation (if (map? interpolation) interpolation {:type interpolation})]
    (set-ik-keyframe-values document animation-id ik-id time {:softness softness}
                            {:type (get key :interpolation "linear") :curve (:curve key) :softness interpolation})))

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
  "Record native Spine offsets (per influence for weighted meshes). Inserting
  a key trims the preceding Bezier to its new interval without retiming it."
  [document animation-id slot-id time vertices interpolation]
  (let [document (validate-document document)
        index (item-index document :animations animation-id)
        slot (get-in document [:slots (item-index document :slots slot-id)])
        deforms (get-in document [:animations index :deforms] [])
        track (or (coll/first-where #(= slot-id (:slot %)) deforms)
                  {:slot slot-id :attachment (get-in slot [:attachment :name] slot-id)
                   :skin (get slot :spine-skin "default") :keys []})
        _ (require-value! (and (finite-number? time) (not (neg? (double time)))) "Deform time must be finite and nonnegative." {})
        keys (:keys track)
        key (merge (dissoc (coll/first-where #(== time (:time %)) keys) :curve)
                   (if (map? interpolation)
                     {:time time :vertices vertices :interpolation (:type interpolation) :curve (:curve interpolation)}
                     {:time time :vertices vertices :interpolation interpolation}))
        trimmed (mapv
                  (fn [index original]
                    (let [right (get keys (inc index))]
                      (if-not (and right (= "bezier" (:interpolation original))
                                   (< (:time original) time (:time right)))
                        original
                        (let [{:keys [value left]} (editor.skeletal-animation-interpolation/split-bezier
                                                    (:time original) 0.0 (:time right) 1.0 (:curve original) time)
                              [x0 y0 x1 y1] left]
                          (if (> (abs (double value)) 1.0e-12)
                            (assoc original :curve [x0 (/ y0 value) x1 (/ y1 value)])
                            (if (or (= (:vertices original) (:vertices right))
                                    (and (< (abs (double y0)) 1.0e-12) (< (abs (double y1)) 1.0e-12)))
                              (-> original (dissoc :curve) (assoc :interpolation "linear"))
                              (throw (ex-info "Cannot split an FFD curve at a return to its starting value. Choose a nearby time to preserve the deformation."
                                              {:time time :slot slot-id}))))))))
                  (range (count keys)) keys)
        inserted (vec (sort-by :time (conj (filterv #(not (== time (:time %))) trimmed) key)))]
    (validate-document
      (-> document
          (update-in [:animations index :duration] max time)
          (assoc-in [:animations index :deforms]
                    (conj (filterv #(not= slot-id (:slot %)) deforms)
                          (assoc track :keys inserted)))))))

(defn- sample-deforms-impl [document animation-id time]
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
                                                 (interpolation/sample-bezier
                                                   (interpolation/bezier-points (:time left) 0.0 (:time right) 1.0 (:curve left)) time)
                                                 (/ (- time (double (:time left))) (- (double (:time right)) (double (:time left)))))]
                                     (mapv (fn [a b] (+ a (* alpha (- b a)))) (:vertices left) (:vertices right))))])))
            (:deforms animation)))))

(defn sample-deforms [document animation-id time]
  (validate-document document)
  (require-value! (finite-number? time) "Sample time must be finite." {})
  (sample-deforms-impl document animation-id time))

(defn add-ik [document constraint]
  (validate-document (update (validate-document document) :ik (fnil conj [])
                            (merge {:mix 1.0 :bend-positive true :softness 0.0 :compress false :stretch false :uniform false :order (inc (reduce max -1 (mapv #(get % :order 0) (into (into (into (get document :ik []) (:transform document)) (:path document)) (:physics document)))))} constraint))))

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

(defn- sample-pose-impl
  "Return local bones at time, clamped to duration. nil animation-id returns setup pose."
  [document animation-id time]
  (if (nil? animation-id)
    (:bones document)
    (let [animation (get-in document [:animations (item-index document :animations animation-id)])
          time (max 0.0 (min (double time) (double (:duration animation))))
          tracks (group-by :bone (:tracks animation))]
      (mapv (fn [bone]
              (reduce (fn [bone {:keys [property keys]}]
                        (let [field (keyword property)]
                          (assoc bone field (sample-track-impl keys time (get bone field 0.0)))))
                      bone
                      (get tracks (:id bone))))
            (:bones document)))))

(defn sample-pose [document animation-id time]
  (validate-document document)
  (require-value! (finite-number? time) "Sample time must be finite." {})
  (sample-pose-impl document animation-id time))

(defn local-transform
  "Affine [a b c d tx ty], including Spine's independent X/Y shear axes."
  [local]
  (validate-numbers! local [:x :y :rotation :scale-x :scale-y] {})
  (geometry/local-transform local))

(defn world-transforms
  "Evaluate all five Spine inheritance modes, rejecting malformed hierarchies."
  [bones]
  (validate-bones! bones)
  (constraints/world-transforms bones))

(defn transform-point [[a b c d tx ty] [x y]]
  [(+ (* a x) (* c y) tx) (+ (* b x) (* d y) ty)])


(defn- decode-color [value]
  (require-value! (and (string? value) (boolean (re-matches #"(?i)[0-9a-f]{6}([0-9a-f]{2})?" value))) "Spine color must be hexadecimal RGB or RGBA." {:color value})
  (let [value (if (= 6 (count value)) (str value "ff") value)]
    (mapv #(/ (Integer/parseInt (subs value % (+ % 2)) 16) 255.0) [0 2 4 6])))

(defn- encode-color [value]
  (apply str (mapv #(format "%02x" (Math/round (* 255.0 (double %)))) value)))

(def transform-source-fields
  {:order "order" :local "local" :relative "relative" :rotation "rotation" :x "x" :y "y"
   :scale-x "scaleX" :scale-y "scaleY" :shear-y "shearY" :mix-rotate "mixRotate"
   :mix-x "mixX" :mix-y "mixY" :mix-scale-x "mixScaleX" :mix-scale-y "mixScaleY" :mix-shear-y "mixShearY"})

(defn- next-constraint-order [document]
  (inc (reduce max -1 (mapv #(get % :order 0) (into (get document :ik []) (:transform document))))))

(defn- remove-source-transform [text id]
  (json/write-str
    (-> (json/read-str text)
        (update "transform" #(filterv (fn [constraint] (not= id (get constraint "name"))) %))
        (update "skins" #(mapv (fn [skin] (cond-> skin (get skin "transform") (update "transform" (fn [ids] (filterv (fn [value] (not= id value)) ids))))) %))
        (update "animations" #(coll/map-vals (fn [animation] (cond-> animation (get animation "transform") (update "transform" dissoc id))) %)))))

(defn add-transform [document constraint]
  (let [updated (validate-document
                  (update (validate-document document) :transform (fnil conj [])
                          (constraints/normalize-transform (merge {:order (next-constraint-order document)} constraint))))]
    (cond-> updated (:spine-source updated) (update :spine-source remove-source-transform (:id constraint)))))

(defn update-transform [document constraint-id changes]
  (let [document (validate-document document)
        new-id (get changes :id constraint-id)
        rename #(if (= constraint-id %) new-id %)
        document (cond-> document
                   (and (:spine-source document) (not= new-id constraint-id))
                   (update :spine-source
                           #(json/write-str
                              (-> (json/read-str %)
                                  (update "transform" (fn [items] (mapv (fn [item] (update item "name" rename)) items)))
                                  (update "skins" (fn [skins] (mapv (fn [skin] (cond-> skin (get skin "transform") (update "transform" (fn [ids] (mapv rename ids))))) skins)))
                                  (update "animations" (fn [animations] (coll/map-vals (fn [animation] (cond-> animation (get animation "transform") (update "transform" rename-map-key constraint-id new-id))) animations)))))))]
    (validate-document
      (-> document
          (update-in [:transform (item-index document :transform constraint-id)] merge changes)
          (update :animations #(mapv (fn [animation] (update animation :transform-tracks (fn [tracks] (mapv (fn [track] (update track :transform rename)) tracks)))) %))))))

(defn remove-transform [document constraint-id]
  (let [document (validate-document document)]
    (item-index document :transform constraint-id)
    (-> (cond-> document (:spine-source document) (update :spine-source remove-source-transform constraint-id))
        (update :transform #(filterv (fn [constraint] (not= constraint-id (:id constraint))) %))
        (update :animations #(mapv (fn [animation] (update animation :transform-tracks (fn [tracks] (filterv (fn [track] (not= constraint-id (:transform track))) tracks)))) %)))))

(defn set-transform-keyframe [document animation-id constraint-id property time value interpolation]
  (require-value! (and (finite-number? time) (not (neg? time))) "Key time must be finite and nonnegative." {})
  (let [document (validate-document document)
        index (item-index document :animations animation-id)
        tracks (get-in document [:animations index :transform-tracks] [])
        matches #(and (= constraint-id (:transform %)) (= property (:property %)))
        track (or (coll/first-where matches tracks) {:transform constraint-id :property property :keys []})
        key (merge (dissoc (coll/first-where #(== time (:time %)) (:keys track)) :curve)
                   {:time time :value value}
                   (if (map? interpolation) {:interpolation (:type interpolation) :curve (:curve interpolation)} {:interpolation interpolation}))]
    (validate-document
      (-> document (update-in [:animations index :duration] max time)
          (assoc-in [:animations index :transform-tracks]
                    (conj (filterv (complement matches) tracks)
                          (update track :keys
                                  (fn [keys]
                                    (let [inserted (vec (sort-by :time (conj (filterv (fn [old] (not (== time (:time old)))) keys) key)))]
                                      (mapv (fn [index frame]
                                              (let [new-right (get inserted (inc index))
                                                    old-right (coll/first-where #(> (double (:time %)) (:time frame)) keys)]
                                                (if (and (not (== time (:time frame))) (= "bezier" (:interpolation frame))
                                                         new-right old-right (not= (double (:time new-right)) (double (:time old-right))))
                                                  (let [[t1 v1 t2 v2] (:curve frame)
                                                        ratio (/ (- (:time new-right) (:time frame)) (- (:time old-right) (:time frame)))
                                                        remap #(+ (:time frame) (* ratio (- % (:time frame))))]
                                                    (assoc frame :curve [(remap t1) v1 (remap t2) v2])) frame)))
                                            (range (count inserted)) inserted))))))))))

(defn remove-transform-keyframe [document animation-id constraint-id property time]
  (require-value! (finite-number? time) "Key time must be finite." {})
  (let [document (validate-document document)
        index (item-index document :animations animation-id)]
    (update-in document [:animations index :transform-tracks]
               #(into [] (comp (map (fn [track]
                                     (if (and (= constraint-id (:transform track)) (= property (:property track)))
                                       (update track :keys (fn [keys] (filterv (fn [key] (not (== time (:time key)))) keys))) track)))
                               (filter (fn [track] (pos? (count (:keys track)))))) %))))

(defn move-transform-keyframe [document animation-id constraint-id property before after]
  (move-track-keyframe document animation-id :transform-tracks
                       #(and (= constraint-id (:transform %)) (= property (:property %))) before after))

(defn- sample-transforms-impl [document animation-id time]
  (let [animation (when animation-id (get-in document [:animations (item-index document :animations animation-id)]))
        time (if animation (max 0.0 (min time (:duration animation))) 0.0)
        tracks (group-by :transform (:transform-tracks animation))]
    (mapv (fn [constraint]
            (reduce (fn [result {:keys [property keys]}]
                      (let [field (keyword property)] (assoc result field (sample-track-impl keys time (get result field)))))
                    (constraints/normalize-transform constraint) (get tracks (:id constraint)))) (:transform document))))

(defn sample-transforms [document animation-id time]
  (validate-document document)
  (require-value! (finite-number? time) "Sample time must be finite." {})
  (sample-transforms-impl document animation-id time))

(def path-source-fields
  {:order "order" :position-mode "positionMode" :spacing-mode "spacingMode" :rotate-mode "rotateMode"
   :rotation "rotation" :position "position" :spacing "spacing" :mix-rotate "mixRotate" :mix-x "mixX" :mix-y "mixY"})

(defn- remove-source-path [text id]
  (json/write-str
    (-> (json/read-str text)
        (update "path" #(filterv (fn [constraint] (not= id (get constraint "name"))) %))
        (update "skins" #(mapv (fn [skin] (cond-> skin (get skin "path") (update "path" (fn [ids] (filterv (fn [value] (not= id value)) ids))))) %))
        (update "animations" #(coll/map-vals (fn [animation] (cond-> animation (get animation "path") (update "path" dissoc id))) %)))))

(defn add-path [document constraint]
  (let [updated (validate-document
                  (update (validate-document document) :path (fnil conj [])
                          (constraints/normalize-path (merge {:order (next-constraint-order document)} constraint))))]
    (cond-> updated (:spine-source updated) (update :spine-source remove-source-path (:id constraint)))))

(defn update-path [document constraint-id changes]
  (let [document (validate-document document)
        new-id (get changes :id constraint-id)
        rename #(if (= constraint-id %) new-id %)
        document (cond-> document
                   (and (:spine-source document) (not= new-id constraint-id))
                   (update :spine-source
                           #(json/write-str
                              (-> (json/read-str %)
                                  (update "path" (fn [items] (mapv (fn [item] (update item "name" rename)) items)))
                                  (update "skins" (fn [skins] (mapv (fn [skin] (cond-> skin (get skin "path") (update "path" (fn [ids] (mapv rename ids))))) skins)))
                                  (update "animations" (fn [animations] (coll/map-vals (fn [animation] (cond-> animation (get animation "path") (update "path" rename-map-key constraint-id new-id))) animations)))))))]
    (validate-document
      (-> document
          (update-in [:path (item-index document :path constraint-id)] merge changes)
          (update :animations #(mapv (fn [animation] (update animation :path-tracks (fn [tracks] (mapv (fn [track] (update track :path rename)) tracks)))) %))))))

(defn remove-path [document constraint-id]
  (let [document (validate-document document)]
    (item-index document :path constraint-id)
    (-> (cond-> document (:spine-source document) (update :spine-source remove-source-path constraint-id))
        (update :path #(filterv (fn [constraint] (not= constraint-id (:id constraint))) %))
        (update :animations #(mapv (fn [animation] (update animation :path-tracks (fn [tracks] (filterv (fn [track] (not= constraint-id (:path track))) tracks)))) %)))))

(defn set-path-keyframe [document animation-id constraint-id property time value interpolation]
  (require-value! (and (finite-number? time) (not (neg? time))) "Key time must be finite and nonnegative." {})
  (let [document (validate-document document)
        index (item-index document :animations animation-id)
        tracks (get-in document [:animations index :path-tracks] [])
        matches #(and (= constraint-id (:path %)) (= property (:property %)))
        track (or (coll/first-where matches tracks) {:path constraint-id :property property :keys []})
        key (merge (dissoc (coll/first-where #(== time (:time %)) (:keys track)) :curve)
                   {:time time :value value}
                   (if (map? interpolation) {:interpolation (:type interpolation) :curve (:curve interpolation)} {:interpolation interpolation}))]
    (validate-document
      (-> document (update-in [:animations index :duration] max time)
          (assoc-in [:animations index :path-tracks]
                    (conj (filterv (complement matches) tracks)
                          (update track :keys
                                  (fn [keys]
                                    (let [inserted (vec (sort-by :time (conj (filterv (fn [old] (not (== time (:time old)))) keys) key)))]
                                      (mapv (fn [index frame]
                                              (let [new-right (get inserted (inc index))
                                                    old-right (coll/first-where #(> (double (:time %)) (:time frame)) keys)]
                                                (if (and (not (== time (:time frame))) (= "bezier" (:interpolation frame))
                                                         new-right old-right (not= (double (:time new-right)) (double (:time old-right))))
                                                  (let [[t1 v1 t2 v2] (:curve frame)
                                                        ratio (/ (- (:time new-right) (:time frame)) (- (:time old-right) (:time frame)))
                                                        remap #(+ (:time frame) (* ratio (- % (:time frame))))]
                                                    (assoc frame :curve [(remap t1) v1 (remap t2) v2])) frame)))
                                            (range (count inserted)) inserted))))))))))

(defn remove-path-keyframe [document animation-id constraint-id property time]
  (require-value! (finite-number? time) "Key time must be finite." {})
  (let [document (validate-document document)
        index (item-index document :animations animation-id)]
    (update-in document [:animations index :path-tracks]
               #(into [] (comp (map (fn [track]
                                     (if (and (= constraint-id (:path track)) (= property (:property track)))
                                       (update track :keys (fn [keys] (filterv (fn [key] (not (== time (:time key)))) keys))) track)))
                               (filter (fn [track] (pos? (count (:keys track)))))) %))))

(defn move-path-keyframe [document animation-id constraint-id property before after]
  (move-track-keyframe document animation-id :path-tracks
                       #(and (= constraint-id (:path %)) (= property (:property %))) before after))

(defn- sample-paths-impl [document animation-id time]
  (let [animation (when animation-id (get-in document [:animations (item-index document :animations animation-id)]))
        time (if animation (max 0.0 (min time (:duration animation))) 0.0)
        tracks (group-by :path (:path-tracks animation))]
    (mapv (fn [constraint]
            (reduce (fn [result {:keys [property keys]}]
                      (let [field (keyword property)] (assoc result field (sample-track-impl keys time (get result field)))))
                    (constraints/normalize-path constraint) (get tracks (:id constraint)))) (:path document))))

(defn sample-paths [document animation-id time]
  (validate-document document)
  (require-value! (finite-number? time) "Sample time must be finite." {})
  (sample-paths-impl document animation-id time))

(def physics-source-fields
  (merge {:order "order" :x "x" :y "y" :rotate "rotate" :scale-x "scaleX" :shear-x "shearX"
          :limit "limit" :fps "fps" :inertia "inertia" :strength "strength" :damping "damping" :mass "mass"
          :wind "wind" :gravity "gravity" :mix "mix"}
         (into {} (map (fn [property] [(keyword (str property "-global")) (str property "Global")])) physics/animated-properties)))

(defn- remove-source-physics [text id]
  (json/write-str
    (-> (json/read-str text)
        (update "physics" #(filterv (fn [constraint] (not= id (get constraint "name"))) %))
        (update "skins" #(mapv (fn [skin] (cond-> skin (get skin "physics") (update "physics" (fn [ids] (filterv (fn [value] (not= id value)) ids))))) %))
        (update "animations" #(coll/map-vals (fn [animation] (cond-> animation (get animation "physics") (update "physics" dissoc id))) %)))))

(defn add-physics [document constraint]
  (let [updated (validate-document
                  (update (validate-document document) :physics (fnil conj [])
                          (physics/normalize-constraint (merge {:order (next-constraint-order document)} constraint))))]
    (cond-> updated (:spine-source updated) (update :spine-source remove-source-physics (:id constraint)))))

(defn update-physics [document constraint-id changes]
  (let [document (validate-document document)
        new-id (get changes :id constraint-id)
        rename #(if (= constraint-id %) new-id %)
        document (cond-> document
                   (and (:spine-source document) (not= new-id constraint-id))
                   (update :spine-source
                           #(json/write-str
                              (-> (json/read-str %)
                                  (update "physics" (fn [items] (mapv (fn [item] (update item "name" rename)) items)))
                                  (update "skins" (fn [skins] (mapv (fn [skin] (cond-> skin (get skin "physics") (update "physics" (fn [ids] (mapv rename ids))))) skins)))
                                  (update "animations" (fn [animations] (coll/map-vals (fn [animation] (cond-> animation (get animation "physics") (update "physics" rename-map-key constraint-id new-id))) animations)))))))]
    (validate-document
      (-> document
          (update-in [:physics (item-index document :physics constraint-id)] merge changes)
          (update :animations #(mapv (fn [animation] (-> animation (update :physics-order (fn [ids] (mapv rename ids)))
                                                       (update :physics-tracks (fn [tracks] (mapv (fn [track] (update track :physics rename)) tracks))))) %))))))

(defn remove-physics [document constraint-id]
  (let [document (validate-document document)]
    (item-index document :physics constraint-id)
    (-> (cond-> document (:spine-source document) (update :spine-source remove-source-physics constraint-id))
        (update :physics #(filterv (fn [constraint] (not= constraint-id (:id constraint))) %))
        (update :animations #(mapv (fn [animation] (update animation :physics-tracks (fn [tracks] (filterv (fn [track] (not= constraint-id (:physics track))) tracks)))) %)))))

(defn set-physics-keyframe [document animation-id constraint-id property time value interpolation]
  (require-value! (not= "reset" property) "Use set-physics-reset-keyframe for reset events." {})
  (require-value! (and (finite-number? time) (not (neg? time))) "Key time must be finite and nonnegative." {})
  (let [document (validate-document document)
        index (item-index document :animations animation-id)
        tracks (get-in document [:animations index :physics-tracks] [])
        matches #(and (= constraint-id (:physics %)) (= property (:property %)))
        track (or (coll/first-where matches tracks) {:physics constraint-id :property property :keys []})
        key (merge {:spine-frame-source "{}"} (dissoc (coll/first-where #(== time (:time %)) (:keys track)) :curve)
                   {:time time :value value}
                   (if (map? interpolation) {:interpolation (:type interpolation) :curve (:curve interpolation)} {:interpolation interpolation}))]
    (validate-document
      (-> document (update-in [:animations index :duration] max time)
          (assoc-in [:animations index :physics-tracks]
                    (conj (filterv (complement matches) tracks)
                          (update track :keys
                                  (fn [keys]
                                    (let [inserted (vec (sort-by :time (conj (filterv (fn [old] (not (== time (:time old)))) keys) key)))]
                                      (mapv (fn [index frame]
                                              (let [new-right (get inserted (inc index))
                                                    old-right (coll/first-where #(> (double (:time %)) (:time frame)) keys)]
                                                (if (and (not (== time (:time frame))) (= "bezier" (:interpolation frame))
                                                         new-right old-right (not= (double (:time new-right)) (double (:time old-right))))
                                                  (let [[t1 v1 t2 v2] (:curve frame)
                                                        ratio (/ (- (:time new-right) (:time frame)) (- (:time old-right) (:time frame)))
                                                        remap #(+ (:time frame) (* ratio (- % (:time frame))))]
                                                    (assoc frame :curve [(remap t1) v1 (remap t2) v2])) frame)))
                                            (range (count inserted)) inserted))))))))))

(defn remove-physics-keyframe [document animation-id constraint-id property time]
  (require-value! (finite-number? time) "Key time must be finite." {})
  (let [document (validate-document document)
        index (item-index document :animations animation-id)]
    (update-in document [:animations index :physics-tracks]
               #(into [] (comp (map (fn [track]
                                     (if (and (= constraint-id (:physics track)) (= property (:property track)))
                                       (update track :keys (fn [keys] (filterv (fn [key] (not (== time (:time key)))) keys))) track)))
                               (filter (fn [track] (pos? (count (:keys track)))))) %))))

(defn move-physics-keyframe [document animation-id constraint-id property before after]
  (move-track-keyframe document animation-id :physics-tracks
                       #(and (= constraint-id (:physics %)) (= property (:property %))) before after))

(defn set-physics-reset-keyframe [document animation-id constraint-id time]
  (require-value! (and (finite-number? time) (not (neg? time))) "Reset time must be finite and nonnegative." {})
  (let [document (validate-document document)
        index (item-index document :animations animation-id)
        tracks (get-in document [:animations index :physics-tracks] [])
        matches #(and (= constraint-id (:physics %)) (= "reset" (:property %)))
        track (or (coll/first-where matches tracks) {:physics constraint-id :property "reset" :keys []})
        frame (merge {:spine-frame-source "{}"} (coll/first-where #(== time (:time %)) (:keys track)) {:time time :value 0.0 :interpolation "stepped"})]
    (validate-document (-> document (update-in [:animations index :duration] max time)
                           (assoc-in [:animations index :physics-tracks]
                                     (conj (filterv (complement matches) tracks) (update track :keys insert-animation-key frame)))))))

(defn- sample-physics-impl [document animation-id time]
  (let [animation (when animation-id (get-in document [:animations (item-index document :animations animation-id)]))
        time (if animation (max 0.0 (min time (:duration animation))) 0.0)
        order (into (vec (:physics-order animation)) (distinct) (into [""] (map :id) (:physics document)))
        rank (into {} (map-indexed (fn [index id] [id index])) (into [] (distinct) order))
        tracks (sort-by #(get rank (:physics %) Long/MAX_VALUE) (:physics-tracks animation))]
    (mapv (fn [constraint]
            (reduce (fn [result {:keys [physics property keys]}]
                      (if (or (= "reset" property)
                              (not (or (= physics (:id constraint))
                                       (and (= "" physics) (get constraint (keyword (str property "-global"))))))) result
                          (let [field (keyword property)] (assoc result field (sample-track-impl keys time (get result field))))))
                    (physics/normalize-constraint constraint) tracks)) (:physics document))))

(defn sample-physics [document animation-id time]
  (validate-document document)
  (require-value! (finite-number? time) "Sample time must be finite." {})
  (sample-physics-impl document animation-id time))

(defn apply-mesh-edit
  "Apply a topology edit and migrate every active FFD key using its vertex map.
  Weight-only edits retain per-bone offsets. :reset explicitly removes weights
  and all affected FFD, including raw source keys on linked mesh descendants."
  [document slot-id {:keys [mesh vertex-map deform-policy]}]
  (let [document (validate-document document)
        index (item-index document :slots slot-id)
        slot (get-in document [:slots index])
        before (:attachment slot)
        reset (= :reset deform-policy)
        after (cond-> (merge before mesh)
                reset (dissoc :weights :spine-export-hull :spine-export-edges)
                reset (assoc :spine-topology-edited true)
                (or (not= (:triangles before) (:triangles mesh))
                    (not= (count (:vertices before)) (count (:vertices mesh)))
                    (not= (:constrained-edges before) (:constrained-edges mesh)))
                (assoc :spine-topology-edited true))
        document (if (and reset (:spine-source document))
                   (let [source (json/read-str (:spine-source document))
                         initial #{[(get slot :spine-skin "default") slot-id (get before :name slot-id)]}
                         linked (into []
                                      (mapcat (fn [skin]
                                                (into []
                                                      (keep (fn [[name attachment]]
                                                              (when (= "linkedmesh" (get attachment "type"))
                                                                {:id [(get skin "name") slot-id name]
                                                                 :parent [(get attachment "skin" "default") slot-id (get attachment "parent")]})))
                                                      (get-in skin ["attachments" slot-id])))) (get source "skins" []))
                         affected (loop [affected initial]
                                    (let [expanded (into affected (comp (filter #(contains? affected (:parent %))) (map :id)) linked)]
                                      (if (= affected expanded) affected (recur expanded))))
                         clear-track (fn [animation [skin slot name]]
                                       (cond-> animation
                                         (get-in animation ["attachments" skin slot name "deform"])
                                         (update-in ["attachments" skin slot name] dissoc "deform")
                                         (get-in animation ["deform" skin slot name])
                                         (update-in ["deform" skin slot] dissoc name)))]
                     (assoc document :spine-source
                            (json/write-str (update source "animations"
                                                    #(coll/map-vals (fn [animation] (reduce clear-track animation affected)) %)))))
                   document)]
    (require-value! (and (= "mesh" (:type before)) (= "mesh" (:type after))) "Mesh edits require a mesh attachment." {:slot slot-id})
    (validate-document
      (-> document
          (assoc-in [:slots index :attachment] after)
          (update :animations
                  #(mapv (fn [animation]
                           (update animation :deforms
                                   (fn [tracks]
                                     (if reset
                                       (filterv (fn [track] (not= slot-id (:slot track))) tracks)
                                       (mapv (fn [track]
                                             (if (not= slot-id (:slot track)) track
                                                 (update track :keys
                                                         (fn [keys]
                                                           (mapv (fn [key]
                                                                   (update key :vertices
                                                                           (fn [vertices]
                                                                             (if (= :bone-identity deform-policy)
                                                                               (remap-deform-weights vertices before after (:bone slot) (constraints/world-transforms (:bones document)))
                                                                               (mesh/remap-deform before after vertex-map vertices))))) keys))))) tracks))))) %))))))

(defn- import-bone [bone]
  (cond-> {:id (get bone "name") :parent (get bone "parent")
           :inherit (get bone "inherit" (get bone "transform" "normal"))
           :x (get bone "x" 0.0) :y (get bone "y" 0.0) :rotation (get bone "rotation" 0.0)
           :scale-x (get bone "scaleX" 1.0) :scale-y (get bone "scaleY" 1.0)
           :shear-x (get bone "shearX" 0.0) :shear-y (get bone "shearY" 0.0) :length (get bone "length" 0.0)}
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
                      "scalex" [["scale-x" "value" 1.0]] "scaley" [["scale-y" "value" 1.0]]
                      "shear" [["shear-x" "x" 0.0] ["shear-y" "y" 0.0]]
                      "shearx" [["shear-x" "value" 0.0]] "sheary" [["shear-y" "value" 0.0]]} timeline)]
    (mapv (fn [channel [property field fallback]]
            (let [setup (get bone (keyword property) 0.0)
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
    (if-not (contains? #{"mesh" "path"} type)
      attachment
      (let [uvs (if (= "path" type) (vec (repeat (* 2 (get region "vertexCount" 0)) 0.0)) (get region "uvs"))
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
        (cond-> (if (= "path" type)
                  (assoc (dissoc attachment :path) :vertices vertices :weights weights :closed (get region "closed" false)
                         :constant-speed (get region "constantSpeed" true) :lengths (get region "lengths" []))
                  (assoc attachment :vertices vertices :uvs uvs :triangles (get region "triangles" []) :weights weights))
          (get region "edges") (assoc :constrained-edges
                                       (let [edges (get region "edges")]
                                         (require-value! (and (vector? edges) (even? (count edges))
                                                              (coll/every? #(and (integer? %) (even? %) (<= 0 % (dec (count uvs)))) edges))
                                                         "Mesh edge indices must reference vertex XY offsets." {:attachment name})
                                         (into [] (comp (partition-all 2) (map #(mapv (fn [index] (quot index 2)) %))) edges))))))))

(defn decode-spine-attachment
  "Decode one resolved Spine 4.2 region, mesh or path for a preview cache.
  Bones retain the source index order; worlds and slot-bone use current setup
  transforms. Linked meshes must be resolved by the caller before decoding."
  [name attachment bones worlds slot-bone]
  (require-value! (contains? #{"region" "mesh" "path"} (get attachment "type" "region"))
                  "This attachment type cannot be previewed." {:attachment name})
  (import-attachment name attachment bones worlds slot-bone))

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

(defn- physics-source-orders [text]
  (let [^Map source (.readValue (ObjectMapper.) ^String text Map)]
    (into {} (keep (fn [[id ^Map animation]]
                    (when-let [^Map tracks (.get animation "physics")]
                      [id (vec (.keySet tracks))]))) (.get source "animations"))))

(defn- import-spine-json-impl
  "Import Spine 4.2 JSON, retaining source fields for overlay
  export. Ordinary transform, region/mesh, weights, deform and IK data become
  editable. Other skins, attachments and advanced features remain in source;
  :warnings explicitly identifies content not represented in the preview."
  [text selected-skin]
  (let [source (json/read-str text)
        physics-orders (when (pos? (count (get source "physics"))) (physics-source-orders text))
        version (get-in source ["skeleton" "spine"] "")
        _ (require-value! (and (string? version) (boolean (re-matches #"4\.2(?:\..*)?" version))) "Only Spine 4.2 JSON is supported." {:version version})
        bones (mapv import-bone (get source "bones" []))
        _ (validate-bones! bones)
        bone-map (into {} (map (juxt :id identity)) bones)
        worlds (world-transforms bones)
        skins (get source "skins" [])
        _ (require-value! (vector? skins) "Spine 4.2 skins must be an array." {})
        skin (if selected-skin
               (or (coll/first-where #(= selected-skin (get % "name")) skins)
                   (when (and (= "default" selected-skin) (zero? (count skins)))
                     {"name" "default" "attachments" {}})
                   (throw (ex-info "Unknown skin." {:skin selected-skin})))
               (or (coll/first-where #(= "default" (get % "name")) skins) (first skins) {"name" "default" "attachments" {}}))
        skin-name (get skin "name")
        default-skin (coll/first-where #(= "default" (get % "name")) skins)
        attachments (merge-with merge (get default-skin "attachments" {}) (get skin "attachments" {}))
        warnings (transient [])
        _ (run! (fn [section] (when (pos? (count (get source section)))
                               (conj! warnings (str section " are preserved in JSON but are not evaluated by this editor."))))
                ["events"])
        _ (run! (fn [bone]
                  (when (get bone "skin")
                    (conj! warnings (str "Bone " (get bone "name") " has skin-required data; it is preserved but previewed as an active bone."))))
                (get source "bones"))
        _ (run! (fn [[section constraint]]
                  (when (get constraint "skin")
                    (conj! warnings (str "Constraint " (get constraint "name") " (" section ") has skin-required data; it is preserved but previewed as active."))))
                (into [] (mapcat (fn [section] (mapv #(vector section %) (get source section)))) ["ik" "transform" "path" "physics"]))
        slots (mapv (fn [slot]
                      (let [id (get slot "name")
                            name (get slot "attachment")
                            regions (get attachments id {})
                            region (get regions name)
                            supported (contains? #{"region" "mesh" "path"} (get region "type" "region"))]
                        (when (> (count regions) 1) (conj! warnings (str "Slot " id " has additional attachments preserved in JSON; only its setup attachment is editable.")))
                        (when (or (get slot "dark") (not= "normal" (get slot "blend" "normal")))
                          (conj! warnings (str "Slot " id " uses two-color tint/blending preserved in JSON but not previewed.")))
                        (when (and region (not supported)) (conj! warnings (str "Attachment " name " uses " (get region "type") "; it remains in JSON but is not editable.")))
                        (when (get region "sequence")
                          (conj! warnings (str "Attachment " name " contains an image sequence preserved in JSON; sequence frames are not previewed.")))
                        (when (and name (nil? region))
                          (conj! warnings (str "Slot " id " has no setup attachment in the selected skin; its original assignment remains in JSON.")))
                        (cond-> {:id id :bone (get slot "bone")
                                 :spine-skin (if (contains? (get-in skin ["attachments" id]) name) skin-name "default")}
                          (get slot "color") (assoc :color (decode-color (get slot "color")))
                          (and name (or (nil? region) (not supported))) (assoc :unsupported-attachment true)
                          (and region supported) (assoc :attachment (import-attachment name region bones worlds (get slot "bone"))))))
                    (get source "slots" []))
        slot-map (into {} (map (juxt :id identity)) slots)
        ik (mapv (fn [constraint]
                   (constraints/normalize-ik
                     {:id (get constraint "name") :bones (get constraint "bones") :target (get constraint "target") :order (get constraint "order" 0)
                      :mix (get constraint "mix" 1.0) :bend-positive (get constraint "bendPositive" true)
                      :softness (get constraint "softness" 0.0) :compress (get constraint "compress" false)
                      :stretch (get constraint "stretch" false) :uniform (get constraint "uniform" false)}))
                 (sort-by #(get % "order" 0) (get source "ik" [])))
        transforms (mapv (fn [constraint]
                           (constraints/normalize-transform
                             (reduce (fn [result [field source-field]]
                                       (if (contains? constraint source-field) (assoc result field (get constraint source-field)) result))
                                     {:id (get constraint "name") :bones (get constraint "bones") :target (get constraint "target")}
                                     transform-source-fields))) (get source "transform" []))
        paths (mapv (fn [constraint]
                      (constraints/normalize-path
                        (reduce (fn [result [field source-field]]
                                  (if (contains? constraint source-field) (assoc result field (get constraint source-field)) result))
                                {:id (get constraint "name") :bones (get constraint "bones") :target (get constraint "target")}
                                path-source-fields))) (get source "path" []))
        physics-constraints (mapv (fn [constraint]
                                    (physics/normalize-constraint
                                      (reduce (fn [result [field source-field]]
                                                (if (contains? constraint source-field) (assoc result field (get constraint source-field)) result))
                                              {:id (get constraint "name") :bone (get constraint "bone")} physics-source-fields))) (get source "physics" []))
        animations (mapv
                     (fn [[id animation]]
                       (let [tracks (into []
                                          (mapcat (fn [[bone-id timelines]]
                                                    (let [bone (get bone-map bone-id)]
                                                      (require-value! bone "Animation references a missing bone." {:bone bone-id})
                                                      (into [] (mapcat (fn [[timeline frames]]
                                                                        (if (contains? #{"rotate" "translate" "translatex" "translatey" "scale" "scalex" "scaley" "shear" "shearx" "sheary"} timeline)
                                                                          (import-timeline bone timeline frames)
                                                                          (warn-preserved! warnings (str "Animation " id "/" bone-id "/" timeline " is preserved but not editable."))))) timelines))))
                                          (get animation "bones" {}))
                             deforms (into []
                                           (keep (fn [{:keys [skin slot attachment frames]}]
                                                   (let [target (get slot-map slot)]
                                                     (if (and (= skin (:spine-skin target)) (= attachment (get-in target [:attachment :name])) (contains? #{"mesh" "path"} (get-in target [:attachment :type])))
                                                       (import-deform target skin attachment frames)
                                                       (warn-preserved! warnings (str "Deform " id "/" slot "/" attachment " is retained for an inactive attachment."))))))
                                           (spine-deform-tracks animation))
                             ik-tracks (mapv (fn [[ik-id frames]]
                                               {:ik ik-id :keys (mapv (fn [frame]
                                                                       (let [softness-curve (import-curve frame (if (and (vector? (get frame "curve")) (> (count (get frame "curve")) 4)) 4 0) identity)]
                                                                         (merge {:time (get frame "time" 0.0) :mix (get frame "mix" 1.0) :bend-positive (get frame "bendPositive" true)
                                                                                 :softness (get frame "softness" 0.0) :compress (get frame "compress" false) :stretch (get frame "stretch" false)
                                                                                 :softness-interpolation (:interpolation softness-curve) :softness-curve (:curve softness-curve)
                                                                                 :spine-frame-source (json/write-str frame)}
                                                                                (import-curve frame 0 identity)))) frames)})
                                             (get animation "ik" {}))
                             physics-tracks (into [] (mapcat (fn [[constraint timelines]]
                                                              (into [] (keep (fn [[property frames]]
                                                                               (if-not (contains? (conj (set physics/animated-properties) "reset") property)
                                                                                 (warn-preserved! warnings (str "Physics timeline " id "/" constraint "/" property " is preserved but not editable."))
                                                                                 {:physics constraint :property property
                                                                                  :keys (mapv (fn [frame]
                                                                                                (merge {:time (get frame "time" 0.0) :value (get frame "value" 0.0)
                                                                                                        :spine-frame-source (json/write-str frame)}
                                                                                                       (if (= "reset" property) {:interpolation "stepped"} (import-curve frame 0 identity)))) frames)}))) timelines))) (get animation "physics" {}))
                             path-tracks (into [] (mapcat (fn [[constraint timelines]]
                                                           (into [] (mapcat (fn [[timeline frames]]
                                                                             (let [properties (case timeline "position" ["position"] "spacing" ["spacing"] "mix" ["mix-rotate" "mix-x" "mix-y"] [])]
                                                                               (when (zero? (count properties)) (conj! warnings (str "Path timeline " id "/" constraint "/" timeline " is preserved but not editable.")))
                                                                               (mapv (fn [channel property]
                                                                                       {:path constraint :property property
                                                                                        :keys (mapv (fn [frame]
                                                                                                      (merge {:time (get frame "time" 0.0) :spine-frame-source (json/write-str frame)
                                                                                                              :value (if (= "mix" timeline)
                                                                                                                       (get frame (get path-source-fields (keyword property)) (if (= "mix-y" property) (get frame "mixX" 1.0) 1.0))
                                                                                                                       (get frame "value" 0.0))}
                                                                                                             (import-curve frame (* 4 channel) identity))) frames)})
                                                                                     (range (count properties)) properties)))) timelines))) (get animation "path" {}))
                             transform-tracks (into [] (mapcat (fn [[constraint frames]]
                                                                (mapv (fn [channel property]
                                                                        {:transform constraint :property property
                                                                         :keys (mapv (fn [frame]
                                                                                       (merge {:time (get frame "time" 0.0)
                                                                                               :spine-frame-source (json/write-str frame)
                                                                                               :value (get frame (get transform-source-fields (keyword property))
                                                                                                           (case property "mix-y" (get frame "mixX" 1.0) "mix-scale-y" (get frame "mixScaleX" 1.0) 1.0))}
                                                                                              (import-curve frame (* 4 channel) identity))) frames)})
                                                                      (range 6) constraints/transform-properties))) (get animation "transform" {}))]
                         (run! (fn [section] (when (pos? (count (get animation section)))
                                              (conj! warnings (str "Animation " id "/" section " is preserved in JSON but not previewed."))))
                               ["events"])
                         (when (coll/any? (fn [timelines] (coll/any? #(not= "deform" %) (coll/keys timelines)))
                                          (into [] (comp (mapcat coll/vals) (mapcat coll/vals)) (coll/vals (get animation "attachments" {}))))
                           (conj! warnings (str "Attachment sequence timelines in " id " are preserved but not previewed.")))
                         {:id id :duration (timeline-duration animation) :tracks tracks :deforms deforms :ik-tracks ik-tracks :transform-tracks transform-tracks :path-tracks path-tracks :physics-tracks physics-tracks :physics-order (get physics-orders id [])}))
                     (get source "animations" {}))]
    (validate-document {:schema "defold.skeleton" :version 1 :name "Imported skeleton"
                        :spine-source text :active-skin skin-name :warnings (into [] (distinct) (persistent! warnings))
                        :reference-scale (get-in source ["skeleton" "referenceScale"] 100.0)
                        :bones bones :slots slots :ik ik :transform transforms :path paths :physics physics-constraints :animations animations})))

(defn import-spine-json
  "Import Spine 4.2 using the default skin, retaining source data for other
  skins. select-skin commits current edits and materializes another skin."
  [text]
  (import-spine-json-impl text nil))

(defn- upgrade-document [document]
  (let [needs-ik (coll/any? #(not (contains? % :softness)) (:ik document))
        needs-transform (not (contains? document :transform))
        needs-path (not (contains? document :path))
        needs-physics (not (contains? document :physics))]
    (if (or (nil? (:spine-source document)) (not (or needs-ik needs-transform needs-path needs-physics)))
      document
      (let [imported (import-spine-json-impl (:spine-source document) (:active-skin document))
            bones (into {} (map (juxt :id identity)) (:bones imported))
            ik (into {} (map (juxt :id identity)) (:ik imported))
            restored-path-slots (if needs-path
                                  (into #{} (comp (filter :unsupported-attachment)
                                                  (filter (fn [slot] (= "path" (get-in (coll/first-where #(= (:id slot) (:id %)) (:slots imported)) [:attachment :type]))))
                                                  (map :id)) (:slots document)) #{})
            animations (into {} (map (juxt :id identity)) (:animations imported))]
        (-> (cond-> document needs-transform (assoc :transform (:transform imported)) needs-path (assoc :path (:path imported)) needs-physics (assoc :physics (:physics imported) :reference-scale (:reference-scale imported)))
            (update :slots #(mapv (fn [slot] (if (and needs-path (:unsupported-attachment slot))
                                             (let [source-slot (coll/first-where (fn [source-slot] (= (:id slot) (:id source-slot))) (:slots imported))]
                                               (if (= "path" (get-in source-slot [:attachment :type])) (merge slot (select-keys source-slot [:attachment :spine-skin]) {:unsupported-attachment false}) slot)) slot)) %))
            (update :bones #(mapv (fn [bone] (merge (select-keys (bones (:id bone)) [:inherit :shear-x :shear-y]) bone)) %))
            (update :ik #(mapv (fn [constraint] (merge (select-keys (ik (:id constraint)) [:order :softness :compress :stretch :uniform]) constraint)) %))
            (update :animations
                    #(mapv (fn [animation]
                             (let [source (animations (:id animation))
                                   tracks (into {} (map (juxt :ik identity)) (:ik-tracks source))]
                               (-> (merge (select-keys source (cond-> [:transform-tracks] needs-path (conj :path-tracks) needs-physics (into [:physics-tracks :physics-order]))) animation)
                                   (update :deforms (fn [current] (into (filterv (fn [track] (not (contains? restored-path-slots (:slot track)))) current)
                                                                      (filter (fn [track] (contains? restored-path-slots (:slot track)))) (:deforms source))))
                                   (update :ik-tracks (fn [current]
                                                       (mapv (fn [track]
                                                               (update track :keys
                                                                       (fn [keys]
                                                                         (mapv (fn [key]
                                                                                 (merge (select-keys (coll/first-where (fn [old] (== (:time old) (:time key))) (:keys (tracks (:ik track))))
                                                                                                     [:softness :compress :stretch :softness-interpolation :softness-curve]) key)) keys)))) current)))))) %)))))))

(defn- sample-ik-impl
  "Sample imported animated IK mix/bend into the document's constraints."
  [document animation-id time]
  (let [animation (when animation-id (get-in document [:animations (item-index document :animations animation-id)]))
        time (if animation (max 0.0 (min (double time) (double (:duration animation)))) 0.0)
        tracks (into {} (map (juxt :ik identity)) (:ik-tracks animation))]
    (mapv (fn [constraint]
            (if-let [keys (:keys (get tracks (:id constraint)))]
              (let [key (reduce (fn [previous key] (if (> (double (:time key)) time) (reduced previous) key)) nil keys)]
                (cond-> (assoc (constraints/normalize-ik constraint)
                               :mix (sample-track-impl (mapv #(assoc % :value (:mix %)) keys) time (:mix constraint))
                               :softness (sample-track-impl (mapv #(assoc % :value (get % :softness 0.0)
                                                                          :interpolation (get % :softness-interpolation (:interpolation %))
                                                                          :curve (or (:softness-curve %) (:curve %))) keys)
                                                           time (get constraint :softness 0.0)))
                  key (assoc :bend-positive (:bend-positive key) :compress (get key :compress false) :stretch (get key :stretch false))))
              (constraints/normalize-ik constraint))) (:ik document))))

(defn sample-ik [document animation-id time]
  (validate-document document)
  (require-value! (finite-number? time) "Sample time must be finite." {})
  (sample-ik-impl document animation-id time))

(defn- evaluate-pose-step [document animation-id time previous-time physics-state slot-sampler]
  (let [bones (sample-pose-impl document animation-id time)
        transforms (sample-transforms-impl document animation-id time)
        ik (sample-ik-impl document animation-id time)
        paths (sample-paths-impl document animation-id time)
        dynamics (sample-physics-impl document animation-id time)
        animation (coll/first-where #(= animation-id (:id %)) (:animations document))
        resets (into #{} (comp (filter #(= "reset" (:property %)))
                              (filter #(coll/any? (fn [key] (< previous-time (:time key) (+ time 1.0e-12))) (:keys %)))
                              (map :physics)) (:physics-tracks animation))
        sampled-slots (when (and slot-sampler (pos? (count paths))) (slot-sampler document animation-id time))
        slots (into {} (map (juxt :id identity)) (if sampled-slots (:slots sampled-slots) (:slots document)))
        deforms (if sampled-slots (:deforms sampled-slots) (sample-deforms-impl document animation-id time))
        ordered (sort-by #(get (second %) :order 0)
                         (into [] (mapcat (fn [[type values]] (mapv #(vector type %) values)))
                               [[:ik ik] [:transform transforms] [:path paths] [:physics dynamics]]))]
    (reduce (fn [{:keys [pose state]} [type constraint]]
              (case type
                :ik {:pose (constraints/apply-ik-constraint pose constraint) :state state}
                :transform {:pose (constraints/apply-transform-constraint pose constraint) :state state}
                :path {:pose (constraints/apply-path-constraint pose constraint (slots (:target constraint)) (get deforms (:target constraint) [])) :state state}
                :physics (let [bone (coll/first-where #(= (:bone constraint) (:id %)) (:bones pose))
                               result (physics/step constraint (get state (:id constraint))
                                                    {:matrix (get-in pose [:transforms (:bone constraint)]) :length (:length bone) :time time
                                                     :reference-scale (get document :reference-scale 100.0)
                                                     :mode (if (or (contains? resets "") (contains? resets (:id constraint))) :reset-event :update)})]
                           {:pose (constraints/apply-world-transform pose (:bone constraint) (:matrix result))
                            :state (assoc state (:id constraint) (:state result))})))
            {:pose (constraints/pose bones) :state physics-state} ordered)))

(def ^:private validated-pose-documents (atom []))

(defn- evaluate-pose-state-impl [document animation-id time previous-state slot-sampler]
  (when-not (coll/any? #(identical? document %) @validated-pose-documents)
    (validate-document document)
    ;; Documents are immutable. Keep the tiny preview cache bounded, and only
    ;; retain successfully validated identities so edits always cross the edge.
    (swap! validated-pose-documents #(into [document] (take 3) %)))
  (require-value! (finite-number? time) "Sample time must be finite." {})
  (when (and (nil? slot-sampler) animation-id (:spine-source document) (pos? (count (:path document))))
    (let [source (json/read-str (:spine-source document))]
      (require-value!
        (not (coll/any? #(coll/not-empty (get-in source ["animations" animation-id "slots" (:target %) "attachment"])) (:path document)))
        "Animated path attachments require a slot sampler. Use evaluate-pose-with-slots-state." {})))
  (let [animation (when animation-id (get-in document [:animations (item-index document :animations animation-id)]))
        time (if animation (max 0.0 (min time (:duration animation))) 0.0)]
    (if (or (nil? animation) (zero? (count (:physics document))))
      {:pose (:pose (evaluate-pose-step document animation-id time -1.0 {} slot-sampler)) :state nil}
      (let [fps (reduce max 1.0 (mapv #(get % :fps 60.0) (:physics document)))
            target (long (Math/floor (+ (* time fps) 1.0e-10)))
            reusable (and (= document (:document previous-state)) (= animation-id (:animation previous-state))
                          (identical? slot-sampler (:slot-sampler previous-state))
                          (<= (:time previous-state) time))
            initial (if reusable previous-state
                        (let [{:keys [pose state]} (evaluate-pose-step document animation-id 0.0 -1.0 {} slot-sampler)]
                          {:document document :animation animation-id :step 0 :time 0.0 :pose pose :physics state :slot-sampler slot-sampler}))
            _ (require-value! (<= (- target (:step initial)) 100000) "Physics replay is too long. Shorten the animation or reduce simulation FPS." {})
            canonical (loop [cache initial]
                        (if (>= (:step cache) target) cache
                            (let [step (inc (:step cache)) sample-time (/ step fps)
                                  result (evaluate-pose-step document animation-id sample-time (:time cache) (:physics cache) slot-sampler)]
                              (recur (assoc cache :step step :time sample-time :pose (:pose result) :physics (:state result))))))
            pose (if (< (- time (:time canonical)) 1.0e-10) (:pose canonical)
                     (:pose (evaluate-pose-step document animation-id time (:time canonical) (:physics canonical) slot-sampler)))]
        {:pose pose :state canonical}))))

(defn evaluate-pose-state
  "Evaluate shared constraint order with cached, deterministic Physics replay.
  Dynamic path attachment timelines require evaluate-pose-with-slots-state."
  [document animation-id time previous-state]
  (evaluate-pose-state-impl document animation-id time previous-state nil))

(defn evaluate-pose-with-slots-state
  "Evaluate with visible slots and FFD sampled at every Physics replay step.
  slot-sampler receives document, animation ID and time, and returns
  {:slots [...] :deforms {slot-id offsets}}. Pass a stable function to reuse the
  cache. Backward seeks, document edits and sampler changes restart replay."
  [document animation-id time previous-state slot-sampler]
  (require-value! (ifn? slot-sampler) "A slot sampler function is required." {})
  (evaluate-pose-state-impl document animation-id time previous-state slot-sampler))

(defn evaluate-pose
  "Deterministically evaluate animation and every supported constraint in shared order."
  [document animation-id time]
  (:pose (evaluate-pose-state document animation-id time nil)))

(defn evaluate-pose-with-slots
  "Evaluate visible path attachments and every constraint without a cache."
  [document animation-id time slot-sampler]
  (:pose (evaluate-pose-with-slots-state document animation-id time nil slot-sampler)))

(defn- export-curve [key convert]
  (case (:interpolation key)
    "stepped" {"curve" "stepped"}
    "bezier" (let [[t1 v1 t2 v2] (:curve key)] {"curve" [t1 (convert v1) t2 (convert v2)]})
    {}))

(defn- export-timeline [bone {:keys [property keys]} duration]
  (let [setup (get bone (keyword property) 0.0)
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
        base (merge original {"type" (or type "region") "path" (when path (subs path 1 (- (count path) 4)))
                              "x" x "y" y "rotation" rotation "scaleX" scale-x "scaleY" scale-y
                              "width" width "height" height "color" (encode-color color)})]
    (cond
      (= "path" type)
      (assoc (apply dissoc base ["path" "x" "y" "rotation" "scaleX" "scaleY" "width" "height" "uvs" "triangles" "hull" "edges"])
             "closed" (:closed attachment) "constantSpeed" (:constant-speed attachment) "lengths" (:lengths attachment)
             "vertexCount" (quot (count vertices) 2)
             "vertices" (if weights (into [] (mapcat (fn [influences] (into [(count influences)] (mapcat (fn [{:keys [bone x y weight]}] [(get bone-indices bone) x y weight])) influences))) weights) vertices))
      (= "mesh" type)
      (assoc (cond-> base
               (:spine-export-hull attachment) (assoc "hull" (:spine-export-hull attachment) "edges" (:spine-export-edges attachment))) "uvs" uvs "triangles" triangles
             "vertices" (if weights
                          (into [] (mapcat (fn [influences]
                                             (into [(count influences)]
                                                   (mapcat (fn [{:keys [bone x y weight]}] [(get bone-indices bone) x y weight])) influences))) weights)
                          vertices))
      :else (dissoc base "uvs" "triangles" "vertices" "hull" "edges" "parent" "skin" "deform"))))

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

(defn- curve-parameter [[x0 x1 x2 x3] time]
  (loop [low 0.0 high 1.0 iteration 0]
    (let [t (* 0.5 (+ low high)) value (cubic-value x0 x1 x2 x3 t)]
      (if (= iteration 48) t
          (if (< value time) (recur t high (inc iteration)) (recur low t (inc iteration)))))))

(defn- split-curve [points t]
  (let [lerp (fn [a b] (mapv #(+ %1 (* t (- %2 %1))) a b))
        [p0 p1 p2 p3] points a (lerp p0 p1) b (lerp p1 p2) c (lerp p2 p3)
        d (lerp a b) e (lerp b c) f (lerp d e)]
    [[p0 a d f] [f e c p3]]))

(defn- transform-segment [track setup start end]
  (let [keys (:keys track)
        left (reduce (fn [previous key] (if (> (double (:time key)) start) (reduced previous) key)) nil keys)
        right (coll/first-where #(> (double (:time %)) start) keys)
        from (sample-track-impl keys start setup)
        to (sample-track-impl keys end setup)
        stepped (and (not= (double from) (double to)) (or (nil? left) (= "stepped" (:interpolation left))))
        linear [start from end to]]
    {:from from :to to :stepped stepped
     :bezier (and left right (= "bezier" (:interpolation left)))
     :curve (if (and left right (= "bezier" (:interpolation left)))
              (let [[t1 v1 t2 v2] (:curve left)
                    points [[(:time left) (:value left)] [t1 v1] [t2 v2] [(:time right) (:value right)]]
                    a (curve-parameter (mapv first points) start)
                    b (curve-parameter (mapv first points) end)
                    tail (second (split-curve points a))
                    clipped (first (split-curve tail (if (= a 1.0) 0.0 (/ (- b a) (- 1.0 a)))))]
                (into (nth clipped 1) (nth clipped 2))) linear)}))

(defn- export-constraint-mix-tracks [document tracks originals section properties fields normalize]
  (into {}
        (map (fn [[id tracks]]
               (let [constraint (normalize (get-in document [section (item-index document section id)]))
                     by-property (into {} (map (juxt :property identity)) tracks)
                     times (vec (sort (into #{} (comp (mapcat :keys) (map #(double (:time %)))) tracks)))
                     frames (mapv (fn [index time]
                                    (let [end (get times (inc index) time)
                                          segments (mapv (fn [property] (transform-segment (get by-property property) (get constraint (keyword property)) time end)) properties)
                                          changing (filterv #(not= (double (:from %)) (double (:to %))) segments)
                                          stepped (coll/any? :stepped changing)
                                          _ (require-value! (or (not stepped) (coll/every? :stepped changing))
                                                            "Spine constraint mix timelines cannot mix stepped and smooth channels in the same interval. Align their interpolation before exporting."
                                                            {:transform id :time time})
                                          source (reduce (fn [source track]
                                                           (if-let [raw (:spine-frame-source (coll/first-where #(== time (:time %)) (:keys track)))]
                                                             (merge source (json/read-str raw)) source))
                                                         (coll/first-where #(== time (get % "time" 0.0)) (get originals id)) tracks)]
                                      (cond-> (reduce (fn [frame [property segment]] (assoc frame (get fields (keyword property)) (:from segment)))
                                                      (assoc (dissoc source "curve") "time" time) (mapv vector properties segments))
                                        stepped (assoc "curve" "stepped")
                                        (and (not stepped) (coll/any? :bezier segments)) (assoc "curve" (into [] (mapcat :curve) segments)))))
                                  (range (count times)) times)]
                 [id frames])))
        (group-by section tracks)))

(defn- export-transform-tracks [document tracks original]
  (export-constraint-mix-tracks document tracks (get original "transform" {}) :transform
                                constraints/transform-properties transform-source-fields constraints/normalize-transform))

(defn- export-path-tracks [document tracks original]
  (let [current-ids (into #{} (map :id) (:path document))
        preserved (into {} (map (fn [[id timelines]] [id (dissoc timelines "position" "spacing" "mix")])) (select-keys (get original "path" {}) current-ids))
        scalars (filterv #(contains? #{"position" "spacing"} (:property %)) tracks)
        mixes (filterv #(not (contains? #{"position" "spacing"} (:property %))) tracks)
        exported-mixes (export-constraint-mix-tracks document mixes (coll/map-vals #(get % "mix") (get original "path" {}))
                                                   :path ["mix-rotate" "mix-x" "mix-y"] path-source-fields constraints/normalize-path)]
    (reduce (fn [result track]
              (assoc-in result [(:path track) (:property track)]
                        (mapv (fn [key]
                                (merge (dissoc (key-spine-frame key (get-in original ["path" (:path track) (:property track)])) "curve")
                                       {"time" (:time key) "value" (:value key)} (export-curve key identity))) (:keys track))))
            (reduce-kv (fn [result id frames] (assoc-in result [id "mix"] frames)) preserved exported-mixes) scalars)))

(defn- export-physics-tracks [document animation original]
  (let [ids (conj (into #{} (map :id) (:physics document)) "")
        supported (conj (set physics/animated-properties) "reset")
        preserved (into {} (map (fn [[id timelines]] [id (apply dissoc timelines supported)])) (select-keys (get original "physics" {}) ids))
        timelines (reduce (fn [result {:keys [physics property keys]}]
                            (assoc-in result [physics property]
                                      (mapv (fn [key]
                                              (let [frame (dissoc (key-spine-frame key (get-in original ["physics" physics property])) "curve" "value")]
                                                (if (= "reset" property) (assoc frame "time" (:time key))
                                                    (merge frame {"time" (:time key) "value" (:value key)} (export-curve key identity))))) keys)))
                          preserved (:physics-tracks animation))
        order (into [] (distinct) (into (vec (:physics-order animation)) (into [""] (map :id) (:physics document))))
        rank (zipmap order (range (count order)))]
    (into (sorted-map-by (fn [a b] (compare [(get rank a Long/MAX_VALUE) a] [(get rank b Long/MAX_VALUE) b]))) timelines)))

(defn- export-ik-track [{:keys [ik keys]} original]
  (mapv (fn [index key]
          (let [next-key (get keys (inc index))
                time (:time key)
                end (get next-key :time time)
                channels [{:value (:mix key) :next (:mix next-key) :type (:interpolation key) :curve (:curve key)}
                          {:value (get key :softness 0.0) :next (get next-key :softness 0.0)
                           :type (get key :softness-interpolation (:interpolation key)) :curve (or (:softness-curve key) (:curve key))}]
                changing (if next-key (filterv #(not= (double (:value %)) (double (:next %))) channels) [])
                stepped (coll/any? #(= "stepped" (:type %)) changing)
                _ (require-value! (or (not stepped) (coll/every? #(= "stepped" (:type %)) changing))
                                  "Spine IK timelines cannot mix stepped and smooth changing channels. Align mix and softness interpolation before exporting."
                                  {:ik ik :time time})
                bezier (and next-key (coll/any? #(= "bezier" (:type %)) channels))
                curve (when bezier
                        (into [] (mapcat (fn [{:keys [value next type curve]}]
                                           (if (= "bezier" type) curve
                                               [(+ time (/ (- end time) 3.0)) (+ value (/ (- next value) 3.0))
                                                (+ time (* 2.0 (/ (- end time) 3.0))) (+ value (* 2.0 (/ (- next value) 3.0)))]))) channels))]
            (cond-> (merge (dissoc (key-spine-frame key (get-in original ["ik" ik])) "curve")
                           {"time" time "mix" (:mix key) "softness" (get key :softness 0.0)
                            "bendPositive" (:bend-positive key) "compress" (get key :compress false) "stretch" (get key :stretch false)})
              stepped (assoc "curve" "stepped")
              (and bezier (not stepped)) (assoc "curve" curve))))
        (range (count keys)) keys))

(defn- remap-export-deforms [animation slot-id before after vertex-map]
  (update animation :deforms
          (fn [tracks]
            (mapv (fn [track]
                    (if (not= slot-id (:slot track)) track
                        (update track :keys
                                (fn [keys]
                                  (mapv (fn [key]
                                          (update key :vertices (fn [vertices] (mesh/remap-deform before after vertex-map vertices)))) keys))))) tracks))))

(defn- prepare-mesh-exports [document source]
  (reduce
    (fn [document [slot-index slot]]
      (let [attachment (:attachment slot)
            skin (get slot :spine-skin "default")
            name (get attachment :name (:id slot))
            source-skin (coll/first-where #(= skin (get % "name")) (get source "skins" []))
            original (get-in source-skin ["attachments" (:id slot) name])
            changed (or (:spine-topology-edited attachment) (not= "mesh" (get original "type"))
                        (not= (:triangles attachment) (get original "triangles"))
                        (not= (count (:uvs attachment)) (count (get original "uvs"))))]
        (if (or (not= "mesh" (:type attachment)) (not changed)) document
            (let [initial #{[skin (:id slot) name]}
                  links (into [] (mapcat (fn [source-skin]
                                          (into [] (keep (fn [[linked-name linked-attachment]]
                                                           (when (= "linkedmesh" (get linked-attachment "type"))
                                                             {:id [(get source-skin "name") (:id slot) linked-name]
                                                              :parent [(get linked-attachment "skin" "default") (:id slot) (get linked-attachment "parent")]})))
                                                (get-in source-skin ["attachments" (:id slot)])))) (get source "skins" []))
                  linked (disj (loop [affected initial]
                                 (let [expanded (into affected (comp (filter #(contains? affected (:parent %))) (map :id)) links)]
                                   (if (= affected expanded) affected (recur expanded)))) [skin (:id slot) name])
                  _ (run! (fn [animation]
                            (require-value! (not (coll/any? #(contains? linked [(:skin %) (:slot %) (:attachment %)]) (spine-deform-tracks animation)))
                                            "A linked mesh has private deformation keys. Make its geometry independent before changing the parent topology."
                                            {:slot (:id slot) :attachment name})) (coll/vals (get source "animations" {})))
                  {:keys [mesh vertex-map hull edges]} (mesh/spine-export-layout attachment)]
              (-> document
                  (assoc-in [:slots slot-index :attachment] (assoc mesh :spine-export-hull hull :spine-export-edges edges))
                  (update :animations #(mapv (fn [animation] (remap-export-deforms animation (:id slot) attachment mesh vertex-map)) %)))))))
    document (mapv vector (range (count (:slots document))) (:slots document))))

(defn export-spine-json
  "Export Spine 4.2 JSON by overlaying editable data onto the original source.
  Unknown skins, attachments, curves and constraint/timeline fields survive.
  Source image paths are project relative without the PNG extension."
  [document]
  (let [document (validate-document (upgrade-document document))
        source (if-let [text (:spine-source document)] (json/read-str text) {})
        document (prepare-mesh-exports document source)
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
                         (map (fn [{:keys [id duration tracks deforms ik-tracks transform-tracks path-tracks physics-tracks] :as animation}]
                                (let [original (get-in source ["animations" id] {})
                                      bone-tracks (into {} (comp (filter (fn [[bone-id]] (contains? bone-map bone-id)))
                                                                (map (fn [[bone-id timelines]]
                                                                       [bone-id (apply dissoc timelines ["rotate" "translate" "translatex" "translatey" "scale" "scalex" "scaley" "shear" "shearx" "sheary"])])))
                                                        (get original "bones" {}))
                                      tracks (filterv #(pos? (count (:keys %))) tracks)
                                      tracks (if (and (zero? (count tracks)) (pos? (count bones)))
                                               [{:bone (:id (first bones)) :property "rotation"
                                                 :keys [{:time duration :value (:rotation (first bones)) :interpolation "linear"}]}] tracks)
                                      _ (require-value! (or (pos? (count bones)) (zero? duration)) "An empty skeleton cannot export a timed animation." {:animation id})
                                      bone-tracks (reduce (fn [result track]
                                                            (assoc-in result [(:bone track) (get {"x" "translatex" "y" "translatey" "rotation" "rotate" "scale-x" "scalex" "scale-y" "scaley" "shear-x" "shearx" "shear-y" "sheary"} (:property track))]
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
                                      ik-tracks (into {} (map (fn [track] [(:ik track) (export-ik-track track original)])) ik-tracks)]
                                  [id (cond-> (assoc (dissoc original "attachments" "ik" "transform" "path" "physics") "bones" bone-tracks)
                                        (get original "slots") (assoc "slots" (select-keys (get original "slots") current-slot-ids))
                                        (pos? (count deform-tracks)) (assoc "attachments" deform-tracks)
                                        (pos? (count ik-tracks)) (assoc "ik" ik-tracks)
                                        (pos? (count transform-tracks)) (assoc "transform" (export-transform-tracks document transform-tracks original))
                                        (or (pos? (count path-tracks)) (get original "path")) (assoc "path" (export-path-tracks document path-tracks original))
                                        (or (pos? (count physics-tracks)) (get original "physics")) (assoc "physics" (export-physics-tracks document animation original)))]))) (:animations document))
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
        old-transform (into {} (map (juxt #(get % "name") identity)) (get source "transform" []))
        skins (mapv (fn [skin] (cond-> skin (get skin "transform") (update "transform" #(filterv (into #{} (map :id) (:transform document)) %)))) skins)
        old-path (into {} (map (juxt #(get % "name") identity)) (get source "path" []))
        skins (mapv (fn [skin] (cond-> skin (get skin "path") (update "path" #(filterv (into #{} (map :id) (:path document)) %)))) skins)
        old-physics (into {} (map (juxt #(get % "name") identity)) (get source "physics" []))
        skins (mapv (fn [skin] (cond-> skin (get skin "physics") (update "physics" #(filterv (into #{} (map :id) (:physics document)) %)))) skins)
        maximum-ik-order (reduce max -1 (mapv #(get % "order" 0) (get source "ik" [])))
        output (assoc source
                      "skeleton" (assoc (merge {"spine" "4.2.0"} (get source "skeleton" {})) "referenceScale" (get document :reference-scale 100.0))
                      "bones" (mapv (fn [{:keys [id parent x y rotation scale-x scale-y shear-x shear-y length inherit spine-color]}]
                                      (cond-> (assoc (dissoc (get old-bones id {}) "parent") "name" id "x" x "y" y "rotation" rotation "scaleX" scale-x "scaleY" scale-y "shearX" (or shear-x 0.0) "shearY" (or shear-y 0.0) "inherit" (or inherit "normal") "length" length)
                                        parent (assoc "parent" parent) spine-color (assoc "color" spine-color))) ordered-bones)
                      "slots" slots "skins" skins "animations" animations
                      "physics" (mapv (fn [constraint]
                                        (reduce (fn [result [field source-field]]
                                                  (assoc result source-field (get constraint field false)))
                                                (assoc (get old-physics (:id constraint) {}) "name" (:id constraint) "bone" (:bone constraint))
                                                physics-source-fields)) (mapv physics/normalize-constraint (:physics document)))
                      "path" (mapv (fn [constraint]
                                     (reduce (fn [result [field source-field]] (assoc result source-field (get constraint field)))
                                             (assoc (get old-path (:id constraint) {}) "name" (:id constraint) "bones" (:bones constraint) "target" (:target constraint))
                                             path-source-fields)) (mapv constraints/normalize-path (:path document)))
                      "transform" (mapv (fn [constraint]
                                          (reduce (fn [result [field source-field]] (assoc result source-field (get constraint field)))
                                                  (assoc (get old-transform (:id constraint) {}) "name" (:id constraint) "bones" (:bones constraint) "target" (:target constraint))
                                                  transform-source-fields)) (mapv constraints/normalize-transform (:transform document)))
                      "ik" (mapv (fn [index {:keys [id bones target mix bend-positive softness compress stretch uniform order]}]
                                   (assoc (get old-ik id {}) "name" id "bones" bones "target" target "mix" mix "bendPositive" bend-positive "softness" (or softness 0.0)
                                          "compress" (boolean compress) "stretch" (boolean stretch) "uniform" (boolean uniform)
                                          "order" (or order (get (get old-ik id) "order" (+ maximum-ik-order 1 index)))))
                                 (range (count (:ik document))) (:ik document)))
        ;; Spine runs orders 0..constraint-count-1. Deletion and user-assigned
        ;; priorities may leave gaps, so serialize the same stable preview order
        ;; using contiguous indices instead of silently disabling later constraints.
        sections ["ik" "transform" "path" "physics"]
          ordered (sort-by #(get (second %) "order" 0)
                           (into [] (mapcat (fn [section] (mapv #(vector section %) (get output section)))) sections))
          orders (into {} (map-indexed (fn [index [section constraint]] [[section (get constraint "name")] index])) ordered)
          output (reduce (fn [output section]
                           (update output section #(mapv (fn [constraint] (assoc constraint "order" (get orders [section (get constraint "name")]))) %))) output sections)]
    (doseq [{:keys [id duration]} (:animations document)]
      (require-value! (<= (timeline-duration (get animations id)) (+ (double duration) 1.0e-9))
                      "Animation duration cannot end before preserved inactive or unsupported timeline keys. Edit or remove those keys first."
                      {:animation id :duration duration}))
    (str (json/write-str output :escape-slash false :indent true) "\n")))

(defn skin-names [document]
  (if-let [source (:spine-source document)]
    (let [names (mapv #(get % "name") (get (json/read-str source) "skins" []))]
      (if (pos? (count names)) names ["default"]))
    ["default"]))

(defn- select-skin-impl [document source skin-id]
  (let [selected (import-spine-json-impl source skin-id)
        deforms (into {} (map (juxt :id :deforms)) (:animations selected))]
    ;; Skin selection changes attachments, not authoring duration, bone metadata
    ;; or empty tracks. Preserve those values rather than reimporting them.
    (-> document
        (merge (select-keys selected [:slots :spine-source :active-skin :warnings]))
        (update :animations #(mapv (fn [animation]
                                    (assoc animation :deforms (get deforms (:id animation) []))) %)))))

(defn select-skin
  "Flush current editable attachments/deforms into the complete source before
  selecting a skin. Default attachments remain available as per-slot fallback."
  [document skin-id]
  (if (= (get document :active-skin "default") skin-id)
    document
    (select-skin-impl document (export-spine-json document) skin-id)))

(defn- duplicate-spine-skin [source skin new-id]
  (let [names (into #{}
                    (comp (mapcat #(coll/vals (get % "attachments")))
                          (mapcat identity)
                          (map (fn [[placeholder attachment]] (get attachment "name" placeholder))))
                    (get source "skins" []))
        [attachments _]
        (reduce-kv
          (fn [[result names] slot attachments]
            (reduce-kv
              (fn [[result names] placeholder attachment]
                (let [prefix (str new-id "/" slot "/" placeholder)
                      name (loop [index 0]
                             (let [name (if (zero? index) prefix (str prefix "_" index))]
                               (if (contains? names name) (recur (inc index)) name)))
                      copy (assoc attachment "name" name
                                  "path" (get attachment "path" (get attachment "name" placeholder)))]
                  [(assoc-in result [slot placeholder] copy) (conj names name)]))
              [result names] attachments))
          [{} names] (get skin "attachments" {}))]
    (assoc skin "name" new-id "attachments" attachments)))

(defn duplicate-skin
  "Duplicate the active skin, including its deform timelines. New names must
  be unique; switching away commits edits before the source skin is copied."
  [document new-id]
  (require-value! (valid-id? new-id) "A skin needs a nonempty name." {})
  (let [source (json/read-str (export-spine-json document))
        skins (get source "skins" [])
        skins (if (zero? (count skins)) [{"name" "default" "attachments" {}}] skins)
        old-id (get document :active-skin "default")
        skin (or (coll/first-where #(= old-id (get % "name")) skins) {"name" old-id "attachments" {}})
        promote-default (and (= "default" old-id) (pos? (count (get skin "attachments"))))]
    (require-value! (not (coll/any? #(= new-id (get % "name")) skins)) "A skin with this name already exists." {:skin new-id})
    ;; Default attachments are direct slot children in the Spine editor. A skin
    ;; placeholder cannot have the same name as that direct attachment. Promote
    ;; a single default-only skeleton into a named base skin before duplication.
    ;; Mixed-skin projects need a separate fallback migration; never silently
    ;; remove their shared default attachments.
    (require-value! (or (not promote-default) (= 1 (count skins)))
                    "Select a named skin to duplicate. Converting shared default attachments in a mixed-skin project is not supported yet." {})
    (let [base-id (if (= "base" new-id) "base1" "base")
          old-id (if promote-default base-id old-id)
          skin (if promote-default (duplicate-spine-skin source skin base-id) skin)
          skins (if promote-default [skin] skins)
          source (if promote-default
                   (update source "animations"
                           #(coll/map-vals (fn [animation]
                                             (if (contains? animation "attachments")
                                               (update animation "attachments" rename-map-key "default" base-id)
                                               animation)) %))
                   source)
          source (-> source
                     (assoc "skins" (conj skins (duplicate-spine-skin source skin new-id)))
                     (update "animations" #(coll/map-vals
                                              (fn [animation]
                                                (if-let [tracks (get-in animation ["attachments" old-id])]
                                                  (assoc-in animation ["attachments" new-id] tracks)
                                                  animation)) %)))
          source (if promote-default
                   (update source "skins"
                           #(mapv (fn [skin]
                                    (update skin "attachments"
                                            (fn [slots]
                                              (coll/map-vals
                                                (fn [attachments]
                                                  (coll/map-vals
                                                    (fn [attachment]
                                                      (if (and (get attachment "parent") (= "default" (get attachment "skin" "default")))
                                                        (assoc attachment "skin" base-id)
                                                        attachment)) attachments)) slots)))) %))
                   source)]
      (select-skin-impl document (json/write-str source :escape-slash false) new-id))))
