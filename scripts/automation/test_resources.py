"""Project path isolation and generated runtime preview entry regressions."""
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

from resources import prepare, resolve, search, resolve_compiled, override_bootstrap
from resources import native_library_archive


class ResourcesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parents[2] / 'tmp', prefix='resource-test-')
        self.addCleanup(self.tmp.cleanup)
        self.project = Path(self.tmp.name) / 'project'
        self.project.mkdir()
        (self.project / 'main.gui').write_text('script: ""\n')
        (self.project / 'main.go').write_text('')
        (self.project / 'main.collection').write_text('name: "main"\n')

    # Resource references must never escape the registered project or interpret a drive/UNC/path traversal.
    def test_invalid_resource_paths(self):
        for path in ('../main.gui', '/../main.gui', '/a/../main.gui', '//host/main.gui',
                     '/main.gui\n', '/C:/main.gui', '/main.gui:stream', '/a\\main.gui',
                     '/./main.gui', '/main.script', '/missing.gui', '/a//main.gui'):
            with self.subTest(path=path), self.assertRaises(ValueError): resolve(self.project, path)
        self.assertEqual(self.project / 'main.gui', resolve(self.project, '/main.gui'))

    # Search ordering/pagination must be deterministic and must not expose generated build resources.
    def test_search_filters_and_pagination(self):
        (self.project / 'build').mkdir()
        (self.project / 'build/hidden.gui').write_text('')
        first = search(self.project, limit=1)
        second = search(self.project, offset=1, limit=1)
        self.assertEqual(3, first['total'])
        self.assertEqual(1, first['next_offset'])
        self.assertNotEqual(first['resources'][0]['path'], second['resources'][0]['path'])
        self.assertEqual(['/main.gui'], [x['path'] for x in search(self.project, types=['gui'])['resources']])

    # Preview uses an external settings override, preserves source bytes and avoids overwriting a reserved-name collision.
    def test_generated_wrapper_preserves_sources(self):
        source = {p.name: p.read_bytes() for p in self.project.iterdir()}
        settings = Path(self.tmp.name) / 'preview.settings'
        preview, generated = prepare(self.project, '/main.gui', settings)
        first_wrapper = generated[0]
        self.assertIn('component: \\\"/main.gui\\\"', first_wrapper.read_text())
        _, next_generated = prepare(self.project, '/main.gui', settings)
        self.assertNotEqual(first_wrapper, next_generated[0])
        self.assertEqual('runtime_isolated_resource', preview['mode'])
        for name, contents in source.items(): self.assertEqual(contents, (self.project / name).read_bytes())
        self.assertNotIn(settings, self.project.iterdir())

    # A Collection is booted directly, retaining its native hierarchy; a GO is wrapped via its original prototype.
    def test_collection_and_go_bootstrap(self):
        settings = Path(self.tmp.name) / 'preview.settings'
        preview, generated = prepare(self.project, '/main.collection', settings)
        self.assertEqual('/main.collectionc', preview['bootstrap'])
        self.assertEqual([settings], generated)
        preview, generated = prepare(self.project, '/main.go', settings)
        self.assertIn('prototype: "/main.go"', generated[0].read_text())
        self.assertEqual('go', preview['resource_type'])

    # Native-extension resource previews fail before any compiler process could contact a build service.
    def test_native_preview_fails_before_compiler(self):
        from core import AutomationError, Service
        import threading
        (self.project / 'game.project').write_text('[project]\ntitle = test\n')
        (self.project / 'native').mkdir()
        (self.project / 'native/ext.manifest').write_text('name: Example\n')
        service = Service({'engine': 'unused-engine'}, [self.project], Path(self.tmp.name) / 'artifacts')
        try:
            with patch('core.subprocess.Popen') as process, self.assertRaises(AutomationError) as failure:
                service.build(next(iter(service.projects)), {'cancel': threading.Event()}, '/main.gui')
            self.assertEqual('UNSUPPORTED_CAPABILITY', failure.exception.code)
            process.assert_not_called()
        finally:
            service.close()

    # Cached zipped libraries are also native dependencies; looking only for loose ext.manifest files misses them.
    def test_zipped_native_dependency(self):
        import zipfile
        path = self.project / 'library.zip'
        with zipfile.ZipFile(path, 'w') as archive:
            archive.writestr('repo/spine/ext.manifest', 'name: spine\n')
        self.assertTrue(native_library_archive(path, Path('.internal/lib/library.zip')))
        self.assertFalse(native_library_archive(path, Path('assets/library.zip')))

    # Compiled previews do not require current source files, but reject missing output and traversal before Java.
    def test_compiled_resource_resolution(self):
        compiled = self.project / 'build/default'
        compiled.mkdir(parents=True)
        (compiled / 'old.guic').write_bytes(b'compiled')
        self.assertEqual(compiled / 'old.guic', resolve_compiled(compiled, '/old.gui'))
        for path in ('/../old.gui', '/missing.gui', '/old.guic', '//old.gui', '/x/../old.gui'):
            with self.subTest(path=path), self.assertRaises(ValueError): resolve_compiled(compiled, path)

    # Override only bootstrap, preserving render/input and percent-bearing user configuration without interpolation.
    def test_compiled_config_override(self):
        path = self.project / 'game.projectc'
        for initial in ('[bootstrap]\nmain_collection = /old.collectionc\nrender = /render.renderc\n',
                        '[bootstrap]\nrender = /render.renderc\n', '[display]\nwidth = 640\n'):
            path.write_text(initial + '[project]\ntitle = 100% QA\n')
            override_bootstrap(path, '/new.collectionc')
            result = path.read_text()
            self.assertEqual(1, result.count('main_collection = /new.collectionc'))
            self.assertIn('title = 100% QA', result)
            if 'render' in initial: self.assertIn('render = /render.renderc', result)
            override_bootstrap(path, '/second.collectionc')
            self.assertNotIn('/new.collectionc', path.read_text())
        path.write_text('[bootstrap]')
        override_bootstrap(path, '/new.collectionc')
        self.assertEqual('[bootstrap]\nmain_collection = /new.collectionc\n', path.read_text())


if __name__ == '__main__':
    unittest.main()
