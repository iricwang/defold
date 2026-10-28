#!/usr/bin/env python3
# Copyright 2020-2026 The Defold Foundation
# Copyright 2014-2020 King
# Copyright 2009-2014 Ragnar Svensson, Christian Murray
# Licensed under the Defold License version 1.0 (the "License"); you may not use
# this file except in compliance with the License.
#
# You may obtain a copy of the License, together with FAQs at
# https://www.defold.com/license
#
# Unless required by applicable law or agreed to in writing, software distributed
# under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
# CONDITIONS OF ANY KIND, either express or implied. See the License for the
# specific language governing permissions and limitations under the License.
"""Loopback-only debug extension builder using this checkout's Windows CMake SDK.

Supports source extensions with symbols/defines/includes and packaged .lib files.
Unsupported manifest features fail explicitly; this is not a cloud fallback.
"""
from concurrent.futures import ThreadPoolExecutor
from email import policy
from email.parser import BytesParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
from pathlib import Path, PurePosixPath
from urllib.parse import parse_qs, urlsplit
import hashlib
import json
import os
import re
import shutil
import subprocess
import threading
import time
import uuid
import zipfile

import yaml

ROOT = Path(__file__).resolve().parents[2]
MAX_BYTES = 128 * 1024 * 1024
PLATFORM = 'x86_64-win32'


def windows():
    return PLATFORM.endswith('-win32')


def engine_filename():
    return 'dmengine.exe' if windows() else 'dmengine'


def file_hash(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def sdk_revision(home):
    # Bob is an installed SDK artifact; the generated CMake header follows HEAD
    # and may change after an editor-only commit without rebuilding the SDK.
    with zipfile.ZipFile(home / 'share/java/bob.jar') as archive:
        version = archive.read('com/dynamo/bob/archive/EngineVersion.class')
    revisions = set(re.findall(rb'\x01\x00\x28([0-9a-f]{40})', version))
    if len(revisions) != 1:
        raise ValueError('Cannot identify the installed Bob SDK revision')
    revision = revisions.pop().decode('ascii')
    if revision.encode('ascii') + b'\x00' not in (home / 'bin' / PLATFORM / engine_filename()).read_bytes():
        raise ValueError('Installed Bob and engine SDK revisions differ')
    return revision


def check_engine_libraries(home, binary_dir):
    # The version and SDK identity live in these linked libraries, not in
    # engine_main.cpp's (unused) generated version constants.
    for name in (('engine.lib', 'engine_service.lib') if windows() else ('libengine.a', 'libengine_service.a')):
        if file_hash(binary_dir / name) != file_hash(home / 'lib' / PLATFORM / name):
            raise ValueError('Engine libraries differ from the installed SDK; rebuild SDK: ' + name)


def safe_name(name):
    path = PurePosixPath(name)
    if (not name or '\\' in name or ':' in name or path.is_absolute()
            or any(p in ('.', '..') or p.endswith((' ', '.')) for p in name.split('/'))
            or any(re.fullmatch(r'(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\..*)?', p) for p in path.parts)):
        raise ValueError('Invalid extension path: ' + name)
    return path


def extract_sources(data, destination):
    with zipfile.ZipFile(BytesIO(data)) as archive:
        files = [entry for entry in archive.infolist() if not entry.is_dir()]
        if len(files) > 20000 or sum(entry.file_size for entry in files) > MAX_BYTES:
            raise ValueError('Extension sources exceed local build limit')
        seen = set()
        for entry in files:
            path = safe_name(entry.filename)
            if str(path).lower() in seen or (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError('Duplicate path or symbolic link in sources')
            seen.add(str(path).lower())
            target = destination / str(path)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(entry))


def cmake_arg(value):
    # CMake bracket arguments preserve paths without variable/list expansion.
    value = str(value).replace('\\', '/')
    if any(c in value for c in (';', '\n', '\r', '\x00', ']=]')):
        raise ValueError('Unsupported CMake argument')
    return '[=[' + value + ']=]'


def manifest_context(manifest):
    if not isinstance(manifest, dict) or set(manifest) - {'name', 'platforms'}:
        raise ValueError('Unsupported extension manifest fields')
    name = manifest.get('name', '')
    if not re.fullmatch(r'[A-Za-z_]\w*', name):
        raise ValueError('Invalid extension registration symbol')
    result = {'symbols': [name], 'defines': [], 'includes': []}
    if not windows():
        result['frameworks'] = []
    for platform in ('common', 'win32' if windows() else 'osx', PLATFORM.replace('-macos','-osx')):
        settings = manifest.get('platforms', {}).get(platform, {})
        if not isinstance(settings, dict) or set(settings) - {'context'}:
            raise ValueError('Unsupported platform manifest fields: ' + platform)
        context = settings.get('context', {})
        if not isinstance(context, dict) or set(context) - result.keys():
            raise ValueError('Local build supports only symbols, defines and includes; unsupported context in ' + platform)
        for key, values in context.items():
            if not isinstance(values, list) or not all(isinstance(v, str) for v in values):
                raise ValueError('Expected string list for ' + key)
            result[key].extend(values)
    if not all(re.fullmatch(r'[A-Za-z_]\w*', value) for value in result['symbols']):
        raise ValueError('Invalid extension registration symbol')
    return result


def make_config(source, output):
    app = source / '_app' / 'app.manifest'
    if app.exists():
        manifest = yaml.safe_load(app.read_text(encoding='utf-8')) or {}
        if (not isinstance(manifest, dict) or set(manifest) - {'context', 'platforms'}
                or manifest.get('platforms')
                or manifest.get('context', {}).get('baseVariant', 'debug') != 'debug'
                # Bob includes Android metadata even in Windows build requests.
                or set(manifest.get('context', {})) - {'baseVariant', 'withSymbols', 'minAndroidSdkVersion'}):
            raise ValueError('Local extension build currently requires the default debug app manifest')
    manifests = sorted(source.rglob('ext.manifest'))
    if not manifests:
        raise ValueError('No native extension manifest in request')
    lines = ['set(DEFOLD_LOCAL_EXTENSION_LIBRARIES)', 'set(DEFOLD_LOCAL_EXTENSION_SYMBOLS)']
    for index, manifest in enumerate(manifests):
        base = manifest.parent
        context = manifest_context(yaml.safe_load(manifest.read_text(encoding='utf-8')))
        target = 'local_extension_' + str(index)
        sources = sorted(p for folder in ('src', 'commonsrc') for p in (base / folder).rglob('*')
                         if p.suffix in (('.c', '.cc', '.cpp') if windows() else ('.c', '.cc', '.cpp', '.m', '.mm')))
        generated = output.parent / ('generated-' + str(index))
        generated.mkdir(parents=True, exist_ok=True)
        proto_names = set()
        for proto in sorted(p for folder in ('src', 'commonsrc') for p in (base / folder).rglob('*.proto')):
            if proto.stem.lower() in proto_names:
                raise ValueError('Local build requires unique proto basenames')
            proto_names.add(proto.stem.lower())
            cpp = generated / (proto.stem + '.cpp')
            lines.append('defold_protoc_gen_cpp(' + cmake_arg(cpp) + ' ' + cmake_arg(proto)
                         + ' INCLUDES ' + cmake_arg(base / 'commonsrc') + ' ' + cmake_arg(base / 'src') + ')')
            sources.append(cpp)
        if not sources:
            raise ValueError('No sources in ' + str(manifest))
        includes = [base / name for name in ('include', 'src', 'commonsrc')]
        includes.append(generated)
        for relative in context['includes']:
            candidate = (base / str(safe_name(relative))).resolve()
            if not candidate.is_relative_to(source.resolve()):
                raise ValueError('Include escapes uploaded sources')
            includes.append(candidate)
        lines.append('defold_add_library(' + target + ' STATIC EXCLUDE_FROM_ALL ' + ' '.join(map(cmake_arg, sources)) + ')')
        lines.append('target_include_directories(' + target + ' PRIVATE ' + ' '.join(map(cmake_arg, includes)) + ')')
        lines.append('target_compile_definitions(' + target + ' PRIVATE DM_DEBUG ' + ' '.join(map(cmake_arg, context['defines'])) + ')')
        libraries = sorted(p for folder in ('common', 'win32' if windows() else 'osx', PLATFORM.replace('-macos','-osx')) for p in (base / 'lib' / folder).rglob('*.lib' if windows() else '*.a'))
        lines.append('list(APPEND DEFOLD_LOCAL_EXTENSION_LIBRARIES ' + target + ' ' + ' '.join(map(cmake_arg, libraries)) + ')')
        for index, framework in enumerate(context.get('frameworks', [])):
            if not re.fullmatch(r'[A-Za-z0-9_]+', framework):
                raise ValueError('Invalid framework name')
            variable = target + '_framework_' + str(index)
            lines.append('find_library(' + variable + ' ' + framework + ' REQUIRED)')
            lines.append('list(APPEND DEFOLD_LOCAL_EXTENSION_LIBRARIES ${' + variable + '})')
        lines.append('list(APPEND DEFOLD_LOCAL_EXTENSION_SYMBOLS ' + ' '.join(context['symbols']) + ')')
    output.write_text('\n'.join(lines) + '\n', encoding='utf-8')


class Builder:
    def __init__(self, engine_home, work, cache=None):
        self.home = engine_home.resolve()
        self.work = work.resolve()
        self.work.mkdir(parents=True, exist_ok=True)
        self.build_dir = ROOT / 'engine/build' / PLATFORM
        self.cmake = shutil.which('cmake') or ('C:/Program Files/CMake/bin/cmake.exe' if windows() else 'cmake')
        self.sdk = sdk_revision(self.home)
        self.engine_hash = file_hash(self.home / 'bin' / PLATFORM / engine_filename())
        self.bob_hash = file_hash(self.home / 'share/java/bob.jar')
        check_engine_libraries(self.home, ROOT / 'engine/engine/build' / PLATFORM)
        self.configure = json.loads((self.build_dir / '.defold_cmake_configure.json').read_text())['args']
        if not any(str(self.home).replace('\\', '/') in arg.replace('\\', '/') for arg in self.configure if 'DEFOLD_SDK_ROOT' in arg):
            raise ValueError('CMake build SDK does not match --engine-home')
        self.environment = dict(os.environ, DEFOLD_HOME=str(ROOT), DYNAMO_HOME=str(self.home),
                                DEFOLD_LOCAL_ENGINE_SHA1=self.sdk,
                                PYTHONUTF8='1', PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION='python')
        self.environment['PYTHONPATH'] = os.pathsep.join(str(p) for p in (
            self.home / 'lib/python', ROOT / 'build_tools', self.home / 'ext/lib/python'))
        if not self.environment.get('JAVA_HOME'):
            jdks = sorted((ROOT / 'editor/build/jdk' / PLATFORM).glob('jdk-*'))
            if jdks:
                home = jdks[-1]
                self.environment['JAVA_HOME'] = str(home/'Contents/Home' if (home/'Contents/Home').is_dir() else home)
            elif not windows():
                self.environment['JAVA_HOME'] = subprocess.check_output(['/usr/libexec/java_home'], text=True).strip()
        self.jobs = {}
        self.cache = Path(cache).resolve() if cache else None
        if self.cache:
            self.cache.mkdir(parents=True, exist_ok=True)
        self.stopping = threading.Event()
        self.process = None
        # The engine CMake tree is shared: never configure or link jobs concurrently.
        self.executor = ThreadPoolExecutor(max_workers=1)

    def close(self):
        self.stopping.set()
        if self.process and self.process.poll() is None:
            self.process.terminate()
        self.executor.shutdown(wait=True, cancel_futures=True)

    def run(self, command, log, timeout):
        if self.stopping.is_set():
            raise InterruptedError('Local build cancelled')
        with subprocess.Popen(command, env=self.environment, stdout=log, stderr=subprocess.STDOUT,
                              creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0) as process:
            self.process = process
            if self.stopping.is_set():
                process.terminate()
            try:
                code = process.wait(timeout=timeout)
            except BaseException:
                process.kill()
                process.wait()
                raise
            finally:
                self.process = None
            if code:
                raise subprocess.CalledProcessError(code, command)

    def cache_key(self, data):
        digest = hashlib.sha256((PLATFORM + self.sdk + self.engine_hash + self.bob_hash + file_hash(Path(__file__))).encode())
        # ZIP timestamps/order vary between Bob invocations; cache actual named content.
        with zipfile.ZipFile(BytesIO(data)) as archive:
            for name in sorted(archive.namelist()):
                if not name.endswith('/'):
                    digest.update(name.encode()); digest.update(b'\0'); digest.update(hashlib.sha256(archive.read(name)).digest())
        for header in sorted((self.home / 'include').rglob('*')):
            if header.is_file():
                digest.update(str(header.relative_to(self.home)).encode());digest.update(file_hash(header).encode())
        return digest.hexdigest()

    def submit(self, data):
        job = uuid.uuid4().hex
        self.jobs[job] = {'status': 0}
        self.executor.submit(self.build, job, data)
        return job

    def build(self, job, data):
        directory = self.work / job
        directory.mkdir()
        log_path = directory / 'build.log'
        try:
            if (file_hash(self.home / 'bin' / PLATFORM / engine_filename()) != self.engine_hash
                    or file_hash(self.home / 'share/java/bob.jar') != self.bob_hash):
                raise ValueError('Local engine SDK changed; restart the local extension builder')
            source = directory / 'sources'
            source.mkdir()
            extract_sources(data, source)
            cached = self.cache / (self.cache_key(data) + '.zip') if self.cache else None
            if cached and cached.is_file():
                log_path.write_text('REMOTE_NATIVE_CACHE_HIT\n', encoding='utf-8')
                self.jobs[job] = {'status': 1, 'result': cached}
                return
            config = directory / 'extensions.cmake'
            make_config(source, config)
            with build_lock(self.build_dir / '.remote-native.lock', self.stopping), log_path.open('wb') as log:
                self.run([self.cmake, *self.configure[1:], '-DDEFOLD_LOCAL_EXTENSION_CONFIG=' + config.as_posix()], log, 300)
                self.run([self.cmake, '--build', str(self.build_dir), '--target', 'dmengine_local', '--parallel', '8'], log, 600)
                archive = directory / 'engine.zip'
                binary_dir = ROOT / 'engine/engine/build' / PLATFORM
                check_engine_libraries(self.home, binary_dir)
                with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as output:
                    if windows():
                        for extension in ('exe', 'pdb'):
                            output.write(binary_dir / ('dmengine_local.' + extension), 'dmengine.' + extension)
                    else:
                        subprocess.run(['codesign','--force','--sign','-',str(binary_dir/'dmengine_local')],check=True,stdout=log,stderr=subprocess.STDOUT)
                        output.write(binary_dir/'dmengine_local','dmengine')
            archive = directory / 'engine.zip'
            binary_dir = ROOT / 'engine/engine/build' / PLATFORM
            check_engine_libraries(self.home, binary_dir)
            if (file_hash(self.home / 'bin' / PLATFORM / engine_filename()) != self.engine_hash
                    or file_hash(self.home / 'share/java/bob.jar') != self.bob_hash):
                raise ValueError('Local engine SDK changed during build; restart the local builder')
            if cached:
                temporary = cached.with_suffix('.' + job + '.part')
                shutil.copy2(archive, temporary)
                temporary.replace(cached)
            self.jobs[job] = {'status': 1, 'result': archive}
        except Exception as error:
            with log_path.open('a', encoding='utf-8') as log:
                log.write('\nLocal extension build failed: ' + str(error) + '\n')
            self.jobs[job] = {'status': 2, 'result': log_path}


class build_lock:
    """Serialize local builder processes sharing one CMake tree."""
    def __init__(self, path, stopping):
        self.path, self.stopping = path, stopping
    def __enter__(self):
        self.stream = self.path.open('a+b')
        if self.stream.tell() == 0:
            self.stream.write(b'0'); self.stream.flush()
        while not self.stopping.is_set():
            try:
                self.stream.seek(0)
                if os.name=='nt':
                    import msvcrt
                    msvcrt.locking(self.stream.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(self.stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                return self
            except OSError:
                self.stopping.wait(.2)
        self.stream.close()
        raise InterruptedError('Local build cancelled while waiting for compiler')
    def __exit__(self, *args):
        self.stream.seek(0)
        if os.name=='nt':
            import msvcrt
            msvcrt.locking(self.stream.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            fcntl.flock(self.stream.fileno(), fcntl.LOCK_UN)
        self.stream.close()


def create_server(builder, port=0, token=None):

    class Handler(BaseHTTPRequestHandler):
        def reply(self, code, body, content_type='text/plain'):
            if isinstance(body, str):
                body = body.encode('utf-8')
            self.send_response(code)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(body)))
            self.send_header('X-TraceId', 'local-windows')
            self.end_headers()
            self.wfile.write(body)

        def allowed(self):
            if (self.headers.get('Origin') or self.headers.get('Host') != '127.0.0.1:' + str(self.server.server_port)
                    # Bob forwards custom headers on uploads, but not on async GETs.
                    # Poll/results require the random 128-bit job capability returned by an authenticated upload.
                    or token and self.command == 'POST' and self.headers.get('X-Remote-Build-Token') != token):
                self.reply(403, 'Loopback build clients only')
                return False
            return True

        def do_GET(self):
            if not self.allowed():
                return
            url = urlsplit(self.path)
            if url.path == '/health':
                self.reply(200, json.dumps({'sdk': builder.sdk, 'platform': PLATFORM, 'engine_sha256': builder.engine_hash}), 'application/json')
                return
            job = builder.jobs.get(parse_qs(url.query).get('jobId', [''])[0])
            if job is None:
                self.reply(404, 'Unknown local job')
            elif url.path == '/job_status':
                self.reply(200, str(job['status']))
            elif url.path == '/job_result' and job['status']:
                self.reply(200, job['result'].read_bytes(), 'application/zip' if job['status'] == 1 else 'text/plain')
            else:
                self.reply(404, 'Unknown endpoint or job not ready')

        def do_POST(self):
            if not self.allowed():
                return
            try:
                size = int(self.headers.get('Content-Length', '0'))
                if not 0 < size <= MAX_BYTES:
                    self.reply(413, 'Missing or oversized request body')
                    return
                data = self.rfile.read(size)
                if self.path == '/query':
                    # No server-side source cache: request every file. The editor's
                    # existing content-addressed engine cache still avoids rebuilds.
                    self.reply(404, 'Source cache disabled')
                    return
                if self.path != '/build_async/' + PLATFORM.replace('-macos','-osx') + '/' + builder.sdk:
                    self.reply(501, 'Local builder requires Windows debug SDK ' + builder.sdk)
                    return
                message = BytesParser(policy=policy.default).parsebytes(
                    ('Content-Type: ' + self.headers.get('Content-Type', '') + '\r\n\r\n').encode() + data)
                archives = [part.get_payload(decode=True) for part in message.iter_parts()
                            if part.get_filename() == '__source_code__.zip']
                if len(archives) != 1:
                    raise ValueError('Expected one source archive')
                self.reply(200, builder.submit(archives[0]))
            except (ValueError, OSError) as error:
                self.reply(400, str(error))

    server = ThreadingHTTPServer(('127.0.0.1', port), Handler)
    server.daemon_threads = True
    return server


def serve(engine_home, work, port):
    builder = Builder(engine_home, work)
    server = create_server(builder, port)
    print(json.dumps({'url': 'http://127.0.0.1:' + str(server.server_port), 'sdk': builder.sdk,
                      'pid': os.getpid()}), flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()
        builder.close()
