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

(ns editor.ai-publication
  "Local candidate projects and auditable resource publication checks."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.craft-project :as craft]
            [util.coll :as coll]
            [util.digest :as digest])
  (:import [java.io File]
           [java.nio.file Files StandardCopyOption]
           [java.time Instant]
           [java.util UUID]))

(set! *warn-on-reflection* true)

(defn sha256 [^File file]
  (when (.isFile file)
    (with-open [input (io/input-stream file)] (digest/stream->sha256-hex input))))

(defn inventory [root]
  (let [root (.getCanonicalFile (io/file root)) boundary (.toPath root)]
    (loop [pending [root] records []]
      (if-not (coll/not-empty pending)
        (vec (sort-by :path records))
        (let [^File file (peek pending) pending (pop pending)
              relative (string/replace (str (.relativize boundary (.toPath file))) "\\" "/")
              name (.getName file)
              ignored (or (contains? #{"build" "ai-workspace" "codex-attachments"} relative)
                          (and (string/starts-with? name ".")
                               (not (contains? #{".internal" ".defoldignore"} relative))))]
          (cond
            ignored (recur pending records)
            (or (Files/isSymbolicLink (.toPath file)) (not= (.toPath file) (.toPath (.getCanonicalFile file))))
            (throw (ex-info "候选工程不跟随符号链接或目录联接，请先使用项目内普通文件" {:path relative}))
            (= ".internal" relative)
            (let [libraries (io/file file "lib")]
              (recur (cond-> pending (.exists libraries) (conj libraries)) records))
            (.isDirectory file)
            (let [children (.listFiles file)]
              (when-not children (throw (ex-info "无法读取候选工程目录" {:path relative})))
              (recur (into pending children) records))
            (.isFile file)
            (recur pending (conj records {:path (str "/" relative) :sha256 (sha256 file) :size (.length file)}))
            :else (throw (ex-info "候选工程文件在扫描时被删除" {:path relative}))))))))

(defn baseline-hash [records]
  (digest/string->sha256-hex (pr-str (mapv (juxt :path :sha256) (sort-by :path records)))))

(defn environment-files [records]
  (mapv #(select-keys % [:path :sha256])
        (filterv #(or (contains? #{"/game.project" "/.defoldignore"} (:path %))
                      (string/starts-with? (:path %) "/.internal/lib/")) records)))

(defn assert-baseline! [root expected]
  (when-not (= (baseline-hash expected) (baseline-hash (inventory root)))
    (throw (ex-info "工程发生人工修改或依赖变化，入库已停止；请重新生成方案" {}))))

(defn final-baseline [baseline prepared]
  (vec (sort-by :path (into [] (coll/vals (reduce (fn replace-baseline [files entry]
                                                    (assoc files (:target entry) {:path (:target entry) :sha256 (:hash entry) :size (alength ^bytes (:bytes entry))}))
                                                  (into {} (mapv (juxt :path identity) baseline)) prepared))))))

(defn candidate! [root ^File directory baseline prepared output validate!]
  (let [candidate (io/file directory "candidates" (str (UUID/randomUUID)) "project")]
    (io/make-parents (io/file candidate "game.project"))
    (doseq [{:keys [path] expected-hash :sha256} baseline]
      (let [source (io/file (.getCanonicalFile (io/file root)) (subs path 1))
            target (io/file candidate (subs path 1))]
        (when-not (= (.toPath source) (.toPath (.getCanonicalFile source)))
          (throw (ex-info "候选工程源路径已变化" {:path path})))
        (io/make-parents target)
        (Files/copy (.toPath source) (.toPath target) ^"[Ljava.nio.file.CopyOption;" (make-array StandardCopyOption 0))
        (when-not (= expected-hash (sha256 target))
          (throw (ex-info "复制候选工程期间源文件发生变化" {:path path})))))
    (doseq [entry prepared]
      (let [file (craft/resource-file candidate (:target entry))]
        (io/make-parents file)
        (Files/write (.toPath file) ^bytes (:bytes entry) ^"[Ljava.nio.file.OpenOption;" (make-array java.nio.file.OpenOption 0))))
    (let [expected (final-baseline baseline prepared)
          validation (try
                       (validate! (assoc output :project-root candidate :candidate true
                                         :build-directory (io/file (.getParentFile candidate) "build")))
                       (catch Exception e
                         (try (assert-baseline! root baseline)
                              (catch Exception conflict
                                (throw (ex-info (str (.getMessage e) "；" (.getMessage conflict)) (ex-data conflict) e))))
                         (throw e)))]
      (when-not (= "passed" (:status validation))
        (throw (ex-info "候选工程编译未通过，正式工程未写入" {:validation validation})))
      (assert-baseline! candidate expected)
      (assert-baseline! root baseline)
      (assoc validation :candidate true :validator-version "gui-static-v1"
             :environment-files (environment-files expected)
             :baseline-hash (baseline-hash baseline) :final-baseline-hash (baseline-hash expected)))))

(defn changes [root prepared]
  (mapv (fn describe-change [entry]
          (let [file (craft/resource-file root (:target entry))
                text (when (and (not= "image" (:kind entry)) (<= (alength ^bytes (:bytes entry)) (* 1024 1024)))
                       (String. ^bytes (:bytes entry) "UTF-8"))
                before (when (and text (.isFile file) (<= (.length file) (* 1024 1024))) (slurp file :encoding "UTF-8"))
                a (string/split-lines (or before "")) b (string/split-lines (or text ""))
                prefix (loop [n 0] (if (and (< n (count a)) (< n (count b)) (= (nth a n) (nth b n))) (recur (inc n)) n))
                suffix (loop [n 0] (if (and (< n (- (count a) prefix)) (< n (- (count b) prefix))
                                            (= (nth a (- (count a) n 1)) (nth b (- (count b) n 1)))) (recur (inc n)) n))]
            (cond-> {:source (:source entry) :path (:target entry) :kind (:kind entry) :operation (:operation entry)
                     :before-hash (:targetHash entry) :after-hash (:hash entry)
                     :before-size (when (.isFile file) (.length file)) :after-size (alength ^bytes (:bytes entry))}
              text (assoc :diff (into (mapv #(str "- " %) (subvec a prefix (min (- (count a) suffix) (+ prefix 40))))
                                      (mapv #(str "+ " %) (subvec b prefix (min (- (count b) suffix) (+ prefix 40)))))
                          :diff-truncated (or (> (- (count a) prefix suffix) 40) (> (- (count b) prefix suffix) 40)))))) prepared))

(defn report-text [report]
  (str "# 资源发布检查 · " (:module report)
       "\n\n" (get {"checking" "检查中" "review-ready" "静态检查通过，等待人工确认；尚未编译或写入工程" "failed" "未通过，不能交付" "imported" "已写入，等待运行与人工验收"} (:status report) "待检查")
       "\n\n计划：`" (:plan-id report) "`  · 检查器：`gui-static-v1`"
       "\n\n检查时间：" (:created-at report) "。本报告记录此次检查；正式执行会重新核对当前工程。"
       "\n\n| 阶段 | 结果 |\n| --- | --- |\n"
       (coll/join-to-string "\n" (mapv #(str "| " (:title %) " | " (get {"passed" "通过" "running" "进行中" "failed" "失败"} (:status %) (:status %)) " |") (:checks report)))
       (when-let [error (:error report)] (str "\n\n问题：" error))
       (when-let [details (:details report)] (str "\n\n```text\n" details "\n```\n"))
       "\n\n## 文件与引用变化\n"
       (coll/join-to-string "\n" (mapv (fn file-change [entry]
                                         (str "\n### `" (:path entry) "`\n"
                                              "\n" (get {"create" "新建" "replace" "替换" "reuse" "复用"} (:operation entry) (:operation entry))
                                              " · " (or (:before-size entry) 0) " → " (:after-size entry) " 字节"
                                              (when (= "reference" (:kind entry)) " · 工程引用更新")
                                              "\n\n原摘要：`" (or (:before-hash entry) "无") "`\n\n新摘要：`" (:after-hash entry) "`\n"
                                              (when (coll/not-empty (:diff entry)) (str "\n```diff\n" (coll/join-to-string "\n" (:diff entry)) "\n```\n"))
                                              (when (:diff-truncated entry) "\n长差异仅展示变化区段的前 40 行，请对照完整文件。\n"))) (:changes report)))
       "\n\n## 验证边界\n\n候选编译成功只证明资源可构建，不代表运行、视觉或人工验收通过。正式工程基线：`"
       (get-in report [:validation :final-baseline-hash] "尚未产生") "`。\n"))

(defn save-report! [root report]
  (let [prefix (str "/ai-workspace/deliveries/reports/" (:plan-id report) "/")
        path (str prefix (:attempt-id report) ".md")
        entries [[path (report-text report)] [(str prefix (:attempt-id report) ".json") (json/write-str report)]
                 [(str prefix "latest.json") (json/write-str {:path path :status (:status report) :plan-fingerprint (:plan-fingerprint report)})]]]
    (doseq [[path text] entries]
      (let [file (craft/resource-file root path)]
        (io/make-parents file)
        (let [temp (Files/createTempFile (.toPath (.getParentFile file)) "report-" ".tmp" (make-array java.nio.file.attribute.FileAttribute 0))]
          (try
            (spit (.toFile temp) text :encoding "UTF-8")
            (Files/move temp (.toPath file) (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
            (finally (Files/deleteIfExists temp))))))
    path))

(defn new-report [plan]
  {:version 1 :validator-version "gui-static-v1" :plan-id (:id plan) :module (:module plan)
   :plan-fingerprint (digest/string->sha256-hex (pr-str plan))
   :attempt-id (str (UUID/randomUUID)) :created-at (str (Instant/now)) :status "checking" :checks []})

(defn latest-report-path [root plan-id]
  (when-not (and (string? plan-id) (re-matches #"[a-f0-9-]{36}" plan-id))
    (throw (ex-info "入库计划编号无效" {})))
  (let [file (craft/resource-file root (str "/ai-workspace/deliveries/reports/" plan-id "/latest.json"))]
    (when (.isFile file) (:path (json/read-str (slurp file :encoding "UTF-8") :key-fn keyword)))))

(defn matching-report-path [root plan]
  (when-let [path (latest-report-path root (:id plan))]
    (let [file (craft/resource-file root (string/replace path #"\.md$" ".json"))
          report (json/read-str (slurp file :encoding "UTF-8") :key-fn keyword)]
      (when (= (:plan-fingerprint report) (digest/string->sha256-hex (pr-str plan))) path))))
