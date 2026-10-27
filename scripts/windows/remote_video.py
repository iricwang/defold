#!/usr/bin/env python3
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
"""Local Windows Media Foundation JNI build, shared by iteration and packaging."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]

def build(jdk, output):
    jdk, output = Path(jdk).resolve(), Path(output).resolve()
    if not (jdk / 'include/jni.h').is_file():
        raise ValueError('A complete JDK with JNI headers is required')
    vswhere = Path(os.environ['ProgramFiles(x86)']) / 'Microsoft Visual Studio/Installer/vswhere.exe'
    installation = subprocess.check_output([str(vswhere), '-latest', '-products', '*', '-requires',
        'Microsoft.VisualStudio.Component.VC.Tools.x86.x64', '-property', 'installationPath'],
        text=True, creationflags=subprocess.CREATE_NO_WINDOW).strip()
    if not installation:
        raise RuntimeError('MSVC and Windows SDK are required for hardware Remote video')
    output.parent.mkdir(parents=True, exist_ok=True)
    (ROOT / 'tmp').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='remote-video-build-', dir=ROOT / 'tmp') as directory:
        directory = Path(directory)
        script = directory / 'build.cmd'
        vcvars = Path(installation) / 'VC/Auxiliary/Build/vcvars64.bat'
        source = ROOT / 'remote/native/remote_video_windows.cpp'
        script.write_text(
            f'@call "{vcvars}" >nul\n@if errorlevel 1 exit /b 1\n'
            f'@cl /nologo /std:c++17 /EHsc /LD /O2 /MT /I"{jdk / "include"}" /I"{jdk / "include/win32"}" '
            f'"{source}" /Fo:"{directory / "video.obj"}" /Fe:"{output}" '
            f'/link mfplat.lib mf.lib mfuuid.lib ole32.lib oleaut32.lib uuid.lib '
            f'/IMPLIB:"{directory / "video.lib"}"\n', encoding='utf-8')
        result = subprocess.run(['cmd', '/d', '/c', str(script)], cwd=directory,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors='replace',
            creationflags=subprocess.CREATE_NO_WINDOW)
        print(result.stdout, flush=True)
        result.check_returncode()
    print('Built Windows Remote hardware video:', output, flush=True)
