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

/** Real snapshot I/O and deterministic prepare scheduling; builder/launch are controlled fakes. */
public final class RemoteSnapshotTest {
    private static final List<String> checks = new ArrayList<>();
    private static final AtomicLong clock = new AtomicLong(1700000000000L);
    private static final RemoteManager.SnapshotObserver NONE = () -> { };
    private static void check(String name, boolean value) {
        if (!value) throw new AssertionError(name);
        checks.add(name);
    }
    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        Files.setLastModifiedTime(path, FileTime.fromMillis(clock.incrementAndGet()));
    }
    private static Path project(Path parent, String name) throws IOException {
        Path root = parent.resolve(name); Files.createDirectories(root);
        write(root.resolve("game.project"), "[project]\ntitle = Snapshot regression\n[display]\nwidth = 320\nheight = 240\n");
        write(root.resolve("a.lua"), "AAAA"); write(root.resolve("b.lua"), "BBBB");
        return root;
    }
    private static void copyCases(Path work) throws Exception {
        Path root = project(work, "copy-source"), frozen = work.resolve("copy-frozen");
        RemoteManager.SourceSnapshot snapshot = RemoteManager.copySnapshot(root, RemoteManager.resources(root), frozen,
                new RemoteManager.SnapshotObserver() {
                    public void check() { }
                    public void copied(Path source, Path target, int attempt) throws Exception {
                        if (source.getFileName().toString().equals("b.lua")) {
                            write(root.resolve("a.lua"), "A changed after capture");
                            write(root.resolve("new.lua"), "new membership after capture");
                        }
                    }
                });
        check("later edits to another file do not discard the frozen snapshot", Files.readString(frozen.resolve("a.lua")).equals("AAAA"));
        check("new files after the initial resource list belong to the next snapshot", !Files.exists(frozen.resolve("new.lua")));
        check("snapshot stamp identifies the captured version, not later source", !snapshot.sourceStamp().equals(RemoteManager.stamp(root, RemoteManager.resources(root))));
        java.security.MessageDigest fingerprint = java.security.MessageDigest.getInstance("SHA-256");
        for (Path target : RemoteManager.resources(frozen)) {
            fingerprint.update(frozen.relativize(target).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fingerprint.update(RemoteManager.hash(target).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        check("content fingerprint is computed from frozen targets rather than later source", snapshot.fingerprint().equals(HexFormat.of().formatHex(fingerprint.digest())));

        Path retrySource = project(work, "retry-source"); int[] copies = {0};
        RemoteManager.copySnapshot(retrySource, RemoteManager.resources(retrySource), work.resolve("retry-frozen"), new RemoteManager.SnapshotObserver() {
            public void check() { }
            public void copied(Path source, Path target, int attempt) throws Exception {
                if (source.getFileName().toString().equals("a.lua")) {
                    copies[0]++; if (attempt < 2) write(source, "version " + attempt);
                }
            }
        });
        check("only a changing file is retried, with a three-copy upper bound", copies[0] == 3);
        check("the stable final version is copied", Files.readString(work.resolve("retry-frozen/a.lua")).equals("version 1"));

        Path atomicSource = project(work, "atomic-source"); Object oldKey = Files.readAttributes(atomicSource.resolve("a.lua"), java.nio.file.attribute.BasicFileAttributes.class).fileKey(); int[] atomicCopies = {0};
        RemoteManager.copySnapshot(atomicSource, RemoteManager.resources(atomicSource), work.resolve("atomic-frozen"), new RemoteManager.SnapshotObserver() {
            public void check() { }
            public void copied(Path source, Path target, int attempt) throws Exception {
                if (!source.getFileName().toString().equals("a.lua")) return;
                atomicCopies[0]++;
                if (attempt == 0) {
                    FileTime time = Files.getLastModifiedTime(source); Path replacement = source.resolveSibling("replacement.part");
                    Files.writeString(replacement, "CCCC"); Files.setLastModifiedTime(replacement, time);
                    Files.move(replacement, source, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        });
        check(oldKey == null ? "content hash fallback detects same-size same-time replacement when file identity is unavailable"
                             : "atomic replacement with unchanged size and time is detected by file identity", atomicCopies[0] == 2);
        check("replacement content, not the removed inode content, is frozen", Files.readString(work.resolve("atomic-frozen/a.lua")).equals("CCCC"));

        Path unstable = project(work, "unstable-source"); int[] attempts = {0};
        try {
            RemoteManager.copySnapshot(unstable, RemoteManager.resources(unstable), work.resolve("unstable-frozen"), new RemoteManager.SnapshotObserver() {
                public void check() { }
                public void copied(Path source, Path target, int attempt) throws Exception {
                    if (source.getFileName().toString().equals("a.lua")) { attempts[0]++; write(source, "unstable " + attempt); }
                }
            });
            throw new AssertionError("unstable source accepted");
        } catch (RemoteManager.SourceChangedException expected) {
            check("a continuously changing source is classified as retryable after exactly three attempts", attempts[0] == 3);
        }

        Path deleted = project(work, "deleted-source"); List<Path> originalList = RemoteManager.resources(deleted); Files.delete(deleted.resolve("b.lua"));
        try {
            RemoteManager.copySnapshot(deleted, originalList, work.resolve("deleted-frozen"), NONE);
            throw new AssertionError("deleted source accepted in the original snapshot");
        } catch (RemoteManager.SourceChangedException expected) {
            check("a file removed after listing is a retryable source change", expected.getMessage().contains("b.lua"));
        }
        RemoteManager.SourceSnapshot afterDeletion = RemoteManager.copySnapshot(deleted, RemoteManager.resources(deleted), work.resolve("deleted-stable"), NONE);
        check("the next stable resource list completes after an atomic removal", afterDeletion.sourceStamp().equals(RemoteManager.stamp(deleted, RemoteManager.resources(deleted))));
    }

    private static final class ControlledManager extends RemoteManager {
        SnapshotObserver changes = NONE;
        int buildsObserved, launchesObserved;
        boolean compilationFails;
        ControlledManager(Path manifest, Path state) throws Exception { super(manifest, state, 18720); monitor.shutdownNow(); }
        @Override SnapshotObserver snapshotObserver(Project p, long generation) {
            return new SnapshotObserver() {
                public void check() throws Exception { ControlledManager.this.check(p, generation); changes.check(); }
                public void copied(Path source, Path target, int attempt) throws Exception { changes.copied(source, target, attempt); }
            };
        }
        @Override Path automaticBuild(Project p, Path destination, long generation) throws Exception {
            check(p, generation); buildsObserved++;
            if (compilationFails) throw new IOException("INTENTIONAL_SCRIPT_COMPILE_ERROR");
            Path compiled = destination.resolve("build/default/game.projectc"); Files.createDirectories(compiled.getParent());
            Files.writeString(compiled, "[display]\nwidth = 320\nheight = 240\n"); return path("engine");
        }
        @Override void launch(Project p, long generation) throws Exception { check(p, generation); launchesObserved++; }
        Project add(Path source) throws Exception {
            Map<?, ?> added = (Map<?, ?>)command(Map.of("method", "add", "params", Map.of("path", source.toString())));
            return project(added.get("id").toString());
        }
        void settled() throws Exception { builds.submit(() -> { }).get(20, TimeUnit.SECONDS); }
        void observeNow() throws Exception { var method = RemoteManager.class.getDeclaredMethod("observe"); method.setAccessible(true); method.invoke(this); }
    }
    private static void schedulingCases(Path manifest, Path work) throws Exception {
        try (ControlledManager manager = new ControlledManager(manifest, work.resolve("edit-state"))) {
            Path root = project(work, "edit-project"); RemoteManager.Project p = manager.add(root);
            manager.changes = new RemoteManager.SnapshotObserver() {
                public void check() { }
                public void copied(Path source, Path target, int attempt) throws Exception {
                    if (source.getFileName().toString().equals("b.lua")) write(root.resolve("a.lua"), "edit after capture");
                }
            };
            manager.prepare(p, true, false); manager.settled();
            check("a later edit does not lose the original launch request", manager.launchesObserved == 1);
            check("a built frozen version reports subsequent source edits as modified", p.modified && !p.builtStamp.equals(RemoteManager.stamp(root, RemoteManager.resources(root))));
            check("build receipt records the captured source stamp", Objects.equals(RemoteManager.JSON.readValue(p.build.getParent().resolve("receipt.json").toFile(), Map.class).get("sourceStamp"), p.builtStamp));
            manager.settings.put("prepare", false); manager.observeNow();
            check("a stopped frozen version becomes dirty when the later edit is observed", p.state.equals("dirty"));
        }
        try (ControlledManager manager = new ControlledManager(manifest, work.resolve("retry-state"))) {
            Path root = project(work, "retry-project"); RemoteManager.Project p = manager.add(root);
            manager.changes = new RemoteManager.SnapshotObserver() {
                public void check() { }
                public void copied(Path source, Path target, int attempt) throws Exception {
                    if (source.getFileName().toString().equals("a.lua")) write(source, "changing " + clock.incrementAndGet());
                }
            };
            manager.prepare(p, true, false); manager.settled();
            check("transient copy failure keeps the explicit start intent", p.startAfterBuild && !p.busy && p.errorKind.equals("source_changed"));
            check("transient copy failure waits without calling the compiler or launcher", manager.buildsObserved == 0 && manager.launchesObserved == 0 && p.state.equals("preparing"));
            manager.changes = NONE; manager.observeNow(); manager.settled();
            check("a stable retry reaches launch with the original intent", manager.launchesObserved == 1 && !p.startAfterBuild && p.error.isEmpty());
        }
        try (ControlledManager manager = new ControlledManager(manifest, work.resolve("cancel-state"))) {
            RemoteManager.Project p = manager.add(project(work, "cancel-project")); CountDownLatch copied = new CountDownLatch(1), resume = new CountDownLatch(1);
            manager.changes = new RemoteManager.SnapshotObserver() {
                public void check() { }
                public void copied(Path source, Path target, int attempt) throws Exception {
                    if (source.getFileName().toString().equals("a.lua")) { copied.countDown(); if (!resume.await(10, TimeUnit.SECONDS)) throw new AssertionError("cancel latch timeout"); }
                }
            };
            manager.prepare(p, true, false); if (!copied.await(10, TimeUnit.SECONDS)) throw new AssertionError("copy did not begin");
            manager.stop(p); resume.countDown(); manager.settled();
            check("stop during copying cancels the original generation and run intent", manager.buildsObserved == 0 && manager.launchesObserved == 0 && !p.startAfterBuild && !p.busy);
            manager.changes = NONE; manager.observeNow(); manager.settled();
            check("later observation never restores a cancelled launch request", manager.launchesObserved == 0 && !p.startAfterBuild);
            write(p.root.resolve("a.lua"), "edit after cancellation"); manager.observeNow(); manager.settled();
            check("automatic preparation after a later edit still does not restore cancelled run intent", manager.buildsObserved == 1 && manager.launchesObserved == 0 && !p.startAfterBuild);
        }
        try (ControlledManager manager = new ControlledManager(manifest, work.resolve("error-state"))) {
            Path root = project(work, "error-project"); RemoteManager.Project p = manager.add(root); manager.compilationFails = true;
            manager.prepare(p, true, false); manager.settled();
            check("real compiler failures are errors and clear the run intent", p.state.equals("error") && !p.startAfterBuild && p.error.contains("INTENTIONAL_SCRIPT_COMPILE_ERROR"));
            manager.observeNow(); manager.settled();
            check("unchanged failed source is not automatically rebuilt or launched", manager.buildsObserved == 1 && manager.launchesObserved == 0);
            manager.compilationFails = false; write(root.resolve("a.lua"), "fixed script"); manager.observeNow(); manager.settled();
            check("editing failed source may prepare a fix but does not silently launch it", manager.buildsObserved == 2 && manager.launchesObserved == 0 && p.state.equals("ready"));
            manager.prepare(p, true, false); manager.settled();
            check("explicit retry can launch the repaired cached build", manager.launchesObserved == 1);
        }
    }
    public static void main(String[] args) throws Exception {
        Path manifest = Path.of(args[0]).toAbsolutePath(), work = Path.of(args[1]).toAbsolutePath(); Files.createDirectories(work);
        copyCases(work); schedulingCases(manifest, work);
        RemoteManager.JSON.writerWithDefaultPrettyPrinter().writeValue(work.resolve("report.json").toFile(), Map.of("passed", checks,
                "scope", "Real file snapshot I/O, deterministic cancellation and prepare control flow; compiler/launch fakes, no user project or game process touched",
                "monitorBoundary", "The passive monitor checks metadata rather than hashing all content. Later edits preserving size, time and absent file identity remain a pre-existing watcher limitation."));
        System.out.println("RemoteSnapshotTest: " + checks.size() + " checks passed; " + work);
    }
}
