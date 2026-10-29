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

    def described(description):
        value = ui()
        return value if any(n.get('content-desc') == description or n.get('text') == description
                            for n in ET.fromstring(value).iter('node')) else None

    def tap_description(description):
        for attempt in range(5):
            value = ui()
            nodes = list(ET.fromstring(value).iter('node'))
            matches = [n for n in nodes if n.get('content-desc') == description]
            if not matches:
                matches = [n for n in nodes if n.get('text') == description and n.get('clickable') == 'true']
            for node in matches:
                    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
                    if x2 > x1 and y2 > y1:
                        device('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                        return
            # Scroll only the app content; keep the fixed tab bar outside the gesture.
            bounds = ET.fromstring(value).find('.//node').get('bounds')
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', bounds))
            device('shell', 'input', 'swipe', str((x1+x2)//2), str(round(y2*.72)),
                   str((x1+x2)//2), str(round(y2*.32)), '350')
        raise AssertionError('Missing described action: '+description)

    def open_link(link):
        # Link credentials stay in memory and are never written to test reports.
        device('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW', '-d',
               "'"+link+"'", 'com.defold.remote/.MainActivity')

    def tabs_present():
        value = ui()
        names = {n.get('content-desc') for n in ET.fromstring(value).iter('node')}
        return value if {'游戏', '运行中', '电脑', '设置'} <= names else None

    def tap_tab(name):
        for attempt in range(3):
            tap_description(name)
            if any(n.get('content-desc') == name and n.get('selected') == 'true'
                   for n in ET.fromstring(ui()).iter('node')):
                return
        raise AssertionError('Tab did not become selected: '+name)

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
    original_size = original_density = None
    original_rotation = None
    try:
        # Keep paired hosts/preferences, reset only this test APK's volatile UI state.
        device('shell', 'am', 'force-stop', 'com.defold.remote')
        if serial.startswith('emulator-'):
            device('shell', 'pm', 'grant', 'com.defold.remote', 'android.permission.CAMERA')
        fixture = work/'fixture'
        shutil.copytree(ROOT/'scripts/automation/fixture', fixture)
        project = rpc('add', path=str(fixture))
        state = rpc('status')
        rpc('connection-address', address=state['addresses'][0])
        pair = rpc('pairing')
        assert pair['qrUri'].startswith('defoldremote://connect?v=3&')
        open_link(pair['qrUri'])
        wait(lambda: described('开始游戏 · '+project['name']))
        assert tabs_present()
        capture('00-compact-pair-catalog.png')
        passed.append('Compact v3 QR payload verifies TLS identity and enters the project list with four fixed tabs')
        tap_description('搜索游戏')
        device('shell', 'input', 'text', 'Background')
        device('shell', 'input', 'keyevent', '4')
        for tab in ('运行中', '电脑', '设置', '游戏'):
            tap_tab(tab)
            assert wait(tabs_present)
        assert any(n.get('content-desc') == '搜索游戏' and n.get('text') == 'Background'
                   for n in ET.fromstring(ui()).iter('node'))
        passed.append('Four tabs retain the game search and paired computer filter')
        capture('00-navigation-retains-search.png')
        pair = rpc('pairing')
        address = urlparse(pair['address'])
        proxy = NetworkProxy(('127.0.0.1', address.port))
        threading.Thread(target=proxy.serve_forever, daemon=True).start()
        fields = {key: values[0] for key, values in parse_qs(urlparse(pair['uri']).query).items()}
        fields['address'] = 'https://'+address.hostname+':'+str(proxy.server_address[1])
        link = 'defoldremote://connect?'+urlencode(fields)
        # Explicit argv/shell quoting: never print credentials or the one-use QR secret.
        open_link(link)
        wait(lambda: described('开始游戏 · '+project['name']))
        assert len(rpc('status')['devices']) == 2
        capture('01-project-list.png')
        passed.append('APK QR payload pairs and displays catalog despite an interrupted initial TLS handshake')

        proxy.interrupt()
        wait(lambda: text_page('连接中断'))
        capture('02-network-interrupted.png')
        proxy.offline = False
        def recovered_catalog():
            value = described('开始游戏 · '+project['name'])
            return value if value and '连接中断' not in value else None
        catalog = wait(recovered_catalog)
        capture('03-catalog-restored.png')
        passed.append('Unchanged catalog reappears automatically after network recovery')

        before_retries = device('logcat', '-d', '-s', 'DefoldRemote').count('Retrying startup request')
        proxy.interrupt()
        tap_description('开始游戏 · '+project['name'])
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
        tap_description('打开游戏控制')
        tap(wait(lambda: text_page('返回游戏列表')), '返回游戏列表')
        wait(tabs_present)
        wait(lambda: rpc('status')['projects'][0]['state'] == 'running'
             and not rpc('status')['projects'][0].get('controller'))
        passed.append('Visible player controls return to the list and release control without stopping the game')
        tap_tab('运行中')
        wait(lambda: described('继续游戏 · '+project['name']))
        capture('06-running-tab.png')

        second_fixture = work/'fixture-b'
        shutil.copytree(ROOT/'scripts/automation/fixture', second_fixture)
        config = second_fixture/'game.project'
        config.write_text(config.read_text().replace('Background automation fixture', 'Background fixture B'))
        second = rpc('add', path=str(second_fixture))
        tap_tab('游戏')
        wait(lambda: described('开始游戏 · '+second['name']))
        tap_description('开始游戏 · '+second['name'])
        wait(lambda: all(p['state'] == 'running' for p in rpc('status')['projects']), 180)
        wait(presented)
        tap_description('打开游戏控制')
        tap(wait(lambda: text_page('停止这款游戏')), '停止这款游戏')
        tap(wait(lambda: text_page('停止游戏')), '停止游戏')
        wait(tabs_present)
        wait(lambda: next(p for p in rpc('status')['projects'] if p['id'] == second['id'])['state'] == 'ready')
        assert next(p for p in rpc('status')['projects'] if p['id'] == project['id'])['state'] == 'running'
        passed.append('Switching starts an independent second game; explicit stop only stops that selected game')
        capture('07-stop-only-selected.png')

        script = second_fixture/'main.gui_script'
        original_script = script.read_bytes()
        # Exercise an explicit failed startup. Otherwise the default source watcher
        # can finish the intentional error before the test presses Start.
        rpc('settings', prepare=False)
        script.write_bytes(original_script+b'\nlocal intentionally_broken =\n')
        tap_tab('游戏')
        wait(lambda: described('开始游戏 · '+second['name']))
        tap_description('开始游戏 · '+second['name'])
        wait(lambda: next(p for p in rpc('status')['projects'] if p['id'] == second['id'])['state'] == 'error', 180)
        wait(lambda: text_page('项目准备未完成'))
        capture('08-project-build-error.png')
        device('shell', 'input', 'keyevent', '3')
        device('shell', 'am', 'start', '-W', '-n', 'com.defold.remote/.MainActivity')
        wait(lambda: text_page('项目准备未完成'))
        assert next(p for p in rpc('status')['projects'] if p['id'] == second['id'])['state'] == 'error'
        assert next(p for p in rpc('status')['projects'] if p['id'] == project['id'])['state'] == 'running'
        script.write_bytes(original_script)
        tap_description('重试')
        wait(lambda: next(p for p in rpc('status')['projects'] if p['id'] == second['id'])['state'] == 'running', 180)
        wait(presented)
        tap_description('打开游戏控制')
        tap(wait(lambda: text_page('返回游戏列表')), '返回游戏列表')
        wait(tabs_present)
        passed.append('Real Lua compiler failure retains project context across background/resume; explicit retry rebuilds repaired source')

        tap_tab('电脑')
        tap_description('添加电脑')
        tap(wait(lambda: text_page('扫描电脑二维码')), '扫描电脑二维码')
        wait(lambda: described('补光'))
        for action in ('1倍缩放', '2倍缩放', '从相册识别', '粘贴完整配对链接'):
            assert described(action), action
        capture('09-camera-controls-landscape.png')
        tap_description('粘贴完整配对链接')
        wait(lambda: text_page('使用配对链接'))
        capture('10-pair-link-entry.png')
        for _ in range(4):
            if tabs_present():
                break
            device('shell', 'input', 'keyevent', '4')
        wait(tabs_present)
        passed.append('Scanner exposes focus/torch/zoom, album and full-link alternatives with a path back to Computers')
        if serial.startswith('emulator-'):
            original_rotation = {key: device('shell', 'settings', 'get', 'system', key).strip()
                                 for key in ('accelerometer_rotation', 'user_rotation')}
            device('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
            device('shell', 'settings', 'put', 'system', 'user_rotation', '0')
            size = device('shell', 'wm', 'size')
            density = device('shell', 'wm', 'density')
            original_size = re.search(r'Override size:\s*(\d+x\d+)', size)
            original_size = original_size.group(1) if original_size else 'reset'
            original_density = re.search(r'Override density:\s*(\d+)', density)
            original_density = original_density.group(1) if original_density else 'reset'
            device('shell', 'wm', 'size', '1080x2340')
            device('shell', 'wm', 'density', '420')
            wait(tabs_present)
            tap_tab('游戏')
            wait(lambda: described('搜索游戏'))
            capture('11-games-portrait.png')
            tap_tab('电脑')
            tap_description('添加电脑')
            tap(wait(lambda: text_page('扫描电脑二维码')), '扫描电脑二维码')
            wait(lambda: described('补光'))
            portrait = capture('12-camera-controls-portrait.png')
            assert portrait.height > portrait.width
            passed.append('Actual APK remains navigable in portrait and landscape; emulator display overrides are restored')
        (work/'report.json').write_text(json.dumps({'passed': passed,
            'scope': 'Real APK on selected Android emulator plus isolated Windows GPU manager. '
                     'QR payload intents do not verify physical camera recognition or Wi-Fi.'}, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'work': str(work), 'passed': passed}, ensure_ascii=False, indent=2))
    finally:
        if original_size is not None:
            device('shell', 'wm', 'size', original_size)
        if original_density is not None:
            device('shell', 'wm', 'density', original_density)
        if original_rotation is not None:
            for key, value in original_rotation.items():
                if value == 'null':
                    device('shell', 'settings', 'delete', 'system', key)
                else:
                    device('shell', 'settings', 'put', 'system', key, value)
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
