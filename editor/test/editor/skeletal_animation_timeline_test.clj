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

(ns editor.skeletal-animation-timeline-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-timeline :as timeline]
            [util.coll :as coll]))

(defn- close? [a b] (< (abs (double (- a b))) 1.0e-6))

(defn- fixture []
  (-> (data/new-document "timeline")
      (data/add-bone {:id "joint" :parent "root"})
      (data/add-bone {:id "target" :parent "root" :x 100.0})
      (data/add-ik {:id "reach" :bones ["joint"] :target "target"})
      (data/add-transform {:id "follow" :bones ["joint"] :target "target"})
      (data/add-slot {:id "image" :bone "root"
                      :attachment {:type "mesh" :name "image" :path "/images/image.png"
                                   :vertices [0.0 0.0 100.0 0.0 0.0 100.0]
                                   :uvs [0.0 0.0 1.0 0.0 0.0 1.0] :triangles [0 1 2]
                                   :weights [[{:bone "root" :weight 0.5 :x 0.0 :y 0.0} {:bone "joint" :weight 0.5 :x 0.0 :y 0.0}]
                                             [{:bone "root" :weight 1.0 :x 100.0 :y 0.0}]
                                             [{:bone "joint" :weight 1.0 :x 0.0 :y 100.0}]]}})
      (data/add-path {:id "path" :bones ["target"] :target "image"})
      (data/add-animation {:id "other" :duration 3.0 :tracks []})
      (data/set-keyframe "default" "root" "x" 1.0 10.0 "linear")
      (data/set-keyframe "default" "root" "x" 0.0 0.0 {:type "bezier" :curve [0.2 1.0 0.8 9.0]})
      (data/set-transform-keyframe "default" "follow" "mix-x" 1.0 1.0 "linear")
      (data/set-transform-keyframe "default" "follow" "mix-x" 0.0 0.0 {:type "bezier" :curve [0.2 0.1 0.8 0.9]})
      (data/set-path-keyframe "default" "path" "position" 1.0 100.0 "linear")
      (data/set-path-keyframe "default" "path" "position" 0.0 0.0 {:type "bezier" :curve [0.2 10.0 0.8 90.0]})
      (data/set-ik-keyframe-values "default" "reach" 1.0 {:mix 1.0 :softness 20.0} "linear")
      (data/set-ik-keyframe-values "default" "reach" 0.0 {:mix 0.0 :softness 0.0 :compress true} {:type "bezier" :curve [0.2 0.1 0.8 0.9]})
      (data/set-ik-softness-keyframe "default" "reach" 0.0 0.0 {:type "bezier" :curve [0.3 2.0 0.7 18.0]})
      (data/set-deform-keyframe "default" "image" 1.0 [1.0 2.0 3.0 4.0 5.0 6.0 7.0 8.0] "linear")
      (data/set-deform-keyframe "default" "image" 0.0 (vec (repeat 8 0.0)) {:type "bezier" :curve [0.2 0.15 0.7 0.95]})))

(defn- bone-ref [time] {:type :bone :id "root" :property "x" :time time})

;; Selections cross all supported sections, normalize numeric times, and fail
;; explicitly for stale references instead of applying only part of an edit.
(deftest selection-test
  (let [document (fixture) refs (timeline/all-key-refs document "default")]
    (is (= 10 (count refs)))
    (is (= #{:bone :transform :ik :deform :path} (into #{} (map :type) refs)))
    (is (= refs (timeline/select-keys document "default" (into (vec (rseq refs)) refs))))
    (is (= [(bone-ref 0.0)] (timeline/select-keys document "default" [(bone-ref 0) (bone-ref 0.0)])))
    (is (thrown? clojure.lang.ExceptionInfo (timeline/select-keys document "default" [(bone-ref 0.5)])))
    (is (thrown? clojure.lang.ExceptionInfo (timeline/select-keys document "default" [{:type :event :id "sound" :time 0.0}])))))

;; Moving all selected tracks is one simultaneous operation: their old times
;; are cleared first, and absolute controls/FFD arrays move without data loss.
(deftest move-batch-test
  (let [document (fixture) refs (timeline/all-key-refs document "default")
        moved (timeline/move-keys document "default" refs 0.5 {})]
    (is (= moved (timeline/move-keys document "default" (vec (rseq refs)) 0.5 {})))
    (is (= #{0.5 1.5} (into #{} (map :time) (timeline/all-key-refs moved "default"))))
    (is (= 1.5 (get-in moved [:animations 0 :duration])))
    (is (= [0.7 1.0 1.3 9.0] (get-in moved [:animations 0 :tracks 0 :keys 0 :curve])))
    (is (= [0.8 2.0 1.2 18.0] (get-in moved [:animations 0 :ik-tracks 0 :keys 0 :softness-curve])))
    (is (= [1.0 2.0 3.0 4.0 5.0 6.0 7.0 8.0] (get-in moved [:animations 0 :deforms 0 :keys 1 :vertices])))
    (is (= moved (data/validate-document moved)))))

;; Copy/paste crosses animations, creates missing tracks and keeps weighted
;; FFD channel identity, curve controls and arbitrary first-party metadata.
(deftest copy-paste-test
  (let [document (assoc-in (fixture) [:animations 0 :tracks 0 :keys 0 :note] "author")
        refs (timeline/all-key-refs document "default")
        clipboard (timeline/copy-keys document "default" refs)
        pasted (timeline/paste-keys document "other" clipboard 1.0 {})]
    (is (= clipboard (timeline/copy-keys document "default" (vec (rseq refs)))))
    (is (= 10 (count (timeline/all-key-refs pasted "other"))))
    (is (= #{1.0 2.0} (into #{} (map :time) (timeline/all-key-refs pasted "other"))))
    (is (= "author" (get-in pasted [:animations 1 :tracks 0 :keys 0 :note])))
    (is (= [1.2 1.0 1.8 9.0] (get-in pasted [:animations 1 :tracks 0 :keys 0 :curve])))
    (is (= (get-in document [:animations 0 :deforms 0 :keys 1 :vertices]) (get-in pasted [:animations 1 :deforms 0 :keys 1 :vertices])))
    (is (= (get-in document [:animations 0]) (get-in pasted [:animations 0])))
    (is (= 10 (count (timeline/all-key-refs (timeline/paste-keys document "default" clipboard 0.0 {}) "default"))))))

;; Positive scaling preserves normalized segment timing and stretches both
;; independently animated IK channels, including an untouched right neighbor.
(deftest scale-test
  (let [document (fixture)
        refs (timeline/all-key-refs document "default")
        scaled (timeline/scale-keys document "default" refs 0.0 2.0 {})]
    (is (= [0.4 1.0 1.6 9.0] (get-in scaled [:animations 0 :tracks 0 :keys 0 :curve])))
    (is (= [0.6 2.0 1.4 18.0] (get-in scaled [:animations 0 :ik-tracks 0 :keys 0 :softness-curve])))
    (is (= 2.0 (get-in scaled [:animations 0 :duration])))
    (is (close? (:x (first (data/sample-pose document "default" 0.3))) (:x (first (data/sample-pose scaled "default" 0.6)))))
    (is (= scaled (timeline/scale-keys document "default" (vec (rseq refs)) 0.0 2.0 {})))))

;; Reversal transfers outgoing curves to the reversed interval. FFD uses a
;; normalized progress curve, so its Y handles are reflected as well as time.
(deftest reverse-curves-test
  (let [document (fixture) refs (timeline/all-key-refs document "default")
        reversed (timeline/reverse-keys document "default" refs 0.0 1.0 {})]
    (is (= reversed (timeline/reverse-keys document "default" (vec (rseq refs)) 0.0 1.0 {})))
    (is (= [10.0 0.0] (mapv :value (get-in reversed [:animations 0 :tracks 0 :keys]))))
    (doseq [time [0.0 0.15 0.5 0.85 1.0]]
      (is (close? (:x (first (data/sample-pose document "default" time)))
                  (:x (first (data/sample-pose reversed "default" (- 1.0 time))))))
      (is (close? (:mix-x (first (data/sample-transforms document "default" time)))
                  (:mix-x (first (data/sample-transforms reversed "default" (- 1.0 time))))))
      (is (close? (:position (first (data/sample-paths document "default" time)))
                  (:position (first (data/sample-paths reversed "default" (- 1.0 time))))))
      (is (close? (:softness (first (data/sample-ik document "default" time)))
                  (:softness (first (data/sample-ik reversed "default" (- 1.0 time))))))
      (is (coll/every? true? (mapv close? (get (data/sample-deforms document "default" time) "image")
                                          (get (data/sample-deforms reversed "default" (- 1.0 time)) "image")))))))

;; Snap collisions use source-time order, not selection order; selected keys
;; replace resident ones, while :reject fails atomically for either collision.
(deftest snap-and-conflict-test
  (let [document (-> (data/new-document "snap")
                     (data/set-keyframe "default" "root" "x" 0.0 0.0 "linear")
                     (data/set-keyframe "default" "root" "x" 0.1 10.0 "linear")
                     (data/set-keyframe "default" "root" "x" 0.2 20.0 "linear"))
        refs [(bone-ref 0.1) (bone-ref 0.2)]
        snapped (timeline/snap-keys document "default" refs 1.0 {})]
    (is (= [{:time 0.0 :value 20.0 :interpolation "linear"}] (get-in snapped [:animations 0 :tracks 0 :keys])))
    (is (= snapped (timeline/snap-keys document "default" (vec (rseq refs)) 1.0 {})))
    (is (thrown? clojure.lang.ExceptionInfo (timeline/snap-keys document "default" refs 1.0 {:collision :reject})))
    (is (thrown? clojure.lang.ExceptionInfo (timeline/move-keys document "default" [(bone-ref 0.1)] -0.1 {:collision :reject})))
    (is (= 3 (count (get-in document [:animations 0 :tracks 0 :keys])))))
  (let [document (data/set-keyframe (data/new-document "snap") "default" "root" "x" 0.25 1.0 "linear")]
    (is (= 0.5 (:time (first (timeline/all-key-refs (timeline/snap-keys document "default" [(bone-ref 0.25)] 2.0 {}) "default")))))))

;; An unselected adjacent key remains present but its absolute control times
;; must follow the new interval when a selected neighbor moves or is deleted.
(deftest adjacent-curves-test
  (let [document (-> (data/new-document "adjacent")
                     (data/set-keyframe "default" "root" "x" 2.0 20.0 "linear")
                     (data/set-keyframe "default" "root" "x" 1.0 10.0 "linear")
                     (data/set-keyframe "default" "root" "x" 0.0 0.0 {:type "bezier" :curve [0.25 2.0 0.75 8.0]}))
        moved (timeline/move-keys document "default" [(bone-ref 1.0)] 0.5 {})
        deleted (timeline/delete-keys document "default" [(bone-ref 1.0)])]
    (is (= [0.375 2.0 1.125 8.0] (get-in moved [:animations 0 :tracks 0 :keys 0 :curve])))
    (is (= [0.5 2.0 1.5 8.0] (get-in deleted [:animations 0 :tracks 0 :keys 0 :curve])))
    (is (= [0.0 2.0] (mapv :time (get-in deleted [:animations 0 :tracks 0 :keys]))))))

;; Imported multi-channel IK raw frames travel with their key, including
;; unknown values. Export must not read an unrelated frame at the new time.
(deftest imported-ik-metadata-test
  (let [source (json/read-str (data/export-spine-json (fixture)))
        source (assoc-in source ["animations" "default" "ik" "reach" 0 "artist"] "first")
        source (assoc-in source ["animations" "default" "ik" "reach" 1 "artist"] "second")
        document (data/import-spine-json (json/write-str source))
        refs (filterv #(= :ik (:type %)) (timeline/all-key-refs document "default"))
        moved (timeline/move-keys document "default" refs 0.5 {})
        frames (get-in (json/read-str (data/export-spine-json moved)) ["animations" "default" "ik" "reach"])]
    (is (= ["first" "second"] (mapv #(get % "artist") frames)))
    (is (= [0.5 1.5] (mapv #(get % "time") frames)))
    (is (= [0.7 0.1 1.3 0.9 0.8 2.0 1.2 18.0] (get (first frames) "curve")))
    (is (= moved (data/read-json (data/write-json moved))))))

;; Deleting every selected key removes supported tracks while retaining
;; animation duration and unrelated source metadata. Empty selections are safe.
(deftest delete-and-empty-test
  (let [document (fixture)
        deleted (timeline/delete-keys document "default" (timeline/all-key-refs document "default"))]
    (is (= [] (timeline/all-key-refs deleted "default")))
    (is (= 1.0 (get-in deleted [:animations 0 :duration])))
    (is (= document (timeline/move-keys document "default" [] 0.5 {})))
    (is (= document (timeline/paste-keys document "default" (timeline/copy-keys document "default" []) 0.0 {})))))

;; Batch boundary checks reject negative/overflowing time, invalid scale/rate,
;; unknown policies and FFD clipboards whose topology changed after copying.
(deftest invalid-operation-test
  (let [document (fixture) refs [(bone-ref 0.0)]
        clipboard (timeline/copy-keys document "default" [{:type :deform :id "image" :time 0.0}])]
    (is (thrown? clojure.lang.ExceptionInfo (timeline/move-keys document "default" refs -0.5 {})))
    (is (thrown? clojure.lang.ExceptionInfo (timeline/move-keys document "default" refs ##Inf {})))
    (is (thrown? clojure.lang.ExceptionInfo (timeline/scale-keys document "default" refs 0.0 0.0 {})))
    (is (thrown? clojure.lang.ExceptionInfo (timeline/snap-keys document "default" refs 0.0 {})))
    (is (thrown? clojure.lang.ExceptionInfo (timeline/reverse-keys document "default" refs 0.5 1.0 {})))
    (is (thrown? clojure.lang.ExceptionInfo (timeline/move-keys document "default" refs 0.5 {:collision :append})))
    (is (thrown? clojure.lang.ExceptionInfo (timeline/paste-keys (assoc-in document [:slots 0 :attachment :vertices 0] 2.0) "other" clipboard 1.0 {})))
    (is (thrown? clojure.lang.ExceptionInfo (timeline/paste-keys document "other" {:version 2} 1.0 {})))))

;; A batch moves independently selected keys across bones without pulling
;; unselected keys along or dropping empty, unrelated authoring tracks.
(deftest multi-bone-and-unaffected-track-test
  (let [document (-> (data/new-document "bones")
                     (data/add-bone {:id "child" :parent "root"})
                     (data/set-keyframe "default" "root" "x" 0.0 2.0 "linear")
                     (data/set-keyframe "default" "root" "x" 1.0 7.0 "linear")
                     (data/set-keyframe "default" "child" "rotation" 0.0 30.0 "stepped")
                     (update-in [:animations 0 :tracks] conj {:bone "root" :property "y" :keys [] :note "keep"}))
        refs [(bone-ref 0.0) {:type :bone :id "child" :property "rotation" :time 0.0}]
        moved (timeline/move-keys document "default" refs 0.5 {})
        tracks (get-in moved [:animations 0 :tracks])]
    (is (= [0.5 1.0] (mapv :time (:keys (coll/first-where #(= "x" (:property %)) tracks)))))
    (is (= [{:time 0.5 :value 30.0 :interpolation "stepped"}] (:keys (coll/first-where #(= "rotation" (:property %)) tracks))))
    (is (= {:bone "root" :property "y" :keys [] :note "keep"} (peek tracks)))
    (is (= document (timeline/delete-keys document "default" [])))))

;; Clipboard contents are bound to both the active skin and its geometry.
;; Duplicate/malformed tracks are rejected rather than chosen by input order.
(deftest clipboard-integrity-test
  (let [document (fixture)
        clipboard (timeline/copy-keys document "default" [{:type :deform :id "image" :time 0.0}])]
    (is (thrown? clojure.lang.ExceptionInfo
                 (timeline/paste-keys (assoc-in document [:slots 0 :spine-skin] "other") "other" clipboard 0.0 {})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (timeline/paste-keys document "other" (update clipboard :tracks into (:tracks clipboard)) 0.0 {})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (timeline/paste-keys document "other" (assoc-in clipboard [:tracks 0 :entries 0 :key :time] nil) 0.0 {})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (timeline/paste-keys document "other" (assoc-in clipboard [:tracks 0 :track :slot] "different") 0.0 {})))))

;; Legacy documents without per-key raw metadata acquire it before retiming;
;; arbitrary source fields and all IK Bezier channels then follow their keys.
(deftest legacy-source-metadata-test
  (let [source (-> (json/read-str (data/export-spine-json (fixture)))
                   (assoc-in ["animations" "default" "ik" "reach" 0 "note"] "source"))
        document (update-in (data/import-spine-json (json/write-str source)) [:animations 0 :ik-tracks 0 :keys]
                            #(mapv (fn [key] (dissoc key :spine-frame-source)) %))
        refs (filterv #(= :ik (:type %)) (timeline/all-key-refs document "default"))
        moved (timeline/scale-keys document "default" refs 0.0 2.0 {})
        frame (get-in (json/read-str (data/export-spine-json moved)) ["animations" "default" "ik" "reach" 0])]
    (is (= "source" (get frame "note")))
    (is (= [0.4 0.1 1.6 0.9 0.6 2.0 1.4 18.0] (get frame "curve")))))

(defn- physics-document []
  (-> (data/new-document "physics-timeline")
      (data/add-physics {:id "spring" :bone "root" :wind-global true})
      (data/add-animation {:id "other" :duration 3.0 :tracks []})
      (data/set-physics-keyframe "default" "spring" "wind" 1.0 4.0 "linear")
      (data/set-physics-keyframe "default" "spring" "wind" 0.0 2.0 {:type "bezier" :curve [0.2 2.5 0.8 3.5]})
      (data/set-physics-keyframe "default" "" "wind" 0.0 7.0 "linear")
      (data/set-physics-reset-keyframe "default" "spring" 0.25)
      (data/set-physics-reset-keyframe "default" "" 0.75)
      (assoc-in [:animations 0 :physics-order] ["spring" ""])))

;; Physics batches include global tracks and discrete reset events. Moving,
;; scaling, reversing and snapping preserve reset semantics and curve timing.
(deftest physics-batch-test
  (let [document (physics-document)
        refs (timeline/all-key-refs document "default")
        moved (timeline/move-keys document "default" refs 0.5 {})
        scaled (timeline/scale-keys document "default" refs 0.0 2.0 {})
        reversed (timeline/reverse-keys document "default" refs 0.0 1.0 {})
        reset-times (fn [doc]
                      (into {} (comp (filter #(= "reset" (:property %)))
                                     (map (fn [track] [(:physics track) (mapv :time (:keys track))])))
                            (get-in doc [:animations 0 :physics-tracks])))
        wind-keys (fn [doc] (:keys (coll/first-where #(and (= "spring" (:physics %)) (= "wind" (:property %)))
                                                                   (get-in doc [:animations 0 :physics-tracks]))))]
    (is (= 5 (count refs)))
    (is (= #{:physics} (into #{} (map :type) refs)))
    (is (= refs (timeline/select-keys document "default" (vec (rseq refs)))))
    (is (= {"spring" [0.75] "" [1.25]} (reset-times moved)))
    (is (= {"spring" [0.5] "" [1.5]} (reset-times scaled)))
    (is (= {"spring" [0.75] "" [0.25]} (reset-times reversed)))
    (is (= [0.7 2.5 1.3 3.5] (:curve (first (wind-keys moved)))))
    (is (= [0.4 2.5 1.6 3.5] (:curve (first (wind-keys scaled)))))
    (is (= [4.0 2.0] (mapv :value (wind-keys reversed))))
    (is (= {"spring" [0.5] "" [1.0]}
           (reset-times (timeline/snap-keys document "default" refs 2.0 {}))))
    (is (coll/every? #(= "stepped" (:interpolation %))
                    (eduction (filter #(= "reset" (:property %))) (mapcat :keys)
                              (get-in reversed [:animations 0 :physics-tracks]))))
    (is (= [] (timeline/all-key-refs (timeline/delete-keys document "default" refs) "default")))
    (is (= moved (data/read-json (data/write-json moved))))))

;; Copying global and per-constraint physics tracks must retain their source
;; precedence. Otherwise the target clip samples a different global override.
(deftest physics-paste-order-test
  (let [document (physics-document)
        refs (timeline/all-key-refs document "default")
        clipboard (timeline/copy-keys document "default" refs)
        pasted (timeline/paste-keys document "other" clipboard 1.0 {})]
    (is (= ["spring" ""] (get-in pasted [:animations 1 :physics-order])))
    (is (= 7.0 (:wind (first (data/sample-physics pasted "other" 1.5)))))
    (is (= 5 (count (timeline/all-key-refs pasted "other"))))
    (is (= pasted (timeline/paste-keys document "other" (update clipboard :tracks #(vec (rseq %))) 1.0 {})))
    (is (= 1.25 (:time (first (:keys (coll/first-where #(and (= "spring" (:physics %)) (= "reset" (:property %)))
                                                                     (get-in pasted [:animations 1 :physics-tracks])))))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (timeline/paste-keys document "other" (assoc-in clipboard [:tracks 0 :physics-rank] -1) 1.0 {})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (timeline/select-keys document "default" [{:type :bone :id "" :property "x" :time 0.0}])))))

;; Legacy Physics keys hydrate raw metadata from their property-specific JSON
;; array before retiming so source-only fields and curves follow copied keys.
(deftest physics-legacy-metadata-test
  (let [source {"animations" {"default" {"physics" {"spring" {"wind" [{"time" 0.0 "value" 2.0 "note" "keep"
                                                                                 "curve" [0.2 2.5 0.8 3.5]}]}}}}}
        document (-> (physics-document)
                     (assoc :spine-source (json/write-str source))
                     (update-in [:animations 0 :physics-tracks]
                                #(mapv (fn [track] (update track :keys (fn [keys] (mapv (fn [key] (dissoc key :spine-frame-source)) keys)))) %)))
        moved (timeline/move-keys document "default"
                                  [{:type :physics :id "spring" :property "wind" :time 0.0}] 0.5 {})
        key (first (:keys (coll/first-where #(and (= "spring" (:physics %)) (= "wind" (:property %)))
                                                       (get-in moved [:animations 0 :physics-tracks]))))
        raw (json/read-str (:spine-frame-source key))]
    (is (= "keep" (get raw "note")))
    (is (= 0.5 (get raw "time")))
    (is (= [0.6 2.5 0.9 3.5] (get raw "curve")))))
