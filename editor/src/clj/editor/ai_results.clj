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

(ns editor.ai-results
  "One editable proposal per Result, with permanent Action provenance."
  (:require [clojure.string :as string]
            [editor.ai-graph :as graph]
            [editor.ai-tasks :as tasks]
            [util.coll :as coll]
            [util.digest :as digest]))

(defn candidate [card] (first (:candidates card)))

(defn revision [card]
  (let [{:keys [id text image-path files]} (candidate card)]
    (digest/string->sha256-hex (pr-str [id text image-path (mapv #(select-keys % [:path :sha256 :name]) files)]))))

(defn action-id [model result-id]
  (let [id (:from (coll/first-where #(= result-id (:to %)) (:edges model)))]
    (when (= :action (:kind (graph/node model id))) id)))

(defn cards [model action]
  (coll/into-> (:edges model) []
    (filter #(= action (:from %)))
    (keep #(let [card (graph/node model (:to %))] (when (= :result (:kind card)) card)))))

(defn produced-cards [model action]
  (filterv candidate (cards model action)))

(defn locked-edge? [model edge]
  (= [:action :result] (mapv #(:kind (graph/node model %)) [(:from edge) (:to edge)])))

(defn- put-card [model card]
  (update model :nodes
          (fn put-result [nodes]
            (if (coll/any? #(= (:id card) (:id %)) nodes)
              (mapv #(if (= (:id card) (:id %)) card %) nodes)
              (conj (vec nodes) card)))))

(defn merge-output [model action candidates]
  (reduce
    (fn merge-proposal [model proposal]
      (let [existing (cards model action)
            same (coll/first-where #(= (:id proposal) (:id (candidate %))) existing)
            empty-card (coll/first-where #(not (coll/not-empty (:candidates %))) existing)
            source (graph/node model action)
            index (count existing)
            x (+ 310.0 (double (or (:x source) 0.0)))
            y (loop [y (double (or (:y source) 0.0))]
                (if (coll/any? #(and (< (Math/abs (- x (double (or (:x %) 0.0)))) 280.0)
                                     (< (Math/abs (- y (double (or (:y %) 0.0)))) 490.0)) (:nodes model))
                  (recur (+ y 500.0))
                  y))
            card (or same empty-card
                     {:id (str "result_" (subs (digest/string->sha256-hex (str action "/" (:id proposal))) 0 24))
                      :kind :result :title (str "方案 " (inc index))
                      :x x :y y})
            card (assoc card :source-action action
                        :generated-candidate proposal
                        :candidates [(if (or (:manually-edited card) (:confirmed-revision card)) (candidate card) proposal)]
                        :chosen (:id proposal))
            model (put-card model card)]
        (if (or same empty-card)
          model
          (update model :edges conj {:from action :to (:id card) :locked true}))))
    model candidates))

(defn migrate [model]
  (reduce
    (fn split-result [model old]
      (if-let [action (action-id model (:id old))]
        (let [proposals (:candidates old)
              chosen (or (coll/first-where #(= (:chosen old) (:id %)) proposals) (first proposals))
              base (cond-> (assoc old :source-action action :candidates (if chosen [chosen] []))
                     chosen (assoc :chosen (:id chosen)))
              adopted (coll/any? #(and (= (:id old) (:source-result %))
                                       (= (:id chosen) (:source-candidate %))) (:nodes model))
              base (cond-> base
                     (and chosen adopted (nil? (:source-action old)) (nil? (:confirmed-revision base)))
                     (assoc :confirmed-revision (revision base) :confirmed-candidate chosen))
              model (put-card model base)]
          (-> (merge-output model action (filterv #(not= (:id chosen) (:id %)) proposals))
              (update :edges #(mapv (fn lock-origin [edge]
                                      (cond-> edge (= (:id old) (:to edge)) (assoc :locked true))) %))))
        model))
    model (filterv #(= :result (:kind %)) (:nodes model))))

(defn edit [model id fields]
  (let [card (graph/node model id)
        proposal (candidate card)]
    (when-not (and (= :result (:kind card)) proposal)
      (throw (ex-info "此 Result 尚未产出方案" {})))
    (put-card model (assoc card :generated-candidate (or (:generated-candidate card) proposal)
                           :candidates [(merge proposal fields)] :manually-edited true))))

(defn confirmation [model id]
  (let [card (graph/node model id)
        proposal (candidate card)]
    (when (or (nil? proposal)
              (and (string/blank? (:text proposal)) (string/blank? (:image-path proposal))
                   (not (coll/not-empty (:files proposal)))))
      (throw (ex-info "请先补全方案内容" {})))
    {:result-id id :action-id (action-id model id) :revision (revision card) :candidate proposal
     :draft (str "[人工确认方案]\n来源任务：" (:title (graph/node model (action-id model id)))
                 "\n方案：" (:title card) "\n版本：" (revision card)
                 "\n用户已确认下面的方案（可能包含人工修改），请将其作为此任务后续工作的正式依据，记录确认并整理交付；保留其他已确认方案的独立产出，不覆盖其他方案。\n\n"
                 (:text proposal)
                 (when (:image-path proposal) (str "\n方案图片：" (:image-path proposal)))
                 (when (coll/not-empty (:files proposal))
                   (str "\n附件：\n" (coll/join-to-string "\n" (mapv #(or (:source %) (:path %) (:name %)) (:files proposal))))))}))

(defn edit-state [state id fields]
  (let [before (:canvas state)
        after (edit before id fields)]
    (if (= (candidate (graph/node before id)) (candidate (graph/node after id)))
      state
      (do
        (when (tasks/confirmation-locked? state id)
          (throw (ex-info "此版本已加入负责人队列，请等待处理完成后再修改" {})))
        (-> state (assoc :canvas after) (update :result-drafts dissoc id))))))

(defn confirm-state [state id]
  (let [model (:canvas state)
        _ (when-let [error (graph/task-error model (:staff state) id)] (throw (ex-info error {})))
        downstream (:to (coll/first-where #(= id (:from %)) (:edges model)))
        _ (when (contains? (:producing state) downstream)
            (throw (ex-info "此方案正在生成交付资源，请完成后再确认新版本" {})))
        confirmation (confirmation model id)
        person (tasks/person-id model (:action-id confirmation))
        queued (tasks/enqueue-confirmation state person confirmation)]
    (if (identical? state queued)
      state
      (update queued :canvas put-card (assoc (graph/node model id)
                                        :confirmed-revision (:revision confirmation)
                                        :confirmed-candidate (:candidate confirmation))))))
