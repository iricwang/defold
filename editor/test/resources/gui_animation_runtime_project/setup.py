#!/usr/bin/env python3
"""Install only the checksum-pinned runtime modules, preserving upstream licenses."""
import hashlib
import io
import json
from pathlib import Path
from urllib.request import urlopen
import zipfile

ROOT = Path(__file__).resolve().parent


def install():
    lock = json.loads((ROOT / 'dependencies.lock.json').read_text())
    for name, dependency in lock.items():
        files = dependency['files']
        if all((ROOT / path).is_file() and hashlib.sha256((ROOT / path).read_bytes()).hexdigest() == digest
               for path, digest in files.items()):
            print(f'{name}: verified {dependency["commit"]}')
            continue
        print(f'{name}: downloading {dependency["commit"]}')
        with urlopen(dependency['url'], timeout=60) as response:
            archive = zipfile.ZipFile(io.BytesIO(response.read()))
        # Validate all contents before replacing anything. Never extract arbitrary archive paths.
        contents = {}
        for path, digest in files.items():
            matches = [member for member in archive.namelist() if member.endswith('/' + path)]
            if len(matches) != 1:
                raise RuntimeError(f'Ambiguous or missing dependency file: {path}')
            contents[path] = archive.read(matches[0])
            if hashlib.sha256(contents[path]).hexdigest() != digest:
                raise RuntimeError(f'Checksum mismatch: {path}')
        for path, data in contents.items():
            target = ROOT / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        print(f'{name}: installed and verified')


if __name__ == '__main__':
    install()
