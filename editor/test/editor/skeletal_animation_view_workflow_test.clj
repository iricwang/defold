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

(ns editor.skeletal-animation-view-workflow-test
  (:require [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-clipping :as clipping]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-events :as events]
            [editor.skeletal-animation-mixing :as mixing]
            [editor.skeletal-animation-playback :as playback]
            [editor.skeletal-animation-project :as project]
            [editor.skeletal-animation-view :as view]
            [editor.skeletal-animation-workspace-view :as workspace-view]
            [util.coll :as coll])
  (:import [javafx.scene.canvas Canvas]))

(defn- event-rig []
  (-> (data/new-document "mixer")
      (data/add-animation {:id "overlay" :duration 1.0})
      (data/add-animation {:id "extra" :duration 1.0})
      (events/add-definition {:name "start"})
      (events/set-keyframe "default" {:name "start" :time 0.0 :int 1})
      (events/set-keyframe "overlay" {:name "start" :time 0.0 :int 2})
      (events/set-keyframe "extra" {:name "start" :time 0.0 :int 3})))

(defn- context [document]
  {:state (atom {:draft document :mode "animate" :animation "default" :time 0.0 :playback-time 0.0 :preview-time 0.0
                 :playing false :loop true :mix-duration 0.25 :mix-overlay "overlay" :mix-alpha 0.5
                 :zoom 1.0 :pan-x 300.0 :pan-y 250.0 :fps 60.0})
   :pose-cache (atom nil) :mix-cache (atom nil) :ghost-cache (atom nil) :workspace-cache (atom nil)})

(defn- check-message [_context text]
  (is (= "" text) (str "Unexpected editor command failure: " text)))

;; Entering mixed preview at zero retains both base and overlay notifications;
;; more paused commands append events, and the first Play consumes them once.
(deftest mixer-command-initial-events-test
  (let [document (event-rig) context (context document)]
    ;; Replace the UI/graph boundary; exercise actual view orchestration.
    #_{:clj-kondo/ignore [:discouraged-var]}
    (with-redefs-fn {#'view/message! check-message}
      (fn invoke-mixer-commands []
        (#'view/mixer-command! context :set)
        (is (= [1 2] (mapv :int (:mixer-pending-events @(:state context)))))
        (swap! (:state context) assoc :mixer-index 2 :mix-overlay "extra")
        (#'view/mixer-command! context :set)
        (is (= [1 2 3] (mapv :int (:mixer-pending-events @(:state context)))))
        (is (= ["default" "overlay" "extra"] (mapv :animation (get-in @(:state context) [:mixer-state :tracks]))))
        (let [paused (playback/advance-mixer @(:state context) document 10.0)
              playing (playback/advance-mixer (assoc paused :playing true :last-clock nil) document 11.0)
              continued (playback/advance-mixer playing document 11.1)]
          (is (= [] (:frame-events paused)))
          (is (= [1 2 3] (mapv :int (:frame-events playing))))
          (is (= [] (:mixer-pending-events playing) (:frame-events continued)))
          (is (= [1 2 3] (mapv :int (:event-log continued)))))))))

;; Starting an overlay at a positive cursor seeks only the base before the
;; command. Re-entering after Stop starts from the newly selected animation.
(deftest mixer-command-positive-time-test
  (let [document (event-rig) context (context document)]
    (swap! (:state context) assoc :time 0.6 :playback-time 0.6 :preview-time 0.6)
    ;; Replace the UI/graph boundary; exercise actual view orchestration.
    #_{:clj-kondo/ignore [:discouraged-var]}
    (with-redefs-fn {#'view/message! check-message}
      (fn invoke-mixer-commands []
        (#'view/mixer-command! context :set)
        (let [state @(:state context)]
          (is (= 0.6 (get-in state [:mixer-state :time])))
          (is (< (abs (- 0.6 (get-in state [:mixer-state :tracks 0 :track-time]))) 1e-8))
          (is (= 0.0 (get-in state [:mixer-state :tracks 1 :track-time])))
          (is (= [2] (mapv :int (:mixer-pending-events state)))))
        (swap! (:state context) assoc :mixer-enabled false :animation "extra" :time 0.0 :preview-time 0.0 :playback-time 0.0)
        (#'view/mixer-command! context :set)
        (is (= "extra" (get-in @(:state context) [:mixer-state :tracks 0 :animation])))
        (is (= [3 2] (mapv :int (:mixer-pending-events @(:state context)))))))))

;; The view accepts only a stateful sample belonging to the current document;
;; stale legacy flags cannot route rendering into paused-Physics sample mode.
(deftest mixed-preview-current-document-test
  (let [document (event-rig) context (context document)
        frame (mixing/advance-state document (mixing/create-state document {:tracks [{:animation "default"}]}) 0.0)]
    ;; Fail if rendering regresses to the obsolete stateless entry point.
    #_{:clj-kondo/ignore [:discouraged-var]}
    (with-redefs [mixing/sample (fn legacy-sample [& _] (throw (ex-info "Legacy mixer must not render" {})))]
      (swap! (:state context) assoc :mix-enabled true :mix-transition {:from "overlay"})
      (is (nil? (#'view/mixed-preview context document)))
      (swap! (:state context) assoc :mixer-enabled true :mixer-state (:state frame) :mixer-sample frame)
      (is (identical? frame (#'view/mixed-preview context document)))
      (is (nil? (#'view/mixed-preview context (data/update-bone document "root" {:x 10.0}))))
      (swap! (:state context) assoc :mode "setup")
      (is (nil? (#'view/mixed-preview context document))))))

;; Ghost passes terminate open-ended and reordered clips separately. Their
;; opacity multiplies source alpha, and playback does not recompute ghosts.
(deftest ghost-scope-and-cache-test
  (let [document (data/new-document "ghost") context (context document)
        calls (atom 0)
        visible {:id "image" :points [[0.0 0.0] [10.0 0.0] [0.0 10.0]]
                 :uvs [[0.0 0.0] [1.0 0.0] [0.0 1.0]] :triangles [[0 1 2]] :opacity 0.0}
        active (assoc visible :id "active" :points [[-20.0 -20.0] [-10.0 -20.0] [-20.0 -10.0]] :opacity 1.0)]
    (swap! (:state context) assoc :ghosting true :time 0.5)
    (doseq [end [nil "end"]]
      (reset! (:ghost-cache context) nil)
      ;; Replace the UI/graph boundary; exercise actual view orchestration.
      #_{:clj-kondo/ignore [:discouraged-var]}
      (with-redefs-fn {#'view/render-slots (fn render-ghost [_context _document _sampled _evaluated]
                                             (swap! calls inc)
                                             [{:id "end" :marker true}
                                              {:id "clip" :clip-polygon [[0.0 0.0] [20.0 0.0] [20.0 20.0] [0.0 20.0]] :clip-end end}
                                              visible])}
        (fn verify-ghost-pass []
          (let [ghosts (#'view/ghost-slots context document) count-before @calls
                cached (#'view/ghost-slots context document)
                images (filterv :points ghosts)]
            (is (identical? ghosts cached))
            (is (= count-before @calls))
            (is (= [0.0 0.0] (mapv :opacity images)))
            (is (= (count ghosts) (count (set (mapv :id ghosts)))))
            (is (= active (peek (clipping/clip-slots (conj ghosts active)))))
            (doseq [pass [:before :after]
                    :let [slots (filterv #(= pass (first (:id %))) ghosts)
                          clip (coll/first-where :clip-polygon slots)]]
              (is (= (:id (peek slots)) (:clip-end clip))))))))
    (let [before @calls]
      (swap! (:state context) assoc :playing true)
      (is (nil? (#'view/ghost-slots context document)))
      (is (= before @calls)))))

;; Workspace previews cache setup across active animation frames, invalidate
;; for camera/document changes, and keep labels usable without touching graph.
(deftest workspace-view-cache-test
  (let [document (project/assemble [{:name "one" :document (data/new-document "one")}
                                    {:name "two" :document (data/new-document "two")}] "one")
        context (assoc (context document) :canvas (Canvas. 1000.0 700.0))
        calls (atom [])]
    (swap! (:state context) assoc :show-workspace-peers true)
    ;; Replace the UI/graph boundary; exercise actual view orchestration.
    #_{:clj-kondo/ignore [:discouraged-var]}
    (with-redefs-fn {#'view/render-slots (fn render-peer [context document _sampled _evaluated]
                                           (swap! calls conj {:name (:name document) :state @(:state context)}) [])}
      (fn verify-workspace-cache []
        (let [initial (#'view/workspace-preview context document)]
          (is (= ["two"] (mapv :name @calls)))
          (is (= "setup" (get-in @calls [0 :state :mode])))
          (is (= 0.0 (get-in @calls [0 :state :time])))
          (swap! (:state context) assoc :playing true :time 0.7 :preview-time 0.7)
          (is (identical? initial (#'view/workspace-preview context document)))
          (is (= 1 (count @calls)))
          (let [{:keys [bounds]} (coll/first-where #(= "two" (:name %)) (:labels initial))
                [x y width height] bounds]
            (is (= "two" (workspace-view/hit-label initial (+ x (/ width 2.0)) (+ y (/ height 2.0))))))
          (swap! (:state context) update :pan-x + 10.0)
          (#'view/workspace-preview context document)
          (is (= 2 (count @calls)))
          (#'view/workspace-preview context (data/update-bone document "root" {:x 20.0}))
          (is (= 3 (count @calls)))
          (swap! (:state context) assoc :show-workspace-peers false)
          (is (= [] (:entries (#'view/workspace-preview context document))))
          (is (= 3 (count @calls))))))))

;; Switching by a workspace label uses the same mode reset as the selector,
;; preserving the peer toggle while stopping audio and invalidating caches.
(deftest workspace-switch-workflow-test
  (let [document (project/assemble [{:name "one" :document (data/new-document "one")}
                                    {:name "two" :document (data/new-document "two")}] "one")
        stopped (atom 0)
        context (assoc (context document) :audio {:stop! (fn stop-audio [] (swap! stopped inc))})]
    (swap! (:state context) assoc :show-workspace-peers true :mixer-enabled true :playing true :time 0.7)
    (doseq [cache [:pose-cache :mix-cache :ghost-cache :workspace-cache]] (reset! (get context cache) {:stale true}))
    ;; Replace the UI/graph boundary; exercise actual view orchestration.
    #_{:clj-kondo/ignore [:discouraged-var]}
    (with-redefs-fn {#'view/message! check-message
                     #'view/commit! (fn commit-document [context document]
                                      (is (= "setup" (:mode @(:state context))))
                                      (is (false? (:mixer-enabled @(:state context))))
                                      (swap! (:state context) assoc :draft document))}
      (fn switch-workspace [] (#'view/switch-workspace! context #(project/select % "two"))))
    (is (= "two" (get-in @(:state context) [:draft :name])))
    (is (= "setup" (:mode @(:state context))))
    (is (false? (:playing @(:state context))))
    (is (true? (:show-workspace-peers @(:state context))))
    (is (= 1 @stopped))
    (is (coll/every? nil? (mapv #(deref (get context %)) [:pose-cache :mix-cache :ghost-cache :workspace-cache])))
    (is (true? (:show-workspace-peers (project/view-settings (assoc document :editor-view {:show-workspace-peers true})))))))
