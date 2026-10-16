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

(ns editor.gui-animation-lua
  "Read generated Panthera Lua data tables without executing Lua code."
  (:require [clojure.string :as string]
            [util.coll :as coll])
  (:import [java.io StringReader]
           [java.nio.charset StandardCharsets]
           [org.luaj.vm2 Lua LuaString LuaValue]
           [org.luaj.vm2.ast Exp$Constant Exp$FuncCall Exp$NameExp Exp$ParensExp Exp$UnopExp Stat$Return TableConstructor TableField]
           [org.luaj.vm2.parser LuaParser]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn- read-value-impl [expression resolve-module depth]
  (when (> (long depth) 64) (throw (ex-info "Animation table nesting is too deep." {})))
  (cond
    (instance? Exp$Constant expression)
    (let [^LuaValue value (.-value ^Exp$Constant expression)]
      (cond (.isnil value) nil (.isboolean value) (.toboolean value)
            (= LuaValue/TSTRING (.type value))
            (let [^LuaString text (.checkstring value)]
              (String. (.-m_bytes text) (.-m_offset text) (.-m_length text) StandardCharsets/UTF_8)) (.isnumber value) (.todouble value)
            :else (throw (ex-info "Only literal values are supported." {}))))

    (instance? TableConstructor expression)
    (let [fields (.-fields ^TableConstructor expression)
          values (reduce (fn [{:keys [index entries]} ^TableField field]
                           (let [key (cond (.-name field) (keyword (.-name field))
                                           (.-index field) (read-value-impl (.-index field) resolve-module (inc (long depth)))
                                           :else index)
                                 key (if (string? key) (keyword key) key)
                                 value (read-value-impl (.-rhs field) resolve-module (inc (long depth)))]
                             {:index (if (or (.-name field) (.-index field)) index (inc (long index)))
                              :entries (if (nil? value) (dissoc entries key) (assoc entries key value))}))
                         {:index 1 :entries {}} fields)
          entries (:entries values)
          size (count entries)]
      (if (and (pos? size) (= (set (coll/keys entries)) (set (range 1 (inc size)))))
        (mapv #(get entries %) (range 1 (inc size))) entries))

    (instance? Exp$UnopExp expression)
    (let [value (read-value-impl (.-rhs ^Exp$UnopExp expression) resolve-module (inc (long depth)))]
      (if (and (= Lua/OP_UNM (.-op ^Exp$UnopExp expression)) (number? value))
        (- (double value))
        (throw (ex-info "Only literal numeric negation is supported." {}))))

    (instance? Exp$ParensExp expression)
    (read-value-impl (.-exp ^Exp$ParensExp expression) resolve-module (inc (long depth)))

    (instance? Exp$FuncCall expression)
    (let [call ^Exp$FuncCall expression
          lhs (.-lhs call)
          arguments (.-exps (.-args call))]
      (when-not (and (instance? Exp$NameExp lhs) (= "require" (.-name (.-name ^Exp$NameExp lhs))) (= 1 (count arguments)))
        (throw (ex-info "Only literal require references to template animation data are supported." {})))
      (let [module (read-value-impl (first arguments) resolve-module (inc (long depth)))]
        (when-not (and (string? module) (not (string/blank? module)))
          (throw (ex-info "Invalid template animation module." {})))
        (resolve-module (str "/" (string/replace module "." "/") ".lua"))))

    :else (throw (ex-info "Import expects a generated Lua data table; executable expressions are not supported." {}))))

(defn read-data [text resolve-module]
  (when (> (count text) 16777216) (throw (ex-info "Animation input exceeds 16 MiB." {})))
  ;; LuaJ's AST lexer stores strings as bytes; supply UTF-8 bytes as one-byte
  ;; characters so it does not truncate non-ASCII Java characters.
  (let [byte-text (String. (.getBytes ^String text StandardCharsets/UTF_8) StandardCharsets/ISO_8859_1)
        chunk (.Chunk (LuaParser. (StringReader. byte-text)))
        statements (.-stats (.-block chunk))
        statement (first statements)]
    (when-not (and (= 1 (count statements)) (instance? Stat$Return statement) (= 1 (count (.-values ^Stat$Return statement))))
      (throw (ex-info "Import expects one return statement containing an animation data table." {})))
    (read-value-impl (first (.-values ^Stat$Return statement)) resolve-module 0)))
