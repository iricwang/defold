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

(ns editor.skeletal-animation-mesh-reset-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-mesh :as mesh]
            [util.coll :as coll]))

(defn- fixture []
  (let [attachment {:type "mesh" :name "image" :path "/images/image.png"
                    :vertices [0.0 0.0 100.0 0.0 0.0 100.0]
                    :uvs [0.0 0.0 1.0 0.0 0.0 1.0] :triangles [0 1 2]
                    :weights [[{:bone "root" :weight 0.5 :x 0.0 :y 0.0} {:bone "joint" :weight 0.5 :x 0.0 :y 0.0}]
                              [{:bone "root" :weight 1.0 :x 100.0 :y 0.0}]
                              [{:bone "joint" :weight 1.0 :x 0.0 :y 100.0}]]}]
    (-> (data/new-document "reset")
        (data/add-bone {:id "joint" :parent "root"})
        (data/add-slot {:id "image" :bone "root" :attachment attachment})
        (data/add-slot {:id "other" :bone "root" :attachment attachment})
        (data/add-animation {:id "second" :duration 1.0 :tracks []})
        (data/set-deform-keyframe "default" "image" 0.0 [1.0 2.0 3.0 4.0 5.0 6.0 7.0 8.0] "linear")
        (data/set-deform-keyframe "second" "image" 0.0 [8.0 7.0 6.0 5.0 4.0 3.0 2.0 1.0] "linear")
        (data/set-deform-keyframe "default" "other" 0.0 [9.0 8.0 7.0 6.0 5.0 4.0 3.0 2.0] "linear"))))

;; Every reset-style tool removes all active animation FFD and weights from
;; its attachment. Other slots' deformation keys and metadata are unchanged.
(deftest reset-policy-test
  (let [document (fixture) attachment (get-in document [:slots 0 :attachment])]
    (doseq [edit [(mesh/reset-vertices attachment [-50.0 -50.0 50.0 50.0])
                 (mesh/generate attachment {})
                 (mesh/trace-alpha attachment {:width 2 :height 2 :alpha [255 255 255 255]} {})]]
      (let [updated (data/apply-mesh-edit document "image" edit)]
        (is (nil? (get-in updated [:slots 0 :attachment :weights])))
        (is (coll/every? #(not (coll/any? (fn [track] (= "image" (:slot track))) (:deforms %))) (:animations updated)))
        (is (= [9.0 8.0 7.0 6.0 5.0 4.0 3.0 2.0] (get (data/sample-deforms updated "default" 0.0) "other")))
        (is (= updated (data/read-json (data/write-json updated))))))))

;; Reset also clears preserved raw FFD on linked-mesh descendants across skins,
;; preventing export/skin switching from reviving arrays with the old topology.
;; Independent geometry in another skin and non-deform timelines are retained.
(deftest reset-source-and-linked-deforms-test
  (let [source (json/read-str (data/export-spine-json (fixture)))
        original (get-in source ["skins" 0 "attachments" "image" "image"])
        source (-> source
                   (assoc-in ["skins" 0 "attachments" "image" "alias"] {"type" "linkedmesh" "parent" "image" "deform" false})
                   (update "skins" conj {"name" "winter" "attachments" {"image" {"image" original
                                                                                  "alias2" {"type" "linkedmesh" "parent" "alias" "skin" "default" "deform" false}}}})
                   (assoc-in ["animations" "default" "attachments" "default" "image" "alias" "deform"] [{"time" 0 "vertices" [1 2 3 4 5 6 7 8]}])
                   (assoc-in ["animations" "default" "attachments" "winter" "image" "alias2" "deform"] [{"time" 0 "vertices" [1 2 3 4 5 6 7 8]}])
                   (assoc-in ["animations" "default" "attachments" "winter" "image" "image" "deform"] [{"time" 0 "vertices" [1 2 3 4 5 6 7 8]}])
                   (assoc-in ["animations" "default" "attachments" "default" "image" "image" "sequence"] [{"time" 0 "index" 1}])
                   (assoc-in ["animations" "default" "deform" "default" "image" "image"] [{"time" 0 "vertices" [1 2]}]))
        document (data/import-spine-json (json/write-str source))
        updated (data/apply-mesh-edit document "image" (mesh/reset-vertices (get-in document [:slots 0 :attachment]) [-50.0 -50.0 50.0 50.0]))
        raw (json/read-str (:spine-source updated))
        output (json/read-str (data/export-spine-json updated))]
    (doseq [[skin attachment] [["default" "image"] ["default" "alias"] ["winter" "alias2"]]]
      (is (nil? (get-in raw ["animations" "default" "attachments" skin "image" attachment "deform"])))
      (is (nil? (get-in output ["animations" "default" "attachments" skin "image" attachment "deform"]))))
    (is (nil? (get-in raw ["animations" "default" "deform" "default" "image" "image"])))
    (is (= [{"time" 0 "index" 1}] (get-in output ["animations" "default" "attachments" "default" "image" "image" "sequence"])))
    (is (= [{"time" 0 "vertices" [1 2 3 4 5 6 7 8]}] (get-in output ["animations" "default" "attachments" "winter" "image" "image" "deform"])))
    (is (= 8 (count (get-in output ["skins" 0 "attachments" "image" "image" "vertices"]))))
    (is (nil? (get (data/sample-deforms (data/select-skin updated "default") "default" 0.0) "image")))
    (is (= [1 2 3 4 5 6 7 8] (get (data/sample-deforms (data/select-skin updated "winter") "default" 0.0) "image")))))
