# Copyright 2020-2026 The Defold Foundation
# Copyright 2014-2020 King
# Copyright 2009-2014 Ragnar Svensson, Christian Murray
# Licensed under the Defold License version 1.0 (the "License"); you may not use
# this file except in compliance with the License.
#
# You may obtain a copy of the License, together with FAQs at
# https://www.defold.com/license
#
# Unless required by applicable law or agreed to in writing, software distributed
# under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
# CONDITIONS OF ANY KIND, either express or implied. See the License for the
# specific language governing permissions and limitations under the License.
"""Verify resource cache upgrades and compilation using the shipped Windows editor JAR."""
import os
import io
import json
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile


@unittest.skipUnless(os.name == 'nt' and os.environ.get('DEFOLD_PATCH_TEST_JAR'),
                     'Windows editor JAR required')
class EditorPipelineTest(unittest.TestCase):
    # The shipped Windows codec must load; supported hardware must produce decodable configuration.
    def test_packaged_windows_remote_video(self):
        root = Path(__file__).resolve().parents[1]
        jar = os.environ['DEFOLD_PATCH_TEST_JAR']
        with zipfile.ZipFile(jar) as archive:
            self.assertIn('lib/x86_64-win32/remote_video.dll', archive.namelist())
        java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
        compiler = java.with_name('javac.exe')
        if not compiler.exists():
            compiler = next((root / 'editor/build/jdk/x86_64-win32').glob('jdk-*/bin/javac.exe'))
        with tempfile.TemporaryDirectory(prefix='remote-video-', dir=root / 'tmp') as directory:
            compiled = subprocess.run([str(compiler), '-cp', jar, '-d', directory,
                str(root / 'remote/tests/java/RemoteVideoTest.java')], capture_output=True, text=True,
                errors='replace', timeout=60, creationflags=subprocess.CREATE_NO_WINDOW)
            self.assertEqual(0, compiled.returncode, compiled.stdout + compiled.stderr)
            result = subprocess.run([str(java), '--enable-native-access=ALL-UNNAMED', '-cp', directory + os.pathsep + jar,
                'RemoteVideoTest', str(Path(directory) / 'encoded.h264'), 'optional'], capture_output=True, text=True,
                errors='replace', timeout=60, creationflags=subprocess.CREATE_NO_WINDOW)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    # Windows bottom-up capture must become top-left Remote frames at every quality.
    def test_packaged_remote_frame_orientation(self):
        root = Path(__file__).resolve().parents[1]
        java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
        compiler = java.with_name('javac.exe')
        if not compiler.exists():
            compiler = next((root / 'editor/build/jdk/x86_64-win32').glob('jdk-*/bin/javac.exe'))
        jar = os.environ['DEFOLD_PATCH_TEST_JAR']
        with tempfile.TemporaryDirectory(prefix='remote-orientation-', dir=root / 'tmp') as directory:
            classes = Path(directory) / 'classes'
            classes.mkdir()
            compilation = subprocess.run(
                [str(compiler), '-cp', jar, '-d', str(classes),
                 str(root / 'remote/tests/java/RemoteFrameTest.java')],
                capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=60,
                creationflags=subprocess.CREATE_NO_WINDOW)
            self.assertEqual(0, compilation.returncode, compilation.stdout + compilation.stderr)
            result = subprocess.run(
                [str(java), '--enable-native-access=ALL-UNNAMED', '-Dglass.platform=headless', '-Dprism.order=sw',
                 '-cp', str(classes) + os.pathsep + jar, 'com.defold.editor.RemoteFrameTest', directory],
                capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=60,
                creationflags=subprocess.CREATE_NO_WINDOW)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    # Native RectTransform fields must survive the actual shipped Bob protocol.
    def test_packaged_gui_rect_transform_protocol(self):
        code = """(import 'com.dynamo.gamesys.proto.Gui$NodeDesc)
                  (let [descriptor (Gui$NodeDesc/getDescriptor)]
                    (doseq [[field number] {\"rect_transform\" 52 \"rect_anchor_min\" 53
                                           \"rect_anchor_max\" 54 \"rect_reference\" 55 \"font_size\" 56}]
                      (assert (= number (some-> (.findFieldByName descriptor field) .getNumber))
                              (str \"GUI wire field changed: \" field))))"""
        result = subprocess.run(
            [str(Path(os.environ['JAVA_HOME']) / 'bin/java.exe'),
             '-cp', os.environ['DEFOLD_PATCH_TEST_JAR'], 'clojure.main', '-e', code],
            capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=60,
            creationflags=subprocess.CREATE_NO_WINDOW)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    # Fresh patch cache paths must not consume session-wide DLL hook slots or lose native errors.
    def test_game_startup_across_fresh_native_cache_paths(self):
        root = Path(__file__).resolve().parents[1]
        spec = importlib.util.spec_from_file_location('windows_build', root / 'scripts/windows/build-patch.py')
        builder = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(builder)
        with tempfile.TemporaryDirectory(prefix='game-cache-probe-', dir=root / 'tmp') as directory:
            probe = Path(directory) / 'probe.exe'
            builder.build_windows_native(root / 'patch/game-window-probe.c', probe)
            code = f"""(com.defold.libs.ResourceUnpacker/unpackResources)
                      (let [original (System/getProperty "defold.unpack.path")
                            source (java.io.File. original "x86_64-win32/lib/game_window_hook.dll")]
                        (try
                          (dotimes [i 40]
                            (let [cache (java.io.File. {json.dumps(directory)} (str {json.dumps('cache 路径 😀 ')} i))
                                  dll (java.io.File. cache "x86_64-win32/lib/game_window_hook.dll")]
                              (.mkdirs (.getParentFile dll))
                              (clojure.java.io/copy source dll)
                              (System/setProperty "defold.unpack.path" (.getPath cache))
                              (let [native (com.sun.jna.NativeLibrary/getInstance (.getPath dll))]
                                (assert (= 6 (.invokeInt (.getFunction native "StartGuardedGameProcess")
                                             (object-array [nil nil]))) "Native error must survive the call"))
                              (let [p (com.defold.editor.WindowsGameProcess/start
                                        [{json.dumps(str(probe))}] (java.io.File. {json.dumps(directory)}) {{}})
                                    output (with-open [stream (.getInputStream p)] (slurp stream))]
                                (assert (= 0 (.waitFor p)) (str "Cache iteration " i ": " output))
                                (assert (.contains output "HIDDEN_STARTUP_AND_ADOPTION_OK") output)
                                (assert (not (.isAlive p))))))
                          (println "FRESH_CACHE_STARTS_OK 40")
                          (finally (System/setProperty "defold.unpack.path" original))))"""
            result = subprocess.run(
                [str(Path(os.environ['JAVA_HOME']) / 'bin/java.exe'),
                 '--enable-native-access=ALL-UNNAMED',
                 f'-Djdk.net.unixdomain.tmpdir={root / "tmp"}',
                 '-cp', os.environ['DEFOLD_PATCH_TEST_JAR'], 'clojure.main', '-e', code],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                encoding='utf-8', errors='replace', timeout=120,
                creationflags=subprocess.CREATE_NO_WINDOW)
            self.assertEqual(0, result.returncode, result.stdout)
            self.assertIn('FRESH_CACHE_STARTS_OK 40', result.stdout)

    # Simulator ships its device classes, bounded resource cache and matching native safe-area symbols together.
    def test_simulator_and_preview_cache_in_shipped_jar(self):
        code = """(require 'cljfx.api '[editor.game-preview :as preview])
                  (com.defold.libs.ResourceUnpacker/unpackResources)
                  (let [device (com.defold.editor.SimulatorDevice. "Test" 100 200 20 3 10 7 8 20 10 5)
                        entries (reduce preview/retain-preview []
                                  (mapv (fn [i] {:view i :bytes (* 5 1024 1024)}) (range 20)))
                        directory (System/getProperty "defold.unpack.path")
                        library (com.sun.jna.NativeLibrary/getInstance
                                  (str directory "/x86_64-win32/lib/game_window_hook.dll"))
                        resolve-symbol (.getFunction library "SimulatorSafeAreaRva")]
                    (assert (= [7 20 3 10] (vec (.insets device 1))))
                    (assert (= [19 18 17 16 15 14] (mapv :view entries)))
                    (assert (pos? (.invokeLong resolve-symbol
                      (object-array [(com.sun.jna.WString. (str directory "/x86_64-win32/bin/dmengine.exe"))]))))
                    (assert (zero? (.invokeLong resolve-symbol
                      (object-array [(com.sun.jna.WString. (str directory "/missing-engine.exe"))])))))
                  (shutdown-agents) (System/exit 0)"""
        result = subprocess.run(
            [str(Path(os.environ['JAVA_HOME']) / 'bin/java.exe'),
             '--enable-native-access=ALL-UNNAMED', '-Dglass.platform=headless', '-Dprism.order=sw',
             '--add-opens=java.base/java.lang=ALL-UNNAMED',
             '--add-opens=java.base/sun.nio.fs=ALL-UNNAMED',
             '--add-opens=java.desktop/sun.awt=ALL-UNNAMED',
             '--add-opens=java.desktop/sun.awt.image=ALL-UNNAMED',
             '--add-opens=java.desktop/sun.java2d.opengl=ALL-UNNAMED',
             '--add-opens=java.xml/com.sun.org.apache.xerces.internal.jaxp=ALL-UNNAMED',
             f'-Djdk.net.unixdomain.tmpdir={Path.cwd() / "tmp"}',
             '-cp', os.environ['DEFOLD_PATCH_TEST_JAR'], 'clojure.main', '-e', code],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
            encoding='utf-8', errors='replace', timeout=120)
        self.assertEqual(0, result.returncode, result.stdout)

    # Show requests stay hidden until adoption; hidden helpers must never be selected as the game.
    def test_game_window_hidden_until_adopted(self):
        root = Path(__file__).resolve().parents[1]
        spec = importlib.util.spec_from_file_location('windows_build', root / 'scripts/windows/build-patch.py')
        builder = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(builder)
        with tempfile.TemporaryDirectory(prefix='game-window-probe-', dir=root / 'tmp') as directory:
            probe = Path(directory) / 'probe.exe'
            builder.build_windows_native(root / 'patch/game-window-probe.c', probe)
            code = f"""(let [p (com.defold.editor.WindowsGameProcess/start
                                 [{json.dumps(str(probe))}]
                                 (java.io.File. {json.dumps(directory)}) {{}})
                            output (slurp (.getInputStream p))]
                        (assert (= 0 (.waitFor p)) output)
                        (assert (.contains output "HIDDEN_STARTUP_AND_ADOPTION_OK") output)
                        (.close (.getInputStream p)))
                      (let [p (com.defold.editor.WindowsGameProcess/start
                                [{json.dumps(str(probe))} "selection"]
                                (java.io.File. {json.dumps(directory)}) {{}})]
                        (try
                          (with-open [reader (clojure.java.io/reader (.getInputStream p))]
                            (assert (= "SELECTION_READY" (.readLine reader)))
                            (let [method (.getDeclaredMethod
                                           (Class/forName "com.defold.editor.WindowsGameWindow")
                                           "findWindow" (into-array Class [Long/TYPE]))
                                  _ (.setAccessible method true)
                                  window (.invoke method nil (object-array [(.pid p)]))
                                  title (char-array 256)]
                              (assert (some? window))
                              (.GetWindowText com.sun.jna.platform.win32.User32/INSTANCE
                                (com.sun.jna.platform.win32.WinDef$HWND. window) title 256)
                              (assert (= "Game startup regression" (com.sun.jna.Native/toString title)))))
                          (finally (.destroyForcibly p) (.waitFor p))))"""
            result = subprocess.run(
                [str(Path(os.environ['JAVA_HOME']) / 'bin/java.exe'),
                 '--enable-native-access=ALL-UNNAMED',
                 f'-Djdk.net.unixdomain.tmpdir={root / "tmp"}',
                 '-cp', os.environ['DEFOLD_PATCH_TEST_JAR'], 'clojure.main', '-e', code],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                encoding='utf-8', errors='replace', timeout=120)
            self.assertEqual(0, result.returncode, result.stdout)

    # The actual embedded launcher must create no console while preserving arguments, env and logs.
    def test_embedded_game_starts_without_console(self):
        java = str(Path(os.environ['JAVA_HOME']) / 'bin/java.exe')
        jar = os.environ['DEFOLD_PATCH_TEST_JAR']
        argument = '路径 with spaces "quoted" and trailing\\'
        child = """(assert (nil? (.invokePointer
                     (.getFunction (com.sun.jna.NativeLibrary/getInstance "kernel32") "GetConsoleWindow")
                     (object-array 0))))
                   (println (System/getProperty "probe.argument"))
                   (println (System/getenv "DEFOLD_CONSOLE_PROBE"))
                   (binding [*out* *err*] (println "STDERR_CAPTURED"))
                   (System/exit 7)"""
        command = [java, '--enable-native-access=ALL-UNNAMED', '-Dstdout.encoding=UTF-8',
                   '-Dstderr.encoding=UTF-8', '-cp', jar,
                   '-Dprobe.argument=' + argument, 'clojure.main', '-e', child]
        code = f"""(let [p (com.defold.editor.WindowsGameProcess/start
                            {json.dumps(command, ensure_ascii=False)}
                            (java.io.File. {json.dumps(str(Path.cwd()))})
                            {{"DEFOLD_CONSOLE_PROBE" "ENV_CAPTURED"}})
                        output (slurp (.getInputStream p))]
                    (assert (= 7 (.waitFor p)))
                    (assert (not (.isAlive p)))
                    (assert (= p (.get (.onExit p))))
                    (assert (.contains output {json.dumps(argument, ensure_ascii=False)}) (pr-str output))
                    (assert (.contains output "ENV_CAPTURED"))
                    (assert (.contains output "STDERR_CAPTURED"))
                    (.close (.getInputStream p)))"""
        result = subprocess.run(
            [java, '--enable-native-access=ALL-UNNAMED',
             f'-Djdk.net.unixdomain.tmpdir={Path.cwd()}',
             '-cp', jar, 'clojure.main', '-e', code],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
            encoding='utf-8', errors='replace', timeout=60)
        self.assertEqual(0, result.returncode, result.stdout)

    # Exercises real startup with a legacy cache, a warm restart, and a same-commit patch upgrade.
    def test_startup_replaces_stale_resources_and_reuses_current_cache(self):
        java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
        jar = os.environ['DEFOLD_PATCH_TEST_JAR']
        with zipfile.ZipFile(jar) as editor:
            expected = editor.read('lib/builtins.zip')
        with tempfile.TemporaryDirectory(prefix='editor-cache-upgrade-') as directory:
            cache = Path(directory)
            builtins = cache / 'builtins/builtins.zip'
            builtins.parent.mkdir()
            marker = cache / 'editor-sha.txt'
            builtins.write_bytes(b'old resources without debugger')
            marker.write_text('same-commit')

            def start(version):
                result = subprocess.run(
                    [str(java), '--enable-native-access=ALL-UNNAMED',
                     f'-Djdk.net.unixdomain.tmpdir={Path.cwd()}',
                     f'-Ddefold.unpack.path={cache}', '-Ddefold.editor.sha1=same-commit',
                     f'-Ddefold.version={version}',
                     '-cp', jar, 'clojure.main', '-e',
                     '(com.defold.libs.ResourceUnpacker/unpackResources)'],
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                    encoding='utf-8', errors='replace', timeout=120)
                self.assertEqual(0, result.returncode, result.stdout)
                self.assertEqual(expected, builtins.read_bytes())
                self.assertEqual(f'same-commit-{version}', marker.read_text())

            start('1.14.1-patch.51')
            # Set an explicit old timestamp: a warm restart must leave the resource untouched.
            os.utime(builtins, ns=(1_600_000_000_000_000_000,) * 2)
            before = builtins.stat().st_mtime_ns
            start('1.14.1-patch.51')
            self.assertEqual(before, builtins.stat().st_mtime_ns)
            # Keep the SHA and previous completion marker while simulating changed resources.
            builtins.write_bytes(b'previous patch resources')
            start('1.14.1-patch.52')

    # Same-commit local patches must not reuse the previous patch's extracted resources.
    def test_patch_version_changes_resource_cache_key(self):
        code = """(let [method (.getDeclaredMethod com.defold.libs.ResourceUnpacker
                         "editorCacheKey" (make-array Class 0))
                       cache-key (fn [] (.invoke method nil (object-array 0)))]
                   (.setAccessible method true)
                   (System/setProperty "defold.editor.sha1" "same-commit")
                   (System/setProperty "defold.version" "1.14.1-patch.50")
                   (let [before (cache-key)]
                     (System/setProperty "defold.version" "1.14.1-patch.51")
                     (assert (not= before (cache-key)))
                     (assert (.contains (cache-key) "patch.51")))
                   (System/clearProperty "defold.version")
                   (assert (= "same-commit" (cache-key))))"""
        result = subprocess.run(
            [str(Path(os.environ['JAVA_HOME']) / 'bin/java.exe'),
             '-cp', os.environ['DEFOLD_PATCH_TEST_JAR'], 'clojure.main', '-e', code],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
            encoding='utf-8', errors='replace', timeout=60)
        self.assertEqual(0, result.returncode, result.stdout)

    # Native DAP needs no legacy module; older editors must include every Lua dependency.
    def test_packaged_editor_debugger_resources(self):
        with zipfile.ZipFile(os.environ['DEFOLD_PATCH_TEST_JAR']) as editor:
            start = editor.read('_unpack/_defold/debugger/start.lua')
            if b'builtins.scripts.debugger' not in start:
                self.assertIn(b'debugger.start(', start)
                return
            with zipfile.ZipFile(io.BytesIO(editor.read('lib/builtins.zip'))) as builtins:
                for name in ('debugger', 'mobdebug', 'edn', 'socket'):
                    path = f'builtins/scripts/{name}.lua'
                    self.assertEqual(editor.read(path), builtins.read(path), path)

    # Guards against generated engine protocols shadowing the archived Bob compiler's ABI.
    def test_packaged_editor_compiles_default_font_material(self):
        java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
        with tempfile.TemporaryDirectory(prefix='editor-font-pipeline-') as directory:
            root = Path(directory)
            (root / 'game.project').write_text(
                '[project]\ntitle = Font pipeline regression\n'
                '[bootstrap]\nmain_collection = /main.collectionc\n'
                '[input]\ngame_binding = /game.input_bindingc\n')
            (root / 'game.input_binding').write_text('')
            (root / 'main.collection').write_text(
                'name: "main"\nembedded_instances {\n'
                'id: "gui"\ndata: "components { id: \\"gui\\" component: \\"/main.gui\\" }\\n'
                'components { id: \\"script\\" component: \\"/main.script\\" }\\n"\n'
                '}\n')
            with zipfile.ZipFile(os.environ['DEFOLD_PATCH_TEST_JAR']) as editor:
                (root / 'debugger_start.lua').write_bytes(
                    editor.read('_unpack/_defold/debugger/start.lua'))
            (root / 'main.script').write_text("require 'debugger_start'\n")
            (root / 'main.gui').write_text(
                'fonts { name: "default" font: "/builtins/fonts/default.font" }\n'
                'material: "/builtins/materials/gui.material"\n'
                'nodes { type: TYPE_TEXT id: "text" text: "Font regression" font: "default" }\n')
            result = subprocess.run(
                [str(java), '--enable-native-access=ALL-UNNAMED',
                 f'-Djdk.net.unixdomain.tmpdir={Path.cwd()}',
                 '-cp', os.environ['DEFOLD_PATCH_TEST_JAR'], 'com.dynamo.bob.Bob',
                 '--root', str(root), '--platform', 'x86_64-win32',
                 '--debug-output-hlsl', 'true', 'build'],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                encoding='utf-8', errors='replace', timeout=180)
            self.assertEqual(0, result.returncode, result.stdout)
            self.assertTrue((root / 'build/default/builtins/fonts/font-df.materialc').is_file(),
                            result.stdout)
            self.assertTrue((root / 'build/default/debugger_start.luac').is_file(),
                            result.stdout)


if __name__ == '__main__':
    unittest.main()
