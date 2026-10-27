"""Build the automation sidecar locally for the host platform."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import shutil
import platform
import zipfile

ROOT = Path(__file__).resolve().parents[2]
MACOS_BRIDGE_MARKER = b'Editor automation bridge protocol 1 (macOS HID)'


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def validate_macos_engine(path):
    # Reject old/official engines before launching any window. The runtime handshake is still mandatory.
    with Path(path).open('rb') as stream:
        previous = b''
        while block := stream.read(1024 * 1024):
            if MACOS_BRIDGE_MARKER in previous + block:
                return
            previous = block[-len(MACOS_BRIDGE_MARKER):]
    raise ValueError('MACOS_BRIDGE_REQUIRED: rebuild the debug GPU dmengine locally from this checkout')


def host_platform(system=None, machine=None):
    system, machine = system or platform.system(), (machine or platform.machine()).lower()
    if system == 'Windows' and machine in ('amd64', 'x86_64'):
        return 'x86_64-win32'
    if system == 'Darwin' and machine in ('arm64', 'aarch64', 'x86_64'):
        return ('arm64' if machine in ('arm64', 'aarch64') else 'x86_64') + '-macos'
    raise ValueError(f'Unsupported automation host: {system}/{machine}')


def prepare(app, output=None, engine=None):
    target = host_platform()
    if target == 'x86_64-win32':
        if engine:
            raise ValueError('Windows custom engines are selected at server startup with --engine')
        return prepare_windows(app, output)
    return prepare_macos(app, output, engine, target)


def prepare_windows(app, output=None):
    app = Path(app).resolve()
    output = Path(output or ROOT / 'tmp/automation-runtime').resolve()
    jars = list(app.glob('packages/defold-*.jar'))
    runtimes = list(app.glob('packages/jdk-*/bin/java.exe'))
    compiler = shutil.which('javac')
    if not compiler:
        candidates = sorted(Path('C:/Program Files/Eclipse Adoptium').glob('jdk-25*/bin/javac.exe'))
        compiler = str(candidates[-1]) if candidates else None
    if len(jars) != 1 or len(runtimes) != 1 or not compiler:
        raise ValueError('Expected one installed Defold JAR/runtime and a Java 25 javac on PATH')
    output.mkdir(parents=True, exist_ok=True)
    source = ROOT / 'editor/src/java/com/defold/editor'
    inputs = [jars[0], source / 'AutomationWorker.java', source / 'WindowsGameWindow.java',
              source / 'WindowsGameProcess.java', source / 'AutomationPreviewBuilder.java',
              ROOT / 'scripts/windows/game-window-hook.c', ROOT / 'scripts/windows/game-simulator.h',
              ROOT / 'scripts/windows/automation-scene.h',
              Path(__file__)]
    hashes = {str(path): digest(path) for path in inputs}
    manifest_path = output / 'manifest.json'
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text())
        if manifest['inputs'] == hashes and all(Path(path).is_file() and digest(path) == value
                                               for path, value in manifest['outputs'].items()):
            return manifest
    native = output / 'native/x86_64-win32/lib/game_window_hook.dll'
    spec = importlib.util.spec_from_file_location('windows_pipeline', ROOT / 'scripts/windows/editor.py')
    pipeline = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(pipeline)
    pipeline.builder().build_windows_native(ROOT / 'scripts/windows/game-window-hook.c', native)
    classes = output / 'classes'
    classes.mkdir(exist_ok=True)
    subprocess.run([compiler, '-cp', str(jars[0]), '-d', str(classes),
                    str(source / 'AutomationWorker.java'), str(source / 'WindowsGameWindow.java'),
                    str(source / 'WindowsGameProcess.java'), str(source / 'AutomationPreviewBuilder.java')],
                   check=True, creationflags=subprocess.CREATE_NO_WINDOW)
    engine = output / 'engine/dmengine.exe'
    engine.parent.mkdir(exist_ok=True)
    with zipfile.ZipFile(jars[0]) as archive:
        engine.write_bytes(archive.read('libexec/x86_64-win32/dmengine.exe'))
        engine.with_suffix('.pdb').write_bytes(archive.read('_unpack/x86_64-win32/bin/dmengine.pdb'))
    outputs = [native, engine, engine.with_suffix('.pdb'), *classes.rglob('*.class')]
    manifest = dict(version=1, platform='x86_64-win32', inputs=hashes, outputs={str(p): digest(p) for p in outputs},
                    java=str(runtimes[0]), jar=str(jars[0]), classes=str(classes),
                    native=str(output / 'native'), engine=str(engine), unpack=str(output / 'unpack'))
    temporary = manifest_path.with_suffix('.part')
    temporary.write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    temporary.replace(manifest_path)
    return manifest


def prepare_macos(app, output, engine, target):
    app = Path(app).resolve()
    resources = app / 'Contents/Resources' if (app / 'Contents/Resources').is_dir() else app
    jars = list(resources.glob('packages/defold-*.jar'))
    runtimes = list(resources.glob('packages/jdk-*/bin/java'))
    runtimes += list(resources.glob('packages/jdk-*/Contents/Home/bin/java'))
    compiler = Path(os.environ['JAVA_HOME']) / 'bin/javac' if os.environ.get('JAVA_HOME') else shutil.which('javac')
    if len(jars) != 1 or len(runtimes) != 1 or not compiler:
        raise ValueError('Expected a Defold.app with one JAR/runtime and a Java 25 javac (JAVA_HOME or PATH)')
    if not engine or not Path(engine).is_file():
        raise ValueError('macOS requires --engine pointing to dmengine compiled locally from this checkout (not dmengine_headless/release)')
    engine = Path(engine).resolve()
    validate_macos_engine(engine)
    architecture = target.removesuffix('-macos')
    subprocess.run(['lipo', str(engine), '-verify_arch', architecture], check=True)
    output = Path(output or ROOT / 'tmp/automation-runtime').resolve()
    output.mkdir(parents=True, exist_ok=True)
    source = ROOT / 'editor/src/java/com/defold/editor'
    sources = [source / name for name in ('AutomationWorker.java', 'WindowsGameWindow.java', 'WindowsGameProcess.java', 'AutomationPreviewBuilder.java')]
    guard_source = ROOT / 'scripts/automation/macos-guard.c'
    hashes = {str(p): digest(p) for p in [jars[0], engine, *sources, guard_source, Path(__file__)]}
    manifest_path = output / 'manifest.json'
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text())
        if manifest.get('platform') == target and manifest['inputs'] == hashes and all(
                Path(p).is_file() and digest(p) == value for p, value in manifest['outputs'].items()):
            return manifest
    native, classes = output / 'native', output / 'classes'
    native.mkdir(exist_ok=True)
    classes.mkdir(exist_ok=True)
    guard = native / 'libautomation_guard.dylib'
    subprocess.run(['xcrun', 'clang', '-dynamiclib', '-O2', '-Wall', '-Wextra', '-Werror',
                    '-arch', architecture, str(guard_source), '-o', str(guard)], check=True)
    subprocess.run(['codesign', '--force', '--sign', '-', str(guard)], check=True)
    subprocess.run([str(compiler), '-cp', str(jars[0]), '-d', str(classes), *map(str, sources)], check=True)
    copied_engine = output / 'engine/dmengine'
    copied_engine.parent.mkdir(exist_ok=True)
    if engine != copied_engine:
        shutil.copy2(engine, copied_engine)
    copied_engine.chmod(copied_engine.stat().st_mode | 0o100)
    outputs = [guard, copied_engine, *classes.rglob('*.class')]
    manifest = dict(version=1, platform=target, inputs=hashes, outputs={str(p): digest(p) for p in outputs},
                    java=str(runtimes[0]), jar=str(jars[0]), classes=str(classes), native=str(native),
                    engine=str(copied_engine), unpack=str(output / 'unpack'))
    temporary = manifest_path.with_suffix('.part')
    temporary.write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    temporary.replace(manifest_path)
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', type=Path, required=True)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--engine', type=Path, help='macOS locally built debug GPU engine')
    arguments = parser.parse_args()
    print(json.dumps(prepare(arguments.app, arguments.output, arguments.engine), indent=2))
