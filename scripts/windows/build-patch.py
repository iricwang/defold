#!/usr/bin/env python3
"""Build the Windows editor patch using the matching archived engine, without an app bundle."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
from types import SimpleNamespace
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
ENGINE = '65f2d1f306c461d2793359ea9aee94f20398c54c'


def download(url, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if not destination.exists():
        temporary = destination.with_suffix(destination.suffix + '.part')
        urllib.request.urlretrieve(url, temporary)
        temporary.replace(destination)


def main():
    os.chdir(ROOT)
    home = ROOT / 'tmp/windows-patch-home'
    os.environ['DYNAMO_HOME'] = str(home)
    os.environ['LEIN_HOME'] = str(ROOT / 'editor/build/lein')
    os.environ['PYTHONUTF8'] = '1'
    git_tools = Path(os.environ['ProgramFiles']) / 'Git/usr/bin'
    os.environ['PATH'] = str(git_tools) + os.pathsep + os.environ['PATH']
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
    download(f'{archive_url}/bob/bob.jar', home / 'share/java/bob.jar')
    download(f'{archive_url}/engine/share/ref-doc.zip', home / 'share/ref-doc.zip')
    for name in ('launcher.exe', 'dmengine.pdb', 'particle_shared.dll', 'mouse_capture_shared.dll'):
        destination = home / ('bin' if name in ('launcher.exe', 'dmengine.pdb') else 'lib') / 'x86_64-win32' / name
        download(f'{archive_url}/engine/x86_64-win32/{name}', destination)
    subprocess.run([sys.executable, 'patch/manage.py', 'stage'], check=True)
    subprocess.run([sys.executable, 'patch/manage.py', 'prepare-base', '--directory', 'tmp/patch-base', '--platform', 'x86_64-win32'], check=True)
    metadata = json.loads((ROOT / 'patch/channel.json').read_text())
    os.chdir(ROOT / 'editor')
    sys.path.insert(0, str(ROOT / 'editor/scripts'))
    import bundle
    options = SimpleNamespace(patch_base=str(ROOT / 'tmp/patch-base/Defold-x86_64-win32-state.json'),
        editor_sha1=subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
        engine_sha1=None, version=metadata['version'], channel=metadata['channel'],
        archive_domain='d.defold.com', launcher=str(home / 'bin/x86_64-win32/launcher.exe'), codesign=False)
    jdk = bundle.get_jdk('x86_64-win32')
    bundle.place_release_notes(options)
    bundle.init_editor(options, 'x86_64-win32', jdk)
    bundle.invoke_lein(['run', '-m', 'editor.ns-batch-builder', 'resources/sorted_clojure_ns_list.edn'], jdk_path=jdk)
    bundle.invoke_lein(['prerelease'], jdk_path=jdk)
    options.engine_sha1 = ENGINE
    bundle.create_patch(jdk, 'x86_64-win32', options)


if __name__ == '__main__':
    main()
