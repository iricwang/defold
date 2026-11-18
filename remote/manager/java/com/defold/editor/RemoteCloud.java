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
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Optional WAN controller. The relay authorizes peers; the private worker owns media. */
final class RemoteCloud implements AutoCloseable {
    private final RemoteManager manager;
    private final Path credentials;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService guard = Executors.newSingleThreadScheduledExecutor();
    private final ThreadPoolExecutor events = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),new ThreadPoolExecutor.DiscardPolicy());
    private final String epoch = RemoteGateway.secret();
    private volatile Map<String,Object> account = Map.of();
    private volatile Map<String,Object> view = Map.of("enabled",false,"state","off");
    private Process transport;
    private BufferedWriter pipe;
    private String room="", peer="", lease="", project="";
    private long until, sequence=-1;
    private final LinkedHashMap<String,Object> replies = new LinkedHashMap<>();
    private boolean closed, connected;

    RemoteCloud(RemoteManager manager) {
        this.manager=manager;credentials=manager.directory.resolve("cloud.json");
        try { if(Files.isRegularFile(credentials))account=RemoteManager.JSON.readValue(credentials.toFile(),Map.class); }
        catch(IOException ignored){view=Map.of("enabled",false,"state","error","message","公网配置无法读取，请重新登录");}
        heartbeat.scheduleWithFixedDelay(this::beat,0,8,TimeUnit.SECONDS);
        guard.scheduleWithFixedDelay(()->{synchronized(this){if(!lease.isEmpty()&&System.nanoTime()>until)disconnectViewer();}},200,200,TimeUnit.MILLISECONDS);
    }
    Map<String,Object> status(){return view;}
    Map<String,Object> pairedAccess(String id,String lanToken) throws IOException {
        var current=account;
        if(current.isEmpty())return Map.of();
        String host=RemoteManager.str(current,"id");
        String secret=RemoteManager.hash(("defold-viewer-v1:"+current.get("server")+":"+host+":"+lanToken).getBytes(StandardCharsets.UTF_8));
        String verifier=RemoteManager.hash(secret.getBytes(StandardCharsets.UTF_8));
        synchronized(manager){
            var device=manager.devices.get(id);
            if(device==null)return Map.of();
            if(!verifier.equals(device.get("cloudVerifier"))){
                device.put("cloudVerifier",verifier);device.put("cloudHost",host);manager.save();
                pairingChanged();
            }
        }
        return Map.of("server",current.get("server"),"host",host,"grant","paired."+host+"."+id,"deviceToken",secret);
    }
    void pairingChanged(){heartbeat.execute(this::beat);}
    private Path executable() throws IOException {
        String name=System.getProperty("os.name").startsWith("Windows")?"defold-remote-transport.exe":"defold-remote-transport";
        Path path=manager.runtimeRoot.resolve(name);
        if(!Files.isExecutable(path))throw new IOException("当前运行包尚未包含公网传输组件");
        return path;
    }
    static URI origin(String value) {
        URI uri=URI.create(value);
        boolean local="http".equals(uri.getScheme())&&Set.of("127.0.0.1","localhost").contains(uri.getHost());
        if((!"https".equals(uri.getScheme())&&!local)||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!Set.of("","/").contains(uri.getPath()))
            throw new IllegalArgumentException("请填写 HTTPS 服务器地址");
        return URI.create(uri.getScheme()+"://"+uri.getRawAuthority());
    }
    private Map<String,Object> request(String server,String path,String token,Object body,String method)throws Exception {
        HttpRequest.Builder b=HttpRequest.newBuilder(origin(server).resolve(path)).timeout(Duration.ofSeconds(8))
            .header("Authorization","Bearer "+token).header("Content-Type","application/json");
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(RemoteManager.JSON.writeValueAsBytes(body)));
        HttpResponse<InputStream> response=http.send(b.build(),HttpResponse.BodyHandlers.ofInputStream());
        try(InputStream in=response.body()){
            byte[] bytes=in.readNBytes(65537);if(bytes.length>65536)throw new IOException("服务器响应过大");
            Map<String,Object> value=RemoteManager.JSON.readValue(bytes,Map.class);
            if(response.statusCode()!=200)throw new IOException(Objects.toString(value.get("error"),"公网请求失败"));return value;
        }
    }
    private Map<String,Object> api(Map<String,Object> a,String path,Object body,String method)throws Exception {
        return request(RemoteManager.str(a,"server"),"/v1/defold/"+path,RemoteManager.str(a,"token"),body,method);
    }
    Object command(String method,Map<String,Object> params)throws Exception {
        if(method.equals("cloud-status"))return status();
        if(method.equals("cloud-login")){
            synchronized(this){if(!account.isEmpty())throw new IOException("请先关闭当前公网连接");}
            executable();String server=origin(RemoteManager.str(params,"server")).toString();
            Map<String,Object> login=request(server,"/v1/login","",Map.of("username",RemoteManager.str(params,"username"),"password",RemoteManager.str(params,"password")),"POST");
            Map<String,Object> host=request(server,"/v1/defold/hosts",RemoteManager.str(login,"token"),Map.of("name",manager.computer),"POST");
            Map<String,Object> next=new LinkedHashMap<>(host);next.put("server",server);
            synchronized(this){if(!account.isEmpty())throw new IOException("请先关闭当前公网连接");
                Path tmp=credentials.resolveSibling("cloud.json.tmp");RemoteManager.JSON.writeValue(tmp.toFile(),next);
                if(!System.getProperty("os.name").startsWith("Windows"))Files.setPosixFilePermissions(tmp,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                Files.move(tmp,credentials,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);account=next;view=Map.of("enabled",true,"state","connecting");
            }
            heartbeat.execute(this::beat);return Map.of("ok",true);
        }
        Map<String,Object> a=account;if(a.isEmpty())throw new IOException("请先登录公网服务");
        if(method.equals("cloud-invite")){Map<String,Object> invite=api(a,"invitations",Map.of(),"POST");return Map.of("server",a.get("server"),"invitation",invite.get("id"));}
        if(method.equals("cloud-revoke")){api(a,"grants/"+RemoteManager.str(params,"id"),null,"DELETE");heartbeat.execute(this::beat);return Map.of("ok",true);}
        if(method.equals("cloud-logout")){
            api(a,"host/remove",Map.of(),"POST");
            synchronized(this){account=Map.of();disconnect();Files.deleteIfExists(credentials);view=Map.of("enabled",false,"state","off");}return Map.of("ok",true);
        }
        throw new IllegalArgumentException("未知公网操作");
    }
    private void beat(){
        Map<String,Object> a=account;if(a.isEmpty())return;
        try{
            List<Map<String,String>> paired;
            synchronized(manager){
                paired=manager.devices.values().stream().filter(d->a.get("id").equals(d.get("cloudHost")))
                    .map(d->Map.of("id",RemoteManager.str(d,"id"),"verifier",RemoteManager.str(d,"cloudVerifier"))).toList();
            }
            Map<String,Object> response=api(a,"host/heartbeat",Map.of("epoch",epoch,"paired",paired),"POST");
            synchronized(this){
                if(closed||account!=a)return;
                String nextRoom=RemoteManager.str(response,"room");
                if(transport==null||!transport.isAlive()||!room.equals(nextRoom)){
                    disconnect();start();room=nextRoom;send(Map.of("command","join","url",response.get("url"),"token",response.get("token")));
                }
                Map<?,?> viewer=response.get("viewer") instanceof Map<?,?> v?v:Map.of();
                String nextLease=Objects.toString(viewer.get("lease"),"");
                if(!nextLease.equals(lease))disconnectViewer();
                peer=Objects.toString(viewer.get("identity"),"");lease=nextLease;
                long remaining=viewer.get("expiresInMs") instanceof Number n?n.longValue():0;
                until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.max(0,Math.min(20000,remaining)));
                if(!project.isEmpty()){
                    RemoteManager.Project p=manager.project(project);
                    synchronized(p){if(!p.shared||p.worker==null||!p.controller.equals(peer))disconnectViewer();else p.controlUntil=until;}
                }
                view=Map.of("enabled",true,"state",connected?"online":"connecting","server",a.get("server"),"grants",response.get("grants"));
            }
        }catch(Exception e){view=Map.of("enabled",true,"state","retrying","message","公网暂不可用，正在重连");}
    }
    private void start()throws IOException {
        transport=new ProcessBuilder(executable().toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        Process owned=transport;pipe=owned.outputWriter(StandardCharsets.UTF_8);
        Thread.ofVirtual().start(()->{try(var in=owned.inputReader(StandardCharsets.UTF_8)){
            for(String line;(line=in.readLine())!=null;){if(line.length()>65536)break;Map<String,Object> event=RemoteManager.JSON.readValue(line,Map.class);events.execute(()->event(owned,event));}
        }catch(IOException ignored){}finally{synchronized(this){if(transport==owned)disconnect();}}});
    }
    private synchronized void send(Object value)throws IOException{if(pipe==null)throw new IOException("公网传输尚未连接");pipe.write(RemoteManager.JSON.writeValueAsString(value));pipe.newLine();pipe.flush();}
    private long lastAdmissionCheck;
    private void event(Process owned,Map<String,Object> event){
        boolean refresh;
        synchronized(this){
            long now=System.nanoTime();
            refresh=transport==owned&&!closed&&"rpc".equals(event.get("event"))
                &&(!Objects.equals(peer,event.get("caller"))||now>=until)
                &&now-lastAdmissionCheck>TimeUnit.SECONDS.toNanos(3);
            if(refresh)lastAdmissionCheck=now;
        }
        if(refresh)try{heartbeat.submit(this::beat).get(4,TimeUnit.SECONDS);}catch(Exception ignored){}
        synchronized(this){
        if(transport!=owned||closed)return;
        String kind=RemoteManager.str(event,"event");
        try{
            if(kind.equals("connected")){connected=true;return;}
            if(kind.equals("error")||kind.equals("disconnected")){disconnect();return;}
            if(kind.equals("encoder")&&!project.isEmpty()){
                RemoteManager.Project p=manager.project(project);synchronized(p){if(p.worker!=null&&p.controller.equals(peer)&&System.nanoTime()<until)worker(p,"/encoder",event,null);}return;
            }
            if(!kind.equals("rpc")&&!kind.equals("data"))return;
            Object result;
            try{
                if(lease.isEmpty()||System.nanoTime()>=until||!peer.equals(event.get("caller")))throw new IOException("设备授权已失效");
                if(peer.startsWith("viewer-paired.")&&!manager.devices.containsKey(peer.substring(peer.lastIndexOf('.')+1)))throw new IOException("电脑已移除此配对");
                String payload=RemoteManager.str(event,"payload");if(payload.length()>12000)throw new IOException("请求过大");
                Map<String,Object> command=RemoteManager.JSON.readValue(payload,Map.class);
                if(!lease.equals(command.get("lease")))throw new IOException("会话已变化");
                if(kind.equals("data")){
                    if(!"defold.move".equals(event.get("topic"))||!"move".equals(command.get("action")))return;
                    control(command);return;
                }
                String id=RemoteManager.str(command,"requestId");if(!id.matches("[A-Za-z0-9_-]{8,80}"))throw new IOException("请求编号无效");
                if(replies.containsKey(id))result=replies.get(id);
                else{try{result=Map.of("ok",true,"result",control(command));}catch(Exception e){result=Map.of("ok",false,"error",Objects.toString(e.getMessage(),"操作失败"));}
                    replies.put(id,result);while(replies.size()>128)replies.remove(replies.keySet().iterator().next());}
            }catch(Exception e){result=Map.of("ok",false,"error",Objects.toString(e.getMessage(),"操作失败"));}
            if(kind.equals("rpc"))send(Map.of("command","reply","id",event.get("id"),"result",result));
        }catch(Exception ignored){/* The peer retries RPCs with the same requestId. */}
    }}
    // Use the same shared project set as LAN. Page below LiveKit's RPC payload limit.
    Map<String,Object> catalog(Map<String,Object> command) throws IOException {
        String cursor=Objects.toString(command.get("cursor"),"");
        List<Map<String,Object>> games=new ArrayList<>();String last="";boolean more=false;
        for(RemoteManager.Project p:manager.sharedProjects().stream().sorted(Comparator.comparing(p->p.id)).toList()) {
            if(p.id.compareTo(cursor)<=0)continue;
            Map<String,Object> entry;
            synchronized(p){entry=Map.of("id",p.id,"name",p.name,"state",p.state);}
            games.add(entry);
            if(games.size()>1&&(games.size()>20||RemoteManager.JSON.writeValueAsBytes(games).length>12000)) {
                games.remove(games.size()-1);more=true;break;
            }
            if(RemoteManager.JSON.writeValueAsBytes(games).length>12000)throw new IOException("项目名称过长，请在电脑端缩短名称");
            last=p.id;
        }
        return Map.of("games",games,"nextCursor",more?last:"");
    }
    // WAN carries physical pixels; the existing worker wire format is unsigned 16-bit normalized.
    static byte[] pointerPacket(int x,int y,int width,int height,boolean down) {
        if(width<2||height<2||width>65536||height>65536||x<0||y<0||x>=width||y>=height)
            throw new IllegalArgumentException("输入坐标无效");
        // Round upward so the worker's integer pixel conversion returns the exact source pixel.
        int nx=(int)(((long)x*65535+width-2)/(width-1));
        int ny=(int)(((long)y*65535+height-2)/(height-1));
        return new byte[]{(byte)nx,(byte)(nx>>8),(byte)ny,(byte)(ny>>8),(byte)(down?1:0)};
    }
    private Object control(Map<String,Object> command)throws Exception {
        String action=RemoteManager.str(command,"action");
        if(action.equals("games"))return catalog(command);
        RemoteManager.Project p=manager.project(RemoteManager.str(command,"project"));
        synchronized(p){
            if(!p.shared)throw new IOException("项目未共享");
            if(action.equals("status"))return manager.gateway.publicStatus(p);
            if(action.equals("start")){manager.prepare(p,true,false);return manager.gateway.publicStatus(p);}
            if(action.equals("claim")){
                if(p.worker==null)throw new IOException("游戏尚未运行");
                if(!p.controller.isEmpty()&&!p.controller.equals(peer)&&p.controlUntil>System.nanoTime())throw new IOException("其他设备正在操作");
                if(!project.isEmpty())manager.release(manager.project(project));
                project=p.id;p.controller=peer;p.controlToken=RemoteGateway.secret();p.worker.lease(p.controlToken);p.controlUntil=until;sequence=-1;
                return manager.gateway.publicStatus(p);
            }
            if(!p.id.equals(project)||!p.controller.equals(peer)||p.worker==null)throw new IOException("请先取得控制权");
            p.controlUntil=until;
            switch(action){
                case "device"->{
                    send(Map.of("command","stop-media"));p.worker.release();
                    Map<String,Object> device=new LinkedHashMap<>();for(String k:List.of("width","height","top","right","bottom","left"))device.put(k,command.getOrDefault(k,0));
                    device.put("command","device");device.put("name","device.png");
                    try{p.worker.call(device);p.worker.call(Map.of("command","status"));}finally{p.worker.lease(p.controlToken);}
                    sequence=-1;return manager.gateway.publicStatus(p);
                }
                case "stream"->{
                    String quality=RemoteRuntime.remoteQuality(Objects.toString(command.getOrDefault("resolution","540p"))).id;
                    // Configure the private encoder before the transport opens its WAN-profile stream.
                    worker(p,"/encoder",Map.of("resolution",quality),null);
                    send(Map.of("command","media","endpoint",p.worker.status.get("endpoint"),"token",p.worker.status.get("token"),"lease",p.controlToken,"audio",Boolean.TRUE.equals(p.worker.status.get("audio"))));
                    return manager.gateway.publicStatus(p);
                }
                case "input","move"->{
                    long next=command.get("sequence") instanceof Number n?n.longValue():-1;
                    if(next<=sequence)return Map.of("stale",true);
                    if(!Objects.toString(p.worker.status.get("device_revision"),"").equals(Objects.toString(command.get("deviceRevision"),"")))throw new IOException("画面尺寸已变化");
                    int x=((Number)command.get("x")).intValue(),y=((Number)command.get("y")).intValue();
                    if(x<0||y<0||x>=((Number)p.worker.status.get("width")).intValue()||y>=((Number)p.worker.status.get("height")).intValue())throw new IOException("输入坐标无效");
                    boolean down=Boolean.TRUE.equals(command.get("down"));
                    worker(p,"/input",pointerPacket(x,y,((Number)p.worker.status.get("width")).intValue(),((Number)p.worker.status.get("height")).intValue(),down),Objects.toString(command.get("deviceRevision"),""));sequence=next;return Map.of("ok",true);
                }
                case "release"->{releaseProject();return Map.of("ok",true);}
                case "stop"->{releaseProject();manager.stop(p);return manager.gateway.publicStatus(p);}
                default->throw new IOException("不支持的公网操作");
            }
        }
    }
    private void worker(RemoteManager.Project p,String path,Object value,String revision)throws Exception{
        HttpURLConnection c=p.worker.connection(path,"POST");
        try{c.setRequestProperty("X-Remote-Lease",p.controlToken);if(revision!=null)c.setRequestProperty("X-Defold-Device-Revision",revision);
            byte[] data=value instanceof byte[] bytes?bytes:RemoteManager.JSON.writeValueAsBytes(value);
            c.setDoOutput(true);c.setFixedLengthStreamingMode(data.length);try(var out=c.getOutputStream()){out.write(data);}if(c.getResponseCode()!=200)throw new IOException("游戏操作失败");c.getInputStream().close();
        }finally{c.disconnect();}
    }
    private void releaseProject(){
        if(!project.isEmpty())try{RemoteManager.Project p=manager.project(project);synchronized(p){if(p.controller.equals(peer))manager.release(p);}}catch(Exception ignored){}
        project="";sequence=-1;try{send(Map.of("command","stop-media"));}catch(IOException ignored){}
    }
    private void disconnectViewer(){
        if(!project.isEmpty())try{RemoteManager.Project p=manager.project(project);synchronized(p){if(p.controller.equals(peer))manager.release(p);}}catch(Exception ignored){}
        project="";peer="";lease="";until=0;sequence=-1;replies.clear();
        try{send(Map.of("command","stop-media"));}catch(IOException ignored){}
    }
    private void disconnect(){connected=false;disconnectViewer();room="";if(pipe!=null)try{pipe.close();}catch(IOException ignored){}pipe=null;if(transport!=null)transport.destroy();transport=null;}
    @Override public void close(){heartbeat.shutdownNow();guard.shutdownNow();events.shutdownNow();synchronized(this){closed=true;disconnect();}http.close();}
}
