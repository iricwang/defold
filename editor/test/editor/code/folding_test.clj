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

(ns editor.code.folding-test
  (:require [clojure.test :refer [deftest is]]
            [editor.code.data :as data]
            [editor.code.folding :as folding]))

(def sample
  ["local M = {}" "--region Lifecycle" "function M.init()" "end"
   "--- region Nested" "local value = 1" "--- endregion" "--endregion"
   "return M"])

;; Recognize paired/nested IDEA markers, but never markers inside strings or long comments.
(deftest regions-test
  (is (= [{:start 1 :end 7 :label "Lifecycle"} {:start 4 :end 6 :label "Nested"}]
         (folding/regions sample)))
  (is (= [] (folding/regions ["local s = [=[" "--region text" "--endregion" "]=]"
                              "--[[" "--region comment" "--endregion" "]]"
                              "local s = '--region inline'" "return s --region trailing"])))
  (is (= [] (folding/regions ["--endregion" "--region unfinished"])))
  (is (= [{:start 1 :end 2 :label "名字"}]
         (folding/regions ["--regional ignored" "  -- region 名字" "  -- endregion"]))))

;; Hidden rows map to the fold header; visible rows round-trip, including the EOF sentinel.
(deftest mapping-test
  (let [[outer inner] (folding/regions sample)
        layout (folding/mapping (count sample) #{outer inner})]
    (is (= [0 1 8] (:fold-visible-rows layout)))
    (is (= 3 (folding/line-count layout 9)))
    (doseq [row [0 1 8 9 10 -1]]
      (is (= row (folding/source-row layout (folding/visual-row layout row)))))
    (doseq [row (range 2 8)]
      (is (folding/hidden-row? layout row)))
    (is (= 8 (folding/next-row layout 1)))
    (is (= [0 1 2 3 4 7 8] (:fold-visible-rows (folding/mapping 9 #{inner}))))
    (is (nil? (folding/mapping 9 #{})))))

;; Navigation and debugger destinations reveal all enclosing folds, preserving unrelated folds.
(deftest reveal-test
  (let [[outer inner :as regions] (folding/regions sample)
        cursor (fn [row] (let [c (data/->Cursor row 0)] (data/->CursorRange c c)))]
    (is (= #{outer inner} (folding/effective-collapsed regions #{outer inner} [(cursor 0)] [])))
    (is (= #{} (folding/effective-collapsed regions #{outer inner} [(cursor 5)] [])))
    (is (= #{inner} (folding/effective-collapsed regions #{outer inner} [(cursor 2)] [])))
    (is (= #{} (folding/effective-collapsed regions #{outer inner} [] [(cursor 5)])))
    (is (= #{} (folding/effective-collapsed [] #{outer inner} [] [])))))

(def metrics
  (reify data/GlyphMetrics
    (ascent [_] 8.0)
    (line-height [_] 12.0)
    (char-width [_ _] 6.0)))

(defn folded-layout [lines regions]
  (let [mapping (folding/mapping (count lines) (set regions))]
    (merge (data/layout-info 600.0 24.0 400.0 0.0 0.0 (:fold-visible-rows mapping)
                            40.0 12.0 metrics 4 true)
           mapping)))

;; Hit testing, vertical movement, minimap and scrolling use visual rows, but return source coordinates.
(deftest layout-navigation-test
  (let [layout (folded-layout sample [(first (folding/regions sample))])
        cursor (data/->CursorRange (data/->Cursor 1 0) (data/->Cursor 1 0))
        down (data/move sample [cursor] layout :navigation :down)
        minimap (data/minimap-layout-info layout (count sample) metrics 4)]
    (is (= 24.0 (data/row->y layout 8)))
    (is (= 8 (data/y->row layout 25.0)))
    (is (= 8 (get-in down [:cursor-ranges 0 :to :row])))
    (is (= 1 (get-in (data/move sample (:cursor-ranges down) layout :navigation :up) [:cursor-ranges 0 :to :row])))
    (is (= -12.0 (data/scroll-to-bottom layout (count sample))))
    (is (= 8 (data/y->row minimap 25.0)))
    (is (= 8 (data/last-visible-row layout)))
    (is (= [] (data/visible-cursor-ranges sample layout
                                         [(data/->CursorRange (data/->Cursor 3 0) (data/->Cursor 3 0))])))))
