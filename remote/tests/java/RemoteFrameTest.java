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
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.concurrent.FutureTask;
import javax.imageio.ImageIO;
import org.codehaus.jackson.map.ObjectMapper;

public final class RemoteFrameTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static <T> T fx(java.util.concurrent.Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(20, java.util.concurrent.TimeUnit.SECONDS);
    }
    private static BufferedImage pattern(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.RED); graphics.fillRect(0, 0, width/2, height/2);
            graphics.setColor(Color.GREEN); graphics.fillRect(width/2, 0, width-width/2, height/2);
            graphics.setColor(Color.BLUE); graphics.fillRect(0, height/2, width/2, height-height/2);
            graphics.setColor(Color.YELLOW); graphics.fillRect(width/2, height/2, width-width/2, height-height/2);
            graphics.setColor(Color.WHITE); graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(12, width/28)));
            graphics.drawString("TOP LEFT  ->", width/12, height/4);
            graphics.setColor(Color.BLACK); graphics.drawString("TOP RIGHT", width*7/12, height/4);
            graphics.setColor(Color.WHITE); graphics.drawString("BOTTOM LEFT", width/12, height*3/4);
            graphics.setColor(Color.BLACK); graphics.drawString("BOTTOM RIGHT", width*7/12, height*3/4);
        } finally { graphics.dispose(); }
        return image;
    }
    private static void publish(GamePreview.Session session, BufferedImage expected, int sequence) throws Exception {
        int width=expected.getWidth(), height=expected.getHeight();
        try (var file=FileChannel.open(Path.of(session.getPath()), StandardOpenOption.READ, StandardOpenOption.WRITE);
             var lock=file.lock()) {
            ByteBuffer data=ByteBuffer.allocate(width*height*4).order(ByteOrder.nativeOrder());
            for(int y=height-1;y>=0;y--)for(int x=0;x<width;x++)data.putInt(expected.getRGB(x,y));
            data.flip(); long offset=GamePreview.HEADER;
            while(data.hasRemaining())offset+=file.write(data,offset);
            ByteBuffer header=ByteBuffer.allocate(44).order(ByteOrder.nativeOrder());
            header.putInt(width).putInt(height).putInt(sequence).putInt(123);
            header.position(40);header.putInt(0);header.flip();file.write(header,4);
        }
    }
    // Exercise the real mapped-frame reader with odd-size landscape/portrait images,
    // every quality, reused buffers and JPEG/thumbnail HTTP output. Guards both axes.
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[0]);Files.createDirectories(output);
        Path project=output.toAbsolutePath().resolve("project");Files.createDirectories(project.resolve(".internal"));
        Files.writeString(project.resolve(".internal/game-simulator.properties"),
            "enabled=true\nname=Remote orientation test\nwidth=2048\nheight=2048\ntop=0\nright=0\nbottom=0\nleft=0\nradius=0\ncutoutWidth=0\ncutoutHeight=0\ncutoutTop=0\n");
        Platform.startup(() -> {});
        GamePreview view=fx(() -> new GamePreview(project.toString(),()->{},()->{},(node,key)->{}));
        RemoteGameServer.Game game=RemoteGameServer.register(project.toString(),view,()->{});
        try (var session=GamePreview.prepare(project.toString(),true)) {
            int sequence=0;
            for(int[] size:new int[][]{{1921,1081},{1081,1921},{65,67}}) {
                BufferedImage expected=pattern(size[0],size[1]);publish(session,expected,++sequence);
                for(var quality:RemoteStreamQuality.values()) {
                    int[] reuse=new int[size[0]*size[1]];
                    for(int repeat=0;repeat<2;repeat++) {
                        var frame=view.remoteFrame(null,-1,quality,reuse);
                        check(frame!=null,"Frame missing");
                        for(int y=0;y<frame.height;y++)for(int x=0;x<frame.width;x++) {
                            if(frame.rgb[y*frame.width+x]!=expected.getRGB(x*size[0]/frame.width,y*size[1]/frame.height))
                                throw new AssertionError("Orientation at "+x+","+y+" for "+quality+" "+size[0]+"x"+size[1]);
                        }
                        checks++;
                        check(view.remoteFrame(frame.session,frame.sequence,quality)==null,"Duplicate frame");
                        if(repeat==0&&quality==RemoteStreamQuality.SMOOTH) {
                            BufferedImage result=new BufferedImage(frame.width,frame.height,BufferedImage.TYPE_INT_RGB);
                            result.setRGB(0,0,frame.width,frame.height,frame.rgb,0,frame.width);
                            ImageIO.write(result,"png",output.resolve(size[0]+"x"+size[1]+".png").toFile());
                        }
                    }
                }
            }
            BufferedImage expected=pattern(1280,720);publish(session,expected,++sequence);
            URI address=URI.create(game.getAddress());String base="http://127.0.0.1:"+address.getPort()+address.getPath();
            var http=HttpClient.newHttpClient();
            var pair=http.send(HttpRequest.newBuilder(URI.create(base+"/pair")).POST(HttpRequest.BodyPublishers.ofString("{\"code\":\""+game.getCode()+"\"}")).build(),HttpResponse.BodyHandlers.ofString());
            check(pair.statusCode()==200,"Pairing failed");
            String token=new ObjectMapper().readTree(pair.body()).get("token").asText();
            for(String route:new String[]{"frame","thumbnail"}) {
                long deadline=System.nanoTime()+5_000_000_000L;
                HttpResponse<byte[]> response;
                do {
                    response=http.send(HttpRequest.newBuilder(URI.create(base+"/"+route)).header("Authorization","Bearer "+token).build(),HttpResponse.BodyHandlers.ofByteArray());
                    if(response.statusCode()==200)break;
                    Thread.sleep(10);
                } while(System.nanoTime()<deadline);
                check(response.statusCode()==200,"JPEG not ready");
                BufferedImage jpeg=ImageIO.read(new ByteArrayInputStream(response.body()));
                int w=jpeg.getWidth(),h=jpeg.getHeight();
                for(int[] corner:new int[][]{{w/8,h/8,0xff0000},{w*7/8,h/8,0x00ff00},{w/8,h*7/8,0x0000ff},{w*7/8,h*7/8,0xffff00}}) {
                    int actual=jpeg.getRGB(corner[0],corner[1]);
                    for(int shift:new int[]{0,8,16})check(Math.abs(((actual>>shift)&255)-((corner[2]>>shift)&255))<10,"JPEG corner mirrored");
                }
                Files.write(output.resolve(route+".jpg"),response.body());
            }
            System.out.println(checks+" Remote orientation assertions passed");
        } finally {
            game.close();fx(()->{view.close();return null;});Platform.exit();
        }
    }
}
