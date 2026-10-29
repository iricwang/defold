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

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipFile;

/** Resolves one installed Defold distribution for every project, without executing it. */
final class RemoteEngineInstallation {
    record Selection(Path directory, Path manifest, Map<String,Object> runtime, boolean nativeCompatible) { }

    static Path single(Path directory, String pattern, String description) throws IOException {
        if (!Files.isDirectory(directory)) throw new IOException("引擎目录缺少 " + description + "：" + directory);
        try (var entries = Files.newDirectoryStream(directory,pattern)) {
            List<Path> paths = new ArrayList<>(); entries.forEach(paths::add);
            if (paths.size() != 1) throw new IOException("引擎目录应包含唯一的 " + description);
            return paths.getFirst();
        }
    }
    static Path path(Path root, Map<?,?> runtime, String key) { return root.resolve(RemoteManager.str(runtime,key)).normalize(); }
    static void extract(ZipFile zip, String name, Path destination) throws IOException {
        var entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) throw new IOException("所选引擎缺少 " + name + "，请使用支持 Remote 的本地 Defold 版本");
        try (InputStream in = zip.getInputStream(entry)) { Files.copy(in,destination,StandardCopyOption.REPLACE_EXISTING); }
        if (Files.size(destination) == 0) throw new IOException("所选引擎文件为空：" + name);
    }
    static boolean contains(Path path, byte[] marker) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            int matched = 0;
            for (int b; (b=in.read()) >= 0;) {
                if ((byte)b == marker[matched]) { if (++matched == marker.length) return true; }
                else matched = (byte)b == marker[0] ? 1 : 0;
            }
        }
        return false;
    }
    static void requireSdk(Map<String,Object> runtime, String platform, String engineName, Object expected) throws Exception {
        Map<?,?> profile=runtime.get("nativeBuild") instanceof Map<?,?> p ? p : Map.of();
        Path sdkEngine=Path.of(RemoteManager.str(profile,"sdk")).resolve("bin").resolve(platform).resolve(engineName);
        if (!Files.isRegularFile(sdkEngine) || !RemoteManager.hash(sdkEngine).equals(expected))
            throw new IOException("所选引擎缺少匹配的本地原生扩展 SDK，请先完成此版本的本机 SDK 编译；当前引擎配置保持不变");
    }

    static Selection resolve(Path app, Path cache, Path packagedManifest, Map<String,Object> packaged) throws Exception {
        app = app.toRealPath();
        Path root = packagedManifest.getParent();
        String platform = RemoteManager.str(packaged,"platform");
        boolean windows = platform.endsWith("win32");
        Path resources = Files.isDirectory(app.resolve("Contents/Resources")) ? app.resolve("Contents/Resources") : app;
        Path jar = single(resources.resolve("packages"),"defold-*.jar","Defold 资源编译器 JAR");
        Path jdk = single(resources.resolve("packages"),"jdk-*","Java 运行时");
        Path java = jdk.resolve(windows ? "bin/javaw.exe" : "bin/java");
        if (!windows && !Files.isRegularFile(java)) java=jdk.resolve("Contents/Home/bin/java");
        if (!Files.isRegularFile(java)) throw new IOException("所选引擎缺少 Java 运行时：" + java);
        String digest = RemoteManager.hash(jar);
        Map<?,?> packagedFiles = (Map<?,?>)packaged.get("files");
        // Same installation contents already live in the immutable bundled runtime.
        // Reuse those bytes without copying the JAR or invalidating any game build.
        if (digest.equals(packagedFiles.get("editor.jar"))) {
            String engineName=windows?"dmengine.exe":"dmengine";
            requireSdk(packaged,platform,engineName,packagedFiles.get(engineName));
            return new Selection(app,packagedManifest,new LinkedHashMap<>(packaged),true);
        }

        Path directory = cache.resolve(RemoteManager.str(packaged,"revision")+"-"+platform+"-"+digest);
        Files.createDirectories(directory);
        Path selectedJar = directory.resolve("editor.jar");
        if (!Files.isRegularFile(selectedJar) || !RemoteManager.hash(selectedJar).equals(digest)) {
            Path partial = directory.resolve("editor.jar.part"); Files.copy(jar,partial,StandardCopyOption.REPLACE_EXISTING);
            if (!RemoteManager.hash(partial).equals(digest)) throw new IOException("引擎正在更新，请等待更新完成后重试");
            Files.move(partial,selectedJar,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }
        String engineName = windows ? "dmengine.exe" : "dmengine";
        Path engine = directory.resolve(engineName);
        try (ZipFile zip = new ZipFile(selectedJar.toFile())) {
            extract(zip,"libexec/"+platform+"/"+engineName,engine);
            if (windows) extract(zip,"_unpack/"+platform+"/bin/dmengine.pdb",RemoteManager.symbols(engine));
        }
        if (windows) {
            try (RandomAccessFile pe = new RandomAccessFile(engine.toFile(),"r")) {
                if (pe.readUnsignedByte()!=0x4d || pe.readUnsignedByte()!=0x5a) throw new IOException("所选引擎不是 Windows EXE");
                pe.seek(0x3c); long offset=Integer.toUnsignedLong(Integer.reverseBytes(pe.readInt())); pe.seek(offset);
                if (pe.readInt()!=0x50450000 || Short.reverseBytes(pe.readShort())!=(short)0x8664) throw new IOException("所选引擎不是 Windows x64 版本");
            }
        } else {
            if (!contains(engine,"Editor automation bridge protocol 1 (macOS HID)".getBytes(StandardCharsets.US_ASCII)))
                throw new IOException("所选 macOS 引擎缺少 Remote 后台输入桥接，请选择本地编译的 Defold 版本");
            engine.toFile().setExecutable(true);
        }
        Map<String,Object> effective = new LinkedHashMap<>(packaged);
        effective.put("revision",RemoteManager.hash((RemoteManager.str(packaged,"revision")+digest).getBytes(StandardCharsets.UTF_8)).substring(0,16));
        for (String key : List.of("classes","native")) effective.put(key,path(root,packaged,key).toString());
        effective.put("java",java.toString()); effective.put("jar",selectedJar.toString()); effective.put("engine",engine.toString());
        Map<String,Object> files = new LinkedHashMap<>((Map<String,Object>)packagedFiles);
        files.put("editor.jar",digest); files.put(engineName,RemoteManager.hash(engine));
        if (windows) files.put("dmengine.pdb",RemoteManager.hash(RemoteManager.symbols(engine)));
        effective.put("files",files);
        for (String script : List.of("prepare.py","local_extensions.py")) Files.copy(root.resolve(script),directory.resolve(script),StandardCopyOption.REPLACE_EXISTING);
        // The SDK links native extensions. A directory selection must never silently
        // link against another version of the engine from the manager's toolchain.
        requireSdk(packaged,platform,engineName,files.get(engineName));
        effective.put("nativeEngineCompatible",true);
        effective.put("engineDirectory",app.toString());
        Path manifest = directory.resolve("manifest.json"), temporary = directory.resolve("manifest.json.part");
        RemoteManager.JSON.writeValue(temporary.toFile(),effective);
        Files.move(temporary,manifest,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        return new Selection(app,manifest,effective,true);
    }
}
