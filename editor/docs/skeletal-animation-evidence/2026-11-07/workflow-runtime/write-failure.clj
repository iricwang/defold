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

(require '[clojure.java.io :as io])
(doseq [file ["skeletal_animation_geometry" "skeletal_animation_sequence" "skeletal_animation_skin" "skeletal_animation_events"
              "skeletal_animation_data" "skeletal_animation_slots" "skeletal_animation_io"]]
  (load-file (str "src/clj/editor/" file ".clj")))
(let [root (io/file "/tmp/defold-spine-workflow-runtime/write-failure")
      target (io/file root "limited.spine")
      base (editor.skeletal-animation-data/new-document "write-failure")
      bone (first (:bones base))
      doc (assoc base :bones (into [bone] (mapv (fn [i] (assoc bone :id (str "bone-" (java.util.UUID/randomUUID)) :parent "root" :x (double i))) (range 1000))))
      error (try (editor.skeletal-animation-io/write-independent-project! doc root target) nil (catch java.io.IOException e (.getMessage e)))
      result {:error error :target-exists (.exists target) :remaining-files (mapv #(.getName %) (.listFiles root))}]
  (prn result)
  (assert error "Expected a real OS write failure")
  (assert (not (:target-exists result)) "Failed write published target")
  (assert (zero? (count (:remaining-files result))) "Failed write left staging bytes"))
(System/exit 0)
