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

(ns editor.ai-delivery-view
  (:require [editor.ai-delivery :as delivery]
            [editor.ai-tasks :as tasks]
            [editor.file-reveal :as file-reveal]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [javafx.scene.control Button CheckBox ComboBox Label TextArea]
           [javafx.scene.layout VBox]
           [javafx.util StringConverter]))

(set! *warn-on-reflection* true)

(defn- text! [^VBox parent text]
  (.add (.getChildren parent) (doto (Label. (str text)) (.setWrapText true) (.setMaxWidth Double/MAX_VALUE))))

(def ^:private detail-button-style
  "-fx-background-color: #303030; -fx-border-color: #181818; -fx-border-width: 1; -fx-background-radius: 3; -fx-border-radius: 3; -fx-text-fill: #c8c8c8; -fx-min-width: 0; -fx-padding: 5 12;")

(defn- style-detail-buttons! [^javafx.scene.Parent root]
  (letfn [(walk [^javafx.scene.Node node]
            (cond
              (instance? Button node)
              (let [classes (set (.getStyleClass ^Button node))]
                (when-not (contains? classes "ai-card-link")
                  (.setStyle ^Button node detail-button-style)))
              (instance? javafx.scene.Parent node)
              (doseq [child (.getChildrenUnmodifiable ^javafx.scene.Parent node)] (walk child))))]
    (walk root)))

(defn- command! [^VBox parent title id f disabled]
  (.add (.getChildren parent) (doto (Button. title) (.setId id) (.setMaxWidth Double/MAX_VALUE)
                                    (.setDisable (boolean disabled))
                                    (.setOnAction (ui/event-handler _ (f))))))

(defn details [state card work! report!]
  (let [s @state data (:project-work s) id (:id card) root (VBox. 10.0)
        locked (or (and (not= :assets (:kind card)) (tasks/locked? s)) (contains? (:producing s) id))
        draft (get-in s [:delivery-drafts id])
        selected (atom (set (or (:artifact-ids draft) (mapv :artifact-id (:inputs card)))))
        edit! (fn edit-delivery [key value] (swap! state assoc-in [:delivery-drafts id key] value))]
    (.add (.getStyleClass root) "ai-detail-section")
    (.setMinWidth root 0.0)
    (case (:kind card)
      :action
      (do
        (text! root "固定成果输入")
        (text! root "只读取所选已批准版本；任务前置成果由验收记录自动锁定。")
        (doseq [record (filterv #(and (not= "integration-plan" (:kind %)) (delivery/approved? data (:id %))) (:artifacts data))]
          (let [check (doto (CheckBox. (str (:title record) " · " (subs (:revision record) 0 8)))
                        (.setSelected (contains? @selected (:id record))) (.setDisable (boolean locked)))]
            (.setOnAction check (ui/event-handler _
                                  (swap! selected (if (.isSelected check) conj disj) (:id record))
                                  (edit! :artifact-ids (vec @selected))))
            (.add (.getChildren root) check)))
        (command! root "保存输入版本" "delivery-bind-inputs" #(work! :bind-inputs id {:artifact-ids (vec @selected)}) locked)
        (when-let [job (coll/first-where #(= (get-in s [:sessions id :project-job-id]) (:id %)) (:jobs data))]
          (text! root (if (:inputs-fixed job) "本次执行已锁定输入，修改只应用于下一次执行。" (:block-reason job)))))
      :assets
      (do
        (when (get-in card [:production :publication-report])
          (command! root "查看发布检查报告" "delivery-publication-report" #(work! :open-import-report id nil) false))
        (text! root "资产版本审批")
        (if-let [record (delivery/artifact data (:artifact-id card))]
          (do
            (text! root (str (if (delivery/approved? data (:id record)) "已批准" "待人工批准") " · " (subs (:revision record) 0 12)))
            (doseq [file (:files record)]
              (.add (.getChildren root) (file-reveal/install! (doto (Label. (:name file)) (.setWrapText true))
                                                              (:project-root s) (constantly (:path file)) report!))))
          (text! root "尚未建立固定版本。批准会保存当前文本与所有文件的副本。"))
        (command! root "批准当前资产版本" "delivery-approve-assets" #(work! :approve-assets id nil) locked))
      :task
      (when (= "allocated" (:status card))
        (text! root "交付与人工验收")
        (text! root (str "交付类型 · " (get {"document" "文档" "assets" "资产包" "engineering" "工程集成"}
                                        (:delivery-type (delivery/task data id)) "文档")))
        (when-let [decision (last (filterv #(and (= "task-acceptance" (:kind %)) (= id (:subject-id %))) (:approvals data)))]
          (text! root (str (if (delivery/accepted data id) "验收通过" (if (= "rejected" (:decision decision)) "已退回" "已有历史验收，当前版本待验收")) " · " (:notes decision))))
        (doseq [record (filterv #(and (= id (:task-id %)) (delivery/approved? data (:id %))) (:artifacts data))]
          (let [check (doto (CheckBox. (str (:title record) " · " (subs (:revision record) 0 8))) (.setSelected (contains? @selected (:id record))))]
            (.setOnAction check (ui/event-handler _ (swap! selected (if (.isSelected check) conj disj) (:id record)) (edit! :artifact-ids (vec @selected))))
            (.add (.getChildren root) check)))
        (let [receipts (filterv #(= id (:task-id %)) (:deliveries data))
              picker (doto (ComboBox.) (.setId "delivery-receipt") (.setMaxWidth Double/MAX_VALUE))
              notes (doto (TextArea. (or (:notes draft) "")) (.setId "delivery-notes") (.setWrapText true) (.setPrefRowCount 3))
              selected-receipt (or (delivery/delivery data (:delivery-id draft)) (last receipts))]
          (.addAll (.getItems picker) ^java.util.Collection (mapv :id receipts))
          (.setConverter picker (proxy [StringConverter] []
                                  (toString [id]
                                    (when id
                                      (let [receipt (delivery/delivery data id) build (or (:build-id receipt) "待重新构建") date (or (:created-at receipt) "")]
                                        (str "构建 " (subs build 0 (min 12 (count build))) " · " (subs date 0 (min 10 (count date)))))))
                                  (fromString [value] value)))
          (.setValue picker (:id selected-receipt))
          (.setOnAction picker (ui/event-handler _ (edit! :delivery-id (.getValue picker)) (work! :refresh-delivery id nil)))
          (when (coll/not-empty receipts)
            (text! root "工程构建收据")
            (.add (.getChildren root) picker)
            (text! root "检查结果与证据绑定同一构建版本")
            (doseq [name ["build" "runtime" "visual"]]
              (let [check (last (filterv #(= name (:name %)) (:checks selected-receipt)))]
                (text! root (str (get {"build" "引擎编译" "runtime" "运行验证" "visual" "视觉检查"} name) " · "
                                 (get {"passed" "已通过" "failed" "未通过"} (:status check) "待检查")))
                (when-let [notes (:notes check)] (text! root notes))
                (doseq [file (:files (delivery/artifact data (:evidence-id check)))]
                  (.add (.getChildren root)
                        (file-reveal/install! (doto (Button. (:name file))
                                                (.setOnAction (ui/event-handler _ (work! :open-approved-file id {:path (:path file)}))))
                                              (:project-root s) (constantly (:path file)) report!))))))
          (text! root "验收 / 检查说明（必填）")
          (.add (.getChildren root) notes)
          (.addListener (.textProperty notes) (reify javafx.beans.value.ChangeListener (changed [_ _ _ value] (edit! :notes value))))
          (when selected-receipt
            (doseq [[name title] [["runtime" "运行验证"] ["visual" "视觉检查"]] status ["passed" "failed"]]
              (command! root (str title (if (= "passed" status) "通过，附证据…" "失败，附证据…"))
                        (str "delivery-check-" name "-" status)
                        #(work! :record-delivery-check id {:delivery-id (.getValue picker) :check name :status status :notes (.getText notes)}) locked)))
          (command! root "验收通过并回流项目" "delivery-accept-task"
                    #(work! :accept-delivery id {:artifact-ids (vec @selected) :delivery-id (.getValue picker) :notes (.getText notes)}) locked)
          (command! root "退回修改" "delivery-reject-task" #(work! :reject-delivery id {:notes (.getText notes)}) locked)))
      nil)
    (style-detail-buttons! root)
    root))
