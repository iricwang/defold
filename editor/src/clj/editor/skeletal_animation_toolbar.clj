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

(ns editor.skeletal-animation-toolbar
  "Compact, shared animation transport and key editing controls."
  (:require [clojure.java.io :as io]
            [editor.skeletal-animation-navigation :as navigation]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [javafx.beans.value ChangeListener]
           [javafx.geometry Pos Side]
           [javafx.scene Node]
           [javafx.scene.control Button ButtonBase CheckMenuItem ContentDisplay ContextMenu CustomMenuItem MenuButton Slider ToggleButton ToolBar Tooltip]
           [javafx.scene.canvas Canvas]
           [javafx.scene.input MouseButton MouseEvent]
           [javafx.scene.image Image ImageView WritableImage]
           [javafx.scene.layout FlowPane HBox Priority Region VBox]
           [javafx.scene.paint Color]
           [javafx.scene.shape SVGPath]))

(set! *warn-on-reflection* true)

(def ^:private style-sheet (io/resource "skeletal-animation-toolbar.css"))

(def ^:private paths
  {:filter "M1 2 L15 2 L10 8 L10 14 L6 12 L6 8 Z"
   :lock "M4 7 L4 4 C4 -1 12 -1 12 4 L12 7 M3 7 L13 7 L13 15 L3 15 Z M8 10 L8 12"
   :refresh "M2 7 A6 6 0 0 1 13 4 L13 0 M13 4 L9 4 M14 9 A6 6 0 0 1 3 12 L3 16 M3 12 L7 12"
   :select "M2 1 L4 14 L8 10 L12 14 L14 12 L10 8 L15 5 Z"
   :shift "M1 8 L9 8 M6 4 L10 8 L6 12 M12 2 L12 14 M15 2 L15 14"
   :offset "M1 8 L6 3 L6 13 Z M15 8 L10 3 L10 13 Z M8 1 L8 15"
   :auto "M2 3 L2 13 L6 13 M14 13 L14 3 L10 3 M4 9 C6 1 10 15 12 7"
   :heart "M8 14 C-3 7 0 -2 8 4 C16 -2 19 7 8 14 Z"
   :store "M1 3 C7 3 4 7 8 7 C12 7 9 3 15 3 M1 13 C7 13 4 9 8 9 C12 9 9 13 15 13 M8 1 L8 5 M8 11 L8 15"
   :swap "M1 4 L14 4 L11 1 M14 4 L11 7 M15 12 L2 12 L5 9 M2 12 L5 15"
   :play "M3 1 L15 8 L3 15 Z"
   :pause "M4 2 L4 14 M11 2 L11 14"
   :start "M2 2 L2 14 M14 2 L5 8 L14 14 Z"
   :end "M14 2 L14 14 M2 2 L11 8 L2 14 Z"
   :previous "M11 2 L3 8 L11 14 Z"
   :next "M5 2 L13 8 L5 14 Z"
   :previous-key "M2 2 L2 14 M13 3 L6 8 L13 13 Z"
   :next-key "M14 2 L14 14 M3 3 L10 8 L3 13 Z"
   :loop "M3 5 C4 1 11 1 13 5 L13 1 M13 5 L9 5 M13 11 C11 15 4 15 2 11 L2 15 M2 11 L6 11"
   :copy "M5 2 L13 2 L13 12 L5 12 Z M2 5 L2 15 L10 15"
   :paste "M5 3 L2 3 L2 15 L14 15 L14 3 L11 3 M5 1 L11 1 L11 5 L5 5 Z"
   :cut "M3 2 A2 2 0 1 0 3 6 A2 2 0 1 0 3 2 M3 10 A2 2 0 1 0 3 14 A2 2 0 1 0 3 10 M5 5 L14 13 M5 11 L14 3"
   :delete "M3 3 L13 13 M13 3 L3 13"
   :key "M5 2 L10 7 L5 12 L0 7 Z M10 7 L16 7 M13 7 L13 11 M16 7 L16 10"
   :changed "M8 1 L15 8 L8 15 L1 8 Z M8 4 L8 9 M8 12 L8 13"
   :record "M8 3 A5 5 0 1 0 8 13 A5 5 0 1 0 8 3"
   :fit "M1 6 L1 1 L6 1 M10 1 L15 1 L15 6 M15 10 L15 15 L10 15 M6 15 L1 15 L1 10"})

(def ^:private reference-sheet (io/resource "skeletal-animation-reference-controls.png"))

(def ^:private reference-icons
  (delay
    (when-let [url reference-sheet]
      (let [source (Image. (str url))
            reader (.getPixelReader source)
            positions {:filter [12 12] :lock [49 12] :refresh [77 12] :select [106 12] :key [141 12]
                       :copy [188 12] :cut [217 12] :delete [247 12] :paste [276 12]
                       :stepped [309 12] :linear [339 12] :bezier [369 12] :smooth [405 12]
                       :flat [462 12] :ease-in [434 12] :ease-out [492 12]
                       :shift [535 12] :offset [598 12] :fit [674 12] :auto [721 12]
                       :heart [787 12] :record [821 12] :store [859 12] :swap [921 12]
                       :start [12 54] :previous-key [42 54] :previous [76 54] :play [103 54]
                       :next [139 54] :end [168 54] :loop [202 54]}]
        (coll/into-> positions {} (map (fn [[kind [x y]]]
                                         (let [image (WritableImage. 17 17)
                                               writer (.getPixelWriter image)
                                               background (.getColor reader x y)]
                                           ;; Remove flat background.
                                           (dotimes [dy 17]
                                             (dotimes [dx 17]
                                               (let [color (.getColor reader (+ x dx) (+ y dy))
                                                     distance (max (abs (- (.getRed color) (.getRed background)))
                                                                   (abs (- (.getGreen color) (.getGreen background)))
                                                                   (abs (- (.getBlue color) (.getBlue background))))]
                                                 (.setColor writer dx dy (if (< distance 0.055) Color/TRANSPARENT
                                                                             (if (contains? #{:copy :cut :delete :paste :stepped :linear :bezier :smooth :flat :ease-in :ease-out :swap :refresh} kind)
                                                                               (.interpolate color Color/WHITE 0.5) color))))))
                                           [kind image]))))))))

(defn icon [kind]
  (if-let [image (get @reference-icons kind)]
    (doto (ImageView. ^Image image) (.setSmooth false) (.setFitWidth 17.0) (.setFitHeight 17.0)
          (ui/add-style! "animation-reference-icon"))
    (doto (SVGPath.)
      (.setContent (get paths kind (:play paths)))
      (.setFill Color/TRANSPARENT)
      (.setStroke (Color/web "#eeeeee"))
      (.setStrokeWidth 1.4))))

(defn- compact! [^ButtonBase control kind title text]
  (.setTooltip control (Tooltip. (str title
                                      (get {"skeletal-play" " · Space" "skeletal-rewind" " · Home"
                                            "skeletal-toolbar-end" " · End"
                                            "skeletal-previous-frame" " · ←" "skeletal-next-frame" " · →"
                                            "skeletal-previous-key" " · Alt+←" "skeletal-next-key" " · Alt+→"}
                                           (.getId control) ""))))
  (.setAccessibleText control title)
  (.setFocusTraversable control false)
  (.setContentDisplay control (if text ContentDisplay/LEFT ContentDisplay/GRAPHIC_ONLY))
  (when text (.setText control text))
  (when kind (.setGraphic control (icon kind)))
  (.add (.getStyleClass control) (if text "animation-text-tool" "animation-icon-tool"))
  control)

(defn- group
  ^HBox [nodes]
  (doto (HBox. 2.0 (into-array Node nodes))
    (.setAlignment Pos/CENTER_LEFT)
    (ui/add-style! "animation-tool-group")))

(defn- ruler-step [width start span fps]
  (let [largest-frame (long (Math/ceil (max (abs (* start fps)) (abs (* (+ start span) fps)))))
        label-width (max 48.0 (+ 14.0 (* 7.0 (count (str largest-frame)))))
        needed (max 1.0 (/ (* fps span label-width) (max 1.0 (- width 80.0))))
        magnitude (Math/pow 10.0 (Math/floor (Math/log10 needed)))
        fraction (/ needed magnitude)]
    (* magnitude (cond (<= fraction 1.0) 1.0 (<= fraction 2.0) 2.0 (<= fraction 5.0) 5.0 :else 10.0))))

(defn install!
  [{:keys [^ToolBar playback ^ToolBar curve-strip ^Node root state tr actions
           animation-choice ^javafx.scene.control.TextField current-frame ^Node time-label graph-visible? read-ruler!]
    :or {graph-visible? (constantly true)}}]
  (let [controls (coll/into-> (.getItems playback) {} (map (fn [^Node node] [(.getId node) node])))
        curve-controls (vec (.getItems curve-strip))
        ^Button play (get controls "skeletal-play")
        action (fn make-action [id kind title]
                 (let [control (doto (Button.) (.setId (str "skeletal-toolbar-" id)))]
                   (.setOnAction control (ui/event-handler _ ((get actions (keyword id)))))
                   (compact! control kind (tr title) nil)))
        toggle (fn make-toggle [id kind text]
                 (let [control (doto (ToggleButton.) (.setId (str "skeletal-toolbar-" id)))]
                   (.setOnAction control (ui/event-handler _
                                           (swap! state assoc (keyword (str "graph-" id)) (.isSelected control))
                                           (when-not (= "bias" id) (when-let [f (get actions (keyword id))] (f)))))
                   (compact! control kind (tr (str "toolbar-" id)) (when text (tr (str "toolbar-" id))))))
        ^Button filter-button (action "filter" :filter "toolbar-filter")
        ^ToggleButton lock-button (toggle "lock" :lock false)
        ^Button refresh-button (action "refresh" :refresh "toolbar-refresh")
        ^Button select-button (action "select" :select "toolbar-select")
        ^ToggleButton shift-button (toggle "shift" :shift true)
        ^ToggleButton offset-button (toggle "offset" :offset true)
        ^ToggleButton bias-button (toggle "bias" :heart false)
        ^Button store-button (action "store" :store "toolbar-store")
        ^Button swap-button (action "swap" :swap "toolbar-swap")
        bias (doto (Slider. -1.0 1.0 0.0) (.setId "skeletal-toolbar-bias-slider") (.setPrefWidth 160.0))
        ruler (doto (Canvas. 600.0 30.0) (.setId "skeletal-toolbar-ruler"))
        ruler-host (doto (HBox. 0.0 (into-array Node [ruler])) (.setMinWidth 0.0))
        loop-button (doto (ToggleButton.) (.setId "skeletal-toolbar-loop"))
        auto-key (doto (ToggleButton.) (.setId "skeletal-toolbar-auto-key"))
        cut (action "cut" :cut "cut-keys")
        key (action "key" :key "key")
        end (action "end" :end "last-frame")
        ^Button copy (get controls "skeletal-copy-keys")
        ^Button paste (get controls "skeletal-paste-keys")
        ^Button delete (get controls "skeletal-delete-key")
        ^ButtonBase frame (get (coll/into-> curve-controls {} (map (fn [^Node node] [(.getId node) node]))) "skeletal-curve-frame")
        ^ButtonBase auto-frame (get (coll/into-> curve-controls {} (map (fn [^Node node] [(.getId node) node]))) "skeletal-curve-auto")
        top (doto (FlowPane. 5.0 2.0) (.setId "skeletal-animation-actions"))
        bottom (doto (HBox. 0.0) (.setId "skeletal-animation-transport"))
        rows (doto (VBox. 2.0 (into-array Node [top bottom])) (.setId "skeletal-animation-controls") (.setMinWidth 0.0))
        refresh (fn refresh-toolbar []
                  (let [current @state selected (pos? (count (:selected-keys current))) playing (:playing current)]
                    (doseq [^Button control [cut copy delete]] (.setDisable control (not selected)))
                    (.setDisable refresh-button (not (:graph-lock current)))
                    (.setSelected lock-button (boolean (:graph-lock current)))
                    (.setSelected shift-button (boolean (:graph-shift current)))
                    (.setSelected offset-button (boolean (:graph-offset current)))
                    (.setDisable swap-button (nil? (get-in current [:graph-stored (:animation current)])))
                    (.setVisible bias (boolean (:graph-bias current)))
                    (.setManaged bias (boolean (:graph-bias current)))
                    (when read-ruler!
                      (let [{:keys [duration time fps channels view-range]} (read-ruler!)
                            start (double (or (first view-range) 0.0))
                            span (double (if view-range (- (second view-range) start) (max 0.001 (or duration 1.0))))
                            fps (double (or fps 60.0))
                            width (.getWidth ruler)
                            scale (/ (max 1.0 (- width 80.0)) span)
                            step (ruler-step width start span fps)
                            gc (.getGraphicsContext2D ruler)]
                        (.setFill gc (Color/web "#474747")) (.fillRect gc 0.0 0.0 width 30.0)
                        (.setFont gc (javafx.scene.text.Font/font 11.0))
                        (doseq [frame (range (* step (Math/ceil (/ (* start fps) step))) (+ (* (+ start span) fps) step) step)
                                :let [x (+ 16.0 (* scale (- (/ frame fps) start)))]]
                          (.setStroke gc (Color/web "#888888")) (.strokeLine gc x 19.0 x 30.0)
                          (.setFill gc (Color/web "#ababab"))
                          (when (> (abs (- frame (* (or time 0.0) fps))) 0.5)
                            (.fillText gc (str (long frame)) (- x 3.0) 11.0))
                          (dotimes [minor 5]
                            (let [mx (+ x (* (/ step 5.0 fps) minor scale))]
                              (.strokeLine gc mx 24.0 mx 30.0))))
                        (.setFill gc (Color/web "#ed7026"))
                        (doseq [t (coll/into-> channels #{} (mapcat :keys) (map :time))
                                :let [x (+ 16.0 (* scale (- t start)))]]
                          (.fillPolygon gc (double-array [(- x 4.0) x (+ x 4.0) x]) (double-array [25.0 21.0 25.0 29.0]) 4))
                        (let [x (+ 16.0 (* scale (- (or time 0.0) start)))]
                          (.setStroke gc (Color/web "#7edddd")) (.strokeLine gc x 13.0 x 30.0)
                          (.setFill gc (Color/web "#7edddd"))
                          (.fillPolygon gc (double-array [(- x 5.0) (+ x 5.0) x]) (double-array [13.0 13.0 18.0]) 3)
                          (.fillText gc (str (long (Math/round (* (or time 0.0) fps)))) (+ x 4.0) 11.0))))
                    (.setDisable paste (nil? (:key-clipboard current)))
                    (.setDisable ^Button key (not (navigation/single-node? current)))
                    (.setDisable frame (not (graph-visible?)))
                    (.setDisable auto-frame (not (graph-visible?)))
                    (.setSelected loop-button (not (false? (:loop current))))
                    (.setSelected auto-key (boolean (:auto-key current)))
                    (when (not= playing (ui/user-data play ::playing))
                      (ui/user-data! play ::playing playing)
                      (.setGraphic play (icon (if playing :pause :play)))
                      (.setText (.getTooltip play) (str (tr (if playing "pause" "play")) " · Space"))
                      (.setAccessibleText play (tr (if playing "pause" "play"))))))]
    ;; Detach before regrouping: JavaFX nodes must have exactly one parent.
    (.clear (.getItems playback))
    (.clear (.getItems curve-strip))
    (.setVisible curve-strip false) (.setManaged curve-strip false)
    (doseq [[id kind title] [["rewind" :start "rewind"] ["previous-key" :previous-key "previous-key"]
                             ["previous-frame" :previous "previous-frame"] ["play" :play "play"]
                             ["next-frame" :next "next-frame"] ["next-key" :next-key "next-key"]
                             ["copy-keys" :copy "copy-keys"] ["paste-keys" :paste "paste-keys"]
                             ["delete-key" :delete "delete-key"]]]
      (compact! (get controls (str "skeletal-" id)) kind (tr title) nil))
    (doseq [[^ButtonBase control kind] (mapv vector (subvec curve-controls 0 7) [:stepped :linear :bezier :smooth :flat :ease-in :ease-out])]
      (.setGraphic control (icon kind))
      (ui/add-style! control "animation-icon-tool"))
    (compact! frame :fit (.getText frame) (tr "toolbar-frame"))
    (compact! auto-frame :auto (.getText auto-frame) (tr "toolbar-auto-frame"))
    (compact! loop-button :loop (tr "loop") nil)
    (compact! auto-key :record (tr "auto-key") nil)
    (compact! store-button :store (tr "toolbar-store") (tr "toolbar-store"))
    (.setOnAction loop-button (ui/event-handler _ (swap! state assoc :loop (.isSelected loop-button))))
    (.setOnAction auto-key (ui/event-handler _ (swap! state assoc :auto-key (.isSelected auto-key))))
    (.addAll (.getChildren top) ^"[Ljavafx.scene.Node;"
             (into-array Node [(group [filter-button lock-button refresh-button select-button key])
                               (group (into [copy cut delete paste] (subvec curve-controls 0 7)))
                               (group [shift-button offset-button])
                               (group [frame auto-frame])
                               (group [bias-button]) (group [auto-key])
                               (group [store-button swap-button]) bias]))
    (let [transport (group [(get controls "skeletal-rewind") (get controls "skeletal-previous-key")
                            (get controls "skeletal-previous-frame") play (get controls "skeletal-next-frame")
                            end loop-button])]
      (.setId transport "skeletal-transport-buttons")
      (.setMinWidth transport 230.0) (.setPrefWidth transport 230.0) (.setMaxWidth transport 230.0)
      (.addAll (.getChildren bottom) ^"[Ljavafx.scene.Node;" (into-array Node [transport ruler-host])))
    ;; Clip/frame entry stays available without wrapping the toolbar.
    (let [menu (doto (MenuButton. "⋯") (.setId "skeletal-clip-controls") (.setFocusTraversable false))]
      (.add (.getItems menu) (CustomMenuItem. (group [animation-choice current-frame]) false))
      (.add (.getChildren top) menu))
    (.setOnAction filter-button
                  (ui/event-handler _
                    (let [menu (ContextMenu.)]
                      (doseq [property ["x" "y" "rotation" "scale-x" "scale-y" "shear-x" "shear-y"]]
                        (let [item (doto (CheckMenuItem. (tr (str "curve-" property)))
                                     (.setSelected (not (contains? (:graph-hidden-properties @state) property))))]
                          (.setOnAction item (ui/event-handler _
                                               (swap! state update :graph-hidden-properties
                                                      (fn [hidden] ((if (.isSelected item) disj conj) (or hidden #{}) property)))))
                          (.add (.getItems menu) item)))
                      (.show menu filter-button Side/BOTTOM 0.0 2.0))))
    (.setTooltip current-frame (Tooltip. (tr "current-frame")))
    (.setVisible time-label false) (.setManaged time-label false)
    (HBox/setHgrow ruler-host Priority/ALWAYS)
    (.bind (.widthProperty ruler) (.widthProperty ruler-host))
    (.addListener (.widthProperty ruler) (reify ChangeListener (changed [_ _ _ _] (refresh))))
    (.addListener (.valueChangingProperty bias)
                  (reify ChangeListener
                    (changed [_ _ before after]
                      (when (and before (not after))
                        (when-let [f (:bias actions)] (f (.getValue bias)))
                        (.setValue bias 0.0)))))
    (let [scrub (ui/event-handler event
                  (when (and read-ruler! (or (= MouseButton/PRIMARY (.getButton ^MouseEvent event)) (.isPrimaryButtonDown ^MouseEvent event)))
                    (let [{:keys [duration view-range fps]} (read-ruler!)
                          start (or (first view-range) 0.0)
                          span (if view-range (- (second view-range) start) (max 0.001 (or duration 1.0)))
                          time (max 0.0 (+ start (* span (/ (- (.getX ^MouseEvent event) 16.0) (max 1.0 (- (.getWidth ruler) 80.0))))))
                          time (if (.isShiftDown ^MouseEvent event) time (/ (Math/round (* time (or fps 60.0))) (or fps 60.0)))]
                      (swap! state assoc :playing false :mode "animate" :time time :preview-time time)
                      (refresh))))]
      (.setOnMousePressed ruler scrub) (.setOnMouseDragged ruler scrub))
    (.bind (.prefWrapLengthProperty top) (.widthProperty rows))
    (.setId playback "skeletal-animation-toolbar")
    (.add (.getItems playback) rows)
    (.bind (.prefWidthProperty rows) (.subtract (.widthProperty playback) 14.0))
    (.setMinHeight playback Region/USE_PREF_SIZE) (.setPrefHeight playback Region/USE_COMPUTED_SIZE) (.setMaxHeight playback Region/USE_PREF_SIZE)
    (when-let [style-sheet style-sheet]
      (let [styles (.getStylesheets ^javafx.scene.Parent root) url (str style-sheet)]
        (when-not (.contains styles url) (.add styles url))))
    (ui/user-data! play ::playing ::unset)
    (refresh)
    {:refresh! refresh :rows rows :loop loop-button :auto-key auto-key}))
