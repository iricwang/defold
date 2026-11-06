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

(ns editor.ai-import-workflow-test
  (:require [cljfx.plorer :as plorer]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [dynamo.graph :as g]
            [editor.ai-canvas :as canvas]
            [editor.ai-delivery :as delivery]
            [editor.ai-graph :as graph]
            [editor.ai-graph-test :as graph-test]
            [editor.ai-import-workflow :as workflow]
            [editor.ai-production :as production]
            [editor.ai-project-work :as work]
            [editor.ai-resource-import :as admission]
            [editor.ai-resource-import-test :as admission-test]
            [editor.ai-results :as results]
            [editor.ai-tasks :as tasks]
            [editor.ai-workbench :as workbench]
            [editor.ai-workspace :as ai-workspace]
            [editor.craft-project :as craft]
            [editor.defold-project :as project]
            [editor.editor-extensions :as extensions]
            [editor.localization :as localization]
            [editor.prefs :as prefs]
            [editor.resource-types :as resource-types]
            [editor.ui :as ui]
            [editor.workspace :as workspace]
            [support.test-support :as test-support]
            [util.coll :as coll]))

;; Assets dependencies coexist with ownership and GUI output.
;; Changed source production invalidates the admission input.
(deftest import-ownership-and-input-test
  (let [[staff base] (graph-test/fixture)
        base (canvas/update-node base "s" #(assoc % :production {:manifest "/ui/ai_generated/reward/v_1/manifest.json"}))
        model (workflow/add-action base staff "s") id (:selected model)
        changed (canvas/update-node model "s" #(assoc-in % [:production :manifest] "/ui/ai_generated/reward/v_2/manifest.json"))
        repaired (graph/connect changed staff "s" id)
        with-gui (canvas/ensure-gui-card model "s")
        arranged (canvas/auto-layout with-gui)]
    (is (= "p" (tasks/person-id model id)))
    (is (= ["a" id] (tasks/action-ids model "p")))
    (is (= "s" (:id (workflow/source-card model id))))
    (is (nil? (graph/task-error model staff id)))
    (is (string? (graph/task-error changed staff id)))
    (is (nil? (graph/task-error repaired staff id)))
    (is (= :gui (:kind (canvas/node-by-id with-gui "gui_s"))))
    (is (> (:x (canvas/node-by-id arranged id)) (:x (canvas/node-by-id arranged "s"))))
    (is (thrown? Exception (graph/connect with-gui staff "s" "a")))))

;; Plans allow later Actions to continue; confirmations join the owner's queue.
;; Completed imports deliver formal resources and a native GUI card.
(deftest plan-confirm-apply-workflow-test
  (let [{:keys [root output]} (admission-test/fixture)
        [staff base] (graph-test/fixture)
        base (canvas/update-node base "s" #(assoc % :production output))
        model (workflow/add-action base staff "s") id (:selected model)
        state (workflow/plan-state! root {:canvas model :staff staff} id)
        result (first (results/cards (:canvas state) id)) rid (:id result)
        _ (workflow/validate-confirmation! root result)
        state (-> state (tasks/start "p" [id "a"]) (results/confirm-state rid))
        applied (workflow/apply-state! root state rid admission-test/passed)
        assets (canvas/node-by-id (:canvas applied) (str "assets_" rid))
        gui (canvas/node-by-id (:canvas applied) (str "gui_assets_" rid))]
    (is (= [id "a" rid] (get-in state [:sessions "p" :queue :remaining])))
    (is (= "resource-import" (:task-type (canvas/node-by-id (:canvas state) id))))
    (is (= "imported" (get-in assets [:production :status])))
    (is (= (get-in assets [:production :gui]) (:gui-resource gui)))
    (is (= 3 (count (:assets assets))))
    (is (nil? (graph/task-error (:canvas applied) staff (:id gui))))))

;; Compile the real GUI, atlas, texture and material dependencies in isolation.
;; Preserve 51 native GUI nodes and controller binding, plus Play output.
;; Validation yields a traceable receipt; human
;; acceptance remains blocked until separately supplied runtime/visual evidence.
(deftest native-import-compilation-test
  (test-support/with-clean-system
    (let [{:keys [root output]} (admission-test/fixture)
          layout (io/file root "runtime.gui")
          _ (spit layout (str (slurp (craft/resource-file root (:gui output)))
                              "\nscript: \"/reward.gui_script\"\n"
                              (coll/join-to-string "\n" (mapv #(str "nodes { id: \"runtime_" % "\" type: TYPE_BOX size { x: 8.0 y: 8.0 } }") (range 50)))))
          _ (spit (io/file root "reward.gui_script") "function init(self) end\n")
          component (io/file root "reward.go")
          _ (spit component "components { id: \"reward\" component: \"/runtime.gui\" }")
          plan (admission/plan-existing-gui! root (:manifest output) "reward" "/runtime.gui")
          prefs (prefs/make :scopes {:global (io/file root ".internal/test.editor_settings")} :schemas [:default])
          localization (localization/make prefs ::test {"en.editor_localization" #(io/reader (io/resource "localization/en.editor_localization"))}
                                          (fn report-error [error] (throw error)))
          workspace (workspace/make-workspace root {} {} localization)
          _ (resource-types/register-resource-types! workspace)
          _ (workspace/resource-sync! workspace)
          project (-> (project/make-project workspace (extensions/make)) project/load-project!)
          snapshot (g/node-value workspace :resource-snapshot)
          marker (io/file root "build/default/keep-me.txt")
          _ (io/make-parents marker) _ (spit marker "existing play build")
          output (admission/apply! root plan
                                   (fn validate-candidate [candidate]
                                     (is (:candidate candidate))
                                     (is (not (.exists (craft/resource-file root (:gui candidate)))))
                                     (workbench/validate-import! project candidate)))
          actual (:data (production/validate-resource-text :gui (slurp (craft/resource-file root (:gui output)))))]
      (is (= 51 (count (:nodes actual))))
      (is (= "/reward.gui_script" (:script actual)))
      (is (= "passed" (get-in output [:validation :status])))
      (is (= "editor-resource-pipeline" (get-in output [:validation :compiler])))
      (is (re-matches #"[a-f0-9]{64}" (get-in output [:validation :build-id])))
      (is (coll/not-empty (get-in output [:validation :compiled-files])))
      (is (= snapshot (g/node-value workspace :resource-snapshot)))
      (is (= "existing play build" (slurp marker)))
      (with-open [files (java.nio.file.Files/list (.toPath (.getParentFile marker)))]
        (is (= 1 (.count files))))
      (let [[staff model] (graph-test/fixture)
            model (-> model
                      (assoc :workflow :document)
                      (canvas/update-node "a" #(assoc % :task-type "resource-import"))
                      (results/merge-output "a" [{:id "native-import-plan" :text (admission/plan-text plan)}]))
            result (first (results/produced-cards model "a"))
            rid (:id result)
            state {:canvas model :staff staff :context {:canvas-id "native-test" :path "/work.aicanvas"}
                   :task-origin {:task "integrate" :revision "spec"}}
            _ (work/register-task! root {:id "integrate" :title "工程集成" :spec-revision "spec" :delivery-type "engineering" :action-ids ["a"]})
            jobs (work/enqueue! root [{:task-id "integrate" :staff-id "s" :canvas-id "native-test" :action-id "a"}
                                      {:task-id "integrate" :staff-id "s" :canvas-id "native-test" :action-id rid}])
            _ (work/update-jobs! root (set (mapv :id jobs)) {:status "completed"})
            plan-version (delivery/card-snapshot! root state result "integration-plan")
            _ (delivery/approve! root (:id plan-version) "测试中明确批准入库方案")
            state (update state :canvas canvas/update-node rid #(assoc % :artifact-id (:id plan-version)))
            applied (workflow/apply-state! root state rid (fn no-rebuild [_] (throw (ex-info "重复事务不应重编译" {}))))
            assets (canvas/node-by-id (:canvas applied) (:to (coll/first-where #(= rid (:from %)) (get-in applied [:canvas :edges]))))
            receipt (delivery/delivery (work/read-work root) (:delivery-id assets))
            gui (canvas/node-by-id (:canvas applied) (str "gui_" (:id assets)))]
        (is (= (get-in output [:validation :build-id]) (:build-id receipt)))
        (is (= (:delivery-id assets) (:delivery-id gui)))
        (is (= (:id plan-version) (:plan-artifact-id receipt)))
        (delivery/approve! root (:artifact-id receipt) "测试中批准实际编译的工程包")
        (is (thrown-with-msg? Exception #"检查" (delivery/decide-task! root "integrate" "accepted" [(:artifact-id receipt)] (:id receipt) "尚无运行证据")))
        ;; This is synthetic QA evidence for testing the decision contract, not
        ;; a claim that a person has played or visually accepted this fixture.
        (doseq [check ["runtime" "visual"]]
          (delivery/record-check! root (:id receipt) check "passed" "验收契约测试用证据" [{:path (:gui output)}]))
        (delivery/decide-task! root "integrate" "accepted" [(:artifact-id receipt)] (:id receipt) "测试验收回流")
        (is (= "已验收" (work/task-status (work/read-work root) "integrate")))
        (let [config (io/file root "game.project") original (slurp config)]
          (try
            (spit config (str original "\n; changed after validation\n"))
            (is (thrown-with-msg? Exception #"配置或依赖" (delivery/record-check! root (:id receipt) "runtime" "passed" "stale environment" [{:path (:gui output)}])))
            (finally (spit config original)))))
      (let [broken (io/file root "broken.gui")
            checked (atom false)
            _ (spit broken (str (slurp layout) "\nnodes { id: \"broken\" type: TYPE_BOX texture: \"components/missing_frame\" }"))
            broken-plan (admission/plan-existing-gui! root (:source-manifest output) "broken" "/broken.gui")]
        (is (thrown? Exception (admission/apply! root broken-plan
                                                 (fn validate-broken-candidate [candidate]
                                                   (reset! checked true)
                                                   (workbench/validate-import! project candidate)))))
        (is @checked)
        (doseq [entry (:files broken-plan)]
          (is (not (.exists (craft/resource-file root (:target entry)))))))
      (let [source (get-in plan [:files 2 :source])
            node (project/get-resource-node project source)]
        (g/transact (g/set-property node :max-nodes 1024))
        (is (thrown-with-msg? Exception #"未保存修改" (workbench/assert-import-resources-saved! project [source])))))))

;; The native Assets menu creates an admission Action with the original owner.
;; Its inspector exposes rules and task type, with execution kept on Person.
(deftest native-import-controls-test
  (ui/run-now
    (let [[staff base] (graph-test/fixture)
          base (-> (merge (select-keys (canvas/initial-canvas) [:version :viewport]) base)
                   (canvas/update-node "s" #(assoc % :production {:manifest "/ui/ai_generated/reward/v_1/manifest.json"})) canvas/auto-layout)
          state (atom {:canvas base :staff staff})
          change! (fn change-import-card [f] (swap! state update :canvas f))
          select! (fn select-import-card [id] (swap! state assoc-in [:canvas :selected] id))
          work! (fn create-import-card [op id _fields]
                  (when (= :add-import op) (swap! state update :canvas workflow/add-action staff id)))
          view (canvas/make-canvas state change! select! (fn focus-import []) (fn report-import [_])
                                   (fn produce-import [_]) (fn open-import [_ _]) (fn gui-import [_ _ _])
                                   (fn attach-import []) (constantly staff) (fn assign-import [_ _]) work!)
          details (canvas/make-details state (fn choose-import [_ _]) (fn open-import-details [_ _])
                                       (fn gui-import-details [_ _ _]) (fn save-import-role [_ _]) (constantly staff)
                                       (fn assign-import-details [_ _]) (fn report-import-details [_]) work!)
          root (:root view) stage (javafx.stage.Stage.)]
      (try
        (.setRight ^javafx.scene.layout.BorderPane root (:root details))
        (.setScene stage (javafx.scene.Scene. root 1400.0 900.0))
        (.add (.getStylesheets (.getScene stage)) (str (io/resource "editor.css")))
        (ai-workspace/apply-styles! root)
        (.show stage) ((:render! view)) (.applyCss root) (.layout root)
        (javafx.event.Event/fireEvent (plorer/one root "#canvas-card-s")
                                      (javafx.scene.input.ContextMenuEvent. javafx.scene.input.ContextMenuEvent/CONTEXT_MENU_REQUESTED 20.0 20.0 100.0 100.0 false nil))
        (let [menu (coll/first-where #(and (instance? javafx.scene.control.ContextMenu %) (.isShowing ^javafx.stage.Window %)) (vec (javafx.stage.Window/getWindows)))
              item (coll/first-where #(= "canvas-create-import-s" (.getId ^javafx.scene.control.MenuItem %)) (.getItems ^javafx.scene.control.ContextMenu menu))]
          (is (not (.isDisable ^javafx.scene.control.MenuItem item)))
          (.hide ^javafx.scene.control.ContextMenu menu) (.fire ^javafx.scene.control.MenuItem item))
        ((:render! view)) ((:refresh! details)) (.applyCss root) (.layout root)
        (let [id (get-in @state [:canvas :selected])]
          (is (= "p" (tasks/person-id (:canvas @state) id)))
          (is (= "resource-import" (:task-type (canvas/node-by-id (:canvas @state) id)))))
        (is (some? (plorer/one root "#action-import-rules")))
        (is (some? (plorer/one root "#action-import-report")))
        (finally ((:dispose! view)) (.close stage))))))

;; Admission rules use a native editable child tab and preserve invalid drafts.
(deftest native-rules-editor-test
  (let [{:keys [root]} (admission-test/fixture)
        file (io/file root (subs admission/rules-path 1))]
    (ui/run-now
      (let [pane (javafx.scene.layout.AnchorPane.)
            view (ai-workspace/make-file-view! pane root admission/rules-path {})
            source (plorer/one pane "#ai-resource-rules-source")
            text (json/write-str (assoc-in admission/default-rules [:gui :images] "/art/ui/{module}"))]
        (javafx.scene.Scene. pane 1000.0 700.0)
        (.setText ^javafx.scene.control.TextArea source text)
        (.fire ^javafx.scene.control.Button (plorer/one pane "#ai-resource-rules-save"))
        (is (= text (slurp file :encoding "UTF-8")))
        (.setText ^javafx.scene.control.TextArea source "{ invalid")
        (.fire ^javafx.scene.control.Button (plorer/one pane "#ai-resource-rules-save"))
        (is (= text (slurp file :encoding "UTF-8")))
        (is (= "{ invalid" (.getText ^javafx.scene.control.TextArea source)))
        ((:close! view))))))
