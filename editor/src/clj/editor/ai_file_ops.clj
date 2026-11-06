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

(ns editor.ai-file-ops
  "Local file plans, reference-aware moves and recoverable file transactions."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.ai-project-work :as work]
            [util.coll :as coll]
            [util.digest :as digest])
  (:import [java.io File ByteArrayInputStream]
           [java.nio.file Files StandardCopyOption]
           [java.time Instant]
           [java.util UUID]))

(set! *warn-on-reflection* true)

(defn file
  ^File [root path]
  (when-not (and (string? path) (string/starts-with? path "/")
                 (not (re-find #"[\\:\x00]" path))
                 (coll/every? #(and (not (string/blank? %)) (not (contains? #{"." ".."} %))
                                    (not (re-find #"[. ]$" %))) (string/split (subs path 1) #"/" -1)))
    (throw (ex-info "文件路径无效" {:path path})))
  (let [boundary (.toPath (.getCanonicalFile (io/file root)))
        target (.normalize (.resolve boundary (subs path 1)))]
    (loop [p target]
      (when (and p (not= boundary p))
        (when (or (Files/isSymbolicLink p) (not= p (.toPath (.getCanonicalFile (.toFile p)))))
          (throw (ex-info "文件管理不跟随符号链接或目录联接" {:path path})))
        (recur (.getParent p))))
    (.toFile target)))

(defn hash-file [^File f]
  (when (.isFile f) (with-open [in (io/input-stream f)] (digest/stream->sha256-hex in))))

(defn hash-bytes [bytes]
  (when bytes (digest/stream->sha256-hex (ByteArrayInputStream. bytes))))

(defn read-json [^File f]
  (when (> (.length f) (* 16 1024 1024)) (throw (ex-info "管理文件超过 16 MiB" {})))
  (json/read-str (slurp f :encoding "UTF-8") :key-fn keyword))

(defn read-verified-bytes [^File f expected]
  (let [bytes (Files/readAllBytes (.toPath f))]
    (when-not (= expected (hash-bytes bytes)) (throw (ex-info "源文件在读取期间变化，请重新预览" {:path (str f)})))
    bytes))

(defn json-bytes [value]
  (.getBytes (json/write-str value :escape-unicode false :escape-slash false) "UTF-8"))

(defn write! [^File target bytes]
  (if-not bytes (Files/deleteIfExists (.toPath target))
          (do (io/make-parents target)
              (let [temp (Files/createTempFile (.toPath (.getParentFile target)) "managed-" ".tmp" (make-array java.nio.file.attribute.FileAttribute 0))]
                (try
                  (Files/write temp ^bytes bytes ^"[Ljava.nio.file.OpenOption;" (make-array java.nio.file.OpenOption 0))
                  (Files/move temp (.toPath target) (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
                  (finally (Files/deleteIfExists temp)))))))

(defn inventory [root]
  (loop [pending [(.getCanonicalFile (io/file root))] out []]
    (when (> (count out) 30000) (throw (ex-info "工程超过三万个文件，请拆分管理范围" {})))
    (if-not (coll/not-empty pending) (vec (sort-by :path out))
            (let [^File f (peek pending) pending (pop pending)
                  path (str "/" (string/replace (str (.relativize (.toPath (.getCanonicalFile (io/file root))) (.toPath f))) "\\" "/"))
                  name (.getName f)
                  excluded (and (not= "/" path)
                                (or (contains? #{"/build" "/.git" "/.internal" "/codex-attachments"} path)
                                    (and (string/starts-with? name ".") (not= path "/.defoldignore"))
                                    (string/ends-with? name ".tmp")))]
              (cond
                excluded (recur pending out)
                (not= "/" path) (let [checked (file root path)]
                                  (if (.isDirectory checked)
                                    (let [children (.listFiles checked)]
                                      (when-not children (throw (ex-info "无法读取工程目录" {:path path})))
                                      (recur (into pending children) out))
                                    (do (when-not (.isFile checked) (throw (ex-info "扫描期间文件已丢失" {:path path})))
                                        (recur pending (conj out {:path path :sha256 (hash-file checked) :size (.length checked)})))))
                :else (recur (into pending (.listFiles f)) out))))))

(defn baseline [records]
  (digest/string->sha256-hex (pr-str (mapv (juxt :path :sha256) records))))

(defn idle! [data]
  (when (coll/any? #(contains? #{"running" "unknown"} (:status %)) (:jobs data))
    (throw (ex-info "有执行中或待核对任务，请先停止并核对后再管理文件" {}))))

(defn plan [root kind writes details]
  (let [paths (mapv :path writes) records (inventory root)]
    (when (> (reduce + 0 (mapv #(if (:bytes %) (alength ^bytes (:bytes %)) 0) writes)) (* 512 1024 1024))
      (throw (ex-info "单次文件变更超过 512 MiB，请分批处理" {})))
    (when-not (= (count paths) (count (set (mapv string/lower-case paths))))
      (throw (ex-info "变更包含重复或大小写冲突的目标路径" {})))
    (merge {:id (str (UUID/randomUUID)) :kind kind :project (.getCanonicalPath (io/file root))
            :baseline (baseline records) :inventory records :created-at (str (Instant/now))
            :writes (mapv #(assoc % :before (hash-file (file root (:path %))) :after (hash-bytes (:bytes %))) writes)} details)))

(defn- rollback! [root journal]
  (let [conflicts (reduce (fn undo-write [out entry]
                            (let [target (file root (:path entry)) current (hash-file target)]
                              (cond
                                (= current (:before entry)) out
                                (not= current (:after entry)) (conj out (:path entry))
                                :else (do (write! target (when (:before entry) (Files/readAllBytes (.toPath (file root (:backup entry)))))) out)))) [] (reverse (:entries journal)))]
    (assoc journal :status (if (coll/not-empty conflicts) "recovery-conflict" "rolled-back") :conflicts conflicts)))

(defn apply! [root proposal]
  (when-not (and (= (:project proposal) (.getCanonicalPath (io/file root))) (re-matches #"[a-f0-9-]{36}" (:id proposal)))
    (throw (ex-info "文件计划不属于当前项目" {})))
  (doseq [entry (:writes proposal)]
    (when-not (= (:after entry) (hash-bytes (:bytes entry)))
      (throw (ex-info "预览内容已变化，请重新生成计划" {}))))
  (let [signature (digest/string->sha256-hex (pr-str [(:kind proposal) (:baseline proposal) (mapv #(select-keys % [:path :before :after]) (:writes proposal))]))
        journal-path (str "/.internal/ai-governance/transactions/" (:id proposal) "/journal.json")
        journal-file (file root journal-path) result (atom nil)]
    (work/transact! root
                    (fn apply-files [data]
                      (idle! data)
                      (doseq [{:keys [path sha256]} (:external-inputs proposal)]
                        (when-not (= sha256 (hash-file (io/file path))) (throw (ex-info "导入包已改变，请重新预览" {}))))
                      (let [prior (when (.isFile journal-file) (read-json journal-file))]
                        (when (and prior (not= signature (:signature prior)))
                          (throw (ex-info "操作身份对应不同变更，请重新预览" {})))
                        (when (and prior (not= "committed" (:status prior)))
                          (let [recovered (rollback! root prior)]
                            (write! journal-file (json-bytes recovered))
                            (when (= "recovery-conflict" (:status recovered)) (throw (ex-info "恢复遇到人工修改，原文件和备份已保留" {:paths (:conflicts recovered)})))))
                        (if (= "committed" (:status prior))
                          (do (doseq [entry (:entries prior)]
                                (when-not (= (:after entry) (hash-file (file root (:path entry)))) (throw (ex-info "操作已执行，文件之后发生修改，请重新预览" {}))))
                              (reset! result prior))
                          (do
                            (when-not (= (:baseline proposal) (baseline (inventory root)))
                              (throw (ex-info "文件或引用已变化，请重新预览；本次未写入" {})))
                            (let [entries (mapv (fn backup-entry [index entry]
                                                  (let [backup (str "/.internal/ai-governance/transactions/" (:id proposal) "/" index ".before")]
                                                    (when (:before entry) (write! (file root backup) (Files/readAllBytes (.toPath (file root (:path entry))))))
                                                    (assoc (dissoc entry :bytes) :backup backup))) (range (count (:writes proposal))) (:writes proposal))
                                  journal (assoc (dissoc proposal :writes :baseline :inventory) :signature signature :entries entries :status "applying")]
                              (write! journal-file (json-bytes journal))
                              (try
                                (doseq [entry (:writes proposal)]
                                  (when-not (= (:before entry) (hash-file (file root (:path entry)))) (throw (ex-info "写入前文件被修改" {:path (:path entry)})))
                                  (write! (file root (:path entry)) (:bytes entry)))
                                (doseq [entry entries]
                                  (when-not (= (:after entry) (hash-file (file root (:path entry)))) (throw (ex-info "写入后文件被外部修改" {:path (:path entry)}))))
                                (let [expected (reduce (fn expected-file [all entry]
                                                         (if (string/starts-with? (:path entry) "/.internal/") all
                                                             (if (:after entry) (assoc all (:path entry) {:path (:path entry) :sha256 (:after entry)}) (dissoc all (:path entry)))))
                                                       (into {} (mapv (juxt :path identity) (:inventory proposal))) entries)]
                                  (when-not (= (baseline (vec (sort-by :path (into [] (coll/vals expected))))) (baseline (inventory root)))
                                    (throw (ex-info "应用期间工程或引用发生外部变化，已停止并回退自身写入" {}))))
                                (let [done (assoc journal :status "committed")]
                                  (write! journal-file (json-bytes done)) (reset! result done))
                                (catch Exception e
                                  (let [recovered (rollback! root journal)]
                                    (write! journal-file (json-bytes recovered))
                                    (throw (ex-info (str (.getMessage e) (when (= "recovery-conflict" (:status recovered)) "；存在恢复冲突，保留人工文件")) {:journal journal-path :conflicts (:conflicts recovered)} e)))))))))
                      data))
    (swap! work/revision update (work/project-key root) (fnil inc 0))
    @result))

(defn library-file!
  ^File [root path]
  (when-not (re-matches #"/ai-workspace/library/[0-9]{2}-[a-z]+/.+" path)
    (throw (ex-info "仅管理创作资料中的普通文件；冻结成果和内部记录不能直接移动或删除" {})))
  (let [f (file root path)]
    (when-not (.isFile f) (throw (ex-info "请选择一个已存在的文件" {})))
    f))

(defn- relative-link [root owner target]
  (string/replace (str (.relativize (.toPath (.getParentFile (file root owner))) (.toPath (file root target)))) "\\" "/"))

(defn remap-markdown [root owner next-owner text mapping]
  (let [rewrite (fn update-link [[whole prefix path suffix]]
                  (let [path (string/replace path #"^<|>$" "")]
                    (if (or (re-find #"^[a-zA-Z]+:|^#" path) (string/starts-with? path "//")) whole
                        (let [[base anchor] (string/split path #"#" 2)
                              base (java.net.URLDecoder/decode (string/replace base "+" "%2B") "UTF-8")
                              boundary (.toPath (.getCanonicalFile (io/file root)))
                              target (.normalize (if (string/starts-with? base "/") (.resolve boundary (subs base 1))
                                                     (.resolve (.toPath (.getParentFile (file root owner))) base)))]
                          (when (and (not= owner next-owner) (not (.startsWith target boundary))) (throw (ex-info "Markdown 引用超出项目，不能自动移动" {:path owner})))
                          (let [resolved (str "/" (string/replace (str (.relativize boundary target)) "\\" "/"))
                                change (or (contains? mapping resolved) (not= owner next-owner))
                                resolved (get mapping resolved resolved)
                                value (when change (if (string/starts-with? base "/") resolved (relative-link root next-owner resolved)))]
                            (if-not change whole
                                    (let [html (boolean (re-find #"[\"']$" prefix))]
                                      (str prefix (when-not html "<") (string/replace value " " "%20") (when anchor (str "#" anchor)) (when-not html ">") suffix))))))))]
    (-> text
        (string/replace #"(!?\[[^\]]*\]\()(<[^>\r\n]+>|[^\s)>]+)((?:\s+\"[^\"]*\")?\))" rewrite)
        (string/replace #"(?m)(^\s{0,3}\[[^\]]+\]:\s*)(<[^>\r\n]+>|[^\s>]+)([^\r\n]*)" rewrite)
        (string/replace #"(?i)(\b(?:src|href)\s*=\s*[\"'])([^\"']+)([\"'])" rewrite))))

(defn move-plan [root source destination remove]
  (let [source-file (library-file! root source)
        destination (if remove (str "/.internal/ai-governance/trash/" (UUID/randomUUID) "/" (.getName source-file)) destination)]
    (when (> (.length source-file) (* 128 1024 1024)) (throw (ex-info "文件超过 128 MiB，请拆分后管理" {})))
    (when (or (= (string/lower-case source) (string/lower-case destination)) (.exists (file root destination)))
      (throw (ex-info "目标已存在或仅大小写变化，本次未覆盖" {})))
    (when-not (or remove (re-matches #"/ai-workspace/library/[0-9]{2}-[a-z]+/.+" destination))
      (throw (ex-info "目标必须位于创作资料分类中" {})))
    (let [records (inventory root)
          text-files (filterv #(re-find #"(?i)\.(md|json|aicanvas|lua|gui|script|atlas|collection|go|txt)$" (:path %)) records)
          updates (atom []) blockers (atom [])
          source-bytes (Files/readAllBytes (.toPath source-file))
          moved-bytes (atom source-bytes)]
      (doseq [{:keys [path size]} text-files]
        (when (> size (* 16 1024 1024)) (throw (ex-info "引用文件过大，不能安全扫描" {:path path})))
        (let [text (slurp (file root path) :encoding "UTF-8")
              md (string/ends-with? (string/lower-case path) ".md")
              next-owner (if (= path source) destination path)
              ^String rewritten (if md (remap-markdown root path (if remove path next-owner) text {source (if remove source destination)})
                                    (-> text (string/replace (json/write-str source :escape-slash false :escape-unicode false) (json/write-str destination :escape-slash false :escape-unicode false))
                                        (string/replace (json/write-str source) (json/write-str destination))))
              has-reference (or (string/includes? text source) (not= text rewritten)
                                (and remove md (not= text (remap-markdown root path path text {source "/ai-workspace/library/10-delivery/deleted-reference"}))))
              remaining (if md (remap-markdown root path path text {source "/__managed_reference__"})
                            (-> text (string/replace (json/write-str source :escape-slash false :escape-unicode false) "\"__managed_reference__\"")
                                (string/replace (json/write-str source) "\"__managed_reference__\"")))
              unresolved (string/includes? (string/lower-case remaining) (string/lower-case (.getName source-file)))
              writable (and (string/starts-with? path "/ai-workspace/library/") (re-find #"\.(md|aicanvas|json|txt)$" path))]
          (cond
            (= path source) (when (and md (not remove)) (reset! moved-bytes (.getBytes rewritten "UTF-8")))
            (or unresolved (and has-reference (or remove (not writable) (= text rewritten)))) (swap! blockers conj path)
            (not= text rewritten) (swap! updates conj {:path path :bytes (.getBytes rewritten "UTF-8")}))))
      (when (coll/not-empty @blockers)
        (throw (ex-info (str "文件仍被引用或属于固定历史，不能" (if remove "删除" "自动移动") "：\n" (coll/join-to-string "\n" @blockers)) {:references @blockers})))
      (plan root (if remove "trash" "move")
            (into [{:path destination :bytes @moved-bytes} {:path source :bytes nil}] @updates)
            {:source source :destination destination :references (mapv :path @updates)
             :notice (if remove "移入可恢复回收站，保留原文件内容；不永久删除。"
                         (str "移动文件并更新 " (count @updates) " 处资料引用。相关页签需要关闭后重新打开。"))}))))

(defn history [root]
  (let [directory (file root "/.internal/ai-governance/transactions")]
    (if-not (.isDirectory directory) []
            (vec (sort-by :created-at coll/descending-order
                          (coll/into-> (.listFiles directory) []
                            (keep (fn read-entry [^File dir]
                                    (let [journal (file root (str "/.internal/ai-governance/transactions/" (.getName dir) "/journal.json"))]
                                      (when (.isFile journal) (read-json journal)))))))))))

(defn restore-file-plan [root id]
  (let [record (or (coll/first-where #(= id (:id %)) (history root)) (throw (ex-info "找不到操作记录" {})))]
    (if-not (= "committed" (:status record))
      (let [writes (coll/into-> (:entries record) []
                     (keep (fn recover-entry [entry]
                             (let [current (hash-file (file root (:path entry)))]
                               (when-not (= current (:before entry))
                                 (when-not (= current (:after entry))
                                   (throw (ex-info "恢复位置已有人工修改，文件与原副本已保留" {:path (:path entry)}))))
                               (when-not (= current (:before entry))
                                 (let [bytes (when (:before entry) (Files/readAllBytes (.toPath (file root (:backup entry)))))]
                                   (when-not (= (:before entry) (hash-bytes bytes)) (throw (ex-info "恢复副本摘要不一致" {:path (:path entry)})))
                                   {:path (:path entry) :bytes bytes}))))))]
        (plan root "recover-interrupted" writes {:restores id :notice "只恢复中断操作自身写入；存在人工修改时停止，不覆盖。"}))
      (case (:kind record)
        "move" (move-plan root (:destination record) (:source record) false)
        "trash" (let [source (file root (:destination record)) target (file root (:source record))]
                  (when (or (.exists target) (not= (:after (first (:entries record))) (hash-file source)))
                    (throw (ex-info "原位置已被占用或回收文件已变化，不能覆盖恢复" {})))
                  (plan root "restore-trash" [{:path (:source record) :bytes (Files/readAllBytes (.toPath source))} {:path (:destination record) :bytes nil}]
                        {:restores id}))
        (throw (ex-info "此操作请通过完整性备份恢复；只允许直接恢复受控移动和回收操作" {}))))))
