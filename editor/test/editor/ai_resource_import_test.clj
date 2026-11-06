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

(ns editor.ai-resource-import-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is]]
            [editor.ai-production :as production]
            [editor.ai-resource-import :as admission]
            [editor.craft-project :as craft-project]
            [util.coll :as coll])
  (:import [java.awt.image BufferedImage]
           [java.nio.file Files]
           [javax.imageio ImageIO]))

(defn fixture []
  (let [root (.toFile (Files/createTempDirectory "ai-admission-" (make-array java.nio.file.attribute.FileAttribute 0)))
        source (io/file root "source.png") image (BufferedImage. 64 64 BufferedImage/TYPE_INT_ARGB)]
    (spit (io/file root "game.project") "[display]\nwidth = 64\nheight = 64\n")
    (ImageIO/write image "png" source) (.flush image)
    (let [output (production/publish! root source {:version 1 :module "reward" :imageWidth 64 :imageHeight 64
                                                   :regions [{:name "confirm" :role "button" :x 0 :y 0 :width 32 :height 32}]} {} [64 64])]
      {:root root :output output :plan (admission/plan! root (:manifest output) "reward")})))

(defn passed [_output] {:status "passed" :compiler "test"})

;; Planning leaves production assets untouched and supports editable Results.
(deftest review-before-write-test
  (let [{:keys [root output plan]} (fixture)]
    (is (= plan (admission/parse-plan (admission/plan-text plan))))
    (is (= ["image" "atlas" "gui"] (mapv :kind (:files plan))))
    (is (coll/every? #(= "create" (:operation %)) (:files plan)))
    (is (not (.exists (io/file root "assets"))))
    (is (.isFile (craft-project/resource-file root (:manifest output))))
    (is (= 3 (count (admission/prepare! root plan))))))

;; Admission rewrites references and preserves source files.
;; Replaying a transaction must not compile twice.
(deftest admission-and-idempotency-test
  (let [{:keys [root output plan]} (fixture)
        calls (atom 0)
        validate! (fn validate-once [output] (swap! calls inc) (passed output))
        imported (admission/apply! root plan validate!)
        atlas (:data (production/validate-resource-text :atlas (slurp (craft-project/resource-file root (:atlas imported)))))
        gui (:data (production/validate-resource-text :gui (slurp (craft-project/resource-file root (:gui imported)))))]
    (is (= "imported" (:status imported)))
    (is (= (:atlas imported) (get-in gui [:textures 0 :texture])))
    (is (string/starts-with? (get-in atlas [:images 0 :image]) "/assets/ui/reward/"))
    (is (.isFile (craft-project/resource-file root (:gui output))))
    (is (= (:gui imported) (:gui (admission/apply! root (admission/parse-plan (admission/plan-text plan)) validate!))))
    (is (= 1 @calls))
    (is (coll/every? #(= "reuse" (:operation %)) (:files (admission/plan! root (:manifest output) "reward"))))))

;; Engine failure restores old files and removes only new admission files.
(deftest rollback-test
  (let [{:keys [root plan]} (fixture)
        target (:target (first (:files plan))) file (craft-project/resource-file root target)
        _ (io/make-parents file) _ (spit file "old image")
        revised (admission/plan! root (:sourceManifest plan) "reward")
        revised (update revised :files #(mapv (fn replace-conflict [entry] (cond-> entry (= "conflict" (:operation entry)) (assoc :operation "replace"))) %))]
    (is (thrown-with-msg? Exception #"故意失败" (admission/apply! root revised (fn fail-compile [_] (throw (ex-info "故意失败" {}))))))
    (is (= "old image" (slurp file)))
    (doseq [entry (rest (:files revised))] (is (not (.exists (craft-project/resource-file root (:target entry))))))
    (is (.isFile (craft-project/resource-file root (:source (first (:files revised))))))))

;; Stale inputs, conflicts and unsafe destinations cannot publish.
(deftest conflict-and-boundary-test
  (let [{:keys [root plan]} (fixture)
        image (:source (first (:files plan)))
        target (craft-project/resource-file root (:target (first (:files plan))))]
    (is (thrown? Exception (admission/prepare! root (assoc-in plan [:files 0 :target] "/../escape.png"))))
    (is (thrown? Exception (admission/prepare! root (assoc-in plan [:files 0 :target] "/ui/ai_generated/reward.png"))))
    (is (thrown? Exception (admission/prepare! root (assoc-in plan [:files 0 :source] "/source.png"))))
    (io/make-parents target) (spit target "external change")
    (is (thrown? Exception (admission/apply! root plan passed)))
    (let [fresh (admission/plan! root (:sourceManifest plan) "reward")]
      (is (= "conflict" (get-in fresh [:files 0 :operation])))
      (is (thrown? Exception (admission/prepare! root fresh))))
    (spit (craft-project/resource-file root image) "modified art")
    (is (thrown? Exception (admission/prepare! root plan)))
    (is (= "external change" (slurp target)))))

;; Renaming a frame also updates its GUI texture reference.
(deftest renamed-frame-test
  (let [{:keys [root plan]} (fixture)
        renamed (assoc-in plan [:files 0 :target] "/assets/ui/reward/confirm.png")
        imported (admission/apply! root renamed passed)
        gui (:data (production/validate-resource-text :gui (slurp (craft-project/resource-file root (:gui imported)))))]
    (is (= "components/confirm" (get-in gui [:nodes 0 :texture])))))

;; Rollback never overwrites edits made by another writer during validation.
(deftest rollback-preserves-concurrent-edits-test
  (let [{:keys [root plan]} (fixture)
        target (craft-project/resource-file root (:target (first (:files plan))))]
    (is (thrown-with-msg? Exception #"人工修改"
                          (admission/apply! root plan (fn concurrent-edit [_] (io/make-parents target) (spit target "human edit") (throw (ex-info "compile failed" {}))))))
    (is (= "human edit" (slurp target)))))

;; A compiler success cannot hide edits made while validation was running.
(deftest post-validation-guard-test
  (let [{:keys [root plan]} (fixture)
        target (craft-project/resource-file root (:target (first (:files plan))))]
    (is (thrown? Exception (admission/apply! root plan (fn modify-during-validation [output] (io/make-parents target) (spit target "human edit") (passed output)))))
    (is (= "human edit" (slurp target)))))

;; Portable rule edits reject traversal and stale saves without changing disk.
(deftest portable-rules-test
  (let [{:keys [root output]} (fixture)
        file (craft-project/resource-file root admission/rules-path)
        original (slurp file :encoding "UTF-8")
        config (assoc-in admission/default-rules [:gui :images] "/art/ui/{module}")
        text (json/write-str config)]
    (admission/save-rules! root original text)
    (is (string/starts-with? (get-in (admission/plan! root (:manifest output) "reward") [:files 0 :target]) "/art/ui/reward/"))
    (is (thrown? Exception (admission/save-rules! root original original)))
    (is (thrown? Exception (admission/save-rules! root text (json/write-str (assoc-in config [:gui :images] "/../{module}")))))
    (is (= text (slurp file :encoding "UTF-8")))))

;; Existing runtime references join the same transaction and restore on failure.
(deftest external-reference-transaction-test
  (let [{:keys [root output]} (fixture)
        file (io/file root "reward.gui_script")
        original (str "local screen = '" (:gui output) "'\n")
        _ (spit file original)
        plan (admission/plan! root (:manifest output) "reward")]
    (is (= ["/reward.gui_script"] (mapv :path (:references plan))))
    (is (thrown? Exception (admission/apply! root plan (fn fail-reference-build [_] (throw (ex-info "compile failure" {}))))))
    (is (= original (slurp file)))
    (let [imported (admission/apply! root plan passed)]
      (is (= (str "local screen = '" (:gui imported) "'\n") (slurp file)))
      (is (= 1 (:count imported)))
      (is (contains? (:paths imported) "/reward.gui_script")))))

;; References created after planning cannot silently keep runtime on old paths.
(deftest changed-reference-set-test
  (let [{:keys [root plan output]} (fixture)]
    (spit (io/file root "late.script") (str "local atlas = \"" (:atlas output) "\""))
    (is (thrown-with-msg? Exception #"工程引用已变化" (admission/prepare! root plan)))))

;; A completed native GUI must retain its runtime nodes and bindings when the
;; staged manifest still points at a component-only preview. Source edits after
;; review, or protected/foreign layout paths, cannot be imported.
(deftest existing-native-layout-admission-test
  (let [{:keys [root output]} (fixture)
        preview (slurp (craft-project/resource-file root (:gui output)))
        source "/game/layouts/reward.gui"
        file (craft-project/resource-file root source)
        full (str preview "\nnodes { id: \"runtime_target\" type: TYPE_BOX position { x: 24.0 y: 12.0 } size { x: 40.0 y: 20.0 } }\n"
                  "nodes { id: \"equip\" parent: \"runtime_target\" type: TYPE_BOX size { x: 18.0 y: 8.0 } }\n")
        _ (io/make-parents file)
        _ (spit file full)
        host (io/file root "system.gui")
        _ (spit host (str "nodes { id: \"reward\" type: TYPE_TEMPLATE template: \"" source "\" }"))
        plan (admission/plan-existing-gui! root (:manifest output) "reward" source)
        original (:data (production/validate-resource-text :gui full))]
    (is (= source (:sourceGui plan)))
    (is (= source (:source (coll/first-where #(= "gui" (:kind %)) (:files plan)))))
    (is (string/includes? (admission/plan-text plan) source))
    (is (= full (slurp file)))
    (let [imported (admission/apply! root plan passed)
          actual (:data (production/validate-resource-text :gui (slurp (craft-project/resource-file root (:gui imported)))))]
      (is (= (mapv #(dissoc % :texture) (:nodes original)) (mapv #(dissoc % :texture) (:nodes actual))))
      (is (= (dissoc original :nodes :textures) (dissoc actual :nodes :textures)))
      (is (= (:atlas imported) (get-in actual [:textures 0 :texture])))
      (is (string/includes? (slurp host) (:gui imported)))
      (is (= preview (slurp (craft-project/resource-file root (:gui output))))))
    (let [fresh (admission/plan-existing-gui! root (:manifest output) "second" source)]
      (spit file (str full "\nnodes { id: \"late_edit\" type: TYPE_BOX }\n"))
      (is (thrown? Exception (admission/prepare! root fresh))))
    (doseq [invalid ["/../foreign.gui" "/builtins/reward.gui" (:gui output)]]
      (is (thrown? Exception (admission/plan-existing-gui! root (:manifest output) "reward" invalid))))))
