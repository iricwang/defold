"""Native Windows installer tests; DEFOLD_PATCH_TEST_JAR supplies Jackson dependencies."""
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import zipfile


@contextmanager
def temporary_directory():
    directory = tempfile.mkdtemp(prefix='windows-patch-fixture-')
    try:
        yield directory
    finally:
        deadline = time.monotonic() + 10
        while True:
            try:
                shutil.rmtree(directory)
                break
            except PermissionError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.05)


@unittest.skipUnless(os.name == 'nt' and os.environ.get('DEFOLD_PATCH_TEST_JAR'), 'Windows editor JAR required')
class WindowsPatchTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workspace = tempfile.TemporaryDirectory(prefix='windows-patch-tests-')
        cls.classes = Path(cls.workspace.name) / 'classes'
        cls.java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
        root = Path(__file__).resolve().parents[1]
        cls.cp = str(cls.classes) + os.pathsep + os.environ['DEFOLD_PATCH_TEST_JAR']
        sources = list((root / 'editor/src/java/com/defold/editor').glob('Patch*.java'))
        subprocess.run([str(cls.java.with_name('javac.exe')), '-cp', os.environ['DEFOLD_PATCH_TEST_JAR'],
                        '-d', str(cls.classes), str(root / 'patch/WindowsPatchHarness.java'), *map(str, sources)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.workspace.cleanup()

    def fixture(self, directory):
        app = Path(directory) / 'Defold with spaces'
        app.mkdir()
        (app / 'config').write_bytes(b'old')
        shutil.copyfile(Path(os.environ['SystemRoot']) / 'System32/cmd.exe', app / 'Defold.exe')
        (app / 'packages/jdk-test').mkdir(parents=True)
        (app / 'packages/jdk-test/runtime').write_bytes(b'unchanged')
        (app / 'unpack').mkdir()
        (app / 'unpack/cache').write_bytes(b'mutable')
        def item(name, body):
            return {'path': name, 'sha256': hashlib.sha256(body).hexdigest(), 'mode': 420, 'kind': 'file'}
        before = [item(p.relative_to(app).as_posix(), p.read_bytes()) for p in app.rglob('*') if p.is_file() and 'unpack' not in p.parts]
        after = [item('config', b'new') if i['path'] == 'config' else i for i in before]
        plan = {'schema': 1, 'base_revision': 8, 'revision': 10, 'base': before, 'target': after}
        archive = Path(directory) / 'patch.zip'
        self.write_patch(archive, plan)
        return app, archive, plan

    def write_patch(self, archive, plan):
        with zipfile.ZipFile(archive, 'w') as output:
            output.writestr('patch.json', json.dumps(plan))
            output.writestr('files/config', b'new')

    def prepare(self, archive, app):
        return subprocess.run([str(self.java), '-cp', self.cp, 'WindowsPatchHarness', 'prepare', str(archive), str(app)],
                              capture_output=True, text=True)

    # Installation on NTFS retains runtime files, drops the extraction cache and rejects incompatible or unsafe input.
    def test_prepare_and_validation(self):
        with temporary_directory() as directory:
            app, archive, plan = self.fixture(directory)
            result = self.prepare(archive, app)
            self.assertEqual(0, result.returncode, result.stderr)
            staged = Path(result.stdout.strip())
            self.assertEqual(b'new', (staged / 'config').read_bytes())
            self.assertEqual(b'old', (app / 'config').read_bytes())
            self.assertEqual(b'unchanged', (staged / 'packages/jdk-test/runtime').read_bytes())
            self.assertFalse((staged / 'unpack').exists())
            plan['target'][-1] = {**plan['target'][-1], 'sha256': '0' * 64}
            self.write_patch(archive, plan)
            self.assertNotEqual(0, self.prepare(archive, app).returncode)
            self.assertEqual(b'old', (app / 'config').read_bytes())
            plan['base'].append({'path': 'packages/../../escape', 'sha256': '0'*64, 'mode': 420, 'kind': 'file'})
            self.write_patch(archive, plan)
            self.assertNotEqual(0, self.prepare(archive, app).returncode)

    # A separate JVM waits for parent exit before swapping directories, then launches the Windows executable.
    def test_restart_waits_and_launches(self):
        with temporary_directory() as directory:
            app, archive, _ = self.fixture(directory)
            staged = self.prepare(archive, app)
            self.assertEqual(0, staged.returncode, staged.stderr)
            parent = subprocess.Popen([sys.executable, '-c', 'import sys; sys.stdin.buffer.read(1)'], stdin=subprocess.PIPE)
            helper = subprocess.Popen([str(self.java), '-cp', self.cp, 'com.defold.editor.PatchRestart', str(parent.pid),
                str(app), staged.stdout.strip(), str(Path(directory) / 'editor.log'), '/c', 'echo PATCH_RESTART_OK'],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                self.assertEqual('READY', helper.stdout.readline().strip())
                self.assertEqual(b'old', (app / 'config').read_bytes())
                parent.communicate(b'x', timeout=10)
                out, err = helper.communicate(timeout=20)
                self.assertEqual(0, helper.returncode, err)
                self.assertIn('STARTED', out)
                self.assertEqual(b'new', (app / 'config').read_bytes())
                backups = list(Path(directory).glob('*.patch-backup-*'))
                self.assertEqual(b'old', (backups[0] / 'config').read_bytes())
            finally:
                if parent.poll() is None: parent.kill(); parent.wait()
                if helper.poll() is None: helper.kill(); helper.wait()

    # Failed second rename restores the original directory rather than stranding the installation in its backup.
    def test_swap_rollback(self):
        with temporary_directory() as directory:
            app, _, _ = self.fixture(directory)
            result = subprocess.run([str(self.java), '-cp', self.cp, 'WindowsPatchHarness', 'swap', str(app),
                str(Path(directory) / 'missing'), str(Path(directory) / 'backup')], capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(b'old', (app / 'config').read_bytes())


if __name__ == '__main__':
    unittest.main()
