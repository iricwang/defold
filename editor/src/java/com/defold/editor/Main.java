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

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import java.awt.AWTError;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.io.IOException;
import java.util.Locale;
import java.util.Properties;

/**
 * Using separate Main class is needed to avoid error "JavaFX runtime components
 * are missing, and are required to run this application."
 *
 * If class with main function extends {@link javafx.application.Application}
 * class, Java 11 checks if the module javafx.graphics is available and refuses
 * to start otherwise. Using other class as container for main is a workaround
 *
 * @see <a href="https://github.com/javafxports/openjdk-jfx/issues/236">Issue
 * discussion on github</a>
 */
public class Main {
    private interface MacDisplays extends Library {
        int CGGetActiveDisplayList(int capacity, int[] displays, IntByReference count);
        Pointer CGDisplayCopyDisplayMode(int display);
        double CGDisplayModeGetRefreshRate(Pointer mode);
        void CGDisplayModeRelease(Pointer mode);
    }

    private static int[] displayRefreshRates() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            // Initializing AWT before Glass on macOS claims NSApplication's dock
            // menu delegate. Query CoreGraphics without starting another UI toolkit.
            MacDisplays api = (MacDisplays)Native.loadLibrary("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", MacDisplays.class);
            IntByReference count = new IntByReference();
            if (api.CGGetActiveDisplayList(0, null, count) != 0) return new int[0];
            int[] displays = new int[count.getValue()];
            if (api.CGGetActiveDisplayList(displays.length, displays, count) != 0) return new int[0];
            int[] rates = new int[count.getValue()];
            for (int i = 0; i < rates.length; ++i) {
                Pointer mode = api.CGDisplayCopyDisplayMode(displays[i]);
                if (mode == null) continue;
                try {
                    rates[i] = (int)Math.ceil(api.CGDisplayModeGetRefreshRate(mode));
                } finally {
                    api.CGDisplayModeRelease(mode);
                }
            }
            return rates;
        }
        GraphicsDevice[] displays = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        int[] rates = new int[displays.length];
        for (int i = 0; i < displays.length; ++i)
            rates[i] = displays[i].getDisplayMode().getRefreshRate();
        return rates;
    }

    static void configureRefreshRate(Properties properties, int[] displayRates) {
        // Match the fastest active display, including high-refresh secondary monitors.
        // A zero rate means that the platform cannot report its refresh rate.
        int refreshRate = 0;
        for (int rate : displayRates) refreshRate = Math.max(refreshRate, rate);
        String detected = Integer.toString(refreshRate > 0 ? refreshRate : 144);
        String animation = properties.getProperty("javafx.animation.framerate");
        String pulse = properties.getProperty("javafx.animation.pulse");
        // Both timers need a target: setting only framerate leaves the non-native
        // pulse scheduler at 60 Hz. Keep explicit user overrides on either timer.
        properties.putIfAbsent("javafx.animation.framerate", pulse != null ? pulse : detected);
        properties.putIfAbsent("javafx.animation.pulse", animation != null ? animation : detected);
    }

    public static void main(String[] args) throws IOException {
        int[] displayRates = new int[0];
        try {
            displayRates = displayRefreshRates();
        } catch (HeadlessException | AWTError | SecurityException | LinkageError error) {
            System.err.println("Unable to detect display refresh rate: " + error.getMessage());
        }
        // Configure before JavaFX initializes. Keep native display synchronization;
        // fullspeed mode would busy-poll without increasing visible refresh rate.
        configureRefreshRate(System.getProperties(), displayRates);
        Start.main(args);
    }
}
