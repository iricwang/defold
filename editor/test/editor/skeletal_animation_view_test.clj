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
            [editor.skeletal-animation-events :as events]
            [editor.skeletal-animation-geometry :as geometry]
            [editor.skeletal-animation-mesh :as mesh]
            [editor.skeletal-animation-slots :as slots]
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

;; Wheel over row labels scrolls rows; over the plot it zooms around the cursor.
;; Horizontal or Shift wheel pans without changing the scale.
(deftest timeline-scroll-routing-test
  (ui/run-now
    (doseq [[x dx dy shift consumed]
            [[10.0 0.0 -80.0 false false]
             [400.0 -40.0 0.0 false true]
             [400.0 0.0 -80.0 true true]
             [400.0 0.0 80.0 false true]]]
      (let [canvas (javafx.scene.canvas.Canvas.)
            context (assoc (context (rig) "animate") :timeline canvas)
            before @(:state context)
            before-time (/ (- x 180.0 (:timeline-pan before)) (* 120.0 (:timeline-zoom before)))
            event (javafx.scene.input.ScrollEvent.
                    javafx.scene.input.ScrollEvent/SCROLL x 10.0 x 10.0
                    shift false false false false false dx dy dx dy
                    javafx.scene.input.ScrollEvent$HorizontalTextScrollUnits/NONE 0.0
                    javafx.scene.input.ScrollEvent$VerticalTextScrollUnits/NONE 0.0 0 nil)]
        (#'view/timeline-events! context)
        (.handle (.getOnScroll canvas) event)
        (is (= consumed (.isConsumed event)))
        (let [after @(:state context)]
          (cond
            (not consumed) (is (= before after))
            (or shift (not (zero? dx)))
            (do (is (= (:timeline-zoom before) (:timeline-zoom after)))
                (is (= (+ (:timeline-pan before) dx (if shift dy 0.0)) (:timeline-pan after))))
            :else (do (is (> (:timeline-zoom after) (:timeline-zoom before)))
                      (is (near? [before-time] [(/ (- x 180.0 (:timeline-pan after)) (* 120.0 (:timeline-zoom after)))])))))))))

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
;; FFD key, retaining different existing offsets rather than collapsing them.
(deftest selected-mesh-drag-retains-influence-offsets-test
  (let [doc (mesh-document true)
        size (data/deform-size (:attachment (slot doc)))
        doc (data/set-deform-keyframe doc "default" "mesh" 0.5 (mapv double (range size)) "linear")
        context (slot-context doc "animate")
        before (rendered-vertices context doc)
        changed (#'view/drag-vertices context doc #{0 1 2 3} [0.0 0.0] [7.0 -3.0])]
    (is (= (mapv :time (get-in doc [:animations 0 :deforms 0 :keys]))
           (mapv :time (get-in changed [:animations 0 :deforms 0 :keys]))))
    (is (= 1 (count (filterv #(= 0.5 (:time %)) (get-in changed [:animations 0 :deforms 0 :keys])))))
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
    (is (= {:slot "mesh" :skin "default" :attachment "mesh" :property "deform" :keys []} (first tracks)))
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

;; Event selection survives refresh and uses discrete timeline references.
(deftest event-selection-and-lane-test
  (let [doc (-> (rig)
                (events/add-definition {:name "hit" :int 3})
                (events/set-keyframe "default" {:name "hit" :time 0.5}))
        context (context doc "animate")]
    (#'view/set-selection! context :event "hit")
    (#'view/repair-selection! context doc)
    (is (= :event (:selected-kind @(:state context))))
    (is (= "hit" (:selected @(:state context))))
    (let [track (first (#'view/timeline-tracks context doc))]
      (is (= "hit" (:event track)))
      (is (= {:type :event :id "hit" :time 0.5}
             (#'view/key-ref track (first (:keys track))))))))

;; Slot and draw-order keys must not be mistaken for mesh deform keys.
(deftest slot-animation-lane-reference-test
  (let [doc (-> (mesh-document false)
                (slots/set-keyframe "default" "mesh" "attachment" {:time 0.5 :name nil})
                (slots/set-keyframe "default" "mesh" "rgba" {:time 0.5 :color "ff00ffff"})
                (slots/set-draw-order-keyframe "default" 0.5 ["mesh"]))
        context (slot-context doc "animate")
        tracks (#'view/timeline-tracks context doc)
        refs (coll/into-> tracks #{} (mapcat (fn [track] (mapv #(#'view/key-ref track %) (:keys track)))))]
    (is (contains? refs {:type :slot :id "mesh" :property "attachment" :time 0.5}))
    (is (contains? refs {:type :slot :id "mesh" :property "rgba" :time 0.5}))
    (is (contains? refs {:type :draw-order :id "draw-order" :time 0.5}))))

;; Key Shown resolves each lane instead of repeating the selected event.
(deftest event-property-transaction-test
  (ui/run-now
    (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
      (let [doc (-> (rig)
                    (events/add-definition {:name "hit"})
                    (events/add-definition {:name "sound"}))
            resource-node (g/make-node! ToolTransactionFixture :document doc)
            context (assoc (context doc "animate") :resource-node resource-node
                           :status (javafx.scene.control.Label.))]
        (swap! (:state context) dissoc :draft)
        (#'view/set-selection! context :event "hit")
        (#'view/key-properties! context ["◆ hit" "◆ sound"])
        (is (= ["hit" "sound"]
               (mapv :name (events/keyframes (g/node-value resource-node :document) "default"))))))))

;; Mixed visual transforms cannot be authored back into the base animation.
(deftest mixed-preview-edit-guard-test
  (let [context (context (rig) "animate")]
    (swap! (:state context) assoc :mix-enabled true)
    (is (thrown? clojure.lang.ExceptionInfo (#'view/require-editable-preview! context)))
    (swap! (:state context) assoc :mix-enabled false)
    (is (nil? (#'view/require-editable-preview! context)))))

;; Polygon attachments use the same weighted Setup and FFD drag path as paths.
(deftest polygon-attachment-drag-test
  (doseq [type ["boundingbox" "clipping"]
          weighted [false true]
          mode ["setup" "animate"]]
    (let [doc (mesh-document weighted)
          source (:attachment (slot doc))
          attachment (-> source (dissoc :uvs :triangles :hull) (assoc :type type))
          doc (data/update-slot doc "mesh" {:attachment attachment})
          context (slot-context doc mode)
          before (rendered-vertices context doc)
          origin (subvec before 0 2)
          target (mapv + origin [11.0 -7.0])
          changed (#'view/drag-vertices context doc #{0} origin target)
          result (rendered-vertices (slot-context changed mode) changed)]
      (is (near? target (subvec result 0 2)))
      (is (near? (subvec before 2) (subvec result 2)))
      (is (= type (get-in (data/read-json (data/write-json changed)) [:slots 0 :attachment :type]))))))

;; Empty canvas clicks and toggling off the final slot leave a selection kind
;; without an ID. The inspector must show the selection hint, not sample nil.
(deftest empty-selection-inspector-test
  (ui/run-now
    (let [doc (mesh-document false)]
      (doseq [mode ["setup" "animate"]
              kind [nil :slot :bone :ik :transform :path :physics]]
        (let [context (context doc mode)
              state (:state context)]
          (swap! state assoc :selected-kind kind :selected nil
                 :selected-slots #{} :selected-bones #{})
          (#'view/repair-selection! context doc)
          (let [before @state
                controls (#'view/entity-inspector context doc)]
            (is (= 1 (count controls)) (str mode " / " kind))
            (is (coll/any? #(= "Invalid editor operation" (.getText ^javafx.scene.control.Label %))
                           (.lookupAll ^javafx.scene.Node (first controls) ".label")))
            (is (= before @state))
            (is (= doc (:draft @state)))))))))

;; Both image and existing mesh selections expose an enabled meshing entry.
(deftest smart-mesh-entry-visibility-test
  (ui/run-now
    (let [mesh (mesh-document false)
          region (update-in mesh [:slots 0 :attachment]
                            #(-> % (assoc :type "region" :width 20.0 :height 20.0 :x 0.0 :y 0.0 :rotation 0.0 :scale-x 1.0 :scale-y 1.0)
                                 (dissoc :uvs :vertices :triangles :weights)))]
      (doseq [[doc disabled] [[mesh false] [region false]]]
        (let [context (slot-context doc "setup")
              _ (swap! (:state context) assoc :vertex 0 :weight 1.0)
              nodes (#'view/entity-inspector-content context doc)
              button (.lookup (javafx.scene.layout.VBox. (into-array javafx.scene.Node nodes)) "#skeletal-smart-mesh")]
          (is (some? button))
          (is (.isVisible ^javafx.scene.control.Button button))
          (is (= disabled (.isDisable ^javafx.scene.control.Button button)))
          (is (some? (.getTooltip ^javafx.scene.control.Button button))))))))

;; Animation mode must expose FFD editing without exposing setup topology or
;; weights. Opening the tool must retain the setup mesh and pause playback.
(deftest animate-mesh-deform-entry-test
  (ui/run-now
    (let [doc (mesh-document false)
          context (slot-context doc "animate")
          _ (swap! (:state context) assoc :playing true :tool "move")
          panel (javafx.scene.layout.VBox.
                  (into-array javafx.scene.Node (#'view/entity-inspector context doc)))
          edit (.lookup panel "#skeletal-edit-deform")]
      (is (some? edit))
      (is (some? (.lookup panel "#skeletal-key-deform")))
      (is (nil? (.lookup panel "#skeletal-smart-mesh")))
      (is (nil? (.lookup panel "#skeletal-assign-weight")))
      (.fire ^javafx.scene.control.Button edit)
      (is (= {:tool "mesh" :mesh-mode "modify" :property "deform" :playing false}
             (select-keys @(:state context) [:tool :mesh-mode :property :playing])))
      (is (= doc (#'view/document context))))))

;; The all-node graph must retain separate channel identities for identical
;; properties, so a curve edit cannot silently modify a different bone.
(deftest all-node-timeline-and-curve-target-test
  (let [doc (-> (rig)
                (data/set-keyframe "default" "arm" "rotation" 0.0 1.0 "linear")
                (data/set-keyframe "default" "arm" "rotation" 1.0 2.0 "linear")
                (data/set-keyframe "default" "other" "rotation" 0.0 3.0 "linear")
                (data/set-keyframe "default" "other" "rotation" 1.0 4.0 "linear"))
        context (context doc "animate")]
    (swap! (:state context) assoc :selected nil :selected-kind nil)
    (let [tracks (#'view/timeline-tracks context doc)
          other (coll/first-where #(= "other" (:bone %)) tracks)
          key (assoc (first (:keys other)) :track (:channel-id other))
          updated (#'view/update-curve context doc key "stepped")]
      (is (= #{"arm" "other"} (set (mapv :bone tracks))))
      (is (= 2 (count (set (mapv :channel-id tracks)))))
      (is (identical? tracks (#'view/timeline-tracks context doc)))
      (is (= "stepped" (:interpolation (first (:keys (coll/first-where #(= "other" (:bone %)) (:tracks (first (:animations updated)))))))))
      (is (= "linear" (:interpolation (first (:keys (coll/first-where #(= "arm" (:bone %)) (:tracks (first (:animations updated)))))))))
      (with-redefs [g/node-value (fn read-document [_ _] doc)]
        (let [graph (#'view/curve-state context)]
          (is (= 2 (count (:channels graph))))
          (is (= 2 (count (set (mapv :property (:channels graph)))))))))
    (swap! (:state context) assoc :selected "arm" :selected-kind :bone :selected-bones #{"arm" "other"})
    (is (= #{"arm" "other"} (set (mapv :bone (#'view/timeline-tracks context doc)))))
    (swap! (:state context) assoc :selected-bones #{"arm"})
    (is (= #{"arm"} (set (mapv :bone (#'view/timeline-tracks context doc)))))))

;; Recreating a bone changes its setup endpoints without moving descendants or
;; the selected mesh; the temporary helper bone must never enter the document.
(deftest recreate-bone-compensation-test
  (let [doc (data/add-bone (mesh-document false) {:id "child" :parent "arm" :x 20.0 :y 15.0 :rotation 45.0})
        context (slot-context doc "setup")
        before (rendered-vertices context doc)
        old-child (get (data/world-transforms (:bones doc)) "child")
        after (#'view/creation-document context {:document doc :bone (bone (:bones doc) "arm")
                                                 :world [25.0 40.0] :create-id "temporary" :recreate true} [80.0 90.0])]
    (is (= (count (:bones doc)) (count (:bones after))))
    (is (nil? (bone (:bones after) "temporary")))
    (is (near? old-child (get (data/world-transforms (:bones after)) "child")))
    (is (near? before (rendered-vertices context after)))))

;; Attachment-assisted creation retains mesh vertices and existing FFD offsets
;; in world space instead of shifting the artwork onto the new bone's origin.
(deftest create-with-mesh-attachment-test
  (let [doc (data/set-deform-keyframe (mesh-document false) "default" "mesh" 0.5 [1.0 2.0 3.0 4.0 5.0 6.0 7.0 8.0] "linear")
        context (slot-context doc "setup")
        after (#'view/creation-document context {:document doc :bone (bone (:bones doc) "root")
                                                 :world [50.0 80.0] :create-id "new" :slots ["mesh"]} [90.0 110.0])]
    (is (= "new" (:bone (slot after))))
    (is (near? (rendered-vertices context doc) (rendered-vertices context after)))
    (is (near? (rendered-vertices (slot-context doc "animate") doc)
               (rendered-vertices (slot-context after "animate") after)))))

;; A gesture previews without authoring, commits once, undoes in one step,
;; supports sibling creation and cancels atomically with Escape.
(deftest creation-gesture-transaction-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (rig) resource-node (g/make-node! ToolTransactionFixture :document doc)
            canvas (javafx.scene.canvas.Canvas. 500.0 400.0)
            context (assoc (context doc "setup") :resource-node resource-node :canvas canvas :status (javafx.scene.control.Label.))
            state (:state context)
            mouse (fn send-mouse [type x y shift]
                    (javafx.event.Event/fireEvent canvas
                                                  (javafx.scene.input.MouseEvent. type x y x y javafx.scene.input.MouseButton/PRIMARY 1
                                                                                  shift false false false true false false false false false nil)))
            press javafx.scene.input.MouseEvent/MOUSE_PRESSED
            drag javafx.scene.input.MouseEvent/MOUSE_DRAGGED
            release javafx.scene.input.MouseEvent/MOUSE_RELEASED]
        (swap! state #(-> % (dissoc :draft) (assoc :tool "create" :zoom 1.0 :pan-x 0.0 :pan-y 400.0 :show-images false)))
        (#'view/creation-events! context)
        (#'view/creation-events! context)
        (mouse press 200.0 200.0 false)
        (mouse drag 260.0 170.0 false)
        (is (= doc (g/node-value resource-node :document)))
        (is (= 4 (count (:bones (:draft @state)))))
        (mouse release 260.0 170.0 false)
        (let [created (g/node-value resource-node :document)
              id (:selected @state)]
          (is (= "arm" (:parent (bone (:bones created) id))))
          (g/undo! :undo/global)
          (is (= doc (g/node-value resource-node :document)))
          (g/redo! :undo/global)
          (is (= created (g/node-value resource-node :document)))
          (mouse press 300.0 250.0 true)
          (mouse release 300.0 250.0 true)
          (is (= id (:selected @state)))
          (is (= 0.0 (:length (last (:bones (g/node-value resource-node :document))))))
          (g/undo! :undo/global)
          (mouse press 300.0 250.0 false)
          (mouse drag 330.0 260.0 false)
          (javafx.event.Event/fireEvent canvas
                                        (javafx.scene.input.KeyEvent. javafx.scene.input.KeyEvent/KEY_PRESSED "" "" javafx.scene.input.KeyCode/ESCAPE false false false false))
          (mouse release 330.0 260.0 false)
          (is (= created (g/node-value resource-node :document)))
          (is (nil? (:draft @state)))
          (swap! state assoc :mode "animate")
          (mouse press 300.0 250.0 false)
          (mouse release 330.0 260.0 false)
          (is (= created (g/node-value resource-node :document))))))))

;; Alternate skins must fail before authoring instead of silently shifting
;; hidden artwork when the selected setup attachment is reparented.
(deftest create-alternate-attachment-guard-test
  (let [doc (assoc (mesh-document false) :spine-source
                   "{\"skins\":[{\"name\":\"other\",\"attachments\":{\"mesh\":{\"alternate\":{}}}}]}")
        context (slot-context doc "setup")]
    (is (thrown? clojure.lang.ExceptionInfo
                 (#'view/creation-document context
                                           {:document doc :bone (bone (:bones doc) "root")
                                            :world [50.0 80.0] :create-id "new" :slots ["mesh"]}
                                           [90.0 110.0])))))

;; Binding works mesh-first and bone-first; pending picks are UI-only, while
;; completion is a single graph transaction with undo and FFD preservation.
(deftest binding-workflow-transaction-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (data/set-deform-keyframe (mesh-document false) "default" "mesh" 0.5 [1.0 2.0 3.0 4.0 5.0 6.0 7.0 8.0] "linear")
            node (g/make-node! ToolTransactionFixture :document doc)
            context (assoc (slot-context doc "setup") :resource-node node :status (javafx.scene.control.Label.))]
        (swap! (:state context) dissoc :draft)
        (#'view/start-binding! context)
        (#'view/pick-binding! context :bone "arm")
        (is (= doc (g/node-value node :document)))
        (#'view/finish-binding! context)
        (let [bound (g/node-value node :document)]
          (is (some? (get-in bound [:slots 0 :attachment :weights])))
          (is (near? (rendered-vertices (slot-context doc "animate") doc)
                     (rendered-vertices (slot-context bound "animate") bound))))
        (is (nil? (:binding @(:state context))))
        (g/undo! :undo/global)
        (is (= doc (g/node-value node :document)))
        (swap! (:state context) assoc :rigging-panel :properties :tool "move")
        (#'view/set-selection! context :bone "arm")
        (#'view/start-binding! context)
        (#'view/pick-binding! context :slot "mesh")
        (#'view/transport! context :play)
        (is (= "setup" (:mode @(:state context))))
        (is (false? (:playing @(:state context))))
        (is (nil? (:binding @(:state context))))
        (g/undo! :undo/global)
        (is (= doc (g/node-value node :document)))))))

;; Weight slider dragging is one undoable edit, not one transaction per pixel;
;; its initial value comes from the selected vertices, not a stale UI default.
(deftest weight-slider-transaction-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (mesh-document true)
            node (g/make-node! ToolTransactionFixture :document doc)
            context (assoc (slot-context doc "setup") :resource-node node :status (javafx.scene.control.Label.))
            pane (javafx.scene.layout.VBox.)]
        (swap! (:state context) #(-> % (dissoc :draft) (assoc :weight-bone "arm" :vertices #{0})))
        (.addAll (.getChildren pane) ^java.util.Collection (#'view/weights-inspector context doc))
        (let [slider ^javafx.scene.control.Slider (.lookup pane "#skeletal-direct-weight-slider")]
          (is (= 60.0 (.getValue slider)))
          (.setValueChanging slider true)
          (.setValue slider 70.0)
          (.setValue slider 80.0)
          (is (= doc (g/node-value node :document)))
          (.setValueChanging slider false)
          (let [after (g/node-value node :document)]
            (is (= 0.8 (get-in after [:slots 0 :attachment :weights 0 0 :weight])))
            (is (= (get-in doc [:slots 0 :attachment :weights 1]) (get-in after [:slots 0 :attachment :weights 1]))))
          (g/undo! :undo/global)
          (is (= doc (g/node-value node :document))))))))

;; Undo can remove the binding while the overlay toggle remains enabled.
;; Rendering that state must leave a transparent canvas, not stop UI timers.
(deftest unbound-weight-overlay-render-test
  (ui/run-now
    (let [canvas (javafx.scene.canvas.Canvas. 40.0 40.0)
          gc (.getGraphicsContext2D canvas)
          points [[0.0 0.0] [40.0 0.0] [0.0 40.0]]
          state {:weight-overlay true}
          params (doto (javafx.scene.SnapshotParameters.) (.setFill javafx.scene.paint.Color/TRANSPARENT))
          alpha (fn [] (.getOpacity (.getColor (.getPixelReader (.snapshot canvas params nil)) 5 5)))]
      (#'view/draw-weight-overlay! gc state points [[0 1 2]] nil)
      (is (= 0.0 (alpha)))
      (#'view/draw-weight-overlay! gc state points [[0 1 2]] (vec (repeat 3 [{:bone "arm" :weight 1.0}])))
      (is (> (alpha) 0.5))
      (.clearRect gc 0.0 0.0 40.0 40.0)
      (is (nil? (#'view/draw-weight-overlay! gc state points [[0 1 2]] nil))))))

;; Weights live in the inspector tab, never a fourth split column. Hot reload
;; removes an existing legacy dock without changing authoring or undo state.
(deftest integrated-weight-tab-layout-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (mesh-document true)
            node (g/make-node! ToolTransactionFixture :document doc)
            viewport (javafx.scene.layout.StackPane.)
            tree (javafx.scene.layout.VBox.)
            properties (javafx.scene.layout.VBox.)
            legacy (javafx.scene.layout.VBox.)
            split (javafx.scene.control.SplitPane. (into-array javafx.scene.Node [viewport legacy tree properties]))
            root (javafx.scene.layout.BorderPane. split)
            scene (javafx.scene.Scene. root 1100.0 700.0)
            context (assoc (slot-context doc "setup") :root root :viewport viewport :resource-node node)
            undo (:undo @g/*the-system*)]
        (ui/user-data! root :editor.skeletal-animation-view/weight-dock {:panel legacy})
        (swap! (:state context) assoc :tool "weights" :rigging-panel :weights)
        (.applyCss (.getRoot scene))
        (.layout root)
        (#'view/refresh-weight-dock! context)
        (is (= [viewport tree properties] (vec (.getItems split))))
        (.addAll (.getChildren properties) ^java.util.Collection (#'view/entity-inspector context doc))
        (is (some? (.lookup properties "#skeletal-auto-weights")))
        (is (.isSelected ^javafx.scene.control.ToggleButton (.lookup properties "#skeletal-rigging-weights")))
        (.fire ^javafx.scene.control.ToggleButton (.lookup properties "#skeletal-inspector-lock"))
        (is (.isDisabled (.lookup properties "#skeletal-auto-weights")))
        (.fire ^javafx.scene.control.ToggleButton (.lookup properties "#skeletal-inspector-lock"))
        (.fire ^javafx.scene.control.ToggleButton (.lookup properties "#skeletal-rigging-properties"))
        (is (= :properties (:rigging-panel @(:state context))))
        (is (= "move" (:tool @(:state context))))
        (is (= doc (g/node-value node :document)))
        (is (= undo (:undo @g/*the-system*)))))))

;; Group gizmos share the primary world delta, including reflected parents and
;; selected descendants. Descendants must not receive the delta twice.
(deftest multi-selection-transform-test
  (doseq [mode ["setup" "animate"]
          tool ["move" "rotate" "scale" "shear"]]
    (let [doc (data/add-bone (rig) {:id "child" :parent "arm" :x 12.0 :y 3.0})
          context (context doc mode)
          _ (swap! (:state context) assoc :selected-bones #{"arm" "other" "child"} :coordinate-space "world")
          primary (bone (:bones doc) "arm")
          before (data/world-transforms (#'view/pose context doc))
          origin (subvec (before "arm") 4 6)
          start (mapv + origin [25.0 18.0])
          target (mapv + origin [40.0 42.0])
          single (#'view/drag-bone context doc primary tool start target)
          delta (geometry/compose-transforms (get (data/world-transforms (#'view/pose context single)) "arm")
                                             (geometry/inverse-transform (before "arm")))
          result (#'view/transform-group context doc primary single)
          after (data/world-transforms (#'view/pose context result))]
      (is (= result (data/validate-document result)))
      (doseq [id ["arm" "other" "child"]]
        (is (near? (geometry/compose-transforms delta (before id)) (after id)) (str mode " " tool " " id)))
      (is (near? (before "root") (after "root")))
      (when (= "animate" mode) (is (= (:bones doc) (:bones result)))))))

;; Selected attachments on one bone must not compound its transformation.
;; Numeric edits use the same group path as dragging.
(deftest attachment-group-numeric-transform-test
  (let [doc (-> (rig) (data/add-slot {:id "a" :bone "arm"})
                (data/add-slot {:id "b" :bone "arm"}) (data/add-slot {:id "c" :bone "other"}))
        context (context doc "setup")
        _ (swap! (:state context) assoc :selected-kind :slot :selected "a" :selected-slots #{"a" "b" "c"} :coordinate-space "world")
        before (data/world-transforms (:bones doc))
        result (#'view/change-displayed-value context doc (bone (:bones doc) "arm") "x" (+ 15.0 (nth (before "arm") 4)))
        after (data/world-transforms (:bones result))]
    (doseq [id ["arm" "other"]]
      (is (near? (update (before id) 4 + 15.0) (after id))))))

;; Group field edits and group key operations each have one undo/redo step.
;; Manual keys preserve all moved nodes when Auto Key is disabled.
(deftest multi-selection-undo-and-key-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (let [doc (rig) resource-node (g/make-node! ToolTransactionFixture :document doc)
          context (assoc (context doc "setup") :resource-node resource-node :status (ui/run-now (javafx.scene.control.Label.)))
          state (:state context)]
      (swap! state #(-> % (dissoc :draft) (assoc :selected-bones #{"arm" "other"} :coordinate-space "world")))
      (ui/run-now
        (do
          (#'view/pose-change! context (fn [doc] (#'view/change-displayed-value context doc (bone (:bones doc) "arm") "rotation" 80.0)))
          (let [edited (g/node-value resource-node :document)]
            (is (not= doc edited))
            (g/undo! :undo/global) (is (= doc (g/node-value resource-node :document)))
            (g/redo! :undo/global) (is (= edited (g/node-value resource-node :document))))
          (swap! state assoc :mode "animate" :auto-key false)
          (let [base (g/node-value resource-node :document)]
            (#'view/pose-change! context (fn [doc] (#'view/change-displayed-value context doc (bone (:bones doc) "arm") "rotation" 120.0)))
            (is (= base (g/node-value resource-node :document)))
            (let [preview (#'view/document context)]
              (#'view/key-properties! context ["rotation"])
              (let [keyed (g/node-value resource-node :document)]
                (doseq [id ["arm" "other"]]
                  (is (near? (get (data/world-transforms (data/sample-pose preview "default" 0.5)) id)
                             (get (data/world-transforms (data/sample-pose keyed "default" 0.5)) id))))
                (g/undo! :undo/global) (is (= base (g/node-value resource-node :document)))
                (g/redo! :undo/global) (is (= keyed (g/node-value resource-node :document)))))))))))

;; Parent/child deletion is atomic; root failure retains the selection.
(deftest multi-selection-delete-undo-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (let [doc (data/add-bone (rig) {:id "child" :parent "arm"})
          node (g/make-node! ToolTransactionFixture :document doc)
          context (assoc (context doc "setup") :resource-node node :status (ui/run-now (javafx.scene.control.Label.)))]
      (swap! (:state context) #(-> % (dissoc :draft) (assoc :selected-bones #{"arm" "child" "other"})))
      (ui/run-now
        (do
          (#'view/delete-selection! context)
          (is (= ["root"] (mapv :id (:bones (g/node-value node :document)))))
          (g/undo! :undo/global) (is (= doc (g/node-value node :document)))
          (swap! (:state context) assoc :selected "root" :selected-kind :bone :selected-bones #{"root"})
          (#'view/delete-selection! context)
          (is (= doc (g/node-value node :document)))
          (is (= "root" (:selected @(:state context)))))))))

;; Real event dispatch must delete bones from tree and canvas in weight mode,
;; without treating timeline or text-field Delete as a structural edit.
(deftest structural-delete-key-routing-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (rig) node (g/make-node! ToolTransactionFixture :document doc)
            tree (javafx.scene.control.TreeView.) canvas (javafx.scene.canvas.Canvas.)
            text (javafx.scene.control.TextField. "abc")
            root (javafx.scene.layout.VBox.)
            context (assoc (context doc "setup") :resource-node node :tree tree :canvas canvas :root root
                           :status (javafx.scene.control.Label.))
            dispatch (fn dispatch-delete [target code]
                       (javafx.event.Event/fireEvent target
                                                     (javafx.scene.input.KeyEvent. javafx.scene.input.KeyEvent/KEY_PRESSED "" "" code false false false false)))]
        (.addAll (.getChildren root) ^java.util.Collection [tree canvas text])
        (swap! (:state context) #(-> % (dissoc :draft) (assoc :tool "weights" :selected-bones #{"arm"})))
        (#'view/rigging-events! context)
        (#'view/rigging-events! context)
        (dispatch text javafx.scene.input.KeyCode/DELETE)
        (dispatch root javafx.scene.input.KeyCode/DELETE)
        (is (= doc (g/node-value node :document)))
        (dispatch tree javafx.scene.input.KeyCode/DELETE)
        (is (nil? (bone (:bones (g/node-value node :document)) "arm")))
        (g/undo! :undo/global)
        (is (= doc (g/node-value node :document)))
        (swap! (:state context) assoc :selected-kind :bone :selected "arm" :selected-bones #{"arm"})
        (dispatch canvas javafx.scene.input.KeyCode/BACK_SPACE)
        (is (nil? (bone (:bones (g/node-value node :document)) "arm")))
        (g/undo! :undo/global)
        (swap! (:state context) assoc :selected-kind :bone :selected "arm" :selected-bones #{"arm"} :mode "animate")
        (dispatch tree javafx.scene.input.KeyCode/DELETE)
        (is (= doc (g/node-value node :document)))))))

;; A mesh rigidly attached to its slot bone is not yet usefully bound to a
;; new chain. Completing that bind must assign weights, preserve its shape,
;; move descendants and mesh together, and undo as one transaction.
(deftest initial-rigid-binding-follows-chain-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [base (mesh-document false)
            doc (-> base
                    (data/add-bone {:id "chain" :parent "arm" :length 5.0})
                    (data/add-bone {:id "tip" :parent "chain" :x 5.0 :length 5.0})
                    (data/update-slot "mesh" {:attachment {:weights
                                                           (mapv (fn [[x y]] [{:bone "arm" :weight 1.0 :x x :y y}
                                                                              {:bone "chain" :weight 0.0 :x x :y y}
                                                                              {:bone "tip" :weight 0.0 :x (- x 5.0) :y y}])
                                                                 (partitionv 2 (get-in base [:slots 0 :attachment :vertices])))}}))
            node (g/make-node! ToolTransactionFixture :document doc)
            context (assoc (slot-context doc "setup") :resource-node node :status (javafx.scene.control.Label.))]
        (swap! (:state context) dissoc :draft)
        (#'view/start-binding! context)
        (#'view/finish-binding! context)
        (let [bound (g/node-value node :document)
              positive (coll/into-> (get-in bound [:slots 0 :attachment :weights]) #{} cat
                         (filter #(pos? (:weight %))) (map :bone))
              before (rendered-vertices (slot-context bound "setup") bound)]
          (is (contains? positive "chain"))
          (is (contains? positive "tip"))
          (is (near? (rendered-vertices (slot-context doc "setup") doc) before))
          (#'view/set-selection! context :bone "arm")
          (swap! (:state context) assoc :coordinate-space "world")
          (let [world (data/world-transforms (:bones bound))
                moved (#'view/change-displayed-value context bound (bone (:bones bound) "arm") "x" (+ 10.0 (nth (world "arm") 4)))
                after-world (data/world-transforms (:bones moved))]
            (doseq [id ["arm" "chain" "tip"]]
              (is (near? (update (world id) 4 + 10.0) (after-world id))))
            (is (near? (mapv (fn [index value] (+ value (if (even? index) 10.0 0.0))) (range (count before)) before)
                       (rendered-vertices (slot-context moved "setup") moved))))
          (g/undo! :undo/global) (is (= doc (g/node-value node :document)))
          (g/redo! :undo/global) (is (= bound (g/node-value node :document))))))))

;; Editing a genuinely weighted mesh's binding retains its authored weights.
(deftest existing-distributed-binding-is-preserved-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (mesh-document true) node (g/make-node! ToolTransactionFixture :document doc)
            context (assoc (slot-context doc "setup") :resource-node node :status (javafx.scene.control.Label.))]
        (swap! (:state context) dissoc :draft)
        (#'view/start-binding! context)
        (#'view/finish-binding! context)
        (is (= (get-in doc [:slots 0 :attachment :weights])
               (get-in (g/node-value node :document) [:slots 0 :attachment :weights])))))))

;; IK target picking is non-destructive until confirmed, preserves the tip on
;; scaled roots, rejects descendant targets, and undoes target plus constraint.
(deftest ik-target-workflow-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (-> (rig)
                    (data/update-bone "arm" {:scale-x 1.0 :scale-y 1.0})
                    (data/add-bone {:id "tip" :parent "arm" :x 10.0 :y 0.0 :length 8.0 :rotation 35.0}))
            node (g/make-node! ToolTransactionFixture :document doc)
            context (assoc (context doc "setup") :resource-node node :status (javafx.scene.control.Label.))
            state (:state context)]
        (swap! state dissoc :draft)
        (#'view/add-ik! context)
        (is (= ["arm"] (get-in @state [:ik-pick :bones])))
        (is (= doc (g/node-value node :document)))
        (#'view/finish-ik! context "tip" nil)
        (is (= doc (g/node-value node :document)))
        (swap! state assoc :selected-bones #{"arm" "tip"})
        (#'view/add-ik! context)
        (is (= ["arm" "tip"] (get-in @state [:ik-pick :bones])))
        (#'view/finish-ik! context "tip" nil)
        (let [after (g/node-value node :document)
              target (:target (first (:ik after)))
              worlds (data/world-transforms (:bones after))
              tip (geometry/transform-point (worlds "tip") [8.0 0.0])]
          (is (= 1 (count (:ik after))))
          (is (= "root" (:parent (bone (:bones after) target))))
          (is (near? tip (geometry/transform-point (worlds target) [0.0 0.0])))
          (is (nil? (:ik-pick @state)))
          (is (= target (:selected @state)))
          (g/undo! :undo/global)
          (is (= doc (g/node-value node :document)))
          (g/redo! :undo/global)
          (is (= after (g/node-value node :document))))))))

;; Enlarged axis arrowheads remain pickable at a fixed screen size regardless
;; of zoom, while their center and space beyond the handles remain free.
(deftest enlarged-move-handle-hit-test
  (let [doc (rig) context (context doc "setup")
        [x y] (geometry/transform-point (get (data/world-transforms (:bones doc)) "arm") [0.0 0.0])]
    (doseq [zoom [0.2 1.0 5.0]]
      (swap! (:state context) assoc :tool "move" :coordinate-space "world" :zoom zoom :pan-x 0.0 :pan-y 0.0)
      (let [px (* x zoom) py (- (* y zoom))]
        (is (= 0 (#'view/gizmo-axis context doc (+ px 76.0) (+ py 9.0))))
        (is (= 1 (#'view/gizmo-axis context doc (+ px 9.0) (- py 76.0))))
        (is (nil? (#'view/gizmo-axis context doc px py)))
        (is (nil? (#'view/gizmo-axis context doc (+ px 100.0) py)))))))

;; Long clip names must remain readable after list replacement, translation, and font changes.
(deftest animation-choice-fits-complete-name-test
  (ui/run-now
    (let [choice (#'view/choice "animation" ["idle"] "idle" (fn ignore-choice [_]))
          original (.getPrefWidth choice)
          long-name "tail_ik_secondary_motion_preview_very_long_animation_name"]
      (.add (.getItems choice) long-name)
      (is (> (.getMinWidth choice) (* 2.0 original)))
      (is (= (.getMinWidth choice) (.getPrefWidth choice)))
      (let [wide (.getPrefWidth choice)]
        (.setValue choice long-name)
        (is (= wide (.getPrefWidth choice)))
        (.setFont (.getButtonCell choice) (javafx.scene.text.Font/font 24.0))
        (is (> (.getPrefWidth choice) wide)))
      (.setValue choice nil)
      (.setItems choice (javafx.collections.FXCollections/observableArrayList (java.util.ArrayList. ^java.util.Collection ["a"])))
      (let [short-width (.getPrefWidth choice)]
        (.add (.getItems choice) long-name)
        (is (> (.getPrefWidth choice) short-width)))
      (.setConverter choice (proxy [javafx.util.StringConverter] []
                              (toString [value] (str "动画名称及预览版本：" value))
                              (fromString [value] value)))
      (is (> (.getMinWidth choice) original)))))

;; Constraint targets/order are setup data, while mix/bend remain editable in
;; animation mode. The hidden section must not leave a large empty property area.
(deftest animate-constraint-setup-controls-test
  (ui/run-now
    (let [doc (data/add-ik (rig) {:id "reach" :bones ["arm"] :target "other"})
          context (context doc "animate")]
      (swap! (:state context) assoc :selected-kind :ik :selected "reach")
      (let [panel (javafx.scene.layout.VBox.
                    (into-array javafx.scene.Node (#'view/object-inspector context doc)))
            section (.lookup panel "#skeletal-properties-property-constraint-setup-section")]
        (is (false? (.isVisible section)))
        (is (false? (.isManaged section)))
        (is (.isDisable section))
        (is (.isDisable (.lookup panel "#skeletal-ik-name")))
        (is (not (.isDisable (.lookup panel "#skeletal-ik-mix"))))
        (is (not (.isDisable (.lookup panel "#skeletal-ik-bend"))))))))

;; Setup callbacks can outlive their inspector after switching mode. Reject
;; them before evaluating mutations, including creation from an open menu.
(deftest stale-setup-action-in-animation-test
  (ui/run-now
    (let [doc (rig)
          context (assoc (context doc "animate") :status (javafx.scene.control.Label.))
          invoked (atom false)]
      (is (nil? (#'view/setup-change! context (fn unexpected-setup-edit [_] (reset! invoked true)))))
      (is (false? @invoked))
      (doseq [command [#'view/add-path! #'view/add-transform! #'view/add-physics!]]
        (is (nil? (command context))))
      (is (= doc (#'view/document context))))))

;; Non-rendered vertex attachments also support deform keys in Spine; do not
;; strand bounding boxes and clipping shapes without an animation editing tool.
(deftest animate-vertex-attachment-entry-test
  (ui/run-now
    (let [doc (data/update-slot (mesh-document false) "mesh" {:attachment {:type "boundingbox"}})
          context (slot-context doc "animate")
          _ (swap! (:state context) assoc :tool "move" :playing true)
          panel (javafx.scene.layout.VBox.
                  (into-array javafx.scene.Node (#'view/object-inspector context doc)))
          edit (.lookup panel "#skeletal-edit-deform")]
      (is (some? edit))
      (.fire ^javafx.scene.control.Button edit)
      (is (= {:tool "path" :mesh-mode "modify" :property "deform" :playing false}
             (select-keys @(:state context) [:tool :mesh-mode :property :playing])))
      (is (= doc (#'view/document context))))))

;; Picking a bone keeps the mesh target. Bound/unbound selections control list
;; highlight and actions; binding and removal preserve geometry and undo.
(deftest weight-selection-actions-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [doc (mesh-document true)
            node (g/make-node! ToolTransactionFixture :document doc)
            context (assoc (slot-context doc "setup") :resource-node node :status (javafx.scene.control.Label.))
            panel (fn panel [] (javafx.scene.layout.VBox.
                                 (into-array javafx.scene.Node (#'view/weights-inspector context (#'view/document context)))))
            bound (fn bound [] (mesh/binding-bones (get-in (g/node-value node :document) [:slots 0 :attachment])))
            root (panel)]
        (swap! (:state context) assoc :rigging-panel :weights :tool "weights")
        (is (false? (.isManaged (.lookup root "#skeletal-unbind-bone"))))
        (#'view/set-selection! context :bone "arm")
        (let [root (panel)]
          (is (= "mesh" (:selected @(:state context))))
          (is (= ["arm"] (vec (.getSelectedItems (.getSelectionModel ^javafx.scene.control.ListView (.lookup root "#skeletal-weight-bone-list"))))))
          (is (.isManaged (.lookup root "#skeletal-unbind-bone")))
          (is (false? (.isManaged (.lookup root "#skeletal-bind-selected-bones")))))
        (#'view/set-selection! context :bone "root")
        (let [root (panel)]
          (is (= "mesh" (:selected @(:state context))))
          (is (= [] (vec (.getSelectedItems (.getSelectionModel ^javafx.scene.control.ListView (.lookup root "#skeletal-weight-bone-list"))))))
          (is (false? (.isManaged (.lookup root "#skeletal-unbind-bone"))))
          (is (.isDisabled (.lookup root "#skeletal-direct-weight-percent")))
          (.fire ^javafx.scene.control.Button (.lookup root "#skeletal-bind-selected-bones")))
        (is (= ["arm" "other" "root"] (bound)))
        (is (= (get-in doc [:slots 0 :attachment :vertices])
               (get-in (g/node-value node :document) [:slots 0 :attachment :vertices])))
        (is (= (get-in doc [:slots 0 :attachment :weights 0])
               (subvec (get-in (g/node-value node :document) [:slots 0 :attachment :weights 0]) 0 2)))
        (let [root (panel)]
          (is (.isManaged (.lookup root "#skeletal-unbind-bone")))
          (.fire ^javafx.scene.control.Button (.lookup root "#skeletal-unbind-bone")))
        (is (= ["arm" "other"] (bound)))
        (g/undo! :undo/global)
        (is (= ["arm" "other" "root"] (bound)))
        (g/undo! :undo/global)
        (is (= doc (g/node-value node :document)))))))

;; The list consumes extra viewport height through all inspector containers,
;; and a shorter window still allows scrolling instead of clipping controls.
(deftest weight-list-fills-viewport-test
  (ui/run-now
    (let [doc (mesh-document true)
          context (slot-context doc "setup")
          inspector (javafx.scene.layout.VBox.)
          scroll (doto (javafx.scene.control.ScrollPane. inspector) (.setFitToWidth true) (.setFitToHeight true))
          root (javafx.scene.layout.VBox. (into-array javafx.scene.Node [scroll]))
          _ (javafx.scene.Scene. root 360.0 1000.0)]
      (swap! (:state context) assoc :rigging-panel :weights :tool "weights")
      (.addAll (.getChildren inspector) ^java.util.Collection (#'view/entity-inspector context doc))
      (javafx.scene.layout.VBox/setVgrow scroll javafx.scene.layout.Priority/ALWAYS)
      (.setMinHeight scroll 0.0)
      (.resize root 360.0 650.0)
      (.applyCss root) (.layout root)
      (let [list ^javafx.scene.control.ListView (.lookup root "#skeletal-weight-bone-list")
            short-height (.getHeight list)]
        (.resize root 360.0 1000.0) (.layout root)
        (is (> (.getHeight list) (+ short-height 300.0)))
        (is (> (.getHeight list) 500.0))))))

;; Canvas bone clicks must select the corresponding weight influence while
;; keeping the mesh target; picking an unbound bone must not edit the document.
(deftest weight-canvas-bone-selection-test
  (ui/run-now
    (let [doc (mesh-document true)
          canvas (javafx.scene.canvas.Canvas. 500.0 400.0)
          context (assoc (slot-context doc "setup") :canvas canvas)
          state (:state context)
          transforms (geometry/world-transforms (:bones doc))]
      (swap! state assoc :rigging-panel :weights :tool "weights" :zoom 2.0 :pan-x 200.0 :pan-y 200.0)
      (#'view/rigging-events! context)
      (doseq [[id bound] [["arm" #{"arm"}] ["root" #{}]]]
        (let [[x y] (#'view/screen context (geometry/transform-point (get transforms id) [0.0 0.0]))]
          (javafx.event.Event/fireEvent canvas
                                        (javafx.scene.input.MouseEvent. javafx.scene.input.MouseEvent/MOUSE_PRESSED
                                                                        x y x y javafx.scene.input.MouseButton/PRIMARY 1
                                                                        false false false false true false false false false false nil)))
        (is (= "mesh" (:selected @state)))
        (is (= [id] (:weight-picked-bones @state)))
        (is (= bound (:weight-bones @state)))
        (is (= doc (#'view/document context)))))))

;; The visible grid/restore controls form reversible graph transactions, keep
;; the image under the original bone, and sit in the same grouped tool area.
(deftest inspector-grid-image-roundtrip-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (let [image {:type "region" :path "/image.png" :width 100.0 :height 60.0
                   :x 3.0 :y 8.0 :rotation 20.0 :scale-x 0.8 :scale-y 0.8}
            doc (data/add-slot (rig) {:id "mesh" :bone "arm" :attachment image})
            node (g/make-node! ToolTransactionFixture :document doc)
            context (assoc (slot-context doc "setup") :resource-node node :status (javafx.scene.control.Label.))
            panel (fn panel [] (javafx.scene.layout.VBox.
                                 (into-array javafx.scene.Node (#'view/object-inspector context (#'view/document context)))))]
        (swap! (:state context) dissoc :draft)
        (let [root (panel)]
          (is (some? (.lookup root "#skeletal-properties-property-transform")))
          (is (some? (.lookup root "#skeletal-properties-property-mesh-tools")))
          (.fire ^javafx.scene.control.Button (.lookup root "#skeletal-make-mesh")))
        (let [meshed (g/node-value node :document)
              root (panel)]
          (is (= "mesh" (get-in meshed [:slots 0 :attachment :type])))
          (is (= 16 (quot (count (get-in meshed [:slots 0 :attachment :vertices])) 2)))
          (.fire ^javafx.scene.control.Button (.lookup root "#skeletal-restore-image"))
          (let [restored (g/node-value node :document)]
            (is (= "region" (get-in restored [:slots 0 :attachment :type])))
            (is (= "arm" (get-in restored [:slots 0 :bone])))
            (is (= (:bones doc) (:bones restored)))
            (g/undo! :undo/global)
            (is (= meshed (g/node-value node :document)))
            (g/undo! :undo/global)
            (is (= doc (g/node-value node :document)))
            (g/redo! :undo/global) (g/redo! :undo/global)
            (is (= restored (g/node-value node :document)))))))))

;; Empty primary clicks clear object/vertex selection without editing the asset;
;; additive clicks and right-button navigation retain the selected object.
(deftest empty-canvas-click-selection-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (ui/run-now
      (doseq [mode ["setup" "animate"]
              kind [:bone :slot]
              [button shift clear] [[javafx.scene.input.MouseButton/PRIMARY false true]
                                    [javafx.scene.input.MouseButton/PRIMARY true false]
                                    [javafx.scene.input.MouseButton/SECONDARY false false]]]
        (let [doc (mesh-document true)
              node (g/make-node! ToolTransactionFixture :document doc)
              canvas (javafx.scene.canvas.Canvas. 500.0 400.0)
              context (assoc (context doc mode) :resource-node node :canvas canvas
                             :workspace-cache (atom nil) :preview-cache (atom {})
                             :status (javafx.scene.control.Label.))
              state (:state context)
              id (if (= :bone kind) "arm" "mesh")
              history (:undo @g/*the-system*)]
          (swap! state #(-> % (dissoc :draft)
                            (assoc :tool "move" :zoom 1.0 :pan-x 0.0 :pan-y 400.0
                                   :show-images false :selected-kind kind :selected id
                                   :selected-bones (if (= :bone kind) #{"arm" "other"} #{})
                                   :selected-slots (if (= :slot kind) #{"mesh"} #{}) :vertices #{0 1})))
          (#'view/canvas-events! context)
          (doseq [type [javafx.scene.input.MouseEvent/MOUSE_PRESSED javafx.scene.input.MouseEvent/MOUSE_RELEASED]]
            (javafx.event.Event/fireEvent canvas
                                          (javafx.scene.input.MouseEvent. type 450.0 20.0 450.0 20.0 button 1
                                                                          shift false false false (= button javafx.scene.input.MouseButton/PRIMARY)
                                                                          false (= button javafx.scene.input.MouseButton/SECONDARY) false false false nil)))
          (if clear
            (do (is (nil? (:selected @state)))
                (is (nil? (:selected-kind @state)))
                (is (= #{} (:selected-bones @state) (:selected-slots @state) (:vertices @state))))
            (is (= id (:selected @state))))
          (is (= doc (g/node-value node :document)))
          (is (= history (:undo @g/*the-system*))))))))
