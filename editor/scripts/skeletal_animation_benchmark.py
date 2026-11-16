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

"""Repeatable skeletal editor CPU and real-pulse benchmarks in an isolated project.

Delegates project copying, startup and virtual input to headless_editor.py.
Use --fixture-edn with an immutable editor document snapshot. All measured
mouse gestures assert canvas hits and actual edits; no desktop input is used.
"""
import argparse
import json
from pathlib import Path
import sys

import headless_editor as launcher


def main():
    parser = argparse.ArgumentParser(description=__doc__, add_help=False)
    parser.add_argument('--fixture-edn', type=Path, required=True)
    parser.add_argument('--measure', choices=['cpu', 'pulse'], default='cpu')
    parser.add_argument('--jfr', action='store_true')
    parser.add_argument('--textured-grid', action='store_true', help='Render the stress mesh using the tail fixture texture')
    parser.add_argument('--drag-events-per-frame', type=int, default=1)
    args, remaining = parser.parse_known_args()
    if not 1 <= args.drag_events_per_frame <= 100:
        parser.error('--drag-events-per-frame must be between 1 and 100')
    fixture = args.fixture_edn.resolve(strict=True)
    if '--output' not in remaining:
        parser.error('--output is required')
    output = Path(remaining[remaining.index('--output') + 1]).resolve()
    script_name = 'skeletal_animation_benchmark.clj' if args.measure == 'cpu' else 'skeletal_animation_pulse_benchmark.clj'
    script = Path(__file__).with_name(script_name).read_text()
    script = script.replace('"FIXTURE_PATH"', json.dumps(str(fixture)))
    script = script.replace('"OUTPUT_PATH"', json.dumps(str(output / 'performance.json')))
    evaluate = launcher.evaluate
    popen = launcher.subprocess.Popen

    def evaluate_with_benchmark(port, code, timeout):
        result = evaluate(port, code, timeout)
        if code.startswith('(headless/run-scenario!'):
            _, out, err = evaluate(port, script, 300)
            (output / 'performance.log').write_text(out + err)
            report = json.loads((output / 'performance.json').read_text())
            if report.get('disabled-handlers', 0) or report.get('stopped-timers', 0):
                raise RuntimeError('Benchmark disabled an editor handler or timer')
            print(json.dumps({'benchmark': args.measure, 'report': str(output / 'performance.json')}), flush=True)
        return result

    def recorded_popen(command, **kwargs):
        command = command[:1] + [f'-Ddefold.benchmark.drag-events={args.drag_events_per_frame}'] + command[1:]
        if args.textured_grid:
            command = command[:1] + ['-Ddefold.benchmark.textured-grid=true'] + command[1:]
        if args.jfr:
            command = command[:1] + [f'-XX:StartFlightRecording=filename={output / "profile.jfr"},settings=profile,dumponexit=true'] + command[1:]
        return popen(command, **kwargs)

    launcher.evaluate = evaluate_with_benchmark
    launcher.subprocess.Popen = recorded_popen
    sys.argv = [sys.argv[0]] + remaining
    try:
        result = launcher.main()
        if list(output.glob('hs_err_pid*.log')):
            raise RuntimeError('Native JVM crash during benchmark or shutdown; see output directory')
        return result
    finally:
        launcher.evaluate = evaluate
        launcher.subprocess.Popen = popen


if __name__ == '__main__':
    raise SystemExit(main())
