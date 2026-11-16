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

"""Isolation and transport regressions for the background UI runner."""
import io
from pathlib import Path
import tempfile
import unittest

import headless_editor as runner


class HeadlessRunnerTest(unittest.TestCase):
    # Unicode paths must survive the same bencode transport used by the live REPL.
    def test_unicode_repl_message(self):
        message = {'op': 'eval', 'code': '(println "动画/尾巴")', 'id': 'qa'}
        self.assertEqual(message, runner.decode(io.BufferedReader(io.BytesIO(runner.encode(message)))))
        self.assertEqual({'status': ['done']}, runner.decode(io.BufferedReader(io.BytesIO(b'd6:statusl4:doneee'))))

    # A closed socket must fail rather than spin forever while waiting for a delimiter.
    def test_truncated_reply(self):
        for value in [b'', b'12', b'3:a', b'i123']:
            with self.subTest(value=value), self.assertRaises(EOFError):
                runner.decode(io.BufferedReader(io.BytesIO(value)))

    # QA writes belong to the copy; caches and symlinks to external resources stay out.
    def test_project_copy_isolation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'source'
            source.mkdir()
            (source / 'game.project').write_text('[project]\n')
            (source / 'asset.txt').write_text('original')
            (source / 'build').mkdir()
            (source / 'build/cache').write_text('cache')
            (source / 'external').symlink_to(root)
            copy = root / 'copy'
            runner.copy_project(source, copy)
            (copy / 'asset.txt').write_text('changed')
            self.assertEqual('original', (source / 'asset.txt').read_text())
            self.assertFalse((copy / 'build').exists())
            self.assertFalse((copy / 'external').exists())
            with self.assertRaises(ValueError):
                runner.copy_project(source, source / 'recursive')

    # Empty-project smoke tests need a valid bootstrap collection, not a build-error toast.
    def test_default_project(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory) / 'project'
            runner.copy_project(None, project)
            self.assertIn('/main.collection', (project / 'game.project').read_text())
            self.assertTrue((project / 'main.collection').is_file())


if __name__ == '__main__':
    unittest.main()
