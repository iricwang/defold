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

(ns editor.file-reveal
  "Shared file-manager actions for native and AI workspace file surfaces."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.os :as os]
            [editor.process :as process]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [java.io File]
           [javafx.scene Node]
           [javafx.scene.control ContextMenu Control MenuItem SeparatorMenuItem TextInputControl TreeCell TreeItem TreeView]
           [javafx.scene.input ContextMenuEvent]
           [javafx.stage WindowEvent]))

(set! *warn-on-reflection* true)

(defn resolve-file [root value]
  (let [paths (if (map? value) (mapv value [:file :local-path :preview :source :path :image-path]) [value])]
    (coll/first-where identity
                      (mapv (fn existing-file [path]
                              (try
                                (let [file (cond
                                             (instance? File path) path
                                             (and (string? path) (not (string/blank? path)))
                                             (cond
                                               (string/starts-with? path "file:") (io/file (java.net.URI/create path))
                                               (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]+://" path) nil
                                               (and (.isAbsolute (io/file path)) (.exists (io/file path))) (io/file path)
                                               root (io/file root (string/replace-first path #"^[/\\]" ""))
                                               :else nil))]
                                  (when (and file (.exists ^File file)) (.getCanonicalFile ^File file)))
                                (catch Exception _ nil))) paths))))

(defn reveal-command [platform ^File file]
  (when-not (and file (.exists file)) (throw (ex-info "文件不存在或尚未同步到本地" {})))
  (let [path (.getCanonicalPath file)]
    (case platform
      :win32 (if (.isDirectory file) ["explorer.exe" path] ["explorer.exe" "/select," path])
      :macos ["open" "-R" path]
      :linux ["dbus-send" "--session" "--print-reply"
              "--dest=org.freedesktop.FileManager1" "/org/freedesktop/FileManager1"
              "org.freedesktop.FileManager1.ShowItems" (str "array:string:" (.toURI file)) "string:"])))

(defn reveal! [file]
  (apply process/start! {:out :discard :err :discard} (reveal-command (os/os) file)))

(defn add-item! [^ContextMenu menu root value! report!]
  (let [item (doto (MenuItem. "在文件管理器中显示") (.setId "file-reveal"))
        update! (fn update-file-action [] (.setDisable item (nil? (resolve-file root (value!)))))]
    (.setOnAction item (ui/event-handler _
                         (try (reveal! (resolve-file root (value!)))
                              (catch Exception e (report! (.getMessage e))))))
    (.addEventHandler menu WindowEvent/WINDOW_SHOWING (ui/event-handler _ (update!)))
    (.add (.getItems menu) item)
    (update!)
    item))

(defn install! [^Node node root value! report!]
  (when-not (ui/user-data node ::menu)
    (let [menu (or (when (instance? Control node) (.getContextMenu ^Control node)) (ContextMenu.))]
      ;; Text editors retain their editing actions alongside the file action.
      (when (and (instance? TextInputControl node) (.isEmpty (.getItems menu)))
        (let [input ^TextInputControl node]
          (doseq [[title action] [["撤销" #(.undo input)] ["重做" #(.redo input)]
                                  ["剪切" #(.cut input)] ["复制" #(.copy input)]
                                  ["粘贴" #(.paste input)] ["全选" #(.selectAll input)]]]
            (let [item (MenuItem. title)]
              (.setOnAction item (ui/event-handler _ (action)))
              (when (contains? #{"撤销" "重做" "剪切" "粘贴"} title)
                (.setDisable item (not (.isEditable input))))
              (.add (.getItems menu) item)))))
      (when-not (.isEmpty (.getItems menu)) (.add (.getItems menu) (SeparatorMenuItem.)))
      (add-item! menu root value! report!)
      (ui/user-data! node ::menu menu)
      (if (instance? Control node)
        (.setContextMenu ^Control node menu)
        (.addEventHandler node ContextMenuEvent/CONTEXT_MENU_REQUESTED
                          (ui/event-handler event
                            (let [event ^ContextMenuEvent event]
                              (.show menu node (.getScreenX event) (.getScreenY event))
                              (.consume event)))))))
  node)

(defn install-tree! [^TreeView tree root value! report!]
  (install! tree root value! report!)
  (.addEventFilter tree ContextMenuEvent/CONTEXT_MENU_REQUESTED
    (ui/event-handler event
      (let [event ^ContextMenuEvent event]
        (when-not (.isKeyboardTrigger event)
          (let [cell (loop [node (.getTarget event)]
                       (cond (instance? TreeCell node) node
                             (or (identical? tree node) (not (instance? Node node))) nil
                             :else (recur (.getParent ^Node node))))
                item (when cell (.getTreeItem ^TreeCell cell))]
            (if item
              (.select (.getSelectionModel tree) ^TreeItem item)
              (.clearSelection (.getSelectionModel tree)))))))))
