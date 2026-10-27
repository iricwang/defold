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

(ns editor.skeletal-animation-constraints
  "Independent affine inheritance and IK/Transform/Path constraint evaluation. World
  matrices use [a b c d tx ty]. Returned matrices are authoritative; :bones
  contains the applied local pose for subsequent constraints and authoring."
  (:require [editor.skeletal-animation-geometry :as geometry]
            [util.coll :as coll]))

(def inherit-modes #{"normal" "onlyTranslation" "noRotationOrReflection" "noScale" "noScaleOrReflection"})
(def transform-properties ["mix-rotate" "mix-x" "mix-y" "mix-scale-x" "mix-scale-y" "mix-shear-y"])

(defn- angle [x y] (Math/toDegrees (Math/atan2 (double y) (double x))))
(defn- radians [degrees] (Math/toRadians (double degrees)))
(defn- wrap-angle [degrees] (cond (> degrees 180.0) (- degrees 360.0) (< degrees -180.0) (+ degrees 360.0) :else degrees))
(defn- principal-angle [degrees] (- (mod (+ degrees 180.0) 360.0) 180.0))
(defn- determinant [[a b c d]] (- (* a d) (* b c)))
(defn- length [[x y]] (Math/hypot (double x) (double y)))
(defn- axes [[a b c d]] [[a b] [c d]])
(defn- vector-at [degrees magnitude]
  [(* magnitude (Math/cos (radians degrees))) (* magnitude (Math/sin (radians degrees)))])
(defn- rotation-matrix [degrees]
  (geometry/local-transform {:rotation degrees}))

(defn normalize-bone [bone]
  (let [bone (merge {:x 0.0 :y 0.0 :rotation 0.0 :scale-x 1.0 :scale-y 1.0 :shear-x 0.0 :shear-y 0.0 :inherit "normal"} bone)]
    (when-not (contains? inherit-modes (:inherit bone))
      (throw (ex-info "Unsupported bone inheritance mode." {:bone (:id bone) :inherit (:inherit bone)})))
    (geometry/local-transform bone)
    bone))

(defn- stretch-basis [[a b c d :as parent]]
  (let [sx (Math/hypot a b)]
    (if (> sx 1.0e-8)
      (let [heading (angle a b)
            sy (/ (abs (determinant parent)) sx)]
        (-> (rotation-matrix heading)
            (geometry/compose-transforms [sx 0.0 0.0 sy 0.0 0.0])
            (geometry/compose-transforms (rotation-matrix (- heading)))))
      ;; A collapsed axis has no rotation. Keep the remaining rank-one stretch
      ;; finite instead of propagating NaNs into weighted vertices or the GPU.
      (let [sy (Math/hypot c d)] [0.0 0.0 0.0 sy 0.0 0.0]))))

(defn- no-scale-basis [bone parent]
  (let [[x y] (geometry/transform-point (assoc parent 4 0.0 5 0.0) (vector-at (:rotation bone) 1.0))
        magnitude (Math/hypot x y)
        [x y] (if (> magnitude 1.0e-8) [(/ x magnitude) (/ y magnitude)] [0.0 0.0])
        handedness (if (and (= "noScale" (:inherit bone)) (neg? (determinant parent))) -1.0 1.0)]
    [x y (* (- y) handedness) (* x handedness) 0.0 0.0]))

(defn- bone-world [bone parent]
  (if-not parent
    (geometry/local-transform bone)
    (let [[x y] (geometry/transform-point parent [(:x bone) (:y bone)])
          linear (case (:inherit bone)
                   "normal" (geometry/compose-transforms parent (geometry/local-transform bone))
                   "onlyTranslation" (geometry/local-transform bone)
                   "noRotationOrReflection" (geometry/compose-transforms (stretch-basis parent) (geometry/local-transform bone))
                   (geometry/compose-transforms (no-scale-basis bone parent) (geometry/local-transform (assoc bone :rotation 0.0))))]
      (assoc linear 4 x 5 y))))

(defn- hierarchy [bones]
  (let [bones (mapv normalize-bone bones)
        by-id (into {} (map (juxt :id identity)) bones)]
    (when (or (not= (count bones) (count by-id)) (contains? by-id nil))
      (throw (ex-info "Bone IDs must be present and unique." {})))
    (loop [pending bones ordered [] seen #{}]
      (if (zero? (count pending))
        {:bones bones :ordered ordered :by-id by-id}
        (if-let [ready (coll/first-where #(or (nil? (:parent %)) (contains? seen (:parent %))) pending)]
          (recur (filterv #(not= (:id ready) (:id %)) pending) (conj ordered (:id ready)) (conj seen (:id ready)))
          (throw (ex-info "Bone hierarchy has a cycle or missing parent." {:bones (mapv :id pending)})))))))

(defn world-transforms [bones]
  (let [{:keys [ordered by-id]} (hierarchy bones)]
    (reduce (fn [result id]
              (let [bone (by-id id)] (assoc result id (bone-world bone (get result (:parent bone)))))) {} ordered)))

(defn pose [bones]
  (let [{:keys [bones ordered]} (hierarchy bones)]
    {:bones bones :transforms (world-transforms bones) :order ordered :warnings []}))

(defn normalize-transform [constraint]
  (let [constraint (merge {:order 0 :local false :relative false :rotation 0.0 :x 0.0 :y 0.0
                           :scale-x 0.0 :scale-y 0.0 :shear-y 0.0 :mix-rotate 1.0 :mix-x 1.0
                           :mix-y (get constraint :mix-x 1.0) :mix-scale-x 1.0
                           :mix-scale-y (get constraint :mix-scale-x 1.0) :mix-shear-y 1.0} constraint)]
    (when-not (and (boolean? (:local constraint)) (boolean? (:relative constraint))
                  (coll/every? geometry/finite-number? (mapv constraint [:order :rotation :x :y :scale-x :scale-y :shear-y :mix-rotate :mix-x :mix-y :mix-scale-x :mix-scale-y :mix-shear-y])))
      (throw (ex-info "Transform constraint flags and numeric values are invalid." {:constraint (:id constraint)})))
    constraint))

(defn- world-constraint [matrix target constraint]
  (let [{:keys [relative rotation x y scale-x scale-y shear-y mix-rotate mix-x mix-y mix-scale-x mix-scale-y mix-shear-y]} constraint
        [[ax ay :as axis-x] [bx by :as axis-y]] (axes matrix)
        [[tx ty :as target-x] [ux uy :as target-y]] (axes target)
        reflection (if (pos? (determinant target)) 1.0 -1.0)
        turn (* mix-rotate (wrap-angle (+ (angle tx ty) (* rotation reflection) (if relative 0.0 (- (angle ax ay))))))
        angle-x (+ (angle ax ay) turn)
        angle-y (+ (angle bx by) turn)
        magnitude-x (length axis-x) magnitude-y (length axis-y)
        scale (fn [value goal offset mix]
                (if relative (* value (+ 1.0 (* mix (+ goal offset -1.0))))
                    (if (zero? value) 0.0 (+ value (* mix (- (+ goal offset) value))))))
        magnitude-x (scale magnitude-x (length target-x) scale-x mix-scale-x)
        magnitude-y (scale magnitude-y (length target-y) scale-y mix-scale-y)
        angle-x (principal-angle (+ angle-x (if (neg? magnitude-x) 180.0 0.0)))
        angle-y (principal-angle (+ angle-y (if (neg? magnitude-y) 180.0 0.0)))
        magnitude-x (abs magnitude-x) magnitude-y (abs magnitude-y)
        target-shear (- (angle ux uy) (angle tx ty))
        shear (if relative (- (wrap-angle target-shear) 90.0)
                  (wrap-angle (- target-shear (- angle-y angle-x))))
        angle-y (+ angle-y (if (pos? mix-shear-y) (* mix-shear-y (+ shear (* reflection shear-y))) 0.0))
        [px py] (geometry/transform-point target [x y])
        [cx cy] (subvec matrix 4 6)
        position [(+ cx (* mix-x (if relative px (- px cx)))) (+ cy (* mix-y (if relative py (- py cy))))]]
    (into (into (vector-at angle-x magnitude-x) (vector-at angle-y magnitude-y)) position)))

(defn- local-constraint [bone target constraint]
  (reduce (fn [result [field mix-field offset-field]]
            (let [value (get bone field) goal (+ (get target field) (get constraint offset-field))
                  mix (get constraint mix-field)
                  scale (contains? #{:scale-x :scale-y} field)
                  result-value (cond
                                 (zero? mix) value
                                 (and scale (:relative constraint)) (* value (+ 1.0 (* mix (- goal 1.0))))
                                 scale (if (zero? value) 0.0 (/ (+ value (* mix (- goal value))) value))
                                 (:relative constraint) (+ value (* mix goal))
                                 :else (+ value (* mix (- goal value))))]
              (assoc result field result-value))) bone
          [[:rotation :mix-rotate :rotation] [:x :mix-x :x] [:y :mix-y :y]
           [:scale-x :mix-scale-x :scale-x] [:scale-y :mix-scale-y :scale-y] [:shear-y :mix-shear-y :shear-y]]))

(defn- applied-bone [bone matrix parent]
  (let [[x y] (if parent
                (or (geometry/inverse-transform-point parent (subvec matrix 4 6)) [(:x bone) (:y bone)])
                (subvec matrix 4 6))
        basis (when parent (case (:inherit bone)
                             "normal" (assoc parent 4 0.0 5 0.0)
                             "onlyTranslation" geometry/identity-transform
                             "noRotationOrReflection" (let [[[a b] _] (axes parent)
                                                             sx (Math/hypot a b)
                                                             sy (if (> sx 1.0e-8) (/ (abs (determinant parent)) sx) 0.0)]
                                                         (geometry/compose-transforms (rotation-matrix (angle a b)) [sx 0.0 0.0 sy 0.0 0.0]))
                             (no-scale-basis bone parent)))
        local (if-let [inverse (when basis (geometry/inverse-transform basis))]
                (geometry/compose-transforms
                  (if (and parent (= "noRotationOrReflection" (:inherit bone)))
                    (let [[_ b _ d] (or (geometry/inverse-transform (assoc parent 4 0.0 5 0.0)) inverse)]
                      (assoc inverse 1 b 3 d)) inverse) matrix) matrix)
        [a b c d] local
        sx (Math/hypot a b)
        det (determinant local)
        turn (angle a b)]
    (assoc bone :x x :y y :rotation turn :scale-x sx
           :scale-y (if (and parent (> sx 1.0e-8)) (/ det sx) (Math/hypot c d)) :shear-x 0.0
           :shear-y (if (> sx 1.0e-8) (* (if parent -1.0 1.0) (angle det (+ (* a c) (* b d)))) 0.0))))

(defn compensate-bone
  "Preserve the full affine transform, including shear and reflections."
  [bone world parent]
  (if (and parent (= "normal" (:inherit bone "normal")))
    (let [inverse (or (geometry/inverse-transform parent) (throw (ex-info "Cannot compensate a collapsed parent" {})))
          [a b c d x y] (geometry/compose-transforms inverse world)
          rotation (angle a b)]
      (assoc bone :x x :y y :rotation rotation :scale-x (Math/hypot a b) :scale-y (Math/hypot c d)
             :shear-x 0.0 :shear-y (- (angle c d) rotation 90.0)))
    (applied-bone bone world parent)))

(defn apply-transform-constraint [state constraint]
  (let [constraint (normalize-transform constraint)
        bones (:bones state)
        by-id (into {} (map (juxt :id identity)) bones)
        indices (into {} (map-indexed (fn [index bone] [(:id bone) index])) bones)
        target (get by-id (:target constraint))
        constrained (set (:bones constraint))]
    (when-not (and target (vector? (:bones constraint)) (pos? (count constrained))
                   (= (count constrained) (count (:bones constraint))) (coll/every? by-id constrained))
      (throw (ex-info "Transform constraint requires existing, unique bones and a target." {:constraint (:id constraint)})))
    (loop [ancestor (:target constraint)]
      (when ancestor
        (when (contains? constrained ancestor)
          (throw (ex-info "Transform target cannot descend from a constrained bone." {:constraint (:id constraint)})))
        (recur (:parent (get by-id ancestor)))))
    (if (coll/every? zero? (mapv constraint (mapv keyword transform-properties)))
      state
      (let [target-matrix (get-in state [:transforms (:target constraint)])
          updated (reduce (fn [result id]
                            (let [bone (get by-id id)
                                  parent (get-in result [:transforms (:parent bone)])
                                  changed-bone (if (:local constraint) (local-constraint bone target constraint) bone)
                                  matrix (if (:local constraint) (bone-world changed-bone parent)
                                             (world-constraint (get-in state [:transforms id]) target-matrix constraint))
                                  changed-bone (if (:local constraint) changed-bone (applied-bone bone matrix parent))]
                              (-> result (assoc-in [:bones (indices id)] changed-bone) (assoc-in [:transforms id] matrix)))) state (:bones constraint))
          applied (into {} (map (juxt :id identity)) (:bones updated))]
      (reduce (fn [result id]
                (let [bone (applied id)
                      changed (or (contains? constrained (:parent bone)) (contains? (:changed-descendants result) (:parent bone)))]
                  (if (or (contains? constrained id) (not changed)) result
                      (-> result
                          (assoc-in [:transforms id] (bone-world bone (get-in result [:transforms (:parent bone)])))
                          (update :changed-descendants (fnil conj #{}) id)))))
              (assoc updated :changed-descendants #{}) (:order state))))))

(defn evaluate [bones constraints]
  (reduce apply-transform-constraint (pose bones) (vec (sort-by #(get % :order 0) constraints))))

(defn normalize-ik [constraint]
  (let [constraint (merge {:mix 1.0 :softness 0.0 :bend-positive true :compress false :stretch false :uniform false :order 0} constraint)]
    (when-not (and (geometry/finite-number? (:mix constraint))
                   (geometry/finite-number? (:softness constraint))
                   (coll/every? boolean? (mapv constraint [:bend-positive :compress :stretch :uniform])))
      (throw (ex-info "IK mix, softness or flags are invalid." {:ik (:id constraint)})))
    constraint))

(defn- solve-one-bone [bone parent world target constraint]
  (let [mode (:inherit bone)
        [a b _ _] parent
        basis (if (= "noRotationOrReflection" mode)
                (let [sx (Math/hypot a b) sy (/ (abs (determinant parent)) (max sx 0.01))]
                  (assoc (geometry/compose-transforms (rotation-matrix (angle a b)) [sx 0.0 0.0 sy 0.0 0.0]) 4 (nth parent 4) 5 (nth parent 5))) parent)
        direction (if (= "onlyTranslation" mode)
                    (mapv - target (subvec world 4 6))
                    (if (<= (abs (determinant basis)) 0.0001) [0.0 0.0]
                        (mapv - (geometry/inverse-transform-point basis target) [(:x bone) (:y bone)])))
        desired (+ (apply angle direction) (if (= "noRotationOrReflection" mode) (angle a b) 0.0)
                   (if (neg? (:scale-x bone)) 180.0 0.0) (- (:shear-x bone)))
        distance (length (if (contains? #{"noScale" "noScaleOrReflection"} mode) (mapv - target (subvec world 4 6)) direction))
        reach (* (:length bone 0.0) (:scale-x bone))
        resize (and (> reach 0.0001) (or (and (:compress constraint) (< distance reach)) (and (:stretch constraint) (> distance reach))))
        factor (if resize (+ 1.0 (* (:mix constraint) (- (/ distance reach) 1.0))) 1.0)]
    (cond-> (-> bone
                (update :rotation + (* (:mix constraint) (wrap-angle (- desired (:rotation bone)))))
                (update :scale-x * factor))
      (:uniform constraint) (update :scale-y * factor))))

(defn- ellipse-solution
  "Intersect a radius with the endpoint ellipse [reach+a*cos(t), b*sin(t)].
  Select the bend-side intersection; unreachable targets use radial extrema."
  [reach a b radius bend]
  (let [qa (- (* a a) (* b b)) qb (* 2.0 reach a)
        qc (- (+ (* reach reach) (* b b)) (* radius radius))
        roots (if (< (abs qa) 1.0e-12)
                (if (< (abs qb) 1.0e-12) [] [(/ (- qc) qb)])
                (let [discriminant (- (* qb qb) (* 4.0 qa qc))]
                  (if (neg? discriminant) []
                      [(/ (+ (- qb) (Math/sqrt discriminant)) (* 2.0 qa))
                       (/ (- (- qb) (Math/sqrt discriminant)) (* 2.0 qa))])))
        valid (filterv #(<= -1.0 % 1.0) roots)
        endpoint (fn [cosine]
                   (let [theta (Math/acos (max -1.0 (min 1.0 cosine)))
                         x (+ reach (* a cosine)) y (* b (Math/sin theta) bend)]
                     {:theta (* theta bend) :x x :y y :distance (+ (* x x) (* y y))}))]
    (if (pos? (count valid))
      (endpoint (first (sort-by #(abs (+ reach (* a %))) valid)))
      (let [extreme (if (< (abs qa) 1.0e-12) 2.0 (/ (- (* reach a)) qa))
            candidates (mapv endpoint (cond-> [-1.0 1.0] (<= -1.0 extreme 1.0) (conj extreme)))
            nearest (first (sort-by #(abs (- (:distance %) (* radius radius))) candidates))]
        nearest))))

(defn- solve-two-bones [parent child grandparent parent-world target constraint]
  (let [sx (abs (:scale-x parent)) sy (abs (:scale-y parent))
        child-scale (abs (:scale-x child))
        uniform-scale (<= (abs (- sx sy)) 0.0001)
        child-y (if (or (not uniform-scale) (:stretch constraint)) 0.0 (:y child))
        target-local (or (geometry/inverse-transform-point grandparent target) [0.0 0.0])
        target-direction (mapv - target-local [(:x parent) (:y parent)])
        joint-world (geometry/transform-point parent-world [(:x child) child-y])
        joint-local (or (geometry/inverse-transform-point grandparent joint-world) [0.0 0.0])
        reach (length (mapv - joint-local [(:x parent) (:y parent)]))
        second-reach (* (:length child 0.0) child-scale)]
    (if (< reach 0.0001)
      [(solve-one-bone parent grandparent parent-world target (assoc constraint :compress false :uniform false)) (assoc child :y child-y :rotation 0.0)]
      (let [radius (length target-direction)
            maximum-reach (+ reach (* sx second-reach))
            softness (* (:softness constraint) sx (+ child-scale 1.0) 0.5)
            radius (cond
                     (or (zero? softness) (<= radius (- maximum-reach softness))) radius
                     (and (pos? softness) (>= radius (+ maximum-reach softness))) maximum-reach
                     :else (- maximum-reach (/ (Math/pow (- (+ maximum-reach softness) radius) 2.0) (* 4.0 softness))))
            bend (if (:bend-positive constraint) 1.0 -1.0)
            solution (ellipse-solution reach (* sx second-reach) (* sy second-reach) radius bend)
            handedness (if (neg? (* (:scale-x parent) (:scale-y parent))) -1.0 1.0)
            offset (* handedness (angle (:x child) child-y))
            parent-goal (+ (- (apply angle target-direction) (angle (:x solution) (:y solution)) offset)
                           (if (neg? (:scale-x parent)) 180.0 0.0))
            child-goal (+ (* handedness (- (+ (Math/toDegrees (:theta solution)) offset) (:shear-x child)))
                          (if (neg? (:scale-x child)) 180.0 0.0))
            factor (if (and uniform-scale (:stretch constraint) (> radius maximum-reach) (> maximum-reach 0.0001))
                     (+ 1.0 (* (:mix constraint) (- (/ radius maximum-reach) 1.0))) 1.0)
            parent (cond-> (-> parent
                                (update :rotation + (* (:mix constraint) (wrap-angle (- parent-goal (:rotation parent)))))
                                (update :scale-x * factor)
                                (assoc :shear-x 0.0 :shear-y 0.0))
                     (:uniform constraint) (update :scale-y * factor))
            child (-> child (assoc :y child-y)
                      (update :rotation + (* (:mix constraint) (wrap-angle (- child-goal (:rotation child))))))]
        [parent child]))))

(defn apply-ik-constraint
  "Apply one-/two-bone IK to the existing constrained pose. Stretch, compression,
  uniform scaling and softened reach are solved in the chain parent's space."
  [state constraint]
  (let [constraint (normalize-ik constraint)
        by-id (into {} (map (juxt :id identity)) (:bones state))
        chain (mapv by-id (:bones constraint))
        target (get-in state [:transforms (:target constraint)])]
    (when-not (and (<= 1 (count chain) 2) (coll/every? identity chain) target
                   (:parent (first chain))
                   (or (= 1 (count chain))
                       (and (= (:id (first chain)) (:parent (second chain)))
                            (coll/every? #(= "normal" (:inherit %)) chain))))
      (throw (ex-info "IK requires a parented bone, or a normal-inheritance parent-child pair, and a target." {:ik (:id constraint)})))
    (if (zero? (:mix constraint)) state
        (let [parent (first chain)
              grandparent (get-in state [:transforms (:parent parent)])
              world (get-in state [:transforms (:id parent)])
              target (subvec target 4 6)
              solved (if (= 1 (count chain)) [(solve-one-bone parent grandparent world target constraint)]
                         (solve-two-bones parent (second chain) grandparent world target constraint))
              solved-map (into {} (map (juxt :id identity)) solved)
              bones (mapv #(get solved-map (:id %) %) (:bones state))
              by-id (into {} (map (juxt :id identity)) bones)]
          (reduce (fn [result id]
                    (let [bone (by-id id)]
                      (if (or (contains? solved-map id) (contains? (:changed-descendants result) (:parent bone)))
                        (-> result (assoc-in [:transforms id] (bone-world bone (get-in result [:transforms (:parent bone)])))
                            (update :changed-descendants conj id)) result)))
                  (assoc state :bones bones :changed-descendants #{}) (:order state))))))

(def path-properties ["position" "spacing" "mix-rotate" "mix-x" "mix-y"])

(defn normalize-path [constraint]
  (let [constraint (merge {:order 0 :position-mode "percent" :spacing-mode "length" :rotate-mode "tangent"
                           :rotation 0.0 :position 0.0 :spacing 0.0 :mix-rotate 1.0 :mix-x 1.0
                           :mix-y (get constraint :mix-x 1.0)} constraint)]
    (when-not (and (contains? #{"fixed" "percent"} (:position-mode constraint))
                   (contains? #{"length" "fixed" "percent" "proportional"} (:spacing-mode constraint))
                   (contains? #{"tangent" "chain" "chainScale"} (:rotate-mode constraint))
                   (coll/every? geometry/finite-number? (mapv constraint [:rotation :position :spacing :mix-rotate :mix-x :mix-y])))
      (throw (ex-info "Path modes and numeric values are invalid." {:path (:id constraint)})))
    constraint))

(defn- bezier-point [points t]
  (let [s (- 1.0 t) factors [(* s s s) (* 3.0 s s t) (* 3.0 s t t) (* t t t)]]
    (reduce (fn [result [point factor]] (mapv #(+ %1 (* %2 factor)) result point)) [0.0 0.0] (mapv vector points factors))))

(defn- bezier-tangent [[p0 p1 p2 p3] t]
  (let [s (- 1.0 t)]
    (mapv (fn [a b c d] (+ (* s s (- b a)) (* 2.0 s t (- c b)) (* t t (- d c)))) p0 p1 p2 p3)))

(defn path-curves
  "World cubic curves of a path attachment, with optional per-influence FFD.
  Spine path vertices contain the preceding/following control points; open
  paths skip these endpoints, and closed paths wrap them into the final curve."
  [attachment slot-transform transforms deform]
  (let [points (into [] (comp (partition-all 2) (map vec)) (geometry/mesh-world-vertices attachment slot-transform transforms deform))
        size (count points)
        closed (:closed attachment)
        count-curves (- (quot size 3) (if closed 0 1))]
    (when-not (and (zero? (mod size 3)) (pos? count-curves))
      (throw (ex-info "A path needs groups of three points and at least one cubic curve." {})))
    (mapv (fn [index] (mapv #(nth points (mod (+ 1 (* index 3) %) size)) (range 4))) (range count-curves))))

(defn- cumulative-lengths [curve divisions]
  (:lengths (reduce (fn [{:keys [point distance lengths]} index]
                     (let [next-point (bezier-point curve (/ (double index) divisions))
                           distance (+ distance (length (mapv - next-point point)))]
                       {:point next-point :distance distance :lengths (conj lengths distance)}))
                   {:point (first curve) :distance 0.0 :lengths [0.0]} (range 1 (inc divisions)))))

(defn path-lengths
  "Cumulative setup lengths suitable for exporting non-constant-speed paths."
  [attachment slot-transform transforms]
  (let [curves (path-curves attachment slot-transform transforms [])]
    (subvec (reduce (fn [result curve] (conj result (+ (peek result) (peek (cumulative-lengths curve 10))))) [0.0] curves) 1)))

(defn- path-sampler [curves attachment]
  (let [constant-speed (get attachment :constant-speed true)
        segments (mapv #(cumulative-lengths % 10) curves)
        lengths (if constant-speed
                  (reduce (fn [result curve] (conj result (+ (peek result) (peek (cumulative-lengths curve 4))))) [0.0] curves)
                  (into [0.0] (take (count curves)) (:lengths attachment)))
        total (peek lengths)
        ;; Reference sampling advances its segment cursor through the chain,
        ;; including negative spacing. Keeping that traversal order reproduces
        ;; exported runtime poses rather than silently using a different curve.
        cursor (volatile! {:curve 0 :segment 0})
        sample (fn [distance]
                 (let [closed (:closed attachment)
                       distance (if (and closed (pos? total)) (mod distance total) distance)
                       before (neg? distance)
                       after (> distance total)
                       index (cond before 0 after (dec (count curves))
                                   :else (or (coll/first-where #(<= distance (nth lengths (inc %))) (range (if closed 0 (:curve @cursor)) (count curves))) (dec (count curves))))
                       curve (nth curves index)
                       previous @cursor
                       _ (when-not (or before after) (vswap! cursor assoc :curve index))
                       curve-length (- (nth lengths (inc index)) (nth lengths index))
                       fraction (if (zero? curve-length) 0.0 (/ (- distance (nth lengths index)) curve-length))
                       t (cond before 0.0 after 1.0 (not constant-speed) fraction
                               :else (let [arcs (nth segments index)
                                           target (* fraction (peek arcs))
                                           segment (or (coll/first-where #(<= target (nth arcs (inc %))) (range (if (= index (:curve previous)) (:segment previous) 0) 10)) 9)
                                           _ (vswap! cursor assoc :segment segment)
                                           a (nth arcs segment) b (nth arcs (inc segment))]
                                       (/ (+ segment (if (= a b) 0.0 (/ (- target a) (- b a)))) 10.0)))
                       point (bezier-point curve t)
                       tangent (bezier-tangent curve (if (< t 0.001) 0.0 t))
                       tangent (if (> (length tangent) 1.0e-10) tangent (mapv - (peek curve) (first curve)))
                       heading (apply angle tangent)
                       extra (cond before distance after (- distance total) :else 0.0)]
                   {:point (mapv + point (vector-at heading extra)) :angle heading}))]
    {:length total :sample sample}))

(defn apply-path-constraint
  "Constrain bones to the target slot's visible cubic path. Weighted paths,
  deformation, all spacing/rotation modes and shared constraint order are used."
  [state constraint slot deform]
  (let [constraint (normalize-path constraint)
        attachment (:attachment slot)]
    (if (or (not= "path" (:type attachment)) (coll/every? zero? (mapv constraint [:mix-rotate :mix-x :mix-y])))
      state
      (let [by-id (into {} (map (juxt :id identity)) (:bones state))
            chain (mapv by-id (:bones constraint))
            _ (when-not (and (pos? (count chain)) (coll/every? identity chain))
                (throw (ex-info "Path constraint bones must exist." {:path (:id constraint)})))
            slot-world (get-in state [:transforms (:bone slot)])
            curves (path-curves attachment slot-world (:transforms state) deform)
            {:keys [sample length]} (path-sampler curves attachment)
            tangent (= "tangent" (:rotate-mode constraint))
            scale (= "chainScale" (:rotate-mode constraint))
            count-spaces (if tangent (count chain) (inc (count chain)))
            world-lengths (mapv (fn [bone] (let [[a b] (get-in state [:transforms (:id bone)])] (* (:length bone 0.0) (Math/hypot a b)))) chain)
            space-bones (subvec chain 0 (dec count-spaces))
            proportional-total (reduce + 0.0 (subvec world-lengths 0 (dec count-spaces)))
            spaces (mapv (fn [bone world-length]
                           (case (:spacing-mode constraint)
                             "percent" (* (:spacing constraint) length)
                             "proportional" (if (pos? proportional-total) (* world-length (/ (* (:spacing constraint) length) proportional-total)) 0.0)
                             (if (< (:length bone 0.0) 1.0e-5) (:spacing constraint)
                                 (* (/ world-length (:length bone)) (+ (:spacing constraint) (if (= "length" (:spacing-mode constraint)) (:length bone) 0.0))))))
                         space-bones (subvec world-lengths 0 (dec count-spaces)))
            position (* (:position constraint) (if (= "percent" (:position-mode constraint)) length 1.0))
            positions (mapv sample (reduce (fn [result spacing] (conj result (+ (peek result) spacing))) [position] spaces))
            rotation (* (:rotation constraint) (if (neg? (determinant slot-world)) -1.0 1.0))
            indices (into {} (map-indexed (fn [index bone] [(:id bone) index])) (:bones state))
            constrained (set (:bones constraint))
            updated (:state
                      (reduce (fn [{:keys [state point]} index]
                                (let [bone (nth chain index)
                                      id (:id bone)
                                      matrix (get-in state [:transforms id])
                                      [a b c d x y] matrix
                                      next-sample (get positions (inc index))
                                      next-point (:point next-sample)
                                      direction (when next-point (mapv - next-point point))
                                      factor (if (and scale (> (nth world-lengths index) 1.0e-5))
                                               (+ 1.0 (* (:mix-rotate constraint) (- (/ (Math/hypot (double (first direction)) (double (second direction))) (nth world-lengths index)) 1.0))) 1.0)
                                      a (* a factor) b (* b factor)
                                      heading (cond tangent (:angle (nth positions index))
                                                    (and (get spaces index) (zero? (get spaces index))) (:angle next-sample)
                                                    :else (apply angle direction))
                                      goal-turn (principal-angle (+ heading rotation (- (angle a b))))
                                      turn (* (:mix-rotate constraint) goal-turn)
                                      rotated (geometry/compose-transforms (rotation-matrix turn) [a b c d 0.0 0.0])
                                      translated [(+ x (* (:mix-x constraint) (- (first point) x))) (+ y (* (:mix-y constraint) (- (second point) y)))]
                                      matrix (into (subvec rotated 0 4) translated)
                                      next-point (if (and next-point (not tangent) (not scale) (zero? rotation))
                                                   (let [tip (mapv #(* (:length bone 0.0) %) (subvec (geometry/compose-transforms (rotation-matrix goal-turn) [a b c d 0.0 0.0]) 0 2))]
                                                     (mapv #(+ %1 (* (:mix-rotate constraint) (- %2 %3))) next-point tip direction)) next-point)
                                      bone (applied-bone bone matrix (get-in state [:transforms (:parent bone)]))]
                                  {:state (-> state (assoc-in [:transforms id] matrix) (assoc-in [:bones (indices id)] bone))
                                   :point next-point}))
                              {:state state :point (:point (first positions))} (range (count chain))))
            applied (into {} (map (juxt :id identity)) (:bones updated))]
        (reduce (fn [result id]
                  (let [bone (applied id)
                        changed (or (contains? constrained (:parent bone)) (contains? (:changed-descendants result) (:parent bone)))]
                    (if (or (contains? constrained id) (not changed)) result
                        (-> result (assoc-in [:transforms id] (bone-world bone (get-in result [:transforms (:parent bone)])))
                            (update :changed-descendants conj id)))))
                (assoc updated :changed-descendants #{}) (:order state))))))

(defn apply-world-transform
  "Replace one solved world matrix and update its applied pose and descendants.
  Used by stateful physics while preserving the common constraint order."
  [state bone-id matrix]
  (let [index (or (reduce-kv (fn [_ index bone] (when (= bone-id (:id bone)) (reduced index))) nil (:bones state))
                  (throw (ex-info "Constrained bone does not exist." {:bone bone-id})))
        bone (get-in state [:bones index])]
    (when-not (and (vector? matrix) (= 6 (count matrix)) (coll/every? geometry/finite-number? matrix))
      (throw (ex-info "A solved bone matrix must have six finite values." {:bone bone-id})))
    (let [state (-> state (assoc-in [:transforms bone-id] matrix)
                    (assoc-in [:bones index] (applied-bone bone matrix (get-in state [:transforms (:parent bone)]))))
          by-id (into {} (map (juxt :id identity)) (:bones state))]
      (reduce (fn [result id]
                (let [bone (by-id id)]
                  (if-not (contains? (:changed-descendants result) (:parent bone)) result
                      (-> result (assoc-in [:transforms id] (bone-world bone (get-in result [:transforms (:parent bone)])))
                          (update :changed-descendants conj id)))))
              (assoc state :changed-descendants #{bone-id}) (:order state)))))
