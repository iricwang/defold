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

import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.User32;
import javafx.animation.AnimationTimer;
import javafx.scene.robot.Robot;
import javafx.stage.Window;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Tracks an initiated panel drag even after reparenting releases native mouse capture. */
public final class DockDrag implements AutoCloseable {
    public record Sample(double x, double y, boolean pressed, boolean escape) {}
    private final Supplier<Sample> pointer;
    private final BiConsumer<Double, Double> moved;
    private final BiConsumer<Double, Double> released;
    private final Runnable cancelled;
    private final AnimationTimer timer;
    private boolean closed;

    public DockDrag(Supplier<Sample> pointer, BiConsumer<Double, Double> moved,
                    BiConsumer<Double, Double> released, Runnable cancelled) {
        this.pointer = pointer;
        this.moved = moved;
        this.released = released;
        this.cancelled = cancelled;
        timer = new AnimationTimer() { @Override public void handle(long now) { poll(); } };
        timer.start();
    }

    public static DockDrag start(Window owner, BiConsumer<Double, Double> moved,
                                 BiConsumer<Double, Double> released, Runnable cancelled) {
        Supplier<Sample> pointer;
        if (Platform.isWindows()) {
            User32 api = User32.INSTANCE;
            int primary = api.GetSystemMetrics(23) == 0 ? 1 : 2; // SM_SWAPBUTTON; physical primary button.
            if (api.GetAsyncKeyState(primary) >= 0) return null; // Synthetic events keep using JavaFX routing.
            Robot robot = new Robot();
            pointer = () -> new Sample(robot.getMouseX(), robot.getMouseY(),
                    api.GetAsyncKeyState(primary) < 0,
                    !owner.isShowing() || api.GetAsyncKeyState(0x1B) < 0);
        } else if (Platform.isMac()) {
            NativeLibrary graphics = NativeLibrary.getInstance("/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices");
            var button = graphics.getFunction("CGEventSourceButtonState");
            var key = graphics.getFunction("CGEventSourceKeyState");
            if (button.invokeInt(new Object[]{0, 0}) == 0) return null;
            Robot robot = new Robot();
            pointer = () -> new Sample(robot.getMouseX(), robot.getMouseY(),
                    button.invokeInt(new Object[]{0, 0}) != 0,
                    !owner.isShowing() || key.invokeInt(new Object[]{0, 53}) != 0);
        } else return null;
        return new DockDrag(pointer, moved, released, cancelled);
    }

    /** Samples logical screen coordinates on the FX thread; it never depends on the old Scene. */
    public void poll() {
        if (closed) return;
        Sample sample = pointer.get();
        if (sample.escape()) {
            close();
            cancelled.run();
        } else {
            moved.accept(sample.x(), sample.y());
            if (!sample.pressed() && !closed) {
                close();
                released.accept(sample.x(), sample.y());
            }
        }
    }

    @Override public void close() {
        closed = true;
        timer.stop();
    }
}
