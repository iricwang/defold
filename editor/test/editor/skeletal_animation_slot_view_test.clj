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

(ns editor.skeletal-animation-slot-view-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-attachments :as attachments]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-properties :as properties]
            [editor.skeletal-animation-slot-view :as slot-view]
            [editor.skeletal-animation-slots :as slots]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [javafx.scene.control Button ComboBox TextField]
           [javafx.scene.layout VBox]))

(defn- rig []
  (data/add-slot (data/new-document "slot-playhead")
                 {:id "face" :bone "root"
                  :attachment {:type "region" :name "face" :path "/face.png" :width 20.0 :height 20.0}}))

(defn- pane [document current-time]
  (let [root (VBox.)]
    (.addAll (.getChildren root) ^java.util.Collection
             (slot-view/inspector {:document @document :animation-id "default" :slot-id "face"
                                   :time 0.0 :current-time current-time :tr identity
                                   :change! (fn edit-document [f] (swap! document f))}))
    root))

(defn- fire! [^VBox pane id]
  (.fire ^Button (.lookup pane (str "#skeletal-" id))))

(defn- key-times [document property]
  (mapv :time (:keys (coll/first-where #(= property (:property %)) (slots/tracks document "default" "face")))))

;; A playing inspector is intentionally retained between frames. All six slot
;; key actions must read the live playhead instead of its construction time.
(deftest slot-inspector-live-playhead-test
  (ui/run-now
    (let [document (atom (rig))
          time (atom 0.0)
          pane (pane document (fn current-time [] @time))]
      (reset! time 0.75)
      (fire! pane "slot-key-attachment-add")
      (is (= [0.75] (key-times @document "attachment")))
      (reset! time 0.5)
      (fire! pane "slot-key-color-add")
      (is (= [0.5] (key-times @document "rgba")))
      (reset! time 0.6)
      (fire! pane "slot-key-draw-order-add")
      (is (= [0.6] (mapv :time (:keys (slots/draw-order-track @document "default")))))
      (reset! time 0.75)
      (fire! pane "slot-key-attachment-delete")
      (is (= [] (key-times @document "attachment")))
      (reset! time 0.5)
      (fire! pane "slot-key-color-delete")
      (is (= [] (key-times @document "rgba")))
      (reset! time 0.6)
      (fire! pane "slot-key-draw-order-delete")
      (is (= [] (:keys (slots/draw-order-track @document "default")))))))

;; Consumers without a clock callback keep the captured playhead behavior.
(deftest slot-inspector-static-playhead-test
  (ui/run-now
    (let [document (atom (rig))
          pane (pane document nil)]
      (fire! pane "slot-key-attachment-add")
      (is (= [0.0] (key-times @document "attachment"))))))

(defn- management-pane [document time]
  (let [root (VBox.)]
    (.addAll (.getChildren root) ^java.util.Collection
             (slot-view/management-inspector {:document @document :animation-id "default" :slot-id "face"
                                              :time 0.0 :current-time (fn current-time [] @time) :tr identity
                                              :change! (fn edit-document [f] (swap! document f))}))
    root))

(defn- skin-pane [document mode]
  (let [root (VBox.)]
    (.addAll (.getChildren root) ^java.util.Collection
             (slot-view/skin-inspector {:document @document :mode mode :tr identity
                                        :change! (fn edit-skin [f] (swap! document f))}))
    root))

(defn- set-text! [^VBox pane id text]
  (.setText ^TextField (.lookup pane (str "#skeletal-" id)) text))

;; Native controls create an alternate point, sequence keys at the live time,
;; and a combined skin without changing or discarding the setup image.
(deftest attachment-management-controls-test
  (ui/run-now
    (let [document (atom (rig))
          time (atom 0.0)
          pane (management-pane document time)]
      (set-text! pane "attachment-entry-name" "anchor")
      (.setValue ^ComboBox (.lookup pane "#skeletal-attachment-entry-type") "point")
      (set-text! pane "attachment-edit-x" "12")
      (fire! pane "attachment-save")
      (is (= ["anchor" "face"] (mapv :name (attachments/entries @document "default" "face"))))
      (is (= "face" (get-in @document [:slots 0 :attachment :name])))
      (let [pane (management-pane document time)]
        (set-text! pane "sequence-count" "3")
        (fire! pane "sequence-apply"))
      (let [pane (management-pane document time)]
        (reset! time 0.7)
        (fire! pane "sequence-key")
        (is (= [0.7] (mapv :time (attachments/sequence-keys @document "default" "default" "face" "face"))))
        (fire! pane "sequence-delete-key")
        (is (= [] (attachments/sequence-keys @document "default" "default" "face" "face")))
        (let [pane (skin-pane document "setup")]
          (set-text! pane "skin-new-name" "combined")
          (fire! pane "skin-compose"))
        (is (= "combined" (:active-skin @document)))))))

;; Refreshing or switching the selected attachment must preserve panel expansion
;; in UI state, without writing any folding preferences into the resource.
(deftest property-section-refresh-test
  (ui/run-now
    (let [state (atom {})
          options {:state state :scope {:mode "setup" :selected-kind :slot} :tr identity}
          first-pane (properties/section options "advanced" false [])]
      (is (false? (.isManaged (.lookup first-pane "#skeletal-properties-advanced-content"))))
      (.fire ^Button (.lookup first-pane "#skeletal-properties-advanced"))
      (let [refreshed (properties/section options "advanced" false [])
            animate (properties/section (assoc options :scope {:mode "animate" :selected-kind :slot}) "advanced" false [])]
        (is (.isManaged (.lookup refreshed "#skeletal-properties-advanced-content")))
        (is (false? (.isManaged (.lookup animate "#skeletal-properties-advanced-content"))))
        (is (= #{:property-sections} (set (coll/keys @state))))))))

;; Setup cannot insert sequence keys, Animate cannot overwrite setup attachment
;; definitions, and type changes expose only the matching attachment metadata.
(deftest contextual-attachment-properties-test
  (ui/run-now
    (let [document (atom (rig))
          make-pane (fn make-pane [mode]
                      (doto (VBox.)
                        (-> .getChildren (.addAll ^java.util.Collection
                                           (slot-view/management-inspector
                                             {:document @document :animation-id "default" :slot-id "face"
                                              :time 0.0 :mode mode :tr identity
                                              :change! (fn change [f] (swap! document f))})))))
          setup (make-pane "setup")
          animated (make-pane "animate")
          type-field (.lookup setup "#skeletal-attachment-entry-type")
          end-field (.lookup setup "#skeletal-attachment-end")
          raw (.lookup setup "#skeletal-properties-raw-attachment-data-section")]
      (is (nil? (.lookup setup "#skeletal-sequence-key")))
      (is (nil? (.lookup animated "#skeletal-attachment-save")))
      (is (nil? (.lookup animated "#skeletal-properties-sequence-section")))
      (is (false? (.isManaged end-field)))
      (is (false? (.isManaged raw)))
      (.setValue ^ComboBox type-field "clipping")
      (is (.isManaged end-field))
      (is (.isManaged raw))
      (.setValue ^ComboBox type-field "linkedmesh")
      (is (false? (.isManaged end-field)))
      (is (.isManaged (.getParent (.lookup setup "#skeletal-attachment-parent"))))
      (is (= (rig) @document)))))

;; Single-color tracks must not expose the dark tint. Switching track type
;; reveals it without changing the stored enum or inserting a keyframe.
(deftest contextual-color-properties-test
  (ui/run-now
    (let [document (atom (rig))
          pane (pane document nil)
          ^ComboBox property (.lookup pane "#skeletal-slot-key-color-type")
          dark-row (.getParent (.lookup pane "#skeletal-slot-key-dark-color"))]
      (is (false? (.isManaged dark-row)))
      (.setValue property "rgba2")
      (is (.isManaged dark-row))
      (is (= "rgba2" (.getValue property)))
      (is (= "color-rgba2" (.toString (.getConverter property) "rgba2")))
      (.setValue property "alpha")
      (is (false? (.isManaged dark-row)))
      (is (= (rig) @document)))))

;; The visible removal command acts on the chosen skin, not the list of compose
;; sources, and never offers deletion of the default fallback.
(deftest skin-removal-control-test
  (ui/run-now
    (let [document (atom (attachments/compose-skin (rig) "combined" ["default"]))
          pane (skin-pane document "setup")
          picker ^ComboBox (.lookup pane "#skeletal-active-skin")]
      (is (= ["default" "combined"] (vec (.getItems picker))))
      (is (= "combined" (.getValue picker)))
      (fire! pane "skin-delete")
      (is (= ["default"] (data/skin-names @document)))
      (is (= "default" (:active-skin @document)))
      (is (.isDisable (.lookup (skin-pane document "setup") "#skeletal-skin-delete"))))))

;; Skin management is available without selecting an attachment, and setup-only
;; operations cannot be invoked from the animation workflow.
(deftest independent-skin-workflow-test
  (ui/run-now
    (let [document (atom (rig))
          root (skin-pane document "setup")]
      (set-text! root "skin-new-name" "costume")
      (fire! root "skin-new")
      (is (= "costume" (:active-skin @document)))
      (is (= "/face.png" (get-in @document [:slots 0 :attachment :path])))
      (let [root (skin-pane document "setup")]
        (.setValue ^ComboBox (.lookup root "#skeletal-active-skin") "default")
        (is (= "default" (:active-skin @document))))
      (is (.isDisabled (.lookup (skin-pane document "animate") "#skeletal-skin-new")))
      (is (nil? (.lookup (management-pane document (atom 0.0)) "#skeletal-skin-compose"))))))

;; Animate with no image sequence has no management controls. In-progress UVs
;; need not be exportable merely to rebuild the property pane after an FFD edit.
(deftest animated-management-without-export-test
  (ui/run-now
    (let [doc (data/add-slot (data/new-document "editing")
                             {:id "mesh" :bone "root"
                              :attachment {:type "mesh" :path "/mesh.png" :width 10.0 :height 10.0
                                           :vertices [0.0 0.0 10.0 0.0 10.0 10.0 0.0 10.0]
                                           :uvs [0.0 0.0 1.0 1.0 1.0 0.0 0.0 1.0] :triangles [0 1 2 0 2 3]}})]
      (is (thrown? clojure.lang.ExceptionInfo (data/export-spine-json doc)))
      (is (= [] (slot-view/management-inspector {:document doc :animation-id "default" :slot-id "mesh" :time 0.0 :mode "animate" :tr identity})))
      (is (thrown? clojure.lang.ExceptionInfo (slot-view/management-inspector {:document doc :animation-id "default" :slot-id "missing" :time 0.0 :mode "animate" :tr identity})))
      nil)))

;; Key diamonds represent committed data, retain live-time actions and expose
;; removal through their context menu. Invalid alpha input cannot corrupt keys.
(deftest compact-slot-key-and-opacity-test
  (ui/run-now
    (let [document (atom (slots/set-keyframe (rig) "default" "face" "rgba" {:time 0.0 :color "ffffffff"}))
          root (pane document nil)
          key ^Button (.lookup root "#skeletal-slot-key-color-add")
          opacity ^TextField (.lookup root "#skeletal-slot-key-opacity")
          delete (.lookup root "#skeletal-slot-key-color-delete")]
      (is (true? (.get (.getProperties key) :recorded)))
      (is (= "" (.getText key)))
      (is (false? (.isManaged delete)))
      (.setText opacity "40%")
      (.fireEvent opacity (javafx.event.ActionEvent.))
      (.fire key)
      (is (= "ffffff66" (:color (first (:keys (first (slots/tracks @document "default" "face")))))))
      (doseq [value ["invalid" "NaN" "150" "-1"]]
        (.setText opacity value)
        (.fireEvent opacity (javafx.event.ActionEvent.))
        (is (= "40%" (.getText opacity))))
      (.fire ^javafx.scene.control.MenuItem (first (.getItems (.getContextMenu key))))
      (is (= [] (key-times @document "rgba"))))))

;; Unkeyed pose previews must not light a diamond. Changing color track type
;; updates the marker without losing its accessible label or delete command.
(deftest compact-key-baseline-test
  (ui/run-now
    (let [baseline (rig)
          preview (slots/set-keyframe baseline "default" "face" "rgba" {:time 0.0 :color "ffffffff"})
          root (doto (VBox.)
                 (-> .getChildren (.addAll ^java.util.Collection
                                    (slot-view/inspector {:document preview :key-document baseline :animation-id "default"
                                                          :slot-id "face" :time 0.0 :tr identity :change! identity}))))
          key ^Button (.lookup root "#skeletal-slot-key-color-add")
          property ^ComboBox (.lookup root "#skeletal-slot-key-color-type")]
      (is (false? (.get (.getProperties key) :recorded)))
      (.setValue property "alpha")
      (.setValue property "rgba")
      (is (= "key" (.getAccessibleText key)))
      (is (= 1 (count (filterv #{"inspector-key"} (vec (.getStyleClass key))))))
      (is (= 1 (count (.getItems (.getContextMenu key))))))))

;; Match the skeleton workspace palette and leave enough actual text area in
;; closed and open choices, including a narrow panel and a larger font.
(deftest inspector-theme-and-choice-layout-test
  (ui/run-now
    (doseq [width [260.0 400.0]
            font-size [13.0 16.0]]
      (let [root (doto (VBox.) (.setId "skeletal-workspace"))
            card (doto (pane (atom (rig)) nil)
                   (.setId "skeletal-inspector-card")
                   (-> .getStylesheets (.add (str (io/resource "skeletal-animation-inspector.css")))))
            scene (javafx.scene.Scene. root width 780.0)
            window (doto (javafx.stage.Stage.) (.setScene scene))]
        (.add (.getStylesheets scene) (str (io/resource "editor.css")))
        (.add (.getChildren root) card)
        (try
          (.show window)
          (.resize root width 780.0)
          (doseq [^ComboBox choice (.lookupAll card ".combo-box")]
            (.setStyle choice (str "-fx-font-size: " font-size ";")))
          (.applyCss root)
          (.layout root)
          (is (= (javafx.scene.paint.Color/web "#45464a")
                 (.getFill (first (.getFills (.getBackground card))))))
          (doseq [^ComboBox choice (.lookupAll card ".combo-box")]
            (let [^javafx.scene.control.ListCell cell (.lookup choice ".list-cell")
                  text (.lookup cell ".text")
                  insets (.getInsets cell)]
              (is (>= (.getHeight choice) 28.0))
              (is (<= (.getHeight (.getLayoutBounds text))
                      (+ 1.0 (- (.getHeight cell) (.getTop insets) (.getBottom insets))))
                  (str (.getId choice) " at " width " / " font-size))
              (.show choice)
              (let [popup (.getPopupContent ^javafx.scene.control.skin.ComboBoxListViewSkin (.getSkin choice))]
                (.applyCss popup)
                (.layout popup)
                (doseq [^javafx.scene.control.ListCell item (.lookupAll popup ".list-cell")
                        :when (not (.isEmpty item))]
                  (is (>= (.getHeight item) 28.0))
                  (is (<= (.getHeight (.getLayoutBounds (.lookup item ".text")))
                          (+ 1.0 (- (.getHeight item) (.getTop (.getInsets item)) (.getBottom (.getInsets item))))))))
              (.hide choice)))
          (finally (.hide window)))))))
