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

(ns editor.scene-tools-animation-test
  (:require [clojure.test :refer [deftest is]]
            [dynamo.graph :as g]
            [editor.math :as math]
            [editor.scene-tools :as tools]
            [util.coll :as coll])
  (:import [javax.vecmath Vector3d]))

;; Animated gizmos produce pose overrides in both phases and never call resource manipulation.
(deftest animation-manipulation-routing-test
  (let [calls (atom [])
        opts {:animation-manipulation
              {:make-manipulation-fn (fn [property node-id]
                                       (swap! calls conj [property node-id])
                                       (fn [delta] (math/vecmath->clj delta)))}}
        manipulate (#'tools/make-drag-manipulations-fn opts :move-x (Vector3d.)
                                                       [{:node-id 1 :parent-world-transform math/identity-mat4}]
                                                       nil)]
    (with-redefs [tools/manip-move (fn [& _] (throw (AssertionError. "GUI resource must not be manipulated")))]
      (doseq [phase [:manip-phase/preview :manip-phase/commit]]
        (is (= {:node-id->prop-kw->override-value {1 {:position [20.0 0.0 0.0]}}}
               (tools/combine-manipulations (manipulate phase (Vector3d.) (Vector3d. 20.0 0.0 0.0)))))))
    (is (= [[:position 1] [:rotation 1] [:scale 1]] @calls))))

;; Release must commit through the animation handler and discard any resource transaction.
(deftest animation-release-commit-test
  (let [overrides {1 {:scale [2.0 3.0 1.0]}}
        commits (atom [])
        transactions (atom [])
        drag {:animation-manipulation {:apply! #(swap! commits conj [%1 %2])} :op-seq :drag}]
    (with-redefs [g/node-value (fn [_ property] (case property :drag-start-state drag :active-tool :scale))
                  g/transact (fn [& tx] (swap! transactions conj tx))
                  tools/apply-manipulator (fn [& _] {:tx-data [:forbidden-resource-write]
                                                     :node-id->prop-kw->override-value overrides})]
      (is (nil? (tools/handle-input 10 nil {:type :mouse-released} nil))))
    (is (= [[:commit overrides]] @commits))
    (is (coll/not-any? #{:forbidden-resource-write} (flatten @transactions)))))
