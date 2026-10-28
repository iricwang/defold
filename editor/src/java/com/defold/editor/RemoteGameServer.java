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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.common.BitMatrix;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import org.codehaus.jackson.map.ObjectMapper;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.ImageWriteParam;
import javax.imageio.IIOImage;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.Preferences;

/** LAN access to existing editor Game sessions. Network workers never touch JavaFX nodes. */
public final class RemoteGameServer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    // Allow brief Wi-Fi stalls, but reconnect before the client's 3.5 second read timeout.
    static final long WRITE_TIMEOUT_NANOS = 1_500_000_000L;
    private static RemoteGameServer instance;
    private final HttpServer http;
    private final ExecutorService network;
    private volatile MulticastSocket discoverySocket;
    private final Map<String, Game> games = new ConcurrentHashMap<>();
    private final ScheduledExecutorService capture = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "remote-capture"));
    private final String computer;
    private final String machineId;
    private final int port;

    private static Thread daemon(Runnable run, String name) { Thread t = new Thread(run, name); t.setDaemon(true); return t; }
    private RemoteGameServer() throws IOException {
        String name;
        try { name = InetAddress.getLocalHost().getHostName(); } catch (IOException e) { name = "Defold"; }
        computer = name;
        Preferences identity = Preferences.userRoot().node("com/defold/remote");
        machineId = identity.get("hostId", UUID.randomUUID().toString());
        identity.put("hostId", machineId);
        HttpServer bound = null;
        for (int p = 8390; p < 8420 && bound == null; ++p) {
            try { bound = HttpServer.create(new InetSocketAddress(p), 16); } catch (BindException occupied) { }
        }
        if (bound == null) throw new IOException("Remote ports 8390–8419 are occupied");
        http = bound; port = http.getAddress().getPort();
        network = new ThreadPoolExecutor(16, 16, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), r -> daemon(r, "remote-http"), new ThreadPoolExecutor.AbortPolicy());
        http.setExecutor(network);
        http.createContext("/", this::handle); http.start();
        capture.scheduleAtFixedRate(this::capture, 0, 16667, TimeUnit.MICROSECONDS);
        daemon(this::discovery, "remote-discovery").start();
    }

    public static synchronized Game register(String project, GamePreview view, Runnable start) {
        try {
            if (instance == null) instance = new RemoteGameServer();
            Game game = instance.new Game(project, view, start);
            instance.games.put(game.id, game); return game;
        } catch (Exception e) { System.err.println("Remote unavailable: " + e.getMessage()); return null; }
    }

    public final class Game implements AutoCloseable {
        final String id, title;
        final GamePreview view;
        final Runnable start;
        final Preferences preferences;
        volatile String code, token;
        volatile boolean enabled;
        volatile long demandUntil, startRequested;
        volatile Frame latest;
        volatile RemoteStreamQuality quality=RemoteStreamQuality.SMOOTH, lastQuality;
        volatile HttpExchange stream, audio;
        volatile long audioWriteStarted;
        volatile boolean videoStream;
        volatile long videoWriteStarted;
        volatile long stalledStreams;
        volatile int lastSequence = -1;
        volatile Object lastSession;
        final ArrayDeque<Long> attempts = new ArrayDeque<>();
        final Object signal = new Object();
        private Game(String project, GamePreview view, Runnable start) throws Exception {
            this.view = view; this.start = start;
            id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((machineId + ":" + project).getBytes(StandardCharsets.UTF_8))).substring(0, 24);
            title = projectTitle(project);
            preferences = Preferences.userRoot().node("com/defold/remote/" + id);
            code = preferences.get("code", "");
            if (!code.matches("[0-9]{8}")) { code = newCode(); preferences.put("code", code); }
            token = preferences.get("token", ""); enabled = preferences.getBoolean("enabled", true);
        }
        public String getId() { return id; }
        public void resetFrames() { latest = null; lastSequence = -1; lastSession = null; }
        public boolean hasStream() { return enabled && stream != null; }
        public String getCode() { return code; }
        public String getLabel() { return enabled ? "Remote · " + code.substring(0,4) + " " + code.substring(4) : "Remote · Off"; }
        public String getAddress() { return "http://" + lanAddress() + ":" + port + "/games/" + id; }
        public String getLink() { return "defoldremote://connect?v=1&address=" + URLEncoder.encode(getAddress(), StandardCharsets.UTF_8) + "&code=" + code; }
        public String getState() {
            if (!enabled) return "offline";
            if (view.remoteRunning()) { startRequested = 0; return "running"; }
            if (startRequested > 0 && System.nanoTime() - startRequested < 30_000_000_000L) return "starting";
            return "stopped";
        }
        private Map<String, Object> info() {
            Map<String,Object> result=new LinkedHashMap<>(Map.of("id", id, "name", title, "computer", computer, "state", getState(), "path", "/games/" + id,
                "protocol", 2, "port", port, "max_fps", 60, "host_id", machineId));
            result.put("audio",com.sun.jna.Platform.isMac());
            result.put("video_codecs", RemoteVideoEncoder.available() ? List.of("h264", "jpeg") : List.of("jpeg"));
            result.put("resolutions",List.of("540p","720p","1080p","original"));result.put("stream_resolution",quality.id);
            result.put("stream_codec", stream == null ? "none" : videoStream ? "h264" : "jpeg");
            result.put("stream_stall_disconnects", stalledStreams);
            return result;
        }
        private boolean authorized(String authorization) {
            String current = token;
            return enabled && !current.isEmpty() && MessageDigest.isEqual(("Bearer " + current).getBytes(StandardCharsets.UTF_8), authorization.getBytes(StandardCharsets.UTF_8));
        }
        private synchronized String pair(String candidate) throws IOException {
            long now = System.nanoTime();
            while (!attempts.isEmpty() && now - attempts.peekFirst() > 30_000_000_000L) attempts.removeFirst();
            if (attempts.size() >= 5) throw new RateLimit();
            attempts.addLast(now);
            if (!MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8))) return null;
            closeConnections();
            byte[] random = new byte[32]; RANDOM.nextBytes(random);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            preferences.put("token", token); return token;
        }
        private synchronized void closeStream() {
            HttpExchange previous = stream;
            stream = null; videoStream = false; videoWriteStarted = 0;
            // HttpExchange.close can wait for a blocked writer. Never stall the capture/FX thread on it.
            if (previous != null) Thread.ofVirtual().name("remote-stream-close").start(previous::close);
            view.remoteRelease();
            synchronized (signal) { signal.notifyAll(); }
        }
        private synchronized void closeAudio() {
            HttpExchange old = audio; audio = null; audioWriteStarted = 0;
            if (old != null) Thread.ofVirtual().name("remote-audio-close").start(old::close);
        }
        synchronized boolean beginVideoWrite(HttpExchange owner) {
            if (stream != owner) return false;
            videoWriteStarted = System.nanoTime();
            return true;
        }
        synchronized void endVideoWrite(HttpExchange owner) {
            if (stream == owner) videoWriteStarted = 0;
        }
        synchronized void expireStreams(long now) {
            if (audioWriteStarted != 0 && now - audioWriteStarted > 250_000_000L) closeAudio();
            if (videoWriteStarted != 0 && now - videoWriteStarted > WRITE_TIMEOUT_NANOS) {
                stalledStreams++;
                closeStream();
            }
        }
        private void closeConnections(){closeStream();closeAudio();}
        public void showPairing(javafx.stage.Window owner, Button button) {
            Dialog<Void> dialog = new Dialog<>(); dialog.setTitle("Remote · " + title);
            if (owner != null) { dialog.initOwner(owner); dialog.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets()); dialog.getDialogPane().getStylesheets().addAll(owner.getScene().getRoot().getStylesheets()); }
            dialog.initModality(Modality.NONE);
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            Label name = new Label(title), pairing = new Label(), address = new Label();
            pairing.setStyle("-fx-font-size: 22; -fx-font-family: monospace;"); address.setWrapText(true); address.setMaxWidth(300);
            ImageView qr = new ImageView(); qr.setFitWidth(256); qr.setFitHeight(256);
            Runnable refresh = () -> {
                button.setText(getLabel());
                pairing.setText(code.substring(0, 4) + " " + code.substring(4)); address.setText(getAddress());
                try {
                    BitMatrix matrix = new QRCodeWriter().encode(getLink(), BarcodeFormat.QR_CODE, 256, 256);
                    WritableImage image = new WritableImage(256, 256);
                    for (int y=0;y<256;y++) for (int x=0;x<256;x++) image.getPixelWriter().setArgb(x,y,matrix.get(x,y)?0xff000000:0xffffffff);
                    qr.setImage(image);
                } catch (Exception e) { address.setText("QR error: " + e.getMessage()); }
            };
            CheckBox sharing = new CheckBox("启用局域网 Remote"); sharing.setSelected(enabled);
            sharing.setOnAction(e -> { enabled = sharing.isSelected(); preferences.putBoolean("enabled", enabled); if (!enabled) closeConnections(); refresh.run(); });
            Button copy = new Button("复制连接信息"), rotate = new Button("刷新配对码"), revoke = new Button("撤销手机连接");
            copy.setOnAction(e -> { ClipboardContent content = new ClipboardContent(); content.putString(getLink()); Clipboard.getSystemClipboard().setContent(content); });
            rotate.setOnAction(e -> { code = newCode(); preferences.put("code", code); refresh.run(); });
            revoke.setOnAction(e -> { token = ""; preferences.remove("token"); closeConnections(); });
            Label connection = new Label();
            javafx.animation.Timeline state = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), e -> connection.setText(hasStream() ? "手机已连接" : "等待手机扫码连接")));
            connection.setText(hasStream() ? "手机已连接" : "等待手机扫码连接");
            state.setCycleCount(javafx.animation.Animation.INDEFINITE);
            dialog.setOnHidden(e -> state.stop());
            VBox box = new VBox(12, name, sharing, qr, pairing, address, connection, copy, rotate, revoke);
            box.setStyle("-fx-padding: 18;"); box.setMaxWidth(330);
            dialog.getDialogPane().setContent(box); refresh.run(); dialog.show(); state.play();
        }
        @Override public void close() {
            enabled = false; closeConnections(); games.remove(id, this); synchronized (signal) { signal.notifyAll(); }
            synchronized (RemoteGameServer.class) {
                if (games.isEmpty() && instance == RemoteGameServer.this) {
                    http.stop(0); capture.shutdownNow(); network.shutdownNow();
                    if (discoverySocket != null) discoverySocket.close();
                    instance = null;
                }
            }
        }
    }

    private record Frame(int sequence, byte[] bytes, RemoteStreamQuality quality) { }
    private static final class RateLimit extends IOException { }
    private static String newCode() { return String.format(Locale.ROOT, "%08d", RANDOM.nextInt(100000000)); }
    private static String projectTitle(String project) {
        try {
            boolean section = false;
            for (String line : Files.readAllLines(Path.of(project, "game.project"))) {
                String text = line.trim();
                if (text.startsWith("[")) section = text.equals("[project]");
                else if (section && text.matches("title\\s*=.*")) return text.substring(text.indexOf('=')+1).trim();
            }
        } catch (IOException ignored) { }
        return Path.of(project).getFileName().toString();
    }
    private static String lanAddress() {
        try {
            for (NetworkInterface adapter : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!adapter.isUp() || adapter.isLoopback() || adapter.isPointToPoint()) continue;
                for (InetAddress address : Collections.list(adapter.getInetAddresses()))
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) return address.getHostAddress();
            }
        } catch (SocketException ignored) { }
        return "127.0.0.1";
    }
    private List<Map<String, Object>> catalog() { return games.values().stream().filter(g -> g.enabled).map(Game::info).toList(); }
    private void discovery() {
        try (MulticastSocket socket = new MulticastSocket(null)) {
            discoverySocket = socket;
            socket.setReuseAddress(true); socket.bind(new InetSocketAddress(8391));
            InetSocketAddress group = new InetSocketAddress("239.255.83.91", 8391);
            for (NetworkInterface adapter : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (adapter.isUp() && !adapter.isLoopback()) try { socket.joinGroup(group, adapter); } catch (IOException ignored) { }
            }
            while (true) {
                DatagramPacket packet = new DatagramPacket(new byte[128],128); socket.receive(packet);
                String query = new String(packet.getData(),0,packet.getLength(),StandardCharsets.US_ASCII);
                if (!query.equals("DEFOLD_REMOTE_DISCOVER_1")) continue;
                byte[] response = JSON.writeValueAsBytes(Map.of("service","defold-remote","protocol",2,"name",computer,"port",port,"games",catalog()));
                if (response.length <= 60000) socket.send(new DatagramPacket(response,response.length,packet.getSocketAddress()));
            }
        } catch (IOException e) { System.err.println("Remote discovery unavailable: " + e.getMessage()); }
    }
    private void capture() {
        for (Game game : games.values()) {
            if (!game.enabled) continue;
            long now = System.nanoTime();
            // Inspect and detach the same stream under one lock; an old timeout cannot close a replacement.
            game.expireStreams(now);
            if ((game.stream == null || game.videoStream) && now > game.demandUntil) continue;
            try {
                RemoteStreamQuality quality=game.videoStream ? RemoteStreamQuality.SMOOTH : game.quality;
                GamePreview.RemoteFrame raw = game.view.remoteFrame(game.lastQuality==quality?game.lastSession:null, game.lastSequence, quality);
                if (raw == null) continue;
                
                BufferedImage image = new BufferedImage(raw.width, raw.height, BufferedImage.TYPE_INT_RGB);
                System.arraycopy(raw.rgb,0,((java.awt.image.DataBufferInt)image.getRaster().getDataBuffer()).getData(),0,raw.rgb.length);
                byte[] bytes=jpeg(image,quality.jpeg);
                if(!game.videoStream && game.quality!=quality)continue;
                if(game.videoStream) game.demandUntil=0;
                game.lastSession=raw.session;game.lastSequence=raw.sequence;game.lastQuality=quality;
                game.latest = new Frame(raw.sequence, bytes, quality);
                synchronized (game.signal) { game.signal.notifyAll(); }
            } catch (Exception e) { /* A closing game may invalidate its shared frame between ticks. */ }
        }
    }
    private static byte[] jpeg(BufferedImage image,float quality) throws IOException {
        ImageWriter writer=ImageIO.getImageWritersByFormatName("jpeg").next();ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(MemoryCacheImageOutputStream output=new MemoryCacheImageOutputStream(bytes)){
            writer.setOutput(output);ImageWriteParam options=writer.getDefaultWriteParam();options.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);options.setCompressionQuality(quality);
            writer.write(null,new IIOImage(image,null,null),options);output.flush();return bytes.toByteArray();
        }finally{writer.dispose();}
    }
    private static byte[] thumbnail(byte[] bytes) throws IOException {
        BufferedImage source=ImageIO.read(new ByteArrayInputStream(bytes));if(source==null)return bytes;
        double scale=Math.min(1.0,400.0/Math.max(source.getWidth(),source.getHeight()));
        BufferedImage small=new BufferedImage(Math.max(1,(int)(source.getWidth()*scale)),Math.max(1,(int)(source.getHeight()*scale)),BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics=small.createGraphics();try{graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);graphics.drawImage(source,0,0,small.getWidth(),small.getHeight(),null);}finally{graphics.dispose();}
        return jpeg(small,.82f);
    }
    private static void reply(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = body instanceof byte[] b ? b : JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Cache-Control","no-store");
        exchange.getResponseHeaders().set("Content-Type",body instanceof byte[] ? "image/jpeg" : "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status,status==204 ? -1 : bytes.length);
        try (OutputStream output=exchange.getResponseBody()) { if (status!=204) output.write(bytes); }
    }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath(), method = exchange.getRequestMethod();
            if (path.equals("/games") && method.equals("GET")) { reply(exchange,200,Map.of("games",catalog(),"protocol",2)); return; }
            String[] parts=path.split("/");
            if (parts.length!=4 || !parts[1].equals("games")) { reply(exchange,404,Map.of("error","Unknown game endpoint")); return; }
            Game game=games.get(parts[2]); String action=parts[3];
            if (game==null || !game.enabled) { reply(exchange,404,Map.of("error","Game is offline")); return; }
            String expected = switch(action) { case "pair","start","stop","input" -> "POST"; case "status","frame","thumbnail","stream","video","audio" -> "GET"; default -> ""; };
            if (expected.isEmpty()) { reply(exchange,404,Map.of("error","Unknown action")); return; }
            if (!method.equals(expected)) { reply(exchange,405,Map.of("error","Incorrect HTTP method")); return; }
            String authorization=Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"),"");
            if (!action.equals("pair") && !game.authorized(authorization)) { reply(exchange,403,Map.of("error","请在电脑 Game 面板查看配对码")); return; }
            byte[] body=exchange.getRequestBody().readNBytes(1025);
            if (body.length>1024) { reply(exchange,413,Map.of("error","Request too large")); return; }
            switch(action) {
                case "pair" -> {
                    Map<?,?> input=JSON.readValue(body,Map.class); Object value=input.get("code");
                    if (!(value instanceof String)) { reply(exchange,400,Map.of("error","请输入八位配对码")); return; }
                    String token=game.pair((String)value);
                    reply(exchange,token==null?403:200,token==null?Map.of("error","配对码不正确"):Map.of("token",token,"name",game.title,"game_id",game.id,"protocol",2));
                }
                case "status" -> reply(exchange,200,game.info());
                case "start" -> {
                    synchronized(game) {
                        if (game.getState().equals("stopped")) { game.startRequested=System.nanoTime(); Platform.runLater(game.start); }
                    }
                    reply(exchange,200,game.info());
                }
                case "stop" -> { game.startRequested=0; game.closeConnections(); Platform.runLater(game.view::stop); reply(exchange,200,Map.of("state","stopping")); }
                case "input" -> {
                    if(body.length!=5 || (body[4]&255)>1) { reply(exchange,400,Map.of("error","Invalid pointer")); return; }
                    if(!game.view.remoteRunning()) { reply(exchange,409,Map.of("error","Game is not running")); return; }
                    boolean accepted=game.view.remotePointer((body[0]&255)|((body[1]&255)<<8),(body[2]&255)|((body[3]&255)<<8),body[4]!=0);
                    reply(exchange,accepted?204:429,new byte[0]);
                }
                case "frame","thumbnail" -> {
                    game.demandUntil=System.nanoTime()+2_000_000_000L;
                    Frame frame=game.latest; reply(exchange,frame==null?204:200,frame==null?new byte[0]:action.equals("thumbnail")?thumbnail(frame.bytes()):frame.bytes());
                }
                case "stream" -> stream(exchange,game,authorization);
                case "video" -> video(exchange,game,authorization);
                case "audio" -> audio(exchange,game,authorization);
            }
        } catch (RateLimit e) { reply(exchange,429,Map.of("error","请等待 30 秒后再尝试配对")); }
        catch (org.codehaus.jackson.JsonProcessingException e) { reply(exchange,400,Map.of("error","Invalid JSON request")); }
        catch (IllegalArgumentException e) { reply(exchange,400,Map.of("error","Invalid request")); }
        catch (IOException e) { exchange.close(); }
    }
    private void stream(HttpExchange exchange, Game game, String authorization) throws IOException {
        RemoteStreamQuality quality=RemoteStreamQuality.SMOOTH;
        String query=exchange.getRequestURI().getRawQuery();
        if(query!=null){if(!query.matches("resolution=(540p|720p|1080p|original)"))throw new IllegalArgumentException("Unsupported stream resolution");quality=RemoteStreamQuality.parse(query.substring(11));}
        synchronized(game) { game.closeStream(); game.quality=quality;game.stream=exchange; }
        exchange.getResponseHeaders().set("Content-Type","application/x-defold-frames");
        exchange.getResponseHeaders().set("Cache-Control","no-store");
        exchange.sendResponseHeaders(200,0);
        Frame previous=null;
        try (DataOutputStream output=new DataOutputStream(exchange.getResponseBody())) {
            while(game.enabled && game.stream==exchange && game.authorized(authorization)) {
                Frame frame;
                synchronized(game.signal) {
                    if(game.latest==previous || (game.latest!=null && game.latest.quality()!=game.quality) || !game.view.remoteRunning()) game.signal.wait(500);
                    frame=game.view.remoteRunning()?game.latest:null;
                    if(frame!=null&&frame.quality()!=game.quality)frame=null;
                }
                if (!game.beginVideoWrite(exchange)) break;
                try {
                    if(frame==null || frame==previous) { output.writeInt(0); output.writeInt(0); }
                    else { output.writeInt(frame.bytes().length); output.writeInt(frame.sequence()); output.write(frame.bytes()); previous=frame; }
                    output.flush();
                } finally { game.endVideoWrite(exchange); }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally { synchronized (game) { if (game.stream == exchange) game.closeStream(); } exchange.close(); }
    }
    private void audio(HttpExchange exchange,Game game,String authorization)throws IOException{
        if(!com.sun.jna.Platform.isMac()){reply(exchange,503,Map.of("error","Audio unavailable"));return;}
        if(!game.view.remoteRunning()){reply(exchange,409,Map.of("error","Game is not running"));return;}
        Path path=game.view.remoteAudioPath();RemoteAudioFile reader;
        try{if(path==null)throw new IOException();reader=new RemoteAudioFile(path);}catch(IOException|OverlappingFileLockException e){reply(exchange,503,Map.of("error","Audio mixer unavailable"));return;}
        synchronized(game){game.closeAudio();game.audio=exchange;}
        try(reader;DataOutputStream output=new DataOutputStream(exchange.getResponseBody())){
            exchange.getResponseHeaders().set("Content-Type","application/x-defold-pcm");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(200,0);
            output.writeInt(RemoteAudioFile.MAGIC);output.writeInt(reader.rate);output.writeInt(2);output.writeInt(16);output.flush();
            long sent=System.nanoTime();
            while(game.enabled&&game.audio==exchange&&game.authorized(authorization)&&game.view.remoteRunning()&&path.equals(game.view.remoteAudioPath())){
                RemoteAudioFile.Packet packet=reader.next();
                if(packet==null&&System.nanoTime()-sent<250_000_000L){Thread.sleep(3);continue;}
                if(game.audio==exchange)game.audioWriteStarted=System.nanoTime();
                output.writeInt(packet==null?0:packet.pcm().length);output.writeInt(packet==null?0:packet.sequence());if(packet!=null)output.write(packet.pcm());output.flush();
                if(game.audio==exchange)game.audioWriteStarted=0;sent=System.nanoTime();
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();}
        finally{synchronized(game){if(game.audio==exchange)game.closeAudio();}exchange.close();}
    }

    private void video(HttpExchange exchange, Game game, String authorization) throws IOException {
        if (!RemoteVideoEncoder.available()) { reply(exchange, 503, Map.of("error", "Hardware video unavailable")); return; }
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || !query.matches("resolution=(540p|720p|1080p|original)")) throw new IllegalArgumentException();
        RemoteStreamQuality quality = RemoteStreamQuality.parse(query.substring(11));
        synchronized (game) { game.closeStream(); game.quality = quality; game.videoStream = true; game.stream = exchange; }
        RemoteVideoEncoder encoder = null;
        try {
            long deadline = System.nanoTime() + 3_000_000_000L;
            GamePreview.RemoteFrame raw;
            do {
                raw = game.view.remoteFrame(null, -1, RemoteStreamQuality.ORIGINAL);
                if (raw != null) break;
                if (game.stream != exchange || System.nanoTime() > deadline) throw new IOException("No game frame");
                Thread.sleep(2);
            } while (true);
            int[] size = quality.dimensions(raw.width, raw.height);
            encoder = new RemoteVideoEncoder(size[0] & ~1, size[1] & ~1);
            int sourceWidth=raw.width, sourceHeight=raw.height; int[] pixels=raw.rgb;
            exchange.getResponseHeaders().set("Content-Type", "application/x-defold-h264");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, 0);
            try (DataOutputStream output = new DataOutputStream(exchange.getResponseBody())) {
                output.writeInt(encoder.width); output.writeInt(encoder.height);
                Object session = null; int sequence = -1;
                long nextFrame=System.nanoTime();
                while (game.enabled && game.stream == exchange && game.authorized(authorization) && game.view.remoteRunning()) {
                    long delay=nextFrame-System.nanoTime();
                    if(delay>0)java.util.concurrent.locks.LockSupport.parkNanos(delay);
                    if (raw == null) raw=game.view.remoteFrame(session,sequence,RemoteStreamQuality.ORIGINAL,pixels);
                    if (raw == null) { Thread.sleep(1); continue; }
                    if (raw.width != sourceWidth || raw.height != sourceHeight || (session != null && raw.session != session)) break;
                    session = raw.session; sequence = raw.sequence;
                    byte[] bytes = encoder.frame(raw.rgb, raw.width, raw.height);
                    // Encoding and writing are serial: at most one compressed frame in our pipeline.
                    if (!game.beginVideoWrite(exchange)) break;
                    try {
                        output.writeInt(bytes.length); output.writeInt(sequence); output.write(bytes); output.flush();
                    } finally { game.endVideoWrite(exchange); }
                    raw = null;
                    nextFrame=Math.max(nextFrame+16_666_667L,System.nanoTime()-16_666_667L);
                }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally {
            if (encoder != null) encoder.close();
            synchronized (game) { if (game.stream == exchange) game.closeStream(); }
            exchange.close();
        }
    }

}
