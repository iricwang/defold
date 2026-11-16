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

(ns editor.skeletal-animation-slot-view
  "Native slot controls; edits enter the caller's undo transaction."
  (:require [clojure.string :as string]
            [editor.skeletal-animation-attachments :as attachments]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-properties :as properties]
            [editor.skeletal-animation-sequence :as sequence]
            [editor.skeletal-animation-slots :as slots]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [javafx.beans.value ChangeListener]
           [javafx.collections FXCollections]
           [javafx.geometry Pos]
           [javafx.scene Node]
           [javafx.scene.control Button CheckBox ColorPicker ComboBox Label ListView SelectionMode TextArea TextField Tooltip]
           [javafx.scene.layout HBox VBox]
           [javafx.scene.paint Color]))

(set! *warn-on-reflection* true)

(defn- identify! [^Node node id]
  (.setId node (str "skeletal-" id))
  node)

(defn- row [nodes]
  (let [box (HBox. 5.0)]
    (.setAlignment box Pos/CENTER_LEFT)
    (.addAll (.getChildren box) ^java.util.Collection nodes)
    box))

(defn- action-button [id text action]
  (doto ^Button (identify! (Button. text) id)
    (.setFocusTraversable false)
    (.setOnAction (ui/event-handler _ (action)))))

(defn- color-text [^Color color channels]
  (coll/join-to-string "" (mapv #(format "%02x" (Math/round (* 255.0 (double %))))
                                (subvec [(.getRed color) (.getGreen color) (.getBlue color) (.getOpacity color)] 0 channels))))

(defn inspector
  "Return attachment/color/draw-order controls for the currently selected slot.
  :tr translates suffix keys. :change! takes a document transformation.
  :current-time optionally reads the live playhead when an edit is committed."
  [{:keys [document animation-id slot-id time current-time tr change! pose-change! sections key-document]}]
  (let [group (fn group [id expanded nodes] (properties/section (merge {:tr tr} sections) id expanded nodes))
        current-time (or current-time (fn current-playhead-time [] time))
        sample (slots/sample-slots document animation-id time)
        slot (coll/first-where #(= slot-id (:id %)) (:slots sample))
        hidden (tr "hidden-attachment")
        names (slots/attachment-names document slot-id)
        ^ComboBox attachment (doto ^ComboBox (identify! (ComboBox. (FXCollections/observableArrayList ^java.util.Collection (into [hidden] names))) "slot-key-attachment")
                               (.setMaxWidth Double/MAX_VALUE)
                               (.setValue (or (second (:preview-attachment-key slot)) hidden)))
        rgba (:color slot [1.0 1.0 1.0 1.0])
        ^ColorPicker picker (identify! (ColorPicker. (Color/color (double (nth rgba 0)) (double (nth rgba 1))
                                                                  (double (nth rgba 2)) (double (nth rgba 3)))) "slot-key-color")
        dark (:dark-color slot [0.0 0.0 0.0])
        dark (or dark [0.0 0.0 0.0])
        ^ColorPicker dark-picker (identify! (ColorPicker. (Color/color (double (nth dark 0)) (double (nth dark 1)) (double (nth dark 2)))) "slot-key-dark-color")
        ^ComboBox property (doto ^ComboBox (identify! (ComboBox. (FXCollections/observableArrayList ^objects (into-array String ["rgba" "rgb" "alpha" "rgba2" "rgb2"]))) "slot-key-color-type")
                             (.setValue "rgba"))
        ^ComboBox interpolation (doto ^ComboBox (identify! (ComboBox. (FXCollections/observableArrayList ^objects (into-array String ["linear" "stepped"]))) "slot-key-color-interpolation")
                                  (.setValue "linear"))
        ^HBox dark-row (row [(Label. (tr "dark-color")) dark-picker])
        update-dark! (fn update-dark [type]
                       (let [visible (contains? #{"rgba2" "rgb2"} type)]
                         (.setVisible dark-row visible)
                         (.setManaged dark-row visible)))
        order (mapv :id (:slots sample))
        ^ListView order-list (doto ^ListView (identify! (ListView. (FXCollections/observableArrayList ^java.util.Collection order)) "slot-key-draw-order")
                               (.setPrefHeight 150.0) (.setMinWidth 0.0) (.setFixedCellSize 30.0))
        preview-attachment! (fn preview-attachment []
                              (when pose-change!
                                (let [name (.getValue attachment)]
                                  (pose-change! #(slots/set-keyframe % animation-id slot-id "attachment"
                                                                     {:time (current-time) :name (when-not (= hidden name) name)})))))
        preview-color! (fn preview-color []
                         (when pose-change!
                           (let [^Color color (.getValue picker)
                                 type (.getValue property)
                                 key (merge {:time (current-time) :interpolation (.getValue interpolation)}
                                            (case type
                                              "rgba2" {:color (color-text color 4) :dark-color (color-text (.getValue dark-picker) 3)}
                                              "rgb2" {:color (color-text color 3) :dark-color (color-text (.getValue dark-picker) 3)}
                                              "rgba" {:color (color-text color 4)}
                                              "rgb" {:color (color-text color 3)}
                                              "alpha" {:value (.getOpacity color)}))]
                             (pose-change! #(slots/set-keyframe % animation-id slot-id type key)))))
        move-order (fn move-order [delta]
                     (let [items (.getItems order-list)
                           index (.getSelectedIndex (.getSelectionModel order-list))
                           target (+ index delta)]
                       (when (and (<= 0 index) (<= 0 target) (< target (.size items)))
                         (let [value (.get items index)]
                           (.set items index (.get items target))
                           (.set items target value)
                           (.select (.getSelectionModel order-list) (int target))
                           (when pose-change!
                             (let [order (vec items)]
                               (pose-change! #(slots/set-draw-order-keyframe % animation-id (current-time) order))))))))]
    (.addListener (.valueProperty attachment)
                  (reify ChangeListener
                    (changed [_ _ _ _] (preview-attachment!))))
    (.setOnAction picker (ui/event-handler _ (preview-color!)))
    (.setOnAction dark-picker (ui/event-handler _ (preview-color!)))
    (update-dark! (.getValue property))
    (.addListener (.valueProperty property)
                  (reify ChangeListener
                    (changed [_ _ _ value] (update-dark! value))))
    (properties/translate-choice! property tr {"rgba" "color-rgba" "rgb" "color-rgb" "alpha" "color-alpha" "rgba2" "color-rgba2" "rgb2" "color-rgb2"})
    (properties/translate-choice! interpolation tr {"linear" "interpolation-linear" "stepped" "interpolation-stepped"})
    (.select (.getSelectionModel order-list) ^Object slot-id)
    (let [panels [(group "slot-animation" true
                         [(Label. (tr "attachment"))
                          attachment
                          (row [(action-button "slot-key-attachment-add" (tr "key")
                                               (fn key-attachment []
                                                 (let [name (.getValue attachment)]
                                                   (change! #(slots/set-keyframe % animation-id slot-id "attachment"
                                                                                 {:time (current-time) :name (when-not (= hidden name) name)})))))
                                (action-button "slot-key-attachment-delete" (tr "delete-key")
                                               (fn delete-attachment-key []
                                                 (change! #(slots/remove-keyframe % animation-id slot-id "attachment" (current-time)))))])])
                  (group "slot-color-animation" true
                         [(row [property picker])
                          dark-row
                          interpolation
                          (row [(action-button "slot-key-color-add" (tr "key")
                                               (fn key-color []
                                                 (let [^Color color (.getValue picker)
                                                       type (.getValue property)
                                                       key (merge {:time (current-time) :interpolation (.getValue interpolation)}
                                                                  (case type
                                                                    "rgba2" {:color (color-text color 4) :dark-color (color-text (.getValue dark-picker) 3)}
                                                                    "rgb2" {:color (color-text color 3) :dark-color (color-text (.getValue dark-picker) 3)}
                                                                    "rgba" {:color (color-text color 4)}
                                                                    "rgb" {:color (color-text color 3)}
                                                                    "alpha" {:value (.getOpacity color)}))]
                                                   (change! #(slots/set-keyframe % animation-id slot-id type key)))))
                                (action-button "slot-key-color-delete" (tr "delete-key")
                                               (fn delete-color-key []
                                                 (change! #(slots/remove-keyframe % animation-id slot-id (.getValue property) (current-time)))))])])
                  (group "draw-order" true
                         [order-list
                          (row [(action-button "slot-draw-order-up" (tr "move-up") (fn move-up [] (move-order -1)))
                                (action-button "slot-draw-order-down" (tr "move-down") (fn move-down [] (move-order 1)))])
                          (row [(action-button "slot-key-draw-order-add" (tr "key")
                                               (fn key-draw-order []
                                                 (let [order (vec (.getItems order-list))]
                                                   (change! #(slots/set-draw-order-keyframe % animation-id (current-time) order)))))
                                (action-button "slot-key-draw-order-delete" (tr "delete-key")
                                               (fn delete-draw-order-key []
                                                 (change! #(slots/remove-draw-order-keyframe % animation-id (current-time)))))])])]
          root (doto (VBox.) (-> .getChildren (.addAll ^java.util.Collection panels)))
          find-node (fn find-node [id] (.lookup root (str "#skeletal-" id)))
          recorded (fn recorded [property]
                     (let [doc (or key-document document)
                           tracks (if (= property "drawOrder") [(slots/draw-order-track doc animation-id)]
                                      (slots/tracks doc animation-id slot-id))]
                       (coll/any? (fn [track]
                                    (and (or (= property "drawOrder") (= property (:property track)))
                                         (coll/any? #(< (abs (- (double (:time %)) (double time))) 1.0e-6) (:keys track)))) tracks)))
          attachment-add (find-node "slot-key-attachment-add")
          attachment-delete (find-node "slot-key-attachment-delete")
          color-add (find-node "slot-key-color-add")
          color-delete (find-node "slot-key-color-delete")
          order-add (find-node "slot-key-draw-order-add")
          order-delete (find-node "slot-key-draw-order-delete")
          up (find-node "slot-draw-order-up")
          down (find-node "slot-draw-order-down")
          opacity (doto ^TextField (identify! (TextField. (format "%.0f%%" (* 100.0 (.getOpacity ^Color (.getValue picker))))) "slot-key-opacity")
                    (.setPrefColumnCount 4) (.setPrefWidth 70.0) (.setMinWidth 44.0))
          color-content (find-node "properties-slot-color-animation-content")
          order-content (find-node "properties-draw-order-content")
          ^HBox color-row (row [property picker opacity color-add color-delete])
          refresh-marker! (fn refresh-marker [type]
                            (properties/key-button! color-add (recorded type) color-delete)
                            (.remove (.getStyleClass color-row) "inspector-keyed-row")
                            (when (recorded type) (.add (.getStyleClass color-row) "inspector-keyed-row")))]
      (properties/key-button! attachment-add (recorded "attachment") attachment-delete)
      (refresh-marker! (.getValue property))
      (properties/key-button! order-add (recorded "drawOrder") order-delete)
      (.addListener (.valueProperty property)
                    (reify ChangeListener
                      (changed [_ _ _ value] (refresh-marker! value))))
      (.setOnAction opacity
                    (ui/event-handler _
                      (let [value (try (Double/parseDouble (string/replace (.getText opacity) "%" ""))
                                       (catch NumberFormatException _ Double/NaN))
                            ^Color color (.getValue picker)]
                        (if (and (Double/isFinite value) (<= 0.0 value 100.0))
                          (do (.setValue picker (Color/color (.getRed color) (.getGreen color) (.getBlue color) (/ value 100.0)))
                              (preview-color!))
                          (.setText opacity (format "%.0f%%" (* 100.0 (.getOpacity color))))))))
      (.addListener (.valueProperty picker)
                    (reify ChangeListener
                      (changed [_ _ _ color] (.setText opacity (format "%.0f%%" (* 100.0 (.getOpacity ^Color color)))))))
      (doseq [[^Button control glyph] [[up "↑"] [down "↓"]]]
        (.setTooltip control (Tooltip. (.getText control)))
        (.setAccessibleText control (.getText control))
        (.setText control glyph))
      (.setMinWidth property 0.0)
      (.setPrefWidth property 120.0)
      (.setMinWidth picker 40.0)
      (.setPrefWidth picker 44.0)
      (.setMaxWidth picker 44.0)
      (.setStyle picker "-fx-color-label-visible: false;")
      (.setAll (.getChildren ^VBox color-content) ^"[Ljavafx.scene.Node;" (into-array Node
                                                                                      [color-row dark-row
                                                                                       (properties/field-row (tr "interpolation") [interpolation])]))
      (.setAll (.getChildren ^VBox order-content) ^"[Ljavafx.scene.Node;" (into-array Node
                                                                                      [(row [up down order-add order-delete]) order-list]))
      [(properties/field-row (tr "attachment") [attachment attachment-add attachment-delete])
       (second panels) (nth panels 2)
       (doto (Label. (tr "inspector-key-legend")) (.setWrapText true)
             (-> .getStyleClass (.add "inspector-key-legend")))])))

(defn- management-inspector-impl
  "Setup attachment authoring and mode-specific sequence controls."
  [{:keys [document animation-id slot-id time current-time tr change! pose-change! sections mode selected-name selected-skin]}]
  (let [group (fn group [id expanded nodes] (properties/section (merge {:tr tr} sections) id expanded nodes))
        animated (= "animate" mode)
        slot (coll/first-where #(= slot-id (:id %)) (:slots (if animated (slots/sample-slots document animation-id time) document)))
        skin (or selected-skin (if animated (or (first (:preview-attachment-key slot)) (:active-skin document "default")) (:active-skin document "default")))
        name (or selected-name (if animated (or (second (:preview-attachment-key slot)) (get-in slot [:attachment :name] slot-id)) (get-in slot [:attachment :name] slot-id)))
        entries (attachments/entries document skin slot-id)
        raw (:source (coll/first-where #(= name (:name %)) entries))
        current-time (or current-time (fn current-playhead-time [] time))
        text (fn field [id value] (identify! (TextField. (str value)) id))
        ^TextField name-field (text "attachment-entry-name" name)
        ^TextField path-field (text "attachment-entry-path" (get raw "path" (get-in slot [:attachment :path] "")))
        ^ComboBox picker (doto ^ComboBox (identify! (ComboBox. (FXCollections/observableArrayList ^java.util.Collection (mapv :name entries))) "attachment-entry")
                           (.setValue name))
        ^ComboBox type-field (doto ^ComboBox (identify! (ComboBox. (FXCollections/observableArrayList ^java.util.Collection attachments/types)) "attachment-entry-type")
                               (.setValue (get raw "type" (get-in slot [:attachment :type] "region"))))
        ^TextField parent-field (text "attachment-parent" (get raw "parent" ""))
        ^TextField parent-skin (text "attachment-parent-skin" (get raw "skin" skin))
        ^CheckBox inherit-deform (doto ^CheckBox (identify! (CheckBox. (tr "attachment-inherit-deform")) "attachment-inherit-deform")
                                   (.setSelected (get raw "timelines" (get raw "deform" true))))
        ^ComboBox end-field (doto ^ComboBox (identify! (ComboBox. (FXCollections/observableArrayList ^java.util.Collection (mapv :id (:slots document)))) "attachment-end")
                              (.setValue (get raw "end")))
        numeric (fn numeric-field [id value] (doto ^TextField (text id value) (.setPrefColumnCount 4)))
        x (numeric "attachment-edit-x" (get raw "x" 0.0))
        y (numeric "attachment-edit-y" (get raw "y" 0.0))
        rotation (numeric "attachment-edit-rotation" (get raw "rotation" 0.0))
        width (numeric "attachment-edit-width" (get raw "width" 100.0))
        height (numeric "attachment-edit-height" (get raw "height" 100.0))
        vertices (doto ^TextArea (identify! (TextArea. (coll/join-to-string ", " (get raw "vertices" [-50 -50 50 -50 50 50 -50 50]))) "attachment-vertices")
                   (.setPrefRowCount 3) (.setWrapText true))
        number (fn parse-number [^TextField field] (Double/parseDouble (.getText field)))
        sequence-value (sequence/decode (get raw "sequence"))
        count-field (numeric "sequence-count" (:count sequence-value 0))
        start-field (numeric "sequence-start" (:start sequence-value 1))
        digits-field (numeric "sequence-digits" (:digits sequence-value 0))
        setup-field (numeric "sequence-setup" (:setup-index sequence-value 0))
        ^ComboBox mode-field (doto ^ComboBox (identify! (ComboBox. (FXCollections/observableArrayList ^java.util.Collection sequence/modes)) "sequence-mode")
                               (.setValue "loop"))
        index-field (numeric "sequence-index" (get-in slot [:attachment :sequence-index] 0))
        delay-field (numeric "sequence-delay" 0.1)
        preview-sequence! (fn preview-sequence []
                            (when (and animated sequence-value pose-change!)
                              (pose-change! #(attachments/set-sequence-keyframe % animation-id skin slot-id name
                                                                                {:time (current-time) :index (long (number index-field))
                                                                                 :mode (.getValue mode-field) :delay (number delay-field)}))))
        linked-controls (row [parent-field parent-skin inherit-deform])
        linked-label (Label. (tr "attachment-parent"))
        end-label (Label. (tr "attachment-end"))
        raw-controls (group "raw-attachment-data" false [(Label. (tr "attachment-vertices")) vertices])
        update-type! (fn update-type-controls [type]
                       (doseq [[node visible] [[linked-label (= "linkedmesh" type)]
                                               [linked-controls (= "linkedmesh" type)]
                                               [end-label (= "clipping" type)]
                                               [end-field (= "clipping" type)]
                                               [raw-controls (contains? #{"mesh" "path" "boundingbox" "clipping"} type)]]]
                         (.setVisible ^Node node visible)
                         (.setManaged ^Node node visible)))
        ^ComboBox blend-field (doto ^ComboBox (identify! (ComboBox. (FXCollections/observableArrayList ^objects (into-array String ["normal" "additive" "multiply" "screen"]))) "slot-blend")
                                (.setValue (:blend slot "normal")))]
    (.setOnAction mode-field (ui/event-handler _ (preview-sequence!)))
    (.setOnAction ^TextField index-field (ui/event-handler _ (preview-sequence!)))
    (.setOnAction ^TextField delay-field (ui/event-handler _ (preview-sequence!)))
    (properties/translate-choice! type-field tr {"region" "type-region" "mesh" "type-mesh" "linkedmesh" "type-linkedmesh"
                                                 "clipping" "type-clipping" "boundingbox" "type-boundingbox" "path" "type-path" "point" "type-point"})
    (properties/translate-choice! blend-field tr {"normal" "blend-normal" "additive" "blend-additive" "multiply" "blend-multiply" "screen" "blend-screen"})
    (update-type! (.getValue type-field))
    (.addListener (.valueProperty type-field)
                  (reify ChangeListener
                    (changed [_ _ _ value] (update-type! value))))
    (coll/into->
      [(when-not animated
         (group "slot-blend" true [(properties/field-row "" [blend-field
                                                             (action-button "slot-blend-apply" (tr "apply")
                                                                            (fn set-blend [] (change! #(data/update-slot % slot-id {:blend (.getValue blend-field)}))))])]))
       (when-not animated
         (group "attachment-manager" false
                [picker
                 (action-button "attachment-use" (tr "attachment-use")
                                (fn select-entry []
                                  (change! #(attachments/select-setup % slot-id (.getValue picker)))))
                 (doto ^Button (action-button "attachment-restore-image" (tr "restore-image")
                                              (fn restore-entry [] (change! #(attachments/restore-image % skin slot-id name))))
                   (.setVisible (contains? #{"mesh" "linkedmesh"} (get raw "type")))
                   (.setManaged (contains? #{"mesh" "linkedmesh"} (get raw "type"))))
                 (Label. (tr "attachment-name")) name-field
                 (Label. (tr "attachment-type")) type-field
                 (Label. (tr "image")) path-field
                 (row [(Label. "X") x (Label. "Y") y])
                 (row [(Label. (tr "rotation")) rotation])
                 (row [(Label. (tr "width")) width (Label. (tr "height")) height])
                 linked-label linked-controls
                 end-label end-field
                 raw-controls
                 (action-button "attachment-save" (tr "attachment-save")
                                (fn save-entry []
                                  (change!
                                    (fn [document]
                                      (let [type (.getValue type-field)
                                            values (when (contains? #{"mesh" "path" "boundingbox" "clipping"} type)
                                                     (mapv #(Double/parseDouble %) (coll/into-> (string/split (.getText vertices) #"[\s,]+") [] (remove string/blank?))))
                                            base {"type" type "x" (number x) "y" (number y) "rotation" (number rotation)
                                                  "width" (number width) "height" (number height)}
                                            changes (case type
                                                      "point" base
                                                      ("clipping" "boundingbox") (cond-> (assoc base "vertices" values "vertexCount" (get raw "vertexCount" (quot (count values) 2)))
                                                                                   (= "clipping" type) (assoc "end" (.getValue end-field)))
                                                      "linkedmesh" (assoc base "path" (.getText path-field) "parent" (.getText parent-field)
                                                                          "skin" (.getText parent-skin) "timelines" (.isSelected inherit-deform))
                                                      "path" (assoc base "vertices" (if (= "path" (get raw "type")) values [-40 0 0 0 40 90 100 90 140 0 180 0])
                                                                    "vertexCount" (if (= "path" (get raw "type")) (get raw "vertexCount" (quot (count values) 2)) 6)
                                                                    "closed" (get raw "closed" false) "constantSpeed" (get raw "constantSpeed" true) "lengths" (get raw "lengths" [200]))
                                                      "mesh" (merge base {"path" (.getText path-field) "vertices" values
                                                                          "uvs" (get raw "uvs" [0 1 1 1 1 0 0 0]) "triangles" (get raw "triangles" [0 1 2 0 2 3])})
                                                      (assoc base "path" (.getText path-field)))]
                                        (if (and (= "region" type) (contains? #{"mesh" "linkedmesh"} (get raw "type")) (= name (.getText name-field)))
                                          (attachments/restore-image document skin slot-id name)
                                          (attachments/put document skin slot-id (.getText name-field) changes)))))))
                 (row [(action-button "attachment-delete" (tr "delete")
                                      (fn delete-entry [] (change! #(attachments/remove-entry % skin slot-id (.getValue picker)))))
                       (action-button "attachment-rename-placeholder" (tr "rename-placeholder")
                                      (fn rename-entry [] (change! #(attachments/rename-placeholder % slot-id name (.getText name-field)))))])]))
       (when (or (not animated) (pos? (:count sequence-value 0)))
         (group "sequence" false
                (coll/into->
                  [(when-not animated (row [(Label. (tr "sequence-count")) count-field (Label. (tr "sequence-start")) start-field]))
                   (when-not animated (row [(Label. (tr "sequence-digits")) digits-field (Label. (tr "sequence-setup")) setup-field]))
                   (when-not animated (action-button "sequence-apply" (tr "sequence-apply")
                                                     (fn update-sequence []
                                                       (change! (fn [document]
                                                                  (attachments/put document skin slot-id name
                                                                                   {"sequence" (when (pos? (number count-field))
                                                                                                 {"count" (long (number count-field)) "start" (long (number start-field))
                                                                                                  "digits" (long (number digits-field)) "setup" (long (number setup-field))})}))))))
                   (when (not= "setup" mode) mode-field)
                   (when (not= "setup" mode) (row [(Label. (tr "sequence-index")) index-field (Label. (tr "sequence-delay")) delay-field]))
                   (when (not= "setup" mode) (row [(action-button "sequence-key" (tr "key")
                                                                  (fn key-sequence []
                                                                    (change! #(attachments/set-sequence-keyframe % animation-id skin slot-id name
                                                                                                                 {:time (current-time) :index (long (number index-field))
                                                                                                                  :mode (.getValue mode-field) :delay (number delay-field)}))))
                                                   (action-button "sequence-delete-key" (tr "delete-key")
                                                                  (fn delete-sequence-key []
                                                                    (change! #(attachments/remove-sequence-keyframe % animation-id skin slot-id name (current-time)))))]))] [] (remove nil?))))] [] (remove nil?))))

(defn management-inspector
  "Skip setup-only controls and source export when Animate has no sequence."
  [{:keys [document animation-id slot-id time mode] :as context}]
  (if (not= "animate" mode)
    (management-inspector-impl context)
    (let [slot (coll/first-where #(= slot-id (:id %)) (:slots (slots/sample-slots document animation-id time)))]
      (when-not slot (throw (ex-info "Unknown animation slot." {:slot slot-id})))
      (if (pos? (get-in slot [:attachment :sequence :count] 0))
        (management-inspector-impl context)
        []))))

(defn skin-inspector
  "Skeleton-wide skin controls, separate from per-attachment properties."
  [{:keys [document tr change! mode]}]
  (let [skin (:active-skin document "default")
        ^ComboBox active (doto ^ComboBox (identify! (ComboBox. (FXCollections/observableArrayList ^java.util.Collection (data/skin-names document))) "active-skin")
                           (.setValue skin) (.setMaxWidth Double/MAX_VALUE))
        ^TextField name (doto ^TextField (identify! (TextField.) "skin-new-name")
                          (.setPromptText (tr "skin-new-name")))
        ^ListView sources (doto ^ListView (identify! (ListView. (FXCollections/observableArrayList ^java.util.Collection (data/skin-names document))) "skin-combination-sources")
                            (.setPrefHeight 120.0))
        delete (action-button "skin-delete" (tr "skin-delete") (fn delete-skin [] (change! #(attachments/remove-skin % skin))))
        edit-controls [(row [(action-button "skin-new" (tr "skin-new") (fn new-skin [] (change! #(attachments/create-skin % (.getText name)))))
                             (action-button "copy-skin" (tr "copy-skin") (fn duplicate-skin [] (change! #(data/duplicate-skin % (.getText name)))))])
                       delete
                       (properties/section {:tr tr} "skin-compose" false
                                           [sources
                                            (action-button "skin-compose" (tr "skin-compose")
                                                           (fn compose-skins []
                                                             (change! #(attachments/compose-skin % (.getText name) (vec (.getSelectedItems (.getSelectionModel sources)))))))])]]
    (.setSelectionMode (.getSelectionModel sources) SelectionMode/MULTIPLE)
    (.select (.getSelectionModel sources) ^Object skin)
    (.addListener (.valueProperty active)
                  (reify ChangeListener
                    (changed [_ _ old value]
                      (when (and value (not= old value))
                        (change! #(data/select-skin % value))))))
    (doseq [^Node control edit-controls] (.setDisable control (= "animate" mode)))
    (.setDisable ^Button delete (or (= "animate" mode) (= "default" skin) (<= (count (data/skin-names document)) 1)))
    (into [(Label. (tr "active-skin")) active name] edit-controls)))
