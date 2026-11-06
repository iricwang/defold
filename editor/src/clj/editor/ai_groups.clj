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

(ns editor.ai-groups
  "Presentation-only task groups; folding never changes the execution graph."
  (:require [util.coll :as coll]))

(defn groups [model mode]
  (let [nodes (:nodes model)
        by-id (into {} (mapv (juxt :id identity) nodes))
        children (group-by :from (:edges model))
        roots (filterv #(contains? (if (= :task mode) #{:task :action} #{:person :task}) (:kind %)) nodes)
        branches (mapv (fn branch [node]
                         (let [members (loop [pending [(:id node)] found #{}]
                                         (if-not (coll/not-empty pending) found
                                                 (let [id (peek pending) pending (pop pending)]
                                                   (if (contains? found id) (recur pending found)
                                                       (recur (into pending (coll/into-> (get children id) []
                                                                              (map :to)
                                                                              (remove #(contains? #{:person :pm :task} (:kind (get by-id %)))))) (conj found id))))))]
                           {:id (:id node) :title (:title node) :anchor (:id node) :members members
                            :stage (or (coll/not-empty (:stage node)) "未设阶段")})) roots)]
    (if-not (= :stage mode) branches
            (mapv (fn stage-group [[stage items]]
                    {:id stage :title stage :anchor (:anchor (first items)) :members (coll/into-> items #{} (mapcat :members))})
                  (sort-by key (group-by :stage branches))))))

(defn visible-model [model mode folded]
  (let [selected (filterv #(contains? folded (:id %)) (groups model mode))
        anchors (set (mapv :anchor selected))
        hidden (coll/into-> selected #{} (mapcat :members) (remove anchors))
        counts (into {} (mapv #(vector (:anchor %) (dec (count (:members %)))) selected))]
    (-> model
        (update :nodes #(coll/into-> % []
                          (remove (fn hidden-card [node] (contains? hidden (:id node))))
                          (map (fn label-group [node]
                                 (if-let [n (get counts (:id node))] (assoc node :folded-count n) node)))))
        (update :edges #(filterv (fn visible-edge [edge] (not (or (contains? hidden (:from edge)) (contains? hidden (:to edge))))) %)))))
