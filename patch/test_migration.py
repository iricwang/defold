import hashlib
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import Mock, patch

import migrate_local22 as migration


class MigrationTest(unittest.TestCase):
    # Only the intended bundle's launcher/JVM blocks migration; another installation stays independent.
    def test_running_installation_is_detected(self):
        app = Path('/Applications/Defold.app')
        listing = (f'12 {app / "Contents/MacOS/Defold"}\n'
                   f'13 java -cp {app / "Contents/Resources/packages/editor.jar"}\n'
                   f'14 {Path("/tmp/QA/Defold.app/Contents/MacOS/Defold")}\n')
        self.assertEqual(['12', '13'], migration.running_pids(app, listing))

    # A locally modified binary or wrong permissions must never be sent to the one-time migration.
    def test_exact_baseline_is_required(self):
        with tempfile.TemporaryDirectory() as directory:
            app = Path(directory)
            file = app / 'launcher'
            file.write_bytes(b'local')
            file.chmod(0o755)
            baseline = {'files': [{'path': 'launcher', 'mode': stat.S_IMODE(file.stat().st_mode), 'sha256': hashlib.sha256(b'local').hexdigest()}]}
            migration.validate_app(app, baseline)
            file.write_bytes(b'modified')
            with self.assertRaisesRegex(ValueError, 'baseline'):
                migration.validate_app(app, baseline)

    # The replacement process inherits a valid configuration which returns to the normal GitHub feed after exit.
    def test_regular_feed_is_restored_after_launcher_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app = root / 'Defold.app'
            app.mkdir()
            config_dir = root / 'feed'
            config_dir.mkdir()
            baseline = root / 'baseline.json'
            baseline.write_text(json.dumps({'files': [], 'metadata': {'repository': 'owner/repo', 'channel': 'dev'}, 'migration_target_revision': 31}))
            def launch(command, env):
                self.assertEqual([str(app.resolve() / 'Contents/MacOS/Defold')], command)
                self.assertIn(str(config_dir), env['JDK_JAVA_OPTIONS'])
                self.assertIn('patch-dev-31/manifest-arm64-macos-local22.json', (config_dir / 'build.json').read_text())
                return Mock(wait=Mock(return_value=0))
            with patch.object(migration, 'BASELINE', baseline), patch.object(migration.subprocess, 'check_output', return_value=''), \
                 patch.object(migration.tempfile, 'mkdtemp', return_value=str(config_dir)), patch.object(migration.subprocess, 'Popen', side_effect=launch):
                self.assertEqual(0, migration.main(['--app', str(app)]))
            self.assertEqual('https://github.com/owner/repo/releases/download/patch-dev/manifest.json', json.loads((config_dir / 'build.json').read_text())['feed'])


if __name__ == '__main__':
    unittest.main()
