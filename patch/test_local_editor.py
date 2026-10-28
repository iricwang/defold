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

    # Local native changes must use a complete, matching engine/Bob set.
    def test_local_engine_requires_matching_bob(self):
        builder = editor.builder()
        with tempfile.TemporaryDirectory(dir=ROOT / 'tmp') as directory:
            home = Path(directory)
            with self.assertRaisesRegex(ValueError, 'Incomplete local engine'):
                builder.validate_engine_home(home)
            for name in ('share/ref-doc.zip', 'bin/x86_64-win32/dmengine.exe',
                         'bin/x86_64-win32/dmengine.pdb', 'bin/x86_64-win32/launcher.exe',
                         'lib/x86_64-win32/particle_shared.dll',
                         'lib/x86_64-win32/mouse_capture_shared.dll'):
                file = home / name
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(b'local')
            (home / 'share/java').mkdir()
            revision = b'a' * 40
            engine_file = home / 'bin/x86_64-win32/dmengine.exe'
            engine_file.write_bytes(b'local' + revision + b'\0')
            for engine, valid in ((b'archived', False), (engine_file.read_bytes(), True)):
                with zipfile.ZipFile(home / 'share/java/bob.jar', 'w') as jar:
                    jar.writestr('libexec/x86_64-win32/dmengine.exe', engine)
                    jar.writestr('com/dynamo/bob/archive/EngineVersion.class', b'\x01\x00\x28' + revision)
                if valid:
                    self.assertEqual(revision.decode(), builder.validate_engine_home(home))
                else:
                    with self.assertRaisesRegex(ValueError, 'rebuild Bob'):
                        builder.validate_engine_home(home)
            args = editor.parser().parse_args(['patch', '--app', 'Defold', '--engine-home', directory])
            self.assertEqual(home, args.engine_home)

    # Reject mismatched version metadata even when the packaged executable bytes match.
    def test_local_engine_rejects_wrong_sdk_revision(self):
        builder = editor.builder()
        with tempfile.TemporaryDirectory(dir=ROOT / 'tmp') as directory:
            home = Path(directory)
            for name in ('share/ref-doc.zip', 'bin/x86_64-win32/dmengine.exe',
                         'bin/x86_64-win32/dmengine.pdb', 'bin/x86_64-win32/launcher.exe',
                         'lib/x86_64-win32/particle_shared.dll', 'lib/x86_64-win32/mouse_capture_shared.dll'):
                path = home / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'engine' + b'a' * 40 + b'\0')
            (home / 'share/java').mkdir()
            for version in (b'unknown', b'\x01\x00\x28' + b'b' * 40):
                with zipfile.ZipFile(home / 'share/java/bob.jar', 'w') as jar:
                    jar.writestr('libexec/x86_64-win32/dmengine.exe', (home / 'bin/x86_64-win32/dmengine.exe').read_bytes())
                    jar.writestr('com/dynamo/bob/archive/EngineVersion.class', version)
                with self.assertRaisesRegex(ValueError, 'revision'):
                    builder.validate_engine_home(home)
