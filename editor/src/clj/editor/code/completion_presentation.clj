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

(ns editor.code.completion-presentation
  (:require [clojure.string :as string]
            [util.coll :as coll]))

(defn label-parts [{:keys [display-string label-detail label-description]}]
  ;; EmmyLua supplies "(parameters) -> return type" as an LSP label suffix.
  ;; Other servers' label details remain intact. Presentation never changes insertion.
  (let [[_ parameters return-type] (when label-detail
                                    (re-matches #"(?s)^(\([^\n]*?\)) -> (.+)$" label-detail))]
    {:label (str display-string (or parameters label-detail))
     :description (coll/join-to-string "  " (eduction (remove string/blank?) [label-description return-type]))}))

(defn- escape-html [text]
  (string/escape text {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;"}))

(defn documentation [{:keys [detail doc tags] :as completion}]
  (let [body (when-not (string/blank? (:value doc))
               (case (:type doc)
                 :markdown (:value doc)
                 :plaintext (str "<pre>" (escape-html (:value doc)) "</pre>")))
        {:keys [label description]} (label-parts completion)
        heading (when (or detail (not body))
                  (str "<pre>" (escape-html (or detail (str label (when-not (string/blank? description) (str " : " description))))) "</pre>"))]
    (str (when (contains? tags :deprecated) "<small>Deprecated</small>\n\n")
         heading (when (and heading body) "\n\n") body)))
