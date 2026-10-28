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

(ns editor.skeletal-animation-io-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [editor.fs :as fs]
            [editor.skeletal-animation-data :as data]
            [editor.skeletal-animation-io :as skeletal-io]
            [editor.skeletal-animation-spine :as spine]
            [util.coll :as coll])
  (:import [java.awt.image BufferedImage]
           [java.io File]
           [javax.imageio ImageIO]))

(defn- fixture! [^File directory]
  (let [project (doto (io/file directory "project") .mkdirs)
        source (doto (io/file directory "source") .mkdirs)
        image (io/file source "images" "part.png")
        document (data/add-slot (data/new-document "hero")
                               {:id "body" :bone "root"
                                :attachment {:path "/images/part.png" :width 8.0 :height 8.0}})
        file (io/file source "hero.json")]
    (fs/create-parent-directories! image)
    (ImageIO/write (BufferedImage. 8 8 BufferedImage/TYPE_INT_ARGB) "png" image)
    (spit file (data/export-spine-json document))
    {:project project :file file :image image}))

;; PNG imports copy into project-owned folders, preserve the originals and
;; allocate a new folder on reimport instead of replacing an existing asset.
(deftest import-copies-png-without-overwrite-test
  (let [directory (fs/create-temp-directory! "skeleton-import")]
    (try
      (let [{:keys [project file image]} (fixture! directory)
            first-result (skeletal-io/read-spine-file! file project)
            second-result (skeletal-io/read-spine-file! file project)
            first-path (get-in first-result [:document :slots 0 :attachment :path])
            second-path (get-in second-result [:document :slots 0 :attachment :path])]
        (is (= "hero" (get-in first-result [:document :name])))
        (is (= "/hero_images/images/part.png" first-path))
        (is (= "/hero_images_2/images/part.png" second-path))
        (is (= 1 (count (:files first-result))))
        (is (.isFile image))
        (is (.isFile ^File (first (:files first-result))))
        (is (= (slurp image :encoding "ISO-8859-1")
               (slurp (first (:files first-result)) :encoding "ISO-8859-1"))))
      (finally (fs/delete-directory! directory {:fail :silently})))))

;; Exported atlas animation IDs must match the complete Spine attachment path,
;; including directories; a basename-only atlas cannot render these attachments.
(deftest export-project-resources-test
  (let [directory (fs/create-temp-directory! "skeleton-export")]
    (try
      (let [{:keys [project file]} (fixture! directory)
            document (:document (skeletal-io/read-spine-file! file project))
            result (skeletal-io/export-project! (assoc document :name "hero") project "generated")
            atlas (slurp (io/file project (subs (:atlas result) 1)))
            scene (slurp (io/file project (subs (:spinescene result) 1)))
            model (slurp (io/file project (subs (:spinemodel result) 1)))
            exported (json/read-str (slurp (first (:files result))))]
        (is (= 4 (count (:files result))))
        (is (coll/every? #(.isFile ^File %) (:files result)))
        (is (= "/generated/hero.spinejson" (:spinejson result)))
        (is (string/includes? atlas "id: \"hero_images/images/part\""))
        (is (string/includes? atlas "image: \"/hero_images/images/part.png\""))
        (is (string/includes? scene "spine_json: \"/generated/hero.spinejson\""))
        (is (string/includes? model "skin: \"\""))
        (is (= "hero_images/images/part" (get-in exported ["skins" 0 "attachments" "body" "body" "path"]))))
      (finally (fs/delete-directory! directory {:fail :silently})))))

;; Untrusted attachment paths and export directories cannot escape the project,
;; and a missing source image must not leave partially copied assets behind.
(deftest invalid-image-paths-are-rejected-before-write-test
  (let [directory (fs/create-temp-directory! "skeleton-path-safety")]
    (try
      (let [{:keys [project file image]} (fixture! directory)
            source (json/read-str (slurp file))]
        (testing "Traversal and absolute attachment paths are rejected"
          (doseq [path ["../escape" "/absolute" "C:/absolute" "images\\part"]]
            (spit file (json/write-str (assoc-in source ["skins" 0 "attachments" "body" "body" "path"] path)))
            (is (thrown? clojure.lang.ExceptionInfo (skeletal-io/read-spine-file! file project)))))
        (testing "Image sequences cannot silently omit their extra PNG frames"
          (spit file (json/write-str (assoc-in source ["skins" 0 "attachments" "body" "body" "sequence"] {"count" 2})))
          (is (thrown? clojure.lang.ExceptionInfo (skeletal-io/read-spine-file! file project)))
          (is (zero? (count (.listFiles project)))))
        (spit file (json/write-str source))
        (testing "Missing assets are detected before the destination is created"
          (.delete image)
          (is (thrown? clojure.lang.ExceptionInfo (skeletal-io/read-spine-file! file project)))
          (is (zero? (count (.listFiles project)))))
        (testing "Export cannot target a sibling directory"
          (is (thrown? clojure.lang.ExceptionInfo
                       (skeletal-io/export-project! (data/new-document "hero") project "../outside"))))
        (is (not (.exists (io/file directory "outside")))))
      (finally (fs/delete-directory! directory {:fail :silently})))))

;; Native metadata must survive ordinary document save/reload without turning
;; Spine JSON keys into keywords or discarding unselected skeletons.
(deftest native-project-metadata-save-reload-test
  (let [directory (fs/create-temp-directory! "skeleton-native-metadata")]
    (try
      (let [{:keys [project file]} (fixture! directory)
            source (json/read-str (slurp file))
            skeletons [{:name "edited" :source source}
                       {:name "untouched" :source (assoc source "events" {"marker" {"string" "keep"}})}]
            imported (:document (skeletal-io/read-native-skeleton! file project skeletons "edited"))
            saved (data/read-json (data/write-json imported))
            saved (data/update-bone saved "root" {:x 25})
            exported (atom nil)]
        (with-redefs [spine/write-project! (fn [skeletons _ _] (reset! exported skeletons))]
          (skeletal-io/write-native-project! saved project (io/file directory "new.spine") {:timeout-seconds 1}))
        (is (= ["edited" "untouched"] (mapv :name @exported)))
        (is (= 25 (get-in @exported [0 :source "bones" 0 "x"])))
        (is (= {"marker" {"string" "keep"}} (get-in @exported [1 :source "events"])))
        (is (= (.getCanonicalFile project) (.getCanonicalFile (io/file (get-in @exported [0 :source "skeleton" "images"])))))
        (is (= saved (data/read-json (data/write-json saved))))
        (is (thrown? clojure.lang.ExceptionInfo (skeletal-io/read-native-skeleton! file project skeletons "missing"))))
      (finally (fs/delete-directory! directory {:fail :silently})))))

;; The exported model must select the same nondefault skin as the authoring
;; preview; otherwise a valid export silently displays a different character.
(deftest exported-model-selects-active-skin-test
  (let [directory (fs/create-temp-directory! "skeleton-export-skin")]
    (try
      (let [{:keys [project file]} (fixture! directory)
            document (data/duplicate-skin (:document (skeletal-io/read-spine-file! file project)) "winter")
            result (skeletal-io/export-project! document project "generated")
            model (slurp (io/file project (subs (:spinemodel result) 1)))
            source (json/read-str (slurp (io/file project (subs (:spinejson result) 1))))]
        (is (string/includes? model "skin: \"winter\""))
        (is (= ["base" "winter"] (mapv #(get % "name") (get source "skins")))))
      (finally (fs/delete-directory! directory {:fail :silently})))))

;; Independent Save As must create and reopen real native projects without ever
;; launching the installed Spine bridge or replacing the user's original file.
(deftest independent-native-save-edit-reopen-test
  (let [directory (fs/create-temp-directory! "skeleton-independent")]
    (try
      (with-redefs [spine/write-project! (fn [& _] (throw (AssertionError. "Native save invoked Spine")))]
        (let [{:keys [project file]} (fixture! directory)
              original-document (:document (skeletal-io/read-spine-file! file project))
              target (io/file directory "independent.spine")
              _ (skeletal-io/write-independent-project! original-document project target)
              original-bytes (slurp target :encoding "ISO-8859-1")
              decoded (skeletal-io/read-independent-project! target)
              imported (:document (skeletal-io/read-independent-skeleton! target project decoded "hero"))
              edited (data/update-bone imported "root" {:x 42.0})
              edited-target (io/file directory "edited.spine")]
          (is (= ["hero"] (mapv :name (:skeletons decoded))))
          (is (= "independent-4.2.43" (get-in imported [:native-project :codec])))
          (is (= 0 (get-in imported [:native-project :selected-index])))
          (skeletal-io/write-independent-project! edited project edited-target)
          (is (= 42.0 (get-in (skeletal-io/read-independent-project! edited-target) [:skeletons 0 :source "bones" 0 "x"])))
          (is (= original-bytes (slurp target :encoding "ISO-8859-1")))
          (is (thrown? clojure.lang.ExceptionInfo (skeletal-io/write-independent-project! edited project target)))
          (is (= original-bytes (slurp target :encoding "ISO-8859-1")))
          (is (thrown? clojure.lang.ExceptionInfo (skeletal-io/write-independent-project! edited project (io/file directory "wrong.json"))))))
      (finally (fs/delete-directory! directory {:fail :silently})))))

;; Unsupported native features fail before creating a target instead of silently
;; dropping content or delegating conversion to an installed external editor.
(deftest independent-native-failure-is-atomic-test
  (let [directory (fs/create-temp-directory! "skeleton-independent-reject")]
    (try
      (let [document (assoc (data/new-document "unsupported") :spine-source (json/write-str {"bones" [{"name" "root"}] "animations" {"default" {"futureTimeline" [{"time" 0.0}]}}}))
            target (io/file directory "output" "unsupported.spine")]
        (is (thrown? clojure.lang.ExceptionInfo (skeletal-io/write-independent-project! document directory target)))
        (is (not (.exists target)))
        (is (not (.exists (.getParentFile target)))))
      (finally (fs/delete-directory! directory {:fail :silently})))))
