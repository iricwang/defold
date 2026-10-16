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

(ns integration.craft-publish-test
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is]]
            [dynamo.graph :as g]
            [editor.craft-project :as craft-project]
            [editor.craft-publish :as publish]
            [editor.craft-resources :as craft-resources]
            [editor.defold-project :as project]
            [editor.disk :as disk]
            [editor.disk-availability :as disk-availability]
            [editor.pose]
            [editor.resource-node :as resource-node]
            [editor.ui :as ui]
            [editor.workspace :as workspace]
            [integration.test-util :as test-util]
            [util.digest :as digest])
  (:import [java.awt.image BufferedImage]
           [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.util UUID]
           [javax.imageio ImageIO]))

(defn- png-bytes []
  (let [output (ByteArrayOutputStream.)]
    (ImageIO/write (BufferedImage. 2 2 BufferedImage/TYPE_INT_ARGB) "png" output)
    (.toByteArray output)))

(defn- stage! [root transaction-id path action bytes expected]
  (let [hash (str "sha256:" (digest/stream->sha256-hex (ByteArrayInputStream. bytes)))
        target (io/file root ".internal" "photocraft" "transactions" transaction-id "files" (str "sha256-" (subs hash 7)))]
    (io/make-parents target)
    (with-open [output (io/output-stream target)] (.write output ^bytes bytes))
    {:path path :action action :expectedDiskHash expected :newContentHash hash}))

(defn- publication-service [project root id]
  (let [completed (promise)]
    {:root root :project project :id id :publish-state (atom {}) :completed completed
     :release-fn (fn [transaction-id]
                   (ui/run-now (disk/end-managed-write! transaction-id))
                   (deliver completed true))}))

(defn- await-publication [service request]
  (is (= true (deref (:completed service) 10000 :timeout)))
  (publish/status service request))

;; Separate HTTP requests decode equal transaction ids into distinct String objects.
(deftest publication-gate-releases-by-transaction-value
  (let [claimed-id (str (UUID/randomUUID))
        journal-id (String. ^String claimed-id)]
    (is (not (identical? claimed-id journal-id)))
    (ui/run-now
      (is (disk/begin-managed-write! claimed-id))
      (try
        (disk/end-managed-write! (str (UUID/randomUUID)))
        (is (disk/managed-write-active?))
        (disk/end-managed-write! journal-id)
        (is (not (disk/managed-write-active?)))
        (is (disk-availability/available?))
        (disk/end-managed-write! journal-id)
        (is (disk-availability/available?))
        (finally
          (disk/end-managed-write! claimed-id))))))

;; Structured creation keeps rotations as quaternions; frozen saving leaves an unrelated dirty GUI untouched.
(deftest structured-creation-saves-only-its-target
  (test-util/with-temp-project-content {"/test.gui" {:material "/builtins/materials/gui.material"}
                                        "/other.gui" {:material "/builtins/materials/gui.material"}}
    (let [root (workspace/project-directory workspace)
          service {:project project :id "integration" :root root}
          other (project/get-resource-node project "/other.gui")
          other-before (slurp (io/file root "other.gui"))
          _ (test-util/prop! other :max-nodes 777)
          transaction-id (str (UUID/randomUUID))
          request {:transactionId transaction-id :path "/test.gui"
                   :changes {:nodes [{:id "new-node" :parent "" :kind "gui-node-type-box"
                                      :values {:position [10.0 20.0 0.0] :rotation [0.0 0.0 35.0]
                                               :manual_size [30.0 40.0 0.0]}}]
                             :textures [] :renames []}}
          snapshot (ui/run-now (#'publish/apply-graph! service request))
          scene (project/get-resource-node project "/test.gui")
          node (:ref (first (craft-project/gui-nodes project scene)))]
      (is (s/valid? :editor.pose/rotation (g/node-value node :rotation)))
      (is (= [scene] (mapv :node-id (:save-data snapshot))))
      (disk/write-save-snapshot! snapshot)
      (is (false? (g/node-value scene :dirty)))
      (is (g/node-value other :dirty))
      (is (= other-before (slurp (io/file root "other.gui"))))
      (is (.contains (slurp (io/file root "test.gui")) "new-node")))))

;; Edits made after capturing a save snapshot remain dirty and are not accidentally written to disk.
(deftest snapshot-save-preserves-a-later-editor-edit
  (test-util/with-temp-project-content {"/test.gui" {:material "/builtins/materials/gui.material"}}
    (let [scene (project/get-resource-node project "/test.gui")
          _ (test-util/prop! scene :max-nodes 700)
          snapshot (disk/capture-save-snapshot project [scene])
          frozen (resource-node/save-data-content (first (:save-data snapshot)))]
      (test-util/prop! scene :max-nodes 701)
      (disk/write-save-snapshot! snapshot)
      (is (g/node-value scene :dirty))
      (is (= 701 (g/node-value scene :max-nodes)))
      (is (= frozen (slurp (io/file (workspace/project-directory workspace) "test.gui")))))))

;; Invalid input rolls back the complete operation sequence without erasing earlier user history.
(deftest failed-graph-application-restores-earlier-scene
  (test-util/with-temp-project-content {"/test.gui" {:material "/builtins/materials/gui.material"}}
    (let [scene (project/get-resource-node project "/test.gui")
          _ (test-util/prop! scene :max-nodes 700)
          before (g/node-value scene :save-value)
          undo-count (g/undo-stack-count :undo/global)
          service {:project project :id "integration" :root (workspace/project-directory workspace)}]
      (is (thrown? Exception
                   (ui/run-now
                     (#'publish/apply-graph! service
                       {:transactionId (str (UUID/randomUUID)) :path "/test.gui"
                        :changes {:nodes [{:id "new-node" :parent "" :kind "gui-node-type-box"
                                           :values {:not_a_gui_property 12}}] :textures [] :renames []}}))))
      (is (= before (g/node-value scene :save-value)))
      (is (= undo-count (g/undo-stack-count :undo/global))))))

;; A new PNG and atlas load without resetting unrelated undo, then GUI/atlas save together.
(deftest publishes-new-image-and-updates-atlas-without-resetting-undo
  (test-util/with-temp-project-content {"/test.gui" {:material "/builtins/materials/gui.material"}
                                        "/other.gui" {:material "/builtins/materials/gui.material"}}
    (let [root (workspace/project-directory workspace)
          other (project/get-resource-node project "/other.gui")
          _ (test-util/prop! other :max-nodes 700)
          undo-count (g/undo-stack-count :undo/global)
          transaction-id (str (UUID/randomUUID))
          service (publication-service project root "integration")
          baseline (ui/run-now (craft-project/snapshot service "/test.gui"))
          request {:schemaVersion 1 :transactionId transaction-id :planHash (str "sha256:" (apply str (repeat 64 "a")))
                   :editorSessionId "integration" :path "/test.gui" :expectedDiskHash (:diskHash baseline)
                   :expectedEditorGraphRevision (:editorGraphRevision baseline)
                   :resourceUpdates [(stage! root transaction-id "/managed/hero.png" "create" (png-bytes) nil)
                                     (stage! root transaction-id "/managed/ui.atlas" "create"
                                             (.getBytes "images { image: \"/managed/hero.png\" }\n" "UTF-8") nil)]
                   :changes {:nodes [{:id "hero" :parent "" :kind "gui-node-type-box"
                                      :values {:texture "managed/hero" :rotation [0 0 25] :manual_size [32 32 0]}}]
                             :textures [{:name "managed" :texture "/managed/ui.atlas"}] :renames []}}]
      (is (= "PREPARED" (:state (publish/prepare! service request))))
      (is (= "APPLYING" (:state (publish/apply! service request))))
      (let [result (await-publication service request)]
        (is (= "COMMITTED" (:state result)) (pr-str result))
        (is (= 2 (count (get-in result [:receipt :resources])))))
      (let [scene (project/get-resource-node project "/test.gui")
            atlas-node (project/get-resource-node project "/managed/ui.atlas")
            atlas-before (g/node-value atlas-node :save-value)
            second-id (str (UUID/randomUUID))
            update (stage! root second-id "/managed/ui.atlas" "replace"
                           (.getBytes "animations { id: \"named\" images { image: \"/managed/hero.png\" } }\n" "UTF-8")
                           (craft-project/file-hash root "/managed/ui.atlas"))
            entries (craft-resources/read-staged! service {:transactionId second-id :resourceUpdates [update]})
            _ (craft-resources/check-atlases! service entries)
            snapshot (ui/run-now
                       (#'publish/apply-graph! service
                         {:transactionId second-id :path "/test.gui" :preparedResources entries
                          :changes {:nodes [{:id "hero" :parent "" :kind "gui-node-type-box"
                                             :values {:texture "managed/named"}}]
                                    :textures [{:name "managed" :texture "/managed/ui.atlas"}] :renames []}}))]
        (is (= atlas-node (project/get-resource-node project "/managed/ui.atlas")))
        (is (= [scene atlas-node] (mapv :node-id (:save-data snapshot))))
        (is (> (g/undo-stack-count :undo/global) undo-count))
        (disk/write-save-snapshot! snapshot)
        (is (g/node-value other :dirty))
        (is (.contains (slurp (io/file root "managed/ui.atlas")) "named"))
        (ui/run-now (g/undo! :undo/global))
        (is (= atlas-before (g/node-value atlas-node :save-value)))
        (is (= 700 (g/node-value other :max-nodes)))
        (is (.isFile (io/file root "managed/hero.png")))
        (ui/run-now (g/undo! :undo/global))
        (is (= [] (craft-project/gui-nodes project scene)))
        (is (= atlas-node (project/get-resource-node project "/managed/ui.atlas")))
        (ui/run-now (g/undo! :undo/global))
        (is (not= 700 (g/node-value other :max-nodes)))
        (ui/run-now (g/redo! :undo/global) (g/redo! :undo/global) (g/redo! :undo/global))
        (is (= "hero" (:id (first (craft-project/gui-nodes project scene)))))
        (is (= 700 (g/node-value other :max-nodes)))))))

;; A destructive or unrelated disk delta is rejected before the workspace snapshot or undo can change.
(deftest refresh-does-not-clear-undo-for-an-unrelated-file-change
  (test-util/with-temp-project-content {"/test.gui" {:material "/builtins/materials/gui.material"}}
    (let [scene (project/get-resource-node project "/test.gui")
          _ (test-util/prop! scene :max-nodes 700)
          before (g/node-value workspace :resource-snapshot)
          undo-count (g/undo-stack-count :undo/global)]
      (spit (io/file (workspace/project-directory workspace) "test.gui") "max_nodes: 777\n")
      (is (= "UNDO_BARRIER_REQUIRED"
             (try (ui/run-now (craft-project/safe-refresh! project #{}))
                  (catch clojure.lang.ExceptionInfo error (:code (ex-data error))))))
      (is (= before (g/node-value workspace :resource-snapshot)))
      (is (= undo-count (g/undo-stack-count :undo/global))))))

;; Saving private collaborative source, leases and journals never introduces game resources or an undo barrier.
(deftest private-project-metadata-does-not-change-editor-resources-or-undo
  (test-util/with-temp-project-content {"/test.gui" {:material "/builtins/materials/gui.material"}}
    (let [root (workspace/project-directory workspace)
          scene (project/get-resource-node project "/test.gui")
          _ (test-util/prop! scene :max-nodes 700)
          before (g/node-value workspace :resource-snapshot)
          undo-count (g/undo-stack-count :undo/global)]
      (doseq [path [".photocraft/project.json" ".photocraft/local/leases.json"
                    ".photocraft/sources/revision/document.pcraft"
                    ".photocraft/publications/transaction/record.json" ".internal/photocraft-test-project"]]
        (let [file (io/file root path)]
          (io/make-parents file)
          (spit file "private collaboration data")))
      (ui/run-now (craft-project/safe-refresh! project #{}))
      (is (= before (g/node-value workspace :resource-snapshot)))
      (is (= undo-count (g/undo-stack-count :undo/global)))
      (is (nil? (project/get-resource-node project "/.photocraft/project.json")))
      (spit (io/file root ".photocraft/local/leases.json") "changed lease")
      (ui/run-now (craft-project/safe-refresh! project #{}))
      (is (= undo-count (g/undo-stack-count :undo/global)))
      (spit (io/file root ".photocraft-other.json") "not reserved")
      (is (= "UNDO_BARRIER_REQUIRED"
             (try (ui/run-now (craft-project/safe-refresh! project #{}))
                  (catch clojure.lang.ExceptionInfo error (:code (ex-data error))))))
      (is (= undo-count (g/undo-stack-count :undo/global)))
      (ui/run-now (g/undo! :undo/global))
      (is (not= 700 (g/node-value scene :max-nodes))))))

;; Reparenting and ordinary type changes preserve descendants and undo the whole scene operation.
(deftest reparent-and-retype-keep-descendants-and-undo
  (test-util/with-temp-project-content {"/test.gui" {:material "/builtins/materials/gui.material"}}
    (let [service {:root (workspace/project-directory workspace) :project project}
          scene (project/get-resource-node project "/test.gui")
          node (fn [id parent kind] {:id id :parent parent :kind kind :values {:position [10 20 0] :rotation [0 0 30] :scale [1 1 1]}})
          _ (ui/run-now (#'publish/apply-graph! service
                         {:transactionId (str (UUID/randomUUID)) :path "/test.gui"
                          :changes {:nodes [(node "a" "" "gui-node-type-box") (node "b" "a" "gui-node-type-box")
                                            (node "c" "b" "gui-node-type-box")] :textures [] :renames []}}))
          before (g/node-value scene :save-value)]
      (ui/run-now (#'publish/apply-graph! service
                    {:transactionId (str (UUID/randomUUID)) :path "/test.gui"
                     :changes {:nodes [(node "b" "" "gui-node-type-box") (node "a" "b" "gui-node-type-pie")
                                       (node "c" "a" "gui-node-type-box")] :textures [] :renames []}}))
      (is (= #{["a" "b" "gui-node-type-pie"] ["b" "" "gui-node-type-box"] ["c" "a" "gui-node-type-box"]}
             (into #{} (map (juxt :id :parent :kind)) (craft-project/gui-nodes project scene))))
      (doseq [{:keys [ref]} (craft-project/gui-nodes project scene)]
        (is (s/valid? :editor.pose/rotation (g/node-value ref :rotation))))
      (ui/run-now (g/undo! :undo/global))
      (is (= before (g/node-value scene :save-value))))))

;; Two GUI targets share one graph operation: a failed second target restores the first without any target save.
(deftest multiple-targets-commit-together-or-restore-their-graphs
  (doseq [invalid [false true]]
    (test-util/with-temp-project-content {"/one.gui" {:material "/builtins/materials/gui.material"}
                                          "/two.gui" {:material "/builtins/materials/gui.material"}}
      (let [root (workspace/project-directory workspace)
            service (publication-service project root "multi")
            originals (mapv #(slurp (io/file root %)) ["one.gui" "two.gui"])
            targets (mapv (fn [path]
                            (let [baseline (ui/run-now (craft-project/snapshot service path))]
                              {:path path :expectedDiskHash (:diskHash baseline) :expectedEditorGraphRevision (:editorGraphRevision baseline)
                               :changes {:nodes [{:id "added" :parent "" :kind "gui-node-type-box"
                                                  :values (if (and invalid (= "/two.gui" path)) {:position ["invalid" 0 0]} {:rotation [0 0 45]})}]
                                         :textures [] :renames []}})) ["/one.gui" "/two.gui"])
            request {:schemaVersion 1 :transactionId (str (UUID/randomUUID)) :planHash (str "sha256:" (apply str (repeat 64 "b")))
                     :editorSessionId "multi" :targets targets :resourceUpdates []}]
        (publish/prepare! service request)
        (publish/apply! service request)
        (let [result (await-publication service request)]
          (is (= (if invalid "RECOVERY_REQUIRED" "COMMITTED") (:state result)) (pr-str result))
          (if invalid
            (do
              (is (= originals (mapv #(slurp (io/file root %)) ["one.gui" "two.gui"])))
              (doseq [path ["/one.gui" "/two.gui"]]
                (is (= [] (craft-project/gui-nodes project (project/get-resource-node project path))))))
            (do
              (is (= 2 (count (get-in result [:receipt :targets]))))
              (doseq [target (get-in result [:receipt :targets])]
                (let [snapshot (ui/run-now (craft-project/snapshot service (:path target)))]
                  (is (= (:sceneHash snapshot) (:sceneHash target)))
                  (is (= (:editorGraphRevision snapshot) (:editorGraphRevision target))))))))))))

;; Full exported defaults must honor native mode dependencies and preserve unchanged read-only values.
(deftest full-box-properties-apply-size-mode-before-slice9
  (test-util/with-temp-project-content {"/test.gui" {:material "/builtins/materials/gui.material"}}
    (let [service {:root (workspace/project-directory workspace) :project project}
          scene (project/get-resource-node project "/test.gui")
          values {:slice9 [1 2 3 4] :size_mode "size-mode-manual" :manual_size [22 22 0]
                  :position [10 20 0] :rotation [0 0 30] :scale [1 1 1] :texture ""
                  :material "" :layer "" :enabled true :visible true :alpha 1.0 :color [1 1 1 1]
                  :inherit_alpha false :clipping_visible true :clipping_inverted false
                  :clipping_mode "clipping-mode-none" :blend_mode "blend-mode-alpha"
                  :pivot "pivot-center" :x_anchor "xanchor-none" :y_anchor "yanchor-none"
                  :adjust_mode "adjust-mode-fit"}
          apply-values! (fn [values]
                          (ui/run-now (#'publish/apply-graph! service
                                        {:path "/test.gui" :transactionId (str (UUID/randomUUID))
                                         :changes {:nodes [{:id "box" :kind "gui-node-type-box" :parent "" :values values}]
                                                   :textures [] :renames []}})))]
      (apply-values! values)
      (let [node (:ref (first (craft-project/gui-nodes project scene)))]
        (is (= :size-mode-manual (g/node-value node :size-mode)))
        (is (= [1.0 2.0 3.0 4.0] (mapv double (g/node-value node :slice9))))
        (apply-values! (assoc values :size_mode "size-mode-auto"))
        (is (= :size-mode-auto (g/node-value node :size-mode)))
        (is (= [1.0 2.0 3.0 4.0] (mapv double (g/node-value node :slice9))))
        (let [before (g/node-value scene :save-value)]
          (is (= {:property "slice9" :node "box" :code "UNSUPPORTED_GUI_FEATURE"}
                 (try (apply-values! (assoc values :size_mode "size-mode-auto" :slice9 [4 3 2 1]))
                      (catch clojure.lang.ExceptionInfo error (ex-data error)))))
          (is (= before (g/node-value scene :save-value))))))))

;; Invalid declared properties are rejected during prepare, before any staged image can be installed.
(deftest prepare-rejects-unknown-properties-before-installation
  (test-util/with-temp-project-content {"/test.gui" {:material "/builtins/materials/gui.material"}}
    (let [root (workspace/project-directory workspace)
          service (publication-service project root "preflight")
          baseline (ui/run-now (craft-project/snapshot service "/test.gui"))
          transaction-id (str (UUID/randomUUID))
          update (stage! root transaction-id "/managed/icon.png" "create" (png-bytes) nil)
          request {:schemaVersion 1 :transactionId transaction-id :planHash (str "sha256:" (apply str (repeat 64 "c")))
                   :editorSessionId "preflight" :resourceUpdates [update]
                   :targets [{:path "/test.gui" :expectedDiskHash (:diskHash baseline)
                              :expectedEditorGraphRevision (:editorGraphRevision baseline)
                              :changes {:nodes [{:id "new" :parent "" :kind "gui-node-type-box" :values {:invalid_property 1}}]
                                        :textures [] :renames []}}]}]
      (is (= {:path "/test.gui" :node "new" :property "invalid_property" :code "UNSUPPORTED_GUI_FEATURE"}
             (try (publish/prepare! service request)
                  (catch clojure.lang.ExceptionInfo error (ex-data error)))))
      (is (not (.exists (io/file root "managed/icon.png"))))
      (is (= (:sceneHash baseline) (:sceneHash (ui/run-now (craft-project/snapshot service "/test.gui"))))))))
