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
"""Compare actual engine samples with the saved editor transform samples."""
import json
from pathlib import Path

project = Path(__file__).resolve().parent.parent
expected = json.loads((project / 'validation/editor-samples.json').read_text())
actual = json.loads((project / 'runtime.json').read_text())
assert len(actual) == len(expected), 'Sample count differs'
errors = {}
for reference, observed in zip(expected, actual):
    assert reference['time'] == observed['time'], 'Sample time differs'
    assert reference['nodes'].keys() == observed['nodes'].keys(), 'GO paths differ'
    for name, channels in reference['nodes'].items():
        for channel, values in channels.items():
            result = observed['nodes'][name][channel]
            assert len(values) == len(result), (name, channel, 'Component count differs')
            error = max(abs(x - y) for x, y in zip(values, result))
            if channel == 'rotation':
                error = min(error, max(abs(x + y) for x, y in zip(values, result)))
            errors[channel] = max(errors.get(channel, 0), error)
            assert error < 0.001, (reference['time'], name, channel, error)
print('PASS: 11 GO objects, 8 samples:', errors)
