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

(ns editor.skeletal-animation-io-review-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [editor.fs :as fs]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-io :as skeletal-io]
            [editor.skeletal-animation-native :as native])
  (:import [java.awt.image BufferedImage]
           [java.io File]
           [java.nio.file Files]
           [javax.imageio ImageIO]))

;; Saving an edited skeleton in a different directory must preserve sibling
;; skeleton content and rebase its relative image root. Persisted import
;; metadata must survive a normal authoring save before the native Save As.
(deftest native-save-as-retains-sibling-images-test
  (let [directory (fs/create-temp-directory! "spine-peer-review")]
    (try
      (let [origin (doto (io/file directory "origin") .mkdirs)
            project (doto (io/file directory "project") .mkdirs)
            image (io/file origin "images" "part.png")
            source-file (io/file origin "duo.spine")
            target (io/file directory "moved" "edited.spine")
            document (data/add-slot (data/new-document "hero")
                                    {:id "body" :bone "root"
                                     :attachment {:path "/part.png" :width 8.0 :height 8.0}})
            source (assoc-in (json/read-str (data/export-spine-json document)) ["skeleton" "images"] "images")
            peer-source (assoc-in source ["bones" 0 "x"] 77.0)
            packed (native/encode-graph (native/from-skeletons [{:name "hero" :source source}
                                                               {:name "peer" :source peer-source}]))]
        (fs/create-parent-directories! image)
        (ImageIO/write (BufferedImage. 8 8 BufferedImage/TYPE_INT_ARGB) "png" image)
        (with-open [stream (io/output-stream source-file)] (.write stream ^bytes packed))
        (let [decoded (skeletal-io/read-independent-project! source-file)
              imported (:document (skeletal-io/read-independent-skeleton! source-file project decoded "hero"))
              restored (data/read-json (data/write-json imported))
              edited (data/update-bone restored "root" {:x 42.0})
              _ (skeletal-io/write-independent-project! edited project target)
              reopened (skeletal-io/read-independent-project! target)
              peer (get-in reopened [:skeletons 1 :source])
              peer-images (io/file (get-in peer ["skeleton" "images"]))
              resolved (if (.isAbsolute peer-images) peer-images (io/file (.getParentFile target) (str peer-images)))
              graph (native/decode-graph (native/decode (Files/readAllBytes (.toPath target))))]
          (is (= (:native-project imported) (:native-project restored)))
          (is (= ["hero" "peer"] (mapv :name (:skeletons reopened))))
          (is (= 42.0 (get-in reopened [:skeletons 0 :source "bones" 0 "x"])))
          (is (= 77.0 (get-in peer ["bones" 0 "x"])))
          (is (= (vec packed) (vec (Files/readAllBytes (.toPath source-file)))))
          (is (= (.getCanonicalFile (io/file origin "images")) (.getCanonicalFile resolved)))
          (is (.isFile ^File (io/file resolved "part.png")))
          (doseq [skeleton (native/graph-skeletons graph)]
            (let [fields (:data (native/graph-node graph (:reference skeleton)))
                  audio (:data (native/graph-node graph (get fields 34)))]
              (is (= (.getCanonicalFile (io/file origin "audio")) (.getCanonicalFile (io/file audio))))))
          (let [peer-imported (:document (skeletal-io/read-independent-skeleton! source-file project decoded "peer"))
                peer-target (io/file directory "second" "peer.spine")
                _ (skeletal-io/write-independent-project! (data/update-bone peer-imported "root" {:x 88.0}) project peer-target)
                peer-reopened (skeletal-io/read-independent-project! peer-target)]
            (is (= 1 (get-in peer-imported [:native-project :selected-index])))
            (is (= 0.0 (get-in peer-reopened [:skeletons 0 :source "bones" 0 "x"])))
            (is (= 88.0 (get-in peer-reopened [:skeletons 1 :source "bones" 0 "x"]))))))
      (finally (fs/delete-directory! directory {:fail :silently})))))

;; Native projects can keep images outside the project directory. Resolve an
;; absolute root directly so Windows does not need to relativize different drives.
;; Ordinary JSON import retains its separate relative-root restriction.
(deftest native-absolute-images-root-test
  (let [directory (fs/create-temp-directory! "spine-absolute-review")]
    (try
      (let [origin (doto (io/file directory "source-project") .mkdirs)
            project (doto (io/file directory "defold-project") .mkdirs)
            image-root (doto (io/file directory "separate image library") .mkdirs)
            image (io/file image-root "part.png")
            source-file (io/file origin "hero.spine")
            document (data/add-slot (data/new-document "hero")
                                    {:id "body" :bone "root"
                                     :attachment {:path "/part.png" :width 8.0 :height 8.0}})
            source (assoc-in (json/read-str (data/export-spine-json document)) ["skeleton" "images"] (.getCanonicalPath image-root))
            packed (native/encode-graph (native/from-skeletons [{:name "hero" :source source}]))]
        (ImageIO/write (BufferedImage. 8 8 BufferedImage/TYPE_INT_ARGB) "png" image)
        (with-open [stream (io/output-stream source-file)] (.write stream ^bytes packed))
        (let [decoded (skeletal-io/read-independent-project! source-file)
              imported (skeletal-io/read-independent-skeleton! source-file project decoded "hero")
              copied (first (:files imported))]
          (is (= 1 (count (:files imported))))
          (is (.isFile ^File copied))
          (is (= (.getWidth (ImageIO/read image)) (.getWidth (ImageIO/read ^File copied))))
          (is (= (vec (Files/readAllBytes (.toPath image))) (vec (Files/readAllBytes (.toPath ^File copied)))))
          (is (= (vec packed) (vec (Files/readAllBytes (.toPath source-file)))))
          (is (= "independent-4.2.43" (get-in imported [:document :native-project :codec])))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"relative"
                               (skeletal-io/read-spine-source! source-file project source)))))
      (finally (fs/delete-directory! directory {:fail :silently})))))

;; Persisted bridge-import metadata predates the independent codec. Saving it
;; into a new directory must relocate every skeleton's audio as well as images.
(deftest legacy-multiple-skeleton-audio-save-test
  (let [directory (fs/create-temp-directory! "spine-legacy-audio-review")]
    (try
      (let [origin (doto (io/file directory "origin") .mkdirs)
            project (doto (io/file directory "project") .mkdirs)
            image (io/file origin "images" "part.png")
            source-file (io/file origin "legacy.spine")
            target (io/file directory "new-location" "saved.spine")
            document (data/add-slot (data/new-document "hero")
                                    {:id "body" :bone "root"
                                     :attachment {:path "/part.png" :width 8.0 :height 8.0}})
            source (-> (json/read-str (data/export-spine-json document))
                       (assoc-in ["skeleton" "images"] "images")
                       (assoc-in ["skeleton" "audio"] "hero-audio"))
            peer-source (-> source
                            (assoc-in ["skeleton" "audio"] "peer/audio")
                            (assoc-in ["bones" 0 "x"] 77.0))]
        (fs/create-parent-directories! image)
        (ImageIO/write (BufferedImage. 8 8 BufferedImage/TYPE_INT_ARGB) "png" image)
        (let [imported (:document (skeletal-io/read-native-skeleton! source-file project
                                                                   [{:name "hero" :source source} {:name "peer" :source peer-source}] "hero"))
              restored (data/read-json (data/write-json imported))
              _ (skeletal-io/write-independent-project! restored project target)
              reopened (skeletal-io/read-independent-project! target)
              graph (native/decode-graph (native/decode (Files/readAllBytes (.toPath target))))
              audio-roots (mapv (fn [skeleton]
                                 (let [fields (:data (native/graph-node graph (:reference skeleton)))]
                                   (:data (native/graph-node graph (get fields 34)))))
                               (native/graph-skeletons graph))]
          (is (nil? (get-in restored [:native-project :codec])))
          (is (= ["hero" "peer"] (mapv :name (:skeletons reopened))))
          (is (= [(.getCanonicalFile (io/file origin "hero-audio"))
                  (.getCanonicalFile (io/file origin "peer" "audio"))]
                 (mapv #(.getCanonicalFile (io/file %)) audio-roots)))
          (is (= 77.0 (get-in reopened [:skeletons 1 :source "bones" 0 "x"])))
          (is (= (.getCanonicalFile (io/file origin "images"))
                 (.getCanonicalFile (io/file (get-in reopened [:skeletons 1 :source "skeleton" "images"])))))
          (is (.isFile target))))
      (finally (fs/delete-directory! directory {:fail :silently})))))
