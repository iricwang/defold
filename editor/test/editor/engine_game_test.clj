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
            [clojure.test :refer [deftest is]]
            [dynamo.graph :as g]
            [editor.engine :as engine]
            [editor.engine.native-extensions :as native-extensions]
            [integration.test-util :as test-util]
            [support.test-support :refer [with-clean-system]])
  (:import [com.defold.extender.client ExtenderClientCache ExtenderResource]
           [java.nio.file Files]))

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
                (is (= #{"__defold_editor_game_bridge/ext.manifest" "__defold_editor_game_bridge/src/bridge.mm" "__defold_editor_game_bridge/src/audio.h"}
                       (into #{} (map #(.getPath ^ExtenderResource %)) bridge)))
                (is (not= (.calcKey cache platform "sdk" resources)
                          (.calcKey cache platform "sdk" (java.util.ArrayList. (filterv #(not (.startsWith (.getPath ^ExtenderResource %) "__defold_editor_game_bridge/")) resources)))))))))))))
