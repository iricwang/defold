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


(ns editor.ai-project-browser
  "Project-local creative library and lazy AI archive browser."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.ai-document :as document]
            [editor.craft-project :as craft-project]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [java.io File]
           [java.nio.file Files]
           [javafx.beans.value ChangeListener]
           [javafx.event EventHandler]
           [javafx.scene.control Button ContextMenu Label MenuItem TextInputDialog Tooltip TreeItem TreeView]
           [javafx.scene.input KeyCode MouseButton]
           [javafx.scene.layout HBox Priority VBox]
           [javafx.stage FileChooser]))

(set! *warn-on-reflection* true)

(def sections
  [{:path "01-concept" :title "立项与玩法" :description "游戏定位、核心循环、玩法原型与立项文档"}
   {:path "02-world" :title "世界观与风格" :description "世界设定、剧情、美术方向与风格参考"}
   {:path "03-characters" :title "角色与怪物" :description "角色设定、怪物图鉴、立绘与技能表现"}
   {:path "04-levels" :title "场景与关卡" :description "场景概念、地图、关卡设计与环境资源"}
   {:path "05-systems" :title "玩法系统" :description "战斗、成长、任务、背包与经济系统"}
   {:path "06-interface" :title "界面与交互" :description "界面流程、效果图、GUI 规范与组件设计"}
   {:path "07-motion" :title "动画与特效" :description "动作设计、动画分镜、粒子与技能特效"}
   {:path "08-audio" :title "音频与音乐" :description "配乐、音效、语音与音频制作说明"}
   {:path "09-balancing" :title "数值与配置" :description "数值表、掉落、平衡性与配置规范"}
   {:path "10-delivery" :title "测试与交付" :description "测试计划、验收记录、发布清单与版本说明"}])

(defn ensure-library! [root]
  (doseq [{:keys [path title description]} sections]
    (let [file (craft-project/resource-file root (str "/ai-workspace/library/" path "/README.md"))]
      (io/make-parents file)
      ;; Exclusive creation preserves user edits across repeated opens.
      (when (.createNewFile file)
        (spit file (str "# " title "\n\n" description "。\n")))))
  nil)

(defn- directory-file
  ^File [root relative]
  (when-not (or (= relative "ai-workspace") (string/starts-with? relative "ai-workspace/"))
    (throw (ex-info "只能访问当前项目的 AI 资料目录" {:path relative})))
  (let [file (craft-project/resource-file root (str "/" relative))
        base (.toPath (.getCanonicalFile (io/file root "ai-workspace")))]
    (when-not (.startsWith (.toPath (.getCanonicalFile file)) base)
      (throw (ex-info "资料路径超出 AI 工程目录" {:path relative})))
    file))

(defn entries [root relative]
  (let [directory (directory-file root relative)
        labels {"library" "创作资料" "canvases" "画布与任务" "conversations" "对话记录"
                "attachments" "参考附件" "artifacts" "生成资料" "candidates" "效果图候选" "deliveries" "成品归档"}]
    (->> (coll/into-> (.listFiles directory) []
           (remove (fn hidden-file [^File file]
                     (or (.isHidden file) (Files/isSymbolicLink (.toPath file))
                         (string/ends-with? (.getName file) ".tmp"))))
           (map (fn describe-entry [^File file]
                  (let [name (.getName file)
                        section (when (= relative "ai-workspace/library")
                                  (coll/first-where #(= name (:path %)) sections))]
                    {:path (str relative "/" name)
                     :title (or (:title section) (when (= relative "ai-workspace") (get labels name)) name)
                     :description (:description section)
                     :directory (.isDirectory file)}))))
         (sort-by (juxt #(if (= "ai-workspace/library" (:path %)) 0 (if (:directory %) 1 2)) :path))
         vec)))

(defn import-files! [root relative files]
  (when-not (string/starts-with? relative "ai-workspace/library/")
    (throw (ex-info "请选择创作资料中的分类目录" {})))
  (let [directory (directory-file root relative)]
    (when-not (.isDirectory directory)
      (throw (ex-info "请选择一个目录" {})))
    (doseq [^File file files]
      (when-not (and (.isFile file) (<= (.length file) (* 64 1024 1024)))
        (throw (ex-info "资料必须是文件，且每个不超过 64 MB" {:name (.getName file)}))))
    (mapv (fn copy-file [^File source]
            (let [name (.getName source)
                  dot (.lastIndexOf name ".")
                  stem (if (pos? dot) (subs name 0 dot) name)
                  ext (if (pos? dot) (subs name dot) "")]
              (loop [index 0]
                (let [target (io/file directory (if (zero? index) name (str stem "-" index ext)))
                      copied (try
                               (Files/copy (.toPath source) (.toPath target) ^"[Ljava.nio.file.CopyOption;" (make-array java.nio.file.CopyOption 0))
                               true
                               (catch java.nio.file.FileAlreadyExistsException _ false))]
                  (if copied
                    (str relative "/" (.getName target))
                    (recur (inc index))))))) files)))

(defn- tree-item
  ^TreeItem [root entry expanded show!]
  (let [item (TreeItem. entry)]
    (when (:directory entry)
      (.add (.getChildren item) (TreeItem.))
      (.addListener (.expandedProperty item)
                    (reify ChangeListener
                      (changed [_ _ _ opened]
                        (when opened
                          (try
                            (.setAll (.getChildren item) ^java.util.Collection
                                     (mapv #(tree-item root % expanded show!) (entries root (:path entry))))
                            (catch Exception e (show! (.getMessage e))))))))
      (.setExpanded item (contains? expanded (:path entry))))
    item))

(defn- loaded-items [^TreeItem item]
  (coll/into-> (.getChildren item) [item] (mapcat loaded-items)))

(defn make-browser [root open! changed! show!]
  (ensure-library! root)
  (let [tree (doto (TreeView.) (.setId "ai-project-tree") (.setShowRoot false))
        menu (ContextMenu.)
        create (doto (MenuItem. "新建 Canvas · 文本方案") (.setId "ai-project-new-canvas"))
        title (Label. "AI 工程")
        refresh (doto (Button. "↻") (.setId "ai-project-refresh") (.setTooltip (Tooltip. "刷新工程目录")))
        import (doto (Button. "导入资料") (.setId "ai-project-import") (.setDisable true))
        selected (doto (Label. "选择分类查看资料") (.setWrapText true))
        detail (doto (Label. "ai-workspace") (.setWrapText true))
        bar (HBox. 6.0)
        pane (VBox. 8.0)
        refresh! (fn refresh-project-tree []
                   (let [previous (.getRoot tree)
                         items (if previous (loaded-items previous) [])
                         expanded (coll/into-> items (if previous #{"ai-workspace"} #{"ai-workspace" "ai-workspace/library"})
                                    (filter #(.isExpanded ^TreeItem %))
                                    (map #(:path (.getValue ^TreeItem %))))
                         selection (some-> ^TreeItem (.getSelectedItem (.getSelectionModel tree)) .getValue :path)
                         next-root (tree-item root {:path "ai-workspace" :title "AI 工程" :directory true} expanded show!)]
                     (.setRoot tree next-root)
                     (when-let [item (coll/first-where #(= selection (:path (.getValue ^TreeItem %))) (loaded-items next-root))]
                       (.select (.getSelectionModel tree) ^TreeItem item))))
        open-selected! (fn open-selected-file []
                         (when-let [entry (some-> ^TreeItem (.getSelectedItem (.getSelectionModel tree)) .getValue)]
                           (when-not (:directory entry)
                             (try
                               (directory-file root (:path entry))
                               (open! (str "/" (:path entry)))
                               (catch Exception e (show! (.getMessage e)))))))]
    (.add (.getStyleClass pane) "ai-project-browser")
    (.add (.getStyleClass detail) "ai-muted")
    (.setMinWidth pane 190.0)
    (.setPrefWidth pane 240.0)
    (.setMaxWidth title Double/MAX_VALUE)
    (HBox/setHgrow title Priority/ALWAYS)
    (.addAll (.getChildren bar) ^java.util.Collection (identity [title refresh]))
    (.addAll (.getChildren pane) ^java.util.Collection (identity [bar tree selected detail import]))
    (VBox/setVgrow tree Priority/ALWAYS)
    (ui/cell-factory! tree
                      (fn render-project-entry [entry]
                        {:text (:title entry)
                         :icon (if (:directory entry)
                                 "icons/32/Icons_01-Folder-closed.png"
                                 "icons/64/Icons_29-AT-Unknown.png")
                         :tooltip (Tooltip. (:path entry))}))
    (.addListener (.selectedItemProperty (.getSelectionModel tree))
                  (reify ChangeListener
                    (changed [_ _ _ item]
                      (let [entry (when item (.getValue ^TreeItem item))]
                        (.setText selected (or (:description entry) (:title entry) "选择分类查看资料"))
                        (.setText detail (or (:path entry) "ai-workspace"))
                        (.setDisable import (not (and (:directory entry)
                                                      (string/starts-with? (:path entry) "ai-workspace/library/"))))))))
    (.setOnMouseClicked tree
                        (reify EventHandler
                          (handle [_ event]
                            (when (and (= MouseButton/PRIMARY (.getButton ^javafx.scene.input.MouseEvent event))
                                       (= 2 (.getClickCount ^javafx.scene.input.MouseEvent event)))
                              (open-selected!)))))
    (.setOnKeyPressed tree
                      (reify EventHandler
                        (handle [_ event]
                          (when (= KeyCode/ENTER (.getCode ^javafx.scene.input.KeyEvent event))
                            (open-selected!)
                            (.consume ^javafx.scene.input.KeyEvent event)))))
    (.add (.getItems menu) create)
    (.setContextMenu tree menu)
    (.setOnShowing menu
                   (reify EventHandler
                     (handle [_ _]
                       (let [entry (some-> ^TreeItem (.getSelectedItem (.getSelectionModel tree)) .getValue)]
                         (.setDisable create (not (and (:directory entry) (string/starts-with? (:path entry) "ai-workspace/library/"))))))))
    (.setOnAction create
                  (reify EventHandler
                    (handle [_ _]
                      (let [dialog (TextInputDialog. "核心玩法")
                            entry (.getValue ^TreeItem (.getSelectedItem (.getSelectionModel tree)))]
                        (.setTitle dialog "新建 Canvas")
                        (.setHeaderText dialog (str (:title entry) " · 文本方案模板"))
                        (.setContentText dialog "名称")
                        (.initOwner dialog (.getWindow (.getScene pane)))
                        (.addAll (.getStylesheets (.getDialogPane dialog)) ^java.util.Collection (.getStylesheets (.getScene pane)))
                        (let [answer (.showAndWait dialog)]
                          (when (.isPresent answer)
                            (try
                              (let [path (document/create-document! root (:path entry) (.get answer))]
                                (refresh!) (changed!) (open! path))
                              (catch java.nio.file.FileAlreadyExistsException _ (show! "同名 Canvas 已存在，请换一个名称"))
                              (catch Exception e (show! (.getMessage e))))))))))
    (.setOnAction refresh (reify EventHandler (handle [_ _] (refresh!))))
    (.setOnAction import
                  (reify EventHandler
                    (handle [_ _]
                      (when-let [files (.showOpenMultipleDialog (FileChooser.) (.getWindow (.getScene pane)))]
                        (try
                          (let [entry (.getValue ^TreeItem (.getSelectedItem (.getSelectionModel tree)))]
                            (import-files! root (:path entry) files)
                            (refresh!)
                            (changed!))
                          (catch Exception e (show! (.getMessage e))))))))
    (refresh!)
    {:root pane :refresh! refresh!}))
