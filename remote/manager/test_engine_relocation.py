"""Run an already built native project through Manager import, reuse and restart.

The supplied build is read-only. All resources and the exact EXE/PDB pair are
copied into a fresh test directory. The copied executable's CodeView path points
to an unmounted drive, so the original build machine cannot supply its symbols.
No editor, user Manager, phone, or game business controls are operated.
"""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
import shutil
import time
import uuid

from PIL import Image
from test_android_project import ManagerPipe, image_metrics

ROOT = Path(__file__).resolve().parents[2]


def run(manifest, source, previous_manifest=None):
    manifest, source = Path(manifest).absolute(), Path(source).absolute()
    runtime = json.loads(manifest.read_text(encoding='utf-8'))
    if runtime['platform'] != 'x86_64-win32':
        raise ValueError('This relocation regression is specific to Windows PDBs')
    original = source/'build/x86_64-win32/dmengine.exe'
    if not original.is_file() or not (source/'build/default/game.projectc').is_file():
        raise ValueError('Choose an already built native project with build/default and EXE/PDB')
    spec = importlib.util.spec_from_file_location('engine_symbols_test', ROOT/'scripts/windows/test_engine_symbols.py')
    symbols = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(symbols)
    originals = {str(path): symbols.digest(path) for path in (original, original.with_suffix('.pdb'))}
    work = ROOT/'tmp/remote-engine-relocation-tests'/uuid.uuid4().hex
    work.mkdir(parents=True)
    # Keep both manifests beside each other, as in an installed content-addressed
    # runtime update. Only manifests are copied; all packaged binaries are read-only.
    def isolated_manifest(value):
        value = Path(value).absolute()
        content = json.loads(value.read_text(encoding='utf-8'))
        for key in ('java', 'classes', 'jar', 'engine', 'native'):
            content[key] = str(value.parent/content[key])
        target = work/('runtime-'+content['revision'])/'manifest.json'
        target.parent.mkdir()
        target.write_text(json.dumps(content), encoding='utf-8')
        return target, content
    initial_manifest, initial_runtime = isolated_manifest(previous_manifest) if previous_manifest else (manifest, runtime)
    if previous_manifest:
        manifest, runtime = isolated_manifest(manifest)
    project = work/'native-project'
    shutil.copytree(source, project, ignore=shutil.ignore_patterns('.git'))
    data, _, recorded, isolated, _ = symbols.isolated_executable(original)
    (project/'build/x86_64-win32/dmengine.exe').write_bytes(data)
    checks = []
    report = dict(outcome='failed', work=str(work), runtimeRevision=runtime['revision'],
                  recordedPdb=recorded, isolatedPdb=isolated, passed=checks,
                  scope='Owned Windows Manager and real native game; no physical Android/Wi-Fi validation')
    manager = None
    project_id = None

    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        checks.append(name)

    def status():
        return next(p for p in manager.rpc('status')['projects'] if p['id'] == project_id)

    def await_state(expected):
        deadline = time.monotonic()+180
        while time.monotonic() < deadline:
            current = status()
            if current['state'] == 'error':
                raise RuntimeError(current.get('failed_stage', '')+': '+current['error'])
            if current['state'] == expected and not current['busy']:
                return current
            time.sleep(.2)
        raise TimeoutError('Manager did not reach '+expected)

    def capture(name):
        deadline = time.monotonic()+15
        while time.monotonic() < deadline:
            result = manager.rpc('thumbnail', id=project_id)
            if result.get('image'):
                target = work/(name+'.jpg')
                target.write_bytes(base64.b64decode(result['image'].split(',', 1)[1]))
                with Image.open(target) as image:
                    metrics = image_metrics(image)
                if metrics['centerDistinctColors'] > 100 and metrics['centerStddev'] > 20:
                    report[name] = dict(image=str(target), **metrics)
                    return
            time.sleep(.2)
        raise AssertionError('No non-black GPU image after '+name)

    try:
        first = work/'first'; first.mkdir()
        manager = ManagerPipe(initial_manifest, initial_runtime, first)
        manager.rpc('settings', prepare=False)
        project_id = manager.rpc('add', path=str(project))['id']
        manager.rpc('import', id=project_id)
        ready = await_state('ready')
        engine = Path(ready['engine'])
        check('import copied the matching PDB beside the relocated engine', engine.with_suffix('.pdb').is_file())
        receipt_path = Path(ready['build']).parent/'receipt.json'
        receipt = json.loads(receipt_path.read_text(encoding='utf-8'))
        check('import receipt pins both engine and PDB', all(receipt.get(k) for k in ('engineSha256', 'symbolsSha256')))
        built_at = ready['builtAt']
        start_time = time.monotonic()
        manager.rpc('start', id=project_id)
        running = await_state('running')
        report['cachedStartSeconds'] = time.monotonic()-start_time
        capture('imported-frame')
        check('imported engine starts without the original PDB path', bool(running['session_id']))
        manager.rpc('stop', id=project_id)
        check('intact imported build is reused without a rebuild', status()['builtAt'] == built_at)
        check('owned Manager shuts down normally', not manager.close(project_id))
        manager = None
        # Keep logs distinct while sharing the same owned registry and receipts.
        (first/'manager.log').rename(first/'manager-first.log')
        manager = ManagerPipe(manifest, runtime, first)
        restored = status()
        check('Manager restart restores the verified import as ready', restored['state'] == 'ready' and restored['builtAt'] == built_at)
        start_time = time.monotonic()
        manager.rpc('start', id=project_id)
        running = await_state('running')
        report['restartCachedStartSeconds'] = time.monotonic()-start_time
        report['previousRuntimeRevision'] = initial_runtime['revision']
        check('no additional build directory on repeated start and upgrade', len(list((first/'state/builds').iterdir())) == 1)
        capture('restart-frame')
        check('restored engine starts and renders without compilation', running['builtAt'] == built_at)
        if runtime.get('engineDirectory'):
            manager.rpc('engine-directory', path=runtime['engineDirectory'])
            check('selecting identical global engine preserves running session and cache', status()['session_id']==running['session_id'] and status()['builtAt']==built_at)
            try:
                manager.rpc('engine-directory', path=str(work/'missing-engine'))
                raise AssertionError('Invalid engine directory was accepted')
            except RuntimeError:
                pass
            check('invalid engine directory does not interrupt the running game', status()['session_id']==running['session_id'])
        after = json.loads(receipt_path.read_text(encoding='utf-8'))
        check('engine and symbol identities remain pinned after restart', all(after[k] == receipt[k] for k in ('engineSha256', 'symbolsSha256')))
        check('original engine and PDB remain untouched', all(symbols.digest(Path(path)) == value for path, value in originals.items()))
        report['outcome'] = 'passed'
    finally:
        if manager:
            report['forcedOwnedCleanup'] = manager.close(project_id)
        (work/'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    parser.add_argument('--project-build', required=True, type=Path)
    parser.add_argument('--previous-manifest', type=Path, help='Verify reuse across an actual packaged Manager upgrade')
    args = parser.parse_args()
    run(args.manifest, args.project_build, args.previous_manifest)
