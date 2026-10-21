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
import java.util.function.BiConsumer;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.image.Image;
import javafx.stage.DirectoryChooser;

/** Project-local simulator settings and a physical screen overlay. */
final class GameSimulator {
    final FlowPane toolbar = new FlowPane(6, 4);
    final Canvas overlay = new Canvas();
    final CheckBox enabled = new CheckBox();
    final ComboBox<SimulatorDevice> devices = new ComboBox<>();
    final CheckBox safe = new CheckBox();
    final Button rotate = new Button("↻");
    final MenuItem custom = new MenuItem();
    final MenuItem importDevices = new MenuItem();
    private final List<SimulatorDevice> catalog = new ArrayList<>(SimulatorDevice.PRESETS);
    private final Set<Path> definitions = new LinkedHashSet<>();
    private Image deviceOverlay;
    private final Path settings;
    private final BiConsumer<Object, String> localize;
    volatile SimulatorDevice device = SimulatorDevice.PRESETS.getFirst();
    volatile boolean active;
    volatile int rotation;
    private boolean loading = true;

    GameSimulator(String project, BiConsumer<Object, String> localize, Runnable changed) {
        this.localize = localize;
        settings = Path.of(project, ".internal", "game-simulator.properties");
        enabled.setId("game-simulator-enabled");
        devices.setId("game-simulator-device");
        rotate.setId("game-simulator-rotate");
        rotate.setFocusTraversable(false);
        rotate.setStyle("-fx-min-width: 28; -fx-pref-width: 28; -fx-max-width: 28; -fx-padding: 2;");
        safe.setId("game-simulator-safe-area");
        custom.setId("game-simulator-custom");
        importDevices.setId("game-simulator-import");
        localize.accept(enabled, "game-preview.simulator");
        localize.accept(safe, "game-preview.safe-area");
        localize.accept(custom, "game-preview.custom-device");
        localize.accept(importDevices, "game-preview.import-devices");
        rotate.setTooltip(new Tooltip());
        localize.accept(rotate.getTooltip(), "game-preview.rotate-device");
        devices.getItems().setAll(SimulatorDevice.PRESETS);
        devices.setVisibleRowCount(12);
        devices.setPrefWidth(250);
        load();
        devices.setValue(device);
        enabled.setSelected(active);
        safe.setSelected(true);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setStyle("-fx-padding: 4 8 4 8;");
        toolbar.getStyleClass().add("game-preview-toolbar");
        TextField search = new TextField();
        search.setId("game-simulator-search"); search.setPrefColumnCount(8);
        search.setTooltip(new Tooltip());
        localize.accept(search.getTooltip(), "game-preview.search-device");
        search.promptTextProperty().bind(search.getTooltip().textProperty());
        search.textProperty().addListener((p, old, value) -> {
            SimulatorDevice selected = devices.getValue();
            devices.getItems().setAll(catalog.stream().filter(d -> d.name().toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT))).toList());
            devices.setValue(selected);
        });
        toolbar.getChildren().addAll(enabled, devices, search, rotate, safe);
        overlay.setMouseTransparent(true);
        enabled.setDisable(!com.sun.jna.Platform.isWindows());
        enabled.selectedProperty().addListener((p, old, value) -> { active = value; save(); changed.run(); });
        devices.valueProperty().addListener((p, old, value) -> {
            if (value == null || value == device) return;
            device = value;
            if (!device.supports(rotation)) rotation = device.initialRotation();
            loadOverlay(); save(); changed.run();
        });
        rotate.setOnAction(e -> { rotation = device.nextRotation(rotation); save(); changed.run(); });
        safe.setOnAction(e -> changed.run());
        custom.setOnAction(e -> edit());
        importDevices.setOnAction(e -> importDirectory());
        loadOverlay();
        loading = false;
    }

    void running(boolean value) {
        enabled.setDisable(value || !com.sun.jna.Platform.isWindows());
        devices.setDisable(value || !active);
        custom.setDisable(value || !active);
        importDevices.setDisable(value);
        rotate.setDisable(!active);
        safe.setDisable(!active);
    }

    private void load() {
        if (!Files.isRegularFile(settings)) return;
        Properties p = new Properties();
        try (Reader input = Files.newBufferedReader(settings)) {
            p.load(input);
            for (int i = 0; p.containsKey("definition." + i); ++i) {
                try { addDefinition(Path.of(p.getProperty("definition." + i))); }
                catch (IOException | IllegalArgumentException error) { System.err.println(error.getMessage()); }
            }
            device = new SimulatorDevice(p.getProperty("name", "Custom"),
                Integer.parseInt(p.getProperty("width")), Integer.parseInt(p.getProperty("height")),
                Integer.parseInt(p.getProperty("top")), Integer.parseInt(p.getProperty("right")),
                Integer.parseInt(p.getProperty("bottom")), Integer.parseInt(p.getProperty("left")),
                Integer.parseInt(p.getProperty("radius")), Integer.parseInt(p.getProperty("cutoutWidth")),
                Integer.parseInt(p.getProperty("cutoutHeight")), Integer.parseInt(p.getProperty("cutoutTop")));
            device = catalog.stream().filter(d -> d.name().equals(p.getProperty("name"))).findFirst().orElse(device);
            if (!catalog.contains(device)) catalog.add(device);
            devices.getItems().setAll(catalog);
            active = com.sun.jna.Platform.isWindows() && Boolean.parseBoolean(p.getProperty("enabled"));
            rotation = Integer.parseInt(p.getProperty("rotation", "0"));
            if (rotation < 0 || rotation > 3 || !device.supports(rotation)) rotation = device.initialRotation();
        } catch (IOException | IllegalArgumentException error) {
            System.err.println("Unable to load Game simulator settings: " + error.getMessage());
        }
    }

    private void save() {
        if (loading) return;
        Properties p = new Properties();
        p.setProperty("name", device.name());
        String[] names = {"width", "height", "top", "right", "bottom", "left", "radius", "cutoutWidth", "cutoutHeight", "cutoutTop"};
        int[] values = values();
        for (int i = 0; i < names.length; ++i) p.setProperty(names[i], Integer.toString(values[i]));
        p.setProperty("enabled", Boolean.toString(active));
        p.setProperty("rotation", Integer.toString(rotation));
        int index = 0;
        for (Path path : definitions) p.setProperty("definition." + index++, path.toString());
        try {
            Files.createDirectories(settings.getParent());
            Path temporary = Files.createTempFile(settings.getParent(), "simulator-", ".tmp");
            try {
                try (Writer output = Files.newBufferedWriter(temporary)) { p.store(output, "Game simulator"); }
                Files.move(temporary, settings, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException error) {
            System.err.println("Unable to save Game simulator settings: " + error.getMessage());
        }
    }

    private int[] values() {
        return new int[]{device.width(), device.height(), device.top(), device.right(), device.bottom(),
            device.left(), device.radius(), device.cutoutWidth(), device.cutoutHeight(), device.cutoutTop()};
    }

    private void edit() {
        Dialog<SimulatorDevice> dialog = new Dialog<>();
        dialog.initOwner(toolbar.getScene().getWindow());
        dialog.getDialogPane().getStylesheets().setAll(toolbar.getScene().getStylesheets());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        GridPane grid = new GridPane();
        grid.setHgap(12); grid.setVgap(6);
        Label hint = new Label();
        localize.accept(hint, "game-preview.device-hint");
        hint.setWrapText(true); hint.setMaxWidth(380);
        grid.add(hint, 0, 0, 2, 1);
        String[] labels = {"width", "height", "top", "right", "bottom", "left", "radius", "cutout-width", "cutout-height", "cutout-top"};
        TextField[] fields = new TextField[labels.length];
        int[] values = values();
        for (int i = 0; i < fields.length; ++i) {
            Label label = new Label();
            localize.accept(label, "game-preview.device-" + labels[i]);
            fields[i] = new TextField(Integer.toString(values[i]));
            grid.add(label, 0, i + 1); grid.add(fields[i], 1, i + 1);
        }
        Label error = new Label();
        error.setWrapText(true); error.setMaxWidth(380);
        grid.add(error, 0, fields.length + 1, 2, 1);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            try {
                int[] v = Arrays.stream(fields).mapToInt(f -> Integer.parseInt(f.getText().trim())).toArray();
                SimulatorDevice next = new SimulatorDevice("Custom · " + v[0] + " × " + v[1],
                    v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9]);
                catalog.add(next); devices.getItems().setAll(catalog); devices.setValue(next);
            } catch (IllegalArgumentException ex) {
                localize.accept(error, "game-preview.device-invalid"); e.consume();
            }
        });
        dialog.showAndWait();
    }

    private void loadOverlay() {
        deviceOverlay = null;
        if (device.overlay() != null) {
            Image candidate = new Image(device.overlay().toUri().toString(), false);
            if (!candidate.isError() && candidate.getWidth() > 0) deviceOverlay = candidate;
        }
    }

    private List<SimulatorDevice> addDefinition(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (definitions.contains(normalized)) return List.of();
        List<SimulatorDevice> imported = SimulatorDevice.read(normalized);
        definitions.add(normalized);
        for (SimulatorDevice next : imported) {
            catalog.removeIf(existing -> existing.name().equals(next.name()));
            catalog.add(next);
        }
        return imported;
    }

    private void importDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        File directory = chooser.showDialog(toolbar.getScene().getWindow());
        if (directory == null) return;
        List<String> errors = new ArrayList<>();
        List<SimulatorDevice> imported = new ArrayList<>();
        try (var paths = Files.walk(directory.toPath(), 12)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".device")).sorted().toList()) {
                try { imported.addAll(addDefinition(path)); }
                catch (IOException | IllegalArgumentException error) { errors.add(path.getFileName() + ": " + error.getMessage()); }
            }
        } catch (IOException error) { errors.add(error.getMessage()); }
        devices.getItems().setAll(catalog);
        if (!imported.isEmpty()) devices.setValue(imported.getFirst());
        save();
        if (!errors.isEmpty() || imported.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.initOwner(toolbar.getScene().getWindow());
            alert.getDialogPane().getStylesheets().setAll(toolbar.getScene().getStylesheets());
            alert.setHeaderText(imported.size() + " devices imported");
            alert.setContentText(String.join("\n", errors.stream().limit(8).toList())); alert.showAndWait();
        }
    }

    void draw(double width, double height) {
        if (overlay.getWidth() != width) overlay.setWidth(width);
        if (overlay.getHeight() != height) overlay.setHeight(height);
        GraphicsContext g = overlay.getGraphicsContext2D();
        g.clearRect(0, 0, width, height);
        if (!active || width <= 0 || height <= 0) return;
        double w = device.pixelWidth(rotation), h = device.pixelHeight(rotation);
        double scale = Math.min(width / w, height / h);
        double x = (width - w * scale) / 2, y = (height - h * scale) / 2;
        g.save(); g.translate(x, y); g.scale(scale, scale);
        switch (rotation) {
            case 1 -> { g.translate(w, 0); g.rotate(90); }
            case 2 -> { g.translate(w, h); g.rotate(180); }
            case 3 -> { g.translate(0, h); g.rotate(270); }
        }
        double r = device.radius(), dw = device.width(), dh = device.height();
        g.setFill(Color.BLACK);
        // Only the corner wedges are covered; the actual runtime pixels remain untouched.
        if (r > 0) {
            for (int corner = 0; corner < 4; ++corner) {
                g.save();
                switch (corner) {
                    case 1 -> { g.translate(dw, 0); g.rotate(90); }
                    case 2 -> { g.translate(dw, dh); g.rotate(180); }
                    case 3 -> { g.translate(0, dh); g.rotate(270); }
                }
                g.beginPath(); g.moveTo(0, 0); g.lineTo(r, 0);
                g.bezierCurveTo(r * 0.448, 0, 0, r * 0.448, 0, r);
                g.closePath(); g.fill(); g.restore();
            }
        }
        g.fillRoundRect((dw - device.cutoutWidth()) / 2, device.cutoutTop(),
            device.cutoutWidth(), device.cutoutHeight(), device.cutoutHeight(), device.cutoutHeight());
        if (deviceOverlay != null) {
            double[] b = device.border();
            g.drawImage(deviceOverlay, b[0], b[1], deviceOverlay.getWidth()-b[0]-b[2], deviceOverlay.getHeight()-b[1]-b[3],
                0, 0, dw, dh);
        }
        g.restore();
        if (deviceOverlay == null) {
            g.setFill(Color.BLACK);
            for (SimulatorDevice.Rect rect : device.cutouts(rotation))
                g.fillRect(x+rect.x()*scale, y+(h-rect.y()-rect.height())*scale, rect.width()*scale, rect.height()*scale);
        }
        if (safe.isSelected()) {
            int[] insets = device.insets(rotation);
            g.setStroke(Color.web("#54dfb1")); g.setLineWidth(1); g.setLineDashes(5, 4);
            g.strokeRect(x + insets[3] * scale, y + insets[0] * scale,
                (w - insets[1] - insets[3]) * scale, (h - insets[0] - insets[2]) * scale);
            g.setLineDashes();
        }
    }

    boolean accepts(double x, double y) {
        if (!active) return true;
        double px = x, py = y;
        switch (rotation) {
            case 1 -> { px = y; py = device.height() - x; }
            case 2 -> { px = device.width() - x; py = device.height() - y; }
            case 3 -> { px = device.width() - y; py = x; }
        }
        if (px < 0 || py < 0 || px >= device.width() || py >= device.height()) return false;
        if (deviceOverlay != null) {
            double[] b = device.border();
            int ix = (int)(b[0]+px/device.width()*(deviceOverlay.getWidth()-b[0]-b[2]));
            int iy = (int)(b[1]+py/device.height()*(deviceOverlay.getHeight()-b[1]-b[3]));
            if (ix >= 0 && iy >= 0 && ix < deviceOverlay.getWidth() && iy < deviceOverlay.getHeight()
                    && deviceOverlay.getPixelReader().getColor(ix, iy).getOpacity() > 0.5) return false;
        } else {
            double bottomY = device.pixelHeight(rotation) - y;
            for (SimulatorDevice.Rect rect : device.cutouts(rotation))
                if (x >= rect.x() && x <= rect.x()+rect.width() && bottomY >= rect.y() && bottomY <= rect.y()+rect.height()) return false;
        }
        double cx = Math.max(device.radius(), Math.min(device.width() - device.radius(), px));
        double cy = Math.max(device.radius(), Math.min(device.height() - device.radius(), py));
        if (Math.hypot(px - cx, py - cy) > device.radius()) return false;
        return !(px >= (device.width() - device.cutoutWidth()) / 2.0
            && px <= (device.width() + device.cutoutWidth()) / 2.0
            && py >= device.cutoutTop() && py <= device.cutoutTop() + device.cutoutHeight());
    }
}
