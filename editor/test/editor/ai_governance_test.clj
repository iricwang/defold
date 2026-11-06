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

(ns editor.ai-governance-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [editor.ai-backup :as backup]
            [editor.ai-document :as document]
            [editor.ai-file-ops :as files]
            [editor.ai-groups :as groups]
            [editor.ai-project-work :as work]
            [editor.ai-roles :as roles]
            [editor.ai-templates :as templates]
            [util.coll :as coll])
  (:import [java.nio.file Files]))

(defn fixture [] (.toFile (Files/createTempDirectory "ai-governance-" (make-array java.nio.file.attribute.FileAttribute 0))))
(defn put! [root path text] (files/write! (files/file root path) (.getBytes text "UTF-8")))
(def source "/ai-workspace/library/01-concept/source.md")
(def destination "/ai-workspace/library/02-world/moved.md")
(def reference "/ai-workspace/library/01-concept/index.md")

;; Moving a document repairs relative inline/reference links and its own links;
;; replay does not duplicate writes, and recovery restores references.
(deftest reference-move-recovery-test
  (let [root (fixture)]
    (put! root source "[index](index.md)")
    (put! root reference "[inline](source.md#rules)\n[ref]: source.md \"title\"")
    (let [plan (files/move-plan root source destination false)
          result (files/apply! root plan)]
      (is (not (.exists (files/file root source))))
      (is (string/includes? (slurp (files/file root reference)) "../02-world/moved.md#rules"))
      (is (string/includes? (slurp (files/file root destination)) "../01-concept/index.md"))
      (is (= result (files/apply! root plan)))
      (is (thrown? Exception (files/apply! root (assoc-in plan [:writes 0 :after] "changed"))))
      (files/apply! root (files/restore-file-plan root (:id result)))
      (is (.isFile (files/file root source)))
      (is (string/includes? (slurp (files/file root reference)) "source.md#rules")))))

;; Referenced deletion and frozen-history mutation are refused; unreferenced
;; deletion remains recoverable without overwriting a later human file.
(deftest protected-history-and-trash-test
  (let [root (fixture)]
    (put! root source "document") (put! root reference "[ref]: source.md")
    (is (thrown? Exception (files/move-plan root source nil true)))
    (put! root reference "no reference")
    (work/transact! root #(assoc % :tasks [{:id "task" :source-path source}]))
    (is (thrown? Exception (files/move-plan root source destination false)))
    (work/transact! root #(assoc % :tasks []))
    (let [result (files/apply! root (files/move-plan root source nil true))]
      (is (not (.exists (files/file root source))))
      (put! root source "new human version")
      (is (thrown? Exception (files/restore-file-plan root (:id result))))
      (files/write! (files/file root source) nil)
      (files/apply! root (files/restore-file-plan root (:id result)))
      (is (= "document" (slurp (files/file root source)))))))

;; Markdown image, HTML and encoded links follow moved files without changing
;; remote URLs or fragment-only navigation.
(deftest markdown-link-forms-test
  (let [root (fixture) path "/ai-workspace/library/01-concept/a b(1).png"
        text "![preview](<a b(1).png>)\n<img src=\"a%20b(1).png\">\n[x]: <a b(1).png>\n[web](https://example.com/a.png)\n[section](#top)"
        changed (files/remap-markdown root reference reference text {path "/ai-workspace/library/02-world/image.png"})]
    (is (= 3 (count (re-seq #"\.\./02-world/image.png" changed))))
    (is (string/includes? changed "src=\"../02-world/image.png\""))
    (is (string/includes? changed "https://example.com/a.png"))
    (is (string/includes? changed "(#top)"))))

;; A stale preview or running task cannot change files. Mid-transaction failure
;; restores its own writes and keeps the original source and references intact.
(deftest stale-preview-and-failure-rollback-test
  (let [root (fixture)]
    (put! root source "original")
    (let [plan (files/move-plan root source destination false)]
      (put! root reference "new reference")
      (is (thrown? Exception (files/apply! root plan)))
      (is (= "original" (slurp (files/file root source)))))
    (work/transact! root #(assoc % :jobs [{:id "job" :status "running"}]))
    (is (thrown? Exception (files/apply! root (files/move-plan root source destination false))))
    (work/transact! root #(assoc % :jobs []))
    (put! root "/blocked" "a real file prevents creating its child directory")
    (let [move (files/move-plan root source destination false)
          plan (files/plan root "failing-write" (conj (:writes move) {:path "/blocked/child.txt" :bytes (.getBytes "cannot write" "UTF-8")}) {})]
      (is (thrown? Exception (files/apply! root plan)))
      (is (= "original" (slurp (files/file root source))))
      (is (not (.exists (files/file root destination))))
      (is (= "rolled-back" (:status (first (files/history root)))))
      (is (= "committed" (:status (files/apply! root (files/move-plan root source destination false))))))))

;; Path traversal and hidden backup payloads cannot escape the management scope.
(deftest path-boundary-test
  (let [root (fixture)]
    (doseq [path ["/../outside" "/x/../y" "C:/outside" "/x:stream" "/x\\y" "/x./y" "/x//y"]]
      (is (thrown? Exception (files/file root path))))
    (put! root source "original")
    (is (thrown? Exception (files/move-plan root source "/game.project" false)))
    (is (thrown? Exception (backup/create! root root)))))

(defn populated-project []
  (let [root (fixture)
        role {:id "designer" :kind "role" :title "系统策划" :role-type "planning" :assignee "程思源" :model "Sol" :effort "高"
              :responsibilities "设计交互" :deliverables "设计文档" :enabled true :thread-id "old-thread"}
        doc {:format "codexcamp.ai-canvas" :schemaVersion 1 :id "canvas_old" :revision 8 :template "concept" :title "系统设计"
             :nodes [{:id "person" :kind "person" :title "策划" :staff-id "designer" :thread-id "old-chat"}
                     {:id "action" :kind "action" :title "设计任务" :prompt "目标" :status "completed" :job-id "job"}
                     {:id "result" :kind "result" :title "旧结果" :artifact-id "artifact"}]
             :edges [{:from "person" :to "action" :stamp "old"} {:from "action" :to "result"}]}]
    (files/write! (files/file root roles/document-path) (files/json-bytes {:format "codexcamp.ai-canvas" :schemaVersion 1 :id "canvas_roles" :template "project-roles" :revision 1 :nodes [role] :edges []}))
    (files/write! (files/file root "/ai-workspace/library/01-concept/work.aicanvas") (files/json-bytes doc))
    (put! root source "[design](work.aicanvas)")
    (put! root "/ai-workspace/artifacts/fixed.txt" "fixed evidence")
    (work/transact! root #(assoc % :jobs [{:id "waiting" :status "queued"}]
                                 :approvals [{:id "human-approved"}]
                                 :artifacts [{:id "artifact" :files [{:path "/ai-workspace/artifacts/fixed.txt" :sha256 (files/hash-file (files/file root "/ai-workspace/artifacts/fixed.txt"))}]}]))
    root))

;; Imported templates create destination-specific identities and strip output,
;; approval and chat state; repeated imports preserve human changes and staff.
(deftest template-portability-and-idempotence-test
  (let [root (populated-project) target (fixture) second-target (fixture)
        package (templates/export-data root)
        plan (templates/import-plan target package false)
        other (templates/import-plan second-target package false)]
    (is (not= (:staff-mapping plan) (:staff-mapping other)))
    (is (not (string/includes? (pr-str package) "old-thread")))
    (files/apply! target plan)
    (is (zero? (count (:writes (templates/import-plan target package false)))))
    (let [doc (document/read-document target (first (:documents plan)))]
      (is (= 2 (count (:nodes doc))))
      (is (= 1 (count (:edges doc))))
      (is (not (string/includes? (pr-str doc) "completed")))
      (is (not (string/includes? (pr-str doc) "old-chat")))
      (is (not= "canvas_old" (:id doc))))
    (put! target (first (:documents plan)) "human changed")
    (is (thrown? Exception (templates/import-plan target package false)))))

;; Backups retain fixed files and human approvals; restore pauses queued work,
;; cross-project migration keeps history separate from the active ledger.
(deftest backup-restore-and-migration-test
  (let [root (populated-project) target (fixture)
        directory (backup/create! root (fixture))
        manifest (backup/verify directory)]
    (is (= [] (:issues manifest)))
    (put! root source "later human edit")
    (files/apply! root (backup/restore-plan root directory))
    (is (= "paused" (:status (first (:jobs (work/read-work root))))))
    (is (= "human-approved" (:id (first (:approvals (work/read-work root))))))
    (is (thrown? Exception (backup/restore-plan target directory)))
    (let [plan (backup/migrate-plan target directory)]
      (files/apply! target plan)
      (is (= [] (:approvals (work/read-work target))))
      (is (= [] (:jobs (work/read-work target))))
      (is (.isFile (files/file target (str (:history-root plan) work/path))))
      (is (= "human-approved" (:id (first (:approvals (files/read-json (files/file target (str (:history-root plan) work/path))))))))
      (is (zero? (count (:writes (backup/migrate-plan target directory)))))
      (is (= [] (:issues (first (backup/verify-migrations target)))))
      (files/write! (files/file target (str (:history-root plan) "/ai-workspace/artifacts/fixed.txt")) nil)
      (is (= 1 (count (:issues (first (backup/verify-migrations target)))))))
    (let [blob (files/file directory (str "/files/" (:sha256 (first (:files manifest)))))]
      (files/write! blob (.getBytes "tampered" "UTF-8"))
      (is (pos? (count (:issues (backup/verify directory)))))
      (is (thrown? Exception (backup/migrate-plan (fixture) directory))))))

;; Interrupted transactions can be recovered after reopening, but later human
;; edits are never overwritten by the recovery plan.
(deftest interrupted-recovery-conflict-test
  (let [root (fixture)]
    (put! root source "original")
    (let [record (files/apply! root (files/move-plan root source destination false))
          journal (files/file root (str "/.internal/ai-governance/transactions/" (:id record) "/journal.json"))]
      (files/write! journal (files/json-bytes (assoc record :status "applying")))
      (put! root destination "human edit")
      (is (thrown? Exception (files/restore-file-plan root (:id record))))
      (is (= "human edit" (slurp (files/file root destination))))
      (put! root destination "original")
      (files/apply! root (files/restore-file-plan root (:id record)))
      (is (= "original" (slurp (files/file root source))))
      (is (not (.exists (files/file root destination)))))))

;; Conflicting role defaults create a separate definition and never mutate
;; the receiving project's existing staff or rules.
(deftest role-conflict-and-task-template-test
  (let [root (populated-project) target (fixture)
        package (-> (templates/export-data root)
                    (assoc-in [:role-types :roles 0 :defaults :responsibilities] "新的职责"))
        original (roles/default-config)
        plan (templates/import-plan target package false)]
    (is (not= "planning" (get-in plan [:role-mapping "planning"])))
    (files/apply! target plan)
    (is (= (first (:roles original)) (first (:roles (files/read-json (files/file target roles/config-path))))))
    (let [doc (templates/sanitize-document {:format "codexcamp.ai-canvas" :schemaVersion 1 :id "canvas_old" :template "ai-pm" :title "规划"
                                            :nodes [{:id "pm" :kind "pm" :title "AI-PM" :staff-id roles/ai-pm-id}
                                                    {:id "task" :kind "task" :title "任务" :source-pm "pm" :status "accepted" :allocated-path "/old.aicanvas" :approved-at "old" :depends-on []}]
                                            :edges [{:from "pm" :to "task"}]} {"canvas_old" "canvas_new" "pm" "pm-new" "task" "task-new"} {roles/ai-pm-id roles/ai-pm-id})]
      (is (= "review" (:status (second (:nodes doc)))))
      (is (= "pm-new" (:source-pm (second (:nodes doc)))))
      (is (nil? (:allocated-path (second (:nodes doc)))))
      (is (nil? (:approved-at (second (:nodes doc))))))))

;; A valid manifest checksum cannot authorize hidden project internals, and
;; changing the source package after preview prevents any destination writes.
(deftest backup-input-and-path-tamper-test
  (let [root (populated-project) target (fixture) directory (backup/create! root (fixture))
        manifest-file (files/file directory "/manifest.json") manifest (files/read-json manifest-file)
        plan (backup/migrate-plan target directory)]
    (files/write! manifest-file (files/json-bytes (assoc manifest :extra "changed")))
    (is (thrown? Exception (files/apply! target plan)))
    (is (zero? (count (files/inventory target))))
    (let [bad (assoc-in manifest [:files 0 :path] "/.git/config")]
      (files/write! manifest-file (files/json-bytes (assoc bad :checksum (#'backup/checksum bad))))
      (is (thrown? Exception (backup/verify directory))))))

;; Archived editor scripts and native-extension manifests stay inert in the
;; receiving project, including copies from creative-library folders.
(deftest migration-inert-history-test
  (let [root (populated-project) target (fixture)]
    (put! root "/hooks.editor_script" "return {}")
    (put! root "/extension/ext.manifest" "name: legacy")
    (put! root "/ai-workspace/library/01-concept/tool.editor_script" "return {}")
    (let [plan (backup/migrate-plan target (backup/create! root (fixture)))
          paths (mapv :path (:writes plan))]
      (is (coll/not-any? #(re-find #"(\.editor_script|/ext\.manifest)$" %) paths))
      (is (= 4 (count (filterv #(string/ends-with? % ".history.txt") paths))))
      (files/apply! target plan)
      (is (= [] (:issues (first (backup/verify-migrations target))))))))

;; Missing frozen files prevent producing a misleading complete backup.
(deftest missing-artifact-blocks-backup-test
  (let [root (populated-project)]
    (files/write! (files/file root "/ai-workspace/artifacts/fixed.txt") nil)
    (is (thrown? Exception (backup/create! root (fixture))))))

;; Folding is a presentation filter: queue nodes and immutable result links in
;; the source graph remain unchanged, and ungrouped cards stay reachable.
(deftest grouping-presentation-only-test
  (let [model {:nodes [{:id "p" :kind :person :title "林序"} {:id "a" :kind :action :title "任务"}
                       {:id "r" :kind :result :title "成果"} {:id "other" :kind :action :title "独立任务"}]
               :edges [{:from "p" :to "a"} {:from "a" :to "r"}]}
        folded (groups/visible-model model :staff #{"p"})]
    (is (= ["p" "other"] (mapv :id (:nodes folded))))
    (is (= [] (:edges folded)))
    (is (= 4 (count (:nodes model))))
    (is (= model (groups/visible-model model :staff #{})))
    (is (= #{"a" "r"} (:members (first (groups/groups model :task)))))))
