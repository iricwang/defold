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

(ns editor.play-build
  (:require [clojure.java.io :as io]
            [dynamo.graph :as g]
            [editor.defold-project :as project]
            [editor.engine :as engine]
            [editor.prefs :as prefs]
            [editor.system :as system]
            [util.coll :as coll]
            [util.digest :as digest]))

(set! *warn-on-reflection* true)

(defn snapshot [project prefs skip-engine]
  {:counters (g/invalidate-counters)
   :options [(engine/current-platform) (system/defold-engine-sha1)
             skip-engine (prefs/get prefs [:build])
             (digest/string->sha256-hex (pr-str (prefs/get prefs [:extensions])))
             (prefs/get prefs engine/custom-engine-pref-key)]
   :endpoints (coll/into-> [:resource-snapshot :dependencies :build-settings]
                (mapv #(g/endpoint project %) [:save-data :nodes-by-resource-path :settings :build-settings :breakpoints])
                (map #(g/endpoint (project/workspace project) %)))})

(defn current? [before after]
  (and before
       (= (:options before) (:options after))
       (= (:endpoints before) (:endpoints after))
       (coll/not-any? (g/endpoint-invalidated-pred (:counters before) (:counters after))
                      (:endpoints before))))

(defn file-stamp [file]
  (when file
    (let [file (io/file file)]
      (when (.isFile file)
        [(.lastModified file) (.length file)]))))

(defn engine-stamp [engine-descriptor]
  (file-stamp (or (:engine-archive engine-descriptor) (:dmengine engine-descriptor))))

(defn files-valid? [{:keys [artifacts engine] :as result}]
  ;; Check on an IO thread. No graph resolution or resource compilation occurs.
  ;; Use the same mtime/size contract as the incremental pipeline on every OS.
  (and (coll/not-empty artifacts)
       (or (nil? engine)
           (and (::engine-stamp result)
                (= (::engine-stamp result) (engine-stamp engine))))
       (coll/every? (fn [{:keys [resource mtime size]}]
                      (= [mtime size] (file-stamp (io/as-file resource))))
                    artifacts)))

(defn observe [state snapshot now]
  (if (current? (:observed state) snapshot)
    state
    (assoc state :observed snapshot :changed-at now)))

(defn due? [state snapshot now]
  (and (not (:preparing state))
       (not (:launching state))
       (not (:closed state))
       (not (current? (:attempt state) snapshot))
       (current? (:observed state) snapshot)
       (>= (- now (:changed-at state)) 1000)))
