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

(ns editor.ai-replan-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [editor.ai-delivery :as delivery]
            [editor.ai-document :as document]
            [editor.ai-graph :as graph]
            [editor.ai-pm :as pm]
            [editor.ai-pm-test :as fixtures]
            [editor.ai-project-work :as work]
            [editor.ai-replan :as replan]))

(defn planned-fixture []
  (let [f (fixtures/fixture)]
    (assoc f :model (pm/accept-plan (:model f) "pm" "initial" (:text f) (:staff f)))))

(defn next-plan [model plan staff]
  (pm/accept-plan model "pm" "next" (str "```ai-pm-plan\n" (json/write-str plan) "\n```") staff))

(defn pending [model] (filterv #(= "review" (:status %)) (:plan-changes (graph/node model "pm"))))

(defn apply-change [root model item staff]
  (replan/apply! root "/pm.aicanvas" model "pm" item staff (:token (replan/impact root model item))))

;; Replanning preserves human drafts and unchanged tasks until each delta is
;; reviewed; omitted tasks are retained, rejected changes do not affect others.
(deftest partial-review-and-stable-identity-test
  (let [{:keys [root model plan staff]} (planned-fixture)
        design (second (:nodes model)) art (nth (:nodes model) 2)
        edited (pm/edit-task model (:id design) {:prompt "人工改稿"} staff)
        proposal (next-plan edited plan staff)
        items (pending proposal)]
    (is (= (:nodes edited) (assoc (:nodes proposal) 0 (first (:nodes edited)))))
    (is (= 2 (count items)))
    (is (= (:id design) (get-in (first items) [:before :id])))
    (is (= "人工改稿" (get-in (first items) [:before :prompt])))
    (is (zero? (count (.listFiles root))))
    (is (thrown? Exception (pm/accept-plan proposal "pm" "third" (:text (fixtures/fixture)) staff)))
    (let [rejected (replan/reject! root proposal "pm" (first items) staff)]
      (is (= "人工改稿" (:prompt (graph/node rejected (:id design)))))
      (is (thrown? Exception (apply-change root rejected (second items) staff))))
    (let [same (next-plan model plan staff)]
      (is (zero? (count (pending same))))
      (is (= (:id art) (:id (nth (:nodes same) 2)))))
    (let [omitted (next-plan model (assoc-in plan [:stages 0 :tasks] [(get-in plan [:stages 0 :tasks 0])]) staff)]
      (is (= art (graph/node omitted (:id art)))))))

;; Changed allocation creates one successor Canvas; original human content is
;; unchanged, old queues stop, and stale work cannot restart.
;; Replaying the decision is idempotent, including after reopening the PM file.
(deftest allocation-revision-and-replay-test
  (let [{:keys [root model plan staff]} (planned-fixture)
        original (second (:nodes model))
        allocated (pm/allocate! root "/pm.aicanvas" model (:id original) staff)
        old (graph/node allocated (:id original))
        file (io/file root (subs (:allocated-path old) 1)) before (slurp file)
        jobs (work/enqueue! root [{:task-id (:id old) :staff-id (:staff-id old) :action-id "one"}])
        revised (next-plan allocated (assoc-in plan [:stages 0 :tasks 0 :staff-id] (:id (first staff))) staff)
        item (first (pending revised))
        applied (apply-change root revised item staff)
        successor (graph/node applied (get-in item [:after :id]))
        data (work/read-work root)]
    (is (= "allocated" (:status successor)))
    (is (not= (:allocated-path old) (:allocated-path successor)))
    (is (= before (slurp file)))
    (is (= "superseded" (:status (graph/node applied (:id old)))))
    (is (= "cancelled" (:status (first (:jobs data)))))
    (is (false? (work/claim! root (:id (first jobs)))))
    (is (thrown? Exception (work/enqueue! root [{:task-id (:id old) :staff-id (:staff-id old)}])))
    (is (thrown? Exception (delivery/admission data (:id old) {} {:inputs-fixed true})))
    (is (= applied (apply-change root revised item staff)))
    (is (= applied (replan/reconcile-model root "/pm.aicanvas" revised staff)))
    (is (thrown? Exception (replan/reconcile-model root "/copied.aicanvas" revised staff)))
    (spit (io/file root "pm.aicanvas") (json/write-str {:format "codexcamp.ai-canvas" :schemaVersion 1 :id "canvas_test_pm" :title "规划"
                                                        :template "ai-pm" :revision 1 :nodes (mapv #(update % :kind name) (:nodes revised)) :edges (:edges revised)}))
    (let [reopened (document/model (document/read-document root "/pm.aicanvas") nil)
          recovered (replan/reconcile-model root "/pm.aicanvas" reopened staff)]
      (is (= (:allocated-path successor) (:allocated-path (graph/node recovered (:id successor))))))
    (is (= 2 (count (:tasks (work/read-work root)))))
    (is (= 1 (count (:planning-decisions (work/read-work root)))))
    (is (= (:id old) (:previous-task-id (last (:tasks data)))))))

;; Active/unknown turns must end for real, then their outputs must be reviewed.
;; Holding a task blocks other views from claiming or enqueuing additional work.
(deftest stop-reconcile-and-handoff-test
  (let [{:keys [root model plan staff]} (planned-fixture)
        id (:id (second (:nodes model)))
        allocated (pm/allocate! root "/pm.aicanvas" model id staff)
        jobs (work/enqueue! root [{:task-id id :staff-id (:id (second staff)) :action-id "one"}])
        jid (:id (first jobs))
        _ (work/claim! root jid)
        _ (work/update-jobs! root #{jid} {:thread-id "chat" :turn-id "turn"})
        proposal (next-plan allocated (assoc-in plan [:stages 0 :tasks 0 :prompt] "新的工作规格") staff)
        item (first (pending proposal))]
    (is (thrown? Exception (apply-change root proposal item staff)))
    (is (= [jid] (mapv :id (replan/request-stop! root proposal item))))
    (is (thrown? Exception (replan/acknowledge! root proposal item "已核对")))
    (is (thrown? Exception (work/enqueue! root [{:task-id id :staff-id "another"}])))
    (work/observe-event! root {:method "turn/completed" :params {:threadId "chat" :turn {:id "turn" :status "interrupted"}}})
    (is (thrown? Exception (apply-change root proposal item staff)))
    (replan/acknowledge! root proposal item "已核对中断后的文件与未完成产出")
    (let [applied (apply-change root proposal item staff)]
      (is (= "allocated" (:status (graph/node applied (get-in item [:after :id])))))
      (let [card (graph/node applied (get-in item [:after :id]))
            doc (document/read-document root (:allocated-path card))]
        (is (re-find #"已核对中断后的文件与未完成产出" (:prompt (second (:nodes doc)))))
        (is (= id (get-in card [:handoff :previous-task-id])))))))

;; Acceptance, approvals, artifacts, and old work remain intact; changing an
;; accepted task creates additional scope instead of resetting historical facts.
(deftest accepted-history-and-explicit-cancel-test
  (let [{:keys [root model plan staff]} (planned-fixture)
        id (:id (second (:nodes model)))
        allocated (pm/allocate! root "/pm.aicanvas" model id staff)
        spec (first (:tasks (work/read-work root)))
        _ (work/transact! root #(assoc % :approvals [{:id "accepted" :kind "task-acceptance" :subject-id id :decision "accepted" :revision (:spec-revision spec) :job-ids []}]))
        proposal (next-plan allocated (assoc-in plan [:stages 0 :tasks 0 :prompt] "后续新增范围") staff)
        item (first (pending proposal))
        before (:approvals (work/read-work root))
        applied (apply-change root proposal item staff)]
    (is (= "allocated" (:status (graph/node applied id))))
    (is (= "已验收" (work/task-status (work/read-work root) id)))
    (is (= before (:approvals (work/read-work root))))
    (is (= "待执行" (work/task-status (work/read-work root) (get-in item [:after :id]))))
    (let [cancel-plan (assoc-in plan [:stages 0 :tasks] [(assoc (get-in plan [:stages 0 :tasks 0]) :existing-task-id id :change "cancel")])
          cancel-model (pm/accept-plan allocated "pm" "cancel" (str "```ai-pm-plan\n" (json/write-str cancel-plan) "\n```") staff)]
      (is (thrown? Exception (apply-change root cancel-model (first (pending cancel-model)) staff))))))

;; Review is bound to the current queue/file state; external file changes or
;; a competing queue claim invalidate an old approval token without publication.
(deftest stale-impact-and-rejection-test
  (let [{:keys [root model plan staff]} (planned-fixture)
        id (:id (second (:nodes model)))
        allocated (pm/allocate! root "/pm.aicanvas" model id staff)
        proposal (next-plan allocated (assoc-in plan [:stages 0 :tasks 0 :prompt] "changed") staff)
        item (first (pending proposal)) token (:token (replan/impact root proposal item))
        file (io/file root (subs (:allocated-path (graph/node allocated id)) 1))]
    (spit file (str (slurp file) "\n"))
    (is (thrown? Exception (replan/apply! root "/pm.aicanvas" proposal "pm" item staff token)))
    (replan/request-stop! root proposal item)
    (let [rejected (replan/reject! root proposal "pm" item staff)]
      (is (= "allocated" (:status (graph/node rejected id))))
      (is (nil? (:planning-hold (first (:tasks (work/read-work root)))))))))

;; Explicit cancellation retires only unaccepted scope and protects the old
;; Canvas; held work never blocks unrelated jobs belonging to the same employee.
(deftest cancellation-and-independent-work-test
  (let [{:keys [root model plan staff]} (planned-fixture)
        id (:id (second (:nodes model)))
        allocated (pm/allocate! root "/pm.aicanvas" model id staff)
        cancelled-plan (assoc-in plan [:stages 0 :tasks] [(assoc (get-in plan [:stages 0 :tasks 0]) :change "cancel" :existing-task-id id)])
        proposal (next-plan allocated cancelled-plan staff)
        item (first (pending proposal))
        jobs (work/enqueue! root [{:task-id id :staff-id "same" :action-id "old"} {:task-id "independent" :staff-id "same" :action-id "other"}])]
    (replan/request-stop! root proposal item)
    (is (false? (work/claim! root (:id (first jobs)))))
    (is (true? (work/claim! root (:id (second jobs)))))
    (let [applied (apply-change root proposal item staff)]
      (is (= "cancelled" (:status (graph/node applied id))))
      (is (= "已取消" (work/task-status (work/read-work root) id)))
      (is (= "running" (:status (second (:jobs (work/read-work root))))))
      (is (.isFile (io/file root (subs (:allocated-path (graph/node allocated id)) 1)))))
    (is (thrown? Exception (pm/allocate! root "/pm.aicanvas" allocated id staff)))))

;; Dependent revisions bind the explicitly approved successor, while omitted
;; consumers keep their old version. An independent addition survives refusal.
(deftest dependency-upgrade-and-partial-refusal-test
  (let [{:keys [root model plan staff]} (planned-fixture)
        revised (assoc-in plan [:stages 0 :tasks 0 :prompt] "revision")
        extra (assoc (get-in plan [:stages 0 :tasks 0]) :id "extra" :title "独立补充任务")
        proposal (next-plan model (update-in revised [:stages 0 :tasks] conj extra) staff)
        [first-change dependent independent] (pending proposal)]
    (is (thrown? Exception (apply-change root proposal dependent staff)))
    (let [applied (apply-change root proposal first-change staff)
          applied (apply-change root applied dependent staff)
          task (graph/node applied (get-in dependent [:after :id]))]
      (is (= [(get-in first-change [:after :id])] (:allocated-dependencies task))))
    (let [{:keys [root]} (fixtures/fixture)
          declined (replan/reject! root proposal "pm" first-change staff)
          applied (apply-change root declined independent staff)]
      (is (= "allocated" (:status (graph/node applied (get-in independent [:after :id])))))
      (is (thrown? Exception (apply-change root applied dependent staff))))))

;; An interrupted allocation keeps its reservation and cannot execute or reject.
;; and resumes exactly once. A different proposal cannot reuse its decision ID.
(deftest interrupted-application-recovery-test
  (let [{:keys [root model plan staff]} (planned-fixture)
        id (:id (second (:nodes model)))
        allocated (pm/allocate! root "/pm.aicanvas" model id staff)
        proposal (next-plan allocated (assoc-in plan [:stages 0 :tasks 0 :prompt] "revision") staff)
        item (first (pending proposal))
        disabled (mapv #(assoc % :enabled false) staff)]
    (is (thrown? Exception (apply-change root proposal item disabled)))
    (is (= "applying" (:status (first (:planning-decisions (work/read-work root))))))
    (is (thrown? Exception (work/enqueue! root [{:task-id (get-in item [:after :id]) :staff-id "s"}])))
    (is (thrown? Exception (replan/reject! root proposal "pm" item staff)))
    (let [applied (apply-change root proposal item staff)]
      (is (= "allocated" (:status (graph/node applied (get-in item [:after :id])))))
      (is (= 2 (count (:tasks (work/read-work root)))))
      (is (thrown? Exception (apply-change root proposal (assoc-in item [:after :prompt] "different") staff))))))
