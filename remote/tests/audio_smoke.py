#!/usr/bin/env python3
"""Exercise a dedicated 440/660 Hz stereo fixture alongside 1080p video (starts/stops it)."""
import argparse
import concurrent.futures
import http.client
import json
import math
import struct
import time
from pathlib import Path
from urllib.parse import urlsplit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--connection', type=Path, required=True)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    config = json.loads(args.connection.read_text())
    base, token = urlsplit(config['base']), config['token']

    def request(route, method='GET', credential=token):
        connection = http.client.HTTPConnection(base.hostname, base.port, timeout=5)
        connection.request(method, base.path + route, body=b'' if method == 'POST' else None,
                           headers={'Authorization': 'Bearer ' + credential})
        return connection, connection.getresponse()

    def action(route, method='GET', expected=200, credential=token):
        connection, response = request(route, method, credential)
        try:
            assert response.status == expected, (route, response.status)
            data = response.read()
            return json.loads(data) if data else None
        finally:
            response.close()
            connection.close()

    def packet(response):
        length, sequence = struct.unpack('>II', response.read(8))
        assert length <= 8 * 1024 * 1024
        data = response.read(length)
        assert len(data) == length
        return sequence, data

    def audio():
        deadline = time.monotonic() + 5
        while True:
            connection, response = request('/audio')
            if response.status == 200:
                break
            response.close()
            connection.close()
            assert time.monotonic() < deadline
            time.sleep(.02)
        try:
            magic, rate, channels, bits = struct.unpack('>IIII', response.read(16))
            assert (magic, channels, bits) == (0x44464131, 2, 16)
            chunks, sequences, count = [], [], 0
            while count < rate * 4 * 8:
                sequence, data = packet(response)
                assert len(data) <= 3840 and len(data) % 4 == 0
                if data:
                    chunks.append(data)
                    sequences.append(sequence)
                    count += len(data)
            data = b''.join(chunks)
            values = struct.unpack('<' + 'h' * (len(data) // 2), data)
            measured = []
            for channel, expected in enumerate((440, 660)):
                samples = values[channel::2]
                rms = math.sqrt(sum(x*x for x in samples) / len(samples))
                frequency = sum(a < 0 <= b for a, b in zip(samples, samples[1:])) * rate / len(samples)
                assert rms > 300 and abs(frequency - expected) < 15, (rms, frequency)
                measured.append({'rms': round(rms), 'hz': round(frequency, 1)})
            return {'rate': rate, 'channels': measured, 'seconds': count / (rate*4),
                    'sequence_gaps': sum(b != (a+1) & 0xffffffff for a, b in zip(sequences, sequences[1:]))}
        finally:
            response.close()
            connection.close()

    def video():
        connection, response = request('/video?resolution=1080p')
        try:
            assert response.status == 200
            width, height = struct.unpack('>II', response.read(8))
            assert (width, height) == (1920, 1080)
            frames, first, last = 0, None, None
            while first is None or time.monotonic() - first < 8:
                _, data = packet(response)
                if data:
                    frames += 1
                    last = time.monotonic()
                    if first is None:
                        first = last
            return {'width': width, 'height': height, 'fps': round((frames-1)/(last-first), 2)}
        finally:
            response.close()
            connection.close()

    action('/audio', expected=403, credential='invalid')
    action('/start', 'POST')
    deadline = time.monotonic() + 30
    while action('/status')['state'] != 'running':
        assert time.monotonic() < deadline
        time.sleep(.05)
    try:
        assert action('/status')['audio']
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            audio_future, video_future = pool.submit(audio), pool.submit(video)
            result = {'audio': audio_future.result(), 'video': video_future.result()}
        # Reopening audio after disconnect must resume live audio, without restarting video/game.
        result['reconnected_audio'] = audio()
        connection, response = request('/audio')
        assert response.status == 200
        response.read(16)
        packet(response)
        action('/stop', 'POST')
        # A live subscriber must end promptly when the game stops.
        try:
            while response.read(4096):
                pass
        except http.client.IncompleteRead:
            pass
        finally:
            response.close()
            connection.close()
    finally:
        action('/stop', 'POST')
    deadline = time.monotonic() + 6
    while action('/status')['state'] != 'stopped':
        assert time.monotonic() < deadline
        time.sleep(.02)
    action('/audio', expected=409)
    if args.output:
        args.output.write_text(json.dumps(result, indent=2))
    print(json.dumps(result))
    print('PASS: audio auth, stereo PCM, concurrent 1080p video, reconnect and stop')


if __name__ == '__main__':
    main()
