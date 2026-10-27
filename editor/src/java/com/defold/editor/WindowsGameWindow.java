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

import com.sun.javafx.stage.WindowHelper;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.stage.Window;

/** Parents only the launched game's window into the current JavaFX viewport. */
final class WindowsGameWindow {
    interface User32 extends StdCallLibrary {
        User32 INSTANCE = (User32) Native.loadLibrary("user32", User32.class);
        interface EnumProc extends StdCallCallback {
            boolean callback(Pointer window, Pointer data);
        }
        boolean EnumWindows(EnumProc callback, Pointer data);
        int GetWindowThreadProcessId(Pointer window, IntByReference process);
        Pointer GetWindow(Pointer window, int command);
        Pointer GetPropW(Pointer window, com.sun.jna.WString name);
        Pointer GetParent(Pointer window);
        Pointer SetParent(Pointer window, Pointer parent);
        Pointer GetWindowLongPtrW(Pointer window, int index);
        Pointer SetWindowLongPtrW(Pointer window, int index, Pointer value);
        boolean GetClientRect(Pointer window, Pointer rectangle);
        boolean SetWindowPos(Pointer window, Pointer after, int x, int y, int width, int height, int flags);
        boolean ShowWindow(Pointer window, int command);
        boolean IsWindowVisible(Pointer window);
        boolean PostMessageW(Pointer window, int message, Pointer wparam, Pointer lparam);
        int MapVirtualKeyW(int code, int mapType);
    }

    private Pointer window;
    private Pointer parent;
    private int x = -1, y = -1, width = -1, height = -1;
    private int simulatorButtons;
    private final byte[] simulatorKeys = new byte[256];

    private static Pointer findWindow(long processId) {
        User32 api = User32.INSTANCE;
        Pointer[] result = {null};
        api.EnumWindows((candidate, ignored) -> {
            IntByReference pid = new IntByReference();
            api.GetWindowThreadProcessId(candidate, pid);
            if (Integer.toUnsignedLong(pid.getValue()) == processId
                    && api.GetWindow(candidate, 4) == null
                    && (api.IsWindowVisible(candidate)
                        || api.GetPropW(candidate, new com.sun.jna.WString("DefoldEditorGameShowRequested")) != null)) {
                result[0] = candidate;
                return false;
            }
            return true;
        }, null);
        return result[0];
    }

    boolean update(Process process, Node viewport) {
        if (process == null || !process.isAlive()) return false;
        User32 api = User32.INSTANCE;
        if (window == null) {
            window = findWindow(process.pid());
            if (window == null) return false;
        }
        for (Node node = viewport; node != null; node = node.getParent()) {
            if (!node.isVisible()) { hide(); return true; }
        }
        if (viewport.getScene() == null) { hide(); return true; }
        Window host = viewport.getScene().getWindow();
        if (host == null || !host.isShowing() || WindowHelper.getPeer(host) == null) {
            hide();
            return true;
        }
        Pointer nextParent = new Pointer(WindowHelper.getPeer(host).getRawHandle());
        if (!nextParent.equals(parent)) {
            api.ShowWindow(window, 0);
            long style = Pointer.nativeValue(api.GetWindowLongPtrW(window, -16));
            // WS_CHILD, without caption, sizing border, system menu or popup ownership.
            style = (style & ~0x80CF0000L) | 0x40000000L;
            api.SetWindowLongPtrW(window, -16, new Pointer(style));
            api.SetParent(window, nextParent);
            if (!nextParent.equals(api.GetParent(window))) {
                throw new IllegalStateException("Unable to embed the game window in its Game panel");
            }
            parent = nextParent;
            width = -1;
        }
        Bounds bounds = viewport.localToScene(viewport.getBoundsInLocal());
        Memory rect = new Memory(16);
        if (!api.GetClientRect(parent, rect)) return false;
        double scaleX = rect.getInt(8) / viewport.getScene().getWidth();
        double scaleY = rect.getInt(12) / viewport.getScene().getHeight();
        int nextX = (int)Math.round(bounds.getMinX() * scaleX);
        int nextY = (int)Math.round(bounds.getMinY() * scaleY);
        int nextWidth = Math.max(1, (int)Math.round(bounds.getWidth() * scaleX));
        int nextHeight = Math.max(1, (int)Math.round(bounds.getHeight() * scaleY));
        if (nextX != x || nextY != y || nextWidth != width || nextHeight != height) {
            api.SetWindowPos(window, null, nextX, nextY, nextWidth, nextHeight, 0x0034);
            x = nextX; y = nextY; width = nextWidth; height = nextHeight;
        }
        api.ShowWindow(window, 4); // SW_SHOWNOACTIVATE; mouse input gives the game focus.
        return true;
    }

    void hide() {
        if (window != null) User32.INSTANCE.ShowWindow(window, 0);
    }

    /** A Simulator drawable stays hidden at device resolution; JavaFX scales its captured frame. */
    boolean updateSimulator(Process process, int deviceWidth, int deviceHeight) {
        if (process == null || !process.isAlive()) return false;
        if (window == null) window = findWindow(process.pid());
        if (window == null) return false;
        if (width != deviceWidth || height != deviceHeight) {
            User32 api = User32.INSTANCE;
            api.ShowWindow(window, 0);
            long style = Pointer.nativeValue(api.GetWindowLongPtrW(window, -16));
            api.SetWindowLongPtrW(window, -16, new Pointer((style & ~0x40CF0000L) | 0x80000000L));
            api.SetWindowPos(window, null, -32000, -32000, deviceWidth, deviceHeight, 0x0034);
            width = deviceWidth; height = deviceHeight;
        }
        return true;
    }

    private void post(int message, long wparam, long lparam) {
        if (window != null) User32.INSTANCE.PostMessageW(window, message, new Pointer(wparam), new Pointer(lparam));
    }

    /** FIFO acknowledgement for an owned automation session; does not affect human Game input. */
    void automationMarker(int sequence) {
        post(0x8000 + 0x3D1, Integer.toUnsignedLong(sequence), 0);
    }

    void input(int mouseX, int mouseY, int buttons, int wheel, byte[] keys, java.util.ArrayDeque<Integer> text) {
        if (window == null) return;
        long point = (mouseX & 0xffffL) | ((mouseY & 0xffffL) << 16);
        int state = ((buttons & 1) != 0 ? 1 : 0) | ((buttons & 2) != 0 ? 2 : 0) | ((buttons & 4) != 0 ? 16 : 0);
        post(0x0200, state, point);
        int[] down = {0x0201, 0x0204, 0x0207};
        for (int i = 0; i < 3; ++i) if (((buttons ^ simulatorButtons) & (1 << i)) != 0)
            post(down[i] + ((buttons & (1 << i)) == 0 ? 1 : 0), state, point);
        simulatorButtons = buttons;
        if (wheel != 0) post(0x020A, ((long)(wheel*120) << 16) | state, point);
        int[] special = {27, 38, 40, 37, 39, 16, 17, 18, 9, 13, 8, 46, 36, 35, 33, 34,
            112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123};
        for (int i = 32; i < keys.length; ++i) if (keys[i] != simulatorKeys[i]) {
            int code = i < 128 ? i : i < 128 + special.length ? special[i-128] : 0;
            if (code != 0) {
                long flags = 1 | ((long)User32.INSTANCE.MapVirtualKeyW(code, 0) << 16);
                if (code >= 33 && code <= 46) flags |= 1L << 24;
                if (keys[i] == 0) flags |= 0xC0000000L;
                post(keys[i] == 0 ? 0x0101 : 0x0100, code, flags);
            }
            simulatorKeys[i] = keys[i];
        }
        while (!text.isEmpty()) for (char value : Character.toChars(text.removeFirst())) post(0x0102, value, 1);
    }
}
