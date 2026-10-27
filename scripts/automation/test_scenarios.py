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
from scenarios import json_equal, mapped_action, run, step_report, validate


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

    # V1 remains strict; semantic steps require V2 and explicit QA opt-in before any game starts.
    def test_semantic_preflight(self):
        step = {'kind': 'element_click_verified', 'selector': {'id': 'verify_button'}, 'event': 'clicked'}
        spec = {**self.spec, 'version': 2, 'app_channel': True, 'steps': [step]}
        validate(spec, SCENARIO)
        invalid = [{**spec, 'version': 1}, {**spec, 'app_channel': False},
                   {**spec, 'steps': [{**step, 'selector': {}}]},
                   {**spec, 'steps': [{'kind': 'layout_assert', 'rules': [
                       {'kind': 'no_overlap', 'selector': {'id': 'button'}}]}]},
                   {**spec, 'steps': [{'kind': 'application_command', 'name': '__arm_input'}]},
                   {**spec, 'steps': [{'kind': 'application_command', 'name': 'echo', 'arguments': {'x': 'a'*30001}}]}]
        for value in invalid:
            with self.subTest(value=str(value)[:200]), self.assertRaises(Exception):
                validate(value, SCENARIO)
        validate({**spec, 'steps': [step, {'kind': 'wait_log', 'contains': 'x', 'after': 'last_input'}]}, SCENARIO)
        # A correlated event has no log-wait cursor; do not pretend it supplied last_wait.
        with self.assertRaises(AutomationError):
            validate({**spec, 'steps': [step, {'kind': 'wait_log', 'contains': 'x', 'after': 'last_wait'}]}, SCENARIO)

    # Exact payload assertions distinguish booleans from numbers recursively but accept equivalent JSON numbers.
    def test_payload_equality(self):
        self.assertTrue(json_equal({'clicks': 1.0, 'values': [None, False]}, {'values': [None, False], 'clicks': 1}))
        self.assertFalse(json_equal({'clicks': [True]}, {'clicks': [1]}))
        self.assertFalse(json_equal({'clicks': 1, 'extra': 0}, {'clicks': 1}))

    # If a click succeeded but its expected log timed out, the failure report must retain dispatch evidence.
    def test_failed_step_report_keeps_receipt_and_escapes_text(self):
        report = step_report({'index': 0, 'state': 'failed', 'step': {'kind': 'element_click'},
                              'receipt': {'input_id': 'dispatched-once'}, 'error': {'message': '<script>bad</script>'}}, ROOT)
        self.assertIn('dispatched-once', report)
        self.assertIn('&lt;script&gt;', report)
        self.assertNotIn('<script>', report)

    def service(self, directory):
        service = Mock()
        service.root = Path(directory)
        service.builds = {'b': {'build_id': 'b'}}
        service.artifacts = {}
        service.runs = {}
        service.start.side_effect = [{'session_id': 's1', 'lease_id': 'l', 'pid': 1},
                                     {'session_id': 's2', 'lease_id': 'l', 'pid': 2}]
        service.capture.return_value = {'artifact_id': 'image', 'observation_id': 'image', 'render_sequence': 1,
                                        'image_path': str(Path(directory) / 'frame.png')}
        service.logs.return_value = {'entries': [], 'cursor': 0}
        service.report.return_value = {'report_path': str(Path(directory) / 'session.html')}
        return service

    # A semantic run preserves frame evidence, uses separate per-device command IDs, and never replays a click.
    def test_semantic_run_and_report(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'tmp') as directory:
            service = self.service(directory)
            service.layout_check.return_value = {'state': 'passed', 'rules': []}
            service.element_click_verified.return_value = {'business_confirmed': True}
            service.application_command.return_value = {'message': {'payload': {'clicks': 1}}}
            spec = {**self.spec, 'version': 2, 'app_channel': True, 'devices': self.spec['devices'][:2], 'steps': [
                {'kind': 'layout_assert', 'rules': [{'kind': 'inside_safe_area', 'selector': {'id': 'button'}}]},
                {'kind': 'element_click_verified', 'selector': {'id': 'button'}, 'event': 'clicked'},
                {'kind': 'application_command', 'name': 'state', 'expect_result': {'clicks': 1}}]}
            validate(spec, SCENARIO)
            result = run(service, 'b', spec, {'cancel': threading.Event()})
            self.assertEqual('passed', result['state'])
            self.assertTrue(all(call.kwargs['app_channel'] for call in service.start.call_args_list))
            clicks = service.element_click_verified.call_args_list
            self.assertEqual(2, len(clicks))
            self.assertNotEqual(clicks[0].args[4], clicks[1].args[4])
            saved = json.loads(Path(result['json_path']).read_text())
            self.assertEqual('image', saved['cases'][0]['steps'][0]['observation']['artifact_id'])
            report = Path(result['report_path']).read_text(encoding='utf-8')
            self.assertIn('layout_assert', report)
            self.assertIn('frame.png', report)
            self.assertIn('&quot;expect_result&quot;', report)

    # Unknown/missing layout is an explicit failure with its original observation, not a false pass or skip.
    def test_unknown_layout_fails_and_continues(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'tmp') as directory:
            service = self.service(directory)
            service.layout_check.side_effect = [{'state': 'unknown', 'rules': [{'reason': 'missing'}]},
                                                {'state': 'passed', 'rules': []}]
            spec = {**self.spec, 'version': 2, 'devices': self.spec['devices'][:2], 'steps': [
                {'kind': 'layout_assert', 'rules': [{'kind': 'inside_viewport', 'selector': {'id': 'absent'}}]}]}
            result = run(service, 'b', spec, {'cancel': threading.Event()})
            self.assertEqual(['failed', 'passed'], [c['state'] for c in result['cases']])
            self.assertEqual('LAYOUT_UNKNOWN', result['cases'][0]['error']['code'])
            saved = json.loads(Path(result['json_path']).read_text())
            self.assertEqual('unknown', saved['cases'][0]['steps'][0]['result']['state'])
            self.assertEqual(2, service.stop.call_count)

    # A returned QA response is evidence, but does not pass when it contradicts the asserted business payload.
    def test_qa_payload_mismatch_preserves_evidence(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'tmp') as directory:
            service = self.service(directory)
            service.application_command.return_value = {'message': {'payload': {'clicks': 0}}}
            spec = {**self.spec, 'version': 2, 'app_channel': True, 'devices': self.spec['devices'][:1], 'steps': [
                {'kind': 'application_command', 'name': 'state', 'expect_result': {'clicks': 1}}]}
            result = run(service, 'b', spec, {'cancel': threading.Event()})
            self.assertEqual('ASSERTION_FAILED', result['cases'][0]['error']['code'])
            saved = json.loads(Path(result['json_path']).read_text())
            self.assertEqual(0, saved['cases'][0]['steps'][0]['result']['message']['payload']['clicks'])
            service.stop.assert_called_once_with('s1')

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
