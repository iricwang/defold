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

(ns editor.ai-graph
  "Typed Canvas connections and configuration-bound execution reachability."
  (:require [clojure.string :as string]
            [editor.ai-roles :as roles]
            [util.coll :as coll]
            [util.digest :as digest]))

(def successors {:pm [:task] :task [:task] :person [:action] :action [:result] :result [:assets] :assets [:gui :action] :gui []})

(defn input-edge? [model edge]
  (= [:assets :action] (mapv #(:kind (coll/first-where (fn match-node [node] (= % (:id node))) (:nodes model))) [(:from edge) (:to edge)])))

(defn node [model id]
  (coll/first-where #(= id (:id %)) (:nodes model)))

;; A TaskCard -> TaskCard wire is a prerequisite, not an execution input.
(defn dependency-edge? [model edge]
  (= [:task :task] (mapv #(:kind (node model %)) [(:from edge) (:to edge)])))

(defn prerequisite-ids [model id]
  (let [task (node model id)
        wires (coll/into-> (:edges model) [] (filter #(and (= id (:to %)) (dependency-edge? model %))) (map :from))]
    (vec (sort (into (if (:dependency-wires task) #{} (set (:depends-on task))) wires)))))

(defn migrate-prerequisites [model]
  (let [tasks (filterv #(= :task (:kind %)) (:nodes model))
        pairs (set (mapv (juxt :from :to) (:edges model)))
        edges (coll/into-> tasks []
                (remove :dependency-wires)
                (mapcat (fn legacy-prerequisites [task]
                          (mapv #(hash-map :from % :to (:id task)) (:depends-on task))))
                (remove #(contains? pairs [(:from %) (:to %)])))]
    (-> model
        (update :edges #(coll/into-> (into (vec %) edges) [] (distinct)))
        (update :nodes #(mapv (fn mark-wires [card] (cond-> card (= :task (:kind card)) (assoc :dependency-wires true))) %)))))

(defn configuration [card staff]
  (let [person (coll/first-where #(= (:staff-id card) (:id %)) staff)]
    [(name (:kind card)) (:title card)
     (when (contains? #{:action :pm} (:kind card))
       (cond-> (:prompt card) (:task-type card) (vector (:task-type card))
         (coll/not-empty (:inputs card)) (vector (:inputs card))))
     (:staff-id card)
     (when (:staff-id card)
       (mapv #(get person %) [:id :role-type :title :enabled :model :effort :responsibilities :deliverables]))]))

(defn version [card staff]
  (digest/string->sha256-hex (pr-str (configuration card staff))))

(defn- input-version [card]
  (digest/string->sha256-hex
    (pr-str [(:source-candidate card) (:source-revision card)
             (mapv #(get-in card [:production %]) [:manifest :gui :atlas :import-id])])))

(defn node-error [card staff]
  (cond
    (nil? card) "节点不存在"
    (string/blank? (:title card)) "请先填写节点名称"
    (contains? #{:person :pm} (:kind card))
    (let [person (coll/first-where #(= (:staff-id card) (:id %)) staff)]
      (cond
        (nil? (:staff-id card)) "请先为 Person 选择职员和角色职能"
        (not (:enabled person)) "职员已停用或删除，请重新选择"
        (not (roles/role-type person)) "请先设置职员的角色职能"
        (coll/any? #(string/blank? (get person %)) [:title :model :effort :responsibilities :deliverables])
        "请补全职员名称、模型、思考强度、职责说明和交付要求"
        (not (contains? #{"低" "中" "高" "极高"} (:effort person))) "请设置有效的思考强度"))
    (and (= :action (:kind card)) (string/blank? (:prompt card))) "请先填写并保存 Action 任务描述"))

(defn connection-error [model staff edge]
  (let [source (node model (:from edge)) target (node model (:to edge))]
    (or (node-error source staff) (node-error target staff)
        (when (and (input-edge? model edge) (not= "resource-import" (:task-type target)) (nil? (:artifact-id edge)))
          "请先批准 Assets 固定版本，再连接到 Action")
        (when-not (contains? (set (get successors (:kind source))) (:kind target))
          "连线类型无效，请按 Person → Action → Result → Assets → GUI 重新连接")
        (when (or (and (nil? (:artifact-id edge)) (:source-version edge) (not= (:source-version edge) (version source staff)))
                  (and (:target-version edge) (not= (:target-version edge) (version target staff)))
                  (and (nil? (:artifact-id edge)) (:input-version edge) (not= (:input-version edge) (input-version source))))
          "节点配置已变更，请右键连线重新确认或重新拉线"))))

(defn- upstream-error-impl [model staff id visited]
  (if (contains? visited id)
    "不支持循环连接"
    (let [card (node model id)
          incoming (filterv #(and (= id (:to %)) (not (dependency-edge? model %))) (:edges model))
          inputs (filterv #(input-edge? model %) incoming)
          edge (coll/first-where #(not (input-edge? model %)) incoming)
          input-error (coll/first-where identity
                                        (mapv (fn check-input [input]
                                                (or (connection-error model staff input)
                                                    (when-not (:artifact-id input) (upstream-error-impl model staff (:from input) (conj visited id))))) inputs))]
      (cond
        input-error input-error
        (and (= "resource-import" (:task-type card)) (not= 1 (count inputs))) "资源入库 Action 需要连接一个 Assets 输入"
        edge (or (connection-error model staff edge)
                 (upstream-error-impl model staff (:from edge) (conj visited id)))
        (contains? #{:person :pm} (:kind card)) (node-error card staff)
        (= :action (:kind card)) "请先连接已配置的 Person 负责人"
        :else "此节点尚未连接可执行的上游任务"))))

(defn upstream-error [model staff id]
  (upstream-error-impl model staff id #{}))

(defn output-error [model staff id]
  (or (node-error (node model id) staff) (when-not (and (= :assets (:kind (node model id))) (:artifact-id (node model id))) (upstream-error model staff id))))

(defn edge-error [model staff edge]
  (or (connection-error model staff edge) (when-not (:artifact-id edge) (upstream-error model staff (:from edge)))))

(defn task-error [model staff id]
  (output-error model staff id))

(defn stamp-edge [model staff edge]
  (cond-> (assoc edge :source-version (version (node model (:from edge)) staff)
                 :target-version (version (node model (:to edge)) staff))
    (input-edge? model edge) (assoc :input-version (input-version (node model (:from edge))))
    (and (input-edge? model edge) (:artifact-id (node model (:from edge)))) (assoc :artifact-id (:artifact-id (node model (:from edge))))))

(defn execution-error [model staff id]
  (if-not (= :person (:kind (node model id)))
    (task-error model staff id)
    (let [edges (filterv #(= id (:from %)) (:edges model))]
      (or (node-error (node model id) staff)
          (when-not (coll/not-empty edges) "请先从 Person 接点拖出 Action 任务")
          (coll/first-where identity (mapv #(task-error model staff (:to %)) edges))))))

(defn stamp-legacy [model staff]
  (update model :edges #(mapv (fn stamp [edge] (if (:source-version edge) edge (stamp-edge model staff edge))) %)))

(defn- dependency-path? [model from to seen]
  (cond
    (= from to) true
    (contains? seen from) false
    :else (coll/any? #(dependency-path? model (:to %) to (conj seen from))
                     (filterv #(and (= from (:from %)) (dependency-edge? model %)) (:edges model)))))

(defn dependency-cycle? [model from to]
  (dependency-path? model to from #{}))

(defn connect [model staff from to]
  (let [edge {:from from :to to}
        dependency? (dependency-edge? model edge)]
    (when-not (contains? (set (get successors (:kind (node model from)))) (:kind (node model to)))
      (throw (ex-info "此接点不支持目标节点类型" {})))
    (if dependency?
      (do
        (when (= "allocated" (:status (node model to)))
          (throw (ex-info "已分配任务的前置关系已冻结，请提出任务变更后再调整" {})))
        (when (= from to) (throw (ex-info "任务不能作为自己的前置" {})))
        (when (coll/any? #(= [from to] [(:from %) (:to %)]) (:edges model))
          (throw (ex-info "前置任务已连接" {})))
        (when (dependency-cycle? model from to)
          (throw (ex-info "前置任务不能形成循环" {}))))
      (do
        (when-let [error (output-error model staff from)] (throw (ex-info error {})))
        (when (and (input-edge? model edge) (not= "resource-import" (:task-type (node model to))) (nil? (:artifact-id (node model from))))
          (throw (ex-info "请先批准 Assets 固定版本，再连接到 Action" {})))
        (when-let [source (or (:source-action (node model to)) (:source-pm (node model to)))]
          (when (not= source from)
            (throw (ex-info "Result 固定绑定产出它的 Action" {}))))
        (when (and (not (and (input-edge? model edge) (not= "resource-import" (:task-type (node model to)))))
                   (coll/any? #(and (not (dependency-edge? model %)) (= to (:to %)) (not= from (:from %))
                                    (= (input-edge? model edge) (input-edge? model %))) (:edges model)))
          (throw (ex-info "目标节点已有上游，请先断开原连线" {})))
        (when (and (not (contains? #{:person :pm :action :assets :task} (:kind (node model from))))
                   (coll/any? #(and (= from (:from %)) (not= to (:to %))) (:edges model)))
          (throw (ex-info "此接点只支持一个后续节点，请先断开原连线" {})))))
    (let [edge (stamp-edge model staff edge)
          existing (coll/any? #(= [from to] [(:from %) (:to %)]) (:edges model))]
      (when-not dependency?
        (when-let [error (when-not (and (= :assets (:kind (node model from))) (:artifact-id (node model from))) (upstream-error model staff from))] (throw (ex-info error {})))
        (when (string/includes? (or (upstream-error-impl (update model :edges conj edge) staff to #{}) "") "循环")
          (throw (ex-info "不支持循环连接" {}))))
      (update model :edges
              #(if existing (mapv (fn replace-edge [old] (if (= [from to] [(:from old) (:to old)]) edge old)) %)
                   (conj % edge))))))
