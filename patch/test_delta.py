import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from unittest.mock import patch

import delta
import manage


class DeltaTest(unittest.TestCase):
    def write_bundle(self, path, entries):
        with zipfile.ZipFile(path, 'w') as archive:
            for name, value in entries.items():
                archive.writestr('Defold.app/' + name, value)

    # Verifies the daily update command advances and stages one matching version with release notes.
    def test_bump_stages_next_revision(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / 'patch/channel.json'
            data = {'schema': 1, 'revision': 2, 'channel': 'dev', 'repository': 'owner/repo', 'version': '1.14.1-patch.2', 'notes': ''}
            manage.write_json(path, data)
            with patch.object(manage, 'ROOT', root), patch('sys.argv', ['manage.py', 'bump', '--notes', 'Fix texture navigation']):
                manage.main()
            updated = json.loads(path.read_text())
            embedded = json.loads((root / 'editor/resources/patch/build.json').read_text())
            self.assertEqual(updated['revision'], 3)
            self.assertEqual(updated['version'], '1.14.1-patch.3')
            self.assertEqual(updated['notes'], 'Fix texture navigation')
            self.assertEqual({k: embedded[k] for k in updated}, updated)

    # Verifies true file deltas, guarding against silently shipping unchanged runtime files.
    def test_delta_only_contains_changed_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            before = {'Contents/Resources/config': 'old', 'Contents/MacOS/Defold': 'launcher',
                      'Contents/Resources/old.jar': 'obsolete', 'Contents/Resources/packages/jdk-25/bin/java': 'runtime'}
            after = {**before, 'Contents/Resources/config': 'new', 'Contents/Resources/new.jar': 'added'}
            del after['Contents/Resources/old.jar']
            self.write_bundle(root / 'base.zip', before)
            self.write_bundle(root / 'new.zip', after)
            delta.create(root / 'base.zip', root / 'new.zip', root / 'patch.zip', 1, 2)
            with zipfile.ZipFile(root / 'patch.zip') as archive:
                self.assertEqual(set(archive.namelist()), {'patch.json', 'files/Contents/Resources/config',
                                                          'files/Contents/Resources/new.jar'})
                plan = json.loads(archive.read('patch.json'))
                self.assertEqual(plan['base_revision'], 1)
                self.assertEqual(len(plan['target']), 4)
            after['Contents/Resources/packages/jdk-25/bin/java'] = 'different runtime'
            self.write_bundle(root / 'new.zip', after)
            with self.assertRaisesRegex(ValueError, 'JDK changed'):
                delta.create(root / 'base.zip', root / 'new.zip', root / 'patch.zip', 1, 2)

    # Verifies interrupted publications repair the feed without overwriting a released package.
    def test_published_version_reuses_manifest(self):
        data = {'schema': 1, 'revision': 1, 'channel': 'dev', 'repository': 'owner/repo', 'version': 'v1', 'notes': ''}
        calls = []
        def fake_gh(*args):
            calls.append(args)
            if args[0] == 'api':
                return json.dumps([[{'tag_name': 'patch-dev-1', 'draft': False}, {'tag_name': 'patch-dev', 'draft': False}]])
            if args[:2] == ('release', 'download'):
                (Path(args[args.index('--dir') + 1]) / 'manifest.json').write_text(json.dumps({'commit': 'commit'}))
            return ''
        with tempfile.TemporaryDirectory() as directory, patch.object(manage, 'channel', return_value=data), patch.object(manage, 'gh', side_effect=fake_gh):
            manage.publish(Path(directory), 'commit')
        self.assertTrue(any(c[:3] == ('release', 'download', 'patch-dev-1') for c in calls))
        self.assertFalse(any(c[:3] == ('release', 'upload', 'patch-dev-1') for c in calls))
        self.assertTrue(any(c[:3] == ('release', 'upload', 'patch-dev') for c in calls))

    # Verifies a new commit cannot silently reuse an old revision and leave clients without an update.
    def test_published_revision_rejects_changed_commit(self):
        data = {'schema': 1, 'revision': 1, 'channel': 'dev', 'repository': 'owner/repo', 'version': 'v1', 'notes': ''}
        def fake_gh(*args):
            if args[0] == 'api':
                return json.dumps([[{'tag_name': 'patch-dev-1', 'draft': False}]])
            if args[:2] == ('release', 'download'):
                (Path(args[args.index('--dir') + 1]) / 'manifest.json').write_text(json.dumps({'commit': 'old'}))
                return ''
            self.fail('A mismatched revision must not be uploaded')
        with tempfile.TemporaryDirectory() as directory, patch.object(manage, 'channel', return_value=data), patch.object(manage, 'gh', side_effect=fake_gh):
            with self.assertRaisesRegex(ValueError, 'another commit'):
                manage.publish(Path(directory), 'new')

    # Verifies network/auth errors abort publication rather than creating or moving a release.
    def test_api_error_aborts(self):
        with patch.object(manage, 'gh', side_effect=RuntimeError('offline')):
            with self.assertRaisesRegex(RuntimeError, 'offline'):
                manage.publish(Path('.'), 'commit')


if __name__ == '__main__':
    unittest.main()
