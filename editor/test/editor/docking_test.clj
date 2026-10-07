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

(ns editor.docking-test
  (:require [clojure.test :refer [deftest is]]
            [editor.docking :as docking]))

;; Rejects missing, duplicate and unknown panels in saved layouts before creating JavaFX nodes.
(deftest saved-layout-validation
  (is (docking/valid-layout? docking/default-layout))
  (doseq [layout [nil {} {:left [:assets] :right [:preview] :bottom [:tools]}
                  (update docking/default-layout :left conj :preview)
                  (assoc docking/default-layout :bottom [:unknown])]]
    (is (not (docking/valid-layout? layout)))))

;; Moving between and within zones keeps every panel exactly once, including when a zone becomes empty.
(deftest move-panel-between-zones
  (let [layout (-> docking/default-layout
                   (docking/move-panel :assets :right)
                   (docking/move-panel :preview :bottom))]
    (is (= {:left [] :right [:inspector :assets] :bottom [:tools :preview]} layout))
    (is (docking/valid-layout? layout))
    (is (= layout (docking/move-panel layout :preview :bottom)))
    (is (= [:preview :tools] (:bottom (docking/move-panel layout :tools :bottom))))))
