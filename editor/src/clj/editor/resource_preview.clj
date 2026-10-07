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

(ns editor.resource-preview
  (:require [clojure.java.io :as io]
            [dynamo.graph :as g]
            [editor.defold-project :as project]
            [editor.localization :as localization]
            [editor.resource :as resource]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [java.io File]
           [javafx.beans.value ChangeListener]
           [javafx.geometry Insets]
           [javafx.scene Node]
           [javafx.scene.control Button Label Slider ToggleButton TreeItem TreeView]
           [javafx.scene.image Image ImageView]
           [javafx.scene.layout HBox Priority StackPane VBox]
           [javafx.scene.media Media MediaPlayer MediaPlayer$Status]
           [javafx.util Duration]))

(set! *warn-on-reflection* true)

(defonce ^:private providers (atom []))

(defn register-provider!
  "Registers a preview provider. Later registrations take precedence.
  :accept? receives a resource type; :create! receives context and resource,
  and returns {:node Node :dispose! fn}. Creation and disposal run on the FX
  thread. Providers must not edit resources or add undo steps."
  [id provider]
  (swap! providers #(conj (filterv (fn [entry] (not= id (:id entry))) %)
                          (assoc provider :id id))))

(defn provider-for [resource-type]
  (coll/first-where #((:accept? %) resource-type) (rseq @providers)))

(defn- message-label
  ^Label [localization key]
  (doto (Label.)
    (.setWrapText true)
    (localization/localize! localization (localization/message key))))

(defn- image-pane [^Image image flipped]
  (let [view (doto (ImageView. image)
               (.setId "resource-preview-image")
               (.setPreserveRatio true)
               (.setSmooth true)
               (.setScaleY (if flipped -1.0 1.0)))
        pane (doto (StackPane. (into-array Node [view]))
               (.setMinSize 0.0 0.0)
               (.setPrefSize 320.0 240.0))]
    (.bind (.fitWidthProperty view) (.widthProperty pane))
    (.bind (.fitHeightProperty view) (.heightProperty pane))
    {:node pane
     :dispose! (fn []
                 (.unbind (.fitWidthProperty view))
                 (.unbind (.fitHeightProperty view))
                 (.setImage view nil))}))

(defn- create-scene-preview! [{:keys [workspace project app-view]} resource]
  (let [resource-type (resource/resource-type resource)
        view-type (coll/first-where :make-preview-fn (:view-types resource-type))
        resource-node (project/get-resource-node project resource)
        opts (assoc (get (:view-opts resource-type) (:id view-type))
               :app-view app-view :project project :workspace workspace
               :select-fn (fn [_selection _op-seq]) :inherit-selection false)
        preview ((:make-preview-fn view-type) resource-node opts 640 480)]
    (try
      (let [image (g/node-value preview :image)]
        (when (g/error-value? image)
          (throw (ex-info "Unable to render this resource" {})))
        (image-pane image true))
      (finally
        (try
          (when-let [dispose! (:dispose-preview-fn view-type)] (dispose! preview))
          (finally (g/transact {:undoable false} (g/delete-node preview))))))))

(defn- create-image-preview! [_context resource]
  (with-open [input (io/input-stream resource)]
    (let [image (Image. input 1024.0 1024.0 true true)]
      (when (.isError image) (throw (.getException image)))
      (image-pane image false))))

(defn- create-audio-preview! [{:keys [localization]} resource]
  (let [file (doto (File/createTempFile "defold-preview-" (str "." (resource/ext resource))) (.deleteOnExit))
        _ (try (with-open [input (io/input-stream resource)] (io/copy input file))
               (catch Exception error (.delete file) (throw error)))
        ^MediaPlayer player (try (MediaPlayer. (Media. (str (.toURI file))))
                                 (catch Exception error (.delete file) (throw error)))
        play (doto (Button.)
               (.setId "resource-preview-play")
               (localization/localize! localization (localization/message "resource-preview.play")))
        stop (doto (Button.)
               (.setId "resource-preview-stop")
               (localization/localize! localization (localization/message "resource-preview.stop")))
        time (Label. "0:00.0 / 0:00.0")
        seek (doto (Slider. 0.0 1.0 0.0) (.setId "resource-preview-seek") (.setDisable true))
        status (message-label localization "resource-preview.audio-loading")
        pane (doto (VBox. 10.0 (into-array Node [(HBox. 8.0 (into-array Node [play stop time])) seek status]))
               (.setPadding (Insets. 12.0)))
        format-time (fn [^Duration duration]
                      (let [seconds (if (or (.isUnknown duration) (.isIndefinite duration)) 0 (double (max 0.0 (.toSeconds duration))))]
                        (format "%d:%04.1f" (long (/ seconds 60)) (double (mod seconds 60)))))]
    (.setOnAction play (ui/event-handler _
                         (if (= MediaPlayer$Status/PLAYING (.getStatus player))
                           (.pause player)
                           (.play player))))
    (.setOnAction stop (ui/event-handler _ (.stop player)))
    (.setOnReady player (fn []
                          (.setDisable seek false)
                          (.setMax seek (.toSeconds (.getTotalDuration player)))
                          (.setText status (resource/resource-name resource))
                          (.setText time (str "0:00.0 / " (format-time (.getTotalDuration player))))))
    (.setOnEndOfMedia player (fn [] (.stop player)))
    (.setOnError player (fn []
                          (.setDisable play true)
                          (.setDisable seek true)
                          (.setText status (str (.getError player)))))
    (ui/observe (.statusProperty player)
                (fn [_ _ value]
                  (localization/localize! play localization
                                          (localization/message (if (= MediaPlayer$Status/PLAYING value)
                                                                  "resource-preview.pause" "resource-preview.play")))))
    (ui/observe (.currentTimeProperty player)
                (fn [_ _ ^Duration value]
                  (when-not (.isValueChanging seek) (.setValue seek (.toSeconds value)))
                  (.setText time (str (format-time value) " / " (format-time (.getTotalDuration player))))))
    (.setOnMouseReleased seek (ui/event-handler _ (.seek player (Duration/seconds (.getValue seek)))))
    {:node pane :dispose! (fn [] (.stop player) (.dispose player) (.delete file))}))

(register-provider! :scene {:accept? #(coll/any? :make-preview-fn (:view-types %)) :create! create-scene-preview!})
(register-provider! :image {:accept? #(contains? #{"png" "jpg" "jpeg" "gif" "bmp"} (:ext %)) :create! create-image-preview!})
(register-provider! :audio {:accept? #(contains? #{"wav" "mp3" "m4a" "aif" "aiff"} (:ext %)) :create! create-audio-preview!})

(defn- set-content! [^StackPane content ^Node node]
  (.clear (.getChildren content))
  (.add (.getChildren content) node))

(defn make-pane!
  "Creates a selection-driven preview. The returned dispose! must be called on
  project close. Pinning freezes the selected resource, not an editor graph node."
  [{:keys [localization open-resource] :as context} ^TreeView assets]
  (let [state (atom {:generation 0})
        pinned (doto (ToggleButton.)
                 (.setId "resource-preview-pin")
                 (localization/localize! localization (localization/message "resource-preview.pin")))
        open (doto (Button.)
               (.setDisable true)
               (localization/localize! localization (localization/message "resource-preview.open")))
        refresh (doto (Button.)
                  (.setDisable true)
                  (localization/localize! localization (localization/message "resource-preview.refresh")))
        details (doto (Label.) (.setWrapText true) (.setId "resource-preview-details"))
        content (doto (StackPane.) (.setMinSize 0.0 0.0) (.setId "resource-preview-content"))
        pane (doto (VBox. 8.0 (into-array Node [(HBox. 6.0 (into-array Node [pinned open refresh])) details content]))
               (.setId "resource-preview")
               (.setPadding (Insets. 8.0))
               (.setMinSize 180.0 150.0))]
    (VBox/setVgrow content Priority/ALWAYS)
    (letfn [(show! [selected]
              (when-let [dispose! (:dispose! @state)] (dispose!))
              (let [generation (:generation (swap! state #(-> % (assoc :selected selected) (dissoc :dispose!) (update :generation inc))))
                    file (when (and (resource/resource? selected) (= :file (resource/source-type selected))) selected)]
                (.setDisable open (nil? file))
                (.setDisable refresh (nil? file))
                (.setText details (if file (str (resource/proj-path file) "\n" (resource/ext file)) ""))
                (set-content! content (message-label localization (if file "resource-preview.loading" "resource-preview.empty")))
                (when file
                  (ui/run-later
                    (when (= generation (:generation @state))
                      (try
                        (if-let [provider (provider-for (resource/resource-type file))]
                          (let [{:keys [node dispose!]} ((:create! provider) context file)]
                            (set-content! content node)
                            (swap! state assoc :dispose! dispose!))
                          (set-content! content (message-label localization (if (contains? #{"ogg" "opus"} (resource/ext file)) "resource-preview.audio-unsupported" "resource-preview.unsupported"))))
                        (catch Exception error
                          (let [label (message-label localization "resource-preview.failed")]
                            (.setText label (str (.getText label) "\n" (.getMessage error)))
                            (set-content! content label)))))))))]
      (let [selection (.selectedItemProperty (.getSelectionModel assets))
            listener (reify ChangeListener
                       (changed [_ _ _ item]
                         (when-not (.isSelected pinned) (show! (some-> ^TreeItem item .getValue)))))]
        (.addListener selection listener)
        (.setOnAction pinned (ui/event-handler _
                               (when-not (.isSelected pinned)
                                 (show! (some-> ^TreeItem (.getValue selection) .getValue)))))
        (.setOnAction refresh (ui/event-handler _ (show! (:selected @state))))
        (.setOnAction open (ui/event-handler _ (when-let [resource (:selected @state)] (open-resource resource))))
        (show! (some-> ^TreeItem (.getValue selection) .getValue))
        {:node pane
         :dispose! (fn []
                     (.removeListener selection listener)
                     (swap! state update :generation inc)
                     (when-let [dispose! (:dispose! @state)] (dispose!)))}))))
