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

(ns editor.ai-roles-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [editor.ai-canvas :as canvas]
            [editor.ai-document :as document]
            [editor.ai-graph :as graph]
            [editor.ai-project-browser :as browser]
            [editor.ai-roles :as roles]
            [editor.ai-workbench :as workbench]
            [editor.ai-workspace :as workspace]
            [editor.fs :as fs]
            [editor.keymap :as keymap]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [java.nio.file Files]
           [javafx.scene.control Button ComboBox Label TextArea TextField TreeItem TreeView]
           [javafx.scene.layout AnchorPane]))

;; A copied catalog creates independent staff in another project and custom
;; roles retain their identity, appearance and task instructions end to end.
(deftest portable-custom-role-config-test
  (let [root (.toFile (Files/createTempDirectory "role-catalog" (make-array java.nio.file.attribute.FileAttribute 0)))
        other (.toFile (Files/createTempDirectory "role-catalog-copy" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (document/ensure-roles-document! root)
      (let [file (io/file root (subs roles/config-path 1))
            custom (-> (first (:roles (roles/read-config root)))
                       (assoc :id "audio" :title "音频设计" :color "#20B2AA" :icon "person" :portrait "art"
                              :namePool ["听澜" "知音"] :initialCount 2)
                       (assoc-in [:defaults :model] "Astra")
                       (assoc-in [:defaults :responsibilities] "制作游戏音效")
                       (assoc-in [:defaults :deliverables] "交付 WAV 音频与资源清单"))
            config (update (roles/read-config root) :roles conj custom)]
        (roles/save-config! root (slurp file :encoding "UTF-8") (json/write-str config))
        (let [doc (document/read-document root roles/document-path)
              model (document/model doc nil)
              added (roles/add-role model "audio")
              person (canvas/node-by-id added (:selected added))
              saved (document/save-document! root roles/document-path doc added)
              card (roles/assign-staff {:id "person" :kind :person :title "音频负责人"} person)]
          (is (= 5 (count (:nodes model))))
          (is (= "audio" (:id (last (roles/rows model)))))
          (is (= 0 (:count (last (roles/rows model)))))
          (is (= "#20B2AA" (:color (roles/role-type person))))
          (is (= "Astra" (:model person)))
          (is (contains? #{"听澜" "知音"} (:title person)))
          (is (nil? (graph/node-error card [person])))
          (is (.contains (workbench/message-text "生成背景音效" {:staff person}) "音频设计"))
          (is (.contains (workbench/message-text "生成背景音效" {:staff person}) "交付 WAV 音频与资源清单"))
          (is (= (:id person) (:id (canvas/node-by-id (document/model saved nil) (:id person)))))
          (let [updated (-> config (assoc-in [:roles 5 :title] "声音设计")
                            (assoc-in [:roles 5 :defaults :model] "Terra"))]
            (roles/save-config! root (slurp file :encoding "UTF-8") (json/write-str updated))
            (let [current (document/model (document/read-document root roles/document-path) nil)
                  old-person (canvas/node-by-id current (:id person))]
              (is (= "Astra" (:model old-person)))
              (is (= "声音设计" (:title (roles/role-type old-person))))
              (is (= "Terra" (:model (last (:nodes (roles/add-role current "audio"))))))))
          (roles/read-config other)
          (spit (io/file other (subs roles/config-path 1)) (slurp file :encoding "UTF-8") :encoding "UTF-8")
          (document/ensure-roles-document! other)
          (let [copied (document/model (document/read-document other roles/document-path) nil)
                original-ids (set (mapv :id (:nodes added)))]
            (is (= 7 (count (:nodes copied))))
            (is (= #{"听澜" "知音"} (set (mapv :title (filterv #(= "audio" (:role-type %)) (:nodes copied))))))
            (is (coll/not-any? #(contains? original-ids (:id %)) (:nodes copied))))))
      (finally (fs/delete-directory! root) (fs/delete-directory! other)))))

;; Invalid tables, removal of referenced IDs and stale edits never overwrite
;; a valid catalog; explicit per-staff blank fields and false remain editable.
(deftest role-config-validation-test
  (let [root (.toFile (Files/createTempDirectory "role-catalog-validation" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (document/ensure-roles-document! root)
      (let [file (io/file root (subs roles/config-path 1))
            text (slurp file :encoding "UTF-8")
            config (roles/read-config root)]
        (doseq [invalid [(assoc config :schemaVersion 2)
                         (update config :roles conj (first (:roles config)))
                         (assoc-in config [:roles 0 :color] "invalid")
                         (assoc-in config [:roles 0 :namePool] [])
                         (assoc-in config [:roles 0 :defaults :enabled] "false")
                         (assoc-in config [:roles 0 :initialCount] -1)
                         (update config :roles subvec 1)]]
          (is (thrown? Exception (roles/save-config! root text (json/write-str invalid))))
          (is (= text (slurp file :encoding "UTF-8"))))
        (is (thrown? Exception (roles/save-config! root "stale" text)))
        (let [person (assoc (roles/new-role "planning" #{}) :enabled false :responsibilities "" :deliverables "")]
          (is (= person (first (:nodes (roles/apply-defaults {:nodes [person]})))))))
      (finally (fs/delete-directory! root)))))

;; The in-workspace JSON editor validates and persists the entire catalog,
;; reports errors, and leaves the last valid file intact on invalid input.
(deftest editable-role-config-view-test
  (let [root (.toFile (Files/createTempDirectory "role-catalog-editor" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (roles/read-config root)
      (ui/run-now
        (let [parent (AnchorPane.)
              view (workspace/make-file-view! parent root roles/config-path {})
              _ (javafx.scene.Scene. parent)
              source ^TextArea (.lookup parent "#ai-role-config-source")
              save ^Button (.lookup parent "#ai-role-config-save")
              status ^Label (.lookup parent "#ai-role-config-status")
              config (assoc-in (roles/read-config root) [:roles 0 :title] "系统策划")]
          (.setText source (json/write-str config))
          (.fire save)
          (is (= config (roles/read-config root)))
          (.setText source "{broken")
          (.fire save)
          (is (not (string/blank? (.getText status))))
          (is (= config (roles/read-config root)))
          ((:close! view))))
      (finally (fs/delete-directory! root)))))

;; Reopening preserves assignments; stale saves cannot overwrite other edits.
(deftest role-document-round-trip-test
  (let [root (.toFile (Files/createTempDirectory "ai-roles" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (browser/ensure-library! root)
      (let [path (document/ensure-roles-document! root)
            doc (document/read-document root path)
            model (document/model doc nil)
            id (:id (first (:nodes model)))
            changed (canvas/update-node model id #(assoc % :assignee "Alice" :model "Astra" :effort "中"))
            saved (document/save-document! root path doc changed)]
        (is (= :roles (:workflow model)))
        (is (= ["planning" "art" "programming" "pm" "qa"] (mapv :role-type (:nodes model))))
        (is (= 5 (count (set (mapv :title (:nodes model))))))
        (is (= (:nodes model) (:nodes (document/model doc nil))))
        (is (= 5 (count (set (mapv :id (:nodes model))))))
        (is (= path (document/ensure-roles-document! root)))
        (is (= saved (document/read-document root path)))
        (is (= "Alice" (:assignee (first (:nodes saved)))))
        (is (= 2 (:revision saved)))
        (is (thrown? Exception (document/save-document! root path doc model)))
        (is (= saved (document/save-document! root path saved (assoc changed :viewport {:zoom 1.2 :x 0 :y 0}))))
        (is (= #{"创作资料" "项目配置"} (set (mapv :title (browser/entries root "ai-workspace")))))
        (is (= ["项目职能" "职能配表"] (mapv :title (browser/entries root "ai-workspace/config"))))
        (is (.isFile (io/file root (subs path 1))))
        (let [state (canvas/change-state {:canvas changed} #(roles/add-role % "planning"))
              added (:canvas state)
              undone (canvas/history-state state :undo)]
          (is (= 6 (count (:nodes added))))
          (is (= (dissoc changed :viewport) (dissoc (:canvas undone) :viewport)))
          (is (= added (:canvas (canvas/history-state undone :redo))))
          (is (= 5 (count (:nodes (canvas/remove-node added (:selected added))))))
          (is (thrown? Exception (canvas/connect-nodes added id (:selected added))))))
      (finally (fs/delete-directory! root)))))

;; Real detail controls save edited values and reject invalid input.
(deftest role-details-edit-test
  (ui/run-now
    (let [card (roles/new-role "planning" #{})
          saved (atom nil)
          pane (roles/make-editor card (:roles (roles/default-config)) (fn capture-role [id fields] (reset! saved [id fields])))
          _ (javafx.scene.Scene. pane)
          _ (.applyCss pane)
          title ^TextField (.lookup pane "#ai-role-title")
          assignee ^TextField (.lookup pane "#ai-role-assignee")
          model ^ComboBox (.lookup pane "#ai-role-model")
          responsibilities ^TextArea (.lookup pane "#ai-role-responsibilities")
          save ^Button (.lookup pane "#ai-role-save")]
      (.setText title "主策划")
      (.setText assignee "Alice")
      (.setText (.getEditor model) "custom-model")
      (.setText responsibilities "维护核心循环")
      (.fire save)
      (is (= (:id card) (first @saved)))
      (is (= "Alice" (:assignee (second @saved))))
      (is (= "custom-model" (:model (second @saved))))
      (is (= "维护核心循环" (:responsibilities (second @saved))))
      (reset! saved nil)
      (.setText title "   ")
      (.fire save)
      (is (nil? @saved))
      (.fire ^Button (.lookup pane "#ai-role-reset"))
      (is (= "主策划" (.getText title)))
      (is (= "custom-model" (.getText (.getEditor model))))
      (is (nil? @saved)))))

;; Search changes presentation only and retains IDs and complete saved data.
(deftest staff-search-test
  (let [nodes (roles/initial-nodes)
        staff (assoc (roles/new-role "art" #{}) :title "角色设计" :assignee "Alice")
        model (roles/layout {:workflow :roles :nodes (conj nodes staff) :edges []})
        by-person (roles/visible-model model " ALICE ")
        by-role (roles/visible-model model "美术")]
    (is (= [(:id staff)] (mapv :id (:nodes by-person))))
    (is (= roles/row-label-width (:x (first (:nodes by-person)))))
    (is (= 2 (count (:nodes by-role))))
    (is (= model (roles/visible-model model "")))
    (is (= 6 (count (:nodes model))))
    (is (= [] (:nodes (roles/visible-model model "missing"))))))

;; The configuration entry is a library sibling and is selected on opening.
(deftest role-browser-selection-test
  (let [root (.toFile (Files/createTempDirectory "ai-role-browser" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (ui/run-now
        (let [browser (browser/make-browser root roles/document-path identity (constantly nil) identity {})
              tree ^TreeView (.lookup ^javafx.scene.Node (:root browser) "#ai-project-tree")
              selected ^TreeItem (.getSelectedItem (.getSelectionModel tree))]
          (is (= "项目职能" (:title (.getValue selected))))
          (is (= "项目配置" (:title (.getValue (.getParent selected)))))
          (is (= ["创作资料" "项目配置"] (mapv :title (browser/entries root "ai-workspace"))))))
      (finally (fs/delete-directory! root)))))

;; Ctrl+I has one global destination, including when a code editor has focus.
(deftest ai-mode-shortcut-test
  (doseq [platform [:win32 :linux :macos]]
    (let [bindings (keymap/default platform)]
      (is (= #{:ai.open} (keymap/commands bindings "Ctrl+I")))
      (is (= #{:code.reindent} (keymap/commands bindings "Shift+Ctrl+J"))))))

;; Legacy producer cards become PM without losing assignments or stable IDs.
(deftest fixed-role-migration-test
  (let [legacy (-> (roles/new-role "pm" #{})
                   (dissoc :role-type)
                   (assoc :title "制作人" :assignee "Alice" :responsibilities "排期" :x 900.0 :y 42.0))
        model (roles/layout {:workflow :roles :nodes [legacy] :edges []})
        node (first (:nodes model))]
    (is (= "pm" (:role-type node)))
    (is (= "PM" (:title node)))
    (is (= (:id legacy) (:id node)))
    (is (= "Alice" (:assignee node)))
    (is (= "排期" (:responsibilities node)))
    (is (= [roles/row-label-width (* 3 roles/row-height)] [(:x node) (:y node)]))
    (is (= model (roles/layout model)))
    (let [doc {:template "project-roles" :nodes [(assoc legacy :kind "role")] :edges []}
          reopened (document/model doc {:viewport {:x -999.0 :y 999.0 :zoom 1.5}})]
      (is (= {:x 24.0 :y 24.0 :zoom 0.85} (:viewport reopened)))
      (is (= (:id legacy) (:id (first (:nodes reopened))))))))

;; Add/delete/undo stay within fixed rows, including rows with no cards.
(deftest fixed-role-row-layout-test
  (let [nodes (roles/initial-nodes)
        model (roles/layout {:workflow :roles :nodes nodes :selected (:id (first nodes)) :edges []})
        state (canvas/change-state {:canvas model} #(roles/add-role % "art"))
        added (:canvas state)
        cards (filterv #(= "art" (:role-type %)) (:nodes added))
        removed (:canvas (canvas/change-state state #(canvas/remove-node % (:id (first cards)))))
        restored (:canvas (canvas/history-state state :undo))]
    (is (= [roles/row-height roles/row-height] (mapv :y cards)))
    (is (= [roles/row-label-width (+ roles/row-label-width roles/column-step)] (mapv :x cards)))
    (is (= roles/row-label-width (:x (first (filterv #(= "art" (:role-type %)) (:nodes removed))))))
    (is (= model restored))
    (is (= 5 (count (roles/rows {:nodes []}))))
    (is (= 5 (count (set (mapv :color roles/role-types)))))
    (is (= ["策划" "美术" "程序" "PM" "QA"] (mapv :title (roles/rows model))))
    (let [moved (:canvas (canvas/change-state {:canvas model} #(canvas/update-node % (:id (first (:nodes model))) (fn [node] (assoc node :x 1000.0 :y 800.0)))))]
      (is (= (:nodes model) (:nodes moved))))))

;; Every fixed role has its own portrait, with no external image dependency.
(deftest role-portrait-test
  (ui/run-now
    (doseq [type roles/role-types]
      (let [card (roles/new-role (:id type) #{})
            portrait ^javafx.scene.layout.Pane (roles/make-portrait card)]
        (is (= (str (:title type) "人物形象") (.getAccessibleText portrait)))
        (is (> (count (.getChildren portrait)) 8))
        (is (= 80.0 (.getPrefWidth portrait)))))))

;; Role pools avoid existing names, including after the pool is exhausted.
(deftest random-staff-names-test
  (doseq [type roles/role-types]
    (let [type-id (:id type)
          pool (get roles/name-pools type-id)
          card (roles/new-role type-id #{})
          nodes (:nodes (reduce (fn add-staff [model _] (roles/add-role model type-id))
                                {:nodes []} (range (+ 3 (count pool)))))]
      (is (contains? (set pool) (:title card)))
      (is (= (count nodes) (count (set (mapv :title nodes)))))
      (is (= (set pool) (set (mapv :title (subvec nodes 0 (count pool))))))
      (is (= type-id (:role-type card)))
      (let [occupied (set (concat pool (mapv #(str % "·2") pool)))
            next-card (roles/new-role type-id occupied)]
        (is (not (contains? occupied (:title next-card)))))))
  (let [model {:nodes [(assoc (roles/new-role "art" #{}) :title (first (get roles/name-pools "planning")))]}
        added (roles/add-role model "planning")]
    (is (= 2 (count (set (mapv :title (:nodes added))))))))

;; Migration preserves custom fields and IDs; saved names do not reroll.
(deftest staff-name-migration-test
  (let [root (.toFile (Files/createTempDirectory "ai-role-names" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (let [path (document/ensure-roles-document! root)
            doc (document/read-document root path)
            nodes (:nodes (document/model doc nil))
            legacy (assoc (first nodes) :title "策划")
            custom (assoc (second nodes) :title "我的美术" :assignee "Alice")
            saved (document/save-document! root path doc {:nodes [legacy custom] :edges []})
            named (document/model saved nil)
            persisted (document/save-document! root path saved named)]
        (is (not= "策划" (:title (first (:nodes named)))))
        (is (= (dissoc legacy :title) (dissoc (first (:nodes named)) :title)))
        (is (= (dissoc custom :x :y) (dissoc (second (:nodes named)) :x :y)))
        (is (= named (roles/assign-staff-names named)))
        (is (= (:nodes named) (:nodes (document/model persisted nil)))))
      (finally (fs/delete-directory! root)))))

;; Defaults cover every role and fill fields independently without losing edits.
(deftest staff-work-defaults-test
  (doseq [{:keys [id]} roles/role-types]
    (let [defaults (get roles/role-defaults id)
          card (roles/new-role id #{})
          legacy (dissoc card :responsibilities :deliverables)
          custom (assoc card :responsibilities "仅负责战斗系统" :deliverables "提交战斗测试报告")
          partial (dissoc custom :deliverables)
          model {:nodes [legacy custom partial]}
          filled (roles/apply-defaults model)]
      (is (= defaults (select-keys card [:responsibilities :deliverables])))
      (is (coll/every? #(pos? (count %)) (coll/vals defaults)))
      (is (= card (roles/validate-role card)))
      (is (= card (first (:nodes filled))))
      (is (= custom (second (:nodes filled))))
      (is (= (assoc partial :deliverables (:deliverables defaults)) (nth (:nodes filled) 2)))
      (is (= filled (roles/apply-defaults filled)))))
  (let [root (.toFile (Files/createTempDirectory "ai-role-defaults" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (let [path (document/ensure-roles-document! root)
            doc (document/read-document root path)
            model (document/model doc nil)
            old-doc (update doc :nodes #(mapv (fn [node] (dissoc node :responsibilities :deliverables)) %))
            filled (document/model old-doc nil)
            saved (document/save-document! root path doc filled)]
        (is (= (:nodes model) (:nodes filled)))
        (is (= (:nodes filled) (:nodes (document/model saved nil)))))
      (finally (fs/delete-directory! root)))))

;; Person supplies downstream staff; missing or disabled staff is rejected.
(deftest action-staff-assignment-test
  (let [person (roles/new-role "planning" #{})
        base (canvas/add-person (canvas/initial-canvas))
        model (canvas/update-node base (:selected base) #(roles/assign-staff % person))
        state {:canvas model :context {:path "/test.gui"}}
        updated (assoc person :title "新名字" :responsibilities "新的职责")
        context (workbench/task-context state "result-1" [updated])
        text (workbench/message-text "安排工作" context)]
    (is (= (:id person) (:staff-id (canvas/node-by-id model (:selected base)))))
    (is (nil? (:staff-id (canvas/node-by-id model "action-1"))))
    (is (nil? (:staff (workbench/task-context {:canvas (canvas/update-node (canvas/initial-canvas) "action-1" #(roles/assign-staff % person))} "action-1" [person]))))
    (is (= updated (:staff context)))
    (is (.contains text "新的职责"))
    (is (.contains text (:deliverables updated)))
    (is (.contains text (:id person)))
    (is (thrown? Exception (workbench/task-context state "assets-1" [])))
    (is (thrown? Exception (workbench/task-context state "action-1" [(assoc updated :enabled false)])))
    (is (nil? (:staff-id (roles/assign-staff (canvas/node-by-id model "action-1") nil))))
    (is (= (:canvas (canvas/history-state (canvas/change-state {:canvas (canvas/initial-canvas)} (constantly model)) :undo))
           (canvas/initial-canvas)))))

;; Friendly model names resolve through the live catalog, never arbitrary IDs.
(deftest staff-model-settings-test
  (let [person (roles/new-role "planning" #{})
        models [{:id "available-sol" :model "available-sol" :isDefault true :supportedReasoningEfforts [{:reasoningEffort "high"}]}]]
    (is (= {:model "available-sol" :effort "high"} (roles/request-settings person models)))
    (is (= {:model "available-sol" :effort "high"} (roles/request-settings (assoc person :model "available-sol") models)))
    (is (thrown? Exception (roles/request-settings person [])))
    (is (thrown? Exception (roles/request-settings person [(assoc (first models) :hidden true)])))
    (is (thrown? Exception (roles/request-settings (assoc person :effort "极高") models)))))

;; Staff IDs survive save and reopen without copying stale settings.
(deftest assigned-document-round-trip-test
  (let [root (.toFile (Files/createTempDirectory "canvas-assignment" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (browser/ensure-library! root)
      (let [path (document/create-document! root "ai-workspace/library/01-concept" "工作指派" "concept")
            doc (document/read-document root path)
            model (document/model doc nil)
            person (roles/new-role "programming" #{})
            assigned (canvas/update-node model (:id (first (:nodes model))) #(roles/assign-staff % person))
            saved (document/save-document! root path doc assigned)]
        (is (= (:nodes assigned) (:nodes (document/model saved nil)))))
      (finally (fs/delete-directory! root)))))

;; The real picker refreshes its roster and routes selections by staff ID.
(deftest staff-assignment-picker-test
  (ui/run-now
    (let [active (roles/new-role "art" #{})
          disabled (assoc (roles/new-role "art" #{}) :enabled false)
          roster (atom [active disabled])
          selected (atom nil)
          picker ^javafx.scene.control.MenuButton
          (roles/make-assignment {:id "action-1"} #(deref roster)
                                 (fn capture-assignment [card-id staff-id] (reset! selected [card-id staff-id]))
                                 (fn report-assignment [message] (throw (ex-info message {}))) "test-staff-")]
      (.handle (.getOnShowing picker) nil)
      (is (= 2 (count (.getItems picker))))
      (.fire ^javafx.scene.control.MenuItem (.get (.getItems picker) 1))
      (is (= ["action-1" (:id active)] @selected))
      (reset! roster [disabled])
      (.handle (.getOnShowing picker) nil)
      (is (= 1 (count (.getItems picker))))
      (.fire ^javafx.scene.control.MenuItem (.get (.getItems picker) 0))
      (is (= ["action-1" nil] @selected)))))
