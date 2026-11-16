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

(ns editor.skeletal-animation-performance-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-io :as animation-io]
            [editor.skeletal-animation-mesh :as mesh]
            [editor.skeletal-animation-render :as render]
            [editor.skeletal-animation-slots :as slots]
            [editor.skeletal-animation-view :as view]))

;; Preview results must be reused without retaining a gesture's entire history.
;; Equal but distinct immutable sources cannot accidentally reuse old objects.
(deftest bounded-preview-cache-test
  (let [cache (atom {}) context {:preview-cache cache} calls (atom 0)
        source {:value 1} produce #(swap! calls inc)]
    (is (= 1 (#'view/preview-value context :slots source [:clip 0.0] produce)))
    (dotimes [_ 100] (is (= 1 (#'view/preview-value context :slots source [:clip 0.0] produce))))
    (is (= 1 @calls))
    (is (= 2 (#'view/preview-value context :slots (into {} source) [:clip 0.0] produce)))
    (doseq [i (range 100)] (#'view/preview-value context :slots {:value i} [:clip i] produce))
    (is (= 2 (count (:slots @cache))))
    (is (= 102 (:value (first (:slots @cache)))))
    (is (= 102 @calls))))

;; Cached pose and slot samples must invalidate on time, setup mode and
;; edited document, and match an uncached evaluation after reverse seeking.
(deftest preview-sampling-invalidation-test
  (let [doc (-> (data/new-document "cache")
                (data/add-bone {:id "child" :parent "root" :length 20.0})
                (data/set-keyframe "default" "child" "rotation" 0.0 0.0 "linear")
                (data/set-keyframe "default" "child" "rotation" 1.0 90.0 "linear"))
        state (atom {:mode "animate" :animation "default" :time 0.2})
        context {:state state :preview-cache (atom {}) :pose-cache (atom nil)}
        first-pose (#'view/evaluated-pose context doc)
        first-slots (#'view/sampled-slots context doc)]
    (is (identical? first-pose (#'view/evaluated-pose context doc)))
    (is (identical? first-slots (#'view/sampled-slots context doc)))
    (doseq [time [0.3 0.8 0.1 1.0 0.0]]
      (swap! state assoc :time time)
      (is (= (data/evaluate-pose-with-slots doc "default" time slots/sample-slots) (#'view/evaluated-pose context doc))))
    (swap! state assoc :mode "setup")
    (is (= (data/evaluate-pose-with-slots doc nil 0.0 slots/sample-slots) (#'view/evaluated-pose context doc)))
    (let [changed (data/update-bone doc "child" {:x 30.0})]
      (is (not= (#'view/evaluated-pose context doc) (#'view/evaluated-pose context changed))))
    (is (<= (count (:pose @(:preview-cache context))) 2))))

;; A mixer can change at the same document and time; its authoritative result
;; bypasses the single-clip cache instead of showing stale geometry.
(deftest mixed-preview-cache-bypass-test
  (let [doc (data/new-document "mix") state (atom {:mode "animate" :animation "default" :time 0.0})
        context {:state state :preview-cache (atom {})}]
    (#'view/evaluated-pose context doc)
    (#'view/sampled-slots context doc)
    (doseq [i (range 3)]
      (let [sample {:pose {:marker i} :slots [] :deforms {}}]
        (swap! state assoc :mixer-enabled true :mixer-state {:document doc} :mixer-sample sample)
        (is (= (:pose sample) (#'view/evaluated-pose context doc)))
        (is (= sample (#'view/sampled-slots context doc)))))))

;; Repeated animation exports reuse validated mesh layouts, while changed UVs
;; still go through validation; retained meshes have a hard bound.
(deftest export-layout-cache-test
  #_{:clj-kondo/ignore [:discouraged-var]}
  (with-redefs-fn {#'mesh/export-layout-cache (atom [])}
    (fn verify-export-layout-cache []
      (let [mesh {:type "mesh" :vertices [0.0 0.0 10.0 0.0 10.0 10.0 0.0 10.0]
                  :uvs [0.0 0.0 1.0 0.0 1.0 1.0 0.0 1.0] :triangles [0 1 2 0 2 3]}
            first-layout (mesh/spine-export-layout mesh)]
        (is (identical? first-layout (mesh/spine-export-layout mesh)))
        (let [changed (assoc mesh :vertices [0.0 0.0 20.0 0.0 10.0 10.0 0.0 10.0])]
          (is (= [0.0 0.0 20.0 0.0 10.0 10.0 0.0 10.0] (get-in (mesh/spine-export-layout changed) [:mesh :vertices]))))
        (is (thrown? clojure.lang.ExceptionInfo (mesh/spine-export-layout (assoc mesh :uvs [0.0 0.0 1.0 1.0 1.0 0.0 0.0 1.0]))))
        (doseq [i (range 100)] (mesh/spine-export-layout (assoc mesh :name (str i))))
        (is (= 32 (count @@#'mesh/export-layout-cache)))))))

;; Preview memoization must not skip Physics replay or reuse a spring pose
;; after reverse seeking or an edit to the physical parameters.
(deftest physics-preview-cache-invalidation-test
  (let [doc (-> (data/new-document "physics-cache")
                (data/add-bone {:id "child" :parent "root" :length 20.0})
                (data/add-physics {:id "spring" :bone "child" :x 1.0 :gravity 8.0}))
        state (atom {:mode "animate" :animation "default" :time 0.0})
        context {:state state :preview-cache (atom {}) :pose-cache (atom nil)}]
    (doseq [time [0.0 0.3 0.7 0.2]]
      (swap! state assoc :time time)
      (is (= (data/evaluate-pose-with-slots doc "default" time slots/sample-slots) (#'view/evaluated-pose context doc))))
    (let [edited (data/update-physics doc "spring" {:gravity 2.0})]
      (is (= (data/evaluate-pose-with-slots edited "default" 0.2 slots/sample-slots) (#'view/evaluated-pose context edited))))))

;; Material warnings must match the full exporter, including deleted slots and
;; clips, without serializing all mesh/FFD data on mouse release.
(deftest compatibility-without-export-test
  (let [base (-> (data/new-document "compatibility")
                 (assoc :transform [] :path [] :physics [])
                 (data/add-slot {:id "slot" :bone "root"}))
        timeline (fn timeline [clip slot property]
                   (assoc base :spine-source
                          (json/write-str {"animations" {clip {"slots" {slot {property [{"light" "ffffffff" "dark" "000000"}]}}}}})))
        cases [[base false]
               [(data/update-slot base "slot" {:dark-color [0.1 0.2 0.3]}) true]
               [(assoc base :spine-source (json/write-str {"slots" [{"name" "slot" "dark" "112233"}]})) false]
               [(timeline "default" "slot" "rgba2") true]
               [(timeline "default" "slot" "rgb2") true]
               [(timeline "default" "removed" "rgba2") false]
               [(timeline "removed" "slot" "rgba2") false]]]
    (doseq [[doc expected] cases]
      (let [exported (json/read-str (data/export-spine-json doc))
            expected-warning (if expected [{:target :defold :severity :warning :code :default-material-dark}] [])]
        (is (= expected (boolean (or (get-in exported ["slots" 0 "dark"])
                                     (get-in exported ["animations" "default" "slots" "slot" "rgba2"])
                                     (get-in exported ["animations" "default" "slots" "slot" "rgb2"])))))
        #_{:clj-kondo/ignore [:discouraged-var]}
        (with-redefs [data/export-spine-json (fn unexpected-export [_] (throw (ex-info "Unexpected full export" {})))]
          (is (= expected-warning (animation-io/export-compatibility doc))))))))

;; Repeated overlays must share world vertices, but never reuse coordinates
;; after animation deforms, poses, attachments or setup/animate mode change.
(deftest mesh-world-points-cache-invalidation-test
  (doseq [weighted [false true]]
    (let [attachment (cond-> {:type "mesh" :vertices [0.0 0.0 10.0 0.0 0.0 10.0]}
                       weighted (assoc :weights [[{:bone "root" :weight 1.0 :x 0.0 :y 0.0}]
                                                 [{:bone "root" :weight 1.0 :x 10.0 :y 0.0}]
                                                 [{:bone "root" :weight 1.0 :x 0.0 :y 10.0}]]))
          slot {:id "mesh" :bone "root" :attachment attachment}
          state (atom {:mode "animate"})
          context {:state state :preview-cache (atom {}) :sampled-deforms {"mesh" [1.0 2.0 3.0 4.0 5.0 6.0]}}
          transforms {"root" [1.0 0.0 0.0 1.0 0.0 0.0]}
          points (#'view/attachment-points context {} slot transforms)]
      (is (= [1.0 2.0 13.0 4.0 5.0 16.0] points))
      (is (identical? points (#'view/attachment-points context {} slot transforms)))
      (is (= [8.0 11.0 20.0 13.0 12.0 25.0]
             (#'view/attachment-points context {} slot {"root" [1.0 0.0 0.0 1.0 7.0 9.0]})))
      (is (= (:vertices attachment) (#'view/attachment-points (assoc context :sampled-deforms {}) {} slot transforms)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (#'view/attachment-points (assoc context :sampled-deforms {"mesh" [1.0]}) {} slot transforms)))
      (swap! state assoc :mode "setup")
      (is (= (:vertices attachment) (#'view/attachment-points context {} slot transforms)))
      (let [edited (if weighted (assoc-in attachment [:weights 0 0 :x] 2.0)
                       (assoc-in attachment [:vertices 0] 2.0))]
        (is (= 2.0 (first (#'view/attachment-points context {} (assoc slot :attachment edited) transforms)))))
      (doseq [x (range 20)] (#'view/attachment-points context {} slot {"root" [1.0 0.0 0.0 1.0 (double x) 0.0]}))
      (is (= 2 (count (:mesh-points @(:preview-cache context))))))))

;; Handlers, the numeric palette and drawing share one manipulator frame.
;; Pointer-only updates preserve it; selection, pivot and document edits do not.
(deftest mesh-manipulator-cache-invalidation-test
  (let [attachment {:type "mesh" :path "/mesh.png" :width 10.0 :height 10.0
                    :vertices [0.0 0.0 10.0 0.0 0.0 10.0] :uvs [0.0 0.0 1.0 0.0 0.0 1.0] :triangles [0 1 2]}
        doc (data/add-slot (data/new-document "frame-cache") {:id "mesh" :bone "root" :attachment attachment})
        state (atom {:mode "setup" :selected-kind :slot :selected "mesh" :tool "move" :vertices #{}})
        context {:state state :preview-cache (atom {}) :pose-cache (atom nil)}
        frame (#'view/mesh-manipulator context doc)]
    (swap! state assoc :pointer [4.0 5.0])
    (is (identical? frame (#'view/mesh-manipulator context doc)))
    (swap! state assoc :vertices #{1})
    (let [selected (#'view/mesh-manipulator context doc)]
      (is (= [10.0 0.0] (:center selected)))
      (swap! state assoc :mesh-pivot {:token (:token selected) :point [30.0 40.0]})
      (is (= [30.0 40.0] (:pivot (#'view/mesh-manipulator context doc)))))
    (let [edited (data/update-bone doc "root" {:x 20.0})]
      (is (= [30.0 0.0] (:center (#'view/mesh-manipulator context edited)))))
    ;; Blended/explicit FFD samples can change without changing time or bones.
    (swap! state assoc :mode "animate" :animation "default" :time 0.0 :mesh-pivot nil)
    (let [sampled (slots/sample-slots doc "default" 0.0)
          base (assoc context :sampled-slots sampled :sampled-deforms {"mesh" [0.0 0.0 0.0 0.0 0.0 0.0]})
          deformed (assoc base :sampled-deforms {"mesh" [0.0 0.0 5.0 3.0 0.0 0.0]})]
      (is (= [10.0 0.0] (:center (#'view/mesh-manipulator base doc))))
      (is (= [15.0 3.0] (:center (#'view/mesh-manipulator deformed doc)))))
    (is (= 2 (count (:mesh-manipulator @(:preview-cache context)))))))

;; Screen movement must not rebuild fixed UV/triangle lists, while a real
;; topology edit must invalidate them before rendering or drawing wireframes.
(deftest render-topology-cache-invalidation-test
  (let [attachment {:type "mesh" :path "" :vertices [0.0 0.0 10.0 0.0 0.0 10.0]
                    :uvs [0.0 0.0 1.0 0.0 0.0 1.0] :triangles [0 1 2]}
        doc {:slots [{:id "mesh" :bone "root" :attachment attachment}]}
        state (atom {:mode "setup" :zoom 1.0 :pan-x 0.0 :pan-y 0.0})
        context {:state state :preview-cache (atom {})}
        pose {:transforms {"root" [1.0 0.0 0.0 1.0 0.0 0.0]} :active-bone-ids #{"root"}}
        first-slot (first (#'view/render-slots context doc doc pose))]
    (is (= [0.0 0.0 1.0 0.0 0.0 1.0] (mapv double (render/uv-buffer (:uvs first-slot)))))
    (is (= [0 0 1 1 2 2] (vec (render/face-buffer (:triangles first-slot) 3))))
    (swap! state assoc :pan-x 10.0)
    (let [moved (first (#'view/render-slots context doc doc pose))]
      (is (identical? (:triangles first-slot) (:triangles moved)))
      (is (identical? (:uvs first-slot) (:uvs moved)))
      (is (not= (:points first-slot) (:points moved))))
    (let [edited (assoc-in doc [:slots 0 :attachment :triangles] [2 1 0])]
      (is (= [[2 1 0]] (:triangles (first (#'view/render-slots context edited edited pose))))))))

;; Timeline reads must not export fresh meshes. A mesh can have editable UVs
;; that are not exportable yet; querying FFD lanes must still work normally.
(deftest fresh-mesh-timeline-without-export-test
  (let [attachment {:type "mesh" :path "/mesh.png" :width 10.0 :height 10.0
                    :vertices [0.0 0.0 10.0 0.0 10.0 10.0 0.0 10.0]
                    :uvs [0.0 0.0 1.0 1.0 1.0 0.0 0.0 1.0] :triangles [0 1 2 0 2 3]}
        offsets [1.0 2.0 0.0 0.0 0.0 0.0 0.0 0.0]
        doc (-> (data/new-document "unexported-mesh")
                (data/add-slot {:id "mesh" :bone "root" :attachment attachment})
                (data/set-deform-keyframe "default" "mesh" 0.0 offsets "linear"))]
    (is (thrown? clojure.lang.ExceptionInfo (data/export-spine-json doc)))
    (let [invalid (assoc-in doc [:slots 0 :bone] "missing")]
      (is (thrown? clojure.lang.ExceptionInfo (slots/tracks invalid "default" "mesh")))
      (is (thrown? clojure.lang.ExceptionInfo (slots/draw-order-track invalid "default"))))
    (is (= ["mesh"] (slots/attachment-names doc "mesh")))
    (is (= [] (slots/tracks doc "default" "mesh")))
    (is (= [] (:keys (slots/draw-order-track doc "default"))))
    (is (= offsets (get-in (slots/deform-track doc "default" "mesh" 0.0) [:keys 0 :vertices])))
    (let [materialized (slots/materialize-tracks doc)]
      (is (= offsets (get-in materialized [:animations 0 :deforms 0 :keys 0 :vertices])))
      (is (= [] (get-in materialized [:animations 0 :sequence-tracks]))))))

;; Parsed source caching must preserve Spine channel order, remain bounded
;; across source edits, and never retain failed JSON decodes.
(deftest parsed-spine-source-cache-test
  #_{:clj-kondo/ignore [:discouraged-var]}
  (with-redefs-fn {#'slots/parsed-source-cache (atom [])}
    (fn verify-parsed-source-cache []
      (let [text "{\"z\":1,\"a\":2}"
            parsed (#'slots/parsed-source text)]
        (is (identical? parsed (#'slots/parsed-source text)))
        (is (= ["z" "a"] (vec (.keySet ^java.util.Map (:ordered parsed)))))
        (doseq [i (range 20)] (#'slots/parsed-source (json/write-str {"version" i})))
        (is (= 4 (count @@#'slots/parsed-source-cache)))
        (is (thrown? Exception (#'slots/parsed-source "{")))
        (is (= 4 (count @@#'slots/parsed-source-cache)))))))

;; Mutable preview storage must never escape into committed FFD or undo
;; snapshots; changing selection invalidates the cached influence plan.
(deftest reusable-deform-buffer-test
  (let [attachment {:type "mesh" :path "/mesh.png" :width 10.0 :height 10.0
                    :vertices [0.0 0.0 10.0 0.0 0.0 10.0] :uvs [0.0 0.0 1.0 0.0 0.0 1.0] :triangles [0 1 2]}
        doc (data/add-slot (data/new-document "buffer") {:id "mesh" :bone "root" :attachment attachment})
        context {:state (atom {:mode "animate" :animation "default" :time 0.5 :interpolation "linear" :selected-kind :slot :selected "mesh"})
                 :preview-cache (atom {}) :pose-cache (atom nil)}
        first-doc (#'view/transform-vertices context doc #{1} [1.0 0.0 0.0 1.0 2.0 3.0])
        buffer (get-in @(:preview-cache context) [:deform-buffer 0 :value :buffer])
        saved-values (mapv double (get-in first-doc [:animations 0 :deforms 0 :keys 0 :vertices]))
        second-doc (#'view/transform-vertices context doc #{1} [1.0 0.0 0.0 1.0 4.0 5.0])]
    (is (identical? buffer (get-in @(:preview-cache context) [:deform-buffer 0 :value :buffer])))
    (is (= saved-values (get-in first-doc [:animations 0 :deforms 0 :keys 0 :vertices])))
    (is (< (abs (- 2.0 (saved-values 2))) 1.0e-10))
    (is (< (abs (- 4.0 (get-in second-doc [:animations 0 :deforms 0 :keys 0 :vertices 2]))) 1.0e-10))
    (let [third (#'view/transform-vertices context doc #{2} [1.0 0.0 0.0 1.0 6.0 7.0])]
      (is (= [0.0 0.0 0.0 0.0 6.0 7.0] (get-in third [:animations 0 :deforms 0 :keys 0 :vertices]))))
    (is (nil? (:deforms (first (:animations doc)))))))

;; Hierarchy refresh keys ignore geometric/animation values while preserving
;; parent changes, naming, draw order and search invalidation.
(deftest hierarchy-structure-invalidation-test
  (let [doc (data/add-bone (data/new-document "tree") {:id "child" :parent "root"})
        context {:state (atom {})}
        token (#'view/tree-structure context doc)]
    (is (= token (#'view/tree-structure context (data/update-bone doc "child" {:x 20.0}))))
    (is (= token (#'view/tree-structure context (data/set-keyframe doc "default" "child" "x" 0.5 30.0 "linear"))))
    (is (not= token (#'view/tree-structure context (data/add-bone doc {:id "new" :parent "child"}))))
    (swap! (:state context) assoc :tree-search "child")
    (is (not= token (#'view/tree-structure context doc)))))
