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

(ns editor.ai-templates
  "Portable templates without execution identity or inherited approvals."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.ai-file-ops :as files]
            [editor.ai-graph :as graph]
            [editor.ai-resource-import :as admission]
            [editor.ai-roles :as roles]
            [util.coll :as coll]
            [util.digest :as digest]))

(def staff-fields [:id :title :role-type :assignee :model :effort :responsibilities :deliverables :enabled])
(def card-fields [:id :kind :title :prompt :stage :stage-id :directory :template :deliverables :delivery-type :required-checks :task-type :staff-id :depends-on :source-pm])

(defn- config-at [root path fallback]
  (let [f (files/file root path)] (if (.isFile f) (files/read-json f) fallback)))

(defn sanitize-document [doc ids staff-map]
  (let [nodes (filterv #(contains? #{"person" "action" "pm" "task"} (:kind %)) (:nodes doc))
        present (set (mapv :id nodes))
        edges (filterv #(and (contains? present (:from %)) (contains? present (:to %))) (:edges doc))
        model {:nodes (mapv #(update % :kind keyword) nodes) :edges edges}]
    (when-not (and (= "codexcamp.ai-canvas" (:format doc)) (= 1 (:schemaVersion doc))
                   (contains? #{"concept" "gui" "art-characters" "art-levels" "balancing" "ai-pm"} (:template doc))
                   (vector? (:nodes doc)) (vector? (:edges doc)) (string? (:title doc))
                   (<= 1 (count nodes) 1000) (= (count nodes) (count present))
                   (coll/every? #(and (string? (:id %)) (string? (:title %))) nodes))
      (throw (ex-info "模板 Canvas 格式无效或没有可复用的工作节点" {})))
    (doseq [{:keys [from to]} edges]
      (when (or (not (contains? (set (get graph/successors (:kind (graph/node model from)))) (:kind (graph/node model to))))
                (= from to) (graph/dependency-cycle? model from to))
        (throw (ex-info "模板包含无效或循环连接" {:from from :to to}))))
    {:format "codexcamp.ai-canvas" :schemaVersion 1 :id (get ids (:id doc) (:id doc))
     :title (:title doc) :template (:template doc) :revision 1
     :nodes (mapv (fn clean-card [card]
                    (let [staff (get staff-map (:staff-id card))]
                      (cond-> (-> (select-keys card card-fields)
                                  (assoc :id (get ids (:id card) (:id card)))
                                  (update :prompt #(first (string/split (or % "") #"\n\n人工确认的任务交接：" 2))))
                        staff (assoc :staff-id staff)
                        (nil? staff) (dissoc :staff-id)
                        (:source-pm card) (assoc :source-pm (get ids (:source-pm card)))
                        (:stage-id card) (assoc :stage-id (str "stage_tpl_" (subs (digest/string->sha256-hex (str (get ids (:id doc) (:id doc)) "/" (:stage-id card))) 0 16)))
                        (= "task" (:kind card)) (assoc :status "review" :dependency-wires true
                                                       :depends-on (mapv ids (filterv present (:depends-on card))))))) nodes)
     :edges (coll/into-> (:edges doc) []
              (filter #(and (contains? present (:from %)) (contains? present (:to %))))
              (map #(hash-map :from (get ids (:from %) (:from %)) :to (get ids (:to %) (:to %)))))}))

(defn export-data [root]
  (let [config (roles/validate-config (config-at root roles/config-path (roles/default-config)))
        roster (config-at root roles/document-path {:nodes []})
        staff (mapv #(select-keys % staff-fields) (:nodes roster))
        ids (into {roles/ai-pm-id roles/ai-pm-id} (mapv (juxt :id :id) staff))
        documents (coll/into-> (files/inventory root) []
                    (filter #(and (string/starts-with? (:path %) "/ai-workspace/library/") (string/ends-with? (:path %) ".aicanvas")))
                    (map (fn export-canvas [entry]
                           (let [doc (files/read-json (files/file root (:path entry)))
                                 nodes (into (:nodes doc) [doc])]
                             {:path (:path entry) :document (sanitize-document doc (into {} (mapv (juxt :id :id) nodes)) ids)}))))]
    {:format "defold.ai-workbench-template" :version 1 :role-types config :staff staff :documents documents
     :resource-rules (admission/validate-rules! root (config-at root admission/rules-path admission/default-rules))}))

(defn import-plan [root package replace-rules]
  (when-not (and (= "defold.ai-workbench-template" (:format package)) (= 1 (:version package))
                 (vector? (:staff package)) (<= (count (:staff package)) 500)
                 (vector? (:documents package)) (<= (count (:documents package)) 500))
    (throw (ex-info "不是支持的工作台模板包" {})))
  (roles/validate-config (:role-types package))
  (doseq [person (:staff package)]
    (roles/validate-role person)
    (when-not (coll/any? #(= (:role-type person) (:id %)) (get-in package [:role-types :roles]))
      (throw (ex-info "模板职员使用了未包含的职能定义" {:staff (:id person) :role (:role-type person)}))))
  (doseq [items [(:staff package) (mapv :document (:documents package))]]
    (when-not (coll/every? #(and (string? (:id %)) (<= 1 (count (:id %)) 200)) items) (throw (ex-info "模板身份无效" {})))
    (when-not (= (count items) (count (set (mapv :id items)))) (throw (ex-info "模板包含重复身份" {}))))
  (admission/validate-rules! root (:resource-rules package))
  (let [key (subs (digest/string->sha256-hex (pr-str package)) 0 12)
        identity-key (subs (digest/string->sha256-hex (str key "/" (.getCanonicalPath (io/file root)))) 0 12)
        config (roles/validate-config (config-at root roles/config-path (roles/default-config)))
        local-types (into {} (mapv (juxt :id identity) (:roles config)))
        type-map (into {} (mapv (fn type-id [type]
                                  [(:id type) (if (or (nil? (get local-types (:id type))) (= type (get local-types (:id type)))) (:id type)
                                                  (str (subs (:id type) 0 (min 45 (count (:id type)))) "_" key))]) (get-in package [:role-types :roles])))
        types (reduce (fn merge-type [out type]
                        (let [type (assoc type :id (get type-map (:id type)))]
                          (if (coll/any? #(= (:id type) (:id %)) out) out (conj out type)))) (:roles config) (get-in package [:role-types :roles]))
        _ (roles/validate-config (assoc config :roles types))
        staff-map (into {roles/ai-pm-id roles/ai-pm-id}
                        (mapv #(vector (:id %) (if (= roles/ai-pm-id (:id %)) roles/ai-pm-id
                                                   (str "staff_tpl_" identity-key "_" (subs (digest/string->sha256-hex (:id %)) 0 10)))) (:staff package)))
        roster (config-at root roles/document-path {:format "codexcamp.ai-canvas" :schemaVersion 1 :id (str "canvas_roles_" key) :title "项目职能" :template "project-roles" :revision 1 :nodes [] :edges []})
        imported (mapv #(-> (select-keys % staff-fields) (assoc :id (get staff-map (:id %)) :kind "role" :role-type (get type-map (:role-type %))))
                       (filterv #(not= roles/ai-pm-id (:id %)) (:staff package)))
        next-nodes (reduce (fn add-staff [out person] (if (coll/any? #(= (:id person) (:id %)) out) out (conj out person))) (:nodes roster) imported)
        roster (cond-> (assoc roster :nodes next-nodes) (not= next-nodes (:nodes roster)) (update :revision (fnil inc 0)))
        doc-writes (mapv (fn clone-document [{:keys [path document]}]
                           (when-not (re-matches #"/ai-workspace/library/[0-9]{2}-[a-z]+/.+\.aicanvas" path) (throw (ex-info "模板路径必须位于创作资料分类" {})))
                           (let [section (nth (string/split path #"/") 3)
                                 suffix (subs (digest/string->sha256-hex path) 0 10)
                                 target (str "/ai-workspace/library/" section "/templates-" key "/" suffix "-" (.getName (io/file path)))
                                 ids (into {} (mapv #(vector (:id %) (str (if (= % document) "canvas_" "card_") identity-key "_" (subs (digest/string->sha256-hex (str path "/" (:id %))) 0 16))) (into (:nodes document) [document])))]
                             {:path target :bytes (files/json-bytes (sanitize-document document ids staff-map))})) (:documents package))
        writes (into [{:path roles/config-path :bytes (files/json-bytes (assoc config :roles types))}
                      {:path roles/document-path :bytes (files/json-bytes roster)}] doc-writes)
        writes (cond-> writes (or replace-rules (not (.isFile (files/file root admission/rules-path))))
                 (conj {:path admission/rules-path :bytes (files/json-bytes (:resource-rules package))}))]
    (doseq [entry doc-writes]
      (when (and (.exists (files/file root (:path entry))) (not= (files/hash-bytes (:bytes entry)) (files/hash-file (files/file root (:path entry)))))
        (throw (ex-info "已导入的模板工作文件已有人修改，不能覆盖；请导出新的模板版本" {:path (:path entry)}))))
    (files/plan root "import-template" (filterv #(not= (files/hash-bytes (:bytes %)) (files/hash-file (files/file root (:path %)))) writes)
                {:template key :role-mapping type-map :staff-mapping staff-map :documents (mapv :path doc-writes)
                 :summary (into [(str "资源规则：" (if (or replace-rules (not (.isFile (files/file root admission/rules-path)))) "采用模板规则" "保留本项目规则"))]
                                (into (mapv (fn role-summary [type]
                                              (str "职能「" (:title type) "」："
                                                   (if (= (:id type) (get type-map (:id type))) "复用 / 补充定义" "与本地冲突，新增独立定义")
                                                   "；默认模型 " (get-in type [:defaults :model]) " / " (get-in type [:defaults :effort]))) (get-in package [:role-types :roles]))
                                      (mapv (fn staff-summary [person]
                                              (str "职员「" (:title person) "」：" (:model person) " / " (:effort person)
                                                   "\n  职责：" (:responsibilities person) "\n  交付：" (:deliverables person))) imported)))
                 :notice "仅导入结构、人员与默认要求。结果、批准、已分配状态及聊天绑定不继承。职能冲突创建新定义；已有人员不覆盖。"})))
