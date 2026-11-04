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


(ns editor.ai-canvas-test
  (:require [clojure.test :refer [deftest is]]
            [editor.ai-canvas :as canvas]
            [editor.ai-graph :as graph]
            [editor.ai-results :as results]
            [editor.ai-roles :as roles]
            [editor.ai-workbench :as workbench]
            [editor.ui :as ui]))

;; Migration preserves explicit GUI bindings and creates one terminal card.
(deftest gui-delivery-card-test
  (let [base (assoc (canvas/initial-canvas) :workflow :document :template "gui")
        output {:gui "/ui/generated.gui" :atlas "/ui/generated.atlas"}
        delivered (canvas/deliver-gui base "assets-1" output)
        id "gui_assets-1"
        bound (canvas/update-node delivered id #(assoc % :gui-resource "/game/final.gui"))]
    (is (= [:gui] (get graph/successors :assets)))
    (is (= [] (get graph/successors :gui)))
    (is (= 4 (count (:nodes delivered))))
    (is (= (:gui output) (:gui-resource (canvas/node-by-id delivered id))))
    (is (= bound (canvas/migrate-gui-cards bound)))
    (is (= "/game/final.gui" (:gui-resource (canvas/node-by-id (canvas/migrate-gui-cards bound) id))))
    (is (thrown? Exception (canvas/connect-nodes delivered "result-1" id)))
    (is (thrown? Exception (canvas/connect-nodes delivered id "assets-1")))
    (is (= 3 (count (:nodes (canvas/migrate-gui-cards (assoc base :template "concept"))))))))

;; Component sheets stay on Assets, including replay and legacy adoption.
(deftest gui-candidate-purpose-test
  (let [sheet {:id "sheet" :image-path "sheet.png" :preview "file:/sheet.png"
               :files [{:name "loot_components_v2.png" :path "sheet.png"}]}
        screen {:id "screen" :image-path "screen.png" :files [{:name "loot_screen.png" :path "screen.png"}]}
        base (assoc (canvas/initial-canvas) :workflow :document :template "gui")
        model (-> base (canvas/merge-candidates "action-1" [sheet screen])
                  (canvas/update-node "result-1" #(assoc % :chosen "screen"))
                  (canvas/adopt-candidate "result-1")
                  (canvas/deliver-gui "assets-1" {:gui "/screen.gui"}))
        result (canvas/node-by-id model "result-1")
        chosen (canvas/adopt-components model "assets-1" sheet)]
    (is (= [screen] (:candidates result)))
    (is (= [sheet] (:asset-candidates result)))
    (is (= model (canvas/merge-candidates model "action-1" [sheet screen])))
    (is (= "sheet" (:source-candidate (canvas/node-by-id chosen "assets-1"))))
    (is (= "screen" (:chosen (canvas/node-by-id chosen "result-1"))))
    (is (:gui-stale (canvas/node-by-id chosen "gui_assets-1")))
    (is (:gui-stale (canvas/node-by-id (canvas/adopt-candidate model "result-1") "gui_assets-1")))
    (is (= [sheet] (:asset-candidates (canvas/node-by-id (canvas/merge-candidates base "assets-1" [sheet]) "result-1"))))))

;; Connections must remain directional and unambiguous for reply attribution.
(deftest linear-connections-test
  (let [model (canvas/initial-canvas)]
    (is (= 2 (count (:edges (canvas/connect-nodes model "action-1" "result-1")))))
    (doseq [[a b] [["assets-1" "action-1"] ["action-1" "assets-1"] ["result-1" "result-1"] ["missing" "result-1"]]]
      (is (thrown? Exception (canvas/connect-nodes model a b))))
    (is (thrown? Exception (canvas/connect-nodes (canvas/add-flow model) "action-2" "result-1")))))

;; Zoom preserves the world point under the cursor and respects usable limits.
(deftest zoom-anchor-test
  (let [old {:x 40.0 :y -30.0 :zoom 0.8}
        next (canvas/zoom-about old 1.5 240.0 170.0)]
    (is (< (Math/abs (- (/ (- 240.0 (:x old)) (:zoom old)) (/ (- 240.0 (:x next)) (:zoom next)))) 0.00001))
    (is (< (Math/abs (- (/ (- 170.0 (:y old)) (:zoom old)) (/ (- 170.0 (:y next)) (:zoom next)))) 0.00001))
    (is (= 1.8 (:zoom (canvas/zoom-about old 100.0 0.0 0.0))))
    (is (= 0.25 (:zoom (canvas/zoom-about old 0.001 0.0 0.0))))))

;; Replayed snapshots must not duplicate candidates or replace adoption.
(deftest candidate-adoption-test
  (let [a {:id "a" :image-path "refs/a.png" :text "first" :files [{:path "refs/a.png" :source "/tmp/a.png" :name "a.png"}]}
        b {:id "b" :text "second" :files []}
        model (-> (canvas/initial-canvas)
                  (canvas/merge-candidates "action-1" [a b])
                  (canvas/update-node "result-1" #(assoc % :chosen "a"))
                  (canvas/adopt-candidate "result-1"))
        replay (canvas/merge-candidates model "action-1" [a b])]
    (is (= 2 (count (results/cards replay "action-1"))))
    (is (= 1 (count (:candidates (canvas/node-by-id replay "result-1")))))
    (is (= "a" (:chosen (canvas/node-by-id replay "result-1"))))
    (is (= (:files a) (:assets (canvas/node-by-id replay "assets-1"))))
    (is (= "a" (:source-candidate (canvas/node-by-id replay "assets-1"))))
    (is (thrown? Exception (canvas/adopt-candidate (canvas/initial-canvas) "result-1")))
    (is (thrown? Exception
                 (-> replay
                     (canvas/update-node "result-1" #(assoc % :chosen "b"))
                     (canvas/adopt-candidate "result-1"))))))

;; Only completed public outputs become candidates, scoped to their own files.
(deftest snapshot-output-test
  (let [snapshot {:thread {:referencedFiles [{:source "/tmp/a.png" :path "refs/a.png"}
                                             {:source "/tmp/private.png" :path "refs/private.png"}]
                           :turns [{:id "partial" :status "inProgress" :items [{:type "agentMessage" :text "unfinished"}]}
                                   {:id "complete" :status "completed"
                                    :items [{:type "agentMessage" :phase "analysis" :text "/tmp/private.png"}
                                            {:type "agentMessage" :phase "commentary" :text "progress"}
                                            {:type "agentMessage" :text "[candidate](/tmp/a.png)"}]}]}}
        result (canvas/snapshot-candidates snapshot "thread-a")]
    (is (= 1 (count result)))
    (is (= "refs/a.png" (:image-path (first result))))
    (is (= "thread-a/complete/refs/a.png" (:id (first result))))
    (is (not= (:id (first result)) (:id (first (canvas/snapshot-candidates snapshot "thread-b")))))))

;; Independent flows cannot receive another action's candidates or card context.
(deftest card-isolation-test
  (let [model (-> (canvas/initial-canvas) canvas/add-flow
                  (canvas/merge-candidates "action-2" [{:id "second" :text "second"}]))
        state {:canvas model :context {:path "/shop.gui" :selectedNodeIds ["buy"]}}]
    (is (= [] (:candidates (canvas/node-by-id model "result-1"))))
    (is (= "second" (-> (canvas/node-by-id model "result-2") :candidates first :id)))
    (is (= "result-2" (get-in (workbench/card-context state "result-2") [:canvasCard :id])))
    (is (= "action-2" (get-in (workbench/card-context state "result-2") [:canvasCard :upstream :id])))
    (is (= ["buy"] (:selectedNodeIds (workbench/card-context state "result-2"))))))

;; Internal analysis must not leak into public chat.
(deftest public-transcript-test
  (is (= "Codex\n公开结果"
         (workbench/transcript {:thread {:turns [{:items [{:type "agentMessage" :phase "analysis" :text "internal"}
                                                          {:type "agentMessage" :phase "final_answer" :text "公开结果"}]}]}}))))

;; Native image deliveries work without Markdown; copied links avoid duplicates.
(deftest native-image-candidate-test
  (let [snapshot {:thread {:referencedFiles [{:source "/tmp/generated.png" :path "refs/generated.png"}
                                             {:source "/tmp/copied.png" :path "refs/copied.png"}]
                           :turns [{:id "image" :status "completed"
                                    :items [{:type "imageGeneration" :savedPath "/tmp/generated.png"}
                                            {:type "agentMessage" :text "已生成图片"}]}]}}
        copied (assoc-in snapshot [:thread :turns 0 :items 1 :text] "![候选](/tmp/copied.png)")]
    (is (= ["refs/generated.png"] (mapv :image-path (canvas/snapshot-candidates snapshot "thread"))))
    (is (= ["refs/copied.png"] (mapv :image-path (canvas/snapshot-candidates copied "thread"))))))

;; Fit the whole card, including cards with negative positions.
(deftest focus-card-bounds-test
  (doseq [[x y width height] [[0.0 0.0 1000.0 700.0] [-200.0 750.0 500.0 320.0] [900.0 -700.0 200.0 180.0]]]
    (let [view (canvas/focus-viewport x y 268.0 450.0 width height)
          left (+ (:x view) (* x (:zoom view)))
          top (+ (:y view) (* y (:zoom view)))]
      (is (<= 0.0 left))
      (is (<= 0.0 top))
      (is (<= (+ left (* 268.0 (:zoom view))) width))
      (is (<= (+ top (* 450.0 (:zoom view))) height))
      (is (<= (:zoom view) 1.8)))))

;; Selection and zoom retain content; changed attachments invalidate it.
(deftest card-content-invalidation-test
  (let [state {:canvas (canvas/initial-canvas) :sessions {}}
        key (canvas/card-content-key state)]
    (is (= key (canvas/card-content-key (assoc-in state [:canvas :selected] "result-1"))))
    (is (= key (canvas/card-content-key (update state :canvas canvas/update-node "result-1" #(assoc % :chosen "a")))))
    (is (= key (canvas/card-content-key (assoc-in state [:canvas :viewport :zoom] 1.4))))
    (is (not= key (canvas/card-content-key (assoc-in state [:sessions "action-1" :attachments] [{:name "reference.png"}]))))))

;; Real JavaFX selection and double-click retain the same card and ImageView.
(deftest card-selection-and-double-click-retain-images-test
  (ui/run-now
    (let [file (java.io.File/createTempFile "canvas-preview" ".png")
          _ (javax.imageio.ImageIO/write (java.awt.image.BufferedImage. 2 2 java.awt.image.BufferedImage/TYPE_INT_ARGB) "png" file)
          staff [(roles/new-role "planning" #{})]
          model (canvas/add-person (canvas/update-node (canvas/initial-canvas) "action-1" #(assoc % :prompt "输出方案")))
          model (canvas/update-node model (:selected model) #(roles/assign-staff % (first staff)))
          state (atom {:staff staff :canvas (canvas/update-node model "result-1"
                                                                #(assoc % :candidates [{:id "a" :text "Candidate" :image-path "a.png" :preview (str (.toURI file))}] :chosen "a"))})
          view-ref (atom nil)
          change! (fn change-test-canvas [f] (swap! state canvas/change-state f))
          select! (fn select-test-card [id]
                    (change! #(assoc % :selected id))
                    ((:render! @view-ref)))
          view (canvas/make-canvas state change! select! (fn focus-test []) (fn report-test [_])
                                   (fn produce-test [_]) (fn open-test [_ _]) (fn gui-test [_ _ _]) (fn attach-test []) (constantly staff) (fn assign-test [_ _]) (fn work-test [_ _ _]))
          root ^javafx.scene.layout.BorderPane (:root view)]
      (try
        (reset! view-ref view)
        (javafx.scene.Scene. root 1000.0 800.0)
        (.resize root 1000.0 800.0)
        (.applyCss root) (.layout root)
        (let [card (.lookup root "#canvas-card-result-1")
              image (.lookup card "ImageView")]
          (is (some? image))
          (select! "result-1")
          (is (identical? card (.lookup root "#canvas-card-result-1")))
          (is (identical? image (.lookup card "ImageView")))
          (javafx.event.Event/fireEvent image
                                        (javafx.scene.input.MouseEvent. javafx.scene.input.MouseEvent/MOUSE_CLICKED
                                                                        30.0 30.0 30.0 30.0 javafx.scene.input.MouseButton/PRIMARY 2
                                                                        false false false false false false false false false true nil))
          (is (identical? card (.lookup root "#canvas-card-result-1")))
          (is (identical? image (.lookup card "ImageView")))
          (is (= "a" (:chosen (canvas/node-by-id (:canvas @state) "result-1"))))
          (is (not= 0.85 (get-in @state [:canvas :viewport :zoom]))))
        (finally (.delete file))))))

;; Deletion removes edges and prevents reuse of deleted card IDs.
(deftest delete-card-and-recreate-flow-test
  (let [original (canvas/add-flow (canvas/initial-canvas))
        model (canvas/remove-node (assoc original :selected "result-2") "result-2")
        cleared (reduce canvas/remove-node original (mapv :id (:nodes original)))
        recreated (canvas/add-flow cleared)]
    (is (nil? (canvas/node-by-id model "result-2")))
    (is (= 5 (count (:nodes model))))
    (is (= 2 (count (:edges model))))
    (is (= "action-1" (:selected model)))
    (is (= [] (:nodes cleared)))
    (is (nil? (:selected cleared)))
    (is (= "action-3" (:selected recreated)))
    (is (= 3 (count (:nodes recreated))))))

;; Middle drag works over card content; left drag on empty space cannot pan.
(deftest middle-button-pan-and-context-delete-test
  (ui/run-now
    (let [state (atom {:canvas (canvas/initial-canvas) :sessions {"action-1" {:draft "keep"}}})
          view-ref (atom nil)
          change! (fn change-controls [f] (swap! state canvas/change-state f))
          select! (fn select-controls [id] (change! #(assoc % :selected id)) ((:render! @view-ref)))
          view (canvas/make-canvas state change! select! (fn focus-controls []) (fn report-controls [_])
                                   (fn produce-controls [_]) (fn open-controls [_ _]) (fn gui-test [_ _ _]) (fn attach-controls []) (constantly []) (fn assign-controls [_ _]) (fn work-controls [_ _ _]))
          root ^javafx.scene.layout.BorderPane (:root view)
          stage (javafx.stage.Stage.)
          mouse! (fn fire-mouse [target kind button x y]
                   (javafx.event.Event/fireEvent target
                                                 (javafx.scene.input.MouseEvent. kind x y x y button 1
                                                                                 false false false false (= button javafx.scene.input.MouseButton/PRIMARY)
                                                                                 (= button javafx.scene.input.MouseButton/MIDDLE) false false false true nil)))]
      (reset! view-ref view)
      (try
        (.setScene stage (javafx.scene.Scene. root 1000.0 800.0))
        (.show stage)
        (.applyCss root) (.layout root)
        (let [viewport (.lookup root "#ai-canvas-viewport")
              header (.lookup root "#canvas-drag-action-1")
              original (:canvas @state)]
          (mouse! viewport javafx.scene.input.MouseEvent/MOUSE_PRESSED javafx.scene.input.MouseButton/PRIMARY 10.0 10.0)
          (mouse! viewport javafx.scene.input.MouseEvent/MOUSE_DRAGGED javafx.scene.input.MouseButton/PRIMARY 70.0 50.0)
          (mouse! viewport javafx.scene.input.MouseEvent/MOUSE_RELEASED javafx.scene.input.MouseButton/PRIMARY 70.0 50.0)
          (is (= (:viewport original) (get-in @state [:canvas :viewport])))
          (mouse! header javafx.scene.input.MouseEvent/MOUSE_PRESSED javafx.scene.input.MouseButton/MIDDLE 10.0 10.0)
          (mouse! header javafx.scene.input.MouseEvent/MOUSE_DRAGGED javafx.scene.input.MouseButton/MIDDLE 70.0 50.0)
          (mouse! header javafx.scene.input.MouseEvent/MOUSE_RELEASED javafx.scene.input.MouseButton/MIDDLE 70.0 50.0)
          (is (= (+ 60.0 (get-in original [:viewport :x])) (get-in @state [:canvas :viewport :x])))
          (is (= (+ 40.0 (get-in original [:viewport :y])) (get-in @state [:canvas :viewport :y])))
          (is (= (:nodes original) (get-in @state [:canvas :nodes])))
          (is (= javafx.scene.Cursor/DEFAULT (.getCursor viewport)))
          (mouse! header javafx.scene.input.MouseEvent/MOUSE_PRESSED javafx.scene.input.MouseButton/PRIMARY 10.0 10.0)
          (mouse! header javafx.scene.input.MouseEvent/MOUSE_DRAGGED javafx.scene.input.MouseButton/PRIMARY 40.0 30.0)
          (mouse! header javafx.scene.input.MouseEvent/MOUSE_DRAGGED javafx.scene.input.MouseButton/PRIMARY 70.0 50.0)
          (mouse! header javafx.scene.input.MouseEvent/MOUSE_RELEASED javafx.scene.input.MouseButton/PRIMARY 70.0 50.0)
          (is (= 1 (count (:canvas-undo @state))))
          (.fire ^javafx.scene.control.Button (.lookup root "#canvas-undo"))
          (is (= 0.0 (:x (canvas/node-by-id (:canvas @state) "action-1"))))
          (.fire ^javafx.scene.control.Button (.lookup root "#canvas-redo"))
          (is (pos? (:x (canvas/node-by-id (:canvas @state) "action-1"))))
          (javafx.event.Event/fireEvent (.lookup root "#canvas-card-action-1")
                                        (javafx.scene.input.ContextMenuEvent. javafx.scene.input.ContextMenuEvent/CONTEXT_MENU_REQUESTED 20.0 20.0 100.0 100.0 false nil))
          (let [menu ^javafx.scene.control.ContextMenu (first (filterv #(instance? javafx.scene.control.ContextMenu %) (vec (javafx.stage.Window/getWindows))))
                delete ^javafx.scene.control.MenuItem (first (filterv #(= "canvas-delete-action-1" (.getId ^javafx.scene.control.MenuItem %)) (.getItems menu)))]
            (is (.isShowing menu))
            (swap! state assoc :busy #{"action-1"})
            (.fire delete)
            (is (some? (canvas/node-by-id (:canvas @state) "action-1")))
            (swap! state assoc :busy #{})
            (.fire delete)
            (.hide menu)
            (is (nil? (.lookup root "#canvas-card-action-1")))
            (is (= "assets-1" (get-in @state [:canvas :selected])))
            (is (= "keep" (get-in @state [:sessions "action-1" :draft])))))
        (change! #(reduce canvas/remove-node % (mapv :id (:nodes %))))
        ((:render! view))
        ((:fit! view))
        (is (= [] (get-in @state [:canvas :nodes])))
        (finally (.close stage))))))

;; Right-click creates one Person; invalid setup blocks pins and task buttons.
(deftest person-card-controls-test
  (ui/run-now
    (let [staff [(roles/new-role "planning" #{})]
          state (atom {:canvas (canvas/initial-canvas) :sessions {} :staff staff})
          view-ref (atom nil)
          work (atom nil)
          change! (fn change-person [f] (swap! state canvas/change-state f))
          select! (fn select-person [id] (change! #(assoc % :selected id)) ((:render! @view-ref)))
          view (canvas/make-canvas state change! select! (fn focus-person []) (fn report-person [_])
                                   (fn produce-person [_]) (fn open-person [_ _]) (fn gui-test [_ _ _]) (fn attach-person [])
                                   (constantly staff) (fn assign-person [_ _]) (fn work-person [op id fields] (reset! work [op id fields])))
          root ^javafx.scene.layout.BorderPane (:root view)
          stage (javafx.stage.Stage.)]
      (reset! view-ref view)
      (try
        (.setScene stage (javafx.scene.Scene. root 1000.0 800.0))
        (.show stage)
        (javafx.event.Event/fireEvent (.lookup root "#ai-canvas-viewport")
                                      (javafx.scene.input.ContextMenuEvent. javafx.scene.input.ContextMenuEvent/CONTEXT_MENU_REQUESTED 80.0 650.0 100.0 650.0 false nil))
        (let [menu ^javafx.scene.control.ContextMenu (first (filterv #(instance? javafx.scene.control.ContextMenu %) (vec (javafx.stage.Window/getWindows))))]
          (is (.isShowing menu))
          (javafx.event.Event/fireEvent root
                                        (javafx.scene.input.MouseEvent. javafx.scene.input.MouseEvent/MOUSE_PRESSED
                                                                        10.0 10.0 10.0 10.0 javafx.scene.input.MouseButton/PRIMARY 1
                                                                        false false false false true false false false false true nil))
          (is (not (.isShowing menu)))
          (is (= 3 (count (get-in @state [:canvas :nodes])))))
        (javafx.event.Event/fireEvent (.lookup root "#ai-canvas-viewport")
                                      (javafx.scene.input.ContextMenuEvent. javafx.scene.input.ContextMenuEvent/CONTEXT_MENU_REQUESTED 80.0 650.0 100.0 650.0 false nil))
        (let [menu ^javafx.scene.control.ContextMenu (first (filterv #(instance? javafx.scene.control.ContextMenu %) (vec (javafx.stage.Window/getWindows))))]
          (.fire ^javafx.scene.control.MenuItem (first (filterv #(= "canvas-create-person" (.getId ^javafx.scene.control.MenuItem %)) (vec (.getItems menu)))))
          (.hide menu))
        (.applyCss root) (.layout root)
        (let [person (get-in @state [:canvas :selected])
              viewport (get-in @state [:canvas :viewport])
              model (canvas/node-by-id (:canvas @state) person)]
          (is (< 0.0 (+ (:x viewport) (* (:x model) (:zoom viewport))) 1000.0))
          (is (some? (.lookup root (str "#canvas-staff-" person))))
          (is (.isDisabled ^javafx.scene.control.Button (.lookup root (str "#canvas-out-" person))))
          (is (.isDisabled ^javafx.scene.control.Button (.lookup root (str "#canvas-run-person-" person))))
          (is (= 4 (count (get-in @state [:canvas :nodes]))))
          (is (= [] (filterv #(= person (:from %)) (get-in @state [:canvas :edges]))))
          (change! #(-> % (canvas/update-node person (fn assign [card] (roles/assign-staff card (first staff))))
                        (canvas/update-node "action-1" (fn describe [card] (assoc card :prompt "设计任务")))
                        (graph/connect staff person "action-1")))
          ((:render! view)) (.applyCss root) (.layout root)
          (.fire ^javafx.scene.control.Button (.lookup root (str "#canvas-run-person-" person)))
          (is (= [:run person nil] @work))
          (change! canvas/add-flow)
          ((:render! view))
          (let [action (get-in @state [:canvas :selected])]
            (change! #(canvas/connect-nodes % person action))
            ((:render! view))
            (.applyCss root) (.layout root)
            (is (some? (.lookup root (str "#person-task-" action)))))
          (swap! state assoc-in [:sessions "action-1" :execution] {:status "running" :turn-id "t"})
          ((:render! view))
          (.applyCss root) (.layout root)
          (is (= "执行中" (.getText ^javafx.scene.control.Label (.lookup root "#canvas-status-action-1"))))
          (is (nil? (.lookup root "#canvas-compose-action-1")))
          (is (some? (.lookup (.lookup root "#canvas-card-action-1") "ProgressBar"))))
        (finally (.close stage))))))

;; Orphan Actions cannot assign staff or run, and disable downstream controls.
;; A valid Person connection restores them; removing it blocks them again.
(deftest person-required-controls-test
  (ui/run-now
    (let [staff [(roles/new-role "planning" #{})]
          model (-> (canvas/initial-canvas)
                    (assoc :workflow :document :template "gui")
                    (canvas/update-node "action-1" #(-> % (assoc :prompt "输出界面") (roles/assign-staff (first staff))))
                    (canvas/update-node "result-1" #(assoc % :candidates [{:id "candidate" :text "方案" :image-path "screen.png"}] :chosen "candidate"))
                    (canvas/deliver-gui "assets-1" {:gui "/screen.gui"})
                    (assoc :selected "action-1"))
          state (atom {:canvas model :staff staff})
          change! (fn change-gated-canvas [f] (swap! state canvas/change-state f))
          view (canvas/make-canvas state change! (fn select-gated-card [id] (change! #(assoc % :selected id)))
                                   (fn focus-gated-card []) (fn report-gated-card [_])
                                   (fn produce-gated-card [_]) (fn open-gated-card [_ _]) (fn gui-gated-card [_ _ _])
                                   (fn attach-gated-card []) (constantly staff) (fn assign-gated-card [_ _]) (fn work-gated-card [_ _ _]))
          details (canvas/make-details state (fn choose-gated-card [_ _]) (fn open-gated-details [_ _])
                                       (fn gui-gated-details [_ _ _]) (fn save-gated-role [_ _]) (constantly staff)
                                       (fn assign-gated-details [_ _]) (fn report-gated-details [_]) (fn work-gated-details [_ _ _]))
          root ^javafx.scene.layout.BorderPane (:root view)
          detail-root ^javafx.scene.layout.BorderPane (:root details)]
      (javafx.scene.Scene. root 1000.0 800.0)
      (javafx.scene.Scene. detail-root 360.0 800.0)
      (.applyCss root) (.layout root)
      ((:refresh! details))
      (.applyCss detail-root) (.layout detail-root)
      (is (nil? (.lookup root "#canvas-staff-action-1")))
      (is (nil? (.lookup root "#canvas-compose-action-1")))
      (is (nil? (.lookup detail-root "#ai-detail-staff-action-1")))
      (is (nil? (.lookup detail-root "#action-task-run")))
      (is (not (.isDisabled (.lookup detail-root "#action-task-save"))))
      (doseq [id ["canvas-out-action-1" "canvas-confirm-result-result-1"
                  "canvas-produce-assets-1" "canvas-gui-open-gui_assets-1" "canvas-gui-refresh-gui_assets-1"]]
        (is (.isDisabled (.lookup root (str "#" id)))))
      (doseq [id ["action-1" "result-1" "assets-1" "gui_assets-1"]]
        (is (= 0.55 (.getOpacity (.lookup root (str "#canvas-card-" id))))))
      (change! #(let [next (canvas/add-person (assoc % :selected "action-1"))
                      person (:selected next)]
                  (-> next (canvas/update-node person (fn configure-person [card] (roles/assign-staff card (first staff))))
                      (graph/connect staff person "action-1"))))
      ((:render! view))
      (.applyCss root) (.layout root)
      (is (.isDisabled (.lookup root "#canvas-out-action-1")))
      (is (not (.isDisabled (.lookup root "#canvas-confirm-result-result-1"))))
      (is (not (.isDisabled (.lookup root "#canvas-gui-open-gui_assets-1"))))
      (let [person (get-in @state [:canvas :selected])]
        (is (not (.isDisabled (.lookup root (str "#canvas-run-person-" person)))))
        (change! #(canvas/remove-node % person)))
      ((:render! view))
      (is (.isDisabled (.lookup root "#canvas-out-action-1")))
      (is (.isDisabled (.lookup root "#canvas-produce-assets-1"))))))

;; Undo preserves asynchronous outputs and archived sessions.
(deftest canvas-undo-redo-test
  (let [start {:canvas (canvas/initial-canvas) :sessions {"action-1" {:draft "keep"}}}
        moved (canvas/change-state start #(canvas/update-node % "action-1" (fn move-card [node] (assoc node :x 100.0))))
        completed (update moved :canvas canvas/update-node "action-1" #(assoc % :prompt "new reply"))
        undone (canvas/history-state completed :undo)
        redone (canvas/history-state undone :redo)
        deleted (canvas/change-state redone #(canvas/remove-node % "result-1"))
        restored (canvas/history-state deleted :undo)
        undo-card (canvas/node-by-id (:canvas undone) "action-1")
        redo-card (canvas/node-by-id (:canvas redone) "action-1")]
    (is (= 0.0 (:x undo-card)))
    (is (= "new reply" (:prompt undo-card)))
    (is (= 100.0 (:x redo-card)))
    (is (= (get-in redone [:canvas :nodes]) (get-in restored [:canvas :nodes])))
    (is (= (set (get-in redone [:canvas :edges])) (set (get-in restored [:canvas :edges]))))
    (is (= (:sessions start) (:sessions restored)))
    (is (= [] (:canvas-redo (canvas/change-state restored canvas/add-flow))))
    (is (= (:canvas-undo moved) (:canvas-undo (canvas/change-state moved #(assoc % :selected "result-1")))))))

;; Only produced Results unlock viewing and determine the Action menu mode.
(deftest action-result-menu-test
  (ui/run-now
    (let [events (atom []) state (atom {:canvas (canvas/initial-canvas)})
          view (canvas/make-canvas state #(swap! state update :canvas %) (fn select-card [_])
                                   (fn focus-card []) (fn report-card [_]) (fn produce-card [_])
                                   (fn open-card [_ _]) (fn gui-card [_ _ _]) (fn attach-card [])
                                   (constantly []) (fn assign-card [_ _])
                                   (fn work-card [op id fields] (swap! events conj [op id fields])))
          root (:root view) stage (javafx.stage.Stage.)]
      (try
        (.setScene stage (javafx.scene.Scene. root 1000.0 800.0)) (.show stage)
        (doseq [n [0 1 2]]
          (swap! state assoc :canvas (canvas/merge-candidates (canvas/initial-canvas) "action-1"
                                                              (mapv #(hash-map :id (str %) :text (str "方案" %)) (range n))))
          ((:render! view)) (.applyCss root) (.layout root)
          (javafx.event.Event/fireEvent (.lookup root "#canvas-card-action-1")
                                        (javafx.scene.input.ContextMenuEvent. javafx.scene.input.ContextMenuEvent/CONTEXT_MENU_REQUESTED 20.0 20.0 100.0 100.0 false nil))
          (let [menu ^javafx.scene.control.ContextMenu (first (filterv #(and (instance? javafx.scene.control.ContextMenu %) (.isShowing %)) (vec (javafx.stage.Window/getWindows))))
                item ^javafx.scene.control.MenuItem (first (filterv #(= "canvas-action-results-action-1" (.getId ^javafx.scene.control.MenuItem %)) (.getItems menu)))]
            (is (= (if (= 2 n) "对比方案" "查看方案") (.getText item)))
            (is (= (zero? n) (.isDisable item)))
            (.hide menu)
            (when (pos? n) (.fire item))))
        (is (= [[:view-action-results "action-1" nil] [:view-action-results "action-1" nil]] @events))
        (finally ((:dispose! view)) (.close stage))))))
