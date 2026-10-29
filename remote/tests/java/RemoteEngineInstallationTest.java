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
import java.util.zip.*;

/** Installation parsing uses synthetic distributions; no fixture binary is executed. */
public final class RemoteEngineInstallationTest {
    static final List<String> checks = new ArrayList<>();
    static void check(String name, boolean ok) { if (!ok) throw new AssertionError(name); checks.add(name); }
    static void write(Path path, byte[] bytes) throws Exception { Files.createDirectories(path.getParent()); Files.write(path,bytes); }
    static byte[] pe() { byte[] b=new byte[80]; b[0]=0x4d;b[1]=0x5a;b[0x3c]=64;b[64]=0x50;b[65]=0x45;b[68]=0x64;b[69]=(byte)0x86;return b; }
    static Path app(Path root, String platform, byte[] engine, boolean pdb) throws Exception {
        boolean windows=platform.endsWith("win32"); Path resources=windows?root:root.resolve("Contents/Resources");
        write(resources.resolve(windows?"packages/jdk-test/bin/javaw.exe":"packages/jdk-test/Contents/Home/bin/java"),new byte[]{1});
        Path jar=resources.resolve("packages/defold-test.jar");
        try (ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("libexec/"+platform+"/"+(windows?"dmengine.exe":"dmengine")));zip.write(engine);zip.closeEntry();
            if(pdb){zip.putNextEntry(new ZipEntry("_unpack/"+platform+"/bin/dmengine.pdb"));zip.write(new byte[]{1,2,3});zip.closeEntry();}
        }
        return root;
    }
    static void rejected(String name, RunnableWithError call, String message) throws Exception {
        try{call.run();throw new AssertionError("Accepted invalid installation: "+name);}catch(java.io.IOException expected){check(name,expected.getMessage().contains(message));}
    }
    interface RunnableWithError {void run()throws Exception;}
    // Verifies platform layout, pinned engine/compiler selection, and rejection before configuration mutation.
    public static void main(String[] args)throws Exception {
        Path manifest=Path.of(args[0]).toAbsolutePath(),work=Path.of(args[1]).toAbsolutePath();Files.createDirectories(work);
        Map<String,Object> original=RemoteManager.JSON.readValue(manifest.toFile(),Map.class);
        Map<String,Object> runtime=new LinkedHashMap<>(original);
        Map<String,Object> profile=new LinkedHashMap<>((Map<String,Object>)runtime.get("nativeBuild"));profile.put("sdk",work.resolve("sdk").toString());runtime.put("nativeBuild",profile);
        write(work.resolve("sdk/bin/x86_64-win32/dmengine.exe"),pe());
        Path selected=app(work.resolve("Windows Defold"),"x86_64-win32",pe(),true);
        var resolved=RemoteEngineInstallation.resolve(selected,work.resolve("cache"),manifest,runtime);
        check("selected installation becomes the global directory",resolved.directory().equals(selected.toRealPath()));
        check("compiler is pinned outside the mutable installed directory",!Path.of(resolved.runtime().get("jar").toString()).startsWith(selected));
        check("compiler and engine come from the selected archive",Files.readAllBytes(Path.of(resolved.runtime().get("engine").toString())).length==pe().length);
        check("selection creates its own effective build manifest",Files.isRegularFile(resolved.manifest())&&!resolved.runtime().get("revision").equals(runtime.get("revision")));
        check("build scripts use the same effective engine manifest",Files.isRegularFile(resolved.manifest().getParent().resolve("local_extensions.py")));
        check("matching native SDK is required",resolved.nativeCompatible());
        Path noPdb=app(work.resolve("no-pdb"),"x86_64-win32",pe(),false);
        rejected("missing PDB rejected before activation",()->RemoteEngineInstallation.resolve(noPdb,work.resolve("bad-cache"),manifest,runtime),"dmengine.pdb");
        byte[] wrong=pe();wrong[68]=0x4c;wrong[69]=1;
        Path wrongArch=app(work.resolve("wrong-arch"),"x86_64-win32",wrong,true);
        rejected("wrong CPU architecture rejected",()->RemoteEngineInstallation.resolve(wrongArch,work.resolve("arch-cache"),manifest,runtime),"x64");
        write(work.resolve("sdk/bin/x86_64-win32/dmengine.exe"),new byte[]{9});
        rejected("mismatching local SDK cannot silently supply a different engine",()->RemoteEngineInstallation.resolve(selected,work.resolve("sdk-cache"),manifest,runtime),"本地原生扩展 SDK");
        Map<String,Object> mac=new LinkedHashMap<>(runtime);mac.put("platform","arm64-macos");
        byte[] bridge="Editor automation bridge protocol 1 (macOS HID)".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        write(work.resolve("sdk/bin/arm64-macos/dmengine"),bridge);
        Path macApp=app(work.resolve("Defold.app"),"arm64-macos",bridge,false);
        var macSelected=RemoteEngineInstallation.resolve(macApp,work.resolve("mac-cache"),manifest,mac);
        check("macOS app bundle and Contents/Home Java layout resolve",macSelected.runtime().get("java").toString().replace('\\','/').endsWith("Contents/Home/bin/java"));
        Path unsupportedMac=app(work.resolve("Unsupported.app"),"arm64-macos",new byte[]{1,2},false);
        rejected("macOS without background bridge rejected",()->RemoteEngineInstallation.resolve(unsupportedMac,work.resolve("mac-bad"),manifest,mac),"后台输入桥接");
        Path actual=Path.of(args[2]).toAbsolutePath();
        var same=RemoteEngineInstallation.resolve(actual,work.resolve("same-cache"),manifest,original);
        check("same installed contents reuse existing immutable package",same.manifest().equals(manifest));
        try(var manager=new RemoteEngineArtifactsTest.ControlledManager(manifest,work.resolve("manager-state"))){
            String before=manager.engineDirectory;
            manager.command(Map.of("method","engine-directory","params",Map.of("path",actual.toString())));
            check("global engine setting is persisted",manager.settings.get("engineDirectory").equals(actual.toRealPath().toString()));
            rejected("invalid selection retains working configuration",()->manager.selectEngine(work.resolve("missing")),"missing");
            check("failed selection leaves active runtime intact",manager.engineDirectory.equals(before));
        }
        try(var manager=new RemoteEngineArtifactsTest.ControlledManager(manifest,work.resolve("manager-state"))){check("selected global installation survives restart",manager.engineDirectory.equals(actual.toRealPath().toString())&&manager.engineError.isEmpty());}
        write(work.resolve("sdk/bin/x86_64-win32/dmengine.exe"),pe());
        try(var manager=new RemoteEngineArtifactsTest.ControlledManager(manifest,work.resolve("switch-state"))){
            var first=manager.add(RemoteEngineArtifactsTest.project(work,"switch-one",false));
            var second=manager.add(RemoteEngineArtifactsTest.project(work,"switch-two",false));
            manager.prepare(first,false,false);manager.prepare(second,false,false);manager.settled();
            Path oldFirst=first.build,oldSecond=second.build;
            manager.packagedRuntime.put("nativeBuild",profile);
            first.startAfterBuild=true;second.startAfterBuild=true;
            manager.selectEngine(selected);manager.settled();
            check("global version change rebuilds both previously prepared projects",manager.built==4&&!first.build.equals(oldFirst)&&!second.build.equals(oldSecond));
            check("global version change preserves both launch intents",manager.launched==2);
            check("all new builds share the selected engine/compiler manifest",manager.gameManifest.startsWith(manager.directory.resolve("engines"))
                    && RemoteEngineArtifactsTest.receipt(first).get("buildCompatibility").equals(RemoteEngineArtifactsTest.receipt(second).get("buildCompatibility"))
                    && RemoteManager.buildCompatibility(manager.runtime).equals(RemoteEngineArtifactsTest.receipt(first).get("buildCompatibility")));
        }
        RemoteManager.JSON.writerWithDefaultPrettyPrinter().writeValue(work.resolve("report.json").toFile(),Map.of("passed",checks,"scope","Installation fixture parsing and actual Windows installation settings; macOS paths only, no Mac execution"));
        System.out.println("RemoteEngineInstallationTest: "+checks.size()+" checks passed; "+work);
    }
}
