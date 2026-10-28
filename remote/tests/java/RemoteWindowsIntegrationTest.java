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

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.Parent;
import javafx.stage.Stage;
import java.nio.file.*;
import java.nio.*;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.codehaus.jackson.map.ObjectMapper;

/** Dedicated headless JavaFX host with a real hidden engine; never opens a user project. */
public class RemoteWindowsIntegrationTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static <T>T fx(Callable<T> action)throws Exception {
        FutureTask<T> task=new FutureTask<>(action);Platform.runLater(task);return task.get(20,TimeUnit.SECONDS);
    }
    private static HttpResponse<byte[]> request(HttpClient http,String base,String route,String token,byte[] body)throws Exception {
        var b=HttpRequest.newBuilder(URI.create(base+route)).timeout(java.time.Duration.ofSeconds(10));
        if(token!=null)b.header("Authorization","Bearer "+token);
        if(body!=null)b.POST(HttpRequest.BodyPublishers.ofByteArray(body));
        return http.send(b.build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    private static void pointer(HttpClient http,String base,String token,int x,int y,boolean down)throws Exception {
        byte[] packet=ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).putShort((short)x).putShort((short)y).put((byte)(down?1:0)).array();
        if(request(http,base,"/input",token,packet).statusCode()!=204)throw new AssertionError("Input rejected");
    }
    private static void awaitCallbacks(List<String> logs,int count)throws Exception {
        long deadline=System.nanoTime()+3_000_000_000L;
        synchronized(logs) {
            while(logs.stream().filter(s->s.contains("GAME_INPUT")).count()<count) {
                long remaining=deadline-System.nanoTime();
                if(remaining<=0)throw new AssertionError("Missing game callback "+count);
                TimeUnit.NANOSECONDS.timedWait(logs,remaining);
            }
        }
    }
    // Verifies real Remote HTTP streaming and Lua input callbacks, not just HTTP acceptance.
    public static void main(String[] args)throws Exception {
        Path project=Path.of(args[0]).toAbsolutePath(), output=Path.of(args[2]).toAbsolutePath();Files.createDirectories(output);
        boolean baseline=args.length>3&&args[3].equals("baseline");
        boolean embedded=args.length>3&&args[3].equals("embedded");
        boolean hidden=embedded||(args.length>3&&args[3].equals("hidden"));
        int frameCount=args.length>4?Integer.parseInt(args[4]):240;
        Files.createDirectories(project.resolve(".internal"));
        Files.writeString(project.resolve(".internal/game-simulator.properties"),
            "enabled="+!embedded+"\nname=Remote Windows test\nwidth=1920\nheight=1080\ntop=0\nright=0\nbottom=0\nleft=0\nradius=0\ncutoutWidth=0\ncutoutHeight=0\ncutoutTop=0\n");
        Platform.startup(()->Platform.setImplicitExit(false));
        Stage[] stage=new Stage[1];
        GamePreview view=fx(()->{
            var v=new GamePreview(project.toString(),()->{},()->{},(n,k)->{});
            stage[0]=new Stage();stage[0].setScene(new Scene((Parent)v.getNode(),800,500));stage[0].show();return v;
        });
        var field=GamePreview.class.getDeclaredField("remote");field.setAccessible(true);
        var remote=(RemoteGameServer.Game)field.get(view);
        if (!baseline) RemoteStreamLifecycleTest.verify(remote);
        if(hidden)fx(()->{stage[0].getScene().setRoot(new javafx.scene.layout.Pane());return null;});
        var logs=new CopyOnWriteArrayList<String>();
        try(var session=fx(()->GamePreview.prepare(project.toString(),true))) {
            if(session.isSimulator()==embedded)throw new AssertionError("Wrong preview mode");
            if(session.getPath()==null)throw new AssertionError("Embedded Remote lacks capture mapping");
            var command=List.of(Path.of(args[1]).toAbsolutePath().toString(),"--config=display.vsync=0",
                "--config=display.update_frequency=60","--config=display.focus_on_show=0","--config=display.high_dpi=0",
                "--config=display.window_width=1920","--config=display.window_height=1080",
                "--config=engine.run_while_iconified=1","--config=profiler.remotery_port=0","--graphics-adapter=opengl",
                "build/default/game.projectc");
            Process process=WindowsGameProcess.start(command,project.toFile(),Map.of("DM_EDITOR_GAME_FILE",session.getPath(),
                "DM_EDITOR_GAME_PARENT",Long.toString(ProcessHandle.current().pid()),"DM_EDITOR_AUTOMATION","1","DM_SERVICE_PORT","dynamic","DM_WINDOW_STATE","",
                "DM_EDITOR_GAME_CAPTURE_ONLY",embedded?"1":"0"));
            session.attach(process);
            Thread reader=Thread.ofPlatform().daemon().start(()->{
                try(var r=new BufferedReader(new InputStreamReader(process.getInputStream()));var log=Files.newBufferedWriter(output.resolve("engine.log"))) {
                    for(String line;(line=r.readLine())!=null;){synchronized(logs){logs.add(line);logs.notifyAll();}log.write(line+"\n");log.flush();}
                }catch(IOException ignored){}
            });
            URI address=URI.create(remote.getAddress());String base="http://127.0.0.1:"+address.getPort()+address.getPath();
            HttpClient http=HttpClient.newHttpClient();
            var pair=request(http,base,"/pair",null,("{\"code\":\""+remote.getCode()+"\"}").getBytes());
            String token=JSON.readTree(pair.body()).get("token").asText();
            List<Map<String,Object>> results=new ArrayList<>();
            int expectedCallbacks=hidden?4:8;
            for(String mode:baseline?List.of("jpeg"):hidden&&!embedded?List.of("h264"):List.of("jpeg","h264")) {
                String route=mode.equals("jpeg")?"/stream?resolution=1080p":"/video?resolution=1080p";
                HttpURLConnection response=(HttpURLConnection)URI.create(base+route).toURL().openConnection();
                response.setConnectTimeout(10000);response.setReadTimeout(10000);
                response.setRequestProperty("Authorization","Bearer "+token);
                if(response.getResponseCode()!=200)throw new AssertionError("Stream "+response.getResponseCode());
                List<Long> times=new ArrayList<>();Set<Integer> sequences=new HashSet<>();long bytes=0;
                try(var input=new DataInputStream(response.getInputStream());var video=Files.newOutputStream(output.resolve(mode+".stream"))) {
                    if(mode.equals("h264")) {int w=input.readInt(),h=input.readInt();if(w!=(embedded?640:1920)||h!=(embedded?360:1080))throw new AssertionError(w+"x"+h);}
                    while(times.size()<frameCount) {
                        int size=input.readInt(),sequence=input.readInt();if(size==0)continue;
                        if(size<0||size>24*1024*1024)throw new AssertionError("Packet size");
                        byte[] packet=input.readNBytes(size);if(packet.length!=size)throw new EOFException();
                        video.write(packet);bytes+=size;times.add(System.nanoTime());
                        if(!sequences.add(sequence))throw new AssertionError("Repeated frame");
                        int count=times.size();
                        if(count==30)pointer(http,base,token,16384,16384,true);
                        if(count==35)pointer(http,base,token,49151,49151,true);
                        if(count==40)pointer(http,base,token,49151,49151,false);
                        if(count==50&&!baseline)fx(()->{stage[0].getScene().setRoot(new javafx.scene.layout.Pane());return null;});
                        if(count==70)pointer(http,base,token,32768,32768,true);
                        // Lease expiry must release the held pointer without an up packet.
                        if(count==160&&!baseline&&!hidden)fx(()->{stage[0].getScene().setRoot((Parent)view.getNode());return null;});
                    }
                }finally{response.disconnect();}
                List<Double> intervals=new ArrayList<>();for(int i=1;i<times.size();i++)intervals.add((times.get(i)-times.get(i-1))/1e6);
                Collections.sort(intervals);
                double seconds=(times.getLast()-times.getFirst())/1e9;
                var result=new LinkedHashMap<String,Object>();result.put("mode",mode);result.put("frames",times.size());
                result.put("fps",(times.size()-1)/seconds);result.put("p95_interval_ms",intervals.get((int)(intervals.size()*.95)));
                result.put("mbps",bytes*8/seconds/1e6);results.add(result);System.out.println(JSON.writeValueAsString(result));
            }
            long callbacks=logs.stream().filter(s->s.contains("GAME_INPUT")).count();
            System.out.println("Lua input callbacks: "+callbacks);
            if(embedded)expectedCallbacks*=2;
            if(!baseline&&callbacks<expectedCallbacks)throw new AssertionError("Missing Lua press/release callbacks: "+callbacks);
            if(!baseline) {
                var inputLines=logs.stream().filter(s->s.contains("GAME_INPUT")).toList();
                int[][] expected={{160,270},{480,90},{320,180},{320,180}};
                for(int i=0;i<expectedCallbacks;i++) {
                    String[] fields=inputLines.get(i).split("\t");
                    if(!fields[2].equals(i%2==0?"true":"false") || !fields[3].equals(i%2==0?"false":"true")
                        || Math.abs(Double.parseDouble(fields[4])-expected[i%4][0])>1
                        || Math.abs(Double.parseDouble(fields[5])-expected[i%4][1])>1)throw new AssertionError(inputLines.get(i));
                }
                // Closing a stream while the Game panel is detached must release its pointer.
                HttpURLConnection connection=(HttpURLConnection)URI.create(base+"/video?resolution=720p").toURL().openConnection();
                connection.setReadTimeout(10000);connection.setRequestProperty("Authorization","Bearer "+token);
                try(var input=new DataInputStream(connection.getInputStream())) {
                    if(input.readInt()!=(embedded?640:1280)||input.readInt()!=(embedded?360:720))throw new AssertionError("Resolution switch");
                    int size=input.readInt();input.readInt();input.readNBytes(size);
                    fx(()->{stage[0].getScene().setRoot(new javafx.scene.layout.Pane());return null;});
                    pointer(http,base,token,32768,32768,true);
                    pointer(http,base,token,32768,32768,false);
                    awaitCallbacks(logs,expectedCallbacks+2);
                    pointer(http,base,token,32768,32768,true);awaitCallbacks(logs,expectedCallbacks+3);
                }finally{connection.disconnect();}
                awaitCallbacks(logs,expectedCallbacks+4);
                // Reconnect and switch resolutions repeatedly; each decoder must start from a fresh IDR.
                for (int retry=0; retry<6; retry++) {
                    String resolution=retry%2==0?"540p":"720p";
                    HttpURLConnection reconnect=(HttpURLConnection)URI.create(base+"/video?resolution="+resolution).toURL().openConnection();
                    reconnect.setConnectTimeout(5000); reconnect.setReadTimeout(5000);
                    reconnect.setRequestProperty("Authorization","Bearer "+token);
                    try(var input=new DataInputStream(reconnect.getInputStream())) {
                        int width=input.readInt(),height=input.readInt();
                        if(width!=(embedded?640:retry%2==0?960:1280)||height!=(embedded?360:retry%2==0?540:720))throw new AssertionError("Reconnect dimensions");
                        int size=input.readInt();input.readInt();
                        if(size<=0||size>8*1024*1024)throw new AssertionError("Reconnect packet size");
                        byte[] frame=input.readNBytes(size);
                        boolean idr=false,sps=false,pps=false;
                        for(int i=0;i+4<frame.length;i++)if(frame[i]==0&&frame[i+1]==0&&frame[i+2]==0&&frame[i+3]==1){
                            int type=frame[i+4]&31;idr|=type==5;sps|=type==7;pps|=type==8;
                        }
                        if(!idr||!sps||!pps)throw new AssertionError("Reconnect is not independently decodable");
                        if(request(http,base,"/status",token,null).statusCode()!=200)throw new AssertionError("Control blocked by video");
                    } finally { reconnect.disconnect(); }
                }
            }
            JSON.writeValue(output.resolve("results.json").toFile(),Map.of("streams",results,"input_callbacks",logs.stream().filter(s->s.contains("GAME_INPUT")).count(),"baseline",baseline,
                "disconnect_release_verified",!baseline,"reconnects_verified",baseline?0:6,"preview_mode",embedded?"embedded":"simulator",
                "transport","loopback, real hidden Windows engine and headless JavaFX; not phone/Wi-Fi displayed FPS"));
        }finally{fx(()->{view.close();stage[0].close();return null;});Platform.exit();}
    }
}
