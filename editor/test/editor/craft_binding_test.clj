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
            [editor.craft-project :as craft-project]
            [editor.editor-extensions.server :as ext.server]
            [editor.ui :as ui])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.util Base64]
           [java.util.concurrent CompletableFuture]))

;; Named images update in place, while checksum mismatches and live references prevent deletion.
(deftest managed-asset-updates-preserve-external-edits-and-referenced-images
  (let [root (.toFile (Files/createTempDirectory "craft-managed-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        service {:root root :state (atom {})}
        resource "/photocraft_generated/test/images/Reward.png"
        image (io/file root (subs resource 1))
        atlas (io/file root "test.atlas")
        encoded (fn [text] (.encodeToString (Base64/getEncoder) (.getBytes ^String text StandardCharsets/UTF_8)))
        hash-text (fn [text] (apply str (into [] (map #(format "%02x" (bit-and 255 %)))
                                                (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes ^String text StandardCharsets/UTF_8)))))]
    (try
      (is (thrown? Exception (#'binding/managed-upload! service {:path "/game.project" :data (encoded "x")})))
      (#'binding/managed-upload! service {:path resource :data (encoded "first")})
      (is (= "first" (slurp image)))
      (is (thrown? Exception (#'binding/managed-upload! service {:path resource :expected (.repeat "0" 64) :data (encoded "second")})))
      (is (= "first" (slurp image)))
      (#'binding/managed-upload! service {:path resource :expected (hash-text "first") :data (encoded "second")})
      (is (= "second" (slurp image)))
      (spit atlas (str "images {image: \"" resource "\"}"))
      (is (= {:retained true} (#'binding/managed-upload! service {:path resource :expected (hash-text "second") :remove true})))
      (is (.isFile image))
      (.delete atlas)
      (is (= {:removed true} (#'binding/managed-upload! service {:path resource :expected (hash-text "second") :remove true})))
      (is (not (.exists image)))
      (finally
        (doseq [path [image atlas (io/file root "photocraft_generated/test/images") (io/file root "photocraft_generated/test") (io/file root "photocraft_generated") root]]
          (.delete ^java.io.File path))))))

(defn- request [token body]
  {:remote-address "127.0.0.1"
   :headers {"authorization" (str "Bearer " token)}
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
      (spit (io/file root ".editor_settings") "private settings")
      (is (= [{:path "game.project" :size 3 :sha256 "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"}]
             (:files (#'binding/manifest {:root root :project-id "game"}))))
      (finally
        (doseq [relative ["game.project" ".internal/editor.token" ".internal" "work.gui.pcraft" ".editor_settings"]]
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

;; A remote binding must not enable the local opener, and discovery must notice a local app exiting.
(deftest local-availability-tracks-discovery-independently-of-the-binding
  (let [service {:state (atom {:active {:id "remote" :address "192.0.2.1:17655"}})}
        status (atom {:app "PhotoCraft" :id "local" :bound "another-editor"})]
    (with-redefs-fn {#'binding/current (atom service)
                    #'binding/call! (fn [address _ _ _]
                                      (is (= "127.0.0.1:17655" address))
                                      @status)}
      (fn []
        (is (not (binding/local-available?)))
        (#'binding/discover-local! service)
        (is (binding/local-available?))
        (is (= "remote" (get-in @(:state service) [:active :id])))
        (swap! (:state service) assoc-in [:local-peer :seen-at] 0)
        (is (not (binding/local-available?)))
        (reset! status {:app "Other application"})
        (#'binding/discover-local! service)
        (is (not (binding/local-available?)))))))

;; Opening the captured tab path waits for consent and never opens a file after binding is cancelled.
(deftest local-open-waits-for-binding-before-opening-the-captured-resource
  (doseq [accepted [true false]]
    (let [service {:state (atom {:active {:address "127.0.0.1:17655" :token "scoped"}})}
          calls (atom [])]
      (with-redefs-fn {#'binding/current (atom service)
                      #'binding/bind! (fn [_ address automatic]
                                       (is (= "127.0.0.1:17655" address))
                                       (is (false? automatic))
                                       (CompletableFuture/completedFuture accepted))
                      #'binding/call! (fn [address route token body]
                                       (swap! calls conj [address route token body]))
                      #'ui/do-run-later (fn [f] (f))}
        (fn []
          (.get ^CompletableFuture (binding/open-local! "/main/right-clicked.gui" #(swap! calls conj :reopen-defold)))
          (is (= (if accepted
                   [["127.0.0.1:17655" "/binding/open" "scoped" {:path "/main/right-clicked.gui"}] :reopen-defold]
                   [])
                 @calls)))))))

;; Pending and active tickets identify the whole project without reading or changing GUI files.
(deftest project-identity-is-ticket-scoped-and-independent-of-open-resources
  (let [service {:project nil :project-id "X:/games/example" :editor-token "secret"
                 :state (atom {:active {:token "active"}
                               :incoming {:ticket "pending" :phase "ready" :expires Long/MAX_VALUE}})}
        endpoint (get-in (#'binding/routes service) ["/binding/project" "POST"])]
    (is (thrown? Exception (endpoint (request "unknown" {}))))
    (is (thrown? Exception (endpoint (request "" {}))))
    (doseq [token ["active" "pending"]]
      (is (= 200 (:status (endpoint (request token {}))))))
    (swap! (:state service) dissoc :incoming)
    (is (thrown? Exception (endpoint (request "pending" {}))))
    (is (= {:token "active"} (:active @(:state service))))))

;; Restarting the local client must release its stale identity without stealing another live binding.
(deftest local-client-restart-releases-only-the-replaced-listening-session
  (doseq [[address identity bound operations released]
          [["127.0.0.1:17655" "old" nil 0 true]
           ["127.0.0.1:17655" "new" nil 0 false]
           ["127.0.0.1:17656" "old" nil 0 false]
           ["192.0.2.1:17655" "old" nil 0 false]
           ["127.0.0.1:17655" "old" "another-editor" 0 false]
           ["127.0.0.1:17655" "old" nil 1 false]]]
    (let [active {:id identity :address address}
          service {:state (atom {:active active :file-operations operations})}]
      (with-redefs-fn {#'binding/call! (fn [_ _ _ _] {:app "PhotoCraft" :id "new" :bound bound})}
        (fn []
          (#'binding/discover-local! service)
          (is (= (when-not released active) (:active @(:state service))))
          (is (= "new" (get-in @(:state service) [:local-peer :id]))))))))

;; Legacy evaluation and structured control stay loopback-only even with an otherwise valid credential.
(deftest remote-evaluation-and-structured-control-are-rejected
  (let [service {:project nil :editor-token "editor-secret" :state (atom {:active {:token "peer-secret"}})}
        calls (atom 0)]
    (with-redefs [ext.server/routes (fn [_ _] {"/eval" {"POST" (fn [_] (swap! calls inc))}})]
      (let [routes (#'binding/routes service)]
        (doseq [[path token] [["/binding/eval" "peer-secret"] ["/craft/v1/project" "editor-secret"] ["/craft/v1/resource/open" "editor-secret"]]]
          (is (thrown? Exception
                       ((get-in routes [path "POST"])
                        (assoc (request token {}) :remote-address "192.0.2.4")))))
        (is (zero? @calls))
        (is (zero? (:file-operations @(:state service))))))))

;; Revealing an asset is an authenticated explicit route and executes through the UI dispatcher.
(deftest explicit-resource-open-uses-project-adapter-on-ui-thread
  (let [service {:project nil :editor-token "editor-secret" :state (atom {})}
        calls (atom [])
        ui-thread (atom false)
        endpoint (get-in (#'binding/routes service) ["/craft/v1/resource/open" "POST"])]
    (with-redefs [ui/do-run-now (fn [f] (reset! ui-thread true) (try (f) (finally (reset! ui-thread false))))
                  craft-project/open-resource! (fn [actual-service path]
                                                 (is @ui-thread)
                                                 (is (= service actual-service))
                                                 (swap! calls conj path)
                                                 {:schemaVersion 1 :path path :opened true})]
      (is (thrown? Exception (endpoint (request "wrong-token" {:path "/test.gui"}))))
      (is (= 200 (:status (endpoint (request "editor-secret" {:path "/test.gui"})))))
      (is (= ["/test.gui"] @calls)))))

;; Discovery contains no credential and an old editor session cannot delete a newer registration.
(deftest project-adapter-discovery-is-session-scoped
  (let [root (.toFile (Files/createTempDirectory "craft-discovery" (make-array java.nio.file.attribute.FileAttribute 0)))
        service {:root root :id "old-session" :port 12345 :editor-token "must-not-appear"}
        file (io/file root ".internal" "photocraft" "editor-adapter.json")]
    (#'binding/write-discovery! service)
    (is (= {:schemaVersion 1 :port 12345 :editorSessionId "old-session"} (json/read-str (slurp file) :key-fn keyword)))
    (#'binding/write-discovery! (assoc service :id "new-session" :port 12346))
    (#'binding/remove-discovery! service)
    (is (.isFile file))
    (#'binding/remove-discovery! (assoc service :id "new-session"))
    (is (not (.exists file)))))
