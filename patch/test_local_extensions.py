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
from io import BytesIO
from pathlib import Path
import importlib.util
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('local_extensions', Path(__file__).resolve().parents[1] / 'scripts/windows/local_extensions.py')
local = importlib.util.module_from_spec(spec)
spec.loader.exec_module(local)


class LocalExtensionsTest(unittest.TestCase):
    # macOS consumes its own framework context and library format; Windows must ignore it.
    def test_macos_manifest_context(self):
        previous = local.PLATFORM
        try:
            local.PLATFORM = 'arm64-macos'
            value = local.manifest_context({'name':'Example','platforms':{
                'osx':{'context':{'frameworks':['Cocoa']}},
                'arm64-osx':{'context':{'defines':['ARM64=1']}},
                'win32':{'context':{'defines':['WINDOWS=1']}}}})
            self.assertEqual(['Cocoa'],value['frameworks'])
            self.assertEqual(['ARM64=1'],value['defines'])
            self.assertEqual('dmengine',local.engine_filename())
        finally:
            local.PLATFORM = previous

    # Native caching follows named source content, not ZIP timestamps or order, and separates platforms.
    def test_native_cache_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            builder = object.__new__(local.Builder)
            builder.home=Path(directory);builder.sdk='sdk';builder.engine_hash='engine';builder.bob_hash='bob'
            def archive(entries):
                data=BytesIO()
                with zipfile.ZipFile(data,'w') as output:
                    for name,body in entries:output.writestr(name,body)
                return data.getvalue()
            first=archive([('a.cpp','one'),('b.h','two')]);second=archive([('b.h','two'),('a.cpp','one')])
            self.assertEqual(builder.cache_key(first),builder.cache_key(second))
            self.assertNotEqual(builder.cache_key(first),builder.cache_key(archive([('a.cpp','changed'),('b.h','two')])))
            original=builder.cache_key(first);builder.engine_hash='updated'
            self.assertNotEqual(original,builder.cache_key(first))

    # Editor commits may regenerate headers; the installed SDK remains authoritative.
    def test_sdk_revision_uses_installed_artifacts(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            revision = 'a' * 40
            (home / 'share/java').mkdir(parents=True)
            (home / 'bin' / local.PLATFORM).mkdir(parents=True)
            with zipfile.ZipFile(home / 'share/java/bob.jar', 'w') as archive:
                archive.writestr('com/dynamo/bob/archive/EngineVersion.class', b'\x01\x00\x28' + revision.encode())
            engine = home / 'bin' / local.PLATFORM / 'dmengine.exe'
            engine.write_bytes(revision.encode() + b'\x00')
            self.assertEqual(revision, local.sdk_revision(home))
            engine.write_bytes(b'b' * 40 + b'\x00')
            with self.assertRaisesRegex(ValueError, 'revisions differ'):
                local.sdk_revision(home)

    # A real core rebuild must not be mislabeled as the older installed SDK.
    def test_changed_engine_library_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory) / 'sdk'
            binary_dir = Path(directory) / 'build'
            binary_dir.mkdir()
            (home / 'lib' / local.PLATFORM).mkdir(parents=True)
            for name in ('engine.lib', 'engine_service.lib'):
                (binary_dir / name).write_bytes(b'original')
                (home / 'lib' / local.PLATFORM / name).write_bytes(b'original')
            local.check_engine_libraries(home, binary_dir)
            (binary_dir / 'engine.lib').write_bytes(b'rebuilt')
            with self.assertRaisesRegex(ValueError, 'libraries differ'):
                local.check_engine_libraries(home, binary_dir)

    # Uploaded paths must stay inside the job, including Windows aliases and ADS.
    def test_reject_escaping_and_windows_special_paths(self):
        for name in ('../outside', '/outside', 'C:/outside', 'a\\..\\outside', 'a/b:stream', 'a/CON.cpp', 'a./file', 'a/../b'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                local.safe_name(name)

    # Case collisions must not silently replace a different uploaded source on Windows.
    def test_reject_case_colliding_sources(self):
        data = BytesIO()
        with zipfile.ZipFile(data, 'w') as archive:
            archive.writestr('ext/src/A.cpp', b'first')
            archive.writestr('ext/src/a.cpp', b'second')
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
            local.extract_sources(data.getvalue(), Path(directory))

    # Legitimate source trees survive the archive boundary byte-for-byte.
    def test_extract_regular_sources(self):
        data = BytesIO()
        with zipfile.ZipFile(data, 'w') as archive:
            archive.writestr('ext/commonsrc/example.cpp', b'\x00\xff\r\n')
        with tempfile.TemporaryDirectory() as directory:
            local.extract_sources(data.getvalue(), Path(directory))
            self.assertEqual(b'\x00\xff\r\n', (Path(directory) / 'ext/commonsrc/example.cpp').read_bytes())

    # Unsupported compiler hooks/options must fail rather than produce a partial extension.
    def test_reject_unsupported_manifest(self):
        for field in ('libs', 'linkFlags', 'flags', 'frameworks', 'excludeLibs'):
            with self.subTest(field=field), self.assertRaises(ValueError):
                local.manifest_context({'name': 'Example', 'platforms': {'win32': {'context': {field: ['x']}}}})

    # Only applicable Windows contexts contribute registration; foreign platforms are ignored.
    def test_registration_contexts(self):
        context = local.manifest_context({'name': 'SpineExt', 'platforms': {
            'common': {'context': {'symbols': ['ResourceTypeSpineSceneExt']}},
            'win32': {'context': {'defines': ['WINDOWS_EXTENSION=1']}},
            'arm64-osx': {'context': {'frameworks': ['Cocoa']}}}})
        self.assertEqual(['SpineExt', 'ResourceTypeSpineSceneExt'], context['symbols'])
        self.assertEqual(['WINDOWS_EXTENSION=1'], context['defines'])

    # Names and argument encoding cannot inject CMake commands or multiple list entries.
    def test_reject_cmake_injection(self):
        for value in ('a;b', 'a\nexecute_process()', 'a]=]b'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                local.cmake_arg(value)
        with self.assertRaises(ValueError):
            local.manifest_context({'name': 'Example);execute_process('})

    # A non-default app manifest must not be silently linked against default engine features.
    def test_reject_app_manifest_feature_removal(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / '_app').mkdir()
            (root / '_app/app.manifest').write_text('platforms:\n  common:\n    context:\n      excludeLibs: [physics]\n')
            with self.assertRaisesRegex(ValueError, 'default debug app manifest'):
                local.make_config(root, root / 'extensions.cmake')

    # Basename collisions in generated DDF files must fail before invoking the compiler.
    def test_reject_conflicting_proto_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'ext/src/a').mkdir(parents=True)
            (root / 'ext/src/b').mkdir()
            (root / 'ext/ext.manifest').write_text('name: Example\n')
            (root / 'ext/src/a/data.proto').touch()
            (root / 'ext/src/b/data.proto').touch()
            with self.assertRaisesRegex(ValueError, 'unique proto basenames'):
                local.make_config(root, root / 'extensions.cmake')


if __name__ == '__main__':
    unittest.main()
