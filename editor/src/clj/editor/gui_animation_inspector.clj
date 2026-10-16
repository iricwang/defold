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

(ns editor.gui-animation-inspector
  "Native controls for Panthera segment, trigger, event and nested-clip keys."
  (:require [clojure.data.json :as json]
            [editor.gui-animation-data :as animation]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [javafx.geometry Insets Pos]
           [javafx.scene Node]
           [javafx.scene.control Button CheckBox ComboBox Label ListView TextField]
           [javafx.scene.layout GridPane HBox Priority VBox]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn create! [{:keys [state commit! set-time! localize! guard template-document! node-names]}]
  (let [loading (atom false)
        selected (atom nil)
        context (atom nil)
        root (doto (HBox. 10.0) (.setId "gui-animation-key-inspector") (.setPadding (Insets. 6.0)))
        list (doto (ListView.) (.setId "gui-animation-segments") (.setPrefWidth 350.0) (.setMinWidth 200.0))
        kind (doto (ComboBox.) (.setId "gui-animation-key-kind") (ui/items! ["tween" "trigger" "event" "animation"]) (.setValue "event"))
        node (doto (ComboBox.) (.setId "gui-animation-key-node") (.setEditable true))
        property (doto (ComboBox.) (.setId "gui-animation-key-property") (.setEditable true))
        start (doto (TextField. "0") (.setId "gui-animation-key-start"))
        duration (doto (TextField. "0") (.setId "gui-animation-key-duration"))
        from (doto (TextField. "0") (.setId "gui-animation-key-from"))
        to (doto (TextField. "1") (.setId "gui-animation-key-to"))
        event (doto (TextField. "event") (.setId "gui-animation-key-event"))
        data (doto (TextField.) (.setId "gui-animation-key-data"))
        start-data (doto (TextField.) (.setId "gui-animation-key-start-data"))
        easing (doto (ComboBox.) (.setId "gui-animation-key-easing") (ui/items! animation/supported-easings) (.setValue "linear"))
        template (doto (TextField.) (.setId "gui-animation-template-path") (.setPromptText "/path/template_panthera.json"))
        editor-only (doto (CheckBox.) (.setId "gui-animation-key-editor-only") (localize! "editor-only"))
        event-status (doto (Label.) (.setId "gui-animation-event-status") (.setMaxWidth 350.0))
        form (doto (GridPane.) (.setHgap 6.0) (.setVgap 4.0))
        value (fn [^ComboBox control] (if (.isEditable control) (.getText (.getEditor control)) (str (.getValue control))))
        number-value (fn [^TextField field] (Double/parseDouble (.getText field)))
        fill! (fn [index key]
                (reset! loading true)
                (try
                  (reset! selected index)
                  (.setValue kind (animation/key-kind key))
                  (ui/items! property (case (animation/key-kind key)
                                       "tween" animation/supported-properties
                                       "trigger" animation/trigger-properties
                                       "animation" (animation/animation-ids (:document @state)) ["event"]))
                  (.setValue node (get key :node_id ""))
                  (.setValue property (get key :property_id ""))
                  (.setText start (str (get key :start_time 0)))
                  (.setText duration (str (get key :duration 0)))
                  (.setText from (str (get key :start_value 0)))
                  (.setText to (str (get key :end_value 1)))
                  (.setText event (get key :event_id "event"))
                  (.setText data (let [value (get key :data "")] (if (string? value) value (json/write-str value))))
                  (.setText start-data (str (get key :start_data "")))
                  (.setValue easing (get key :easing "linear"))
                  (.setText template (let [target (animation/template-document (:document @state) (get key :node_id ""))] (if (string? target) target "")))
                  (.setSelected editor-only (boolean (:is_editor_only key)))
                  (finally (reset! loading false))))
        apply! (fn []
                 (let [{:keys [document animation-id]} @state
                       old (when-let [index @selected] (get (animation/animation-keys document animation-id) index))
                       kind-value (.getValue kind)
                       payload (let [text (.getText data)]
                                 (if (and old (not (string? (:data old))) (some? (:data old)))
                                   (json/read-str text :key-fn keyword) text))
                       key (cond-> (assoc old :key_type kind-value :node_id (value node) :property_id (value property)
                                               :start_time (number-value start) :duration (number-value duration)
                                               :is_editor_only (.isSelected editor-only))
                             (contains? #{"tween" "event" "animation"} kind-value)
                             (assoc :start_value (number-value from) :end_value (number-value to) :easing (.getValue easing))
                             (= "trigger" kind-value) (assoc :start_data (.getText start-data) :data payload)
                             (= "event" kind-value) (assoc :event_id (.getText event) :data payload))]
                   (commit! #(let [document (if (and (= "animation" kind-value) (not (.isEmpty (.getText template))))
                                             (animation/bind-template % (value node) (.getText template)) %)]
                               (animation/replace-key document animation-id @selected key)))))
        action (fn [suffix id f]
                 (doto (Button.) (.setId id) (ui/add-style! "button-small") (localize! suffix)
                       (.setOnAction (ui/event-handler _ ((guard f) nil)))))
        actions (doto (HBox. 6.0) (.setAlignment Pos/CENTER_RIGHT)
                       (ui/children! [event-status editor-only
                                      (action "new-key" "gui-animation-new-segment"
                                              #(do (.clearSelection (.getSelectionModel list))
                                                   (fill! nil {:key_type (.getValue kind) :start_time (:time @state)})))
                                      (action "apply" "gui-animation-apply-segment" apply!)
                                      (action "delete" "gui-animation-delete-segment"
                                              #(when-let [index @selected]
                                                 (commit! (fn [document] (animation/remove-key document (:animation-id @state) index)))
                                                 (reset! selected nil)))]))
        right (doto (VBox. 5.0) (.setMinWidth 760.0) (ui/children! [form actions]))]
    (doseq [[index [suffix ^Node control]] (map-indexed vector [["key-type" kind] ["node" node] ["current-property" property] ["time" start]
                                                               ["duration" duration] ["from" from] ["to" to] ["event-id" event]
                                                               ["start-data" start-data] ["event-data" data] ["interpolation" easing] ["template" template]])]
      (let [column (long (* 2 (long (mod (long index) 4)))) row (long (quot (long index) 4))]
        (.add form (doto (Label.) (localize! suffix)) (int column) (int row))
        (.add form control (int (inc column)) (int row))
        (GridPane/setHgrow control Priority/ALWAYS)
        (when (instance? javafx.scene.layout.Region control)
          (.setMinWidth ^javafx.scene.layout.Region control 65.0)
          (.setPrefWidth ^javafx.scene.layout.Region control 110.0))))
    (ui/cell-factory! list (fn [[index key]]
                            {:text (format "%d  %s  ·  %s  %s  ·  %.3f s + %.3f" (inc (long index)) (animation/key-kind key)
                                           (get key :node_id "") (get key :event_id (get key :property_id ""))
                                           (double (get key :start_time 0)) (double (get key :duration 0)))}))
    (ui/observe (.selectedItemProperty (.getSelectionModel list))
                (fn [_ _ entry]
                  (when (and entry (not @loading))
                    (let [[index key] entry]
                      (fill! index key)
                      (set-time! (get key :start_time 0))
                      (swap! state assoc :selected nil :selected-keys #{} :selected-raw index)))))
    (.setOnAction kind (ui/event-handler _
                        (when-not @loading
                          (ui/items! property (case (.getValue kind)
                                                "tween" animation/supported-properties
                                                "trigger" animation/trigger-properties
                                                "animation" (animation/animation-ids (template-document! (value node))) []))
                          (.setDisable event (not= "event" (.getValue kind)))
                          (.setDisable start-data (not= "trigger" (.getValue kind)))
                          (.setDisable data (not (contains? #{"event" "trigger"} (.getValue kind)))))))
    (HBox/setHgrow right Priority/ALWAYS)
    (ui/children! root [list right])
    {:node root
     :select! #(ui/select-index! list %)
     :refresh! (fn []
                 (let [{:keys [document animation-id time]} @state
                       keys (animation/animation-keys document animation-id)]
                   (let [names (into [""] (node-names))]
                     (when (not= names (vec (.getItems node)))
                       (let [current (value node)]
                         (ui/items! node names)
                         (.setValue node current))))
                   (when (not= @context [animation-id keys])
                     (reset! loading true)
                     (reset! context [animation-id keys])
                     (ui/items! list (mapv vector (range (count keys)) keys))
                     (when-let [index @selected]
                       (if-let [key (get keys index)]
                         (do (ui/select-index! list index) (fill! index key))
                         (reset! selected nil)))
                     (reset! loading false))
                   (let [events (animation/event-samples document animation-id time)]
                     (.setText event-status (coll/join-to-string " · " (mapv #(str (:event_id %) "=" (format "%.2f" (double (:value %)))) (subvec events (max 0 (- (count events) 3))))))
                     (.setAccessibleText list (str (count events) " active events")))))}))
