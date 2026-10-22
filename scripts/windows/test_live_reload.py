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
import contextlib
import io
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

import live_reload


class LiveReloadTest(unittest.TestCase):
    # Fragmented UTF-8 output and acknowledgements must not finish a reload early.
    def test_fragmented_success(self):
        connection = MagicMock()
        connection.recv.side_effect = [b'user=> \xe4', b'\xb8\xad\nDEFOLD_RELOAD_test_', b'OK\r\n']
        connection.__enter__.return_value = connection
        output = io.StringIO()
        with patch.object(live_reload.uuid, 'uuid4', return_value=SimpleNamespace(hex='test')), \
                patch.object(live_reload.socket, 'create_connection', return_value=connection) as connect, \
                contextlib.redirect_stdout(output):
            live_reload.exchange(19941, '(+ 1 1)', 10)
        connect.assert_called_once_with(('127.0.0.1', 19941), timeout=10)
        self.assertIn('中', output.getvalue())
        self.assertIn('RELOAD_OK', output.getvalue())
        self.assertEqual(3, connection.recv.call_count)

    # Clojure exceptions and missing acknowledgements must return failure, never success.
    def test_failure_and_disconnect(self):
        for response, message in [(b'compiler error\nDEFOLD_RELOAD_test_ERROR\n', 'compiler error'),
                                  (b'', 'disconnected')]:
            with self.subTest(response=response):
                connection = MagicMock()
                connection.__enter__.return_value = connection
                connection.recv.return_value = response
                with patch.object(live_reload.uuid, 'uuid4', return_value=SimpleNamespace(hex='test')), \
                        patch.object(live_reload.socket, 'create_connection', return_value=connection), \
                        self.assertRaisesRegex(RuntimeError, message):
                    live_reload.exchange(19941, '(throw (Exception.))', 10)

    # Check the identity guard runs before loading; malformed inputs must never connect.
    def test_source_validation_and_identity_order(self):
        root = Path(__file__).resolve().parents[2]
        args = SimpleNamespace(port=19941, pid=123, timeout=10,
                               namespace=['editor.game-preview'], after=None)
        with patch.object(live_reload, 'exchange') as exchange, contextlib.redirect_stdout(io.StringIO()):
            live_reload.reload_sources(root, args)
            code = exchange.call_args.args[1]
            self.assertLess(code.index('ProcessHandle/current'), code.index('(load-file'))
            self.assertLess(code.index('(require'), code.index('(load-file'))
            self.assertLess(code.index('ProcessHandle/current'), code.index('.addURL'))
            self.assertLess(code.index('.addURL'), code.index('(load-file'))
            self.assertIn('/editor/resources/', code)
            exchange.reset_mock()
            args.namespace = ['../../outside']
            with self.assertRaises(ValueError):
                live_reload.reload_sources(root, args)
            exchange.assert_not_called()

    # A timeout must report uncertain state without retrying a potentially partial mutation.
    def test_timeout_not_retried(self):
        root = Path(__file__).resolve().parents[2]
        args = SimpleNamespace(port=19941, pid=123, timeout=10,
                               namespace=['editor.game-preview'], after=None)
        with patch.object(live_reload, 'exchange', side_effect=TimeoutError) as exchange, \
                self.assertRaisesRegex(RuntimeError, 'outcome unknown'):
            live_reload.reload_sources(root, args)
        exchange.assert_called_once()


if __name__ == '__main__':
    unittest.main()
