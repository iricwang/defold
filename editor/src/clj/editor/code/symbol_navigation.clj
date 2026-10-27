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

(ns editor.code.symbol-navigation
  (:require [editor.code.data :as data]
            [editor.resource :as resource]
            [util.coll :as coll]))

(defn identifier-range [lines {:keys [row col]}]
  (when-let [line (get lines row)]
    (when (< -1 col (count line))
      (let [matcher (re-matcher #"[\p{L}_][\p{L}\p{N}_]*" line)]
        (loop []
          (when (.find matcher)
            (if (<= (.start matcher) col (dec (.end matcher)))
              (data/->CursorRange (data/->Cursor row (.start matcher))
                                 (data/->Cursor row (.end matcher)))
              (recur))))))))

(defn ordered-results [results]
  (->> results
       (into [] (comp (filter #(and (:resource %) (:cursor-range %))) (distinct)))
       (sort-by (juxt #(resource/proj-path (:resource %))
                      #(get-in % [:cursor-range :from :row])
                      #(get-in % [:cursor-range :from :col])))
       vec))

(defn at-definition? [source cursor results]
  (and (= 1 (count results))
       (= source (:resource (first results)))
       (let [range (:cursor-range (first results))]
         (and (not (pos? (compare (data/cursor-range-start range) cursor)))
              (not (pos? (compare cursor (data/cursor-range-end range))))))))

(defn matching-results [results query]
  (let [query (.toLowerCase ^String query java.util.Locale/ROOT)]
    (into [] (filter #(coll/any? (fn [text]
                                 (.contains (.toLowerCase ^String text java.util.Locale/ROOT) query))
                               [(:path %) (:snippet %)])) results)))
