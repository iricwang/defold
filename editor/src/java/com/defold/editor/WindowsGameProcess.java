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

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import com.defold.libs.ResourceUnpacker;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.WORD;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.platform.win32.WinNT.HANDLEByReference;
import com.sun.jna.ptr.IntByReference;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** A console-free embedded game process, with merged output retained by the editor. */
public final class WindowsGameProcess extends Process {
    private interface ThreadControl extends StdCallLibrary {
        ThreadControl INSTANCE = (ThreadControl) Native.loadLibrary("kernel32", ThreadControl.class);
        int ResumeThread(HANDLE thread);
    }
    private final NativeLibrary hookLibrary;
    private final HANDLE process;
    private final long pid;
    private final ProcessHandle processHandle;
    private final InputStream output;
    private final CompletableFuture<Integer> exit = new CompletableFuture<>();

    private WindowsGameProcess(HANDLE process, long pid, HANDLE output, Pointer hook, NativeLibrary hookLibrary) {
        this.hookLibrary = hookLibrary;
        this.process = process;
        this.pid = pid;
        this.processHandle = ProcessHandle.of(pid).orElse(null);
        this.output = new InputStream() {
            private boolean closed;
            @Override public int read() throws IOException {
                byte[] value = new byte[1];
                return read(value, 0, 1) == -1 ? -1 : value[0] & 255;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, bytes.length);
                if (length == 0) return 0;
                if (closed) return -1;
                byte[] buffer = new byte[Math.min(length, 8192)];
                IntByReference count = new IntByReference();
                if (!Kernel32.INSTANCE.ReadFile(output, buffer, buffer.length, count, null)) {
                    int error = Native.getLastError();
                    if (error == 109 || error == 232) return -1; // Broken/closed child pipe.
                    throw new IOException("Read game output failed: " + error);
                }
                if (count.getValue() == 0) return -1;
                System.arraycopy(buffer, 0, bytes, offset, count.getValue());
                return count.getValue();
            }
            @Override public void close() {
                if (!closed) { closed = true; Kernel32.INSTANCE.CloseHandle(output); }
            }
        };
        Thread.ofPlatform().daemon().name("embedded-game-wait-" + pid).start(() -> {
            Kernel32.INSTANCE.WaitForSingleObject(process, -1);
            IntByReference code = new IntByReference();
            synchronized (this) {
                Kernel32.INSTANCE.GetExitCodeProcess(process, code);
                hookLibrary.getFunction("CloseGameHook").invokeVoid(new Object[]{hook});
                // Complete before closing so destroy() never uses a recycled handle.
                exit.complete(code.getValue());
                Kernel32.INSTANCE.CloseHandle(process);
            }
        });
    }

    private static IOException failure(String operation) {
        return new IOException(operation + " failed: " + Native.getLastError());
    }

    // Microsoft CRT command-line quoting, including quotes and trailing backslashes.
    public static String quoteArgument(String argument) {
        if (argument.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL in argument");
        StringBuilder result = new StringBuilder("\"");
        int slashes = 0;
        for (char ch : argument.toCharArray()) {
            if (ch == '\\') { slashes++; continue; }
            result.append("\\".repeat(ch == '"' ? slashes * 2 + 1 : slashes));
            slashes = 0;
            result.append(ch);
        }
        return result.append("\\".repeat(slashes * 2)).append('"').toString();
    }

    public static Process start(List<String> command, File directory, Map<String, String> overrides) throws IOException {
        if (command.isEmpty()) throw new IllegalArgumentException("Missing game command");
        try { ResourceUnpacker.unpackResources(); }
        catch (java.net.URISyntaxException error) { throw new IOException(error); }
        NativeLibrary hookLibrary = NativeLibrary.getInstance(new File(
                System.getProperty("defold.unpack.path"), "x86_64-win32/lib/game_window_hook.dll").getAbsolutePath());
        TreeMap<String, String> environment = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        environment.putAll(System.getenv());
        overrides.forEach((key, value) -> {
            if (value == null) environment.remove(key); else environment.put(key, value);
        });
        StringBuilder block = new StringBuilder();
        environment.forEach((key, value) -> block.append(key).append('=').append(value).append('\0'));
        block.append('\0');
        byte[] bytes = block.toString().getBytes(StandardCharsets.UTF_16LE);
        Memory nativeEnvironment = new Memory(bytes.length);
        nativeEnvironment.write(0, bytes, 0, bytes.length);
        String line = String.join(" ", command.stream().map(WindowsGameProcess::quoteArgument).toList());

        WinBase.SECURITY_ATTRIBUTES security = new WinBase.SECURITY_ATTRIBUTES();
        security.dwLength = new DWORD(security.size());
        security.bInheritHandle = true;
        HANDLEByReference read = new HANDLEByReference(), write = new HANDLEByReference();
        HANDLEByReference input = new HANDLEByReference(), inputWrite = new HANDLEByReference();
        boolean transferred = false;
        try {
            if (!Kernel32.INSTANCE.CreatePipe(read, write, security, 0)) throw failure("Create output pipe");
            if (!Kernel32.INSTANCE.SetHandleInformation(read.getValue(), 1, 0)) throw failure("Protect output pipe");
            if (!Kernel32.INSTANCE.CreatePipe(input, inputWrite, security, 0)) throw failure("Create input pipe");
            if (!Kernel32.INSTANCE.SetHandleInformation(inputWrite.getValue(), 1, 0)) throw failure("Protect input pipe");
            WinBase.STARTUPINFO startup = new WinBase.STARTUPINFO();
            startup.cb = new DWORD(startup.size());
            // Hide the initial show; the process-scoped guard also holds later show/move calls.
            startup.dwFlags = 0x105; // STARTF_USESTDHANDLES | STARTF_USESHOWWINDOW | STARTF_USEPOSITION.
            startup.dwX = new DWORD(-32000);
            startup.dwY = new DWORD(-32000);
            startup.wShowWindow = new WORD(0); // SW_HIDE, independent of CREATE_NO_WINDOW.
            startup.hStdInput = input.getValue();
            startup.hStdOutput = write.getValue();
            startup.hStdError = write.getValue();
            WinBase.PROCESS_INFORMATION info = new WinBase.PROCESS_INFORMATION();
            // CREATE_SUSPENDED installs the window guard before any game window can be created.
            if (!Kernel32.INSTANCE.CreateProcessW(command.getFirst(), Native.toCharArray(line),
                    null, null, true, new DWORD(0x08000404), nativeEnvironment,
                    directory.getAbsolutePath(), startup, info)) throw failure("Start embedded game");
            Pointer hook = hookLibrary.getFunction("HookGameProcess").invokePointer(new Object[]{info.dwProcessId.intValue()});
            if (hook == null || ThreadControl.INSTANCE.ResumeThread(info.hThread) == -1) {
                IOException error = failure("Guard embedded game startup");
                Kernel32.INSTANCE.TerminateProcess(info.hProcess, 1);
                Kernel32.INSTANCE.CloseHandle(info.hThread);
                Kernel32.INSTANCE.CloseHandle(info.hProcess);
                if (hook != null) hookLibrary.getFunction("CloseGameHook").invokeVoid(new Object[]{hook});
                throw error;
            }
            Kernel32.INSTANCE.CloseHandle(info.hThread);
            WindowsGameProcess result = new WindowsGameProcess(info.hProcess, info.dwProcessId.longValue(), read.getValue(), hook, hookLibrary);
            transferred = true;
            return result;
        } finally {
            if (!transferred && read.getValue() != null) Kernel32.INSTANCE.CloseHandle(read.getValue());
            if (write.getValue() != null) Kernel32.INSTANCE.CloseHandle(write.getValue());
            if (input.getValue() != null) Kernel32.INSTANCE.CloseHandle(input.getValue());
            if (inputWrite.getValue() != null) Kernel32.INSTANCE.CloseHandle(inputWrite.getValue());
        }
    }

    @Override public InputStream getInputStream() { return output; }
    @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
    @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
    @Override public int waitFor() throws InterruptedException {
        try { return exit.get(); } catch (ExecutionException error) { throw new IllegalStateException(error); }
    }
    @Override public int exitValue() {
        if (!exit.isDone()) throw new IllegalThreadStateException("Game is running");
        return exit.join();
    }
    @Override public boolean isAlive() { return !exit.isDone(); }
    @Override public synchronized void destroy() {
        if (!exit.isDone()) Kernel32.INSTANCE.TerminateProcess(process, 0);
    }
    @Override public Process destroyForcibly() { destroy(); return this; }
    @Override public long pid() { return pid; }
    @Override public ProcessHandle toHandle() {
        if (processHandle == null) throw new IllegalStateException("Game already exited");
        return processHandle;
    }
    @Override public CompletableFuture<Process> onExit() { return exit.thenApply(code -> this); }
}
