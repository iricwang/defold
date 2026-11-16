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

(ns editor.skeletal-animation-runtime-gap-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-geometry :as geometry]
            [editor.skeletal-animation-io :as animation-io]
            [editor.skeletal-animation-mixing :as mixing]
            [editor.skeletal-animation-native :as native]
            [editor.skeletal-animation-slots :as slots]
            [util.coll :as coll]))

(defn- oracle []
  (:cases (json/read-str (slurp (io/resource "skeletal-animation/runtime-gaps-oracle.json")) :key-fn keyword)))

(defn- normalize-configuration [configuration]
  (cond-> (update configuration :tracks #(mapv (fn [track] (cond-> track (:blend track) (update :blend keyword))) %))
    (:step-mode configuration) (update :step-mode keyword)))

(defn- check-bones [name expected result]
  (doseq [{:keys [id transform]} (:bones expected)]
    (is (coll/every? true? (mapv #(< (abs (- %1 %2)) 0.0001) transform (get-in result [:pose :transforms id])))
        (str name " / " id " @ " (:time expected)))))

;; Independent 4.2.120 frames cover reverse/ranges/events, interrupted and held
;; transitions, empty fades, animated IK inheritance, variable dt and resets.
(deftest official-runtime-gaps-test
  (doseq [{:keys [name source configuration commands frames]} (oracle)]
    (let [document (cond-> (data/import-spine-json (json/write-str source)) (:skin configuration) (data/select-skin (:skin configuration)))
          final (reduce (fn [state [index expected]]
                          (let [state (reduce (fn [state command]
                                                ((if (= "queue" (:operation command)) mixing/queue-track mixing/set-track)
                                                 document state (:index command) (:specification command)))
                                              state (filterv #(= index (:frame %)) commands))
                                result (mixing/advance-state document state (:delta expected))]
                            (check-bones name expected result)
                            (is (= (:events expected) (mapv :name (:events result))) (str name " events @ " index))
                            (:state result)))
                        (mixing/create-state document (normalize-configuration configuration)) (mapv vector (range) frames))]
      (when (= "runtime" (:step-mode configuration))
        (let [frame (nth frames (quot (count frames) 2))
              replayed (mixing/seek-state document final (:time frame))]
          (check-bones (str name " seek") frame replayed)
          (is (= [] (:events replayed))))))))

;; Sampling must sum the exported raw weights; normalization belongs to editing
;; commands, not rendering. The official identity-bone oracle is (x+deform)*w.
(deftest raw-weight-sampling-test
  (doseq [weight [0.0 0.5 1.0 1.5] deform [[] [2.0 4.0]]]
    (is (= [(* weight (+ 10.0 (get deform 0 0.0))) (* weight (get deform 1 0.0))]
           (geometry/mesh-world-vertices
             {:vertices [10.0 0.0] :weights [[{:bone "root" :x 10.0 :y 0.0 :weight weight}]]}
             nil {"root" [1.0 0.0 0.0 1.0 0.0 0.0]} deform))))
  (is (= 1.0 (:weight (first (geometry/normalize-influences [{:bone "root" :x 10.0 :y 0.0 :weight 0.5}]))))))

;; Decode an actual official 4.2.43 file, encode it independently, and preserve
;; all five discrete inheritance modes through both native and JSON editing.
(deftest native-inheritance-roundtrip-test
  (let [bytes (with-open [input (io/input-stream (io/resource "skeletal-animation/inherit-official-4.2.43.spine"))] (.readAllBytes input))
        graph (native/decode-graph (native/decode bytes))
        source (native/to-spine-json graph 0)
        frames (get-in source ["animations" "probe" "bones" "root" "inherit"])
        edited (assoc-in source ["animations" "probe" "bones" "root" "inherit" 1 "time"] 0.5)
        updated (native/update-skeleton graph 0 edited)
        roundtrip (native/to-spine-json (native/decode-graph (native/decode (native/encode-graph updated))) 0)]
    (is (= ["normal" "onlyTranslation" "noRotationOrReflection" "noScale" "noScaleOrReflection"] (mapv #(get % "inherit") frames)))
    (is (= (get-in edited ["animations" "probe"]) (get-in roundtrip ["animations" "probe"])))
    (is (= [] (animation-io/export-compatibility (data/import-spine-json (json/write-str source)))))))

;; Unknown parameters must fail immediately instead of looking configured but
;; doing nothing. Invalid ranges cannot enter playback or modify its state.
(deftest playback-options-validation-test
  (let [document (data/new-document "validation")]
    (doseq [options [{:unknown true} {:reverse 1} {:animation-start -1.0} {:animation-end 2000.0} {:animation-start 1.0 :animation-end 0.0}]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (mixing/create-state document {:tracks [(merge {:animation "default"} options)]}))))))

;; Export compatibility must expose the default material's missing dark channel
;; before publication, while keeping data intact for an appropriate runtime.
(deftest default-material-preflight-test
  (let [source {"skeleton" {"spine" "4.2.43"} "bones" [{"name" "root"}]
                "slots" [{"name" "slot" "bone" "root" "dark" "123456"}]}
        document (data/import-spine-json (json/write-str source))]
    (is (= [{:target :defold :severity :warning :code :default-material-dark}]
           (animation-io/export-compatibility document)))
    (is (= "123456" (get-in (json/read-str (data/export-spine-json document)) ["slots" 0 "dark"])))))

;; A key authored at an arbitrary paused time must be visible immediately after
;; Float32 export, and repeated edits must replace it rather than add a neighbor.
(deftest inheritance-playhead-rounding-test
  (doseq [time [0.3 0.5133333333333333 0.8 0.55]]
    (let [document (-> (data/new-document "rounding")
                       (slots/set-inherit-keyframe "default" "root" time "noScale")
                       (slots/set-inherit-keyframe "default" "root" time "onlyTranslation"))
          source (json/read-str (data/export-spine-json document))
          keys (get-in source ["animations" "default" "bones" "root" "inherit"])]
      (is (= "onlyTranslation" (:inherit (first (data/sample-pose document "default" time)))))
      (is (= 1 (count keys)))
      (is (<= (double (float (get (first keys) "time"))) time)))))
