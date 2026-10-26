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

(ns editor.code.lua-highlight-test
  (:require [clojure.test :refer :all]
            [editor.code.data :as data]
            [editor.code.insight :as insight]
            [editor.code.lua-highlight :as highlight]
            [editor.code.script :as script]
            [editor.code.view :as view]))

(defn- colored-names [lines]
  (into (sorted-map)
        (map (fn [[row spans]]
               [row (mapv (fn [[start end scope]] [(subs (get lines row) start end) scope]) spans)]))
        (highlight/local-spans lines)))

;; Mirrors the exported background override and the resolved IDEA/EmmyLua colors.
(deftest palette-test
  (let [palette (into {} highlight/paints)]
    (is (= "#2B2D30" (get palette "editor.background")))
    (is (= "#A9B7C6" (get palette "editor.foreground")))
    (is (= ["#CC7832" "#6A8759" "#6897BB" "#0F9795" "#AB51BA" "#9876AA"]
           (mapv palette ["keyword" "string" "numeric" "lua.parameter" "lua.self" "lua.global"])))
    (is (= :bold-italic (highlight/font-style "lua.parameter.upvalue")))
    (is (= "#A8C023" (highlight/underline "lua.local.upvalue")))
    (is (= :italic (highlight/font-style "lua.global")))))

;; Resolves parameter uses and captures across function boundaries, including implicit self.
(deftest bindings-and-captures-test
  (let [colors (colored-names ["local x = 1" "function M:foo(arg)"
                              " return function() return x + arg + self.value end" "end"])]
    (is (= [["x" "lua.local"]] (get colors 0)))
    (is (= [["M" "lua.global"] ["foo" "lua.method"] ["arg" "lua.parameter"]] (get colors 1)))
    (is (= [["x" "lua.local.upvalue"] ["arg" "lua.parameter.upvalue"]
            ["self" "lua.self.upvalue"] ["value" "lua.field"]] (get colors 2)))))

;; Local initializers see outer bindings; do/end, loop and repeat scopes do not leak names.
(deftest shadowing-and-blocks-test
  (let [colors (colored-names ["local x = 1" "do local x = x end" "print(x)"
                              "for i = 1, x do print(i) end" "print(i)"
                              "repeat local done = true until done" "print(done)"])]
    (is (= [["x" "lua.local"] ["x" "lua.local"]] (get colors 1)))
    (is (= [["print" "lua.global"] ["x" "lua.local"]] (get colors 2)))
    (is (= "lua.global" (second (last (get colors 4)))))
    (is (= [["done" "lua.local"] ["done" "lua.local"]] (get colors 5)))
    (is (= "lua.global" (second (last (get colors 6)))))))

;; Function recursion is local; dot methods, colon methods and plain fields stay distinct.
(deftest functions-and-members-test
  (let [colors (colored-names ["local function recurse(n) return recurse(n) end"
                              "M.run() M:run() M.value = 1"
                              "local t = { name = 1 }"])]
    (is (= [["recurse" "lua.local"] ["n" "lua.parameter"]
            ["recurse" "lua.local.upvalue"] ["n" "lua.parameter"]] (get colors 0)))
    (is (= [["M" "lua.global"] ["run" "lua.static-method"] ["M" "lua.global"]
            ["run" "lua.method"] ["M" "lua.global"] ["value" "lua.field"]] (get colors 1)))
    (is (= [["name" "lua.field"] ["t" "lua.local"]] (get colors 2)))))

;; ANTLR positions use UTF-16; names in strings/comments never create bindings and invalid input falls back.
(deftest lexical-safety-test
  (let [lines ["local text = '中文😀'; print(text)" "-- local hidden = 1" "local s = [[function fake(x) end]]"]
        colors (colored-names lines)]
    (is (= [["text" "lua.local"] ["print" "lua.global"] ["text" "lua.local"]] (get colors 0)))
    (is (nil? (get colors 1)))
    (is (= [["s" "lua.local"]] (get colors 2))))
  (is (nil? (highlight/local-spans ["function incomplete("]))))

;; LSP modifier bits are decoded from the supplied legend, not assumed fixed indices.
(deftest semantic-modifiers-test
  (let [response {:lua true :legend {:tokenTypes ["variable" "parameter" "function"]
                                   :tokenModifiers ["global" "defaultLibrary" "definition"]}
                  :data [0 0 1 0 1 0 2 1 1 0 0 2 1 2 2 0 2 4 0 4]}]
    (is (= {0 [[0 1 "lua.global"] [2 3 "lua.parameter"] [4 5 "lua.std"] [6 10 "lua.self"]]}
           (insight/semantic-tokens response ["g p f self"])))
    (is (= "variable" (nth (first (get (insight/semantic-tokens (dissoc response :lua) ["g p f self"]) 0)) 2)))))

;; Server documentation tokens cannot flatten annotation tags, primitive types or explanatory text.
(deftest annotation-overlay-test
  (let [lines ["---@param count number Description" "print(count)"]
        tokens {0 [[0 33 "comment"] [10 15 "lua.parameter"] [16 22 "entity.name.type"]]
                1 [[0 5 "lua.std"]]}
        local {1 [[0 5 "lua.global"] [6 11 "lua.local"]]}
        result (highlight/merge-spans local tokens lines)]
    (is (= [[10 15 "comment.lua.doc.value"] [16 22 "comment.lua.doc.primitive"]] (get result 0)))
    (is (= [[0 5 "lua.std"] [6 11 "lua.local"]] (get result 1)))))

;; Quoted text remains green while commas/operators use their own scopes; doc tags use green emphasis.
(deftest lexical-colors-test
  (let [lines ["local text = \"hello\", 42" "---@param count number" "---Explanation" "-- ordinary"]
        syntax (data/ensure-syntax-info [] 4 lines script/lua-grammar)
        color (fn [row col]
                (let [runs (second (get syntax row))
                      scope (reduce (fn [scope [offset next-scope]] (if (<= offset col) next-scope (reduced scope))) nil runs)]
                  (str (view/color-match @#'view/lua-color-scheme scope))))]
    (is (= "0x6a8759ff" (color 0 13)))
    (is (= "0xcc7832ff" (color 0 20)))
    (is (= "0x6897bbff" (color 0 22)))
    (is (= "0x629755ff" (color 1 4)))
    (is (= "0x629755ff" (color 2 4)))
    (is (= "0x808080ff" (color 3 4)))))
