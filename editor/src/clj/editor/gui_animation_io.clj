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

(ns editor.gui-animation-io
  "UTF-8 file boundaries shared by native file dialogs and round-trip tests."
  (:require [editor.gui-animation-data :as animation]))

(set! *warn-on-reflection* true)

(defn read-document-file [file resolve-template]
  (animation/read-source (slurp file :encoding "UTF-8") resolve-template))

(defn write-document-file! [file format document]
  (let [text (case format
               :json (animation/write-json (animation/runtime-document document))
               :lua (animation/write-lua document))]
    (spit file text :encoding "UTF-8")
    file))
