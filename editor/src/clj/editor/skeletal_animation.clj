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

(ns editor.skeletal-animation
  (:require [dynamo.graph :as g]
            [editor.code.resource :as code.resource]
            [editor.localization :as localization]
            [editor.resource-node :as resource-node]
            [editor.skeletal-animation-data :as data]
            [editor.workspace :as workspace]))

(set! *warn-on-reflection* true)

(g/defnode SkeletalAnimationNode
  (inherits resource-node/ResourceNode)

  (property document g/Any
            (default (data/new-document "skeleton"))
            (dynamic visible (g/constantly false)))

  (output save-value g/Any :cached (g/fnk [document] document)))

(defn- read-document [_read-opts _owner-resource readable]
  ;; Source parsing is an edge: project loading translates malformed documents
  ;; to resource errors and can recover when the source file is corrected.
  (data/read-json (slurp readable)))

(defn- load-document [_load-opts {:keys [node-id source-value]}]
  (g/set-property node-id :document source-value))

(defn- search-value [node-id resource evaluation-context]
  (let [lines (g/node-value node-id :lines evaluation-context)]
    (if (g/error-value? lines)
      lines
      {:resource resource
       :loaded-lines lines})))

(defn register-resource-types [workspace]
  (workspace/register-resource-type workspace
    :ext "skeleton"
    :textual? true
    :language "json"
    :node-type SkeletalAnimationNode
    :read-fn read-document
    :write-fn data/write-json
    :load-fn load-document
    :search-fn code.resource/search-fn
    :search-value-fn search-value
    :label (localization/message "resource.type.skeleton")
    :category (localization/message "resource.category.editor")
    :icon "icons/32/Icons_24-AT-Animation.png"
    :icon-class :design
    :test-info {:type :skeletal-animation}
    :view-types [:skeletal-animation :text]))
