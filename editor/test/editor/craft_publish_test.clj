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

(ns editor.craft-publish-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [editor.craft-project :as craft-project]
            [editor.craft-publish :as publish]
            [util.digest :as digest])
  (:import [java.nio.file Files]
           [java.util UUID]))

(defn- hash-text [text]
  (str "sha256:" (digest/string->sha256-hex text)))

(def ^:private old-source "script: \"/old.gui_script\"")
(def ^:private new-source "script: \"/new.gui_script\"")

(defn- fixture []
  (let [root (.toFile (Files/createTempDirectory "craft-publish-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        target (io/file root "test.gui")
        state (atom {:source old-source :diskHash (hash-text old-source) :sceneHash (craft-project/scene-hash old-source)
                     :editorGraphRevision (hash-text old-source) :dirty false})
        applies (atom 0)
        releases (promise)
        request {:schemaVersion 1 :transactionId (str (UUID/randomUUID)) :planHash (hash-text "plan")
                 :path "/test.gui" :expectedDiskHash (hash-text old-source) :expectedEditorGraphRevision (hash-text old-source)
                 :editorSessionId "test-session" :changes {:nodes [] :textures [] :renames []} :resourceUpdates []}]
    (spit target old-source)
    {:root root :target target :state state :applies applies :releases releases :request request
     :service {:root root :id "test-session" :publish-state (atom {})
               :snapshot-fn (fn [_] @state)
               :claim-fn (constantly true)
               :release-fn #(deliver releases %)
               :apply-fn (fn [_] (swap! applies inc) {:frozenSource new-source})
               :save-fn (fn [_]
                          (spit target new-source)
                          (swap! state assoc :source new-source :diskHash (hash-text new-source)
                                 :sceneHash (craft-project/scene-hash new-source) :editorGraphRevision (hash-text new-source))
                          {:diskHash (hash-text new-source)})}}))

;; Reload omits explicit defaults without changing the native GUI's meaning.
(deftest scene-identity-normalizes-native-defaults-without-losing-content
  (let [explicit "nodes { id: \"n\" position { x: 0 y: 3 z: 0 } scale { x: 1 y: 1 z: 1 } color { x: 1 y: 1 z: 1 w: 1 } }"
        omitted "nodes { id: \"n\" position { y: 3 } scale {} color {} }"
        changed "nodes { id: \"n\" position { y: 4 } scale {} color {} }"]
    (is (= (craft-project/scene-hash explicit) (craft-project/scene-hash omitted)))
    (is (not= (hash-text explicit) (hash-text omitted)))
    (is (not= (craft-project/scene-hash omitted) (craft-project/scene-hash changed)))
    (testing "Schema fields and repeated order remain significant"
      (is (not= (craft-project/scene-hash "nodes { id: \"a\" } nodes { id: \"b\" }")
                (craft-project/scene-hash "nodes { id: \"b\" } nodes { id: \"a\" }")))
      (is (not= (craft-project/scene-hash omitted) (craft-project/scene-hash (str omitted " script: \"/logic.gui_script\"")))))
    (testing "Unknown schema fields are rejected, never silently omitted from the fingerprint"
      (is (thrown? com.google.protobuf.TextFormat$ParseException
                   (craft-project/scene-hash (str omitted " unknown_gui_field: 1")))))))

;; Retrying both prepare and apply must return one durable publication, including after restart.
(deftest durable-publication-is-idempotent
  (let [{:keys [service request applies releases]} (fixture)]
    (is (= "PREPARED" (:state (publish/prepare! service request))))
    (is (= "PREPARED" (:state (publish/prepare! service request))))
    (is (= "APPLYING" (:state (publish/apply! service request))))
    (is (= (:transactionId request) (deref releases 5000 :timeout)))
    (let [completed (publish/status service request)]
      (is (= "COMMITTED" (:state completed)))
      (is (= (hash-text new-source) (get-in completed [:receipt :diskHash])))
      (is (= completed (publish/apply! service request)))
      (is (= completed (publish/status (assoc service :publish-state (atom {})) request))))
    (is (= 1 @applies))))

;; An operation ID cannot be reused with altered content even when the caller keeps the same plan hash.
(deftest changed-request-cannot-reuse-id
  (let [{:keys [service request]} (fixture)]
    (publish/prepare! service request)
    (is (= "IDEMPOTENCY_MISMATCH"
           (try (publish/prepare! service (assoc request :extra "different"))
                (catch clojure.lang.ExceptionInfo error (:code (ex-data error))))))))

;; Unknown refresh safety, dirty targets, stale sessions and invalid rotations fail before any write.
(deftest preparation-enforces-version-and-undo-barriers
  (doseq [[change expected] [[#(assoc % :resourceUpdates [{:path "/new.png" :action "remove"}]) "UNDO_BARRIER_REQUIRED"]
                             [#(assoc % :expectedDiskHash (hash-text "changed")) "TARGET_CHANGED"]
                             [#(assoc % :editorSessionId "stale") "EDITOR_UNAVAILABLE"]
                             [#(assoc-in % [:changes :nodes] [{:id "n" :parent "" :kind "box" :values {:rotation [0 0 0 1]}}]) "INVALID_TRANSFORM"]]]
    (let [{:keys [service request target applies]} (fixture)]
      (is (= expected (try (publish/prepare! service (change request))
                           (catch clojure.lang.ExceptionInfo error (:code (ex-data error))))))
      (is (= old-source (slurp target)))
      (is (zero? @applies))))
  (let [{:keys [service request state]} (fixture)]
    (swap! state assoc :dirty true)
    (is (= "TARGET_DIRTY" (try (publish/prepare! service request)
                               (catch clojure.lang.ExceptionInfo error (:code (ex-data error))))))))

;; A later edit stays dirty and must not replace the frozen scene fingerprint in the receipt.
(deftest save-acknowledges-only-the-captured-version
  (let [{:keys [service request state releases]} (fixture)
        original-save (:save-fn service)
        service (assoc service :save-fn (fn [snapshot]
                                         (let [result (original-save snapshot)]
                                           (swap! state assoc :dirty true :sceneHash (hash-text "later") :editorGraphRevision (hash-text "later"))
                                           result)))]
    (publish/prepare! service request)
    (publish/apply! service request)
    (deref releases 5000 :timeout)
    (let [receipt (:receipt (publish/status service request))]
      (is (:editorAhead receipt))
      (is (= (craft-project/scene-hash new-source) (:sceneHash receipt)))
      (is (= (hash-text new-source) (get-in receipt [:targets 0 :editorGraphRevision]))))))

;; Failure after applying never pretends to commit or cancels away the recovery record.
(deftest failed-save-remains-recoverable-and-releases-gate
  (let [{:keys [service request releases]} (fixture)
        service (assoc service :save-fn (fn [_] (throw (ex-info "disk full" {:code "DISK_FULL"}))))]
    (publish/prepare! service request)
    (publish/apply! service request)
    (is (= (:transactionId request) (deref releases 5000 :timeout)))
    (is (= "RECOVERY_REQUIRED" (:state (publish/status service request))))
    (is (= "RECOVERY_REQUIRED" (:state (publish/cancel! service request))))
    (is (= "RECOVERY_REQUIRED" (:state (publish/status (assoc service :publish-state (atom {})) request))))))

;; Project APIs reject host paths and traversal before touching a resource.
(deftest resource-paths-cannot-escape-project
  (doseq [path ["C:/test.gui" "/../test.gui" "/a\\b.gui" "//test.gui" "/a/./b.gui" "/.internal/editor.token"]]
    (is (= "PATH_INVALID" (try (craft-project/validate-resource-path! path)
                               (catch clojure.lang.ExceptionInfo error (:code (ex-data error))))))))
