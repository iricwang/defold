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

(ns editor.skeletal-animation-toolbar-test
  (:require [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-toolbar :as toolbar]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [javafx.scene Node Scene]
           [javafx.scene.control Button ComboBox Label TextField ToggleButton ToolBar]
           [javafx.scene.layout VBox]))

;; Regrouping must preserve callbacks, selection gating, and playback state,
;; with two compact rows at normal width instead of unused wrapping space.
(deftest compact-toolbar-interaction-test
  (ui/run-now
    (let [calls (atom [])
          graph-visible (atom true)
          state (atom {:selected-keys [] :loop true :auto-key false :playing false})
          controls (into {} (mapv (fn [id]
                                    [id (doto (Button. id)
                                          (.setId (str "skeletal-" id))
                                          (.setOnAction (ui/event-handler _ (swap! calls conj id))))])
                                  ["play" "rewind" "previous-key" "previous-frame" "next-frame" "next-key" "copy-keys" "paste-keys" "delete-key"]))
          curves (mapv (fn [id] (doto (ToggleButton. id)
                                  (.setId (str "skeletal-curve-" id))
                                  (.setOnAction (ui/event-handler _ (swap! calls conj id)))))
                       ["stepped" "linear" "bezier" "smooth" "flat" "ease-in" "ease-out" "frame" "auto"])
          playback (ToolBar. (into-array Node (into [] (coll/vals controls))))
          strip (ToolBar. (into-array Node curves))
          root (VBox. (into-array Node [playback strip]))
          _scene (Scene. root 1100.0 200.0)
          result (toolbar/install! {:root root :playback playback :curve-strip strip
                                    :state state :tr #(get {"toolbar-shift" "移位" "toolbar-offset" "偏移" "toolbar-frame" "帧" "toolbar-auto-frame" "自动" "toolbar-store" "存储"} % %) :graph-visible? (fn graph-visible? [] @graph-visible)
                                    :animation-choice (ComboBox.) :current-frame (TextField.) :time-label (Label.)
                                    :actions {:cut (fn cut [] (swap! calls conj "cut"))
                                              :key (fn key [] (swap! calls conj "key"))
                                              :edited (fn edited [] (swap! calls conj "edited"))
                                              :end (fn end [] (swap! calls conj "end"))}})]
      (.applyCss root) (.layout root) (.layout root)
      (is (= 2 (count (.getChildren (:rows result)))))
      (doseq [id ["skeletal-toolbar-refresh" "skeletal-copy-keys" "skeletal-toolbar-cut"
                  "skeletal-delete-key" "skeletal-paste-keys" "skeletal-curve-stepped"
                  "skeletal-curve-linear" "skeletal-curve-bezier" "skeletal-toolbar-swap"]]
        (is (some? (.getGraphic ^javafx.scene.control.ButtonBase (.lookup root (str "#" id)))) id))
      (is (< (.getHeight playback) 75.0))
      (is (not (.isManaged strip)))
      (is (zero? (count (.getItems strip))))
      (is (.isDisabled ^Button (get controls "copy-keys")))
      (is (.isDisabled ^Button (get controls "paste-keys")))
      (swap! state assoc :selected-keys [[:bone "tail" "rotation" 0]] :key-clipboard {:keys [1]} :playing true)
      ((:refresh! result))
      (is (not (.isDisabled ^Button (get controls "copy-keys"))))
      (is (= "pause" (.getAccessibleText ^Button (get controls "play"))))
      (doseq [id ["copy-keys" "paste-keys" "delete-key" "next-frame" "previous-key"]]
        (.fire ^Button (get controls id)))
      (.fire ^ToggleButton (get curves 3))
      (.fire ^Button (.lookup root "#skeletal-toolbar-cut"))
      (.fire ^ToggleButton (:loop result))
      (.fire ^ToggleButton (:auto-key result))
      (is (= ["copy-keys" "paste-keys" "delete-key" "next-frame" "previous-key" "smooth" "cut"] @calls))
      (is (false? (:loop @state)))
      (is (true? (:auto-key @state)))
      (swap! state assoc :selected-keys [] :auto-key false :loop true)
      ((:refresh! result))
      (is (.isDisabled ^Button (.lookup root "#skeletal-toolbar-cut")))
      (is (not (.isSelected ^ToggleButton (:auto-key result))))
      (is (.isSelected ^ToggleButton (:loop result)))
      (reset! graph-visible false)
      ((:refresh! result))
      (is (.isDisabled ^ToggleButton (get curves 7)))
      (is (.isDisabled ^ToggleButton (get curves 8))))))

;; Ruler labels must stay separated when zooming out or viewing large frame
;; numbers; the previous floor-based interval drew 87 labels in 600 pixels.
(deftest adaptive-ruler-spacing-test
  (is (= 10.0 (#'toolbar/ruler-step 600.0 0.0 1.45 60.0)))
  (doseq [width [160.0 600.0 1200.0]
          start [0.0 10000.0 -10000.0]
          span [0.1 1.45 60.0 600.0]]
    (let [step (#'toolbar/ruler-step width start span 60.0)
          pixels (* step (/ (- width 80.0) (* 60.0 span)))
          largest (long (Math/ceil (max (abs (* start 60.0)) (abs (* (+ start span) 60.0)))))
          required (max 48.0 (+ 14.0 (* 7.0 (count (str largest)))))]
      (is (>= step 1.0))
      (is (>= (+ pixels 1.0e-7) required)))))
