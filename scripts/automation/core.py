"""Owned background sessions and immutable screenshot artifacts. No desktop automation."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import configparser
import html
import json
import os
from pathlib import Path
import queue
import re
import shutil
import subprocess
import threading
import time
import urllib.request
import uuid

from build import digest, host_platform, validate_macos_engine
import images as image_evidence
import resources as preview_resources
import layout as gui_layout
import editor_bridge
import baselines
import conditions
import annotations
import gestures
import sequences

CREATE_FLAGS = getattr(subprocess, 'CREATE_NO_WINDOW', 0)


class AutomationError(Exception):
    def __init__(self, code, message, details=None):
        self.code = code
        self.details = details
        super().__init__(message)


def atomic_json(path, data):
    path = Path(path)
    temporary = path.with_suffix(path.suffix + '.part')
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
    temporary.replace(path)


def local_json(port, path, timeout=5):
    # Explicitly ignore proxy environment for local engine communication.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(f'http://127.0.0.1:{int(port)}{path}', timeout=timeout) as response:
        body = response.read(16 * 1024 * 1024 + 1)
        if len(body) > 16 * 1024 * 1024:
            raise AutomationError('RESPONSE_TOO_LARGE', 'Runtime scene response exceeds 16 MiB')
        return json.loads(body)


class Worker:
    def __init__(self, manifest, directory):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True)
        self.lock = threading.RLock()
        self.responses = queue.Queue()
        self.stderr = (self.directory / 'worker.log').open('wb')
        self.process = subprocess.Popen([
            manifest['java'], '--enable-native-access=ALL-UNNAMED', '-Djava.awt.headless=true',
            '-Ddefold.editor.sha1=automation-v1', '-Ddefold.unpack.path=' + manifest['unpack'],
            '-cp', manifest['classes'] + os.pathsep + manifest['jar'],
            'com.defold.editor.AutomationWorker', str(self.directory), manifest['native']],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.stderr,
            text=True, encoding='utf-8', creationflags=CREATE_FLAGS)
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self):
        try:
            for line in self.process.stdout:
                self.responses.put(json.loads(line))
        except Exception as error:
            self.responses.put({'ok': False, 'error': str(error)})
        finally:
            self.responses.put({'ok': False, 'error': 'WORKER_EXITED'})

    def call(self, command, timeout=40, **arguments):
        with self.lock:
            if self.process.poll() is not None:
                raise AutomationError('ENGINE_UNAVAILABLE', 'Worker has exited; inspect worker.log')
            self.process.stdin.write(json.dumps({'command': command, **arguments}) + '\n')
            self.process.stdin.flush()
            try:
                response = self.responses.get(timeout=timeout)
            except queue.Empty:
                # Never reuse a pipe after an ambiguous timeout: a late reply could belong to the previous action.
                self.close()
                raise AutomationError('CONDITION_TIMEOUT', 'Worker response timed out; session closed, operation may be partial')
            if not response['ok']:
                raise AutomationError('RUNTIME_FAILED', response['error'])
            return response['result']

    def close(self):
        with self.lock:
            if self.process.poll() is None:
                try:
                    self.process.stdin.close()  # EOF releases input and destroys this worker's owned game.
                    self.process.wait(timeout=15)
                except (OSError, subprocess.TimeoutExpired):
                    self.process.kill()
                    self.process.wait(timeout=5)
            self.stderr.close()
            # Shared pixels are transient (128 MiB); keep only encoded deliverables after process exit.
            try:
                (self.directory / 'runtime.shared').unlink(missing_ok=True)
            except PermissionError:
                # A failed OS cleanup is diagnostic, not grounds to discard screenshots/report data.
                pass


class Service:
    def __init__(self, manifest, projects, artifact_root, engine=None, baseline_root=None):
        self.manifest = manifest
        self.platform = manifest.get('platform', host_platform())
        self.windows = self.platform.endswith('-win32')
        self.root = Path(artifact_root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.baselines = baselines.Store(baseline_root or self.root / 'baselines')
        self.engine = str(Path(engine).resolve()) if engine else manifest['engine']
        self.custom_engine = bool(engine)
        self.projects = {}
        for path in projects:
            path = Path(path).resolve()
            if not (path / 'game.project').is_file():
                raise ValueError('Not a Defold project: ' + str(path))
            self.projects[hashlib.sha256(str(path).encode()).hexdigest()[:16]] = path
        self.sessions, self.builds, self.artifacts, self.jobs = {}, {}, {}, {}
        self.comparisons = {}
        self.evidence = {}
        self.runs = {}
        self.sequences, self.sequence_active = {}, set()
        self.operations = {}
        self.lock = threading.RLock()
        self.executor = ThreadPoolExecutor(max_workers=2, thread_name_prefix='defold-automation')
        self.closed = False

    def project(self, project_id):
        if project_id not in self.projects:
            raise AutomationError('PROJECT_NOT_REGISTERED', 'Only explicitly registered projects are accessible')
        return self.projects[project_id]

    def discover(self):
        return {'projects': [{'project_id': key, 'path': str(path),
                              'editor_available': (path / '.internal/editor.port').is_file()}
                             for key, path in self.projects.items()],
                'platform': self.platform,
                'capabilities': ['owned_hidden_opengl', 'disk_snapshot_build', 'device_resolution_safe_area',
                                 'input_ack', 'png_capture', 'image_crop', 'image_compare', 'runtime_scene_live', 'log_wait', 'reports', 'scenario_matrix',
                                 'disk_resource_search', 'isolated_resource_runtime_preview', 'compiled_resource_runtime_preview', 'compiled_incremental_import', 'preview_support_objects',
                                 'editor_gui_snapshot_transactions', 'opt_in_application_ack', 'device_inspection_overlay',
                                 'persistent_image_baselines', 'scenario_baseline_assert', 'temporal_capture_sequence',
                                 'bounded_job_wait']
                                + (['same_render_scene_snapshot','gui_rectangle_queries','node_click','layout_checks',
                                    'scenario_semantic_steps', 'element_condition_wait', 'layout_annotation', 'node_drag'] if self.windows else []),
                'scenario_versions': [1, 2],
                'unsupported': ['attached_session', 'native_extension_build',
                                'native_extension_source_preview', 'metal', 'hot_reload', 'text_glyph_geometry', 'custom_render_geometry']
                               + ([] if self.windows else ['same_render_scene_snapshot']),
                'conditional_capabilities': {'editor_gui_snapshot_transactions':'Requires a running editor with /automation/v1 routes',
                                             'same_render_scene_snapshot':'Windows exact PDB/ABI checks; capture metadata is authoritative',
                                             'opt_in_application_ack':'Requires explicit sdk/automation.lua integration and app_channel'},
                'upstream_bridge_integrated': False,
                'input_ack_level': 'window_dispatch' if self.windows else 'hid_snapshot', 'protocol_version': 1}

    def operation(self, key, payload, function):
        fingerprint = hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()
        with self.lock:
            existing = self.operations.get(key)
            if existing:
                if existing['fingerprint'] != fingerprint:
                    raise AutomationError('IDEMPOTENCY_MISMATCH', 'Operation ID was used with different parameters')
                if 'error' in existing:
                    raise AutomationError(existing.get('error_code', 'OPERATION_FAILED'), existing['error'])
                if 'result' not in existing:
                    raise AutomationError('OPERATION_PENDING', 'Operation is still running; do not replay')
                return existing['result']
            if self.closed:
                raise AutomationError('SERVICE_CLOSED', 'Automation service is shutting down')
            journal = self.root / ('operation-' + hashlib.sha256(key.encode()).hexdigest() + '.json')
            record = {'operation_id': key, 'fingerprint': fingerprint, 'state': 'accepted'}
            self.operations[key] = record
            if journal.exists():
                # Never turn a recovered operation into a permanently pending in-memory request.
                record.update(state='recovery_required', error_code='RECOVERY_REQUIRED',
                              error='Operation exists from an earlier service process; inspect journal')
                raise AutomationError(record['error_code'], record['error'])
        try:
            atomic_json(journal, record)
            result = function()
            with self.lock:
                record.update(state='complete', result=result)
            atomic_json(journal, record)
            return result
        except Exception as error:
            with self.lock:
                record.update(state='failed', error=str(error))
            atomic_json(journal, record)
            raise

    def submit(self, operation_id, payload, function):
        def create():
            job_id = uuid.uuid4().hex
            job = {'job_id': job_id, 'state': 'queued', 'cancel': threading.Event(), 'process': None,
                   'done': threading.Event(), 'created_at': time.time()}
            def run():
                with self.lock:
                    job.update(state='running', started_at=time.time())
                outcome = {'state': 'failed', 'error_code': 'JOB_INTERRUPTED', 'error': 'Job interrupted'}
                try:
                    if job['cancel'].is_set():
                        raise AutomationError('CANCELLED', 'Job cancelled before execution')
                    result = function(job)
                    outcome = {'state': 'complete', 'result': result}
                except Exception as error:
                    outcome = {'state': 'failed', 'error': str(error),
                               'error_code': getattr(error, 'code', 'INTERNAL_ERROR')}
                    if getattr(error, 'details', None) is not None:
                        outcome['error_details'] = error.details
                finally:
                    with self.lock:
                        job.update(outcome)
                        if job['cancel'].is_set():
                            job['state'] = 'cancelled'
                        job.update(process=None, finished_at=time.time())
                        job['done'].set()
            def finished(future):
                if future.cancelled():
                    with self.lock:
                        job.update(state='cancelled', error_code='CANCELLED',
                                   error='Job cancelled before execution', finished_at=time.time())
                        job['done'].set()
            with self.lock:
                if self.closed:
                    raise AutomationError('SERVICE_CLOSED', 'Automation service is shutting down')
                future = self.executor.submit(run)
                job['future'] = future
                self.jobs[job_id] = job
                future.add_done_callback(finished)
            return {'job_id': job_id}
        return self.operation(operation_id, payload, create)

    def job_status(self, job_id, cancel=False):
        process = None
        with self.lock:
            job = self.jobs.get(job_id)
            if not job:
                raise AutomationError('JOB_NOT_FOUND', job_id)
            if cancel and not job['done'].is_set():
                job['cancel'].set()
                job['future'].cancel()
                process = job.get('process')
        if process and process.poll() is None:
            try:
                process.terminate()
            except OSError:
                if process.poll() is None:
                    raise
        with self.lock:
            return {**{key: value for key, value in job.copy().items()
                       if key not in ('cancel', 'process', 'done', 'future')},
                    'terminal': job['done'].is_set(), 'cancel_requested': job['cancel'].is_set()}

    def job_wait(self, job_id, timeout_ms=10000):
        if type(timeout_ms) is not int or not 0 <= timeout_ms <= 10000:
            raise AutomationError('INVALID_TIMEOUT', 'timeout_ms must be an integer from 0 to 10000')
        with self.lock:
            job = self.jobs.get(job_id)
            if not job:
                raise AutomationError('JOB_NOT_FOUND', job_id)
        job['done'].wait(timeout_ms / 1000)
        return self.job_status(job_id)

    def resources(self, project_id, query='', types=None, offset=0, limit=50):
        try:
            return preview_resources.search(self.project(project_id), query, types, offset, limit, self.root)
        except ValueError as error:
            raise AutomationError('RESOURCE_SEARCH_FAILED', str(error)) from error

    def editor_call(self, project_id, endpoint, request):
        try:
            return editor_bridge.call(self.project(project_id), endpoint, request)
        except (ValueError, OSError) as error:
            raise AutomationError('EDITOR_ADAPTER_ERROR', str(error)) from error

    def editor_preview(self, project_id, resource_path, job, include_gui_paths=None):
        paths = list(dict.fromkeys([resource_path] + (include_gui_paths or [])))
        if len(paths)>32 or any(not path.endswith('.gui') for path in paths):
            raise AutomationError('INVALID_RESOURCE', 'Editor snapshot preview supports up to 32 explicit GUI resources')
        snapshots = [self.editor_call(project_id, 'snapshot', {'path':path}) for path in paths]
        # Never combine snapshots from different editor histories or restarts.
        if len({(s['editorSessionId'],s['historyRevision']) for s in snapshots}) != 1:
            raise AutomationError('EDITOR_CONFLICT', 'Editor changed while gathering GUI snapshots')
        return self.build(project_id, job, resource_path, snapshots)

    def build(self, project_id, job, resource_path=None, editor_snapshots=None):
        project = self.project(project_id)
        if resource_path is not None:
            try:
                preview_resources.resolve(project, resource_path)
            except ValueError as error:
                raise AutomationError('INVALID_RESOURCE', str(error)) from error
        build_id = uuid.uuid4().hex
        target = self.root / 'builds' / build_id / 'project'
        target.mkdir(parents=True)
        files, native = {}, False
        # Copy a disk snapshot. Never save the user's editor graph or overwrite their build directory.
        for base, directories, names in os.walk(project, followlinks=False):
            relative = Path(base).relative_to(project)
            directories[:] = [name for name in directories if name not in ('.git', 'build', '.DS_Store')
                              and not preview_resources.is_link(Path(base) / name)
                              and not (Path(base) / name).resolve().is_relative_to(self.root)]
            if relative.parts and relative.parts[0] == '.internal':
                directories[:] = [name for name in directories if len(relative.parts) > 1 or name == 'lib']
                if len(relative.parts) == 1:
                    names = []
            for name in names:
                if job['cancel'].is_set():
                    raise AutomationError('CANCELLED', 'Snapshot cancelled')
                source = Path(base) / name
                if source.is_symlink():
                    raise AutomationError('UNSUPPORTED_RESOURCE', 'Symlink resource: ' + str(source))
                destination = target / relative / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                before = source.stat()
                shutil.copyfile(source, destination)
                after = source.stat()
                if (before.st_mtime_ns, before.st_size) != (after.st_mtime_ns, after.st_size):
                    raise AutomationError('RESOURCE_CONFLICT', 'Source changed during snapshot: ' + str(source))
                files[(relative / name).as_posix()] = digest(destination)
                native |= name == 'ext.manifest'
                native |= preview_resources.native_library_archive(destination, relative / name)
        if resource_path is not None and native:
            raise AutomationError('UNSUPPORTED_CAPABILITY', 'Native-extension source preview requires a local build first; use build_import followed by preview_import to preview existing compiled resources')
        source_policy = 'editor_gui_snapshot' if editor_snapshots else 'disk'
        for snapshot in editor_snapshots or []:
            relative = preview_resources.resource_relative(snapshot['path'])
            output = target / str(relative)
            output.write_text(snapshot['source'], encoding='utf-8', newline='')
            files[str(relative)] = digest(output)
        fingerprint = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()
        for previous in self.builds.values():
            if (previous['project_id'] == project_id and previous['source_policy'] == source_policy
                    and previous['resource_fingerprint'] == fingerprint
                    and previous.get('preview', {}).get('resource_path') == resource_path):
                # Only remove this job's newly created scratch directory after checking its exact ownership.
                scratch = target.parent.resolve()
                if scratch.parent != (self.root / 'builds').resolve() or scratch.name != build_id:
                    raise AutomationError('INVALID_PATH', 'Unexpected build scratch directory')
                shutil.rmtree(scratch)
                cached = {**previous, 'cache_hit': True}
                if editor_snapshots:
                    cached['requested_editor_snapshots'] = [{key:snap[key] for key in ('path','dirty','editorSessionId','editorGraphRevision')} for snap in editor_snapshots]
                return cached
        # Resource-only builds deliberately disable the remote extension compiler, including accidental fallback.
        command = [self.manifest['java'], '--enable-native-access=ALL-UNNAMED', '-cp', self.manifest['jar'],
                   'com.dynamo.bob.Bob', '--root', str(target), '--platform', self.platform,
                   '--build-server', 'http://127.0.0.1:1', 'build']
        preview, generated = None, []
        if resource_path is not None:
            try:
                settings = target.parent / 'preview.settings'
                preview, generated = preview_resources.prepare(target, resource_path, settings)
            except ValueError as error:
                raise AutomationError('INVALID_RESOURCE', str(error)) from error
            command[-1:-1] = ['--settings', str(settings)]
        log = target.parent / 'build.log'
        with log.open('wb') as output:
            process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT,
                                       creationflags=CREATE_FLAGS)
            job['process'] = process
            if job['cancel'].is_set():
                process.terminate()
            try:
                code = process.wait(timeout=300)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait()
                raise AutomationError('BUILD_TIMEOUT', str(log))
        if job['cancel'].is_set():
            raise AutomationError('CANCELLED', 'Build cancelled')
        if code:
            raise AutomationError('BUILD_FAILED', log.read_text(encoding='utf-8', errors='replace')[-6000:])
        compiled = target / 'build/default/game.projectc'
        if not compiled.exists():
            raise AutomationError('BUILD_FAILED', 'Missing compiled game.projectc')
        result = {'build_id': build_id, 'project_id': project_id, 'project': str(target),
                  'compiled_project': str(compiled), 'source_policy': source_policy,
                  'resource_fingerprint': fingerprint,
                  'native_extensions_detected': native, 'build_log': str(log), 'cache_hit': False}
        if editor_snapshots:
            result['editor_snapshots'] = [{key: snap[key] for key in ('path','dirty','editorSessionId','editorGraphRevision','diskHash')}
                                          for snap in editor_snapshots]
            result['editor_source_policy'] = 'Explicit GUI graph snapshots; all other resources from disk; no save'
        if preview:
            result['preview'] = preview
        atomic_json(target.parent / 'manifest.json', {**result, 'files': files,
                    'generated_files': {str(path.relative_to(target.parent)): digest(path) for path in generated}})
        self.builds[build_id] = result
        return result

    def import_build(self, project_id, job):
        """Freeze existing compiled resources and the exact project's engine, without invoking Bob/extender."""
        project = self.project(project_id)
        source = project / 'build/default'
        if not (source / 'game.projectc').is_file():
            raise AutomationError('BUILD_NOT_FOUND', 'Build locally in the editor first; build/default/game.projectc is missing')
        started = time.monotonic()
        files, sources = {}, {}
        for path in source.rglob('*'):
            if preview_resources.is_link(path):
                raise AutomationError('UNSUPPORTED_RESOURCE', 'Compiled symlink resources are unsupported')
            if not path.is_file():
                continue
            if job['cancel'].is_set():
                raise AutomationError('CANCELLED', 'Import cancelled')
            relative = path.relative_to(source)
            before = path.stat()
            files[relative.as_posix()] = digest(path)
            sources[relative.as_posix()] = path
            if (before.st_size, before.st_mtime_ns) != (path.stat().st_size, path.stat().st_mtime_ns):
                raise AutomationError('RESOURCE_CONFLICT', 'Editor build changed during import; wait for completion')
        name = 'dmengine.exe' if self.windows else 'dmengine'
        engine = project / 'build' / self.platform / name
        engine_source = engine if engine.exists() else Path(self.engine)
        symbols = engine_source.with_suffix('.pdb')
        if self.windows and not symbols.is_file():
            raise AutomationError('NATIVE_ENGINE_REQUIRED', 'Matching PDB is required beside the imported engine')
        engine_hash = digest(engine_source)
        symbols_hash = digest(symbols) if self.windows else None
        fingerprint = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()
        previous = next((item for item in reversed(list(self.builds.values()))
                         if item['project_id'] == project_id and item['source_policy'] == 'existing_compiled'
                         and not item.get('preview')), None)
        if (previous and previous['resource_fingerprint'] == fingerprint
                and previous['engine_sha256'] == engine_hash and previous.get('symbols_sha256') == symbols_hash):
            return {**previous, 'cache_hit': True, 'copied_resource_bytes': 0,
                    'import_seconds': time.monotonic() - started}
        build_id = uuid.uuid4().hex
        target = self.root / 'builds' / build_id / 'project'
        destination = target / 'build/default'
        previous_files = (json.loads((Path(previous['project']).parent / 'manifest.json').read_text(encoding='utf-8'))['files']
                          if previous else {})
        def freeze(item):
            relative, path = item
            if job['cancel'].is_set():
                raise AutomationError('CANCELLED', 'Import cancelled')
            copy = destination / relative
            copy.parent.mkdir(parents=True, exist_ok=True)
            if previous and previous_files.get(relative) == files[relative]:
                # Link only our immutable frozen resources, never the user's mutable build output.
                try:
                    os.link(Path(previous['project']) / 'build/default' / relative, copy)
                    return (0, 1)
                except OSError:
                    pass
            shutil.copyfile(path, copy)
            if digest(copy) != files[relative]:
                raise AutomationError('RESOURCE_CONFLICT', 'Editor build changed during import; wait for completion')
            return (copy.stat().st_size, 0)
        with ThreadPoolExecutor(max_workers=8, thread_name_prefix='defold-freeze') as copies:
            totals = list(copies.map(freeze, sources.items()))
        copied_bytes = sum(item[0] for item in totals)
        reused_files = sum(item[1] for item in totals)
        engine_target = target.parent / 'engine' / name
        engine_target.parent.mkdir()
        shutil.copy2(engine_source, engine_target)
        if self.windows:
            shutil.copyfile(symbols, engine_target.with_suffix('.pdb'))
        if digest(engine_target) != engine_hash or (self.windows and digest(engine_target.with_suffix('.pdb')) != symbols_hash):
            raise AutomationError('RESOURCE_CONFLICT', 'Engine or PDB changed during import')
        result = {'build_id': build_id, 'project_id': project_id, 'project': str(target),
                  'compiled_project': str(destination / 'game.projectc'), 'source_policy': 'existing_compiled',
                  'resource_fingerprint': fingerprint,
                  'native_extensions_detected': engine.exists(), 'engine': str(engine_target),
                  'engine_sha256': engine_hash, 'symbols_sha256': symbols_hash, 'cache_hit': False,
                  'copied_resource_bytes': copied_bytes, 'reused_resource_files': reused_files,
                  'import_seconds': time.monotonic() - started,
                  'freshness': 'existing compiled resources; source changes are not rebuilt'}
        atomic_json(target.parent / 'manifest.json', {**result, 'files': files})
        self.builds[build_id] = result
        return result

    def preview_import(self, build_id, resource_path, job, support_objects=None, instance_id='preview'):
        """Derive an isolated resource entry from an owned frozen build; no source/native compiler."""
        base = self.builds.get(build_id)
        if not base:
            raise AutomationError('BUILD_NOT_FOUND', build_id)
        if base.get('preview'):
            raise AutomationError('INVALID_BUILD', 'Use the original full build as the preview base')
        source = Path(base['compiled_project']).parent
        support_objects = support_objects or []
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', instance_id) or len(support_objects) > 32:
            raise AutomationError('INVALID_PREVIEW', 'Invalid instance ID or too many support objects')
        ids = {instance_id} if not resource_path.endswith('.collection') else set()
        try:
            preview_resources.resolve_compiled(source, resource_path)
            for item in support_objects:
                identifier = item.get('id', '')
                if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', identifier) or identifier in ids:
                    raise ValueError('Duplicate or invalid support object ID')
                ids.add(identifier)
                if not item.get('resource_path', '').endswith('.go'):
                    raise ValueError('Support objects must reference compiled GO resources')
                preview_resources.resolve_compiled(source, item['resource_path'])
        except ValueError as error:
            raise AutomationError('INVALID_RESOURCE', str(error)) from error
        for previous in list(self.builds.values()):
            if (previous.get('base_build_id') == build_id
                    and previous.get('preview', {}).get('resource_path') == resource_path
                    and previous['preview'].get('support_objects', []) == support_objects
                    and previous['preview'].get('instance_id', 'preview') == instance_id):
                return {**previous, 'cache_hit': True}
        preview_id = uuid.uuid4().hex
        record = self.root / 'builds' / preview_id
        record.mkdir(parents=True)
        # The frozen base is read-only to this API. Append a unique wrapper and override bootstrap at launch;
        # no existing resource/configuration is modified and no bulk resource copy is needed per preview.
        destination = source
        if job['cancel'].is_set():
            raise AutomationError('CANCELLED', 'Preview cancelled')
        job['progress'] = {'stage': 'compiled_preview_entry', 'resource_path': resource_path}
        wrapper = '__automation_preview_' + uuid.uuid4().hex
        specification = record / 'preview-spec.json'
        atomic_json(specification, {'support_objects': support_objects, 'instance_id': instance_id})
        command = [self.manifest['java'], '-cp', self.manifest['classes'] + os.pathsep + self.manifest['jar'],
                   'com.defold.editor.AutomationPreviewBuilder', str(destination), resource_path, wrapper, str(specification)]
        log = record / 'preview.log'
        with log.open('wb') as output:
            process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT, creationflags=CREATE_FLAGS)
            job['process'] = process
            if job['cancel'].is_set(): process.terminate()
            try:
                code = process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait()
                raise AutomationError('BUILD_TIMEOUT', str(log))
        if job['cancel'].is_set():
            raise AutomationError('CANCELLED', 'Preview cancelled')
        output = log.read_text(encoding='utf-8', errors='replace').strip()
        if code:
            raise AutomationError('PREVIEW_FAILED', output[-6000:])
        bootstrap = resource_path + 'c' if resource_path.endswith('.collection') and not support_objects else '/' + wrapper + '/main.collectionc'
        if output != bootstrap or not (destination / bootstrap[1:]).is_file():
            raise AutomationError('PREVIEW_FAILED', 'Preview builder returned an unexpected bootstrap')
        files = {path.relative_to(destination).as_posix(): digest(path) for path in (destination / wrapper).glob('*')}
        preview = {'resource_path': resource_path, 'resource_type': preview_resources.TYPES[Path(resource_path).suffix],
                   'base_build_id': build_id,
                   'support_objects': support_objects, 'instance_id': instance_id,
                   'mode': 'runtime_isolated_resource', 'bootstrap': bootstrap, 'scripts': 'enabled',
                   'source_policy': 'existing_compiled',
                   'limitations': ['Source changes are not rebuilt; only loose resources present in the base build are available.',
                                   'The original bootstrap scene and its services are not started.',
                                   'GO is instantiated at the origin; no automatic camera or framing is added.',
                                   'GUI/GO wrappers use project component limits (dynamic allocation).']}
        result = {**base, 'build_id': preview_id, 'base_build_id': build_id,
                  'bootstrap_override': bootstrap, 'source_policy': 'existing_compiled', 'preview': preview,
                  'storage_mode': 'shared_frozen_resources', 'copied_resource_bytes': 0,
                  'resource_fingerprint': hashlib.sha256((base['resource_fingerprint'] + json.dumps(files, sort_keys=True)).encode()).hexdigest(),
                  'cache_hit': False, 'freshness': 'Frozen base build; source changes are not rebuilt', 'build_log': str(log)}
        atomic_json(record / 'manifest.json', {**result, 'generated_files': files})
        self.builds[preview_id] = result
        return result

    def start(self, build_id, device, job, project_config=None, app_channel=False):
        build = self.builds.get(build_id)
        if not build:
            raise AutomationError('BUILD_NOT_FOUND', build_id)
        if build['native_extensions_detected'] and not self.custom_engine and not build.get('engine'):
            raise AutomationError('NATIVE_ENGINE_REQUIRED', 'Supply a locally compiled matching custom engine with --engine')
        if not self.windows:
            try:
                validate_macos_engine(build.get('engine', self.engine))
            except ValueError as error:
                raise AutomationError('UNSUPPORTED_CAPABILITY', str(error)) from error
        with self.lock:
            if len(self.sessions) >= 2:
                raise AutomationError('SESSION_LIMIT', 'Close an owned session before opening another (limit 2)')
            session_id = uuid.uuid4().hex
            directory = self.root / 'sessions' / session_id
            worker = Worker(self.manifest, directory)
            session = {'session_id': session_id, 'worker': worker, 'build': build, 'device': device,
                       'project_config': project_config or {},
                       'lease_id': uuid.uuid4().hex, 'lock': threading.RLock(), 'lease_until': time.monotonic() + 60}
            self.sessions[session_id] = session
        try:
            effective_config = dict(project_config or {})
            if app_channel:
                session['app_inbox'] = directory / 'application-inbox.json'
                effective_config.update({'project.automation_enabled':'1','project.automation_inbox':str(session['app_inbox'])})
            configuration = configparser.ConfigParser(interpolation=None, strict=False)
            configuration.read(build['compiled_project'], encoding='utf-8')
            session['gui_profile'] = 'default_gui' if configuration.get('bootstrap', 'render', fallback='') == '/builtins/render/default.renderc' else 'unknown_renderer'
            status = worker.call('start', engine=build.get('engine', self.engine), project=build['project'],
                                 compiled_project=Path(build['compiled_project']).relative_to(build['project']).as_posix(),
                                 bootstrap=build.get('bootstrap_override'),
                                 project_config=effective_config, **device)
            if job['cancel'].is_set():
                raise AutomationError('CANCELLED', 'Session startup cancelled')
            session['status'] = status
            return {'session_id': session_id, 'lease_id': session['lease_id'], 'lease_seconds': 60,
                    'ownership': 'owned', 'build_id': build_id, 'preview': build.get('preview'), **status}
        except Exception:
            self.stop(session_id)
            raise

    def session(self, session_id):
        session = self.sessions.get(session_id)
        if not session:
            raise AutomationError('SESSION_NOT_FOUND', session_id)
        return session

    def stop(self, session_id):
        with self.lock:
            session = self.sessions.pop(session_id, None)
        if session:
            session['worker'].close()
        return {'session_id': session_id, 'state': 'stopped'}

    def lease(self, session_id, lease_id=None, release=False):
        session = self.session(session_id)
        with session['lock']:
            if lease_id is not None and lease_id != session['lease_id']:
                raise AutomationError('INPUT_CONTROLLER_BUSY', 'Lease belongs to another controller')
            if release:
                session['lease_until'] = 0
            else:
                if lease_id is None and session['lease_until'] > time.monotonic():
                    raise AutomationError('INPUT_CONTROLLER_BUSY', 'Use the lease returned by session.start')
                if lease_id is None:
                    session['lease_id'] = uuid.uuid4().hex
                session['lease_until'] = time.monotonic() + 60
            return {'lease_id': session['lease_id'], 'lease_seconds': 0 if release else 60}

    def capture(self, session_id, publish=True):
        session = self.session(session_id)
        with session['lock']:
            if sum(value['session_id'] == session_id for value in self.artifacts.values()) >= 256:
                raise AutomationError('ARTIFACT_LIMIT', '256 observations per session; export the report and start a new session')
            observation_id = uuid.uuid4().hex
            result = session['worker'].call('capture', name=observation_id + '.png')
            result.update(session_id=session_id, observation_id=observation_id, source='runtime',
                          artifact_kind='runtime_frame',
                          build_id=session['build']['build_id'], temporal_consistency='image_and_bridge_metadata',
                          device=session['device'], image_sha256=digest(result['image_path']))
            result.update({key: session['build'].get(key) for key in ('project_id', 'source_policy', 'resource_fingerprint')})
            result['project_config'] = session['project_config']
            result['gui_projection_profile'] = session.get('gui_profile', 'unknown_renderer')
            if result.get('scene_snapshot', {}).get('supported'):
                result['temporal_consistency'] = 'same_render_boundary'
                result['geometry_precision'] = 'engine_gui_matrix'
            if session['build'].get('preview'):
                result['preview'] = session['build']['preview']
            artifact_id = observation_id
            result.update(artifact_id=artifact_id, uri=f'defold://artifacts/{artifact_id}/frame.png')
            if publish:
                self.publish_capture(result)
            return result

    def publish_capture(self, frame):
        with self.lock:
            if frame['artifact_id'] not in self.artifacts:
                if sum(item['session_id'] == frame['session_id'] for item in self.artifacts.values()) >= 256:
                    raise AutomationError('ARTIFACT_LIMIT', '256 observations per session; cannot publish wait evidence')
                atomic_json(Path(frame['image_path']).with_suffix('.json'), frame)
                self.artifacts[frame['artifact_id']] = frame
        return frame

    def discard_capture(self, frame):
        if frame and frame['artifact_id'] not in self.artifacts:
            Path(frame['image_path']).unlink(missing_ok=True)

    def wait_element(self, session_id, selector, condition, text=None, timeout_ms=5000, stable_samples=1, cancel=None, progress=None):
        if not 100 <= timeout_ms <= 30000 or not 1 <= stable_samples <= 10:
            raise AutomationError('INVALID_CONDITION', 'Wait timeout or stable sample count outside bounds')
        started = time.monotonic()
        deadline = started + timeout_ms / 1000
        tracker = conditions.Stability()
        frame, evaluation, samples = None, None, 0

        def evidence():
            result = {'selector': selector, 'condition': condition, 'expected_text': text,
                      'samples': samples, 'consecutive_matches': tracker.count, 'required_samples': stable_samples,
                      'sample_semantics': 'distinct_render_samples_not_continuous_frames',
                      'elapsed_ms': round((time.monotonic() - started) * 1000), 'evidence': evaluation}
            if frame:
                result['after'] = self.publish_capture(frame)
            return result

        try:
            while True:
                if cancel is not None and cancel.is_set():
                    raise AutomationError('CANCELLED', 'GUI condition wait cancelled')
                if time.monotonic() >= deadline:
                    raise AutomationError('CONDITION_TIMEOUT', 'GUI condition did not reach the required sample count')
                sample = self.capture(session_id, publish=False)
                self.discard_capture(frame)
                frame = sample
                samples += 1
                try:
                    items = gui_layout.elements(frame, frame.get('gui_projection_profile'))
                    evaluation = conditions.evaluate(items, selector, condition, text)
                except ValueError as error:
                    raise AutomationError('GEOMETRY_UNAVAILABLE', str(error)) from error
                if evaluation.get('error_code'):
                    raise AutomationError(evaluation['error_code'], evaluation['reason'])
                count = tracker.update(frame, evaluation)
                if progress is not None:
                    progress({'phase': 'waiting_for_gui', 'samples': samples, 'consecutive_matches': count,
                              'required_samples': stable_samples, 'matched': evaluation['matched']})
                if cancel is not None and cancel.is_set():
                    raise AutomationError('CANCELLED', 'GUI condition wait cancelled')
                if time.monotonic() >= deadline:
                    raise AutomationError('CONDITION_TIMEOUT', 'GUI condition sampling exceeded its deadline')
                if count >= stable_samples:
                    result = {'state': 'condition_satisfied', **evidence()}
                    self.record(session_id, {'kind': 'element_wait', **result})
                    return result
                delay = min(.1, max(0, deadline - time.monotonic()))
                if cancel is None: time.sleep(delay)
                else: cancel.wait(delay)
        except AutomationError as error:
            error.details = evidence()
            self.record(session_id, {'kind': 'element_wait', 'state': error.code, **error.details})
            raise
        finally:
            self.discard_capture(frame)

    def input(self, session_id, lease_id, action, observation_id=None):
        session = self.session(session_id)
        with session['lock']:
            if lease_id != session['lease_id'] or session['lease_until'] < time.monotonic():
                raise AutomationError('INPUT_CONTROLLER_BUSY', 'Acquire or renew the input lease')
            status = session['worker'].call('status')
            if observation_id:
                previous = self.artifacts.get(observation_id)
                if not previous or previous['session_id'] != session_id or previous['device_revision'] != status['device_revision']:
                    raise AutomationError('STALE_OBSERVATION', 'Capture again after changing device or session')
                if previous.get('artifact_kind', 'runtime_frame') != 'runtime_frame':
                    raise AutomationError('INVALID_OBSERVATION', 'Use the source runtime observation and map crop pixels to the original frame')
            result = session['worker'].call('input', **action)
            session['lease_until'] = time.monotonic() + 60
            session['last_input'] = result
            result['after'] = self.capture(session_id)
            self.record(session_id, {'kind': 'input', 'action': action, 'observation_id': observation_id,
                                     'receipt': {key: value for key, value in result.items() if key != 'after'},
                                     'after_id': result['after']['artifact_id']})
            return result

    def record(self, session_id, event):
        with self.lock:
            events = self.evidence.setdefault(session_id, [])
            events.append({'sequence': len(events) + 1, **event})

    def elements(self, artifact_id, selector=None, offset=0, limit=50):
        frame = self.original(artifact_id)
        try:
            items = gui_layout.elements(frame, frame.get('gui_projection_profile'))
            if selector: items = gui_layout.select(items, selector)
        except ValueError as error:
            raise AutomationError('GEOMETRY_UNAVAILABLE', str(error)) from error
        return {'observation_id': frame['observation_id'], 'render_sequence': frame['render_sequence'],
                'temporal_consistency': 'same_render_boundary', 'total': len(items),
                'elements': items[offset:offset+limit], 'next_offset': offset+limit if offset+limit<len(items) else None,
                'limitations': ['Rectangles are pre-shader geometry, not pixel coverage or occlusion.',
                                'Custom render projections, text glyph bounds and inverted/nonrectangular clipping return unknown.']}

    def element_click(self, session_id, lease_id, observation_id, selector):
        session = self.session(session_id)
        with session['lock']:
            old = self.original(observation_id)
            if old['session_id'] != session_id or old['device'] != session['device']:
                raise AutomationError('INVALID_OBSERVATION', 'Use an observation from this session and device')
            before = self.elements(observation_id, selector)['elements']
            if len(before) != 1: raise AutomationError('AMBIGUOUS_ELEMENT', 'Selector must identify exactly one element')
            fresh = self.capture(session_id)
            current = self.elements(fresh['artifact_id'], {'element_id': before[0]['element_id']})['elements']
            if len(current) != 1: raise AutomationError('STALE_ELEMENT', 'Element has been destroyed or replaced')
            try: x,y = gui_layout.point(current[0])
            except ValueError as error: raise AutomationError('ELEMENT_NOT_INTERACTABLE', str(error)) from error
            result = self.input(session_id, lease_id, {'type':'click','x':x,'y':y}, fresh['observation_id'])
            result['target'] = {'element_id': current[0]['element_id'], 'id':current[0]['id'], 'point':[x,y],
                                'resolved_observation_id':fresh['observation_id']}
            return result

    def element_drag(self, session_id, lease_id, observation_id, selector, destination, steps=12, cancel=None):
        session = self.session(session_id)
        with session['lock']:
            if lease_id != session['lease_id'] or session['lease_until'] < time.monotonic():
                raise AutomationError('INPUT_CONTROLLER_BUSY', 'Acquire or renew the input lease')
            old = self.original(observation_id)
            if old['session_id'] != session_id or old['device'] != session['device']:
                raise AutomationError('INVALID_OBSERVATION', 'Use an observation from this session and device')
            try:
                gestures.validate(destination, steps)
                before = gui_layout.elements(old, old.get('gui_projection_profile'))
                gestures.unique(before, selector)
                if 'selector' in destination: gestures.unique(before, destination['selector'])
                fresh = self.capture(session_id)
                if old['device_revision'] != fresh['device_revision']:
                    raise AutomationError('STALE_OBSERVATION', 'Capture again after a device change')
                current = gui_layout.elements(fresh, fresh.get('gui_projection_profile'))
                source, target = gestures.resolve(before, current, selector, destination, fresh)
            except gestures.GestureError as error:
                raise AutomationError(error.code, str(error)) from error
            except ValueError as error:
                raise AutomationError('GEOMETRY_UNAVAILABLE', str(error)) from error
            if cancel is not None and cancel.is_set():
                raise AutomationError('CANCELLED', 'Drag cancelled before input')
            result = self.input(session_id, lease_id, {'type': 'drag', 'x': source['point'][0], 'y': source['point'][1],
                'to_x': target['point'][0], 'to_y': target['point'][1], 'steps': steps}, fresh['observation_id'])
            result.update(source=source, destination=target, business_confirmed=False,
                          path_semantics='straight_line_to_frozen_endpoint')
            self.record(session_id, {'kind': 'element_drag', 'source': source, 'destination': target,
                                    'steps': steps, 'after_id': result['after']['artifact_id']})
            return result

    def element_drag_verified(self, session_id, lease_id, observation_id, selector, destination,
                              operation_id, event, steps=12, timeout_ms=5000, cancel=None):
        session = self.session(session_id)
        with session['lock']:
            cursor = self.logs(session_id)['cursor']
            self.application_command(session_id, lease_id, operation_id + ':arm', '__arm_input',
                                     {'operation_id': operation_id, 'timeout_seconds': 30},
                                     timeout_ms=timeout_ms, cancel=cancel)
            result = self.element_drag(session_id, lease_id, observation_id, selector, destination, steps, cancel)
            try:
                result['application_ack'] = self.application_wait(session_id, operation_id, cursor, event, timeout_ms, cancel)
            except AutomationError as error:
                error.details = {'input_dispatched': True, 'receipt': result, 'after': result['after']}
                raise
            result.update(business_confirmed=True, ack_level='application_correlated')
            return result

    def application_wait(self, session_id, operation_id, after, event=None, timeout_ms=5000, cancel=None):
        deadline=time.monotonic()+timeout_ms/1000
        cursor=after
        while time.monotonic()<deadline:
            if cancel is not None and cancel.is_set():
                raise AutomationError('CANCELLED', 'Application wait cancelled; completed actions are not undone')
            logs=self.logs(session_id,cursor)
            if logs['overflow']: raise AutomationError('LOG_OVERFLOW','Application event cursor expired')
            for entry in logs['entries']:
                _, marker, payload=entry['text'].partition('DEFOLD_AUTOMATION ')
                if not marker: continue
                try: message=json.loads(payload)
                except ValueError: continue
                if message.get('operation_id') != operation_id: continue
                if message.get('kind')=='error': raise AutomationError('APPLICATION_ERROR',json.dumps(message))
                if message.get('kind') in ('result','event') and (event is None or message.get('event')==event):
                    self.record(session_id,{'kind':'application_ack','operation_id':operation_id,'evidence':entry,'message':message})
                    return message
            cursor=logs['cursor']
            if cancel is None: time.sleep(.025)
            else: cancel.wait(.025)
        raise AutomationError('BUSINESS_TIMEOUT','No correlated application event for '+operation_id)

    def application_command(self, session_id, lease_id, operation_id, name, arguments=None, timeout_ms=5000, cancel=None):
        session=self.session(session_id)
        with session['lock']:
            if cancel is not None and cancel.is_set():
                raise AutomationError('CANCELLED', 'Application command cancelled before dispatch')
            if session['lease_id']!=lease_id or time.monotonic()>session['lease_until']:
                raise AutomationError('INPUT_CONTROLLER_BUSY','Acquire or renew the session lease')
            if 'app_inbox' not in session: raise AutomationError('UNSUPPORTED_CAPABILITY','Start with app_channel and integrate sdk/automation.lua')
            payload={'operation_id':operation_id,'name':name,'arguments':arguments or {}}
            if len(json.dumps(payload,ensure_ascii=False,indent=2).encode())>32768: raise AutomationError('INVALID_COMMAND','Application command exceeds 32 KiB')
            cursor=self.logs(session_id)['cursor']
            atomic_json(session['app_inbox'],payload)
            ack=self.application_wait(session_id,operation_id,cursor,timeout_ms=timeout_ms,cancel=cancel)
            return {'ack_level':'application_correlated','business_confirmed':True,'message':ack}

    def element_click_verified(self, session_id, lease_id, observation_id, selector, operation_id, event, timeout_ms=5000, cancel=None):
        session=self.session(session_id)
        with session['lock']:
            cursor=self.logs(session_id)['cursor']
            self.application_command(session_id,lease_id,operation_id+':arm','__arm_input',
                                     {'operation_id':operation_id,'timeout_seconds':timeout_ms/1000},
                                     timeout_ms=timeout_ms,cancel=cancel)
            if cancel is not None and cancel.is_set():
                raise AutomationError('CANCELLED', 'Verified click cancelled before input')
            result=self.element_click(session_id,lease_id,observation_id,selector)
            result['application_ack']=self.application_wait(session_id,operation_id,cursor,event,timeout_ms,cancel=cancel)
            result['business_confirmed']=True
            result['ack_level']='application_correlated'
            return result

    def layout_check(self, artifact_id, rules, annotate=False):
        frame = self.original(artifact_id)
        try:
            items = gui_layout.elements(frame, frame.get('gui_projection_profile'))
            result = gui_layout.check(items, rules, frame)
        except ValueError as error:
            raise AutomationError('GEOMETRY_UNAVAILABLE', str(error)) from error
        result.update(observation_id=frame['observation_id'], render_sequence=frame['render_sequence'])
        if annotate:
            if digest(frame['image_path']) != frame['image_sha256']:
                raise AutomationError('ARTIFACT_CORRUPT', 'Original image changed after capture; cannot annotate this snapshot')
            annotation = annotations.plan(items, result, frame)
            def render(path):
                annotations.render(frame['image_path'], path, annotation)
                return {'layout_annotation': annotation, 'coordinate_space': 'frame_pixels_top_left',
                        'raw_image_unchanged': True, 'to_frame_scale': [1, 1], 'to_frame_offset': [0, 0]}
            result['annotation'] = self.derived(frame, 'layout_annotation', render)
        self.record(frame['session_id'], {'kind':'layout_check', **result})
        return result

    def cutout_overlay(self, artifact_id, device, cutouts, name):
        frame = self.original(artifact_id)
        if any(frame['device'].get(key,0) != device.get(key,0) for key in ('width','height','top','right','bottom','left')):
            raise AutomationError('DEVICE_MISMATCH', 'Capture must match the selected preset, orientation and safe area')
        def render(path):
            image_evidence.cutout_overlay(frame['image_path'], path, device, cutouts)
            return {'device_preset':name, 'cutouts':cutouts, 'mask_geometry':'catalog_rectangles',
                    'raw_image_unchanged':True, 'coordinate_space':'frame_pixels_top_left'}
        return self.derived(frame, 'device_overlay', render)

    def device(self, session_id, device, expected_revision):
        session = self.session(session_id)
        with session['lock']:
            status = session['worker'].call('status')
            if expected_revision != status['device_revision']:
                raise AutomationError('DEVICE_CHANGED', 'Device revision changed')
            session['worker'].call('device', name='device-' + uuid.uuid4().hex + '.png', **device)
            session['device'] = device
            return self.capture(session_id)

    def logs(self, session_id, after=0):
        return self.session(session_id)['worker'].call('logs', after=after)

    def wait_log(self, session_id, contains, after=0, timeout_ms=5000, cancel=None):
        deadline = time.monotonic() + timeout_ms / 1000
        cursor = after
        while True:
            if cancel is not None and cancel.is_set():
                raise AutomationError('CANCELLED', 'Log wait cancelled')
            result = self.logs(session_id, cursor)
            if result['overflow']:
                raise AutomationError('LOG_OVERFLOW', 'Requested log cursor has expired')
            for entry in result['entries']:
                if contains in entry['text']:
                    result = {'state': 'condition_satisfied', 'evidence': entry, 'after': self.capture(session_id)}
                    self.record(session_id, {'kind': 'log_condition', 'contains': contains, 'after_cursor': after,
                                             'evidence': entry, 'after_id': result['after']['artifact_id']})
                    return result
            cursor = result['cursor']
            if time.monotonic() >= deadline:
                raise AutomationError('CONDITION_TIMEOUT', f'No matching log after cursor {after}: {contains}')
            (cancel if cancel is not None else threading.Event()).wait(min(0.025, max(0, deadline - time.monotonic())))

    def scene(self, session_id, selector=None, limit=100):
        status = self.session(session_id)['worker'].call('status')
        if not status['port']:
            raise AutomationError('ENGINE_UNAVAILABLE', 'Engine HTTP service not ready')
        root = local_json(status['port'], '/scene_graph')
        selector = selector or {}
        matches, count = [], 0
        pending = [(root, '/')]
        while pending:
            node, path = pending.pop()
            children = node.get('children', [])
            pending.extend((child, path + str(index) + '/') for index, child in enumerate(children))
            if all(str(value).lower() in str(node.get(key, '')).lower() for key, value in selector.items()):
                count += 1
                if len(matches) < limit:
                    matches.append({**{key: value for key, value in node.items() if key != 'children'}, 'sample_path': path})
        return {'sample_id': uuid.uuid4().hex, 'temporal_consistency': 'independent_live_sample',
                'geometry_precision': 'unavailable', 'elements': matches, 'matched': count, 'truncated': count > limit}

    def original(self, artifact_id):
        item = self.artifacts.get(artifact_id)
        if not item:
            raise AutomationError('ARTIFACT_NOT_FOUND', artifact_id)
        if item.get('artifact_kind', 'runtime_frame') != 'runtime_frame':
            raise AutomationError('INVALID_OBSERVATION', 'This tool requires original runtime frames')
        return item

    def derived(self, source, kind, render):
        with self.lock:
            if sum(item['session_id'] == source['session_id'] for item in self.artifacts.values()) >= 256:
                raise AutomationError('ARTIFACT_LIMIT', '256 artifacts per session')
            artifact_id = uuid.uuid4().hex
            path = Path(source['image_path']).with_name(artifact_id + '.png')
            details = render(path)
            result = {**source, **details, 'artifact_id': artifact_id, 'artifact_kind': kind,
                      'observation_id': source['observation_id'], 'source_artifact_id': source['artifact_id'],
                      'image_path': str(path), 'image_sha256': digest(path),
                      'uri': f'defold://artifacts/{artifact_id}/frame.png'}
            self.artifacts[artifact_id] = result
            atomic_json(path.with_suffix('.json'), result)
            return result

    def crop(self, artifact_id, region):
        source = self.original(artifact_id)
        def render(path):
            try:
                image_evidence.crop(source['image_path'], path, region)
            except ValueError as error:
                raise AutomationError('INVALID_REGION', str(error)) from error
            return {'width': region['width'], 'height': region['height'], 'region': region,
                    'coordinate_space': 'crop_pixels_top_left', 'to_frame_offset': [region['x'], region['y']],
                    'to_frame_scale': [1, 1], 'source_size': [source['width'], source['height']]}
        return self.derived(source, 'crop', render)

    def compare(self, before_id, after_id, channel_threshold=8, max_changed_ratio=0.005, region=None, ignore=None):
        before, after = self.original(before_id), self.original(after_id)
        return self.compare_frames(before, after, channel_threshold, max_changed_ratio, region, ignore)

    def compare_frames(self, before, after, channel_threshold=8, max_changed_ratio=0.005, region=None, ignore=None):
        before_id, after_id = before['artifact_id'], after['artifact_id']
        if before['device'] != after['device']:
            raise AutomationError('DEVICE_MISMATCH', 'Compare frames captured with the same device dimensions and safe area')
        def render(path):
            try:
                metrics = image_evidence.compare(before['image_path'], after['image_path'], path,
                            channel_threshold, max_changed_ratio, region, ignore)
            except ValueError as error:
                raise AutomationError('INVALID_COMPARISON', str(error)) from error
            return {'comparison': {**metrics, 'before_id': before_id, 'after_id': after_id},
                    'coordinate_space': 'frame_pixels_top_left'}
        artifact = self.derived(after, 'difference', render)
        result = {**artifact['comparison'], 'comparison_id': artifact['artifact_id'], 'before': before, 'after': artifact,
                  'session_id': after['session_id']}
        self.comparisons[result['comparison_id']] = result
        return result

    def baseline_save(self, project_id, name, artifact_id):
        self.project(project_id)
        frame = self.original(artifact_id)
        if frame.get('project_id') != project_id:
            raise AutomationError('BASELINE_CONTEXT_MISMATCH', 'Capture belongs to another project or has no project provenance')
        return self.baselines.save(project_id, name, frame)

    def baseline_list(self, project_id, name=None, offset=0, limit=20):
        self.project(project_id)
        return self.baselines.list(project_id, name, offset, limit)

    def baseline_get(self, project_id, baseline_id):
        self.project(project_id)
        metadata, data = self.baselines.load(project_id, baseline_id)
        # Materialize an immutable report copy, never treat historical data as a live observation.
        identifier = 'baseline-' + baseline_id
        with self.lock:
            if identifier not in self.artifacts:
                directory = self.root / 'baseline-images'
                directory.mkdir(exist_ok=True)
                path = directory / (identifier + '.png')
                temporary = path.with_suffix('.' + uuid.uuid4().hex + '.part')
                temporary.write_bytes(data)
                temporary.replace(path)
                device = metadata['context']['device']
                item = {'artifact_id': identifier, 'artifact_kind': 'baseline', 'source': 'persisted_baseline',
                        'session_id': identifier, 'observation_id': metadata['source']['observation_id'],
                        'device': device, 'width': device['width'], 'height': device['height'],
                        'render_sequence': metadata['source']['render_sequence'],
                        'input_sequence': metadata['source']['input_sequence'], 'image_path': str(path),
                        'image_sha256': metadata['image_sha256'], 'baseline': metadata,
                        'uri': f'defold://artifacts/{identifier}/frame.png'}
                self.artifacts[identifier] = item
            elif digest(self.artifacts[identifier]['image_path']) != metadata['image_sha256']:
                raise AutomationError('BASELINE_CORRUPT', 'Materialized baseline image was changed')
        return self.artifacts[identifier]

    def baseline_compare(self, baseline_id, artifact_id, **options):
        after = self.original(artifact_id)
        project_id = after.get('project_id')
        self.project(project_id)
        before = self.baseline_get(project_id, baseline_id)
        metadata = before['baseline']
        if baselines.context(after) != metadata['context']:
            raise AutomationError('BASELINE_CONTEXT_MISMATCH', 'Device, safe area, preview resource, QA config or render profile differs')
        if digest(after['image_path']) != after['image_sha256']:
            raise AutomationError('BASELINE_CORRUPT', 'Current capture was modified after publication')
        # Canonicalize omitted zero insets; no pixel rescaling or other context substitution.
        result = self.compare_frames(before, {**after, 'device': metadata['context']['device']}, **options)
        result['baseline'] = metadata
        self.record(after['session_id'], {'kind': 'baseline_comparison', 'baseline_id': baseline_id,
                                         'comparison_id': result['comparison_id'], 'verdict': result['verdict']})
        return result

    def report(self, session_id):
        images = [value for value in self.artifacts.values() if value['session_id'] == session_id]
        if not images:
            raise AutomationError('ARTIFACT_NOT_FOUND', 'Capture at least one frame first')
        directory = Path(images[0]['image_path']).parent
        comparisons = [item for item in self.comparisons.values() if item['session_id'] == session_id]
        events = self.evidence.get(session_id, [])
        report = {'session_id': session_id, 'observations': images, 'comparisons': comparisons, 'events': events,
                  'preview': images[0].get('preview'),
                  'limitations': ['Scene queries are live samples, not same-frame geometry.',
                                  'Input dispatch is not proof of a business callback.']}
        atomic_json(directory / 'report.json', report)
        cards = ''.join('<figure><img src="' + html.escape(Path(item['image_path']).name) + '"><figcaption>'
                        + html.escape(f"{item.get('artifact_kind', 'runtime_frame')} · {item['width']}×{item['height']} · render {item['render_sequence']} · input {item['input_sequence']}")
                        + '</figcaption>' + (annotations.legend_html(item['layout_annotation']) if item.get('layout_annotation') else '')
                        + '</figure>' for item in images)
        comparison_cards = ''
        for item in comparisons:
            comparison_cards += '<section><h2>' + html.escape(f"{item['verdict']} · changed {item['changed_ratio']:.3%}") + '</h2><div class="compare">'
            for label, identifier in (('Before', item['before_id']), ('After', item['after_id']), ('Difference', item['comparison_id'])):
                artifact = self.artifacts[identifier]
                # Baselines may come from another session; copy their immutable PNG into this report directory.
                source = Path(artifact['image_path'])
                destination = directory / source.name
                if source != destination:
                    shutil.copyfile(source, destination)
                comparison_cards += '<figure><img src="' + html.escape(source.name) + '"><figcaption>' + label + '</figcaption></figure>'
            comparison_cards += '</div><p>Pixel evidence only; no business success inference.</p><pre>' + html.escape(json.dumps(item['thresholds'])) + '</pre></section>'
        event_cards = '<h2>操作与业务日志证据</h2>' + ''.join('<details><summary>'
            + html.escape(f"{item['sequence']} · {item['kind']}") + '</summary><pre>'
            + html.escape(json.dumps(item, ensure_ascii=False, indent=2)) + '</pre></details>' for item in events)
        for sequence in self.sequences.values():
            if sequence['session_id'] == session_id and sequence['state'] != 'running':
                link = html.escape(Path(os.path.relpath(sequence['report_path'], directory)).as_posix(), quote=True)
                event_cards += f'<p><a href="{link}">连续帧查验 · {sequence["captured_frames"]} 帧 · {html.escape(sequence["state"])}</a></p>'
        preview_title = '<p>资源运行预览：' + html.escape(report['preview']['resource_path']) + '</p>' if report['preview'] else ''
        (directory / 'report.html').write_text('<!doctype html><meta charset="utf-8"><title>Defold background verification</title>'
            '<style>body{background:#202124;color:#eee;font:16px system-ui;margin:24px}main,.compare{display:flex;flex-wrap:wrap;gap:20px}'
            'figure{margin:0;max-width:100%}img{max-width:420px;max-height:700px;object-fit:contain}figcaption{padding:10px}'
            '.layout-legend{border-collapse:collapse;font-size:13px;max-width:680px;overflow-wrap:anywhere}'
            '.layout-legend td,.layout-legend th{border:1px solid #555;padding:6px;text-align:left}</style>'
            '<h1>Defold 后台运行截图</h1>' + preview_title + '<p>原始游戏画面；业务结果应结合操作回执和日志确认。差异图中的粉色表示超过阈值的像素。</p>' + comparison_cards + event_cards + '<main>' + cards + '</main>', encoding='utf-8')
        return {'report_path': str(directory / 'report.html'), 'json_path': str(directory / 'report.json'),
                'observations': len(images), 'comparisons': len(comparisons), 'events': len(events)}

    def close(self):
        with self.lock:
            if self.closed:
                return
            self.closed = True
            job_ids = list(self.jobs)
            # Mark every job before releasing workers; they must not pick up new queued work on shutdown.
            for job_id in job_ids:
                if not self.jobs[job_id]['done'].is_set():
                    self.jobs[job_id]['cancel'].set()
        for job_id in job_ids:
            self.job_status(job_id, cancel=True)
        self.executor.shutdown(wait=True, cancel_futures=True)
        for session_id in list(self.sessions):
            self.stop(session_id)
