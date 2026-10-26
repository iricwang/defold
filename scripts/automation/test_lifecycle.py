"""Real-process failure cleanup and precompiled import; independent of a visible editor."""
import ctypes
import json
from pathlib import Path
import sys
import threading
import uuid

from core import Service
from build import ROOT, digest


def main():
    manifest = json.loads((ROOT / 'tmp/automation-runtime/manifest.json').read_text())
    directory = ROOT / 'tmp/automation-lifecycle' / uuid.uuid4().hex
    service = Service(manifest, [ROOT / 'scripts/automation/fixture'], directory)
    job = {'cancel': threading.Event()}
    try:
        build = service.build(next(iter(service.projects)), job)
        # Register the completed isolated fixture as an import source, not an arbitrary user path.
        importer = Service(manifest, [build['project']], directory / 'import')
        try:
            imported = importer.import_build(next(iter(importer.projects)), job)
            assert imported['source_policy'] == 'existing_compiled'
            assert imported['engine_sha256'] == digest(manifest['engine'])
            session = importer.start(imported['build_id'], {'width': 640, 'height': 480}, job)
            worker = importer.session(session['session_id'])['worker']
            kernel = ctypes.windll.kernel32
            kernel.OpenProcess.restype = ctypes.c_void_p
            kernel.WaitForSingleObject.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
            kernel.CloseHandle.argtypes = [ctypes.c_void_p]
            handle = kernel.OpenProcess(0x100000, False, session['pid'])
            assert handle, 'Could not retain exact game process handle'
            try:
                worker.process.kill()
                worker.process.wait(timeout=5)
                assert kernel.WaitForSingleObject(handle, 5000) == 0, 'Worker crash left a live game process'
            finally:
                kernel.CloseHandle(handle)
            print(json.dumps({'passed': True, 'checks': ['precompiled_import', 'engine_hash_preserved',
                                                        'worker_crash_kills_owned_game'], 'directory': str(directory)}))
        finally:
            importer.close()
    finally:
        service.close()


if __name__ == '__main__':
    main()
