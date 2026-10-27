"""Same-frame annotation provenance, geometric evidence and report contracts."""
import copy
from pathlib import Path
import tempfile
import unittest

from server import ROOT, SCENARIO, artifact_content
from core import Service, AutomationError
from build import digest
from scenarios import step_report
from jsonschema import validate, ValidationError
from PIL import Image
import annotations
import layout


class AnnotationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=ROOT / 'tmp')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        project = self.root / 'project'
        project.mkdir()
        (project / 'game.project').write_text('[project]\n')
        self.service = Service({'engine': 'unused'}, [project], self.root / 'artifacts')
        self.addCleanup(self.service.close)
        image = self.root / 'raw.png'
        Image.new('RGB', (200, 200), (15, 20, 25)).save(image)
        node = {'index': 0, 'parent': -1, 'element_id': 'instance-a', 'enabled_recursive': True, 'visible': True,
                'properties': {'id': 'a', 'type': 'gui_node_box', 'size': [20, 10, 0], 'pivot': 'PIVOT_CENTER'},
                'gui_world_matrix': [0, 2, 0, 0, -2, 0, 0, 0, 0, 0, 1, 0, 50, 40, 0, 1]}
        self.frame = {'artifact_id': 'frame', 'observation_id': 'frame', 'session_id': 'test',
                      'project_id': next(iter(self.service.projects)), 'source': 'runtime', 'artifact_kind': 'runtime_frame',
                      'image_path': str(image), 'image_sha256': digest(image), 'width': 200, 'height': 200,
                      'render_sequence': 4, 'input_sequence': 0, 'device': {'width': 200, 'height': 200, 'left': 45},
                      'gui_projection_profile': 'default_gui',
                      'scene_snapshot': {'supported': True, 'render_sequence': 4, 'nodes': [node]}}
        self.service.artifacts['frame'] = self.frame
        self.rule = {'kind': 'inside_viewport', 'selector': {'id': 'a'}}

    # Marking is optional and creates independent bytes with an identity mapping to the frozen observation.
    def test_identity_and_raw_immutability(self):
        self.service.layout_check('frame', [self.rule])
        self.assertEqual(1, len(self.service.artifacts))
        before = copy.deepcopy(self.frame)
        result = self.service.layout_check('frame', [self.rule], annotate=True)
        artifact = result['annotation']
        self.assertEqual(before, self.frame)
        self.assertEqual(before['image_sha256'], digest(before['image_path']))
        self.assertEqual('layout_annotation', artifact['artifact_kind'])
        self.assertEqual('frame', artifact['source_artifact_id'])
        self.assertEqual('frame', artifact['observation_id'])
        self.assertEqual(4, artifact['render_sequence'])
        self.assertEqual([1, 1], artifact['to_frame_scale'])
        self.assertEqual([0, 0], artifact['to_frame_offset'])
        with Image.open(artifact['image_path']) as image:
            self.assertEqual((200, 200), image.size)
        response = artifact_content(result)
        self.assertTrue(any(part.type == 'image' for part in response.content))
        self.assertEqual('Layout annotation', next(part.name for part in response.content if part.type == 'resource_link'))
        with self.assertRaises(AutomationError): self.service.original(artifact['artifact_id'])
        with self.assertRaises(AutomationError): self.service.elements(artifact['artifact_id'], {'id': 'a'})
        with self.assertRaises(AutomationError): self.service.baseline_save(self.frame['project_id'], 'bad', artifact['artifact_id'])

    # Rotation, safe-area violations and overlap use the precise engine quadrilateral, not its bounding box.
    def test_rotated_overlap_and_safe_area(self):
        rules = [self.rule, {'kind': 'inside_safe_area', 'selector': {'id': 'a'}},
                 {'kind': 'no_overlap', 'selector': {'id': 'a'}, 'other': {'id': 'a'}}]
        result = self.service.layout_check('frame', rules, annotate=True)
        plan = result['annotation']['layout_annotation']
        self.assertEqual(['passed', 'failed', 'failed'], [r['state'] for r in plan['rules']])
        self.assertEqual([[60, 180], [60, 140], [40, 140], [40, 180]], plan['rules'][0]['targets'][0]['quad'])
        self.assertEqual(200, plan['rules'][1]['outside_area'])
        self.assertEqual(800, abs(layout.area(plan['rules'][2]['overlap_polygon'])))
        self.assertEqual(['3a', '3b'], [t['label'] for t in plan['rules'][2]['targets']])

    # Unsupported, missing, ambiguous and fully offscreen nodes never acquire a fabricated on-image box.
    def test_unknown_and_offscreen_locations(self):
        node = self.frame['scene_snapshot']['nodes'][0]
        text_node = copy.deepcopy(node)
        text_node.update(index=1, element_id='instance-text')
        text_node['properties'].update(id='<script>alert(1)</script>', type='gui_node_text')
        offscreen = copy.deepcopy(node)
        offscreen.update(index=2, element_id='instance-offscreen')
        offscreen['properties']['id'] = 'outside'
        offscreen['gui_world_matrix'][12] = -1000
        self.frame['scene_snapshot']['nodes'].extend([text_node, offscreen])
        rules = [{'kind': 'inside_viewport', 'selector': selector} for selector in
                 ({'id': text_node['properties']['id']}, {'id': 'absent'}, {'type': 'gui_node_box'}, {'id': 'outside'})]
        result = self.service.layout_check('frame', rules, annotate=True)
        plan = result['annotation']['layout_annotation']
        self.assertEqual(['unknown_geometry', 'missing', 'ambiguous', 'outside_viewport'],
                         [r['targets'][0]['location_status'] for r in plan['rules']])
        self.assertTrue(all(not r['targets'][0]['drawable_polygon'] for r in plan['rules']))
        legend = annotations.legend_html(plan)
        self.assertNotIn('<script>', legend)
        self.assertIn('&lt;script&gt;', legend)

    # Snapshot/image mismatch and damaged PNGs fail before publishing a convincing but untrustworthy mark-up.
    def test_corrupt_and_mismatched_source(self):
        Path(self.frame['image_path']).write_bytes(b'changed')
        with self.assertRaises(AutomationError) as error:
            self.service.layout_check('frame', [self.rule], annotate=True)
        self.assertEqual('ARTIFACT_CORRUPT', error.exception.code)
        self.assertEqual(1, len(self.service.artifacts))
        self.frame['scene_snapshot']['render_sequence'] = 3
        with self.assertRaises(AutomationError) as error:
            self.service.layout_check('frame', [self.rule], annotate=True)
        self.assertEqual('GEOMETRY_UNAVAILABLE', error.exception.code)

    # Both exported reports retain raw+annotated evidence and a readable UTF-8 numbered legend.
    def test_reports_and_schema(self):
        result = self.service.layout_check('frame', [self.rule], annotate=True)
        entry = {'index': 0, 'step': {'kind': 'layout_assert', 'rules': [self.rule]}, 'state': 'passed',
                 'result': result, 'observation': self.frame}
        text = step_report(entry, self.root)
        self.assertIn('raw.png', text)
        self.assertIn(Path(result['annotation']['image_path']).name, text)
        self.assertIn('layout-legend', text)
        report = self.service.report('test')
        self.assertIn('layout-legend', Path(report['report_path']).read_text(encoding='utf-8'))
        spec = {'version': 2, 'name': 'test', 'devices': [{'name': 'test', 'device': {'width': 200, 'height': 200}}],
                'steps': [{**entry['step'], 'annotate': False}]}
        validate(spec, SCENARIO)
        spec['steps'][0]['annotate'] = 'false'
        with self.assertRaises(ValidationError): validate(spec, SCENARIO)


if __name__ == '__main__':
    unittest.main()
