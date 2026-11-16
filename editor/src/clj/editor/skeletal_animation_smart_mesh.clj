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

(ns editor.skeletal-animation-smart-mesh
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.fs :as fs]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-io :as animation-io]
            [editor.skeletal-animation-mesh :as mesh]
            [util.coll :as coll])
  (:import [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(def ^:private trace-script (io/resource "spine-mesh-trace.py" (clojure.lang.RT/baseLoader)))

(def ^:private auto-rig-script (io/resource "spine-auto-rig.py" (clojure.lang.RT/baseLoader)))

(def ^:private bridge-script (io/resource "spine-smart-mesh-bridge.py" (clojure.lang.RT/baseLoader)))

(defn tool-directory [project-root]
  (or (when-let [path (System/getenv "DEFOLD_SPINE_SMART_MESH")] (io/file path))
      (loop [directory (.getCanonicalFile (io/file project-root))]
        (when directory
          (let [candidate (io/file directory "tools" "spine-smart-mesh")]
            (cond
              (.isFile (io/file directory "mesh.py")) directory
              (.isFile (io/file candidate "mesh.py")) candidate
              :else (recur (.getParentFile directory))))))
      (throw (ex-info "找不到 spine-smart-mesh：请设置 DEFOLD_SPINE_SMART_MESH，或放到工程上级的 tools 目录。" {}))))

(defn run-tool! [project-root request]
  (let [tool (if-let [path (get request "tool")] (io/file path) (tool-directory project-root))
        python (io/file tool ".venv" "bin" "python")
        directory (.toFile (Files/createTempDirectory "defold-smart-mesh-" (make-array java.nio.file.attribute.FileAttribute 0)))
        input (io/file directory "request.json")
        output (io/file directory "result.json")
        script (io/file directory "bridge.py")
        log (io/file directory "process.log")]
    (try
      (when-not (.isFile python) (throw (ex-info "智能布网工具的 Python 环境不存在。" {})))
      (spit input (json/write-str request))
      (with-open [source (io/input-stream bridge-script)]
        (io/copy source script))
      (when (= "rig-guide" (get request "operation"))
        (with-open [source (io/input-stream auto-rig-script)]
          (io/copy source (io/file directory "spine-auto-rig.py"))))
      (when (get request "trace")
        (with-open [source (io/input-stream trace-script)]
          (io/copy source (io/file directory "spine-mesh-trace.py"))))
      (let [process (.start (doto (ProcessBuilder. ^java.util.List [(str python) (str script) (str tool) (str input) (str output)])
                              (.redirectErrorStream true) (.redirectOutput log)))]
        (when-not (.waitFor process 120 TimeUnit/SECONDS)
          (with-open [children (.descendants (.toHandle process))]
            (.forEach children (reify java.util.function.Consumer (accept [_ child] (.destroyForcibly ^java.lang.ProcessHandle child)))))
          (.destroyForcibly process)
          (.waitFor process)
          (throw (ex-info "智能布网超时，未修改文档。" {})))
        (when-not (zero? (.exitValue process))
          (throw (ex-info (str "智能布网失败：" (slurp log)) {})))
        (let [result (json/read-str (slurp output))]
          (when-let [message (get result "error")] (throw (ex-info message {})))
          result))
      (finally
        (fs/delete-directory! directory {:fail :silently})))))

(defn open-project! [file project-root]
  (let [tool (try (tool-directory project-root)
                  (catch clojure.lang.ExceptionInfo _ (tool-directory (.getParentFile (io/file file)))))
        result (run-tool! project-root {"operation" "open" "file" (str file) "tool" (str tool)})]
    (update (animation-io/read-spine-source! file project-root (get result "source")) :document assoc
            :smart-mesh-tool (str tool)
            :native-project {:codec "spine-cli" :file (str file) :version (get result "version")})))

(defn requires-cli? [document]
  (or (= "spine-cli" (get-in document [:native-project :codec]))
      (string/starts-with? (get-in (json/read-str (or (:spine-source document) "{}")) ["skeleton" "spine"] "") "4.3.")))

(defn write-project! [document project-root target]
  (when (> (count (:workspace-skeletons document)) 1)
    (throw (ex-info "Spine CLI 保存暂时仅支持单骨架；请分别保存各骨架。" {})))
  (let [target (.getCanonicalFile (io/file target))]
    (when (or (.exists target) (not (string/ends-with? (string/lower-case (.getName target)) ".spine")))
      (throw (ex-info "请选择新的 .spine 文件；不会覆盖已有工程。" {})))
    (run-tool! project-root {"operation" "save" "file" (str target)
                             "tool" (:smart-mesh-tool document)
                             "root" (str project-root)
                             "source" (json/read-str (data/export-spine-json document))})
    target))

(defn- generate-region! [document project-root slot-id settings source]
  (let [slot (coll/first-where #(= slot-id (:id %)) (:slots document))
        attachment (:attachment slot)
        _ (when-not (= "region" (:type attachment))
            (throw (ex-info "请选择普通图片附件；已有网格和变形动画不会自动重建。" {})))
        source (or source (data/export-spine-data document))
        key [(:spine-skin slot "default") slot-id (:name attachment)]
        result (run-tool! project-root {"operation" "mesh" "trace" (some? settings) "config" settings "source" source "key" key "root" (str project-root) "tool" (:smart-mesh-tool document)})
        updated (update source "skins"
                        (fn replace-skin-attachment [skins]
                          (mapv (fn [skin]
                                  (if (= (first key) (get skin "name"))
                                    (assoc-in skin ["attachments" slot-id (:name attachment)] (get result "attachment")) skin)) skins)))
        imported (data/select-skin (data/import-spine-json (json/write-str updated)) (get document :active-skin "default"))
        changed (coll/first-where #(= slot-id (:id %)) (:slots imported))]
    (when-not (= "mesh" (get-in changed [:attachment :type]))
      (throw (ex-info "工具返回的附件无法转换为可编辑网格。" {:key key})))
    {:document (data/update-slot document slot-id {:attachment (:attachment changed)})
     :report (get result "report")}))

(defn- rebuild! [document project-root slot settings generated source]
  (let [before (:attachment slot)
        slot-id (:id slot)
        skin (get slot :spine-skin "default")
        name (get before :name slot-id)
        key [skin slot-id name]
        source (or source (data/export-spine-data document))
        skin-index (reduce-kv (fn [_ i value] (when (= skin (get value "name")) (reduced i))) nil (get source "skins"))
        raw (get-in source ["skins" skin-index "attachments" slot-id name])
        _ (when (:sequence before) (throw (ex-info "序列贴图暂不支持重新智能布网。" {})))
        ;; The external tool only proposes UVs/triangles. Its temporary rigid
        ;; bindings never replace the user's skeleton, weights or constraints.
        scratch {"skeleton" {"spine" "4.2.43"}
                 "bones" [{"name" "root"}]
                 "slots" [{"name" slot-id "bone" "root" "attachment" name}]
                 "skins" [{"name" skin "attachments"
                           {slot-id {name (merge (select-keys raw ["name" "path" "color"])
                                                 {"type" "region" "width" (max 1.0 (:width before 1.0))
                                                  "height" (max 1.0 (:height before 1.0))})}}}]}
        generated (or generated (run-tool! project-root {"operation" "mesh" "trace" (some? settings) "config" settings "source" scratch "key" key "root" (str project-root) "tool" (:smart-mesh-tool document)}))
        topology (get generated "attachment")
        worlds (data/world-transforms (:bones document))
        binding {:slot-transform (get worlds (:bone slot)) :bone-transforms worlds}
        raw-edges (get topology "edges" [])
        _ (when-not (and (vector? raw-edges) (even? (count raw-edges))
                         (coll/every? #(and (integer? %) (even? %) (<= 0 %)) raw-edges))
            (throw (ex-info "工具返回的网格边索引无效，未修改文档。" {})))
        edges (mapv (fn [[a b]] [(quot a 2) (quot b 2)]) (partitionv 2 raw-edges))
        transferred (mesh/transfer-topology before (get topology "uvs") (get topology "triangles") edges binding)
        layout (mesh/spine-export-layout (:mesh transferred))
        after (assoc (:mesh layout) :spine-export-hull (:hull layout) :spine-export-edges (:edges layout))
        mapping (mapv (fn [sources]
                        (reduce-kv (fn [result index coefficient]
                                     (merge-with + result (coll/map-vals #(* coefficient %) (get (:vertex-map transferred) index)))) {} sources)) (:vertex-map layout))
        edit {:mesh after :vertex-map mapping :deform-policy :barycentric}
        ;; Export may have permuted the old hull vertices. Raw timelines must
        ;; use that exported order, while editable FFD uses the original order.
        canonical-before (data/decode-spine-attachment name raw (:bones document) worlds (:bone slot))
        raw-mapping (:vertex-map (mesh/transfer-topology canonical-before (:uvs after) (:triangles after) (:constrained-edges after) binding))
        links (coll/into-> (get source "skins") []
                (mapcat (fn [skin]
                          (coll/into-> (get-in skin ["attachments" slot-id]) []
                            (keep (fn [[name raw]]
                                    (when (= "linkedmesh" (get raw "type"))
                                      {:key [(get skin "name") slot-id name]
                                       :parent [(get raw "skin" "default") slot-id (get raw "parent")]})))))))
        affected (loop [keys #{key}]
                   (let [expanded (coll/into-> links keys (filter #(contains? keys (:parent %))) (map :key))]
                     (if (= keys expanded) keys (recur expanded))))
        remap-frame (fn remap-frame [frame]
                      (let [size (data/deform-size canonical-before)
                            values (get frame "vertices" [])
                            offset (get frame "offset" 0)]
                        (when-not (and (integer? offset) (<= 0 offset) (vector? values) (<= (+ offset (count values)) size))
                          (throw (ex-info "变形关键帧与原网格不匹配，未修改文档。" {})))
                        (-> frame
                            (dissoc "offset")
                            (assoc "vertices" (mesh/remap-deform canonical-before after raw-mapping
                                                                 (reduce-kv (fn [result i value] (assoc result (+ offset i) value))
                                                                            (vec (repeat size 0.0)) values))))))
        frame-count (atom 0)
        migrate (fn migrate [animation [skin slot name]]
                  (reduce (fn migrate-track [animation path]
                            (if-let [frames (get-in animation path)]
                              (do (swap! frame-count + (count frames))
                                  (assoc-in animation path (mapv remap-frame frames)))
                              animation))
                          animation [["attachments" skin slot name "deform"] ["deform" skin slot name]]))
        bone-indices (coll/into-> (:bones document) {} (map-indexed (fn [i bone] [(:id bone) i])))
        encoded (if-let [weights (:weights after)]
                  (coll/into-> weights []
                    (mapcat (fn [influences]
                              (coll/into-> influences [(count influences)]
                                (mapcat (fn [{:keys [bone x y weight]}] [(get bone-indices bone) x y weight]))))))
                  (:vertices after))
        source (-> source
                   (assoc-in ["skins" skin-index "attachments" slot-id name]
                             (assoc raw "uvs" (:uvs after) "triangles" (:triangles after) "vertices" encoded
                                    "hull" (:hull layout) "edges" (:edges layout)))
                   (update "animations" #(coll/map-vals (fn migrate-animation [animation] (reduce migrate animation affected)) %)))
        slot-index (reduce-kv (fn [_ i candidate] (when (= slot-id (:id candidate)) (reduced i))) nil (:slots document))
        updated (-> (data/apply-mesh-edit document slot-id edit)
                    (assoc :spine-source (json/write-str source))
                    (update-in [:slots slot-index :attachment] dissoc :spine-topology-edited))
        exported (data/export-spine-data updated)]
    ;; Validate the complete export before the UI commits its one undo step.
    (data/import-spine-json (json/write-str exported))
    {:document updated ::exported exported
     :report (merge (get generated "report")
                    {"rebuild" true "vertices" (quot (count (:uvs after)) 2)
                     "triangles" (quot (count (:triangles after)) 3)
                     "deform_keys_preserved" @frame-count "linked_meshes_preserved" (dec (count affected))
                     "outside_vertices" (:outside-vertices transferred)})}))

(defn generate! [document project-root slot-id]
  (let [slot (coll/first-where #(= slot-id (:id %)) (:slots document))]
    (case (get-in slot [:attachment :type])
      "region" (generate-region! document project-root slot-id nil nil)
      "mesh" (rebuild! document project-root slot nil nil nil)
      (throw (ex-info "请选择普通图片或独立网格附件；链接网格请选中其源网格。" {})))))

(defn trace! [document project-root slot-id settings]
  (let [slot (coll/first-where #(= slot-id (:id %)) (:slots document))]
    (case (get-in slot [:attachment :type])
      "region" (generate-region! document project-root slot-id settings nil)
      "mesh" (rebuild! document project-root slot settings nil nil)
      (throw (ex-info "请选择图片或源网格附件进行描绘。" {})))))

(defn revise-trace [result slot-id uvs]
  (let [document (:document result)
        slot (coll/first-where #(= slot-id (:id %)) (:slots document))
        n (quot (count uvs) 2)
        topology (:mesh (mesh/triangulate {:type "mesh" :uvs uvs :vertices uvs :triangles []} (vec (range n))))
        revised (rebuild! document nil slot nil
                          {"attachment" {"uvs" uvs "triangles" (:triangles topology)
                                         "edges" (coll/into-> (range n) [] (mapcat #(vector (* 2 %) (* 2 (mod (inc %) n)))))}
                           "report" (assoc (:report result) "manual" true)} (::exported result))]
    (assoc revised :preview (:attachment (coll/first-where #(= slot-id (:id %)) (:slots (:document revised)))))))

(defn refine-trace
  "Transfer a proposed UV topology through the same FFD/linked-mesh remapper."
  [result slot-id topology]
  (let [document (:document result)
        slot (coll/first-where #(= slot-id (:id %)) (:slots document))]
    (rebuild! document nil slot nil
              {"attachment" {"uvs" (:uvs topology) "triangles" (:triangles topology)
                             "edges" (coll/into-> (:constrained-edges topology) [] (mapcat #(mapv (fn [i] (* 2 i)) %)))}
               "report" (:report result)} (::exported result))))

(defn trace-proposer
  "A preview owns its immutable source and a bounded result cache. Image edits
  invalidate proposals; closing the panel releases the retained documents."
  [document project-root slot-id]
  (let [slot (coll/first-where #(= slot-id (:id %)) (:slots document))
        file (io/file project-root (string/replace (get-in slot [:attachment :path]) #"^/" ""))
        source (delay (data/export-spine-data document))
        cache (atom [])]
    (fn propose-trace [settings]
      (let [key [settings (.lastModified file) (.length file)]]
        (if-let [cached (coll/first-where #(= key (:key %)) @cache)]
          (:result cached)
          (let [result (case (get-in slot [:attachment :type])
                         "region" (generate-region! document project-root slot-id settings @source)
                         "mesh" (rebuild! document project-root slot settings nil @source)
                         (throw (ex-info "请选择图片或源网格附件进行描绘。" {})))
                result (assoc result :preview (:attachment (coll/first-where #(= slot-id (:id %)) (:slots (:document result)))))]
            (swap! cache #(coll/into-> % [{:key key :result result}] (take 3)))
            result))))))
