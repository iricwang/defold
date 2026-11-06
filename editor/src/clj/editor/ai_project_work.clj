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

(ns editor.ai-project-work
  "Project-owned staff queues and execution facts, shared across Canvas views."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [editor.craft-project :as craft-project]
            [util.coll :as coll])
  (:import [java.nio.channels FileChannel FileLock]
           [java.nio.file Files StandardCopyOption StandardOpenOption]
           [java.time Instant]
           [java.util UUID]))

(set! *warn-on-reflection* true)

(def path "/ai-workspace/project/work.json")
(defonce revision (atom {}))
(defonce ^:private monitor (Object.))
(defonce ^:private initialized (atom #{}))

(defn project-key [root] (.getCanonicalPath (io/file root)))

(defn read-work [root]
  (let [file (craft-project/resource-file root path)]
    (if-not (.exists file)
      {:version 1 :jobs [] :tasks [] :approvals [] :deliveries [] :artifacts []}
      (do
        (when (> (.length file) (* 32 1024 1024)) (throw (ex-info "项目工作台账超过 32 MB，请归档历史后继续" {})))
        (let [data (json/read-str (slurp file) :key-fn keyword)]
          (when-not (and (= 1 (:version data)) (coll/every? #(vector? (get data %)) [:jobs :tasks :approvals :deliveries]))
            (throw (ex-info "项目工作台账格式无效，原文件已保留" {})))
          (when-not (vector? (:artifacts data [])) (throw (ex-info "资产台账格式无效" {})))
          (update data :artifacts #(or % [])))))))

(defn transact! [root f]
  (locking monitor
    (let [file (craft-project/resource-file root path)
          lock-file (io/file (.getParentFile file) ".work.lock")]
      (io/make-parents file) (io/make-parents lock-file)
      (when (Files/isSymbolicLink (.toPath lock-file)) (throw (ex-info "任务台账锁文件不能是符号链接" {})))
      (with-open [channel (FileChannel/open (.toPath lock-file) (into-array StandardOpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))
                  ^FileLock _lock (or (.tryLock channel) (throw (ex-info "另一个编辑器正在更新项目任务，请稍后重试" {})))]
        (let [before (read-work root) after (f before)]
          (when-not (= before after)
            (let [temp (Files/createTempFile (.toPath (.getParentFile file)) "work-" ".tmp" (make-array java.nio.file.attribute.FileAttribute 0))]
              (try
                (spit (.toFile temp) (json/write-str after))
                (Files/move temp (.toPath file) (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
                (finally (Files/deleteIfExists temp))))
            (swap! revision update (project-key root) (fnil inc 0)))
          after)))))

(defn initialize! [root]
  (locking monitor
    (let [key (project-key root)]
      (when-not (contains? @initialized key)
        (transact! root #(update % :jobs
                                 (fn recover-jobs [jobs]
                                   (mapv (fn recover-job [job]
                                           (update job :status {"queued" "paused" "running" "unknown"} (:status job))) jobs))))
        (swap! initialized conj key)))))

(defn enqueue! [root entries]
  (let [now (str (Instant/now))
        jobs (mapv #(merge % {:id (str (UUID/randomUUID)) :status "queued" :created-at now}) entries)]
    (transact! root (fn enqueue-current [data]
                      (doseq [entry entries :let [task (coll/first-where #(= (:task-id entry) (:id %)) (:tasks data))]]
                        (when (or (:planning-hold task) (contains? #{"cancelled" "superseded" "pending-change"} (:scope-status task)))
                          (throw (ex-info "任务正在重规划或已退出当前范围，不能加入执行队列" {}))))
                      (update data :jobs
                              (fn insert-jobs [current]
                                (reduce (fn insert-job [current job]
                                          (let [index (.indexOf ^java.util.List (mapv :id current) (:before-job-id job))
                                                index (if (neg? index) (count current) index)]
                                            (into (conj (subvec current 0 index) (dissoc job :before-job-id)) (subvec current index)))) current jobs)))))
    jobs))

(defn update-jobs! [root ids fields]
  (transact! root #(update % :jobs (fn update-jobs [jobs] (mapv (fn update-job [job] (if (contains? ids (:id job)) (merge job fields) job)) jobs)))))

(defn admission-state! [root id error]
  (transact! root #(update % :jobs
                           (fn admission-jobs [jobs]
                             (mapv (fn admission-job [job]
                                     (cond-> job
                                       (and (= id (:id job)) (contains? #{"queued" "blocked"} (:status job)))
                                       (assoc :status (if error "blocked" "queued") :block-reason error))) jobs)))))

(defn control! [root ids op]
  (transact! root #(update % :jobs
                           (fn control-jobs [jobs]
                             (mapv (fn control-job [job]
                                     (if-not (contains? ids (:id job)) job
                                             (case op
                                               :resume (cond-> job (= "paused" (:status job)) (assoc :status "queued"))
                                               :pause (cond-> job (contains? #{"queued" "blocked"} (:status job)) (assoc :status "paused"))
                                               :cancel (cond-> job (contains? #{"queued" "blocked" "paused"} (:status job)) (assoc :status "cancelled"))))) jobs)))))

(defn claim! [root id]
  (let [claim-token (str (UUID/randomUUID))
        data (transact! root
                        (fn claim-job [data]
                          (let [job (coll/first-where #(= id (:id %)) (:jobs data))
                                task (coll/first-where #(= (:task-id job) (:id %)) (:tasks data))
                                staff (:staff-id job)
                                active (coll/first-where #(and (= staff (:staff-id %)) (contains? #{"running" "unknown"} (:status %))) (:jobs data))
                                held (coll/into-> (:tasks data) #{}
                                       (filter #(or (:planning-hold %) (contains? #{"cancelled" "superseded" "pending-change"} (:scope-status %)))) (map :id))
                                first-job (coll/first-where #(and (= staff (:staff-id %)) (= "queued" (:status %)) (not (contains? held (:task-id %)))) (:jobs data))]
                            (if-not (and job (= id (:id first-job)) (nil? active)
                                         (not (:planning-hold task)) (not (contains? #{"cancelled" "superseded" "pending-change"} (:scope-status task)))) data
                                    (update data :jobs #(mapv (fn start-job [candidate]
                                                                (cond-> candidate (= id (:id candidate)) (assoc :status "running" :claim-token claim-token :started-at (str (Instant/now))))) %))))))]
    (= claim-token (:claim-token (coll/first-where #(= id (:id %)) (:jobs data))))))

(defn jobs-for [data staff-id]
  (filterv #(= staff-id (:staff-id %)) (:jobs data)))

(defn resolve-canvas! [root canvas-id hint]
  (let [id-at (fn canvas-at [file]
                (when (and (.isFile ^java.io.File file) (<= (.length ^java.io.File file) (* 16 1024 1024)))
                  (try (let [doc (json/read-str (slurp file) :key-fn keyword)]
                         (when (= "codexcamp.ai-canvas" (:format doc)) (:id doc)))
                       (catch Exception _ nil))))
        hinted (when hint (craft-project/resource-file root hint))]
    (if (and hinted (.isFile hinted) (or (nil? canvas-id) (= canvas-id (id-at hinted))))
      hint
      (let [library (craft-project/resource-file root "/ai-workspace/library")
            matches (when (.isDirectory library)
                      (with-open [stream (Files/walk (.toPath library) 16 (make-array java.nio.file.FileVisitOption 0))]
                        (let [iterator (.iterator stream)]
                          (loop [matches [] count 0]
                            (if-not (.hasNext iterator) matches
                                    (let [candidate ^java.nio.file.Path (.next iterator)]
                                      (when (> count 10000) (throw (ex-info "AI 工程文件过多，请先整理目录后再定位移动的 Canvas" {})))
                                      (recur (cond-> matches
                                               (and canvas-id (.endsWith (str candidate) ".aicanvas")
                                                    (not (Files/isSymbolicLink candidate)) (= canvas-id (id-at (.toFile candidate))))
                                               (conj (str "/" (.replace (str (.relativize (.toPath (.getCanonicalFile (io/file root))) candidate)) "\\" "/"))))
                                             (inc count))))))))]
        (when-not (= 1 (count matches))
          (throw (ex-info (if (coll/not-empty matches) "发现重复 Canvas 身份，请处理复制冲突后再打开" "工作 Canvas 已丢失，请恢复后再打开") {:canvas-id canvas-id :path hint})))
        (let [path (first matches)]
          (transact! root #(update % :tasks (fn relocate-tasks [tasks] (mapv (fn relocate-task [task] (cond-> task (= canvas-id (:canvas-id task)) (assoc :canvas-path path))) tasks))))
          path)))))

(defn register-task! [root task]
  (transact! root #(update % :tasks
                           (fn register-task [tasks]
                             (if (coll/any? (fn same-task [old] (= (:id task) (:id old))) tasks)
                               (mapv (fn update-task [old] (if (= (:id task) (:id old)) (merge old task) old)) tasks)
                               (conj tasks task))))))

(defn prepare-queue! [root state person]
  (let [model (:canvas state)
        owner (coll/first-where #(= person (:id %)) (:nodes model))
        data (read-work root)
        ids (get-in state [:sessions person :queue :remaining])
        missing (filterv (fn needs-job [id]
                           (let [job-id (get-in state [:sessions id :project-job-id])
                                 job (coll/first-where #(= job-id (:id %)) (:jobs data))]
                             (not (contains? #{"queued" "blocked" "paused" "running" "unknown"} (:status job))))) ids)
        entries (mapv (fn job-entry [id]
                        (let [card (coll/first-where #(= id (:id %)) (:nodes model))
                              staff (coll/first-where #(= (:staff-id owner) (:id %)) (:staff state))]
                          {:staff-id (:staff-id owner) :staff-snapshot staff
                           :person-id person :action-id id :title (:title card)
                           :canvas-path (get-in state [:context :path]) :canvas-id (get-in state [:context :canvas-id]) :task-id (get-in state [:task-origin :task])
                           :before-job-id (get-in state [:sessions (coll/first-where #(not (contains? (set missing) %))
                                                                                     (subvec ids (inc (.indexOf ^java.util.List ids id)))) :project-job-id])
                           :task-spec-revision (get-in state [:task-origin :revision])
                           :spec-snapshot (dissoc card :x :y)})) missing)
        jobs (when (coll/not-empty entries) (enqueue! root entries))
        state (reduce (fn bind-job [state job] (assoc-in state [:sessions (:action-id job) :project-job-id] (:id job))) state jobs)
        remaining (coll/into-> ids #{} (keep #(get-in state [:sessions % :project-job-id])))]
    (when (coll/not-empty remaining) (control! root remaining :resume))
    state))

(defn queue-job-ids [state person]
  (coll/into-> (get-in state [:sessions person :queue :actions]) #{}
    (keep #(get-in state [:sessions % :project-job-id]))))

(defn bind-jobs [state prepared]
  (reduce-kv (fn bind-job [state id session]
               (if-let [job-id (:project-job-id session)] (assoc-in state [:sessions id :project-job-id] job-id) state)) state (:sessions prepared)))

(defn next-ready-action [data state person]
  (let [job-actions (into {} (mapv (fn job-action [id] [(get-in state [:sessions id :project-job-id]) id])
                                   (get-in state [:sessions person :queue :remaining])))]
    (when-let [job (coll/first-where #(and (= "queued" (:status %)) (contains? job-actions (:id %))) (:jobs data))]
      (get job-actions (:id job)))))

(defn retry-pending! [root id request-id]
  (transact! root #(update % :jobs
                           (fn retry-jobs [jobs]
                             (mapv (fn retry-job [job]
                                     (cond-> job
                                       (and (= id (:id job)) (= "unknown" (:status job)) (= request-id (:request-id job)))
                                       (assoc :status "queued"))) jobs)))))

(defn record-execution! [root state id]
  (when-let [job-id (get-in state [:sessions id :project-job-id])]
    (let [execution (get-in state [:sessions id :execution])
          status (:status execution)]
      (when (contains? #{"running" "waiting" "completed" "failed" "interrupted" "unknown"} status)
        (let [fields (cond-> {:status (if (= "waiting" status) "running" status)}
                       (:turn-id execution) (assoc :turn-id (:turn-id execution))
                       (get-in state [:sessions id :thread-id]) (assoc :thread-id (get-in state [:sessions id :thread-id]))
                       (:error execution) (assoc :error (:error execution)))]
          (transact! root #(update % :jobs
                                   (fn record-jobs [jobs]
                                     (let [current (coll/first-where (fn current-job [job] (= job-id (:id job))) jobs)]
                                       (mapv (fn record-job [job]
                                               (cond
                                                 (= job-id (:id job))
                                                 (if (and (contains? #{"completed" "failed" "interrupted" "cancelled"} (:status job))
                                                          (contains? #{"running" "unknown"} (:status fields))) job (merge job fields))
                                                 (and (contains? #{"failed" "interrupted"} status) (= "queued" (:status job))
                                                      (= (or (:canvas-id current) (:canvas-path current)) (or (:canvas-id job) (:canvas-path job)))
                                                      (= (:person-id current) (:person-id job)))
                                                 (assoc job :status "paused")
                                                 :else job)) jobs))))))))))

(defn observe-event! [root event]
  (when (= "turn/completed" (:method event))
    (let [thread (get-in event [:params :threadId])
          turn (get-in event [:params :turn])
          status (:status turn)]
      (when (contains? #{"completed" "failed" "interrupted"} status)
        (transact! root #(update % :jobs
                                 (fn finish-jobs [jobs]
                                   (mapv (fn finish-job [job]
                                           (cond-> job
                                             (and (= thread (:thread-id job)) (= (:id turn) (:turn-id job)))
                                             (assoc :status status))) jobs))))))))

(defn reconcile-native! [root fetch!]
  (doseq [job (filterv #(and (contains? #{"running" "unknown"} (:status %)) (:thread-id %) (:turn-id %)) (:jobs (read-work root)))]
    (let [snapshot (fetch! (:thread-id job))
          turn (coll/first-where #(= (:turn-id job) (:id %)) (get-in snapshot [:thread :turns]))]
      (when turn (observe-event! root {:method "turn/completed" :params {:threadId (:thread-id job) :turn turn}})))))

(defn task-status [data id]
  (let [jobs (vec (coll/vals (reduce (fn latest-action [latest job] (assoc latest (:action-id job) job)) {}
                                     (filterv #(= id (:task-id %)) (:jobs data)))))
        spec (coll/first-where #(= id (:id %)) (:tasks data))
        decision (last (filterv #(and (= "task-acceptance" (:kind %)) (= id (:subject-id %))) (:approvals data)))]
    (cond
      (contains? #{"cancelled" "superseded"} (:scope-status spec)) "已取消"
      (:planning-hold spec) "变更待处理"
      (and (= "accepted" (:decision decision)) (= (:spec-revision spec) (:revision decision))
           (= (set (mapv :id jobs)) (set (:job-ids decision)))) "已验收"
      (coll/any? #(= "unknown" (:status %)) jobs) "待核对"
      (coll/any? #(= "running" (:status %)) jobs) "工作中"
      (coll/any? #(= "queued" (:status %)) jobs) "待执行"
      (coll/any? #(= "blocked" (:status %)) jobs) "等待前置成果"
      (coll/any? #(= "paused" (:status %)) jobs) "已暂停"
      (coll/any? #(contains? #{"failed" "interrupted"} (:status %)) jobs) "需处理"
      (and (coll/not-empty jobs) (coll/every? #(= "cancelled" (:status %)) jobs)) "已取消"
      (and (= "rejected" (:decision decision)) (= (:spec-revision spec) (:revision decision))
           (= (set (mapv :id jobs)) (set (:job-ids decision)))) "已退回"
      (coll/not-empty jobs) "待验收"
      :else "待执行")))
