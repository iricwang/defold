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

(ns editor.ai-pm-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [editor.ai-canvas :as canvas]
            [editor.ai-document :as document]
            [editor.ai-graph :as graph]
            [editor.ai-pm :as pm]
            [editor.ai-pm-view :as pm-view]
            [editor.ai-project-work :as work]
            [editor.ai-roles :as roles]
            [editor.ai-tasks :as tasks]
            [editor.ai-workbench :as workbench]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [java.nio.file Files]
           [javafx.scene Scene]
           [javafx.scene.control Button TextField]))

(defn fixture []
  (let [staff [(roles/new-role "pm" #{}) (roles/new-role "planning" #{})]
        manager (roles/assign-staff {:id "pm" :kind :pm :title "AI-PM" :prompt "设计战斗结算奖励系统" :x 0.0 :y 0.0} (first staff))
        task {:id "design" :title "结算交互设计" :staff-id (:id (second staff))
              :directory "/ai-workspace/library/06-interface" :template "concept"
              :prompt "设计奖励三选一的状态、交互和异常流程" :deliverables "交互文档，包含空状态、确认和重复请求验收" :depends-on []}
        plan {:version 1 :stages [{:id "design" :title "交互与美术" :tasks [task (assoc task :id "art" :title "结算界面美术" :template "gui" :depends-on ["design"])]}]}
        text (str "```ai-pm-plan\n" (json/write-str plan) "\n```")
        model {:workflow :document :template "ai-pm" :nodes [manager] :edges [] :selected "pm"}
        root (.toFile (Files/createTempDirectory "ai-pm-test-" (make-array java.nio.file.attribute.FileAttribute 0)))]
    {:staff staff :plan plan :text text :model model :root root}))

;; Ownership and prerequisites use separate slots even when dependency is wired
;; first; save/reopen does not duplicate or resurrect disconnected legacy edges.
(deftest prerequisite-ownership-and-migration-test
  (let [{:keys [staff model text]} (fixture)
        planned (pm/accept-plan model "pm" "t" text staff)
        [a b] (subvec (:nodes planned) 1)
        next (canvas/add-card planned :task 700.0 0.0 {:staff (second staff) :from (:id a)})
        id (:selected next)
        connected (-> next (graph/connect staff (:id a) id) (graph/connect staff "pm" id))
        legacy (update planned :nodes #(mapv (fn old-task [node] (dissoc node :dependency-wires)) %))
        migrated (graph/migrate-prerequisites legacy)
        edge (first (filterv #(graph/dependency-edge? migrated %) (:edges migrated)))
        disconnected (canvas/disconnect-edge migrated edge)]
    (is (nil? (graph/task-error connected staff id)))
    (is (= [(:id a)] (graph/prerequisite-ids connected id)))
    (is (= (count (:edges planned)) (count (:edges migrated))))
    (is (= migrated (graph/migrate-prerequisites migrated)))
    (is (= [] (graph/prerequisite-ids disconnected (:id b))))
    (is (= disconnected (graph/migrate-prerequisites disconnected)))))

;; Confirmed allocation pins normalized wires and cannot silently reuse a work
;; file after a prerequisite changes, including an externally edited document.
(deftest allocation-dependency-snapshot-test
  (let [{:keys [staff model text root]} (fixture)
        planned (pm/accept-plan model "pm" "t" text staff)
        [a b] (subvec (:nodes planned) 1)
        edge (first (filterv #(graph/dependency-edge? planned %) (:edges planned)))
        independent (canvas/disconnect-edge planned edge)
        assigned (pm/allocate! root "/pm.aicanvas" independent (:id b) staff)
        path (:allocated-path (graph/node assigned (:id b)))
        file (io/file root (subs path 1)) before (slurp file)
        both (pm/allocate! root "/pm.aicanvas" assigned (:id a) staff)
        forged (update both :edges conj edge)]
    (is (= [] (:allocated-dependencies (graph/node assigned (:id b)))))
    (is (string? (:allocated-canvas-id (graph/node assigned (:id b)))))
    (is (thrown? Exception (graph/connect both staff (:id a) (:id b))))
    (is (thrown? Exception (pm/allocate! root "/pm.aicanvas" forged (:id b) staff)))
    (is (= before (slurp file)))
    (is (nil? (get-in (json/read-str before :key-fn keyword) [:nodes 1 :dependencies 0])))))

;; Structured planning creates only review cards; complete own-turn replies are
;; deduplicated, partial/other-turn output cannot dispatch or replace a plan.
(deftest planning-review-boundary-test
  (let [{:keys [staff text model root]} (fixture)
        state {:canvas model :staff staff :sessions {"pm" {:execution {:turn-id "t" :status "running"}}}}
        snapshot {:thread {:turns [{:id "t" :status "completed" :items [{:type "agentMessage" :text text}]}]}}
        accepted (pm/accept-snapshot state "pm" snapshot)
        cards (filterv #(= :task (:kind %)) (get-in accepted [:canvas :nodes]))]
    (is (= 2 (count cards)))
    (is (= ["review" "review"] (mapv :status cards)))
    (is (zero? (count (.listFiles root))))
    (is (= accepted (pm/accept-snapshot accepted "pm" snapshot)))
    (is (= state (pm/accept-snapshot state "pm" (assoc-in snapshot [:thread :turns 0 :status] "inProgress"))))
    (is (= state (pm/accept-snapshot state "pm" (assoc-in snapshot [:thread :turns 0 :id] "other"))))
    (is (= "failed" (get-in (pm/accept-snapshot state "pm" (assoc-in snapshot [:thread :turns 0 :items 0 :text] "invalid")) [:sessions "pm" :execution :status])))
    (is (= "pm" (tasks/person-id model "pm")))
    (is (= ["pm"] (tasks/action-ids model "pm")))
    (is (nil? (graph/task-error model staff "pm")))
    (is (= (:title (first staff)) (get-in (workbench/task-context state "pm" staff) [:staff :title])))
    (is (string/includes? (workbench/message-text "目标" (workbench/task-context state "pm" staff)) "ai-pm-plan"))))

;; Approval materializes a portable Person→Action Canvas once, preserves
;; later human edits. Dependencies can be allocated early and retain stable IDs;
;; legacy task registration recovers identities without inventing execution or
;; approval history.
(deftest allocate-approved-work-test
  (let [{:keys [root model staff text]} (fixture)
        planned (pm/accept-plan model "pm" "t" text staff)
        [design art] (filterv #(= :task (:kind %)) (:nodes planned))
        early (pm/allocate! root "/pm.aicanvas" planned (:id art) staff)
        assigned (pm/allocate! root "/pm.aicanvas" planned (:id design) staff)
        path (:allocated-path (graph/node assigned (:id design)))
        doc (document/read-document root path)
        child (document/model doc nil)
        action (second (:nodes child))
        edited (canvas/update-node child (:id action) #(assoc % :prompt "人工调整后的工作"))
        saved (document/save-document! root path doc edited)
        replayed (pm/allocate! root "/pm.aicanvas" planned (:id design) staff)
        assigned-all (pm/allocate! root "/pm.aicanvas" assigned (:id art) staff)
        next-doc (document/read-document root (:allocated-path (graph/node assigned-all (:id art))))]
    (is (= ["person" "action"] (mapv :kind (:nodes doc))))
    (is (= (:id (second staff)) (:staff-id (first (:nodes doc)))))
    (is (nil? (graph/task-error child staff (:id action))))
    (is (string/includes? (:prompt action) (:deliverables design)))
    (is (= saved (document/read-document root path)))
    (is (= path (:allocated-path (graph/node replayed (:id design)))))
    (is (= "gui" (:template next-doc)))
    (is (= "allocated" (:status (graph/node early (:id art)))))
    (is (= [(:id design)] (:required-task-ids (second (:nodes next-doc)))))
    (is (thrown? Exception (pm/edit-task assigned (:id design) {:prompt "改动"} staff)))
    (is (thrown? Exception (pm/allocate! root "/pm.aicanvas" planned (:id design) [(first staff) (assoc (second staff) :enabled false)])))
    (is (= "allocated" (:status (graph/node assigned-all (:id art)))))
    (Files/deleteIfExists (.toPath (io/file root "ai-workspace/project/work.json")))
    (pm/register-allocated! root "/pm.aicanvas" assigned-all)
    (is (= 2 (count (:tasks (work/read-work root)))))
    (is (= [] (:jobs (work/read-work root))))
    (is (= [] (:approvals (work/read-work root))))))

;; Bad paths, missing staff, duplicate IDs and cyclic dependencies never produce
;; TaskCards or files; reruns preserve confirmed work and supersede old drafts.
(deftest planning-validation-and-revision-test
  (let [{:keys [root model staff text plan]} (fixture)
        render (fn plan-text [plan] (str "```ai-pm-plan\n" (json/write-str plan) "\n```"))
        planned (pm/accept-plan model "pm" "t" text staff)
        [a b] (subvec (:nodes planned) 1)
        allocated (pm/allocate! root "/pm.aicanvas" planned (:id a) staff)
        replanned (pm/accept-plan allocated "pm" "t2" text staff)]
    (doseq [[key value] [[:directory "/../escape"] [:directory "/ai-workspace/library/06-interface/../config"]
                         [:directory "/game"] [:staff-id "missing"] [:depends-on ["absent"]] [:template "ai-pm"]]]
      (is (thrown? Exception (pm/parse-plan (render (assoc-in plan [:stages 0 :tasks 0 key] value)) staff))))
    (is (thrown? Exception (pm/parse-plan (render (assoc-in plan [:stages 0 :tasks 0 :depends-on] ["art"])) staff)))
    (is (thrown? Exception (pm/parse-plan (render (assoc-in plan [:stages 0 :tasks 1 :id] "design")) staff)))
    (is (thrown? Exception (pm/edit-task planned (:id a) {:depends-on [(:id b)]} staff)))
    (is (= "allocated" (:status (graph/node replanned (:id a)))))
    (is (= "superseded" (:status (graph/node replanned (:id b)))))
    (is (thrown? Exception (pm/allocate! root "/pm.aicanvas" replanned (:id b) staff)))
    (is (thrown? Exception (canvas/disconnect-edge planned (first (:edges planned)))))
    (is (string? (graph/task-error (canvas/update-node planned "pm" #(assoc % :prompt "新目标")) staff (:id a))))))

;; A PM template survives save/reopen; native TaskCard details preserve drafts
;; and expose separate save/approval controls instead of executing on selection.
(deftest pm-document-and-native-ui-test
  (let [{:keys [root model staff text]} (fixture)
        _ (.mkdirs (io/file root "ai-workspace/library/01-concept"))
        path (document/create-document! root "ai-workspace/library/01-concept" "项目统筹" "ai-pm")
        doc (document/read-document root path)
        planned (pm/accept-plan model "pm" "t" text staff)
        task (second (:nodes planned))
        state (atom {:canvas (assoc planned :selected (:id task)) :staff staff :project-root root :sessions {}})
        commands (atom [])]
    (is (= ["pm"] (mapv :kind (:nodes doc))))
    (is (= "AI-PM" (:title (first (:nodes doc)))))
    (document/save-document! root path doc planned)
    (is (= [:pm :task :task] (mapv :kind (:nodes (document/model (document/read-document root path) nil)))))
    (ui/run-now
      (let [view (pm-view/details state task (constantly staff) (fn assign [_ _]) (fn report [_])
                                  (fn work [op id fields] (swap! commands conj [op id fields])))
            _ (Scene. view)
            title ^TextField (.lookup view "#pm-field-title")]
        (.setText title "人工修改的任务")
        (.fire ^Button (.lookup view "#pm-save"))
        (is (= "人工修改的任务" (get-in @state [:pm-drafts (:id task) :title])))
        (is (= :save-pm-task (ffirst @commands)))
        (is (= "人工修改的任务" (:title (nth (first @commands) 2))))
        (is (not (.isDisabled ^Button (.lookup view "#pm-confirm"))))
        (is (= 1 (count @commands)))))))

;; Prerequisites are TaskCard wires: the details list them read-only instead of
;; the old picker, while save/reject/confirm remain grouped at the bottom.
(deftest task-dependencies-details-test
  (let [{:keys [staff text model root]} (fixture)
        planned (pm/accept-plan model "pm" "t" text staff)
        art (first (filterv #(= "结算界面美术" (:title %)) (:nodes planned)))
        design (first (filterv #(= "结算交互设计" (:title %)) (:nodes planned)))
        state (atom {:canvas planned :staff staff :project-root root :sessions {}})]
    (is (some? (first (filterv #(and (= (:from %) (:id design)) (= (:to %) (:id art))) (:edges planned)))))
    (ui/run-now
      (let [view (pm-view/details state art (constantly staff) (fn [_ _]) (fn [_]) (fn [_ _ _]))
            _ (Scene. view)
            _ (.applyCss view)
            _ (.layout view)
            texts (atom [])]
        (letfn [(collect [^javafx.scene.Node node]
                  (when (instance? javafx.scene.control.Label node)
                    (swap! texts conj (.getText ^javafx.scene.control.Label node)))
                  (when (instance? javafx.scene.Parent node)
                    (doseq [child (.getChildrenUnmodifiable ^javafx.scene.Parent node)]
                      (collect child))))]
          (collect view))
        (is (nil? (.lookup view "#pm-dependencies")))
        (is (some? (.lookup view "#pm-save")))
        (is (some? (.lookup view "#pm-confirm")))
        (is (some? (.lookup view "#pm-reject")))
        (is (coll/some #(re-find #"结算交互设计" %) @texts))))))
