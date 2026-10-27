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

(ns editor.skeletal-animation-view-test
  (:require [clojure.test :refer [deftest is]]
            [dynamo.graph :as g]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-geometry :as geometry]
            [editor.skeletal-animation-timeline :as timeline]
            [editor.skeletal-animation-tools :as tools]
            [editor.skeletal-animation-view :as view]
            [editor.ui :as ui]
            [internal.system :as system]
            [util.coll :as coll]))

(defn- near? [expected actual]
  (and (= (count expected) (count actual))
       (coll/every? #(< (abs (double %)) 1.0e-5) (mapv - expected actual))))

(defn- rig []
  (-> (data/new-document "interaction")
      (data/update-bone "root" {:x 20.0 :y -15.0 :rotation 30.0 :scale-x 1.5 :scale-y 0.75})
      (data/add-bone {:id "arm" :parent "root" :x 10.0 :y 20.0 :rotation 15.0 :scale-x -1.0 :scale-y 2.0 :length 10.0})
      (data/add-bone {:id "other" :parent "root" :x -30.0 :y 10.0 :rotation -40.0 :length 10.0})))

(defn- context [doc mode]
  {:localization (constantly "Invalid editor operation")
   :state (atom {:draft doc :mode mode :time 0.5 :animation "default" :interpolation "linear"
                 :selected-kind :bone :selected "arm" :timeline-pan -120.0 :timeline-zoom 2.0 :fps 60.0})})

(defn- bone [bones id]
  (coll/first-where #(= id (:id %)) bones))

(defn- slot [doc]
  (first (:slots doc)))

(defn- mesh-document [weighted]
  (let [doc (rig)
        vertices [-5.0 -5.0 5.0 -5.0 5.0 5.0 -5.0 5.0]
        transforms (geometry/world-transforms (:bones doc))
        attachment (cond-> {:type "mesh" :path "/image.png" :width 10.0 :height 10.0
                            :vertices vertices :uvs [0.0 1.0 1.0 1.0 1.0 0.0 0.0 0.0]
                            :triangles [0 1 2 0 2 3]}
                     weighted
                     (assoc :weights
                            (mapv (fn [[x y]]
                                    (let [[bx by] (geometry/inverse-transform-point (transforms "other")
                                                                                    (geometry/transform-point (transforms "arm") [x y]))]
                                      [{:bone "arm" :weight 0.6 :x x :y y}
                                       {:bone "other" :weight 0.4 :x bx :y by}]))
                                  (partitionv 2 vertices))))]
    (data/add-slot doc {:id "mesh" :bone "arm" :attachment attachment})))

(defn- slot-context [doc mode]
  (let [context (context doc mode)]
    (swap! (:state context) assoc :selected-kind :slot :selected "mesh")
    context))

;; A rendered frame shares one deform sample across attachments; reusing it
;; must produce the same weighted geometry without revalidating the document.
(deftest frame-deform-sample-reuse-test
  (let [doc (mesh-document true)
        offsets (vec (repeat (data/deform-size (:attachment (slot doc))) 2.0))
        doc (data/set-deform-keyframe doc "default" "mesh" 0.5 offsets "linear")
        context (slot-context doc "animate")
        transforms (geometry/world-transforms (#'view/pose context doc))
        expected (#'view/attachment-points context doc (slot doc) transforms)
        samples (data/sample-deforms doc "default" 0.5)]
    (with-redefs [data/sample-deforms (fn [& _] (throw (ex-info "Unexpected repeated frame sampling" {})))]
      (is (= expected (#'view/attachment-points (assoc context :sampled-deforms samples) doc (slot doc) transforms))))))

(defn- rendered-vertices [context doc]
  (#'view/attachment-points context doc (slot doc)
                           (geometry/world-transforms (#'view/pose context doc))))

(defn- drag-vertex [context doc vertex target]
  (#'view/drag-vertices context doc #{vertex} (nth (partitionv 2 (rendered-vertices context doc)) vertex) target))

;; Setup drags operate in the transformed parent's local coordinates and
;; preserve reflection when uniformly scaling a bone with a negative X scale.
(deftest setup-bone-gizmo-test
  (let [doc (rig)
        context (context doc "setup")
        arm (bone (:bones doc) "arm")
        parent ((geometry/world-transforms (:bones doc)) "root")
        start (geometry/transform-point parent [20.0 20.0])]
    (doseq [[tool local-target expected] [["move" [25.0 28.0] [15.0 28.0 15.0 -1.0 2.0]]
                                        ["rotate" [10.0 30.0] [10.0 20.0 105.0 -1.0 2.0]]
                                        ["scale" [30.0 20.0] [10.0 20.0 15.0 -2.0 4.0]]]]
      (let [changed (#'view/drag-bone context doc arm tool start (geometry/transform-point parent local-target))]
        (is (near? expected ((juxt :x :y :rotation :scale-x :scale-y) (bone (:bones changed) "arm"))))
        (is (= (:animations doc) (:animations changed)))
        (is (= doc (:draft @(:state context))))))))

;; Animate drags key the sampled local pose at the current playhead, leaving
;; the setup pose untouched and recording both axes for move and scale tools.
(deftest animated-bone-gizmo-test
  (let [doc (-> (rig)
                (data/set-keyframe "default" "arm" "x" 0.0 10.0 "linear")
                (data/set-keyframe "default" "arm" "x" 1.0 30.0 "linear"))
        context (context doc "animate")
        arm (bone (:bones doc) "arm")
        parent ((geometry/world-transforms (:bones doc)) "root")
        start (geometry/transform-point parent [30.0 20.0])]
    (doseq [[tool local-target properties expected] [["move" [35.0 28.0] #{"x" "y"} [25.0 28.0 15.0 -1.0 2.0]]
                                                   ["rotate" [20.0 30.0] #{"rotation"} [20.0 20.0 105.0 -1.0 2.0]]
                                                   ["scale" [40.0 20.0] #{"scale-x" "scale-y"} [20.0 20.0 15.0 -2.0 4.0]]]]
      (let [changed (#'view/drag-bone context doc arm tool start (geometry/transform-point parent local-target))
            sample (bone (data/sample-pose changed "default" 0.5) "arm")
            keys-at-time (into #{} (comp (filter #(coll/any? (fn [key] (= 0.5 (:time key))) (:keys %)))
                                        (map :property)) (get-in changed [:animations 0 :tracks]))]
        (is (near? expected ((juxt :x :y :rotation :scale-x :scale-y) sample)))
        (is (= properties keys-at-time))
        (is (= (:bones doc) (:bones changed)))))))

;; Setup mesh drags update the bind coordinates of every contributing bone so
;; weighted and unweighted vertices reach the cursor without moving neighbors.
(deftest setup-mesh-drag-test
  (doseq [weighted [false true]]
    (let [doc (mesh-document weighted)
          context (slot-context doc "setup")
          before (rendered-vertices context doc)
          target [87.0 23.0]
          changed (drag-vertex context doc 1 target)
          after (rendered-vertices context changed)]
      (is (near? target (subvec after 2 4)))
      (is (near? (subvec before 0 2) (subvec after 0 2)))
      (is (near? (subvec before 4) (subvec after 4)))
      (is (= (mapv #(update % :deforms (fnil vec [])) (:animations doc))
             (mapv #(update % :deforms (fnil vec [])) (:animations changed))))
      (when weighted
        (is (= (mapv #(mapv :weight %) (get-in doc [:slots 0 :attachment :weights]))
               (mapv #(mapv :weight %) (get-in changed [:slots 0 :attachment :weights]))))))))

;; Animated mesh drags produce native per-influence FFD keys, render the keyed
;; position immediately and preserve unrelated offsets and all setup geometry.
(deftest animated-mesh-drag-test
  (doseq [weighted [false true]]
    (let [doc (mesh-document weighted)
          size (data/deform-size (:attachment (slot doc)))
          offsets (-> (vec (repeat size 0.0)) (assoc 0 1.0) (assoc 1 2.0))
          doc (-> doc
                  (data/set-keyframe "default" "arm" "rotation" 0.5 50.0 "linear")
                  (data/set-deform-keyframe "default" "mesh" 0.5 offsets "linear"))
          context (slot-context doc "animate")
          before (rendered-vertices context doc)
          target [87.0 23.0]
          changed (drag-vertex context doc 1 target)
          after (rendered-vertices context changed)
          recorded (get (data/sample-deforms changed "default" 0.5) "mesh")]
      (is (near? target (subvec after 2 4)))
      (is (near? (subvec before 0 2) (subvec after 0 2)))
      (is (near? (subvec before 4) (subvec after 4)))
      (is (= size (count recorded)))
      (is (near? [1.0 2.0] (subvec recorded 0 2)))
      (is (= (:slots doc) (:slots changed)))
      (is (= (:bones doc) (:bones changed))))))

;; Weight assignment is a setup-only operation. A new influence must preserve
;; the vertex's world position and must not shift the other vertices or weights.
(deftest weight-assignment-test
  (let [doc (mesh-document false)
        context (slot-context doc "setup")
        before (rendered-vertices context doc)
        changed (#'view/apply-weight! context doc 1 "other" 0.25)
        weights (get-in changed [:slots 0 :attachment :weights])]
    (is (near? before (rendered-vertices context changed)))
    (is (near? [0.75 0.25] (mapv :weight (weights 1))))
    (is (= ["arm" "other"] (mapv :bone (weights 1))))
    (is (= [1.0] (mapv :weight (weights 0))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (#'view/apply-weight! (slot-context doc "animate") doc 1 "other" 0.25)))))

;; Timeline coordinates account for zoom and pan, snap to frame boundaries and
;; clamp to the animation's duration so dragging never writes an invalid time.
(deftest timeline-coordinate-test
  (let [doc (data/update-animation (rig) "default" {:duration 4.0})
        context (context doc "animate")]
    (is (near? [0.0] [(#'view/timeline-time context -500.0)]))
    (is (near? [4.0] [(#'view/timeline-time context 2000.0)]))
    (is (near? [1.25] [(#'view/timeline-time context (#'view/timeline-x context 1.25))]))
    (is (near? [(/ 76.0 60.0)] [(#'view/timeline-time context (#'view/timeline-x context 1.263))]))
    (swap! (:state context) assoc :timeline-pan -480.0 :timeline-zoom 4.0)
    (is (near? [2.0] [(#'view/timeline-time context (#'view/timeline-x context 2.0))]))))

;; Vertical wheel events must reach the track ScrollPane in compact layouts;
;; only horizontal/Shift panning and Control zoom consume the gesture.
(deftest timeline-scroll-routing-test
  (ui/run-now
    (doseq [[dx dy shift control consumed pan zoom]
            [[0.0 -80.0 false false false -120.0 2.0]
             [-40.0 0.0 false false true -160.0 2.0]
             [0.0 -80.0 true false true -200.0 2.0]
             [0.0 80.0 false true true -120.0 (* 2.0 (Math/pow 1.002 80.0))]]]
      (let [canvas (javafx.scene.canvas.Canvas.)
            context (assoc (context (rig) "animate") :timeline canvas)
            event (javafx.scene.input.ScrollEvent.
                    javafx.scene.input.ScrollEvent/SCROLL 10.0 10.0 10.0 10.0
                    shift control false false false false dx dy dx dy
                    javafx.scene.input.ScrollEvent$HorizontalTextScrollUnits/NONE 0.0
                    javafx.scene.input.ScrollEvent$VerticalTextScrollUnits/NONE 0.0 0 nil)]
        (#'view/timeline-events! context)
        (.handle (.getOnScroll canvas) event)
        (is (= consumed (.isConsumed event)))
        (is (near? [pan zoom] [(:timeline-pan @(:state context)) (:timeline-zoom @(:state context))]))))))

;; Singular parent scales cannot be inverted by gizmos. Bone dragging leaves
;; the document untouched and mesh dragging raises a recoverable editor error.
(deftest singular-drag-test
  (let [doc (data/update-bone (mesh-document true) "root" {:scale-x 0.0})
        context (slot-context doc "setup")]
    (is (nil? (#'view/drag-bone context doc (bone (:bones doc) "arm") "move" [0.0 0.0] [1.0 1.0])))
    (is (thrown? clojure.lang.ExceptionInfo (drag-vertex context doc 0 [1.0 1.0])))))

;; Crossing the negative X axis is a two-degree drag, not a nearly complete
;; reverse turn that would create a large unwanted rotation in animation keys.
(deftest rotation-drag-wrap-test
  (doseq [mode ["setup" "animate"]]
    (let [doc (rig)
          context (context doc mode)
          arm (bone (:bones doc) "arm")
          parent ((geometry/world-transforms (:bones doc)) "root")
          polar-point (fn [degrees]
                        (let [radians (Math/toRadians (double degrees))]
                          (geometry/transform-point parent
                                                    [(+ (:x arm) (* 10.0 (Math/cos radians)))
                                                     (+ (:y arm) (* 10.0 (Math/sin radians)))])))
          changed (#'view/drag-bone context doc arm "rotate" (polar-point 179.0) (polar-point -179.0))
          sampled (if (= "animate" mode) (data/sample-pose changed "default" 0.5) (:bones changed))]
      (is (near? [17.0] [(:rotation (bone sampled "arm"))])))))

;; Spine slots may be empty in setup pose or retain an unsupported attachment.
;; These slots must contribute no preview geometry instead of dereferencing nil.
(deftest empty-spine-slot-preview-test
  (doseq [extra [{} {:unsupported-attachment true}]]
    (let [doc (data/add-slot (rig) (merge {:id "empty" :bone "arm"} extra))
          context (context doc "setup")]
      (is (= [] (#'view/attachment-points context doc (slot doc) (geometry/world-transforms (:bones doc))))))))

;; Setup-only imports have no animation clips. Their timeline still provides a
;; finite placeholder range until the user creates the first animation.
(deftest setup-only-timeline-test
  (let [doc (data/remove-animation (rig) "default")
        context (context doc "setup")]
    (is (near? [0.0] [(#'view/timeline-time context -500.0)]))
    (is (near? [1.0] [(#'view/timeline-time context 2000.0)]))))

;; Editing weights after FFD animation exists remaps native offsets by bone
;; identity rather than shifting the deformation of following vertices.
(deftest weight-assignment-retains-deform-test
  (let [doc (mesh-document false)
        offsets [1.0 2.0 3.0 4.0 5.0 6.0 7.0 8.0]
        doc (data/set-deform-keyframe doc "default" "mesh" 0.5 offsets "linear")
        context (slot-context doc "setup")
        changed (#'view/apply-weight! context doc 1 "other" 0.25)
        recorded (get (data/sample-deforms changed "default" 0.5) "mesh")]
    (is (near? [1.0 2.0 3.0 4.0] (subvec recorded 0 4)))
    (is (near? [5.0 6.0 7.0 8.0] (subvec recorded 6)))
    (is (near? (rendered-vertices (slot-context doc "animate") doc)
               (rendered-vertices (slot-context changed "animate") changed)))
    (is (= 10 (data/deform-size (get-in changed [:slots 0 :attachment]))))
    (is (near? (rendered-vertices context doc) (rendered-vertices context changed)))))

;; Add Image creates a default region without an explicit type. It must render
;; the same rectangle as a region imported with type="region".
(deftest default-region-preview-test
  (let [doc (data/add-slot (rig) {:id "image" :bone "arm"
                                :attachment {:path "/image.png" :width 20.0 :height 10.0}})
        context (context doc "setup")
        worlds (geometry/world-transforms (:bones doc))
        implicit (slot doc)
        explicit (assoc-in implicit [:attachment :type] "region")
        points (#'view/attachment-points context doc implicit worlds)]
    (is (= 8 (count points)))
    (is (near? (#'view/attachment-points context doc explicit worlds) points))))

;; Import, undo and rename can invalidate a selected entity or weight target.
;; Repairing selection must remove dangling IDs before inspector construction.
(deftest selection-repair-test
  (let [doc (rig)
        context (context doc "setup")]
    (swap! (:state context) assoc :selected-kind :slot :selected "removed" :weight-bone "removed")
    (#'view/repair-selection! context doc)
    (is (= :bone (:selected-kind @(:state context))))
    (is (= "root" (:selected @(:state context))))
    (is (not (contains? @(:state context) :weight-bone)))
    (swap! (:state context) assoc :selected-kind :bone :selected "arm" :weight-bone "other")
    (#'view/repair-selection! context doc)
    (is (= "arm" (:selected @(:state context))))
    (is (= "other" (:weight-bone @(:state context))))))

(defn- invoke-key-action [context action]
  (let [committed (atom nil)
        messages (atom [])]
    (with-redefs-fn {#'g/node-value (fn [_ _] (:draft @(:state context)))
                    #'view/commit! (fn [_ doc] (reset! committed (data/validate-document doc)))
                    #'view/message! (fn [_ text] (swap! messages conj text))}
      #(action context))
    {:document @committed :messages @messages}))

;; JSON integer times and floating point playhead times refer to the same key;
;; editing that key must preserve its existing curve for every track kind.
(deftest integer-key-time-retains-interpolation-test
  (let [curve [1.2 3.0 1.8 4.0]]
    (is (= {:type "bezier" :curve curve}
           (#'view/interpolation-at [{:time 1 :interpolation "bezier" :curve curve}] 1.0 "linear")))
    (is (= "stepped"
           (#'view/interpolation-at [{:time 1 :interpolation "stepped"}] 1.0 "linear")))
    (is (= "linear"
           (#'view/interpolation-at [{:time 1 :interpolation "stepped"}] 2.0 "linear")))))

;; Programmatic duration refresh after undo or clip switching resets the input
;; baseline, so re-entering the former value commits and untouched precision stays intact.
(deftest number-field-refresh-baseline-test
  (ui/run-now
    (let [changes (atom [])
          field (#'view/number-field "test-duration" 1.234567 #(swap! changes conj %))
          commit #(.handle (.getOnAction field) (javafx.event.ActionEvent.))]
      (commit)
      (is (= [] @changes))
      (.setText field "2.0")
      (commit)
      (#'view/refresh-number-field! field 1.234567)
      (commit)
      (is (= [2.0] @changes))
      (.setText field "2.0")
      (commit)
      (is (= [2.0 2.0] @changes)))))

;; Editing an existing bone key changes its value without replacing its
;; Bezier controls with the toolbar's interpolation for newly created keys.
(deftest bone-value-retains-bezier-test
  (let [curve [0.65 20.0 0.85 40.0]
        doc (-> (rig)
                (data/set-keyframe "default" "arm" "rotation" 1.0 40.0 "linear")
                (data/set-keyframe "default" "arm" "rotation" 0.5 20.0 {:type "bezier" :curve curve}))
        context (context doc "animate")
        changed (#'view/change-bone-value context doc "arm" "rotation" 22.0)
        track (coll/first-where #(= "rotation" (:property %)) (get-in changed [:animations 0 :tracks]))
        key (first (:keys track))]
    (is (= "bezier" (:interpolation key)))
    (is (= curve (:curve key)))
    (is (near? [22.0] [(:value key)]))
    (is (= 2 (count (:keys track))))
    (is (= (:bones doc) (:bones changed)))))

;; FFD drags retain the interpolation curve on the edited key for both native
;; weighted offsets and unweighted vertices, then render the new cursor point.
(deftest mesh-drag-retains-bezier-test
  (doseq [weighted [false true]]
    (let [doc (mesh-document weighted)
          size (data/deform-size (:attachment (slot doc)))
          curve [0.65 0.0 0.85 1.0]
          doc (-> doc
                  (data/set-deform-keyframe "default" "mesh" 1.0 (vec (repeat size 5.0)) "linear")
                  (data/set-deform-keyframe "default" "mesh" 0.5 (vec (repeat size 0.0)) {:type "bezier" :curve curve}))
          context (slot-context doc "animate")
          changed (drag-vertex context doc 1 [87.0 23.0])
          key (first (get-in changed [:animations 0 :deforms 0 :keys]))]
      (is (= "bezier" (:interpolation key)))
      (is (= curve (:curve key)))
      (is (near? [87.0 23.0] (subvec (rendered-vertices context changed) 2 4)))
      (is (= size (count (:vertices key))))
      (is (= (:slots doc) (:slots changed))))))

;; Explicit bone keys and zero-distance gizmo edits store the authored local
;; rotation, never the solved IK preview angle, in both setup and animate mode.
(deftest bone-key-does-not-bake-ik-test
  (doseq [mode ["setup" "animate"]]
    (let [doc (-> (rig)
                  (data/add-ik {:id "aim" :bones ["arm"] :target "other" :mix 1.0 :bend-positive true})
                  (data/set-keyframe "default" "arm" "rotation" 0.0 15.0 "linear")
                  (data/set-keyframe "default" "arm" "rotation" 1.0 35.0 "linear"))
          context (context doc mode)
          _ (swap! (:state context) assoc :property "rotation" :tool "rotate")
          expected (if (= "setup" mode) 15.0 25.0)
          solved (:rotation (bone (#'view/pose context doc) "arm"))
          parent ((geometry/world-transforms (#'view/pose context doc)) "root")
          position (geometry/transform-point parent [20.0 20.0])
          dragged (#'view/drag-bone context doc (bone (:bones doc) "arm") "rotate" position position)
          result (invoke-key-action context #'view/set-key!)
          keyed (:document result)]
      (is (not (near? [expected] [solved])))
      (is (near? [expected] [(:rotation (bone (if (= "setup" mode) (:bones dragged) (data/sample-pose dragged "default" 0.5)) "arm"))]))
      (is (= [""] (:messages result)))
      (is (near? [expected] [(:rotation (bone (data/sample-pose keyed "default" 0.5) "arm"))]))
      (is (= (:bones doc) (:bones keyed)))
      (is (= (:ik doc) (:ik keyed))))))

;; The Set Key action dispatches to the native FFD key API with its curve
;; argument intact, rather than calling an incomplete arity or losing offsets.
(deftest set-mesh-key-retains-bezier-test
  (doseq [weighted [false true]]
    (let [doc (mesh-document weighted)
          size (data/deform-size (:attachment (slot doc)))
          offsets (vec (range size))
          curve [0.65 0.0 0.85 1.0]
          doc (-> doc
                  (data/set-deform-keyframe "default" "mesh" 1.0 (vec (repeat size 5.0)) "linear")
                  (data/set-deform-keyframe "default" "mesh" 0.5 offsets {:type "bezier" :curve curve}))
          context (slot-context doc "animate")
          _ (swap! (:state context) assoc :tool "mesh")
          result (invoke-key-action context #'view/set-key!)
          key (first (get-in result [:document :animations 0 :deforms 0 :keys]))]
      (is (= [""] (:messages result)))
      (is (near? offsets (:vertices key)))
      (is (= "bezier" (:interpolation key)))
      (is (= curve (:curve key))))))

;; Setting a selected IK key records mix and bend direction on the constraint
;; while preserving an existing Bezier curve and leaving target bones unkeyed.
(deftest set-ik-key-retains-bezier-test
  (let [curve [0.65 0.25 0.85 0.75]
        doc (-> (rig)
                (data/add-ik {:id "aim" :bones ["arm"] :target "other" :mix 1.0 :bend-positive true})
                (data/set-ik-keyframe "default" "aim" 1.0 0.75 true "linear")
                (data/set-ik-keyframe "default" "aim" 0.5 0.25 false {:type "bezier" :curve curve}))
        context (context doc "animate")
        _ (swap! (:state context) assoc :selected-kind :ik :selected "aim")
        result (invoke-key-action context #'view/set-key!)
        key (first (get-in result [:document :animations 0 :ik-tracks 0 :keys]))]
    (is (= [""] (:messages result)))
    (is (near? [0.25] [(:mix key)]))
    (is (false? (:bend-positive key)))
    (is (= "bezier" (:interpolation key)))
    (is (= curve (:curve key)))
    (is (= [] (get-in result [:document :animations 0 :tracks])))))

;; Moving a selected vertex group preserves its spacing and the cursor offset,
;; including rotated/scaled parents and weighted attachments.
(deftest selected-mesh-drag-test
  (doseq [weighted [false true]
          mode ["setup" "animate"]]
    (let [doc (mesh-document weighted)
          context (slot-context doc mode)
          before (vec (partitionv 2 (rendered-vertices context doc)))
          selection #{0 2}
          changed (#'view/drag-vertices context doc selection [12.0 8.0] [22.0 28.0])
          after (vec (partitionv 2 (rendered-vertices context changed)))]
      (doseq [index (range (count before))]
        (is (near? (if (contains? selection index) (mapv + (before index) [10.0 20.0]) (before index))
                   (after index)))))))

;; Direct assignment must preserve other locked bones, not just stop when the
;; painted bone itself is locked; this is shared by numeric and drag editing.
(deftest direct-weights-respect-lock-test
  (let [doc (mesh-document true)
        context (slot-context doc "setup")
        original (get-in (slot doc) [:attachment :weights 0])]
    (swap! (:state context) assoc :locked-bones #{"other"})
    (let [changed (#'view/apply-weight! context doc 0 "arm" 1.0)
          influences (get-in (slot changed) [:attachment :weights 0])]
      (is (= (coll/first-where #(= "other" (:bone %)) original)
             (coll/first-where #(= "other" (:bone %)) influences)))
      (is (< (abs (- 0.6 (:weight (first influences)))) 1.0e-9)))
    (swap! (:state context) assoc :locked-bones #{"arm"})
    (is (= original (get-in (slot (#'view/apply-weight! context doc 0 "arm" 0.0)) [:attachment :weights 0])))))

;; Softness uses its own Bezier channel: Set Key must not copy the mix curve
;; onto softness or erase the constraint's discrete compression flags.
(deftest set-ik-softness-key-retains-independent-curve-test
  (let [mix-curve [0.6 0.2 0.9 0.8]
        softness-curve [0.6 8.0 0.9 25.0]
        doc (-> (rig)
                (data/add-ik {:id "aim" :bones ["arm"] :target "other" :compress true :stretch true})
                (data/set-ik-keyframe "default" "aim" 1.0 0.8 true "linear")
                (data/set-ik-keyframe "default" "aim" 0.5 0.2 true {:type "bezier" :curve mix-curve})
                (data/set-ik-softness-keyframe "default" "aim" 1.0 25.0 "linear")
                (data/set-ik-softness-keyframe "default" "aim" 0.5 8.0 {:type "bezier" :curve softness-curve}))
        context (context doc "animate")
        _ (swap! (:state context) assoc :selected-kind :ik :selected "aim" :property "IK softness")
        result (invoke-key-action context #'view/set-key!)
        key (first (get-in result [:document :animations 0 :ik-tracks 0 :keys]))]
    (is (= [""] (:messages result)))
    (is (= 8.0 (:softness key)))
    (is (= mix-curve (:curve key)))
    (is (= softness-curve (:softness-curve key)))
    (is (:compress key))
    (is (:stretch key))))

;; A batch drag offsets each influence in its own bone space and performs one
;; FFD write, retaining different existing offsets rather than collapsing them.
(deftest selected-mesh-drag-retains-influence-offsets-test
  (let [doc (mesh-document true)
        size (data/deform-size (:attachment (slot doc)))
        doc (data/set-deform-keyframe doc "default" "mesh" 0.5 (mapv double (range size)) "linear")
        context (slot-context doc "animate")
        before (rendered-vertices context doc)
        calls (atom 0)
        setter data/set-deform-keyframe
        changed (with-redefs [data/set-deform-keyframe (fn [& args] (swap! calls inc) (apply setter args))]
                  (#'view/drag-vertices context doc #{0 1 2 3} [0.0 0.0] [7.0 -3.0]))]
    (is (= 1 @calls))
    (is (near? (into [] (mapcat #(mapv + % [7.0 -3.0])) (partitionv 2 before))
               (rendered-vertices context changed)))
    (is (= (:slots doc) (:slots changed)))))

;; Physics keys target the selected constraint or explicit global channel,
;; preserve an existing curve, and expose reset events to batch timeline tools.
(deftest physics-key-actions-test
  (let [curve [0.6 3.0 0.9 9.0]
        doc (-> (rig)
                (data/add-physics {:id "spring" :bone "arm" :x 1.0 :wind-global true})
                (data/set-physics-keyframe "default" "spring" "wind" 1.0 9.0 "linear")
                (data/set-physics-keyframe "default" "spring" "wind" 0.5 3.0 {:type "bezier" :curve curve}))
        context (context doc "animate")]
    (swap! (:state context) assoc :selected-kind :physics :selected "spring" :property "wind")
    (let [result (invoke-key-action context #'view/set-key!)
          keys (get-in result [:document :animations 0 :physics-tracks 0 :keys])]
      (is (= [""] (:messages result)))
      (is (= curve (:curve (first keys))))
      (is (= 3.0 (:value (first keys)))))
    (swap! (:state context) assoc :physics-scope "global")
    (let [result (invoke-key-action context #'view/set-key!)]
      (is (= "" (:physics (last (get-in result [:document :animations 0 :physics-tracks]))))))
    (swap! (:state context) assoc :property "reset")
    (let [result (invoke-key-action context #'view/set-key!)
          track (last (get-in result [:document :animations 0 :physics-tracks]))]
      (is (= "reset" (:property track)))
      (is (= {:type :physics :id "" :property "reset" :time 0.5}
             (#'view/key-ref track (first (:keys track)))))
      (is (= "stepped" (:interpolation (first (:keys track))))))))

;; Cached evaluation must give the same pose after seeking backwards or editing
;; a constraint, rather than retaining stale physics velocities from playback.
(deftest physics-view-cache-invalidation-test
  (let [doc (-> (rig) (data/add-physics {:id "spring" :bone "arm" :x 1.0 :gravity 8.0}))
        context (assoc (context doc "animate") :pose-cache (atom nil))]
    (doseq [time [0.0 0.5 1.0 0.25]]
      (swap! (:state context) assoc :time time)
      (is (= (data/evaluate-pose doc "default" time) (#'view/evaluated-pose context doc))))
    (let [edited (data/update-physics doc "spring" {:gravity 2.0})]
      (is (= (data/evaluate-pose edited "default" 0.25) (#'view/evaluated-pose context edited))))))

;; Refresh must retain a valid Physics selection instead of treating the new
;; constraint kind as missing and replacing its inspector with the root bone.
(deftest physics-selection-survives-refresh-test
  (let [doc (data/add-physics (rig) {:id "spring" :bone "arm"})
        context (context doc "setup")]
    (swap! (:state context) assoc :selected-kind :physics :selected "spring")
    (#'view/repair-selection! context doc)
    (is (= {:selected-kind :physics :selected "spring"}
           (select-keys @(:state context) [:selected-kind :selected])))))

;; Consecutive batch operations keep references to moved keys, so Snap after
;; Scale acts on the same selection rather than silently doing nothing.
(deftest batch-key-selection-follows-edits-test
  (let [doc (-> (rig)
                (data/set-keyframe "default" "arm" "x" 0.0 0.0 "linear")
                (data/set-keyframe "default" "arm" "x" 1.0 100.0 "linear"))
        context (context doc "animate")
        state (:state context)]
    (swap! state assoc :selected-keys (set (timeline/all-key-refs doc "default")))
    (with-redefs-fn
      {#'g/node-value (fn [_ _] (:draft @state))
       #'view/attempt! (fn [_ action] (action))
       #'view/commit! (fn [_ updated] (swap! state assoc :draft updated))}
      #(do
         (#'view/batch-keys! context
                            (fn [d id refs] (timeline/scale-keys d id refs 0.0 0.333 {}))
                            (fn [time] (* 0.333 time)))
         (is (= #{0.0 0.333} (into #{} (map :time) (:selected-keys @state))))
         (#'view/batch-keys! context
                            (fn [d id refs] (timeline/snap-keys d id refs 60.0 {}))
                            (fn [time] (/ (Math/round (* time 60.0)) 60.0)))
         (is (= #{0.0 (/ 20.0 60.0)} (into #{} (map :time) (:selected-keys @state))))
         (is (= (:selected-keys @state) (set (timeline/all-key-refs (:draft @state) "default"))))
         (#'view/batch-keys! context timeline/delete-keys nil)
         (is (= #{} (:selected-keys @state)))
         (is (= [] (timeline/all-key-refs (:draft @state) "default")))))))

;; Selecting a mesh exposes its deformation lane without scrolling past the
;; parent bone channels, even when the timeline panel is compact.
(deftest selected-mesh-timeline-priority-test
  (let [doc (mesh-document false)
        tracks (#'view/timeline-tracks (slot-context doc "animate") doc)]
    (is (= {:slot "mesh" :property "deform" :keys nil} (first tracks)))
    (is (= "x" (:property (second tracks))))))

;; Filtering retains ancestor context and matching descendants, instead of
;; making nested bones unreachable when searching the hierarchy.
(deftest hierarchy-search-retains-ancestor-test
  (ui/run-now
    (let [root (javafx.scene.control.TreeItem. {:id "Skeleton"})
          parent (javafx.scene.control.TreeItem. {:id "root"})
          match (javafx.scene.control.TreeItem. {:id "Left Arm"})
          other (javafx.scene.control.TreeItem. {:id "leg"})]
      (.addAll (.getChildren parent) ^java.util.Collection [match other])
      (.add (.getChildren root) parent)
      (is (#'view/filter-tree! root "arm"))
      (is (= [parent] (vec (.getChildren root))))
      (is (= [match] (vec (.getChildren parent))))
      (is (.isExpanded parent)))))

;; The visible shear tool edits the selected bone's shear channel and records
;; it in animation mode, without changing scale or the setup pose.
(deftest shear-tool-animation-test
  (let [doc (rig)
        setup (context doc "setup")
        result (#'view/drag-bone setup doc (bone (:bones doc) "arm") "shear" [0.0 0.0] [0.0 20.0])
        animated (context doc "animate")
        keyed (#'view/drag-bone animated doc (bone (:bones doc) "arm") "shear" [0.0 0.0] [0.0 20.0])]
    (is (not= (:shear-y (bone (:bones doc) "arm")) (:shear-y (bone (:bones result) "arm"))))
    (is (= (:scale-x (bone (:bones doc) "arm")) (:scale-x (bone (:bones result) "arm"))))
    (is (= (:bones doc) (:bones keyed)))
    (is (coll/any? #(and (= "arm" (:bone %)) (= "shear-y" (:property %)))
                  (:tracks (first (:animations keyed)))))))

;; A constrained drag projects onto the selected world axis before parent-space
;; conversion; the other world coordinate must stay unchanged under reflection.
(deftest axis-constrained-translation-test
  (let [doc (rig) context (context doc "setup") bone (bone (:bones doc) "arm")
        start [100.0 80.0] target [140.0 110.0]
        before (get (data/world-transforms (:bones doc)) "arm")]
    (doseq [axis [0 1]]
      (swap! (:state context) assoc :coordinate-space "world" :drag {:axis axis})
      (let [changed (#'view/drag-bone context doc bone "move" start target)
            after (get (data/world-transforms (:bones changed)) "arm")]
        (is (near? (if (zero? axis) [40.0 0.0] [0.0 30.0]) (mapv - (subvec after 4) (subvec before 4))))))))

;; Creation converts both endpoints into the selected parent's space, preserving
;; the drawn world endpoints; animation mode must never create setup bones.
(deftest create-bone-drag-test
  (let [doc (rig) context (context doc "setup") parent (bone (:bones doc) "arm")
        changed (#'view/create-bone-drag context doc parent [20.0 35.0] [95.0 60.0] "new")
        new-bone (bone (:bones changed) "new")
        transform (get (data/world-transforms (:bones changed)) "new")]
    (is (= "arm" (:parent new-bone)))
    (is (near? [20.0 35.0] (geometry/transform-point transform [0.0 0.0])))
    (is (near? [95.0 60.0] (geometry/transform-point transform [(:length new-bone) 0.0])))
    (is (= (:animations doc) (:animations changed)))
    (swap! (:state context) assoc :mode "animate")
    (is (thrown? clojure.lang.ExceptionInfo (#'view/create-bone-drag context doc parent [0.0 0.0] [1.0 1.0] "bad")))))

(g/defnode ToolTransactionFixture
  (property document g/Any))

;; Exercise real graph transactions, not a commit mock: Auto Key off must leave
;; authored data untouched, and a paired manual key must undo as one operation.
(deftest auto-key-and-group-transaction-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (let [doc (rig)
          resource-node (g/make-node! ToolTransactionFixture :document doc)
          context (assoc (context doc "animate") :resource-node resource-node)
          state (:state context)]
      (swap! state #(-> % (dissoc :draft) (assoc :auto-key false)))
      (with-redefs-fn {#'view/message! (fn [_ _])}
        #(do
           (#'view/pose-change! context (fn [d] (#'view/change-bone-value context d "arm" "x" 80.0)))
           (#'view/pose-change! context (fn [d] (#'view/change-bone-value context d "arm" "y" 90.0)))
           (is (= doc (g/node-value resource-node :document)))
           (is (near? [80.0 90.0] ((juxt :x :y) (bone (data/sample-pose (#'view/document context) "default" 0.5) "arm"))))
           (#'view/key-properties! context ["x" "y"])
           (is (nil? (:pending-pose @state)))
           (is (near? [80.0 90.0] ((juxt :x :y) (bone (data/sample-pose (g/node-value resource-node :document) "default" 0.5) "arm"))))
           (g/undo! :undo/global)
           (is (= doc (g/node-value resource-node :document)))
           (g/redo! :undo/global)
           (is (near? [80.0 90.0] ((juxt :x :y) (bone (data/sample-pose (g/node-value resource-node :document) "default" 0.5) "arm"))))
           (swap! state assoc :auto-key true)
           (#'view/pose-change! context (fn [d] (#'view/change-bone-value context d "arm" "rotation" 75.0)))
           (is (nil? (:pending-pose @state)))
           (is (near? [75.0] [(:rotation (bone (data/sample-pose (g/node-value resource-node :document) "default" 0.5) "arm"))])))))))

;; An unkeyed preview must not appear as authored keys in the curve editor.
;; This guards against temporary pose keys leaking into timeline editing UI.
(deftest unkeyed-curve-display-test
  (let [doc (rig)
        edited (data/set-keyframe doc "default" "arm" "x" 0.5 80.0 "linear")
        context (context doc "animate")]
    (swap! (:state context) #(-> % (dissoc :draft) (assoc :property "x" :auto-key false) (tools/stage-pose doc edited)))
    (with-redefs [g/node-value (fn [_ _] doc)]
      (is (= [] (:keys (#'view/curve-state context))))
      (is (= 80.0 (:x (bone (data/sample-pose (#'view/document context) "default" 0.5) "arm")))))))

;; Compensation must preserve children's complete world transforms under a
;; reflected, nonuniform parent, including when the edit uses numeric fields.
(deftest child-compensation-world-transform-test
  (let [doc (rig) context (context doc "setup")
        _ (swap! (:state context) assoc :compensate-bones true :selected "root")
        edited (#'view/change-displayed-value context doc (first (:bones doc)) "rotation" 75.0)
        before (data/world-transforms (:bones doc))
        after (data/world-transforms (:bones edited))]
    (doseq [id ["arm" "other"]] (is (near? (get before id) (get after id))))))

;; Images stay fixed when their bone changes; unkeyed data and undo are tested
;; separately by the live UI run, while this guards region geometry preservation.
(deftest image-compensation-world-vertices-test
  (let [doc (data/add-slot (data/update-bone (rig) "arm" {:scale-x 1.0 :scale-y 1.0}) {:id "image" :bone "arm" :attachment {:type "region" :path "/image.png" :width 20.0 :height 10.0}})
        context (context doc "setup")
        _ (swap! (:state context) assoc :compensate-images true)
        edited (#'view/change-displayed-value context doc (bone (:bones doc) "arm") "rotation" 85.0)]
    (is (= "region" (get-in edited [:slots 0 :attachment :type])))
    (is (near? (#'view/attachment-points context doc (slot doc) (data/world-transforms (:bones doc)))
               (#'view/attachment-points context edited (slot edited) (data/world-transforms (:bones edited)))))))
