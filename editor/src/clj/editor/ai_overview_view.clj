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

(ns editor.ai-overview-view
  "Native project overview backed by local records."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.ai-overview :as overview]
            [editor.ai-roles :as roles]
            [editor.craft-project :as craft]
            [util.coll :as coll])
  (:import [java.time Instant ZoneId]
           [java.time.format DateTimeFormatter]
           [javafx.animation PauseTransition]
           [javafx.application Platform]
           [javafx.beans.property SimpleStringProperty]
           [javafx.beans.value ChangeListener]
           [javafx.event EventHandler]
           [javafx.geometry Pos]
           [javafx.scene Group Node]
           [javafx.scene.control Button ComboBox Label ScrollPane Tab TextField Tooltip]
           [javafx.scene.image Image ImageView]
           [javafx.scene.layout BorderPane ColumnConstraints GridPane HBox Priority Region RowConstraints StackPane VBox]
           [javafx.scene.shape Circle Rectangle SVGPath]
           [javafx.util Duration]))

(set! *warn-on-reflection* true)

(defn- label
  ^Label [text style]
  (doto (Label. (str text))
    (.setWrapText true) (.setMinWidth 0.0)
    (-> .getStyleClass (.add style))))

(defn- box
  ^VBox [style children]
  (let [pane (VBox. 9.0)]
    (.add (.getStyleClass pane) style)
    (.addAll (.getChildren pane) ^java.util.Collection (filterv some? children))
    pane))

(defn- icon
  ^StackPane [kind]
  (let [path (doto (SVGPath.)
               (.setContent (get {:play "M6 3 L21 12 L6 21 Z"
                                  :person "M16 6 A4 4 0 1 1 8 6 A4 4 0 1 1 16 6 M4 22 V18 C4 12 20 12 20 18 V22 Z"
                                  :warning "M12 2 L23 22 H1 Z M12 8 V14 M12 18 V19"
                                  :assets "M12 2 L22 7 V17 L12 22 L2 17 V7 Z M2 7 L12 12 L22 7 M12 12 V22"
                                  :image "M2 3 H22 V21 H2 Z M2 17 L8 11 L13 16 L17 12 L22 17 M17 7 H18"
                                  :refresh "M21 10 A9 9 0 1 0 21 17 M21 3 V10 H14"
                                  :back "M14 5 L7 12 L14 19 M7 12 H22"
                                  :document "M4 2 H15 L21 8 V22 H4 Z M15 2 V8 H21 M8 12 H17 M8 17 H17"} kind))
               (.setScaleX 0.75) (.setScaleY 0.75)
               (-> .getStyleClass (.add "ai-overview-icon")))]
    (doto (StackPane. (into-array Node [path]))
      (.setMinSize 20.0 20.0) (.setPrefSize 20.0 20.0) (.setMaxSize 20.0 20.0))))

(defn- row
  ^HBox [children]
  (doto (HBox. 12.0)
    (.setAlignment Pos/CENTER_LEFT)
    (-> .getChildren (.addAll ^java.util.Collection (vec children)))))

(defn- stretch [^Region node]
  (HBox/setHgrow node Priority/ALWAYS)
  (.setMaxWidth node Double/MAX_VALUE)
  node)

(defn- card-grid
  ^GridPane [columns]
  (let [pane (doto (GridPane.) (.setHgap 12.0) (.setVgap 12.0))]
    (dotimes [_ columns]
      (.add (.getColumnConstraints pane)
            (doto (ColumnConstraints.) (.setPercentWidth (/ 100.0 columns)) (.setHgrow Priority/ALWAYS))))
    pane))

(defn- action
  ^Button [text id f]
  (doto (Button. text)
    (.setId id)
    (-> .getStyleClass (.add "ai-overview-link"))
    (.setOnAction (reify EventHandler (handle [_ _] (f))))))

(defn- timestamp [value]
  (if-not value "尚无记录"
          (try (.format (DateTimeFormatter/ofPattern "MM-dd HH:mm")
                        (.atZone (Instant/parse value) (ZoneId/systemDefault)))
               (catch Exception _ "时间未知"))))

(defn- short-revision [value]
  (if (string/blank? value) "未登记版本" (str "版本 " (subs value 0 (min 8 (count value))))))

(defn- relative-time [value]
  (if-not value "暂无时间记录"
          (try
            (let [seconds (max 0 (.getSeconds (java.time.Duration/between (Instant/parse value) (Instant/now))))]
              (cond (< seconds 60) "刚刚" (< seconds 3600) (str (quot seconds 60) " 分钟前")
                    (< seconds 86400) (str (quot seconds 3600) " 小时前") :else (timestamp value)))
            (catch Exception _ "时间未知"))))

(defn- elapsed-time [value]
  (if-not value "未记录"
          (try (let [seconds (max 0 (.getSeconds (java.time.Duration/between (Instant/parse value) (Instant/now))))]
                 (format "%02d:%02d:%02d" (quot seconds 3600) (quot (mod seconds 3600) 60) (mod seconds 60)))
               (catch Exception _ "时间未知"))))

(defn- role-chip
  ^Label [person]
  (let [definition (or (:role-definition person) (roles/role-type person))]
    (doto (label (:title definition "职员") "ai-overview-role-chip")
      (.setStyle (str "-fx-text-fill: " (:color definition "#b7c1ce") "; -fx-background-color: derive(" (:color definition "#b7c1ce") ", -65%);")))))

(defn- portrait
  ^StackPane [person]
  (let [definition (or (:role-definition person) (roles/role-type person) {:id "pm" :title "职员" :color "#999999"})
        ^Node node (roles/make-portrait (with-meta (merge {:id "unassigned" :role-type "pm"} person) {:editor.ai-roles/definition definition}))]
    (.setScaleX node 0.48) (.setScaleY node 0.48)
    (let [image (doto (StackPane. (into-array Node [(Group. ^"[Ljavafx.scene.Node;" (into-array Node [node]))]))
                  (.setClip (Circle. 23.0 23.0 22.0)))]
      (.setMinSize image 46.0 46.0) (.setPrefSize image 46.0 46.0) (.setMaxSize image 46.0 46.0)
      (doto (StackPane. (into-array Node [image]))
        (-> .getStyleClass (.add "ai-overview-avatar"))
        (.setMinSize 48.0 48.0) (.setPrefSize 48.0 48.0) (.setMaxSize 48.0 48.0)))))

(defn- progress
  ^HBox [done total]
  (let [segments (max 1 (min 8 total))
        fraction (if (pos? total) (/ (double done) total) 0.0)
        pane (HBox. 3.0)]
    (.add (.getStyleClass pane) "ai-overview-progress")
    (doseq [index (range segments)]
      (let [part (Region.)]
        (.setMinWidth part 0.0) (.setPrefWidth part 0.0)
        (HBox/setHgrow part Priority/ALWAYS)
        (.add (.getStyleClass part) (if (< (/ (double index) segments) fraction)
                                      (if (= done total) "overview-segment-done" "overview-segment-active") "overview-segment"))
        (.add (.getChildren pane) part)))
    pane))

(defn- preview
  ^StackPane [root artifact]
  (let [file-path (or (:preview-path artifact)
                      (when-not (get-in artifact [:entrypoints :gui])
                        (:path (coll/first-where #(re-find #"(?i)\.(png|jpe?g)$" (:name % "")) (:files artifact)))))
        file (when file-path (try (craft/resource-file root file-path) (catch Exception _ nil)))
        pane (StackPane.)
        component-images (filterv #(and (re-find #"(?i)\.(png|jpe?g)$" (:name % ""))
                                        (re-find #"(?i)component|/images/" (:source-path % ""))) (:files artifact))
        clip (doto (Rectangle.) (.setArcWidth 12.0) (.setArcHeight 12.0))]
    (.add (.getStyleClass pane) "ai-overview-preview")
    (.bind (.widthProperty clip) (.widthProperty pane))
    (.bind (.heightProperty clip) (.heightProperty pane))
    (.setClip pane clip)
    (.setMinHeight pane 180.0) (.setPrefHeight pane 190.0) (.setMaxHeight pane 190.0)
    (cond
      (and (= "素材包" (:content-type artifact)) (coll/not-empty component-images))
      (let [grid (card-grid 3)]
        (.add (.getStyleClass pane) "ai-overview-asset-preview")
        (doseq [[index entry] (mapv vector (range (min 6 (count component-images))) (take 6 component-images))]
          (let [image-file (craft/resource-file root (:path entry))
                view (ImageView. (Image. (str (.toURI image-file)) 220.0 150.0 true true true))
                cell (StackPane. (into-array Node [view]))]
            (.setPreserveRatio view true) (.setFitWidth view 100.0) (.setFitHeight view 70.0)
            (.add (.getStyleClass cell) "ai-overview-asset-cell")
            (.setMinSize cell 0.0 78.0)
            (Tooltip/install cell (Tooltip. (:name entry)))
            (.add grid cell (int (mod index 3)) (int (quot index 3)))))
        (.add (.getChildren pane) grid))
      (and file (.isFile ^java.io.File file))
      (let [image (Image. (str (.toURI ^java.io.File file)) 800.0 380.0 true true true)
            view (ImageView. image)]
        (.setPreserveRatio view true) (.setFitHeight view 182.0)
        (.bind (.fitWidthProperty view) (.widthProperty pane))
        (.add (.getChildren pane) view))
      :else (.add (.getChildren pane)
                  (if (get-in artifact [:entrypoints :gui])
                    (box "ai-overview-preview-copy" [(label "GUI 界面" "ai-overview-section-title")
                                                     (label "打开成果卡查看原生 GUI" "ai-overview-muted")])
                    (let [text (string/replace (or (:text artifact) "暂无预览") #"[#*`]" "")]
                      (doto (label (subs text 0 (min 220 (count text))) "ai-overview-excerpt")
                        (.setMaxWidth Double/MAX_VALUE) (.setMaxHeight Double/MAX_VALUE))))))
    pane))

(defn make-view [root ^Tab tab open! connected!]
  (let [pane (BorderPane.)
        body (VBox. 14.0)
        search (doto (TextField.) (.setPromptText "搜索任务、职员、成果…") (.setId "ai-overview-search") (.setPrefWidth 230.0))
        stage (doto (ComboBox.) (.setId "ai-overview-stage") (.setPrefWidth 180.0))
        filter (doto (ComboBox.) (.setId "ai-overview-filter") (.setPrefWidth 130.0))
        output-filter (SimpleStringProperty. "全部成果")
        scroll-positions (atom {})
        notice (label "" "ai-overview-warning")
        read-lock (Object.)
        cache (atom {}) snapshot (atom nil) shown (atom nil)
        closed (atom false) busy (atom false) pending (atom nil)
        timer (PauseTransition. (Duration/seconds 3.0))
        render-ref (atom nil) refresh-ref (atom nil)
        show! (fn show-error [error] (.setText notice (.getMessage ^Exception error)))
        navigate! (fn open-overview-target [target]
                    (try
                      (when-not (:path target) (throw (ex-info "原始画布未登记，无法定位" {})))
                      (open! target)
                      (catch Exception e (show! e))))
        person-for (fn lookup-person [id] (coll/first-where #(= id (:id %)) (:staff @snapshot)))
        task-target (fn task-location [task] {:path (:canvas-path task) :card-id (first (:action-ids task))})
        reset-view! (fn reset-overview []
                      (reset! scroll-positions {})
                      (doseq [^ScrollPane viewport (.lookupAll body ".ai-overview-section-scroll")]
                        (.setVvalue viewport 0.0))
                      (.setValue filter "全部工作")
                      (.setValue stage "全部阶段")
                      (.setText search "")
                      (.setValue output-filter "全部成果")
                      (when @snapshot (@render-ref)))
        back (doto (action "返回总览" "ai-overview-reset" reset-view!)
               (.setGraphic (icon :back))
               (.setVisible false) (.setManaged false)
               (-> .getStyleClass (.add "ai-overview-back")))
        filter-caption (doto (label "" "ai-overview-filter-caption") (.setWrapText false))
        navigation (doto (row [back (label "/" "ai-overview-muted") filter-caption])
                     (-> .getStyleClass (.add "ai-overview-navigation")))]
    (.setId pane "ai-project-overview")
    (.setFocusTraversable pane true)
    (.bind (.visibleProperty navigation) (.visibleProperty back))
    (.bind (.managedProperty navigation) (.managedProperty back))
    (.bind (.visibleProperty notice) (.isNotEmpty (.textProperty notice)))
    (.bind (.managedProperty notice) (.visibleProperty notice))
    (.add (.getStyleClass pane) "ai-overview")
    (.add (.getStyleClass body) "ai-overview-body")
    (.setMinSize body 0.0 0.0)
    (.addAll (.getItems filter) ^java.util.Collection (identity ["全部工作" "执行中" "待我处理" "需要关注"]))
    (.setValue filter "全部工作")
    (.add (.getItems stage) "全部阶段") (.setValue stage "全部阶段")
    (let [refresh (action "" "ai-overview-refresh" (fn refresh-now [] (reset! cache {}) (@refresh-ref)))
          heading (row [(stretch (box "ai-overview-heading" [(label "项目总览" "ai-overview-title")
                                                             (doto (label (str (.getName (io/file root)) "  |  当前计划") "ai-overview-muted") (.setId "ai-overview-project-name"))])) stage filter search refresh])]
      (.setTooltip refresh (Tooltip. "刷新本地项目进度"))
      (.setGraphic refresh (icon :refresh))
      (.setTop pane (box "ai-overview-header" [heading navigation notice])))
    (.setCenter pane body)
    (reset! render-ref
            (fn render-overview []
              (when-let [model @snapshot]
                (let [query (string/lower-case (.getText search))
                      selected-stage (.getValue stage)
                      matching-stage (coll/first-where #(= (str (:title %) " · " (:plan-title % "未归类")) selected-stage) (:stages model))
                      rows (filterv #(and (or (nil? matching-stage) (= (:stage-key %) (:key matching-stage)))
                                          (or (string/blank? query) (string/includes? (string/lower-case (str (:title %) " " (:title (person-for (:staff-id %))))) query))) (:tasks model))
                      task-ids (set (mapv :id rows))
                      matches (fn matches-content [item]
                                (and (or (nil? matching-stage) (contains? task-ids (:task-id item)))
                                     (or (string/blank? query) (string/includes? (string/lower-case (str (:title item) " " (:title (person-for (:staff-id item))))) query))))
                      stages (filterv #(or (nil? matching-stage) (= (:key %) (:key matching-stage))) (:stages model))
                      mode (.getValue filter)
                      running (filterv matches (:running model))
                      issues (filterv matches (:issues model))
                      reviews (filterv matches (:reviews model))
                      artifacts (filterv #(and (matches %) (or (= "全部成果" (.getValue output-filter)) (= (:status %) (.getValue output-filter)))) (:artifacts model))
                      left (VBox. 10.0) right (VBox. 10.0)
                      middle (row [(stretch left) right])
                      compact (fn compact-copy [text style]
                                (doto (label text style) (.setWrapText false) (.setTooltip (Tooltip. (str text)))))
                      panel (fn titled-panel [id title height controls children]
                              (let [content (doto (box "ai-overview-section-content" children)
                                              (.setMinHeight Region/USE_PREF_SIZE))
                                    viewport (doto (ScrollPane. content)
                                               (.setId id)
                                               (.setFitToWidth true)
                                               (.setHbarPolicy javafx.scene.control.ScrollPane$ScrollBarPolicy/NEVER)
                                               (.setMinSize 0.0 0.0)
                                               (.setPrefHeight 0.0)
                                               (.setVvalue (get @scroll-positions id 0.0))
                                               (-> .getStyleClass (.add "ai-overview-section-scroll")))
                                    heading (doto (row (into [(stretch (label title "ai-overview-section-title"))] controls))
                                              (.setMinHeight Region/USE_PREF_SIZE))
                                    section (doto (box "ai-overview-section" [heading viewport])
                                              (.setMinHeight 0.0) (.setPrefHeight height)
                                              (.setMaxHeight Double/MAX_VALUE))]
                                (VBox/setVgrow viewport Priority/ALWAYS)
                                (VBox/setVgrow section Priority/ALWAYS)
                                section))
                      output-count (count (filterv #(= "已验收" (:status %)) (:artifacts model)))]
                  ;; JavaFX can retain a detached focus owner until the next
                  ;; pulse. Keep the editor command/menu context attached while
                  ;; replacing cards, without stealing focus from header inputs.
                  (loop [node (some-> pane .getScene .getFocusOwner)]
                    (when node
                      (if (identical? body node)
                        (.requestFocus pane)
                        (recur (.getParent node)))))
                  (doseq [^ScrollPane viewport (.lookupAll body ".ai-overview-section-scroll")]
                    (swap! scroll-positions assoc (.getId viewport) (.getVvalue viewport)))
                  (.clear (.getChildren body))
                  (.setPrefWidth left 0.0) (.setMinWidth left 0.0)
                  (.setMinHeight left 0.0) (.setMinHeight right 0.0)
                  (.setMinHeight middle 0.0)
                  (VBox/setVgrow middle Priority/ALWAYS)
                  (.bind (.prefWidthProperty right) (.multiply (.widthProperty body) 0.27))
                  (.setMinWidth right 300.0) (.setMaxWidth right 440.0)
                  (.setAlignment middle Pos/TOP_LEFT) (.setFillHeight middle true)
                  (let [meter (row [(label "当前已确认任务" "ai-overview-muted") (stretch (progress (:accepted model) (:total model)))])
                        copy (box "ai-overview-copy" [(label (str "已验收  " (:accepted model) " / " (:total model)) "ai-overview-metric-number") meter])
                        accepted (box "ai-overview-metric-main" [(row [(label "✓" "ai-overview-complete-icon") (stretch copy)])])
                        metrics (row (into [(stretch accepted)]
                                           (mapv (fn metric [[title value filter-value]]
                                                   (let [cell (box "ai-overview-metric-cell"
                                                                   [(doto (label title "ai-overview-muted")
                                                                      (.setGraphic (icon (get {"执行中" :play "待我处理" :person "需关注" :warning "交付成果" :assets} title)))
                                                                      (.setGraphicTextGap 8.0))
                                                                    (action (str value) (str "overview-metric-" filter-value) #(.setValue filter filter-value))])]
                                                     (.setPrefWidth cell 0.0) (stretch cell)))
                                                 [["执行中" (count (:running model)) "执行中"]
                                                  ["待我处理" (count (:reviews model)) "待我处理"]
                                                  ["需关注" (count (:issues model)) "需要关注"]
                                                  ["交付成果" output-count "全部工作"]])))]
                    (.add (.getStyleClass metrics) "ai-overview-summary-strip")
                    (.setMinHeight metrics Region/USE_PREF_SIZE)
                    (HBox/setHgrow accepted Priority/NEVER)
                    (.bind (.prefWidthProperty accepted) (.multiply (.widthProperty metrics) 0.42))
                    (.add (.getChildren body) metrics))
                  (when (= mode "全部工作")
                    (.add (.getChildren left)
                          (panel "ai-overview-stages-scroll" "阶段进度" 180.0 [(action "查看 AI-PM →" "ai-overview-open-pm"
                                                                                   #(when-let [entry (first stages)] (navigate! {:path (:path entry)})))]
                                 (if-not (coll/not-empty stages)
                                   [(label "在 AI-PM 中确认分配后，这里汇总阶段进度。" "ai-overview-empty")]
                                   (mapv (fn stage-row [entry index]
                                           (let [person (person-for (:staff-id (first (:tasks entry))))
                                                 title (string/replace (:title entry) #"^阶段[^：:]*[：:]\s*" "")
                                                 bar (progress (:done entry) (:total entry))
                                                 owner (row [(portrait person) (compact (:title person "未指派") "ai-overview-copy") (role-chip person)])
                                                 description (box "ai-overview-stage-copy"
                                                                  (cond-> [(doto (label title "ai-overview-subtitle")
                                                                             (.setMinHeight Region/USE_PREF_SIZE))]
                                                                    (coll/not-empty (:dependencies entry))
                                                                    (conj (doto (label (str "依赖：" (coll/join-to-string "、" (mapv :title (:dependencies entry)))) "ai-overview-dependency")
                                                                            (.setMinHeight Region/USE_PREF_SIZE)))))
                                                 line (row [(label (format "%02d" (inc index)) "ai-overview-stage-index")
                                                            (stretch description) owner
                                                            (label (str (:done entry) " / " (:total entry) " 已验收") "ai-overview-muted")
                                                            bar
                                                            (doto (label (str (if (= "已完成" (:status entry)) "✓ " "● ") (:status entry))
                                                                         (if (= "已完成" (:status entry)) "ai-overview-success" "ai-overview-accent"))
                                                              (.setMinWidth 72.0))])]
                                             (.setPrefWidth owner 215.0) (.setMinWidth owner 180.0)
                                             (.setMinWidth bar 90.0) (.setPrefWidth bar 150.0)
                                             (.setFillHeight line false)
                                             (.setMinHeight line Region/USE_PREF_SIZE)
                                             (.setMaxHeight line Region/USE_PREF_SIZE)
                                             (.add (.getStyleClass line) "ai-overview-stage-row") line)) stages (range (count stages)))))))
                  (when (contains? #{"全部工作" "执行中"} mode)
                    (let [cards (card-grid 2)
                          visible running]
                      (doseq [[index job] (mapv vector (range (count visible)) visible)]
                        (let [person (person-for (:staff-id job)) task (:task job)
                              card (row [(box "ai-overview-person-column" [(portrait person) (label (:title person "未指派") "ai-overview-subtitle")
                                                                           (role-chip person)])
                                         (stretch (box "ai-overview-execution-copy"
                                                       [(row [(stretch (compact (:title job) "ai-overview-subtitle"))
                                                              (label (if (and (connected!) (= "running" (:status job))) "执行中" "待核对") "ai-overview-count-badge")])
                                                        (label (if (pos? (:total task 0)) (str "已完成 " (:done task) " / " (:total task) " 个步骤") "当前工作正在执行，等待阶段结果") "ai-overview-muted")
                                                        (progress (:done task 0) (:total task 0))
                                                        (row [(stretch (compact (str "当前：" (get-in job [:card :title] (:title job))) "ai-overview-copy"))
                                                              (action "查看任务 ↗" nil #(navigate! {:path (:canvas-path job) :card-id (:action-id job)}))])
                                                        (row [(stretch (label (str "运行 " (elapsed-time (:started-at job)) " · "
                                                                                   (if (:updated-at job) (str "更新于 " (relative-time (:updated-at job))) "暂无更新记录")) "ai-overview-muted"))
                                                              (action "查看对话 ▢" nil #(navigate! {:path (:canvas-path job) :card-id (:action-id job) :conversation true}))])]))])]
                          (.add (.getStyleClass card) "ai-overview-execution-card")
                          (.setPrefWidth card 0.0) (.setMinWidth card 0.0)
                          (.add cards (stretch card) (int (mod index 2)) (int (quot index 2)))))
                      (.add (.getChildren left)
                            (panel "ai-overview-running-scroll" "正在执行" 180.0 [(action (str (count running) " 项任务 · 查看全部 ↗") "ai-overview-all-running" #(.setValue filter "执行中"))]
                                   (if (coll/not-empty running) [cards]
                                       [(box "ai-overview-empty-running" [(label "✓  当前没有正在执行的任务" "ai-overview-copy")
                                                                          (label "任务分配并由负责人启动后，在这里显示执行步骤与最新状态。" "ai-overview-muted")])])))))
                  (when (= mode "全部工作")
                    (let [outputs (card-grid 3)
                          visible (vec (sort-by #(get {"文档" 0 "GUI" 1 "素材包" 2} (:content-type %) 3) artifacts))]
                      (doseq [[index artifact] (mapv vector (range (count visible)) visible)]
                        (let [person (person-for (:staff-id artifact))
                              title (if-let [path (get-in artifact [:entrypoints :gui])] (.getName (io/file path))
                                            (cond-> (:title artifact) (= "已验收" (:status artifact)) (string/replace #"\s*·\s*待人工确认$" "")))
                              tile (box "ai-overview-output"
                                        [(preview root artifact)
                                         (row [(icon (case (:content-type artifact) "GUI" :image "素材包" :assets :document))
                                               (stretch (compact title "ai-overview-subtitle")) (label (:status artifact) "ai-overview-role-badge")])
                                         (row [(doto (portrait person) (.setScaleX 0.65) (.setScaleY 0.65) (.setMinSize 32.0 32.0) (.setPrefSize 32.0 32.0) (.setMaxSize 32.0 32.0))
                                               (stretch (box "ai-overview-output-owner" [(label (or (:title person) "项目成果") "ai-overview-copy")
                                                                                         (compact (str (short-revision (:revision artifact)) " · " (:content-type artifact) " · "
                                                                                                       (if (:node-count artifact) (str (:node-count artifact) " 节点") (str (count (:files artifact)) " 项资源"))) "ai-overview-muted")]))
                                               (action "查看详情 ↗" nil #(navigate! {:path (:source-path artifact) :card-id (:source-card-id artifact) :inspect-card true}))])])]
                          (.setPrefWidth tile 0.0) (.setMinWidth tile 0.0)
                          (.add outputs (stretch tile) (int (mod index 3)) (int (quot index 3)))))
                      (.add (.getChildren left)
                            (panel "ai-overview-outputs-scroll" "交付成果" 300.0
                                   [(row (mapv (fn output-status [value]
                                                 (let [button (action (str value " " (count (filterv #(or (= "全部成果" value) (= value (:status %))) (:artifacts model))))
                                                                      (str "ai-overview-output-" value) #(.setValue output-filter value))]
                                                   (.add (.getStyleClass button) (if (= value (.getValue output-filter)) "ai-overview-tab-active" "ai-overview-tab")) button)) ["已验收" "待审核" "全部成果"]))]
                                   (if (coll/not-empty artifacts) [outputs] [(label "没有符合筛选条件的成果" "ai-overview-empty")])))))
                  (let [review-rows (mapv (fn review-row [review]
                                            (let [badge (doto (StackPane. (into-array Node [(icon (if (= "方案待选择" (:label review)) :image :document))]))
                                                          (-> .getStyleClass (.add "ai-overview-review-icon")))
                                                  line (row [badge
                                                             (stretch (box "ai-overview-copy" [(label (:label review) "ai-overview-subtitle")
                                                                                               (compact (str (:title (person-for (:staff-id review)) "项目管理") " · " (:title review)) "ai-overview-muted")
                                                                                               (when (:revision review) (label (short-revision (:revision review)) "ai-overview-version"))]))
                                                             (action (get {"任务分配待确认" "审阅分配" "方案待选择" "选择方案" "成果版本待批准" "查看版本" "任务待验收" "查看验收"} (:label review) "查看详情") nil #(navigate! (select-keys review [:path :card-id])))])]
                                              (.add (.getStyleClass line) "ai-overview-review-row") line))
                                          reviews)
                        issue-rows (mapv (fn issue-row [job]
                                           (box "ai-overview-issue"
                                                [(label "△  需要关注" "ai-overview-warning")
                                                 (compact (:title job) "ai-overview-copy")
                                                 (label (or (:block-reason job) (:error job) "执行状态需要核对") "ai-overview-muted")
                                                 (label (if (coll/not-empty (:dependents job)) (str "影响 " (count (:dependents job)) " 项后续任务") "未关联后续阶段任务") "ai-overview-muted")
                                                 (action "定位任务 ↗" nil #(navigate! {:path (:canvas-path job) :card-id (:action-id job)}))]))
                                         issues)]
                    (.add (.getChildren right)
                          (panel "ai-overview-reviews-scroll" "待我处理" 400.0 [(label (str (count reviews)) "ai-overview-count-badge")]
                                 (into (if (coll/not-empty review-rows) review-rows [(label "暂无待处理事项" "ai-overview-empty")])
                                       issue-rows))))
                  (.add (.getChildren right)
                        (panel "ai-overview-staff-scroll" "职员状态" 400.0 []
                               (mapv (fn staff-row [person]
                                       (let [active (count (filterv #(contains? #{"running" "unknown"} (:status %)) (:jobs person)))
                                             queued (count (filterv #(= "queued" (:status %)) (:jobs person)))
                                             line (row [(portrait person)
                                                        (stretch (compact (str (:title person) " · " (get-in person [:role-definition :title] "职员")) "ai-overview-copy"))
                                                        (label (str "● " (cond (false? (:enabled person)) "停用" (pos? active) "执行中" :else "空闲")
                                                                    (when (pos? queued) (str " · " queued))) (if (pos? active) "ai-overview-accent" "ai-overview-muted"))])]
                                         (.add (.getStyleClass line) "ai-overview-staff-row") line)) (:staff model))))
                  (when (= mode "全部工作")
                    (.add (.getChildren left)
                          (panel "ai-overview-tasks-scroll" "项目任务明细" 200.0
                                 [(label (str (count rows)) "ai-overview-count-badge")]
                                 (if-not (coll/not-empty rows)
                                   [(label "没有符合筛选条件的任务" "ai-overview-muted")]
                                   (mapv (fn task-row [task]
                                           (doto (row [(stretch (label (:title task) "ai-overview-copy"))
                                                       (compact (:title (person-for (:staff-id task)) "未指派") "ai-overview-muted")
                                                       (label (:status task) "ai-overview-accent")
                                                       (action "打开工作 ↗" nil #(navigate! (task-target task)))])
                                             (.setMinHeight Region/USE_PREF_SIZE)
                                             (-> .getStyleClass (.add "ai-overview-task-row")))) rows)))))
                  ;; Allocate fixed shares of the available height. Scrollable
                  ;; contents must never enlarge the dashboard's outer bounds.
                  (doseq [^VBox column [left right]]
                    (let [sections (filterv #(.contains (.getStyleClass ^Node %) "ai-overview-section") (.getChildren column))
                          weight (reduce + 0.0 (mapv #(.getPrefHeight ^Region %) sections))
                          grid (doto (GridPane.) (.setVgap 10.0) (.setMinSize 0.0 0.0))]
                      (.add (.getColumnConstraints grid) (doto (ColumnConstraints.) (.setPercentWidth 100.0)))
                      (.clear (.getChildren column))
                      (doseq [[index ^Region section] (mapv vector (range (count sections)) sections)]
                        (.add (.getRowConstraints grid) (doto (RowConstraints.)
                                                          (.setPercentHeight (* 100.0 (/ (.getPrefHeight section) weight)))))
                        (.add grid section 0 (int index)))
                      (VBox/setVgrow grid Priority/ALWAYS)
                      (.add (.getChildren column) grid)))
                  (.add (.getChildren body) middle)
                  (.applyCss body) (.layout body)
                  (doseq [^ScrollPane viewport (.lookupAll body ".ai-overview-section-scroll")]
                    (.setVvalue viewport (get @scroll-positions (.getId viewport) 0.0)))))))
    (reset! refresh-ref
            (fn refresh-overview []
              (when (and (not @closed) (.isSelected tab) (some-> pane .getScene .getWindow .isShowing) (compare-and-set! busy false true))
                (reset! pending
                        (future
                          (try
                            (let [next (locking read-lock (when-not @closed (overview/load-snapshot root cache)))]
                              (Platform/runLater
                                (fn apply-overview []
                                  (when-not @closed
                                    (let [connected (connected!)]
                                      (.setText notice (coll/join-to-string "\n" (:errors next)))
                                      (when (or (not= [next connected] @shown) (coll/not-empty (:running next)))
                                        (reset! snapshot next)
                                        (reset! shown [next connected])
                                        (when-let [title (:project-title next)]
                                          (.setText ^Label (.lookup pane "#ai-overview-project-name")
                                                    (str title "  |  " (or (some-> next :stages first :plan-title
                                                                                   (string/replace #"^AI-PM[_·\s]*|_\d{8}$" "")) "当前项目") " · 当前计划")))
                                        (let [items (coll/into-> (:stages next) ["全部阶段"] (map #(str (:title %) " · " (:plan-title % "未归类"))))
                                              selected (.getValue stage)]
                                          (.setAll (.getItems stage) ^java.util.Collection items)
                                          (.setValue stage (if (contains? (set items) selected) selected "全部阶段")))
                                        (@render-ref)))))))
                            (catch Exception e (Platform/runLater (fn show-read-error [] (when-not @closed (show! e)))))
                            (finally (reset! busy false))))))))
    (doseq [property [(.textProperty search) (.valueProperty stage) (.valueProperty filter) output-filter]]
      (.addListener ^javafx.beans.value.ObservableValue property
                    (reify ChangeListener
                      (changed [_ _ _ _]
                        (let [filtered (or (not= "全部工作" (.getValue filter))
                                           (not= "全部阶段" (.getValue stage))
                                           (not (string/blank? (.getText search)))
                                           (not= "全部成果" (.getValue output-filter)))]
                          (.setVisible back filtered)
                          (.setManaged back filtered)
                          (.setText filter-caption
                                    (str (get {"执行中" "正在执行" "待我处理" "待我处理" "需要关注" "需要关注"}
                                              (.getValue filter) "筛选结果")
                                         (when (not= "全部阶段" (.getValue stage)) (str " · " (.getValue stage)))
                                         (when (not= "全部成果" (.getValue output-filter)) (str " · " (.getValue output-filter))))))
                        (when @snapshot (@render-ref))))))
    (.addListener (.selectedProperty tab) (reify ChangeListener (changed [_ _ _ selected] (when selected (@refresh-ref)))))
    (.setOnFinished timer (reify EventHandler (handle [_ _] (when-not @closed (@refresh-ref) (.playFromStart timer)))))
    (.play timer)
    {:root pane :refresh! @refresh-ref :reset! reset-view! :snapshot snapshot
     :close! (fn close-overview [] (reset! closed true) (.stop timer) (when-let [task @pending] (future-cancel task)) (locking read-lock nil))}))
