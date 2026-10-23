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

(ns editor.gui-preview-test
  (:require [clojure.test :refer [deftest is]]
            [dynamo.graph :as g]
            [editor.camera :as camera]
            [editor.defold-project :as project]
            [editor.gl.pass :as pass]
            [editor.gui-layout :as layout]
            [editor.gui-preview :as preview]
            [editor.pose :as pose]
            [integration.test-util :as test-util]
            [util.coll :as coll])
  (:import [javax.vecmath Matrix4d Point3d Vector3d]))

;; Safe-area mode chooses physical long/short axes and keeps the bottom-left origin after rotation.
(deftest physical-safe-area-modes
  (is (= [0 0 600 900] (preview/safe-rect [600 900] [50 10 30 20] "none")))
  (is (= [20 30 570 820] (preview/safe-rect [600 900] [50 10 30 20] "BOTH")))
  (is (= [0 30 600 820] (preview/safe-rect [600 900] [50 10 30 20] "long")))
  (is (= [20 0 570 900] (preview/safe-rect [600 900] [50 10 30 20] "short")))
  (is (= [30 0 820 600] (preview/safe-rect [900 600] [20 50 10 30] "long")))
  (is (= [0 10 900 570] (preview/safe-rect [900 600] [20 50 10 30] "short")))
  (is (= [0 0 600 900] (preview/safe-rect [600 900] [50 10 30 20] "unknown"))))

;; Orientation wins over area, only declared layouts qualify, and tied qualifiers retain file order.
(deftest runtime-layout-selection
  (let [profiles [{:name "wide" :qualifiers [{:width 800 :height 400}]}
                  {:name "tall" :qualifiers [{:width 400 :height 800}]}
                  {:name "tall2" :qualifiers [{:width 400 :height 800}]}
                  {:name "model-only" :qualifiers [{:width 400 :height 800 :device-models ["iPhone"]}]}]]
    (is (= "tall" (preview/select-layout profiles #{"wide" "tall" "tall2" "model-only"} [400 800])))
    (is (= "wide" (preview/select-layout profiles #{"wide" "tall"} [801 400])))
    (is (= "tall" (preview/select-layout profiles #{"tall"} [801 400])))
    (is (= "" (preview/select-layout profiles #{"other"} [400 800])))
    (is (= "" (preview/select-layout profiles #{"model-only"} [400 800])))))

;; Root anchors scale positions with the reference axes, while FIT/ZOOM keep uniform geometry scaling.
(deftest runtime-root-adjustment
  (let [node (pose/translation-pose 20 20 0)
        adjust (fn [properties] (preview/adjust-pose node properties :adjust-reference-parent [100 100] [10 20 200 400] nil))]
    (is (= [[50.0 160.0 0.0] [2.0 2.0 1.0]] ((juxt (comp :translation first) second) (adjust {}))))
    (is (= [-10.0 100.0 0.0] (:translation (first (adjust {:adjust-mode :adjust-mode-zoom})))) )
    (is (= [50.0 100.0 0.0] (:translation (first (adjust {:adjust-mode :adjust-mode-stretch})))))
    (is (= [50.0 100.0 0.0] (:translation (first (adjust {:x-anchor :xanchor-right :y-anchor :yanchor-top})))))
    (is (= [node [1.0 1.0 1.0]] (preview/adjust-pose node {} :adjust-reference-disabled [100 100] [10 20 200 400] nil)))
    (is (= [node [1.0 1.0 1.0]] (preview/adjust-pose node {} :adjust-reference-legacy [100 100] [10 20 200 400] [2.0 4.0 1.0])))))

;; Parent adjustment cancels anisotropic scale before child rotation; decomposing it into one pose loses shear.
(deftest rotated-parent-reference
  (let [child {:node-id 2 :pose (pose/euler-rotation-pose 0 0 45) :gui-layout {:type :type-box}}
        root {:node-id 1 :pose pose/default :gui-layout {:type :type-box :adjust-mode :adjust-mode-stretch} :children [child]}
        adapted (preview/adapt-scene root :adjust-reference-parent [100 100] [0 0 200 400] {})
        wrapper (first (:children adapted))
        child (first (:children wrapper))
        matrix (doto (Matrix4d. (pose/matrix (:pose adapted)))
                 (.mul (pose/matrix (:pose wrapper)))
                 (.mul (pose/matrix (:pose child))))
        point (Point3d. 1 0 0)]
    (.transform matrix point)
    (is (< (Math/abs (- (.x point) (Math/sqrt 2.0))) 0.000001))
    (is (< (Math/abs (- (.y point) (Math/sqrt 2.0))) 0.000001))))

;; Template poses are baked before root adaptation, and synthetic clipping children are not adjusted twice.
(deftest templates-and-clipper-children
  (let [node {:node-id 2 :pose (pose/translation-pose 10 10 0) :gui-layout {:type :type-box}
              :children [{:node-id 2 :gui-layout {:type :type-box} :renderable {}}]}
        template {:node-id 1 :pose (pose/translation-pose 20 30 0) :gui-layout {:type :type-template} :children [node]}
        adapted (preview/adapt-scene template :adjust-reference-parent [100 100] [0 0 200 400] {})
        child (first (:children adapted))]
    (is (= pose/default (:pose adapted)))
    (is (= [60.0 180.0 0.0] (get-in child [:pose :translation])))
    (is (nil? (:pose (first (:children child)))))))

(g/defnode PreviewState
  (property animation-preview-overrides g/Any (default {}))
  (property preview-overrides g/Any (default {}))
  (property local-camera g/Any (default (camera/make-camera :orthographic))))

;; Switching the preview layout re-evaluates GUI geometry without touching saved properties or undo history.
(deftest private-layout-evaluation
  (test-util/with-loaded-project "test/resources/gui_preview_project"
    (let [resource-node (test-util/resource-node project "/parent.gui")
          view (g/make-node! PreviewState)
          before-basis (g/now)
          before-save (g/node-value resource-node :save-value)
          before-dirty (project/dirty-save-data project (g/make-evaluation-context))
          box-id (get (g/node-value resource-node :node-ids) "fit")
          config (g/node-value resource-node :game-preview-config)
          render (fn [size auto-layout]
                   (g/with-auto-evaluation-context ec
                     (preview/render-data resource-node view config size [50 10 30 20] auto-layout ec)))
          box-position (fn [data]
                         (:world-translation (coll/first-where #(= box-id (:node-id %))
                                                               (get-in data [:renderables pass/transparent]))))
          portrait (render [600 900] true)
          landscape (render [900 600] true)
          manual (render [900 600] false)]
      (is (= {:layout "portrait" :reference-size [320 480] :device-size [600 900]
              :safe-rect [20 30 570 820]}
             (:gui-preview-info portrait)))
      (is (= {:layout "landscape" :reference-size [640 360] :device-size [900 600]
              :safe-rect [20 30 870 520]}
             (:gui-preview-info landscape)))
      (is (not= (box-position portrait) (box-position landscape)))
      (is (not= (box-position landscape) (box-position manual)))
      (is (= before-save (g/node-value resource-node :save-value)))
      (is (= "" (g/node-value resource-node :visible-layout)))
      (is (= before-dirty (project/dirty-save-data project (g/make-evaluation-context))))
      (is (= before-basis (g/now))))))

;; Named layout overrides must follow a live resize without losing device/safe-area adaptation.
(deftest transient-rectangle-matches-committed-layout
  (test-util/with-loaded-project "test/resources/gui_preview_project"
    (let [gui (test-util/resource-node project "/parent.gui")
          node (get (g/node-value gui :node-ids) "fit")
          view (g/make-node! PreviewState)
          config (g/node-value gui :game-preview-config)
          render (fn [size]
                   (g/with-auto-evaluation-context ec
                     (let [data (preview/render-data gui view config size [50 10 30 20] true ec)]
                       [(:gui-preview-info data)
                        (select-keys (coll/first-where #(= node (:node-id %)) (get-in data [:renderables pass/transparent]))
                                     [:world-transform :aabb :user-data])])))]
      (doseq [[name size] [["portrait" [600 900]] ["landscape" [900 600]]]]
        (g/transact {:undoable false} (g/set-property gui :visible-layout name))
        (layout/commit! [[node :manual-size [63.0 42.0 0.0]]] "Initial layout")
        (let [before (g/node-value gui :save-value)
              values {:manual-size [84.0 56.0 0.0] :position [160.0 120.0 0.0] :pivot :pivot-ne}]
          (g/transact {:undoable false} (g/set-property view :preview-overrides {node {:graph-properties values}}))
          (let [transient-render (render size)]
            (is (= before (g/node-value gui :save-value)))
            (is (= name (get-in transient-render [0 :layout])))
            (g/transact {:undoable false} (g/set-property view :preview-overrides {}))
            (layout/commit! (mapv (fn [[key value]] [node key value]) values) "Commit layout")
            (is (= transient-render (render size)))))))))

;; Editing a portrait override must not temporarily resize the landscape preview;
;; edits to the default layout must still flow into layouts without that override.
(deftest transient-rectangle-respects-source-layout
  (test-util/with-loaded-project "test/resources/gui_preview_project"
    (let [gui (test-util/resource-node project "/parent.gui")
          node (get (g/node-value gui :node-ids) "fit")
          view (g/make-node! PreviewState)
          config (g/node-value gui :game-preview-config)
          render (fn [size]
                   (g/with-auto-evaluation-context ec
                     (select-keys (coll/first-where #(= node (:node-id %))
                                                   (get-in (preview/render-data gui view config size [0 0 0 0] true ec)
                                                           [:renderables pass/transparent]))
                                  [:world-transform :aabb :user-data])))
          landscape-before (render [900 600])
          portrait-before (render [600 900])]
      (g/transact {:undoable false}
                  (g/set-property view :preview-overrides {node {:layout "portrait" :graph-properties {:manual-size [120.0 80.0 0.0]}}}))
      (is (= landscape-before (render [900 600])))
      (is (not= portrait-before (render [600 900])))
      (g/transact {:undoable false}
                  (g/set-property view :preview-overrides {node {:layout "" :graph-properties {:manual-size [120.0 80.0 0.0]}}}))
      (let [landscape-transient (render [900 600])
            portrait-transient (render [600 900])]
        (is (not= landscape-before landscape-transient))
        (g/transact {:undoable false} (g/set-property view :preview-overrides {}))
        (layout/commit! [[node :manual-size [120.0 80.0 0.0]]] "Default layout resize")
        (is (= landscape-transient (render [900 600])))
        (is (= portrait-transient (render [600 900])))))))

;; Device preview must rebuild rectangle geometry at logical viewport dimensions,
;; and restore the authored graph after rendering rather than scaling its margins.
(deftest native-rect-device-preview
  (test-util/with-loaded-project "test/resources/gui_preview_project"
    (let [resource (test-util/resource-node project "/parent.gui")
          node ((g/node-value resource :node-ids) "fit")
          view (g/make-node! PreviewState)]
      (layout/commit! (layout/rect-preset-changes node
                                                {:anchor-min [0.0 0.0 0.0 0.0]
                                                 :anchor-max [1.0 1.0 0.0 0.0]
                                                 :pivot [0.5 0.5]}
                                                true true (g/make-evaluation-context)) "Stretch")
      (let [before (g/node-value resource :save-value)
            config (g/node-value resource :game-preview-config)
            data (preview/render-data resource view config [640 240] [0 0 0 0] false (g/make-evaluation-context))
            item (coll/first-where #(= node (:node-id %)) (get-in data [:renderables pass/transparent]))]
        (is (= (Vector3d. 320.0 120.0 0.0) (:world-translation item)))
        (is (= before (g/node-value resource :save-value)))
        (is (= [320.0 240.0 0.0] (g/node-value node :rect-size)))))))
