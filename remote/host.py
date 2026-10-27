#!/usr/bin/env python3
"""LAN-only controller for one locally configured Defold game. Python 3.10+."""
import argparse
import hmac
import http.client
import json
import os
import platform
from pathlib import Path
import secrets
import socket
import struct
import subprocess
import threading
import tempfile
import zipfile
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import zlib

MAX_FRAME = 16 + 960 * 960 * 4


def frame_png(frame):
    if len(frame) < 16 or frame[:4] != b'DFR1':
        raise ValueError('Invalid engine frame')
    width, height, sequence = struct.unpack_from('<III', frame, 4)
    if not (1 <= width <= 960 and 1 <= height <= 960) or len(frame) != 16 + width * height * 4:
        raise ValueError('Invalid engine frame dimensions')
    pixels = frame[16:]
    rgba = bytearray(len(pixels))
    rgba[0::4], rgba[1::4], rgba[2::4] = pixels[2::4], pixels[1::4], pixels[0::4]
    rgba[3::4] = b'\xff' * (width * height)
    rows = b''.join(b'\0' + rgba[y * width * 4:(y + 1) * width * 4] for y in range(height))

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))

    return sequence, (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
                      + chunk(b'IDAT', zlib.compress(rows, 1)) + chunk(b'IEND', b''))


class RemoteHost:
    def __init__(self, command, cwd, engine_port, log_path):
        self.command, self.cwd, self.engine_port = command, cwd, engine_port
        self.log_path = log_path
        self.code = ''.join(secrets.choice('0123456789') for _ in range(8))
        self.token = None
        self.engine_token = secrets.token_hex(32)
        self.process = None
        self.lifecycle = threading.Lock()
        self.engine_io = threading.Lock()
        self.auth_lock = threading.Lock()
        self.attempts = []
        self.png = None
        self.sequence = None
        self.state = 'stopped'
        self.error = None

    def pair(self, code):
        with self.auth_lock:
            now = time.monotonic()
            self.attempts = [at for at in self.attempts if now - at < 30]
            if len(self.attempts) >= 5:
                return 429, {'error': 'Too many pairing attempts; wait 30 seconds'}
            self.attempts.append(now)
            if not isinstance(code, str) or not hmac.compare_digest(code.encode(), self.code.encode()):
                return 403, {'error': 'Incorrect pairing code'}
            self.token = secrets.token_urlsafe(32)
            return 200, {'token': self.token, 'name': socket.gethostname(), 'protocol': 1}

    def authorized(self, value):
        with self.auth_lock:
            return self.token is not None and hmac.compare_digest(value.encode(), ('Bearer ' + self.token).encode())

    def engine_request(self, method, path, body=None):
        with self.engine_io:
            connection = http.client.HTTPConnection('127.0.0.1', self.engine_port, timeout=2)
            try:
                connection.request(method, path, body=body, headers={'X-Defold-Remote-Token': self.engine_token})
                response = connection.getresponse()
                data = response.read(MAX_FRAME + 1)
                if len(data) > MAX_FRAME:
                    raise ValueError('Engine response too large')
                return response.status, data
            finally:
                connection.close()

    def status(self):
        if self.state in ('starting', 'stopping'):
            return {'state': self.state, 'error': None, 'protocol': 1, 'audio': False}
        with self.lifecycle:
            if self.process is not None and self.process.poll() is not None:
                code = self.process.returncode
                self.process = None
                self.state = 'stopped' if code == 0 else 'error'
                self.error = None if code == 0 else 'Game exited with code %s; see %s' % (code, self.log_path)
            return {'state': self.state, 'error': self.error, 'protocol': 1, 'audio': False}

    def start(self):
        with self.lifecycle:
            if self.process is not None and self.process.poll() is None:
                return {'state': self.state}
            # Do not send commands to another editor/game that already uses this port.
            with socket.socket() as probe:
                if probe.connect_ex(('127.0.0.1', self.engine_port)) == 0:
                    raise ValueError('Engine port is occupied; choose another --engine-port')
            env = os.environ.copy()
            env.update(DM_SERVICE_PORT=str(self.engine_port), DM_REMOTE_TOKEN=self.engine_token)
            for key in ('DM_EDITOR_GAME_FILE', 'DM_EDITOR_GAME_PARENT'):
                env.pop(key, None)
            self.png = self.sequence = None
            self.error = None
            self.state = 'starting'
            try:
                with open(self.log_path, 'ab', buffering=0) as log:
                    self.process = subprocess.Popen(self.command, cwd=self.cwd, env=env,
                                                    stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 15
                while self.process.poll() is None and time.monotonic() < deadline:
                    try:
                        code, body = self.engine_request('GET', '/remote/status')
                        if code == 200 and json.loads(body).get('protocol') == 1:
                            self.state = 'running'
                            return {'state': 'running'}
                    except (OSError, ValueError, http.client.HTTPException):
                        pass
                    threading.Event().wait(0.05)
                raise ValueError('Game failed to start Remote protocol; see %s' % self.log_path)
            except Exception as exc:
                self._terminate()
                self.state, self.error = 'error', str(exc)
                raise

    def _terminate(self):
        if self.process is not None and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=3)
        self.process = None

    def stop(self):
        with self.lifecycle:
            self.state = 'stopping'
            if self.process is not None and self.process.poll() is None:
                try:
                    status, _ = self.engine_request('GET', '/remote/status')
                    if status == 200:
                        self.engine_request('POST', '/post/@system/exit', b'\x08\x00')
                        self.process.wait(timeout=3)
                except (OSError, ValueError, http.client.HTTPException, subprocess.TimeoutExpired):
                    pass
                finally:
                    self._terminate()
            self.state, self.error = 'stopped', None
            self.png = self.sequence = None
            return {'state': 'stopped'}

    def frame(self):
        if self.status()['state'] != 'running':
            return 204, b''
        code, frame = self.engine_request('GET', '/remote/frame')
        if code == 204:
            return 204, b''
        if code != 200:
            raise ValueError('Engine rejected frame request (%s)' % code)
        # Cache and encode under a separate frame lock owned by the HTTP server.
        sequence, = struct.unpack_from('<I', frame, 12) if len(frame) >= 16 else (None,)
        if sequence != self.sequence or self.png is None:
            self.sequence, self.png = frame_png(frame)
        return 200, self.png


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, host):
        self.host = host
        self.slots = threading.BoundedSemaphore(12)
        self.frame_lock = threading.Lock()
        self.frames = threading.Condition()
        self.subscribers = 0
        self.latest = None
        self.frame_serial = 0
        self.closed = False
        super().__init__(address, Handler)
        self.producer = threading.Thread(target=self.produce, daemon=True)
        self.producer.start()

    def produce(self):
        # A slow receiver never queues old frames or stalls capture/input.
        while True:
            with self.frames:
                self.frames.wait_for(lambda: self.closed or self.subscribers)
                if self.closed:
                    return
            try:
                with self.frame_lock:
                    code, png = self.host.frame()
                    sequence = self.host.sequence
                with self.frames:
                    key = (self.host.process, sequence)
                    if code == 200 and (self.latest is None or self.latest[0] != key):
                        self.frame_serial += 1
                        self.latest = (key, struct.pack('>II', len(png), sequence) + png)
                        self.frames.notify_all()
                    else:
                        self.frames.wait(timeout=0.004 if code == 200 else 0.1)
            except (ValueError, OSError, http.client.HTTPException):
                with self.frames:
                    self.frames.wait(timeout=0.1)

    def server_close(self):
        with self.frames:
            self.closed = True
            self.frames.notify_all()
        super().server_close()
        self.producer.join(timeout=3)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            request.close()
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


class Handler(BaseHTTPRequestHandler):
    server_version = 'DefoldRemote/1'

    def setup(self):
        super().setup()
        self.connection.settimeout(20)

    def log_message(self, *_):
        pass  # Tokens and pairing codes must not enter access logs.

    def reply(self, status, body, mime='application/json'):
        data = json.dumps(body).encode() if isinstance(body, dict) else body
        self.send_response(status)
        self.send_header('Content-Type', mime)
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Connection', 'close')
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.dispatch('GET')

    def do_POST(self):
        self.dispatch('POST')

    def stream(self):
        server = self.server
        authorization = self.headers.get('Authorization', '')
        self.connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.connection.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 65536)
        self.connection.settimeout(2)
        self.send_response(200)
        self.send_header('Content-Type', 'application/x-defold-frames')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Connection', 'close')
        self.end_headers()
        with server.frames:
            serial = server.frame_serial
            server.subscribers += 1
            server.frames.notify_all()
        try:
            while server.host.authorized(authorization):
                with server.frames:
                    server.frames.wait_for(lambda: server.closed or server.frame_serial != serial, timeout=0.5)
                    if server.closed:
                        return
                    changed = serial != server.frame_serial
                    serial = server.frame_serial
                    packet = server.latest[1] if changed and server.latest is not None and server.host.state == 'running' else None
                # Zero-length heartbeat keeps stopped-game streams alive.
                self.wfile.write(packet if packet is not None else struct.pack('>II', 0, 0))
        except OSError:
            pass
        finally:
            with server.frames:
                server.subscribers -= 1
                server.frames.notify_all()

    def dispatch(self, method):
        host = self.server.host
        try:
            if self.headers.get('Transfer-Encoding'):
                return self.reply(400, {'error': 'Chunked requests are unsupported'})
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 <= size <= 1024:
                return self.reply(413, {'error': 'Request too large'})
            if self.path != '/pair' and not host.authorized(self.headers.get('Authorization', '')):
                return self.reply(403, {'error': 'Pair with this computer first'})
            routes = {'/pair': 'POST', '/status': 'GET', '/start': 'POST', '/stop': 'POST', '/frame': 'GET', '/stream': 'GET', '/input': 'POST'}
            if self.path not in routes:
                return self.reply(404, {'error': 'Unknown endpoint'})
            if method != routes[self.path]:
                return self.reply(405, {'error': 'Incorrect HTTP method'})
            body = self.rfile.read(size)
            if len(body) != size:
                return self.reply(400, {'error': 'Incomplete body'})
            if self.path == '/pair':
                request = json.loads(body)
                if not isinstance(request, dict):
                    raise ValueError('Pairing requires a JSON object')
                code, result = host.pair(request.get('code'))
                return self.reply(code, result)
            if self.path == '/status':
                return self.reply(200, host.status())
            if self.path == '/start':
                return self.reply(200, host.start())
            if self.path == '/stop':
                return self.reply(200, host.stop())
            if self.path == '/stream':
                return self.stream()
            if self.path == '/frame':
                if not self.server.frame_lock.acquire(blocking=False):
                    return self.reply(204, b'')
                try:
                    code, frame = host.frame()
                    return self.reply(code, frame, 'image/png')
                finally:
                    self.server.frame_lock.release()
            if self.path == '/input':
                if len(body) != 5 or body[4] > 1:
                    return self.reply(400, {'error': 'Invalid pointer packet'})
                if host.status()['state'] != 'running':
                    return self.reply(409, {'error': 'Game is not running'})
                code, _ = host.engine_request('POST', '/remote/input', body)
                return self.reply(code, b'')
        except (ValueError, OSError, http.client.HTTPException) as exc:
            try:
                self.reply(400 if isinstance(exc, ValueError) else 503, {'error': str(exc)})
            except OSError:
                pass


def discover(bind, port, http_port, stop):
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
        try:
            udp.bind((bind, port))
        except OSError as exc:
            print('Discovery unavailable; connect by IP:', exc, flush=True)
            return
        udp.settimeout(0.5)
        payload = json.dumps({'service': 'defold-remote', 'protocol': 1,
                              'name': socket.gethostname(), 'port': http_port}).encode()
        while not stop.is_set():
            try:
                data, address = udp.recvfrom(64)
                if data == b'DEFOLD_REMOTE_DISCOVER_1':
                    udp.sendto(payload, address)
            except socket.timeout:
                pass


def editor_command(editor, project, directory):
    """Extract only the installed editor's debug engine into an owner-only temp directory."""
    editor = editor.resolve()
    project = project.resolve()
    if project.name == 'game.project':
        project = project.parent / 'build/default/game.projectc'
    if not project.is_file() or project.suffix != '.projectc':
        raise ValueError('Build the game in Defold first; expected game.projectc or game.project')
    system = platform.system()
    arch = 'arm64' if platform.machine().lower() in ('arm64', 'aarch64') else 'x86_64'
    if system == 'Darwin':
        packages = editor / 'Contents/Resources/packages'
        target, executable = arch + '-macos', 'dmengine'
    elif system == 'Windows':
        packages = editor / 'packages'
        target, executable = 'x86_64-win32', 'dmengine.exe'
    elif system == 'Linux':
        packages = editor / 'packages'
        target, executable = arch + '-linux', 'dmengine'
    else:
        raise ValueError('Unsupported desktop platform')
    jars = list(packages.glob('defold-*.jar'))
    if len(jars) != 1:
        raise ValueError('Expected one Defold editor JAR in %s' % packages)
    destination = Path(directory) / executable
    with zipfile.ZipFile(jars[0]) as archive:
        entry = 'libexec/%s/%s' % (target, executable)
        destination.write_bytes(archive.read(entry))
    destination.chmod(0o700)
    return [str(destination), '--config=engine.run_while_iconified=1', str(project)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bind', default='0.0.0.0')
    parser.add_argument('--port', type=int, default=8390)
    parser.add_argument('--discovery-port', type=int, default=8391)
    parser.add_argument('--engine-port', type=int, default=8392)
    parser.add_argument('--editor', type=Path, help='Installed Defold app/folder (requires --project)')
    parser.add_argument('--project', type=Path, help='Already-built game.project or game.projectc')
    parser.add_argument('--cwd', type=Path, default=Path.cwd())
    parser.add_argument('--log', type=Path, default=Path('remote-game.log'))
    parser.add_argument('command', nargs=argparse.REMAINDER, help='-- /path/to/dmengine /path/to/game.projectc')
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if not command and not (args.editor and args.project):
        parser.error('Supply --editor and --project, or the engine command after --')
    if command and (args.editor or args.project):
        parser.error('Choose either --editor/--project or an explicit engine command')
    engine_directory = tempfile.TemporaryDirectory(prefix='defold-remote-')
    if not command:
        try:
            command = editor_command(args.editor, args.project, engine_directory.name)
        except (OSError, ValueError, KeyError) as exc:
            parser.error(str(exc))
    if any(not 1 <= port <= 65535 for port in (args.port, args.engine_port, args.discovery_port)):
        parser.error('Ports must be between 1 and 65535')
    if args.port == args.engine_port:
        parser.error('HTTP and engine ports must differ')
    host = RemoteHost(command, args.cwd, args.engine_port, args.log)
    server = Server((args.bind, args.port), host)
    stop = threading.Event()
    discovery = threading.Thread(target=discover, args=(args.bind, args.discovery_port, args.port, stop), daemon=True)
    discovery.start()
    print('Defold Remote — TCP %s, discovery UDP %s\nPairing code: %s\nKeep this window open. Ctrl+C stops the host and its game.'
          % (args.port, args.discovery_port, host.code), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        server.server_close()
        host.stop()
        discovery.join(timeout=1)
        engine_directory.cleanup()


if __name__ == '__main__':
    main()
