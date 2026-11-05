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

(ns editor.ai-results-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [editor.ai-canvas :as canvas]
            [editor.ai-graph :as graph]
            [editor.ai-results :as results]
            [editor.ai-roles :as roles]
            [editor.ai-tasks :as tasks]
            [editor.ai-workbench :as workbench]
            [editor.ui :as ui]
            [util.coll :as coll]))

(defn- fixture []
  (let [staff [(roles/new-role "planning" #{})]
        model (canvas/add-person (assoc (canvas/initial-canvas) :workflow :document :template "concept"))
        person (:selected model)
        model (-> model
                  (canvas/update-node person #(roles/assign-staff % (first staff)))
                  (canvas/update-node "action-1" #(assoc % :prompt "设计系统交互"))
                  (canvas/merge-candidates "action-1" [{:id "a" :text "方案 A"} {:id "b" :text "方案 B"}]))]
    {:staff staff :canvas model :sessions {} :person person}))

;; External file edits change the frozen content version even when the card's
;; text/path are unchanged. A queued version is immutable and a rerun gets a
;; separate confirmation after the original completes.
(deftest frozen-content-version-confirmation-test
  (let [state (fixture) person (:person state)
        result (first (results/cards (:canvas state) "action-1")) id (:id result)
        first-version (results/bind-content-version state id "bytes-v1")
        queued (results/confirm-state first-version id)
        finished (-> queued (tasks/claim-next person)
                     (assoc-in [:sessions id :execution] {:status "completed"}) tasks/reconcile)
        next-version (results/bind-content-version finished id "bytes-v2")
        confirmed (results/confirm-state next-version id)]
    (is (not (:manually-edited (graph/node (:canvas first-version) id))))
    (is (identical? queued (results/bind-content-version queued id "bytes-v1")))
    (is (thrown? Exception (results/bind-content-version queued id "bytes-v2")))
    (is (not= (get-in queued [:sessions id :confirmation :revision]) (get-in confirmed [:sessions id :confirmation :revision])))
    (is (= [id] (get-in confirmed [:sessions person :queue :remaining])))
    (is (= "bytes-v1" (get-in (graph/node (:canvas confirmed) id) [:revision-history 0 :candidate :content-revision])))))

;; Each proposal keeps its provenance and branch; replay preserves edits.
(deftest independent-proposals-test
  (let [state (fixture)
        model (:canvas state)
        [a b] (results/cards model "action-1")
        edited (results/edit model (:id b) {:text "人工修订" :image-path "new.png" :files [{:path "new.png" :sha256 "new"}]})
        replay (canvas/merge-candidates edited "action-1" [{:id "a" :text "方案 A"} {:id "b" :text "旧快照"}])
        adopted (reduce (fn adopt [model card] (-> model (canvas/ensure-result-assets (:id card)) (canvas/adopt-candidate (:id card)))) replay [a b])
        assets (filterv #(= :assets (:kind %)) (:nodes adopted))]
    (is (= 2 (count (results/cards replay "action-1"))))
    (is (= "人工修订" (:text (results/candidate (graph/node replay (:id b))))))
    (is (= #{"a" "b"} (set (mapv :source-candidate assets))))
    (is (= #{"方案 A" "人工修订"} (set (mapv :proposal-text assets))))
    (is (coll/every? :source-revision assets))
    (is (not= (:y a) (:y b)))
    (is (thrown? Exception (canvas/disconnect-edge model {:from "action-1" :to (:id a)})))
    (let [detached (-> model (assoc :edges []) canvas/add-flow)
          target (:selected detached)]
      (is (thrown? Exception (canvas/connect-nodes detached target (:id a)))))
    (is (= [] (results/cards (canvas/remove-node model "action-1") "action-1")))))

;; Migration splits old Results once and preserves their adopted branches.
(deftest legacy-proposals-test
  (let [old (-> (assoc (canvas/initial-canvas) :workflow :document :template "concept")
                (canvas/update-node "result-1" #(assoc % :candidates [{:id "a" :text "A"} {:id "b" :text "B"}] :chosen "b"))
                (canvas/adopt-candidate "result-1"))
        migrated (results/migrate old)]
    (is (= migrated (results/migrate migrated)))
    (is (= "b" (:id (results/candidate (graph/node migrated "result-1")))))
    (is (= (graph/node old "assets-1") (graph/node migrated "assets-1")))
    (is (= (results/revision (graph/node migrated "result-1")) (:confirmed-revision (graph/node migrated "result-1"))))
    (is (= 2 (count (results/cards migrated "action-1"))))))

;; Actions run first; confirmations append and cannot double-submit.
(deftest confirmation-queue-test
  (let [state (fixture)
        person (:person state)
        [a b] (mapv :id (results/cards (:canvas state) "action-1"))
        running (-> state (tasks/start person ["action-1" "action-2"]) (tasks/claim-next person)
                    (assoc-in [:sessions "action-1" :execution] {:status "running" :turn-id "one"}))
        edited (results/edit-state running a {:text "采用人工修改版本"})
        queued (-> edited (results/confirm-state a) (results/confirm-state b))
        next (-> queued (tasks/accept-snapshot "action-1" {:thread {:turns [{:id "one" :status "completed"}]}})
                 (tasks/claim-next person))
        confirmation (-> next (assoc-in [:sessions "action-2" :execution :status] "completed") tasks/reconcile (tasks/claim-next person))
        failed (-> confirmation (assoc-in [:sessions a :execution :status] "failed") tasks/reconcile)
        resumed (tasks/resume failed person)]
    (is (= ["action-2" a b] (get-in queued [:sessions person :queue :remaining])))
    (is (= "action-2" (get-in next [:sessions person :queue :current])))
    (is (= a (get-in confirmation [:sessions person :queue :current])))
    (is (= "采用人工修改版本" (get-in queued [:sessions a :confirmation :candidate :text])))
    (is (identical? queued (results/confirm-state queued a)))
    (is (thrown? Exception (results/edit-state queued a {:text "不能改动已排队版本"})))
    (is (identical? failed (results/confirm-state failed a)))
    (is (= [a b] (get-in resumed [:sessions person :queue :remaining])))
    (is (= (workbench/task-owner (:canvas state) "action-1" "/test.aicanvas") (workbench/task-owner (:canvas state) a "/test.aicanvas")))
    (is (thrown? Exception (results/confirm-state (update state :canvas canvas/remove-node person) a)))
    (is (thrown? Exception (results/confirm-state (assoc state :producing #{"assets-1"}) a)))
    (let [done (-> queued (assoc-in [:sessions a :execution :status] "completed")
                   (assoc-in [:sessions person :queue] {:status "completed" :current nil}))
          revised (results/edit-state done a {:text "下一次人工版本"})]
      (is (identical? done (results/confirm-state done a)))
      (is (= [a] (get-in (results/confirm-state revised a) [:sessions person :queue :remaining]))))))

;; Explicit proposal headings split outputs, excluding code blocks.
(deftest text-proposals-test
  (let [output (fn snapshot [text] (canvas/snapshot-candidates {:thread {:turns [{:id "t" :status "completed" :items [{:type "agentMessage" :text text}]}]}} "thread"))
        split (output "公共约束\n## 方案 A：战斗\n内容 A\n## 方案 B：探索\n内容 B")]
    (is (= ["thread/t/proposal-1" "thread/t/proposal-2"] (mapv :id split)))
    (is (= "公共约束\n## 方案 A：战斗\n内容 A" (:text (first split))))
    (is (= 1 (count (output "## 方案说明\n文字\n## 交付要求\n文字"))))
    (is (= 1 (count (output "```md\n## 方案 A：示例\n## 方案 B：示例\n```"))))))

;; Result details expose proposal controls, without Action task controls.
(deftest result-details-test
  (ui/run-now
    (let [state (atom (assoc-in (fixture) [:canvas :selected] "result-1"))
          events (atom [])
          details (canvas/make-details state (fn choose-result [_ _]) (fn open-result [_ _])
                                       (fn gui-result [_ _ _]) (fn save-role [_ _]) (constantly (:staff @state))
                                       (fn assign-role [_ _]) (fn report-result [_])
                                       (fn result-command [op id fields] (swap! events conj [op id fields])))
          root ^javafx.scene.layout.BorderPane (:root details)]
      (javafx.scene.Scene. root 400.0 800.0)
      ((:refresh! details)) (.applyCss root) (.layout root)
      (is (nil? (.lookup root "#action-task-save")))
      (.setText ^javafx.scene.control.TextArea (.lookup root "#ai-result-text") "手工版本")
      (.fire ^javafx.scene.control.Button (.lookup root "#ai-result-confirm"))
      (is (= [[:confirm-result "result-1" {:text "手工版本"}]] @events))
      (is (= "手工版本" (get-in @state [:result-drafts "result-1" :text])))
      (swap! state results/confirm-state "result-1")
      ((:refresh! details)) (.applyCss root)
      (is (.isDisabled (.lookup root "#ai-result-text")))
      (is (.isDisabled (.lookup root "#ai-result-image"))))))

;; All workflow cards share dimensions; activity shows a narrow scrollbar.
(deftest card-size-and-scroll-test
  (ui/run-now
    (let [state (atom (fixture))
          view (canvas/make-canvas state (fn change-card [f] (swap! state update :canvas f))
                                   (fn select-card [_]) (fn focus-card []) (fn report-card [_])
                                   (fn produce-card [_]) (fn open-card [_ _]) (fn gui-card [_ _ _])
                                   (fn attach-card []) (constantly (:staff @state))
                                   (fn assign-card [_ _]) (fn work-card [_ _ _]))
          root ^javafx.scene.layout.BorderPane (:root view)
          scene (javafx.scene.Scene. root 1200.0 800.0)]
      (.add (.getStylesheets scene) (str (io/resource "editor.css")))
      (.add (.getStylesheets scene) (str (.toURI (io/file "resources/ai-canvas.css"))))
      (.resize root 1200.0 800.0) (.applyCss root) (.layout root)
      (let [button ^javafx.scene.control.Button (.lookup root "#canvas-arrange")]
        (is (some? button))
        (.fire button)
        (is (= 340.0 (:x (graph/node (:canvas @state) "action-1")))))
      (.applyCss root) (.layout root)
      (doseq [card (:nodes (:canvas @state))]
        (let [node ^javafx.scene.layout.Region (.lookup root (str "#canvas-card-" (:id card)))]
          (is (= 268.0 (.getWidth node)))
          (is (= 470.0 (.getHeight node)))))
      (let [scroll ^javafx.scene.control.ScrollPane (.lookup (.lookup root "#canvas-card-action-1") ".ai-card-scroll")
            bar ^javafx.scene.control.ScrollBar (.lookup scroll ".scroll-bar:vertical")
            timer ^javafx.animation.PauseTransition (ui/user-data scroll :editor.ai-canvas/scroll-timer)]
        (is (= 5.0 (.getPrefWidth bar)))
        (is (= 0.0 (.getOpacity bar)))
        (javafx.event.Event/fireEvent scroll
                                      (javafx.scene.input.MouseEvent. javafx.scene.input.MouseEvent/MOUSE_MOVED
                                                                      5.0 5.0 5.0 5.0 javafx.scene.input.MouseButton/NONE 0
                                                                      false false false false false false false false false false nil))
        (.applyCss root)
        (is (= 1.0 (.getOpacity bar)))
        (.stop timer)
        (.handle (.getOnFinished timer) (javafx.event.ActionEvent.))
        (.applyCss root)
        (is (= 0.0 (.getOpacity bar)))))))

;; Arrangement reserves room for all branches and keeps execution order.
;; It only moves cards and can be undone in one operation.
(deftest arrange-branches-test
  (let [state (fixture)
        person (:person state)
        expanded (canvas/add-flow (:canvas state))
        second (:selected expanded)
        base (-> expanded
                 (canvas/connect-nodes person second)
                 (canvas/merge-candidates second [{:id "second" :text "第二任务"}])
                 (canvas/add-node :action -800.0 -900.0))
        arranged (canvas/auto-layout base)
        cards (:nodes arranged)
        moved (canvas/change-state (assoc state :canvas base) canvas/auto-layout)
        restored (canvas/history-state moved :undo)]
    (is (= arranged (canvas/auto-layout arranged)))
    (is (= (:edges base) (:edges arranged)))
    (is (= (:selected base) (:selected arranged)))
    (is (= (mapv #(dissoc % :x :y) (:nodes base)) (mapv #(dissoc % :x :y) cards)))
    (is (= ["action-1" second] (tasks/action-ids arranged person)))
    (is (< (:y (graph/node arranged "action-1")) (:y (graph/node arranged second))))
    (doseq [a cards b cards :when (not= (:id a) (:id b))]
      (is (or (<= (+ 268.0 (:x a)) (:x b)) (<= (+ 268.0 (:x b)) (:x a))
              (<= (+ 470.0 (:y a)) (:y b)) (<= (+ 470.0 (:y b)) (:y a)))))
    (is (= base (:canvas restored)))
    (is (= 1 (count (:canvas-undo moved))))
    (is (= (:sessions state) (:sessions moved)))))
