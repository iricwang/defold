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

(javafx.application.Platform/startup (fn []))
(javafx.application.Platform/setImplicitExit false)
(require '[clojure.edn :as edn] '[clojure.data.json :as json]
         '[editor.skeletal-animation-drag-test :as drag]
         '[editor.skeletal-animation-view :as view]
         '[editor.skeletal-animation-data :as data]
         '[editor.skeletal-animation-geometry :as geometry]
         '[editor.ui :as ui] '[dynamo.graph :as g] '[internal.system :as system])
(binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
  (ui/run-now
    (let [doc (edn/read-string (slurp "/tmp/spine-mesh-transform/live-document.edn"))
          context (#'drag/context doc {:mode "animate" :animation "attack" :time 0.5 :selected "05_tail" :selected-kind :slot :selected-bones #{} :selected-slots #{"05_tail"} :tool "rotate"})
          frame (#'view/mesh-manipulator context doc)
          [cx cy] (:pivot frame)
          start [(+ cx 160.0) cy] end [(+ cx (* 160.0 (Math/cos 0.3))) (+ cy (* 160.0 (Math/sin 0.3)))]]
      (swap! (:state context) assoc :zoom 0.3 :pan-x (- 350.0 (* 0.3 cx)) :pan-y (+ 250.0 (* 0.3 cy)))
      (#'drag/drag! context start end)
      (let [after (g/node-value (:resource-node context) :document)
            actual (:points (#'view/mesh-manipulator context after))
            transform (#'view/mesh-transform-matrix frame "rotate" start end nil false)
            expected (mapv #(geometry/transform-point transform %) (:points frame))
            error (apply max (mapcat (fn [a b] (mapv #(abs (double %)) (mapv - a b))) actual expected))]
        (assert (< error 1.0e-4) (str "Whole mesh rotation error " error))
        (assert (= (:bones doc) (:bones after)))
        (assert (= (:slots doc) (:slots after)))
        (g/undo! :undo/global)
        (assert (= doc (g/node-value (:resource-node context) :document)))
        (g/redo! :undo/global)
        (assert (= after (g/node-value (:resource-node context) :document)))
        (swap! (:state context) assoc :tool "scale" :vertices #{0 1 2})
        (let [selection-frame (#'view/mesh-manipulator context after)
              scaled (#'view/change-mesh-value context after selection-frame "scale-x" 1.1)]
          (#'view/commit-pose! context scaled)
          (assert (= (:bones doc) (:bones scaled)))
          (assert (= (:slots doc) (:slots scaled)))
          (spit "/tmp/spine-mesh-transform/edited-tail.skeleton" (data/write-json scaled))
          (spit "/tmp/spine-mesh-transform/tail-interaction.json"
                (json/write-str {:rotation-radians 0.3 :world-error error :subset [0 1 2] :scale-x 1.1
                                 :bind-data-preserved true :undo-redo true :source-unchanged true})))))))
(System/exit 0)
