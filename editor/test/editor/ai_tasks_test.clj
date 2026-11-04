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

(ns editor.ai-tasks-test
  (:require [clojure.test :refer [deftest is]]
            [editor.ai-canvas :as canvas]
            [editor.ai-roles :as roles]
            [editor.ai-tasks :as tasks]
            [editor.ai-workbench :as workbench]))

;; A Person owns multiple Actions without breaking each Action's output chain.
(deftest person-graph-test
  (let [model (canvas/add-person (canvas/initial-canvas))
        person (:selected model)
        expanded (canvas/add-flow model)
        second (:selected expanded)
        linked (canvas/connect-nodes expanded person second)
        ids (tasks/action-ids linked person)
        reordered (canvas/move-action linked person second -1)]
    (is (= ["action-1" second] ids))
    (is (= [second "action-1"] (tasks/action-ids reordered person)))
    (is (= "action-1" (workbench/flow-action-id linked "assets-1")))
    (is (= [] (tasks/action-ids (canvas/remove-node linked person) person)))
    (is (thrown? Exception (canvas/connect-nodes linked "action-1" second)))
    (let [other (canvas/add-person linked)]
      (is (thrown? Exception (canvas/connect-nodes other (:selected other) second))))
    (let [staff (roles/new-role "art" #{})
          assigned (canvas/update-node linked person #(roles/assign-staff % staff))]
      (is (= staff (:staff (workbench/task-context {:canvas assigned} second [staff])))))))

;; Old turns and repeated completion events never release the next task early.
(deftest sequential-turn-status-test
  (let [started (tasks/claim-next (tasks/start {:sessions {}} "p" ["a" "b"]) "p")
        running (assoc-in started [:sessions "a" :execution] {:status "running" :turn-id "turn-a"})
        old (tasks/accept-snapshot running "a" {:thread {:turns [{:id "old" :status "completed"}]}})
        waiting (tasks/accept-snapshot running "a" {:questions [{:id "q"}] :thread {:turns [{:id "turn-a" :status "inProgress"}]}})
        complete {:thread {:turns [{:id "turn-a" :status "completed"}]}}
        completed (tasks/accept-snapshot running "a" complete)
        repeated (tasks/accept-snapshot completed "a" complete)
        second (tasks/claim-next repeated "p")]
    (is (= "a" (get-in started [:sessions "p" :queue :current])))
    (is (= started (tasks/claim-next started "p")))
    (is (= running old))
    (is (= "waiting" (tasks/status waiting "a")))
    (is (= "a" (get-in waiting [:sessions "p" :queue :current])))
    (is (= ["a"] (get-in repeated [:sessions "p" :queue :completed])))
    (is (= "b" (get-in second [:sessions "p" :queue :current])))
    (is (= ["a"] (get-in second [:sessions "p" :queue :completed])))))

;; Failure pauses; retry retains order and reopening never auto-runs.
(deftest queue-recovery-test
  (let [state (-> (tasks/start {:sessions {}} "p" ["a" "b"]) (tasks/claim-next "p")
                  (assoc-in [:sessions "a" :execution] {:status "running" :turn-id "t"}))
        failed (tasks/accept-snapshot state "a" {:thread {:turns [{:id "t" :status "failed"}]}})
        resumed (tasks/resume failed "p")
        recovered (tasks/recover state)]
    (is (= "paused" (get-in failed [:sessions "p" :queue :status])))
    (is (= failed (tasks/claim-next failed "p")))
    (is (= ["a" "b"] (get-in resumed [:sessions "p" :queue :remaining])))
    (is (= "a" (get-in (tasks/claim-next resumed "p") [:sessions "p" :queue :current])))
    (is (= "unknown" (tasks/status recovered "a")))
    (is (= "paused" (get-in recovered [:sessions "p" :queue :status])))
    (is (thrown? Exception (tasks/resume recovered "p")))))

;; A paused final task can finish, but Resume must not resurrect an empty queue.
(deftest finished-queue-resume-test
  (let [running (-> (tasks/start {:sessions {}} "p" ["a"])
                    (tasks/claim-next "p")
                    (assoc-in [:sessions "a" :execution] {:status "running" :turn-id "t"}))
        completed (tasks/accept-snapshot (tasks/pause running "p") "a"
                                         {:thread {:turns [{:id "t" :status "completed"}]}})]
    (is (= "completed" (get-in (tasks/resume completed "p") [:sessions "p" :queue :status])))
    (is (= completed (tasks/pause completed "p")))
    (is (not (tasks/locked? completed)))))

;; Recovery distinguishes unsent requests from unacknowledged journaled ones.
(deftest submission-recovery-test
  (let [state {:sessions {"a" {:execution {:status "submitting"}}
                          "b" {:execution {:status "submitting"} :pending {:body {:requestId "r"}}}}}
        recovered (tasks/recover state)]
    (is (= "failed" (tasks/status recovered "a")))
    (is (= "unknown" (tasks/status recovered "b")))
    (is (= "r" (get-in recovered [:sessions "b" :pending :body :requestId])))))

;; Pausing retains a running task; cancellation only removes unstarted work.
(deftest queue-pause-cancel-test
  (let [running (-> (tasks/start {:sessions {}} "p" ["a" "b"]) (tasks/claim-next "p")
                    (assoc-in [:sessions "a" :execution] {:status "running" :turn-id "t"}))
        paused (tasks/pause running "p")
        completed (tasks/accept-snapshot paused "a" {:thread {:turns [{:id "t" :status "completed"}]}})
        cancelled (tasks/cancel running "p")]
    (is (= "paused" (get-in completed [:sessions "p" :queue :status])))
    (is (= completed (tasks/claim-next completed "p")))
    (is (= "b" (get-in (tasks/claim-next (tasks/resume completed "p") "p") [:sessions "p" :queue :current])))
    (is (= "running" (tasks/status cancelled "a")))
    (is (= "idle" (tasks/status cancelled "b")))
    (is (tasks/locked? cancelled))
    (is (not (tasks/locked? (tasks/accept-snapshot cancelled "a" {:thread {:turns [{:id "t" :status "completed"}]}}))))))
