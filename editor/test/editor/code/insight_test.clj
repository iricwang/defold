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

(ns editor.code.insight-test
  (:require [clojure.test :refer :all]
            [editor.code.insight :as insight]
            [editor.lsp.server :as server]))

;; Keeps hints during asynchronous typing updates, moves unchanged rows, and drops deleted rows.
(deftest pending-insights-test
  (let [lines ["local count = 42" "print(count)"]
        hints {0 ["count: number"] 1 ["value: count"]}]
    (is (= hints (insight/pending-value :inlay-hints hints lines lines nil nil)))
    (is (= hints (insight/pending-value :inlay-hints hints lines ["local count = 420" "print(count)"] nil nil)))
    (is (= {1 ["count: number"] 2 ["value: count"]}
           (insight/pending-value :inlay-hints hints lines (into [""] lines) nil nil)))
    (is (= {0 ["value: count"]}
           (insight/pending-value :inlay-hints hints lines ["print(count)"] nil nil)))
    (is (nil? (insight/pending-value :inlay-hints nil nil lines nil nil))))
  (let [signature {:label "print(value)"}]
    (is (= signature (insight/pending-value :signature-help signature ["print(a)"] ["print(ab)"] {:row 0 :col 7} {:row 0 :col 8})))
    (is (nil? (insight/pending-value :signature-help signature ["print(a)" ""] ["print(a)" ""] {:row 0 :col 7} {:row 1 :col 0}))))
  (is (= {0 [[0 5 "function"]]}
         (insight/pending-value :semantic-tokens {0 [[0 5 "function"] [6 7 "variable"]]}
                                ["print(a)"] ["print(b)"] nil nil))))

;; Checks relative UTF-16 token decoding across rows, rejecting ranges beyond the document.
(deftest semantic-token-decoding-test
  (is (= {0 [[6 11 "variable"]] 1 [[0 5 "entity.name.function"]]}
         (insight/semantic-tokens {:legend {:tokenTypes ["variable" "function"]}
                                  :data [0 6 5 0 0 1 0 5 1 0 0 99 5 0 0]}
                                 ["local count = 1" "print(count)"])))
  (is (= {} (insight/semantic-tokens nil [""])))
  (is (= {} (insight/semantic-tokens {:data [0 1]} [""]))))

;; Checks semantic colors restore lexical colors outside token spans, including adjacent tokens.
(deftest semantic-overlay-test
  (let [syntax [[[] [[0 "keyword"] [5 "source"] [10 "comment"]]]]]
    (is (= [[[] [[0 "keyword"] [5 "source"] [6 "variable"] [9 "source"] [10 "comment"]]]]
           (insight/overlay-syntax syntax {0 [[6 9 "variable"]]})))
    (is (= syntax (insight/overlay-syntax syntax {})))
    (is (= syntax (insight/overlay-syntax syntax {5 [[0 2 "variable"]]})))))

;; Checks the server's active parameter override and UTF-16 label offsets including non-ASCII text.
(deftest signature-help-test
  (is (= {:label "add(a, b)" :parameter "b" :documentation "second" :overloads 1}
         (insight/signature {:activeParameter 0
                             :signatures [{:label "add(a, b)" :activeParameter 1
                                           :parameters [{:label [4 5]}
                                                        {:label [7 8] :documentation {:kind "markdown" :value "second"}}]}]})))
  (is (nil? (insight/signature nil)))
  (is (= "参数" (:parameter (insight/signature {:activeSignature nil :signatures [{:label "f(参数)" :parameters [{:label [2 4]}]}]})))))

;; Checks that hints are grouped by source line and never change source offsets or include invalid positions.
(deftest inlay-hints-test
  (is (= {0 ["count: number"]}
         (insight/line-hints [{:position {:line 0 :character 11} :label ": number"}
                              {:position {:line 0 :character 11} :label [{:value ": number"}]}
                              {:position {:line 0 :character 100} :label ": invalid"}]
                             ["local count = 42"])))
  (is (= {0 ["value: count"]}
         (insight/line-hints [{:position {:line 0 :character 6} :label "value:"}]
                             ["print(count)"])))
  (is (= {} (insight/line-hints [{:position {:line 0 :character 8} :label "module:"}]
                               ["require \"module\""])))
  (is (= {} (insight/line-hints nil [""]))))

;; Checks newly advertised protocol capabilities and absence of optional server features.
(deftest insight-capabilities-test
  (let [caps (#'server/lsp-capabilities->editor-capabilities
               {:signatureHelpProvider {:triggerCharacters ["(" ","]}
                :semanticTokensProvider {:full true :legend {:tokenTypes ["variable"]}}
                :inlayHintProvider true})]
    (is (:signature-help caps))
    (is (:inlay-hints caps))
    (is (= {:tokenTypes ["variable"]} (:semantic-tokens caps))))
  (let [caps (#'server/lsp-capabilities->editor-capabilities {})]
    (is (false? (:signature-help caps)))
    (is (false? (:inlay-hints caps)))
    (is (nil? (:semantic-tokens caps)))))
