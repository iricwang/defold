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

(ns editor.ai-gui-preview
  (:require [clojure.string :as string]
            [dynamo.graph :as g]
            [editor.craft-project :as craft-project]
            [editor.defold-project :as project]
            [editor.gl.pass :as pass]
            [editor.resource :as resource]
            [editor.scene :as scene]
            [editor.workspace :as workspace]
            [internal.cache :as cache]
            [util.coll :as coll])
  (:import [java.awt.image BufferedImage]
           [javafx.embed.swing SwingFXUtils]))

(set! *warn-on-reflection* true)

(defn validate-path! [root path]
  (when-not (and (string? path) (string/starts-with? path "/") (string/ends-with? path ".gui")
                 (.isFile (craft-project/resource-file root path)))
    (throw (ex-info "请选择当前项目内已生成的 .gui 文件" {:path path})))
  path)

(defn render!
  "Render the actual editor resource graph on the FX thread. The temporary
  scene owns its GL context; neither selection nor resource undo is changed."
  [project app-view path]
  (let [workspace (project/workspace project)
        resource (or (workspace/find-resource workspace path)
                     (throw (ex-info "GUI 尚未载入编辑器，请加载外部修改后刷新" {:path path})))
        resource-node (project/get-resource-node project resource)
        resource-type (resource/resource-type resource)
        view-type (or (coll/first-where :make-preview-fn (:view-types resource-type))
                      (throw (ex-info "该资源不支持 GUI 预览" {:path path})))
        settings (g/node-value project :settings)
        width (max 1 (long (get settings ["display" "width"] 640)))
        height (max 1 (long (get settings ["display" "height"] 960)))
        scale (min 1.0 (/ 1280.0 (max width height)))
        opts (assoc (get (:view-opts resource-type) (:id view-type))
               :app-view app-view :project project :workspace workspace
               :select-fn (fn ignore-selection [_selection _op-seq]) :inherit-selection false)
        preview ((:make-preview-fn view-type) resource-node opts (long (* width scale)) (long (* height scale)))]
    (try
      (let [tree (g/node-value resource-node :node-tree)
            roots (g/node-value tree :nodes)
            ;; Simulate page activation in a private basis. Keep hidden child
            ;; states and never write this simulated state to disk.
            report (g/transact {:dry-run true}
                     (coll/into-> roots [] (mapcat #(g/set-property % :enabled true))))
            context (g/make-evaluation-context {:basis (:basis report)
                                                :cache (cache/cache-invalidate (g/cache) (:outputs-modified report))})
            renderables (g/node-value preview :all-renderables context)
            frame (when-not (g/error-value? renderables)
                    (scene/produce-frame {:all-renderables (dissoc renderables pass/outline)
                                          :viewport (g/node-value preview :viewport context)
                                          :pass->render-args (g/node-value preview :pass->render-args context)
                                          :drawable (g/node-value preview :drawable context)}))]
        (when-not frame
          (throw (ex-info "GUI 渲染失败，请检查资源依赖" {:path path})))
        (let [^BufferedImage frame frame
              flipped (BufferedImage. (.getWidth frame) (.getHeight frame) BufferedImage/TYPE_INT_ARGB)
              graphics (.createGraphics flipped)]
          (try
            (.drawImage graphics frame 0 (.getHeight frame) (.getWidth frame) (- (.getHeight frame)) nil)
            (SwingFXUtils/toFXImage flipped nil)
            (finally (.dispose graphics) (.flush flipped)))))
      (finally
        (try
          (when-let [dispose! (:dispose-preview-fn view-type)] (dispose! preview))
          (finally (g/transact {:undoable false} (g/delete-node preview))))))))
