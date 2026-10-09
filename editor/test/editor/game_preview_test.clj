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

(ns editor.game-preview-test
  (:require [cljfx.api]
            [clojure.test :refer [deftest is]])
  (:import [com.defold.editor GamePreview]
           [javafx.scene.input KeyCode]))

;; Letterboxing maps the displayed game center and edges to the original input coordinates.
(deftest letterboxed-input-coordinates
  (is (= [320.0 240.0] (vec (GamePreview/gamePoint 1000 600 640 480 500 300))))
  (is (= [0.0 0.0] (vec (GamePreview/gamePoint 1000 600 640 480 0 0))))
  (is (= [639.0 479.0] (vec (GamePreview/gamePoint 1000 600 640 480 1000 600))))
  (is (= [0.0 0.0] (vec (GamePreview/gamePoint 0 0 640 480 0 0)))))

;; JavaFX navigation key codes overlap ASCII punctuation; special keys must win over ASCII.
(deftest keyboard-protocol-mapping
  (is (= 65 (GamePreview/keyCode KeyCode/A)))
  (is (= 32 (GamePreview/keyCode KeyCode/SPACE)))
  (is (= 140 (GamePreview/keyCode KeyCode/HOME)))
  (is (= 141 (GamePreview/keyCode KeyCode/END)))
  (is (= 129 (GamePreview/keyCode KeyCode/UP)))
  (is (= 155 (GamePreview/keyCode KeyCode/F12)))
  (is (= -1 (GamePreview/keyCode KeyCode/UNDEFINED))))
