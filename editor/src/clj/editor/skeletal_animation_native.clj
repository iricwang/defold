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

(ns editor.skeletal-animation-native
  "Independent Spine 4.2.43 project support for a verified authoring subset.
  The graph codec covers bones, regions, meshes, weights, supported timelines,
  IK, transform, path and physics constraints. Unknown graph structures fail explicitly.
  A separate preservation API retains opaque streams for limited bone edits."
  (:require [clojure.string :as string]
            [util.coll :as coll])
  (:import [java.io ByteArrayOutputStream DataOutputStream]
           [java.nio ByteBuffer ByteOrder]
           [java.util Arrays]
           [java.util.zip Deflater Inflater]))

(set! *warn-on-reflection* true)

(def ^:private schema :spine-4.2.43-project-40-bones)
(def ^:private max-packed-size (* 32 1024 1024))
(def ^:private max-payload-size (* 64 1024 1024))
(def ^:private bone-tags [14 32 1 13 30 2 25 5 23 3 8 26 11 29 12 17 31 6 27 24 7 10 9 4 0 28 126])
(def ^:private float-field-names
  {4 :unknown-4 5 :unknown-5 6 :unknown-6 7 :unknown-7 8 :unknown-8
   9 :length 10 :x 11 :y 12 :rotation 13 :scale-x 14 :scale-y
   23 :shear-x 24 :unknown-24 25 :shear-y 26 :unknown-26})
(def editable-properties #{:length :x :y :rotation :scale-x :scale-y :shear-x :shear-y})

(defn- unsupported! [message data]
  (throw (ex-info message (assoc data :type ::unsupported))))

(defn- unpack
  ^bytes [^bytes packed]
  (let [inflater (Inflater. true)
        buffer (byte-array 8192)
        output (ByteArrayOutputStream.)]
    (try
      (.setInput inflater packed)
      (loop []
        (if (.finished inflater)
          (when (pos? (.getRemaining inflater))
            (unsupported! "The native project contains trailing compressed data." {}))
          (let [n (.inflate inflater buffer)]
            (when (zero? n)
              (unsupported! "The native project is truncated or uses an unsupported compression stream." {}))
            (when (> (+ (.size output) n) max-payload-size)
              (unsupported! "The native project exceeds the supported decompressed size." {}))
            (.write output buffer 0 n)
            (recur))))
      (.toByteArray output)
      (finally (.end inflater)))))

(defn- pack
  ^bytes [^bytes payload]
  (let [deflater (Deflater. 6 true)
        buffer (byte-array 8192)
        output (ByteArrayOutputStream.)]
    (try
      (.setInput deflater payload)
      (.finish deflater)
      (while (not (.finished deflater))
        (let [n (.deflate deflater buffer)]
          (.write output buffer 0 n)))
      (.toByteArray output)
      (finally (.end deflater)))))

(defn- unsigned-byte [^ByteBuffer input]
  (bit-and 255 (.get input)))

(defn- read-varint [^ByteBuffer input]
  (loop [value 0 shift 0]
    (let [b (unsigned-byte input)
          value (bit-or value (bit-shift-left (bit-and b 127) shift))]
      (cond
        (< b 128) value
        (= shift 28) (unsupported! "A native project reference exceeds five bytes." {:offset (.position input)})
        :else (recur value (+ shift 7))))))

(defn- read-native-string [^ByteBuffer input]
  (let [first-byte (unsigned-byte input)]
    (if (< first-byte 128)
      (let [result (StringBuilder.)]
        (loop [b first-byte]
          (.append result (char (bit-and b 127)))
          (when (< b 128)
            (recur (unsigned-byte input))))
        (.toString result))
      (let [length (if (zero? (bit-and first-byte 64))
                     (bit-and first-byte 63)
                     (loop [value (bit-and first-byte 63) shift 6]
                       (let [b (unsigned-byte input)
                             value (bit-or value (bit-shift-left (bit-and b 127) shift))]
                         (cond
                           (< b 128) value
                           (> shift 27) (unsupported! "A native string length is invalid." {})
                           :else (recur value (+ shift 7))))))]
        (case (long length)
          0 nil
          1 ""
          (do
            (when (> (dec length) (.remaining input))
              (unsupported! "A native string exceeds the remaining project data." {}))
            (let [result (StringBuilder.)]
              (dotimes [_ (dec length)]
                (let [b (unsigned-byte input)
                      code (cond
                             (< b 128) b
                             (<= 192 b 223) (bit-or (bit-shift-left (bit-and b 31) 6)
                                                    (bit-and (unsigned-byte input) 63))
                             (<= 224 b 239) (bit-or (bit-shift-left (bit-and b 15) 12)
                                                    (bit-shift-left (bit-and (unsigned-byte input) 63) 6)
                                                    (bit-and (unsigned-byte input) 63))
                             :else (unsupported! "Unsupported native string encoding." {:offset (.position input)}))]
                  (.append result (char code))))
              (.toString result))))))))

(defn- read-string-reference [^ByteBuffer input]
  (let [reference (read-varint input)]
    (cond-> {:reference reference}
      (= 1 reference) (assoc :value (read-native-string input)))))

(defn- read-enum-reference [^ByteBuffer input]
  (let [reference (read-varint input)]
    (cond-> {:reference reference}
      (= 1 reference) (assoc :ordinal-plus-one (read-varint input)))))

(declare read-bone-impl)

(defn- read-field [^ByteBuffer input tag depth]
  (cond
    (contains? float-field-names tag) (double (.getFloat input))

    (contains? #{2 29 30 126} tag)
    (let [value (unsigned-byte input)]
      (when (> value 1)
        (unsupported! "A native bone Boolean has an unexpected value." {:offset (.position input)}))
      (= 1 value))

    (= 0 tag) (read-varint input)
    (contains? #{1 28} tag) (read-string-reference input)
    (contains? #{27 32} tag) (read-enum-reference input)

    (= 3 tag)
    (let [reference (read-varint input)]
      (cond-> {:reference reference}
        (= 1 reference) (assoc :new-bone (read-bone-impl input (inc depth)))))

    (= 17 tag)
    (let [class (read-varint input)]
      (when-not (= 30 class)
        (unsupported! "The native bone color uses an unsupported class." {:class class}))
      (let [reference (read-varint input)]
        (cond-> {:class class :reference reference}
          (= 1 reference) (assoc :rgba (bit-and 0xffffffff (.getInt input))))))

    (= 31 tag)
    (let [class (read-varint input)]
      (when-not (= 15 class)
        (unsupported! "The native bone auxiliary collection uses an unsupported class." {:class class}))
      (let [reference (read-varint input)]
        (when (and (= 1 reference) (not (zero? (read-varint input))))
          (unsupported! "Nonempty native bone auxiliary collections are not yet supported." {}))
        {:class class :reference reference}))))

(defn- read-bone-impl [^ByteBuffer input depth]
  (when (> depth 256)
    (unsupported! "The native bone graph is too deeply nested." {}))
  (let [offset (.position input)
        count (read-varint input)]
    (when-not (= 27 count)
      (unsupported! "The native bone field schema is not supported." {:offset offset :field-count count}))
    (let [fields (reduce (fn [fields expected-tag]
                           (let [tag (read-varint input)
                                 value-offset (.position input)]
                             (when-not (= expected-tag tag)
                               (unsupported! "The native bone field order is not supported." {:offset value-offset :tag tag :expected expected-tag}))
                             (let [value (read-field input tag depth)]
                               (assoc fields tag {:offset value-offset :length (- (.position input) value-offset) :value value}))))
                         {}
                         bone-tags)
          properties (into {} (comp (filter (fn [[_ name]] (contains? editable-properties name)))
                                    (map (fn [[tag name]] [name (get-in fields [tag :value])])))
                           float-field-names)]
      {:offset offset :end (.position input) :fields fields
       :name (get-in fields [1 :value :value])
       :name-reference (get-in fields [1 :value :reference])
       :parent-reference (get-in fields [3 :value :reference])
       :properties properties})))

(defn- scan-bones [^bytes payload]
  (let [length (alength payload)
        input (doto (ByteBuffer/wrap payload) (.order ByteOrder/BIG_ENDIAN))]
    (loop [position 1 result (transient [])]
      (if (> position (- length 7))
        (persistent! result)
        (if (and (= 1 (aget payload (dec position)))
                 (= 27 (aget payload position))
                 (= 14 (aget payload (inc position)))
                 (= 32 (aget payload (+ position 6))))
          (do
            (.position input position)
            (recur (inc position) (conj! result (read-bone-impl input 0))))
          (recur (inc position) result))))))

(defn decode
  "Inspect an exact supported native project without invoking Spine. Returns an
  opaque preservation container plus typed bone transforms. Bone indexes refer
  to records, not a solved hierarchy. Unresolved cross-object references remain
  explicit. This result cannot be used as a complete editor document."
  [packed]
  (when-not (and (bytes? packed) (pos? (alength ^bytes packed)) (<= (alength ^bytes packed) max-packed-size))
    (unsupported! "Expected a nonempty native project byte array within 32 MiB." {}))
  (try
    (let [original (Arrays/copyOf ^bytes packed (alength ^bytes packed))
          payload (unpack original)
          input (doto (ByteBuffer/wrap payload) (.order ByteOrder/BIG_ENDIAN))
          header [(read-native-string input) (read-native-string input) (read-native-string input)]]
      (when-not (= ["40" " 0" " 4.2.43"] header)
        (unsupported! "Independent native bone editing supports only the verified Spine 4.2.43 project schema 40." {:header header}))
      (let [skeleton-count-candidate (read-varint input)
            bones (scan-bones payload)]
        (when (or (zero? (count bones)) (coll/any? #(or (not (string? (:name %))) (string/blank? (:name %))) bones))
          (unsupported! "The native project has no fully decoded, uniquely addressable bone records." {}))
        {::schema schema ::original original ::payload payload ::patches (sorted-map)
         :header header :skeleton-count-candidate skeleton-count-candidate
         :complete false :opaque-data-preserved true
         :capabilities #{:bone-transform-inspection :bone-transform-editing :opaque-roundtrip}
         :bones bones}))
    (catch clojure.lang.ExceptionInfo error (throw error))
    (catch Exception error
      (throw (ex-info "The native project is corrupt or uses an unsupported encoding." {:type ::unsupported} error)))))

(defn set-bone-properties
  "Change only verified native bone transform fields. All other bytes, including
  attachments, animation curves and editor-only state, remain preserved."
  [project bone-index changes]
  (when-not (and (= schema (::schema project))
                 (int? bone-index) (<= 0 bone-index) (< bone-index (count (:bones project)))
                 (map? changes)
                 (coll/every? (fn [[property value]]
                               (and (contains? editable-properties property) (number? value)
                                    (Double/isFinite (double value))
                                    (<= (- Float/MAX_VALUE) (double value) Float/MAX_VALUE)))
                             changes))
    (unsupported! "Native editing requires a valid bone index and finite supported transform values." {:bone-index bone-index}))
  (let [bone (get-in project [:bones bone-index])
        property-tags (into {} (map (fn [[tag property]] [property tag])) float-field-names)]
    (reduce-kv (fn [project property value]
                 (let [tag (get property-tags property)
                       offset (get-in bone [:fields tag :offset])
                       value (double (float value))]
                   (-> project
                       (assoc-in [::patches offset] value)
                       (assoc-in [:bones bone-index :properties property] value))))
               project
               changes)))

(defn encode
  "Write the original project with verified four-byte float edits only. Unedited
  projects retain their exact original compressed bytes. No external app is used."
  ^bytes [project]
  (when-not (= schema (::schema project))
    (unsupported! "Expected a native preservation container produced by decode." {}))
  (if (zero? (count (::patches project)))
    (Arrays/copyOf ^bytes (::original project) (alength ^bytes (::original project)))
    (let [original ^bytes (::payload project)
          payload (Arrays/copyOf original (alength original))
          output (doto (ByteBuffer/wrap payload) (.order ByteOrder/BIG_ENDIAN))]
      (reduce-kv (fn [_ offset value]
                   (.putFloat output (int offset) (float value)))
                 nil
                 (::patches project))
      (pack payload))))

(defn require-complete-document!
  "Guard full-editor import until every native object schema has been decoded."
  [project]
  (if (and (= schema (::schema project)) (:complete project))
    project
    (unsupported! "This preservation container is not a complete decoded native graph. Decode the supported graph explicitly; unsupported attachments, animation tracks and editor state must not be discarded." {})))

;; The native stream uses separate reference tables for model objects and value
;; objects. These exact schemas were established with isolated differential
;; projects; marker values are deliberately not inferred from field counts.
(def ^:private graph-schemas
  {:project [26 [[31 :dynamic] [19 :integer] [0 :dynamic] [33 :dynamic] [8 :float]
                 [14 :dynamic] [38 :boolean] [42 :boolean] [2 :float] [20 :string]
                 [17 :float] [41 :integer] [40 :boolean] [7 :dynamic] [15 :float]
                 [16 :dynamic] [4 :boolean] [34 :float] [28 :boolean] [36 :dynamic]
                 [30 :dynamic] [37 :boolean] [32 :float] [13 :boolean] [35 :boolean] [29 :dynamic]]]
   :skeleton [38 [[35 :boolean] [40 :dynamic] [3 :string] [14 :dynamic] [33 :boolean]
                  [47 :boolean] [46 :float] [29 :dynamic] [45 :dynamic] [26 :dynamic]
                  [34 :string] [44 :dynamic] [23 :dynamic] [16 :dynamic] [42 :name-path]
                  [31 :dynamic] [32 :dynamic] [10 :dynamic] [2 :boolean] [37 :dynamic]
                  [36 :dynamic] [13 :dynamic] [22 :dynamic] [4 :dynamic] [30 :dynamic]
                  [0 :project] [6 :dynamic] [48 :boolean] [41 :dynamic] [8 :dynamic]
                  [27 :dynamic] [28 :dynamic] [38 :dynamic] [24 :boolean] [43 :boolean]
                  [7 :dynamic] [126 :boolean]]]
   :folder [5 [[0 :folder] [3 :string] [5 :dynamic] [1 :dynamic] [2 :skeleton]]]
   :bone [27 [[14 :float] [32 :enum] [1 :string] [13 :float] [30 :boolean] [2 :boolean]
              [25 :float] [5 :float] [23 :float] [3 :bone] [8 :float] [26 :float]
              [11 :float] [29 :boolean] [12 :float] [17 :dynamic] [31 :dynamic]
              [6 :float] [27 :enum] [24 :float] [7 :float] [10 :float] [9 :float]
              [4 :float] [0 :skeleton] [28 :string] [126 :boolean]]]
   :slot [17 [[3 :boolean] [4 :dynamic] [5 :dynamic] [14 :enum] [2 :bone] [10 :dynamic]
              [12 :boolean] [8 :boolean] [15 :float] [11 :dynamic] [7 :slot-name]
              [9 :dynamic] [16 :dynamic] [0 :skeleton] [13 :boolean] [126 :boolean]]]
   :region [17 [[15 :boolean] [11 :float] [9 :float] [12 :float] [14 :dynamic]
                [6 :float] [10 :float] [13 :string] [7 :float] [8 :float]
                [4 :placeholder] [5 :name-path] [32 :skin] [35 :boolean] [33 :boolean] [0 :slot]]]
   :placeholder [8 [[125 :boolean] [4 :placeholder] [5 :name-path] [32 :skin]
                     [35 :boolean] [33 :boolean] [0 :slot]]]
   :skin [9 [[7 :dynamic] [1 :string] [8 :dynamic] [4 :boolean] [6 :dynamic]
             [0 :skeleton] [2 :dynamic] [5 :dynamic] [126 :boolean]]]
   :animation [9 [[8 :dynamic] [0 :skeleton] [1 :string] [6 :boolean] [3 :boolean]
                  [7 :dynamic] [2 :dynamic] [9 :boolean] [5 :boolean]]]
   :bone-track [5 [[4 :boolean] [6 :dynamic] [1 :bone] [0 :animation] [2 :dynamic]]]
   :mesh [28 [[26 :boolean] [16 :float] [12 :dynamic] [27 :boolean] [14 :string]
              [17 :float] [36 :boolean] [34 :boolean] [13 :dynamic] [19 :float]
              [31 :dynamic] [21 :float] [28 :dynamic] [9 :float] [30 :boolean]
              [8 :dynamic] [11 :float] [10 :float] [7 :integer] [20 :float]
              [18 :dynamic] [4 :placeholder] [5 :name-path] [32 :skin]
              [35 :boolean] [33 :boolean] [0 :slot]]]
   :mesh-vertices [4 [[0 :dynamic] [2 :dynamic] [1 :dynamic] [3 :dynamic]]]
   :influence [2 [[1 :dynamic] [0 :dynamic]]]
   :ik [18 [[16 :boolean] [13 :boolean] [10 :boolean] [11 :boolean] [2 :dynamic]
            [3 :bone] [12 :boolean] [14 :float] [6 :float] [17 :boolean]
            [15 :float] [9 :boolean] [5 :float] [0 :skeleton] [32 :dynamic]
            [1 :string] [34 :boolean] [22 :dynamic]]]
   :transform [28 [[11 :float] [18 :float] [3 :bone] [13 :float] [9 :float]
                   [20 :boolean] [19 :dynamic] [15 :boolean] [17 :float] [4 :float]
                   [21 :boolean] [8 :float] [5 :float] [16 :float] [6 :float]
                   [25 :float] [12 :float] [23 :float] [26 :float] [24 :float]
                   [14 :float] [10 :float] [7 :float] [0 :skeleton] [32 :dynamic]
                   [1 :string] [34 :boolean] [22 :dynamic]]]
   :physics [34 [[28 :boolean] [3 :bone] [7 :physics-rotation] [20 :float] [9 :float]
                  [5 :float] [27 :boolean] [21 :float] [12 :float] [30 :boolean]
                  [33 :float] [19 :float] [23 :float] [26 :boolean] [8 :float]
                  [17 :float] [10 :float] [16 :float] [18 :float] [31 :boolean]
                  [4 :float] [29 :boolean] [25 :boolean] [24 :float] [6 :float]
                  [13 :float] [14 :float] [15 :float] [11 :float] [0 :skeleton]
                  [32 :dynamic] [1 :string] [34 :boolean] [22 :dynamic]]]
   :path-attachment [22 [[12 :boolean] [15 :path-lengths] [13 :boolean] [19 :float]
                         [31 :dynamic] [21 :float] [28 :dynamic] [9 :float] [30 :boolean]
                         [8 :dynamic] [11 :float] [10 :float] [7 :integer] [20 :float]
                         [18 :dynamic] [4 :placeholder] [5 :name-path] [32 :skin]
                         [35 :boolean] [33 :boolean] [0 :slot]]]
   :path-vertices [4 [[0 :dynamic] [2 :dynamic] [1 :dynamic] [3 :path-vertex-options-field]]]
   :path-constraint [22 [[14 :enum] [6 :float] [4 :float] [7 :float] [23 :float] [11 :boolean]
                         [9 :float] [3 :slot] [12 :float] [16 :enum] [24 :float] [5 :float]
                         [15 :enum] [10 :float] [8 :float] [13 :float] [2 :dynamic]
                         [0 :skeleton] [32 :dynamic] [1 :string] [34 :boolean] [22 :dynamic]]]
   :event [10 [[3 :integer] [10 :dynamic] [9 :float] [8 :float] [6 :string]
               [0 :skeleton] [2 :string] [4 :float] [5 :string] [7 :boolean]]]
   :event-key [6 [[5 :float] [3 :float] [4 :string] [6 :float] [1 :event] [2 :integer]]]})

(def ^:private graph-classes
  {11 :skeleton 12 :bone 13 :slot 15 :array 18 :animation 19 :bone-track
   30 :color 34 :skin 38 :empty-38 41 :vector 43 :region 44 :placeholder
   16 :ints 17 :floats 35 :event 46 :mesh 47 :shorts 54 :influence 56 :ik 65 :transform 71 :mesh-vertices
   53 :path-vertices 69 :path-attachment 72 :path-constraint
   80 :empty-map 108 :folder 112 :timeline-category 116 :empty-116 121 :physics 132 :timeline 133 :keyframe})

(def ^:private model-kinds
  (into #{:empty-map :timeline :keyframe :timeline-category :deform-values} (coll/keys graph-schemas)))

(def ^:private timeline-names
  {0 "rotate" 1 "translate" 2 "scale" 3 "shear"
   14 "translatex" 15 "translatey" 16 "scalex" 17 "scaley" 18 "shearx" 19 "sheary"})

(def ^:private timeline-layouts
  {0 [1 1 nil] 1 [2 2 nil] 2 [2 2 nil] 3 [2 2 nil]
   5 [0 0 :slot] 6 [0 1 :dynamic] 7 [5 2 :ik] 8 [6 6 :transform]
   9 [1 1 :path-constraint] 10 [1 1 :path-constraint] 11 [3 3 :path-constraint]
   12 [0 0 :event] 14 [1 1 nil] 15 [1 1 nil] 16 [1 1 nil]
   17 [1 1 nil] 18 [1 1 nil] 19 [1 1 nil]
   23 [1 1 :physics] 24 [1 1 :physics] 25 [1 1 :physics] 26 [1 1 :physics]
   27 [1 1 :physics] 28 [1 1 :physics] 29 [1 1 :physics] 30 [0 0 :physics]
   31 [0 0 nil] 32 [1 1 nil] 33 [1 1 nil] 34 [1 1 nil] 35 [1 1 nil]
   36 [1 1 nil] 37 [1 1 nil] 38 [1 1 nil]})

(def ^:private physics-timeline-names
  {23 "inertia" 24 "strength" 25 "damping" 26 "mass" 27 "wind" 28 "gravity" 29 "mix" 30 "reset"
   31 "reset" 32 "inertia" 33 "strength" 34 "damping" 35 "mass" 36 "wind" 37 "gravity" 38 "mix"})

(def ^:private default-footer (byte-array [1 11 0 0 0 0 0 0 0 0 0 0]))

(defn- reference-space [kind]
  (if (contains? model-kinds kind) :models :values))

(defn graph-node
  "Resolve a typed reference from a complete native graph. References are scoped
  to their own model or value table, never interpreted as bone indexes."
  [graph reference]
  (when reference
    (get-in graph [(:space reference) (:id reference)])))

(declare read-graph-value)

(defn- read-graph-boolean [^ByteBuffer input]
  (let [value (unsigned-byte input)]
    (when (> value 1)
      (unsupported! "Unexpected native Boolean value." {:offset (.position input) :value value}))
    (= 1 value)))

(defn- read-graph-count [^ByteBuffer input]
  (let [value (read-varint input)]
    (when (> value 100000)
      (unsupported! "Native collection exceeds the supported object limit." {:offset (.position input)}))
    value))

(defn- read-graph-parts [context]
  (let [input ^ByteBuffer (:input context)]
    (mapv (fn [_] (read-graph-value context :string)) (range (read-graph-count input)))))

(defn- read-graph-body [context kind reference]
  (let [input ^ByteBuffer (:input context)]
    (if-let [[marker fields] (get graph-schemas kind)]
      (do
        (when-not (= marker (read-varint input))
          (unsupported! "Unsupported native model schema marker." {:kind kind :offset (.position input)}))
        (into {} (map (fn [[expected-tag type]]
                       (let [tag (read-varint input)]
                         (when-not (= expected-tag tag)
                           (unsupported! "Unsupported native model field order." {:kind kind :tag tag :expected expected-tag :offset (.position input)}))
                         [tag (read-graph-value context type)])))
              fields))
      (case kind
        :string (read-native-string input)
        (:enum :timeline-category) (read-varint input)
        :floats (mapv (fn [_] (double (.getFloat input))) (range (read-graph-count input)))
        :path-lengths (let [encoded-length (read-graph-count input)]
                        (when (zero? encoded-length)
                          (unsupported! "Unsupported native path lengths marker." {}))
                        (mapv (fn [_] (double (.getFloat input))) (range (dec encoded-length))))
        :path-vertex-options []
        :ints (mapv (fn [_] (read-varint input)) (range (read-graph-count input)))
        :shorts (mapv (fn [_] (bit-and 0xffff (.getShort input))) (range (read-graph-count input)))
        :deform-values
        (let [size (read-graph-count input)
              count (read-graph-count input)]
          (when (> count (quot (+ size 127) 128))
            (unsupported! "Native deformation contains too many chunks." {}))
          {:size size
           :chunks (mapv (fn [index]
                           (let [nonzero (read-graph-count input)
                                 length (min 128 (- size (* index 128)))]
                             (when (> nonzero length)
                               (unsupported! "Native deformation chunk count exceeds its length." {}))
                             {:nonzero nonzero
                              :values (if (zero? nonzero) [] (mapv (fn [_] (double (.getFloat input))) (range length)))}))
                         (range count))})
        :color (bit-and 0xffffffff (.getInt input))
        :vector (mapv (fn [_] (double (.getFloat input))) (range 3))
        :array (mapv (fn [_] (read-graph-value context :dynamic)) (range (read-graph-count input)))
        :empty-116
        (mapv (fn [_]
                (let [bone (read-graph-value context :dynamic)
                      flags (read-varint input)]
                  (when-not (<= 1 flags 7)
                    (unsupported! "Unsupported native split-channel flags." {:flags flags}))
                  [bone flags]))
              (range (read-graph-count input)))
        :empty-38
        (do
          (when-not (zero? (read-varint input))
            (unsupported! "This native collection is supported only when empty." {:kind kind :offset (.position input)}))
          [])
        :empty-map
        (let [bytes (mapv (fn [_] (unsigned-byte input)) (range 3))]
          (when-not (= [1 0 0] bytes)
            (unsupported! "Nonempty native lookup maps are not yet supported." {:offset (.position input)}))
          [])
        :timeline
        (let [track (read-graph-value context :bone-track)
              property (read-varint input)
              data {:track track :property property}]
          (when-not (contains? timeline-layouts property)
            (unsupported! "Only native bone transform timelines are supported." {:property property}))
          ;; Keyframes refer back to their owning timeline while it is read.
          (swap! (:models context) assoc-in [(:id reference) :data] data)
          (let [data (assoc data :keys (read-graph-value context :array))
                target-type (nth (get timeline-layouts property) 2)]
            (cond-> data target-type (assoc :target (read-graph-value context target-type)))))
        :keyframe
        (let [track (read-graph-value context :bone-track)
              timeline (read-graph-value context :timeline)
              property (get-in @(:models context) [(:id timeline) :data :property])
              [dimensions curve-dimensions] (get timeline-layouts property)
              time (double (.getFloat input))
              values (mapv (fn [_] (double (.getFloat input))) (range dimensions))
              curves (mapv (fn [_] (mapv (fn [_] (double (.getFloat input))) (range 5))) (range curve-dimensions))
              flag (read-varint input)
              result {:track track :timeline timeline :time time :values values :curves curves :flag flag}]
          (when-not (<= 0 flag 2)
            (unsupported! "Unsupported native keyframe interpolation flag." {:flag flag}))
          (case (int property)
            5 (assoc result :attachment (read-graph-value context :dynamic))
            6 (assoc result :deform (read-graph-value context :deform-values))
            12 (assoc result :event (read-graph-value context :event-key))
            result))))))

(defn- read-graph-reference [context kind]
  (let [input ^ByteBuffer (:input context)
        offset (.position input)
        encoded-reference (read-varint input)]
    (when-not (zero? encoded-reference)
      (let [space (reference-space kind)
            table (get context space)]
        (if (= 1 encoded-reference)
          (let [id (count @table)
                reference {:space space :id id}]
            (when (>= (+ (count @(:models context)) (count @(:values context))) 100000)
              (unsupported! "Native graph exceeds the supported object limit." {}))
            (swap! table conj {:kind kind :offset offset})
            (let [data (read-graph-body context kind reference)]
              (swap! table assoc id {:kind kind :offset offset :end (.position input) :data data}))
            reference)
          (let [id (- encoded-reference 2)
                node (get @table id)]
            (when-not (= kind (:kind node))
              (unsupported! "Native object reference does not resolve to its declared type." {:kind kind :space space :id id :actual (:kind node) :offset offset}))
            {:space space :id id}))))))

(defn- read-graph-value [context type]
  (when (> (:depth context 0) 256)
    (unsupported! "Native object graph is too deeply nested." {}))
  (let [context (update context :depth (fnil inc 0))
        input ^ByteBuffer (:input context)]
    (case type
      (:float :physics-rotation) (double (.getFloat input))
      :integer (read-varint input)
      :path-vertex-options-field (do
                                   (when-not (= 17 (read-varint input))
                                     (unsupported! "Unsupported native path vertex options." {}))
                                   (read-graph-reference context :path-vertex-options))
      :boolean (read-graph-boolean input)
      :name-path (do
                   (when-not (= 1 (read-varint input))
                     (unsupported! "Unsupported native name-path prefix." {:offset (.position input)}))
                   (read-graph-parts context))
      :slot-name {:prefix (read-graph-value context :enum) :parts (read-graph-parts context)}
      :dynamic (let [class (read-varint input)]
                 (when-not (zero? class)
                   (if-let [kind (get graph-classes class)]
                     (read-graph-reference context kind)
                     (unsupported! "This native object class is not yet supported." {:class class :offset (.position input)}))))
      (read-graph-reference context type))))

(defn decode-graph
  "Decode the verified authoring subset in stream order, including meshes,
  weighted deformation, constraints, events and multiple skeletons.
  Unknown classes, nonempty unknown containers and nondefault footer state fail
  explicitly. The preservation-container API remains available independently."
  [project]
  (when-not (= schema (::schema project))
    (unsupported! "Expected a native preservation container produced by decode." {}))
  (try
    (let [input (doto (ByteBuffer/wrap ^bytes (::payload project)) (.order ByteOrder/BIG_ENDIAN))
          header [(read-native-string input) (read-native-string input) (read-native-string input)]
          skeleton-count (read-graph-count input)
          context {:input input :models (atom []) :values (atom [])}
          root (read-graph-value context :project)
          footer (byte-array (.remaining input))
          _ (.get input footer)
          graph {:header header :skeleton-count skeleton-count :root root
                 :models @(:models context) :values @(:values context)}
          skeletons (:data (graph-node graph (get-in (graph-node graph root) [:data 0])))]
      (when-not (Arrays/equals ^bytes default-footer footer)
        (unsupported! "The native project footer contains unsupported editor state." {}))
      (when-not (= skeleton-count (count skeletons))
        (unsupported! "Native skeleton count does not match the decoded project." {}))
      (assoc graph ::schema schema :complete true))
    (catch clojure.lang.ExceptionInfo error (throw error))
    (catch Exception error
      (throw (ex-info "The native graph is corrupt or uses an unsupported structure." {:type ::unsupported} error)))))

(defn- write-varint! [^DataOutputStream output value]
  (when-not (and (int? value) (<= 0 value 0xffffffff))
    (unsupported! "Expected a native unsigned integer." {:value value}))
  (loop [value (long value)]
    (if (< value 128)
      (.writeByte output (int value))
      (do
        (.writeByte output (int (bit-or 128 (bit-and value 127))))
        (recur (unsigned-bit-shift-right value 7))))))

(defn- write-native-string! [^DataOutputStream output value]
  (cond
    (nil? value) (.writeByte output 128)
    (not (string? value)) (unsupported! "Expected a native string." {})
    (coll/empty? value) (.writeByte output 129)
    :else
    (let [value ^String value
          length (.length value)
          ascii? (and (> length 1) (coll/every? #(< (int %) 128) value))]
      (if ascii?
        (dotimes [index length]
          (.writeByte output (bit-or (int (.charAt value index)) (if (= index (dec length)) 128 0))))
        (do
          (let [count (inc length)]
            (if (< count 64)
              (.writeByte output (bit-or 128 count))
              (do
                (.writeByte output (bit-or 192 (bit-and count 63)))
                (write-varint! output (unsigned-bit-shift-right count 6)))))
          ;; Kryo-style strings encode UTF-16 units, including surrogate pairs.
          (dotimes [index length]
            (let [character (int (.charAt value index))]
              (cond
                (< character 128) (.writeByte output character)
                (< character 2048)
                (do
                  (.writeByte output (bit-or 192 (bit-shift-right character 6)))
                  (.writeByte output (bit-or 128 (bit-and character 63))))
                :else
                (do
                  (.writeByte output (bit-or 224 (bit-shift-right character 12)))
                  (.writeByte output (bit-or 128 (bit-and (bit-shift-right character 6) 63)))
                  (.writeByte output (bit-or 128 (bit-and character 63))))))))))))

(declare write-graph-value!)

(defn- write-graph-parts! [context parts]
  (when-not (and (vector? parts) (<= (count parts) 100000))
    (unsupported! "Expected a bounded native name path." {}))
  (write-varint! (:output context) (count parts))
  (run! #(write-graph-value! context :string %) parts))

(defn- write-graph-body! [context node]
  (let [output ^DataOutputStream (:output context)
        kind (:kind node)
        data (:data node)]
    (if-let [[marker fields] (get graph-schemas kind)]
      (do
        (when-not (= (into #{} (map first) fields) (into #{} (coll/keys data)))
          (unsupported! "A native object contains missing or unknown fields." {:kind kind}))
        (write-varint! output marker)
        (run! (fn [[tag type]]
                (write-varint! output tag)
                (write-graph-value! context type (get data tag)))
              fields))
      (case kind
        :string (write-native-string! output data)
        :path-lengths (do
                        (write-varint! output (inc (count data)))
                        (run! #(write-graph-value! context :float %) data))
        :path-vertex-options (when-not (= [] data)
                               (unsupported! "Unsupported native path vertex options." {}))
        (:enum :timeline-category) (write-varint! output data)
        (:floats :ints :shorts)
        (do
          (when-not (and (vector? data) (<= (count data) 100000))
            (unsupported! "Native numeric array exceeds its supported size." {}))
          (write-varint! output (count data))
          (run! (fn [value]
                  (case kind
                    :floats (write-graph-value! context :float value)
                    :ints (write-varint! output value)
                    :shorts (do
                              (when-not (and (int? value) (<= 0 value 65535))
                                (unsupported! "Native mesh index exceeds unsigned short range." {}))
                              (.writeShort output (int value))))) data))
        :deform-values
        (let [{:keys [size chunks]} data]
          (when-not (and (int? size) (<= 0 size 100000) (vector? chunks)
                         (<= (count chunks) (quot (+ size 127) 128)))
            (unsupported! "Invalid native deformation chunks." {}))
          (write-varint! output size)
          (write-varint! output (count chunks))
          (doseq [[index {:keys [nonzero values]}] (mapv vector (range (count chunks)) chunks)]
            (let [length (min 128 (- size (* index 128)))]
              (when-not (and (int? nonzero) (<= 0 nonzero length)
                             (= (if (zero? nonzero) 0 length) (count values)))
                (unsupported! "Native deformation chunk does not match its declared size." {}))
              (write-varint! output nonzero)
              (run! #(write-graph-value! context :float %) values))))
        :color (.writeInt output (unchecked-int (long data)))
        :vector (do
                  (when-not (= 3 (count data))
                    (unsupported! "Expected a three-component native vector." {}))
                  (run! #(write-graph-value! context :float %) data))
        :array (do
                 (when-not (and (vector? data) (<= (count data) 100000))
                   (unsupported! "Expected a bounded native array." {}))
                 (write-varint! output (count data))
                 (run! #(write-graph-value! context :dynamic %) data))
        :empty-116
        (do
          (write-varint! output (count data))
          (run! (fn [[bone flags]]
                  (when-not (and (= :bone (:kind (graph-node (:graph context) bone))) (<= 1 flags 7))
                    (unsupported! "Unsupported native split-channel entry." {}))
                  (write-graph-value! context :dynamic bone)
                  (write-varint! output flags)) data))
        (:empty-38 :empty-map)
        (do
          (when-not (= [] data)
            (unsupported! "Nonempty native auxiliary containers are not supported." {:kind kind}))
          (if (= :empty-map kind)
            (.write output (byte-array [1 0 0]))
            (write-varint! output 0)))
        :timeline
        (do
          (when-not (contains? timeline-layouts (:property data))
            (unsupported! "Only native bone transform timelines are supported." {}))
          (write-graph-value! context :bone-track (:track data))
          (write-varint! output (:property data))
          (write-graph-value! context :array (:keys data))
          (when-let [target-type (nth (get timeline-layouts (:property data)) 2)]
            (write-graph-value! context target-type (:target data))))
        :keyframe
        (let [property (get-in (graph-node (:graph context) (:timeline data)) [:data :property])
              [dimensions curve-dimensions] (get timeline-layouts property)]
          (when-not (and (= dimensions (count (:values data)))
                         (= curve-dimensions (count (:curves data)))
                         (coll/every? #(= 5 (count %)) (:curves data))
                         (<= 0 (:flag data) 2))
            (unsupported! "A native keyframe has unsupported dimensions or interpolation." {}))
          (write-graph-value! context :bone-track (:track data))
          (write-graph-value! context :timeline (:timeline data))
          (write-graph-value! context :float (:time data))
          (run! #(write-graph-value! context :float %) (:values data))
          (run! (fn [curve] (run! #(write-graph-value! context :float %) curve)) (:curves data))
          (write-varint! output (:flag data))
          (case (int property)
            5 (write-graph-value! context :dynamic (:attachment data))
            6 (write-graph-value! context :deform-values (:deform data))
            12 (write-graph-value! context :event-key (:event data))
            nil))))))

(defn- write-graph-reference! [context kind reference]
  (let [output ^DataOutputStream (:output context)]
    (if (nil? reference)
      (write-varint! output 0)
      (let [space (reference-space kind)
            node (graph-node (:graph context) reference)
            table (get context space)]
        (when-not (and (= space (:space reference)) (= kind (:kind node)))
          (unsupported! "Native output reference does not match the required type." {:kind kind :reference reference}))
        (if-let [written-id (get @table reference)]
          (write-varint! output (+ 2 written-id))
          (do
            (swap! table assoc reference (count @table))
            (write-varint! output 1)
            (write-graph-body! context node)))))))

(defn- write-graph-value! [context type value]
  (when (> (:depth context 0) 256)
    (unsupported! "Native output graph is too deeply nested." {}))
  (let [context (update context :depth (fnil inc 0))
        output ^DataOutputStream (:output context)]
    (case type
      :physics-rotation (if (and (number? value) (Double/isNaN (double value)))
                          (.writeInt output 0x7fc00000)
                          (write-graph-value! context :float value))
      :float (do
               (when-not (and (number? value) (Double/isFinite (double value))
                              (<= (- Float/MAX_VALUE) (double value) Float/MAX_VALUE))
                 (unsupported! "Expected a finite native float." {}))
               (.writeFloat output (float value)))
      :integer (write-varint! output value)
      :path-vertex-options-field (do
                                   (write-varint! output 17)
                                   (write-graph-reference! context :path-vertex-options value))
      :boolean (do
                 (when-not (instance? Boolean value)
                   (unsupported! "Expected a native Boolean." {}))
                 (.writeBoolean output value))
      :name-path (do (write-varint! output 1) (write-graph-parts! context value))
      :slot-name (do
                   (write-graph-value! context :enum (:prefix value))
                   (write-graph-parts! context (:parts value)))
      :dynamic (if (nil? value)
                 (write-varint! output 0)
                 (let [kind (:kind (graph-node (:graph context) value))
                       class (coll/first-where (fn [[_ candidate]] (= kind candidate)) graph-classes)]
                   (when-not class
                     (unsupported! "Native output contains an unsupported dynamic class." {:kind kind}))
                   (write-varint! output (first class))
                   (write-graph-reference! context kind value)))
      (write-graph-reference! context type value))))

(defn encode-graph
  "Independently write the verified native object graph, rebuilding both
  reference tables. Structural changes are limited to this exact supported
  schema; unknown fields and object classes fail explicitly."
  ^bytes [graph]
  (when-not (and (= schema (::schema graph)) (:complete graph)
                 (= ["40" " 0" " 4.2.43"] (:header graph)))
    (unsupported! "Expected a complete supported graph produced by decode-graph." {}))
  (let [buffer (ByteArrayOutputStream.)
        output (DataOutputStream. buffer)
        context {:output output :graph graph :models (atom {}) :values (atom {})}]
    (run! #(write-native-string! output %) (:header graph))
    (write-varint! output (:skeleton-count graph))
    (write-graph-value! context :project (:root graph))
    (.write output ^bytes default-footer)
    (let [packed (pack (.toByteArray buffer))]
      ;; Validate the whole emitted graph, not only the transforms being edited.
      (decode-graph (decode packed))
      packed)))

(defn- graph-data [graph reference]
  (:data (graph-node graph reference)))

(defn- graph-name [graph parts]
  (when-not (= 1 (count parts))
    (unsupported! "Native names with multiple path components are not yet supported." {}))
  (graph-data graph (first parts)))

(defn graph-skeletons
  "List independently decoded skeletons without merging their namespaces."
  [graph]
  (mapv (fn [reference]
          {:reference reference :name (graph-name graph (get (graph-data graph reference) 42))})
        (graph-data graph (get (graph-data graph (:root graph)) 0))))

(defn- graph-bone-json [graph reference]
  (let [fields (graph-data graph reference)
        inherit (graph-data graph (get fields 27))
        parent (get fields 3)
        result {"name" (graph-data graph (get fields 1))
                "length" (get fields 9) "x" (get fields 10) "y" (get fields 11)
                "rotation" (get fields 12) "scaleX" (get fields 13) "scaleY" (get fields 14)
                "shearX" (get fields 23) "shearY" (get fields 25)
                "color" (format "%08x" (long (graph-data graph (get fields 17))))
                "icon" (graph-data graph (get fields 28)) "visible" (get fields 2)}]
    (when-not (<= 1 inherit 5)
      (unsupported! "Unsupported native bone inheritance mode." {:inherit inherit}))
    (cond-> (assoc result "inherit" (nth ["normal" "onlyTranslation" "noRotationOrReflection" "noScale" "noScaleOrReflection"] (dec inherit)))
      parent (assoc "parent" (graph-data graph (get (graph-data graph parent) 1))))))

(defn- graph-attachment-name [graph reference]
  (graph-name graph (get (graph-data graph reference) 5)))

(defn- graph-slot-name [graph reference]
  (let [data (get (graph-data graph reference) 7)]
    (when-not (= 1 (graph-data graph (:prefix data)))
      (unsupported! "Unsupported native slot name prefix." {}))
    (graph-name graph (:parts data))))

(defn- graph-slot-json [graph reference]
  (let [fields (graph-data graph reference)
        blend (graph-data graph (get fields 14))
        attachment (get fields 9)]
    (when-not (= 3 blend)
      (unsupported! "Only the verified normal native slot blend mode is supported." {:blend blend}))
    (cond-> {"name" (graph-slot-name graph reference)
             "bone" (graph-data graph (get (graph-data graph (get fields 2)) 1))
             "color" (format "%08x" (long (graph-data graph (get fields 5))))}
      (get fields 12) (assoc "dark" (subs (format "%08x" (long (graph-data graph (get fields 11)))) 0 6))
      attachment (assoc "attachment" (graph-attachment-name graph attachment)))))

(defn- graph-region-json [graph reference]
  (when-not (= :region (:kind (graph-node graph reference)))
    (unsupported! "This native attachment is decoded but its editable projection is not yet supported." {:kind (:kind (graph-node graph reference))}))
  (let [fields (graph-data graph reference)
        name (graph-attachment-name graph reference)
        path (graph-data graph (get fields 13))]
    {"name" name "type" "region" "path" (if (string/blank? path) name path)
     "width" (get fields 11) "height" (get fields 12)
     "x" (get fields 6) "y" (get fields 7) "rotation" (get fields 10)
     "scaleX" (get fields 8) "scaleY" (get fields 9)
     "color" (format "%08x" (long (graph-data graph (get fields 14))))}))

(defn- graph-object-name [graph reference]
  (graph-data graph (get (graph-data graph reference) 1)))

(defn- graph-attachment-location [graph reference]
  (let [fields (graph-data graph reference)
        skin (get fields 32)
        placeholder (get fields 4)]
    [(if skin (graph-object-name graph skin) "default")
     (graph-slot-name graph (get fields 0))
     (graph-attachment-name graph (or placeholder reference))]))

(defn- graph-mesh-influences [graph reference]
  (let [fields (graph-data graph reference)
        vertices (graph-data graph (get fields 28))
        palette (graph-data graph (get vertices 1))]
    (mapv (fn [reference]
            (let [influence (graph-data graph reference)
                  weights (graph-data graph (get influence 0))
                  positions (graph-data graph (get influence 1))]
              (when-not (and (= (count palette) (count weights)) (= (* 2 (count palette)) (count positions)))
                (unsupported! "Native dense mesh influences do not match their bone palette." {}))
              (mapv (fn [index bone]
                      {:bone bone :weight (nth weights index)
                       :x (nth positions (* index 2)) :y (nth positions (inc (* index 2)))})
                    (range (count palette)) palette)))
          (graph-data graph (get vertices 2)))))

(defn- graph-mesh-json [graph reference bone-indices]
  (let [fields (graph-data graph reference)
        name (graph-attachment-name graph reference)
        path (graph-data graph (get fields 14))
        weighted (get fields 30)
        uvs (graph-data graph (get fields 12))
        influences (when weighted (graph-mesh-influences graph reference))
        vertices (if weighted
                   (into [] (mapcat (fn [vertex]
                                     (let [active (filterv #(not (zero? (:weight %))) vertex)]
                                       (when (zero? (count active))
                                         (unsupported! "Native weighted mesh contains an unbound vertex." {}))
                                       (into [(count active)]
                                             (mapcat (fn [{:keys [bone x y weight]}]
                                                       (let [index (get bone-indices bone)]
                                                         (when (nil? index)
                                                           (unsupported! "Native mesh influence references a different skeleton." {}))
                                                         [index x y weight]))) active)))) influences)
                   (graph-data graph (get fields 18)))]
    (when-not (and (even? (count uvs)) (zero? (mod (get fields 7) 4))
                   (= (quot (count uvs) 2) (if weighted (count influences) (quot (count vertices) 2))))
      (unsupported! "Native mesh UVs, hull or vertices have incompatible lengths." {}))
    {"type" "mesh" "name" name "path" (if (string/blank? path) name path)
     "width" (get fields 16) "height" (get fields 17)
     "color" (format "%08x" (long (graph-data graph (get fields 31))))
     "uvs" uvs "triangles" (graph-data graph (get fields 13)) "vertices" vertices
     "hull" (quot (get fields 7) 4) "edges" (graph-data graph (get fields 8))}))

(defn- graph-deform-values [graph reference attachment]
  (let [{:keys [size chunks]} (graph-data graph reference)
        fields (graph-data graph attachment)
        dense (reduce-kv (fn [result index {:keys [values]}]
                           (reduce-kv (fn [result offset value] (assoc result (+ (* index 128) offset) value)) result values))
                         (vec (repeat size 0.0)) chunks)]
    (if (get fields 30)
      (let [influences (graph-mesh-influences graph attachment)
            palette-size (count (first influences))]
        (when-not (= size (* 2 palette-size (count influences)))
          (unsupported! "Native weighted deformation does not match its dense influence layout." {:size size}))
        (into [] cat (mapv (fn [vertex-index vertex]
                          (into [] (comp (map-indexed (fn [bone-index influence]
                                                      (when-not (zero? (:weight influence))
                                                        (let [offset (* 2 (+ (* vertex-index palette-size) bone-index))]
                                                          [(nth dense offset) (nth dense (inc offset))]))))
                                         (keep identity) cat) vertex))
              (range (count influences)) influences)))
      (do
        (when-not (= size (count (graph-data graph (get fields 18))))
          (unsupported! "Native unweighted deformation does not match its vertex layout." {:size size}))
        dense))))

(defn- graph-key-curve [frames index fps]
  (let [{:keys [flag curves]} (nth frames index)]
    (when-not (coll/every? #(zero? (nth % 4)) curves)
      (unsupported! "Native tangent modes beyond explicit handles are not yet supported." {}))
    (case (int flag)
      0 {}
      2 {"curve" "stepped"}
      1 (let [next-frame (get frames (inc index))]
          (when-not (and next-frame (= (count curves) (count (:curves next-frame))))
            (unsupported! "Native Bezier handles require a matching following keyframe." {}))
          {"curve" (into [] cat (mapv (fn [curve next-curve]
                                       [(/ (nth curve 2) fps) (nth curve 3)
                                        (/ (nth next-curve 0) fps) (nth next-curve 1)]) curves (:curves next-frame)))})
      (unsupported! "Unsupported native keyframe interpolation." {:flag flag}))))

(defn- graph-timeline-json [graph reference fps]
  (let [{:keys [property keys target]} (graph-data graph reference)
        frames (mapv #(graph-data graph %) (graph-data graph keys))]
    (mapv (fn [index {:keys [time values attachment event deform]}]
            (let [base {"time" (/ time fps)}
                  result (case (int property)
                           (30 31) base
                           (1 2 3) (assoc base "x" (first values) "y" (second values))
                           5 (assoc base "name" (when attachment (last (graph-attachment-location graph attachment))))
                           6 (assoc base "vertices" (graph-deform-values graph deform target))
                           7 (assoc base "mix" (nth values 0) "softness" (nth values 1)
                                         "stretch" (not (zero? (nth values 2))) "compress" (not (zero? (nth values 3)))
                                         "bendPositive" (not (zero? (nth values 4))))
                           8 (merge base (zipmap ["mixRotate" "mixX" "mixY" "mixScaleX" "mixScaleY" "mixShearY"] values))
                           11 (merge base (zipmap ["mixRotate" "mixX" "mixY"] values))
                           12 (let [fields (graph-data graph event)
                                    source (graph-data graph (get fields 1))
                                    audio (graph-data graph (get source 6))
                                    integer (get fields 2)]
                                (cond-> (assoc base "name" (graph-data graph (get source 2))
                                                    "int" (bit-xor (unsigned-bit-shift-right integer 1) (- (bit-and integer 1)))
                                                    "float" (get fields 3) "string" (graph-data graph (get fields 4)))
                                  (not (string/blank? audio)) (assoc "volume" (get fields 5) "balance" (get fields 6))))
                           (assoc base "value" (first values)))]
              (if (contains? #{5 12} property) result
                  (merge result (graph-key-curve frames index fps)))))
          (range (count frames)) frames)))

(defn- graph-animation-json [graph reference fps]
  (let [fields (graph-data graph reference)
        tracks (graph-data graph (get fields 2))
        animation (reduce (fn [animation track-reference]
              (let [track (graph-data graph track-reference)
                    bone (get track 1)]
                (reduce (fn [animation timeline-reference]
                          (let [{:keys [property target]} (graph-data graph timeline-reference)
                                frames (graph-timeline-json graph timeline-reference fps)]
                            (case (int property)
                              (23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38)
                              (assoc-in animation ["physics" (if target (graph-object-name graph target) "") (get physics-timeline-names property)] frames)
                              (9 10 11) (assoc-in animation ["path" (graph-object-name graph target) (get {9 "position" 10 "spacing" 11 "mix"} property)] frames)
                              5 (assoc-in animation ["slots" (graph-slot-name graph target) "attachment"] frames)
                              6 (assoc-in animation (into ["attachments"] (conj (graph-attachment-location graph target) "deform")) frames)
                              7 (assoc-in animation ["ik" (graph-object-name graph target)] frames)
                              8 (assoc-in animation ["transform" (graph-object-name graph target)] frames)
                              12 (update animation "events" (fnil into []) frames)
                              (let [timeline-name (get timeline-names property)]
                                (when-not (and bone timeline-name)
                                  (unsupported! "Unsupported native timeline target or property." {:property property}))
                                (assoc-in animation ["bones" (graph-object-name graph bone) timeline-name] frames)))))
                        animation (graph-data graph (get track 2)))))
            {} tracks)]
    (cond-> animation (get animation "events") (update "events" #(vec (sort-by (fn [event] (get event "time" 0.0)) %))))))

(defn- graph-constraint-orders [graph fields]
  (let [folder (graph-data graph (get fields 44))
        children (graph-data graph (get folder 5))
        supported (into #{} cat [(graph-data graph (get fields 16)) (graph-data graph (get fields 22)) (graph-data graph (get fields 41)) (graph-data graph (get fields 23))])]
    (when-not (and (= supported (set children))
                   (coll/every? #(contains? #{:ik :transform :physics :path-constraint} (:kind (graph-node graph %))) children))
      (unsupported! "Native constraint folders with nested or unsupported objects cannot yet be projected." {}))
    (doseq [reference supported
            :let [constraint (graph-data graph reference)]]
      (when (pos? (count (graph-data graph (get constraint 22))))
        (unsupported! "Native constraint skin dependencies are not yet supported for editor conversion." {}))
      (when-not (true? (get constraint 34))
        (unsupported! "Native constraint activation state is not yet verified for editor conversion." {})))
    (into {} (map-indexed (fn [index reference] [reference index])) (rseq (vec children)))))

(defn- graph-ik-json [graph reference orders]
  (let [fields (graph-data graph reference)]
    {"name" (graph-object-name graph reference) "order" (orders reference)
     "bones" (mapv #(graph-object-name graph %) (graph-data graph (get fields 2)))
     "target" (graph-object-name graph (get fields 3))
     "mix" (get fields 5) "softness" (get fields 14)
     "bendPositive" (get fields 16) "compress" (get fields 11) "stretch" (get fields 9) "uniform" (get fields 13)}))

(defn- graph-transform-json [graph reference orders]
  (let [fields (graph-data graph reference)]
    (merge {"name" (graph-object-name graph reference) "order" (orders reference)
            "bones" (mapv #(graph-object-name graph %) (graph-data graph (get fields 19)))
            "target" (graph-object-name graph (get fields 3)) "local" (get fields 20) "relative" (get fields 21)}
           (into {} (map (fn [[field name]] [name (get fields field)]))
                 {14 "rotation" 6 "x" 7 "y" 16 "scaleX" 17 "scaleY" 18 "shearY"
                  8 "mixRotate" 4 "mixX" 23 "mixY" 10 "mixScaleX" 25 "mixScaleY" 12 "mixShearY"}))))

(defn- graph-event-json [graph reference]
  (let [fields (graph-data graph reference)
        integer (get fields 3)
        audio (graph-data graph (get fields 6))]
    [(graph-data graph (get fields 2))
     (cond-> {"int" (bit-xor (unsigned-bit-shift-right integer 1) (- (bit-and integer 1)))
              "float" (get fields 4) "string" (graph-data graph (get fields 5))}
       (not (string/blank? audio)) (assoc "audio" audio "volume" (get fields 8) "balance" (get fields 9)))]))

(def ^:private physics-source-tags
  {5 "x" 6 "y" 8 "scaleX" 33 "limit" 10 "inertia" 12 "strength" 14 "damping" 16 "mass"
   18 "wind" 20 "gravity" 23 "mix" 25 "inertiaGlobal" 26 "strengthGlobal" 27 "dampingGlobal"
   28 "massGlobal" 29 "windGlobal" 30 "gravityGlobal" 31 "mixGlobal"})

(defn- graph-physics-json [graph reference orders]
  (let [fields (graph-data graph reference)
        rotate (get fields 7)
        shear (get fields 9)
        rotate (if (Double/isNaN rotate) 0.0 rotate)
        total (+ rotate shear)
        factor (if (zero? total) 0.0 (/ (max rotate shear) total))
        step (get fields 4)]
    (when-not (and (Double/isFinite rotate) (Double/isFinite shear) (<= 0.0 rotate) (<= 0.0 shear)
                   (pos? step) (Double/isFinite step))
      (unsupported! "Native physics contains unsupported influence or step values." {}))
    (merge {"name" (graph-object-name graph reference) "order" (orders reference)
            "bone" (graph-object-name graph (get fields 3))
            "rotate" (* rotate factor) "shearX" (* shear factor)
            "fps" (double (Math/round (/ 1.0 step)))}
           (into {} (map (fn [[tag field]] [field (get fields tag)])) physics-source-tags))))

(defn- graph-path-json [graph reference bone-indices]
  (let [fields (graph-data graph reference)
        weighted (get fields 30)
        influences (when weighted (graph-mesh-influences graph reference))
        vertices (if-not weighted
                   (graph-data graph (get fields 18))
                   (into [] (mapcat (fn [vertex]
                                     (let [active (filterv #(not (zero? (:weight %))) vertex)]
                                       (when (zero? (count active))
                                         (unsupported! "Native weighted path contains an unbound vertex." {}))
                                       (into [(count active)]
                                             (mapcat (fn [{:keys [bone x y weight]}]
                                                       (let [index (get bone-indices bone)]
                                                         (when (nil? index)
                                                           (unsupported! "Native path influence references a different skeleton." {}))
                                                         [index x y weight]))) active)))) influences))
        vertex-count (if weighted (count influences) (quot (count vertices) 2))
        closed (get fields 12)]
    (when-not (and (zero? (mod (get fields 7) 4)) (= (* 4 vertex-count) (get fields 7))
                   (zero? (mod vertex-count 3)) (<= (if closed 3 6) vertex-count)
                   (or weighted (even? (count vertices))))
      (unsupported! "Native path vertices have an incompatible control point layout." {}))
    {"type" "path" "name" (graph-attachment-name graph reference)
     "vertexCount" vertex-count "vertices" vertices "closed" closed
     "constantSpeed" (get fields 13) "lengths" (graph-data graph (get fields 15))
     "color" (format "%08x" (long (graph-data graph (get fields 31))))}))

(defn- graph-path-constraint-json [graph reference orders]
  (let [fields (graph-data graph reference)
        position-mode (get {1 "fixed" 2 "percent"} (graph-data graph (get fields 16)))
        spacing-mode (get {1 "length" 2 "fixed" 3 "percent" 4 "proportional"} (graph-data graph (get fields 14)))
        rotate-mode (get {1 "tangent" 2 "chain" 3 "chainScale"} (graph-data graph (get fields 15)))]
    (when-not (and position-mode spacing-mode rotate-mode)
      (unsupported! "Native path constraint uses an unknown position, spacing or rotation mode." {}))
    {"name" (graph-object-name graph reference) "order" (get orders reference)
     "bones" (mapv #(graph-object-name graph %) (graph-data graph (get fields 2)))
     "target" (graph-slot-name graph (get fields 3))
     "positionMode" position-mode "spacingMode" spacing-mode "rotateMode" rotate-mode
     "position" (get fields 4) "spacing" (get fields 12) "rotation" (get fields 10)
     "mixRotate" (get fields 8) "mixX" (get fields 6) "mixY" (get fields 23)}))

(defn to-spine-json
  "Project a fully decoded native skeleton, retaining the graph separately for
  native round-trip. Supports region/mesh, dense weights/FFD, bone animation,
  IK/Transform/Path/Physics, events and attachment keys; unverified objects fail explicitly."
  [graph skeleton-index]
  (let [skeletons (graph-skeletons graph)
        reference (:reference (get skeletons skeleton-index))]
    (when-not reference
      (unsupported! "Native skeleton index is out of range." {:skeleton-index skeleton-index}))
    (let [fields (graph-data graph reference)
          fps (get (graph-data graph (:root graph)) 2)
          bones (graph-data graph (get fields 4))
          bone-indices (zipmap bones (range (count bones)))
          slots (graph-data graph (get fields 13))
          attachments (graph-data graph (get fields 6))
          skins (graph-data graph (get fields 8))
          animations (graph-data graph (get fields 7))
          orders (graph-constraint-orders graph fields)
          skin-attachments (reduce (fn [result attachment-reference]
                                     (let [kind (:kind (graph-node graph attachment-reference))]
                                       (if (= :placeholder kind) result
                                           (assoc-in result (graph-attachment-location graph attachment-reference)
                                                     (case kind
                                                       :region (graph-region-json graph attachment-reference)
                                                       :mesh (graph-mesh-json graph attachment-reference bone-indices)
                                                       :path-attachment (graph-path-json graph attachment-reference bone-indices)
                                                       (unsupported! "Unsupported native attachment." {:kind kind})))))) {} attachments)
          skin-names (into ["default"] (map #(graph-object-name graph %)) skins)]
      (when-not (and (number? fps) (pos? fps))
        (unsupported! "Native animation frame rate must be positive." {:fps fps}))
      (doseq [tag [14 36]]
        (when (pos? (count (graph-data graph (get fields tag))))
          (unsupported! "This native skeleton contains unsupported auxiliary objects." {:field tag})))
      (doseq [bone bones]
        (when (pos? (count (graph-data graph (get (graph-data graph bone) 31))))
          (unsupported! "Native bone auxiliary objects are not yet supported for editor conversion." {})))
      (doseq [skin skins tag [7 6]]
        (when (pos? (count (graph-data graph (get (graph-data graph skin) tag))))
          (unsupported! "Native skin dependencies are not yet supported for editor conversion." {:field tag})))
      {"skeleton" {"spine" "4.2.43" "fps" fps "images" (graph-data graph (get fields 3))
                   "audio" (graph-data graph (get fields 34)) "referenceScale" (get fields 46)}
       "bones" (mapv #(graph-bone-json graph %) bones)
       "slots" (mapv #(graph-slot-json graph %) slots)
       "skins" (mapv (fn [name] {"name" name "attachments" (get skin-attachments name {})}) skin-names)
       "ik" (mapv #(graph-ik-json graph % orders) (graph-data graph (get fields 16)))
       "transform" (mapv #(graph-transform-json graph % orders) (graph-data graph (get fields 22)))
       "physics" (mapv #(graph-physics-json graph % orders) (graph-data graph (get fields 41)))
       "path" (mapv #(graph-path-constraint-json graph % orders) (graph-data graph (get fields 23)))
       "events" (into {} (map #(graph-event-json graph %)) (graph-data graph (get fields 10)))
       "animations" (into {} (map (fn [animation-reference]
                                    [(graph-object-name graph animation-reference)
                                     (graph-animation-json graph animation-reference fps)])) animations)})))

(defn- source-fields! [value allowed location]
  (when-not (and (map? value) (coll/every? allowed (coll/keys value)))
    (unsupported! "Native saving encountered unsupported source fields." {:location location :fields (coll/keys value)})))

(defn- source-name! [name location]
  (when-not (and (string? name) (not (string/blank? name)))
    (unsupported! "Native object names must be nonempty strings." {:location location})))

(defn- source-color [value default]
  (let [value (or value default)]
    (when-not (and (string? value) (re-matches #"[0-9a-fA-F]{8}" value))
      (unsupported! "Native colors require eight hexadecimal RGBA digits." {:color value}))
    (Long/parseLong value 16)))

(defn- append-node! [graph kind data]
  (let [space (reference-space kind)
        reference {:space space :id (count (get @graph space))}]
    (swap! graph update space conj {:kind kind :data data})
    reference))

(defn- replace-node! [graph reference data]
  (swap! graph assoc-in [(:space reference) (:id reference) :data] data)
  reference)

(defn- replace-folder-children! [graph folder-reference old-references new-references]
  (let [old-set (set old-references)
        new-set (set new-references)
        folder (graph-data @graph folder-reference)
        children (graph-data @graph (get folder 5))
        retained (filterv #(or (not (contains? old-set %)) (contains? new-set %)) children)
        seen (set retained)
        children (into retained (remove seen) new-references)]
    (replace-node! graph folder-reference (assoc folder 5 (append-node! graph :array children)))))

(defn- validate-native-source! [source fps]
  (source-fields! source #{"skeleton" "bones" "slots" "skins" "animations" "ik" "transform" "path" "physics" "events"} :skeleton)
  (let [metadata (get source "skeleton" {})
        bones (get source "bones" [])
        slots (get source "slots" [])
        skins (get source "skins" [])
        bone-names (mapv #(get % "name") bones)
        slot-names (mapv #(get % "name") slots)
        skin-names (mapv #(get % "name") skins)
        bone-set (set bone-names)
        slot-set (set slot-names)]
    (source-fields! metadata #{"spine" "fps" "images" "audio" "referenceScale" "hash" "x" "y" "width" "height"} :metadata)
    (when-not (let [scale (get metadata "referenceScale" 100.0)]
                (and (number? scale) (Double/isFinite (double scale)) (pos? scale)))
      (unsupported! "Native reference scale must be finite and positive." {}))
    (when-not (= (double fps) (double (get metadata "fps" fps)))
      (unsupported! "Changing a native project's shared frame rate is not supported while saving one skeleton." {}))
    (doseq [[kind names] [[:bones bone-names] [:slots slot-names] [:skins skin-names]]]
      (when-not (= (count names) (count (set names)))
        (unsupported! "Duplicate native names are not supported." {:kind kind}))
      (run! #(source-name! % kind) names))
    (when (zero? (count bones))
      (unsupported! "A native skeleton requires at least one bone." {}))
    (reduce (fn [seen bone]
              (source-fields! bone #{"name" "parent" "length" "x" "y" "rotation" "scaleX" "scaleY" "shearX" "shearY" "inherit" "color" "icon" "visible"} :bone)
              (when-let [parent (get bone "parent")]
                (when-not (contains? seen parent)
                  (unsupported! "Native bones must follow their parents, without cycles or missing references." {:bone (get bone "name")})))
              (when-not (contains? #{"normal" "onlyTranslation" "noRotationOrReflection" "noScale" "noScaleOrReflection"} (get bone "inherit" "normal"))
                (unsupported! "Unsupported native bone inheritance." {}))
              (conj seen (get bone "name"))) #{} bones)
    (doseq [slot slots]
      (source-fields! slot #{"name" "bone" "attachment" "color" "dark" "blend"} :slot)
      (when-not (and (contains? bone-set (get slot "bone")) (= "normal" (get slot "blend" "normal")))
        (unsupported! "A native slot requires an existing bone and a verified normal blend mode." {:slot (get slot "name")})))
    (doseq [skin skins]
      (source-fields! skin #{"name" "attachments"} :skin)
      (doseq [[slot attachments] (get skin "attachments")]
        (when-not (contains? slot-set slot)
          (unsupported! "A native attachment refers to a missing slot." {:slot slot}))
        (doseq [[name attachment] attachments]
          (source-name! name :attachment)
          (source-fields! attachment #{"name" "type" "path" "x" "y" "rotation" "scaleX" "scaleY" "width" "height" "color" "uvs" "triangles" "vertices" "hull" "edges" "vertexCount" "closed" "constantSpeed" "lengths"} :attachment)
          (when-not (contains? #{"region" "mesh" "path"} (get attachment "type" "region"))
            (unsupported! "Native saving currently supports region, mesh and path attachments." {:type (get attachment "type")}))
          (when (and (= "default" (get skin "name")) (not= name (get attachment "name" name)))
            (unsupported! "Default-skin native region names must match their attachment keys." {:attachment name})))))
    (doseq [[name animation] (get source "animations")]
      (source-name! name :animation)
      (source-fields! animation #{"bones" "slots" "attachments" "ik" "transform" "physics" "path" "events"} :animation)
      (reduce (fn [previous frame]
                (let [time (get frame "time" 0.0)]
                  (when-not (and (number? time) (Double/isFinite (double time)) (< previous time))
                    (unsupported! "Native events must have unique increasing times; simultaneous event order cannot be preserved." {:animation name}))
                  time)) -1.0 (get animation "events" []))
      (doseq [[bone timelines] (get animation "bones")]
        (when-not (contains? bone-set bone)
          (unsupported! "A native animation refers to a missing bone." {:bone bone}))
        (source-fields! timelines (set (coll/vals timeline-names)) :timelines)
        (doseq [[combined split-x split-y] [["translate" "translatex" "translatey"] ["scale" "scalex" "scaley"] ["shear" "shearx" "sheary"]]]
          (when (and (contains? timelines combined) (or (contains? timelines split-x) (contains? timelines split-y)))
            (unsupported! "Combined and split native timelines cannot target the same channels." {:bone bone :property combined})))
        (doseq [[property frames] timelines]
          (when-not (vector? frames)
            (unsupported! "Native timeline keys must be a vector." {:property property}))
          (let [combined (contains? #{"translate" "scale" "shear"} property)]
            (reduce (fn [previous frame]
                      (source-fields! frame (if combined #{"time" "x" "y" "curve"} #{"time" "value" "curve"}) :keyframe)
                      (let [time (get frame "time" 0.0)
                            curve (get frame "curve")]
                        (when-not (and (number? time) (Double/isFinite (double time)) (<= 0.0 time) (> time previous))
                          (unsupported! "Native keyframe times must be finite, nonnegative and strictly increasing." {:time time}))
                        (when-not (or (nil? curve) (= "stepped" curve)
                                      (and (vector? curve) (= (if combined 8 4) (count curve))
                                           (coll/every? #(and (number? %) (Double/isFinite (double %))) curve)))
                          (unsupported! "Native keyframes contain unsupported Bezier data." {:property property}))
                        time)) -1.0 frames)
            (when (vector? (get (peek frames) "curve"))
              (unsupported! "The final native keyframe cannot start a Bezier segment." {:property property}))))))))

(defn- source-timeline! [graph track property frames fps]
  (let [timeline (append-node! graph :timeline {:track track :property property})
        combined (<= 1 property 3)
        dimensions (if combined 2 1)
        default (if (contains? #{2 16 17} property) 1.0 0.0)
        sentinel 2147483648.0
        keys (mapv (fn [index frame]
                     (let [curve (get frame "curve")
                           previous-curve (get (get frames (dec index)) "curve")
                           data {:track track :timeline timeline :time (* fps (get frame "time" 0.0))
                                 :values (if combined [(get frame "x" default) (get frame "y" default)] [(get frame "value" default)])
                                 :curves (mapv (fn [channel]
                                                 (let [offset (* channel 4)]
                                                   [(if (vector? previous-curve) (* fps (nth previous-curve (+ offset 2))) sentinel)
                                                    (if (vector? previous-curve) (nth previous-curve (+ offset 3)) sentinel)
                                                    (if (vector? curve) (* fps (nth curve offset)) sentinel)
                                                    (if (vector? curve) (nth curve (inc offset)) sentinel) 0.0]))
                                               (range dimensions))
                                 :flag (cond (= "stepped" curve) 2 (vector? curve) 1 :else 0)}]
                       (append-node! graph :keyframe data))) (range (count frames)) frames)]
    (replace-node! graph timeline {:track track :property property :keys (append-node! graph :array keys)})))

(defn- mesh-hull-contains-point [uvs hull index]
  (let [x (double (nth uvs (* index 2)))
        y (double (nth uvs (inc (* index 2))))]
    (loop [edge 0 inside false]
      (if (= edge hull)
        inside
        (let [next-edge (mod (inc edge) hull)
              ax (double (nth uvs (* edge 2)))
              ay (double (nth uvs (inc (* edge 2))))
              bx (double (nth uvs (* next-edge 2)))
              by (double (nth uvs (inc (* next-edge 2))))
              cross (- (* (- x ax) (- by ay)) (* (- y ay) (- bx ax)))
              boundary (and (< (Math/abs cross) 1.0e-6)
                            (<= (- (min ax bx) 1.0e-6) x (+ (max ax bx) 1.0e-6))
                            (<= (- (min ay by) 1.0e-6) y (+ (max ay by) 1.0e-6)))]
          (if boundary
            true
            (recur (inc edge)
                   (if (and (not= (> ay y) (> by y))
                            (< x (+ ax (/ (* (- bx ax) (- y ay)) (- by ay)))))
                     (not inside) inside))))))))

(defn- source-mesh! [graph target base slot-reference skin-reference placeholder name attachment bones bone-order]
  (let [uvs (get attachment "uvs")
        vertices (get attachment "vertices")
        triangles (get attachment "triangles")
        size (quot (count uvs) 2)
        weighted (not= (count vertices) (count uvs))
        hull (get attachment "hull" 0)
        edges (get attachment "edges" [])]
    (when-not (and (vector? uvs) (pos? size) (even? (count uvs)) (vector? vertices)
                   (vector? triangles) (zero? (mod (count triangles) 3))
                   (coll/every? #(and (int? %) (<= 0 % (dec size))) triangles)
                   (coll/every? #(and (number? %) (Double/isFinite (double %))) uvs)
                   (int? hull) (<= 3 hull size) (vector? edges) (even? (count edges))
                   (coll/every? #(and (int? %) (even? %) (<= 0 % (- (count uvs) 2))) edges))
      (unsupported! "Native mesh geometry contains invalid vertex, triangle or hull data." {:attachment name}))
    (when-not (coll/every? #(mesh-hull-contains-point uvs hull %) (range hull size))
      (unsupported! "Native mesh has an internal vertex outside its hull; repair the mesh boundary before saving." {:attachment name}))
    (doseq [[field default] [["x" 0.0] ["y" 0.0] ["rotation" 0.0] ["scaleX" 1.0] ["scaleY" 1.0]]]
      (when-not (= (double default) (double (get attachment field default)))
        (unsupported! "Mesh transforms must be baked into vertices before native saving." {:attachment name :field field})))
    (let [influences
          (if-not weighted
            (mapv (fn [index] [{:bone (get (graph-data @graph slot-reference) 2)
                               :x (nth vertices (* index 2)) :y (nth vertices (inc (* index 2))) :weight 1.0}]) (range size))
            (loop [vertex 0 offset 0 result []]
              (if (= vertex size)
                (do (when-not (= offset (count vertices)) (unsupported! "Native mesh weight data contains trailing values." {})) result)
                (let [influence-count (get vertices offset)]
                  (when-not (and (int? influence-count) (pos? influence-count) (<= (+ offset 1 (* influence-count 4)) (count vertices)))
                    (unsupported! "Native mesh weight data is truncated or invalid." {:vertex vertex}))
                  (let [row (mapv (fn [index]
                                    (let [start (+ offset 1 (* index 4))
                                          bone-index (nth vertices start)
                                          weight (nth vertices (+ start 3))]
                                      (when-not (and (int? bone-index) (<= 0 bone-index (dec (count bone-order)))
                                                     (number? weight) (Double/isFinite (double weight)) (<= 0.0 weight 1.0))
                                        (unsupported! "Native mesh contains an invalid bone or weight." {:vertex vertex}))
                                      {:bone (get bones (nth bone-order bone-index)) :x (nth vertices (inc start))
                                       :y (nth vertices (+ start 2)) :weight weight})) (range influence-count))]
                    (when-not (and (= (count row) (count (set (mapv :bone row))))
                                   (< (Math/abs (- 1.0 (reduce + 0.0 (mapv :weight row)))) 0.001))
                      (unsupported! "Native mesh vertex weights must be unique and normalized." {:vertex vertex}))
                    (recur (inc vertex) (long (+ offset 1 (* influence-count 4))) (conj result row)))))))
          palette (into [] (distinct) (into [] (mapcat #(mapv :bone %)) influences))
          container (append-node! graph :mesh-vertices {})
          rows (mapv (fn [row]
                       (let [weights (into {} (map (juxt :bone identity)) row)]
                         (append-node! graph :influence
                                       {0 (append-node! graph :floats (mapv #(get-in weights [% :weight] 0.0) palette))
                                        1 (append-node! graph :floats (into [] (mapcat #(let [v (get weights %)] [(get v :x 0.0) (get v :y 0.0)])) palette))}))) influences)
          fields (merge (or base {26 false 27 false 36 false 34 false 19 0.0 21 1.0 9 0.0 11 1.0 10 1.0 20 1.0 35 false 33 true})
                        {0 slot-reference 4 placeholder 5 [(append-node! graph :string (get attachment "name" name))] 32 skin-reference
                         16 (get attachment "width" 0.0) 17 (get attachment "height" 0.0)
                         12 (append-node! graph :floats uvs) 13 (append-node! graph :shorts triangles)
                         14 (append-node! graph :string (or (get attachment "path") ""))
                         31 (append-node! graph :color (source-color (get attachment "color") "ffffffff"))
                         28 container 30 weighted 7 (* 4 hull) 8 (append-node! graph :ints edges)
                         18 (append-node! graph :floats (if weighted (vec (repeat (* size 2) 0.0)) vertices))})]
      (replace-node! graph container {0 target 1 (append-node! graph :array palette)
                                     2 (append-node! graph :array rows) 3 (append-node! graph :floats [])})
      (replace-node! graph target fields))))

(defn- source-path-attachment! [graph target base slot-reference skin-reference placeholder name attachment bones bone-order]
  (let [size (get attachment "vertexCount")
        closed (get attachment "closed" false)
        lengths (get attachment "lengths")
        curves (if (and (int? size) closed) (quot size 3) (if (int? size) (dec (quot size 3)) 0))]
    (when-not (and (int? size) (<= (if closed 3 6) size) (zero? (mod size 3))
                   (boolean? closed) (boolean? (get attachment "constantSpeed" true))
                   (vector? lengths) (>= (count lengths) curves)
                   (coll/every? #(and (number? %) (Double/isFinite (double %)) (not (neg? %))) lengths))
      (unsupported! "Native path needs valid cubic control points, flags and explicit curve lengths." {:attachment name}))
    (source-mesh! graph target base slot-reference skin-reference placeholder name
                  (merge attachment {"uvs" (mapv (constantly 0.0) (range (* 2 size))) "triangles" [] "hull" size "edges" []
                                     "color" (get attachment "color" "ff7f00ff")}) bones bone-order)
    (let [mesh (graph-data @graph target)
          container (get mesh 28)
          anchors (vec (range 2 (* 2 size) 6))
          edge-count (if closed (count anchors) (dec (count anchors)))
          edges (into [] (mapcat (fn [index] [(nth anchors index) (nth anchors (mod (inc index) (count anchors)))])) (range edge-count))
          fields (into #{} (map first) (second (get graph-schemas :path-attachment)))]
      (swap! graph assoc-in [(:space container) (:id container) :kind] :path-vertices)
      (replace-node! graph container (assoc (graph-data @graph container) 3 (append-node! graph :path-vertex-options [])))
      (replace-node! graph target
                     (merge (select-keys mesh fields)
                             {12 closed 13 (get attachment "constantSpeed" true) 15 (append-node! graph :path-lengths (into lengths (map (constantly 0.0)) (range (max 0 (- (quot size 3) (count lengths))))))
                             8 (append-node! graph :ints edges)})))))

(defn- source-path-constraint! [graph original owner fields bones slots existing item]
  (source-fields! item #{"name" "order" "bones" "target" "positionMode" "spacingMode" "rotateMode" "position" "spacing"
                        "rotation" "mixRotate" "mixX" "mixY" "skin"} :path)
  (let [name (get item "name")
        target (or existing (append-node! graph :path-constraint {}))
        position-mode (get {"fixed" 1 "percent" 2} (get item "positionMode" "percent"))
        spacing-mode (get {"length" 1 "fixed" 2 "percent" 3 "proportional" 4} (get item "spacingMode" "length"))
        rotate-mode (get {"tangent" 1 "chain" 2 "chainScale" 3} (get item "rotateMode" "tangent"))
        bone-names (get item "bones")
        slot (get slots (get item "target"))]
    (source-name! name :path)
    (when-not (and slot position-mode spacing-mode rotate-mode (not (get item "skin" false))
                   (vector? bone-names) (pos? (count bone-names)) (coll/every? #(contains? bones %) bone-names))
      (unsupported! "Native path constraint needs existing bones/target and supported modes." {:path name}))
    (replace-node! graph target
                   (merge (or (graph-data original existing) {7 0.0 9 0.0 24 0.0 5 0.0 13 0.0 11 true 34 true 22 (append-node! graph :array [])})
                          {0 owner 1 (append-node! graph :string name) 2 (append-node! graph :array (mapv bones bone-names))
                           3 slot 32 (get fields 44) 14 (append-node! graph :enum spacing-mode)
                           16 (append-node! graph :enum position-mode) 15 (append-node! graph :enum rotate-mode)
                           4 (get item "position" 0.0) 12 (get item "spacing" 0.0) 10 (get item "rotation" 0.0)
                           8 (get item "mixRotate" 1.0) 6 (get item "mixX" 1.0) 23 (get item "mixY" (get item "mixX" 1.0))}))
    [name target]))

(defn- source-physics! [graph original owner fields bones existing item]
  (source-fields! item (into #{"name" "order" "bone" "rotate" "shearX" "fps" "skin"} (coll/vals physics-source-tags)) :physics)
  (let [name (get item "name")
        bone (get bones (get item "bone"))
        rotate (double (get item "rotate" 0.0))
        shear (double (get item "shearX" 0.0))
        fps (get item "fps" 60.0)
        target (or existing (append-node! graph :physics {}))
        defaults {5 0.0 6 0.0 8 0.0 33 5000.0 10 1.0 12 100.0 14 1.0 16 1.0 18 0.0 20 0.0 23 1.0
                  25 false 26 false 27 false 28 false 29 false 30 false 31 false}
        data (merge (or (graph-data original existing)
                         {21 0.0 19 0.0 17 0.0 24 0.0 13 0.0 15 0.0 11 0.0 34 true 22 (append-node! graph :array [])})
                    (into {} (map (fn [[tag field]] [tag (get item field (get defaults tag))])) physics-source-tags))
        maximum (max rotate shear)
        factor (if (zero? maximum) 0.0 (/ (+ rotate shear) maximum))]
    (source-name! name :physics)
    (when-not (and bone (not (get item "skin" false)) (number? fps) (<= 1.0 fps 1000.0) (= (double fps) (Math/rint (double fps)))
                   (Double/isFinite rotate) (Double/isFinite shear) (<= 0.0 rotate 1.0) (<= 0.0 shear 1.0)
                   (coll/every? #(let [v (get data %)] (and (number? v) (Double/isFinite (double v)) (<= 0.0 v 1.0))) [5 6 8 14])
                   (pos? (get data 16)) (not (neg? (get data 33))) (not (neg? (get data 12))))
      (unsupported! "Native physics requires valid influences, an existing bone, integer FPS and supported parameters." {:physics name}))
    (replace-node! graph target
                   (merge data {0 owner 1 (append-node! graph :string name) 3 bone 32 (get fields 44)
                                7 (if (zero? maximum) Double/NaN (* rotate factor)) 9 (* shear factor) 4 (/ 1.0 fps)}))
    [name target]))

(defn- source-constraints! [graph owner fields source bones slots]
  (let [original @graph
        old-ik (graph-data original (get fields 16))
        old-transform (graph-data original (get fields 22))
        old-events (graph-data original (get fields 10))
        old-physics (graph-data original (get fields 41))
        old-path (graph-data original (get fields 23))
        named (fn [references tag] (into {} (map (fn [ref] [(graph-data original (get (graph-data original ref) tag)) ref])) references))
        old-ik-map (named old-ik 1)
        old-transform-map (named old-transform 1)
        old-event-map (named old-events 2)
        old-physics-map (named old-physics 1)
        old-path-map (named old-path 1)
        make-constraint
        (fn [kind item]
          (let [ik (= :ik kind)
                name (get item "name")
                existing (get (if ik old-ik-map old-transform-map) name)
                target (or existing (append-node! graph kind {}))
                bone-names (get item "bones")
                target-bone (get bones (get item "target"))]
            (source-name! name kind)
            (source-fields! item (if ik
                                  #{"name" "order" "bones" "target" "mix" "softness" "bendPositive" "compress" "stretch" "uniform" "skin"}
                                  #{"name" "order" "bones" "target" "rotation" "x" "y" "scaleX" "scaleY" "shearY" "local" "relative" "skin"
                                    "mixRotate" "mixX" "mixY" "mixScaleX" "mixScaleY" "mixShearY"}) kind)
            (when-not (and target-bone (vector? bone-names) (pos? (count bone-names))
                           (coll/every? #(contains? bones %) bone-names) (not (get item "skin" false)))
              (unsupported! "Native constraint contains missing bones or unsupported skin dependencies." {:constraint name}))
            (when (and ik (not (<= 1 (count bone-names) 2)))
              (unsupported! "Native IK requires one or two bones." {:constraint name}))
            (let [base (or (graph-data original existing)
                           (if ik {10 false 12 false 6 0.0 17 false 15 0.0 34 true 22 (append-node! graph :array [])}
                               {11 0.0 13 0.0 9 0.0 15 false 5 0.0 26 0.0 24 0.0 34 true 22 (append-node! graph :array [])}))
                  data (merge base {0 owner 1 (append-node! graph :string name) 3 target-bone 32 (get base 32 (get fields 44))
                                    (if ik 2 19) (append-node! graph :array (mapv bones bone-names))}
                              (if ik
                                {5 (get item "mix" 1.0) 14 (get item "softness" 0.0) 16 (get item "bendPositive" true)
                                 11 (get item "compress" false) 9 (get item "stretch" false) 13 (get item "uniform" false)}
                                {20 (get item "local" false) 21 (get item "relative" false)
                                 14 (get item "rotation" 0.0) 6 (get item "x" 0.0) 7 (get item "y" 0.0)
                                 16 (get item "scaleX" 0.0) 17 (get item "scaleY" 0.0) 18 (get item "shearY" 0.0)
                                 8 (get item "mixRotate" 1.0) 4 (get item "mixX" 1.0) 23 (get item "mixY" (get item "mixX" 1.0))
                                 10 (get item "mixScaleX" 1.0) 25 (get item "mixScaleY" (get item "mixScaleX" 1.0)) 12 (get item "mixShearY" 1.0)}))]
              (replace-node! graph target data)
              [name target])))
        ik (into {} (map #(make-constraint :ik %)) (get source "ik" []))
        transform (into {} (map #(make-constraint :transform %)) (get source "transform" []))
        path (into {} (map #(source-path-constraint! graph original owner fields bones slots (get old-path-map (get % "name")) %)) (get source "path" []))
        physics (into {} (map #(source-physics! graph original owner fields bones (get old-physics-map (get % "name")) %)) (get source "physics" []))
        events (into {} (map (fn [[name event]]
                              (source-name! name :event)
                              (source-fields! event #{"int" "float" "string" "audio" "volume" "balance"} :event)
                              (let [integer (get event "int" 0)
                                    audio (get event "audio" "")
                                    target (or (get old-event-map name) (append-node! graph :event {}))]
                                (when-not (and (int? integer) (<= Integer/MIN_VALUE integer Integer/MAX_VALUE))
                                  (unsupported! "Native event integer exceeds signed 32-bit range." {}))
                                (replace-node! graph target
                                               {0 owner 2 (append-node! graph :string name) 3 (bit-xor (bit-shift-left integer 1) (bit-shift-right integer 63))
                                                4 (get event "float" 0.0) 5 (append-node! graph :string (get event "string" ""))
                                                6 (append-node! graph :string audio) 8 (get event "volume" 1.0) 9 (get event "balance" 0.0)
                                                7 (not (string/blank? audio)) 10 (get (graph-data original (get old-event-map name)) 10 (get fields 40))})
                                [name target]))) (get source "events" {}))
        all-constraints (merge ik transform physics path)
        ordered (into [] (map #(get all-constraints (get % "name")))
                      (sort-by #(get % "order" 0) (into [] cat [(get source "ik" []) (get source "transform" []) (get source "physics" []) (get source "path" [])])))
        folder (graph-data @graph (get fields 44))]
    (when-not (= (+ (count (get source "ik" [])) (count (get source "transform" [])) (count (get source "physics" [])) (count (get source "path" [])))
                 (+ (count ik) (count transform) (count physics) (count path))
                 (count all-constraints))
      (unsupported! "Native constraint names must be unique across constraint types." {}))
    (when (coll/any? #(= :folder (:kind (graph-node @graph %))) (graph-data @graph (get folder 5)))
      (unsupported! "Constraint subfolders are not yet supported for native editing." {}))
    (replace-node! graph (get fields 44) (assoc folder 5 (append-node! graph :array (vec (rseq ordered)))))
    (replace-folder-children! graph (get fields 40) old-events (vec (coll/vals events)))
    (replace-node! graph owner (assoc (graph-data @graph owner)
                                     16 (append-node! graph :array (mapv #(get ik (get % "name")) (get source "ik" [])))
                                     22 (append-node! graph :array (mapv #(get transform (get % "name")) (get source "transform" [])))
                                     10 (append-node! graph :array (vec (coll/vals events)))
                                     41 (append-node! graph :array (mapv #(get physics (get % "name")) (get source "physics" [])))
                                     23 (append-node! graph :array (mapv #(get path (get % "name")) (get source "path" [])))))
    {:ik ik :transform transform :physics physics :path path :events events}))

(defn- source-deform! [graph attachment-reference source-attachment frame bones bone-order]
  (let [mesh (graph-data @graph attachment-reference)
        container (graph-data @graph (get mesh 28))
        palette (graph-data @graph (get container 1))
        size (if (= "path" (get source-attachment "type")) (get source-attachment "vertexCount") (quot (count (get source-attachment "uvs")) 2))
        weighted (get mesh 30)
        input (get source-attachment "vertices")
        indices (if-not weighted
                  (vec (range (* size 2)))
                  (loop [vertex 0 offset 0 result []]
                    (if (= vertex size) result
                        (let [influence-count (nth input offset)
                              coordinates (into [] (mapcat (fn [index]
                                                             (let [bone (get bones (nth bone-order (nth input (+ offset 1 (* index 4)))))
                                                                   palette-index (.indexOf ^java.util.List palette bone)
                                                                   dense-index (* 2 (+ (* vertex (count palette)) palette-index))]
                                                               [dense-index (inc dense-index)]))) (range influence-count))]
                          (recur (inc vertex) (long (+ offset 1 (* influence-count 4))) (into result coordinates))))))
        offset (get frame "offset" 0)
        values (get frame "vertices" [])
        _ (when-not (and (int? offset) (<= 0 offset) (vector? values) (<= (+ offset (count values)) (count indices)))
            (unsupported! "Native deformation exceeds its attachment's vertices." {}))
        dense-size (* size 2 (if weighted (count palette) 1))
        dense (reduce-kv (fn [result index value] (assoc result (nth indices (+ offset index)) value))
                         (vec (repeat dense-size 0.0)) values)
        chunks (mapv (fn [start]
                       (let [chunk (subvec dense start (min dense-size (+ start 128)))
                             nonzero (count (filterv #(not (zero? %)) chunk))]
                         {:nonzero nonzero :values (if (zero? nonzero) [] chunk)})) (range 0 dense-size 128))]
    (append-node! graph :deform-values {:size dense-size :chunks chunks})))

(defn- source-extra-timeline! [graph track property frames fps target context]
  (let [[dimensions curve-dimensions] (get timeline-layouts property)
        timeline (append-node! graph :timeline {:track track :property property :target target})
        sentinel 2147483648.0
        frames (if-not (= 7 property)
                 frames
                 (mapv (fn [index frame]
                         (let [curve (get frame "curve")]
                           (if-not (and (vector? curve) (= 4 (count curve)))
                             frame
                             (let [next-frame (get frames (inc index))
                                   time (get frame "time" 0.0)
                                   next-time (get next-frame "time" time)
                                   softness (get frame "softness" 0.0)
                                   next-softness (get next-frame "softness" 0.0)]
                               (assoc frame "curve" (into curve [(+ time (/ (- next-time time) 3.0))
                                                                  (+ softness (/ (- next-softness softness) 3.0))
                                                                  (- next-time (/ (- next-time time) 3.0))
                                                                  (- next-softness (/ (- next-softness softness) 3.0))]))))))
                       (range (count frames)) frames))
        allowed (case (int property)
                  5 #{"time" "name"}
                  6 #{"time" "vertices" "offset" "curve"}
                  7 #{"time" "mix" "softness" "bendPositive" "compress" "stretch" "curve"}
                  8 #{"time" "mixRotate" "mixX" "mixY" "mixScaleX" "mixScaleY" "mixShearY" "curve"}
                  11 #{"time" "mixRotate" "mixX" "mixY" "curve"}
                  12 #{"time" "name" "int" "float" "string" "volume" "balance"}
                  (30 31) #{"time"}
                  #{"time" "value" "curve"})]
    (when-not (vector? frames) (unsupported! "Native animation frames must be vectors." {}))
    (reduce (fn [previous frame]
              (source-fields! frame allowed :keyframe)
              (when (= 7 property)
                (doseq [field ["stretch" "compress" "bendPositive"]]
                  (when (and (contains? frame field) (not (boolean? (get frame field))))
                    (unsupported! "Native IK keyframe flags must be booleans." {:field field}))))
              (let [time (get frame "time" 0.0)
                    curve (get frame "curve")]
                (when-not (and (number? time) (Double/isFinite (double time)) (<= 0.0 time)
                               (if (= property 12) (<= previous time) (< previous time)))
                  (unsupported! "Native keyframe times must be ordered and nonnegative." {:time time}))
                (when-not (or (nil? curve) (= curve "stepped")
                              (and (vector? curve) (= (* 4 curve-dimensions) (count curve))
                                   (coll/every? #(and (number? %) (Double/isFinite (double %))) curve)))
                  (unsupported! "Native keyframe curve dimensions are invalid." {:property property}))
                time)) -1.0 frames)
    (when (vector? (get (peek frames) "curve"))
      (unsupported! "The final native keyframe cannot start a Bezier curve." {}))
    (let [keys
          (mapv (fn [index frame]
                  (let [curve (get frame "curve")
                        previous-curve (get (get frames (dec index)) "curve")
                        values (case (int property)
                                 7 [(get frame "mix" 1.0) (get frame "softness" 0.0)
                                    (if (get frame "stretch" false) 1.0 0.0) (if (get frame "compress" false) 1.0 0.0)
                                    (if (get frame "bendPositive" true) 1.0 0.0)]
                                 8 [(get frame "mixRotate" 1.0) (get frame "mixX" 1.0) (get frame "mixY" (get frame "mixX" 1.0))
                                    (get frame "mixScaleX" 1.0) (get frame "mixScaleY" (get frame "mixScaleX" 1.0)) (get frame "mixShearY" 1.0)]
                                 11 [(get frame "mixRotate" 1.0) (get frame "mixX" 1.0) (get frame "mixY" (get frame "mixX" 1.0))]
                                 (9 10 23 24 25 26 27 28 29 32 33 34 35 36 37 38) [(get frame "value" 0.0)]
                                 [])
                        data {:track track :timeline timeline :time (* fps (get frame "time" 0.0)) :values values
                              :curves (mapv (fn [channel]
                                              (let [offset (* channel 4)]
                                                [(if (vector? previous-curve) (* fps (nth previous-curve (+ offset 2))) sentinel)
                                                 (if (vector? previous-curve) (nth previous-curve (+ offset 3)) sentinel)
                                                 (if (vector? curve) (* fps (nth curve offset)) sentinel)
                                                 (if (vector? curve) (nth curve (inc offset)) sentinel) 0.0])) (range curve-dimensions))
                              :flag (cond (= curve "stepped") 2 (vector? curve) 1 :else 0)}
                        data (case (int property)
                               5 (let [name (get frame "name")
                                       reference (get-in context [:attachment-names name])]
                                   (when (and name (nil? reference)) (unsupported! "An attachment key refers to a missing native attachment." {:attachment name}))
                                   (assoc data :attachment reference))
                               6 (assoc data :deform (source-deform! graph target (:source-attachment context) frame (:bones context) (:bone-order context)))
                               12 (let [definition (graph-data @graph target)
                                        raw-default (get definition 3)
                                        integer (get frame "int" (bit-xor (unsigned-bit-shift-right raw-default 1) (- (bit-and raw-default 1))))]
                                    (when-not (and (int? integer) (<= Integer/MIN_VALUE integer Integer/MAX_VALUE))
                                      (unsupported! "Native event integer exceeds signed 32-bit range." {}))
                                    (assoc data :event
                                           (append-node! graph :event-key
                                                         {1 target 2 (bit-xor (bit-shift-left integer 1) (bit-shift-right integer 63))
                                                          3 (get frame "float" (get definition 4))
                                                          4 (append-node! graph :string (get frame "string" (graph-data @graph (get definition 5))))
                                                          5 (get frame "volume" (get definition 8)) 6 (get frame "balance" (get definition 9))})))
                               data)]
                    (when-not (= dimensions (count values)) (unsupported! "Native keyframe value dimensions do not match." {}))
                    (append-node! graph :keyframe data))) (range (count frames)) frames)]
      (replace-node! graph timeline {:track track :property property :target target :keys (append-node! graph :array keys)}))))

(defn- source-animation! [graph owner fields existing name animation fps bones slots regions placeholders objects source]
  (let [target (or existing (append-node! graph :animation {}))
        properties (into {} (map (fn [[code name]] [name code])) timeline-names)
        bone-order (mapv #(get % "name") (get source "bones"))
        descriptors (atom [])
        add! (fn [bone property frames reference context]
               (swap! descriptors conj {:bone bone :property property :frames frames :target reference :context context}))]
    (doseq [[bone timelines] (get animation "bones")
            [property frames] timelines]
      (add! (get bones bone) (get properties property) frames nil nil))
    (doseq [[slot timelines] (get animation "slots")]
      (source-fields! timelines #{"attachment"} :slot-timelines)
      (let [slot-reference (get slots slot)
            names (into {} (keep (fn [[[skin slot-name attachment-name] reference]]
                                  (when (and (= "default" skin) (= slot slot-name)) [attachment-name reference]))) regions)
            names (into names (keep (fn [[[slot-name attachment-name] reference]] (when (= slot slot-name) [attachment-name reference]))) placeholders)]
        (when-not slot-reference (unsupported! "A native animation refers to a missing slot." {:slot slot}))
        (when-let [frames (get timelines "attachment")]
          (add! (get (graph-data @graph slot-reference) 2) 5 frames slot-reference {:attachment-names names}))))
    (doseq [[skin slots-source] (get animation "attachments")
            [slot attachments] slots-source
            [attachment-name timelines] attachments]
      (source-fields! timelines #{"deform"} :attachment-timelines)
      (let [reference (get regions [skin slot attachment-name])
            source-skin (coll/first-where #(= skin (get % "name")) (get source "skins"))
            source-attachment (get-in source-skin ["attachments" slot attachment-name])]
        (when-not (contains? #{:mesh :path-attachment} (:kind (graph-node @graph reference)))
          (unsupported! "A native deformation refers to a missing or unsupported mesh." {:attachment attachment-name}))
        (add! (get (graph-data @graph (get slots slot)) 2) 6 (get timelines "deform") reference
              {:source-attachment source-attachment :bones bones :bone-order bone-order})))
    (doseq [[kind property] [["ik" 7] ["transform" 8]]
            [constraint-name frames] (get animation kind)]
      (let [reference (get-in objects [(keyword kind) constraint-name])]
        (when-not reference (unsupported! "A native animation refers to a missing constraint." {:constraint constraint-name}))
        (add! (get (graph-data @graph reference) 3) property frames reference nil)))
    (doseq [[constraint-name timelines] (get animation "path")
            [property frames] timelines]
      (let [reference (get-in objects [:path constraint-name])
            code (get {"position" 9 "spacing" 10 "mix" 11} property)]
        (when-not (and reference code)
          (unsupported! "A native path timeline has a missing constraint or unsupported property." {:path constraint-name}))
        (add! (get (graph-data @graph (get (graph-data @graph reference) 3)) 2) code frames reference nil)))
    (doseq [[constraint-name timelines] (get animation "physics")
            [property frames] timelines]
      (let [global (= "" constraint-name)
            reference (get-in objects [:physics constraint-name])
            code (coll/first-where (fn [[code candidate]] (and (= property candidate) (= global (>= code 31)))) physics-timeline-names)]
        (when-not (and code (or global reference))
          (unsupported! "A native physics timeline refers to a missing constraint or unsupported property." {:physics constraint-name :property property}))
        (add! (when reference (get (graph-data @graph reference) 3)) (first code) frames reference nil)))
    (doseq [[event-name frames] (group-by #(get % "name") (get animation "events" []))]
      (let [reference (get-in objects [:events event-name])]
        (when-not reference (unsupported! "A native event frame refers to a missing definition." {:event event-name}))
        (add! nil 12 frames reference nil)))
    (let [groups (group-by (fn [{:keys [bone property]}] [bone (when-not bone property)]) @descriptors)
          split-flags (atom [])
          tracks (mapv (fn [[[bone category] descriptors]]
                         (let [track (append-node! graph :bone-track {})
                               flags (reduce (fn [flags {:keys [property]}] (bit-or flags (get {14 1 15 1 16 2 17 2 18 4 19 4} property 0))) 0 descriptors)
                               refs (mapv (fn [{:keys [property frames target context]}]
                                            (if (contains? timeline-names property)
                                              (source-timeline! graph track property frames fps)
                                              (source-extra-timeline! graph track property frames fps target context))) descriptors)]
                           (when (pos? flags) (swap! split-flags conj [bone flags]))
                           (replace-node! graph track {0 target 1 bone 2 (append-node! graph :array refs)
                                                      4 true 6 (when-not bone (append-node! graph :timeline-category category))}))) groups)
          base (or (graph-data @graph existing) {6 true 3 false 9 false 5 true})]
      (replace-node! graph target (merge base {0 owner 1 (append-node! graph :string name)
                                              2 (append-node! graph :array tracks) 8 (append-node! graph :empty-116 @split-flags)
                                              7 (get base 7 (get fields 37))})))))

(defn update-skeleton
  "Apply a complete supported Spine JSON skeleton to one native graph skeleton.
  Bone, slot, attachment, skin, constraint and key additions/deletions are structural
  edits. Other skeletons and verified native editor metadata remain preserved.
  Unsupported source objects fail before a graph is returned; no external app
  or runtime is required. Call encode-graph to serialize the returned graph."
  [native-graph skeleton-index source]
  ;; Validate the original selected graph as well, so hidden unsupported state
  ;; cannot accidentally disappear through the editable interchange projection.
  (to-spine-json native-graph skeleton-index)
  (let [reference (:reference (get (graph-skeletons native-graph) skeleton-index))
        fields (graph-data native-graph reference)
        fps (get (graph-data native-graph (:root native-graph)) 2)
        _ (validate-native-source! source fps)
        graph (atom native-graph)
        old-bones (graph-data native-graph (get fields 4))
        old-slots (graph-data native-graph (get fields 13))
        old-skins (graph-data native-graph (get fields 8))
        old-animations (graph-data native-graph (get fields 7))
        old-attachments (graph-data native-graph (get fields 6))
        existing-bones (into {} (map (fn [ref] [(graph-data native-graph (get (graph-data native-graph ref) 1)) ref])) old-bones)
        existing-slots (into {} (map (fn [ref] [(graph-slot-name native-graph ref) ref])) old-slots)
        existing-skins (into {} (map (fn [ref] [(graph-data native-graph (get (graph-data native-graph ref) 1)) ref])) old-skins)
        existing-animations (into {} (map (fn [ref] [(graph-data native-graph (get (graph-data native-graph ref) 1)) ref])) old-animations)
        bones (into {} (map (fn [bone]
                             (let [name (get bone "name")]
                               [name (or (get existing-bones name) (append-node! graph :bone {}))]))) (get source "bones"))
        slots (into {} (map (fn [slot]
                             (let [name (get slot "name")]
                               [name (or (get existing-slots name) (append-node! graph :slot {}))]))) (get source "slots"))
        skins (into {} (comp (remove #(= "default" (get % "name")))
                             (map (fn [skin]
                                    (let [name (get skin "name")]
                                      [name (or (get existing-skins name) (append-node! graph :skin {}))])))) (get source "skins"))
        existing-regions (into {} (comp (filter #(contains? #{:region :mesh :path-attachment} (:kind (graph-node native-graph %))))
                                        (map (fn [ref]
                                               (let [data (graph-data native-graph ref)
                                                     skin (get data 32)]
                                                 [[(if skin (graph-data native-graph (get (graph-data native-graph skin) 1)) "default")
                                                   (graph-slot-name native-graph (get data 0))
                                                   (graph-attachment-name native-graph (or (get data 4) ref))] ref])))) old-attachments)
        existing-placeholders (into {} (comp (filter #(= :placeholder (:kind (graph-node native-graph %))))
                                             (map (fn [ref] [[(graph-slot-name native-graph (get (graph-data native-graph ref) 0)) (graph-attachment-name native-graph ref)] ref]))) old-attachments)
        regions (atom {})
        placeholders (atom {})
        skin-region-refs (atom {})]
    (doseq [bone (get source "bones")]
      (let [name (get bone "name")
            target (get bones name)
            base (or (graph-data native-graph (get existing-bones name))
                     {4 0.0 5 0.0 6 0.0 7 1.0 8 1.0 24 0.0 26 0.0 29 false 30 true 126 false
                      31 (append-node! graph :array []) 32 (append-node! graph :enum 1)})
            inherit (inc (.indexOf ["normal" "onlyTranslation" "noRotationOrReflection" "noScale" "noScaleOrReflection"] (get bone "inherit" "normal")))]
        (replace-node! graph target
                       (merge base {0 reference 1 (append-node! graph :string name) 2 (get bone "visible" true)
                                    3 (get bones (get bone "parent")) 9 (get bone "length" 0.0)
                                    10 (get bone "x" 0.0) 11 (get bone "y" 0.0) 12 (get bone "rotation" 0.0)
                                    13 (get bone "scaleX" 1.0) 14 (get bone "scaleY" 1.0)
                                    23 (get bone "shearX" 0.0) 25 (get bone "shearY" 0.0)
                                    17 (append-node! graph :color (source-color (get bone "color") "989898ff"))
                                    27 (append-node! graph :enum inherit) 28 (append-node! graph :string (get bone "icon" ""))}))))
    (doseq [slot (get source "slots")]
      (let [ref (get slots (get slot "name"))]
        (replace-node! graph ref (assoc (graph-data @graph ref) 2 (get bones (get slot "bone"))))))
    (doseq [skin (get source "skins")
            [slot attachments] (get skin "attachments")
            [name attachment] attachments]
      (let [skin-name (get skin "name")
            default (= "default" skin-name)
            key [skin-name slot name]
            kind (case (get attachment "type") "mesh" :mesh "path" :path-attachment :region)
            existing (get existing-regions key)
            existing (when (= kind (:kind (graph-node native-graph existing))) existing)
            target (or existing (append-node! graph kind {}))
            placeholder (when-not default
                          (or (get @placeholders [slot name])
                              (let [ref (or (get existing-placeholders [slot name]) (append-node! graph :placeholder {}))]
                                (replace-node! graph ref {0 (get slots slot) 4 nil 5 [(append-node! graph :string name)] 32 nil 125 false 35 false 33 true})
                                (swap! placeholders assoc [slot name] ref)
                                ref)))
            base (graph-data native-graph existing)]
        (case kind
          :mesh (source-mesh! graph target base (get slots slot) (get skins skin-name) placeholder name attachment bones (mapv #(get % "name") (get source "bones")))
          :path-attachment (source-path-attachment! graph target base (get slots slot) (get skins skin-name) placeholder name attachment bones (mapv #(get % "name") (get source "bones")))
          (replace-node! graph target
                       (merge (or base {15 false 35 false 33 true}) {0 (get slots slot) 4 placeholder 5 [(append-node! graph :string (get attachment "name" name))]
                                    32 (get skins skin-name) 6 (get attachment "x" 0.0) 7 (get attachment "y" 0.0)
                                    8 (get attachment "scaleX" 1.0) 9 (get attachment "scaleY" 1.0) 10 (get attachment "rotation" 0.0)
                                    11 (get attachment "width" 0.0) 12 (get attachment "height" 0.0)
                                    13 (append-node! graph :string (or (get attachment "path") ""))
                                    14 (append-node! graph :color (source-color (get attachment "color") "ffffffff"))})))
        (swap! regions assoc key target)
        (swap! skin-region-refs update skin-name (fnil conj []) target)))
    (doseq [slot (get source "slots")]
      (let [name (get slot "name")
            attachment-name (get slot "attachment")
            attachment (or (get @regions ["default" name attachment-name]) (get @placeholders [name attachment-name]))
            dark (get slot "dark")
            base (or (graph-data native-graph (get existing-slots name))
                     {3 true 4 (append-node! graph :color 0xffffffff) 10 (append-node! graph :color 0x000000ff)
                      8 false 15 (/ 1.0 12.0) 13 true 126 false})]
        (when (and attachment-name (nil? attachment))
          (unsupported! "A native slot's setup attachment is missing." {:slot name :attachment attachment-name}))
        (when-not (or (nil? dark) (and (string? dark) (re-matches #"[0-9a-fA-F]{6}" dark)))
          (unsupported! "Native dark colors require six RGB hexadecimal digits." {:slot name}))
        (replace-node! graph (get slots name)
                       (merge base {0 reference 2 (get bones (get slot "bone"))
                                    5 (append-node! graph :color (source-color (get slot "color") "ffffffff"))
                                    11 (append-node! graph :color (source-color (when dark (str dark "ff")) "000000ff"))
                                    12 (boolean dark) 14 (append-node! graph :enum 3)
                                    7 {:prefix (append-node! graph :enum 1) :parts [(append-node! graph :string name)]}
                                    9 attachment 16 (get base 16 (get fields 45))}))))
    (doseq [[name target] skins]
      (let [base (or (graph-data native-graph (get existing-skins name))
                     {4 true 126 false 8 (append-node! graph :color 0xfe9e4fff)
                      6 (append-node! graph :array []) 7 (append-node! graph :array [])})]
        (replace-node! graph target
                       (merge base {0 reference 1 (append-node! graph :string name)
                                    2 (append-node! graph :array (get @skin-region-refs name []))
                                    5 (get base 5 (get fields 38))}))))
    (let [objects (source-constraints! graph reference fields source bones slots)
          animations (mapv (fn [[name animation]]
                             (source-animation! graph reference fields (get existing-animations name) name animation fps
                                                bones slots @regions @placeholders objects source))
                           (get source "animations" {}))
          bone-refs (mapv #(get bones (get % "name")) (get source "bones"))
          slot-refs (mapv #(get slots (get % "name")) (get source "slots"))
          skin-refs (into [] (keep #(get skins (get % "name"))) (get source "skins"))]
      (replace-folder-children! graph (get fields 45) old-slots slot-refs)
      (replace-folder-children! graph (get fields 38) old-skins skin-refs)
      (replace-folder-children! graph (get fields 37) old-animations animations)
      (replace-node! graph reference
                     (assoc (graph-data @graph reference)
                            3 (append-node! graph :string (get-in source ["skeleton" "images"] ""))
                            34 (append-node! graph :string (get-in source ["skeleton" "audio"] (graph-data native-graph (get fields 34))))
                            46 (get-in source ["skeleton" "referenceScale"] (get fields 46))
                            4 (append-node! graph :array bone-refs) 13 (append-node! graph :array slot-refs)
                            8 (append-node! graph :array skin-refs) 7 (append-node! graph :array animations)
                            6 (append-node! graph :array (into (vec (coll/vals @regions)) (coll/vals @placeholders)))))
      ;; The complete writer validates references, field types and object limits.
      ;; Re-reading also drops unreachable old objects without altering peers.
      (decode-graph (decode (encode-graph @graph))))))

(defn from-skeletons
  "Create a supported native project independently from named Spine JSON maps.
  Entries are {:name string :source map}. The defaults are the verified minimal
  project schema, not an embedded application template or a user's project.
  Every skeleton shares one project frame rate and retains its own namespace."
  [skeletons]
  (when-not (and (vector? skeletons) (pos? (count skeletons)) (<= (count skeletons) 1000)
                 (= (count skeletons) (count (set (mapv :name skeletons)))))
    (unsupported! "Native project creation requires 1–1000 uniquely named skeletons." {}))
  (let [fps (get-in (first skeletons) [:source "skeleton" "fps"] 30.0)
        graph (atom {::schema schema :complete true :header ["40" " 0" " 4.2.43"]
                     :skeleton-count (count skeletons) :models [] :values []})
        project (append-node! graph :project {})]
    (when-not (and (number? fps) (Double/isFinite (double fps)) (pos? fps))
      (unsupported! "Native project frame rate must be finite and positive." {}))
    (doseq [{:keys [name source]} skeletons]
      (source-name! name :skeleton)
      (validate-native-source! source fps))
    (swap! graph assoc :root project)
    (let [references
          (mapv (fn [{:keys [name source]}]
                  (let [reference (append-node! graph :skeleton {})
                        fields {0 project 2 true 3 (append-node! graph :string (get-in source ["skeleton" "images"] "./images/"))
                                34 (append-node! graph :string (get-in source ["skeleton" "audio"] "./audio"))
                                24 false 33 false 35 true 42 [(append-node! graph :string name)]
                                43 false 46 (get-in source ["skeleton" "referenceScale"] 100.0) 47 true 48 true 126 false}
                        fields (reduce (fn [fields tag] (assoc fields tag (append-node! graph :array [])))
                                       fields [14 23 16 10 36 13 22 4 6 41 8 7])
                        fields (reduce (fn [fields tag] (assoc fields tag (append-node! graph :empty-map [])))
                                       fields [29 26 31 32 30 27 28])
                        fields (reduce (fn [fields tag]
                                         (assoc fields tag (append-node! graph :folder
                                                                        {0 nil 3 nil 5 (append-node! graph :array []) 1 nil 2 reference})))
                                       fields [40 45 44 37 38])]
                    (replace-node! graph reference fields))) skeletons)
          fields {0 (append-node! graph :array references) 19 0 8 1.0 38 false 42 false 2 fps
                  20 (append-node! graph :string "4.2.43") 17 1.0 41 12 40 true 15 1.0
                  4 false 34 1.0 28 true 37 false 32 1.0 13 false 35 true}
          fields (reduce (fn [fields tag] (assoc fields tag (append-node! graph :vector [0.0 0.0 0.0])))
                         fields [31 33 14 7 16])
          fields (reduce (fn [fields tag] (assoc fields tag (append-node! graph :empty-38 [])))
                         fields [36 30 29])]
      (replace-node! graph project fields)
      (reduce-kv (fn [graph index {:keys [source]}]
                   (update-skeleton graph index source)) @graph skeletons))))

(defn relocate-skeleton-directories
  "Replace only one skeleton's image/audio directory strings. Used before Save
  As to preserve relative asset locations for every skeleton in a project.
  Shared strings are never mutated; all other native fields remain untouched."
  [graph skeleton-index images audio]
  (require-complete-document! graph)
  (when-not (and (string? images) (string? audio))
    (unsupported! "Native image and audio directories must be strings." {}))
  (let [reference (:reference (get (graph-skeletons graph) skeleton-index))]
    (when-not reference
      (unsupported! "Native skeleton index is out of range." {:skeleton-index skeleton-index}))
    (let [result (atom graph)
          fields (graph-data graph reference)]
      (replace-node! result reference
                     (assoc fields 3 (append-node! result :string images)
                                   34 (append-node! result :string audio)))
      @result)))
