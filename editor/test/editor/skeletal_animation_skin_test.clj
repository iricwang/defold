;; Copyright 2020-2026 The Defold Foundation
;; Copyright 2014-2020 King
;; Copyright 2009-2014 Ragnar Svensson, Christian Murray
;; Licensed under the Defold License version 1.0 (the "License"); you may
;; not use
;; this file except in compliance with the License.
;;
;; You may obtain a copy of the License, together with FAQs at
;; https://www.defold.com/license
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed
;; under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
;; CONDITIONS OF ANY KIND, either express or implied. See the License for the
;; specific language governing permissions and limitations under the License.

(ns editor.skeletal-animation-skin-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [editor.skeletal-animation-constraints :as constraints]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-skin :as skin]
            [util.coll :as coll]))

(def ^:private bones
  [{:id "root"}
   {:id "gated-parent" :parent "root"}
   {:id "gated-leaf" :parent "gated-parent"}
   {:id "ordinary-child" :parent "gated-parent"}
   {:id "target" :parent "root"}])

(def ^:private source
  {"bones" [{"name" "root"}
            {"name" "gated-parent" "parent" "root" "skin" true}
            {"name" "gated-leaf" "parent" "gated-parent" "skin" true}
            {"name" "ordinary-child" "parent" "gated-parent"}
            {"name" "target" "parent" "root" "skin" true}]
   "ik" [{"name" "ik" "skin" true}]
   "transform" [{"name" "transform" "skin" true}]
   "path" [{"name" "path" "skin" true}]
   "physics" [{"name" "physics" "skin" true}]
   "skins" [{"name" "default" "attachments" {}}
            {"name" "equipped" "bones" ["gated-leaf" "target"]
             "ik" ["ik"] "transform" ["transform"] "path" ["path"] "physics" ["physics"] "attachments" {}}
            {"name" "partial" "bones" ["gated-leaf"]
             "ik" ["ik"] "transform" ["transform"] "path" ["path"] "physics" ["physics"] "attachments" {}}
            {"name" "bones-only" "bones" ["target"] "attachments" {}}]})

(def ^:private document
  {:bones bones
   :slots [{:id "path-slot" :bone "target"}]
   :ik [{:id "ik" :target "target" :bones ["ordinary-child"]}]
   :transform [{:id "transform" :target "root" :bones ["ordinary-child"]}]
   :path [{:id "path" :target "path-slot" :bones ["ordinary-child"]}]
   :physics [{:id "physics" :bone "gated-leaf"}]
   :spine-source (json/write-str source)})

;; Golden active sets come from @esotericsoftware/spine-core 4.2.120 and
;; guard ancestor and target gating.
(deftest skin-activation-oracle-test
  (doseq [[name expected-bones expected-constraints]
          [["default" #{"root" "ordinary-child"} {:ik #{} :transform #{} :path #{} :physics #{}}]
           ["equipped" #{"root" "gated-parent" "gated-leaf" "ordinary-child" "target"}
            {:ik #{"ik"} :transform #{"transform"} :path #{"path"} :physics #{"physics"}}]
           ["partial" #{"root" "gated-parent" "gated-leaf" "ordinary-child"}
            {:ik #{} :transform #{"transform"} :path #{} :physics #{"physics"}}]
           ["bones-only" #{"root" "ordinary-child" "target"} {:ik #{} :transform #{} :path #{} :physics #{}}]]]
    (testing name
      (is (= {:bone-ids expected-bones :constraints expected-constraints}
             (skin/activation (assoc document :active-skin name)))))))

;; Ordinary documents keep all bones/constraints active, while target gating
;; still applies to unrestricted constraints.
(deftest unrestricted-and-unselected-skin-test
  (is (= {:bone-ids (coll/into-> bones #{} (map :id))
          :constraints {:ik #{"ik"} :transform #{"transform"} :path #{"path"} :physics #{"physics"}}}
         (skin/activation (dissoc document :spine-source))))
  (let [document (-> document
                     (skin/set-required :ik "ik" false)
                     (skin/set-required :transform "transform" false)
                     (skin/set-required :path "path" false)
                     (skin/set-required :physics "physics" false))]
    (is (= {:ik #{} :transform #{"transform"} :path #{} :physics #{}}
           (:constraints (skin/activation document))))))

;; Editing required and membership flags survives JSON persistence and
;; preserves other skins and attachments.
(deftest skin-membership-edit-test
  (let [document (assoc document :active-skin "equipped")
        updated (-> document
                    (skin/set-required :bone "target" false)
                    (skin/set-included :bone "target" false)
                    (skin/set-required :transform "transform" false)
                    (skin/set-included :physics "physics" false))
        before (json/read-str (:spine-source document))
        after (json/read-str (:spine-source updated))]
    (is (skin/required? document :bone "target"))
    (is (not (skin/required? updated :bone "target")))
    (is (not (skin/included? updated :bone "target")))
    (is (not (skin/included? updated :physics "physics")))
    (is (not (skin/required? updated :transform "transform")))
    (is (= (get-in before ["skins" 0]) (get-in after ["skins" 0])))
    (is (= (get-in before ["skins" 2]) (get-in after ["skins" 2])))
    (is (contains? (:bone-ids (skin/activation updated)) "target"))
    (is (= #{} (get-in (skin/activation updated) [:constraints :physics])))
    (is (= (skin/set-included updated :bone "target" true)
           (-> updated (skin/set-included :bone "target" true) (skin/set-included :bone "target" true))))))

;; A newly named skin can acquire membership without cloning the default
;; skin's bone or constraint memberships.
(deftest new-skin-membership-test
  (let [updated (skin/set-included (assoc document :active-skin "new-skin") :bone "gated-leaf" true)]
    (is (skin/included? updated :bone "gated-leaf"))
    (is (= #{"root" "gated-parent" "gated-leaf" "ordinary-child"}
           (:bone-ids (skin/activation updated))))))

;; Invalid item IDs, non-boolean flags and incomplete source materialization
;; are rejected before mutation.
(deftest skin-edit-validation-test
  (doseq [change [#(skin/set-required document :bone "missing" true)
                  #(skin/set-included document :ik "ik" nil)
                  #(skin/set-required (dissoc document :spine-source) :bone "root" true)]]
    (is (thrown? clojure.lang.ExceptionInfo (change)))))

;; Defold spine-c starts inactive worlds at identity; constraints must not
;; update them
;; indirectly through a changed ancestor, even when an ordinary child is active.
(deftest inactive-bone-world-oracle-test
  (let [bones [{:id "root"}
               {:id "moving" :parent "root" :x 10.0}
               {:id "gated" :parent "moving" :x 20.0 :active false}
               {:id "child" :parent "gated" :x 3.0}
               {:id "target" :parent "root" :x 100.0}]
        transform {:id "move" :bones ["moving"] :target "target"
                   :mix-rotate 0.0 :mix-x 1.0 :mix-y 1.0
                   :mix-scale-x 0.0 :mix-scale-y 0.0 :mix-shear-y 0.0}
        inactive (constraints/evaluate bones [transform])
        active (constraints/evaluate (mapv #(dissoc % :active) bones) [transform])
        physical (constraints/apply-world-transform (constraints/pose bones) "moving" [1.0 0.0 0.0 1.0 100.0 0.0])]
    (doseq [pose [inactive physical]]
      (is (= [1.0 0.0 0.0 1.0 0.0 0.0] (get-in pose [:transforms "gated"])))
      (is (= 3.0 (get-in pose [:transforms "child" 4]))))
    (is (= 100.0 (get-in inactive [:transforms "moving" 4])))
    (is (= 120.0 (get-in active [:transforms "gated" 4])))
    (is (= 123.0 (get-in active [:transforms "child" 4])))))

;; Import, animation sampling and skin switching agree on inactive bones;
;; editing skin metadata must not silently lose it in the existing export path.
(deftest skin-document-pose-and-export-test
  (let [source {"skeleton" {"spine" "4.2.43"}
                "bones" [{"name" "root"}
                         {"name" "gated" "parent" "root" "x" 20 "skin" true}
                         {"name" "child" "parent" "gated" "x" 3}]
                "skins" [{"name" "default" "attachments" {}}
                         {"name" "enabled" "bones" ["gated"] "attachments" {}}]
                "animations" {"idle" {"bones" {"gated" {"translate" [{"time" 0 "x" 0}
                                                                     {"time" 1 "x" 100}]}}}}}
        document (data/import-spine-json (json/write-str source))
        inactive (data/evaluate-pose document "idle" 0.5)
        active-document (data/select-skin document "enabled")
        active (data/evaluate-pose active-document "idle" 0.5)
        edited (-> active-document
                   (skin/set-required :bone "child" true)
                   (skin/set-included :bone "child" true))
        reopened (data/import-spine-json (data/export-spine-json edited))
        reopened (data/select-skin reopened "enabled")]
    (is (= #{"root" "child"} (:active-bone-ids inactive)))
    (is (== 20.0 (get-in inactive [:bones 1 :x])))
    (is (= 3.0 (get-in inactive [:transforms "child" 4])))
    (is (= 73.0 (get-in active [:transforms "child" 4])))
    (is (skin/required? reopened :bone "child"))
    (is (skin/included? reopened :bone "child"))
    (is (= #{"root" "gated" "child"}
           (:active-bone-ids (data/evaluate-pose reopened "idle" 0.5))))))
