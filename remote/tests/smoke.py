#!/usr/bin/env python3
"""Exercise a real graphical engine through the complete host HTTP API."""
import argparse
import http.client
import json
from pathlib import Path
import socket
import struct
import sys
import threading
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from host import RemoteHost, Server


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--engine', type=Path, required=True)
    parser.add_argument('--project', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--stream-frames', type=int, default=180)
    parser.add_argument('--min-fps', type=float, default=0, help='Optional local performance acceptance threshold')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    command = [str(args.engine.resolve()), '--config=engine.run_while_iconified=1',
               '--config=display.width=640', '--config=display.height=360', str(args.project.resolve())]
    host = RemoteHost(command, args.project.resolve().parent, port, args.output / 'game.log')
    server = Server(('127.0.0.1', 0), host)
    thread = threading.Thread(target=server.serve_forever)
    thread.start()
    token = ''

    def request(method, path, body=None):
        client = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=20)
        try:
            client.request(method, path, body, {'Authorization': 'Bearer ' + token})
            response = client.getresponse()
            return response.status, response.read()
        finally:
            client.close()

    evidence = {'transport': 'local HTTP feed (loopback)', 'cycles': []}
    try:
        assert request('POST', '/start')[0] == 403
        status, body = request('POST', '/pair', json.dumps({'code': host.code}))
        assert status == 200
        token = json.loads(body)['token']
        for cycle in range(2):
            status, body = request('POST', '/start', b'')
            assert status == 200, body
            assert json.loads(body)['state'] == 'running', body
            pid = host.process.pid
            assert request('POST', '/start', b'')[0] == 200
            assert host.process.pid == pid
            images = set()
            deadline = time.monotonic() + 8
            count = 0
            while len(images) < 3 and time.monotonic() < deadline:
                status, body = request('GET', '/frame')
                if status == 200:
                    assert body[:8] == b'\x89PNG\r\n\x1a\n'
                    images.add(body)
                    count += 1
                    (args.output / ('frame-%s.png' % cycle)).write_bytes(body)
                elif status != 204:
                    raise AssertionError((status, body))
                # Production polling cadence; success is gated by changing image data, not elapsed time.
                threading.Event().wait(0.07)
            assert len(images) >= 3, 'No changing rendered frames'
            stream = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=5)
            times, sequences = [], set()
            try:
                stream.request('GET', '/stream', headers={'Authorization': 'Bearer ' + token})
                response = stream.getresponse()
                assert response.status == 200
                for _ in range(args.stream_frames):
                    size, sequence = struct.unpack('>II', response.read(8))
                    assert 0 < size <= 4 * 1024 * 1024
                    image = response.read(size)
                    assert len(image) == size and image[:8] == b'\x89PNG\r\n\x1a\n'
                    assert sequence not in sequences, 'Repeated frame in continuous stream'
                    sequences.add(sequence)
                    times.append(time.monotonic())
                    if len(times) == args.stream_frames // 2:
                        packet = struct.pack('<HHB', 16384, 49151, 1)
                        assert request('POST', '/input', packet)[0] == 204
                        assert request('POST', '/input', packet[:4] + b'\0')[0] == 204
                fps = (len(times) - 1) / (times[-1] - times[0])
                assert fps >= args.min_fps, 'Measured %.2f FPS below threshold' % fps
            finally:
                stream.close()
            packet = struct.pack('<HHB', 16384, 49151, 1)
            assert request('POST', '/input', packet)[0] == 204
            assert request('POST', '/input', packet[:4] + b'\0')[0] == 204
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                text = (args.output / 'game.log').read_text(errors='replace')
                if text.count('GAME_INPUT') >= (cycle + 1) * 4:
                    break
                threading.Event().wait(0.02)
            assert text.count('GAME_INPUT') >= (cycle + 1) * 4, 'Input edges missing from game log'
            status, body = request('POST', '/stop', b'')
            assert status == 200, body
            assert json.loads(body)['state'] == 'stopped'
            assert request('GET', '/frame')[0] == 204
            evidence['cycles'].append({'pid': pid, 'distinct_frames': len(images), 'frame_responses': count,
                                       'unique_stream_frames': len(sequences), 'stream_fps': fps,
                                       'start_idempotent': True, 'touch_edges': True, 'stopped': True})
        evidence['passed'] = True
        (args.output / 'results.json').write_text(json.dumps(evidence, indent=2))
        print(json.dumps(evidence, indent=2))
    finally:
        host.stop()
        server.shutdown()
        server.server_close()
        thread.join()


if __name__ == '__main__':
    main()
