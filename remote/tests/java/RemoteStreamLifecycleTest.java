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

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/** Deterministic transport ownership checks, invoked with the isolated JavaFX host. */
public class RemoteStreamLifecycleTest {
    private static final class BlockedClose extends HttpExchange {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        @Override public void close() {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test release missing"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        @Override public Headers getRequestHeaders() { return new Headers(); }
        @Override public Headers getResponseHeaders() { return new Headers(); }
        @Override public URI getRequestURI() { return URI.create("/test"); }
        @Override public String getRequestMethod() { return "GET"; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public InputStream getRequestBody() { return InputStream.nullInputStream(); }
        @Override public OutputStream getResponseBody() { return OutputStream.nullOutputStream(); }
        @Override public void sendResponseHeaders(int code, long length) { }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public int getResponseCode() { return 200; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { }
        @Override public void setStreams(InputStream input, OutputStream output) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
    // A blocked socket close must not block capture; late writes cannot clear or terminate a new stream.
    public static void verify(RemoteGameServer.Game game) throws Exception {
        BlockedClose old = new BlockedClose(), replacement = new BlockedClose();
        ExecutorService watchdog = Executors.newSingleThreadExecutor();
        try {
            game.stream = old;
            if (!game.beginVideoWrite(old)) throw new AssertionError("Missing owner");
            long started = game.videoWriteStarted;
            game.expireStreams(started + 500_000_000L);
            if (game.stream != old) throw new AssertionError("Brief Wi-Fi stall disconnected");
            watchdog.submit(() -> game.expireStreams(started + RemoteGameServer.WRITE_TIMEOUT_NANOS + 1)).get(1, TimeUnit.SECONDS);
            if (!old.entered.await(1, TimeUnit.SECONDS) || game.stream != null) throw new AssertionError("Stall not detached");
            if (game.stalledStreams != 1) throw new AssertionError("Missing stall diagnostic");
            game.stream = replacement;
            if (!game.beginVideoWrite(replacement)) throw new AssertionError("Replacement unavailable");
            long replacementStart = game.videoWriteStarted;
            game.endVideoWrite(old);
            if (game.beginVideoWrite(old) || game.videoWriteStarted != replacementStart) throw new AssertionError("Stale writer changed new deadline");
            game.expireStreams(replacementStart + 500_000_000L);
            if (game.stream != replacement) throw new AssertionError("Stale timeout closed replacement");
            game.endVideoWrite(replacement);
            if (game.videoWriteStarted != 0) throw new AssertionError("Write completion not recorded");
            System.out.println("Remote stream ownership, Wi-Fi grace, asynchronous close checks passed");
        } finally {
            old.release.countDown(); replacement.release.countDown();
            game.stream = null; game.videoWriteStarted = 0;
            watchdog.shutdownNow();
        }
    }
}
