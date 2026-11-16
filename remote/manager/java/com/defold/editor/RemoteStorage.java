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
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;

/** Reclaims only recognized, unreferenced children of Manager-owned storage directories. */
final class RemoteStorage {
    private static final String UUID_NAME = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern DIRECTORY_NAME = Pattern.compile("(?:" + UUID_NAME + "(?:-" + UUID_NAME + ")?|[0-9a-f]{16}-(?:arm64-macos|x86_64-macos|x86_64-win32)-[0-9a-f]{64})");
    // local_extensions.py uses cached.with_suffix('.' + uuid.uuid4().hex + '.part').
    private static final Pattern CACHE_NAME = Pattern.compile("[0-9a-f]{64}(?:\\.zip|\\.[0-9a-f]{32}\\.part)");

    private RemoteStorage() { }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("缓存清理已取消");
    }

    private static boolean managedParent(Path parent) throws IOException {
        checkInterrupted();
        BasicFileAttributes attrs;
        try { attrs = Files.readAttributes(parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException missing) { return false; }
        if (attrs.isSymbolicLink() || !attrs.isDirectory()) throw new IOException("缓存根目录必须是实际目录：" + parent);
        return true;
    }

    private static IOException failure(IOException previous, IOException next) {
        if (previous == null) return next;
        previous.addSuppressed(next); return previous;
    }

    /** The caller must retain every build/session still used by an owned process or queued task. */
    static void retainDirectories(Path parent, Set<Path> referencedPaths) throws IOException {
        parent = parent.toAbsolutePath().normalize();
        if (!managedParent(parent)) return;
        Set<Path> retained = new HashSet<>();
        for (Path reference : referencedPaths) {
            Path normalized = reference.toAbsolutePath().normalize();
            if (normalized.startsWith(parent) && !normalized.equals(parent))
                retained.add(parent.resolve(parent.relativize(normalized).getName(0)));
        }
        IOException failures = null;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(parent)) {
            for (Path child : children) {
                checkInterrupted();
                if (!DIRECTORY_NAME.matcher(child.getFileName().toString()).matches() || retained.contains(child)) continue;
                try {
                    BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attrs.isDirectory() || attrs.isSymbolicLink()) deleteTree(parent, child);
                } catch (NoSuchFileException missing) { /* Another cleanup may already have removed this child. */ }
                catch (IOException error) { failures = failure(failures, error); }
            }
        }
        if (failures != null) throw failures;
    }

    private record CachedFile(Path path, long size, long modified) { }

    /** Keeps the newest native cache files within both limits; call only while no builder uses them. */
    static void pruneFiles(Path parent, long maxBytes, long maxAgeMillis) throws IOException {
        if (maxBytes < 0 || maxAgeMillis < 0) throw new IllegalArgumentException("缓存容量和保留时间不能为负数");
        parent = parent.toAbsolutePath().normalize();
        if (!managedParent(parent)) return;
        long now = System.currentTimeMillis();
        long oldestAllowed = now - maxAgeMillis;
        List<CachedFile> files = new ArrayList<>();
        IOException failures = null;
        long total = 0;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(parent)) {
            for (Path child : children) {
                checkInterrupted();
                if (!CACHE_NAME.matcher(child.getFileName().toString()).matches()) continue;
                try {
                    BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!attrs.isRegularFile() && !attrs.isSymbolicLink()) continue;
                    if (attrs.lastModifiedTime().toMillis() < oldestAllowed) deleteTree(parent, child);
                    else {
                        files.add(new CachedFile(child, attrs.size(), attrs.lastModifiedTime().toMillis()));
                        total = Math.addExact(total, attrs.size());
                    }
                } catch (NoSuchFileException missing) { /* Already removed. */ }
                catch (IOException error) { failures = failure(failures, error); }
            }
        }
        files.sort(Comparator.comparingLong(CachedFile::modified).thenComparing(file -> file.path().getFileName().toString()));
        for (CachedFile file : files) {
            checkInterrupted();
            if (total <= maxBytes) break;
            try { deleteTree(parent, file.path()); total -= file.size(); }
            catch (IOException error) { failures = failure(failures, error); }
        }
        if (failures != null) throw failures;
    }

    /** Deletes exactly one direct child; symbolic links are unlinked without traversing their target. */
    static void deleteTree(Path parent, Path child) throws IOException {
        parent = parent.toAbsolutePath().normalize(); child = child.toAbsolutePath().normalize();
        if (!parent.equals(child.getParent())) throw new IOException("只允许清理缓存根目录的直接子项：" + child);
        if (!managedParent(parent)) return;
        try {
            Files.walkFileTree(child, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                    checkInterrupted(); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    checkInterrupted(); Files.deleteIfExists(file); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                    checkInterrupted();
                    if (error instanceof NoSuchFileException) return FileVisitResult.CONTINUE;
                    throw error;
                }
                @Override public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                    checkInterrupted();
                    if (error != null) throw error;
                    Files.deleteIfExists(directory); return FileVisitResult.CONTINUE;
                }
            });
        } catch (NoSuchFileException missing) { /* Already removed. */ }
    }
}
