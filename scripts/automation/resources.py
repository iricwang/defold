"""Project-scoped disk resource lookup and isolated runtime preview bootstrap files."""
import hashlib
import json
import os
import re
import zipfile
from pathlib import Path, PurePosixPath

TYPES = {'.gui': 'gui', '.go': 'go', '.collection': 'collection'}
EXCLUDE = {'.git', '.internal', 'build', '.DS_Store', '__pycache__'}


def override_bootstrap(path, bootstrap):
    lines = path.read_text(encoding='utf-8').splitlines(keepends=True)
    section, found, header = '', False, None
    for index, line in enumerate(lines):
        match = re.match(r'^\s*\[([^]]+)\]\s*$', line)
        if match:
            section = match[1]
            if section == 'bootstrap': header = index
        elif section == 'bootstrap' and re.match(r'^\s*main_collection\s*=', line):
            lines[index] = 'main_collection = ' + bootstrap + '\n'
            found = True
    if not found:
        if header is None:
            lines.extend(['\n[bootstrap]\n', 'main_collection = ' + bootstrap + '\n'])
        else:
            if not lines[header].endswith('\n'): lines[header] += '\n'
            lines.insert(header + 1, 'main_collection = ' + bootstrap + '\n')
    path.write_text(''.join(lines), encoding='utf-8')


def native_library_archive(path, relative):
    if tuple(relative.parts[:2]) != ('.internal', 'lib') or path.suffix.lower() != '.zip':
        return False
    with zipfile.ZipFile(path) as archive:
        return any(PurePosixPath(name).name == 'ext.manifest' for name in archive.namelist())


def is_link(path):
    return path.is_symlink() or (hasattr(path, 'is_junction') and path.is_junction())


def resource_relative(resource_path):
    if (not isinstance(resource_path, str) or not resource_path.startswith('/')
            or resource_path.startswith('//') or any(ord(char) < 32 for char in resource_path)
            or any(char in resource_path for char in '\\:*?"<>|')
            or any(part in ('', '.', '..') for part in resource_path[1:].split('/'))):
        raise ValueError('Use a project resource path such as /gui/login.gui; traversal, drives and links are not allowed')
    relative = PurePosixPath(resource_path[1:])
    if relative.suffix not in TYPES or any(part.casefold() in {name.casefold() for name in EXCLUDE} for part in relative.parts[:-1]):
        raise ValueError('Only project .gui, .go and .collection resources are supported')
    return relative


def resolve_compiled(directory, resource_path):
    relative = resource_relative(resource_path)
    root = Path(directory).resolve()
    path = root
    for part in (*relative.parts[:-1], relative.name + 'c'):
        path = path / part
        if is_link(path):
            raise ValueError('Linked compiled resources are unsupported')
    if not path.resolve().is_relative_to(root) or not path.is_file():
        raise ValueError('Resource has no loose compiled output in this build; build it locally first')
    return path


def resolve(project, resource_path):
    relative = resource_relative(resource_path)
    root = Path(project).resolve()
    path = root
    for part in relative.parts:
        path = path / part
        if is_link(path):
            raise ValueError('Linked resource paths are unsupported')
    if not path.resolve().is_relative_to(root) or not path.is_file():
        raise ValueError('Resource does not exist inside this registered project')
    return path


def search(project, query='', types=None, offset=0, limit=50, exclude_root=None):
    project = Path(project).resolve()
    found = []
    visited = 0
    for base, directories, names in os.walk(project, followlinks=False):
        directories[:] = sorted(name for name in directories if name not in EXCLUDE
                                and not is_link(Path(base) / name)
                                and not (exclude_root and (Path(base) / name).resolve().is_relative_to(exclude_root)))
        visited += len(names) + len(directories)
        if visited > 100000:
            raise ValueError('Resource search exceeds 100000 entries; register a smaller project')
        for name in names:
            path = Path(base) / name
            kind = TYPES.get(path.suffix)
            if not kind or (types and kind not in types) or is_link(path):
                continue
            relative = '/' + path.relative_to(project).as_posix()
            if query.casefold() in relative.casefold():
                info = path.stat()
                found.append({'path': relative, 'type': kind, 'size': info.st_size, 'mtime_ns': info.st_mtime_ns})
    found.sort(key=lambda item: item['path'])
    return {'source_policy': 'disk', 'resources': found[offset:offset + limit], 'total': len(found),
            'next_offset': offset + limit if offset + limit < len(found) else None,
            'limitations': ['Unsaved editor changes and dependency-library resources are not included.']}


def prepare(project, resource_path, settings_path):
    path = resolve(project, resource_path)
    kind = TYPES[path.suffix]
    generated = []
    if kind == 'collection':
        bootstrap = resource_path + 'c'
    else:
        # Deterministic names aid cache auditing; never overwrite a project-owned path, even in a copy.
        name = '__automation_preview_' + hashlib.sha256(resource_path.encode()).hexdigest()[:12]
        directory = project / name
        suffix = 0
        while directory.exists():
            suffix += 1
            directory = project / (name + '_' + str(suffix))
        directory.mkdir()
        quote = lambda value: json.dumps(value, ensure_ascii=False)
        collection = directory / 'main.collection'
        if kind == 'gui':
            component = 'components { id: "gui" component: ' + quote(resource_path) + ' }\n'
            content = 'name: "automation_preview"\nembedded_instances { id: "preview" data: ' + quote(component) + ' }\n'
        else:
            content = 'name: "automation_preview"\ninstances { id: "preview" prototype: ' + quote(resource_path) + ' }\n'
        collection.write_text(content, encoding='utf-8')
        generated.append(collection)
        bootstrap = '/' + collection.relative_to(project).as_posix() + 'c'
    settings_path.write_text('[bootstrap]\nmain_collection = ' + bootstrap + '\n', encoding='utf-8')
    generated.append(settings_path)
    return {'resource_path': resource_path, 'resource_type': kind, 'mode': 'runtime_isolated_resource',
            'bootstrap': bootstrap, 'scripts': 'enabled', 'source_policy': 'disk',
            'limitations': ['The original bootstrap scene and its services are not started.',
                            'GO is instantiated at the origin; no automatic camera or framing is added.',
                            'Uses project rendering/display/input configuration and normal runtime scripts.']}, generated
