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

(ns editor.skeletal-animation-project
  "Multiple independent skeletons in one editable .skeleton document."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-io :as animation-io]
            [editor.skeletal-animation-native :as native]
            [util.coll :as coll])
  (:import [java.nio.file Files]
           [java.util Base64 UUID]
           [javax.imageio ImageIO]))

(defn documents [document]
  (let [active (dissoc document :workspace-skeletons :workspace-active :workspace-origin)
        name (:workspace-active document (:name document))]
    (if-not (:workspace-skeletons document)
      [{:name (:name active) :document active}]
      (mapv #(if (= name (:name %)) {:name name :document active} %) (:workspace-skeletons document)))))

(defn assemble [skeletons selected]
  (when-not (and (vector? skeletons) (<= 1 (count skeletons) 1000)
                 (= (count skeletons) (count (set (mapv :name skeletons)))))
    (throw (ex-info "A workspace needs 1–1000 uniquely named skeletons." {})))
  (doseq [{:keys [name document]} skeletons]
    (when (or (not (string? name)) (string/blank? name) (not= name (:name document)))
      (throw (ex-info "Skeleton names must be nonempty and match their documents." {})))
    (data/validate-document document))
  (let [active (:document (coll/first-where #(= selected (:name %)) skeletons))]
    (when-not active (throw (ex-info "Unknown workspace skeleton." {:skeleton selected})))
    (assoc (dissoc active :workspace-active :workspace-skeletons)
      :workspace-active selected
      :workspace-origin (get-in (coll/first-where #(get-in % [:document :native-project :packed]) skeletons) [:document :native-project])
      :workspace-skeletons (mapv #(update % :document dissoc :workspace-active :workspace-skeletons :workspace-origin) skeletons))))

(defn- retain-origin [before after]
  (cond-> after (:workspace-origin before) (assoc :workspace-origin (:workspace-origin before))))

(defn select [document name]
  (retain-origin document (assemble (documents document) name)))

(defn add [document name]
  (retain-origin document (assemble (conj (documents document) {:name name :document (data/new-document name)}) name)))

(defn rename-active [document name]
  (let [active (:workspace-active document (:name document))]
    (retain-origin document (assemble (mapv #(if (= active (:name %)) (-> % (assoc :name name) (assoc-in [:document :name] name)) %) (documents document)) name))))

(defn remove-active [document]
  (let [active (:workspace-active document (:name document))
        remaining (coll/into-> (documents document) [] (remove #(= active (:name %))))]
    (retain-origin document (assemble remaining (:name (first remaining))))))

(defn write-native!
  "Publish all authored skeletons together, never overwrite another project."
  [document project-root destination]
  (let [target (.getCanonicalFile (io/file destination))]
    (when (or (.exists target) (not (string/ends-with? (string/lower-case (.getName target)) ".spine")))
      (throw (ex-info "Choose a new .spine file." {})))
    (let [root (string/replace (.getCanonicalPath (io/file project-root)) "\\" "/")
          entries (documents document)
          origin (or (:workspace-origin document)
                     (get-in (coll/first-where #(get-in % [:document :native-project :packed]) entries) [:document :native-project]))
          skeletons (mapv (fn [{:keys [name document]}]
                            (cond-> {:name name :source (-> (json/read-str (data/export-spine-json document))
                                                            (assoc-in ["skeleton" "images"] root)
                                                            (assoc-in ["skeleton" "audio"]
                                                                      (string/replace (.getPath (animation-io/native-audio-root document project-root (json/read-str (data/export-spine-json document)))) "\\" "/")))}
                              (and (:packed origin) (= (:packed origin) (get-in document [:native-project :packed])))
                              (assoc :original-index (get-in document [:native-project :selected-index])))) entries)
          graph (if-not (:packed origin)
                  (native/from-skeletons skeletons)
                  (native/update-skeletons (native/decode-graph (native/decode (.decode (Base64/getDecoder) ^String (:packed origin)))) skeletons))
          packed (native/encode-graph graph)
          temporary (Files/createTempFile (.toPath (.getParentFile target)) ".skeletal-" ".spine" (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (Files/write temporary ^bytes packed (make-array java.nio.file.OpenOption 0))
        (Files/move temporary (.toPath target) (make-array java.nio.file.CopyOption 0))
        target
        (finally (Files/deleteIfExists temporary))))))

(defn view-settings [document]
  (let [value (:editor-view document)
        finite (fn finite-value [key fallback minimum maximum]
                 (let [n (get value key fallback)]
                   (if (and (number? n) (Double/isFinite (double n)))
                     (max minimum (min maximum (double n))) fallback)))
        reference (:reference-image value)]
    (merge {:zoom (finite :zoom 1.0 0.02 100.0)
            :pan-x (finite :pan-x 300.0 -1000000.0 1000000.0)
            :pan-y (finite :pan-y 300.0 -1000000.0 1000000.0)
            :reference-opacity (finite :reference-opacity 0.35 0.0 1.0)
            :reference-image (when (and (string? reference)
                                        (not (string/includes? reference ".."))
                                        (not (string/includes? reference ":"))
                                        (not (string/includes? reference "\\"))) reference)
            :ghosting (true? (:ghosting value))
            :show-workspace-peers (true? (:show-workspace-peers value))}
           (coll/into-> [:show-bones :show-images] {} (map (fn [key] [key (not (false? (get value key)))])))
           {:show-names (true? (:show-names value))})))

(defn import-reference!
  "Copy a chosen reference image into a project-owned directory."
  [project-root source]
  (let [root (.getCanonicalFile (io/file project-root))
        directory (.getCanonicalFile (io/file root "skeleton_references"))
        source (io/file source)]
    (when-not (.startsWith (.toPath directory) (.toPath root))
      (throw (ex-info "Reference image directory escapes the project." {})))
    (let [image (ImageIO/read source)]
      (when-not image (throw (ex-info "Unsupported reference image." {})))
      (Files/createDirectories (.toPath directory) (make-array java.nio.file.attribute.FileAttribute 0))
      (let [target (io/file directory (str "reference_" (UUID/randomUUID) ".png"))]
        (try
          (ImageIO/write image "png" target)
          (str "skeleton_references/" (.getName target))
          (catch Exception error (.delete target) (throw error)))))))
