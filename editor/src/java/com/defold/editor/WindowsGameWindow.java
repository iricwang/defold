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
    }

    private Pointer window;
    private Pointer parent;
    private int x = -1, y = -1, width = -1, height = -1;

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
}
