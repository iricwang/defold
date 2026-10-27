"""GUI wait predicates, sampled stability, cancellation and bounded evidence storage."""
import json
from pathlib import Path
import tempfile
import threading
import unittest

from server import ROOT, SCENARIO, SELECTOR, obj, tools
from core import AutomationError, Service
from conditions import Stability, definition, evaluate
from jsonschema import validate, ValidationError
from PIL import Image


class PredicateTests(unittest.TestCase):
    def node(self):
        return {'element_id': 'a', 'id': 'button', 'enabled': True, 'text': 'Ready',
                'geometry_precision': 'engine_gui_rectangle', 'visible_polygon': [[0,0],[2,0],[2,2],[0,2]]}

    # Missing/hidden/disabled are different states; unknown text/shader geometry must never count as hidden.
    def test_finite_conditions(self):
        node = self.node()
        selector = {'id': 'button'}
        self.assertTrue(evaluate([], selector, 'absent')['satisfied'])
        self.assertFalse(evaluate([], selector, 'hidden')['satisfied'])
        self.assertTrue(evaluate([node], selector, 'visible')['satisfied'])
        self.assertTrue(evaluate([node], selector, 'enabled')['satisfied'])
        self.assertFalse(evaluate([node], selector, 'disabled')['satisfied'])
        self.assertTrue(evaluate([node], selector, 'text_equals', 'Ready')['satisfied'])
        self.assertFalse(evaluate([node], selector, 'text_equals', 'ready')['satisfied'])
        self.assertEqual('AMBIGUOUS_ELEMENT', evaluate([node,node], selector, 'present')['error_code'])
        node['visible_polygon'] = []
        self.assertTrue(evaluate([node], selector, 'hidden')['satisfied'])
        node.update(geometry_precision='unknown', visible_polygon=None)
        self.assertEqual('GEOMETRY_UNAVAILABLE', evaluate([node], selector, 'hidden')['error_code'])

    # A duplicate frame does not advance confirmation; replacing the node or device resets the streak.
    def test_stability_identity_and_frame_guard(self):
        tracker = Stability()
        frame = {'device_revision': 1, 'render_sequence': 1}
        result = {'satisfied': True, 'element': self.node()}
        self.assertEqual(1, tracker.update(frame, result))
        self.assertEqual(1, tracker.update(frame, result))
        frame['render_sequence'] = 2
        self.assertEqual(2, tracker.update(frame, result))
        result['element']['element_id'] = 'replacement'
        frame['render_sequence'] = 3
        self.assertEqual(1, tracker.update(frame, result))
        frame['device_revision'] = 2
        self.assertEqual(1, tracker.update(frame, result))
        frame['render_sequence'] = 4
        self.assertEqual(0, tracker.update(frame, {'satisfied': False}))

    # The MCP and V2 scenario share the same bounded schema; arbitrary expressions and stray text are rejected.
    def test_schema(self):
        request = {'selector': {'id': 'button'}, 'condition': 'text_equals', 'text': '', 'stable_samples': 2}
        validate(request, definition(obj, SELECTOR))
        for bad in ({**request, 'stable_samples': 0}, {**request, 'timeout_ms': 999999},
                    {**request, 'condition': 'present'}, {**request, 'selector': {}},
                    {'selector': {'id': 'button'}, 'condition': 'text_equals'}):
            with self.assertRaises(ValidationError): validate(bad, definition(obj, SELECTOR))
        spec = json.loads((ROOT / 'scripts/automation/fixture/scenario.json').read_text())
        spec.update(version=2, steps=[{'kind': 'wait_element', **request}])
        validate(spec, SCENARIO)
        with self.assertRaises(ValidationError): validate({**spec, 'version': 1}, SCENARIO)
        tool = next(tool for tool in tools() if tool.name == 'defold_element_wait')
        validate({**request, 'session_id': 's', 'operation_id': 'wait'}, tool.inputSchema)


class WaitTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=ROOT / 'tmp')
        self.service = Service({'engine': 'unused'}, [], self.temporary.name)
        self.counter = 0

    def tearDown(self):
        self.service.close()
        self.temporary.cleanup()

    def capture(self, session_id, publish=True):
        self.counter += 1
        path = Path(self.temporary.name) / (str(self.counter) + '.png')
        Image.new('RGB', (64, 48)).save(path)
        node = {'index': 0, 'parent': -1, 'element_id': 'button-instance', 'enabled_recursive': True, 'visible': True,
                'properties': {'id': 'button', 'type': 'gui_node_box', 'size': [10,10,0], 'pivot': 'PIVOT_CENTER'},
                'gui_world_matrix': [1,0,0,0,0,1,0,0,0,0,1,0,20,20,0,1]}
        frame = {'artifact_id': str(self.counter), 'observation_id': str(self.counter), 'session_id': session_id,
                 'image_path': str(path), 'width': 64, 'height': 48, 'device_revision': 1,
                 'render_sequence': self.counter, 'gui_projection_profile': 'default_gui',
                 'scene_snapshot': {'supported': True, 'render_sequence': self.counter, 'nodes': [node]}}
        if publish: self.service.publish_capture(frame)
        return frame

    # Intermediate GPU PNGs never consume the artifact budget; only the successful immutable evidence remains.
    def test_success_retains_only_final_sample(self):
        self.service.capture = self.capture
        progress = []
        result = self.service.wait_element('s', {'id': 'button'}, 'visible', stable_samples=3, progress=progress.append)
        self.assertEqual(3, result['samples'])
        self.assertEqual(3, result['consecutive_matches'])
        self.assertEqual(result['after']['artifact_id'], next(iter(self.service.artifacts)))
        self.assertEqual(1, len(list(Path(self.temporary.name).glob('*.png'))))
        self.assertEqual(3, len(progress))

    # A timeout preserves the last actual frame and explicit predicate result for diagnostics.
    def test_timeout_evidence(self):
        self.service.capture = self.capture
        with self.assertRaises(AutomationError) as error:
            self.service.wait_element('s', {'id': 'absent'}, 'present', timeout_ms=200)
        self.assertEqual('CONDITION_TIMEOUT', error.exception.code)
        self.assertEqual(0, error.exception.details['evidence']['matched'])
        self.assertTrue(Path(error.exception.details['after']['image_path']).is_file())
        self.assertEqual(1, len(self.service.artifacts))
        self.assertEqual(1, len(list(Path(self.temporary.name).glob('*.png'))))

    # Cancellation during a capture keeps evidence but cannot report a condition as completed.
    def test_cancel_during_capture(self):
        cancel = threading.Event()
        def capture(*args, **kwargs):
            frame = self.capture(*args, **kwargs)
            cancel.set()
            return frame
        self.service.capture = capture
        with self.assertRaises(AutomationError) as error:
            self.service.wait_element('s', {'id': 'button'}, 'present', cancel=cancel)
        self.assertEqual('CANCELLED', error.exception.code)
        self.assertEqual(1, len(self.service.artifacts))

    # Unsupported native snapshots fail promptly and retain a real screenshot, rather than guessing absence.
    def test_unsupported_snapshot(self):
        def capture(*args, **kwargs):
            frame = self.capture(*args, **kwargs)
            frame['scene_snapshot']['supported'] = False
            return frame
        self.service.capture = capture
        with self.assertRaises(AutomationError) as error:
            self.service.wait_element('s', {'id': 'button'}, 'absent')
        self.assertEqual('GEOMETRY_UNAVAILABLE', error.exception.code)
        self.assertEqual(1, error.exception.details['samples'])


if __name__ == '__main__':
    unittest.main()
