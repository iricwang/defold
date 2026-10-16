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

(ns editor.gui-animation-features-test
  (:require [clojure.test :refer [deftest is]]
            [editor.gui-animation-data :as animation]))

(defn- clip [id duration keys]
  {:animation_id id :duration duration :animation_keys keys})

(defn- project [clips]
  (assoc-in (animation/new-document "/main.gui") [:data :animations] clips))

(defn- tween [node property start duration from to]
  {:key_type "tween" :node_id node :property_id property :start_time start :duration duration
   :start_value from :end_value to :easing "linear"})

(defn- nested [node id start duration]
  {:key_type "animation" :node_id node :property_id id :start_time start :duration duration :easing "linear"})

;; Every exposed GUI channel and easing must serialize, sample finite values and reach both endpoints.
(deftest gui-channel-and-easing-coverage-test
  (is (= 41 (count animation/supported-easings)))
  (doseq [property animation/supported-properties]
    (let [document (project [(clip "run" 1.0 [(tween "box" property 0 1 10 20)])])]
      (is (= 15.0 (get-in (animation/evaluate document "run" 0.5) ["box" property])) property)))
  (doseq [easing animation/supported-easings]
    (is (= 0.0 (animation/ease easing 0.0)) easing)
    (is (= 1.0 (animation/ease easing 1.0)) easing)
    (doseq [sample (range 101)]
      (is (Double/isFinite (animation/ease easing (/ sample 100.0))) easing)))
  (is (> (animation/ease "outback" 0.5) 1.0))
  (is (< (animation/ease "inback" 0.5) 0.0)))

;; Custom easing preserves non-unit endpoints, interpolates samples, and survives key movement and export.
(deftest custom-curve-test
  (let [key (assoc (tween "box" "position_x" 0 1 10 20) :easing_custom [0.2 1.4 0.8])
        document (project [(clip "run" 2 [key])])
        sample #(get-in (animation/evaluate document "run" %) ["box" "position_x"])
        edited (animation/set-keyframe document "run" "box" "position_x" 1 20 [0.2 1.4 0.8])
        moved (animation/move-keyframe edited "run" "box" "position_x" 1 1.5)]
    (is (= 12.0 (sample 0)))
    (is (= 24.0 (sample 0.5)))
    (is (= 18.0 (sample 1)))
    (is (= 20.0 (sample 1.1)))
    (is (= [0.2 1.4 0.8] (:easing (last (animation/keyframes moved "run" "box" "position_x")))))
    (is (= moved (animation/read-json (animation/write-json moved))))))

;; Event editing never blocks numeric preview or executes callbacks, and timed events sample their values.
(deftest event-editing-test
  (let [base (project [(clip "run" 1 [(tween "box" "position_x" 0 1 0 100)])])
        key {:key_type "event" :event_id "sound" :node_id "box" :property_id "event"
             :start_time 0.25 :duration 0.5 :start_value 10 :end_value 20 :data {:name "click"}}
        added (animation/replace-key base "run" nil key)
        changed (animation/replace-key added "run" 1 (assoc key :event_id "changed"))
        removed (animation/remove-key changed "run" 1)]
    (is (= 50.0 (get-in (animation/evaluate added "run" 0.5) ["box" "position_x"])))
    (is (= [] (animation/event-samples added "run" 0.1)))
    (is (= [15.0] (mapv :value (animation/event-samples added "run" 0.5))))
    (is (= "changed" (:event_id (second (animation/animation-keys changed "run")))))
    (is (= base removed))))

;; Trigger values retain strings, booleans and initial data with Panthera's cut-at-start behavior.
(deftest trigger-sampling-test
  (doseq [property animation/trigger-properties]
    (let [key {:key_type "trigger" :node_id "node" :property_id property :start_time 0.5 :duration 0
               :start_data "before" :data "after"}
          document (project [(clip "run" 1 [key])])]
      (is (= "before" (get-in (animation/evaluate document "run" 0) ["node" property])))
      (is (= "after" (get-in (animation/evaluate document "run" 0.5) ["node" property]))))))

;; Renaming clips updates local references while preserving external template names and prevents destructive deletion.
(deftest clip-management-test
  (let [base (project [(clip "idle" 1 [(tween "box" "position_x" 0 1 0 100)])
                       (assoc (clip "run" 2 [(nested "" "idle" 0 2)]) :initial_state "idle")])
        renamed (animation/rename-animation base "idle" "rest")
        copied (animation/duplicate-animation renamed "run" "run_copy")]
    (is (= "rest" (get-in renamed [:data :animations 1 :initial_state])))
    (is (= "rest" (:property_id (first (animation/animation-keys renamed "run")))))
    (is (= (animation/animation-keys copied "run") (animation/animation-keys copied "run_copy")))
    (is (thrown? clojure.lang.ExceptionInfo (animation/remove-animation copied "rest")))
    (is (= ["rest" "run"] (animation/animation-ids (animation/remove-animation copied "run_copy"))))
    (is (thrown? clojure.lang.ExceptionInfo (animation/remove-animation (project [(clip "run" 1 [])]) "run")))))

;; Initial-state pose is inherited without overwriting a newer key; local and cross-template cycles fail explicitly.
(deftest initial-and-nested-clips-test
  (let [base (project [(clip "idle" 1 [(tween "box" "position_x" 0 1 0 100)])
                       (clip "child" 1 [(tween "box" "position_y" 0 1 0 200)])
                       (clip "run" 4 [(nested "" "child" 1 2)])])
        document (animation/set-initial-state base "run" "idle")]
    (is (= 100.0 (get-in (animation/evaluate document "run" 0) ["box" "position_x"])))
    (is (= 0.0 (get-in (animation/evaluate document "run" 1) ["box" "position_y"])))
    (is (= 100.0 (get-in (animation/evaluate document "run" 2) ["box" "position_y"])))
    (is (= 200.0 (get-in (animation/evaluate document "run" 4) ["box" "position_y"])))
    (is (thrown? clojure.lang.ExceptionInfo (animation/set-initial-state document "idle" "run")))
    (is (thrown? clojure.lang.ExceptionInfo (animation/replace-key base "child" nil (nested "" "run" 0 1))))))

;; Later root-time keys beat nested values, and an unstarted child property cannot steal a previous clip's pose.
(deftest nested-precedence-test
  (let [document (project [(clip "child" 1 [(tween "box" "position_x" 0 1 0 100)])
                           (clip "later" 1 [(tween "box" "position_x" 0.5 0.5 800 900)])
                           (clip "run" 3 [(nested "" "child" 0 2)
                                           (tween "box" "position_x" 1 0 0 400)
                                           (nested "" "later" 1.5 1)])])]
    (is (= 25.0 (get-in (animation/evaluate document "run" 0.5) ["box" "position_x"])))
    (is (= 400.0 (get-in (animation/evaluate document "run" 1.5) ["box" "position_x"])))
    (is (= 400.0 (get-in (animation/evaluate document "run" 1.75) ["box" "position_x"])))
    (is (= 800.0 (get-in (animation/evaluate document "run" 2) ["box" "position_x"])))))

;; Embedded and resource-backed templates prefix node IDs and serialize references without flattening the saved file.
(deftest template-clips-test
  (let [child (project [(clip "appear" 1 [(tween "label" "color_a" 0 1 0 1)])])
        saved (animation/bind-template (project [(clip "run" 2 [(nested "panel" "appear" 0 2)])]) "panel" "/panel_panthera.json")
        loaded (animation/resolve-templates saved (fn [path] (is (= "/panel_panthera.json" path)) child))]
    (is (= 0.5 (get-in (animation/evaluate loaded "run" 1) ["panel/label" "color_a"])))
    (is (= "/panel_panthera.json" (animation/template-document saved "panel")))
    (is (thrown? clojure.lang.ExceptionInfo (animation/resolve-templates saved (constantly saved))))
    (is (pos? (count (animation/preview-issues saved "run"))))))

;; Bulk copy/move/delete keeps relative timing and imported key metadata, and rejects negative destinations atomically.
(deftest bulk-keyframes-test
  (let [document (project [(clip "run" 3 [(assoc (tween "box" "position_x" 0 1 0 100) :label "retain")
                                         (tween "box" "position_x" 1 1 100 200)
                                         (tween "box" "color_a" 0 1 1 0)])])
        selection #{["box" "position_x" 1.0] ["box" "color_a" 1.0]}
        points (animation/selected-keyframes document "run" selection)
        moved (animation/move-keyframes document "run" selection 0.5)
        pasted (animation/paste-keyframes document "run" points 1.0)
        removed (animation/delete-keyframes document "run" selection)]
    (is (= 2 (count points)))
    (is (= 100.0 (get-in (animation/evaluate moved "run" 1.5) ["box" "position_x"])))
    (is (= 0.0 (get-in (animation/evaluate pasted "run" 2) ["box" "color_a"])))
    (is (= [0 2.0] (mapv :time (animation/keyframes removed "run" "box" "position_x"))))
    (is (contains? (set (mapv :label (animation/animation-keys moved "run"))) "retain"))
    (is (thrown? clojure.lang.ExceptionInfo (animation/move-keyframes document "run" selection -2)))))

;; Editing raw overlapping segments preserves their gaps, metadata and independent durations.
(deftest raw-segment-edit-test
  (let [document (project [(clip "run" 3 [(tween "box" "position_x" 0 1 0 100)
                                         (assoc (tween "box" "position_x" 0.5 2 500 600) :extension {:value 7})])])
        key (second (animation/animation-keys document "run"))
        edited (animation/replace-key document "run" 1 (assoc key :duration 3))]
    (is (= 3.5 (animation/animation-duration edited "run")))
    (is (= {:value 7} (:extension (second (animation/animation-keys edited "run")))))
    (is (< 500.0 (get-in (animation/evaluate edited "run" 1) ["box" "position_x"]) 600.0))
    (is (thrown? clojure.lang.ExceptionInfo (animation/replace-key document "run" 1 (assoc key :duration -1))))))

;; Generated Lua imports arrays, Unicode, negative literals and template requires without executing source code.
(deftest lua-import-test
  (let [document (project [(clip "run" 1 [(tween "box" "position_x" 0 1 -10 20)])])
        imported (animation/read-source (animation/write-lua document) (fn [_] (throw (ex-info "Unexpected template" {}))))]
    (is (= 5.0 (get-in (animation/evaluate imported "run" 0.5) ["box" "position_x"])))
    (is (= [] (animation/animation-keys (animation/read-source (animation/write-lua (project [(clip "empty" 1 [])])) identity) "empty"))))
  (doseq [text ["os.execute('touch /tmp/should-not-exist'); return {}" "while true do end" "return (function() return {} end)()"]]
    (is (thrown? Exception (animation/read-source text identity)) text))
  (let [text "return {type='animation_editor',data={animations={{animation_id='run',duration=1,animation_keys={}}},metadata={template_animation_paths={panel=require('main.panel_panthera')}}}}"
        calls (atom [])
        imported (animation/read-source text #(do (swap! calls conj %) (project [(clip "child" 1 [])])))]
    (is (= ["/main/panel_panthera.lua"] @calls))
    (is (= ["child"] (animation/animation-ids (animation/template-document imported "panel"))))))

;; Mixed selection operations copy and delete events with scalar points in one reversible document edit.
(deftest mixed-selection-test
  (let [event {:key_type "event" :node_id "" :property_id "event" :event_id "sound" :start_time 0.5 :duration 0.0}
        original (project [(clip "run" 2.0 [(tween "box" "position_x" 0 1 0 100) event])])
        points #{["box" "position_x" 1.0]}
        copied (animation/copy-selection original "run" points #{1})
        pasted (animation/paste-selection original "run" copied 1.0)
        moved (animation/move-selection original "run" points #{1} 0.5)
        deleted (animation/delete-selection original "run" points #{1})]
    (is (= 1 (count (:points copied))))
    (is (= [event] (:segments copied)))
    (is (= 1.5 (:start_time (last (animation/animation-keys pasted "run")))))
    (is (= 1.0 (:start_time (last (animation/animation-keys moved "run")))))
    (is (= 100.0 (get-in (animation/evaluate moved "run" 1.5) ["box" "position_x"])))
    (is (= 1 (count (animation/animation-keys deleted "run"))))
    (is (thrown? clojure.lang.ExceptionInfo (animation/move-selection original "run" points #{1} -1.0)))))

;; A missing template used only by another clip must not disable an unrelated animation's preview.
(deftest active-template-resolution-test
  (let [source (animation/bind-template (project [(clip "idle" 1 []) (clip "external" 1 [(nested "panel" "missing" 0 1)])]) "panel" "/missing.json")
        document (animation/resolve-preview source "idle" (fn [_] (throw (ex-info "Unreferenced template was loaded" {}))))]
    (is (= {} (animation/evaluate document "idle" 0)))
    (is (= source document))))

;; Numeric-looking Lua strings and nil fields retain Lua table semantics rather than coercing identifiers or values.
(deftest lua-literal-types-test
  (let [text "return {type='animation_editor',version=1,data={animations={{animation_id='001',duration=1,animation_keys={{key_type='trigger',node_id='box',property_id='text',data='123',start_time=0.5,start_data=nil,easing_custom=nil}}}}}}"
        document (animation/read-source text identity)]
    (is (= ["001"] (animation/animation-ids document)))
    (is (= {} (animation/evaluate document "001" 0.0)))
    (is (= "123" (get-in (animation/evaluate document "001" 0.5) ["box" "text"])))
    (is (not (contains? (first (animation/animation-keys document "001")) :easing_custom)))))

;; A mixed-type imported property group must never lose an event when a scalar keyframe is edited.
(deftest mixed-property-track-preservation-test
  (let [event {:key_type "event" :node_id "box" :property_id "position_x" :event_id "signal" :start_time 0.5}
        source (project [(clip "run" 1 [(tween "box" "position_x" 0 1 0 100) event])])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-tween" (animation/set-keyframe source "run" "box" "position_x" 1 200 "linear")))
    (is (= event (second (animation/animation-keys source "run"))))))
