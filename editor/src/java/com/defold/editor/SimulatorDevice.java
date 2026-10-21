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

import java.util.*;
import java.io.IOException;
import java.nio.file.*;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;

/** Pixel dimensions and editable test insets, expressed in portrait coordinates. */
public record SimulatorDevice(String name, int width, int height, int top, int right,
                              int bottom, int left, int radius, int cutoutWidth,
                              int cutoutHeight, int cutoutTop, Map<Integer, Orientation> orientations,
                              double dpi, Path overlay, double[] border) {
    public record Rect(double x, double y, double width, double height) {}
    public record Orientation(Rect safeArea, List<Rect> cutouts) {}

    public SimulatorDevice(String name, int width, int height, int top, int right,
                           int bottom, int left, int radius, int cutoutWidth, int cutoutHeight, int cutoutTop) {
        this(name, width, height, top, right, bottom, left, radius, cutoutWidth, cutoutHeight,
             cutoutTop, Map.of(), 0, null, new double[4]);
    }
    public SimulatorDevice {
        if (width < 64 || height < 64 || width > 4096 || height > 4096
                || top < 0 || right < 0 || bottom < 0 || left < 0
                || (long)top + bottom >= height || (long)left + right >= width
                || radius < 0 || radius > Math.min(width, height) / 2
                || cutoutWidth < 0 || cutoutWidth > width || cutoutHeight < 0
                || cutoutTop < 0 || (long)cutoutHeight + cutoutTop > height)
            throw new IllegalArgumentException("Invalid simulator dimensions or safe area");
        orientations = Map.copyOf(orientations);
        border = border.clone();
        for (var entry : orientations.entrySet()) {
            int rotation = entry.getKey();
            int w = rotation % 2 == 0 ? width : height, h = rotation % 2 == 0 ? height : width;
            if (rotation < 0 || rotation > 3) throw new IllegalArgumentException("Invalid orientation");
            validate(entry.getValue().safeArea(), w, h);
            if (entry.getValue().safeArea().width() <= 0 || entry.getValue().safeArea().height() <= 0)
                throw new IllegalArgumentException("Device safe area is empty");
            for (Rect rect : entry.getValue().cutouts()) validate(rect, w, h);
        }
    }

    // Insets and cutouts are editable layout test profiles, not hardware measurements.
    public static final List<SimulatorDevice> PRESETS = presets();

    private static List<SimulatorDevice> presets() {
        List<SimulatorDevice> devices = new ArrayList<>(List.of(
        new SimulatorDevice("iPhone 14 · 1170 × 2532", 1170, 2532, 141, 0, 102, 0, 138, 465, 96, 0),
        new SimulatorDevice("iPhone 15 Pro · 1179 × 2556", 1179, 2556, 177, 0, 102, 0, 156, 375, 111, 33),
        new SimulatorDevice("Android · 1080 × 2400", 1080, 2400, 96, 0, 72, 0, 96, 72, 72, 24),
        new SimulatorDevice("Tablet · 1536 × 2048", 1536, 2048, 48, 0, 40, 0, 36, 0, 0, 0),
        new SimulatorDevice("HD · 1280 × 720", 1280, 720, 0, 0, 0, 0, 0, 0, 0, 0),
        new SimulatorDevice("Full HD · 1920 × 1080", 1920, 1080, 0, 0, 0, 0, 0, 0, 0, 0)));
        try (var input = SimulatorDevice.class.getResourceAsStream("/game-preview/devices.json")) {
            if (input == null) throw new IOException("Missing device catalog");
            for (JsonNode entry : new ObjectMapper().readTree(input).path("devices")) {
                Map<Integer, Orientation> orientations = new HashMap<>();
                for (JsonNode o : entry.path("orientations")) {
                    List<Rect> cutouts = new ArrayList<>();
                    for (JsonNode r : o.path("cutouts")) cutouts.add(arrayRect(r));
                    orientations.put(o.path("rotation").asInt(), new Orientation(arrayRect(o.path("safeArea")), List.copyOf(cutouts)));
                }
                int w = entry.path("width").asInt(), h = entry.path("height").asInt();
                devices.add(new SimulatorDevice(entry.path("name").asText() + " · " + w + " × " + h,
                    w, h, 0, 0, 0, 0, 0, 0, 0, 0, orientations, entry.path("dpi").asDouble(), null, new double[4]));
            }
        } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
        return List.copyOf(devices);
    }

    private static Rect arrayRect(JsonNode value) {
        if (!value.isArray() || value.size() != 4) throw new IllegalArgumentException("Invalid catalog rectangle");
        return new Rect(value.get(0).asDouble(), value.get(1).asDouble(), value.get(2).asDouble(), value.get(3).asDouble());
    }

    public int pixelWidth(int rotation) { return rotation % 2 == 0 ? width : height; }
    public int pixelHeight(int rotation) { return rotation % 2 == 0 ? height : width; }
    public int nextRotation(int rotation) {
        for (int i = 1; i <= 4; ++i) {
            int next = (rotation + i) % 4;
            if (orientations.isEmpty() || orientations.containsKey(next)) return next;
        }
        throw new IllegalStateException("No device orientations");
    }
    public int initialRotation() { return orientations.isEmpty() ? 0 : orientations.keySet().stream().min(Integer::compare).orElseThrow(); }
    public boolean supports(int rotation) { return orientations.isEmpty() || orientations.containsKey(rotation); }
    /** Insets are top, right, bottom, left. Imported orientations are independent. */
    public int[] insets(int rotation) {
        Orientation orientation = orientations.get(rotation);
        if (orientation != null) {
            Rect r = orientation.safeArea();
            return new int[]{(int)Math.round(pixelHeight(rotation)-r.y()-r.height()),
                (int)Math.round(pixelWidth(rotation)-r.x()-r.width()), (int)Math.round(r.y()), (int)Math.round(r.x())};
        }
        int[] source = {top, right, bottom, left}, result = new int[4];
        for (int i = 0; i < 4; ++i) result[(i + rotation) % 4] = source[i];
        return result;
    }
    public List<Rect> cutouts(int rotation) {
        Orientation orientation = orientations.get(rotation);
        return orientation == null ? List.of() : orientation.cutouts();
    }
    private static void validate(Rect rect, int w, int h) {
        if (!Double.isFinite(rect.x()+rect.y()+rect.width()+rect.height())
                || rect.x()<0 || rect.y()<0 || rect.width()<0 || rect.height()<0
                || rect.x()+rect.width()>w+0.01 || rect.y()+rect.height()>h+0.01)
            throw new IllegalArgumentException("Device rectangle is outside its screen");
    }
    private static Rect rect(JsonNode node, int width, int height) {
        if (node.isMissingNode() || node.isNull()) return new Rect(0, 0, width, height);
        return new Rect(node.path("x").asDouble(), node.path("y").asDouble(),
            node.path("width").asDouble(), node.path("height").asDouble());
    }
    /** Reads the published .device schema; does not depend on Unity code or assets. */
    public static List<SimulatorDevice> read(Path path) throws IOException {
        if (Files.size(path) > 4*1024*1024) throw new IOException("Device definition exceeds 4 MiB");
        JsonNode root = new ObjectMapper().readTree(Files.readAllBytes(path));
        if (root.path("version").asInt() != 1 || !root.path("screens").isArray()
                || !root.path("friendlyName").isTextual() || root.path("friendlyName").asText().isBlank()) throw new IOException("Invalid .device definition");
        List<SimulatorDevice> result = new ArrayList<>();
        int index = 0;
        for (JsonNode screen : root.path("screens")) {
            int w = screen.path("width").asInt(), h = screen.path("height").asInt();
            Map<Integer, Orientation> orientations = new HashMap<>();
            for (JsonNode data : screen.path("orientations")) {
                int rotation = switch (data.path("orientation").asInt()) {
                    case 1 -> 0; case 2 -> 2; case 3 -> 3; case 4 -> 1;
                    default -> throw new IOException("Unsupported screen orientation");
                };
                int rw = rotation % 2 == 0 ? w : h, rh = rotation % 2 == 0 ? h : w;
                List<Rect> cutouts = new ArrayList<>();
                for (JsonNode cutout : data.path("cutouts")) cutouts.add(rect(cutout, 0, 0));
                orientations.put(rotation, new Orientation(rect(data.path("safeArea"), rw, rh), List.copyOf(cutouts)));
            }
            String name = root.path("friendlyName").asText();
            if (root.path("screens").size() > 1) name += " [" + (++index) + "]";
            JsonNode presentation = screen.path("presentation");
            String overlayName = presentation.path("overlayPath").asText();
            Path overlay = null;
            if (overlayName != null && !overlayName.isBlank()) {
                Path directory = path.toAbsolutePath().normalize().getParent();
                Path candidate = directory.resolve(overlayName).normalize();
                if (candidate.startsWith(directory) && Files.isRegularFile(candidate)) overlay = candidate;
            }
            JsonNode b = presentation.path("borderSize");
            double[] border = {b.path("x").asDouble(), b.path("y").asDouble(), b.path("z").asDouble(), b.path("w").asDouble()};
            for (double v : border) if (!Double.isFinite(v) || v < 0) throw new IOException("Invalid device border");
            result.add(new SimulatorDevice(name + " · " + w + " × " + h, w, h, 0, 0, 0, 0, 0, 0, 0, 0,
                orientations, screen.path("dpi").asDouble(), overlay, border));
        }
        if (result.isEmpty()) throw new IOException("Device has no screens");
        return List.copyOf(result);
    }
    @Override public String toString() { return name; }
}
