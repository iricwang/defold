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

(ns editor.dock-windows
  (:require [clojure.edn :as edn]
            [editor.localization :as localization]
            [editor.prefs :as prefs]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [javafx.geometry Orientation Pos]
           [javafx.scene Node Scene]
           [javafx.scene.control Label MenuButton MenuItem SplitPane Tab TabPane TabPane$TabClosingPolicy Tooltip]
           [javafx.scene.input KeyCode KeyEvent MouseEvent]
           [javafx.scene.layout BorderPane StackPane]
           [javafx.stage Screen Stage]))

(set! *warn-on-reflection* true)

(defn panel-ids [tree]
  (if (:tabs tree)
    (:tabs tree)
    (into [] (mapcat panel-ids) (:children tree))))

(defn remove-panel [tree id]
  (if (:tabs tree)
    (let [ids (filterv #(not= id %) (:tabs tree))]
      (when (pos? (count ids)) (assoc tree :tabs ids)))
    (let [children (into [] (keep #(remove-panel % id)) (:children tree))]
      (case (count children)
        0 nil
        1 (first children)
        (assoc tree :children children)))))

(defn insert-panel [tree id target edge]
  (if (:tabs tree)
    (if-not (contains? (set (:tabs tree)) target)
      tree
      (if (= :tab edge)
        (update tree :tabs conj id)
        {:axis (if (contains? #{:left :right} edge) :horizontal :vertical)
         :children (if (contains? #{:left :top} edge)
                     [{:tabs [id]} tree]
                     [tree {:tabs [id]}])}))
    (update tree :children #(mapv (fn [child] (insert-panel child id target edge)) %))))

(defn drop-edge [width height x y]
  (cond
    (< x (* width 0.22)) :left
    (> x (* width 0.78)) :right
    (< y (* height 0.22)) :top
    (> y (* height 0.78)) :bottom
    :else :tab))

(defn remap-tree
  "Resolve panel identities and collapse branches whose resources are no longer open."
  [tree identities]
  (if-let [tabs (:tabs tree)]
    (let [tabs (into [] (keep identities) tabs)]
      (when (pos? (count tabs))
        {:tabs tabs :selected (or (identities (:selected tree)) (first tabs))}))
    (let [children (into [] (keep #(remap-tree % identities)) (:children tree))]
      (case (count children)
        0 nil
        1 (first children)
        (cond-> {:axis (:axis tree) :children children}
          (= (count children) (count (:children tree))) (assoc :dividers (:dividers tree)))))))

(defn- finite-number? [value]
  (and (number? value) (Double/isFinite (double value))))

(defn- valid-tree? [tree depth]
  (and (map? tree) (< depth 24)
       (if-let [tabs (:tabs tree)]
         (and (vector? tabs) (<= 1 (count tabs) 128)
              (coll/every? #(and (vector? %) (<= 2 (count %) 4)
                                (coll/every? (fn [part] (or (string? part) (keyword? part))) %)) tabs))
         (and (contains? #{:horizontal :vertical} (:axis tree))
              (vector? (:children tree)) (<= 2 (count (:children tree)) 32)
              (coll/every? #(valid-tree? % (inc depth)) (:children tree))
              (or (nil? (:dividers tree))
                  (and (vector? (:dividers tree))
                       (= (count (:dividers tree)) (dec (count (:children tree))))
                       (coll/every? #(and (finite-number? %) (< 0.0 (double %) 1.0)) (:dividers tree))
                       (apply < 0.0 (:dividers tree))))))))

(defn read-layout [text]
  (try
    (when (and (string? text) (< (count text) 1048576))
      (let [layout (edn/read-string text)
            windows (:windows layout)]
        (when (and (= 1 (:version layout)) (vector? windows) (<= (count windows) 32)
                   (coll/every? (fn [{:keys [tree bounds]}]
                                  (and (valid-tree? tree 0)
                                       (coll/every? #(finite-number? (bounds %)) [:x :y :width :height])
                                       (pos? (:width bounds)) (pos? (:height bounds)))) windows)
                   (let [ids (into [] (mapcat #(panel-ids (:tree %))) windows)]
                     (= (count ids) (count (set ids)))))
          layout)))
    (catch Exception _ nil)))

(defn fit-window-bounds
  "Keep a window on its monitor, or move it onto a connected monitor after undocking."
  [{:keys [x y width height] :as bounds} screens]
  (let [screen (reduce (fn [best screen]
                         (let [area (* (max 0.0 (- (min (+ x width) (+ (:x screen) (:width screen))) (max x (:x screen))))
                                       (max 0.0 (- (min (+ y height) (+ (:y screen) (:height screen))) (max y (:y screen)))))]
                           (if (> area (:area best)) {:area area :screen screen} best)))
                       {:area -1.0 :screen (first screens)} screens)
        {sx :x sy :y sw :width sh :height} (:screen screen)
        width (min sw (max 320.0 width))
        height (min sh (max 200.0 height))]
    (assoc bounds :x (max sx (min x (- (+ sx sw) width)))
           :y (max sy (min y (- (+ sy sh) height))) :width width :height height)))

(declare restore! float-panel! render!)

(defn set-home-visible!
  "Remove a home from its split while retaining the original order and divider positions."
  [^Node node visible]
  (let [^SplitPane split (or (ui/user-data node ::home-split)
                            (ui/closest-node-where #(instance? SplitPane %) (.getParent node)))]
    (when split
      (ui/user-data! node ::home-split split)
      (let [state (or (ui/user-data split ::homes)
                      {:items (vec (.getItems split))
                       :dividers (vec (.getDividerPositions split))
                       :hidden #{}})
            state (update state :hidden (if visible disj conj) node)]
        (let [current (vec (.getItems split))
              present ((if visible conj disj) (set current) node)
              items (into (filterv #(contains? present %) (:items state))
                          (remove #(contains? (set (:items state)) %)) current)]
          (when (not= items (vec (.getItems split)))
            (.setAll (.getItems split) ^java.util.Collection items)))
        (when (zero? (count (:hidden state)))
          (.setDividerPositions split (double-array (:dividers state))))
        (ui/user-data! split ::homes (when (pos? (count (:hidden state))) state))))))

(defn refresh-tab-homes! [^SplitPane split]
  ;; Keep logical tabs registered for save/close/reopen, but release their display area.
  (let [panes (.getItems split)
        visible (filterv (fn [^TabPane pane]
                           (or (zero? (count (.getTabs pane)))
                               (coll/any? #(not (ui/user-data % ::empty)) (.getTabs pane)))) panes)]
    (doseq [^TabPane pane panes]
      (let [shown (contains? (set visible) pane)]
        (.setVisible pane shown)
        (when-not (ui/user-data pane ::widths)
          (ui/user-data! pane ::widths [(.getMinWidth pane) (.getMaxWidth pane)]))
        (let [[minimum maximum] (ui/user-data pane ::widths)]
          (.setMinWidth pane (if shown (double minimum) 0.0))
          (.setMaxWidth pane (if shown (double maximum) 0.0)))))
    (when-let [home (or (ui/user-data split ::home) (.getParent split))]
      (ui/user-data! split ::home home)
      (set-home-visible! home (pos? (count visible))))))

(defn set-tab-empty! [^Tab tab empty]
  (ui/user-data! tab ::empty empty)
  (when-let [pane (.getTabPane tab)]
    (when (and empty (.isSelected tab))
      (when-let [other (coll/first-where #(not (ui/user-data % ::empty)) (.getTabs pane))]
        (.select (.getSelectionModel pane) ^Tab other)))
    (refresh-tab-homes! (ui/tab-pane-parent pane))))

(defn- capture-tree [tree ^Node node]
  (if (:tabs tree)
    (assoc tree :selected (ui/user-data node ::panel))
    (assoc tree
           :dividers (vec (.getDividerPositions ^SplitPane node))
           :children (mapv capture-tree (:children tree) (.getItems ^SplitPane node)))))

(defn- capture! [{:keys [windows]}]
  (swap! windows (fn [entries]
                   (into {} (map (fn [[id {:keys [^BorderPane host] :as entry}]]
                                   [id (update entry :tree capture-tree (.getCenter host))])) entries))))

(defn- manager [^Scene scene]
  (or (ui/user-data scene ::manager)
      (let [value {:scene scene :panels (atom {}) :windows (atom {}) :drag (atom nil)
                   :persistence (atom {:restoring false :last-check 0})}]
        (ui/user-data! scene ::manager value)
        value)))

(defn- clear-content! [^Node node]
  (cond
    (instance? TabPane node)
    (doseq [^Tab tab (.getTabs ^TabPane node)]
      (.setContent tab nil))

    (instance? SplitPane node)
    (doseq [^Node child (.getItems ^SplitPane node)]
      (clear-content! child))))

(defn- cancel-drag! [{:keys [drag]}]
  (when-let [^Node node (:highlight @drag)] (.setStyle node ""))
  (reset! drag nil))

(defn- target-at [{:keys [windows]} source x y]
  (coll/some
    (fn [[window-id {:keys [^BorderPane host]}]]
      (coll/some
        (fn [^TabPane group]
          (when-let [point (.screenToLocal group (double x) (double y))]
            (when (.contains group point)
              (let [selected (ui/user-data group ::panel)
                    target (if (not= source selected) selected
                               (coll/first-where #(not= source %) (ui/user-data group ::panels)))]
                (when target
                  {:window window-id :target target :highlight group
                   :edge (drop-edge (.getWidth group) (.getHeight group) (.getX point) (.getY point))})))))
        (.lookupAll host ".floating-dock-group")))
    @windows))

(defn- install-drag! [{:keys [drag windows] :as manager} ^Node handle id]
  (.setOnMousePressed handle
    (ui/event-handler event
      (when (.isPrimaryButtonDown ^MouseEvent event)
        (reset! drag {:id id :x (.getScreenX ^MouseEvent event) :y (.getScreenY ^MouseEvent event)}))))
  (.setOnMouseDragged handle
    (ui/event-handler event
      (when-let [{:keys [x y highlight]} @drag]
        (when highlight (.setStyle ^Node highlight ""))
        (let [dx (- (.getScreenX ^MouseEvent event) x)
              dy (- (.getScreenY ^MouseEvent event) y)
              target (target-at manager id (.getScreenX ^MouseEvent event) (.getScreenY ^MouseEvent event))]
          (when-let [^Node node (:highlight target)]
            (.setStyle node (str "-fx-border-color: #4b9fe1; -fx-border-width: "
                                 (case (:edge target) :left "0 0 0 8" :right "0 8 0 0" :top "8 0 0 0" :bottom "0 0 8 0" "3") ";")))
          (swap! drag merge target {:target target :active (> (+ (* dx dx) (* dy dy)) 36.0)})
          (.consume event)))))
  (.setOnMouseReleased handle
    (ui/event-handler event
      (let [{:keys [active target]} @drag]
        (cancel-drag! manager)
        (when active
          (if-let [{:keys [window target edge]} target]
            (do
              (capture! manager)
              (swap! windows (fn [entries]
                               (into {} (map (fn [[key entry]]
                                               [key (update entry :tree remove-panel id)])) entries)))
              (swap! windows update-in [window :tree] insert-panel id target edge)
              (render! manager))
            (float-panel! manager id (.getScreenX ^MouseEvent event) (.getScreenY ^MouseEvent event)))
          (.consume event))))))

(defn- build-node [{:keys [panels scene] :as manager} tree]
  (if-let [ids (:tabs tree)]
    (let [group (doto (TabPane.)
                  (.setMinSize 80.0 60.0)
                  (.setTabClosingPolicy TabPane$TabClosingPolicy/UNAVAILABLE)
                  (ui/add-style! "floating-dock-group"))]
      (doseq [id ids]
        (let [{:keys [^Node body title]} (@panels id)
              label (doto (Label. title)
                      (.setTooltip (doto (Tooltip.)
                                     (localization/localize! (ui/user-data scene :localization) (localization/message "dock.drag-help")))))
              tab (doto (Tab.) (.setGraphic label) (.setContent body))]
          (install-drag! manager label id)
          (.setOnMouseClicked label (ui/event-handler event
                                     (when (and (= 2 (.getClickCount ^MouseEvent event))
                                                (.isStillSincePress ^MouseEvent event))
                                       (restore! manager id))))
          (ui/observe (.selectedProperty tab)
                      (fn [_ _ selected]
                        (when selected (ui/user-data! group ::panel id))))
          (.add (.getTabs group) tab)))
      (ui/user-data! group ::panel (first ids))
      (ui/user-data! group ::panels ids)
      (when-let [index (coll/first-where #(= (:selected tree) (ids %)) (range (count ids)))]
        (.select (.getSelectionModel group) (int index)))
      group)
    (let [split (doto (SplitPane.)
                  (.setMinSize 80.0 60.0)
                  (.setOrientation (if (= :horizontal (:axis tree)) Orientation/HORIZONTAL Orientation/VERTICAL)))]
      (.setAll (.getItems split) ^java.util.Collection (mapv #(build-node manager %) (:children tree)))
      (when-let [dividers (:dividers tree)]
        (.setDividerPositions split (double-array dividers)))
      split)))

(defn render! [{:keys [windows] :as manager}]
  ;; Release every old parent before rebuilding any window, including cross-window moves.
  (doseq [[_ {:keys [^BorderPane host]}] @windows]
    (clear-content! (.getCenter host))
    (.setCenter host nil))
  (doseq [[id {:keys [tree ^BorderPane host ^Stage stage]}] @windows]
    (if tree
      (.setCenter host (build-node manager tree))
      (do (swap! windows dissoc id) (.hide stage)))))

(defn restore! [{:keys [panels windows] :as manager} id]
  (let [{:keys [^StackPane slot ^Node body floating! floating home-visible!]} (@panels id)]
    (capture! manager)
    (swap! windows (fn [entries]
                     (into {} (map (fn [[key entry]] [key (update entry :tree remove-panel id)])) entries)))
    (render! manager)
    (.setAll (.getChildren slot) ^java.util.Collection (vector body))
    (swap! panels assoc-in [id :floating] false)
    (when floating (home-visible! true))
    (when (and floating floating!) (floating! false))))

(defn float-panel! [{:keys [^Scene scene panels windows persistence] :as manager} id x y]
  (let [{:keys [^StackPane slot title floating! floating home-visible!]} (@panels id)
        localization (ui/user-data scene :localization)
        host (BorderPane.)
        root (doto (StackPane. (into-array Node [host])) (.setId "main-window"))
        floating-scene (Scene. root 960.0 640.0)
        stage (doto (ui/make-stage)
                (.initOwner (.getWindow scene))
                (.setTitle title)
                (.setScene floating-scene)
                (.setMinWidth 320.0)
                (.setMinHeight 200.0)
                (.setX (double x))
                (.setY (double y)))
        window-id (random-uuid)]
    (when-not (:restoring @persistence) (capture! manager))
    (.setAll (.getStylesheets floating-scene) ^java.util.Collection (.getStylesheets scene))
    (.setAll (.getStylesheets root) ^java.util.Collection (.getStylesheets (.getRoot scene)))
    (.setStyle root (.getStyle (.getRoot scene)))
    (ui/user-data! floating-scene :localization localization)
    (ui/user-data! root :editor.ui/context (ui/user-data (.getRoot scene) :editor.ui/context))
    (ui/user-data! host :editor.ui/context (ui/user-data (.lookup scene "#workbench") :editor.ui/context))
    (.clear (.getChildren slot))
    (swap! windows (fn [entries]
                     (into {} (map (fn [[key entry]] [key (update entry :tree remove-panel id)])) entries)))
    (swap! windows assoc window-id {:tree {:tabs [id]} :stage stage :host host})
    (.setOnCloseRequest stage
      (ui/event-handler event
        (.consume event)
        (doseq [panel (panel-ids (get-in @windows [window-id :tree]))]
          (restore! manager panel))))
    (.addEventFilter floating-scene KeyEvent/KEY_PRESSED
      (ui/event-handler event
        (when (= KeyCode/ESCAPE (.getCode ^KeyEvent event)) (cancel-drag! manager))))
    (ui/observe (.focusOwnerProperty floating-scene)
                (fn [_ _ node]
                  (when-let [owner (ui/closest-node-where #(ui/user-data % ::focus!) node)]
                    ((ui/user-data owner ::focus!)))))
    (ui/observe (.focusedProperty stage)
                (fn [_ _ focused]
                  (when focused
                    (when-let [owner (ui/closest-node-where #(ui/user-data % ::focus!) (.getFocusOwner floating-scene))]
                      ((ui/user-data owner ::focus!))))))
    (render! manager)
    (swap! panels assoc-in [id :floating] true)
    (when-not floating (home-visible! false))
    (when (and (not floating) floating!) (floating! true))
    (ui/user-data! stage ::normal-bounds {:x (double x) :y (double y) :width 960.0 :height 640.0})
    (when-not (:restoring @persistence) (.show stage))))

(defn register!
  "Register a stable home slot. Its body is moved, never copied; dispose before deleting the view."
  [^StackPane slot title {:keys [focus! floating! menu-items home-visible! bottom persistent-key]}]
  (let [manager (manager (ui/main-scene))
        id (random-uuid)
        body (doto (StackPane.) (.setMinSize 0.0 0.0))
        children (vec (.getChildren slot))
        button (when-not menu-items
                 (doto (MenuButton. "…")
                 (.setId "dock-panel-menu")
                 (.setFocusTraversable false)
                 (.setMinSize 24.0 24.0)
                 (.setPrefSize 24.0 24.0)
                 (.setMaxSize 24.0 24.0)
                 (.setStyle "-fx-min-width: 24px; -fx-pref-width: 24px; -fx-max-width: 24px; -fx-min-height: 24px; -fx-pref-height: 24px; -fx-max-height: 24px; -fx-padding: 0;")
                 (.setTooltip (doto (Tooltip.)
                                (localization/localize! (ui/user-data (:scene manager) :localization) (localization/message "dock.float"))))))
        item (doto (MenuItem.) (.setId "dock-float-panel"))
        menu-items (or menu-items (.getItems ^MenuButton button))
        localization (ui/user-data (:scene manager) :localization)
        dispose! (fn []
                   (restore! manager id)
                   (swap! (:panels manager) dissoc id))]
    (.clear (.getChildren slot))
    ;; Preserve the CSS scope on the moving body, not on its empty home placeholder.
    (when-let [id (.getId slot)]
      (.setId body id)
      (.setId slot (str id "-dock-slot")))
    (.setAll (.getStyleClass body) ^java.util.Collection (.getStyleClass slot))
    (.clear (.getStyleClass slot))
    (.setStyle body (.getStyle slot))
    (.setStyle slot "")
    (.setAll (.getChildren body) ^java.util.Collection children)
    (.setAll (.getChildren slot) ^java.util.Collection (vector body))
    (when button
      (.add (.getChildren body) button)
      (StackPane/setAlignment button (if bottom Pos/BOTTOM_RIGHT Pos/TOP_RIGHT)))
    (.add ^java.util.List menu-items item)
    (localization/localize! item localization (localization/message "dock.float"))
    (ui/user-data! body ::focus! focus!)
    (when focus!
      (.addEventFilter body MouseEvent/MOUSE_PRESSED (ui/event-handler _ (focus!))))
    (swap! (:panels manager) assoc id {:body body :slot slot :title title :persistent-key persistent-key
                                     :floating! (fn [floating]
                                                  (localization/localize! item localization (localization/message (if floating "dock.return" "dock.float")))
                                                  (when floating! (floating! floating)))
                                     :home-visible! (or home-visible! #(set-home-visible! slot %))})
    (.setOnAction item
      (ui/event-handler _
        (if (:floating (@(:panels manager) id))
          (restore! manager id)
          (let [point (.localToScreen body 20.0 20.0)]
            (float-panel! manager id (.getX point) (.getY point))))))
    (ui/user-data! slot ::dispose! dispose!)
    (ui/user-data! slot ::restore! #(when (and (not (:restoring @(:persistence manager)))
                                            (:floating (@(:panels manager) id)))
                                    (restore! manager id)))
    {:dispose! dispose!
     :visible? #(not (ui/inside-hidden-tab? body))}))

(defn restore-all! [scene]
  (when-let [manager (ui/user-data scene ::manager)]
    (doseq [[id {:keys [floating]}] @(:panels manager)]
      (when floating (restore! manager id)))))

(defn restore-slot! [slot]
  (when-let [restore! (ui/user-data slot ::restore!)] (restore!)))

(defn save-layout! [scene]
  (when-let [{:keys [panels windows persistence] :as manager} (ui/user-data scene ::manager)]
    (when-let [preferences (when-not (:restoring @persistence) (:prefs @persistence))]
      (capture! manager)
      (let [identities (into {} (keep (fn [[id panel]] (when-let [key (:persistent-key panel)] [id key]))) @panels)
            layout {:version 1
                    :windows (into [] (keep (fn [[_ {:keys [tree ^Stage stage]}]]
                                              (when-let [tree (remap-tree tree identities)]
                                                (let [bounds (if (or (.isMaximized stage) (.isIconified stage))
                                                               (ui/user-data stage ::normal-bounds)
                                                               {:x (.getX stage) :y (.getY stage)
                                                                :width (.getWidth stage) :height (.getHeight stage)})]
                                                  (ui/user-data! stage ::normal-bounds bounds)
                                                  {:tree tree :bounds bounds :maximized (.isMaximized stage)})))) @windows)}]
        (when (and (read-layout (pr-str layout)) (not= layout (:saved @persistence)))
          (prefs/set! preferences [:window :floating-layout] (pr-str layout))
          (swap! persistence assoc :saved layout))))))

(defn autosave! [scene]
  (when-let [{:keys [persistence]} (ui/user-data scene ::manager)]
    (let [now (System/nanoTime)]
      (when (> (- now (:last-check @persistence)) 1000000000)
        (swap! persistence assoc :last-check now)
        (save-layout! scene)))))

(defn- restore-dividers! [tree ^Node node]
  (when-not (:tabs tree)
    (let [^SplitPane split node]
      (.setDividerPositions split (double-array (:dividers tree)))
      (.layout split)
      (doseq [[child item] (mapv vector (:children tree) (.getItems split))]
        (restore-dividers! child item)))))

(defn start! [^Scene scene preferences]
  (let [{:keys [panels windows persistence] :as manager} (manager scene)
        layout (read-layout (prefs/get preferences [:window :floating-layout]))
        identities (into {} (keep (fn [[id panel]] (when-let [key (:persistent-key panel)] [key id]))) @panels)
        screens (mapv (fn [^Screen screen]
                        (let [bounds (.getVisualBounds screen)]
                          {:x (.getMinX bounds) :y (.getMinY bounds)
                           :width (.getWidth bounds) :height (.getHeight bounds)})) (Screen/getScreens))]
    (swap! persistence assoc :restoring true)
    (try
      (doseq [{:keys [tree bounds maximized]} (:windows layout)
              :let [tree (remap-tree tree identities)]
              :when tree]
        (let [ids (panel-ids tree)
              {:keys [x y width height] :as bounds} (fit-window-bounds bounds screens)]
          (float-panel! manager (first ids) x y)
          (let [[window-id {:keys [^Stage stage]}] (coll/first-where #(contains? (set (panel-ids (:tree (val %)))) (first ids)) @windows)]
            (doseq [id (subvec ids 1)]
              (let [{:keys [^StackPane slot home-visible! floating!]} (@panels id)]
                (.clear (.getChildren slot))
                (swap! panels assoc-in [id :floating] true)
                (home-visible! false)
                (floating! true)))
            (swap! windows assoc-in [window-id :tree] tree)
            (render! manager)
            (ui/user-data! stage ::normal-bounds bounds)
            (.setWidth stage width)
            (.setHeight stage height)
            (.show stage)
            (.setMaximized stage (boolean maximized)))))
      (catch Throwable error
        (swap! persistence assoc :restoring false)
        (throw error)))
    ;; Apply ratios after the shown windows have their decorated dimensions and CSS metrics.
    ;; Doing this before the first layout lets SplitPane redistribute their initial pixel sizes.
    (ui/run-later
      (try
        (doseq [[_ {:keys [tree ^BorderPane host ^Stage stage]}] @windows]
          (let [root (.getRoot (.getScene stage))]
            (.applyCss root)
            (.layout root)
            (restore-dividers! tree (.getCenter host))))
        (finally (swap! persistence assoc :restoring false :prefs preferences)))
      (save-layout! scene))))

(defn refresh! [^Scene scene evaluation-context]
  (when-let [manager (ui/user-data scene ::manager)]
    (doseq [[_ {:keys [^Stage stage]}] @(:windows manager)]
      (let [floating-scene (.getScene stage)]
        (ui/user-data! floating-scene :keymap (ui/user-data scene :keymap))
        (ui/refresh floating-scene evaluation-context)))))
