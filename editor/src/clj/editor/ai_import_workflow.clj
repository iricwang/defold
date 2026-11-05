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

(ns editor.ai-import-workflow
  (:require [clojure.string :as string]
            [editor.ai-canvas :as canvas]
            [editor.ai-delivery :as delivery]
            [editor.ai-graph :as graph]
            [editor.ai-resource-import :as resource-import]
            [editor.ai-results :as results]
            [editor.ai-tasks :as tasks]
            [util.coll :as coll]))

(defn source-card [model action-id]
  (graph/node model (:from (coll/first-where #(and (= action-id (:to %)) (graph/input-edge? model %)) (:edges model)))))

(defn add-action [model staff assets-id]
  (let [asset (graph/node model assets-id)
        _ (when-not (get-in asset [:production :manifest]) (throw (ex-info "请先拆分并生成 GUI 资源，再创建资源入库任务" {})))
        model (canvas/add-node model :action (+ (or (:x asset) 0.0) 340.0) (+ (or (:y asset) 0.0) 520.0))
        id (:selected model)
        model (canvas/update-node model id #(assoc % :title "资源入库" :task-type "resource-import"
                                                   :prompt "整理所连接 Assets 的 GUI 素材、图集和引用，生成入库方案供人工确认；确认后由负责人队列执行。"))
        owner (loop [upstream assets-id]
                (if (= :action (:kind (graph/node model upstream)))
                  (tasks/person-id model upstream)
                  (when-let [edge (coll/first-where #(= upstream (:to %)) (:edges model))] (recur (:from edge)))))]
    (cond-> (graph/connect model staff assets-id id)
      owner (graph/connect staff owner id))))

(defn handles? [model id]
  (resource-import/import-action? (graph/node model (if (= :result (:kind (graph/node model id))) (results/action-id model id) id))))

(defn validate-confirmation! [root card]
  (resource-import/prepare! root (resource-import/parse-plan (:text (results/candidate card)))))

(defn plan-state! [root state id]
  (let [model (:canvas state)
        asset (source-card model id)
        manifest (get-in asset [:production :manifest])
        gui (get-in asset [:production :gui])
        plan (if (and gui (not (string/includes? gui "/ai_generated/")))
               (resource-import/plan-existing-gui! root manifest nil gui)
               (resource-import/plan! root manifest nil))
        proposal {:id (str "import/" (:id plan)) :text (resource-import/plan-text plan) :files []}]
    (update state :canvas #(-> % (results/merge-output id [proposal]) (canvas/update-node id (fn mark-plan [card] (assoc card :import-status "待确认入库方案")))))))

(defn apply-state! [root state id validate!]
  (let [card (graph/node (:canvas state) id)
        plan (resource-import/parse-plan (:text (results/candidate card)))
        output (resource-import/apply! root plan validate!)
        model (-> (:canvas state) (canvas/ensure-result-assets id) (canvas/adopt-candidate id))
        assets-id (:to (coll/first-where #(= id (:from %)) (:edges model)))
        model (-> model
                  (canvas/update-node assets-id #(assoc % :assets (mapv (fn imported-file [path] {:path path :source path :name (last (string/split path #"/"))}) (sort (:paths output)))))
                  (canvas/deliver-gui assets-id output)
                  (canvas/update-node id #(assoc % :import-status "已入库 · 编译通过"))
                  (canvas/update-node (results/action-id model id) #(assoc % :import-status "已入库 · 编译通过")))
        card (assoc (graph/node model assets-id) :source-action-id (results/action-id model id) :execution-card-id id :approved-plan-id (:artifact-id card))
        receipt (delivery/record-delivery! root state card output)]
    (assoc state :canvas (update model :nodes #(mapv (fn bind-receipt [node]
                                                       (cond-> node
                                                         (or (= assets-id (:id node))
                                                             (and (= :gui (:kind node)) (= (:gui output) (:gui-resource node))))
                                                         (assoc :artifact-id (:artifact-id receipt) :delivery-id (:id receipt)))) %)))))
