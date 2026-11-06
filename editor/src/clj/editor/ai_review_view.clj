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

(ns editor.ai-review-view
  (:require [clojure.string :as string]
            [editor.ai-delivery :as delivery]
            [editor.ai-overview :as overview]
            [editor.ai-project-work :as work]
            [editor.ai-result-compare :as compare]
            [editor.ai-review :as review]
            [editor.ai-toast :as toast]
            [editor.ai-workspace :as workspace]
            [editor.craft-project :as craft]
            [editor.file-reveal :as reveal]
            [editor.fxui :as fxui]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [javafx.beans.value ChangeListener]
           [javafx.geometry Insets Pos]
           [javafx.scene Scene]
           [javafx.scene.control Button CheckBox ComboBox Label ListView ScrollPane SplitPane TextArea TextField]
           [javafx.scene.image Image ImageView]
           [javafx.scene.layout AnchorPane BorderPane FlowPane HBox Priority StackPane VBox]
           [javafx.scene.paint Color]
           [javafx.scene.shape Rectangle]
           [javafx.stage FileChooser Stage Window]))

(set! *warn-on-reflection* true)

(defn version-card [record]
  {:id (:id record) :kind :result :parent-id (:parent-id record) :title (str (:title record) " · " (subs (:revision record) 0 8))
   :candidates [{:id (:id record) :text (:text record) :files (:files record)}]})

(defn open! [^Window owner root artifact-id opts]
  (let [stage (Stage.)
        pane (BorderPane.) overlay (StackPane.)
        notifications (toast/make-view)
        selected (atom (delivery/verify-artifact! root (delivery/artifact (work/read-work root) artifact-id)))
        draft (atom nil) versions (atom []) changing (atom false)
        editing (atom nil) anchor (atom nil) entry (atom nil) cleanup (atom (fn dispose-preview []))
        picker (doto (ComboBox.) (.setId "ai-review-version"))
        file-list (doto (ListView.) (.setId "ai-review-files"))
        preview (StackPane.) history (VBox. 8.0)
        feedback (doto (TextArea.) (.setPromptText "对此版本的具体修改意见…") (.setWrapText true) (.setPrefRowCount 3) (.setId "ai-review-feedback"))
        checklist (doto (TextArea.) (.setPromptText "检查清单：每行一项，[x] 已通过 / [ ] 待核对") (.setPrefRowCount 2))
        start-line (doto (TextField.) (.setPromptText "起始行") (.setPrefColumnCount 4))
        end-line (doto (TextField.) (.setPromptText "结束行") (.setPrefColumnCount 4))
        anchor-label (doto (Label. "图片可拖出批注区域；文本可填写行号。") (.setWrapText true))
        status (doto (Label.) (.setWrapText true))
        people (atom [])
        assignee (doto (ComboBox.) (.setPromptText "返修负责人") (.setMaxWidth Double/MAX_VALUE))
        toolbar (FlowPane. 8.0 6.0)
        controls (FlowPane. 8.0 6.0)
        rows (atom [])]
    (letfn [(guard! []
              (when (and @editing (not= (:base @editing) (.getText ^TextArea (:control @editing))))
                (throw (ex-info "有未保存的修改，请先保存或取消" {}))))
            (attempt! [f]
              (try (f)
                   (catch Exception e ((:show! notifications) {:level :warning :text (.getMessage e)}))))
            (button [title id f]
              (doto (Button. title) (.setId id) (.setOnAction (ui/event-handler _ (attempt! f)))))
            (record [] (or @draft @selected))
            (reload-draft! []
              (when @draft (reset! draft (review/draft (work/read-work root) (:id @draft)))))
            (refresh-notes! []
              (.clear (.getChildren history))
              (doseq [note (filterv #(= (:id @selected) (:artifact-id %)) (:review-notes (work/read-work root)))]
                (let [location (str (if (contains? #{nil "body"} (:path note)) "方案正文" (last (string/split (:path note) #"/")))
                                    (case (get-in note [:anchor :type])
                                      "lines" (str " · 第 " (get-in note [:anchor :start]) "–" (get-in note [:anchor :end]) " 行")
                                      "region" " · 图片选区"
                                      " · 整体意见"))
                      label (doto (Label. (str (if (= "changes-requested" (:decision note)) "需修改 · " "意见 · ")
                                               (:text note) "\n" location)) (.setWrapText true))]
                  (.add (.getChildren history) label)
                  (.add (.getChildren history) (button "定位批注内容" (str "ai-review-locate-" (:id note)) #(locate-note! note)))
                  (doseq [item (:checklist note)]
                    (.add (.getChildren history) (doto (CheckBox. (if (string? item) item (:text item)))
                                                   (.setSelected (true? (:checked item))) (.setDisable true) (.setStyle "-fx-opacity: 1;")))))))
            (show-entry! [index]
              (guard!)
              (@cleanup) (reset! cleanup (fn dispose-preview []))
              (reset! editing nil) (reset! anchor nil)
              (ui/user-data! preview ::show-region! nil)
              (.clear start-line) (.clear end-line)
              (.setDisable file-list false)
              (.setText anchor-label "图片可拖出批注区域；文本可填写行号。")
              (.clear (.getChildren preview))
              (let [item (nth @rows index nil)
                    file (when (:path item) (craft/resource-file root (:path item)))
                    image? (and file (re-find #"(?i)\.(png|jpg|jpeg|gif|bmp)$" (.getName file)))
                    text (if (= :body (:id item)) (:text (record))
                             (when (and file (.isFile file) (<= (.length file) (* 8 1024 1024))
                                        (re-find #"(?i)\.(md|markdown|txt|json|lua|gui|atlas|csv)$" (.getName file)))
                               (slurp file :encoding "UTF-8")))]
                (reset! entry item)
                (cond
                  image?
                  (let [image (Image. (str (.toURI file)))
                        image-view (doto (ImageView. image) (.setPreserveRatio true))
                        drawing (Rectangle.) origin (atom nil)
                        image-pane (doto (StackPane.) (.setAlignment Pos/TOP_LEFT))
                        scroll (doto (ScrollPane. image-pane) (.setFitToWidth true))]
                    (.setFill drawing (Color/color 0.2 0.5 1.0 0.12))
                    (.setStroke drawing Color/DODGERBLUE) (.setMouseTransparent drawing true)
                    (.setManaged drawing false)
                    (ui/user-data! preview ::show-region!
                                   (fn show-saved-region [region]
                                     (let [w (.getWidth (.getBoundsInLocal image-view)) h (.getHeight (.getBoundsInLocal image-view))]
                                       (.setX drawing (* w (:x region))) (.setY drawing (* h (:y region)))
                                       (.setWidth drawing (* w (:width region))) (.setHeight drawing (* h (:height region))))))
                    (.bind (.fitWidthProperty image-view) (.subtract (.widthProperty scroll) 20.0))
                    (.setOnMousePressed image-view (ui/event-handler e (reset! origin [(.getX ^javafx.scene.input.MouseEvent e) (.getY ^javafx.scene.input.MouseEvent e)])))
                    (.setOnMouseDragged image-view
                                        (ui/event-handler e
                                          (when-let [[x y] @origin]
                                            (let [w (.getWidth (.getBoundsInLocal image-view)) h (.getHeight (.getBoundsInLocal image-view))
                                                  xx (max 0.0 (min w (.getX ^javafx.scene.input.MouseEvent e))) yy (max 0.0 (min h (.getY ^javafx.scene.input.MouseEvent e)))
                                                  left (min x xx) top (min y yy) width (Math/abs (double (- xx x))) height (Math/abs (double (- yy y)))]
                                              (.setX drawing left) (.setY drawing top) (.setWidth drawing width) (.setHeight drawing height)
                                              (when (and (pos? w) (pos? h) (pos? width) (pos? height))
                                                (reset! anchor {:type "region" :x (/ left w) :y (/ top h) :width (/ width w) :height (/ height h)})
                                                (.setText anchor-label "已选择图片区域，提交意见后绑定当前版本。"))))))
                    (.addAll (.getChildren image-pane) ^"[Ljava.lang.Object;" (to-array [image-view drawing]))
                    (reset! cleanup (fn dispose-image [] (.unbind (.fitWidthProperty image-view))))
                    (.add (.getChildren preview) scroll))
                  (and text @draft (or (nil? file) (re-find #"(?i)\.(md|markdown)$" (.getName file))))
                  (let [source (doto (TextArea. text) (.setWrapText true) (.setId "ai-review-source"))]
                    (reset! editing {:base text :control source :file file :index (:index item)})
                    (.addListener (.textProperty source)
                                  (reify ChangeListener
                                    (changed [_ _ _ value] (.setDisable file-list (not= (:base @editing) value)))))
                    (.add (.getChildren preview) source))
                  text
                  (let [view (doto (AnchorPane.) (-> .getStyleClass (.add "ai-markdown-reader")))]
                    (ui/advance-ui-user-data-component! view ::preview
                                                        {:fx/type fxui/ext-with-anchor-pane-props :desc {:fx/type ui/ext-value :value view}
                                                         :props {:children [(merge (:markdown-opts opts)
                                                                                   {:fx/type workspace/reading-view :content text
                                                                                    :anchor-pane/top 0 :anchor-pane/right 0 :anchor-pane/bottom 0 :anchor-pane/left 0
                                                                                    :root-props {:style-class "ai-markdown-page"} :style-class "ai-markdown-scroll"})]}})
                    (reset! cleanup (fn dispose-text [] (ui/advance-ui-user-data-component! view ::preview nil)))
                    (.add (.getChildren preview) view))
                  :else (.add (.getChildren preview) (Label. "请打开工作副本，用对应应用编辑，再重新收集。")))
                (reveal/install! preview root (fn current-file [] file)
                                 (fn report-file [message] ((:show! notifications) {:level :warning :text message})))))
            (locate-note! [note]
              (guard!)
              (when @draft (throw (ex-info "请先结束编辑再定位固定版本批注" {})))
              (let [index (.indexOf ^java.util.List (mapv #(or (:path %) "body") @rows) (or (:path note) "body"))
                    range (:anchor note)]
                (when (neg? index) (throw (ex-info "批注资源不存在于当前版本" {})))
                (reset! changing true)
                (try (.select (.getSelectionModel file-list) (int index)) (finally (reset! changing false)))
                (show-entry! index)
                (case (:type range)
                  "region" (when-let [show-region! (ui/user-data preview ::show-region!)]
                             (.applyCss preview) (.layout preview) (show-region! range)
                             (.setText anchor-label "正在查看此版本已记录的图片区域。"))
                  "lines" (let [text (if (contains? #{nil "body"} (:path note)) (:text @selected)
                                         (slurp (craft/resource-file root (:path note)) :encoding "UTF-8"))
                                lines (string/split-lines text)
                                source (doto (TextArea. text) (.setEditable false) (.setId "ai-review-anchor-source"))
                                starts (coll/into-> text [0] (keep-indexed (fn newline [i c] (when (= c \newline) (inc i)))))
                                from (nth starts (dec (:start range)))
                                to (min (count text) (get starts (:end range) (count text)))]
                            (@cleanup) (reset! cleanup (fn dispose-anchor []))
                            (.clear (.getChildren preview)) (.add (.getChildren preview) source)
                            (.selectRange source (int from) (int to))
                            (.setText anchor-label (str "批注定位：第 " (:start range) "–" (:end range) " 行 / 共 " (count lines) " 行")))
                  nil)))
            (refresh! []
              (reset! changing true)
              (try
                (let [data (work/read-work root)
                      all (review/versions data @selected)
                      task (delivery/task data (:task-id @selected))
                      staff (filterv #(and (:enabled %) (not= "ai-pm" (:id %))) (:staff (overview/load-snapshot root (atom {}))))]
                  (reset! people staff)
                  (.setAll (.getItems assignee) ^java.util.Collection (mapv #(str (:title %) " · " (get-in % [:role-definition :title] (:role-type %))) staff))
                  (.select (.getSelectionModel assignee) (int (.indexOf ^java.util.List (mapv :id staff) (:staff-id task))))
                  (reset! versions all)
                  (.setAll (.getItems picker) ^java.util.Collection
                           (mapv #(str (subs (:revision %) 0 8) (when (delivery/approved? data (:id %)) " · 已批准")) all))
                  (.select (.getSelectionModel picker) (int (.indexOf ^java.util.List (mapv :id all) (:id @selected)))))
                (.setDisable picker (some? @draft))
                (reset! rows (into [{:id :body :title "方案正文"}]
                                   (mapv #(assoc %1 :index %2 :title (:name %1)) (:files (record)) (range (count (:files (record)))))))
                (.setAll (.getItems file-list) ^java.util.Collection (mapv :title @rows))
                (.select (.getSelectionModel file-list) (int 0))
                (.setText status (if @draft "工作副本 · 保存后提交为新版本，旧批准不变" "固定版本 · 审阅意见和决定只属于当前版本"))
                (refresh-notes!)
                (show-entry! 0)
                (finally (reset! changing false))))
            (save! []
              (when-let [{:keys [base control file index]} @editing]
                (let [text (.getText ^TextArea control)]
                  (if file
                    (review/save-markdown! root (:id @draft) (:revision @draft) index base text)
                    (review/save-text! root (:id @draft) (:revision @draft) text))
                  (swap! editing assoc :base text)
                  (.setDisable file-list false)
                  (reload-draft!))))
            (submit-note! [decision]
              (guard!)
              (when @draft (throw (ex-info "请先将工作副本提交为固定版本后再审阅" {})))
              (when (and (= "changes-requested" decision)
                         (nil? (delivery/task (work/read-work root) (:task-id @selected))))
                (throw (ex-info "此成果没有已分配的 Task，请先记录意见并通过 AI-PM 分配返修" {})))
              (when (and (= "changes-requested" decision) (neg? (.getSelectedIndex (.getSelectionModel assignee))))
                (throw (ex-info "请选择返修负责人" {})))
              (let [line-anchor (when-not (string/blank? (.getText start-line))
                                  {:type "lines" :start (Long/parseLong (.getText start-line))
                                   :end (Long/parseLong (if (string/blank? (.getText end-line)) (.getText start-line) (.getText end-line)))})
                    note (review/add-note! root (:id @selected)
                                           {:decision decision :text (.getText feedback)
                                            :path (or (:path @entry) "body") :anchor (or @anchor line-anchor)
                                            :checklist (mapv (fn checklist-item [line]
                                                               {:text (string/replace-first line #"^\s*\[[xX ]\]\s*" "")
                                                                :checked (boolean (re-find #"^\s*\[[xX]\]" line))})
                                                             (filterv #(not (string/blank? %)) (string/split-lines (.getText checklist))))})]
                (when (= "changes-requested" decision)
                  (review/propose-rework! root (:id note) (:id (nth @people (.getSelectedIndex (.getSelectionModel assignee))))))
                (.clear feedback) (.clear checklist) (refresh-notes!)))]
      (.addAll (.getChildren toolbar) ^"[Ljava.lang.Object;" (to-array [picker
                                                                        (button "基于此版本修改" "ai-review-derive"
                                                                                (fn derive [] (guard!)
                                                                                  (let [existing (coll/first-where #(and (= (:id @selected) (:parent-id %)) (= "editing" (:status %)))
                                                                                                                   (:review-drafts (work/read-work root)))]
                                                                                    (reset! editing nil) (reset! draft (or existing (review/create-draft! root (:id @selected)))) (refresh!))))
                                                                        (button "保存副本" "ai-review-save" save!)
                                                                        (button "提交新版本" "ai-review-publish"
                                                                                (fn publish [] (save!)
                                                                                  (when-not @draft (throw (ex-info "请先创建工作副本" {})))
                                                                                  (reset! selected (review/publish-draft! root (:id @draft) (:revision @draft)))
                                                                                  (reset! draft nil) (reset! editing nil) (refresh!)))
                                                                        (button "结束编辑" "ai-review-close-draft" (fn close-draft [] (guard!) (reset! editing nil) (reset! draft nil) (refresh!)))]))
      (.add (.getChildren toolbar)
            (button "撤销未保存修改" "ai-review-discard"
                    (fn discard-unsaved []
                      (when-let [control (:control @editing)]
                        (.setText ^TextArea control (:base @editing))))))
      (.addAll (.getChildren controls) ^"[Ljava.lang.Object;" (to-array [(button "对比历史版本" "ai-review-compare"
                                                                                 (fn compare-versions [] (guard!)
                                                                                   (when (< (count @versions) 2) (throw (ex-info "当前方案只有一个固定版本" {})))
                                                                                   (compare/open! stage root "同一方案 · 历史版本" (fn current-versions [] (mapv version-card @versions))
                                                                                                  (assoc opts :comparison-kind :versions :on-inspect! (fn inspect-version [id] (open! stage root id opts))))))
                                                                         (button (if (= "proposal" (:kind @selected)) "采用当前方案" "批准当前资产版本") "ai-review-approve"
                                                                                 (fn approve [] (guard!)
                                                                                   (when @draft (throw (ex-info "请先提交固定版本" {})))
                                                                                   (when (and (= "proposal" (:kind @selected)) (nil? (:adopt! opts)))
                                                                                     (throw (ex-info "请从原 Result 卡片采用此方案" {})))
                                                                                   (if (= "proposal" (:kind @selected))
                                                                                     ((:adopt! opts) @selected)
                                                                                     (delivery/approve! root (:id @selected) (.getText feedback)))
                                                                                   (refresh!)))
                                                                         (button "打开工作副本" "ai-review-open-file"
                                                                                 (fn open-file [] (guard!)
                                                                                   (when-not (and @draft (:path @entry)) (throw (ex-info "请先创建副本并选择文件" {})))
                                                                                   (ui/open-file (craft/resource-file root (:path @entry))
                                                                                                 (fn open-error [message] ((:show! notifications) {:level :error :text message})))))
                                                                         (button "替换文件" "ai-review-replace"
                                                                                 (fn replace-file [] (guard!)
                                                                                   (when-not (and @draft (:index @entry)) (throw (ex-info "请在工作副本中选择要替换的文件" {})))
                                                                                   (when-let [file (.showOpenDialog (FileChooser.) stage)]
                                                                                     (review/replace-file! root (:id @draft) (:revision @draft) (:index @entry) (:base-hash @entry) file)
                                                                                     (reset! editing nil) (reload-draft!) (refresh!))))
                                                                         (button "重新收集" "ai-review-collect"
                                                                                 (fn collect-file [] (guard!)
                                                                                   (when-not (and @draft (:index @entry)) (throw (ex-info "请先选择工作副本文件" {})))
                                                                                   (review/collect-file! root (:id @draft) (:revision @draft) (:index @entry))
                                                                                   (reset! editing nil) (reload-draft!) (refresh!)))]))
      (let [right (VBox. 8.0)
            notes-scroll (doto (ScrollPane. history) (.setFitToWidth true))
            center (doto (BorderPane.) (.setCenter preview) (.setBottom controls))
            split (SplitPane.)]
        (.add (.getStyleClass right) "studio-review-panel")
        (.setPadding right (Insets. 14.0))
        (.addAll (.getChildren right) ^"[Ljava.lang.Object;" (to-array [status anchor-label (HBox. 6.0 (into-array javafx.scene.Node [start-line end-line])) feedback checklist assignee
                                                                        (HBox. 6.0 (into-array javafx.scene.Node
                                                                                               [(button "记录意见" "ai-review-note" #(submit-note! "comment"))
                                                                                                (button "退回并提出返修" "ai-review-rework" #(submit-note! "changes-requested"))])) notes-scroll]))
        (VBox/setVgrow notes-scroll Priority/ALWAYS)
        (.addAll (.getItems split) ^"[Ljava.lang.Object;" (to-array [file-list center right]))
        (.setDividerPositions split (double-array [0.17 0.73]))
        (.setTop pane toolbar) (.setCenter pane split))
      (.addListener (.selectedIndexProperty (.getSelectionModel picker))
                    (reify ChangeListener
                      (changed [_ _ _ value]
                        (when (and (not @changing) (<= 0 (int value)))
                          (attempt! (fn choose-version [] (guard!) (reset! editing nil) (reset! draft nil)
                                      (reset! selected (nth @versions (int value))) (refresh!)))))))
      (.addListener (.selectedIndexProperty (.getSelectionModel file-list))
                    (reify ChangeListener
                      (changed [_ _ _ value]
                        (when (and (not @changing) (<= 0 (int value))) (attempt! #(show-entry! (int value)))))))
      (.setOnCloseRequest stage (ui/event-handler event
                                  (try (guard!) (catch Exception e (.consume event)
                                                       ((:show! notifications) {:level :warning :text (.getMessage e)})))))
      (StackPane/setAlignment (:root notifications) Pos/TOP_CENTER)
      (.addAll (.getChildren overlay) ^"[Ljava.lang.Object;" (to-array [pane (:root notifications)]))
      (.setId pane "ai-review-window")
      (.setPadding pane (Insets. 12.0))
      (.setPadding toolbar (Insets. 0.0 0.0 8.0 0.0))
      (.setPadding controls (Insets. 8.0 0.0 0.0 0.0))
      (ui/apply-css! overlay) (workspace/apply-styles! overlay)
      (.setScene stage (Scene. overlay 1300.0 820.0))
      (.setAll (.getStylesheets (.getScene stage)) ^java.util.Collection (.getStylesheets (.getScene owner)))
      (.initOwner stage owner) (.setTitle stage (str "成果审阅 · " (:title @selected)))
      (.setMinWidth stage 950.0) (.setMinHeight stage 600.0)
      (.setOnHidden stage (ui/event-handler _ (@cleanup) ((:dispose! notifications))))
      (refresh!) (.show stage)
      {:stage stage :refresh! refresh!})))
