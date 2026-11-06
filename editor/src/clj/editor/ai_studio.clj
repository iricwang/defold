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

(ns editor.ai-studio
  "Shared native visual components for the AI Canvas studio."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.ui :as ui])
  (:import [javafx.geometry Pos]
           [javafx.scene Node]
           [javafx.scene.control Button ContentDisplay Label Tooltip]
           [javafx.scene.layout HBox StackPane]
           [javafx.scene.shape SVGPath]))

(set! *warn-on-reflection* true)

(defn icon
  ^StackPane [kind]
  (let [shape (doto (SVGPath.)
                (.setContent (get {:home "M3 3 H9 V9 H3 Z M15 3 H21 V9 H15 Z M3 15 H9 V21 H3 Z M15 15 H21 V21 H15 Z"
                                   :flame "M12 1 C13 8 22 10 21 16 C21 22 16 24 12 23 C16 19 12 17 13 12 C5 18 8 21 10 23 C0 20 2 13 6 8 C8 6 8 3 8 2 C10 5 10 7 10 8 Z"
                                   :tasks "M6 3 H18 V21 H6 Z M9 2 H15 V6 H9 Z M9 11 L11 13 L16 9 M9 17 H15"
                                   :stages "M3 5 L12 1 L21 5 V20 H3 Z M7 9 H17 M7 13 H17"
                                   :assets "M12 2 L22 7 V17 L12 22 L2 17 V7 Z M2 7 L12 12 L22 7 M12 12 V22"
                                   :people "M8 2 A4 4 0 1 1 7.99 2 M2 22 V18 C2 12 14 12 14 18 V22 M18 5 A3 3 0 1 1 17.99 5 M17 15 C23 14 23 19 23 22"
                                   :resources "M2 5 H9 L12 8 H22 V21 H2 Z"
                                   :settings "M4 3 H20 V21 H4 Z M8 7 H16 M8 12 H16 M8 17 H16 M11 5 V9 M15 10 V14 M10 15 V19"
                                   :search "M10 2 A8 8 0 1 1 9.99 2 M16 16 L23 23"
                                   :bell "M5 16 V9 C5 0 19 0 19 9 V16 L22 19 H2 Z M9 22 H15"
                                   :play "M6 3 L21 12 L6 21 Z"
                                   :warning "M12 2 L23 22 H1 Z M12 8 V14 M12 18 V19"
                                   :target "M19 11 A8 8 0 1 1 13 4 M15 8 A4 4 0 1 0 16 13 M12 12 L21 3 M16 3 H21 V8"
                                   :arrow "M4 12 H21 M16 7 L21 12 L16 17"
                                   :back "M20 12 H3 M8 7 L3 12 L8 17"
                                   :open "M5 19 L19 5 M7 5 H19 V17"
                                   :chevron "M9 5 L16 12 L9 19"
                                   :down "M5 9 L12 16 L19 9"
                                   :list "M3 5 H6 M10 5 H22 M3 12 H6 M10 12 H22 M3 19 H6 M10 19 H22"
                                   :document "M4 2 H15 L21 8 V22 H4 Z M15 2 V8 H21 M8 12 H17 M8 17 H17"
                                   :empty "M4 8 L8 3 H16 L20 8 L23 18 V22 H1 V18 Z M4 8 V15 H9 L11 18 H14 L16 15 H20 V8"
                                   :refresh "M21 10 A9 9 0 1 0 21 17 M21 3 V10 H14"} kind "M4 4 H20 V20 H4 Z"))
                (-> .getStyleClass (.addAll ^"[Ljava.lang.Object;" (to-array ["studio-glyph" (str "studio-glyph-" (name kind))]))))]
    (doto (StackPane. (into-array Node [shape]))
      (.setMinSize 24.0 24.0) (.setPrefSize 24.0 24.0) (.setMaxSize 24.0 24.0)
      (-> .getStyleClass (.add "studio-icon")))))

(defn compact-icon
  ^StackPane [kind]
  (let [graphic (icon kind)
        width (get {:arrow 10.0 :chevron 10.0 :back 12.0 :open 12.0} kind 16.0)]
    (doseq [^SVGPath shape (.getChildren graphic)]
      (when (= :arrow kind) (.setContent shape "M8 5 L15 12 L8 19"))
      (.setScaleX shape (/ 2.0 3.0))
      (.setScaleY shape (/ 2.0 3.0)))
    (.setMinSize graphic width 16.0)
    (.setPrefSize graphic width 16.0)
    (.setMaxSize graphic width 16.0)
    graphic))

(defn decorate-navigation!
  "Use vector icons for navigation markers, including existing live controls."
  ^Button [^Button button]
  (when-let [marker (re-find #"^[←↗›↻]$|^[←↗]|[→↗]$" (.getText button))]
    (let [text (string/trim (string/replace (.getText button) #"^[←↗›↻]|[→↗]$" ""))
          kind (get {"←" :back "↗" :open "›" :chevron "↻" :refresh "→" :arrow} marker)
          description (if (string/blank? text) (if (= :refresh kind) "刷新" "查看详情") text)]
      (.setText button text)
      (.setGraphic button (compact-icon kind))
      (.setContentDisplay button (if (= :back kind) ContentDisplay/LEFT ContentDisplay/RIGHT))
      (.setGraphicTextGap button 3.0)
      (.setAccessibleText button description)
      (when (string/blank? text) (.setTooltip button (Tooltip. description)))
      (.add (.getStyleClass button) "studio-navigation-action")))
  button)

(defn brand
  ^HBox []
  (let [outer (doto (SVGPath.) (.setContent "M18 0 C19 11 31 15 31 25 C31 35 23 41 17 42 C20 34 12 31 13 23 C4 29 7 38 13 42 C-2 38 -5 25 3 15 C8 10 10 7 11 2 C14 8 14 13 14 15 C18 11 20 6 18 0 Z") (.setStyle "-fx-fill: #f79243;"))
        inner (doto (SVGPath.) (.setContent "M19 17 C26 25 26 33 19 39 C20 31 13 29 19 17 Z") (.setStyle "-fx-fill: #ffcc70;"))
        mark (doto (StackPane. (into-array Node [outer inner])) (.setMinSize 42.0 46.0) (.setMaxSize 42.0 46.0))
        title (doto (Label. "HERMES") (-> .getStyleClass (.add "studio-brand-title")))]
    (doto (HBox. 10.0 (into-array Node [mark title]))
      (.setAlignment Pos/CENTER_LEFT)
      (-> .getStyleClass (.add "studio-brand")))))

(defn button
  ^Button [title id kind f]
  (doto (Button. title)
    (.setId id) (.setGraphic (icon kind))
    (.setOnAction (ui/event-handler _ (f)))
    (-> .getStyleClass (.add "studio-nav-button"))))

(defn project-name [root]
  (let [file (io/file root "game.project")]
    (or (when (.isFile file) (second (re-find #"(?m)^title\s*=\s*(.+)$" (slurp file :encoding "UTF-8"))))
        (.getName (io/file root)))))
