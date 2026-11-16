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

(ns editor.skeletal-animation-skin
  "Spine 4.2 skin bone and constraint membership, independent of pose solving."
  (:require [clojure.data.json :as json]
            [util.coll :as coll]))

(defn- source-data [document]
  (if-let [text (:spine-source document)]
    (json/read-str text)
    {}))

(defn- source-section [kind]
  (case kind
    :bone "bones"
    :ik "ik"
    :transform "transform"
    :path "path"
    :physics "physics"))

(defn- selected-skin [document source]
  (coll/first-where #(= (get document :active-skin "default") (get % "name")) (get source "skins")))

(defn required?
  "Whether the bone or constraint is activated only by skin membership."
  [document kind id]
  (true? (get (coll/first-where #(= id (get % "name"))
                                (get (source-data document) (source-section kind))) "skin")))

(defn included?
  "Whether the current skin explicitly includes this bone or constraint."
  [document kind id]
  (contains? (into #{} (get (selected-skin document (source-data document)) (source-section kind))) id))

(defn- activate-ancestors [bones active id]
  (if-not id
    active
    (recur bones (conj active id) (:parent (get bones id)))))

(defn activation
  "Return active bone IDs and constraint IDs for the document's selected skin.
  Membership activates a bone and its ancestors; an ordinary child remains
  active even when its parent requires a different skin, matching Spine 4.2.
  Evaluate once per immutable document, outside Physics replay substeps."
  [document]
  (let [source (source-data document)
        skin (selected-skin document source)
        bones (coll/into-> (:bones document) {} (map (juxt :id identity)))
        required-bones (coll/into-> (get source "bones") #{}
                         (filter #(true? (get % "skin")))
                         (map #(get % "name")))
        initial-bones (coll/into-> (:bones document) #{} (map :id) (remove required-bones))
        active-bones (reduce (partial activate-ancestors bones) initial-bones (get skin "bones"))
        slot-bones (coll/into-> (:slots document) {} (map (juxt :id :bone)))]
    {:bone-ids active-bones
     :constraints
     (coll/into-> [:ik :transform :path :physics] {}
       (map (fn [kind]
              (let [section (source-section kind)
                    required-ids (coll/into-> (get source section) #{}
                                   (filter #(true? (get % "skin")))
                                   (map #(get % "name")))
                    members (into #{} (get skin section))]
                [kind (coll/into-> (get document kind) #{}
                        (filter (fn [constraint]
                                  (let [id (:id constraint)
                                        target (case kind
                                                 :physics (:bone constraint)
                                                 :path (get slot-bones (:target constraint))
                                                 (:target constraint))]
                                    (and (contains? active-bones target)
                                         (or (not (contains? required-ids id))
                                             (contains? members id))))))
                        (map :id))]))))}))

(defn- editable-source [document kind id enabled]
  (let [section (if (= :bone kind) :bones kind)]
    (when-not (and (boolean? enabled)
                   (coll/any? #(= id (:id %)) (get document section))
                   (:spine-source document))
      (throw (ex-info "Skin editing requires an existing item, a boolean and materialized Spine JSON." {:kind kind :id id})))
    (source-data document)))

(defn set-required
  "Set skinRequired in preserved Spine JSON. Materialize new documents with
  export-spine-json before invoking, so later skin switches retain all content."
  [document kind id enabled]
  (let [source (editable-source document kind id enabled)
        section (source-section kind)
        items (get source section [])
        items (if (coll/any? #(= id (get % "name")) items)
                items
                (conj items {"name" id}))
        items (mapv #(if (= id (get % "name")) (assoc % "skin" enabled) %) items)]
    (assoc document :spine-source (json/write-str (assoc source section items)))))

(defn set-included
  "Include or remove an item in the current skin without affecting other skins."
  [document kind id included]
  (let [source (editable-source document kind id included)
        skin-name (get document :active-skin "default")
        section (source-section kind)
        skins (get source "skins" [])
        skins (if (selected-skin document source)
                skins
                (conj skins {"name" skin-name "attachments" {}}))
        skins (mapv (fn [skin]
                      (if (not= skin-name (get skin "name"))
                        skin
                        (let [members (coll/into-> (get skin section) [] (remove #(= id %)) (distinct))]
                          (assoc skin section (cond-> members included (conj id)))))) skins)]
    (assoc document :spine-source (json/write-str (assoc source "skins" skins)))))
