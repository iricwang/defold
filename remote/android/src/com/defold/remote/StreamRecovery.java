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

package com.defold.remote;

/** Bounded reconnects; a brief transport interruption must not permanently disable H.264. */
public final class StreamRecovery {
    private boolean hardware;
    private int failures, videoFailures;
    private long firstFrame = -1;

    public StreamRecovery(boolean hardware) { this.hardware = hardware; }
    public synchronized boolean hardware() { return hardware; }
    public synchronized void beginAttempt() { firstFrame = -1; }
    public synchronized void frame(long now) {
        if (firstFrame < 0) firstFrame = now;
        // A single decoded frame followed by failure is not a healthy connection.
        if (now - firstFrame >= 5000) { failures = 0; videoFailures = 0; }
    }
    public synchronized boolean failed(boolean unsupportedCodec) {
        failures++;
        if (hardware && (unsupportedCodec || ++videoFailures >= 3)) hardware = false;
        return failures < 5;
    }
    public synchronized long delayMillis() { return Math.min(2000, 250L << Math.min(3, Math.max(0, failures - 1))); }
}
