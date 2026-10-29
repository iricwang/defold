"""Hidden, real-TLS pairing regression. Does not start games or control a user application."""
import argparse
import base64
import hashlib
import http.client
import json
import os
from pathlib import Path
import ssl
import struct
import subprocess
import uuid
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parents[2]


def run(manifest):
    manifest = Path(manifest).resolve()
    runtime = json.loads(manifest.read_text(encoding='utf-8'))
    work = ROOT / 'tmp/remote-pairing-tests' / uuid.uuid4().hex
    work.mkdir(parents=True)
    checks = []
    quiet = {'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {}
    with (work / 'manager.log').open('w', encoding='utf-8') as log:
        child = subprocess.Popen([str(manifest.parent / runtime['java']), '--enable-native-access=ALL-UNNAMED', '-cp',
                                  str(manifest.parent / runtime['classes']) + os.pathsep + str(manifest.parent / runtime['jar']),
                                  'com.defold.editor.RemoteManager', str(manifest), str(work/'state'), '18550'],
                                 stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=log, text=True, encoding='utf-8', **quiet)
        def rpc(method, **params):
            child.stdin.write(json.dumps(dict(method=method, params=params)) + '\n')
            child.stdin.flush()
            line = child.stdout.readline()
            if not line:
                raise RuntimeError('Pairing test service stopped; inspect its private manager.log')
            result = json.loads(line)
            if not result['ok']:
                raise RuntimeError(result.get('error', 'Pairing RPC failed'))
            return result['result']
        def check(name, passed):
            if not passed:
                raise AssertionError(name)
            checks.append(name)
        def pair(code):
            connection = http.client.HTTPSConnection('127.0.0.1', port, context=ssl._create_unverified_context(), timeout=10)
            connection.connect()
            check('actual TLS certificate matches full QR pin', hashlib.sha256(connection.sock.getpeercert(binary_form=True)).hexdigest() == fingerprint)
            connection.request('POST', '/pair', json.dumps(dict(code=code, name='Pairing regression phone')))
            response = connection.getresponse()
            status, payload = response.status, json.loads(response.read())
            connection.close()
            return status, payload
        try:
            state = rpc('status')
            port, fingerprint = state['port'], state['fingerprint']
            check('a recommended current LAN address is selected', state['connectionAddress'] in state['addresses'])
            first = rpc('pairing')
            old = parse_qs(urlparse(first['uri']).query)
            compact = parse_qs(urlparse(first['qrUri']).query)
            raw = base64.urlsafe_b64decode(compact['d'][0] + '==')
            check('compact QR keeps full certificate identity', len(raw) == 70 and raw[38:].hex() == fingerprint == old['fingerprint'][0])
            check('compact QR keeps full one-use secret', base64.urlsafe_b64encode(raw[6:38]).decode().rstrip('=') == old['code'][0])
            check('compact QR keeps selected origin and port', first['address'] == 'https://' + '.'.join(map(str, raw[:4])) + ':' + str(struct.unpack('>H', raw[4:6])[0]))
            check('compact QR is shorter without shortening credentials', len(first['qrUri']) < len(first['uri']) * .65)
            progress = rpc('pairing-status')
            check('waiting feedback is non-secret', progress['state'] == 'waiting' and set(progress) == {'state', 'expiresAt', 'deviceName'})
            refreshed = rpc('pairing')
            code = parse_qs(urlparse(refreshed['uri']).query)['code'][0]
            status, _ = pair(old['code'][0])
            check('manual refresh invalidates original code', status == 403)
            status, auth = pair(code)
            check('actual TLS pair succeeds and returns computer identity', status == 200 and auth['computer'] == state['computer'] and auth['token'])
            progress = rpc('pairing-status')
            check('paired feedback names the accepted phone', progress['state'] == 'paired' and progress['deviceName'] == 'Pairing regression phone')
            status, _ = pair(code)
            check('accepted credential is single-use', status == 403)
            next_pair = rpc('pairing')
            check('adding another phone returns to waiting', rpc('pairing-status')['state'] == 'waiting')
            rpc('connection-address', address=state['connectionAddress'])
            status, _ = pair(parse_qs(urlparse(next_pair['uri']).query)['code'][0])
            check('address reselection invalidates prior QR', status == 403)
            report = dict(passed=checks, scope='Real local TLS and hidden manager; no phone camera or Wi-Fi tested')
            (work/'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
            print(json.dumps(dict(work=str(work), **report), indent=2))
        finally:
            child.stdin.close()
            try:
                child.wait(timeout=20)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=5)
                raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest')
    run(parser.parse_args().manifest)
