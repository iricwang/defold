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

(ns editor.gui-animation-inspector-test
  (:require [clojure.test :refer [deftest is]]
            [editor.gui-animation-data :as animation]
            [editor.gui-animation-inspector :as inspector]
            [editor.gui-animation-view :as view]
            [editor.ui :as ui])
  (:import [javafx.scene.canvas Canvas]
           [javafx.event ActionEvent]
           [javafx.scene.control Button CheckBox ComboBox TextField]
           [javafx.scene.layout Region]))

;; Sharing a pose must invalidate on seek/edit/clip changes and retain only the last input.
(deftest preview-cache-test
  (let [calls (atom 0)
        evaluate (#'view/cache-last (fn [document animation-id time]
                                     (swap! calls inc)
                                     (animation/evaluate document animation-id time)))
        document (animation/add-animation (animation/new-document "/main.gui") "other" 1.0)
        edited (animation/replace-key document "default" nil
                                      {:key_type "trigger" :node_id "panel" :property_id "visible"
                                       :start_time 0.5 :duration 0 :data "false"})]
    (doseq [[doc time expected-calls] [[document 0.0 1] [document 0.0 1]
                                      [edited 0.0 2] [edited 0.75 3]
                                      [edited 0.75 3] [edited 0.0 4]]]
      (is (= (animation/evaluate doc "default" time) (evaluate doc "default" time)))
      (is (= expected-calls @calls)))
    (is (= {} (evaluate document "default" 0.0)))
    (is (= {} (evaluate document "other" 0.0)))
    (is (= 6 @calls))))

;; Selection, edits and clearing must address one segment, never a stale list selection.
(deftest selected-segment-inspector-test
  (ui/run-now
    (let [event {:key_type "event" :event_id "hit" :start_time 0.25 :duration 0 :data "blade"}
          trigger {:key_type "trigger" :node_id "panel" :property_id "visible" :start_time 0.5 :duration 0 :data "false"}
          document (-> (animation/new-document "/main.gui")
                       (animation/replace-key "default" nil event)
                       (animation/replace-key "default" nil trigger))
          state (atom {:document document :animation-id "default" :selected-raw nil :selected-raws #{}})
          panel (inspector/create! {:state state
                                    :commit! #(swap! state (fn [s] (assoc s :document (% (:document s)) :selected-raw nil :selected-raws #{})))
                                    :set-time! #(swap! state assoc :time %)
                                    :localize! (fn [_ _])
                                    :guard (fn [f] (fn [_] (f)))
                                    :template-document! (constantly document)
                                    :node-names (constantly ["panel"])})
          ^Region root (:node panel)
          ^TextField event-field (.lookup root "#gui-animation-key-event")
          ^TextField data-field (.lookup root "#gui-animation-key-data")
          ^Button apply-button (.lookup root "#gui-animation-apply-segment")]
      ((:refresh! panel))
      (is (.isVisible (.lookup root "#gui-animation-selection-empty")))
      (is (nil? (.lookup root "#gui-animation-segments")))
      ((:select! panel) 0)
      ((:refresh! panel))
      (is (= "hit" (.getText event-field)))
      (is (= 0.25 (:time @state)))
      (is (not (.isVisible (.lookup root "#gui-animation-key-property"))))
      (is (not (.isVisible (.lookup root "#gui-animation-key-from"))))
      (.setText ^TextField (.lookup root "#gui-animation-key-duration") "0.3")
      (is (.isVisible (.lookup root "#gui-animation-key-from")))
      (.setText ^TextField (.lookup root "#gui-animation-key-duration") "0")
      (.setText data-field "sword")
      (.fire apply-button)
      ((:refresh! panel))
      (is (= 0 (:selected-raw @state)))
      (is (= "sword" (:data (first (animation/animation-keys (:document @state) "default")))))
      (is (= trigger (get (animation/animation-keys (:document @state) "default") 1)))
      ((:select! panel) 1)
      ((:refresh! panel))
      (is (.isDisabled event-field))
      (is (= "false" (.getText data-field)))
      (is (not (.isVisible event-field)))
      (is (not (.isVisible (.lookup root "#gui-animation-key-duration"))))
      (let [^CheckBox check (.lookup root "#gui-animation-key-data-boolean")]
        (is (.isVisible check))
        (is (not (.isVisible data-field)))
        (.fire check)
        (.fire apply-button)
        ((:refresh! panel))
        (is (= "true" (:data (get (animation/animation-keys (:document @state) "default") 1)))))
      (.setText (.getEditor ^ComboBox (.lookup root "#gui-animation-key-property")) "blend_mode")
      (let [^ComboBox choice (.lookup root "#gui-animation-key-data-enum")
            ^ComboBox initial (.lookup root "#gui-animation-key-start-data-enum")]
        (is (.isVisible choice))
        (.setValue initial "alpha")
        (.fireEvent initial (ActionEvent.))
        (.setValue choice "multiply")
        (.fireEvent choice (ActionEvent.))
        (.fire apply-button)
        ((:refresh! panel))
        (is (= "multiply" (:data (get (animation/animation-keys (:document @state) "default") 1))))
        (is (= "alpha" (:start_data (get (animation/animation-keys (:document @state) "default") 1)))))
      (swap! state assoc :selected-raw nil :selected-raws #{0})
      ((:refresh! panel))
      (is (not (.isDisabled event-field)))
      (is (= "sword" (.getText data-field)))
      (swap! state assoc :selected-raw nil :selected-raws #{})
      ((:refresh! panel))
      (is (.isVisible (.lookup root "#gui-animation-selection-empty"))))))

;; Empty track space must not select a segment; zoom and offset must affect its hit area.
(deftest segment-hit-area-test
  (ui/run-now
    (let [canvas (Canvas. 432.0 200.0)
          state {:offset 0.5 :zoom 2.0
                 :rows [{:kind :segment :key-index 3 :node-name "panel"
                         :key {:key_type "event" :start_time 0.75 :duration 0}}]}]
      (is (= 3 (get-in (#'view/timeline-key canvas state 220.0 46.0) [2 :raw-index])))
      (is (nil? (#'view/timeline-key canvas state 100.0 46.0)))
      (is (nil? (#'view/timeline-key canvas state 300.0 46.0)))
      (is (nil? (#'view/timeline-key canvas state 220.0 12.0))))))
