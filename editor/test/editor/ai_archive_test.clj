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
            [util.coll :as coll]
            [util.digest :as digest])
  (:import [java.lang Thread$State]))

(defn- temp-root []
  (.toFile (java.nio.file.Files/createTempDirectory "ai-archive-test" (make-array java.nio.file.attribute.FileAttribute 0))))

;; A Windows reader cannot cause a partial overwrite or leaked temp file.
;; Once it releases the file, the same save must succeed.
(deftest locked-canvas-replacement-test
  (when (= "\\" java.io.File/separator)
    (let [root (temp-root)
          gui "/locked.gui"
          before {:sessions {"action" {:draft "before"}}}
          after {:sessions {"action" {:draft "after"}}}]
      (try
        (archive/save-canvas! root gui before)
        (let [file (io/file (.getParentFile (archive/canvas-file root gui)) "canvas.json")
              original (slurp file)]
          (with-open [_reader (io/input-stream file)]
            (is (thrown? java.nio.file.AccessDeniedException
                         (archive/save-canvas! root gui after)))
            (is (= original (slurp file)))))
        (archive/save-canvas! root gui after)
        (is (= after (archive/load-canvas root gui)))
        (let [directory (.getParentFile (archive/canvas-file root gui))]
          (is (= "after" (get-in (json/read-str (slurp (io/file directory "canvas.json")) :key-fn keyword) [:sessions :action :draft])))
          (is (coll/not-any? #(string/ends-with? (.getName ^java.io.File %) ".tmp") (.listFiles directory))))
        (finally (fs/delete-directory! root))))))

;; A real Windows sharing violation is retried after the reader releases,
;; without requiring the user to repeat the save.
(deftest transient-reader-replacement-test
  (when (= "\\" java.io.File/separator)
    (let [root (temp-root)
          started (promise)
          save-task (atom nil)]
      (try
        (archive/save-canvas! root "/test.gui" {:sessions {}})
        (let [file (io/file (.getParentFile (archive/canvas-file root "/test.gui")) "canvas.json")]
          (with-open [_reader (io/input-stream file)]
            (reset! save-task (future
                                (deliver started (Thread/currentThread))
                                (archive/save-canvas! root "/test.gui" {:sessions {"a" {:draft "saved"}}})))
            (let [writer (deref started 5000 nil)]
              (is (some? writer))
              (when writer
                ;; Release only after observing the failed move's backoff.
                (let [deadline (+ (System/nanoTime) 1000000000)]
                  (loop []
                    (when (and (not= Thread$State/TIMED_WAITING (.getState ^Thread writer))
                               (not (realized? @save-task))
                               (< (System/nanoTime) deadline))
                      (Thread/yield)
                      (recur))))
                (is (= Thread$State/TIMED_WAITING (.getState ^Thread writer)))))))
        (is (not= ::timeout (deref @save-task 5000 ::timeout)))
        (is (= "saved" (get-in (archive/load-canvas root "/test.gui") [:sessions "a" :draft])))
        (finally
          (when-let [task @save-task] (future-cancel task))
          (fs/delete-directory! root))))))

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

;; Offline refresh preserves legacy history and user documents.
(deftest local-library-migration-test
  (let [root (temp-root)]
    (try
      (archive/ensure-project! root "project")
      (let [identity-file (io/file root "ai-workspace/project.json")
            original (json/read-str (slurp identity-file) :key-fn keyword)
            notes (io/file root "ai-workspace/notes.md")
            legacy (io/file root "ai-workspace/sync.json")]
        (spit identity-file (json/write-str (assoc original :storage "project-local-with-codexcamp-backup")))
        (spit notes "local edit")
        (spit legacy "legacy sync receipt")
        (let [lock (io/file root "ai-workspace/project/.work.lock")
              hidden (io/file root "ai-workspace/.private/state.json")]
          (io/make-parents lock)
          (io/make-parents hidden)
          (spit lock "task lock")
          (spit hidden "private record"))
        (archive/refresh-local! root)
        (is (coll/not-any? #(re-find #"(?:^|/)\." (:path %)) (archive/manifest root)))
        (is (= (assoc original :storage "project-local") (json/read-str (slurp identity-file) :key-fn keyword)))
        (is (= "local edit" (slurp notes)))
        (is (= "legacy sync receipt" (slurp legacy)))
        (is (not (string/includes? (slurp (io/file root "ai-workspace/index.md")) "CodexCamp 保存对应备份")))
        (spit (io/file root "ai-workspace/README.md") "My custom library")
        (.delete notes)
        (archive/refresh-local! root)
        (is (= "My custom library" (slurp (io/file root "ai-workspace/README.md"))))
        (is (not (.exists notes)) "Deleted local files are not silently restored by remote sync"))
      (finally (fs/delete-directory! root)))))

;; Local versions survive missing originals; damaged copies are rejected.
(deftest local-artifact-versions-test
  (let [root (temp-root) source (io/file root "proposal.md")
        reference {:source (.getCanonicalPath source) :path "@referenced/proposal"}
        origin {:threadId "staff" :turnId "turn"}]
    (try
      (spit source "version one")
      (let [first-version (archive/receive-artifact! root reference origin)]
        (spit source "version two")
        (let [second-version (archive/receive-artifact! root reference origin)]
          (is (not= (:sha256 first-version) (:sha256 second-version)))
          (is (= "version one" (slurp (io/file root (:path first-version)))))
          (.delete source)
          (is (= second-version (archive/receive-artifact! root reference origin)))
          (spit (io/file root (:path second-version)) "corrupt")
          (is (thrown? Exception (archive/receive-artifact! root reference origin)))))
      (finally (fs/delete-directory! root)))))

;; Task attachments reject missing, changed and out-of-project files.
(deftest local-task-attachments-test
  (let [root (temp-root) outside (temp-root) source (io/file outside "说明.md")]
    (try
      (spit source "input")
      (let [record (archive/import-file! root "attachments" source {})
            attachment {:path (.getCanonicalPath ^java.io.File (:file record)) :name "说明.md" :sha256 (:sha256 record)}
            text (archive/attachment-text root [attachment])]
        (is (string/includes? text "本地任务附件"))
        (is (string/includes? text "说明.md"))
        (is (string/includes? text (:sha256 record)))
        (is (thrown? Exception (archive/attachment-text root [{:path (.getCanonicalPath source)}])))
        (spit (:file record) "changed")
        (is (thrown? Exception (archive/attachment-text root [attachment])))
        (.delete ^java.io.File (:file record))
        (is (thrown? Exception (archive/attachment-text root [attachment]))))
      (is (= "" (archive/attachment-text root [])))
      (is (thrown? Exception (archive/local-file root "https://example.com/remote.md")))
      (is (thrown? Exception (archive/local-file root "../escaped.md")))
      (finally (fs/delete-directory! root) (fs/delete-directory! outside)))))

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

;; Git's text conversions must not change the immutable byte identity, while
;; editable library files retain the user's own attributes and line endings.
(deftest archive-git-attributes-test
  (let [root (temp-root)
        attributes (io/file root "ai-workspace/.gitattributes")
        user-rules "*.md text eol=crlf\r\n/library/** text"]
    (try
      (io/make-parents attributes)
      (spit attributes user-rules :encoding "UTF-8")
      (archive/ensure-project! root nil)
      (let [saved (slurp attributes :encoding "UTF-8")]
        (is (string/starts-with? saved user-rules))
        (doseq [category ["attachments" "artifacts" "deliveries"]]
          (is (string/includes? saved (str "/" category "/files/** -text\n"))))
        (archive/ensure-project! root nil)
        (is (= saved (slurp attributes :encoding "UTF-8")))
        (spit attributes (string/replace saved #"\r?\n" "\r\n") :encoding "UTF-8")
        (let [crlf (slurp attributes :encoding "UTF-8")]
          (archive/ensure-project! root nil)
          (is (= crlf (slurp attributes :encoding "UTF-8")))))
      (is (not (.exists (io/file root ".gitattributes"))))
      (finally (fs/delete-directory! root)))))

;; CRLF, LF, UTF-8 and binary inputs must keep their exact bytes, hash and size
;; across both initial import and later deduplicated imports.
(deftest immutable-file-bytes-test
  (let [root (temp-root)
        source (io/file root "input.md")]
    (try
      (doseq [category ["attachments" "artifacts" "deliveries"]
              bytes [(.getBytes "设计\r\n方案\r\n" "UTF-8")
                     (.getBytes "设计\n方案\n" "UTF-8")
                     (byte-array [0 13 10 -1 -128 65])]]
        (with-open [out (io/output-stream source)] (.write out ^bytes bytes))
        (let [record (archive/import-file! root category source {:task "first"})
              repeated (archive/import-file! root category source {:task "second"})]
          (is (= (vec bytes) (vec (java.nio.file.Files/readAllBytes (.toPath ^java.io.File (:file record))))))
          (is (= (alength ^bytes bytes) (:size record)))
          (is (= (digest/stream->sha256-hex (java.io.ByteArrayInputStream. bytes)) (:sha256 record)))
          (is (= (:sha256 record) (:sha256 repeated)))
          (is (= [{:task "first"} {:task "second"}] (:origins repeated)))))
      (is (.isFile (io/file root "ai-workspace/.gitattributes")))
      (finally (fs/delete-directory! root)))))

;; Oversized sources must fail before allocating a snapshot or creating an
;; archive entry, including sparse files with little physical disk use.
(deftest oversized-immutable-file-rejected-test
  (let [root (temp-root)
        source (io/file root "oversized.md")]
    (try
      (with-open [file (java.io.RandomAccessFile. source "rw")]
        (.setLength file (inc (* 64 1024 1024))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"超过 64 MB"
                            (archive/import-file! root "artifacts" source {})))
      (is (not (.exists (io/file root "ai-workspace"))))
      (finally (fs/delete-directory! root)))))

;; Reusing a corrupt immutable object must fail without replacing its bytes or
;; receipt; importing that same object must not legitimize it under a new hash.
(deftest corrupt-immutable-file-rejected-test
  (let [root (temp-root)
        source (io/file root "proposal.md")]
    (try
      (spit source "version one\r\n" :encoding "UTF-8")
      (let [record (archive/import-file! root "artifacts" source {:task "first"})
            receipt (io/file root "ai-workspace/artifacts/records"
                             (str (:sha256 record) "-" (subs (digest/string->sha256-hex (:name record)) 0 12) ".json"))
            original-receipt (slurp receipt)
            corrupt "version two\r\n"]
        (spit (:file record) corrupt :encoding "UTF-8")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"归档副本已损坏"
                              (archive/import-file! root "artifacts" source {:task "second"})))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"归档源文件已损坏"
                              (archive/import-file! root "artifacts" (:file record) {:task "third"})))
        (is (= corrupt (slurp (:file record) :encoding "UTF-8")))
        (is (= original-receipt (slurp receipt)))
        (is (= 1 (count (.listFiles (io/file root "ai-workspace/artifacts/files"))))))
      (finally (fs/delete-directory! root)))))
