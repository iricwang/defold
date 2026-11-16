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

(ns editor.skeletal-animation-atlas-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [editor.fs :as fs]
            [editor.skeletal-animation-atlas :as atlas]
            [editor.skeletal-animation-data :as data]
            [util.coll :as coll])
  (:import [java.awt.image BufferedImage]
           [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [javax.imageio ImageIO]))

(defn- document []
  (data/import-spine-json
    (json/write-str
      {"skeleton" {"spine" "4.2.43"} "bones" [{"name" "root"}]
       "slots" [{"name" "body" "bone" "root" "attachment" "walk"}]
       "skins" [{"name" "default" "attachments"
                 {"body" {"walk" {"path" "hero/walk" "sequence" {"count" 3 "digits" 2}}
                          "face" {"path" "ui/face"}
                          "mesh" {"type" "mesh" "path" "ui/mesh" "uvs" [0 0 1 0 1 1] "triangles" [0 1 2] "vertices" [0 0 8 0 8 8]}}}}
                {"name" "alternate" "attachments" {"body" {"same" {"path" "ui/face"}}}}]
       "animations" {"idle" {}}})))

(defn- png! [root name width height color]
  (let [file (io/file root (str name ".png"))
        image (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)]
    (.mkdirs (.getParentFile file))
    (dotimes [y height] (dotimes [x width] (.setRGB image x y (unchecked-int color))))
    (ImageIO/write image "png" file)
    file))

(defn- populate! [root]
  (doseq [name ["hero/walk01" "hero/walk02" "hero/walk03" "ui/face" "ui/mesh"]]
    (png! root name 8 8 0xff33aa88)))

;; Real PNG pages include every sequence frame and all skins, with preserved
;; names, disjoint padding, edge bleed and deterministic multi-page packing.
(deftest spine-atlas-pages-test
  (let [root (fs/create-temp-directory! "spine-atlas-pages")]
    (try
      (populate! root)
      (let [output (io/file root "export")
            result (atlas/export! (document) root output {:page-width 24 :page-height 24 :padding 1 :extrude 1})
            text (slurp (:atlas result))]
        (is (= 2 (count (:pages result))))
        (is (= #{"hero/walk01" "hero/walk02" "hero/walk03" "ui/face" "ui/mesh"} (set (coll/keys (:regions result)))))
        (is (= 3 (count (:files result))))
        (doseq [[name {:keys [page x y width height]}] (:regions result)]
          (let [^BufferedImage image (ImageIO/read (io/file output page))]
            (is (.contains text (str name "\n")))
            (is (= [8 8] [width height]))
            (is (= (unchecked-int 0xff33aa88) (.getRGB image x y)))
            (is (= (.getRGB image x y) (.getRGB image (dec x) (dec y))))
            (is (= 0 (.getRGB image (- x 2) (- y 2))))
            (is (<= (+ x width 2) (.getWidth image)))
            (is (<= (+ y height 2) (.getHeight image)))))
        (is (thrown? clojure.lang.ExceptionInfo
                     (atlas/export! (document) root output {})))
        (let [again (atlas/export! (document) root (io/file root "again") {:page-width 24 :page-height 24 :padding 1 :extrude 1})]
          (is (= (:regions result) (:regions again)))
          (is (= text (slurp (:atlas again))))))
      (finally (fs/delete-directory! root {:fail :silently})))))

;; Missing, oversize and symlinked assets fail before publication. Paths cannot
;; escape the project even when every other attachment is valid.
(deftest spine-atlas-input-boundaries-test
  (let [root (fs/create-temp-directory! "spine-atlas-boundary")
        project (doto (io/file root "project") .mkdirs)
        output (io/file root "output")]
    (try
      (is (thrown? clojure.lang.ExceptionInfo (atlas/export! (document) project output {})))
      (is (not (.exists output)))
      (populate! project)
      (is (thrown? clojure.lang.ExceptionInfo
                   (atlas/export! (document) project output {:page-width 8 :page-height 8})))
      (is (not (.exists output)))
      (let [image (png! root "outside" 8 8 0xff000000)
            target (io/file project "ui/face.png")]
        (.delete target)
        (Files/createSymbolicLink (.toPath target) (.toPath image) (make-array FileAttribute 0))
        (is (thrown? clojure.lang.ExceptionInfo (atlas/export! (document) project output {})))
        (is (not (.exists output))))
      (is (= #{"project" "outside.png"} (coll/into-> (.listFiles root) #{} (map #(.getName ^File %)))))
      (finally (fs/delete-directory! root {:fail :silently})))))
