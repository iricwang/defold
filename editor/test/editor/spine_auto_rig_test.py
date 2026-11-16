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

"""Regression checks for source-alpha centerlines used by the editor."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import numpy as np
from PIL import Image, ImageDraw

spec = importlib.util.spec_from_file_location('auto_rig', Path(__file__).resolve().parents[2] / 'resources' / 'spine-auto-rig.py')
rig = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rig)

class CenterlineTest(unittest.TestCase):
    def write(self, directory, draw):
        image = Image.new('RGBA', (256, 256)); draw(ImageDraw.Draw(image))
        path = Path(directory) / 'part.png'; image.save(path)
        return path, image

    # A curved thin part must follow its own pixels, not bridge empty space.
    def test_curved_part_and_determinism(self):
        with tempfile.TemporaryDirectory() as directory:
            path, image = self.write(directory, lambda d: d.arc((20,20,240,240), -70, 190, fill='white', width=12))
            proposal = rig.centerline(path)
            self.assertEqual(proposal, rig.centerline(path))
            self.assertGreater(len(proposal['centerline']), 100)
            alpha = np.asarray(image)[:,:,3]
            for u,v in proposal['centerline']:
                self.assertGreater(alpha[min(255,int(v*256)),min(255,int(u*256))], 0)

    # Independent parts need separate rigs instead of arbitrary joining bones.
    def test_disconnected_parts_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path,_ = self.write(directory, lambda d: (d.rectangle((10,10,40,240),fill='white'), d.rectangle((180,10,210,240),fill='white')))
            with self.assertRaisesRegex(ValueError, '连续部位'):
                rig.centerline(path)

    # Empty/degenerate selections report an actionable error before any commit.
    def test_empty_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path,_ = self.write(directory, lambda d: None)
            with self.assertRaises(ValueError): rig.centerline(path)

if __name__ == '__main__': unittest.main()
