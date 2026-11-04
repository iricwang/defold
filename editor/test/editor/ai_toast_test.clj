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

(ns editor.ai-toast-test
  (:require [clojure.test :refer [deftest is]]
            [editor.ai-toast :as toast]
            [editor.ui :as ui]))

;; Informational messages expire without removing persistent warnings/errors.
(deftest toast-levels-test
  (ui/run-now
    (let [{:keys [root show! dispose!]} (toast/make-view)]
      (try
        (show! {:id :upload :level :warning :text "资料上传失败，已保留本地副本"})
        (show! {:id :save :level :error :text "文档保存失败"})
        (show! "已整理卡片")
        (let [[error warning info] (vec (.getChildren ^javafx.scene.layout.VBox root))
              timer ^javafx.animation.PauseTransition (ui/user-data info :editor.ai-toast/timer)]
          (is (= [:save :upload :info] (mapv #(ui/user-data % :editor.ai-toast/id) (.getChildren root))))
          (is (nil? (.lookup ^javafx.scene.Node error ".ai-toast-close")))
          (is (some? (.lookup ^javafx.scene.Node warning ".ai-toast-close")))
          (is (nil? (ui/user-data warning :editor.ai-toast/timer)))
          (is (nil? (ui/user-data error :editor.ai-toast/timer)))
          (is (= 4000.0 (.toMillis (.getDuration timer))))
          (.stop timer)
          (.handle (.getOnFinished timer) (javafx.event.ActionEvent.))
          (is (= [:save :upload] (mapv #(ui/user-data % :editor.ai-toast/id) (.getChildren root))))
          (.fire ^javafx.scene.control.Button (.lookup warning ".ai-toast-close"))
          (is (= [:save] (mapv #(ui/user-data % :editor.ai-toast/id) (.getChildren root)))))
        (finally (dispose!))))))

;; Dismissed warnings stay dismissed across identical background retries.
;; Recovery resolves only its own condition; a later recurrence is visible.
(deftest toast-dedup-and-recovery-test
  (ui/run-now
    (let [{:keys [root show! resolve! dispose!]} (toast/make-view)
          warning {:id :sync :level :warning :text "资料待同步"}]
      (try
        (show! warning)
        (let [row (.get (.getChildren ^javafx.scene.layout.VBox root) 0)]
          (show! warning)
          (is (= 1 (count (.getChildren root))))
          (is (identical? row (.get (.getChildren root) 0)))
          (.fire ^javafx.scene.control.Button (.lookup ^javafx.scene.Node row ".ai-toast-close")))
        (show! warning)
        (is (not (.isVisible root)))
        (is (not (.isManaged root)))
        (resolve! :sync)
        (show! warning)
        (is (.isVisible root))
        (show! {:id :save :level :error :text "保存失败"})
        (show! {:id :save :level :info :text "正在重试"})
        (is (= "保存失败" (.getText ^javafx.scene.control.Label (.lookup (.get (.getChildren root) 0) ".ai-toast-message"))))
        (show! {:id :sync :level :info :resolved true :text "已同步"})
        (is (= [:save :sync] (mapv #(ui/user-data % :editor.ai-toast/id) (.getChildren root))))
        (resolve! :save)
        (is (= [:sync] (mapv #(ui/user-data % :editor.ai-toast/id) (.getChildren root))))
        (finally (dispose!))))))

;; Stale timer callbacks cannot remove replacements; closing stops timers.
(deftest toast-lifecycle-test
  (ui/run-now
    (let [{:keys [root show! dispose!]} (toast/make-view)]
      (show! "旧提示")
      (let [old ^javafx.animation.PauseTransition (ui/user-data (.get (.getChildren ^javafx.scene.layout.VBox root) 0) :editor.ai-toast/timer)]
        (show! "新提示")
        (.handle (.getOnFinished old) (javafx.event.ActionEvent.))
        (is (= 1 (count (.getChildren root))))
        (let [current ^javafx.animation.PauseTransition (ui/user-data (.get (.getChildren root) 0) :editor.ai-toast/timer)]
          (dispose!)
          (is (= javafx.animation.Animation$Status/STOPPED (.getStatus current)))
          (show! {:level :error :text "关闭后收到的异步错误"})
          (is (= 0 (count (.getChildren root)))))))))
