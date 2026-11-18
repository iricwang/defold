"""Build a standalone Remote Manager runtime; never modifies the installed editor."""
import argparse
import hashlib
import importlib.util
import json
import os
import plistlib
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent


def macos_icon(destination):
    """Package the supplied artwork at Apple's standard icon resolutions."""
    with tempfile.TemporaryDirectory(prefix='remote-icon-') as temporary:
        iconset = Path(temporary) / 'DefoldRemote.iconset'
        iconset.mkdir()
        for size in (16, 32, 128, 256, 512):
            for scale in (1, 2):
                name = f'icon_{size}x{size}' + ('@2x' if scale == 2 else '') + '.png'
                subprocess.run(['sips', '-z', str(size * scale), str(size * scale),
                                str(HERE/'icons/icon.png'), '--out', str(iconset/name)],
                               check=True, stdout=subprocess.DEVNULL)
        subprocess.run(['iconutil', '-c', 'icns', str(iconset), '-o', str(destination)], check=True)


def build(app, output, engine=None, shell=False):
    # Finder does not inherit the build terminal's PYTHONPATH.
    subprocess.run([sys.executable, '-I', '-c', 'import yaml'], check=True)
    spec = importlib.util.spec_from_file_location('automation_build', ROOT / 'scripts/automation/build.py')
    automation = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(automation)
    base = automation.prepare(app, ROOT / 'tmp/remote-manager-runtime', engine)
    sources = sorted((HERE / 'java').rglob('*.java')) + [ROOT / 'editor/src/java/com/defold/editor' / f for f in ('RemoteVideoEncoder.java', 'RemoteStreamQuality.java', 'RemoteAudioFile.java')]
    compiler = shutil.which('javac')
    if not compiler and os.name == 'nt':
        compiler = str(sorted(Path('C:/Program Files/Eclipse Adoptium').glob('jdk-25*/bin/javac.exe'))[-1])
    if not compiler:
        raise RuntimeError('Java 25 javac is required')
    quiet = {'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {}
    subprocess.run([compiler, '-encoding', 'UTF-8', '-cp', base['classes'] + os.pathsep + base['jar'], '-d', base['classes'], *map(str, sources)], check=True, **quiet)
    resources = sorted(p for p in (HERE/'resources').rglob('*') if p.is_file())
    for source in resources:
        target = Path(base['classes']) / source.relative_to(HERE/'resources')
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    # The sidecar must load the matching JNI profile API before the selected editor jar.
    codec = ROOT/'editor/resources/lib'/base['platform']/('remote_video.dll' if os.name == 'nt' else 'libremote_video.dylib')
    if codec.is_file():
        target = Path(base['classes'])/'lib'/base['platform']/codec.name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(codec,target)
    transport = Path(os.environ.get('DEFOLD_REMOTE_TRANSPORT', ROOT/'tmp/remote-transport-cargo/release'/('defold-remote-transport.exe' if os.name == 'nt' else 'defold-remote-transport')))
    inputs = {str(p.relative_to(ROOT)): automation.digest(p) for p in sources}
    inputs.update({str(p.relative_to(ROOT)): automation.digest(p) for p in resources})
    inputs.update(base['inputs'])
    if codec.is_file(): inputs['remoteCodec'] = automation.digest(codec)
    if transport.is_file(): inputs['remoteTransport'] = automation.digest(transport)
    for source in [HERE/'prepare.py', ROOT/'scripts/windows/local_extensions.py', Path(__file__)]:
        inputs[str(source.relative_to(ROOT))] = automation.digest(source)
    sdk = Path(os.environ.get('DYNAMO_HOME', ROOT/'tmp/dynamo_home'))
    configure = ROOT/'engine/build'/base['platform']/'.defold_cmake_configure.json'
    if configure.is_file():
        for arg in json.loads(configure.read_text(encoding='utf-8'))['args']:
            if arg.startswith('-DDEFOLD_SDK_ROOT') and '=' in arg:
                sdk = Path(arg.split('=', 1)[1])
    # Refuse to ship a runtime which only fails once a user starts a native project.
    spec = importlib.util.spec_from_file_location('remote_local_extensions', ROOT/'scripts/windows/local_extensions.py')
    local = importlib.util.module_from_spec(spec); spec.loader.exec_module(local)
    local.PLATFORM = base['platform']
    sdk_revision = local.sdk_revision(sdk)
    local.check_engine_libraries(sdk, ROOT/'engine/engine/build'/base['platform'])
    import re, zipfile
    with zipfile.ZipFile(base['jar']) as archive:
        revisions = set(re.findall(rb'\x01\x00\x28([0-9a-f]{40})', archive.read('com/dynamo/bob/archive/EngineVersion.class')))
    if revisions != {sdk_revision.encode('ascii')}:
        raise ValueError('Selected editor Bob and native extension SDK revisions differ')
    if automation.digest(base['engine']) != automation.digest(sdk/'bin'/base['platform']/('dmengine.exe' if os.name=='nt' else 'dmengine')):
        raise ValueError('Packaged engine differs from the installed native extension SDK')
    tool_paths = sorted({str(Path(tool).resolve().parent) for name in ('cmake', 'ninja') if (tool := shutil.which(name))})
    profile = {'python': sys.executable, 'repository': str(ROOT), 'sdk': str(sdk.resolve()), 'toolPaths': tool_paths}
    inputs['nativeBuild'] = profile
    revision = hashlib.sha256(json.dumps(inputs, sort_keys=True).encode()).hexdigest()[:16]
    output = Path(output).resolve()
    package = output / ('runtime-' + revision)
    if not (package / 'manifest.json').is_file():
        package.mkdir(parents=True, exist_ok=True)
        java_home = Path(base['java']).parent.parent
        for source, target in [(java_home, package/'jre'), (Path(base['classes']), package/'classes'), (Path(base['native']), package/'native')]:
            shutil.copytree(source, target, dirs_exist_ok=True)
        shutil.copy2(base['jar'], package/'editor.jar')
        if transport.is_file(): shutil.copy2(transport,package/transport.name)
        engine_name = 'dmengine.exe' if os.name == 'nt' else 'dmengine'
        shutil.copy2(base['engine'], package/engine_name)
        shutil.copy2(HERE/'prepare.py', package/'prepare.py')
        shutil.copy2(ROOT/'scripts/windows/local_extensions.py', package/'local_extensions.py')
        if os.name=='nt' and Path(base['engine']).with_suffix('.pdb').is_file():
            shutil.copy2(Path(base['engine']).with_suffix('.pdb'), package/'dmengine.pdb')
        manifest = dict(version=1, revision=revision, platform=base['platform'], java='jre/bin/javaw.exe' if os.name=='nt' else 'jre/bin/java',
                        jar='editor.jar', classes='classes', native='native', engine=engine_name)
        manifest['nativeBuild'] = profile
        manifest['engineDirectory'] = str(Path(app).resolve())
        manifest['files'] = {str(p.relative_to(package)): automation.digest(p) for p in package.rglob('*') if p.is_file()}
        (package/'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    config_path=output/'remote-manager.json'
    config=json.loads(config_path.read_text(encoding='utf-8')) if config_path.is_file() else {}
    config['manifest']=str(package.relative_to(output)/'manifest.json')
    config_path.write_text(json.dumps(config), encoding='utf-8')
    if shell:
        cargo = shutil.which('cargo') or str(Path.home()/'.cargo/bin'/('cargo.exe' if os.name=='nt' else 'cargo'))
        env = dict(os.environ, CARGO_TARGET_DIR=str(ROOT/'tmp/remote-manager-cargo'))
        subprocess.run([cargo, 'build', '--release', '--offline', '--manifest-path', str(HERE/'Cargo.toml')], env=env, check=True, **quiet)
        executable = 'defold-remote-manager.exe' if os.name=='nt' else 'defold-remote-manager'
        shutil.copy2(Path(env['CARGO_TARGET_DIR'])/'release'/executable, output/executable)
        if sys.platform == 'darwin':
            bundle = output/'Defold Remote Manager.app'
            contents = bundle/'Contents'
            macos = contents/'MacOS'
            resources = contents/'Resources'
            macos.mkdir(parents=True, exist_ok=True)
            resources.mkdir(parents=True, exist_ok=True)
            shutil.copy2(output/executable, macos/executable)
            # Runtime directories are content-addressed and contain read-only JRE files.
            if not (resources/package.name).exists():
                shutil.copytree(package, resources/package.name)
            bundled_config = dict(config, manifest=str(Path(package.name)/'manifest.json'))
            (macos/'remote-manager.json').unlink(missing_ok=True)
            (resources/'remote-manager.json').write_text(json.dumps(bundled_config), encoding='utf-8')
            macos_icon(resources/'DefoldRemote.icns')
            version = json.loads((HERE/'tauri.conf.json').read_text())['version']
            with (contents/'Info.plist').open('wb') as info:
                plistlib.dump(dict(CFBundleExecutable=executable, CFBundleIdentifier='com.defold.remote.manager',
                                  CFBundleName='Defold Remote Manager', CFBundleDisplayName='Defold Remote Manager',
                                  CFBundlePackageType='APPL', CFBundleIconFile='DefoldRemote.icns', CFBundleShortVersionString=version,
                                  CFBundleVersion=version, LSMinimumSystemVersion='11.0',
                                  NSHighResolutionCapable=True), info)
            subprocess.run(['codesign', '--force', '--sign', '-', str(bundle)], check=True)
    return {'directory': str(output), 'manifest': str(package/'manifest.json'), 'revision': revision}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', required=True)
    parser.add_argument('--output', default=str(ROOT/'tmp/remote-manager-delivery'))
    parser.add_argument('--engine', help='macOS debug GPU engine built locally from this checkout')
    parser.add_argument('--shell', action='store_true')
    args = parser.parse_args()
    print(json.dumps(build(args.app,args.output,args.engine,args.shell), indent=2))
