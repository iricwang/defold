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
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-geometry :as geometry]
            [editor.skeletal-animation-mesh :as mesh]
            [editor.skeletal-animation-slots :as slots]
            [util.coll :as coll]))

(defn- near? [a b] (< (Math/abs (- (double a) (double b))) 1.0e-7))

(defn- mesh-document []
  (-> (data/new-document "test")
      (data/add-bone {:id "joint" :parent "root"})
      (data/add-bone {:id "tip" :parent "joint" :x 100.0})
      (data/add-bone {:id "target" :parent "root" :x 150.0 :y 100.0})
      (data/add-slot {:id "image" :bone "root"
                      :attachment {:type "mesh" :name "image" :path "/images/body.png"
                                   :vertices [0.0 0.0 100.0 0.0 0.0 100.0]
                                   :uvs [0.0 0.0 1.0 0.0 0.0 1.0] :triangles [0 1 2]}})))

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
    (is (= [1.0 2.0 3.0 4.0 5.0 6.0 7.0 8.0] (get (data/sample-deforms imported "default" 0.0) "image")))
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
    (doseq [path [["events"] ["animations" "default" "events"] ["skins" 1]]]
      (is (= (get-in source path) (get-in output path))))
    (is (= [2.0 3.0] ((juxt :shear-x :shear-y) (first (data/sample-pose (data/import-spine-json (json/write-str output)) "default" 0.0)))))
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
    (is (coll/every? true? (mapv near? [1 2 3 4 3 4 5 6] (get (data/sample-deforms bound "default" 0) "image"))))
    (let [reordered (data/update-slot bound "image" {:attachment {:weights (update weights 1 #(vec (reverse %)))}})]
      (is (coll/every? true? (mapv near? [1 2 3 4 3 4 5 6] (get (data/sample-deforms reordered "default" 0) "image")))))
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

;; Editable transform references are removed with deleted bones; unsupported path references still block deletion.
(deftest advanced-reference-deletion-test
  (let [source (assoc (json/read-str (data/export-spine-json (mesh-document))) "transform" [{"name" "follow" "bones" ["tip"] "target" "target"}])
        doc (data/import-spine-json (json/write-str source))]
    (is (= [] (:transform (data/remove-bone doc "tip"))))
    (is (= [] (get (json/read-str (data/export-spine-json (data/remove-bone doc "tip"))) "transform"))))
  (let [source (assoc (json/read-str (data/export-spine-json (mesh-document))) "path" [{"name" "follow" "bones" ["tip"] "target" "image"}])
        doc (data/import-spine-json (json/write-str source))]
    (is (= [] (:path (data/remove-bone doc "tip"))))
    (is (= [] (get (json/read-str (data/export-spine-json (data/remove-bone doc "tip"))) "path")))))

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
    (is (= [0 1 2] (mapv #(get % "order") (get output "ik"))))))

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

;; Both editable IK channels and discrete options survive overlay export without unsupported-feature warnings.
(deftest ik-extended-frame-preservation-test
  (let [doc (data/add-ik (mesh-document) {:id "reach" :bones ["joint" "tip"] :target "target"})
        frame {"time" 0.0 "mix" 0.2 "softness" 4.0 "compress" true
               "curve" [0.25 0.2 0.75 1.0 0.25 4.0 0.75 8.0]}
        source (assoc-in (json/read-str (data/export-spine-json doc)) ["animations" "default" "ik" "reach"]
                         [frame {"time" 1.0 "mix" 1.0 "softness" 8.0}])
        imported (data/import-spine-json (json/write-str source))
        output (json/read-str (data/export-spine-json imported))]
    (is (= (assoc frame "bendPositive" true "stretch" false) (get-in output ["animations" "default" "ik" "reach" 0])))
    (is (zero? (count (:warnings imported))))))

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
    (is (= {"time" 0.0 "mix" 0.5 "bendPositive" false "softness" 0.0 "compress" false "stretch" false} recreated-frame))
    (is (= moved (data/read-json (data/write-json moved))))
    (let [collision (data/move-ik-keyframe moved "default" "reach" 0.5 2.0)
          frame (get-in (json/read-str (data/export-spine-json collision)) ["animations" "default" "ik" "reach" 0])]
      (is (= 1 (count (get-in collision [:animations 0 :ik-tracks 0 :keys]))))
      (is (= 4.0 (get frame "softness")))
      (is (false? (get frame "stretch")))))
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
;; Linear mix now coexists with an independently editable softness curve; a mixed step/smooth interval is rejected at export.
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
            interpolation ["linear" {:type "linear"}]]
      (let [changed (data/set-ik-keyframe document "default" "reach" 0.0 0.4 false interpolation)
            frame (get-in (json/read-str (data/export-spine-json changed)) ["animations" "default" "ik" "reach" 0])]
        (is (= [0.25 4.0 0.75 8.0] (subvec (get frame "curve") 4)))))
    (doseq [interpolation ["stepped" {:type "stepped"}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot mix stepped"
                            (data/export-spine-json (data/set-ik-keyframe imported "default" "reach" 0.0 0.4 false interpolation)))))
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

;; Shear is an independent axis angle, not a rotation or scale approximation.
;; Both setup and keyed values must survive an export and be used in geometry.
(deftest shear-import-edit-export-test
  (let [source {"skeleton" {"spine" "4.2.43"}
                "bones" [{"name" "root" "shearX" 10 "shearY" -20}]
                "animations" {"bend" {"bones" {"root" {"shear" [{"x" 0 "y" 0}
                                                                                {"time" 1 "x" 20 "y" 40}]}}}}}
        doc (data/import-spine-json (json/write-str source))
        sampled (first (data/sample-pose doc "bend" 0.5))
        matrix (get (data/world-transforms [sampled]) "root")
        edited (data/set-keyframe doc "bend" "root" "shear-x" 0.5 45 "linear")
        reopened (data/import-spine-json (data/export-spine-json edited))]
    (is (near? 20 (:shear-x sampled)))
    (is (near? 0 (:shear-y sampled)))
    (is (near? (Math/cos (Math/toRadians 20)) (nth matrix 0)))
    (is (near? (Math/sin (Math/toRadians 20)) (nth matrix 1)))
    (is (near? 0 (nth matrix 2)))
    (is (near? 1 (nth matrix 3)))
    (is (near? 45 (:shear-x (first (data/sample-pose reopened "bend" 0.5)))))
    (is (= 2 (count (get-in reopened [:animations 0 :tracks]))))))

;; Legacy documents lack shear fields; a newly keyed shear must start from zero
;; before its first key, and invalid numeric values cannot reach the renderer.
(deftest legacy-document-shear-default-test
  (let [doc (data/set-keyframe (data/new-document "legacy") "default" "root" "shear-y" 0.5 30 "linear")]
    (is (near? 0 (:shear-y (first (data/sample-pose doc "default" 0.0)))))
    (is (near? 30 (:shear-y (first (data/sample-pose doc "default" 0.5)))))
    (is (thrown? clojure.lang.ExceptionInfo (data/update-bone doc "root" {:shear-x Double/NaN})))))

(defn- skin-document []
  (let [source (json/read-str (data/export-spine-json (mesh-document)))
        default (first (get source "skins"))
        alternative (-> default
                        (assoc "name" "winter")
                        (assoc-in ["attachments" "image" "image" "path"] "images/winter")
                        (assoc-in ["attachments" "image" "image" "vertices"] [10 0 110 0 10 100]))]
    (data/import-spine-json (json/write-str (assoc source "skins" [default alternative])))))

;; Skin changes must commit both setup edits and FFD independently, preserve
;; authoring durations/custom data, and survive ordinary document persistence.
(deftest skin-switch-preserves-edits-test
  (let [initial (assoc (skin-document) :name "hero" :custom {:author "Alice"})
        first-edit (-> initial
                       (data/update-slot "image" {:attachment {:width 160.0}})
                       (data/set-deform-keyframe "default" "image" 0.25 [1 2 3 4 5 6] "linear")
                       (data/update-animation "default" {:duration 8.0}))
        switched (data/select-skin first-edit "winter")
        second-edit (-> switched
                        (data/update-slot "image" {:attachment {:width 240.0}})
                        (data/set-deform-keyframe "default" "image" 0.5 [7 8 9 10 11 12] "stepped"))
        back (data/select-skin second-edit "default")
        saved (data/read-json (data/write-json back))
        winter (data/select-skin saved "winter")]
    (is (= ["default" "winter"] (data/skin-names initial)))
    (is (= "winter" (:active-skin switched)))
    (is (= "/images/winter.png" (get-in switched [:slots 0 :attachment :path])))
    (is (= [] (get-in switched [:animations 0 :deforms])))
    (is (= [1 2 3 4 5 6] (get-in back [:animations 0 :deforms 0 :keys 0 :vertices])))
    (is (= [7 8 9 10 11 12] (get-in winter [:animations 0 :deforms 0 :keys 0 :vertices])))
    (is (= 160.0 (get-in back [:slots 0 :attachment :width])))
    (is (= 240.0 (get-in winter [:slots 0 :attachment :width])))
    (is (= 8.0 (get-in winter [:animations 0 :duration])))
    (is (= (:bones initial) (:bones winter)))
    (is (= {:author "Alice"} (:custom winter)))
    (is (= "hero" (:name winter)))
    (is (= saved back))
    (is (= first-edit (data/select-skin first-edit "default")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown skin" (data/select-skin initial "missing")))))

;; An absent attachment in a skin falls back to default, including default FFD;
;; editing the fallback must update its actual owner, not create a shadow copy.
(deftest skin-default-fallback-test
  (let [source (json/read-str (data/export-spine-json (skin-document)))
        source (assoc-in source ["skins" 1 "attachments"] {})
        document (-> (data/import-spine-json (json/write-str source))
                     (data/set-deform-keyframe "default" "image" 0.5 [1 2 3 4 5 6] "linear")
                     (data/select-skin "winter"))
        edited (data/update-slot document "image" {:attachment {:width 280.0}})
        output (json/read-str (data/export-spine-json edited))]
    (is (= "winter" (:active-skin document)))
    (is (= "default" (get-in document [:slots 0 :spine-skin])))
    (is (= "default" (get-in document [:animations 0 :deforms 0 :skin])))
    (is (= [1 2 3 4 5 6] (get-in document [:animations 0 :deforms 0 :keys 0 :vertices])))
    (is (= 280.0 (get-in output ["skins" 0 "attachments" "image" "image" "width"])))
    (is (= {} (get-in output ["skins" 1 "attachments"])))
    (is (= 280.0 (get-in (data/select-skin edited "default") [:slots 0 :attachment :width])))))

;; Duplicating a skin clones its mesh deformation tracks but leaves the source
;; independent. Invalid/duplicate names cannot replace an existing skin.
(deftest duplicate-skin-test
  (let [initial (-> (skin-document)
                    (data/select-skin "winter")
                    (data/set-deform-keyframe "default" "image" 0.5 [1 2 3 4 5 6] "linear"))
        copied (data/duplicate-skin initial "summer")
        edited (data/update-slot copied "image" {:attachment {:width 320.0}})
        back (data/select-skin edited "winter")]
    (is (= ["default" "winter" "summer"] (data/skin-names copied)))
    (is (= "summer" (:active-skin copied)))
    (let [source (json/read-str (data/export-spine-json copied))
          attachment (get-in source ["skins" 2 "attachments" "image" "image"])]
      (is (= "summer/image/image" (get attachment "name")))
      (is (= "images/winter" (get attachment "path"))))
    (is (= "summer" (get-in copied [:animations 0 :deforms 0 :skin])))
    (is (= [1 2 3 4 5 6] (get-in copied [:animations 0 :deforms 0 :keys 0 :vertices])))
    (is (= (get-in initial [:slots 0 :attachment]) (get-in back [:slots 0 :attachment])))
    (is (= 320.0 (get-in (data/select-skin back "summer") [:slots 0 :attachment :width])))
    (is (thrown? clojure.lang.ExceptionInfo (data/duplicate-skin copied "summer")))
    (is (thrown? clojure.lang.ExceptionInfo (data/duplicate-skin copied "")))))

;; Adding attachments while a nondefault skin is active must not insert them
;; into default. Empty projects must retain default as a selectable base skin.
(deftest new-skin-attachment-ownership-test
  (let [empty-doc (data/new-document "empty")
        copied (data/duplicate-skin empty-doc "winter")
        attached (data/add-slot copied {:id "body" :bone "root" :attachment {:path "/images/body.png"}})
        back (data/select-skin attached "default")
        again (data/select-skin back "winter")]
    (is (= ["default" "winter"] (data/skin-names copied)))
    (is (= "winter" (get-in attached [:slots 0 :spine-skin])))
    (is (nil? (get-in back [:slots 0 :attachment])))
    (is (= "/images/body.png" (get-in again [:slots 0 :attachment :path])))
    (is (= 1.0 (get-in back [:animations 0 :duration])))))

;; Direct default attachments and skin placeholders cannot share names in a
;; native Spine project. Promote the base appearance before copying it, and
;; move FFD plus linked-mesh references to the same base skin.
(deftest default-skin-promotion-test
  (let [document (-> (mesh-document)
                     (data/set-deform-keyframe "default" "image" 0.5 [1 2 3 4 5 6] "linear"))
        copied (data/duplicate-skin document "winter")
        source (json/read-str (data/export-spine-json copied))
        base (data/select-skin copied "base")]
    (is (= ["base" "winter"] (data/skin-names copied)))
    (is (= "base/image/image" (get-in source ["skins" 0 "attachments" "image" "image" "name"])))
    (is (= "winter/image/image" (get-in source ["skins" 1 "attachments" "image" "image" "name"])))
    (is (= "winter" (get-in copied [:animations 0 :deforms 0 :skin])))
    (is (= "base" (get-in base [:animations 0 :deforms 0 :skin])))
    (is (= [1.0 2.0 3.0 4.0 5.0 6.0] (get-in base [:animations 0 :deforms 0 :keys 0 :vertices])))
    (is (nil? (get-in source ["animations" "default" "attachments" "default"])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Select a named skin"
                          (data/duplicate-skin (skin-document) "another")))))

(defn- transform-document []
  (data/add-transform (mesh-document) {:id "follow" :bones ["tip"] :target "target" :order 2
                                      :mix-rotate 0.0 :mix-scale-x 0.0 :mix-scale-y 0.0 :mix-shear-y 0.0}))

;; Transform setup, arbitrary source metadata and animated six-channel Bezier curves survive editing and serialization.
(deftest transform-roundtrip-and-edit-test
  (let [doc (transform-document)
        source (-> (json/read-str (data/export-spine-json doc))
                   (assoc-in ["transform" 0 "custom"] "retained")
                   (assoc-in ["animations" "default" "transform" "follow"]
                             [{"time" 0.0 "mixX" 0.0 "mixY" 0.2
                               "curve" [0.2 1.0 0.8 1.0 0.2 0.1 0.8 0.8 0.2 0.2 0.8 0.9
                                        0.2 1.0 0.8 1.0 0.2 1.0 0.8 1.0 0.2 1.0 0.8 1.0]}
                              {"time" 1.0 "mixX" 1.0 "mixY" 1.0}]))
        imported (data/import-spine-json (json/write-str source))
        edited (data/set-transform-keyframe imported "default" "follow" "mix-x" 0.5 0.4 "linear")
        roundtrip (data/import-spine-json (data/export-spine-json edited))
        output (json/read-str (data/export-spine-json edited))]
    (is (= 6 (count (get-in imported [:animations 0 :transform-tracks]))))
    (is (= "retained" (get-in output ["transform" 0 "custom"])))
    (doseq [time [0.0 0.2 0.4 0.5 0.6 0.8 1.0]
            property [:mix-rotate :mix-x :mix-y :mix-scale-x :mix-scale-y :mix-shear-y]]
      (is (near? (property (first (data/sample-transforms edited "default" time)))
                 (property (first (data/sample-transforms roundtrip "default" time))))))
    (let [renamed (-> imported (data/update-transform "follow" {:id "follow2"}) (data/update-bone "target" {:id "goal"}))
          output (json/read-str (data/export-spine-json renamed))]
      (is (= "goal" (get-in output ["transform" 0 "target"])))
      (is (= "retained" (get-in output ["transform" 0 "custom"])))
      (is (contains? (get-in output ["animations" "default" "transform"]) "follow2"))
      (is (nil? (get-in output ["animations" "default" "transform" "follow"]))))
    (is (nil? (get-in (json/read-str (data/export-spine-json (data/remove-transform imported "follow"))) ["animations" "default" "transform"])))
    (is (thrown? clojure.lang.ExceptionInfo (data/update-transform imported "follow" {:target "missing"})))
    (is (thrown? clojure.lang.ExceptionInfo (data/update-transform imported "follow" {:order -1})))))

;; Moving/removing transform keys preserves Bezier intervals and avoids orphan timelines after deletion.
(deftest transform-key-lifecycle-test
  (let [doc (-> (transform-document)
                (data/set-transform-keyframe "default" "follow" "mix-x" 0.0 0.0 {:type "bezier" :curve [0.2 0.1 0.8 0.9]})
                (data/set-transform-keyframe "default" "follow" "mix-x" 1.0 1.0 "linear"))
        moved (data/move-transform-keyframe doc "default" "follow" "mix-x" 1.0 2.0)]
    (is (= [0.4 0.1 1.6 0.9] (get-in moved [:animations 0 :transform-tracks 0 :keys 0 :curve])))
    (is (near? 0.5 (:mix-x (first (data/sample-transforms moved "default" 1.0)))))
    (is (= 1 (count (get-in (data/remove-transform-keyframe moved "default" "follow" "mix-x" 2.0) [:animations 0 :transform-tracks 0 :keys]))))
    (is (= [] (get-in (data/remove-bone moved "target") [:animations 0 :transform-tracks])))))

;; Spine's shared stepped flag cannot encode independently stepped and changing smooth channels; export must reject the loss.
(deftest transform-mixed-step-export-test
  (let [doc (-> (transform-document)
                (data/set-transform-keyframe "default" "follow" "mix-x" 0.0 0.0 "stepped")
                (data/set-transform-keyframe "default" "follow" "mix-x" 1.0 1.0 "linear")
                (data/set-transform-keyframe "default" "follow" "mix-y" 0.0 0.0 "linear")
                (data/set-transform-keyframe "default" "follow" "mix-y" 1.0 1.0 "linear"))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot mix stepped" (data/export-spine-json doc)))))

;; IK must see a target moved by an earlier transform constraint, but not by a later constraint.
(deftest unified-constraint-order-test
  (let [doc (-> (mesh-document)
                (data/add-bone {:id "goal" :parent "root" :x 0.0 :y 100.0})
                (data/add-transform {:id "move-target" :bones ["target"] :target "goal" :order 0
                                     :mix-rotate 0.0 :mix-scale-x 0.0 :mix-scale-y 0.0 :mix-shear-y 0.0})
                (data/add-ik {:id "reach" :bones ["joint"] :target "target" :order 1}))
        first-pose (data/evaluate-pose doc nil 0.0)
        last-pose (data/evaluate-pose (data/update-transform doc "move-target" {:order 2}) nil 0.0)]
    (is (near? 90.0 (:rotation (nth (:bones first-pose) 1))))
    (is (< (:rotation (nth (:bones last-pose) 1)) 40.0))
    (is (= [0.0 100.0] (subvec (get-in first-pose [:transforms "target"]) 4 6)))
    (is (= [0.0 100.0] (subvec (get-in last-pose [:transforms "target"]) 4 6))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"normal inheritance"
                       (-> (mesh-document) (data/update-bone "joint" {:inherit "onlyTranslation"})
                           (data/add-ik {:id "reach" :bones ["joint" "tip"] :target "target"})))))

;; A topology split migrates all active FFD keys and exported mesh arrays to the same new vertex count.
(deftest mesh-edit-deform-integration-test
  (let [doc (data/set-deform-keyframe (mesh-document) "default" "image" 0.0 [0.0 0.0 10.0 20.0 30.0 40.0] "linear")
        before (get-in doc [:slots 0 :attachment])
        edit (mesh/split-edge before [0 1] 0.5)
        edited (data/apply-mesh-edit doc "image" edit)
        roundtrip (data/import-spine-json (data/export-spine-json edited))]
    (is (= 8 (count (get-in edited [:slots 0 :attachment :vertices]))))
    (is (= [5.0 10.0] (subvec (get (data/sample-deforms edited "default" 0.0) "image") 6 8)))
    (let [layout (mesh/spine-export-layout (get-in edited [:slots 0 :attachment]))]
      (is (= (mesh/remap-deform (get-in edited [:slots 0 :attachment]) (:mesh layout) (:vertex-map layout)
                               (get (data/sample-deforms edited "default" 0.0) "image"))
             (get (data/sample-deforms roundtrip "default" 0.0) "image"))))))

;; Saved documents from the previous editor kept transform/inheritance only in source JSON; loading must materialize them without resetting authored values.
(deftest legacy-constraint-upgrade-test
  (let [doc (-> (transform-document)
                (data/update-bone "target" {:inherit "noScale" :shear-y 12.0})
                (data/set-transform-keyframe "default" "follow" "mix-x" 0.0 0.25 "linear"))
        imported (data/import-spine-json (data/export-spine-json doc))
        legacy (-> imported (dissoc :transform)
                   (update :bones #(mapv (fn [bone] (dissoc bone :inherit :shear-x :shear-y)) %))
                   (update :animations #(mapv (fn [animation] (dissoc animation :transform-tracks)) %))
                   (assoc-in [:bones 3 :x] 500.0))
        loaded (data/read-json (data/write-json legacy))]
    (is (= (:transform imported) (:transform loaded)))
    (is (= "noScale" (:inherit (nth (:bones loaded) 3))))
    (is (= 12.0 (:shear-y (nth (:bones loaded) 3))))
    (is (= 500.0 (:x (nth (:bones loaded) 3))))
    (is (= 0.25 (:mix-x (first (data/sample-transforms loaded "default" 0.0)))))
    (is (= "noScale" (get-in (json/read-str (data/export-spine-json legacy)) ["bones" 3 "inherit"])))
    (is (= 1 (count (get (json/read-str (data/export-spine-json legacy)) "transform"))))))

;; Binding or unbinding an already animated mesh keeps setup-space deformation under rotated, reflected, nonuniform bones, through both edit APIs.
(deftest binding-retains-world-deform-test
  (let [doc (-> (mesh-document)
                (data/update-bone "root" {:rotation 31.0 :scale-x -1.3 :scale-y 2.1})
                (data/update-bone "joint" {:rotation -27.0 :scale-x 2.0 :scale-y 0.4})
                (data/update-bone "tip" {:rotation 64.0 :scale-x 0.8 :scale-y -1.2})
                (data/set-deform-keyframe "default" "image" 0.0 [3.0 -7.0 5.0 9.0 -2.0 6.0] "linear"))
        worlds (data/world-transforms (:bones doc))
        attachment (get-in doc [:slots 0 :attachment])
        context {:bones (:bones doc) :slot-transform (worlds "root") :bone-transforms worlds}
        edit (mesh/bind-weights attachment ["joint" "tip"] context {:falloff 2.0 :max-influences 2})
        from-slot (data/update-slot doc "image" {:attachment (:mesh edit)})
        from-mesh (data/apply-mesh-edit doc "image" edit)
        unbound (data/update-slot from-mesh "image" {:attachment {:weights nil}})
        positions (fn [document]
                    (geometry/mesh-world-vertices (get-in document [:slots 0 :attachment]) (worlds "root") worlds
                                                  (get (data/sample-deforms document "default" 0.0) "image")))
        expected (positions doc)]
    (doseq [changed [from-slot from-mesh unbound]
            [a b] (mapv vector expected (positions changed))]
      (is (near? a b)))
    (is (= (data/sample-deforms from-slot "default" 0.0) (data/sample-deforms from-mesh "default" 0.0)))))

;; Softness and mix have independent curves, discrete flags sample at key boundaries, and setup options survive interchange.
(deftest full-ik-channel-editing-test
  (let [doc (-> (mesh-document)
                (data/add-ik {:id "reach" :bones ["joint" "tip"] :target "target" :softness 15.0 :stretch true :uniform true})
                (data/set-ik-keyframe-values "default" "reach" 0.0 {:mix 0.25 :softness 20.0 :compress true :stretch false :bend-positive false} "linear")
                (data/set-ik-keyframe-values "default" "reach" 1.0 {:mix 0.75 :softness 60.0 :compress false :stretch true :bend-positive true} "linear")
                (data/set-ik-softness-keyframe "default" "reach" 0.0 20.0 {:type "bezier" :curve [0.2 25.0 0.8 55.0]}))
        roundtrip (data/import-spine-json (data/export-spine-json doc))
        moved (data/move-ik-keyframe doc "default" "reach" 1.0 2.0)]
    (is (= (:ik doc) (:ik roundtrip)))
    (is (= [0.4 25.0 1.6 55.0] (get-in moved [:animations 0 :ik-tracks 0 :keys 0 :softness-curve])))
    (doseq [time [0.0 0.25 0.5 0.75 1.0]]
      (let [sample (first (data/sample-ik doc "default" time))
            actual (first (data/sample-ik roundtrip "default" time))]
        (is (near? (:mix sample) (:mix actual)))
        (is (near? (:softness sample) (:softness actual)))
        (is (= (:compress sample) (< time 1.0)))
        (is (= (:stretch sample) (>= time 1.0)))
        (is (= (:bend-positive sample) (>= time 1.0)))))
    (is (near? 40.0 (:softness (first (data/sample-ik moved "default" 1.0)))))
    (is (thrown? clojure.lang.ExceptionInfo (data/update-ik doc "reach" {:softness -1.0})))
    (is (thrown? clojure.lang.ExceptionInfo (data/set-ik-softness-keyframe doc "default" "reach" 0.5 -1.0 "linear")))
    (is (thrown? clojure.lang.ExceptionInfo (data/update-ik doc "reach" {:compress "yes"})))))

;; Inserting a key inside a softness-only Bezier interval retimes its controls instead of leaving invalid keys.
(deftest insert-softness-key-test
  (let [doc (-> (mesh-document)
                (data/add-ik {:id "reach" :bones ["joint" "tip"] :target "target"})
                (data/set-ik-softness-keyframe "default" "reach" 0.0 0.0 {:type "bezier" :curve [0.2 0.0 0.8 10.0]})
                (data/set-ik-softness-keyframe "default" "reach" 1.0 10.0 "linear")
                (data/set-ik-softness-keyframe "default" "reach" 0.5 5.0 "linear"))]
    (is (= [0.1 0.0 0.4 10.0] (get-in doc [:animations 0 :ik-tracks 0 :keys 0 :softness-curve])))
    (is (string? (data/export-spine-json doc)))))

;; Export derives a fresh hull/edge list and permutes FFD together with an edited mesh, leaving the in-memory vertex order intact.
(deftest mesh-export-hull-and-deform-test
  (let [source (json/read-str (data/export-spine-json (mesh-document)))
        edited-mesh {:vertices [50.0 50.0 0.0 0.0 100.0 0.0 100.0 100.0 0.0 100.0]
                     :uvs [0.5 0.5 0.0 0.0 1.0 0.0 1.0 1.0 0.0 1.0]
                     :triangles [1 2 0 2 3 0 3 4 0 4 1 0]
                     :constrained-edges [[0 2]] :spine-topology-edited true}
        doc (-> (data/import-spine-json (json/write-str source))
                (data/update-slot "image" {:attachment edited-mesh})
                (data/set-deform-keyframe "default" "image" 0.0 [1.0 2.0 3.0 4.0 5.0 6.0 7.0 8.0 9.0 10.0] "linear"))
        output (json/read-str (data/export-spine-json doc))
        attachment (get-in output ["skins" 0 "attachments" "image" "image"])
        roundtrip (data/import-spine-json (json/write-str output))
        vertex-map (fn [document]
                     (let [attachment (get-in document [:slots 0 :attachment])
                           vertices (geometry/mesh-world-vertices attachment (data/local-transform (first (:bones document)))
                                                                   (data/world-transforms (:bones document))
                                                                   (get (data/sample-deforms document "default" 0.0) "image"))]
                       (zipmap (into [] (partition-all 2) (:uvs attachment)) (into [] (partition-all 2) vertices))))]
    (is (= 4 (get attachment "hull")))
    (is (= 12 (count (get attachment "triangles"))))
    (is (coll/every? #(and (even? %) (< % 10)) (get attachment "edges")))
    (is (= (vertex-map doc) (vertex-map roundtrip)))
    (is (= (:vertices edited-mesh) (get-in doc [:slots 0 :attachment :vertices])))))

;; A linked mesh's private FFD cannot be silently invalidated by parent-topology editing.
(deftest linked-private-deform-topology-guard-test
  (let [source (-> (json/read-str (data/export-spine-json (mesh-document)))
                   (assoc-in ["skins" 0 "attachments" "image" "linked"] {"type" "linkedmesh" "parent" "image" "path" "images/body"})
                   (assoc-in ["animations" "default" "attachments" "default" "image" "linked" "deform"] [{"time" 0.0 "vertices" [1 2 3 4 5 6]}]))
        doc (data/import-spine-json (json/write-str source))
        edited (data/apply-mesh-edit doc "image" (mesh/split-edge (get-in doc [:slots 0 :attachment]) [0 1] 0.5))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"private deformation" (data/export-spine-json edited)))))

(defn- path-document []
  (-> (data/new-document "paths")
      (data/add-bone {:id "follower" :parent "root" :length 20.0})
      (data/add-slot {:id "curve-slot" :bone "root"
                      :attachment {:name "curve" :type "path" :vertices [-10 0 0 0 30 0 70 0 100 0 110 0]
                                   :closed false :constant-speed true :lengths [100.0]}})
      (data/add-path {:id "follow" :bones ["follower"] :target "curve-slot" :position-mode "percent" :position 0.5})))

;; Path setup, scalar/mix Bezier animation and FFD survive a JSON round-trip and evaluate together.
(deftest path-authoring-roundtrip-test
  (let [doc (-> (path-document)
                (data/set-path-keyframe "default" "follow" "position" 0.0 0.0 {:type "bezier" :curve [0.2 0.1 0.8 0.9]})
                (data/set-path-keyframe "default" "follow" "position" 1.0 1.0 "linear")
                (data/set-path-keyframe "default" "follow" "spacing" 0.0 4.0 "stepped")
                (data/set-path-keyframe "default" "follow" "mix-rotate" 0.0 0.0 {:type "bezier" :curve [0.2 0.3 0.8 0.7]})
                (data/set-path-keyframe "default" "follow" "mix-rotate" 1.0 1.0 "linear")
                (data/set-path-keyframe "default" "follow" "mix-x" 0.0 0.2 "linear")
                (data/set-path-keyframe "default" "follow" "mix-x" 0.5 0.6 "linear")
                (data/set-path-keyframe "default" "follow" "mix-x" 1.0 1.0 "linear")
                (data/set-deform-keyframe "default" "curve-slot" 0.0 [0 10 0 10 0 10 0 10 0 10 0 10] "linear"))
        exported (json/read-str (data/export-spine-json doc))
        imported (data/import-spine-json (json/write-str exported))]
    (is (= "path" (get-in imported [:slots 0 :attachment :type])))
    (is (nil? (get-in exported ["skins" 0 "attachments" "curve-slot" "curve" "path"])))
    (is (= 6 (get-in exported ["skins" 0 "attachments" "curve-slot" "curve" "vertexCount"])))
    (is (= [] (:warnings imported)))
    (is (= 3 (count (get-in exported ["animations" "default" "path" "follow" "mix"]))))
    (doseq [time [0.0 0.1 0.3 0.5 0.8 1.0]]
      (let [before (data/evaluate-pose doc "default" time)
            after (data/evaluate-pose imported "default" time)]
        (is (coll/every? true? (mapv near? (get-in before [:transforms "follower"]) (get-in after [:transforms "follower"]))))))
    (is (near? 10.0 (get-in (data/evaluate-pose imported "default" 0.5) [:transforms "follower" 5])))
    (is (= (:path imported) (:path (data/read-json (data/write-json imported)))))))

;; Constraint/slot/bone renames, key moves and deletion retain unknown source data without resurrecting removed paths.
(deftest path-lifecycle-source-preservation-test
  (let [source (-> (json/read-str (data/export-spine-json (path-document)))
                   (assoc-in ["path" 0 "future"] {"keep" true})
                   (assoc-in ["skins" 0 "path"] ["follow"])
                   (assoc-in ["animations" "default" "path" "follow"]
                             {"position" [{"time" 0.0 "value" 0.1 "curve" [0.2 0.2 0.8 0.8] "memo" "first"}
                                          {"time" 1.0 "value" 0.9 "memo" "last"}]
                              "future" [{"time" 0.3 "payload" "keep"}]}))
        imported (data/import-spine-json (json/write-str source))
        edited (-> imported
                   (data/update-bone "follower" {:id "follow-bone"})
                   (data/update-slot "curve-slot" {:id "path-slot"})
                   (data/update-path "follow" {:id "follow-path" :spacing-mode "proportional"})
                   (data/move-path-keyframe "default" "follow-path" "position" 0.0 0.1))
        exported (json/read-str (data/export-spine-json edited))
        removed (data/remove-path edited "follow-path")
        without-slot (data/remove-slot edited "path-slot")]
    (is (= ["follow-bone"] (get-in exported ["path" 0 "bones"])))
    (is (= "path-slot" (get-in exported ["path" 0 "target"])))
    (is (= {"keep" true} (get-in exported ["path" 0 "future"])))
    (is (= "first" (get-in exported ["animations" "default" "path" "follow-path" "position" 0 "memo"])))
    (is (= "keep" (get-in exported ["animations" "default" "path" "follow-path" "future" 0 "payload"])))
    (is (= ["follow-path"] (get-in exported ["skins" 0 "path"])))
    (is (= [] (:path removed)))
    (is (= [] (:path without-slot)))
    (is (= [] (get-in without-slot [:animations 0 :path-tracks])))
    (is (nil? (get-in (json/read-str (data/export-spine-json removed)) ["animations" "default" "path" "follow-path"])))
    (is (= 1 (count (get-in (data/remove-path-keyframe edited "default" "follow-path" "position" 0.1) [:animations 0 :path-tracks 0 :keys]))))))

;; Weighted path binding retains FFD and recalculates setup lengths when control points change.
(deftest path-weighted-edit-test
  (let [doc (-> (path-document)
                (data/add-bone {:id "anchor" :parent "root" :rotation 30.0 :scale-x 1.2 :scale-y 0.7})
                (data/set-deform-keyframe "default" "curve-slot" 0.0 [0 10 0 10 0 10 0 10 0 10 0 10] "linear"))
        worlds (data/world-transforms (:bones doc))
        points (partition-all 2 (get-in doc [:slots 0 :attachment :vertices]))
        weights (mapv (fn [point]
                        (let [[x y] (geometry/inverse-transform-point (get worlds "anchor") point)]
                          [{:bone "anchor" :weight 1.0 :x x :y y}])) points)
        bound (data/update-slot doc "curve-slot" {:attachment {:weights weights}})
        output (data/import-spine-json (data/export-spine-json bound))
        before (get-in (data/evaluate-pose doc "default" 0.0) [:transforms "follower"])
        after (get-in (data/evaluate-pose output "default" 0.0) [:transforms "follower"])
        changed (data/update-slot (path-document) "curve-slot" {:attachment {:vertices [-10 0 0 0 60 0 140 0 200 0 210 0] :constant-speed false}})]
    (is (= 6 (count (get-in output [:slots 0 :attachment :weights]))))
    (is (coll/every? true? (mapv near? before after)))
    (is (near? 200.0 (get-in changed [:slots 0 :attachment :lengths 0])))
    (is (near? 100.0 (get-in (data/evaluate-pose changed nil 0.0) [:transforms "follower" 4])))))

;; Path validation blocks malformed curves, references, cycles and unsupported mixed stepped/smooth export.
(deftest path-validation-test
  (let [doc (path-document)]
    (doseq [changes [{:target "missing"} {:bones ["missing"]} {:bones ["root"]} {:position-mode "bad"}
                     {:spacing-mode "bad"} {:rotate-mode "bad"} {:position Double/NaN} {:order -1}]]
      (is (thrown? clojure.lang.ExceptionInfo (data/update-path doc "follow" changes))))
    (doseq [changes [{:vertices [0 0]} {:closed "yes"} {:constant-speed "yes"} {:lengths []}
                     {:vertices [0 0 0 0 0 0 0 0 0 0 Double/NaN 0]}]]
      (is (thrown? clojure.lang.ExceptionInfo (data/update-slot doc "curve-slot" {:attachment changes}))))
    (is (thrown? clojure.lang.ExceptionInfo (data/set-path-keyframe doc "default" "follow" "bad" 0.0 1.0 "linear")))
    (is (thrown? clojure.lang.ExceptionInfo (data/set-path-keyframe doc "default" "follow" "position" (json/read-str "null") 1.0 "linear")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot mix stepped and smooth"
                         (data/export-spine-json (-> doc
                                                    (data/set-path-keyframe "default" "follow" "mix-x" 0.0 0.0 "stepped")
                                                    (data/set-path-keyframe "default" "follow" "mix-x" 1.0 1.0 "linear")
                                                    (data/set-path-keyframe "default" "follow" "mix-y" 0.0 0.0 "linear")
                                                    (data/set-path-keyframe "default" "follow" "mix-y" 1.0 1.0 "linear")))))))

;; Documents saved before Path editing hydrate newly supported attachments and FFD instead of dropping the original deformation on export.
(deftest legacy-path-document-upgrade-test
  (let [imported (data/import-spine-json (data/export-spine-json
                                         (-> (path-document)
                                             (data/set-path-keyframe "default" "follow" "position" 0.0 0.2 "linear")
                                             (data/set-deform-keyframe "default" "curve-slot" 0.0 [0 7 0 7 0 7 0 7 0 7 0 7] "linear"))))
        legacy (-> imported (dissoc :path)
                   (update-in [:slots 0] #(assoc (dissoc % :attachment) :unsupported-attachment true))
                   (update-in [:animations 0] #(dissoc % :path-tracks :deforms)))
        upgraded (data/read-json (json/write-str legacy))
        reimported (data/import-spine-json (data/export-spine-json legacy))]
    (is (= (:path imported) (:path upgraded)))
    (is (= (get-in imported [:animations 0 :deforms]) (get-in upgraded [:animations 0 :deforms])))
    (is (near? 7.0 (get-in (data/evaluate-pose reimported "default" 0.0) [:transforms "follower" 5])))))

(defn- physics-document []
  (-> (data/new-document "physics")
      (data/add-bone {:id "tip" :parent "root" :x 40.0 :length 100.0})
      (data/add-bone {:id "end" :parent "tip" :x 100.0})
      (data/add-physics {:id "spring" :bone "tip" :x 1.0 :y 1.0 :rotate 0.6 :scale-x 0.4 :shear-x 0.3
                         :damping 0.7 :wind 0.2 :gravity -0.15 :mass-global true})
      (data/set-keyframe "default" "root" "x" 0.0 0.0 "linear")
      (data/set-keyframe "default" "root" "x" 1.0 60.0 "linear")))

;; Physics setup, global flags, curves, reset keys and unknown metadata survive editing, copying and exporting.
(deftest physics-data-lifecycle-test
  (let [source (-> (json/read-str (data/export-spine-json (physics-document)))
                   (assoc-in ["physics" 0 "future"] {"keep" true})
                   (assoc-in ["skins" 0 "physics"] ["spring"])
                   (assoc-in ["animations" "default" "physics" "spring"]
                             {"mass" [{"time" 0.0 "value" 1.0 "curve" [0.2 1.5 0.8 2.5] "memo" "first"}
                                      {"time" 1.0 "value" 3.0}]
                              "reset" [{"time" 0.5 "memo" "reset"}]
                              "future" [{"time" 0.3 "payload" "keep"}]}))
        imported (data/import-spine-json (json/write-str source))
        edited (-> imported (data/update-bone "tip" {:id "arm"})
                   (data/update-physics "spring" {:id "sway" :wind 0.4})
                   (data/move-physics-keyframe "default" "sway" "mass" 0.0 0.1)
                   (data/move-physics-keyframe "default" "sway" "reset" 0.5 0.7))
        output (json/read-str (data/export-spine-json edited))
        removed (data/remove-physics edited "sway")]
    (is (= "arm" (get-in output ["physics" 0 "bone"])))
    (is (= {"keep" true} (get-in output ["physics" 0 "future"])))
    (is (= true (get-in output ["physics" 0 "massGlobal"])))
    (is (= "first" (get-in output ["animations" "default" "physics" "sway" "mass" 0 "memo"])))
    (is (= 0.7 (get-in output ["animations" "default" "physics" "sway" "reset" 0 "time"])))
    (is (= "reset" (get-in output ["animations" "default" "physics" "sway" "reset" 0 "memo"])))
    (is (= "keep" (get-in output ["animations" "default" "physics" "sway" "future" 0 "payload"])))
    (is (= ["sway"] (get-in output ["skins" 0 "physics"])))
    (is (= [] (:physics removed)))
    (is (= [] (get-in removed [:animations 0 :physics-tracks])))
    (is (nil? (get-in (json/read-str (data/export-spine-json removed)) ["animations" "default" "physics" "sway"])))
    (is (= [] (:physics (data/remove-bone edited "arm")))))
  (let [doc (-> (physics-document)
                (data/set-physics-keyframe "default" "spring" "mass" 0.0 1.0 "linear")
                (data/set-physics-keyframe "default" "spring" "mass" 1.0 3.0 "linear")
                (data/set-physics-reset-keyframe "default" "" 0.5))
        imported (data/import-spine-json (data/export-spine-json doc))]
    (is (near? 2.0 (:mass (first (data/sample-physics imported "default" 0.5)))))
    (is (= [] (get-in (data/remove-physics-keyframe doc "default" "" "reset" 0.5) [:animations 0 :physics-tracks 1 :keys] [])))
    (is (= (:physics imported) (:physics (data/read-json (data/write-json imported)))))))

;; JSON object order controls the priority of global and individual timelines and must survive import/export.
(deftest physics-global-timeline-order-test
  (doseq [[order expected] [[["" "spring"] 2.0] [["spring" ""] 8.0]]]
    (let [rank (zipmap order [0 1])
          timelines (into (sorted-map-by #(compare (rank %1) (rank %2)))
                          {"" {"mass" [{"time" 0.0 "value" 8.0}]}
                           "spring" {"mass" [{"time" 0.0 "value" 1.0} {"time" 1.0 "value" 3.0}]}})
          source (assoc-in (json/read-str (data/export-spine-json (physics-document))) ["animations" "default" "physics"] timelines)
          imported (data/import-spine-json (json/write-str source))
          reimported (data/import-spine-json (data/export-spine-json imported))]
      (is (= order (get-in imported [:animations 0 :physics-order])))
      (is (= order (get-in reimported [:animations 0 :physics-order])))
      (is (near? expected (:mass (first (data/sample-physics reimported "default" 0.5))))))))

;; Cached forward playback, random seeks and backward seeks yield the same pose and propagate physics to descendants.
(deftest physics-deterministic-preview-test
  (let [doc (physics-document)
        result (reduce (fn [{:keys [state]} time]
                         (let [next (data/evaluate-pose-state doc "default" time state)
                               direct (data/evaluate-pose doc "default" time)]
                           (is (= (:transforms direct) (get-in next [:pose :transforms])))
                           next)) {:state nil} [0.0 0.017 0.039 0.1 0.399 0.65 0.203 0.8 1.0])
        pose (:pose result)
        tip (get-in pose [:transforms "tip"])
        end (get-in pose [:transforms "end"])
        expected (geometry/transform-point tip [100.0 0.0])
        reset (data/set-physics-reset-keyframe doc "default" "" 0.5)
        reset-pose (data/evaluate-pose reset "default" 0.5)
        setup-at-half (data/evaluate-pose (assoc doc :physics []) "default" 0.5)]
    (is (not= (:transforms (data/evaluate-pose (assoc doc :physics []) "default" 1.0)) (:transforms pose)))
    (is (coll/every? true? (mapv near? expected (subvec end 4 6))))
    (is (= (:transforms setup-at-half) (:transforms reset-pose)))
    (is (= (:transforms (data/evaluate-pose (data/update-physics doc "spring" {:wind 0.7}) "default" 1.0))
           (get-in (data/evaluate-pose-state (data/update-physics doc "spring" {:wind 0.7}) "default" 1.0 (:state result)) [:pose :transforms])))))

;; Physics migration restores newly editable source tracks, while invalid masses, references and numeric values fail explicitly.
(deftest physics-upgrade-validation-test
  (let [imported (data/import-spine-json (data/export-spine-json
                                         (data/set-physics-keyframe (physics-document) "default" "spring" "strength" 0.0 50.0 "linear")))
        legacy (-> imported (dissoc :physics :reference-scale) (update-in [:animations 0] dissoc :physics-tracks :physics-order))
        upgraded (data/read-json (json/write-str legacy))]
    (is (= (:physics imported) (:physics upgraded)))
    (is (= (get-in imported [:animations 0 :physics-tracks]) (get-in upgraded [:animations 0 :physics-tracks])))
    (doseq [changes [{:bone "missing"} {:mass 0} {:fps 0} {:limit -1} {:strength -1} {:damping 2} {:wind ##NaN} {:order -1} {:mass-global "yes"}]]
      (is (thrown? clojure.lang.ExceptionInfo (data/update-physics imported "spring" changes))))
    (doseq [[property value] [["mass" 0] ["damping" -0.1] ["strength" -1] ["wind" ##Inf] ["missing" 1]]]
      (is (thrown? clojure.lang.ExceptionInfo (data/set-physics-keyframe imported "default" "spring" property 0.0 value "linear"))))))

;; Duration padding already handles empty, constraint-only and bone animation without changing the last sampled value.
(deftest export-duration-preservation-test
  (doseq [doc [(data/new-document "empty") (path-document)
               (-> (data/new-document "rotated")
                   (data/set-keyframe "default" "root" "rotation" 0.0 15.0 {:type "bezier" :curve [0.1 20.0 0.4 30.0]})
                   (data/set-keyframe "default" "root" "rotation" 0.5 40.0 "linear"))]]
    (let [doc (data/update-animation doc "default" {:duration 2.0})
          imported (data/import-spine-json (data/export-spine-json doc))]
      (is (= 2.0 (get-in imported [:animations 0 :duration])))
      (doseq [time [0.0 0.25 0.5 1.0 2.0]]
        (is (coll/every? true? (mapv near? (get-in (data/evaluate-pose doc "default" time) [:transforms "root"])
                                       (get-in (data/evaluate-pose imported "default" time) [:transforms "root"]))))))))

;; Renaming a newly authored slot freezes its existing attachment name so every deformation still targets the exported attachment.
(deftest slot-rename-default-attachment-test
  (let [doc (-> (mesh-document) (update-in [:slots 0 :attachment] dissoc :name)
                (data/set-deform-keyframe "default" "image" 0.0 [1 2 3 4 5 6] "linear")
                (data/update-slot "image" {:id "renamed"}))
        output (json/read-str (data/export-spine-json doc))
        imported (data/import-spine-json (data/export-spine-json doc))]
    (is (= "image" (get-in output ["slots" 0 "attachment"])))
    (is (= "image" (get-in imported [:animations 0 :deforms 0 :attachment])))
    (is (= [1.0 2.0 3.0 4.0 5.0 6.0] (get (data/sample-deforms imported "default" 0.0) "renamed")))))

;; Parent topology edits also guard private FFD on indirect linked meshes, including cross-skin descendants.
(deftest linked-grandchild-private-deform-topology-guard-test
  (let [source (-> (json/read-str (data/export-spine-json (mesh-document)))
                   (assoc-in ["skins" 0 "attachments" "image" "alias"] {"type" "linkedmesh" "parent" "image" "deform" false})
                   (update "skins" conj {"name" "alternate" "attachments" {"image" {"alias2" {"type" "linkedmesh" "parent" "alias" "deform" false}}}})
                   (assoc-in ["animations" "default" "attachments" "alternate" "image" "alias2" "deform"] [{"time" 0 "vertices" [1 2 3 4 5 6]}]))
        doc (data/import-spine-json (json/write-str source))
        edited (data/apply-mesh-edit doc "image" (mesh/split-edge (get-in doc [:slots 0 :attachment]) [0 1] 0.5))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"private deformation" (data/export-spine-json edited)))))

;; Shortening checks the final overlaid animation, preventing invalid skin switches without resurrecting deleted editable source keys.
(deftest preserved-inactive-duration-guard-test
  (let [source (json/read-str (data/export-spine-json (mesh-document)))
        source (-> source
                   (update "skins" conj (assoc (get-in source ["skins" 0]) "name" "winter"))
                   (assoc-in ["animations" "default" "attachments" "winter" "image" "image" "deform"] [{"time" 3.0 "vertices" [1 2 3 4 5 6]}]))
        doc (data/import-spine-json (json/write-str source))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"preserved inactive" (data/update-animation doc "default" {:duration 1.0})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"preserved inactive" (data/export-spine-json (assoc-in doc [:animations 0 :duration] 1.0)))))
  (let [doc (data/import-spine-json (data/export-spine-json (data/set-keyframe (mesh-document) "default" "root" "x" 3.0 50.0 "linear")))
        edited (-> doc (data/remove-keyframe "default" "root" "x" 3.0) (data/update-animation "default" {:duration 1.0}))]
    (is (= 1.0 (get-in (data/import-spine-json (data/export-spine-json edited)) [:animations 0 :duration])))))

;; Deleting then recreating a physics key owns its metadata and cannot resurrect deleted raw fields from the original file.
(deftest physics-replaced-key-metadata-test
  (let [source (assoc-in (json/read-str (data/export-spine-json (physics-document)))
                         ["animations" "default" "physics" "spring" "wind"] [{"time" 0.0 "value" 0.4 "deleted" true}])
        doc (-> (data/import-spine-json (json/write-str source))
                (data/remove-physics-keyframe "default" "spring" "wind" 0.0)
                (data/set-physics-keyframe "default" "spring" "wind" 0.0 0.6 "linear"))
        output (json/read-str (data/export-spine-json doc))]
    (is (nil? (get-in output ["animations" "default" "physics" "spring" "wind" 0 "deleted"])))
    (is (= 0.6 (get-in output ["animations" "default" "physics" "spring" "wind" 0 "value"])))))

;; Spine executes only contiguous order indices; exporting gaps or duplicate priorities must retain the editor's mixed constraint order.
(deftest export-contiguous-constraint-order-test
  (let [doc (-> (path-document)
                (data/add-transform {:id "copy" :bones ["follower"] :target "root" :order 3 :mix-x 0.2})
                (data/add-physics {:id "sway" :bone "follower" :order 3 :x 1.0})
                (data/update-path "follow" {:order 9}))
        exported (json/read-str (data/export-spine-json doc))
        imported (data/import-spine-json (json/write-str exported))]
    (is (= [0 1 2] [(get-in exported ["transform" 0 "order"]) (get-in exported ["physics" 0 "order"]) (get-in exported ["path" 0 "order"])]))
    (doseq [time [0.0 0.25 0.5 1.0]]
      (is (= (:transforms (data/evaluate-pose doc "default" time)) (:transforms (data/evaluate-pose imported "default" time)))))
    (let [removed (data/remove-transform doc "copy")
          output (json/read-str (data/export-spine-json removed))]
      (is (= [0 1] [(get-in output ["physics" 0 "order"]) (get-in output ["path" 0 "order"])])))))

(defn- switching-path-document []
  (let [source (json/read-str (data/export-spine-json (path-document)))
        alternate (update (get-in source ["skins" 0 "attachments" "curve-slot" "curve"]) "vertices"
                          #(mapv (fn [index value] (if (odd? index) (+ 100.0 value) value)) (range (count %)) %))]
    (data/import-spine-json
      (json/write-str
        (-> source
            (assoc-in ["skins" 0 "attachments" "curve-slot" "alternate"] alternate)
            (assoc-in ["animations" "default" "slots" "curve-slot" "attachment"]
                      [{"time" 0.25 "name" "alternate"} {"time" 0.5 "name" nil} {"time" 0.75 "name" "curve"}]))))))

;; Constraint evaluation follows the visible A/B/hidden path and rejects a missing dynamic slot sampler.
(deftest path-attachment-playback-test
  (let [doc (switching-path-document)]
    (doseq [[time expected] [[0.1 [50.0 0.0]] [0.3 [50.0 100.0]] [0.6 [0.0 0.0]] [0.9 [50.0 0.0]]]]
      (let [pose (data/evaluate-pose-with-slots doc "default" time slots/sample-slots)]
        (is (coll/every? true? (mapv near? expected (subvec (get-in pose [:transforms "follower"]) 4 6))))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"slot sampler" (data/evaluate-pose doc "default" 0.3)))))

;; Physics must sample path switching at every canonical replay step, including hidden intervals and sampler changes.
(deftest physics-path-attachment-replay-test
  (let [doc (data/add-physics (switching-path-document) {:id "sway" :bone "follower" :order 2 :y 1.0 :fps 30.0})
        calls (atom [])
        sampler (fn [document animation time] (swap! calls conj time) (slots/sample-slots document animation time))
        one-pass (data/evaluate-pose-with-slots-state doc "default" 0.9 nil sampler)
        cached (reduce (fn [result time] (data/evaluate-pose-with-slots-state doc "default" time (:state result) sampler))
                       nil [0.2 0.4 0.6 0.9])
        setup-sampler (fn [document _ _] (slots/sample-slots document nil 0.0))
        changed (data/evaluate-pose-with-slots-state doc "default" 0.9 (:state one-pass) setup-sampler)
        fresh (data/evaluate-pose-with-slots-state doc "default" 0.9 nil setup-sampler)]
    (is (= (:pose one-pass) (:pose cached)))
    (is (coll/every? #(coll/any? (partial near? %) @calls) [(/ 8.0 30.0) 0.5 (/ 23.0 30.0)]))
    (is (not= (:pose one-pass) (:pose fresh)))
    (is (= (:pose fresh) (:pose changed)))))

;; Core FFD and visible slot FFD use the same runtime approximation, preventing path/mesh authoring jumps.
(deftest deform-runtime-interpolation-consistency-test
  (let [doc (-> (mesh-document)
                (data/set-deform-keyframe "default" "image" 0.0 [0 0 0 0 0 0] {:type "bezier" :curve [0.1 0.8 0.9 0.2]})
                (data/set-deform-keyframe "default" "image" 1.0 [900 500 700 400 500 300] "linear"))]
    (doseq [time [0.0 0.1 0.233 0.57 0.9 1.0]]
      (is (= (data/sample-deforms doc "default" time) (:deforms (slots/sample-slots doc "default" time)))))))

;; Repeated preview frames reuse validation only for the same immutable identity; edits and evictions validate again.
(deftest pose-validation-cache-test
  (let [doc (data/new-document "validation-cache")
        edited (assoc-in doc [:bones 0 :x] 3.0)
        invalid (assoc-in doc [:bones 0 :x] ##NaN)
        validate data/validate-document
        validated (atom [])]
    (with-redefs [data/validate-document (fn [document] (swap! validated conj document) (validate document))]
      (data/evaluate-pose doc "default" 0.0)
      (data/evaluate-pose doc "default" 0.5)
      (is (= 1 (count @validated)))
      (data/evaluate-pose edited "default" 0.5)
      (is (= 2 (count @validated)))
      (is (thrown? clojure.lang.ExceptionInfo (data/evaluate-pose invalid "default" 0.5)))
      (is (thrown? clojure.lang.ExceptionInfo (data/evaluate-pose invalid "default" 0.5)))
      (is (= 4 (count @validated)))
      (doseq [value [4.0 5.0 6.0 7.0]]
        (data/evaluate-pose (assoc-in doc [:bones 0 :x] value) "default" 0.5))
      (data/evaluate-pose doc "default" 0.5)
      (is (= 9 (count @validated))))))
