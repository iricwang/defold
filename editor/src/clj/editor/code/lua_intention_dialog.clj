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

(ns editor.code.lua-intention-dialog
  (:require [editor.code.lua-intentions :as intentions]
            [editor.dialogs :as dialogs]
            [editor.fxui :as fxui]
            [editor.localization :as localization]))

(defn- field-dialog [{:keys [context name create-doc localization] :as props}]
  (let [valid (intentions/valid-field-name? name)
        duplicate (and valid (intentions/field-exists? context name))
        text #(localization (localization/message %))]
    {:fx/type dialogs/dialog-stage
     :showing (fxui/dialog-showing? props)
     :on-close-request {:event-type :cancel}
     :title (text "code.intentions.field")
     :size :small
     :header {:fx/type fxui/legacy-label :variant :header :text (text "code.intentions.field")}
     :content {:fx/type :v-box
               :style-class "dialog-content-padding"
               :spacing 12
               :children [{:fx/type fxui/two-col-input-grid-pane
                           :children [{:fx/type fxui/legacy-label :text (text "code.intentions.field-name")}
                                      {:fx/type fxui/legacy-text-field
                                       :id "lua-intention-field-name"
                                       :text name
                                       :variant (if (and valid (not duplicate)) :default :error)
                                       :on-text-changed {:event-type :name}}]}
                          {:fx/type fxui/check-box
                           :id "lua-intention-field-doc"
                           :text (text "code.intentions.field-doc")
                           :disable (nil? (:class-doc context))
                           :selected create-doc
                           :on-selected-changed {:event-type :doc}}
                          {:fx/type fxui/legacy-label
                           :wrap-text true
                           :text (cond
                                   (not valid) (text "code.intentions.invalid-name")
                                   duplicate (text "code.intentions.duplicate-field")
                                   (nil? (:class-doc context)) (text "code.intentions.no-class")
                                   :else (str "self." name " = " (:parameter context)))}]}
     :footer {:fx/type dialogs/dialog-buttons
              :children [{:fx/type fxui/legacy-button
                          :text (text "dialog.button.cancel") :cancel-button true :on-action {:event-type :cancel}}
                         {:fx/type fxui/legacy-button
                          :id "lua-intention-create"
                          :text (text "code.intentions.create")
                          :variant :primary :default-button true
                          :disable (or (not valid) duplicate)
                          :on-action {:event-type :confirm}}]}}))

(defn show-field-dialog! [context localization]
  (fxui/show-dialog-and-await-result!
    :initial-state {:name (:parameter context) :create-doc false}
    :event-handler (fn [state {:keys [event-type fx/event]}]
                     (case event-type
                       :name (assoc state :name event)
                       :doc (assoc state :create-doc event)
                       :cancel (assoc state ::fxui/result nil)
                       :confirm (assoc state ::fxui/result (select-keys state [:name :create-doc]))))
    :description {:fx/type field-dialog :context context :localization localization}))
