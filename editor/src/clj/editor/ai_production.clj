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


(ns editor.ai-production
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.craft-project :as craft-project]
            [editor.protobuf :as protobuf]
            [util.coll :as coll]
            [util.digest :as digest])
  (:import [com.dynamo.gamesys.proto AtlasProto$Atlas Gui$SceneDesc]
           [java.awt.image BufferedImage]
           [java.io File]
           [java.nio.file CopyOption Files StandardCopyOption]
           [java.util BitSet UUID]
           [javax.imageio ImageIO]))

(set! *warn-on-reflection* true)

(defn read-image
  ^BufferedImage [file]
  (when-not (and (.isFile (io/file file)) (<= (.length (io/file file)) (* 64 1024 1024)))
    (throw (ex-info "图片不存在或超过 64 MB" {})))
  (with-open [stream (ImageIO/createImageInputStream (io/file file))]
    (let [readers (ImageIO/getImageReaders stream)]
      (when-not (.hasNext readers) (throw (ex-info "当前切图支持 PNG、JPEG、GIF 和 BMP" {})))
      (let [reader ^javax.imageio.ImageReader (.next readers)]
        (try
          (.setInput reader stream)
          (let [w (.getWidth reader 0) h (.getHeight reader 0)]
            (when-not (and (<= 1 w 8192) (<= 1 h 8192) (<= (* (long w) h) 16777216))
              (throw (ex-info "图片最大 8192 像素，且不超过 1600 万像素" {})))
            (.read reader 0))
          (finally (.dispose reader)))))))

(defn slug [value fallback]
  (let [value (-> (str value) string/lower-case (string/replace #"[^a-z0-9]+" "_") (string/replace #"^_+|_+$" ""))]
    (if (string/blank? value) fallback (subs value 0 (min 48 (count value))))))

(defn normalize-plan [plan width height]
  (when-not (and (= 1 (:version plan)) (= width (:imageWidth plan)) (= height (:imageHeight plan))
                 (vector? (:regions plan)) (<= 1 (count (:regions plan)) 256))
    (throw (ex-info "拆分清单必须匹配原图尺寸，并包含 1–256 个区域" {})))
  (let [module (slug (:module plan) "screen")
        used (volatile! #{})
        regions
        (mapv (fn [index region]
                (let [{:keys [x y width height]} region
                      role (if (contains? #{"button" "icon" "panel" "background" "decoration" "component"} (:role region)) (:role region) "component")
                      base (str module "_" role "_" (slug (:name region) (format "%03d" (inc index))) "_" (slug (:state region) "normal"))
                      id (loop [n 1] (let [id (if (= n 1) base (str base "_" n))] (if (contains? @used id) (recur (inc n)) id)))]
                  (when-not (and (coll/every? integer? [x y width height]) (<= 0 x) (<= 0 y) (pos? width) (pos? height)
                                 (<= (+ x width) (:imageWidth plan)) (<= (+ y height) (:imageHeight plan)))
                    (throw (ex-info (str "区域 " (inc index) " 的坐标或尺寸超出原图") {})))
                  (vswap! used conj id)
                  {:id id :name (or (:name region) id) :role role :state (slug (:state region) "normal")
                   :x x :y y :width width :height height}))
              (range (count (:regions plan))) (:regions plan))]
    (when (> (reduce + 0 (mapv #(* (:width %) (:height %)) regions)) 33554432)
      (throw (ex-info "切片总面积超过 3200 万像素，请减少重复或重叠区域" {})))
    {:version 1 :module module :imageWidth width :imageHeight height :regions regions}))

(defn detect-regions [^BufferedImage image]
  (let [w (.getWidth image) h (.getHeight image) n (* w h)
        pixels (.getRGB image 0 0 w h nil 0 w)
        transparent (loop [i 0] (cond (= i n) false (< (unsigned-bit-shift-right (aget pixels i) 24) 8) true :else (recur (inc i))))]
    (if-not transparent
      []
      (let [seen (BitSet. n) queue (int-array n)]
        (loop [index 0 regions []]
          (if (or (= index n) (>= (count regions) 256))
            regions
            (if (or (.get seen index) (< (unsigned-bit-shift-right (aget pixels index) 24) 8))
              (recur (inc index) regions)
              (let [_ (.set seen index) _ (aset-int queue 0 index)
                    [left top right bottom count-pixels]
                    (loop [head 0 tail 1 left w top h right 0 bottom 0]
                      (if (= head tail)
                        [left top right bottom tail]
                        (let [p (aget queue head) x (rem p w) y (quot p w)
                              end (reduce (fn [tail neighbor]
                                            (if (and (<= 0 neighbor (dec n)) (not (.get seen neighbor))
                                                     (>= (unsigned-bit-shift-right (aget pixels neighbor) 24) 8))
                                              (do (.set seen neighbor) (aset-int queue tail neighbor) (inc tail)) tail))
                                          tail (cond-> [(- p w) (+ p w)] (pos? x) (conj (dec p)) (< x (dec w)) (conj (inc p))))]
                          (recur (inc head) (long end) (min left x) (min top y) (max right x) (max bottom y)))))
                    region {:name (format "component_%03d" (inc (count regions))) :role "component" :state "normal"
                            :x left :y top :width (inc (- right left)) :height (inc (- bottom top))}]
                (recur (inc index) (if (< count-pixels 12) regions (conj regions region)))))))))))

(defn plan-from-snapshot [snapshot]
  (reduce (fn [found item]
            (if (and (= "agentMessage" (:type item)) (not= "analysis" (:phase item)))
              (let [text (:text item)
                    encoded (or (second (re-find #"(?s)```(?:json)?\s*(\{.*?\})\s*```" (or text ""))) text)]
                (try
                  (let [value (json/read-str encoded :key-fn keyword)]
                    (if (and (= "defold-gui-split" (:kind value)) (= 1 (:version value))) value found))
                  (catch Exception _ found))) found))
          nil (coll/into-> (get-in snapshot [:thread :turns]) [] (filter #(= "completed" (:status %))) (mapcat :items))))

(defn split-prompt [module width height source-key]
  (str "请分析附件中的已选定 GUI 候选图，提出可调整的资源裁剪方案。不要执行代码或修改任何文件。"
       "坐标原点是图片左上角，全部坐标为原图整数像素。只标注你能识别的完整区域，不虚构被遮挡像素，不把完整页面冒充已去背景组件。"
       "name 使用描述功能的英文 snake_case，role 取 button/icon/panel/background/decoration/component，state 使用 normal。"
       "不要用等分网格代替语义拆分。按从背景到前景的绘制顺序排列，最多 128 项。只返回一个 JSON 代码块，格式：\n"
       (json/write-str {:kind "defold-gui-split" :version 1 :sourceKey source-key :module module :imageWidth width :imageHeight height
                        :regions [{:name "buy" :role "button" :state "normal" :x 0 :y 0 :width 1 :height 1}]})))

(defn gui-data [plan atlas-path display-width display-height]
  (let [scale (min (/ (double display-width) (:imageWidth plan)) (/ (double display-height) (:imageHeight plan)))
        left (/ (- display-width (* scale (:imageWidth plan))) 2.0)
        bottom (/ (- display-height (* scale (:imageHeight plan))) 2.0)]
    {:material "/builtins/materials/gui.material" :adjust-reference :adjust-reference-parent
     :max-nodes (max 512 (inc (count (:regions plan))))
     :textures [{:name "components" :texture atlas-path}]
     :nodes (mapv (fn [{:keys [id x y width height]}]
                    {:id id :type :type-box :texture (str "components/" id) :size-mode :size-mode-manual
                     :position [(+ left (* scale (+ x (/ width 2.0)))) (+ bottom (* scale (- (:imageHeight plan) y (/ height 2.0)))) 0.0 1.0]
                     :size [(* scale width) (* scale height) 0.0 0.0]
                     :scale [1.0 1.0 1.0 1.0] :color [1.0 1.0 1.0 1.0]
                     :pivot :pivot-center :adjust-mode :adjust-mode-fit}) (:regions plan))}))

(defn validate-resource-text [kind text]
  (let [repairs (volatile! [])
        aliases (if (= kind :gui)
                  {"perimeter_vertices" "perimeterVertices" "fill_angle" "pieFillAngle" "inner_radius" "innerRadius"}
                  {})
        repaired (string/replace text
                                 #"(?s)\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'|#[^\n]*|//[^\n]*|/\*.*?\*/|\b(?:perimeter_vertices|fill_angle|inner_radius)\s*(?=:)"
                                 (fn repair-field [token]
                                   (if-let [replacement (get aliases (string/trim token))]
                                     (do (vswap! repairs conj {:from (string/trim token) :to replacement}) replacement)
                                     token)))
        cls (case kind :gui Gui$SceneDesc :atlas AtlasProto$Atlas)
        data (try (protobuf/str->map-with-defaults cls repaired)
                  (catch Exception e
                    (throw (ex-info (str (name kind) " 格式校验失败：" (.getMessage e)) {:kind kind} e))))]
    {:text repaired :data data :repairs @repairs}))

(defn validate-bundle! [stage prefix gui-name atlas-name]
  (let [gui-file (io/file stage gui-name)
        atlas-file (io/file stage atlas-name)
        gui (validate-resource-text :gui (slurp gui-file))
        atlas (validate-resource-text :atlas (slurp atlas-file))
        nodes (get-in gui [:data :nodes])
        ids (mapv :id nodes)
        images (get-in atlas [:data :images])
        frames (mapv #(-> (:image %) (string/split #"/") last (string/replace #"\.[^.]+$" "")) images)
        textures (get-in gui [:data :textures])]
    (when-not (and (coll/not-empty nodes) (= (count ids) (count (set ids)))
                   (coll/every? #(not (string/blank? %)) ids)
                   (<= (count nodes) (get-in gui [:data :max-nodes]))
                   (= (count frames) (count (set frames))))
      (throw (ex-info "GUI 节点编号或 atlas 资源名称重复、为空或超出节点上限" {})))
    (doseq [{:keys [image]} images]
      (when-not (string/starts-with? image (str prefix "/"))
        (throw (ex-info "atlas 图片必须属于当前生成模块" {:image image})))
      (let [file (io/file stage (subs image (inc (count prefix))))]
        (when-not (.startsWith (.toPath (.getCanonicalFile file)) (.toPath (.getCanonicalFile (io/file stage))))
          (throw (ex-info "atlas 图片路径越界" {:image image})))
        (.flush (read-image file))))
    (doseq [{:keys [texture]} textures]
      (when-not (= texture (str prefix "/" atlas-name))
        (throw (ex-info "GUI 引用了未生成的 atlas" {:texture texture}))))
    (let [allowed (coll/into-> textures #{} (mapcat (fn [texture] (mapv #(str (:name texture) "/" %) frames))))
          parents (coll/into-> nodes {} (map (juxt :id :parent)))]
      (doseq [{:keys [id parent texture]} nodes]
        (when (and (not (string/blank? texture)) (not (contains? allowed texture)))
          (throw (ex-info "GUI 纹理在 atlas 中不存在" {:node id :texture texture})))
        (loop [parent parent visited #{id}]
          (when-not (string/blank? parent)
            (when (or (contains? visited parent) (not (contains? parents parent)))
              (throw (ex-info "GUI 父节点不存在或形成循环" {:node id :parent parent})))
            (recur (get parents parent) (conj visited parent))))))
    ;; Only persist repairs after the whole bundle has passed validation.
    (spit gui-file (:text gui))
    (spit atlas-file (:text atlas))
    {:status "passed" :repairs (into (:repairs gui) (:repairs atlas))}))

(defn publish! [root source-file proposed-plan provenance display-size]
  (when-not (and (= 2 (count display-size))
                 (coll/every? #(and (number? %) (Double/isFinite (double %)) (pos? %)) display-size))
    (throw (ex-info "GUI 预览尺寸必须为正数" {})))
  (let [image (read-image source-file)
        plan (normalize-plan proposed-plan (.getWidth image) (.getHeight image))
        revision (str "v_" (string/replace (str (UUID/randomUUID)) "-" ""))
        prefix (str "/ui/ai_generated/" (:module plan) "/" revision)
        target (craft-project/resource-file root prefix)
        stage (io/file root ".internal" "ai-production" revision)
        atlas-path (str prefix "/" (:module plan) ".atlas")
        gui-path (str prefix "/" (:module plan) ".gui")
        source-hash (with-open [stream (io/input-stream source-file)] (digest/stream->sha256-hex stream))]
    (when-not (.isFile (io/file root "game.project")) (throw (ex-info "目标目录不是 Defold 项目" {})))
    (when-not (.startsWith (.toPath (.getCanonicalFile stage)) (.toPath (.getCanonicalFile (io/file root))))
      (throw (ex-info "生产暂存目录不能位于项目外" {})))
    (Files/createDirectories (.toPath (.getParentFile stage)) (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/createDirectory (.toPath stage) (make-array java.nio.file.attribute.FileAttribute 0))
    (try
      (let [assets (mapv (fn [{:keys [id x y width height] :as region}]
                           (let [relative (str "images/" id ".png")
                                 file (io/file stage relative)
                                 crop (.getSubimage image x y width height)]
                             (io/make-parents file)
                             (when-not (ImageIO/write crop "png" file) (throw (ex-info "PNG 写入失败" {})))
                             (assoc region :file (str prefix "/" relative)
                                    :sha256 (with-open [stream (io/input-stream file)] (digest/stream->sha256-hex stream))))) (:regions plan))
            atlas {:images (mapv #(hash-map :image (:file %)) assets) :margin 0 :inner-padding 2 :extrude-borders 2}
            gui (gui-data plan atlas-path (first display-size) (second display-size))
            manifest (assoc plan :status "generated-review-required" :revision revision :sourceHash source-hash :source provenance
                            :sourceSnapshot (str prefix "/source/candidate.png") :extraction "rectangular-crop-preserve-source-alpha" :displaySize display-size :assets assets
                            :atlas atlas-path :gui gui-path
                            :notes ["矩形裁剪保留源像素，不能恢复遮挡区域或自动移除文字。" "节点可编辑；运行逻辑、响应式布局和九宫格需要后续设置。"])]
        (let [source-copy (io/file stage "source" "candidate.png")]
          (io/make-parents source-copy)
          (ImageIO/write image "png" source-copy))
        (spit (io/file stage (str (:module plan) ".atlas")) (protobuf/map->str AtlasProto$Atlas atlas))
        (spit (io/file stage (str (:module plan) ".gui")) (protobuf/map->str Gui$SceneDesc gui))
        (let [validation (validate-bundle! stage prefix (str (:module plan) ".gui") (str (:module plan) ".atlas"))]
          (spit (io/file stage "manifest.json") (json/write-str (assoc manifest :validation validation))))
        (io/make-parents target)
        ;; Revalidate the destination; never replace existing assets.
        (craft-project/resource-file root prefix)
        (Files/move (.toPath stage) (.toPath target) (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))
        {:gui gui-path :atlas atlas-path :manifest (str prefix "/manifest.json") :directory prefix :count (count assets)
         :paths (coll/into-> assets #{gui-path atlas-path (str prefix "/manifest.json") (str prefix "/source/candidate.png")} (map :file))})
      (finally
        (.flush image)
        (when (.exists stage)
          (doseq [^File file (reverse (file-seq stage))] (Files/deleteIfExists (.toPath file))))))))
