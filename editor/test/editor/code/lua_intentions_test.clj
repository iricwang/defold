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

(ns editor.code.lua-intentions-test
  (:require [clojure.test :refer [deftest is]]
            [editor.code.data :as data]
            [editor.code.lua-intentions :as intentions]
            [editor.keymap :as keymap]))

(defn- context [lines row col]
  (intentions/parameter-context lines (data/->Cursor row col)))

(defn- edited [lines edit]
  (:lines (data/apply-edits lines [] [] (:edits edit))))

;; Intention eligibility must refer to declarations, never strings, comments or usages.
(deftest parameter-context-test
  (let [lines ["-- function M:fake(x) end" "local s = 'function M:fake(x) end'"
               "function M:show(bInMenu)" "    return bInMenu" "end"]]
    (is (= [:field :parameter] (intentions/actions (context lines 2 22))))
    (doseq [[row col] [[0 19] [1 27] [3 14] [2 10]]]
      (is (nil? (context lines row col)))))
  (doseq [line ["local function f(x) end" "function f(x) end" "local f = function(x) end"]]
    (is (= [:parameter] (intentions/actions (context [line] 0 (.indexOf ^String line "x"))))))
  (is (nil? (context ["function M:f(x)" "end ?"] 0 13)))
  (is (nil? (context ["function M:f(x" "end"] 0 13))))

;; Multiline and nested function parameters must use their own body and doc owner.
(deftest multiline-nested-test
  (let [lines ["function M:outer(" "    first," "    second)" "    local function inner(first)" "    end" "end"]
        outer (context lines 2 10)
        inner (context lines 3 29)]
    (is (= "second" (:parameter outer)))
    (is (= "first" (:parameter inner)))
    (is (= 3 (:row inner)))
    (is (= [:parameter] (intentions/actions inner)))
    (is (= ["function M:outer(" "    first," "    second)" "    self.value = second"
            "    local function inner(first)" "    end" "end"]
           (edited lines (intentions/field-edit lines outer "value" :four-spaces false))))))

;; Existing documentation stays intact, optional parameter annotations are not duplicated.
(deftest parameter-annotation-test
  (let [lines ["---中文说明 😀" "---@param other string" "---@return boolean" "function M:f(x, other)" "end"]
        ctx (context lines 3 14)
        edit (intentions/parameter-edit ctx)
        result (edited lines edit)]
    (is (= ["---中文说明 😀" "---@param other string" "---@return boolean" "---@param x table" "function M:f(x, other)" "end"] result))
    (is (= (data/->CursorRange (data/->Cursor 3 12) (data/->Cursor 3 17)) (:selection edit)))
    (is (= [:field] (intentions/actions (context result 4 14))))
    (is (= [:field] (intentions/actions (context lines 3 20)))))
  (is (= [:field] (intentions/actions (context ["---@param x? boolean" "function M:f(x) end"] 1 13)))))

;; Generated field assignments respect indentation and are inserted before the body.
(deftest field-assignment-test
  (doseq [[lines expected] [[["function M:f(x)" "\treturn x" "end"]
                            ["function M:f(x)" "\tself.value = x" "\treturn x" "end"]]
                           [["function M:f(x)" "end"]
                            ["function M:f(x)" "    self.value = x" "end"]]
                           [["function M:f(x) return x end"]
                            ["function M:f(x)" "    self.value = x" "     return x end"]]]]
    (is (= expected (edited lines (intentions/field-edit lines (context lines 0 13) "value" :four-spaces false)))))
  (let [lines ["---@class Screen" "local M = {}" "" "---@param x boolean" "function M:f(x)" "end"]
        ctx (context lines 4 13)
        edit (intentions/field-edit lines ctx "value" :two-spaces true)]
    (is (= ["---@class Screen" "---@field public value boolean" "local M = {}" ""
            "---@param x boolean" "function M:f(x)" "  self.value = x" "end"] (edited lines edit)))
    (is (= 6 (:row (:from (:selection edit)))))))

;; Invalid identifiers and duplicate assignments/annotations cannot be generated.
(deftest field-validation-test
  (doseq [name ["" "end" "a.b" "has space" "1x" "x\nreturn"]]
    (is (not (intentions/valid-field-name? name))))
  (doseq [name ["value" "_value2"]] (is (intentions/valid-field-name? name)))
  (let [lines ["function M:f(x)" "    self.value = x" "end"] ctx (context lines 0 13)]
    (is (intentions/field-exists? ctx "value"))
    (is (nil? (intentions/field-edit lines ctx "value" :four-spaces false))))
  (let [lines ["---@class Screen" "---@field private value boolean" "local M = {}" "function M:f(x) end"] ctx (context lines 3 13)]
    (is (intentions/field-exists? ctx "value"))))

;; A class on another table must never receive this method's field documentation.
(deftest class-ownership-test
  (let [lines ["---@class Other" "local N = {}" "local M = {}" "function M:f(x) end"]]
    (is (nil? (:class-doc (context lines 3 13)))))
  (let [lines ["---@class Screen" "local M = {}" "function M:f(x) end"]]
    (is (= 0 (get-in (context lines 2 13) [:class-doc :row])))))

;; Alt+Enter is consistent across platforms and presets; explicit custom mappings win.
(deftest intention-keymap-test
  (doseq [os [:win32 :macos :linux] preset ["Defold" "IntelliJ IDEA"]]
    (let [base (keymap/preset-keymap os preset)]
      (is (= #{:code.lua-intentions} (keymap/commands base "Alt+Enter")))
      (is (= #{:project.build} (keymap/commands
                                (keymap/with-user-shortcuts base os {:project.build {:add #{"Alt+Enter"}}}) "Alt+Enter"))))))

;; Inline statements and long comments on the signature must remain after the new assignment.
(deftest signature-tail-test
  (doseq [lines [["function M:f(x) print(x)" "end"]
                 ["function M:f(x) --[[ note" "more note ]]" "return x" "end"]]]
    (let [ctx (context lines 0 13)
          result (edited lines (intentions/field-edit lines ctx "value" :four-spaces false))]
      (is (= "    self.value = x" (get result 1)))
      (is (some? (context result 0 13))))))

;; Rebinding, nested classes and text in long strings cannot redirect class documentation.
(deftest class-shadowing-test
  (doseq [lines [["---@class Old" "local M = {}" "local M = {}" "function M:f(x) end"]
                 ["---@class Old" "local M = {}" "M = require('other')" "function M:f(x) end"]
                 ["local text = [[" "---@class Fake" "local M = {}" "]]" "function M:f(x) end"]
                 ["do" "---@class Hidden" "local M = {}" "end" "function M:f(x) end"]]]
    (is (nil? (:class-doc (context lines (dec (count lines)) 13))))))

;; A generic type containing spaces must not be truncated into an invalid @field type.
(deftest field-type-test
  (let [lines ["---@class Menu" "local M = {}" "---@param x table<string, number> values" "function M:f(x) end"]
        result (edited lines (intentions/field-edit lines (context lines 3 13) "value" :four-spaces true))]
    (is (= "---@field public value table<string, number> values" (get result 1)))))
