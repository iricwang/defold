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

(ns integration.gui-rect-test
  (:require [clojure.test :refer [deftest is]]
            [dynamo.graph :as g]
            [editor.gui-layout :as layout]
            [editor.gui-rect :as rect]
            [integration.test-util :as test-util]
            [util.coll :as coll]))

;; Editing presets must use real graph properties, serialize native rectangle
;; fields, resize children with their parent, and leave unrelated legacy nodes alone.
(deftest native-rect-graph-test
  (test-util/with-loaded-project
    (let [scene (test-util/resource-node project "/logic/main.gui")
          ids (g/node-value scene :node-ids)
          parent (ids "atlas_node")
          child (ids "atlas_child")
          legacy (ids "left")
          legacy-pose (g/node-value legacy :pose)]
      (test-util/prop! parent :size-mode :size-mode-manual)
      (test-util/prop! parent :manual-size [200.0 100.0 0.0])
      (test-util/prop! child :size-mode :size-mode-manual)
      (test-util/prop! child :manual-size [160.0 70.0 0.0])
      (test-util/prop! child :position [10.0 5.0 0.0])
      (layout/commit! (layout/rect-preset-changes child (last rect/presets) false false (g/make-evaluation-context)) "Rect preset")
      (is (= [160.0 70.0 0.0] (g/node-value child :rect-size)))
      (is (= [10.0 5.0 0.0] (g/node-value child :rect-position)))
      (test-util/prop! parent :manual-size [400.0 200.0 0.0])
      (is (= [360.0 170.0 0.0] (g/node-value child :rect-size)))
      (is (= legacy-pose (g/node-value legacy :pose)))
      (let [saved (g/node-value scene :save-value)
            node (coll/first-where #(= "atlas_child" (:id %)) (:nodes saved))]
        (is (true? (:rect-transform node)))
        (is (= [1.0 1.0 0.0 0.0] (:rect-anchor-max node)))
        (is (= [160.0 70.0 0.0 0.0] (:size node))))
      (doseq [preset rect/presets
              shift [false true]
              alt [false true]]
        (let [before (g/node-value child :rect-geometry)
              old-pivot (g/node-value child :pivot)]
          (layout/commit! (layout/rect-preset-changes child preset shift alt (g/make-evaluation-context)) "Rect preset")
          (is (= (:anchor-min preset) (g/node-value child :rect-anchor-min)))
          (is (= (:anchor-max preset) (g/node-value child :rect-anchor-max)))
          (when-not (or shift alt)
            (is (= before (g/node-value child :rect-geometry))))
          (when-not shift (is (= old-pivot (g/node-value child :pivot))))
          (when shift (is (= (:pivot preset) (layout/pivot-points (g/node-value child :pivot))))))))))
