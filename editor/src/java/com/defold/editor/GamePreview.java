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
import java.lang.foreign.Arena;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/** An isolated game process presents BGRA frames through a private, locked shared file. */
public final class GamePreview implements AutoCloseable {
    public static final int MAGIC = 0x44464732;
    public static final int HEADER = 4096;
    public static final int CAPACITY = HEADER + 2 * 2048 * 2048 * 4;
    private static final Map<String, GamePreview> VIEWS = new ConcurrentHashMap<>();
    private final String project;
    private final Runnable showGame;
    private final BiConsumer<Object, String> localize;
    private final VBox root = new VBox();
    private final StackPane viewport = new StackPane();
    private final ImageView image = new ImageView();
    private final Label previewMode = new Label();
    private final ToggleButton previewLock = new ToggleButton();
    private final Label previewResource = new Label();
    private String resourcePath = "";
    private String previewLayout = "";
    private long previewRenders, previewCacheHits, previewCacheBytes, previewLastNanos;
    private final GameSimulator simulator;
    private Runnable idleRefresh;
    private Runnable idleDispose;
    private Image idleImage;
    private String idleStatusKey = "game-preview.edit-empty";
    private long lastIdleRefresh;
    private final Label status = new Label();
    private final CheckBox embedded = new CheckBox();
    private final ComboBox<Integer> frameRate = new ComboBox<>();
    private volatile int targetFrameRate = 120;
    private final Button run = new Button("▶");
    private final Button stop = new Button("■");
    private final AnimationTimer timer;
    private volatile boolean enabled = true;
    private volatile Session session;
    private WritableImage pixels;
    private ByteBuffer frame;
    private PixelBuffer<ByteBuffer> pixelBuffer;
    private final byte[] keys = new byte[256];
    private final ArrayDeque<Integer> text = new ArrayDeque<>();
    private int buttons, mouseX, mouseY, wheel, sequence;
    private boolean windowFocused;

    public GamePreview(String project, Runnable runCommand, Runnable showGame, BiConsumer<Object, String> localize) {
        this.project = project;
        this.showGame = showGame;
        this.localize = localize;
        simulator = new GameSimulator(project, localize, () -> {
            simulatorChanged();
        });
        localize.accept(status, "game-preview.ready");
        localize.accept(embedded, "game-preview.embedded");
        root.setId("game-preview");
        root.getProperties().put("dock-title", previewResource.textProperty());
        root.getProperties().put("dock-menu-items", List.of(simulator.custom, simulator.importDevices));
        root.setMinSize(80, 60);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        embedded.setSelected(true);
        embedded.setMinWidth(Region.USE_PREF_SIZE);
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
        frameRate.setId("game-frame-rate");
        frameRate.getItems().addAll(60, 120, 144);
        frameRate.setValue(targetFrameRate);
        frameRate.setFocusTraversable(false);
        frameRate.setConverter(new javafx.util.StringConverter<Integer>() {
            public String toString(Integer value) { return value + " FPS"; }
            public Integer fromString(String value) { throw new UnsupportedOperationException(); }
        });
        frameRate.valueProperty().addListener((p, old, value) -> targetFrameRate = value);
        frameRate.setTooltip(new Tooltip());
        localize.accept(frameRate.getTooltip(), "game-preview.frame-rate");
        previewMode.setId("game-preview-mode");
        previewMode.setMouseTransparent(true);
        previewMode.setTooltip(new Tooltip());
        localize.accept(previewMode.getTooltip(), "game-preview.edit-hint");
        previewLock.setId("game-preview-lock");
        previewLock.setFocusTraversable(false);
        previewLock.setTooltip(new Tooltip());
        localize.accept(previewLock.getTooltip(), "game-preview.lock");
        previewLock.accessibleTextProperty().bind(previewLock.getTooltip().textProperty());
        javafx.scene.shape.SVGPath lockIcon = new javafx.scene.shape.SVGPath();
        lockIcon.setStyle("-fx-fill: -df-text;");
        previewLock.setGraphic(lockIcon);
        previewLock.setStyle("-fx-min-width: 24; -fx-pref-width: 24; -fx-max-width: 24; -fx-padding: 4;");
        Runnable updateLock = () -> lockIcon.setContent(previewLock.isSelected()
            ? "M3 7V4a4 4 0 0 1 8 0v3h1v8H2V7z M5 7h4V4a2 2 0 0 0-4 0z"
            : "M3 7V4a4 4 0 0 1 8 0H9a2 2 0 0 0-4 0v3h7v8H2V7z");
        previewLock.selectedProperty().addListener((p, old, value) -> updateLock.run());
        updateLock.run();
        previewResource.setId("game-preview-resource");
        previewResource.setMinWidth(0);
        previewResource.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(previewResource, Priority.ALWAYS);
        previewResource.setTooltip(new Tooltip());
        previewResource.setText("Game");
        HBox toolbar = new HBox(5, previewResource, spacer, embedded, frameRate, run, stop, previewLock);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setMinHeight(26);
        toolbar.getStyleClass().add("game-preview-toolbar");
        toolbar.setStyle("-fx-padding: 0 28 0 8;");
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
        viewport.getChildren().addAll(image, simulator.overlay, status);
        viewport.widthProperty().addListener((p, old, value) -> simulator.draw(viewport.getWidth(), viewport.getHeight()));
        viewport.heightProperty().addListener((p, old, value) -> simulator.draw(viewport.getWidth(), viewport.getHeight()));
        VBox.setVgrow(viewport, Priority.ALWAYS);
        root.getChildren().addAll(toolbar, simulator.toolbar, viewport);
        viewport.addEventFilter(MouseEvent.ANY, e -> {
            if (session == null) return;
            if (e.getEventType() == MouseEvent.MOUSE_PRESSED) viewport.requestFocus();
            if (pixels != null) {
                double[] point = gamePoint(viewport.getWidth(), viewport.getHeight(), pixels.getWidth(), pixels.getHeight(), e.getX(), e.getY());
                mouseX = (int)point[0]; mouseY = (int)point[1];
            }
            buttons = (e.isPrimaryButtonDown() ? 1 : 0) | (e.isSecondaryButtonDown() ? 2 : 0) | (e.isMiddleButtonDown() ? 4 : 0);
            if (simulator.active && pixels != null) {
                double scale = Math.min(viewport.getWidth() / pixels.getWidth(), viewport.getHeight() / pixels.getHeight());
                double px = (e.getX() - (viewport.getWidth() - pixels.getWidth() * scale) / 2) / scale;
                double py = (e.getY() - (viewport.getHeight() - pixels.getHeight() * scale) / 2) / scale;
                if (!simulator.accepts(px, py)) buttons = 0;
            }
            e.consume();
        });
        viewport.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (session == null) return;
            wheel += (int)Math.signum(e.getDeltaY()); e.consume();
        });
        viewport.addEventFilter(KeyEvent.ANY, e -> {
            if (session == null) return;
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

    public static boolean simulatorEnabled(String project) {
        GamePreview view = VIEWS.get(project);
        return view != null && view.enabled && view.simulator.active;
    }

    private void simulatorChanged() {
        simulator.draw(viewport.getWidth(), viewport.getHeight());
        if (session == null) {
            if (idleRefresh != null) idleRefresh.run();
            showIdleImage();
        }
    }

    public boolean isPreviewLocked() { return previewLock.isSelected(); }
    public void unlockPreview() { previewLock.setSelected(false); }
    public void setPreviewResource(String path) {
        if (!resourcePath.equals(path)) previewLayout = "";
        resourcePath = path;
        updateTitle();
    }
    /** Show the effective GUI reference and device resolution without changing panel zoom. */
    public void setPreviewLayout(String layout, int referenceWidth, int referenceHeight, int deviceWidth, int deviceHeight) {
        previewLayout = referenceWidth > 0
            ? layout + " · " + referenceWidth + " × " + referenceHeight + " → " + deviceWidth + " × " + deviceHeight
            : "";
        updateTitle();
    }
    private void updateTitle() {
        boolean resource = session == null && !resourcePath.isEmpty();
        previewResource.setText(resource ? "Game · " + resourcePath.substring(resourcePath.lastIndexOf('/') + 1) : "Game");
        previewResource.getTooltip().setText(resource ? resourcePath + (previewLayout.isEmpty() ? "" : "\n" + previewLayout) : "Game");
    }
    public int[] getPreviewDeviceSize() {
        return simulator.active ? new int[]{simulator.device.pixelWidth(simulator.rotation), simulator.device.pixelHeight(simulator.rotation)} : null;
    }
    /** The same physical safe-area insets used by the native runtime bridge, in top/right/bottom/left order. */
    public int[] getPreviewSafeInsets() {
        return simulator.active ? simulator.device.insets(simulator.rotation) : new int[4];
    }
    /** FX-thread diagnostics: renders, cache hits, retained image bytes, last switch/render time. */
    public long[] getPreviewMetrics() {
        return new long[]{previewRenders, previewCacheHits, previewCacheBytes, previewLastNanos};
    }
    public void recordPreviewFrame(boolean cached, long bytes, long nanos) {
        if (cached) ++previewCacheHits; else ++previewRenders;
        previewCacheBytes = bytes;
        previewLastNanos = nanos;
    }

    /** Called on the FX thread. Idle rendering never creates a game process. */
    public void setIdleRenderer(Runnable refresh, Runnable dispose) {
        if (idleDispose != null) idleDispose.run();
        idleRefresh = refresh;
        idleDispose = dispose;
    }

    public double getPreviewWidth() { return viewport.getWidth(); }
    public double getPreviewHeight() { return viewport.getHeight(); }

    public void setIdleImage(Image value, String modeKey) {
        idleImage = value;
        idleStatusKey = switch (modeKey) {
            case "game-preview.edit-loading", "game-preview.edit-unavailable" -> modeKey;
            default -> "game-preview.edit-empty";
        };
        localize.accept(previewMode, modeKey);
        if (session == null) showIdleImage();
    }

    private void showIdleImage() {
        updateTitle();
        simulator.overlay.setVisible(simulator.active && idleImage != null);
        image.setImage(idleImage);
        image.setScaleY(-1);
        previewMode.setVisible(true);
        status.setVisible(idleImage == null);
        if (idleImage == null) localize.accept(status, idleStatusKey);
    }

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
        Session next = new Session(view.targetFrameRate, view.simulator.active ? view.simulator.device : null, view.simulator.rotation);
        view.session = next;
        Platform.runLater(() -> { view.showGame.run(); view.updateTitle(); view.sequence = 0; view.image.setImage(null); view.image.setScaleY(next.isSimulator() ? -1 : 1); view.pixels = null;
            view.previewMode.setVisible(false);
            view.releaseInput(); view.status.setVisible(true); view.localize.accept(view.status, "game-preview.starting"); });
        return next;
    }

    private void releaseInput() { Arrays.fill(keys, (byte)0); buttons = 0; text.clear(); }
    public void stop() {
        Session old = session;
        session = null;
        if (old != null) {
            old.close();
            Platform.runLater(() -> {
                if (session == null) {
                    pixels = null; pixelBuffer = null; frame = null;
                    showIdleImage();
                }
            });
        }
    }

    private void update() {
        Session current = session;
        stop.setDisable(current == null);
        frameRate.setDisable(current != null);
        simulator.running(current != null);
        previewLock.setDisable(current != null || resourcePath.isEmpty());
        simulator.overlay.setVisible(current == null ? simulator.active && idleImage != null : current.isSimulator());
        if (current == null) {
            long now = System.nanoTime();
            if (idleRefresh != null && now - lastIdleRefresh >= 33_333_333L
                    && com.sun.javafx.scene.NodeHelper.isTreeShowing(root)
                    && viewport.getWidth() > 1 && viewport.getHeight() > 1) {
                lastIdleRefresh = now;
                idleRefresh.run();
            }
            return;
        }
        // Engine launch/stop can run on the build thread. Keep the shared mapping
        // alive for the entire FX read; Session.close uses the same monitor.
        synchronized (current) {
            if (current.closed || current != session) return;
            if (root.getScene() == null || root.getScene().getWindow() == null) {
                if (current.window != null) current.window.hide();
                return;
            }
            if (current.process != null && !current.process.isAlive()) {
                localize.accept(status, "game-preview.stopped"); status.setVisible(true); stop(); return;
            }
            if (current.isSimulator()) {
                boolean showing = com.sun.javafx.scene.NodeHelper.isTreeShowing(root)
                    && viewport.getWidth() > 1 && viewport.getHeight() > 1
                    && !(root.getScene().getWindow() instanceof javafx.stage.Stage stage && stage.isIconified());
                current.data.putInt(1060, showing ? 1 : 0);
                if (!showing) {
                    releaseInput();
                    current.simulatorWindow.input(mouseX, mouseY, 0, 0, keys, text);
                    return;
                }
            }
            if (current.window != null) {
                try {
                    status.setVisible(!current.window.update(current.process, viewport));
                } catch (RuntimeException error) {
                    error.printStackTrace();
                    stop();
                    localize.accept(status, "game-preview.connection-closed");
                    status.setVisible(true);
                }
                return;
            }
            if (current.simulatorWindow != null) {
                try {
                    current.refreshDevice(simulator.rotation);
                    current.simulatorWindow.updateSimulator(current.process, current.device.pixelWidth(simulator.rotation), current.device.pixelHeight(simulator.rotation));
                    if (!root.getScene().getWindow().isFocused() || root.getScene().getFocusOwner() != viewport) releaseInput();
                    current.simulatorWindow.input(mouseX, mouseY, buttons, wheel, keys, text);
                    wheel = 0;
                } catch (RuntimeException error) {
                    error.printStackTrace(); stop(); return;
                }
            }
            if (sequence == 0 && System.nanoTime() - current.started > 30_000_000_000L) {
                stop();
                localize.accept(status, "game-preview.simulator-timeout"); status.setVisible(true);
                return;
            }
            try (FileLock lock = current.channel.tryLock()) {
                if (lock == null) return;
                ByteBuffer data = current.data;
                boolean nowFocused = root.getScene().getWindow().isFocused();
                boolean focused = nowFocused && root.getScene().getFocusOwner() == viewport;
                if (!focused || (windowFocused && !nowFocused)) releaseInput();
                windowFocused = nowFocused;
                data.putInt(20, focused ? 1 : 0);
                data.putInt(24, mouseX); data.putInt(28, mouseY); data.putInt(32, buttons); data.putInt(36, wheel);
                data.position(64); data.put(keys);
                int count = Math.min(128, data.getInt(40));
                while (count < 128 && !text.isEmpty()) data.putInt(512 + 4*count++, text.removeFirst());
                data.putInt(40, count);
                int w = data.getInt(4), h = data.getInt(8), next = data.getInt(12);
                if (current.isSimulator() && data.getInt(52) != 1) return;
                if (data.getInt(16) == -1) { localize.accept(status, "game-preview.resolution-limit"); status.setVisible(true); return; }
                if (next == sequence || w <= 0 || h <= 0 || w > 4096 || h > 4096 || (long)w*h*4 > current.slotSize) return;
                if (pixels == null || pixels.getWidth() != w || pixels.getHeight() != h) {
                    frame = ByteBuffer.allocateDirect(w*h*4);
                    pixelBuffer = new PixelBuffer<>(w, h, frame, PixelFormat.getByteBgraPreInstance());
                    pixels = new WritableImage(pixelBuffer);
                    image.setImage(pixels);
                }
                int slot = data.getInt(44);
                if (slot != 0 && slot != 1) throw new IOException("Invalid frame slot");
                frame.put(0, data, HEADER + slot * current.slotSize, frame.capacity());
                sequence = next;
                if (current.isSimulator()) data.putInt(1068, data.getInt(1068) + 1);
            } catch (IOException | IllegalStateException e) {
                stop();
                localize.accept(status, "game-preview.connection-closed"); status.setVisible(true); return;
            }
            // Release the shared lock before uploading to JavaFX. PixelBuffer avoids
            // PixelWriter's extra staging copy and per-frame format conversion.
            if (!current.isSimulator())
                for (int i = 3; i < frame.capacity(); i += 4) frame.put(i, (byte)255);
            pixelBuffer.updateBuffer(buffer -> null);
            status.setVisible(false);
        }
    }

    @Override public void close() {
        timer.stop(); stop(); VIEWS.remove(project, this);
        if (idleDispose != null) idleDispose.run();
        idleDispose = null; idleRefresh = null; idleImage = null;
        image.setImage(null);
    }

    public static final class Session implements AutoCloseable {
        private final Path path;
        private final FileChannel channel;
        private final ByteBuffer data;
        private final Arena arena;
        private final WindowsGameWindow window;
        private final WindowsGameWindow simulatorWindow;
        private int savedRotation = -1;
        private int settingsSequence;
        private volatile Process process;
        private boolean closed;
        private final int frameRate;
        private final SimulatorDevice device;
        private final int rotation;
        private final int slotSize;
        private final long started = System.nanoTime();
        private Session(int frameRate, SimulatorDevice device, int rotation) throws IOException {
            this.frameRate = frameRate;
            this.device = device;
            this.rotation = rotation;
            slotSize = device == null ? 2048*2048*4 : device.width()*device.height()*4;
            if (com.sun.jna.Platform.isWindows() && device == null) {
                window = new WindowsGameWindow();
                simulatorWindow = null;
                path = null;
                channel = null;
                data = null;
                arena = null;
                return;
            }
            window = null;
            simulatorWindow = device == null ? null : new WindowsGameWindow();
            path = com.sun.jna.Platform.isWindows()
                ? Files.createTempFile("defold-game-", ".shm")
                : Files.createTempFile("defold-game-", ".shm", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            arena = Arena.ofShared();
            data = channel.map(FileChannel.MapMode.READ_WRITE, 0, HEADER + 2L * slotSize, arena).asByteBuffer();
            data.order(ByteOrder.nativeOrder()); data.putInt(0, device == null ? MAGIC : 0x44464733);
            data.putInt(48, slotSize);
            data.putInt(1068, 1);
            refreshDevice(rotation);
        }
        public int getFrameRate() { return frameRate; }
        private void refreshDevice(int rotation) {
            if (device == null || rotation == savedRotation) return;
            int[] insets = device.insets(rotation);
            data.putInt(1056, ++settingsSequence);
            java.lang.invoke.VarHandle.fullFence();
            data.putInt(1024, device.pixelWidth(rotation));
            data.putInt(1028, device.pixelHeight(rotation));
            for (int i = 0; i < 4; ++i) data.putInt(1032 + i * 4, insets[i]);
            java.lang.invoke.VarHandle.fullFence();
            data.putInt(1056, ++settingsSequence);
            savedRotation = rotation;
        }
        public boolean isSimulator() { return device != null; }
        public int getDeviceWidth() { return device == null ? 0 : device.pixelWidth(rotation); }
        public int getDeviceHeight() { return device == null ? 0 : device.pixelHeight(rotation); }
        public String getPath() { return path == null ? null : path.toString(); }
        public synchronized void attach(Process process) {
            this.process = process;
            if (closed) process.destroy();
        }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            if (window != null) window.hide();
            if (process != null && process.isAlive()) {
                process.destroy();
                ProcessHandle handle = process.toHandle();
                java.util.concurrent.CompletableFuture.delayedExecutor(2, java.util.concurrent.TimeUnit.SECONDS).execute(() -> {
                    if (handle.isAlive()) handle.destroyForcibly();
                });
            }
            if (channel != null) {
                arena.close();
                try { channel.close(); Files.deleteIfExists(path); }
                catch (IOException ignored) {
                    if (process != null) process.onExit().thenRun(() -> {
                        try { Files.deleteIfExists(path); } catch (IOException error) { path.toFile().deleteOnExit(); }
                    });
                    else path.toFile().deleteOnExit();
                }
            }
        }
    }
}
