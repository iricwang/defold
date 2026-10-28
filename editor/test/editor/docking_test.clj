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

(ns editor.docking-test
  (:require [cljfx.api] ; Initialize JavaFX before loading AOT-compiled control classes.
            [cljfx.plorer :as plorer]
            [clojure.test :refer [deftest is]]
            [editor.dock-windows :as dock]
            [editor.docking :as docking]
            [editor.localization :as localization]
            [editor.prefs :as prefs]
            [editor.ui :as ui])
  (:import [com.defold.editor DockDrag DockDrag$Sample]
           [java.nio.file Files]
           [java.util.function BiConsumer Supplier]
           [javafx.geometry Point2D]
           [javafx.scene Node Scene]
           [javafx.scene.control Label MenuBar SplitPane Tab TabPane]
           [javafx.scene.layout AnchorPane HBox Priority StackPane VBox]
           [javafx.scene.shape Rectangle]
           [javafx.stage Stage]))

;; Rejects missing, duplicate and unknown panels in saved layouts before creating JavaFX nodes.
(deftest saved-layout-validation
  (is (docking/valid-layout? docking/default-layout))
  (doseq [layout [nil {} {:left [:assets] :right [:preview] :bottom [:tools] :center [:game]}
                  (update docking/default-layout :left conj :preview)
                  (assoc docking/default-layout :bottom [:unknown])]]
    (is (not (docking/valid-layout? layout)))))

;; Moving between and within zones keeps every panel exactly once, including when a zone becomes empty.
(deftest move-panel-between-zones
  (let [layout (-> docking/default-layout
                   (docking/move-panel :assets :right)
                   (docking/move-panel :preview :bottom))]
    (is (= {:left [] :right [:outline :inspector :assets] :bottom [:tools :preview] :center [:game]} layout))
    (is (docking/valid-layout? layout))
    (is (= layout (docking/move-panel layout :preview :bottom)))
    (is (= [:preview :tools] (:bottom (docking/move-panel layout :tools :bottom))))))

;; Dropping onto a panel halves only its share and preserves the other target-zone panels.
(deftest split-target-panel
  (let [sizes {:left {:assets 1.0} :right {:outline 0.25 :inspector 0.25 :preview 0.5} :bottom {:tools 1.0}}
        result (docking/split-panel docking/default-layout sizes :assets :right :inspector true)]
    (is (= {:left [] :right [:outline :assets :inspector :preview] :bottom [:tools] :center [:game]} (:layout result)))
    (is (= {:outline 0.25 :assets 0.125 :inspector 0.125 :preview 0.5} (get-in result [:sizes :right])))
    (is (docking/valid-layout? (:layout result)))
    (is (= [:outline :inspector :assets :preview]
           (get-in (docking/split-panel docking/default-layout sizes :assets :right :inspector false)
                   [:layout :right])))))

;; Reordering in the same zone neither duplicates a panel nor loses its available space.
(deftest split-within-zone
  (let [sizes {:left {:assets 1.0} :right {:outline 0.25 :inspector 0.25 :preview 0.5} :bottom {:tools 1.0}}
        result (docking/split-panel docking/default-layout sizes :preview :right :inspector true)]
    (is (= [:outline :preview :inspector] (get-in result [:layout :right])))
    (is (= {:outline 0.5 :preview 0.25 :inspector 0.25} (get-in result [:sizes :right])))
    (is (= {:layout docking/default-layout :sizes sizes}
           (docking/split-panel docking/default-layout sizes :preview :right :preview false)))))

;; The outline describes exactly one half of the hovered panel, for both docking orientations.
(deftest drop-outline-geometry
  (is (= {:before true :x 0.0 :y 0.0 :width 300.0 :height 200.0}
         (docking/drop-half 300.0 400.0 false 20.0 100.0)))
  (is (= {:before false :x 0.0 :y 200.0 :width 300.0 :height 200.0}
         (docking/drop-half 300.0 400.0 false 20.0 200.0)))
  (is (= {:before true :x 0.0 :y 0.0 :width 150.0 :height 400.0}
         (docking/drop-half 300.0 400.0 true 10.0 300.0)))
  (is (= {:before false :x 150.0 :y 0.0 :width 150.0 :height 400.0}
         (docking/drop-half 300.0 400.0 true 290.0 300.0))))

;; Same-zone drag outlines include the space released by the source, matching the final split.
(deftest same-zone-outline-matches-planned-layout
  (let [sizes {:left {:assets 1.0} :right {:outline 0.25 :inspector 0.25 :preview 0.5} :bottom {:tools 1.0}}
        {layout :layout planned-sizes :sizes}
        (docking/split-panel docking/default-layout sizes :preview :right :inspector true)]
    (is (= {:x 0.0 :y 400.0 :width 300.0 :height 200.0}
           (docking/panel-rectangle (:right layout) (:right planned-sizes) :preview 300.0 800.0 false)))
    (is (= {:x 225.0 :y 0.0 :width 75.0 :height 800.0}
           (docking/panel-rectangle (:right layout) (:right planned-sizes) :inspector 300.0 800.0 true)))))

;; Legacy combined inspectors expand in place; repeated migration never duplicates either panel.
(deftest separate-outline-layout-migration
  (let [legacy {:left [:inspector :assets] :right [:preview] :bottom [:tools] :center [:game]}
        migrated (docking/migrate-layout legacy)]
    (is (= [:outline :inspector :assets] (:left migrated)))
    (is (docking/valid-layout? migrated))
    (is (= migrated (docking/migrate-layout migrated)))
    (is (= [:inspector :assets] (:left (docking/move-panel migrated :outline :bottom))))
    (is (= [:tools :outline] (:bottom (docking/move-panel migrated :outline :bottom)))))
  (is (nil? (docking/migrate-layout nil)))
  (is (= {} (docking/migrate-layout {}))))

;; Mixed-axis splits preserve all panels, survive serialization, and collapse emptied source branches.
(deftest mixed-axis-docked-layout
  (let [trees (-> (docking/layout-trees docking/default-layout)
                  (docking/split-trees :assets :right :outline :left)
                  (docking/split-trees :tools :right :assets :bottom))
        layout (into {} (map (fn [[zone tree]] [zone (dock/panel-ids tree)])) trees)]
    (is (docking/valid-layout? layout))
    (is (docking/valid-trees? trees layout))
    (is (nil? (:left trees)))
    (is (nil? (:bottom trees)))
    (is (= :vertical (get-in trees [:right :axis])))
    (is (= :horizontal (get-in trees [:right :children 0 :axis])))
    (is (= :vertical (get-in trees [:right :children 0 :children 0 :axis])))
    (is (= [:assets :tools :outline :inspector :preview] (:right layout)))
    (is (not (docking/valid-trees? (assoc-in trees [:right :dividers] [0.8 0.3]) layout)))
    (is (not (docking/valid-trees? (assoc-in trees [:right :axis] :diagonal) layout)))
    (is (not (docking/valid-trees? trees docking/default-layout)))
    (is (= {:tabs [:outline]}
           (-> (get-in trees [:right :children 0])
               (dock/remove-panel :assets)
               (dock/remove-panel :tools))))))

;; Every panel exposes four directional targets regardless of its containing region's orientation.
(deftest four-direction-panel-targets
  (doseq [[x y edge] [[5.0 200.0 :left] [295.0 200.0 :right]
                      [150.0 5.0 :top] [150.0 395.0 :bottom]]]
    (is (= edge (:edge (docking/drop-quarter 300.0 400.0 x y)))))
  (is (= :top (dock/drop-edge 300.0 400.0 30.0 5.0)))
  (is (= :left (dock/drop-edge 300.0 400.0 5.0 40.0))))


(defn- with-dock-scene [test!]
  (ui/run-now
    (let [directory (.toFile (Files/createTempDirectory "dock-gesture" (make-array java.nio.file.attribute.FileAttribute 0)))
          preferences (prefs/make :schemas [:default] :scopes {:global (java.io.File. directory "global.edn")
                                                               :project (java.io.File. directory "project.edn")})
          localize (localization/make-editor preferences #(throw %))
          hosts (into {} (map (fn [zone]
                               [zone (doto (AnchorPane.)
                                       (.setId (str (name zone) "-pane"))
                                       (.setMinSize 160.0 0.0)
                                       (HBox/setHgrow Priority/ALWAYS))])) [:left :right :bottom :center])
          assets-title (Label. "Assets")
          assets (doto (StackPane. (into-array Node [assets-title]))
                   (.setId "assets-split"))
          inspector (doto (SplitPane.) (.setId "right-split"))
          tools (doto (TabPane.) (.setId "tool-tabs"))
          root (StackPane. (into-array Node [(VBox. (into-array Node [(doto (MenuBar.) (.setId "menu-bar"))
                                                                      (doto (HBox. 4.0 (into-array Node (mapv hosts [:left :right :bottom :center])))
                                                                        (.setId "workbench")
                                                                        (VBox/setVgrow Priority/ALWAYS))]))]))
          scene (Scene. root 1100.0 600.0)
          stage (doto (Stage.) (.setTitle "Dock gesture regression") (.setScene scene))]
      (StackPane/setAlignment assets-title javafx.geometry.Pos/TOP_LEFT)
      (.add (.getChildren ^AnchorPane (:left hosts)) assets)
      (.add (.getChildren ^AnchorPane (:right hosts)) inspector)
      (.add (.getChildren ^AnchorPane (:bottom hosts)) tools)
      (ui/user-data! scene :localization localize)
      (try
        (.show stage)
        (with-redefs [ui/main-scene (constantly scene)]
          (docking/init! scene preferences localize (StackPane.) (StackPane.) (fn [_ _])))
        (.applyCss root)
        (.layout root)
        (test! {:stage stage :scene scene :root root :assets-title assets-title})
        (finally
          (dock/restore-all! scene)
          (.hide stage))))))

;; Active drags float real content immediately while preserving the original home dimensions and half outline.
(deftest docked-drag-floats-with-home-placeholder
  (with-dock-scene
    (fn [{:keys [^Stage stage ^Scene scene ^StackPane root assets-title]}]
      (let [windows (:windows (ui/user-data scene :editor.dock-windows/manager))
            ^Node original-parent (.getParent ^Node assets-title)
            source (plorer/point assets-title 0.5 0.5)
            ^Node home (.lookup scene "#dock-panel-assets-dock-slot")
            home-width (.getWidth (.getLayoutBounds home))
            home-height (.getHeight (.getLayoutBounds home))]
        (plorer/mouse-press! stage source :primary)
        (plorer/mouse-move! stage [-30.0 80.0])
        (.applyCss root)
        (.layout root)
        (is (= 1 (count @windows)))
        (is (identical? original-parent (.getParent ^Node assets-title)))
        (is (not (identical? scene (.getScene ^Node assets-title))))
        (is (some? (.lookup scene "#dock-home-assets")))
        (is (< (Math/abs (- home-width (.getWidth (.getLayoutBounds home)))) 2.0))
        (is (< (Math/abs (- home-height (.getHeight (.getLayoutBounds home)))) 2.0))
        (let [^Node target (.lookup scene "#dock-panel-inspector-dock-slot")
              point (plorer/point target 0.05 0.5)
              ^Rectangle indicator (.lookup scene "#dock-drop-indicator")]
          (plorer/mouse-move! stage point)
          (is (.isVisible indicator))
          (is (< (Math/abs (- (.getWidth indicator) (- (/ (.getWidth (.getLayoutBounds target)) 2.0) 2.0))) 3.0))
          (plorer/mouse-release! stage point :primary)
          (.applyCss root)
          (.layout root)
          (is (zero? (count @windows)))
          (is (not (.isVisible indicator)))
          (let [manager (ui/user-data scene :editor.docking/manager)]
            (is (= [:outline :assets :inspector :preview] (:right @(:layout manager))))
            (is (= :horizontal (get-in @(:trees manager) [:right :children 1 :axis])))))))))

;; A floated panel can redock beside a different main-window panel instead of creating another float.
(deftest floating-drag-returns-to-another-main-panel
  (with-dock-scene
    (fn [{:keys [^Stage stage ^Scene scene ^StackPane root assets-title]}]
      (let [windows (:windows (ui/user-data scene :editor.dock-windows/manager))]
        (plorer/mouse-press! stage (plorer/point assets-title 0.5 0.5) :primary)
        (plorer/mouse-move! stage [-30.0 80.0])
        (plorer/mouse-release! stage [-30.0 80.0] :primary)
        (is (= 1 (count @windows)))
        (let [^Stage floating (:stage (val (first @windows)))
              label (plorer/one floating ".floating-dock-tab-label")
              ^Node target (.lookup scene "#dock-panel-game-dock-slot")
              screen-point (.localToScreen target 5.0 (/ (.getHeight (.getLayoutBounds target)) 2.0))
              point (.screenToLocal (.getRoot (.getScene floating)) screen-point)
              coordinates [(.getX point) (.getY point)]]
          (plorer/mouse-press! floating (plorer/point label 0.5 0.5) :primary)
          (plorer/mouse-move! floating coordinates)
          (is (.isVisible ^Rectangle (.lookup scene "#dock-drop-indicator")))
          (let [point (.screenToLocal (.getRoot (.getScene floating)) screen-point)]
            (plorer/mouse-release! floating [(.getX point) (.getY point)] :primary))
          (.applyCss root)
          (.layout root)
          (is (zero? (count @windows)))
          (is (identical? scene (.getScene ^Node assets-title)))
          (let [manager (ui/user-data scene :editor.docking/manager)]
            (is (= [:assets :game] (:center @(:layout manager))))
            (is (= :horizontal (get-in @(:trees manager) [:center :axis])))))))))


;; Windows can lose all JavaFX mouse events during reparenting; native samples must still move and release the float once.
(deftest native-pointer-tracking-survives-scene-reparenting
  (let [pointer (atom (DockDrag$Sample. 0.0 0.0 true false))
        tracker (atom nil)]
    (with-redefs-fn {#'dock/track-native-drag!
                    (fn [_ moved! released! cancelled!]
                      (let [value (DockDrag. (reify Supplier (get [_] @pointer))
                                             (reify BiConsumer (accept [_ x y] (moved! x y)))
                                             (reify BiConsumer (accept [_ x y] (released! x y)))
                                             cancelled!)]
                        (reset! tracker value)
                        value))}
      #(with-dock-scene
         (fn [{:keys [^Stage stage ^Scene scene ^StackPane root assets-title]}]
           (let [windows (:windows (ui/user-data scene :editor.dock-windows/manager))
                 ^Node home (.lookup scene "#dock-panel-assets-dock-slot")
                 outside (.localToScreen root -50.0 90.0)]
             (plorer/mouse-press! stage (plorer/point assets-title 0.5 0.5) :primary)
             (reset! pointer (DockDrag$Sample. (.getX outside) (.getY outside) true false))
             (.poll ^DockDrag @tracker)
             (.applyCss root)
             (.layout root)
             (is (= 1 (count @windows)))
             (is (some? (.lookup home ".dock-home-placeholder")))
             (let [^Stage floating (:stage (val (first @windows)))
                   x (.getX floating)]
               (reset! pointer (DockDrag$Sample. (+ 80.0 (.getX outside)) (.getY outside) true false))
               (.poll ^DockDrag @tracker)
               (is (= (+ 80.0 x) (.getX floating)))
               (is (= 1 (count @windows))))
             (let [^Node target (.lookup scene "#dock-panel-inspector-dock-slot")
                   point (.localToScreen target 5.0 (/ (.getHeight (.getLayoutBounds target)) 2.0))]
               (reset! pointer (DockDrag$Sample. (.getX point) (.getY point) false false)))
             (.poll ^DockDrag @tracker)
             (.applyCss root)
             (.layout root)
             (is (zero? (count @windows)))
             (is (identical? scene (.getScene ^Node assets-title)))
             (is (zero? (count @(:reserved (ui/user-data scene :editor.docking/manager)))))
             (.poll ^DockDrag @tracker)
             (is (zero? (count @windows)))
             (plorer/mouse-release! stage (plorer/point assets-title 0.5 0.5) :primary)))))))

;; Escape cancels an in-progress native drag and restores the real content without leaving an occupied slot or a ghost window.
(deftest escape-restores-detached-content-and-home
  (with-dock-scene
    (fn [{:keys [^Stage stage ^Scene scene ^StackPane root assets-title]}]
      (let [manager (ui/user-data scene :editor.dock-windows/manager)
            layout ((:snapshot! (ui/user-data scene :editor.docking/manager)))]
        (plorer/mouse-press! stage (plorer/point assets-title 0.5 0.5) :primary)
        (plorer/mouse-move! stage [-45.0 85.0])
        (is (= 1 (count @(:windows manager))))
        (is (= #{:assets} @(:reserved (ui/user-data scene :editor.docking/manager))))
        (let [^Stage floating (:stage (val (first @(:windows manager))))]
          (.requestFocus (.getRoot (.getScene floating)))
          (plorer/key-chord! floating [:escape]))
        (.applyCss root)
        (.layout root)
        (is (zero? (count @(:windows manager))))
        (is (identical? scene (.getScene ^Node assets-title)))
        (is (zero? (count @(:reserved (ui/user-data scene :editor.docking/manager)))))
        (is (= layout ((:snapshot! (ui/user-data scene :editor.docking/manager)))))
        (plorer/mouse-release! stage (plorer/point assets-title 0.5 0.5) :primary)))))

;; Native Escape must cancel exactly once even when neither the old nor the new window receives key or release events.
(deftest native-escape-stops-pointer-tracking
  (ui/run-now
    (let [pointer (atom (DockDrag$Sample. 10.0 20.0 true false))
          events (atom [])
          tracker (DockDrag. (reify Supplier (get [_] @pointer))
                             (reify BiConsumer (accept [_ x y] (swap! events conj [:move x y])))
                             (reify BiConsumer (accept [_ x y] (swap! events conj [:release x y])))
                             #(swap! events conj [:cancel]))]
      (.poll tracker)
      (reset! pointer (DockDrag$Sample. 30.0 40.0 true true))
      (.poll tracker)
      (reset! pointer (DockDrag$Sample. 30.0 40.0 false false))
      (.poll tracker)
      (is (= [[:move 10.0 20.0] [:cancel]] @events)))))

(defn- move-pointer! [^Stage stage ^Point2D point release]
  (let [point (.screenToLocal (.getRoot (.getScene stage)) point)
        coordinates [(.getX point) (.getY point)]]
    (if release
      (plorer/mouse-release! stage coordinates :primary)
      (plorer/mouse-move! stage coordinates))))

(defn- layout-windows! [manager]
  (doseq [[_ {:keys [^Stage stage]}] @(:windows manager)]
    (.applyCss (.getRoot (.getScene stage)))
    (.layout (.getRoot (.getScene stage)))))

;; Floating targets show exact half outlines and combine into tabs or nested splits without duplicating content.
(deftest floating-target-outlines-and-composition
  (doseq [[edge x y] [[:left 0.02 0.5] [:right 0.98 0.5] [:top 0.5 0.02] [:bottom 0.5 0.98] [:tab 0.5 0.5]]]
    (with-dock-scene
      (fn [{:keys [^Scene scene]}]
        (let [manager (ui/user-data scene :editor.dock-windows/manager)
              source-slot (.lookup scene "#dock-panel-assets-dock-slot")
              destination-slot (.lookup scene "#dock-panel-inspector-dock-slot")
              ^Stage source (dock/float-slot! source-slot 100.0 100.0)
              ^Stage destination (dock/float-slot! destination-slot 600.0 100.0)
              ^Node group (.lookup (.getScene destination) ".floating-dock-group")
              point (.localToScreen group (* x (.getWidth (.getLayoutBounds group))) (* y (.getHeight (.getLayoutBounds group))))
              ^Rectangle indicator (.lookup (.getScene destination) "#floating-dock-drop-indicator")]
          (layout-windows! manager)
          (plorer/mouse-press! source (plorer/point (plorer/one source ".floating-dock-tab-label") 0.5 0.5) :primary)
          (move-pointer! source point false)
          (is (.isVisible indicator) (name edge))
          (is (< (Math/abs (- (.getWidth indicator) (- (* (.getWidth (.getLayoutBounds group)) (if (contains? #{:left :right} edge) 0.5 1.0)) 2.0))) 1.0))
          (is (< (Math/abs (- (.getHeight indicator) (- (* (.getHeight (.getLayoutBounds group)) (if (contains? #{:top :bottom} edge) 0.5 1.0)) 2.0))) 1.0))
          (move-pointer! source point true)
          (is (= 1 (count @(:windows manager))))
          (let [tree (:tree (val (first @(:windows manager))))]
            (is (= 2 (count (dock/panel-ids tree))))
            (is (= (if (= :tab edge) 2 nil) (when (:tabs tree) (count (:tabs tree)))))
            (is (= (when-not (= :tab edge) (if (contains? #{:left :right} edge) :horizontal :vertical)) (:axis tree)))))))))

;; Tearing a grouped panel leaves its original split in place; Escape restores its tree, selection and bounds.
(deftest floating-group-tear-off-and-cancel
  (with-dock-scene
    (fn [{:keys [^Scene scene]}]
      (let [manager (ui/user-data scene :editor.dock-windows/manager)
            source-slot (.lookup scene "#dock-panel-assets-dock-slot")
            destination-slot (.lookup scene "#dock-panel-inspector-dock-slot")
            ^Stage source (dock/float-slot! source-slot 100.0 100.0)
            ^Stage destination (dock/float-slot! destination-slot 600.0 100.0)
            ^Node group (.lookup (.getScene destination) ".floating-dock-group")
            point (.localToScreen group 5.0 100.0)]
        (layout-windows! manager)
        (plorer/mouse-press! source (plorer/point (plorer/one source ".floating-dock-tab-label") 0.5 0.5) :primary)
        (move-pointer! source point false)
        (move-pointer! source point true)
        (.applyCss (.getRoot (.getScene destination)))
        (.layout (.getRoot (.getScene destination)))
        (let [[window-id _] (first @(:windows manager))
              original-x (.getX destination)
              label (first (plorer/all destination ".floating-dock-tab-label"))]
          (plorer/mouse-press! destination (plorer/point label 0.5 0.5) :primary)
          (let [tree (get-in @(:windows manager) [window-id :tree])]
            (plorer/mouse-move! destination [-100.0 -100.0])
            (layout-windows! manager)
            (is (= 2 (count @(:windows manager))))
            (is (some? (.lookup (.getScene destination) ".dock-home-placeholder")))
            (is (= :horizontal (get-in @(:windows manager) [window-id :drag-home :tree :axis])))
            (let [^Stage detached (:stage @(:drag manager))]
              (.requestFocus (.getRoot (.getScene detached)))
              (plorer/key-chord! detached [:escape]))
            (is (= 1 (count @(:windows manager))))
            (is (= tree (get-in @(:windows manager) [window-id :tree])))
            (is (= original-x (.getX destination)))
            (is (nil? (.lookup (.getScene destination) ".dock-home-placeholder")))
            (plorer/mouse-release! destination [10.0 10.0] :primary)))))))

;; Resource pages reorder without detaching, then detach and return through the same tab header without losing content.
(deftest resource-tab-reorder-tear-off-and-return
  (with-dock-scene
    (fn [{:keys [^Stage stage ^Scene scene ^StackPane root]}]
      (let [first-slot (StackPane. (into-array Node [(Label. "First editor")]))
            second-slot (StackPane. (into-array Node [(Label. "Second editor")]))
            first-tab (Tab. "First" first-slot)
            second-tab (Tab. "Second" second-slot)
            pane (doto (TabPane.) (.setMaxSize 500.0 300.0))
            manager (ui/user-data scene :editor.dock-windows/manager)]
        (.addAll (.getTabs pane) ^java.util.Collection [first-tab second-tab])
        (.add (.getChildren root) pane)
        (let [{:keys [dispose!]} (with-redefs [ui/main-scene (constantly scene)]
                                 (dock/register! first-slot "First" {:tab first-tab :home-visible! (fn [_])}))]
          (try
            (.applyCss root)
            (.layout root)
            (let [headers (vec (.lookupAll pane ".tab"))
                  ^Node first-header (:node (first (filterv #(identical? first-tab (:tab %)) (mapv dock/tab-header headers))))
                  ^Node second-header (:node (first (filterv #(identical? second-tab (:tab %)) (mapv dock/tab-header headers))))
                  start (.localToScreen first-header 10.0 10.0)
                  end (.localToScreen second-header (- (.getWidth (.getLayoutBounds second-header)) 3.0) 10.0)
                  ^Node body (first (.getChildren first-slot))]
              (plorer/mouse-press! stage (plorer/point first-header 0.5 0.5) :primary)
              (dock/start-slot-drag! first-slot (.getX start) (.getY start))
              (move-pointer! stage end false)
              (is (zero? (count @(:windows manager))))
              (move-pointer! stage end true)
              (is (= [second-tab first-tab] (vec (.getTabs pane))))
              (.applyCss root)
              (.layout root)
              (let [^Node first-header (:node (first (filterv #(identical? first-tab (:tab %)) (mapv dock/tab-header (.lookupAll pane ".tab")))))
                    start (.localToScreen first-header 10.0 10.0)
                    outside (.localToScreen root -60.0 80.0)]
                (plorer/mouse-press! stage (plorer/point first-header 0.5 0.5) :primary)
                (dock/start-slot-drag! first-slot (.getX start) (.getY start))
                (move-pointer! stage outside false)
                (is (= 1 (count @(:windows manager))) (pr-str (select-keys @(:drag manager) [:x :y :active :target])))
                (is (some? (.lookup first-slot ".dock-home-placeholder")))
                (is (not (identical? scene (.getScene body))))
                (let [return-point (.localToScreen first-header 10.0 10.0)]
                  (move-pointer! stage return-point false)
                  (move-pointer! stage return-point true)))
              (is (zero? (count @(:windows manager))))
              (is (identical? body (first (.getChildren first-slot))))
              (is (identical? scene (.getScene body))))
            (finally (dispose!))))))))

;; The GUI animation header uses the common drag controller, and a normal click never creates a window.
(deftest registered-workspace-handle-and-click-threshold
  (with-dock-scene
    (fn [{:keys [^Stage stage ^Scene scene ^StackPane root]}]
      (let [handle (Label. "GUI Animation")
            slot (doto (StackPane. (into-array Node [handle])) (.setMaxSize 500.0 200.0))
            manager (ui/user-data scene :editor.dock-windows/manager)]
        (StackPane/setAlignment handle javafx.geometry.Pos/TOP_LEFT)
        (.add (.getChildren root) slot)
        (let [{:keys [dispose!]} (with-redefs [ui/main-scene (constantly scene)]
                                 (dock/register! slot "Animation" {:drag-handle handle :home-visible! (fn [_])}))]
          (try
            (.applyCss root)
            (.layout root)
            (let [[x y :as point] (plorer/point handle 0.5 0.5)]
              (plorer/mouse-press! stage point :primary)
              (plorer/mouse-move! stage [(+ x 2.0) (+ y 2.0)])
              (plorer/mouse-release! stage point :primary)
              (is (zero? (count @(:windows manager))))
              (plorer/mouse-press! stage point :primary)
              (plorer/mouse-move! stage [-60.0 80.0])
              (is (= 1 (count @(:windows manager))))
              (is (some? (.lookup slot ".dock-home-placeholder")))
              (dock/cancel-drag! scene)
              (is (zero? (count @(:windows manager))))
              (is (identical? scene (.getScene handle)))
              (plorer/mouse-release! stage point :primary))
            (finally (dispose!))))))))
