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
           [javafx.scene.layout HBox Priority Region VBox]
           [javafx.scene.shape SVGPath]))

(set! *warn-on-reflection* true)

(defn make-composer [input attach clear send attachment-label collapsed-change!]
  (let [input ^TextArea input
        attach ^Button attach
        clear ^Button clear
        send ^Button send
        root (VBox. 6.0)
        form (VBox. 8.0)
        toggle (Button.)
        toggle-icon (doto (SVGPath.) (.setContent "M 1 2 L 5 6 L 9 2"))
        heading (Label. "✦  图片与界面设计")
        row (HBox. 8.0)
        footer (HBox. 8.0)
        spacer (Region.)
        save-icon (doto (SVGPath.) (.setContent "M 2 8 L 6 12 L 14 4"))
        expanded (atom true)
        apply! (fn apply-composer [open]
                 (reset! expanded open)
                 (.remove (.getChildren row) send)
                 (.remove (.getChildren footer) send)
                 (.add (.getChildren (if open footer row)) send)
                 (doseq [node [toggle heading footer attachment-label]]
                   (.setVisible ^javafx.scene.Node node open)
                   (.setManaged ^javafx.scene.Node node open))
                 (.pseudoClassStateChanged form (PseudoClass/getPseudoClass "compact") (not open))
                 (.setPrefRowCount input (if open 3 1))
                 (.setPrefHeight input (if open 56.0 36.0))
                 (.setMinHeight input (if open 56.0 36.0))
                 (.setMaxHeight input (if open 56.0 36.0))
                 (.setMaxWidth root (if open 736.0 380.0))
                 (.setVisible ^Label attachment-label (and open (not (.isEmpty (.getText ^Label attachment-label)))))
                 (.setManaged ^Label attachment-label (.isVisible ^Label attachment-label))
                 (.setPromptText input (if open "描述目标，添加参考图片或文档…" "请描述你想生成的内容"))
                 (collapsed-change! (not open)))
        expand! (fn expand-composer [] (apply! true) (.requestFocus input))]
    (doseq [[node id style] [[root "ai-composer" "ai-composer-shell"] [form "ai-composer-form" "ai-composer-form"]
                             [toggle "ai-composer-collapse" "ai-composer-toggle"] [input "ai-input" "ai-composer-input"]
                             [send "ai-send" "ai-send"] [heading "ai-composer-heading" "ai-composer-heading"]
                             [attach "ai-attach" "ai-composer-attach"] [clear "ai-clear" "ai-composer-clear"]
                             [save-icon "ai-composer-save-icon" "ai-composer-save-icon"]
                             [toggle-icon "ai-composer-toggle-icon" "ai-composer-toggle-icon"]]]
      (.setId ^javafx.scene.Node node id)
      (.add (.getStyleClass ^javafx.scene.Node node) style))
    (.setGraphic toggle toggle-icon)
    (.setAccessibleText toggle "收起输入框")
    (.setAlignment root Pos/CENTER)
    (.setMaxHeight root javafx.scene.layout.Region/USE_PREF_SIZE)
    (.setAlignment row Pos/CENTER)
    (.setAlignment footer Pos/CENTER_LEFT)
    (HBox/setHgrow input Priority/ALWAYS)
    (HBox/setHgrow spacer Priority/ALWAYS)
    (.setMinWidth input 0.0)
    (.setWrapText input true)
    (.setText send "")
    (.setGraphic send save-icon)
    (.setAccessibleText send "保存任务内容")
    (.setTooltip send (Tooltip. "保存任务内容 · Ctrl / ⌘ + Enter"))
    (.setText attach "+")
    (.setTooltip attach (Tooltip. "添加图片或文档"))
    (.setAccessibleText attach "添加图片或文档")
    (.setText clear "清除附件")
    (doseq [button [attach clear]] (.add (.getStyleClass ^Button button) "button-small"))
    (.add (.getStyleClass ^Label attachment-label) "ai-muted")
    (.addAll (.getChildren footer) ^java.util.Collection (identity [attach heading spacer clear]))
    (.addAll (.getChildren row) ^java.util.Collection (identity [input]))
    (.addAll (.getChildren form) ^java.util.Collection (identity [row attachment-label footer]))
    (.addAll (.getChildren root) ^java.util.Collection (identity [toggle form]))
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
