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

(ns editor.ai-publication-test
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [editor.ai-publication :as publication]
            [editor.ai-resource-import :as admission]
            [editor.ai-resource-import-test :as admission-test]
            [editor.craft-project :as craft]))

;; Static review exposes changes before approval, without invoking a compiler
;; or creating production files; invalid inputs also produce a readable report.
(deftest preflight-before-approval-test
  (let [{:keys [root plan]} (admission-test/fixture)
        path (admission/inspect-plan! root plan)
        text (slurp (craft/resource-file root path) :encoding "UTF-8")]
    (is (string/includes? text "尚未编译或写入工程"))
    (is (string/includes? text (:target (first (:files plan)))))
    (is (not (.exists (io/file root "assets"))))
    (is (not (.exists (io/file root ".internal/ai-resource-import"))))
    (is (= path (publication/matching-report-path root plan)))
    (is (nil? (publication/matching-report-path root (assoc plan :module "changed"))))
    (java.nio.file.Files/delete (.toPath (craft/resource-file root (:source (first (:files plan))))))
    (let [failed (admission/inspect-plan! root plan)]
      (is (not= path failed))
      (is (string/includes? (slurp (craft/resource-file root failed) :encoding "UTF-8") "未通过，不能交付")))))

;; Compilation receives a physical candidate copy, never the live targets.
;; A failed candidate leaves production untouched and retains a failure report.
(deftest candidate-failure-never-writes-production-test
  (let [{:keys [root plan]} (admission-test/fixture)
        called (atom false)]
    (is (thrown-with-msg? Exception #"候选工程编译未通过"
                          (admission/apply! root plan
                                            (fn fail-candidate [output]
                                              (reset! called true)
                                              (is (:candidate output))
                                              (is (not= (.getCanonicalPath root) (.getCanonicalPath ^java.io.File (:project-root output))))
                                              (doseq [entry (:files plan)]
                                                (is (not (.exists (craft/resource-file root (:target entry)))))
                                                (is (.isFile (craft/resource-file (:project-root output) (:target entry)))))
                                              {:status "failed" :compiler "fixture"}))))
    (is @called)
    (is (not (.exists (io/file root "assets"))))
    (let [path (publication/latest-report-path root (:id plan))
          text (slurp (craft/resource-file root path) :encoding "UTF-8")]
      (is (string/includes? text "隔离候选工程编译 | 失败"))
      (is (string/includes? text "文件与引用变化")))))

;; The candidate includes dependency caches and project config but excludes
;; Play output, workbench internals and Git metadata; hashes bind the commit.
(deftest candidate-baseline-and-receipt-test
  (let [{:keys [root plan]} (admission-test/fixture)]
    (doseq [path [".internal/lib/cached.zip" "build/default/keep.txt" ".git/private.txt" "ai-workspace/private.txt"]]
      (let [file (io/file root path)] (io/make-parents file) (spit file path)))
    (let [output (admission/apply! root plan
                                   (fn inspect-candidate [output]
                                     (let [copy (:project-root output)]
                                       (is (.isFile (io/file copy ".internal/lib/cached.zip")))
                                       (is (not (.exists (io/file copy "build/default/keep.txt"))))
                                       (is (not (.exists (io/file copy ".git"))))
                                       (is (not (.exists (io/file copy "ai-workspace")))))
                                     {:status "passed" :compiler "fixture"}))]
      (is (= "imported" (:status output)))
      (is (= (publication/baseline-hash (publication/inventory root)) (get-in output [:validation :final-baseline-hash])))
      (is (= "build/default/keep.txt" (slurp (io/file root "build/default/keep.txt"))))
      (is (string/includes? (slurp (craft/resource-file root (:publication-report output)) :encoding "UTF-8") "等待运行与人工验收"))
      (is (= (:import-id output) (:import-id (admission/apply! root plan admission-test/passed))))
      (spit (io/file root "game.project") "[display]\nwidth = 65\n")
      (is (thrown-with-msg? Exception #"基线已变化" (admission/apply! root plan admission-test/passed))))))

;; Dependency changes in the live project and edits inside the candidate are
;; detected before production writes; neither is overwritten by rollback.
(deftest candidate-mutation-guards-test
  (doseq [mode [:live :candidate]]
    (let [{:keys [root plan]} (admission-test/fixture)]
      (is (thrown? Exception
                   (admission/apply! root plan
                                     (fn change-during-build [output]
                                       (spit (io/file (if (= :live mode) root (:project-root output)) "game.project") "human edit")
                                       {:status "passed" :compiler "fixture"}))))
      (is (not (.exists (io/file root "assets"))))
      (when (= :live mode) (is (= "human edit" (slurp (io/file root "game.project"))))))))

;; One lock covers candidate validation through commit, so competing plans
;; cannot both validate an old target baseline and overwrite each other.
(deftest candidate-publication-lock-test
  (let [{:keys [root plan]} (admission-test/fixture)
        entered (promise) release (promise)
        first-run (future (admission/apply! root plan
                                            (fn wait-for-competing-plan [_]
                                              (deliver entered true)
                                              (when (= :timeout (deref release 15000 :timeout)) (throw (ex-info "测试未释放入库锁" {})))
                                              {:status "passed" :compiler "fixture"})))]
    (try
      (is (= true (deref entered 15000 :timeout)))
      (is (thrown-with-msg? Exception #"另一个入库事务" (admission/apply! root (assoc plan :id (str (java.util.UUID/randomUUID))) admission-test/passed)))
      (finally (deliver release true)))
    (is (= "imported" (:status (deref first-run 15000 {:status "timeout"}))))))

;; File-level review trims unchanged edges, preserves reference provenance,
;; and reports missing art before any candidate compiler can be called.
(deftest change-report-and-missing-resource-test
  (let [{:keys [root plan]} (admission-test/fixture)
        file (io/file root "example.lua")
        _ (spit file "same\nold\ntail" :encoding "UTF-8")
        change (first (publication/changes root [{:kind "reference" :source "/example.lua" :target "/example.lua" :operation "replace"
                                                  :bytes (.getBytes "same\nnew\ntail" "UTF-8") :hash "after" :targetHash "before"}]))
        called (atom false)]
    (is (= ["- old" "+ new"] (:diff change)))
    (is (= "reference" (:kind change)))
    (java.nio.file.Files/delete (.toPath (craft/resource-file root (:source (first (:files plan))))))
    (is (thrown? Exception (admission/apply! root plan (fn unexpected-compile [_] (reset! called true) {:status "passed"}))))
    (is (false? @called))
    (is (not (.exists (io/file root "assets"))))
    (is (some? (publication/latest-report-path root (:id plan))))))

;; A write failure after successful candidate compilation still uses the
;; original journal recovery, while preserving files modified by other writers.
(deftest commit-failure-recovery-test
  (doseq [external-edit [false true]]
    (let [{:keys [root plan]} (admission-test/fixture)
          target (craft/resource-file root (get-in plan [:files 0 :target]))
          second-target (craft/resource-file root (get-in plan [:files 1 :target]))
          original @#'admission/write-atomic!
          compiled (atom false)]
      ;; Inject a deterministic disk failure at the I/O boundary; the real
      ;; candidate preparation, journal, writes and rollback remain in use.
      #_{:clj-kondo/ignore [:discouraged-var]}
      (with-redefs-fn {#'admission/write-atomic!
                       (fn fail-second-write [file bytes]
                         (if (= (.getCanonicalPath second-target) (.getCanonicalPath ^java.io.File file))
                           (do
                             (when external-edit (spit target "human change after first write"))
                             (throw (ex-info "simulated commit failure" {})))
                           (original file bytes)))}
        (fn attempt-publication []
          (is (thrown-with-msg? Exception #"simulated commit failure"
                                (admission/apply! root plan (fn compiled-candidate [_] (reset! compiled true) {:status "passed"}))))))
      (is @compiled)
      (is (not (.exists second-target)))
      (if external-edit
        (is (= "human change after first write" (slurp target)))
        (is (not (.exists target)))))))
