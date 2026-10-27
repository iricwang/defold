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

(ns editor.skeletal-animation-tools-test
  (:require [clojure.test :refer [deftest is testing]]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-tools :as tools]))

(defn- near? [a b]
  (< (abs (double (- a b))) 1.0e-6))

(defn- rig [inherit]
  (-> (data/new-document "tools")
      (data/update-bone "root" {:x 100.0 :y -25.0 :rotation 47.0 :scale-x -1.8 :scale-y 0.7})
      (data/add-bone {:id "child" :parent "root" :x 30.0 :y -12.0 :rotation 390.0 :scale-x 1.3 :scale-y -0.6
                      :shear-x 10.0 :shear-y 20.0 :inherit inherit})))

;; Each coordinate space must edit the displayed value, even under a mirrored,
;; nonuniformly scaled parent and all five supported inheritance modes.
(deftest coordinate-edit-roundtrip-test
  (doseq [inherit ["normal" "onlyTranslation" "noRotationOrReflection" "noScale" "noScaleOrReflection"]
          space ["local" "parent" "world"]
          property ["x" "y" "rotation"]]
    (testing (str inherit " " space " " property)
      (let [doc (rig inherit)
            bone (second (:bones doc))
            transforms (data/world-transforms (:bones doc))
            changes (tools/local-changes bone transforms space property 123.0)
            edited (data/update-bone doc "child" changes)
            displayed (tools/displayed-transform (second (:bones edited)) (data/world-transforms (:bones edited)) space)]
        (is (near? 123.0 (get displayed (keyword property))))))))

;; Local rotation must retain multiple turns; world rotation changes direction
;; without erasing winding, and world scale entry still writes local scale.
(deftest winding-and-world-scale-test
  (let [doc (rig "normal") bone (second (:bones doc)) transforms (data/world-transforms (:bones doc))
        displayed (tools/displayed-transform bone transforms "world")]
    (is (= 390.0 (:rotation (tools/displayed-transform bone transforms "local"))))
    (is (<= 0.0 (:rotation displayed) 360.0))
    (is (= {:scale-x 2.5} (tools/local-changes bone transforms "world" "scale-x" 2.5)))
    (is (neg? (:scale-y displayed)))
    (is (not (near? (:scale-x bone) (:scale-x displayed))))))

;; Axes are normalized for a fixed pixel-size gizmo; scale and shear always
;; use the bone's own axes, while World translate uses screen-independent XY.
(deftest gizmo-axes-test
  (let [doc (rig "normal") bone (second (:bones doc)) transforms (data/world-transforms (:bones doc))]
    (is (= [[1.0 0.0] [0.0 1.0]] (tools/axes bone transforms "world" "move")))
    (is (= (tools/axes bone transforms "local" "move") (tools/axes bone transforms "world" "scale")))
    (doseq [[x y] (tools/axes bone transforms "parent" "move")]
      (is (near? 1.0 (Math/hypot x y))))))

;; Collapsed axes reject inverse editing instead of persisting NaN or infinity.
(deftest singular-transform-test
  (let [doc (data/update-bone (rig "normal") "root" {:scale-x 0.0})
        bone (second (:bones doc)) transforms (data/world-transforms (:bones doc))]
    (is (thrown? clojure.lang.ExceptionInfo (tools/local-changes bone transforms "world" "x" 1.0)))
    (is (thrown? clojure.lang.ExceptionInfo (tools/local-changes bone transforms "parent" "x" Double/NaN)))))

;; Relative numeric expressions and fine increments must produce finite values.
(deftest relative-numeric-input-test
  (doseq [[text expected] [["+5" 15.0] ["+-2" 8.0] ["*2" 20.0] ["/4" 2.5] ["-5" -5.0] ["1.25" 1.25]]]
    (is (= expected (tools/numeric-value text 10.0))))
  (doseq [text ["/0" "NaN" "Infinity" "+" "bad"]]
    (is (thrown? NumberFormatException (tools/numeric-value text 10.0)))))

;; Auto-key-off changes live outside the authored document; undo/redo operates
;; on temporary poses and a graph change invalidates any old pending baseline.
(deftest pending-pose-history-test
  (let [doc (rig "normal")
        edited (data/set-keyframe doc "default" "child" "x" 0.5 80.0 "linear")
        initial {:mode "animate" :animation "default" :time 0.5}
        state (tools/stage-pose initial doc edited)]
    (is (= edited (tools/pending-document state doc)))
    (is (= [] (get-in doc [:animations 0 :tracks])))
    (is (nil? (tools/pending-document (assoc state :time 0.6) doc)))
    (is (nil? (tools/pending-document state (assoc doc :name "changed"))))
    (is (nil? (tools/pending-document (tools/undo-pose state false) doc)))
    (is (= edited (tools/pending-document (-> state (tools/undo-pose false) (tools/undo-pose true)) doc)))))

;; Seeking discards an unkeyed pose, but undo can recover its changed channels
;; at the new time, leaving both the original key and untouched channels intact.
(deftest recover-unkeyed-pose-after-seek-test
  (let [doc (-> (rig "normal")
                (data/set-keyframe "default" "child" "x" 0.5 10.0 "linear")
                (data/set-keyframe "default" "child" "y" 0.5 20.0 "linear"))
        edited (data/set-keyframe doc "default" "child" "x" 0.5 80.0 "linear")
        state (tools/stage-pose {:mode "animate" :animation "default" :time 0.5} doc edited)
        recovered (tools/restore-discarded {:mode "animate" :animation "default" :time 0.8 :discarded-pose (:pending-pose state)})
        preview (tools/pending-document recovered doc)]
    (is (= 80.0 (:x (second (data/sample-pose preview "default" 0.8)))))
    (is (= 10.0 (:x (second (data/sample-pose preview "default" 0.5)))))
    (is (= (get-in doc [:animations 0 :tracks 1]) (get-in preview [:animations 0 :tracks 1])))
    (is (= 2 (count (get-in preview [:animations 0 :tracks 0 :keys]))))))
