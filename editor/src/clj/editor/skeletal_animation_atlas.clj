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

(ns editor.skeletal-animation-atlas
  "Pack project PNGs into portable Spine atlas pages."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.fs :as fs]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-sequence :as sequence]
            [editor.skeletal-animation-textures :as textures]
            [util.coll :as coll])
  (:import [java.awt.image BufferedImage]
           [java.io File]
           [java.nio.file CopyOption Files LinkOption]
           [java.nio.file.attribute FileAttribute]
           [javax.imageio ImageIO ImageReader]))

(set! *warn-on-reflection* true)

(defn- relative-path [path]
  (when-not (and (string? path) (not (string/blank? path))
                 (not (.isAbsolute (io/file path)))
                 (not (re-find #"[:\\\r\n]" path))
                 (coll/not-any? #{"" "." ".."} (string/split path #"/" -1)))
    (throw (ex-info "Atlas image names must be safe relative paths." {:path path})))
  path)

(defn- source-images [document ^File root]
  (let [source (json/read-str (data/export-spine-json document))
        names (coll/into-> (get source "skins") (sorted-set)
                (mapcat #(coll/vals (get % "attachments")))
                (mapcat identity)
                (mapcat (fn [[name raw]]
                          (when (contains? #{"region" "mesh" "linkedmesh"} (get raw "type" "region"))
                            (let [path (relative-path (get raw "path" (get raw "name" name)))]
                              (if-let [config (sequence/decode (get raw "sequence"))]
                                (sequence/frame-paths path config)
                                [path]))))))]
    (when (coll/empty? names)
      (throw (ex-info "An atlas needs at least one image attachment." {})))
    (mapv (fn [name]
            (let [path (if (string/ends-with? (string/lower-case name) ".png") name (str name ".png"))
                  file (.getCanonicalFile (io/file root path))]
              (when-not (and (.startsWith (.toPath file) (.toPath root))
                             (Files/isRegularFile (.toPath file) (make-array LinkOption 0)))
                (throw (ex-info "Atlas image is missing or escapes the project." {:path path})))
              (with-open [stream (ImageIO/createImageInputStream file)]
                (let [readers (when stream (ImageIO/getImageReaders stream))]
                  (when-not (and readers (.hasNext readers))
                    (throw (ex-info "Atlas image is not a readable PNG." {:path path})))
                  (let [^ImageReader reader (.next readers)]
                    (try
                      (when-not (= "png" (string/lower-case (.getFormatName reader)))
                        (throw (ex-info "Atlas inputs must be PNG files." {:path path})))
                      (.setInput reader stream true true)
                      {:name name :file file :width (.getWidth reader 0) :height (.getHeight reader 0)}
                      (finally (.dispose reader))))))))
          names)))

(defn- pack-pages [images width height margin]
  ;; Height-sorted shelves are deterministic and keep every source pixel intact.
  (let [images (sort-by (juxt (comp - :height) (comp - :width) :name) images)]
    (loop [remaining (vec images) cursor 0 x 0 y 0 row-height 0
           page {:regions [] :width 0 :height 0} pages []]
      (if (= cursor (count remaining))
        (cond-> pages (pos? (count (:regions page))) (conj page))
        (let [{image-width :width image-height :height :as image} (get remaining cursor)
              cell-width (long (+ image-width (* 2 margin)))
              cell-height (long (+ image-height (* 2 margin)))]
          (when (or (> cell-width width) (> cell-height height))
            (throw (ex-info "An image plus its padding exceeds the atlas page."
                            {:image (:name image) :width image-width :height image-height
                             :page-width width :page-height height})))
          (cond
            (> (+ x cell-width) width)
            (recur remaining cursor 0 (+ y row-height) 0 page pages)

            (> (+ y cell-height) height)
            (recur remaining cursor 0 0 0 {:regions [] :width 0 :height 0} (conj pages page))

            :else
            (recur remaining (inc cursor) (+ x cell-width) y (max row-height cell-height)
                   (-> page
                       (update :width max (+ x cell-width))
                       (update :height max (+ y cell-height))
                       (update :regions conj (assoc image :x (+ x margin) :y (+ y margin))))
                   pages)))))))

(defn- write-page! [^File target {:keys [width height regions]} extrude]
  (let [page (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)]
    (doseq [{:keys [file x y width height name source-width source-height scale]} regions]
      (let [^BufferedImage image (ImageIO/read ^File file)]
        (when-not (and image (= source-width (.getWidth image)) (= source-height (.getHeight image)))
          (throw (ex-info "An atlas image changed while exporting." {:image name})))
        (let [image (textures/resize-image image scale)
              pixels (.getRGB image 0 0 width height nil 0 width)]
          ;; Duplicate edge pixels around each rectangle. Transparent padding
          ;; outside this band separates regions for linear filtering.
          (dotimes [dy (+ height (* 2 extrude))]
            (dotimes [dx (+ width (* 2 extrude))]
              (let [source-x (max 0 (min (dec width) (- dx extrude)))
                    source-y (max 0 (min (dec height) (- dy extrude)))]
                (.setRGB page (+ x (- dx extrude)) (+ y (- dy extrude))
                         (aget ^ints pixels (+ source-x (* source-y width))))))))))
    (when-not (ImageIO/write page "png" target)
      (throw (ex-info "PNG encoder unavailable." {})))))

(defn- atlas-text [pages]
  (coll/join-to-string
    "\n"
    (mapv (fn [{:keys [file width height regions]}]
            (str file "\nsize: " width ", " height
                 "\nformat: RGBA8888\nfilter: Linear, Linear\nrepeat: none\npma: false\n"
                 (coll/join-to-string
                   (mapv (fn [{:keys [name x y width height]}]
                           (str name "\n  rotate: false\n  xy: " x ", " y
                                "\n  size: " width ", " height "\n  orig: " width ", " height
                                "\n  offset: 0, 0\n  index: -1\n")) regions))))
          pages)))

(defn export!
  "Write Spine .atlas and PNG pages into a NEW directory atomically.
  Options: :page-width/:page-height (1024), :padding (2), :extrude (1).
  Returned region names match every source attachment and numbered frame."
  [document project-root destination options]
  (let [{:keys [page-width page-height padding extrude]}
        (merge {:page-width 1024 :page-height 1024 :padding 2 :extrude 1} options)
        _ (when-not (and (integer? page-width) (integer? page-height)
                         (<= 1 page-width 8192) (<= 1 page-height 8192)
                         (integer? padding) (<= 0 padding 64)
                         (integer? extrude) (<= 0 extrude 16))
            (throw (ex-info "Atlas pages must be 1–8192 px; padding 0–64 and edge bleed 0–16." {})))
        root (.getCanonicalFile (io/file project-root))
        target (.getCanonicalFile (io/file destination))
        parent (.getParentFile target)
        _ (when (or (.exists target) (not (.isDirectory parent)))
            (throw (ex-info "Choose a new atlas directory with an existing parent." {:directory (str target)})))
        factor (textures/scale document)
        images (mapv (fn [{:keys [width height] :as image}]
                       (let [[w h] (textures/dimensions width height factor)]
                         (assoc image :source-width width :source-height height :width w :height h :scale factor)))
                     (source-images document root))
        packed (pack-pages images page-width page-height (+ padding extrude))
        pages (mapv (fn [index page] (assoc page :file (format "page-%03d.png" (inc index)))) (range (count packed)) packed)
        staging (.toFile (Files/createTempDirectory (.toPath parent) ".spine-atlas-" (make-array FileAttribute 0)))]
    (try
      (doseq [page pages] (write-page! (io/file staging (:file page)) page extrude))
      (spit (io/file staging "textures.atlas") (atlas-text pages))
      (Files/move (.toPath staging) (.toPath target) (make-array CopyOption 0))
      {:directory (.getPath target)
       :atlas (.getPath (io/file target "textures.atlas"))
       :pages (mapv #(select-keys % [:file :width :height]) pages)
       :regions (coll/into-> pages {}
                  (mapcat (fn [{:keys [file regions]}]
                            (mapv (fn [region] [(:name region) (assoc (select-keys region [:x :y :width :height]) :page file)]) regions))))
       :files (into [(io/file target "textures.atlas")] (mapv #(io/file target (:file %)) pages))}
      (finally
        (when (.exists staging) (fs/delete-directory! staging {:fail :silently}))))))
