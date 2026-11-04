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

(ns editor.file-reveal-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [editor.file-reveal :as reveal]
            [editor.ui :as ui])
  (:import [java.nio.file Files]
           [javafx.scene.control ContextMenu MenuItem TextArea]
           [javafx.stage WindowEvent]))

;; Local and cached paths resolve without treating remote URLs as files.
(deftest file-resolution-test
  (let [root (.toFile (Files/createTempDirectory "reveal-path" (make-array java.nio.file.attribute.FileAttribute 0)))
        file (io/file root "中文 空格,方案.md")]
    (try
      (spit file "# 本地文件")
      (is (= file (reveal/resolve-file root "/中文 空格,方案.md")))
      (is (= file (reveal/resolve-file root {:path "/missing.png" :preview (str (.toURI file))})))
      (is (= file (reveal/resolve-file root {:local-path (.getAbsolutePath file)})))
      (is (nil? (reveal/resolve-file root "https://example.com/file.md")))
      (is (nil? (reveal/resolve-file root "/missing.md")))
      (finally (.delete file) (.delete root)))))

;; File-manager commands keep the path as one argument and select the file.
(deftest reveal-launch-test
  (let [file (java.io.File/createTempFile "中文 空格,方案" ".md")]
    (try
      (is (= ["explorer.exe" "/select," (.getCanonicalPath file)] (reveal/reveal-command :win32 file)))
      (is (= ["explorer.exe" (.getCanonicalPath (.getParentFile file))] (reveal/reveal-command :win32 (.getParentFile file))))
      (is (= ["open" "-R" (.getCanonicalPath file)] (reveal/reveal-command :macos file)))
      (.delete file)
      (is (thrown? clojure.lang.ExceptionInfo (reveal/reveal! file)))
      (finally (.delete file)))))

;; Existing menu actions survive; stale and virtual entries cannot launch.
(deftest file-context-menu-test
  (let [file (java.io.File/createTempFile "reveal-menu" ".md")]
    (try
      (ui/run-now
        (let [input (TextArea. "editable") menu (ContextMenu.) original (MenuItem. "已有操作")
              current (atom file) errors (atom [])]
          (.add (.getItems menu) original) (.setContextMenu input menu)
          (reveal/install! input nil #(deref current) #(swap! errors conj %))
          (reveal/install! input nil #(deref current) #(swap! errors conj %))
          (is (= 3 (count (.getItems menu))))
          (is (identical? original (first (.getItems menu))))
          (let [item ^MenuItem (last (.getItems menu))]
            (is (not (.isDisable item)))
            (reset! current nil)
            (javafx.event.Event/fireEvent menu (WindowEvent. menu WindowEvent/WINDOW_SHOWING))
            (is (.isDisable item))
            (reset! current file) (.delete file)
            (.handle (.getOnAction item) (javafx.event.ActionEvent.))
            (is (= ["文件不存在或尚未同步到本地"] @errors)))
          (let [source (TextArea.)]
            (reveal/install! source nil (constantly nil) #(swap! errors conj %))
            (is (= ["撤销" "重做" "剪切" "复制" "粘贴" "全选" nil "在文件管理器中显示"]
                   (mapv #(.getText ^MenuItem %) (.getItems (.getContextMenu source))))))))
      (finally (.delete file)))))
