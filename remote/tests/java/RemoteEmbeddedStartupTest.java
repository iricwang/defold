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

package com.defold.editor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class RemoteEmbeddedStartupTest {
    // Capture-only hooks must preserve hidden startup and subsequent native child-window adoption.
    public static void main(String[] args)throws Exception {
        var constructor=GamePreview.Session.class.getDeclaredConstructor(int.class,SimulatorDevice.class,int.class);
        constructor.setAccessible(true);
        try(var session=constructor.newInstance(60,null,0)) {
            var process=WindowsGameProcess.start(List.of(Path.of(args[0]).toAbsolutePath().toString()),
                Path.of(args[1]).toFile(),Map.of("DM_EDITOR_GAME_FILE",session.getPath(),
                "DM_EDITOR_GAME_PARENT",Long.toString(ProcessHandle.current().pid()),
                "DM_EDITOR_GAME_CAPTURE_ONLY","1","DM_EDITOR_AUTOMATION","1"));
            session.attach(process);
            if(!process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Native window probe timed out");
            String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            if(process.exitValue()!=0||!output.contains("HIDDEN_STARTUP_AND_ADOPTION_OK"))throw new AssertionError(output);
            System.out.println("Capture-only: "+output.trim());
        }
    }
}
