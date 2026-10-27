import http.client
import json
import queue
from pathlib import Path
import socket
import struct
import sys
import tempfile
import time
import threading
import unittest
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from host import RemoteHost, Server, frame_png, discover


class HostTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.host = RemoteHost([sys.executable, '-c', 'raise SystemExit(3)'], self.temp.name, 65432,
                               Path(self.temp.name) / 'game.log')
        self.server = Server(('127.0.0.1', 0), self.host)
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.token = None

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.host.stop()
        self.temp.cleanup()

    def request(self, method, path, body=None):
        connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=5)
        try:
            headers = {'Authorization': 'Bearer ' + (self.token or '')}
            connection.request(method, path, body=body, headers=headers)
            response = connection.getresponse()
            return response.status, response.read()
        finally:
            connection.close()

    def pair(self):
        status, body = self.request('POST', '/pair', json.dumps({'code': self.host.code}))
        self.assertEqual(200, status)
        self.token = json.loads(body)['token']

    # Verifies unpaired clients cannot observe or change the game, and a new pairing revokes the old token.
    def test_authentication_and_revocation(self):
        for method, route in [('GET', '/status'), ('POST', '/start'), ('POST', '/stop'), ('GET', '/frame'), ('GET', '/stream')]:
            self.assertEqual(403, self.request(method, route)[0])
        self.assertEqual(403, self.request('POST', '/pair', b'{"code":"wrong"}')[0])
        self.pair()
        old = self.token
        self.assertEqual('stopped', json.loads(self.request('GET', '/status')[1])['state'])
        self.pair()
        self.assertNotEqual(old, self.token)
        self.token = old
        self.assertEqual(403, self.request('POST', '/start')[0])

    # Verifies malformed/oversized requests and incorrect HTTP methods never launch a process.
    def test_validation(self):
        self.pair()
        self.assertEqual(405, self.request('GET', '/start')[0])
        self.assertEqual(404, self.request('POST', '/start/anything')[0])
        self.assertEqual(400, self.request('POST', '/pair', b'[]')[0])
        self.assertEqual(413, self.request('POST', '/input', b'x' * 1025)[0])
        self.assertEqual(400, self.request('POST', '/input', b'1234')[0])
        self.assertEqual(400, self.request('POST', '/input', b'1234\x02')[0])
        self.assertEqual(409, self.request('POST', '/input', b'1234\x01')[0])
        self.assertIsNone(self.host.process)

    # Verifies brute-force pairing attempts are bounded even when the supplied code changes.
    def test_pair_rate_limit(self):
        for _ in range(5):
            self.assertEqual(403, self.host.pair('wrong')[0])
        self.assertEqual(429, self.host.pair(self.host.code)[0])

    # Verifies stop is idempotent and does not create a game when none is running.
    def test_idle_stop_and_frame(self):
        self.pair()
        for _ in range(2):
            self.assertEqual('stopped', json.loads(self.request('POST', '/stop')[1])['state'])
        self.assertEqual((204, b''), self.request('GET', '/frame'))

    # Verifies ordered stream framing, no duplicate image sends, and revocation of an open stream.
    def test_continuous_stream_and_revocation(self):
        self.pair()
        frames = queue.Queue()
        self.host.state = 'running'

        def next_frame():
            try:
                self.host.sequence, png = frames.get(timeout=0.1)
                return 200, png
            except queue.Empty:
                return 204, b''

        self.host.frame = next_frame
        connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=3)
        try:
            connection.request('GET', '/stream', headers={'Authorization': 'Bearer ' + self.token})
            response = connection.getresponse()
            self.assertEqual(200, response.status)
            for sequence in (1, 2):
                _, png = frame_png(b'DFR1' + struct.pack('<III', 1, 1, sequence) + b'\0\0\xff\xff')
                frames.put((sequence, png))
                size, actual = struct.unpack('>II', response.read(8))
                self.assertEqual(sequence, actual)
                self.assertEqual(png, response.read(size))
            frames.put((2, png))
            self.assertEqual((0, 0), struct.unpack('>II', response.read(8)))
            self.pair()
            # One heartbeat may already be in flight when pairing revokes the stream.
            remaining = response.read()
            self.assertIn(remaining, (b'', b'\0' * 8))
        finally:
            connection.close()

    # Verifies an occupied port is rejected without sending engine commands to an unrelated process.
    def test_occupied_engine_port(self):
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            listener.listen()
            self.host.engine_port = listener.getsockname()[1]
            with self.assertRaisesRegex(ValueError, 'occupied'):
                self.host.start()
            self.assertIsNone(self.host.process)

    # Verifies failed game launch reports an error and reaps the child process.
    def test_failed_launch(self):
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            self.host.engine_port = listener.getsockname()[1]
        with self.assertRaisesRegex(ValueError, 'failed to start'):
            self.host.start()
        self.assertEqual('error', self.host.status()['state'])
        self.assertIsNone(self.host.process)


class DiscoveryTest(unittest.TestCase):
    # Verifies LAN discovery returns the HTTP endpoint without leaking either pairing or engine credentials.
    def test_discovery(self):
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as reservation:
            reservation.bind(('127.0.0.1', 0))
            port = reservation.getsockname()[1]
        stop = threading.Event()
        worker = threading.Thread(target=discover, args=('127.0.0.1', port, 8390, stop))
        worker.start()
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as client:
                client.settimeout(0.1)
                deadline = time.monotonic() + 3
                while True:
                    client.sendto(b'DEFOLD_REMOTE_DISCOVER_1', ('127.0.0.1', port))
                    try:
                        body, address = client.recvfrom(1024)
                        break
                    except socket.timeout:
                        if time.monotonic() >= deadline:
                            self.fail('Discovery never became available')
                response = json.loads(body)
                self.assertEqual('defold-remote', response['service'])
                self.assertEqual(8390, response['port'])
                self.assertEqual({'service', 'protocol', 'name', 'port'}, set(response))
        finally:
            stop.set()
            worker.join(timeout=2)
            self.assertFalse(worker.is_alive())


class FrameTest(unittest.TestCase):
    # Verifies BGRA conversion, top-left row order, opaque PNG pixels, and intact chunk checksums.
    def test_frame_pixels(self):
        frame = b'DFR1' + struct.pack('<III', 1, 2, 7) + bytes([0, 0, 255, 0, 255, 0, 0, 0])
        sequence, png = frame_png(frame)
        self.assertEqual(7, sequence)
        offset, data = 8, b''
        while offset < len(png):
            size, = struct.unpack_from('>I', png, offset)
            kind = png[offset + 4:offset + 8]
            chunk = png[offset + 8:offset + 8 + size]
            crc, = struct.unpack_from('>I', png, offset + 8 + size)
            self.assertEqual(zlib.crc32(kind + chunk), crc)
            if kind == b'IDAT':
                data += chunk
            offset += size + 12
        self.assertEqual(bytes([0, 255, 0, 0, 255, 0, 0, 0, 255, 255]), zlib.decompress(data))

    # Verifies truncated, oversized, and wrong-version engine frames cannot allocate unbounded images.
    def test_invalid_frame(self):
        for frame in (b'', b'BAD1' + b'\0' * 12, b'DFR1' + struct.pack('<III', 0, 1, 0),
                      b'DFR1' + struct.pack('<III', 961, 1, 0), b'DFR1' + struct.pack('<III', 1, 1, 0)):
            with self.assertRaises(ValueError):
                frame_png(frame)


if __name__ == '__main__':
    unittest.main()
