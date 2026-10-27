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
"""Build a Windows editor patch with matching archived or locally built engine artifacts."""
import json
import argparse
import io
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
from types import SimpleNamespace
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
ENGINE = '65f2d1f306c461d2793359ea9aee94f20398c54c'


def add_editor_debugger(source, destination):
    """Preserve native DAP builds; supply Lua modules only for legacy editors."""
    start = (ROOT / 'editor/bundle-resources/_defold/debugger/start.lua').read_text(encoding='utf-8')
    if 'builtins.scripts.debugger' not in start:
        shutil.copyfile(source, destination)
        return
    modules = {
        f'builtins/scripts/{name}.lua':
            (ROOT / f'engine/engine/content/builtins/scripts/{name}.lua').read_bytes()
        for name in ('debugger', 'mobdebug', 'edn')
    }
    with zipfile.ZipFile(source) as original:
        builtins = io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(original.read('lib/builtins.zip'))) as library:
            with zipfile.ZipFile(builtins, 'w', zipfile.ZIP_DEFLATED) as updated:
                for entry in library.infolist():
                    if entry.filename not in modules:
                        updated.writestr(entry, library.read(entry))
                for name, content in modules.items():
                    updated.writestr(name, content)
        replacements = {**modules, 'lib/builtins.zip': builtins.getvalue()}
        with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED) as updated:
            for entry in original.infolist():
                if entry.filename not in replacements:
                    updated.writestr(entry, original.read(entry))
            for name, content in replacements.items():
                updated.writestr(name, content)


def download(url, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if not destination.exists():
        temporary = destination.with_suffix(destination.suffix + '.part')
        urllib.request.urlretrieve(url, temporary)
        temporary.replace(destination)


def verify_patch(patch, jdk):
    """Gate local and CI builds on the resources in the actual incremental ZIP."""
    with tempfile.TemporaryDirectory(prefix='windows-patch-check-', dir=ROOT / 'tmp') as directory:
        jar = Path(directory) / 'editor.jar'
        with zipfile.ZipFile(patch) as archive:
            entries = [name for name in archive.namelist()
                       if name.startswith(('files/packages/defold-', 'Defold/packages/defold-')) and name.endswith('.jar')]
            if len(entries) != 1:
                raise ValueError('Expected exactly one editor JAR in the patch')
            jar.write_bytes(archive.read(entries[0]))
        env = dict(os.environ, JAVA_HOME=str(Path(jdk).resolve()),
                   DEFOLD_PATCH_TEST_JAR=str(jar))
        subprocess.run([sys.executable, '-X', 'utf8', '-m', 'unittest', 'discover',
                        '-s', 'patch', '-p', 'test_editor_pipeline.py'],
                       cwd=ROOT, env=env, check=True)


def build_windows_native(source, destination):
    """Build the game startup guard or its standalone regression probe."""
    destination = Path(destination).resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    source = Path(source).resolve()
    shared = destination.suffix == '.dll'
    gcc = shutil.which('gcc')
    if gcc:
        subprocess.run([gcc, *(['-shared'] if shared else []), '-O2', '-static-libgcc', '-o', str(destination),
                        str(source), '-luser32', '-lgdi32', '-lopengl32', '-ldbghelp'], check=True)
        return
    vswhere = Path(os.environ['ProgramFiles(x86)']) / 'Microsoft Visual Studio/Installer/vswhere.exe'
    installation = subprocess.check_output(
        [str(vswhere), '-latest', '-products', '*', '-requires',
         'Microsoft.VisualStudio.Component.VC.Tools.x86.x64', '-property', 'installationPath'],
        text=True).strip()
    if not installation:
        raise RuntimeError('MSVC or MinGW is required to build the Windows game window guard')
    with tempfile.TemporaryDirectory(prefix='game-window-build-', dir=ROOT / 'tmp') as directory:
        build = Path(directory)
        script = build / 'build.cmd'
        vcvars = Path(installation) / 'VC/Auxiliary/Build/vcvars64.bat'
        script.write_text(
            f'@call "{vcvars}" >nul\r\n@if errorlevel 1 exit /b 1\r\n'
            f'@cl /nologo {"/LD" if shared else ""} /O2 /MT "{source}" /Fo:"{build / "hook.obj"}" '
            f'/Fe:"{destination}" user32.lib gdi32.lib opengl32.lib dbghelp.lib /link /IMPLIB:"{build / "hook.lib"}"\r\n')
        subprocess.run(['cmd', '/d', '/c', str(script)], cwd=build, check=True)


def validate_engine_home(home):
    """Require all local artifacts before touching editor build outputs."""
    required = ('share/java/bob.jar', 'share/ref-doc.zip',
                'bin/x86_64-win32/dmengine.exe', 'bin/x86_64-win32/dmengine.pdb',
                'bin/x86_64-win32/launcher.exe',
                'lib/x86_64-win32/particle_shared.dll',
                'lib/x86_64-win32/mouse_capture_shared.dll')
    missing = [name for name in required if not (home / name).is_file()]
    if missing:
        raise ValueError('Incomplete local engine build: ' + ', '.join(missing))
    # The executable shipped inside Bob and its external debug symbols must be
    # from the same local build, or Simulator symbol lookup cannot be trusted.
    with zipfile.ZipFile(home / 'share/java/bob.jar') as bob:
        if bob.read('libexec/x86_64-win32/dmengine.exe') != (home / 'bin/x86_64-win32/dmengine.exe').read_bytes():
            raise ValueError('Local Bob engine differs from the engine build; rebuild Bob first')


def main(*, baseline=None, full=False, engine_home=None):
    os.chdir(ROOT)
    home = Path(engine_home).resolve() if engine_home else ROOT / 'tmp/windows-patch-home'
    if engine_home:
        validate_engine_home(home)
    os.environ['DYNAMO_HOME'] = str(home)
    os.environ['LEIN_HOME'] = str(ROOT / 'editor/build/lein')
    os.environ['PYTHONUTF8'] = '1'
    # Reuse the selected Bob's protocols together with its compiler/native tools.
    os.environ['DM_EDITOR_USE_BOB_PROTOCOLS'] = '1'
    git_tools = Path(os.environ['ProgramFiles']) / 'Git/usr/bin'
    os.environ['PATH'] = str(git_tools) + os.pathsep + os.environ['PATH']
    if engine_home:
        os.environ['PATH'] = str(home / 'ext/bin/x86_64-win32') + os.pathsep + os.environ['PATH']
    else:
        tools = ROOT / 'tmp/windows-patch-tools'
        protoc = tools / 'protoc.zip'
        download('https://github.com/protocolbuffers/protobuf/releases/download/v34.0/protoc-34.0-win64.zip', protoc)
        with zipfile.ZipFile(protoc) as archive:
            archive.extractall(tools / 'protoc')
        shutil.copytree(tools / 'protoc/include', home / 'ext/include', dirs_exist_ok=True)
        os.environ['PATH'] = str(tools / 'protoc/bin') + os.pathsep + os.environ['PATH']
        for package in sorted((ROOT / 'packages').glob('luajit-*-common.tar.gz')):
            with tarfile.open(package) as archive:
                archive.extractall(home / 'ext', filter='data')
    archive_url = f'https://d.defold.com/archive/alpha/{ENGINE}'
    if not engine_home:
        download(f'{archive_url}/bob/bob.jar', home / 'share/java/bob.jar')
    editor_bob = home / 'share/java/bob-editor.jar'
    add_editor_debugger(home / 'share/java/bob.jar', editor_bob)
    os.environ['DM_BOB_JAR'] = str(editor_bob)
    if not engine_home:
        download(f'{archive_url}/engine/share/ref-doc.zip', home / 'share/ref-doc.zip')
        for name in ('launcher.exe', 'dmengine.pdb', 'particle_shared.dll', 'mouse_capture_shared.dll'):
            destination = home / ('bin' if name in ('launcher.exe', 'dmengine.pdb') else 'lib') / 'x86_64-win32' / name
            download(f'{archive_url}/engine/x86_64-win32/{name}', destination)
    subprocess.run([sys.executable, 'patch/manage.py', 'stage'], check=True)
    if not full and baseline is None:
        subprocess.run([sys.executable, 'patch/manage.py', 'prepare-base', '--directory', 'tmp/patch-base', '--platform', 'x86_64-win32'], check=True)
    metadata = json.loads((ROOT / 'patch/channel.json').read_text())
    os.chdir(ROOT / 'editor')
    sys.path.insert(0, str(ROOT / 'editor/scripts'))
    import bundle
    options = SimpleNamespace(patch_base=str(baseline or ROOT / 'tmp/patch-base/Defold-x86_64-win32-state.json'),
        editor_sha1=subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
        engine_sha1=None, version=metadata['version'], channel=metadata['channel'],
        archive_domain='d.defold.com', launcher=str(home / 'bin/x86_64-win32/launcher.exe'), codesign=False)
    jdk = bundle.get_jdk('x86_64-win32')
    bundle.place_release_notes(options)
    bundle.init_editor(options, 'x86_64-win32', jdk)
    from remote_video import build as build_remote_video
    build_remote_video(jdk, ROOT / 'editor/resources/lib/x86_64-win32/remote_video.dll')
    build_windows_native(ROOT / 'scripts/windows/game-window-hook.c',
                         ROOT / 'editor/resources/_unpack/x86_64-win32/lib/game_window_hook.dll')
    bundle.invoke_lein(['run', '-m', 'editor.ns-batch-builder', 'resources/sorted_clojure_ns_list.edn'], jdk_path=jdk)
    bundle.invoke_lein(['prerelease'], jdk_path=jdk)
    options.engine_sha1 = options.editor_sha1 if engine_home else ENGINE
    if full:
        # create_bundle uses editor/tmp as its staging directory.
        staging = ROOT / 'editor/tmp'
        if staging.is_symlink() or staging.resolve().parent != (ROOT / 'editor').resolve():
            raise ValueError('Full bundle staging must remain inside editor/')
        bundle.create_bundle(jdk, 'x86_64-win32', options)
        verify_patch(ROOT / 'editor/target/editor/Defold-x86_64-win32.zip', jdk)
        return
    bundle.create_patch(jdk, 'x86_64-win32', options)
    base_revision = json.loads(Path(options.patch_base).read_text())['metadata']['revision']
    verify_patch(ROOT / 'editor/target/editor' / f"Defold-x86_64-win32-patch-{base_revision}-to-{metadata['revision']}.zip", jdk)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--engine-home', type=Path,
                        help='Use the matching locally compiled engine and Bob for CI publication')
    main(engine_home=parser.parse_args().engine_home)
