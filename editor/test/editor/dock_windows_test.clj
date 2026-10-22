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

(ns editor.dock-windows-test
  (:require [clojure.test :refer [deftest is testing]]
            [editor.dock-windows :as dock]
            [editor.docking :as docking]
            [editor.localization :as localization]
            [editor.prefs :as prefs]
            [editor.ui :as ui]
            [integration.test-util :as test-util])
  (:import [javafx.scene Scene]
           [javafx.scene.control SplitPane]
           [javafx.scene.layout StackPane]))

;; Resource title changes follow the floating tab and native window without rebuilding or moving content.
(deftest floating-resource-title-test
  (ui/run-now
    (let [preferences (test-util/make-test-prefs)
          localize (localization/make-editor preferences #(throw %))
          slot (StackPane. (into-array javafx.scene.Node [(javafx.scene.control.Label. "Preview")]))
          root (doto (StackPane. (into-array javafx.scene.Node [slot])) (.setId "workbench"))
          scene (Scene. root 600.0 400.0)
          owner (doto (javafx.stage.Stage.) (.setScene scene) (.setX -10000.0) (.setY -10000.0))
          title (javafx.beans.property.SimpleStringProperty. "Game")
          previous-main-stage (ui/main-stage)]
      (ui/user-data! scene :localization localize)
      (try
        (.show owner)
        (ui/set-main-stage owner)
        (dock/register! slot "Game" {:title-property title})
        (dock/float-slot! slot -10000.0 -10000.0)
        (let [manager (ui/user-data scene :editor.dock-windows/manager)
              window (val (first @(:windows manager)))
              stage ^javafx.stage.Stage (:stage window)
              group ^javafx.scene.control.TabPane (.getCenter ^javafx.scene.layout.BorderPane (:host window))
              label ^javafx.scene.control.Label (.getGraphic (.get (.getTabs group) 0))]
          (.set title "Game · screen.gui")
          (is (= "Game · screen.gui" (.getTitle stage)))
          (is (= "Game · screen.gui" (.getText label)))
          (.set title "Game")
          (is (= "Game" (.getTitle stage)))
          (is (= "Game" (.getText label))))
        (finally (dock/restore-all! scene) (.hide owner) (ui/set-main-stage previous-main-stage))))))

;; Moving panels must neither duplicate them nor discard other nested panels.
(deftest nested-layout-test
  (doseq [edge [:left :right :top :bottom :tab]]
    (testing (name edge)
      (let [tree (dock/insert-panel {:tabs [:scene :inspector]} :animation :scene edge)]
        (is (= #{:scene :inspector :animation} (set (dock/panel-ids tree))))
        (is (= 3 (count (dock/panel-ids tree))))
        (is (= {:tabs [:scene :inspector]} (dock/remove-panel tree :animation)))
        (is (= #{:inspector :animation} (set (dock/panel-ids (dock/remove-panel tree :scene)))))
        (is (nil? (reduce dock/remove-panel tree [:animation :scene :inspector])))))))

;; Removing the last panel from a split collapses empty ancestors.
(deftest collapse-nested-split-test
  (let [tree (-> {:tabs [:scene]}
                 (dock/insert-panel :animation :scene :bottom)
                 (dock/insert-panel :inspector :scene :right))]
    (is (= :vertical (:axis tree)))
    (is (= :horizontal (:axis (first (:children tree)))))
    (is (= {:tabs [:animation]} (-> tree (dock/remove-panel :scene) (dock/remove-panel :inspector))))
    (is (= tree (dock/remove-panel tree :missing)))))

;; Drops distinguish tab grouping and all four split directions.
(deftest drop-target-test
  (is (= :tab (dock/drop-edge 1000.0 600.0 500.0 300.0)))
  (is (= :left (dock/drop-edge 1000.0 600.0 10.0 300.0)))
  (is (= :right (dock/drop-edge 1000.0 600.0 990.0 300.0)))
  (is (= :top (dock/drop-edge 1000.0 600.0 500.0 10.0)))
  (is (= :bottom (dock/drop-edge 1000.0 600.0 500.0 590.0))))

;; Floating siblings releases their actual split items and restores their original order in either return order.
(deftest home-space-test
  (ui/run-now
    (doseq [reverse-return [false true]]
      (let [left (StackPane.)
            right (StackPane.)
            split (SplitPane.)
            root (StackPane.)]
        (.addAll (.getItems split) ^java.util.Collection [left right])
        (.add (.getChildren root) split)
        (Scene. root)
        (.applyCss root)
        (.layout root)
        (.setDividerPositions split (double-array [0.35]))
        (dock/set-home-visible! left false)
        (is (= [right] (vec (.getItems split))))
        (dock/set-home-visible! right false)
        (is (zero? (count (.getItems split))))
        (dock/set-home-visible! (if reverse-return right left) true)
        (is (= 1 (count (.getItems split))))
        (dock/set-home-visible! (if reverse-return left right) true)
        (is (= [left right] (vec (.getItems split))))
        (is (< (Math/abs (- 0.35 (first (.getDividerPositions split)))) 0.001))))))

;; Returning one home must not resurrect another panel hidden through its own docking controls.
(deftest independently-hidden-home-test
  (ui/run-now
    (let [editor (StackPane.)
          game (StackPane.)
          split (SplitPane.)
          root (StackPane.)]
      (.addAll (.getItems split) ^java.util.Collection [editor game])
      (.add (.getChildren root) split)
      (Scene. root)
      (.applyCss root)
      (.layout root)
      (dock/set-home-visible! editor false)
      (.remove (.getItems split) game)
      (dock/set-home-visible! editor true)
      (is (= [editor] (vec (.getItems split)))))))

;; Saved tab groups retain selection and nested divider ratios when session ids change.
(deftest persistent-tree-roundtrip-test
  (let [tree {:axis :vertical :dividers [0.63]
              :children [{:tabs [:scene :inspector] :selected :inspector}
                         {:tabs [:animation] :selected :animation}]}
        identities {:scene [:gui "/main/main.gui" "dock.gui-scene"]
                    :animation [:gui "/main/main.gui" "dock.gui-animation"]
                    :inspector [:panel :inspector]}
        saved (dock/remap-tree tree identities)
        layout {:version 1 :windows [{:tree saved :bounds {:x -1600.0 :y 20.0 :width 1200.0 :height 800.0} :maximized false}]}]
    (is (= layout (dock/read-layout (pr-str layout))))
    (is (= tree (dock/remap-tree saved (into {} (map (fn [[id key]] [key id])) identities))))))

;; Missing or renamed resources cannot block recovery of the remaining panels.
(deftest missing-panel-recovery-test
  (let [tree {:axis :vertical :dividers [0.5]
              :children [{:tabs [[:panel :inspector]] :selected [:panel :inspector]}
                         {:tabs [[:gui "/removed.gui" "dock.gui-animation"]]}]}]
    (is (= {:tabs [:live] :selected :live} (dock/remap-tree tree {[:panel :inspector] :live})))
    (is (nil? (dock/remap-tree tree {})))))

;; Corrupt, future-version and duplicate-panel snapshots must fall back to normal docking.
(deftest invalid-persistent-layout-test
  (let [window {:tree {:tabs [[:panel :assets]]} :bounds {:x 0 :y 0 :width 900 :height 600}}]
    (doseq [text ["" "{" "#=(System/exit 0)" "{:version 2 :windows []}"
                  (pr-str {:version 1 :windows [window window]})
                  (pr-str {:version 1 :windows [(assoc-in window [:bounds :x] Double/NaN)]})
                  (pr-str {:version 1 :windows [(assoc-in window [:bounds :height] -1)]})]]
      (is (nil? (dock/read-layout text))))))

;; Negative monitor coordinates stay intact, and removed monitors never strand windows off-screen.
(deftest monitor-recovery-test
  (let [primary {:x 0.0 :y 0.0 :width 1920.0 :height 1040.0}
        secondary {:x -1920.0 :y -200.0 :width 1920.0 :height 1080.0}
        bounds {:x -1800.0 :y -100.0 :width 1200.0 :height 800.0}]
    (is (= bounds (dock/fit-window-bounds bounds [primary secondary])))
    (is (= (assoc bounds :x 0.0 :y 0.0) (dock/fit-window-bounds bounds [primary])))
    (is (= primary (dock/fit-window-bounds {:x 5000 :y 900 :width 4000 :height 3000} [primary])))))

;; Floating arrangements are project scoped even when two projects share global editor preferences.
(deftest project-layout-isolation-test
  (let [directory (.toFile (java.nio.file.Files/createTempDirectory "dock-prefs" (make-array java.nio.file.attribute.FileAttribute 0)))
        global (java.io.File. directory "global.edn")
        first-project (prefs/make :schemas [:default] :scopes {:global global :project (java.io.File. directory "first.edn")})
        second-project (prefs/make :schemas [:default] :scopes {:global global :project (java.io.File. directory "second.edn")})]
    (prefs/set! first-project [:window :floating-layout] "{:version 1 :windows []}")
    (is (= "" (prefs/get second-project [:window :floating-layout])))
    (is (= "{:version 1 :windows []}" (prefs/get first-project [:window :floating-layout])))))

;; Project-local floating sidebars must not be remembered as globally hidden panes.
(deftest floating-zone-isolation-test
  (ui/run-now
    (let [scene (Scene. (StackPane.))
          floating (atom #{:assets :inspector})]
      (ui/user-data! scene :editor.docking/manager
                     {:layout (atom {:left [:assets] :right [:inspector :preview]}) :floating floating})
      (is (docking/floating-zone? scene :left))
      (is (not (docking/floating-zone? scene :right)))
      (is (not (docking/floating-zone? scene :bottom)))
      (swap! floating conj :preview)
      (is (docking/floating-zone? scene :right)))))


;; Named layouts are project scoped, trim names, overwrite intentionally, and retain docked arrangements.
(deftest named-layout-library-test
  (ui/run-now
    (let [directory (.toFile (java.nio.file.Files/createTempDirectory "named-layout" (make-array java.nio.file.attribute.FileAttribute 0)))
          preferences (prefs/make :schemas [:default] :scopes {:project (java.io.File. directory "project.edn")})
          scene (Scene. (StackPane.))
          persistence (atom {:prefs preferences :restoring false :last-check 0})
          snapshot {:layout docking/default-layout :sizes {}}
          reset-count (atom 0)]
      (ui/user-data! scene :editor.dock-windows/manager
                     {:scene scene :panels (atom {}) :windows (atom {}) :drag (atom nil) :persistence persistence})
      (ui/user-data! scene :editor.docking/manager {:snapshot! (constantly snapshot) :reset! #(swap! reset-count inc)})
      (is (nil? (dock/save-named-layout! scene "  ")))
      (is (nil? (dock/save-named-layout! scene (apply str (repeat 81 "x")))))
      (is (true? (dock/save-named-layout! scene "  Dual Screen  ")))
      (is (= snapshot (:docked (dock/read-layout (get (prefs/get preferences [:window :named-layouts]) "Dual Screen")))))
      (is (true? (dock/save-named-layout! scene "Dual Screen")))
      (is (= 1 (count (prefs/get preferences [:window :named-layouts]))))
      (is (nil? (dock/apply-named-layout! scene "missing")))
      (dock/reset-default-layout! scene)
      (is (= 1 @reset-count))
      (is (= [] (:windows (dock/read-layout (prefs/get preferences [:window :floating-layout])))))
      (is (= 1 (count (prefs/get preferences [:window :named-layouts]))))
      (let [other-project (prefs/make :schemas [:default] :scopes {:project (java.io.File. directory "other.edn")})]
        (is (= {} (prefs/get other-project [:window :named-layouts]))))
      (ui/user-data! scene :localization (localization/make-editor preferences #(throw %)))
      (let [menu (dock/layout-menu scene)
            switch (second (.getItems menu))]
        (.handle (.getOnShowing menu) (javafx.event.Event. javafx.scene.control.Menu/ON_SHOWING))
        (is (= ["Dual Screen"] (mapv #(.getText ^javafx.scene.control.MenuItem %) (.getItems ^javafx.scene.control.Menu switch)))))
      (swap! persistence assoc :restoring true)
      (is (nil? (dock/save-named-layout! scene "During restoration"))))))
