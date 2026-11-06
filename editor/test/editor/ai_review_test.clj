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

(ns editor.ai-review-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [editor.ai-card-view :as card-view]
            [editor.ai-delivery :as delivery]
            [editor.ai-document :as document]
            [editor.ai-project-work :as work]
            [editor.ai-review :as review]
            [editor.ai-review-view :as view]
            [editor.ai-roles :as roles]
            [editor.ai-tasks :as tasks]
            [editor.ai-workspace :as workspace]
            [editor.craft-project :as craft]
            [editor.ui :as ui])
  (:import [java.nio.file Files]
           [javafx.scene Scene]
           [javafx.scene.control Button ComboBox Label ListView MenuItem Tab TabPane TextArea]
           [javafx.scene.layout AnchorPane]
           [javafx.stage Stage]))

(defn fixture []
  (let [root (.toFile (Files/createTempDirectory "ai-review-" (make-array java.nio.file.attribute.FileAttribute 0)))
        source (io/file root "design.md")]
    (spit source "# 设计\n原始正文" :encoding "UTF-8")
    {:root root :record (delivery/snapshot! root {:kind "proposal" :bundle-id "canvas/result" :title "设计"
                                                  :task-id "original" :text "原始方案" :files [{:path "/design.md"}]})}))

;; Editing a frozen Markdown is refused, while a derived version retains the
;; parent's bytes, approval and downstream input identity.
(deftest immutable-draft-test
  (let [{:keys [root record]} (fixture)
        frozen (craft/resource-file root (get-in record [:files 0 :path]))
        original (slurp frozen :encoding "UTF-8")
        _ (delivery/approve! root (:id record) "已批准")
        draft (review/create-draft! root (:id record))
        path (get-in draft [:files 0 :path])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"固定成果" (card-view/save-markdown! root frozen original "破坏")))
    (card-view/save-markdown! root (craft/resource-file root path) original "# 人工修改")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"外部修改" (review/publish-draft! root (:id draft) 1)))
    (review/collect-file! root (:id draft) 1 0)
    (review/save-text! root (:id draft) 2 "新正文")
    (let [next (review/publish-draft! root (:id draft) 3)
          data (work/read-work root)]
      (is (= original (slurp frozen :encoding "UTF-8")))
      (is (= (:id record) (:parent-id next)))
      (is (not= (:id record) (:id next)))
      (is (delivery/approved? data (:id record)))
      (is (not (delivery/approved? data (:id next))))
      (is (= [(:id record)] (:artifacts (delivery/admission data nil {} {:inputs-fixed true :input-snapshot [(:id record)]}))))
      (is (thrown? clojure.lang.ExceptionInfo (delivery/admission data nil {:inputs [{:artifact-id (:id next)}]} nil)))
      (is (= "# 人工修改" (slurp (craft/resource-file root (get-in next [:files 0 :path])) :encoding "UTF-8")))
      (is (= record (delivery/verify-artifact! root record))))))

;; Stale draft revisions and mismatched replacement types cannot silently
;; overwrite another editor's changes.
(deftest draft-conflicts-test
  (let [{:keys [root record]} (fixture)
        draft (review/create-draft! root (:id record))
        other (io/file root "replacement.png")]
    (spit other "not-markdown")
    (review/save-text! root (:id draft) 1 "新版")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"已变化" (review/save-text! root (:id draft) 1 "旧编辑器")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"格式" (review/replace-file! root (:id draft) 2 0 (get-in draft [:files 0 :base-hash]) other)))
    (is (= "新版" (:text (review/draft (work/read-work root) (:id draft)))))))

;; Review comments retain exact version and anchor provenance; a new revision
;; never receives a parent's feedback or approval implicitly.
(deftest anchored-review-test
  (let [{:keys [root record]} (fixture)
        note (review/add-note! root (:id record) {:text "调整描述" :decision "comment" :path "body"
                                                  :anchor {:type "lines" :start 1 :end 1} :checklist ["术语一致"]})
        draft (review/create-draft! root (:id record))]
    (review/save-text! root (:id draft) 1 "新方案")
    (let [next (review/publish-draft! root (:id draft) 2)]
      (is (= (:revision record) (:revision note)))
      (is (not= (:id next) (:artifact-id note)))
      (is (= 1 (count (:review-notes (work/read-work root))))))
    (is (thrown? clojure.lang.ExceptionInfo (review/add-note! root (:id record) {:text "错位" :decision "comment" :path "/other.md"})))
    (is (thrown? clojure.lang.ExceptionInfo (review/add-note! root (:id record) {:text "越界" :decision "comment" :path "body" :anchor {:type "region" :x 0.8 :y 0 :width 0.5 :height 1}})))))

;; Reordering is project-wide, skips other staff and cannot release active or
;; unknown execution; independent work still passes blocked items.
(deftest queue-reordering-test
  (let [{:keys [root]} (fixture)
        [a b c] (work/enqueue! root [{:staff-id "s"} {:staff-id "other"} {:staff-id "s"}])]
    (review/move-job! root (:id c) -1)
    (is (= [(:id c) (:id b) (:id a)] (mapv :id (:jobs (work/read-work root)))))
    (is (= "second" (work/next-ready-action (work/read-work root)
                                            {:sessions {"owner" {:queue {:remaining ["first" "second"]}}
                                                        "first" {:project-job-id (:id a)}
                                                        "second" {:project-job-id (:id c)}}} "owner")))
    (is (work/claim! root (:id c)))
    (is (thrown? clojure.lang.ExceptionInfo (review/move-job! root (:id c) 1)))
    (work/update-jobs! root #{(:id c)} {:status "unknown"})
    (is (false? (work/claim! root (:id a))))
    (work/update-jobs! root #{(:id c)} {:status "blocked"})
    (is (true? (work/claim! root (:id a))))))

;; Human confirmation creates one separate rework Task and stable work Canvas;
;; repeated confirmation never duplicates it or rewrites the original Task.
(deftest confirmed-rework-test
  (let [{:keys [root record]} (fixture)
        staff [(merge (:defaults (first (:roles (roles/default-config))))
                      {:id "designer" :title "设计职员" :role-type "planning" :enabled true})]]
    (work/register-task! root {:id "original" :title "原任务" :staff-id "designer" :deliverables "设计说明" :delivery-type "document"})
    (let [note (review/add-note! root (:id record) {:text "完善边界条件" :decision "changes-requested" :path "body"})
          proposal (review/propose-rework! root (:id note) "designer")]
      (is (= "review" (:status proposal)))
      (is (= 1 (count (:tasks (work/read-work root)))))
      (let [allocated (review/confirm-rework! root (:id proposal) staff)
            child (document/read-document root (:canvas-path allocated))]
        (is (= "allocated" (:status allocated)))
        (is (= (:person-id allocated) (:id (first (:nodes child)))))
        (is (= "designer" (:staff-id (first (:nodes child)))))
        (is (= allocated (review/confirm-rework! root (:id proposal) staff)))
        (is (= 2 (count (:tasks (work/read-work root)))))
        (is (= "原任务" (:title (delivery/task (work/read-work root) "original"))))
        (is (= [] (:jobs (work/read-work root))))
        (let [model (document/model child nil)
              state {:canvas model :staff staff :sessions {} :task-origin (:pm-origin child)
                     :context {:path (:canvas-path allocated) :canvas-id (:id child)}}
              started (tasks/start state (:person-id allocated) (tasks/action-ids model (:person-id allocated)))
              prepared (work/prepare-queue! root started (:person-id allocated))
              _ (work/prepare-queue! root prepared (:person-id allocated))
              jobs (:jobs (work/read-work root))]
          (is (= 1 (count jobs)))
          (is (= (:id proposal) (:task-id (first jobs))))
          (is (= "designer" (:staff-id (first jobs))))
          (is (true? (work/claim! root (:id (first jobs))))))))))

;; Native review editing preserves old versions and exposes the saved derived
;; version; staff/Task views use independent panes without changing overview.
(deftest native-review-workflow-test
  (let [{:keys [root record]} (fixture)]
    (ui/run-now
      (let [owner (doto (Stage.) (.setScene (Scene. (AnchorPane.) 800.0 600.0)))
            window (view/open! owner root (:id record) {:markdown-opts {}})
            stage ^Stage (:stage window) pane (.getRoot (.getScene stage))
            fire! (fn fire-control [id] (.applyCss pane) (.layout pane) (.fire ^Button (.lookup pane id)))]
        (try
          (fire! "#ai-review-derive")
          (.setText ^TextArea (.lookup pane "#ai-review-source") "人工修订正文")
          (is (.isDisabled ^ListView (.lookup pane "#ai-review-files")))
          (fire! "#ai-review-save")
          (is (not (.isDisabled ^ListView (.lookup pane "#ai-review-files"))))
          (fire! "#ai-review-publish")
          (is (= 2 (count (:artifacts (work/read-work root)))))
          (is (= "原始方案" (:text (delivery/artifact (work/read-work root) (:id record)))))
          (fire! "#ai-review-compare")
          (let [comparison ^Stage (first (filterv #(and (instance? Stage %) (= "同一方案 · 历史版本 · 版本对比" (.getTitle ^Stage %))) (vec (javafx.stage.Window/getWindows))))]
            (is (some? comparison))
            (when comparison
              (is (re-find #"同一方案的固定版本" (.getText ^Label (.lookup (.getRoot (.getScene comparison)) "#ai-compare-notice"))))
              (.close comparison)))
          (.setText ^TextArea (.lookup pane "#ai-review-feedback") "版本意见")
          (fire! "#ai-review-note")
          (is (= 1 (count (:review-notes (work/read-work root)))))
          (is (some? (.lookup pane (str "#ai-review-locate-" (:id (first (:review-notes (work/read-work root))))))))
          (let [version (last (:artifacts (work/read-work root)))
                note (review/add-note! root (:id version) {:text "定位正文" :decision "comment" :path "body" :anchor {:type "lines" :start 1 :end 1}})]
            ((:refresh! window))
            (fire! (str "#ai-review-locate-" (:id note)))
            (is (= "人工修订正文" (.getSelectedText ^TextArea (.lookup pane "#ai-review-anchor-source")))))
          (finally (.close stage) (.close owner)))))))

;; Staff and Task tabs share project identity, preserve overview filters on
;; return, and close only their own workspace siblings while keeping home.
(deftest native-staff-task-tabs-test
  (let [{:keys [root]} (fixture)]
    (work/register-task! root {:id "task" :title "交互复核" :staff-id "designer" :deliverables "说明文档"})
    (ui/run-now
      (let [parent (AnchorPane.)
            view (workspace/make-view! parent root {} {} (fn make-content [_ _ _ _] {}))
            tabs ^TabPane (:tabs view)
            home ^Tab (first (.getTabs tabs))
            mode ^ComboBox (.lookup (.getContent home) "#ai-overview-filter")]
        (try
          (.setValue mode "执行中")
          ((:open! view) {:staff-id "designer"})
          (let [staff (ui/selected-tab tabs)]
            ((:open! view) {:task-id "task"})
            (is (= 3 (count (.getTabs tabs))))
            ((:open! view) {:staff-id "designer"})
            (is (identical? staff (ui/selected-tab tabs)))
            (is (= 3 (count (.getTabs tabs))))
            ((:open! view) {:overview true :preserve true})
            (is (identical? home (ui/selected-tab tabs)))
            (is (= "执行中" (.getValue mode)))
            (.fire ^MenuItem (second (.getItems (.getContextMenu ^Tab staff))))
            (is (= [home staff] (vec (.getTabs tabs)))))
          (finally ((:close! view))))))))

;; A draft save checks both its ledger revision and disk contents. Failed
;; saves cannot alter files or publish a second editor's stale candidate.
(deftest draft-markdown-save-conflict-test
  (let [{:keys [root record]} (fixture)
        draft (review/create-draft! root (:id record))
        file (craft/resource-file root (get-in draft [:files 0 :path]))
        original (slurp file :encoding "UTF-8")]
    (review/save-text! root (:id draft) 1 "新正文")
    (is (thrown? clojure.lang.ExceptionInfo (review/save-markdown! root (:id draft) 1 0 original "旧窗口")))
    (is (= original (slurp file :encoding "UTF-8")))
    (spit file "外部编辑" :encoding "UTF-8")
    (is (thrown? clojure.lang.ExceptionInfo (review/save-markdown! root (:id draft) 2 0 original "覆盖外部")))
    (is (= "外部编辑" (slurp file :encoding "UTF-8")))))

;; Freezing rejects empty content without registering a partial artifact or
;; marking its editable draft as submitted.
(deftest publish-invalid-content-transaction-test
  (let [{:keys [root]} (fixture)
        record (delivery/snapshot! root {:kind "proposal" :bundle-id "body-only" :title "正文" :text "父版本" :files []})
        draft (review/create-draft! root (:id record))]
    (review/save-text! root (:id draft) 1 "")
    (is (thrown? clojure.lang.ExceptionInfo (review/publish-draft! root (:id draft) 2)))
    (is (= 2 (count (:artifacts (work/read-work root)))))
    (is (= "editing" (:status (review/draft (work/read-work root) (:id draft)))))
    (is (= record (delivery/verify-artifact! root record)))))

;; Image notes accept normalized regions only on image files and preserve the
;; exact version identity. Text line anchors cannot exceed actual line counts.
(deftest image-and-line-anchor-boundaries-test
  (let [{:keys [root]} (fixture)
        png (io/file root "preview.png")]
    (javax.imageio.ImageIO/write (java.awt.image.BufferedImage. 4 4 java.awt.image.BufferedImage/TYPE_INT_ARGB) "png" png)
    (let [record (delivery/snapshot! root {:kind "asset-bundle" :bundle-id "image" :title "图片" :text "一行" :files [{:path "/preview.png"}]})
          fields {:text "调整边缘" :decision "comment" :path (get-in record [:files 0 :path])
                  :anchor {:type "region" :x 0.2 :y 0.1 :width 0.4 :height 0.3}
                  :checklist [{:text "保留布局" :checked true}]}
          note (review/add-note! root (:id record) fields)]
      (is (= (:anchor fields) (:anchor note)))
      (is (= (:revision record) (:revision note)))
      (is (true? (get-in note [:checklist 0 :checked])))
      (is (thrown? clojure.lang.ExceptionInfo (review/add-note! root (:id record) (assoc fields :path "body"))))
      (is (thrown? clojure.lang.ExceptionInfo (review/add-note! root (:id record) (assoc fields :path "body" :anchor {:type "lines" :start 1 :end 2})))))))
