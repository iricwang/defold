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

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Host-paired, certificate-pinned LAN protocol. Administration only exists on the private stdin pipe. */
final class RemoteGateway implements AutoCloseable {
    final RemoteManager manager;
    final HttpsServer server;
    final String fingerprint;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private final Semaphore requests = new Semaphore(96);
    private String pairSecret = "";
    private long pairExpires;
    private boolean pairBusy;
    private String pairState = "idle", pairedName = "";
    private final Map<String, long[]> attempts = new HashMap<>();
    private final Map<HttpExchange, Long> writing = new ConcurrentHashMap<>();
    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    RemoteGateway(RemoteManager manager, int requestedPort) throws Exception {
        // Small touch replies must not wait for TCP's delayed-ACK/Nagle timer.
        System.setProperty("sun.net.httpserver.nodelay", "true");
        this.manager = manager;
        Path key = manager.directory.resolve("host.p12"), password = manager.directory.resolve("host-key-password");
        if (!Files.exists(password)) Files.writeString(password, secret(), StandardOpenOption.CREATE_NEW);
        String pass = Files.readString(password).trim();
        if (!Files.exists(key)) {
            ProcessBuilder pb = new ProcessBuilder(manager.path("java").toString(), "-m", "java.base/sun.security.tools.keytool.Main", "-genkeypair", "-alias", "host", "-keyalg", "EC", "-groupname", "secp256r1",
                    "-validity", "3650", "-dname", "CN=Defold Remote Manager", "-storetype", "PKCS12", "-keystore", key.toString(),
                    "-storepass:env", "DEFOLD_REMOTE_STORE_PASSWORD", "-keypass:env", "DEFOLD_REMOTE_STORE_PASSWORD", "-noprompt");
            pb.environment().put("DEFOLD_REMOTE_STORE_PASSWORD", pass);
            pb.redirectErrorStream(true).redirectOutput(manager.directory.resolve("certificate.log").toFile());
            Process process = pb.start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("创建主机证书超时"); }
            if (process.exitValue()!=0) throw new IOException("无法创建主机证书，请查看 certificate.log");
        }
        if (!System.getProperty("os.name").startsWith("Windows")) for(Path file:List.of(key,password))
            Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        KeyStore store = KeyStore.getInstance("PKCS12"); try(InputStream in=Files.newInputStream(key)){store.load(in,pass.toCharArray());}
        fingerprint=RemoteManager.hash(store.getCertificate("host").getEncoded());
        KeyManagerFactory keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(store,pass.toCharArray());
        SSLContext context=SSLContext.getInstance("TLS");context.init(keys.getKeyManagers(),null,new SecureRandom());
        HttpsServer bound=null;
        for(int port=requestedPort;port<requestedPort+30;port++)try{bound=HttpsServer.create(new InetSocketAddress("0.0.0.0",port),32);break;}catch(BindException ignored){}
        if(bound==null)throw new IOException("Remote 端口范围被占用");server=bound;
        server.setHttpsConfigurator(new HttpsConfigurator(context));server.setExecutor(executor);server.createContext("/",this::route);server.start();
        watchdog.scheduleWithFixedDelay(()->{
            long now=System.nanoTime();
            writing.forEach((client,start)->{if(now-start>1_500_000_000L){writing.remove(client);Thread.ofVirtual().start(client::close);}});
            for(var p:manager.all())synchronized(p){if(!p.controlToken.isEmpty()&&p.controlUntil<now)manager.release(p);}
        },200,200,TimeUnit.MILLISECONDS);
    }
    static String secret(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    int port(){return server.getAddress().getPort();}
    List<String> addresses() throws SocketException {
        Map<String,Integer> ranked=new HashMap<>();
        for(var interfaces=NetworkInterface.getNetworkInterfaces();interfaces.hasMoreElements();){NetworkInterface n=interfaces.nextElement();if(!n.isUp()||n.isLoopback())continue;
            String description=(n.getName()+" "+n.getDisplayName()).toLowerCase(Locale.ROOT);
            int rank=n.isVirtual()||description.matches(".*(virtual|vethernet|docker|wsl|vpn|tailscale|zerotier|hamachi).*" )?10:0;
            for(var addresses=n.getInetAddresses();addresses.hasMoreElements();){InetAddress a=addresses.nextElement();if(a instanceof Inet4Address&&a.isSiteLocalAddress())ranked.put("https://"+a.getHostAddress()+":"+port(),rank);}}
        List<String> result=new ArrayList<>(ranked.keySet());result.sort(Comparator.comparingInt((String a)->ranked.get(a)).thenComparing(a->a));
        if(result.isEmpty())result.add("https://127.0.0.1:"+port());return result;
    }
    String selectedAddress() throws SocketException {
        List<String> available=addresses();
        String selected=String.valueOf(manager.settings.getOrDefault("connectionAddress",""));
        return available.contains(selected)?selected:available.getFirst();
    }
    synchronized void selectAddress(String address) throws Exception {
        if(!addresses().contains(address))throw new IllegalArgumentException("请选择当前电脑的有效连接地址");
        if(pairBusy)throw new IllegalStateException("手机正在配对，请完成后再切换地址");
        pairSecret="";pairExpires=0;pairState="idle";pairedName="";
        synchronized(manager){manager.settings.put("connectionAddress",address);manager.save();}
    }
    synchronized Map<String,Object> pairing() throws Exception {
        String address=selectedAddress();
        if(address.isEmpty())throw new IllegalArgumentException("请选择一个手机可访问的连接 IP");
        if(pairBusy)throw new IllegalStateException("手机正在配对，请稍候");
        pairSecret=secret();pairExpires=System.currentTimeMillis()+120000;
        pairState="waiting";pairedName="";
        String uri="defoldremote://connect?v=2&address="+URLEncoder.encode(address,StandardCharsets.UTF_8)+"&code="+pairSecret+"&fingerprint="+fingerprint;
        return Map.of("uri",uri,"qrUri",RemotePairingLink.compact(address,pairSecret,fingerprint),"address",address,"fingerprint",fingerprint,"expiresAt",pairExpires,"addresses",addresses());
    }
    synchronized Map<String,Object> pairingStatus() {
        String state=pairBusy?"pairing":pairState.equals("waiting")&&System.currentTimeMillis()>pairExpires?"expired":pairState;
        return Map.of("state",state,"expiresAt",pairExpires,"deviceName",pairedName);
    }
    static String bearer(HttpExchange e){String a=e.getRequestHeaders().getFirst("Authorization");return a!=null&&a.startsWith("Bearer ")?a.substring(7):"";}
    String device(String token){String digest=RemoteManager.hash(token.getBytes(StandardCharsets.UTF_8));synchronized(manager){for(var d:manager.devices.values())if(MessageDigest.isEqual(digest.getBytes(StandardCharsets.UTF_8),RemoteManager.str(d,"tokenHash").getBytes(StandardCharsets.UTF_8)))return RemoteManager.str(d,"id");}return "";}
    Map<String,Object> body(HttpExchange e)throws IOException{byte[] bytes=e.getRequestBody().readNBytes(16385);if(bytes.length>16384)throw new IllegalArgumentException("请求过大");return bytes.length==0?Map.of():RemoteManager.JSON.readValue(bytes,Map.class);}
    void reply(HttpExchange e,int status,Object value)throws IOException{RemoteRuntime.reply(e,status,value);}
    void denied(HttpExchange e,int status,String message)throws IOException{reply(e,status,Map.of("error",message));}
    Map<String,Object> publicStatus(RemoteManager.Project p) {
        Map<String,Object> value = new LinkedHashMap<>(manager.status(p));
        for(String key:List.of("root","build","engine","builtStamp")) value.remove(key);
        return value;
    }
    private void route(HttpExchange e)throws IOException{
        InetAddress peer=e.getRemoteAddress().getAddress();
        if(!peer.isLoopbackAddress()&&!peer.isSiteLocalAddress()&&!peer.isLinkLocalAddress()){denied(e,403,"仅允许局域网连接");return;}
        if(e.getRequestHeaders().containsKey("Origin")){denied(e,403,"请使用 Remote 客户端连接");return;}
        if(!requests.tryAcquire()){denied(e,429,"请求过多，请稍后重试");return;}
        try{
            String path=e.getRequestURI().getPath(),method=e.getRequestMethod(),token=bearer(e);
            if(path.equals("/pair")&&method.equals("POST")){pair(e);return;}
            String device=device(token);
            if(path.equals("/games")&&method.equals("GET")){
                if(device.isEmpty()){denied(e,403,"请先扫码配对此电脑");return;}
                seen.put(device,System.nanoTime());
                reply(e,200,Map.of("host_id",manager.hostId,"computer",manager.computer,"protocol",2,"games",manager.all().stream().filter(p->p.shared).map(p->{Map<String,Object>s=new LinkedHashMap<>(manager.status(p));s.remove("root");s.remove("build");s.remove("engine");s.remove("builtStamp");return s;}).toList()));return;
            }
            if(!path.matches("/games/[a-zA-Z0-9-]+/[a-z]+")){denied(e,404,"接口不存在");return;}
            String[] parts=path.split("/");RemoteManager.Project p=manager.project(parts[2]);String action=parts[3];
            synchronized(p){if(!p.shared){denied(e,403,"此项目已取消共享");return;}}
            boolean control;
            synchronized(p){control=!token.isEmpty()&&token.equals(p.controlToken)&&p.controlUntil>System.nanoTime();if(control)p.controlUntil=System.nanoTime()+10_000_000_000L;}
            if(device.isEmpty()&&!control){denied(e,403,"设备授权或控制权已失效，请重新连接");return;}
            if(!device.isEmpty())seen.put(device,System.nanoTime());
            if(action.equals("status")&&method.equals("GET")){reply(e,200,publicStatus(p));return;}
            if(action.equals("claim")&&method.equals("POST")&&!device.isEmpty()){claim(e,p,device);return;}
            if(action.equals("start")&&method.equals("POST")&&!device.isEmpty()){manager.prepare(p,true,false);reply(e,202,manager.status(p));return;}
            if(action.equals("release")&&method.equals("POST")&&control){synchronized(p){if(!token.equals(p.controlToken)){denied(e,403,"控制权已失效");return;}manager.release(p);}reply(e,200,Map.of("ok",true));return;}
            if(action.equals("stop")&&method.equals("POST")){
                synchronized(p){if(!p.controller.isEmpty()&&!token.equals(p.controlToken)){denied(e,409,"其他设备正在操作，请先接管");return;}manager.stop(p);}
                reply(e,200,publicStatus(p));return;
            }
            if(action.equals("thumbnail")&&method.equals("GET")){proxy(e,p,"/thumbnail","GET",null,false);return;}
            if(!control){denied(e,409,"请先取得此游戏的控制权");return;}
            if(action.equals("device")&&method.equals("POST")){
                Map<String,Object> request=new LinkedHashMap<>(body(e));request.keySet().retainAll(Set.of("width","height","top","right","bottom","left"));
                synchronized(p){if(p.worker==null||!token.equals(p.controlToken)){denied(e,409,"游戏未运行或控制权已变化");return;}p.worker.release();request.put("command","device");request.put("name","device.png");try{p.worker.call(request);p.worker.call(Map.of("command","status"));}finally{p.worker.lease(p.controlToken);}reply(e,200,publicStatus(p));}return;
            }
            if(action.equals("input")&&method.equals("POST")){
                byte[] input=e.getRequestBody().readNBytes(6);if(input.length!=5)throw new IllegalArgumentException("输入格式错误");
                String revision=e.getRequestHeaders().getFirst("X-Defold-Device-Revision");
                synchronized(p){if(p.worker==null){denied(e,409,"游戏未运行");return;}if(!Objects.toString(p.worker.status.get("device_revision"),"").equals(revision)){denied(e,409,"画面尺寸已变化，请重新连接");return;}}
                proxy(e,p,"/input","POST",input,false);return;
            }
            if((action.equals("video")||action.equals("stream"))&&method.equals("GET")){
                String query=e.getRequestURI().getRawQuery();if(query!=null&&!query.matches("resolution=(540p|720p|1080p|original)"))throw new IllegalArgumentException("画质参数错误");
                proxy(e,p,"/"+action+(query==null?"":"?"+query),"GET",null,true);return;
            }
            denied(e,404,"接口不存在");
        }catch(IllegalArgumentException ex){denied(e,400,ex.getMessage());}
        catch(Exception ex){try{denied(e,503,Objects.toString(ex.getMessage(),"连接失败"));}catch(IOException ignored){e.close();}}
        finally{requests.release();}
    }
    private void pair(HttpExchange e)throws Exception{
        Map<String,Object> values=body(e);String candidate=RemoteManager.str(values,"code"),ip=e.getRemoteAddress().getAddress().getHostAddress();
        synchronized(this){long now=System.currentTimeMillis();long[] a=attempts.computeIfAbsent(ip,k->new long[]{now,0});if(now-a[0]>60000){a[0]=now;a[1]=0;}if(++a[1]>10){denied(e,429,"配对尝试过多，请稍后重试");return;}
            if(now>pairExpires||candidate.isEmpty()||!MessageDigest.isEqual(candidate.getBytes(StandardCharsets.UTF_8),pairSecret.getBytes(StandardCharsets.UTF_8))){denied(e,403,"二维码已过期，请在电脑上刷新");return;}pairSecret="";pairExpires=0;pairBusy=true;pairState="pairing";}
        try {
        String name=RemoteManager.str(values,"name");if(name.isBlank())name="Android Remote";if(name.length()>80)name=name.substring(0,80);
        String id=UUID.randomUUID().toString(),token=secret();
        synchronized(manager){if(manager.devices.size()>=32)throw new IOException("配对设备已达上限，请在电脑端移除旧设备");manager.devices.put(id,new LinkedHashMap<>(Map.of("id",id,"name",name,"tokenHash",RemoteManager.hash(token.getBytes(StandardCharsets.UTF_8)),"pairedAt",Instant.now().toString())));manager.save();}
        reply(e,200,Map.of("token",token,"device_id",id,"host_id",manager.hostId,"computer",manager.computer,"protocol",2));
        synchronized(this){pairState="paired";pairedName=name;}
        } catch(Exception failure) { synchronized(this){pairState="error";}throw failure; }
        finally { synchronized(this){pairBusy=false;} }
    }
    private synchronized void claim(HttpExchange e,RemoteManager.Project p,String device)throws Exception{
        boolean force=Boolean.TRUE.equals(body(e).get("takeover"));
        synchronized(p){if(p.worker==null){denied(e,409,"游戏尚未运行");return;}if(!p.controller.isEmpty()&&!p.controller.equals(device)&&p.controlUntil>System.nanoTime()&&!force){denied(e,409,"其他设备正在操作："+manager.controllerName(p));return;}}
        for(var other:manager.all())synchronized(other){if(other!=p&&other.controller.equals(device))manager.release(other);}
        synchronized(p){manager.release(p);p.controller=device;p.controlToken=secret();p.worker.lease(p.controlToken);p.controlUntil=System.nanoTime()+10_000_000_000L;Map<String,Object> result=publicStatus(p);result.put("token",p.controlToken);reply(e,200,result);}
    }
    private void proxy(HttpExchange client,RemoteManager.Project p,String path,String method,byte[] body,boolean media)throws Exception{
        RemoteManager.Worker worker;String control=bearer(client);
        synchronized(p){worker=p.worker;if(worker==null||!worker.process.isAlive()){denied(client,409,"游戏未运行");return;}}
        HttpURLConnection upstream=worker.connection(path,method);upstream.setRequestProperty("X-Remote-Lease",control);p.connections.add(upstream);p.clients.add(client);
        String revision=client.getRequestHeaders().getFirst("X-Defold-Device-Revision");if(revision!=null)upstream.setRequestProperty("X-Defold-Device-Revision",revision);
        boolean reusable=false;
        try{
            if(body!=null){upstream.setDoOutput(true);upstream.setFixedLengthStreamingMode(body.length);try(var out=upstream.getOutputStream()){out.write(body);}}
            int code=upstream.getResponseCode();String type=upstream.getContentType();
            if(!media){
                byte[] response;
                try(InputStream in=code>=400?upstream.getErrorStream():upstream.getInputStream()){
                    response=in==null?new byte[0]:in.readNBytes(8*1024*1024+1);
                    if(response.length>8*1024*1024)throw new IOException("Remote response too large");
                }
                reusable=true;
                client.getResponseHeaders().set("Content-Type",type==null?"application/octet-stream":type);
                client.getResponseHeaders().set("Cache-Control","no-store");
                client.sendResponseHeaders(code,response.length==0?-1:response.length);
                try(OutputStream out=client.getResponseBody()){out.write(response);}
                return;
            }
            client.getResponseHeaders().set("Content-Type",type==null?"application/octet-stream":type);client.getResponseHeaders().set("Cache-Control","no-store");client.sendResponseHeaders(code,0);
            try(InputStream in=code>=400?upstream.getErrorStream():upstream.getInputStream();OutputStream out=client.getResponseBody()){
                if(in==null)return;byte[] bytes=new byte[65536];for(int n;(n=in.read(bytes))>=0;){
                    synchronized(p){if(worker!=p.worker||!p.shared)break;if(media){if(!control.equals(p.controlToken))break;p.controlUntil=System.nanoTime()+10_000_000_000L;}}
                    writing.put(client,System.nanoTime());try{out.write(bytes,0,n);out.flush();}finally{writing.remove(client);}
                }
            }
        }finally{writing.remove(client);p.connections.remove(upstream);p.clients.remove(client);if(!reusable)upstream.disconnect();client.close();}
    }
    @Override public void close(){server.stop(0);watchdog.shutdownNow();executor.shutdownNow();}
}
