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

(ns editor.ai-overview-test
  (:require [clojure.test :refer [deftest is]]
            [editor.ai-overview :as overview]))

(def base {:tasks [{:id "t" :title "Task" :spec-revision "r1" :source-path "/pm.aicanvas" :canvas-path "/work.aicanvas" :action-ids ["a"]}]
           :jobs [] :approvals [] :deliveries [] :artifacts []})

;; Retries and Result confirmations must not inflate completed Action steps.
(deftest execution-is-not-acceptance-test
  (let [jobs [{:id "old" :task-id "t" :canvas-id "work" :action-id "a" :status "failed"}
              {:id "new" :task-id "t" :canvas-id "work" :action-id "a" :status "completed"}
              {:id "confirm" :task-id "t" :canvas-path "/work.aicanvas" :action-id "result" :status "completed"}]
        docs [{:path "/work.aicanvas" :nodes [{:id "result" :kind "result"}]}]
        model (overview/summarize (assoc base :jobs jobs) docs [])]
    (is (= 0 (:accepted model)))
    (is (= 1 (:total model)))
    (is (= [1 1] ((juxt :done :total) (first (:tasks model)))))
    (is (= "待验收" (:status (first (:tasks model)))))
    (is (= [] (:issues model)))))

;; Only the current specification and exact attempts count as acceptance.
(deftest accepted-revision-test
  (let [job {:id "job" :task-id "t" :canvas-id "work" :action-id "a" :status "completed"}
        approval {:kind "task-acceptance" :subject-id "t" :revision "r1" :decision "accepted" :job-ids ["job"] :artifact-ids ["asset"]}
        data (assoc base :jobs [job] :approvals [approval] :artifacts [{:id "asset" :kind "asset-bundle"}])
        accepted (overview/summarize data [] [])
        changed (overview/summarize (assoc-in data [:tasks 0 :spec-revision] "r2") [] [])
        retry (overview/summarize (update data :jobs conj (assoc job :id "retry" :status "running")) [] [])]
    (is (= 1 (:accepted accepted)))
    (is (= "已验收" (get-in accepted [:artifacts 0 :status])))
    (is (= 0 (:accepted changed)))
    (is (= 0 (:accepted retry)))))

;; Same-named stages in different plans stay separate; old plans stay out.
(deftest distinct-plans-and-review-test
  (let [tasks [(first (:tasks base)) (assoc (first (:tasks base)) :id "t2" :source-path "/pm2.aicanvas")]
        docs [{:path "/pm.aicanvas" :title "Plan A" :nodes [{:id "t" :kind "task" :stage "设计" :status "allocated"}
                                                            {:id "pending" :kind "task" :status "review" :title "Review" :prompt "Work"}
                                                            {:id "blank" :kind "task" :status "review" :title "Task"}
                                                            {:id "old" :kind "task" :status "superseded"}]}
              {:path "/pm2.aicanvas" :title "Plan B" :nodes [{:id "t2" :kind "task" :stage "设计" :status "allocated"}]}]
        model (overview/summarize (assoc base :tasks tasks) docs [])]
    (is (= 2 (count (:stages model))))
    (is (= ["pending"] (mapv :id (:reviews model))))
    (is (= 2 (:total model)))))

;; Standalone staff work stays visible; unknown runs are not completed.
(deftest unplanned-work-test
  (let [job {:id "j" :staff-id "person" :canvas-id "c" :action-id "a" :status "unknown"}
        model (overview/summarize (assoc base :jobs [job]) [] [{:id "person" :title "职员"}])]
    (is (= [job] (:unplanned model)))
    (is (= 1 (count (:running model))))
    (is (= 1 (count (:issues model))))
    (is (= [job] (get-in model [:staff 0 :jobs])))))

;; Unchosen alternatives of accepted tasks must not become pending approvals.
(deftest accepted-alternatives-test
  (let [job {:id "job" :task-id "t" :canvas-id "work" :action-id "a" :status "completed"}
        approval {:kind "task-acceptance" :subject-id "t" :revision "r1" :decision "accepted" :job-ids ["job"] :artifact-ids []}
        docs [{:path "/work.aicanvas" :nodes [{:id "r" :kind "result" :candidates [{:id "c" :text "Alternative"}]}]}]]
    (is (= ["r"] (mapv :id (filterv #(= "方案待选择" (:label %)) (:reviews (overview/summarize (assoc base :jobs [job]) docs []))))))
    (is (= [] (:reviews (overview/summarize (assoc base :jobs [job] :approvals [approval]) docs []))))))

;; Standalone results retain their responsible employee and immutable revision.
(deftest standalone-review-context-test
  (let [docs [{:path "/design.aicanvas"
               :nodes [{:id "p" :kind "person" :staff-id "artist"}
                       {:id "a" :kind "action" :title "Design"}
                       {:id "r" :kind "result" :source-action "a" :title "Option" :candidates [{:id "c" :text "Result"}]}]
               :edges [{:from "p" :to "a"} {:from "a" :to "r"}]}]
        data {:artifacts [{:id "asset" :kind "proposal" :source-path "/design.aicanvas" :source-card-id "r" :revision "fixed"}]}
        model (overview/summarize data docs [])]
    (is (= "artist" (get-in model [:artifacts 0 :staff-id])))
    (is (= "文档" (get-in model [:artifacts 0 :content-type])))
    (is (= #{"artist"} (set (mapv :staff-id (:reviews model)))))
    (is (string? (get-in model [:reviews 0 :revision])))))

;; Stage rows show real dependency context and distinguish work from acceptance.
(deftest stage-dependency-context-test
  (let [data (assoc base :tasks [(first (:tasks base)) (assoc (first (:tasks base)) :id "t2" :dependencies ["t"])]
                    :jobs [{:id "run" :task-id "t2" :action-id "a2" :status "running"}])
        docs [{:path "/pm.aicanvas" :nodes [{:id "t" :stage "A"} {:id "t2" :stage "B"}]}]
        model (overview/summarize data docs [])
        stage (last (:stages model))]
    (is (= "执行中" (:status stage)))
    (is (= ["t"] (mapv :id (:dependencies stage))))
    (is (= 0 (:accepted model)))))
