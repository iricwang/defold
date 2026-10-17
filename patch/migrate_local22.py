#!/usr/bin/env python3
"""Open the exact local patch.22 installation on its one-time CI migration feed."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile

BASELINE = Path(__file__).resolve().parent / 'baselines/arm64-macos-local22.json'


def running_pids(app, listing):
    markers = (str(app / 'Contents/MacOS/Defold'), str(app / 'Contents/Resources/packages'))
    return [line.split(None, 1)[0] for line in listing.splitlines()
            if any(marker in line for marker in markers)]


def validate_app(app, baseline):
    for entry in baseline['files']:
        file = app / entry['path']
        if not file.is_file() or file.is_symlink() or stat.S_IMODE(file.stat().st_mode) != entry['mode']:
            raise ValueError('Installation does not match the local patch.22 baseline')
        with file.open('rb') as stream:
            if hashlib.file_digest(stream, 'sha256').hexdigest() != entry['sha256']:
                raise ValueError('Installation does not match the local patch.22 baseline')


def write_feed(path, url):
    temporary = path.with_suffix('.pending')
    temporary.write_text(json.dumps({'feed': url}) + '\n')
    temporary.replace(path)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', type=Path, default=Path('/Applications/Defold.app'))
    args = parser.parse_args(argv)
    app = args.app.resolve()
    listing = subprocess.check_output(['ps', '-Ao', 'pid=,command='], text=True)
    if running_pids(app, listing):
        parser.error('Save your projects and fully quit this Defold installation before migration.')
    baseline = json.loads(BASELINE.read_text())
    try:
        validate_app(app, baseline)
    except ValueError as error:
        parser.error(str(error))
    metadata = baseline['metadata']
    root = f'https://github.com/{metadata["repository"]}/releases/download/patch-{metadata["channel"]}'
    revision = baseline['migration_target_revision']
    feed = f'{root}-{revision}/manifest-arm64-macos-local22.json'
    normal = f'{root}/manifest.json'
    directory = Path(tempfile.mkdtemp(prefix='defold-local22-migration-', dir='/tmp'))
    config = directory / 'build.json'
    write_feed(config, feed)
    env = os.environ.copy()
    env['JDK_JAVA_OPTIONS'] = (env.get('JDK_JAVA_OPTIONS', '') + f' -Ddefold.patch.directory={directory}').strip()
    print('In Defold, choose Patch Updates (or Help > Check for Updates), then Download and Apply Patch.', flush=True)
    try:
        return subprocess.Popen([str(app / 'Contents/MacOS/Defold')], env=env).wait()
    finally:
        # The restarted application inherits the directory option. Keep it valid and
        # restore the regular feed atomically once the original application exits.
        write_feed(config, normal)


if __name__ == '__main__':
    raise SystemExit(main())
