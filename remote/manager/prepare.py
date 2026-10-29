"""Automatic, local-only resource/native build used by Remote Manager."""
import argparse
import sys
sys.dont_write_bytecode = True  # Keep signed macOS app resources read-only.
import configparser
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import threading
import zipfile


def write_state(path, **value):
    temporary = path.with_suffix('.part')
    temporary.write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')
    temporary.replace(path)


def has_extensions(project):
    if any(project.rglob('ext.manifest')):
        return True
    for path in (project / '.internal/lib').glob('*.zip'):
        with zipfile.ZipFile(path) as archive:
            if any(n == 'ext.manifest' or n.endswith('/ext.manifest') for n in archive.namelist()):
                return True
    return False


def validate_native_engine(engine, platform):
    if not engine.is_file() or not engine.stat().st_size:
        raise RuntimeError('本地构建没有生成项目对应的原生引擎')
    if platform.endswith('win32'):
        symbols = engine.with_suffix('.pdb')
        if not symbols.is_file() or not symbols.stat().st_size:
            raise RuntimeError('Windows 原生引擎缺少配套调试符号，请重新构建：' + str(symbols))


def prepare(manifest, project, cache):
    root = manifest.parent
    runtime = json.loads(manifest.read_text(encoding='utf-8'))
    path = lambda key: root / runtime[key]
    status = project.parent / 'progress.json'
    stage = '检查运行环境'
    server = builder = thread = None
    kind = 'environment'
    command = [str(path('java')), '--enable-native-access=ALL-UNNAMED', '-Dfile.encoding=UTF-8',
               '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Djava.awt.headless=true',
               '-cp', str(path('classes')) + os.pathsep + str(path('jar')), 'com.dynamo.bob.Bob',
               '--root', str(project), '--platform', runtime['platform'],
               '--architectures', runtime['platform'], '--variant', 'debug']
    quiet = {'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {}

    def run(args):
        import sys
        with subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, **quiet) as process:
            for chunk in iter(lambda: process.stdout.read(8192), b''):
                sys.stdout.buffer.write(chunk);sys.stdout.buffer.flush()
            if process.wait():
                raise RuntimeError('构建失败，请查看下方编译日志')

    try:
        config = configparser.ConfigParser(interpolation=None, strict=False)
        config.read(project / 'game.project', encoding='utf-8')
        if config.has_section('project') and any(key == 'dependencies' or key.startswith('dependencies#')
               for key, value in config.items('project') if value.strip()):
            stage = '同步项目依赖';kind = 'dependency'
            write_state(status, stage=stage)
            # Dependency downloads contain no project source upload; engine endpoint remains loopback.
            run(command + ['--build-server', 'http://127.0.0.1:1', 'resolve'])
        stage = '迁移构建副本中的旧版 GUI 资源';kind = 'project'
        write_state(status, stage=stage)
        run(command[:command.index('com.dynamo.bob.Bob')] +
            ['com.defold.editor.RemoteGuiMigration', str(project)])
        native = has_extensions(project)
        extra = ['--build-server', 'http://127.0.0.1:1']
        if native:
            stage = '准备本地原生扩展工具链';kind = 'environment'
            write_state(status, stage=stage)
            profile = runtime.get('nativeBuild', {})
            os.environ['PATH'] = os.pathsep.join([*profile.get('toolPaths', []), os.environ.get('PATH', '')])
            repository, sdk = Path(profile.get('repository', '')), Path(profile.get('sdk', ''))
            if not (sdk/'share/java/bob.jar').is_file() or not (repository/'engine/build'/runtime['platform']/'.defold_cmake_configure.json').is_file():
                raise RuntimeError('本机缺少与运行版本匹配的原生扩展 SDK / CMake 工具链')
            spec = importlib.util.spec_from_file_location('local_extensions', root/'local_extensions.py')
            local = importlib.util.module_from_spec(spec);spec.loader.exec_module(local)
            local.ROOT = repository;local.PLATFORM = runtime['platform']
            builder = local.Builder(sdk, project.parent/'native-jobs', cache)
            token = secrets.token_urlsafe(32)
            server = local.create_server(builder, token=token)
            thread = threading.Thread(target=server.serve_forever, daemon=True);thread.start()
            extra = ['--build-server', 'http://127.0.0.1:' + str(server.server_port),
                     '--build-server-header', 'X-Remote-Build-Token:' + token]
        stage = '编译原生扩展与游戏资源' if native else '构建游戏资源';kind = 'project'
        write_state(status, stage=stage)
        run(command + extra + ['build'])
        engine = path('engine')
        if native:
            engine = project/'build'/runtime['platform'].replace('-macos','-osx')/('dmengine.exe' if os.name=='nt' else 'dmengine')
            validate_native_engine(engine, runtime['platform'])
            if os.name!='nt':
                engine.chmod(engine.stat().st_mode | 0o111)
                subprocess.run(['lipo',str(engine),'-verify_arch',runtime['platform'].split('-')[0]],check=True)
                if b'Editor automation bridge protocol 1 (macOS HID)' not in engine.read_bytes():
                    raise RuntimeError('本地引擎 SDK 缺少 macOS 后台输入桥接，请更新运行组件')
        write_state(status, stage='本地构建完成', engine=str(engine.resolve()), native=native)
    except Exception as error:
        write_state(status, stage=stage, error=str(error), errorKind=kind)
        raise
    finally:
        if server:
            server.shutdown();server.server_close()
        if thread:
            thread.join()
        if builder:
            builder.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('project', type=Path)
    parser.add_argument('cache', type=Path)
    args = parser.parse_args()
    prepare(args.manifest.resolve(), args.project.resolve(), args.cache.resolve())
