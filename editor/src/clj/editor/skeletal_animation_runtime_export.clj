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

(ns editor.skeletal-animation-runtime-export
  "Guard exported Spine JSON against the bundled 4.2 runtime schema.
  Keep authoring provenance; migrate only compatible runtime exports."
  (:require [clojure.string :as string]
            [util.coll :as coll]))

(def ^:private inherit-modes #{"normal" "onlyTranslation" "noRotationOrReflection" "noScale" "noScaleOrReflection"})

(defn- fields [names]
  (coll/into-> (string/split names #" ") {} (map (fn [name] [name :value]))))

(defn- frame [names]
  [:array (assoc (fields (str "time " names)) "curve" :curve)])

(def ^:private scalar-frames (frame "value"))
(def ^:private bone-timelines
  {"rotate" scalar-frames "translatex" scalar-frames "translatey" scalar-frames
   "scalex" scalar-frames "scaley" scalar-frames "shearx" scalar-frames "sheary" scalar-frames
   "translate" (frame "x y") "scale" (frame "x y") "shear" (frame "x y")
   "inherit" [:array {"time" :value "inherit" inherit-modes}]})

(def ^:private slot-timelines
  {"attachment" [:array (fields "time name")]
   "rgba" (frame "color") "rgb" (frame "color") "alpha" scalar-frames
   "rgba2" (frame "light dark") "rgb2" (frame "light dark")})

(def ^:private animation-schema
  {"bones" [:named bone-timelines]
   "slots" [:named slot-timelines]
   "ik" [:named (frame "mix softness bendPositive compress stretch")]
   "transform" [:named (frame "mixRotate mixX mixY mixScaleX mixScaleY mixShearY")]
   "path" [:named {"position" scalar-frames "spacing" scalar-frames
                   "mix" (frame "mixRotate mixX mixY")}]
   "physics" [:named (assoc (coll/into-> ["inertia" "strength" "damping" "mass" "wind" "gravity" "mix"] {} (map (fn [name] [name scalar-frames])))
                       "reset" [:array {"time" :value}])]
   "attachments" [:named [:named [:named
                                  {"deform" (frame "offset vertices")
                                   "sequence" [:array {"time" :value "index" :value "delay" :value
                                                       "mode" #{"hold" "once" "loop" "pingpong" "onceReverse" "loopReverse" "pingpongReverse"}}]}]]]
   "drawOrder" [:array {"time" :value "offsets" [:array (fields "slot offset")]}]
   "events" [:array (fields "time name int float string volume balance")]})

(def ^:private attachment-schema
  (assoc (fields "name path x y rotation scaleX scaleY width height color uvs triangles vertices hull edges vertexCount closed constantSpeed lengths end parent skin timelines")
    "type" #{"region" "mesh" "linkedmesh" "path" "point" "boundingbox" "clipping"}
    "sequence" (fields "count start digits setupIndex")))

(def ^:private runtime-schema
  {"skeleton" (fields "hash spine x y width height referenceScale fps images audio")
   "bones" [:array (assoc (fields "name parent length x y rotation scaleX scaleY shearX shearY skin color icon visible") "inherit" inherit-modes)]
   "slots" [:array (assoc (fields "name bone attachment color dark visible") "blend" #{"normal" "additive" "multiply" "screen"})]
   "skins" [:array (assoc (fields "name bones ik transform path physics color") "attachments" [:named [:named attachment-schema]])]
   "ik" [:array (fields "name order skin bones target bendPositive compress stretch uniform mix softness")]
   "transform" [:array (fields "name order skin bones target local relative rotation x y scaleX scaleY shearY mixRotate mixX mixY mixScaleX mixScaleY mixShearY")]
   "path" [:array (assoc (fields "name order skin bones target rotation position spacing mixRotate mixX mixY")
                    "positionMode" #{"fixed" "percent"}
                    "spacingMode" #{"length" "fixed" "percent" "proportional"}
                    "rotateMode" #{"tangent" "chain" "chainScale"})]
   "physics" [:array (fields "name order skin bone x y rotate scaleX shearX limit fps inertia strength damping mass wind gravity mix inertiaGlobal strengthGlobal dampingGlobal massGlobal windGlobal gravityGlobal mixGlobal")]
   "events" [:named (fields "int float string audio volume balance")]
   "animations" [:named animation-schema]})

(defn- incompatible! [path]
  (throw (ex-info (str "Spine 4.2 runtime export cannot represent " (coll/join-to-string "/" path)
                       ". Remove the incompatible feature or export it from Spine 4.2.")
                  {:type :incompatible-spine-runtime :target-version "4.2" :path path})))

(defn- validate! [schema value path]
  (cond
    (= :value schema) nil
    (= :curve schema) (when-not (or (= "stepped" value)
                                    (and (vector? value) (pos? (count value))
                                         (zero? (mod (count value) 4)) (coll/every? number? value)))
                        (incompatible! path))
    (set? schema) (when-not (contains? schema value) (incompatible! path))
    (map? schema) (do
                    (when-not (map? value) (incompatible! path))
                    (doseq [[key child] value]
                      (if-let [child-schema (get schema key)]
                        (validate! child-schema child (conj path key))
                        (incompatible! (conj path key)))))
    (= :array (first schema)) (do
                                (when-not (vector? value) (incompatible! path))
                                (doseq [index (range (count value))]
                                  (validate! (second schema) (nth value index) (conj path index))))
    (= :named (first schema)) (do
                                (when-not (map? value) (incompatible! path))
                                (doseq [[name child] value]
                                  (validate! (second schema) child (conj path name))))))

(defn for-runtime
  "Target the installed Defold Spine 4.2 integration. Only the compatible 4.3
  subset is relabeled. Reject unsupported preserved data before writing assets."
  [source]
  (let [version (get-in source ["skeleton" "spine"])]
    (cond
      (and (string? version) (re-matches #"4\.2(?:\..*)?" version)) source
      (and (string? version) (re-matches #"4\.3(?:\..*)?" version))
      (do (validate! runtime-schema source [])
          (assoc-in source ["skeleton" "spine"] "4.2.0"))
      :else (incompatible! ["skeleton" "spine"]))))
