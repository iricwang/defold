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

(ns editor.ai-card-view
  "Resource explorer and Markdown editor for Result and Assets cards."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.ai-project-browser :as project-browser]
            [editor.ai-results :as results]
            [editor.ai-review :as review]
            [editor.ai-toast :as toast]
            [editor.ai-workspace :as ai-workspace]
            [editor.file-reveal :as file-reveal]
            [editor.fxui :as fxui]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [java.nio.file Files StandardCopyOption]
           [javafx.beans.value ChangeListener]
           [javafx.geometry Insets Pos]
           [javafx.scene Scene]
           [javafx.scene.control Button Label ScrollPane SplitPane TextArea Tooltip TreeItem TreeView]
           [javafx.scene.image Image ImageView]
           [javafx.scene.input KeyCode KeyEvent]
           [javafx.scene.layout AnchorPane BorderPane HBox Priority StackPane VBox]
           [javafx.scene.shape SVGPath]
           [javafx.stage Stage Window]))

(set! *warn-on-reflection* true)

(defn- local-file [root paths]
  (let [boundary (.toPath (.getCanonicalFile (io/file root)))]
    (coll/first-where
      identity
      (mapv (fn resolve-local [path]
              (when (and (string? path) (not (string/blank? path)))
                (try
                  (let [file (if (string/starts-with? path "file:") (io/file (java.net.URI/create path))
                                 (let [file (io/file path)]
                                   (if (.isAbsolute file) file (io/file root (string/replace-first path #"^[/\\]" "")))))
                        file (.getCanonicalFile file)]
                    (when (and (.isFile file) (.startsWith (.toPath file) boundary)) file))
                  (catch Exception _ nil)))) paths))))

(defn save-markdown! [root file expected text]
  (let [file (local-file root [(.getPath ^java.io.File file)])]
    (when (and file (review/protected-file? root file))
      (throw (ex-info "固定成果不能直接修改，请基于此版本创建工作副本" {})))
    (when-not (and file (re-find #"(?i)\.(md|markdown)$" (.getName ^java.io.File file)))
      (throw (ex-info "只能保存项目内的 Markdown 文件" {})))
    (when (> (alength (.getBytes ^String text "UTF-8")) (* 8 1024 1024))
      (throw (ex-info "Markdown 内容超过 8 MiB" {})))
    (when-not (= expected (slurp file :encoding "UTF-8"))
      (throw (ex-info "文件已被外部修改，草稿已保留。请先复制草稿，再取消编辑并刷新。" {})))
    (let [temp (Files/createTempFile (.toPath (.getParentFile ^java.io.File file)) ".markdown-" ".tmp"
                                     (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (spit (.toFile temp) text :encoding "UTF-8")
        (Files/move temp (.toPath ^java.io.File file)
                    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))
        (finally (Files/deleteIfExists temp))))))

(defn entries [root card]
  (let [proposal (results/candidate card)
        result (= :result (:kind card))
        text (if result (:text proposal) (:proposal-text card))
        image-path (:image-path proposal)
        files (if result (:files proposal) (:assets card))
        body (cond-> []
               (not (string/blank? text))
               (conj {:id :body :title (if result "当前方案正文" "交付方案说明") :path "/proposal.md" :text text})
               (and result (not (string/blank? (get-in card [:generated-candidate :text])))
                    (not= text (get-in card [:generated-candidate :text])))
               (conj {:id :original :title "Action 原始正文" :path "/original.md" :text (get-in card [:generated-candidate :text])})
               (and result (not (string/blank? (get-in card [:confirmed-candidate :text])))
                    (not= text (get-in card [:confirmed-candidate :text])))
               (conj {:id :confirmed :title "已确认版本正文" :path "/confirmed.md" :text (get-in card [:confirmed-candidate :text])}))
        resources (cond-> []
                    (and result image-path) (conj (assoc proposal :id :image :name "方案效果图" :path image-path)))
        resources (into resources (filterv #(and (not (and result (= image-path (:path %))))
                                                 (not (and (:text %) (= text (:text %)) (nil? (:path %))))) files))
        outputs (when-not result
                  (coll/into-> (into [(:document-output card)] (into (vec (get-in card [:production :paths]))
                                                                     (mapv #(get-in card [:production %]) [:gui :atlas :manifest]))) []
                    (filter string?) (distinct)))
        resources (into resources (mapv #(hash-map :path % :source % :name (.getName (io/file %))) (sort outputs)))]
    (into body
          (mapv (fn resource-entry [index resource]
                  (let [path (or (:path resource) (:source resource) (str "/attachment-" index ".txt"))
                        file (local-file root [(:local-path resource) (:preview resource) (:source resource) (:path resource)])]
                    {:id (or (:id resource) [:file index path]) :title (or (:name resource) (.getName (io/file path)))
                     :path path :file file :text (:text resource) :missing (and (nil? file) (nil? (:text resource)))}))
                (range (count resources)) resources))))

(defn open! [^Window owner root card! opts]
  (let [stage (Stage.)
        pane (BorderPane.) tree (doto (TreeView.) (.setId "ai-card-resource-tree") (.setShowRoot false))
        browser (doto (VBox.) (.setMinWidth 160.0) (.setPrefWidth 250.0) (-> .getStyleClass (.add "ai-project-browser")))
        content (StackPane.) heading (doto (Label.) (.setMaxWidth Double/MAX_VALUE))
        refresh (doto (Button.) (.setId "ai-card-resource-refresh"))
        open (doto (Button.) (.setId "ai-card-resource-open"))
        edit (doto (Button.) (.setId "ai-card-resource-edit"))
        save (doto (Button.) (.setId "ai-card-resource-save"))
        cancel (doto (Button.) (.setId "ai-card-resource-cancel"))
        review-button (doto (Button. "版本与审阅") (.setId "ai-card-review") (.setVisible (boolean (:review! opts))) (.setManaged (boolean (:review! opts))))
        editing (atom nil)
        editable? (fn editable-entry? [entry]
                    (or (and (:file entry) (re-find #"(?i)\.(md|markdown)$" (.getName ^java.io.File (:file entry)))
                             (<= (.length ^java.io.File (:file entry)) (* 8 1024 1024)))
                        (and (= :body (:id entry)) (= :result (:kind (card!))) (:save-body! opts))))
        bar (HBox. 8.0) right (BorderPane.) split (SplitPane.) overlay (StackPane.)
        notifications (toast/make-view)
        cleanup (atom (fn no-content [])) current (atom nil)
        show-entry! (fn show-resource [entry]
                      (@cleanup) (reset! cleanup (fn empty-cleanup []))
                      (reset! current entry) (.clear (.getChildren content))
                      (.setText heading (str (:title entry) (when @editing " · 编辑中")))
                      (.setDisable open (or (some? @editing) (nil? (:file entry))))
                      (.setDisable tree (some? @editing))
                      (.setDisable refresh (some? @editing))
                      (.setVisible edit (boolean (editable? entry))) (.setManaged edit (.isVisible edit))
                      (.setVisible save (some? @editing)) (.setManaged save (.isVisible save))
                      (.setVisible cancel (some? @editing)) (.setManaged cancel (.isVisible cancel))
                      (.setAccessibleText edit (if @editing (if (:source @editing) "预览修改" "继续编辑") "编辑 Markdown"))
                      (.setTooltip edit (Tooltip. (.getAccessibleText edit)))
                      ((:resolve! notifications) :preview)
                      (try
                        (let [file ^java.io.File (:file entry)
                              extension (string/lower-case (last (string/split (if file (.getName file) (:path entry "")) #"\.")))
                              text (or (:text @editing)
                                       (when (and file (<= (.length file) (* 8 1024 1024))
                                                  (contains? #{"md" "markdown" "txt" "json" "edn" "csv" "yaml" "yml" "lua" "script" "gui_script" "gui" "atlas" "aicanvas" "project" "log" "xml"} extension))
                                         (slurp file :encoding "UTF-8")) (:text entry))
                              view
                              (cond
                                (:source @editing)
                                (let [source (doto (TextArea. text) (.setId "ai-card-markdown-source") (.setWrapText true)
                                                   (-> .getStyleClass (.add "ai-card-markdown-source")))]
                                  (.addListener (.textProperty source)
                                                (reify ChangeListener
                                                  (changed [_ _ _ value]
                                                    (swap! editing assoc :text value)
                                                    (.setText heading (str (:title entry) (when (not= value (:base @editing)) " · 未保存"))))))
                                  source)
                                (:missing entry) (doto (Label. "此资源尚未同步到本地。同步资料后点击刷新。") (.setWrapText true))
                                (and file (contains? #{"png" "jpg" "jpeg" "gif" "bmp"} extension))
                                (let [image (Image. (str (.toURI file)) true)
                                      image-view (doto (ImageView. image) (.setPreserveRatio true))
                                      scroll (doto (ScrollPane. image-view) (.setFitToWidth true) (.setPannable true) (.setId "ai-card-image-preview"))]
                                  (.bind (.fitWidthProperty image-view) (.subtract (.widthProperty scroll) 20.0))
                                  (.addListener (.errorProperty image)
                                                (reify ChangeListener
                                                  (changed [_ _ _ failed]
                                                    (when (and failed (= entry @current))
                                                      ((:show! notifications) {:id :preview :level :error :text "图片读取失败，请检查文件内容。"})))))
                                  (reset! cleanup (fn close-image [] (.unbind (.fitWidthProperty image-view)) (.cancel image)))
                                  scroll)
                                (and text (or (:text entry) (contains? #{"md" "markdown"} extension)))
                                (let [preview (doto (AnchorPane.) (.setId "ai-card-markdown-preview") (-> .getStyleClass (.add "ai-markdown-reader")))]
                                  (ui/advance-ui-user-data-component!
                                    preview ::markdown
                                    {:fx/type fxui/ext-with-anchor-pane-props :desc {:fx/type ui/ext-value :value preview}
                                     :props {:children [(merge (:markdown-opts opts)
                                                               (when (and file (:base-resource! opts)) {:base-resource ((:base-resource! opts) file)})
                                                               {:fx/type ai-workspace/reading-view :content text
                                                                :anchor-pane/top 0 :anchor-pane/right 0 :anchor-pane/bottom 0 :anchor-pane/left 0
                                                                :root-props {:style-class "ai-markdown-page"} :style-class "ai-markdown-scroll"})]}})
                                  (reset! cleanup (fn close-markdown [] (ui/advance-ui-user-data-component! preview ::markdown nil)))
                                  preview)
                                text (doto (TextArea. text) (.setId "ai-card-text-preview") (.setEditable false) (.setWrapText true))
                                :else (doto (Label. "此格式请通过“打开原文件”使用对应应用查看。") (.setWrapText true)))]
                          (.add (.getChildren content) view)
                          (file-reveal/install! view root (fn preview-file [] file)
                                                (fn report-reveal [message] ((:show! notifications) {:level :warning :text message}))))
                        (catch Exception e ((:show! notifications) {:id :preview :level :error :text (.getMessage e)}))))
        refresh! (fn refresh-resources []
                   (when-not @editing
                     (let [card (card!)
                           items (entries root card)
                           root-item (TreeItem. {:directory true :title "全部内容" :path "/"})
                           leaves (mapv #(TreeItem. %) items)
                           selected (:id @current)]
                       (.setTitle stage (str (:title card) " · " (if (= :result (:kind card)) "Result" "Assets") " 详情"))
                       (doseq [[title entries] [["方案正文" (filterv #(contains? #{:body :original :confirmed} (:id (.getValue ^TreeItem %))) leaves)]
                                                ["图片与文件" (filterv #(not (contains? #{:body :original :confirmed} (:id (.getValue ^TreeItem %)))) leaves)]]]
                         (when (coll/not-empty entries)
                           (let [folder (doto (TreeItem. {:directory true :title (str title " · " (count entries)) :path "/"}) (.setExpanded true))]
                             (.addAll (.getChildren folder) ^java.util.Collection entries)
                             (.add (.getChildren root-item) folder))))
                       (.setRoot tree root-item)
                       (if-let [item (or (coll/first-where #(= selected (:id (.getValue ^TreeItem %))) leaves) (first leaves))]
                         (.select (.getSelectionModel tree) ^TreeItem item)
                         (show-entry! {:title "暂无内容" :path "/empty.md" :text "此卡片还没有产出内容。"})))))
        save! (fn save-markdown-edit []
                (when-let [{:keys [entry base text]} @editing]
                  (try
                    (if-let [file (:file entry)]
                      (save-markdown! root file base text)
                      ((:save-body! opts) base text))
                    (reset! editing nil)
                    (refresh!)
                    ((:resolve! notifications) :unsaved)
                    ((:show! notifications) {:id :save :level :info :resolved true :text "Markdown 修改已保存"})
                    true
                    (catch Exception e
                      ((:show! notifications) {:id :save :level (if (instance? clojure.lang.ExceptionInfo e) :warning :error) :text (.getMessage e)})
                      false))))]
    (project-browser/configure-browser! {:pane browser :tree tree :title (Label. "卡片内容") :resource-types (:resource-types opts)})
    (.addListener (.selectedItemProperty (.getSelectionModel tree))
                  (reify ChangeListener
                    (changed [_ _ _ item]
                      (when item (let [entry (.getValue ^TreeItem item)] (when-not (:directory entry) (show-entry! entry)))))))
    (.setOnAction edit (ui/event-handler _
                         (try
                           (if (and (:file @current) (review/protected-file? root (:file @current)))
                             (if-let [review! (:review! opts)] (review!)
                                     (throw (ex-info "固定成果只能通过版本与审阅创建工作副本" {})))
                             (when (editable? @current)
                               (if @editing
                                 (swap! editing update :source not)
                                 (let [entry @current text (if-let [file (:file entry)] (slurp file :encoding "UTF-8") (:text entry))]
                                   (reset! editing {:entry entry :base text :text text :source true})))
                               (show-entry! @current)))
                           (catch Exception e
                             ((:show! notifications) {:id :preview :level :error :text (.getMessage e)})))))
    (.setOnAction save (ui/event-handler _ (save!)))
    (.setOnAction cancel (ui/event-handler _
                           (reset! editing nil) ((:resolve! notifications) :save) ((:resolve! notifications) :unsaved) (refresh!)))
    (.addEventFilter pane KeyEvent/KEY_PRESSED
      (ui/event-handler event
        (let [event ^KeyEvent event]
          (when (and @editing (.isShortcutDown event) (= KeyCode/S (.getCode event)))
            (save!) (.consume event)))))
    (.setOnCloseRequest stage (ui/event-handler event
                                (when (and @editing (not= (:base @editing) (:text @editing)))
                                  (.consume ^javafx.stage.WindowEvent event)
                                  ((:show! notifications) {:id :unsaved :level :warning :text "有未保存的修改，请保存或取消编辑后关闭。"}))))
    (file-reveal/install-tree! tree root (fn selected-resource-file [] (some-> ^TreeItem (.getSelectedItem (.getSelectionModel tree)) .getValue :file))
                               (fn report-reveal [message] ((:show! notifications) {:level :warning :text message})))
    (.setOnAction refresh (ui/event-handler _ (refresh!)))
    (.setOnAction review-button (ui/event-handler _ (when-let [review! (:review! opts)] (review!))))
    (.setOnAction open (ui/event-handler _
                         (when-let [file (:file @current)]
                           (try ((:open-file! opts) file)
                                (catch Exception e ((:show! notifications) {:id :open :level :error :text (.getMessage e)}))))))
    (HBox/setHgrow heading Priority/ALWAYS)
    (doseq [[^Button button title path] [[refresh "刷新内容" "M12 5 A5 5 0 1 0 12 9 M12 1 V5 H8"]
                                         [open "打开原文件" "M8 1 H13 V6 M13 1 L6 8 M6 3 H2 V12 H11 V8"]
                                         [edit "编辑 Markdown" "M2 10 L10 2 L13 5 L5 13 L1 14 Z M8 4 L11 7"]
                                         [save "保存修改 · Ctrl+S" "M2 1 H11 L14 4 V14 H1 V1 Z M4 1 V6 H10 V1 M4 14 V9 H11 V14"]
                                         [cancel "取消编辑并放弃修改" "M3 3 L12 12 M12 3 L3 12"]]]
      (.setGraphic button (doto (SVGPath.) (.setContent path) (-> .getStyleClass (.add "ai-resource-icon"))))
      (.setTooltip button (Tooltip. title))
      (.setAccessibleText button title)
      (.add (.getStyleClass button) "ai-icon-button"))
    (.setAlignment bar Pos/CENTER_LEFT)
    (.setStyle bar "-fx-padding: 8 12;")
    (.addAll (.getChildren bar) ^java.util.Collection (identity [heading review-button edit save cancel open refresh]))
    (.setTop right bar) (.setCenter right content)
    (.addAll (.getItems split) ^java.util.Collection (identity [browser right]))
    (SplitPane/setResizableWithParent browser false) (.setDividerPositions split (double-array [0.23]))
    (StackPane/setAlignment (:root notifications) Pos/TOP_CENTER)
    (StackPane/setMargin (:root notifications) (Insets. 50.0 12.0 12.0 12.0))
    (.addAll (.getChildren overlay) ^java.util.Collection (identity [split (:root notifications)]))
    (.setCenter pane overlay) (.setId pane "ai-card-resource-window")
    (ui/apply-css! pane)
    (ai-workspace/apply-styles! pane)
    (let [scene (Scene. pane 1140.0 800.0)]
      (.setAll (.getStylesheets scene) ^java.util.Collection (.getStylesheets (.getScene owner)))
      (.setScene stage scene))
    (.initOwner stage owner) (.setMinWidth stage 700.0) (.setMinHeight stage 480.0)
    (.setOnHidden stage (ui/event-handler _ (@cleanup) ((:dispose! notifications))))
    (refresh!) (.show stage)
    {:stage stage :refresh! refresh!}))
