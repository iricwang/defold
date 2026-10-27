"""Persistent baseline identity, context isolation, corruption and scenario contracts."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing
import copy
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from server import ROOT, SCENARIO, artifact_content
from core import Service, AutomationError
from build import digest
from baselines import BaselineError, Store
from scenarios import validate
from PIL import Image


class BaselineTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=ROOT / 'tmp')
        self.root = Path(self.temporary.name)
        self.project = self.root / 'project'
        self.project.mkdir()
        (self.project / 'game.project').write_text('[project]\n')
        self.store = self.root / 'baseline-store'
        self.services = []
        self.service = self.make_service('first')
        self.pid = next(iter(self.service.projects))

    def tearDown(self):
        for service in self.services:
            service.close()
        self.temporary.cleanup()

    def make_service(self, name):
        service = Service({'engine': 'unused'}, [self.project], self.root / name, baseline_root=self.store)
        self.services.append(service)
        return service

    def frame(self, service, name='frame', changed=False):
        path = service.root / (name + '.png')
        image = Image.new('RGB', (64, 48), (20, 30, 40))
        if changed:
            image.putpixel((30, 20), (255, 30, 40))
        image.save(path)
        result = {'artifact_id': name, 'observation_id': name, 'session_id': 'session', 'source': 'runtime',
                  'artifact_kind': 'runtime_frame', 'project_id': self.pid, 'build_id': 'build-' + name,
                  'image_path': str(path), 'image_sha256': digest(path), 'width': 64, 'height': 48,
                  'device': {'width': 64, 'height': 48}, 'render_sequence': 3, 'input_sequence': 1,
                  'device_revision': 1, 'project_config': {}, 'gui_projection_profile': 'default_gui'}
        service.artifacts[name] = result
        return result

    # Restarted services can verify exact bytes and create reports without the old artifact/session index.
    def test_restart_compare_and_report(self):
        self.frame(self.service)
        saved = self.service.baseline_save(self.pid, 'Login / 竖屏', 'frame')
        self.service.close()
        restarted = self.make_service('restarted')
        self.frame(restarted, 'current')
        listed = restarted.baseline_list(self.pid, name='Login / 竖屏')
        self.assertEqual(saved['baseline_id'], listed['baselines'][0]['baseline_id'])
        old = restarted.baseline_get(self.pid, saved['baseline_id'])
        self.assertEqual('baseline', old['artifact_kind'])
        self.assertEqual(saved['image_sha256'], digest(old['image_path']))
        self.assertTrue(artifact_content(old).structuredContent['inline_preview'])
        result = restarted.baseline_compare(saved['baseline_id'], 'current', max_changed_ratio=0)
        self.assertEqual('match', result['verdict'])
        self.assertEqual(0, result['changed_pixels'])
        report = restarted.report('session')
        self.assertIn(Path(old['image_path']).name, Path(report['report_path']).read_text(encoding='utf-8'))
        self.assertTrue((Path(report['report_path']).parent / Path(old['image_path']).name).is_file())

    # Explicit revisions are append-only, so a failing comparison never replaces the accepted PNG.
    def test_revision_and_failed_comparison(self):
        self.frame(self.service)
        first = self.service.baseline_save(self.pid, 'login', 'frame')
        duplicate = self.service.baseline_save(self.pid, 'login', 'frame')
        self.assertTrue(duplicate['reused'])
        self.assertEqual(first['baseline_id'], duplicate['baseline_id'])
        self.frame(self.service, 'changed', True)
        result = self.service.baseline_compare(first['baseline_id'], 'changed', max_changed_ratio=0)
        self.assertEqual('different', result['verdict'])
        self.assertEqual(1, result['changed_pixels'])
        self.assertEqual(1, self.service.baseline_list(self.pid)['total'])
        second = self.service.baseline_save(self.pid, 'login', 'changed')
        self.assertNotEqual(first['baseline_id'], second['baseline_id'])
        self.assertEqual(2, self.service.baseline_list(self.pid)['total'])
        self.assertEqual(first['image_sha256'], self.service.baseline_get(self.pid, first['baseline_id'])['image_sha256'])
        page = self.service.baseline_list(self.pid, limit=1)
        self.assertEqual(1, page['next_offset'])

    # Project identity, dimensions, safe area, QA config and resource context must all match before comparing pixels.
    def test_context_and_project_guards(self):
        self.frame(self.service)
        saved = self.service.baseline_save(self.pid, 'login', 'frame')
        current = self.frame(self.service, 'current')
        variants = [{'device': {**current['device'], 'top': 1}}, {'project_config': {'project.qa': '1'}},
                    {'gui_projection_profile': 'custom'}, {'preview': {'resource_path': '/login.gui', 'resource_type': 'gui'}}]
        for changed in variants:
            self.service.artifacts['current'] = {**current, **changed}
            with self.subTest(changed=changed), self.assertRaises(AutomationError) as error:
                self.service.baseline_compare(saved['baseline_id'], 'current')
            self.assertEqual('BASELINE_CONTEXT_MISMATCH', error.exception.code)
        self.assertEqual(0, self.service.baselines.list('other-project')['total'])
        with self.assertRaises(BaselineError): self.service.baselines.load('other-project', saved['baseline_id'])
        with self.assertRaises(AutomationError): self.service.baseline_get('other-project', saved['baseline_id'])
        with self.assertRaises(BaselineError): self.service.baselines.load(self.pid, '../../file')

    # Historical/cropped/diff artifacts cannot become live input observations or be accepted as new baselines.
    def test_non_runtime_baseline_rejected(self):
        self.frame(self.service)
        saved = self.service.baseline_save(self.pid, 'login', 'frame')
        loaded = self.service.baseline_get(self.pid, saved['baseline_id'])
        with self.assertRaises(AutomationError): self.service.original(loaded['artifact_id'])
        with self.assertRaises(AutomationError): self.service.baseline_save(self.pid, 'copy', loaded['artifact_id'])
        cropped = self.service.crop('frame', {'x': 0, 'y': 0, 'width': 32, 'height': 32})
        with self.assertRaises(AutomationError): self.service.baseline_save(self.pid, 'crop', cropped['artifact_id'])

    # Modified capture bytes and corrupted persisted PNG/metadata are rejected before publishing evidence.
    def test_integrity(self):
        frame = self.frame(self.service)
        saved = self.service.baseline_save(self.pid, 'login', 'frame')
        Path(frame['image_path']).write_bytes(b'changed')
        with self.assertRaises(BaselineError): self.service.baseline_save(self.pid, 'bad', 'frame')
        with closing(sqlite3.connect(self.service.baselines.path)) as database, database:
            database.execute('UPDATE baselines SET png=? WHERE id=?', (b'corrupt', saved['baseline_id']))
        with self.assertRaises(BaselineError) as error:
            self.service.baseline_get(self.pid, saved['baseline_id'])
        self.assertEqual('BASELINE_CORRUPT', error.exception.code)

    # Atomic database publication avoids partial baselines and duplicate rows from concurrent services.
    def test_concurrent_publish_and_capacity(self):
        frame = self.frame(self.service)
        stores = [Store(self.store), Store(self.store)]
        with ThreadPoolExecutor(max_workers=2) as executor:
            results = list(executor.map(lambda store: store.save(self.pid, 'same', frame), stores))
        self.assertEqual(results[0]['baseline_id'], results[1]['baseline_id'])
        self.assertEqual(1, self.service.baseline_list(self.pid)['total'])
        with patch('baselines.MAX_RECORDS', 1), self.assertRaises(BaselineError) as error:
            self.service.baseline_save(self.pid, 'new-name', 'frame')
        self.assertEqual('BASELINE_LIMIT', error.exception.code)

    # Device mappings are explicit and complete; V1 cannot accidentally accept an unsupported new step.
    def test_scenario_mapping_preflight(self):
        spec = json.loads((ROOT / 'scripts/automation/fixture/scenario.json').read_text())
        spec['version'] = 2
        mapping = {device['name']: 'a' * 64 for device in spec['devices']}
        spec['steps'] = [{'kind': 'baseline_assert', 'baselines': mapping}]
        validate(spec, SCENARIO)
        bad = copy.deepcopy(spec)
        bad['steps'][0]['baselines'].pop(next(iter(mapping)))
        with self.assertRaises(AutomationError): validate(bad, SCENARIO)
        with self.assertRaises(Exception): validate({**spec, 'version': 1}, SCENARIO)


if __name__ == '__main__':
    unittest.main()
