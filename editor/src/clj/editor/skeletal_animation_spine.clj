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

(ns editor.skeletal-animation-spine
  "Explicit compatibility bridge to an installed, licensed Spine CLI. This is
  not an independent decoder for the proprietary .spine project format. Calls
  run off the UI thread, never overwrite an input, and use private staging."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [editor.fs :as fs]
            [editor.process :as process]
            [util.coll :as coll])
  (:import [java.io File]
           [java.lang ProcessBuilder$Redirect ProcessHandle]
           [java.nio.file CopyOption Files]
           [java.util.concurrent TimeUnit]
           [java.util.stream Stream]))

(set! *warn-on-reflection* true)

(defn configuration []
  (let [configured (or (System/getProperty "defold.spine.executable") (System/getenv "DEFOLD_SPINE_EXECUTABLE"))
        candidates (if configured
                     [(io/file configured)]
                     (into [(io/file "/Applications/Spine.app/Contents/MacOS/Spine")]
                           (comp (keep #(System/getenv %)) (map #(io/file % "Spine" "Spine.com")))
                           ["ProgramFiles" "ProgramFiles(x86)"]))
        executable (coll/first-where #(.isFile ^File %) candidates)]
    (when-not executable
      (throw (ex-info "Native .spine compatibility requires an installed licensed Spine. Set DEFOLD_SPINE_EXECUTABLE to its command-line executable." {})))
    {:executable (.getCanonicalPath ^File executable)
     :version (System/getProperty "defold.spine.version" "4.2.43")
     :timeout-seconds 180}))

(defn- run-cli! [{:keys [executable version timeout-seconds]} directory arguments]
  (when-not (and (string? version) (re-matches #"4\.2\.\d+" version))
    (throw (ex-info "The native project bridge currently requires a pinned Spine 4.2 version." {:version version})))
  (let [log (io/file directory "spine-cli.log")
        ;; macOS JavaFX sets CFProcessPath to the editor launcher. Inheriting it
        ;; makes another Cocoa launcher identify itself as Defold and wait for
        ;; GUI events instead of entering the requested command-line operation.
        environment (into {"CFProcessPath" nil "JAVA_TOOL_OPTIONS" nil "JDK_JAVA_OPTIONS" nil "_JAVA_OPTIONS" nil}
                          (comp (map key)
                                (filter #(coll/any? (fn [prefix] (string/starts-with? % prefix))
                                                    ["APP_NAME_" "APP_ICON_" "JAVA_MAIN_CLASS_" "JAVA_STARTED_ON_FIRST_THREAD_"]))
                                (map #(vector % nil)))
                          (System/getenv))
        ^Process child (apply process/start! {:dir directory :out (ProcessBuilder$Redirect/to log) :err :stdout :env environment}
                     executable "-u" version arguments)]
    (try
      (.close (process/in child))
      (when-not (.waitFor child (long timeout-seconds) TimeUnit/SECONDS)
        (throw (ex-info "Spine conversion timed out. No destination project was replaced." {})))
      (when-not (zero? (.exitValue child))
        (let [details (-> (slurp log)
                          (string/replace #"(?m)^Licensed to:.*$" "")
                          (string/trim))]
          (throw (ex-info (str "Spine conversion failed: " (subs details 0 (min 4096 (count details))))
                          {:exit-code (.exitValue child)}))))
      (finally
        (when (.isAlive child)
          (with-open [^Stream descendants (.descendants child)]
            (.forEach descendants (reify java.util.function.Consumer
                                    (accept [_ handle] (.destroyForcibly ^ProcessHandle handle)))))
          (.destroyForcibly child))))))

(defn read-project!
  "Read all skeletons from a native project. The source remains untouched.
  Returns [{:name string :source Spine-JSON-map} ...]. PNG references remain
  relative to the original .spine file, not the temporary export directory."
  [file config]
  (let [file (.getCanonicalFile (io/file file))
        directory (fs/create-temp-directory! "defold-spine-read")]
    (try
      (when-not (.isFile file)
        (throw (ex-info "The Spine project does not exist." {:file (str file)})))
      (let [settings (io/file directory "settings.json")
            output (doto (io/file directory "skeletons") .mkdirs)]
        (spit settings (json/write-str {"class" "json" "extension" ".json" "format" "JSON"
                                       "nonessential" true "prettyPrint" true "cleanUp" false}))
        (run-cli! config directory ["-i" (str file) "-o" (str output) "-e" (str settings)])
        (let [files (into [] (filter #(and (.isFile ^File %) (string/ends-with? (.getName ^File %) ".json"))) (.listFiles output))]
          (when (zero? (count files))
            (throw (ex-info "Spine did not export any skeletons. The project was not imported." {})))
          (mapv (fn [^File json-file]
                  (let [source (json/read-str (slurp json-file))]
                    (when-not (re-matches #"4\.2(?:\..*)?" (get-in source ["skeleton" "spine"] ""))
                      (throw (ex-info "Spine returned an unsupported skeleton version." {:file (.getName json-file)})))
                    {:name (string/replace (.getName json-file) #"\.json$" "") :source source}))
                (sort-by #(.getName ^File %) files))))
      (finally (fs/delete-directory! directory {:fail :silently})))))

(defn write-project!
  "Write a NEW native project from one or more skeleton JSON maps. Deliberately
  refuses existing destinations: JSON does not preserve every project-only
  setting, so overwriting an original project would imply unsafe losslessness.
  The caller resolves image paths before invoking this compatibility bridge."
  [skeletons target config]
  (let [target (.getCanonicalFile (io/file target))
        names (mapv :name skeletons)
        directory (fs/create-temp-directory! "defold-spine-write")]
    (try
      (when (or (zero? (count names)) (not= (count names) (count (set names)))
                (not (coll/every? #(and (string? %) (not (string/blank? %))) names)))
        (throw (ex-info "Native projects need uniquely named skeletons." {})))
      (when (or (.exists target) (not (string/ends-with? (string/lower-case (.getName target)) ".spine")))
        (throw (ex-info "Choose a new .spine file. Original projects are never overwritten by the compatibility bridge." {:file (str target)})))
      (let [staged (io/file directory "project.spine")]
        (doseq [[index {:keys [name source]}] (map-indexed vector skeletons)]
          (let [input (io/file directory (str "skeleton-" index ".json"))]
            (spit input (json/write-str source :escape-slash false))
            (run-cli! config directory ["-i" (str input) "-o" (str staged) "-r" name])))
        (when-not (and (.isFile staged) (pos? (.length staged)))
          (throw (ex-info "Spine did not create a project. Nothing was installed." {})))
        (fs/create-parent-directories! target)
        ;; No REPLACE_EXISTING: also protects a file created while conversion ran.
        (Files/copy (.toPath staged) (.toPath target) ^CopyOption/1 (make-array CopyOption 0))
        target)
      (finally (fs/delete-directory! directory {:fail :silently})))))
