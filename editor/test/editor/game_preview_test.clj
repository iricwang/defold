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

(ns editor.game-preview-test
  (:require [cljfx.api :as fx]
            [clojure.test :refer [deftest is]]
            [editor.game-preview :as preview]
            [editor.gl.pass :as pass]
            [editor.scene :as scene])
  (:import [com.defold.editor GamePreview]
           [java.nio.file Files Path]
           [java.util.function BiConsumer]
           [javafx.scene.control ComboBox]
           [javafx.event Event EventHandler]
           [javafx.scene.input KeyCode KeyEvent]
           [javafx.scene.image ImageView WritableImage]
           [javax.vecmath Point3d Quat4d Vector3d]))

;; Letterboxing maps the displayed game center and edges to the original input coordinates.
(deftest letterboxed-input-coordinates
  (is (= [320.0 240.0] (vec (GamePreview/gamePoint 1000 600 640 480 500 300))))
  (is (= [0.0 0.0] (vec (GamePreview/gamePoint 1000 600 640 480 0 0))))
  (is (= [639.0 479.0] (vec (GamePreview/gamePoint 1000 600 640 480 1000 600))))
  (is (= [0.0 0.0] (vec (GamePreview/gamePoint 0 0 640 480 0 0)))))

;; JavaFX navigation key codes overlap ASCII punctuation; special keys must win over ASCII.
(deftest keyboard-protocol-mapping
  (is (= 65 (GamePreview/keyCode KeyCode/A)))
  (is (= 32 (GamePreview/keyCode KeyCode/SPACE)))
  (is (= 140 (GamePreview/keyCode KeyCode/HOME)))
  (is (= 141 (GamePreview/keyCode KeyCode/END)))
  (is (= 129 (GamePreview/keyCode KeyCode/UP)))
  (is (= 155 (GamePreview/keyCode KeyCode/F12)))
  (is (= -1 (GamePreview/keyCode KeyCode/UNDEFINED))))

;; Selected FPS belongs to each run; Windows uses a native child and macOS cleans its shared file.
(deftest preview-session-frame-rate-and-cleanup
  @(fx/on-fx-thread
     (with-open [view (GamePreview. "frame-rate-test" (fn []) (fn [])
                                    (reify BiConsumer (accept [_ _ _])))]
       (let [^ComboBox selector (.lookup (.getNode view) "#game-frame-rate")
             first-session (GamePreview/prepare "frame-rate-test" true)
             first-path (when-let [path (.getPath first-session)]
                          (Path/of path (make-array String 0)))]
         (is (= 120 (.getFrameRate first-session)))
         (.setValue selector (int 144))
         (is (= 120 (.getFrameRate first-session)))
         (with-open [next-session (GamePreview/prepare "frame-rate-test" true)]
           (is (= 144 (.getFrameRate next-session)))
           (if first-path
             (is (not (Files/exists first-path (make-array java.nio.file.LinkOption 0))))
             (is (nil? (.getPath next-session))))
           (.stop view)
           (when-let [path (.getPath next-session)]
             (is (not (Files/exists (Path/of path (make-array String 0))
                                   (make-array java.nio.file.LinkOption 0))))))))))

;; Edit preview keeps the project aspect ratio and bounds GPU readback at large window sizes.
(deftest edit-preview-resolution
  (is (= [270 585] (preview/render-size 300 585 (/ 540.0 1170))))
  (is (= [1536 864] (preview/render-size 3840 2160 (/ 16.0 9))))
  (is (= [768 1536] (preview/render-size 2000 4000 0.5)))
  (is (= [1 1] (preview/render-size 0 0 1.0))))

;; GUI framing uses layout bounds rather than framing all authored nodes, including offscreen nodes.
(deftest gui-and-default-game-projection
  (doseq [gui-dims [[540 1170] nil]]
    (let [{:keys [camera mode aspect]} (scene/game-preview-camera {:renderables {}} gui-dims [960 640])
          [w h] (or gui-dims [960 640])]
      (is (= :orthographic (:type camera)))
      (is (= (Point3d. (/ w 2.0) (/ h 2.0) 0.0) (:position camera)))
      (is (== w (:fov-x camera)))
      (is (== h (:fov-y camera)))
      (is (= (/ (double w) h) aspect))
      (is (= (if gui-dims "game-preview.edit-gui" "game-preview.edit-default") mode)))))

;; Camera selection, world transforms and fixed zoom must survive the clean Game render pass.
(deftest scene-game-camera-projection
  (let [a {:tags #{:camera :gizmo} :world-translation (Vector3d. 20.0 30.0 40.0)
           :world-rotation (Quat4d. 0.0 0.0 0.0 1.0)
           :user-data {:is-orthographic true :orthographic-mode :ortho-mode-fixed
                       :orthographic-zoom 2.0 :display-width 960 :display-height 640
                       :near-z 1.0 :far-z 1000.0}}
        b (assoc a :selected true :world-translation (Vector3d. 50.0 60.0 70.0))
        data {:renderables {pass/selection [a b]}}
        result (scene/game-preview-camera data nil [960 640])]
    (is (= "game-preview.edit-camera" (:mode result)))
    (is (= (Point3d. 50.0 60.0 70.0) (get-in result [:camera :position])))
    (is (= 480.0 (get-in result [:camera :fov-x])))
    (is (= 320.0 (get-in result [:camera :fov-y])))
    (is (= 1.0 (get-in result [:camera :z-near])))
    (is (= 1000.0 (get-in result [:camera :z-far])))
    (is (= "game-preview.edit-gui" (:mode (scene/game-preview-camera data [960 640] [960 640]))))
    (is (nil? (scene/game-preview-camera
                {:renderables {pass/selection [(assoc-in a [:user-data :orthographic-zoom] 0)]}}
                nil [960 640])))))

;; Starting clears the edit image; stopping restores it with the correct OpenGL vertical orientation.
(deftest runtime-edit-preview-handoff
  (let [view @(fx/on-fx-thread (GamePreview. "idle-handoff-test" (fn []) (fn [])
                                (reify BiConsumer (accept [_ _ _]))))
        idle (WritableImage. 16 8)]
    (try
      @(fx/on-fx-thread
         (.setIdleImage view idle "game-preview.edit-gui")
         (GamePreview/prepare "idle-handoff-test" true))
      @(fx/on-fx-thread
         (let [image ^ImageView (.lookup (.getNode view) "ImageView")]
           (is (nil? (.getImage image)))
           (is (= 1.0 (.getScaleY image))))
         (.stop view))
      @(fx/on-fx-thread
         (let [image ^ImageView (.lookup (.getNode view) "ImageView")]
           (is (identical? idle (.getImage image)))
           (is (= -1.0 (.getScaleY image)))))
      (finally @(fx/on-fx-thread (.close view))))))

;; After playing, focus can remain in Game; idle preview must not swallow editor shortcuts.
(deftest idle-preview-keyboard-bubbling
  @(fx/on-fx-thread
     (with-open [view (GamePreview. "idle-keyboard-test" (fn []) (fn [])
                                    (reify BiConsumer (accept [_ _ _])))]
       (let [root (.getNode view)
             viewport (.lookup root "#game-viewport")
             events (atom 0)
             key-event #(KeyEvent. KeyEvent/KEY_PRESSED "" "" KeyCode/A false true false false)]
         (.addEventHandler root KeyEvent/KEY_PRESSED
                           (reify EventHandler (handle [_ _] (swap! events inc))))
         (Event/fireEvent viewport (key-event))
         (is (= 1 @events))
         (GamePreview/prepare "idle-keyboard-test" true)
         (Event/fireEvent viewport (key-event))
         (is (= 1 @events))
         (.stop view)
         (Event/fireEvent viewport (key-event))
         (is (= 2 @events))))))
