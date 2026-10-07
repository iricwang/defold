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

(ns editor.ui.patch
  (:require [cljfx.api :as fx]
            [clojure.java.io :as io]
            [editor.dialogs :as dialogs]
            [editor.fxui :as fxui]
            [editor.localization :as localization]
            [editor.patch :as patch]
            [editor.ui :as ui])
  (:import [com.defold.editor Editor]
           [com.dynamo.bob Platform]))

(set! *warn-on-reflection* true)

(ui/defc patch-dialog
  {:compose [{:fx/type fx/ext-watcher
              :ref (:localization props)
              :key :localization-state}]}
  [{:keys [localization-state config status manifest downloaded progress error
           check-fn download-fn full-installer-fn restart-fn installer kind owner]
    :as props}]
  (let [text #(localization-state (localization/message %))
        busy (contains? #{:checking :downloading :preparing} status)]
    {:fx/type dialogs/dialog-stage
     :showing (fxui/dialog-showing? props)
     :owner owner
     :title (text "patch.title")
     :on-close-request {:event-type :close}
     :size :large
     :width 680
     :header {:fx/type fxui/legacy-label
              :variant :header
              :text (text "patch.title")}
     :content {:fx/type :v-box
               :spacing 12
               :style-class "dialog-content-padding"
               :children [{:fx/type :label
                           :wrap-text true
                           :text (localization-state
                                   (localization/message "patch.current"
                                                         {"version" (:version config "—")
                                                          "channel" (:channel config "—")}))}
                          {:fx/type :label
                           :wrap-text true
                           :text (localization-state
                                   (localization/message "patch.latest"
                                                         {"version" (:version manifest "—")}))}
                          {:fx/type :label
                           :wrap-text true
                           :text (text (case status
                                         :checking "patch.checking"
                                         :available (if (= :patch kind) "patch.available" "patch.installer-required")
                                         :current "patch.current-status"
                                         :unsupported "patch.unsupported"
                                         :downloading "patch.downloading"
                                         :preparing "patch.preparing"
                                         :downloaded "patch.downloaded"
                                         "patch.failed"))}
                          {:fx/type :progress-bar
                           :visible busy
                           :managed busy
                           :max-width Double/MAX_VALUE
                           :progress (if (= :downloading status) (or progress 0.0) -1.0)}
                          {:fx/type :text-area
                           :editable false
                           :wrap-text true
                           :pref-row-count 10
                           :text (or error (:notes manifest) (:notes config) "")}
                          {:fx/type :label
                           :wrap-text true
                           :text (text "patch.install-hint")}]}
     :footer {:fx/type dialogs/dialog-buttons
              :children [{:fx/type fxui/legacy-button
                          :text (text "patch.close")
                          :cancel-button true
                          :on-action {:event-type :close}}
                         {:fx/type fxui/legacy-button
                          :text (text "patch.check")
                          :disable busy
                          :on-action (fn [_] (check-fn))}
                         {:fx/type fxui/legacy-button
                          :text (text "patch.full-installer")
                          :visible (boolean (and (= :patch kind) installer))
                          :managed (boolean (and (= :patch kind) installer))
                          :disable busy
                          :on-action (fn [_] (full-installer-fn))}
                         {:fx/type fxui/legacy-button
                          :text (text (if downloaded
                                        (if (= :patch kind) "patch.restart" "patch.open")
                                        "patch.download"))
                          :variant :primary
                          :disable (not (contains? #{:available :downloaded} status))
                          :on-action (fn [_]
                                       (if downloaded
                                         (if (= :patch kind)
                                           (restart-fn)
                                           (ui/open-file downloaded))
                                         (download-fn)))}]}}))

(defn show! [owner localization before-restart-fn]
  (let [state (atom {:status :checking})
        check-fn (fn []
                   (when-let [prepared (:prepared @state)]
                     (future (patch/discard! prepared)))
                   (swap! state dissoc :manifest :asset :installer :downloaded :prepared :error)
                   (swap! state assoc :status :checking)
                   (future
                     (try
                       (let [config (patch/configuration)]
                         (swap! state assoc :config config)
                         (swap! state merge (patch/check! config (.getPair (Platform/getHostPlatform)))))
                       (catch Exception e
                         (swap! state assoc :status :failed :error (.getMessage e))))))
        download-fn (fn []
                      (let [{:keys [asset kind config manifest]} @state]
                        (swap! state assoc :status :downloading :progress 0.0)
                        (future
                          (try
                            (let [file (patch/download!
                                         asset
                                         (io/file (.toFile (Editor/getSupportPath)) "patches")
                                         (fn [received]
                                           (swap! state assoc :progress (/ (double received) (:size asset)))))
                                  prepared (when (= :patch kind)
                                             (swap! state assoc :status :preparing)
                                             (patch/prepare! file config manifest))
                                  file (if (= :installer kind)
                                         (let [installer (io/file (str file ".dmg"))]
                                           (io/copy file installer)
                                           installer)
                                         file)
                                  [_ new-state] (swap-vals! state
                                                            (fn [state]
                                                              (if (contains? state ::fxui/result)
                                                                state
                                                                (assoc state :status :downloaded :downloaded file :prepared prepared))))]
                              (when (contains? new-state ::fxui/result)
                                (patch/discard! prepared)))
                            (catch Exception e
                              (swap! state assoc :status :failed :error (.getMessage e)))))))
        restart-fn (fn []
                     (before-restart-fn
                       (fn []
                         (try
                           (patch/restart! (:prepared @state))
                           (catch Exception e
                             (ui/enable-ui!)
                             (swap! state assoc :status :failed :error (.getMessage e)))))))]
    (check-fn)
    (fxui/show-dialog-and-await-result!
      :state-atom state
      :event-handler (fn [state _]
                       (when-let [prepared (:prepared state)]
                         (future (patch/discard! prepared)))
                       (assoc state ::fxui/result nil))
      :description {:fx/type patch-dialog
                    :localization localization
                    :owner owner
                    :check-fn check-fn
                    :download-fn download-fn
                    :full-installer-fn (fn []
                                         (when-let [prepared (:prepared @state)]
                                           (future (patch/discard! prepared)))
                                         (swap! state (fn [state]
                                                        (-> state
                                                            (dissoc :prepared :downloaded :error)
                                                            (assoc :asset (:installer state) :kind :installer))))
                                         (download-fn))
                    :restart-fn restart-fn})))
