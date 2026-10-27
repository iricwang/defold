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

/** Optional platform codec. Older hosts and clients retain the JPEG protocol. */
public final class RemoteVideoEncoder implements AutoCloseable {
    private static final boolean AVAILABLE = load();
    private long handle;
    final int width, height;
    public static boolean available() { return AVAILABLE; }
    private static boolean load() {
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        if (!windows && !System.getProperty("os.name", "").startsWith("Mac")) return false;
        String arch = System.getProperty("os.arch").equals("aarch64") ? "arm64" : "x86_64";
        String resource = windows ? "/lib/x86_64-win32/remote_video.dll" : "/lib/" + arch + "-macos/libremote_video.dylib";
        try (InputStream input = RemoteVideoEncoder.class.getResourceAsStream(resource)) {
            if (input == null) return false;
            Path library = Files.createTempFile("defold-remote-video-", windows ? ".dll" : ".dylib");
            Files.copy(input, library, StandardCopyOption.REPLACE_EXISTING);
            library.toFile().deleteOnExit(); System.load(library.toString());
            if (windows) {
                long probe = create(640, 360);
                if (probe == 0) return false;
                destroy(probe);
            }
            return true;
        } catch (IOException | LinkageError | SecurityException e) {
            System.err.println("Remote hardware video unavailable: " + e.getMessage()); return false;
        }
    }
    public RemoteVideoEncoder(int width, int height) throws IOException {
        this.width = width; this.height = height;
        if (!AVAILABLE || (handle = create(width, height)) == 0) throw new IOException("Hardware encoder unavailable");
    }
    public byte[] frame(int[] rgb) throws IOException { return frame(rgb, width, height); }
    public byte[] frame(int[] rgb, int sourceWidth, int sourceHeight) throws IOException {
        if (handle == 0) throw new IOException("Encoder closed");
        byte[] bytes = encode(handle, rgb, sourceWidth, sourceHeight);
        if (bytes == null) throw new IOException("Hardware encode failed or timed out");
        return bytes;
    }
    @Override public void close() { if (handle != 0) { destroy(handle); handle = 0; } }
    private static native long create(int width, int height);
    private static native byte[] encode(long handle, int[] rgb, int sourceWidth, int sourceHeight);
    private static native void destroy(long handle);
}
