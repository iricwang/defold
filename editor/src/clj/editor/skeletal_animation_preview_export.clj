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

(ns editor.skeletal-animation-preview-export
  "Streaming frame exports. Publish only complete output."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io])
  (:import [java.awt Color]
           [java.awt.image BufferedImage]
           [java.io ByteArrayOutputStream File RandomAccessFile]
           [java.nio.file Files]
           [javax.imageio IIOImage ImageIO ImageTypeSpecifier ImageWriter]
           [javax.imageio.metadata IIOMetadataNode]))

(set! *warn-on-reflection* true)

(defn frame-times [duration fps]
  (when-not (and (number? duration) (Double/isFinite (double duration)) (pos? duration)
                 (number? fps) (Double/isFinite (double fps)) (<= 1 fps 240)
                 (<= (Math/ceil (* duration fps)) 4096))
    (throw (ex-info "Export requires a positive duration, 1–240 FPS and at most 4096 frames." {})))
  (mapv #(/ (double %) fps) (range (long (Math/ceil (* duration fps))))))

(defn- gif-metadata [writer ^BufferedImage image fps index]
  (let [metadata (.getDefaultImageMetadata ^javax.imageio.ImageWriter writer
                                           (ImageTypeSpecifier/createFromRenderedImage image) nil)
        root (IIOMetadataNode. "javax_imageio_gif_image_1.0")
        control (IIOMetadataNode. "GraphicControlExtension")]
    (doseq [[attribute value] {"disposalMethod" "restoreToBackgroundColor" "userInputFlag" "FALSE"
                               "transparentColorFlag" "TRUE" "transparentColorIndex" "0"
                               "delayTime" (str (max 1 (- (Math/round (/ (* 100.0 (inc index)) fps)) (Math/round (/ (* 100.0 index) fps)))))}]
      (.setAttribute control attribute value))
    (.appendChild root control)
    (when (zero? index)
      (let [extensions (IIOMetadataNode. "ApplicationExtensions")
            extension (IIOMetadataNode. "ApplicationExtension")]
        (.setAttribute extension "applicationID" "NETSCAPE")
        (.setAttribute extension "authenticationCode" "2.0")
        (.setUserObject extension (byte-array [1 0 0]))
        (.appendChild extensions extension)
        (.appendChild root extensions)))
    (.mergeTree metadata "javax_imageio_gif_image_1.0" root)
    metadata))

(defn- write-gif! [file times fps frame!]
  (let [^ImageWriter writer (.next (ImageIO/getImageWritersByFormatName "gif"))]
    (try
      (with-open [output (ImageIO/createImageOutputStream file)]
        (.setOutput writer output)
        (.prepareWriteSequence writer nil)
        (doseq [[index time] (map-indexed vector times)]
          (let [^BufferedImage image (frame! time)
                ^java.util.List thumbnails nil
                ^javax.imageio.metadata.IIOMetadata metadata (gif-metadata writer image fps index)]
            (.writeToSequence writer (IIOImage. image thumbnails metadata) nil)))
        (.endWriteSequence writer))
      (finally (.dispose writer)))))

(defn- u32! [^RandomAccessFile output value]
  (.writeInt output (Integer/reverseBytes (unchecked-int value))))

(defn- chunk! [^RandomAccessFile output tag f]
  (.writeBytes output tag)
  (let [size-offset (.getFilePointer output)]
    (u32! output 0)
    (f)
    (let [end (.getFilePointer output) size (- end size-offset 4)]
      (when (> size 4294967295)
        (throw (ex-info "AVI exceeds the 4 GiB RIFF limit. Export PNG frames or a shorter clip." {:chunk tag :size size})))
      (.seek output size-offset)
      (u32! output size)
      (.seek output end)
      (when (odd? size) (.write output 0)))))

(defn- write-avi! [file times fps width height frame!]
  (with-open [output (RandomAccessFile. ^File file "rw")]
    (chunk! output "RIFF"
            (fn write-riff []
              (.writeBytes output "AVI ")
              (chunk! output "LIST"
                      (fn write-header-list []
                        (.writeBytes output "hdrl")
                        (chunk! output "avih"
                                (fn write-main-header []
                                  (doseq [n [(Math/round (/ 1000000.0 fps)) 0 0 16 (count times) 0 1 0 width height 0 0 0 0]] (u32! output n))))
                        (chunk! output "LIST"
                                (fn write-stream-list []
                                  (.writeBytes output "strl")
                                  (chunk! output "strh"
                                          (fn write-stream-header []
                                            (.writeBytes output "vidsMJPG")
                                            (doseq [n [0 0 0 1000 (Math/round (double (* fps 1000))) 0 (count times) 0 -1 0 0
                                                       (bit-or (bit-shift-left (long height) 16) (long width))]] (u32! output n))))
                                  (chunk! output "strf"
                                          (fn write-bitmap-header []
                                            (doseq [n [40 width height 1572865]] (u32! output n))
                                            (.writeBytes output "MJPG")
                                            (doseq [n [(* width height 3) 0 0 0 0]] (u32! output n))))))))
              (let [entries (atom [])]
                (chunk! output "LIST"
                        (fn write-movie []
                          (let [movie-offset (.getFilePointer output)]
                            (.writeBytes output "movi")
                            (doseq [time times]
                              (let [source (frame! time)
                                    rgb (BufferedImage. width height BufferedImage/TYPE_INT_RGB)
                                    graphics (.createGraphics rgb)
                                    bytes (ByteArrayOutputStream.)]
                                (try
                                  (.setColor graphics Color/BLACK)
                                  (.fillRect graphics 0 0 width height)
                                  (.drawImage graphics ^BufferedImage source 0 0 nil)
                                  (finally (.dispose graphics)))
                                (when-not (ImageIO/write rgb "jpeg" bytes)
                                  (throw (ex-info "JPEG encoder unavailable." {})))
                                (let [data (.toByteArray bytes)
                                      offset (- (.getFilePointer output) movie-offset)]
                                  (swap! entries conj [offset (alength data)])
                                  (chunk! output "00dc" (fn write-frame [] (.write output data)))))))))
                (chunk! output "idx1"
                        (fn write-index []
                          (doseq [[offset size] @entries]
                            (.writeBytes output "00dc")
                            (doseq [n [16 offset size]] (u32! output n))))))))))

(defn- write-sheet! [^File file times width height frame!]
  (let [columns (long (Math/ceil (Math/sqrt (count times))))
        rows (long (Math/ceil (/ (count times) (double columns))))
        sheet-width (* columns width) sheet-height (* rows height)]
    (when (> (* sheet-width sheet-height) 67108864)
      (throw (ex-info "Packed sheet exceeds 64 megapixels. Lower the resolution or export PNG frames." {})))
    (let [sheet (BufferedImage. sheet-width sheet-height BufferedImage/TYPE_INT_ARGB)
          graphics (.createGraphics sheet)]
      (try
        (doseq [[index time] (map-indexed vector times)]
          (.drawImage graphics ^BufferedImage (frame! time) (int (* (mod index columns) width)) (int (* (quot index columns) height)) nil))
        (finally (.dispose graphics)))
      (ImageIO/write sheet "png" file)
      (mapv (fn [index time] {:index index :time time :x (* (mod index columns) width) :y (* (quot index columns) height)
                              :width width :height height}) (range (count times)) times))))

(defn export!
  "Export into a NEW directory. frame! returns an ARGB image for a time.
  format: :png, :gif, :avi (MJPEG), :sheet (PNG + rectangles)."
  [destination {:keys [format duration fps width height] :as options} frame!]
  (let [times (frame-times duration fps)
        destination (.getCanonicalFile (io/file destination))
        parent (.getParentFile destination)]
    (when-not (and (contains? #{:png :gif :avi :sheet} format)
                   (integer? width) (integer? height) (<= 1 width 4096) (<= 1 height 4096))
      (throw (ex-info "Select a supported format and dimensions between 1 and 4096." {})))
    (when (and (= :gif format) (> fps 100))
      (throw (ex-info "GIF supports at most 100 FPS. Use PNG or AVI for higher rates." {})))
    (when (.exists destination) (throw (ex-info "Choose a new export directory; existing output is never overwritten." {})))
    (when-not (.isDirectory parent) (throw (ex-info "Export parent directory does not exist." {})))
    (let [staging (.toFile (Files/createTempDirectory (.toPath parent) ".skeletal-export-" (make-array java.nio.file.attribute.FileAttribute 0)))
          checked-frame! (fn checked-frame [time]
                           (let [^BufferedImage image (frame! time)]
                             (when-not (and (instance? BufferedImage image) (= width (.getWidth ^BufferedImage image)) (= height (.getHeight ^BufferedImage image)))
                               (throw (ex-info "Renderer returned an unexpected image size." {})))
                             image))]
      (try
        (let [frames (case format
                       :png (mapv (fn [index time]
                                    (let [name (clojure.core/format "frame_%05d.png" (long index))]
                                      (ImageIO/write ^BufferedImage (checked-frame! time) "png" (io/file staging name))
                                      {:index index :time time :file name})) (range (count times)) times)
                       :gif (do (write-gif! (io/file staging "animation.gif") times fps checked-frame!) times)
                       :avi (do (write-avi! (io/file staging "animation.avi") times fps width height checked-frame!) times)
                       :sheet (write-sheet! (io/file staging "animation.png") times width height checked-frame!))]
          (spit (io/file staging "manifest.json") (json/write-str (assoc options :frames frames)))
          (Files/move (.toPath staging) (.toPath destination) (make-array java.nio.file.CopyOption 0))
          {:directory (.getPath destination) :frames (count times) :format format})
        (finally
          (when (.exists staging)
            (doseq [file (reverse (vec (file-seq staging)))] (io/delete-file file true))))))))
