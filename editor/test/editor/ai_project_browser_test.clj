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


(ns editor.ai-project-browser-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [editor.ai-archive :as archive]
            [editor.ai-project-browser :as browser]
            [editor.fs :as fs])
  (:import [java.nio.file Files]))

;; Reopening preserves authored documents and stable sync hashes.
(deftest library-reopen-preserves-content-test
  (let [root (.toFile (Files/createTempDirectory "ai-library-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (archive/ensure-project! root nil)
      (browser/ensure-library! root)
      (let [file (io/file root "ai-workspace/library/01-concept/README.md")]
        (spit file "my game concept")
        (let [before (archive/manifest root)]
          (browser/ensure-library! root)
          (is (= before (archive/manifest root)))
          (is (= "my game concept" (slurp file)))
          (is (= (mapv :title browser/sections) (mapv :title (browser/entries root "ai-workspace/library"))))))
      (finally (fs/delete-directory! root)))))

;; Duplicate imports keep both resources in the sync inventory.
(deftest import-preserves-duplicates-test
  (let [root (.toFile (Files/createTempDirectory "ai-library-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        source (io/file root "reference.png")]
    (try
      (archive/ensure-project! root nil)
      (browser/ensure-library! root)
      (spit source "first")
      (is (= ["ai-workspace/library/03-characters/reference.png"]
             (browser/import-files! root "ai-workspace/library/03-characters" [source])))
      (spit source "second")
      (is (= ["ai-workspace/library/03-characters/reference-1.png"]
             (browser/import-files! root "ai-workspace/library/03-characters" [source])))
      (is (= "first" (slurp (io/file root "ai-workspace/library/03-characters/reference.png"))))
      (is (= "second" (slurp (io/file root "ai-workspace/library/03-characters/reference-1.png"))))
      (is (contains? (set (mapv :path (archive/manifest root))) "ai-workspace/library/03-characters/reference-1.png"))
      (is (thrown? Exception (browser/import-files! root "ai-workspace/artifacts" [source])))
      (finally (fs/delete-directory! root)))))

;; Browsing cannot escape the library through traversal or symlinks.
(deftest library-path-boundary-test
  (let [root (.toFile (Files/createTempDirectory "ai-library-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        outside (.toFile (Files/createTempDirectory "ai-library-outside" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (browser/ensure-library! root)
      (Files/createSymbolicLink (.toPath (io/file root "ai-workspace/library/external")) (.toPath outside) (make-array java.nio.file.attribute.FileAttribute 0))
      (is (= 10 (count (browser/entries root "ai-workspace/library"))))
      (is (thrown? Exception (browser/entries root "ai-workspace/library/external")))
      (is (thrown? Exception (browser/entries root "ai-workspace/..")))
      (finally
        (Files/deleteIfExists (.toPath (io/file root "ai-workspace/library/external")))
        (fs/delete-directory! root)
        (fs/delete-directory! outside)))))
