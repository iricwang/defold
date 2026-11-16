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

(ns editor.skeletal-animation-parity-edit-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [dynamo.graph :as g]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-inherit :as inherit]
            [editor.skeletal-animation-navigation :as navigation]
            [editor.skeletal-animation-mixing :as mixing]
            [editor.skeletal-animation-slots :as slots]
            [editor.skeletal-animation-slot-view :as slot-view]
            [editor.skeletal-animation-timeline :as timeline]
            [editor.skeletal-animation-tools :as tools]
            [editor.skeletal-animation-view :as view]
            [editor.ui :as ui]
            [internal.system :as system]
            [util.coll :as coll])
  (:import [javafx.event ActionEvent]
           [javafx.scene.control Button ColorPicker ComboBox Label]
           [javafx.scene.layout VBox]
           [javafx.scene.paint Color]))

(defn fixture []
  (data/import-spine-json
    (json/write-str
      {"skeleton" {"spine" "4.2.43"}
       "bones" [{"name" "root" "rotation" 35 "scaleX" 1.4 "scaleY" 0.7}
                {"name" "tail" "parent" "root" "x" 5 "y" 8 "rotation" 20}]
       "slots" [{"name" "05_tail" "bone" "tail" "attachment" "base"}
                {"name" "body" "bone" "root" "attachment" "body"}]
       "skins" [{"name" "default"
                 "attachments" {"05_tail" {"base" {"type" "mesh" "uvs" [0 0 1 0 0 1] "vertices" [0 0 10 0 0 10] "triangles" [0 1 2]}
                                           "alternate" {"type" "mesh" "uvs" [0 0 1 0 1 1 0 1] "vertices" [0 0 10 0 10 10 0 10] "triangles" [0 1 2 0 2 3]}
                                           "shared" {"type" "linkedmesh" "parent" "base"}
                                           "private" {"type" "linkedmesh" "parent" "base" "timelines" false}}
                                "body" {"body" {"width" 10 "height" 10}}}}]
       "animations" {"test" {"bones" {"tail" {"rotate" [{"time" 0 "value" 0} {"time" 2 "value" 10}]}}
                             "slots" {"05_tail" {"attachment" [{"time" 0.5 "name" "alternate"} {"time" 1 "name" "shared"} {"time" 1.5 "name" "private"}]}}}}})))

;; Switching a slot to a mesh with a different vertex count must author that
;; attachment, survive persistence, and leave the setup attachment untouched.
(deftest attachment-scoped-deform-authoring-test
  (let [doc (fixture)
        base (slots/set-deform-keyframe doc "test" "05_tail" 0.0 [1 2 3 4 5 6] "linear")
        edited (slots/set-deform-keyframe base "test" "05_tail" 0.5 [8 7 6 5 4 3 2 1] "linear")
        sample #(get-in (slots/sample-slots % "test" 0.5) [:deforms "05_tail"])
        target (pr-str ["default" "05_tail" "alternate"])
        ref {:type :attachment-deform :id target :time 0.5}
        moved (timeline/move-keys edited "test" [ref] 0.1 {})
        pasted (timeline/paste-keys moved "test" (timeline/copy-keys edited "test" [ref]) 0.8 {})]
    (is (= [8 7 6 5 4 3 2 1] (sample edited)))
    (is (= [1 2 3 4 5 6] (get-in (slots/sample-slots edited "test" 0) [:deforms "05_tail"])))
    (is (= (:slots doc) (:slots edited)))
    (is (= (sample edited) (sample (data/read-json (data/write-json edited)))))
    (is (= (sample edited) (sample (data/import-spine-json (data/export-spine-json edited)))))
    (is (nil? (sample moved)))
    (is (contains? (set (timeline/all-key-refs pasted "test")) (assoc ref :time 0.8)))
    (is (= #{0.6 0.8} (set (mapv :time (:keys (slots/deform-track pasted "test" "05_tail" 0.5))))))
    (is (thrown? clojure.lang.ExceptionInfo (slots/set-deform-keyframe edited "test" "05_tail" 0.5 [1 2] "linear")))
    (let [deleted (timeline/delete-keys edited "test" [ref])]
      (is (nil? (sample deleted)))
      (is (= [1 2 3 4 5 6] (get-in (slots/sample-slots deleted "test" 0) [:deforms "05_tail"]))))
    (is (coll/any? #(= target (:deform-target %)) (navigation/all-tracks edited "test")))))

;; Linked meshes share their parent's timeline only when timelines is enabled.
(deftest linked-deform-ownership-authoring-test
  (let [doc (-> (fixture)
                (slots/set-deform-keyframe "test" "05_tail" 1.0 [9 9 9 9 9 9] "linear")
                (slots/set-deform-keyframe "test" "05_tail" 1.5 [3 3 3 3 3 3] "linear"))
        sample #(get-in (slots/sample-slots doc "test" %) [:deforms "05_tail"])]
    (is (nil? (:deform-target (slots/deform-track doc "test" "05_tail" 1.0))))
    (is (= [9 9 9 9 9 9] (sample 1.0)))
    (is (= [3 3 3 3 3 3] (sample 1.5)))
    (is (= (pr-str ["default" "05_tail" "private"]) (:deform-target (slots/deform-track doc "test" "05_tail" 1.5))))
    (is (= "base" (get-in doc [:animations 0 :deforms 0 :attachment])))))

;; Explicit FFD lanes must edit their owner even when the playhead displays a
;; different mesh; changing geometry invalidates copied offsets.
(deftest hidden-deform-track-and-clipboard-test
  (let [doc (slots/set-deform-keyframe (fixture) "test" "05_tail" 0.5 [1 1 1 1 1 1 1 1] "linear")
        track (slots/deform-track doc "test" "05_tail" 0.5)
        edited (slots/set-deform-track-keyframe doc "test" track 0.0 [2 2 2 2 2 2 2 2] "linear")
        reversed (timeline/reverse-keys edited "test" (timeline/all-key-refs edited "test") 0 2 {})]
    (is (= [0.0 0.5] (mapv :time (:keys (slots/deform-track edited "test" "05_tail" 0.5)))))
    (is (= (:slots doc) (:slots reversed)))
    (is (= 8 (count (get-in (json/read-str (data/export-spine-json edited))
                            ["animations" "test" "attachments" "default" "05_tail" "alternate" "deform" 0 "vertices"]))))))

;; Inheritance is discrete, never interpolated, and must be editable through
;; the same timeline copy/move/delete pipeline as ordinary bone keys.
(deftest animated-inheritance-authoring-test
  (let [doc (reduce (fn [doc [index mode]] (slots/set-inherit-keyframe doc "test" "tail" (* 0.25 (inc index)) mode))
                    (fixture) (mapv vector (range 5) inherit/modes))
        sample (fn sample [doc time] (:inherit (coll/first-where #(= "tail" (:id %)) (data/sample-pose doc "test" time))))
        refs (filterv #(= :inherit (:type %)) (timeline/all-key-refs doc "test"))
        moved (timeline/move-keys doc "test" refs 0.1 {})
        removed (timeline/delete-keys doc "test" refs)]
    (is (= "normal" (sample doc 0.0)))
    (doseq [[index mode] (mapv vector (range 5) inherit/modes)]
      (is (= mode (sample doc (* 0.25 (inc index)))))
      (is (= mode (sample moved (+ 0.100001 (* 0.25 (inc index)))))))
    (is (= "normal" (sample removed 2.0)))
    (is (= (:bones (fixture)) (:bones doc)))
    (is (= (data/sample-pose doc "test" 1.1)
           (data/sample-pose (data/import-spine-json (data/export-spine-json doc)) "test" 1.1)))
    (is (= 5 (count refs)))
    (is (thrown? clojure.lang.ExceptionInfo (slots/set-inherit-keyframe doc "test" "missing" 0 "normal")))
    (is (thrown? clojure.lang.ExceptionInfo (slots/set-inherit-keyframe doc "test" "tail" 0 "invalid")))))

(g/defnode EditFixture (property document g/Any))

(defn- context [_doc node]
  {:resource-node node :status (ui/run-now (Label.)) :localization (constantly "Invalid operation")
   :state (atom {:mode "animate" :animation "test" :time 0.5 :fps 60 :interpolation "linear"
                 :selected-kind :slot :selected "05_tail" :auto-key false})})

;; Real graph transactions verify slot preview, manual key, undo/redo, and
;; Auto Key share one state model and do not silently discard pending edits.
(deftest slot-controls-preview-key-and-undo-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (let [doc (fixture) node (g/make-node! EditFixture :document doc) context (context doc node)
          state (:state context)]
      (ui/run-now
        (let [pane (VBox.)]
          (.addAll (.getChildren pane) ^java.util.Collection
                   (slot-view/inspector {:document doc :animation-id "test" :slot-id "05_tail" :time 0.5
                                         :tr identity
                                         :pose-change! (fn preview [f] (#'view/pose-change! context f))
                                         :change! (fn key [f] (#'view/animation-key-change! context f))}))
          (.setValue ^ComboBox (.lookup pane "#skeletal-slot-key-attachment") "base")
          (is (= doc (g/node-value node :document)))
          (is (some? (:pending-pose @state)))
          (let [^ColorPicker picker (.lookup pane "#skeletal-slot-key-color")]
            (.setValue picker Color/RED)
            (.handle (.getOnAction picker) (ActionEvent. picker picker)))
          (.fire ^Button (.lookup pane "#skeletal-slot-key-color-add"))
          (let [committed (g/node-value node :document)
                visible (first (:slots (slots/sample-slots committed "test" 0.5)))]
            (is (= "base" (get-in visible [:attachment :name])))
            (is (= [1.0 0.0 0.0 1.0] (:color visible)))
            (is (nil? (:pending-pose @state)))
            (g/undo! :undo/global)
            (is (= doc (g/node-value node :document)))
            (g/redo! :undo/global)
            (is (= committed (g/node-value node :document))))
          (swap! state assoc :auto-key true)
          (.setValue ^ComboBox (.lookup pane "#skeletal-slot-key-attachment") "alternate")
          (is (= "alternate" (get-in (first (:slots (slots/sample-slots (g/node-value node :document) "test" 0.5))) [:attachment :name])))
          (is (nil? (:pending-pose @state))))))))

;; Official 4.2.120 InheritTimeline applies even at zero alpha, never adds
;; numeric values, and resets to setup immediately when mixing out.
(deftest inherit-animation-state-boundaries-test
  (let [doc (-> (fixture)
                (data/add-animation {:id "base" :duration 2.0})
                (data/add-animation {:id "overlay" :duration 2.0})
                (slots/set-inherit-keyframe "base" "tail" 0.0 "noScale")
                (slots/set-inherit-keyframe "overlay" "tail" 0.5 "onlyTranslation"))
        mode (fn mode [result] (:inherit (coll/first-where #(= "tail" (:id %)) (get-in result [:pose :bones]))))]
    (doseq [alpha [0.0 0.5 1.0] blend [:replace :add]]
      (let [state (mixing/create-state doc {:tracks [{:animation "base"} {:animation "overlay" :alpha alpha :blend blend}]})
            before (mixing/advance-state doc state 0.1)
            after (mixing/advance-state doc (:state before) 0.4)]
        (is (= "noScale" (mode before)))
        (is (= "onlyTranslation" (mode after)))))
    (let [state (mixing/create-state doc {:default-mix 1.0 :tracks [{:animation "base"}]})
          before (mixing/advance-state doc state 0.1)
          state (mixing/set-track doc (:state before) 0 {:animation "test"})
          after (mixing/advance-state doc state 0.0)]
      (is (= "noScale" (mode before)))
      (is (= "normal" (mode after))))))

;; Inspector callbacks retained across selection/mode changes must not write
;; setup or animation data to the wrong object, even for equal-size meshes.
(deftest stale-inspector-and-pending-cursor-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (let [doc (fixture) node (g/make-node! EditFixture :document doc)
          context (context doc node) state (:state context)
          captured (assoc context :inspector-selection (select-keys @state [:mode :animation :selected-kind :selected])
                          :attachment-edit-key ["default" "alternate"])
          messages (:status context)]
      (ui/run-now

        (#'view/pose-change! captured #(slots/set-deform-keyframe % "test" "05_tail" 0.5 [1 1 1 1 1 1 1 1] "linear"))
        (is (some? (:pending-pose @state)))
        (swap! state tools/undo-pose false)
        (is (nil? (:pending-pose @state)))
        (swap! state tools/undo-pose true)
        (is (some? (:pending-pose @state)))
        (swap! state assoc :time 1.0)
        (is (nil? (tools/pending-document @state doc)))
        (#'view/pose-change! captured #(slots/set-deform-keyframe % "test" "05_tail" 1.0 [2 2 2 2 2 2] "linear"))
        (is (not= "" (.getText ^Label messages)))
        (swap! state assoc :mode "setup")
        (#'view/pose-change! captured #(data/update-bone % "tail" {:x 999.0}))
        (is (= doc (g/node-value node :document)))
        (is (not= "" (.getText ^Label messages)))))))
