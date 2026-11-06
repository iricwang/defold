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

(ns editor.ai-resource-import
  "Reviewable GUI resource admission, with guarded writes and durable recovery."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.ai-production :as production]
            [editor.ai-publication :as publication]
            [editor.craft-project :as craft-project]
            [editor.protobuf :as protobuf]
            [util.coll :as coll]
            [util.digest :as digest])
  (:import [com.dynamo.gamesys.proto AtlasProto$Atlas Gui$SceneDesc]
           [java.io File]
           [java.nio.channels FileChannel OverlappingFileLockException]
           [java.nio.file Files StandardCopyOption StandardOpenOption]
           [java.util UUID]))

(set! *warn-on-reflection* true)

(def rules-path "/ai-workspace/config/resource-rules.json")
(def default-rules
  {:version 1 :gui {:images "/assets/ui/{module}" :atlas "/assets/atlases/ui/{module}.atlas"
                    :scene "/gui/{module}/{module}.gui" :maxImageSize 4096
                    :maxAtlasSize 4096 :innerPadding 2 :extrudeBorders 2}})

(defn import-action? [card] (= "resource-import" (:task-type card)))

(defn- read-json [file]
  (when-not (and (.isFile ^File file) (<= (.length ^File file) (* 4 1024 1024)))
    (throw (ex-info "资源清单或规则不存在，或超过 4 MiB" {})))
  (json/read-str (slurp file :encoding "UTF-8") :key-fn keyword))

(defn- file-hash [file]
  (when (.isFile ^File file)
    (with-open [stream (io/input-stream file)] (digest/stream->sha256-hex stream))))

(defn- write-atomic! [^File file bytes]
  (io/make-parents file)
  (let [temp (Files/createTempFile (.toPath (.getParentFile file)) "admission-" ".tmp"
                                   (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (Files/write temp ^bytes bytes ^"[Ljava.nio.file.OpenOption;" (make-array java.nio.file.OpenOption 0))
      (Files/move temp (.toPath file) (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))
      (finally (Files/deleteIfExists temp)))))

(defn ensure-rules! [root]
  (let [file (craft-project/resource-file root rules-path)]
    (io/make-parents file)
    (try
      (Files/write (.toPath file) (.getBytes (json/write-str default-rules :indent true) "UTF-8")
                   ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE]))
      (catch java.nio.file.FileAlreadyExistsException _ nil))
    rules-path))

(defn- destination! [root path extension]
  (when-not (and (string? path) (string/ends-with? path extension)
                 (not (re-find #"(?i)(?:^/(?:ai-workspace|builtins|build|codex-attachments)(?:/|$)|/ai_generated(?:/|$))" path)))
    (throw (ex-info "正式资源路径无效，不能写入暂存区、构建目录或内置资源" {:path path})))
  (craft-project/resource-file root path))

(defn validate-rules! [root config]
  (let [rule (:gui config)]
    (when-not (and (= 1 (:version config))
                   (coll/every? #(and (integer? %) (<= 1 % 8192)) [(:maxImageSize rule) (:maxAtlasSize rule)])
                   (coll/every? #(and (integer? %) (<= 0 % 32)) [(:innerPadding rule) (:extrudeBorders rule)])
                   (coll/every? #(and (string? (get rule %)) (string/includes? (get rule %) "{module}")) [:images :atlas :scene]))
      (throw (ex-info "入库规则需要 version=1、GUI 目录模板和有效的尺寸/留白参数" {})))
    (doseq [[key suffix] [[:images "/frame.png"] [:atlas ""] [:scene ""]]]
      (destination! root (str (string/replace (get rule key) "{module}" "screen") suffix)
                    (case key :images ".png" :atlas ".atlas" :scene ".gui")))
    config))

(defn save-rules! [root expected text]
  (when (> (count text) (* 1024 1024)) (throw (ex-info "入库规则超过 1 MiB" {})))
  (validate-rules! root (json/read-str text :key-fn keyword))
  (let [file (craft-project/resource-file root rules-path)]
    (when-not (= expected (slurp file :encoding "UTF-8"))
      (throw (ex-info "入库规则已被外部修改，请保留草稿并重载" {})))
    (write-atomic! file (.getBytes ^String text "UTF-8"))))

(defn- source-manifest [root path]
  (when-not (and (string? path) (re-find #"/ai_generated/[^/]+/[^/]+/manifest\.json$" path))
    (throw (ex-info "请选择已有 GUI 拆分产物的 manifest.json；完整效果图不能直接入库" {})))
  (let [manifest (read-json (craft-project/resource-file root path))
        prefix (subs path 0 (- (count path) (count "manifest.json")))
        sources (into [(:atlas manifest) (:gui manifest)] (mapv :file (:assets manifest)))]
    (when-not (and (= 1 (:version manifest)) (<= 1 (count (:assets manifest)) 256)
                   (coll/every? #(and (string? %) (string/starts-with? % prefix)) sources))
      (throw (ex-info "GUI 产物清单不完整或引用了其他模块" {})))
    (doseq [source sources] (craft-project/resource-file root source))
    manifest))

(defn- serialized
  ^String [kind data]
  (protobuf/map->str (case kind "atlas" AtlasProto$Atlas "gui" Gui$SceneDesc) data))

(defn- content [root files entry rules]
  (let [file (craft-project/resource-file root (:source entry))
        _ (when (> (.length file) (* 64 1024 1024)) (throw (ex-info "资源文件超过 64 MiB" {})))
        remap (coll/into-> files {} (map (juxt :source :target)))
        kind (:kind entry)]
    (cond
      (= "reference" kind)
      (if (string/ends-with? (:source entry) ".gui")
        (content root files (assoc entry :kind "gui") rules)
        (.getBytes ^String
          (reduce (fn replace-reference [text {:keys [source target]}]
                    (-> text (string/replace (pr-str source) (pr-str target))
                        (string/replace (str "'" source "'") (str "'" target "'"))))
                  (slurp file :encoding "UTF-8") files) "UTF-8"))
      (= "image" kind)
      (Files/readAllBytes (.toPath file))
      :else
      (let [data (:data (production/validate-resource-text (keyword kind) (slurp file :encoding "UTF-8")))
            data (case kind
                   "atlas" (-> data
                               (assoc :inner-padding (:innerPadding rules) :extrude-borders (:extrudeBorders rules))
                               (update :images #(mapv (fn remap-image [image] (update image :image remap)) %))
                               (update :animations #(mapv (fn remap-animation [animation]
                                                            (update animation :images (fn remap-frames [images] (mapv (fn remap-frame [image] (update image :image remap)) images)))) %)))
                   "gui" (let [frame-map (coll/into-> files {} (filter #(= "image" (:kind %)))
                                           (map (fn renamed-frame [entry]
                                                  [(string/replace (.getName (io/file (:source entry))) #"\.png$" "")
                                                   (string/replace (.getName (io/file (:target entry))) #"\.png$" "")])))
                               local-textures (set (mapv :name (filterv #(contains? remap (:texture %)) (:textures data))))]
                           (-> data
                               (update :textures #(mapv (fn remap-texture [texture] (update texture :texture (fn replace-texture [path] (get remap path path)))) %))
                               (update :nodes #(mapv (fn remap-node [node]
                                                       (let [node (cond-> node
                                                                    (contains? remap (:template node)) (update :template remap))
                                                             [texture frame] (string/split (:texture node "") #"/" 2)]
                                                         (if (contains? local-textures texture)
                                                           (assoc node :texture (str texture "/" (get frame-map frame frame))) node))) %)))))]
        (.getBytes (serialized kind data) "UTF-8")))))

(defn- bytes-hash [bytes]
  (with-open [stream (java.io.ByteArrayInputStream. bytes)] (digest/stream->sha256-hex stream)))

(defn- reference-records [root files]
  (let [boundary (.toPath (.getCanonicalFile (io/file root)))
        targets (set (mapv :target files))
        extensions #{"gui" "atlas" "go" "collection" "sprite" "factory" "collectionfactory" "model" "mesh" "particlefx" "material" "label" "tilesource" "tilemap" "script" "gui_script" "lua" "project"}]
    (loop [pending [(io/file root)] found []]
      (if-not (coll/not-empty pending)
        (vec (sort-by :path found))
        (let [^File file (peek pending) pending (pop pending) name (.getName file)]
          (cond
            (or (string/starts-with? name ".") (contains? #{"build" "ai_generated" "ai-workspace" "codex-attachments"} name)) (recur pending found)
            (.isDirectory file)
            (do
              (when-not (= boundary (.toPath (.getCanonicalFile file)))
                (craft-project/resource-file root (str "/" (string/replace (str (.relativize boundary (.toPath file))) "\\" "/"))))
              (recur (into pending (.listFiles file)) found))
            (and (contains? extensions (string/lower-case (last (string/split name #"\.")))) (<= (.length file) (* 8 1024 1024)))
            (let [path (str "/" (string/replace (str (.relativize boundary (.toPath file))) "\\" "/"))
                  _ (craft-project/resource-file root path)
                  text (slurp file :encoding "UTF-8")
                  used (coll/any? #(or (string/includes? text (pr-str (:source %)))
                                       (string/includes? text (str "'" (:source %) "'"))) files)]
              (recur pending (cond-> found (and used (not (contains? targets path))) (conj {:path path :sha256 (file-hash file)}))))
            :else (recur pending found)))))))

(defn- plan-impl [root manifest-path module gui-source]
  (let [manifest (cond-> (source-manifest root manifest-path)
                   gui-source (assoc :gui gui-source))
        _ (ensure-rules! root)
        rule (:gui (validate-rules! root (read-json (craft-project/resource-file root rules-path))))
        module (production/slug module (:module manifest))
        expand (fn expand-template [key] (string/replace (get rule key "") "{module}" module))
        files (into (mapv (fn plan-image [asset]
                            {:kind "image" :source (:file asset)
                             :target (str (expand :images) "/" (.getName (io/file (:file asset))))}) (:assets manifest))
                    [{:kind "atlas" :source (:atlas manifest) :target (expand :atlas)}
                     {:kind "gui" :source (:gui manifest) :target (expand :scene)}])
        files (mapv (fn snapshot-file [entry]
                      (destination! root (:target entry) (case (:kind entry) "image" ".png" "atlas" ".atlas" "gui" ".gui"))
                      (let [before (file-hash (craft-project/resource-file root (:target entry)))
                            after (bytes-hash (content root files entry rule))]
                        (assoc entry :sourceHash (file-hash (craft-project/resource-file root (:source entry)))
                               :targetHash before :operation (cond (nil? before) "create" (= before after) "reuse" :else "conflict")))) files)]
    (cond-> {:format "defold.resource-import" :version 1 :id (str (UUID/randomUUID)) :module module
             :sourceManifest manifest-path :sourceHash (file-hash (craft-project/resource-file root manifest-path))
             :rulesPath rules-path :rules rule :files files :references (reference-records root files)}
      gui-source (assoc :sourceGui gui-source))))

(defn plan! [root manifest-path module]
  (plan-impl root manifest-path module nil))

(defn plan-existing-gui! [root manifest-path module gui-source]
  ;; A completed runtime layout can replace a generated component preview as
  ;; the source. Pin its bytes and include all runtime reference changes in the
  ;; same reviewed transaction; never copy the preview over the completed GUI.
  (destination! root gui-source ".gui")
  (plan-impl root manifest-path module gui-source))

(defn plan-text [plan]
  (str "# 资源入库方案 · " (:module plan)
       "\n\n确认后由负责人队列执行：源文件/目标检查 → 隔离候选工程编译 → 基线复核 → 正式写入 → 收据。候选失败不写正式目录；编译通过仍需运行与人工验收。原始产物保留。可以修改下面 JSON 中的 target；重名文件必须将 operation 从 conflict 明确改为 replace。"
       (when-let [source (:sourceGui plan)]
         (str "\n\n完整布局来源：`" source "`。保留其节点、模板和控制器绑定，仅重定向已列出的资源引用；不使用素材拆图预览作为页面布局。"))
       "\n\n| 类型 | 正式资源 | 操作 |\n| --- | --- | --- |\n"
       (coll/join-to-string "\n" (mapv #(str "| " (:kind %) " | `" (:target %) "` | " (:operation %) " |") (:files plan)))
       "\n\n工程引用更新（保留原文件备份）：\n"
       (if (coll/not-empty (:references plan)) (coll/join-to-string "\n" (mapv #(str "- `" (:path %) "`") (:references plan))) "无外部引用需要更新。")
       "\n\n```json\n" (json/write-str plan :indent true :escape-slash false) "\n```"))

(defn parse-plan [text]
  (try
    (json/read-str (or (second (re-find #"(?s)```json\s*\n(.*?)\n```" text)) text) :key-fn keyword)
    (catch Exception e (throw (ex-info "入库方案 JSON 无效，草稿已保留" {} e)))))

(defn prepare! [root plan]
  (when-not (and (= "defold.resource-import" (:format plan)) (= 1 (:version plan))
                 (string? (:id plan)) (re-matches #"[a-f0-9-]{36}" (:id plan))
                 (vector? (:files plan)) (<= 3 (count (:files plan)) 258)
                 (<= (count (:references plan)) 256))
    (throw (ex-info "入库方案格式或版本无效" {})))
  (let [manifest (cond-> (source-manifest root (:sourceManifest plan))
                   (:sourceGui plan) (assoc :gui (:sourceGui plan)))
        _ (when-let [source (:sourceGui plan)] (destination! root source ".gui"))
        sources (into #{(:atlas manifest) (:gui manifest)} (mapv :file (:assets manifest)))
        files (:files plan) rule (:rules plan)
        targets (mapv #(string/lower-case (:target % "")) files)]
    (when-not (and (= (:sourceHash plan) (file-hash (craft-project/resource-file root (:sourceManifest plan))))
                   (= sources (set (mapv :source files))) (= (count sources) (count files))
                   (= (count targets) (count (set targets)))
                   (= 1 (count (filterv #(= "atlas" (:kind %)) files)))
                   (= 1 (count (filterv #(= "gui" (:kind %)) files)))
                   (coll/every? #(and (integer? %) (<= 1 % 8192)) [(:maxImageSize rule) (:maxAtlasSize rule)])
                   (coll/every? #(and (integer? %) (<= 0 % 32)) [(:innerPadding rule) (:extrudeBorders rule)]))
      (throw (ex-info "产物已变化、目标路径重复，或入库规则无效，请重新生成方案" {})))
    (let [prepared (mapv (fn prepare-file [entry]
                           (let [{:keys [kind source target operation sourceHash targetHash]} entry
                                 extension (case kind "image" ".png" "atlas" ".atlas" "gui" ".gui" (throw (ex-info "不支持的入库类型" {})))
                                 file (destination! root target extension)
                                 expected-kind (cond (= source (:atlas manifest)) "atlas" (= source (:gui manifest)) "gui" :else "image")
                                 bytes (content root files entry rule)
                                 hash (bytes-hash bytes)]
                             (when-not (and (= expected-kind kind) (= sourceHash (file-hash (craft-project/resource-file root source)))
                                            (= targetHash (file-hash file))
                                            (case operation "create" (nil? targetHash) "reuse" (= hash targetHash) "replace" (string? targetHash) false))
                               (throw (ex-info "源文件或目标已变化，或重名冲突尚未确认，请重新生成入库方案" {:path target})))
                             (when (= "image" kind)
                               (let [image (production/read-image (craft-project/resource-file root source))]
                                 (try
                                   (when (or (> (.getWidth image) (:maxImageSize rule)) (> (.getHeight image) (:maxImageSize rule)))
                                     (throw (ex-info "图片超过项目入库尺寸限制" {:path source})))
                                   (finally (.flush image)))))
                             (assoc entry :bytes bytes :hash hash))) files)
          atlas (:data (production/validate-resource-text :atlas (String. ^bytes (:bytes (coll/first-where #(= "atlas" (:kind %)) prepared)) "UTF-8")))
          frames (mapv #(string/replace (.getName (io/file (:target %))) #"\.png$" "") (filterv #(= "image" (:kind %)) files))
          gui (:data (production/validate-resource-text :gui (String. ^bytes (:bytes (coll/first-where #(= "gui" (:kind %)) prepared)) "UTF-8")))
          dependencies (into [(:material gui) (:script gui)]
                             (coll/into-> (into (vec (:textures gui)) (:fonts gui)) [] (mapcat #(into [] (coll/vals %)))))]
      (when (coll/any? #(and (string? %) (re-find #"(?i)/ai_generated/" %)) dependencies)
        (throw (ex-info "正式 GUI 仍依赖暂存资源，请将所有依赖纳入入库方案" {})))
      (when-not (= (count frames) (count (set frames)))
        (throw (ex-info "图集帧名重复，请调整图片文件名" {})))
      (when (or (coll/not-empty (:animations atlas)) (not (string/blank? (:rename-patterns atlas))))
        (throw (ex-info "当前 GUI 入库要求静态图集；动画或帧重命名规则需要单独处理" {})))
      (when-not (= (set (mapv :image (:images atlas))) (set (mapv :target (filterv #(= "image" (:kind %)) files))))
        (throw (ex-info "图集存在未纳入方案的图片" {})))
      (let [detected (reference-records root files)
            expected (vec (:references plan))]
        (when-not (= detected (vec (sort-by :path expected)))
          (throw (ex-info "工程引用已变化，请重新生成入库方案" {})))
        (into prepared
              (mapv (fn prepare-reference [{:keys [path sha256]}]
                      (destination! root path "")
                      (let [entry {:kind "reference" :source path :target path :sourceHash sha256 :targetHash sha256 :operation "replace"}
                            bytes (content root files entry rule)]
                        (assoc entry :bytes bytes :hash (bytes-hash bytes)))) expected))))))

(defn inspect-plan! [root plan]
  (when-not (and (string? (:id plan)) (re-matches #"[a-f0-9-]{36}" (:id plan)))
    (throw (ex-info "入库事务编号无效" {})))
  (let [report (publication/new-report plan)
        check {:title "源文件、路径、重名与引用检查" :status "passed"}]
    (try
      (let [prepared (prepare! root plan)]
        (publication/save-report! root (assoc report :status "review-ready" :checks [check]
                                              :changes (publication/changes root prepared))))
      (catch Exception e
        (publication/save-report! root (assoc report :status "failed" :checks [(assoc check :status "failed")]
                                              :error (.getMessage e) :details (pr-str (ex-data e))))))))

(defn- transaction-directory [root id]
  (let [boundary (.toPath (.getCanonicalFile (io/file root)))
        directory (io/file (.getCanonicalFile (io/file root)) ".internal" "ai-resource-import" id)]
    (loop [^File candidate directory]
      (when (and candidate (not= boundary (.toPath candidate)))
        (when (or (Files/isSymbolicLink (.toPath candidate))
                  (not (.startsWith (.toPath (.getCanonicalFile candidate)) boundary)))
          (throw (ex-info "入库事务目录越界" {})))
        (recur (.getParentFile candidate))))
    directory))

(defn- save-journal! [directory journal]
  (write-atomic! (io/file directory "journal.json") (.getBytes (json/write-str journal) "UTF-8")))

(defn- rollback! [root directory journal]
  (let [conflicts (reduce (fn restore-file [conflicts entry]
                            (let [target (craft-project/resource-file root (:target entry))
                                  current (file-hash target)]
                              (cond
                                (= current (:targetHash entry)) conflicts
                                (not= current (:hash entry)) (conj conflicts (:target entry))
                                (:targetHash entry) (do (write-atomic! target (Files/readAllBytes (.toPath (io/file directory (:backup entry))))) conflicts)
                                :else (do (Files/deleteIfExists (.toPath target)) conflicts)))) [] (reverse (:entries journal)))]
    (save-journal! directory (assoc journal :status (if (coll/not-empty conflicts) "recovery-conflict" "rolled-back") :conflicts conflicts))
    (when (coll/not-empty conflicts)
      (throw (ex-info "回退遇到人工修改，已保留文件和事务备份，请处理冲突" {:paths conflicts})))))

(defn apply! [root plan validate!]
  (when-not (and (string? (:id plan)) (re-matches #"[a-f0-9-]{36}" (:id plan)))
    (throw (ex-info "入库事务编号无效" {})))
  (let [^File directory (transaction-directory root (:id plan))
        journal-file (io/file directory "journal.json")
        lock-file (io/file (.getParentFile directory) "admission.lock")]
    (io/make-parents lock-file)
    (when (Files/isSymbolicLink (.toPath lock-file)) (throw (ex-info "入库锁文件不能是符号链接" {})))
    (with-open [channel (FileChannel/open (.toPath lock-file) (into-array java.nio.file.OpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))
                ^java.nio.channels.FileLock _lock (try (or (.tryLock channel) (throw (ex-info "另一个入库事务正在执行，请稍后重试" {})))
                                                       (catch OverlappingFileLockException e (throw (ex-info "另一个入库事务正在执行，请稍后重试" {} e))))]
      (let [previous (when (.isFile journal-file) (read-json journal-file))
            fingerprint (digest/string->sha256-hex (pr-str plan))]
        (when (and previous (not= fingerprint (:fingerprint previous)))
          (throw (ex-info "入库方案已执行过；修改后请重新生成方案，不能复用事务编号" {})))
        (if (= "imported" (:status previous))
          (do
            (when-let [expected (:final-baseline-hash previous)]
              (when-not (= expected (publication/baseline-hash (publication/inventory root)))
                (throw (ex-info "已入库工程基线已变化，请重新生成入库方案" {}))))
            (doseq [entry (:entries previous)]
              (when-not (= (:hash entry) (file-hash (craft-project/resource-file root (:target entry))))
                (throw (ex-info "已入库文件被修改，请重新生成入库方案" {:path (:target entry)}))))
            (:output previous))
          (do
            (when (contains? #{"applying" "recovery-conflict"} (:status previous)) (rollback! root directory previous))
            (let [report (atom (publication/new-report plan))
                  check! (fn publication-check [title]
                           (swap! report update :checks #(conj (mapv (fn pass-check [item] (assoc item :status "passed")) %) {:title title :status "running"}))
                           (publication/save-report! root @report))]
              (try
                (check! "源文件、路径、重名与引用检查")
                (let [prepared (prepare! root plan)
                      baseline (publication/inventory root)
                      _ (swap! report assoc :changes (publication/changes root prepared) :baseline-hash (publication/baseline-hash baseline))
                      _ (check! "隔离候选工程编译")
                      gui (:target (coll/first-where #(= "gui" (:kind %)) prepared))
                      atlas (:target (coll/first-where #(= "atlas" (:kind %)) prepared))
                      validation (publication/candidate! root directory baseline prepared
                                                         {:gui gui :atlas atlas :paths (set (mapv :target prepared)) :rules (:rules plan)
                                                          :source-paths (into ["/game.project"] (mapv :source prepared))
                                                          :reference-paths (mapv :target (filterv #(= "reference" (:kind %)) prepared))} validate!)
                      _ (swap! report assoc :validation validation)
                      _ (check! "正式写入前基线复核")
                      _ (prepare! root plan)
                      _ (publication/assert-baseline! root baseline)
                      entries (mapv (fn backup-file [index entry]
                                      (let [backup (str index ".backup")
                                            target (craft-project/resource-file root (:target entry))]
                                        (when (:targetHash entry) (write-atomic! (io/file directory backup) (Files/readAllBytes (.toPath target))))
                                        (assoc (dissoc entry :bytes) :backup backup))) (range (count prepared)) prepared)
                      journal {:status "applying" :fingerprint fingerprint :entries entries
                               :final-baseline-hash (:final-baseline-hash validation)}]
                  (check! "受控写入与最终基线校验")
                  (save-journal! directory journal)
                  (try
                    (doseq [entry prepared :when (not= "reuse" (:operation entry))]
                      (let [target (destination! root (:target entry) (case (:kind entry) "image" ".png" "atlas" ".atlas" "gui" ".gui" "reference" ""))]
                        (when-not (= (:targetHash entry) (file-hash target)) (throw (ex-info "写入前目标已变化，入库已停止" {:path (:target entry)})))
                        (write-atomic! target (:bytes entry))))
                    (let [output {:gui gui :atlas atlas :paths (set (mapv :target prepared)) :count (count (filterv #(= "image" (:kind %)) prepared))
                                  :status "imported" :import-id (:id plan) :source-manifest (:sourceManifest plan) :validation validation
                                  :file-hashes (mapv #(hash-map :path (:target %) :sha256 (:hash %)) prepared)}]
                      (doseq [entry prepared]
                        (when-not (and (= (:hash entry) (file-hash (craft-project/resource-file root (:target entry))))
                                       (or (= "reference" (:kind entry))
                                           ;; Match changed reference sources.
                                           (= (or (:hash (coll/first-where #(= (:source entry) (:target %)) prepared)) (:sourceHash entry))
                                              (file-hash (craft-project/resource-file root (:source entry))))))
                          (throw (ex-info "校验期间资源被外部修改，入库已停止" {:path (:target entry)}))))
                      (publication/assert-baseline! root (publication/final-baseline baseline prepared))
                      (swap! report assoc :status "imported" :checks (mapv #(assoc % :status "passed") (:checks @report)))
                      (let [path (publication/save-report! root @report)
                            output (assoc output :publication-report path :publication-report-id (:attempt-id @report))]
                        (save-journal! directory (assoc journal :status "imported" :output (update output :paths vec)))
                        output))
                    (catch Exception e
                      (try (rollback! root directory journal)
                           (catch Exception recovery (throw (ex-info (str (.getMessage e) "；" (.getMessage recovery)) (ex-data recovery) e))))
                      (throw e))))
                (catch Exception e
                  (swap! report assoc :status "failed" :error (.getMessage e) :details (pr-str (ex-data e))
                         :checks (mapv #(cond-> % (= "running" (:status %)) (assoc :status "failed")) (:checks @report)))
                  (let [path (publication/save-report! root @report)]
                    (throw (ex-info (str (.getMessage e) "；可在入库卡片查看检查报告")
                                    (assoc (ex-data e) :publication-report path) e))))))))))))
