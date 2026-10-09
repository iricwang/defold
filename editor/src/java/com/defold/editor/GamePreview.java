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

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** An isolated game process presents BGRA frames through a private, locked shared file. */
public final class GamePreview implements AutoCloseable {
    public static final int MAGIC = 0x44464731;
    public static final int HEADER = 4096;
    public static final int CAPACITY = HEADER + 2048 * 2048 * 4;
    private static final Map<String, GamePreview> VIEWS = new ConcurrentHashMap<>();
    private final String project;
    private final Runnable showGame;
    private final BiConsumer<Object, String> localize;
    private final VBox root = new VBox();
    private final StackPane viewport = new StackPane();
    private final ImageView image = new ImageView();
    private final Label status = new Label();
    private final CheckBox embedded = new CheckBox();
    private final Button run = new Button("▶");
    private final Button stop = new Button("■");
    private final AnimationTimer timer;
    private volatile boolean enabled = true;
    private volatile Session session;
    private WritableImage pixels;
    private byte[] frame;
    private final byte[] keys = new byte[256];
    private final ArrayDeque<Integer> text = new ArrayDeque<>();
    private int buttons, mouseX, mouseY, wheel, sequence;
    private boolean windowFocused;

    public GamePreview(String project, Runnable runCommand, Runnable showGame, BiConsumer<Object, String> localize) {
        this.project = project;
        this.showGame = showGame;
        this.localize = localize;
        localize.accept(status, "game-preview.ready");
        localize.accept(embedded, "game-preview.embedded");
        root.setId("game-preview");
        root.setMinSize(80, 60);
        Label title = new Label("Game");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        embedded.setSelected(true);
        embedded.setOnAction(e -> { enabled = embedded.isSelected(); });
        run.setTooltip(new Tooltip());
        localize.accept(run.getTooltip(), "game-preview.run");
        stop.setTooltip(new Tooltip());
        localize.accept(stop.getTooltip(), "game-preview.stop");
        run.setOnAction(e -> runCommand.run());
        stop.setOnAction(e -> stop());
        for (Button button : List.of(run, stop)) {
            button.setFocusTraversable(false);
            button.setStyle("-fx-min-width: 24px; -fx-pref-width: 24px; -fx-max-width: 24px; -fx-background-color: transparent; -fx-padding: 0;");
        }
        HBox toolbar = new HBox(5, title, spacer, embedded, run, stop);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setMinHeight(26);
        toolbar.setStyle("-fx-padding: 0 28 0 8;");
        viewport.setStyle("-fx-background-color: #15171a;");
        viewport.setMinSize(0, 0);
        viewport.setFocusTraversable(true);
        viewport.setId("game-viewport");
        image.setPreserveRatio(true);
        image.setSmooth(false);
        image.fitWidthProperty().bind(viewport.widthProperty());
        image.fitHeightProperty().bind(viewport.heightProperty());
        image.setMouseTransparent(true);
        status.setWrapText(true);
        status.setMouseTransparent(true);
        viewport.getChildren().addAll(image, status);
        VBox.setVgrow(viewport, Priority.ALWAYS);
        root.getChildren().addAll(toolbar, viewport);
        viewport.addEventFilter(MouseEvent.ANY, e -> {
            if (session == null) return;
            if (e.getEventType() == MouseEvent.MOUSE_PRESSED) viewport.requestFocus();
            if (pixels != null) {
                double[] point = gamePoint(viewport.getWidth(), viewport.getHeight(), pixels.getWidth(), pixels.getHeight(), e.getX(), e.getY());
                mouseX = (int)point[0]; mouseY = (int)point[1];
            }
            buttons = (e.isPrimaryButtonDown() ? 1 : 0) | (e.isSecondaryButtonDown() ? 2 : 0) | (e.isMiddleButtonDown() ? 4 : 0);
            e.consume();
        });
        viewport.addEventFilter(ScrollEvent.SCROLL, e -> { wheel += (int)Math.signum(e.getDeltaY()); e.consume(); });
        viewport.addEventFilter(KeyEvent.ANY, e -> {
            int code = keyCode(e.getCode());
            if (code >= 0) keys[code] = (byte)(e.getEventType() == KeyEvent.KEY_PRESSED ? 1 : 0);
            if (e.getEventType() == KeyEvent.KEY_TYPED && text.size() < 128)
                e.getCharacter().codePoints().filter(c -> c >= 32 && c != 127).forEach(text::add);
            e.consume();
        });
        viewport.focusedProperty().addListener((p, old, focused) -> { if (!focused) releaseInput(); });
        timer = new AnimationTimer() { public void handle(long now) { update(); } };
        timer.start();
        VIEWS.put(project, this);
    }

    public Node getNode() { return root; }

    // Maps letterboxed panel coordinates to game coordinates (HID uses a top-left origin).
    public static double[] gamePoint(double panelW, double panelH, double gameW, double gameH, double x, double y) {
        double scale = Math.min(panelW / gameW, panelH / gameH);
        if (scale <= 0) return new double[]{0, 0};
        return new double[]{Math.max(0, Math.min(gameW - 1, (x - (panelW-gameW*scale)/2)/scale)),
                            Math.max(0, Math.min(gameH - 1, (y - (panelH-gameH*scale)/2)/scale))};
    }

    public static int keyCode(KeyCode code) {
        int value = code.getCode();
        KeyCode[] special = {KeyCode.ESCAPE, KeyCode.UP, KeyCode.DOWN, KeyCode.LEFT, KeyCode.RIGHT,
            KeyCode.SHIFT, KeyCode.CONTROL, KeyCode.ALT, KeyCode.TAB, KeyCode.ENTER, KeyCode.BACK_SPACE,
            KeyCode.DELETE, KeyCode.HOME, KeyCode.END, KeyCode.PAGE_UP, KeyCode.PAGE_DOWN,
            KeyCode.F1, KeyCode.F2, KeyCode.F3, KeyCode.F4, KeyCode.F5, KeyCode.F6,
            KeyCode.F7, KeyCode.F8, KeyCode.F9, KeyCode.F10, KeyCode.F11, KeyCode.F12};
        for (int i = 0; i < special.length; ++i) if (special[i] == code) return 128+i;
        if (value >= 32 && value <= 126 && !code.isArrowKey() && !code.isFunctionKey()) return value;
        return -1;
    }

    public static Session prepare(String project, boolean supported) throws IOException {
        GamePreview view = VIEWS.get(project);
        if (view == null || !view.enabled) return null;
        if (!supported) {
            Platform.runLater(() -> view.localize.accept(view.status, "game-preview.external"));
            return null;
        }
        view.stop();
        Session next = new Session();
        view.session = next;
        Platform.runLater(() -> { view.showGame.run(); view.sequence = 0; view.image.setImage(null); view.pixels = null;
            view.releaseInput(); view.status.setVisible(true); view.localize.accept(view.status, "game-preview.starting"); });
        return next;
    }

    private void releaseInput() { Arrays.fill(keys, (byte)0); buttons = 0; text.clear(); }
    public void stop() {
        Session old = session;
        session = null;
        if (old != null) {
            old.close();
            Platform.runLater(() -> { localize.accept(status, "game-preview.stopped"); status.setVisible(true); });
        }
    }

    private void update() {
        Session current = session;
        stop.setDisable(current == null);
        if (current == null || root.getScene() == null || root.getScene().getWindow() == null) return;
        if (current.process != null && !current.process.isAlive()) {
            localize.accept(status, "game-preview.stopped"); status.setVisible(true); stop(); return;
        }
        try (FileLock lock = current.channel.tryLock()) {
            if (lock == null) return;
            MappedByteBuffer data = current.data;
            boolean nowFocused = root.getScene().getWindow().isFocused();
            boolean focused = root.getScene().getFocusOwner() == viewport;
            if (!focused || (windowFocused && !nowFocused)) releaseInput();
            windowFocused = nowFocused;
            data.putInt(20, focused ? 1 : 0);
            data.putInt(24, mouseX); data.putInt(28, mouseY); data.putInt(32, buttons); data.putInt(36, wheel);
            data.position(64); data.put(keys);
            int count = Math.min(128, data.getInt(40));
            while (count < 128 && !text.isEmpty()) data.putInt(512 + 4*count++, text.removeFirst());
            data.putInt(40, count);
            int w = data.getInt(4), h = data.getInt(8), next = data.getInt(12);
            if (data.getInt(16) == -1) { localize.accept(status, "game-preview.resolution-limit"); status.setVisible(true); return; }
            if (next == sequence || w <= 0 || h <= 0 || w > 2048 || h > 2048) return;
            if (pixels == null || pixels.getWidth() != w || pixels.getHeight() != h) {
                pixels = new WritableImage(w, h); frame = new byte[w*h*4]; image.setImage(pixels);
            }
            data.position(HEADER); data.get(frame);
            // The displayed backbuffer is opaque even if the game's clear alpha is zero.
            for (int i = 3; i < frame.length; i += 4) frame[i] = (byte)255;
            pixels.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getByteBgraInstance(), frame, 0, w*4);
            sequence = next; status.setVisible(false);
        } catch (IOException | IllegalStateException e) {
            localize.accept(status, "game-preview.connection-closed"); status.setVisible(true); stop();
        }
    }

    @Override public void close() { timer.stop(); stop(); VIEWS.remove(project, this); }

    public static final class Session implements AutoCloseable {
        private final Path path;
        private final FileChannel channel;
        private final MappedByteBuffer data;
        private volatile Process process;
        private boolean closed;
        private Session() throws IOException {
            path = Files.createTempFile("defold-game-", ".shm", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            data = channel.map(FileChannel.MapMode.READ_WRITE, 0, CAPACITY);
            data.order(ByteOrder.nativeOrder()); data.putInt(0, MAGIC);
        }
        public String getPath() { return path.toString(); }
        public synchronized void attach(Process process) {
            this.process = process;
            if (closed) process.destroy();
        }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            if (process != null && process.isAlive()) {
                process.destroy();
                ProcessHandle handle = process.toHandle();
                java.util.concurrent.CompletableFuture.delayedExecutor(2, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
                    if (handle.isAlive()) handle.destroyForcibly();
                });
            }
            try { channel.close(); Files.deleteIfExists(path); } catch (IOException ignored) { path.toFile().deleteOnExit(); }
        }
    }
}
