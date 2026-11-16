# Copyright 2020-2026 The Defold Foundation
# Copyright 2014-2020 King
# Copyright 2009-2014 Ragnar Svensson, Christian Murray
# Licensed under the Defold License version 1.0 (the "License"); you may not use
# this file except in compliance with the License.
#
# You may obtain a copy of the License, together with FAQs at
# https://www.defold.com/license
#
# Unless required by applicable law or agreed to in writing, software distributed
# under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
# CONDITIONS OF ANY KIND, either express or implied. See the License for the
# specific language governing permissions and limitations under the License.

import importlib.util
import math
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import numpy as np
from PIL import Image, ImageDraw
import shapely
from shapely.geometry import Polygon

spec = importlib.util.spec_from_file_location('mesh_trace', Path(__file__).resolve().parents[2] / 'resources' / 'spine-mesh-trace.py')
trace = importlib.util.module_from_spec(spec)
spec.loader.exec_module(trace)


class TraceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'part.png'

    def tearDown(self):
        self.temp.cleanup()

    def save(self, image):
        image.save(self.path)

    # The hull must cover retained pixels, keep concavities and constrain every
    # boundary segment; detail changes density without filling a rectangular grid.
    def test_parameters_and_constraints(self):
        image = Image.new('RGBA', (180, 180))
        draw = ImageDraw.Draw(image)
        points = [(90+int((70 if i % 2 == 0 else 36)*math.cos(i*math.pi/24)),
                   90+int((70 if i % 2 == 0 else 36)*math.sin(i*math.pi/24))) for i in range(48)]
        draw.polygon(points, fill='white')
        self.save(image)
        sparse = trace.trace(self.path, {'detail': 0, 'concavity': 0, 'uniform': 0})
        dense = trace.trace(self.path, {'detail': 100, 'concavity': 100, 'uniform': 1})
        self.assertGreater(dense['report']['vertices'], sparse['report']['vertices'])
        for result in (sparse, dense):
            self.assertEqual(0, result['report']['clipped_pixels'])
            self.assertEqual(len(result['points'])-2, len(result['triangles'])//3)
            polygon = Polygon(result['points'])
            faces = [Polygon([result['points'][i] for i in result['triangles'][n:n+3]]) for n in range(0, len(result['triangles']), 3)]
            self.assertAlmostEqual(polygon.area, sum(face.area for face in faces), places=6)
            edges = {tuple(sorted((f[i], f[(i+1)%3]))) for n in range(0,len(result['triangles']),3) for f in [result['triangles'][n:n+3]] for i in range(3)}
            for i in range(len(result['points'])):
                self.assertIn(tuple(sorted((i,(i+1)%len(result['points'])))), edges)

    # Integer percentages must produce exactly the legacy ratio topology.
    def test_integer_uniform_percent_compatibility(self):
        image = Image.new('RGBA', (64, 64))
        ImageDraw.Draw(image).ellipse((6, 3, 58, 61), fill='white')
        self.save(image)
        for percent in (0, 1, 30, 100):
            self.assertEqual(trace.trace(self.path, {'uniform': percent / 100}),
                             trace.trace(self.path, {'uniform-percent': percent}))

    # Disconnected parts use narrow bridges, never one global convex hull.
    def test_separate_components_bridged(self):
        image = Image.new('RGBA', (64, 64)); draw = ImageDraw.Draw(image)
        draw.rectangle((3,3,15,15),fill='white'); draw.rectangle((40,40,55,55),fill='white'); self.save(image)
        result = trace.trace(self.path,{'detail':100, 'concavity':100})
        self.assertEqual(1, result['report']['islands_bridged'])
        self.assertEqual(0, result['report']['clipped_pixels'])
        self.assertLess(Polygon(result['points']).area, 900)

    # Isolated one/two-pixel noise is reported, while image-edge contours remain
    # bounded and an alpha threshold can intentionally exclude faint pixels.
    def test_noise_alpha_padding_and_image_bounds(self):
        image = Image.new('RGBA', (64,64)); draw = ImageDraw.Draw(image)
        draw.rectangle((0,0,30,40), fill=(255,255,255,255))
        draw.rectangle((31,0,38,40), fill=(255,255,255,7))
        image.putpixel((60,60),(255,255,255,255)); self.save(image)
        first=trace.trace(self.path,{'alpha':8,'padding':0})
        second=trace.trace(self.path,{'alpha':1,'padding':2})
        self.assertEqual(1, first['report']['ignored_noise_pixels'])
        self.assertGreater(Polygon(second['points']).area, Polygon(first['points']).area)
        self.assertTrue(all(0 <= u <= 1 for u in second['uvs']))
        self.assertEqual(0, second['report']['clipped_pixels'])

    # The native GEOS adapter preserves polygon coverage and frees each context;
    # malformed numeric settings fail before invoking native geometry code.
    def test_outer_hull_and_invalid_input(self):
        polygon=Polygon([(0,0),(10,0),(10,10),(6,10),(6,4),(4,4),(4,10),(0,10)])
        for fraction in (.1,.5,1):
            self.assertTrue(trace.outer_hull(polygon,fraction).covers(polygon))
        self.save(Image.new('RGBA',(20,20)))
        with self.assertRaisesRegex(ValueError,'没有可描绘'):
            trace.trace(self.path,{})
        for settings in ({'detail':float('nan')},{'alpha':0},{'padding':-1}):
            with self.assertRaises(ValueError):trace.trace(self.path,settings)

    # Sorting cheap local costs must avoid rebuilding every full candidate on
    # every collapse; count geometry constructions instead of flaky wall time.
    def test_simplification_work_scales_with_accepted_collapses(self):
        points = [(100+80*math.cos(i*math.tau/240),
                   100+60*math.sin(i*math.tau/240)) for i in range(240)]
        original = Polygon(points)
        with patch.object(trace, 'Polygon', wraps=Polygon) as constructors:
            simplified = trace.optimize_outline(original, original, 18, .15, 300, 200, 200)
        self.assertEqual(18, len(simplified.exterior.coords)-1)
        self.assertLess(constructors.call_count, 480)
        self.assertTrue(simplified.buffer(1e-7).covers(original))
        self.assertLessEqual(simplified.area, original.area*1.15)

    # Uniform spacing must respect the low-detail budget rather than refill
    # long smooth contours with dozens of points based on image dimensions.
    def test_low_detail_uniform_spacing_stays_sparse(self):
        image = Image.new('RGBA', (400, 600))
        ImageDraw.Draw(image).ellipse((40, 20, 360, 580), fill='white')
        self.save(image)
        settings = {'detail': 2.1, 'concavity': 59.7, 'refinement': 50, 'uniform': .3}
        sparse = trace.trace(self.path, settings)
        dense = trace.trace(self.path, dict(settings, detail=100))
        self.assertLessEqual(sparse['report']['vertices'], 24)
        self.assertGreater(dense['report']['vertices'], sparse['report']['vertices'])
        self.assertEqual(0, sparse['report']['clipped_pixels'])
        self.assertTrue(Polygon(sparse['points']).is_valid)

if __name__ == '__main__':
    unittest.main()
