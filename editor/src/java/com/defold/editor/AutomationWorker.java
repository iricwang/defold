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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;

/** Private pipe worker for an owned GPU session. No JavaFX toolkit or desktop input. */
public final class AutomationWorker implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CAPACITY = 4096 * 4096 * 4;
    private static final boolean MAC = System.getProperty("os.name").equals("Mac OS X");
    private final WindowsGameWindow window = MAC ? null : new WindowsGameWindow();
    private final ArrayDeque<Map<String, Object>> logs = new ArrayDeque<>();
    private final byte[] keys = new byte[256];
    private final ArrayDeque<Integer> text = new ArrayDeque<>();
    private final Path directory;
    private final RandomAccessFile shared;
    private final MappedByteBuffer data;
    private Process game;
    private int width, height, revision, sequence, x, y, buttons;
    private long logCursor;
    private volatile int servicePort;

    AutomationWorker(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
        if (MAC) Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        shared = new RandomAccessFile(directory.resolve("runtime.shared").toFile(), "rw");
        if (MAC) Files.setPosixFilePermissions(directory.resolve("runtime.shared"), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        shared.setLength(4096L + 2L * CAPACITY);
        data = shared.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, shared.length());
        data.order(ByteOrder.LITTLE_ENDIAN);
        put(0, 0x44464733); put(12, CAPACITY); put(5, 1);
        put(268, 0x41555431); put(265, 1);
    }

    private int get(int index) { return data.getInt(index * 4); }
    private void put(int index, int value) { data.putInt(index * 4, value); }

    private void await(BooleanSupplier condition, long timeoutMillis) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!condition.getAsBoolean()) {
            if (game != null && !game.isAlive()) throw new IOException("ENGINE_EXITED: " + game.exitValue());
            if (System.nanoTime() >= deadline) throw new IOException("CONDITION_TIMEOUT");
            LockSupport.parkNanos(1_000_000); // Bounded polling of an explicit shared-memory completion signal.
        }
    }

    private static int integer(Map<?, ?> request, String name, int fallback) {
        Object value = request.get(name);
        if (value == null) return fallback;
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue())
            throw new IllegalArgumentException("Invalid integer: " + name);
        return number.intValue();
    }

    private Map<String, Object> configure(Map<?, ?> request) throws IOException {
        int nextWidth = integer(request, "width", width), nextHeight = integer(request, "height", height);
        if (nextWidth < 32 || nextHeight < 32 || nextWidth > 4096 || nextHeight > 4096)
            throw new IllegalArgumentException("Device dimensions must be 32..4096");
        int[] insets = {integer(request, "top", 0), integer(request, "right", 0),
                        integer(request, "bottom", 0), integer(request, "left", 0)};
        for (int inset : insets) if (inset < 0) throw new IllegalArgumentException("Negative safe inset");
        if (insets[0] + insets[2] >= nextHeight || insets[1] + insets[3] >= nextWidth)
            throw new IllegalArgumentException("Safe area is empty");
        put(264, get(264) + 1);
        put(256, nextWidth); put(257, nextHeight);
        for (int i = 0; i < 4; ++i) put(258 + i, insets[i]);
        width = nextWidth; height = nextHeight; put(275, ++revision);
        x = Math.min(x, width - 1); y = Math.min(y, height - 1);
        put(264, get(264) + 1);
        if (game != null) {
            release();
            await(() -> MAC ? get(278) == revision : window.updateSimulator(game, width, height), 10000);
            send();
        }
        return Map.of("width", width, "height", height, "device_revision", revision);
    }

    private void start(Map<?, ?> request) throws Exception {
        configure(request);
        Path project = Path.of((String)request.get("project"));
        List<String> command = new ArrayList<>(List.of((String)request.get("engine"),
                "--config=display.window_width=" + width, "--config=display.window_height=" + height,
                "--config=display.vsync=0", "--config=display.update_frequency=60",
                "--config=display.focus_on_show=0", "--config=display.high_dpi=0",
                "--config=engine.run_while_iconified=1", "--config=profiler.remotery_port=0",
                "--graphics-adapter=opengl", (String)request.get("compiled_project")));
        if (request.get("bootstrap") instanceof String bootstrap) {
            if (!bootstrap.startsWith("/") || !bootstrap.endsWith(".collectionc")
                    || bootstrap.contains("..") || bootstrap.contains("\\") || bootstrap.contains("\n"))
                throw new IllegalArgumentException("Invalid internal preview bootstrap");
            command.add(command.size() - 1, "--config=bootstrap.main_collection=" + bootstrap);
        }
        if (request.get("project_config") instanceof Map<?, ?> configuration) {
            for (var entry : configuration.entrySet()) {
                String key = (String)entry.getKey(), value = (String)entry.getValue();
                if (!key.matches("project\\.[a-zA-Z0-9_]+") || value.length() > 256
                        || value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0)
                    throw new IllegalArgumentException("Invalid project test configuration");
                command.add(command.size() - 1, "--config=" + key + "=" + value);
            }
        }
        Map<String, String> environment = Map.of(
                "DM_EDITOR_GAME_FILE", directory.resolve("runtime.shared").toString(),
                "DM_EDITOR_GAME_PARENT", Long.toString(ProcessHandle.current().pid()),
                "DM_EDITOR_AUTOMATION_SCENE", directory.resolve("runtime.scene.json").toString(),
                "DM_EDITOR_AUTOMATION", "1", "DM_SERVICE_PORT", "dynamic",
                "DM_WINDOW_STATE", "");
        if (MAC) {
            ProcessBuilder builder = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true);
            builder.environment().putAll(environment);
            builder.environment().put("DYLD_INSERT_LIBRARIES", System.getProperty("defold.unpack.path") + "/libautomation_guard.dylib");
            game = builder.start();
        } else {
            game = WindowsGameProcess.start(command, project.toFile(), environment);
        }
        Thread.ofPlatform().daemon().name("automation-log").start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(game.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                 var file = Files.newBufferedWriter(directory.resolve("engine.log"), java.nio.charset.StandardCharsets.UTF_8)) {
                for (String line; (line = reader.readLine()) != null;) {
                    file.write(line); file.newLine(); file.flush();
                    synchronized (logs) {
                        logs.addLast(Map.of("cursor", ++logCursor, "text", line));
                        if (logs.size() > 4096) logs.removeFirst();
                    }
                    var matcher = java.util.regex.Pattern.compile("Engine service started on port (\\d+)").matcher(line);
                    if (matcher.find()) servicePort = Integer.parseInt(matcher.group(1));
                }
            } catch (IOException error) { System.err.println(error); }
        });
        await(() -> (MAC ? get(279) == 1 && get(278) == revision : window.updateSimulator(game, width, height))
                && get(269) == 1, 20000);
        capture("startup.png");
    }

    private void send(int wheel) throws IOException {
        int next = ++sequence;
        if (MAC) {
            try (var lock = shared.getChannel().lock()) {
                put(6, x); put(7, y); put(8, buttons); put(9, wheel);
                for (int i = 0; i < keys.length; ++i) data.put(64 + i, keys[i]);
                int count = 0;
                while (count < 128 && !text.isEmpty()) put(128 + count++, text.removeFirst());
                put(10, count);
                put(277, next);
            }
        } else {
            put(6, x); put(7, y);
            window.input(x, y, buttons, wheel, keys, text);
            // FIFO marker is posted to exactly the same owned window as the input messages.
            window.automationMarker(next);
        }
        await(() -> get(271) == next, 5000);
        int frame = get(270);
        await(() -> Integer.toUnsignedLong(get(270) - frame) >= 2, 5000);
    }

    private void send() throws IOException {
        send(0);
    }

    private void release() throws IOException {
        buttons = 0; Arrays.fill(keys, (byte)0); text.clear(); send();
    }

    private Map<String, Object> input(Map<?, ?> request) throws IOException {
        String type = (String)request.get("type");
        x = integer(request, "x", x); y = integer(request, "y", y);
        if (x < 0 || x >= width || y < 0 || y >= height) throw new IllegalArgumentException("Point outside frame");
        int targetX = integer(request, "to_x", x), targetY = integer(request, "to_y", y);
        if (targetX < 0 || targetX >= width || targetY < 0 || targetY >= height)
            throw new IllegalArgumentException("Drag endpoint outside frame");
        int steps = integer(request, "steps", 12);
        if (steps < 1 || steps > 120) throw new IllegalArgumentException("Drag steps must be 1..120");
        int button = integer(request, "button", 1);
        if (button != 1 && button != 2 && button != 4) throw new IllegalArgumentException("Unsupported button");
        try {
            switch (type) {
                case "click", "drag" -> {
                    buttons = button; send();
                    if (type.equals("drag")) {
                        int startX = x, startY = y;
                        for (int i = 1; i <= steps; ++i) {
                            x = startX + (targetX - startX) * i / steps;
                            y = startY + (targetY - startY) * i / steps;
                            send();
                        }
                    }
                }
                case "move" -> send();
                case "key" -> {
                    int key = integer(request, "key", -1);
                    if (key < 32 || key > 155) throw new IllegalArgumentException("Unsupported key index");
                    keys[key] = 1; send();
                }
                case "text" -> {
                    String value = (String)request.get("text");
                    if (value == null || value.length() > 4096) throw new IllegalArgumentException("Text too long");
                    value.codePoints().forEach(text::add);
                    do { send(); } while (!text.isEmpty());
                }
                case "wheel" -> {
                    int wheel = integer(request, "delta", 0);
                    if (Math.abs((long)wheel) > 100) throw new IllegalArgumentException("Wheel delta too large");
                    send(wheel);
                }
                default -> throw new IllegalArgumentException("Unsupported input type");
            }
        } finally { release(); }
        return Map.of("input_sequence", sequence, "state", "released", "ack_level", MAC ? "hid_snapshot" : "window_dispatch",
                "render_sequence", get(270), "business_confirmed", false);
    }

    private Map<String, Object> capture(String name) throws IOException {
        if (!name.matches("[a-zA-Z0-9_-]+\\.png")) throw new IllegalArgumentException("Invalid image name");
        int request = get(267) + 1;
        put(267, request);
        await(() -> get(276) == request, 10000);
        BufferedImage image;
        Map<String, Object> metadata;
        try (var lock = shared.getChannel().lock()) {
            int actualWidth = get(1), actualHeight = get(2);
            if (actualWidth != width || actualHeight != height || get(274) != revision)
                throw new IOException("DEVICE_CHANGED: capture dimensions do not match requested device");
            image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int offset = 4096 + get(11) * CAPACITY;
            int[] row = new int[width];
            for (int py = 0; py < height; ++py) {
                // The macOS engine ReadPixels API already returns top-left rows.
                // The Windows OpenGL hook writes raw bottom-up rows instead.
                int sourceY = MAC ? py : height - py - 1;
                for (int px = 0; px < width; ++px) row[px] = data.getInt(offset + (sourceY * width + px) * 4);
                image.setRGB(0, py, width, 1, row, 0, width);
            }
            metadata = new LinkedHashMap<>(Map.of("width", width, "height", height,
                    "device_revision", get(274), "render_sequence", get(272),
                    "input_sequence", get(273), "capture_sequence", get(3),
                    "coordinate_space", "frame_pixels_top_left", "geometry_precision", "unavailable"));
            Path scenePath = directory.resolve("runtime.scene.json");
            if (Files.isRegularFile(scenePath)) {
                Map<?, ?> snapshot = JSON.readValue(scenePath.toFile(), Map.class);
                if (((Number)snapshot.get("render_sequence")).intValue() == get(272)) {
                    metadata.put("scene_snapshot", snapshot);
                }
            }
        }
        Path target = directory.resolve(name), temporary = directory.resolve(name + ".part");
        ImageIO.write(image, "png", temporary.toFile());
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        metadata.put("image_path", target.toString());
        return metadata;
    }

    synchronized Object command(Map<?, ?> request) throws Exception {
        return switch ((String)request.get("command")) {
            case "start" -> { start(request); yield status(); }
            case "status" -> status();
            case "capture" -> capture((String)request.get("name"));
            case "device" -> { configure(request); yield capture((String)request.get("name")); }
            case "input" -> input(request);
            case "logs" -> {
                long after = ((Number)request.getOrDefault("after", null) == null) ? 0 : ((Number)request.get("after")).longValue();
                synchronized (logs) {
                    yield Map.of("entries", logs.stream().filter(item -> (long)item.get("cursor") > after).toList(),
                            "cursor", logCursor, "overflow", !logs.isEmpty() && after < (long)logs.getFirst().get("cursor") - 1);
                }
            }
            default -> throw new IllegalArgumentException("Unknown worker command");
        };
    }

    synchronized Map<String, Object> status() {
        return Map.of("alive", game != null && game.isAlive(), "pid", game == null ? 0 : game.pid(),
                "port", servicePort, "width", width, "height", height, "device_revision", revision,
                "render_sequence", get(270), "input_sequence", get(271), "protocol_version", get(269));
    }

    /** Continuous Remote frames use the same coordinates as deterministic MCP captures. */
    record LiveFrame(int width, int height, int sequence, int revision, int[] pixels) {}

    synchronized LiveFrame liveFrame(int previous, int[] reuse) throws IOException {
        if (game == null || !game.isAlive()) return null;
        try (var lock = shared.getChannel().tryLock()) {
            if (lock == null) return null;
            put(265, 1);
            put(267, get(267) + 1);
            int frame = get(3), w = get(1), h = get(2), slot = get(11);
            if (frame == previous || w != width || h != height || w < 32 || h < 32
                    || slot < 0 || slot > 1 || (long)w * h * 4 > CAPACITY) return null;
            int[] pixels = reuse != null && reuse.length == w * h ? reuse : new int[w * h];
            ByteBuffer source = data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            source.position(4096 + slot * CAPACITY);
            source.asIntBuffer().get(pixels);
            // macOS engine readback is already top-left, just like capture().
            for (int row = 0; !MAC && row < h / 2; row++) {
                int top = row * w, bottom = (h - row - 1) * w;
                for (int col = 0; col < w; col++) {
                    int value = pixels[top + col]; pixels[top + col] = pixels[bottom + col]; pixels[bottom + col] = value;
                }
            }
            return new LiveFrame(w, h, frame, get(274), pixels);
        }
    }

    /** A held Remote pointer must not wait two game frames or release after every move. */
    synchronized void livePointer(int normalizedX, int normalizedY, boolean pressed) throws IOException {
        if (game == null || !game.isAlive()) return;
        if (normalizedX < 0 || normalizedX > 65535 || normalizedY < 0 || normalizedY > 65535)
            throw new IllegalArgumentException("Pointer outside frame");
        x = (int)((long)normalizedX * (width - 1) / 65535);
        y = (int)((long)normalizedY * (height - 1) / 65535);
        buttons = pressed ? 1 : 0;
        if (MAC) {
            try (var lock = shared.getChannel().lock()) {
                put(6, x); put(7, y); put(8, buttons); put(9, 0); put(10, 0);
                put(277, ++sequence);
            }
        } else {
            put(6, x); put(7, y);
            window.input(x, y, buttons, 0, keys, text);
            window.automationMarker(++sequence);
        }
    }

    @Override public synchronized void close() throws Exception {
        if (game != null && game.isAlive()) {
            try { release(); } catch (Exception ignored) { }
            game.destroyForcibly(); game.waitFor(10, TimeUnit.SECONDS); game.getInputStream().close();
        }
        shared.close();
    }

    public static void main(String[] args) throws Exception {
        PrintStream protocol = System.out;
        System.setOut(System.err); // Resource unpacking and libraries must never corrupt the private protocol.
        ResourceUnpacker.unpackResources();
        System.setProperty("defold.unpack.path", args[1]);
        try (var worker = new AutomationWorker(Path.of(args[0]));
             var input = new BufferedReader(new InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8))) {
            for (String line; (line = input.readLine()) != null;) {
                try {
                    Map<?, ?> request = JSON.readValue(line, Map.class);
                    if ("close".equals(request.get("command"))) break;
                    Object result = worker.command(request);
                    protocol.println(JSON.writeValueAsString(Map.of("ok", true, "result", result)));
                } catch (Exception error) {
                    protocol.println(JSON.writeValueAsString(Map.of("ok", false, "error", error.toString())));
                }
                protocol.flush();
            }
        }
    }
}
