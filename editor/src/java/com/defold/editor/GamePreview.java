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
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** An isolated game process presents BGRA frames through a private, locked shared file. */
public final class GamePreview implements AutoCloseable {
    public static final int MAGIC = 0x44464732;
    public static final int HEADER = 4096;
    public static final int CAPACITY = HEADER + 2 * 2048 * 2048 * 4;
    private static final Map<String, GamePreview> VIEWS = new ConcurrentHashMap<>();
    private final String project;
    private final Runnable showGame;
    private final RemoteGameServer.Game remote;
    private final java.util.concurrent.ArrayBlockingQueue<int[]> remotePointers = new java.util.concurrent.ArrayBlockingQueue<>(64);
    private volatile long remoteInputTime;
    private int remoteX, remoteY;
    private boolean remotePressed;
    private boolean remotePosition;
    private Session remotePointerSession;
    private int remotePointerSequence;
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
    private Consumer<String> revealGuiResource = path -> {};
    private boolean resourcePickGesture;
    private int pickSequence, pendingPick;
    private double[] pickPoint;
    private long pickDeadline, pickMessageUntil;

    public void setRevealGuiResource(Consumer<String> callback) { revealGuiResource = callback; }

    /** Returns null outside the displayed image; picking must never clamp letterbox clicks. */
    public static double[] resourcePickPoint(double panelWidth, double panelHeight, double width, double height, double x, double y) {
        if (panelWidth <= 0 || panelHeight <= 0 || width <= 0 || height <= 0) return null;
        double scale = Math.min(panelWidth / width, panelHeight / height);
        double px = (x - (panelWidth - width * scale) / 2) / (width * scale);
        double py = (y - (panelHeight - height * scale) / 2) / (height * scale);
        return px >= 0 && px < 1 && py >= 0 && py < 1 ? new double[]{px, py} : null;
    }

    private void pickMessage(String key) {
        localize.accept(status, key);
        pickMessageUntil = System.nanoTime() + 3_000_000_000L;
        status.setVisible(true);
    }

    // Called with the shared file locked. Run navigation after releasing the lock.
    private void updateResourcePick(Session current, ByteBuffer data) {
        if (pickPoint != null) {
            double[] point = pickPoint; pickPoint = null;
            if (data.getInt(1096) != 1) {
                pickMessage("game-preview.pick-unsupported");
                pendingPick = 0;
                return;
            }
            pendingPick = ++pickSequence;
            if (pendingPick == 0) pendingPick = ++pickSequence;
            data.putInt(1084, (int)Math.round(point[0] * 65535));
            data.putInt(1088, (int)Math.round(point[1] * 65535));
            data.putInt(1080, pendingPick);
            pickDeadline = System.nanoTime() + 2_000_000_000L;
        }
        if (pendingPick == 0) return;
        if (data.getInt(1092) == pendingPick) {
            pendingPick = 0;
            int length = data.getInt(1104);
            if (data.getInt(1100) != 1 || length <= 0 || length >= 2048) {
                pickMessage("game-preview.pick-empty");
                return;
            }
            byte[] bytes = new byte[length];
            data.get(1120, bytes);
            String path = new String(bytes, StandardCharsets.UTF_8);
            Platform.runLater(() -> { if (session == current && !current.closed) revealGuiResource.accept(path); });
        } else if (System.nanoTime() >= pickDeadline) {
            pendingPick = 0;
            pickMessage("game-preview.pick-timeout");
        }
    }

    public GamePreview(String project, Runnable runCommand, Runnable showGame, BiConsumer<Object, String> localize) {
        this.project = project;
        this.showGame = showGame;
        this.localize = localize;
        remote = RemoteGameServer.register(project, this, () -> { enabled = true; embedded.setSelected(true); runCommand.run(); });
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
        Button remoteButton = new Button(remote == null ? "Remote unavailable" : remote.getLabel());
        remoteButton.setId("game-remote-pairing"); remoteButton.setFocusTraversable(false);
        remoteButton.setDisable(remote == null);
        remoteButton.setOnAction(e -> remote.showPairing(root.getScene().getWindow(), remoteButton));
        HBox toolbar = new HBox(5, previewResource, spacer, embedded, frameRate, run, stop, previewLock, remoteButton);
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
            boolean pickPress = e.getEventType() == MouseEvent.MOUSE_PRESSED
                && e.getButton() == MouseButton.SECONDARY && e.isControlDown() && e.isShiftDown();
            if (pickPress || resourcePickGesture) {
                resourcePickGesture = e.isSecondaryButtonDown();
                buttons &= ~2;
                if (pickPress) {
                    if (session == null) pickMessage("game-preview.pick-running");
                    else if (session.data == null) pickMessage("game-preview.pick-unsupported");
                    else if (pixels != null) pickPoint = resourcePickPoint(viewport.getWidth(), viewport.getHeight(),
                            pixels.getWidth(), pixels.getHeight(), e.getX(), e.getY());
                }
                e.consume();
                return;
            }
            if (session == null) return;
            if (e.getEventType() == MouseEvent.MOUSE_MOVED || e.getEventType() == MouseEvent.MOUSE_DRAGGED
                    || e.getEventType() == MouseEvent.MOUSE_PRESSED || e.getEventType() == MouseEvent.MOUSE_RELEASED) {
                remotePosition = false;
                remoteRelease();
            }
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
        if (view.remote != null) view.remote.resetFrames();
        Session next = new Session(view.targetFrameRate, view.simulator.active ? view.simulator.device : null, view.simulator.rotation);
        view.session = next;
        Platform.runLater(() -> { view.remotePosition = false; view.remotePressed = false; view.showGame.run(); view.updateTitle(); view.sequence = 0; view.image.setImage(null); view.image.setScaleY(next.simulatorWindow != null ? -1 : 1); view.pixels = null;
            view.previewMode.setVisible(false);
            view.releaseInput(); view.status.setVisible(true); view.localize.accept(view.status, "game-preview.starting"); });
        return next;
    }

    private void releaseInput() { Arrays.fill(keys, (byte)0); buttons = 0; text.clear(); }
    public void stop() {
        pendingPick = 0; pickPoint = null; resourcePickGesture = false; pickMessageUntil = 0;
        remoteRelease();
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
            javafx.stage.Window host = root.getScene() == null ? null : root.getScene().getWindow();
            if (host == null && !remotePressed && (remote == null || !remote.hasStream())) {
                if (current.window != null) current.window.hide();
                return;
            }
            if (current.process != null && !current.process.isAlive()) {
                localize.accept(status, "game-preview.stopped"); status.setVisible(true); stop(); return;
            }
            boolean localShowing = host != null && com.sun.javafx.scene.NodeHelper.isTreeShowing(root)
                    && viewport.getWidth() > 1 && viewport.getHeight() > 1
                    && !(host instanceof javafx.stage.Stage stage && stage.isIconified());
            if (current.isSimulator()) {
                boolean showing = remote != null && remote.hasStream() || localShowing;
                current.data.putInt(1060, showing ? 1 : 0);
                if (!showing) {
                    releaseInput();
                    if (current.simulatorWindow != null)
                        current.simulatorWindow.input(
                            remotePosition ? (int)((long)remoteX * Math.max(0, current.data.getInt(4)-1) / 65535) : mouseX,
                            remotePosition ? (int)((long)remoteY * Math.max(0, current.data.getInt(8)-1) / 65535) : mouseY,
                            0, 0, keys, text);
                    else {
                        try (FileLock lock = current.channel.tryLock()) {
                            if (lock != null) { current.data.putInt(20, 0); current.data.putInt(32, 0); }
                        } catch (IOException | IllegalStateException error) { stop(); }
                    }
                    remotePressed = false;
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
            current.refreshDevice(simulator.rotation);
            if (current.simulatorWindow != null) {
                try {
                    current.simulatorWindow.updateSimulator(current.process, current.device.pixelWidth(simulator.rotation), current.device.pixelHeight(simulator.rotation));
                } catch (RuntimeException error) {
                    error.printStackTrace(); stop(); return;
                }
            }
            if (current.data.getInt(12) == 0 && System.nanoTime() - current.started > 30_000_000_000L) {
                stop();
                localize.accept(status, "game-preview.simulator-timeout"); status.setVisible(true);
                return;
            }
            try (FileLock lock = current.channel.tryLock()) {
                if (lock == null) return;
                ByteBuffer data = current.data;
                updateResourcePick(current, data);
                boolean nowFocused = host != null && host.isFocused();
                boolean focused = nowFocused && root.getScene().getFocusOwner() == viewport;
                if (!focused || (windowFocused && !nowFocused)) releaseInput();
                windowFocused = nowFocused;
                boolean remoteControl = remoteInputTime != 0 && System.nanoTime() - remoteInputTime < 1_000_000_000L;
                boolean releaseRemote = !remoteControl && remotePressed;
                // Preserve tap edges until the engine has completed a frame; FX
                // pulses can run faster than the game's input/render loop.
                int[] pointer = remotePointerSession != current || remotePointerSequence != data.getInt(12)
                    ? remotePointers.poll() : null;
                if (pointer != null && remotePressed && pointer[2] != 0) {
                    int[] newer;
                    while ((newer = remotePointers.peek()) != null && newer[2] != 0) {
                        newer = remotePointers.poll(); if (newer == null) break; pointer = newer;
                    }
                }
                if (pointer != null && remoteControl) {
                    remoteX = pointer[0]; remoteY = pointer[1]; remotePressed = pointer[2] != 0;
                    remotePosition = true; remotePointerSession = current; remotePointerSequence = data.getInt(12);
                }
                if (!remoteControl) { remotePressed = false; remotePointers.clear(); }
                boolean useRemote = remoteControl || releaseRemote;
                data.putInt(20, focused || useRemote ? 1 : 0);
                data.putInt(24, remotePosition ? (int)((long)remoteX * Math.max(0, data.getInt(4)-1) / 65535) : mouseX);
                data.putInt(28, remotePosition ? (int)((long)remoteY * Math.max(0, data.getInt(8)-1) / 65535) : mouseY);
                data.putInt(32, useRemote ? (remotePressed ? 1 : 0) : buttons); data.putInt(36, wheel);
                // Windows consumes window messages, whereas macOS consumes the shared
                // input fields. Dispatch the same resolved controller state to both.
                if (current.simulatorWindow != null) {
                    current.simulatorWindow.input(data.getInt(24), data.getInt(28), data.getInt(32), wheel, keys, text);
                    wheel = 0;
                }
                data.position(64); data.put(keys);
                int count = Math.min(128, data.getInt(40));
                while (count < 128 && !text.isEmpty()) data.putInt(512 + 4*count++, text.removeFirst());
                data.putInt(40, count);
                // A detached/minimized panel still pumps input but does not need a
                // second frame copy and JavaFX upload. Remote owns its readback demand.
                if (!localShowing) return;
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
            if (current.simulatorWindow == null)
                for (int i = 3; i < frame.capacity(); i += 4) frame.put(i, (byte)255);
            pixelBuffer.updateBuffer(buffer -> null);
            status.setVisible(System.nanoTime() < pickMessageUntil);
        }
    }

    @Override public void close() {
        timer.stop(); stop(); VIEWS.remove(project, this);
        if (remote != null) remote.close();
        if (idleDispose != null) idleDispose.run();
        idleDispose = null; idleRefresh = null; idleImage = null;
        image.setImage(null);
    }

    boolean remoteRunning() {
        Session current = session;
        return current != null && !current.closed && current.process != null && current.process.isAlive();
    }
    Path remoteAudioPath() {
        Session current=session;
        return current!=null&&!current.closed&&current.path!=null?RemoteAudioFile.path(current.path):null;
    }
    boolean remotePointer(int x, int y, boolean down) {
        if (!remotePointers.offer(new int[]{x, y, down ? 1 : 0})) return false;
        remoteInputTime = System.nanoTime(); return true;
    }
    void remoteRelease() { remoteInputTime = 0; remotePointers.clear(); }
    static final class RemoteFrame {
        final Object session;
        final int sequence, width, height;
        final int[] rgb;
        RemoteFrame(Object session, int sequence, int width, int height, int[] rgb) {
            this.session=session; this.sequence=sequence; this.width=width; this.height=height; this.rgb=rgb;
        }
    }
    RemoteFrame remoteFrame(Object previousSession, int previousSequence, RemoteStreamQuality quality) throws IOException {
        return remoteFrame(previousSession, previousSequence, quality, null);
    }
    RemoteFrame remoteFrame(Object previousSession, int previousSequence, RemoteStreamQuality quality, int[] reuse) throws IOException {
        Session current = session;
        if (current == null || current.data == null) return null;
        synchronized (current) {
            if (current.closed || current != session) return null;
            try (FileLock lock=current.channel.tryLock()) {
                if (lock==null) return null;
                ByteBuffer data=current.data;
                // Remote must drive readback independently of JavaFX presentation:
                // collapsed/minimized Game panels must not stall the phone stream.
                if (current.isSimulator()) {
                    data.putInt(1060, 1);
                    data.putInt(1068, data.getInt(1068) + 1);
                }
                int sequence=data.getInt(12), width=data.getInt(4), height=data.getInt(8), slot=data.getInt(44);
                if (current==previousSession && sequence==previousSequence || width<=0 || height<=0
                    || width>4096 || height>4096 || (long)width*height*4>current.slotSize || slot<0 || slot>1) return null;
                int[] dimensions=quality.dimensions(width,height);int outW=dimensions[0],outH=dimensions[1];
                int base=HEADER+slot*current.slotSize;
                // One bulk copy avoids a mapped-memory scope check for every pixel.
                int[] source=reuse!=null&&reuse.length==width*height?reuse:new int[width*height];
                ByteBuffer pixels=data.duplicate().order(data.order());pixels.position(base);pixels.asIntBuffer().get(source);
                int[] rgb=source;
                // Windows glReadPixels stores bottom-up rows. The local ImageView
                // flips them, but Remote encoders need top-left rows themselves.
                // Keep X unchanged: reversing the full array would mirror text.
                boolean bottomUp=current.simulatorWindow!=null;
                if(outW!=width||outH!=height){
                    rgb=new int[outW*outH];
                    for(int y=0;y<outH;y++){
                        int sourceY=y*height/outH;
                        if(bottomUp)sourceY=height-1-sourceY;
                        for(int x=0;x<outW;x++)rgb[y*outW+x]=source[sourceY*width+x*width/outW];
                    }
                }else if(bottomUp){
                    for(int y=0;y<height/2;y++){
                        int top=y*width,bottom=(height-1-y)*width;
                        for(int x=0;x<width;x++){
                            int pixel=rgb[top+x];rgb[top+x]=rgb[bottom+x];rgb[bottom+x]=pixel;
                        }
                    }
                }
                return new RemoteFrame(current, sequence, outW, outH, rgb);
            }
        }
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
            simulatorWindow = device != null && com.sun.jna.Platform.isWindows() ? new WindowsGameWindow() : null;
            path = com.sun.jna.Platform.isWindows()
                ? Files.createTempFile("defold-game-", ".shm")
                : Files.createTempFile("defold-game-", ".shm", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            if (com.sun.jna.Platform.isMac()) RemoteAudioFile.create(path);
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
                try { channel.close(); Files.deleteIfExists(path); Files.deleteIfExists(RemoteAudioFile.path(path)); }
                catch (IOException ignored) {
                    if (process != null) process.onExit().thenRun(() -> {
                        try { Files.deleteIfExists(path); Files.deleteIfExists(RemoteAudioFile.path(path)); } catch (IOException error) { path.toFile().deleteOnExit(); }
                    });
                    else path.toFile().deleteOnExit();
                }
            }
        }
    }
}
