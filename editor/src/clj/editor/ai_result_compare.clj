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

(ns editor.ai-result-compare
  "Side-by-side previews and text differences for Action outputs."
  (:require [clojure.string :as string]
            [editor.ai-card-view :as card-view]
            [editor.ai-results :as results]
            [editor.ai-toast :as toast]
            [editor.ai-workspace :as ai-workspace]
            [editor.file-reveal :as file-reveal]
            [editor.fxui :as fxui]
            [editor.ui :as ui]
            [util.coll :as coll]
            [util.diff :as diff])
  (:import [com.defold.control ListCell]
           [java.nio.file Files]
           [javafx.beans.value ChangeListener]
           [javafx.css PseudoClass]
           [javafx.geometry Insets Pos]
           [javafx.scene Node Scene]
           [javafx.scene.control Button CheckBox ComboBox Label ListView ScrollPane SplitPane TextArea Tooltip]
           [javafx.scene.image Image ImageView]
           [javafx.scene.layout AnchorPane BorderPane HBox Priority StackPane VBox]
           [javafx.stage Stage Window]
           [javafx.util Callback StringConverter]))

(set! *warn-on-reflection* true)

(defn resource-pairs [root left right]
  (let [index (fn index-resources [card]
                (:items
                  (reduce (fn index-resource [{:keys [counts] :as acc} entry]
                            (let [key (case (:id entry) :body :body :image :image
                                            (string/lower-case (last (string/split (:path entry) #"[/\\]"))))
                                  occurrence (get counts key 0)]
                              (-> acc (update :items conj (assoc entry :compare-key [key occurrence]))
                                  (update-in [:counts key] (fnil inc 0)))))
                          {:counts {} :items []}
                          (eduction (remove #(contains? #{:original :confirmed} (:id %))) (card-view/entries root card)))))
        left (index left) right (index right)
        left-by-key (coll/pair-map-by :compare-key identity left)
        right-by-key (coll/pair-map-by :compare-key identity right)
        keys (coll/into-> (into left right) [] (map :compare-key) (distinct))]
    (mapv (fn pair-resource [key]
            (let [a (get left-by-key key) b (get right-by-key key)]
              {:key key :label (case (first key) :body "方案正文" :image "方案效果图" (:title (or a b)))
               :left a :right b})) keys)))

(defn entry-content [entry]
  (let [file ^java.io.File (:file entry)
        extension (string/lower-case (last (string/split (:path entry "") #"\.")))
        image (or (= :image (:id entry)) (contains? #{"png" "jpg" "jpeg" "gif" "bmp"} extension))
        text (or (when (and file (not image) (<= (.length file) (* 8 1024 1024))
                            (contains? #{"md" "markdown" "txt" "json" "edn" "csv" "yaml" "yml" "lua" "script" "gui_script" "gui" "atlas" "project" "xml" "log"} extension))
                   (slurp file :encoding "UTF-8")) (:text entry))]
    (assoc entry :content-kind (cond (nil? entry) :absent (:missing entry) :missing
                                     (and image file) :image (some? text) :text :else :binary)
           :content-text text :markdown (or (:text entry) (contains? #{"md" "markdown"} extension)))))

(defn pair-status [left right]
  (cond
    (and (= :absent (:content-kind left)) (= :absent (:content-kind right))) :empty
    (= :absent (:content-kind left)) :right-only
    (= :absent (:content-kind right)) :left-only
    (or (= :missing (:content-kind left)) (= :missing (:content-kind right))) :missing
    (and (some? (:content-text left)) (some? (:content-text right)))
    (if (= (:content-text left) (:content-text right)) :same :different)
    (and (:file left) (:file right))
    (let [a ^java.io.File (:file left) b ^java.io.File (:file right)]
      (cond (not= (.length a) (.length b)) :different
            (<= (.length a) (* 8 1024 1024)) (if (= -1 (Files/mismatch (.toPath a) (.toPath b))) :same :different)
            :else :binary))
    :else :different))

(defn line-differences [left right]
  (let [{:keys [left-lines right-lines edits]} (diff/find-edits (string/replace left "\r\n" "\n") (string/replace right "\r\n" "\n"))]
    {:changes (count (filterv #(not= :nop (:type %)) edits))
     :rows (coll/into-> edits []
             (mapcat (fn aligned-edit [edit]
                       (let [a (:left edit) b (:right edit)
                             left-count (- (:end a) (:begin a)) right-count (- (:end b) (:begin b))]
                         (mapv (fn aligned-row [offset]
                                 {:kind (:type edit)
                                  :left (when (< offset left-count) {:line (inc (+ offset (:begin a))) :text (get left-lines (+ offset (:begin a)))})
                                  :right (when (< offset right-count) {:line (inc (+ offset (:begin b))) :text (get right-lines (+ offset (:begin b)))})})
                               (range (max left-count right-count)))))))}))

(defn- make-preview [entry opts report!]
  (let [file ^java.io.File (:file entry)
        cleanup (atom (fn no-preview []))
        ^Node node
        (case (:content-kind entry)
          :image
          (let [image (Image. (str (.toURI file)) true)
                active (atom true)
                view (doto (ImageView. image) (.setPreserveRatio true) (.setAccessibleText (:title entry)))
                scroll (doto (ScrollPane. view) (.setFitToWidth true) (.setPannable true))]
            (.bind (.fitWidthProperty view) (.subtract (.widthProperty scroll) 24.0))
            (.addListener (.errorProperty image)
                          (reify ChangeListener (changed [_ _ _ failed] (when (and failed @active) (report! "图片读取失败，请打开详情检查原文件。")))))
            (reset! cleanup (fn close-image [] (reset! active false) (.unbind (.fitWidthProperty view)) (.cancel image)))
            scroll)
          :text
          (if-not (:markdown entry)
            (doto (TextArea. (:content-text entry)) (.setEditable false) (.setWrapText true))
            (let [preview (doto (AnchorPane.) (-> .getStyleClass (.add "ai-markdown-reader")))]
              (ui/advance-ui-user-data-component! preview ::markdown
                                                  {:fx/type fxui/ext-with-anchor-pane-props :desc {:fx/type ui/ext-value :value preview}
                                                   :props {:children [(merge (:markdown-opts opts)
                                                                             (when (and file (:base-resource! opts)) {:base-resource ((:base-resource! opts) file)})
                                                                             {:fx/type ai-workspace/reading-view :content (:content-text entry)
                                                                              :anchor-pane/top 0 :anchor-pane/right 0 :anchor-pane/bottom 0 :anchor-pane/left 0
                                                                              :root-props {:style-class "ai-markdown-page"} :style-class "ai-markdown-scroll"})]}})
              (reset! cleanup (fn close-markdown [] (ui/advance-ui-user-data-component! preview ::markdown nil)))
              preview))
          (doto (Label. (case (:content-kind entry) :absent "此侧没有对应内容" :missing "本地资源缺失，请恢复文件后刷新。" "此格式可在完整详情中打开原文件。"))
            (.setWrapText true) (-> .getStyleClass (.add "ai-compare-empty"))))]
    (file-reveal/install! node nil (constantly file) report!)
    {:root node :dispose! (fn dispose-preview [] (@cleanup))
     :scroll (if (instance? ScrollPane node) node (.lookup node "ScrollPane"))}))

(defn- make-diff-list [rows]
  (let [list (doto (ListView.) (.setId "ai-compare-diff") (-> .getStyleClass (.add "ai-compare-diff")))]
    (.setAll (.getItems list) ^java.util.Collection rows)
    (.setCellFactory list
                     (reify Callback
                       (call [_ _]
                         (proxy [ListCell] []
                           (updateItem [item vacant]
                             (let [^ListCell this this]
                               (proxy-super updateItem item vacant)
                               (.setText ^ListCell this nil)
                               (.setGraphic ^ListCell this
                                            (when-not vacant
                                              (let [row (HBox.)]
                                                (doseq [side [:left :right]]
                                                  (let [line (get item side)
                                                        changed (and line (not= :nop (:kind item)))
                                                        prefix (if changed (if (= :left side) "− " "+ ") "")
                                                        number (doto (Label. (when line (str prefix (:line line))))
                                                                 (.setMinWidth 48.0) (-> .getStyleClass (.add "ai-compare-line-number")))
                                                        text (doto (Label. (or (:text line) " ")) (.setWrapText true) (.setMinWidth 0.0) (.setMaxWidth Double/MAX_VALUE))
                                                        box (doto (HBox. 8.0) (.setMinWidth 0.0)
                                                                  (-> .getStyleClass (.addAll ^java.util.Collection (identity ["ai-compare-diff-side" (if (= side :left) "ai-compare-left" "ai-compare-right")])))
                                                                  (.pseudoClassStateChanged (PseudoClass/getPseudoClass "changed") (boolean changed)))]
                                                    (HBox/setHgrow text Priority/ALWAYS)
                                                    (.bind (.prefWidthProperty box) (.divide (.subtract (.widthProperty ^ListCell this) 20.0) 2.0))
                                                    (.bind (.maxWidthProperty box) (.prefWidthProperty box))
                                                    (.addAll (.getChildren box) ^java.util.Collection (identity [number text]))
                                                    (.add (.getChildren row) box)))
                                                row)))))))))
    list))

(defn open! [^Window owner root action-title cards! opts]
  (when (< (count (cards!)) 2) (throw (ex-info "至少需要两个已产出的 Result 才能对比" {})))
  (let [stage (Stage.) pane (BorderPane.)
        version-mode (= :versions (:comparison-kind opts))
        unit (if version-mode "版本" "方案")
        left (doto (ComboBox.) (.setId "ai-compare-left") (.setMaxWidth Double/MAX_VALUE))
        right (doto (ComboBox.) (.setId "ai-compare-right") (.setMaxWidth Double/MAX_VALUE))
        resource (doto (ComboBox.) (.setId "ai-compare-resource") (.setPrefWidth 300.0))
        mode (doto (ComboBox.) (.setId "ai-compare-mode"))
        sync (doto (CheckBox. "同步滚动") (.setId "ai-compare-sync") (.setSelected true))
        swap (doto (Button. "交换左右") (.setId "ai-compare-swap"))
        refresh (doto (Button. "刷新") (.setId "ai-compare-refresh"))
        status (doto (Label.) (.setId "ai-compare-status") (.setMaxWidth Double/MAX_VALUE))
        notice (doto (Label.) (.setId "ai-compare-notice"))
        headers (HBox. 12.0) selectors (HBox. 8.0) tools (HBox. 10.0)
        content (StackPane.) overlay (StackPane.)
        notifications (toast/make-view)
        report! (fn compare-warning [message] ((:show! notifications) {:level :warning :text message}))
        selected (atom {:left nil :right nil :resource nil})
        updating (atom false) cleanups (atom [])
        dispose-content! (fn dispose-comparison []
                           (doseq [dispose! @cleanups] (dispose!))
                           (reset! cleanups []) (.clear (.getChildren content)))]
    (letfn [(render-content! []
              (dispose-content!)
              (try
                (let [a (.getValue left) b (.getValue right)
                      pairs (resource-pairs root a b)
                      pair (or (coll/first-where #(= (:resource @selected) (:key %)) pairs) (first pairs))
                      _ (reset! updating true)
                      _ (.setAll (.getItems resource) ^java.util.Collection pairs)
                      _ (.setValue resource pair)
                      _ (reset! updating false)
                      a (entry-content (:left pair)) b (entry-content (:right pair))
                      status-kind (pair-status a b)
                      can-diff (and (some? (:content-text a)) (some? (:content-text b))
                                    (<= (+ (count (:content-text a)) (count (:content-text b))) 1000000))
                      _ (.setDisable mode (not can-diff))
                      _ (.setTooltip mode (Tooltip. (if can-diff "按行查看文字差异" "图片、二进制资源或过长文本使用阅读预览")))
                      _ (when-not can-diff (reset! updating true) (.setValue mode "阅读预览") (reset! updating false))
                      diff-mode (= "文本差异" (.getValue mode))
                      edits (when (and can-diff diff-mode) (line-differences (:content-text a) (:content-text b)))]
                  (swap! selected assoc :resource (:key pair))
                  (.setDisable sync diff-mode)
                  (.setText status (str ({:empty "暂无可对比内容" :same "内容相同" :different "内容不同" :left-only "仅左侧方案包含" :right-only "仅右侧方案包含" :missing "部分资源尚未同步" :binary "二进制资源"} status-kind)
                                        (when edits (str " · " (:changes edits) " 处差异 · − 左侧内容 / + 右侧内容"))))
                  (if diff-mode
                    (.add (.getChildren content) (make-diff-list (:rows edits)))
                    (let [split (SplitPane.)
                          views (mapv (fn create-preview [entry]
                                        (let [view (make-preview entry opts report!)]
                                          (swap! cleanups conj (:dispose! view)) view)) [a b])
                          a ^ScrollPane (:scroll (first views)) b ^ScrollPane (:scroll (second views))]
                      (.addAll (.getItems split) ^java.util.Collection (mapv :root views))
                      (.setDividerPositions split (double-array [0.5]))
                      (when (and a b (.isSelected sync))
                        (.bindBidirectional (.vvalueProperty a) (.vvalueProperty b))
                        (swap! cleanups conj (fn unbind-scrolling [] (.unbindBidirectional (.vvalueProperty a) (.vvalueProperty b)))))
                      (.add (.getChildren content) split))))
                (catch Exception e
                  ((:show! notifications) {:id :preview :level :error :text (.getMessage e)}))))
            (refresh! []
              (reset! updating true)
              (try
                (let [values (mapv (fn numbered-card [index card] (assoc card :compare-name (str unit " " (inc index) " · " (:title card)))) (range) (cards!))
                      a (or (coll/first-where #(= (:left @selected) (:id %)) values) (first values))
                      b (or (coll/first-where #(and (= (:right @selected) (:id %)) (not= (:id a) (:id %))) values)
                            (coll/first-where #(not= (:id a) (:id %)) values))]
                  (swap! selected assoc :left (:id a) :right (:id b))
                  (.setAll (.getItems left) ^java.util.Collection values) (.setAll (.getItems right) ^java.util.Collection values)
                  (.setValue left a) (.setValue right b)
                  (.setText notice (str (count values) " 个" unit " · " (if version-mode "同一方案的固定版本；修改需创建工作副本。"
                                                                           (if b "不同 Result 方案；每个方案可分别采用。" "当前不足两个方案，可打开详情查看剩余方案。"))))
                  (.clear (.getChildren headers))
                  (doseq [[side card] [[:left a] [:right b]]]
                    (let [box (VBox. 6.0) title (Label. (or (:compare-name card) (str "没有第二个" unit)))
                          body (:text (results/candidate card))
                          summary (Label. (str (count body) " 字 · " (count (:files (results/candidate card))) " 个附件 · "
                                               (if version-mode
                                                 (if (:parent-id card) "派生版本" "初始版本")
                                                 (str (if (:manually-edited card) "人工修改" "原始产出") " · "
                                                      (if (and (:confirmed-revision card) (= (:confirmed-revision card) (results/revision card))) "已选定" "未选定")))))
                          open (doto (Button. "打开完整详情") (.setId (str "ai-compare-inspect-" (name side))) (.setDisable (nil? card)))]
                      (.add (.getStyleClass title) "ai-compare-title") (.add (.getStyleClass summary) "ai-compare-muted")
                      (.setOnAction open (ui/event-handler _ ((:on-inspect! opts) (:id card))))
                      (.addAll (.getChildren box) ^java.util.Collection (identity [title summary open]))
                      (.setMinWidth box 0.0) (.setPrefWidth box 0.0) (.setMaxWidth box Double/MAX_VALUE)
                      (HBox/setHgrow box Priority/ALWAYS) (.add (.getChildren headers) box))))
                (finally (reset! updating false)))
              (render-content!))
            (choose! [side value]
              (when (and value (not @updating))
                (let [other (if (= side :left) :right :left) old (get @selected side)]
                  (swap! selected (fn select-proposal [selection]
                                    (cond-> (assoc selection side (:id value)) (= (:id value) (get selection other)) (assoc other old)))))
                (refresh!)))]
      (doseq [^ComboBox box [left right]]
        (.setConverter box (proxy [StringConverter] [] (toString [card] (or (:compare-name card) "")) (fromString [_] nil)))
        (.setMinWidth box 180.0) (HBox/setHgrow box Priority/ALWAYS))
      (.setConverter resource (proxy [StringConverter] [] (toString [pair] (or (:label pair) "")) (fromString [_] nil)))
      (.setAll (.getItems mode) ^java.util.Collection (identity ["阅读预览" "文本差异"])) (.setValue mode "阅读预览")
      (doseq [[^ComboBox box side] [[left :left] [right :right]]]
        (.setAccessibleText box (str (if (= side :left) "左侧" "右侧") unit))
        (.addListener (.valueProperty box) (reify ChangeListener (changed [_ _ _ value] (choose! side value)))))
      (.setAccessibleText resource "对比内容") (.setAccessibleText mode "显示方式")
      (.addListener (.valueProperty resource)
                    (reify ChangeListener (changed [_ _ _ value] (when (and value (not @updating)) (swap! selected assoc :resource (:key value)) (render-content!)))))
      (.addListener (.valueProperty mode) (reify ChangeListener (changed [_ _ _ _] (when-not @updating (render-content!)))))
      (.setOnAction sync (ui/event-handler _ (render-content!)))
      (.setOnAction swap (ui/event-handler _ (swap! selected (fn swap-proposals [selection] (assoc selection :left (:right selection) :right (:left selection)))) (refresh!)))
      (.setOnAction refresh (ui/event-handler _ ((:resolve! notifications) :preview) (refresh!)))
      (.addAll (.getChildren selectors) ^java.util.Collection (identity [(Label. "左侧") left swap (Label. "右侧") right refresh]))
      (.addAll (.getChildren tools) ^java.util.Collection (identity [(Label. "对比内容") resource mode sync status]))
      (HBox/setHgrow status Priority/ALWAYS)
      (doseq [^HBox bar [selectors tools headers]] (.setAlignment bar Pos/CENTER_LEFT) (.add (.getStyleClass bar) "ai-compare-bar"))
      (let [top (VBox.)]
        (.addAll (.getChildren top) ^java.util.Collection (identity [selectors notice headers tools]))
        (.setTop pane top))
      (.add (.getStyleClass notice) "ai-compare-notice")
      (.addAll (.getChildren overlay) ^java.util.Collection (identity [content (:root notifications)]))
      (StackPane/setAlignment (:root notifications) Pos/TOP_CENTER)
      (StackPane/setMargin (:root notifications) (Insets. 12.0))
      (.setCenter pane overlay) (.setId pane "ai-result-compare")
      (ui/apply-css! pane) (ai-workspace/apply-styles! pane)
      (.initOwner stage owner) (.setTitle stage (str action-title " · " unit "对比"))
      (.setScene stage (Scene. pane 1440.0 900.0)) (.setMinWidth stage 1050.0) (.setMinHeight stage 650.0)
      (.setOnHidden stage (ui/event-handler _ (dispose-content!) ((:dispose! notifications))))
      (refresh!) (.show stage)
      {:stage stage :refresh! refresh!})))
