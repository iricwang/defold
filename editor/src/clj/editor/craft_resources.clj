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

(ns editor.craft-resources
  "Bounded staged assets for the local trusted project host. No caller paths or code are executed."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [dynamo.graph :as g]
            [editor.craft-project :as craft-project]
            [editor.defold-project :as project]
            [editor.protobuf :as protobuf]
            [util.digest :as digest])
  (:import [com.dynamo.gamesys.proto AtlasProto$Atlas]
           [java.io ByteArrayInputStream File FileOutputStream]
           [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [java.nio.file AtomicMoveNotSupportedException CopyOption Files LinkOption StandardCopyOption]
           [javax.imageio ImageIO]))

(set! *warn-on-reflection* true)

(defn- hash! [value]
  (when-not (and (string? value) (re-matches #"sha256:[0-9a-f]{64}" value))
    (craft-project/fail! "HASH_MISMATCH" "Expected a versioned SHA-256 asset fingerprint" {}))
  value)

(defn- staged-file
  ^File [service request content-hash]
  (let [root (.toRealPath (.toPath (io/file (:root service))) (make-array LinkOption 0))
        path (.resolve root (str ".internal/photocraft/transactions/" (:transactionId request)
                                 "/files/sha256-" (subs content-hash 7)))]
    (loop [candidate path]
      (when (not= candidate root)
        (when (or (Files/isSymbolicLink candidate)
                  (and (Files/exists candidate (make-array LinkOption 0))
                       (not (.startsWith (.toRealPath candidate (make-array LinkOption 0)) root))))
          (craft-project/fail! "PATH_INVALID" "Staged content resolves outside its project" {}))
        (recur (.getParent candidate))))
    (.toFile path)))

(defn read-staged!
  "Validate and materialize immutable upload bytes. Rechecked before installation."
  [service request]
  (when-not (and (string? (:transactionId request))
                (re-matches #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" (:transactionId request)))
    (craft-project/fail! "PATH_INVALID" "Invalid staged transaction ID" {}))
  (let [updates (:resourceUpdates request [])]
    (when-not (and (vector? updates) (<= (count updates) 1024))
      (craft-project/fail! "PAYLOAD_TOO_LARGE" "Too many asset updates" {}))
    (when-not (= (count updates) (count (set (mapv :path updates))))
      (craft-project/fail! "NAME_COLLISION" "Duplicate asset paths in publication" {}))
    (let [total-bytes (volatile! 0)
          entries
          (mapv (fn [{:keys [action path expectedDiskHash newContentHash] :as update}]
                  (craft-project/validate-resource-path! path)
                  (when-not (and (contains? #{"create" "replace"} action)
                                (or (string/ends-with? path ".png") (string/ends-with? path ".atlas")))
                    (craft-project/fail! "UNDO_BARRIER_REQUIRED" "Only PNG/atlas creation and replacement are supported; reclamation is separate" {:path path}))
                  (when-not (if (= "create" action) (nil? expectedDiskHash) (some? expectedDiskHash))
                    (craft-project/fail! "HASH_MISMATCH" "Asset action has no matching baseline" {:path path}))
                  (when expectedDiskHash (hash! expectedDiskHash))
                  (hash! newContentHash)
                  (let [file (staged-file service request newContentHash)]
                    (when-not (and (.isFile file) (<= (.length file) (* 64 1024 1024)))
                      (craft-project/fail! "RESOURCE_MISSING" "Bounded staged asset bytes are unavailable" {:path path}))
                    (when (and (string/ends-with? path ".atlas") (< (* 4 1024 1024) (.length file)))
                      (craft-project/fail! "PAYLOAD_TOO_LARGE" "Atlas descriptor exceeds limit" {:path path}))
                    (when (< (* 256 1024 1024) (vswap! total-bytes + (.length file)))
                      (craft-project/fail! "PAYLOAD_TOO_LARGE" "Asset update total exceeds limit" {}))
                    (let [bytes (Files/readAllBytes (.toPath file))]
                      (when-not (= newContentHash (str "sha256:" (digest/stream->sha256-hex (ByteArrayInputStream. bytes))))
                        (craft-project/fail! "HASH_MISMATCH" "Staged asset bytes changed" {:path path}))
                      (if (string/ends-with? path ".png")
                        (do
                          (when-not (and (<= 24 (alength bytes))
                                        (= [-119 80 78 71 13 10 26 10] (mapv #(aget bytes %) (range 8))))
                            (craft-project/fail! "UNSUPPORTED_GUI_FEATURE" "Expected PNG image content" {:path path}))
                          (let [header (ByteBuffer/wrap bytes)
                                width (.getInt header 16)
                                height (.getInt header 20)]
                            (when-not (and (<= 1 width 16384) (<= 1 height 16384) (<= (* (long width) height) 67108864))
                              (craft-project/fail! "PAYLOAD_TOO_LARGE" "PNG dimensions exceed adapter limits" {:path path})))
                          (try
                            (if-let [image (ImageIO/read (ByteArrayInputStream. bytes))]
                              (.flush image)
                              (craft-project/fail! "UNSUPPORTED_GUI_FEATURE" "PNG cannot be decoded" {:path path}))
                            (catch Exception _
                              (craft-project/fail! "UNSUPPORTED_GUI_FEATURE" "PNG cannot be decoded" {:path path})))
                          (assoc update :bytes bytes))
                        (let [data (try (protobuf/str->map-with-defaults AtlasProto$Atlas (String. bytes StandardCharsets/UTF_8))
                                        (catch Exception _ (craft-project/fail! "UNSUPPORTED_GUI_FEATURE" "Invalid atlas descriptor" {:path path})))
                              images (into (vec (:images data)) (mapcat :images) (:animations data))]
                          (when (< 8192 (count images))
                            (craft-project/fail! "PAYLOAD_TOO_LARGE" "Atlas image count exceeds limit" {:path path}))
                          (doseq [image images] (craft-project/validate-resource-path! (:image image)))
                          (assoc update :bytes bytes :atlas-data data))))))
                updates)]
      (when (< (* 256 1024 1024) (transduce (map #(alength ^bytes (:bytes %))) + 0 entries))
        (craft-project/fail! "PAYLOAD_TOO_LARGE" "Asset update total exceeds limit" {}))
      entries)))

(defn check-baselines! [service entries]
  (doseq [{:keys [path expectedDiskHash]} entries]
    (when-not (= expectedDiskHash (craft-project/file-hash (:root service) path))
      (craft-project/fail! "TARGET_CHANGED" "Asset changed after publication planning" {:path path}))))

(defn check-atlases! [service entries]
  (doseq [{:keys [action path atlas-data]} entries :when (and atlas-data (= "replace" action))]
    (let [node (project/ensure-resource-loaded! (:project service) path)]
      (when-not node
        (craft-project/fail! "RESOURCE_MISSING" "Atlas is not present in the editor" {:path path}))
      (when (g/node-value node :dirty)
        (craft-project/fail! "TARGET_DIRTY" "Managed atlas has unsaved editor changes" {:path path})))))

(defn- write-atomically! [^File target ^bytes bytes]
  (io/make-parents target)
  (let [temp (Files/createTempFile (.toPath (.getParentFile target)) ".craft-" ".tmp" (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (with-open [out (FileOutputStream. (.toFile temp))]
        (.write out bytes)
        (.sync (.getFD out)))
      (try
        (Files/move temp (.toPath target) (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
        (catch AtomicMoveNotSupportedException _
          (Files/move temp (.toPath target) (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))
      (finally (Files/deleteIfExists temp)))))

(defn backup-existing!
  "Retain original exact bytes by their expected content hash before any game-file write."
  [service request entries]
  (doseq [{:keys [path expectedDiskHash] :as entry} entries :when expectedDiskHash]
    (check-baselines! service [entry])
    (let [file (craft-project/resource-file (:root service) path)
          backup (staged-file service request expectedDiskHash)]
      (write-atomically! backup (Files/readAllBytes (.toPath file))))))

(defn install!
  "Install PNGs and new atlases. Existing atlas files are written only by targeted save.
  Backups remain in the transaction journal for explicit failure recovery."
  [service _request entries]
  (doseq [{:keys [action path bytes atlas-data] :as entry} entries]
    (check-baselines! service [entry])
    (when (or (nil? atlas-data) (= "create" action))
      (write-atomically! (craft-project/resource-file (:root service) path) bytes))))

(defn atlas-steps [service entries evaluation-context]
  (into []
        (comp (filter :atlas-data)
              (mapcat (fn [{:keys [path atlas-data]}]
                        (let [node (project/ensure-resource-loaded! (:project service) path)]
                          (when-not node
                            (craft-project/fail! "RESOURCE_MISSING" "Published atlas was not registered" {:path path}))
                          ;; Atlas depends on app-view; resolving its registered
                          ;; adapter here avoids a bridge/app-view namespace cycle.
                          ((requiring-resolve 'editor.atlas/managed-content-tx-data)
                           (project/workspace (:project service)) node atlas-data evaluation-context)))))
        entries))

(defn atlas-node-ids [service entries]
  (into [] (comp (filter :atlas-data) (map #(project/get-resource-node (:project service) (:path %)))) entries))
