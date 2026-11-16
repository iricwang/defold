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

(ns editor.skeletal-animation-workspace-view
  "Read-only setup previews for other skeletons sharing an authoring project."
  (:require [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-geometry :as geometry]
            [editor.skeletal-animation-project :as project]
            [editor.skeletal-animation-slots :as slots]
            [util.coll :as coll]))

(defn- intersects? [[x y width height] viewport-width viewport-height]
  (and (< x viewport-width) (pos? (+ x width))
       (< y viewport-height) (pos? (+ y height))))

(defn- camera-point [{:keys [zoom pan-x pan-y]} [x y]]
  [(+ pan-x (* zoom x)) (- pan-y (* zoom y))])

(defn- preview-entry [entry camera render-setup]
  (let [{:keys [document name bounds]} entry
        sampled (slots/sample-slots document nil 0.0)
        evaluated (data/evaluate-pose-with-slots document nil 0.0 slots/sample-slots)
        rendered (vec (render-setup document sampled evaluated camera))
        active (:active-bone-ids evaluated)
        bones (coll/into-> (:bones evaluated) []
                (filter #(or (nil? active) (contains? active (:id %))))
                (map (fn [bone]
                       (let [matrix (get-in evaluated [:transforms (:id bone)])]
                         {:id (:id bone)
                          :from (camera-point camera (geometry/transform-point matrix [0.0 0.0]))
                          :to (camera-point camera (geometry/transform-point matrix [(:length bone 0.0) 0.0]))}))))
        points (coll/into-> bones (coll/into-> rendered [] (mapcat #(into (vec (:points %)) (:clip-polygon %))))
                 (mapcat #(vector (:from %) (:to %))))
        [left top right bottom] (reduce (fn [[left top right bottom] [x y]]
                                          [(min left x) (min top y) (max right x) (max bottom y)])
                                        (if (pos? (count points))
                                          [(ffirst points) (second (first points)) (ffirst points) (second (first points))]
                                          [0.0 0.0 1.0 1.0]) points)
        [x y width height] bounds
        scale (min (/ (- width 24.0) (max 1.0 (- right left)))
                   (/ (- height 24.0) (max 1.0 (- bottom top))))
        center-x (/ (+ left right) 2.0) center-y (/ (+ top bottom) 2.0)
        place-point (fn place-point [[px py]]
                      [(+ x (/ width 2.0) (* scale (- px center-x)))
                       (+ y (/ height 2.0) (* scale (- py center-y)))])
        prefix [:workspace name]
        terminal [prefix :end]
        positions (coll/into-> (range (count rendered)) {} (map (fn [index] [(:id (nth rendered index)) index])))
        rendered (mapv (fn [index slot]
                         (let [end-position (get positions (:clip-end slot))
                               end (if (and end-position (> end-position index)
                                            (not (:clip-polygon (nth rendered end-position))))
                                     [prefix (:clip-end slot)] terminal)]
                           (cond-> (assoc slot :id [prefix (:id slot)])
                             (:clip-polygon slot) (assoc :clip-end end)
                             (:points slot) (update :points #(mapv place-point %))
                             (:clip-polygon slot) (update :clip-polygon #(mapv place-point %))
                             (:points slot) (assoc :opacity (* 0.42 (:opacity slot 1.0))
                                                   :tint (mapv #(* 0.65 %) (:tint slot [1.0 1.0 1.0]))
                                                   :dark-tint (mapv #(* 0.65 %) (or (:dark-tint slot) [0.0 0.0 0.0]))))))
                       (range (count rendered)) rendered)
        bones (mapv (fn [bone]
                      (-> bone
                          (assoc :id [prefix (:id bone)] :name name :opacity 0.42)
                          (update :from place-point)
                          (update :to place-point))) bones)]
    (assoc entry :id-prefix prefix :camera camera :editable false
           :slots (conj rendered {:id terminal :marker true}) :bone-lines bones
           :warnings (:warnings sampled))))

(defn preview
  "Return {:slots :bone-lines :labels :entries} for visible neighboring setup
  skeletons; the selected skeleton is never sampled or rendered here. camera
  accepts :enabled (false by default), :zoom, :pan-x/:pan-y and :width/:height.
  render-setup is called as [document sampled evaluated camera] and returns
  the existing screen-space render-slots format. Cache the result externally
  by document, selected name and camera; active playback need not invalidate it.
  Labels use [x y width height] bounds. Small zoom keeps preview cells readable
  and bounds the number of setup poses sampled even for 1000-skeleton projects."
  [document selected-name {:keys [enabled zoom pan-x pan-y width height]
                           :or {enabled false zoom 1.0 pan-x 0.0 pan-y 0.0 width 0.0 height 0.0} :as camera} render-setup]
  (when-not (and (boolean? enabled) (coll/every? geometry/finite-number? [zoom pan-x pan-y width height])
                 (< 0.0 zoom 101.0) (not (neg? width)) (not (neg? height)) (ifn? render-setup))
    (throw (ex-info "Workspace preview needs a valid camera and render callback." {})))
  (if-not (and enabled (pos? width) (pos? height))
    {:slots [] :bone-lines [] :labels [] :entries [] :skeleton-count 0}
    (let [documents (project/documents document)
          selected (or selected-name (:workspace-active document) (:name document))
          active-index (reduce-kv (fn [_ index item] (when (= selected (:name item)) (reduced index))) nil documents)
          _ (when-not active-index (throw (ex-info "Unknown active workspace skeleton." {:name selected})))
          cell-width (max 144.0 (* 360.0 zoom))
          cell-height (max 144.0 (* 360.0 zoom))
          spacing (+ cell-width (max 24.0 (* 64.0 zoom)))
          start (long (max 0.0 (min (count documents) (+ active-index (Math/floor (/ (- (- (/ cell-width 2.0)) pan-x) spacing))))))
          end (long (max 0.0 (min (count documents) (inc (+ active-index (Math/ceil (/ (- (+ width (/ cell-width 2.0)) pan-x) spacing)))))))
          entries (coll/into-> (range start end) []
                    (keep (fn [index]
                            (let [offset (* (- index active-index) spacing)
                                  x (- (+ pan-x offset) (/ cell-width 2.0))
                                  y (- pan-y (/ cell-height 2.0))
                                  bounds [x y cell-width cell-height]
                                  margin (min 8.0 (/ height 4.0))
                                  label-height (min 24.0 (- height (* 2.0 margin)))
                                  label-y (max margin (min (- height margin label-height) (- y label-height 6.0)))
                                  horizontal-margin (min 8.0 (/ width 4.0))
                                  label-width (min (- width (* 2.0 horizontal-margin)) 240.0
                                                   (max 80.0 (+ 24.0 (* 8.0 (count (:name (nth documents index)))))))
                                  minimum-x (if (and (< label-y 48.0) (>= width (+ 110.0 label-width horizontal-margin)))
                                              110.0 horizontal-margin)
                                  label-x (max minimum-x (min (- width horizontal-margin label-width)
                                                              (- (+ x (/ cell-width 2.0)) (/ label-width 2.0))))
                                  label-bounds [label-x label-y label-width label-height]
                                  horizontal-visible (and (< x width) (pos? (+ x cell-width)))
                                  body-visible (intersects? bounds width height)]
                              (when (and horizontal-visible (or body-visible (intersects? label-bounds width height)))
                                (assoc (nth documents index) :index index :bounds bounds :label-bounds label-bounds
                                       :body-visible body-visible :screen-offset [offset 0.0] :active (= index active-index)))))))
          labels (mapv (fn [entry] {:name (:name entry) :bounds (:label-bounds entry) :active (:active entry)}) entries)
          previews (coll/into-> entries []
                     (filter #(and (not (:active %)) (:body-visible %)))
                     (map (fn [entry]
                            (preview-entry entry (assoc camera :zoom zoom :pan-x (+ pan-x (first (:screen-offset entry))) :pan-y pan-y)
                                           render-setup))))]
      {:entries previews :labels labels :skeleton-count (count documents)
       :slots (coll/into-> previews [] (mapcat :slots))
       :bone-lines (coll/into-> previews [] (mapcat :bone-lines))})))

(defn hit-label
  "Return a skeleton name at a screen point, or nil. Only labels are pickable;
  neighboring meshes and bones never become editable through this helper."
  [preview x y]
  (when-not (coll/every? geometry/finite-number? [x y])
    (throw (ex-info "Workspace label coordinates must be finite." {})))
  (:name (coll/first-where (fn [{[left top width height] :bounds}]
                             (and (<= left x (+ left width)) (<= top y (+ top height)))) (:labels preview))))
