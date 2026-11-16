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

(ns editor.skeletal-animation-runtime-export-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [editor.fs :as fs]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-io :as animation-io]
            [editor.skeletal-animation-runtime-export :as runtime-export]
            [editor.skeletal-animation-textures :as textures])
  (:import [java.awt.image BufferedImage]
           [javax.imageio ImageIO]))

;; Compatible 4.3 provenance changes only the runtime marker; required default
;; skin bones, paths, deforms, weighted vertices and keys remain identical.
(deftest compatible-runtime-version-test
  (let [source {"skeleton" {"spine" "4.3.26"}
                "bones" [{"name" "root"} {"name" "tail" "parent" "root" "skin" true}]
                "skins" [{"name" "default" "bones" ["tail"]
                          "attachments" {"tail" {"mesh" {"type" "mesh" "uvs" [0 0 1 1] "vertices" [1 1 0 0 1 1 1 1 1 1]}
                                                 "path" {"type" "path" "vertexCount" 6 "vertices" [0 0 1 0 2 0 3 0 4 0 5 0] "lengths" [2]}}}}]
                "animations" {"move" {"bones" {"tail" {"rotate" [{"time" 0 "value" 0 "curve" [0.1 0 0.5 1]} {"time" 1 "value" 25}]}}
                                      "attachments" {"default" {"tail" {"mesh" {"deform" [{"time" 0.5 "offset" 0 "vertices" [1 2]}]}}}}}}}
        result (runtime-export/for-runtime source)]
    (is (= "4.2.0" (get-in result ["skeleton" "spine"])))
    (is (= source (assoc-in result ["skeleton" "spine"] "4.3.26")))
    (is (identical? result (runtime-export/for-runtime result)))))

;; Unknown fields, enums and timeline forms must fail before newer semantics
;; can be silently relabeled as 4.2.
(deftest incompatible-runtime-feature-test
  (let [source {"skeleton" {"spine" "4.3.26"}}]
    (doseq [invalid [(assoc source "unknown" {})
                     (assoc source "bones" [{"name" "root" "inherit" "future-mode"}])
                     (assoc source "animations" {"a" {"bones" {"root" {"future" []}}}})
                     (assoc source "skins" [{"name" "unused" "attachments" {"slot" {"mesh" {"type" "mesh" "future" true}}}}])
                     (assoc source "animations" {"a" {"bones" {"root" {"rotate" [{"curve" {"type" "bezier"}}]}}}})
                     (assoc-in source ["skeleton" "spine"] "4.4.0")]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Spine 4.2 runtime export" (runtime-export/for-runtime invalid))))))

;; The real production entry point applies texture scale and runtime migration
;; together, without editing authoring provenance or original PNG pixels.
(deftest scaled-production-export-version-test
  (let [root (fs/create-temp-directory! "runtime-export")]
    (try
      (let [png (io/file root "part.png")
            _ (ImageIO/write (BufferedImage. 101 51 BufferedImage/TYPE_INT_ARGB) "png" png)
            doc (data/add-slot (data/new-document "tail") {:id "tail" :bone "root" :attachment {:path "/part.png" :width 101.0 :height 51.0}})
            source (assoc-in (data/export-spine-data doc) ["skeleton" "spine"] "4.3.26")
            document (textures/set-scale (data/import-spine-json (json/write-str source)) 0.3)
            bytes (slurp png :encoding "ISO-8859-1")
            result (animation-io/export-project! document root "generated")
            output (json/read-str (slurp (first (:files result))))]
        (is (= "4.2.0" (get-in output ["skeleton" "spine"])))
        (is (= (data/export-spine-data document) (assoc-in output ["skeleton" "spine"] "4.3.26")))
        (is (= [30 15] (textures/image-size (first (:texture-files result)))))
        (is (= bytes (slurp png :encoding "ISO-8859-1")))
        (is (= "4.3.26" (get-in (json/read-str (:spine-source document)) ["skeleton" "spine"])))
        (let [invalid (assoc document :spine-source (json/write-str (assoc source "future" {})))
              before (slurp (first (:files result)))]
          (is (thrown? clojure.lang.ExceptionInfo (animation-io/export-project! invalid root "generated")))
          (is (= before (slurp (first (:files result)))))
          (is (thrown? clojure.lang.ExceptionInfo (animation-io/export-project! invalid root "rejected")))
          (is (not (.exists (io/file root "rejected"))))))
      (finally (fs/delete-directory! root {:fail :silently})))))
