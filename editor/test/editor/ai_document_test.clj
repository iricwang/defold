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


(ns editor.ai-document-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [editor.ai-canvas :as canvas]
            [editor.ai-document :as document]
            [editor.ai-project-browser :as browser]
            [editor.ai-workbench :as workbench]
            [editor.fs :as fs])
  (:import [java.nio.file Files]))

;; Moving preserves identity; camera edits do not change semantic revisions.
(deftest document-round-trip-test
  (let [root (.toFile (Files/createTempDirectory "ai-document-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (browser/ensure-library! root)
      (let [path (document/create-document! root "ai-workspace/library/01-concept" "核心玩法")
            doc (document/read-document root path)
            model (document/model doc nil)
            next (document/save-document! root path doc (assoc model :viewport {:zoom 1.2 :x 90 :y 30}))
            moved "/ai-workspace/library/02-world/新名称.aicanvas"]
        (is (= doc next))
        (is (= 3 (count (set (mapv :id (:nodes model))))))
        (is (= :document (:workflow model)))
        (is (thrown? java.nio.file.FileAlreadyExistsException (document/create-document! root "ai-workspace/library/01-concept" "核心玩法")))
        (Files/move (.toPath (io/file root (subs path 1))) (.toPath (io/file root (subs moved 1))) (make-array java.nio.file.CopyOption 0))
        (is (= (:id doc) (:id (document/read-document root moved))))
        (let [changed (canvas/update-node model (:id (first (:nodes model))) #(assoc % :prompt "探索循环"))
              saved (document/save-document! root moved next changed)]
          (is (= 2 (:revision saved)))
          (is (= saved (document/read-document root moved)))
          (is (thrown? Exception (document/save-document! root moved next model)))))
      (finally (fs/delete-directory! root)))))

;; Text replies export source-linked Markdown without GUI production.
(deftest text-proposal-adoption-test
  (let [root (.toFile (Files/createTempDirectory "ai-document-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (browser/ensure-library! root)
      (let [path (document/create-document! root "ai-workspace/library/01-concept" "循环")
            model (document/model (document/read-document root path) nil)
            [a r s] (mapv :id (:nodes model))
            snapshot {:thread {:turns [{:id "turn" :status "completed" :items [{:type "agentMessage" :phase "final" :text "# 核心循环\n探索 → 战斗 → 成长"}]}]}}
            candidates (canvas/snapshot-candidates snapshot "thread")
            adopted (-> model (canvas/merge-candidates a candidates)
                        (canvas/update-node r #(assoc % :chosen (:id (first candidates))))
                        (canvas/adopt-candidate r))
            node (canvas/node-by-id adopted s)
            output (document/export-document! root path node)]
        (is (= s (:selected adopted)))
        (is (= "thread/turn" (:source-candidate node)))
        (is (.contains (slurp (io/file root (subs output 1))) "探索 → 战斗"))
        (is (.contains (slurp (io/file root (subs output 1))) "候选: thread/turn"))
        (is (not= output (document/export-document! root path node)))
        (is (.startsWith (workbench/message-text "目标" {:workflow :document}) "[方案创作]"))
        (is (= "/canvas/stable" (:guiPath (workbench/canvas-metadata {:routing-path "/canvas/stable" :path path :canvasCard {:id a :type "action"}}))))
        (is (= 1 (count (get-in (workbench/card-snapshot {:thread {:turns [{:canvasContext {:guiPath "/canvas/stable" :cardId a}} {:canvasContext {:guiPath "/other" :cardId a}}]}} "/canvas/stable" a) [:thread :turns])))))
      (finally (fs/delete-directory! root)))))

;; Invalid paths, versions and duplicate IDs cannot overwrite documents.
(deftest invalid-document-test
  (let [root (.toFile (Files/createTempDirectory "ai-document-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (browser/ensure-library! root)
      (is (thrown? Exception (document/create-document! root "ai-workspace/library/01-concept/../../.." "escape")))
      (is (thrown? Exception (document/create-document! root "ai-workspace/library/01-concept" "../escape")))
      (let [path (document/create-document! root "ai-workspace/library/01-concept" "版本")
            file (io/file root (subs path 1))
            doc (document/read-document root path)
            future-doc (assoc doc :schemaVersion 99 :futureData {:keep true})]
        (spit file (json/write-str future-doc))
        (is (= future-doc (document/read-document root path)))
        (is (thrown? Exception (document/save-document! root path future-doc (document/model doc nil))))
        (is (= future-doc (document/read-document root path)))
        (spit file (json/write-str (assoc doc :nodes [(first (:nodes doc)) (first (:nodes doc))])))
        (is (thrown? Exception (document/read-document root path))))
      (finally (fs/delete-directory! root)))))
