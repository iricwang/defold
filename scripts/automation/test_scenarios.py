"""Scenario contracts and cleanup regressions without an engine."""
import copy
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import Mock

from server import ROOT, SCENARIO
from core import AutomationError
from scenarios import mapped_action, run, validate


class ScenarioTest(unittest.TestCase):
    def setUp(self):
        self.spec = json.loads((ROOT / 'scripts/automation/fixture/scenario.json').read_text())

    # Scenario files reject arbitrary execution, forward references, ambiguous names, and unavailable cursors before launch.
    def test_preflight_validation(self):
        validate(self.spec, SCENARIO)
        invalid = []
        for step in ({'kind': 'exec', 'code': 'anything'},
                     {'kind': 'compare', 'before': 'missing', 'after': 'missing', 'expected': 'match'},
                     {'kind': 'wait_log', 'contains': 'x', 'after': 'last_input'}):
            spec = copy.deepcopy(self.spec); spec['steps'] = [step]; invalid.append(spec)
        spec = copy.deepcopy(self.spec); spec['steps'] = [{'kind': 'capture', 'name': 'same'}] * 2; invalid.append(spec)
        spec = copy.deepcopy(self.spec); spec['devices'] *= 4; invalid.append(spec)
        spec = copy.deepcopy(self.spec); spec['devices'][0]['device']['top'] = 480; invalid.append(spec)
        spec = copy.deepcopy(self.spec); spec['steps'][3]['action']['x'] = float('nan'); invalid.append(spec)
        for spec in invalid:
            with self.subTest(spec=spec), self.assertRaises(Exception): validate(spec, SCENARIO)

    # Normalized endpoints include the final pixel without producing an out-of-frame width/height coordinate.
    def test_normalized_coordinates(self):
        action = {'type': 'drag', 'x': 0, 'y': 0.5, 'to_x': 1, 'to_y': 1}
        result = mapped_action(action, 'frame_normalized', {'width': 1170, 'height': 2532})
        self.assertEqual((0, 1266, 1169, 2531), (result['x'], result['y'], result['to_x'], result['to_y']))
        self.assertEqual(1, action['to_x'])

    def service(self, directory):
        service = Mock()
        service.root = Path(directory)
        service.builds = {'b': {'build_id': 'b'}}
        service.artifacts = {}
        service.runs = {}
        service.start.side_effect = [{'session_id': 's1', 'lease_id': 'l', 'pid': 1},
                                     {'session_id': 's2', 'lease_id': 'l', 'pid': 2}]
        service.capture.return_value = {'artifact_id': 'image'}
        service.logs.return_value = {'entries': [], 'cursor': 0}
        service.report.return_value = {'report_path': str(Path(directory) / 'session.html')}
        return service

    # A failed assertion records evidence, closes its owned process, and does not suppress later device cases.
    def test_failure_continues_and_cleans_up(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'tmp') as directory:
            service = self.service(directory)
            service.scene.side_effect = [{'matched': 0}, {'matched': 1}]
            spec = {**self.spec, 'devices': self.spec['devices'][:2],
                    'steps': [{'kind': 'scene_assert', 'selector': {'id': 'button'}}]}
            result = run(service, 'b', spec, {'cancel': threading.Event()})
            self.assertEqual(['failed', 'passed'], [case['state'] for case in result['cases']])
            self.assertEqual(['s1', 's2'], [call.args[0] for call in service.stop.call_args_list])
            self.assertIn('failure_capture', result['cases'][0])
            self.assertTrue(Path(result['report_path']).is_file())

    # Cancellation during a wait still writes diagnostics, closes the current process and skips remaining devices.
    def test_cancel_wait_cleans_up(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'tmp') as directory:
            service = self.service(directory)
            cancel = threading.Event()
            def cancelled(*args, **kwargs):
                cancel.set()
                raise AutomationError('CANCELLED', 'test')
            service.wait_log.side_effect = cancelled
            spec = {**self.spec, 'devices': self.spec['devices'][:2], 'steps': [self.spec['steps'][0]]}
            result = run(service, 'b', spec, {'cancel': cancel})
            self.assertEqual('cancelled', result['state'])
            self.assertEqual(['cancelled', 'skipped'], [case['state'] for case in result['cases']])
            service.stop.assert_called_once_with('s1')
            self.assertTrue(Path(result['json_path']).is_file())


if __name__ == '__main__':
    unittest.main()
