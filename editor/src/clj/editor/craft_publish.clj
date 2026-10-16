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

(ns editor.craft-publish
  "Structured, project-scoped GUI publication with durable, idempotent receipts."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [dynamo.graph :as g]
            [editor.attachment :as attachment]
            [editor.craft-project :as craft-project]
            [editor.craft-resources :as craft-resources]
            [editor.defold-project :as project]
            [editor.disk :as disk]
            [editor.editor-extensions.coerce :as coerce]
            [editor.editor-extensions.graph :as ext-graph]
            [editor.editor-extensions.runtime :as rt]
            [editor.resource :as resource]
            [editor.resource-node :as resource-node]
            [editor.ui :as ui]
            [util.coll :as coll]
            [util.digest :as digest])
  (:import [java.io File FileOutputStream]
           [java.nio.charset StandardCharsets]
           [java.nio.file AtomicMoveNotSupportedException CopyOption Files StandardCopyOption]))

(set! *warn-on-reflection* true)

(defn- canonical [value]
  (walk/postwalk #(if (map? %) (into (sorted-map-by (fn [a b] (compare (name a) (name b)))) %) %) value))

(defn- fingerprint [value]
  (str "sha256:" (digest/string->sha256-hex (json/write-str (canonical value)))))

(defn- transaction-id! [value]
  (when-not (and (string? value) (re-matches #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" value))
    (craft-project/fail! "PATH_INVALID" "Invalid publication transaction ID" {}))
  value)

(defn- journal-file
  ^File [service transaction-id]
  (transaction-id! transaction-id)
  (let [root (.getCanonicalFile (io/file (:root service)))
        file (.getCanonicalFile (io/file root ".internal" "photocraft" "adapter-transactions" (str transaction-id ".json")))]
    (when-not (.startsWith (.toPath file) (.toPath root))
      (craft-project/fail! "PATH_INVALID" "Publication journal resolves outside the project" {}))
    file))

(defn- persist! [service record]
  (let [file (journal-file service (:transactionId record))
        bytes (.getBytes ^String (json/write-str record) StandardCharsets/UTF_8)]
    (when (< (* 16 1024 1024) (alength bytes))
      (craft-project/fail! "PAYLOAD_TOO_LARGE" "Publication journal exceeds limit" {}))
    (io/make-parents file)
    (let [temp (Files/createTempFile (.toPath (.getParentFile file)) ".craft-" ".tmp" (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (with-open [out (FileOutputStream. (.toFile temp))]
          (.write out bytes)
          (.sync (.getFD out)))
        (try
          (Files/move temp (.toPath file) (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
          (catch AtomicMoveNotSupportedException _
            (Files/move temp (.toPath file) (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))
        (finally
          (Files/deleteIfExists temp))))
    (swap! (:publish-state service) assoc (:transactionId record) record)
    record))

(defn- lookup [service transaction-id]
  (or (get @(:publish-state service) transaction-id)
      (let [file (journal-file service transaction-id)]
        (when (.isFile file)
          (when (< (* 16 1024 1024) (.length file))
            (craft-project/fail! "RECOVERY_REQUIRED" "Oversized publication journal" {}))
          (let [record (json/read-str (slurp file) :key-fn keyword)
                record (if (contains? #{"COMMITTED" "ABORTED" "PREPARED"} (:state record))
                         record
                         (assoc record :state "RECOVERY_REQUIRED"
                                       :error {:code "RECOVERY_REQUIRED" :message "Editor restarted during publication; recovery must inspect persisted hashes"}))]
            (swap! (:publish-state service) assoc transaction-id record)
            record)))))

(defn- public-status [record]
  (select-keys record [:schemaVersion :transactionId :planHash :state :receipt :error]))

(defn status [service request]
  (if-let [record (lookup service (transaction-id! (:transactionId request)))]
    (public-status record)
    (craft-project/fail! "RESOURCE_MISSING" "Publication transaction is unknown" {})))

(defn- snapshot! [service path]
  (if-let [snapshot-fn (:snapshot-fn service)]
    (snapshot-fn path)
    (ui/run-now (craft-project/snapshot service path))))

(defn- request-targets [request]
  (or (:targets request)
      [(select-keys request [:path :expectedDiskHash :expectedEditorGraphRevision :changes])]))

(defn- validate-baseline! [service request]
  (when-not (= (craft-project/session-id service) (:editorSessionId request))
    (craft-project/fail! "EDITOR_UNAVAILABLE" "The editor session changed; obtain a new snapshot" {}))
  (mapv (fn [target]
          (let [snapshot (snapshot! service (:path target))]
            (when (:dirty snapshot)
              (craft-project/fail! "TARGET_DIRTY" "Target GUI has unpublished editor changes" {:path (:path target)}))
            (when-not (and (= (:expectedDiskHash target) (:diskHash snapshot))
                           (= (:expectedEditorGraphRevision target) (:editorGraphRevision snapshot)))
              (craft-project/fail! "TARGET_CHANGED" "Target GUI changed after the source snapshot" {:path (:path target)}))
            snapshot))
        (request-targets request)))

(defn- validate-values! [value depth]
  (when (< 16 depth)
    (craft-project/fail! "INVALID_TRANSFORM" "GUI property nesting exceeds limit" {}))
  (cond
    (number? value) (when-not (Double/isFinite (double value))
                      (craft-project/fail! "INVALID_TRANSFORM" "GUI properties must contain finite numbers" {}))
    (map? value) (run! #(validate-values! % (inc depth)) (coll/vals value))
    (vector? value) (run! #(validate-values! % (inc depth)) value)
    (or (string? value) (boolean? value) (nil? value)) nil
    :else (craft-project/fail! "UNSUPPORTED_GUI_FEATURE" "Unsupported GUI property value" {})))

(defn- validate-target! [request]
  (craft-project/validate-resource-path! (:path request))
  (doseq [field [:expectedDiskHash :expectedEditorGraphRevision]]
    (when-not (and (string? (field request)) (re-matches #"sha256:[0-9a-f]{64}" (field request)))
      (craft-project/fail! "HASH_MISMATCH" "Expected a versioned SHA-256 fingerprint" {:field (name field)})))
  (let [nodes (get-in request [:changes :nodes])]
    (when-not (and (vector? nodes) (<= (count nodes) 8192))
      (craft-project/fail! "PROTOCOL_UNSUPPORTED" "Expected a bounded version 1 GUI scene update" {}))
    (when-not (= (count nodes) (count (set (mapv :id nodes))))
      (craft-project/fail! "NAME_COLLISION" "Duplicate GUI node IDs" {}))
    (doseq [{:keys [id parent kind values]} nodes]
      (when-not (and (string? id) (not (string/blank? id)) (string? parent) (string? kind) (map? values))
        (craft-project/fail! "UNSUPPORTED_GUI_FEATURE" "Malformed GUI node" {}))
      (validate-values! values 0)
      (doseq [[property value] values]
        (when (string/ends-with? (name property) "rotation")
          (when-not (and (vector? value) (= 3 (count value)) (coll/every? number? value))
            (craft-project/fail! "INVALID_TRANSFORM" "Rotation requires three finite Euler angles" {:node id}))))
      (loop [ancestor parent seen #{id} depth 0 nodes-by-id (into {} (map (juxt :id identity)) nodes)]
        (when-not (= "" ancestor)
          (when (or (< 128 depth) (contains? seen ancestor))
            (craft-project/fail! "INVALID_TRANSFORM" "GUI hierarchy contains a cycle or is too deep" {:node id}))
          (if-let [entry (get nodes-by-id ancestor)]
            (recur (:parent entry) (conj seen ancestor) (inc depth) nodes-by-id)
            (craft-project/fail! "RESOURCE_MISSING" "GUI parent is absent from publication" {:node id :parent ancestor})))))))

(defn- validate-request! [request]
  (transaction-id! (:transactionId request))
  (when-not (and (= 1 (:schemaVersion request))
                (string? (:planHash request)) (re-matches #"sha256:[0-9a-f]{64}" (:planHash request)))
    (craft-project/fail! "PROTOCOL_UNSUPPORTED" "Expected a version 1 publication with plan fingerprint" {}))
  (let [targets (request-targets request)]
    (when-not (and (vector? targets) (<= 1 (count targets) 128)
                  (= (count targets) (count (set (mapv :path targets)))))
      (craft-project/fail! "PROTOCOL_UNSUPPORTED" "Expected distinct bounded GUI targets" {}))
    (run! validate-target! targets)))

(declare preflight-properties!)

(defn prepare! [service request]
  (validate-request! request)
  (locking service
    (if-let [record (lookup service (:transactionId request))]
      (if (= (:requestHash record) (fingerprint request))
        (public-status record)
        (craft-project/fail! "IDEMPOTENCY_MISMATCH" "Transaction ID was used for different content" {}))
      (let [resources (craft-resources/read-staged! service request)]
        (craft-resources/check-baselines! service resources)
        (when (coll/not-empty resources)
          (ui/run-now
            (craft-resources/check-atlases! service resources)
            (craft-project/safe-refresh! (:project service) #{})))
        (let [baseline (validate-baseline! service request)]
          (when (:project service)
            (ui/run-now (preflight-properties! service request)))
          (public-status
            (persist! service {:schemaVersion 1
                               :transactionId (:transactionId request)
                               :planHash (:planHash request)
                               :requestHash (fingerprint request)
                               :request request
                               :baseline baseline
                               :state "PREPARED"})))))))

(defn- tx! [transaction-id steps]
  (g/transact (into (into (vec (g/operation-sequence transaction-id))
                          (g/operation-label "PhotoCraft publication")) steps)))

(defn- attachment-tx [runtime function args]
  (rt/->clj runtime coerce/userdata (apply rt/invoke-immediate-1 runtime function args)))

(defn- same-property-value? [left right]
  (cond
    (and (number? left) (number? right)) (== left right)
    (and (sequential? left) (sequential? right))
    (and (= (count left) (count right))
         (coll/every? true? (mapv same-property-value? left right)))
    :else (= left right)))

(defn- unchanged-property? [project node-id property value evaluation-context]
  (when-let [getter (ext-graph/ext-value-getter node-id property project evaluation-context)]
    (same-property-value? value (getter))))

(defn- set-value-group! [project runtime transaction-id node-id values]
  (g/with-auto-evaluation-context evaluation-context
    (let [steps (into []
                      (mapcat (fn [[property value]]
                                (when-not (unchanged-property? project node-id (name property) value evaluation-context)
                                  (if-let [setter (ext-graph/ext-lua-value-setter node-id (name property) runtime project evaluation-context)]
                                    (setter (rt/->lua value))
                                    (craft-project/fail! "UNSUPPORTED_GUI_FEATURE" "GUI property is not writable"
                                      {:property (name property)
                                       :node (craft-project/node-property project node-id "id" evaluation-context)})))))
                      values)]
      (when (coll/not-empty steps)
        (tx! transaction-id steps)))))

(defn- set-values! [project runtime transaction-id node-id values]
  ;; Size mode controls slice9 writability. Commit controlling properties first,
  ;; then obtain a fresh evaluation context for dependent native setters.
  (let [controls (into {} (filter #(string/ends-with? (name (key %)) "size_mode")) values)]
    (set-value-group! project runtime transaction-id node-id controls)
    (set-value-group! project runtime transaction-id node-id (reduce dissoc values (coll/keys controls)))))

(defn- preflight-properties! [service request]
  (let [project (:project service)
        runtime (rt/make)
        property-names (requiring-resolve 'editor.gui/managed-property-names)]
    (doseq [{:keys [path changes]} (request-targets request)]
      (let [scene (craft-project/ensure-gui-loaded! project path)
            renames (into {} (map (juxt :from :to)) (:renames changes))
            current (into {} (map (fn [node] [(get renames (:id node) (:id node)) node]))
                          (craft-project/gui-nodes project scene))]
        (doseq [{:keys [id kind values]} (:nodes changes)]
          (let [known (property-names kind)
                existing (get current id)]
            (when-not known
              (craft-project/fail! "UNSUPPORTED_GUI_FEATURE" "Unknown GUI node type" {:path path :node id}))
            (g/with-auto-evaluation-context evaluation-context
              (doseq [[property value] values]
                (let [property (name property)
                      base-property (last (string/split property #":"))]
                  (when-not (contains? known base-property)
                    (craft-project/fail! "UNSUPPORTED_GUI_FEATURE" "Unknown GUI property" {:path path :node id :property property}))
                  (when (and existing (= kind (:kind existing))
                             (not (unchanged-property? project (:ref existing) property value evaluation-context))
                             (not (and (= "slice9" base-property) (= "size-mode-manual" (:size_mode values))))
                             (not (ext-graph/ext-lua-value-setter (:ref existing) property runtime project evaluation-context)))
                    (craft-project/fail! "UNSUPPORTED_GUI_FEATURE" "GUI property is not writable" {:path path :node id :property property})))))))))))

(defn- apply-graph! [service request]
  (let [project (:project service)
        path (:path request)
        transaction-id (:transactionId request)
        scene (craft-project/ensure-gui-loaded! project path)
        workspace (project/workspace project)
        runtime (rt/make)
        nodes (get-in request [:changes :nodes])
        wanted (into {} (map (juxt :id identity)) nodes)
        current (craft-project/gui-nodes project scene)
        renames (get-in request [:changes :renames] [])
        rename-map (into {} (map (juxt :from :to)) renames)
        renamed-current (mapv #(-> % (update :id (fn [id] (get rename-map id id)))
                                      (update :parent (fn [id] (get rename-map id id)))) current)
        replacing (into #{} (comp (filter #(when-let [desired (get wanted (:id %))] (not= (:kind %) (:kind desired)))) (map :id)) renamed-current)
        removing (into replacing (comp (remove #(contains? wanted (:id %))) (map :id)) renamed-current)]
    (doseq [old renamed-current]
      (when-let [desired (get wanted (:id old))]
        (when (and (not= (:parent old) (:parent desired))
                   (not (coll/every? #(contains? (:values desired) %) [:position :rotation :scale])))
          (craft-project/fail! "INVALID_TRANSFORM" "Reparenting requires explicit final parent-local position, rotation and scale" {:node (:id old)}))
        (when (and (contains? replacing (:id old))
                   (not (coll/every? #{"gui-node-type-box" "gui-node-type-text" "gui-node-type-pie"}
                                     [(:kind old) (:kind desired)])))
          (craft-project/fail! "UNSUPPORTED_GUI_FEATURE" "Only ordinary box/text/pie node types may be replaced" {:node (:id old)}))))
    (try
      (when-let [resources (:preparedResources request)]
        (g/with-auto-evaluation-context evaluation-context
          (tx! transaction-id (craft-resources/atlas-steps service resources evaluation-context))))
      (doseq [{:keys [name texture]} (get-in request [:changes :textures] [])]
        (craft-project/validate-resource-path! texture)
        (when-not (project/get-resource-node project texture)
          (craft-project/fail! "RESOURCE_MISSING" "Texture must already be loaded before GUI publication" {:path texture}))
        (g/with-auto-evaluation-context evaluation-context
          (let [existing (coll/first-where #(= name (craft-project/node-property project % "name" evaluation-context))
                                           (attachment/get workspace scene :textures evaluation-context))]
            (if existing
              (set-values! project runtime transaction-id existing {:texture texture})
              (tx! transaction-id [(attachment-tx runtime (ext-graph/make-ext-add-fn project)
                                                  [(rt/wrap-userdata scene) (rt/->lua "textures") (rt/->lua {:name name :texture texture})])])))))
      (let [refs (into {} (map (juxt :id :ref)) current)]
        (doseq [[index {:keys [from to]}] (into [] (map-indexed vector) renames)]
          (when-not (and (contains? refs from) (contains? wanted to))
            (craft-project/fail! "RESOURCE_MISSING" "Rename source or destination is absent" {:node from}))
          (set-values! project runtime transaction-id (get refs from)
                       {:id (str "__craft_" transaction-id "_" index)}))
        (doseq [{:keys [from to]} renames]
          (set-values! project runtime transaction-id (get refs from) {:id to})))
      ;; First detach surviving children of removed/retyped parents and nodes
      ;; with a changed parent. Root detachment makes inverse parent swaps safe.
      (doseq [{:keys [id parent ref]} renamed-current]
        (when-let [desired (get wanted id)]
          (when (and (not (contains? replacing id)) (not= "" parent)
                     (or (contains? removing parent) (not= parent (:parent desired))))
            (g/with-auto-evaluation-context evaluation-context
              ;; GUI depends on app-view, which depends on this bridge. Resolve
              ;; the already registered resource adapter only at publication time.
              (tx! transaction-id ((requiring-resolve 'editor.gui/managed-reparent-tx-data) scene ref nil evaluation-context))))))
      (doseq [{:keys [id parent parent-ref ref]} (craft-project/gui-nodes project scene)]
        (when (and (contains? removing id) (not (contains? removing parent)))
          (tx! transaction-id [(attachment/remove workspace parent-ref :nodes ref)])))
      (loop [pending nodes refs (into {} (map (juxt :id :ref)) (craft-project/gui-nodes project scene))]
        (let [addable (filterv #(and (not (contains? refs (:id %))) (or (= "" (:parent %)) (contains? refs (:parent %)))) pending)]
          (when (coll/not-empty addable)
            (doseq [{:keys [id parent kind]} addable]
              (tx! transaction-id [(attachment-tx runtime (ext-graph/make-ext-add-fn project)
                                                  [(rt/wrap-userdata (if (= "" parent) scene (get refs parent)))
                                                   (rt/->lua "nodes") (rt/->lua {:id id :type kind})])]))
            (recur pending (into {} (map (juxt :id :ref)) (craft-project/gui-nodes project scene))))))
      (let [refs (into {} (map (juxt :id :ref)) (craft-project/gui-nodes project scene))]
        (doseq [{:keys [id parent ref]} (craft-project/gui-nodes project scene)]
          (let [target-parent (:parent (get wanted id))]
            (when-not (= parent target-parent)
              (g/with-auto-evaluation-context evaluation-context
                (tx! transaction-id ((requiring-resolve 'editor.gui/managed-reparent-tx-data) scene ref (get refs target-parent) evaluation-context))))))
        (doseq [{:keys [id values]} nodes]
          (if-let [node-id (get refs id)]
            (set-values! project runtime transaction-id node-id values)
            (craft-project/fail! "RESOURCE_MISSING" "Node could not be attached" {:node id})))
        (doseq [[parent children] (group-by :parent nodes)]
          (tx! transaction-id [(attachment/reorder workspace (if (= "" parent) scene (get refs parent)) :nodes (mapv #(get refs (:id %)) children))])))
      (g/valid-node-value scene :save-value)
      (g/valid-node-value scene :build-errors)
      (doseq [atlas-node (craft-resources/atlas-node-ids service (:preparedResources request))]
        (g/valid-node-value atlas-node :build-errors))
      (disk/capture-save-snapshot project (into [scene] (craft-resources/atlas-node-ids service (:preparedResources request))))
      (catch Throwable error
        ;; All graph changes execute in a single FX callback, so no other user's
        ;; transaction can interleave with this operation sequence.
        (g/cancel! :undo/global transaction-id)
        (throw error)))))

(defn- run-publication! [service record]
  (let [request (:request record)
        transaction-id (:transactionId request)]
    (try
      (let [resources (craft-resources/read-staged! service request)
            targets (request-targets request)
            _ (craft-resources/backup-existing! service request (into (mapv #(select-keys % [:path :expectedDiskHash]) targets) resources))
            _ (when (coll/not-empty resources)
                (craft-resources/check-baselines! service resources)
                (ui/run-now
                  (validate-baseline! service request)
                  (craft-resources/check-atlases! service resources)
                  (craft-project/safe-refresh! (:project service) #{}))
                (craft-resources/install! service request resources))
            snapshot (if-let [apply-fn (:apply-fn service)]
                       (do (validate-baseline! service request) (apply-fn request))
                       (ui/run-now
                         (when (coll/not-empty resources)
                           (craft-project/safe-refresh! (:project service) (into #{} (map :path) resources))
                           (craft-resources/check-atlases! service resources))
                         (validate-baseline! service request)
                         (try
                           (doseq [[index target] (into [] (map-indexed vector) targets)]
                             (apply-graph! service (assoc (merge request target) :preparedResources (when (zero? index) resources))))
                           (disk/capture-save-snapshot (:project service)
                             (into (mapv #(project/get-resource-node (:project service) (:path %)) targets)
                                   (craft-resources/atlas-node-ids service resources)))
                           (catch Throwable error
                             (g/cancel! :undo/global transaction-id)
                             (throw error)))))
            frozen-source (or (:frozenSource snapshot)
                              (resource-node/save-data-content (first (:save-data snapshot))))
            frozen-sources (if (:frozenSource snapshot)
                             [{:path (:path (first targets)) :source frozen-source}]
                             (mapv (fn [save-data]
                                     {:path (resource/proj-path (:resource save-data))
                                      :source (resource-node/save-data-content save-data)}) (:save-data snapshot)))
            record (persist! service (assoc record :state "APPLIED_UNSAVED" :frozenSource frozen-source :frozenSources frozen-sources))]
        (persist! service (assoc record :state "SAVING"))
        (let [save-result (if-let [save-fn (:save-fn service)]
                            (save-fn snapshot)
                            (disk/write-save-snapshot! snapshot))
              target-receipts
              (mapv (fn [index target]
                      (let [after (snapshot! service (:path target))
                            saved-disk-hash (or (:diskHash save-result)
                                               (when-let [hash (get (:written-disk-sha256s-by-node-id save-result)
                                                                   (:node-id (get (:save-data snapshot) index)))]
                                                 (str "sha256:" hash)))]
                        (when-not (= saved-disk-hash (:diskHash after))
                          (craft-project/fail! "TARGET_CHANGED" "Saved GUI changed before publication was acknowledged" {:path (:path target)}))
                        (let [source (:source (get frozen-sources index))]
                          {:path (:path target) :diskHash (:diskHash after)
                           :sceneHash (craft-project/scene-hash source)
                           :editorGraphRevision (str "sha256:" (digest/string->sha256-hex source))
                           :editorAhead (:dirty after)})))
                    (vec (range (count targets))) targets)
              resource-receipts
              (mapv (fn [{:keys [path action atlas-data newContentHash expectedDiskHash]}]
                      (let [actual (craft-project/file-hash (:root service) path)
                            node-id (when atlas-data (project/get-resource-node (:project service) path))
                            written-hash (get (:written-disk-sha256s-by-node-id save-result) node-id)
                            expected (cond
                                       written-hash (str "sha256:" written-hash)
                                       (and atlas-data (= "replace" action)) expectedDiskHash
                                       :else newContentHash)]
                        (when-not (= actual expected)
                          (craft-project/fail! "TARGET_CHANGED" "Published asset changed before acknowledgement" {:path path}))
                        {:path path :diskHash actual})) resources)
              receipt {:schemaVersion 1
                       :transactionId transaction-id
                       :path (:path (first targets))
                       :diskHash (:diskHash (first target-receipts))
                       :sceneHash (:sceneHash (first target-receipts))
                       :resources resource-receipts
                       :targets target-receipts
                       :editorAhead (coll/any? :editorAhead target-receipts)
                       :editorSessionId (craft-project/session-id service)}]
          (persist! service (assoc record :state "COMMITTED" :receipt receipt))))
      (catch Throwable error
        (let [failed (assoc (get @(:publish-state service) transaction-id record) :state "RECOVERY_REQUIRED"
                                  :error (merge (select-keys (ex-data error) [:path :paths :node :property])
                                                {:code (get (ex-data error) :code "SAVE_FAILED")
                                                 :message (or (ex-message error) "Publication did not complete")}))]
          (try
            (persist! service failed)
            (catch Throwable _
              ;; A full disk must not leave the running status falsely pending.
              ;; The previous durable nonterminal journal requires reconciliation.
              (swap! (:publish-state service) assoc transaction-id failed)))))
      (finally
        (if-let [release-fn (:release-fn service)]
          (release-fn transaction-id)
          (ui/run-now (disk/end-managed-write! transaction-id)))))))

(defn apply! [service request]
  (transaction-id! (:transactionId request))
  (locking service
    (let [record (lookup service (:transactionId request))]
      (when-not record
        (craft-project/fail! "RESOURCE_MISSING" "Prepare this transaction before applying it" {}))
      (when-not (= (:planHash request) (:planHash record))
        (craft-project/fail! "IDEMPOTENCY_MISMATCH" "Publication plan hash differs" {}))
      (if (not= "PREPARED" (:state record))
        (public-status record)
        (let [claimed (if-let [claim-fn (:claim-fn service)]
                        (claim-fn (:transactionId request))
                        (ui/run-now
                          (if-let [legacy-state (:state service)]
                            (locking legacy-state
                              (when (zero? (:file-operations @legacy-state 0))
                                (disk/begin-managed-write! (:transactionId request))))
                            (disk/begin-managed-write! (:transactionId request)))))]
          (when-not claimed
            (craft-project/fail! "TARGET_BUSY" "Editor file operations are busy" {}))
          (try
            (let [record (persist! service (assoc record :state "APPLYING"))]
              (future (run-publication! service record))
              (public-status record))
            (catch Throwable error
              (if-let [release-fn (:release-fn service)]
                (release-fn (:transactionId request))
                (ui/run-now (disk/end-managed-write! (:transactionId request))))
              (throw error))))))))

(defn cancel! [service request]
  (locking service
    (let [record (lookup service (transaction-id! (:transactionId request)))]
      (when-not record
        (craft-project/fail! "RESOURCE_MISSING" "Publication transaction is unknown" {}))
      (public-status (if (= "PREPARED" (:state record))
                       (persist! service (assoc record :state "ABORTED"))
                       record)))))
