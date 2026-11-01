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


(ns editor.ai-production-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [editor.ai-production :as production]
            [editor.protobuf :as protobuf])
  (:import [com.dynamo.gamesys.proto AtlasProto$Atlas Gui$SceneDesc]
           [java.awt Color]
           [java.awt.image BufferedImage]
           [java.nio.file Files]
           [javax.imageio ImageIO]))

(defn fixture-image []
  (let [image (BufferedImage. 120 100 BufferedImage/TYPE_INT_ARGB)
        g (.createGraphics image)]
    (.setColor g Color/BLUE) (.fillRect g 10 10 30 20)
    (.setColor g Color/GREEN) (.fillRect g 70 60 20 30)
    (.dispose g)
    image))

;; Independent alpha components must preserve their real bounds, not a grid.
(deftest alpha-components-test
  (let [image (fixture-image)
        regions (production/detect-regions image)]
    (is (= [{:x 10 :y 10 :width 30 :height 20} {:x 70 :y 60 :width 20 :height 30}]
           (mapv #(select-keys % [:x :y :width :height]) regions)))
    (.flush image))
  (let [image (BufferedImage. 10 10 BufferedImage/TYPE_INT_RGB)]
    (is (= [] (production/detect-regions image))) (.flush image)))

;; Proposed names cannot escape a module; duplicates get stable unique IDs.
(deftest naming-and-bounds-test
  (let [region {:name "../BUY button" :role "button" :state "normal" :x 0 :y 0 :width 10 :height 10}
        plan {:version 1 :module "../../shop" :imageWidth 120 :imageHeight 100 :regions [region region]}
        normalized (production/normalize-plan plan 120 100)]
    (is (= "shop" (:module normalized)))
    (is (= ["shop_button_buy_button_normal" "shop_button_buy_button_normal_2"] (mapv :id (:regions normalized))))
    (is (thrown? Exception (production/normalize-plan (assoc-in plan [:regions 0 :x] -1) 120 100)))
    (is (thrown? Exception (production/normalize-plan (assoc-in plan [:regions 0 :width] 121) 120 100)))
    (is (thrown? Exception (production/normalize-plan plan 121 100)))))

;; Native GUI centers use bottom-left coordinates and retain editable boxes.
(deftest gui-coordinate-test
  (let [plan (production/normalize-plan {:version 1 :module "shop" :imageWidth 120 :imageHeight 100
                                         :regions [{:name "buy" :x 10 :y 10 :width 30 :height 20}]} 120 100)
        node (first (:nodes (production/gui-data plan "/test.atlas" 240 200)))]
    (is (= :type-box (:type node)))
    (is (= [50.0 160.0 0.0 1.0] (:position node)))
    (is (= [60.0 40.0 0.0 0.0] (:size node)))
    (is (= :size-mode-manual (:size-mode node)))))

;; Publication creates PNG/atlas/gui without replacing previous revisions.
(deftest native-publication-test
  (let [root (.toFile (Files/createTempDirectory "defold-ai-production-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        source (io/file root "fixture.png") image (fixture-image)]
    (try
      (spit (io/file root "game.project") "[project]\ntitle = AI production test\n")
      (ImageIO/write image "png" source)
      (let [plan {:version 1 :module "shop" :imageWidth 120 :imageHeight 100 :regions (production/detect-regions image)}
            result (production/publish! (.getPath root) source plan {:candidate "test"} [240 200])
            gui (protobuf/str->map-with-defaults Gui$SceneDesc (slurp (io/file root (subs (:gui result) 1))))
            atlas (protobuf/str->map-with-defaults AtlasProto$Atlas (slurp (io/file root (subs (:atlas result) 1))))
            manifest (json/read-str (slurp (io/file root (subs (:manifest result) 1))) :key-fn keyword)
            crop (ImageIO/read (io/file root (subs (get-in manifest [:assets 0 :file]) 1)))
            again (production/publish! (.getPath root) source plan {:candidate "test"} [240 200])]
        (is (= 2 (:count result)))
        (is (= 2 (count (:nodes gui))))
        (is (= 2 (count (:images atlas))))
        (is (= 30 (.getWidth crop)))
        (is (= (.getRGB image 10 10) (.getRGB crop 0 0)))
        (is (= "generated-review-required" (:status manifest)))
        (is (not= (:directory result) (:directory again)))
        (is (.isFile (io/file root (subs (:gui result) 1))))
        (.flush crop))
      (finally
        (.flush image)
        (doseq [file (reverse (file-seq root))] (io/delete-file file))))))

;; Incomplete turns and private analysis cannot supply production plans.
(deftest structured-plan-test
  (let [plan {:kind "defold-gui-split" :version 1 :sourceKey "candidate" :regions []}
        item {:type "agentMessage" :text (str "```json\n" (json/write-str plan) "\n```")}]
    (is (= plan (production/plan-from-snapshot {:thread {:turns [{:status "completed" :items [item]}]}})))
    (is (nil? (production/plan-from-snapshot {:thread {:turns [{:status "inProgress" :items [item]}]}})))
    (is (nil? (production/plan-from-snapshot {:thread {:turns [{:status "completed" :items [(assoc item :phase "analysis")]}]}})))))

;; Legacy AI field spellings are repaired without touching strings or comments.
(deftest generated-resource-format-test
  (let [result (production/validate-resource-text :gui
                                                  "nodes { id: \"fill_angle: keep\" type: TYPE_PIE perimeter_vertices: 32 fill_angle: 180 inner_radius: 2 } # inner_radius: keep\n")]
    (is (= 3 (count (:repairs result))))
    (is (= "fill_angle: keep" (get-in result [:data :nodes 0 :id])))
    (is (= 180.0 (get-in result [:data :nodes 0 :pie-fill-angle]))))
  (is (thrown? Exception (production/validate-resource-text :gui "nodes { imaginary_field: 1 }")))
  (is (thrown? Exception (production/validate-resource-text :atlas "images { nonexistent: \"x\" }"))))

;; Broken image references must be rejected before publication.
(deftest generated-bundle-reference-test
  (let [root (.toFile (Files/createTempDirectory "defold-bundle-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        gui-file (io/file root "screen.gui") atlas-file (io/file root "screen.atlas")]
    (try
      (spit gui-file "max_nodes: 10 nodes { id: \"a\" type: TYPE_BOX texture: \"components/missing\" } textures { name: \"components\" texture: \"/module/screen.atlas\" }")
      (spit atlas-file "images { image: \"/module/images/missing.png\" }")
      (is (thrown? Exception (production/validate-bundle! root "/module" "screen.gui" "screen.atlas")))
      (spit atlas-file "")
      (is (thrown? Exception (production/validate-bundle! root "/module" "screen.gui" "screen.atlas")))
      (finally (.delete gui-file) (.delete atlas-file) (.delete root)))))
