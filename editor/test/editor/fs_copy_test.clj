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

(ns editor.fs-copy-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [editor.fs :as fs])
  (:import [java.nio.file CopyOption FileAlreadyExistsException Files LinkOption StandardCopyOption]
           [java.nio.file.attribute FileAttribute FileTime PosixFilePermissions]))

;; The fallback copies real bytes, timestamps and modes without native cloning.
;; Keep-existing behavior must still protect the destination from replacement.
(deftest clone-fallback-file-semantics-test
  (let [directory (.toFile (Files/createTempDirectory "defold-copy" (make-array FileAttribute 0)))
        source (io/file directory "source.gui")
        target (io/file directory "target.gui")
        copy-options (into-array CopyOption [StandardCopyOption/COPY_ATTRIBUTES])]
    (try
      (spit source "nodes { id: \"button\" }")
      (Files/setLastModifiedTime (.toPath source) (FileTime/fromMillis 1600000000000))
      (is (= [[source target]] (fs/copy-file! source target)))
      (is (= (slurp source) (slurp target)))
      (when (.contains (.supportedFileAttributeViews (.getFileSystem (.toPath source))) "posix")
        (Files/setPosixFilePermissions (.toPath source) (PosixFilePermissions/fromString "rwxr-x---"))
        (Files/delete (.toPath target))
        (#'fs/copy-file-content! (.toPath source) (.toPath target) copy-options)
        (is (= (slurp source) (slurp target)))
        (is (= 1600000000000 (.lastModified target)))
        (is (= (Files/getPosixFilePermissions (.toPath source) (make-array LinkOption 0))
               (Files/getPosixFilePermissions (.toPath target) (make-array LinkOption 0))))
        (spit target "keep this file")
        (is (thrown? FileAlreadyExistsException
                     (#'fs/copy-file-content! (.toPath source) (.toPath target) copy-options)))
        (is (= "keep this file" (slurp target)))
        (is (thrown? FileAlreadyExistsException (fs/copy-file! source target {:target :keep})))
        (is (= "keep this file" (slurp target)))
        (#'fs/copy-file-content! (.toPath source) (.toPath target)
                                 (into-array CopyOption [StandardCopyOption/COPY_ATTRIBUTES StandardCopyOption/REPLACE_EXISTING]))
        (is (= (slurp source) (slurp target))))
      (finally (fs/delete-directory! directory)))))
