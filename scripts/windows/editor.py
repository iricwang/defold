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
"""Local Windows editor builds, portable archives, verification and installation."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'patch'))
import package


def builder():
    spec = importlib.util.spec_from_file_location('windows_build', ROOT / 'scripts/windows/build-patch.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def runtime(app):
    candidates = list(app.glob('packages/jdk-*/bin/java.exe'))
    if len(candidates) != 1:
        raise ValueError('Expected one installed JDK in ' + str(app))
    return candidates[0].parent.parent


def checksum(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def write_checksum(path):
    path.with_suffix(path.suffix + '.sha256').write_text(checksum(path) + '\n')


def validate_patch(plan, current):
    if current['platform'] != 'x86_64-win32' or not any(item['path'] == 'Defold.exe' for item in plan['target']):
        raise ValueError('This installer only accepts Windows patches')
    if plan['base_revision'] != current['metadata']['revision']:
        raise ValueError('Patch base revision does not match installed editor')
    if {item['path']: item for item in plan['base']} != {item['path']: item for item in current['files']}:
        raise ValueError('Installed files do not match the exact patch baseline')


def export_installed(app, output):
    """Snapshot only immutable application files; exclude project/cache/preferences."""
    state = package.windows_baseline(app)
    output = output.resolve()
    if output.is_relative_to(app.resolve()):
        raise ValueError('Output must be outside the installation')
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for item in state['files']:
            source = app / item['path']
            if checksum(source) != item['sha256']:
                raise ValueError('Installation changed during packaging: ' + item['path'])
            archive.write(source, 'Defold/' + item['path'])
    # Detect concurrent replacement while the archive was written.
    if package.windows_baseline(app) != state:
        raise ValueError('Installation changed during packaging; discard this archive')
    return state


def install(artifact, app):
    state = package.windows_baseline(app)
    with zipfile.ZipFile(artifact) as archive:
        plan = json.loads(archive.read('patch.json'))
        validate_patch(plan, state)
        helper_bytes = archive.read('patch-helper.jar')
    java = runtime(app) / 'bin/java.exe'
    jar = next(app.glob('packages/defold-*.jar'))
    # PatchBootstrap enforces closed editor, full baseline hashes and backup/rollback.
    with tempfile.TemporaryDirectory(prefix='local-install-', dir=ROOT / 'tmp') as directory:
        helper = Path(directory) / 'patch-helper.jar'
        helper.write_bytes(helper_bytes)
        subprocess.run([str(java), '-cp', str(helper) + os.pathsep + str(jar),
                        'com.defold.editor.PatchBootstrap', str(artifact), str(app),
                        str(plan['base_revision']), str(plan['revision'])], check=True,
                       creationflags=subprocess.CREATE_NO_WINDOW)
    for _ in range(60):
        try:
            updated = package.windows_baseline(app)
            if updated['metadata']['revision'] == plan['revision']:
                if {item['path']: item for item in updated['files']} != {item['path']: item for item in plan['target']}:
                    raise RuntimeError('Installed file inventory differs from patch target')
                print('Installed revision', plan['revision'], 'and requested editor restart.', flush=True)
                return
        except (OSError, ValueError, zipfile.BadZipFile):
            pass
        time.sleep(1)
    raise RuntimeError('Restart did not finish within 60 seconds; inspect .defold-helper-* logs beside installation')


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    sub = result.add_subparsers(dest='command', required=True)
    patch = sub.add_parser('patch', help='Compile source and build an exact-baseline local patch')
    patch.add_argument('--app', type=Path, required=True)
    patch.add_argument('--install', action='store_true', help='Install after verification; close editor first')
    full = sub.add_parser('full', help='Compile source into a complete portable ZIP including JDK')
    full.add_argument('--from-installed', type=Path, help='Package this installed editor instead of recompiling')
    full.add_argument('--output', type=Path, help='Only with --from-installed')
    for name in ('verify', 'install'):
        command = sub.add_parser(name, help='Verify an archive' if name == 'verify' else 'Verify and install an incremental ZIP')
        command.add_argument('archive', type=Path)
        command.add_argument('--app', type=Path, required=True, help='Installation supplying the matching JDK')
        command.add_argument('--sha256', help='Expected SHA-256 from the trusted download source')
    status = sub.add_parser('status', help='Show source and installed versions')
    status.add_argument('--app', type=Path, required=True)
    reload = sub.add_parser('reload', help='Reload Clojure sources in a running editor; no build, archive or restart')
    reload.add_argument('--port', type=int, required=True, help='Existing loopback Clojure socket REPL (not nREPL)')
    reload.add_argument('--pid', type=int, required=True, help='Expected editor Java PID; checked before loading')
    reload.add_argument('--namespace', action='append', required=True, help='Source namespace; repeat in dependency order')
    reload.add_argument('--after', type=Path, help='Optional Clojure file to refresh existing callbacks and assert behavior')
    reload.add_argument('--timeout', type=float, default=60, help='Overall timeout in seconds; never retries mutations')
    automation = sub.add_parser('automation-build', help='Compile the local Windows background MCP worker; no packaging/install')
    automation.add_argument('--app', type=Path, required=True)
    automation.add_argument('--output', type=Path)
    return result


def main(argv=None):
    args = parser().parse_args(argv)
    if args.command == 'automation-build':
        sys.path.insert(0, str(ROOT / 'scripts/automation'))
        from build import prepare
        print(json.dumps(prepare(args.app, args.output), indent=2))
        return
    if args.command == 'reload':
        from live_reload import reload_sources
        reload_sources(ROOT, args)
        return
    for name in ('app', 'archive', 'from_installed', 'output'):
        value = getattr(args, name, None)
        if value is not None:
            setattr(args, name, value.resolve())
    os.chdir(ROOT)
    (ROOT / 'tmp').mkdir(exist_ok=True)
    # Keep compiler sockets and test temporary files on a short, writable path.
    os.environ['TEMP'] = os.environ['TMP'] = str(ROOT / 'tmp')
    os.environ['JDK_JAVA_OPTIONS'] = (os.environ.get('JDK_JAVA_OPTIONS', '')
        + ' -Djdk.net.unixdomain.tmpdir=' + (ROOT / 'tmp').as_posix()).strip()
    metadata = json.loads((ROOT / 'patch/channel.json').read_text(encoding='utf-8'))
    if args.command == 'status':
        state = package.windows_baseline(args.app.resolve())
        print(json.dumps({'source': metadata, 'installed': state['metadata']}, ensure_ascii=False, indent=2))
        return
    build = builder()
    if args.command in ('verify', 'install'):
        archive = args.archive.resolve()
        if args.sha256 and checksum(archive) != args.sha256.lower():
            raise ValueError('Archive SHA-256 mismatch')
        build.verify_patch(archive, runtime(args.app.resolve()))
        if args.command == 'install':
            install(archive, args.app.resolve())
        return
    if args.command == 'full':
        if args.from_installed:
            app = args.from_installed.resolve()
            version = package.windows_baseline(app)['metadata']['revision']
            output = (args.output or ROOT / f'editor/target/editor/Defold-x86_64-win32-local-{version}.zip').resolve()
            export_installed(app, output)
            build.verify_patch(output, runtime(app))
        else:
            if args.output:
                raise ValueError('--output requires --from-installed')
            build.main(full=True)
            output = ROOT / 'editor/target/editor/Defold-x86_64-win32.zip'
        write_checksum(output)
        print('Verified full archive:', output)
        return
    app = args.app.resolve()
    state = package.windows_baseline(app)
    revision = state['metadata']['revision']
    if revision >= metadata['revision']:
        raise ValueError('Source revision must be newer than installation; bump patch/channel.json intentionally first')
    baseline = ROOT / 'tmp/local-editor-baseline.json'
    baseline.write_text(json.dumps(state, indent=2))
    build.main(baseline=baseline)
    output = ROOT / f"editor/target/editor/Defold-x86_64-win32-patch-{revision}-to-{metadata['revision']}.zip"
    write_checksum(output)
    print('Verified local patch:', output)
    if args.install:
        install(output, app)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print('ERROR:', error, file=sys.stderr)
        sys.exit(1)
