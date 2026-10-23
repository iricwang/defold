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

(ns editor.skeletal-animation-io
  "Local import/export of authoring documents and their PNG assets. Authoring
  resources are separate from the Spine extension's compiled game resources."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.fs :as fs]
            [editor.skeletal-animation-data :as data]
            [util.coll :as coll])
  (:import [java.io File]
           [java.nio.file CopyOption Files]
           [javax.imageio ImageIO]))

(set! *warn-on-reflection* true)

(defn- safe-relative-path [path]
  (when-not (and (string? path)
                 (not (string/blank? path))
                 (not (string/starts-with? path "/"))
                 (not (string/includes? path "\\"))
                 (not (string/includes? path ":"))
                 (not (coll/any? #{"" "." ".."} (string/split path #"/" -1))))
    (throw (ex-info "Image paths must be relative and cannot contain traversal components." {:path path})))
  path)

(defn- project-file
  ^File [^File root relative-path]
  (let [file (.getCanonicalFile (io/file root (safe-relative-path relative-path)))]
    (when-not (.startsWith (.toPath file) (.toPath root))
      (throw (ex-info "The path resolves outside the project." {:path relative-path})))
    file))

(defn- project-path [^File root ^File file]
  (str "/" (string/replace (str (.relativize (.toPath root) (.toPath file))) "\\" "/")))

(defn- image-references [source]
  (into []
        (mapcat (fn [skin-index]
                  (let [attachments (get-in source ["skins" skin-index "attachments"] {})]
                    (into []
                          (mapcat (fn [[slot entries]]
                                    (into []
                                          (keep (fn [[name attachment]]
                                                  (when (contains? #{"region" "mesh" "linkedmesh"} (get attachment "type" "region"))
                                                    (when (get attachment "sequence")
                                                      (throw (ex-info "Image-sequence attachments require a sequence-aware asset export; no files were written."
                                                                      {:slot slot :attachment name})))
                                                    (let [path (safe-relative-path (get attachment "path" (get attachment "name" name)))
                                                          png-path (if (string/ends-with? (string/lower-case path) ".png") path (str path ".png"))]
                                                      {:key-path ["skins" skin-index "attachments" slot name "path"]
                                                       :path path
                                                       :png-path png-path}))))
                                          entries)))
                          attachments))))
        (range (count (get source "skins" [])))))

(defn- validate-png! [^File file]
  (when-not (and (.isFile file)
                 (with-open [stream (io/input-stream file)]
                   (let [signature (byte-array 8)]
                     (and (= 8 (.read stream signature))
                          (= [-119 80 78 71 13 10 26 10] (vec signature)))))
                 (ImageIO/read file))
    (throw (ex-info "A referenced PNG image is missing or invalid." {:file (str file)})))
  file)

(defn- import-image-file [^File source-root ^File project-root images-path png-path]
  (let [roots (into (if (string/blank? images-path) [] [(io/file source-root images-path)])
                    [source-root (io/file source-root "images") project-root])
        candidates (mapv #(project-file (.getCanonicalFile ^File %) png-path) roots)]
    (if-let [file (coll/first-where #(.isFile ^File %) candidates)]
      (validate-png! file)
      (throw (ex-info "Could not find an image next to the Spine JSON or in its images folder."
                      {:path png-path :searched (mapv str candidates)})))))

(defn- available-image-directory
  ^File [^File root stem]
  (loop [index 1]
    (let [directory (project-file root (str stem "_images" (when (> index 1) (str "_" index))))]
      (if (.exists directory)
        (recur (inc index))
        directory))))

(defn read-spine-file!
  "Read Spine JSON and copy its referenced PNGs into a fresh project folder.
  Returns {:document doc :warnings [...] :files [File ...]}. Existing assets
  are never overwritten. All source images are validated before any copy."
  [file project-root]
  (let [file (.getCanonicalFile (io/file file))
        root (.getCanonicalFile (io/file project-root))
        source-root (.getParentFile file)
        source (json/read-str (slurp file))
        references (image-references source)
        images-path (get-in source ["skeleton" "images"] "")
        _ (when (or (.isAbsolute (io/file images-path)) (string/includes? images-path "\\"))
            (throw (ex-info "The Spine images directory must be relative to the JSON file." {:images images-path})))
        stem (-> (.getName file) (string/replace #"\.[^.]+$" "") (string/replace #"[^\p{L}\p{N}_-]" "_"))
        directory (available-image-directory root stem)
        paths (into #{} (map :png-path) references)
        files (into {}
                    (map (fn [path]
                           [path {:source (import-image-file source-root root images-path path)
                                  :target (project-file directory path)}]))
                    paths)
        source (reduce (fn [source {:keys [key-path png-path]}]
                         (let [target-path (project-path root (:target (get files png-path)))]
                           (assoc-in source key-path (subs target-path 1 (- (count target-path) 4)))))
                       (assoc-in source ["skeleton" "images"] "")
                       references)
        document (data/import-spine-json (json/write-str source :escape-slash false))
        document (if-not (= "Imported skeleton" (:name document))
                   document
                   (assoc document :name stem))]
    (try
      (run! (fn [[_ {:keys [source target]}]]
              (fs/create-parent-directories! target)
              (Files/copy (.toPath ^File source) (.toPath ^File target) ^CopyOption/1 (make-array CopyOption 0)))
            files)
      {:document document
       :warnings (get document :warnings [])
       :files (mapv :target (coll/vals files))}
      (catch Exception exception
        (when (.exists directory)
          (fs/delete-directory! directory {:fail :silently}))
        (throw exception)))))

(defn export-project!
  "Write a .spinejson plus matching Defold atlas, scene and model inside the
  project. Returns their project paths and absolute File objects. Requires the
  Spine extension when these exported resources are built by the game."
  [document project-root target-dir]
  (let [root (.getCanonicalFile (io/file project-root))
        target (io/file target-dir)
        directory (.getCanonicalFile (if (.isAbsolute target) target (io/file root target-dir)))
        _ (when-not (.startsWith (.toPath directory) (.toPath root))
            (throw (ex-info "Export must stay inside the project." {:directory (str directory)})))
        stem (string/replace (:name document) #"[^\p{L}\p{N}_-]" "_")
        _ (when (string/blank? stem)
            (throw (ex-info "An export needs a nonempty document name." {})))
        spine-json (data/export-spine-json document)
        references (image-references (json/read-str spine-json))
        images (into (sorted-map) (map (juxt :path :png-path)) references)
        _ (run! #(validate-png! (project-file root %)) (coll/vals images))
        _ (when (zero? (count images))
            (throw (ex-info "Add at least one image attachment before exporting game resources." {})))
        files (into {} (map (fn [extension] [(keyword extension) (project-file directory (str stem "." extension))]))
                    ["spinejson" "atlas" "spinescene" "spinemodel"])
        paths (into {} (map (fn [[extension file]] [extension (project-path root file)])) files)
        atlas (str (coll/join-to-string
                     (into []
                           (map (fn [[path image]]
                                  (str "animations {\n  id: " (pr-str path) "\n  images { image: " (pr-str (str "/" image)) " }\n  playback: PLAYBACK_ONCE_FORWARD\n  fps: 0\n}\n")))
                           images))
                   "extrude_borders: 2\ninner_padding: 2\n")
        contents {:spinejson spine-json
                  :atlas atlas
                  :spinescene (str "spine_json: " (pr-str (:spinejson paths)) "\natlas: " (pr-str (:atlas paths)) "\n")
                  :spinemodel (str "spine_scene: " (pr-str (:spinescene paths)) "\ndefault_animation: " (pr-str (or (:id (first (:animations document))) ""))
                                   "\nskin: \"\"\nmaterial: \"/defold-spine/assets/spine.material\"\ncreate_go_bones: true\n")}]
    ;; Resolve every target and validate every image before touching outputs.
    (run! (fn [[extension file]]
            (fs/create-parent-directories! file)
            (spit file (get contents extension)))
          files)
    (assoc paths :files (mapv files [:spinejson :atlas :spinescene :spinemodel]))))
