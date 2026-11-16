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

(ns defold-spine-agent
  "Local skill adapter for the fork's open skeletal editor documents."
  (:require [cljfx.plorer :as p]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [dynamo.graph :as g]
            [editor.defold-project :as project]
            [editor.disk :as disk]
            [editor.resource :as resource]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-geometry :as geometry]
            [editor.skeletal-animation-mesh :as mesh]
            [editor.skeletal-animation-slots :as slots]
            [editor.skeletal-animation-view :as view]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [java.nio.file Files]
           [java.security MessageDigest]
           [java.util HexFormat UUID]
           [javafx.scene.control ComboBox TextField]
           [javafx.stage Window]))

(defonce session-id (str (UUID/randomUUID)))
(defonce history (atom {}))

(def operations
  ["bone.add" "bone.update" "bone.remove" "slot.add" "animation.add"
   "animation.update" "key.set" "key.remove" "deform.set" "mesh.grid"
   "mesh.bind" "mesh.auto-weight" "mesh.restore-image" "ik.add" "ik.update"
   "path.add" "path.update"])

(defn fail! [code message]
  (throw (ex-info message {:code code})))

(defn revision [document]
  (str session-id ":" (.formatHex (HexFormat/of)
                                  (.digest (MessageDigest/getInstance "SHA-256")
                                           (.getBytes (pr-str document) "UTF-8")))))

(defn summary [document]
  {:name (:name document) :active-skin (:active-skin document "default")
   :bones (mapv #(select-keys % [:id :parent :length]) (:bones document))
   :slots (mapv (fn [slot]
                  {:id (:id slot) :bone (:bone slot) :skin (:spine-skin slot "default")
                   :attachment (select-keys (:attachment slot) [:type :name :path :width :height])
                   :vertices (quot (count (get-in slot [:attachment :vertices])) 2)
                   :influences (mesh/binding-bones (:attachment slot))}) (:slots document))
   :animations (mapv #(hash-map :id (:id %) :duration (:duration %) :tracks (count (:tracks %)) :deforms (count (:deforms %))) (:animations document))
   :constraints (select-keys document [:ik :path :transform :physics])})

(defn apply-operation [document {:keys [op id values animation bone property time value interpolation slot bones columns rows vertices options skin name]}]
  (case op
    "bone.add" (data/add-bone document values)
    "bone.update" (data/update-bone document id values)
    "bone.remove" (data/remove-bone document id)
    "slot.add" (data/add-slot document values)
    "animation.add" (data/add-animation document values)
    "animation.update" (data/update-animation document id values)
    "key.set" (data/set-keyframe document animation bone property time value (or interpolation "linear"))
    "key.remove" (data/remove-keyframe document animation bone property time)
    "deform.set" (data/set-deform-keyframe document animation slot time vertices (or interpolation "linear"))
    "ik.add" (data/add-ik document values)
    "ik.update" (data/update-ik document id values)
    "path.add" (data/add-path document values)
    "path.update" (data/update-path document id values)
    "mesh.restore-image"
    (if-let [restore (ns-resolve 'editor.skeletal-animation-attachments 'restore-image)]
      (restore document skin slot name)
      (fail! "UNSUPPORTED" "This editor does not have mesh.restore-image; update the editor first."))
    ("mesh.grid" "mesh.bind" "mesh.auto-weight")
    (let [entry (or (coll/first-where #(= slot (:id %)) (:slots document))
                    (fail! "NOT_FOUND" "Slot not found."))
          attachment (:attachment entry)]
      (if (= op "mesh.grid")
        (do (when-not (= "region" (:type attachment "region"))
              (fail! "WRONG_TYPE" "Create a grid from a region; use remeshing for an existing mesh."))
            (data/update-slot document slot {:attachment (geometry/region-to-mesh attachment (or columns 3) (or rows 3))}))
        (let [worlds (data/world-transforms (:bones document))
              binding {:bones (:bones document) :bone-transforms worlds :slot-transform (get worlds (:bone entry))}
              result (if (= op "mesh.bind")
                       (mesh/change-binding attachment bones binding)
                       (mesh/bind-weights attachment bones binding
                                          (cond-> (or options {})
                                            (:locked-bones options) (update :locked-bones set)
                                            (:selected-bones options) (update :selected-bones set))))]
          (data/apply-mesh-edit document slot result))))
    (fail! "UNKNOWN_OPERATION" (str "Unknown operation: " op))))

(defn proposal [document edits]
  (when-not (and (vector? edits) (<= 1 (count edits) 100))
    (fail! "INVALID_BATCH" "Send 1–100 operations per batch."))
  (data/validate-document (reduce apply-operation document edits)))

(defn contexts []
  (coll/into-> (vec (Window/getWindows)) []
    (mapcat #(p/all % "#skeletal-workspace"))
    (keep #(ui/user-data % :editor.skeletal-animation-view/context))
    (distinct)))

(defn identity-of [context]
  {:project (.getCanonicalPath (io/file (:project-root context)))
   :resource (resource/proj-path (g/node-value (:resource-node context) :resource))})

(defn ensure-idle! [context]
  (when (coll/any? #(get @(:state context) %)
                   [:drag :draft :pending-pose :discarded-pose :binding :playing :mixer-enabled :mix-enabled :mix-transition])
    (fail! "EDITOR_BUSY" "Finish the gesture, pause playback, and commit/cancel pending pose or binding before editing.")))

(defn capture-edit [context request]
  (ensure-idle! context)
  (let [before (g/node-value (:resource-node context) :document)]
    (when-not (= (:revision request) (revision before))
      (fail! "REVISION_CONFLICT" "The document/session changed. Read a new snapshot; do not replay blindly."))
    before))

(defn commit-proposal! [context request before after]
  (when-not (= before (capture-edit context request))
    (fail! "REVISION_CONFLICT" "Document changed while computing the proposal."))
  (if-not (true? (:apply request))
    {:dry-run true :revision (revision before) :proposal (summary after)}
    (let [token (str (UUID/randomUUID))]
      (#'view/commit! context after)
      (when-not (= before after)
        (swap! history assoc (:resource-node context)
               {:token token :undo (:undo @g/*the-system*) :after after :before before}))
      {:applied (not= before after) :revision (revision after)
       :undo-token (when-not (= before after) token) :document (summary after)})))

(defn edit! [context request]
  (let [before (capture-edit context request)]
    (commit-proposal! context request before (proposal before (:operations request)))))

(defn find-context [request]
  (let [wanted {:project (.getCanonicalPath (io/file (:project request))) :resource (:resource request)}
        matches (filterv #(= wanted (identity-of %)) (contexts))]
    (when-not (= 1 (count matches))
      (fail! "DOCUMENT_NOT_OPEN" "Open exactly one skeletal editor tab for this project/resource, then list again."))
    (first matches)))

(defn undo! [context request]
  (ensure-idle! context)
  (let [entry (get @history (:resource-node context))]
    (when-not (and entry (= (:undo-token request) (:token entry))
                   (= (:revision request) (revision (g/node-value (:resource-node context) :document)))
                   (= (:after entry) (g/node-value (:resource-node context) :document))
                   (= (:undo entry) (:undo @g/*the-system*)))
      (fail! "HISTORY_CHANGED" "Only the latest unchanged agent edit can be undone here. Inspect history in the editor."))
    (g/undo! :undo/global)
    (swap! history dissoc (:resource-node context))
    {:undone true :revision (revision (g/node-value (:resource-node context) :document))}))

(defn disk-revision [context]
  (let [file (io/file (resource/abs-path (g/node-value (:resource-node context) :resource)))]
    (when (.isFile file)
      (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (Files/readAllBytes (.toPath file)))))))

(defn sample-document [document {:keys [animation times slot bones vertices]}]
  (let [clip (or (coll/first-where #(= animation (:id %)) (:animations document))
                 (fail! "NOT_FOUND" "Animation not found."))]
    (when-not (and (vector? times) (<= 1 (count times) 241)
                   (coll/every? #(and (number? %) (Double/isFinite (double %)) (<= 0.0 % (:duration clip))) times)
                   (vector? bones) (<= (count bones) 64)
                   (coll/every? #(coll/any? (fn [bone] (= % (:id bone))) (:bones document)) bones)
                   (coll/any? #(= slot (:id %)) (:slots document)))
      (fail! "INVALID_SAMPLE" "Use 1–241 in-range times, one existing slot and at most 64 existing bones."))
    {:animation animation :frames
     (mapv (fn [time]
             (let [sampled (slots/sample-slots document animation time)
                   entry (coll/first-where #(= slot (:id %)) (:slots sampled))
                   pose (data/evaluate-pose-with-slots document animation time slots/sample-slots)
                   attachment (:attachment entry)
                   xy (case (:type attachment)
                        "mesh" (partitionv 2 (geometry/mesh-world-vertices attachment (get (:transforms pose) (:bone entry)) (:transforms pose) (get (:deforms sampled) slot)))
                        "region" (partitionv 2 (geometry/mesh-world-vertices (geometry/region-to-mesh attachment 1 1) (get (:transforms pose) (:bone entry)) (:transforms pose) []))
                        (fail! "UNSUPPORTED_SAMPLE" "Sample a mesh or region attachment."))]
               (cond-> {:time time :bones (select-keys (:transforms pose) bones)
                        :bounds (when (pos? (count xy))
                                  [(transduce (map first) min Double/POSITIVE_INFINITY xy)
                                   (transduce (map second) min Double/POSITIVE_INFINITY xy)
                                   (transduce (map first) max Double/NEGATIVE_INFINITY xy)
                                   (transduce (map second) max Double/NEGATIVE_INFINITY xy)])}
                 vertices (assoc :vertices xy)))) times)}))

(defn clip-control! [context key]
  (let [control (get context key)]
    (when-not (and (.getScene ^javafx.scene.Node control) (.isShowing (.getWindow (.getScene ^javafx.scene.Node control))))
      (let [menu (p/one (:root context) "#skeletal-clip-controls")]
        (p/mouse-click! (.getWindow (.getScene menu)) (p/point menu 0.5 0.5) :primary)))
    (p/one #(identical? control %))))

(defn choose! [context selector value]
  (let [^ComboBox control (if (= selector "#skeletal-animation")
                            (clip-control! context :animation-choice)
                            (p/one (:root context) selector))
        index (.indexOf (.getItems control) value)
        window (.getWindow (.getScene control))]
    (when (or (neg? index) (.isDisabled control))
      (fail! "CONTROL_UNAVAILABLE" (str "Cannot select " value " in " selector)))
    (.requestFocus control)
    (p/key-tap! window :home)
    (dotimes [_ index] (p/key-tap! window :down))
    (p/key-tap! window :enter)
    (when-not (= value (.getValue control))
      (fail! "PREVIEW_FAILED" "The animation control did not accept selection."))))

(defn preview! [context request]
  (let [state @(:state context)
        document (g/node-value (:resource-node context) :document)
        clip (coll/first-where #(= (:animation request) (:id %)) (:animations document))
        frame (:frame request 0)
        fps (:fps state 60.0)]
    (when (coll/any? #(get state %) [:drag :draft :pending-pose :discarded-pose :binding])
      (fail! "EDITOR_BUSY" "Finish the current edit before previewing."))
    (when-not (and clip (number? frame) (Double/isFinite (double frame)) (<= 0 frame (* fps (:duration clip))))
      (fail! "INVALID_PREVIEW" "Select an existing animation and a frame within its duration."))
    (choose! context "#skeletal-mode" "animate")
    (#'view/refresh! context 0.0)
    (choose! context "#skeletal-animation" (:animation request))
    (#'view/refresh! context 0.0)
    (let [^TextField control (clip-control! context :current-frame)
          window (.getWindow (.getScene control))]
      (.requestFocus control)
      (p/key-chord! window [(if (.contains (System/getProperty "os.name") "Mac") :meta :control) :a])
      (doseq [c (.toPlainString (bigdec frame))]
        (p/key-tap! window (if (= c \.) :period (keyword (str "digit" c)))))
      (p/key-tap! window :enter))
    (p/key-tap! (.getWindow (.getScene ^javafx.scene.Node (:root context))) :escape)
    (#'view/refresh! context 0.0)
    (when (true? (:fit request))
      (let [button (p/one (:root context) "#skeletal-mode-overlay" "#skeletal-fit")]
        (p/mouse-click! (.getWindow (.getScene button)) (p/point button 0.5 0.5) :primary)
        (#'view/refresh! context 0.0)))
    (when (true? (:playing request))
      (let [button (p/one (:root context) "#skeletal-play")]
        (p/mouse-click! (.getWindow (.getScene button)) (p/point button 0.5 0.5) :primary)))
    {:editor (select-keys @(:state context) [:mode :animation :time :playing :fps])
     :revision (revision (g/node-value (:resource-node context) :document))}))

(defn save-document! [request]
  (when-not (and (true? (:apply request)) (string? (:disk-revision request)))
    (fail! "EXPLICIT_SAVE_REQUIRED" "Saving requires apply=true, revision and disk-revision from inspect."))
  (let [owner (str (UUID/randomUUID))
        [context document snapshot]
        (ui/run-now
          (let [context (find-context request)
                document (capture-edit context request)]
            (when-not (= (:disk-revision request) (disk-revision context))
              (fail! "DISK_CONFLICT" "The file changed externally. Resolve before saving."))
            (when-not (disk/begin-managed-write! owner)
              (fail! "EDITOR_BUSY" "Another save/build/reload is active."))
            (try
              [context document (disk/capture-save-snapshot (or (:project context) (project/get-project (g/now))) [(:resource-node context)])]
              (catch Exception e
                (disk/end-managed-write! owner)
                (throw e)))))]
    (try
      (disk/write-save-snapshot! snapshot)
      (ui/run-now
        {:saved true :saved-revision (revision document)
         :revision (revision (g/node-value (:resource-node context) :document))
         :dirty (g/node-value (:resource-node context) :dirty)
         :disk-revision (disk-revision context) :resource (:resource request)})
      (finally (ui/run-now (disk/end-managed-write! owner))))))

(defn dispatch! [request]
  (case (:action request)
    "capabilities" {:protocol 2 :session session-id :transport "local-nrepl"
                    :actions ["list" "inspect" "read" "edit" "undo" "preview" "snapshot" "sample" "save"]
                    :operations (filterv #(or (not= "mesh.restore-image" %) (ns-resolve 'editor.skeletal-animation-attachments 'restore-image)) operations)
                    :persists-to-disk "explicit save only" :runtime-engine-changes false}
    "list" {:documents (mapv (fn [context]
                               (assoc (identity-of context) :name (:name (g/node-value (:resource-node context) :document)))) (contexts))}
    (let [context (find-context request)
          wanted (identity-of context)
          doc (g/node-value (:resource-node context) :document)]
      (case (:action request)
        "inspect" (merge wanted {:revision (revision doc) :disk-revision (disk-revision context) :dirty (g/node-value (:resource-node context) :dirty) :document (summary doc)
                                 :editor (select-keys @(:state context) [:mode :animation :time :playing :selected-kind :selected :rigging-panel])
                                 :pending (boolean (coll/any? #(get @(:state context) %) [:draft :drag :pending-pose :binding]))})
        "read" (let [section (keyword (:section request))]
                 (when-not (contains? #{:bones :slots :animations :ik :path :transform :physics} section)
                   (fail! "UNKNOWN_SECTION" "Read bones, slots, animations, ik, path, transform or physics."))
                 {:revision (revision doc) :items (filterv #(= (:id request) (:id %)) (get doc section []))})
        "preview" (preview! context request)
        "snapshot" {:path (p/screenshot! (:viewport context)) :editor (select-keys @(:state context) [:mode :animation :time :playing])}
        "edit" (edit! context request)
        "undo" (undo! context request)
        (fail! "UNKNOWN_ACTION" "Use capabilities, list, inspect, read, edit, undo, preview, snapshot, sample or save.")))))

(defn handle-json [text]
  (try
    (let [request (json/read-str text :key-fn keyword)
          result (case (:action request)
                   "edit" (let [[context before] (ui/run-now
                                                   (let [context (find-context request)]
                                                     [context (capture-edit context request)]))
                                after (proposal before (:operations request))]
                            (ui/run-now (commit-proposal! context request before after)))
                   "sample" (let [document (ui/run-now (g/node-value (:resource-node (find-context request)) :document))]
                              (assoc (sample-document document request) :revision (revision document)))
                   "save" (save-document! request)
                   (ui/run-now (dispatch! request)))]
      (json/write-str {:ok true :result result}))
    (catch Exception e
      (json/write-str {:ok false :error {:code (or (:code (ex-data e)) "EDITOR_ERROR") :message (.getMessage e)}}))))
