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


(ns editor.ai-document
  "Portable standalone creative Canvas documents."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.ai-canvas :as canvas]
            [editor.ai-roles :as roles]
            [editor.craft-project :as craft-project]
            [util.coll :as coll])
  (:import [java.nio.file Files StandardCopyOption]
           [java.util UUID]))

(set! *warn-on-reflection* true)

(defn document-path? [path]
  (and (string? path) (string/ends-with? path ".aicanvas")))

(defn read-document [root path]
  (let [file (craft-project/resource-file root path)
        _ (when (> (.length file) (* 16 1024 1024)) (throw (ex-info "Canvas 文档超过 16 MB" {:path path})))
        doc (json/read-str (slurp file) :key-fn keyword)]
    (when-not (= "codexcamp.ai-canvas" (:format doc))
      (throw (ex-info "不是有效的 AI Canvas 文档：format" {:path path})))
    (when-not (and (string? (:id doc)) (string/starts-with? (:id doc) "canvas_")
                   (string? (:title doc)) (pos-int? (:revision doc)))
      (throw (ex-info "Canvas 缺少 id 或 title" {:path path})))
    (when (= 1 (:schemaVersion doc))
      (when-not (or (= "project-roles" (:template doc)) (canvas/template (:template doc)))
        (throw (ex-info "当前编辑器不支持此 Canvas 方案类型" {:template (:template doc)})))
      (let [nodes (:nodes doc) ids (set (mapv :id nodes))]
        (when-not (and (vector? nodes) (= (count nodes) (count ids))
                       (coll/every? #(and (string? (:id %)) (contains? (if (= "project-roles" (:template doc)) #{"role"} #{"person" "action" "result" "assets" "gui"}) (:kind %))) nodes)
                       (vector? (:edges doc))
                       (coll/every? #(and (contains? ids (:from %)) (contains? ids (:to %))) (:edges doc)))
          (throw (ex-info "Canvas nodes 或 edges 无效" {:path path})))
        (when (= "project-roles" (:template doc))
          (doseq [node nodes] (roles/validate-role node)))
        (reduce (fn validate-edge [model edge] (canvas/connect-nodes model (:from edge) (:to edge)))
                {:nodes (mapv #(update % :kind keyword) nodes) :edges []} (:edges doc))))
    (if (= "project-roles" (:template doc))
      (let [catalog (:roles (roles/read-config root))
            ids (set (mapv :id catalog))]
        (doseq [node (:nodes doc) :when (and (:role-type node) (not (contains? ids (:role-type node))))]
          (throw (ex-info (str "职能配表缺少已使用的职能：" (:role-type node)) {})))
        (with-meta doc {::role-types catalog}))
      doc)))

(defn ensure-roles-document! [root]
  (let [catalog (:roles (roles/read-config root))
        file (craft-project/resource-file root roles/document-path)]
    (io/make-parents file)
    (when-not (.exists file)
      (let [doc {:format "codexcamp.ai-canvas" :schemaVersion 1
                 :id (str "canvas_" (UUID/randomUUID)) :title "项目职能" :template "project-roles" :revision 1
                 :nodes (reduce (fn initial-staff [nodes type]
                                  (reduce (fn add-person [nodes _]
                                            (conj nodes (roles/new-role type (coll/into-> nodes #{} (map :title)))))
                                          nodes (range (:initialCount type)))) [] catalog)
                 :edges []}]
        (try
          (Files/write (.toPath file) (.getBytes (json/write-str doc) "UTF-8")
                       ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/CREATE_NEW java.nio.file.StandardOpenOption/WRITE]))
          (catch java.nio.file.FileAlreadyExistsException _ nil))))
    roles/document-path))

(defn create-document! [root directory title template-id]
  (let [title (string/trim title)
        template (canvas/template template-id)]
    (when-not template (throw (ex-info "未知 Canvas 方案类型" {:template template-id})))
    (when-not (and (<= 1 (count title) 80) (not (re-find #"[/\\:<>|?*\p{Cntrl}]" title))
                   (not (contains? #{"." ".."} title))
                   (re-matches #"ai-workspace/library/[0-9]{2}-[a-z]+(?:/[^./][^/]*)*" directory)
                   (not (re-find #"(?:^|/)\.\.?(?:/|$)" directory)))
      (throw (ex-info "请选择创作分类，名称不能包含路径或特殊字符" {})))
    (let [path (str "/" directory "/" title ".aicanvas")
          file (craft-project/resource-file root path)
          model (cond-> (canvas/initial-canvas) (= "gui" template-id) canvas/migrate-gui-cards)
          ids (into {} (mapv (fn [node] [(:id node) (str "card_" (UUID/randomUUID))]) (:nodes model)))
          doc {:format "codexcamp.ai-canvas" :schemaVersion 1
               :id (str "canvas_" (UUID/randomUUID)) :title title :template (:id template) :revision 1
               :nodes (mapv (fn [node] (-> node
                                           (assoc :id (get ids (:id node)) :title (get template (:kind node)))
                                           (dissoc :x :y))) (:nodes model))
               :edges (mapv #(-> % (update :from ids) (update :to ids)) (:edges model))}]
      (Files/write (.toPath file) (.getBytes (json/write-str doc) "UTF-8")
                   ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/CREATE_NEW java.nio.file.StandardOpenOption/WRITE]))
      path)))

(defn model [doc presentation]
  (let [positions (into {} (mapv (juxt :id #(select-keys % [:x :y])) (:nodes presentation)))]
    (cond-> (merge (canvas/initial-canvas) (select-keys presentation [:selected :viewport :next-flow-id])
                   {:workflow (if (= "project-roles" (:template doc)) :roles :document) :template (:template doc) :edges (:edges doc)
                    :selected (or (:selected presentation) (:id (first (:nodes doc))))
                    :nodes (mapv (fn [index node]
                                   (merge {:x (* 310.0 index) :y 0.0} node (get positions (:id node)) {:kind (keyword (:kind node))}))
                                 (range (count (:nodes doc))) (:nodes doc))})
      (= "project-roles" (:template doc)) (-> (assoc :role-types (or (::role-types (meta doc)) (:roles (roles/default-config))))
                                              roles/layout roles/assign-staff-names roles/apply-defaults)
      (and (= "project-roles" (:template doc)) (coll/any? #(nil? (:role-type %)) (:nodes doc)))
      (assoc :viewport {:x 24.0 :y 24.0 :zoom 0.85}))))

(defn save-document! [root path expected model]
  (let [current (read-document root path)
        nodes (mapv #(-> % (dissoc :x :y) (update :kind name)) (:nodes model))
        changed (not= [nodes (:edges model)] [(:nodes current) (:edges current)])]
    (when-not (and (= 1 (:schemaVersion current)) (= expected current))
      (throw (ex-info "Canvas 已被外部修改，请关闭并重新打开后继续；本次未覆盖文件" {:path path})))
    (if-not changed
      current
      (let [next (assoc current :nodes nodes :edges (:edges model) :revision (inc (:revision current)))
            file (craft-project/resource-file root path)
            temp (Files/createTempFile (.toPath (.getParentFile file)) "canvas-" ".tmp" (make-array java.nio.file.attribute.FileAttribute 0))]
        (try
          (Files/write temp (.getBytes (json/write-str next) "UTF-8") ^"[Ljava.nio.file.OpenOption;" (make-array java.nio.file.OpenOption 0))
          (Files/move temp (.toPath file) (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
          next
          (finally (Files/deleteIfExists temp)))))))

(defn export-document! [root path node]
  (let [asset (first (:assets node))
        text (or (:proposal-text node) (:text asset) "")
        images (filterv :preview (:assets node))]
    (when (and (string/blank? text) (not (coll/not-empty images)))
      (throw (ex-info "请先采用一份方案" {})))
    (let [source (craft-project/resource-file root path)
          name (str (subs (.getName source) 0 (- (count (.getName source)) 9)) "-" (subs (str (UUID/randomUUID)) 0 8) ".md")
          relative (str (subs path 0 (inc (.lastIndexOf ^String path "/"))) name)
          target (craft-project/resource-file root relative)]
      (Files/write (.toPath target)
                   (.getBytes (str text
                                   (coll/join-to-string "" (mapv (fn image-reference [image]
                                                                   (let [file (io/file (java.net.URI/create (:preview image)))
                                                                         relative (.relativize (.toPath (.getParentFile source)) (.toPath file))]
                                                                     (str "\n\n![方案图](<" (string/replace (str relative) "\\" "/") ">)"))) images))
                                   "\n\n---\n来源 Canvas: " path "\n\n候选: " (:source-candidate node) "\n") "UTF-8")
                   ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/CREATE_NEW java.nio.file.StandardOpenOption/WRITE]))
      relative)))
