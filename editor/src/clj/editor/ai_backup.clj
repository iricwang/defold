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

(ns editor.ai-backup
  "Verified backups and history-isolated cross-project migration."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.ai-file-ops :as files]
            [editor.ai-project-work :as work]
            [editor.ai-resource-import :as admission]
            [editor.ai-roles :as roles]
            [editor.ai-templates :as templates]
            [util.coll :as coll]
            [util.digest :as digest])
  (:import [java.nio.file Files StandardCopyOption]
           [java.time Instant]
           [java.util UUID]))

(defn- included? [path]
  (not (or (coll/any? #(string/starts-with? path %) ["/build/" "/codex-attachments/" "/ai-workspace/conversations/" "/ai-workspace/canvases/" "/ai-workspace/migrations/"])
           (and (not= path "/.defoldignore") (coll/any? #(string/starts-with? % ".") (string/split path #"/")))
           (contains? #{"/ai-workspace/project.json" "/ai-workspace/sync.json"} path))))

(defn- checksum [manifest]
  (digest/string->sha256-hex (pr-str [(:format manifest) (:version manifest) (:id manifest) (:project manifest) (:created-at manifest)
                                      (mapv (juxt :path :sha256 :size) (:files manifest))])))

(defn- passive-path [path]
  (str path (cond (string/ends-with? path ".aicanvas") ".history.json"
                  (re-find #"(?i)(\.editor_script|/ext\.manifest)$" path) ".history.txt")))

(defn missing-artifacts [data records]
  (let [by-path (into {} (mapv (juxt :path :sha256) records))]
    (coll/into-> (:artifacts data) []
      (mapcat :files)
      (keep #(when (not= (:sha256 %) (get by-path (:path %))) (:path %))))))

(defn create! [root parent]
  (let [id (str (UUID/randomUUID)) directory (io/file parent (str "ai-backup-" id)) result (atom nil)]
    (when (.startsWith (.toPath (.getCanonicalFile directory)) (.toPath (.getCanonicalFile (io/file root))))
      (throw (ex-info "请选择当前项目之外的备份目录，避免备份包含自身" {})))
    (work/transact! root
                    (fn snapshot-project [data]
                      (files/idle! data)
                      (let [records (filterv #(included? (:path %)) (files/inventory root))
                            missing (missing-artifacts data records)]
                        (when (> (reduce + 0 (mapv :size records)) (* 256 1024 1024))
                          (throw (ex-info "当前备份支持 256 MiB 以内的工程，请拆分后处理" {})))
                        (when (coll/not-empty missing) (throw (ex-info "固定成果已缺失或被修改，不能创建完整备份" {:missing missing})))
                        (Files/createDirectory (.toPath directory) (make-array java.nio.file.attribute.FileAttribute 0))
                        (doseq [{:keys [path sha256]} records]
                          (let [blob (files/file directory (str "/files/" sha256))]
                            (io/make-parents blob)
                            (when-not (.exists blob) (Files/copy (.toPath (files/file root path)) (.toPath blob) (make-array StandardCopyOption 0)))
                            (when-not (= sha256 (files/hash-file blob)) (throw (ex-info "复制期间文件变化，备份未完成" {:path path})))))
                        (when-not (= (files/baseline records) (files/baseline (filterv #(included? (:path %)) (files/inventory root))))
                          (throw (ex-info "工程在备份期间变化，未发布备份清单" {})))
                        (let [manifest {:format "defold.ai-project-backup" :version 1 :id id :project (.getCanonicalPath (io/file root))
                                        :created-at (str (Instant/now)) :files records}]
                          (files/write! (files/file directory "/manifest.json") (files/json-bytes (assoc manifest :checksum (checksum manifest))))
                          (reset! result directory))) data))
    @result))

(defn verify [directory]
  (let [manifest-file (files/file directory "/manifest.json")
        manifest-sha256 (files/hash-file manifest-file)
        manifest (files/read-json manifest-file)]
    (when-not (= manifest-sha256 (files/hash-file manifest-file)) (throw (ex-info "备份清单在读取期间变化" {})))
    (when-not (and (= "defold.ai-project-backup" (:format manifest)) (= 1 (:version manifest))
                   (vector? (:files manifest)) (<= (count (:files manifest)) 30000)
                   (string? (:id manifest)) (re-matches #"[a-f0-9-]{36}" (:id manifest))
                   (= (:checksum manifest) (checksum manifest))
                   (= (count (:files manifest)) (count (set (mapv #(string/lower-case (:path %)) (:files manifest))))))
      (throw (ex-info "备份清单格式、摘要或路径唯一性校验失败" {})))
    (when (> (reduce + 0 (mapv :size (:files manifest))) (* 256 1024 1024))
      (throw (ex-info "备份超过 256 MiB，当前迁移请拆分工程后处理" {})))
    (let [issues (coll/into-> (:files manifest) []
                   (keep (fn verify-file [{:keys [path sha256 size]}]
                           (files/file directory path)
                           (when-not (included? path) (throw (ex-info "备份包含内部、缓存或连接文件" {:path path})))
                           (when-not (and (re-matches #"[a-f0-9]{64}" (or sha256 "")) (integer? size) (<= 0 size))
                             (throw (ex-info "备份文件摘要或大小无效" {:path path})))
                           (let [blob (files/file directory (str "/files/" sha256))]
                             (when-not (and (= size (.length blob)) (= sha256 (files/hash-file blob))) path)))))
          ledger (coll/first-where #(= work/path (:path %)) (:files manifest))
          missing (when (and ledger (not (contains? (set issues) (:path ledger))))
                    (missing-artifacts (files/read-json (files/file directory (str "/files/" (:sha256 ledger)))) (:files manifest)))]
      (assoc manifest :issues (into issues missing) :manifest-sha256 manifest-sha256))))

(defn restore-plan [root directory]
  (let [manifest (verify directory)]
    (when (coll/not-empty (:issues manifest)) (throw (ex-info "备份文件缺失或被修改，不能恢复" {:missing (:issues manifest)})))
    (when-not (= (:project manifest) (.getCanonicalPath (io/file root)))
      (throw (ex-info "此备份属于其他项目，请选择迁移资料，不继承原项目审批状态" {})))
    (let [writes (mapv (fn restore-entry [entry]
                         (when-not (included? (:path entry)) (throw (ex-info "备份包含不应恢复的连接数据" {})))
                         (let [blob (files/file directory (str "/files/" (:sha256 entry)))
                               bytes (files/read-verified-bytes blob (:sha256 entry))]
                           {:path (:path entry) :bytes (if (= "/ai-workspace/project/work.json" (:path entry))
                                                         (files/json-bytes (update (json/read-str (String. ^bytes bytes "UTF-8") :key-fn keyword) :jobs
                                                                                   #(mapv (fn pause-restored [job] (if (contains? #{"queued" "running" "unknown"} (:status job))
                                                                                                                     (assoc (dissoc job :claim-token) :status "paused" :message "备份恢复后需人工核对再继续") job)) %)))
                                                         bytes)})) (:files manifest))]
      (files/plan root "restore-backup" (filterv #(not= (files/hash-bytes (:bytes %)) (files/hash-file (files/file root (:path %)))) writes)
                  {:backup (:id manifest) :external-inputs (into [{:path (.getCanonicalPath (files/file directory "/manifest.json")) :sha256 (:manifest-sha256 manifest)}]
                                                                 (mapv #(hash-map :path (.getCanonicalPath (files/file directory (str "/files/" (:sha256 %)))) :sha256 (:sha256 %)) (:files manifest)))
                   :notice "恢复原项目固定版本、台账与证据；不恢复聊天连接。备份之外的新文件保留，覆盖项会保留事务前副本。恢复后请重新打开相关页签并核对执行状态。"}))))

(defn migrate-plan [root directory]
  (let [manifest (verify directory)
        _ (when (coll/not-empty (:issues manifest)) (throw (ex-info "备份不完整，不能迁移" {:missing (:issues manifest)})))
        records (:files manifest)
        by-path (into {} (mapv (juxt :path identity) records))
        blob (fn source-blob [path] (files/file directory (str "/files/" (:sha256 (get by-path path)))))
        bytes-at (fn source-bytes [path] (files/read-verified-bytes (blob path) (:sha256 (get by-path path))))
        read-at (fn read-config [path fallback] (if (get by-path path) (json/read-str (String. ^bytes (bytes-at path) "UTF-8") :key-fn keyword) fallback))
        package {:format "defold.ai-workbench-template" :version 1
                 :role-types (read-at roles/config-path (roles/default-config))
                 :staff (mapv #(select-keys % templates/staff-fields) (:nodes (read-at roles/document-path {:nodes []})))
                 :resource-rules (read-at admission/rules-path admission/default-rules)
                 :documents (coll/into-> records []
                              (filter #(and (string/starts-with? (:path %) "/ai-workspace/library/") (string/ends-with? (:path %) ".aicanvas")))
                              (map #(hash-map :path (:path %) :document (read-at (:path %) nil))))}
        template-plan (templates/import-plan root package false)
        prefix (str "/ai-workspace/migrations/" (:id manifest) "/project")
        archived-path (fn history-path [path] (str prefix (passive-path path)))
        mapping (into {} (mapv #(vector (:path %) (archived-path (:path %))) records))
        library-records (filterv #(and (string/starts-with? (:path %) "/ai-workspace/library/") (not (string/ends-with? (:path %) ".aicanvas"))) records)
        mapping (reduce (fn map-document [all entry]
                          (let [parts (string/split (:path entry) #"/" 5)]
                            (assoc all (:path entry) (passive-path (str "/ai-workspace/library/" (nth parts 3) "/migration-" (subs (:id manifest) 0 8) "/" (nth parts 4)))))) mapping library-records)
        history-writes (mapv #(hash-map :path (archived-path (:path %)) :bytes (bytes-at (:path %))) records)
        document-writes (mapv (fn copy-document [entry]
                                (let [path (:path entry) target (get mapping path)
                                      bytes (if (string/ends-with? (string/lower-case path) ".md")
                                              (.getBytes (files/remap-markdown root path target (String. ^bytes (bytes-at path) "UTF-8") mapping) "UTF-8")
                                              (bytes-at path))]
                                  {:path target :bytes bytes})) library-records)
        extra (into (conj history-writes
                          {:path (str "/ai-workspace/migrations/" (:id manifest) "/manifest.json")
                           :bytes (files/json-bytes {:format "defold.ai-migration-history" :version 1
                                                     :source-manifest (dissoc manifest :issues :manifest-sha256)
                                                     :files (mapv #(assoc % :path (archived-path (:path %))) records)})}) document-writes)]
    (doseq [entry extra]
      (when (and (.exists (files/file root (:path entry))) (not= (files/hash-file (files/file root (:path entry))) (files/hash-bytes (:bytes entry))))
        (throw (ex-info "迁移目标已有不同内容，不覆盖人工修改" {:path (:path entry)}))))
    (files/plan root "migrate-project" (into (:writes template-plan) (filterv #(not= (files/hash-file (files/file root (:path %))) (files/hash-bytes (:bytes %))) extra))
                {:backup (:id manifest) :history-root prefix :documents (:documents template-plan)
                 :role-mapping (:role-mapping template-plan) :staff-mapping (:staff-mapping template-plan)
                 :summary (:summary template-plan)
                 :external-inputs (into [{:path (.getCanonicalPath (files/file directory "/manifest.json")) :sha256 (:manifest-sha256 manifest)}]
                                        (mapv #(hash-map :path (.getCanonicalPath (blob (:path %))) :sha256 (:sha256 %)) records))
                 :notice "迁移资料可离线阅读；旧台账、审批、证据仅保存在历史目录，不成为当前项目状态。新工作身份、人员配置与模板需重新确认并绑定本项目聊天。"})))

(defn verify-migrations [root]
  (let [manifests (filterv #(re-matches #"/ai-workspace/migrations/[a-f0-9-]{36}/manifest.json" (:path %)) (files/inventory root))]
    (mapv (fn verify-history [entry]
            (let [record (files/read-json (files/file root (:path entry)))
                  manifest (:source-manifest record)
                  prefix (str "/ai-workspace/migrations/" (:id manifest) "/project")
                  expected (mapv #(update % :path (fn archived-path [path] (str prefix (passive-path path)))) (:files manifest))]
              (when-not (and (= "defold.ai-migration-history" (:format record)) (= 1 (:version record))
                             (= (:checksum manifest) (checksum manifest)) (= expected (:files record)))
                (throw (ex-info "迁移历史清单校验失败" {:path (:path entry)})))
              {:id (:id manifest)
               :issues (coll/into-> expected []
                         (keep #(when-not (= (:sha256 %) (files/hash-file (files/file root (:path %)))) (:path %))))})) manifests)))
