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

(ns editor.ai-work-views
  "Project-owned staff and Task tabs; these views never own execution state."
  (:require [editor.ai-delivery :as delivery]
            [editor.ai-document :as document]
            [editor.ai-overview :as overview]
            [editor.ai-project-work :as work]
            [editor.ai-review :as review]
            [editor.ai-studio :as studio]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [javafx.animation KeyFrame Timeline]
           [javafx.beans.value ChangeListener]
           [javafx.scene.control Button Label ScrollPane SplitPane Tab]
           [javafx.scene.layout BorderPane HBox VBox]
           [javafx.util Duration]))

(set! *warn-on-reflection* true)

(defn make-view [root ^Tab tab context navigate!]
  (let [pane (doto (BorderPane.) (.setFocusTraversable true))
        header (VBox. 8.0) notice (doto (Label.) (.setWrapText true))
        left (VBox. 8.0) right (VBox. 8.0)
        left-scroll (doto (ScrollPane. left) (.setFitToWidth true))
        right-scroll (doto (ScrollPane. right) (.setFitToWidth true))
        cache (atom {}) stamp (atom nil) disposed (atom false)]
    (letfn [(^Label label [text] (doto (Label. (str text)) (.setWrapText true)))
            (^Button button [title f]
              (doto (studio/decorate-navigation! (Button. title))
                (.setOnAction (ui/event-handler _
                                (try (f) (reset! stamp nil) (refresh!)
                                     (catch Exception e (.setText notice (.getMessage e))))))))
            (section! [^VBox parent title nodes]
              (let [box (VBox. 8.0)]
                (.add (.getStyleClass box) "ai-overview-section")
                (.setStyle box "-fx-padding: 12;")
                (.add (.getChildren box) (doto ^Label (label title) (-> .getStyleClass (.add "ai-overview-section-title"))))
                (.addAll (.getChildren box) ^java.util.Collection nodes)
                (.add (.getChildren parent) box)))
            (refresh! []
              (when-not @disposed
                (try
                  (let [data (work/read-work root)]
                    (when (not= @stamp data)
                      (let [positions [(.getVvalue left-scroll) (.getVvalue right-scroll)]
                            snapshot (overview/load-snapshot root cache)
                            task (delivery/task data (:task-id context))
                            staff-id (or (:staff-id context) (:staff-id task))
                            staff (coll/first-where #(= staff-id (:id %)) (:staff snapshot))
                            jobs (filterv #(if task (= (:id task) (:task-id %)) (= staff-id (:staff-id %))) (:jobs data))
                            task-ids (set (mapv :task-id jobs))
                            tasks (if task [task] (filterv #(or (= staff-id (:staff-id %)) (contains? task-ids (:id %))) (:tasks data)))
                            artifacts (filterv #(contains? (set (mapv :id tasks)) (:task-id %)) (:artifacts data))]
                        (reset! stamp data)
                        (.setText tab (str (if task "任务 · " "职员 · ") (or (:title task) (:title staff) "未找到")))
                        ;; Keep focus attached while replacing live queue rows.
                        (loop [node (some-> pane .getScene .getFocusOwner)]
                          (when node
                            (if (or (identical? header node) (identical? left node) (identical? right node))
                              (.requestFocus pane)
                              (recur (.getParent node)))))
                        (.clear (.getChildren header)) (.clear (.getChildren left)) (.clear (.getChildren right))
                        (.addAll (.getChildren header) ^"[Ljava.lang.Object;" (to-array [(HBox. 8.0 (into-array javafx.scene.Node
                                                                                                                [(button "← 返回总览" #(navigate! {:overview true :preserve true}))
                                                                                                                 (button "刷新" #(reset! stamp nil))
                                                                                                                 (label (or (:title task) (:title staff) "人员与任务"))])) notice]))
                        (section! left "负责人"
                                  [(label (str (:title staff "未指派") " · " (get-in staff [:role-definition :title] (:role-type staff))))
                                   (label (str "模型：" (:model staff) " · " (:effort staff)))
                                   (label (:responsibilities staff))
                                   (label (str "执行 / 待核对 " (count (filterv #(contains? #{"running" "unknown"} (:status %)) jobs))
                                               "   可执行 " (count (filterv #(= "queued" (:status %)) jobs))
                                               "   阻塞 " (count (filterv #(= "blocked" (:status %)) jobs))
                                               "   待返修 " (count (filterv #(and (= staff-id (:staff-id %)) (= "review" (:status %))) (:rework data)))))])
                        (when task
                          (let [doc (when (:canvas-path task) (document/read-document root (:canvas-path task)))
                                actions (filterv #(= "action" (:kind %)) (:nodes doc))]
                            (section! left "任务范围与验收"
                                      (into [(label (str "状态：" (work/task-status data (:id task))))
                                             (label (str "交付要求：" (:deliverables task)))
                                             (label (str "验收检查：" (coll/join-to-string "、" (:required-checks task))))
                                             (label (if (delivery/accepted data (:id task)) "工程验收：已通过" "工程验收：尚未通过"))
                                             (label (str "前置任务：" (coll/join-to-string "、" (mapv #(or (:title (delivery/task data %)) %) (:dependencies task)))))
                                             (button "打开工作 Canvas" #(navigate! {:path (:canvas-path task) :card-id (first (:action-ids task))}))
                                             (button "查看负责人工作页" #(navigate! {:staff-id staff-id}))]
                                            (mapv #(label (str (:title %) "\n" (:prompt %))) actions)))))
                        (section! left "项目工作队列"
                                  (if-not (coll/not-empty jobs) [(label "暂无执行记录")]
                                          (mapv (fn job-row [job]
                                                  (let [row (VBox. 6.0)]
                                                    (.addAll (.getChildren row) ^"[Ljava.lang.Object;" (to-array [(label (str (:title job) " · " (get {"queued" "待执行" "running" "执行中" "unknown" "待核对" "blocked" "等待前置成果" "paused" "已暂停" "completed" "执行完成" "cancelled" "已取消" "failed" "失败" "interrupted" "已中断"} (:status job) (:status job))))
                                                                                                                  (label (or (:block-reason job) (:error job) ""))
                                                                                                                  (label (str "本次输入版本：" (coll/join-to-string "、" (:input-snapshot job))))
                                                                                                                  (HBox. 6.0 (into-array javafx.scene.Node
                                                                                                                                         (into [(button "任务 / 画布" #(navigate! (if (:task-id job) {:task-id (:task-id job)} {:path (:canvas-path job) :card-id (:action-id job)})))
                                                                                                                                                (button "对话 / 执行控制" #(navigate! {:path (:canvas-path job) :card-id (:action-id job) :conversation true}))]
                                                                                                                                               (when (contains? #{"queued" "blocked" "paused"} (:status job))
                                                                                                                                                 [(button "上移" #(review/move-job! root (:id job) -1))
                                                                                                                                                  (button "下移" #(review/move-job! root (:id job) 1))]))))]))
                                                    row)) jobs)))
                        (when-not task
                          (section! left "负责的任务"
                                    (mapv #(button (str (:title %) " · " (work/task-status data (:id %)))
                                                   (fn open-task [] (navigate! {:task-id (:id %)}))) tasks)))
                        (section! right "待确认返修"
                                  (mapv (fn rework-row [item]
                                          (let [note (coll/first-where #(= (:note-id item) (:id %)) (:review-notes data))]
                                            (doto (VBox. 6.0)
                                              (-> .getChildren (.addAll ^"[Ljava.lang.Object;" (to-array [(label (:title item)) (label (:text note))
                                                                                                          (button "确认分配并加入负责人队列"
                                                                                                                  (fn confirm-rework []
                                                                                                                    (let [allocated (review/confirm-rework! root (:id item) (:staff snapshot))]
                                                                                                                      (navigate! {:path (:canvas-path allocated) :card-id (:person-id allocated)
                                                                                                                                  :queue-rework (:id allocated)}))))]))))))
                                        (filterv #(and (= "review" (:status %)) (= staff-id (:staff-id %))
                                                       (or (nil? task) (= (:id task) (:task-id %)))) (:rework data))))
                        (section! right "成果版本与交付"
                                  (mapv (fn artifact-row [artifact]
                                          (button (str (:title artifact) " · " (subs (:revision artifact) 0 8)
                                                       (if (delivery/approved? data (:id artifact)) " · 已批准" " · 待审"))
                                                  #(navigate! {:path (:source-path artifact) :card-id (:source-card-id artifact)
                                                               :review-artifact (:id artifact)}))) artifacts))
                        (.setVvalue left-scroll (first positions)) (.setVvalue right-scroll (second positions)))))
                  (catch Exception e (.setText notice (str "保留当前内容：" (.getMessage e)))))))]
      (.add (.getStyleClass pane) "ai-overview")
      (.add (.getStyleClass pane) "ai-work-page")
      (.setStyle pane "-fx-padding: 12;")
      (.setTop pane header)
      (.setCenter pane (SplitPane. (into-array javafx.scene.Node [left-scroll right-scroll])))
      (ui/apply-css! pane)
      (let [timer (Timeline. ^"[Ljavafx.animation.KeyFrame;" (into-array KeyFrame
                                                                         [(KeyFrame. (Duration/seconds 3.0)
                                                                                     ^javafx.event.EventHandler (ui/event-handler _ (when (.isSelected tab) (refresh!)))
                                                                                     ^"[Ljavafx.animation.KeyValue;" (make-array javafx.animation.KeyValue 0))]))]
        (.setCycleCount timer -1) (.play timer)
        (.addListener (.selectedProperty tab) (reify ChangeListener (changed [_ _ _ selected] (when selected (refresh!)))))
        (refresh!)
        {:root pane :close! (fn close-view [] (reset! disposed true) (.stop timer))
         :update-context! (fn update-context [_] (reset! stamp nil) (refresh!))}))))
