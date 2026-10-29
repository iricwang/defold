"""Connect the installed Android APK to an actual project through an isolated Manager.

The supplied source directory is only passed to Manager.add: Manager owns the build
snapshot and all test output stays below tmp/remote-android-project-tests. This test
does not edit the source, inject a precompiled game, click game business controls,
or replace camera testing with an assertion about physical QR recognition.
"""
import argparse
import base64
from io import BytesIO
import json
import os
from pathlib import Path
import queue
import re
import subprocess
import threading
import time
from urllib.parse import parse_qs, urlparse
import uuid
import xml.etree.ElementTree as ET

from PIL import Image, ImageStat

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'com.defold.remote'
QUIET = {'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {}


class ManagerPipe:
    """One ordered RPC stream, bounded even if the owned Java child wedges."""
    def __init__(self, manifest, runtime, work, main_class='com.defold.editor.RemoteManager', extra_classpath=()):
        self.log = (work/'manager.log').open('wb')
        classpath = os.pathsep.join([*map(str, extra_classpath), str(manifest.parent/runtime['classes']),
                                    str(manifest.parent/runtime['jar'])])
        command = [str(manifest.parent/runtime['java']), '--enable-native-access=ALL-UNNAMED',
                   '-cp', classpath, main_class, str(manifest), str(work/'state'), '18680']
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=self.log, text=True, encoding='utf-8', **QUIET)
        self.replies = queue.Queue()
        self.lock = threading.Lock()
        self.failed = False
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self):
        try:
            for line in self.process.stdout:
                self.replies.put(line)
        finally:
            self.replies.put(None)

    def rpc(self, method, timeout=45, **params):
        with self.lock:
            if self.failed:
                raise RuntimeError('Manager RPC stream already failed; inspect private manager.log')
            try:
                self.process.stdin.write(json.dumps({'method': method, 'params': params})+'\n')
                self.process.stdin.flush()
                line = self.replies.get(timeout=timeout)
                if line is None:
                    raise RuntimeError('Owned Manager exited; inspect private manager.log')
                reply = json.loads(line)
            except (OSError, queue.Empty, json.JSONDecodeError) as error:
                self.failed = True
                raise RuntimeError('Manager RPC failed or exceeded its bounded timeout: '+method) from error
            if not reply.get('ok'):
                raise RuntimeError(str(reply.get('error', 'Manager rejected '+method)))
            return reply['result']

    def close(self, project_id):
        if self.process.poll() is None and project_id and not self.failed:
            try:
                self.rpc('stop', id=project_id, timeout=10)
            except Exception:
                pass
        try:
            self.process.stdin.close()
        except OSError:
            pass
        forced = False
        try:
            self.process.wait(timeout=40)
        except subprocess.TimeoutExpired:
            forced = True
            # This is the child launched above, never the user's existing Manager.
            self.process.kill()
            self.process.wait(timeout=5)
        self.log.close()
        return forced


def image_metrics(image):
    image = image.convert('RGB')
    width, height = image.size
    center = image.crop((width//8, height//8, width*7//8, height*7//8))
    sample = center.resize((160, 120))
    return {'width': width, 'height': height,
            'centerDistinctColors': len(sample.getcolors(160*120) or []),
            'centerStddev': max(ImageStat.Stat(sample).stddev)}


def run(manifest, project, adb, serial, timeout=1260, expected_version=None,
        main_class='com.defold.editor.RemoteManager', extra_classpath=(), require_prepared_only_restart=False):
    manifest, project = Path(manifest).resolve(), Path(project).resolve()
    if not (project/'game.project').is_file():
        raise ValueError('Choose an actual directory containing game.project')
    extra_classpath = tuple(Path(entry).resolve() for entry in extra_classpath)
    if any(not entry.exists() for entry in extra_classpath):
        raise ValueError('An --extra-classpath entry does not exist')
    if not re.fullmatch(r'[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+', main_class):
        raise ValueError('Choose a fully qualified Java --main-class')
    runtime = json.loads(manifest.read_text(encoding='utf-8'))
    work = ROOT/'tmp/remote-android-project-tests'/uuid.uuid4().hex
    work.mkdir(parents=True)
    started = time.monotonic()
    secrets = []
    stages, checks = [], []
    project_id = None
    manager = None
    report = {'sourceProject': str(project), 'manifest': str(manifest),
              'runtimeRevision': runtime.get('revision'), 'serial': serial,
              'managerMainClass': main_class, 'extraClasspath': list(map(str, extra_classpath)),
              'scope': 'Installed APK, independent Windows/macOS Manager, actual project read only. '
                       'No physical camera recognition or real-phone Wi-Fi claim.',
              'stages': stages, 'passed': checks, 'outcome': 'failed'}

    def redact(value):
        text = str(value)
        for secret in secrets:
            if secret:
                text = text.replace(secret, '[redacted]')
        return re.sub(r'defoldremote://[^\s\"<>]+', '[redacted pairing URI]', text)

    def device(*args, binary=False):
        result = subprocess.run([adb, '-s', serial, *args], capture_output=True, timeout=35, **QUIET)
        if result.returncode:
            raise RuntimeError('ADB operation failed: '+str(args[0])+'; '+redact(result.stderr.decode('utf-8', errors='replace')))
        return result.stdout if binary else result.stdout.decode('utf-8', errors='replace')

    def capture(filename):
        data = device('exec-out', 'screencap', '-p', binary=True)
        (work/filename).write_bytes(data)
        return Image.open(BytesIO(data)).convert('RGB')

    def ui():
        device('shell', 'uiautomator', 'dump', '/sdcard/remote-project-test.xml')
        text = device('shell', 'cat', '/sdcard/remote-project-test.xml')
        return text, list(ET.fromstring(text).iter('node'))

    def project_status():
        return next(p for p in manager.rpc('status')['projects'] if p['id'] == project_id)

    def stage(current):
        point = {key: current.get(key) for key in ('state', 'stage', 'busy', 'modified', 'failed_stage', 'error_kind')}
        point['hasSession'] = bool(current.get('session_id'))
        if not stages or any(stages[-1].get(key) != value for key, value in point.items()):
            point['elapsedSeconds'] = round(time.monotonic()-started, 2)
            stages.append(point)
            print(json.dumps({'progress': point}, ensure_ascii=False), flush=True)

    def fail_on_project_error(current):
        if current.get('state') == 'error':
            raise RuntimeError('Actual project failed at '+str(current.get('failed_stage'))+': '+str(current.get('error', '')))

    try:
        version_text = device('shell', 'dumpsys', 'package', PACKAGE)
        version = re.search(r'\bversionName=([^\s]+)', version_text)
        if not version:
            raise RuntimeError('Defold Remote APK is not installed on the selected device')
        report['apkVersion'] = version.group(1)
        if expected_version and version.group(1) != expected_version:
            raise RuntimeError('Installed APK version differs from --expected-version')
        clock = device('shell', 'date', '+%s').strip()
        if clock.isdigit() and abs(int(clock)-time.time()) > 300:
            raise RuntimeError('Android clock differs from host by over five minutes; correct the device clock, keeping TLS validation enabled')
        device('shell', 'am', 'force-stop', PACKAGE)
        manager = ManagerPipe(manifest, runtime, work, main_class, extra_classpath)
        entry = manager.rpc('add', path=str(project))
        project_id, name = entry['id'], entry.get('name', '').strip() or 'Defold 游戏'
        report.update(projectId=project_id, projectName=name)
        state = manager.rpc('status')
        manager.rpc('connection-address', address=state['addresses'][0])
        pairing = manager.rpc('pairing')
        link = pairing.get('qrUri') or pairing['uri']
        secrets.extend([link, pairing.get('uri', '')])
        for key, values in parse_qs(urlparse(pairing.get('uri', '')).query).items():
            if key == 'code':
                secrets.extend(values)
        # Never echo this command or raw pairing response: both contain one-use credentials.
        device('shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW', '-d',
               "'"+link+"'", PACKAGE+'/.MainActivity')
        pairing_deadline = time.monotonic()+60
        actions = ['开始游戏 · '+name, '查看准备进度 · '+name, '继续游戏 · '+name]
        selected = None
        while time.monotonic() < pairing_deadline:
            xml, nodes = ui()
            if '连接电脑未完成' in xml:
                raise RuntimeError('APK pairing failed: '+' | '.join(n.get('text', '') for n in nodes if n.get('text')))
            selected = next((n for n in nodes if n.get('content-desc') in actions and n.get('enabled') == 'true'), None)
            if selected is not None:
                break
            current = project_status()
            stage(current)
            fail_on_project_error(current)
            time.sleep(.5)
        if selected is None:
            raise AssertionError('Actual project never appeared as a startable row in the paired computer catalog')
        capture('01-project-list.png')
        checks.append('Actual project is listed after compact QR/TLS pairing')
        # Capturing can span an orientation/layout transition after a previous game.
        # Use a fresh hierarchy for the single click, rather than its pre-capture bounds.
        _, nodes = ui()
        selected = next((n for n in nodes if n.get('content-desc') in actions
                         and n.get('enabled') == 'true'), None)
        if selected is None:
            raise AssertionError('Project action disappeared before the startup click')
        x1, y1, x2, y2 = map(int, re.findall(r'\d+', selected.get('bounds')))
        if x2 <= x1 or y2 <= y1:
            raise AssertionError('Actual project action has no visible touch area')
        report['clickedActionBounds'] = [x1, y1, x2, y2]
        device('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
        # A single actual APK click initiates the run. Do not hide failures by calling
        # Manager.start or injecting prebuilt resources from the test process.
        capture('02-preparing.png')
        deadline = time.monotonic()+timeout
        heartbeat = time.monotonic()
        idle_ready_since = None
        while time.monotonic() < deadline:
            current = project_status()
            stage(current)
            fail_on_project_error(current)
            if current['state'] == 'running':
                break
            if current['state'] in ('new', 'ready', 'dirty') and not current.get('busy'):
                if idle_ready_since is None:
                    idle_ready_since = time.monotonic()
                elif time.monotonic()-idle_ready_since > 30:
                    raise AssertionError('APK remained waiting while the actual project was idle and startable for over 30 seconds')
            else:
                idle_ready_since = None
            if time.monotonic()-heartbeat >= 30:
                print(json.dumps({'waitingSeconds': round(time.monotonic()-started),
                                  'state': current.get('state'), 'stage': current.get('stage')}, ensure_ascii=False), flush=True)
                heartbeat = time.monotonic()
            time.sleep(.5)
        else:
            raise AssertionError('Actual project did not reach running before the bounded cold-build deadline')
        checks.append('One APK project click automatically builds the actual source and creates a game session')
        report['runningStatus'] = {key: current.get(key) for key in ('id', 'name', 'state', 'busy', 'modified', 'session_id', 'width', 'height', 'builtAt')}
        # Handle appears only from MainActivity.firstFrame after JPEG presentation or
        # MediaCodec output, not from the generic connection UI.
        first_frame_deadline = time.monotonic()+60
        phone_metrics = engine_metrics = None
        while time.monotonic() < first_frame_deadline:
            current = project_status()
            stage(current)
            fail_on_project_error(current)
            xml, nodes = ui()
            if '项目准备未完成' in xml or '连接电脑未完成' in xml:
                raise RuntimeError('APK connection failed after build: '+' | '.join(n.get('text', '') for n in nodes if n.get('text')))
            if any(n.get('content-desc') == '打开游戏控制' for n in nodes):
                photo = capture('03-phone-game.png')
                phone_metrics = image_metrics(photo)
                thumbnail = manager.rpc('thumbnail', id=project_id).get('image', '')
                if thumbnail.startswith('data:image/'):
                    raw = base64.b64decode(thumbnail.split(',', 1)[1])
                    (work/'04-manager-game.jpg').write_bytes(raw)
                    engine_metrics = image_metrics(Image.open(BytesIO(raw)))
                if (phone_metrics['centerDistinctColors'] > 16 and phone_metrics['centerStddev'] > 1.5
                        and engine_metrics and engine_metrics['centerDistinctColors'] > 16 and engine_metrics['centerStddev'] > 1.5):
                    break
            time.sleep(.5)
        else:
            raise AssertionError('Actual project is running, but APK did not present a nonblank decoded frame')
        checks.append('APK decodes a nonblank first frame and Manager independently captures the actual game image')
        if require_prepared_only_restart:
            stats = manager.rpc('test-start-stats', id=project_id)
            report['preparedOnlyRestart'] = stats
            if not (stats.get('firstStartPreparedOnly') and stats.get('startRequests', 0) >= 2
                    and stats.get('idleBeforeRetry')):
                raise AssertionError('APK did not send another Start after the first real resource-only build became idle')
            checks.append('APK sends another Start from idle ready after the test host loses the first run intent; real compiler and game renderer succeed')
        report.update(phoneImageMetrics=phone_metrics, engineImageMetrics=engine_metrics, outcome='passed')
        print(json.dumps({'work': str(work), 'passed': checks}, ensure_ascii=False), flush=True)
    except Exception as error:
        report['error'] = redact(error)
        try:
            capture('failure.png')
            xml, _ = ui()
            (work/'failure-ui.xml').write_text(redact(xml), encoding='utf-8')
        except Exception:
            pass
        print(json.dumps({'work': str(work), 'error': report['error']}, ensure_ascii=False), flush=True)
        raise
    finally:
        report['elapsedSeconds'] = round(time.monotonic()-started, 2)
        # Do not dump global logcat, which may include other apps or pairing Intents.
        try:
            logs = device('logcat', '-d', '-t', '600', '-s', 'DefoldRemote', 'AndroidRuntime')
            (work/'android.log').write_text(redact(logs), encoding='utf-8')
            device('shell', 'am', 'force-stop', PACKAGE)
        except Exception:
            pass
        cleanup_error = None
        if manager is not None:
            try:
                report['managerForcedCleanup'] = manager.close(project_id)
                if report['managerForcedCleanup']:
                    cleanup_error = 'Owned test Manager required forced termination during cleanup'
            except Exception as error:
                cleanup_error = redact(error)
            if cleanup_error:
                report['cleanupError'] = cleanup_error
                report['outcome'] = 'failed'
        (work/'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        if cleanup_error:
            raise RuntimeError(cleanup_error)
    return work


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('--project', required=True, type=Path)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--timeout', type=int, default=1260, help='Bounded cold-build wait in seconds')
    parser.add_argument('--expected-version', help='Optional installed APK version guard')
    parser.add_argument('--main-class', default='com.defold.editor.RemoteManager', help='Optional test-only Manager main class')
    parser.add_argument('--extra-classpath', action='append', type=Path, default=[], help='Optional test class directory; repeat for multiple entries')
    parser.add_argument('--require-prepared-only-restart', action='store_true', help='Assert RemotePreparedOnlyTestHost observed a ready-state Start retry')
    args = parser.parse_args()
    run(args.manifest, args.project, args.adb, args.serial, args.timeout, args.expected_version,
        args.main_class, args.extra_classpath, args.require_prepared_only_restart)
