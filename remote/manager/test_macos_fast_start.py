"""Exercise directory selection and cached GPU starts in an owned macOS Manager."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import shutil
import time
import uuid

from PIL import Image
from test_android_project import ManagerPipe, image_metrics

ROOT = Path(__file__).resolve().parents[2]


def run(manifest):
    manifest = Path(manifest).resolve()
    runtime = json.loads(manifest.read_text())
    if not runtime['platform'].endswith('macos'):
        raise ValueError('This regression needs a native macOS runtime')
    work = ROOT/'tmp/remote-macos-fast-start-tests'/uuid.uuid4().hex
    work.mkdir(parents=True)
    project = work/'project'
    shutil.copytree(ROOT/'scripts/automation/fixture', project)
    # Select another installation directory with the same compiler bytes. This
    # is an isolated layout fixture, not a second installed editor application.
    selected = work/'selected-installation'
    selected.mkdir()
    (selected/'packages').symlink_to(Path(runtime['engineDirectory'])/'Contents/Resources/packages')
    owned = work/'manager'
    owned.mkdir()
    checks = []
    report = dict(outcome='failed', runtimeRevision=runtime['revision'], passed=checks,
                  scope='Actual macOS Manager, resource compilation and GPU frames; no APK build or phone test')
    manager = None
    project_id = None

    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        checks.append(name)

    def status():
        return next(p for p in manager.rpc('status')['projects'] if p['id'] == project_id)

    def running():
        deadline = time.monotonic()+240
        while time.monotonic() < deadline:
            state = status()
            if state['state'] == 'error':
                raise RuntimeError(state.get('failed_stage', '')+': '+state['error'])
            if state['state'] == 'running' and not state['busy']:
                return state
            time.sleep(.1)
        raise TimeoutError('Owned game did not report running')

    def frame(name):
        deadline = time.monotonic()+20
        while time.monotonic() < deadline:
            result = manager.rpc('thumbnail', id=project_id)
            if result.get('image'):
                path = work/(name+'.jpg')
                path.write_bytes(base64.b64decode(result['image'].split(',', 1)[1]))
                with Image.open(path) as image:
                    metrics = image_metrics(image)
                if metrics['centerDistinctColors'] > 100 and metrics['centerStddev'] > 20:
                    report[name] = metrics
                    return
            time.sleep(.1)
        raise AssertionError('No non-black GPU frame: '+name)

    def close():
        nonlocal manager
        check('owned Manager exits normally', not manager.close(project_id))
        manager = None

    try:
        manager = ManagerPipe(manifest, runtime, owned)
        manager.rpc('settings', prepare=False)
        manager.rpc('engine-directory', path=runtime['engineDirectory'])
        project_id = manager.rpc('add', path=str(project))['id']
        started = time.monotonic()
        manager.rpc('start', id=project_id)
        first = running()
        frame('first-frame')
        report['firstBuildAndFrameSeconds'] = round(time.monotonic()-started, 3)
        built_at, build = first['builtAt'], first['build']
        receipt_path = Path(build).parent/'receipt.json'
        receipt = json.loads(receipt_path.read_text())
        expected_engine = hashlib.sha256((manifest.parent/runtime['engine']).read_bytes()).hexdigest()
        check('real game uses the selected packaged engine', receipt['engineSha256'] == expected_engine)
        check('compiler and build inputs are pinned separately from Manager revision', bool(receipt['buildCompatibility']))
        manager.rpc('engine-directory', path=str(selected))
        check('same installation contents retain running session', status()['session_id'] == first['session_id'])
        try:
            manager.rpc('engine-directory', path=str(work/'missing'))
        except RuntimeError:
            pass
        else:
            raise AssertionError('Invalid installation accepted')
        check('invalid selection leaves active session and directory unchanged',
              status()['session_id'] == first['session_id'] and
              manager.rpc('status')['engine']['directory'] == str(selected))
        manager.rpc('stop', id=project_id)
        started = time.monotonic()
        manager.rpc('start', id=project_id)
        cached = running()
        frame('cached-frame')
        report['cachedStartAndFrameSeconds'] = round(time.monotonic()-started, 3)
        check('repeat start reuses original build and timestamp', cached['build'] == build and cached['builtAt'] == built_at)
        close()
        (owned/'manager.log').rename(owned/'manager-first.log')
        manager = ManagerPipe(manifest, runtime, owned)
        check('chosen engine directory survives restart', manager.rpc('status')['engine']['directory'] == str(selected))
        check('restart restores the cached project as ready', status()['state'] == 'ready' and status()['builtAt'] == built_at)
        started = time.monotonic()
        manager.rpc('start', id=project_id)
        restored = running()
        frame('restart-frame')
        report['restartCachedStartAndFrameSeconds'] = round(time.monotonic()-started, 3)
        check('restart launches cached resources without compiling', restored['build'] == build and restored['builtAt'] == built_at)
        check('all three starts created only one build directory', len(list((owned/'state/builds').iterdir())) == 1)
        check('engine identity remains unchanged after restart', json.loads(receipt_path.read_text())['engineSha256'] == expected_engine)
        close()
        # Simulate a Manager-only update while keeping actual build inputs fixed.
        upgraded = dict(runtime, revision='mac-test-manager-only-update')
        for key in ('java', 'jar', 'classes', 'native', 'engine'):
            upgraded[key] = str(manifest.parent/runtime[key])
        upgrade_manifest = work/'upgrade/manifest.json'
        upgrade_manifest.parent.mkdir()
        upgrade_manifest.write_text(json.dumps(upgraded))
        (owned/'manager.log').rename(owned/'manager-restart.log')
        manager = ManagerPipe(upgrade_manifest, upgraded, owned)
        check('Manager-only revision update preserves compatible cache', status()['state'] == 'ready' and status()['builtAt'] == built_at)
        manager.rpc('start', id=project_id)
        upgraded_state = running()
        frame('upgrade-frame')
        check('Manager-only upgrade runs without another build', upgraded_state['build'] == build and len(list((owned/'state/builds').iterdir())) == 1)
        report['outcome'] = 'passed'
    finally:
        if manager:
            report['forcedCleanup'] = manager.close(project_id)
        (work/'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(json.dumps(dict(report, work=str(work)), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    run(parser.parse_args().manifest)
