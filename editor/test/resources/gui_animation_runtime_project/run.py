#!/usr/bin/env python3
"""Build and execute the real GUI game; require its completion signal and every assertion."""
import argparse
import json
from pathlib import Path
import subprocess
import sys

from setup import install

ROOT = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bob', required=True, type=Path)
    parser.add_argument('--engine', required=True, type=Path)
    parser.add_argument('--java', default='java')
    parser.add_argument('--platform', default='arm64-macos', choices=['arm64-macos', 'x86_64-macos'])
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--repeat', type=int, default=2)
    args = parser.parse_args()
    if args.repeat < 1:
        parser.error('--repeat must be positive')
    install()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    command = [args.java, '-jar', str(args.bob.resolve()), '--root', str(ROOT), '--platform', args.platform, 'build']
    with (output / 'build.log').open('w') as log:
        subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=180)
    for iteration in range(1, args.repeat + 1):
        result_file = output / f'run-{iteration}.json'
        result_file.unlink(missing_ok=True)
        with (output / f'run-{iteration}.log').open('w') as log:
            process = subprocess.run([str(args.engine.resolve()), '--config=regression.exit=1',
                                      f'--config=regression.output={result_file}', 'build/default/game.projectc'],
                                     cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=100)
        if not result_file.is_file():
            raise RuntimeError(f'Run {iteration} did not produce its completion signal; see log')
        result = json.loads(result_file.read_text())
        if process.returncode or not result.get('completed') or not result['checks'] or result['failures'] or any(not check['passed'] for check in result['checks']):
            raise RuntimeError(f'Run {iteration} failed: {result["failures"]} assertions; see {result_file}')
        print(f'Run {iteration}: {len(result["checks"])} assertions passed; {result["frames"]} engine frames')
    return 0


if __name__ == '__main__':
    sys.exit(main())
