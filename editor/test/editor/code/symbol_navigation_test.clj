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

(ns editor.code.symbol-navigation-test
  (:require [clojure.test :refer [deftest is testing]]
            [editor.code.data :as data]
            [editor.code.symbol-navigation :as navigation]
            [editor.keymap :as keymap]))

;; Pointer hit testing must not resolve the adjacent identifier when hovering punctuation or whitespace.
(deftest identifier-hit-test
  (let [lines ["local player = module.run()"]]
    (is (= (data/->CursorRange (data/->Cursor 0 6) (data/->Cursor 0 12))
           (navigation/identifier-range lines (data/->Cursor 0 8))))
    (doseq [col [-1 5 12 13 14 21 25 26 27 200]]
      (is (nil? (navigation/identifier-range lines (data/->Cursor 0 col))))))
  (is (nil? (navigation/identifier-range [] (data/->Cursor 2 0))))
  (is (= 2 (get-in (navigation/identifier-range ["变量()"] (data/->Cursor 0 1)) [:to :col]))))

;; Navigation on a declaration should offer its uses, while other positions still jump to the definition.
(deftest declaration-hit-test
  (let [range (data/->CursorRange (data/->Cursor 2 3) (data/->Cursor 2 6))
        results [{:resource :a :cursor-range range}]]
    (is (navigation/at-definition? :a (data/->Cursor 2 4) results))
    (is (not (navigation/at-definition? :b (data/->Cursor 2 4) results)))
    (is (not (navigation/at-definition? :a (data/->Cursor 3 4) results)))
    (is (not (navigation/at-definition? :a (data/->Cursor 2 4) [])))))

;; Reference filtering searches both source code and paths, preserving the ordered result sequence.
(deftest reference-filter-test
  (let [a {:path "/main.script" :snippet "player.run()"}
        b {:path "/module.lua" :snippet "function run()"}]
    (is (= [a b] (navigation/matching-results [a b] "RUN")))
    (is (= [a] (navigation/matching-results [a b] "MAIN")))
    (is (= [] (navigation/matching-results [a b] "missing")))
    (is (= [a b] (navigation/matching-results [a b] "")))))

;; IDEA assignments replace conflicting build/file/quit commands instead of accidentally executing them.
(deftest idea-preset-conflict-test
  (doseq [os [:win32 :linux :macos]]
    (let [modifier (if (= :macos os) "Meta" "Ctrl")
          m (keymap/preset-keymap os "IntelliJ IDEA")]
      (doseq [[suffix command] [["+B" :code.goto-definition] ["+F9" :project.build]
                                ["+Alt+F7" :code.show-references] ["+E" :file.open-recent]
                                ["+F12" :code.jump-to-symbol]]]
        (is (= #{command} (keymap/commands m (str modifier suffix)))))
      (is (= #{:code.show-references} (keymap/commands m "Alt+F7")))
      (is (= #{:edit.rename} (keymap/commands m "Shift+F6")))
      (is (not (contains? (keymap/commands m (str modifier "+P")) :file.open)))
      (is (not (contains? (keymap/commands m (str modifier "+Q")) :app.quit)))
      (testing "Defold preset and custom overrides remain available"
        (is (= (keymap/default os) (keymap/preset-keymap os "Defold")))
        (is (= #{:project.build}
               (keymap/commands
                 (keymap/from m os {:code.goto-definition {:remove #{(str modifier "+B")}}
                                   :project.build {:add #{(str modifier "+B")}}})
                 (str modifier "+B"))))))))

;; Explicit user shortcuts own their keys even when a new preset assigns them to a different command.
(deftest custom-shortcuts-override-preset-test
  (let [base (keymap/preset-keymap :win32 "IntelliJ IDEA")
        custom (keymap/with-user-shortcuts base :win32 {:project.build {:add #{"Ctrl+B"}}})]
    (is (= #{:project.build} (keymap/commands custom "Ctrl+B")))
    (is (= #{:code.goto-definition} (keymap/commands custom "F12")))
    (is (= base (keymap/with-user-shortcuts base :win32 {})))))
