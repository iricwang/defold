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
  "Project-local creative documents and editable project configuration."
  (:require [cljfx.api :as fx]
            [cljfx.fx.tree-cell :as fx.tree-cell]
            [cljfx.fx.tree-view :as fx.tree-view]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.ai-canvas :as canvas]
            [editor.ai-document :as document]
            [editor.ai-roles :as roles]
            [editor.craft-project :as craft-project]
            [editor.file-reveal :as file-reveal]
            [editor.resource :as resource]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [com.defold.control ExtendedTreeViewSkin]
           [java.io File]
           [java.nio.file Files]
           [javafx.application Platform]
           [javafx.beans.value ChangeListener]
           [javafx.event EventHandler]
           [javafx.scene.control ContextMenu Label MenuItem SeparatorMenuItem TextInputDialog TreeCell TreeItem TreeView]
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
        labels {"config" "项目配置" "library" "创作资料"}
        section (coll/first-where #(= relative (str "ai-workspace/library/" (:path %))) sections)]
    (->> (coll/into-> (.listFiles directory) []
           (remove (fn hidden-file [^File file]
                     (let [name (.getName file)]
                       (or (.isHidden file) (string/starts-with? name ".")
                           (Files/isSymbolicLink (.toPath file))
                           (string/ends-with? name ".tmp")
                           (and (= relative "ai-workspace")
                                (contains? #{"canvases" "conversations" "attachments" "artifacts"
                                             "candidates" "deliveries" "project" "project.json"
                                             "sync.json" "index.md" "README.md"} name))
                           (and section (= "README.md" name) (.isFile file)
                                (< (.length file) 4096)
                                (= (str "# " (:title section) "\n\n" (:description section) "。\n")
                                   (string/replace (slurp file :encoding "UTF-8") "\r\n" "\n")))))))
           (map (fn describe-entry [^File file]
                  (let [name (.getName file)
                        section (when (= relative "ai-workspace/library")
                                  (coll/first-where #(= name (:path %)) sections))]
                    {:path (str relative "/" name)
                     :title (or (:title section)
                                (when (= (str "/" relative "/" name) roles/document-path) "项目职能")
                                (when (= (str "/" relative "/" name) roles/config-path) "职能配表")
                                (when (= (str relative "/" name) "ai-workspace/config/resource-rules.json") "资源入库规则")
                                (when (= relative "ai-workspace") (get labels name)) name)
                     :description (:description section)
                     :directory (.isDirectory file)}))))
         (sort-by (juxt #(case (:path %)
                           "ai-workspace/library" 0
                           "ai-workspace/config" 1
                           (if (:directory %) 2 3)) :path))
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

(declare refresh-item!)

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
                            (refresh-item! root item expanded show!)
                            (catch Exception e (show! (.getMessage e))))))))
      (.setExpanded item (contains? expanded (:path entry))))
    item))

(defn- refresh-item! [root ^TreeItem item expanded show!]
  (let [children (.getChildren item)
        existing (coll/into-> children {}
                   (keep (fn [^TreeItem child]
                           (when-let [path (:path (.getValue child))]
                             [path child]))))
        next-children (mapv (fn reconcile-entry [entry]
                              (if-let [^TreeItem child (get existing (:path entry))]
                                (do
                                  (when (not= entry (.getValue child)) (.setValue child entry))
                                  (when (and (:directory entry) (.isExpanded child))
                                    (refresh-item! root child expanded show!))
                                  child)
                                (tree-item root entry expanded show!)))
                            (entries root (:path (.getValue item))))]
    (when (not= (vec children) next-children)
      (.setAll children ^java.util.Collection next-children))))

(defn- loaded-items [^TreeItem item]
  (coll/into-> (.getChildren item) [item] (mapcat loaded-items)))

(defn- make-project-tree-cell []
  (proxy [TreeCell] []
    (layoutChildren []
      (proxy-super layoutChildren)
      ;; Keep the native disclosure node and its mouse/keyboard behavior, but
      ;; align it to the row's trailing edge after the skin lays out the label.
      (let [^TreeCell cell this]
        (when-let [disclosure (.getDisclosureNode cell)]
          (.relocate disclosure
                     (- (.getWidth cell) 8.0 (.getWidth (.getLayoutBounds disclosure)))
                     (.getLayoutY disclosure)))))))

(defn configure-browser! [{:keys [^VBox pane ^TreeView tree ^Label title resource-types]}]
  (let [bar (HBox. 2.0)]
    (.add (.getStyleClass bar) "ai-project-header")
    (.setMaxWidth title Double/MAX_VALUE)
    (HBox/setHgrow title Priority/ALWAYS)
    ;; Match Assets: native TreeCells, resource type styling and 16 px icons.
    (ui/advance-ui-user-data-component!
      tree ::cells
      {:fx/type (fx/make-ext-with-props fx.tree-view/props)
       :desc {:fx/type ui/ext-value :value tree}
       :props {:cell-factory
               {:fx/cell-type (assoc fx.tree-cell/lifecycle :ctor make-project-tree-cell)
                :describe (fn describe-project-entry [entry]
                            (if-not entry
                              {}
                              (let [extension (string/lower-case (last (string/split (:path entry) #"\.")))
                                    type (get resource-types extension {:ext extension})
                                    category-icon (get {"library" "M3 1 H11 L15 5 V17 H3 Z M11 1 V5 H15 M6 9 H12 M6 13 H12"
                                                        "config" "M3 3 H17 V17 H3 Z M7 1 V5 M13 1 V5 M1 7 H5 M15 7 H19 M7 15 V19 M13 15 V19 M1 13 H5 M15 13 H19"
                                                        "01-concept" "M5 5 H15 C19 5 21 17 17 17 L13 13 H7 L3 17 C-1 17 1 5 5 5 Z M5 8 V12 M3 10 H7 M14 9 H15 M16 12 H17"
                                                        "02-world" "M10 4 Q5 1 1 3 V17 Q5 15 10 18 Q15 15 19 17 V3 Q15 1 10 4 V18"
                                                        "03-characters" "M14 5 A4 4 0 1 1 6 5 A4 4 0 1 1 14 5 M2 19 V16 C2 10 18 10 18 16 V19 Z"
                                                        "04-levels" "M1 17 L7 5 L11 11 L14 7 L20 17 Z M5 9 L7 11 L9 9"
                                                        "05-systems" "M10 1 L20 6 L10 11 L0 6 Z M1 11 L10 16 L19 11 M1 16 L10 21 L19 16"
                                                        "06-interface" "M1 2 H19 V18 H1 Z M1 7 H19 M6 7 V18"
                                                        "07-motion" "M10 0 V5 M10 15 V20 M0 10 H5 M15 10 H20 M3 3 L6 6 M14 14 L17 17 M3 17 L6 14 M14 6 L17 3"
                                                        "08-audio" "M8 15 V4 L18 2 V13 M8 6 L18 4 M8 15 A3 2 0 1 1 2 15 A3 2 0 1 1 8 15 M18 13 A3 2 0 1 1 12 13 A3 2 0 1 1 18 13"
                                                        "09-balancing" "M1 4 H19 M1 10 H19 M1 16 H19 M6 2 V6 M14 8 V12 M8 14 V18"
                                                        "10-delivery" "M2 2 H18 V18 H2 Z M5 10 L9 14 L15 6"}
                                                       (last (string/split (:path entry) #"/")))]
                                {:text (:title entry)
                                 :style-class (into ["cell" "indexed-cell" "tree-cell"]
                                                    (if (:directory entry) #{"resource" "resource-folder"} (resource/type-style-classes type)))
                                 :graphic {:fx/type :stack-pane :style-class "ai-project-entry-icon"
                                           :children [(if category-icon
                                                        {:fx/type :svg-path :content category-icon :style-class "ai-project-category-icon"}
                                                        {:fx/type ui/image-icon :size 16.0
                                                         :path (if (:directory entry) "icons/32/Icons_01-Folder-closed.png"
                                                                   (or (:icon type) "icons/32/Icons_29-AT-Unknown.png"))})]}
                                 :tooltip {:fx/type :tooltip :text (str (when-let [description (:description entry)] (str description "\n")) (:path entry))}})))}}})
    (when-not (instance? ExtendedTreeViewSkin (.getSkin tree))
      (.setSkin tree (ExtendedTreeViewSkin. tree))
      (ui/customize-tree-view! tree {:double-click-expand true}))
    (.add (.getChildren bar) title)
    (.clear (.getChildren pane))
    (.addAll (.getChildren pane) ^java.util.Collection (identity [bar tree]))
    (VBox/setVgrow tree Priority/ALWAYS)))

(defn make-browser [root current-path open! changed! show! resource-types]
  (ensure-library! root)
  (document/ensure-roles-document! root)
  (let [tree (doto (TreeView.) (.setId "ai-project-tree") (.setShowRoot false) (.setFixedCellSize 30.0))
        fit-scroll! (fn fit-short-tree []
                      (when (and (.getSkin tree) (<= (* 30.0 (.getExpandedItemCount tree)) (.getHeight tree)))
                        (let [flow (.getVirtualFlowInstance ^ExtendedTreeViewSkin (.getSkin tree))]
                          (.setPosition flow 0.0)
                          (.scrollToTop flow (int 0)))))
        menu (ContextMenu.)
        creates (mapv (fn create-template-item [template]
                        (doto (MenuItem. (str "新建 Canvas · " (:title template)))
                          (.setId (if (= "concept" (:id template)) "ai-project-new-canvas" (str "ai-project-new-" (:id template)))))) canvas/templates)
        title (Label. "AI 工程")
        refresh (doto (MenuItem. "刷新目录") (.setId "ai-project-refresh"))
        import (doto (MenuItem. "导入资料…") (.setId "ai-project-import") (.setDisable true))
        pane (VBox.)
        refresh! (fn refresh-project-tree []
                   (let [previous (.getRoot tree)
                         items (if previous (loaded-items previous) [])
                         expanded (coll/into-> items (if previous #{"ai-workspace"} #{"ai-workspace" "ai-workspace/library" "ai-workspace/config"})
                                    (filter #(.isExpanded ^TreeItem %))
                                    (map #(:path (.getValue ^TreeItem %))))
                         selection (or (some-> ^TreeItem (.getSelectedItem (.getSelectionModel tree)) .getValue :path)
                                       (when current-path (string/replace-first current-path #"^/" "")))
                         next-root (or previous (tree-item root {:path "ai-workspace" :title "AI 工程" :directory true} expanded show!))]
                     (if previous
                       (refresh-item! root previous expanded show!)
                       (do
                         (.setRoot tree next-root)
                         (when-let [item (coll/first-where #(= selection (:path (.getValue ^TreeItem %))) (loaded-items next-root))]
                           (.select (.getSelectionModel tree) ^TreeItem item)
                           (.scrollTo tree (.getRow tree item)))))
                     (Platform/runLater fit-scroll!)))
        open-selected! (fn open-selected-file []
                         (when-let [entry (some-> ^TreeItem (.getSelectedItem (.getSelectionModel tree)) .getValue)]
                           (when-not (:directory entry)
                             (try
                               (directory-file root (:path entry))
                               (open! (str "/" (:path entry)))
                               (catch Exception e (show! (.getMessage e)))))))]
    (.add (.getStyleClass pane) "ai-project-browser")
    (.setMinWidth pane 190.0)
    (.setPrefWidth pane 240.0)
    (configure-browser! {:pane pane :tree tree :title title :resource-types resource-types})
    (.addListener (.heightProperty tree) (reify ChangeListener (changed [_ _ _ _] (fit-scroll!))))
    (.addListener (.selectedItemProperty (.getSelectionModel tree))
                  (reify ChangeListener
                    (changed [_ _ _ item]
                      (let [entry (when item (.getValue ^TreeItem item))]
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
    (.addAll (.getItems menu) ^java.util.Collection creates)
    (.addAll (.getItems menu) ^java.util.Collection (identity [(SeparatorMenuItem.) import refresh]))
    (.setContextMenu tree menu)
    (file-reveal/install-tree! tree root
                               (fn selected-project-file [] (some-> ^TreeItem (.getSelectedItem (.getSelectionModel tree)) .getValue :path)) show!)
    (.setOnShowing menu
                   (reify EventHandler
                     (handle [_ _]
                       (let [entry (some-> ^TreeItem (.getSelectedItem (.getSelectionModel tree)) .getValue)]
                         (doseq [^MenuItem create creates] (.setDisable create (not (and (:directory entry) (string/starts-with? (:path entry) "ai-workspace/library/")))))))))
    (doseq [[^MenuItem create template] (mapv vector creates canvas/templates)]
      (.setOnAction create
                    (reify EventHandler
                      (handle [_ _]
                        (let [dialog (TextInputDialog. (:default-name template))
                              entry (.getValue ^TreeItem (.getSelectedItem (.getSelectionModel tree)))]
                          (.setTitle dialog "新建 Canvas")
                          (.setHeaderText dialog (str (:title entry) " · " (:title template)))
                          (.setContentText dialog "名称")
                          (.initOwner dialog (.getWindow (.getScene pane)))
                          (.addAll (.getStylesheets (.getDialogPane dialog)) ^java.util.Collection (.getStylesheets (.getScene pane)))
                          (let [answer (.showAndWait dialog)]
                            (when (.isPresent answer)
                              (try
                                (let [path (document/create-document! root (:path entry) (.get answer) (:id template))]
                                  (refresh!) (changed!) (open! path))
                                (catch java.nio.file.FileAlreadyExistsException _ (show! "同名 Canvas 已存在，请换一个名称"))
                                (catch Exception e (show! (.getMessage e)))))))))))
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
