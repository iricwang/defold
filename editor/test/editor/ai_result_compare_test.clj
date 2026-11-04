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

(ns editor.ai-result-compare-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [editor.ai-result-compare :as compare]
            [editor.ui :as ui])
  (:import [java.nio.file Files]
           [javafx.scene Scene]
           [javafx.scene.control Button CheckBox ComboBox Label ListView ScrollPane]
           [javafx.scene.layout AnchorPane]
           [javafx.stage Stage]))

;; Pair current bodies and repeated names; historical drafts stay separate.
(deftest resource-pairing-test
  (let [a {:kind :result :candidates [{:text "当前稿" :files [{:path "/config.json"} {:path "/config.json"}]}]
           :generated-candidate {:text "原稿"}}
        b {:kind :result :candidates [{:text "另一稿" :files [{:path "/config.json"} {:path "/right.txt"}]}]}
        pairs (compare/resource-pairs "." a b)]
    (is (= [[:body 0] ["config.json" 0] ["config.json" 1] ["right.txt" 0]] (mapv :key pairs)))
    (is (= "当前稿" (get-in pairs [0 :left :text])))
    (is (nil? (get-in pairs [2 :right])))
    (is (nil? (get-in pairs [3 :left])))))

;; Diff alignment keeps changed lines and insertions while normalizing CRLF.
(deftest aligned-text-differences-test
  (let [result (compare/line-differences "开头\r\n左侧\r\n结尾\r\n" "开头\n右侧\n新行\n结尾\n")
        changed (filterv #(not= :nop (:kind %)) (:rows result))]
    (is (= 1 (:changes result)))
    (is (= 2 (count changed)))
    (is (= "左侧" (get-in changed [0 :left :text])))
    (is (= "右侧" (get-in changed [0 :right :text])))
    (is (nil? (get-in changed [1 :left])))
    (is (= "新行" (get-in changed [1 :right :text])))
    (is (= 0 (:changes (compare/line-differences "同一行\r\n" "同一行\n")))))
  (is (= :same (compare/pair-status (compare/entry-content {:text "相同"}) (compare/entry-content {:text "相同"}))))
  (is (= :left-only (compare/pair-status (compare/entry-content {:text "左"}) (compare/entry-content nil))))
  (is (= :missing (compare/pair-status (compare/entry-content {:missing true}) (compare/entry-content {:text "右"})))))

;; Real previews support switching, distinct selections, diff mode and removal.
(deftest comparison-window-lifecycle-test
  (ui/run-now
    (let [owner (doto (Stage.) (.setScene (Scene. (AnchorPane.) 800.0 600.0)))
          cards (atom [{:id "a" :kind :result :title "方案 A" :candidates [{:id "a" :text "# 方案 A\n\n共同行\n左侧规则"}]}
                       {:id "b" :kind :result :title "方案 B" :candidates [{:id "b" :text "# 方案 B\n\n共同行\n右侧规则"}]}
                       {:id "c" :kind :result :title "方案 C" :candidates [{:id "c" :text "# 方案 C\n\n第三项规则"}]}])
          inspected (atom [])
          view (compare/open! owner "." "策划任务" #(deref cards)
                              {:markdown-opts {} :on-inspect! #(swap! inspected conj %)})
          stage ^Stage (:stage view) root (.getRoot (.getScene stage))
          left ^ComboBox (.lookup root "#ai-compare-left") right ^ComboBox (.lookup root "#ai-compare-right")
          mode ^ComboBox (.lookup root "#ai-compare-mode")]
      (try
        (.applyCss root) (.layout root)
        (is (= ["a" "b"] (mapv #(:id (.getValue ^ComboBox %)) [left right])))
        (is (= 2 (count (.lookupAll root ".ai-markdown-reader"))))
        (.setValue left (second (.getItems left)))
        (is (= ["b" "a"] (mapv #(:id (.getValue ^ComboBox %)) [left right])))
        (.setValue left (last (.getItems left)))
        (.fire ^Button (.lookup root "#ai-compare-inspect-left"))
        (is (= ["c"] @inspected))
        (.setValue mode "文本差异") (.applyCss root) (.layout root)
        (is (pos? (count (.getItems ^ListView (.lookup root "#ai-compare-diff")))))
        (is (.contains (.getText ^Label (.lookup root "#ai-compare-status")) "处差异"))
        (.setValue mode "阅读预览") (.applyCss root) (.layout root)
        (let [[a b] (vec (.lookupAll root "ScrollPane"))]
          (.setVvalue ^ScrollPane a 0.4)
          (is (= 0.4 (.getVvalue ^ScrollPane b))))
        (.fire ^CheckBox (.lookup root "#ai-compare-sync"))
        (is (not (.isSelected ^CheckBox (.lookup root "#ai-compare-sync"))))
        (.fire ^Button (.lookup root "#ai-compare-swap"))
        (is (= ["a" "c"] (mapv #(:id (.getValue ^ComboBox %)) [left right])))
        (swap! cards #(subvec % 0 2)) ((:refresh! view))
        (is (= ["a" "b"] (mapv #(:id (.getValue ^ComboBox %)) [left right])))
        (reset! cards []) ((:refresh! view))
        (is (= "暂无可对比内容" (.getText ^Label (.lookup root "#ai-compare-status"))))
        (is (nil? (.lookup root ".ai-toast-error")))
        (finally (.close stage) (.close owner))))))

;; Cached image files have no standard extension but still render as images.
(deftest cached-image-comparison-test
  (let [root (.toFile (Files/createTempDirectory "compare-image" (make-array java.nio.file.attribute.FileAttribute 0)))
        file (io/file root "preview.image")]
    (try
      (javax.imageio.ImageIO/write (java.awt.image.BufferedImage. 4 4 java.awt.image.BufferedImage/TYPE_INT_ARGB) "png" file)
      (let [card {:kind :result :candidates [{:id "image" :image-path "/preview.image" :preview (str (.toURI file))}]}
            pair (first (compare/resource-pairs root card card))]
        (is (= :image (:content-kind (compare/entry-content (:left pair)))))
        (is (= :same (compare/pair-status (compare/entry-content (:left pair)) (compare/entry-content (:right pair)))))
        (ui/run-now
          (let [owner (doto (Stage.) (.setScene (Scene. (AnchorPane.) 800.0 600.0)))
                view (compare/open! owner root "美术任务"
                                    (constantly [(assoc card :id "a" :title "效果 A") (assoc card :id "b" :title "效果 B")])
                                    {:on-inspect! (fn inspect-image [_])})
                stage ^Stage (:stage view) pane (.getRoot (.getScene stage))]
            (try
              (.applyCss pane) (.layout pane)
              (is (= 2 (count (.lookupAll pane "ImageView"))))
              (is (.isDisabled ^ComboBox (.lookup pane "#ai-compare-mode")))
              (is (= "内容相同" (.getText ^Label (.lookup pane "#ai-compare-status"))))
              (finally (.close stage) (.close owner))))))
      (finally (.delete file) (.delete root)))))
