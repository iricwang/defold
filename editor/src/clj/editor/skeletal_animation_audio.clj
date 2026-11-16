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

(ns editor.skeletal-animation-audio
  "Project-local event audio with bounded loading and explicit disposal."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.skeletal-animation-events :as events]
            [editor.ui :as ui]
            [util.coll :as coll])
  (:import [java.io File]
           [java.nio.file Files LinkOption]
           [java.util UUID]
           [javafx.scene.media AudioClip]))

(set! *warn-on-reflection* true)

(defn resolve-file
  "Resolve an existing project file. Never follow a link outside the project."
  [project-root document audio-path]
  (when-not (string/blank? audio-path)
    (let [root (.toPath (.getCanonicalFile (io/file project-root)))
          source (when-let [source (:spine-source document)] (json/read-str source))
          directory (get-in source ["skeleton" "audio"] "")
          paths (cond-> [audio-path] (not (string/blank? directory)) (conj (str directory "/" audio-path)))]
      (or (reduce (fn [_ path]
                    (when-not (.isAbsolute (io/file path))
                      (let [file (io/file project-root path)
                            canonical (.toPath (.getCanonicalFile file))]
                        (when (and (.startsWith canonical root)
                                   (Files/isRegularFile canonical (make-array LinkOption 0)))
                          (reduced (.toFile canonical)))))) nil paths)
          (throw (ex-info "Audio must reference an existing file inside the game project." {:audio audio-path}))))))

(defn import-file!
  "Copy a chosen audio file into the project without overwriting an asset."
  [project-root source]
  (let [^File source (io/file source)
        extension (string/lower-case (last (string/split (.getName source) #"\.")))
        root (.getCanonicalFile (io/file project-root))
        directory (.getCanonicalFile (io/file root "skeleton_audio"))]
    (when-not (and (.isFile source) (contains? #{"wav" "mp3" "ogg" "aif" "aiff" "m4a"} extension))
      (throw (ex-info "Choose a WAV, MP3, OGG, AIFF or M4A audio file." {})))
    (when-not (.startsWith (.toPath directory) (.toPath root))
      (throw (ex-info "Audio import directory escapes the project." {})))
    (Files/createDirectories (.toPath directory) (make-array java.nio.file.attribute.FileAttribute 0))
    (let [stem (string/replace (.getName source) #"[^A-Za-z0-9_-]" "_")
          file (io/file directory (str stem "_" (subs (str (UUID/randomUUID)) 0 8) "." extension))]
      (try
        (Files/copy (.toPath source) (.toPath file) ^java.nio.file.CopyOption/1 (make-array java.nio.file.CopyOption 0))
        (str "skeleton_audio/" (.getName file))
        (catch Exception error (.delete file) (throw error))))))

(defn assign-file
  "Assign a project-local audio resource without retaining its old native root."
  [document event-name path]
  (cond-> (assoc (events/update-definition document event-name {:audio path}) :audio-project-relative true)
    (:spine-source document)
    (update :spine-source (fn clear-native-audio-root [source]
                            (json/write-str (assoc-in (json/read-str source) ["skeleton" "audio"] ""))))))

(defn- load-clip! [^File file]
  (AudioClip. (str (.toURI file))))

(defn- stop-clip! [^AudioClip clip]
  (.stop clip))

(defn- play-clip! [^AudioClip clip event]
  (.play clip (double (max 0.0 (min 1.0 (:volume event 1.0))))
         (double (max -1.0 (min 1.0 (:balance event 0.0)))) 1.0 0.0 0))

(defn- make-player* [project-root report-error! {:keys [load! play! stop!]}]
  (let [state (atom {:generation 0 :closed false :clips {} :loading {} :muted false})
        stop-all! (fn stop-audio []
                    (swap! state (fn cancel-pending [state]
                                   (-> state
                                       (update :generation inc)
                                       (update :loading #(coll/map-vals (constantly []) %)))))
                    (run! stop! (coll/vals (:clips @state))))
        loaded! (fn audio-loaded [key result]
                  (let [pending (get-in @state [:loading key])]
                    (swap! state update :loading dissoc key)
                    (if-let [clip (:clip result)]
                      (if (:closed @state)
                        (stop! clip)
                        (do
                          (when (>= (count (:clips @state)) 32)
                            (let [[old-key old-clip] (first (:clips @state))]
                              (stop! old-clip)
                              (swap! state update :clips dissoc old-key)))
                          (swap! state assoc-in [:clips key] clip)
                          (run! #(% clip) pending)))
                      (when (and (not (:closed @state)) (pos? (count pending)))
                        (report-error! (str "Audio preview: " (.getMessage ^Exception (:error result))))))))
        play-event! (fn play-event-audio [document event]
                      (when (and (not (:closed @state)) (not (:muted @state))
                                 (not (string/blank? (:audio event))))
                        (try
                          (let [file (resolve-file project-root document (:audio event))
                                key [(.getPath ^File file) (.lastModified ^File file)]
                                generation (:generation @state)
                                play-loaded! (fn play-loaded-clip [clip]
                                               (when (and (= generation (:generation @state))
                                                          (not (:closed @state)) (not (:muted @state)))
                                                 (play! clip event)))]
                            (if-let [clip (get-in @state [:clips key])]
                              (play-loaded! clip)
                              (let [loading (contains? (:loading @state) key)]
                                (swap! state update-in [:loading key] (fnil conj []) play-loaded!)
                                (when-not loading
                                  (future
                                    (let [result (try {:clip (load! file)}
                                                      (catch Exception error {:error error}))]
                                      (ui/run-later (loaded! key result))))))))
                          (catch Exception error (report-error! (.getMessage error))))))]
    {:play! play-event!
     :stop! stop-all!
     :mute! (fn mute-audio [muted] (swap! state assoc :muted muted) (when muted (stop-all!)))
     :dispose! (fn dispose-audio [] (stop-all!) (swap! state assoc :closed true :clips {}))}))

(defn make-player
  "Call the returned functions on the FX thread. Loading runs on a worker.
  Muting, seeking, stopping and disposal invalidate pending playback requests."
  [project-root report-error!]
  (make-player* project-root report-error! {:load! load-clip! :play! play-clip! :stop! stop-clip!}))
