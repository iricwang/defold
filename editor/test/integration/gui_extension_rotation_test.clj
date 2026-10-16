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

(ns integration.gui-extension-rotation-test
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [dynamo.graph :as g]
            [editor.defold-project :as project]
            [editor.editor-extensions.graph :as ext-graph]
            [editor.editor-extensions.runtime :as rt]
            [editor.gui :as gui]
            [editor.pose]
            [integration.test-util :as test-util]
            [util.coll :as coll]))

;; GUI extension reads/writes must convert Euler angles in each layout so
;; transactions, scene evaluation, saving and quitting never receive a Vec3 pose.
(deftest gui-extension-rotation-roundtrip-test
  (test-util/with-loaded-project
    (let [scene (test-util/resource-node project "/gui/layouts.gui")
          parent (g/node-value scene :node-tree)
          node (gui/add-gui-node-with-props! scene parent
                                            (test-util/gui-node-type-info workspace gui/BoxNode)
                                            {:id "rotation_regression"} nil)
          runtime (rt/make)]
      (doseq [layout ["" "Landscape"]
              angles [[0.0 0.0 0.0] [0.0 0.0 45.0] [15.0 -20.0 35.0]]]
        (testing (str layout " " angles)
          (let [property (str (when-not (= "" layout) (str layout ":")) "rotation")
                evaluation-context (g/make-evaluation-context)
                setter (ext-graph/ext-lua-value-setter node property runtime project evaluation-context)]
            (is (some? setter))
            (g/transact (setter (rt/->lua angles)))
            (let [evaluation-context (g/make-evaluation-context)
                  getter (ext-graph/ext-value-getter node property project evaluation-context)
                  actual (getter)
                  quaternion (get-in (g/node-value node :layout->prop->value evaluation-context)
                                     [layout :rotation])]
              (is (= 3 (count actual)))
              (is (coll/every? true? (mapv #(< (Math/abs (double (- %1 %2))) 0.0001) angles actual))
                  (str "Euler readback: " actual))
              (is (s/valid? :editor.pose/rotation quaternion))
              (is (map? (g/valid-node-value scene :save-value evaluation-context)))
              (is (vector? (project/dirty-save-data project evaluation-context))))))))))
