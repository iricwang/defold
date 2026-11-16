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

(ns editor.skeletal-animation-mixing-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-attachments :as attachments]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-events :as events]
            [editor.skeletal-animation-mixing :as mixing]
            [editor.skeletal-animation-slots :as slots]
            [util.coll :as coll]))

(defn- close? [expected actual]
  (and (= (count expected) (count actual))
       (coll/every? true? (mapv #(< (abs (- (double %1) (double %2))) 1.0e-5) expected actual))))

(defn- golden []
  (json/read-str (slurp (io/resource "skeletal-animation/mixing-oracle.json")) :key-fn keyword))

;; Independent Spine 4.2 AnimationState golden samples cover sparse RGB/alpha,
;; alpha-zero attachment/order changes, hidden slots and both mesh deform types.
(deftest slot-mixing-oracle-test
  (doseq [{:keys [name source expected]} (:slot_cases (golden))]
    (let [document (data/import-spine-json (json/write-str source))]
      (doseq [{:keys [alpha time slots]} expected]
        (let [sample (mixing/sample document {:base {:animation "base" :time time}
                                              :overlay {:animation "overlay" :time time :alpha alpha}})
              message (str name " at " time " alpha " alpha)]
          (is (= (mapv :id slots) (mapv :id (:slots sample))) message)
          (doseq [[expected actual] (mapv vector slots (:slots sample))]
            (is (= (:attachment expected) (second (:preview-attachment-key actual))) message)
            (is (close? (:color expected) (:color actual)) message)
            (is (close? (:deform expected)
                        (or (get-in sample [:deforms (:id actual)])
                            (when (= "mesh" (get-in actual [:attachment :type]))
                              (vec (repeat (data/deform-size (:attachment actual)) 0.0))) [])) message)))))))

;; Independent world matrices verify initial shortest-route rotation, immediate
;; scale sign changes, sparse ownership and constraint-parameter blending.
(deftest bone-and-constraint-mixing-oracle-test
  (doseq [{:keys [name source samples]} (:bone_cases (golden))]
    (let [document (data/import-spine-json (json/write-str source))]
      (doseq [{:keys [alpha time bones]} samples]
        (let [sample (mixing/sample document {:base {:animation "base" :time time}
                                              :overlay {:animation "overlay" :time time :alpha alpha}})]
          (doseq [{:keys [id transform]} bones]
            (is (close? transform (get-in sample [:pose :transforms id]))
                (str name "/" id " at " time " alpha " alpha))))))))

(defn- transition-document []
  (-> (data/new-document "transition")
      (data/add-animation {:id "next" :duration 1.0})
      (data/set-keyframe "default" "root" "x" 0.0 100.0 "linear")
      (data/set-keyframe "default" "root" "y" 0.0 80.0 "linear")
      (data/set-keyframe "next" "root" "x" 0.0 200.0 "linear")))

;; Mix duration controls a deterministic crossfade; channels omitted by the
;; incoming base return to setup, and zero duration cuts immediately.
(deftest transition-duration-test
  (let [document (transition-document)]
    (doseq [[elapsed expected] [[0.0 [100.0 80.0]] [0.25 [125.0 60.0]]
                                [0.5 [150.0 40.0]] [1.0 [200.0 0.0]] [2.0 [200.0 0.0]]]]
      (let [sample (mixing/sample document {:base {:animation "next" :time 0.5}
                                            :transition {:from "default" :time 0.5 :elapsed elapsed :duration 1.0}})]
        (is (close? expected (subvec (get-in sample [:pose :transforms "root"]) 4 6)))))
    (is (= 200.0 (get-in (mixing/sample document {:base {:animation "next"}
                                                  :transition {:from "default" :duration 0.0}})
                         [:pose :transforms "root" 4])))))

;; Single-clip mode uses the existing pose solver, while mixed Physics produces
;; an explicit warning instead of claiming to reproduce stateful simulation.
(deftest single-clip-and-physics-test
  (let [document (-> (transition-document)
                     (data/add-physics {:id "spring" :bone "root" :x 1.0}))
        single (mixing/sample document {:base {:animation "default" :time 0.5}})
        mixed (mixing/sample document {:base {:animation "default" :time 0.5}
                                       :overlay {:animation "next" :time 0.5 :alpha 0.5}})]
    (is (= (:pose single) (data/evaluate-pose-with-slots document "default" 0.5 slots/sample-slots)))
    (is (= [] (:warnings single)))
    (is (= 1 (count (:warnings mixed))))))

;; Invalid identifiers, times, alpha and duration fail at the preview boundary.
(deftest preview-input-validation-test
  (let [document (transition-document)]
    (doseq [options [{:base {:animation "missing"}}
                     {:base {:animation "default" :time -1.0}}
                     {:base {:animation "default" :loop 1}}
                     {:base {:animation "default"} :overlay {:animation "next" :alpha Double/NaN}}
                     {:base {:animation "default"} :overlay {:animation "next" :alpha -0.5}}
                     {:base {:animation "default"} :transition {:from "next" :duration -1.0}}]]
      (is (thrown? clojure.lang.ExceptionInfo (mixing/sample document options))))))

;; Stateful tracks preserve unkeyed channels and additive offsets over any
;; number of ordered tracks instead of limiting composition to two clips.
(deftest stateful-additive-tracks-test
  (let [document (-> (data/new-document "multi")
                     (data/add-animation {:id "offset" :duration 1.0})
                     (data/add-animation {:id "upper" :duration 1.0})
                     (data/set-keyframe "default" "root" "x" 0.0 100.0 "linear")
                     (data/set-keyframe "offset" "root" "x" 0.0 20.0 "linear")
                     (data/set-keyframe "upper" "root" "y" 0.0 30.0 "linear"))
        state (mixing/create-state document {:tracks [{:animation "default"}
                                                      {:animation "offset" :blend :add :alpha 0.5}
                                                      {:animation "upper" :alpha 0.5}]})
        result (mixing/advance-state document state 0.5)]
    (is (close? [110.0 15.0] (subvec (get-in result [:pose :transforms "root"]) 4 6)))
    (is (= 3 (count (get-in result [:state :tracks]))))
    (is (= [] (:warnings result)))))

;; Queued clips consume leftover time, crossfade keyed values, and manual track
;; edits are replayed by seeking without redispatching historical events.
(deftest stateful-queue-and-seek-test
  (let [document (transition-document)
        state (mixing/create-state document {:default-mix 0.25
                                             :tracks [{:animation "default" :queue [{:animation "next"}]}]})
        halfway (mixing/advance-state document state 0.875)
        finished (mixing/advance-state document (:state halfway) 0.375)
        changed (mixing/set-track document (:state finished) 1 {:animation "default" :blend :add :alpha 0.25})
        continued (mixing/advance-state document changed 0.25)
        replayed (mixing/seek-state document (:state continued) 1.5)]
    (is (close? [150.0 40.0] (subvec (get-in halfway [:pose :transforms "root"]) 4 6)))
    (is (= "next" (get-in finished [:state :tracks 0 :animation])))
    (is (close? (get-in continued [:pose :transforms "root"]) (get-in replayed [:pose :transforms "root"])))
    (is (= [] (:events replayed)))
    (is (= nil (get-in (mixing/clear-track (:state continued) 1) [:tracks 1])))))

;; Canonical Physics replay is independent of display-frame partitions and
;; remains active while several tracks and animated constraints are mixed.
(deftest stateful-mixed-physics-test
  (let [document (-> (transition-document)
                     (data/set-keyframe "default" "root" "x" 1.0 200.0 "linear")
                     (data/add-physics {:id "spring" :bone "root" :x 1.0 :fps 60.0 :mix 1.0}))
        state (mixing/create-state document {:tracks [{:animation "default"}
                                                      {:animation "next" :alpha 0.25}]})
        direct (mixing/advance-state document state 0.7)
        partitioned (reduce (fn [result delta] (mixing/advance-state document (:state result) delta))
                            {:state state} [0.13 0.07 0.11 0.09 0.3])
        replayed (mixing/seek-state document (:state direct) 0.7)]
    (is (= [] (:warnings direct)))
    (is (pos? (count (get-in direct [:state :core :physics]))))
    (is (close? (get-in direct [:pose :transforms "root"]) (get-in partitioned [:pose :transforms "root"])))
    (is (close? (get-in direct [:pose :transforms "root"]) (get-in replayed [:pose :transforms "root"])))
    (is (not= 177.5 (get-in direct [:pose :transforms "root" 4])))))

;; Official Spine 4.2.120 frames validate additive layers, queued transitions,
;; accumulated 720-degree rotations/loops and live Physics on the same clock.
(deftest stateful-runtime-oracle-test
  (doseq [{:keys [name source configuration frames]}
          (json/read-str (slurp (io/resource "skeletal-animation/stateful-mixing-oracle.json")) :key-fn keyword)]
    (let [document (data/import-spine-json (json/write-str source))
          configuration (update configuration :tracks #(mapv (fn [track] (cond-> track (:blend track) (update :blend keyword))) %))]
      (reduce (fn [{:keys [state time]} expected]
                (let [result (mixing/advance-state document state (max 0.0 (- (:time expected) time)))
                      actual (coll/into-> (get-in result [:pose :bones]) {} (map (juxt :id identity)))]
                  (doseq [{:keys [id rotation transform]} (:bones expected)]
                    (is (close? transform (get-in result [:pose :transforms id])) (str name " @ " (:time expected)))
                    (is (< (abs (- rotation (get-in actual [id :rotation]))) 1e-5) (str name " rotation @ " (:time expected))))
                  {:state (:state result) :time (:time expected)}))
              {:state (mixing/create-state document configuration) :time 0.0} frames))))

;; Incoming queue reset keys clear existing Physics state at the transition
;; boundary, including global reset tracks whose owner is the empty ID.
(deftest queued-physics-reset-test
  (let [document (-> (transition-document)
                     (data/set-keyframe "default" "root" "x" 1.0 200.0 "linear")
                     (data/add-physics {:id "spring" :bone "root" :x 1.0 :fps 60.0})
                     (data/set-physics-reset-keyframe "next" "" 0.0))
        state (mixing/create-state document {:default-mix 0.25
                                             :tracks [{:animation "default" :queue [{:animation "next"}]}]})
        before (mixing/advance-state document state 0.7)
        reset (mixing/advance-state document (:state before) 0.05)]
    (is (not (zero? (get-in before [:state :core :physics "spring" :x-offset]))))
    (is (zero? (get-in reset [:state :core :physics "spring" :x-offset])))))

;; Sequence timelines apply independently of attachment keys, and dark/light
;; tint channels blend together while retaining numbered image selection.
(deftest sequence-and-two-color-mixing-test
  (let [document (-> (data/new-document "sequence")
                     (data/add-animation {:id "overlay" :duration 1.0})
                     (data/add-slot {:id "body" :bone "root"
                                     :attachment {:name "body" :path "/body.png" :width 16.0 :height 16.0}}))
        document (-> (json/read-str (data/export-spine-json document))
                     (assoc-in ["slots" 0 "dark"] "000000")
                     json/write-str data/import-spine-json
                     (attachments/put "default" "body" "body" {"sequence" {"count" 3 "start" 1 "digits" 3 "setup" 0}})
                     (slots/set-keyframe "default" "body" "rgba2" {:time 0.0 :color "ff0000ff" :dark-color "204060"})
                     (slots/set-keyframe "overlay" "body" "rgba2" {:time 0.0 :color "0000ffff" :dark-color "6080a0"})
                     (attachments/set-sequence-keyframe "overlay" "default" "body" "body" {:time 0.0 :index 0 :mode "loop" :delay 0.25}))
        state (mixing/create-state document {:tracks [{:animation "default"} {:animation "overlay" :alpha 0.5}]})
        result (mixing/advance-state document state 0.5)
        slot (first (:slots result))]
    (is (= "/body003.png" (get-in slot [:attachment :path])))
    (is (close? [0.5 0.0 0.5 1.0] (:color slot)))
    (is (close? [(/ 64.0 255.0) (/ 96.0 255.0) (/ 128.0 255.0)] (:dark-color slot)))))

;; Initial and queued events fire once across partial display frames. Manual
;; replacement must still fire its zero key at the current playback boundary.
(deftest stateful-event-boundaries-test
  (let [document (-> (data/new-document "event-mix")
                     (data/add-animation {:id "next" :duration 1.0})
                     (events/add-definition {:name "step" :int 7})
                     (events/set-keyframe "default" {:name "step" :time 0.0})
                     (events/set-keyframe "default" {:name "step" :time 0.5 :int 8})
                     (events/set-keyframe "default" {:name "step" :time 1.0 :int 9})
                     (events/set-keyframe "next" {:name "step" :time 0.0 :int 10}))
        state (mixing/create-state document {:tracks [{:animation "default" :loop true}]})
        whole (mixing/advance-state document state 2.2)
        parts (reduce (fn [result delta]
                        (let [frame (mixing/advance-state document (:state result) delta)]
                          {:state (:state frame) :events (into (:events result) (:events frame))}))
                      {:state state :events []} [0.13 0.07 0.31 0.49 0.4 0.3 0.5])
        changed (mixing/set-track document (:state parts) 0 {:animation "next"})
        frame (mixing/advance-state document changed 0.0)]
    (is (= [7 8 9 7 8 9 7] (mapv :int (:events whole))))
    (is (= (mapv (juxt :int :cycle) (:events whole)) (mapv (juxt :int :cycle) (:events parts))))
    (is (= [10] (mapv :int (:events frame))))
    (is (= [] (:events (mixing/advance-state document (:state frame) 0.0))))
    (is (= [] (:events (mixing/seek-state document (:state frame) 1.5))))))

;; Linked meshes inherit the parent's deform and Sequence ownership during
;; mixing while retaining their own numbered image paths.
(deftest linked-mesh-mixing-ownership-test
  (let [mesh {"type" "mesh" "uvs" [0 0 1 0 0 1] "triangles" [0 1 2]
              "vertices" [0 0 10 0 0 10] "hull" 3}
        source {"skeleton" {"spine" "4.2.120"}
                "bones" [{"name" "root"}]
                "slots" [{"name" "body" "bone" "root" "attachment" "copy"}]
                "skins" [{"name" "default" "attachments"
                          {"body" {"mesh" mesh "copy" {"type" "linkedmesh" "parent" "mesh" "path" "copy"
                                                       "sequence" {"count" 3 "digits" 2}}}}}]
                "animations" {"base" {"bones" {"root" {"rotate" [{"time" 1.0}]}}}
                              "overlay" {"attachments" {"default" {"body" {"mesh"
                                                                           {"deform" [{"vertices" [2 4 6 8 10 12]} {"time" 1.0 "vertices" [2 4 6 8 10 12]}]
                                                                            "sequence" [{"index" 2 "mode" "hold"}]}}}}}}}
        document (data/import-spine-json (json/write-str source))
        state (mixing/create-state document {:tracks [{:animation "base"} {:animation "overlay" :alpha 0.5}]})
        result (mixing/advance-state document state 0.5)]
    (is (= "/copy03.png" (get-in result [:slots 0 :attachment :path])))
    (is (close? [1 2 3 4 5 6] (get-in result [:deforms "body"])))
    (is (= ["default" "mesh"] (get-in result [:slots 0 :preview-timeline-key])))
    (let [source (-> source
                     (assoc-in ["skins" 0 "attachments" "body" "alternate"]
                               {"type" "linkedmesh" "parent" "mesh" "path" "alternate"})
                     (assoc-in ["animations" "base" "attachments"] (get-in source ["animations" "overlay" "attachments"]))
                     (assoc-in ["animations" "overlay" "attachments" "default" "body" "mesh" "deform"]
                               [{"vertices" [4 8 12 16 20 24]} {"time" 1.0 "vertices" [4 8 12 16 20 24]}])
                     (assoc-in ["animations" "overlay" "slots" "body" "attachment"] [{"name" "alternate"}]))
          document (data/import-spine-json (json/write-str source))
          state (mixing/create-state document {:tracks [{:animation "base"} {:animation "overlay" :alpha 0.5}]})
          result (mixing/advance-state document state 0.5)]
      (is (= "/alternate.png" (get-in result [:slots 0 :attachment :path])))
      (is (close? [3 6 9 12 15 18] (get-in result [:deforms "body"]))))))
