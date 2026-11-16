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

;; Creates a separate path-control example; never overwrites its IK input.
(require '[clojure.data.json :as json]
         '[clojure.java.io :as io]
         '[editor.skeletal-animation-data :as data]
         '[editor.skeletal-animation-geometry :as geo]
         '[editor.skeletal-animation-slots :as slots]
         '[util.coll :as coll])
(let [[input output] *command-line-args*
      _ (when (= (.getCanonicalPath (io/file input)) (.getCanonicalPath (io/file output)))
          (throw (ex-info "Write the path example to a separate file." {})))
      original (data/read-json (slurp input))
      before (:transforms (data/evaluate-pose-with-slots original nil 0.0 slots/sample-slots))
      chain ["bone1" "bone3" "bone4" "bone5" "bone6" "bone7" "bone8"]
      members (set chain)
      by-id (coll/into-> (:bones original) {} (map (juxt :id identity)))
      joints (conj (mapv #(geo/transform-point (before %) [0.0 0.0]) chain)
                   (geo/transform-point (before "bone8") [(:length (by-id "bone8")) 0.0]))
      parent "tail"
      parent-world (before parent)
      local (fn to-parent [point] (geo/inverse-transform-point parent-world point))
      anchors [(first joints) (nth joints 3) (peek joints)]
      controls ["tail_path_root" "tail_path_mid" "tail_path_tip"]
      source (-> (json/read-str (:spine-source original))
                 (assoc "animations" {} "ik" [] "path" []))
      doc (-> original
              (update :bones #(filterv (fn [bone] (not= "tail_ik_target" (:id bone))) %))
              (assoc :name "05_tail_path_controls" :ik [] :path [] :animations []
                     :spine-source (json/write-str source))
              (update :bones (fn [bones]
                               (mapv (fn [bone]
                                       (if-not (members (:id bone)) bone
                                               (let [[a b c d x y] (before (:id bone))
                                                     [lx ly] (local [x y])
                                                     [ex ey] (local [(+ x a) (+ y b)])
                                                     [fx fy] (local [(+ x c) (+ y d)])
                                                     angle (Math/toDegrees (Math/atan2 (- ey ly) (- ex lx)))
                                                     y-angle (Math/toDegrees (Math/atan2 (- fy ly) (- fx lx)))]
                                                 (assoc bone :parent parent :x lx :y ly :rotation angle
                                                        :scale-x (Math/hypot (- ex lx) (- ey ly))
                                                        :scale-y (Math/hypot (- fx lx) (- fy ly))
                                                        :shear-x 0.0 :shear-y (- y-angle angle 90.0))))) bones))))
      doc (reduce (fn [doc [id point]]
                    (let [[x y] (local point)]
                      (data/add-bone doc {:id id :parent parent :x x :y y :length 0.0
                                          :spine-color (if (= id "tail_path_mid") "70dbffff" "ffcc66ff")})))
                  doc (mapv vector controls anchors))
      ;; Interpolate the original chain with cubic Hermite segments. Open Spine
      ;; paths include an unused point before and after the actual curve.
      tangent (fn joint-tangent [i]
                (mapv #(* 0.5 (- %1 %2)) (joints (min (dec (count joints)) (inc i)))
                      (joints (max 0 (dec i)))))
      points (coll/into-> (range (dec (count joints))) [(first joints) (first joints)]
                          (mapcat (fn [i]
                                    [(mapv #(+ %1 (/ %2 3.0)) (joints i) (tangent i))
                                     (mapv #(- %1 (/ %2 3.0)) (joints (inc i)) (tangent (inc i)))
                                     (joints (inc i))])))
      points (conj points (peek joints))
      worlds (data/world-transforms (:bones doc))
      weights (mapv (fn [index point]
                      (let [t (max 0.0 (min 1.0 (/ (double (dec index)) (* 3.0 (dec (count joints))))))
                            fractions [(* (- 1.0 t) (- 1.0 t)) (* 2.0 t (- 1.0 t)) (* t t)]]
                        (coll/into-> (mapv vector controls fractions) []
                                     (keep (fn [[bone weight]]
                                             (when (pos? weight)
                                               (let [[x y] (geo/inverse-transform-point (worlds bone) point)]
                                                 {:bone bone :weight weight :x x :y y})))))))
                    (vec (range (count points))) points)
      doc (data/add-slot doc {:id "tail_path" :bone parent
                              :attachment {:name "tail_path" :type "path" :closed false :constant-speed true
                                           :vertices (coll/into-> points [] (mapcat local)) :weights weights
                                           :lengths (mapv double (range 1 (count joints)))}})
      doc (data/add-path doc {:id "tail_follow_path" :target "tail_path" :bones chain
                              :position-mode "fixed" :position 0.0 :spacing-mode "length" :spacing 0.0
                              :rotate-mode "chain" :rotation 0.0 :mix-x 1.0 :mix-y 1.0 :mix-rotate 1.0})
      after (:transforms (data/evaluate-pose-with-slots doc nil 0.0 slots/sample-slots))
      ;; Keep each mesh influence's rest world position while changing the rig.
      doc (update doc :slots
                  (fn [items]
                    (mapv (fn [slot]
                            (if-not (= "05_tail" (:id slot)) slot
                                    (update-in slot [:attachment :weights]
                                               (fn [weights]
                                                 (mapv (fn [influences]
                                                         (mapv (fn [{:keys [bone x y] :as influence}]
                                                                 (let [point (geo/transform-point (before bone) [x y])
                                                                       [x y] (geo/inverse-transform-point (after bone) point)]
                                                                   (assoc influence :x x :y y))) influences)) weights))))) items)))
      doc (data/add-animation doc {:id "tail_path_demo" :duration 2.0})
      doc (reduce (fn [doc [id phase amplitude]]
                    (reduce (fn [doc i]
                              (let [t (/ i 8.0)
                                    bone (coll/first-where #(= id (:id %)) (:bones doc))
                                    wave (- (Math/sin (+ phase (* Math/PI t))) (Math/sin phase))]
                                (-> doc
                                    (data/set-keyframe "tail_path_demo" id "x" t (+ (:x bone) (* amplitude wave)) "linear")
                                    (data/set-keyframe "tail_path_demo" id "y" t (+ (:y bone) (* 0.6 amplitude (Math/sin (* Math/PI t)))) "linear")))) doc (range 17)))
                  doc [["tail_path_mid" 0.5 12.0] ["tail_path_tip" 0.0 24.0]])]
  (spit output (json/write-str (data/validate-document doc)))
  (println "Created" output))
(shutdown-agents)
