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

(ns integration.game-preview-test
  (:require [cljfx.api :as fx]
            [clojure.test :refer [deftest is]]
            [dynamo.graph :as g]
            [editor.scene :as scene]
            [integration.test-util :as test-util])
  (:import [java.util Arrays]
           [javafx.scene Scene]
           [javafx.scene.control Tab]
           [javafx.scene.image Image PixelFormat]
           [javafx.scene.layout AnchorPane]
           [javafx.stage Stage]))

;; Reusing a larger frame after a resize must not shrink the GUI inside an old canvas.
(deftest resized-preview-matches-fresh-render
  @(fx/on-fx-thread
     (test-util/with-loaded-project "test/resources/gui_preview_project"
       (let [root (AnchorPane.)
             stage (doto (Stage.)
                     (.setScene (Scene. root 800 650))
                     (.setX -10000)
                     (.setY -10000))
             renderer (scene/make-game-preview-renderer)]
         (try
           (.show stage)
           (let [[_ view] (#'test-util/make-tab! project app-view "/parent.gui"
                            (fn [node]
                              (scene/make-view root node
                                {:prefs (test-util/make-build-stage-test-prefs)
                                 :app-view app-view
                                 :project project
                                 :manual-refresh? true
                                 :tab (Tab.)})))]
             (.applyCss root)
             (.layout root)
             (dotimes [_ 3] (scene/refresh-scene-view! view 0.0))
             (g/with-auto-evaluation-context ec
               (let [data (g/node-value view :game-render-data ec)
                     states (g/node-value view :updatable-states ec)
                     drawable (g/node-value view :drawable ec)
                     render (fn [width height image]
                              (let [camera (:camera (scene/game-preview-camera data [width height] [320 240] [width height]))]
                                ((:render! renderer) data states camera width height [0.05 0.1 0.15 1.0] drawable image)))]
                 (reduce (fn [^Image previous [width height]]
                           (let [^Image reused (render width height previous)
                                 ^Image fresh (render width height nil)]
                             (is (= [(double width) (double height)] [(.getWidth reused) (.getHeight reused)]))
                             (when previous
                               (is (= (= [(double width) (double height)] [(.getWidth previous) (.getHeight previous)])
                                      (identical? previous reused))))
                             (let [actual (int-array (* width height))
                                   expected (int-array (* width height))
                                   format (PixelFormat/getIntArgbInstance)]
                               (.getPixels (.getPixelReader reused) 0 0 width height format actual 0 width)
                               (.getPixels (.getPixelReader fresh) 0 0 width height format expected 0 width)
                               (is (Arrays/equals actual expected)))
                             reused))
                         nil [[495 661] [365 487] [365 487] [487 225] [225 487] [100 100] [660 495] [365 487]]))))
           (finally
             ((:dispose! renderer))
             (.close stage)))))))
