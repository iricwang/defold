"""Semantic drag identity, coordinates, bounded contracts and non-replayed receipts."""
import copy
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock

from server import ROOT, SCENARIO, tools, dispatch
from core import Service, AutomationError
from scenarios import validate
import gestures


def item(name, x=20, y=30):
    return {'element_id': 'instance-' + name, 'id': name, 'type': 'gui_node_box',
            'geometry_precision': 'engine_gui_rectangle',
            'visible_polygon': [[x-10, y-5], [x+10, y-5], [x+10, y+5], [x-10, y+5]]}


class GestureTests(unittest.TestCase):
    def setUp(self):
        self.before = [item('handle'), item('drop', 100, 100)]
        self.current = [item('handle', 50, 60), item('drop', 150, 170)]
        self.frame = {'width': 200, 'height': 300, 'observation_id': 'fresh'}
        self.selector = {'id': 'handle'}
        self.target = {'selector': {'id': 'drop'}}

    # Both endpoints refresh from the same new snapshot; stale input coordinates are never reused.
    def test_refresh_both_endpoints(self):
        a, b = gestures.resolve(self.before, self.current, self.selector, self.target, self.frame)
        self.assertEqual([50, 60], a['point'])
        self.assertEqual([150, 170], b['point'])
        self.assertEqual('fresh', a['resolved_observation_id'])
        self.assertEqual('fresh', b['resolved_observation_id'])
        _, edge = gestures.resolve(self.before, self.current, self.selector,
                                  {'coordinate_space': 'frame_normalized', 'x': 1, 'y': 0}, self.frame)
        self.assertEqual([199, 0], edge['point'])

    # A reused display ID must not silently redirect a drag to a replacement node.
    def test_destroyed_replaced_and_changed_selector(self):
        for index in (0, 1):
            for change in ({'element_id': 'replacement'}, {'id': 'renamed'}):
                current = copy.deepcopy(self.current)
                current[index].update(change)
                with self.subTest(index=index, change=change), self.assertRaises(gestures.GestureError) as error:
                    gestures.resolve(self.before, current, self.selector, self.target, self.frame)
                self.assertEqual('STALE_ELEMENT', error.exception.code)

    # Missing/ambiguous/hidden/unknown geometry stops before mouse-down, for source and destination alike.
    def test_unusable_nodes(self):
        with self.assertRaises(gestures.GestureError): gestures.unique(self.before * 2, self.selector)
        with self.assertRaises(gestures.GestureError): gestures.unique([], self.selector)
        for index in (0, 1):
            for change in ({'visible_polygon': []}, {'geometry_precision': 'unknown_clip'}):
                current = copy.deepcopy(self.current)
                current[index].update(change)
                with self.subTest(index=index, change=change), self.assertRaises(gestures.GestureError) as error:
                    gestures.resolve(self.before, current, self.selector, self.target, self.frame)
                self.assertEqual('ELEMENT_NOT_INTERACTABLE', error.exception.code)

    # Schema rejects mixed destinations, unbounded samples, V1 and missing explicit business-channel opt-in.
    def test_schema_and_preflight(self):
        step = {'kind': 'element_drag_verified', 'selector': self.selector, 'destination': self.target, 'event': 'dropped'}
        spec = {'version': 2, 'name': 'drag', 'app_channel': True,
                'devices': [{'name': 'one', 'device': {'width': 200, 'height': 300}}], 'steps': [step]}
        validate(spec, SCENARIO)
        invalid = [{**spec, 'version': 1}, {**spec, 'app_channel': False}]
        for change in ({'steps': 121}, {'destination': {}},
                       {'destination': {**self.target, 'coordinate_space': 'frame_normalized', 'x': 1, 'y': 0}},
                       {'destination': {'coordinate_space': 'frame_normalized', 'x': 1.1, 'y': 0}}):
            invalid.append({**spec, 'steps': [{**step, **change}]})
        for value in invalid:
            with self.assertRaises(Exception): validate(value, SCENARIO)
        validate({**spec, 'steps': [step, {'kind': 'wait_log', 'contains': 'done', 'after': 'last_input'}]}, SCENARIO)
        for steps in (0, 121, True, 1.5):
            with self.assertRaises(gestures.GestureError): gestures.validate(self.target, steps)
        self.assertTrue({'defold_element_drag', 'defold_element_drag_verified'} <= {tool.name for tool in tools()})

    def service(self):
        temporary = tempfile.TemporaryDirectory(dir=ROOT / 'tmp')
        self.addCleanup(temporary.cleanup)
        service = Service({'engine': 'unused'}, [], temporary.name)
        self.addCleanup(service.close)
        worker = Mock()
        worker.process.poll.return_value = None
        session = {'lock': threading.RLock(), 'worker': worker, 'lease_id': 'lease', 'lease_until': time.monotonic()+60,
                   'device': {'width': 200, 'height': 300}}
        service.session = Mock(return_value=session)
        old = {**self.frame, 'artifact_id': 'old', 'observation_id': 'old', 'session_id': 's',
               'device': session['device'], 'device_revision': 1, 'gui_projection_profile': 'default_gui'}
        service.original = Mock(return_value=old)
        service.capture = Mock(return_value={**old, 'artifact_id': 'fresh', 'observation_id': 'fresh'})
        service.input = Mock(return_value={'after': {'artifact_id': 'after'}})
        return service, session

    # Lease, device revision and cancellation gates must all prevent dispatch.
    def test_dispatch_guards(self):
        from unittest.mock import patch
        service, session = self.service()
        with self.assertRaises(AutomationError): service.element_drag('s', 'wrong', 'old', self.selector, self.target)
        service.capture.assert_not_called()
        with patch('core.gui_layout.elements', side_effect=[self.before, self.current]):
            cancel = threading.Event(); cancel.set()
            with self.assertRaises(AutomationError) as error:
                service.element_drag('s', 'lease', 'old', self.selector, self.target, cancel=cancel)
            self.assertEqual('CANCELLED', error.exception.code)
        service.capture.return_value['device_revision'] = 2
        with patch('core.gui_layout.elements', return_value=self.before), self.assertRaises(AutomationError) as error:
            service.element_drag('s', 'lease', 'old', self.selector, self.target)
        self.assertEqual('STALE_OBSERVATION', error.exception.code)
        service.input.assert_not_called()

    # Idempotency covers successful dispatch and its fresh-coordinate evidence.
    def test_dispatch_and_deduplication(self):
        from unittest.mock import patch
        service, _ = self.service()
        args = {'session_id': 's', 'lease_id': 'lease', 'observation_id': 'old', 'selector': self.selector,
                'destination': self.target, 'operation_id': 'drag-once', 'steps': 7}
        with patch('core.gui_layout.elements', side_effect=[self.before, self.current]):
            result = dispatch(service, 'element_drag', args)
            self.assertEqual(result, dispatch(service, 'element_drag', args))
        service.input.assert_called_once_with('s', 'lease',
            {'type': 'drag', 'x': 50, 'y': 60, 'to_x': 150, 'to_y': 170, 'steps': 7}, 'fresh')
        self.assertFalse(result['business_confirmed'])

    # A business timeout preserves evidence of the completed drag and never repeats it.
    def test_business_failure_receipt(self):
        service, _ = self.service()
        service.logs = Mock(return_value={'cursor': 4})
        service.application_command = Mock()
        service.element_drag = Mock(return_value={'after': {'artifact_id': 'after'}, 'business_confirmed': False})
        service.application_wait = Mock(side_effect=AutomationError('BUSINESS_TIMEOUT', 'No callback'))
        args = {'session_id': 's', 'lease_id': 'lease', 'observation_id': 'old', 'selector': self.selector,
                'destination': self.target, 'operation_id': 'failed-drag', 'event': 'dropped'}
        with self.assertRaises(AutomationError) as error: dispatch(service, 'element_drag_verified', args)
        self.assertEqual('BUSINESS_TIMEOUT', error.exception.code)
        self.assertTrue(error.exception.details['input_dispatched'])
        self.assertEqual('after', error.exception.details['after']['artifact_id'])
        with self.assertRaises(AutomationError): dispatch(service, 'element_drag_verified', args)
        service.element_drag.assert_called_once()


if __name__ == '__main__': unittest.main()
