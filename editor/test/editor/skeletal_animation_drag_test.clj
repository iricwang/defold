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

(ns editor.skeletal-animation-drag-test
  (:require [cljfx.plorer :as p]
            [clojure.test :refer [deftest is]]
            [dynamo.graph :as g]
            [editor.skeletal-animation-attachments :as attachments]
            [editor.skeletal-animation-constraints :as constraints]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-geometry :as geometry]
            [editor.skeletal-animation-slots :as slots]
            [editor.skeletal-animation-tools :as tools]
            [editor.skeletal-animation-view :as view]
            [editor.ui :as ui]
            [internal.system :as system]
            [util.coll :as coll])
  (:import [javafx.scene Group Scene]
           [javafx.scene.canvas Canvas]
           [javafx.scene.control Label]))

(g/defnode DragFixture (property document g/Any))

(defn- rig []
  (-> (data/new-document "drag")
      (data/add-bone {:id "upper" :parent "root" :length 100.0})
      (data/add-bone {:id "lower" :parent "upper" :x 100.0 :length 100.0})))

(defn- context [doc state]
  (let [canvas (Canvas. 700.0 500.0)
        scene (Scene. (Group. (into-array javafx.scene.Node [canvas])))
        node (g/make-node! DragFixture :document doc)
        context {:canvas canvas :scene scene :resource-node node :workspace-cache (atom nil)
                 :status (Label.) :localization (constantly "Invalid operation")
                 :state (atom (merge {:mode "animate" :animation "default" :time 0.5 :auto-key true
                                      :zoom 1.0 :pan-x 150.0 :pan-y 300.0 :show-images false
                                      :tool "move" :coordinate-space "world" :interpolation "linear"
                                      :selected-kind :bone :selected "lower" :selected-bones #{"lower"}}
                                     state))}]
    (#'view/canvas-events! context)
    context))

(defn- mouse-point [context position]
  (let [[x y] (#'view/screen context position)
        canvas (:canvas context)]
    (p/point canvas (/ x (.getWidth canvas)) (/ y (.getHeight canvas)))))

(defn- drag! [context start target]
  (let [window (doto (javafx.stage.Stage.) (.setScene (:scene context)) (.show))]
    (try
      (.applyCss (.getRoot (:scene context)))
      (.layout (.getRoot (:scene context)))
      (p/mouse-press! (:scene context) (mouse-point context start) :primary)
      (p/mouse-move! (:scene context) (mouse-point context target))
      (p/mouse-release! (:scene context) (mouse-point context target) :primary)
      (finally (.hide window)))))

(defn- near? [a b]
  (and (= (count a) (count b)) (coll/every? #(< (abs %) 1.0e-4) (mapv - a b))))

(defn- pose [doc]
  (data/evaluate-pose-with-slots doc "default" 0.5 slots/sample-slots))

;; At an overlapping chain tip, the target origin must be pickable and a drag
;; must solve both parent rotations, key only the target and undo atomically.
(deftest ik-target-canvas-drag-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (-> (rig)
                    (data/add-bone {:id "target" :parent "root" :x 200.0})
                    (data/add-ik {:id "ik" :bones ["upper" "lower"] :target "target" :mix 1.0 :bend-positive true}))
            context (context doc {:selected nil :selected-bones #{}})]
        (drag! context [200.0 0.0] [120.0 60.0])
        (let [after (g/node-value (:resource-node context) :document)
              transforms (:transforms (pose after))]
          (is (= "target" (:selected @(:state context))))
          (is (near? [120.0 60.0] (geometry/transform-point (transforms "lower") [100.0 0.0])))
          (is (not (near? (get-in (pose doc) [:transforms "upper"]) (transforms "upper"))))
          (is (= (:bones doc) (:bones after)))
          (g/undo! :undo/global)
          (is (= doc (g/node-value (:resource-node context) :document)))
          (g/redo! :undo/global)
          (is (= after (g/node-value (:resource-node context) :document))))))))

;; Pose dragging the shaft solves the selected chain; dragging its origin
;; translates only that bone rather than accidentally invoking chain IK.
(deftest pose-chain-origin-canvas-drag-test
  (doseq [[start target origin] [[[150.0 0.0] [120.0 60.0] false]
                                 [[100.0 0.0] [110.0 20.0] true]]]
    (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
      (ui/run-now
        (let [doc (rig)
              context (context doc {:tool "pose" :selected-bones #{"upper" "lower"}})]
          (drag! context start target)
          (let [after (g/node-value (:resource-node context) :document)
                transforms (:transforms (pose after))]
            (is (= #{"upper" "lower"} (:selected-bones @(:state context))))
            (is (near? target (geometry/transform-point (transforms "lower") [(if origin 0.0 100.0) 0.0])))
            (if origin
              (is (near? (get-in (pose doc) [:transforms "upper"]) (transforms "upper")))
              (is (not (near? (get-in (pose doc) [:transforms "upper"]) (transforms "upper")))))
            (is (= (:bones doc) (:bones after)))
            (g/undo! :undo/global)
            (is (= doc (g/node-value (:resource-node context) :document)))))))))

;; Ordinary transform tools must drag visible vertices, not their slot bone.
;; Setup keeps UVs; animation creates FFD and preserves bind data.
(deftest mesh-transform-tool-canvas-drag-test
  (doseq [tool ["move" "rotate" "scale" "shear"] mode ["setup" "animate"] weighted [false true]]
    (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
      (ui/run-now
        (let [vertices [[20.0 40.0] [80.0 40.0] [80.0 100.0] [20.0 100.0]]
              attachment (cond-> {:type "mesh" :path "/image.png" :width 60.0 :height 60.0
                                  :vertices (coll/into-> vertices [] cat) :uvs [0.0 1.0 1.0 1.0 1.0 0.0 0.0 0.0]
                                  :triangles [0 1 2 0 2 3]}
                           weighted (assoc :weights (mapv (fn [[x y]] [{:bone "upper" :weight 1.0 :x x :y y}]) vertices)))
              doc (data/add-slot (rig) {:id "mesh" :bone "upper" :attachment attachment})
              context (context doc {:mode mode :tool tool :selected-kind :slot :selected "mesh" :selected-bones #{} :selected-slots #{"mesh"}})]
          (drag! context (first vertices) [30.0 60.0])
          (let [after (g/node-value (:resource-node context) :document)
                points (vec (partitionv 2 (#'view/attachment-points context after (#'view/selected-slot context after)
                                                                    (:transforms (#'view/evaluated-pose context after)))))]
            (is (near? [30.0 60.0] (first points)) (pr-str [tool mode weighted]))
            (is (coll/every? true? (mapv near? (subvec vertices 1) (subvec points 1))))
            (is (= (:bones doc) (:bones after)))
            (is (= (:uvs attachment) (get-in after [:slots 0 :attachment :uvs])))
            (when (= "animate" mode) (is (= (:slots doc) (:slots after))))
            (g/undo! :undo/global)
            (is (= doc (g/node-value (:resource-node context) :document)))))))))

;; A keyed zero mix deliberately disables IK; moving its target must not
;; silently re-enable the constraint or change the authored chain rotations.
(deftest disabled-ik-target-canvas-drag-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (-> (rig)
                    (data/add-bone {:id "target" :parent "root" :x 200.0})
                    (data/add-ik {:id "ik" :bones ["upper" "lower"] :target "target" :mix 1.0})
                    (data/set-ik-keyframe-values "default" "ik" 0.0 {:mix 0.0} "linear"))
            context (context doc {:selected "target" :selected-bones #{"target"}})]
        (drag! context [200.0 0.0] [120.0 60.0])
        (let [after (g/node-value (:resource-node context) :document)
              transforms (:transforms (pose after))]
          (is (near? [120.0 60.0] (subvec (transforms "target") 4 6)))
          (is (near? (get-in (pose doc) [:transforms "upper"]) (transforms "upper")))
          (is (near? (get-in (pose doc) [:transforms "lower"]) (transforms "lower")))
          (is (= 0.0 (:mix (first (data/sample-ik after "default" 0.5)))))
          (g/undo! :undo/global)
          (is (= doc (g/node-value (:resource-node context) :document))))))))

;; Bursts must wait for a pulse, keep the last target, flush a newer release
;; position, and create exactly one undoable document operation.
(deftest coalesced-drag-release-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (rig) context (context doc {})
            window (doto (javafx.stage.Stage.) (.setScene (:scene context)) (.show))]
        (try
          (p/mouse-press! (:scene context) (mouse-point context [100.0 0.0]) :primary)
          (doseq [x (range 101 151)] (p/mouse-move! (:scene context) (mouse-point context [x 20.0])))
          (is (nil? (:draft @(:state context))))
          (is (= doc (g/node-value (:resource-node context) :document)))
          (#'view/flush-drag-input! context)
          (let [draft (:draft @(:state context))]
            (is (near? [150.0 20.0] (subvec (get-in (pose draft) [:transforms "lower"]) 4 6)))
            (#'view/flush-drag-input! context)
            (is (identical? draft (:draft @(:state context)))))
          (p/mouse-move! (:scene context) (mouse-point context [160.0 30.0]))
          (p/mouse-release! (:scene context) (mouse-point context [170.0 40.0]) :primary)
          (let [after (g/node-value (:resource-node context) :document)]
            (is (near? [170.0 40.0] (subvec (get-in (pose after) [:transforms "lower"]) 4 6)))
            (g/undo! :undo/global)
            (is (= doc (g/node-value (:resource-node context) :document)))
            (g/redo! :undo/global)
            (is (= after (g/node-value (:resource-node context) :document))))
          (finally (.hide window)))))))

;; Cancellation must discard a queued sample; it cannot resurrect a draft
;; during the next editor pulse or affect the next gesture.
(deftest cancelled-coalesced-drag-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (rig) context (context doc {})
            window (doto (javafx.stage.Stage.) (.setScene (:scene context)) (.show))]
        (try
          (p/mouse-press! (:scene context) (mouse-point context [100.0 0.0]) :primary)
          (p/mouse-move! (:scene context) (mouse-point context [150.0 20.0]))
          (swap! (:state context) dissoc :drag :draft)
          (#'view/flush-drag-input! context)
          (is (nil? (:draft @(:state context))))
          (is (= doc (g/node-value (:resource-node context) :document)))
          (p/mouse-release! (:scene context) (mouse-point context [150.0 20.0]) :primary)
          (finally (.hide window)))))))

;; Coalescing must never discard brush path samples or bone-creation samples.
(deftest path-sensitive-drag-bypass-test
  (ui/run-now
    (let [canvas (Canvas. 400.0 300.0)
          scene (Scene. (Group. (into-array javafx.scene.Node [canvas])))
          window (doto (javafx.stage.Stage.) (.setScene scene) (.show))
          samples (atom 0)
          context {:canvas canvas :state (atom {})}]
      (try
        (.setOnMousePressed canvas (ui/event-handler _ nil))
        (.setOnMouseDragged canvas (ui/event-handler _ (swap! samples inc)))
        (.setOnMouseReleased canvas (ui/event-handler _ nil))
        (#'view/coalesce-drag-events! context)
        (doseq [gesture [{:tool "weights" :brush true} {:tool "create" :create-id "new"}]]
          (swap! (:state context) assoc :drag gesture)
          (reset! samples 0)
          (p/mouse-press! scene (p/point canvas 0.2 0.2) :primary)
          (dotimes [i 10] (p/mouse-move! scene (p/point canvas (+ 0.3 (* i 0.01)) 0.3)))
          (is (= 10 @samples))
          (#'view/flush-drag-input! context)
          (is (= 10 @samples))
          (p/mouse-release! scene (p/point canvas 0.4 0.3) :primary))
        (finally (.hide window))))))

;; Rotation rings must keep the current bone selected in empty space and over
;; another bone, including zero-length bones at different zoom levels.
(deftest bone-rotation-ring-canvas-drag-test
  (doseq [mode ["setup" "animate"] zoom [0.5 1.0 2.0] length [0.0 5.0]]
    (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
      (ui/run-now
        (let [doc (-> (rig)
                      (data/update-bone "lower" {:length length})
                      (data/add-bone {:id "overlap" :parent "root" :x (+ 100.0 (/ 48.0 zoom)) :length 0.0}))
              context (context doc {:mode mode :tool "rotate" :zoom zoom})
              radius (/ 48.0 zoom)]
          (drag! context [(+ 100.0 radius) 0.0] [100.0 radius])
          (let [after (g/node-value (:resource-node context) :document)
                bone (coll/first-where #(= "lower" (:id %)) (if (= mode "animate") (:bones (pose after)) (:bones after)))]
            (is (= "lower" (:selected @(:state context))))
            (is (< (abs (- 90.0 (:rotation bone))) 1.0e-4))
            (when (= mode "animate") (is (= (:bones doc) (:bones after))))
            (g/undo! :undo/global)
            (is (= doc (g/node-value (:resource-node context) :document)))
            (g/redo! :undo/global)
            (is (= after (g/node-value (:resource-node context) :document)))))))))

;; The ring's hit band is in screen pixels and does not swallow the origin,
;; distant selection clicks, playback, or mesh-vertex editing.
(deftest bone-rotation-ring-hit-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [context (context (rig) {:tool "rotate"})
            doc (rig)
            handle (#'view/bone-rotation-handle context doc)
            [x y] (:center handle)]
        (is (#'view/rotation-handle-hit? handle (+ x 56.0) y))
        (is (not (#'view/rotation-handle-hit? handle (+ x 64.0) y)))
        (is (not (#'view/rotation-handle-hit? handle x y)))
        (swap! (:state context) assoc :playing true)
        (is (nil? (#'view/bone-rotation-handle context doc)))
        (swap! (:state context) assoc :playing false :selected-kind :slot)
        (is (nil? (#'view/bone-rotation-handle context doc)))))))

;; Shift on the ring snaps rotation without changing a multi-selection;
;; selected descendants inherit the rotation once, not twice.
(deftest bone-rotation-ring-snap-group-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (rig)
            context (context doc {:mode "setup" :tool "rotate" :selected "upper" :selected-bones #{"upper" "lower"}})
            scene (:scene context)
            window (doto (javafx.stage.Stage.) (.setScene scene) (.show))
            angle (Math/toRadians 38.0)]
        (try
          (.requestFocus (:canvas context))
          (p/key-press! scene :shift)
          (p/mouse-press! scene (mouse-point context [48.0 0.0]) :primary)
          (p/mouse-move! scene (mouse-point context [(* 48.0 (Math/cos angle)) (* 48.0 (Math/sin angle))]))
          (p/mouse-release! scene (mouse-point context [(* 48.0 (Math/cos angle)) (* 48.0 (Math/sin angle))]) :primary)
          (let [after (g/node-value (:resource-node context) :document)]
            (is (= #{"upper" "lower"} (:selected-bones @(:state context))))
            (is (< (abs (- 45.0 (:rotation (coll/first-where #(= "upper" (:id %)) (:bones after))))) 1.0e-4))
            (is (= 0.0 (:rotation (coll/first-where #(= "lower" (:id %)) (:bones after)))))
            (g/undo! :undo/global)
            (is (= doc (g/node-value (:resource-node context) :document))))
          (finally (p/key-release! scene :shift) (.hide window)))))))

;; A selected bone transforms from empty space, preserving selection and one
;; undo operation in setup and animate; a handle is not required.
(deftest empty-space-bone-transform-test
  (doseq [mode ["setup" "animate"]
          [tool start target property expected] [["move" [180.0 80.0] [190.0 100.0] :x 110.0]
                                                 ["rotate" [180.0 80.0] [20.0 80.0] :rotation 90.0]
                                                 ["scale" [180.0 80.0] [260.0 160.0] :scale-x 2.0]
                                                 ["shear" [180.0 80.0] [100.0 120.0] :shear-y 45.0]]]
    (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
      (ui/run-now
        (let [doc (rig) context (context doc {:mode mode :tool tool})]
          (drag! context start target)
          (let [after (g/node-value (:resource-node context) :document)
                bone (coll/first-where #(= "lower" (:id %)) (if (= "animate" mode) (:bones (pose after)) (:bones after)))]
            (is (= "lower" (:selected @(:state context))))
            (is (< (abs (- expected (get bone property))) 1.0e-4) (str mode " " tool))
            (g/undo! :undo/global)
            (is (= doc (g/node-value (:resource-node context) :document)))))))))

;; Cmd-drag remains additive box selection; starting on a different bone
;; still selects and transforms that bone rather than the prior selection.
(deftest empty-space-selection-modifiers-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (rig) context (context doc {:mode "setup"})
            scene (:scene context)
            window (doto (javafx.stage.Stage.) (.setScene scene) (.show))]
        (try
          (.requestFocus (:canvas context))
          (p/key-press! scene :meta)
          (p/mouse-press! scene (mouse-point context [-10.0 -10.0]) :primary)
          (p/mouse-move! scene (mouse-point context [10.0 10.0]))
          (p/mouse-release! scene (mouse-point context [10.0 10.0]) :primary)
          (p/key-release! scene :meta)
          (is (= #{"root" "upper" "lower"} (:selected-bones @(:state context))))
          (is (= doc (g/node-value (:resource-node context) :document)))
          (finally (p/key-release! scene :meta) (.hide window)))))))

;; Right click swaps tools and cycles mesh modes, but any pan beyond the
;; threshold (even an out-and-back burst before a pulse) must never swap.
(deftest secondary-click-versus-pan-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (rig) context (context doc {:mode "setup" :previous-tool "rotate"})
            scene (:scene context)
            window (doto (javafx.stage.Stage.) (.setScene scene) (.show))
            origin (mouse-point context [100.0 100.0])
            target (mouse-point context [140.0 120.0])]
        (try
          (p/mouse-press! scene origin :secondary)
          (p/mouse-release! scene origin :secondary)
          (is (= "rotate" (:tool @(:state context))))
          (p/mouse-press! scene origin :secondary)
          (p/mouse-move! scene target)
          (p/mouse-move! scene origin)
          (p/mouse-release! scene origin :secondary)
          (is (= "rotate" (:tool @(:state context))))
          (is (= [150.0 300.0] ((juxt :pan-x :pan-y) @(:state context))))
          (p/mouse-press! scene origin :secondary)
          (p/mouse-move! scene target)
          (p/mouse-release! scene target :secondary)
          (is (= "rotate" (:tool @(:state context))))
          (is (= [190.0 280.0] ((juxt :pan-x :pan-y) @(:state context))))
          (p/mouse-press! scene origin :secondary)
          (p/mouse-release! scene origin :secondary)
          (is (= "move" (:tool @(:state context))))
          (swap! (:state context) assoc :tool "mesh" :mesh-mode "modify")
          (doseq [mode ["create" "delete" "modify"]]
            (p/mouse-press! scene origin :secondary)
            (p/mouse-release! scene origin :secondary)
            (is (= mode (:mesh-mode @(:state context)))))
          (is (= doc (g/node-value (:resource-node context) :document)))
          (finally (.hide window)))))))

;; Every raw pointer sample contributes to winding, even when no pulse occurs
;; between samples. Local animation stores turns; setup/world store direction.
(deftest continuous-rotation-winding-test
  (doseq [[mode space turns expected] [["animate" "local" 2.25 810.0]
                                       ["animate" "parent" -1.25 -450.0]
                                       ["animate" "world" 2.25 90.0]
                                       ["setup" "local" 2.25 90.0]]]
    (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
      (ui/run-now
        (let [doc (rig) context (context doc {:mode mode :tool "rotate" :coordinate-space space})
              scene (:scene context) window (doto (javafx.stage.Stage.) (.setScene scene) (.show))
              at (fn at [angle] (mouse-point context [(+ 100.0 (* 48.0 (Math/cos angle))) (* 48.0 (Math/sin angle))]))]
          (try
            (p/mouse-press! scene (at 0.0) :primary)
            (doseq [index (range 1 109)] (p/mouse-move! scene (at (* turns 2.0 Math/PI (/ index 108.0)))))
            (p/mouse-release! scene (at (* turns 2.0 Math/PI)) :primary)
            (let [after (g/node-value (:resource-node context) :document)
                  bone (coll/first-where #(= "lower" (:id %)) (if (= mode "animate") (:bones (pose after)) (:bones after)))]
              (is (< (abs (- expected (:rotation bone))) 1.0e-4) (str mode space turns))
              (g/undo! :undo/global)
              (is (= doc (g/node-value (:resource-node context) :document)))
              (g/redo! :undo/global)
              (is (= after (g/node-value (:resource-node context) :document))))
            (finally (.hide window))))))))

;; Numeric scrubbing previews without committing, commits once on release,
;; honors Shift precision and cancels without leaving a key or an undo step.
(deftest numeric-drag-preview-test
  (doseq [mode ["setup" "animate"] fine [false true] cancel [false true]]
    (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
      (ui/run-now
        (let [doc (rig) context (context doc {:mode mode :coordinate-space "local"})
              field (#'view/number-field "test-number" 0.0 (fn unexpected-commit [_] (throw (ex-info "Expected preview lifecycle" {}))))
              scene (:scene context)
              root (.getRoot scene)
              _ (.add (.getChildren root) field)
              _ (.resizeRelocate field 400.0 150.0 120.0 30.0)
              window (doto (javafx.stage.Stage.) (.setScene scene) (.show))]
          (#'view/install-palette-number-preview! context field "rotation")
          (try
            (.requestFocus field)
            (when fine (p/key-press! scene :shift))
            (let [point (p/point field 0.5 0.5)
                  target (let [location (.localToScene field (/ (.getWidth field) 2.0) (- (/ (.getHeight field) 2.0)))
                               canvas (:canvas context)]
                           (p/point canvas (/ (.getX location) (.getWidth canvas)) (/ (.getY location) (.getHeight canvas))))
                  expected (* (.getHeight field) (if fine 0.1 1.0))]
              (p/mouse-press! scene point :primary)
              (p/mouse-move! scene target)
              (is (= doc (g/node-value (:resource-node context) :document)))
              (let [draft (:draft @(:state context))
                    bone (coll/first-where #(= "lower" (:id %)) (if (= mode "animate") (:bones (pose draft)) (:bones draft)))]
                (is (< (abs (- expected (:rotation bone))) 1.0e-4)))
              (when cancel (p/key-press! scene :escape) (p/key-release! scene :escape))
              (p/mouse-release! scene target :primary)
              (is (nil? (:draft @(:state context))))
              (is (nil? (:drag @(:state context))))
              (if cancel
                (is (= doc (g/node-value (:resource-node context) :document)))
                (do (is (not= doc (g/node-value (:resource-node context) :document)))
                    (g/undo! :undo/global)
                    (is (= doc (g/node-value (:resource-node context) :document))))))
            (finally (when fine (p/key-release! scene :shift)) (.hide window))))))))

;; Tree rows retain slot identity while attachments have separate identities;
;; selecting an alternate is read-only, and deleting a child preserves its slot.
(deftest slot-attachment-hierarchy-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (-> (rig)
                    (data/add-slot {:id "image-slot" :bone "lower" :attachment {:type "region" :name "front" :path "/front.png" :width 20.0 :height 20.0}})
                    (attachments/put "default" "image-slot" "back" {"type" "region" "path" "back" "width" 20 "height" 20}))
            context (assoc (context doc {:mode "setup"}) :tree (javafx.scene.control.TreeView.))]
        (#'view/refresh-tree! context doc)
        (let [items (:tree-items @(:state context))
              slot (get items [:slot "image-slot"])
              child (get items [:attachment ["image-slot" "back" "default"]])]
          (is (= 2 (count (.getChildren slot))))
          (is (= slot (.getParent child)))
          (#'view/select-tree-attachment! context (.getValue child))
          (#'view/repair-selection! context doc)
          (is (= :attachment (:selected-kind @(:state context))))
          (is (= doc (g/node-value (:resource-node context) :document)))
          (#'view/delete-selection! context)
          (let [after (g/node-value (:resource-node context) :document)]
            (is (= ["image-slot"] (mapv :id (:slots after))))
            (is (= ["front"] (mapv :name (attachments/entries after "default" "image-slot"))))
            (g/undo! :undo/global)
            (is (= doc (g/node-value (:resource-node context) :document)))))))))

;; Narrow layouts reserve a separate tool strip; wide layouts restore the
;; floating tools without duplicating controls or covering the narrow viewport.
(deftest responsive-tools-layout-test
  (ui/run-now
    (let [viewport (javafx.scene.layout.StackPane.)
          palette (javafx.scene.layout.HBox.)
          _ (.setPrefSize palette 700.0 120.0)
          overlay (javafx.scene.layout.VBox. (into-array javafx.scene.Node [palette]))
          _ (.add (.getChildren viewport) overlay)
          host (#'view/responsive-tool-host viewport overlay palette)]
      (.resize host 400.0 500.0) (.applyCss host) (.layout host)
      (is (some? (.getBottom host)))
      (is (not (.contains (.getChildren viewport) overlay)))
      (is (< (.getHeight viewport) (.getHeight host)))
      (.resize host 1200.0 600.0) (.layout host)
      (is (nil? (.getBottom host)))
      (is (.contains (.getChildren viewport) overlay))
      (.resize host 1200.0 300.0) (.layout host)
      (is (some? (.getBottom host))))))

;; Group rotation retains authored turns for independent roots, while a selected
;; descendant inherits its parent's turn rather than receiving it a second time.
(deftest group-rotation-winding-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (data/add-bone (rig) {:id "peer" :parent "root" :y 50.0 :length 50.0})
            context (context doc {:mode "animate" :coordinate-space "local" :tool "rotate" :selected "upper" :selected-bones #{"upper" "lower" "peer"}})
            after (#'view/change-displayed-value context doc (first (filterv #(= "upper" (:id %)) (:bones doc))) "rotation" 810.0)
            rotations (coll/into-> (:bones (pose after)) {} (map (juxt :id :rotation)))]
        (is (< (abs (- 810.0 (rotations "upper"))) 1.0e-4))
        (is (< (abs (- 810.0 (rotations "peer"))) 1.0e-4))
        (is (< (abs (rotations "lower")) 1.0e-4))))))

;; Active attachment rename/delete must affect the child only, preserving the
;; slot, its bone parent, and the ability to undo both operations.
(deftest active-attachment-operations-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (data/add-slot (rig) {:id "image-slot" :bone "lower" :attachment {:type "region" :name "front" :path "/front.png" :width 20.0 :height 20.0}})
            context (context doc {:mode "setup"})]
        (#'view/select-tree-attachment! context {:slot "image-slot" :id "front" :skin "default"})
        (#'view/rename-entity! context :slot "image-slot" "renamed" data/update-slot)
        (let [renamed (g/node-value (:resource-node context) :document)]
          (is (= "image-slot" (get-in renamed [:slots 0 :id])))
          (is (= "renamed" (get-in renamed [:slots 0 :attachment :name])))
          (#'view/delete-selection! context)
          (let [removed (g/node-value (:resource-node context) :document)]
            (is (= "image-slot" (get-in removed [:slots 0 :id])))
            (is (= "lower" (get-in removed [:slots 0 :bone])))
            (is (nil? (get-in removed [:slots 0 :attachment]))))
          (g/undo! :undo/global)
          (is (= renamed (g/node-value (:resource-node context) :document)))
          (g/undo! :undo/global)
          (is (= doc (g/node-value (:resource-node context) :document))))))))

;; With automatic keys disabled, a numeric preview stages a pose only; it
;; remains undoable and canceling the next drag preserves that existing pose.
(deftest numeric-manual-key-pose-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (rig) context (context doc {:auto-key false :coordinate-space "local"})
            field (#'view/number-field "manual-key" 0.0 identity)]
        (#'view/install-palette-number-preview! context field "rotation")
        (let [{:keys [begin preview finish cancel]} (ui/user-data field :editor.skeletal-animation-view/number-preview)]
          (begin) (preview 45.0) (finish)
          (is (= doc (g/node-value (:resource-node context) :document)))
          (let [pending (:pending-pose @(:state context))]
            (is (some? pending))
            (is (< (abs (- 45.0 (:rotation (coll/first-where #(= "lower" (:id %)) (:bones (pose (#'view/document context))))))) 1.0e-4))
            (begin) (preview 90.0) (cancel)
            (is (= pending (:pending-pose @(:state context))))
            (swap! (:state context) tools/undo-pose false)
            (is (= doc (#'view/document context)))))))))

;; A hidden attachment named like its slot is still inactive. Repairing
;; selection must not promote it to a visible attachment or mutate data.
(deftest hidden-attachment-selection-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (-> (rig)
                    (data/add-slot {:id "image" :bone "upper" :attachment {:type "region" :name "image" :path "/image.png" :width 20.0 :height 20.0}})
                    (attachments/select-setup "image" nil))
            context (context doc {:mode "setup"})]
        (#'view/select-tree-attachment! context {:slot "image" :id "image" :skin "default"})
        (is (= :attachment (:selected-kind @(:state context))))
        (#'view/repair-selection! context doc)
        (is (= :attachment (:selected-kind @(:state context))))
        (is (= doc (g/node-value (:resource-node context) :document)))))))

;; Restoring a bone selection group must clear the attachment identity,
;; so Delete never deletes the previously selected image.
(deftest attachment-selection-group-restore-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (data/add-slot (rig) {:id "image" :bone "upper" :attachment {:type "region" :name "image" :path "/image.png" :width 20.0 :height 20.0}})
            context (context doc {:mode "setup"})
            scene (:scene context)
            window (doto (javafx.stage.Stage.) (.setScene scene) (.show))]
        (try
          (.requestFocus (:canvas context))
          (p/key-chord! scene [:meta :digit1])
          (#'view/select-tree-attachment! context {:slot "image" :id "image" :skin "default"})
          (p/key-chord! scene [:digit1])
          (is (= :bone (:selected-kind @(:state context))))
          (is (= "lower" (:selected @(:state context))))
          (is (nil? (:selected-attachment @(:state context))))
          (#'view/delete-selection! context)
          (let [after (g/node-value (:resource-node context) :document)]
            (is (= (get-in doc [:slots 0 :attachment]) (get-in after [:slots 0 :attachment])))
            (is (nil? (coll/first-where #(= "lower" (:id %)) (:bones after)))))
          (finally (.hide window)))))))

;; Multiple attachment rows contribute all active slots to transforms, but
;; deleting their rows removes only attachments in a single undo transaction.
(deftest multiple-attachment-selection-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (reduce (fn add-image [doc id]
                          (data/add-slot doc {:id id :bone "upper" :attachment {:type "region" :name id :path (str "/" id ".png") :width 20.0 :height 20.0}}))
                        (rig) ["a" "b"])
            context (context doc {:mode "setup"})
            entries (mapv (fn entry [id] {:kind :attachment :slot id :id id :skin "default"}) ["a" "b"])]
        (#'view/select-tree-attachment! context (peek entries))
        (#'view/sync-tree-selection! context entries)
        (is (= #{"a" "b"} (:selected-slots @(:state context))))
        (is (= 2 (count (:tree-selection @(:state context)))))
        (#'view/delete-selection! context)
        (let [after (g/node-value (:resource-node context) :document)]
          (is (= ["a" "b"] (mapv :id (:slots after))))
          (is (coll/every? #(nil? (:attachment %)) (:slots after)))
          (g/undo! :undo/global)
          (is (= doc (g/node-value (:resource-node context) :document))))))))

;; A slot row explicitly selected together with its attachment still deletes
;; the slot; a child selected on its own must never imply deleting its parent.
(deftest mixed-slot-attachment-delete-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (data/add-slot (rig) {:id "a" :bone "upper" :attachment {:type "region" :name "a" :path "/a.png" :width 20.0 :height 20.0}})
            context (context doc {:mode "setup"})
            entry {:kind :attachment :slot "a" :id "a" :skin "default"}]
        (#'view/select-tree-attachment! context entry)
        (#'view/sync-tree-selection! context [{:kind :slot :id "a"} entry])
        (#'view/delete-selection! context)
        (is (zero? (count (:slots (g/node-value (:resource-node context) :document)))))
        (g/undo! :undo/global)
        (is (= doc (g/node-value (:resource-node context) :document)))))))

;; Disabling rotation inheritance means the selected child needs its own
;; full turns; only children that inherit rotation should skip those keys.
(deftest group-rotation-inherit-winding-test
  (doseq [inherit ["normal" "onlyTranslation" "noRotationOrReflection" "noScale" "noScaleOrReflection"]
          rotation [720.0 -450.0]]
    (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
      (ui/run-now
        (let [doc (data/update-bone (rig) "lower" {:inherit inherit})
              context (context doc {:mode "animate" :coordinate-space "local" :tool "rotate" :selected "upper" :selected-bones #{"upper" "lower"}})
              after (#'view/change-displayed-value context doc (coll/first-where #(= "upper" (:id %)) (:bones doc)) "rotation" rotation)
              bone (coll/first-where #(= "lower" (:id %)) (:bones (pose after)))
              expected (if (contains? #{"onlyTranslation" "noRotationOrReflection"} inherit) rotation 0.0)]
          (is (< (abs (- expected (:rotation bone))) 1.0e-4) (str inherit " " rotation)))))))

;; Mirrored parent spaces reverse local winding. Group rotation must retain
;; the correct direction and turn count, not just an equivalent final matrix.
(deftest mirrored-group-rotation-winding-test
  (doseq [[driver-scale peer-scale] [[1.0 -1.0] [-1.0 1.0] [-1.0 -1.0]]]
    (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
      (ui/run-now
        (let [doc (-> (rig)
                      (data/add-bone {:id "driver-parent" :parent "root" :scale-x driver-scale})
                      (data/add-bone {:id "peer-parent" :parent "root" :scale-x peer-scale})
                      (data/update-bone "upper" {:parent "driver-parent"})
                      (data/add-bone {:id "peer" :parent "peer-parent" :y 50.0 :length 50.0}))
              context (context doc {:coordinate-space "local" :selected "upper" :selected-bones #{"upper" "peer"}})
              after (#'view/change-displayed-value context doc (coll/first-where #(= "upper" (:id %)) (:bones doc)) "rotation" 810.0)
              peer (coll/first-where #(= "peer" (:id %)) (:bones (pose after)))]
          (is (< (abs (- (* 810.0 driver-scale peer-scale) (:rotation peer))) 1.0e-4)))))))

;; Deleting a linked mesh and its source together validates the final document,
;; while a rejected source-only delete keeps the current selection and history.
(deftest linked-attachment-batch-delete-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (-> (rig)
                    (data/add-slot {:id "image" :bone "upper" :attachment {:type "region" :name "base" :path "/image.png" :width 20.0 :height 20.0}})
                    (attachments/put "default" "image" "base" {"type" "mesh" "path" "image" "vertices" [-5 -5 5 -5 5 5 -5 5] "uvs" [0 1 1 1 1 0 0 0] "triangles" [0 1 2 0 2 3]})
                    (attachments/put "default" "image" "copy" {"type" "linkedmesh" "parent" "base" "skin" "default"}))
            context (context doc {:mode "setup"})
            entries (mapv (fn entry [id] {:kind :attachment :slot "image" :id id :skin "default"}) ["base" "copy"])]
        (#'view/select-tree-attachment! context (first entries))
        (#'view/delete-selection! context)
        (is (= doc (g/node-value (:resource-node context) :document)))
        (is (= ["image" "base" "default"] (:selected-attachment @(:state context))))
        (#'view/sync-tree-selection! context entries)
        (#'view/delete-selection! context)
        (is (nil? (get-in (g/node-value (:resource-node context) :document) [:slots 0 :attachment])))
        (g/undo! :undo/global)
        (is (= doc (g/node-value (:resource-node context) :document)))))))

;; Reclassification of a selected attachment during playback keeps the rest
;; of the tree selection, excluding hidden images from transform targets.
(deftest attachment-reclassification-multiselect-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (data/add-slot (rig) {:id "image" :bone "upper" :attachment {:type "region" :name "image" :path "/image.png" :width 20.0 :height 20.0}})
            context (context doc {:mode "setup"})
            entry {:kind :attachment :slot "image" :id "image" :skin "default"}]
        (#'view/select-tree-attachment! context entry)
        (#'view/sync-tree-selection! context [{:kind :bone :id "lower"} entry])
        (let [hidden (attachments/select-setup doc "image" nil)]
          (g/transact (g/set-property (:resource-node context) :document hidden))
          (#'view/repair-selection! context hidden)
          (is (= :attachment (:selected-kind @(:state context))))
          (is (= #{"lower"} (:selected-bones @(:state context))))
          (is (= #{} (:selected-slots @(:state context))))
          (is (= 2 (count (:tree-selection @(:state context))))))))))

;; Authoring compensation must invert the same no-rotation basis used by
;; forward evaluation, including nonuniform scale, reflection and shear.
(deftest no-rotation-authoring-compensation-test
  (doseq [rotation [0.0 37.0 -90.0] scale [-2.0 2.0]]
    (let [parent {:id "parent" :rotation rotation :scale-x scale :scale-y 3.0 :shear-y 11.0 :x 12.0 :y 8.0}
          bone {:id "bone" :parent "parent" :inherit "noRotationOrReflection" :x 9.0 :y -3.0 :rotation 53.0 :scale-x 1.2 :scale-y 0.8 :shear-x 7.0 :shear-y 15.0}
          worlds (constraints/world-transforms [parent bone])
          adjusted (constraints/compensate-bone bone (worlds "bone") (worlds "parent"))
          actual (get (constraints/world-transforms [parent adjusted]) "bone")]
      (is (near? (worlds "bone") actual)))))

;; World-scale fields switch to local values on press. Scrubbing starts from
;; that displayed local value, not the world value captured before focus.
(deftest numeric-world-scale-press-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (data/update-bone (rig) "upper" {:scale-x 3.0})
            context (context doc {:mode "setup" :coordinate-space "world"})
            field (#'view/number-field "scale" 3.0 identity)
            scene (:scene context)
            _ (.add (.getChildren (.getRoot scene)) field)
            _ (.resizeRelocate field 400.0 150.0 120.0 30.0)
            window (doto (javafx.stage.Stage.) (.setScene scene) (.show))]
        (#'view/install-palette-number-preview! context field "scale-x")
        (.setOnMousePressed field (ui/event-handler _ (#'view/refresh-number-field! field 1.0)))
        (try
          (let [start (p/point field 0.5 0.9)
                end (p/point field 0.5 0.1)
                expected (+ 1.0 (* 0.8 (.getHeight field)))]
            (p/mouse-press! scene start :primary)
            (p/mouse-move! scene end)
            (p/mouse-release! scene end :primary)
            (let [after (g/node-value (:resource-node context) :document)
                  bone (coll/first-where #(= "lower" (:id %)) (:bones after))]
              (is (< (abs (- expected (:scale-x bone))) 1.0e-4))
              (g/undo! :undo/global)
              (is (= doc (g/node-value (:resource-node context) :document)))))
          (finally (.hide window)))))))

;; Animation/group rows are navigation, not an empty object selection that
;; overrides the current transform/delete targets.
(deftest non-object-tree-selection-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [context (context (rig) {}) before @(:state context)]
        (#'view/sync-tree-selection! context [{:kind :animation :id "default"}])
        (is (= before @(:state context)))))))

;; Dragging an IK-controlled child proxies its target; only target translation
;; is authored, while the evaluated parent/child rotations follow the solver.
(deftest ik-child-canvas-drag-test
  (doseq [mode ["setup" "animate"] tool ["move" "pose"] origin [true false]]
    (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
      (ui/run-now
        (let [doc (-> (rig)
                      (data/add-bone {:id "target" :parent "root" :x 200.0})
                      (data/add-ik {:id "ik" :bones ["upper" "lower"] :target "target" :mix 1.0 :bend-positive true}))
              context (context doc {:mode mode :tool tool})
              start (if origin [100.0 0.0] [187.0 0.0])]
          (drag! context start (mapv + start [-80.0 60.0]))
          (let [after (g/node-value (:resource-node context) :document)
                sample (data/evaluate-pose-with-slots after (when (= "animate" mode) "default") 0.5 slots/sample-slots)]
            (is (near? [120.0 60.0] (geometry/transform-point (get-in sample [:transforms "lower"]) [100.0 0.0])) (str mode tool origin))
            (is (not (near? (get-in sample [:transforms "upper"]) (get-in (pose doc) [:transforms "upper"]))))
            (is (= "lower" (:selected @(:state context))))
            (is (= (subvec (:bones doc) 0 3) (subvec (:bones after) 0 3)))
            (g/undo! :undo/global)
            (is (= doc (g/node-value (:resource-node context) :document)))))))))

;; A single distal bone in Pose mode automatically uses its unbranched parent
;; chain, without requiring an invisible prerequisite multi-selection.
(deftest single-pose-child-canvas-drag-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (rig) context (context doc {:tool "pose"})]
        (drag! context [150.0 0.0] [120.0 60.0])
        (let [after (g/node-value (:resource-node context) :document)
              sample (pose after)]
          (is (near? [120.0 60.0] (geometry/transform-point (get-in sample [:transforms "lower"]) [100.0 0.0])))
          (is (not (near? (get-in sample [:transforms "upper"]) (get-in (pose doc) [:transforms "upper"]))))
          (is (= #{"lower"} (:selected-bones @(:state context))))
          (is (= (:bones doc) (:bones after)))
          (g/undo! :undo/global)
          (is (= doc (g/node-value (:resource-node context) :document))))))))

;; An inactive constraint must not capture FK drags, and locking the target
;; blocks proxy edits without changing the constrained child's bind.
(deftest ik-child-drag-boundaries-test
  (doseq [[mix locked manual] [[0.0 false false] [1.0 true false] [1.0 false true] [0.5 false false]]]
    (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
      (ui/run-now
        (let [doc (-> (rig)
                      (data/add-bone {:id "target" :parent "root" :x 200.0})
                      (data/add-ik {:id "ik" :bones ["upper" "lower"] :target "target" :mix mix}))
              context (context doc {:auto-key (not manual) :locked-bones (if locked #{"target"} #{})})]
          (drag! context [100.0 0.0] [20.0 60.0])
          (let [saved (g/node-value (:resource-node context) :document)
                edited (#'view/document context)
                sample (data/sample-pose edited "default" 0.5)
                target (coll/first-where #(= "target" (:id %)) sample)
                child (coll/first-where #(= "lower" (:id %)) sample)]
            (cond
              locked (is (= doc edited))
              (zero? mix) (do (is (= 200.0 (:x target))) (is (= 20.0 (:x child))))
              :else (do (is (= 120.0 (:x target))) (is (= 100.0 (:x child)))))
            (is (= mix (get-in edited [:ik 0 :mix])))
            (when manual
              (is (= doc saved))
              (swap! (:state context) tools/undo-pose false)
              (is (= doc (#'view/document context))))))))))

;; The shipped 05_tail mesh must deform when its IK child is dragged. Keep
;; bind bones and mesh weights unchanged, key the target and undo atomically.
(deftest tail-ik-child-example-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (data/read-json (slurp "test/resources/skeletal-animation/performance-project/tail.skeleton"))
            context (context doc {:selected "bone8" :selected-bones #{"bone8"} :animation "tail_ik_demo" :time 0.0 :zoom 1.0})
            before (data/evaluate-pose-with-slots doc "tail_ik_demo" 0.0 slots/sample-slots)
            transforms (:transforms before)
            start (geometry/transform-point (transforms "bone8") [0.0 0.0])
            goal (geometry/transform-point (transforms "tail_ik_target") [0.0 0.0])
            anchor (geometry/transform-point (transforms "bone7") [0.0 0.0])
            target (mapv #(+ %1 (* 0.7 (- %2 %1))) anchor goal)
            delta (mapv - target goal)]
        (swap! (:state context) assoc :pan-x (- 350.0 (first start)) :pan-y (+ 250.0 (second start)))
        (drag! context start (mapv + start delta))
        (let [after (g/node-value (:resource-node context) :document)
              sample (data/evaluate-pose-with-slots after "tail_ik_demo" 0.0 slots/sample-slots)
              child (coll/first-where #(= "bone8" (:id %)) (:bones sample))]
          (is (near? target (geometry/transform-point (get-in sample [:transforms "bone8"]) [(:length child) 0.0])))
          (is (not (near? (transforms "bone7") (get-in sample [:transforms "bone7"]))))
          (let [slot (coll/first-where #(= "05_tail" (:id %)) (:slots doc))
                points (fn mesh-points [transforms]
                         (geometry/mesh-world-vertices (:attachment slot) (transforms (:bone slot)) transforms []))]
            (is (not= (points transforms) (points (:transforms sample)))))
          (is (= (:slots doc) (:slots after)))
          (is (= (:bones doc) (:bones after)))
          (g/undo! :undo/global)
          (is (= doc (g/node-value (:resource-node context) :document))))))))

;; Dragging either path control must deform the whole tail without moving its
;; root or changing weights. Animation edits key controls and undo atomically.
(deftest tail-path-controls-canvas-test
  (doseq [mode ["setup" "animate"] control ["tail_path_mid" "tail_path_tip"]]
    (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
      (ui/run-now
        (let [doc (data/read-json (slurp "test/resources/skeletal-animation/performance-project/tail_path.skeleton"))
              context (context doc {:mode mode :selected control :selected-bones #{control}
                                    :animation "tail_path_demo" :time 0.0 :zoom 1.0})
              sample (fn sample-tail [document]
                       (data/evaluate-pose-with-slots document (when (= mode "animate") "tail_path_demo") 0.0 slots/sample-slots))
              before (:transforms (sample doc))
              start (geometry/transform-point (before control) [0.0 0.0])
              target (mapv + start [24.0 18.0])]
          (swap! (:state context) assoc :pan-x (- 350.0 (first start)) :pan-y (+ 250.0 (second start)))
          (drag! context start target)
          (let [after (g/node-value (:resource-node context) :document)
                transforms (:transforms (sample after))
                mesh (:attachment (coll/first-where #(= "05_tail" (:id %)) (:slots doc)))
                vertices (fn mesh-points [worlds]
                           (geometry/mesh-world-vertices mesh (worlds "tail") worlds []))]
            (is (= control (:selected @(:state context))))
            (is (near? target (geometry/transform-point (transforms control) [0.0 0.0])))
            (doseq [bone ["bone1" "bone3" "bone4" "bone5" "bone6" "bone7" "bone8"]]
              (is (not (near? (before bone) (transforms bone))) (str mode " " control " " bone)))
            (is (near? (subvec (before "bone1") 4) (subvec (transforms "bone1") 4)))
            (let [tip (coll/first-where #(= "bone8" (:id %)) (:bones doc))]
              (is (near? (geometry/transform-point (transforms "tail_path_tip") [0.0 0.0])
                         (geometry/transform-point (transforms "bone8") [(:length tip) 0.0]))))
            (is (not (near? (vertices before) (vertices transforms))))
            (is (= (:slots doc) (:slots after)))
            (when (= mode "animate")
              (is (= (:bones doc) (:bones after)))
              (is (not= (:animations doc) (:animations after))))
            (g/undo! :undo/global)
            (is (= doc (g/node-value (:resource-node context) :document)))
            (g/redo! :undo/global)
            (is (= after (g/node-value (:resource-node context) :document)))))))))

;; Rebinding to the path must preserve the original visible tail at rest,
;; including all individual influence weights and the mesh topology.
(deftest tail-path-rest-pose-test
  (let [original (data/read-json (slurp "test/resources/skeletal-animation/performance-project/tail.skeleton"))
        converted (data/read-json (slurp "test/resources/skeletal-animation/performance-project/tail_path.skeleton"))
        mesh (fn tail-mesh [doc] (:attachment (coll/first-where #(= "05_tail" (:id %)) (:slots doc))))
        points (fn tail-points [doc]
                 (let [worlds (:transforms (data/evaluate-pose-with-slots doc nil 0.0 slots/sample-slots))]
                   (geometry/mesh-world-vertices (mesh doc) (worlds "tail") worlds [])))
        old (mesh original) new (mesh converted)]
    (is (near? (points original) (points converted)))
    (is (= (:triangles old) (:triangles new)))
    (is (= (:uvs old) (:uvs new)))
    (is (= (mapv #(mapv (juxt :bone :weight) %) (:weights old))
           (mapv #(mapv (juxt :bone :weight) %) (:weights new))))))

;; A controller must win over a nearer ordinary shaft and that bone's move
;; gizmo. The grab offset is preserved at different viewport zoom levels.
(deftest controller-overlap-picking-test
  (doseq [zoom [0.25 1.0 4.0]]
    (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
      (ui/run-now
        (let [doc (-> (rig)
                      (data/add-bone {:id "target" :parent "root" :x 200.0 :length 0.0})
                      (data/add-bone {:id "behind" :parent "root" :x (+ 200.0 (/ 4.0 zoom))
                                      :y (/ -60.0 zoom) :rotation 90.0 :length (/ 120.0 zoom)})
                      (data/add-ik {:id "reach" :bones ["upper" "lower"] :target "target" :mix 1.0}))
              context (context doc {:selected "behind" :selected-bones #{"behind"} :zoom zoom
                                    :pan-x (- 350.0 (* zoom 200.0)) :pan-y 250.0})
              start [(+ 200.0 (/ 4.0 zoom)) 0.0]
              delta [(/ -20.0 zoom) (/ 15.0 zoom)]]
          (is (= "target" (#'view/bone-at context doc (:bones (pose doc)) (:transforms (pose doc)) 354.0 250.0)))
          (drag! context start (mapv + start delta))
          (let [after (g/node-value (:resource-node context) :document)]
            (is (= "target" (:selected @(:state context))))
            (is (near? (mapv + [200.0 0.0] delta)
                       (geometry/transform-point (get-in (pose after) [:transforms "target"]) [0.0 0.0])))
            (is (= (:bones doc) (:bones after)))
            (g/undo! :undo/global)
            (is (= doc (g/node-value (:resource-node context) :document)))))))))

;; Controllers are derived from the path's weights, not naming conventions.
;; Their handles remain easy to pick over a bone through the same pixel.
(deftest weighted-path-controller-picking-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (data/read-json (slurp "test/resources/skeletal-animation/performance-project/tail_path.skeleton"))
            context (context doc {:animation "tail_path_demo" :time 0.0 :zoom 1.0
                                  :selected "bone8" :selected-bones #{"bone8"}})
            worlds (:transforms (data/evaluate-pose-with-slots doc "tail_path_demo" 0.0 slots/sample-slots))
            links (#'view/rig-links context doc)]
        (is (= #{"tail_path_root" "tail_path_mid" "tail_path_tip"} (set (:controls (first links)))))
        (doseq [id ["tail_path_mid" "tail_path_tip"]]
          (let [[x y] (geometry/transform-point (worlds id) [0.0 0.0])]
            (swap! (:state context) assoc :pan-x (- 350.0 x) :pan-y (+ 250.0 y))
            (is (= id (#'view/bone-at context doc (:bones doc) worlds 356.0 250.0)))))
        (is (nil? (#'view/control-at (assoc context :evaluated-pose {:active-bone-ids #{}}) doc worlds 356.0 250.0)))
        (swap! (:state context) assoc :show-bones false)
        (is (nil? (#'view/control-at context doc worlds 356.0 250.0)))))))

;; Bound bone properties keep controller navigation in both modes and omit
;; unrelated constraint creation actions from the compact inspector.
(deftest rig-properties-entry-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (data/read-json (slurp "test/resources/skeletal-animation/performance-project/tail_path.skeleton"))]
        (doseq [mode ["setup" "animate"]]
          (let [context (context doc {:mode mode :animation "tail_path_demo" :selected "bone8" :selected-bones #{"bone8"}})
                panel (javafx.scene.layout.VBox. (into-array javafx.scene.Node (#'view/entity-inspector-content context doc)))
                create (.lookup panel "#skeletal-rig-create-ik")]
            (is (nil? create))
            (is (some? (.lookup panel "#skeletal-rig-configure-path-tail_follow_path")))
            (is (some? (.lookup panel "#skeletal-rig-controller-tail_path_tip")))
            (is (some? (.lookup panel "#skeletal-rig-role")))))))))

;; Length presets live in path follow settings and change all linked
;; Spine settings atomically, without erasing animation keys or mesh bindings.
(deftest path-length-controls-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (-> (data/read-json (slurp "test/resources/skeletal-animation/performance-project/tail_path.skeleton"))
                    (data/set-path-keyframe "tail_path_demo" "tail_follow_path" "mix-rotate" 0.25 0.8 "linear"))
            context (assoc (context doc {:mode "setup" :selected-kind :path :selected "tail_follow_path" :selected-bones #{}}) :localization :k)
            panel (javafx.scene.layout.VBox. (into-array javafx.scene.Node (#'view/entity-inspector-content context doc)))
            choice (.lookup panel "#skeletal-rig-path-tail_follow_path-length-mode")
            fixed "skeletal.rig-path-length-fixed"
            fit "skeletal.rig-path-length-fit"]
        (is (some? choice))
        (.setValue choice fit)
        (let [before (g/node-value (:resource-node context) :document)]
          (.setValue choice fixed)
          (.fireEvent choice (javafx.event.ActionEvent.))
          (let [after (g/node-value (:resource-node context) :document)]
            (is (= {:rotate-mode "chain" :spacing-mode "length" :spacing 0.0}
                   (select-keys (first (:path after)) [:rotate-mode :spacing-mode :spacing])))
            (is (= (:animations doc) (:animations after)))
            (is (= (:slots doc) (:slots after)))
            (g/undo! :undo/global)
            (is (= before (g/node-value (:resource-node context) :document)))
            (g/redo! :undo/global)
            (is (= after (g/node-value (:resource-node context) :document)))))
        (swap! (:state context) assoc :mode "animate" :animation "tail_path_demo")
        (let [panel (javafx.scene.layout.VBox. (into-array javafx.scene.Node (#'view/entity-inspector-content context (g/node-value (:resource-node context) :document))))]
          (is (.isDisable (.lookup panel "#skeletal-rig-path-tail_follow_path-length-mode"))))))))

;; Pulling the weighted tail far beyond reach must not scale any driven bone
;; in fixed-length mode; enabling Chain Scale must still permit stretching.
(deftest path-fixed-length-drag-test
  (doseq [mode ["setup" "animate"] control ["tail_path_mid" "tail_path_tip"]]
    (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
      (ui/run-now
        (let [doc (-> (data/read-json (slurp "test/resources/skeletal-animation/performance-project/tail_path.skeleton"))
                      (data/update-path "tail_follow_path" {:rotate-mode "chain" :spacing-mode "length" :spacing 0.0}))
              context (context doc {:mode mode :selected control :selected-bones #{control} :animation "tail_path_demo" :time 0.0 :zoom 1.0})
              sample (fn sample-tail [d] (:transforms (data/evaluate-pose-with-slots d (when (= mode "animate") "tail_path_demo") 0.0 slots/sample-slots)))
              before (sample doc)
              start (geometry/transform-point (before control) [0.0 0.0])
              axis-length (fn axis-length [transforms id] (let [[a b] (transforms id)] (Math/hypot a b)))]
          (swap! (:state context) assoc :pan-x (- 250.0 (first start)) :pan-y (+ 250.0 (second start)))
          (drag! context start (mapv + start [250.0 100.0]))
          (let [after (g/node-value (:resource-node context) :document)
                transforms (sample after)
                scaled (sample (data/update-path after "tail_follow_path" {:rotate-mode "chainScale" :spacing-mode "proportional" :spacing 1.0}))]
            (doseq [id (:bones (first (:path after)))]
              (is (< (abs (- (axis-length before id) (axis-length transforms id))) 1.0e-6)))
            (is (coll/any? #(> (abs (- (axis-length before %) (axis-length scaled %))) 0.01) (:bones (first (:path after)))))
            (g/undo! :undo/global)
            (is (= doc (g/node-value (:resource-node context) :document)))))))))

;; A bone's IK length checkbox uses the same setup and keyed animation path as
;; the constraint inspector, preserving rest length, existing keys and undo.
(deftest ik-length-checkbox-test
  (doseq [mode ["setup" "animate"]]
    (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
      (ui/run-now
        (let [doc (-> (rig)
                      (data/add-bone {:id "target" :parent "root" :x 400.0 :length 0.0})
                      (data/add-ik {:id "reach" :bones ["upper" "lower"] :target "target" :stretch false}))
              context (context doc {:mode mode :selected "upper" :selected-bones #{"upper"}})
              panel (javafx.scene.layout.VBox. (into-array javafx.scene.Node (#'view/entity-inspector-content context doc)))
              toggle (.lookup panel "#skeletal-rig-ik-reach-stretch")
              sample (fn sample-ik [d] (data/evaluate-pose-with-slots d (when (= mode "animate") "default") 0.5 slots/sample-slots))
              scale-x (fn scale-x [d] (let [[a b] (get-in (sample d) [:transforms "upper"])] (Math/hypot a b)))]
          (is (false? (.isSelected toggle)))
          (is (.isDisable (.lookup panel "#skeletal-rig-ik-reach-compress")))
          (is (< (abs (- 1.0 (scale-x doc))) 1.0e-6))
          (.fire toggle)
          (let [after (g/node-value (:resource-node context) :document)]
            (is (> (scale-x after) 1.5))
            (is (= (:bones doc) (:bones after)))
            (is (true? (:stretch (first (data/sample-ik after (when (= mode "animate") "default") 0.5)))))
            (when (= mode "animate") (is (= (:ik doc) (:ik after))))
            (g/undo! :undo/global)
            (is (= doc (g/node-value (:resource-node context) :document)))
            (g/redo! :undo/global)
            (is (= after (g/node-value (:resource-node context) :document)))))))))
