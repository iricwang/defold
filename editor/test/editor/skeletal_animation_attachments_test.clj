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

(ns editor.skeletal-animation-attachments-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [editor.fs :as fs]
            [editor.skeletal-animation-attachments :as attachments]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-geometry :as geometry]
            [editor.skeletal-animation-io :as animation-io]
            [editor.skeletal-animation-sequence :as sequence]
            [editor.skeletal-animation-slots :as slots]
            [editor.skeletal-animation-timeline :as timeline]
            [util.coll :as coll])
  (:import [java.awt.image BufferedImage]
           [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [javax.imageio ImageIO]))

(defn- mesh-source []
  {"type" "mesh" "path" "mesh" "vertices" [-5 -5 5 -5 5 5 -5 5]
   "uvs" [0 1 1 1 1 0 0 0] "triangles" [0 1 2 0 2 3]})

(defn- source []
  {"skeleton" {"spine" "4.2.43"} "bones" [{"name" "root"}]
   "slots" [{"name" "clip" "bone" "root" "attachment" "clip"}
            {"name" "body" "bone" "root" "attachment" "mesh" "color" "ffffffff" "dark" "204060" "blend" "multiply"}
            {"name" "point" "bone" "root" "attachment" "point"}
            {"name" "hit" "bone" "root" "attachment" "box"}
            {"name" "linked" "bone" "root" "attachment" "copy"}]
   "skins" [{"name" "default" "attachments"
             {"clip" {"clip" {"type" "clipping" "vertexCount" 4 "vertices" [-4 -4 4 -4 4 4 -4 4] "end" "linked" "custom" 9}}
              "body" {"mesh" (mesh-source) "alternate" {"type" "point" "x" 2 "y" 3}}
              "point" {"point" {"type" "point" "x" 5 "y" 6 "rotation" 30 "custom" "marker"}}
              "hit" {"box" {"type" "boundingbox" "vertexCount" 3 "vertices" [1 0 -3 -3 1 1 0 3 -3 1 1 0 0 3 1]}}
              "linked" {"base" (mesh-source) "copy" {"type" "linkedmesh" "parent" "base" "skin" "default" "path" "copy" "deform" true}}}}
            {"name" "warm" "bones" ["root"] "attachments" {"point" {"point" {"type" "point" "x" 10}}}}
            {"name" "cool" "attachments" {"point" {"point" {"type" "point" "x" 20}}}}]
   "animations" {"move" {"bones" {"root" {"rotate" [{"time" 1 "value" 0}]}}}}})

(defn- document [] (data/import-spine-json (json/write-str (source))))

(defn- find-slot [slots id] (coll/first-where #(= id (:id %)) slots))

;; Every supported non-image attachment remains editable and roundtrips its
;; data, including weighted geometry, clipping end slots and unknown fields.
(deftest special-attachments-edit-and-roundtrip-test
  (let [doc (document)
        changed (data/update-slot doc "point" {:attachment {:x 17.0}})
        output (json/read-str (data/export-spine-json changed))
        sampled (slots/sample-slots changed "move" 0.5)]
    (is (= "point" (get-in (find-slot (:slots changed) "point") [:attachment :type])))
    (is (= 17.0 (get-in output ["skins" 0 "attachments" "point" "point" "x"])))
    (is (= "marker" (get-in output ["skins" 0 "attachments" "point" "point" "custom"])))
    (is (not (contains? (get-in output ["skins" 0 "attachments" "point" "point"]) "path")))
    (is (= "linked" (get-in output ["skins" 0 "attachments" "clip" "clip" "end"])))
    (is (= 9 (get-in output ["skins" 0 "attachments" "clip" "clip" "custom"])))
    (is (= 3 (count (get-in (find-slot (:slots doc) "hit") [:attachment :weights]))))
    (is (= "mesh" (get-in (find-slot (:slots sampled) "linked") [:attachment :type])))
    (is (= "/copy.png" (get-in (find-slot (:slots sampled) "linked") [:attachment :path])))
    (is (= "multiply" (:blend (find-slot (:slots sampled) "body"))))
    (is (= [(/ 32.0 255) (/ 64.0 255) (/ 96.0 255)] (:dark-color (find-slot (:slots sampled) "body"))))
    (is (= [] (:warnings sampled)))
    (is (= changed (data/read-json (data/write-json changed))))))

;; Editing alternate attachments must not change the setup assignment; renaming
;; placeholders updates all skins, while deleting a linked parent is rejected.
(deftest attachment-management-test
  (let [doc (attachments/put (document) "default" "body" "marker" {"type" "point" "x" 42 "custom" 88})
        selected (attachments/select-setup doc "body" "marker")
        renamed (attachments/rename-placeholder selected "body" "marker" "anchor")
        output (json/read-str (data/export-spine-json renamed))]
    (is (= "mesh" (get-in (find-slot (:slots doc) "body") [:attachment :name])))
    (is (= 42 (get-in (find-slot (:slots selected) "body") [:attachment :x])))
    (is (= "anchor" (get-in (find-slot (:slots renamed) "body") [:attachment :name])))
    (is (= 88 (get-in output ["skins" 0 "attachments" "body" "anchor" "custom"])))
    (is (thrown? clojure.lang.ExceptionInfo (attachments/remove-entry doc "default" "linked" "base")))
    (is (thrown? clojure.lang.ExceptionInfo (attachments/put doc "default" "linked" "base" {"type" "linkedmesh" "parent" "copy"})))))

;; Combined skins follow ordered override semantics and activate the union of
;; skin bones without removing either source skin or default fallback entries.
(deftest combined-skin-test
  (let [doc (attachments/compose-skin (document) "combined" ["warm" "cool"])
        output (json/read-str (data/export-spine-json doc))]
    (is (= "combined" (:active-skin doc)))
    (is (= ["default" "warm" "cool" "combined"] (data/skin-names doc)))
    (is (= 20 (get-in (find-slot (:slots doc) "point") [:attachment :x])))
    (is (= ["root"] (get-in output ["skins" 3 "bones"])))
    (is (= "mesh" (get-in (find-slot (:slots doc) "body") [:attachment :name])))))

;; All seven sequence modes reproduce deterministic frame indices, inherited
;; delays and the setup frame before the first key, including one-frame clips.
(deftest sequence-mode-test
  (let [value {:count 3 :start 2 :digits 3 :setup-index 1}
        expected {"hold" [0 0 0 0 0 0 0]
                  "once" [0 1 2 2 2 2 2] "loop" [0 1 2 0 1 2 0]
                  "pingpong" [0 1 2 1 0 1 2] "onceReverse" [2 1 0 0 0 0 0]
                  "loopReverse" [2 1 0 2 1 0 2] "pingpongReverse" [2 1 0 1 2 1 0]}]
    (is (= ["/image002.png" "/image003.png" "/image004.png"] (sequence/frame-paths "/image.png" value)))
    (doseq [mode sequence/modes]
      (let [frames (sequence/prepare-frames [{"time" 0.2 "mode" mode "index" 0 "delay" 0.1}])]
        (is (= 1 (sequence/sample-index value frames 0.0)))
        (is (= (get expected mode) (mapv #(sequence/sample-index value frames (+ 0.2 (* 0.1 %))) (range 7))))
        (is (= 0 (sequence/sample-index {:count 1} (sequence/prepare-frames [{"mode" mode "delay" 0.1}]) 10.0)))))
    (is (= 1 (sequence/sample-index value (sequence/prepare-frames [{"mode" "hold" "index" -1}]) 1.0)))
    (is (= [0.1 0.1] (mapv #(get % "delay") (sequence/prepare-frames [{"delay" 0.1 "mode" "loop"} {"time" 1 "mode" "once"}]))))))

;; Sequence authoring must preview the numbered images and survive batch copy,
;; move and deletion without losing its attachment or custom frame fields.
(deftest sequence-authoring-test
  (let [doc (-> (document)
                (attachments/put "default" "body" "mesh" {"sequence" {"count" 3 "start" 2 "digits" 3 "setup" 1}})
                (attachments/set-sequence-keyframe "move" "default" "body" "mesh" {:time 0.2 :mode "loop" :index 0 :delay 0.1}))
        ref {:type :sequence :id (pr-str ["default" "body" "mesh"]) :time 0.2}
        shifted (timeline/move-keys doc "move" [ref] 0.2 {})]
    (is (= "/mesh003.png" (get-in (find-slot (:slots (slots/sample-slots doc "move" 0.0)) "body") [:attachment :path])))
    (is (= "/mesh002.png" (get-in (find-slot (:slots (slots/sample-slots doc "move" 0.5)) "body") [:attachment :path])))
    (is (= [0.4] (mapv :time (attachments/sequence-keys shifted "move" "default" "body" "mesh"))))
    (is (= [] (attachments/sequence-keys (timeline/delete-keys shifted "move" [(assoc ref :time 0.4)]) "move" "default" "body" "mesh")))))

;; Both two-color formats independently interpolate light and dark channels,
;; and their authored frames remain valid after export/import.
(deftest two-color-key-test
  (doseq [[property start end] [["rgba2" "00000000" "ffffffff"] ["rgb2" "000000" "ffffff"]]]
    (let [doc (-> (document)
                  (slots/set-keyframe "move" "body" property {:time 0 :color start :dark-color "000000" :interpolation "linear"})
                  (slots/set-keyframe "move" "body" property {:time 1 :color end :dark-color "ffffff" :interpolation "linear"}))
          sample (find-slot (:slots (slots/sample-slots doc "move" 0.5)) "body")
          restored (data/import-spine-json (data/export-spine-json doc))]
      (is (= [0.5 0.5 0.5] (:dark-color sample)))
      (is (= [0.5 0.5 0.5 (if (= "rgba2" property) 0.5 1.0)] (:color sample)))
      (is (= sample (find-slot (:slots (slots/sample-slots restored "move" 0.5)) "body"))))))

;; Sequence resource import copies every numbered PNG and keeps the base path;
;; Defold export lists all frames, never requiring a nonexistent base image.
(deftest sequence-resource-pipeline-test
  (let [directory (fs/create-temp-directory! "spine-sequence-assets")]
    (try
      (let [origin (doto (io/file directory "origin") .mkdirs)
            project (doto (io/file directory "project") .mkdirs)
            source {"skeleton" {"spine" "4.2.43"} "bones" [{"name" "root"}]
                    "slots" [{"name" "body" "bone" "root" "attachment" "frame" "blend" "additive" "dark" "203040"}]
                    "skins" [{"name" "default" "attachments" {"body" {"frame" {"path" "frame" "sequence" {"count" 3 "start" 1 "digits" 2}}}}}]
                    "animations" {"move" {"bones" {"root" {"rotate" [{"time" 1 "value" 0}]}}}}}]
        (doseq [name ["frame01.png" "frame02.png" "frame03.png"]]
          (ImageIO/write (BufferedImage. 8 8 BufferedImage/TYPE_INT_ARGB) "png" (io/file origin name)))
        (let [imported (animation-io/read-spine-source! (io/file origin "hero.json") project source)
              doc (:document imported)
              output (animation-io/export-project! doc project "out")
              atlas (slurp ^File (get-in output [:files 1]))
              exported (json/read-str (slurp ^File (get-in output [:files 0])))]
          (is (= 3 (count (:files imported))))
          (is (.contains (slurp ^File (get-in output [:files 3])) "blend_mode: BLEND_MODE_INHERIT"))
          (is (coll/any? #(.contains ^String % "Two-color") (:warnings output)))
          (is (coll/every? #(.isFile ^File %) (:files imported)))
          (is (= "hero_images/frame" (get-in exported ["skins" 0 "attachments" "body" "frame" "path"])))
          (doseq [frame ["frame01" "frame02" "frame03"]] (is (.contains atlas frame)))
          (is (= 3 (count (re-seq #"animations \{" atlas))))))
      (finally (fs/delete-directory! directory {:fail :silently})))))

;; Removing an unkeyed sequence is a no-op; invalid authoring input fails at
;; the API boundary, and placeholder rename must not synthesize null slot maps.
(deftest attachment-authoring-boundaries-test
  (let [doc (document)
        renamed (attachments/rename-placeholder doc "body" "mesh" "surface")
        exported (json/read-str (data/export-spine-json renamed))
        cleared (data/update-slot doc "body" {:dark-color nil :blend nil})
        output (json/read-str (data/export-spine-json cleared))]
    (is (= doc (attachments/remove-sequence-keyframe doc "move" "default" "body" "mesh" 1.0)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (attachments/set-sequence-keyframe doc "move" "default" "body" "mesh" {:index 0})))
    (is (not (contains? (get-in exported ["skins" 1 "attachments"]) "body")))
    (is (not (contains? (get-in output ["slots" 1]) "dark")))
    (is (not (contains? (get-in output ["slots" 1]) "blend")))))

;; Import archives event defaults and keyframe audio overrides in the project,
;; preserving volume/balance and reporting missing dependencies on both exports.
(deftest audio-resource-pipeline-test
  (let [directory (fs/create-temp-directory! "spine-audio-assets")]
    (try
      (let [origin (doto (io/file directory "origin") .mkdirs)
            audio (doto (io/file origin "audio") .mkdirs)
            project (doto (io/file directory "project") .mkdirs)
            source {"skeleton" {"spine" "4.2.43" "audio" "audio"}
                    "bones" [{"name" "root"}]
                    "slots" [{"name" "body" "bone" "root" "attachment" "body"}]
                    "skins" [{"name" "default" "attachments" {"body" {"body" {}}}}]
                    "events" {"hit" {"audio" "hit.wav" "volume" 0.6 "balance" -0.2}
                              "missing" {"audio" "missing.wav"}}
                    "animations" {"hit" {"events" [{"time" 0.2 "name" "hit" "audio" "override.wav" "volume" 0.4 "balance" 0.3}]}}}]
        (ImageIO/write (BufferedImage. 8 8 BufferedImage/TYPE_INT_ARGB) "png" (io/file origin "body.png"))
        (spit (io/file audio "hit.wav") "default-audio-bytes")
        (spit (io/file audio "override.wav") "override-audio-bytes")
        (let [imported (animation-io/read-spine-source! (io/file origin "hero.json") project source)
              output (animation-io/export-project! (:document imported) project "out")
              exported (json/read-str (slurp ^File (get-in output [:files 0])))]
          (is (= 3 (count (:files imported))))
          (is (= "default-audio-bytes" (slurp (io/file project "hero_audio/hit.wav"))))
          (is (= "override-audio-bytes" (slurp (io/file project "hero_audio/override.wav"))))
          (is (= "" (get-in exported ["skeleton" "audio"])))
          (is (= {"audio" "hero_audio/hit.wav" "volume" 0.6 "balance" -0.2}
                 (get-in exported ["events" "hit"])))
          (is (= {"time" 0.2 "name" "hit" "audio" "hero_audio/override.wav" "volume" 0.4 "balance" 0.3}
                 (get-in exported ["animations" "hit" "events" 0])))
          (is (coll/any? #(.contains ^String % "missing.wav") (:warnings imported)))
          (is (coll/any? #(.contains ^String % "missing.wav") (:warnings output))))
        (is (thrown? clojure.lang.ExceptionInfo
                     (animation-io/read-spine-source! (io/file origin "hero.json") project
                                                      (assoc-in source ["events" "hit" "audio"] "../outside.wav"))))
        (spit (io/file origin "outside.wav") "outside")
        (Files/createSymbolicLink (.toPath (io/file audio "link.wav"))
                                  (.toPath (io/file origin "outside.wav")) (make-array FileAttribute 0))
        (is (thrown? clojure.lang.ExceptionInfo
                     (animation-io/read-spine-source! (io/file origin "hero.json") project
                                                      (assoc-in source ["events" "hit" "audio"] "link.wav")))))
      (finally (fs/delete-directory! directory {:fail :silently})))))

;; Spine 4.2 uses timelines for linked mesh inheritance. A linked mesh owns its
;; image sequence but may receive its parent's deform and sequence keys.
(deftest linked-mesh-timeline-inheritance-test
  (let [base (-> (source)
                 (assoc-in ["skins" 0 "attachments" "linked" "base" "sequence"] {"count" 3 "digits" 2})
                 (assoc-in ["skins" 0 "attachments" "linked" "copy" "sequence"] {"count" 3 "digits" 2})
                 (assoc-in ["skins" 0 "attachments" "linked" "copy" "timelines"] true)
                 (assoc-in ["animations" "move" "attachments" "default" "linked" "base" "sequence"]
                           [{"mode" "hold" "index" 2}]))
        inherited (data/import-spine-json (json/write-str base))
        independent (data/import-spine-json (json/write-str (assoc-in base ["skins" 0 "attachments" "linked" "copy" "timelines"] false)))
        unsequenced (data/import-spine-json (json/write-str (update-in base ["skins" 0 "attachments" "linked" "copy"] dissoc "sequence")))
        output (json/read-str (data/export-spine-json independent))
        attachment (fn preview-attachment [doc] (:attachment (find-slot (:slots (slots/sample-slots doc "move" 0.5)) "linked")))]
    (is (= ["default" "base"] (:preview-timeline-key (find-slot (:slots (slots/sample-slots inherited "move" 0.5)) "linked"))))
    (is (= ["default" "copy"] (:preview-timeline-key (find-slot (:slots (slots/sample-slots independent "move" 0.5)) "linked"))))
    (is (= "/copy03.png" (:path (attachment inherited))))
    (is (= "/copy01.png" (:path (attachment independent))))
    (is (= "/copy.png" (:path (attachment unsequenced))))
    (is (false? (get-in output ["skins" 0 "attachments" "linked" "copy" "timelines"])))
    (is (not (contains? (get-in output ["skins" 0 "attachments" "linked" "copy"]) "deform")))))

;; A native Save As destination must never appear when its parent cannot accept
;; a file. Existing siblings remain intact and a later valid retry succeeds.
(deftest native-save-failed-parent-test
  (let [directory (fs/create-temp-directory! "spine-save-failure")]
    (try
      (let [parent (io/file directory "not-a-directory")
            target (io/file parent "failed.spine")
            retry (io/file directory "valid.spine")]
        (spit parent "existing-sibling")
        (is (thrown? Exception
                     (animation-io/write-independent-project! (data/new-document "test") directory target)))
        (is (not (.exists target)))
        (is (= "existing-sibling" (slurp parent)))
        (is (= retry (animation-io/write-independent-project! (data/new-document "test") directory retry)))
        (is (.isFile retry)))
      (finally (fs/delete-directory! directory {:fail :silently})))))

;; Removing a combined skin cleans its private timelines while retaining source
;; skins, selecting a valid fallback, and surviving save/reopen.
(deftest remove-combined-skin-test
  (let [base (-> (document)
                 (attachments/put "default" "body" "mesh" {"sequence" {"count" 2}})
                 (attachments/set-sequence-keyframe "move" "default" "body" "mesh" {:time 0.25 :mode "loop" :delay 0.1 :index 0}))
        combined (attachments/compose-skin base "combined" ["default" "warm"])
        removed (attachments/remove-skin combined "combined")
        output (json/read-str (data/export-spine-json removed))
        reopened (data/read-json (data/write-json removed))]
    (is (= "default" (:active-skin removed)))
    (is (= ["default" "warm" "cool"] (data/skin-names removed)))
    (is (nil? (get-in output ["animations" "move" "attachments" "combined"])))
    (is (= [0.25] (mapv :time (attachments/sequence-keys removed "move" "default" "body" "mesh"))))
    (is (= (data/skin-names removed) (data/skin-names reopened)))
    (is (= (:slots removed) (:slots reopened)))
    (is (= "warm" (:active-skin (attachments/remove-skin (data/select-skin combined "warm") "combined"))))))

;; Skin removal must not silently break a linked mesh or delete default/the
;; final skin; rejected edits leave the caller's original document available.
(deftest remove-skin-reference-protection-test
  (let [source (-> (source)
                   (assoc-in ["skins" 1 "attachments" "body" "mesh"] (mesh-source))
                   (assoc-in ["skins" 2 "attachments" "body" "mesh"] {"type" "linkedmesh" "skin" "warm" "parent" "mesh"}))
        doc (data/import-spine-json (json/write-str source))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"链接网格" (attachments/remove-skin doc "warm")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"默认" (attachments/remove-skin doc "default")))
    (is (thrown? clojure.lang.ExceptionInfo (attachments/remove-skin doc "missing"))))
  (let [doc (data/import-spine-json (json/write-str {"skeleton" {"spine" "4.2.43"} "bones" [{"name" "root"}] "skins" [{"name" "only" "attachments" {}}]}))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"至少" (attachments/remove-skin doc "only")))))

;; Grid recovery must reconstruct the texture placement, including reflections,
;; and produce a clean region that survives a Spine export/import round trip.
(deftest restore-grid-image-placement-test
  (let [image {:type "region" :path "/body.png" :width 400.0 :height 200.0
               :x 12.5 :y -8.0 :rotation 27.0 :scale-x -0.6 :scale-y 0.9}
        grid (geometry/region-to-mesh image 3 3)
        doc (data/add-slot (data/new-document "grid") {:id "body" :bone "root" :attachment grid})
        restored (attachments/restore-image doc "default" "body" "body")
        region (get-in restored [:slots 0 :attachment])
        points (fn image-points [image]
                 (mapv #(geometry/transform-point (geometry/local-transform image) %)
                       [[-200.0 -100.0] [200.0 -100.0] [200.0 100.0] [-200.0 100.0]]))]
    (is (= "region" (:type region)))
    (is (= "/body.png" (:path region)))
    (is (coll/every? #(< (abs %) 1.0e-6) (mapv - (coll/into-> (points image) [] cat) (coll/into-> (points region) [] cat))))
    (is (coll/not-any? #(contains? region %) [:vertices :weights :uvs :triangles]))
    (is (= region (get-in (data/import-spine-json (data/export-spine-json restored)) [:slots 0 :attachment])))))

;; Restoring a weighted parent mesh must detach linked descendants and retain
;; their inherited FFD, while removing only the converted attachment's FFD.
(deftest restore-weighted-linked-images-test
  (let [raw (assoc (mesh-source) "width" 10 "height" 10
                   "vertices" (coll/into-> [[-5 -5] [5 -5] [5 5] [-5 5]] [] (mapcat (fn [[x y]] [1 0 x y 1]))))
        frames [{"time" 0 "vertices" []} {"time" 1 "vertices" [1 2 1 2 1 2 1 2]}]
        source (-> (source)
                   (assoc-in ["skins" 0 "attachments" "body" "mesh"] raw)
                   (assoc-in ["skins" 0 "attachments" "body" "copy"] {"type" "linkedmesh" "parent" "mesh" "path" "copy" "width" 10 "height" 10})
                   (assoc-in ["skins" 0 "attachments" "body" "grandchild"] {"type" "linkedmesh" "parent" "copy" "path" "grandchild" "width" 10 "height" 10})
                   (assoc-in ["animations" "deform" "attachments" "default" "body" "mesh" "deform"] frames))
        doc (data/import-spine-json (json/write-str source))
        before (json/read-str (data/export-spine-json doc))
        restored (attachments/restore-image doc "default" "body" "mesh")
        exported (json/read-str (data/export-spine-json restored))
        child-only (json/read-str (data/export-spine-json (attachments/restore-image doc "default" "body" "copy")))]
    (is (= "region" (get-in exported ["skins" 0 "attachments" "body" "mesh" "type"])))
    (is (nil? (get-in exported ["animations" "deform" "attachments" "default" "body" "mesh" "deform"])))
    (doseq [child ["copy" "grandchild"]]
      (is (= "mesh" (get-in exported ["skins" 0 "attachments" "body" child "type"])))
      (is (= child (get-in exported ["skins" 0 "attachments" "body" child "path"])))
      (is (= (get-in before ["animations" "deform" "attachments" "default" "body" "mesh" "deform"])
             (get-in exported ["animations" "deform" "attachments" "default" "body" child "deform"]))))
    (is (= (get-in before ["animations" "deform" "attachments" "default" "body" "mesh"])
           (get-in child-only ["animations" "deform" "attachments" "default" "body" "mesh"])))
    (is (= "region" (get-in child-only ["skins" 0 "attachments" "body" "copy" "type"])))
    (is (= "mesh" (get-in child-only ["skins" 0 "attachments" "body" "grandchild" "type"])))
    (is (= (:bones doc) (:bones restored)))
    (is (some? (data/validate-document (data/import-spine-json (json/write-str exported)))))))
