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

(ns editor.code.lua-intentions
  (:require [clojure.string :as string]
            [editor.code.data :as data]
            [util.coll :as coll])
  (:import [com.dynamo.bob.pipeline.antlr.lua LuaLexer LuaParser LuaParser$FuncbodyContext LuaParser$FunctionstatContext]
           [org.antlr.v4.runtime ANTLRInputStream BaseErrorListener CommonTokenStream Token]
           [org.antlr.v4.runtime.tree ParseTree TerminalNode]))

(set! *warn-on-reflection* true)

(defn valid-field-name? [name]
  (and (boolean (re-matches #"[a-zA-Z_][a-zA-Z_0-9]*" name))
       (not (contains? #{"and" "break" "do" "else" "elseif" "end" "false" "for" "function"
                         "goto" "if" "in" "local" "nil" "not" "or" "repeat" "return" "then"
                         "true" "until" "while"} name))))

(defn- token-cursor [^Token token end]
  (data/->Cursor (dec (.getLine token)) (+ (.getCharPositionInLine token) (if end (count (.getText token)) 0))))

(defn- parameter-at [^ParseTree tree cursor]
  (or (when (instance? LuaParser$FuncbodyContext tree)
        (when-let [names (some-> ^LuaParser$FuncbodyContext tree .parlist .namelist)]
          (when-let [node (coll/first-where
                           (fn [^TerminalNode node]
                             (let [token (.getSymbol node)]
                               (and (= (:row cursor) (dec (.getLine token)))
                                    (<= (.getCharPositionInLine token) (:col cursor)
                                        (+ (.getCharPositionInLine token) (count (.getText token)))))))
                           (.NAME names))]
            [tree (.getSymbol ^TerminalNode node)])))
      (loop [i 0]
        (when (< i (.getChildCount tree))
          (or (parameter-at (.getChild tree i) cursor) (recur (inc i)))))))

(defn- preceding-docs [lines row]
  (loop [start row]
    (if (and (pos? start) (re-find #"^\s*---" (lines (dec start))))
      (recur (dec start))
      {:start start :lines (subvec lines start row)})))

(defn- class-doc [lines owner row top-level-rows]
  ;; Only attach fields to a class explicitly declared on this table in this
  ;; file. Guessing another class (or a cross-file alias) can corrupt its docs.
  (when (and owner (contains? top-level-rows row) (not (string/includes? owner ".")))
    (let [binding-pattern (re-pattern (str "^\\s*(?:local\\s+)?" owner "\\s*=\\s*"))]
      (when-let [binding-row (coll/first-where #(and (contains? top-level-rows %)
                                                    (re-find binding-pattern (lines %)))
                                              (range (dec row) -1 -1))]
        (when (re-find #"=\s*\{" (lines binding-row))
          (let [docs (preceding-docs lines binding-row)]
            (when-let [offset (coll/first-where
                                #(re-find #"^\s*---@class\s+" (get (:lines docs) %))
                                (range (count (:lines docs))))]
              (assoc docs :row (+ (:start docs) offset)))))))))

(defn parameter-context
  "Find a real Lua parameter declaration. Parsing is performed on invocation,
  never during painting or command enablement. UTF-16 matches editor columns."
  [lines cursor]
  (let [errors (volatile! false)
        listener (proxy [BaseErrorListener] []
                   (syntaxError [_ _ _ _ _ _] (vreset! errors true)))
        lexer (doto (LuaLexer. (ANTLRInputStream. ^String (coll/join-to-string "\n" lines)))
                (.removeErrorListeners) (.addErrorListener listener))
        tokens (CommonTokenStream. lexer)
        parser (doto (LuaParser. tokens) (.removeErrorListeners) (.addErrorListener listener))
        tree (.chunk parser)]
    (when-not @errors
      (when-let [[^LuaParser$FuncbodyContext body ^Token parameter] (parameter-at tree cursor)]
        (let [parent (.getParent body)
              start (.getStart parent)
              row (dec (.getLine start))
              prefix (subs (lines row) 0 (.getCharPositionInLine start))
              method-name (when (instance? LuaParser$FunctionstatContext parent)
                            (.getText (.funcname ^LuaParser$FunctionstatContext parent)))
              owner (when method-name (second (re-matches #"(.+):[a-zA-Z_][a-zA-Z_0-9]*" method-name)))
              name (.getText parameter)
              docs (preceding-docs lines row)
              annotation (coll/first-where
                           #(re-find (re-pattern (str "^\\s*---@param\\s+" name "\\??(?:\\s|$)")) %)
                           (:lines docs))]
          ;; An expression embedded in another statement has no independent doc
          ;; owner. Named functions and standalone assignments do.
          (when (or (string/blank? prefix)
                    (re-matches #"\s*(?:local\s+)?[a-zA-Z_][a-zA-Z_0-9.]*\s*=\s*" prefix))
            {:parameter name
             :row row
             :indent (re-find #"^\s*" (lines row))
             :annotation annotation
             :docs docs
             :owner owner
             :class-doc (class-doc lines owner row
                          (into #{} (map #(dec (.getLine (.getStart ^org.antlr.v4.runtime.ParserRuleContext %))))
                                (.stat (.block tree))))
             :body-start (token-cursor (.getSymbol (.RPAREN body)) true)
             :body-end (token-cursor (.getSymbol (.END body)) false)
             :tokens (into [] (comp (filter #(= Token/DEFAULT_CHANNEL (.getChannel ^Token %)))
                                    (map #(.getText ^Token %)))
                           (.getTokens tokens (.getTokenIndex (.getSymbol (.RPAREN body)))
                                       (.getTokenIndex (.getSymbol (.END body)))))}))))))

(defn actions [context]
  (cond-> []
    (:owner context) (conj :field)
    (and context (not (:annotation context))) (conj :parameter)))

(defn field-exists? [context field-name]
  (let [tokens (:tokens context)]
    (or (boolean (coll/first-where
                   #(= ["self" "." field-name "="] (subvec tokens % (+ % 4)))
                   (range (max 0 (- (count tokens) 3)))))
        (boolean (coll/first-where
                   #(re-find (re-pattern (str "^\\s*---@field\\s+(?:(?:public|private|protected)\\s+)?"
                                              (java.util.regex.Pattern/quote field-name) "(?:\\s|$)")) %)
                   (get-in context [:class-doc :lines]))))))

(defn parameter-edit [context]
  (let [row (:row context)
        prefix (str (:indent context) "---@param " (:parameter context) " ")
        cursor (data/->Cursor row 0)]
    {:edits [[(data/->CursorRange cursor cursor) [(str prefix "table") ""]]]
     :selection (data/->CursorRange (data/->Cursor row (count prefix))
                                   (data/->Cursor row (+ 5 (count prefix))))}))

(defn field-edit [lines context field-name indent-type create-doc]
  (when (and (:owner context) (valid-field-name? field-name) (not (field-exists? context field-name)))
    (let [{:keys [body-start body-end indent parameter class-doc annotation]} context
          next-row (inc (:row body-start))
          tail (subs (lines (:row body-start)) (:col body-start))
          multiline (and (< (:row body-start) (:row body-end))
                         (or (string/blank? tail)
                             (and (re-find #"^\s*--" tail) (not (re-find #"^\s*--\[=*\[" tail)))))
          next-indent (when multiline (re-find #"^\s*" (lines next-row)))
          body-indent (if (and next-indent (> (count next-indent) (count indent)))
                        next-indent
                        (str indent (data/indent-type->indent-string indent-type)))
          assignment (str body-indent "self." field-name " = " parameter)
          cursor (if multiline (data/->Cursor next-row 0) body-start)
          replacement (if multiline [assignment ""] ["" assignment body-indent])
          doc-row (when (and create-doc class-doc) (inc (:row class-doc)))
          doc-cursor (when doc-row (data/->Cursor doc-row 0))
          field-type (or (when annotation (second (re-find #"---@param\s+\S+\s+(.+)" annotation))) "table")
          doc-edit (when doc-row [(data/->CursorRange doc-cursor doc-cursor)
                                  [(str (re-find #"^\s*" (lines (:row class-doc))) "---@field public " field-name " " field-type) ""]])
          target (data/->Cursor (+ next-row (if doc-row 1 0)) (count assignment))]
      {:edits (cond-> [] doc-edit (conj doc-edit) true (conj [(data/->CursorRange cursor cursor) replacement]))
       :selection (data/->CursorRange target target)})))
