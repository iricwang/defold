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

(ns editor.skeletal-animation-textures
  "Non-destructive texture resolution for preview and runtime exports."
  (:require [clojure.java.io :as io])
  (:import [java.awt AlphaComposite RenderingHints]
           [java.awt.image BufferedImage]
           [java.io File]
           [java.nio.file Files CopyOption StandardCopyOption]
           [javax.imageio ImageIO ImageReader]))

(set! *warn-on-reflection* true)

(defn scale [document]
  (let [value (get-in document [:texture-output :scale] 1.0)]
    (when-not (and (number? value) (Double/isFinite (double value)) (<= 0.01 (double value) 1.0))
      (throw (ex-info "Texture output scale must be between 1% and 100%." {:scale value})))
    (double value)))

(defn set-scale [document value]
  (let [result (assoc-in document [:texture-output :scale] value)]
    (scale result)
    result))

(defn dimensions [width height factor]
  [(max 1 (Math/round (* (double width) (double factor))))
   (max 1 (Math/round (* (double height) (double factor))))])

(defn image-size [file]
  (with-open [stream (ImageIO/createImageInputStream (io/file file))]
    (let [readers (when stream (ImageIO/getImageReaders stream))]
      (when-not (and readers (.hasNext readers))
        (throw (ex-info "Texture is not a readable PNG." {:file (str file)})))
      (let [^ImageReader reader (.next readers)]
        (try
          (when-not (= "png" (.toLowerCase (.getFormatName reader) java.util.Locale/ROOT))
            (throw (ex-info "Texture inputs must be PNG files." {:file (str file)})))
          (.setInput reader stream true true)
          [(.getWidth reader 0) (.getHeight reader 0)]
          (finally (.dispose reader)))))))

(defn resize-image
  "Progressive premultiplied-alpha resampling; geometry and UVs stay unchanged."
  ^BufferedImage [^BufferedImage source factor]
  (let [[width height] (dimensions (.getWidth source) (.getHeight source) factor)]
    (loop [image source]
      (if (and (= width (.getWidth image)) (= height (.getHeight image)))
        image
        (let [w (max width (quot (.getWidth image) 2))
              h (max height (quot (.getHeight image) 2))
              next-image (BufferedImage. (int w) (int h) BufferedImage/TYPE_INT_ARGB_PRE)
              graphics (.createGraphics next-image)]
          (try
            (.setComposite graphics AlphaComposite/Src)
            (.setRenderingHint graphics RenderingHints/KEY_INTERPOLATION RenderingHints/VALUE_INTERPOLATION_BICUBIC)
            (.drawImage graphics image 0 0 (int w) (int h) nil)
            (finally (.dispose graphics)))
          (recur next-image))))))

(defn write-scaled!
  "Atomically replace a derived PNG.
  The caller validates destination ownership."
  [^File source ^File target factor]
  (let [image (ImageIO/read source)]
    (when-not image (throw (ex-info "Cannot decode texture." {:file (str source)})))
    (Files/createDirectories (.toPath (.getParentFile target)) (make-array java.nio.file.attribute.FileAttribute 0))
    (let [temporary (Files/createTempFile (.toPath (.getParentFile target)) ".texture-" ".png" (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (when-not (ImageIO/write (resize-image image factor) "png" (.toFile temporary))
          (throw (ex-info "PNG encoder unavailable." {})))
        (Files/move temporary (.toPath target) (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))
        (finally (Files/deleteIfExists temporary)))))
  target)
