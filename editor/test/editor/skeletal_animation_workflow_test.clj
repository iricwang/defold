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

(ns editor.skeletal-animation-workflow-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [editor.skeletal-animation-audio :as audio]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-native :as native]
            [editor.skeletal-animation-preview-export :as export]
            [editor.skeletal-animation-project :as project]
            [editor.ui :as ui])
  (:import [java.awt Color]
           [java.awt.image BufferedImage]
           [java.io RandomAccessFile]
           [java.nio.file Files]
           [java.util Base64]
           [javax.imageio ImageIO]))

(defn- with-directory [f]
  (let [directory (.toFile (Files/createTempDirectory "skeletal-workflow-" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try (f directory)
         (finally (doseq [file (reverse (vec (file-seq directory)))] (io/delete-file file true))))))

(defn- frame [time]
  (let [image (BufferedImage. 16 16 BufferedImage/TYPE_INT_ARGB)
        graphics (.createGraphics image)]
    (try (.setColor graphics (if (< time 0.5) Color/RED Color/BLUE))
         (.fillRect graphics (if (< time 0.5) 0 8) 0 8 16)
         (finally (.dispose graphics)))
    image))

;; Switching and saving must keep every skeleton's edits isolated.
(deftest multiple-skeleton-workspace-test
  (with-directory
    (fn verify-workspace [directory]
      (let [first (assoc-in (data/new-document "hero") [:bones 0 :x] 14.0)
            second (-> first (project/add "weapon") (assoc-in [:bones 0 :x] 91.0))
            restored (data/read-json (data/write-json second))
            hero (project/select restored "hero")
            weapon (project/select hero "weapon")
            renamed (project/rename-active weapon "sword")
            target (io/file directory "two.spine")]
        (is (= 14.0 (get-in hero [:bones 0 :x])))
        (is (= 91.0 (get-in weapon [:bones 0 :x])))
        (is (= ["hero" "sword"] (mapv :name (project/documents renamed))))
        (is (thrown? clojure.lang.ExceptionInfo (project/rename-active renamed "hero")))
        (is (= "hero" (:name (project/remove-active renamed))))
        (is (thrown? clojure.lang.ExceptionInfo (project/remove-active (data/new-document "only"))))
        (project/write-native! renamed directory target)
        (let [graph (native/decode-graph (native/decode (Files/readAllBytes (.toPath target))))]
          (is (= ["hero" "sword"] (mapv :name (native/graph-skeletons graph)))))))))

;; Media exports retain frame count and never publish partial output.
(deftest preview-export-formats-test
  (with-directory
    (fn verify-exports [directory]
      (doseq [format [:png :gif :avi :sheet]]
        (let [output (io/file directory (name format))
              result (export/export! output {:format format :duration 1.0 :fps 2.0 :width 16 :height 16} frame)]
          (is (= 2 (:frames result)))
          (is (.isFile (io/file output "manifest.json")))
          (case format
            :png (let [image (ImageIO/read (io/file output "frame_00000.png"))]
                   (is (= (.getRGB Color/RED) (.getRGB image 2 2)))
                   (is (zero? (unsigned-bit-shift-right (.getRGB image 14 2) 24))))
            :gif (with-open [input (ImageIO/createImageInputStream (io/file output "animation.gif"))]
                   (let [reader (.next (ImageIO/getImageReaders input))]
                     (try (.setInput reader input) (is (= 2 (.getNumImages reader true)))
                          (is (= (.getRGB Color/BLUE) (.getRGB (.read reader 1) 12 2)))
                          (finally (.dispose reader)))))
            :avi (let [bytes (Files/readAllBytes (.toPath (io/file output "animation.avi")))]
                   (is (= "RIFF" (String. bytes 0 4 "US-ASCII")))
                   (is (= "AVI " (String. bytes 8 4 "US-ASCII"))))
            :sheet (let [image (ImageIO/read (io/file output "animation.png"))]
                     (is (= 32 (.getWidth image)))
                     (is (= (.getRGB Color/BLUE) (.getRGB image 28 2)))))
          (is (thrown? clojure.lang.ExceptionInfo
                       (export/export! output {:format format :duration 1.0 :fps 2.0 :width 16 :height 16} frame)))))
      (let [target (io/file directory "broken")]
        (is (thrown? Exception (export/export! target {:format :png :duration 1.0 :fps 2.0 :width 16 :height 16}
                                               (fn broken-frame [_] (throw (ex-info "render failed" {}))))))
        (is (not (.exists target)))
        (is (= 4 (count (.listFiles directory))))))))

;; Audio cannot escape the project through traversal or symlinks.
;; Imports never overwrite existing assets.
(deftest audio-project-boundary-test
  (with-directory
    (fn verify-audio [directory]
      (let [project (doto (io/file directory "project") .mkdir)
            outside (io/file directory "sound.wav")]
        (spit outside "test audio")
        (is (nil? (audio/resolve-file project {} "")))
        (is (thrown? clojure.lang.ExceptionInfo (audio/resolve-file project {} "../sound.wav")))
        (is (thrown? clojure.lang.ExceptionInfo (audio/resolve-file project {} (.getPath outside))))
        (let [a (audio/import-file! project outside) b (audio/import-file! project outside)]
          (is (not= a b))
          (is (= "test audio" (slurp (audio/resolve-file project {} a)))))
        (Files/createSymbolicLink (.toPath (io/file project "escape.wav")) (.toPath outside) (make-array java.nio.file.attribute.FileAttribute 0))
        (is (thrown? clojure.lang.ExceptionInfo (audio/resolve-file project {} "escape.wav")))))))

;; Invalid parameters fail before rendering or creating output.
(deftest bounded-export-test
  (is (= [0.0 0.5] (export/frame-times 1.0 2.0)))
  (doseq [[duration fps] [[Double/NaN 30.0] [1.0 0.0] [1000.0 60.0] [1.0 300.0]]]
    (is (thrown? clojure.lang.ExceptionInfo (export/frame-times duration fps)))))

;; Stopping an idle audio player must be safe before loading and after disposal.
(deftest idle-audio-lifecycle-test
  (let [errors (atom []) player (audio/make-player "." #(swap! errors conj %))]
    ((:stop! player))
    ((:mute! player) true)
    ((:mute! player) false)
    ((:dispose! player))
    ((:stop! player))
    ((:play! player) {} {:audio "not-loaded.wav"})
    (is (= [] @errors))))

;; Invalid saved camera values must not poison the JavaFX render loop.
(deftest view-preferences-boundary-test
  (let [settings (project/view-settings {:editor-view {:zoom Double/NaN :pan-x Double/POSITIVE_INFINITY :reference-opacity 9
                                                       :reference-image "../../outside.png"}})]
    (is (= 1.0 (:zoom settings)))
    (is (= 300.0 (:pan-x settings)))
    (is (= 1.0 (:reference-opacity settings)))
    (is (nil? (:reference-image settings)))))

;; GIF centisecond quantization must not speed up or slow down a full second.
(deftest gif-timing-test
  (with-directory
    (fn verify-gif-timing [directory]
      (let [output (io/file directory "gif")]
        (export/export! output {:format :gif :duration 1.0 :fps 30.0 :width 16 :height 16} frame)
        (with-open [input (ImageIO/createImageInputStream (io/file output "animation.gif"))]
          (let [reader (.next (ImageIO/getImageReaders input))]
            (try
              (.setInput reader input)
              (let [delays (mapv (fn [index]
                                   (let [tree (.getAsTree (.getImageMetadata reader index) "javax_imageio_gif_image_1.0")
                                         controls (.getElementsByTagName ^javax.imageio.metadata.IIOMetadataNode tree "GraphicControlExtension")]
                                     (Long/parseLong (.getAttribute ^javax.imageio.metadata.IIOMetadataNode (.item controls 0) "delayTime"))))
                                 (range (.getNumImages reader true)))]
                (is (= #{3 4} (set delays)))
                (is (= 100 (reduce + delays))))
              (finally (.dispose reader)))))
        (is (thrown? clojure.lang.ExceptionInfo
                     (export/export! (io/file directory "too-fast") {:format :gif :duration 1.0 :fps 120.0 :width 16 :height 16} frame)))))))

;; Native metadata survives deleting the last original workspace skeleton.
(deftest native-workspace-origin-test
  (with-directory
    (fn verify-native-origin [directory]
      (let [document (data/new-document "original")
            source (json/read-str (data/export-spine-json document))
            graph (native/from-skeletons [{:name "original" :source source}])
            root (:root graph)
            graph (assoc-in graph [(:space root) (:id root) :data 8] 1.375)
            packed (.encodeToString (Base64/getEncoder) (native/encode-graph graph))
            document (assoc document :native-project {:codec "independent-4.2.43" :packed packed :selected-index 0})
            workspace (-> (project/assemble [{:name "original" :document document}] "original")
                          (project/add "new") (project/select "original") project/remove-active)
            target (io/file directory "workspace.spine")]
        (project/write-native! (data/read-json (data/write-json workspace)) directory target)
        (let [result (native/decode-graph (native/decode (Files/readAllBytes (.toPath target))))]
          (is (= ["new"] (mapv :name (native/graph-skeletons result))))
          (is (= 1.375 (get-in (native/graph-node result (:root result)) [:data 8]))))))))

;; Reject oversized RIFF lengths without writing a multi-gigabyte fixture.
(deftest avi-container-boundary-test
  (with-directory
    (fn verify-riff-boundary [directory]
      (with-open [output (RandomAccessFile. (io/file directory "limit.avi") "rw")]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"4 GiB"
                              (#'export/chunk! output "RIFF"
                                               (fn exceed-riff-limit [] (.seek output 4294967304)))))
        (is (= 8 (.length output)))))))

;; First-load coalescing preserves each event and cancels stale queued sounds.
(deftest pending-audio-events-test
  (doseq [stop-before-load [false true]]
    (with-directory
      (fn verify-pending-audio [directory]
        (spit (io/file directory "sound.wav") "test loader stub")
        (let [ready (promise) release (promise) completed (promise)
              plays (atom []) loads (atom 0) errors (atom [])
              expected (if stop-before-load [3] [1 2])
              player (#'audio/make-player* directory #(swap! errors conj %)
                                           {:load! (fn load-audio [_] (swap! loads inc) (deliver ready true) @release :clip)
                                            :play! (fn play-audio [_ event]
                                                     (when (= (count expected) (count (swap! plays conj (:int event))))
                                                       (deliver completed true)))
                                            :stop! (fn stop-audio [_])})]
          (try
            (ui/run-now
              ((:play! player) {} {:audio "sound.wav" :int 1})
              ((:play! player) {} {:audio "sound.wav" :int 2})
              (when stop-before-load
                ((:stop! player))
                ((:play! player) {} {:audio "sound.wav" :int 3})))
            (is (= true (deref ready 5000 :timeout)))
            (deliver release true)
            (is (= true (deref completed 5000 :timeout)))
            (is (= expected @plays))
            (is (= 1 @loads))
            (is (= [] @errors))
            (finally
              (deliver release true)
              (ui/run-now ((:dispose! player))))))))))
