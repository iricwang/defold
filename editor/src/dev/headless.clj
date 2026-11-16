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

(ns headless
  "Virtual-window interaction driver. All input travels through plorer."
  (:require [cljfx.plorer :as p]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [editor.gl :as gl]
            [editor.handler :as handler]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [com.sun.glass.events WindowEvent]
           [com.sun.javafx.stage WindowHelper]
           [com.sun.javafx.tk.quantum WindowStage]
           [java.util.concurrent CountDownLatch TimeUnit]
           [javafx.animation AnimationTimer]
           [javafx.application Platform]
           [javafx.stage Screen Window]))

(set! *warn-on-reflection* true)

(defn ready? []
  (ui/run-now
    (boolean (when-let [stage (ui/main-stage)]
               (and (.isShowing stage) (.lookup (.getRoot (.getScene stage)) "#workbench"))))))

(defn startup-status [allow-no-gl]
  (if (ready?)
    "ready"
    (ui/run-now
      (let [matches (p/all {:fx.plorer/class javafx.scene.control.Button :text "Disable Scene Editor"})]
        (if-not (= 1 (count matches))
          "loading"
          (if-not allow-no-gl
            "opengl-unavailable"
            (let [button (first matches)]
              (p/mouse-click! (.getWindow (.getScene ^javafx.scene.Node button)) (p/point button 0.5 0.5) :primary)
              "loading")))))))

(defn- pulse! []
  (when (Platform/isFxApplicationThread)
    (throw (ex-info "Run headless scenarios from the REPL thread, not the FX thread." {})))
  (let [done (CountDownLatch. 1)
        ticks (atom 0)
        timer (proxy [AnimationTimer] []
                (handle [_]
                  (when (= 2 (swap! ticks inc))
                    (.stop ^AnimationTimer this)
                    (.countDown done))))]
    (ui/run-now (.start timer) (Platform/requestNextPulse))
    (when-not (.await done 10 TimeUnit/SECONDS)
      (ui/run-now (.stop timer))
      (throw (ex-info "No JavaFX pulse within 10 seconds." {})))))

(defn- window [index]
  (ui/run-now
    (if (nil? index)
      (ui/main-stage)
      (let [windows (filterv #(.isShowing ^Window %) (Window/getWindows))]
        (or (get windows index)
            (throw (ex-info "Window does not exist." {:index index :count (count windows)})))))))

(defn- target [root selector]
  (let [matches (apply p/all root (if (vector? selector) selector [selector]))]
    (when-not (= 1 (count matches))
      (throw (ex-info "Selector must identify exactly one element." {:selector selector :matches (count matches)})))
    (first matches)))

(defn- point [root {:keys [selector at]}]
  (if selector
    (let [[x y] (or at [0.5 0.5])]
      (p/point (target root selector) x y))
    (or at (throw (ex-info "Input requires :selector or :at." {})))))

(defn- value-at [root {:keys [selector property context-key state-path]}]
  (let [node (target root selector)]
    (if-not context-key
      (let [values (p/props node :only [property])]
        (when-not (contains? values property)
          (throw (ex-info "Unknown UI property." {:selector selector :property property})))
        (get values property))
      (ui/run-now
        (reduce (fn read-state [value key]
                  (get (if (instance? clojure.lang.IDeref value) @value value) key))
                (ui/user-data node context-key) state-path)))))

(defn- assert-property! [root {:keys [selector property equals differs-from] :as step} remembered]
  (let [actual (value-at root step)
        passed (if differs-from
                 (and (contains? @remembered differs-from) (not= (get @remembered differs-from) actual))
                 (= equals actual))]
    (when-not passed
      (throw (ex-info "UI assertion failed."
                      {:selector selector :property property :expected equals :actual actual :differs-from differs-from})))))

(defn- virtual-focus! [^Window window]
  (when (= "background" (System/getProperty "defold.qa.backend"))
    (ui/run-now
      (let [peer (WindowHelper/getPeer window)]
        (when (instance? WindowStage peer)
          (let [method (.getDeclaredMethod com.sun.glass.ui.Window "notifyFocus" (into-array Class [Integer/TYPE]))]
            (.setAccessible method true)
            (.invoke method (.getPlatformWindow ^WindowStage peer) (object-array [WindowEvent/FOCUS_GAINED]))))))))

(defn- perform! [{:keys [op button keys path delta window-index] :as step} remembered]
  (let [root (window window-index)
        button (or button :primary)]
    (virtual-focus! root)
    (case op
      :click (let [selector (:selector step)
                   expected (when selector (target root selector))
                   hit (p/mouse-click! root (point root step) button)]
               ;; Compare the original control when a click rebuilds the inspector.
               ;; not a replacement node resolved after the action completed.
               (when selector
                 (loop [node hit]
                   (when-not (= node expected)
                     (if (instance? javafx.scene.Node node)
                       (recur (.getParent ^javafx.scene.Node node))
                       (throw (ex-info "Input did not reach the selected control (clipped or covered)." {:selector selector})))))))
      :move (p/mouse-move! root (point root step))
      :keys (p/key-chord! root keys)
      :scroll (let [[dx dy] delta] (p/scroll! root (point root step) dx dy))
      :drag (let [points (mapv #(point root %) path)]
              (when (< (count points) 2)
                (throw (ex-info "Drag requires at least two points." {})))
              (doseq [key keys] (p/key-press! root key))
              (try
                (p/mouse-press! root (first points) button)
                (try
                  (doseq [position (subvec points 1)]
                    (p/mouse-move! root position)
                    (pulse!))
                  (finally (p/mouse-release! root (peek points) button)))
                (finally (doseq [key (rseq (vec keys))] (p/key-release! root key)))))
      :wait (let [deadline (+ (System/nanoTime) (* 1000000 (long (:timeout-ms step 10000))))]
              (loop []
                (let [failure (try (assert-property! (window window-index) step remembered) nil
                                   (catch Exception error error))]
                  (when failure
                    (if (< (System/nanoTime) deadline)
                      (do (Thread/sleep 50) (recur))
                      (throw failure))))))
      :remember (swap! remembered assoc (:as step) (value-at root step))
      :assert (assert-property! root step remembered)
      :snapshot nil
      (throw (ex-info "Unknown interaction operation." {:op op})))))

(defn- snapshots! [directory index]
  (let [windows (ui/run-now (filterv #(.isShowing ^Window %) (Window/getWindows)))]
    (mapv (fn capture-window [number window]
            (let [source (io/file (p/screenshot! window))
                  destination (io/file directory (format "%03d-window-%d.png" index number))]
              (io/copy source destination)
              (.delete source)
              (.getAbsolutePath destination)))
          (range (count windows)) windows)))

(defn run-scenario! [scenario-path output-directory]
  (when-not (= "true" (System/getProperty "defold.qa.session"))
    (throw (ex-info "Refusing to run scenarios outside an isolated QA process." {})))
  (let [scenario (if scenario-path (edn/read-string (slurp scenario-path)) {:steps []})
        directory (io/file output-directory)
        steps (:steps scenario)
        results (atom [])
        remembered (atom {})
        started (System/currentTimeMillis)]
    (when-not (vector? steps)
      (throw (ex-info "Scenario must contain a vector of :steps." {})))
    (.mkdirs directory)
    (try
      (let [[width height] (:viewport scenario (if (= "headless" (System/getProperty "glass.platform")) [960 900] [1920 1080]))]
        (when (= "headless" (System/getProperty "glass.platform"))
          (let [bounds (ui/run-now (.getBounds (Screen/getPrimary)))]
            (when (or (> width (- (.getWidth bounds) 40.0)) (> height (- (.getHeight bounds) 60.0)))
              (throw (ex-info "Viewport exceeds the JavaFX headless framebuffer. Use background rendering for large screenshots."
                              {:requested [width height] :screen [(.getWidth bounds) (.getHeight bounds)]})))))
        (ui/run-now
          (.setX (ui/main-stage) 0.0)
          (.setY (ui/main-stage) 0.0)
          (doseq [^javafx.scene.control.MenuBar bar (p/all (ui/main-stage) javafx.scene.control.MenuBar)]
            (.setUseSystemMenuBar bar false))
          (.setWidth (ui/main-stage) (double width))
          (.setHeight (ui/main-stage) (double height))))
      (pulse!)
      (snapshots! directory 0)
      (doseq [[index step] (coll/into-> steps [] (map-indexed vector))]
        (try
          (perform! step remembered)
          (pulse!)
          (swap! results conj {:step (inc index) :op (:op step) :status :passed
                               :screenshots (snapshots! directory (inc index))})
          (catch Throwable error
            (swap! results conj {:step (inc index) :op (:op step) :status :failed
                                 :message (.getMessage error) :data (ex-data error)
                                 :screenshots (snapshots! directory (inc index))})
            (throw error))))
      (catch Throwable error
        (when (coll/every? #(= :passed (:status %)) @results)
          (swap! results conj {:status :failed :message (.getMessage error)}))))
    (let [diagnostics {:stopped-timers (count @@#'ui/stopped-timers)
                       :disabled-handlers (count @@#'handler/throwing-handlers)}
          _ (when-not (coll/every? zero? (coll/vals diagnostics))
              (swap! results conj {:status :failed :message "An editor handler or animation timer was disabled."}))
          report {:diagnostics diagnostics :status (if (coll/every? #(= :passed (:status %)) @results) :passed :failed)
                  :backend (System/getProperty "defold.qa.backend")
                  :platform (System/getProperty "glass.platform" "native")
                  :renderer (str (class (com.sun.prism.GraphicsPipeline/getPipeline)))
                  :capabilities {:scene3d (Platform/isSupported javafx.application.ConditionalFeature/SCENE3D)
                                 :opengl-error (gl/gl-support-error)
                                 :native-os-dialogs :not-verified
                                 :desktop-pixel-parity :not-verified}
                  :viewport (:viewport scenario (if (= "headless" (System/getProperty "glass.platform")) [960 900] [1920 1080]))
                  :elapsed-ms (- (System/currentTimeMillis) started)
                  :steps @results}]
      (spit (io/file directory "report.json") (json/write-str report))
      report)))
