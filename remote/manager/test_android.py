"""APK UI integration on an explicitly selected Android emulator/device.

Uses an isolated manager and a transparent TLS TCP proxy to simulate lost network.
The QR payload enters Android's real VIEW intent; physical camera optics are not tested.
"""
import argparse
import json
import os
from pathlib import Path
import re
import select
import shutil
import socket
import socketserver
import subprocess
import threading
import time
from urllib.parse import parse_qs, urlencode, urlparse
import uuid
import xml.etree.ElementTree as ET
from io import BytesIO
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]


class NetworkProxy(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, upstream):
        self.upstream = upstream
        self.offline = False
        self.connections = set()
        self.lock = threading.Lock()
        self.drop_handshakes = 1
        super().__init__(('0.0.0.0', 0), Forward)

    def interrupt(self):
        with self.lock:
            self.offline = True
            for stream in list(self.connections):
                try:
                    stream.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass


class Forward(socketserver.BaseRequestHandler):
    def handle(self):
        with self.server.lock:
            if self.server.drop_handshakes:
                self.server.drop_handshakes -= 1
                return
        if self.server.offline:
            return
        with socket.create_connection(self.server.upstream, timeout=5) as upstream:
            streams = [self.request, upstream]
            with self.server.lock:
                if self.server.offline:
                    return
                self.server.connections.update(streams)
            try:
                while not self.server.offline:
                    readable, _, _ = select.select(streams, [], [], .2)
                    for source in readable:
                        data = source.recv(65536)
                        if not data:
                            return
                        (upstream if source is self.request else self.request).sendall(data)
            except OSError:
                pass
            finally:
                with self.server.lock:
                    self.server.connections.difference_update(streams)


def run(manifest, adb, serial):
    manifest = Path(manifest).resolve()
    runtime = json.loads(manifest.read_text(encoding='utf-8'))
    work = ROOT/'tmp/remote-android-tests'/uuid.uuid4().hex
    work.mkdir(parents=True)
    quiet = {'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {}

    def device(*args, binary=False):
        result = subprocess.run([adb, '-s', serial, *args], capture_output=True, timeout=30, **quiet)
        assert result.returncode == 0, result.stderr.decode('utf-8', errors='replace')
        return result.stdout if binary else result.stdout.decode('utf-8', errors='replace')

    def ui():
        device('shell', 'uiautomator', 'dump', '/sdcard/remote-test.xml')
        return device('shell', 'cat', '/sdcard/remote-test.xml')

    def wait(check, seconds=60):
        deadline = time.monotonic()+seconds
        while time.monotonic() < deadline:
            result = check()
            if result:
                return result
            time.sleep(.3)
        raise AssertionError('Timed out: '+str(check))

    def text_page(text):
        value = ui()
        return value if text in value else None

    def tap(xml, text):
        for node in ET.fromstring(xml).iter('node'):
            if node.get('text') == text:
                x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
                device('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                return
        raise AssertionError('Missing action: '+text)

    def capture(name):
        data = device('exec-out', 'screencap', '-p', binary=True)
        (work/name).write_bytes(data)
        return Image.open(BytesIO(data)).convert('RGB')

    command = [str(manifest.parent/runtime['java']), '-cp',
               str(manifest.parent/runtime['classes'])+os.pathsep+str(manifest.parent/runtime['jar']),
               'com.defold.editor.RemoteManager', str(manifest), str(work/'state'), '18570']
    log = (work/'manager.log').open('wb')
    child = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                             stderr=log, text=True, encoding='utf-8', **quiet)

    def rpc(method, **params):
        child.stdin.write(json.dumps(dict(method=method, params=params))+'\n')
        child.stdin.flush()
        response = json.loads(child.stdout.readline())
        assert response['ok'], response
        return response['result']

    proxy = None
    passed = []
    try:
        fixture = work/'fixture'
        shutil.copytree(ROOT/'scripts/automation/fixture', fixture)
        project = rpc('add', path=str(fixture))
        pair = rpc('pairing')
        address = urlparse(pair['address'])
        proxy = NetworkProxy(('127.0.0.1', address.port))
        threading.Thread(target=proxy.serve_forever, daemon=True).start()
        fields = {key: values[0] for key, values in parse_qs(urlparse(pair['uri']).query).items()}
        fields['address'] = 'https://'+address.hostname+':'+str(proxy.server_address[1])
        link = 'defoldremote://connect?'+urlencode(fields)
        # Explicit argv/shell quoting: never print credentials or the one-use QR secret.
        device('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW', '-d',
               "'"+link+"'", 'com.defold.remote/.MainActivity')
        wait(lambda: text_page('连接游戏'))
        assert len(rpc('status')['devices']) == 1
        capture('01-project-list.png')
        passed.append('APK QR payload pairs and displays catalog despite an interrupted initial TLS handshake')

        proxy.interrupt()
        wait(lambda: text_page('正在尝试恢复电脑连接'))
        capture('02-network-interrupted.png')
        proxy.offline = False
        catalog = wait(lambda: text_page('连接游戏'))
        capture('03-catalog-restored.png')
        passed.append('Unchanged catalog reappears automatically after network recovery')

        before_retries = device('logcat', '-d', '-s', 'DefoldRemote').count('Retrying startup request')
        proxy.interrupt()
        tap(catalog, '连接游戏')
        # Text updates on a retry screen can keep UIAutomator from becoming idle.
        # Restore the network on the actual retry event, not on an idle UI snapshot.
        wait(lambda: device('logcat', '-d', '-s', 'DefoldRemote').count('Retrying startup request') > before_retries, 15)
        proxy.offline = False
        wait(lambda: any(p['state'] == 'running' for p in rpc('status')['projects']), 180)

        def presented():
            image = capture('04-game.png')
            r, g, b = image.getpixel((image.width//2, image.height//2))
            return image if b > 100 and b > r*1.5 and g > r else None

        screenshot = wait(presented)
        passed.append('Interrupted connection resumes automatic build and presents streamed game')
        device('shell', 'input', 'tap', str(screenshot.width//2), str(screenshot.height//2))
        wait(lambda: 'BUTTON_CLICKED' in json.dumps(rpc('logs', id=project['id'])))
        capture('05-touch-callback.png')
        passed.append('Android touch reaches the background game Lua callback')
        (work/'report.json').write_text(json.dumps({'passed': passed}, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'work': str(work), 'passed': passed}, ensure_ascii=False, indent=2))
    finally:
        (work/'android.log').write_text(device('logcat', '-d', '-t', '1500'), encoding='utf-8')
        if proxy:
            proxy.interrupt()
            proxy.shutdown()
            proxy.server_close()
        child.stdin.close()
        child.wait(timeout=35)
        log.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest')
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    args = parser.parse_args()
    run(args.manifest, args.adb, args.serial)
