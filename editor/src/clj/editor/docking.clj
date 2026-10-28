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

(ns editor.docking
  (:require [editor.dock-windows :as dock-windows]
            [editor.localization :as localization]
            [editor.prefs :as prefs]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [javafx.geometry Insets Orientation Pos Side]
           [javafx.scene Node Scene]
           [javafx.scene.control Button ContextMenu Label MenuItem SplitPane]
           [javafx.scene.input MouseEvent]
           [javafx.scene.layout AnchorPane Priority StackPane VBox]
           [javafx.scene.paint Color]
           [javafx.scene.shape Rectangle]))

(set! *warn-on-reflection* true)

(def default-layout {:left [:assets] :right [:outline :inspector :preview] :bottom [:tools] :center [:game]})

(defn refresh-windows! [scene evaluation-context]
  (dock-windows/refresh! scene evaluation-context))

(defn valid-layout? [layout]
  (and (map? layout)
       (= #{:left :right :bottom :center} (set (coll/keys layout)))
       (coll/every? vector? (coll/vals layout))
       (= {:assets 1 :outline 1 :inspector 1 :preview 1 :tools 1 :game 1}
          (frequencies (into [] cat (coll/vals layout))))))

(defn migrate-layout [layout]
  (if (and (map? layout)
           (coll/every? vector? (coll/vals layout))
           (= {:assets 1 :inspector 1 :preview 1 :tools 1 :game 1}
              (frequencies (into [] cat (coll/vals layout)))))
    (into {} (map (fn [[zone panels]]
                    [zone (into [] (mapcat #(if (= :inspector %) [:outline :inspector] [%])) panels)])) layout)
    layout))

(defn move-panel [layout panel zone]
  (assert (valid-layout? layout))
  (assert (contains? #{:assets :outline :inspector :preview :tools :game} panel))
  (assert (contains? default-layout zone))
  (update (into {} (map (fn [[key panels]] [key (filterv #(not= panel %) panels)])) layout)
          zone conj panel))

(defn panel-zone [scene panel fallback]
  (if-let [layout (:layout (ui/user-data scene ::manager))]
    (reduce-kv (fn [result zone panels] (if (contains? (set panels) panel) (reduced zone) result)) fallback @layout)
    fallback))

(defn floating-zone? [scene zone]
  (when-let [{:keys [layout floating]} (ui/user-data scene ::manager)]
    (let [ids (get @layout zone)]
      (and (pos? (count ids)) (coll/every? #(contains? @floating %) ids)))))

(defn find-split [^Scene scene id]
  (or (.lookup scene (str "#" id)) (get-in (ui/user-data scene ::manager) [:splits id])))

(defn reset-layout! [scene]
  (dock-windows/restore-all! scene)
  (when-let [reset! (:reset! (ui/user-data scene ::manager))] (reset!)))

(defn split-panel
  "Insert a panel beside a target, dividing the target's share in two."
  [layout sizes panel zone target before]
  (assert (valid-layout? layout))
  (assert (contains? (set (zone layout)) target))
  (if (= panel target)
    {:layout layout :sizes sizes}
    (let [remaining (into {} (map (fn [[key ids]] [key (filterv #(not= panel %) ids)])) layout)
          normalized (into {} (map (fn [[key ids]]
                                     (let [total (transduce (map #(get-in sizes [key %] 1.0)) + ids)]
                                       [key (into {} (map (fn [id] [id (/ (get-in sizes [key id] 1.0) total)])) ids)]))) remaining)
          half (/ (get-in normalized [zone target]) 2.0)]
      {:layout (update remaining zone
                       #(into [] (mapcat (fn [id]
                                           (if (= id target)
                                             (if before [panel target] [target panel])
                                             [id]))) %))
       :sizes (-> normalized (assoc-in [zone target] half) (assoc-in [zone panel] half))})))

(defn drop-half
  "Return the target half in local coordinates without changing layout."
  [width height horizontal x y]
  (if horizontal
    (let [before (< x (/ width 2.0))]
      {:before before :x (if before 0.0 (/ width 2.0)) :y 0.0 :width (/ width 2.0) :height height})
    (let [before (< y (/ height 2.0))]
      {:before before :x 0.0 :y (if before 0.0 (/ height 2.0)) :width width :height (/ height 2.0)})))

(defn panel-rectangle
  "Locate a panel in a planned split, including space released by its old position."
  [ids sizes panel width height horizontal]
  (let [offset (:offset (reduce (fn [result id]
                                  (if (= id panel)
                                    (reduced result)
                                    (update result :offset + (sizes id))))
                                {:offset 0.0} ids))
        share (sizes panel)]
    (if horizontal
      {:x (* width offset) :y 0.0 :width (* width share) :height height}
      {:x 0.0 :y (* height offset) :width width :height (* height share)})))

(defn layout-trees [layout]
  (into {} (map (fn [[zone ids]]
                  [zone (case (count ids)
                          0 nil
                          1 {:tabs ids}
                          {:axis (if (= :bottom zone) :horizontal :vertical)
                           :children (mapv #(hash-map :tabs [%]) ids)})])) layout))

(defn valid-trees? [trees layout]
  (letfn [(valid-node? [tree depth]
            (and (< depth 12)
                 (or (nil? tree)
                     (and (map? tree)
                          (if (:tabs tree)
                            (and (vector? (:tabs tree)) (= 1 (count (:tabs tree))))
                            (and (contains? #{:horizontal :vertical} (:axis tree))
                                 (vector? (:children tree))
                                 (<= 2 (count (:children tree)) 6)
                                 (coll/every? #(and % (valid-node? % (inc depth))) (:children tree))
                                 (or (nil? (:dividers tree))
                                     (and (vector? (:dividers tree))
                                          (= (dec (count (:children tree))) (count (:dividers tree)))
                                          (coll/every? #(and (number? %) (< 0.0 % 1.0)) (:dividers tree))
                                          (apply < 0.0 (:dividers tree))))))))))]
    (and (map? trees)
         (= (set (coll/keys layout)) (set (coll/keys trees)))
         (coll/every? (fn [[zone tree]]
                       (and (valid-node? tree 0)
                            (= (get layout zone) (dock-windows/panel-ids tree)))) trees))))

(defn split-trees [trees panel zone target edge]
  (let [remaining (into {} (map (fn [[key tree]] [key (dock-windows/remove-panel tree panel)])) trees)]
    (update remaining zone dock-windows/insert-panel panel target edge)))

(defn drop-quarter [width height x y]
  (let [horizontal (> (Math/abs (- (/ x width) 0.5))
                      (Math/abs (- (/ y height) 0.5)))
        half (drop-half width height horizontal x y)]
    (assoc half :edge (if horizontal
                       (if (:before half) :left :right)
                       (if (:before half) :top :bottom)))))

(defn- split-positions [^Scene scene]
  (into {} (keep (fn [id]
                   (when-let [^SplitPane split (.lookup scene (str "#" id))]
                     [id (vec (.getDividerPositions split))])))
        ["main-split" "workbench-split" "center-split" "scene-game-split"]))

(defn- apply-split-positions! [^Scene scene positions]
  (doseq [[id values] positions
          :when (and (string? id) (vector? values) (coll/every? #(and (number? %) (< 0 % 1)) values))]
    (when-let [^SplitPane split (.lookup scene (str "#" id))]
      (.setDividerPositions split (double-array values)))))

(defn init! [^Scene scene preferences localization ^Node preview ^Node game set-zone-visible!]
  (let [saved (prefs/get preferences [:window :panel-layout])
        saved (if (and saved (not (contains? saved :center))) (assoc saved :center [:game]) saved)
        saved (migrate-layout saved)
        layout (atom (if (valid-layout? saved) saved default-layout))
        trees (atom (layout-trees @layout))
        floating (atom #{})
        reserved (atom #{})
        home-sizes (atom nil)
        default-positions (split-positions scene)
        outline-split (or (ui/user-data (.lookup scene "#right-split") ::outline-split)
                          (doto (SplitPane.) (.setId "outline-dock-split") (.setMinSize 0.0 0.0)))
        bodies {:outline outline-split
                :assets (.lookup scene "#assets-split")
                :inspector (.lookup scene "#right-split")
                :tools (.lookup scene "#tool-tabs")
                :preview preview
                :game game}
        zones (into {} (map (fn [zone]
                              (let [^AnchorPane host (.lookup scene (str "#" (name zone) "-pane"))
                                    split (doto (SplitPane.)
                                            (.setId (str "dock-" (name zone) "-split"))
                                            (.setOrientation (if (= :bottom zone) Orientation/HORIZONTAL Orientation/VERTICAL))
                                            (.setMinSize 80.0 60.0)
                                            (.setStyle "-fx-padding: 0; -fx-background-insets: 0;"))]
                                (.clear (.getChildren host))
                                (AnchorPane/setTopAnchor split 0.0)
                                (AnchorPane/setBottomAnchor split 0.0)
                                (AnchorPane/setLeftAnchor split 0.0)
                                (AnchorPane/setRightAnchor split 0.0)
                                (.add (.getChildren host) split)
                                [zone split])))
                    [:left :right :bottom :center])
        panels (into {} (map (fn [[id ^Node body]]
                               (let [menu (doto (Button. "…")
                                            (.setId (str "dock-menu-" (name id)))
                                            (.setFocusTraversable false)
                                            (.setMinSize 24.0 22.0)
                                            (.setPrefSize 24.0 22.0)
                                            (.setMaxSize 24.0 22.0)
                                            (.setStyle "-fx-min-width: 24px; -fx-pref-width: 24px; -fx-max-width: 24px; -fx-min-height: 22px; -fx-max-height: 22px; -fx-background-color: transparent; -fx-border-width: 0; -fx-padding: 0; -fx-opacity: 0.65; -fx-font-size: 16px;"))
                                     title (when (= :preview id)
                                             (doto (Label.)
                                               (.setId "dock-header-preview")
                                               (localization/localize! localization (localization/message "dock.preview"))
                                               (.setMinHeight 26.0)
                                               (.setPadding (Insets. 0.0 30.0 0.0 8.0))))
                                     content (if title
                                               (doto (VBox. 0.0 (into-array Node [title body]))
                                                 (.setMinSize 0.0 0.0))
                                               body)
                                     panel (doto (StackPane. (into-array Node [content menu]))
                                             (.setId (str "dock-panel-" (name id)))
                                             (.setMinSize 80.0 60.0))]
                                 (when title (VBox/setVgrow body Priority/ALWAYS))
                                 (StackPane/setAlignment menu Pos/TOP_RIGHT)
                                 (StackPane/setMargin menu (Insets. 0.0 0.0 0.0 0.0))
                                 [id {:node panel :menu menu :popup (ContextMenu.)}]))) bodies)
        root ^StackPane (.getRoot scene)
        indicator (doto (Rectangle.)
                    (.setId "dock-drop-indicator")
                    (.setManaged false)
                    (.setMouseTransparent true)
                    (.setVisible false)
                    (.setFill (Color/web "#4b9fe1" 0.12))
                    (.setStroke (Color/web "#4b9fe1"))
                    (.setStrokeWidth 2.0))]
    (.add (.getChildren root) indicator)
    (letfn [(clear-split! [^SplitPane split]
              (doseq [child (vec (.getItems split))
                      :when (instance? SplitPane child)]
                (clear-split! child))
              (.clear (.getItems split)))
            (build-tree [tree]
              (if-let [id (first (:tabs tree))]
                (when (or (contains? @reserved id) (not (contains? @floating id)))
                  (get-in panels [id :node]))
                (let [children (into [] (keep build-tree) (:children tree))]
                  (case (count children)
                    0 nil
                    1 (first children)
                    (let [split (doto (SplitPane.)
                                  (.setMinSize 80.0 60.0)
                                  (.setStyle "-fx-padding: 0; -fx-background-insets: 0;")
                                  (.setOrientation (if (= :horizontal (:axis tree)) Orientation/HORIZONTAL Orientation/VERTICAL)))]
                      (.setAll (.getItems split) ^java.util.Collection children)
                      (.setDividerPositions split
                                            (double-array (mapv #(/ % (double (count children)))
                                                                (range 1 (count children)))))
                      (when (= (count children) (count (:children tree)))
                        (ui/user-data! split ::tree-ids (dock-windows/panel-ids tree))
                        (when-let [dividers (:dividers tree)]
                          (.setDividerPositions split (double-array dividers))))
                      split)))))
            (capture-trees! []
              (let [positions (into {} (keep (fn [^Node node]
                                              (when-let [ids (ui/user-data node ::tree-ids)]
                                                [ids (vec (.getDividerPositions ^SplitPane node))])))
                                    (into [] (mapcat #(.lookupAll ^Node % ".split-pane")) (coll/vals zones)))]
                (letfn [(capture [tree]
                          (if (:children tree)
                            (cond-> (update tree :children #(mapv capture %))
                              (get positions (dock-windows/panel-ids tree))
                              (assoc :dividers (get positions (dock-windows/panel-ids tree))))
                            tree))]
                  (swap! trees #(into {} (map (fn [[zone tree]] [zone (capture tree)])) %)))))
            (render! [_sizes]
              (doseq [[_ split] zones] (clear-split! split))
              (doseq [[zone tree] @trees]
                (let [^SplitPane split (zones zone)
                      node (build-tree tree)]
                  (when node (.add (.getItems split) node))
                  (set-zone-visible! zone (boolean node)))))
            (capture-sizes []
              (capture-trees!)
              nil)
            (refresh-home! [id]
              (capture-trees!)
              (render! @home-sizes)
              (doseq [[zone ids] @layout
                      :when (contains? (set ids) id)]
                (set-zone-visible! zone (coll/any? #(or (contains? @reserved %) (not (contains? @floating %))) ids))))
            (reserve-home! [id]
              (when-not (contains? @reserved id)
                (let [^StackPane slot (get-in panels [id :node])]
                  (swap! reserved conj id)
                  (ui/user-data! slot :editor.dock-windows/reserved-home true)
                  (.add (.getChildren slot)
                        (doto (Label. (localization (localization/message (str "dock." (name id)))))
                          (.setId (str "dock-home-" (name id)))
                          (ui/add-style! "dock-home-placeholder")
                          (.setMaxSize Double/MAX_VALUE Double/MAX_VALUE)
                          (.setMouseTransparent true)
                          (.setStyle "-fx-opacity: 0.65; -fx-background-color: derive(-fx-base, 5%); -fx-border-color: -fx-box-border; -fx-border-style: segments(4, 4); -fx-border-insets: 3;")))
                  (refresh-home! id))))
            (release-home! [id]
              (when (contains? @reserved id)
                (let [^StackPane slot (get-in panels [id :node])]
                  (swap! reserved disj id)
                  (ui/user-data! slot :editor.dock-windows/reserved-home false)
                  (.removeIf (.getChildren slot)
                             (reify java.util.function.Predicate
                               (test [_ child] (.contains (.getStyleClass ^Node child) "dock-home-placeholder"))))
                  (refresh-home! id))))
            (save! [] (prefs/set! preferences [:window :panel-layout] @layout))
            (move! [id zone]
              (dock-windows/restore-slot! (get-in panels [id :node]))
              (capture-trees!)
              (swap! trees #(into {} (map (fn [[key tree]] [key (dock-windows/remove-panel tree id)])) %))
              (swap! trees update zone
                     #(if % {:axis (if (= :bottom zone) :horizontal :vertical)
                             :children [% {:tabs [id]}]} {:tabs [id]}))
              (swap! layout move-panel id zone)
              (save!)
              (set-zone-visible! zone true)
              (render! nil))
            (cancel! []
              (dock-windows/cancel-drag! scene)
              (.setVisible indicator false))
            (target-at [source x y]
              (reduce-kv
                (fn [result zone ^SplitPane split]
                  (if (and (.getScene split) (.contains split (.screenToLocal split (double x) (double y))))
                    (reduced
                      (or (reduce (fn [result id]
                                    (let [^Node panel (get-in panels [id :node])
                                          point (.screenToLocal panel (double x) (double y))]
                                      (if (and point (.contains panel point))
                                        (reduced (if (= source id)
                                                   (when (contains? @reserved id)
                                                     {:home true :zone zone :node panel :x 0.0 :y 0.0
                                                      :width (.getWidth (.getLayoutBounds panel))
                                                      :height (.getHeight (.getLayoutBounds panel))})
                                                   (merge {:zone zone :target id :node panel}
                                                          (drop-quarter (.getWidth (.getLayoutBounds panel))
                                                                        (.getHeight (.getLayoutBounds panel))
                                                                        (.getX point) (.getY point)))))
                                        result))) nil (filterv #(or (contains? @reserved %) (not (contains? @floating %))) (zone @layout)))
                          (when (zero? (count (zone @layout)))
                            {:zone zone :node split :x 0.0 :y 0.0
                             :width (.getWidth split) :height (.getHeight split)})))
                    result)) nil zones))
            (show-target! [{:keys [^Node node x y width height] :as target}]
              (.setVisible indicator (boolean target))
              (when target
                (let [point (.sceneToLocal root (.localToScene node (double x) (double y)))]
                  (.setX indicator (+ 1.0 (.getX point)))
                  (.setY indicator (+ 1.0 (.getY point)))
                  (.setWidth indicator (max 0.0 (- width 2.0)))
                  (.setHeight indicator (max 0.0 (- height 2.0)))
                  (.toFront indicator))))
            (drop! [panel {:keys [zone target edge home]}]
              (dock-windows/restore-slot! (get-in panels [panel :node]))
              (when-not home
                (if target
                  (do
                    (capture-trees!)
                    (swap! trees split-trees panel zone target edge)
                    (reset! layout (into {} (map (fn [[zone tree]] [zone (dock-windows/panel-ids tree)])) @trees))
                    (save!)
                    (render! nil))
                  (move! panel zone))))]
      (render! nil)
      (doseq [[id {:keys [node ^ContextMenu popup]}] panels]
        (when-let [items (.get (.getProperties ^Node (bodies id)) "dock-menu-items")]
          (.addAll (.getItems popup) ^java.util.Collection items))
        (dock-windows/register! node (localization (localization/message (str "dock." (name id))))
                               {:persistent-key [:panel id]
                                :title-property (.get (.getProperties ^Node (bodies id)) "dock-title")
                                :menu-items (.getItems popup)
                                :reserve-home! #(reserve-home! id)
                                :release-home! #(release-home! id)
                                :home-visible! (fn [visible]
                                                 (when (zero? (count @floating))
                                                   (reset! home-sizes (capture-sizes)))
                                                 (swap! floating (if visible disj conj) id)
                                                 (when visible
                                                   (swap! reserved disj id)
                                                   (ui/user-data! node :editor.dock-windows/reserved-home false))
                                                 (refresh-home! id))}))
        (doseq [[id {:keys [^Node node ^Button menu ^ContextMenu popup]}] panels]
          (.setOnAction menu (ui/event-handler _ (.show popup menu Side/BOTTOM 0.0 0.0)))
          (doseq [zone [:left :right :bottom :center]]
            (let [item (doto (MenuItem.) (localization/localize! localization (localization/message (str "dock.to-" (name zone)))))]
              (.setOnAction item (ui/event-handler _ (move! id zone)))
              (.add (.getItems popup) item)))
          ;; Reuse the existing title/tab row. The overlay menu consumes no content space.
          (.addEventFilter node MouseEvent/MOUSE_PRESSED
            (ui/event-handler event
              (when (and (.isPrimaryButtonDown ^MouseEvent event)
                         (or (< (.getY (.sceneToLocal node (.getSceneX ^MouseEvent event) (.getSceneY ^MouseEvent event))) 28.0)
                             (ui/closest-node-where
                               #(or (.contains (.getStyleClass ^Node %) "fake-titled-pane-label")
                                    (.contains (.getStyleClass ^Node %) "title")
                                    (.contains (.getStyleClass ^Node %) "tab-header-area"))
                               (.getTarget event)))
                         (nil? (ui/closest-node-where #(or (instance? javafx.scene.control.ButtonBase %)
                                                           (instance? javafx.scene.control.ComboBoxBase %)
                                                           (instance? javafx.scene.control.TextInputControl %)
                                                           (instance? javafx.scene.control.Slider %)
                                                           (identical? menu %)) (.getTarget event))))
                (dock-windows/start-slot-drag! node (.getScreenX ^MouseEvent event) (.getScreenY ^MouseEvent event))))))

      (ui/user-data! scene ::manager
                     {:layout layout
                      :target-at target-at
                      :show-target! show-target!
                      :drop! drop!
                      :trees trees
                      :floating floating
                      :reserved reserved
                      :splits {"assets-split" (:assets bodies) "right-split" (:inspector bodies) "outline-dock-split" (:outline bodies)}
                      :snapshot! (fn [] (capture-trees!)
                                   {:layout @layout :trees @trees
                                    :positions (or (ui/user-data scene :editor.dock-windows/collapsed-home-positions)
                                                   (split-positions scene))
                                    :hidden (into #{} (filter #(and (nil? (.lookup scene (str "#" (name %) "-pane")))
                                                                    (not (floating-zone? scene %))))
                                                  [:left :right :bottom :center])})
                      :restore-visibility! (fn [snapshot]
                                             (doseq [zone (:hidden snapshot)
                                                     :when (contains? default-layout zone)]
                                               (set-zone-visible! zone false)))
                      :apply! (fn [snapshot]
                                (when (valid-layout? (migrate-layout (:layout snapshot)))
                                  (cancel!)
                                  (reset! layout (migrate-layout (:layout snapshot)))
                                  (reset! trees (if (valid-trees? (:trees snapshot) @layout)
                                                  (:trees snapshot)
                                                  (layout-trees @layout)))
                                  (reset! home-sizes (:sizes snapshot))
                                  (save!)
                                  (doseq [zone [:left :right :bottom :center]] (set-zone-visible! zone true))
                                  (render! (:sizes snapshot))
                                  (apply-split-positions! scene (:positions snapshot))))
                      :reset! (fn []
                                (cancel!)
                                (reset! layout default-layout)
                                (reset! trees (layout-trees default-layout))
                                (reset! home-sizes nil)
                                (save!)
                                (doseq [zone [:left :right :bottom :center]] (set-zone-visible! zone true))
                                (render! nil)
                                (apply-split-positions! scene default-positions))})
      (render! nil)
      (.applyCss root)
      (.layout root)
      (dock-windows/install-game-collapse! scene (get-in panels [:game :node])
                                           #(split-positions scene)
                                           #(apply-split-positions! scene %))
      {:panels (into {} (map (fn [[id value]] [id (:node value)])) panels)})))
