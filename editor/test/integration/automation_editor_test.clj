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

(ns integration.automation-editor-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [dynamo.graph :as g]
            [editor.automation-editor :as automation]
            [editor.craft-binding :as binding]
            [editor.defold-project :as project]
            [editor.workspace :as workspace]
            [integration.test-util :as test-util]
            [util.http-server :as http-server])
  (:import [java.util.concurrent TimeUnit]))

(defn request [snapshot]
  {:path (:path snapshot) :editorSessionId (:editorSessionId snapshot)
   :expectedRevision (:editorGraphRevision snapshot) :expectedHistory (:historyRevision snapshot)})

;; Unsaved edits stay in the graph, form one native undo step, and refuse stale or intervening edits.
(deftest gui-edit-undo-redo-and-conflicts
  (test-util/with-temp-project-content
    {"/test.gui" {:material "/builtins/materials/gui.material"
                   :nodes [{:id "button" :type :type-box :size-mode :size-mode-manual
                            :position [10.0 20.0 0.0 1.0] :size [80.0 40.0 0.0 0.0]}]}}
    (let [service {:project project :id "automation-test" :automation-state (atom {})
                   :root (workspace/project-directory workspace)}
          disk (slurp (io/file (:root service) "test.gui"))
          scene (project/get-resource-node project "/test.gui")
          _ (test-util/prop! scene :max-nodes 777)
          before (automation/snapshot service "/test.gui")
          tabs (g/node-value app-view :open-views)
          edit (assoc (request before) :operationId "move"
                      :changes [{:id "button" :property "position" :value [30.0 40.0 0.0]}])
          after (automation/edit! service edit)]
      (is (:dirty before))
      (is (:dirty after))
      (is (= [30 40 0] (get-in after [:nodes 0 :values "position"])))
      (is (= disk (slurp (io/file (:root service) "test.gui"))))
      (is (= tabs (g/node-value app-view :open-views)))
      (is (= "EDITOR_CONFLICT" (try (automation/edit! service (assoc edit :operationId "stale"))
                                     (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))
      (let [undone (automation/history! service (assoc (request after) :transactionId "move" :action "undo"))
            redone (automation/history! service (assoc (request undone) :transactionId "move" :action "redo"))]
        (is (= (:source before) (:source undone)))
        (is (= (:source after) (:source redone)))
        (is (= 777 (g/node-value scene :max-nodes)))
        (test-util/prop! scene :max-nodes 888)
        (let [current (automation/snapshot service "/test.gui")]
          (is (= "UNDO_CONFLICT" (try (automation/history! service (assoc (request current) :transactionId "move" :action "undo"))
                                        (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))))))
      (let [current (automation/snapshot service "/test.gui")]
        (is (thrown? Exception (automation/edit! service (assoc (request current) :operationId "invalid"
                                              :changes [{:id "button" :property "position" :value [1 2 3]}
                                                        {:id "missing" :property "position" :value [1 2 3]}]))))
        (is (= (:source current) (:source (automation/snapshot service "/test.gui"))))))))

;; The real stdio MCP client crosses authenticated HTTP, edits unsaved GUI, builds and compares hidden runtime images.
(deftest editor-mcp-runtime-roundtrip
  (when-let [python (System/getProperty "defold.automation.python")]
    (test-util/with-temp-project-content
      {"/input/game.input_binding" {}
       "/probe.gui" {:material "/builtins/materials/gui.material"
                      :nodes [{:id "button" :type :type-box :size-mode :size-mode-manual
                               :position [100.0 120.0 0.0 1.0] :size [80.0 40.0 0.0 0.0]
                               :color [0.0 1.0 0.0 1.0]}]}}
      (let [root (workspace/project-directory workspace)
            service {:project project :id "roundtrip" :root root :editor-token "isolated-test-token"
                     :automation-state (atom {})}
            handler (http-server/router-handler (#'binding/structured-routes service))
            server (http-server/start! (bound-fn [request] (handler request)) :host "127.0.0.1" :port 0)
            discovery (io/file root ".internal/photocraft/editor-adapter.json")
            output (io/file "../tmp/automation-editor-mcp.log")]
        (try
          (io/make-parents discovery)
          (spit discovery (json/write-str {:port (http-server/port server) :editorSessionId "roundtrip"}))
          (spit (io/file root ".internal/editor.token") "isolated-test-token")
          (let [process (-> (ProcessBuilder. ^java.util.List [python "-X" "utf8"
                                               (.getCanonicalPath (io/file "../scripts/automation/test_editor_runtime.py"))
                                               "--project" (.getCanonicalPath (io/file root))])
                            (.redirectErrorStream true)
                            (.redirectOutput output)
                            (.start))]
            (if (.waitFor process 300 TimeUnit/SECONDS)
              (is (zero? (.exitValue process)) (slurp output))
              (do (.destroyForcibly process) (is false "MCP editor roundtrip timed out"))))
          (finally (http-server/stop! server)))))))
