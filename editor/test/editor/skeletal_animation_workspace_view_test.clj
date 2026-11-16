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

(ns editor.skeletal-animation-workspace-view-test
  (:require [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-clipping :as clipping]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-project :as project]
            [editor.skeletal-animation-workspace-view :as workspace-view]
            [util.coll :as coll]))

(defn- workspace [names selected]
  (project/assemble (mapv #(hash-map :name % :document (data/new-document %)) names) selected))

(defn- renderer [calls]
  (fn render-setup [document sampled evaluated camera]
    (swap! calls conj {:name (:name document) :sampled sampled :evaluated evaluated :camera camera})
    [{:id "image" :points [[0.0 0.0] [40.0 0.0] [40.0 40.0] [0.0 40.0]]
      :uvs [[0.0 0.0] [1.0 0.0] [1.0 1.0] [0.0 1.0]] :triangles [[0 1 2] [0 2 3]]
      :tint [1.0 0.5 0.25] :dark-tint [0.1 0.2 0.3] :opacity 0.5}]))

;; Neighbor setup poses render beside the active document, have scoped IDs,
;; fit their cells, and expose only label hit targets for switching documents.
(deftest neighboring-setup-preview-test
  (let [document (workspace ["left" "active" "right"] "active")
        calls (atom [])
        result (workspace-view/preview document "active"
                                       {:enabled true :zoom 1.0 :pan-x 500.0 :pan-y 250.0 :width 1000.0 :height 700.0}
                                       (renderer calls))]
    (is (= ["left" "right"] (mapv :name @calls)))
    (is (= ["left" "right"] (mapv :name (:entries result))))
    (is (= ["left" "active" "right"] (mapv :name (:labels result))))
    (is (= [[-424.0 0.0] [424.0 0.0]] (mapv :screen-offset (:entries result))))
    (is (coll/every? false? (mapv :editable (:entries result))))
    (is (= 4 (count (set (mapv :id (:slots result))))))
    (is (= 0.21 (:opacity (first (:slots result)))))
    (doseq [entry (:entries result)
            point (coll/into-> (:slots entry) [] (mapcat :points))
            :let [[x y width height] (:bounds entry) [px py] point]]
      (is (<= x px (+ x width)))
      (is (<= y py (+ y height))))
    (doseq [{:keys [name bounds]} (:labels result)
            :let [[x y width height] bounds]]
      (is (= name (workspace-view/hit-label result (+ x (/ width 2.0)) (+ y (/ height 2.0))))))
    (is (nil? (workspace-view/hit-label result 500.0 250.0)))
    (is (= document (workspace ["left" "active" "right"] "active")))))

;; Virtualization computes visible indices before sampling. At minimum zoom a
;; thousand-document workspace still samples only readable on-screen cells.
(deftest workspace-viewport-culling-test
  (let [document (workspace (mapv #(str "rig" %) (range 1000)) "rig500")
        calls (atom [])
        camera {:enabled true :zoom 0.02 :pan-x 400.0 :pan-y 200.0 :width 800.0 :height 500.0}
        result (workspace-view/preview document "rig500" camera (renderer calls))
        initial-count (count @calls)]
    (is (< 0 initial-count 10))
    (is (= 1000 (:skeleton-count result)))
    (is (not (contains? (set (mapv :name @calls)) "rig500")))
    (is (= [] (:entries (workspace-view/preview document "rig500" (assoc camera :pan-y -2000.0) (renderer calls)))))
    (is (= initial-count (count @calls)))
    (is (= [] (:entries (workspace-view/preview document "rig500" (assoc camera :pan-x Double/MAX_VALUE) (renderer calls)))))
    (is (= initial-count (count @calls)))
    (let [panned (workspace-view/preview document "rig500" (assoc camera :pan-x -1600.0) (renderer calls))]
      (is (coll/every? #(> (:index %) 500) (:entries panned))))))

;; Each scope terminates even an open-ended or invalidly targeted clip. A
;; following active skeleton cannot inherit its clipping or collide on IDs.
(deftest workspace-clipping-and-opacity-test
  (let [document (workspace ["active" "other"] "active")
        render-setup (fn render-setup [_document _sampled _evaluated _camera]
                       [{:id "clip" :clip-polygon [[0.0 0.0] [20.0 0.0] [20.0 20.0] [0.0 20.0]] :clip-end nil}
                        {:id "hidden" :points [[0.0 0.0] [10.0 0.0] [0.0 10.0]]
                         :uvs [[0.0 0.0] [1.0 0.0] [0.0 1.0]] :triangles [[0 1 2]] :opacity 0.0 :dark-tint nil}])
        result (workspace-view/preview document "active"
                                       {:enabled true :zoom 1.0 :pan-x 150.0 :pan-y 200.0 :width 1000.0 :height 500.0} render-setup)
        slots (:slots result)
        active {:id "hidden" :points [[-10.0 -10.0] [-5.0 -10.0] [-10.0 -5.0]]
                :uvs [[0.0 0.0] [1.0 0.0] [0.0 1.0]] :triangles [[0 1 2]]}
        clipped (clipping/clip-slots (conj slots active))]
    (is (= (:clip-end (first slots)) (:id (peek slots))))
    (is (:marker (peek slots)))
    (is (= 0.0 (:opacity (second slots))))
    (is (= [0.0 0.0 0.0] (:dark-tint (second slots))))
    (is (= active (peek clipped))))
  (let [document (workspace ["active" "other"] "active")
        result (workspace-view/preview document "active"
                                       {:enabled true :zoom 1.0 :pan-x 150.0 :pan-y 200.0 :width 1000.0 :height 500.0}
                                       (fn render-setup [_document _sampled _evaluated _camera]
                                         [{:id "clip" :clip-polygon [[0 0] [1 0] [0 1]] :clip-end "missing"}]))]
    (is (= (:id (peek (:slots result))) (:clip-end (first (:slots result)))))))

;; Disabled preview does not invoke the renderer. Invalid cameras and unknown
;; selected skeletons fail explicitly instead of drawing misleading content.
(deftest workspace-preview-validation-test
  (let [document (workspace ["one" "two"] "one") calls (atom []) render-setup (renderer calls)
        camera {:enabled true :zoom 1.0 :pan-x 0.0 :pan-y 0.0 :width 500.0 :height 500.0}]
    (is (= [] (:entries (workspace-view/preview document "one" (dissoc camera :enabled) render-setup))))
    (is (= [] @calls))
    (is (thrown? clojure.lang.ExceptionInfo (workspace-view/preview document "missing" camera render-setup)))
    (is (thrown? clojure.lang.ExceptionInfo (workspace-view/preview document "one" (assoc camera :zoom 0.0) render-setup)))
    (is (thrown? clojure.lang.ExceptionInfo (workspace-view/preview document "one" (assoc camera :pan-x Double/NaN) render-setup)))))

;; Labels stay above preview cells and within the canvas, so fitting a rig
;; cannot hide its switch target beneath the bottom animation/tool palette.
(deftest workspace-label-placement-test
  (let [document (workspace ["active" "other"] "active") calls (atom [])]
    (doseq [pan-y [0.0 250.0 800.0]
            :let [preview (workspace-view/preview document "active"
                                                  {:enabled true :zoom 1.0 :pan-x 150.0 :pan-y pan-y :width 1000.0 :height 468.0}
                                                  (renderer calls))]
            label (:labels preview)
            :let [[_ y _ height] (:bounds label)]]
      (is (<= 8.0 y))
      (is (<= (+ y height) 460.0)))
    (let [preview (workspace-view/preview document "active"
                                          {:enabled true :zoom 1.0 :pan-x 150.0 :pan-y 250.0 :width 1000.0 :height 468.0}
                                          (renderer calls))
          entry (first (:entries preview))]
      (is (< (second (:label-bounds entry)) (second (:bounds entry)))))))

;; Partially visible neighbors keep their entire compact name label on-screen;
;; top labels reserve the mode control area, with a small-window fallback.
(deftest workspace-label-horizontal-visibility-test
  (let [names ["left-preview-name" "active" "right-preview-name"]
        document (workspace names "active")
        result (workspace-view/preview document "active"
                                       {:enabled true :zoom 1.0 :pan-x 350.0 :pan-y 0.0 :width 800.0 :height 468.0}
                                       (renderer (atom [])))]
    (is (= names (mapv :name (:labels result))))
    (doseq [{:keys [name bounds]} (:labels result)
            :let [[x y width height] bounds]]
      (is (= (min 240.0 (max 80.0 (+ 24.0 (* 8.0 (count name))))) width))
      (is (<= 110.0 x))
      (is (<= (+ x width) 792.0))
      (is (= name (workspace-view/hit-label result (+ x (/ width 2.0)) (+ y (/ height 2.0)))))))
  (let [document (workspace ["a-long-name-that-cannot-fit-in-a-small-window" "other"] "a-long-name-that-cannot-fit-in-a-small-window")
        result (workspace-view/preview document (:name document)
                                       {:enabled true :zoom 1.0 :pan-x 60.0 :pan-y 0.0 :width 120.0 :height 90.0}
                                       (renderer (atom [])))
        [x y width height] (:bounds (first (:labels result)))]
    (is (= 1 (count (:labels result))))
    (is (= 104.0 width))
    (is (<= 8.0 x (+ x width) 112.0))
    (is (<= 8.0 y (+ y height) 82.0))))
