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

(ns editor.skeletal-animation-mesh-transform-test
  (:require [cljfx.plorer :as p]
            [clojure.test :refer [deftest is]]
            [dynamo.graph :as g]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-drag-test :as drag]
            [editor.skeletal-animation-geometry :as geometry]
            [editor.skeletal-animation-view :as view]
            [editor.ui :as ui]
            [internal.system :as system]
            [util.coll :as coll]))

(defn- mesh-document [weighted]
  (let [vertices [[20.0 40.0] [80.0 40.0] [80.0 100.0] [20.0 100.0]]
        attachment (cond-> {:type "mesh" :path "/image.png" :width 60.0 :height 60.0
                            :vertices (coll/into-> vertices [] cat) :uvs [0.0 1.0 1.0 1.0 1.0 0.0 0.0 0.0]
                            :triangles [0 1 2 0 2 3]}
                     weighted (assoc :weights (mapv (fn [[x y]] [{:bone "root" :weight 0.5 :x x :y y}
                                                                 {:bone "rotated" :weight 0.5 :x y :y (- x)}]) vertices)))
        doc (-> (data/new-document "mesh-transform")
                (data/add-bone {:id "rotated" :parent "root" :rotation 90.0})
                (data/add-slot {:id "mesh" :bone "root" :attachment attachment}))]
    (if-not weighted doc
            (-> doc
                (assoc :spine-source (data/export-spine-json doc))
                (update-in [:slots 0 :attachment :weights]
                           #(mapv (fn [bindings] (mapv (fn [binding] (assoc binding :weight 0.4998)) bindings)) %))))))

(defn- context [doc state]
  (#'drag/context doc (merge {:selected-kind :slot :selected "mesh" :selected-bones #{} :selected-slots #{"mesh"}}
                             state)))

(defn- points [context doc]
  (vec (partitionv 2 (#'view/attachment-points context doc (#'view/selected-slot context doc)
                                               (:transforms (#'view/evaluated-pose context doc))))))

;; Whole-mesh and subset transformations must deform geometry without moving
;; the slot bone, preserve UVs, and create one reversible gesture in both modes.
(deftest mesh-group-canvas-transform-test
  (doseq [mode ["setup" "animate"]
          [tool start target matrix] [["move" [50.0 70.0] [60.0 90.0] [1.0 0.0 0.0 1.0 10.0 20.0]]
                                      ["rotate" [150.0 70.0] [50.0 170.0] [0.0 1.0 -1.0 0.0 120.0 20.0]]
                                      ["scale" [150.0 170.0] [250.0 270.0] [2.0 0.0 0.0 2.0 -50.0 -70.0]]]]
    (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
      (ui/run-now
        (let [doc (mesh-document false) context (context doc {:mode mode :tool tool}) old (points context doc)]
          (#'drag/drag! context start target)
          (let [after (g/node-value (:resource-node context) :document)]
            (is (coll/every? true? (mapv #'drag/near? (mapv #(geometry/transform-point matrix %) old) (points context after))) (str mode tool))
            (is (= (:bones doc) (:bones after)))
            (is (= (get-in doc [:slots 0 :attachment :uvs]) (get-in after [:slots 0 :attachment :uvs])))
            (when (= mode "animate") (is (= (:slots doc) (:slots after))))
            (g/undo! :undo/global)
            (is (= doc (g/node-value (:resource-node context) :document)))
            (g/redo! :undo/global)
            (is (= after (g/node-value (:resource-node context) :document)))))))))

;; Raw Spine influence sums need not equal one. Each selected vertex must
;; still reach the requested world position with rotated influences and FFD.
(deftest weighted-mesh-affine-test
  (doseq [mode ["setup" "animate"]
          matrix [[1.0 0.0 0.0 1.0 17.0 -9.0] [0.0 1.0 -1.0 0.0 90.0 20.0] [1.5 0.2 0.1 0.8 0.0 0.0]]]
    (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
      (ui/run-now
        (let [doc (mesh-document true) context (context doc {:mode mode}) before (points context doc)
              after (#'view/transform-vertices context doc #{0 2} matrix) actual (points context after)]
          (doseq [i (range 4)]
            (is (#'drag/near? (if (#{0 2} i) (geometry/transform-point matrix (before i)) (before i)) (actual i))))
          (is (= (:bones doc) (:bones after)))
          (is (= (mapv #(mapv :weight %) (get-in doc [:slots 0 :attachment :weights]))
                 (mapv #(mapv :weight %) (get-in after [:slots 0 :attachment :weights])))))))))

;; Moving and snapping the origin changes only the manipulator; rotation
;; then uses that origin and does not move an unselected vertex.
(deftest mesh-pivot-canvas-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (mesh-document false) context (context doc {:tool "rotate"})]
        (#'drag/drag! context [50.0 70.0] [21.0 41.0])
        (is (= doc (g/node-value (:resource-node context) :document)))
        (is (#'drag/near? [20.0 40.0] (get-in @(:state context) [:mesh-pivot :point])))
        (#'drag/drag! context [120.0 40.0] [20.0 140.0])
        (is (#'drag/near? [20.0 40.0] (first (points context (g/node-value (:resource-node context) :document)))))
        (g/undo! :undo/global)
        (is (= doc (g/node-value (:resource-node context) :document)))))))

;; Numeric mesh edits and manual keys must write deform tracks, not bone
;; rotation/translation; auto-key off retains a preview until manually keyed.
(deftest mesh-numeric-manual-key-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (mesh-document true) context (context doc {:auto-key false :property "rotation" :vertices #{0 2}})
            frame (#'view/mesh-manipulator context doc)
            changed (#'view/change-mesh-value context doc frame "rotation" 90.0)]
        (#'view/commit-pose! context changed)
        (is (= doc (g/node-value (:resource-node context) :document)))
        (is (:pending-pose @(:state context)))
        (#'view/key-properties! context ["rotation"])
        (let [after (g/node-value (:resource-node context) :document)]
          (is (= (points context changed) (points context after)))
          (is (= (:bones doc) (:bones after)))
          (is (= (get-in doc [:animations 0 :tracks]) (get-in after [:animations 0 :tracks])))
          (is (not= doc after))
          (g/undo! :undo/global)
          (is (= doc (g/node-value (:resource-node context) :document))))))))

;; Ordinary transform tools select mesh vertices via Cmd+A, middle-button
;; marquee and additive selection, without accidentally selecting all bones.
(deftest mesh-selection-canvas-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (mesh-document false) context (context doc {}) scene (:scene context)
            window (doto (javafx.stage.Stage.) (.setScene scene) (.show))]
        (try
          (.requestFocus (:canvas context))
          (p/key-chord! scene [:meta :a])
          (is (= #{0 1 2 3} (:vertices @(:state context))))
          (is (= #{} (:selected-bones @(:state context))))
          (p/mouse-press! scene (#'drag/mouse-point context [10.0 30.0]) :middle)
          (p/mouse-move! scene (#'drag/mouse-point context [40.0 110.0]))
          (p/mouse-release! scene (#'drag/mouse-point context [40.0 110.0]) :middle)
          (is (= #{0 3} (:vertices @(:state context))))
          (p/key-press! scene :meta)
          (p/mouse-click! scene (#'drag/mouse-point context [80.0 40.0]) :primary)
          (p/key-release! scene :meta)
          (is (= #{0 1 3} (:vertices @(:state context))))
          (p/key-tap! scene :delete)
          (is (= doc (g/node-value (:resource-node context) :document)))
          (finally (.hide window)))))))
