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

(ns editor.ai-card-view-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [editor.ai-card-view :as card-view]
            [editor.ui :as ui])
  (:import [java.nio.file Files]
           [javafx.scene Scene]
           [javafx.scene.control TreeItem TreeView]
           [javafx.scene.layout AnchorPane]
           [javafx.stage Stage]))

;; Both card kinds enumerate their files; outside-project paths stay unread.
(deftest card-resource-entries-test
  (let [root (.toFile (Files/createTempDirectory "card-resources" (make-array java.nio.file.attribute.FileAttribute 0)))
        file (io/file root "notes.md")
        outside (java.io.File/createTempFile "outside-card" ".md")]
    (try
      (spit file "# 交付文档" :encoding "UTF-8")
      (let [result {:kind :result :candidates [{:id "a" :text "人工版本" :files [{:path "/notes.md" :name "文档"}
                                                                             {:path (.getAbsolutePath outside) :name "外部文件"}]}]
                    :generated-candidate {:text "原稿"} :confirmed-candidate {:text "已确认稿"}}
            entries (card-view/entries root result)
            assets (card-view/entries root {:kind :assets :proposal-text "交付内容" :assets []
                                            :document-output "/notes.md" :production {:paths ["/missing.gui"]}})]
        (is (= [:body :original :confirmed] (mapv :id (subvec entries 0 3))))
        (is (= file (:file (nth entries 3))))
        (is (:missing (nth entries 4)))
        (is (= 3 (count assets)))
        (is (= 1 (count (filterv :missing assets)))))
      (finally (.delete file) (.delete root) (.delete outside)))))

;; The native tree switches Markdown, JSON and images in one window.
(deftest resource-window-preview-test
  (let [root (.toFile (Files/createTempDirectory "card-window" (make-array java.nio.file.attribute.FileAttribute 0)))
        json (io/file root "data.json") image (io/file root "image.png")]
    (try
      (spit json "{\"reward\": 10}" :encoding "UTF-8")
      (javax.imageio.ImageIO/write (java.awt.image.BufferedImage. 4 4 java.awt.image.BufferedImage/TYPE_INT_ARGB) "png" image)
      (ui/run-now
        (let [owner (Stage.)
              card (atom {:kind :assets :title "交付内容" :proposal-text "# 长方案\n\n正文"
                          :assets [{:path "/data.json" :name "数值配置"} {:path "/image.png" :name "效果图"}]})
              view (do (.setScene owner (Scene. (AnchorPane.) 800.0 600.0))
                       (card-view/open! owner root #(deref card) {:resource-types {} :markdown-opts {} :open-file! (fn open-test [_])}))
              stage ^Stage (:stage view)
              pane (.getRoot (.getScene stage))
              tree ^TreeView (.lookup pane "#ai-card-resource-tree")
              leaves (mapv #(.getChildren ^TreeItem %) (.getChildren (.getRoot tree)))
              files (second leaves)]
          (try
            (.applyCss pane) (.layout pane)
            (is (.contains (.getStylesheets pane) (str (io/resource "editor.css"))))
            (is (some? (.lookup pane "#ai-card-markdown-preview")))
            (.select (.getSelectionModel tree) ^TreeItem (first files))
            (.applyCss pane) (.layout pane)
            (is (= "{\"reward\": 10}" (.getText ^javafx.scene.control.TextArea (.lookup pane "#ai-card-text-preview"))))
            (.select (.getSelectionModel tree) ^TreeItem (second files))
            (.applyCss pane) (.layout pane)
            (is (some? (.lookup pane "#ai-card-image-preview")))
            ((:refresh! view))
            (.applyCss pane) (.layout pane)
            (is (= "效果图" (:title (.getValue ^TreeItem (.getSelectedItem (.getSelectionModel tree))))))
            (is (some? (.lookup pane "#ai-card-image-preview")))
            (finally (.close stage) (.close owner)))))
      (finally (.delete json) (.delete image) (.delete root)))))

;; File saves reject conflicts and outside-project targets without truncation.
(deftest markdown-save-conflict-test
  (let [root (.toFile (Files/createTempDirectory "markdown-save" (make-array java.nio.file.attribute.FileAttribute 0)))
        file (io/file root "notes.md")
        outside (java.io.File/createTempFile "outside-markdown" ".md")]
    (try
      (spit file "# 原文" :encoding "UTF-8")
      (card-view/save-markdown! root file "# 原文" "# 修改")
      (is (= "# 修改" (slurp file :encoding "UTF-8")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"外部修改" (card-view/save-markdown! root file "# 原文" "旧草稿")))
      (is (= "# 修改" (slurp file :encoding "UTF-8")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"项目内" (card-view/save-markdown! root outside "" "内容")))
      (is (= ["notes.md"] (vec (.list root))))
      (finally (.delete file) (.delete outside) (.delete root)))))

;; Drafts survive preview, refresh and failed saves; closing guards edits.
(deftest markdown-window-edit-test
  (let [root (.toFile (Files/createTempDirectory "markdown-edit" (make-array java.nio.file.attribute.FileAttribute 0)))
        file (io/file root "notes.md")]
    (try
      (spit file "# 原文" :encoding "UTF-8")
      (ui/run-now
        (let [owner (doto (Stage.) (.setScene (Scene. (AnchorPane.) 800.0 600.0)))
              view (card-view/open! owner root (fn current-card [] {:kind :assets :title "文档" :assets [{:path "/notes.md"}]})
                                    {:resource-types {} :markdown-opts {}})
              stage ^Stage (:stage view) pane (.getRoot (.getScene stage))
              click! (fn click-control [id] (.fire ^javafx.scene.control.Button (.lookup pane id)) (.applyCss pane) (.layout pane))
              source! (fn source-control [] (.lookup pane "#ai-card-markdown-source"))]
          (try
            (.applyCss pane) (.layout pane)
            (click! "#ai-card-resource-edit")
            (.setText ^javafx.scene.control.TextArea (source!) "# 未保存修改")
            (is (.isDisabled ^TreeView (.lookup pane "#ai-card-resource-tree")))
            ((:refresh! view))
            (is (= "# 未保存修改" (.getText ^javafx.scene.control.TextArea (source!))))
            (let [event (javafx.stage.WindowEvent. stage javafx.stage.WindowEvent/WINDOW_CLOSE_REQUEST)]
              (.handle (.getOnCloseRequest stage) event)
              (is (.isConsumed event)))
            (click! "#ai-card-resource-edit")
            (is (some? (.lookup pane "#ai-card-markdown-preview")))
            (click! "#ai-card-resource-edit")
            (is (= "# 未保存修改" (.getText ^javafx.scene.control.TextArea (source!))))
            (click! "#ai-card-resource-save")
            (is (= "# 未保存修改" (slurp file :encoding "UTF-8")))
            (is (nil? (.lookup pane "#ai-card-markdown-source")))
            (is (not (.isDisabled ^TreeView (.lookup pane "#ai-card-resource-tree"))))
            (click! "#ai-card-resource-edit")
            (.setText ^javafx.scene.control.TextArea (source!) "我的草稿")
            (spit file "外部修改" :encoding "UTF-8")
            (click! "#ai-card-resource-save")
            (is (= "我的草稿" (.getText ^javafx.scene.control.TextArea (source!))))
            (is (= "外部修改" (slurp file :encoding "UTF-8")))
            (click! "#ai-card-resource-cancel")
            (is (nil? (.lookup pane "#ai-card-markdown-source")))
            (finally (.close stage) (.close owner)))))
      (finally (.delete file) (.delete root)))))

;; Result edits use the model callback; original snapshots stay read-only.
(deftest result-body-edit-test
  (ui/run-now
    (let [owner (doto (Stage.) (.setScene (Scene. (AnchorPane.) 800.0 600.0)))
          card (atom {:kind :result :title "方案" :candidates [{:id "one" :text "# 当前方案"}] :generated-candidate {:text "原稿"}})
          writes (atom [])
          view (card-view/open! owner "." #(deref card)
                                {:resource-types {} :markdown-opts {}
                                 :save-body! (fn save-body [expected text]
                                               (swap! writes conj [expected text])
                                               (swap! card assoc-in [:candidates 0 :text] text))})
          stage ^Stage (:stage view) pane (.getRoot (.getScene stage))
          tree ^TreeView (.lookup pane "#ai-card-resource-tree")]
      (try
        (.applyCss pane) (.layout pane)
        (.fire ^javafx.scene.control.Button (.lookup pane "#ai-card-resource-edit"))
        (.applyCss pane) (.layout pane)
        (.setText ^javafx.scene.control.TextArea (.lookup pane "#ai-card-markdown-source") "# 人工修改")
        (javafx.event.Event/fireEvent pane (javafx.scene.input.KeyEvent. javafx.scene.input.KeyEvent/KEY_PRESSED "" "" javafx.scene.input.KeyCode/S false true false false))
        (is (= [["# 当前方案" "# 人工修改"]] @writes))
        (is (= "# 人工修改" (get-in @card [:candidates 0 :text])))
        (let [folder ^TreeItem (first (.getChildren (.getRoot tree)))]
          (.select (.getSelectionModel tree) ^TreeItem (second (.getChildren folder))))
        (.applyCss pane) (.layout pane)
        (is (not (.isVisible ^javafx.scene.control.Button (.lookup pane "#ai-card-resource-edit"))))
        (finally (.close stage) (.close owner))))))
