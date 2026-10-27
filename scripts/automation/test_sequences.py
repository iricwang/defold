"""Temporal evidence: sampled timing, partial failures, immutable originals and coordinate boundaries."""
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import Mock

from server import ROOT, SCENARIO, artifact_content, dispatch
from core import Service, AutomationError
from build import digest
from scenarios import validate
from PIL import Image
import sequences


class SequenceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=ROOT / 'tmp')
        self.addCleanup(self.temporary.cleanup)
        self.service = Service({'engine': 'unused'}, [], self.temporary.name)
        self.addCleanup(self.service.close)
        self.service.session = Mock(return_value={})
        self.count = 0
        self.job = {'cancel': threading.Event()}
        self.service.capture = self.capture

    def capture(self, session_id, publish=True):
        self.count += 1
        path = Path(self.temporary.name) / f'frame{self.count}.png'
        Image.new('RGB', (64, 48), (self.count * 20, 30, 40)).save(path)
        frame = {'artifact_id': str(self.count), 'observation_id': str(self.count), 'session_id': session_id,
                 'artifact_kind': 'runtime_frame', 'image_path': str(path), 'image_sha256': digest(path),
                 'width': 64, 'height': 48, 'device': {'width': 64, 'height': 48}, 'device_revision': 1,
                 'render_sequence': self.count, 'input_sequence': 0, 'uri': f'defold://artifacts/{self.count}/frame.png'}
        if publish: self.service.publish_capture(frame)
        return frame

    # Host times and every raw PNG are retained; overview coordinates are explicitly not an input observation.
    def test_success_artifacts_resources_and_report(self):
        result = sequences.run(self.service, 's', self.job, sample_count=3, interval_ms=100)
        self.assertEqual('complete', result['state'])
        self.assertEqual(3, result['captured_frames'])
        self.assertEqual(4, len(self.service.artifacts))
        for frame in result['frames']:
            self.assertEqual(frame['image_sha256'], digest(frame['image_path']))
            self.assertLessEqual(frame['capture_started_ms'], frame['capture_completed_ms'])
        times = [f['capture_completed_ms'] for f in result['frames']]
        self.assertTrue(all(a < b for a,b in zip(times,times[1:])))
        with Image.open(result['preview']['path']) as image:
            self.assertEqual(3, image.n_frames)
            self.assertEqual((64, 48), image.size)
        response = artifact_content(result)
        self.assertEqual('contact_sheet_not_input', response.structuredContent['inline_preview']['coordinate_space'])
        self.assertNotIn('to_frame_scale', response.structuredContent['inline_preview'])
        self.assertTrue(any(c.type == 'resource_link' and c.mimeType == 'image/webp' for c in response.content))
        with self.assertRaises(AutomationError): self.service.original(result['overview']['artifact_id'])
        report = self.service.report('s')
        self.assertIn(result['sequence_id'], Path(report['report_path']).read_text(encoding='utf-8'))
        self.assertEqual(result, dispatch(self.service, 'sequence_get', {'sequence_id': result['sequence_id']}))
        self.assertFalse(self.service.sequence_active)

    # Duplicate engine render sequences are dropped instead of appearing to be new observed frames.
    def test_duplicates_do_not_consume_budget(self):
        def duplicate(*args, **kwargs):
            frame = self.capture(*args, **kwargs)
            frame['render_sequence'] = 1
            return frame
        self.service.capture = duplicate
        result = sequences.run(self.service, 's', self.job, 3, 100)
        self.assertEqual(1, result['captured_frames'])
        self.assertEqual(2, result['duplicate_renders'])
        self.assertFalse((Path(self.temporary.name) / 'frame2.png').exists())
        self.assertEqual(2, len(self.service.artifacts))

    # Device changes never contaminate a sequence and the rejected unpublished PNG is cleaned up.
    def test_device_change_preserves_partial(self):
        def resize(*args, **kwargs):
            frame = self.capture(*args, **kwargs)
            frame['device_revision'] = self.count
            return frame
        self.service.capture = resize
        with self.assertRaises(AutomationError) as error: sequences.run(self.service, 's', self.job, 3, 100)
        self.assertEqual('DEVICE_CHANGED', error.exception.code)
        partial = error.exception.details
        self.assertEqual(1, partial['captured_frames'])
        self.assertEqual('failed', partial['state'])
        self.assertTrue(Path(partial['report_path']).is_file())
        self.assertFalse((Path(self.temporary.name) / 'frame2.png').exists())
        self.assertFalse(self.service.sequence_active)

    # Cancelled work produces reviewable partial evidence and never stops someone else's session.
    def test_cancel_retains_frames(self):
        def cancel(*args, **kwargs):
            frame = self.capture(*args, **kwargs)
            if self.count == 2: self.job['cancel'].set()
            return frame
        self.service.capture = cancel
        self.service.stop = Mock()
        with self.assertRaises(AutomationError) as error: sequences.run(self.service, 's', self.job, 5, 100)
        self.assertEqual('CANCELLED', error.exception.code)
        self.assertEqual(2, error.exception.details['captured_frames'])
        self.assertEqual('cancelled', error.exception.details['state'])
        self.service.stop.assert_not_called()
        self.assertFalse(self.service.sequence_active)

    # Memory/disk budgets and duplicate recorders are rejected before capture, without claiming a new lease.
    def test_bounds_capacity_and_busy(self):
        for samples, interval in ((1,100), (61,100), (10,2000), (True,100), (2,99)):
            with self.assertRaises(ValueError): sequences.validate(samples, interval)
        self.service.sequence_active.add('s')
        with self.assertRaises(AutomationError) as error: sequences.run(self.service, 's', self.job)
        self.assertEqual('SEQUENCE_BUSY', error.exception.code)
        self.service.sequence_active.clear()
        self.service.artifacts = {str(i): {'session_id':'s'} for i in range(255)}
        with self.assertRaises(AutomationError) as error: sequences.run(self.service, 's', self.job, 2, 100)
        self.assertEqual('ARTIFACT_LIMIT', error.exception.code)
        self.assertEqual(0, self.count)

    # A corrupted source cannot be silently encoded as trustworthy temporal evidence.
    def test_corrupt_encoding_retains_manifest(self):
        def corrupt(*args, **kwargs):
            frame = self.capture(*args, **kwargs)
            Path(frame['image_path']).write_bytes(b'changed')
            return frame
        self.service.capture = corrupt
        with self.assertRaises(AutomationError) as error: sequences.run(self.service, 's', self.job, 2, 100)
        self.assertEqual('SEQUENCE_ENCODING_FAILED', error.exception.code)
        self.assertEqual(2, error.exception.details['captured_frames'])
        self.assertNotIn('preview', error.exception.details)
        self.assertFalse(self.service.sequence_active)

    # The V2 runner rejects invalid sampling spans before launching a game; V1 stays strict.
    def test_scenario_schema(self):
        spec = json.loads((ROOT / 'scripts/automation/fixture/scenario.json').read_text())
        spec.update(version=2, steps=[{'kind':'capture_sequence','sample_count':3,'interval_ms':100}])
        validate(spec, SCENARIO)
        with self.assertRaises(Exception): validate({**spec, 'version':1}, SCENARIO)
        spec['steps'][0].update(sample_count=60, interval_ms=1000)
        with self.assertRaises(ValueError): validate(spec, SCENARIO)


if __name__ == '__main__': unittest.main()
