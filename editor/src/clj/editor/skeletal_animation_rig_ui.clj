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

(ns editor.skeletal-animation-rig-ui
  "Generated rig presentation without changing skeleton parentage or IDs."
  (:require [editor.ui :as ui]
            [util.coll :as coll])
  (:import [javafx.scene.control ToggleButton ToggleGroup Tooltip]
           [javafx.scene.layout FlowPane]))

(set! *warn-on-reflection* true)

(defn groups [doc]
  (let [bones (coll/into-> (:bones doc) {} (map (juxt :id identity)))
        slots (coll/into-> (:slots doc) {} (map (juxt :id identity)))]
    (coll/into-> (:path doc) []
      (keep (fn [constraint]
              (when-let [rig (coll/first-where #(and (pos? (count (:bones %)))
                                                     (= (:bones %) (:bones constraint)))
                                               (coll/vals (:auto-rigs doc)))]
                (let [ids (into (:bones rig) (:controls rig))
                      parent (:parent (bones (first ids)))
                      slot (slots (:target constraint))]
                  ;; Regroup only intact generated siblings. Custom reparenting
                  ;; keeps its actual layout. Ignore stale metadata.
                  (when (and (= 1 (count (filterv #(= (:bones rig) (:bones %)) (:path doc))))
                             (pos? (count (:controls rig)))
                             (coll/every? #(contains? bones %) ids)
                             (coll/every? #(= parent (:parent (bones %))) ids)
                             (= parent (:bone slot)) (= "path" (get-in slot [:attachment :type])))
                    (assoc (select-keys rig [:slot :bones :controls])
                      :id (:id constraint) :target (:target constraint) :parent parent)))))))))

(defn ordered-controls [doc constraint-id controls]
  (let [preferred (:controls (coll/first-where #(= constraint-id (:id %)) (groups doc)))
        available (set controls)]
    (into (filterv available preferred) (sort (remove (set preferred) controls)))))

(defn control-grid [controls selected select! tooltip]
  (let [group (ToggleGroup.)
        pane (doto (FlowPane. 5.0 5.0)
               (.setId "skeletal-rig-control-grid")
               (.setMinWidth 0.0)
               (.setPrefWrapLength 220.0)
               (.setMaxWidth Double/MAX_VALUE))]
    (.addAll (.getChildren pane) ^java.util.Collection
             (mapv (fn [index id]
                     (let [text (str (inc index))]
                       (doto (ToggleButton. text)
                         (.setId (str "skeletal-rig-controller-" id))
                         (.setToggleGroup group)
                         (.setSelected (= selected id))
                         (.setMinSize 32.0 28.0)
                         (.setPrefSize 32.0 28.0)
                         (.setMaxSize 32.0 28.0)
                         (.setFocusTraversable false)
                         (.setTooltip (Tooltip. (tooltip index id)))
                         (.setAccessibleText (tooltip index id))
                         (.setOnAction (ui/event-handler event
                                         (.setSelected ^ToggleButton (.getSource event) true)
                                         (select! id))))))
                   (range (count controls)) controls))
    pane))
