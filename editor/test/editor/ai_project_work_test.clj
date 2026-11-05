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

(ns editor.ai-project-work-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [editor.ai-canvas :as canvas]
            [editor.ai-graph :as graph]
            [editor.ai-project-work :as work]
            [editor.ai-roles :as roles]
            [editor.ai-tasks :as tasks]
            [editor.ui :as ui])
  (:import [java.nio.file Files]
           [javafx.scene Scene]
           [javafx.scene.control Button]))

(defn fixture []
  (.toFile (Files/createTempDirectory "project-work-" (make-array java.nio.file.attribute.FileAttribute 0))))

;; A staff queue spans Canvas views and admits one claim, while other staff can
;; work independently; native completion releases closed-view work as well.
(deftest project-staff-serialization-test
  (let [root (fixture)
        [a b c] (work/enqueue! root [{:staff-id "designer" :canvas-path "/a.aicanvas" :action-id "a"}
                                     {:staff-id "designer" :canvas-path "/b.aicanvas" :action-id "b"}
                                     {:staff-id "artist" :canvas-path "/c.aicanvas" :action-id "c"}])]
    (is (false? (work/claim! root (:id b))))
    (is (true? (work/claim! root (:id a))))
    (is (false? (work/claim! root (:id a))))
    (is (false? (work/claim! root (:id b))))
    (is (true? (work/claim! root (:id c))))
    (work/update-jobs! root #{(:id a)} {:thread-id "thread" :turn-id "turn"})
    (work/observe-event! root {:method "turn/completed" :params {:threadId "other" :turn {:id "turn" :status "completed"}}})
    (is (false? (work/claim! root (:id b))))
    (work/observe-event! root {:method "turn/completed" :params {:threadId "thread" :turn {:id "turn" :status "completed"}}})
    (is (true? (work/claim! root (:id b))))
    (is (= 2 (count (work/jobs-for (work/read-work root) "designer"))))))

;; When every view was closed during completion, reconnection checks the exact
;; native turn before allowing the employee's next Canvas to acquire its slot.
(deftest missed-native-completion-test
  (let [root (fixture)
        [a b] (work/enqueue! root [{:staff-id "s"} {:staff-id "s"}])
        calls (atom [])]
    (work/claim! root (:id a))
    (work/update-jobs! root #{(:id a)} {:thread-id "thread" :turn-id "turn"})
    (work/reconcile-native! root (fn wrong-turn [id] (swap! calls conj id) {:thread {:turns [{:id "other" :status "completed"}]}}))
    (is (false? (work/claim! root (:id b))))
    (work/reconcile-native! root (fn matching-turn [id] (swap! calls conj id) {:thread {:turns [{:id "turn" :status "completed"}]}}))
    (is (true? (work/claim! root (:id b))))
    (is (= ["thread" "thread"] @calls))))

;; Recovery preserves unknown requests and pauses unstarted work. Only the same
;; durable request may retry; cancellation cannot release an active remote run.
(deftest recovery-and-unknown-request-test
  (let [root (fixture)
        [a b] (work/enqueue! root [{:staff-id "s"} {:staff-id "s"}])]
    (work/claim! root (:id a))
    (work/update-jobs! root #{(:id a)} {:request-id "same"})
    (work/initialize! root)
    (is (= ["unknown" "paused"] (mapv :status (:jobs (work/read-work root)))))
    (work/control! root #{(:id b)} :resume)
    (is (false? (work/claim! root (:id b))))
    (work/retry-pending! root (:id a) "different")
    (is (false? (work/claim! root (:id a))))
    (work/retry-pending! root (:id a) "same")
    (is (true? (work/claim! root (:id a))))
    (work/control! root #{(:id a) (:id b)} :cancel)
    (is (= ["running" "cancelled"] (mapv :status (:jobs (work/read-work root)))))
    (work/initialize! root)
    (is (= "running" (:status (first (:jobs (work/read-work root))))))))

;; Queue registration survives repeated pumps, pins staff/spec snapshots, and
;; a model turn ending does not claim that its business Task passed acceptance.
(deftest queue-snapshot-and-task-state-test
  (let [root (fixture)
        state {:canvas {:nodes [{:id "p" :kind :person :staff-id "s"}
                                {:id "a" :kind :action :title "工作" :prompt "约定"}]}
               :context {:path "/work.aicanvas"} :staff [{:id "s" :model "Sol"}]
               :task-origin {:task "task"} :sessions {}}
        state (tasks/start state "p" ["a"])
        prepared (work/prepare-queue! root state "p")
        job-id (get-in prepared [:sessions "a" :project-job-id])]
    (is (= prepared (work/prepare-queue! root prepared "p")))
    (is (= 1 (count (:jobs (work/read-work root)))))
    (is (= "Sol" (get-in (work/read-work root) [:jobs 0 :staff-snapshot :model])))
    (is (= "约定" (get-in (work/read-work root) [:jobs 0 :spec-snapshot :prompt])))
    (is (= "待执行" (work/task-status (work/read-work root) "task")))
    (work/claim! root job-id)
    (work/record-execution! root (assoc-in prepared [:sessions "a" :execution] {:status "completed" :turn-id "turn"}) "a")
    (is (= "待验收" (work/task-status (work/read-work root) "task")))
    (is (= [] (:approvals (work/read-work root))))))

;; A failed first action's new attempt must precede its already registered next
;; action; stale running snapshots cannot undo an acknowledged terminal status.
(deftest retry-order-and-terminal-snapshot-test
  (let [root (fixture)
        state {:canvas {:nodes [{:id "p" :kind :person :staff-id "s"}
                                {:id "a" :kind :action :title "A"} {:id "b" :kind :action :title "B"}]}
               :context {:path "/work.aicanvas"} :staff [{:id "s"}] :sessions {}}
        queued (work/prepare-queue! root (tasks/start state "p" ["a" "b"]) "p")
        original (get-in queued [:sessions "a" :project-job-id])
        b (get-in queued [:sessions "b" :project-job-id])]
    (work/claim! root original)
    (work/record-execution! root (assoc-in queued [:sessions "a" :execution] {:status "failed"}) "a")
    (let [retry (work/prepare-queue! root queued "p")
          attempt (get-in retry [:sessions "a" :project-job-id])]
      (is (not= attempt original))
      (is (false? (work/claim! root b)))
      (is (true? (work/claim! root attempt)))
      (work/record-execution! root (assoc-in retry [:sessions "a" :execution] {:status "completed"}) "a")
      (work/record-execution! root (assoc-in retry [:sessions "a" :execution] {:status "running"}) "a")
      (is (true? (work/claim! root b)))
      (is (= "completed" (:status (first (filterv #(= attempt (:id %)) (:jobs (work/read-work root))))))))))

;; Moving a work Canvas preserves task identity, updates its locator and refuses
;; ambiguous duplicate identities instead of silently selecting another file.
(deftest moved-canvas-locator-test
  (let [root (fixture)
        original "/ai-workspace/library/01-concept/original.aicanvas"
        moved "/ai-workspace/library/01-concept/moved.aicanvas"
        file (io/file root (subs original 1))
        target (io/file root (subs moved 1))
        payload (json/write-str {:format "codexcamp.ai-canvas" :id "canvas_stable"})]
    (io/make-parents file)
    (spit file payload)
    (work/register-task! root {:id "task" :canvas-id "canvas_stable" :canvas-path original})
    (is (= original (work/resolve-canvas! root "canvas_stable" original)))
    (Files/move (.toPath file) (.toPath target) (make-array java.nio.file.CopyOption 0))
    (is (= moved (work/resolve-canvas! root "canvas_stable" original)))
    (is (= moved (:canvas-path (first (:tasks (work/read-work root))))))
    (spit (io/file (.getParentFile file) "duplicate.aicanvas") payload)
    (is (thrown? Exception (work/resolve-canvas! root "canvas_stable" original)))))

;; Person details show the employee's work from other Canvas views and route
;; each open operation by its project job identity.
(deftest native-project-queue-details-test
  (let [root (fixture) staff [(roles/new-role "planning" #{})]
        person (roles/assign-staff {:id "p" :kind :person :title "负责人"} (first staff))
        model (graph/connect {:nodes [person {:id "a" :kind :action :title "本页工作" :prompt "设计"}] :edges [] :selected "p"} staff "p" "a")
        jobs (work/enqueue! root [{:staff-id (:id (first staff)) :title "本页工作" :canvas-path "/a.aicanvas"}
                                  {:staff-id (:id (first staff)) :title "另一个画布的工作" :canvas-path "/b.aicanvas"}])
        state (atom {:canvas model :staff staff :project-root root :project-work (work/read-work root) :sessions {}})
        commands (atom [])]
    (ui/run-now
      (let [view (canvas/make-details state (fn [_ _]) (fn [_ _]) (fn [_ _ _]) (fn [_ _]) (constantly staff)
                                      (fn [_ _]) (fn [_]) (fn work-command [op id fields] (swap! commands conj [op id fields])))
            pane (:root view) _ (Scene. pane)]
        ((:refresh! view))
        (.applyCss pane) (.layout pane)
        (doseq [job jobs]
          (let [button ^Button (.lookup pane (str "#project-job-" (:id job)))]
            (is (some? button)) (.fire button)))
        (is (= (mapv :id jobs) (mapv second @commands)))
        (is (= [:open-project-job :open-project-job] (mapv first @commands)))))))
