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

(ns editor.automation-editor
  (:require [dynamo.graph :as g]
            [editor.craft-project :as craft]
            [editor.editor-extensions.graph :as ext-graph]
            [editor.editor-extensions.runtime :as rt]
            [util.digest :as digest]))

(set! *warn-on-reflection* true)

(def ^:private writable #{"position" "rotation" "scale" "size" "manual_size" "text" "enabled" "visible"
                          "pivot" "x_anchor" "y_anchor" "adjust_mode" "size_mode" "alpha"})

(defn- history-revision []
  (digest/string->sha256-hex (pr-str (g/undo-stack-revisions))))

(defn snapshot [service path]
  (let [result (craft/snapshot service path)
        project (:project service)
        scene (craft/ensure-gui-loaded! project path)]
    (assoc result :historyRevision (history-revision)
                  :nodes (g/with-auto-evaluation-context context
                           (mapv (fn [{:keys [ref] :as node}]
                                   (assoc (dissoc node :ref :parent-ref) :values
                                     (into {} (keep (fn [property]
                                                      (when-let [getter (ext-graph/ext-value-getter ref property project context)]
                                                        [property (getter)])))
                                           writable)))
                                 (craft/gui-nodes project scene))))))

(defn- check-baseline! [service request]
  (let [current (snapshot service (:path request))]
    (when-not (= (:editorSessionId current) (:editorSessionId request))
      (craft/fail! "EDITOR_SESSION_CHANGED" "Obtain a new snapshot after restarting the editor" {}))
    (when-not (and (= (:editorGraphRevision current) (:expectedRevision request))
                  (= (:historyRevision current) (:expectedHistory request)))
      (craft/fail! "EDITOR_CONFLICT" "The editor or undo history changed; inspect a new snapshot" {}))
    current))

(defn edit! [service request]
  (check-baseline! service request)
  (let [project (:project service)
        scene (craft/ensure-gui-loaded! project (:path request))
        nodes (into {} (map (juxt :id :ref)) (craft/gui-nodes project scene))
        runtime (rt/make)
        operation (:operationId request)
        state (:automation-state service)]
    (when-not (and (string? operation) (<= 1 (count operation) 128) state
                  (vector? (:changes request)) (<= 1 (count (:changes request)) 64))
      (craft/fail! "INVALID_REQUEST" "Expected 1-64 GUI property changes and an operation ID" {}))
    (when (contains? @state operation)
      (craft/fail! "OPERATION_EXISTS" "Do not repeat an editor operation" {}))
    (when (<= 1024 (count @state))
      (craft/fail! "OPERATION_LIMIT" "Editor automation receipt limit reached" {}))
    (let [steps (g/with-auto-evaluation-context context
                  (into [] (mapcat (fn [{:keys [id property value]}]
                                    (let [node (get nodes id)]
                                      (when-not (and node (contains? writable property))
                                        (craft/fail! "INVALID_PROPERTY" "Expected an existing GUI node and a supported base-layout property" {:id id :property property}))
                                      (if-let [setter (ext-graph/ext-lua-value-setter node property runtime project context)]
                                        (setter (rt/->lua value))
                                        (craft/fail! "READ_ONLY_PROPERTY" "Property is not writable in its current size/layout mode" {:id id :property property})))))
                        (:changes request)))
          before (history-revision)]
      (try
        (g/transact (into (into (vec (g/operation-sequence operation))
                               (g/operation-label "Game automation GUI edit")) steps))
        (let [after (history-revision)]
          (when (= before after)
            (craft/fail! "NO_CHANGE" "The transaction did not change the GUI" {}))
          (swap! state assoc operation {:path (:path request) :state :applied :history after})
          (assoc (snapshot service (:path request)) :operationId operation :transactionState "applied"))
        (catch Exception error
          (swap! state dissoc operation)
          (g/cancel! :undo/global operation)
          (throw error))))))

(defn history! [service request]
  (check-baseline! service request)
  (let [operation (:transactionId request)
        record (get @(:automation-state service) operation)
        action (:action request)]
    (when-not (and record (= (:path request) (:path record))
                  (= (:history record) (history-revision))
                  (or (and (= "undo" action) (= :applied (:state record)))
                      (and (= "redo" action) (= :undone (:state record)))))
      (craft/fail! "UNDO_CONFLICT" "Only the unchanged top automation transaction can be undone or redone" {}))
    (case action
      "undo" (g/undo! :undo/global)
      "redo" (g/redo! :undo/global))
    (let [next-state (if (= action "undo") :undone :applied)]
      (swap! (:automation-state service) assoc operation (assoc record :state next-state :history (history-revision)))
      (assoc (snapshot service (:path request)) :operationId operation :transactionState (name next-state)))))
