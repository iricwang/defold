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
         '[editor.skeletal-animation-view :as view]
         '[editor.ui :as ui]
         '[util.coll :as coll])
(def perf-pulse-done (java.util.concurrent.CountDownLatch. 1))
(def perf-pulse-times (atom []))
(def perf-pulse-counter (atom 0))
(def perf-pulse-last (atom nil))
(def perf-pulse-context
  (ui/run-now
    (let [ctx (ui/user-data (p/one (ui/main-stage) "#skeletal-workspace") :editor.skeletal-animation-view/context)
          doc (edn/read-string (slurp "FIXTURE_PATH"))]
      (g/transact {:undoable false} (g/set-property (:resource-node ctx) :document doc))
      (swap! (:state ctx) assoc :mode "animate" :animation "attack" :time 0.0 :playing false :show-images true
             :selected-kind :slot :selected "05_tail" :selected-slots #{"05_tail"} :selected-bones #{} :auto-fit true :fitted false)
      (#'view/refresh! ctx 0.0)
      (.requestFocus (:canvas ctx))
      (p/key-tap! (.getScene (:root ctx)) :space)
      (assert (:playing @(:state ctx)))
      ctx)))
(def perf-pulse-timer
  (proxy [javafx.animation.AnimationTimer] []
    (handle [now]
      (let [i (swap! perf-pulse-counter inc)]
        (when (and (> i 60) @perf-pulse-last) (swap! perf-pulse-times conj (/ (- now @perf-pulse-last) 1.0e6)))
        (reset! perf-pulse-last now)
        (when (= i 240)
          (.stop ^javafx.animation.AnimationTimer this)
          (.countDown perf-pulse-done))))))
(ui/run-now (.start perf-pulse-timer))
(assert (.await perf-pulse-done 45 java.util.concurrent.TimeUnit/SECONDS) "Animation pulse benchmark timed out")
(ui/run-now
  (let [v (vec (sort @perf-pulse-times)) total (reduce + v)]
    (swap! (:state perf-pulse-context) assoc :playing false)
    (spit "OUTPUT_PATH" (json/write-str {:measurement "Real JavaFX animation pulses during visible textured tail playback; GPU presentation not instrumented"
                                         :frames (count v) :median-ms (v (quot (count v) 2)) :p95-ms (v (long (* 0.95 (count v))))
                                         :max-ms (peek v) :fps (/ (* 1000.0 (count v)) total)
                                         :loaded-images (transduce (filter identity) (completing (fn [n _] (inc n))) 0 (coll/vals @(:images perf-pulse-context)))
                                         :disabled-handlers (count @@#'handler/throwing-handlers)
                                         :stopped-timers (count @@#'ui/stopped-timers)}))))
