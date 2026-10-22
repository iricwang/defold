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

(ns editor.lsp.emmy-bridge
  "Test-only stdio harness exercising the production lifecycle adapter against
  the real pinned backend with scripts/test_emmylua.py."
  (:require [clojure.core.async :as a]
            [editor.lsp.base :as base]
            [editor.lsp.defold-lua :as lua]))

(defn -main [server]
  (let [process (.start (doto (ProcessBuilder. ^java.util.List [server])
                         (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)))
        [source sink] (base/make (.getInputStream process) (.getOutputStream process))
        [source sink] (lua/wrap source sink)
        [input output] (base/make System/in System/out)]
    (a/pipe input sink)
    (a/pipe source output)
    (System/exit (.waitFor process))))
