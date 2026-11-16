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
import java.nio.file.attribute.FileTime;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Real shared-project filesystem/registry boundaries, controlled compiler and launch. */
public final class RemoteInPlaceBuildTest {
    static final List<String> passed=new ArrayList<>();
    static void check(String name,boolean yes){if(!yes)throw new AssertionError(name);passed.add(name);}
    static class Manager extends RemoteManager {
        int compiled,launched; boolean fail;
        CountDownLatch entered,proceed;
        Manager(Path manifest,Path state)throws Exception{super(manifest,state,0);monitor.shutdownNow();settings.put("prepare",false);}
        Project add(Path root)throws Exception{return project(((Map<?,?>)command(Map.of("method","add","params",Map.of("path",root.toString())))).get("id").toString());}
        void settled()throws Exception{builds.submit(()->{}).get(20,TimeUnit.SECONDS);}
        @Override Path automaticBuild(Project p,Path root,long generation)throws Exception{
            RemoteInPlaceBuildTest.check("compiler receives original project root",root.equals(p.root)); compiled++;
            if(entered!=null){entered.countDown();proceed.await(10,TimeUnit.SECONDS);}
            check(p,generation);
            if(fail)throw new IOException("EXPECTED_BUILD_ERROR");
            Files.writeString(root.resolve("build/default/game.projectc"),"[display]\nwidth=320\nheight=240\n");
            return path("engine");
        }
        @Override void launch(Project p,long generation)throws Exception{check(p,generation);launched++;}
    }
    static Path source(Path work)throws Exception{
        Path root=work.resolve("game");Files.createDirectories(root);
        Files.writeString(root.resolve("game.project"),"[project]\ntitle=Shared project test\n");
        Files.writeString(root.resolve("main.lua"),"return 1");return root;
    }
    // Source builds, retries and restart must never create a project snapshot or delete shared outputs.
    public static void main(String[] args)throws Exception{
        if(args[0].equals("--lease-child")){System.out.println("ready");System.in.read();return;}
        Path manifest=Path.of(args[0]),work=Files.createTempDirectory("remote-inplace-test-").toRealPath(),root=source(work),state=work.resolve("manager");
        String id;
        try(Manager m=new Manager(manifest,state)){
            String unpack=m.unpackPath().toString();
            check("native extraction follows compiler identity",!unpack.endsWith("/unpack") && m.javaCommand("test").contains("-Ddefold.unpack.path="+unpack));
            var changedRuntime=new LinkedHashMap<String,Object>(m.runtime);
            var changedFiles=new LinkedHashMap<String,Object>((Map<String,Object>)m.runtime.get("files"));
            changedFiles.put("editor.jar","a".repeat(64));changedRuntime.put("files",changedFiles);
            var currentRuntime=m.runtime;m.runtime=changedRuntime;
            check("compiler changes isolate native libraries",!m.unpackPath().toString().equals(unpack));m.runtime=currentRuntime;
            var p=m.add(root);id=p.id;m.prepare(p,false,false);m.settled();
            check("build is the game root",root.equals(p.build));
            check("no Manager snapshots",!Files.exists(state.resolve("builds")));
            check("receipt is project-local",Files.isRegularFile(RemoteManager.receiptPath(p)) && RemoteManager.receiptPath(p).startsWith(root.resolve(".internal")));
            check("no parent-directory bookkeeping",!Files.exists(root.getParent().resolve("receipt.json")));
            check("original source remains intact",Files.readString(root.resolve("main.lua")).equals("return 1"));
            Files.createDirectories(root.resolve("build/unrelated-audit"));
            Files.createSymbolicLink(root.resolve("build/unrelated-audit/python"),Path.of(System.getProperty("java.home"),"bin/java"));
            m.prepare(p,true,false);m.settled();check("unchanged start reuses build",m.compiled==1 && m.launched==1);
            Files.setLastModifiedTime(root.resolve("main.lua"),FileTime.fromMillis(System.currentTimeMillis()+10000));
            m.prepare(p,false,false);m.settled();check("metadata-only change reuses content",m.compiled==1);
            Files.writeString(root.resolve("build/default/game.projectc"),"external builder output");
            m.prepare(p,false,false);m.settled();check("external output mutation revalidated",m.compiled==2 && p.error.isEmpty());
            Files.writeString(root.resolve("main.lua"),"return 2");m.fail=true;
            m.prepare(p,true,false);m.settled();
            check("failed build not published",p.state.equals("error") && !Files.exists(RemoteManager.receiptPath(p)));
            check("failed build preserves project",Files.readString(root.resolve("main.lua")).equals("return 2") && Files.exists(root.resolve("build/default/game.projectc")));
            m.fail=false;m.prepare(p,true,false);m.settled();check("retry repairs outputs",m.compiled==4 && p.error.isEmpty() && m.launched==2);
            try(var lock=RemoteManager.lockProject(root)){
                Files.writeString(root.resolve("main.lua"),"return 3");m.prepare(p,false,false);m.settled();
                check("another project lock prevents concurrent compiler",m.compiled==4 && !p.error.isEmpty());
            }
            m.prepare(p,false,false);m.settled();check("released lock permits build",m.compiled==5 && p.error.isEmpty());
            Files.writeString(root.resolve("main.lua"),"return 4");m.entered=new CountDownLatch(1);m.proceed=new CountDownLatch(1);
            m.prepare(p,false,false);check("compiler entered",m.entered.await(10,TimeUnit.SECONDS));m.stop(p);m.proceed.countDown();m.settled();
            check("cancel leaves receipt invalid",!Files.exists(RemoteManager.receiptPath(p)));
            check("cancel preserves shared source and output",Files.exists(root.resolve("main.lua")) && Files.exists(root.resolve("build/default/game.projectc")));
            m.entered=null;m.prepare(p,false,false);m.settled();
            check("cancelled build can retry",p.error.isEmpty() && root.equals(p.build));
            Process child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),
                    "com.defold.editor.RemoteInPlaceBuildTest","--lease-child").start();
            try {
                check("lease child ready",child.inputReader().readLine().equals("ready"));
                RemoteManager.JSON.writeValue(RemoteManager.projectState(root).resolve("preview-lease.json").toFile(),Map.of("pid",child.pid(),"started",child.info().startInstant().get().toString()));
                boolean blocked=false;try(var ignored=RemoteManager.lockProject(root)){}catch(IOException expected){blocked=true;}
                check("orphan preview prevents overwriting live outputs",blocked);
            } finally {child.getOutputStream().close();child.waitFor(10,TimeUnit.SECONDS);child.destroyForcibly();}
            try(var ignored=RemoteManager.lockProject(root)){check("exited preview permits next build",true);}
            m.cleanupStorage();check("collection cannot remove project",Files.exists(root.resolve("main.lua")) && Files.exists(root.resolve("build/default/game.projectc")));
        }
        try(Manager m=new Manager(manifest,state)){
            var p=m.project(id);check("restart restores in-place build",root.equals(p.build) && p.state.equals("ready"));
            m.prepare(p,true,false);m.settled();check("restart reuses compiled resources",m.compiled==0 && m.launched==1);
            Files.writeString(RemoteManager.receiptPath(p),"broken");m.prepare(p,false,false);m.settled();
            check("corrupt receipt repaired in place",m.compiled==1 && p.error.isEmpty());
            m.prepare(p,false,true);m.settled();check("explicit import references shared outputs",root.equals(p.build) && p.mode.equals("import") && m.compiled==1);
            m.command(Map.of("method","remove","params",Map.of("id",id)));m.settled();
            check("remove never deletes game or compiled resources",Files.exists(root.resolve("main.lua")) && Files.exists(root.resolve("build/default/game.projectc")));
            check("remove never creates snapshot tree",!Files.exists(state.resolve("builds")));
        }
        Files.writeString(work.resolve("report.json"),RemoteManager.JSON.writeValueAsString(Map.of("passed",passed)));
        System.out.println(RemoteManager.JSON.writeValueAsString(Map.of("work",work.toString(),"passed",passed)));
    }
}
