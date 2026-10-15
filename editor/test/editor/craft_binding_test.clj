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

(ns editor.craft-binding-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [editor.craft-binding :as binding]
            [editor.editor-extensions.server :as ext.server]
            [editor.ui :as ui])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.util Base64]
           [java.util.concurrent CompletableFuture]))

(defn- request [token body]
  {:headers {"authorization" (str "Bearer " token)}
   :body (ByteArrayInputStream. (.getBytes ^String (json/write-str body) StandardCharsets/UTF_8))})

;; A cancelled or busy request must retain the original binding; consent alone is not a commit.
(deftest consent-and-idle-are-required-to-switch
  (with-redefs [ui/do-run-later (fn [f] (f))
                ui/do-run-now (fn [f] (f))]
    (let [old {:id "previous" :address "127.0.0.1:1" :token "old-secret"}
          service {:id "defold" :project nil :editor-token "editor-secret"
                   :state (atom {:active old}) :idle? (constantly false)
                   :prepare! (fn [_ done!] (done! false))}
          routes (#'binding/routes service)]
      ((get-in routes ["/binding/request" "POST"])
       (request "" {:id (.repeat "a" 64) :address "127.0.0.1:17655"}))
      (let [ticket (get-in @(:state service) [:incoming :ticket])]
        (is (= "rejected" (get-in @(:state service) [:incoming :phase])))
        (is (thrown? Exception ((get-in routes ["/binding/commit" "POST"]) (request ticket {}))))
        (is (= old (:active @(:state service))))
        (swap! (:state service) assoc-in [:incoming :phase] "ready")
        (is (thrown? Exception ((get-in routes ["/binding/commit" "POST"]) (request ticket {}))))
        (is (= old (:active @(:state service))))
        ((get-in routes ["/binding/cancel" "POST"]) (request ticket {}))
        (is (nil? (:incoming @(:state service))))
        (is (= old (:active @(:state service))))))))

;; Remote file reads cannot reach session tokens, dot directories, another drive, or a parent folder.
(deftest resource-paths-stay-in-the-project
  (doseq [path ["../secret" "/.internal/editor.token" "C:/secret" "a\\b" "//host/share" "a/./b" "a//b"]]
    (is (thrown? Exception (#'binding/relative-path path)) path))
  (is (= "assets/gui/main.gui" (#'binding/relative-path "/assets/gui/main.gui"))))

;; Only the currently bound session can read resources; an old peer cannot release a newer binding.
(deftest session-tokens-are-scoped-and-revoked
  (let [service {:project nil :editor-token "editor-secret" :state (atom {:active {:id "craft" :token "new-secret"}})}
        routes (#'binding/routes service)]
    (is (thrown? Exception (#'binding/authorize! service (request "old-secret" {}))))
    (is (nil? (#'binding/authorize! service (request "new-secret" {}))))
    ((get-in routes ["/binding/release" "POST"]) (request "old-secret" {}))
    (is (= "craft" (get-in @(:state service) [:active :id])))
    ((get-in routes ["/binding/release" "POST"]) (request "new-secret" {}))
    (is (nil? (:active @(:state service))))))

;; File publication is immutable and restricted to generated assets, preserving authored game files.
(deftest uploads-cannot-overwrite-game-resources
  (let [root (.toFile (Files/createTempDirectory "craft-binding-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        service {:root root :project nil :editor-token "editor-secret" :state (atom {:active {:token "peer-secret"}})}
        upload (get-in (#'binding/routes service) ["/binding/upload" "POST"])
        data (.encodeToString (Base64/getEncoder) (.getBytes "first" StandardCharsets/UTF_8))]
    (try
      (is (thrown? Exception (upload (request "peer-secret" {:path "/game.project" :data data}))))
      (upload (request "peer-secret" {:path "/photocraft_generated/gui/image.png" :data data}))
      (upload (request "peer-secret" {:path "/photocraft_generated/gui/image.png" :data data}))
      (is (= "first" (slurp (io/file root "photocraft_generated/gui/image.png"))))
      (is (thrown? Exception (upload (request "peer-secret" {:path "/photocraft_generated/gui/image.png" :data "c2Vjb25k"}))))
      (finally
        (doseq [relative ["photocraft_generated/gui/image.png" "photocraft_generated/gui" "photocraft_generated"]]
          (.delete (io/file root relative)))
        (.delete root)))))

;; A request cancelled while waiting for the UI thread must never change the active peer.
(deftest cancellation-wins-over-a-queued-commit
  (let [old {:id "previous" :address "127.0.0.1:1" :token "old-secret"}
        service {:project nil :editor-token "editor-secret" :state (atom {:active old
                  :incoming {:ticket "ticket" :phase "ready" :expires Long/MAX_VALUE}}) :idle? (constantly true)}
        routes (#'binding/routes service)]
    (with-redefs [ui/do-run-now (fn [f] (swap! (:state service) dissoc :incoming) (f))]
      (is (thrown? Exception ((get-in routes ["/binding/commit" "POST"]) (request "ticket" {})))))
    (is (= old (:active @(:state service))))))

;; Workspace transfer excludes editor secrets and uses a content digest, rather than file size alone.
(deftest resource-manifest-is-bounded-and-content-addressed
  (let [root (.toFile (Files/createTempDirectory "craft-manifest-test" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (.mkdirs (io/file root ".internal"))
      (spit (io/file root "game.project") "abc")
      (spit (io/file root ".internal/editor.token") "secret")
      (spit (io/file root "work.gui.pcraft") "editable-source")
      (is (= [{:path "game.project" :size 3 :sha256 "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"}]
             (:files (#'binding/manifest {:root root :project-id "game"}))))
      (finally
        (doseq [relative ["game.project" ".internal/editor.token" ".internal" "work.gui.pcraft"]]
          (.delete (io/file root relative)))
        (.delete root)))))

;; Pending editor operations hold the workspace until their future completes, including failure paths.
(deftest asynchronous-editor-operations-block-rebinding
  (doseq [successful [true false]]
    (let [pending (CompletableFuture.)
          service {:project nil :editor-token "editor-secret" :state (atom {:active {:id "old" :token "old-secret"}})}]
      (with-redefs [ext.server/routes (fn [_ _] {"/eval" {"POST" (fn [_] pending)}})]
        (let [routes (#'binding/routes service)
              response ((get-in routes ["/binding/eval" "POST"]) (request "old-secret" {}))]
          (is (not (.isDone ^CompletableFuture response)))
          (is (= 1 (:file-operations @(:state service))))
          (is (thrown? Exception ((get-in routes ["/binding/request" "POST"])
                                 (request "" {:id (.repeat "a" 64) :address "127.0.0.1:17655"}))))
          (if successful
            (.complete pending {})
            (.completeExceptionally pending (Exception. "interrupted")))
          (is (zero? (:file-operations @(:state service)))))))))
