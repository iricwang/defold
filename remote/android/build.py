#!/usr/bin/env python3
"""Build the standalone APK with Android SDK 36 / Build Tools 36.1.0 and JDK 17+."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT'))
    parser.add_argument('--build-tools', type=Path)
    parser.add_argument('--android-jar', type=Path)
    parser.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'))
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    build = root / 'build'
    build.mkdir(exist_ok=True)
    tools = args.build_tools or (args.sdk / 'build-tools/36.1.0' if args.sdk else None)
    android = args.android_jar or (args.sdk / 'platforms/android-36/android.jar' if args.sdk else None)
    if not tools or not android:
        parser.error('Provide --sdk or both --build-tools and --android-jar')
    tools, android = tools.resolve(), android.resolve()
    zxing = root.parent / 'vendor/zxing-core-3.5.3.jar'
    key = root / 'signing/defold-remote-development.keystore'
    if not key.is_file():
        parser.error('Missing shared development signing key; restore remote/android/signing from Git')
    if not zxing.is_file():
        parser.error('Missing remote/vendor/zxing-core-3.5.3.jar')
    exe = '.exe' if os.name == 'nt' else ''

    def java_tool(name):
        return str(args.java_home / 'bin' / (name + exe)) if args.java_home else name

    def run(*command):
        subprocess.run([str(item) for item in command], check=True, cwd=root)

    required = [tools / ('aapt2' + exe), tools / ('zipalign' + exe), tools / 'lib/d8.jar',
                tools / 'lib/apksigner.jar', android]
    for item in required:
        if not item.is_file():
            parser.error('Missing SDK file: %s' % item)
    for folder in ('classes', 'dex'):
        path = build / folder
        if build.is_symlink() or path.is_symlink() or path.resolve().parent != (root / 'build').resolve():
            raise ValueError('Android build cleanup must stay inside remote/android/build')
        if path.exists():
            shutil.rmtree(path)
        path.mkdir()
    run(java_tool('javac'), '-source', '8', '-target', '8', '-Xlint:-options', '-encoding', 'UTF-8',
        '-classpath', str(android) + os.pathsep + str(zxing), '-d', build / 'classes', *sorted((root / 'src').rglob('*.java')))
    classes = build / 'classes.jar'
    with zipfile.ZipFile(classes, 'w') as archive:
        for path in sorted((build / 'classes').rglob('*.class')):
            archive.write(path, path.relative_to(build / 'classes'))
    run(java_tool('java'), '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '26',
        '--lib', android, '--output', build / 'dex', classes, zxing)
    unsigned = build / 'unsigned.apk'
    resources = build / 'resources.zip'
    run(tools / ('aapt2' + exe), 'compile', '--dir', root / 'res', '-o', resources)
    run(tools / ('aapt2' + exe), 'link', '-I', android, '--manifest', root / 'AndroidManifest.xml', '-o', unsigned, resources)
    with zipfile.ZipFile(unsigned, 'a', compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(root.parent / 'vendor/ZXING-LICENSE', 'assets/ZXING-LICENSE')
        for path in sorted((build / 'dex').glob('*.dex')):
            archive.write(path, path.name)
    aligned = build / 'aligned.apk'
    run(tools / ('zipalign' + exe), '-f', '-p', '4', unsigned, aligned)
    apk = build / 'DefoldRemote-debug.apk'
    run(java_tool('java'), '-jar', tools / 'lib/apksigner.jar', 'sign', '--ks', key, '--ks-pass', 'pass:android',
        '--key-pass', 'pass:android', '--out', apk, aligned)
    run(java_tool('java'), '-jar', tools / 'lib/apksigner.jar', 'verify', '--verbose', apk)
    run(tools / ('zipalign' + exe), '-c', '-p', '4', apk)
    print(apk)


if __name__ == '__main__':
    main()
