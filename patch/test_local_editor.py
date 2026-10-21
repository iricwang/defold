"""Regression coverage for the local Windows packaging entry point."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('local_editor', ROOT / 'scripts/windows/editor.py')
editor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(editor)


class LocalEditorTest(unittest.TestCase):
    # Equal revisions do not imply equal local/CI bytes; reject an incompatible installation.
    def test_exact_baseline_required(self):
        files = [{'path': 'Defold.exe', 'sha256': 'a'}]
        current = {'platform': 'x86_64-win32', 'metadata': {'revision': 55}, 'files': files}
        plan = {'base_revision': 55, 'base': copy.deepcopy(files), 'target': files}
        editor.validate_patch(plan, current)
        plan['base'][0]['sha256'] = 'b'
        with self.assertRaisesRegex(ValueError, 'exact patch baseline'):
            editor.validate_patch(plan, current)
        plan['base_revision'] = 54
        with self.assertRaisesRegex(ValueError, 'revision'):
            editor.validate_patch(plan, current)

    # A full portable export must contain the runtime but never mutable caches or user files.
    def test_full_export_contains_only_application_inventory(self):
        (ROOT / 'tmp').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=ROOT / 'tmp') as directory:
            root = Path(directory)
            app = root / 'app'
            (app / 'packages/jdk-25/bin').mkdir(parents=True)
            (app / 'packages/jdk-25/bin/java.exe').write_bytes(b'runtime')
            (app / 'packages/jdk-25/release').write_text('MODULES="java.base"')
            (app / 'config').write_text('editor_sha1 = abc\n')
            (app / 'Defold.exe').write_bytes(b'launcher')
            (app / 'private.txt').write_text('not part of app')
            (app / 'unpack').mkdir()
            (app / 'unpack/cache').write_text('cache')
            with zipfile.ZipFile(app / 'packages/defold-abc.jar', 'w') as jar:
                jar.writestr('patch/build.json', json.dumps({'revision': 60}))
            output = root / 'full.zip'
            state = editor.export_installed(app, output)
            with zipfile.ZipFile(output) as archive:
                self.assertEqual({'Defold/' + item['path'] for item in state['files']}, set(archive.namelist()))
                self.assertEqual(b'runtime', archive.read('Defold/packages/jdk-25/bin/java.exe'))
            with self.assertRaisesRegex(ValueError, 'outside'):
                editor.export_installed(app, app / 'recursive.zip')

    # Installation remains explicit; merely building a patch cannot close or replace an editor.
    def test_install_is_opt_in(self):
        args = editor.parser().parse_args(['patch', '--app', 'Defold'])
        self.assertFalse(args.install)
        self.assertEqual('full', editor.parser().parse_args(['full']).command)
