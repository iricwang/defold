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

(ns editor.ai-pm-view
  (:require [clojure.string :as string]
            [editor.ai-delivery-view :as delivery-view]
            [editor.ai-graph :as graph]
            [editor.ai-pm :as pm]
            [editor.ai-project-work :as project-work]
            [editor.ai-roles :as roles]
            [editor.ai-tasks :as tasks]
            [editor.file-reveal :as file-reveal]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [javafx.beans.value ChangeListener]
           [javafx.scene.control Button ComboBox Label ProgressBar TextArea TextField TextInputControl]
           [javafx.scene.layout VBox]))

(set! *warn-on-reflection* true)

(defn- caption [text]
  (doto (Label. (str text)) (.setWrapText true) (.setMaxWidth Double/MAX_VALUE)))

(def ^:private detail-button-style
  "-fx-background-color: #303030; -fx-border-color: #181818; -fx-border-width: 1; -fx-background-radius: 3; -fx-border-radius: 3; -fx-text-fill: #c8c8c8; -fx-min-width: 0; -fx-padding: 5 12;")

(defn- style-detail-buttons! [^javafx.scene.Parent root]
  (letfn [(walk [^javafx.scene.Node node]
            (cond
              (instance? javafx.scene.control.Button node)
              (let [classes (set (.getStyleClass ^javafx.scene.control.Button node))]
                (when-not (or (contains? classes "ai-primary") (contains? classes "ai-card-link"))
                  (.setStyle ^javafx.scene.control.Button node detail-button-style)))
              (instance? javafx.scene.Parent node)
              (doseq [child (.getChildrenUnmodifiable ^javafx.scene.Parent node)] (walk child))))]
    (walk root)))

(defn- command
  ^Button [text id f]
  (doto (Button. text) (.setId id) (.setMaxWidth Double/MAX_VALUE)
        (.setMinWidth 0.0)
        (-> .getStyleClass (.add "ai-command"))
        (.setOnAction (ui/event-handler _ (f)))))

(defn summary [state card select! work!]
  (let [s @state id (:id card) root (VBox. 10.0) children (.getChildren root)
        pm (= :pm (:kind card))]
    (if pm
      (let [person (coll/first-where #(= (:staff-id card) (:id %)) (:staff s))
            cards (filterv #(and (= id (:source-pm %)) (not= "superseded" (:status %))) (get-in s [:canvas :nodes]))
            completed (count (filterv #(= "allocated" (:status %)) cards))]
        (.add children (roles/make-portrait (or person {:id id :role-type "pm"})))
        (.add children (caption (str "统筹负责人 · " (or (:title person) "待选择职员"))))
        (.add children (caption "拆分阶段 → 人力安排 → 人工确认 → 工作 Canvas"))
        (.add children (caption (str "已分配 " completed " / " (count cards) " 项工作")))
        (.add children (caption (str "已验收 " (count (filterv #(= "已验收" (project-work/task-status (:project-work s) (:id %))) cards)) " / " (count cards) " 项工作")))
        (.add children (doto (ProgressBar. (if (pos? (count cards)) (/ (double completed) (count cards)) 0.0)) (.setMaxWidth Double/MAX_VALUE)))
        (doseq [stage (coll/into-> cards [] (map :stage) (distinct))]
          (let [stage-tasks (filterv #(= stage (:stage %)) cards)
                accepted (count (filterv #(= "已验收" (project-work/task-status (:project-work s) (:id %))) stage-tasks))]
            (.add children (caption (str "阶段 · " stage " · 验收 " accepted "/" (count stage-tasks)
                                         (when (= accepted (count stage-tasks)) " · 已完成"))))))
        (.add children (caption (get tasks/status-labels (tasks/status s id))))
        (when-let [error (get-in s [:sessions id :execution :error])] (.add children (caption error)))
        (when (string/blank? (:prompt card))
          (.add children (caption "未填写项目目标；可直接拖出 TaskCard 管理后续工作")))
        (.add children (doto (command "生成 / 继续阶段规划" (str "pm-run-" id) #(work! :run id nil))
                         (.setDisable (or (boolean (graph/node-error card (:staff s)))
                                          (string/blank? (:prompt card)))))))
      (let [person (coll/first-where #(= (:staff-id card) (:id %)) (:staff s))]
        (.add children (caption (str "阶段 · " (:stage card))))
        (.add children (caption (str "负责人 · " (:title person "待分配") " / " (:title (roles/role-type person)))))
        (.add children (caption (get pm/statuses (:status card) "待人工确认")))
        (when (= "allocated" (:status card))
          (.add children (caption (str "工作状态 · " (project-work/task-status (:project-work s) id)))))
        (.add children (caption (subs (:prompt card "") 0 (min 160 (count (:prompt card ""))))))
        (.add children (caption (str "交付 · " (:deliverables card))))
        (.add children (caption (str "前置工作 · " (count (:depends-on card)) " 项")))
        (.add children (command "审核人员与工作内容" (str "pm-review-" id) #(select! id)))
        (.add children (doto (command (if (= "allocated" (:status card)) "打开工作 Canvas ↗" "确认分配")
                                      (str "pm-allocate-" id)
                                      #(work! (if (= "allocated" (:status card)) :open-pm-task :confirm-pm-task) id nil))
                         (.setDisable (or (tasks/locked? s) (not (contains? #{"review" "allocated"} (:status card)))
                                          (and (= "review" (:status card)) (boolean (graph/task-error (:canvas s) (:staff s) id)))))))))
    (.setMinWidth root 0.0)
    root))

(defn details [state card staff! assign! report! work!]
  (let [id (:id card) pm (= :pm (:kind card)) root (VBox. 10.0)
        children (.getChildren root) s @state
        draft (merge card (get-in s [:pm-drafts id]))
        locked (or (tasks/locked? s) (and (not pm) (not= "review" (:status card))))
        edit! (fn edit-field [key value]
                (swap! state assoc-in [:pm-drafts id key] value))]
    (.setMinWidth root 0.0)
    (.add (.getStyleClass root) "ai-detail-section")
    (.add children (caption (if pm "AI-PM · 项目目标与人力统筹" "TaskCard · 人工审核分配")))
    (if pm
      (.add children (roles/make-assignment card staff! assign! report! "pm-staff-"))
      (let [picker-ref (atom nil)
            ^javafx.scene.control.MenuButton picker (roles/make-assignment draft staff!
                                                                           (fn select-task-staff [_ staff-id]
                                                                             (edit! :staff-id staff-id)
                                                                             (.setText ^javafx.scene.control.MenuButton @picker-ref
                                                                                       (:title (coll/first-where #(= staff-id (:id %)) (staff!)) "待选择职员"))) report! "pm-task-staff-")]
        (reset! picker-ref picker)
        (.setDisable picker (boolean locked))
        (.add children picker)))
    (doseq [[key title multiline] (if pm [[:title "规划名称" false] [:prompt "项目目标、阶段约束与交付范围" true]]
                                      [[:title "任务名称" false] [:stage "阶段" false] [:directory "工作目录" false]
                                       [:prompt "工作内容" true] [:deliverables "交付要求与验收标准" true]])]
      (let [^TextInputControl input (if multiline (doto (TextArea. (get draft key "")) (.setWrapText true) (.setPrefRowCount 4))
                                        (TextField. (get draft key "")))]
        (.setId input (str "pm-field-" (name key)))
        (.setMinWidth input 0.0)
        (.setMaxWidth input Double/MAX_VALUE)
        (.setDisable input (boolean locked))
        (.addListener (.textProperty input) (reify ChangeListener (changed [_ _ _ value] (edit! key value))))
        (.add children (caption title)) (.add children input)))
    (when-not pm
      (let [names {"concept" "文本方案" "gui" "GUI 方案" "art-characters" "美术 · 角色与怪物"
                   "art-levels" "美术 · 场景与关卡" "balancing" "数值与配置"}
            picker (doto (ComboBox.) (.setId "pm-task-template") (.setMaxWidth Double/MAX_VALUE))]
        (.addAll (.getItems picker) ^java.util.Collection (mapv names (sort pm/template-ids)))
        (.setValue picker (get names (:template draft))) (.setDisable picker (boolean locked))
        (.setOnAction picker (ui/event-handler _ (edit! :template (first (coll/first-where #(= (.getValue picker) (second %)) names)))))
        (.add children (caption "工作 Canvas 类型")) (.add children picker))
      (let [names {"document" "文档" "assets" "资产包" "engineering" "工程集成"}
            picker (doto (ComboBox.) (.setId "pm-delivery-type") (.setMaxWidth Double/MAX_VALUE))]
        (.addAll (.getItems picker) ^java.util.Collection (mapv names ["document" "assets" "engineering"]))
        (.setValue picker (get names (:delivery-type draft) (if (contains? #{"concept" "balancing"} (:template draft)) "文档" "资产包")))
        (.setDisable picker (boolean locked))
        (.setOnAction picker (ui/event-handler _ (edit! :delivery-type (first (coll/first-where #(= (.getValue picker) (second %)) names)))))
        (.add children (caption "交付类型")) (.add children picker))
      (.add children (caption "前置任务 · 从其他 TaskCard 拖线到本卡添加"))
      (let [dependencies (mapv #(graph/node (:canvas s) %) (graph/prerequisite-ids (:canvas s) id))]
        (if (coll/not-empty dependencies)
          (do
            (doseq [dependency dependencies]
              (.add children (caption (str "· " (:title dependency "已丢失") " · " (get pm/statuses (:status dependency) "已丢失")))))
            (.add children (caption "可以提前分配；执行前必须取得前置任务已验收的固定成果版本")))
          (.add children (caption "暂无前置任务"))))
      (when-let [path (:allocated-path card)]
        (.add children (file-reveal/install! (caption path) (:project-root s) (constantly path) report!))))
    (let [save (doto (command "保存修改" "pm-save"
                              (fn save-fields []
                                (work! (if pm :save-task :save-pm-task) id
                                       (merge (select-keys card (if pm [:title :prompt] pm/task-fields)) (get-in @state [:pm-drafts id])))))
                 (.setDisable (boolean locked)))]
      (if pm
        (do
          (.add children save)
          (.add children (caption "规划结果只生成待确认 TaskCard，不执行其中的工作。"))
          (.add children (command "生成 / 继续阶段规划" "pm-plan" #(work! :run id nil)))
          (.add children (command "结束规划队列（当前请求继续）" "pm-cancel" #(work! :cancel id nil)))
          (.add children (caption "项目人力 · 本规划工作量"))
          (doseq [person (filterv :enabled (:staff s))]
            (let [cards (filterv #(and (= id (:source-pm %)) (= (:id person) (:staff-id %))
                                       (contains? #{"review" "allocated"} (:status %))) (get-in s [:canvas :nodes]))]
              (.add children (caption (str (:title person) " · " (:title (roles/role-type person)) " · " (count cards) " 项工作"))))))
        (do
          (.add children save)
          (.add children (doto (command "驳回本项分配" "pm-reject" #(work! :reject-pm-task id nil))
                           (.setDisable (boolean locked))))
          (.add children (doto (command "确认分配并生成 Canvas" "pm-confirm" #(work! :confirm-pm-task id nil))
                           (.setDisable (or locked (boolean (graph/task-error (:canvas s) (:staff s) id))))))
          (when (:allocated-path card)
            (.add children (command "打开工作 Canvas ↗" "pm-open" #(work! :open-pm-task id nil)))))))
    (when-not pm (.add children (delivery-view/details state card work! report!)))
    (style-detail-buttons! root)
    root))
