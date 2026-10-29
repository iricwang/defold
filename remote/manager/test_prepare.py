"""Artifact validation only: no SDK, compiler, manager or game process is started."""
from pathlib import Path
import tempfile
import unittest

from remote.manager.prepare import validate_native_engine


class NativeEngineArtifactsTest(unittest.TestCase):
    def setUp(self):
        work = Path(__file__).resolve().parents[2] / 'tmp/remote-prepare-unit-tests'
        work.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=work)
        self.addCleanup(self.temporary.cleanup)
        self.engine = Path(self.temporary.name) / 'dmengine.exe'

    def test_missing_engine_rejected(self):
        with self.assertRaisesRegex(RuntimeError, '没有生成'):
            validate_native_engine(self.engine, 'x86_64-win32')

    def test_windows_missing_or_empty_symbols_rejected(self):
        self.engine.write_bytes(b'engine')
        for contents in (None, b''):
            if contents is not None:
                self.engine.with_suffix('.pdb').write_bytes(contents)
            with self.assertRaisesRegex(RuntimeError, '配套调试符号'):
                validate_native_engine(self.engine, 'x86_64-win32')

    def test_windows_pair_accepted(self):
        self.engine.write_bytes(b'engine')
        self.engine.with_suffix('.pdb').write_bytes(b'symbols')
        validate_native_engine(self.engine, 'x86_64-win32')

    def test_macos_does_not_require_windows_symbols(self):
        self.engine.write_bytes(b'engine')
        validate_native_engine(self.engine, 'arm64-macos')


if __name__ == '__main__':
    unittest.main()
