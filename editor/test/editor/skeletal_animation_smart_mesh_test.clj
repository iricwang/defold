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

(ns editor.skeletal-animation-smart-mesh-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-io :as animation-io]
            [editor.skeletal-animation-smart-mesh :as smart]))

(def source
  {"skeleton" {"spine" "4.3.26"}
   "bones" [{"name" "root"}]
   "slots" [{"name" "body" "bone" "root" "attachment" "body"}]
   "skins" [{"name" "default" "attachments" {"body" {"body" {"width" 10 "height" 10}}}}]
   "animations" {"idle" {"bones" {"root" {"rotate" [{"time" 0 "value" 0} {"time" 1 "value" 10}]}}}}})

(def mesh-attachment
  {"type" "mesh" "width" 10 "height" 10 "uvs" [0 0 1 0 0 1] "triangles" [0 1 2]
   "vertices" [1.0 0.0 -5 -5 1.0 1.0 0.0 5 -5 1.0 1.0 0.0 -5 5 1.0] "hull" 3})

;; Spine 4.3 writes integral weight counts and bone indices as JSON floats.
;; Fractional counts and invalid bone indices must still be rejected.
(deftest spine-43-weight-index-test
  (let [source (assoc-in source ["skins" 0 "attachments" "body" "body"] mesh-attachment)]
    (is (= 3 (count (get-in (data/import-spine-json (json/write-str source)) [:slots 0 :attachment :weights]))))
    (doseq [[index value] [[0 1.5] [1 0.5] [1 -1] [1 12]]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (data/import-spine-json (json/write-str (assoc-in source ["skins" 0 "attachments" "body" "body" "vertices" index] value))))))))

;; Smart meshing must affect only the selected active-skin attachment and retain
;; all existing animation, workspace and native source metadata.
;; The licensed subprocess is stubbed; all conversion and validation is real.
(deftest selected-region-only-test
  (doseq [skin ["default" "alternate"]]
    (let [source (cond-> source (= "alternate" skin) (update "skins" conj {"name" "alternate" "attachments" {"body" {"body" {"width" 20 "height" 20}}}}))
          before (assoc (data/select-skin (data/import-spine-json (json/write-str source)) skin) :native-project {:codec "spine-cli"})
          request (atom nil)
          after (:document #_{:clj-kondo/ignore [:discouraged-var]}
                  (with-redefs [smart/run-tool! (fn capture-request [_ value]
                                                  (reset! request value)
                                                  {"attachment" mesh-attachment "report" {"vertices" 3 "triangles" 1}})]
                    (smart/generate! before "/tmp" "body")))]
      (is (= [skin "body" "body"] (get @request "key")))
      (is (map? (get @request "source")))
      (is (= "mesh" (get-in after [:slots 0 :attachment :type])))
      (is (= (dissoc before :slots) (dissoc after :slots)))
      (is (= (get-in before [:slots 0 :spine-skin]) (get-in after [:slots 0 :spine-skin]))))))

;; Remeshing an existing weighted/unweighted mesh retains transform keys and
;; migrates sparse FFD, including private linked-mesh keys in inactive skins.
(deftest remesh-preserves-animation-test
  (doseq [weighted [true false]
          timeline ["attachments" "deform"]]
    (let [old (cond-> mesh-attachment (not weighted) (assoc "vertices" [-5 -5 5 -5 -5 5]))
          frames [{"time" 0 "offset" 2 "vertices" [2 4] "curve" "stepped"}
                  {"time" 1 "vertices" [1 2 3 4 5 6]}]
          track-path (fn track-path [skin name]
                       (if (= "attachments" timeline)
                         ["animations" "idle" "attachments" skin "body" name "deform"]
                         ["animations" "idle" "deform" skin "body" name]))
          fixture (-> source
                      (assoc-in ["skins" 0 "attachments" "body" "body"] old)
                      (update "skins" conj {"name" "alternate" "attachments"
                                            {"body" {"child" {"type" "linkedmesh" "parent" "body" "skin" "default" "timelines" false}
                                                     "grandchild" {"type" "linkedmesh" "parent" "child" "skin" "alternate" "timelines" false}}}})
                      (assoc-in (track-path "default" "body") frames)
                      (assoc-in (track-path "alternate" "child") frames)
                      (assoc-in (track-path "alternate" "grandchild") frames))
          before (data/import-spine-json (json/write-str fixture))
          request (atom nil)
          result #_{:clj-kondo/ignore [:discouraged-var]}
          (with-redefs [smart/run-tool! (fn generated-topology [_ value]
                                          (reset! request value)
                                          {"attachment" {"uvs" [0 0 1 0 0 1 0.25 0.25]
                                                         "triangles" [0 1 3 1 2 3 2 0 3] "edges" [0 2 2 4 4 0]}})]
            (smart/generate! before "/tmp" "body"))
          after (:document result)
          exported (json/read-str (data/export-spine-json after))
          imported (data/import-spine-json (json/write-str exported))]
      (is (= 4 (get-in result [:report "vertices"])))
      (is (= 6 (get-in result [:report "deform_keys_preserved"])))
      (is (= 2 (get-in result [:report "linked_meshes_preserved"])))
      (is (= (:bones before) (:bones after)))
      (is (= (mapv :tracks (:animations before)) (mapv :tracks (:animations after))))
      (is (= weighted (boolean (get-in after [:slots 0 :attachment :weights]))))
      (is (= "region" (get-in @request ["source" "skins" 0 "attachments" "body" "body" "type"])))
      (doseq [[skin name] [["default" "body"] ["alternate" "child"] ["alternate" "grandchild"]]]
        (let [keys (or (get-in exported ["animations" "idle" "attachments" skin "body" name "deform"])
                       (get-in exported ["animations" "idle" "deform" skin "body" name]))]
          (is (= 2 (count keys)))
          (is (= "stepped" (get-in keys [0 "curve"])))
          (is (= 8 (count (get-in keys [1 "vertices"]))))
          (is (= [2.5 3.5] (subvec (get-in keys [1 "vertices"]) 6)))))
      (is (= (data/sample-deforms after "idle" 1.0) (data/sample-deforms imported "idle" 1.0))))))

;; Invalid topology and unsupported attachments cannot yield a replacement
;; document. Existing meshes must remain valid after a tool failure.
(deftest remesh-invalid-topology-test
  (let [document (data/import-spine-json (json/write-str (assoc-in source ["skins" 0 "attachments" "body" "body"] mesh-attachment)))]
    #_{:clj-kondo/ignore [:discouraged-var]}
    (with-redefs [smart/run-tool! (fn invalid-topology [& _] {"attachment" {"uvs" [0 0 1 0 0 1] "triangles" [0 1 10]}})]
      (is (thrown? clojure.lang.ExceptionInfo (smart/generate! document "/tmp" "body")))
      (is (thrown? clojure.lang.ExceptionInfo (smart/generate! document "/tmp" "missing"))))
    (is (= 3 (count (get-in document [:slots 0 :attachment :weights]))))))

;; Never pass a 4.3 document through the native 4.2 encoder.
(deftest native-version-guard-test
  (let [document (data/import-spine-json (json/write-str source))]
    (is (smart/requires-cli? document))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"matching Spine CLI"
                          (animation-io/write-independent-project! document "/tmp" "/tmp/unsupported-43.spine")))
    (is (not (smart/requires-cli? (data/new-document "new"))))))

;; Manual contour adjustment reuses the same safe weight/FFD migration, and
;; invalid self-intersecting edits cannot replace a valid preview.
(deftest revise-traced-contour-test
  (let [doc (data/import-spine-json (json/write-str (assoc-in source ["skins" 0 "attachments" "body" "body"] mesh-attachment)))
        before {:document doc :report {"vertices" 3}}
        after (smart/revise-trace before "body" [0.0 0.0 0.9 0.0 0.0 0.9])]
    (is (= 3 (count (get-in after [:preview :weights]))))
    (is (get-in after [:report "manual"]))
    (is (= (:animations doc) (:animations (:document after))))
    (is (data/import-spine-json (data/export-spine-json (:document after))))
    (is (thrown? clojure.lang.ExceptionInfo (smart/revise-trace before "body" [0 0 1 1 0 1 1 0])))))

;; The preview caches only matching settings and image revisions. Repeated
;; refresh must reuse a validated proposal, while image edits rerun tracing.
(deftest trace-preview-cache-test
  (let [file (java.io.File/createTempFile "trace-cache" ".png")
        document (assoc-in (data/import-spine-json (json/write-str source)) [:slots 0 :attachment :path] (str "/" (.getName file)))
        calls (atom 0)]
    (try
      #_{:clj-kondo/ignore [:discouraged-var]}
      (with-redefs [smart/run-tool! (fn generated-topology [_ _]
                                      (swap! calls inc)
                                      {"attachment" mesh-attachment "report" {"vertices" 3}})]
        (let [propose (smart/trace-proposer document (.getParent file) "body")
              first-result (propose {"detail" 20})]
          (is (identical? first-result (propose {"detail" 20})))
          (is (= 1 @calls))
          (propose {"detail" 21})
          (is (= 2 @calls))
          (spit file "new-image-revision")
          (propose {"detail" 20})
          (is (= 3 @calls))
          (is (= (data/export-spine-data (:document first-result))
                 (json/read-str (data/export-spine-json (:document first-result)))))))
      (finally (.delete file)))))
