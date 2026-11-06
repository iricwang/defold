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

(ns editor.ai-replan
  "Human-reviewed PM deltas preserving existing work and accepted history."
  (:require [clojure.string :as string]
            [editor.ai-graph :as graph]
            [editor.ai-pm :as pm]
            [editor.ai-project-work :as work]
            [editor.craft-project :as craft]
            [util.coll :as coll]
            [util.digest :as digest])
  (:import [java.time Instant]))

(defn change [model pm-id id]
  (or (coll/first-where #(= id (:id %)) (:plan-changes (graph/node model pm-id)))
      (throw (ex-info "重规划变更不存在，请刷新规划" {}))))

(defn- decision [data id]
  (coll/first-where #(= id (:id %)) (:planning-decisions data)))

(defn- signature [item]
  (digest/string->sha256-hex
    (pr-str [(:id item) (:kind item) (:turn-id item)
             (mapv #(get (:before item) %) (into pm/task-fields [:id :status :allocated-path :allocated-revision]))
             (mapv #(get (:after item) %) (into pm/task-fields [:id :source-pm :plan-task-id]))])))

(defn- verify-decision! [item record]
  (when (and (:signature record) (not= (:signature record) (signature item)))
    (throw (ex-info "同一规划变更编号对应不同内容，原人工决定未复用；请重新规划" {}))))

(defn- impact-data [root model item data]
  (let [id (get-in item [:before :id])
        card (graph/node model id)
        jobs (filterv #(= id (:task-id %)) (:jobs data))
        approvals (filterv #(and (= "task-acceptance" (:kind %)) (= id (:subject-id %))) (:approvals data))
        accepted (= "已验收" (work/task-status data id))
        artifacts (filterv #(= id (:task-id %)) (:artifacts data))
        artifact-ids (set (mapv :id artifacts))
        asset-approvals (filterv #(and (= "artifact" (:kind %)) (contains? artifact-ids (:subject-id %))) (:approvals data))
        dependents (filterv #(and (not (contains? #{"cancelled" "superseded"} (:scope-status %)))
                                  (contains? (set (:dependencies %)) id)) (:tasks data))
        path (:allocated-path card)
        file (when path (craft/resource-file root path))
        hash (when file (if (.isFile file) (craft/file-hash root path) "missing"))
        snapshot (when card (pm/task-snapshot model card))
        running (filterv #(contains? #{"running" "unknown"} (:status %)) jobs)
        started (filterv #(or (:turn-id %) (:started-at %) (contains? #{"completed" "failed" "interrupted" "running" "unknown"} (:status %))) jobs)
        execution-token (digest/string->sha256-hex (pr-str [jobs hash artifacts approvals asset-approvals]))
        stored (decision data (:id item))
        _ (verify-decision! item stored)]
    {:token (digest/string->sha256-hex (pr-str [item snapshot jobs hash artifacts approvals asset-approvals dependents]))
     :execution-token execution-token :jobs jobs :running running :started started :accepted accepted
     :artifacts artifacts :asset-approvals asset-approvals :approvals approvals :dependents dependents :work-file path :work-file-hash hash
     :acknowledged (= execution-token (:acknowledged stored))
     :stale (and (:before item) (not= (:before item) snapshot))
     :differences (filterv #(not= (get (:before item) %) (get (:after item) %)) pm/task-fields)
     :blocked (cond
                (and (:before item) (not= (:before item) snapshot)) "原任务已修改，请拒绝此项后重新规划"
                (= "missing" hash) "原工作 Canvas 已丢失，请先恢复"
                (and accepted (= "cancel" (:kind item))) "已验收任务不能撤销；新增范围请创建后续修订"
                (coll/not-empty running) "先请求停止，等待真实执行状态结束，再核对产出"
                (and (coll/not-empty started) (not= execution-token (:acknowledged stored))) "请先核对已有执行与产出，填写交接说明"
                (coll/any? #(nil? (graph/node model %)) (get-in item [:after :depends-on])) "需先批准前置变更；被拒绝的前置变更需要重新规划"
                :else nil)}))

(defn impact [root model item]
  (impact-data root model item (work/read-work root)))

(defn- store-decision [data entry]
  (update data :planning-decisions
          (fn upsert [entries]
            (if (coll/any? #(= (:id entry) (:id %)) entries)
              (mapv #(if (= (:id entry) (:id %)) (merge % entry) %) entries)
              (conj (vec entries) entry)))))

(defn request-stop! [root model item]
  (let [data (work/transact! root
                             (fn hold-task [data]
                               (let [impact (impact-data root model item data)
                                     id (get-in item [:before :id])
                                     spec (coll/first-where #(= id (:id %)) (:tasks data))]
                                 (when (contains? #{"applying" "applied" "rejected"} (:status (decision data (:id item))))
                                   (throw (ex-info "此变更已经决定，不能重新请求停止" {})))
                                 (when (or (:stale impact) (nil? spec) (:accepted impact))
                                   (throw (ex-info "该任务不能请求停止，请刷新变更影响" {})))
                                 (when (and (:planning-hold spec) (not= (:id item) (:planning-hold spec)))
                                   (throw (ex-info "任务已有另一项变更待处理" {})))
                                 (-> data
                                     (update :tasks #(mapv (fn hold [task] (cond-> task (= id (:id task)) (assoc :planning-hold (:id item)))) %))
                                     (store-decision {:id (:id item) :signature (signature item) :status "review" :stop-requested-at (str (Instant/now))})))))]
    (:running (impact-data root model item data))))

(defn acknowledge! [root model item note]
  (when (or (string/blank? note) (> (count note) 4000)) (throw (ex-info "请填写本次执行、产出与交接核对说明（1–4000 字）" {})))
  (work/transact! root
                  (fn acknowledge-results [data]
                    (let [impact (impact-data root model item data)]
                      (when (or (:stale impact) (coll/not-empty (:running impact)))
                        (throw (ex-info "仍有执行中或待核对任务，不能确认交接" {})))
                      (store-decision data {:id (:id item) :signature (signature item) :status (or (:status (decision data (:id item))) "review") :acknowledged (:execution-token impact)
                                            :note note :reviewed-at (str (Instant/now)) :actor "local-human"})))))

(defn- materialize [model pm-id item record staff]
  (let [card (:card record)
        old-id (get-in item [:before :id])
        model (update model :nodes
                      (fn apply-cards [nodes]
                        (cond-> (mapv (fn apply-card [node]
                                        (cond
                                          (= pm-id (:id node)) (update node :plan-changes #(mapv (fn decide [row] (cond-> row (= (:id item) (:id row)) (assoc :status (:status record)))) %))
                                          (and (= old-id (:id node)) (:old-status record)) (assoc node :status (:old-status record) :replacement-task-id (:id card))
                                          :else node)) nodes)
                          (and card (coll/not-any? #(= (:id card) (:id %)) nodes)) (conj (assoc card :kind :task :x 350.0 :y (+ 540.0 (reduce max 0.0 (mapv #(double (:y % 0)) nodes))))))))]
    (if-not card model
            (let [pairs (set (mapv (juxt :from :to) (:edges model)))
                  sources (into [pm-id] (:depends-on card))]
              (update model :edges into (coll/into-> sources []
                                          (remove #(contains? pairs [% (:id card)]))
                                          (map #(graph/stamp-edge model staff {:from % :to (:id card)}))))))))

(defn reconcile-model [root source-path model staff]
  (let [data (work/read-work root)]
    (reduce (fn reconcile-manager [model manager]
              (reduce (fn reconcile-change [model item]
                        (let [record (decision data (:id item))
                              _ (verify-decision! item record)]
                          (when (and (:source-path record) (not= source-path (:source-path record)))
                            (throw (ex-info "规划身份被复制到其他文件，请处理重复身份后继续" {})))
                          (if (contains? #{"applied" "rejected"} (:status record))
                            (materialize model (:id manager) item record staff) model))) model (:plan-changes manager)))
            model (filterv #(= :pm (:kind %)) (:nodes model)))))

(defn reject! [root model pm-id item staff]
  (let [data (work/transact! root
                             (fn reject-change [data]
                               (verify-decision! item (decision data (:id item)))
                               (when (contains? #{"applying" "applied"} (:status (decision data (:id item))))
                                 (throw (ex-info "此项已确认，请恢复应用，不能撤销已生效决定" {})))
                               (-> data
                                   (update :tasks #(mapv (fn release [task] (if (= (:id item) (:planning-hold task)) (dissoc task :planning-hold) task)) %))
                                   (store-decision {:id (:id item) :signature (signature item) :status "rejected" :decided-at (str (Instant/now)) :actor "local-human"}))))]
    (materialize model pm-id item (decision data (:id item)) staff)))

(defn apply! [root source-path model pm-id item staff token]
  (let [data (work/transact! root
                             (fn reserve-change [data]
                               (let [stored (decision data (:id item))
                                     impact (impact-data root model item data)
                                     old-id (get-in item [:before :id])
                                     spec (coll/first-where #(= old-id (:id %)) (:tasks data))]
                                 (when (and (:source-path stored) (not= source-path (:source-path stored)))
                                   (throw (ex-info "此变更已绑定其他规划文件，不能重复应用" {})))
                                 (if (= "applied" (:status stored)) data
                                     (do
                                       (when-not (and (= "review" (:status item)) (not= "rejected" (:status stored)))
                                         (throw (ex-info "此项不处于待确认状态" {})))
                                       (when-not (= token (:token impact)) (throw (ex-info "变更影响已变化，请刷新后重新确认" {})))
                                       (when-let [error (:blocked impact)] (throw (ex-info error {})))
                                       (when (and (:planning-hold spec) (not= (:id item) (:planning-hold spec)))
                                         (throw (ex-info "任务已有另一项变更正在处理" {})))
                                       (-> data
                                           (update :tasks (fn reserve-identities [tasks]
                                                            (reduce (fn reserve-identity [tasks entry]
                                                                      (if (or (nil? (:id entry)) (coll/any? #(= (:id entry) (:id %)) tasks)) tasks
                                                                          (conj tasks (assoc entry :planning-hold (:id item) :scope-status "pending-change")))) tasks
                                                                    (cond-> [(merge (:before item) {:source-path source-path})]
                                                                      (not= "cancel" (:kind item)) (conj (select-keys (:after item) [:id :title]))))))
                                           (update :tasks #(mapv (fn reserve [task] (cond-> task (= old-id (:id task)) (assoc :planning-hold (:id item)))) %))
                                           (store-decision {:id (:id item) :signature (signature item) :status "applying" :proposal item :source-path source-path
                                                            :execution-token (:execution-token impact)
                                                            :handoff (or (:handoff stored)
                                                                         (when old-id {:previous-task-id old-id :work-canvas (:work-file impact) :work-file-hash (:work-file-hash impact)
                                                                                       :note (or (:note stored) "未开始工作，按本次人工批准的规格交接")
                                                                                       :artifact-ids (mapv :id (:artifacts impact)) :change-id (:id item)}))
                                                            :accepted (if (= "applying" (:status stored)) (:accepted stored) (:accepted impact))
                                                            :actor "local-human" :decided-at (str (Instant/now))})))))))
        reserved (decision data (:id item))]
    (if (= "applied" (:status reserved)) (materialize model pm-id item reserved staff)
        (let [current (impact root model item)]
          (when (or (:stale current) (coll/not-empty (:running current)) (not= (:execution-token reserved) (:execution-token current)))
            (throw (ex-info "任务在恢复应用前发生变化，已保留变更锁，请核对后重试" {})))
          (let [next (if (= "cancel" (:kind item)) model
                         (let [draft (materialize model pm-id item {:card (cond-> (:after item) (:handoff reserved) (assoc :handoff (:handoff reserved))) :status "review"} staff)]
                           (pm/allocate! root source-path draft (get-in item [:after :id]) staff)))
                card (when-not (= "cancel" (:kind item)) (graph/node next (get-in item [:after :id])))
                result (work/transact! root
                                       (fn commit-change [data]
                                         (let [current (impact-data root model item data)
                                               _ (when (or (coll/not-empty (:running current)) (not= (:execution-token reserved) (:execution-token current)))
                                                   (throw (ex-info "应用期间执行或产出已变化，保留原工作与变更锁；重新核对后重试" {})))
                                               old-id (get-in item [:before :id])
                                               old-status (when (and old-id (not (:accepted reserved))) (if (= "cancel" (:kind item)) "cancelled" "superseded"))
                                               record (assoc reserved :status "applied" :card card :old-status old-status)]
                                           (-> data
                                               (update :jobs #(mapv (fn retire-job [job]
                                                                      (cond-> job (and old-status (= old-id (:task-id job)) (contains? #{"queued" "blocked" "paused"} (:status job))) (assoc :status "cancelled"))) %))
                                               (update :tasks #(mapv (fn link-task [task]
                                                                       (cond-> (if (= (:id item) (:planning-hold task)) (dissoc task :planning-hold) task)
                                                                         (and old-status (= old-id (:id task))) (assoc :scope-status old-status :replacement-task-id (:id card))
                                                                         (= (:id card) (:id task)) (dissoc :scope-status)
                                                                         (= (:id card) (:id task)) (assoc :previous-task-id old-id :change-id (:id item)))) %))
                                               (store-decision record)))))]
            (materialize next pm-id item (decision result (:id item)) staff))))))
