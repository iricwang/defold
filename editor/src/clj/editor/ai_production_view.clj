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


(ns editor.ai-production-view
  (:require [clojure.java.io :as io]
            [editor.ai-production :as production]
            [util.coll :as coll])
  (:import [javafx.application Platform]
           [javafx.beans.value ChangeListener]
           [javafx.event EventHandler]
           [javafx.scene Group Node Scene]
           [javafx.scene.control Button Label ListView ScrollPane TextField]
           [javafx.scene.image Image ImageView]
           [javafx.scene.input MouseEvent]
           [javafx.scene.layout BorderPane HBox Pane Priority VBox]
           [javafx.scene.shape Rectangle]
           [javafx.stage Stage]))

(set! *warn-on-reflection* true)

(defn- button
  ^Button [text id f]
  (doto (Button. text) (.setId id)
        (.setOnAction (reify EventHandler (handle [_ _] (f))))))

(defn open! [owner source-file initial-plan propose! generate! save-plan!]
  (let [image (production/read-image source-file) w (.getWidth image) h (.getHeight image)
        model (atom (or initial-plan {:version 1 :module "screen" :imageWidth w :imageHeight h :regions []}))
        stage (Stage.) root (BorderPane.) overlay (Pane.) content (Group.)
        scale (min 1.0 (/ 640.0 w) (/ 700.0 h))
        preview (ImageView. (Image. (str (.toURI (io/file source-file)))))
        list (ListView.) module (TextField. (:module @model))
        name-field (TextField.) x-field (TextField.) y-field (TextField.) width-field (TextField.) height-field (TextField.)
        fields [name-field x-field y-field width-field height-field]
        status (doto (Label. "在图上拖动框选区域，或自动识别透明区域；双击区域可选中。") (.setWrapText true))
        right (VBox. 8.0) toolbar (HBox. 8.0) start (atom nil) temporary (Rectangle.)
        selected (atom -1) suppress (atom false)
        render! (fn render-regions []
                  (reset! suppress true)
                  (.setAll (.getItems list) ^java.util.Collection
                           (identity (mapv (fn [r] (str (:name r) "  ·  " (:width r) "×" (:height r))) (:regions @model))))
                  (when (< -1 @selected (count (:regions @model))) (.select (.getSelectionModel list) (int @selected)))
                  (reset! suppress false)
                  (.clear (.getChildren overlay))
                  (.add (.getChildren overlay) temporary)
                  (doseq [[index {:keys [x y width height]}] (eduction (map-indexed vector) (:regions @model))]
                    (let [rect (doto (Rectangle. (double x) (double y) (double width) (double height))
                                 (.setStyle (str "-fx-fill: transparent; -fx-stroke: " (if (= index @selected) "-df-ui-accent" "-df-text-dark") ";"))
                                 (.setStrokeWidth (/ 2.0 scale)))]
                      (.setOnMouseClicked rect
                                          (reify EventHandler (handle [_ e]
                                                                (when (= 2 (.getClickCount ^MouseEvent e))
                                                                  (.select (.getSelectionModel list) (int index))
                                                                  (.consume ^MouseEvent e)))))
                      (.add (.getChildren overlay) rect))))
        persist! (fn save-draft []
                   (swap! model assoc :module (.getText module))
                   (save-plan! @model))
        apply-fields! (fn apply-fields []
                        (when (< -1 @selected (count (:regions @model)))
                          (let [region (merge (get-in @model [:regions @selected])
                                              {:name (.getText name-field) :x (Long/parseLong (.getText x-field)) :y (Long/parseLong (.getText y-field))
                                               :width (Long/parseLong (.getText width-field)) :height (Long/parseLong (.getText height-field))})
                                proposal (assoc-in @model [:regions @selected] region)]
                            (production/normalize-plan proposal w h)
                            (reset! model proposal) (render!) (persist!))))
        apply-button (button "保存区域修改" "production-apply"
                             (fn apply-edit [] (try (apply-fields!) (.setText status "区域已更新") (catch Exception e (.setText status (.getMessage e))))))
        generate-button (button "生成 GUI / Atlas 并预览" "production-generate"
                                (fn generate-assets []
                                  (try
                                    (apply-fields!) (persist!)
                                    (let [plan (production/normalize-plan @model w h)]
                                      (.setText status "正在生成模块资源…")
                                      (generate! plan (fn generation-complete [message]
                                                        (Platform/runLater (fn show-generation [] (.setText status message))))))
                                    (catch Exception e (.setText status (.getMessage e))))))]
    (.flush image)
    (.setId root "ai-production-editor")
    (.setId module "production-module") (.setId list "production-regions")
    (doseq [[field id] (map vector fields ["production-name" "production-x" "production-y" "production-width" "production-height"])] (.setId ^TextField field id))
    (.setPrefSize overlay (double w) (double h))
    (.setStyle temporary "-fx-fill: transparent; -fx-stroke: -df-ui-accent;") (.setMouseTransparent temporary true)
    (.addAll (.getChildren content) ^java.util.Collection (identity [preview overlay]))
    (.add (.getTransforms content) (javafx.scene.transform.Scale. scale scale 0.0 0.0))
    (let [holder (Pane. ^"[Ljavafx.scene.Node;" (into-array Node [content]))]
      (.setPrefSize holder (* w scale) (* h scale))
      (.setStyle holder "-fx-background-color: -df-background-darker;")
      (.setCenter root (ScrollPane. holder)))
    (.setOnMousePressed overlay (reify EventHandler (handle [_ e]
                                                      (let [e ^MouseEvent e p (.sceneToLocal overlay (.getSceneX e) (.getSceneY e))]
                                                        (reset! start [(.getX p) (.getY p)])
                                                        (.setWidth temporary 0.0) (.setHeight temporary 0.0)))))
    (.setOnMouseDragged overlay (reify EventHandler (handle [_ e]
                                                      (when-let [[sx sy] @start]
                                                        (let [e ^MouseEvent e p (.sceneToLocal overlay (.getSceneX e) (.getSceneY e))
                                                              x (max 0.0 (min (double w) (.getX p))) y (max 0.0 (min (double h) (.getY p)))]
                                                          (.setX temporary (min sx x)) (.setY temporary (min sy y))
                                                          (.setWidth temporary (Math/abs (- x sx))) (.setHeight temporary (Math/abs (- y sy))))))))
    (.setOnMouseReleased overlay (reify EventHandler (handle [_ _]
                                                       (when (and @start (>= (.getWidth temporary) 2.0) (>= (.getHeight temporary) 2.0))
                                                         (swap! model update :regions conj {:name (str "component_" (inc (count (:regions @model)))) :role "component" :state "normal"
                                                                                            :x (long (.getX temporary)) :y (long (.getY temporary))
                                                                                            :width (long (.getWidth temporary)) :height (long (.getHeight temporary))})
                                                         (render!) (persist!)
                                                         (.select (.getSelectionModel list) (int (dec (count (:regions @model))))))
                                                       (reset! start nil) (.setWidth temporary 0.0) (.setHeight temporary 0.0))))
    (.addListener (.selectedIndexProperty (.getSelectionModel list))
                  (reify ChangeListener
                    (changed [_ _ _ value]
                      (when-not @suppress
                        (reset! selected (int value))
                        (when-let [region (get (:regions @model) @selected)]
                          (doseq [[field key] (map vector fields [:name :x :y :width :height])]
                            (.setText ^TextField field (str (get region key)))))
                        (render!)))))
    (.addAll (.getChildren toolbar) ^java.util.Collection
             (identity [(Label. "模块") module
                        (button "识别透明区域" "production-detect"
                                (fn detect-alpha []
                                  (.setText status "正在识别透明区域…")
                                  (future
                                    (try
                                      (let [source (production/read-image source-file) regions (production/detect-regions source)]
                                        (.flush source)
                                        (Platform/runLater
                                          (fn display-detected []
                                            (when (.isShowing stage)
                                              (if-not (coll/not-empty regions)
                                                (.setText status "未找到独立透明区域。请框选，或请求 AI 提供语义拆分建议。")
                                                (do (swap! model assoc :regions regions) (reset! selected -1) (render!) (persist!)
                                                    (.setText status (str "识别出 " (count regions) " 个区域，请检查边缘与名称"))))))))
                                      (catch Exception e (Platform/runLater (fn detection-error [] (.setText status (.getMessage e)))))))))
                        (button "AI 拆分建议" "production-propose"
                                (fn propose-regions [] (persist!) (propose! @model) (.close stage)))]))
    (.addAll (.getChildren right) ^java.util.Collection
             (identity [(Label. "区域（按背景 → 前景排列）") list
                        (Label. "语义名称") name-field
                        (Label. "左上角 X / Y")]))
    ;; Keep coordinates directly editable alongside visual rectangle selection.
    (doseq [child [x-field y-field (Label. "宽 / 高") width-field height-field apply-button
                   (button "删除选中区域" "production-remove"
                           (fn remove-region []
                             (when (< -1 @selected (count (:regions @model)))
                               (swap! model update :regions #(into (subvec % 0 @selected) (subvec % (inc @selected))))
                               (reset! selected -1) (render!) (persist!))))
                   (doto (Label. "裁剪保留源图背景和文字；不自动恢复遮挡像素。") (.setWrapText true)) generate-button]]
      (.add (.getChildren right) ^Node child))
    (.setPrefWidth right 310.0) (.setPrefHeight list 220.0) (VBox/setVgrow list Priority/ALWAYS)
    (.setRight root right) (.setTop root toolbar) (.setBottom root status)
    (.setStyle root "-fx-padding: 8; -fx-background-color: -df-background-light;")
    (.setStyle right "-fx-padding: 8;")
    (.initOwner stage owner) (.setTitle stage "Assets → Defold GUI · 拆分与生产")
    (let [scene (Scene. root 1050.0 850.0)]
      ;; Load the editor's full control theme, then inherit owner overrides.
      (.add (.getStylesheets scene) (str (io/resource "editor.css")))
      (when (instance? Stage owner)
        (doseq [stylesheet (.getStylesheets (.getScene ^Stage owner))]
          (when-not (.contains (.getStylesheets scene) stylesheet)
            (.add (.getStylesheets scene) stylesheet))))
      (.setScene stage scene))
    (.setOnHidden stage (reify EventHandler (handle [_ _] (persist!))))
    (render!) (.show stage)
    {:stage stage :root root :model model}))
