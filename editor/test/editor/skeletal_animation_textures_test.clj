;; Copyright 2020-2026 The Defold Foundation
;; Copyright 2014-2020 King
;; Copyright 2009-2014 Ragnar Svensson, Christian Murray
;; Licensed under the Defold License version 1.0 (the "License"); you may
;; not use
;; this file except in compliance with the License.
;;
;; You may obtain a copy of the License, together with FAQs at
;; https://www.defold.com/license
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed
;; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
;; CONDITIONS OF ANY KIND, either express or implied. See the License for the
;; specific language governing permissions and limitations under the License.

(ns editor.skeletal-animation-textures-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [editor.fs :as fs]
            [editor.skeletal-animation-atlas :as atlas]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-io :as animation-io]
            [editor.skeletal-animation-texture-view :as texture-view]
            [editor.skeletal-animation-textures :as textures]
            [editor.skeletal-animation-view :as view]
            [editor.ui :as ui])
  (:import [java.awt.image BufferedImage]
           [javafx.scene.control Button Slider TextField]
           [javax.imageio ImageIO]))

(defn- fixture [root]
  (let [file (io/file root "images/part.png")
        image (BufferedImage. 81 43 BufferedImage/TYPE_INT_ARGB)]
    (.setRGB image 40 21 (unchecked-int 0xffff0000))
    (fs/create-parent-directories! file)
    (ImageIO/write image "png" file)
    (data/add-slot (data/new-document "hero") {:id "body" :bone "root"
                                               :attachment {:path "/images/part.png" :width 81.0 :height 43.0}})))

;; Persist arbitrary output ratios while rejecting corrupt/nonfinite values
;; at load.
(deftest scale-document-roundtrip-test
  (let [doc (textures/set-scale (data/new-document "test") 0.373712)]
    (is (= doc (data/read-json (data/write-json doc))))
    (is (= 0.373712 (textures/scale doc)))
    (is (= [30 16] (textures/dimensions 81 43 0.373712)))
    (is (= [1 1] (textures/dimensions 1 1 0.01)))
    (doseq [value [0 -1 1.01 ##NaN ##Inf "half" nil]]
      (is (thrown? clojure.lang.ExceptionInfo (textures/set-scale doc value)))))
  (is (thrown? clojure.lang.ExceptionInfo (data/read-json (json/write-str (assoc-in (data/new-document "invalid") [:texture-output :scale] 2))))))

;; Runtime exports reference derived pixels but keep geometry/IDs and source
;; bytes intact.
(deftest scaled-export-preserves-source-and-geometry-test
  (let [root (fs/create-temp-directory! "texture-output")]
    (try
      (let [doc (fixture root) source (io/file root "images/part.png")
            before (slurp source :encoding "ISO-8859-1")
            scaled (textures/set-scale doc 0.373712)
            result (animation-io/export-project! scaled root "generated")
            output (io/file root "generated/hero_textures/images/part.png")
            exported (json/read-str (slurp (first (:files result))))]
        (is (= [30 16] (textures/image-size output)))
        (is (= before (slurp source :encoding "ISO-8859-1")))
        (is (= (json/read-str (data/export-spine-json doc)) exported))
        (is (string/includes? (slurp (second (:files result))) "/generated/hero_textures/images/part.png"))
        (is (= [{:path "images/part.png" :size [81 43]}] (animation-io/texture-inventory scaled root)))
        (animation-io/export-project! (textures/set-scale doc 0.5) root "generated")
        (is (= [41 22] (textures/image-size output)))
        (let [original (animation-io/export-project! doc root "generated")]
          (is (= [] (:texture-files original)))
          (is (string/includes? (slurp (second (:files original))) "image: \"/images/part.png\"")))
        (is (= before (slurp source :encoding "ISO-8859-1"))))
      (finally (fs/delete-directory! root {:fail :silently})))))

;; Portable atlas pages must carry the same reduced pixel dimensions as game
;; exports.
(deftest portable-atlas-respects-texture-scale-test
  (let [root (fs/create-temp-directory! "texture-atlas")]
    (try
      (let [doc (textures/set-scale (fixture root) 0.5)
            result (atlas/export! doc root (io/file root "atlas") {:page-width 128 :page-height 128 :padding 2 :extrude 1})]
        (is (= [41 22] ((juxt :width :height) (get-in result [:regions "images/part"]))))
        (is (= [47 28] (textures/image-size (io/file root "atlas/page-001.png")))))
      (finally (fs/delete-directory! root {:fail :silently})))))

;; Image-sequence and inactive skin entries are included, and duplicate
;; paths count once.
(deftest inventory-includes-all-skins-and-sequences-test
  (let [root (fs/create-temp-directory! "texture-inventory")]
    (try
      (let [base (fixture root)
            source (json/read-str (data/export-spine-json base))
            attachment (get-in source ["skins" 0 "attachments" "body" "body"])
            sequence-source (-> source
                                (assoc-in ["skins" 0 "attachments" "body" "body" "path"] "images/frame")
                                (assoc-in ["skins" 0 "attachments" "body" "body" "sequence"] {"count" 2 "start" 1 "digits" 2})
                                (update "skins" conj {"name" "alternate" "attachments" {"body" {"body" attachment}}}))
            doc (assoc base :spine-source (json/write-str sequence-source))]
        ;; Imported source drives the preserved alternate/sequence records.
        (doseq [name ["frame01.png" "frame02.png"]]
          (io/copy (io/file root "images/part.png") (io/file root "images" name)))
        (let [imported (data/import-spine-json (json/write-str sequence-source))]
          (is (= #{"images/part.png" "images/frame01.png" "images/frame02.png"}
                 (set (mapv :path (animation-io/texture-inventory imported root))))))
        (is (map? doc)))
      (finally (fs/delete-directory! root {:fail :silently})))))

;; Dragging edits a draft only; Apply commits once and accepts non-preset
;; fractions.
(deftest continuous-ui-commits-only-on-apply-test
  (ui/run-now
    (let [doc (atom (data/new-document "ui")) calls (atom [])
          controls (texture-view/make-controls {:document (fn current [] @doc)
                                                :inventory (constantly [])
                                                :translate identity
                                                :apply (fn commit [factor] (swap! calls conj factor) (swap! doc textures/set-scale factor))})
          root (:root controls)
          ^Slider slider (.lookup root "#skeletal-texture-scale-slider")
          ^TextField field (.lookup root "#skeletal-texture-scale-percent")
          ^Button button (.lookup root "#skeletal-texture-scale-apply")]
      (.setValue slider 37.3712)
      (is (= [] @calls))
      (is (= 1.0 (textures/scale @doc)))
      (.fire button)
      (is (= 1 (count @calls)))
      (is (< (abs (- 0.373712 (textures/scale @doc))) 1.0e-12))
      (.setText field "0")
      (.fire button)
      (is (= 1 (count @calls)))
      (.setText field "50.125")
      (.fire button)
      (is (= 0.50125 (textures/scale @doc))))))

;; Reduced preview pixels are cached and broken sources cannot stop UI pulses.
(deftest scaled-preview-cache-and-invalid-image-test
  (let [root (fs/create-temp-directory! "texture-preview")]
    (try
      (fixture root)
      (ui/run-now
        (let [context {:project-root root :images (atom {}) :texture-scale 0.5}
              image (#'view/image! context "/images/part.png")]
          (is (= [41.0 22.0] [(.getWidth image) (.getHeight image)]))
          (is (identical? image (#'view/image! context "/images/part.png")))
          (with-open [stream (io/output-stream (io/file root "images/broken.png"))]
            (.write stream (byte-array (mapv unchecked-byte [-119 80 78 71 13 10 26 10]))))
          (is (thrown? java.io.IOException (ImageIO/read (io/file root "images/broken.png"))))
          (is (nil? (#'view/image! context "/images/broken.png")))))
      (finally (fs/delete-directory! root {:fail :silently})))))
