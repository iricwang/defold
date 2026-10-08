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

(ns editor.patch-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [editor.patch :as patch])
  (:import [com.defold.editor PatchInstaller PatchRestart]
           [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.file Files]
           [java.util.zip ZipEntry ZipOutputStream]
           [java.nio.file.attribute FileAttribute]
           [org.apache.commons.codec.digest DigestUtils]))

(set! *warn-on-reflection* true)

(defn- with-server [routes f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (with-open [^HttpExchange exchange exchange]
                          (let [{:keys [body status location]
                                 :or {body "missing" status 404}} (get @routes (.getPath (.getRequestURI exchange)))
                                bytes (.getBytes ^String body "UTF-8")]
                            (when location
                              (.add (.getResponseHeaders exchange) "Location" location))
                            (.sendResponseHeaders exchange status (alength bytes))
                            (with-open [out (.getResponseBody exchange)]
                              (.write out bytes)))))))
    (.start server)
    (try
      (f (str "http://127.0.0.1:" (.getPort (.getAddress server))))
      (finally (.stop server 0)))))

;; Verifies patch ordering, channel isolation and invalid responses, guarding against false update success.
(deftest check-patch-test
  (let [routes (atom {})]
    (with-server routes
      (fn [base]
        (let [config {:feed (str base "/manifest.json") :channel "dev" :repository "owner/repo" :revision 2}
              manifest {:schema 1 :channel "dev" :repository "owner/repo" :revision 3 :version "patch.3"
                        :notes "Fixes" :assets {:arm64-macos {:url (str base "/patch.dmg")
                                                              :size 4 :base_revision 2 :sha256 (DigestUtils/sha256Hex "test")}}}
              set-manifest! #(swap! routes assoc "/manifest.json" {:status 200 :body (json/write-str %)})]
          (set-manifest! manifest)
          (is (= :available (:status (patch/check! config "arm64-macos"))))
          (is (= :unsupported (:status (patch/check! config "x86_64-macos"))))
          (set-manifest! (-> manifest
                             (assoc :installers (:assets manifest))
                             (assoc-in [:assets :arm64-macos :base_revision] 1)))
          (is (= :installer (:kind (patch/check! config "arm64-macos"))))
          (doseq [revision [1 2]]
            (set-manifest! (assoc manifest :revision revision))
            (is (= :current (:status (patch/check! config "arm64-macos")))))
          (doseq [invalid [(assoc manifest :channel "stable")
                           (assoc manifest :repository "other/repo")
                           (assoc manifest :revision "3")
                           (assoc-in manifest [:assets :arm64-macos :sha256] "bad")
                           (assoc-in manifest [:assets :arm64-macos :url] "file:///tmp/patch.dmg")]]
            (set-manifest! invalid)
            (is (thrown? Exception (patch/check! config "arm64-macos"))))
          (swap! routes assoc "/manifest.json" {:status 404})
          (is (thrown-with-msg? Exception #"HTTP 404" (patch/check! config "arm64-macos"))))))))

;; Verifies redirects, size and SHA-256 checks, and cleanup of failed downloads before offering installation.
(deftest download-patch-test
  (let [routes (atom {"/redirect" {:status 302 :location "/patch.dmg"}
                      "/patch.dmg" {:status 200 :body "test"}})
        directory (.toFile (Files/createTempDirectory "patch-test" (make-array FileAttribute 0)))]
    (try
      (with-server routes
        (fn [base]
          (let [asset {:url (str base "/redirect") :size 4 :sha256 (DigestUtils/sha256Hex "test")}
                received (atom nil)
                file (patch/download! asset directory #(reset! received %))]
            (is (= "test" (slurp file)))
            (is (= 4 @received))
            (io/delete-file file)
            (testing "Failures leave no installer or partial file"
              (doseq [invalid [(assoc asset :sha256 (DigestUtils/sha256Hex "wrong"))
                               (assoc asset :size 3)
                               (assoc asset :size 5)
                               (assoc asset :url (str base "/404"))]]
                (is (thrown? Exception (patch/download! invalid directory (fn [_]))))
                (is (zero? (alength (.listFiles directory)))))))))
      (finally
        (doseq [file (.listFiles directory)] (io/delete-file file))
        (io/delete-file directory)))))

(defn- write-patch! [file plan files]
  (with-open [zip (ZipOutputStream. (io/output-stream file))]
    (doseq [[path body] (assoc files "patch.json" (json/write-str plan))]
      (.putNextEntry zip (ZipEntry. ^String path))
      (.write zip (.getBytes ^String body "UTF-8"))
      (.closeEntry zip))))

;; Verifies staged application, obsolete-file removal and atomic rollback; the running app stays untouched during preparation.
(deftest install-patch-test
  (let [directory (.toFile (Files/createTempDirectory "patch-install-test" (make-array FileAttribute 0)))
        app (io/file directory "Defold.app")
        archive (io/file directory "patch.zip")
        item (fn [path body mode] {:path path :kind "file" :mode mode :sha256 (DigestUtils/sha256Hex ^String body)})
        config-path "Contents/Resources/config"
        launcher-path "Contents/MacOS/Defold"
        old-path "Contents/Resources/old.jar"
        new-path "Contents/Resources/new.jar"
        license-path "Contents/Resources/license"
        link-path "Contents/Resources/license-link"
        plan {:schema 1 :base_revision 1 :revision 2
              :base [(item config-path "old" 420) (item launcher-path "launcher" 493) (item old-path "obsolete" 420)
                     (item license-path "license" 420) (item link-path "license" 420)]
              :target [(item config-path "new" 420) (item launcher-path "launcher" 493) (item new-path "added" 420)
                       (item license-path "license" 420) (item link-path "license" 420)]}
        files {(str "files/" config-path) "new" (str "files/" new-path) "added"}]
    (try
      (doseq [[path body] {config-path "old" launcher-path "launcher" old-path "obsolete" license-path "license"}]
        (io/make-parents (io/file app path))
        (spit (io/file app path) body))
      (Files/createSymbolicLink (.toPath (io/file app link-path)) (.toPath (io/file "license")) (make-array FileAttribute 0))
      (.setExecutable (io/file app launcher-path) true)
      (write-patch! archive plan files)
      (let [staged (PatchInstaller/prepare archive app 1 2)
            backup (io/file directory "backup")]
        (is (= "old" (slurp (io/file app config-path))))
        (is (= "new" (slurp (io/file staged config-path))))
        (is (= "added" (slurp (io/file staged new-path))))
        (is (not (.exists (io/file staged old-path))))
        (is (.canExecute (io/file staged launcher-path)))
        (is (Files/isSymbolicLink (.toPath (io/file staged link-path))))
        (PatchRestart/swap (.toPath app) (.toPath staged) (.toPath backup))
        (is (= "new" (slurp (io/file app config-path))))
        (is (= "old" (slurp (io/file backup config-path))))
        (is (thrown? Exception (PatchRestart/swap (.toPath app) (.toPath staged) (.toPath (io/file directory "rollback")))))
        (is (= "new" (slurp (io/file app config-path)))))
      (testing "Wrong installed base and corrupt payload cannot modify the app"
        (is (thrown? Exception (PatchInstaller/prepare archive app 1 2)))
        (is (= "new" (slurp (io/file app config-path))))
        (write-patch! archive (assoc plan :base (:target plan)) (assoc files (str "files/" config-path) "corrupt"))
        (is (thrown? Exception (PatchInstaller/prepare archive app 1 2)))
        (is (= "new" (slurp (io/file app config-path)))))
      (testing "Traversal paths are rejected before modifying any file"
        (write-patch! archive (update plan :base conj (item "Contents/../../escape" "x" 420)) files)
        (is (thrown? Exception (PatchInstaller/prepare archive app 1 2))))
      (finally
        (PatchInstaller/deleteTree (.toPath directory))))))

;; Verifies the standalone helper waits for editor exit, then switches apps and launches the replacement.
(deftest restart-helper-test
  (let [directory (.toFile (Files/createTempDirectory "patch-restart-test" (make-array FileAttribute 0)))
        app (io/file directory "Defold.app")
        staged (io/file directory "staged")
        helper-dir (io/file directory "helper")
        helper-class (io/file helper-dir "com/defold/editor/PatchRestart.class")
        parent (.start (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String ["/bin/sh" "-c" "read -r signal"])))]
    (try
      (io/make-parents helper-class)
      (with-open [input (io/input-stream (io/resource "com/defold/editor/PatchRestart.class"))]
        (io/copy input helper-class))
      (doseq [[root version] [[app "old"] [staged "new"]]]
        (io/make-parents (io/file root "Contents/MacOS/Defold"))
        (spit (io/file root "version") version)
        (spit (io/file root "Contents/MacOS/Defold") "#!/bin/sh\necho PATCH_RESTART_OK\n")
        (.setExecutable (io/file root "Contents/MacOS/Defold") true))
      (let [helper (.start (doto (ProcessBuilder. ^"[Ljava.lang.String;" (into-array String
                                                                                     [(str (io/file (System/getProperty "java.home") "bin/java"))
                                                                                      "-cp" (str helper-dir) "com.defold.editor.PatchRestart"
                                                                                      (str (.pid parent)) (str app) (str staged)
                                                                                      (str (io/file directory "editor.log"))]))
                             (.redirectErrorStream true)))]
        (try
          (with-open [reader (io/reader (.getInputStream helper))]
            (is (= "READY" (deref (future (.readLine ^java.io.BufferedReader reader)) 10000 :timeout)))
            (is (= "old" (slurp (io/file app "version"))))
            (.close (.getOutputStream parent))
            (is (= "STARTED" (deref (future (.readLine ^java.io.BufferedReader reader)) 10000 :timeout)))
            (is (.waitFor helper 10 java.util.concurrent.TimeUnit/SECONDS))
            (is (= 0 (.exitValue helper)))
            (is (= "new" (slurp (io/file app "version")))))
          (finally (.destroyForcibly helper))))
      (finally
        (.destroyForcibly parent)
        (PatchInstaller/deleteTree (.toPath directory))))))

;; Verifies a local feed override cannot pin a packaged app to the old revision after restart.
(deftest installed-version-with-local-feed-test
  (let [directory (.toFile (Files/createTempDirectory "patch-config-test" (make-array FileAttribute 0)))
        embedded (json/read-str (slurp (io/resource "patch/build.json")) :key-fn keyword)
        properties ["defold.patch.directory" "defold.version" "defold.dev"]
        previous (into {} (map (fn [key] [key (System/getProperty key)])) properties)]
    (try
      (spit (io/file directory "build.json")
            (json/write-str (assoc embedded :revision 999 :version "preview" :feed "http://127.0.0.1:8765/manifest.json")))
      (System/setProperty "defold.patch.directory" (str directory))
      (System/setProperty "defold.version" "test-packaged-app")
      (System/clearProperty "defold.dev")
      (is (= (:revision embedded) (:revision (patch/configuration))))
      (is (= "http://127.0.0.1:8765/manifest.json" (:feed (patch/configuration))))
      (finally
        (doseq [[key value] previous]
          (if value
            (System/setProperty key value)
            (System/clearProperty key)))
        (PatchInstaller/deleteTree (.toPath directory))))))

;; Patch-only feeds walk backwards to the next compatible update and reject cyclic or foreign history.
(deftest patch-only-history-test
  (let [routes (atom {})]
    (with-server routes
      (fn [base]
        (let [config {:feed (str base "/latest") :channel "dev" :repository "owner/repo" :revision 5}
              next-update {:schema 1 :channel "dev" :repository "owner/repo" :revision 6 :version "patch.6"
                           :notes "Next update" :assets {:arm64-macos {:url (str base "/patch.zip")
                                                                       :size 4 :base_revision 5 :sha256 (DigestUtils/sha256Hex "test")}}}
              latest (-> next-update (assoc :revision 7 :version "patch.7" :previous (str base "/previous"))
                         (assoc-in [:assets :arm64-macos :base_revision] 6))]
          (reset! routes {"/latest" {:status 200 :body (json/write-str latest)}
                          "/previous" {:status 200 :body (json/write-str next-update)}})
          (let [result (patch/check! config "arm64-macos")]
            (is (= :available (:status result)))
            (is (= :patch (:kind result)))
            (is (= 6 (get-in result [:manifest :revision]))))
          (swap! routes assoc "/previous" {:status 200 :body (json/write-str latest)})
          (is (thrown? Exception (patch/check! config "arm64-macos")))
          (swap! routes assoc "/previous" {:status 200 :body (json/write-str (assoc next-update :repository "other/repo"))})
          (is (thrown? Exception (patch/check! config "arm64-macos"))))))))
