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

import com.defold.libs.ResourceUnpacker;
import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Exercises live-video/MCP snapshot mode transitions against a real native GUI game. */
public final class RemoteCaptureModesTest {
    private static void ordinaryPreview(Path project, Path output) throws Exception {
        Files.createDirectories(output);
        Path transport = output.resolve("runtime.shared");
        try (RandomAccessFile file = new RandomAccessFile(transport.toFile(), "rw")) {
            int capacity = 4096 * 4096 * 4;
            file.setLength(4096L + 2L * capacity);
            var data = file.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, file.length());
            data.order(ByteOrder.LITTLE_ENDIAN);
            data.putInt(0, 0x44464733); data.putInt(12 * 4, capacity);
            data.putInt(256 * 4, 584); data.putInt(257 * 4, 1280);
            data.putInt(265 * 4, 1); data.putInt(267 * 4, 1);
            // No automation flag (268): the shared hook must never dereference an absent scene transport.
            List<String> command = List.of(project.resolve("build/x86_64-win32/dmengine.exe").toString(),
                    "--config=display.window_width=584", "--config=display.window_height=1280",
                    "--config=engine.run_while_iconified=1", "--config=display.focus_on_show=0",
                    "--config=profiler.remotery_port=0", "--graphics-adapter=opengl", "build/default/game.projectc");
            Process game = WindowsGameProcess.start(command, project.toFile(), Map.of(
                    "DM_EDITOR_GAME_FILE", transport.toAbsolutePath().toString(),
                    "DM_EDITOR_GAME_PARENT", Long.toString(ProcessHandle.current().pid()), "DM_SERVICE_PORT", "dynamic"));
            try {
                long deadline = System.nanoTime() + 20_000_000_000L;
                while (data.getInt(3 * 4) == 0 && game.isAlive() && System.nanoTime() < deadline) Thread.sleep(5);
                if (!game.isAlive() || data.getInt(3 * 4) == 0) throw new AssertionError("Ordinary preview failed to capture");
                System.out.println("Ordinary preview: pixel capture succeeds without an automation scene transport");
            } finally { game.destroyForcibly(); game.waitFor(); game.getInputStream().close(); }
        }
    }
    private static int frames(AutomationWorker worker, int previous) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        int count = 0;
        int[] pixels = null;
        while (count < 90 && System.nanoTime() < deadline) {
            var frame = worker.liveFrame(previous, pixels);
            if (frame != null) { previous = frame.sequence(); pixels = frame.pixels(); count++; }
            else Thread.sleep(1);
        }
        if (count != 90) throw new AssertionError("Live capture stalled: " + count);
        return previous;
    }

    // Verifies video avoids per-frame scene writes and a later MCP capture restores a matching full scene.
    public static void main(String[] args) throws Exception {
        ResourceUnpacker.unpackResources();
        System.setProperty("defold.unpack.path", args[0]);
        Path project = Path.of(args[1]), output = Path.of(args[2]);
        ordinaryPreview(project, output.resolve("ordinary"));
        try (AutomationWorker worker = new AutomationWorker(output)) {
            worker.command(Map.of("command", "start", "engine", project.resolve("build/x86_64-win32/dmengine.exe").toString(),
                    "project", project.toString(), "compiled_project", "build/default/game.projectc", "width", 584, "height", 1280));
            Path scene = output.resolve("runtime.scene.json");
            byte[] initial = Files.readAllBytes(scene);
            int previous = frames(worker, -1);
            if (!Arrays.equals(initial, Files.readAllBytes(scene))) throw new AssertionError("Video rewrote the scene JSON");
            Map<?, ?> capture = (Map<?, ?>)worker.command(Map.of("command", "capture", "name", "after-video.png"));
            Map<?, ?> snapshot = (Map<?, ?>)capture.get("scene_snapshot");
            if (snapshot == null || !Boolean.TRUE.equals(snapshot.get("supported"))) throw new AssertionError("Missing MCP scene");
            if (!Objects.equals(snapshot.get("render_sequence"), capture.get("render_sequence"))) throw new AssertionError("MCP scene/frame mismatch");
            if (((List<?>)snapshot.get("nodes")).size() < 100) throw new AssertionError("Expected complex native GUI scene");
            byte[] deterministic = Files.readAllBytes(scene);
            frames(worker, previous);
            if (!Arrays.equals(deterministic, Files.readAllBytes(scene))) throw new AssertionError("Resumed video rewrote scene JSON");
            System.out.println("RemoteCaptureModesTest: 180 live frames; no per-frame scene writes; matching full MCP scene between streams");
        }
    }
}
