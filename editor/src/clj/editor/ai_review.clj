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

(ns editor.ai-review
  "Local version drafts, anchored review decisions and human-confirmed rework."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.ai-delivery :as delivery]
            [editor.ai-graph :as graph]
            [editor.ai-pm :as pm]
            [editor.ai-project-work :as work]
            [editor.ai-roles :as roles]
            [editor.craft-project :as craft]
            [util.coll :as coll])
  (:import [java.nio.file Files StandardCopyOption]
           [java.time Instant]
           [java.util UUID]))

(set! *warn-on-reflection* true)

(defn protected-file? [root file]
  (let [path (.toPath (.getCanonicalFile (io/file file)))]
    (coll/any? #(.startsWith path (.toPath (.getCanonicalFile (craft/resource-file root %))))
               ["/ai-workspace/artifacts/files" "/ai-workspace/deliveries/files" "/ai-workspace/candidates/files"])))

(defn versions [data record]
  (vec (sort-by :created-at (filterv #(= (:bundle-id record) (:bundle-id %)) (:artifacts data)))))

(defn draft [data id]
  (coll/first-where #(= id (:id %)) (:review-drafts data)))

(defn- update-draft! [root id expected f]
  (work/transact! root
                  (fn update-draft [data]
                    (let [current (draft data id)]
                      (when-not (and current (= "editing" (:status current)) (= expected (:revision current)))
                        (throw (ex-info "工作副本已变化或已提交，请刷新；未保存内容已保留" {})))
                      (update data :review-drafts
                              #(mapv (fn update-entry [entry]
                                       (if (= id (:id entry)) (-> (f entry) (update :revision inc)) entry)) %))))))

(defn create-draft! [root artifact-id]
  (let [parent (delivery/verify-artifact! root (delivery/artifact (work/read-work root) artifact-id))
        id (str (UUID/randomUUID))
        files (mapv (fn copy-source [index entry]
                      (let [path (str "/ai-workspace/candidates/drafts/" id "/" index "-" (.getName (io/file (:path entry))))
                            file (craft/resource-file root path)]
                        (io/make-parents file)
                        (Files/copy (.toPath (craft/resource-file root (:path entry))) (.toPath file)
                                    ^"[Ljava.nio.file.CopyOption;" (make-array StandardCopyOption 0))
                        (assoc entry :path path :parent-path (:path entry) :base-hash (craft/file-hash root path))))
                    (range (count (:files parent))) (:files parent))
        record {:id id :parent-id artifact-id :revision 1 :status "editing" :text (:text parent)
                :files files :created-at (str (Instant/now))}]
    (work/transact! root #(update % :review-drafts (fnil conj []) record))
    record))

(defn save-text! [root id expected text]
  (when-not (and (string? text) (<= (count text) (* 8 1024 1024)))
    (throw (ex-info "正文超过工作副本大小限制" {})))
  (update-draft! root id expected #(assoc % :text text)))

(defn save-markdown! [root id expected index base text]
  (when-not (and (string? text) (<= (alength (.getBytes ^String text "UTF-8")) (* 8 1024 1024)))
    (throw (ex-info "Markdown 内容超过 8 MiB" {})))
  (update-draft! root id expected
                 (fn save-draft-markdown [record]
                   (let [entry (get (:files record) index)
                         file (when entry (craft/resource-file root (:path entry)))]
                     (when-not (and file (not (protected-file? root file))
                                    (re-find #"(?i)\.(md|markdown)$" (.getName file)))
                       (throw (ex-info "只能保存工作副本中的 Markdown" {})))
                     (when-not (and (= (:base-hash entry) (craft/file-hash root (:path entry)))
                                    (= base (slurp file :encoding "UTF-8")))
                       (throw (ex-info "文件已被外部修改，未保存草稿已保留" {})))
                     (let [temp (Files/createTempFile (.toPath (.getParentFile file)) "review-" ".tmp" (make-array java.nio.file.attribute.FileAttribute 0))]
                       (try
                         (spit (.toFile temp) text :encoding "UTF-8")
                         (Files/move temp (.toPath file) (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
                         (finally (Files/deleteIfExists temp))))
                     (assoc-in record [:files index :base-hash] (craft/file-hash root (:path entry)))))))

(defn replace-file! [root id expected index expected-hash source]
  (when-not (and (.isFile (io/file source)) (<= (.length (io/file source)) (* 256 1024 1024)))
    (throw (ex-info "替换文件不存在或超过 256 MiB" {})))
  (update-draft! root id expected
                 (fn replace-draft-file [record]
                   (let [entry (get (:files record) index)
                         target (when entry (craft/resource-file root (:path entry)))]
                     (when-not (and target (= expected-hash (craft/file-hash root (:path entry))))
                       (throw (ex-info "工作文件已被外部修改，请保留草稿并刷新" {})))
                     (when-not (= (string/lower-case (last (string/split (.getName (io/file source)) #"\.")))
                                  (string/lower-case (last (string/split (.getName target) #"\."))))
                       (throw (ex-info "替换文件的格式必须保持一致" {})))
                     (let [temp (Files/createTempFile (.toPath (.getParentFile target)) "review-" ".tmp" (make-array java.nio.file.attribute.FileAttribute 0))]
                       (try
                         (Files/copy (.toPath (io/file source)) temp ^"[Ljava.nio.file.CopyOption;" (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
                         (Files/move temp (.toPath target) (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
                         (finally (Files/deleteIfExists temp))))
                     (assoc-in record [:files index :base-hash] (craft/file-hash root (:path entry)))))))

(defn publish-draft! [root id expected]
  (let [data (work/transact! root
                             (fn publish-version [data]
                               (let [record (draft data id)
                                     parent (delivery/verify-artifact! root (delivery/artifact data (:parent-id record)))]
                                 (when-not (and (= "editing" (:status record)) (= expected (:revision record)))
                                   (throw (ex-info "工作副本已变化，请刷新后重新提交" {})))
                                 (let [files (mapv (fn draft-file [entry]
                                                     (when-not (= (:base-hash entry) (craft/file-hash root (:path entry)))
                                                       (throw (ex-info "工作副本存在外部修改，请先重新收集文件" {:path (:path entry)})))
                                                     (select-keys entry [:path :name])) (:files record))
                                       paths (into {} (mapv (juxt :source-path :path) (:files record)))
                                       version (delivery/freeze-artifact! root (-> parent
                                                                                   (dissoc :id :revision :manifest-hash :created-at)
                                                                                   (assoc :parent-id (:id parent) :draft-id id :text (:text record) :files files
                                                                                          :origin-status "human-revision")
                                                                                   (update :entrypoints #(into {} (mapv (fn [[k v]] [k (get paths v v)]) %)))))]
                                   ;; Guard against concurrent external edits.
                                   (doseq [entry (:files record)]
                                     (when-not (= (:base-hash entry)
                                                  (str "sha256:" (:sha256 (coll/first-where #(= (:path entry) (:source-path %)) (:files version)))))
                                       (throw (ex-info "提交期间文件发生变化，工作副本已保留" {}))))
                                   (-> data
                                       (update :artifacts (fn add-version [entries]
                                                            (if (delivery/artifact data (:id version)) entries (conj (vec entries) version))))
                                       (update :review-drafts #(mapv (fn submit-draft [entry]
                                                                       (if (= id (:id entry))
                                                                         (-> entry (assoc :status "submitted" :artifact-id (:id version)) (update :revision inc))
                                                                         entry)) %)))))))]
    (delivery/artifact data (:artifact-id (draft data id)))))

(defn collect-file! [root id expected index]
  (update-draft! root id expected
                 (fn collect-edited-file [record]
                   (let [entry (get (:files record) index)
                         hash (when entry (craft/file-hash root (:path entry)))]
                     (when-not hash (throw (ex-info "工作副本文件不存在" {})))
                     (assoc-in record [:files index :base-hash] hash)))))

(defn add-note! [root artifact-id {:keys [text path anchor checklist decision] :as fields}]
  (let [record (delivery/verify-artifact! root (delivery/artifact (work/read-work root) artifact-id))
        file (when (and path (not= "body" path))
               (when-let [entry (coll/first-where #(= path (:path %)) (:files record))]
                 (craft/resource-file root (:path entry))))
        image (and file (re-find #"(?i)\.(png|jpg|jpeg|gif|bmp)$" (.getName file)))
        lines (when (or (= "body" path) (nil? path)
                        (and file (<= (.length file) (* 8 1024 1024)) (re-find #"(?i)\.(md|markdown|txt|json|lua|gui|atlas|csv)$" (.getName file))))
                (count (string/split-lines (or (if file (slurp file :encoding "UTF-8") (:text record)) ""))))]
    (when-not (and (string? text) (<= 1 (count (string/trim text)) 8000)
                   (contains? #{"comment" "changes-requested"} decision)
                   (or (nil? path) (= path "body") (coll/any? #(= path (:path %)) (:files record))))
      (throw (ex-info "请填写意见并选择当前版本内的内容" {})))
    (when (and anchor
               (not (case (:type anchor)
                      "lines" (and lines (integer? (:start anchor)) (integer? (:end anchor))
                                   (<= 1 (:start anchor) (:end anchor) lines))
                      "region" (and image (coll/every? number? (mapv anchor [:x :y :width :height]))
                                    (<= 0 (:x anchor) 1) (<= 0 (:y anchor) 1)
                                    (< 0 (:width anchor)) (< 0 (:height anchor))
                                    (<= (+ (:x anchor) (:width anchor)) 1.00001)
                                    (<= (+ (:y anchor) (:height anchor)) 1.00001))
                      false)))
      (throw (ex-info "批注范围无效" {})))
    (when-not (or (nil? checklist) (and (vector? checklist) (<= (count checklist) 100)
                                        (coll/every? #(or (string? %) (and (map? %) (string? (:text %)) (boolean? (:checked %)))) checklist)))
      (throw (ex-info "检查清单格式无效" {})))
    (let [note (merge (select-keys fields [:text :path :anchor :checklist :decision])
                      {:id (str (UUID/randomUUID)) :artifact-id artifact-id :revision (:revision record)
                       :task-id (:task-id record) :actor "local-human" :created-at (str (Instant/now))})]
      (work/transact! root #(update % :review-notes (fnil conj []) note))
      note)))

(defn propose-rework! [root note-id staff-id]
  (let [data (work/read-work root)
        note (coll/first-where #(= note-id (:id %)) (:review-notes data))
        record (delivery/artifact data (:artifact-id note))
        task (delivery/task data (:task-id record))
        id (str "rework_" note-id)]
    (when-not (and (= "changes-requested" (:decision note)) task (not (string/blank? staff-id)))
      (throw (ex-info "返修需要已分配的 Task、明确意见和负责职员" {})))
    (let [proposal {:id id :status "review" :note-id note-id :artifact-id (:id record) :task-id (:id task)
                    :staff-id staff-id :title (str "返修 · " (:title task)) :created-at (str (Instant/now))}]
      (work/transact! root (fn add-proposal [current]
                             (if (coll/any? #(= id (:id %)) (:rework current)) current
                                 (update current :rework (fnil conj []) proposal))))
      proposal)))

(defn rework [data id]
  (coll/first-where #(= id (:id %)) (:rework data)))

(defn confirm-rework! [root id staff]
  (let [data (work/read-work root)
        proposal (rework data id)
        staff (if (coll/any? #(= roles/ai-pm-id (:id %)) staff) staff
                  (conj (vec staff) (roles/ai-pm-card (:roles (roles/read-config root)))))]
    (when-not (contains? #{"review" "allocated"} (:status proposal))
      (throw (ex-info "返修提案不存在或已被拒绝" {})))
    (if (= "allocated" (:status proposal)) proposal
        (let [parent (delivery/verify-artifact! root (delivery/artifact data (:artifact-id proposal)))
              original (delivery/task data (:task-id proposal))
              note (coll/first-where #(= (:note-id proposal) (:id %)) (:review-notes data))
              path (str "/ai-workspace/library/10-delivery/rework/" id ".aicanvas")
              manager {:id "pm" :kind :pm :title "人工确认返修" :staff-id roles/ai-pm-id}
              task {:id id :kind :task :status "review" :source-pm "pm" :source-turn id
                    :stage "成果返修" :title (subs (:title proposal) 0 (min 70 (count (:title proposal))))
                    :staff-id (:staff-id proposal) :directory "/ai-workspace/library/10-delivery/rework"
                    :template (let [original-file (when (:canvas-path original) (craft/resource-file root (:canvas-path original)))
                                    template (when (and original-file (.isFile original-file))
                                               (:template (json/read-str (slurp original-file :encoding "UTF-8") :key-fn keyword)))]
                                (if (contains? pm/template-ids template) template "concept"))
                    :delivery-type (:delivery-type original "document") :required-checks (:required-checks original)
                    :depends-on [] :deliverables (:deliverables original)
                    :prompt (str "依据人工审阅修改固定版本 " (:id parent) "。原任务：" (:title original)
                                 "\n审阅意见：" (:text note) "\n定位：" (pr-str (select-keys note [:path :anchor :checklist]))
                                 "\n原版正文：\n" (:text parent) "\n参考文件（只读，不得覆盖冻结版本）：\n"
                                 (coll/join-to-string "\n" (mapv :path (:files parent)))
                                 "\n交付一个新的候选版本；不得改写原批准或宣布工程验收。")}
              model {:nodes [manager task] :edges []}
              model (assoc model :edges [(graph/stamp-edge model staff {:from "pm" :to id})])
              allocated (pm/allocate! root path model id staff)
              child (graph/node allocated id)
              file (craft/resource-file root path)
              doc {:format "codexcamp.ai-canvas" :schemaVersion 1 :id (str "canvas_" id)
                   :title "人工确认返修" :template "ai-pm" :revision 1
                   :nodes (mapv #(update % :kind name) (:nodes allocated)) :edges (:edges allocated)}]
          (io/make-parents file)
          (when-not (.exists file)
            (Files/write (.toPath file) (.getBytes (json/write-str doc) "UTF-8")
                         ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/CREATE_NEW java.nio.file.StandardOpenOption/WRITE])))
          (work/transact! root
                          (fn accept-rework [current]
                            (-> current
                                (update :tasks #(mapv (fn link-rework [item]
                                                        (cond-> item (= id (:id item))
                                                          (assoc :rework-of (:task-id proposal) :review-note-id (:note-id proposal)))) %))
                                (update :rework #(mapv (fn allocate-proposal [item]
                                                         (cond-> item (= id (:id item))
                                                           (assoc :status "allocated" :canvas-path (:allocated-path child)
                                                                  :person-id (str "person_" (last (string/split (:allocated-canvas-id child) #"_")))))) %)))))
          (rework (work/read-work root) id)))))

(defn move-job! [root id direction]
  (when-not (contains? #{-1 1} direction) (throw (ex-info "队列移动方向无效" {})))
  (work/transact! root
                  (fn move-unstarted [data]
                    (let [jobs (:jobs data)
                          job (coll/first-where #(= id (:id %)) jobs)
                          movable (filterv #(and (= (:staff-id job) (:staff-id %))
                                                 (contains? #{"queued" "blocked" "paused"} (:status %))) jobs)
                          at (.indexOf ^java.util.List (mapv :id movable) id)
                          next (+ at direction)]
                      (when (neg? at) (throw (ex-info "只能调整未开始的工作，执行中或待核对任务不能移动" {})))
                      (if-not (< -1 next (count movable)) data
                              (let [other (:id (nth movable next))
                                    a (.indexOf ^java.util.List (mapv :id jobs) id)
                                    b (.indexOf ^java.util.List (mapv :id jobs) other)]
                                (assoc data :jobs (assoc jobs a (nth jobs b) b (nth jobs a)))))))))
