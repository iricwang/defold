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

(ns editor.engine-game-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dynamo.graph :as g]
            [editor.engine :as engine]
            [editor.engine.native-extensions :as native-extensions]
            [editor.fs :as fs]
            [integration.test-util :as test-util]
            [support.test-support :refer [with-clean-system]])
  (:import [com.defold.extender.client ExtenderClientCache ExtenderResource]
           [java.nio.file Files]
           [java.util.zip ZipEntry ZipOutputStream]))

;; Repeated play reuses the installed engine, but missing/changed files, archives and failed installs invalidate it.
(deftest installed-native-engine-cache
  (let [directory (.toFile (Files/createTempDirectory "engine-install-cache" (make-array java.nio.file.attribute.FileAttribute 0)))
        archive (io/file directory "engine.zip")
        project (io/file directory "project")
        filename (#'engine/dmengine-filename (engine/current-platform))
        descriptor {:id {:type :custom :version "first"} :engine-archive archive :extender-platform "test-platform"}
        write-archive! (fn [contents]
                         (with-open [zip (ZipOutputStream. (io/output-stream archive))]
                           (doseq [[name content] contents]
                             (.putNextEntry zip (ZipEntry. name))
                             (.write zip (.getBytes ^String content "UTF-8"))
                             (.closeEntry zip))))
        original-unpack @#'engine/unpack-build-zip!
        installs (atom 0)]
    (try
      (with-redefs [engine/installed-engine-cache (atom nil)
                    engine/unpack-build-zip! (fn [archive directory]
                                              (swap! installs inc)
                                              (original-unpack archive directory))]
        (write-archive! {filename "engine-one" "dmengine.pdb" "matching-symbols"})
        (let [executable (engine/install-engine! project descriptor)
              pdb (io/file (.getParentFile executable) "dmengine.pdb")
              modified (.lastModified executable)]
          (testing "unchanged archives and files avoid all extraction and preserve timestamps"
            (dotimes [_ 3] (is (= executable (engine/install-engine! project descriptor))))
            (is (= 1 @installs))
            (is (= modified (.lastModified executable))))
          (testing "a deleted PDB or modified executable is repaired"
            (Files/delete (.toPath pdb))
            (engine/install-engine! project descriptor)
            (is (= "matching-symbols" (slurp pdb)))
            (spit executable "externally-replaced-engine")
            (engine/install-engine! project descriptor)
            (is (= "engine-one" (slurp executable)))
            (is (= 3 @installs)))
          (testing "replacing the archive at its old path installs the new engine"
            (write-archive! {filename "engine-two-new" "dmengine.pdb" "new-symbols" "extra.txt" "new"})
            (engine/install-engine! project descriptor)
            (is (= "engine-two-new" (slurp executable)))
            (is (= "new-symbols" (slurp pdb)))
            (is (= 4 @installs)))
          (testing "engine identity and destination are part of the cache key"
            (engine/install-engine! project (assoc descriptor :id {:type :custom :version "second"}))
            (is (= 5 @installs))
            (let [other (engine/install-engine! (io/file directory "other-project") descriptor)]
              (is (not= executable other))
              (is (= "engine-two-new" (slurp other))))
            (is (= 6 @installs)))
          (testing "failed installations are never cached and a later valid archive recovers"
            (spit archive "invalid zip")
            (is (thrown? Exception (engine/install-engine! project descriptor)))
            (is (nil? @@#'engine/installed-engine-cache))
            (write-archive! {filename "recovered" "dmengine.pdb" "recovered-symbols"})
            (engine/install-engine! project descriptor)
            (is (= "recovered" (slurp executable))))))
      (finally (fs/delete-directory! directory)))))

;; macOS native-extension builds must enter Game, while sibling directories and arbitrary dev binaries stay external.
(deftest mac-native-engine-preview-selection
  (let [project (io/file "/tmp/game")
        unpack (io/file "/tmp/unpack")]
    (is (engine/mac-preview-engine? (io/file unpack "arm64-macos/bin/dmengine") project unpack))
    (is (engine/mac-preview-engine? (io/file project "build/arm64-osx/dmengine") project unpack))
    (is (not (engine/mac-preview-engine? (io/file "/tmp/unpack-other/dmengine") project unpack)))
    (is (not (engine/mac-preview-engine? (io/file project "build-other/dmengine") project unpack)))
    (is (not (engine/mac-preview-engine? (io/file "/tmp/dev/dmengine") project unpack)))))

;; macOS editor builds include the bridge in their cache key; Windows and exported project resources stay unchanged.
(deftest native-game-bridge-build-resources
  (with-clean-system
    (let [workspace (test-util/setup-workspace! "test/resources/empty_project")
          project (test-util/setup-project! workspace)
          cache (ExtenderClientCache. (.toFile (Files/createTempDirectory "game-bridge-cache" (make-array java.nio.file.attribute.FileAttribute 0))))]
      (g/with-auto-evaluation-context evaluation-context
        (doseq [platform ["arm64-macos" "x86_64-macos" "x86_64-win32"]]
          (let [resources (#'native-extensions/make-extender-resources project platform evaluation-context)
                bridge (filterv #(.startsWith (.getPath ^ExtenderResource %) "__defold_editor_game_bridge/") resources)]
            (if (= "x86_64-win32" platform)
              (is (zero? (count bridge)))
              (do
                (is (= #{"__defold_editor_game_bridge/ext.manifest" "__defold_editor_game_bridge/src/bridge.mm"}
                       (into #{} (map #(.getPath ^ExtenderResource %)) bridge)))
                (is (not= (.calcKey cache platform "sdk" resources)
                          (.calcKey cache platform "sdk" (java.util.ArrayList. (filterv #(not (.startsWith (.getPath ^ExtenderResource %) "__defold_editor_game_bridge/")) resources)))))))))))))
