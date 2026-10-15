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

(ns editor.app-view-photocraft-test
  (:require [clojure.test :refer :all]
            [editor.app-view :as app-view]
            [editor.craft-binding :as binding]
            [editor.handler :as handler]
            [editor.resource :as resource]))

;; GUI tabs expose a disabled opener without a local peer; other file types never expose it.
(deftest tab-opener-is-visible-only-for-gui-and-enabled-only-for-a-local-peer
  (is (= 1 (count (filterv #(= :photocraft.open-local (:command %))
                          (handler/realize-menu :editor.app-view/tab-menu)))))
  (doseq [[extension available exists expected-active expected-enabled]
          [["gui" true true true true]
           ["gui" false true true false]
           ["gui" true false true false]
           ["script" true true false false]]]
    (with-redefs-fn {#'app-view/context-resource (fn [_ _ _ _] :clicked-resource)
                    #'binding/local-available? (constantly available)
                    #'resource/type-ext (constantly extension)
                    #'resource/exists? (constantly exists)}
      (fn []
        (let [entry (handler/active :photocraft.open-local
                                    [{:name :global :env {:app-view nil :selection [] :selection-context :tab}}]
                                    nil {})]
          (is (= expected-active (boolean entry)))
          (is (= expected-enabled (boolean (and entry (handler/enabled? entry {}))))))))))
