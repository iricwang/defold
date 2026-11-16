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

"""Run the real packaged Defold editor in isolated JavaFX virtual windows."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import zipfile


def encode(value):
    if isinstance(value, dict):
        return b'd' + b''.join(encode(k) + encode(v) for k, v in sorted(value.items())) + b'e'
    data = str(value).encode('utf-8')
    return str(len(data)).encode('ascii') + b':' + data


def decode(stream):
    tag = stream.read(1)
    if not tag:
        raise EOFError('Headless editor closed its REPL connection')
    if tag == b'd':
        result = {}
        while stream.peek(1)[:1] != b'e':
            key = decode(stream)
            result[key] = decode(stream)
        stream.read(1)
        return result
    if tag == b'l':
        result = []
        while stream.peek(1)[:1] != b'e':
            result.append(decode(stream))
        stream.read(1)
        return result
    if tag == b'i':
        digits = b''
        while (tag := stream.read(1)) != b'e':
            if not tag:
                raise EOFError('Incomplete integer')
            digits += tag
        return int(digits)
    digits = tag
    while (tag := stream.read(1)) != b':':
        if not tag:
            raise EOFError('Incomplete length')
        digits += tag
    length = int(digits)
    data = stream.read(length)
    if len(data) != length:
        raise EOFError('Incomplete value')
    return data.decode('utf-8')


def evaluate(port, code, timeout):
    output = ''
    errors = ''
    value = None
    with socket.create_connection(('127.0.0.1', port), timeout=timeout) as connection:
        connection.settimeout(timeout)
        connection.sendall(encode({'op': 'eval', 'code': code, 'id': 'headless'}))
        with connection.makefile('rb') as stream:
            while True:
                response = decode(stream)
                output += response.get('out', '')
                errors += response.get('err', '')
                value = response.get('value', value)
                status = response.get('status', [])
                if 'eval-error' in status:
                    raise RuntimeError(errors or str(response))
                if 'done' in status:
                    return value, output, errors


def dependency(editor, relative):
    for root in [editor / '.m2/repository', Path.home() / '.m2/repository']:
        path = root / relative
        if path.is_file():
            return path
    raise RuntimeError(f'Missing {relative}; run editor/scripts/lein deps first')


def copy_project(source, destination):
    destination = destination.resolve()
    if source is None:
        destination.mkdir()
        (destination / 'game.project').write_text('[project]\ntitle = Headless Verification\n[bootstrap]\nmain_collection = /main.collection\n')
        (destination / 'main.collection').write_text('name: \"headless\"\n')
        return
    source = source.resolve()
    source = source.parent if source.name == 'game.project' else source
    if not (source / 'game.project').is_file():
        raise ValueError('Project must contain game.project')
    if destination.is_relative_to(source):
        raise ValueError('Output must be outside the source project')
    excluded = {'.git', '.internal', 'build', '.DS_Store'}

    def ignore(directory, names):
        return [name for name in names if name in excluded or (Path(directory) / name).is_symlink()]

    shutil.copytree(source, destination, ignore=ignore)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True, help='Packaged editor JAR under test')
    parser.add_argument('--java', default=os.environ.get('JAVA_HOME', '') + '/bin/java' if os.environ.get('JAVA_HOME') else shutil.which('java'))
    parser.add_argument('--project', type=Path, help='Copied into an isolated workspace; original is never opened')
    parser.add_argument('--scenario', type=Path, help='EDN interaction scenario')
    parser.add_argument('--output', type=Path, required=True, help='New empty evidence directory')
    parser.add_argument('--timeout', type=float, default=180, help='Startup timeout in seconds')
    parser.add_argument('--backend', choices=['headless', 'background'], default='headless', help='Pure JavaFX headless (2D) or native GPU rendering with virtual window focus')
    parser.add_argument('--allow-no-gl', action='store_true', help='Explicitly permit UI-only validation when OpenGL is unavailable; reported as missing capability')
    parser.add_argument('--scenario-timeout', type=float, default=180)
    args = parser.parse_args()
    editor = Path(__file__).resolve().parents[1]
    jar = args.jar.resolve(strict=True)
    scenario = args.scenario.resolve(strict=True) if args.scenario else None
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        parser.error('--output must be new or empty (evidence is never overwritten)')
    output.mkdir(parents=True, exist_ok=True)
    process = None
    port = None
    try:
        with zipfile.ZipFile(jar) as archive:
            if 'com/sun/glass/ui/headless/HeadlessPlatformFactory.class' not in archive.namelist():
                raise RuntimeError('This editor JAR does not include the JavaFX headless backend')
            metadata = json.loads(archive.read('patch/build.json')) if 'patch/build.json' in archive.namelist() else {}
        with jar.open('rb') as source:
            digest = hashlib.file_digest(source, 'sha256').hexdigest()
        project = output / 'project'
        copy_project(args.project, project)
        nrepl = dependency(editor, 'nrepl/nrepl/1.7.0/nrepl-1.7.0.jar')
        plorer = dependency(editor, 'io/github/cljfx/plorer/1.31/plorer-1.31.jar')
        classpath = os.pathsep.join(map(str, [jar, nrepl, plorer, editor / 'src/dev']))
        quote = lambda value: json.dumps(str(value), ensure_ascii=False)
        port_file = output / 'port'
        bootstrap = output / 'bootstrap.clj'
        bootstrap.write_text(
            "(System/setProperty \"defold.engine.sha1\" com.dynamo.bob.archive.EngineVersion/sha1)\n"
            "(require 'nrepl.server)\n"
            f'(spit {quote(port_file)} (:port (nrepl.server/start-server :bind "127.0.0.1" :port 0)))\n'
            f'(com.defold.editor.Main/main (into-array String ["--preferences" {quote(output / "preferences.edn")} {quote(project / "game.project")}]))\n')
        command = [args.java, '-Xmx3g', '-Ddefold.qa.session=true', f'-Ddefold.qa.backend={args.backend}', f'-Ddefold.version={metadata.get("version", "headless-verification")}',
                   f'-Ddefold.editor.sha1={digest}',
                   '-Ddefold.schema.check.disable=true', '-Dfile.encoding=UTF-8',
                   '-Djavafx.animation.framerate=60', '-Djavafx.animation.pulse=60',
                   f'-Ddefold.unpack.path={output / "unpack"}', f'-Ddefold.log.dir={output / "logs"}',
                   '--enable-native-access=ALL-UNNAMED', '--sun-misc-unsafe-memory-access=allow']
        if args.backend == 'headless':
            command += ['-Dglass.platform=headless', '-Dprism.order=sw', '-Djava.awt.headless=true']
        for module in ['java.base/java.lang', 'java.base/sun.nio.fs', 'java.desktop/sun.awt',
                       'java.desktop/sun.java2d.opengl', 'java.desktop/sun.awt.image',
                       'java.xml/com.sun.org.apache.xerces.internal.jaxp']:
            command.append(f'--add-opens={module}=ALL-UNNAMED')
        command += ['-cp', classpath, 'clojure.main', str(bootstrap)]
        (output / 'run.json').write_text(json.dumps({'jar': str(jar), 'sha256': digest,
            'project_source': str(args.project) if args.project else None,
            'project_copy': str(project), 'scenario': str(scenario) if scenario else None,
            'backend': args.backend, 'allow_no_gl': args.allow_no_gl,
            'copy_excludes': ['.git', '.internal', 'build', '.DS_Store', 'symlinks']}, indent=2))
        with (output / 'editor.log').open('w') as log:
            process = subprocess.Popen(command, cwd=output, stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + args.timeout
            ready = False
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(f'Editor exited during startup: {process.returncode}; see editor.log')
                if port_file.exists():
                    port = int(port_file.read_text())
                    value, _, _ = evaluate(port, f"(require 'headless) (headless/startup-status {str(args.allow_no_gl).lower()})", max(1, deadline - time.monotonic()))
                    if value == '"opengl-unavailable"':
                        raise RuntimeError('OpenGL 3.3 unavailable: scene rendering cannot be verified. Use --allow-no-gl only for explicit UI-only checks.')
                    if value == '"ready"':
                        ready = True
                        break
                time.sleep(0.2)
            if not ready:
                raise TimeoutError('Project did not become ready; see editor.log')
            print(f'{args.backend} editor ready; replaying scenario', flush=True)
            code = f'(headless/run-scenario! {quote(scenario) if scenario else "nil"} {quote(output)})'
            _, out, err = evaluate(port, code, args.scenario_timeout)
            (output / 'scenario.log').write_text(out + err)
            report = json.loads((output / 'report.json').read_text())
            print(json.dumps({'status': report['status'], 'steps': len(report['steps']), 'evidence': str(output)}))
            return 0 if report['status'] == 'passed' else 1
    except Exception as error:
        (output / 'launcher-error.json').write_text(json.dumps({'status': 'failed', 'message': str(error)}, indent=2))
        print(f'Headless verification failed: {error}', file=sys.stderr)
        return 1
    finally:
        if process is not None:
            shutdown = {'method': 'already-exited'}
            if process.poll() is None:
                # Drain JavaFX/Metal before terminating this isolated JVM.
                # Immediate SIGTERM races native window teardown on macOS.
                shutdown['method'] = 'javafx-exit'
                if port is not None:
                    try:
                        evaluate(port, '(javafx.application.Platform/exit)', 5)
                    except (EOFError, OSError, RuntimeError) as error:
                        shutdown['repl-close'] = str(error)
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    shutdown['method'] = 'terminate-after-javafx'
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        shutdown['method'] = 'kill-after-timeout'
                        process.kill()
                        process.wait(timeout=5)
            shutdown['exit-code'] = process.returncode
            shutdown['native-crash'] = bool(list(output.glob('hs_err_pid*.log')))
            (output / 'shutdown.json').write_text(json.dumps(shutdown, indent=2))
            if shutdown['native-crash'] or process.returncode not in (0, -15):
                print('Headless verification failed during shutdown; see shutdown.json', file=sys.stderr)
                return 1


if __name__ == '__main__':
    raise SystemExit(main())
