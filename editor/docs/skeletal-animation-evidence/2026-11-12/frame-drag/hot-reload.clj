(require '[cljfx.plorer :as p] '[editor.ui :as ui] '[dynamo.graph :as g])
(let [before (ui/run-now
               (let [root (p/one (ui/main-stage) "#skeletal-workspace")
                     context (ui/user-data root :editor.skeletal-animation-view/context)]
                 {:root root :context context :document (g/node-value (:resource-node context) :document)
                  :busy (or (:drag @(:state context)) (:playing @(:state context)))}))]
  (if (:busy before)
    (prn {:reloaded false :reason :active-gesture-or-playback})
    (do
      (load-file "/Users/applemima1111/Documents/Dev/Defold/editor/src/clj/editor/skeletal_animation_render.clj")
      (load-file "/Users/applemima1111/Documents/Dev/Defold/editor/src/clj/editor/skeletal_animation_slot_view.clj")
      (load-file "/Users/applemima1111/Documents/Dev/Defold/editor/src/clj/editor/skeletal_animation_view.clj")
      (ui/run-now
        (assert (identical? (:document before) (g/node-value (:resource-node (:context before)) :document))))
      (prn {:namespaces-reloaded true :new-views-use-new-input-and-refresh true
            :current-handlers-rebound false :current-compiled-timer-replaced false
            :document-preserved true :installed false
            :disabled-handlers (count @@#'editor.handler/throwing-handlers)
            :stopped-timers (count @@#'ui/stopped-timers)}))))
