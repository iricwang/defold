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

(ns editor.skeletal-animation-view-review-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [dynamo.graph :as g]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-io :as skeletal-io]
            [editor.skeletal-animation-slots :as slots]
            [editor.skeletal-animation-view :as view]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [javafx.event ActionEvent]
           [javafx.scene Node]
           [javafx.scene.control TextField]))

(defn- fixture []
  (-> (data/new-document "rebuild")
      (data/add-slot {:id "mesh" :bone "root"
                      :attachment {:type "mesh" :path "/image.png" :width 10.0 :height 10.0
                                   :vertices [-5.0 -5.0 5.0 -5.0 -5.0 5.0]
                                   :uvs [0.0 1.0 1.0 1.0 0.0 0.0] :triangles [0 1 2]
                                   :weights [[{:bone "root" :weight 1.0 :x -5.0 :y -5.0}]
                                             [{:bone "root" :weight 1.0 :x 5.0 :y -5.0}]
                                             [{:bone "root" :weight 1.0 :x -5.0 :y 5.0}]]}})
      (data/set-deform-keyframe "default" "mesh" 0.0 [1.0 2.0 3.0 4.0 5.0 6.0] "linear")))

(defn- context [doc]
  {:state (atom {:draft doc :mode "setup" :selected-kind :slot :selected "mesh"
                 :vertices #{2} :vertex 2 :selected-keys #{{:type :deform :id "mesh" :time 0.0}}})
   :localization (constantly "setup-only")})

;; Generate/Reset/Trace must each commit one complete mesh edit, clear weights
;; and FFD together and reset stale selections. The worker is deterministic
;; here; no JavaFX window, event timer or real image file is involved.
(deftest rebuild-commit-boundary-test
  (doseq [[operation vertex-count] [[:generate 6] [:reset 4] [:trace 4]]]
    (let [doc (fixture) context (context doc) commits (atom [])]
      (with-redefs-fn
        {#'g/node-value (fn [_ _] (:draft @(:state context)))
         #'view/attempt! (fn [_ operation] (operation))
         #'view/background-io! (fn [_ work complete] (complete (work)))
         #'view/commit! (fn [_ updated] (swap! commits conj updated))
         #'skeletal-io/read-image-alpha (fn [_ _] {:width 2 :height 2 :alpha [255 255 255 255]})}
        #(#'view/rebuild-mesh! context operation))
      (let [updated (first @commits)]
        (is (= 1 (count @commits)))
        (is (= (* 2 vertex-count) (count (get-in updated [:slots 0 :attachment :vertices]))))
        (is (nil? (get-in updated [:slots 0 :attachment :weights])))
        (is (= [] (get-in updated [:animations 0 :deforms])))
        (is (= #{} (:vertices @(:state context))))
        (is (= #{} (:selected-keys @(:state context))))))))

;; A completed background rebuild cannot overwrite edits made since it began,
;; and animation mode must reject a rebuild before even starting worker IO.
(deftest rebuild-stale-document-and-mode-test
  (let [doc (fixture) context (context doc) commits (atom []) jobs (atom 0)
        changed (data/update-bone doc "root" {:x 17.0})]
    (with-redefs-fn
      {#'g/node-value (fn [_ _] (:draft @(:state context)))
         #'view/attempt! (fn [_ operation] (operation))
       #'view/background-io! (fn [_ work complete]
                              (swap! jobs inc)
                              (let [result (work)]
                                (swap! (:state context) assoc :draft changed)
                                (complete result)))
       #'view/commit! (fn [_ updated] (swap! commits conj updated))}
      #(do
         (is (thrown-with-msg? clojure.lang.ExceptionInfo #"document changed" (#'view/rebuild-mesh! context :generate)))
         (is (= [] @commits))
         (is (= changed (:draft @(:state context))))
         (swap! (:state context) assoc :mode "animate")
         (is (thrown-with-msg? clojure.lang.ExceptionInfo #"setup-only" (#'view/rebuild-mesh! context :reset)))
         (is (= 1 @jobs))))))

(defn- preview-fixture [weighted]
  (let [doc (cond-> (fixture)
              (not weighted) (update-in [:slots 0 :attachment] dissoc :weights))
        doc (-> doc
                (data/set-deform-keyframe "default" "mesh" 1.0 [900.0 500.0 700.0 400.0 500.0 300.0] "linear")
                (data/set-deform-keyframe "default" "mesh" 0.0 [0.0 0.0 0.0 0.0 0.0 0.0]
                                         {:type "bezier" :curve [0.1 0.8 0.9 0.2]}))
        source (json/read-str (data/export-spine-json doc))
        alternate (assoc (get-in source ["skins" 0 "attachments" "mesh" "mesh"]) "path" "alternate")
        source (-> source
                   (assoc-in ["skins" 0 "attachments" "mesh" "alternate"] alternate)
                   (assoc-in ["animations" "default" "slots" "mesh" "attachment"]
                             [{"time" 0.75 "name" "alternate"}]))]
    (data/import-spine-json (json/write-str source))))

(defn- preview-context [doc]
  (let [context (context doc)]
    (swap! (:state context) assoc :mode "animate" :animation "default" :time 0.233
           :tool "mesh" :interpolation "linear" :zoom 1.0 :pan-x 0.0 :pan-y 0.0)
    context))

(defn- preview-points [context doc]
  (let [sampled (slots/sample-slots doc "default" (:time @(:state context)))
        context (assoc context :sampled-slots sampled :sampled-deforms (:deforms sampled))]
    (#'view/attachment-points context doc (first (:slots sampled))
                             (:transforms (data/evaluate-pose doc "default" (:time @(:state context)))))))

(defn- near? [expected actual]
  (and (= (count expected) (count actual))
       (coll/every? #(< (abs (double %)) 1.0e-7) (mapv - expected actual))))

;; A field callback retained from the setup attachment cannot write after
;; playback switches attachments or selection changes, including a same-name bone.
(deftest stale-attachment-inspector-commit-test
  (let [doc (preview-fixture true)
        context (assoc (preview-context doc) :attachment-edit-slot "mesh")
        commits (atom [])
        invoked (atom 0)
        change #(do (swap! invoked inc) (data/update-slot % "mesh" {:attachment {:width 81.0}}))]
    (with-redefs-fn
      {#'g/node-value (fn [_ _] (:draft @(:state context)))
         #'view/attempt! (fn [_ operation] (operation))
       #'view/commit! (fn [_ updated] (swap! commits conj updated))}
      #(do
         (swap! (:state context) assoc :time 0.8)
         (is (thrown-with-msg? clojure.lang.ExceptionInfo #"setup-only" (#'view/change! context change)))
         (is (zero? @invoked))
         (is (= [] @commits))
         (swap! (:state context) assoc :time 0.233 :selected "another-slot")
         (is (thrown? clojure.lang.ExceptionInfo (#'view/change! context change)))
         (swap! (:state context) assoc :selected "mesh" :selected-kind :bone)
         (is (thrown? clojure.lang.ExceptionInfo (#'view/change! context change)))
         (is (zero? @invoked))
         (swap! (:state context) assoc :selected-kind :slot)
         (#'view/change! context change)
         (is (= 1 @invoked))
         (is (= 81.0 (get-in (first @commits) [:slots 0 :attachment :width])))))))

;; FFD edits begin from the exact geometry currently displayed, including
;; between-key Bezier values. Zero-motion and moved vertices cannot jump.
(deftest bezier-preview-drag-continuity-test
  (doseq [weighted [false true]]
    (let [doc (preview-fixture weighted)
          context (preview-context doc)
          before (preview-points context doc)
          unchanged (#'view/drag-vertices context doc #{0} [0.0 0.0] [0.0 0.0])
          moved (#'view/drag-vertices context doc #{0} [0.0 0.0] [7.0 -3.0])]
      (is (near? before (preview-points context unchanged)))
      (is (near? (mapv + [7.0 -3.0] (subvec before 0 2)) (subvec (preview-points context moved) 0 2)))
      (is (near? (subvec before 2) (subvec (preview-points context moved) 2))))))

;; Setting an FFD key at the current playhead captures the sampled picture,
;; rather than replacing it with a different interpolation implementation.
(deftest bezier-preview-key-continuity-test
  (doseq [weighted [false true]]
    (let [doc (preview-fixture weighted)
          context (preview-context doc)
          before (preview-points context doc)
          commits (atom [])]
      (with-redefs-fn
        {#'g/node-value (fn [_ _] (:draft @(:state context)))
         #'view/attempt! (fn [_ operation] (operation))
         #'view/commit! (fn [_ updated] (swap! commits conj updated))}
        #(#'view/set-key! context))
      (is (= 1 (count @commits)))
      (is (near? before (preview-points context (first @commits)))))))

;; Exercise the actual retained slot-name control, not only the shared guard:
;; renaming must not bypass read-only checks or steal selection after rejection.
(deftest stale-slot-name-control-test
  (ui/run-now
    (let [doc (-> (preview-fixture false)
                  (update-in [:slots 0 :attachment]
                             #(-> % (assoc :type "region") (dissoc :vertices :uvs :triangles :weights)))
                  (update :animations #(mapv (fn [animation] (assoc animation :deforms [])) %)))
          context (preview-context doc)
          ^TextField field (coll/first-where #(= "skeletal-slot-name" (.getId ^Node %))
                                             (#'view/entity-inspector context doc))
          commits (atom [])]
      (is (some? field))
      (.setText field "renamed")
      (with-redefs-fn
        {#'g/node-value (fn [_ _] (:draft @(:state context)))
         #'view/attempt! (fn [_ operation] (operation))
         #'view/commit! (fn [_ updated] (swap! commits conj updated))}
        #(let [activate (fn [] (.handle (.getOnAction field) (ActionEvent. field field)))]
           (swap! (:state context) assoc :time 0.8)
           (is (thrown? clojure.lang.ExceptionInfo (activate)))
           (is (= [] @commits))
           (is (= "mesh" (:selected @(:state context))))
           (swap! (:state context) assoc :time 0.233 :selected-kind :bone :selected "root")
           (is (thrown? clojure.lang.ExceptionInfo (activate)))
           (is (= :bone (:selected-kind @(:state context))))
           (is (= "root" (:selected @(:state context))))
           (swap! (:state context) assoc :selected-kind :slot :selected "mesh")
           (activate)
           (is (= 1 (count @commits)))
           (is (= "renamed" (get-in (first @commits) [:slots 0 :id])))
           (is (= "renamed" (:selected @(:state context)))))))))
