#!/usr/bin/env python3
"""Exercise a running editor's game endpoint; requires a project opened in the updated editor."""
import argparse
import http.client
import json
from pathlib import Path
import struct
import time
from urllib.parse import urlsplit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--address', required=True, help='Full /games/{id} address from the Game panel')
    parser.add_argument('--code', required=True, help='Eight-digit Game panel pairing code')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--frames', type=int, default=300)
    parser.add_argument('--min-fps', type=float, default=0)
    parser.add_argument('--leave-running', action='store_true')
    args = parser.parse_args()
    endpoint = urlsplit(args.address)
    assert endpoint.scheme == 'http' and endpoint.path.startswith('/games/')
    args.output.mkdir(parents=True, exist_ok=True)
    token = ''

    def request(method, route, body=None):
        client = http.client.HTTPConnection(endpoint.hostname, endpoint.port, timeout=5)
        try:
            client.request(method, endpoint.path + route, body, {'Authorization': 'Bearer ' + token})
            response = client.getresponse()
            return response.status, response.read()
        finally:
            client.close()

    # Verifies pairing boundaries, stream framing, unique frames, and input acceptance on the editor session.
    assert request('GET', '/status')[0] == 403
    assert request('POST', '/pair', b'{')[0] == 400
    status, body = request('POST', '/pair', json.dumps({'code': args.code}))
    assert status == 200, body
    token = json.loads(body)['token']
    assert request('POST', '/start', b'')[0] == 200
    deadline = time.monotonic() + 60
    while True:
        status, body = request('GET', '/status')
        state = json.loads(body)
        if status == 200 and state['state'] == 'running':
            break
        assert time.monotonic() < deadline, state
        time.sleep(0.1)
    assert request('POST', '/start', b'')[0] == 200
    client = http.client.HTTPConnection(endpoint.hostname, endpoint.port, timeout=10)
    times, sequences = [], set()
    try:
        client.request('GET', endpoint.path + '/stream', headers={'Authorization': 'Bearer ' + token})
        response = client.getresponse()
        assert response.status == 200
        deadline = time.monotonic() + 20 + args.frames / 30
        while len(times) < args.frames:
            assert time.monotonic() < deadline, 'No complete stream'
            size, sequence = struct.unpack('>II', response.read(8))
            if not size:
                continue
            assert size <= 4 * 1024 * 1024
            image = response.read(size)
            assert len(image) == size and image[:2] == b'\xff\xd8'
            assert sequence not in sequences
            sequences.add(sequence)
            times.append(time.monotonic())
            if len(times) == 1:
                (args.output / 'frame.jpg').write_bytes(image)
            if len(times) == args.frames // 2:
                packet = struct.pack('<HHB', 32000, 32000, 1)
                assert request('POST', '/input', packet)[0] == 204
                assert request('POST', '/input', packet[:4] + b'\0')[0] == 204
        fps = (len(times) - 1) / (times[-1] - times[0])
        assert fps >= args.min_fps, fps
    finally:
        client.close()
        if not args.leave_running:
            assert request('POST', '/stop', b'')[0] == 200
    result = {'game': state['name'], 'protocol': state['protocol'], 'frames': len(times),
              'unique_frames': len(sequences), 'fps': fps, 'input_accepted': True,
              'authentication_checked': True, 'malformed_json_rejected': True,
              'transport': 'HTTP to ' + endpoint.hostname, 'left_running': args.leave_running}
    (args.output / 'results.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
