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

(require '[clojure.data.json :as json]
         '[clojure.java.io :as io]
         '[util.coll :as coll])
(require '[editor.skeletal-animation-data :as data] '[editor.skeletal-animation-slots :as slots] '[editor.skeletal-animation-geometry :as geometry])
(let [[input output] *command-line-args*
      doc (data/read-json (slurp input))]
  (.mkdirs (io/file output))
  (spit (str output "/input.json") (json/write-str {:fixture (.getCanonicalPath (io/file input)) :skin (:active-skin doc "default") :fps 60 :tail "05_tail"}))
  (spit (str output "/export.json") (data/export-spine-json doc))
  (spit (str output "/actual.json")
        (json/write-str
          (mapv (fn [{:keys [id duration]}]
                  {:animation id :frames
                   (mapv (fn [time]
                           (let [pose (data/evaluate-pose-with-slots doc id time slots/sample-slots)
                                 sampled (slots/sample-slots doc id time)
                                 slot (coll/first-where #(= "05_tail" (:id %)) (:slots sampled))
                                 attachment (:attachment slot)]
                             {:time time :bones (:transforms pose)
                              :draw-order (mapv :id (:slots sampled))
                              :attachments (coll/into-> (:slots sampled) {} (map (fn [s] [(:id s) (last (:preview-attachment-key s))])))
                              :vertices (geometry/mesh-world-vertices attachment (get (:transforms pose) (:bone slot)) (:transforms pose) (get (:deforms sampled) "05_tail" []))}))
                         (conj (mapv #(/ % 60.0) (range (long (Math/ceil (* duration 60.0))))) duration))}) (:animations doc)))))
(shutdown-agents)

(System/exit 0)
