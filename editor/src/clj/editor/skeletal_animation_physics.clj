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

(ns editor.skeletal-animation-physics
  "Independent fixed-step spring dynamics for Spine-style physics constraints.
  Simulation state is immutable and belongs to an editor preview instance."
  (:require [editor.skeletal-animation-geometry :as geometry]
            [util.coll :as coll]))

(def animated-properties ["inertia" "strength" "damping" "mass" "wind" "gravity" "mix"])

(defn normalize-constraint [constraint]
  (let [constraint (merge {:order 0 :x 0.0 :y 0.0 :rotate 0.0 :scale-x 0.0 :shear-x 0.0
                           :limit 5000.0 :fps 60.0 :inertia 1.0 :strength 100.0 :damping 1.0
                           :mass 1.0 :wind 0.0 :gravity 0.0 :mix 1.0} constraint)]
    (when-not (and (coll/every? geometry/finite-number? (mapv constraint [:x :y :rotate :scale-x :shear-x :limit :fps :inertia :strength :damping :mass :wind :gravity :mix]))
                   (coll/every? #(<= 0.0 (get constraint %) 1.0) [:x :y :rotate :scale-x :shear-x])
                   (pos? (:fps constraint)) (<= (:fps constraint) 1000.0) (pos? (:mass constraint))
                   (not (neg? (:limit constraint))) (not (neg? (:strength constraint)))
                   (<= 0.0 (:damping constraint) 1.0))
      (throw (ex-info "Physics values must be finite with positive mass/FPS, nonnegative strength/limit and setup influence/damping between zero and one." {:physics (:id constraint)})))
    constraint))

(defn- wrap-angle [radians]
  (- (mod (+ radians Math/PI) (* 2.0 Math/PI)) Math/PI))

(defn- rotate-vector [[x y] angle]
  (let [cos (Math/cos angle) sin (Math/sin angle)]
    [(- (* x cos) (* y sin)) (+ (* x sin) (* y cos))]))

(defn- initial-state [[a b _ _ x y] length time]
  {:time time :remaining 0.0 :unconstrained [x y] :center [x y] :tip [(* a length) (* b length)]
   :x-offset 0.0 :x-velocity 0.0 :y-offset 0.0 :y-velocity 0.0
   :rotate-offset 0.0 :rotate-velocity 0.0 :scale-offset 0.0 :scale-velocity 0.0})

(defn- spring-step [offset velocity force constraint step]
  (let [velocity (+ velocity (* step (/ (- force (* offset (:strength constraint))) (:mass constraint))))]
    [(+ offset (* velocity step)) (* velocity (Math/pow (:damping constraint) (* 60.0 step)))]))

(defn- integrate [state constraint steps dt fields force]
  (reduce (fn [before _]
            (reduce (fn [state [offset-field velocity-field axis]]
                      (let [[offset velocity] (spring-step (offset-field state) (velocity-field state) (force before axis) constraint dt)]
                        (assoc state offset-field offset velocity-field velocity))) before fields)) state (range steps)))

(defn- apply-offsets [matrix state constraint]
  (let [[a b c d x y] matrix
        mix (:mix constraint)
        angle (* (:rotate-offset state) mix)
        [a b] (rotate-vector [a b] (* angle (+ (:rotate constraint) (:shear-x constraint))))
        [c d] (rotate-vector [c d] (* angle (:rotate constraint)))
        scale (+ 1.0 (* (:scale-offset state) mix (:scale-x constraint)))]
    [(* a scale) (* b scale) c d (+ x (* (:x-offset state) mix (:x constraint))) (+ y (* (:y-offset state) mix (:y constraint)))]))

(defn step
  "Advance a single constraint. Input matrix is its unconstrained world matrix
  at this point in constraint order. :mode is :update, :reset, :reset-event, :pose or :none;
  nil state initializes at the requested time, and rewinding resets safely.
  :pose reapplies the cached offsets without advancing simulation time."
  [constraint simulation-state {:keys [matrix length time mode reference-scale]
                                :or {length 0.0 mode :update reference-scale 100.0}}]
  (let [constraint (normalize-constraint constraint)
        simulation-state (if (= :reset-event mode)
                           (assoc (initial-state matrix length (get simulation-state :time time)) :reset-pending true)
                           simulation-state)]
    (when-not (and (vector? matrix) (= 6 (count matrix)) (coll/every? geometry/finite-number? matrix)
                   (geometry/finite-number? length) (not (neg? length))
                   (geometry/finite-number? time) (not (neg? time))
                   (geometry/finite-number? reference-scale) (pos? reference-scale)
                   (contains? #{:update :reset :reset-event :pose :none} mode))
      (throw (ex-info "Physics sampling requires a finite matrix, nonnegative time/length and a positive reference scale." {})))
    (cond
      (or (= :none mode) (zero? (:mix constraint))) {:matrix matrix :state simulation-state}
      (or (nil? simulation-state) (= :reset mode) (< time (:time simulation-state)))
      {:matrix matrix :state (initial-state matrix length time)}
      (:reset-pending simulation-state)
      {:matrix matrix :state (assoc (initial-state matrix length time) :remaining (max 0.0 (- time (:time simulation-state))))}
      (= :pose mode) {:matrix (apply-offsets matrix simulation-state constraint) :state simulation-state}
      :else
      (let [[a b _ _ x y] matrix
            delta (- time (:time simulation-state))
            dt (/ 1.0 (:fps constraint))
            remaining (+ delta (:remaining simulation-state))
            [steps remaining] (loop [steps 0 remaining remaining]
                                (if (or (< remaining dt) (> steps 100000)) [steps remaining]
                                    (recur (inc steps) (- remaining dt))))
            _ (when (> steps 100000) (throw (ex-info "Physics time jump is too large. Reset or use deterministic sampling." {:steps steps})))
            clamp #(max (- (* delta (:limit constraint))) (min (* delta (:limit constraint)) %))
            inertia (:inertia constraint)
            [ux uy] (:unconstrained simulation-state)
            translate-fields (cond-> [] (not (zero? (:x constraint))) (conj [:x-offset :x-velocity :x])
                                  (not (zero? (:y constraint))) (conj [:y-offset :y-velocity :y]))
            state (cond-> simulation-state
                    (not (zero? (:x constraint))) (-> (update :x-offset + (clamp (* inertia (- ux x)))) (assoc-in [:unconstrained 0] x))
                    (not (zero? (:y constraint))) (-> (update :y-offset + (clamp (* inertia (- uy y)))) (assoc-in [:unconstrained 1] y)))
            state (integrate state constraint steps dt translate-fields
                             (fn [_ axis] (* reference-scale (if (= :x axis) (:wind constraint) (- (:gravity constraint))))))
            center [(+ x (* (:x-offset state) (:x constraint) (:mix constraint))) (+ y (* (:y-offset state) (:y constraint) (:mix constraint)))]
            movement (mapv #(clamp (- %1 %2)) (:center simulation-state) center)
            axis-length (Math/hypot a b)
            reach (* length axis-length)
            angle (Math/atan2 b a)
            rotate-active (and (pos? reach) (or (not (zero? (:rotate constraint))) (not (zero? (:shear-x constraint)))))
            scale-active (and (pos? reach) (not (zero? (:scale-x constraint))))
            tip (mapv + (:tip simulation-state) movement)
            turn (wrap-angle (- (Math/atan2 (second tip) (first tip)) angle
                                (* (:rotate-offset state) (:mix constraint) (+ (:rotate constraint) (:shear-x constraint)))))
            state (cond-> state rotate-active (update :rotate-offset + (* turn inertia)))
            scale-angle (+ angle (* (:rotate-offset state) (:mix constraint) (+ (:rotate constraint) (:shear-x constraint))))
            state (cond-> state scale-active (update :scale-offset + (* inertia (/ (+ (* (first movement) (Math/cos scale-angle)) (* (second movement) (Math/sin scale-angle))) reach))))
            fields (cond-> [] rotate-active (conj [:rotate-offset :rotate-velocity :rotate]) scale-active (conj [:scale-offset :scale-velocity :scale]))
            state (integrate state constraint steps dt fields
                             (fn [state field]
                               (let [heading (+ angle (* (:rotate-offset state) (:mix constraint) (+ (:rotate constraint) (:shear-x constraint)))) cos (Math/cos heading) sin (Math/sin heading)]
                                 (if (= :rotate field)
                                   (* (/ length reference-scale) (- (* (- (:wind constraint)) sin) (* (:gravity constraint) cos)))
                                   (- (* (:wind constraint) cos) (* (:gravity constraint) sin))))))
            result (apply-offsets matrix state constraint)
            state (assoc state :time time :remaining remaining :center center
                               :tip [(* (first result) length) (* (second result) length)])]
        (when-not (and (coll/every? geometry/finite-number? result)
                       (coll/every? geometry/finite-number? (mapv state [:x-offset :x-velocity :y-offset :y-velocity :rotate-offset :rotate-velocity :scale-offset :scale-velocity])))
          (throw (ex-info "Physics simulation became unstable. Reduce strength/forces or increase mass/FPS, then reset." {:physics (:id constraint) :time time})))
        {:matrix result :state state}))))

(defn deterministic-sample
  "Replay from time zero using a pure (sample-input time) callback. The callback
  returns the current world matrix, bone length and optional constraint values.
  Always restarting makes seeking and reverse playback independent of history."
  [constraint sample-input time]
  (let [constraint (normalize-constraint constraint)]
    (when-not (and (geometry/finite-number? time) (not (neg? time)))
      (throw (ex-info "Physics sample time must be finite and nonnegative." {:time time})))
    (let [steps (long (Math/ceil (* time (:fps constraint))))]
      (when (> steps 100000) (throw (ex-info "Physics replay exceeds the supported step count." {:steps steps})))
      (reduce (fn [result index]
                (let [t (min time (/ (double index) (:fps constraint)))
                      input (sample-input t)]
                  (step (merge constraint (:constraint input)) (:state result) (assoc input :time t :mode (if (zero? index) :reset :update)))))
              nil (range (inc steps))))))
