"""Immutable, project-scoped PNG baselines. SQLite transactions publish metadata and bytes together."""
from contextlib import closing
import hashlib
import io
import json
from pathlib import Path
import re
import sqlite3
import time

from PIL import Image

MAX_IMAGE_BYTES = 64 * 1024 * 1024
MAX_STORE_BYTES = 1024 * 1024 * 1024
MAX_RECORDS = 4096


class BaselineError(RuntimeError):
    def __init__(self, code, message):
        self.code = code
        super().__init__(message)


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':'), allow_nan=False)


def context(frame):
    device = {key: frame['device'].get(key, 0) for key in ('width', 'height', 'top', 'right', 'bottom', 'left')}
    preview = frame.get('preview')
    return {'device': device, 'project_config': frame.get('project_config', {}),
            'gui_projection_profile': frame.get('gui_projection_profile'),
            'preview': None if preview is None else {
                'resource_path': preview['resource_path'], 'resource_type': preview['resource_type'],
                'instance_id': preview.get('instance_id', 'preview'),
                'support_objects': sorted(preview.get('support_objects', []), key=lambda item: item['id'])}}


def verify_png(data, metadata):
    if len(data) > MAX_IMAGE_BYTES or hashlib.sha256(data).hexdigest() != metadata['image_sha256']:
        raise BaselineError('BASELINE_CORRUPT', 'PNG hash or size does not match baseline metadata')
    try:
        with Image.open(io.BytesIO(data)) as image:
            device = metadata['context']['device']
            if image.format != 'PNG' or image.size != (device['width'], device['height']):
                raise ValueError('PNG format/dimensions do not match the captured device')
            if any(not 32 <= value <= 4096 for value in image.size):
                raise ValueError('Baseline dimensions must be 32..4096')
            image.verify()
    except (OSError, ValueError) as error:
        raise BaselineError('BASELINE_CORRUPT', str(error)) from error


class Store:
    def __init__(self, directory):
        self.directory = Path(directory).resolve()
        self.directory.mkdir(parents=True, exist_ok=True)
        self.path = self.directory / 'baselines.sqlite3'
        with closing(self.connect()) as database, database:
            database.execute('''CREATE TABLE IF NOT EXISTS baselines (
                id TEXT PRIMARY KEY, project TEXT NOT NULL, name TEXT NOT NULL,
                created REAL NOT NULL, metadata TEXT NOT NULL, png BLOB NOT NULL)''')
            database.execute('CREATE INDEX IF NOT EXISTS baseline_project_name ON baselines(project, name)')

    def connect(self):
        return sqlite3.connect(self.path, timeout=10)

    def save(self, project_id, name, frame):
        if not isinstance(name, str) or not 1 <= len(name.strip()) <= 128:
            raise BaselineError('INVALID_BASELINE', 'Baseline name must contain 1..128 characters')
        with Path(frame['image_path']).open('rb') as stream:
            data = stream.read(MAX_IMAGE_BYTES + 1)
        metadata = {'version': 1, 'project_id': project_id, 'name': name,
                    'context': context(frame), 'image_sha256': frame['image_sha256'],
                    'source': {key: frame.get(key) for key in (
                        'observation_id', 'build_id', 'resource_fingerprint', 'source_policy',
                        'render_sequence', 'input_sequence', 'device_revision', 'preview')}}
        verify_png(data, metadata)
        encoded = canonical(metadata)
        if len(encoded.encode('utf-8')) > 1024 * 1024:
            raise BaselineError('INVALID_BASELINE', 'Baseline metadata exceeds 1 MiB')
        identifier = hashlib.sha256(encoded.encode('utf-8')).hexdigest()
        with closing(self.connect()) as database, database:
            database.execute('BEGIN IMMEDIATE')
            existing = database.execute('SELECT created,metadata,png FROM baselines WHERE id=?', (identifier,)).fetchone()
            if existing:
                if existing[1] != encoded:
                    raise BaselineError('BASELINE_CORRUPT', 'Existing baseline metadata was modified')
                verify_png(existing[2], metadata)
                return {**metadata, 'baseline_id': identifier, 'created_at': existing[0], 'reused': True}
            count, size = database.execute('SELECT count(*), coalesce(sum(length(png)+length(metadata)),0) FROM baselines').fetchone()
            if count >= MAX_RECORDS or size + len(data) + len(encoded.encode('utf-8')) > MAX_STORE_BYTES:
                raise BaselineError('BASELINE_LIMIT', 'Baseline store limit reached (4096 records / 1 GiB); select another store explicitly')
            created = time.time()
            database.execute('INSERT INTO baselines VALUES (?,?,?,?,?,?)',
                             (identifier, project_id, name, created, encoded, data))
        return {**metadata, 'baseline_id': identifier, 'created_at': created, 'reused': False}

    def load(self, project_id, identifier):
        if not isinstance(identifier, str) or not re.fullmatch('[0-9a-f]{64}', identifier):
            raise BaselineError('BASELINE_NOT_FOUND', 'Use an immutable baseline ID from this project')
        with closing(self.connect()) as database:
            row = database.execute('SELECT created,metadata,png FROM baselines WHERE project=? AND id=?',
                                   (project_id, identifier)).fetchone()
        if row is None:
            raise BaselineError('BASELINE_NOT_FOUND', 'Baseline does not exist in this registered project/store')
        created, encoded, data = row
        try:
            metadata = json.loads(encoded)
            if (hashlib.sha256(canonical(metadata).encode('utf-8')).hexdigest() != identifier
                    or metadata['project_id'] != project_id or metadata['version'] != 1):
                raise ValueError('Metadata identity mismatch')
            verify_png(data, metadata)
        except (KeyError, TypeError, ValueError) as error:
            raise BaselineError('BASELINE_CORRUPT', str(error)) from error
        return {**metadata, 'baseline_id': identifier, 'created_at': created}, data

    def list(self, project_id, name=None, offset=0, limit=20):
        if type(offset) is not int or offset < 0 or type(limit) is not int or not 1 <= limit <= 100:
            raise BaselineError('INVALID_BASELINE', 'Invalid baseline page bounds')
        where, arguments = 'project=?', [project_id]
        if name is not None:
            where += ' AND name=?'
            arguments.append(name)
        with closing(self.connect()) as database:
            total = database.execute('SELECT count(*) FROM baselines WHERE ' + where, arguments).fetchone()[0]
            rows = database.execute('SELECT id,created,metadata FROM baselines WHERE ' + where +
                                    ' ORDER BY created DESC,id LIMIT ? OFFSET ?', [*arguments, limit, offset]).fetchall()
        return {'baselines': [{**json.loads(metadata), 'baseline_id': identifier, 'created_at': created}
                              for identifier, created, metadata in rows], 'total': total,
                'next_offset': offset + limit if offset + limit < total else None}
