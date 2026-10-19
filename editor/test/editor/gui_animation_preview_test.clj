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

(ns editor.gui-animation-preview-test
  (:require [clojure.test :refer [deftest is testing]]
            [dynamo.graph :as g]
            [editor.gui-animation-preview :as preview]
            [internal.system :as system]))

;; Verifies preview alpha is recomputed from local values, including a zero-alpha parent.
(deftest inherited-alpha-preview-test
  (let [source {:node-id 1
                ::preview/local-alpha 0.0
                :renderable {:user-data {:color [1.0 1.0 1.0 0.0]
                                         :inherit-alpha true}}
                :children [{:node-id 2
                            ::preview/local-alpha 0.8
                            :renderable {:user-data {:color [1.0 1.0 1.0 0.0]
                                                     :inherit-alpha true}}}
                           {:node-id 3
                            ::preview/local-alpha 0.7
                            :renderable {:user-data {:color [1.0 1.0 1.0 0.7]
                                                     :inherit-alpha false}}}]}
        result (preview/apply-preview source {1 {:alpha 0.5}})]
    (is (= 0.5 (get-in result [:renderable :user-data :color 3])))
    (is (= 0.4 (get-in result [:children 0 :renderable :user-data :color 3])))
    (is (= 0.7 (get-in result [:children 1 :renderable :user-data :color 3])))
    (is (= 0.0 (get-in source [:renderable :user-data :color 3])))
    (is (= 0.0 (get-in source [:children 0 :renderable :user-data :color 3])))))

;; Verifies color and local alpha reach GUI text geometry as well as the shape color.
(deftest text-color-preview-test
  (let [source {:node-id 1
                ::preview/local-alpha 0.0
                :renderable {:user-data {:color [1.0 1.0 1.0 0.0]
                                         :text-data {:color [1.0 1.0 1.0 0.0]
                                                     :outline [0.0 0.0 0.0 0.3]}}}}
        result (preview/apply-preview source {1 {:alpha 0.6
                                                 :color [0.1 0.2 0.3 1.0]}})]
    (is (= [0.1 0.2 0.3 0.6] (get-in result [:renderable :user-data :color])))
    (is (= [0.1 0.2 0.3 0.6] (get-in result [:renderable :user-data :text-data :color])))
    (is (= [0.0 0.0 0.0 0.3] (get-in result [:renderable :user-data :text-data :outline])))))

;; Verifies repeated outline node ids do not multiply the node's alpha a second time.
(deftest outline-alpha-preview-test
  (let [source {:node-id 1
                ::preview/local-alpha 0.2
                :renderable {:user-data {:color [1.0 1.0 1.0 0.2]}}
                :children [{:node-id 1
                            ::preview/local-alpha 1.0
                            :renderable {:user-data {:line-data []}}}
                           {:node-id 2
                            ::preview/local-alpha 0.5
                            :renderable {:user-data {:color [1.0 1.0 1.0 0.1]}}}]}
        result (preview/apply-preview source {1 {:alpha 0.8}})]
    (is (= 0.4 (get-in result [:children 1 :renderable :user-data :color 3])))
    (is (not (contains? (get-in result [:children 0 :renderable :user-data]) :color)))))

;; Verifies transforms alone leave composed colors unchanged when no alpha is animated.
(deftest transform-only-preview-test
  (let [source {:node-id 1
                ::preview/local-alpha 0.5
                :renderable {:user-data {:color [1.0 1.0 1.0 0.5]}}
                :children [{:node-id 2
                            ::preview/local-alpha 0.8
                            :renderable {:user-data {:color [1.0 1.0 1.0 0.4]}}}]}]
    (testing "No animation overrides"
      (is (= source (preview/apply-preview source {}))))
    (testing "Only a position override"
      (is (= source (preview/apply-preview source {1 {:position [4.0 5.0 0.0]}}))))))

;; Verifies precomposed template alpha retains all factors and can restore a zero-alpha ancestor.
(deftest precomposed-template-alpha-preview-test
  (let [source {:node-id 2
                ::preview/local-alpha 0.0
                ::preview/alpha-factors [[1 0.0] [2 0.5] [3 0.4]]
                :renderable {:user-data {:color [1.0 1.0 1.0 0.0]
                                         :inherit-alpha true}}}
        result (preview/apply-preview source {1 {:alpha 0.5} 2 {:alpha 0.8}})]
    (is (< (Math/abs (- 0.16 (double (get-in result [:renderable :user-data :color 3])))) 1.0e-12))
    (is (= source (preview/apply-preview source {})))))

;; Gizmos must start from the animated pose, preserve untouched axes, and leave GUI data immutable.
(deftest animated-transform-delta-test
  (let [position [-150.0 320.0 0.0]
        moved (preview/transform-value :position position (javax.vecmath.Vector3d. 20.0 -5.0 0.0))
        scaled (preview/transform-value :scale [2.0 3.0 1.0] (javax.vecmath.Vector3d. 1.5 0.5 1.0))]
    (is (= [-130.0 315.0 0.0] moved))
    (is (= [-150.0 320.0 0.0] position))
    (is (= [3.0 1.5 1.0] scaled))
    (is (= {"position_x" -130.0 "position_y" 315.0 "position_z" 0.0}
           (preview/transform-channels :position moved)))
    (is (= {"scale_x" 3.0 "scale_y" 1.5 "scale_z" 1.0}
           (preview/transform-channels :scale scaled)))))

;; Rotation gizmos use quaternion composition and emit degree-valued Panthera channels.
(deftest animated-rotation-delta-test
  (let [delta (javax.vecmath.Quat4d. 0.0 0.0 (Math/sin (/ Math/PI 4.0)) (Math/cos (/ Math/PI 4.0)))
        rotated (preview/transform-value :rotation [0.0 0.0 0.0 1.0] delta)
        channels (preview/transform-channels :rotation rotated)]
    (is (< (Math/abs (- 90.0 (double (get channels "rotation_z")))) 1.0e-6))
    (is (< (Math/abs (double (get channels "rotation_x"))) 1.0e-6))
    (is (< (Math/abs (double (get channels "rotation_y"))) 1.0e-6))))

(g/defnode PreviewDependency
  (property calls g/Any)
  (property source g/Str (default "original"))
  (output shader g/Str :cached (g/fnk [calls source] (swap! calls inc) source)))

(g/defnode PreviewScene
  (input shader g/Str)
  (property value g/Num (default 0))
  (output scene g/Any :cached (g/fnk [_node-id shader value]
                              {:node-id _node-id :shader shader :value value})))

;; Preview must reuse unaffected dependencies, invalidate changed outputs, and never pollute live state/cache.
(deftest disposable-preview-cache-test
  (binding [g/*the-system* (atom (system/make-system {:cache-size 1000}))]
    (let [calls (atom 0)
          [dependency node] (g/tx-nodes-added (g/transact [(g/make-node PreviewDependency :calls calls)
                                                        (g/make-node PreviewScene)]))]
      (g/transact (g/connect dependency :shader node :shader))
      (let [scene (g/node-value node :scene)
            basis (g/now)
            cache (g/cache)]
        (doseq [value [1 2 0 3]]
          (is (= (assoc scene :value value)
                 (preview/apply-full-preview scene {node {:graph-properties {:value value}}}))))
        (is (= 1 @calls))
        (is (identical? basis (g/now)))
        (is (identical? cache (g/cache)))
        (is (= scene (g/node-value node :scene)))
        (is (= 0 (g/node-value node :value))))
      (g/transact (g/set-property dependency :source "edited"))
      (let [scene (g/node-value node :scene)]
        (is (= "edited" (:shader (preview/apply-full-preview scene {node {:graph-properties {:value 4}}}))))
        (is (= 2 @calls))))))
