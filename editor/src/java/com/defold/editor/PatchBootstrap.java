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

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** One-time entry point for Windows installations with the original macOS-only updater. */
public final class PatchBootstrap {
    public static void main(String[] args) throws Exception {
        Path app = Path.of(args[1]).toRealPath();
        String prefix = app.toString().toLowerCase(Locale.ROOT) + File.separator;
        boolean running = ProcessHandle.allProcesses().anyMatch(process -> process.pid() != ProcessHandle.current().pid()
                && process.info().command().map(command -> command.toLowerCase(Locale.ROOT).startsWith(prefix)).orElse(false));
        if (running) throw new IOException("Save your work and close Defold before applying this patch.");
        Path staged = PatchInstaller.prepare(new File(args[0]), app.toFile(), Long.parseLong(args[2]), Long.parseLong(args[3])).toPath();
        Path helper = Files.createTempDirectory(app.getParent(), ".defold-helper-");
        Path runtime = Files.createDirectory(helper.resolve("runtime"));
        try {
            PatchInstaller.copyApp(Path.of(System.getProperty("java.home")), runtime);
            Path code = helper.resolve("com/defold/editor/PatchRestart.class");
            Files.createDirectories(code.getParent());
            try (InputStream input = PatchRestart.class.getResourceAsStream("PatchRestart.class")) {
                Files.copy(input, code);
            }
            Process process = new ProcessBuilder(runtime.resolve("bin/java.exe").toString(), "-cp", helper.toString(),
                    PatchRestart.class.getName(), Long.toString(ProcessHandle.current().pid()), app.toString(), staged.toString(),
                    helper.resolve("editor.log").toString()).redirectError(helper.resolve("restart.log").toFile()).start();
            CompletableFuture<String> ready = CompletableFuture.supplyAsync(() -> {
                try { return new BufferedReader(new InputStreamReader(process.getInputStream())).readLine(); }
                catch (IOException error) { throw new UncheckedIOException(error); }
            });
            if (!"READY".equals(ready.get(10, TimeUnit.SECONDS))) {
                process.destroyForcibly();
                throw new IOException("Installer did not start. See " + helper);
            }
            System.out.println("Patch verified. Restarting Defold. Backup and logs are beside the installation.");
        } catch (Exception error) {
            PatchInstaller.deleteTree(staged);
            throw error;
        }
    }
}
