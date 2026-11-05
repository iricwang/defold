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


(ns editor.ai-archive
  "Project-owned AI documents, immutable file copies and local indexes."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [editor.craft-project :as craft-project]
            [util.coll :as coll]
            [util.digest :as digest])
  (:import [java.io File]
           [java.net URI]
           [java.nio.file AccessDeniedException Files StandardCopyOption]
           [java.util UUID]))

(set! *warn-on-reflection* true)

(defonce ^:private archive-lock (Object.))

(defn- file-at
  ^File [root relative]
  (craft-project/resource-file root (str "/ai-workspace/" relative)))

(defn- atomic-bytes! [^File file bytes]
  (io/make-parents file)
  (let [temp (.toFile (Files/createTempFile (.toPath (.getParentFile file)) "archive-" ".tmp" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (with-open [out (io/output-stream temp)] (.write out ^bytes bytes))
      ;; Windows readers can briefly deny replacement. Keep the old file intact
      ;; and retry only this failure, for at most 375 ms in total.
      (loop [attempt 0]
        (let [denied (try
                       (Files/move (.toPath temp) (.toPath file) (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))
                       nil
                       (catch AccessDeniedException e
                         (if (= 4 attempt) (throw e) e)))]
          (when denied
            (Thread/sleep (long (* 25 (bit-shift-left 1 attempt))))
            (recur (inc attempt)))))
      (finally (.delete temp)))))

(defn- write-json! [file data]
  (atomic-bytes! file (.getBytes ^String (json/write-str data :escape-slash false) "UTF-8")))

(defn- read-json [^File file]
  (if-not (.isFile file) {} (json/read-str (slurp file) :key-fn keyword)))

(defn ensure-project! [root project-id]
  (locking archive-lock
    (let [file (file-at root "project.json")
          previous (read-json file)
          record (cond-> (assoc (merge {:schemaVersion 1 :id (str (UUID/randomUUID))} previous) :storage "project-local")
                   project-id (assoc :codexProjectId project-id))]
      (when (not= previous record) (write-json! file record))
      (let [readme (file-at root "README.md")]
        ;; Replace only the former generated text; retain user-written docs.
        (when (or (not (.isFile readme))
                  (= (string/replace (slurp readme :encoding "UTF-8") "\r\n" "\n")
                     "# AI 项目资料库\n\nproject.json：项目身份；canvases：画布与离线对话；conversations：公开对话原始记录及 Markdown；attachments：输入附件；artifacts：生成资料；candidates：候选图片；deliveries：游戏成品索引；sync.json：本机同步状态。\n\n本目录是项目资料，不是临时缓存。成品保留在游戏模块目录。CodexCamp 后台按内容校验值保留文件与历史版本；冲突不会自动覆盖，删除不会自动传播。密钥不写入本目录。\n"))
          (atomic-bytes! readme (.getBytes "# AI 项目资料库\n\nproject.json：项目身份；library：可编辑文档与画布；canvases：画布会话状态；conversations：公开对话记录；attachments：输入附件；artifacts：成果版本；candidates：候选图片；deliveries：工程交付记录。\n\n文件直接保存在本项目，离线也可管理。Companion 只连接职员对话，不上传或同步资料。旧 sync.json 停用并保留。已批准成果按内容校验值冻结，修改应形成新版本。备份请包含整个 ai-workspace 和工程资源；密钥不写入本目录。\n" "UTF-8"))))
      record)))

(defn canvas-file
  ^File [root gui]
  (file-at root (str "canvases/" (digest/string->sha256-hex gui) "/state.edn")))

(defn- portable [root data restore]
  (let [prefix (str (.getCanonicalPath (io/file root)) File/separator)
        relocate (fn relocate-path [value]
                   (if-not (string? value)
                     value
                     (cond
                       (and restore (string/starts-with? value "project-file:")) (str prefix (subs value 13))
                       (and restore (string/starts-with? value "project-uri:")) (str (.toURI (io/file root (subs value 12))))
                       (and (not restore) (string/starts-with? value prefix)) (str "project-file:" (subs value (count prefix)))
                       (and (not restore) (string/starts-with? value "file:"))
                       (let [path (.getCanonicalPath (io/file (URI/create value)))]
                         (if (string/starts-with? path prefix) (str "project-uri:" (subs path (count prefix))) value))
                       :else value)))]
    (walk/postwalk
      (fn relocate-record [value]
        (if-not (map? value)
          value
          (reduce-kv (fn relocate-field [result key value]
                       (assoc result key (if (contains? #{:path :local-path :preview :source} key) (relocate value) value)))
                     {} value))) data)))

(defn load-canvas [root gui]
  (let [file (canvas-file root gui)
        legacy (io/file root ".internal" "ai-chat" (str (digest/string->sha256-hex gui) ".edn"))
        source (if (.isFile file) file legacy)]
    (if-not (.isFile source) {} (portable root (edn/read-string (slurp source)) true))))

(defn save-canvas! [root gui state]
  (locking archive-lock
    (ensure-project! root (:project-id state))
    (let [data (portable root (select-keys state [:address :project-id :sessions :canvas :composer-collapsed]) false)
          file (canvas-file root gui)]
      (atomic-bytes! file (.getBytes (pr-str data) "UTF-8"))
      (write-json! (io/file (.getParentFile file) "canvas.json") {:schemaVersion 1 :gui gui :canvas (:canvas data) :sessions (:sessions data)}))))

(defn import-file! [root category ^File source origin]
  (when-not (contains? #{"attachments" "artifacts" "deliveries"} category)
    (throw (ex-info "未知资料类别" {})))
  (when-not (and (.isFile source) (<= (.length source) (* 64 1024 1024)))
    (throw (ex-info "资料不存在或超过 64 MB" {})))
  (locking archive-lock
    (let [sha (with-open [input (io/input-stream source)] (digest/stream->sha256-hex input))
          name (string/replace (.getName source) #"[^\p{L}\p{N}._-]" "_")
          name (if (string/starts-with? name ".") (str "file_" name) name)
          relative (str category "/files/" sha "/" name)
          file (file-at root relative)
          metadata (file-at root (str category "/records/" sha "-" (subs (digest/string->sha256-hex name) 0 12) ".json"))
          previous (read-json metadata)
          record (merge previous {:schemaVersion 1 :sha256 sha :size (.length source) :path (str "ai-workspace/" relative) :name name}
                        {:origins (coll/into-> (conj (vec (:origins previous)) origin) [] (distinct))})]
      (when-not (.isFile file) (atomic-bytes! file (Files/readAllBytes (.toPath source))))
      (when (not= previous record) (write-json! metadata record))
      (assoc record :file file))))

(defn local-file
  "Resolve a local file; never resolve virtual HTTP paths."
  ^File [root path]
  (when-not (and (string? path) (not (string/blank? path)))
    (throw (ex-info "资料缺少本地路径" {:path path})))
  (when (or (re-find #"(?i)^(?:https?|sandbox):" path)
            (string/starts-with? path "//") (string/starts-with? path "\\\\"))
    (throw (ex-info "资料必须是本地文件，请先保存到项目" {:path path})))
  (let [file (cond
               (string/starts-with? path "file:") (io/file (URI/create path))
               (.isAbsolute (io/file path)) (io/file path)
               :else (craft-project/resource-file root (str "/" (string/replace-first path #"^/" ""))))]
    (.getCanonicalFile ^File file)))

(defn receive-artifact! [root reference origin]
  (locking archive-lock
    (let [key (digest/string->sha256-hex (str origin (:path reference)))
          receipt (file-at root (str "artifacts/references/" key ".json"))
          saved (read-json receipt)
          source (local-file root (:source reference))
          cached (when (:path saved) (craft-project/resource-file root (str "/" (:path saved))))]
      (cond
        (.isFile source)
        (let [record (dissoc (import-file! root "artifacts" source (assoc origin :source (:source reference))) :file)]
          (when (not= saved record) (write-json! receipt record))
          record)

        (and cached (.isFile ^File cached)
             (= (:sha256 saved) (with-open [input (io/input-stream cached)] (digest/stream->sha256-hex input))))
        saved

        :else (throw (ex-info "本地资料不存在或副本已损坏，请恢复文件后重试" {:path (:source reference)}))))))

(defn attachment-text [root attachments]
  (if-not (coll/not-empty attachments)
    ""
    (str "\n\n[本地任务附件]\n以下文件已保存在当前工程。请直接读取；图片使用图片查看工具，不经过资料上传。\n"
         (json/write-str
           (mapv (fn describe-attachment [{:keys [path name sha256]}]
                   (let [file (local-file root path)
                         boundary (.toPath (.getCanonicalFile (io/file root)))]
                     (when-not (and (.isFile file) (.startsWith (.toPath file) boundary))
                       (throw (ex-info "附件不在当前项目或已丢失，请重新添加" {:path path})))
                     (let [actual (with-open [input (io/input-stream file)] (digest/stream->sha256-hex input))]
                       (when (and sha256 (not= sha256 actual))
                         (throw (ex-info "附件已修改，请重新添加后执行" {:path path})))
                       {:name name :path (.getCanonicalPath file) :sha256 actual}))) attachments)
           :escape-unicode false :escape-slash false))))

(defn archive-attachments! [root state]
  (let [archive (fn archive-input [entry]
                  (if-not (and (:path entry) (:id entry) (.isFile (io/file (:path entry))))
                    entry
                    (let [record (import-file! root "attachments" (io/file (:path entry)) {:attachmentId (:id entry) :originalPath (:path entry)})]
                      (assoc entry :path (.getCanonicalPath ^File (:file record)) :sha256 (:sha256 record)))))]
    (cond-> state
      (:sessions state)
      (update :sessions (fn archive-sessions [sessions]
                          (coll/into-> sessions {} (map (fn [[id session]] [id (update session :attachments #(mapv archive %))])))))
      (:canvas state)
      (update-in [:canvas :nodes] (fn archive-references [nodes] (mapv #(cond-> % (:references %) (update :references (fn [files] (mapv archive files)))) nodes))))))

(defn archive-state-files! [root state]
  (walk/postwalk
    (fn archive-preview [value]
      (if-not (and (map? value) (string? (:preview value)) (string/starts-with? (:preview value) "file:"))
        value
        (let [source (io/file (URI/create (:preview value)))
              within (.startsWith (.toPath (.getCanonicalFile source)) (.toPath (.getCanonicalFile (io/file root "ai-workspace"))))]
          (if (or within (not (.isFile source)))
            value
            (let [record (import-file! root "artifacts" source {:role "migrated-preview" :source (:source value)})
                  file ^File (:file record)]
              (cond-> (assoc value :preview (str (.toURI file)))
                (:local-path value) (assoc :local-path (.getCanonicalPath file))))))))
    (archive-attachments! root state)))

(defn migrate-legacy! [root]
  (locking archive-lock
    (ensure-project! root nil)
    (doseq [^File source (.listFiles (io/file root ".internal" "ai-chat"))
            :when (and (.isFile source) (re-matches #"[a-f0-9]{64}\.edn" (.getName source)))]
      (let [key (subs (.getName source) 0 64)
            target (file-at root (str "canvases/" key "/state.edn"))]
        (when-not (.isFile target)
          (let [state (archive-state-files! root (edn/read-string (slurp source)))
                data (portable root (select-keys state [:address :project-id :sessions :canvas :composer-collapsed :thread-id]) false)]
            (atomic-bytes! target (.getBytes (pr-str data) "UTF-8"))
            (write-json! (file-at root (str "canvases/" key "/canvas.json"))
                         {:schemaVersion 1 :migrated true :canvas (:canvas data) :sessions (:sessions data)})))))))

(defn saved-thread-ids [root]
  (let [directories (.listFiles (io/file root "ai-workspace" "canvases"))]
    (coll/into-> directories #{}
      (mapcat (fn canvas-threads [directory]
                (let [file (io/file directory "state.edn")
                      saved (when (.isFile file) (edn/read-string (slurp file)))]
                  (into [(:thread-id saved)] (mapv :thread-id (coll/vals (:sessions saved)))))))
      (filter string?))))

(defn save-history! [root snapshot]
  (locking archive-lock
    (let [thread (:thread snapshot)
          key (digest/string->sha256-hex (:id thread))
          file (file-at root (str "conversations/" key "/history.json"))
          old (read-json file)
          turns (reduce (fn merge-turn [all turn]
                          (let [previous (coll/first-where #(= (:id %) (:id turn)) all)
                                items (reduce (fn merge-item [items item]
                                                (conj (filterv #(not= (:id %) (:id item)) items) item)) (vec (:items previous)) (:items turn))
                                next (assoc turn :items items)]
                            (conj (filterv #(not= (:id %) (:id turn)) all) next)))
                        (vec (:turns old)) (:turns thread))
          record (assoc (select-keys thread [:id :projectId :name]) :schemaVersion 1 :turns (vec (sort-by :id turns)))
          markdown (coll/join-to-string "\n\n" (coll/into-> (:turns record) [] (mapcat :items)
                                                 (keep (fn [{:keys [type text content phase]}]
                                                         (case type
                                                           "agentMessage" (when-not (= phase "analysis") (str "### Codex\n\n" text))
                                                           "userMessage" (str "### 用户\n\n" (coll/join-to-string "\n" (coll/into-> content [] (keep :text))))
                                                           nil)))))]
      (when (not= old record)
        (write-json! file record)
        (atomic-bytes! (file-at root (str "conversations/" key "/messages.md")) (.getBytes ^String markdown "UTF-8"))))))

(defn save-delivery! [root output]
  (locking archive-lock
    (let [index (file-at root (str "deliveries/" (digest/string->sha256-hex (:gui output)) ".json"))
          previous (read-json index)
          files (mapv (fn describe-file [path]
                        (let [file (craft-project/resource-file root path)]
                          (if-not (.isFile file)
                            (assoc (or (coll/first-where #(= path (:path %)) (:files previous)) {:path path}) :missing true)
                            (let [archived (import-file! root "deliveries" file {:resource path :gui (:gui output)})]
                              {:path path :archivePath (:path archived) :size (.length file) :sha256 (:sha256 archived)}))))
                      (sort (:paths output)))
          record {:schemaVersion 1 :gui (:gui output) :atlas (:atlas output) :files files}]
      (when (not= previous record) (write-json! index record)))))

(defn refresh-deliveries! [root]
  (doseq [^File file (.listFiles (io/file root "ai-workspace" "deliveries"))
          :when (and (.isFile file) (re-matches #"[a-f0-9]{64}\.json" (.getName file)))]
    (let [record (read-json file)]
      (save-delivery! root {:gui (:gui record) :atlas (:atlas record) :paths (set (mapv :path (:files record)))}))))

(defn manifest [root]
  (locking archive-lock
    (let [directory (file-at root "project.json") directory (.getParentFile directory)]
      (with-open [paths (Files/walk (.toPath directory) (make-array java.nio.file.FileVisitOption 0))]
        (reduce (fn describe-file [result ^java.nio.file.Path path]
                  (let [file (.toFile path)
                        relative (string/replace (str (.relativize (.toPath (.getCanonicalFile (io/file root))) path)) "\\" "/")]
                    (if (or (not (.isFile file)) (Files/isSymbolicLink path)
                            (re-find #"(?:^|/)\." relative)
                            (= relative "ai-workspace/sync.json") (string/ends-with? relative ".tmp"))
                      result
                      (let [file (craft-project/resource-file root (str "/" relative))]
                        (conj result {:path relative :size (.length file)})))))
                [] (vec (.toArray paths)))))))

(defn write-index! [root]
  (locking archive-lock
    (let [entries (manifest root)
          sections [["创作资料" #(string/starts-with? % "ai-workspace/library/")]
                    ["项目配置" #(string/starts-with? % "ai-workspace/config/")]
                    ["对话记录" #(string/ends-with? % "/messages.md")]
                    ["画布与任务" #(string/ends-with? % "/canvas.json")]
                    ["输入附件" #(string/starts-with? % "ai-workspace/attachments/files/")]
                    ["效果图候选" #(string/starts-with? % "ai-workspace/candidates/")]
                    ["生成的原始资料" #(string/starts-with? % "ai-workspace/artifacts/files/")]
                    ["成品来源与校验" #(boolean (re-matches #"ai-workspace/deliveries/[a-f0-9]+\.json" %))]]
          text (str "# AI 项目资料索引\n\n公开对话、附件、生成资料和成品版本均直接保存在本项目，不经过 Companion 同步。\n"
                    (coll/join-to-string "\n"
                                         (mapv (fn index-section [[title matches]]
                                                 (str "\n## " title "\n\n"
                                                      (coll/join-to-string "\n"
                                                                           (coll/into-> entries []
                                                                             (filter #(matches (:path %)))
                                                                             (map (fn index-entry [entry]
                                                                                    (let [relative (subs (:path entry) (count "ai-workspace/"))
                                                                                          file (craft-project/resource-file root (str "/" (:path entry)))
                                                                                          label (cond
                                                                                                  (string/ends-with? relative "/canvas.json") (or (:gui (read-json file)) (str "迁移画布 " (.getName (.getParentFile file))))
                                                                                                  (string/ends-with? relative "/messages.md")
                                                                                                  (let [history (read-json (io/file (.getParentFile file) "history.json"))
                                                                                                        message (coll/first-where #(= "userMessage" (:type %)) (coll/into-> (:turns history) [] (mapcat :items)))
                                                                                                        text (or (:name history) (:text (coll/first-where :text (:content message))) (:id history))
                                                                                                        text (string/replace text #"^\[(效果图|候选评审|资源规划)\][^\n]*\n\n" "")]
                                                                                                    (subs text 0 (min 50 (count text))))
                                                                                                  :else (.getName file))]
                                                                                      (str "- [" (string/replace label #"[\[\]\r\n]" " ") "](<" relative ">) · " (:size entry) " 字节")))))))) sections)))
          file (file-at root "index.md")]
      (when (or (not (.isFile file)) (not= text (slurp file)))
        (atomic-bytes! file (.getBytes text "UTF-8"))))))

(defn refresh-local! [root]
  (ensure-project! root nil)
  (refresh-deliveries! root)
  (write-index! root))
