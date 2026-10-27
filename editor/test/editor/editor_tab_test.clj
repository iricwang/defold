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

(ns editor.editor-tab-test
  (:require [clojure.test :refer [deftest is]]
            [editor.editor-tab :as editor-tab]
            [editor.ui :as ui])
  (:import [javafx.scene.control Tab TabPane]))

;; Detached documents must keep rendering after another home tab is selected.
(deftest detached-tab-rendering-test
  (ui/run-now
    (let [tab (Tab. "GUI")
          other (Tab. "Script")
          pane (TabPane. (into-array Tab [tab other]))
          selection (.getSelectionModel pane)
          floating (editor-tab/floating-property tab)
          active (editor-tab/rendering-active-property tab)]
      (.select selection tab)
      (is (.get active))
      (.select selection other)
      (is (not (.get active)))
      (.set floating true)
      (is (.get active))
      (.select selection tab)
      (.select selection other)
      (is (.get active))
      (.set floating false)
      (is (not (.get active)))
      (.select selection tab)
      (is (.get active)))))
