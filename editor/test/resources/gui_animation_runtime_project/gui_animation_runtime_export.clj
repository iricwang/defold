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

(ns gui-animation-runtime-export
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [editor.gui-animation-data :as animation]
            [editor.gui-animation-io :as animation-io]))

(defn export! [root]
  (let [documents (into {} (map (fn [name] [name (animation/read-json (slurp (io/file root "authoring" (str name "_panthera.json"))))]))
                        ["features" "panel" "regression"])
        samples (into []
                      (mapcat (fn [name]
                                (let [document (get documents name)]
                                  (into []
                                        (mapcat (fn [clip]
                                                  (let [id (:animation_id clip)
                                                        duration (animation/animation-duration document id)
                                                        resolved (animation/resolve-preview document id #(animation/read-json (slurp (io/file root "authoring" (subs % 1)))))]
                                                    (into []
                                                          (comp (distinct)
                                                                (map (fn [time] {:source name :clip id :time time :values (animation/evaluate resolved id time)})))
                                                          [0.0 (* 0.17 duration) (* 0.4 duration) (* 0.5 duration) (* 0.73 duration) duration (* 0.25 duration) 0.0]))))
                                        (get-in document [:data :animations])))))
                      ["features" "regression"])]
    (doseq [[basename document] documents
            format [:json :lua]]
      (animation-io/write-document-file! (io/file root (str basename "_panthera." (name format))) format document))
    (spit (io/file root "expected.json") (json/write-str samples :escape-unicode false))
    (spit (io/file root "warp_expected.json")
          (json/write-str (mapv #(get-in (animation/evaluate (get documents "regression") "nested_warp" (/ (double %) 1000.0)) ["box" "position_x"])
                               (range 1001))))
    {:samples (count samples)}))
