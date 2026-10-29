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

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real artifact I/O and Manager restore/reuse/import; compilation and game launch are fakes. */
public final class RemoteEngineArtifactsTest {
    static final List<String> checks = new ArrayList<>();
    static void check(String name, boolean value) { if (!value) throw new AssertionError(name); checks.add(name); }
    static void write(Path file, String text) throws Exception { Files.createDirectories(file.getParent()); Files.writeString(file,text); }
    static Path project(Path work, String name, boolean imported) throws Exception {
        Path root = work.resolve(name); write(root.resolve("game.project"),"[project]\ntitle = Artifact test\n");
        if (imported) {
            write(root.resolve("native/ext.manifest"),"name: ArtifactTest\n");
            write(root.resolve("build/default/game.projectc"),"[display]\nwidth = 320\nheight = 240\n");
            write(root.resolve("build/x86_64-win32/dmengine.exe"),"imported engine");
            write(root.resolve("build/x86_64-win32/dmengine.pdb"),"imported symbols");
        }
        return root;
    }
    static Map<String,Object> receipt(RemoteManager.Project p) throws Exception { return RemoteManager.JSON.readValue(p.build.getParent().resolve("receipt.json").toFile(),Map.class); }
    static final class ControlledManager extends RemoteManager {
        int built, launched;
        boolean missingSymbols;
        ControlledManager(Path manifest, Path state) throws Exception { super(manifest,state,18820); monitor.shutdownNow(); settings.put("prepare",false); }
        @Override Path automaticBuild(Project p, Path destination, long generation) throws Exception {
            check(p,generation); built++;
            write(destination.resolve("build/default/game.projectc"),"[display]\nwidth = 320\nheight = 240\n");
            Path engine = destination.resolve("build/x86_64-win32/dmengine.exe"); write(engine,"built engine");
            if (!missingSymbols) write(symbols(engine),"built symbols");
            return engine;
        }
        @Override void launch(Project p, long generation) throws Exception { check(p,generation); launched++; }
        Project add(Path root) throws Exception {
            Map<?,?> result = (Map<?,?>)command(Map.of("method","add","params",Map.of("path",root.toString())));
            return project(result.get("id").toString());
        }
        void settled() throws Exception { builds.submit(()->{}).get(20,TimeUnit.SECONDS); }
    }
    public static void main(String[] args) throws Exception {
        Path manifest = Path.of(args[0]).toAbsolutePath(), work = Path.of(args[1]).toAbsolutePath(); Files.createDirectories(work);
        Map<String,Object> runtime = RemoteManager.JSON.readValue(manifest.toFile(),Map.class);
        runtime.remove("engineDirectory"); // This suite controls compiler identities independently of an installed editor.
        for (String key : List.of("engine","native","classes","jar")) runtime.put(key,manifest.getParent().resolve(runtime.get(key).toString()).toString());
        // Use this test JVM's trusted Java installation for the isolated TLS certificate,
        // rather than executing a packaged runtime copied into a development directory.
        runtime.put("java",Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString());
        manifest = work.resolve("test-runtime/manifest.json"); Files.createDirectories(manifest.getParent()); RemoteManager.JSON.writeValue(manifest.toFile(),runtime);
        Path state = work.resolve("source-state"); String id; Path originalBuild;
        try (ControlledManager manager = new ControlledManager(manifest,state)) {
            RemoteManager.Project p = manager.add(project(work,"source",false)); id = p.id;
            manager.prepare(p,false,false); manager.settled(); originalBuild = p.build;
            check("fresh Windows build receipt hashes both engine and PDB", receipt(p).get("symbolsSha256").equals(RemoteManager.hash(RemoteManager.symbols(p.engine))));
            manager.prepare(p,true,false); manager.settled();
            check("unchanged intact build is reused before launch", manager.built == 1 && manager.launched == 1);
            Map<String,Object> legacy = receipt(p); legacy.remove("symbolsSha256"); legacy.remove("buildCompatibility"); RemoteManager.JSON.writeValue(p.build.getParent().resolve("receipt.json").toFile(),legacy); manager.save();
        }
        Path previousManifest = work.resolve("runtime-"+runtime.get("revision")+"/manifest.json");
        Files.createDirectories(previousManifest.getParent()); RemoteManager.JSON.writeValue(previousManifest.toFile(),runtime);
        runtime.put("revision","0123456789abcdef"); RemoteManager.JSON.writeValue(manifest.toFile(),runtime);
        try (ControlledManager manager = new ControlledManager(manifest,state)) {
            RemoteManager.Project p = manager.project(id);
            check("Manager-only upgrade migrates legacy receipt and preserves build", p.state.equals("ready") && p.build.equals(originalBuild));
            check("legacy receipt acquires the existing PDB hash", receipt(p).containsKey("symbolsSha256"));
            check("legacy receipt acquires independent build compatibility", receipt(p).containsKey("buildCompatibility"));
            manager.prepare(p,true,false); manager.settled();
            check("upgrade skips compilation and launches the existing build", manager.built == 0 && manager.launched == 1);
            manager.launched = 0;
            write(RemoteManager.symbols(p.engine),"replacement symbols");
            manager.prepare(p,true,false); manager.settled();
            check("source connection repairs a changed PDB before launch in the same request", manager.built == 1 && manager.launched == 1 && !p.build.equals(originalBuild) && p.error.isEmpty());
            check("repaired source build pins its new symbols", receipt(p).get("symbolsSha256").equals(RemoteManager.hash(RemoteManager.symbols(p.engine))));
            write(RemoteManager.symbols(p.engine),"changed after stop"); manager.save();
        }
        try (ControlledManager manager = new ControlledManager(manifest,state)) {
            RemoteManager.Project p = manager.project(id);
            check("restoring a changed PDB reports an error instead of ready", p.state.equals("error") && p.error.contains("调试符号已损坏"));
            Files.delete(RemoteManager.symbols(p.engine));
        }
        try (ControlledManager manager = new ControlledManager(manifest,state)) {
            check("restoring a missing PDB reports its required sidecar", manager.project(id).error.contains("缺少配套调试符号"));
            RemoteManager.Project p = manager.project(id); manager.prepare(p,true,false); manager.settled();
            check("explicit retry rebuilds invalidated artifacts without source edits", manager.built == 1 && manager.launched == 1 && !p.build.equals(originalBuild) && p.error.isEmpty());
        }
        String importedId;
        try (ControlledManager manager = new ControlledManager(manifest,work.resolve("import-state"))) {
            Path root = project(work,"import",true); RemoteManager.Project p = manager.add(root); importedId = p.id;
            manager.prepare(p,false,true); manager.settled();
            check("import copies native EXE and PDB together", p.state.equals("ready") && Files.readString(RemoteManager.symbols(p.engine)).equals("imported symbols"));
            check("import receipt pins copied symbols", receipt(p).get("symbolsSha256").equals(RemoteManager.hash(RemoteManager.symbols(p.engine))));
            write(p.engine,"replacement engine"); manager.prepare(p,true,false); manager.settled();
            check("changed EXE is rejected before cached launch", p.state.equals("error") && manager.launched == 0 && p.error.contains("引擎已损坏"));
            check("damaged import identifies validation stage and environment", p.failedStage.equals("校验本地构建产物") && p.errorKind.equals("environment"));
            manager.prepare(p,true,false); manager.settled();
            check("retry of a damaged import never falls back to a default or source engine", p.state.equals("error") && manager.built == 0 && manager.launched == 0);
            Files.delete(root.resolve("build/x86_64-win32/dmengine.pdb")); manager.prepare(p,false,true); manager.settled();
            check("import without symbols fails before becoming ready", p.state.equals("error") && p.error.contains("缺少配套调试符号"));
        }
        try (ControlledManager manager = new ControlledManager(manifest,work.resolve("import-state"))) {
            RemoteManager.Project p = manager.project(importedId);
            check("restored damaged import remains invalidated with its explicit error", p.state.equals("error") && p.builtStamp.isEmpty() && !p.error.isEmpty());
            manager.prepare(p,true,false); manager.settled();
            check("restored damaged import never compiles a substitute engine", manager.built == 0 && manager.launched == 0 && p.state.equals("error"));
        }
        try (ControlledManager manager = new ControlledManager(manifest,work.resolve("missing-output-state"))) {
            manager.missingSymbols = true; RemoteManager.Project p = manager.add(project(work,"missing-output",false));
            manager.prepare(p,true,false); manager.settled();
            check("fresh build without symbols fails before launch or ready", p.state.equals("error") && p.build == null && manager.launched == 0 && p.error.contains("缺少配套调试符号"));
        }
        Path macEngine = work.resolve("mac/dmengine"); write(macEngine,"mac engine");
        check("macOS artifact validation does not require a Windows PDB", !RemoteManager.engineArtifacts(macEngine,Map.of(),false).containsKey("symbolsSha256"));
        // Verify build invalidation separately from the complete Manager package revision.
        Map<String,Object> identity = RemoteManager.buildCompatibility(runtime);
        check("packaged build identity is complete", !identity.isEmpty());
        for (String changed : List.of("editor.jar","dmengine.exe","dmengine.pdb","prepare.py","local_extensions.py","classes/com/defold/editor/RemoteGuiMigration.class")) {
            Map<String,Object> candidate = new LinkedHashMap<>(runtime);
            Map<String,Object> files = new LinkedHashMap<>((Map<String,Object>)runtime.get("files"));
            files.put(changed,"changed"); candidate.put("files",files);
            check("build input change invalidates: "+changed, !identity.equals(RemoteManager.buildCompatibility(candidate)));
        }
        Map<String,Object> candidate = new LinkedHashMap<>(runtime);
        Map<String,Object> transport = new LinkedHashMap<>((Map<String,Object>)runtime.get("files"));
        transport.put("native/game_window_hook.dll","changed"); transport.put("classes/com/defold/editor/RemoteGateway.class","changed");
        candidate.put("files",transport);
        check("capture and transport updates keep compiled game compatibility", identity.equals(RemoteManager.buildCompatibility(candidate)));
        candidate.put("nativeBuild",Map.of("sdk","another-sdk"));
        check("native SDK configuration change invalidates compatibility", !identity.equals(RemoteManager.buildCompatibility(candidate)));
        candidate = new LinkedHashMap<>(runtime); candidate.put("platform","arm64-macos");
        check("platform change invalidates compatibility", !identity.equals(RemoteManager.buildCompatibility(candidate)));
        // A compiler upgrade must invalidate a source cache even when source timestamps did not change.
        Path upgradeState = work.resolve("upgrade-state"); String upgradeId;
        try (ControlledManager manager = new ControlledManager(manifest,upgradeState)) {
            var p = manager.add(project(work,"upgrade-source",false)); upgradeId = p.id;
            manager.prepare(p,false,false); manager.settled();
            check("unverifiable legacy package is never reused", !manager.compatibleBuild(Map.of("runtimeRevision","ffffffffffffffff")));
        }
        String compatibleImportId;
        try (ControlledManager manager = new ControlledManager(manifest,work.resolve("compat-import-state"))) {
            var p = manager.add(project(work,"compat-import",true)); compatibleImportId = p.id;
            manager.prepare(p,false,true); manager.settled();
        }
        runtime.put("revision","abcdef0123456789"); RemoteManager.JSON.writeValue(manifest.toFile(),runtime);
        try (ControlledManager manager = new ControlledManager(manifest,upgradeState)) {
            var p = manager.project(upgradeId); manager.prepare(p,true,false); manager.settled();
            check("new receipt survives subsequent Manager revision without legacy manifest", manager.built == 0 && manager.launched == 1);
        }
        Map<String,Object> changedFiles = new LinkedHashMap<>((Map<String,Object>)runtime.get("files")); changedFiles.put("editor.jar","new compiler");
        runtime.put("files",changedFiles); RemoteManager.JSON.writeValue(manifest.toFile(),runtime);
        try (ControlledManager manager = new ControlledManager(manifest,upgradeState)) {
            var p = manager.project(upgradeId);
            check("compiler update does not restore incompatible cache", p.build == null);
            manager.prepare(p,true,false); manager.settled();
            check("compiler update rebuilds unchanged project before launch", manager.built == 1 && manager.launched == 1);
        }
        try (ControlledManager manager = new ControlledManager(manifest,work.resolve("compat-import-state"))) {
            var p = manager.project(compatibleImportId); manager.prepare(p,true,false); manager.settled();
            check("incompatible import requires reimport, never silently compiles source", p.state.equals("error") && p.error.contains("不兼容") && manager.built == 0 && manager.launched == 0);
        }
        RemoteManager.JSON.writerWithDefaultPrettyPrinter().writeValue(work.resolve("report.json").toFile(),Map.of("passed",checks,"scope","Real artifact I/O, Manager import/restore/reuse and controlled compilation/launch; no user project, installed manager or game process touched"));
        System.out.println("RemoteEngineArtifactsTest: " + checks.size() + " checks passed; " + work);
    }
}
