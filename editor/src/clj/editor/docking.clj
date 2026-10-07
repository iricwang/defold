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
  (:require [editor.localization :as localization]
            [editor.prefs :as prefs]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [javafx.geometry Insets Orientation Pos Side]
           [javafx.scene Node Scene]
           [javafx.scene.control Button ContextMenu Label MenuItem SplitPane]
           [javafx.scene.input KeyCode KeyEvent MouseEvent]
           [javafx.scene.layout AnchorPane Priority StackPane VBox]
           [javafx.scene.paint Color]
           [javafx.scene.shape Rectangle]))

(set! *warn-on-reflection* true)

(def default-layout {:left [:assets] :right [:inspector :preview] :bottom [:tools]})

(defn valid-layout? [layout]
  (and (map? layout)
       (= #{:left :right :bottom} (set (coll/keys layout)))
       (coll/every? vector? (coll/vals layout))
       (= {:assets 1 :inspector 1 :preview 1 :tools 1}
          (frequencies (into [] cat (coll/vals layout))))))

(defn move-panel [layout panel zone]
  (assert (valid-layout? layout))
  (assert (contains? #{:assets :inspector :preview :tools} panel))
  (assert (contains? default-layout zone))
  (update (into {} (map (fn [[key panels]] [key (filterv #(not= panel %) panels)])) layout)
          zone conj panel))

(defn panel-zone [scene panel fallback]
  (if-let [layout (:layout (ui/user-data scene ::manager))]
    (reduce-kv (fn [result zone panels] (if (contains? (set panels) panel) (reduced zone) result)) fallback @layout)
    fallback))

(defn find-split [^Scene scene id]
  (or (.lookup scene (str "#" id)) (get-in (ui/user-data scene ::manager) [:splits id])))

(defn reset-layout! [scene]
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

(defn init! [^Scene scene preferences localization ^Node preview show-zone!]
  (let [saved (prefs/get preferences [:window :panel-layout])
        layout (atom (if (valid-layout? saved) saved default-layout))
        bodies {:assets (.lookup scene "#assets-split")
                :inspector (.lookup scene "#right-split")
                :tools (.lookup scene "#tool-tabs")
                :preview preview}
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
                    [:left :right :bottom])
        panels (into {} (map (fn [[id ^Node body]]
                               (let [menu (doto (Button. "…")
                                            (.setId (str "dock-menu-" (name id)))
                                            (.setFocusTraversable false)
                                            (.setMinSize 24.0 22.0)
                                            (.setPrefSize 24.0 22.0)
                                            (.setMaxSize 24.0 22.0)
                                            (.setStyle "-fx-background-color: transparent; -fx-border-width: 0; -fx-padding: 0; -fx-opacity: 0.65; -fx-font-size: 16px;"))
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
                                 (StackPane/setMargin menu (Insets. 1.0 4.0 0.0 0.0))
                                 [id {:node panel :menu menu}]))) bodies)
        root ^StackPane (.getRoot scene)
        indicator (doto (Rectangle.)
                    (.setId "dock-drop-indicator")
                    (.setManaged false)
                    (.setMouseTransparent true)
                    (.setVisible false)
                    (.setFill (Color/web "#4b9fe1" 0.12))
                    (.setStroke (Color/web "#4b9fe1"))
                    (.setStrokeWidth 2.0))
        gesture (volatile! nil)]
    (.add (.getChildren root) indicator)
    (letfn [(render! [sizes]
              (doseq [[_ ^SplitPane split] zones] (.clear (.getItems split)))
              (doseq [[zone ids] @layout]
                (let [^SplitPane split (zones zone)]
                  (.setAll (.getItems split) ^java.util.Collection (mapv #(get-in panels [% :node]) ids))
                  (when (and sizes (> (count ids) 1))
                    (.setDividerPositions split
                                          (double-array (:positions (reduce (fn [{:keys [total positions]} id]
                                                                              (let [next-total (+ total (get-in sizes [zone id]))]
                                                                                {:total next-total :positions (conj positions next-total)}))
                                                                            {:total 0.0 :positions []} (pop ids)))))))))
            (capture-sizes []
              (into {} (map (fn [[zone ids]]
                              (let [^SplitPane split (zones zone)
                                    boundaries (conj (into [0.0] (.getDividerPositions split)) 1.0)]
                                [zone (into {} (map-indexed (fn [index id]
                                                              [id (- (boundaries (inc index)) (boundaries index))])) ids)]))) @layout))
            (save! [] (prefs/set! preferences [:window :panel-layout] @layout))
            (move! [id zone]
              (swap! layout move-panel id zone)
              (save!)
              (show-zone! zone)
              (render! nil))
            (cancel! []
              (vreset! gesture nil)
              (.setVisible indicator false))
            (target-at [source x y]
              (reduce-kv
                (fn [result zone ^SplitPane split]
                  (if (and (.getScene split) (.contains split (.sceneToLocal split (double x) (double y))))
                    (reduced
                      (or (reduce (fn [result id]
                                    (let [^Node panel (get-in panels [id :node])
                                          point (.sceneToLocal panel (double x) (double y))]
                                      (if (.contains panel point)
                                        (reduced (when (not= source id)
                                                   (merge {:zone zone :target id :node panel}
                                                          (drop-half (.getWidth (.getLayoutBounds panel))
                                                                     (.getHeight (.getLayoutBounds panel))
                                                                     (= :bottom zone) (.getX point) (.getY point)))))
                                        result))) nil (zone @layout))
                          (when (zero? (count (zone @layout)))
                            {:zone zone :node split :x 0.0 :y 0.0
                             :width (.getWidth split) :height (.getHeight split)})))
                    result)) nil zones))]
      (doseq [[id {:keys [^Node node ^Button menu]}] panels]
        (let [popup (ContextMenu.)]
          (.setOnAction menu (ui/event-handler _ (.show popup menu Side/BOTTOM 0.0 0.0)))
          (doseq [zone [:left :right :bottom]]
            (let [item (doto (MenuItem.) (localization/localize! localization (localization/message (str "dock.to-" (name zone)))))]
              (.setOnAction item (ui/event-handler _ (move! id zone)))
              (.add (.getItems popup) item))))
        ;; Reuse the existing title/tab row. The overlay menu consumes no content space.
        (.addEventFilter node MouseEvent/MOUSE_PRESSED
          (ui/event-handler event
            (when (and (.isPrimaryButtonDown ^MouseEvent event)
                       (< (.getY (.sceneToLocal node (.getSceneX ^MouseEvent event) (.getSceneY ^MouseEvent event))) 28.0)
                       (nil? (ui/closest-node-where #(identical? menu %) (.getTarget event))))
              (vreset! gesture {:panel id :x (.getSceneX ^MouseEvent event) :y (.getSceneY ^MouseEvent event)}))))
        (.addEventFilter node MouseEvent/MOUSE_DRAGGED
          (ui/event-handler event
            (when-let [{:keys [panel x y active]} @gesture]
              (let [dx (- (.getSceneX ^MouseEvent event) x)
                    dy (- (.getSceneY ^MouseEvent event) y)]
                (when (or active (> (+ (* dx dx) (* dy dy)) 36.0))
                  (when-not active (doseq [zone [:left :right :bottom]] (show-zone! zone)))
                  (let [{:keys [^Node node x y width height] :as target}
                        (target-at panel (.getSceneX ^MouseEvent event) (.getSceneY ^MouseEvent event))]
                    (vswap! gesture assoc :active true :target target)
                    (.setVisible indicator (boolean target))
                    (when target
                      (let [point (.sceneToLocal root (.localToScene node (double x) (double y)))]
                        (.setX indicator (+ 1.0 (.getX point)))
                        (.setY indicator (+ 1.0 (.getY point)))
                        (.setWidth indicator (max 0.0 (- width 2.0)))
                        (.setHeight indicator (max 0.0 (- height 2.0)))
                        (.toFront indicator))))
                  (.consume event))))))
        (.addEventFilter node MouseEvent/MOUSE_RELEASED
          (ui/event-handler event
            (let [{:keys [panel active target]} @gesture]
              (cancel!)
              (when active
                (when-let [{:keys [zone target before]} target]
                  (if target
                    (let [{new-layout :layout sizes :sizes}
                          (split-panel @layout (capture-sizes) panel zone target before)]
                      (reset! layout new-layout)
                      (save!)
                      (render! sizes))
                    (move! panel zone)))
                (.consume event))))))
      (.addEventFilter scene KeyEvent/KEY_PRESSED
        (ui/event-handler event
          (when (and @gesture (= KeyCode/ESCAPE (.getCode ^KeyEvent event)))
            (cancel!)
            (.consume event))))
      (ui/user-data! scene ::manager
                     {:layout layout
                      :splits {"assets-split" (:assets bodies) "right-split" (:inspector bodies)}
                      :reset! (fn []
                                (cancel!)
                                (reset! layout default-layout)
                                (save!)
                                (doseq [zone [:left :right :bottom]] (show-zone! zone))
                                (render! nil))})
      (render! nil)
      (.applyCss root)
      (.layout root)
      {:panels (into {} (map (fn [[id value]] [id (:node value)])) panels)})))
