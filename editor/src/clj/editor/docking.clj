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
  (:import [javafx.geometry Insets Orientation]
           [javafx.scene Node Scene]
           [javafx.scene.control Label MenuButton MenuItem SplitPane]
           [javafx.scene.input MouseEvent]
           [javafx.scene.layout AnchorPane HBox Priority VBox]))

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
                                            (.setMinSize 80.0 60.0))]
                                (.clear (.getChildren host))
                                (AnchorPane/setTopAnchor split 0.0)
                                (AnchorPane/setBottomAnchor split 0.0)
                                (AnchorPane/setLeftAnchor split 0.0)
                                (AnchorPane/setRightAnchor split 0.0)
                                (.add (.getChildren host) split)
                                [zone split])))
                    [:left :right :bottom])
        panels (into {} (map (fn [[id ^Node body]]
                               (let [title (doto (Label.) (localization/localize! localization (localization/message (str "dock." (name id)))))
                                     menu (doto (MenuButton. "⋮") (.setId (str "dock-menu-" (name id))))
                                     header (doto (HBox. 6.0 (into-array Node [title menu]))
                                              (.setId (str "dock-header-" (name id)))
                                              (.setPadding (Insets. 3.0 6.0 3.0 6.0))
                                              (.setStyle "-fx-background-color: derive(-fx-base, 8%); -fx-cursor: open-hand;"))
                                     wrapper (doto (VBox. 0.0 (into-array Node [header body]))
                                               (.setId (str "dock-panel-" (name id)))
                                               (.setMinSize 80.0 60.0))]
                                 (.setMaxWidth title Double/MAX_VALUE)
                                 (HBox/setHgrow title Priority/ALWAYS)
                                 (VBox/setVgrow body Priority/ALWAYS)
                                 [id {:node wrapper :header header :menu menu}]))) bodies)]
    (letfn [(render! []
              (doseq [[_ ^SplitPane split] zones] (.clear (.getItems split)))
              (doseq [[zone ids] @layout]
                (.setAll (.getItems ^SplitPane (zones zone))
                         ^java.util.Collection (mapv #(get-in panels [% :node]) ids))))
            (move! [id zone]
              (swap! layout move-panel id zone)
              (prefs/set! preferences [:window :panel-layout] @layout)
              (show-zone! zone)
              (render!))]
      (doseq [[id {:keys [^Node header ^MenuButton menu]}] panels]
        (let [gesture (volatile! nil)
              zone-at (fn [x y]
                        (reduce-kv (fn [result zone ^SplitPane split]
                                     (if (and (.getScene split) (.contains split (.sceneToLocal split (double x) (double y))))
                                       (reduced zone)
                                       result)) nil zones))]
          (.setOnMousePressed header
                              (ui/event-handler event
                                (when (and (.isPrimaryButtonDown ^MouseEvent event)
                                           (nil? (ui/closest-node-where #(identical? menu %) (.getTarget event))))
                                  (vreset! gesture {:x (.getSceneX ^MouseEvent event) :y (.getSceneY ^MouseEvent event)}))))
          (.setOnMouseDragged header
                              (ui/event-handler event
                                (when-let [{:keys [x y active]} @gesture]
                                  (let [dx (- (.getSceneX ^MouseEvent event) x)
                                        dy (- (.getSceneY ^MouseEvent event) y)]
                                    (when (or active (> (+ (* dx dx) (* dy dy)) 36.0))
                                      (when-not active (doseq [zone [:left :right :bottom]] (show-zone! zone)))
                                      (let [target (zone-at (.getSceneX ^MouseEvent event) (.getSceneY ^MouseEvent event))]
                                        (vswap! gesture assoc :active true :target target)
                                        (doseq [[zone ^SplitPane split] zones]
                                          (.setStyle split (if (= zone target) "-fx-border-color: #4b9fe1; -fx-border-width: 2;" ""))))
                                      (.consume event))))))
          (.setOnMouseReleased header
                               (ui/event-handler event
                                 (let [{:keys [active target]} @gesture]
                                   (vreset! gesture nil)
                                   (doseq [[_ ^SplitPane split] zones] (.setStyle split ""))
                                   (when active
                                     (when target (move! id target))
                                     (.consume event)))))
          (doseq [zone [:left :right :bottom]]
            (let [item (doto (MenuItem.) (localization/localize! localization (localization/message (str "dock.to-" (name zone)))))]
              (.setOnAction item (ui/event-handler _ (move! id zone)))
              (.add (.getItems menu) item)))))
      (ui/user-data! scene ::manager
                     {:layout layout
                      :splits {"assets-split" (:assets bodies) "right-split" (:inspector bodies)}
                      :reset! (fn []
                                (reset! layout default-layout)
                                (prefs/set! preferences [:window :panel-layout] default-layout)
                                (doseq [zone [:left :right :bottom]] (show-zone! zone))
                                (render!))})
      (render!)
      (.applyCss (.getRoot scene))
      (.layout (.getRoot scene))
      {:panels (into {} (map (fn [[id value]] [id (:node value)])) panels)})))
