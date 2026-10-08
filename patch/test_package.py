import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from unittest.mock import patch

import manage
import package


class PackageTest(unittest.TestCase):
    # A patch is built from inventory plus changed files, without reading/copying the runtime or creating an app.
    def test_inventory_patch_roundtrip(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            metadata = {'revision': 4, 'version': 'patch.4', 'channel': 'dev', 'repository': 'owner/repo'}
            jar = root / 'old.jar'
            with zipfile.ZipFile(jar, 'w') as archive:
                archive.writestr('patch/build.json', json.dumps(metadata))
            baseline = root / 'base.zip'
            runtime = 'Contents/Resources/packages/jdk-25'
            with zipfile.ZipFile(baseline, 'w') as archive:
                archive.writestr('Defold.app/Contents/Resources/config', 'old')
                archive.writestr('Defold.app/Contents/MacOS/Defold', 'launcher')
                archive.writestr(f'Defold.app/{runtime}/release', 'MODULES="java.base java.desktop"')
                archive.writestr(f'Defold.app/{runtime}/bin/java', 'unchanged runtime')
                archive.write(jar, 'Defold.app/Contents/Resources/packages/defold-old.jar')
            base = package.bootstrap(baseline, 'arm64-macos')
            baseline.unlink()  # Subsequent builds must work with just the JSON inventory.
            payload = root / 'payload'
            jars = payload / 'Contents/Resources/packages'
            jars.mkdir(parents=True)
            (payload / 'Contents/MacOS').mkdir()
            (payload / 'Contents/MacOS/Defold').write_text('launcher')
            (payload / 'Contents/Resources/config').write_text('new')
            metadata = {**metadata, 'revision': 5, 'version': 'patch.5'}
            with zipfile.ZipFile(jars / 'defold-new.jar', 'w') as archive:
                archive.writestr('patch/build.json', json.dumps(metadata))
            state = package.create(base, payload, metadata, 'arm64-macos', '25', '--add-modules=java.base', root / 'patch.zip')
            with zipfile.ZipFile(root / 'patch.zip') as archive:
                plan = json.loads(archive.read('patch.json'))
                self.assertEqual(plan['target'], state['files'])
                self.assertFalse(any('/jdk-' in name for name in archive.namelist()))
                self.assertNotIn('Contents/Resources/packages/defold-old.jar', {f['path'] for f in plan['target']})
                unchanged = [f for f in plan['base'] if '/jdk-' in f['path']]
                self.assertEqual(unchanged, [f for f in plan['target'] if '/jdk-' in f['path']])
            for version, modules in [('26', '--add-modules=java.base'), ('25', '--add-modules=java.sql')]:
                with self.assertRaisesRegex(ValueError, 'JDK changed'):
                    package.create(base, payload, metadata, 'arm64-macos', version, modules, root / 'bad.zip')
            with self.assertRaisesRegex(ValueError, 'newer revision'):
                package.create(state, payload, metadata, 'arm64-macos', '25', '', root / 'bad.zip')

    # Published assets are exclusively deltas, small inventories and a linked manifest, never app ZIPs or DMGs.
    def test_publish_patch_only(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data = {'schema': 1, 'revision': 5, 'version': 'patch.5', 'channel': 'dev', 'repository': 'owner/repo', 'notes': 'Patch only'}
            calls = []
            for platform in manage.PLATFORMS:
                files = [{'path': 'Contents/Resources/config', 'sha256': 'a' * 64, 'kind': 'file', 'mode': 420}]
                manage.write_json(root / f'Defold-{platform}-state.json', {'platform': platform, 'metadata': {**data, 'commit': 'new'}, 'files': files})
                with zipfile.ZipFile(root / f'Defold-{platform}-patch-4-to-5.zip', 'w') as archive:
                    archive.writestr('patch.json', json.dumps({'base_revision': 4, 'revision': 5, 'target': files}))
            def gh(*args):
                calls.append(args)
                if args[0] == 'api':
                    return json.dumps([[{'tag_name': 'patch-dev-4', 'draft': False}, {'tag_name': 'patch-dev', 'draft': False}]])
                return ''
            with patch.object(manage, 'channel', return_value=data), patch.object(manage, 'gh', side_effect=gh):
                manage.publish(root, 'new')
            manifest = json.loads((root / 'manifest.json').read_text())
            self.assertEqual({}, manifest['installers'])
            self.assertTrue(manifest['previous'].endswith('/patch-dev-4/manifest.json'))
            upload = next(c for c in calls if c[:3] == ('release', 'upload', 'patch-dev-5'))
            self.assertFalse(any(str(a).endswith('.dmg') or str(a).endswith('-macos.zip') for a in upload))
            self.assertEqual(set(manifest['states']), set(manage.PLATFORMS))

    # Later builds download only the published inventory, checking its hash before trusting it.
    def test_prepare_baseline_without_app_download(self):
        import hashlib
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            data = {'schema': 1, 'revision': 6, 'version': 'patch.6', 'channel': 'dev', 'repository': 'owner/repo', 'notes': 'Next'}
            metadata = {**data, 'revision': 5, 'version': 'patch.5', 'commit': 'previous'}
            state_bytes = json.dumps({'platform': 'arm64-macos', 'metadata': metadata}).encode()
            manifest = {**metadata, 'states': {'arm64-macos': {'size': len(state_bytes), 'sha256': hashlib.sha256(state_bytes).hexdigest()}}}
            downloads = []
            def gh(*args):
                if args[0] == 'api':
                    return json.dumps([[{'tag_name': 'patch-dev-5', 'draft': False}]])
                name = args[args.index('--pattern') + 1]
                downloads.append(name)
                if name == 'manifest.json':
                    (root / name).write_text(json.dumps(manifest))
                else:
                    (root / name).write_bytes(state_bytes)
                return ''
            with patch.object(manage, 'channel', return_value=data), patch.object(manage, 'gh', side_effect=gh):
                manage.prepare_base(root, 'arm64-macos')
                self.assertEqual(['manifest.json', 'Defold-arm64-macos-state.json'], downloads)
                manifest['states']['arm64-macos']['sha256'] = '0' * 64
                with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                    manage.prepare_base(root, 'arm64-macos')


if __name__ == '__main__':
    unittest.main()
