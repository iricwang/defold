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

(ns editor.gui-animation-events-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [editor.gui-animation-data :as animation]
            [editor.gui-animation-io :as animation-io]
            [editor.gui-animation-timeline :as timeline]))

(defn- event-document []
  (reduce (fn [document [time id]]
            (animation/replace-key document "default" nil
                                   {:key_type "event" :event_id id :start_time time :duration 0 :data "payload"}))
          (animation/new-document "/main.gui") [[0 "start"] [0.5 "hit"] [1 "end"]]))

;; Crossed events include loop seams and reverse passes, without replaying a previous pulse's endpoint.
(deftest playback-event-crossings-test
  (let [document (event-document)
        events #(timeline/playback-events document "default" %1 %2 %3 %4 %5 %6)
        ids #(mapv :event-id %)]
    (is (= ["start"] (ids (events 0 0 false :normal 1 true))))
    (is (= ["hit"] (ids (events 0 0.5 false :normal 1 false))))
    (is (= [] (events 0.5 0 false :normal 1 false)))
    (is (= ["end"] (ids (events 0.5 0.5 false :normal 1 false))))
    (is (= ["hit"] (ids (events 1 0.5 false :reverse -1 false))))
    (is (= ["hit" "start" "end"] (ids (events 0.75 0.8 true :normal 1 false))))
    (is (= ["hit" "end"] (ids (events 0.75 0.8 false :ping-pong 1 false))))
    (is (= [-1 1] (mapv :direction (events 0.75 0.8 false :ping-pong 1 false))))
    (is (= ["start"] (ids (events 0.25 0.5 false :ping-pong -1 false))))
    (is (= 200 (count (events 0 10000 true :normal 1 false))))))

;; Export markers survive reopening and ignore themselves; edits and undo correctly toggle stale status.
(deftest export-status-test
  (let [document (event-document)
        signature (animation-io/export-signature document)
        exported (assoc-in document [:data :metadata :editor_export_signature] signature)
        edited (animation/replace-key exported "default" 1
                                      {:key_type "event" :event_id "changed" :start_time 0.5 :duration 0 :data "new"})]
    (is (= :export-needed (animation-io/export-status document)))
    (is (= :exported (animation-io/export-status exported)))
    (is (= :exported (animation-io/export-status (animation/read-json (animation/write-json exported)))))
    (is (= :export-stale (animation-io/export-status edited)))
    (is (= :exported (animation-io/export-status exported)))))

;; Template dispatch keeps configured IDs as escaped strings and uses Panthera's actual callback signature.
(deftest callback-template-test
  (let [document (animation/replace-key (event-document) "default" nil
                                       {:key_type "event" :event_id "quote\"\n事件" :start_time 0.5 :duration 0 :data "x"})
        template (animation/callback-template document "default" "main.anim")]
    (is (string/includes? template "callback_event = function(event_id, node, data, value)"))
    (is (string/includes? template "panthera.play(self.animation, \"default\""))
    (is (string/includes? template "quote\\\"\\n事件"))
    (is (string/includes? template "panthera.stop(self.animation)"))))
