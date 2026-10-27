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

(ns editor.code.lua-highlight
  (:require [clojure.string :as string]
            [util.coll :as coll])
  (:import [com.dynamo.bob.pipeline.antlr.lua LuaLexer LuaParser]
           [org.antlr.v4.runtime ANTLRInputStream BaseErrorListener CommonTokenStream]
           [org.antlr.v4.runtime.tree ParseTree TerminalNode]))

(set! *warn-on-reflection* true)

;; Resolved from IDEA 2025.3 Default -> Darcula, EmmyLua's Darcula defaults,
;; then the user's Darcula_Contrast.icls. Keep specific scopes before fallbacks.
(def paints
  [["lua.std" "#CC542E"]
   ["lua.parameter" "#0F9795"]
   ["lua.self" "#AB51BA"]
   ["lua.global" "#9876AA"]
   ["lua.static-method" "#FFC66D"]
   ["lua.method" "#A9B7C6"]
   ["lua.local" "#A9B7C6"]
   ["lua.field" "#A9B7C6"]
   ["lua.doc.tag" "#629755"]
   ["lua.doc.value" "#8A653B"]
   ["lua.doc.class" "#A9B7C6"]
   ["lua.doc.primitive" "#00E5E5"]
   ["lua.doc.region" "#0088FF"]
   ["lua.doc.keyword" "#CC7832"]
   ["lua.doc.type" "#769AA5"]
   ["lua.doc" "#629755"]
   ["punctuation.definition.string" "#6A8759"]
   ["constant.character.escape" "#CC7832"]
   ["comment" "#808080"]
   ["string" "#6A8759"]
   ["numeric" "#6897BB"]
   ["keyword.operator" "#A9B7C6"]
   ["keyword" "#CC7832"]
   ["storage" "#CC7832"]
   ["constant.language" "#CC7832"]
   ["variable.language.self" "#AB51BA"]
   ["variable.parameter" "#0F9795"]
   ["parameter.function" "#0F9795"]
   ["support.function.any-method" "#A9B7C6"]
   ["support.function" "#CC542E"]
   ["name.function" "#A9B7C6"]
   ["type" "#769AA5"]
   ["punctuation.separator" "#CC7832"]
   ["punctuation" "#A9B7C6"]
   ["editor.foreground" "#A9B7C6"]
   ["editor.background" "#2B2D30"]
   ["editor.cursor" "#BBBBBB"]
   ["editor.selection.background" "#214283"]
   ["editor.selection.background.inactive" "#323232"]
   ["editor.gutter.foreground" "#4E5157"]
   ["editor.gutter.cursor.foreground" "#9DA0A8"]
   ["editor.gutter.background" "#313335"]
   ["editor.gutter.cursor.line.background" "#2B2D30"]
   ["editor.gutter.cursor.line.background.inactive" "#2B2D30"]
   ["editor.indentation.guide" "#313438"]
   ["editor.matching.brace" "#CED9D2"]
   ["editor.matching.brace.background" "#326146"]
   ["editor.whitespace.space" "#606060"]
   ["editor.whitespace.tab" "#606060"]
   ["editor.error" "#BC3F3C"]
   ["editor.warning" "#BE9117"]
   ["editor.info" "#A9B7C6"]
   ["editor.debug" "#6897BB"]])

(defn font-style [scope]
  (cond
    (or (string/includes? scope ".upvalue") (string/includes? scope "lua.doc.tag")) :bold-italic
    (or (string/includes? scope "lua.global") (string/includes? scope "lua.static-method")
        (contains? #{"comment.lua.doc" "comment.line.documentation.lua.doc"} scope)) :italic
    :else :normal))

(defn underline [scope]
  (cond
    (string/includes? scope ".upvalue") "#A8C023"
    (string/includes? scope "lua.doc.tag") "#629755"))

(defn- node-kind [^ParseTree node]
  (.getSimpleName (class node)))

(defn local-spans
  "Resolve lexical bindings and captures in a worker, never during painting.
  Incomplete input falls back to the incremental grammar and language server."
  [lines]
  (let [errors (volatile! false)
        listener (proxy [BaseErrorListener] []
                   (syntaxError [_ _ _ _ _ _] (vreset! errors true)))
        lexer (doto (LuaLexer. (ANTLRInputStream. ^String (coll/join-to-string "\n" lines)))
                (.removeErrorListeners) (.addErrorListener listener))
        parser (doto (LuaParser. (CommonTokenStream. lexer))
                 (.removeErrorListeners) (.addErrorListener listener))
        tree (.chunk parser)
        scopes (volatile! [{}])
        depth (volatile! 0)
        spans (volatile! {})]
    (when-not @errors
      (letfn [(children [^ParseTree node]
                (mapv #(.getChild node (int %)) (range (.getChildCount node))))
              (names [node]
                (into [] (filter #(and (instance? TerminalNode %)
                                      (= LuaLexer/NAME (.getType (.getSymbol ^TerminalNode %))))) (children node)))
              (emit! [^TerminalNode node scope]
                (let [token (.getSymbol node)
                      start (.getCharPositionInLine token)]
                  (vswap! spans update (dec (.getLine token)) (fnil conj [])
                          [start (+ start (count (.getText token))) scope])))
              (binding [name]
                (coll/first-where identity (eduction (map #(get % name)) (rseq @scopes))))
              (bind! [^TerminalNode node scope]
                (vswap! scopes update (dec (count @scopes)) assoc (.getText node) [@depth scope])
                (emit! node scope))
              (reference! [^TerminalNode node]
                (let [name (.getText node)]
                  (if-let [[owner scope] (binding name)]
                    (emit! node (str scope (when (< (long owner) (long @depth)) ".upvalue")))
                    (emit! node (if (= name "self") "lua.self" "lua.global")))))
              (nested! [f]
                (vswap! scopes conj {})
                (f)
                (vswap! scopes pop))
              (walk-children! [node]
                (run! walk! (children node)))
              (walk! [node]
                (let [kind (node-kind node)
                      cs (children node)
                      child (fn [kind] (coll/first-where #(= kind (node-kind %)) cs))]
                  (case kind
                    "BlockContext" (nested! #(walk-children! node))
                    "LocalvariablestatContext"
                    (do
                      (when-let [expression (child "ExplistContext")] (walk! expression))
                      (run! #(bind! % "lua.local") (names (child "AttnamelistContext"))))
                    "LocalfunctionstatContext"
                    (do (run! #(bind! % "lua.local") (names node))
                        (walk! (child "FuncbodyContext")))
                    "FuncbodyContext"
                    (do
                      (vswap! depth inc)
                      (nested!
                        #(do
                           (when (and (= "FunctionstatContext" (node-kind (.getParent ^ParseTree node)))
                                      (string/includes? (.getText (.getChild (.getParent ^ParseTree node) 1)) ":"))
                             (vswap! scopes update (dec (count @scopes)) assoc "self" [@depth "lua.self"]))
                           (when-let [parameters (child "ParlistContext")]
                             (when-let [list (coll/first-where (fn [n] (= "NamelistContext" (node-kind n))) (children parameters))]
                               (run! (fn [n] (bind! n "lua.parameter")) (names list))))
                           (walk! (child "BlockContext"))))
                      (vswap! depth dec))
                    "NamedvariableContext" (reference! (first (names node)))
                    "FuncnameContext"
                    (let [ns (names node)]
                      (reference! (first ns))
                      (doseq [n (subvec ns 1)]
                        (emit! n (if (identical? n (peek ns))
                                   (if (string/includes? (.getText ^ParseTree node) ":") "lua.method" "lua.static-method")
                                   "lua.field"))))
                    "IndexContext"
                    (do (walk-children! node)
                        (run! #(emit! % (if (= "FunctioncallContext" (node-kind (.getParent ^ParseTree node)))
                                         "lua.static-method" "lua.field")) (names node)))
                    "NameAndArgsContext"
                    (do (walk-children! node) (run! #(emit! % "lua.method") (names node)))
                    "FieldContext"
                    (do (walk-children! node) (run! #(emit! % "lua.field") (names node)))
                    ("NumericforstatContext" "GenericforstatContext")
                    (do
                      (run! walk! (filterv #(contains? #{"ExpContext" "ExplistContext"} (node-kind %)) cs))
                      (nested! #(do (run! (fn [n] (bind! n "lua.local"))
                                         (if-let [list (child "NamelistContext")] (names list) (names node)))
                                    (walk! (child "BlockContext")))))
                    "RepeatstatContext"
                    (nested! #(do (walk-children! (child "BlockContext")) (walk! (child "ExpContext"))))
                    (walk-children! node))))]
        (walk! tree)
        @spans))))

(defn merge-spans [local semantic lines]
  (let [semantic (into {}
                       (map (fn [[row spans]]
                              [row (if (string/starts-with? (string/triml (get lines row "")) "---")
                                     (into []
                                           (keep (fn [[start end scope]]
                                                   (let [text (subs (get lines row) start end)
                                                         scope (cond
                                                                 (string/starts-with? text "@") nil
                                                                 (= "comment" scope) (when-not (string/includes? text "@") "comment.lua.doc")
                                                                 (contains? #{"string" "number" "integer" "boolean" "table" "nil" "any" "unknown"} text) "comment.lua.doc.primitive"
                                                                 (and (pos? start) (= \@ (get (get lines row) (dec start)))) "comment.lua.doc.tag"
                                                                 (contains? #{"lua.parameter" "lua.field"} scope) "comment.lua.doc.value"
                                                                 (and (= scope "entity.name.type") (re-find #"^\s*---@(?:class|alias)\s+$" (subs (get lines row) 0 start))) "comment.lua.doc.class"
                                                                 (= scope "entity.name.type") "comment.lua.doc.type"
                                                                 (= scope "keyword.control") "comment.lua.doc.keyword")]
                                                     (when scope [start end scope]))))
                                           spans)
                                     spans)]))
                       semantic)]
    (reduce-kv
      (fn [result row spans]
        (let [server (get semantic row)
              starts (into #{} (map first) spans)]
          (assoc result row
                 (into (filterv #(not (contains? starts (first %))) server)
                       (map (fn [[start end _scope :as span]]
                              (let [server-scope (nth (coll/first-where #(= [start end] (subvec % 0 2)) server) 2 nil)]
                                (if (= server-scope "lua.std") [start end server-scope] span))))
                       spans))))
      semantic local)))
