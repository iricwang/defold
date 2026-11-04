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

(ns editor.ai-graph-test
  (:require [clojure.test :refer [deftest is]]
            [editor.ai-canvas :as canvas]
            [editor.ai-graph :as graph]
            [editor.ai-roles :as roles]
            [editor.ai-tasks :as tasks]))

(defn fixture []
  (let [staff [(roles/new-role "planning" #{})]
        model {:nodes [(roles/assign-staff {:id "p" :kind :person :title "负责人"} (first staff))
                       {:id "a" :kind :action :title "设计任务" :prompt "输出方案"}
                       {:id "r" :kind :result :title "方案结果"}
                       {:id "s" :kind :assets :title "交付内容"}]
               :edges []}]
    [staff (-> model (graph/connect staff "p" "a") (graph/connect staff "a" "r") (graph/connect staff "r" "s"))]))

;; Legacy direct assignments must not bypass Person; disconnecting the owner
;; blocks the output chain; reconnecting restores it without data loss.
(deftest person-required-for-entire-workflow-test
  (let [[staff model] (fixture)
        legacy (-> model
                   (canvas/update-node "a" #(roles/assign-staff % (first staff)))
                   (assoc :edges [{:from "a" :to "r"} {:from "r" :to "s"}]))
        repaired (graph/connect legacy staff "p" "a")
        wrong-parent (update legacy :edges conj {:from "r" :to "a"})]
    (doseq [id ["a" "r" "s"]]
      (is (= "请先连接已配置的 Person 负责人" (graph/task-error legacy staff id)))
      (is (string? (graph/output-error legacy staff id)))
      (is (nil? (graph/task-error repaired staff id))))
    (doseq [edge (:edges legacy)] (is (string? (graph/edge-error legacy staff edge))))
    (is (thrown? Exception (graph/connect legacy staff "a" "r")))
    (is (nil? (tasks/person-id wrong-parent "a")))
    (is (string? (graph/task-error wrong-parent staff "a")))
    (is (= "p" (tasks/person-id repaired "a")))))

;; Configuration changes invalidate existing links and every reachable task.
(deftest stale-connection-test
  (let [[staff model] (fixture)
        changed (canvas/update-node model "a" #(assoc % :prompt "新的任务目标"))
        incoming (graph/connect changed staff "p" "a")
        repaired (graph/connect incoming staff "a" "r")]
    (is (nil? (graph/task-error model staff "s")))
    (is (string? (graph/task-error changed staff "a")))
    (is (string? (graph/task-error changed staff "s")))
    (is (nil? (graph/task-error incoming staff "a")))
    (is (string? (graph/task-error incoming staff "r")))
    (is (nil? (graph/task-error repaired staff "s")))
    (is (= ["a" "r" "s"] (mapv :to (:edges repaired))))))

;; External role edits, deletion and incomplete fields cannot dispatch tasks.
(deftest staff-configuration-test
  (let [[staff model] (fixture)
        changed [(assoc (first staff) :model "Astra")]
        missing (canvas/update-node model "p" #(dissoc % :staff-id))]
    (is (string? (graph/task-error model changed "a")))
    (is (string? (graph/task-error model [] "a")))
    (is (string? (graph/output-error missing staff "p")))
    (is (thrown? Exception (graph/connect missing staff "p" "a")))
    (doseq [field [:model :effort :responsibilities :deliverables]]
      (is (string? (graph/output-error model [(assoc (first staff) field "")] "p"))))
    (is (nil? (graph/task-error (graph/connect model changed "p" "a") changed "a")))))

;; Typed ports reject reverse links and retain order when confirming a link.
(deftest typed-ports-test
  (let [[staff model] (fixture)
        with-other (update model :nodes conj {:id "a2" :kind :action :title "第二项" :prompt "输出另一方案"})
        connected (graph/connect with-other staff "p" "a2")
        confirmed (graph/connect connected staff "p" "a")]
    (is (= [:action] (graph/successors :person)))
    (is (= [:result] (graph/successors :action)))
    (is (thrown? Exception (graph/connect model staff "p" "r")))
    (is (thrown? Exception (graph/connect model staff "s" "p")))
    (is (= (:edges connected) (:edges confirmed)))
    (is (= 4 (count (:edges connected))))))

;; Outputs and movement retain valid links; existing stamps are never reset.
(deftest stable-configuration-test
  (let [[staff model] (fixture)
        moved (canvas/update-node model "p" #(assoc % :x 500.0 :y 300.0))
        completed (canvas/update-node model "r" #(assoc % :candidates [{:id "answer" :text "方案"}] :chosen "answer"))]
    (is (nil? (graph/task-error moved staff "a")))
    (is (nil? (graph/task-error completed staff "s")))
    (is (= model (graph/stamp-legacy model [(assoc (first staff) :model "Astra")])))))

;; Blank-canvas creation adds exactly one node at the dropped world coordinates.
(deftest single-node-creation-test
  (doseq [kind [:person :action :result :assets]]
    (let [model (canvas/add-node {:nodes [] :edges []} kind -145.0 320.0)
          card (first (:nodes model))]
      (is (= 1 (count (:nodes model))))
      (is (= [] (:edges model)))
      (is (= [kind -145.0 320.0] [(:kind card) (:x card) (:y card)]))
      (is (= (:id card) (:selected model))))))
