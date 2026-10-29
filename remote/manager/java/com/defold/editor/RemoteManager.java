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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/** Independent local build/session service shared by the desktop shell and LAN gateway. */
public class RemoteManager implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    final Path directory, runtimeRoot;
    final Map<String, Object> packagedRuntime;
    final Path packagedManifest;
    volatile Map<String, Object> runtime;
    volatile Path gameManifest;
    volatile String engineDirectory = "", engineError = "";
    final Map<String, Project> projects = new LinkedHashMap<>();
    final Map<String, Map<String, Object>> devices = new ConcurrentHashMap<>();
    final Map<String, Object> settings = new ConcurrentHashMap<>(Map.of("prepare", true, "maxSessions", 2));
    final ExecutorService builds = Executors.newSingleThreadExecutor();
    final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();
    final String hostId;
    final String computer;
    final RemoteGateway gateway;
    volatile boolean closed;

    static final class Project {
        final String id;
        final Path root;
        String name, mode = "source", state = "new", error = "", errorKind = "", failedStage = "", stamp = "", builtStamp = "", stage = "", builtAt = "";
        boolean shared = true, busy, startAfterBuild, modified;
        volatile long generation;
        Process compiler;
        Path build, engine, buildLog;
        Worker worker;
        String controller = "", controlToken = "";
        long controlUntil;
        final Set<HttpURLConnection> connections = ConcurrentHashMap.newKeySet();
        final Set<com.sun.net.httpserver.HttpExchange> clients = ConcurrentHashMap.newKeySet();
        Project(String id, Path root, String name) { this.id = id; this.root = root; this.name = name; }
        synchronized Map<String, Object> saved() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id); out.put("root", root.toString()); out.put("name", name); out.put("shared", shared); out.put("mode", mode);
            out.put("build", build == null ? "" : build.toString()); out.put("engine", engine == null ? "" : engine.toString());
            out.put("builtStamp", builtStamp); out.put("builtAt", builtAt); return out;
        }
    }

    static final class Worker implements AutoCloseable {
        final Process process;
        final String sessionId = UUID.randomUUID().toString();
        final BufferedWriter input;
        final BufferedReader output;
        volatile Map<String, Object> status = Map.of();
        Worker(RemoteManager manager, Path session) throws IOException {
            Files.createDirectories(session);
            List<String> command = manager.javaCommand("com.defold.editor.RemoteRuntime");
            command.add(session.toString()); command.add(manager.path("native").toString());
            ProcessBuilder builder = new ProcessBuilder(command).redirectError(session.resolve("worker.log").toFile());
            process = builder.start();
            input = process.outputWriter(StandardCharsets.UTF_8); output = process.inputReader(StandardCharsets.UTF_8);
        }
        synchronized Map<String, Object> call(Map<String, Object> request) throws Exception {
            if (!process.isAlive()) throw new IOException("后台游戏进程已退出");
            input.write(JSON.writeValueAsString(request)); input.newLine(); input.flush();
            // A wedged worker must not hold the manager forever. Killing only this owned child closes its game job.
            ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor();
            var expiry = deadline.schedule(process::destroyForcibly, 35, TimeUnit.SECONDS);
            try {
                String line = output.readLine(); if (line == null) throw new IOException("后台进程失去响应，请查看日志");
                Map<String, Object> reply = JSON.readValue(line, Map.class);
                if (!Boolean.TRUE.equals(reply.get("ok"))) throw new IOException(String.valueOf(reply.get("error")));
                Map<String, Object> result = (Map<String, Object>)reply.get("result");
                if (result.containsKey("endpoint")) status = result;
                return result;
            } finally { expiry.cancel(false); deadline.shutdownNow(); }
        }
        HttpURLConnection connection(String path, String method) throws IOException {
            HttpURLConnection c = (HttpURLConnection)new URL(status.get("endpoint") + path).openConnection();
            c.setConnectTimeout(2000); c.setReadTimeout(5000); c.setInstanceFollowRedirects(false);
            c.setRequestMethod(method); c.setRequestProperty("Authorization", "Bearer " + status.get("token")); return c;
        }
        void release() {
            if (!process.isAlive() || status.isEmpty()) return;
            try { HttpURLConnection c = connection("/release", "POST"); try { c.getInputStream().close(); } finally { c.disconnect(); } }
            catch (IOException ignored) { }
        }
        void lease(String token) throws IOException {
            HttpURLConnection c = connection("/lease", "POST");
            try { c.setDoOutput(true); c.getOutputStream().write(token.getBytes(StandardCharsets.UTF_8)); c.getOutputStream().close(); c.getInputStream().close(); }
            finally { c.disconnect(); }
        }
        @Override public void close() {
            try { input.close(); } catch (IOException ignored) { }
            try { if (!process.waitFor(12, TimeUnit.SECONDS)) process.destroyForcibly(); }
            catch (InterruptedException e) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
        }
    }

    RemoteManager(Path manifest, Path directory, int port) throws Exception {
        RemoteAddressResolver.enableForManager();
        this.runtimeRoot = manifest.toAbsolutePath().getParent();
        this.directory = directory.toAbsolutePath().normalize();
        runtime = JSON.readValue(manifest.toFile(), Map.class);
        packagedRuntime = new LinkedHashMap<>(runtime);
        packagedManifest = manifest.toAbsolutePath(); gameManifest = packagedManifest;
        String expected = String.valueOf(runtime.get("platform"));
        String system = System.getProperty("os.name"), arch = System.getProperty("os.arch");
        if (!system.startsWith("Windows") && !system.startsWith("Mac")) throw new IOException("仅支持 Windows 和 macOS");
        String actual = system.startsWith("Windows") ? "x86_64-win32" : (arch.equals("aarch64") ? "arm64-macos" : "x86_64-macos");
        if (!expected.equals(actual)) throw new IOException("运行组件平台不匹配：" + expected + " / " + actual);
        Files.createDirectories(this.directory);
        if (!system.startsWith("Windows")) Files.setPosixFilePermissions(this.directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        Path registry = this.directory.resolve("registry.json");
        Map<String, Object> saved = Files.exists(registry) ? JSON.readValue(registry.toFile(), Map.class) : Map.of();
        hostId = String.valueOf(saved.getOrDefault("hostId", UUID.randomUUID().toString()));
        String host;
        try { host = InetAddress.getLocalHost().getHostName(); } catch (Exception e) { host = "Defold Computer"; }
        computer = host;
        if (saved.get("settings") instanceof Map<?, ?> values) settings.putAll((Map<String, Object>)values);
        engineDirectory = Objects.toString(settings.getOrDefault("engineDirectory",runtime.getOrDefault("engineDirectory","")),"");
        if (!engineDirectory.isBlank()) {
            try { useEngine(RemoteEngineInstallation.resolve(Path.of(engineDirectory),this.directory.resolve("engines"),packagedManifest,packagedRuntime)); }
            catch (Exception unavailable) { engineError = "引擎目录不可用：" + unavailable.getMessage(); }
        }
        if (saved.get("devices") instanceof List<?> entries) for (Object item : entries) {
            Map<String, Object> device = (Map<String, Object>)item; devices.put(String.valueOf(device.get("id")), device);
        }
        if (saved.get("projects") instanceof List<?> entries) for (Object item : entries) {
            Map<String, Object> entry = (Map<String, Object>)item;
            Project p = new Project(str(entry,"id"), Path.of(str(entry,"root")), str(entry,"name"));
            p.shared = !Boolean.FALSE.equals(entry.get("shared")); p.mode = String.valueOf(entry.getOrDefault("mode", "source"));
            p.builtStamp = str(entry,"builtStamp"); p.builtAt = str(entry,"builtAt");
            if (!str(entry,"build").isEmpty()) {
                Path build = Path.of(str(entry,"build")).toAbsolutePath().normalize();
                if (build.startsWith(this.directory.resolve("builds")) && Files.isRegularFile(build.resolve("build/default/game.projectc"))) {
                    Path receipt = build.getParent().resolve("receipt.json");
                    if (Files.isRegularFile(receipt) && (p.mode.equals("import") || compatibleBuild(JSON.readValue(receipt.toFile(), Map.class)))) {
                        p.build = build; p.engine = Path.of(str(entry,"engine"));
                        try { verifyBuild(p); p.state = "ready"; }
                        catch (EngineArtifactException damaged) { p.builtStamp = ""; p.state = "error"; p.error = damaged.getMessage(); p.errorKind = "environment"; p.failedStage = "校验本地构建产物"; }
                    }
                }
            }
            projects.put(p.id, p);
        }
        save(); gateway = new RemoteGateway(this, port);
        monitor.scheduleWithFixedDelay(this::observe, 3, 3, TimeUnit.SECONDS);
    }

    Path path(String key) { Path value = Path.of(String.valueOf(runtime.get(key))); return value.isAbsolute() ? value : runtimeRoot.resolve(value).normalize(); }
    private void useEngine(RemoteEngineInstallation.Selection selection) {
        runtime = selection.runtime(); gameManifest = selection.manifest();
        engineDirectory = selection.directory().toString(); engineError = "";
        settings.put("engineDirectory",engineDirectory);
    }
    synchronized void selectEngine(Path app) throws Exception {
        // Validation/copy finishes before any existing game is interrupted.
        var selection = RemoteEngineInstallation.resolve(app,directory.resolve("engines"),packagedManifest,packagedRuntime);
        boolean changed = !buildCompatibility(runtime).equals(buildCompatibility(selection.runtime())) || !engineError.isEmpty();
        List<Project> restart = new ArrayList<>();
        if (changed) for (Project p : all()) {
            synchronized(p) { if (p.worker != null || p.startAfterBuild) restart.add(p); }
            stop(p);
        }
        useEngine(selection);
        if (changed) for (Project p : all()) synchronized(p) {
            // Keep pinned imports so incompatibility is reported, never substituted.
            if (p.mode.equals("source")) { p.build=null; p.engine=null; p.builtStamp=""; p.state="new"; }
        }
        save();
        for (Project p : restart) prepare(p,true,false);
    }
    List<String> javaCommand(String main) {
        return new ArrayList<>(List.of(path("java").toString(), "--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true",
                "-Ddefold.editor.sha1=remote-manager-v1", "-Ddefold.unpack.path=" + directory.resolve("unpack"),
                "-cp", path("classes") + File.pathSeparator + path("jar"), main));
    }
    static String str(Map<?, ?> value, String key) { return Objects.toString(value.get(key), ""); }
    static String hash(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception e) { throw new IllegalStateException(e); } }
    static String hash(Path file) throws Exception { MessageDigest digest = MessageDigest.getInstance("SHA-256"); try (InputStream in = Files.newInputStream(file)) { byte[] b = new byte[65536]; for (int n; (n = in.read(b)) >= 0;) digest.update(b,0,n); } return HexFormat.of().formatHex(digest.digest()); }
    /** Build inputs only: transport/UI/capture updates do not change compiled game data. */
    static Map<String,Object> buildCompatibility(Map<?,?> manifest) {
        if (!(manifest.get("files") instanceof Map<?,?> files)) return Map.of();
        Map<String,String> selected = new TreeMap<>();
        for (var entry : files.entrySet()) {
            String name = entry.getKey().toString().replace('\\','/');
            if (Set.of("editor.jar","dmengine","dmengine.exe","dmengine.pdb","prepare.py","local_extensions.py").contains(name)
                    || name.matches("classes/com/defold/editor/RemoteGuiMigration(?:\\$.*)?\\.class"))
                selected.put(name, entry.getValue().toString());
        }
        if (!selected.containsKey("editor.jar") || !selected.containsKey("prepare.py")
                || !(selected.containsKey("dmengine") || selected.containsKey("dmengine.exe"))
                || !selected.containsKey("classes/com/defold/editor/RemoteGuiMigration.class")) return Map.of();
        return Map.of("version",1,"platform",str(manifest,"platform"),"files",selected,
                "nativeBuild",manifest.get("nativeBuild") instanceof Map<?,?> profile ? profile : Map.of());
    }
    boolean compatibleBuild(Map<?,?> receipt) {
        Map<String,Object> current = buildCompatibility(runtime);
        if (receipt.get("buildCompatibility") instanceof Map<?,?> previous)
            return !current.isEmpty() && current.equals(previous);
        if (Objects.equals(receipt.get("runtimeRevision"),runtime.get("revision"))) return true;
        // Upgrade receipts made before build compatibility was separated from the app
        // revision. Only a retained, matching packaged manifest can prove equivalence.
        String previousRevision = str(receipt,"runtimeRevision");
        if (current.isEmpty() || !previousRevision.matches("[a-f0-9]{16}")) return false;
        Path previousManifest = runtimeRoot.getParent().resolve("runtime-"+previousRevision).resolve("manifest.json");
        try {
            Map<?,?> previous = JSON.readValue(previousManifest.toFile(),Map.class);
            return previousRevision.equals(str(previous,"revision")) && current.equals(buildCompatibility(previous));
        } catch (IOException unavailable) { return false; }
    }
    static final class EngineArtifactException extends IOException {
        EngineArtifactException(String message) { super(message); }
    }
    static Path symbols(Path engine) { String name = engine.getFileName().toString(); int dot = name.lastIndexOf('.'); return engine.resolveSibling((dot < 0 ? name : name.substring(0,dot)) + ".pdb"); }
    static Map<String,Object> engineArtifacts(Path engine, Map<?,?> receipt, boolean windows) throws Exception {
        if (!Files.isRegularFile(engine) || Files.size(engine) == 0) throw new EngineArtifactException("本地构建缺少游戏引擎，请重新构建：" + engine);
        String engineHash = hash(engine);
        if (!str(receipt,"engineSha256").isEmpty() && !engineHash.equals(str(receipt,"engineSha256"))) throw new EngineArtifactException("本地构建引擎已损坏或被替换，请重新构建：" + engine);
        Map<String,Object> hashes = new LinkedHashMap<>(); hashes.put("engineSha256",engineHash);
        if (windows) {
            Path pdb = symbols(engine);
            if (!Files.isRegularFile(pdb) || Files.size(pdb) == 0) throw new EngineArtifactException("Windows 游戏引擎缺少配套调试符号，请重新构建或重新导入：" + pdb);
            String symbolsHash = hash(pdb);
            if (!str(receipt,"symbolsSha256").isEmpty() && !symbolsHash.equals(str(receipt,"symbolsSha256"))) throw new EngineArtifactException("Windows 游戏引擎调试符号已损坏或被替换，请重新构建或重新导入：" + pdb);
            hashes.put("symbolsSha256",symbolsHash);
        }
        return hashes;
    }
    void verifyBuild(Project p) throws Exception {
        Path file = p.build.getParent().resolve("receipt.json");
        Map<String,Object> receipt = JSON.readValue(file.toFile(),Map.class);
        if (!compatibleBuild(receipt)) throw new EngineArtifactException("本地构建与当前编译器或引擎不兼容，请重新构建或重新导入");
        Map<String,Object> hashes = engineArtifacts(p.engine,receipt,str(runtime,"platform").endsWith("win32"));
        // Old receipts already identify the engine. Preserve their build and pin the existing
        // sidecar; the native launch resolver still checks that its symbols match the PE.
        Map<String,Object> compatibility = buildCompatibility(runtime);
        if (!receipt.entrySet().containsAll(hashes.entrySet()) || (!compatibility.isEmpty() && !compatibility.equals(receipt.get("buildCompatibility")))) {
            receipt.putAll(hashes);
            if (!compatibility.isEmpty()) receipt.put("buildCompatibility",compatibility);
            Path temporary = file.resolveSibling("receipt.json.part"); JSON.writeValue(temporary.toFile(),receipt);
            Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }
    }
    synchronized List<Project> all() { return new ArrayList<>(projects.values()); }
    synchronized Project project(String id) { Project p = projects.get(id); if (p == null) throw new IllegalArgumentException("项目不存在"); return p; }
    synchronized void save() throws IOException {
        Path temporary = directory.resolve("registry.json.part");
        JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), Map.of("version",1,"hostId",hostId,"settings",settings,
                "projects", projects.values().stream().map(Project::saved).toList(), "devices", new ArrayList<>(devices.values())));
        Files.move(temporary, directory.resolve("registry.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static boolean excluded(Path relative) {
        if (relative.getNameCount() == 0) return false;
        String name = relative.getFileName().toString();
        if (Set.of(".git","build",".DS_Store","node_modules").contains(name)) return true;
        return relative.getName(0).toString().equals(".internal") && relative.getNameCount() > 1 && !relative.getName(1).toString().equals("lib");
    }
    static List<Path> resources(Path root) throws IOException {
        if (!Files.isRegularFile(root.resolve("game.project"))) {
            if (!Files.exists(root.resolve("game.project"))) throw new NoSuchFileException(root.resolve("game.project").toString());
            throw new IOException("工程目录中没有 game.project");
        }
        List<Path> result = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(root) && excluded(root.relativize(dir))) return FileVisitResult.SKIP_SUBTREE;
                if (Files.isSymbolicLink(dir)) throw new IOException("暂不支持符号链接资源：" + dir); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (excluded(root.relativize(file))) return FileVisitResult.CONTINUE;
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) throw new IOException("暂不支持链接资源：" + file);
                if (result.size() >= 200000) throw new IOException("工程资源超过安全上限");
                result.add(file); return FileVisitResult.CONTINUE;
            }
        });
        result.sort(Comparator.comparing(Path::toString)); return result;
    }
    static String stamp(Path root, List<Path> files) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Path file : files) {
            stampEntry(digest, root.relativize(file), sourceAttributes(file));
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void stampEntry(MessageDigest digest, Path relative, BasicFileAttributes attrs) {
        digest.update((relative + "\0" + attrs.size() + "\0" + attrs.lastModifiedTime() + "\0" + attrs.fileKey() + "\n").getBytes(StandardCharsets.UTF_8));
    }
    private static BasicFileAttributes sourceAttributes(Path file) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile() || attrs.isSymbolicLink()) throw new IOException("暂不支持链接资源：" + file);
        return attrs;
    }
    static final class SourceChangedException extends IOException {
        SourceChangedException(Path source) { super("资源正在保存，等待稳定版本：" + source); }
    }
    interface SnapshotObserver {
        void check() throws Exception;
        default void copied(Path source, Path target, int attempt) throws Exception { }
    }
    record SourceSnapshot(String sourceStamp, String fingerprint, boolean nativeExtension) { }
    private record CopiedSource(BasicFileAttributes attributes, String hash) { }
    private static boolean sameSourceVersion(BasicFileAttributes before, BasicFileAttributes after) {
        return before.size() == after.size() && before.lastModifiedTime().equals(after.lastModifiedTime())
                && Objects.equals(before.fileKey(), after.fileKey());
    }
    private static CopiedSource copyStable(Path source, Path target, SnapshotObserver observer) throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            observer.check();
            try {
                BasicFileAttributes before = sourceAttributes(source);
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                observer.copied(source, target, attempt);
                observer.check();
                String hash = hash(target);
                BasicFileAttributes after = sourceAttributes(source);
                if (sameSourceVersion(before, after) && before.size() == Files.size(target)) {
                    // Windows providers may expose no file key. In that case an atomic save
                    // can preserve both size and mtime; validate bytes once while freezing,
                    // without turning the passive source monitor into a full content scan.
                    if (before.fileKey() != null || hash.equals(hash(source)) && sameSourceVersion(before, sourceAttributes(source)))
                        return new CopiedSource(before, hash);
                }
            } catch (NoSuchFileException changed) {
                // Editors can replace or briefly remove the source during an atomic save.
                // Retry this file, without discarding other files already frozen in this snapshot.
            }
        }
        throw new SourceChangedException(source);
    }
    static SourceSnapshot copySnapshot(Path root, List<Path> files, Path destination, SnapshotObserver observer) throws Exception {
        MessageDigest sources = MessageDigest.getInstance("SHA-256"), content = MessageDigest.getInstance("SHA-256");
        boolean nativeExtension = false;
        for (Path source : files) {
            observer.check();
            Path relative = root.relativize(source), target = destination.resolve(relative).normalize();
            if (!target.startsWith(destination.normalize())) throw new IOException("资源路径超出构建副本：" + source);
            Files.createDirectories(target.getParent());
            CopiedSource copied = copyStable(source, target, observer);
            stampEntry(sources, relative, copied.attributes());
            content.update(relative.toString().getBytes(StandardCharsets.UTF_8));
            content.update(copied.hash().getBytes(StandardCharsets.UTF_8));
            nativeExtension |= source.getFileName().toString().equals("ext.manifest");
            if (source.toString().endsWith(".zip") && relative.startsWith(".internal")) {
                try (ZipFile archive = new ZipFile(target.toFile())) {
                    nativeExtension |= archive.stream().anyMatch(e -> e.getName().endsWith("/ext.manifest") || e.getName().equals("ext.manifest"));
                }
            }
        }
        return new SourceSnapshot(HexFormat.of().formatHex(sources.digest()), HexFormat.of().formatHex(content.digest()), nativeExtension);
    }
    SnapshotObserver snapshotObserver(Project p, long generation) { return () -> check(p, generation); }
    private void observe() {
        if (closed) return;
        for (Project p : all()) try {
            String next = stamp(p.root, resources(p.root));
            boolean prepare, pendingStart;
            synchronized (p) {
                boolean changed = !p.stamp.isEmpty() && !next.equals(p.stamp);
                p.stamp = next;
                p.modified = !p.builtStamp.isEmpty() && !next.equals(p.builtStamp);
                if (p.worker != null && !p.worker.process.isAlive()) { release(p); p.worker.close(); p.worker = null; p.state = "error"; p.error = "游戏已退出，请查看运行日志"; }
                if (p.worker == null && !p.busy && p.modified && p.error.isEmpty()) p.state = "dirty";
                pendingStart = p.startAfterBuild && p.errorKind.equals("source_changed");
                prepare = !p.busy && p.mode.equals("source") && (pendingStart || changed && Boolean.TRUE.equals(settings.get("prepare")));
            }
            if (prepare) prepare(p, pendingStart, false);
        } catch (NoSuchFileException sourceBeingSaved) {
            // An editor's atomic save can temporarily remove a file while this passive scan runs.
            // Keep the current build/start state; the next scan observes the finished save.
        } catch (Exception e) { synchronized (p) { p.error = e.getMessage(); if (p.worker == null && !p.busy) p.state = "error"; } }
    }

    Map<String, Object> status(Project p) {
        synchronized (p) {
            Map<String, Object> out = new LinkedHashMap<>(p.saved());
            out.put("state", p.worker != null && p.worker.process.isAlive() ? "running" : p.state);
            out.put("error", p.error); out.put("stage", p.stage); out.put("busy", p.busy); out.put("modified",p.modified);
            out.put("error_kind", p.errorKind); out.put("failed_stage", p.failedStage);
            out.put("path", "/games/" + p.id); out.put("computer",computer); out.put("controller", controllerName(p));
            out.put("session_id", p.worker == null ? "" : p.worker.sessionId);
            out.put("video_codecs",List.of("h264","jpeg")); out.put("resolutions",List.of("540p","720p","1080p","original"));
            out.put("audio",false); out.put("protocol",2);
            if (p.worker != null) for (String k : List.of("width","height","device_revision")) out.put(k,p.worker.status.getOrDefault(k,0));
            return out;
        }
    }
    String controllerName(Project p) { Map<String,Object> d = devices.get(p.controller); return d == null ? "" : str(d,"name"); }
    synchronized Map<String, Object> status() throws SocketException {
        Map<String,Object> state = new LinkedHashMap<>();
        state.put("hostId",hostId); state.put("computer",computer); state.put("port",gateway.port()); state.put("fingerprint",gateway.fingerprint);
        state.put("addresses",gateway.addresses()); state.put("connectionAddress",gateway.selectedAddress()); state.put("projects",all().stream().map(this::status).toList());
        state.put("devices",devices.values().stream().map(d->Map.of("id",str(d,"id"),"name",str(d,"name"),"pairedAt",str(d,"pairedAt"))).toList());
        state.put("settings",new LinkedHashMap<>(settings)); state.put("platform",runtime.get("platform")); state.put("runtime",runtime.get("revision"));
        state.put("engine",Map.of("directory",engineDirectory,"error",engineError,"nativeCompatible",!Boolean.FALSE.equals(runtime.get("nativeEngineCompatible")))); return state;
    }

    synchronized void prepare(Project p, boolean start, boolean importing) {
        if (closed) throw new IllegalArgumentException("管理器正在退出");
        if (!engineError.isEmpty()) throw new IllegalArgumentException(engineError);
        int limit = ((Number)settings.get("maxSessions")).intValue();
        long active = projects.values().stream().filter(x->x.worker != null || x.startAfterBuild).count();
        synchronized (p) {
            if (start && p.worker != null) return;
            if (start && !p.startAfterBuild && active >= limit) throw new IllegalArgumentException("已达到同时运行上限，请先停止其他项目");
            if (p.busy) { if (start) p.startAfterBuild = true; return; }
            p.busy = true; p.startAfterBuild = start; p.error = ""; p.errorKind = ""; p.failedStage = ""; p.stage = "检查项目与运行环境";
            if (p.worker == null) p.state = "preparing";
            long generation = ++p.generation;
            builds.submit(()->{
                try {
                    prepareBuild(p, importing, generation);
                    boolean startNow;
                    synchronized (p) { check(p,generation); startNow = p.startAfterBuild; if(!startNow){p.busy=false;p.state=p.worker==null?"ready":"running";} }
                    if (startNow) launch(p, generation);
                    synchronized (p) {
                        check(p,generation);
                        p.state = p.worker == null ? "ready" : "running"; p.stage = "";
                    }
                } catch (Exception e) {
                    synchronized (p) { if (p.generation == generation) {
                        p.error = Objects.toString(e.getMessage(),e.toString()); p.failedStage=p.stage;
                        if (e instanceof SourceChangedException) {
                            p.errorKind = "source_changed";
                            p.state = p.worker == null ? "preparing" : "running";
                            p.stage = "等待资源保存完成";
                        } else {
                            if (e instanceof EngineArtifactException) p.builtStamp = ""; // The next explicit retry rebuilds unusable artifacts.
                            if(p.errorKind.isEmpty())p.errorKind=e instanceof EngineArtifactException ? "environment" : "project";
                            p.state = p.worker == null ? "error" : "running"; p.stage = "";
                        }
                    } }
                } finally {
                    synchronized (p) { if (p.generation == generation) { p.busy = false; if(!p.errorKind.equals("source_changed"))p.startAfterBuild = false; p.compiler = null; } }
                    try { save(); } catch (IOException e) { System.err.println(e); }
                }
            });
        }
    }
    void check(Project p, long generation) throws IOException { if (closed || generation != p.generation || Thread.currentThread().isInterrupted()) throw new IOException("操作已取消"); }

    private void prepareBuild(Project p, boolean importing, long generation) throws Exception {
        List<Path> files; String stamp;
        try { files = resources(p.root); stamp = stamp(p.root, files); }
        catch (NoSuchFileException sourceBeingSaved) { throw new SourceChangedException(p.root); }
        synchronized (p) {
            if (!importing && p.build != null && Files.isRegularFile(p.build.resolve("build/default/game.projectc")) && (p.mode.equals("import") || stamp.equals(p.builtStamp))) {
                p.stage = "校验本地构建产物";
                try { verifyBuild(p); return; }
                catch (EngineArtifactException damaged) {
                    p.builtStamp = "";
                    if (p.mode.equals("import")) throw damaged;
                    // Source builds are reproducible. Repair their invalid cache within this
                    // connection request; an explicitly imported engine must be re-imported.
                }
            }
            p.stage = importing ? "导入本地构建产物" : "准备已保存的资源";
        }
        Path destination = directory.resolve("builds").resolve(UUID.randomUUID().toString()).resolve("project");
        Files.createDirectories(destination);
        SourceSnapshot snapshot = copySnapshot(p.root, files, destination, snapshotObserver(p,generation));
        stamp = snapshot.sourceStamp(); String fingerprint = snapshot.fingerprint(); boolean nativeExtension = snapshot.nativeExtension();
        synchronized(p) { check(p,generation); p.stamp=stamp; }
        Path engine = path("engine");
        if (importing) {
            Path compiled = p.root.resolve("build/default");
            if (!Files.isRegularFile(compiled.resolve("game.projectc"))) throw new IOException("未找到 build/default/game.projectc，请先完成本地构建");
            copyTree(compiled,destination.resolve("build/default"));
            String exe = String.valueOf(runtime.get("platform")).endsWith("win32") ? "dmengine.exe" : "dmengine";
            Path custom = p.root.resolve("build").resolve(str(runtime,"platform")).resolve(exe);
            if (nativeExtension && !Files.isRegularFile(custom)) throw new IOException("原生扩展工程缺少本机平台的定制引擎：" + custom);
            if (Files.isRegularFile(custom)) {
                boolean windows = exe.endsWith(".exe");
                Map<String,Object> sourceHashes = engineArtifacts(custom,Map.of(),windows);
                engine = destination.getParent().resolve(exe); Files.copy(custom,engine);
                if (windows) Files.copy(symbols(custom),symbols(engine));
                engineArtifacts(engine,sourceHashes,windows);
                if (!exe.endsWith(".exe")) engine.toFile().setExecutable(true);
            }
        } else {
            if (nativeExtension && Boolean.FALSE.equals(runtime.get("nativeEngineCompatible"))) {
                synchronized(p) { p.errorKind="environment"; }
                throw new IOException("所选 Defold 引擎与本地原生扩展 SDK 不匹配，请安装对应版本的本地 SDK 后重新选择引擎目录");
            }
            engine = automaticBuild(p, destination, generation);
        }
        if (!Files.isRegularFile(destination.resolve("build/default/game.projectc"))) throw new IOException("构建没有产生 game.projectc");
        synchronized(p) { p.stage = "校验本地构建产物"; }
        Map<String,Object> receipt = new LinkedHashMap<>(Map.of("fingerprint",fingerprint,"sourceStamp",stamp,"runtimeRevision",runtime.get("revision"),"mode",importing?"import":"source"));
        Map<String,Object> compatibility = buildCompatibility(runtime);
        if (!compatibility.isEmpty()) receipt.put("buildCompatibility",compatibility);
        receipt.putAll(engineArtifacts(engine,Map.of(),str(runtime,"platform").endsWith("win32")));
        JSON.writeValue(destination.getParent().resolve("receipt.json").toFile(),receipt);
        boolean modified;
        try { modified = !stamp.equals(stamp(p.root, resources(p.root))); }
        catch (IOException sourceStillSaving) { modified = true; }
        synchronized (p) { check(p,generation); p.build=destination;p.engine=engine;p.builtStamp=stamp;p.stamp=stamp;p.modified=modified;p.builtAt=Instant.now().toString();p.mode=importing?"import":"source"; }
    }

    Path automaticBuild(Project p, Path destination, long generation) throws Exception {
        Map<?,?> profile = runtime.get("nativeBuild") instanceof Map<?,?> value ? value : Map.of();
        Path python = Path.of(str(profile,"python"));
        if (!Files.isRegularFile(python)) { synchronized(p){p.errorKind="environment";} throw new IOException("运行组件缺少本机构建工具，请更新 Remote Manager 运行包"); }
        Path log = destination.getParent().resolve("build.log"), progress = destination.getParent().resolve("progress.json");
        List<String> command = List.of(python.toString(), "-X", "utf8", runtimeRoot.resolve("prepare.py").toString(),
                gameManifest.toString(), destination.toString(), directory.resolve("native-cache").toString());
        Process compiler;
        synchronized(p) {
            check(p,generation); p.buildLog=log;
            if (System.getProperty("os.name").startsWith("Windows")) {
                // Owned Windows job also terminates Bob, CMake, compiler and linker on cancellation or crash.
                compiler=WindowsGameProcess.start(command, runtimeRoot.toFile(), Map.of("DM_EDITOR_AUTOMATION","1","PYTHONUTF8","1"));
            } else {
                ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true);
                builder.environment().put("PYTHONUTF8","1"); compiler=builder.start();
            }
            p.compiler=compiler;
        }
        var output=CompletableFuture.runAsync(()->{try(InputStream in=compiler.getInputStream();OutputStream out=Files.newOutputStream(log)){in.transferTo(out);}catch(IOException e){System.err.println(e);}});
        Map<String,Object> result=Map.of();
        try {
            long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(20);
            do {
                check(p,generation);
                if(System.nanoTime()>deadline)throw new IOException("本地构建超时");
                if(Files.isRegularFile(progress)){
                    result=JSON.readValue(progress.toFile(),Map.class);
                    synchronized(p){check(p,generation);p.stage=str(result,"stage");p.errorKind=str(result,"errorKind");}
                }
            } while(!compiler.waitFor(150,TimeUnit.MILLISECONDS));
            output.get(10,TimeUnit.SECONDS);check(p,generation);
            if(Files.isRegularFile(progress))result=JSON.readValue(progress.toFile(),Map.class);
            synchronized(p){p.stage=str(result,"stage");p.errorKind=str(result,"errorKind");}
            if(compiler.exitValue()!=0)throw new IOException(str(result,"error")+"\n"+tail(log));
            Path engine=Path.of(str(result,"engine"));
            if(!Files.isRegularFile(engine))throw new IOException("自动构建没有返回有效引擎");
            return engine;
        } finally {
            if(compiler.isAlive())terminateBuild(compiler);
        }
    }

    static void terminateBuild(Process process) {
        if(!System.getProperty("os.name").startsWith("Windows"))process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source,new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path p,BasicFileAttributes a)throws IOException { if(Files.isSymbolicLink(p))throw new IOException("构建中存在链接");Files.createDirectories(target.resolve(source.relativize(p)));return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult visitFile(Path p,BasicFileAttributes a)throws IOException { if(!a.isRegularFile()||Files.isSymbolicLink(p))throw new IOException("构建中存在链接");Files.copy(p,target.resolve(source.relativize(p)));return FileVisitResult.CONTINUE; }
        });
    }
    static String tail(Path path) throws IOException { if(!Files.isRegularFile(path))return "";try(var file=new RandomAccessFile(path.toFile(),"r")){file.seek(Math.max(0,file.length()-12000));byte[] b=new byte[(int)(file.length()-file.getFilePointer())];file.readFully(b);return new String(b,StandardCharsets.UTF_8);} }
    void launch(Project p, long generation) throws Exception {
        synchronized (p) { check(p,generation); p.stage="启动后台游戏"; }
        Map<String,String> config = new LinkedHashMap<>();String section="";
        for(String line:Files.readAllLines(p.build.resolve("build/default/game.projectc"))){line=line.trim();if(line.startsWith("["))section=line.substring(1,line.indexOf(']'));else if(line.contains("=")){String[] a=line.split("=",2);config.put(section+"."+a[0].trim(),a[1].trim());}}
        int width=Integer.parseInt(config.getOrDefault("display.width","960")),height=Integer.parseInt(config.getOrDefault("display.height","640"));
        Path session=directory.resolve("sessions").resolve(p.id+"-"+UUID.randomUUID());
        Worker w=new Worker(this,session);
        try { w.call(new LinkedHashMap<>(Map.of("command","start","engine",p.engine.toString(),"project",p.build.toString(),"compiled_project","build/default/game.projectc","width",width,"height",height))); synchronized(p) { check(p,generation); p.worker=w; } }
        catch(Exception e){w.close();throw e;}
    }

    void release(Project p) {
        synchronized(p){p.controlToken="";p.controller="";p.controlUntil=0;
            for(HttpURLConnection c:p.connections) c.disconnect();p.connections.clear();
            for(var c:p.clients) Thread.ofVirtual().start(c::close);p.clients.clear();
            if(p.worker!=null)p.worker.release();}
    }
    void stop(Project p) {
        Worker worker;Process compiler;
        synchronized(p){++p.generation;p.startAfterBuild=false;p.busy=false;compiler=p.compiler;p.compiler=null;release(p);worker=p.worker;p.worker=null;p.state=p.modified?"dirty":p.build==null?"new":"ready";p.stage="";}
        if(compiler!=null)terminateBuild(compiler);if(worker!=null)worker.close();
    }

    Object command(Map<String,Object> request) throws Exception {
        String method=str(request,"method");Map<String,Object> params=(Map<String,Object>)request.getOrDefault("params",Map.of());
        if(method.equals("status"))return status();
        if(method.equals("engine-directory")){selectEngine(Path.of(str(params,"path")));return status();}
        if(method.equals("pairing"))return gateway.pairing();
        if(method.equals("pairing-status"))return gateway.pairingStatus();
        if(method.equals("connection-address")){gateway.selectAddress(str(params,"address"));return status();}
        if(method.equals("add")){
            Path root=Path.of(str(params,"path")).toRealPath();if(!Files.isRegularFile(root.resolve("game.project")))throw new IOException("请选择包含 game.project 的目录");
            if(directory.startsWith(root)||root.startsWith(directory)||root.startsWith(runtimeRoot))throw new IOException("项目目录不能包含管理器数据或运行组件");
            synchronized(this){if(projects.values().stream().anyMatch(p->p.root.equals(root)))throw new IOException("此项目已经添加");
                String title=root.getFileName().toString();boolean projectSection=false;for(String line:Files.readAllLines(root.resolve("game.project"))){if(line.trim().startsWith("["))projectSection=line.trim().equals("[project]");else if(projectSection&&line.matches("\\s*title\\s*=.*"))title=line.substring(line.indexOf('=')+1).trim();}
                Project p=new Project(UUID.randomUUID().toString(),root,title);projects.put(p.id,p);save();return status(p);}
        }
        if(method.equals("settings")){synchronized(this){if(params.containsKey("maxSessions")){int n=((Number)params.get("maxSessions")).intValue();if(n<1||n>8)throw new IllegalArgumentException("并发范围为 1–8");settings.put("maxSessions",n);}if(params.get("prepare") instanceof Boolean b)settings.put("prepare",b);save();}return status();}
        if(method.equals("revoke")){String id=str(params,"id");synchronized(this){devices.remove(id);}for(Project p:all())synchronized(p){if(p.controller.equals(id))release(p);}save();return status();}
        Project p=project(str(params,"id"));
        switch(method){
            case "start"->prepare(p,true,false);
            case "prepare"->prepare(p,false,false);
            case "import"->prepare(p,false,true);
            case "stop","cancel"->stop(p);
            case "share"->{synchronized(p){p.shared=Boolean.TRUE.equals(params.get("shared"));if(!p.shared)release(p);}save();}
            case "remove"->{stop(p);synchronized(this){projects.remove(p.id);save();}}
            case "logs"->{synchronized(p){if(p.busy||!p.error.isEmpty())return Map.of("text",p.buildLog!=null?tail(p.buildLog):p.error);if(p.worker!=null)return p.worker.call(Map.of("command","logs","after",0));if(p.build!=null)return Map.of("text",tail(p.build.getParent().resolve("build.log")));return Map.of("text",p.error);}}
            case "thumbnail"->{Worker w;synchronized(p){w=p.worker;}if(w==null)return Map.of("image","");HttpURLConnection c=w.connection("/thumbnail","GET");try{byte[] image=c.getInputStream().readNBytes(4*1024*1024);return Map.of("image","data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(image));}finally{c.disconnect();}}
            default->throw new IllegalArgumentException("未知操作："+method);
        }
        return status();
    }

    @Override public void close(){closed=true;monitor.shutdownNow();gateway.close();for(Project p:all())stop(p);builds.shutdownNow();}
    public static void main(String[] args)throws Exception{
        PrintStream protocol=new PrintStream(new FileOutputStream(FileDescriptor.out),true,StandardCharsets.UTF_8);System.setOut(System.err);
        try(RemoteManager manager=new RemoteManager(Path.of(args[0]),Path.of(args[1]),args.length>2?Integer.parseInt(args[2]):8390);
            BufferedReader input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))){
            for(String line;(line=input.readLine())!=null;){try{if(line.length()>65536)throw new IOException("请求过大");Map<String,Object> request=JSON.readValue(line,Map.class);if("quit".equals(request.get("method")))break;Object result=manager.command(request);protocol.println(JSON.writeValueAsString(Map.of("ok",true,"result",result)));}catch(Exception e){protocol.println(JSON.writeValueAsString(Map.of("ok",false,"error",Objects.toString(e.getMessage(),e.toString()))));}protocol.flush();}
        }
    }
}
