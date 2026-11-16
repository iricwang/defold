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

(ns editor.skeletal-animation-inherit
  "Native discrete bone inheritance timelines. Independent of setup data."
  (:require [clojure.data.json :as json]
            [editor.skeletal-animation-geometry :as geometry]
            [util.coll :as coll]))

(def modes ["normal" "onlyTranslation" "noRotationOrReflection" "noScale" "noScaleOrReflection"])

(defn tracks [source animation-id]
  (coll/into-> (get-in source ["animations" animation-id "bones"]) []
    (keep (fn [[bone timelines]]
            (when-let [frames (get timelines "inherit")]
              {:inherit bone :bone bone :property "inherit"
               :keys (mapv (fn [frame] {:time (get frame "time" 0.0) :value (get frame "inherit" "normal") :interpolation "stepped"}) frames)})))))

(defonce ^:private source-cache (atom []))

(defn document-tracks [document animation-id]
  (let [text (:spine-source document)
        source (or (:source (coll/first-where #(identical? text (:text %)) @source-cache))
                   (let [source (if text (json/read-str text) {})]
                     (swap! source-cache #(coll/into-> % [{:text text :source source}] (take 3)))
                     source))]
    (tracks source animation-id)))

(defn sample [document animation-id time bones]
  (let [values (coll/into-> (document-tracks document animation-id) {}
                 (keep (fn [track]
                         (when-let [key (peek (filterv #(<= (double (float (:time %))) time) (:keys track)))]
                           [(:bone track) (:value key)]))))]
    (mapv (fn [bone] (if-let [mode (values (:id bone))] (assoc bone :inherit mode) bone)) bones)))

(defn persist [document source animation-id tracks]
  (let [ids (set (mapv :id (:bones document)))
        source (update-in source ["animations" animation-id "bones"]
                          #(coll/map-vals (fn [timelines] (dissoc timelines "inherit")) %))]
    (reduce (fn [source {:keys [inherit keys]}]
              (when-not (and (contains? ids inherit)
                             (coll/every? #(and (geometry/finite-number? (:time %)) (<= 0 (:time %))
                                                (contains? (set modes) (:value %))) keys)
                             (or (< (count keys) 2) (apply < (mapv :time keys))))
                (throw (ex-info "Invalid bone inheritance timeline." {:bone inherit})))
              (assoc-in source ["animations" animation-id "bones" inherit "inherit"]
                        (mapv (fn [key] {"time" (:time key) "inherit" (:value key)}) keys))) source tracks)))
