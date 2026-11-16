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

(require '[clojure.data.json :as json] '[clojure.java.io :as io])
(doseq [file ["skeletal_animation_geometry" "skeletal_animation_sequence" "skeletal_animation_skin" "skeletal_animation_events"
              "skeletal_animation_data" "skeletal_animation_slots" "skeletal_animation_io"]]
  (load-file (str "src/clj/editor/" file ".clj")))
(import '[java.awt.image BufferedImage] '[javax.imageio ImageIO])
(let [root "/tmp/defold-spine-workflow-runtime"
      project (io/file root "project")
      images (doto (io/file project "images") .mkdirs)]
  (doseq [[name color] [["gray" 0xff808080] ["grid" 0xff80ffff] ["tint" 0x99ff88cc]
                       ["pulse01" 0xffff3333] ["pulse02" 0xff33ff33] ["pulse03" 0xff3333ff]]]
    (let [image (BufferedImage. 32 32 BufferedImage/TYPE_INT_ARGB)]
      (dotimes [y 32] (dotimes [x 32] (.setRGB image x y (unchecked-int color))))
      (ImageIO/write image "png" (io/file images (str name ".png"))))))
(let [doc (assoc (editor.skeletal-animation-data/import-spine-json (slurp "/tmp/defold-spine-workflow-runtime/source.json")) :name "puppet")
      result (editor.skeletal-animation-io/export-project! doc "/tmp/defold-spine-workflow-runtime/project" "generated")]
  (spit "/tmp/defold-spine-workflow-runtime/exported.skeleton" (editor.skeletal-animation-data/write-json doc))
  (spit "/tmp/defold-spine-workflow-runtime/export-results.edn" (pr-str result))
  (spit "/tmp/defold-spine-workflow-runtime/editor-samples.json"
        (json/write-str (mapv (fn [t] {:time t :sample (editor.skeletal-animation-slots/sample-slots doc "default" t)}) [0.0 0.1 0.3 0.5 0.7 0.9])))
  (println "SPINE_WORKFLOW_FOUR_RESOURCES_EXPORTED" (dissoc result :files)))
(System/exit 0)
