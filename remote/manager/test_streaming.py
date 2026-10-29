"""Sustained real GPU streaming with rapid taps, or a read-only imported real game."""
import argparse
import hashlib
import http.client
import json
from pathlib import Path
import shutil
import ssl
import struct
import threading
import time
from urllib.parse import parse_qs, urlparse
import uuid

from test_android_project import ManagerPipe

ROOT = Path(__file__).resolve().parents[2]


def run(manifest, project_build=None, seconds=60, tap_hz=1):
    manifest = Path(manifest).resolve()
    runtime = json.loads(manifest.read_text(encoding='utf-8'))
    work = ROOT/'tmp/remote-streaming-tests'/uuid.uuid4().hex
    work.mkdir(parents=True)
    if project_build:
        project = Path(project_build).resolve()
    else:
        project = work/'game'
        shutil.copytree(ROOT/'scripts/automation/fixture', project)
        with (project/'main.gui_script').open('a', encoding='utf-8') as out:
            out.write('''
local base_init = init
function init(self)
    base_init(self)
    for i=1,800 do
        gui.new_box_node(vmath.vector3(-100-i, -100, 0), vmath.vector3(2))
    end
end
''')
    manager = ManagerPipe(manifest, runtime, work)
    pid = None
    stop = threading.Event()
    errors, sequences, gaps, taps, input_times = [], [], [], [], []
    stream = None
    reader = None
    report = dict(outcome='failed', scope='Isolated real Windows/macOS GPU and TLS; not physical Android Wi-Fi',
                  project=str(project), runtimeRevision=runtime['revision'])

    def request(path, token='', body=None):
        c = http.client.HTTPSConnection('127.0.0.1', port, context=ssl._create_unverified_context(), timeout=3.5)
        c.connect()
        assert hashlib.sha256(c.sock.getpeercert(binary_form=True)).hexdigest() == fingerprint
        headers = {'Authorization': 'Bearer '+token}
        if path.endswith('/input'):
            headers['X-Defold-Device-Revision'] = str(claim['device_revision'])
        c.request('GET' if body is None else 'POST', path, json.dumps(body) if isinstance(body, dict) else body, headers)
        return c, c.getresponse()

    def json_request(path, token='', body=None, expected=200):
        c, r = request(path, token, body)
        try:
            data = json.loads(r.read())
            assert r.status == expected, (path, r.status, data)
            return data
        finally:
            c.close()

    def status():
        return next(p for p in manager.rpc('status')['projects'] if p['id'] == pid)

    def await_state(expected):
        end = time.monotonic()+240
        while time.monotonic() < end:
            s = status()
            if s['state'] == 'error': raise AssertionError(s['error'])
            if s['state'] == expected and not s['busy']: return s
            time.sleep(.1)
        raise TimeoutError(expected)

    try:
        host = manager.rpc('status')
        port, fingerprint = host['port'], host['fingerprint']
        manager.rpc('settings', prepare=False)
        pid = manager.rpc('add', path=str(project))['id']
        if project_build:
            manager.rpc('import', id=pid)
            await_state('ready')
        pair = manager.rpc('pairing')
        code = parse_qs(urlparse(pair['uri']).query)['code'][0]
        auth = json_request('/pair', body=dict(code=code, name='Sustained stream test'))['token']
        base = '/games/'+pid
        json_request(base+'/start', auth, b'', 202)
        await_state('running')
        claim = json_request(base+'/claim', auth, {})
        token = claim['token']
        stream, response = request(base+'/video?resolution=540p', token)
        assert response.status == 200, response.read()
        width, height = struct.unpack('>II', response.read(8))
        report['dimensions'] = [width, height]
        began = time.monotonic()

        def receive():
            last = time.monotonic()
            try:
                while not stop.is_set():
                    size, sequence = struct.unpack('>II', response.read(8))
                    assert 0 <= size <= 8*1024*1024
                    data = response.read(size)
                    assert len(data) == size
                    if size:
                        now = time.monotonic()
                        gaps.append(now-last); last = now
                        sequences.append(sequence)
            except Exception as error:
                if not stop.is_set(): errors.append(str(error))

        reader = threading.Thread(target=receive, daemon=True)
        reader.start()
        next_tap = began
        while time.monotonic()-began < seconds and not errors:
            if not project_build and time.monotonic() >= next_tap:
                # Back-to-back edges expose taps swallowed within a single game update.
                for down in (1, 0):
                    sent = time.monotonic()
                    json_request(base+'/input', token, struct.pack('<HHB', 32768, 32768, down))
                    input_times.append((time.monotonic()-sent)*1000)
                taps.append(time.monotonic()-began)
                next_tap += 1/tap_hz
            time.sleep(.02)
        report.update(frames=len(sequences), uniqueFrames=len(set(sequences)), maxGapSeconds=max(gaps, default=0),
                      elapsedSeconds=time.monotonic()-began, streamErrors=errors, sentTaps=len(taps))
        if input_times:
            ordered = sorted(input_times)
            report['inputRoundTripMs'] = dict(median=ordered[len(ordered)//2], p95=ordered[min(len(ordered)-1, int(len(ordered)*.95))], maximum=max(ordered))
        if not project_build:
            # Logs are a game callback, not only a transport acceptance or window marker.
            logs = json.dumps(manager.rpc('logs', id=pid))
            report['clickCallbacks'] = logs.count('BUTTON_CLICKED ')
            report['releaseCallbacks'] = logs.count('POINTER_RELEASED')
        assert not errors, errors
        assert len(set(sequences)) >= seconds*10, report
        assert max(gaps, default=99) < 3, report
        if not project_build:
            assert report['clickCallbacks'] == len(taps), report
            assert report['releaseCallbacks'] >= len(taps), report
        report['outcome'] = 'passed'
    finally:
        stop.set()
        if stream: stream.close()
        if reader: reader.join(timeout=5)
        report['forcedOwnedCleanup'] = manager.close(pid)
        (work/'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(dict(work=str(work), **report), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest')
    parser.add_argument('--project-build', type=Path)
    parser.add_argument('--seconds', type=int, default=60)
    parser.add_argument('--tap-hz', type=float, default=1, help='Target tap rate; each press/release is verified in Lua')
    args = parser.parse_args()
    if args.tap_hz <= 0:
        parser.error('--tap-hz must be positive')
    run(args.manifest, args.project_build, args.seconds, args.tap_hz)
