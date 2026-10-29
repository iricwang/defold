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
"""Check relocated Windows native-engine symbols without launching a game.

Example:
    python scripts/windows/test_engine_symbols.py --hook path/to/game_window_hook.dll \
        --engine path/to/dmengine.exe --legacy-hook path/to/old/game_window_hook.dll

The EXE/PDB are copied to a fresh artifact directory. Only these copies are
changed; the original CodeView path is made unavailable without moving its file.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
import ctypes
import hashlib
import json
from pathlib import Path
import shutil
import struct
import sys
import tempfile
import uuid


ROOT = Path(__file__).resolve().parents[2]


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def codeview_record(data):
    """Locate the executable's RSDS record through its PE debug directory."""
    if data[:2] != b'MZ':
        raise ValueError('Expected a Windows PE executable')
    pe = struct.unpack_from('<I', data, 0x3c)[0]
    if data[pe:pe + 4] != b'PE\0\0':
        raise ValueError('Invalid PE signature')
    sections = struct.unpack_from('<H', data, pe + 6)[0]
    optional_size = struct.unpack_from('<H', data, pe + 20)[0]
    optional = pe + 24
    magic = struct.unpack_from('<H', data, optional)[0]
    if magic not in (0x10b, 0x20b):
        raise ValueError('Unsupported PE optional header')
    directories = optional + (112 if magic == 0x20b else 96)
    debug_rva, debug_size = struct.unpack_from('<II', data, directories + 6 * 8)
    section_table = optional + optional_size

    def offset(rva):
        for index in range(sections):
            section = section_table + index * 40
            virtual_size, virtual_address, raw_size, raw_address = struct.unpack_from('<IIII', data, section + 8)
            if virtual_address <= rva < virtual_address + max(virtual_size, raw_size):
                return raw_address + rva - virtual_address
        raise ValueError('PE debug directory is outside its sections')

    debug = offset(debug_rva)
    records = []
    for entry in range(debug, debug + debug_size, 28):
        kind, size, address, pointer = struct.unpack_from('<IIII', data, entry + 12)
        if kind == 2 and data[pointer:pointer + 4] == b'RSDS':
            if size < 25 or pointer + size > len(data):
                raise ValueError('Invalid CodeView record size')
            end = data.index(0, pointer + 24, pointer + size)
            records.append((pointer, end))
    if len(records) != 1:
        raise ValueError('Expected exactly one RSDS CodeView record')
    return records[0]


def isolated_executable(source):
    data = bytearray(source.read_bytes())
    record, end = codeview_record(data)
    original = bytes(data[record + 24:end]).decode('utf-8')
    kernel = ctypes.WinDLL('kernel32.dll')
    kernel.GetLogicalDrives.argtypes = []
    kernel.GetLogicalDrives.restype = ctypes.c_uint32
    drives = kernel.GetLogicalDrives()
    if not drives:
        raise ctypes.WinError()
    drive = next((chr(65 + index) for index in reversed(range(26)) if not drives & (1 << index)), None)
    if not drive:
        raise RuntimeError('The test requires one unmounted drive letter to isolate the CodeView path')
    basename = original.replace('\\', '/').rsplit('/', 1)[-1]
    replacement = drive + ':/' + basename
    encoded = replacement.encode('utf-8')
    if len(encoded) > end - record - 24:
        raise ValueError('The executable must contain a PDB path with room for an isolated drive prefix')
    data[record + 24:end] = encoded + bytes(end - record - 24 - len(encoded))
    return data, record, original, replacement, basename


def load_probe(path):
    library = ctypes.WinDLL(str(path))
    probe = library.SimulatorSafeAreaRva
    probe.argtypes = [ctypes.c_wchar_p]
    probe.restype = ctypes.c_uint64
    return library, probe


def run(args):
    engine = args.engine.absolute()
    symbols = (args.pdb or engine.with_suffix('.pdb')).absolute()
    if not engine.is_file() or not symbols.is_file():
        raise FileNotFoundError('The source engine and its matching PDB must both exist')
    source_hashes = {str(path): digest(path) for path in (engine, symbols)}
    parent = ROOT / 'tmp' / 'engine-symbols-tests'
    parent.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='symbols-', dir=parent))
    executable = directory / 'dmengine.exe'
    pdb = executable.with_suffix('.pdb')
    data, record, original, replacement, basename = isolated_executable(engine)
    executable.write_bytes(data)
    shutil.copy2(symbols, pdb)
    hook, probe = load_probe(args.hook.absolute())
    dbghelp = ctypes.WinDLL('dbghelp.dll')
    dbghelp.SymGetOptions.argtypes = []
    dbghelp.SymGetOptions.restype = ctypes.c_uint32
    dbghelp.SymSetOptions.argtypes = [ctypes.c_uint32]
    dbghelp.SymSetOptions.restype = ctypes.c_uint32
    previous = dbghelp.SymGetOptions()
    # Exercise restoration with a caller-owned option, including rejected probes.
    options = previous ^ 0x2  # SYMOPT_UNDNAME
    dbghelp.SymSetOptions(options)
    passed = []
    report = {'directory': str(directory), 'source_sha256': source_hashes,
              'hook': str(args.hook.absolute()), 'hook_sha256': digest(args.hook),
              'recorded_pdb': original, 'isolated_pdb': replacement,
              'guid': str(uuid.UUID(bytes_le=bytes(data[record + 4:record + 20]))),
              'age': struct.unpack_from('<I', data, record + 20)[0], 'passed': passed}

    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        passed.append(name)

    def checked_probe(name, expected):
        actual = probe(str(executable))
        check(name, actual == expected if expected is not None else actual != 0)
        check(name + ': caller DbgHelp options restored', dbghelp.SymGetOptions() == options)
        return actual

    try:
        check('only the canonical adjacent PDB is present', sorted(p.name for p in directory.iterdir()) == ['dmengine.exe', 'dmengine.pdb'])
        rva = checked_probe('relocated EXE resolves canonical dmengine.pdb', None)
        report['safe_area_rva'] = rva

        # Absence must fail even when the source build still exists on this PC.
        pdb.unlink()
        checked_probe('missing adjacent PDB rejected', 0)
        shutil.copy2(symbols, pdb)
        checked_probe('valid pair recovers after missing PDB', rva)

        # Preserve the valid PDB while independently invalidating each EXE identity field.
        mismatched = bytearray(data)
        mismatched[record + 4] ^= 1
        executable.write_bytes(mismatched)
        checked_probe('wrong PDB GUID rejected', 0)
        mismatched = bytearray(data)
        age = struct.unpack_from('<I', data, record + 20)[0]
        struct.pack_into('<I', mismatched, record + 20, (age + 1) & 0xffffffff)
        executable.write_bytes(mismatched)
        checked_probe('wrong PDB age rejected', 0)
        executable.write_bytes(data)
        checked_probe('valid pair recovers after identity mismatch', rva)

        pdb.write_bytes(b'corrupt pdb regression fixture\n')
        checked_probe('corrupt adjacent PDB rejected', 0)
        shutil.copy2(symbols, pdb)
        checked_probe('valid pair recovers after corrupt PDB', rva)

        for iteration in range(args.repeat):
            checked_probe('repeated lookup ' + str(iteration + 1), rva)

        # ctypes releases the GIL for these native calls; the DLL must serialize DbgHelp.
        with ThreadPoolExecutor(max_workers=args.threads) as executor:
            values = list(executor.map(lambda _: probe(str(executable)), range(args.threads * args.repeat)))
        check('concurrent lookups preserve the exact RVA', values == [rva] * len(values))
        check('concurrent lookups restore caller DbgHelp options', dbghelp.SymGetOptions() == options)
        report['concurrent_calls'] = len(values)

        if args.legacy_hook:
            report['legacy_hook'] = str(args.legacy_hook.absolute())
            report['legacy_hook_sha256'] = digest(args.legacy_hook)
            legacy, legacy_probe = load_probe(args.legacy_hook.absolute())
            check('legacy DLL reproduces canonical PDB failure', legacy_probe(str(executable)) == 0)
            alias = directory / basename
            if alias == pdb:
                raise ValueError('The legacy reproduction requires a noncanonical original PDB basename')
            shutil.copy2(symbols, alias)
            try:
                check('legacy DLL succeeds with the original PDB basename', legacy_probe(str(executable)) == rva)
            finally:
                alias.unlink()
            check('legacy calls restore caller DbgHelp options', dbghelp.SymGetOptions() == options)
            checked_probe('fixed DLL still resolves canonical PDB after legacy calls', rva)
        check('original EXE and PDB remain unchanged', source_hashes == {str(path): digest(path) for path in (engine, symbols)})
        report['completed'] = True
    except Exception as error:
        report['completed'] = False
        report['error'] = str(error)
        raise
    finally:
        dbghelp.SymSetOptions(previous)
        report['original_files_unchanged'] = source_hashes == {str(path): digest(path) for path in (engine, symbols)}
        report['initial_dbghelp_options'] = previous
        report['restored_dbghelp_options'] = dbghelp.SymGetOptions()
        (directory / 'report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
        print(json.dumps(report, indent=2), flush=True)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--hook', type=Path, required=True, help='Updated game_window_hook.dll')
    parser.add_argument('--engine', type=Path, required=True, help='Locally built native-extension dmengine.exe')
    parser.add_argument('--pdb', type=Path, help='Matching PDB; defaults to engine basename with .pdb')
    parser.add_argument('--legacy-hook', type=Path, help='Optional previous DLL to reproduce the original failure')
    parser.add_argument('--repeat', type=int, default=3)
    parser.add_argument('--threads', type=int, default=4)
    arguments = parser.parse_args()
    if sys.platform != 'win32':
        parser.error('Native symbol regression checks require Windows')
    if arguments.repeat < 1 or arguments.threads < 1:
        parser.error('--repeat and --threads must be positive')
    run(arguments)
