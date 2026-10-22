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

(ns editor.code.insight
  (:require [clojure.string :as string]
            [util.coll :as coll]))

(set! *warn-on-reflection* true)

(defn- common-prefix-length [a b]
  (let [limit (min (count a) (count b))]
    (loop [i 0]
      (if (and (< i limit) (= (nth a i) (nth b i)))
        (recur (inc i))
        i))))

(defn pending-value [kind value previous-lines lines previous-cursor cursor]
  (cond
    (= kind :signature-help)
    (when (= (:row previous-cursor) (:row cursor)) value)

    (= previous-lines lines)
    value

    :else
    (let [prefix (common-prefix-length previous-lines lines)
          previous-count (count previous-lines)
          line-count (count lines)
          delta (- line-count previous-count)
          suffix (loop [n 0]
                   (if (and (< n (- (min previous-count line-count) prefix))
                            (= (get previous-lines (- previous-count n 1))
                               (get lines (- line-count n 1))))
                     (recur (inc n))
                     n))]
      (reduce-kv
        (fn [result row entries]
          (if-let [new-row (cond
                            (< (long row) prefix) row
                            (>= (long row) (- previous-count suffix)) (+ (long row) delta)
                            (zero? delta) row)]
            (let [old-line (get previous-lines row)
                  line (get lines new-row)
                  entries (if (or (= kind :inlay-hints) (= old-line line))
                            entries
                            ;; Keep colors only where the token still refers to the same text.
                            (into [] (filter (fn [[start end _]]
                                               (and (<= (long end) (count line))
                                                    (= (subs old-line start end) (subs line start end))))) entries))]
              (assoc result new-row entries))
            result))
        (empty value)
        value))))

(def semantic-scopes
  {"namespace" "entity.name.type" "type" "entity.name.type" "class" "entity.name.type"
   "enum" "entity.name.type" "interface" "entity.name.type" "struct" "entity.name.type"
   "typeParameter" "entity.name.type" "parameter" "variable.parameter"
   "variable" "variable" "property" "variable.other.member" "enumMember" "constant.language"
   "function" "entity.name.function" "method" "entity.name.function" "macro" "entity.name.function"
   "keyword" "keyword.control" "modifier" "storage.modifier" "comment" "comment"
   "string" "string" "number" "constant.numeric" "operator" "keyword.operator"
   "decorator" "storage.type"})

(defn semantic-tokens [{:keys [data legend]} lines]
  (loop [groups (into [] (partition-all 5) data) row 0 col 0 result {}]
    (if-let [[delta-row delta-col length token-type _modifiers :as group] (first groups)]
      (if (not= 5 (count group))
        result
        (let [row (+ (long row) (long delta-row))
              col (+ (if (zero? (long delta-row)) (long col) 0) (long delta-col))
              end (+ col (long length))
              line (get lines row)
              scope (get semantic-scopes (get (:tokenTypes legend) token-type))]
          (recur (subvec groups 1) row col
                 (cond-> result
                   (and scope line (<= 0 col) (< col end) (<= end (count line)))
                   (update row (fnil conj []) [col end scope])))))
      result)))

(defn overlay-syntax [syntax-info tokens]
  (reduce-kv
    (fn [result row spans]
      (if-let [[context runs] (get result row)]
        (assoc result row
               [context
                (reduce
                  (fn [runs [start end scope]]
                    (let [resume (reduce (fn [scope [offset next-scope]]
                                           (if (<= (long offset) (long end)) next-scope (reduced scope)))
                                         nil runs)]
                      (->> runs
                           (into [[start scope] [end resume]]
                                 (remove (fn [[offset _]] (<= (long start) (long offset) (long end)))))
                           (sort-by first)
                           vec)))
                  runs spans)])
        result))
    syntax-info tokens))

(defn signature [response]
  (let [signatures (:signatures response)
        index (min (max 0 (long (or (:activeSignature response) 0))) (max 0 (dec (count signatures))))]
    (when-let [signature (get signatures index)]
      (let [label (:label signature)
            active (long (or (:activeParameter signature) (:activeParameter response) 0))
            parameter (get (:parameters signature) active)
            parameter-label (:label parameter)
            parameter-label (if (and (vector? parameter-label) (= 2 (count parameter-label)))
                              (let [[start end] parameter-label]
                                (when (<= 0 (long start) (long end) (count label))
                                  (subs label start end)))
                              parameter-label)
            documentation (or (:documentation parameter) (:documentation signature))]
        {:label label :parameter parameter-label
         :documentation (if (map? documentation) (:value documentation) documentation)
         :overloads (count signatures)}))))

(defn line-hints [hints lines]
  (reduce (fn [result [row label]] (update result row (fnil conj []) label))
          (sorted-map)
          (eduction
            (keep (fn [{:keys [position label]}]
                    (let [{:keys [line character]} position
                          text (get lines line)
                          label (if (string? label) label (coll/join-to-string "" (mapv :value label)))]
                      (when (and text (integer? character) (<= 0 character (count text)) (not (string/blank? label)))
                        (let [type-hint (string/starts-with? label ":")
                              symbol (if type-hint
                                       (re-find #"[\w.]+$" (subs text 0 character))
                                       (re-find #"^[\w.]+" (subs text character)))]
                          (when symbol
                            [line (if type-hint (str symbol label) (str label " " symbol))]))))))
            (distinct)
            hints)))
