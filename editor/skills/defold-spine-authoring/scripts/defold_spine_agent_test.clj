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

(ns defold-spine-agent-test
  (:require [clojure.test :refer [deftest is]]
            [defold-spine-agent :as agent]
            [dynamo.graph :as g]
            [editor.disk :as disk]
            [editor.skeletal-animation-data :as data]
            [editor.ui :as ui]
            [internal.system :as system]))

(g/defnode AgentFixture (property document g/Any) (property dirty g/Bool (default false)))

;; Failed batches and stale previews must leave document/undo intact.
(deftest atomic-edit-and-undo-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (data/new-document "agent-fixture")
            node (g/make-node! AgentFixture :document doc)
            context {:resource-node node :state (atom {:mode "setup"})}
            request {:revision (agent/revision doc) :operations [{:op "bone.add" :values {:id "tail" :parent "root" :length 40.0}}]}
            history (:undo @g/*the-system*)]
        (is (:dry-run (agent/edit! context request)))
        (is (= doc (g/node-value node :document)))
        (is (= history (:undo @g/*the-system*)))
        (is (thrown? clojure.lang.ExceptionInfo (agent/edit! context (assoc request :apply true :revision "stale"))))
        (is (thrown? clojure.lang.ExceptionInfo (agent/edit! context (-> request (assoc :apply true) (update :operations conj {:op "bone.remove" :id "missing"})))))
        (is (= doc (g/node-value node :document)))
        (is (= history (:undo @g/*the-system*)))
        (let [result (agent/edit! context (assoc request :apply true))]
          (is (:applied result))
          (is (= 2 (count (:bones (g/node-value node :document)))))
          (is (:undone (agent/undo! context result)))
          (is (= doc (g/node-value node :document))))))))

;; Another edit anywhere in the global history must not be undone accidentally.
(deftest history-and-pending-gesture-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (data/new-document "one")
            node (g/make-node! AgentFixture :document doc)
            other (g/make-node! AgentFixture :document doc)
            context {:resource-node node :state (atom {:mode "setup"})}
            request {:revision (agent/revision doc) :apply true
                     :operations [{:op "bone.add" :values {:id "tail" :parent "root" :length 40.0}}]}]
        (swap! (:state context) assoc :drag {})
        (is (thrown? clojure.lang.ExceptionInfo (agent/edit! context request)))
        (swap! (:state context) dissoc :drag)
        (let [result (agent/edit! context request)]
          (g/transact (g/set-property other :document (assoc doc :name "someone-else")))
          (is (thrown? clojure.lang.ExceptionInfo (agent/undo! context result)))
          (is (= "someone-else" (:name (g/node-value other :document))))
          (is (= 2 (count (:bones (g/node-value node :document))))))))))

;; Agent recipes use the same authoring functions as the editor: mesh, binding,
;; animation keys, deformation cleanup, and constraint defaults remain valid.
(deftest authoring-batch-test
  (let [doc (agent/proposal (data/new-document "workflow")
                            [{:op "bone.add" :values {:id "tail" :parent "root" :length 60.0}}
                             {:op "slot.add" :values {:id "image" :bone "tail" :attachment {:type "region" :path "/tail.png" :width 100.0 :height 50.0}}}
                             {:op "mesh.grid" :slot "image" :columns 3 :rows 3}
                             {:op "mesh.bind" :slot "image" :bones ["tail"]}
                             {:op "animation.add" :values {:id "sway" :duration 1.0}}
                             {:op "key.set" :animation "sway" :bone "tail" :property "rotation" :time 0.0 :value -5.0}
                             {:op "key.set" :animation "sway" :bone "tail" :property "rotation" :time 0.5 :value 5.0}
                             {:op "key.set" :animation "sway" :bone "tail" :property "rotation" :time 1.0 :value -5.0}])
        restored (agent/proposal doc [{:op "mesh.restore-image" :skin "default" :slot "image" :name "image"}])]
    (is (= "mesh" (get-in doc [:slots 0 :attachment :type])))
    (is (= 16 (count (get-in doc [:slots 0 :attachment :weights]))))
    (is (= "region" (get-in restored [:slots 0 :attachment :type])))
    (is (= 3 (count (get-in restored [:animations 1 :tracks 0 :keys]))))
    (is (= (:bones doc) (:bones restored)))))

;; A human edit or gesture during off-thread calculation invalidates the result.
(deftest proposal-race-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (data/new-document "race")
            node (g/make-node! AgentFixture :document doc)
            context {:resource-node node :state (atom {:mode "setup"})}
            request {:revision (agent/revision doc) :apply true
                     :operations [{:op "bone.add" :values {:id "tail" :parent "root" :length 40.0}}]}
            before (agent/capture-edit context request)
            after (agent/proposal before (:operations request))]
        (swap! (:state context) assoc :drag {})
        (is (thrown? clojure.lang.ExceptionInfo (agent/commit-proposal! context request before after)))
        (is (= doc (g/node-value node :document)))
        (swap! (:state context) dissoc :drag)
        (g/transact (g/set-property node :document (assoc doc :name "human-edit")))
        (let [history (:undo @g/*the-system*)]
          (is (thrown? clojure.lang.ExceptionInfo (agent/commit-proposal! context request before after)))
          (is (= "human-edit" (:name (g/node-value node :document))))
          (is (= history (:undo @g/*the-system*))))))))

;; Sampling must return actual weighted vertices and reject invalid time ranges.
(deftest sample-animation-test
  (let [doc (agent/proposal (data/new-document "sample")
                            [{:op "slot.add" :values {:id "image" :bone "root" :attachment {:type "region" :path "/image.png" :width 40.0 :height 20.0}}}
                             {:op "mesh.grid" :slot "image" :columns 1 :rows 1}
                             {:op "mesh.bind" :slot "image" :bones ["root"]}
                             {:op "animation.add" :values {:id "move" :duration 1.0}}
                             {:op "key.set" :animation "move" :bone "root" :property "x" :time 0.0 :value 0.0}
                             {:op "key.set" :animation "move" :bone "root" :property "x" :time 1.0 :value 10.0}])
        request {:animation "move" :times [0.0 1.0] :slot "image" :bones ["root"] :vertices true}
        frames (:frames (agent/sample-document doc request))]
    (is (= 4 (count (:vertices (first frames)))))
    (is (= 10.0 (- (ffirst (:vertices (second frames))) (ffirst (:vertices (first frames))))))
    (is (thrown? clojure.lang.ExceptionInfo (agent/sample-document doc (assoc request :times [-1]))))))

;; Scoped saves reject stale disk data and release their lock on failure.
(deftest scoped-save-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (let [doc (data/new-document "save")
          node (ui/run-now (g/make-node! AgentFixture :document doc))
          context {:resource-node node :project 4 :state (atom {})}
          events (atom [])
          request {:apply true :revision (agent/revision doc) :disk-revision "disk-before" :resource "/one.skeleton"}]
      ;; Stub disk boundaries to inject failures without live file I/O.
      #_{:clj-kondo/ignore [:discouraged-var]}
      (with-redefs [agent/find-context (constantly context)
                    agent/disk-revision (constantly "disk-before")
                    disk/begin-managed-write! (fn acquire [_] (swap! events conj :lock) true)
                    disk/end-managed-write! (fn release [_] (swap! events conj :unlock))
                    disk/capture-save-snapshot (fn capture [project nodes] {:project project :nodes nodes})
                    disk/write-save-snapshot! (fn write-snapshot [snapshot] (swap! events conj snapshot))]
        (is (thrown? clojure.lang.ExceptionInfo (agent/save-document! (assoc request :apply false))))
        (is (thrown? clojure.lang.ExceptionInfo (agent/save-document! (assoc request :disk-revision "stale"))))
        (is (= [] @events))
        (is (:saved (agent/save-document! request)))
        (is (= [:lock {:project 4 :nodes [node]} :unlock] @events))
        (reset! events [])
        (with-redefs [disk/write-save-snapshot! (fn fail-save [_] (throw (java.io.IOException. "fixture")))]
          (is (thrown? java.io.IOException (agent/save-document! request)))
          (is (= [:lock :unlock] @events)))))))
