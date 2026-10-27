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
            [dynamo.graph :as g]
            [editor.game-preview :as preview]
            [editor.gl.pass :as pass]
            [editor.resource :as resource]
            [editor.scene :as scene]
            [editor.ui :as ui]
            [support.test-support :refer [with-clean-system]]
            [util.coll :as coll])
  (:import [com.defold.editor GamePreview SimulatorDevice]
           [java.nio.file Files Path]
           [java.util.function BiConsumer]
           [javafx.scene.control Button CheckBox ComboBox ToggleButton]
           [javafx.event Event EventHandler]
           [javafx.scene.input KeyCode KeyEvent]
           [javafx.scene.image ImageView WritableImage]
           [javax.vecmath Point3d Quat4d Vector3d]))

;; Repeated refreshes before a resource opens must never look up a nil graph node or disable preview.
(deftest empty-resource-preview-refresh
  (with-clean-system
    @(fx/on-fx-thread
       (let [messages (atom [])]
         (with-open [pane (GamePreview. "empty-resource-test" (fn []) (fn [])
                                       (reify BiConsumer (accept [_ _ key] (swap! messages conj key))))]
           (preview/attach! pane nil nil)
           (let [field (.getDeclaredField GamePreview "idleRefresh")
                 _ (.setAccessible field true)
                 refresh ^Runnable (.get field pane)]
             (with-redefs [g/node-value (fn [_ _ _] nil)
                           ui/ui-disabled? (constantly false)]
               (dotimes [_ 3] (.run refresh)))
             (is (coll/not-any? #{"game-preview.edit-unavailable"} @messages))
             (is (coll/some #{"game-preview.edit-empty"} @messages))))))))

;; Letterboxing maps the displayed game center and edges to the original input coordinates.
(deftest letterboxed-input-coordinates
  (is (= [320.0 240.0] (vec (GamePreview/gamePoint 1000 600 640 480 500 300))))
  (is (= [0.0 0.0] (vec (GamePreview/gamePoint 1000 600 640 480 0 0))))
  (is (= [639.0 479.0] (vec (GamePreview/gamePoint 1000 600 640 480 1000 600))))
  (is (= [0.0 0.0] (vec (GamePreview/gamePoint 0 0 640 480 0 0)))))

;; Custom screen geometry rotates in all four directions without losing asymmetric insets.
(deftest simulator-device-orientations
  (let [device (SimulatorDevice. "Test" 100 200 20 3 10 7 8 20 10 5)]
    (is (= [[20 3 10 7] [7 20 3 10] [10 7 20 3] [3 10 7 20]]
           (mapv #(vec (.insets device %)) (range 4))))
    (is (= [100 200 100 200] (mapv #(.pixelWidth device %) (range 4))))
    (is (= [1 2 3 0] (mapv #(.nextRotation device %) (range 4))))
    (is (thrown? IllegalArgumentException (SimulatorDevice. "Invalid" 100 200 100 0 100 0 0 0 0 0)))))

;; Imported landscape safe areas are independent; multiple screens and cutouts retain their coordinates.
(deftest simulator-device-import
  (let [path (Files/createTempFile "simulator-test-" ".device" (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (spit (.toFile path) "{\"version\":1,\"friendlyName\":\"Synthetic\",\"screens\":[{\"width\":100,\"height\":200,\"dpi\":300,\"orientations\":[{\"orientation\":1,\"safeArea\":{\"x\":0,\"y\":10,\"width\":100,\"height\":170}},{\"orientation\":3,\"safeArea\":{\"x\":20,\"y\":6,\"width\":160,\"height\":94},\"cutouts\":[{\"x\":0,\"y\":40,\"width\":10,\"height\":20}]}]},{\"width\":80,\"height\":120,\"dpi\":150}]}")
      (let [devices (SimulatorDevice/read path)
            ^SimulatorDevice device (first devices)]
        (is (= 2 (count devices)))
        (is (= 300.0 (.dpi device)))
        (is (= [20 0 10 0] (vec (.insets device 0))))
        (is (= [0 20 6 20] (vec (.insets device 3))))
        (is (= 3 (.nextRotation device 0)))
        (is (= 0 (.nextRotation device 3)))
        (is (= 1 (count (.cutouts device 3))))
        (is (= [0 0 0 0] (vec (.insets ^SimulatorDevice (second devices) 2)))))
      (spit (.toFile path) "{\"version\":2,\"friendlyName\":\"Invalid\",\"screens\":[]}")
      (is (thrown? java.io.IOException (SimulatorDevice/read path)))
      (finally (Files/deleteIfExists path)))))

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

;; Device simulation must remain available on macOS, persist settings and avoid loading Win32 APIs.
(deftest simulator-cross-platform-session-and-settings
  (let [directory (Files/createTempDirectory "device-session" (make-array java.nio.file.attribute.FileAttribute 0))
        path (str directory)
        make-pane #(GamePreview. path (fn []) (fn []) (reify BiConsumer (accept [_ _ _])))]
    (try
      @(fx/on-fx-thread
         (with-open [pane (make-pane)]
           (let [root (.getNode pane)
                 enabled ^CheckBox (.lookup root "#game-simulator-enabled")
                 rotate ^Button (.lookup root "#game-simulator-rotate")]
             (is (not (.isDisabled enabled)))
             (.fire enabled)
             (let [[width height] (vec (.getPreviewDeviceSize pane))]
               (is (< 2048 height))
               (.fire rotate)
               (is (= [height width] (vec (.getPreviewDeviceSize pane))))))
           (with-open [session (GamePreview/prepare path true)]
             (is (.isSimulator session))
             (is (= (vec (.getPreviewDeviceSize pane))
                    [(.getDeviceWidth session) (.getDeviceHeight session)]))
             (is (Files/exists (Path/of (.getPath session) (make-array String 0))
                               (make-array java.nio.file.LinkOption 0)))))
         (with-open [pane (make-pane)]
           (is (.isSelected ^CheckBox (.lookup (.getNode pane) "#game-simulator-enabled")))
           (let [[width height] (vec (.getPreviewDeviceSize pane))]
             (is (> width height)))))
      (finally
        (Files/deleteIfExists (.resolve directory ".internal/game-simulator.properties"))
        (Files/deleteIfExists (.resolve directory ".internal"))
        (Files/deleteIfExists directory)))))

;; Edit preview keeps the project aspect ratio and bounds GPU readback at large window sizes.
(deftest edit-preview-resolution
  (is (= [270 585] (preview/render-size 300 585 (/ 540.0 1170))))
  (is (= [1536 864] (preview/render-size 3840 2160 (/ 16.0 9))))
  (is (= [768 1536] (preview/render-size 2000 4000 0.5)))
  (is (= [1 1] (preview/render-size 0 0 1.0))))

;; Revisited resources move to the front without duplicates; both cache limits evict old images.
(deftest preview-cache-budget-and-recency
  (let [entries (reduce preview/retain-preview []
                        (mapv (fn [view] {:view view :bytes (* 5 1024 1024)}) (range 20)))
        revisited (preview/retain-preview entries {:view 16 :bytes (* 5 1024 1024)})]
    (is (= [19 18 17 16 15 14] (mapv :view entries)))
    (is (= [16 19 18 17 15 14] (mapv :view revisited)))
    (is (<= (transduce (map :bytes) + 0 revisited) (* 32 1024 1024)))
    (is (= 8 (count (reduce preview/retain-preview []
                           (mapv (fn [view] {:view view :bytes 4}) (range 20))))))))

;; Both portrait and landscape editor projections must use the device aspect, including auto cameras.
(deftest device-preview-orientation
  (doseq [gui-dims [[540 1170] nil]
          device-dims [[1170 2532] [2532 1170]]]
    (let [{:keys [aspect camera]} (scene/game-preview-camera {:renderables {}} gui-dims [540 1170] device-dims)]
      (is (== aspect (/ (double (first device-dims)) (second device-dims))))
      (is (== aspect (/ (:fov-x camera) (:fov-y camera))))
      (is (<= 540 (:fov-x camera)))
      (is (<= 1170 (:fov-y camera))))))

;; All 82 published device definitions (83 screens) ship as geometry; every orientation is valid.
(deftest complete-device-catalog
  (let [catalog (subvec (vec SimulatorDevice/PRESETS) 6)]
    (is (= 83 (count catalog)))
    (is (= 83 (count (set (mapv #(.name ^SimulatorDevice %) catalog)))))
    (doseq [^SimulatorDevice device catalog
            rotation (into [] (coll/keys (.orientations device)))]
      (let [[top right bottom left] (vec (.insets device rotation))]
        (is (< (+ top bottom) (.pixelHeight device rotation)))
        (is (< (+ left right) (.pixelWidth device rotation)))
        (is (.supports device (.nextRotation device rotation)))))))

;; The lock is a compact toolbar button and resource identity moves into the title, with menu-only setup.
(deftest preview-toolbar-controls
  @(fx/on-fx-thread
     (with-open [pane (GamePreview. "preview-toolbar-test" (fn []) (fn [])
                                    (reify BiConsumer (accept [_ _ _])))]
       (let [root (.getNode pane)
             lock (.lookup root "#game-preview-lock")
             title (.lookup root "#game-preview-resource")
             items (.get (.getProperties root) "dock-menu-items")
             toolbar-children (vec (.getChildren (.getParent lock)))]
         (is (instance? ToggleButton lock))
         (is (= ["game-preview-lock" "game-remote-pairing"]
                (mapv #(.getId ^javafx.scene.Node %) (subvec toolbar-children (- (count toolbar-children) 2)))))
         (is (nil? (.lookup root "#game-simulator-custom")))
         (is (= ["game-simulator-custom" "game-simulator-import"] (mapv #(.getId ^javafx.scene.control.MenuItem %) items)))
         (.setPreviewResource pane "/main/screen.gui")
         (is (= "Game · screen.gui" (.getText title)))
         (is (= "/main/screen.gui" (.getText (.getTooltip title))))
         (.setSelected ^ToggleButton lock true)
         (is (.isPreviewLocked pane))
         (.unlockPreview pane)
         (is (not (.isPreviewLocked pane)))))))

;; Explicitly imported geometry must override a same-name built-in and survive project reopening.
(deftest imported-device-overrides-built-in
  (let [directory (Files/createTempDirectory "device-override" (make-array java.nio.file.attribute.FileAttribute 0))
        definition (.resolve directory "override.device")]
    (spit (.toFile definition) "{\"version\":1,\"friendlyName\":\"Apple iPhone X\",\"screens\":[{\"width\":1125,\"height\":2436,\"orientations\":[{\"orientation\":1,\"safeArea\":{\"x\":0,\"y\":20,\"width\":1125,\"height\":2400}}]}]}")
    (try
      @(fx/on-fx-thread
         (let [make-pane #(GamePreview. (str directory) (fn []) (fn []) (reify BiConsumer (accept [_ _ _])))
               simulator-field (doto (.getDeclaredField GamePreview "simulator") (.setAccessible true))]
           (with-open [pane (make-pane)]
             (let [simulator (.get simulator-field pane)
                   method (doto (.getDeclaredMethod (class simulator) "addDefinition" (into-array Class [Path])) (.setAccessible true))
                   imported (.invoke method simulator (object-array [definition]))
                   combo ^ComboBox (.lookup (.getNode pane) "#game-simulator-device")]
               (.setValue combo (first imported))))
           (with-open [pane (make-pane)]
             (let [combo ^ComboBox (.lookup (.getNode pane) "#game-simulator-device")
                   selected ^SimulatorDevice (.getValue combo)]
               (is (= "Apple iPhone X · 1125 × 2436" (.name selected)))
               (is (= 20 (aget (.insets selected 0) 2)))
               (is (= 1 (count (filterv #(= (.name selected) (.name ^SimulatorDevice %)) (.getItems combo)))))))))
      (finally
        (Files/deleteIfExists (.resolve directory ".internal/game-simulator.properties"))
        (Files/deleteIfExists (.resolve directory ".internal"))
        (Files/deleteIfExists definition)
        (Files/deleteIfExists directory)))))

;; GUI framing uses layout bounds rather than framing all authored nodes, including offscreen nodes.
(deftest gui-and-default-game-projection
  (doseq [gui-dims [[540 1170] nil]]
    (let [{:keys [camera mode aspect]} (scene/game-preview-camera {:renderables {}} gui-dims [960 640] nil)
          [w h] (or gui-dims [960 640] nil)]
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
        result (scene/game-preview-camera data nil [960 640] nil)]
    (is (= "game-preview.edit-camera" (:mode result)))
    (is (= (Point3d. 50.0 60.0 70.0) (get-in result [:camera :position])))
    (is (= 480.0 (get-in result [:camera :fov-x])))
    (is (= 320.0 (get-in result [:camera :fov-y])))
    (is (= 1.0 (get-in result [:camera :z-near])))
    (is (= 1000.0 (get-in result [:camera :z-far])))
    (is (= "game-preview.edit-gui" (:mode (scene/game-preview-camera data [960 640] [960 640] nil))))
    (is (nil? (scene/game-preview-camera
                {:renderables {pass/selection [(assoc-in a [:user-data :orthographic-zoom] 0)]}}
                nil [960 640] nil)))))

;; Starting clears the edit image; stopping restores it with the correct OpenGL vertical orientation.
(deftest runtime-edit-preview-handoff
  (let [view @(fx/on-fx-thread (GamePreview. "idle-handoff-test" (fn []) (fn [])
                                (reify BiConsumer (accept [_ _ _]))))
        idle (WritableImage. 16 8)]
    (try
      @(fx/on-fx-thread
         (.setPreviewResource view "/main/test.gui")
         (.setIdleImage view idle "game-preview.edit-gui")
         (GamePreview/prepare "idle-handoff-test" true))
      @(fx/on-fx-thread
         (let [image ^ImageView (.lookup (.getNode view) "ImageView")]
           (is (nil? (.getImage image)))
           (is (= 1.0 (.getScaleY image)))
           (is (= "Game" (.getText (.lookup (.getNode view) "#game-preview-resource")))))
         (.stop view))
      @(fx/on-fx-thread
         (let [image ^ImageView (.lookup (.getNode view) "ImageView")]
           (is (identical? idle (.getImage image)))
           (is (= -1.0 (.getScaleY image)))
           (is (= "Game · test.gui" (.getText (.lookup (.getNode view) "#game-preview-resource"))))))
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

;; Navigation rejects letterbox clicks instead of clamping them onto a visible GUI node.
(deftest gui-resource-pick-coordinates
  (is (= [0.5 0.5] (vec (GamePreview/resourcePickPoint 1000 600 640 480 500 300))))
  (is (nil? (GamePreview/resourcePickPoint 1000 600 640 480 50 300)))
  (is (nil? (GamePreview/resourcePickPoint 600 1000 640 480 300 50)))
  (is (nil? (GamePreview/resourcePickPoint 0 0 640 480 0 0)))
  (is (nil? (GamePreview/resourcePickPoint 640 480 640 480 640 100))))

;; Only a project-rooted compiled GUI path may navigate; scripts and path traversal cannot.
(deftest runtime-gui-source-path
  (is (= "/screens/menu.gui" (preview/gui-source-path "/screens/menu.guic")))
  (is (= "/界面/菜单.gui" (preview/gui-source-path "/界面/菜单.guic")))
  (doseq [path [nil "" "menu.guic" "/menu.gui_scriptc" "/../menu.guic" "/a/./menu.guic"
               "/a/../menu.guic" "/a\\menu.guic" "/a\u0000menu.guic"]]
    (is (nil? (preview/gui-source-path path)))))

;; Unsupported tabs retain the last live target and avoid redraws; supported tabs still follow,
;; locks still pin, device/project changes still render, and closing the retained view clears it.
(deftest unsupported-tabs-retain-preview
  (with-clean-system
    @(fx/on-fx-thread
       (let [active (atom nil)
             live (atom #{1 2 3})
             width (atom 640)
             renders (atom [])
             source-image (ImageView. (WritableImage. 16 8))
             select! (fn [view ext]
                       (reset! active {:view view :resource {:ext ext :path (str "/test." ext)}}))]
         (with-redefs [ui/ui-disabled? (constantly false)
                       g/node-exists? (fn [_ view] (contains? @live view))
                       g/node-instance? (fn [_ _ view] (contains? @live view))
                       resource/ext :ext
                       resource/proj-path :path
                       scene/make-game-preview-renderer
                       (fn [] {:render! (fn [data _ _ _ _ _ _ _]
                                          (swap! renders conj (:view data))
                                          (WritableImage. 16 8))
                               :dispose! (fn [])})
                       g/node-value (fn [node label _]
                                      (case label
                                        :active-view (:view @active)
                                        :active-resource (:resource @active)
                                        :active-resource-node (:view @active)
                                        :image-view source-image
                                        :scene-dims {:width 640 :height 480}
                                        :display-width @width
                                        :display-height 480
                                        :game-render-data {:view node :renderables {}}
                                        :updatable-states {}
                                        :drawable :test-drawable
                                        :render-clear-color [0.0 0.0 0.0 1.0]
                                        nil))]
           (with-open [pane (GamePreview. "retained-preview-test" (fn []) (fn [])
                                         (reify BiConsumer (accept [_ _ _])))]
             (preview/attach! pane :app :project)
             (let [field (doto (.getDeclaredField GamePreview "idleRefresh") (.setAccessible true))
                   refresh ^Runnable (.get field pane)
                   image ^ImageView (.lookup (.getNode pane) "ImageView")
                   title (.lookup (.getNode pane) "#game-preview-resource")
                   lock ^ToggleButton (.lookup (.getNode pane) "#game-preview-lock")]
               (.run refresh)
               (is (nil? (.getImage image)))
               (doseq [[view ext] [[1 "gui"] [2 "collection"] [3 "go"]]]
                 (select! view ext)
                 (.run refresh)
                 (is (= view (peek @renders)))
                 (is (= (str "Game · test." ext) (.getText title)))
                 (let [before (.getImage image)
                       count-before (count @renders)]
                   (doseq [unsupported ["lua" "script" "atlas" nil]]
                     (select! 9 unsupported)
                     (.run refresh)
                     (is (identical? before (.getImage image)))
                     (is (= (str "Game · test." ext) (.getText title))))
                   (is (= count-before (count @renders))))
                 ;; A changed display input must render the retained resource while a script is active.
                 (swap! width inc)
                 (.run refresh)
                 (is (= view (peek @renders))))
               (.setSelected lock true)
               (select! 1 "gui")
               (.run refresh)
               (is (= "Game · test.go" (.getText title)))
               (.unlockPreview pane)
               (.run refresh)
               (is (= "Game · test.gui" (.getText title)))
               (select! 9 "lua")
               (.setSelected lock true)
               (swap! live disj 1)
               (.run refresh)
               (is (nil? (.getImage image)))
               (is (= "Game" (.getText title)))
               (is (not (.isPreviewLocked pane))))))))))
