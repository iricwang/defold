// Copyright 2020-2026 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
//
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

import com.defold.editor.PatchInstaller;
import com.defold.editor.PatchRestart;
import java.io.File;
import java.nio.file.Path;

public final class WindowsPatchHarness {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("prepare")) {
            System.out.println(PatchInstaller.prepare(new File(args[1]), new File(args[2]), 8, 10));
        } else {
            PatchRestart.swap(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
        }
    }
}
