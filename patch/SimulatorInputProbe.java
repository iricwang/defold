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
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser.GUITHREADINFO;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

/** Exercises real dmengine GUI picking through the production Windows input bridge, without desktop focus. */
public final class SimulatorInputProbe {
    private static final BlockingQueue<String> output = new LinkedBlockingQueue<>();

    private static String await(String marker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            String line = output.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (line == null) break;
            if (line.contains(marker)) return line;
        }
        throw new AssertionError("Engine did not report " + marker);
    }

    public static void main(String[] args) throws Exception {
        ResourceUnpacker.unpackResources();
        if (!args[2].equals("-")) System.setProperty("defold.unpack.path", args[2]);
        int width = Integer.parseInt(args[3]), height = Integer.parseInt(args[4]);
        Path shared = Files.createTempFile(Path.of(args[1]), "input-", ".shared");
        try (var file = new RandomAccessFile(shared.toFile(), "rw")) {
            int slot = width * height * 4;
            file.setLength(4096L + 2L * slot);
            var data = file.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, file.length());
            data.order(ByteOrder.LITTLE_ENDIAN);
            data.putInt(0, 0x44464733); data.putInt(48, slot);
            data.putInt(1024, width); data.putInt(1028, height);
            data.putInt(20, 1);
            data.putInt(1060, 1); data.putInt(1068, 1);
            Process process = WindowsGameProcess.start(List.of(args[0],
                    "--config=display.window_width=" + width, "--config=display.window_height=" + height,
                    "--config=display.vsync=0", "--config=display.update_frequency=120",
                    "--config=display.focus_on_show=0", "--config=display.high_dpi=0", "--graphics-adapter=opengl",
                    "build/default/game.projectc"),
                    Path.of(args[1]).toFile(),
                    Map.of("DM_EDITOR_GAME_FILE", shared.toString(),
                           "DM_EDITOR_GAME_PARENT", Long.toString(ProcessHandle.current().pid())));
            Thread.ofPlatform().daemon().start(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    for (String line; (line = reader.readLine()) != null;) {
                        System.out.println(line); output.add(line);
                    }
                } catch (IOException error) { output.add(error.toString()); }
            });
            try {
                await("INPUT_READY");
                Class<?> bridge = Class.forName("com.defold.editor.WindowsGameWindow");
                var constructor = bridge.getDeclaredConstructor(); constructor.setAccessible(true);
                Object window = constructor.newInstance();
                var update = bridge.getDeclaredMethod("updateSimulator", Process.class, int.class, int.class);
                update.setAccessible(true);
                var input = bridge.getDeclaredMethod("input", int.class, int.class, int.class, int.class, byte[].class, ArrayDeque.class);
                input.setAccessible(true);
                if (!(boolean)update.invoke(window, process, width, height)) throw new AssertionError("Missing game window");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (data.getInt(12) == 0 && System.nanoTime() < deadline) LockSupport.parkNanos(1_000_000);
                if (data.getInt(12) == 0 || data.getInt(4) != width || data.getInt(8) != height)
                    throw new AssertionError("Missing simulator frame at device resolution");
                byte[] keys = new byte[256];
                ArrayDeque<Integer> text = new ArrayDeque<>();
                int x = width / 2, y = height / 2;
                data.putInt(24, x); data.putInt(28, y);
                input.invoke(window, x, y, 1, 0, keys, text);
                String down = await("INPUT click down");
                if (!down.contains(" " + x + " " + (height-y-1) + " hit=true")) throw new AssertionError(down);
                var handle = bridge.getDeclaredField("window"); handle.setAccessible(true);
                int thread = User32.INSTANCE.GetWindowThreadProcessId(new HWND((Pointer)handle.get(window)), null);
                var info = new GUITHREADINFO();
                if (!User32.INSTANCE.GetGUIThreadInfo(thread, info) || info.hwndCapture != null)
                    throw new AssertionError("Hidden game stole desktop mouse capture");
                data.putInt(24, x + 20); data.putInt(28, y + 10);
                input.invoke(window, x + 20, y + 10, 1, 0, keys, text);
                await("INPUT click held " + (x + 20) + " " + (height-y-11) + " hit=true");
                input.invoke(window, x + 20, y + 10, 0, 0, keys, text);
                await("INPUT click up");
                data.putInt(24, 5); data.putInt(28, 5);
                input.invoke(window, 5, 5, 1, 0, keys, text);
                await("INPUT click down 5 " + (height-6) + " hit=false");
                input.invoke(window, 5, 5, 0, 0, keys, text);
                await("INPUT click up");
                for (int button : new int[]{2, 4}) {
                    String name = button == 2 ? "right" : "middle";
                    input.invoke(window, x, y, button, 0, keys, text); await("INPUT " + name + " down");
                    input.invoke(window, x, y, 0, 0, keys, text); await("INPUT " + name + " up");
                }
                input.invoke(window, x, y, 0, 1, keys, text); await("INPUT wheel down");
                keys[32] = 1;
                input.invoke(window, x, y, 0, 0, keys, text); await("INPUT key down");
                keys[32] = 0;
                input.invoke(window, x, y, 0, 0, keys, text); await("INPUT key up");
                text.add((int)'a'); input.invoke(window, x, y, 0, 0, keys, text); await("text=a");
                System.out.println("SIMULATOR_INPUT_OK " + width + "x" + height);
            } finally {
                process.destroyForcibly(); process.waitFor(); process.getInputStream().close();
            }
        } finally { shared.toFile().deleteOnExit(); }
    }
}
