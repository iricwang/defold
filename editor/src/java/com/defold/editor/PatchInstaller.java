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

import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Builds and verifies a replacement app without modifying the running app. */
public final class PatchInstaller {
    private static Path safePath(Path root, String name) throws IOException {
        Path relative = Path.of(name);
        if (!name.startsWith("Contents/") || name.contains("\\") || relative.isAbsolute()
                || !relative.normalize().equals(relative)) {
            throw new IOException("Unsafe patch path: " + name);
        }
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) throw new IOException("Patch path escapes app");
        for (Path parent = path.getParent(); parent != null && !parent.equals(root); parent = parent.getParent()) {
            if (Files.isSymbolicLink(parent)) throw new IOException("Symlink in patch path: " + name);
        }
        return path;
    }

    public static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        if (Files.isSymbolicLink(path)) {
            digest.update(Files.readSymbolicLink(path).toString().getBytes(StandardCharsets.UTF_8));
        } else {
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Map<String, JsonNode> inventory(JsonNode entries) throws IOException {
        if (entries == null || !entries.isArray() || entries.size() > 20000) throw new IOException("Invalid inventory");
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode entry : entries) {
            String path = entry.path("path").asText();
            String kind = entry.path("kind").asText();
            if (result.put(path, entry) != null || !entry.path("sha256").asText().matches("[0-9a-f]{64}")
                    || !(kind.equals("file") || kind.equals("link"))
                    || !entry.path("mode").isInt() || entry.path("mode").asInt() < 0
                    || entry.path("mode").asInt() > 0777) throw new IOException("Invalid inventory entry");
        }
        if (!result.containsKey("Contents/Resources/config") || !result.containsKey("Contents/MacOS/Defold")) {
            throw new IOException("Incomplete app inventory");
        }
        return result;
    }

    private static void verify(Path root, Map<String, JsonNode> entries) throws Exception {
        for (Map.Entry<String, JsonNode> entry : entries.entrySet()) {
            Path path = safePath(root, entry.getKey());
            JsonNode item = entry.getValue();
            boolean link = item.path("kind").asText().equals("link");
            // Upstream ZIP bundles dereference JDK license symlinks, while the
            // DMG preserves them. Accept equivalent in-app targets, never external ones.
            Path content = link ? path : path.toRealPath();
            if ((link && !Files.isSymbolicLink(path)) || (!link && !Files.isRegularFile(path))
                    || !content.startsWith(root)
                    || (Files.isSymbolicLink(path) && !path.toRealPath().startsWith(root))
                    || !sha256(content).equals(item.path("sha256").asText())) {
                throw new IOException("Installed files do not match patch: " + entry.getKey());
            }
        }
    }

    private static void copyApp(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path to = target.resolve(source.relativize(dir));
                if (!dir.equals(source)) Files.copy(dir, to, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES,
                           LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static File prepare(File archive, File app, long baseRevision, long revision) throws Exception {
        Path root = app.toPath().toRealPath();
        if (!Files.isWritable(root.getParent())) throw new IOException("Application folder is not writable");
        Path staged = null;
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry metadata = zip.getEntry("patch.json");
            if (metadata == null) throw new IOException("Missing patch inventory");
            JsonNode plan;
            try (InputStream input = zip.getInputStream(metadata)) {
                byte[] bytes = input.readNBytes(4 * 1024 * 1024 + 1);
                if (bytes.length > 4 * 1024 * 1024) throw new IOException("Patch inventory too large");
                plan = new ObjectMapper().readTree(bytes);
            }
            if (plan.path("schema").asInt() != 1 || plan.path("base_revision").asLong() != baseRevision
                    || plan.path("revision").asLong() != revision || revision <= baseRevision) {
                throw new IOException("Patch version mismatch");
            }
            Map<String, JsonNode> before = inventory(plan.get("base"));
            Map<String, JsonNode> after = inventory(plan.get("target"));
            verify(root, before);
            staged = Files.createTempDirectory(root.getParent(), ".defold-patch-");
            copyApp(root, staged);
            for (String path : before.keySet()) {
                if (!after.containsKey(path)) Files.delete(safePath(staged, path));
            }
            long written = 0;
            for (Map.Entry<String, JsonNode> item : after.entrySet()) {
                Path target = safePath(staged, item.getKey());
                ZipEntry payload = zip.getEntry("files/" + item.getKey());
                if (payload == null) continue;
                Files.createDirectories(target.getParent());
                Files.deleteIfExists(target);
                try (InputStream input = zip.getInputStream(payload)) {
                    if (item.getValue().path("kind").asText().equals("link")) {
                        byte[] bytes = input.readNBytes(4097);
                        if (bytes.length > 4096) throw new IOException("Symlink too long");
                        Path link = Path.of(new String(bytes, StandardCharsets.UTF_8));
                        if (link.isAbsolute() || !target.getParent().resolve(link).normalize().startsWith(staged)) {
                            throw new IOException("Symlink escapes app");
                        }
                        Files.createSymbolicLink(target, link);
                    } else {
                        try (OutputStream output = Files.newOutputStream(target)) {
                            byte[] buffer = new byte[65536];
                            for (int n; (n = input.read(buffer)) != -1;) {
                                written += n;
                                if (written > 2L * 1024 * 1024 * 1024) throw new IOException("Patch payload too large");
                                output.write(buffer, 0, n);
                            }
                        }
                        int mode = item.getValue().path("mode").asInt();
                        Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
                        PosixFilePermission[] values = PosixFilePermission.values();
                        for (int i = 0; i < 9; ++i) if ((mode & (1 << (8 - i))) != 0) permissions.add(values[i]);
                        Files.setPosixFilePermissions(target, permissions);
                    }
                }
            }
            verify(staged, after);
            Files.setPosixFilePermissions(staged, Files.getPosixFilePermissions(root));
            return staged.toFile();
        } catch (Exception error) {
            if (staged != null) {
                try { deleteTree(staged); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            }
            throw error;
        }
    }
}
