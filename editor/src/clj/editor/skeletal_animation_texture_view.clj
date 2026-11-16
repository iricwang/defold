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

(ns editor.skeletal-animation-texture-view
  "Continuous, document-owned output resolution controls."
  (:require [clojure.string :as string]
            [editor.skeletal-animation-textures :as textures]
            [editor.ui :as ui])
  (:import [javafx.application Platform]
           [javafx.beans.value ChangeListener]
           [javafx.geometry Insets]
           [javafx.scene Node]
           [javafx.scene.control Button Label ListView Slider TextField]
           [javafx.scene.layout HBox Priority VBox]))

(set! *warn-on-reflection* true)

(defn make-controls [{get-document :document get-inventory :inventory apply-scale :apply tr :translate}]
  (let [slider (doto (Slider. 1.0 100.0 100.0)
                 (.setId "skeletal-texture-scale-slider")
                 (.setSnapToTicks false)
                 (.setBlockIncrement 1.0)
                 (.setMaxWidth Double/MAX_VALUE))
        field (doto (TextField. "100") (.setId "skeletal-texture-scale-percent") (.setPrefColumnCount 6))
        summary (doto (Label.) (.setId "skeletal-texture-scale-summary") (.setWrapText true))
        status (doto (Label.) (.setId "skeletal-texture-scale-status") (.setWrapText true))
        images (doto (ListView.) (.setId "skeletal-texture-scale-images") (.setPrefHeight 180.0))
        apply-button (doto (Button. (tr "texture-apply")) (.setId "skeletal-texture-scale-apply"))
        rows (atom [])
        request (atom 0)
        update-summary (fn update-texture-summary []
                         (let [factor (/ (.getValue slider) 100.0)
                               sizes (mapv (fn [{:keys [size]}] (textures/dimensions (size 0) (size 1) factor)) @rows)
                               bytes (reduce + 0 (mapv (fn [[width height]] (* 4 width height)) sizes))]
                           (.setText summary (format (tr "texture-estimate") (count sizes) (/ (double bytes) 1048576.0)))
                           (.setAll (.getItems images) ^java.util.Collection
                                    (mapv (fn [{:keys [path size]} [width height]]
                                            (str (peek (string/split path #"/")) "  ·  " (size 0) " × " (size 1) " → " width " × " height "  ·  " path)) @rows sizes))))
        accept-field (fn accept-texture-percent []
                       (let [percent (Double/parseDouble (.trim (.getText field)))]
                         (textures/scale {:texture-output {:scale (/ percent 100.0)}})
                         (.setValue slider percent)))
        refresh (fn refresh-texture-controls []
                  (let [id (swap! request inc)]
                    (.setValue slider (* 100.0 (textures/scale (get-document))))
                    (.setText field (str (.getValue slider)))
                    (.setText status (tr "texture-reading"))
                    (.setDisable apply-button true)
                    (future
                      (try
                        (let [inventory (get-inventory)]
                          (Platform/runLater
                            (fn publish-texture-inventory []
                              (when (= id @request)
                                (reset! rows inventory)
                                (update-summary)
                                (.setText status "")
                                (.setDisable apply-button false)))))
                        (catch Exception error
                          (Platform/runLater
                            (fn show-texture-error []
                              (when (= id @request) (.setText status (.getMessage error))))))))))
        controls (HBox. 8.0 (into-array Node [slider field (Label. "%")]))
        root (doto (VBox. 8.0 (into-array Node [(Label. (tr "texture-output"))
                                                (doto (Label. (tr "texture-help")) (.setWrapText true))
                                                controls summary images status apply-button]))
               (.setPadding (Insets. 8.0)))]
    (HBox/setHgrow slider Priority/ALWAYS)
    (.addListener (.valueProperty slider)
                  (reify ChangeListener
                    (changed [_ _ _ value]
                      (.setText field (Double/toString (double value)))
                      (update-summary))))
    (.setOnAction field (ui/event-handler _ (try (accept-field) (.setText status "")
                                                 (catch Exception error (.setText status (.getMessage error))))))
    (.setOnAction apply-button
                  (ui/event-handler _
                    (try
                      (accept-field)
                      (apply-scale (/ (.getValue slider) 100.0))
                      (.setText status (tr "texture-applied"))
                      (catch Exception error (.setText status (.getMessage error))))))
    {:root root :refresh! refresh}))
