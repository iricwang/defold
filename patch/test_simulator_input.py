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
"""Local real-engine Simulator input regression; no desktop focus or source upload.

Requires JAVA_HOME (JDK), DEFOLD_PATCH_TEST_JAR and DEFOLD_SIMULATOR_TEST_ENGINE
(dmengine.exe with its matching PDB). Builds the native hook using the shared
Windows pipeline, then runs GUI picking, drag/release, wheel and keyboard tests.
Set DEFOLD_SIMULATOR_TEST_PACKAGED=1 to test the JAR's Java/native bridge instead
of locally rebuilt bridge sources, using a fresh unpack directory and JVMs.
"""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


@unittest.skipUnless(os.name == 'nt' and os.environ.get('DEFOLD_PATCH_TEST_JAR')
                     and os.environ.get('DEFOLD_SIMULATOR_TEST_ENGINE'),
                     'Windows editor JAR and engine with matching PDB required')
class SimulatorInputTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = Path(__file__).resolve().parents[1]
        cls.temporary = tempfile.TemporaryDirectory(prefix='simulator-input-', dir=cls.root / 'tmp')
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.work = Path(cls.temporary.name)
        cls.project = cls.work / 'project'
        cls.project.mkdir()
        cls.jar = os.environ['DEFOLD_PATCH_TEST_JAR']
        cls.packaged = os.environ.get('DEFOLD_SIMULATOR_TEST_PACKAGED') == '1'
        cls.java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
        cls.engine = str(Path(os.environ['DEFOLD_SIMULATOR_TEST_ENGINE']).resolve())
        (cls.project / 'game.project').write_text(
            '[project]\ntitle = Simulator input regression\n'
            '[bootstrap]\nmain_collection = /main.collectionc\n'
            '[display]\nwidth = 320\nheight = 240\n'
            '[engine]\nrun_while_iconified = 1\n'
            '[input]\ngame_binding = /game.input_bindingc\n')
        (cls.project / 'game.input_binding').write_text(
            'mouse_trigger { input: MOUSE_BUTTON_1 action: "click" }\n'
            'mouse_trigger { input: MOUSE_BUTTON_2 action: "right" }\n'
            'mouse_trigger { input: MOUSE_BUTTON_3 action: "middle" }\n'
            'mouse_trigger { input: MOUSE_WHEEL_UP action: "wheel" }\n'
            'key_trigger { input: KEY_SPACE action: "key" }\n'
            'text_trigger { input: TEXT action: "text" }\n')
        (cls.project / 'main.collection').write_text(
            'name: "main"\nembedded_instances { id: "gui" '
            'data: "components { id: \\"gui\\" component: \\"/main.gui\\" }\\n" }\n')
        (cls.project / 'main.gui').write_text(
            'script: "/main.gui_script"\nmaterial: "/builtins/materials/gui.material"\n')
        (cls.project / 'main.gui_script').write_text('''function init(self)
    msg.post(".", "acquire_input_focus")
    self.button = gui.new_box_node(vmath.vector3(160, 120, 0), vmath.vector3(120, 100, 0))
    print("INPUT_READY")
end
function on_input(self, id, action)
    local name = id == hash("click") and "click" or id == hash("right") and "right"
        or id == hash("middle") and "middle" or id == hash("wheel") and "wheel"
        or id == hash("key") and "key" or id == hash("text") and "text"
    if not name then return end
    local state = action.pressed and "down" or action.released and "up" or "held"
    local line = string.format("INPUT %s %s %.0f %.0f hit=%s text=%s", name, state,
        action.screen_x or -1, action.screen_y or -1,
        tostring(gui.pick_node(self.button, action.x or 0, action.y or 0)), action.text or "")
    if line ~= self.last then print(line); self.last = line end
end
''')
        cls.run_command([str(cls.java), '--enable-native-access=ALL-UNNAMED', '-cp', cls.jar,
                         'com.dynamo.bob.Bob', '--root', str(cls.project), '--platform', 'x86_64-win32', 'build'])
        cls.classes = cls.work / 'classes'
        cls.classes.mkdir()
        sources = [str(cls.root / 'patch/SimulatorInputProbe.java')]
        if not cls.packaged:
            sources.append(str(cls.root / 'editor/src/java/com/defold/editor/WindowsGameWindow.java'))
        cls.run_command([str(cls.java.with_name('javac.exe')), '-cp', cls.jar, '-d', str(cls.classes), *sources])
        if not cls.packaged:
            spec = importlib.util.spec_from_file_location('windows_editor', cls.root / 'scripts/windows/editor.py')
            pipeline = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(pipeline)
            pipeline.builder().build_windows_native(cls.root / 'scripts/windows/game-window-hook.c',
                                                   cls.work / 'native/x86_64-win32/lib/game_window_hook.dll')

    @staticmethod
    def run_command(command):
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                text=True, encoding='utf-8', errors='replace', timeout=120,
                                creationflags=subprocess.CREATE_NO_WINDOW)
        if result.returncode:
            raise AssertionError(result.stdout)
        return result.stdout

    # Real GUI picking must use virtual client coordinates, including dragging and release, in both orientations.
    def test_hidden_game_receives_simulated_input(self):
        for width, height in ((640, 480), (1170, 2532), (2532, 1170)):
            with self.subTest(width=width, height=height):
                output = self.run_command([
                    str(self.java), '--enable-native-access=ALL-UNNAMED',
                    '-Ddefold.editor.sha1=simulator-input-regression',
                    '-Ddefold.unpack.path=' + str(self.work / 'unpack'),
                    '-cp', str(self.classes) + os.pathsep + self.jar,
                    'com.defold.editor.SimulatorInputProbe', self.engine,
                    str(self.project), '-' if self.packaged else str(self.work / 'native'), str(width), str(height)])
                self.assertIn(f'SIMULATOR_INPUT_OK {width}x{height}', output)


if __name__ == '__main__':
    unittest.main()
