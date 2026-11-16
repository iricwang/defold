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


(ns editor.ai-project-browser-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [editor.ai-archive :as archive]
            [editor.ai-project-browser :as browser]
            [editor.fs :as fs]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [java.nio.file Files]
           [javafx.application Platform]
           [javafx.scene Scene]
           [javafx.scene.control Button ButtonType DialogPane MenuItem TextField TreeItem TreeView]
           [javafx.stage Stage Window]))

;; Reopening preserves authored documents and stable sync hashes.
(deftest library-reopen-preserves-content-test
  (let [root (.toFile (Files/createTempDirectory "ai-library-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (archive/ensure-project! root nil)
      (browser/ensure-library! root)
      (let [file (io/file root "ai-workspace/library/01-concept/README.md")]
        (spit file "my game concept")
        (let [before (archive/manifest root)]
          (browser/ensure-library! root)
          (is (= before (archive/manifest root)))
          (is (= "my game concept" (slurp file)))
          (is (= (mapv :title browser/sections) (mapv :title (browser/entries root "ai-workspace/library"))))))
      (finally (fs/delete-directory! root)))))

;; Duplicate imports keep both resources in the sync inventory.
(deftest import-preserves-duplicates-test
  (let [root (.toFile (Files/createTempDirectory "ai-library-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        source (io/file root "reference.png")]
    (try
      (archive/ensure-project! root nil)
      (browser/ensure-library! root)
      (spit source "first")
      (is (= ["ai-workspace/library/03-characters/reference.png"]
             (browser/import-files! root "ai-workspace/library/03-characters" [source])))
      (spit source "second")
      (is (= ["ai-workspace/library/03-characters/reference-1.png"]
             (browser/import-files! root "ai-workspace/library/03-characters" [source])))
      (is (= "first" (slurp (io/file root "ai-workspace/library/03-characters/reference.png"))))
      (is (= "second" (slurp (io/file root "ai-workspace/library/03-characters/reference-1.png"))))
      (is (contains? (set (mapv :path (archive/manifest root))) "ai-workspace/library/03-characters/reference-1.png"))
      (is (thrown? Exception (browser/import-files! root "ai-workspace/artifacts" [source])))
      (finally (fs/delete-directory! root)))))

;; Browsing cannot escape the library through traversal or symlinks.
(deftest library-path-boundary-test
  (let [root (.toFile (Files/createTempDirectory "ai-library-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        outside (.toFile (Files/createTempDirectory "ai-library-outside" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (browser/ensure-library! root)
      (Files/createSymbolicLink (.toPath (io/file root "ai-workspace/library/external")) (.toPath outside) (make-array java.nio.file.attribute.FileAttribute 0))
      (is (= 10 (count (browser/entries root "ai-workspace/library"))))
      (is (thrown? Exception (browser/entries root "ai-workspace/library/external")))
      (is (thrown? Exception (browser/entries root "ai-workspace/..")))
      (finally
        (Files/deleteIfExists (.toPath (io/file root "ai-workspace/library/external")))
        (fs/delete-directory! root)
        (fs/delete-directory! outside)))))

(defn- visible-entry [^TreeView tree path]
  (coll/first-where #(= path (:path (.getValue ^TreeItem %)))
                    (mapv #(.getTreeItem tree (int %)) (range (.getExpandedItemCount tree)))))

(defn- browser-menu-item [^TreeView tree id]
  (coll/first-where #(= id (.getId ^MenuItem %)) (.getItems (.getContextMenu tree))))

;; Menu actions can arrive after selection has cleared. They must explain the
;; missing target instead of dereferencing nil or opening an invalid dialog.
(deftest missing-browser-target-test
  (let [root (.toFile (Files/createTempDirectory "ai-browser-target" (make-array java.nio.file.attribute.FileAttribute 0)))
        errors (atom [])]
    (try
      (ui/run-now
        (let [view (browser/make-browser root nil identity (constantly nil)
                                         (fn capture-error [message] (swap! errors conj message)) {})
              tree ^TreeView (.lookup ^javafx.scene.Parent (:root view) "#ai-project-tree")]
          (.clearSelection (.getSelectionModel tree))
          (doseq [id ["ai-project-new-canvas" "ai-project-import"]]
            (.handle (.getOnShowing (.getContextMenu tree)) nil)
            (let [item ^MenuItem (browser-menu-item tree id)]
              (is (.isDisable item))
              (.handle (.getOnAction item) nil)))
          (is (= ["请选择创作资料中的分类目录" "请选择创作资料中的分类目录"] @errors))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"请选择创作资料"
                            (browser/import-files! root nil [])))
      (finally (fs/delete-directory! root)))))

;; Reconciliation can replace the selected item's sibling lists. Preserve its
;; path through changes, keeping an empty selection empty.
(deftest browser-refresh-selection-test
  (let [root (.toFile (Files/createTempDirectory "ai-browser-refresh" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (browser/ensure-library! root)
      (spit (io/file root "ai-workspace/library/01-concept/selected.md") "selected")
      (ui/run-now
        (let [view (browser/make-browser root nil identity (constantly nil)
                                         (fn fail-refresh [message] (throw (ex-info message {}))) {})
              tree ^TreeView (.lookup ^javafx.scene.Parent (:root view) "#ai-project-tree")
              folder ^TreeItem (visible-entry tree "ai-workspace/library/01-concept")
              path "ai-workspace/library/01-concept/selected.md"]
          (.setExpanded folder true)
          (.select (.getSelectionModel tree) (int (.getRow tree (visible-entry tree path))))
          (spit (io/file root "ai-workspace/library/01-concept/earlier.md") "new sibling")
          (.mkdir (io/file root "ai-workspace/library/00-new-category"))
          ((:refresh! view))
          (is (= path (:path (.getValue ^TreeItem (.getSelectedItem (.getSelectionModel tree))))))
          (.clearSelection (.getSelectionModel tree))
          ((:refresh! view))
          (is (nil? (.getSelectedItem (.getSelectionModel tree))))))
      (finally (fs/delete-directory! root)))))

;; A real creation dialog must keep the directory captured when its menu opened,
;; even if selection clears or changes during a nested refresh.
(deftest browser-create-captured-target-test
  (let [root (.toFile (Files/createTempDirectory "ai-browser-create" (make-array java.nio.file.attribute.FileAttribute 0)))
        opened (atom [])
        errors (atom [])
        response (promise)]
    (try
      (ui/run-now
        (let [view (browser/make-browser root nil (fn record-open [path] (swap! opened conj path))
                                         (constantly nil) (fn record-error [message] (swap! errors conj message)) {})
              pane ^javafx.scene.Parent (:root view)
              tree ^TreeView (.lookup pane "#ai-project-tree")
              stage (Stage.)]
          (try
            (.setScene stage (Scene. pane 360.0 600.0))
            (.show stage)
            (.select (.getSelectionModel tree) (int (.getRow tree (visible-entry tree "ai-workspace/library/01-concept"))))
            (.handle (.getOnShowing (.getContextMenu tree)) nil)
            (.clearSelection (.getSelectionModel tree))
            (Platform/runLater
              (fn complete-create-dialog []
                (let [window ^Window (coll/first-where #(instance? DialogPane (.getRoot (.getScene ^Window %))) (vec (Window/getWindows)))]
                  (try
                    (.select (.getSelectionModel tree) (int (.getRow tree (visible-entry tree "ai-workspace/library/02-world"))))
                    ((:refresh! view))
                    (let [dialog ^DialogPane (.getRoot (.getScene window))
                          input ^TextField (.lookup dialog ".text-field")]
                      (.setText input "Captured target")
                      (.fire ^Button (.lookupButton dialog ButtonType/OK)))
                    (deliver response :completed)
                    (catch Throwable e (deliver response e))
                    (finally (when window (.hide window)))))))
            (.fire ^MenuItem (browser-menu-item tree "ai-project-new-canvas"))
            (is (= :completed (deref response 1000 ::timeout)))
            (is (= ["/ai-workspace/library/01-concept/Captured target.aicanvas"] @opened))
            (is (= [] @errors))
            (is (.isFile (io/file root "ai-workspace/library/01-concept/Captured target.aicanvas")))
            (is (not (.exists (io/file root "ai-workspace/library/02-world/Captured target.aicanvas"))))
            (finally (.close stage)))))
      (finally (fs/delete-directory! root)))))
