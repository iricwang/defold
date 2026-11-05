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

(ns editor.ai-tasks
  "Person-owned action queues, driven by acknowledged remote turn status."
  (:require [util.coll :as coll]))

(def status-labels
  {"idle" "待执行" "queued" "排队中" "submitting" "提交中" "running" "执行中"
   "waiting" "等待确认" "completed" "已完成" "failed" "执行失败"
   "interrupted" "已中断" "unknown" "等待核对" "paused" "已暂停" "cancelled" "已结束"})

(defn status [state id]
  (get-in state [:sessions id :execution :status] "idle"))

(defn active? [state id]
  (contains? #{"submitting" "running" "waiting" "unknown"} (status state id)))

(defn person-id [model action-id]
  (let [people (coll/into-> (:nodes model) #{} (filter #(= :person (:kind %))) (map :id))
        id (:from (coll/first-where #(and (= action-id (:to %)) (contains? people (:from %))) (:edges model)))]
    (if (coll/any? #(and (= action-id (:id %)) (= :pm (:kind %))) (:nodes model))
      action-id
      (when (coll/any? #(and (= id (:id %)) (= :person (:kind %))) (:nodes model)) id))))

(defn action-ids [model person-id]
  (if (coll/any? #(and (= person-id (:id %)) (= :pm (:kind %))) (:nodes model))
    [person-id]
    (coll/into-> (:edges model) [] (filter #(= person-id (:from %))) (map :to))))

(defn locked? [state]
  (or (coll/any? #(active? state %) (coll/keys (:sessions state)))
      (coll/any? #(contains? #{"running" "paused"} (get-in % [:queue :status])) (coll/vals (:sessions state)))))

(defn confirmation-locked? [state id]
  (or (= "queued" (status state id)) (active? state id)
      (coll/any? #(and (= id (get-in % [:queue :current]))
                       (contains? #{"running" "paused"} (get-in % [:queue :status]))) (coll/vals (:sessions state)))))

(defn reconcile [state]
  (reduce-kv (fn reconcile-person [state person session]
               (let [{:keys [current remaining] queue-status :status :as queue} (:queue session)
                     execution-status (status state current)]
                 (cond
                   (and current (= "completed" execution-status) (contains? #{"running" "paused"} queue-status))
                   (assoc-in state [:sessions person :queue]
                             (-> queue (update :completed conj current) (assoc :current nil)
                                 (assoc :status (if (coll/not-empty remaining) queue-status "completed"))))

                   (and current (= "running" queue-status) (contains? #{"failed" "interrupted" "unknown"} execution-status))
                   (assoc-in state [:sessions person :queue :status] "paused")

                   :else state))) state (:sessions state)))

(defn start [state person ids]
  (when-not (coll/not-empty ids) (throw (ex-info "请先将 Person 连接到 Action 卡片" {})))
  (when (coll/any? #(active? state %) ids) (throw (ex-info "关联任务仍在执行，请等待当前任务结束" {})))
  (-> (reduce (fn queue-action [s id] (assoc-in s [:sessions id :execution] {:status "queued"})) state ids)
      (assoc-in [:sessions person :queue] {:status "running" :actions ids :remaining ids :completed [] :current nil})))

(defn claim-next [state person]
  (let [{:keys [current remaining] queue-status :status} (get-in state [:sessions person :queue])]
    (if (and (= "running" queue-status) (nil? current) (coll/not-empty remaining))
      (let [id (first remaining)]
        (-> state (assoc-in [:sessions person :queue :current] id)
            (assoc-in [:sessions person :queue :remaining] (subvec remaining 1))))
      state)))

(defn claim-id [state person id]
  (let [queue (get-in state [:sessions person :queue])]
    (if (and (= "running" (:status queue)) (nil? (:current queue)) (contains? (set (:remaining queue)) id))
      (-> state (assoc-in [:sessions person :queue :current] id)
          (update-in [:sessions person :queue :remaining] #(filterv (fn other-action [candidate] (not= id candidate)) %)))
      state)))

(defn enqueue-confirmation [state person confirmation]
  (let [id (:result-id confirmation)
        previous (get-in state [:sessions id :confirmation])
        execution (status state id)
        queue (get-in state [:sessions person :queue])]
    (cond
      (and (= (:revision confirmation) (:revision previous))
           (or (confirmation-locked? state id) (= "completed" execution))) state
      (confirmation-locked? state id)
      (throw (ex-info "此方案正在队列中，请等待完成后再修改或确认新版本" {}))
      :else
      (let [queue (if (contains? #{"running" "paused"} (:status queue)) queue
                      {:status "running" :actions [] :remaining [] :completed [] :current nil})]
        (-> state
            (assoc-in [:sessions id :confirmation] confirmation)
            (assoc-in [:sessions id :draft] (:draft confirmation))
            (assoc-in [:sessions id :execution] {:status "queued"})
            (assoc-in [:sessions person :queue]
                      (-> queue (update :actions conj id) (update :remaining conj id))))))))

(defn pause [state person]
  (if (= "running" (get-in state [:sessions person :queue :status]))
    (assoc-in state [:sessions person :queue :status] "paused")
    state))

(defn resume [state person]
  (let [state (reconcile state)
        current (get-in state [:sessions person :queue :current])]
    (when (and (= "unknown" (status state current)) (not (get-in state [:sessions current :pending])))
      (throw (ex-info "请先连接并核对上次执行状态，避免重复提交" {})))
    (cond-> state
      (not= "completed" (get-in state [:sessions person :queue :status]))
      (assoc-in [:sessions person :queue :status] "running")
      (and current (contains? #{"failed" "interrupted" "unknown"} (status state current)))
      (-> (assoc-in [:sessions person :queue :current] nil)
          (update-in [:sessions person :queue :remaining] #(into [current] %))
          (assoc-in [:sessions current :execution] {:status "queued"})))))

(defn cancel [state person]
  (let [remaining (get-in state [:sessions person :queue :remaining])]
    (-> (reduce (fn unqueue [s id] (assoc-in s [:sessions id :execution :status] "idle")) state remaining)
        (assoc-in [:sessions person :queue :status] "cancelled")
        (assoc-in [:sessions person :queue :remaining] []))))

(defn recover [state]
  (update state :sessions
          (fn recover-sessions [sessions]
            (coll/into-> sessions {}
              (map (fn recover-session [[id session]]
                     [id (cond-> session
                           (= "running" (get-in session [:queue :status])) (assoc-in [:queue :status] "paused")
                           (contains? #{"submitting" "running" "waiting"} (get-in session [:execution :status]))
                           (assoc-in [:execution :status]
                                     (if (or (:pending session) (get-in session [:execution :turn-id]))
                                       "unknown" "failed")))]))))))

(defn accept-snapshot [state id snapshot]
  (let [turn-id (get-in state [:sessions id :execution :turn-id])
        turn (when turn-id (coll/first-where #(= turn-id (:id %)) (get-in snapshot [:thread :turns])))
        pending (or (coll/not-empty (:pending snapshot)) (coll/not-empty (:questions snapshot)))]
    (if-not turn
      state
      (-> state
          (assoc-in [:sessions id :execution :status]
                    (case (:status turn)
                      "completed" "completed" "failed" "failed" "interrupted" "interrupted"
                      "inProgress" (if pending "waiting" "running") "unknown"))
          reconcile))))
