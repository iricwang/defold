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


(ns editor.ai-composer
  (:import [javafx.css PseudoClass]
           [javafx.event EventHandler]
           [javafx.geometry Pos]
           [javafx.scene.control Button Label TextArea Tooltip]
           [javafx.scene.input KeyCode KeyEvent]
           [javafx.scene.layout HBox Priority VBox]
           [javafx.scene.shape SVGPath]))

(set! *warn-on-reflection* true)

(defn make-composer [input attach clear send attachment-label status collapsed-change!]
  (let [input ^TextArea input
        attach ^Button attach
        clear ^Button clear
        send ^Button send
        root (VBox. 6.0)
        form (VBox. 8.0)
        toggle (Button. "⌄")
        heading (Label. "✦  图片与界面设计")
        row (HBox. 8.0)
        footer (HBox. 8.0)
        expanded (atom true)
        apply! (fn apply-composer [open]
                 (reset! expanded open)
                 (doseq [node [toggle heading footer attachment-label]]
                   (.setVisible ^javafx.scene.Node node open)
                   (.setManaged ^javafx.scene.Node node open))
                 (.pseudoClassStateChanged form (PseudoClass/getPseudoClass "compact") (not open))
                 (.setPrefRowCount input (if open 3 1))
                 (.setPrefHeight input (if open 82.0 36.0))
                 (.setMinHeight input (if open 82.0 36.0))
                 (.setMaxHeight input (if open 82.0 36.0))
                 (.setMaxWidth root (if open 640.0 380.0))
                 (.setVisible ^Label attachment-label (and open (not (.isEmpty (.getText ^Label attachment-label)))))
                 (.setManaged ^Label attachment-label (.isVisible ^Label attachment-label))
                 (.setPromptText input (if open "描述目标，添加参考图片或文档…" "请描述你想生成的内容"))
                 (collapsed-change! (not open)))
        expand! (fn expand-composer [] (apply! true) (.requestFocus input))]
    (doseq [[node id style] [[root "ai-composer" "ai-composer-shell"] [form "ai-composer-form" "ai-composer-form"]
                             [toggle "ai-composer-collapse" "ai-composer-toggle"] [input "ai-input" "ai-composer-input"]
                             [send "ai-send" "ai-send"] [heading "ai-composer-heading" "ai-composer-heading"]]]
      (.setId ^javafx.scene.Node node id)
      (.add (.getStyleClass ^javafx.scene.Node node) style))
    (.setAlignment root Pos/CENTER)
    (.setMaxHeight root javafx.scene.layout.Region/USE_PREF_SIZE)
    (.setAlignment row Pos/CENTER)
    (.setAlignment footer Pos/CENTER_LEFT)
    (HBox/setHgrow input Priority/ALWAYS)
    (.setText send "")
    (.setAccessibleText send "发送到 AI")
    (.setTooltip send (Tooltip. "发送 · Ctrl / ⌘ + Enter"))
    (.setGraphic send (doto (SVGPath.) (.setContent "M10 0 L1 11 L8 11 L6 21 L18 8 L11 8 L14 0 Z")
                            (-> .getStyleClass (.add "send-glyph"))))
    (.setText attach "+ 图片 / 文档")
    (.setText clear "清除附件")
    (doseq [button [attach clear]] (.add (.getStyleClass ^Button button) "button-small"))
    (.add (.getStyleClass ^Label attachment-label) "ai-muted")
    (.add (.getStyleClass ^Label status) "ai-error")
    (.addAll (.getChildren footer) ^java.util.Collection (identity [attach clear]))
    (.addAll (.getChildren row) ^java.util.Collection (identity [input send]))
    (.addAll (.getChildren form) ^java.util.Collection (identity [heading row attachment-label footer]))
    (.addAll (.getChildren root) ^java.util.Collection (identity [status toggle form]))
    (.setOnAction toggle (reify EventHandler (handle [_ _] (apply! false))))
    (.setTooltip toggle (Tooltip. "收起输入框"))
    (.setOnMouseClicked input (reify EventHandler (handle [_ _] (when-not @expanded (expand!)))))
    (.setOnKeyPressed input
                      (reify EventHandler
                        (handle [_ event]
                          (let [e ^KeyEvent event]
                            (cond
                              (and (= KeyCode/ENTER (.getCode e)) (.isShortcutDown e)) (do (.fire send) (.consume e))
                              (= KeyCode/ESCAPE (.getCode e)) (do (apply! false) (.consume e)))))))
    (apply! true)
    {:root root :expand! expand! :set-expanded! apply!}))
