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

(ns editor.skeletal-animation-navigation-test
  (:require [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-navigation :as nav]
            [editor.ui :as ui])
  (:import [javafx.event Event]
           [javafx.scene Node Scene]
           [javafx.scene.control ComboBox TextField TreeView]
           [javafx.scene.input KeyCode KeyEvent]
           [javafx.scene.layout VBox]))

;; Empty, group, and multiple selection must not inherit the last primary node.
(deftest timeline-scope-test
  (is (nav/single-node? {:selected "tail" :selected-kind :bone}))
  (is (not (nav/single-node? {:selected nil :selected-kind :bone})))
  (is (not (nav/single-node? {:selected "root" :selected-kind :root})))
  (is (not (nav/single-node? {:selected "tail" :selected-kind :bone :selected-bones #{"tail" "body"}})))
  (is (not (nav/single-node? {:selected "tail" :selected-kind :bone :selected-bones #{"tail"} :selected-slots #{"skin"}}))))

;; Frame stepping is snapped, bounded, pauses playback, and keeps selection.
(deftest transport-state-test
  (let [state {:time 0.51 :preview-time 0.0 :fps 10.0 :playing true :selected "tail"}]
    (doseq [[command expected] [[:previous-frame 0.5] [:next-frame 0.6] [:back-ten 0.0] [:forward-ten 1.51]
                                [:previous-key 0.3] [:next-key 0.8] [:start 0.0] [:end 2.0]]]
      (let [result (nav/transport-state state command 2.0 [0.3 0.8])]
        (is (< (abs (- expected (:time result))) 1.0e-8))
        (is (= (:time result) (:preview-time result)))
        (is (= "tail" (:selected result)))
        (is (false? (:playing result)))))
    (is (false? (:playing (nav/transport-state state :play 2.0 []))))
    (is (= 0.0 (:time (nav/transport-state {:time 2.0 :playing false} :play 2.0 []))))
    (is (= 0.0 (:time (nav/transport-state {:time 0.0} :previous-frame 2.0 []))))
    (is (= 2.0 (:time (nav/transport-state {:time 2.0} :next-frame 2.0 []))))
    (is (false? (:playing (nav/transport-state {:time 0.0} :play 0.0 []))))))

(defn- key-event [type code shift alt]
  (KeyEvent. type "" "" code shift false alt false))

;; Repeated Space presses toggle only once. Text fields and hierarchy arrow
;; navigation retain native behavior; reinstalling adds no duplicate filter.
(deftest scoped-shortcut-events-test
  (ui/run-now
    (let [text (TextField.) combo (ComboBox.) tree (TreeView.) canvas (VBox.)
          root (VBox. (into-array Node [text combo tree canvas]))
          _scene (Scene. root)
          calls (atom []) action (fn record-command [command] (swap! calls conj command))
          send-key (fn send-key [target code shift alt]
                     (Event/fireEvent target (key-event KeyEvent/KEY_PRESSED code shift alt))
                     (Event/fireEvent target (key-event KeyEvent/KEY_RELEASED code shift alt)))]
      (nav/install-shortcuts! root action)
      (nav/install-shortcuts! root action)
      (dotimes [_ 5] (Event/fireEvent canvas (key-event KeyEvent/KEY_PRESSED KeyCode/SPACE false false)))
      (is (= [] @calls))
      (Event/fireEvent canvas (key-event KeyEvent/KEY_RELEASED KeyCode/SPACE false false))
      (is (= [:play] @calls))
      (send-key canvas KeyCode/RIGHT false false)
      (send-key canvas KeyCode/LEFT true false)
      (send-key canvas KeyCode/RIGHT false true)
      (send-key canvas KeyCode/HOME false false)
      (send-key canvas KeyCode/END false false)
      (is (= [:play :next-frame :back-ten :next-key :start :end] @calls))
      (reset! calls [])
      (doseq [target [text combo]]
        (send-key target KeyCode/SPACE false false)
        (send-key target KeyCode/RIGHT false false))
      (send-key tree KeyCode/LEFT false false)
      (send-key tree KeyCode/HOME false false)
      (is (= [] @calls))
      (send-key tree KeyCode/SPACE false false)
      (is (= [:play] @calls)))))
