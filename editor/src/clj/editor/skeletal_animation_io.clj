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
            [editor.skeletal-animation-native :as native]
            [editor.skeletal-animation-spine :as spine]
            [util.coll :as coll])
  (:import [java.io File]
           [java.nio.file CopyOption Files]
           [java.util Base64]
           [javax.imageio ImageIO]))

(set! *warn-on-reflection* true)

(defn read-image-alpha
  "Read a project PNG for mesh tracing on a worker thread."
  [project-root path]
  (let [root (.getCanonicalFile (io/file project-root))
        file (.getCanonicalFile (io/file root (string/replace path #"^/" "")))]
    (when-not (.startsWith (.toPath file) (.toPath root))
      (throw (ex-info "Mesh image must be inside the project." {:path path})))
    (let [image (ImageIO/read file)]
      (when-not image
        (throw (ex-info "Unable to read the mesh image." {:path path})))
      (let [width (.getWidth image)
            height (.getHeight image)
            pixels (.getRGB image 0 0 width height nil 0 width)
            alpha (byte-array (* width height))]
        (dotimes [index (alength pixels)]
          (aset-byte alpha index (unchecked-byte (unsigned-bit-shift-right (aget pixels index) 24))))
        {:width width :height height :alpha alpha}))))

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
  (let [images (io/file images-path)
        roots (into (if (string/blank? images-path) [] [(if (.isAbsolute images) images (io/file source-root images-path))])
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

(defn- read-spine-source-impl!
  "Read Spine JSON and copy its referenced PNGs into a fresh project folder.
  Returns {:document doc :warnings [...] :files [File ...]}. Existing assets
  are never overwritten. All source images are validated before any copy."
  [file project-root source]
  (let [file (.getCanonicalFile (io/file file))
        root (.getCanonicalFile (io/file project-root))
        source-root (.getParentFile file)
        references (image-references source)
        images-path (get-in source ["skeleton" "images"] "")
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

(defn read-spine-source!
  "Import a JSON source whose image directory is relative to its source file."
  [file project-root source]
  (let [images-path (get-in source ["skeleton" "images"] "")]
    (when (or (.isAbsolute (io/file images-path)) (string/includes? images-path "\\"))
      (throw (ex-info "The Spine images directory must be relative to the JSON file." {:images images-path}))))
  (read-spine-source-impl! file project-root source))

(defn read-spine-file!
  "Import a JSON interchange file. Native project conversion calls
  read-spine-source! with the original .spine path as the image-path origin."
  [file project-root]
  (read-spine-source! file project-root (json/read-str (slurp file))))

(defn read-native-skeleton!
  "Import the selected skeleton while retaining its native project's other
  skeletons for Save As. The native source and its PNGs are never modified."
  [file project-root skeletons selected-name]
  (let [selected (coll/first-where #(= selected-name (:name %)) skeletons)]
    (when-not selected
      (throw (ex-info "Select a skeleton from the native project." {:skeleton selected-name})))
    ;; Native projects can reference an absolute image root on another volume.
    ;; Keep attachment paths relative and contained, but do not relativize roots
    ;; across Windows drive letters.
    (let [result (read-spine-source-impl! file project-root (:source selected))]
      (update result :document assoc
              :name selected-name
              :native-project {:file (.getCanonicalPath (io/file file))
                               :selected selected-name
                               :skeletons (mapv (fn [{:keys [name source]}]
                                                  {:name name :source-json (json/write-str source :escape-slash false)})
                                                skeletons)}))))

(defn read-independent-project!
  "Decode a supported native project locally. Unsupported object graphs fail
  before PNGs are copied, and never fall back to an installed Spine process."
  [file]
  (let [packed (Files/readAllBytes (.toPath (io/file file)))
        graph (native/decode-graph (native/decode packed))
        skeletons (native/graph-skeletons graph)]
    {:packed (.encodeToString (Base64/getEncoder) packed)
     :skeletons (mapv (fn [index skeleton] {:name (:name skeleton) :source (native/to-spine-json graph index)})
                      (range (count skeletons)) skeletons)}))

(defn read-independent-skeleton!
  [file project-root project selected-name]
  (let [result (read-native-skeleton! file project-root (:skeletons project) selected-name)
        index (reduce-kv (fn [_ index skeleton] (when (= selected-name (:name skeleton)) (reduced index))) nil (:skeletons project))]
    (update-in result [:document :native-project] assoc
               :codec "independent-4.2.43" :packed (:packed project) :selected-index index)))

(defn write-independent-project!
  "Encode the edited skeleton into its preserved native graph without Spine.
  Save As never replaces an original or an existing destination."
  [document project-root target]
  (let [target (.getCanonicalFile (io/file target))
        _ (when (or (.exists target) (not (string/ends-with? (string/lower-case (.getName target)) ".spine")))
            (throw (ex-info "Choose a new .spine file. Existing projects are not overwritten." {:file (str target)})))
        source (assoc-in (json/read-str (data/export-spine-json document)) ["skeleton" "images"]
                         (string/replace (.getCanonicalPath (io/file project-root)) "\\" "/"))
        metadata (:native-project document)
        audio (io/file (get-in source ["skeleton" "audio"] "./audio"))
        audio-root (if-let [file (:file metadata)] (.getParentFile (.getCanonicalFile (io/file file))) (io/file project-root))
        source (assoc-in source ["skeleton" "audio"]
                         (string/replace (.getCanonicalPath (if (.isAbsolute audio) audio (io/file audio-root (str audio)))) "\\" "/"))
        graph (if (= "independent-4.2.43" (:codec metadata))
                (let [graph (native/decode-graph (native/decode (.decode (Base64/getDecoder) ^String (:packed metadata))))
                      directory (.getParentFile (.getCanonicalFile (io/file (:file metadata))))
                      graph (reduce-kv
                              (fn [graph index skeleton]
                                (let [fields (:data (native/graph-node graph (:reference skeleton)))
                                      resolve-directory (fn [field]
                                                          (let [value (:data (native/graph-node graph (get fields field)))
                                                                file (io/file value)]
                                                            (string/replace (.getCanonicalPath (if (.isAbsolute file) file (io/file directory value))) "\\" "/")))]
                                  (native/relocate-skeleton-directories graph index (resolve-directory 3) (resolve-directory 34))))
                              graph (native/graph-skeletons graph))]
                  (native/update-skeleton graph (:selected-index metadata) source))
                (native/from-skeletons
                  (if-not metadata
                    [{:name (:name document) :source source}]
                    (mapv (fn [skeleton]
                            (if (= (:selected metadata) (:name skeleton))
                              {:name (:name document) :source source}
                              (let [original (json/read-str (:source-json skeleton))
                                    images (io/file (get-in original ["skeleton" "images"] ""))
                                    directory (.getParentFile (io/file (:file metadata)))
                                    images (if (.isAbsolute images) images (io/file directory (str images)))
                                    audio (io/file (get-in original ["skeleton" "audio"] "./audio"))
                                    audio (if (.isAbsolute audio) audio (io/file directory (str audio)))]
                                {:name (:name skeleton)
                                 :source (-> original
                                             (assoc-in ["skeleton" "images"] (string/replace (.getCanonicalPath images) "\\" "/"))
                                             (assoc-in ["skeleton" "audio"] (string/replace (.getCanonicalPath audio) "\\" "/")))})))
                          (:skeletons metadata)))))
        packed (native/encode-graph graph)]
    (fs/create-parent-directories! target)
    (with-open [input (java.io.ByteArrayInputStream. packed)]
      (Files/copy input (.toPath target) ^CopyOption/1 (make-array CopyOption 0)))
    target))

(defn write-native-project!
  "Save As through the installed Spine bridge. Other skeletons from an opened
  project are retained. Native project-only settings are not roundtrip-safe."
  [document project-root target config]
  (let [source (json/read-str (data/export-spine-json document))
        source (assoc-in source ["skeleton" "images"] (string/replace (.getCanonicalPath (io/file project-root)) "\\" "/"))
        native (:native-project document)
        source-directory (some-> (:file native) io/file .getParentFile)
        skeletons (if-not native
                    [{:name (:name document) :source source}]
                    (mapv (fn [skeleton]
                            (if (= (:selected native) (:name skeleton))
                              {:name (:name document) :source source}
                              (let [original (json/read-str (:source-json skeleton))
                                    images (io/file (get-in original ["skeleton" "images"] ""))
                                    images (if (.isAbsolute images) images (io/file source-directory (str images)))]
                                {:name (:name skeleton)
                                 :source (assoc-in original ["skeleton" "images"]
                                                   (string/replace (.getCanonicalPath images) "\\" "/"))})))
                          (:skeletons native)))]
    (spine/write-project! skeletons target config)))

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
                                   "\nskin: " (pr-str (let [skin (get document :active-skin "default")] (if (= "default" skin) "" skin)))
                                   "\nmaterial: \"/defold-spine/assets/spine.material\"\ncreate_go_bones: true\n")}]
    ;; Resolve every target and validate every image before touching outputs.
    (run! (fn [[extension file]]
            (fs/create-parent-directories! file)
            (spit file (get contents extension)))
          files)
    (assoc paths :files (mapv files [:spinejson :atlas :spinescene :spinemodel]))))
