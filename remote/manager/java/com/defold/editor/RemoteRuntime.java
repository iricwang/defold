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
import com.sun.net.httpserver.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

/** Per-game, loopback-only media sidecar. EOF owns cleanup; no JavaFX event loop. */
public final class RemoteRuntime implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AutomationWorker worker;
    private final HttpServer server;
    private final String token = UUID.randomUUID().toString() + UUID.randomUUID();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile HttpExchange stream;
    private volatile long writingSince, lastInput;
    private volatile boolean down, closed;
    private volatile int pointerX, pointerY;
    private long frames;
    private volatile String codec = "none";
    private String lease = "";

    private RemoteRuntime(Path directory) throws IOException {
        System.setProperty("sun.net.httpserver.nodelay", "true");
        worker = new AutomationWorker(directory);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        server.createContext("/", this::request);
        server.setExecutor(executor);
        server.start();
        timer.scheduleWithFixedDelay(() -> {
            try {
                synchronized (this) {
                    if (down && System.nanoTime() - lastInput > 1_000_000_000L) release();
                    if (stream != null && writingSince != 0 && System.nanoTime() - writingSince > 1_500_000_000L)
                        closeStream();
                }
            } catch (Exception e) { System.err.println("Remote watchdog: " + e); }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

    private synchronized void release() throws IOException {
        down = false;
        worker.livePointer(pointerX, pointerY, false);
    }

    private synchronized void closeStream() {
        HttpExchange old = stream; stream = null; writingSince = 0; codec = "none";
        try { release(); } catch (IOException ignored) { }
        if (old != null) Thread.ofVirtual().start(old::close);
    }

    private void request(HttpExchange exchange) throws IOException {
        try {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (!MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                    Objects.toString(authorization, "").getBytes(StandardCharsets.UTF_8))) {
                reply(exchange, 403, Map.of("error", "Unauthorized")); return;
            }
            String route = exchange.getRequestURI().getPath();
            if (route.equals("/input") && exchange.getRequestMethod().equals("POST")) {
                byte[] bytes = exchange.getRequestBody().readNBytes(6);
                if (bytes.length != 5 || (bytes[4] != 0 && bytes[4] != 1)) throw new IllegalArgumentException("Invalid pointer");
                synchronized (this) {
                    if (lease.isEmpty() || !lease.equals(exchange.getRequestHeaders().getFirst("X-Remote-Lease"))) {
                        reply(exchange, 403, Map.of("error", "Control lease expired")); return;
                    }
                    if (!Objects.toString(worker.status().get("device_revision"), "").equals(exchange.getRequestHeaders().getFirst("X-Defold-Device-Revision"))) {
                        reply(exchange, 409, Map.of("error", "Device revision changed")); return;
                    }
                    pointerX = (bytes[0] & 255) | (bytes[1] & 255) << 8;
                    pointerY = (bytes[2] & 255) | (bytes[3] & 255) << 8;
                    down = bytes[4] != 0; lastInput = System.nanoTime();
                    worker.livePointer(pointerX, pointerY, down);
                }
                reply(exchange, 200, Map.of("ok", true));
            } else if (route.equals("/release") && exchange.getRequestMethod().equals("POST")) {
                synchronized (this) { lease = ""; closeStream(); }
                reply(exchange, 200, Map.of("ok", true));
            } else if (route.equals("/lease") && exchange.getRequestMethod().equals("POST")) {
                String value = new String(exchange.getRequestBody().readNBytes(64), StandardCharsets.UTF_8);
                if (!value.matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("Invalid lease");
                synchronized (this) { closeStream(); lease = value; }
                reply(exchange, 200, Map.of("ok", true));
            } else if (route.equals("/status") && exchange.getRequestMethod().equals("GET")) {
                reply(exchange, 200, status());
            } else if (route.equals("/thumbnail") && exchange.getRequestMethod().equals("GET")) {
                AutomationWorker.LiveFrame frame = firstFrame();
                byte[] bytes = jpeg(frame, 400);
                exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            } else if ((route.equals("/video") || route.equals("/stream")) && exchange.getRequestMethod().equals("GET")) {
                video(exchange, route.equals("/video"));
            } else reply(exchange, 404, Map.of("error", "Not found"));
        } catch (IllegalArgumentException e) { reply(exchange, 400, Map.of("error", e.getMessage())); }
        catch (Exception e) { try { reply(exchange, 503, Map.of("error", e.toString())); } catch (IOException ignored) { exchange.close(); } }
    }

    private AutomationWorker.LiveFrame firstFrame() throws Exception {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (!closed && System.nanoTime() < deadline) {
            AutomationWorker.LiveFrame frame = worker.liveFrame(-1, null);
            if (frame != null) return frame;
            if (!Boolean.TRUE.equals(worker.status().get("alive"))) throw new IOException("Game stopped");
            Thread.sleep(5);
        }
        throw new IOException("No game frame");
    }

    private void video(HttpExchange exchange, boolean hardware) throws Exception {
        if (hardware && !RemoteVideoEncoder.available()) { reply(exchange, 503, Map.of("error", "Hardware encoder unavailable")); return; }
        String query = exchange.getRequestURI().getRawQuery();
        RemoteStreamQuality quality = RemoteStreamQuality.parse(query == null ? "720p" : query.replaceFirst("^resolution=", ""));
        AutomationWorker.LiveFrame frame = firstFrame();
        int sourceWidth = frame.width(), sourceHeight = frame.height();
        int[] dimensions = quality.dimensions(sourceWidth, sourceHeight);
        RemoteVideoEncoder encoder = hardware ? new RemoteVideoEncoder(dimensions[0] & ~1, dimensions[1] & ~1) : null;
        synchronized (this) {
            if (lease.isEmpty() || !lease.equals(exchange.getRequestHeaders().getFirst("X-Remote-Lease"))) {
                if (encoder != null) encoder.close();
                reply(exchange, 403, Map.of("error", "Control lease expired")); return;
            }
            closeStream(); stream = exchange; codec = hardware ? "h264" : "jpeg";
        }
        try {
            exchange.getResponseHeaders().set("Content-Type", hardware ? "application/x-defold-h264" : "application/x-defold-jpeg");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, 0);
            try (DataOutputStream output = new DataOutputStream(exchange.getResponseBody())) {
                if (hardware) { output.writeInt(dimensions[0] & ~1); output.writeInt(dimensions[1] & ~1); }
                int previous = -1; int[] pixels = frame.pixels();
                long next = System.nanoTime();
                while (!closed && stream == exchange && Boolean.TRUE.equals(worker.status().get("alive"))) {
                    long delay = next - System.nanoTime();
                    if (delay > 0) java.util.concurrent.locks.LockSupport.parkNanos(delay);
                    if (frame == null) frame = worker.liveFrame(previous, pixels);
                    if (frame == null) { Thread.sleep(1); continue; }
                    if (frame.width() != sourceWidth || frame.height() != sourceHeight) break;
                    pixels = frame.pixels(); previous = frame.sequence();
                    byte[] bytes = hardware ? encoder.frame(pixels, sourceWidth, sourceHeight) : jpeg(frame, quality.maxEdge);
                    synchronized (this) { if (stream != exchange) break; writingSince = System.nanoTime(); }
                    try { output.writeInt(bytes.length); output.writeInt(previous); output.write(bytes); output.flush(); frames++; }
                    finally { synchronized (this) { if (stream == exchange) writingSince = 0; } }
                    frame = null;
                    long interval = hardware ? 16_666_667L : 33_333_333L;
                    next = Math.max(next + interval, System.nanoTime());
                }
            }
        } finally {
            if (encoder != null) encoder.close();
            synchronized (this) { if (stream == exchange) closeStream(); }
            exchange.close();
        }
    }

    private static byte[] jpeg(AutomationWorker.LiveFrame frame, int edge) throws IOException {
        double scale = Math.min(1.0, (double)edge / Math.max(frame.width(), frame.height()));
        int w = Math.max(1, (int)(frame.width() * scale)), h = Math.max(1, (int)(frame.height() * scale));
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
            image.setRGB(x, y, frame.pixels()[(y * frame.height() / h) * frame.width() + x * frame.width() / w]);
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(image, "jpeg", out); return out.toByteArray();
    }

    static void reply(HttpExchange exchange, int code, Object value) throws IOException {
        byte[] body = JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, body.length);
        try (var out = exchange.getResponseBody()) { out.write(body); }
    }

    private Map<String, Object> status() {
        Map<String, Object> state = new LinkedHashMap<>(worker.status());
        state.put("frames_sent", frames); state.put("stream_codec", codec);
        state.put("endpoint", "http://127.0.0.1:" + server.getAddress().getPort()); state.put("token", token);
        return state;
    }

    @Override public void close() throws Exception {
        closed = true; closeStream(); server.stop(0); timer.shutdownNow(); worker.close(); executor.shutdownNow();
    }

    public static void main(String[] args) throws Exception {
        PrintStream protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8); System.setOut(System.err);
        ResourceUnpacker.unpackResources(); System.setProperty("defold.unpack.path", args[1]);
        try (var runtime = new RemoteRuntime(Path.of(args[0]));
             var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            for (String line; (line = input.readLine()) != null;) {
                try {
                    Map<?, ?> request = JSON.readValue(line, Map.class);
                    if ("close".equals(request.get("command"))) break;
                    Object result = runtime.worker.command(request);
                    if ("start".equals(request.get("command")) || "status".equals(request.get("command"))) result = runtime.status();
                    protocol.println(JSON.writeValueAsString(Map.of("ok", true, "result", result)));
                } catch (Exception e) { protocol.println(JSON.writeValueAsString(Map.of("ok", false, "error", e.toString()))); }
                protocol.flush();
            }
        }
    }
}
