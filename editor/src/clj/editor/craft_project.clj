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

(ns editor.craft-project
  "Project-scoped PhotoCraft access. Reading a resource never opens a tab."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [dynamo.graph :as g]
            [editor.attachment :as attachment]
            [editor.defold-project :as project]
            [editor.editor-extensions.graph :as ext-graph]
            [editor.progress :as progress]
            [editor.protobuf :as protobuf]
            [editor.resource :as resource]
            [editor.resource-node :as resource-node]
            [editor.resource-update :as resource-update]
            [editor.resource-watch :as resource-watch]
            [editor.workspace :as workspace]
            [util.coll :as coll]
            [util.digest :as digest])
  (:import [com.dynamo.gamesys.proto Gui$SceneDesc]
           [java.io ByteArrayInputStream File StringReader]
           [java.nio.file Files LinkOption Path]))

(set! *warn-on-reflection* true)

(defn fail! [code message data]
  (throw (ex-info message (assoc data :code code))))

(defn session-id [service]
  (or (:editor-session-id service) (:id service)))

(defn validate-resource-path! [path]
  (when-not (and (string? path)
                (string/starts-with? path "/")
                (<= 2 (count path) 4096)
                (not (re-find #"[\\:\x00]" path))
                (coll/every? #(and (not (string/blank? %))
                                   (not (string/starts-with? % ".")))
                             (string/split (subs path 1) #"/" -1)))
    (fail! "PATH_INVALID" "Expected a project-relative resource path" {:path path}))
  path)

(defn resource-file
  ^File [root path]
  (validate-resource-path! path)
  (let [root (.toRealPath (.toPath (io/file root)) (make-array LinkOption 0))
        target (.normalize (.resolve ^Path root ^String (subs path 1)))]
    (when-not (.startsWith target root)
      (fail! "PATH_INVALID" "Resource escapes the project" {:path path}))
    (loop [candidate target]
      (when (and candidate (not= candidate root))
        (when (Files/exists candidate (make-array LinkOption 0))
          (when-not (.startsWith (.toRealPath candidate (make-array LinkOption 0)) root)
            (fail! "PATH_INVALID" "Resource resolves outside the project" {:path path})))
        (when (Files/isSymbolicLink candidate)
          (fail! "PATH_INVALID" "Symbolic-link resources are not supported" {:path path}))
        (recur (.getParent candidate))))
    (.toFile target)))

(defn file-hash [root path]
  (let [file (resource-file root path)]
    (when (.isFile file)
      (with-open [input (io/input-stream file)]
        (str "sha256:" (digest/stream->sha256-hex input))))))

(defn scene-hash [source]
  ;; Reload can omit explicit default fields from native save text. Hash the
  ;; complete native protobuf value while keeping raw save text for graph CAS.
  (let [scene (protobuf/read-map-with-defaults Gui$SceneDesc (StringReader. source))]
    (with-open [input (ByteArrayInputStream. (protobuf/map->bytes Gui$SceneDesc scene))]
      (str "sha256:" (digest/stream->sha256-hex input)))))

(defn ensure-gui-loaded! [project path]
  (validate-resource-path! path)
  (when-not (string/ends-with? path ".gui")
    (fail! "UNSUPPORTED_GUI_FEATURE" "Only GUI scene resources are supported" {:path path}))
  (let [node-id (project/ensure-resource-loaded! project path)]
    (when-not node-id
      (fail! "RESOURCE_MISSING" "GUI resource is not present in the project" {:path path}))
    (when (g/defective? node-id)
      (fail! "UNSUPPORTED_GUI_FEATURE" "GUI resource cannot be loaded safely" {:path path}))
    node-id))

(defn capture-resource [project path evaluation-context]
  (validate-resource-path! path)
  (let [node-id (project/get-resource-node project path evaluation-context)]
    (when-not node-id
      (fail! "RESOURCE_MISSING" "Resource is not loaded" {:path path}))
    (let [save-data (g/valid-node-value node-id :save-data evaluation-context)
          source (resource-node/save-data-content save-data)]
      (when-not (string? source)
        (fail! "UNSUPPORTED_GUI_FEATURE" "Resource does not support structured saving" {:path path}))
      {:path path
       :node-id node-id
       :save-data save-data
       :source source
       :sceneHash (scene-hash source)
       :editorGraphRevision (str "sha256:" (digest/string->sha256-hex source))
       :dirty (boolean (:dirty save-data))})))

(defn snapshot [service path]
  (ensure-gui-loaded! (:project service) path)
  (g/with-auto-evaluation-context evaluation-context
    (let [captured (capture-resource (:project service) path evaluation-context)]
      {:schemaVersion 1
       :path path
       :source (:source captured)
       :dirty (:dirty captured)
       :diskHash (file-hash (:root service) path)
       :sceneHash (:sceneHash captured)
       :editorGraphRevision (:editorGraphRevision captured)
       :editorSessionId (session-id service)})))

(defn open-resource!
  "Explicitly reveal a project asset in a native editor tab. Runs on the UI thread."
  [service path]
  (let [file (resource-file (:root service) path)
        project (:project service)
        workspace (project/workspace project)
        asset (workspace/find-resource workspace path)]
    (when-not (and (.isFile file) asset (resource/exists? asset))
      (fail! "RESOURCE_MISSING" "Resource is not present in the project" {:path path}))
    (when-not (and (workspace/get-resource-type workspace (resource/ext asset))
                  (resource/editor-openable-resource? asset))
      (fail! "UNSUPPORTED_GUI_FEATURE" "Resource has no native editor view" {:path path}))
    (project/ensure-resource-loaded! project path)
    ;; app-view depends on this adapter through project startup. Resolve its
    ;; native tab API only after startup, without introducing a namespace cycle.
    (let [plan (g/with-auto-evaluation-context evaluation-context
                 ((requiring-resolve 'editor.app-view/make-open-resource-plan)
                  (:app-view service) (:prefs service) project asset {:use-custom-editor false} evaluation-context))]
      (when-not (contains? #{:show-existing-tab :open-new-tab} (:type plan))
        (fail! "UNSUPPORTED_GUI_FEATURE" "Resource cannot be opened in a native editor tab" {:path path}))
      ((requiring-resolve 'editor.app-view/perform-open-resource-plan!) plan (:localization service)))
    {:schemaVersion 1 :path path :opened true}))

(defn safe-refresh!
  "Refresh explicitly listed additions/stateless changes. Preflight the entire
  workspace diff before changing its snapshot; never clear global undo."
  [project allowed-paths]
  (let [workspace (project/workspace project)
        info (workspace/make-snapshot-info workspace (workspace/project-directory workspace)
                                           (workspace/dependencies workspace) (workspace/snapshot-cache workspace))
        changes (-> (resource-watch/diff (g/node-value workspace :resource-snapshot) (:snapshot info))
                    (coll/update-vals coll/filterv-> #(= :file (resource/source-type %)))
                    (assoc :moved []))
        paths (into #{} (comp (mapcat val) (map resource/proj-path)) (dissoc changes :moved))]
    (when-not (coll/every? allowed-paths paths)
      (fail! "UNDO_BARRIER_REQUIRED" "Other project files changed; reload them before publishing" {:paths (vec (sort paths))}))
    (let [plan (resource-update/resource-change-plan
                 (g/node-value project :nodes-by-resource-path)
                 (g/node-value workspace :disk-sha256s-by-node-id)
                 changes)]
      (when-not (project/additive-resource-change-plan? plan)
        (fail! "UNDO_BARRIER_REQUIRED" "Resource refresh would replace nodes retained by editor undo" {})))
    (binding [project/*managed-additive-resource-refresh* true]
      (workspace/resource-sync! workspace [] progress/null-render-progress! (:snapshot info) (:map info)))
    (workspace/update-snapshot-cache! workspace (:snapshot-cache info))))

(defn describe [service]
  {:schemaVersion 1
   :editorSessionId (session-id service)
   :capabilities {:backgroundGuiLoad true
                  :targetedSave true
                  :publishReceipts true
                  :rotationEulerV1 true
                  :safeResourceRefresh true
                  :resourceRefreshMode "additive-and-stateless"
                  :managedAtlasGraphUpdates true}
   :resourcesLoaded (count (g/node-value (project/workspace (:project service)) :resource-list))})

(defn node-property [project node-id property evaluation-context]
  (when-let [getter (ext-graph/ext-value-getter node-id property project evaluation-context)]
    (getter)))

(defn gui-nodes [project scene]
  (g/with-auto-evaluation-context evaluation-context
    (let [workspace (project/workspace project evaluation-context)]
      (loop [pending [[scene "" 0]]
             result []]
        (if-let [[parent parent-id depth] (peek pending)]
          (do
            (when (or (< 128 depth) (< 8192 (count result)))
              (fail! "UNSUPPORTED_GUI_FEATURE" "GUI hierarchy exceeds adapter limits" {}))
            (let [children (attachment/get workspace parent :nodes evaluation-context)
                  entries (mapv (fn [node]
                                  {:ref node
                                   :parent-ref parent
                                   :parent parent-id
                                   :id (node-property project node "id" evaluation-context)
                                   :kind (node-property project node "type" evaluation-context)})
                                children)]
              (recur (into (pop pending) (map #(vector (:ref %) (:id %) (inc depth))) (rseq entries))
                     (into result entries))))
          result)))))
