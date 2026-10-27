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

(ns editor.skeletal-animation-mesh
  "Pure mesh editing. Vertices/UVs/triangles use the attachment's flat vectors.
  A result contains :mesh, :selection, :vertex-map and :deform-policy. Each
  vertex-map entry maps original vertex indices to interpolation coefficients.
  Binding contexts contain :slot-transform, :bone-transforms and :bones, all
  evaluated in setup pose. This module does not mutate documents or source JSON."
  (:require [clojure.set :as set]
            [editor.skeletal-animation-geometry :as geometry]
            [util.coll :as coll]))

(set! *warn-on-reflection* true)

(defn- require-value! [value message data]
  (when-not value (throw (ex-info message data))))

(defn- pairs [values]
  (into [] (partition-all 2) values))

(defn- faces [mesh]
  (into [] (partition-all 3) (:triangles mesh)))

(defn- canonical-edge [[a b]]
  (if (< a b) [a b] [b a]))

(defn- face-edges [[a b c]]
  [(canonical-edge [a b]) (canonical-edge [b c]) (canonical-edge [c a])])

(defn- cross [[ax ay] [bx by] [cx cy]]
  (- (* (- bx ax) (- cy ay)) (* (- by ay) (- cx ax))))

(defn- distance [[ax ay] [bx by]]
  (Math/hypot (double (- ax bx)) (double (- ay by))))

(defn- validate-mesh! [{:keys [vertices uvs triangles weights constrained-edges] :as mesh}]
  (require-value! (and (vector? vertices) (even? (count vertices)) (>= (count vertices) 6)
                       (vector? uvs) (= (count vertices) (count uvs))
                       (coll/every? geometry/finite-number? vertices)
                       (coll/every? geometry/finite-number? uvs)
                       (vector? triangles) (zero? (mod (count triangles) 3)))
                  "A mesh needs finite XY/UV vectors and triangle triples." {})
  (let [points (pairs vertices) n (count points)]
    (require-value! (coll/every? #(and (integer? %) (<= 0 % (dec n))) triangles)
                    "Triangle indices must reference existing vertices." {})
    (require-value! (coll/every? #(= 3 (count (set %))) (faces mesh))
                    "A triangle must reference three distinct vertices." {})
    (when constrained-edges
      (require-value! (and (vector? constrained-edges)
                           (coll/every? #(and (vector? %) (= 2 (count %)) (not= (% 0) (% 1))
                                             (coll/every? (fn [index] (and (integer? index) (<= 0 index (dec n)))) %)) constrained-edges))
                      "Constrained edges need two existing, distinct vertex indices." {}))
    (when weights
      (require-value! (and (vector? weights) (= n (count weights)))
                      "Every weighted vertex needs an influence vector." {})
      (doseq [influences weights]
        (require-value! (and (vector? influences) (pos? (count influences))
                             (= (count influences) (count (into #{} (map :bone) influences))))
                        "Vertex influences must contain unique bones." {})
        (doseq [{:keys [bone weight x y]} influences]
          (require-value! (and (string? bone) (coll/every? geometry/finite-number? [weight x y])
                               (not (neg? weight)))
                          "Influences need a bone, finite bind coordinates and nonnegative weights." {})))))
  mesh)

(defn- validate-selection! [mesh selection]
  (require-value! (coll/every? #(and (integer? %) (<= 0 % (dec (quot (count (:vertices mesh)) 2)))) selection)
                  "Selection references a missing vertex." {:selection selection})
  (set selection))

(defn- validate-topology! [mesh]
  (validate-mesh! mesh)
  (when (:weights mesh)
    (require-value! (coll/every? #(< (abs (double (- 1.0 (transduce (map :weight) + 0.0 %)))) 1.0e-7) (:weights mesh))
                    "Normalize vertex weights before changing topology." {})))

(defn- identity-map [mesh]
  (mapv #(hash-map % 1.0) (range (quot (count (:vertices mesh)) 2))))

(defn- result [mesh selection mapping policy]
  {:mesh mesh :selection (set selection) :vertex-map mapping :deform-policy policy})

(defn select-vertices
  "Update a vertex selection with :replace, :add, :remove or :toggle."
  [mesh selection hits mode]
  (validate-mesh! mesh)
  (let [selection (validate-selection! mesh selection) hits (validate-selection! mesh hits)]
    (case mode
      :replace hits
      :add (set/union selection hits)
      :remove (set/difference selection hits)
      :toggle (set/union (set/difference selection hits) (set/difference hits selection))
      (throw (ex-info "Unknown selection mode." {:mode mode})))))

(defn vertices-in-rect [mesh [x0 y0 x1 y1 :as bounds]]
  (validate-mesh! mesh)
  (require-value! (and (= 4 (count bounds)) (coll/every? geometry/finite-number? bounds)) "Invalid selection rectangle." {})
  (into #{} (keep-indexed (fn [index [x y]]
                            (when (and (<= (min x0 x1) x (max x0 x1)) (<= (min y0 y1) y (max y0 y1))) index)))
        (pairs (:vertices mesh))))

(defn nearest-vertex [mesh point max-distance]
  (validate-mesh! mesh)
  (require-value! (and (= 2 (count point)) (coll/every? geometry/finite-number? point)
                       (geometry/finite-number? max-distance) (not (neg? max-distance))) "Invalid vertex pick." {})
  (:index (reduce (fn [found [index vertex]]
                    (let [d (distance point vertex)]
                      (if-not (and (<= d max-distance) (< d (:distance found Double/POSITIVE_INFINITY)))
                        found
                        {:index index :distance d})))
                  nil (eduction (map-indexed vector) (pairs (:vertices mesh))))))

(defn- barycentric [point a b c]
  (let [denominator (cross a b c)
        wa (/ (cross point b c) denominator)
        wb (/ (cross a point c) denominator)]
    [wa wb (- 1.0 wa wb)]))

(defn- inside-triangle? [point a b c]
  (and (geometry/finite-number? (cross a b c)) (> (abs (double (cross a b c))) 1.0e-10)
       (coll/every? #(>= % -1.0e-9) (barycentric point a b c))))

(defn- on-segment? [a b point]
  (and (< (abs (double (cross a b point))) 1.0e-9)
       (<= (min (a 0) (b 0)) (point 0) (max (a 0) (b 0)))
       (<= (min (a 1) (b 1)) (point 1) (max (a 1) (b 1)))))

(defn- segments-cross? [a b c d]
  (or (and (neg? (* (cross a b c) (cross a b d))) (neg? (* (cross c d a) (cross c d b))))
      (on-segment? a b c) (on-segment? a b d) (on-segment? c d a) (on-segment? c d b)))

(defn- triangulate-polygon [points boundary]
  (let [n (count boundary)
        area (reduce (fn [area index]
                       (let [[ax ay] (points (boundary index))
                             [bx by] (points (boundary (mod (inc index) n)))]
                         (+ area (- (* ax by) (* ay bx))))) 0.0 (range n))]
    (require-value! (and (>= n 3) (= n (count (set boundary))) (geometry/finite-number? area) (> (abs (double area)) 1.0e-10))
                    "Triangulation needs a nonzero simple polygon." {:boundary boundary})
    (doseq [i (range n) j (range (+ i 2) n)]
      (when-not (and (zero? i) (= j (dec n)))
        (require-value! (not (segments-cross? (points (boundary i)) (points (boundary (mod (inc i) n)))
                                              (points (boundary j)) (points (boundary (mod (inc j) n)))))
                        "A polygon boundary cannot cross or touch itself." {})))
    (loop [remaining (if (pos? area) boundary (vec (rseq boundary))) triangles []]
      (if (= 3 (count remaining))
        (into triangles remaining)
        (let [n (count remaining)
              ear (coll/first-where
                    (fn [index]
                      (let [a (remaining (mod (dec index) n)) b (remaining index) c (remaining (mod (inc index) n))]
                        (and (> (cross (points a) (points b) (points c)) 1.0e-10)
                             (not (coll/any? #(and (not (contains? #{a b c} %))
                                                   (inside-triangle? (points %) (points a) (points b) (points c))) remaining)))))
                    (range n))]
          (require-value! ear "Cannot triangulate a degenerate or self-intersecting polygon." {})
          (let [a (remaining (mod (dec ear) n)) b (remaining ear) c (remaining (mod (inc ear) n))]
            (recur (into (subvec remaining 0 ear) (subvec remaining (inc ear))) (conj triangles a b c))))))))

(defn- split-face-edge [[a b c :as triangle] edge new-index]
  (if-not (set/subset? edge (set triangle))
    [triangle]
    (let [ordered (cond (= edge #{a b}) [a b c] (= edge #{b c}) [b c a] :else [c a b])
          [u v other] ordered]
      [[u new-index other] [new-index v other]])))

(defn- insert-index [points triangles new-index]
  (let [point (points new-index)
        triangle (coll/first-where (fn [[a b c]] (inside-triangle? point (points a) (points b) (points c))) triangles)]
    (require-value! triangle "A new vertex must lie inside the current mesh." {:point point})
    (require-value! (not (coll/any? #(< (distance point (points %)) 1.0e-9) triangle))
                    "A vertex already exists at that position." {:point point})
    (let [[a b c] triangle
          coefficients (barycentric point (points a) (points b) (points c))
          edge (cond (< (abs (double (coefficients 0))) 1.0e-9) #{b c}
                     (< (abs (double (coefficients 1))) 1.0e-9) #{a c}
                     (< (abs (double (coefficients 2))) 1.0e-9) #{a b})]
      {:triangles (if edge
                    (into [] (mapcat #(split-face-edge % edge new-index)) triangles)
                    (into [] (mapcat #(if (= triangle %) [[a b new-index] [b c new-index] [c a new-index]] [%])) triangles))
       :sources (into {} (keep (fn [[index weight]] (when (> weight 1.0e-9) [index weight]))) (mapv vector triangle coefficients))})))

(declare connect-vertices)

(defn- single-boundary? [triangles]
  (if (zero? (count triangles))
    true
    (let [counts (frequencies (into [] (mapcat face-edges) triangles))
          adjacency (reduce (fn [neighbors [[a b] count]]
                              (if (not= 1 count) neighbors
                                  (-> neighbors (update a (fnil conj #{}) b) (update b (fnil conj #{}) a)))) {} counts)]
      (and (pos? (count adjacency))
           (coll/every? #(<= 1 % 2) (coll/vals counts))
           (coll/every? #(= 2 (count %)) (coll/vals adjacency))
           (loop [pending [(ffirst adjacency)] visited #{}]
             (if (zero? (count pending))
               (= (count visited) (count adjacency))
               (let [vertex (peek pending) remaining (pop pending)]
                 (if (contains? visited vertex)
                   (recur remaining visited)
                   (recur (into remaining (get adjacency vertex)) (conj visited vertex))))))))))

(defn triangulate
  "Rebuild a simple boundary with optional interior vertices. Holes and
  coincident seam vertices require explicit local edits instead of this tool."
  [mesh boundary]
  (validate-topology! mesh)
  (validate-selection! mesh boundary)
  (require-value! (single-boundary? (faces mesh))
                  "Global triangulation cannot fill existing holes or join disconnected components; use local edge edits." {})
  (let [points (pairs (:vertices mesh)) boundary (vec boundary)
        triangles (into [] (partition-all 3) (triangulate-polygon points boundary))
        triangles (reduce (fn [triangles index] (:triangles (insert-index points triangles index)))
                          triangles (eduction (remove (set boundary)) (range (count points))))
        updated (assoc mesh :triangles (into [] cat triangles))
        updated (if-not (pos? (count (:constrained-edges mesh)))
                  updated
                  (reduce (fn [mesh edge] (:mesh (connect-vertices mesh edge)))
                          (assoc updated :constrained-edges []) (:constrained-edges mesh)))]
    (result updated #{} (identity-map mesh) :identity)))

(defn- interpolate-influences [weights sources]
  (let [bones (into [] (comp (mapcat #(get weights %)) (map :bone) (distinct)) (sort (into [] (coll/keys sources))))]
    (into []
          (keep (fn [bone]
                  (let [[weight x y] (reduce (fn [[weight x y] [index coefficient]]
                                              (let [influence (coll/first-where #(= bone (:bone %)) (weights index))
                                                    contribution (* coefficient (:weight influence 0.0))]
                                                [(+ weight contribution) (+ x (* contribution (:x influence 0.0))) (+ y (* contribution (:y influence 0.0)))]))
                                            [0.0 0.0 0.0] sources)]
                    (when (pos? weight) {:bone bone :weight weight :x (/ x weight) :y (/ y weight)})))) bones)))

(defn- interpolate-pair [points sources]
  (reduce (fn [[x y] [index coefficient]]
            (let [[px py] (points index)] [(+ x (* coefficient px)) (+ y (* coefficient py))])) [0.0 0.0] sources))

(defn- append-vertex [mesh sources triangles]
  (let [index (quot (count (:vertices mesh)) 2)
        updated (cond-> (assoc mesh :vertices (into (:vertices mesh) (interpolate-pair (pairs (:vertices mesh)) sources))
                                   :uvs (into (:uvs mesh) (interpolate-pair (pairs (:uvs mesh)) sources))
                                   :triangles (into [] cat triangles))
                  (:weights mesh) (update :weights conj (interpolate-influences (:weights mesh) sources))
                  (and (:constrained-edges mesh) (= 2 (count sources)))
                  (update :constrained-edges (fn [edges]
                                               (into [] (mapcat (fn [[a b :as edge]]
                                                                  (if (= (set edge) (set (coll/keys sources))) [[a index] [index b]] [edge]))) edges))))]
    (result updated #{index} (conj (identity-map mesh) sources) :barycentric)))

(defn insert-vertex [mesh point]
  (validate-topology! mesh)
  (require-value! (and (= 2 (count point)) (coll/every? geometry/finite-number? point)) "Invalid vertex position." {})
  (let [points (pairs (:vertices mesh))
        _ (require-value! (not (coll/any? #(< (distance point %) 1.0e-9) points)) "A vertex already exists at that position." {})
        {:keys [sources triangles]} (insert-index (conj points (vec point)) (faces mesh) (count points))]
    (append-vertex mesh sources triangles)))

(defn split-edge [mesh [a b :as edge] fraction]
  (validate-topology! mesh)
  (validate-selection! mesh edge)
  (require-value! (and (= 2 (count edge)) (not= a b) (geometry/finite-number? fraction) (< 0.0 fraction 1.0))
                  "An edge split needs two distinct endpoints and a fraction between zero and one." {})
  (let [triangles (faces mesh) edge-set (set edge)
        adjacent (into [] (filter #(set/subset? edge-set (set %))) triangles)]
    (require-value! (<= 1 (count adjacent) 2) "Only existing manifold edges can be split." {:edge edge})
    (append-vertex mesh {a (- 1.0 fraction) b fraction}
                   (into [] (mapcat #(split-face-edge % edge-set (quot (count (:vertices mesh)) 2))) triangles))))

(defn- neighbor-boundary [incident index]
  (let [edges (mapv #(filterv (fn [vertex] (not= index vertex)) %) incident)
        adjacency (reduce (fn [m [a b]] (-> m (update a (fnil conj #{}) b) (update b (fnil conj #{}) a))) {} edges)
        endpoints (into [] (keep (fn [[vertex neighbors]] (when (= 1 (count neighbors)) vertex))) adjacency)]
    (require-value! (and (coll/every? #(<= 1 (count %) 2) (coll/vals adjacency)) (contains? #{0 2} (count endpoints)))
                    "A nonmanifold vertex cannot be removed automatically." {:vertex index})
    (let [start (first (sort (if (pos? (count endpoints)) endpoints (into [] (coll/keys adjacency)))))]
      (loop [current start previous nil visited #{} ordered []]
        (if (or (nil? current) (contains? visited current))
          (do (require-value! (= (count visited) (count adjacency)) "The vertex has disconnected triangle fans." {:vertex index}) ordered)
          (recur (first (sort (disj (get adjacency current) previous))) current (conj visited current) (conj ordered current)))))))

(defn delete-vertices [mesh selection]
  (validate-topology! mesh)
  (let [selection (validate-selection! mesh selection)
        n (quot (count (:vertices mesh)) 2)]
    (require-value! (>= (- n (count selection)) 3) "A mesh must retain at least three vertices." {})
    (let [triangles (reduce (fn [triangles index]
                             (let [incident (filterv #(contains? (set %) index) triangles)
                                   boundary (when (pos? (count incident)) (neighbor-boundary incident index))
                                   replacements (if (< (count boundary) 3) [] (into [] (partition-all 3) (triangulate-polygon (pairs (:vertices mesh)) boundary)))]
                               (into (filterv #(not (contains? (set %) index)) triangles) replacements)))
                           (faces mesh) (sort > selection))
          retained (into [] (remove selection) (range n))
          indices (into {} (map-indexed (fn [index original] [original index])) retained)
          updated (cond-> (assoc mesh :vertices (into [] (mapcat #(subvec (:vertices mesh) (* 2 %) (+ 2 (* 2 %)))) retained)
                                     :uvs (into [] (mapcat #(subvec (:uvs mesh) (* 2 %) (+ 2 (* 2 %)))) retained)
                                     :triangles (into [] (comp cat (map indices)) triangles))
                    (:weights mesh) (assoc :weights (mapv (:weights mesh) retained))
                    (:constrained-edges mesh) (update :constrained-edges (fn [edges]
                                                                          (into [] (comp (remove #(coll/any? selection %)) (map #(mapv indices %))) edges))))]
      (result updated #{} (mapv #(hash-map % 1.0) retained) :barycentric))))

(defn rebuild-uvs
  "Planar UV projection in mesh-local coordinates. Explicit image bounds keep
  UVs stable while authoring outside the image; nil bounds fit the mesh."
  [mesh bounds flip-y]
  (validate-mesh! mesh)
  (let [points (pairs (:vertices mesh))
        [min-x min-y max-x max-y :as bounds] (or bounds [(reduce min (mapv first points)) (reduce min (mapv second points))
                                                       (reduce max (mapv first points)) (reduce max (mapv second points))])]
    (require-value! (and (= 4 (count bounds)) (coll/every? geometry/finite-number? bounds) (< min-x max-x) (< min-y max-y))
                    "UV projection needs nonzero finite image bounds." {})
    (result (assoc mesh :uvs (into [] (mapcat (fn [[x y]]
                                               (let [v (/ (- y min-y) (- max-y min-y))]
                                                 [(/ (- x min-x) (- max-x min-x)) (if-not flip-y v (- 1.0 v))]))) points))
            #{} (identity-map mesh) :identity)))

(defn remap-deform
  "Migrate a full FFD vector through a topology result. For weighted meshes,
  each new influence receives the old offsets averaged by barycentric factor
  times old bone weight. This preserves the interpolated world displacement
  under every bone pose. Arbitrary reweighting is deliberately rejected."
  [before after vertex-map offsets]
  (validate-topology! before)
  (validate-topology! after)
  (let [weighted (boolean (:weights before))
        expected-size (if weighted (* 2 (transduce (map count) + 0 (:weights before))) (count (:vertices before)))]
    (require-value! (and (vector? offsets) (vector? vertex-map) (= weighted (boolean (:weights after))) (= expected-size (count offsets))
                         (= (count vertex-map) (quot (count (:vertices after)) 2))
                         (coll/every? geometry/finite-number? offsets)) "FFD does not match the source topology." {})
    (doseq [sources vertex-map]
      (require-value! (map? sources) "Each vertex migration entry must be an index-to-coefficient map." {})
      (validate-selection! before (coll/keys sources))
      (require-value! (and (pos? (count sources)) (coll/every? #(and (geometry/finite-number? %) (not (neg? %))) (coll/vals sources))
                           (< (abs (double (- 1.0 (reduce + 0.0 (coll/vals sources))))) 1.0e-7)) "Invalid vertex migration coefficients." {}))
    (if-not weighted
      (into [] (mapcat #(interpolate-pair (pairs offsets) %)) vertex-map)
      (let [old-offsets (:values (reduce (fn [{:keys [cursor values]} influences]
                                           {:cursor (+ cursor (* 2 (count influences)))
                                            :values (conj values (into {} (map-indexed (fn [index influence]
                                                                                       [(:bone influence) (subvec offsets (+ cursor (* 2 index)) (+ cursor (* 2 index) 2))])) influences))})
                                         {:cursor 0 :values []} (:weights before)))]
        (into []
              (mapcat (fn [[new-index sources]]
                        (let [expected (interpolate-influences (:weights before) sources)
                              expected-weights (into {} (map (juxt :bone :weight)) expected)
                              actual (get (:weights after) new-index)]
                          (require-value! (= (set (coll/keys expected-weights)) (into #{} (comp (filter #(pos? (:weight %))) (map :bone)) actual))
                                          "FFD topology migration cannot also add/remove bone weights." {})
                          (into []
                                (mapcat (fn [{:keys [bone weight]}]
                                          (require-value! (< (abs (double (- weight (get expected-weights bone 0.0)))) 1.0e-7)
                                                          "Reweighting requires bone-identity FFD migration, not topology interpolation." {})
                                          (if (zero? weight)
                                            (reduce (fn [[x y] [old-index coefficient]]
                                                      (let [[dx dy] (get (old-offsets old-index) bone [0.0 0.0])]
                                                        [(+ x (* coefficient dx)) (+ y (* coefficient dy))])) [0.0 0.0] sources)
                                            (reduce (fn [[x y] [old-index coefficient]]
                                                      (let [old (coll/first-where #(= bone (:bone %)) (get (:weights before) old-index))
                                                            factor (/ (* coefficient (:weight old 0.0)) weight)
                                                            [dx dy] (get (old-offsets old-index) bone [0.0 0.0])]
                                                        [(+ x (* factor dx)) (+ y (* factor dy))])) [0.0 0.0] sources)))) actual))))
              (eduction (map-indexed vector) vertex-map))))))

(defn- validate-transform! [transform]
  (require-value! (and (= 6 (count transform)) (coll/every? geometry/finite-number? transform)) "Binding needs finite affine transforms." {}))

(defn- validate-context! [{:keys [slot-transform bone-transforms]} bone-ids]
  (validate-transform! slot-transform)
  (doseq [bone bone-ids]
    (let [transform (get bone-transforms bone)]
      (validate-transform! transform)
      (require-value! (geometry/inverse-transform transform) "Cannot bind to a bone with a singular setup transform." {:bone bone}))))

(defn- bind-point [point bone {:keys [slot-transform bone-transforms]}]
  (let [local (geometry/inverse-transform-point (get bone-transforms bone) (geometry/transform-point slot-transform point))]
    (require-value! (and (= 2 (count local)) (coll/every? geometry/finite-number? local)) "Binding coordinates overflowed or use a missing/singular bone." {:bone bone})
    local))

(defn transform-selected
  "Apply a mesh-local affine transform to selected vertices. Weighted bind
  points receive the equivalent setup-world displacement, preserving weights
  and all existing FFD offsets. UVs remain attached to their vertices."
  [mesh selection transform context]
  (validate-mesh! mesh)
  (validate-transform! transform)
  (let [selection (validate-selection! mesh selection) points (pairs (:vertices mesh))
        updated-points (mapv (fn [index point] (if-not (contains? selection index) point (geometry/transform-point transform point))) (range (count points)) points)]
    (require-value! (coll/every? geometry/finite-number? (into [] cat updated-points)) "Vertex transform overflowed." {})
    (when (:weights mesh)
      (validate-context! context (into #{} (comp (mapcat #((:weights mesh) %)) (map :bone)) selection)))
    (let [updated (cond-> (assoc mesh :vertices (into [] cat updated-points))
                    (:weights mesh)
                    (assoc :weights (mapv (fn [index influences]
                                           (if-not (contains? selection index)
                                             influences
                                             (let [[ox oy] (geometry/transform-point (:slot-transform context) (points index))
                                                   [nx ny] (geometry/transform-point (:slot-transform context) (updated-points index))]
                                               (mapv (fn [{:keys [bone x y] :as influence}]
                                                       (let [world (get (:bone-transforms context) bone)
                                                             [wx wy] (geometry/transform-point world [x y])
                                                             [x y] (geometry/inverse-transform-point world [(+ wx (- nx ox)) (+ wy (- ny oy))])]
                                                         (assoc influence :x x :y y))) influences))))
                                         (range (count points)) (:weights mesh))))]
      (result updated selection (identity-map mesh) :identity))))

(defn- normalize-locked [influences locked-bones]
  (let [locked-total (transduce (comp (filter #(contains? locked-bones (:bone %))) (map :weight)) + 0.0 influences)
        free (filterv #(not (contains? locked-bones (:bone %))) influences)
        total (transduce (map :weight) + 0.0 free)
        available (max 0.0 (- 1.0 locked-total))]
    (require-value! (<= locked-total (+ 1.0 1.0e-9)) "Locked weights exceed one and cannot be normalized without changing them." {})
    (require-value! (or (pos? (count free)) (< available 1.0e-9)) "All influences are locked below a total weight of one." {})
    (mapv (fn [influence]
            (if (contains? locked-bones (:bone influence))
              influence
              (assoc influence :weight (if (pos? total)
                                         (* available (/ (:weight influence) total))
                                         (if (= (:bone influence) (:bone (first free))) available 0.0))))) influences)))

(defn- require-weighted! [mesh]
  (validate-mesh! mesh)
  (require-value! (:weights mesh) "Bind the mesh before editing weights." {}))

(defn normalize-weights [mesh selection locked-bones]
  (require-weighted! mesh)
  (let [selection (validate-selection! mesh selection)]
    (result (update mesh :weights (fn [weights] (mapv (fn [index influences]
                                                       (if-not (contains? selection index) influences (normalize-locked influences locked-bones)))
                                                     (range (count weights)) weights)))
            selection (identity-map mesh) :bone-identity)))

(defn- segment-distance [point a b]
  (let [[ax ay] a [bx by] b [px py] point
        dx (- bx ax) dy (- by ay) length-squared (+ (* dx dx) (* dy dy))
        t (if (< length-squared 1.0e-12) 0.0 (max 0.0 (min 1.0 (/ (+ (* (- px ax) dx) (* (- py ay) dy)) length-squared))))]
    (distance point [(+ ax (* t dx)) (+ ay (* t dy))])))

(defn bind-weights
  "Distance-to-bone-segment binding. Locked existing influences retain their
  exact weights/coordinates; max-influences includes these protected entries."
  [mesh bone-ids context {:keys [falloff max-influences locked-bones selection]
                        :or {falloff 2.0 max-influences 4 locked-bones #{}}}]
  (validate-mesh! mesh)
  (require-value! (and (pos? (count bone-ids)) (= (count bone-ids) (count (set bone-ids)))
                       (geometry/finite-number? falloff) (< 0.0 falloff 16.0)
                       (integer? max-influences) (<= 1 max-influences 64)) "Invalid automatic binding options." {})
  (validate-context! context bone-ids)
  (let [points (pairs (:vertices mesh)) selection (validate-selection! mesh (or selection (range (count points))))
        lengths (into {} (map (fn [bone] [(:id bone) (:length bone 0.0)])) (:bones context))
        candidates (filterv #(not (contains? locked-bones %)) bone-ids)
        weights (mapv (fn [index point]
                        (let [original (get (:weights mesh) index)]
                          (if-not (contains? selection index)
                            (or original (throw (ex-info "An unbound mesh must initially bind all vertices." {})))
                            (let [locked (filterv #(contains? locked-bones (:bone %)) original)
                                  _ (require-value! (<= (count locked) max-influences) "The influence limit cannot remove locked bones." {:vertex index})
                                  world-point (geometry/transform-point (:slot-transform context) point)
                                  distances (mapv (fn [bone]
                                                    (let [transform (get (:bone-transforms context) bone)
                                                          length (get lengths bone 0.0)]
                                                      (require-value! (and (geometry/finite-number? length) (not (neg? length))) "A binding bone has invalid length." {:bone bone})
                                                      [bone (segment-distance world-point (geometry/transform-point transform [0.0 0.0])
                                                                              (geometry/transform-point transform [length 0.0]))])) candidates)
                                  nearest (into [] (take (- max-influences (count locked))) (sort-by (juxt second first) distances))
                                  influences (mapv (fn [[bone d]]
                                                      (let [[x y] (bind-point point bone context)]
                                                        {:bone bone :x x :y y :weight (/ 1.0 (Math/pow (max 1.0e-6 d) falloff))})) nearest)]
                              (normalize-locked (into locked influences) locked-bones)))))
                      (range (count points)) points)]
    (result (assoc mesh :weights weights) selection (identity-map mesh) :bone-identity)))

(defn- paint-vertex [influences point bone amount replace-weight context locked-bones]
  (if (contains? locked-bones bone)
    influences
    (let [normalized (normalize-locked influences locked-bones)
          previous (coll/first-where #(= bone (:bone %)) normalized)
          locked-total (transduce (comp (filter #(contains? locked-bones (:bone %))) (map :weight)) + 0.0 normalized)
          others (filterv #(and (not= bone (:bone %)) (not (contains? locked-bones (:bone %)))) normalized)
          others-total (transduce (map :weight) + 0.0 others)
          available (max 0.0 (- 1.0 locked-total))
          desired (max 0.0 (min available (if (nil? replace-weight) (+ (:weight previous 0.0) amount) replace-weight)))
          desired (if (zero? (count others)) available desired)
          normalized (if (or previous (zero? desired)) normalized (let [[x y] (bind-point point bone context)] (conj normalized {:bone bone :x x :y y :weight 0.0})))
          first-other (:bone (first others))]
      (mapv (fn [influence]
              (cond
                (contains? locked-bones (:bone influence)) influence
                (= bone (:bone influence)) (assoc influence :weight desired)
                :else (assoc influence :weight (if (pos? others-total)
                                                 (* (- available desired) (/ (:weight influence) others-total))
                                                 (if (= first-other (:bone influence)) (- available desired) 0.0))))) normalized))))

(defn- adjacency [mesh]
  (reduce (fn [neighbors [a b c]]
            (-> neighbors (update a (fnil into #{}) [b c]) (update b (fnil into #{}) [a c]) (update c (fnil into #{}) [a b]))) {} (faces mesh)))

(defn set-weights
  "Assign an absolute normalized weight to a bone on selected vertices while
  preserving locked weights and redistributing the remaining free budget."
  [mesh selection bone-id weight context locked-bones]
  (require-weighted! mesh)
  (require-value! (and (geometry/finite-number? weight) (<= 0.0 weight 1.0)) "Assigned weight must be between zero and one." {})
  (validate-context! context [bone-id])
  (let [selection (validate-selection! mesh selection) points (pairs (:vertices mesh))]
    (result (update mesh :weights (fn [weights]
                                   (mapv (fn [index influences]
                                           (if-not (contains? selection index) influences
                                                   (paint-vertex influences (points index) bone-id 0.0 weight context locked-bones)))
                                         (range (count weights)) weights)))
            selection (identity-map mesh) :bone-identity)))

(defn- smooth-vertex [weights neighbors index point amount context locked-bones]
  (let [original (weights index) adjacent (get neighbors index)]
    (if (or (zero? amount) (zero? (count adjacent)))
      original
      (let [averages (reduce (fn [totals neighbor]
                              (reduce (fn [totals {:keys [bone weight]}] (update totals bone (fnil + 0.0) (/ weight (count adjacent)))) totals (weights neighbor))) {} adjacent)
            original-by-bone (into {} (map (juxt :bone identity)) original)
            bones (into (mapv :bone original) (remove #(or (contains? original-by-bone %) (contains? locked-bones %) (zero? (get averages %)))) (sort (into [] (coll/keys averages))))
            influences (mapv (fn [bone]
                                (let [previous (get original-by-bone bone)]
                                  (if (contains? locked-bones bone)
                                    previous
                                    (let [weight (+ (* (- 1.0 amount) (:weight previous 0.0)) (* amount (get averages bone 0.0)))
                                          [x y] (if previous [(:x previous) (:y previous)] (bind-point point bone context))]
                                      (assoc (or previous {:bone bone :x x :y y}) :weight weight))))) bones)]
        (normalize-locked influences locked-bones)))))

(defn smooth-weights [mesh selection amount context locked-bones]
  (require-weighted! mesh)
  (require-value! (and (geometry/finite-number? amount) (<= 0.0 amount 1.0)) "Smoothing amount must be between zero and one." {})
  (let [selection (validate-selection! mesh selection) points (pairs (:vertices mesh)) neighbors (adjacency mesh) weights (:weights mesh)]
    (validate-context! context (into #{} (comp cat (map :bone)) weights))
    (result (assoc mesh :weights (mapv (fn [index point]
                                        (if-not (contains? selection index) (weights index)
                                                (smooth-vertex weights neighbors index point amount context locked-bones)))
                                      (range (count points)) points))
            selection (identity-map mesh) :bone-identity)))

(defn brush-weights
  "Setup-world radial brush, with :add/:subtract/:replace/:smooth. Strength is
  0..1; falloff controls radial softness. Replace blends toward strength."
  [mesh {:keys [mode center radius strength bone-id falloff locked-bones]
         :or {mode :add falloff 2.0 locked-bones #{}}} context]
  (require-weighted! mesh)
  (require-value! (and (contains? #{:add :subtract :replace :smooth} mode)
                       (= 2 (count center)) (coll/every? geometry/finite-number? center)
                       (geometry/finite-number? radius) (pos? radius)
                       (geometry/finite-number? strength) (<= 0.0 strength 1.0)
                       (geometry/finite-number? falloff) (<= 0.0 falloff 16.0)) "Invalid weight brush options." {})
  (validate-context! context (if (= :smooth mode) (into #{} (comp cat (map :bone)) (:weights mesh)) [bone-id]))
  (let [points (pairs (:vertices mesh)) weights (:weights mesh) neighbors (adjacency mesh)
        factors (mapv (fn [point]
                        (let [d (distance center (geometry/transform-point (:slot-transform context) point))]
                          (if (>= d radius) 0.0 (Math/pow (- 1.0 (/ d radius)) falloff)))) points)
        selection (into #{} (keep-indexed (fn [index factor] (when (and (pos? strength) (pos? factor)) index))) factors)
        updated (mapv (fn [index point]
                        (if-not (contains? selection index)
                          (weights index)
                          (let [amount (* strength (factors index))]
                            (if (= :smooth mode)
                              (smooth-vertex weights neighbors index point amount context locked-bones)
                              (let [previous (:weight (coll/first-where #(= bone-id (:bone %)) (weights index)) 0.0)]
                                (paint-vertex (weights index) point bone-id (if (= :subtract mode) (- amount) amount)
                                              (when (= :replace mode) (+ previous (* (factors index) (- strength previous)))) context locked-bones))))))
                      (range (count points)) points)]
    (result (assoc mesh :weights updated) selection (identity-map mesh) :bone-identity)))

(defn prune-weights [mesh selection {:keys [threshold max-influences locked-bones]
                                    :or {threshold 0.01 max-influences 4 locked-bones #{}}}]
  (require-weighted! mesh)
  (require-value! (and (geometry/finite-number? threshold) (<= 0.0 threshold 1.0)
                       (integer? max-influences) (<= 1 max-influences 64)) "Invalid pruning options." {})
  (let [selection (validate-selection! mesh selection)
        weights (mapv (fn [index influences]
                        (if-not (contains? selection index)
                          influences
                          (let [locked (filterv #(contains? locked-bones (:bone %)) influences)
                                _ (require-value! (<= (count locked) max-influences) "The influence limit cannot remove locked bones." {:vertex index})
                                free (vec (sort-by (juxt (comp - :weight) :bone) (remove #(contains? locked-bones (:bone %)) influences)))
                                eligible (filterv #(>= (:weight %) threshold) free)
                                retained (into [] (take (- max-influences (count locked))) (if (pos? (count eligible)) eligible (into [] (take 1) free)))]
                            (normalize-locked (into locked retained) locked-bones))))
                      (range (count (:weights mesh))) (:weights mesh))]
    (result (assoc mesh :weights weights) selection (identity-map mesh) :bone-identity)))

(defn- edge-table [triangles]
  (reduce-kv (fn [table index triangle]
               (reduce (fn [table edge] (update table edge (fnil conj []) index)) table (face-edges triangle))) {} triangles))

(defn- strictly-cross? [a b c d]
  (and (neg? (* (cross a b c) (cross a b d)))
       (neg? (* (cross c d a) (cross c d b)))))

(defn- cavity-boundary [triangles start]
  (let [frequencies (frequencies (into [] (mapcat face-edges) triangles))
        boundary (into [] (keep (fn [[edge count]] (when (= 1 count) edge))) frequencies)
        adjacency (reduce (fn [neighbors [a b]] (-> neighbors (update a (fnil conj #{}) b) (update b (fnil conj #{}) a))) {} boundary)]
    (require-value! (and (contains? adjacency start) (coll/every? #(= 2 (count %)) (coll/vals adjacency)))
                    "The connection does not have one simple triangle corridor." {})
    (loop [vertex start previous nil visited #{} ordered []]
      (if (contains? visited vertex)
        (do (require-value! (and (= vertex start) (= (count visited) (count adjacency)))
                            "The connection would cross a hole or disconnected component." {}) ordered)
        (recur (first (sort (disj (get adjacency vertex) previous))) vertex (conj visited vertex) (conj ordered vertex))))))

(defn- connect-edge-impl [mesh [a b :as edge]]
  (let [points (pairs (:vertices mesh)) triangles (faces mesh) table (edge-table triangles)
        edge (canonical-edge edge)
        protected (into #{} (map canonical-edge) (:constrained-edges mesh))]
    (require-value! (coll/every? #(<= 1 (count %) 2) (coll/vals table)) "Connections cannot change nonmanifold meshes." {})
    (require-value! (coll/every? #(contains? table %) protected) "A constrained edge is missing from the mesh." {})
    (let [updated (if (contains? table edge)
                    mesh
                    (let [crossed (into [] (filter (fn [[c d]] (strictly-cross? (points a) (points b) (points c) (points d)))) (coll/keys table))]
                      (require-value! (pos? (count crossed)) "The connection leaves the mesh or crosses an empty region." {:edge edge})
                      (doseq [crossed-edge crossed]
                        (require-value! (= 2 (count (table crossed-edge))) "The connection would cross a boundary or hole." {:edge crossed-edge})
                        (require-value! (not (contains? protected crossed-edge)) "The connection would cross an existing constrained edge." {:edge crossed-edge}))
                      (let [affected (into #{} (mapcat table) crossed)
                            corridor (mapv triangles (sort affected))
                            boundary (cavity-boundary corridor a)
                            split-index (.indexOf ^java.util.List boundary b)]
                        (require-value! (pos? split-index) "The connection endpoints do not belong to the same triangle corridor." {})
                        (let [left (subvec boundary 0 (inc split-index))
                              right (into [a] (rseq (subvec boundary split-index)))
                              replacements (into (triangulate-polygon points left) (triangulate-polygon points right))]
                          (assoc mesh :triangles (into (into [] (comp (keep-indexed (fn [index triangle] (when-not (contains? affected index) triangle))) cat) triangles)
                                                       replacements))))))]
      (assoc updated :constrained-edges (into [] (sort (conj protected edge)))))))

(defn connect-vertices
  "Connect two vertices by replacing their crossed triangle corridor. Existing
  constrained edges, boundaries, holes and nonmanifold topology are protected.
  Intermediate collinear vertices become a connected chain. Constraints are
  first-party :constrained-edges metadata, not Spine's encoded edges field."
  [mesh [a b :as edge]]
  (validate-mesh! mesh)
  (validate-selection! mesh edge)
  (require-value! (and (= 2 (count edge)) (not= a b)) "Select two distinct vertices to connect." {})
  (let [points (pairs (:vertices mesh))
        chain (vec (sort-by #(distance (points a) (points %))
                            (into [] (filter #(on-segment? (points a) (points b) (points %))) (range (count points)))))
        segments (mapv vector (subvec chain 0 (dec (count chain))) (subvec chain 1))]
    (require-value! (> (distance (points a) (points b)) 1.0e-9) "Coincident seam vertices cannot be connected automatically." {})
    (doseq [[u v] segments]
      (require-value! (> (distance (points u) (points v)) 1.0e-9) "The connection contains coincident seam vertices." {}))
    (result (reduce connect-edge-impl mesh segments) #{a b} (identity-map mesh) :identity)))

(defn disconnect-vertices
  "Remove only the explicit authoring constraint. Triangles remain intact;
  this operation does not cut the mesh or create an invisible hole."
  [mesh [a b :as edge]]
  (validate-mesh! mesh)
  (validate-selection! mesh edge)
  (require-value! (and (= 2 (count edge)) (not= a b)) "Select two distinct vertices to disconnect." {})
  (result (update mesh :constrained-edges (fn [edges] (filterv #(not= (canonical-edge edge) (canonical-edge %)) edges)))
          (set edge) (identity-map mesh) :identity))

(defn spine-export-layout
  "Return a hull-first vertex permutation for a temporary export document.
  :hull is the boundary vertex count. :edges contains encoded XY indices for
  boundary and explicit constrained edges. The caller must apply :vertex-map
  to every FFD key with remap-deform before exporting. Existing holes, unused
  vertices and nonmanifold topology are rejected instead of changing geometry."
  [mesh]
  (validate-topology! mesh)
  (let [triangles (faces mesh)
        table (edge-table triangles)
        boundary-edges (into [] (keep (fn [[edge incident]] (when (= 1 (count incident)) edge))) table)
        boundary-indices (into #{} cat boundary-edges)
        vertex-count (quot (count (:vertices mesh)) 2)
        constraints (into #{} (map canonical-edge) (:constrained-edges mesh))]
    (require-value! (and (pos? (count triangles)) (single-boundary? triangles)
                         (= vertex-count (count (set (:triangles mesh))))
                         (= 1 (+ vertex-count (- (count table)) (count triangles))))
                    "Editable Spine export needs one filled manifold mesh without holes or unused vertices." {})
    (require-value! (coll/every? #(contains? table %) constraints)
                    "A constrained edge is missing from the exported mesh." {})
    (let [boundary (cavity-boundary triangles (apply min boundary-indices))
          uv-points (pairs (:uvs mesh))
          boundary-pairs (mapv vector boundary (conj (subvec boundary 1) (first boundary)))
          area (transduce (map (fn [[a b]]
                                (let [[ax ay] (uv-points a)
                                      [bx by] (uv-points b)]
                                  (- (* ax by) (* bx ay))))) + 0.0 boundary-pairs)]
      (require-value! (and (geometry/finite-number? area) (not (zero? area)))
                      "The exported mesh needs a nonzero UV boundary." {})
      (doseq [[a b] boundary-pairs
              [c d] boundary-pairs
              :when (zero? (count (set/intersection #{a b} #{c d})))]
        (require-value! (not (segments-cross? (uv-points a) (uv-points b) (uv-points c) (uv-points d)))
                        "The exported mesh UV boundary cannot cross itself." {}))
      (let [boundary (if (pos? area) boundary (into [(first boundary)] (rseq (subvec boundary 1))))
            order (into boundary (remove boundary-indices) (range vertex-count))
            indices (into {} (map-indexed (fn [new-index old-index] [old-index new-index])) order)
            remap-flat (fn [values] (into [] (mapcat (pairs values)) order))
            remap-edge (fn [edge] (canonical-edge (mapv indices edge)))
            updated (cond-> (assoc mesh :vertices (remap-flat (:vertices mesh))
                                        :uvs (remap-flat (:uvs mesh))
                                        :triangles (mapv indices (:triangles mesh)))
                      (:weights mesh) (assoc :weights (mapv (:weights mesh) order))
                      (:constrained-edges mesh) (assoc :constrained-edges (into [] (sort (mapv remap-edge (:constrained-edges mesh))))))
            edges (into #{} (map remap-edge) (into constraints boundary-edges))]
        (assoc (result updated #{} (mapv #(hash-map % 1.0) order) :identity)
               :hull (count boundary)
               :edges (into [] (comp cat (map #(* 2 %))) (sort edges)))))))

(defn mirror-weights
  "Mirror selected source weights onto unique existing symmetric vertices.
  axis is :x/:y and origin is mesh-local. The bone-map may swap left/right IDs;
  unmapped IDs retain their identity. Context additionally accepts :locked-bones
  and :mirror-tolerance (default 1e-4). All transfers read the original mesh so
  selecting both sides performs a swap rather than overwriting the source."
  [mesh selection axis origin bone-map context]
  (require-weighted! mesh)
  (require-value! (and (contains? #{:x :y} axis) (geometry/finite-number? origin) (map? bone-map)) "Invalid weight mirror axis or bone map." {})
  (let [selection (validate-selection! mesh selection) points (pairs (:vertices mesh))
        coordinate (if (= :x axis) 0 1)
        tolerance (:mirror-tolerance context 1.0e-4) locked-bones (:locked-bones context #{})
        _ (require-value! (and (geometry/finite-number? tolerance) (pos? tolerance)) "Mirror tolerance must be positive and finite." {})
        mapped-bones (into #{} (comp (mapcat #((:weights mesh) %)) (map #(get bone-map (:bone %) (:bone %)))) selection)
        _ (validate-context! context mapped-bones)
        transfers (mapv (fn [index]
                          (let [source (points index) reflected (assoc source coordinate (- (* 2.0 origin) (source coordinate)))
                                matches (into [] (keep-indexed (fn [index point] (when (<= (distance point reflected) tolerance) index))) points)]
                            (require-value! (= 1 (count matches)) "Each mirrored vertex needs one unique symmetric target." {:vertex index :matches matches})
                            [index (first matches)])) (sort selection))
        weights (reduce (fn [weights [source-index target-index]]
                          (let [source ((:weights mesh) source-index) target ((:weights mesh) target-index)
                                mapped (mapv #(assoc % :bone (get bone-map (:bone %) (:bone %))) source)
                                _ (require-value! (= (count mapped) (count (into #{} (map :bone) mapped))) "Mirror bone mapping cannot merge distinct influences." {:vertex source-index})
                                locked (filterv #(contains? locked-bones (:bone %)) target)
                                free (filterv #(not (contains? locked-bones (:bone %))) mapped)
                                free (if (pos? (count free)) free (filterv #(not (contains? locked-bones (:bone %))) target))
                                updated (if (>= (transduce (map :weight) + 0.0 locked) (- 1.0 1.0e-9))
                                          target
                                          (normalize-locked (into locked (map (fn [influence]
                                                                               (let [[x y] (bind-point (points target-index) (:bone influence) context)]
                                                                                 (assoc influence :x x :y y)))) free) locked-bones))]
                            (assoc weights target-index updated))) (:weights mesh) transfers)]
    (result (assoc mesh :weights weights) (into #{} (map second) transfers) (identity-map mesh) :bone-identity)))

(defn- reset-result [mesh]
  (assoc (result (-> mesh
                     (dissoc :weights :spine-export-hull :spine-export-edges)
                     (assoc :spine-topology-edited true)) #{} [] :reset)
         :clear-weights true :clear-deforms true))

(defn- validate-bounds! [[x0 y0 x1 y1 :as bounds]]
  (require-value! (and (= 4 (count bounds)) (coll/every? geometry/finite-number? bounds) (< x0 x1) (< y0 y1))
                  "Image bounds need finite, increasing XY corners." {}))

(defn reset-vertices
  "Replace geometry with four image corners. Bounds are in slot-local space.
  The caller must delete every affected FFD track, as signaled by :reset."
  [attachment [x0 y0 x1 y1 :as bounds]]
  (validate-bounds! bounds)
  (reset-result (assoc attachment :type "mesh" :vertices [x0 y0 x1 y0 x1 y1 x0 y1]
                                 :uvs [0.0 1.0 1.0 1.0 1.0 0.0 0.0 0.0]
                                 :triangles [0 1 2 0 2 3] :constrained-edges [])))

(defn generate
  "Subdivide every face into four, sharing edge midpoints. Repeated calls
  refine a rectangle into a regular grid, retaining all original vertices,
  UVs and manual edges. Like Spine Generate, weights and FFD are cleared."
  [mesh {:keys [iterations max-vertices] :or {iterations 1 max-vertices 4096}}]
  (validate-mesh! mesh)
  (require-value! (and (integer? iterations) (<= 1 iterations 8)
                       (integer? max-vertices) (<= 4 max-vertices 65536))
                  "Generate needs 1–8 iterations and a vertex limit of 4–65536." {})
  (let [updated
        (reduce (fn [mesh _]
                  (let [triangles (faces mesh) table (edge-table triangles)
                        points (pairs (:vertices mesh)) uvs (pairs (:uvs mesh))
                        edges (vec (sort (into [] (coll/keys table))))
                        base (count points)
                        indices (into {} (map-indexed (fn [index edge] [edge (+ base index)])) edges)]
                    (require-value! (and (pos? (count triangles)) (coll/every? #(<= 1 (count %) 2) (coll/vals table))
                                         (coll/every? (fn [[a b c]] (> (abs (double (cross (points a) (points b) (points c)))) 1.0e-10)) triangles))
                                    "Generate needs nondegenerate manifold triangles." {})
                    (require-value! (<= (+ base (count edges)) max-vertices) "Generate would exceed the vertex limit." {:max-vertices max-vertices})
                    (require-value! (coll/every? #(contains? indices (canonical-edge %)) (:constrained-edges mesh))
                                    "A constrained edge is missing from the mesh." {})
                    (assoc mesh
                           :vertices (into (:vertices mesh) (mapcat (fn [[a b]] (mapv #(* 0.5 (+ %1 %2)) (points a) (points b)))) edges)
                           :uvs (into (:uvs mesh) (mapcat (fn [[a b]] (mapv #(* 0.5 (+ %1 %2)) (uvs a) (uvs b)))) edges)
                           :triangles (into [] (mapcat (fn [[a b c]]
                                                        (let [ab (indices (canonical-edge [a b])) bc (indices (canonical-edge [b c])) ca (indices (canonical-edge [c a]))]
                                                          [a ab ca ab b bc ca bc c ab bc ca]))) triangles)
                           :constrained-edges (into [] (mapcat (fn [[a b :as edge]] (let [mid (indices (canonical-edge edge))] [[a mid] [mid b]]))) (:constrained-edges mesh)))))
                (dissoc mesh :weights) (range iterations))]
    (reset-result updated)))

(defn- dilate-mask [^booleans mask width height padding]
  (if (zero? padding)
    mask
    (let [horizontal (boolean-array (* width height)) result (boolean-array (* width height))]
      (dotimes [y height]
        (loop [x 0 count (long (transduce (map #(if (aget mask (+ % (* y width))) 1 0)) + 0 (range (min width (inc padding)))))]
          (when (< x width)
            (aset-boolean horizontal (+ x (* y width)) (pos? count))
            (recur (inc x) (long (+ count
                                   (if (and (< (+ x padding 1) width) (aget mask (+ x padding 1 (* y width)))) 1 0)
                                   (if (and (>= (- x padding) 0) (aget mask (+ (- x padding) (* y width)))) -1 0)))))))
      (dotimes [x width]
        (loop [y 0 count (long (transduce (map #(if (aget horizontal (+ x (* % width))) 1 0)) + 0 (range (min height (inc padding)))))]
          (when (< y height)
            (aset-boolean result (+ x (* y width)) (pos? count))
            (recur (inc y) (long (+ count
                                   (if (and (< (+ y padding 1) height) (aget horizontal (+ x (* (+ y padding 1) width)))) 1 0)
                                   (if (and (>= (- y padding) 0) (aget horizontal (+ x (* (- y padding) width)))) -1 0)))))))
      result)))

(defn- mask-outline [^booleans mask width height]
  (let [stride (inc width)
        edges (persistent!
                (reduce (fn [edges index]
                          (if-not (aget mask index)
                            edges
                            (let [x (mod index width) y (quot index width)
                                  point (+ x (* y stride))
                                  sides (cond-> []
                                          (or (zero? y) (not (aget mask (- index width)))) (conj [point (inc point)])
                                          (or (= x (dec width)) (not (aget mask (inc index)))) (conj [(inc point) (+ point stride 1)])
                                          (or (= y (dec height)) (not (aget mask (+ index width)))) (conj [(+ point stride 1) (+ point stride)])
                                          (or (zero? x) (not (aget mask (dec index)))) (conj [(+ point stride) point]))]
                              (reduce (fn [edges [from to]]
                                        (require-value! (not (contains? edges from)) "The alpha outline touches itself at a pixel corner. Separate the regions before tracing." {:kind :touching-boundary})
                                        (require-value! (< (count edges) 131072) "The alpha outline is too complex; increase the threshold or use a smaller source image." {:kind :complexity-limit})
                                        (assoc! edges from to)) edges sides)))) (transient {}) (range (* width height))))]
    (require-value! (pos? (count edges)) "The image is fully transparent at this alpha threshold." {:kind :transparent})
    (let [loops (loop [remaining edges loops []]
                  (if (zero? (count remaining))
                    loops
                    (let [start (first (sort (into [] (coll/keys remaining))))
                          contour (loop [point start visited #{} contour []]
                                    (if (contains? visited point)
                                      (do (require-value! (= start point) "The alpha outline is not a closed polygon." {:kind :invalid-boundary}) contour)
                                      (recur (get remaining point) (conj visited point) (conj contour point))))]
                      (recur (reduce dissoc remaining contour) (conj loops (mapv #(vector (mod % stride) (quot % stride)) contour))))))
          signed-area (fn [points]
                        (reduce (fn [area index]
                                  (let [[ax ay] (points index) [bx by] (points (mod (inc index) (count points)))]
                                    (+ area (- (* ax by) (* bx ay))))) 0.0 (range (count points))))
          outer (filterv #(pos? (signed-area %)) loops)
          holes (filterv #(neg? (signed-area %)) loops)]
      (require-value! (= 1 (count outer)) "The image contains separate opaque regions. Trace them as separate attachments." {:kind :multiple-components :components (count outer)})
      (require-value! (zero? (count holes)) "A single Spine mesh cannot contain holes. Split this image into separate attachments before tracing." {:kind :holes :holes (count holes)})
      (let [points (first outer) n (count points)]
        (into [] (keep (fn [index]
                        (when-not (zero? (cross (points (mod (dec index) n)) (points index) (points (mod (inc index) n))))
                          (points index)))) (range n))))))

(defn- simplify-outline [points tolerance]
  (let [n (count points)
        original (vec points)]
    (loop [indices (vec (range n)) cursor 0 unchanged 0]
      (let [size (count indices)]
        (if (or (<= size 3) (>= unchanged size))
          (mapv original indices)
          (let [cursor (mod cursor size)
                before (indices (mod (dec cursor) size)) current (indices cursor) after (indices (mod (inc cursor) size))
                a (original before) b (original current) c (original after)
                max-distance (loop [index (mod (inc before) n) maximum 0.0]
                               (if (= index after) maximum
                                   (recur (mod (inc index) n) (double (max maximum (segment-distance (original index) a c))))))
                safe (and (<= (cross a b c) 0.0) (<= max-distance tolerance)
                          (not (coll/any? #(and (not (contains? #{before current after} %)) (on-segment? a c (original %))) indices))
                          (not (coll/any? (fn [index]
                                            (let [u (indices index) v (indices (mod (inc index) size))]
                                              (and (not (contains? #{before current after} u))
                                                   (not (contains? #{before current after} v))
                                                   (segments-cross? a c (original u) (original v))))) (range size))))]
            (if-not safe
              (recur indices (long (inc cursor)) (inc unchanged))
              (recur (into (subvec indices 0 cursor) (subvec indices (inc cursor))) (long (max 0 (dec cursor))) 0))))))))

(defn trace-alpha
  "Trace one opaque component of PNG-style alpha data, without JavaFX. Alpha
  is an unsigned 0–255 vector or a byte array. Pixel coordinates start at the
  top-left; bounds map them to slot-local Y-up space, defaulting to centered
  image size. Simplification conservatively removes small concave steps, never
  cutting opaque pixels. Padding is an integer square dilation clipped to the
  image. Transparent images, disjoint regions and holes fail explicitly both
  before and after padding. All weights and FFD are cleared by the result."
  [attachment {:keys [width height alpha]} {:keys [threshold simplify padding bounds max-vertices]
                                            :or {threshold 16 simplify 1.0 padding 0 max-vertices 4096}}]
  (require-value! (and (integer? width) (integer? height) (pos? width) (pos? height) (<= (* width height) 16777216)
                       (or (bytes? alpha) (and (vector? alpha) (coll/every? #(and (integer? %) (<= 0 % 255)) alpha)))
                       (= (* width height) (count alpha))
                       (integer? threshold) (<= 0 threshold 255)
                       (geometry/finite-number? simplify) (not (neg? simplify))
                       (integer? padding) (<= 0 padding 128)
                       (integer? max-vertices) (<= 4 max-vertices 16384))
                  "Trace needs alpha bytes for a valid image, threshold 0–255, nonnegative simplification, padding 0–128 and a vertex limit." {})
  (let [bounds (or bounds [(* -0.5 width) (* -0.5 height) (* 0.5 width) (* 0.5 height)])
        _ (validate-bounds! bounds)
        mask (boolean-array (* width height))
        _ (dotimes [index (* width height)]
            (let [value (if (bytes? alpha) (bit-and 255 (aget ^bytes alpha index)) (nth alpha index))]
              (aset-boolean mask index (and (pos? value) (>= value threshold)))))
        original (mask-outline mask width height)
        outline (if (zero? padding) original (mask-outline (dilate-mask mask width height padding) width height))
        _ (require-value! (<= (count outline) 16384) "The alpha boundary has too many corners for interactive tracing." {:kind :complexity-limit})
        outline (simplify-outline outline simplify)
        _ (require-value! (<= (count outline) max-vertices) "The traced outline exceeds the vertex limit; increase simplification." {:kind :vertex-limit :vertices (count outline) :max-vertices max-vertices})
        [x0 y0 x1 y1] bounds
        uvs (mapv (fn [[x y]] [(/ (double x) width) (/ (double y) height)]) outline)
        points (mapv (fn [[u v]] [(+ x0 (* u (- x1 x0))) (- y1 (* v (- y1 y0)))]) uvs)
        mesh (assoc attachment :type "mesh" :vertices (into [] cat points) :uvs (into [] cat uvs)
                               :triangles (triangulate-polygon points (vec (range (count points)))) :constrained-edges [])]
    (assoc (reset-result mesh) :trace {:threshold threshold :simplify simplify :padding padding :components 1 :holes 0})))
