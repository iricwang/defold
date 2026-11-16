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

(ns editor.skeletal-animation-properties
  "Collapsible inspector groups. Expansion is UI state, never document data."
  (:require [editor.ui :as ui]
            [util.coll :as coll])
  (:import [javafx.beans InvalidationListener]
           [javafx.beans.value ChangeListener]
           [javafx.geometry Insets]
           [javafx.scene Node]
           [javafx.scene.control Button ComboBox ContextMenu ListCell MenuItem Tooltip]
           [javafx.scene.control.skin ComboBoxListViewSkin]
           [javafx.scene.layout ColumnConstraints GridPane HBox Priority Region VBox]
           [javafx.scene.paint Color]
           [javafx.scene.shape SVGPath]
           [javafx.scene.text Text]
           [javafx.util StringConverter]))

(set! *warn-on-reflection* true)

(defn section [{:keys [state scope tr]} id expanded nodes]
  (let [path [:property-sections scope id]
        expanded (get-in (when state @state) path expanded)
        content (doto (VBox. 6.0)
                  (.setId (str "skeletal-properties-" id "-content"))
                  (.setPadding (Insets. 6.0 4.0 8.0 4.0))
                  (.setVisible expanded)
                  (.setManaged expanded))
        title (tr id)
        heading (doto (Button. (str (if expanded "▾  " "▸  ") title))
                  (.setId (str "skeletal-properties-" id))
                  (.setAccessibleText title)
                  (-> .getStyleClass (.add "inspector-section-heading"))
                  (.setMaxWidth Double/MAX_VALUE)
                  (.setFocusTraversable false)
                  (.setStyle "-fx-alignment: center-left; -fx-padding: 5 6; -fx-font-weight: bold;"))]
    (.addAll (.getChildren content) ^java.util.Collection nodes)
    (.setOnAction heading
                  (ui/event-handler _
                    (let [expanded (not (.isManaged content))]
                      (.setVisible content expanded)
                      (.setManaged content expanded)
                      (.setText heading (str (if expanded "▾  " "▸  ") title))
                      (when state (swap! state assoc-in path expanded)))))
    (let [box (doto (VBox. 2.0) (.setId (str "skeletal-properties-" id "-section")))]
      (.add (.getChildren box) heading)
      (.add (.getChildren box) content)
      box)))

(defn translate-choice! [^ComboBox choice tr labels]
  (.setConverter choice
                 (proxy [StringConverter] []
                   (toString [value] (if-let [key (get labels value)] (tr key) (str value)))
                   (fromString [text] text)))
  choice)

(defn fit-choice-text! [^ComboBox choice]
  (let [display-text (fn display-text [value]
                       (if-some [converter (.getConverter choice)]
                         (.toString ^StringConverter converter value)
                         (str value)))
        cell (proxy [ListCell] []
               (updateItem [item blank]
                 (proxy-super updateItem item blank)
                 (.setText ^ListCell this (if blank "" (display-text item)))))
        measure (Text.)
        resize (fn resize-choice []
                 (.setFont measure (.getFont cell))
                 (let [width (+ 52.0 (reduce (fn [width value]
                                               (.setText measure (display-text value))
                                               (max width (.getWidth (.getLayoutBounds measure))))
                                             0.0 (conj (vec (.getItems choice)) (.getValue choice))))]
                   (.setMinWidth choice (if (.get (.getProperties choice) ::flex-width) 0.0 width))
                   (.setPrefWidth choice width)
                   (.setMaxWidth choice Double/MAX_VALUE)
                   (when (instance? ComboBoxListViewSkin (.getSkin choice))
                     (let [^Region popup (.getPopupContent ^ComboBoxListViewSkin (.getSkin choice))]
                       (.setMinWidth popup width)
                       (.setPrefWidth popup (max width (.getWidth choice)))))))
        listener (reify InvalidationListener
                   (invalidated [_ _] (resize)))]
    (.setButtonCell choice cell)
    (doseq [observable [(.valueProperty choice) (.converterProperty choice)
                        (.fontProperty cell) (.skinProperty choice) (.showingProperty choice)]]
      (.addListener ^javafx.beans.Observable observable listener))
    (.addListener (.getItems choice) listener)
    (.addListener (.itemsProperty choice)
                  (reify ChangeListener
                    (changed [_ _ before after]
                      (.removeListener ^javafx.collections.ObservableList before listener)
                      (.addListener ^javafx.collections.ObservableList after listener)
                      (resize))))
    (resize)
    choice))

(defn field-row [title controls]
  (let [label (doto (javafx.scene.control.Label. title)
                (.setMinWidth 68.0) (.setPrefWidth 84.0) (.setMaxWidth 104.0) (.setWrapText true))
        row (doto (HBox. 8.0) (.setAlignment javafx.geometry.Pos/CENTER_LEFT))]
    (.add (.getStyleClass row) "inspector-field-row")
    (when (pos? (count title)) (.add (.getChildren row) label))
    (.addAll (.getChildren row) ^java.util.Collection controls)
    (when-let [control (first controls)]
      (when (instance? ComboBox control)
        (.put (.getProperties ^ComboBox control) ::flex-width true))
      (when (instance? Region control)
        (.setMinWidth ^Region control 0.0)
        (.setMaxWidth ^Region control Double/MAX_VALUE)
        (HBox/setHgrow control Priority/ALWAYS)))
    row))

(defn key-button! [^Button button recorded delete-button]
  (let [diamond (doto (SVGPath.)
                  (.setContent "M7,0 L14,7 L7,14 L0,7 Z")
                  (.setStroke (Color/web (if recorded "#81bdf1" "#bfc3c8")))
                  (.setStrokeWidth 1.5)
                  (.setFill (if recorded (Color/web "#81bdf1") Color/TRANSPARENT)))
        title (or (coll/not-empty (.getText button)) (.getAccessibleText button))]
    (.setText button "")
    (.setGraphic button diamond)
    (.setTooltip button (Tooltip. title))
    (.setAccessibleText button title)
    (when-not (.contains (.getStyleClass button) "inspector-key")
      (.add (.getStyleClass button) "inspector-key"))
    (.put (.getProperties button) :recorded (boolean recorded))
    (when delete-button
      (let [item (MenuItem. (.getText ^Button delete-button))
            menu (ContextMenu.)]
        (.setOnAction item (ui/event-handler _ (.fire ^Button delete-button)))
        (.add (.getItems menu) item)
        (.setContextMenu button menu)
        (.setVisible ^Node delete-button false)
        (.setManaged ^Node delete-button false)))
    button))

(defn axis-fields [fields]
  (let [row (doto (HBox. 6.0) (.setMinWidth 0.0))]
    (doseq [[axis ^Region control] fields]
      (let [label (doto (javafx.scene.control.Label. axis) (.setMinWidth Region/USE_PREF_SIZE))
            pair (doto (HBox. 4.0) (.setMinWidth 0.0) (.setAlignment javafx.geometry.Pos/CENTER_LEFT))]
        (.add (.getStyleClass control) "inspector-axis-number")
        (.setMinWidth control 0.0) (.setPrefWidth control 60.0) (.setMaxWidth control Double/MAX_VALUE)
        (HBox/setHgrow control Priority/ALWAYS)
        (HBox/setHgrow pair Priority/ALWAYS)
        (.add (.getChildren pair) label)
        (.add (.getChildren pair) control)
        (.add (.getChildren row) pair)))
    row))

(defn action-grid [buttons]
  (let [grid (doto (GridPane.) (.setHgap 6.0) (.setVgap 6.0) (.setMinWidth 0.0))]
    (dotimes [_ 2]
      (.add (.getColumnConstraints grid) (doto (ColumnConstraints.) (.setPercentWidth 50.0))))
    (doseq [[index ^Button button] (mapv vector (range (count buttons)) buttons)]
      (.setMinWidth button 0.0) (.setMaxWidth button Double/MAX_VALUE)
      (.setWrapText button true)
      (.add (.getStyleClass button) "inspector-tool-action")
      (.add grid button (mod index 2) (quot index 2)))
    grid))
