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

(require '[cljfx.plorer :as p]
         '[clojure.data.json :as json]
         '[clojure.edn :as edn]
         '[dynamo.graph :as g]
         '[editor.handler :as handler]
         '[editor.skeletal-animation-data :as data]
         '[editor.skeletal-animation-view :as view]
         '[editor.ui :as ui]
         '[util.coll :as coll])
(def perf-results (atom []))
(def perf-drag-events (Integer/getInteger "defold.benchmark.drag-events" 1))
(def perf-textured-grid (Boolean/getBoolean "defold.benchmark.textured-grid"))
(def perf-bean (java.lang.management.ManagementFactory/getThreadMXBean))
(defn perf-measure [name n f]
  (dotimes [i 15] (f i))
  (let [thread (.threadId (Thread/currentThread))
        bytes (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean perf-bean thread)
        samples (mapv (fn [i] (let [t (System/nanoTime)] (f (+ i 15)) (/ (- (System/nanoTime) t) 1.0e6))) (range n))
        allocated (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean perf-bean thread) bytes)
        ordered (vec (sort samples))
        result {:name name :samples n :median-ms (ordered (quot n 2)) :p95-ms (ordered (min (dec n) (long (* n 0.95))))
                :max-ms (peek ordered) :mean-ms (/ (reduce + samples) n) :allocated-bytes-per-op (/ allocated n)}]
    (swap! perf-results conj result) (prn result)))
(defn perf-grid [n bone-count]
  (let [vertices (coll/into-> (range n) [] (mapcat (fn [y] (coll/into-> (range n) [] (mapcat (fn [x] [(* 4.0 x) (* 4.0 y)]))))))
        triangles (coll/into-> (range (dec n)) [] (mapcat (fn [y] (coll/into-> (range (dec n)) [] (mapcat (fn [x] (let [a (+ x (* y n)) b (inc a) c (+ a n) d (inc c)] [a b c b d c])))))))
        bones (mapv (fn [i] {:id (str "bone" i) :parent "root" :length 10.0 :x (* 5.0 i) :y 0.0 :rotation 0.0 :scale-x 1.0 :scale-y 1.0 :shear-x 0.0 :shear-y 0.0}) (range bone-count))
        mesh {:type "mesh" :path "/missing.png" :width (* 4 n) :height (* 4 n) :vertices vertices :triangles triangles
              :uvs (mapv #(/ % (* 4.0 (dec n))) vertices)
              :weights (mapv (fn [[x y]] [{:bone "root" :weight 0.5 :x x :y y} {:bone "bone0" :weight 0.5 :x x :y y}]) (partitionv 2 vertices))}]
    (-> (data/new-document (str "grid-" n))
        (update :bones into bones)
        (data/add-slot {:id "mesh" :bone "root" :attachment mesh})
        (data/set-keyframe "default" "bone0" "rotation" 0.0 0.0 "linear")
        (data/set-keyframe "default" "bone0" "rotation" 1.0 25.0 "linear"))))
(ui/run-now
  (let [root (p/one (ui/main-stage) "#skeletal-workspace") ctx (ui/user-data root :editor.skeletal-animation-view/context)
        state (:state ctx) scene (.getScene root) canvas (:canvas ctx)
        baseline-state @state
        original (g/node-value (:resource-node ctx) :document)
        tail-document (edn/read-string (slurp "FIXTURE_PATH"))
        grid (cond-> (perf-grid 32 128)
               perf-textured-grid (assoc-in [:slots 0 :attachment :path]
                                            (get-in (coll/first-where #(= "05_tail" (:id %)) (:slots tail-document)) [:attachment :path])))
        fixtures [["tail" tail-document "05_tail" "attack" "tail_ik_target"]
                  ["grid1024" grid "mesh" "default" "bone127"]]]
    (doseq [[label doc slot animation bone-id] fixtures]
      (let [reset-context (fn reset-context []
                            (g/transact {:undoable false} (g/set-property (:resource-node ctx) :document doc))
                            (reset! state (merge baseline-state {:mode "animate" :animation animation :time 0.3 :playing false :auto-key true
                                                                 :selected-kind :slot :selected slot :selected-slots #{slot} :selected-bones #{}
                                                                 :vertices #{} :tool "move" :coordinate-space "world" :auto-fit false :fitted true
                                                                 :show-images (or (= label "tail") perf-textured-grid) :zoom 1.0 :pan-x 100.0 :pan-y 250.0
                                                                 :draft nil :drag nil :pending-pose nil :last-document nil}))
                            (#'view/refresh! ctx 0.0))
            refresh (fn refresh [] (#'view/refresh! ctx (/ 1.0 60.0)) (.applyCss root) (.layout root))
            mouse-point (fn mouse-point [position]
                          (let [[x y] (#'view/screen ctx position)] (p/point canvas (/ x (.getWidth canvas)) (/ y (.getHeight canvas)))))
            move-drag (fn move-drag [name origin]
                        (swap! state assoc :pan-x (- (* 0.35 (.getWidth canvas)) (first origin)) :pan-y (+ (* 0.22 (.getHeight canvas)) (second origin)))
                        (refresh)
                        (let [hit (p/mouse-press! scene (mouse-point origin) :primary)]
                          (assert (identical? canvas hit) (str "Drag hit " (.getId hit) " instead of canvas: " name " dims=" [(.getWidth canvas) (.getHeight canvas)] " origin=" origin " screen=" (#'view/screen ctx origin) " state=" (select-keys @state [:pan-x :pan-y :zoom])))
                          (assert (:drag @state) (str "No drag: " name)))
                        (perf-measure (str label "/" name) 90
                                      (fn [i]
                                        (dotimes [event perf-drag-events]
                                          (let [t (+ i (/ (inc event) (double perf-drag-events)))]
                                            (p/mouse-move! scene (mouse-point (mapv + origin [(* 18.0 (Math/sin (* t 0.09))) (* 12.0 (Math/cos (* t 0.09)))])))))
                                        (refresh)))
                        (assert (:draft @state) (str "No draft: " name " " (select-keys @state [:selected :selected-kind :drag])))
                        (let [started (System/nanoTime)]
                          (let [[x y] (get-in @state [:drag :last-point])]
                            (p/mouse-release! scene (p/point canvas (/ x (.getWidth canvas)) (/ y (.getHeight canvas))) :primary))
                          (refresh)
                          (swap! perf-results update (dec (count @perf-results)) assoc :release-ms (/ (- (System/nanoTime) started) 1.0e6)))
                        (when (contains? @state :compatibility-document) (assert (= (#'view/document ctx) (:compatibility-document @state)) "Release did not refresh compatibility status")))]
        (reset-context)
        (when (and (= label "grid1024") perf-textured-grid)
          (let [image (#'view/image! ctx (get-in doc [:slots 0 :attachment :path]))]
            (assert (and image (not (.isError ^javafx.scene.image.Image image))) "Stress mesh texture did not load")))
        (perf-measure (str label "/idle-refresh") 120 (fn [_] (refresh)))
        (perf-measure (str label "/hover") 120 (fn [i] (p/mouse-move! scene (p/point canvas (+ 0.15 (* 0.02 (Math/sin i))) 0.2)) (refresh)))
        (reset-context)
        (swap! state assoc :playing true)
        (perf-measure (str label "/playback") 120 (fn [_] (refresh)))
        (reset-context)
        (perf-measure (str label "/scrub") 60 (fn [i] (swap! state assoc :time (/ (mod i 60) 60.0)) (refresh)))
        (refresh)
        (assert (= (:time @state) (peek (:inspector-token @state))) "Inspector did not settle after seeking")
        (reset-context)
        (let [point (first (:points (#'view/mesh-manipulator ctx doc)))] (move-drag "vertex-drag" point))
        (reset-context)
        (move-drag "mesh-drag" (:center (#'view/mesh-manipulator ctx doc)))
        (reset-context)
        (#'view/set-selection! ctx :bone bone-id)
        (let [origin (subvec (get-in (#'view/evaluated-pose ctx doc) [:transforms bone-id]) 4 6)] (move-drag "bone-drag" origin))
        (reset-context)
        (p/mouse-press! scene (p/point canvas 0.1 0.15) :middle)
        (assert (:selection-box @state) "Box-selection gesture did not begin")
        (perf-measure (str label "/box-selection") 90 (fn [i] (p/mouse-move! scene (p/point canvas (+ 0.5 (* 0.1 (Math/sin (* 0.1 i)))) 0.3)) (refresh)))
        (p/mouse-release! scene (p/point canvas 0.5 0.3) :middle)
        (reset-context)
        (swap! state assoc :mode "setup" :tool "weights" :brush-mode "add" :brush-radius 50.0 :brush-feather 0.5 :weight-bone (if (= label "tail") "tail" "bone0"))
        (let [slot (#'view/selected-slot ctx doc) point (subvec (get-in slot [:attachment :vertices]) 0 2)]
          (perf-measure (str label "/weight-brush") 30 (fn [_] (#'view/brush-document ctx doc (vec point) 0.1))))))
    (g/transact {:undoable false} (g/set-property (:resource-node ctx) :document original))
    (reset! state baseline-state)
    (spit "OUTPUT_PATH" (json/write-str {:measurement "FX thread CPU wall time incl event handling, refresh, CSS/layout; excludes GPU presentation" :textured-grid perf-textured-grid :drag-events-per-frame perf-drag-events :results @perf-results :disabled-handlers (count @@#'handler/throwing-handlers) :stopped-timers (count @@#'ui/stopped-timers)}))))
