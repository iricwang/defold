"""Packaged DNS resolver and real HTTPS regression; no machine DNS settings are changed."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[2]


def run(manifest, peer_ip=None):
    manifest = Path(manifest).resolve()
    runtime = json.loads(manifest.read_text(encoding='utf-8'))
    work = ROOT / 'tmp/remote-resolver-tests' / uuid.uuid4().hex
    classes = work / 'classes'
    classes.mkdir(parents=True)
    packaged = [str(manifest.parent / runtime[key]) for key in ('classes', 'jar')]
    compiler = shutil.which('javac')
    if not compiler and os.name == 'nt':
        compiler = str(sorted(Path('C:/Program Files/Eclipse Adoptium').glob('jdk-25*/bin/javac.exe'))[-1])
    if not compiler:
        raise RuntimeError('Java 25 javac is required')
    quiet = {'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {}
    sources = [ROOT/'remote/tests/java'/name for name in ('RemoteAddressResolverTest.java', 'RemoteSlowDnsTest.java')]
    subprocess.run([compiler, '-encoding', 'UTF-8', '-cp', os.pathsep.join(packaged), '-d', str(classes),
                    *map(str, sources)], check=True, **quiet)
    java = str(manifest.parent / runtime['java'])

    def invoke(classpath, main, *args):
        result = subprocess.run([java, '-cp', os.pathsep.join(classpath), main, *map(str, args)],
                                check=True, capture_output=True, text=True, encoding='utf-8', timeout=60, **quiet)
        return result.stdout.strip()

    # The production service registration takes precedence here, not a copied source file.
    checks = [invoke([*packaged, str(classes)], 'com.defold.editor.RemoteAddressResolverTest')]
    probe = []
    if peer_ip:
        for mode in ('original', 'fixed'):
            probe.append(json.loads(invoke([*packaged, str(classes)], 'com.defold.editor.RemoteAddressResolverTest', mode, peer_ip)))
    services = classes / 'META-INF/services'
    services.mkdir(parents=True)
    (services/'java.net.spi.InetAddressResolverProvider').write_text(
        'com.defold.editor.RemoteSlowDnsTest$Provider\n', encoding='utf-8')
    checks.append(invoke([str(classes), *packaged], 'com.defold.editor.RemoteSlowDnsTest', manifest, work/'state'))
    report = dict(checks=checks, peer_lookup=probe,
                  scope='Packaged Windows/macOS-independent Java resolver and isolated real TLS with blocked DNS; not physical phone playback.')
    (work/'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(dict(work=str(work), **report), indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest')
    parser.add_argument('--peer-ip', help='Optional numeric LAN peer for fresh-JVM old/fixed timing comparison')
    args = parser.parse_args()
    run(args.manifest, args.peer_ip)
