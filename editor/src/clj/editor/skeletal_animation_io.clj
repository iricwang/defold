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
            [editor.skeletal-animation-runtime-export :as runtime-export]
            [editor.skeletal-animation-sequence :as sequence]
            [editor.skeletal-animation-spine :as spine]
            [editor.skeletal-animation-textures :as textures]
            [util.coll :as coll])
  (:import [java.io File]
           [java.nio.file CopyOption Files OpenOption]
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
                 (coll/not-any? #{"" "." ".."} (string/split path #"/" -1)))
    (throw (ex-info "Asset paths must be relative and cannot contain traversal components." {:path path})))
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
  (coll/into-> (range (count (get source "skins" []))) []
    (mapcat (fn [skin-index]
              (coll/into-> (get-in source ["skins" skin-index "attachments"] {}) []
                (mapcat (fn [[slot entries]]
                          (coll/into-> entries []
                            (mapcat (fn [[name attachment]]
                                      (when (contains? #{"region" "mesh" "linkedmesh"} (get attachment "type" "region"))
                                        (let [base (safe-relative-path (get attachment "path" (get attachment "name" name)))
                                              paths (if-let [value (sequence/decode (get attachment "sequence"))]
                                                      (sequence/frame-paths base value) [base])]
                                          (mapv (fn [path]
                                                  {:key-path ["skins" skin-index "attachments" slot name "path"]
                                                   :base-path base :path path
                                                   :png-path (if (string/ends-with? (string/lower-case path) ".png") path (str path ".png"))})
                                                paths)))))))))))))

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

(defn- available-asset-directory
  ^File [^File root stem kind]
  (loop [index 1]
    (let [directory (project-file root (str stem "_" kind (when (> index 1) (str "_" index))))]
      (if (.exists directory)
        (recur (inc index))
        directory))))

(defn- audio-references [source]
  (into
    (coll/into-> (get source "events") []
      (keep (fn [[name event]]
              (when-not (string/blank? (get event "audio"))
                {:key-path ["events" name "audio"] :path (safe-relative-path (get event "audio"))}))))
    (coll/into-> (get source "animations") []
      (mapcat (fn [[animation value]]
                (coll/into-> (vec (get value "events")) []
                  (keep-indexed (fn [index event]
                                  (when-not (string/blank? (get event "audio"))
                                    {:key-path ["animations" animation "events" index "audio"]
                                     :path (safe-relative-path (get event "audio"))})))))))))

(defn- audio-import-plan [^File source-root ^File project-root stem source]
  (let [references (audio-references source)
        audio-path (get-in source ["skeleton" "audio"] "")
        audio-root (io/file audio-path)
        audio-root (.getCanonicalFile (if (.isAbsolute audio-root) audio-root (io/file source-root audio-path)))
        directory (available-asset-directory project-root stem "audio")
        files (coll/into-> references {}
                (map (fn [{:keys [path]}]
                       [path {:source (project-file audio-root path)
                              :target (project-file directory path)}])))
        missing (filterv (fn [[_ {:keys [source]}]] (not (.isFile ^File source))) files)]
    {:directory directory
     :project-relative (pos? (count references))
     :files (coll/into-> files {} (remove (fn [[_ {:keys [source]}]] (not (.isFile ^File source)))))
     :warnings (mapv (fn [[path _]] (str "Missing event audio: " path " (searched " audio-root ").")) missing)
     ;; A project-relative identity is shared by preview and the exported game.
     :source (if (coll/empty? references) source
                 (reduce (fn [source {:keys [key-path path]}]
                           (assoc-in source key-path (subs (project-path project-root (project-file directory path)) 1)))
                         (assoc-in source ["skeleton" "audio"] "") references))}))

(defn- project-audio-warnings [^File root source]
  ;; Spine commonly writes ./audio even when no event uses audio. Only resolve
  ;; a directory when it has dependencies, and accept its leading current-dir
  ;; components without weakening attachment or parent-traversal validation.
  (if-let [references (coll/not-empty (audio-references source))]
    (let [directory (string/replace (get-in source ["skeleton" "audio"] "") #"^(?:\./)+" "")
          audio-root (if (contains? #{"" "."} directory) root (project-file root directory))]
      (coll/into-> references []
        (keep (fn [{:keys [path]}]
                (let [file (project-file audio-root path)]
                  (when-not (.isFile file) (str "Missing event audio: " path ".")))))
        (distinct)))
    []))

(defn- read-spine-source-impl!
  "Read Spine JSON and copy PNG/audio dependencies into fresh project folders.
  Returns {:document doc :warnings [...] :files [File ...]}. Existing assets
  are never overwritten. Check all paths and images before copying."
  [file project-root source]
  (let [file (.getCanonicalFile (io/file file))
        root (.getCanonicalFile (io/file project-root))
        source-root (.getParentFile file)
        references (image-references source)
        images-path (get-in source ["skeleton" "images"] "")
        stem (-> (.getName file) (string/replace #"\.[^.]+$" "") (string/replace #"[^\p{L}\p{N}_-]" "_"))
        directory (available-asset-directory root stem "images")
        paths (coll/into-> references #{} (map :png-path))
        image-files (coll/into-> paths {}
                      (map (fn [path]
                             [path {:source (import-image-file source-root root images-path path)
                                    :target (project-file directory path)}])))
        audio (audio-import-plan source-root root stem source)
        files (into (vec (coll/vals image-files)) (coll/vals (:files audio)))
        source (reduce (fn [source {:keys [key-path base-path]}]
                         (let [base-png (if (string/ends-with? (string/lower-case base-path) ".png") base-path (str base-path ".png"))
                               target-path (project-path root (project-file directory base-png))]
                           (assoc-in source key-path (subs target-path 1 (- (count target-path) 4)))))
                       (assoc-in (:source audio) ["skeleton" "images"] "")
                       references)
        document (data/import-spine-json (json/write-str source :escape-slash false))
        document (cond-> (update document :warnings #(into (vec %) (:warnings audio)))
                   (:project-relative audio) (assoc :audio-project-relative true))
        document (if-not (= "Imported skeleton" (:name document)) document (assoc document :name stem))]
    (try
      (run! (fn [{:keys [source target]}]
              (fs/create-parent-directories! target)
              (Files/copy (.toPath ^File source) (.toPath ^File target) ^CopyOption/1 (make-array CopyOption 0)))
            files)
      {:document document :warnings (get document :warnings []) :files (mapv :target files)}
      (catch Exception exception
        (doseq [^File directory [directory (:directory audio)] :when (.exists directory)]
          (fs/delete-directory! directory {:fail :silently}))
        (throw exception)))))

(defn read-spine-source!
  "Import a JSON source whose image directory is relative to its source file."
  [file project-root source]
  (doseq [field ["images" "audio"]
          :let [directory (get-in source ["skeleton" field] "")]]
    (when (or (.isAbsolute (io/file directory)) (string/includes? directory "\\"))
      (throw (ex-info "Spine asset directories must be relative to the JSON file." {:field field :directory directory}))))
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

(defn native-audio-root
  "Resolve native audio roots, preserving unarchived source directories."
  [document project-root source]
  (let [audio (io/file (get-in source ["skeleton" "audio"] "./audio"))
        source-file (get-in document [:native-project :file])
        root (if (and source-file (not (:audio-project-relative document)))
               (.getParentFile (.getCanonicalFile (io/file source-file)))
               (io/file project-root))]
    (.getCanonicalFile (if (.isAbsolute audio) audio (io/file root (str audio))))))

(defn write-independent-project!
  "Encode the edited skeleton into its preserved native graph without Spine.
  Save As never replaces an original or an existing destination."
  [document project-root target]
  (when (string/starts-with? (get-in (json/read-str (or (:spine-source document) "{}")) ["skeleton" "spine"] "") "4.3.")
    (throw (ex-info "Spine 4.3 projects must be saved with the matching Spine CLI, not the 4.2 codec." {})))
  (let [target (.getCanonicalFile (io/file target))
        _ (when (or (.exists target) (not (string/ends-with? (string/lower-case (.getName target)) ".spine")))
            (throw (ex-info "Choose a new .spine file. Existing projects are not overwritten." {:file (str target)})))
        source (assoc-in (json/read-str (data/export-spine-json document)) ["skeleton" "images"]
                         (string/replace (.getCanonicalPath (io/file project-root)) "\\" "/"))
        metadata (:native-project document)
        source (assoc-in source ["skeleton" "audio"]
                         (string/replace (.getPath ^File (native-audio-root document project-root source)) "\\\\" "/"))
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
    (let [staging (Files/createTempFile (.toPath (.getParentFile target)) ".spine-save-" ".tmp"
                                        (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (Files/write staging ^bytes packed ^OpenOption/1 (make-array OpenOption 0))
        (Files/move staging (.toPath target) ^CopyOption/1 (make-array CopyOption 0))
        target
        (finally (Files/deleteIfExists staging))))))

(defn write-native-project!
  "Save As through the installed Spine bridge. Other skeletons from an opened
  project are retained. Native project-only settings are not roundtrip-safe."
  [document project-root target config]
  (let [source (json/read-str (data/export-spine-json document))
        source (-> source
                   (assoc-in ["skeleton" "images"] (string/replace (.getCanonicalPath (io/file project-root)) "\\" "/"))
                   (assoc-in ["skeleton" "audio"] (string/replace (.getCanonicalPath (io/file project-root)) "\\" "/")))
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

(defn export-compatibility
  "Report target differences before opening a save dialog or writing files."
  [document]
  (let [document (data/validate-document document)
        ;; The preview warning only depends on two-color slot data. Exporting
        ;; every mesh and animation here makes mouse release unnecessarily slow.
        source (if-let [text (:spine-source document)] (json/read-str text) {})
        slot-ids (mapv :id (:slots document))
        two-color (or (coll/any? :dark-color (:slots document))
                      (coll/any? (fn [animation]
                                   (coll/any? #(or (get % "rgba2") (get % "rgb2"))
                                              (coll/vals (select-keys (get-in source ["animations" (:id animation) "slots"]) slot-ids))))
                                 (:animations document)))]
    (cond-> []
      two-color (conj {:target :defold :severity :warning :code :default-material-dark}))))

(defn texture-inventory
  "All skins and sequence frames, deduplicated; reads PNG headers only."
  [document project-root]
  (let [root (.getCanonicalFile (io/file project-root))
        images (coll/into-> (image-references (json/read-str (data/export-spine-json document))) (sorted-set) (map :png-path))]
    (mapv (fn [path]
            {:path path :size (textures/image-size (project-file root path))}) images)))

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
        source (runtime-export/for-runtime (data/export-spine-data document))
        spine-json (json/write-str source :escape-slash false)
        references (image-references source)
        audio-warnings (project-audio-warnings root source)
        inherit-blend (coll/any? #(not= "normal" (get % "blend" "normal")) (get source "slots"))
        two-color (or (coll/any? #(get % "dark") (get source "slots"))
                      (coll/any? (fn [animation]
                                   (coll/any? #(or (get % "rgba2") (get % "rgb2")) (coll/vals (get animation "slots"))))
                                 (coll/vals (get source "animations"))))
        warnings (cond-> audio-warnings
                   two-color (conj "Two-color slot tint is preserved in Spine JSON, but the default Defold Spine material does not render dark channels. A runtime extension and material exposing two-color vertex data are required."))
        factor (textures/scale document)
        images (into (sorted-map) (map (juxt :path :png-path)) references)
        _ (run! #(validate-png! (project-file root %)) (coll/vals images))
        _ (when (zero? (count images))
            (throw (ex-info "Add at least one image attachment before exporting game resources." {})))
        derived (if (= 1.0 factor) {}
                    (coll/into-> (set (coll/vals images)) (sorted-map)
                      (map (fn [image]
                             [image (project-file directory (str stem "_textures/" image))]))))
        sources (set (mapv #(project-file root %) (coll/vals images)))
        _ (when (coll/any? sources (coll/vals derived))
            (throw (ex-info "Texture output would overwrite a source image. Choose another export directory." {})))
        files (into {} (map (fn [extension] [(keyword extension) (project-file directory (str stem "." extension))]))
                    ["spinejson" "atlas" "spinescene" "spinemodel"])
        paths (into {} (map (fn [[extension file]] [extension (project-path root file)])) files)
        atlas (str (coll/join-to-string
                     (into []
                           (map (fn [[path image]]
                                  (str "animations {\n  id: " (pr-str path) "\n  images { image: " (pr-str (if-let [file (get derived image)] (project-path root file) (str "/" image))) " }\n  playback: PLAYBACK_ONCE_FORWARD\n  fps: 0\n}\n")))
                           images))
                   "extrude_borders: 2\ninner_padding: 2\n")
        contents {:spinejson spine-json
                  :atlas atlas
                  :spinescene (str "spine_json: " (pr-str (:spinejson paths)) "\natlas: " (pr-str (:atlas paths)) "\n")
                  :spinemodel (str "spine_scene: " (pr-str (:spinescene paths)) "\ndefault_animation: " (pr-str (or (:id (first (:animations document))) ""))
                                   "\nskin: " (pr-str (let [skin (get document :active-skin "default")] (if (= "default" skin) "" skin)))
                                   "\nmaterial: \"/defold-spine/assets/spine.material\"\ncreate_go_bones: true\n"
                                   (when inherit-blend "blend_mode: BLEND_MODE_INHERIT\n"))}]
    ;; Resolve every target and validate every image before touching outputs.
    (run! (fn [[image file]] (textures/write-scaled! (project-file root image) file factor)) derived)
    (run! (fn [[extension file]]
            (fs/create-parent-directories! file)
            (spit file (get contents extension)))
          files)
    (assoc paths :warnings warnings :texture-scale factor :texture-files (vec (coll/vals derived))
           :files (mapv files [:spinejson :atlas :spinescene :spinemodel]))))
