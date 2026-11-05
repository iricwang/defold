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
            [editor.ai-overview-view :as overview-view]
            [editor.ai-workspace :as workspace]
            [editor.fs :as fs]
            [editor.handler :as handler]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [java.nio.file Files]
           [javafx.event Event]
           [javafx.scene Scene]
           [javafx.scene.control Button ComboBox MenuItem ScrollPane Tab TabPane TextArea TextField TreeItem TreeView]
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
          (let [[home-tab first-tab ^Tab keep-tab third-tab] (vec (.getTabs tabs))
                ^MenuItem close-others (second (.getItems (.getContextMenu keep-tab)))]
            (is (identical? third-tab (ui/selected-tab tabs)))
            (.fire close-others)
            (is (= [home-tab keep-tab] (vec (.getTabs tabs))))
            (is (= ["/first.md" "/third.md"] @disposed))
            (is (= [engine-tab ai-tab] (vec (.getTabs outer))))
            (is (= [other-tab] (vec (.getTabs other-tabs))))
            (is (identical? browser (:root (:browser workspace))))
            ((:open! workspace) {:path "/first.md"})
            (is (not (identical? first-tab (ui/selected-tab tabs))))
            (let [^MenuItem close-all (coll/first-where #(= "ai-tab-close-all" (.getId ^MenuItem %)) (.getItems (.getContextMenu keep-tab)))]
              (.fire close-all)
              (is (= [home-tab] (vec (.getTabs tabs))))
              (is (= [engine-tab ai-tab] (vec (.getTabs outer))))
              (is (identical? browser (:root (:browser workspace))))
              ((:open! workspace) {:path "/after-close.md"})
              ;; A menu retained from a closed tab must not close new tabs.
              (.fire close-all)
              (is (= 2 (count (.getTabs tabs))))
              (is (some? (.getContextMenu ^Tab (ui/selected-tab tabs)))))
            ((:close! workspace))
            (is (= ["/first.md" "/third.md" "/keep.md" "/first.md" "/after-close.md"] @disposed)))))
      (finally (fs/delete-directory! root)))))

;; Drill-down filters must have an explicit escape, and the project home entry
;; must clear filters and exclusive sidebar selection without recreating tabs.
(deftest overview-return-clears-filters-test
  (let [root (.toFile (Files/createTempDirectory "ai-overview-return" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (ui/run-now
        (let [parent (AnchorPane.)
              view (workspace/make-view! parent root {} {}
                                         (fn make-content [_ _ _ _] {:close! (fn close-content [])}))
              ^TabPane tabs (:tabs view)
              ^Tab home-tab (first (.getTabs tabs))
              home (.getContent home-tab)
              ^ComboBox mode (.lookup home "#ai-overview-filter")
              ^ComboBox stage (.lookup home "#ai-overview-stage")
              ^TextField search (.lookup home "#ai-overview-search")
              ^Button back (.lookup home "#ai-overview-reset")
              browser (:root (:browser view))
              ^Button home-entry (.lookup ^javafx.scene.Node browser "#ai-project-home")
              ^TreeView tree (.lookup ^javafx.scene.Node browser "#ai-project-tree")
              selected (javafx.css.PseudoClass/getPseudoClass "selected")
              folder (first (.getChildren (.getRoot tree)))]
          (try
            (is (.contains (.getPseudoClassStates home-entry) selected))
            (.select (.getSelectionModel tree) ^TreeItem folder)
            (is (not (.contains (.getPseudoClassStates home-entry) selected)))
            (.fire home-entry)
            (is (nil? (.getSelectedItem (.getSelectionModel tree))))
            (is (.contains (.getPseudoClassStates home-entry) selected))
            (is (not (.isManaged back)))
            (doseq [value ["执行中" "待我处理" "需要关注"]]
              (.setValue mode value)
              (is (.isVisible back))
              (is (.isManaged back))
              (.fire back)
              (is (= "全部工作" (.getValue mode)))
              (is (not (.isManaged back))))
            (.add (.getItems stage) "测试阶段")
            (.setValue stage "测试阶段")
            (.setText search "找不到的任务")
            (.setValue mode "执行中")
            ((:open! view) {:path "/notes.md"})
            (is (not (.contains (.getPseudoClassStates home-entry) selected)))
            (.select (.getSelectionModel tree) ^TreeItem folder)
            (let [before-tabs (vec (.getTabs tabs))]
              (.select (.getSelectionModel tabs) home-tab)
              (is (.contains (.getPseudoClassStates home-entry) selected))
              (is (nil? (.getSelectedItem (.getSelectionModel tree))))
              (.fire home-entry)
              (is (identical? home-tab (ui/selected-tab tabs)))
              (is (= "全部工作" (.getValue mode)))
              (is (= "全部阶段" (.getValue stage)))
              (is (= "" (.getText search)))
              (is (not (.isManaged back)))
              (is (= before-tabs (vec (.getTabs tabs))))
              (is (identical? browser (:root (:browser view)))))
            (finally ((:close! view))))))
      (finally (fs/delete-directory! root)))))

;; Rebuilding a focused card must not leave the Scene pointing at a detached
;; button and losing global menu contexts. Header inputs must keep their focus.
(deftest overview-rebuild-preserves-attached-focus-test
  (ui/run-now
    (let [tab (Tab.)
          view (overview-view/make-view "." tab (fn open-target [_]) (constantly true))
          root (:root view)
          scene (Scene. root 1400.0 900.0)
          ^TextField search (.lookup root "#ai-overview-search")
          ^ComboBox mode (.lookup root "#ai-overview-filter")]
      (try
        (reset! (:snapshot view) {:accepted 0 :total 0 :tasks [] :stages [] :staff []
                                  :running [] :reviews [] :issues [] :artifacts [] :activity []})
        (.setText search " ")
        (.applyCss root)
        (.layout root)
        (let [^Button button (.lookup root "#ai-overview-all-running")]
          (.requestFocus button)
          (is (identical? button (.getFocusOwner scene)))
          (.fire button)
          (is (nil? (.getScene button)))
          (is (identical? root (.getFocusOwner scene)))
          (is (identical? scene (.getScene (.getFocusOwner scene))))
          (is (= "执行中" (.getValue mode))))
        (.requestFocus search)
        (.setText search "next query")
        (is (identical? search (.getFocusOwner scene)))
        (is (identical? scene (.getScene search)))
        (finally ((:close! view)))))))

;; Dense dashboard sections scroll independently within the available height.
;; Refresh keeps each position, and tasks stay expanded rather than in a drawer.
(deftest overview-independent-section-scroll-test
  (ui/run-now
    (let [view (overview-view/make-view "." (Tab.) (fn open-target [_]) (constantly true))
          ^javafx.scene.layout.BorderPane root (:root view)
          scene (Scene. root 1400.0 850.0)
          ^TextField search (.lookup root "#ai-overview-search")
          tasks (mapv (fn [n] {:id (str n) :title (str "任务 " n) :status "待执行"}) (range 20))
          model {:accepted 0 :total 20 :tasks tasks :activity [] :issues []
                 :stages (mapv (fn [n] {:title (str "阶段 " n) :tasks [] :done 0 :total 1 :status "待执行"}) (range 20))
                 :running (mapv (fn [n] {:id (str n) :title (str "执行 " n) :status "running"}) (range 6))
                 :reviews (mapv (fn [n] {:title (str "方案 " n) :label "方案待选择"}) (range 20))
                 :staff (mapv (fn [n] {:id (str n) :title (str "职员 " n) :role-type "pm" :jobs []}) (range 20))
                 :artifacts (mapv (fn [n] {:title (str "成果 " n) :content-type "文档" :text "方案正文" :files []}) (range 9))}]
      (try
        (.add (.getStylesheets scene) (str (io/resource "editor.css")))
        (.add (.getStylesheets root) (str (.toURI (io/file "resources/ai-canvas.css"))))
        (reset! (:snapshot view) model)
        (.setText search " ")
        (.applyCss root) (.layout root)
        (is (not (instance? ScrollPane (.getCenter root))))
        (is (= 6 (count (.lookupAll root ".ai-overview-section-scroll"))))
        (is (coll/empty? (.lookupAll root ".ai-overview-disclosure")))
        (doseq [id ["stages" "running" "outputs" "reviews" "staff" "tasks"]
                :let [^ScrollPane viewport (.lookup root (str "#ai-overview-" id "-scroll"))]]
          (is (> (.getHeight (.getBoundsInLocal (.getContent viewport))) (.getHeight (.getViewportBounds viewport))))
          (let [bounds (.localToScene viewport (.getBoundsInLocal viewport))]
            (is (>= (.getMinY bounds) 0.0))
            (is (<= (.getMaxY bounds) (.getHeight scene)))))
        (is (= 20 (count (.lookupAll root ".ai-overview-task-row"))))
        (is (= 6 (count (.lookupAll root ".ai-overview-execution-card"))))
        (is (= 9 (count (.lookupAll root ".ai-overview-output"))))
        (.setVvalue ^ScrollPane (.lookup root "#ai-overview-tasks-scroll") 0.75)
        (.setVvalue ^ScrollPane (.lookup root "#ai-overview-staff-scroll") 0.4)
        (is (= 0.0 (.getVvalue ^ScrollPane (.lookup root "#ai-overview-stages-scroll"))))
        (.setText search "  ")
        (.applyCss root) (.layout root)
        (is (= 0.75 (.getVvalue ^ScrollPane (.lookup root "#ai-overview-tasks-scroll"))))
        (is (= 0.4 (.getVvalue ^ScrollPane (.lookup root "#ai-overview-staff-scroll"))))
        ((:reset! view))
        (is (coll/every? #(zero? (.getVvalue ^ScrollPane %)) (.lookupAll root ".ai-overview-section-scroll")))
        (finally ((:close! view)))))))

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
          (let [first-tab (second tabs)]
            ((:open! workspace) {:path "/second.md"})
            ((:open! workspace) {:path "/first.md"})
            (is (= ["/first.md" "/second.md"] @created))
            (is (identical? first-tab (.getSelectedItem (.getSelectionModel ^javafx.scene.control.TabPane (:tabs workspace)))))
            (is (identical? browser (:root (:browser workspace))))
            (Event/fireEvent first-tab (Event. Tab/CLOSED_EVENT))
            (.remove tabs ^Object first-tab)
            (is (= ["/first.md"] @disposed))
            (is (= 2 (count tabs)))
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
