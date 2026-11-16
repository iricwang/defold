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

(ns editor.skeletal-animation-trace-view-test
  (:require [clojure.test :refer [deftest is]]
            [editor.skeletal-animation-trace-view :as trace]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [java.awt.image BufferedImage]
           [java.io File]
           [javax.imageio ImageIO]
           [javafx.scene.layout StackPane]))

;; A cancelled in-flight preview must never commit, and opening a replacement
;; preview removes the previous input-catching pane from the viewport.
(deftest isolated-preview-lifetime-test
  (let [file (File/createTempFile "trace-preview-test-" ".png")
        started (promise) release (promise) completed (promise) commits (atom [])]
    (try
      (ImageIO/write (BufferedImage. 4 4 BufferedImage/TYPE_INT_ARGB) "png" file)
      (let [host (ui/run-now (StackPane.))
            old (ui/run-now (trace/show! {:host host :image-url (str (.toURI file)) :tr identity :breadcrumb "root"
                                          :propose (fn pending-proposal [_] (deliver started true) @release)
                                          :apply-result (fn record-commit [result _] (swap! commits conj result))}))]
        (is (= true (deref started 5000 :timeout)))
        (add-watch (:state old) ::complete (fn signal-complete [_ _ before after] (when (and (:busy before) (not (:busy after))) (deliver completed true))))
        (let [replacement (ui/run-now (trace/show! {:host host :image-url (str (.toURI file)) :tr identity :breadcrumb "root"
                                                    :propose (fn invalid-proposal [_] (throw (ex-info "invalid" {})))
                                                    :apply-result (fn unexpected-commit [& _] (throw (AssertionError.)))}))]
          (is (:closed @(:state old)))
          (is (= 1 (ui/run-now (count (.getChildren host)))))
          (deliver release {:preview {:uvs [0 0 1 0 0 1] :triangles [0 1 2] :constrained-edges [[0 1] [1 2] [2 0]]} :report {"vertices" 3}})
          (is (= true (deref completed 5000 :timeout)))
          (is (nil? (:result @(:state old))))
          (is (= [] @commits))
          (ui/run-now ((:close replacement)))
          (is (zero? (ui/run-now (count (.getChildren host)))))))
      (finally (deliver release {}) (.delete file)))))

;; Persist integers, migrate old ratio values, and clamp edited count/range
;; values before they reach the asynchronous proposal worker.
(deftest integer-settings-test
  (let [settings (trace/normalize-settings {"detail" 21.1 "concavity" 59.7 "uniform" 0.31 "alpha" 8.0
                                            "bone-count" 100.0 "control-count" 1.2})]
    (is (= 21 (get settings "detail")))
    (is (= 60 (get settings "concavity")))
    (is (= 31 (get settings "uniform-percent")))
    (is (not (contains? settings "uniform")))
    (is (= 32 (get settings "bone-count")))
    (is (= 2 (get settings "control-count")))
    (is (coll/every? integer? (coll/vals settings)))
    (is (= settings (trace/normalize-settings settings)))))

;; The loading badge stays above the panel during work; stale results never
;; enable Apply. Fractional input rounds on commit and same-value edits do not
;; enqueue another expensive proposal. Errors clear loading and keep Apply off.
(deftest integer-input-and-loading-test
  (let [file (File/createTempFile "trace-controls-test-" ".png")
        started (promise) release (promise) finished (promise) calls (atom [])]
    (try
      (ImageIO/write (BufferedImage. 4 4 BufferedImage/TYPE_INT_ARGB) "png" file)
      (let [preview (ui/run-now (trace/show! {:host (StackPane.) :image-url (str (.toURI file)) :tr identity
                                              :settings {"detail" 21.1 "uniform" 0.31}
                                              :propose (fn pending-proposal [settings]
                                                         (swap! calls conj settings)
                                                         (deliver started true) @release
                                                         (throw (ex-info "test-error" {})))
                                              :apply-result (fn unexpected-commit [& _] (throw (AssertionError.)))}))
            state (:state preview) node (:node preview)]
        (is (= true (deref started 5000 :timeout)))
        (ui/run-now
          (let [field ^javafx.scene.control.TextField (.lookup node "#skeletal-trace-number-detail")
                version (:version @state)]
            (is (= "21" (.getText field)))
            (is (.isVisible (.lookup node "#skeletal-trace-loading")))
            (is (.isDisabled (.lookup node "#skeletal-trace-confirm")))
            (.setText field "21.1")
            (javafx.event.Event/fireEvent field (javafx.event.ActionEvent.))
            (is (= "21" (.getText field)))
            (is (= version (:version @state)))
            (.setText field "27.6")
            (javafx.event.Event/fireEvent field (javafx.event.ActionEvent.))
            (is (= "28" (.getText field)))
            (is (= 28 (get-in @state [:settings "detail"])))
            (.setText field "NaN")
            (javafx.event.Event/fireEvent field (javafx.event.ActionEvent.))
            (is (= "28" (.getText field)))))
        (add-watch state ::finished (fn signal-finished [_ _ before after]
                                      (when (and (:busy before) (not (:busy after)) (= 2 (count @calls))) (deliver finished true))))
        (deliver release true)
        (is (= true (deref finished 5000 :timeout)))
        (ui/run-now
          (is (= 2 (count @calls)))
          (is (= 28 (get (peek @calls) "detail")))
          (is (not (.isVisible (.lookup node "#skeletal-trace-loading"))))
          (is (.isDisabled (.lookup node "#skeletal-trace-confirm")))
          ((:close preview))))
      (finally (deliver release true) (.delete file)))))
