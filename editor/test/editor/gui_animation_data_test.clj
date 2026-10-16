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

(ns editor.gui-animation-data-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [editor.gui-animation-data :as animation]))

(defn- tween [start duration from to]
  {:key_type "tween"
   :node_id "box"
   :property_id "position_x"
   :easing "linear"
   :start_time start
   :duration duration
   :start_value from
   :end_value to})

(defn- document-with-keys [keys]
  (assoc-in (animation/new-document "/main/main.gui")
            [:data :animations 0]
            {:animation_id "default" :duration 4.0 :animation_keys keys}))

(defn- sample-x [document time]
  (get-in (animation/evaluate document "default" time) ["box" "position_x"]))

;; Verifies Panthera's public JSON envelope and preserves unknown import fields across a save.
(deftest json-roundtrip-test
  (let [document (-> (document-with-keys [(assoc (tween 0 1 0 300) :future_key_field {"id" [1 2 3]})])
                     (assoc :future_document_field "preserve"
                            "vendor/extension" {"asset/path/field" "preserve too"})
                     (assoc-in [:data :metadata :future_metadata] {:enabled false}))
        imported (animation/read-json (json/write-str document))]
    (is (= (json/read-str (json/write-str document))
           (json/read-str (animation/write-json imported))))
    (is (= ["default"] (animation/animation-ids imported)))
    (is (= 4.0 (animation/animation-duration imported "default")))
    (is (= 150.0 (sample-x imported 0.5)))))

;; Verifies defaulted Panthera key fields and numeric key types match the runtime's preprocessing.
(deftest compact-panthera-key-test
  (let [document (document-with-keys [{:key_type 1 :node_id "box" :property_id "position_x" :end_value 300}])]
    (is (= [] (animation/preview-issues document "default")))
    (is (= 300.0 (sample-x document 0)))
    (is (= [{:time 0.0 :value 300 :easing "linear"}]
           (animation/keyframes document "default" "box" "position_x")))))

;; Verifies delayed starts, held values in gaps and overlapping keys use Panthera's last-started semantics.
(deftest sampling-boundaries-test
  (let [document (document-with-keys [(tween 0.5 1 20 100)
                                     (tween 2.0 1 100 200)
                                     (tween 2.5 1 500 600)])]
    (is (= 20.0 (sample-x document -2)))
    (is (= 20.0 (sample-x document 0)))
    (is (= 20.0 (sample-x document 0.5)))
    (is (= 60.0 (sample-x document 1.0)))
    (is (= 100.0 (sample-x document 1.75)))
    (is (= 125.0 (sample-x document 2.25)))
    (is (= 500.0 (sample-x document 2.5)))
    (is (= 550.0 (sample-x document 3.0)))
    (is (= 600.0 (sample-x document 100)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"gaps, overlapping"
                         (animation/keyframes document "default" "box" "position_x")))))

;; Verifies equal-start keys use Panthera's duration and end-value tie breakers regardless of file order.
(deftest simultaneous-keys-test
  (let [long-key (tween 0 2 0 200)
        short-key (tween 0 1 1000 2000)
        smaller-end (tween 0 2 0 100)]
    (is (= 50.0 (sample-x (document-with-keys [long-key short-key smaller-end]) 0.5)))
    (is (= 50.0 (sample-x (document-with-keys [short-key smaller-end long-key]) 0.5)))))

;; Verifies fractional-frame arithmetic does not create duplicate keys or falsely reject contiguous tracks.
(deftest fractional-key-times-test
  (let [document (document-with-keys [(tween 0.1 0.2 0 100)
                                     (tween 0.3 0.2 100 200)])
        edited (animation/set-keyframe document "default" "box" "position_x" 0.3 150 "linear")
        removed (animation/remove-keyframe edited "default" "box" "position_x" (+ 0.1 0.2))]
    (is (= 3 (count (animation/keyframes edited "default" "box" "position_x"))))
    (is (= 2 (count (animation/keyframes removed "default" "box" "position_x"))))))

;; Verifies keyframe insertion, replacement and deletion reconnect intervals without changing unrelated data.
(deftest editing-keyframes-test
  (let [document (-> (animation/new-document "/main/main.gui")
                     (animation/set-keyframe "default" "box" "position_x" 0 10 "linear")
                     (animation/set-keyframe "default" "box" "position_x" 2 50 "linear")
                     (animation/set-keyframe "default" "box" "position_x" 1 20 "inquad")
                     (animation/set-keyframe "default" "box" "color_a" 0 1 "linear")
                     (animation/set-keyframe "default" "box" "color_a" 2 0 "linear"))
        replaced (animation/set-keyframe document "default" "box" "position_x" 1.0 30 "linear")
        removed (animation/remove-keyframe replaced "default" "box" "position_x" 1)
        cleared (-> removed
                    (animation/remove-keyframe "default" "box" "position_x" 0)
                    (animation/remove-keyframe "default" "box" "position_x" 2))]
    (is (= [{:time 0.0 :value 10 :easing "linear"}
            {:time 1.0 :value 20 :easing "inquad"}
            {:time 2.0 :value 50 :easing "linear"}]
           (animation/keyframes document "default" "box" "position_x")))
    (is (< (Math/abs (- 12.5 (sample-x document 0.5))) 0.02))
    (is (< (Math/abs (- 20.0 (sample-x replaced 0.5))) 0.02))
    (is (= 30.0 (sample-x removed 1)))
    (is (= 2.0 (animation/animation-duration document "default")))
    (is (= {"box" {"position_x" 35.0 "color_a" 0.25}}
           (animation/evaluate document "default" 1.5)))
    (is (= [] (animation/keyframes cleared "default" "box" "position_x")))
    (is (= {"box" {"color_a" 0.5}} (animation/evaluate cleared "default" 1)))))

;; Verifies editing preserves imported key metadata and keeps read-only tracks unchanged.
(deftest preserve-track-metadata-test
  (let [unsupported {:key_type "event" :event_id "sound" :data "click" :start_time 1.0}
        original (document-with-keys [(assoc (tween 0 1 0 100) :custom_label "first")
                                      (assoc (tween 1 1 100 200) :custom_label "second")
                                      unsupported])
        edited (animation/set-keyframe original "default" "box" "position_x" 1 50 "linear")
        keys (get-in edited [:data :animations 0 :animation_keys])]
    (is (= ["first" "second"] (into [] (keep :custom_label) keys)))
    (is (contains? (set keys) unsupported))
    (is (= 50.0 (get-in (animation/evaluate edited "default" 1) ["box" "position_x"])))))

;; Verifies moving keys retains their imported metadata, including moves onto existing and initial keys.
(deftest move-keyframe-metadata-test
  (let [original (document-with-keys [(assoc (tween 0 1 0 100) :custom_label "moving" :extension {:enabled false})
                                      (assoc (tween 1 1 100 200) :custom_label "other")])
        moved (animation/move-keyframe original "default" "box" "position_x" 1 0.5)
        onto-end (animation/move-keyframe original "default" "box" "position_x" 1 2)
        onto-start (animation/move-keyframe original "default" "box" "position_x" 1 0)
        keys-at #(get-in % [:data :animations 0 :animation_keys])]
    (is (= ["moving" "other"] (mapv :custom_label (keys-at moved))))
    (is (= {:enabled false} (:extension (first (keys-at moved)))))
    (is (= 100.0 (sample-x moved 0.5)))
    (is (= ["moving"] (mapv :custom_label (keys-at onto-end))))
    (is (= 100.0 (sample-x onto-end 2)))
    (is (= ["moving" "other"] (mapv :custom_label (keys-at onto-start))))
    (is (= {:enabled false} (:extension (first (keys-at onto-start)))))
    (is (= 100.0 (sample-x onto-start 0)))
    (is (= [{:time 0.0 :value 100 :easing "linear"}
            {:time 2.0 :value 200 :easing "linear"}]
           (animation/keyframes onto-start "default" "box" "position_x")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no longer exists"
                         (animation/move-keyframe original "default" "box" "position_x" 3 0.5)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (animation/move-keyframe original "default" "box" "position_x" 1 Double/NaN)))))

;; Verifies unknown key payloads survive import, unrelated edits and export without numeric coercion.
(deftest preserve-unsupported-key-values-test
  (let [keys [{:key_type "trigger" :node_id "label" :property_id "text"
               :start_time 0.5 :start_data "before" :data "after"
               :start_value "extension-start" :end_value false}
              {:key_type "event" :start_time 0.75 :event_id "callback"
               :data {:payload "custom"} :start_value false :end_value "extension-end"}
              {:key_type "future-key" :start_time 0.25
               :start_value {"custom" true} :end_value ["custom" 1]}]
        document (animation/read-json (animation/write-json (document-with-keys keys)))
        edited (animation/set-keyframe document "default" "box" "position_x" 0 10 "linear")
        output (json/read-str (animation/write-json edited))]
    (is (= (set (json/read-str (json/write-str keys)))
           (into #{}
                 (remove #(= "tween" (get % "key_type")))
                 (get-in output ["data" "animations" 0 "animation_keys"]))))
    (is (thrown? clojure.lang.ExceptionInfo (animation/evaluate document "default" 1)))
    (is (.contains ^String (animation/write-lua edited) "extension-start")))
  (let [document (document-with-keys [(assoc (tween 0 1 0 100) :end_value "not-numeric")])]
    (is (= document (animation/read-json (animation/write-json document))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"finite numbers"
                         (animation/evaluate document "default" 0.5)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"finite numbers"
                         (animation/set-keyframe document "default" "box" "position_x" 1 20 "linear")))))

;; Verifies every exposed easing has correct endpoints and non-linear easing is applied to interpolation.
(deftest easing-test
  (doseq [easing animation/supported-easings]
    (let [document (document-with-keys [(assoc (tween 0 1 10 20) :easing easing)])]
      (is (= 10.0 (sample-x document 0)) easing)
      (is (= 20.0 (sample-x document 1)) easing)))
  (let [document (document-with-keys [(assoc (tween 0 1 0 100) :easing "outcubic")])]
    (is (< (Math/abs (- 87.5 (sample-x document 0.5))) 0.02))))

;; Verifies unknown keys and malformed curves remain lossless and fail preview explicitly.
(deftest unsupported-preview-test
  (doseq [key [(assoc (tween 0 1 0 100) :easing "future-easing")
               (assoc (tween 0 1 0 100) :easing_custom [0])
               (assoc (tween 0 1 0 100) :property_id "future-property")
               {:key_type "animation" :node_id "template" :property_id "appear" :duration 1}]]
    (let [document (document-with-keys [key])]
      (is (pos? (count (animation/preview-issues document "default"))))
      (is (thrown? clojure.lang.ExceptionInfo (animation/evaluate document "default" 0.5)))
      (is (= document (animation/read-json (animation/write-json document))))))
  (let [document (assoc-in (animation/new-document "/main/main.gui") [:data :animations 0 :initial_state] "idle")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reference is missing"
                         (animation/evaluate document "default" 0)))))

;; Verifies malformed files and invalid edits fail at the boundary before a save can corrupt data.
(deftest invalid-input-test
  (let [document (animation/new-document "/main/main.gui")
        keyed (animation/set-keyframe document "default" "box" "position_x" 2 10 "linear")]
    (is (thrown? clojure.lang.ExceptionInfo (animation/read-json "{}")))
    (is (thrown? clojure.lang.ExceptionInfo (animation/add-animation document "default" 1)))
    (is (thrown? clojure.lang.ExceptionInfo (animation/add-animation document "" 1)))
    (is (thrown? clojure.lang.ExceptionInfo (animation/set-duration keyed "default" 1)))
    (is (thrown? clojure.lang.ExceptionInfo (animation/set-duration document "default" Double/NaN)))
    (is (thrown? clojure.lang.ExceptionInfo (animation/set-keyframe document "default" "box" "position_x" -1 0 "linear")))
    (is (thrown? clojure.lang.ExceptionInfo (animation/set-keyframe document "default" "box" "position_x" 1 Double/POSITIVE_INFINITY "linear")))
    (is (thrown? clojure.lang.ExceptionInfo (animation/set-keyframe document "default" "box" "unknown" 1 0 "linear")))
    (is (thrown? clojure.lang.ExceptionInfo (animation/evaluate document "missing" 1)))
    (is (= ["default" "appear"] (animation/animation-ids (animation/add-animation document "appear" 2))))))

;; Verifies exported Lua is a data module with escaped strings rather than executable user content.
(deftest lua-export-test
  (let [document (assoc (animation/new-document "/main/main.gui")
                        :label "quote\" backslash\\ newline\n中文")
        lua (animation/write-lua document)]
    (is (.startsWith ^String lua "return {"))
    (is (.contains ^String lua "[\"type\"] = \"animation_editor\""))
    (is (.contains ^String lua "[\"format\"] = \"lua\""))
    (is (.contains ^String lua "quote\\\" backslash\\\\ newline\\n中文"))
    (testing "JSON storage is unaffected by a Lua export"
      (is (= "json" (:format document))))))
