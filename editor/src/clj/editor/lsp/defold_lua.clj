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

(ns editor.lsp.defold-lua
  "Virtual lifecycle annotations for EmmyLua. Only protocol documents are changed;
  source files, editor buffers and returned edit coordinates remain original."
  (:require [clojure.core.async :as a :refer [>!]]
            [clojure.data.json :as json]
            [clojure.string :as string]
            [util.coll :as coll])
  (:import [java.util.regex Matcher]))

(set! *warn-on-reflection* true)

(def ^:private signatures
  {"init" ["script_instance"]
   "final" ["script_instance"]
   "update" ["script_instance" "number"]
   "late_update" ["script_instance" "number"]
   "fixed_update" ["script_instance" "number"]
   "on_message" ["script_instance" "hash" "table<any, any>" "url"]
   "on_input" ["script_instance" "hash|nil" "on_input.action"]
   "on_reload" ["script_instance"]})

(defn- code-only [text]
  ;; Mask strings and comments without moving line/UTF-16 offsets. Unfinished
  ;; long strings/comments consume the rest of the document as Lua does.
  (string/replace text
                  #"(?s)--\[(=*)\[.*?(?:\]\1\]|\z)|--[^\r\n]*|\[(=*)\[.*?(?:\]\2\]|\z)|\"(?:\\.|[^\"\\])*?(?:\"|\z)|'(?:\\.|[^'\\])*?(?:'|\z)"
                  (fn [match] (string/replace (first match) #"[^\r\n]" " "))))

(defn- declarations [text]
  (let [^Matcher matcher (re-matcher #"(?m)^\h*(?:function\s+(?:_G\.)?(\w+)|_G\.(\w+)\s*=\s*function)\s*\(([^)]*)\)" (code-only text))]
    (loop [result {} previous-start 0 row 0]
      (if-not (.find matcher)
        result
        (let [start (.start matcher)
              row (+ (long row) (dec (count (string/split (subs text previous-start start) #"\n" -1))))]
          (recur (assoc result row [(or (.group matcher 1) (.group matcher 2)) (.group matcher 3)]) start row))))))

(defn document [uri text]
  (let [lines (string/split text #"\n" -1)
        extension (last (string/split uri #"\."))
        allowed (case extension
                  "script" signatures
                  "gui_script" (dissoc signatures "late_update" "fixed_update")
                  "render_script" (select-keys signatures ["init" "update" "on_message" "on_reload"])
                  {})
        insertions
        (reduce-kv
          (fn [result row [name parameters]]
            (if-let [types (allowed name)]
              (let [start (loop [row row]
                            (if (and (pos? row) (re-find #"^\s*---" (get lines (dec row))))
                              (recur (dec row))
                              row))
                    annotations (subvec lines start row)
                    existing (into #{} (keep #(second (re-find #"^\s*---@param\s+(?:\[)?(\w+)" %))) annotations)
                    names (mapv string/trim (string/split parameters #","))
                    injected (into []
                                   (keep (fn [[name type]]
                                           (when (and (re-matches #"[A-Za-z_]\w*" name) (not (contains? existing name)))
                                             (str "---@param " name " " type " __DEFOLD_LIFECYCLE__"))))
                                   (mapv vector names types))]
                (cond-> result (pos? (count injected)) (assoc start injected)))
              result))
          {} (if (zero? (count allowed)) {} (declarations text)))]
    (reduce-kv
      (fn [{:keys [virtual-lines] :as result} row line]
        (let [inserted (get insertions row [])
              generated (+ (count virtual-lines) (count inserted))]
          (-> result
              (update :virtual-lines into inserted)
              (update :virtual-lines conj line)
              (update :to-original into (mapv (constantly nil) inserted))
              (update :to-original conj row)
              (update :to-virtual conj generated))))
      {:lines lines :virtual-lines [] :to-original [] :to-virtual []}
      lines)))

(defn- position [doc direction {:keys [line] :as p}]
  (let [rows (get doc direction)]
    (if (or (nil? rows) (neg? (long line)))
      p
      (if-let [row (get rows line)]
        (assoc p :line row)
        ;; Synthetic annotations map to the following source declaration.
        (assoc p :line (or (coll/first-where some? (subvec rows (min (count rows) line)))
                           (count (get doc (if (= :to-original direction) :lines :virtual-lines))))
                 :character 0)))))

(defn- clean-text [text]
  (string/replace text #"(?m)^\h*---\s*@param[^\r\n]* __DEFOLD_LIFECYCLE__\h*\r?\n?" ""))

(defn remap [docs uri direction value]
  (cond
    (vector? value) (mapv #(remap docs uri direction %) value)
    (map? value)
    (if (and (integer? (:line value)) (integer? (:character value)))
      (position (get docs uri) direction value)
      (let [target (or (:targetUri value) (:uri value) (get-in value [:textDocument :uri]) uri)]
        (reduce-kv
          (fn [result key v]
            (assoc result key
                   (case key
                     :data v ; opaque server-owned completion data
                     :originSelectionRange (remap docs uri direction v)
                     :changes (into {} (map (fn [[key edits]]
                                             [key (remap docs (if (keyword? key) (subs (str key) 1) key) direction edits)])) v)
                     :newText (if (= direction :to-original) (clean-text v) v)
                     (remap docs target direction v))))
          {} value)))
    (and (string? value) (= direction :to-original)) (string/replace value #" ?__DEFOLD_LIFECYCLE__" "")
    :else value))

(defn- change-text [lines {:keys [range text]}]
  (if-not range
    (string/split text #"\n" -1)
    (let [{start :start end :end} range
          inserted (string/split (str (subs (get lines (:line start)) 0 (:character start))
                                      text
                                      (subs (get lines (:line end)) (:character end))) #"\n" -1)]
      (into (into (subvec lines 0 (:line start)) inserted) (subvec lines (inc (long (:line end))))))))

(defn- remap-tokens [doc data]
  (:tokens
    (reduce
      (fn [{:keys [row col last-row last-col] :as state} [dr dc length kind modifiers]]
        (let [row (+ (long row) (long dr))
              col (+ (if (zero? (long dr)) (long col) 0) (long dc))
              original (if doc (get (:to-original doc) row) row)
              state (assoc state :row row :col col)]
          (if-not original
            state
            (-> state
                (update :tokens into [(- (long original) (long last-row))
                                      (if (= original last-row) (- col (long last-col)) col)
                                      length kind modifiers])
                (assoc :last-row original :last-col col)))))
      {:row 0 :col 0 :last-row 0 :last-col 0 :tokens []}
      (eduction (partition-all 5) data))))

(defn outgoing [state message]
  (let [{:keys [id method params]} message
        uri (or (get-in params [:textDocument :uri]) (:_defold-uri params))]
    (case method
      ("textDocument/didOpen" "textDocument/didChange")
      (let [text (if (= method "textDocument/didOpen")
                   (get-in params [:textDocument :text])
                   (coll/join-to-string "\n" (reduce change-text (get-in state [:docs uri :lines]) (:contentChanges params))))
            doc (document uri text)
            text (coll/join-to-string "\n" (:virtual-lines doc))]
        [(assoc-in state [:docs uri] doc)
         (if (= method "textDocument/didOpen")
           (assoc-in message [:params :textDocument :text] text)
           (assoc-in message [:params :contentChanges] [{:text text}]))])

      "textDocument/didClose"
      [(update state :docs dissoc uri) message]

      [(cond-> state (and id method) (assoc-in [:requests id] {:uri uri :method method :docs (:docs state)}))
       (if uri
         (update message :params #(remap (:docs state) uri :to-virtual (dissoc % :_defold-uri)))
         message)])))

(defn incoming [state message]
  (let [{:keys [id method params]} message
        request (get-in state [:requests id])
        docs (or (:docs request) (:docs state))
        uri (or (:uri request) (:uri params))
        message (remap docs uri :to-original message)
        message (cond
                  (= "textDocument/semanticTokens/full" (:method request))
                  (update-in message [:result :data] #(remap-tokens (get docs uri) %))

                  (= "textDocument/completion" (:method request))
                  (update message :result
                          (fn [result]
                            (let [tag #(mapv (fn [item] (assoc item :_defold-uri uri)) %)]
                              (if (map? result) (update result :items tag) (tag result)))))

                  :else message)]
    [(if (and id (not method)) (update state :requests dissoc id) state) message]))

(defn wrap [source sink]
  (let [to-client (a/chan 128)
        from-client (a/chan 128)]
    (a/go-loop [state {:docs {} :requests {}} source source client from-client]
      (if (and source client)
        (let [[text channel] (a/alts! [source client])]
          (if text
            (let [[state message] ((if (= channel client) outgoing incoming) state (json/read-str text :key-fn keyword))]
              (>! (if (= channel client) sink to-client) (json/write-str message :key-fn #(if (keyword? %) (subs (str %) 1) %)))
              (recur state source client))
            (if (= channel client)
              (do (a/close! sink) (recur state source nil))
              (do (a/close! to-client) (recur state nil client)))))
        (do (a/close! sink) (a/close! to-client) (a/close! from-client))))
    [to-client from-client]))
