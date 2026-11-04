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

(ns editor.ai-toast
  "Canvas-scoped notifications. Call view callbacks on the JavaFX thread."
  (:require [clojure.string :as string]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [javafx.animation PauseTransition]
           [javafx.geometry Pos]
           [javafx.scene.control Button Label Tooltip]
           [javafx.scene.layout HBox Priority Region VBox]
           [javafx.util Duration]))

(set! *warn-on-reflection* true)

(defn make-view []
  (let [root (doto (VBox. 6.0)
               (.setId "ai-toasts") (.setMaxWidth 600.0)
               (.setMaxHeight Region/USE_PREF_SIZE) (.setPickOnBounds false))
        entries (atom {}) dismissed (atom {}) closed (atom false) serial (atom 0)
        rank {:error 0 :warning 1 :info 2}
        render! (fn render-toasts []
                  (.setVisible root (boolean (coll/not-empty @entries)))
                  (.setManaged root (boolean (coll/not-empty @entries)))
                  (.setAll (.getChildren root) ^java.util.Collection
                           (mapv :row (sort-by (juxt #(rank (:level %)) :order) (into [] (coll/vals @entries))))))
        remove! (fn remove-toast [id]
                  (when-let [^PauseTransition timer (:timer (get @entries id))] (.stop timer))
                  (swap! entries dissoc id) (render!))
        resolve! (fn resolve-toast [id]
                   (remove! id) (swap! dismissed dissoc id))
        show! (fn show-toast [notification]
                (let [{:keys [id level text resolved] :or {level :info}} (if (map? notification) notification {:text notification})
                      id (or id (if (= :info level) :info [level text]))
                      fingerprint [level text]]
                  (when-not (contains? rank level) (throw (ex-info "Unknown toast level" {:level level})))
                  (when-not @closed
                    (when resolved (resolve! id))
                    (if (string/blank? text)
                      (when (= :info level) (remove! id))
                      (when-not (or (= fingerprint (get @dismissed id))
                                    (= fingerprint (:fingerprint (get @entries id)))
                                    (< (get rank (:level (get @entries id)) 2) (rank level)))
                        (remove! id)
                        (let [row (doto (HBox. 10.0) (.setAlignment Pos/CENTER_LEFT) (.setUserData {::id id})
                                        (-> .getStyleClass (.addAll ^java.util.Collection (identity ["ai-toast" (str "ai-toast-" (name level))]))))
                              message (doto (Label. text) (.setWrapText true) (.setMinWidth 0.0) (.setMaxWidth Double/MAX_VALUE)
                                            (-> .getStyleClass (.add "ai-toast-message")))
                              icon (doto (Label. (if (= :info level) "i" "!")) (-> .getStyleClass (.add "ai-toast-icon")))
                              timer (when (= :info level) (PauseTransition. (Duration/seconds 4.0)))]
                          (HBox/setHgrow message Priority/ALWAYS)
                          (.addAll (.getChildren row) ^java.util.Collection (identity [icon message]))
                          (when (= :warning level)
                            (.add (.getChildren row)
                                  (doto (Button. "×") (.setTooltip (Tooltip. "关闭此警告")) (.setAccessibleText "关闭此警告")
                                        (-> .getStyleClass (.add "ai-toast-close"))
                                        (.setOnAction (ui/event-handler _
                                                        (swap! dismissed assoc id fingerprint)
                                                        (remove! id))))))
                          (swap! entries assoc id {:level level :text text :fingerprint fingerprint :row row :timer timer :order (swap! serial inc)})
                          (when timer
                            (ui/user-data! row ::timer timer)
                            (.setOnFinished timer (ui/event-handler _
                                                    (when (identical? row (:row (get @entries id))) (remove! id))))
                            (.play timer))
                          (render!)))))))
        dispose! (fn dispose-toasts []
                   (reset! closed true)
                   (doseq [id (into [] (coll/keys @entries))] (remove! id))
                   (reset! dismissed {}))]
    (render!)
    {:root root :show! show! :resolve! resolve! :dispose! dispose!}))
