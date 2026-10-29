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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Test-only fault: the first phone Start prepares real resources but loses its run intent. */
public final class RemotePreparedOnlyTestHost extends RemoteManager {
    private final Map<String,Integer> requests=new HashMap<>();
    private final Set<String> idleBeforeRetry=new HashSet<>();

    private RemotePreparedOnlyTestHost(Path manifest,Path state,int port)throws Exception{super(manifest,state,port);}

    @Override synchronized void prepare(Project project,boolean start,boolean importing){
        if(!start){super.prepare(project,false,importing);return;}
        int count=requests.getOrDefault(project.id,0)+1;requests.put(project.id,count);
        if(count==1){
            // Keep the actual copy/compiler/cache. Only the first run intent is lost.
            super.prepare(project,false,importing);
            return;
        }
        synchronized(project){
            if(!project.busy&&project.worker==null&&Set.of("ready","dirty","new").contains(project.state))
                idleBeforeRetry.add(project.id);
        }
        // The application's second request must launch the real hidden game.
        super.prepare(project,true,importing);
    }

    @Override Object command(Map<String,Object> request)throws Exception{
        if("test-start-stats".equals(str(request,"method"))){
            Map<?,?> params=request.get("params") instanceof Map<?,?> value?value:Map.of();
            String id=str(params,"id");
            synchronized(this){return Map.of("startRequests",requests.getOrDefault(id,0),
                    "firstStartPreparedOnly",requests.containsKey(id),"idleBeforeRetry",idleBeforeRetry.contains(id));}
        }
        return super.command(request);
    }

    public static void main(String[] args)throws Exception{
        PrintStream protocol=new PrintStream(new FileOutputStream(FileDescriptor.out),true,StandardCharsets.UTF_8);
        System.setOut(System.err);
        try(RemotePreparedOnlyTestHost manager=new RemotePreparedOnlyTestHost(Path.of(args[0]),Path.of(args[1]),args.length>2?Integer.parseInt(args[2]):18680);
            BufferedReader input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))){
            for(String line;(line=input.readLine())!=null;){
                try{
                    if(line.length()>65536)throw new IOException("请求过大");
                    Map<String,Object> request=JSON.readValue(line,Map.class);
                    if("quit".equals(request.get("method")))break;
                    protocol.println(JSON.writeValueAsString(Map.of("ok",true,"result",manager.command(request))));
                }catch(Exception error){protocol.println(JSON.writeValueAsString(Map.of("ok",false,"error",Objects.toString(error.getMessage(),error.toString()))));}
                protocol.flush();
            }
        }
    }
}
