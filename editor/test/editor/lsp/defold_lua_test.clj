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

(ns editor.lsp.defold-lua-test
  (:require [clojure.string :as string]
            [clojure.test :refer :all]
            [editor.lsp.defold-lua :as lua]
            [util.coll :as coll]))

;; Verifies only real lifecycle declarations are annotated and explicit types win.
(deftest virtual-annotations-test
  (let [text "--[[\nfunction update(self, dt)\n]]\nlocal text = [=[\nfunction init(self)\n]=]\n---@param self Custom\nfunction on_input(self, action_id, action)\n  print(action.x)\nend"
        doc (lua/document "file:///test.gui_script" text)
        virtual (coll/join-to-string "\n" (:virtual-lines doc))]
    (is (= 2 (- (count (:virtual-lines doc)) (count (:lines doc)))))
    (is (string/includes? virtual "---@param action on_input.action"))
    (is (not (string/includes? virtual "---@param self script_instance")))
    (is (= [0 1 2 3 4 5 8 9 10 11] (:to-virtual doc)))
    (is (= (:lines doc) (:virtual-lines (lua/document "file:///test.lua" text)))))
  (is (= [2 3 4 5 6]
         (:to-virtual (lua/document "file:///test.script" "function update(\n self,\n dt\n)\nend"))))
  (is (= ["_G.on_input(a, b, c)"] (:virtual-lines (lua/document "file:///test.script" "_G.on_input(a, b, c)"))))
  (is (= 3 (count (:virtual-lines (lua/document "file:///test.script" "_G.update = function(instance, delta) end"))))))

;; Verifies UTF-16 positions round trip through virtual lines and cross-file workspace edits.
(deftest positions-and-edits-test
  (let [uri "file:///test.script"
        doc (lua/document uri "function update(self, dt)\n print('中文😀', dt)\nend")
        docs {uri doc}
        p {:line 1 :character 17}
        virtual (lua/remap docs uri :to-virtual p)]
    (is (= {:line 3 :character 17} virtual))
    (is (= p (lua/remap docs uri :to-original virtual)))
    (is (= {:line 0 :character 0} (lua/remap docs uri :to-original {:line 0 :character 10})))
    (is (= {:documentation ""} (lua/remap docs uri :to-original {:documentation "__DEFOLD_LIFECYCLE__"})))
    (is (= {:changes {uri [{:range {:start p :end p} :newText "dt"}]}}
           (lua/remap docs nil :to-original {:changes {uri [{:range {:start virtual :end virtual} :newText "dt"}]}})))
    (is (= {:uri "file:///other.lua" :range {:start virtual :end virtual}}
           (lua/remap docs uri :to-original {:uri "file:///other.lua" :range {:start virtual :end virtual}})))
    (is (= {:newText "function update(self, dt)\nend\n"}
           (lua/remap docs uri :to-original {:newText "---@param dt number __DEFOLD_LIFECYCLE__\nfunction update(self, dt)\nend\n"})))))

;; Verifies incremental source edits are reannotated, responses use their request snapshot, and close drops state.
(deftest document-lifecycle-test
  (let [uri "file:///test.script"
        [state opened] (lua/outgoing {} {:method "textDocument/didOpen" :params {:textDocument {:uri uri :version 1 :text "function update(self, dt)\n print(dt)\nend"}}})
        [state request] (lua/outgoing state {:id 1 :method "textDocument/hover" :params {:textDocument {:uri uri} :position {:line 1 :character 7}}})
        [changed-state changed] (lua/outgoing state {:method "textDocument/didChange" :params {:textDocument {:uri uri :version 2} :contentChanges [{:range {:start {:line 0 :character 9} :end {:line 0 :character 15}} :text "foo"}]}})
        [finished response] (lua/incoming changed-state {:id 1 :result {:range {:start {:line 3 :character 7} :end {:line 3 :character 9}}}})]
    (is (string/includes? (get-in opened [:params :textDocument :text]) "__DEFOLD_LIFECYCLE__"))
    (is (= {:line 3 :character 7} (get-in request [:params :position])))
    (is (= [{:text "function foo(self, dt)\n print(dt)\nend"}] (get-in changed [:params :contentChanges])))
    (is (= {:line 1 :character 7} (get-in response [:result :range :start])))
    (is (nil? (get-in finished [:requests 1])))
    (is (= {} (:docs (first (lua/outgoing finished {:method "textDocument/didClose" :params {:textDocument {:uri uri}}})))))))

;; Verifies semantic deltas drop generated annotation tokens without shifting source colors.
(deftest semantic-token-remapping-test
  (let [uri "file:///test.script"
        doc (lua/document uri "function update(self, dt)\n print(dt)\nend")
        state {:docs {uri doc} :requests {1 {:uri uri :method "textDocument/semanticTokens/full" :docs {uri doc}}}}
        [_ response] (lua/incoming state {:id 1 :result {:data [0 0 3 0 0 2 9 6 1 0 1 1 5 1 0]}})]
    (is (= [0 9 6 1 0 1 1 5 1 0] (get-in response [:result :data])))))
