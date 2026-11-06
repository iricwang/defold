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

(ns editor.ai-overview
  "Read-only project overview. Execution and approvals remain in their cards."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.ai-delivery :as delivery]
            [editor.ai-project-work :as work]
            [editor.ai-results :as results]
            [editor.ai-roles :as roles]
            [editor.craft-project :as craft]
            [util.coll :as coll])
  (:import [java.io File]
           [java.nio.file Files]))

(set! *warn-on-reflection* true)

(defn latest-jobs [jobs]
  (vec (coll/vals
         (reduce (fn latest-attempt [all job]
                   (assoc all [(or (:canvas-id job) (:canvas-path job)) (:action-id job)] job))
                 (array-map) jobs))))

(defn- owner-staff [documents path card-id]
  (let [doc (coll/first-where #(= path (:path %)) documents)
        nodes (into {} (mapv (juxt :id identity) (:nodes doc)))
        incoming (group-by :to (:edges doc))]
    (loop [pending [card-id] visited #{}]
      (when-let [id (peek pending)]
        (let [node (get nodes id) remaining (pop pending)]
          (cond
            (contains? visited id) (recur remaining visited)
            (:staff-id node) (:staff-id node)
            :else (recur (into (cond-> remaining (:source-action node) (conj (:source-action node)))
                               (mapv :from (get incoming id))) (conj visited id))))))))

(defn summarize [data documents staff]
  (let [cards (coll/into-> documents []
                (mapcat (fn document-cards [doc]
                          (mapv #(assoc % :path (:path doc) :document-id (:id doc) :document-title (:title doc)) (:nodes doc)))))
        card-at (fn find-card [path id] (coll/first-where #(and (= path (:path %)) (= id (:id %))) cards))
        jobs (latest-jobs (:jobs data))
        tasks (mapv (fn task-row [task]
                      (let [card (card-at (:source-path task) (:id task))
                            action-jobs (filterv #(and (= (:id task) (:task-id %))
                                                       (not= "result" (:kind (card-at (:canvas-path %) (:action-id %))))
                                                       (not= "confirmation" (:kind %))) jobs)
                            action-ids (coll/into-> action-jobs (set (:action-ids task)) (map :action-id))
                            accepted (delivery/accepted data (:id task))]
                        (assoc task :stage (or (:stage card) "未归类工作")
                               :stage-key [(:source-path task) (or (:stage card) "未归类工作")]
                               :plan-title (:document-title card)
                               :accepted (boolean accepted)
                               :status (if accepted "已验收" (let [status (work/task-status data (:id task))] (if (= "已验收" status) "待验收" status)))
                               :done (count (filterv #(= "completed" (:status %)) action-jobs))
                               :total (count action-ids)))) (:tasks data))
        active-tasks (filterv #(not= "已取消" (:status %)) tasks)
        accepted-ids (coll/into-> tasks #{} (filter :accepted)
                       (mapcat #(-> (delivery/accepted data (:id %)) :artifact-ids)))
        artifacts (mapv (fn artifact-row [artifact]
                          (let [receipt (coll/first-where #(= (:id artifact) (:artifact-id %)) (:deliveries data))
                                visual (last (filterv #(= "visual" (:name %)) (:checks receipt)))
                                proof (delivery/artifact data (:evidence-id visual))
                                preview (coll/first-where #(and (re-find #"(?i)\.(png|jpe?g)$" (:name % ""))
                                                                (not (re-find #"(?i)diff|baseline" (:source-path % "")))) (:files proof))]
                            (assoc artifact :staff-id (or (:staff-id (coll/first-where #(= (:task-id artifact) (:id %)) tasks))
                                                          (owner-staff documents (:source-path artifact) (:source-card-id artifact)))
                                   :content-type (cond (get-in artifact [:entrypoints :gui]) "GUI" (= "proposal" (:kind artifact)) "文档" :else "素材包")
                                   :preview-path (:path preview) :status (cond
                                                                           (contains? accepted-ids (:id artifact)) "已验收"
                                                                           (coll/any? #(= (:id artifact) (:artifact-id %)) (:deliveries data)) "已入库"
                                                                           (delivery/approved? data (:id artifact)) "已批准"
                                                                           :else "待审核"))))
                        (filterv #(contains? #{"proposal" "asset-bundle"} (:kind %)) (:artifacts data)))
        reviews (into
                  (coll/into-> cards []
                    (keep (fn card-review [card]
                            (cond
                              (and (= "task" (:kind card)) (= "review" (:status card)) (not (string/blank? (:prompt card))))
                              {:id (:id card) :title (:title card) :label "任务分配待确认" :path (:path card) :card-id (:id card) :staff-id (:staff-id card)}
                              (and (= "result" (:kind card)) (coll/not-empty (:candidates card))
                                   (not= (:confirmed-revision card) (results/revision card)))
                              {:id (:id card) :title (:title card) :label "方案待选择" :path (:path card) :card-id (:id card) :revision (results/revision card)}))))
                  (concat
                    (coll/into-> cards []
                      (filter #(= "pm" (:kind %)))
                      (mapcat (fn planning-reviews [card]
                                (mapv #(hash-map :id (:id %) :title (get-in % [:after :title]) :label "重规划变更待确认"
                                                 :path (:path card) :card-id (:id card))
                                      (filterv #(= "review" (:status %)) (:plan-changes card))))))
                    (mapv #(hash-map :id (:id %) :title (:title %) :label "成果版本待批准" :path (:source-path %) :card-id (:source-card-id %) :review-artifact (:id %) :revision (:revision %) :staff-id (:staff-id %))
                          (filterv #(= "待审核" (:status %)) artifacts))
                    (mapv #(assoc (select-keys % [:id :title :task-id :staff-id]) :label "返修待确认")
                          (filterv #(= "review" (:status %)) (:rework data)))
                    (mapv #(hash-map :id (:id %) :title (:title %) :label "任务待验收" :path (:source-path %) :card-id (:id %))
                          (filterv #(= "待验收" (:status %)) tasks))))
        running (mapv (fn running-job [job]
                        (assoc job :task (coll/first-where #(= (:task-id job) (:id %)) tasks)
                               :card (card-at (:canvas-path job) (:action-id job))))
                      (filterv #(contains? #{"running" "unknown"} (:status %)) jobs))
        issues (mapv (fn issue-context [job]
                       (assoc job :dependents (filterv #(contains? (set (:dependencies %)) (:task-id job)) tasks)))
                     (filterv #(contains? #{"blocked" "failed" "interrupted" "unknown" "paused"} (:status %)) jobs))
        stages (mapv (fn stage-row [[key rows]]
                       {:key key :title (:stage (first rows)) :plan-title (:plan-title (first rows))
                        :path (:source-path (first rows)) :tasks (vec rows)
                        :dependencies (filterv #(contains? (set (coll/into-> rows [] (mapcat :dependencies))) (:id %)) tasks)
                        :status (cond (coll/every? :accepted rows) "已完成"
                                      (coll/any? #(= "工作中" (:status %)) rows) "执行中"
                                      (coll/any? #(contains? #{"需处理" "待核对" "等待前置成果"} (:status %)) rows) "受阻"
                                      (coll/any? #(= "待验收" (:status %)) rows) "待验收" :else "待执行")
                        :done (count (filterv :accepted rows)) :total (count rows)})
                     (sort-by (comp pr-str key) (group-by :stage-key active-tasks)))]
    {:tasks tasks :stages stages :jobs jobs :running running
     :reviews (coll/into-> reviews []
                (keep (fn scoped-review [review]
                        (let [task (coll/first-where #(or (= (:id %) (:task-id review)) (= (:id %) (:id review)) (and (:path review) (= (:canvas-path %) (:path review)))) tasks)]
                          (when-not (and (:accepted task) (= "方案待选择" (:label review)))
                            (assoc review :task-id (:id task) :staff-id (or (:staff-id review) (:staff-id task) (owner-staff documents (:path review) (:card-id review)))))))))
     :issues issues
     :artifacts artifacts :accepted (count (filterv :accepted active-tasks)) :total (count active-tasks)
     :unplanned (filterv #(nil? (:task-id %)) jobs)
     :staff (mapv (fn staff-row [person]
                    (assoc person :jobs (filterv #(= (:id person) (:staff-id %)) jobs))) staff)
     :activity (mapv (fn activity-context [event]
                       (let [artifact (coll/first-where #(= (or (:artifact-id event) (:subject-id event)) (:id %)) artifacts)
                             task (coll/first-where #(= (:subject-id event) (:id %)) tasks)]
                         (assoc event :title (or (:title task) (:title artifact) (:notes event))
                                :staff-id (or (:staff-id task) (:staff-id artifact)))))
                     (take 8 (sort-by #(or (:created-at %) "") coll/descending-order (into (:approvals data) (:deliveries data)))))}))

(defn load-snapshot [root cache]
  (let [library (craft/resource-file root "/ai-workspace/library")
        files (if-not (.isDirectory library) []
                      (with-open [paths (Files/walk (.toPath library) 16 (make-array java.nio.file.FileVisitOption 0))]
                        (let [iterator (.iterator paths)]
                          (loop [files [] visited 0]
                            (if-not (.hasNext iterator)
                              files
                              (do
                                (when (>= visited 10000)
                                  (throw (ex-info "项目资料超过总览扫描上限，未更新统计" {})))
                                (let [path (.next iterator)]
                                  (recur (cond-> files (string/ends-with? (str path) ".aicanvas") (conj (.toFile ^java.nio.file.Path path)))
                                         (inc visited)))))))))
        paths (into [work/path roles/document-path roles/config-path]
                    (mapv #(str "/" (string/replace (str (.relativize (.toPath (.getCanonicalFile (io/file root))) (.toPath ^File %))) "\\" "/")) files))
        errors (atom [])
        records (into {}
                      (mapv (fn read-record [path]
                              [path (try
                                      (let [file (craft/resource-file root path)
                                            stamp [(.lastModified file) (.length file)]
                                            saved (get @cache path)]
                                        (if (= stamp (:stamp saved))
                                          (:data saved)
                                          (let [data (when (.isFile file)
                                                       (when (> (.length file) (* 32 1024 1024))
                                                         (throw (ex-info "文件超过总览读取限制" {:path path})))
                                                       (json/read-str (slurp file :encoding "UTF-8") :key-fn keyword))]
                                            (swap! cache assoc path {:stamp stamp :data data})
                                            data)))
                                      (catch Exception e
                                        (if (= path work/path) (throw e)
                                            (do (swap! errors conj (str (.getMessage e) " · " path)) nil))))]) paths))
        documents (coll/into-> records []
                    (keep (fn canvas-record [[path data]]
                            (when (= "codexcamp.ai-canvas" (:format data)) (assoc data :path path)))))
        catalog (or (:roles (get records roles/config-path)) (:roles (roles/default-config)))
        staff (mapv #(assoc % :role-definition (coll/first-where (fn role-definition [role] (= (:role-type %) (:id role))) catalog))
                    (:nodes (get records roles/document-path)))
        data (or (get records work/path) {:tasks [] :jobs [] :artifacts [] :approvals [] :deliveries []})]
    (swap! cache select-keys paths)
    (assoc (update (summarize data documents staff) :artifacts
                   (fn artifact-details [artifacts]
                     (mapv (fn gui-count [artifact]
                             (let [entrypoint (get-in artifact [:entrypoints :gui])
                                   entry (when entrypoint (coll/first-where #(= entrypoint (:source-path %)) (:files artifact)))
                                   file (when entry (craft/resource-file root (:path entry)))]
                               (if-not (and file (.isFile ^File file) (< (.length ^File file) (* 8 1024 1024)))
                                 artifact
                                 (let [matcher (re-matcher #"(?m)^\s*nodes\s*\{" (slurp file :encoding "UTF-8"))]
                                   (assoc artifact :node-count (loop [total 0] (if (.find matcher) (recur (inc total)) total))))))) artifacts))) :errors @errors
           :project-title (let [file (io/file root "game.project")]
                            (when (.isFile file)
                              (second (re-find #"(?m)^title\s*=\s*(.+)$" (slurp file :encoding "UTF-8"))))))))
