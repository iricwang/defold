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

(ns editor.code.navigation)

(defn- append-location [locations location]
  (let [locations (conj (or locations []) location)]
    (if (<= (count locations) 100)
      locations
      (subvec locations (- (count locations) 100)))))

(defn- capture-source [history source]
  (if (and source
           (or (nil? (:current history))
               (= (:resource-node source) (get-in history [:current :resource-node]))))
    (assoc history :current source)
    history))

(defn visit
  "Record an explicit jump or tab switch, preserving the position being left."
  [history source destination]
  (let [history (capture-source (or history {}) source)
        current (:current history)]
    (if (or (nil? destination) (= current destination))
      history
      {:back (if current (append-location (:back history) current) [])
       :current destination
       :forward []})))

(defn travel
  "Return updated history and a destination, skipping resources no longer available."
  [history source direction available?]
  (let [history (capture-source (or history {}) source)
        opposite (case direction :back :forward :forward :back)]
    (loop [remaining (get history direction [])]
      (if-let [destination (peek remaining)]
        (if (available? destination)
          {:history (assoc history
                           direction (pop remaining)
                           :current destination
                           opposite (append-location (get history opposite) (:current history)))
           :destination destination}
          (recur (pop remaining)))
        {:history (assoc history direction [])}))))
