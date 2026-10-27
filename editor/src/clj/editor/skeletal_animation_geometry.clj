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

(ns editor.skeletal-animation-geometry
  "Independent 2D authoring geometry. Angles are degrees; affine matrices are
  [a b c d tx ty]. Mesh coordinates and deforms are flat XY vectors."
  (:require [util.coll :as coll]))

(def identity-transform [1.0 0.0 0.0 1.0 0.0 0.0])

(defn finite-number? [value]
  (and (number? value) (Double/isFinite (double value))))

(defn- validate-numbers! [values label]
  (when-not (coll/every? finite-number? values)
    (throw (ex-info (str label " must contain finite numbers") {:value values}))))

(defn transform-point [[a b c d tx ty] [x y]]
  [(+ (* a x) (* c y) tx)
   (+ (* b x) (* d y) ty)])

(defn compose-transforms [[a b c d tx ty] [e f g h ux uy]]
  [(+ (* a e) (* c f)) (+ (* b e) (* d f))
   (+ (* a g) (* c h)) (+ (* b g) (* d h))
   (+ (* a ux) (* c uy) tx) (+ (* b ux) (* d uy) ty)])

(defn inverse-transform
  "Returns nil for singular transforms, including a collapsed bone scale."
  [[a b c d tx ty]]
  (let [determinant (- (* a d) (* b c))]
    (when (> (abs (double determinant)) 1.0e-12)
      [(/ d determinant) (/ (- b) determinant)
       (/ (- c) determinant) (/ a determinant)
       (/ (- (* c ty) (* d tx)) determinant)
       (/ (- (* b tx) (* a ty)) determinant)])))

(defn inverse-transform-point [transform point]
  (when-let [inverse (inverse-transform transform)]
    (transform-point inverse point)))

(defn local-transform [{:keys [x y rotation scale-x scale-y shear-x shear-y]
                        :or {x 0.0 y 0.0 rotation 0.0 scale-x 1.0 scale-y 1.0 shear-x 0.0 shear-y 0.0}}]
  (validate-numbers! [x y rotation scale-x scale-y shear-x shear-y] "Bone transform")
  (let [rx (Math/toRadians (double (+ rotation shear-x)))
        ry (Math/toRadians (double (+ rotation 90.0 shear-y)))]
    [(* scale-x (Math/cos rx)) (* scale-x (Math/sin rx))
     (* scale-y (Math/cos ry)) (* scale-y (Math/sin ry))
     (double x) (double y)]))

(defn- world-transform-impl [bones-by-id result visiting id]
  (if (contains? result id)
    result
    (let [bone (get bones-by-id id)]
      (when-not bone
        (throw (ex-info "Bone parent does not exist" {:bone id})))
      (when (contains? visiting id)
        (throw (ex-info "Bone hierarchy contains a cycle" {:bone id})))
      (let [parent (:parent bone)
            result (if parent
                     (world-transform-impl bones-by-id result (conj visiting id) parent)
                     result)
            transform (compose-transforms (get result parent identity-transform) (local-transform bone))]
        (assoc result id transform)))))

(defn world-transforms
  "Computes transforms independent of source order, rejecting duplicate IDs,
  missing parents and cycles before a malformed rig can reach the viewport."
  [bones]
  (let [bones-by-id (into {} (map (juxt :id identity)) bones)]
    (when (or (not= (count bones) (count bones-by-id)) (contains? bones-by-id nil))
      (throw (ex-info "Bone IDs must be present and unique" {})))
    (reduce (fn [result bone]
              (world-transform-impl bones-by-id result #{} (:id bone)))
            {} bones)))

(defn normalize-influences
  "Normalizes nonnegative weights, merging repeated bones without changing
  their weighted bind position. Zero-weight entries are discarded."
  [influences]
  (doseq [{:keys [bone weight x y]} influences]
    (when-not bone
      (throw (ex-info "A mesh influence must reference a bone" {})))
    (validate-numbers! [weight x y] "Mesh influence")
    (when (neg? weight)
      (throw (ex-info "Mesh weights cannot be negative" {:weight weight}))))
  (let [merged (reduce (fn [result {:keys [bone weight x y]}]
                         (if (zero? weight)
                           result
                           (update result bone
                                   (fn [previous]
                                     (let [previous-weight (get previous :weight 0.0)
                                           total (+ previous-weight weight)]
                                       {:bone bone :weight total
                                        :x (/ (+ (* previous-weight (get previous :x 0.0)) (* weight x)) total)
                                        :y (/ (+ (* previous-weight (get previous :y 0.0)) (* weight y)) total)})))))
                       (array-map) influences)
        total (reduce + 0.0 (into [] (map :weight) (coll/vals merged)))]
    (mapv #(update % :weight / total) (coll/vals merged))))

(defn mesh-world-vertices
  "Returns flat world XY coordinates. Weighted deforms use one XY offset per
  influence, as in Spine 4.2. A per-vertex deformation is also accepted for
  authoring tools and is applied to every influence of that vertex."
  [{:keys [vertices weights]} slot-transform transforms deform]
  (when (odd? (count vertices))
    (throw (ex-info "Mesh vertices require XY pairs" {})))
  (validate-numbers! vertices "Mesh vertices")
  (validate-numbers! deform "Mesh deform")
  (let [vertex-count (quot (count vertices) 2)
        weighted (pos? (count weights))
        influence-count (reduce + 0 (mapv count weights))
        per-influence (= (count deform) (* 2 influence-count))]
    (when (and weighted (not= vertex-count (count weights)))
      (throw (ex-info "Every mesh vertex must have an influence list" {})))
    (when-not (contains? (set [0 (* 2 vertex-count) (* 2 influence-count)]) (count deform))
      (throw (ex-info "Deform size must match mesh vertices or influences" {})))
    (if weighted
      (:vertices
        (reduce (fn [{:keys [vertices offset]} [index influences]]
                  (let [total (reduce + 0.0 (mapv :weight influences))]
                    (doseq [{:keys [weight x y]} influences]
                      (validate-numbers! [weight x y] "Mesh influence")
                      (when (neg? weight)
                        (throw (ex-info "Mesh weights cannot be negative" {}))))
                    (when-not (pos? total)
                      (throw (ex-info "Weighted vertices need a positive influence" {:vertex index})))
                    (let [[x y] (reduce (fn [[x y] [influence-index {:keys [bone weight] :as influence}]]
                                          (let [transform (get transforms bone)
                                                deform-offset (if per-influence (+ offset (* 2 influence-index)) (* 2 index))]
                                            (when-not transform
                                              (throw (ex-info "Mesh references a missing bone" {:bone bone})))
                                            (let [[px py] (transform-point transform
                                                                          [(+ (:x influence) (get deform deform-offset 0.0))
                                                                           (+ (:y influence) (get deform (inc deform-offset) 0.0))])]
                                              [(+ x (* (/ weight total) px)) (+ y (* (/ weight total) py))])))
                                        [0.0 0.0] (eduction (map-indexed vector) influences))]
                      {:vertices (conj vertices x y)
                       :offset (+ offset (* 2 (count influences)))})))
                {:vertices [] :offset 0} (eduction (map-indexed vector) weights)))
      (let [transform slot-transform]
        (when-not transform
          (throw (ex-info "Mesh slot references a missing bone" {})))
        (into [] (mapcat (fn [index]
                          (transform-point transform
                                           [(+ (vertices index) (get deform index 0.0))
                                            (+ (vertices (inc index)) (get deform (inc index) 0.0))])))
              (range 0 (count vertices) 2))))))

(defn region-to-mesh
  "Creates a regular region grid with counterclockwise triangles. Columns and
  rows count cells, so a 1×1 grid contains four vertices and two triangles."
  [{:keys [width height] :as region} columns rows]
  (validate-numbers! [width height] "Region size")
  (when-not (and (pos? width) (pos? height)
                 (integer? columns) (<= 1 columns 128)
                 (integer? rows) (<= 1 rows 128))
    (throw (ex-info "Grid dimensions must be between 1 and 128 with positive region size" {})))
  (let [transform (local-transform region)
        stride (inc columns)
        vertex-count (* stride (inc rows))]
    (merge (dissoc region :x :y :rotation :scale-x :scale-y :shear-x :shear-y)
           {:type "mesh"
            :vertices (into [] (mapcat (fn [index]
                                (transform-point transform
                                                 [(* width (- (/ (mod index stride) columns) 0.5))
                                                  (* height (- (/ (quot index stride) rows) 0.5))])))
                     (range vertex-count))
     :uvs (into [] (mapcat (fn [index]
                           [(/ (double (mod index stride)) columns)
                            (- 1.0 (/ (double (quot index stride)) rows))]))
                (range vertex-count))
     :triangles (into [] (mapcat (fn [index]
                                 (let [bottom-left (+ (mod index columns) (* stride (quot index columns)))
                                       bottom-right (inc bottom-left)
                                       top-left (+ bottom-left stride)
                                       top-right (inc top-left)]
                                   [bottom-left bottom-right top-right bottom-left top-right top-left])))
                      (range (* columns rows)))})))

(defn paint-influences
  "Changes one bone's normalized weight by amount, redistributing remaining
  weight proportionally. bind-point is used when painting a new influence."
  [influences bone-id amount bind-point]
  (validate-numbers! (conj bind-point amount) "Weight brush")
  (when-not (and bone-id (= 2 (count bind-point)))
    (throw (ex-info "Weight brush requires a bone and bind XY position" {})))
  (let [normalized (normalize-influences influences)
        previous (reduce (fn [found influence]
                           (if (= bone-id (:bone influence)) influence found))
                         {:bone bone-id :weight 0.0 :x (bind-point 0) :y (bind-point 1)} normalized)
        others (into [] (remove #(= bone-id (:bone %))) normalized)
        weight (max 0.0 (min 1.0 (+ (:weight previous) amount)))]
    (if (zero? (count others))
      [(assoc previous :weight 1.0)]
      (normalize-influences (conj (mapv #(assoc % :weight (* (:weight %) (/ (- 1.0 weight) (- 1.0 (:weight previous))))) others)
                                  (assoc previous :weight weight))))))

(defn- angle [x y]
  (Math/toDegrees (Math/atan2 (double y) (double x))))

(defn- angle-delta [from to]
  (- (mod (+ (- to from) 180.0) 360.0) 180.0))

(defn- turn-toward [bones index endpoint target]
  (let [bone (bones index)
        transforms (world-transforms bones)
        parent (get transforms (:parent bone) identity-transform)]
    (if-let [inverse (inverse-transform parent)]
      (let [[ex ey] (transform-point inverse endpoint)
            [tx ty] (transform-point inverse target)
            dx (- ex (:x bone 0.0))
            dy (- ey (:y bone 0.0))
            target-x (- tx (:x bone 0.0))
            target-y (- ty (:y bone 0.0))]
        (if (or (< (+ (* dx dx) (* dy dy)) 1.0e-16)
                (< (+ (* target-x target-x) (* target-y target-y)) 1.0e-16))
          bones
          (update-in bones [index :rotation] (fnil + 0.0)
                     (angle-delta (angle dx dy) (angle target-x target-y)))))
      bones)))

(defn- tip [bones transforms index]
  (let [bone (bones index)]
    (transform-point (transforms (:id bone)) [(:length bone 0.0) 0.0])))

(defn solve-pose-chain
  "Rotate a contiguous root-to-tip authoring chain toward a world target.
  Leaves translations, lengths and non-chain bones unchanged. Returns the best
  pose encountered, so an unreachable target cannot make the pose diverge."
  [bones chain target]
  (validate-numbers! target "Pose target")
  (let [bones (vec bones)
        indices (into {} (map-indexed (fn [index bone] [(:id bone) index])) bones)
        chain-indices (mapv indices chain)]
    (when-not (and (= 2 (count target)) (pos? (count chain))
                   (= (count chain) (count (set chain)))
                   (coll/every? integer? chain-indices)
                   (coll/every? (fn [[parent child]] (= parent (:parent (bones (indices child)))))
                                (partitionv 2 1 chain)))
      (throw (ex-info "Pose requires a contiguous bone chain and finite XY target" {:chain chain})))
    (let [last-index (peek chain-indices)
          error (fn [pose]
                  (let [[x y] (tip pose (world-transforms pose) last-index)]
                    (Math/hypot (- x (target 0)) (- y (target 1)))))]
      (loop [pose bones best bones best-error (error bones) iteration 0]
        (if (or (< best-error 1.0e-5) (= iteration 96))
          best
          (let [next-pose (reduce (fn [current index]
                                   (turn-toward current index
                                                (tip current (world-transforms current) last-index) target))
                                 pose (rseq chain-indices))
                next-error (error next-pose)]
            (if (= pose next-pose)
              best
              (recur next-pose
                     (if (< next-error best-error) next-pose best)
                     (min next-error best-error) (inc iteration)))))))))

(defn- solve-constraint
  "Solves a one- or two-bone rotation constraint without changing bind lengths,
  translation or scale. Unreachable targets approach the closest chain pose;
  singular scales and zero-length bones remain finite. mix blends the solved
  rotations with the input pose. Bones may be in arbitrary hierarchy order."
  [bones {:keys [target mix bend-positive] chain :bones :or {mix 1.0 bend-positive true}}]
  (validate-numbers! [mix] "IK mix")
  (when-not (<= 1 (count chain) 2)
    (throw (ex-info "IK requires one or two bones" {:bones chain})))
  (let [bones (vec bones)
        indices (into {} (map-indexed (fn [index bone] [(:id bone) index])) bones)
        transforms (world-transforms bones)
        chain-indices (mapv indices chain)]
    (when-not (and (contains? transforms target) (coll/every? integer? chain-indices))
      (throw (ex-info "IK references a missing bone or target" {})))
    (when (and (= 2 (count chain)) (not= (chain 0) (:parent (bones (chain-indices 1)))))
      (throw (ex-info "Two-bone IK requires a direct parent-child chain" {})))
    (doseq [index chain-indices]
      (validate-numbers! [(:length (bones index) 0.0)] "IK bone length"))
    (let [target-point (transform-point (transforms target) [0.0 0.0])
          last-index (peek chain-indices)
          solved (if (= 1 (count chain))
                   (turn-toward bones last-index (tip bones transforms last-index) target-point)
                   (let [first-index (chain-indices 0)
                         first-bone (bones first-index)
                         second-bone (bones last-index)
                         outer (get transforms (:parent first-bone) identity-transform)]
                     (if-let [inverse (inverse-transform outer)]
                       (let [[tx ty] (transform-point inverse target-point)
                             [p0x p0y] (transform-point inverse (transform-point (transforms (:id first-bone)) [0.0 0.0]))
                             [p1x p1y] (transform-point inverse (transform-point (transforms (:id second-bone)) [0.0 0.0]))
                             [p2x p2y] (transform-point inverse (tip bones transforms last-index))
                             l1 (Math/hypot (- p1x p0x) (- p1y p0y))
                             l2 (Math/hypot (- p2x p1x) (- p2y p1y))
                             dx (- tx p0x)
                             dy (- ty p0y)
                             distance (Math/hypot dx dy)
                             bend (if bend-positive 1.0 -1.0)
                             seed (if (or (< l1 1.0e-9) (< l2 1.0e-9))
                                    bones
                                    (let [cosine (max -1.0 (min 1.0 (/ (- (* distance distance) (* l1 l1) (* l2 l2)) (* 2.0 l1 l2))))
                                          elbow (* bend (Math/acos cosine))
                                          shoulder (- (Math/atan2 dy dx) (Math/atan2 (* l2 (Math/sin elbow)) (+ l1 (* l2 (Math/cos elbow)))))
                                          first-turn (angle-delta (angle (- p1x p0x) (- p1y p0y)) (Math/toDegrees shoulder))
                                          second-turn (angle-delta (angle (- p2x p1x) (- p2y p1y)) (Math/toDegrees (+ shoulder elbow)))]
                                      (-> bones
                                          (update-in [first-index :rotation] (fnil + 0.0) first-turn)
                                          (update-in [last-index :rotation] (fnil + 0.0) (- second-turn first-turn)))))]
                         (loop [pose seed iteration 0]
                           (let [worlds (world-transforms pose)
                                 [ex ey :as endpoint] (tip pose worlds last-index)
                                 error (+ (Math/pow (- ex (target-point 0)) 2.0) (Math/pow (- ey (target-point 1)) 2.0))]
                             (if (or (< error 1.0e-12) (= iteration 64))
                               pose
                               (let [pose (turn-toward pose last-index endpoint target-point)
                                     endpoint (tip pose (world-transforms pose) last-index)
                                     next-pose (turn-toward pose first-index endpoint target-point)
                                     unchanged (coll/every? (fn [index]
                                                              (< (abs (double (- (:rotation (next-pose index) 0.0)
                                                                                 (:rotation (pose index) 0.0))))
                                                                 1.0e-12))
                                                            chain-indices)]
                                 (if unchanged
                                   next-pose
                                   (recur next-pose (inc iteration))))))))
                       bones)))
          mix (max 0.0 (min 1.0 mix))]
      (if (zero? mix)
        bones
        (reduce (fn [result index]
                  (assoc-in result [index :rotation]
                            (+ (:rotation (bones index) 0.0)
                               (* mix (angle-delta (:rotation (bones index) 0.0) (:rotation (solved index) 0.0))))))
                bones chain-indices)))))

(defn solve-ik
  "Applies constraints in authored order and returns bones with solved local rotations."
  [bones constraints]
  (reduce solve-constraint bones constraints))

(defn normalize-weights [influences]
  (normalize-influences influences))

(defn compensate-region
  "Keep a region attachment a region. Reject shear instead of changing topology."
  [attachment old-world new-world]
  (let [inverse (or (inverse-transform new-world) (throw (ex-info "Cannot compensate a collapsed image transform" {})))
        [a b c d x y] (compose-transforms inverse (compose-transforms old-world (local-transform attachment)))
        sx (Math/hypot a b)
        sy (Math/hypot c d)
        dot (+ (* a c) (* b d))]
    (when (> (abs dot) (* 1.0e-6 (max 1.0 (* sx sy))))
      (throw (ex-info "Image compensation would require shear. Region images cannot represent this transform; disable Images compensation or adjust the parent scaling." {:attachment-type "region"})))
    (assoc attachment :x x :y y :rotation (Math/toDegrees (Math/atan2 b a))
           :scale-x sx :scale-y (* (if (neg? (- (* a d) (* b c))) -1.0 1.0) sy))))
