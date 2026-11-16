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

(ns editor.skeletal-animation-rig-ui-test
  (:require [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-rig-ui :as rig-ui]
            [editor.skeletal-animation-view :as view]
            [editor.ui :as ui])
  (:import [javafx.scene Scene]
           [javafx.scene.control ToggleButton TreeItem TreeView]
           [javafx.scene.layout VBox]))

(defn- fixture []
  (let [controls (mapv #(str "control" %) (range 1 13))]
    (-> (data/new-document "tail")
        (assoc :bones (into [{:id "root"} {:id "a" :parent "root"} {:id "b" :parent "root"}]
                            (mapv #(hash-map :id % :parent "root") controls))
               :slots [{:id "path" :bone "root" :attachment {:type "path" :vertices [0 0 1 1 2 2]}}]
               :path [{:id "follow" :bones ["a" "b"] :target "path"}]
               :auto-rigs {"current" {:slot "tail" :bones ["a" "b"] :controls controls}
                           "stale" {:slot "tail" :bones ["removed"] :controls ["missing"]}}))))

;; Ignore stale/deleted or reparented generated rigs, and preserve numeric
;; control ordering beyond nine without changing the authoring hierarchy.
(deftest active-rig-group-test
  (let [doc (fixture)
        groups (rig-ui/groups doc)]
    (is (= 1 (count groups)))
    (is (= "follow" (:id (first groups))))
    (is (= (mapv #(str "control" %) (range 1 13))
           (rig-ui/ordered-controls doc "follow" (set (:controls (first groups))))))
    (is (= [] (rig-ui/groups (update doc :bones pop))))
    (is (= [] (rig-ui/groups (update doc :path conj (assoc (first (:path doc)) :id "duplicate"))))))
  (is (= [] (rig-ui/groups (assoc-in (fixture) [:bones 1 :parent] "b")))))

;; Number buttons wrap in narrow panels, keep one selection, and dispatch the
;; original control ID instead of a display index.
(deftest control-grid-wrap-and-select-test
  (ui/run-now
    (let [selected (atom nil)
          ids (mapv #(str "control" %) (range 1 13))
          grid (rig-ui/control-grid ids "control1" (fn select-control [id] (reset! selected id)) (fn tooltip [index id] (str index " " id)))
          root (VBox. (into-array javafx.scene.Node [grid]))
          _ (Scene. root 150.0 220.0)]
      (.resize root 150.0 220.0)
      (.applyCss root)
      (.layout root)
      (let [buttons (vec (.getChildren grid))]
        (is (= (mapv str (range 1 13)) (mapv #(.getText ^ToggleButton %) buttons)))
        (is (> (.getLayoutY ^ToggleButton (nth buttons 5)) (.getLayoutY ^ToggleButton (first buttons))))
        (.fire ^ToggleButton (nth buttons 10))
        (is (= "control11" @selected))
        (is (= 1 (count (filterv #(.isSelected ^ToggleButton %) buttons))))
        (.fire ^ToggleButton (nth buttons 10))
        (is (.isSelected ^ToggleButton (nth buttons 10)))))))

;; Tree regrouping keeps actual selection IDs and parent data, collapses noisy
;; generated bones/path data and leaves the two control handles accessible.
(deftest grouped-tree-selection-test
  (ui/run-now
    (let [doc (fixture)
          state (atom {:selected-kind :bone :selected "control2"})
          tree (TreeView.)
          context {:tree tree :state state :localization :k}]
      (#'view/refresh-tree! context doc)
      (let [items (:tree-items @state)
            rig ^TreeItem (get items [:path "follow"])
            root-bone ^TreeItem (get items [:bone "root"])
            control ^TreeItem (get items [:bone "control2"])
            driven ^TreeItem (.getParent ^TreeItem (get items [:bone "a"]))
            raw ^TreeItem (.getParent ^TreeItem (get items [:slot "path"]))]
        (is (identical? root-bone (.getParent rig)))
        (is (identical? rig (.getParent control)))
        (is (= "control2" (:id (.getValue (.getSelectedItem (.getSelectionModel tree))))))
        (is (false? (.isExpanded driven)))
        (is (false? (.isExpanded raw)))
        (is (= "root" (:parent (second (:bones doc)))))
        (#'view/refresh-tree! context doc)
        (is (= 1 (count (filterv #(= :path (:kind (.getValue ^TreeItem %))) (.getChildren ^TreeItem (get-in @state [:tree-items [:bone "root"]]))))))))))
