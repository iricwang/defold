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


(ns editor.ai-archive-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [editor.ai-archive :as archive]
            [editor.fs :as fs]
            [util.digest :as digest]))

(defn- temp-root []
  (.toFile (java.nio.file.Files/createTempDirectory "ai-archive-test" (make-array java.nio.file.attribute.FileAttribute 0))))

;; Legacy records survive migration; local links survive a directory move.
(deftest migrate-and-relocate-test
  (let [root (temp-root) moved (temp-root)
        source (io/file root "input.png")
        legacy (io/file root ".internal" "ai-chat" (str (digest/string->sha256-hex "/test.gui") ".edn"))]
    (try
      (spit source "image bytes")
      (io/make-parents legacy)
      (spit legacy (pr-str {:sessions {"action-1" {:thread-id "thread" :draft "unfinished" :attachments [{:id "attachment" :name "input.png" :path (str source)}]}}
                            :canvas {:nodes []}}))
      (archive/migrate-legacy! root)
      (let [state (archive/load-canvas root "/test.gui")
            attachment (get-in state [:sessions "action-1" :attachments 0 :path])]
        (is (.isFile legacy))
        (is (.contains attachment "ai-workspace"))
        (is (= "image bytes" (slurp attachment)))
        (archive/save-canvas! root "/test.gui" (assoc state :secret "DO-NOT-SAVE"))
        (is (not (.contains (slurp (archive/canvas-file root "/test.gui")) "DO-NOT-SAVE"))))
      (fs/copy-directory! (io/file root "ai-workspace") (io/file moved "ai-workspace"))
      (let [restored (archive/load-canvas moved "/test.gui")]
        (is (= "unfinished" (get-in restored [:sessions "action-1" :draft])))
        (is (.startsWith (get-in restored [:sessions "action-1" :attachments 0 :path]) (.getCanonicalPath moved)))
        (is (= #{"thread"} (archive/saved-thread-ids moved))))
      (finally (fs/delete-directory! root) (fs/delete-directory! moved)))))

;; Recent responses may omit old turns; archive merge preserves history.
(deftest history-is-incremental-test
  (let [root (temp-root)
        snapshot {:thread {:id "thread" :turns [{:id "one" :status "completed" :items [{:id "msg" :type "agentMessage" :text "first"}]}]}}]
    (try
      (archive/save-history! root snapshot)
      (archive/save-history! root (assoc-in snapshot [:thread :turns] [{:id "two" :status "completed" :items [{:id "next" :type "agentMessage" :text "second"}]}]))
      (let [file (io/file root "ai-workspace" "conversations" (digest/string->sha256-hex "thread") "history.json")
            value (json/read-str (slurp file) :key-fn keyword)]
        (is (= ["one" "two"] (mapv :id (:turns value))))
        (is (.contains (slurp (io/file (.getParentFile file) "messages.md")) "first")))
      (finally (fs/delete-directory! root)))))

;; Uploads restore missing files, and simultaneous edits preserve both versions.
(deftest bidirectional-sync-and-conflict-test
  (let [root (temp-root) objects (atom {})
        remote (atom {:revision 0 :files {}})
        api (fn fake-api [_path body]
              (if body
                (swap! remote (fn commit [r] {:revision (inc (:revision r)) :files (reduce #(assoc %1 (:path %2) (dissoc %2 :path)) (:files r) (:files body))}))
                (json/read-str (json/write-str @remote) :key-fn keyword)))
        upload (fn store-object [path file] (swap! objects assoc (last (string/split path #"=")) (java.nio.file.Files/readAllBytes (.toPath file))))
        download (fn read-object [path] (get @objects (last (string/split path #"="))))]
    (try
      (archive/ensure-project! root "project")
      (let [file (io/file root "ai-workspace" "notes.txt")]
        (spit file "v1")
        (is (= "synced" (:status (archive/sync! root "project" api upload download))))
        (.delete file)
        (archive/sync! root "project" api upload download)
        (is (= "v1" (slurp file)))
        (spit file "local edit")
        (swap! remote assoc-in [:files "ai-workspace/notes.txt" :sha256] "remote-edit-hash")
        (is (= "conflict" (:status (archive/sync! root "project" api upload download))))
        (is (= "local edit" (slurp file)))
        (is (= "remote-edit-hash" (get-in @remote [:files "ai-workspace/notes.txt" :sha256])))
        (swap! remote assoc-in [:files "game/source.gui"] {:sha256 "bad"})
        (is (thrown? Exception (archive/sync! root "project" api upload download))))
      (finally (fs/delete-directory! root)))))

;; Flat pre-canvas sessions must still use the normal canvas/session fallback.
(deftest flat-legacy-session-test
  (let [root (temp-root)]
    (try
      (is (= {:thread-id "legacy"} (archive/archive-state-files! root {:thread-id "legacy"})))
      (finally (fs/delete-directory! root)))))

;; Game edits get new backups; deletion preserves the previous archived copy.
(deftest delivered-resource-reconciliation-test
  (let [root (temp-root) gui (io/file root "screen.gui")
        output {:gui "/screen.gui" :atlas "/screen.atlas" :paths #{"/screen.gui"}}]
    (try
      (spit gui "version one")
      (archive/save-delivery! root output)
      (let [index (io/file root "ai-workspace" "deliveries" (str (digest/string->sha256-hex "/screen.gui") ".json"))
            original (first (:files (json/read-str (slurp index) :key-fn keyword)))]
        (spit gui "version two")
        (archive/refresh-deliveries! root)
        (let [second (first (:files (json/read-str (slurp index) :key-fn keyword)))]
          (is (not= (:sha256 original) (:sha256 second)))
          (is (= "version one" (slurp (io/file root (:archivePath original)))))
          (is (= "version two" (slurp (io/file root (:archivePath second)))))
          (.delete gui)
          (archive/refresh-deliveries! root)
          (is (:missing (first (:files (json/read-str (slurp index) :key-fn keyword)))))))
      (finally (fs/delete-directory! root)))))
