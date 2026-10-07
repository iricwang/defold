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

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Standalone helper; only JDK classes are needed after the editor exits. */
public final class PatchRestart {
    public static void swap(Path app, Path staged, Path backup) throws Exception {
        Files.move(app, backup, StandardCopyOption.ATOMIC_MOVE);
        try {
            Files.move(staged, app, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception error) {
            Files.move(backup, app, StandardCopyOption.ATOMIC_MOVE);
            throw error;
        }
    }

    public static void main(String[] args) throws Exception {
        long pid = Long.parseLong(args[0]);
        Path app = Path.of(args[1]).toRealPath();
        Path staged = Path.of(args[2]).toRealPath();
        if (!app.getParent().equals(staged.getParent()) || app.equals(staged)) {
            throw new IllegalArgumentException("Replacement must be beside the installed app");
        }
        Path lockPath = app.resolveSibling("." + app.getFileName() + ".patch.lock");
        Path backup = app.resolveSibling(app.getFileName() + ".patch-backup-" + UUID.randomUUID());
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IllegalStateException("Another update is already running");
            ProcessHandle parent = ProcessHandle.of(pid).orElseThrow();
            System.out.println("READY");
            System.out.flush();
            // Completion signal, no polling. A cancelled shutdown never applies the patch.
            parent.onExit().get(120, TimeUnit.SECONDS);
            try {
                swap(app, staged, backup);
            } catch (Exception error) {
                error.printStackTrace();
                if (!Files.isDirectory(app)) throw error;
            }
            List<String> command = new ArrayList<>(List.of(app.resolve("Contents/MacOS/Defold").toString()));
            command.addAll(Arrays.asList(args).subList(4, args.length));
            // The old editor owns the readiness pipe and will close it on exit.
            // The new app must have independent stdout/stderr, or a write could fail.
            ProcessBuilder launcher = new ProcessBuilder(command).directory(app.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(Path.of(args[3]).toFile()));
            try {
                launcher.start();
                System.out.println("STARTED");
            } catch (Exception error) {
                if (Files.isDirectory(backup)) {
                    Files.move(app, staged, StandardCopyOption.ATOMIC_MOVE);
                    Files.move(backup, app, StandardCopyOption.ATOMIC_MOVE);
                    launcher.start();
                }
                throw error;
            }
        }
    }
}
