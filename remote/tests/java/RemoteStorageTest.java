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
import java.io.InterruptedIOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;

/** Real disk reclamation tests; no Manager, compiler, GPU, SDK or user project is started. */
public final class RemoteStorageTest {
    private static final List<String> checks = new ArrayList<>();
    private static final String FIRST = "11111111-1111-1111-1111-111111111111";
    private static final String SECOND = "22222222-2222-2222-2222-222222222222";
    private static final String THIRD = "33333333-3333-3333-3333-333333333333";
    private static final String SHA = "a".repeat(64);
    @FunctionalInterface private interface Operation { void run() throws Exception; }

    private static void check(String name, boolean passed) {
        if (!passed) throw new AssertionError(name);
        checks.add(name);
    }
    private static Path write(Path path, int size, long modified) throws IOException {
        Files.createDirectories(path.getParent()); Files.write(path, new byte[size]);
        Files.setLastModifiedTime(path, FileTime.fromMillis(modified)); return path;
    }
    private static Path write(Path path) throws IOException { return write(path, 8, System.currentTimeMillis()); }
    private static boolean exists(Path path) { return Files.exists(path, LinkOption.NOFOLLOW_LINKS); }
    private static void rejected(String name, Operation action) throws Exception {
        try { action.run(); throw new AssertionError(name + ": operation accepted"); }
        catch (IOException expected) { check(name, true); }
    }

    // Referenced nested files retain their complete snapshot; only owned naming formats are reclaimed.
    private static void directoryCases(Path work) throws Exception {
        Path root = work.resolve("builds"), keep = root.resolve(FIRST), remove = root.resolve(SECOND);
        Path reference = write(keep.resolve("project/build/default/game.projectc"));
        write(remove.resolve("project/large-asset.bin"));
        Path arbitrary = write(root.resolve("user-notes/important.txt"));
        Path prefixCollision = write(root.resolve(THIRD + "-old/project/data"));
        Path namedFile = write(root.resolve(THIRD));
        RemoteStorage.retainDirectories(root, Set.of(reference.getParent().resolve("unused/../game.projectc")));
        check("nested normalized reference retains entire snapshot", exists(reference));
        check("unreferenced UUID snapshot is removed recursively", !exists(remove));
        check("unrecognized directories survive", exists(arbitrary) && exists(prefixCollision));
        check("UUID-named regular files are not treated as snapshot directories", exists(namedFile));
        RemoteStorage.retainDirectories(root, Set.of(work.resolve(FIRST)));
        check("references outside the parent do not retain an unrelated matching name", !exists(keep));

        Path sessions = work.resolve("sessions");
        Path active = write(sessions.resolve(FIRST + "-" + SECOND).resolve("runtime.shared"));
        Path stale = write(sessions.resolve(SECOND + "-" + THIRD).resolve("worker.log"));
        RemoteStorage.retainDirectories(sessions, Set.of(active));
        check("session UUID pair is retained when referenced", exists(active));
        check("unreferenced session UUID pair is removed", !exists(stale));

        Path engines = work.resolve("engines");
        Path engine = write(engines.resolve("0123456789abcdef-arm64-macos-" + SHA).resolve("editor.jar"));
        Path oldMac = write(engines.resolve("fedcba9876543210-x86_64-macos-" + SHA).resolve("dmengine"));
        Path oldWin = write(engines.resolve("fedcba9876543210-x86_64-win32-" + SHA).resolve("dmengine.exe"));
        Path malformed = write(engines.resolve("0123456789abcdef-linux-" + SHA).resolve("do-not-delete"));
        RemoteStorage.retainDirectories(engines, Set.of(engine));
        check("referenced engine package is retained", exists(engine));
        check("recognized old macOS and Windows engine packages are removed", !exists(oldMac) && !exists(oldWin));
        check("unrecognized engine package formats survive", exists(malformed));
    }

    // Native cache limits evict actual oldest bytes and stale partial writes without touching arbitrary files.
    private static void cacheCases(Path work) throws Exception {
        Path cache = work.resolve("native-cache"); long now = System.currentTimeMillis();
        Path expired = write(cache.resolve("0".repeat(64) + ".zip"), 100, now - 200_000);
        Path oldest = write(cache.resolve("1".repeat(64) + ".zip"), 7, now - 30_000);
        Path middle = write(cache.resolve("2".repeat(64) + ".zip"), 5, now - 20_000);
        Path newest = write(cache.resolve("3".repeat(64) + ".zip"), 4, now - 10_000);
        Path partial = write(cache.resolve("4".repeat(64) + "." + "b".repeat(32) + ".part"), 10, now - 200_000);
        Path arbitrary = write(cache.resolve("notes.zip"), 100, now - 200_000);
        Path wrongPartial = write(cache.resolve(SHA + ".zip.part"), 100, now - 200_000);
        Path directory = write(cache.resolve("5".repeat(64) + ".zip/inside"), 100, now - 200_000);
        RemoteStorage.pruneFiles(cache, 9, 100_000);
        check("expired native archive and actual builder partial format are removed", !exists(expired) && !exists(partial));
        check("oldest live archive is evicted first to meet exact byte limit", !exists(oldest) && exists(middle) && exists(newest));
        check("retained native file sizes equal the byte budget", Files.size(middle) + Files.size(newest) == 9);
        check("unrecognized archives and partial formats survive", exists(arbitrary) && exists(wrongPartial));
        check("archive-named directories are never traversed by file pruning", exists(directory));
        RemoteStorage.pruneFiles(cache, 3, 100_000);
        check("an individually oversized newest archive is removed after older entries", !exists(middle) && !exists(newest));
        Path future = write(cache.resolve("6".repeat(64) + ".zip"), 3, now + 100_000);
        Path freshPartial = write(cache.resolve("7".repeat(64) + "." + "c".repeat(32) + ".part"), 3, now - 1_000);
        RemoteStorage.pruneFiles(cache, 3, 100_000);
        check("fresh partials participate in size eviction and future timestamps are not expired", !exists(freshPartial) && exists(future));
        RemoteStorage.pruneFiles(cache, 0, Long.MAX_VALUE);
        check("zero byte budget removes all recognized nonempty archives", !exists(future));
        try { RemoteStorage.pruneFiles(cache, -1, 0); throw new AssertionError("negative size accepted"); }
        catch (IllegalArgumentException expected) { check("negative byte budget is rejected", true); }
        try { RemoteStorage.pruneFiles(cache, 1, -1); throw new AssertionError("negative age accepted"); }
        catch (IllegalArgumentException expected) { check("negative retention age is rejected", true); }
    }

    // Link traversal and direct-child guards prevent cleanup from deleting project or neighboring data.
    private static void boundaryCases(Path work) throws Exception {
        Path outside = write(work.resolve("outside/keep.txt"));
        Path root = Files.createDirectories(work.resolve("boundary"));
        Path link = root.resolve(FIRST); Files.createSymbolicLink(link, outside.getParent());
        RemoteStorage.retainDirectories(root, Set.of());
        check("recognized direct child symlink is unlinked", !exists(link));
        check("symlink target outside cache remains intact", exists(outside));
        Path nested = root.resolve(SECOND); Files.createDirectories(nested);
        Files.createSymbolicLink(nested.resolve("external"), outside.getParent());
        RemoteStorage.deleteTree(root, nested);
        check("recursive deletion does not traverse nested directory symlinks", !exists(nested) && exists(outside));
        Path dangling = root.resolve(THIRD); Files.createSymbolicLink(dangling, work.resolve("missing-target"));
        RemoteStorage.deleteTree(root, dangling);
        check("dangling child symlink can be deleted without a target", !exists(dangling));
        Path parentLink = work.resolve("parent-link"); Files.createSymbolicLink(parentLink, root);
        Path protectedChild = write(root.resolve(FIRST + "/child"));
        rejected("directory cleanup rejects a symlink parent", () -> RemoteStorage.retainDirectories(parentLink, Set.of()));
        rejected("file pruning rejects a symlink parent", () -> RemoteStorage.pruneFiles(parentLink, 0, 0));
        rejected("direct deletion rejects a symlink parent", () -> RemoteStorage.deleteTree(parentLink, parentLink.resolve(FIRST)));
        check("rejected symlink parent never modifies its target", exists(protectedChild));
        rejected("parent itself cannot be deleted", () -> RemoteStorage.deleteTree(root, root));
        rejected("nested child is rejected by direct-child boundary", () -> RemoteStorage.deleteTree(root, protectedChild));
        rejected("sibling subtree is rejected", () -> RemoteStorage.deleteTree(root, outside.getParent()));
        rejected("normalized traversal cannot escape parent", () -> RemoteStorage.deleteTree(root, root.resolve("../outside")));
        check("all rejected boundaries preserve external data", exists(protectedChild) && exists(outside));
        Path ordinary = write(root.resolve("ordinary-file")); RemoteStorage.deleteTree(root, ordinary);
        check("explicit deletion accepts a direct ordinary file", !exists(ordinary));
        Path cacheLink = root.resolve(SHA + ".zip"); Files.createSymbolicLink(cacheLink, outside);
        RemoteStorage.pruneFiles(root, 0, Long.MAX_VALUE);
        check("native cache symlink removal never follows file target", !exists(cacheLink) && exists(outside));
        Path missing = work.resolve("missing-parent");
        RemoteStorage.retainDirectories(missing, Set.of()); RemoteStorage.pruneFiles(missing, 0, 0);
        RemoteStorage.deleteTree(missing, missing.resolve(FIRST));
        check("missing parent is an idempotent no-op", !exists(missing));
        RemoteStorage.deleteTree(root, root.resolve("missing-child"));
        check("missing child is an idempotent no-op", exists(root));
        rejected("ordinary file cannot be used as cache parent", () -> RemoteStorage.pruneFiles(outside, 0, 0));
    }

    // An interrupted cleanup keeps its interruption signal and stops before touching pending files.
    private static void interruptionCase(Path work) throws Exception {
        Path parent = work.resolve("interrupted"), child = write(parent.resolve(FIRST + "/payload"));
        Thread.currentThread().interrupt();
        try {
            RemoteStorage.deleteTree(parent, child.getParent()); throw new AssertionError("interrupted cleanup continued");
        } catch (InterruptedIOException expected) {
            check("interrupted cleanup preserves pending data and interrupt status", exists(child) && Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        RemoteStorage.deleteTree(parent, child.getParent());
        check("cleanup can resume after caller handles interruption", !exists(child));
    }

    public static void main(String[] args) throws Exception {
        Path work = args.length == 0 ? Files.createTempDirectory("remote-storage-test-") : Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(work);
        directoryCases(work); cacheCases(work); boundaryCases(work); interruptionCase(work);
        Files.write(work.resolve("checks.txt"), checks);
        System.out.println("RemoteStorageTest: " + checks.size() + " checks passed; " + work);
    }
}
