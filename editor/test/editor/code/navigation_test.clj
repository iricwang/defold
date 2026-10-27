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

(ns editor.code.navigation-test
  (:require [clojure.test :refer [deftest is]]
            [editor.code.navigation :as navigation]
            [editor.keymap :as keymap]))

(def a {:resource-node 1 :cursor-ranges [10] :scroll-y -40})
(def b {:resource-node 2 :cursor-ranges [20] :scroll-y -80})
(def c {:resource-node 3 :cursor-ranges [30] :scroll-y -120})

;; Back and forward restore departures, including edits to the current cursor and viewport.
(deftest round-trip-test
  (let [history (navigation/visit nil a b)
        moved-b (assoc b :cursor-ranges [27] :scroll-y -160)
        back (navigation/travel history moved-b :back (constantly true))
        forward (navigation/travel (:history back) a :forward (constantly true))]
    (is (= a (:destination back)))
    (is (= [moved-b] (get-in back [:history :forward])))
    (is (= moved-b (:destination forward)))
    (is (= [a] (get-in forward [:history :back])))))

;; Explicit jumps within the same script must retain both cursor positions.
(deftest same-file-jump-test
  (let [next-a (assoc a :cursor-ranges [90])
        history (navigation/visit nil a next-a)]
    (is (= a (:destination (navigation/travel history next-a :back (constantly true)))))))

;; Returning to the initial target after moving the caret still records the departure.
(deftest moved-caret-return-test
  (let [moved (assoc a :cursor-ranges [42])
        history (navigation/visit (navigation/visit nil nil a) moved a)]
    (is (= [moved] (:back history)))
    (is (= a (:current history)))))

;; A fresh destination after Back replaces Forward; refocusing the same location does not.
(deftest branch-and-duplicate-test
  (let [back (:history (navigation/travel (navigation/visit nil a b) b :back (constantly true)))
        duplicate (navigation/visit back a a)
        branch (navigation/visit back a c)]
    (is (= back duplicate))
    (is (= [b] (:forward duplicate)))
    (is (= [] (:forward branch)))
    (is (= [a] (:back branch)))
    (is (= branch (navigation/visit branch a c)))))

;; Deleted resources are skipped; exhausting a stack leaves the active location intact.
(deftest deleted-resource-test
  (let [history (navigation/visit (navigation/visit nil a b) b c)
        back (navigation/travel history c :back #(not= 2 (:resource-node %)))
        exhausted (navigation/travel history c :back (constantly false))]
    (is (= a (:destination back)))
    (is (= [c] (get-in back [:history :forward])))
    (is (nil? (:destination exhausted)))
    (is (= c (get-in exhausted [:history :current])))
    (is (= [] (get-in exhausted [:history :back])))))

;; Visiting non-code content retains the last script position without making a fake entry.
(deftest leave-code-test
  (let [moved (assoc b :scroll-y -200)
        history (navigation/visit (navigation/visit nil a b) moved nil)]
    (is (= moved (:current history)))
    (is (= [a] (:back history)))))

;; Limit retained history and keep distinct project histories independent.
(deftest bounded-project-history-test
  (let [history (reduce #(navigation/visit %1 (:current %1) {:resource-node %2}) nil (range 150))]
    (is (= 100 (count (:back history))))
    (is (= {:resource-node 49} (first (:back history))))
    (is (nil? (:destination (navigation/travel nil nil :back (constantly true)))))
    (is (= [] (:back (navigation/visit nil nil c))))))

;; Windows and Linux match IDEA navigation; macOS uses Command-Option arrows.
(deftest navigation-shortcuts-test
  (doseq [platform [:win32 :linux]]
    (let [bindings (set (keymap/platform->default-key-bindings platform))]
      (is (contains? bindings ["Ctrl+Alt+Left" :code.navigate-back]))
      (is (contains? bindings ["Ctrl+Alt+Right" :code.navigate-forward]))))
  (is (contains? (set (:macos keymap/platform->default-key-bindings)) ["Alt+Meta+Left" :code.navigate-back])))
