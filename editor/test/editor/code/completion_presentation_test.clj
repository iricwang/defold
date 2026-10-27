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

(ns editor.code.completion-presentation-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [editor.code-completion :as completion]
            [editor.code.completion-presentation :as presentation]
            [editor.lsp.server :as server]
            [editor.markdown-view :as markdown-view]))

;; Signature display must not alter accepted snippets, filters, or server metadata.
(deftest completion-label-details-test
  (let [item (#'server/completion-item:lsp->editor
               {:label "newWidget" :filterText "newWidget" :kind 3
                :labelDetails {:detail "(url, count) -> Context" :description "[Context]"}
                :insertText "newWidget(${1:url}, ${2:count})" :insertTextFormat 2
                :data {:id 7}})]
    (is (= {:label "newWidget(url, count)" :description "[Context]  Context"}
           (presentation/label-parts item)))
    (is (= "newWidget" (:name item)))
    (is (= "newWidget(url, count)" (:insert-string (completion/insertion item))))
    (is (= {:id 7} (get-in (meta item) [:editor.lsp.server/completion :data])))))

;; Other language servers and unannotated identifiers retain their original labels.
(deftest completion-label-fallback-test
  (doseq [[item expected] [[{:display-string "value"} {:label "value" :description ""}]
                           [{:display-string "get" :label-detail "(key)" :label-description "Map"}
                            {:label "get(key)" :description "Map"}]
                           [{:display-string "f" :label-detail "(...) -> string, number?"}
                            {:label "f(...)" :description "string, number?"}]]]
    (is (= expected (presentation/label-parts item)))))

;; Preserve all EmmyLua Markdown annotations and escape literal docs instead of treating them as HTML.
(deftest completion-documentation-test
  (let [markdown "```lua\n(method) Context:newWidget(url: string) -> Context\n```\n\n创建窗口\n\n@*param* `url` — 路径\n\n@*return* `window` — 窗口"]
    (is (= markdown (presentation/documentation {:display-string "newWidget" :doc {:type :markdown :value markdown}})))
    (is (= "<pre>Map&lt;T&gt; &amp; value</pre>"
           (presentation/documentation {:display-string "value" :doc {:type :plaintext :value "Map<T> & value"}})))
    (is (= "<pre>get() : string</pre>"
           (presentation/documentation {:display-string "get" :label-detail "() -> string"})))
    (is (string/includes? (presentation/documentation {:display-string "old" :tags #{:deprecated}}) "Deprecated"))
    (is (= "<pre>f(): number</pre>\n\n说明"
           (presentation/documentation {:display-string "f" :detail "f(): number" :doc {:type :markdown :value "说明"}})))
    (is (string/includes?
          (markdown-view/markdown->html
            (presentation/documentation {:display-string "f" :detail "f(): number" :doc {:type :markdown :value "**说明**"}}))
          "<strong>说明</strong>"))))
