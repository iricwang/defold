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

(ns editor.ai-workspace-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [dynamo.graph :as g]
            [editor.ai-document :as document]
            [editor.ai-workspace :as workspace]
            [editor.fs :as fs]
            [editor.handler :as handler]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [java.nio.file Files]
           [javafx.event Event]
           [javafx.scene Scene]
           [javafx.scene.control Button MenuItem ScrollPane Tab TabPane TextArea TreeItem TreeView]
           [javafx.scene.layout AnchorPane]
           [javafx.scene.paint Color]
           [javafx.scene.shape SVGPath]))

;; Right-clicking an unselected child must keep that child, dispose only its
;; siblings once, keeping the outer editor, other workspace and browser intact.
(deftest workspace-close-menu-scope-test
  (let [root (.toFile (Files/createTempDirectory "ai-close-tabs" (make-array java.nio.file.attribute.FileAttribute 0)))
        disposed (atom [])]
    (try
      (ui/run-now
        (let [parent (AnchorPane.)
              workspace (workspace/make-view! parent root {} {}
                                              (fn make-content [_ _ context _]
                                                {:close! (fn dispose-content [] (swap! disposed conj (:path context)))}))
              ^TabPane tabs (:tabs workspace)
              browser (:root (:browser workspace))
              outer (TabPane.)
              ai-tab (Tab. "AI" parent)
              engine-tab (Tab. "Engine" (AnchorPane.))
              other-tabs (TabPane.)
              other-tab (Tab. "Other workspace")]
          (.addAll (.getTabs outer) ^java.util.Collection [engine-tab ai-tab])
          (.add (.getTabs other-tabs) other-tab)
          (doseq [path ["/first.md" "/keep.md" "/third.md"]]
            ((:open! workspace) {:path path}))
          (let [[first-tab ^Tab keep-tab third-tab] (vec (.getTabs tabs))
                ^MenuItem close-others (second (.getItems (.getContextMenu keep-tab)))]
            (is (identical? third-tab (ui/selected-tab tabs)))
            (.fire close-others)
            (is (= [keep-tab] (vec (.getTabs tabs))))
            (is (= ["/first.md" "/third.md"] @disposed))
            (is (= [engine-tab ai-tab] (vec (.getTabs outer))))
            (is (= [other-tab] (vec (.getTabs other-tabs))))
            (is (identical? browser (:root (:browser workspace))))
            ((:open! workspace) {:path "/first.md"})
            (is (not (identical? first-tab (ui/selected-tab tabs))))
            (let [^MenuItem close-all (coll/first-where #(= "ai-tab-close-all" (.getId ^MenuItem %)) (.getItems (.getContextMenu keep-tab)))]
              (.fire close-all)
              (is (zero? (count (.getTabs tabs))))
              (is (= [engine-tab ai-tab] (vec (.getTabs outer))))
              (is (identical? browser (:root (:browser workspace))))
              ((:open! workspace) {:path "/after-close.md"})
              ;; A menu retained from a closed tab must not close new tabs.
              (.fire close-all)
              (is (= 1 (count (.getTabs tabs))))
              (is (some? (.getContextMenu ^Tab (ui/selected-tab tabs)))))
            ((:close! workspace))
            (is (= ["/first.md" "/third.md" "/keep.md" "/first.md" "/after-close.md"] @disposed)))))
      (finally (fs/delete-directory! root)))))

;; Batch closing must respect a veto and non-closable tabs, sharing normal tab
;; cleanup rather than silently removing the views from the list.
(deftest workspace-close-request-test
  (ui/run-now
    (let [tabs (TabPane.)
          vetoed (Tab. "Vetoed")
          pinned (doto (Tab. "Pinned") (.setClosable false))
          closeable (Tab. "Closeable")
          closed (atom [])]
      (doseq [^Tab tab [vetoed pinned closeable]]
        (.setOnClosed tab (ui/event-handler _ (swap! closed conj (.getText tab)))))
      (.setOnCloseRequest vetoed (ui/event-handler event (.consume event)))
      (.addAll (.getTabs tabs) ^java.util.Collection [vetoed pinned closeable])
      (workspace/close-tabs! tabs vetoed :all)
      (is (= [vetoed pinned] (vec (.getTabs tabs))))
      (is (= ["Closeable"] @closed)))))

;; Keyboard/menu commands use the inner context even with no tabs, so a disabled
;; command never falls through and closes the containing AI editor window.
(deftest workspace-close-command-context-test
  (ui/run-now
    (let [tabs (TabPane.)
          browser (AnchorPane.)]
      (workspace/configure-tabs! tabs browser)
      (workspace/configure-tabs! tabs browser)
      (g/with-auto-or-fake-evaluation-context evaluation-context
        (doseq [node [tabs browser]
                command [:window.tab.close :window.tab.close-others :window.tab.close-all]]
          (let [resolved (handler/active command [(ui/context node)] nil evaluation-context)]
            (is (= :editor.ai-workspace/tabs (:name (second resolved))))
            (is (not (handler/enabled? resolved evaluation-context)))))
        (let [first-tab (Tab. "First")
              second-tab (Tab. "Second")]
          (.addAll (.getTabs tabs) ^java.util.Collection [first-tab second-tab])
          (.select (.getSelectionModel tabs) second-tab)
          (let [resolved (handler/active :window.tab.close-others [(ui/context tabs)] nil evaluation-context)]
            (is (handler/enabled? resolved evaluation-context))
            (handler/run resolved)
            (is (= [second-tab] (vec (.getTabs tabs)))))
          (handler/run (handler/active :window.tab.close [(ui/context tabs)] nil evaluation-context))
          (is (zero? (count (.getTabs tabs)))))))))

;; Reloaded views and newly opened readers must share the stroked icons/theme.
(deftest markdown-theme-reload-test
  (let [root (.toFile (Files/createTempDirectory "ai-md-theme" (make-array java.nio.file.attribute.FileAttribute 0)))
        url (str (.toURI (io/file "resources/ai-canvas.css")))]
    (try
      (spit (io/file root "readme.md") "# 阅读主题\n\n正文" :encoding "UTF-8")
      (ui/run-now
        (workspace/reload-styles! url)
        (doseq [_ (range 2)]
          (let [parent (AnchorPane.)
                _scene (Scene. parent 800.0 600.0)
                view (workspace/make-file-view! parent root "/readme.md" {})
                reader (.lookup parent ".ai-markdown-reader")]
            (is (= [url] (vec (.getStylesheets reader))))
            (workspace/apply-styles! reader)
            (workspace/apply-styles! reader)
            (.applyCss parent)
            (.layout parent)
            (is (= [url] (vec (.getStylesheets reader))))
            (doseq [id ["ai-markdown-toggle" "ai-markdown-refresh" "ai-markdown-open"]]
              (let [^Button button (.lookup parent (str "#" id))
                    ^SVGPath icon (.getGraphic button)]
                (is (= Color/TRANSPARENT (.getFill icon)))
                (is (= (Color/web "#9198a1") (.getStroke icon)))
                (is (= 24.0 (.getPrefWidth button)))))
            ((:close! view)))))
      (finally
        (workspace/reload-styles! nil)
        (fs/delete-directory! root)))))

;; Opening/reselecting/closing documents must keep one browser and preserve the
;; other tabs' contents; closing the whole workspace disposes each view once.
(deftest workspace-tab-lifecycle-test
  (let [root (.toFile (Files/createTempDirectory "ai-workspace-tabs" (make-array java.nio.file.attribute.FileAttribute 0)))
        created (atom [])
        disposed (atom [])]
    (try
      (ui/run-now
        (let [workspace (workspace/make-view! (AnchorPane.) root {:path "/first.md"} {}
                                              (fn create-content [_ _ context _]
                                                (swap! created conj (:path context))
                                                {:close! (fn dispose-content [] (swap! disposed conj (:path context)))}))
              browser (:root (:browser workspace))
              tabs (.getTabs ^javafx.scene.control.TabPane (:tabs workspace))]
          ((:open! workspace) {:path "/first.md"})
          (let [first-tab (first tabs)]
            ((:open! workspace) {:path "/second.md"})
            ((:open! workspace) {:path "/first.md"})
            (is (= ["/first.md" "/second.md"] @created))
            (is (identical? first-tab (.getSelectedItem (.getSelectionModel ^javafx.scene.control.TabPane (:tabs workspace)))))
            (is (identical? browser (:root (:browser workspace))))
            (Event/fireEvent first-tab (Event. Tab/CLOSED_EVENT))
            (.remove tabs ^Object first-tab)
            (is (= ["/first.md"] @disposed))
            (is (= 1 (count tabs)))
            ((:close! workspace))
            ((:close! workspace))
            (is (= ["/first.md" "/second.md"] @disposed))
            (is (zero? (count tabs))))))
      (finally (fs/delete-directory! root)))))

;; Markdown source/refresh preserve the view; closing releases its component.
(deftest markdown-preview-lifecycle-test
  (let [root (.toFile (Files/createTempDirectory "ai-markdown" (make-array java.nio.file.attribute.FileAttribute 0)))
        file (java.io.File. root "preview.MD")]
    (try
      (spit file "# 核心玩法\n\n**战斗**与探索\n\n| 职能 | 产物 |\n| --- | --- |\n| 策划 | 文档 |\n\n```lua\nreturn 1\n```\n" :encoding "UTF-8")
      (ui/run-now
        (let [parent (AnchorPane.)
              view (workspace/make-file-view! parent root "/preview.MD" {})
              ^AnchorPane preview (.lookup parent "#ai-markdown-preview")
              content (.getContent ^ScrollPane (first (.getChildren preview)))
              ^TextArea source (.lookup parent "#ai-markdown-source")
              ^Button toggle (.lookup parent "#ai-markdown-toggle")
              ^Button refresh (.lookup parent "#ai-markdown-refresh")]
          (is (some? (.lookup content ".md-h1")))
          (is (some? (.lookup content ".md-table")))
          (is (.isVisible preview))
          (is (not (.isVisible source)))
          (is (not (.isManaged source)))
          (.fire toggle)
          (is (.isVisible source))
          (is (not (.isManaged preview)))
          (is (not (.isEditable source)))
          (is (coll/any? #(= "file-reveal" (.getId ^MenuItem %)) (.getItems (.getContextMenu source))))
          (is (.startsWith (.getText source) "# 核心玩法"))
          (spit file "# 更新的方案\n\n交付要求" :encoding "UTF-8")
          (.fire refresh)
          (is (= "# 更新的方案\n\n交付要求" (.getText source)))
          (.fire toggle)
          (is (.isVisible preview))
          (is (some? (ui/user-data preview :editor.ai-workspace/markdown)))
          ((:close! view))
          (is (nil? (ui/user-data preview :editor.ai-workspace/markdown)))))
      (finally (fs/delete-directory! root)))))

;; Creating a document refreshes directory children in place, keeping the root,
;; expanded folder identity, and the user's selection instead of rebuilding it.
(deftest workspace-browser-refresh-preserves-items-test
  (let [root (.toFile (Files/createTempDirectory "ai-workspace-browser" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (ui/run-now
        (let [workspace (workspace/make-view! (AnchorPane.) root {:path "/first.md"} {}
                                              (fn create-content [_ _ _ _] {:close! (fn close-content [])}))
              browser (:browser workspace)
              ^TreeView tree (.lookup ^javafx.scene.Node (:root browser) "#ai-project-tree")
              root-item (.getRoot tree)
              ^TreeItem library (coll/first-where #(= "ai-workspace/library" (:path (.getValue ^TreeItem %))) (.getChildren root-item))
              ^TreeItem category (first (.getChildren library))]
          (.setExpanded category true)
          (.select (.getSelectionModel tree) category)
          (let [before (count (.getChildren category))]
            (document/create-document! root "ai-workspace/library/01-concept" "新方案" "concept")
            ((:refresh! browser))
            (is (identical? root-item (.getRoot tree)))
            (is (identical? category (first (.getChildren library))))
            (is (.isExpanded category))
            (is (identical? category (.getSelectedItem (.getSelectionModel tree))))
            (is (= (inc before) (count (.getChildren category)))))
          ((:close! workspace))))
      (finally (fs/delete-directory! root)))))
