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

(ns editor.gui-rect-test
  (:require [clojure.test :refer [deftest is]]
            [editor.gui-layout :as layout]
            [editor.gui-rect :as rect]))

;; All sixteen anchor presets retain margins as parents resize; fixed axes keep
;; their dimensions and evaluating twice cannot accumulate layout drift.
(deftest rect-presets-resize-test
  (doseq [{:keys [anchor-min anchor-max]} rect/presets
          pivot (into [] (map val) layout/pivot-points)
          parent-pivot [[0.0 0.0] [0.5 0.5] [1.0 1.0]]]
    (let [reference (into [200.0 100.0] parent-pivot)
          parent (into [400.0 300.0] parent-pivot)
          before {:position [30.0 20.0 0.0] :size [40.0 20.0 0.0]}
          after (rect/resolve-geometry (:position before) (:size before) pivot anchor-min anchor-max reference parent)]
      (is (= (rect/offsets (:position before) (:size before) pivot anchor-min anchor-max reference)
             (rect/offsets (:position after) (:size after) pivot anchor-min anchor-max parent)))
      (is (= before (rect/resolve-geometry (:position before) (:size before) pivot anchor-min anchor-max reference reference)))
      (is (= after (rect/from-offsets (rect/offsets (:position after) (:size after) pivot anchor-min anchor-max parent)
                                     (:position after) (:size after) pivot anchor-min anchor-max parent))))))
