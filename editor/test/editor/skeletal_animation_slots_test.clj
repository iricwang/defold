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

(ns editor.skeletal-animation-slots-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-slots :as slots]
            [editor.skeletal-animation-timeline :as timeline]
            [util.coll :as coll]))

(defn- source []
  {"skeleton" {"spine" "4.2.43"}
   "bones" [{"name" "root"}]
   "slots" [{"name" "a" "bone" "root" "attachment" "one"}
            {"name" "b" "bone" "root" "attachment" "b"}
            {"name" "c" "bone" "root" "attachment" "c"}
            {"name" "d" "bone" "root" "attachment" "d"}]
   "skins" [{"name" "default" "attachments" {"a" {"one" {"path" "one" "width" 20 "height" 20}
                                                  "two" {"path" "two" "width" 16 "height" 16}}
                                             "b" {"b" {"path" "b"}}
                                             "c" {"c" {"path" "c"}}
                                             "d" {"d" {"path" "d"}}}}
            {"name" "winter" "attachments" {"a" {"one" {"path" "winter" "width" 30 "height" 30}}}}]
   "animations" {"move" {"slots" {"a" {"attachment" [{"time" 0.25 "name" "two"} {"time" 0.75} {"time" 1 "name" "one"}]}}
                         "drawOrder" [{"time" 0.5 "offsets" [{"slot" "a" "offset" 2} {"slot" "d" "offset" -2}]}
                                      {"time" 1}]}}})

(defn- document [source] (data/import-spine-json (json/write-str source)))

(defn- sampled-slot [result id] (coll/first-where #(= id (:id %)) (:slots result)))

(defn- near? [a b] (< (abs (double (- a b))) 1.0e-6))

;; Attachment keys are discrete, absent names hide the slot, and returning to
;; the setup attachment must retain unsaved editor changes without source edits.
(deftest attachment-switch-and-edited-setup-test
  (let [doc (-> (document (source)) (data/update-slot "a" {:attachment {:width 42.0}}))
        sample #(sampled-slot (slots/sample-slots doc "move" %) "a")]
    (is (= 42.0 (get-in (sample 0.0) [:attachment :width])))
    (is (true? (:preview-editable-attachment (sample 0.0))))
    (is (= "/two.png" (get-in (sample 0.25) [:attachment :path])))
    (is (false? (:preview-editable-attachment (sample 0.25))))
    (is (nil? (:attachment (sample 0.75))))
    (is (= 42.0 (get-in (sample 1.0) [:attachment :width])))
    (is (= (sample 1.0) (sample 100.0)))
    (is (= "/one.png" (get-in (sampled-slot (slots/sample-slots doc nil 0.5) "a") [:attachment :path])))
    (is (= doc (data/read-json (data/write-json doc))))))

;; Skin-local attachments override the default skin, while timeline names
;; missing in the active skin still resolve through the default fallback.
(deftest active-skin-fallback-test
  (let [doc (data/select-skin (document (source)) "winter")]
    (is (= "/winter.png" (get-in (sampled-slot (slots/sample-slots doc "move" 0.0) "a") [:attachment :path])))
    (is (= ["winter" "one"] (:preview-attachment-key (sampled-slot (slots/sample-slots doc "move" 0.0) "a"))))
    (is (= ["default" "two"] (:preview-attachment-key (sampled-slot (slots/sample-slots doc "move" 0.5) "a"))))
    (is (= [] (:warnings (slots/sample-slots doc "move" 0.5))))))

;; Draw order offsets simultaneously place changed slots and retain the
;; relative order of unchanged slots; an empty frame restores setup order.
(deftest draw-order-test
  (let [doc (document (source)) sample #(mapv :id (:slots (slots/sample-slots doc "move" %)))]
    (is (= ["a" "b" "c" "d"] (sample 0.0)))
    (is (= ["b" "d" "a" "c"] (sample 0.5)))
    (is (= ["a" "b" "c" "d"] (sample 1.0)))
    (let [invalid (document (assoc-in (source) ["animations" "move" "drawOrder" 0 "offsets"] [{"slot" "a" "offset" 99}]))
          result (slots/sample-slots invalid "move" 0.5)]
      (is (= ["a" "b" "c" "d"] (mapv :id (:slots result))))
      (is (pos? (count (:warnings result)))))))

;; RGBA, RGB and alpha use absolute values. Timeline object order is retained
;; so overlapping RGB/alpha channels match the source runtime's overwrite order.
(deftest slot-color-channel-test
  (let [raw (assoc-in (source) ["animations" "move" "slots" "a"]
                      (array-map "rgba" [{"time" 0 "color" "00000000"} {"time" 1 "color" "ffffffff"}]
                                 "rgb" [{"time" 0 "color" "ff0000" "curve" "stepped"} {"time" 1 "color" "00ff00"}]
                                 "alpha" [{"time" 0 "value" 0.2} {"time" 1 "value" 0.8}]))
        doc (document raw)
        color (:color (sampled-slot (slots/sample-slots doc "move" 0.5) "a"))]
    (is (coll/every? true? (mapv near? [1.0 0.0 0.0 0.5] color)))
    (is (= [] (:warnings (slots/sample-slots doc "move" 0.5))))
    (let [doc (document (assoc-in (source) ["animations" "move" "slots" "a"] {"alpha" [{"time" 0.5 "value" 0.4}]}))
          doc (data/update-slot doc "a" {:color [0.2 0.3 0.4 0.7]})]
      (is (= [0.2 0.3 0.4 0.7] (:color (sampled-slot (slots/sample-slots doc "move" 0.25) "a"))))
      (is (= [0.2 0.3 0.4 0.4] (:color (sampled-slot (slots/sample-slots doc "move" 0.5) "a")))))))

;; Each RGBA Bezier channel has independent absolute controls. Invalid curves
;; hide the affected slot with a warning rather than interrupting the renderer.
(deftest slot-color-bezier-test
  (let [controls [0.25 0.0 0.75 1.0 0.25 1.0 0.75 1.0 0.25 0.0 0.75 0.0 0.25 0.2 0.75 0.8]
        raw (assoc-in (source) ["animations" "move" "slots" "a"]
                      {"rgba" [{"time" 0 "color" "00000000" "curve" controls} {"time" 1 "color" "ffffffff"}]})
        color (:color (sampled-slot (slots/sample-slots (document raw) "move" 0.5) "a"))]
    (is (coll/every? true? (mapv near? [0.5 0.875 0.125 0.5] color)))
    (let [result (slots/sample-slots (document (assoc-in raw ["animations" "move" "slots" "a" "rgba" 0 "curve"] [0.2 0 0.8 1])) "move" 0.5)]
      (is (nil? (:attachment (sampled-slot result "a"))))
      (is (pos? (count (:warnings result)))))))

(defn- mesh-source []
  (-> (source)
      (assoc-in ["skins" 0 "attachments" "a"]
                {"one" {"type" "mesh" "path" "one" "vertices" [0 0 10 0 0 10] "uvs" [0 0 1 0 0 1] "triangles" [0 1 2]}
                 "two" {"type" "mesh" "path" "two" "vertices" [0 0 10 0 10 10 0 10] "uvs" [0 0 1 0 1 1 0 1] "triangles" [0 1 2 0 2 3]}})
      (assoc-in ["animations" "move" "slots" "a" "attachment"] [{"time" 0.5 "name" "two"}])
      (assoc-in ["animations" "move" "attachments" "default" "a"]
                {"one" {"deform" [{"time" 0 "vertices" [1 2 3 4 5 6]}]}
                 "two" {"deform" [{"time" 0 "offset" 2 "vertices" [2 4]} {"time" 1 "offset" 2 "vertices" [6 8]}]}})))

;; Alternate meshes can have a different vertex count and private FFD. The
;; editable mesh's changed/deleted FFD overrides preserved source keys only
;; for that attachment, never for another image under the same slot.
(deftest switched-mesh-deformation-test
  (let [doc (data/set-deform-keyframe (document (mesh-source)) "move" "a" 0.0 [9 9 9 9 9 9] "linear")]
    (is (= [9 9 9 9 9 9] (get-in (slots/sample-slots doc "move" 0.0) [:deforms "a"])))
    (is (= [0.0 0.0 4.0 6.0 0.0 0.0 0.0 0.0] (get-in (slots/sample-slots doc "move" 0.5) [:deforms "a"])))
    (is (nil? (get-in (slots/sample-slots (data/remove-deform-keyframe doc "move" "a" 0.0) "move" 0.0) [:deforms "a"])))
    (is (= 8 (count (get-in (sampled-slot (slots/sample-slots doc "move" 0.5) "a") [:attachment :vertices]))))))

;; Linked meshes share current parent geometry and optionally its FFD. A
;; private linked track remains private even across a default-skin fallback.
(deftest linked-mesh-deformation-test
  (let [raw (-> (mesh-source)
                (assoc-in ["skins" 0 "attachments" "a" "two"] {"type" "linkedmesh" "parent" "one" "path" "linked"})
                (assoc-in ["animations" "move" "attachments" "default" "a" "two" "deform"] [{"time" 0 "vertices" [7 7 7 7 7 7]}]))
        doc (data/set-deform-keyframe (document raw) "move" "a" 0.0 [9 9 9 9 9 9] "linear")
        result (slots/sample-slots doc "move" 0.5)]
    (is (= "/linked.png" (get-in (sampled-slot result "a") [:attachment :path])))
    (is (= [9 9 9 9 9 9] (get-in result [:deforms "a"])))
    (is (= [7 7 7 7 7 7]
           (get-in (slots/sample-slots (document (assoc-in raw ["skins" 0 "attachments" "a" "two" "deform"] false)) "move" 0.5) [:deforms "a"])))
    (let [cyclic (document (assoc-in raw ["skins" 0 "attachments" "a" "two" "parent"] "two"))
          result (slots/sample-slots cyclic "move" 0.5)]
      (is (nil? (:attachment (sampled-slot result "a"))))
      (is (pos? (count (:warnings result)))))))

;; Unknown attachment types remain explicit errors in preview; implemented
;; sequence and dark-tint types are covered by attachment integration tests.
(deftest unsupported-preview-test
  (let [doc (document (assoc-in (source) ["skins" 0 "attachments" "a" "two" "type"] "future-type"))
        result (slots/sample-slots doc "move" 0.5)]
    (is (nil? (:attachment (sampled-slot result "a"))))
    (is (pos? (count (:warnings result)))))
  (is (thrown? clojure.lang.ExceptionInfo (slots/sample-slots (document (source)) "absent" 0.0)))
  (is (thrown? clojure.lang.ExceptionInfo (slots/sample-slots (document (source)) "move" ##NaN))))

;; FFD and setup edits retain parsed source JSON while rebuilding attachment
;; data. Replacing the source text must invalidate both values and key order.
(deftest document-cache-test
  (let [doc (document (source))
        parsed (#'slots/parsed-source (:spine-source doc))]
    (slots/sample-slots doc "move" 0.1)
    (slots/sample-slots doc "move" 0.2)
    (let [updated (data/update-slot doc "a" {:attachment {:width 44.0}})]
      (is (= 44.0 (get-in (sampled-slot (slots/sample-slots updated "move" 0.0) "a") [:attachment :width])))
      (is (identical? parsed (#'slots/parsed-source (:spine-source updated))))
      (let [changed (assoc updated :spine-source
                           (json/write-str (assoc-in (source) ["animations" "move" "slots" "a" "alpha"] [{"time" 0 "value" 0.25}])))]
        (is (= 0.25 (peek (:color (sampled-slot (slots/sample-slots changed "move" 0.0) "a")))))
        (is (not (identical? parsed (#'slots/parsed-source (:spine-source changed)))))))))

;; Authored switches and hide keys must preview, export and save correctly;
;; replacing an imported key preserves its unknown/custom fields.
(deftest authored-attachment-key-test
  (let [raw (assoc-in (source) ["animations" "move" "slots" "a" "attachment" 0 "custom"] {"keep" 17})
        doc (-> (document raw)
                (slots/set-keyframe "move" "a" "attachment" {:time 0.25 :name "one"})
                (slots/set-keyframe "move" "a" "attachment" {:time 2.0 :name nil}))
        output (json/read-str (data/export-spine-json doc))]
    (is (= ["one" "two"] (slots/attachment-names doc "a")))
    (is (= "one" (get-in (sampled-slot (slots/sample-slots doc "move" 0.25) "a") [:attachment :name])))
    (is (nil? (:attachment (sampled-slot (slots/sample-slots doc "move" 2.0) "a"))))
    (is (= 2.0 (get-in doc [:animations 0 :duration])))
    (is (= {"keep" 17} (get-in output ["animations" "move" "slots" "a" "attachment" 0 "custom"])))
    (is (= doc (data/read-json (data/write-json doc))))
    (is (nil? (:attachment (sampled-slot (slots/sample-slots (document output) "move" 2.0) "a"))))
    (let [removed (slots/remove-keyframe doc "move" "a" "attachment" 2.0)]
      (is (= "one" (get-in (sampled-slot (slots/sample-slots removed "move" 2.0) "a") [:attachment :name]))))))

;; RGB/RGBA/alpha authoring retains runtime channel overwrite order and rejects
;; invalid colors and nonfinite/out-of-range values at the editing boundary.
(deftest authored-color-key-test
  (let [doc (-> (document (source))
                (slots/set-keyframe "move" "b" "rgba" {:time 0 :color "00000000" :interpolation "linear"})
                (slots/set-keyframe "move" "b" "rgba" {:time 1 :color "ffffffff" :interpolation "linear"})
                (slots/set-keyframe "move" "b" "rgb" {:time 0 :color "ff0000" :interpolation "stepped"})
                (slots/set-keyframe "move" "b" "rgb" {:time 1 :color "00ff00" :interpolation "linear"})
                (slots/set-keyframe "move" "b" "alpha" {:time 0 :value 0.2 :interpolation "linear"})
                (slots/set-keyframe "move" "b" "alpha" {:time 1 :value 0.8 :interpolation "linear"}))]
    (is (coll/every? true? (mapv near? [1.0 0.0 0.0 0.5]
                                 (:color (sampled-slot (slots/sample-slots doc "move" 0.5) "b")))))
    (is (= (:slots (slots/sample-slots doc "move" 0.5))
           (:slots (slots/sample-slots (document (json/read-str (data/export-spine-json doc))) "move" 0.5))))
    (doseq [[property key] [["alpha" {:time 0 :value 2}] ["alpha" {:time 0 :value ##NaN}]
                            ["rgba" {:time 0 :color "abc"}] ["rgb" {:time 0 :color "ffffffff"}]
                            ["attachment" {:time -1 :name "one"}] ["attachment" {:time ##Inf :name "one"}]]]
      (is (thrown? clojure.lang.ExceptionInfo (slots/set-keyframe doc "move" "b" property key))))))

;; Complete draw-order permutations must export sorted offsets that reproduce
;; the requested runtime order, with no duplicate or missing slot identities.
(deftest authored-draw-order-key-test
  (let [doc (document (source))]
    (doseq [order [["a" "b" "c" "d"] ["d" "c" "b" "a"] ["b" "d" "a" "c"] ["a" "d" "c" "b"]]]
      (let [edited (slots/set-draw-order-keyframe doc "move" 0.3 order)
            restored (document (json/read-str (data/export-spine-json edited)))]
        (is (= order (mapv :id (:slots (slots/sample-slots edited "move" 0.3)))))
        (is (= order (mapv :id (:slots (slots/sample-slots restored "move" 0.3)))))
        (is (= ["a" "b" "c" "d"] (mapv :id (:slots (slots/sample-slots (slots/remove-draw-order-keyframe edited "move" 0.3) "move" 0.3)))))))
    (is (thrown? clojure.lang.ExceptionInfo (slots/set-draw-order-keyframe doc "move" 0 ["a" "a" "c" "d"])))
    (is (thrown? clojure.lang.ExceptionInfo (slots/set-draw-order-keyframe doc "move" 0 ["a" "b"])))))

;; Batch projections keep full multi-channel curves and custom source metadata;
;; only temporary projections disappear when the edits are persisted.
(deftest slot-track-materialization-test
  (let [curve [0.25 0.0 0.75 1.0 0.25 1.0 0.75 1.0 0.25 0.0 0.75 0.0 0.25 0.2 0.75 0.8]
        raw (-> (source)
                (assoc-in ["animations" "move" "slots" "b" "rgba"]
                          [{"time" 0 "color" "00000000" "curve" curve "custom" 42} {"time" 1 "color" "ffffffff"}])
                (assoc-in ["animations" "move" "slots" "b" "futureTimeline"] [{"time" 0 "private" 9}]))
        doc (document raw)
        materialized (slots/materialize-tracks doc)
        persisted (slots/persist-tracks materialized "move")
        output (json/read-str (data/export-spine-json persisted))]
    (is (= 2 (count (get-in materialized [:animations 0 :slot-tracks]))))
    (is (= ["b" "d" "a" "c"] (get-in materialized [:animations 0 :draw-order-tracks 0 :keys 0 :order])))
    (is (= curve (get-in output ["animations" "move" "slots" "b" "rgba" 0 "curve"])))
    (is (= 42 (get-in output ["animations" "move" "slots" "b" "rgba" 0 "custom"])))
    (is (= [{"time" 0 "private" 9}] (get-in output ["animations" "move" "slots" "b" "futureTimeline"])))
    (is (not (contains? (first (:animations persisted)) :slot-tracks)))
    (is (not (contains? (first (:animations persisted)) :draw-order-tracks)))
    (is (= (slots/sample-slots doc "move" 0.5) (slots/sample-slots persisted "move" 0.5)))))

;; The first authored slot key on a fresh document must preserve setup metadata;
;; deletion restores setup colors and duration cannot be shortened across keys.
(deftest first-slot-timeline-and-removal-test
  (let [doc (-> (document (source)) (dissoc :spine-source))
        edited (slots/set-keyframe doc "move" "a" "alpha" {:time 0 :value 0.25})
        removed (slots/remove-keyframe edited "move" "a" "alpha" 0)]
    (is (= 0.25 (peek (:color (sampled-slot (slots/sample-slots edited "move" 0) "a")))))
    (is (= 1.0 (peek (:color (sampled-slot (slots/sample-slots removed "move" 0) "a")))))
    (is (not (contains? (get-in (json/read-str (data/export-spine-json removed)) ["animations" "move" "slots" "a"]) "alpha")))
    (is (thrown? clojure.lang.ExceptionInfo (data/update-animation (slots/set-draw-order-keyframe edited "move" 2 ["d" "c" "b" "a"]) "move" {:duration 1})))))

;; Slot batch move/copy/delete must use the same identities as the timeline UI
;; and preserve every color curve channel when moving to a different interval.
(deftest slot-batch-key-editing-test
  (let [curve [0.25 0.0 0.75 1.0 0.25 1.0 0.75 1.0 0.25 0.0 0.75 0.0 0.25 0.2 0.75 0.8]
        doc (document (assoc-in (source) ["animations" "move" "slots" "b" "rgba"]
                                [{"time" 0 "color" "00000000" "curve" curve "custom" 42} {"time" 1 "color" "ffffffff"}]))
        color-refs [{:type :slot :id "b" :property "rgba" :time 0} {:type :slot :id "b" :property "rgba" :time 1}]
        moved (timeline/move-keys doc "move" color-refs 1 {})
        moved-frames (get-in (json/read-str (data/export-spine-json moved)) ["animations" "move" "slots" "b" "rgba"])
        copied (timeline/paste-keys doc "move" (timeline/copy-keys doc "move" color-refs) 2 {})
        reversed (timeline/reverse-keys doc "move" color-refs 0 1 {})
        reversed-frames (get-in (json/read-str (data/export-spine-json reversed)) ["animations" "move" "slots" "b" "rgba"])]
    (is (= [1.0 2.0] (mapv #(double (get % "time")) moved-frames)))
    (is (= (mapv (fn [i value] (if (even? i) (+ value 1.0) value)) (range (count curve)) curve)
           (get (first moved-frames) "curve")))
    (is (= 42 (get (first moved-frames) "custom")))
    (is (= 4 (count (:keys (coll/first-where #(= "rgba" (:property %)) (slots/tracks copied "move" "b"))))))
    (is (= "ffffffff" (get (first reversed-frames) "color")))
    (is (= [0.25 1.0 0.75 0.0 0.25 1.0 0.75 1.0 0.25 0.0 0.75 0.0 0.25 0.8 0.75 0.2]
           (get (first reversed-frames) "curve")))
    (let [removed (timeline/delete-keys doc "move" color-refs)]
      (is (zero? (count (slots/tracks removed "move" "b"))))))
  (let [doc (document (source))
        refs [{:type :slot :id "a" :property "attachment" :time 0.25}
              {:type :draw-order :id "draw-order" :time 0.5}]
        moved (timeline/move-keys doc "move" refs 0.1 {})]
    (is (contains? (set (timeline/all-key-refs moved "move")) {:type :slot :id "a" :property "attachment" :time 0.35}))
    (is (contains? (set (timeline/all-key-refs moved "move")) {:type :draw-order :id "draw-order" :time 0.6}))))

;; Editing a color value on an imported multi-channel Bezier key must not
;; truncate its untouched RGB/alpha curves to the first scalar channel.
(deftest color-value-edit-preserves-curve-test
  (let [curve [0.25 0.0 0.75 1.0 0.25 1.0 0.75 1.0 0.25 0.0 0.75 0.0 0.25 0.2 0.75 0.8]
        doc (document (assoc-in (source) ["animations" "move" "slots" "b" "rgba"]
                                [{"time" 0 "color" "00000000" "curve" curve} {"time" 1 "color" "ffffffff"}]))
        changed (slots/set-keyframe doc "move" "b" "rgba" {:time 0 :color "000000ff"})
        output (json/read-str (data/export-spine-json changed))]
    (is (= curve (get-in output ["animations" "move" "slots" "b" "rgba" 0 "curve"])))
    (is (= "000000ff" (get-in output ["animations" "move" "slots" "b" "rgba" 0 "color"])))
    (is (= [] (:warnings (slots/sample-slots changed "move" 0.5))))))
