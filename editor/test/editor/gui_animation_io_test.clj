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

(ns editor.gui-animation-io-test
  (:require [clojure.test :refer [deftest is]]
            [editor.gui-animation-data :as animation]
            [editor.gui-animation-io :as animation-io]
            [util.coll :as coll])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; Dialog exports must produce readable UTF-8 JSON/Lua files, preserve quoted numeric strings, and avoid truncating files on validation errors.
(deftest file-roundtrip-test
  (let [directory (Files/createTempDirectory "gui-animation-io-" (make-array FileAttribute 0))
        json (.toFile (.resolve directory "animation.json"))
        lua (.toFile (.resolve directory "animation.lua"))
        document (-> (animation/new-document "/界面/main.gui")
                     (animation/set-keyframe "default" "box" "position_x" 0 10 "linear")
                     (animation/set-keyframe "default" "box" "position_x" 1 100 "linear")
                     (animation/replace-key "default" nil {:key_type "trigger" :node_id "label" :property_id "text"
                                                          :start_time 0.5 :duration 0 :start_data "001" :data "中文 123 😀"}))]
    (try
      (doseq [[format file] [[:json json] [:lua lua]]]
        (animation-io/write-document-file! file format document)
        (let [loaded (animation-io/read-document-file file identity)]
          (is (= "/界面/main.gui" (get-in loaded [:data :metadata :gui_path])))
          (is (= "001" (get-in (animation/evaluate loaded "default" 0) ["label" "text"])))
          (is (= "中文 123 😀" (get-in (animation/evaluate loaded "default" 0.5) ["label" "text"])))
          (is (= 55.0 (get-in (animation/evaluate loaded "default" 0.5) ["box" "position_x"])))))
      (let [before (slurp json :encoding "UTF-8")]
        (is (thrown? clojure.lang.ExceptionInfo (animation-io/write-document-file! json :json {})))
        (is (= before (slurp json :encoding "UTF-8"))))
      (finally
        (Files/deleteIfExists (.toPath json))
        (Files/deleteIfExists (.toPath lua))
        (Files/deleteIfExists directory)))))

;; Both export formats must carry the engine's exact curves so Panthera seek/play agree, without rewriting authoring data or custom samples.
(deftest runtime-easing-export-test
  (let [directory (Files/createTempDirectory "gui-animation-runtime-" (make-array FileAttribute 0))
        json (.toFile (.resolve directory "animation.json"))
        lua (.toFile (.resolve directory "animation.lua"))
        document (assoc-in (animation/new-document "/main.gui") [:data :animations]
                           (mapv (fn [easing]
                                   {:animation_id easing
                                    :duration 1.0
                                    :animation_keys [{:key_type "tween" :node_id "box" :property_id "position_x"
                                                      :start_time 0.0 :duration 1.0 :start_value 100.0 :end_value 600.0
                                                      :easing easing}]})
                                 animation/supported-easings))]
    (try
      (doseq [[format file] [[:json json] [:lua lua]]]
        (animation-io/write-document-file! file format document)
        (let [exported (animation-io/read-document-file file identity)]
          (doseq [easing animation/supported-easings
                  time [0.0 0.17 0.4 0.5 0.73 1.0]]
            (is (= (animation/evaluate document easing time) (animation/evaluate exported easing time))
                (str format " " easing " at " time)))
          (is (= exported (animation/runtime-document exported)))))
      (is (= document (animation/read-json (animation/write-json document))))
      (is (coll/every? #(nil? (:easing_custom (first (:animation_keys %)))) (get-in document [:data :animations])))
      (let [custom (assoc-in document [:data :animations 0 :animation_keys 0 :easing_custom] [0.2 0.9 1.3])]
        (is (= [0.2 0.9 1.3] (get-in (animation/runtime-document custom) [:data :animations 0 :animation_keys 0 :easing_custom]))))
      (finally
        (Files/deleteIfExists (.toPath json))
        (Files/deleteIfExists (.toPath lua))
        (Files/deleteIfExists directory)))))
