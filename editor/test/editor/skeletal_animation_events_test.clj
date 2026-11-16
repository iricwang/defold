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

(ns editor.skeletal-animation-events-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-events :as events]
            [editor.skeletal-animation-timeline :as timeline]
            [util.coll :as coll]))

(defn- fixture []
  (-> (data/new-document "events")
      (events/add-definition {:name "footstep" :int 7 :float 0.25 :string "stone" :audio "step.wav" :volume 0.8 :balance -0.2})
      (events/add-definition {:name "hit"})
      (events/set-keyframe "default" {:name "footstep" :time 0.0})
      (events/set-keyframe "default" {:name "hit" :time 0.5 :int 5})
      (events/set-keyframe "default" {:name "footstep" :time 1.0 :string "wood"})))

(defn- source-fixture []
  (-> (json/read-str (data/export-spine-json (data/new-document "imported")))
      (assoc "events" {"footstep" {"audio" "step.wav" "int" 3 "vendor" {"hyphen-key" [1 2]}}
                       "hit" {"string" "impact"}})
      (assoc-in ["animations" "default" "events"]
                [{"time" 0.25 "name" "hit" "int" 2 "extension" {"value" 7}}
                 {"time" 0.25 "name" "footstep" "volume" 0.7 "curve" [8 9 10 11]}
                 {"time" 0.75 "name" "hit" "string" "late"}])))

;; Playback intervals emit once, resolve inherited payloads and retain both
;; duration/zero keys across multiple loops, including zero-duration animations.
(deftest triggered-intervals-test
  (let [document (fixture)
        initial (events/triggered document "default" -1.0 0.0 false)
        one-shot (events/triggered document "default" 0.0 20.0 false)
        loops (events/triggered document "default" 0.0 2.0 true)]
    (is (= ["footstep"] (mapv :name initial)))
    (is (= {:int 7 :float 0.25 :string "stone" :audio "step.wav" :volume 0.8 :balance -0.2}
           (select-keys (first initial) [:int :float :string :audio :volume :balance])))
    (is (= ["hit" "footstep"] (mapv :name one-shot)))
    (is (= [0.5 1.0 1.0 1.5 2.0 2.0] (mapv :absolute-time loops)))
    (is (= ["" "wood" "stone" "" "wood" "stone"] (mapv :string loops)))
    (is (= [0 0 1 1 1 2] (mapv :cycle loops)))
    (is (= [] (events/triggered document "default" 1.0 1.0 true)))
    (is (= [1.5] (mapv :absolute-time (events/triggered document "default" 1.0 1.5 true))))
    (is (= loops (into (events/triggered document "default" 0.0 1.0 true)
                       (events/triggered document "default" 1.0 2.0 true)))))
  (let [document (-> (data/new-document "zero")
                     (data/update-animation "default" {:duration 0.0})
                     (events/add-definition {:name "start"})
                     (events/set-keyframe "default" {:name "start" :time 0.0}))]
    (is (= 1 (count (events/triggered document "default" -1.0 10.0 true))))
    (is (= [] (events/triggered document "default" 0.0 10.0 true)))))

;; Authoring changes defaults without baking inherited values, extends duration,
;; renames every track, and deletes source-backed keys without resurrection.
(deftest event-authoring-test
  (let [document (fixture)
        document (events/update-definition document "footstep" {:name "step" :int 9})
        extended (events/set-keyframe document "default" {:name "step" :time 2.5 :float 0.9})
        removed (events/remove-keyframe extended "default" "step" 1.0)
        deleted (events/remove-definition removed "step")]
    (is (= ["step" "hit" "step"] (mapv :name (events/keyframes document "default"))))
    (is (= 9 (:int (first (events/triggered document "default" -1.0 0.0 false)))))
    (is (not (contains? (first (events/keyframes document "default")) :int)))
    (is (= 2.5 (get-in extended [:animations 0 :duration])))
    (is (= [0.0 0.5 2.5] (mapv :time (events/keyframes removed "default"))))
    (is (= ["hit"] (mapv :name (events/definitions deleted))))
    (is (= ["hit"] (mapv :name (events/keyframes deleted "default"))))
    (is (thrown? clojure.lang.ExceptionInfo (data/update-animation extended "default" {:duration 2.0}))))
  (let [imported (data/import-spine-json (json/write-str (source-fixture)))
        deleted (events/remove-definition imported "hit")
        output (json/read-str (data/export-spine-json deleted))]
    (is (= #{"footstep"} (set (coll/keys (get output "events")))))
    (is (= ["footstep"] (mapv #(get % "name") (get-in output ["animations" "default" "events"]))))))

;; Roundtrip and batch edits preserve unknown fields, same-time event order,
;; and explicit deletions; old documents materialize their retained source.
(deftest event-source-roundtrip-test
  (let [source (source-fixture)
        document (data/import-spine-json (json/write-str source))
        output (json/read-str (data/export-spine-json (data/read-json (data/write-json document))))
        legacy (-> document (dissoc :events) (update :animations #(mapv (fn [animation] (dissoc animation :event-tracks)) %)))
        legacy-output (json/read-str (data/export-spine-json legacy))
        moved (timeline/move-keys document "default" [{:type :event :id "footstep" :time 0.25}] 0.25 {})
        moved-output (json/read-str (data/export-spine-json moved))
        reverse (timeline/reverse-keys moved "default" (timeline/all-key-refs moved "default") 0.0 1.0 {})
        reverse-output (json/read-str (data/export-spine-json reverse))]
    (doseq [path [["events"] ["animations" "default" "events"]]]
      (is (= (get-in source path) (get-in output path)))
      (is (= (get-in source path) (get-in legacy-output path))))
    (is (= ["hit" "footstep" "hit"] (mapv :name (events/keyframes legacy "default"))))
    (is (= ["hit" "footstep" "hit"] (mapv #(get % "name") (get-in moved-output ["animations" "default" "events"]))))
    (is (= [8 9 10 11] (get-in moved-output ["animations" "default" "events" 1 "curve"])))
    (is (= [8 9 10 11] (get-in reverse-output ["animations" "default" "events" 1 "curve"])))
    (is (= {"hyphen-key" [1 2]} (get-in output ["events" "footstep" "vendor"])))
    (is (= (events/keyframes document "default") (events/keyframes legacy "default")))
    (is (= [] (get-in (json/read-str (data/export-spine-json (assoc-in document [:animations 0 :event-tracks] [])))
                      ["animations" "default" "events"])))
    (is (= [0.25 0.75] (mapv :time (events/keyframes (timeline/delete-keys legacy "default" [{:type :event :id "hit" :time 0.25}]) "default")))
        "Legacy deletion materializes the surviving event tracks.")))

;; Animation lifecycle operations carry event payloads and enforce authoring
;; duration while keeping stale retained animation JSON from overriding edits.
(deftest event-animation-lifecycle-test
  (let [document (data/import-spine-json (json/write-str (source-fixture)))
        document (events/set-keyframe document "default" {:name "hit" :time 0.25 :int 17})
        document (data/copy-animation document "default" "copy")
        document (data/update-animation document "copy" {:id "renamed"})
        document (data/remove-animation document "default")
        output (json/read-str (data/export-spine-json document))]
    (is (= #{"renamed"} (set (coll/keys (get output "animations")))))
    (is (= 17 (get-in output ["animations" "renamed" "events" 0 "int"])))
    (is (= [0.25 0.25 0.75] (mapv :time (events/keyframes document "renamed"))))))

;; Invalid payloads, missing definitions, duplicate keys and invalid playback
;; intervals fail explicitly instead of silently dropping or misfiring events.
(deftest event-validation-test
  (let [document (fixture)]
    (doseq [definition [{:name ""} {:name "hit"} {:name "bad" :int 0.5}
                        {:name "bad" :int 2147483648} {:name "bad" :float Double/NaN}
                        {:name "bad" :volume -1.0} {:name "bad" :balance 2.0}
                        {:name "bad" :string 3} {:name "bad" :audio nil}]]
      (is (thrown? clojure.lang.ExceptionInfo (events/add-definition document definition))))
    (doseq [frame [{:name "missing" :time 0.0} {:name "hit" :time -1.0}
                   {:name "hit" :time Double/POSITIVE_INFINITY} {:name "hit" :time 0.0 :int "1"}
                   {:name "hit" :time 0.0 :audio "key.wav"}]]
      (is (thrown? clojure.lang.ExceptionInfo (events/set-keyframe document "default" frame))))
    (doseq [[from to loop] [[1.0 0.0 false] [0.0 Double/NaN true] [0.0 1.0 nil]]]
      (is (thrown? clojure.lang.ExceptionInfo (events/triggered document "default" from to loop))))
    (is (thrown? clojure.lang.ExceptionInfo (events/update-definition document "hit" {:name "footstep"})))
    (is (thrown? clojure.lang.ExceptionInfo (events/keyframes document "missing")))
    (is (thrown? clojure.lang.ExceptionInfo
                 (data/validate-document (update-in document [:animations 0 :event-tracks 0 :keys] conj {:time 1.0}))))))

;; Prepared playback resolves defaults once, preserving source order and
;; payloads while leaving document edits outside an active immutable snapshot.
(deftest prepared-event-playback-test
  (let [document (data/import-spine-json (json/write-str (source-fixture)))
        prepared (events/prepare-playback document)]
    (doseq [[from to looping] [[-1.0 0.5 false] [0.25 0.75 false] [-1.0 3.0 true]]]
      (is (= (events/triggered document "default" from to looping)
             (events/triggered-playback prepared "default" from to looping))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (events/triggered-playback prepared "missing" 0.0 1.0 false)))))
