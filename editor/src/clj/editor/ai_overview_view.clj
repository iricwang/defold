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
            [editor.ai-studio :as studio]
            [editor.craft-project :as craft]
            [editor.ui :as ui]
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
           [javafx.scene.shape Circle Rectangle]
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
  (doto (studio/decorate-navigation! (Button. text))
    (.setId id)
    (-> .getStyleClass (.add "ai-overview-link"))
    (.setOnAction (reify EventHandler (handle [_ _] (f))))))

(defn- timestamp [value]
  (if-not value "尚无记录"
          (try (.format (DateTimeFormatter/ofPattern "MM-dd")
                        (.atZone (Instant/parse value) (ZoneId/systemDefault)))
               (catch Exception _ "时间未知"))))

(defn- elapsed-time [value]
  (if-not value "未记录"
          (try (let [seconds (max 0 (.getSeconds (java.time.Duration/between (Instant/parse value) (Instant/now))))]
                 (format "%02d:%02d:%02d" (quot seconds 3600) (quot (mod seconds 3600) 60) (mod seconds 60)))
               (catch Exception _ "时间未知"))))

(defn portrait
  ^StackPane [person]
  (let [definition (or (:role-definition person) (roles/role-type person) {:id "pm" :title "职员" :color "#999999"})
        ^Node node (roles/make-portrait (with-meta (merge {:id "unassigned" :role-type "pm"} person) {:editor.ai-roles/definition definition}))]
    (.setScaleX node 0.36) (.setScaleY node 0.36)
    (let [image (doto (StackPane. (into-array Node [(Group. ^"[Ljavafx.scene.Node;" (into-array Node [node]))]))
                  (.setClip (Circle. 18.0 18.0 18.0)))]
      (.setMinSize image 36.0 36.0) (.setPrefSize image 36.0 36.0) (.setMaxSize image 36.0 36.0)
      (doto (StackPane. (into-array Node [image]))
        (-> .getStyleClass (.add "ai-overview-avatar"))
        (.setMinSize 38.0 38.0) (.setPrefSize 38.0 38.0) (.setMaxSize 38.0 38.0)))))

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
    (.setMinHeight pane 90.0) (.setPrefHeight pane 110.0) (.setMaxHeight pane 110.0)
    (cond
      (and (= "素材包" (:content-type artifact)) (coll/not-empty component-images))
      (let [grid (card-grid 3)]
        (.add (.getStyleClass pane) "ai-overview-asset-preview")
        (doseq [[index entry] (mapv vector (range (min 6 (count component-images))) (take 6 component-images))]
          (let [image-file (craft/resource-file root (:path entry))
                view (ImageView. (Image. (str (.toURI image-file)) 220.0 150.0 true true true))
                cell (StackPane. (into-array Node [view]))]
            (.setPreserveRatio view true)
            (.bind (.fitWidthProperty view) (.subtract (.widthProperty cell) 4.0))
            (.bind (.fitHeightProperty view) (.multiply (.subtract (.heightProperty pane) 24.0) 0.5))
            (.add (.getStyleClass cell) "ai-overview-asset-cell")
            (.setMinSize cell 0.0 0.0)
            (Tooltip/install cell (Tooltip. (:name entry)))
            (.add grid cell (int (mod index 3)) (int (quot index 3)))))
        (.add (.getChildren pane) grid))
      (and file (.isFile ^java.io.File file))
      (let [image (Image. (str (.toURI ^java.io.File file)) 800.0 380.0 true true true)
            view (ImageView. image)]
        (.setPreserveRatio view true) (.bind (.fitHeightProperty view) (.subtract (.heightProperty pane) 2.0))
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
  (let [pane (doto (BorderPane.) (.setId "ai-project-overview") (.setFocusTraversable true))
        body (doto (VBox. 18.0) (.setMinSize 0.0 0.0) (-> .getStyleClass (.add "ai-overview-body")))
        search (doto (TextField.) (.setId "ai-overview-search") (.setPromptText "搜索任务、成果、成员…"))
        stage (doto (ComboBox.) (.setId "ai-overview-stage") (.setMinWidth 180.0) (.setPrefWidth 280.0) (.setMaxWidth 360.0))
        filter (doto (ComboBox.) (.setId "ai-overview-filter") (.setMinWidth 140.0) (.setPrefWidth 150.0))
        section (SimpleStringProperty. "overview")
        output-mode (SimpleStringProperty. "grid")
        output-filter (SimpleStringProperty. "全部成果")
        scroll-positions (atom {})
        reset-scroll (atom false)
        notice (label "" "ai-overview-warning")
        heading-title (label "项目总览" "ai-overview-title")
        subtitle (doto (label (studio/project-name root) "ai-overview-muted") (.setId "ai-overview-project-name"))
        project-status (label "项目工作台" "studio-project-status")
        period (label "尚无时间记录" "studio-project-period")
        read-lock (Object.) cache (atom {}) snapshot (atom nil) shown (atom nil)
        closed (atom false) busy (atom false) pending (atom nil)
        timer (PauseTransition. (Duration/seconds 3.0))
        render-ref (atom nil) refresh-ref (atom nil)
        show! (fn show-error [error] (.setText notice (.getMessage ^Exception error)))
        navigate! (fn navigate [target]
                    (try
                      (when-not (or (:path target) (:staff-id target) (:task-id target) (:overview target))
                        (throw (ex-info "原始工作未登记，无法定位" {})))
                      (open! target)
                      (catch Exception e (show! e))))
        reset-view! (fn reset-overview []
                      (reset! scroll-positions {})
                      (reset! reset-scroll true)
                      (.setValue section "overview") (.setValue filter "全部工作")
                      (.setValue stage "全部阶段") (.setText search "")
                      (.setValue output-filter "全部成果")
                      (when @snapshot (@render-ref)))
        back (doto (action "" "ai-overview-reset" reset-view!) (.setGraphic (studio/icon :back))
                   (.setTooltip (Tooltip. "返回项目总览")) (.setAccessibleText "返回项目总览")
                   (.setVisible false) (.setManaged false))
        filters (doto (row [(label "阶段" "ai-overview-muted") stage (label "状态" "ai-overview-muted") filter search])
                  (.setId "ai-overview-filters"))]
    (HBox/setHgrow stage Priority/ALWAYS)
    (doseq [^ComboBox combo [stage filter]]
      (.setMinHeight combo 36.0) (.setPrefHeight combo 36.0) (.setMaxHeight combo 36.0)
      (let [tooltip (Tooltip.)]
        (.bind (.textProperty tooltip) (.valueProperty combo))
        (.setTooltip combo tooltip)))
    (.addAll (.getStyleClass pane) ^"[Ljava.lang.Object;" (to-array ["ai-overview" "ai-studio"]))
    (.addAll (.getItems filter) ^"[Ljava.lang.Object;" (to-array ["全部工作" "执行中" "待我处理" "需要关注"]))
    (.setValue filter "全部工作")
    (.add (.getItems stage) "全部阶段") (.setValue stage "全部阶段")
    (.setVisible filters false) (.setManaged filters false)
    (.bind (.visibleProperty notice) (.isNotEmpty (.textProperty notice)))
    (.bind (.managedProperty notice) (.visibleProperty notice))
    (.setTop pane (box "ai-overview-header"
                       [(row [back (stretch (box "studio-overview-heading" [heading-title subtitle])) project-status period]) filters notice]))
    (.addListener (.heightProperty pane)
                  (reify ChangeListener (changed [_ _ _ height] (.pseudoClassStateChanged pane (javafx.css.PseudoClass/getPseudoClass "compact") (< (double height) 800.0)))))
    (.setCenter pane body)
    (.add (.getChildren body) (label "正在读取项目工作记录…" "ai-overview-muted"))
    (reset! render-ref
            (fn render-overview []
              (when-let [model @snapshot]
                (let [query (string/lower-case (.getText search))
                      selected-stage (.getValue stage)
                      matching-stage (coll/first-where #(= (str (:title %) " · " (:plan-title % "未归类")) selected-stage) (:stages model))
                      person-for (fn lookup-person [id] (coll/first-where #(= id (:id %)) (:staff model)))
                      rows (filterv #(and (or (nil? matching-stage) (= (:stage-key %) (:key matching-stage)))
                                          (or (string/blank? query) (string/includes? (string/lower-case (str (:title %) " " (:title (person-for (:staff-id %))))) query))) (:tasks model))
                      ids (set (mapv :id rows))
                      matches (fn matches-content [item]
                                (and (or (nil? matching-stage) (contains? ids (:task-id item)))
                                     (or (string/blank? query) (string/includes? (string/lower-case (str (:title item) " " (:title (person-for (:staff-id item))))) query))))
                      stages (filterv #(or (nil? matching-stage) (= (:key %) (:key matching-stage))) (:stages model))
                      members (filterv #(or (string/blank? query) (string/includes? (string/lower-case (str (:title %) " " (get-in % [:role-definition :title]))) query)) (:staff model))
                      running (filterv matches (:running model)) reviews (filterv matches (:reviews model)) issues (filterv matches (:issues model))
                      artifacts (vec (sort-by #(get {"文档" 0 "GUI" 1 "素材包" 2} (:content-type %) 3) (filterv #(and (matches %) (or (= "全部成果" (.getValue output-filter)) (= (:status %) (.getValue output-filter)))) (:artifacts model))))
                      active (get {"执行中" "running" "待我处理" "reviews" "需要关注" "issues"} (.getValue filter) (.getValue section))
                      home (= "overview" active)
                      compact (fn compact-copy [text style] (doto (label text style) (.setWrapText false) (.setTooltip (Tooltip. (str text)))))
                      see-all (fn see-all [target] (action "查看全部  →" (str "ai-overview-all-" target) #(.setValue section target)))
                      panel (fn panel [id title controls content]
                              (let [scroll (doto (ScrollPane. content) (.setId (str "ai-overview-" id "-scroll"))
                                                 (.setFitToWidth true) (.setMinSize 0.0 0.0) (.setPrefHeight 0.0)
                                                 (.setHbarPolicy javafx.scene.control.ScrollPane$ScrollBarPolicy/NEVER)
                                                 (-> .getStyleClass (.add "ai-overview-section-scroll")))
                                    actions (doto (HBox. 4.0 (into-array Node controls))
                                              (.setAlignment Pos/CENTER_RIGHT)
                                              (.setMaxWidth Region/USE_PREF_SIZE)
                                              (-> .getStyleClass (.add "studio-section-actions")))
                                    head (doto (row [(stretch (label title "ai-overview-section-title")) actions])
                                           (-> .getStyleClass (.add "studio-section-heading")))
                                    result (doto (box "ai-overview-section" [head scroll]) (.setMinSize 0.0 0.0) (.setMaxHeight Double/MAX_VALUE))]
                                (VBox/setVgrow scroll Priority/ALWAYS)
                                result))
                      column (fn column [sections weights]
                               (let [grid (doto (GridPane.) (.setVgap 16.0) (.setMinSize 0.0 0.0))]
                                 (.add (.getColumnConstraints grid) (doto (ColumnConstraints.) (.setPercentWidth 100.0)))
                                 (doseq [[index node weight] (mapv vector (range) sections weights)]
                                   (.add (.getRowConstraints grid) (doto (RowConstraints.) (.setPercentHeight weight) (.setMinHeight 0.0)))
                                   (.add grid node 0 (int index)))
                                 grid))]
                  (loop [node (some-> pane .getScene .getFocusOwner)]
                    (when node (if (identical? body node) (.requestFocus pane) (recur (.getParent node)))))
                  (when-not @reset-scroll
                    (doseq [^ScrollPane scroll (.lookupAll body ".ai-overview-section-scroll")]
                      (swap! scroll-positions assoc (.getId scroll) [(.getVvalue scroll) (.getHvalue scroll)])))
                  (reset! reset-scroll false)
                  (.clear (.getChildren body))
                  (.setText heading-title (get {"overview" "项目总览" "tasks" "项目任务" "list" "任务列表" "stages" "阶段进度" "outputs" "交付成果" "staff" "项目成员" "resources" "项目资源" "running" "正在执行" "reviews" "待我处理" "issues" "需要关注"} active "项目总览"))
                  (.setText subtitle (str (:project-title model (studio/project-name root)) " · "
                                          (if-let [current (or (coll/first-where #(= "执行中" (:status %)) stages) (first stages))]
                                            (str "当前阶段：" (:title current)) "尚未分配项目阶段")))
                  (.pseudoClassStateChanged project-status (javafx.css.PseudoClass/getPseudoClass "warning") (pos? (count (:issues model))))
                  (.setText project-status (cond (pos? (count (:running model))) "▷  进行中" (pos? (count (:issues model))) "△  需关注" (and (pos? (:total model 0)) (= (:accepted model) (:total model))) "✓  已验收" :else "○  待执行"))
                  (let [dates (vec (sort (filterv string? (mapv :created-at (into (vec (:tasks model)) (:artifacts model))))))]
                    (.setText period (if (coll/not-empty dates) (str (subs (first dates) 0 (min 10 (count (first dates)))) " – " (subs (last dates) 0 (min 10 (count (last dates))))) "尚无时间记录")))
                  (.setVisible filters (not home)) (.setManaged filters (not home))
                  (when home
                    (let [metrics (card-grid 5)
                          percent (if (pos? (:total model 0)) (/ (double (:accepted model)) (:total model)) 0.0)
                          track (doto (StackPane.) (-> .getStyleClass (.add "studio-progress-track")))
                          fill (doto (Region.) (.setMaxHeight 12.0) (-> .getStyleClass (.add "studio-progress-fill")))
                          complete (row [(studio/icon :target)
                                         (stretch (box "studio-progress-copy"
                                                       [(label "项目进度" "ai-overview-copy")
                                                        (label (str "已验收 " (:accepted model 0) " / " (:total model 0)) "ai-overview-copy")
                                                        (row [(stretch track) (label (str (int (* 100 percent)) "%") "ai-overview-success")])]))])]
                      (.setAlignment track Pos/CENTER_LEFT)
                      (.bind (.prefWidthProperty fill) (.multiply (.widthProperty track) percent))
                      (.bind (.maxWidthProperty fill) (.prefWidthProperty fill)) (.add (.getChildren track) fill)
                      (.add (.getStyleClass complete) "studio-metric") (.add (.getStyleClass complete) "studio-project-metric")
                      (.clear (.getColumnConstraints metrics))
                      (doseq [width [36.0 16.0 16.0 16.0 16.0]] (.add (.getColumnConstraints metrics) (doto (ColumnConstraints.) (.setPercentWidth width))))
                      (.add metrics complete 0 0)
                      (doseq [[index title kind count target] [[1 "执行中" :play (count (:running model)) "running"]
                                                               [2 "待我处理" :people (count (:reviews model)) "reviews"]
                                                               [3 "需关注" :warning (count (:issues model)) "issues"]
                                                               [4 "交付成果" :assets (count (:artifacts model)) "outputs"]]]
                        (let [button (action "" (str "overview-metric-" target) #(.setValue section target))
                              graphic (box "studio-metric-copy" [(row [(studio/icon kind) (label title "ai-overview-copy")])
                                                                 (row [(label "+" "studio-metric-plus") (label count "studio-metric-number")])])]
                          (.setGraphic button graphic) (.setMaxWidth button Double/MAX_VALUE) (.setMaxHeight button Double/MAX_VALUE)
                          (.add (.getStyleClass button) "studio-metric") (.add metrics button (int index) 0)))
                      (.add (.getStyleClass metrics) "ai-overview-summary-strip")
                      (.setMinHeight metrics 100.0) (.setPrefHeight metrics 100.0) (.setMaxHeight metrics 100.0)
                      (.add (.getChildren body) metrics)))
                  (let [phase-flow (doto (HBox.) (.setAlignment Pos/TOP_LEFT))
                        _ (doseq [[index entry] (mapv vector (range) stages)]
                            (let [done (= "已完成" (:status entry)) current (contains? #{"执行中" "受阻" "待验收"} (:status entry))
                                  tone (cond done "done" current "active" :else "pending")
                                  dot (doto (label (if done "✓" (format "%02d" (inc index))) "studio-phase-dot") (-> .getStyleClass (.add (str "studio-phase-" tone))))
                                  line (doto (Region.) (-> .getStyleClass (.add "studio-phase-line")) (-> .getStyleClass (.add (str "studio-phase-" tone))))
                                  title (doto (label (:title entry) "ai-overview-subtitle") (.setMinHeight Region/USE_PREF_SIZE) (.setTooltip (Tooltip. (:title entry))))
                                  step (box "studio-phase" [(row [dot (stretch line)])
                                                            (row [(label (format "%02d" (inc index)) "studio-phase-index") title])
                                                            (label (cond done "已验收" current (:status entry) :else "未开始") (if done "ai-overview-success" "ai-overview-muted"))])]
                              (.setMinWidth step 180.0) (.setPrefWidth step 210.0) (HBox/setHgrow step Priority/ALWAYS)
                              (.setOnMouseClicked step (ui/event-handler _ (.setValue stage (str (:title entry) " · " (:plan-title entry "未归类"))) (.setValue section "tasks")))
                              (.add (.getChildren phase-flow) step)))
                        phase-panel (panel "stages" "阶段进度" [(see-all "stages")]
                                           (if (coll/not-empty stages) phase-flow (label "在 AI-PM 中确认阶段分配后显示进度" "ai-overview-empty")))
                        phase-scroll ^ScrollPane (.lookup ^VBox phase-panel "#ai-overview-stages-scroll")
                        _ (when phase-scroll (.setFitToWidth phase-scroll true) (.setHbarPolicy phase-scroll javafx.scene.control.ScrollPane$ScrollBarPolicy/AS_NEEDED))
                        running-content (box "studio-running-list"
                                             (mapv (fn running-row [job]
                                                     (let [person (person-for (:staff-id job))]
                                                       (doto (row [(portrait person)
                                                                   (stretch (box "ai-overview-copy" [(label (:title job) "ai-overview-subtitle")
                                                                                                     (label (str (:title person "未指派") " · " (if (and (connected!) (= "running" (:status job))) "执行中" "待核对") " · " (elapsed-time (:started-at job))) "ai-overview-muted")]))
                                                                   (action "查看任务  →" nil #(navigate! (if (:task-id job) {:task-id (:task-id job)} {:path (:canvas-path job) :card-id (:action-id job)})))
                                                                   (action "对话" nil #(navigate! {:path (:canvas-path job) :card-id (:action-id job) :conversation true}))])
                                                         (-> .getStyleClass (.add "ai-overview-execution-card"))))) running))
                        empty-running (doto (box "ai-overview-empty-running" [(studio/icon :empty) (label "当前没有正在执行的任务" "ai-overview-subtitle") (label "任务分配并由负责人启动后，将显示在这里" "ai-overview-muted")]) (.setAlignment Pos/CENTER))
                        running-panel (panel "running" (str "正在执行 · " (count running)) [] (if (coll/not-empty running) running-content empty-running))
                        outputs (card-grid (if (= "list" (.getValue output-mode)) 1 3))
                        _ (doseq [[index artifact] (mapv vector (range) artifacts)]
                            (let [person (person-for (:staff-id artifact))
                                  name (if-let [path (get-in artifact [:entrypoints :gui])] (.getName (io/file path)) (:title artifact))
                                  tile (box "ai-overview-output" [(preview root artifact)
                                                                  (row [(studio/icon :document) (stretch (compact name "ai-overview-subtitle"))])
                                                                  (row [(stretch (compact (str (:title person "项目成果") " · " (timestamp (:created-at artifact))) "ai-overview-muted")) (label (:status artifact "待审核") "studio-delivery-status")])])]
                              (.setMinWidth tile 0.0) (.setPrefWidth tile 0.0)
                              (.setOnMouseClicked tile (ui/event-handler _ (navigate! {:path (:source-path artifact) :card-id (:source-card-id artifact) :inspect-card true})))
                              (.setFocusTraversable tile true)
                              (.setOnKeyPressed tile (ui/event-handler event (when (= javafx.scene.input.KeyCode/ENTER (.getCode ^javafx.scene.input.KeyEvent event))
                                                                               (navigate! {:path (:source-path artifact) :card-id (:source-card-id artifact) :inspect-card true}))))
                              (.add outputs tile (int (if (= "list" (.getValue output-mode)) 0 (mod index 3))) (int (if (= "list" (.getValue output-mode)) index (quot index 3))))))
                        output-panel (panel "outputs" (str "交付成果 · " (count artifacts))
                                            [(doto (studio/button "" "ai-overview-grid" :home #(.setValue output-mode "grid"))
                                               (.setGraphic (studio/compact-icon :home)) (.setTooltip (Tooltip. "网格视图")) (.setAccessibleText "网格视图")
                                               (.pseudoClassStateChanged (javafx.css.PseudoClass/getPseudoClass "selected") (= "grid" (.getValue output-mode))))
                                             (doto (studio/button "" "ai-overview-list" :list #(.setValue output-mode "list"))
                                               (.setGraphic (studio/compact-icon :list)) (.setTooltip (Tooltip. "列表视图")) (.setAccessibleText "列表视图")
                                               (.pseudoClassStateChanged (javafx.css.PseudoClass/getPseudoClass "selected") (= "list" (.getValue output-mode)))) (see-all "outputs")]
                                            (if (coll/not-empty artifacts) outputs (label "尚无交付成果" "ai-overview-empty")))
                        review-panel (panel "reviews" (str "待我处理  " (count reviews)) [(see-all "reviews")]
                                            (box "studio-review-list" (if-not (coll/not-empty reviews) [(label "暂无待处理事项" "ai-overview-empty")]
                                                                              (mapv (fn review-row [review]
                                                                                      (doto (row [(studio/icon :tasks)
                                                                                                  (stretch (box "ai-overview-copy" [(label (:label review) "ai-overview-subtitle")
                                                                                                                                    (compact (str (:title (person-for (:staff-id review)) "项目管理") " · " (:title review)) "ai-overview-muted")]))
                                                                                                  (action "›" nil #(navigate! (if (= "返修待确认" (:label review)) {:staff-id (:staff-id review)} (select-keys review [:path :card-id :review-artifact]))))])
                                                                                        (-> .getStyleClass (.add "ai-overview-review-row")))) reviews))))
                        issue-panel (panel "issues" (str "需要关注  " (count issues)) [(studio/icon :warning)]
                                           (box "studio-issue-list" (if-not (coll/not-empty issues) [(label "暂无需要关注的问题" "ai-overview-empty")]
                                                                            (mapv (fn issue-row [job]
                                                                                    (box "ai-overview-issue" [(row [(studio/icon :warning) (stretch (compact (:title job) "ai-overview-subtitle"))
                                                                                                                    (action "›" nil #(navigate! {:path (:canvas-path job) :card-id (:action-id job)}))])
                                                                                                              (label (or (:block-reason job) (:error job) "执行状态需要核对") "ai-overview-copy")
                                                                                                              (label (if (coll/not-empty (:dependents job)) (str "影响 " (count (:dependents job)) " 项后续任务") "未关联后续阶段任务") "ai-overview-muted")])) issues))))
                        _ (.add (.getStyleClass ^VBox issue-panel) "studio-warning-panel")
                        staff-panel (panel "staff" (str "成员状态 · " (count members)) [(see-all "staff")]
                                           (box "studio-member-list"
                                                (mapv (fn member-row [person]
                                                        (let [jobs (:jobs person)
                                                              status (cond (false? (:enabled person)) "停用" (coll/any? #(= "unknown" (:status %)) jobs) "待核对"
                                                                           (coll/any? #(= "running" (:status %)) jobs) (if (connected!) "执行中" "待核对") :else "空闲")
                                                              line (row [(portrait person) (label (:title person) "ai-overview-subtitle")
                                                                         (stretch (label (get-in person [:role-definition :title] "职员") "ai-overview-muted"))
                                                                         (label (str "● " status) (if (= "待核对" status) "ai-overview-warning" "ai-overview-success"))])]
                                                          (.add (.getStyleClass line) "ai-overview-staff-row")
                                                          (.setOnMouseClicked line (ui/event-handler _ (navigate! {:staff-id (:id person)})))
                                                          (.setFocusTraversable line true)
                                                          (.setOnKeyPressed line (ui/event-handler event (when (= javafx.scene.input.KeyCode/ENTER (.getCode ^javafx.scene.input.KeyEvent event)) (navigate! {:staff-id (:id person)}))))
                                                          line)) members)))
                        task-panel (panel "tasks" (str "项目任务明细 · " (count rows)) []
                                          (box "studio-task-list" (if-not (coll/not-empty rows) [(label "没有符合筛选条件的任务" "ai-overview-empty")]
                                                                          (mapv (fn task-row [task]
                                                                                  (doto (row [(studio/icon :tasks) (stretch (label (:title task) "ai-overview-copy"))
                                                                                              (label (:title (person-for (:staff-id task)) "未指派") "ai-overview-muted")
                                                                                              (label (:status task) "ai-overview-accent") (action "查看任务  →" nil #(navigate! {:task-id (:id task)}))])
                                                                                    (-> .getStyleClass (.add "ai-overview-task-row")))) rows))))
                        content (if home
                                  (let [grid (card-grid 1)]
                                    (.clear (.getColumnConstraints grid))
                                    (doseq [width [68.0 32.0]] (.add (.getColumnConstraints grid) (doto (ColumnConstraints.) (.setPercentWidth width))))
                                    (.add (.getRowConstraints grid) (doto (RowConstraints.) (.setPercentHeight 100.0) (.setMinHeight 0.0)))
                                    (.add grid (column [phase-panel running-panel output-panel] [26.0 31.0 43.0]) 0 0)
                                    (.add grid (column [review-panel issue-panel staff-panel] [30.0 26.0 44.0]) 1 0)
                                    grid)
                                  (get {"tasks" task-panel "list" task-panel "stages" phase-panel "outputs" output-panel "resources" output-panel "staff" staff-panel "running" running-panel "reviews" review-panel "issues" issue-panel} active task-panel))]
                    (.setMinSize ^Region content 0.0 0.0) (VBox/setVgrow content Priority/ALWAYS)
                    (.add (.getChildren body) content)
                    (doseq [^ScrollPane scroll (.lookupAll body ".ai-overview-section-scroll")
                            :let [[v h] (get @scroll-positions (.getId scroll) [0.0 0.0])]]
                      (.setVvalue scroll v) (.setHvalue scroll h)))))))
    (reset! refresh-ref
            (fn refresh-overview []
              (when (and (not @closed) (.isSelected tab) (some-> pane .getScene .getWindow .isShowing) (compare-and-set! busy false true))
                (reset! pending (future
                                  (try
                                    (let [next (locking read-lock (when-not @closed (overview/load-snapshot root cache)))]
                                      (Platform/runLater
                                        (fn apply-overview []
                                          (when-not @closed
                                            (let [connected (connected!)]
                                              (.setText notice (coll/join-to-string "\n" (:errors next)))
                                              (when (or (not= [next connected] @shown) (coll/not-empty (:running next)))
                                                (reset! snapshot next) (reset! shown [next connected])
                                                (let [selected (.getValue stage)
                                                      items (coll/into-> (:stages next) ["全部阶段"] (map #(str (:title %) " · " (:plan-title % "未归类"))))]
                                                  (.setAll (.getItems stage) ^java.util.Collection items)
                                                  (.setValue stage (if (contains? (set items) selected) selected "全部阶段")))
                                                (@render-ref)))))))
                                    (catch Exception e (Platform/runLater (fn show-read-error [] (when-not @closed (show! e)))))
                                    (finally (reset! busy false))))))))
    (doseq [property [(.textProperty search) (.valueProperty stage) (.valueProperty filter) output-filter section output-mode]]
      (.addListener ^javafx.beans.value.ObservableValue property
                    (reify ChangeListener
                      (changed [_ _ _ _]
                        (let [filtered (or (not= "overview" (.getValue section)) (not= "全部工作" (.getValue filter)) (not= "全部阶段" (.getValue stage)) (not (string/blank? (.getText search))))]
                          (.setVisible back filtered) (.setManaged back filtered))
                        (when @snapshot (@render-ref))))))
    (.addListener (.selectedProperty tab) (reify ChangeListener (changed [_ _ _ selected] (when selected (@refresh-ref)))))
    (.setOnFinished timer (reify EventHandler (handle [_ _] (when-not @closed (@refresh-ref) (.playFromStart timer)))))
    (.play timer)
    {:root pane :refresh! @refresh-ref :reset! reset-view! :snapshot snapshot :search search :section section
     :show-section! (fn show-section [target] (.setValue filter "全部工作") (.setValue section target) (when @snapshot (@render-ref)))
     :close! (fn close-overview [] (reset! closed true) (.stop timer) (when-let [task @pending] (future-cancel task)) (locking read-lock nil))}))
