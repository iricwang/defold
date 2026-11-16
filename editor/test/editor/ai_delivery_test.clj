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

(ns editor.ai-delivery-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [editor.ai-delivery :as delivery]
            [editor.ai-delivery-view :as view]
            [editor.ai-graph :as graph]
            [editor.ai-graph-test :as graph-test]
            [editor.ai-project-work :as work]
            [editor.ai-tasks :as tasks]
            [editor.craft-project :as craft]
            [editor.ui :as ui])
  (:import [java.nio.file Files]
           [javafx.scene Scene]
           [javafx.scene.control Button CheckBox ComboBox TextArea]))

(defn fixture []
  (.toFile (Files/createTempDirectory "ai-delivery-" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn register! [root id type dependencies]
  (work/register-task! root {:id id :title id :spec-revision "spec1" :action-ids ["a"] :delivery-type type :dependencies dependencies}))

(defn completed! [root id]
  (let [job (first (work/enqueue! root [{:staff-id "s" :task-id id :action-id "a" :canvas-id id}]))]
    (work/update-jobs! root #{(:id job)} {:status "completed"}) job))

(defn publish! [root task kind]
  (let [record (delivery/snapshot! root {:kind kind :bundle-id (str task "/bundle") :title "交付成果"
                                         :task-id task :task-spec-revision "spec1" :text "固定方案" :files []})]
    (delivery/approve! root (:id record) "人工批准" :human) record))

;; Approval freezes bytes and content, is idempotent, and never approves later
;; edits or loses the original decision. Corrupt archives block further use.
(deftest immutable-approval-test
  (let [root (fixture) file (io/file root "design.md") _ (spit file "v1")
        input {:kind "proposal" :bundle-id "b" :text "方案" :files [{:path "/design.md"}]}
        original (delivery/snapshot! root input)]
    (delivery/approve! root (:id original) "已核对" :human)
    (delivery/approve! root (:id original) "重复点击" :human)
    (is (= 1 (count (:approvals (work/read-work root)))))
    (spit file "v2")
    (let [second (delivery/snapshot! root input) data (work/read-work root)]
      (is (not= (:id original) (:id second)))
      (is (delivery/approved? data (:id original)))
      (is (not (delivery/approved? data (:id second))))
      (is (thrown-with-msg? Exception #"清单被修改" (delivery/verify-artifact! root (assoc original :text "tampered-text"))))
      (is (= "v1" (slurp (craft/resource-file root (:path (first (:files original)))))))
      (spit (craft/resource-file root (:path (first (:files original)))) "tampered")
      (is (thrown-with-msg? Exception #"归档文件" (delivery/verify-artifact! root original))))))

;; Legacy ledgers remain readable, while malicious or missing file references
;; cannot turn an external file or an unreceived asset into an approved input.
(deftest compatibility-and-file-boundaries-test
  (let [root (fixture) ledger (craft/resource-file root work/path)]
    (io/make-parents ledger)
    (spit ledger (json/write-str {:version 1 :jobs [] :tasks [] :approvals [] :deliveries []}))
    (is (= [] (:artifacts (work/read-work root))))
    (doseq [path ["C:/Windows/win.ini" "/../secret" "/missing.png"]]
      (is (thrown? Exception (delivery/snapshot! root {:kind "asset-bundle" :bundle-id "x" :files [{:path path}]}))))
    (is (not (delivery/approved? (work/read-work root) "missing")))))

;; Downstream execution waits for human acceptance, pins the exact accepted
;; artifact, and preserves that version across source edits and later reruns.
(deftest accepted-dependency-and-pinning-test
  (let [root (fixture) _ (register! root "design" "document" [])
        _ (register! root "art" "assets" ["design"])
        _ (completed! root "design")
        record (publish! root "design" "proposal")
        job (first (work/enqueue! root [{:task-id "art" :staff-id "s" :action-id "art"}]))]
    (is (string? (delivery/admission-error (work/read-work root) "art" {} job)))
    (delivery/decide-task! root "design" "accepted" [(:id record)] nil "设计验收通过" :human)
    (is (= "已验收" (work/task-status (work/read-work root) "design")))
    (is (= [(:id record)] (mapv :id (delivery/admit! root "art" {} (:id job)))))
    (let [admitted (delivery/admit! root "art" {} (:id job))
          receipt (delivery/accepted (work/read-work root) "design")
          input (delivery/input-text admitted)]
      (is (string/includes? input (get-in admitted [0 :admission :approval-id])))
      (is (string/includes? input (:id receipt)))
      (is (string/includes? input "当前准入：资产版本已批准")))
    (let [fixed (last (:jobs (work/read-work root)))]
      (is (:inputs-fixed fixed))
      (completed! root "design")
      (is (nil? (delivery/accepted (work/read-work root) "design")))
      (is (string? (delivery/admission-error (work/read-work root) "art" {} {})))
      (is (= [(:id record)] (:artifacts (delivery/admission (work/read-work root) "art" {} fixed)))))))

;; A blocked entry holds no employee lease; an unrelated later Action can run
;; while remaining dependency work stays queued on its Person.
(deftest independent-action-bypasses-blocked-test
  (let [root (fixture) [a b] (work/enqueue! root [{:staff-id "s"} {:staff-id "s"}])
        state (tasks/start {:sessions {}} "p" ["a" "b"])]
    (work/update-jobs! root #{(:id a)} {:status "blocked" :block-reason "等待成果"})
    (is (work/claim! root (:id b)))
    (work/admission-state! root (:id b) "迟到的阻塞检查不能释放正在执行的职员")
    (is (= "running" (:status (last (:jobs (work/read-work root))))))
    (is (= ["a"] (get-in (tasks/claim-id state "p" "b") [:sessions "p" :queue :remaining])))
    (work/control! root #{(:id a)} :cancel)
    (is (= "cancelled" (:status (first (:jobs (work/read-work root))))))))

;; Approved input wires refer to immutable versions, permit multiple bundles,
;; and remain usable when their original author's current assignment changes.
(deftest approved-input-wires-test
  (let [[staff model] (graph-test/fixture)
        model (-> model
                  (update :nodes conj {:id "next" :kind :action :title "后续工作" :prompt "使用两个批准包"}
                          {:id "other" :kind :assets :title "另一资产" :artifact-id "v2"})
                  (update :nodes #(mapv (fn approve-source [card] (if (= "s" (:id card)) (assoc card :artifact-id "v1") card)) %)))
        linked (-> model (graph/connect staff "p" "next") (graph/connect staff "s" "next") (graph/connect staff "other" "next"))
        history (update linked :edges #(filterv (fn remove-old-owner [edge] (not= ["p" "a"] [(:from edge) (:to edge)])) %))
        inputs (:inputs (delivery/action-inputs history (graph/node history "next")))]
    (is (= ["v1" "v2"] (mapv :artifact-id inputs)))
    (is (nil? (graph/task-error history staff "next")))
    (is (nil? (graph/task-error history staff "s")))
    (is (string? (delivery/admission-error (work/read-work (fixture)) nil (delivery/action-inputs history (graph/node history "next")) {})))))

;; Compilation, runtime evidence and a human decision are separate gates. This
;; fixture tests receipt integrity, not actual engine/runtime visual acceptance.
(deftest engineering-acceptance-feedback-test
  (let [root (fixture) _ (register! root "integrate" "engineering" [])
        _ (completed! root "integrate")
        file (io/file root "screen.gui") _ (spit file "native-gui")
        state {:context {:canvas-id "c" :path "/c.aicanvas"} :task-origin {:task "integrate" :revision "spec1"}}
        plan (publish! root "integrate" "integration-plan")
        card {:id "asset" :kind :assets :title "工程交付" :approved-plan-id (:id plan) :assets [{:path "/screen.gui"}]}
        output {:import-id "import1" :validation {:status "passed" :build-id "build1" :engine-sha1 (System/getProperty "defold.engine.sha1")}}
        receipt (delivery/record-delivery! root state card output)
        artifact (:artifact-id receipt)]
    (is (thrown? Exception (delivery/decide-task! root "integrate" "accepted" [artifact] (:id receipt) "尚未批准" :human)))
    (delivery/approve! root artifact "人工批准工程产物" :human)
    (is (thrown-with-msg? Exception #"检查" (delivery/decide-task! root "integrate" "accepted" [artifact] (:id receipt) "缺运行证据" :human)))
    (is (thrown-with-msg? Exception #"检查" (delivery/decide-task! root "integrate" "accepted" [artifact] (:id receipt) "自动化也必须有运行证据" :automation)))
    (is (thrown? Exception (delivery/record-check! root (:id receipt) "build" "passed" "不能人工伪造编译" [{:path "/screen.gui"}])))
    (delivery/record-automated-check! root (:id receipt) "runtime" "passed" "后台自动化运行证据" [{:path "/screen.gui"}])
    (is (= "automation" (:actor (last (:checks (delivery/delivery (work/read-work root) (:id receipt)))))))
    (is (nil? (delivery/accepted (work/read-work root) "integrate")))
    (delivery/record-check! root (:id receipt) "visual" "passed" "已核对截图及运行日志" [{:path "/screen.gui"}])
    (is (= "local-human" (:actor (last (:checks (delivery/delivery (work/read-work root) (:id receipt)))))))
    (delivery/decide-task! root "integrate" "accepted" [artifact] (:id receipt) "工程交付验收通过" :human)
    (is (delivery/accepted (work/read-work root) "integrate"))
    (spit file "changed-after-build")
    (is (thrown-with-msg? Exception #"构建后变更" (delivery/decide-task! root "integrate" "accepted" [artifact] (:id receipt) "应阻止过期构建" :human)))
    (delivery/decide-task! root "integrate" "rejected" [] nil "工程修改后需要重验" :human)
    (is (nil? (delivery/accepted (work/read-work root) "integrate")))))

;; Native inspector controls dispatch structured decisions and preserve drafts;
;; no model conversation or automatic approval is triggered by opening details.
(deftest delivery-inspector-test
  (let [root (fixture) _ (register! root "t" "document" []) _ (completed! root "t")
        record (publish! root "t" "proposal")
        state (atom {:project-root root :project-work (work/read-work root) :sessions {}})
        calls (atom [])]
    (ui/run-now
      (let [panel (view/details state {:id "t" :kind :task :status "allocated"}
                                (fn command [op id fields] (swap! calls conj [op id fields])) (fn report [_]))
            _ (Scene. panel)
            checks (filterv #(instance? CheckBox %) (.getChildren panel))]
        (.fire ^CheckBox (first checks))
        (.setText ^TextArea (.lookup panel "#delivery-notes") "人工验收记录")
        (.fire ^Button (.lookup panel "#delivery-accept-task"))
        (is (= :accept-delivery (ffirst @calls)))
        (is (= [(:id record)] (:artifact-ids (nth (first @calls) 2))))
        (is (= "人工验收记录" (:notes (nth (first @calls) 2))))))))

;; Automation must retain explicit identity and pending user visual approval,
;; without skipping execution gates or overwriting later human review.
(deftest automated-approval-and-acceptance-test
  (let [root (fixture)
        _ (register! root "design" "document" [])
        _ (register! root "art" "assets" ["design"])
        artifact (delivery/snapshot! root {:kind "proposal" :bundle-id "design/review" :task-id "design"
                                           :task-spec-revision "spec1" :text "设计范围" :files []})
        id (:id artifact)
        approved (delivery/approve! root id "结构与来源已核对" :automation)]
    (is (= "automation" (:actor approved)))
    (is (= "automation-verification" (:trigger approved)))
    (is (= "pending" (:user-visual-approval approved)))
    (is (string/includes? (:notes approved) "用户最终视觉验收未完成"))
    (delivery/approve! root id "重复自动化核验" :automation)
    (is (= 1 (count (:approvals (work/read-work root)))))
    (is (thrown-with-msg? Exception #"未完成" (delivery/decide-task! root "design" "accepted" [id] nil "已核对" :automation)))
    (is (string? (delivery/admission-error (work/read-work root) "art" {} {})))
    (completed! root "design")
    (is (thrown-with-msg? Exception #"请填写" (delivery/decide-task! root "design" "accepted" [id] nil "" :automation)))
    (let [accepted (delivery/decide-task! root "design" "accepted" [id] nil "自动化验证采纳；未做最终用户视觉验收" :automation)]
      (is (= "automation" (:actor accepted)))
      (is (= "automation-verification" (:trigger accepted)))
      (is (= "pending" (:user-visual-approval accepted)))
      (is (nil? (delivery/admission-error (work/read-work root) "art" {} {})))
      (let [input (delivery/admit! root "art" {} nil)]
        (is (= "automation" (get-in input [0 :admission :actor])))
        (is (string/includes? (delivery/input-text input) "用户最终视觉验收未完成"))))
    (let [human (delivery/approve! root id "最终人工核对" :human)
          approvals (filterv #(= "artifact" (:kind %)) (:approvals (work/read-work root)))]
      (is (not= (:id approved) (:id human)))
      (is (= ["automation" "local-human"] (mapv :actor approvals)))
      (is (nil? (:user-visual-approval human)))
      (work/transact! root #(update % :approvals
                                    (fn legacy-human [records]
                                      (mapv (fn remove-old-identity [record]
                                              (if (= (:id human) (:id record))
                                                (dissoc record :actor :trigger) record)) records))))
      (is (= (:id human) (:id (delivery/approve! root id "旧人工记录无需重复" nil))))
      (is (= 2 (count (filterv #(= "artifact" (:kind %)) (:approvals (work/read-work root)))))))
    (is (thrown-with-msg? Exception #"无效审查身份" (delivery/approve! root id "不能传任意身份" :unknown)))))

;; The review selector defaults to human, is local to its Canvas state, and
;; dispatches the selected automation identity with Task/Assets actions.
(deftest review-mode-inspector-test
  (ui/run-now
    (let [state (atom {:project-work {} :sessions {}})
          calls (atom [])
          command (fn review-command [op id fields] (swap! calls conj [op id fields]))
          panel (view/details state {:id "assets" :kind :assets} command (fn report [_]))
          _ (Scene. panel)
          picker ^ComboBox (.lookup panel "#delivery-review-mode")]
      (is (= :human (.getValue picker)))
      (is (= :human (:review-mode @state)))
      (.setValue picker :automation)
      (is (= :automation (:review-mode @state)))
      (is (string/includes? (.getText ^javafx.scene.control.Label (.lookup panel "#delivery-review-notice")) "用户最终视觉验收未完成"))
      (.fire ^Button (.lookup panel "#delivery-approve-assets"))
      (is (= [[:approve-assets "assets" {:review-mode :automation}]] @calls))
      (let [task (view/details state {:id "t" :kind :task :status "allocated"} command (fn report-task [_]))]
        (Scene. task)
        (.setText ^TextArea (.lookup task "#delivery-notes") "自动化采纳")
        (.fire ^Button (.lookup task "#delivery-accept-task"))
        (is (= :automation (:review-mode (nth (last @calls) 2)))))
      (let [other (atom {})
            control (view/review-controls other false)]
        (Scene. control)
        (is (= :human (:review-mode @other)))))))
