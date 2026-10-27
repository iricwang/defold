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

(ns editor.code.folding
  (:require [clojure.string :as string]
            [util.coll :as coll])
  (:import [com.dynamo.bob.pipeline.antlr.lua LuaLexer]
           [org.antlr.v4.runtime ANTLRInputStream Token]))

(set! *warn-on-reflection* true)

(defn regions
  "Find paired, whole-line Lua region comments, ignoring strings and long comments."
  [lines]
  (if-not (coll/any? #(re-find #"^\s*---?\s*region\b" %) lines)
    []
    (let [lexer (doto (LuaLexer. (ANTLRInputStream. ^String (coll/join-to-string "\n" lines)))
                  (.removeErrorListeners))]
      (->> (.getAllTokens lexer)
           (reduce (fn [{:keys [stack] :as result} ^Token token]
                     (if (not= LuaLexer/LINE_COMMENT (.getType token))
                       result
                       (let [row (dec (.getLine token))
                             prefix (subs (lines row) 0 (.getCharPositionInLine token))
                             [_ marker label] (when (string/blank? prefix)
                                                (re-matches #"(?s)---?\s*(region|endregion)\b([^\r\n]*).*" (.getText token)))]
                         (case marker
                           "region" (update result :stack conj {:start row :label (string/trim label)})
                           "endregion" (if-let [start (peek stack)]
                                         (-> result
                                             (update :stack pop)
                                             (update :regions conj (assoc start :end row)))
                                         result)
                           result))))
                   {:stack [] :regions []})
           :regions
           (sort-by :start)
           vec))))

(defn contains-row? [{:keys [start end]} row]
  (< (long start) (long row) (inc (long end))))

(defn effective-collapsed
  "Discard stale folds and reveal destinations reached by navigation or debugging."
  [regions collapsed cursor-ranges execution-markers]
  (into #{}
        (filter (fn [region]
                  (and (contains? collapsed region)
                       (not (coll/any? (fn [range]
                                         (or (contains-row? region (get-in range [:from :row]))
                                             (contains-row? region (get-in range [:to :row]))))
                                       (eduction cat [cursor-ranges execution-markers]))))))
        regions))

(defn mapping
  "Map source rows to compact display rows without touching the source document."
  [line-count collapsed]
  (when (pos? (count collapsed))
    (let [ends (into {} (map (juxt :start :end)) collapsed)]
      (loop [row 0
             visible (transient [])
             visual (transient [])]
        (if (>= row (long line-count))
          {:fold-visible-rows (persistent! visible)
           :fold-visual-rows (persistent! visual)}
          (let [end (long (get ends row row))
                index (count visible)]
            (recur (inc end)
                   (conj! visible row)
                   (reduce (fn [rows _] (conj! rows index)) visual (range row (inc end))))))))))

(defn source-row
  ^long [layout ^long visual-row]
  (if-let [rows (:fold-visible-rows layout)]
    (cond
      (neg? visual-row) visual-row
      (< visual-row (count rows)) (long (rows visual-row))
      :else (+ (count (:fold-visual-rows layout)) (- visual-row (count rows))))
    visual-row))

(defn visual-row
  ^long [layout ^long source-row]
  (if-let [rows (:fold-visual-rows layout)]
    (cond
      (neg? source-row) source-row
      (< source-row (count rows)) (long (rows source-row))
      :else (+ (count (:fold-visible-rows layout)) (- source-row (count rows))))
    source-row))

(defn hidden-row? [layout row]
  (not= row (source-row layout (visual-row layout row))))

(defn line-count
  ^long [layout ^long source-count]
  (if-let [rows (:fold-visible-rows layout)] (count rows) source-count))

(defn next-row
  ^long [layout row]
  (source-row layout (inc (visual-row layout row))))
