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

(ns editor.skeletal-animation-data-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-data :as data]))

(defn- near? [a b] (< (Math/abs (- (double a) (double b))) 1.0e-7))

(defn- mesh-document []
  (-> (data/new-document "test")
      (data/add-bone {:id "joint" :parent "root"})
      (data/add-bone {:id "tip" :parent "joint" :x 100.0})
      (data/add-bone {:id "target" :parent "root" :x 150.0 :y 100.0})
      (data/add-slot {:id "image" :bone "root"
                      :attachment {:type "mesh" :name "image" :path "/images/body.png"
                                   :vertices [0.0 0.0 100.0 0.0 0.0 100.0]
                                   :uvs [0.0 1.0 1.0 1.0 0.0 0.0] :triangles [0 1 2]}})))

;; Documents round-trip without dropping first-party extensions or Unicode names.
(deftest document-roundtrip-test
  (let [doc (assoc (data/new-document "骨骼") :custom {:author "Alice"})]
    (is (= doc (data/read-json (data/write-json doc))))
    (is (= "{{NAME}}" (:name (data/new-document "{{NAME}}"))))))

;; Invalid values and cyclic/missing references fail at the document boundary.
(deftest validation-test
  (let [doc (data/new-document "test")]
    (doseq [bad [(assoc-in doc [:bones 0 :x] ##NaN)
                 (assoc-in doc [:bones 0 :length] -1)
                 (assoc-in doc [:bones 0 :parent] "root")
                 (assoc-in doc [:bones 0 :parent] "missing")
                 (update doc :bones conj (first (:bones doc)))
                 (assoc doc :schema "other")]]
      (is (thrown? clojure.lang.ExceptionInfo (data/validate-document bad))))
    (is (thrown? clojure.lang.ExceptionInfo (data/add-slot doc {:id "x" :bone "root" :attachment {:path "/../a.png"}})))
    (is (thrown? clojure.lang.ExceptionInfo (data/add-slot doc {:id "x" :bone "missing"})))
    (is (thrown? clojure.lang.ExceptionInfo (data/sample-pose doc "default" ##Inf))))
  (let [doc (-> (data/new-document "test") (data/add-bone {:id "child" :parent "root"}))]
    (is (thrown? clojure.lang.ExceptionInfo (data/update-bone doc "root" {:parent "child"})))))

;; Rename propagates references; deletion removes descendants, slots, IK and keys.
(deftest bone-lifecycle-test
  (let [doc (-> (mesh-document)
                (data/add-bone {:id "finger" :parent "tip"})
                (data/add-slot {:id "finger-slot" :bone "finger"})
                (data/add-ik {:id "reach" :bones ["joint" "tip"] :target "target"})
                (data/set-keyframe "default" "tip" "rotation" 0.0 15.0 "linear")
                (data/update-bone "tip" {:id "hand"}))]
    (is (= "hand" (:parent (last (:bones doc)))))
    (is (= "hand" (get-in doc [:animations 0 :tracks 0 :bone])))
    (is (= ["joint" "hand"] (get-in doc [:ik 0 :bones])))
    (let [removed (data/remove-bone doc "hand")]
      (is (= ["root" "joint" "target"] (mapv :id (:bones removed))))
      (is (= ["image"] (mapv :id (:slots removed))))
      (is (= [] (:ik removed)))
      (is (= [] (get-in removed [:animations 0 :tracks])))
      (is (= removed (data/validate-document removed))))))

;; Sampling is absolute, preserves setup before the first key and honors stepped interpolation.
(deftest keyframe-sampling-test
  (let [doc (-> (data/new-document "test")
                (data/update-bone "root" {:x 5.0})
                (data/set-keyframe "default" "root" "x" 0.2 10.0 "linear")
                (data/set-keyframe "default" "root" "x" 1.0 30.0 "linear"))]
    (is (= 5.0 (:x (first (data/sample-pose doc "default" 0.0)))))
    (is (near? 20.0 (:x (first (data/sample-pose doc "default" 0.6)))))
    (is (= 30.0 (:x (first (data/sample-pose doc "default" 2.0)))))
    (is (= 10.0 (:x (first (data/sample-pose (data/set-keyframe doc "default" "root" "x" 0.2 10.0 "stepped") "default" 0.6)))))
    (is (= 1 (count (get-in (data/remove-keyframe doc "default" "root" "x" 0.2) [:animations 0 :tracks 0 :keys]))))
    (is (thrown? clojure.lang.ExceptionInfo (data/update-animation doc "default" {:duration 0.5})))))

;; Bezier time inversion matches an ease curve and survives Spine relative-value conversion.
(deftest bezier-roundtrip-test
  (let [doc (-> (data/new-document "test")
                (data/update-bone "root" {:x 50.0})
                (data/set-keyframe "default" "root" "x" 1.0 150.0 "linear")
                (data/set-keyframe "default" "root" "x" 0.0 50.0 {:type "bezier" :curve [0.25 50.0 0.75 150.0]}))
        imported (data/import-spine-json (data/export-spine-json doc))]
    (doseq [time [0.0 0.1 0.5 0.8 1.0]]
      (is (near? (:x (first (data/sample-pose doc "default" time)))
                 (:x (first (data/sample-pose imported "default" time))))))
    (is (near? 100.0 (:x (first (data/sample-pose imported "default" 0.5)))))))

;; Parent transforms work with unsorted bones, negative scale and independent hierarchy branches.
(deftest world-transform-test
  (let [bones (:bones (-> (data/new-document "test")
                          (data/update-bone "root" {:x 10.0 :y 20.0 :rotation 90.0 :scale-x -2.0})
                          (data/add-bone {:id "child" :parent "root" :x 5.0})))
        world (data/world-transforms (vec (reverse bones)))
        [x y] (data/transform-point (get world "child") [0.0 0.0])]
    (is (near? 10.0 x))
    (is (near? 10.0 y))
    (is (thrown? clojure.lang.ExceptionInfo (data/world-transforms (assoc-in bones [0 :parent] "child"))))))

;; A real Spine 4.2 attachments/skin/slot/name/deform timeline maps to editable sparse mesh offsets.
(deftest spine-mesh-deform-roundtrip-test
  (let [doc (-> (mesh-document)
                (data/set-deform-keyframe "default" "image" 0.0 [0.0 0.0 0.0 0.0 0.0 0.0] "linear")
                (data/set-deform-keyframe "default" "image" 1.0 [0.0 0.0 20.0 10.0 0.0 0.0] "linear"))
        output (json/read-str (data/export-spine-json doc))
        imported (data/import-spine-json (json/write-str output))]
    (is (= [0.0 0.0 10.0 5.0 0.0 0.0] (get (data/sample-deforms imported "default" 0.5) "image")))
    (is (= 2 (count (get-in output ["animations" "default" "attachments" "default" "image" "image" "deform"]))))
    (is (= :missing (get-in output ["animations" "default" "deform"] :missing)))
    (let [sparse (assoc-in output ["animations" "default" "attachments" "default" "image" "image" "deform"]
                           [{"offset" 2 "vertices" [4 6]} {"time" 1 "vertices" []}])]
      (is (= [0.0 0.0 4.0 6.0 0.0 0.0] (get (data/sample-deforms (data/import-spine-json (json/write-str sparse)) "default" 0.0) "image"))))))

;; Weighted meshes use per-influence offsets; importing and exporting cannot flatten skinning.
(deftest weighted-mesh-roundtrip-test
  (let [weights [[{:bone "root" :x 0.0 :y 0.0 :weight 1.0}]
                 [{:bone "root" :x 100.0 :y 0.0 :weight 0.5} {:bone "tip" :x 0.0 :y 0.0 :weight 0.5}]
                 [{:bone "root" :x 0.0 :y 100.0 :weight 1.0}]]
        doc (-> (mesh-document)
                (data/update-slot "image" {:attachment {:weights weights}})
                (data/set-deform-keyframe "default" "image" 0.0 [1 2 3 4 5 6 7 8] "linear"))
        imported (data/import-spine-json (data/export-spine-json doc))]
    (is (= weights (get-in imported [:slots 0 :attachment :weights])))
    (is (= [1 2 3 4 5 6 7 8] (get (data/sample-deforms imported "default" 0.0) "image")))
    (is (thrown? clojure.lang.ExceptionInfo (data/remove-bone doc "tip")))
    (is (thrown? clojure.lang.ExceptionInfo (data/set-deform-keyframe doc "default" "image" 0.0 [1 2 3 4 5 6] "linear")))))

;; Unsupported features remain byte-equivalent as parsed data, with an explicit preview warning.
(deftest unsupported-content-preserved-test
  (let [source (-> (json/read-str (data/export-spine-json (mesh-document)))
                   (assoc "events" {"footstep" {"audio" "step.wav"}})
                   (assoc "physics" [{"name" "spring" "bone" "tip" "mass" 2}])
                   (assoc-in ["animations" "default" "events"] [{"time" 0.5 "name" "footstep"}])
                   (assoc-in ["animations" "default" "bones" "root" "shear"] [{"time" 0.0 "x" 2 "y" 3}])
                   (update "skins" conj {"name" "alternate" "attachments" {"image" {"other" {"width" 32 "height" 32}}}}))
        imported (data/import-spine-json (json/write-str source))
        output (json/read-str (data/export-spine-json (data/update-bone imported "root" {:x 20.0}))) ]
    (is (pos? (count (:warnings imported))))
    (doseq [path [["events"] ["physics"] ["animations" "default" "events"] ["animations" "default" "bones" "root" "shear"] ["skins" 1]]]
      (is (= (get-in source path) (get-in output path))))
    (is (= 20.0 (get-in output ["bones" 0 "x"])))
    (is (thrown? clojure.lang.ExceptionInfo (data/import-spine-json (json/write-str (assoc-in source ["skeleton" "spine"] "3.8.0")))))))

;; IK setup and animation mix survive roundtrip and invalid target references are rejected.
(deftest ik-roundtrip-test
  (let [doc (data/add-ik (mesh-document) {:id "reach" :bones ["joint" "tip"] :target "target"})
        source (assoc-in (json/read-str (data/export-spine-json doc)) ["animations" "default" "ik" "reach"]
                         [{"time" 0.0 "mix" 0.0 "bendPositive" false} {"time" 1.0 "mix" 1.0}])
        imported (data/import-spine-json (json/write-str source))]
    (is (= 0.5 (:mix (first (data/sample-ik imported "default" 0.5)))))
    (is (false? (:bend-positive (first (data/sample-ik imported "default" 0.5)))))
    (is (= true (:bend-positive (first (data/sample-ik imported "default" 1.0)))))
    (is (= (:ik imported) (:ik (data/import-spine-json (data/export-spine-json imported)))))
    (is (thrown? clojure.lang.ExceptionInfo (data/update-ik doc "reach" {:target "missing"})))))

;; Weight painting reorders per-influence FFD offsets instead of corrupting animated meshes.
(deftest weight-edit-remaps-deform-test
  (let [doc (data/set-deform-keyframe (mesh-document) "default" "image" 0.0 [1 2 3 4 5 6] "linear")
        weights [[{:bone "root" :x 0 :y 0 :weight 1.0}]
                 [{:bone "tip" :x 0 :y 0 :weight 0.5} {:bone "root" :x 100 :y 0 :weight 0.5}]
                 [{:bone "root" :x 0 :y 100 :weight 1.0}]]
        bound (data/update-slot doc "image" {:attachment {:weights weights}})]
    (is (= [1 2 0.0 0.0 3 4 5 6] (get (data/sample-deforms bound "default" 0) "image")))
    (let [reordered (data/update-slot bound "image" {:attachment {:weights (update weights 1 #(vec (reverse %)))}})]
      (is (= [1 2 3 4 0.0 0.0 5 6] (get (data/sample-deforms reordered "default" 0) "image"))))
    (is (= [] (get-in (data/update-slot bound "image" {:attachment nil}) [:animations 0 :deforms])))))

;; Moving a Bezier key preserves normalized timing on either adjacent segment.
(deftest move-keyframes-test
  (let [doc (-> (data/new-document "test")
                (data/set-keyframe "default" "root" "x" 1 100.0 "linear")
                (data/set-keyframe "default" "root" "x" 0 0.0 {:type "bezier" :curve [0.25 0.0 0.75 100.0]}))
        moved (data/move-keyframe doc "default" "root" "x" 1.0 2.0)]
    (is (= [0.5 0.0 1.5 100.0] (get-in moved [:animations 0 :tracks 0 :keys 0 :curve])))
    (is (near? 50.0 (:x (first (data/sample-pose moved "default" 1.0))))))
  (let [doc (-> (mesh-document)
                (data/set-deform-keyframe "default" "image" 0 [0 0 0 0 0 0] "linear")
                (data/set-deform-keyframe "default" "image" 1 [0 0 10 0 0 0] "linear"))
        moved (data/move-deform-keyframe doc "default" "image" 1.0 2.0)]
    (is (= [0.0 0.0 5.0 0.0 0.0 0.0] (get (data/sample-deforms moved "default" 1.0) "image")))
    (is (= 1 (count (get-in (data/remove-deform-keyframe moved "default" "image" 2.0) [:animations 0 :deforms 0 :keys]))))))

;; Renaming imported data updates preserved slot/skin/constraint references before export.
(deftest imported-rename-test
  (let [source (-> (json/read-str (data/export-spine-json (data/add-ik (mesh-document) {:id "reach" :bones ["joint" "tip"] :target "target"})))
                   (assoc-in ["animations" "default" "slots" "image" "attachment"] [{"time" 0 "name" "image"}]))
        doc (-> (data/import-spine-json (json/write-str source))
                (data/update-bone "tip" {:id "hand"})
                (data/update-slot "image" {:id "sprite"})
                (data/update-animation "default" {:id "move"})
                (data/update-ik "reach" {:id "aim"}))
        output (json/read-str (data/export-spine-json doc))]
    (is (= ["joint" "hand"] (get-in output ["ik" 0 "bones"])))
    (is (= "sprite" (get-in output ["slots" 0 "name"])))
    (is (contains? (get-in output ["skins" 0 "attachments"]) "sprite"))
    (is (= [{"time" 0 "name" "image"}] (get-in output ["animations" "move" "slots" "sprite" "attachment"])))
    (is (= "aim" (get-in output ["ik" 0 "name"])))))

;; Removing supported objects cannot resurrect their original timelines in the export overlay.
(deftest imported-removal-test
  (let [doc (-> (mesh-document)
                (data/add-ik {:id "reach" :bones ["joint" "tip"] :target "target"})
                (data/set-ik-keyframe "default" "reach" 0.0 0.5 true "linear")
                (data/set-deform-keyframe "default" "image" 0.0 [0 0 1 2 0 0] "linear"))
        imported (data/import-spine-json (data/export-spine-json doc))
        removed (-> imported (data/remove-ik "reach") (data/remove-slot "image"))
        output (json/read-str (data/export-spine-json removed))]
    (is (= [] (get output "ik")))
    (is (nil? (get-in output ["animations" "default" "ik"])))
    (is (nil? (get-in output ["animations" "default" "attachments" "default" "image"])))
    (is (= {} (get-in output ["skins" 0 "attachments"])))
    (is (= [] (:ik-tracks (first (:animations (data/remove-bone imported "tip"))))))))

;; Advanced constraints block unsafe deletions instead of producing dangling preserved references.
(deftest advanced-reference-deletion-test
  (let [source (assoc (json/read-str (data/export-spine-json (mesh-document))) "transform" [{"name" "follow" "bones" ["tip"] "target" "target"}])
        doc (data/import-spine-json (json/write-str source))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"preserved transform" (data/remove-bone doc "tip")))))

;; IK animation editing extends duration and retains mix/bend; invalid animated values are rejected.
(deftest ik-key-edit-test
  (let [doc (-> (mesh-document)
                (data/add-ik {:id "reach" :bones ["joint" "tip"] :target "target"})
                (data/set-ik-keyframe "default" "reach" 0.0 0.0 false "linear")
                (data/set-ik-keyframe "default" "reach" 2.0 1.0 true "linear"))]
    (is (= 2.0 (get-in doc [:animations 0 :duration])))
    (is (= 0.5 (:mix (first (data/sample-ik doc "default" 1.0)))))
    (is (false? (:bend-positive (first (data/sample-ik doc "default" 1.0)))))
    (is (thrown? clojure.lang.ExceptionInfo (data/set-ik-keyframe doc "default" "reach" 1.0 ##NaN true "linear")))))

;; Unedited weighted attachments retain bone identities when hierarchy order changes during export.
(deftest inactive-weight-reindex-test
  (let [doc (mesh-document)
        source (-> (json/read-str (data/export-spine-json doc))
                   (assoc-in ["skins" 0 "attachments" "image" "inactive"]
                             {"type" "mesh" "path" "images/body" "width" 100 "height" 100
                              "uvs" [0 0 1 0 0 1] "triangles" [0 1 2]
                              "vertices" [1 2 0 0 1 1 2 100 0 1 1 2 0 100 1]}))
        imported (data/import-spine-json (json/write-str source))
        changed (data/update-bone imported "tip" {:parent "target"})
        output (json/read-str (data/export-spine-json changed))
        vertices (get-in output ["skins" 0 "attachments" "image" "inactive" "vertices"])]
    (is (= ["root" "joint" "target" "tip"] (mapv #(get % "name") (get output "bones"))))
    (is (= [3 3 3] (mapv #(nth vertices %) [1 6 11])))))

;; Exporting deletion of the last deform key must not restore the source animation.
(deftest deform-key-removal-roundtrip-test
  (let [doc (data/set-deform-keyframe (mesh-document) "default" "image" 0 [0 0 1 2 0 0] "linear")
        imported (data/import-spine-json (data/export-spine-json doc))
        output (json/read-str (data/export-spine-json (data/remove-deform-keyframe imported "default" "image" 0.0)))]
    (is (nil? (get-in output ["animations" "default" "attachments" "default" "image" "image" "deform"]))))
  (let [doc (data/update-slot (mesh-document) "image" {:attachment {:name "different-name"}})
        imported (data/import-spine-json (data/export-spine-json doc))
        output (json/read-str (data/export-spine-json (data/update-slot imported "image" {:attachment nil})))]
    (is (nil? (get-in output ["slots" 0 "attachment"])))))

;; Constraint order follows Spine's explicit order, independently of JSON array order.
(deftest ik-order-test
  (let [source (assoc (json/read-str (data/export-spine-json (mesh-document))) "ik"
                      [{"name" "later" "bones" ["tip"] "target" "target" "order" 3}
                       {"name" "first" "bones" ["joint"] "target" "target" "order" 0}])
        doc (data/import-spine-json (json/write-str source))
        output (json/read-str (data/export-spine-json (data/add-ik doc {:id "new" :bones ["tip"] :target "target"}))) ]
    (is (= ["first" "later"] (mapv :id (:ik doc))))
    (is (> (get-in output ["ik" 2 "order"]) 3))))

;; Every IK chain needs a parent transform, even when setup mix is zero, to avoid a native null-parent crash.
(deftest root-ik-rejected-test
  (let [doc (-> (data/new-document "test")
                (data/add-bone {:id "child" :parent "root" :x 100})
                (data/add-bone {:id "target" :parent "root" :x 150 :y 20}))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"root bone"
                          (data/add-ik doc {:id "unsafe" :bones ["root" "child"] :target "target"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"root bone"
                          (data/add-ik doc {:id "unsafe" :bones ["root"] :target "target" :mix 0.0})))))

;; Mesh malformed indices/weights and controls outside a Bezier segment fail before preview/export.
(deftest malformed-animation-values-test
  (let [doc (mesh-document)]
    (is (thrown? clojure.lang.ExceptionInfo (data/update-slot doc "image" {:attachment {:triangles [0 1 99]}})))
    (is (thrown? clojure.lang.ExceptionInfo (data/update-slot doc "image" {:attachment {:vertices [0 ##Inf 1 2 3 4]}})))
    (is (thrown? clojure.lang.ExceptionInfo (data/set-keyframe doc "default" "root" "x" 0 0 {:type "bezier" :curve [0.1 0 1.5 1]})))
    (is (thrown? clojure.lang.ExceptionInfo (data/remove-keyframe doc "default" "root" "x" ##NaN)))))

;; Unsupported IK softness channels survive overlay export beside editable mix channels.
(deftest ik-extended-frame-preservation-test
  (let [doc (data/add-ik (mesh-document) {:id "reach" :bones ["joint" "tip"] :target "target"})
        frame {"time" 0.0 "mix" 0.2 "softness" 4.0 "compress" true
               "curve" [0.25 0.2 0.75 1.0 0.25 4.0 0.75 8.0]}
        source (assoc-in (json/read-str (data/export-spine-json doc)) ["animations" "default" "ik" "reach"]
                         [frame {"time" 1.0 "mix" 1.0 "softness" 8.0}])
        imported (data/import-spine-json (json/write-str source))
        output (json/read-str (data/export-spine-json imported))]
    (is (= (assoc frame "bendPositive" true) (get-in output ["animations" "default" "ik" "reach" 0])))
    (is (pos? (count (:warnings imported))))))

;; IK key movement/removal uses the same interpolation-preserving timeline semantics as bone keys.
(deftest ik-key-movement-test
  (let [doc (-> (mesh-document)
                (data/add-ik {:id "reach" :bones ["joint" "tip"] :target "target"})
                (data/set-ik-keyframe "default" "reach" 1.0 1.0 true "linear")
                (data/set-ik-keyframe "default" "reach" 0.0 0.0 false {:type "bezier" :curve [0.25 0.0 0.75 1.0]}))
        moved (data/move-ik-keyframe doc "default" "reach" 1.0 2.0)]
    (is (= [0.5 0.0 1.5 1.0] (get-in moved [:animations 0 :ik-tracks 0 :keys 0 :curve])))
    (is (near? 0.5 (:mix (first (data/sample-ik moved "default" 1.0)))))
    (is (= 1 (count (get-in (data/remove-ik-keyframe moved "default" "reach" 2.0) [:animations 0 :ik-tracks 0 :keys]))))))

(defn- imported-animation-with-preserved-tracks []
  (let [source (json/read-str (data/export-spine-json (mesh-document)))
        source (-> source
                   (assoc "events" {"ping" {"string" "event data"}})
                   (assoc-in ["skins" 0 "attachments" "image" "alternate"] (get-in source ["skins" 0 "attachments" "image" "image"]))
                   (assoc-in ["animations" "default" "events"] [{"time" 0.4 "name" "ping"}])
                   (assoc-in ["animations" "default" "slots"] {"image" {"attachment" [{"time" 0.3 "name" "alternate"}]}})
                   (assoc-in ["animations" "default" "drawOrder"] [{"time" 0.5}])
                   (assoc-in ["animations" "default" "bones" "root" "shear"] [{"time" 0.6 "x" 10.0 "y" 20.0}])
                   (assoc-in ["animations" "default" "attachments" "default" "image" "alternate" "deform"]
                             [{"time" 0.7 "vertices" [1.0 2.0 3.0 4.0 5.0 6.0]}]))]
    (data/import-spine-json (json/write-str source))))

;; Duplicating an imported clip preserves events, slot switches, draw order,
;; unsupported bone channels and inactive-attachment FFD alongside editable keys.
(deftest copy-imported-animation-test
  (let [doc (imported-animation-with-preserved-tracks)
        copied (data/copy-animation doc "default" "duplicate")
        output (json/read-str (data/export-spine-json copied))
        source (json/read-str (:spine-source copied))]
    (is (= (get-in output ["animations" "default"]) (get-in output ["animations" "duplicate"])))
    (is (= (get-in source ["animations" "default"]) (get-in source ["animations" "duplicate"])))
    (is (= (assoc (first (:animations doc)) :id "duplicate") (second (:animations copied))))
    (is (= (:warnings doc) (:warnings copied)))
    (is (thrown? clojure.lang.ExceptionInfo (data/copy-animation doc "default" "default")))
    (is (thrown? clojure.lang.ExceptionInfo (data/copy-animation doc "missing" "duplicate")))
    (let [local (data/copy-animation (data/new-document "local") "default" "duplicate")]
      (is (= ["default" "duplicate"] (mapv :id (:animations local))))
      (is (not (contains? local :spine-source))))))

;; Delete and rename remove the old raw ID, so reusing an imported animation's
;; name never resurrects its events or other preserved timeline data.
(deftest imported-animation-name-reuse-test
  (let [doc (imported-animation-with-preserved-tracks)
        removed (data/remove-animation doc "default")
        recreated (data/add-animation removed {:id "default"})
        renamed (data/update-animation doc "default" {:id "renamed"})
        reused (data/add-animation renamed {:id "default"})
        output (json/read-str (data/export-spine-json recreated))
        renamed-output (json/read-str (data/export-spine-json reused))]
    (is (not (contains? (get (json/read-str (:spine-source removed)) "animations") "default")))
    (is (not (contains? (get (json/read-str (:spine-source renamed)) "animations") "default")))
    (doseq [clean-output [output renamed-output]
            section ["events" "slots" "drawOrder" "attachments"]]
      (is (nil? (get-in clean-output ["animations" "default" section]))))
    (is (= [{"time" 0.4 "name" "ping"}] (get-in renamed-output ["animations" "renamed" "events"])))
    (is (nil? (get-in renamed-output ["animations" "default" "bones" "root" "shear"])))
    (is (thrown? clojure.lang.ExceptionInfo (data/update-animation reused "renamed" {:id "default"}))))
  (let [doc (imported-animation-with-preserved-tracks)
        legacy (assoc doc :animations [])
        added (data/add-animation legacy {:id "default"})
        renamed (-> (data/add-animation legacy {:id "fresh"})
                    (data/update-animation "fresh" {:id "default"}))]
    (doseq [clean [added renamed]]
      (is (nil? (get-in (json/read-str (data/export-spine-json clean)) ["animations" "default" "events"]))))))

;; Moving an imported IK key carries its unsupported fields and retimes the
;; softness Bezier channel. Replacements retain metadata; newly inserted keys
;; after deletion must never inherit a removed source frame at the same time.
(deftest moved-ik-frame-preservation-test
  (let [doc (data/add-ik (mesh-document) {:id "reach" :bones ["joint" "tip"] :target "target"})
        source (assoc-in (json/read-str (data/export-spine-json doc)) ["animations" "default" "ik" "reach"]
                         [{"time" 0 "mix" 0.2 "softness" 4.0 "compress" true
                           "curve" [0.25 0.2 0.75 1.0 0.25 4.0 0.75 8.0]}
                          {"time" 1 "mix" 1.0 "softness" 8.0 "stretch" true}])
        imported (data/import-spine-json (json/write-str source))
        moved (-> imported
                  (data/move-ik-keyframe "default" "reach" 1.0 2.0)
                  (data/move-ik-keyframe "default" "reach" 0.0 0.5))
        frames (get-in (json/read-str (data/export-spine-json moved)) ["animations" "default" "ik" "reach"])
        curve (get-in moved [:animations 0 :ik-tracks 0 :keys 0 :curve])
        replaced (data/set-ik-keyframe moved "default" "reach" 0.5 0.4 false {:type "bezier" :curve curve})
        replaced-frame (get-in (json/read-str (data/export-spine-json replaced)) ["animations" "default" "ik" "reach" 0])
        recreated (-> imported
                      (data/remove-ik-keyframe "default" "reach" 0.0)
                      (data/set-ik-keyframe "default" "reach" 0.0 0.5 false "linear"))
        recreated-frame (get-in (json/read-str (data/export-spine-json recreated)) ["animations" "default" "ik" "reach" 0])]
    (is (= [0.5 2.0] (mapv #(get % "time") frames)))
    (is (= [4.0 8.0] (mapv #(get % "softness") frames)))
    (is (= [0.875 0.2 1.625 1.0 0.875 4.0 1.625 8.0] (get (first frames) "curve")))
    (is (true? (get (first frames) "compress")))
    (is (true? (get (second frames) "stretch")))
    (is (= 4.0 (get replaced-frame "softness")))
    (is (= 0.4 (get replaced-frame "mix")))
    (is (false? (get replaced-frame "bendPositive")))
    (is (= (get (first frames) "curve") (get replaced-frame "curve")))
    (is (= {"time" 0.0 "mix" 0.5 "bendPositive" false} recreated-frame))
    (is (= moved (data/read-json (data/write-json moved))))
    (let [collision (data/move-ik-keyframe moved "default" "reach" 0.5 2.0)
          frame (get-in (json/read-str (data/export-spine-json collision)) ["animations" "default" "ik" "reach" 0])]
      (is (= 1 (count (get-in collision [:animations 0 :ik-tracks 0 :keys]))))
      (is (= 4.0 (get frame "softness")))
      (is (nil? (get frame "stretch")))))
  (let [doc (data/add-ik (mesh-document) {:id "reach" :bones ["joint" "tip"] :target "target"})
        source (assoc-in (json/read-str (data/export-spine-json doc)) ["animations" "default" "ik" "reach"]
                         [{"time" 0 "mix" 0.2 "softness" 4.0} {"time" 1 "mix" 1.0 "softness" 8.0}])
        legacy (update-in (data/import-spine-json (json/write-str source)) [:animations 0 :ik-tracks 0 :keys]
                          #(mapv (fn [key] (dissoc key :spine-frame-source)) %))
        moved (data/move-ik-keyframe legacy "default" "reach" 1.0 2.0)
        frames (get-in (json/read-str (data/export-spine-json moved)) ["animations" "default" "ik" "reach"])]
    (is (= [4.0 8.0] (mapv #(get % "softness") frames)))
    (is (= [0 2.0] (mapv #(get % "time") frames)))))

;; An imported IK frame can contain a second Bezier channel for softness.
;; Changing mix/bend/first-channel handles preserves it, while replacing its
;; interpolation type must fail explicitly instead of silently losing data.
(deftest ik-softness-interpolation-protection-test
  (let [doc (data/add-ik (mesh-document) {:id "reach" :bones ["joint" "tip"] :target "target"})
        source (assoc-in (json/read-str (data/export-spine-json doc)) ["animations" "default" "ik" "reach"]
                         [{"time" 0 "mix" 0.2 "softness" 4.0
                           "curve" [0.25 0.2 0.75 1.0 0.25 4.0 0.75 8.0]}
                          {"time" 1 "mix" 1.0 "softness" 8.0}])
        imported (data/import-spine-json (json/write-str source))
        legacy (update-in imported [:animations 0 :ik-tracks 0 :keys]
                          #(mapv (fn [key] (dissoc key :spine-frame-source)) %))]
    (doseq [document [imported legacy]
            interpolation ["linear" "stepped" {:type "linear"} {:type "stepped"}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"preserved softness Bezier"
                            (data/set-ik-keyframe document "default" "reach" 0.0 0.4 false interpolation))))
    (let [changed (data/set-ik-keyframe imported "default" "reach" 0.0 0.4 false
                                      {:type "bezier" :curve [0.3 0.4 0.8 0.9]})
          frame (get-in (json/read-str (data/export-spine-json changed)) ["animations" "default" "ik" "reach" 0])]
      (is (= 0.4 (get frame "mix")))
      (is (false? (get frame "bendPositive")))
      (is (= 4.0 (get frame "softness")))
      (is (= [0.3 0.4 0.8 0.9 0.25 4.0 0.75 8.0] (get frame "curve"))))
    (doseq [interpolation ["linear" "stepped"]]
      (is (= interpolation (get-in (data/set-ik-keyframe doc "default" "reach" 0.0 0.4 false interpolation)
                                  [:animations 0 :ik-tracks 0 :keys 0 :interpolation]))))))
