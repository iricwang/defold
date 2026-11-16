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

import io
import unittest
from spine_editor import Decoder, encode

class ProtocolTests(unittest.TestCase):
    # Bencode lengths count bytes; Unicode in project and bone names must survive.
    def test_unicode_and_status(self):
        wire = encode({'out': '尾巴\n', 'id': 'test'})
        self.assertEqual({'out': '尾巴\n', 'id': 'test'}, Decoder(io.BytesIO(wire)).read())
        self.assertEqual({'status': ['done']}, Decoder(io.BytesIO(b'd6:statusl4:doneee')).read())

    # Broken sockets and oversized/nested replies fail before any retry.
    def test_invalid_response(self):
        for wire in [b'4:abc', b'99999999999:', b'l' * 52 + b'e' * 52]:
            with self.assertRaises((ValueError, EOFError)):
                Decoder(io.BytesIO(wire)).read()

if __name__ == '__main__':
    unittest.main()
