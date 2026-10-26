"""Fast protocol and ownership regressions; no running editor or game required."""
import json
from pathlib import Path
import tempfile
import threading
import unittest

from core import AutomationError, Service
from server import ACTION, DEVICE, PROJECT_CONFIG, ROOT, artifact_content, dispatch, tools
from build import host_platform, validate_macos_engine, MACOS_BRIDGE_MARKER
from PIL import Image
from jsonschema import ValidationError, validate


class CoreTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=ROOT / 'tmp', prefix='automation-unit-')
        self.addCleanup(self.temporary.cleanup)
        self.service = Service({'engine': 'unused-test-engine'}, [ROOT / 'scripts/automation/fixture'], self.temporary.name)
        self.addCleanup(self.service.close)

    # A repeated client request must return its receipt without repeating a click/build side effect.
    def test_operation_is_executed_once(self):
        calls = []
        first = self.service.operation('same', {'x': 1}, lambda: calls.append(1) or {'value': 2})
        self.assertEqual(first, self.service.operation('same', {'x': 1}, lambda: calls.append(2)))
        self.assertEqual([1], calls)

    # Reusing an operation ID for a different action must not accidentally return unrelated success.
    def test_mismatched_operation_rejected(self):
        self.service.operation('same', {'x': 1}, lambda: {})
        with self.assertRaises(AutomationError) as failure:
            self.service.operation('same', {'x': 2}, lambda: {})
        self.assertEqual('IDEMPOTENCY_MISMATCH', failure.exception.code)

    # Failed/partial actions must remain failures when clients retry after transport uncertainty.
    def test_failed_operation_not_replayed(self):
        def fail(): raise ValueError('partially applied')
        with self.assertRaises(ValueError): self.service.operation('failed', {}, fail)
        with self.assertRaises(AutomationError) as failure:
            self.service.operation('failed', {}, lambda: {})
        self.assertEqual('OPERATION_FAILED', failure.exception.code)

    # Concurrent duplicate requests cannot run the same mutation twice.
    def test_pending_operation_not_replayed(self):
        entered, release = threading.Event(), threading.Event()
        def work():
            entered.set()
            if not release.wait(5): raise AssertionError('test synchronization timeout')
            return {}
        worker = threading.Thread(target=lambda: self.service.operation('pending', {}, work))
        worker.start()
        try:
            self.assertTrue(entered.wait(5))
            with self.assertRaises(AutomationError) as failure:
                self.service.operation('pending', {}, lambda: {})
            self.assertEqual('OPERATION_PENDING', failure.exception.code)
        finally:
            release.set(); worker.join(5)

    # A new service process cannot replay an old journalled input as if it were a new request.
    def test_journal_prevents_restart_replay(self):
        self.service.operation('old', {}, lambda: {'ok': True})
        replacement = Service({'engine': 'unused-test-engine'}, [ROOT / 'scripts/automation/fixture'], self.temporary.name)
        try:
            with self.assertRaises(AutomationError) as failure:
                replacement.operation('old', {}, lambda: {})
            self.assertEqual('RECOVERY_REQUIRED', failure.exception.code)
        finally: replacement.close()

    # Only projects explicitly configured on the server may be built/read.
    def test_project_allowlist(self):
        with self.assertRaises(AutomationError) as failure:
            self.service.project('../../outside')
        self.assertEqual('PROJECT_NOT_REGISTERED', failure.exception.code)

    # Named gestures have independent schemas: malformed coordinates and irrelevant fields never reach native code.
    def test_strict_action_schema(self):
        for invalid in ({'type': 'click', 'x': -1, 'y': 0}, {'type': 'click', 'x': True, 'y': 0},
                        {'type': 'click', 'x': 10.5, 'y': 0}, {'type': 'click', 'x': 1, 'y': 2, 'code': 'eval'},
                        {'type': 'drag', 'x': 0, 'y': 0, 'to_x': 20, 'to_y': 30, 'steps': 100000}):
            with self.subTest(invalid=invalid), self.assertRaises(ValidationError): validate(invalid, ACTION)

    # Device allocations are bounded before Java allocates the capture mapping.
    def test_device_limits(self):
        for invalid in ({'width': 0, 'height': 480}, {'width': 8192, 'height': 8192},
                        {'width': 640, 'height': 480, 'top': -1}):
            with self.assertRaises(ValidationError): validate(invalid, DEVICE)

    # QA configuration cannot override the engine's controlled graphics/network/process settings.
    def test_project_configuration_scope(self):
        validate({'project.smoke_test': '1'}, PROJECT_CONFIG)
        for invalid in ({'display.width': '1'}, {'project.qa': 'a\nb'}, {'project.qa': 1}):
            with self.assertRaises(ValidationError): validate(invalid, PROJECT_CONFIG)

    # Generated MCP definitions remain uniquely named and reject unrecognized top-level fields.
    def test_tool_catalog_contract(self):
        catalog = tools()
        self.assertEqual(len(catalog), len({item.name for item in catalog}))
        for item in catalog:
            self.assertFalse(item.inputSchema['additionalProperties'])
            self.assertIsNotNone(item.outputSchema)

    # Preset conversion preserves the per-orientation safe area instead of swapping dimensions alone.
    def test_device_presets(self):
        listed = dispatch(self.service, 'defold_device_list', {'query': 'iPhone', 'limit': 2})
        self.assertGreater(listed['total'], 0)
        phone = listed['devices'][0]
        for orientation in phone['orientations']:
            result = dispatch(self.service, 'defold_device_resolve', {'name': phone['name'], 'rotation': orientation['rotation']})
            device = result['device']
            self.assertGreater(device['width'] - device['left'] - device['right'], 0)
            self.assertGreater(device['height'] - device['top'] - device['bottom'], 0)

    def frame(self, name, changed=False):
        path = Path(self.temporary.name) / (name + '.png')
        image = Image.new('RGB', (10, 10), (20, 30, 40))
        if changed:
            for x in range(3, 5):
                for y in range(4, 7): image.putpixel((x, y), (120, 30, 40))
        image.save(path)
        item = dict(artifact_id=name, observation_id=name, session_id='s', device_revision=1,
                    device={'width': 10, 'height': 10}, image_path=str(path), width=10, height=10,
                    render_sequence=1, input_sequence=0, artifact_kind='runtime_frame')
        self.service.artifacts[name] = item
        return item

    # Crops retain the original observation and an explicit offset; they cannot masquerade as input frames.
    def test_crop_coordinate_contract(self):
        self.frame('source', True)
        crop = self.service.crop('source', {'x': 3, 'y': 4, 'width': 2, 'height': 3})
        self.assertEqual('source', crop['observation_id'])
        self.assertEqual([3, 4], crop['to_frame_offset'])
        with Image.open(crop['image_path']) as image:
            self.assertEqual((2, 3), image.size)
            self.assertEqual((120, 30, 40), image.getpixel((0, 0)))
        inline = artifact_content(crop).structuredContent['inline_preview']
        self.assertEqual([3, 4], inline['to_frame_offset'])
        with self.assertRaises(AutomationError): self.service.original(crop['artifact_id'])
        with self.assertRaises(AutomationError):
            self.service.crop('source', {'x': 9, 'y': 0, 'width': 2, 'height': 3})

    # Pixel evidence counts changed pixels once, reports exact bounds, and honors masks without treating them as success.
    def test_comparison_metrics_masks_and_report(self):
        self.frame('before'); self.frame('after', True)
        result = self.service.compare('before', 'after')
        self.assertEqual('different', result['verdict'])
        self.assertFalse(result['business_confirmed'])
        self.assertEqual(6, result['changed_pixels'])
        self.assertEqual(0.06, result['changed_ratio'])
        self.assertEqual(6, result['mean_max_channel_delta'])
        self.assertEqual({'x': 3, 'y': 4, 'width': 2, 'height': 3}, result['changed_bounds'])
        ignored = self.service.compare('before', 'after', ignore=[result['changed_bounds']])
        self.assertEqual(94, ignored['compared_pixels'])
        self.assertEqual('match', ignored['verdict'])
        self.assertEqual(0, ignored['max_channel_delta'])
        equal = self.service.compare('before', 'before')
        self.assertEqual('match', equal['verdict'])
        self.assertIsNone(equal['changed_bounds'])
        report = self.service.report('s')
        self.assertEqual(3, report['comparisons'])
        self.assertIn('Difference', Path(report['report_path']).read_text(encoding='utf-8'))

    # Invalid/empty masks, mismatched devices, and NaN thresholds fail explicitly instead of rescaling or passing.
    def test_comparison_rejects_invalid_evidence(self):
        self.frame('a'); self.frame('b')
        for options in ({'ignore': [{'x': 0, 'y': 0, 'width': 10, 'height': 10}]},
                        {'max_changed_ratio': float('nan')}, {'channel_threshold': -1}):
            with self.subTest(options=options), self.assertRaises(AutomationError):
                self.service.compare('a', 'b', **options)
        self.service.artifacts['b']['device'] = {'width': 20, 'height': 20}
        with self.assertRaises(AutomationError) as failure: self.service.compare('a', 'b')
        self.assertEqual('DEVICE_MISMATCH', failure.exception.code)

    # Threshold equality is allowed, and overlapping ignored regions must not double-subtract pixels.
    def test_comparison_threshold_boundaries(self):
        self.frame('a'); self.frame('b', True)
        self.assertEqual('match', self.service.compare('a', 'b', channel_threshold=100)['verdict'])
        self.assertEqual('match', self.service.compare('a', 'b', max_changed_ratio=0.06)['verdict'])
        region = {'x': 3, 'y': 4, 'width': 2, 'height': 3}
        self.assertEqual(94, self.service.compare('a', 'b', ignore=[region, region])['compared_pixels'])
        self.assertEqual(1, self.service.compare('a', 'b', region=region)['changed_ratio'])

    # Architecture selection must not silently compile Intel binaries on Apple Silicon or accept unsupported hosts.
    def test_host_platform_selection(self):
        self.assertEqual('x86_64-win32', host_platform('Windows', 'AMD64'))
        self.assertEqual('arm64-macos', host_platform('Darwin', 'arm64'))
        self.assertEqual('x86_64-macos', host_platform('Darwin', 'x86_64'))
        with self.assertRaises(ValueError): host_platform('Linux', 'x86_64')

    # Old macOS engines are rejected before startup; a marker spanning read blocks remains detectable.
    def test_macos_bridge_preflight(self):
        path = Path(self.temporary.name) / 'engine'
        path.write_bytes(b'old engine')
        with self.assertRaises(ValueError): validate_macos_engine(path)
        path.write_bytes(b'\0' * (1024 * 1024 - 3) + MACOS_BRIDGE_MARKER)
        validate_macos_engine(path)

    # A macOS import preserves the actual engine and never asks for a Windows PDB.
    def test_macos_import_without_windows_symbols(self):
        project = Path(self.temporary.name) / 'mac-project'
        compiled = project / 'build/default'
        compiled.mkdir(parents=True)
        (project / 'game.project').write_text('[project]\ntitle = test')
        (compiled / 'game.projectc').write_bytes(b'compiled fixture')
        engine = project / 'build/arm64-macos/dmengine'
        engine.parent.mkdir()
        engine.write_bytes(b'test native engine')
        service = Service({'platform': 'arm64-macos', 'engine': str(engine)}, [project], Path(self.temporary.name) / 'mac-artifacts')
        try:
            imported = service.import_build(next(iter(service.projects)), {'cancel': threading.Event()})
            self.assertEqual('dmengine', Path(imported['engine']).name)
            self.assertEqual(engine.read_bytes(), Path(imported['engine']).read_bytes())
            self.assertEqual('hid_snapshot', service.discover()['input_ack_level'])
        finally:
            service.close()


if __name__ == '__main__':
    unittest.main()
