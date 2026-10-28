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
            [clojure.string :as string]
            [editor.localization :as localization]
            [editor.prefs :as prefs]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [com.defold.editor DockDrag]
           [java.util.function BiConsumer]
           [javafx.geometry Orientation Pos]
           [javafx.scene Node Scene]
           [javafx.scene.control Button Label Menu MenuButton MenuItem SeparatorMenuItem SplitPane TextInputDialog Tab TabPane TabPane$TabClosingPolicy Tooltip]
           [javafx.scene.input KeyCode KeyEvent MouseEvent]
           [javafx.scene.layout BorderPane HBox Priority StackPane VBox]
           [javafx.scene.paint Color]
           [javafx.scene.shape Rectangle]
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
        (cond-> (assoc tree :children children)
          (not= (count children) (count (:children tree))) (dissoc :dividers))))))

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
  (let [nx (/ x width)
        ny (/ y height)]
    (if (and (< 0.22 nx 0.78) (< 0.22 ny 0.78))
      :tab
      (if (> (Math/abs (- nx 0.5)) (Math/abs (- ny 0.5)))
        (if (< nx 0.5) :left :right)
        (if (< ny 0.5) :top :bottom)))))

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

(declare restore! float-panel! render! install-scene-drag! cancel-gesture!)

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
                   (into {} (map (fn [[id {:keys [^BorderPane host drag-home] :as entry}]]
                                   [id (if drag-home entry (update entry :tree capture-tree (.getCenter host)))])) entries))))

(defn- manager [^Scene scene]
  (or (ui/user-data scene ::manager)
      (let [value {:scene scene :panels (atom {}) :windows (atom {}) :drag (atom nil)
                   :persistence (atom {:restoring false :last-check 0})}]
        (ui/user-data! scene ::manager value)
        (install-scene-drag! value scene)
        value)))

(defn- clear-content! [^Node node]
  (cond
    (instance? TabPane node)
    (doseq [^Tab tab (.getTabs ^TabPane node)]
      (.setContent tab nil))

    (instance? SplitPane node)
    (doseq [^Node child (.getItems ^SplitPane node)]
      (clear-content! child))))

(defn- clear-target! [{:keys [windows scene]}]
  (doseq [[_ {:keys [^Rectangle indicator]}] @windows]
    (.setVisible indicator false))
  (when-let [show-target! (:show-target! (ui/user-data scene :editor.docking/manager))]
    (show-target! nil)))

(defn- show-target! [{:keys [windows scene] :as manager} target]
  (clear-target! manager)
  (when target
    (if (:window target)
      (let [{:keys [^Stage stage ^Rectangle indicator]} (@windows (:window target))
            ^Node node (:highlight target)
            width (.getWidth (.getLayoutBounds node))
            height (.getHeight (.getLayoutBounds node))
            edge (:edge target)
            horizontal (contains? #{:left :right} edge)
            w (if horizontal (/ width 2.0) width)
            h (if (contains? #{:top :bottom} edge) (/ height 2.0) height)
            point (.sceneToLocal (.getRoot (.getScene stage))
                                 (.localToScene node (if (= :right edge) w 0.0) (if (= :bottom edge) h 0.0)))]
        (.setX indicator (+ 1.0 (.getX point)))
        (.setY indicator (+ 1.0 (.getY point)))
        (.setWidth indicator (max 0.0 (- w 2.0)))
        (.setHeight indicator (max 0.0 (- h 2.0)))
        (.setVisible indicator true)
        (.toFront indicator))
      (when-let [show! (:show-target! (ui/user-data scene :editor.docking/manager))]
        (show! target)))))

(let [skin (Class/forName "javafx.scene.control.skin.TabPaneSkin$TabHeaderSkin")
      method (.getDeclaredMethod skin "getTab" (make-array Class 0))]
  (.setAccessible method true)
  (defn tab-header [node]
    (when-let [header (ui/closest-node-where #(instance? skin %) node)]
      {:node header :tab (.invoke method header (object-array 0))})))

(defn- target-at [{:keys [panels windows scene drag]} source x y]
  (or (coll/some
        (fn [[window-id {:keys [^Stage stage ^BorderPane host]}]]
          ;; The window following the pointer must never hide the destination behind it.
          (when (and (.isShowing stage) (not (.isIconified stage))
                     (not (identical? stage (:stage @drag))))
            (coll/some
              (fn [^TabPane group]
                (when-let [point (.screenToLocal group (double x) (double y))]
                  (when (.contains group point)
                    (let [target (coll/first-where #(not= source %) (ui/user-data group ::panels))]
                      {:window window-id :target (or target source) :highlight group
                       :floating-home (nil? target)
                       :edge (if target (drop-edge (.getWidth group) (.getHeight group) (.getX point) (.getY point)) :tab)}))))
              (.lookupAll host ".floating-dock-group"))))
        @windows)
      (when-let [^Tab tab (:tab (@panels source))]
        (when-let [^TabPane pane (.getTabPane tab)]
          (coll/some (fn [^Node header]
                       (when-let [point (.screenToLocal header (double x) (double y))]
                         (when (.contains header point)
                           (when-let [^Tab target-tab (:tab (tab-header header))]
                             (when (identical? pane (.getTabPane ^Tab target-tab))
                               {:resource-tab target-tab :before (< (.getX point) (/ (.getWidth (.getLayoutBounds header)) 2.0))
                                :node header :x 0.0 :y 0.0 :width (.getWidth (.getLayoutBounds header))
                                :height (.getHeight (.getLayoutBounds header))})))))
                     (.lookupAll pane ".tab"))))
      (let [{:keys [target-at]} (ui/user-data scene :editor.docking/manager)
            [kind panel] (:persistent-key (@panels source))]
        (when (and target-at (= :panel kind))
          (when-let [target (target-at panel x y)]
            (assoc target :docked true :panel panel))))
      (let [^Node slot (:slot (@panels source))]
        (when (ui/user-data slot ::reserved-home)
          (when-let [point (.screenToLocal slot (double x) (double y))]
            (when (.contains slot point)
              {:home source :node slot :x 0.0 :y 0.0
               :width (.getWidth (.getLayoutBounds slot)) :height (.getHeight (.getLayoutBounds slot))}))))))

(defn- track-native-drag! [owner moved! released! cancelled!]
  (DockDrag/start owner
                  (reify BiConsumer (accept [_ x y] (moved! (double x) (double y))))
                  (reify BiConsumer (accept [_ x y] (released! (double x) (double y))))
                  cancelled!))

(defn- cancel-gesture! [{:keys [drag panels windows] :as manager}]
  (when-let [{:keys [id active origin tracker stage]} @drag]
    (when tracker (.close ^DockDrag tracker))
    (reset! drag nil)
    (clear-target! manager)
    (when (and active stage)
      (if-not origin
        (restore! manager id)
        (when (:floating (@panels id))
          (let [[window-id {:keys [^Stage stage] :as entry} bounds] origin]
            (swap! windows (fn [entries]
                             (assoc (into {} (map (fn [[key entry]] [key (update entry :tree remove-panel id)])) entries)
                                    window-id entry)))
            (render! manager)
            (.setMaximized stage false)
            (.setX stage (:x bounds))
            (.setY stage (:y bounds))
            (.setWidth stage (:width bounds))
            (.setHeight stage (:height bounds))
            (.setMaximized stage (:maximized bounds))))))
    (when-let [release-home! (:release-home! (@panels id))] (release-home!))))

(defn cancel-drag! [^Scene scene]
  (when-let [manager (ui/user-data scene ::manager)]
    (cancel-gesture! manager)))

(defn- move-drag! [{:keys [drag panels] :as manager} x y]
  (when-let [{:keys [id active origin stage] start-x :x start-y :y} @drag]
    (let [dx (- x start-x)
          dy (- y start-y)]
      (when (or active (> (+ (* dx dx) (* dy dy)) 36.0))
        (swap! drag assoc :active true)
        (let [target (target-at manager id x y)]
          ;; Reordering resource tabs within their header does not detach the content.
          ;; Leaving the header creates the real floating window and reserves its home.
          (when (and (nil? stage) (not (:resource-tab target)))
            (when-not origin
              (when-let [reserve-home! (:reserve-home! (@panels id))] (reserve-home!)))
            (let [[_ {:keys [^Stage stage tree]}] origin
                  reuse (and origin (= [id] (panel-ids tree)))
                  offset-x (if reuse (- start-x (.getX stage)) 24.0)
                  offset-y (if reuse (- start-y (.getY stage)) 12.0)
                  stage (if reuse stage
                          (do
                            (when origin
                              (let [[window-id entry] origin
                                    placeholder (doto (Label. (:title (@panels id)))
                                                  (ui/add-style! "dock-home-placeholder")
                                                  (.setMaxSize Double/MAX_VALUE Double/MAX_VALUE)
                                                  (.setStyle "-fx-opacity: 0.65; -fx-border-color: -fx-box-border; -fx-border-style: segments(4, 4); -fx-border-insets: 3;"))]
                                (swap! (:windows manager) assoc-in [window-id :drag-home]
                                       {:tree (:tree entry) :id id :placeholder placeholder})))
                            (float-panel! manager id (- x offset-x) (- y offset-y))))]
              (.setMaximized ^Stage stage false)
              (swap! drag assoc :stage stage :offset-x offset-x :offset-y offset-y)))
          (when-let [^Stage stage (:stage @drag)]
            (.setX stage (- x (:offset-x @drag)))
            (.setY stage (- y (:offset-y @drag)))))
        (let [target (target-at manager id x y)]
          (swap! drag assoc :target target)
          (show-target! manager target))))))

(defn- release-drag! [{:keys [drag windows panels scene] :as manager} x y]
  (move-drag! manager x y)
  (when-let [{:keys [id active target tracker]} @drag]
    (if (:floating-home target)
      (cancel-gesture! manager)
      (do
        (when tracker (.close ^DockDrag tracker))
        (reset! drag nil)
        (clear-target! manager)
        (when (coll/any? #(get (val %) :drag-home) @windows)
          (swap! windows #(into {} (map (fn [[id entry]] [id (dissoc entry :drag-home)])) %))
          (render! manager))
        (try
          (when active
            (let [{:keys [window edge home docked panel resource-tab before]} target]
              (cond
                resource-tab
                (let [^Tab source (:tab (@panels id))
                      ^TabPane pane (.getTabPane source)
                      tabs (vec (.getTabs pane))]
                  (restore! manager id)
                  (when-not (identical? source resource-tab)
                    (let [remaining (filterv #(not (identical? source %)) tabs)
                          position (.indexOf ^java.util.List remaining resource-tab)
                          position (if before position (inc position))]
                      (.setAll (.getTabs pane) ^java.util.Collection
                               (into (conj (subvec remaining 0 position) source) (subvec remaining position)))))
                  (.select (.getSelectionModel pane) source))

                docked ((:drop! (ui/user-data scene :editor.docking/manager)) panel target)
                home (restore! manager id)
                window (do
                         (capture! manager)
                         (swap! windows (fn [entries]
                                          (into {} (map (fn [[key entry]] [key (update entry :tree remove-panel id)])) entries)))
                         (swap! windows update-in [window :tree] insert-panel id (:target target) edge)
                         (render! manager)))))
          (finally
            (when-let [release-home! (:release-home! (@panels id))] (release-home!))))))))

(defn- start-drag! [{:keys [drag windows panels] :as manager} id x y]
  (cancel-gesture! manager)
  (capture! manager)
  (let [origin (coll/first-where #(contains? (set (panel-ids (:tree (val %)))) id) @windows)
        ^Node body (:body (@panels id))
        ^Scene source-scene (or (.getScene body) (:scene manager))
        origin (when origin
                 (let [[window-id {:keys [^Stage stage] :as entry}] origin]
                   [window-id entry {:x (.getX stage) :y (.getY stage)
                                     :width (.getWidth stage) :height (.getHeight stage)
                                     :maximized (.isMaximized stage)}]))]
    (reset! drag {:id id :x x :y y :origin origin})
    (when-let [tracker (track-native-drag! (.getWindow source-scene)
                                         #(move-drag! manager %1 %2)
                                         #(release-drag! manager %1 %2)
                                         #(cancel-gesture! manager))]
      (swap! drag assoc :tracker tracker))))

(defn start-slot-drag! [slot x y]
  (when-let [start! (ui/user-data slot ::start-drag!)]
    (start! x y)))

(defn- install-scene-drag! [{:keys [drag] :as manager} ^Scene scene]
  (.addEventFilter scene MouseEvent/MOUSE_DRAGGED
    (ui/event-handler event
      (when @drag
        (move-drag! manager (.getScreenX ^MouseEvent event) (.getScreenY ^MouseEvent event))
        (.consume event))))
  (.addEventFilter scene MouseEvent/MOUSE_RELEASED
    (ui/event-handler event
      (when @drag
        (let [active (:active @drag)]
          (release-drag! manager (.getScreenX ^MouseEvent event) (.getScreenY ^MouseEvent event))
          (when active (.consume event))))))
  (.addEventFilter scene KeyEvent/KEY_PRESSED
    (ui/event-handler event
      (when (and @drag (= KeyCode/ESCAPE (.getCode ^KeyEvent event)))
        (cancel-gesture! manager)
        (.consume event)))))

(defn- install-drag! [manager ^Node handle id]
  (.setOnMousePressed handle
    (ui/event-handler event
      (when (.isPrimaryButtonDown ^MouseEvent event)
        (start-drag! manager id (.getScreenX ^MouseEvent event) (.getScreenY ^MouseEvent event))))))

(defn- build-node [{:keys [panels scene reserved-bodies] :as manager} tree]
  (if-let [ids (:tabs tree)]
    (let [group (doto (TabPane.)
                  (.setMinSize 80.0 60.0)
                  (.setTabClosingPolicy TabPane$TabClosingPolicy/UNAVAILABLE)
                  (ui/add-style! "floating-dock-group"))]
      (doseq [id ids]
        (let [{:keys [^Node body title title-property]} (@panels id)
              label (doto (Label. title)
                      (ui/add-style! "floating-dock-tab-label")
                      (.setTooltip (doto (Tooltip.)
                                     (localization/localize! (ui/user-data scene :localization)
                                                             (localization/message (if (ui/user-data body ::collapse!)
                                                                                     "dock.game-drag-help"
                                                                                     "dock.drag-help"))))))
              tab (doto (Tab.) (.setGraphic label) (.setContent (or (get reserved-bodies id) body)))]
          (when title-property
            (.bind (.textProperty label) ^javafx.beans.value.ObservableValue title-property))
          (install-drag! manager label id)
          (.setOnMouseClicked label (ui/event-handler event
                                      (when (and (= javafx.scene.input.MouseButton/PRIMARY (.getButton ^MouseEvent event))
                                                 (= 2 (.getClickCount ^MouseEvent event))
                                                 (.isStillSincePress ^MouseEvent event))
                                        (if-let [collapse! (ui/user-data body ::collapse!)]
                                          (collapse!)
                                          (restore! manager id))
                                        (.consume event))))
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
  (doseq [[id {:keys [tree ^BorderPane host ^Stage stage drag-home]}] @windows]
    (if (or tree drag-home)
      (.setCenter host (build-node (cond-> manager drag-home (assoc :reserved-bodies {(:id drag-home) (:placeholder drag-home)}))
                                  (or (:tree drag-home) tree)))
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
  (let [{:keys [^StackPane slot title title-property floating! floating home-visible!]} (@panels id)
        localization (ui/user-data scene :localization)
        host (BorderPane.)
        indicator (doto (Rectangle.)
                    (.setId "floating-dock-drop-indicator") (.setManaged false) (.setMouseTransparent true)
                    (.setVisible false) (.setFill (Color/web "#4b9fe1" 0.12))
                    (.setStroke (Color/web "#4b9fe1")) (.setStrokeWidth 2.0))
        root (doto (StackPane. (into-array Node [host indicator])) (.setId "main-window"))
        width (max 320.0 (.getWidth (.getLayoutBounds ^Node (:body (@panels id)))))
        height (max 200.0 (.getHeight (.getLayoutBounds ^Node (:body (@panels id)))))
        floating-scene (Scene. root width height)
        stage (doto (ui/make-stage)
                (.initOwner (.getWindow scene))
                (.setTitle title)
                (.setScene floating-scene)
                (.setMinWidth 320.0)
                (.setMinHeight 200.0)
                (.setX (double x))
                (.setY (double y)))
        window-id (random-uuid)]
    (when title-property
      (.bind (.titleProperty stage) ^javafx.beans.value.ObservableValue title-property))
    (when-not (:restoring @persistence) (capture! manager))
    (.setAll (.getStylesheets floating-scene) ^java.util.Collection (.getStylesheets scene))
    (.setAll (.getStylesheets root) ^java.util.Collection (.getStylesheets (.getRoot scene)))
    (.setStyle root (.getStyle (.getRoot scene)))
    (ui/user-data! floating-scene :localization localization)
    (ui/user-data! root :editor.ui/context (ui/user-data (.getRoot scene) :editor.ui/context))
    (ui/user-data! host :editor.ui/context (ui/user-data (.lookup scene "#workbench") :editor.ui/context))
    (.remove (.getChildren slot) ^Object (:body (@panels id)))
    (swap! windows (fn [entries]
                     (into {} (map (fn [[key entry]] [key (update entry :tree remove-panel id)])) entries)))
    (swap! windows assoc window-id {:tree {:tabs [id]} :stage stage :host host :indicator indicator})
    (.setOnCloseRequest stage
      (ui/event-handler event
        (.consume event)
        (doseq [panel (panel-ids (get-in @windows [window-id :tree]))]
          (restore! manager panel))))
    (install-scene-drag! manager floating-scene)
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
    (ui/user-data! stage ::normal-bounds {:x (double x) :y (double y) :width width :height height})
    (when-not (:restoring @persistence)
      (.show stage)
      (.applyCss root)
      (.layout root))
    stage))

(declare layout-menu)

(defn register!
  "Register a stable home slot. Its body is moved, never copied; dispose before deleting the view."
  [^StackPane slot title {:keys [focus! floating! menu-items title-property home-visible! reserve-home! release-home! bottom persistent-key drag-handle tab]}]
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
        raw-home-visible! (or home-visible! #(set-home-visible! slot %))
        placeholder (doto (Label. (str title "\n" (localization (localization/message "dock.return"))))
                      (ui/add-style! "dock-home-placeholder")
                      (.setMaxSize Double/MAX_VALUE Double/MAX_VALUE)
                      (.setMouseTransparent true)
                      (.setStyle "-fx-opacity: 0.65; -fx-background-color: derive(-fx-base, 5%); -fx-border-color: -fx-box-border; -fx-border-style: segments(4, 4); -fx-border-insets: 3;"))
        generic-reserved (volatile! false)
        reserve-home! (or reserve-home!
                          (fn []
                            (when-not @generic-reserved
                              (vreset! generic-reserved true)
                              (ui/user-data! slot ::reserved-home true)
                              (.add (.getChildren slot) placeholder)
                              (raw-home-visible! true))))
        release-home! (or release-home!
                          (fn []
                            (when @generic-reserved
                              (vreset! generic-reserved false)
                              (ui/user-data! slot ::reserved-home false)
                              (.remove (.getChildren slot) placeholder)
                              (raw-home-visible! (not (:floating (@(:panels manager) id)))))))
        home-visible! (fn [visible]
                        (when visible
                          (vreset! generic-reserved false)
                          (ui/user-data! slot ::reserved-home false)
                          (.remove (.getChildren slot) placeholder))
                        (raw-home-visible! (or visible @generic-reserved)))
        dispose! (fn []
                   (when (= id (:id @(:drag manager))) (cancel-gesture! manager))
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
    (.add ^java.util.List menu-items (layout-menu (:scene manager)))
    (localization/localize! item localization (localization/message "dock.float"))
    (ui/user-data! body ::focus! focus!)
    (when focus!
      (.addEventFilter body MouseEvent/MOUSE_PRESSED (ui/event-handler _ (focus!))))
    (swap! (:panels manager) assoc id {:body body :slot slot :title title :title-property title-property :persistent-key persistent-key :tab tab
                                     :floating! (fn [floating]
                                                  (localization/localize! item localization (localization/message (if floating "dock.return" "dock.float")))
                                                  (when floating! (floating! floating)))
                                     :reserve-home! reserve-home! :release-home! release-home!
                                     :home-visible! home-visible!})
    (.setOnAction item
      (ui/event-handler _
        (if (:floating (@(:panels manager) id))
          (restore! manager id)
          (let [point (.localToScreen body 20.0 20.0)]
            (float-panel! manager id (.getX point) (.getY point))))))
    (ui/user-data! slot ::dispose! dispose!)
    (ui/user-data! slot ::start-drag! #(start-drag! manager id %1 %2))
    (when drag-handle
      (.addEventFilter ^Node drag-handle MouseEvent/MOUSE_PRESSED
        (ui/event-handler event
          (when (and (.isPrimaryButtonDown ^MouseEvent event)
                     (< (.getY (.sceneToLocal ^Node drag-handle (.getSceneX ^MouseEvent event) (.getSceneY ^MouseEvent event))) 28.0)
                     (nil? (ui/closest-node-where #(or (instance? javafx.scene.control.ButtonBase %)
                                                       (instance? javafx.scene.control.ComboBoxBase %)
                                                       (instance? javafx.scene.control.TextInputControl %)) (.getTarget event))))
            (start-drag! manager id (.getScreenX ^MouseEvent event) (.getScreenY ^MouseEvent event))))))
    (ui/user-data! slot ::float! #(float-panel! manager id %1 %2))
    (ui/user-data! slot ::restore! #(when (and (not (:restoring @(:persistence manager)))
                                            (:floating (@(:panels manager) id)))
                                    (restore! manager id)))
    {:dispose! dispose!
     :visible? #(not (ui/inside-hidden-tab? body))}))

(defn restore-all! [scene]
  (when-let [restore! (ui/user-data scene ::restore-collapsed-game!)]
    (restore!))
  (when-let [manager (ui/user-data scene ::manager)]
    (cancel-gesture! manager)
    (doseq [[id {:keys [floating]}] @(:panels manager)]
      (when floating (restore! manager id)))))

(defn restore-slot! [slot]
  (when-let [restore! (ui/user-data slot ::restore!)] (restore!)))

(defn float-slot! [slot x y]
  (when-let [float! (ui/user-data slot ::float!)]
    (float! x y)))

(defn install-game-collapse!
  "Collapse Game to the menu bar, preserving its content and floating group."
  [^Scene scene ^StackPane slot capture-home! restore-home!]
  (when-let [dispose! (ui/user-data slot ::collapse-dispose!)]
    (dispose!))
  (let [manager (manager scene)
        panel (coll/first-where #(identical? slot (:slot %)) (coll/vals @(:panels manager)))
        ^Node body (:body panel)
        localization (ui/user-data scene :localization)
        ^Node menu-bar (.lookup scene "#menu-bar")
        row (or (.lookup scene "#menu-bar-row")
                (let [^VBox parent (.getParent menu-bar)
                      index (.indexOf (.getChildren parent) menu-bar)
                      row (doto (HBox.) (.setId "menu-bar-row") (.setAlignment Pos/CENTER_RIGHT))]
                  (.remove (.getChildren parent) menu-bar)
                  (.add (.getChildren row) menu-bar)
                  (HBox/setHgrow menu-bar Priority/ALWAYS)
                  (.add (.getChildren parent) index row)
                  row))
        button (doto (Button.)
                 (.setId "collapsed-game-button")
                 (.setFocusTraversable false)
                 (.setVisible false)
                 (.setManaged false)
                 (ui/add-style! "button-small")
                 (localization/localize! localization (localization/message "dock.game"))
                 (.setTooltip (doto (Tooltip.)
                                (localization/localize! localization (localization/message "dock.restore-game")))))
        collapsed (volatile! nil)]
    (letfn [(restore-game! []
              (when-let [{:keys [^Stage stage positions]} @collapsed]
                (vreset! collapsed nil)
                (ui/user-data! scene ::collapsed-home-positions nil)
                (if stage
                  (do (.show stage) (.toFront stage))
                  (do ((:home-visible! panel) true)
                      (.applyCss (.getRoot scene))
                      (.layout (.getRoot scene))
                      (restore-home! positions)))
                (.setVisible button false)
                (.setManaged button false)))
            (collapse-game! []
              (when-not @collapsed
                (cancel-gesture! manager)
                (let [body-scene (.getScene body)]
                  (if (identical? scene body-scene)
                    (let [positions (capture-home!)]
                      (vreset! collapsed {:positions positions})
                      (ui/user-data! scene ::collapsed-home-positions positions)
                      ((:home-visible! panel) false))
                    (let [^Stage stage (.getWindow body-scene)]
                      ;; Retain the floating group's tree and geometry.
                      (vreset! collapsed {:stage stage})
                      (.hide stage))))
                (.setVisible button true)
                (.setManaged button true)))]
      (let [clicked (ui/event-handler event
                      (when (and (= javafx.scene.input.MouseButton/PRIMARY (.getButton ^MouseEvent event))
                                 (= 2 (.getClickCount ^MouseEvent event))
                                 (.isStillSincePress ^MouseEvent event)
                                 (< (.getY (.sceneToLocal body (.getSceneX ^MouseEvent event) (.getSceneY ^MouseEvent event))) 28.0)
                                 (nil? (ui/closest-node-where
                                         #(or (instance? javafx.scene.control.ButtonBase %)
                                              (instance? javafx.scene.control.ComboBoxBase %)
                                              (instance? javafx.scene.control.TextInputControl %))
                                         (.getTarget event))))
                        (collapse-game!)
                        (.consume event)))]
        (.add (.getChildren ^HBox row) button)
        (.setOnAction button (ui/event-handler _ (restore-game!)))
        (.addEventFilter body MouseEvent/MOUSE_CLICKED clicked)
        (ui/user-data! body ::collapse! collapse-game!)
        (ui/user-data! scene ::restore-collapsed-game! restore-game!)
        (ui/user-data! slot ::collapse-dispose!
                       (fn dispose-game-collapse! []
                         (restore-game!)
                         (.removeEventFilter body MouseEvent/MOUSE_CLICKED clicked)
                         (.remove (.getChildren ^HBox row) button)
                         (ui/user-data! body ::collapse! nil)
                         (ui/user-data! scene ::restore-collapsed-game! nil)))))))

(defn save-layout! [scene]
  (when-let [{:keys [panels windows persistence drag] :as manager} (ui/user-data scene ::manager)]
    (when-let [preferences (when-not (or (:restoring @persistence) @drag) (:prefs @persistence))]
      (capture! manager)
      (let [identities (into {} (keep (fn [[id panel]] (when-let [key (:persistent-key panel)] [id key]))) @panels)
            layout {:version 1
                    :docked (when-let [snapshot! (:snapshot! (ui/user-data scene :editor.docking/manager))]
                              (snapshot!))
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
      (when-let [apply! (:apply! (ui/user-data scene :editor.docking/manager))]
        (apply! (:docked layout)))
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
        (when-let [restore-visibility! (:restore-visibility! (ui/user-data scene :editor.docking/manager))]
          (restore-visibility! (:docked layout)))
        (finally (swap! persistence assoc :restoring false :prefs preferences)))
      (save-layout! scene))))

(defn refresh! [^Scene scene evaluation-context]
  (when-let [manager (ui/user-data scene ::manager)]
    (doseq [[_ {:keys [^Stage stage]}] @(:windows manager)]
      (let [floating-scene (.getScene stage)]
        (ui/user-data! floating-scene :keymap (ui/user-data scene :keymap))
        (ui/refresh floating-scene evaluation-context)))))


(defn save-named-layout! [scene name]
  (let [{:keys [persistence]} (manager scene)
        preferences (:prefs @persistence)
        name (string/trim name)]
    (when (and preferences (not (:restoring @persistence)) (<= 1 (count name) 80))
      (save-layout! scene)
      (let [layout (assoc (read-layout (prefs/get preferences [:window :floating-layout]))
                         :docked (when-let [snapshot! (:snapshot! (ui/user-data scene :editor.docking/manager))]
                                   (snapshot!)))]
        (prefs/set! preferences [:window :named-layouts]
                    (assoc (prefs/get preferences [:window :named-layouts]) name (pr-str layout)))
        true))))

(defn apply-named-layout! [scene name]
  (let [{:keys [persistence]} (manager scene)
        preferences (:prefs @persistence)]
    (when (and preferences (not (:restoring @persistence)))
      (when-let [layout (read-layout (get (prefs/get preferences [:window :named-layouts]) name ""))]
        (restore-all! scene)
        (when-let [apply! (:apply! (ui/user-data scene :editor.docking/manager))]
          (apply! (:docked layout)))
        (prefs/set! preferences [:window :floating-layout] (pr-str layout))
        (start! scene preferences)
        (ui/run-later
          (when-let [restore-visibility! (:restore-visibility! (ui/user-data scene :editor.docking/manager))]
            (restore-visibility! (:docked layout))))
        true))))

(defn reset-default-layout! [scene]
  (restore-all! scene)
  (when-let [reset! (:reset! (ui/user-data scene :editor.docking/manager))] (reset!))
  (save-layout! scene))

(defn layout-menu [^Scene scene]
  (let [localize (ui/user-data scene :localization)
        label #(localize (localization/message %))
        menu (doto (Menu. (label "dock.layouts")) (.setId "dock-layouts"))
        save (doto (MenuItem. (label "dock.layout-save")) (.setId "dock-layout-save"))
        switch (doto (Menu. (label "dock.layout-switch")) (.setId "dock-layout-switch"))
        recall (doto (MenuItem. (label "dock.layout-recall")) (.setId "dock-layout-recall"))
        defaults (doto (MenuItem. (label "dock.layout-default")) (.setId "dock-layout-default"))]
    (.setOnAction save
      (ui/event-handler _
        (let [dialog (TextInputDialog.)
              pane (.getDialogPane dialog)]
          (.initOwner dialog (.getWindow scene))
          (.setTitle dialog (label "dock.layout-save"))
          (.setHeaderText dialog (label "dock.layout-name"))
          (.setContentText dialog (label "dock.layout-overwrite"))
          (.setAll (.getStylesheets pane) ^java.util.Collection (.getStylesheets scene))
          (.addAll (.getStylesheets pane) ^java.util.Collection (.getStylesheets (.getRoot scene)))
          (.setStyle pane (.getStyle (.getRoot scene)))
          (let [result (.showAndWait dialog)]
            (when (.isPresent result) (save-named-layout! scene (.get result)))))))
    (.setOnAction recall (ui/event-handler _ (restore-all! scene) (save-layout! scene)))
    (.setOnAction defaults (ui/event-handler _ (reset-default-layout! scene)))
    (.setOnShowing menu
      (ui/event-handler _
        (let [state @(:persistence (manager scene))
              preferences (:prefs state)
              busy (or (nil? preferences) (:restoring state))
              names (when preferences (sort (into [] (coll/keys (prefs/get preferences [:window :named-layouts])))))]
          (doseq [^MenuItem item [save switch recall defaults]] (.setDisable item (boolean busy)))
          (.clear (.getItems switch))
          (doseq [name names]
            (let [item (MenuItem. name)]
              (.setOnAction item (ui/event-handler _ (apply-named-layout! scene name)))
              (.add (.getItems switch) item)))
          (when (zero? (count names)) (.setDisable switch true)))))
    (.addAll (.getItems menu) ^java.util.Collection (vector save switch (SeparatorMenuItem.) recall defaults))
    menu))
