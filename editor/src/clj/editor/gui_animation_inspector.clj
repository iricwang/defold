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
            [editor.ui :as ui])
  (:import [javafx.geometry Insets Pos]
           [javafx.scene Node]
           [javafx.scene.control Button CheckBox ComboBox Label TextField]
           [javafx.scene.layout GridPane HBox Priority VBox]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(def ^:private boolean-properties
  #{"enabled" "visible" "inherit_alpha" "clipping_visible" "clipping_inverted" "line_break"})

(def ^:private enum-values
  {"pivot" ["pivot_center" "pivot_n" "pivot_ne" "pivot_e" "pivot_se" "pivot_s" "pivot_sw" "pivot_w" "pivot_nw"]
   "blend_mode" ["alpha" "add" "multiply" "screen"]
   "size_mode" ["manual" "auto"]
   "clipping_mode" ["none" "stencil"]
   "outer_bounds" ["ellipse" "rectangle"]})

(defn- selected-index [{:keys [selected-raw selected-raws]}]
  (or selected-raw
      (when (= 1 (count selected-raws))
        (first selected-raws))))

(defn create! [{:keys [state commit! set-time! localize! guard template-document! node-names tween-properties key-kinds]
                 :or {tween-properties animation/supported-properties
                      key-kinds ["tween" "trigger" "event" "animation"]}}]
  (let [loading (atom false)
        context (atom nil)
        root (doto (HBox. 10.0) (.setId "gui-animation-key-inspector") (.setPadding (Insets. 6.0)))
        empty-label (doto (Label.) (.setId "gui-animation-selection-empty") (localize! "select-segment"))
        kind (doto (ComboBox.) (.setId "gui-animation-key-kind") (ui/items! key-kinds) (.setValue "event"))
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
        form (doto (GridPane.) (.setHgap 6.0) (.setVgap 4.0))
        value (fn [^ComboBox control] (if (.isEditable control) (.getText (.getEditor control)) (str (.getValue control))))
        number-value (fn [^TextField field] (Double/parseDouble (.getText field)))
        payload-controls (mapv (fn [^TextField field]
                                 (let [check (doto (CheckBox.) (.setId (str (.getId field) "-boolean")))
                                       choice (doto (ComboBox.) (.setId (str (.getId field) "-enum")))
                                       box (HBox. 4.0)]
                                   (.setOnAction check (ui/event-handler _
                                                        (when-not @loading
                                                          (.setText field (str (.isSelected check))))))
                                   (.setOnAction choice (ui/event-handler _
                                                         (when-not @loading
                                                           (.setText field (str (.getValue choice))))))
                                   (ui/children! box [field check choice])
                                   {:field field :check check :choice choice :box box}))
                               [start-data data])
        fields (mapv (fn [[suffix control]]
                       [(doto (Label.) (localize! suffix)) control])
                     [["key-type" kind] ["node" node] ["time" start]
                      ["current-property" property] ["event-id" event]
                      ["start-data" (:box (first payload-controls))] ["event-data" (:box (second payload-controls))]
                      ["duration" duration] ["from" from] ["to" to]
                      ["interpolation" easing] ["template" template]])
        render! (fn []
                  (let [kind-value (.getValue kind)
                        continuous (try (pos? (double (number-value duration))) (catch NumberFormatException _ false))
                        visible (case kind-value
                                  "event" (cond-> #{kind node start event (:box (second payload-controls)) duration}
                                            continuous (into [from to easing]))
                                  "trigger" #{kind node start property (:box (first payload-controls)) (:box (second payload-controls))}
                                  "animation" #{kind node start property duration from to easing template}
                                  #{kind node start property duration from to easing})]
                    (loop [remaining fields index 0]
                      (when-let [[^Label label ^Node control] (first remaining)]
                        (let [shown (contains? visible control)]
                          (doseq [^Node item [label control]]
                            (.setVisible item shown)
                            (.setManaged item shown))
                          (when shown
                            (GridPane/setColumnIndex label (int (* 2 (long (mod index 3)))))
                            (GridPane/setColumnIndex control (int (inc (* 2 (long (mod index 3))))))
                            (GridPane/setRowIndex label (int (quot index 3)))
                            (GridPane/setRowIndex control (int (quot index 3))))
                          (recur (subvec remaining 1) (if shown (inc index) index)))))
                    (reset! loading true)
                    (try
                      (doseq [{:keys [^TextField field ^CheckBox check ^ComboBox choice]} payload-controls]
                        (let [property-id (value property)
                              boolean-control (and (= "trigger" kind-value) (contains? boolean-properties property-id))
                              options (when (= "trigger" kind-value) (get enum-values property-id))
                              text (.getText field)]
                          (.setIndeterminate check (not (contains? #{"true" "false"} text)))
                          (.setSelected check (= "true" text))
                          (when options
                            (ui/items! choice (if (contains? (set options) text) options (into [text] options)))
                            (.setValue choice text))
                          (doseq [[^Node control shown] [[field (not (or boolean-control options))] [check boolean-control] [choice (boolean options)]]]
                            (.setVisible control shown)
                            (.setManaged control shown))))
                      (finally (reset! loading false)))))
        fill! (fn [key]
                (reset! loading true)
                (try
                  (.setValue kind (animation/key-kind key))
                  (ui/items! property (case (animation/key-kind key)
                                       "tween" tween-properties
                                       "trigger" animation/trigger-properties
                                       "animation" (animation/animation-ids (:document @state)) ["event"]))
                  (.setValue node (get key :node_id ""))
                  (.setValue property (get key :property_id ""))
                  (.setText (.getEditor node) (get key :node_id ""))
                  (.setText (.getEditor property) (get key :property_id ""))
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
                  (.setDisable event (not= "event" (animation/key-kind key)))
                  (.setDisable start-data (not= "trigger" (animation/key-kind key)))
                  (.setDisable data (not (contains? #{"event" "trigger"} (animation/key-kind key))))
                  (finally (reset! loading false)))
                (render!))
        apply! (fn []
                 (let [{:keys [document animation-id]} @state
                       selected-raw (selected-index @state)
                       old (get (animation/animation-keys document animation-id) selected-raw)
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
                               (animation/replace-key document animation-id selected-raw key)))
                   (swap! state assoc :selected-raw selected-raw :selected-raws #{selected-raw})))
        action (fn [suffix id f]
                 (doto (Button.) (.setId id) (ui/add-style! "button-small") (localize! suffix)
                       (.setOnAction (ui/event-handler _ ((guard f) nil)))))
        actions (doto (HBox. 6.0) (.setAlignment Pos/CENTER_RIGHT)
                       (ui/children! [editor-only
                                      (action "apply" "gui-animation-apply-segment" apply!)
                                      (action "delete" "gui-animation-delete-segment"
                                              #(when-let [index (selected-index @state)]
                                                 (commit! (fn [document] (animation/remove-key document (:animation-id @state) index)))
                                                 (swap! state assoc :selected-raw nil :selected-raws #{})))]))
        right (doto (VBox. 5.0) (.setMinWidth 540.0) (ui/children! [form actions]))]
    (doseq [[^Label label ^Node control] fields]
        (.add form label 0 0)
        (.add form control 1 0)
        (GridPane/setHgrow control Priority/ALWAYS)
        (when (instance? javafx.scene.layout.Region control)
          (.setMinWidth ^javafx.scene.layout.Region control 65.0)
          (.setPrefWidth ^javafx.scene.layout.Region control 110.0)))
    (.setOnAction kind (ui/event-handler _
                        (when-not @loading
                          (ui/items! property (case (.getValue kind)
                                                "tween" tween-properties
                                                "trigger" animation/trigger-properties
                                                "animation" (animation/animation-ids (template-document! (value node))) []))
                          (.setDisable event (not= "event" (.getValue kind)))
                          (.setDisable start-data (not= "trigger" (.getValue kind)))
                          (.setDisable data (not (contains? #{"event" "trigger"} (.getValue kind))))
                          (render!))))
    (.setOnAction property (ui/event-handler _ (when-not @loading (render!))))
    (ui/observe (.textProperty (.getEditor property)) (fn [_ _ _] (when-not @loading (render!))))
    (ui/observe (.textProperty duration) (fn [_ _ _] (when-not @loading (render!))))
    (HBox/setHgrow right Priority/ALWAYS)
    (ui/children! root [empty-label right])
    {:node root
     :select! (fn [index]
                (let [{:keys [document animation-id]} @state
                      key (get (animation/animation-keys document animation-id) index)]
                  (set-time! (get key :start_time 0))
                  (swap! state assoc :selected nil :selected-keys #{} :selected-raw index :selected-raws #{index})))
     :refresh! (fn []
                 (let [{:keys [document animation-id]} @state
                       selected-raw (selected-index @state)
                       key (get (animation/animation-keys document animation-id) selected-raw)]
                   (let [names (into [""] (node-names))]
                     (when (not= names (vec (.getItems node)))
                       (let [current (value node)]
                         (ui/items! node names)
                         (.setValue node current))))
                   (when (not= @context [animation-id selected-raw key])
                     (reset! context [animation-id selected-raw key])
                     (.setVisible right (boolean key))
                     (.setManaged right (boolean key))
                     (.setVisible empty-label (nil? key))
                     (.setManaged empty-label (nil? key))
                     (when key
                       (fill! key)))))}))
