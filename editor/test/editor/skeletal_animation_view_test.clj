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
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-geometry :as geometry]
            [editor.skeletal-animation-view :as view]
            [editor.ui :as ui]
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
          changed (#'view/replace-vertex context doc 1 target)
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
          changed (#'view/replace-vertex context doc 1 target)
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
    (is (thrown? clojure.lang.ExceptionInfo (#'view/replace-vertex context doc 0 [1.0 1.0])))))

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
    (is (near? [1.0 2.0 3.0 4.0 0.0 0.0 5.0 6.0 7.0 8.0] recorded))
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
    (with-redefs-fn {#'view/commit! (fn [_ doc] (reset! committed (data/validate-document doc)))
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
          changed (#'view/replace-vertex context doc 1 [87.0 23.0])
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
