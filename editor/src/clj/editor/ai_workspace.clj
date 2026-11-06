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

(ns editor.ai-workspace
  "One persistent project browser with independently owned document tabs."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.ai-document :as document]
            [editor.ai-overview-view :as overview-view]
            [editor.ai-project-browser :as project-browser]
            [editor.ai-resource-import :as resource-import]
            [editor.ai-roles :as roles]
            [editor.ai-studio :as studio]
            [editor.ai-toast :as toast]
            [editor.ai-work-views :as work-views]
            [editor.craft-project :as craft-project]
            [editor.file-reveal :as file-reveal]
            [editor.fxui :as fxui]
            [editor.handler :as handler]
            [editor.markdown-view :as markdown-view]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [javafx.beans.value ChangeListener]
           [javafx.collections ListChangeListener ListChangeListener$Change]
           [javafx.css PseudoClass]
           [javafx.event Event]
           [javafx.geometry Insets Pos]
           [javafx.scene Parent]
           [javafx.scene.control Button ContextMenu Label MenuItem ScrollPane SplitPane Tab TabPane TabPane$TabClosingPolicy TextArea TitledPane Tooltip TreeView]
           [javafx.scene.image Image ImageView]
           [javafx.scene.layout AnchorPane HBox Priority StackPane VBox]
           [javafx.scene.shape SVGPath]
           [javafx.stage Window]))

(set! *warn-on-reflection* true)

(defonce ^:private live-stylesheet (atom nil))

(defn apply-styles! [^Parent parent]
  (when-not (.contains (.getStyleClass parent) "ai-studio") (.add (.getStyleClass parent) "ai-studio"))
  (let [styles (.getStylesheets parent)
        url (or @live-stylesheet (str (io/resource "ai-canvas.css")))
        retained (filterv #(not (re-find #"(?:^|/)ai-canvas\.css(?:\?.*)?$" %)) styles)]
    (.setAll styles ^java.util.Collection (conj retained url))))

(defn reload-styles!
  "Called by the development reloader. Preserve the source stylesheet for new
  tabs as well as updating existing views; installed startup uses its bundle."
  [url]
  (reset! live-stylesheet url)
  (ui/run-now
    (doseq [^Window window (vec (Window/getWindows))
            :let [scene (.getScene window)]
            :when scene]
      (doseq [selector [".ai-workbench" ".ai-markdown-reader" ".ai-overview"]
              ^Parent parent (.lookupAll (.getRoot scene) selector)]
        (.forget (com.sun.javafx.css.StyleManager/getInstance) parent)
        (apply-styles! parent))
      (.applyCss (.getRoot scene)))))

(defn document-key [root context]
  (cond
    (:staff-id context) [:staff (:staff-id context)]
    (:task-id context) [:task (:task-id context)]
    (document/document-path? (:path context))
    (:id (document/read-document root (:path context)))
    :else (:path context)))

(defn close-tabs! [^TabPane tabs ^Tab target mode]
  ;; A popup can outlive its tab. Never let a stale target close sibling views.
  (when (.contains (.getTabs tabs) target)
    (doseq [^Tab tab (vec (.getTabs tabs))
            :when (and (.isClosable tab)
                       (case mode
                         :current (identical? target tab)
                         :others (not (identical? target tab))
                         :all true))]
      (let [request (Event. tab tab Tab/TAB_CLOSE_REQUEST_EVENT)]
        (Event/fireEvent tab request)
        (when-not (.isConsumed request)
          (Event/fireEvent tab (Event. Tab/CLOSED_EVENT))
          (.remove (.getTabs tabs) tab))))
    (.requestFocus tabs)))

(handler/defhandler :window.tab.close ::tabs
  (enabled? [^TabPane ai-tabs]
    (some-> (ui/selected-tab ai-tabs) .isClosable))
  (run [ai-tabs]
    (close-tabs! ai-tabs (ui/selected-tab ai-tabs) :current)))

(handler/defhandler :window.tab.close-others ::tabs
  (enabled? [^TabPane ai-tabs]
    (let [selected (ui/selected-tab ai-tabs)]
      (and selected (coll/any? #(and (not (identical? selected %)) (.isClosable ^Tab %)) (.getTabs ai-tabs)))))
  (run [ai-tabs]
    (close-tabs! ai-tabs (ui/selected-tab ai-tabs) :others)))

(handler/defhandler :window.tab.close-all ::tabs
  (enabled? [^TabPane ai-tabs]
    (coll/any? #(.isClosable ^Tab %) (.getTabs ai-tabs)))
  (run [ai-tabs]
    (close-tabs! ai-tabs (ui/selected-tab ai-tabs) :all)))

(defn- configure-tab-menu! [^TabPane tabs ^Tab tab]
  (let [menu (ContextMenu.)]
    (doseq [[mode title] [[:current "关闭"] [:others "关闭其他"] [:all "关闭全部"]]]
      (let [item (MenuItem. title)]
        (.setId item (str "ai-tab-close-" (name mode)))
        (.setOnAction item (ui/event-handler _ (close-tabs! tabs tab mode)))
        (.add (.getItems menu) item)))
    (.setOnShowing menu
                   (ui/event-handler _
                     (let [present (.contains (.getTabs tabs) tab)
                           [^MenuItem current ^MenuItem others ^MenuItem all] (.getItems menu)]
                       (.setDisable current (not (and present (.isClosable tab))))
                       (.setDisable others (not (and present (coll/any? #(and (not (identical? tab %)) (.isClosable ^Tab %)) (.getTabs tabs)))))
                       (.setDisable all (not (and present (coll/any? #(.isClosable ^Tab %) (.getTabs tabs))))))))
    (.add (.getItems menu) (javafx.scene.control.SeparatorMenuItem.))
    (file-reveal/add-item! menu nil (fn tab-file []
                                      (or (ui/user-data tab ::file)
                                          (file-reveal/resolve-file (ui/user-data tabs ::project-root) (ui/user-data tab ::path))))
                           (fn report-reveal [message]
                             (if-let [show! (:show-toast! (ui/user-data tab :editor.ai-workbench/canvas))]
                               (show! {:level :warning :text message})
                               (when-let [show! (ui/user-data tabs ::file-error!)] (show! message)))))
    (.setContextMenu tab menu)))

(defn configure-tabs! [^TabPane tabs browser-root]
  (ui/context! tabs ::tabs {:ai-tabs tabs} nil)
  (ui/context! browser-root ::tabs {:ai-tabs tabs} nil)
  (when-not (ui/user-data tabs ::menus-installed)
    (.addListener (.getTabs tabs)
                  ^ListChangeListener
                  (reify ListChangeListener
                    (onChanged [_ change]
                      (let [^ListChangeListener$Change change change]
                        (while (.next change)
                          (doseq [^Tab tab (.getAddedSubList change)]
                            (configure-tab-menu! tabs tab)))))))
    (ui/user-data! tabs ::menus-installed true))
  (doseq [^Tab tab (vec (.getTabs tabs))]
    (configure-tab-menu! tabs tab)))

(defn reading-view [props]
  (let [view (markdown-view/html-view (markdown-view/view props))]
    (assoc view :content {:fx/type :stack-pane
                          :alignment :top-center
                          :style-class "ai-markdown-sheet"
                          :children [(assoc (:content view) :max-width 980)]})))

(defn- make-markdown-view! [file opts]
  (let [preview (doto (AnchorPane.) (.setId "ai-markdown-preview"))
        source (doto (TextArea.) (.setId "ai-markdown-source") (.setEditable false))
        toggle (doto (Button.) (.setId "ai-markdown-toggle"))
        refresh (doto (Button.) (.setId "ai-markdown-refresh"))
        open (doto (Button.) (.setId "ai-markdown-open"))
        status (doto (Label.) (.setId "ai-markdown-status") (.setWrapText true))
        toolbar (doto (HBox. 2.0) (.setId "ai-markdown-toolbar"))
        body (VBox.)
        pane (StackPane.)
        reload! (fn reload-markdown []
                  (try
                    (when (> (.length ^java.io.File file) (* 8 1024 1024))
                      (throw (ex-info "文档超过 8 MB，请打开原文件查看。" {})))
                    (let [text (slurp file :encoding "UTF-8")]
                      (ui/advance-ui-user-data-component!
                        preview ::markdown
                        {:fx/type fxui/ext-with-anchor-pane-props
                         :desc {:fx/type ui/ext-value :value preview}
                         :props {:children [(merge opts
                                                   {:fx/type reading-view
                                                    :content text
                                                    :anchor-pane/top 0 :anchor-pane/right 0
                                                    :anchor-pane/bottom 0 :anchor-pane/left 0
                                                    :root-props {:style-class "ai-markdown-page"}
                                                    :style-class "ai-markdown-scroll"})]}})
                      (.setText source text)
                      (.setText status ""))
                    (catch Exception e (.setText status (.getMessage e)))))]
    (.bind (.managedProperty source) (.visibleProperty source))
    (.bind (.managedProperty preview) (.visibleProperty preview))
    (.bind (.visibleProperty status) (.isNotEmpty (.textProperty status)))
    (.bind (.managedProperty status) (.visibleProperty status))
    (.add (.getStyleClass pane) "ai-markdown-reader")
    (apply-styles! pane)
    (.add (.getStyleClass toolbar) "ai-markdown-toolbar")
    (.add (.getStyleClass status) "ai-markdown-status")
    (.add (.getStyleClass source) "ai-markdown-source")
    (doseq [[^Button button title path] [[toggle "源码 / 预览" "M5 3 L1 7 L5 11 M9 3 L13 7 L9 11"]
                                         [refresh "刷新文档" "M12 5 A5 5 0 1 0 12 9 M12 1 V5 H8"]
                                         [open "打开原文件" "M8 1 H13 V6 M13 1 L6 8 M6 3 H2 V12 H11 V8"]]]
      (let [icon (doto (SVGPath.) (.setContent path))]
        (.add (.getStyleClass icon) "ai-markdown-icon")
        (.setGraphic button icon)
        (.setTooltip button (Tooltip. title))
        (.setAccessibleText button title)))
    (StackPane/setAlignment toolbar Pos/TOP_RIGHT)
    (StackPane/setMargin toolbar (Insets. 6.0 18.0 0.0 0.0))
    (.setMaxSize toolbar javafx.scene.layout.Region/USE_PREF_SIZE javafx.scene.layout.Region/USE_PREF_SIZE)
    (ui/visible! source false)
    (.setOnAction toggle (ui/event-handler _
                           (let [show-source (.isVisible preview)]
                             (ui/visible! source show-source)
                             (ui/visible! preview (not show-source))
                             (.pseudoClassStateChanged toggle (PseudoClass/getPseudoClass "selected") show-source))))
    (.setOnAction refresh (ui/event-handler _ (reload!)))
    (.setOnAction open (ui/event-handler _ (ui/open-file file #(.setText status %))))
    (.addAll (.getChildren toolbar) ^java.util.Collection (identity [toggle refresh open]))
    (.addAll (.getChildren body) ^java.util.Collection (identity [status preview source]))
    (.addAll (.getChildren pane) ^java.util.Collection (identity [body toolbar]))
    (VBox/setVgrow preview Priority/ALWAYS)
    (VBox/setVgrow source Priority/ALWAYS)
    (reload!)
    {:root pane
     :close! (fn close-markdown-preview []
               (ui/advance-ui-user-data-component! preview ::markdown nil))}))

(defn- make-config-view! [root file resource-rules]
  (let [saved-text (slurp file :encoding "UTF-8")
        prefix (if resource-rules "ai-resource-rules" "ai-role-config")
        source (TextArea. saved-text)
        expected (atom saved-text)
        save (doto (Button. "保存配表") (.setId (str prefix "-save")))
        reload (doto (Button. "重载") (.setId (str prefix "-reload")))
        status (doto (Label. (if resource-rules "保存后用于新生成的入库方案；已有方案保留原规则快照。"
                                 "修改后保存，返回项目职能页即可生效。默认参数用于新增职员；现有职员可单独应用。")) (.setWrapText true))
        notifications (when resource-rules (toast/make-view))
        notify! (fn notify-config [level text]
                  (if notifications ((:show! notifications) {:id :config :level level :resolved (= :info level) :text text})
                      (.setText status text)))
        help (doto (TitledPane. "字段说明 / 跨项目复用"
                                (doto (Label. (if resource-rules
                                                "gui.images / atlas / scene：正式图片目录、图集和 GUI 路径，使用 {module} 占位。\nmaxImageSize / maxAtlasSize：图片与图集尺寸上限；innerPadding / extrudeBorders：图集留白与边缘扩展像素。\n复制 ai-workspace/config/resource-rules.json 到其他项目即可复用。第一版支持 GUI 静态图集；不会自动覆盖重名文件。"
                                                (str "roles 数组顺序决定职能行顺序；复制一行并使用新的 id 即可新增职能。\n"
                                                     "id：稳定标识（小写英文、数字、下划线或短横线）；title：职能名；color：#RRGGBB。\n"
                                                     "icon：planning / art / programming / pm / qa / person / model / document / status；portrait：前五种人物样式。\n"
                                                     "namePool：随机姓名数组；initialCount：新项目首次创建职员数（0～20）。\n"
                                                     "defaults：assignee 负责人、model 模型名或模型 ID、effort 低/中/高/极高、enabled true/false、responsibilities 职责、deliverables 交付要求。\n"
                                                     "复制 ai-workspace/config/role-types.json 到另一项目同一路径即可复用。不要复制职员文档或聊天归档；已有职员引用的 id 不能直接删除。")))
                                  (.setWrapText true))) (.setExpanded false))
        bar (HBox. 8.0)
        pane (VBox. 8.0)]
    (.setId source (str prefix "-source"))
    (.setId status (str prefix "-status"))
    (.setStyle source "-fx-font-family: Consolas; -fx-font-size: 14;")
    (.setStyle pane "-fx-padding: 8;")
    (.setOnAction save (ui/event-handler _
                         (try
                           (if resource-rules (resource-import/save-rules! root @expected (.getText source))
                               (roles/save-config! root @expected (.getText source)))
                           (reset! expected (.getText source))
                           (notify! :info (if resource-rules "入库规则已保存，新方案使用此配置。已有方案保留原规则快照。"
                                              "配表已保存。返回项目职能页即可生效；可复制此 JSON 文件到其他项目。"))
                           (catch Exception e (notify! (if (instance? clojure.lang.ExceptionInfo e) :warning :error) (.getMessage e))))))
    (.setOnAction reload (ui/event-handler _
                           (try
                             (let [text (slurp file :encoding "UTF-8")]
                               (.setText source text)
                               (reset! expected text))
                             (notify! :info "已重载磁盘上的配表。")
                             (catch Exception e (notify! :error (.getMessage e))))))
    (.addAll (.getChildren bar) ^java.util.Collection (identity [save reload status]))
    (.addAll (.getChildren pane) ^java.util.Collection (identity [bar help source]))
    (HBox/setHgrow status Priority/ALWAYS)
    (VBox/setVgrow source Priority/ALWAYS)
    {:root (if notifications
             (let [overlay (StackPane.)]
               (.addAll (.getChildren overlay) ^java.util.Collection (identity [pane (:root notifications)]))
               (StackPane/setAlignment (:root notifications) Pos/TOP_CENTER)
               overlay)
             pane)
     :close! (fn close-config [] (when notifications ((:dispose! notifications))))}))

(defn make-file-view! [^AnchorPane parent root path opts]
  (let [file (craft-project/resource-file root path)
        extension (string/lower-case (or (last (string/split (.getName file) #"\.")) ""))
        status (doto (Label. path) (.setWrapText true))
        open (Button. "打开原文件")
        pane (VBox. 8.0)
        markdown (when (contains? #{"md" "markdown"} extension)
                   (make-markdown-view! file opts))
        config (when (contains? #{roles/config-path resource-import/rules-path} path)
                 (make-config-view! root file (= resource-import/rules-path path)))
        content (cond
                  config (:root config)
                  markdown (:root markdown)

                  (contains? #{"png" "jpg" "jpeg" "gif" "bmp"} extension)
                  (let [image (Image. (str (.toURI file)) true)
                        preview (doto (ImageView. image) (.setPreserveRatio true))
                        scroll (doto (ScrollPane. preview) (.setFitToWidth true) (.setPannable true))]
                    (.bind (.fitWidthProperty preview) (.widthProperty scroll))
                    scroll)

                  (and (<= (.length file) (* 8 1024 1024))
                       (contains? #{"md" "txt" "json" "edn" "csv" "yaml" "yml" "lua" "script" "gui_script" "gui" "atlas" "aicanvas" "project" "log" "xml"} extension))
                  (doto (TextArea. (slurp file)) (.setEditable false) (.setWrapText true))

                  :else (doto (Label. "此类型请使用原文件对应的应用查看。") (.setWrapText true)))]
    (.setOnAction open (ui/event-handler _ (ui/open-file file #(.setText status %))))
    (.setStyle pane (if (or markdown config) "-fx-padding: 0;" "-fx-padding: 12;"))
    (.addAll (.getChildren pane) ^java.util.Collection (identity (if (or markdown config) [content] [status open content])))
    (VBox/setVgrow content Priority/ALWAYS)
    (doseq [setter [#(AnchorPane/setTopAnchor pane %) #(AnchorPane/setRightAnchor pane %) #(AnchorPane/setBottomAnchor pane %) #(AnchorPane/setLeftAnchor pane %)]] (setter 0.0))
    (.add (.getChildren parent) pane)
    (file-reveal/install! pane root (fn preview-file [] file) #(.setText status %))
    (doseq [selector ["TextArea" "TextField"]
            input (.lookupAll pane selector)]
      (file-reveal/install! input root (fn preview-source-file [] file) #(.setText status %)))
    {:close! (or (:close! config) (:close! markdown) (fn close-file-preview []))}))

(defn- configure-sidebar-selection! [^Button home ^TreeView tree ^TabPane tabs ^Tab overview-tab]
  (let [selected (PseudoClass/getPseudoClass "selected")
        select-home! (fn select-project-home []
                       (.clearSelection (.getSelectionModel tree))
                       (.pseudoClassStateChanged home selected true))
        open-home (.getOnAction home)]
    (.setOnAction home
                  (reify javafx.event.EventHandler
                    (handle [_ event]
                      (select-home!)
                      (.handle open-home event))))
    (.addListener (.selectedItemProperty (.getSelectionModel tree))
                  (reify ChangeListener
                    (changed [_ _ _ item]
                      (when item
                        (.pseudoClassStateChanged home selected false)))))
    (.addListener (.selectedItemProperty (.getSelectionModel tabs))
                  (reify ChangeListener
                    (changed [_ _ _ tab]
                      (if (identical? overview-tab tab)
                        (select-home!)
                        (.pseudoClassStateChanged home selected false)))))
    (when (identical? overview-tab (.getSelectedItem (.getSelectionModel tabs)))
      (select-home!))))

(defn- make-studio-shell! [root browser overview ^TabPane tabs overview-tab open!]
  (let [sidebar ^VBox (:root browser)
        tree ^TreeView (.lookup sidebar "#ai-project-tree")
        nav (VBox. 4.0)
        content (VBox.)
        top (HBox. 14.0)
        links (HBox. 12.0)
        search (doto (javafx.scene.control.TextField.) (.setId "studio-search") (.setPromptText "搜索任务、成果、成员…") (.setPrefWidth 340.0))
        title (doto (Label. (str "项目    /    " (studio/project-name root))) (.setGraphic (studio/icon :flame)) (.setGraphicTextGap 12.0) (-> .getStyleClass (.add "studio-breadcrumb")))
        search-box (doto (HBox. 8.0) (.setAlignment Pos/CENTER_LEFT) (-> .getStyleClass (.add "studio-search-box")))
        member-button (studio/button "" "studio-members" :people #(open! {:overview true :preserve true :section "staff"}))
        notification-button (studio/button "" "studio-notifications" :bell #(open! {:overview true :preserve true :section "reviews"}))
        badge (doto (javafx.scene.shape.Circle. 3.5) (.setMouseTransparent true) (-> .getStyleClass (.add "studio-notification-dot")))
        notification (StackPane.)
        watch-key (Object.)
        gap (javafx.scene.layout.Region.)
        buttons (atom [])
        select! (fn select-section [section]
                  (ui/visible! tree (= "resources" section))
                  (if (= "settings" section)
                    (open! {:path roles/document-path})
                    (open! {:overview true :preserve true :section section})))
        home (studio/button "项目总览" "ai-project-home" :home #(do ((:reset! overview)) (select! "overview")))
        sync! (fn sync-navigation []
                (let [home-selected (identical? overview-tab (.getSelectedItem (.getSelectionModel ^TabPane tabs)))
                      section (.getValue ^javafx.beans.property.SimpleStringProperty (:section overview))]
                  (.pseudoClassStateChanged tabs (PseudoClass/getPseudoClass "studio-home") home-selected)
                  (doseq [[^Button button target] @buttons]
                    (.pseudoClassStateChanged button (PseudoClass/getPseudoClass "selected") (and home-selected (= section target))))))]
    (.add (.getStyleClass sidebar) "studio-sidebar")
    (.setMinWidth sidebar 238.0) (.setPrefWidth sidebar 238.0) (.setMaxWidth sidebar 238.0)
    (.clear (.getChildren sidebar))
    (.setId nav "studio-navigation")
    (doseq [[text target kind] [["项目总览" "overview" :home] ["任务" "tasks" :tasks] ["阶段" "stages" :stages] ["成果" "outputs" :assets] ["成员" "staff" :people] ["资源" "resources" :resources] ["配置" "settings" :settings]]]
      (let [button (if (= target "overview") home (studio/button text (str "studio-nav-" target) kind #(select! target)))]
        (.setMaxWidth button Double/MAX_VALUE)
        (swap! buttons conj [button target])
        (.add (.getChildren nav) button)
        (when (= target "resources")
          (.add (.getChildren nav) tree))))
    (.bind (.managedProperty tree) (.visibleProperty tree))
    (ui/visible! tree false)
    (.setMinHeight tree 0.0)
    (.bind (.prefHeightProperty tree) (.add (.multiply (.expandedItemCountProperty tree) 30.0) 4.0))
    (.setMaxHeight tree javafx.scene.layout.Region/USE_PREF_SIZE)
    (VBox/setMargin tree (Insets. 0.0 0.0 4.0 18.0))
    (VBox/setVgrow tree Priority/ALWAYS)
    (.setMinHeight nav 0.0)
    (VBox/setVgrow nav Priority/ALWAYS)
    (.addAll (.getChildren sidebar) ^java.util.Collection (identity [(studio/brand) nav]))
    (.bindBidirectional (.textProperty search) (.textProperty ^javafx.scene.control.TextField (:search overview)))
    (.setVisible ^javafx.scene.control.TextField (:search overview) false)
    (.setManaged ^javafx.scene.control.TextField (:search overview) false)
    (.addListener (.textProperty search) (reify ChangeListener (changed [_ _ _ _] (.select (.getSelectionModel ^TabPane tabs) overview-tab))))
    (.setAlignment top Pos/CENTER_LEFT)
    (.add (.getStyleClass top) "studio-topbar")
    (HBox/setHgrow gap Priority/ALWAYS)
    (.addAll (.getChildren search-box) ^java.util.Collection (identity [(studio/icon :search) search]))
    (HBox/setHgrow search Priority/ALWAYS)
    (.addAll (.getChildren notification) ^java.util.Collection (identity [notification-button badge]))
    (StackPane/setAlignment badge Pos/TOP_RIGHT)
    (StackPane/setMargin badge (Insets. 3.0 3.0 0.0 0.0))
    (ui/visible! badge false)
    (add-watch (:snapshot overview) watch-key (fn update-studio-header [_ _ _ model] (ui/visible! badge (pos? (count (:reviews model))))))
    (.setGraphic member-button (overview-view/portrait {:id "studio-team" :role-type "planning"}))
    (.setTooltip member-button (Tooltip. "查看项目成员"))
    (.setTooltip notification-button (Tooltip. "待我处理"))
    (.setAccessibleText member-button "查看项目成员")
    (.setAccessibleText notification-button "待我处理")
    (.addAll (.getChildren top) ^java.util.Collection (identity [title gap search-box notification member-button]))
    (.add (.getStyleClass links) "studio-top-tabs")
    (doseq [[text target] [["概览" "overview"] ["任务" "tasks"] ["列表" "list"] ["成果" "outputs"] ["资源" "resources"] ["设置" "settings"]]]
      (let [button (doto (Button. text) (.setId (str "studio-tab-" target))
                         (-> .getStyleClass (.add "studio-top-tab"))
                         (.setOnAction (ui/event-handler _ (select! target))))]
        (swap! buttons conj [button target])
        (.add (.getChildren links) button)))
    (.addListener (.selectedItemProperty (.getSelectionModel ^TabPane tabs)) (reify ChangeListener (changed [_ _ _ _] (sync!))))
    (.addListener ^javafx.beans.value.ObservableValue (:section overview) (reify ChangeListener (changed [_ _ _ _] (sync!))))
    (.addAll (.getChildren content) ^java.util.Collection (identity [top links tabs]))
    (.setMinWidth content 0.0)
    (VBox/setVgrow tabs Priority/ALWAYS)
    (configure-sidebar-selection! home tree tabs overview-tab)
    (sync!)
    {:root content :close! (fn close-studio-shell [] (remove-watch (:snapshot overview) watch-key))}))

(defn make-view! [^AnchorPane parent root initial-context resource-types make-content!]
  (let [tabs (doto (TabPane.) (.setId "ai-document-tabs") (.setTabClosingPolicy TabPane$TabClosingPolicy/ALL_TABS))
        status (Label.)
        split (SplitPane.)
        pane (VBox.)
        documents (atom {})
        browser (atom nil)
        shell (atom nil)
        overview-tab (doto (Tab. "项目总览") (.setClosable false) (.setId "ai-overview-tab"))
        closed (atom false)]
    (letfn [(show! [message]
              (let [text (or message "")]
                (.setText status text)
                ;; An empty status line would otherwise reserve a blank strip
                ;; under the document area.
                (let [showing? (not (string/blank? text))]
                  (.setVisible status showing?)
                  (.setManaged status showing?))))
            (open! [context]
              (when-not @closed
                (if (:overview context)
                  (do
                    (when-not (:preserve context) ((:reset! (::overview @documents))))
                    (when-let [section (:section context)] ((:show-section! (::overview @documents)) section))
                    (.select (.getSelectionModel tabs) overview-tab))
                  (let [key (document-key root context)
                        existing (get @documents key)]
                    (if existing
                      (do
                        (.select (.getSelectionModel tabs) ^Tab (:tab existing))
                        (when-let [update! (:update-context! existing)] (update! context)))
                      (let [virtual (or (:staff-id context) (:task-id context))
                            doc (when (and (not virtual) (document/document-path? (:path context))) (document/read-document root (:path context)))
                            title (or (:title doc) (when virtual (if (:staff-id context) "职员工作" "任务详情")) (when (= roles/config-path (:path context)) "职能配表") (.getName (io/file (:path context))))
                            child (AnchorPane.)
                            tab (doto (Tab. title child)
                                  (.setGraphic (doto (SVGPath.)
                                                 (.setContent (if doc "M1 2 H15 V12 H1 Z M5 5 H11 M5 9 H9" "M3 1 H10 L14 5 V15 H3 Z M10 1 V5 H14"))
                                                 (-> .getStyleClass (.add "ai-workspace-tab-icon"))))
                                  (.setTooltip (Tooltip. (:path context))))
                            content (if virtual
                                      (let [view (work-views/make-view root tab context open!)]
                                        (.setContent tab (:root view))
                                        (apply-styles! (:root view))
                                        view)
                                      (make-content! child tab context (assoc @browser :open! open!)))
                            disposed (atom false)
                            dispose! (fn dispose-document []
                                       (when (compare-and-set! disposed false true)
                                         ((:close! content))
                                         (swap! documents dissoc key)))]
                        (ui/user-data! tab ::path (:path context))
                        (when (:path context) (ui/user-data! tab ::file (craft-project/resource-file root (:path context))))
                        (ui/on-closed! tab (fn close-document-tab [_] (dispose!)))
                        (swap! documents assoc key (assoc content :tab tab :dispose! dispose!))
                        (.add (.getTabs tabs) tab)
                        (.select (.getSelectionModel tabs) tab)
                        (when-let [update! (:update-context! content)] (update! context))))))))
            (changed! []
              (when-let [entry (coll/first-where #(identical? (:tab %) (.getSelectedItem (.getSelectionModel tabs))) (coll/vals @documents))]
                (when-let [changed! (:library-changed! entry)] (changed!))))
            (close! []
              (when (compare-and-set! closed false true)
                (when-let [dispose! (:close! @shell)] (dispose!))
                (doseq [entry (vec (coll/vals @documents))] ((:dispose! entry)))
                (.clear (.getTabs tabs))))]
      (reset! browser (project-browser/make-browser root (:path initial-context)
                                                    (fn open-library-item [path] (open! {:path path})) changed! show! resource-types))
      (let [overview (overview-view/make-view root overview-tab open!
                                              (fn project-connected []
                                                (coll/any? #(when-let [state (ui/user-data (:tab %) :editor.ai-workbench/state)] (:connected @state)) (coll/vals @documents))))]
        (apply-styles! (:root overview))
        (.setGraphic overview-tab (doto (SVGPath.) (.setContent "M1 8 H4 V16 H1 Z M7 2 H10 V16 H7 Z M13 5 H16 V16 H13 Z")
                                        (-> .getStyleClass (.add "ai-workspace-tab-icon"))))
        (.setContent overview-tab (:root overview))
        (swap! documents assoc ::overview (assoc overview :tab overview-tab :dispose! (:close! overview)))
        (.add (.getTabs tabs) overview-tab)
        (reset! shell (make-studio-shell! root @browser overview tabs overview-tab open!)))
      (ui/user-data! tabs ::project-root root)
      (ui/user-data! tabs ::file-error! #(.setText status %))
      (configure-tabs! tabs (:root @browser))
      (.setId parent "ai-workspace")
      (.add (.getStyleClass parent) "ai-workbench")
      (apply-styles! parent)
      (.addAll (.getItems split) ^java.util.Collection (identity [(:root @browser) (:root @shell)]))
      (SplitPane/setResizableWithParent (:root @browser) false)
      (.setDividerPositions split (double-array [0.15]))
      (.addAll (.getChildren pane) ^java.util.Collection (identity [split status]))
      (VBox/setVgrow split Priority/ALWAYS)
      (doseq [setter [#(AnchorPane/setTopAnchor pane %) #(AnchorPane/setRightAnchor pane %) #(AnchorPane/setBottomAnchor pane %) #(AnchorPane/setLeftAnchor pane %)]] (setter 0.0))
      (.add (.getChildren parent) pane)
      {:open! open! :close! close! :tabs tabs :browser @browser})))
