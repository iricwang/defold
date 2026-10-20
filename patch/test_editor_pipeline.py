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

    # Verifies the editor debugger and all of its Lua dependencies in both resource views.
    def test_packaged_editor_debugger_resources(self):
        with zipfile.ZipFile(os.environ['DEFOLD_PATCH_TEST_JAR']) as editor:
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
            self.assertTrue((root / 'build/default/builtins/scripts/debugger.luac').is_file(),
                            result.stdout)


if __name__ == '__main__':
    unittest.main()
