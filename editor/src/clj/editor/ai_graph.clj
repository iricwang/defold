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

(def successors {:person [:action] :action [:result] :result [:assets] :assets [:gui] :gui []})

(defn node [model id]
  (coll/first-where #(= id (:id %)) (:nodes model)))

(defn configuration [card staff]
  (let [person (coll/first-where #(= (:staff-id card) (:id %)) staff)]
    [(name (:kind card)) (:title card)
     (when (= :action (:kind card)) (:prompt card))
     (:staff-id card)
     (when (:staff-id card)
       (mapv #(get person %) [:id :role-type :title :enabled :model :effort :responsibilities :deliverables]))]))

(defn version [card staff]
  (digest/string->sha256-hex (pr-str (configuration card staff))))

(defn node-error [card staff]
  (cond
    (nil? card) "节点不存在"
    (string/blank? (:title card)) "请先填写节点名称"
    (= :person (:kind card))
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
        (when-not (contains? (set (get successors (:kind source))) (:kind target))
          "连线类型无效，请按 Person → Action → Result → Assets → GUI 重新连接")
        (when (or (and (:source-version edge) (not= (:source-version edge) (version source staff)))
                  (and (:target-version edge) (not= (:target-version edge) (version target staff))))
          "节点配置已变更，请右键连线重新确认或重新拉线"))))

(defn upstream-error [model staff id]
  (loop [id id visited #{}]
    (if (contains? visited id)
      "不支持循环连接"
      (let [card (node model id)
            edge (coll/first-where #(= id (:to %)) (:edges model))]
        (cond
          edge (or (connection-error model staff edge)
                   (recur (:from edge) (conj visited id)))
          (= :person (:kind card)) (node-error card staff)
          (= :action (:kind card)) "请先连接已配置的 Person 负责人"
          :else "此节点尚未连接可执行的上游任务")))))

(defn output-error [model staff id]
  (or (node-error (node model id) staff) (upstream-error model staff id)))

(defn edge-error [model staff edge]
  (or (connection-error model staff edge) (upstream-error model staff (:from edge))))

(defn task-error [model staff id]
  (output-error model staff id))

(defn stamp-edge [model staff edge]
  (assoc edge :source-version (version (node model (:from edge)) staff)
         :target-version (version (node model (:to edge)) staff)))

(defn execution-error [model staff id]
  (if-not (= :person (:kind (node model id)))
    (task-error model staff id)
    (let [edges (filterv #(= id (:from %)) (:edges model))]
      (or (node-error (node model id) staff)
          (when-not (coll/not-empty edges) "请先从 Person 接点拖出 Action 任务")
          (coll/first-where identity (mapv #(task-error model staff (:to %)) edges))))))

(defn stamp-legacy [model staff]
  (update model :edges #(mapv (fn stamp [edge] (if (:source-version edge) edge (stamp-edge model staff edge))) %)))

(defn connect [model staff from to]
  (when-let [error (output-error model staff from)] (throw (ex-info error {})))
  (when-not (contains? (set (get successors (:kind (node model from)))) (:kind (node model to)))
    (throw (ex-info "此接点不支持目标节点类型" {})))
  (when-let [source (:source-action (node model to))]
    (when (not= source from)
      (throw (ex-info "Result 固定绑定产出它的 Action" {}))))
  (when (coll/any? #(and (= to (:to %)) (not= from (:from %))) (:edges model))
    (throw (ex-info "目标节点已有上游，请先断开原连线" {})))
  (when (and (not (contains? #{:person :action} (:kind (node model from))))
             (coll/any? #(and (= from (:from %)) (not= to (:to %))) (:edges model)))
    (throw (ex-info "此接点只支持一个后续节点，请先断开原连线" {})))
  (let [edge (stamp-edge model staff {:from from :to to})
        existing (coll/any? #(= [from to] [(:from %) (:to %)]) (:edges model))]
    (update model :edges
            #(if existing (mapv (fn replace-edge [old] (if (= [from to] [(:from old) (:to old)]) edge old)) %)
                 (conj % edge)))))
