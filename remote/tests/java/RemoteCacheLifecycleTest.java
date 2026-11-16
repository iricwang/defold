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

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Real Manager snapshot/registry/collection I/O with controlled compiler, launch and process leases. */
public final class RemoteCacheLifecycleTest {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = RemoteManager.JSON;
    private static final List<String> checks = new ArrayList<>();
    private static final List<Map<String,Object>> processEvidence = new ArrayList<>();
    private static final AtomicLong clock = new AtomicLong(1700000000000L);
    private static final RemoteManager.SnapshotObserver NONE = () -> { };
    private static void check(String name, boolean passed) {
        if (!passed) throw new AssertionError(name);
        checks.add(name);
    }
    private static Path write(Path path, String text) throws IOException {
        Files.createDirectories(path.getParent()); Files.writeString(path, text);
        Files.setLastModifiedTime(path, FileTime.fromMillis(clock.incrementAndGet())); return path;
    }
    private static Path write(Path path) throws IOException { return write(path, "owned temporary data"); }
    private static Path source(Path work, String name) throws IOException {
        Path root = work.resolve(name);
        write(root.resolve("game.project"), "[project]\ntitle = Cache lifecycle test\n");
        write(root.resolve("main.lua"), "return 0\n");
        write(root.resolve("assets/payload.bin"), "x".repeat(128 * 1024)); return root;
    }
    private static long directories(Path parent) throws IOException {
        if (!Files.isDirectory(parent)) return 0;
        try (var entries = Files.list(parent)) { return entries.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).count(); }
    }
    private static long bytes(Path parent) throws IOException {
        if (!Files.exists(parent)) return 0;
        try (var files = Files.walk(parent)) {
            long total = 0;
            for (Path file : files.toList()) if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) total += Files.size(file);
            return total;
        }
    }
    private static Map<?,?> rpc(RemoteManager manager, String method, String id) throws Exception {
        return (Map<?,?>) manager.command(Map.of("method", method, "params", Map.of("id", id)));
    }

    private static final class ControlledManager extends RemoteManager {
        SnapshotObserver changes = NONE;
        int compilations, launches;
        boolean failCompilation, processCompiler;
        ControlledManager(Path manifest, Path state) throws Exception {
            super(manifest, state, 0); monitor.shutdownNow(); settings.put("prepare", false);
        }
        @Override SnapshotObserver snapshotObserver(Project p, long generation) {
            return new SnapshotObserver() {
                public void check() throws Exception { ControlledManager.this.check(p, generation); changes.check(); }
                public void copied(Path source, Path target, int attempt) throws Exception { changes.copied(source, target, attempt); }
            };
        }
        @Override Path automaticBuild(Project p, Path destination, long generation) throws Exception {
            if (processCompiler) return super.automaticBuild(p, destination, generation);
            check(p, generation); compilations++;
            Path log = projectState(destination).resolve("build.log");
            synchronized (p) { p.buildLog = log; p.stage = "controlled compilation"; }
            write(log, failCompilation ? "old diagnostics\n".repeat(3000) + "INTENTIONAL_COMPILE_FAILURE\n" : "controlled compilation succeeded\n");
            if (failCompilation) throw new IOException("INTENTIONAL_COMPILE_FAILURE");
            write(destination.resolve("build/default/game.projectc"), "[display]\nwidth = 320\nheight = 240\n");
            Path engine = write(destination.resolve("build/controlled-engine"), "controlled engine bytes");
            if (System.getProperty("os.name").startsWith("Windows")) write(symbols(engine), "controlled symbols");
            return engine;
        }
        @Override void launch(Project p, long generation) throws Exception { check(p, generation); launches++; }
        Project add(Path source) throws Exception {
            Map<?,?> result = (Map<?,?>) command(Map.of("method", "add", "params", Map.of("path", source.toString())));
            return project(result.get("id").toString());
        }
        void settled() throws Exception { builds.submit(() -> { }).get(20, TimeUnit.SECONDS); }
        void collect() throws Exception { builds.submit(() -> { cleanupStorage(); return null; }).get(20, TimeUnit.SECONDS); }
    }

    private static Path lease(Path state, String id, Path build, Path engine, boolean live) throws Exception {
        Path session = state.resolve("sessions").resolve(id + "-" + UUID.randomUUID());
        write(session.resolve("runtime.shared"), "controlled worker storage");
        writeLease(session, build, engine, live); return session;
    }
    private static void writeLease(Path session, Path build, Path engine, boolean live) throws Exception {
        ProcessHandle process = ProcessHandle.current();
        String started = process.info().startInstant().orElseThrow().toString();
        JSON.writeValue(session.resolve("lease.json").toFile(), Map.of("pid", process.pid(), "started", live ? started : "1970-01-01T00:00:00Z", "build", build.toString(), "engine", engine.toString()));
    }

    // PID plus start time protects the old running build; dead/reused PID leases release it without killing processes.
    // Persisted compiler leases survive Manager restart and malformed leases conservatively defer deletion.
    private static void recoveryLeaseCases(Path manifest, Path work) throws Exception {
        try (ControlledManager manager = new ControlledManager(manifest, work.resolve("process-lease-state"))) {
            manager.settled();
            Path inFlight = write(manager.directory.resolve("builds").resolve(UUID.randomUUID().toString()).resolve("project/compiling"));
            Path job = inFlight.getParent().getParent(), buildLease = job.resolve("build-lease.json");
            RemoteManager.writeProcessLease(buildLease, Set.of(ProcessHandle.current()));
            Path expiredCache = write(manager.directory.resolve("native-cache").resolve("c".repeat(64) + ".zip"));
            Path unrelated = write(manager.directory.resolve("builds").resolve(UUID.randomUUID().toString()).resolve("project/abandoned"));
            manager.collect();
            check("live persisted compiler lease preserves its build and native cache", Files.exists(inFlight) && Files.exists(expiredCache));
            check("live compiler lease does not pin unrelated obsolete builds", !Files.exists(unrelated));
            RemoteManager.writeProcessLease(buildLease, Set.of()); manager.collect();
            check("settled persisted compiler lease releases build and expired native cache", !Files.exists(job) && !Files.exists(expiredCache));

            Path uncertain = write(manager.directory.resolve("builds").resolve(UUID.randomUUID().toString()).resolve("project/unknown-owner"));
            Path badSession = manager.directory.resolve("sessions").resolve(UUID.randomUUID() + "-" + UUID.randomUUID());
            write(badSession.resolve("lease.json"), "{truncated");
            try { manager.collect(); throw new AssertionError("invalid lease accepted for deletion"); }
            catch (ExecutionException expected) {
                check("malformed lease explicitly reports uncertain ownership", expected.getCause() instanceof IOException);
            }
            check("malformed lease preserves both potentially owned build and session", Files.exists(uncertain) && Files.exists(badSession));
            writeLease(badSession, uncertain.getParent(), work.resolve("unused-engine"), false); manager.collect();
            check("repairing a stale lease permits deferred reclamation", !Files.exists(uncertain) && !Files.exists(badSession));
        }
    }

    // A restart reclaims abandoned build/session directories while preserving the persisted ready version.
    private static void restartCase(Path manifest, Path work) throws Exception {
        Path state = work.resolve("restart-state"), current; String id;
        try (ControlledManager manager = new ControlledManager(manifest, state)) {
            var p = manager.add(source(work, "restart-source")); manager.prepare(p, false, false); manager.settled();
            id = p.id; current = p.build;
        }
        Path orphan = write(state.resolve("builds").resolve(UUID.randomUUID().toString()).resolve("project/unfinished"));
        Path session = write(state.resolve("sessions").resolve(id + "-" + UUID.randomUUID()).resolve("runtime.shared"));
        Path notes = write(state.resolve("builds/user-notes/keep.txt"), "not an owned snapshot");
        try (ControlledManager manager = new ControlledManager(manifest, state)) {
            manager.settled(); var p = manager.project(id);
            check("restart preserves persisted ready build", current.equals(p.build) && p.state.equals("ready") && Files.exists(current));
            check("restart reclaims orphan build and stale session", !Files.exists(orphan) && !Files.exists(session));
            check("restart cleanup leaves unknown storage entries alone", Files.exists(notes));
            manager.prepare(p, true, false); manager.settled();
            check("restart launches cached version without compiling", manager.compilations == 0 && manager.launches == 1);
        }
    }


    @FunctionalInterface private interface Condition { boolean ready() throws Exception; }
    private static void awaitFileEvent(Path directory, Condition condition) throws Exception {
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!condition.ready()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new AssertionError("filesystem completion event timed out: " + directory);
                WatchKey key = watcher.poll(remaining, TimeUnit.NANOSECONDS);
                if (key == null) throw new AssertionError("filesystem completion event timed out: " + directory);
                key.pollEvents(); if (!key.reset()) throw new AssertionError("watched directory removed: " + directory);
            }
        }
    }

    // Separate instances cannot share a data directory or collect one another's active snapshots.
    private static void directoryLockCase(Path manifest, Path work) throws Exception {
        Path state = work.resolve("exclusive-state"); String host;
        try (ControlledManager first = new ControlledManager(manifest, state)) {
            first.settled(); host = first.hostId;
            try (ControlledManager unexpected = new ControlledManager(manifest, state)) {
                throw new AssertionError("second Manager acquired the same data directory");
            } catch (IOException | java.nio.channels.OverlappingFileLockException expected) {
                check("second Manager cannot open an actively owned data directory", first.dataLock.isValid());
            }
            check("rejected second instance leaves first Manager operational", first.status().get("hostId").equals(host));
        }
        try (ControlledManager reopened = new ControlledManager(manifest, state)) {
            reopened.settled();
            check("completed close releases data directory for reopening", reopened.dataLock.isValid() && reopened.hostId.equals(host));
        }
    }

    // Production automaticBuild/stop owns an actual Python parent and child; no Bob, CMake or SDK is invoked.
    private static void compilerProcessCase(Path manifest, Path work) throws Exception {
        Path signals = Files.createDirectories(work.resolve("compiler-signals"));
        write(manifest.getParent().resolve("prepare.py"), """
                import json, os, subprocess, sys
                from pathlib import Path
                manifest, project = Path(sys.argv[1]), Path(sys.argv[2])
                signals = manifest.parent / 'compiler-signals'
                child = subprocess.Popen([sys.executable, '-u', '-c',
                    "import time; print('CONTROLLED_CHILD_READY', flush=True); time.sleep(300)"])
                print('CONTROLLED_PARENT_READY', flush=True)
                def publish(path, value):
                    partial = path.with_suffix('.part')
                    partial.write_text(json.dumps(value))
                    os.replace(partial, path)
                publish(project / '.internal/remote-manager/progress.json', {'stage': 'controlled compiler waiting'})
                publish(signals / 'ready.json', {'parent': os.getpid(), 'child': child.pid, 'project': str(project)})
                child.wait()
                """);
        ProcessHandle parent = null, child = null;
        try (ControlledManager manager = new ControlledManager(manifest, work.resolve("process-state"))) {
            var p = manager.add(source(work, "process-source")); manager.processCompiler = true;
            manager.prepare(p, true, false);
            Path ready = signals.resolve("ready.json");
            awaitFileEvent(signals, () -> Files.isRegularFile(ready));
            Map<?,?> observed = JSON.readValue(ready.toFile(), Map.class);
            parent = ProcessHandle.of(((Number) observed.get("parent")).longValue()).orElseThrow();
            child = ProcessHandle.of(((Number) observed.get("child")).longValue()).orElseThrow();
            long parentPid = parent.pid(), childPid = child.pid();
            Path job = RemoteManager.projectState(Path.of(observed.get("project").toString())), lease = job.resolve("build-lease.json");
            awaitFileEvent(job, () -> {
                if (!Files.isRegularFile(lease)) return false;
                Map<?,?> record = JSON.readValue(lease.toFile(), Map.class);
                return ((List<?>) record.get("processes")).stream().map(entry -> ((Number) ((Map<?,?>) entry).get("pid")).longValue()).toList().containsAll(List.of(parentPid, childPid));
            });
            check("production compiler lease records its actual parent and child", parent.isAlive() && child.isAlive());
            rpc(manager, "cancel", p.id); manager.settled(); manager.collect();
            check("production cancel terminates compiler parent and descendant", !parent.isAlive() && !child.isAlive());
            check("compiler output drain finishes before storage reclamation", manager.buildOutputs.isEmpty() && manager.compilerProcesses.isEmpty());
            check("production cancel preserves project state but releases process lease", Files.isDirectory(job) && !Files.exists(lease) && directories(manager.directory.resolve("builds")) == 0);
            check("cancel retains bounded compiler diagnostics", p.buildLog != null && Files.size(p.buildLog) <= 12_000 && Files.readString(p.buildLog).contains("CONTROLLED_PARENT_READY"));
            check("cancelled process compiler never launches a game", manager.launches == 0 && !p.startAfterBuild);
            check("process cancellation preserves original project", Files.isRegularFile(p.root.resolve("game.project")) && Files.size(p.root.resolve("assets/payload.bin")) == 128 * 1024);
            processEvidence.add(Map.of("parentPid", parentPid, "childPid", childPid, "parentAliveAfterCancel", parent.isAlive(), "childAliveAfterCancel", child.isAlive(), "buildDirectoryRemoved", !Files.exists(job)));
        } finally {
            if (child != null && child.isAlive()) { child.destroyForcibly(); child.onExit().get(10, TimeUnit.SECONDS); }
            if (parent != null && parent.isAlive()) { parent.destroyForcibly(); parent.onExit().get(10, TimeUnit.SECONDS); }
        }
    }

    public static void main(String[] args) throws Exception {
        Path base = Path.of(args[0]).toAbsolutePath(), work = Path.of(args[1]).toAbsolutePath(); Files.createDirectories(work);
        Map<String,Object> runtime = JSON.readValue(base.toFile(), Map.class);
        runtime.remove("engineDirectory"); runtime.put("revision", "controlled-cache-lifecycle");
        for (String key : List.of("classes", "native", "jar", "engine")) runtime.put(key, base.getParent().resolve(runtime.get(key).toString()).toString());
        Path python = args.length > 2 ? Path.of(args[2]).toRealPath() : Path.of("/usr/bin/python3");
        if (!Files.isRegularFile(python)) throw new IllegalArgumentException("Pass the local Python executable as argument 3");
        runtime.put("nativeBuild", Map.of("python", python.toString()));
        runtime.put("java", Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString());
        Path manifest = work.resolve("manifest.json"); JSON.writeValue(manifest.toFile(), runtime);
        recoveryLeaseCases(manifest, work); restartCase(manifest, work);
        directoryLockCase(manifest, work); compilerProcessCase(manifest, work);
        JSON.writerWithDefaultPrettyPrinter().writeValue(work.resolve("report.json").toFile(), Map.of("passed", checks,
                "scope", "Legacy Manager cache reclamation, shared-project restart and cleanup; controlled compiler/launch, actual Python parent/child automaticBuild cancellation, Java PID leases, no SDK build or GPU session",
                "compilerProcesses", processEvidence,
                "bytesPerSourceFixture", 128 * 1024));
        System.out.println("RemoteCacheLifecycleTest: " + checks.size() + " checks passed; " + work);
    }
}
