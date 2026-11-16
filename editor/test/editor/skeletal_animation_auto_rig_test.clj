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

(ns editor.skeletal-animation-auto-rig-test
  (:require [cljfx.plorer :as p]
            [clojure.test :refer [deftest is]]
            [dynamo.graph :as g]
            [editor.skeletal-animation-auto-rig :as rig]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-geometry :as geo]
            [editor.skeletal-animation-slots :as slots]
            [editor.skeletal-animation-trace-view :as trace]
            [editor.skeletal-animation-view :as view]
            [editor.ui :as ui]
            [internal.system :as system]
            [util.coll :as coll]))

(g/defnode RigFixture (property document g/Any))

(defn- fixture []
  (data/add-slot (data/new-document "auto-rig")
                 {:id "tail" :bone "root"
                  :attachment {:name "tail" :type "mesh" :path "/tail.png" :width 100.0 :height 100.0
                               :vertices [0.0 0.0 100.0 0.0 100.0 100.0 0.0 100.0]
                               :uvs [0.0 0.0 1.0 0.0 1.0 1.0 0.0 1.0] :triangles [0 1 2 0 2 3]
                               :constrained-edges [[0 1] [1 2] [2 3] [3 0]]}}))

(def guide {"size" [100 100] "centerline" [[0.3 0.1] [0.4 0.3] [0.6 0.5] [0.7 0.8]]})

;; New rigs must keep setup pixels, old animation/bones and normalized weights;
;; changing counts must create the requested topology without name collisions.
(deftest parametrized-rig-test
  (doseq [[n c] [[2 2] [8 3] [12 5] [32 8]]]
    (let [before (fixture)
          result (rig/generate {:document before :report {}} "tail" guide {"bone-count" n "control-count" c})
          doc (:document result)
          pose (:transforms (data/evaluate-pose-with-slots doc nil 0.0 slots/sample-slots))
          attachment (get-in doc [:slots 0 :attachment])
          vertices (vec (partitionv 2 (geo/mesh-world-vertices attachment (pose "root") pose nil)))
          uvs (vec (partitionv 2 (:uvs attachment)))]
      (is (= n (count (:rig-bones result))))
      (is (= c (count (:rig-control-ids result))))
      (is (= (first (:bones before)) (first (:bones doc))))
      (is (= (:animations before) (mapv #(dissoc % :deforms) (:animations doc))))
      (is (> (count vertices) 4))
      (doseq [[position uv] (mapv vector vertices uvs)]
        (is (< (apply max (mapv #(abs (- %1 (* 100.0 %2))) position uv)) 1.0e-5)))
      (doseq [influences (:weights attachment)]
        (is (< (abs (- 1.0 (transduce (map :weight) + influences))) 1.0e-8))
        (is (<= (count influences) 4)))
      (is (= "chain" (get-in doc [:path 0 :rotate-mode])))
      (is (= "length" (get-in doc [:path 0 :spacing-mode])))
      (is (= 0.0 (get-in doc [:path 0 :spacing])))
      (is (map? (data/import-spine-json (data/export-spine-json doc)))))))

;; Both middle and tip controls must deform vertices and retain bone axis
;; lengths even beyond reach; animation keys remain standard Spine channels.
(deftest control-and-animation-test
  (let [result (rig/generate {:document (fixture) :report {}} "tail" guide {"bone-count" 8 "control-count" 3})
        doc (:document result)
        pose (fn sample [d time] (:transforms (data/evaluate-pose-with-slots d "default" time slots/sample-slots)))
        before (pose doc 0.0)]
    (doseq [control (subvec (:rig-control-ids result) 1)]
      (let [bone (coll/first-where #(= control (:id %)) (:bones doc))
            changed (data/set-keyframe doc "default" control "x" 1.0 (+ 200.0 (:x bone)) "linear")
            after (pose changed 1.0)]
        (is (not= before after))
        (doseq [id (:rig-bones result)]
          (let [[a b] (after id)] (is (< (abs (- 1.0 (Math/hypot a b))) 1.0e-6))))))))

;; Confirming a whole generated rig is one graph transaction. Undo/redo restores
;; the exact complete document, including old mesh weights and animation data.
(deftest generation-undo-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [before (fixture)
            result (rig/generate {:document before :report {}} "tail" guide {"bone-count" 8 "control-count" 3})
            node (g/make-node! RigFixture :document before)
            context {:resource-node node :state (atom {})}]
        (#'view/commit! context (:document result))
        (is (= (:document result) (g/node-value node :document)))
        (g/undo! :undo/global)
        (is (= before (g/node-value node :document)))
        (g/redo! :undo/global)
        (is (= (:document result) (g/node-value node :document)))))))

;; Invalid counts/controls must fail before a proposal can change the document.
(deftest invalid-parameters-test
  (doseq [settings [{"bone-count" 0} {"bone-count" 3.5} {"control-count" 20}
                    {"control-points" [[0 0] [1 1]]}]]
    (is (thrown? clojure.lang.ExceptionInfo (rig/generate {:document (fixture) :report {}} "tail" guide (merge rig/defaults settings))))))

;; Pointer-dragging a generated control must change the proposed rig, remain
;; isolated until confirmation, and support undo/redo inside the preview.
(deftest preview-control-drag-test
  (let [file (java.io.File/createTempFile "auto-rig-preview" ".png")
        image (java.awt.image.BufferedImage. 100 100 java.awt.image.BufferedImage/TYPE_INT_ARGB)
        _ (javax.imageio.ImageIO/write image "png" file)
        commits (atom [])
        ready (promise)
        host (ui/run-now (javafx.scene.layout.StackPane.))
        window (ui/run-now (doto (javafx.stage.Stage.) (.setScene (javafx.scene.Scene. host 800.0 800.0)) (.show)))
        preview (ui/run-now
                  (trace/show! {:host host :image-url (str (.toURI file)) :tr identity :breadcrumb "tail" :rig-mode true
                                :settings rig/defaults
                                :propose (fn propose [settings] (rig/generate {:document (fixture) :report {}} "tail" guide settings))
                                :apply-result (fn commit-result [result _] (swap! commits conj (:document result)))}))
        state (:state preview)]
    (try
      (add-watch state ::initial (fn initial-ready [_ _ _ after] (when (:result after) (deliver ready true))))
      (when (:result @state) (deliver ready true))
      (is (= true (deref ready 10000 :timeout)))
      (let [before (:rig-controls (:result @state))
            done (promise)]
        (add-watch state ::changed (fn control-updated [_ _ _ after]
                                     (when (and (not (:busy after)) (not= before (:rig-controls (:result after)))) (deliver done true))))
        (ui/run-now
          (let [canvas (.lookup host "#skeletal-trace-canvas")
                [x y] (get-in @state [:control-screen-points 1])
                point (p/point canvas (/ x (.getWidth canvas)) (/ y (.getHeight canvas)))
                target (p/point canvas (/ (+ x 10.0) (.getWidth canvas)) (/ y (.getHeight canvas)))]
            (p/mouse-press! window point :primary)
            (p/mouse-move! window target)
            (p/mouse-release! window target :primary)))
        (is (= true (deref done 10000 :timeout)))
        (is (zero? (count @commits)))
        (let [after (:rig-controls (:result @state))]
          (ui/run-now (p/key-chord! window [:meta :z]))
          (is (= before (:rig-controls (:result @state))))
          (ui/run-now (p/key-chord! window [:meta :shift :z]))
          (is (= after (:rig-controls (:result @state))))
          (ui/run-now (p/mouse-click! window (p/point (.lookup host "#skeletal-trace-confirm") 0.5 0.5) :primary))
          (is (= 1 (count @commits)))
          (is (:closed @state))))
      (finally (ui/run-now ((:close preview)) (.hide window)) (.delete file)))))
