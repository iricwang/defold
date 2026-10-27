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

(ns editor.game-preview
  (:require [clojure.string :as string]
            [dynamo.graph :as g]
            [editor.defold-project :as project]
            [editor.error-reporting :as error-reporting]
            [editor.gui-preview :as gui-preview]
            [editor.resource :as resource]
            [editor.scene :as scene]
            [editor.ui :as ui])
  (:import [com.defold.editor GamePreview]
           [java.util.function Consumer]
           [javafx.scene.image ImageView]))

(defn render-size [panel-width panel-height aspect]
  (let [width (min 1536.0 (max 1.0 panel-width) (* (max 1.0 panel-height) aspect))
        height (/ width aspect)
        scale (min 1.0 (/ 1536.0 height))]
    [(max 1 (int (* width scale))) (max 1 (int (* height scale)))]))

(defn retain-preview
  "Most-recent images first; bound both image memory and retained graph snapshots."
  [entries entry]
  (loop [remaining (into [entry] (remove #(= (:view entry) (:view %))) entries)
         retained []
         bytes 0]
    (if-let [candidate (first remaining)]
      (let [next-bytes (+ bytes (:bytes candidate))]
        (if (or (= 8 (count retained)) (> next-bytes (* 32 1024 1024)))
          retained
          (recur (subvec remaining 1) (conj retained candidate) next-bytes)))
      retained)))

(defn gui-source-path
  "Validate a runtime GUI resource path before resolving it in the current project."
  [path]
  (when (and (string? path)
             (string/starts-with? path "/")
             (string/ends-with? path ".guic")
             (not (re-find #"[\\\x00]|(?:^|/)\.\.?(?:/|$)" path)))
    (subs path 0 (dec (count path)))))

(defn attach! [^GamePreview pane app-view project]
  (let [{:keys [render! dispose!]} (scene/make-game-preview-renderer)
        previous (volatile! nil)
        selected (volatile! nil)
        images (volatile! [])
        last-error (volatile! nil)]
    (.setRevealGuiResource
      pane
      (reify Consumer
        (accept [_ path]
          (when-let [source-path (gui-source-path path)]
            (when-let [resource-node (project/get-resource-node project source-path)]
              (let [target (g/node-value resource-node :resource)]
                (when (resource/exists? target)
                  (ui/run-command (.getNode pane) :file.show-in-assets target))))))))
    (.setIdleRenderer
      pane
      (fn []
        (when-not (ui/ui-disabled?)
          (try
            (g/with-auto-evaluation-context ec
              ;; Never retain a closed SceneView or its graph/render data in the cache.
              (vswap! images #(filterv (fn [entry] (g/node-exists? (:basis ec) (:view entry))) %))
              (when (and (:view @selected) (not (g/node-exists? (:basis ec) (:view @selected))))
                (vreset! selected nil)
                (.unlockPreview pane))
              (when (or (not (.isPreviewLocked pane)) (nil? (:view @selected)))
                (let [view (g/node-value app-view :active-view ec)
                      resource (g/node-value app-view :active-resource ec)]
                  ;; Unsupported tabs leave the last live preview target selected.
                  ;; Keeping the target, rather than only its image, also preserves device updates.
                  (when (and view
                             (contains? #{"gui" "collection" "go"} (some-> resource resource/ext))
                             (g/node-instance? (:basis ec) scene/SceneView view))
                    (vreset! selected {:view view
                                       :resource resource
                                       :resource-node (g/node-value app-view :active-resource-node ec)}))))
              (let [{:keys [view resource resource-node]} @selected
                    ext (some-> resource resource/ext)
                    supported (and (contains? #{"gui" "collection" "go"} ext)
                                   view
                                   (g/node-instance? (:basis ec) scene/SceneView view))
                    source-image (when supported (g/node-value view :image-view ec))]
                (cond
                  (not supported)
                  (do
                    (.unlockPreview pane)
                    (when-not (= :empty @previous)
                      (vreset! previous :empty)
                      (.setPreviewResource pane "")
                      (.setIdleImage pane nil "game-preview.edit")))

                  (nil? (.getImage ^ImageView source-image))
                  (when-not (= [:waiting view] @previous)
                    (vreset! previous [:waiting view])
                    (.setPreviewResource pane (resource/proj-path resource))
                    (.setIdleImage pane nil "game-preview.edit-loading"))

                  :else
                  (let [started (System/nanoTime)
                        gui-dims (when (= "gui" ext)
                                   (let [{:keys [width height]} (g/node-value resource-node :scene-dims ec)]
                                     [(max 1 width) (max 1 height)]))
                        display-dims [(max 1 (g/node-value project :display-width ec))
                                      (max 1 (g/node-value project :display-height ec))]
                        device-dims (some-> (.getPreviewDeviceSize pane) vec)
                        safe-insets (vec (.getPreviewSafeInsets pane))
                        gui-config (when gui-dims (g/node-value resource-node :game-preview-config ec))
                        profile-node (when gui-config
                                       (project/get-resource-node project
                                         (get-in gui-config [:settings ["display" "display_profiles"]]
                                                 "/builtins/render/default.display_profiles") ec))
                        auto-layout (if profile-node (g/node-value profile-node :auto-layout-selection ec) true)
                        physical-size (or device-dims display-dims)
                        data (g/node-value view :game-render-data ec)
                        states (g/node-value view :updatable-states ec)
                        drawable (g/node-value view :drawable ec)
                        clear-color (g/node-value project :render-clear-color ec)
                        {:keys [camera aspect mode]} (when-not (or (g/error-value? data) (:error data))
                                                     (scene/game-preview-camera data (when gui-dims physical-size) display-dims device-dims))
                        size (when aspect (render-size (.getPreviewWidth pane) (.getPreviewHeight pane) aspect))
                        signature [view data states gui-config auto-layout safe-insets gui-dims display-dims device-dims clear-color drawable size]]
                    (when-not (= signature @previous)
                      (.setPreviewResource pane (resource/proj-path resource))
                      (if-not (and camera drawable)
                        (.setIdleImage pane nil "game-preview.edit-unavailable")
                        (let [cached (reduce (fn [_ entry] (when (= view (:view entry)) (reduced entry))) nil @images)
                              hit (= signature (:signature cached))
                              [width height] size
                              render-data (if hit
                                            nil
                                            (if gui-config
                                              (gui-preview/render-data resource-node view gui-config physical-size safe-insets auto-layout ec)
                                              data))
                              info (if hit (:gui-preview-info cached) (:gui-preview-info render-data))
                              image (if hit
                                      (:image cached)
                                      (render! render-data
                                               states camera width height clear-color drawable (:image cached)))
                              entry {:view view :signature signature :image image :bytes (* 4 width height)
                                     :gui-preview-info info}]
                          (vswap! images retain-preview entry)
                          (.setIdleImage pane image mode)
                          (let [[reference-width reference-height] (:reference-size info)
                                [device-width device-height] (:device-size info)]
                            (.setPreviewLayout pane (or (:layout info) "")
                                               (int (or reference-width 0)) (int (or reference-height 0))
                                               (int (or device-width 0)) (int (or device-height 0))))
                          (.recordPreviewFrame pane hit (transduce (map :bytes) + 0 @images) (- (System/nanoTime) started))))
                      (vreset! previous signature)))))
              (vreset! last-error nil))
            (catch Exception error
              (.setIdleImage pane nil "game-preview.edit-unavailable")
              (let [signature [(class error) (.getMessage error)]]
                (when-not (= signature @last-error)
                  (vreset! last-error signature)
                  (error-reporting/report-exception! error)))))))
      (fn []
        (vreset! images [])
        (vreset! selected nil)
        (vreset! previous nil)
        (dispose!)))))
