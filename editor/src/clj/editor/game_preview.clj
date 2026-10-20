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
  (:require [dynamo.graph :as g]
            [editor.error-reporting :as error-reporting]
            [editor.resource :as resource]
            [editor.scene :as scene]
            [editor.ui :as ui])
  (:import [com.defold.editor GamePreview]
           [javafx.scene.image ImageView]))

(defn render-size [panel-width panel-height aspect]
  (let [width (min 1536.0 (max 1.0 panel-width) (* (max 1.0 panel-height) aspect))
        height (/ width aspect)
        scale (min 1.0 (/ 1536.0 height))]
    [(max 1 (int (* width scale))) (max 1 (int (* height scale)))]))

(defn attach! [^GamePreview pane app-view project]
  (let [{:keys [render! dispose!]} (scene/make-game-preview-renderer)
        previous (volatile! nil)
        last-error (volatile! nil)]
    (.setIdleRenderer
      pane
      (fn []
        (when-not (ui/ui-disabled?)
          (try
            (g/with-auto-evaluation-context ec
              (let [view (g/node-value app-view :active-view ec)
                    resource (g/node-value app-view :active-resource ec)
                    ext (some-> resource resource/ext)
                    supported (and (contains? #{"gui" "collection" "go"} ext)
                                   view
                                   (g/node-instance? (:basis ec) scene/SceneView view))
                    source-image (when supported (g/node-value view :image-view ec))]
                (cond
                  (not supported)
                  (when-not (= :empty @previous)
                    (vreset! previous :empty)
                    (.setIdleImage pane nil "game-preview.edit"))

                  (nil? (.getImage ^ImageView source-image))
                  (when-not (= [:waiting view] @previous)
                    (vreset! previous [:waiting view])
                    (.setIdleImage pane nil "game-preview.edit-loading"))

                  :else
                  (let [resource-node (g/node-value app-view :active-resource-node ec)
                        gui-dims (when (= "gui" ext)
                                   (let [{:keys [width height]} (g/node-value resource-node :scene-dims ec)]
                                     [(max 1 width) (max 1 height)]))
                        display-dims [(max 1 (g/node-value project :display-width ec))
                                      (max 1 (g/node-value project :display-height ec))]
                        data (g/node-value view :game-render-data ec)
                        states (g/node-value view :updatable-states ec)
                        drawable (g/node-value view :drawable ec)
                        clear-color (g/node-value project :render-clear-color ec)
                        panel-width (.getPreviewWidth pane)
                        panel-height (.getPreviewHeight pane)
                        signature [view data states gui-dims display-dims clear-color drawable panel-width panel-height]]
                    (when-not (= signature @previous)
                      (vreset! previous signature)
                      (let [{:keys [camera aspect mode]} (when-not (or (g/error-value? data) (:error data))
                                                         (scene/game-preview-camera data gui-dims display-dims))]
                        (if-not (and camera drawable)
                          (.setIdleImage pane nil "game-preview.edit-unavailable")
                          (let [[width height] (render-size panel-width panel-height aspect)]
                            (.setIdleImage pane (render! data states camera width height clear-color drawable) mode))))))))
              (vreset! last-error nil))
            (catch Exception error
              (.setIdleImage pane nil "game-preview.edit-unavailable")
              (let [signature [(class error) (.getMessage error)]]
                (when-not (= signature @last-error)
                  (vreset! last-error signature)
                  (error-reporting/report-exception! error)))))))
      dispose!)))
