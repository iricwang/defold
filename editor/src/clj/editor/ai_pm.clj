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

(ns editor.ai-pm
  "AI-PM planning and human-approved, idempotent work Canvas allocation."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.ai-graph :as graph]
            [editor.ai-project-work :as project-work]
            [editor.ai-roles :as roles]
            [editor.craft-project :as craft-project]
            [util.coll :as coll]
            [util.digest :as digest])
  (:import [java.nio.file Files StandardCopyOption]))

(set! *warn-on-reflection* true)

(def task-fields [:title :stage :staff-id :directory :template :prompt :deliverables :depends-on :delivery-type :required-checks])
(def template-ids #{"concept" "gui" "art-characters" "art-levels" "balancing"})
(def statuses {"review" "待人工确认" "allocated" "已分配" "rejected" "已驳回" "superseded" "已被新规划替代"})

(defn- validate-dependencies! [tasks]
  (loop [pending tasks completed #{}]
    (when (coll/not-empty pending)
      (let [ready (filterv #(coll/every? completed (:depends-on %)) pending)
            done (coll/into-> ready completed (map :id))]
        (when-not (coll/not-empty ready) (throw (ex-info "任务依赖存在循环或引用了不存在的任务" {})))
        (recur (filterv #(not (contains? done (:id %))) pending) done)))))

(defn instructions [staff]
  (str "[AI-PM 项目规划] 只规划，不执行任何被分配的工作，不写文件、不创建子聊天。"
       "依据项目目标与职员职责拆分阶段和任务，为每项任务选择已启用的具体职员。人力不足时说明缺口，不能虚构职员。"
       "每项任务由人工确认后，编辑器才生成工作 Canvas。考虑任务顺序、职员负荷、交付物和验收标准。"
       "delivery-type 为 document（文档）、assets（实际资源包）或 engineering（工程集成，须构建、运行和视觉检查）。前置成果必须验收通过才执行后续工作。"
       "前置任务使用 depends-on 引用任务 id，不能循环；不同职员的工作分成独立任务。"
       "只返回一个标记为 ai-pm-plan 的 JSON 代码块，格式如下：\n"
       "```ai-pm-plan\n{\"version\":1,\"stages\":[{\"id\":\"design\",\"title\":\"系统设计\",\"tasks\":["
       "{\"id\":\"interaction\",\"title\":\"设计系统交互\",\"staff-id\":\"现有职员ID\","
       "\"directory\":\"/ai-workspace/library/06-interface\",\"template\":\"concept\","
       "\"prompt\":\"完整工作描述\",\"deliverables\":\"具体交付与验收要求\",\"depends-on\":[]}]}]}\n```\n"
       "directory 只能为 /ai-workspace/library 下的工作分类及子目录；"
       "分类：01-concept、02-world、03-characters、04-levels、05-systems、06-interface、07-motion、08-audio、09-balancing、10-delivery。"
       "template 只能为 concept（文本）、gui（GUI）、art-characters（角色与怪物）、art-levels（场景与关卡）、balancing（数值与配置）。\n"
       "可用职员（包括自定义职能）：\n"
       (json/write-str (mapv #(select-keys % [:id :title :role-type :responsibilities :deliverables]) (filterv :enabled staff))) "\n\n"))

(defn validate-task! [task staff]
  (doseq [key [:title :stage :directory :prompt :deliverables]]
    (when-not (and (string? (get task key)) (<= 1 (count (string/trim (get task key))) 12000))
      (throw (ex-info (str "TaskCard 缺少有效字段：" (name key)) {}))))
  (when-not (and (<= (count (:title task)) 80)
                 (not (re-find #"[/\\:<>|?*\p{Cntrl}]|[. ]$" (:title task))))
    (throw (ex-info "任务名称不能包含文件名特殊字符，且不能超过 80 字" {})))
  (when-not (and (re-matches #"/ai-workspace/library/(?:01-concept|02-world|03-characters|04-levels|05-systems|06-interface|07-motion|08-audio|09-balancing|10-delivery)(?:/[^./\\:<>|?*\p{Cntrl}][^/\\:<>|?*\p{Cntrl}]*)*" (:directory task))
                 (coll/every? #(not (re-find #"[. ]$" %)) (string/split (:directory task) #"/")))
    (throw (ex-info "工作目录必须位于创作资料分类中，不能包含路径跳转或特殊字符" {})))
  (when (and (:delivery-type task) (not (contains? #{"document" "assets" "engineering"} (:delivery-type task))))
    (throw (ex-info "交付类型必须为文档、资产包或工程集成" {})))
  (when (and (:required-checks task) (not (and (vector? (:required-checks task)) (coll/every? #{"build" "runtime" "visual"} (:required-checks task)))))
    (throw (ex-info "验收检查类型无效" {})))
  (when-not (contains? template-ids (:template task)) (throw (ex-info "请选择有效的工作 Canvas 类型" {})))
  (when-not (and (vector? (:depends-on task)) (coll/every? string? (:depends-on task))
                 (= (count (:depends-on task)) (count (set (:depends-on task)))))
    (throw (ex-info "前置任务必须是无重复的任务 ID 列表" {})))
  (when-let [error (graph/node-error {:kind :person :title "任务负责人" :staff-id (:staff-id task)} staff)]
    (throw (ex-info error {})))
  task)

(defn parse-plan [text staff]
  (let [blocks (vec (re-seq #"(?s)```ai-pm-plan\s*\n(.*?)```" (or text "")))
        _ (when-not (= 1 (count blocks)) (throw (ex-info "AI-PM 必须返回一个完整的 ai-pm-plan 规划块，请调整目标后重试" {})))
        plan (json/read-str (second (first blocks)) :key-fn keyword)
        stages (:stages plan)]
    (when-not (and (= 1 (:version plan)) (vector? stages) (<= 1 (count stages) 20)
                   (coll/every? #(and (string? (:id %)) (not (string/blank? (:title %))) (vector? (:tasks %))) stages)
                   (= (count stages) (count (set (mapv :id stages)))))
      (throw (ex-info "阶段格式无效：需要 1–20 个不重复的阶段" {})))
    (let [tasks (coll/into-> stages [] (mapcat (fn stage-tasks [stage] (mapv #(assoc % :stage (:title stage) :stage-id (:id stage)) (:tasks stage)))))
          ids (set (mapv :id tasks))]
      (when-not (and (<= 1 (count tasks) 100) (= (count tasks) (count ids))
                     (coll/every? #(and (string? %) (re-matches #"[A-Za-z0-9_-]{1,64}" %)) ids))
        (throw (ex-info "规划需要 1–100 项任务，任务 ID 必须唯一且使用英文、数字或下划线" {})))
      (doseq [task tasks]
        (validate-task! task staff)
        (when-not (coll/every? ids (:depends-on task)) (throw (ex-info "前置任务不存在" {:task (:id task)}))))
      (validate-dependencies! tasks)
      tasks)))

(defn accept-plan [model pm-id turn-id text staff]
  (if (= turn-id (:planned-turn (graph/node model pm-id)))
    model
    (let [tasks (parse-plan text staff)
          key (subs (digest/string->sha256-hex (str pm-id "/" turn-id)) 0 20)
          ids (into {} (mapv #(vector (:id %) (str "task_" key "_" (:id %))) tasks))
          top (+ 540.0 (reduce max -540.0 (mapv #(double (or (:y %) 0.0)) (filterv #(= :task (:kind %)) (:nodes model)))))
          cards (mapv (fn task-card [index task]
                        (merge (select-keys task task-fields)
                               {:id (get ids (:id task)) :kind :task :source-pm pm-id :source-turn turn-id
                                :plan-task-id (:id task) :stage-id (:stage-id task) :status "review"
                                :depends-on (mapv ids (:depends-on task)) :dependency-wires true
                                :x 350.0 :y (+ top (* 540.0 index))})) (range (count tasks)) tasks)
          model (update model :nodes #(into (mapv (fn supersede [node]
                                                    (cond-> node
                                                      (and (= pm-id (:source-pm node)) (= "review" (:status node))) (assoc :status "superseded")
                                                      (= pm-id (:id node)) (assoc :planned-turn turn-id))) %) cards))
          model (update model :edges into (mapv #(graph/stamp-edge model staff {:from pm-id :to (:id %)}) cards))
          prerequisites (coll/into-> cards []
                          (mapcat (fn prerequisite-edges [card]
                                    (mapv #(graph/stamp-edge model staff {:from % :to (:id card)})
                                          (:depends-on card)))))]
      (update model :edges into prerequisites))))

(defn accept-snapshot [state id snapshot]
  (let [turn-id (get-in state [:sessions id :execution :turn-id])
        turn (coll/first-where #(= turn-id (:id %)) (get-in snapshot [:thread :turns]))]
    (if-not (and (= :pm (:kind (graph/node (:canvas state) id))) (= "completed" (:status turn)))
      state
      (try
        (update state :canvas accept-plan id turn-id
                (coll/join-to-string "\n" (coll/into-> (:items turn) []
                                            (filter #(and (= "agentMessage" (:type %)) (not (contains? #{"analysis" "commentary"} (:phase %))))) (map :text))) (:staff state))
        (catch Exception e
          (-> state (assoc-in [:sessions id :execution :status] "failed")
              (assoc-in [:sessions id :execution :error] (str "规划未分配：" (.getMessage e)))
              (assoc-in [:sessions id :queue :status] "cancelled")))))))

(defn edit-task [model id fields staff]
  (let [task (graph/node model id)]
    (when-not (and (= :task (:kind task)) (= "review" (:status task)))
      (throw (ex-info "只能修改待确认的 TaskCard，已分配的工作请在工作 Canvas 修改" {})))
    (let [updated (validate-task! (merge task (select-keys fields task-fields)) staff)]
      (when (or (contains? (set (:depends-on updated)) id)
                (coll/any? #(not= (:source-turn task) (:source-turn (graph/node model %))) (:depends-on updated)))
        (throw (ex-info "前置任务必须来自同一规划且不能是任务本身" {})))
      (let [next (update model :nodes #(mapv (fn edit-card [card] (if (= id (:id card)) updated card)) %))]
        (validate-dependencies! (filterv #(and (= (:source-pm task) (:source-pm %)) (= (:source-turn task) (:source-turn %))) (:nodes next)))
        (update next :edges #(mapv (fn refresh-task-edge [edge]
                                     (if (= id (:to edge)) (graph/stamp-edge next staff edge) edge)) %))))))

(defn allocate! [root source-path model id staff]
  (let [task (graph/node model id)]
    (when-not (and (= :task (:kind task)) (contains? #{"review" "allocated"} (:status task)))
      (throw (ex-info "只有待确认任务可以分配" {})))
    (validate-task! task staff)
    (when-let [error (graph/task-error model staff id)] (throw (ex-info error {})))
    (let [dependency-ids (graph/prerequisite-ids model id)
          dependencies (mapv #(graph/node model %) dependency-ids)]
      (when (coll/any? nil? dependencies) (throw (ex-info "前置任务不存在" {})))
      (let [task (assoc task :delivery-type (or (:delivery-type task) (if (contains? #{"concept" "balancing"} (:template task)) "document" "assets")))
            revision (digest/string->sha256-hex (json/write-str (mapv #(get (assoc task :depends-on dependency-ids) %) task-fields)))
            suffix (subs (digest/string->sha256-hex (str source-path "/" id)) 0 12)
            path (str (:directory task) "/" (:title task) "-" suffix ".aicanvas")
            file (craft-project/resource-file root path)
            origin {:canvas source-path :task id :revision revision}
            person (roles/assigned-staff task staff)
            p (roles/assign-staff {:id (str "person_" suffix) :kind :person :title "负责人"} person)
            a {:id (str "action_" suffix) :kind :action :title (:title task)
               :prompt (str (:prompt task) "\n\n交付与验收要求：\n" (:deliverables task)
                            "\n\n阶段：" (:stage task)
                            (when (coll/not-empty dependencies)
                              (str "\n前置工作（执行前检查交付物，未完成则报告阻塞，不自行假设已完成）：\n"
                                   (coll/join-to-string "\n" (mapv #(str (:title %) " · " (:allocated-path %)) dependencies)))))
               :pm-origin origin :required-task-ids dependency-ids :delivery-type (:delivery-type task)}
            child {:nodes [p a] :edges []}
            doc {:format "codexcamp.ai-canvas" :schemaVersion 1 :id (str "canvas_pm_" suffix)
                 :title (:title task) :template (:template task) :revision 1 :pm-origin origin
                 :nodes (mapv #(update % :kind name) [p a])
                 :edges [(graph/stamp-edge child staff {:from (:id p) :to (:id a)})]}]
        (io/make-parents file)
        ;; Publish a complete file, never overwrite a pre-existing work Canvas.
        ;; The stable origin recovers a crash before saving the PM document
        ;; without replacing subsequent human edits.
        (when-not (.exists file)
          (let [temp (Files/createTempFile (.toPath (.getParentFile file)) "pm-allocation-" ".tmp" (make-array java.nio.file.attribute.FileAttribute 0))]
            (try
              (spit (.toFile temp) (json/write-str doc))
              (try (Files/move temp (.toPath file) (make-array StandardCopyOption 0))
                   (catch java.nio.file.FileAlreadyExistsException _ nil))
              (finally (Files/deleteIfExists temp)))))
        (let [current (json/read-str (slurp file) :key-fn keyword)]
          (when-not (= origin (:pm-origin current))
            (throw (ex-info "目标工作 Canvas 已存在或确认内容已变化，本次没有覆盖；请恢复原计划或创建新的规划" {:path path}))))
        (project-work/register-task! root {:id id :spec-revision revision :staff-id (:id person)
                                           :canvas-id (:id doc) :canvas-path path :action-ids [(:id a)]
                                           :source-path source-path :dependencies dependency-ids :title (:title task)
                                           :deliverables (:deliverables task) :delivery-type (:delivery-type task)
                                           :required-checks (if (= "engineering" (:delivery-type task)) ["build" "runtime" "visual"] (:required-checks task))})
        (update model :nodes #(mapv (fn allocated-task [node]
                                      (if (= id (:id node))
                                        (assoc node :status "allocated" :allocated-path path :allocated-revision revision
                                               :allocated-dependencies dependency-ids :delivery-type (:delivery-type task)
                                               :allocated-canvas-id (:id doc) :allocated-action-ids [(:id a)]
                                               :staff-name (:title person)) node)) %))))))

(defn register-allocated! [root source-path model]
  (doseq [card (filterv #(and (= :task (:kind %)) (= "allocated" (:status %)) (:allocated-revision %) (:allocated-path %)) (:nodes model))]
    (let [file (craft-project/resource-file root (:allocated-path card))]
      (when (and (.isFile file) (<= (.length file) (* 16 1024 1024)))
        (let [child (json/read-str (slurp file) :key-fn keyword)
              origin (:pm-origin child)
              existing (coll/first-where #(= (:id card) (:id %)) (:tasks (project-work/read-work root)))]
          (when (and (= "codexcamp.ai-canvas" (:format child)) (:id child)
                     (= [source-path (:id card) (:allocated-revision card)] [(:canvas origin) (:task origin) (:revision origin)])
                     (or (nil? existing) (= (:allocated-revision card) (:spec-revision existing))))
            (project-work/register-task! root
                                         (merge {:id (:id card) :title (:title card) :staff-id (:staff-id card)
                                                 :spec-revision (:allocated-revision card) :canvas-id (:id child) :canvas-path (:allocated-path card)
                                                 :source-path source-path :action-ids (coll/into-> (:nodes child) [] (filter #(= "action" (:kind %))) (map :id))
                                                 :dependencies (or (:allocated-dependencies card) (graph/prerequisite-ids model (:id card)))
                                                 :delivery-type (or (:delivery-type card) (if (contains? #{"concept" "balancing"} (:template card)) "document" "assets"))
                                                 :required-checks (:required-checks card) :deliverables (:deliverables card)} existing))))))))

(defn register-origin! [root origin]
  (when-let [path (:canvas origin)]
    (let [file (craft-project/resource-file root path)]
      (when (and (.isFile file) (<= (.length file) (* 16 1024 1024)))
        (let [doc (json/read-str (slurp file) :key-fn keyword)
              model (update doc :nodes #(mapv (fn decode-card [card] (update card :kind keyword)) %))
              card (graph/node model (:task origin))]
          (when (and (= "codexcamp.ai-canvas" (:format doc)) (= (:revision origin) (:allocated-revision card)))
            (register-allocated! root path model)))))))
