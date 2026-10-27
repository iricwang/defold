#!/usr/bin/env python3
"""Validate a dedicated editor test game's H.264/JPEG negotiation and control isolation.
Connection JSON contains base and token. This test starts/stops the test game.
"""
import argparse
import http.client
import json
import struct
import time
from pathlib import Path
from urllib.parse import urlsplit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--connection', type=Path, required=True)
    args = parser.parse_args()
    config = json.loads(args.connection.read_text())
    endpoint, token = urlsplit(config['base']), config['token']

    def request(route, method='GET', body=None, credential=token):
        connection = http.client.HTTPConnection(endpoint.hostname, endpoint.port, timeout=5)
        connection.request(method, endpoint.path + route, body, {'Authorization': 'Bearer ' + credential})
        response = connection.getresponse()
        result = response.status, response.read()
        connection.close()
        return result

    def stream(route):
        connection = http.client.HTTPConnection(endpoint.hostname, endpoint.port, timeout=5)
        connection.request('GET', endpoint.path + route, headers={'Authorization': 'Bearer ' + token})
        response = connection.getresponse()
        assert response.status == 200, response.status
        return connection, response

    def packet(response):
        for _ in range(120):
            length, sequence = struct.unpack('>II', response.read(8))
            assert 0 <= length <= 8 * 1024 * 1024
            if not length:
                continue
            data = response.read(length)
            assert len(data) == length
            return sequence, data
        raise AssertionError('No video frame')

    # Reject unauthenticated, malformed and duplicate parameter requests before starting a codec.
    assert request('/video?resolution=1080p', credential='invalid')[0] == 403
    for query in ['', '?resolution=4k', '?resolution=1080p&resolution=720p']:
        assert request('/video' + query)[0] == 400
    assert 'h264' in json.loads(request('/status')[1])['video_codecs']
    assert request('/start', 'POST', b'')[0] == 200
    deadline = time.monotonic() + 30
    while json.loads(request('/status')[1])['state'] != 'running':
        assert time.monotonic() < deadline
        time.sleep(.05)
    try:
        # A resolution change must start a fresh independently decodable stream.
        for resolution, limit in [('1080p', 1920), ('720p', 1280), ('original', 4096), ('1080p', 1920)]:
            connection, response = stream('/video?resolution=' + resolution)
            try:
                assert response.getheader('Content-Type') == 'application/x-defold-h264'
                width, height = struct.unpack('>II', response.read(8))
                assert 2 <= width <= limit and 2 <= height <= limit and width % 2 == 0 and height % 2 == 0
                _, data = packet(response)
                types = {nal[0] & 31 for nal in data.split(b'\x00\x00\x00\x01')[1:] if nal}
                assert {7, 8, 5} <= types, types
                assert request('/input', 'POST', struct.pack('<HHB', 20000, 22000, 1))[0] == 204
                assert request('/input', 'POST', struct.pack('<HHB', 20000, 22000, 0))[0] == 204
                assert request('/thumbnail')[0] in (200, 204)
            finally:
                response.close()
                connection.close()
        # The existing APK/JPEG contract remains valid after hardware sessions.
        connection, response = stream('/stream?resolution=720p')
        try:
            _, data = packet(response)
            assert data.startswith(b'\xff\xd8')
        finally:
            response.close()
            connection.close()
    finally:
        assert request('/stop', 'POST', b'')[0] == 200
    deadline = time.monotonic() + 6
    while json.loads(request('/status')[1])['state'] != 'stopped':
        assert time.monotonic() < deadline
        time.sleep(.05)
    assert request('/input', 'POST', b'\x00' * 5)[0] == 409
    print('PASS: auth, profile validation, H.264 IDR/config, quality changes, input, thumbnails, JPEG fallback, stop')


if __name__ == '__main__':
    main()
