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

(ns editor.skeletal-animation-auto-rig
  "Editor-only, preview-first flexible-part rig generation."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-geometry :as geo]
            [editor.skeletal-animation-mesh :as mesh]
            [editor.skeletal-animation-slots :as slots]
            [editor.skeletal-animation-smart-mesh :as smart-mesh]
            [util.coll :as coll]))

(def defaults {"bone-count" 8 "control-count" 3 "reverse" false})

(defn- distance [[ax ay] [bx by]]
  (Math/hypot (- bx ax) (- by ay)))

(defn- lerp [a b t]
  (mapv #(+ %1 (* t (- %2 %1))) a b))

(defn- resample [points n]
  (let [lengths (reduce (fn [values [a b]] (conj values (+ (peek values) (distance a b)))) [0.0] (partitionv 2 1 points))
        total (peek lengths)]
    (mapv (fn [i]
            (let [distance (* total (/ i (double (dec n))))
                  segment (or (coll/first-where #(>= (lengths (inc %)) distance) (range (dec (count points)))) (- (count points) 2))
                  span (- (lengths (inc segment)) (lengths segment))]
              (lerp (points segment) (points (inc segment)) (if (pos? span) (/ (- distance (lengths segment)) span) 0.0))))
          (range n))))

(defn- world-at-uv [attachment worlds slot-transform uv]
  (let [uvs (vec (partitionv 2 (:uvs attachment)))
        vertices (vec (partitionv 2 (geo/mesh-world-vertices attachment slot-transform worlds nil)))
        [px py] uv
        hit (reduce (fn [_ [a b c]]
                      (let [[ax ay] (uvs a) [bx by] (uvs b) [cx cy] (uvs c)
                            det (- (* (- by cy) (- ax cx)) (* (- bx cx) (- ay cy)))]
                        (when (> (abs det) 1.0e-12)
                          (let [u (/ (+ (* (- by cy) (- px cx)) (* (- cx bx) (- py cy))) det)
                                v (/ (+ (* (- cy ay) (- px cx)) (* (- ax cx) (- py cy))) det)
                                w (- 1.0 u v)]
                            (when (>= (min u v w) -1.0e-7)
                              (reduced (mapv #(+ (* u %1) (* v %2) (* w %3)) (vertices a) (vertices b) (vertices c))))))))
                    nil (partitionv 3 (:triangles attachment)))]
    (or hit (throw (ex-info "骨骼中轴超出网格，请提高轮廓精度。" {:uv uv})))))

(defn- add-internal-points [attachment points]
  (reduce (fn [topology point]
            (if (coll/any? #(< (distance point %) 1.0e-5) (partitionv 2 (:uvs topology)))
              topology
              (:mesh (mesh/insert-vertex topology point))))
          {:type "mesh" :vertices (:uvs attachment) :uvs (:uvs attachment)
           :triangles (:triangles attachment) :constrained-edges (:constrained-edges attachment)} points))

(defn generate
  "Pure proposal. Keep existing bones/animations; replace only the selected
  attachment binding. New rig names cannot collide with an existing rig."
  [traced slot-id guide settings]
  (let [n (get settings "bone-count" 8) c (get settings "control-count" 3)
        _ (when-not (and (number? n) (== n (Math/rint (double n))) (<= 2 n 32)
                         (number? c) (== c (Math/rint (double c))) (<= 2 c 8))
            (throw (ex-info "骨骼数量应为 2–32，控制点数量应为 2–8。" {})))
        n (long n) c (long c)
        [width height] (get guide "size")
        centerline (cond-> (get guide "centerline") (get settings "reverse") (-> reverse vec))
        pixel-line (mapv (fn [[u v]] [(* u width) (* v height)]) centerline)
        sampled (resample pixel-line (inc n))
        base-controls (resample pixel-line c)
        controls (or (get settings "control-points") (mapv (fn [[x y]] [(/ x width) (/ y height)]) base-controls))
        _ (when-not (and (= c (count controls))
                         (coll/every? #(and (= 2 (count %)) (coll/every? (fn [v] (and (geo/finite-number? v) (<= 0.0 v 1.0))) %)) controls))
            (throw (ex-info "控制点必须位于图片范围内。" {})))
        ;; Keep the sampled silhouette curve; controls add a smooth distributed
        ;; offset instead of replacing a curved tail with a straight chord.
        adjusted (mapv (fn [i point]
                         (let [t (* (dec c) (/ i (double n))) j (min (- c 2) (long t)) f (- t j)
                               offsets (mapv (fn [index]
                                               (mapv - (mapv * (controls index) [width height]) (base-controls index))) [j (inc j)])]
                           (mapv + point (lerp (first offsets) (second offsets) f)))) (range (inc n)) sampled)
        chain-uvs (mapv (fn [[x y]] [(/ x width) (/ y height)]) adjusted)
        doc (:document traced)
        slot (coll/first-where #(= slot-id (:id %)) (:slots doc))
        attachment (:attachment slot)
        ;; Mesh refinement follows the original centerline even when artists
        ;; move controls, so topology remains inside the silhouette.
        interior (mapv (fn [[x y]] [(/ x width) (/ y height)]) (resample pixel-line (+ 1 (* 2 n))))
        refined (or (:rig-refined traced) (smart-mesh/refine-trace traced slot-id (add-internal-points attachment interior)))
        doc (:document refined)
        slot (coll/first-where #(= slot-id (:id %)) (:slots doc))
        attachment (:attachment slot)
        worlds (:transforms (data/evaluate-pose-with-slots doc nil 0.0 slots/sample-slots))
        parent (:bone slot)
        parent-world (worlds parent)
        ;; The original attachment's image mapping can be non-rigid. Sample each
        ;; adjusted point on its surface; reject points outside.
        joints (mapv #(geo/inverse-transform-point parent-world (world-at-uv attachment worlds parent-world %)) chain-uvs)
        control-positions (mapv #(geo/inverse-transform-point parent-world (world-at-uv attachment worlds parent-world %)) controls)
        used (coll/into-> (get (when-let [source (:spine-source doc)] (json/read-str source)) "bones")
               (coll/into-> (into (into (:bones doc) (:slots doc)) (:path doc)) #{} (map :id))
               (map #(get % "name")))
        prefix (loop [i 1]
                 (let [prefix (str slot-id "_auto" i "_")]
                   (if (coll/any? #(string/starts-with? % prefix) used) (recur (inc i)) prefix)))
        ids (mapv #(str prefix "bone" (inc %)) (range n))
        control-ids (mapv #(str prefix "control" (inc %)) (range c))
        ;; All generated names are fresh, including preserved inactive source
        ;; bones. Append the complete batch, then validate once at the boundary.
        ;; Per-joint add-bone revalidated every mesh and animation N times.
        new-bones (into (mapv (fn [i id]
                                (let [[x y :as a] (joints i) [ex ey :as b] (joints (inc i))]
                                  {:id id :parent parent :x x :y y :length (distance a b)
                                   :rotation (Math/toDegrees (Math/atan2 (- ey y) (- ex x)))})) (range n) ids)
                        (mapv (fn [id [x y]] {:id id :parent parent :x x :y y :length 0.0 :spine-color "ffcc66ff"}) control-ids control-positions))
        doc (data/validate-document
              (update doc :bones into (mapv #(merge {:rotation 0.0 :scale-x 1.0 :scale-y 1.0 :shear-x 0.0 :shear-y 0.0} %) new-bones)))
        ;; Cubic segments are initially straight between joints. This preserves
        ;; setup bone lengths exactly while weighted controls bend the path.
        path-points (coll/into-> (partitionv 2 1 joints) [(first joints) (first joints)]
                      (mapcat (fn [[a b]] [(lerp a b (/ 1.0 3.0)) (lerp a b (/ 2.0 3.0)) b])))
        path-points (conj path-points (peek joints))
        rest-worlds (data/world-transforms (:bones doc))
        weights (mapv (fn [index point]
                        (let [t (* (dec c) (max 0.0 (min 1.0 (/ (dec index) (double (* 3 n))))))
                              j (min (- c 2) (long t)) f (- t j)
                              world (geo/transform-point parent-world point)]
                          (coll/into-> [[j (- 1.0 f)] [(inc j) f]] [] (keep (fn [[i weight]]
                                                                              (when (> weight 1.0e-10)
                                                                                (let [id (control-ids i) [x y] (geo/inverse-transform-point (rest-worlds id) world)]
                                                                                  {:bone id :x x :y y :weight weight})))))))
                      (range (count path-points)) path-points)
        path-id (str prefix "path")
        doc (data/add-slot doc {:id path-id :bone parent :attachment {:name path-id :type "path" :closed false :constant-speed true
                                                                      :vertices (coll/into-> path-points [] cat) :weights weights
                                                                      :lengths (mapv double (range 1 (inc n)))}})
        doc (data/add-path doc {:id (str prefix "follow") :target path-id :bones ids :position-mode "fixed" :position 0.0
                                :spacing-mode "length" :spacing 0.0 :rotate-mode "chain" :rotation 0.0 :mix-x 1.0 :mix-y 1.0 :mix-rotate 1.0})
        posed (:transforms (data/evaluate-pose-with-slots doc nil 0.0 slots/sample-slots))
        old-world (geo/mesh-world-vertices attachment parent-world worlds nil)
        flat (assoc attachment :weights nil :vertices (coll/into-> (partitionv 2 old-world) [] (mapcat #(geo/inverse-transform-point parent-world %))))
        bound (:mesh (mesh/bind-weights flat ids {:slot-transform parent-world :bone-transforms posed :bones (:bones doc)} {:max-influences 4 :falloff 2.0}))
        doc (data/update-slot doc slot-id {:attachment bound})
        doc (assoc-in doc [:auto-rigs prefix] {:slot slot-id :settings settings :bones ids :controls control-ids})]
    (data/import-spine-json (json/write-str (data/export-spine-data doc)))
    {:refined-base refined :document doc :preview bound :rig-guide chain-uvs :rig-controls controls :rig-bones ids :rig-control-ids control-ids
     :report (assoc (:report refined) "vertices" (quot (count (:uvs bound)) 2) "bones" n "controls" c)}))

(defn proposer
  "Per-preview caches keep count/control edits from restarting image analysis."
  [document project-root slot-id]
  (let [slot (coll/first-where #(= slot-id (:id %)) (:slots document))
        file (io/file project-root (string/replace (get-in slot [:attachment :path]) #"^/" ""))
        cache (atom {})
        trace-proposal (smart-mesh/trace-proposer document project-root slot-id)]
    (fn propose-auto-rig [settings]
      (let [result-key [:result settings (.lastModified file) (.length file)]]
        (if-let [result (get @cache result-key)]
          result
          (let [trace-settings (select-keys settings ["detail" "concavity" "refinement" "uniform" "uniform-percent" "alpha" "padding"])
                alpha (get trace-settings "alpha" 8.0)
                guide-key [:guide alpha (.lastModified file) (.length file)]
                trace-key [:trace trace-settings (.lastModified file) (.length file)]
                guide-work (or (get @cache guide-key)
                               (future (let [result (smart-mesh/run-tool! project-root {"operation" "rig-guide" "file" (str file) "alpha" alpha "tool" (:smart-mesh-tool document)})]
                                         result)))
                traced (or (get @cache trace-key)
                           (let [result (trace-proposal trace-settings)]
                             (swap! cache assoc trace-key result) result))
                guide (if (future? guide-work) @guide-work guide-work)
                refine-key [:refine trace-key (get settings "bone-count" 8)]
                result (generate (assoc traced :rig-refined (get @cache refine-key)) slot-id guide (merge defaults settings))]
            ;; Retain only the current guide/trace/refinement and completed
            ;; proposal. Continuous slider edits must not grow a document cache.
            (reset! cache {guide-key guide trace-key traced refine-key (:refined-base result)
                           result-key (dissoc result :refined-base)})
            (dissoc result :refined-base)))))))
