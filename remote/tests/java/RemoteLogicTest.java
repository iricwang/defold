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

import com.defold.remote.RemoteAddress;
import com.defold.remote.FourFingerGesture;
import com.defold.remote.PointerBuffer;
import com.defold.remote.FrameRate;
import com.defold.remote.RemoteTls;
import com.defold.remote.QRDecoder;
import com.google.zxing.*;
import com.google.zxing.common.*;
import com.google.zxing.qrcode.QRCodeWriter;
import java.net.URLEncoder;

public class RemoteLogicTest {
    static int assertions;
    static void check(boolean value) { assertions++; if (!value) throw new AssertionError("Assertion " + assertions); }
    // Verifies only bounded, unambiguous LAN game links are accepted by all entry points.
    static void addresses() throws Exception {
        check(new RemoteAddress("192.168.1.3:8390/games/demo","1234 5678").base.equals("http://192.168.1.3:8390/games/demo"));
        check(new RemoteAddress("10.1.2.3","").base.equals("http://10.1.2.3:8390"));
        check(new RemoteAddress("https://192.168.1.1:8390","").base.equals("https://192.168.1.1:8390"));
        String[] bad={"http://8.8.8.8:8390","127.0.0.1:0","192.168.1.256:8390","http://u@192.168.1.1:8390","192.168.1.1:8390/games/../secret","192.168.1.1:8390/games/demo?code=x"};
        for(String input:bad){try{new RemoteAddress(input,"");throw new AssertionError(input);}catch(Exception expected){assertions++;}}
        String link="defoldremote://connect?v=1&address="+URLEncoder.encode("http://192.168.1.1:8390/games/demo","UTF-8")+"&code=12345678";
        check(RemoteAddress.fromLink(link).code.equals("12345678"));
        for(String input:new String[]{link+"&code=87654321",link.replace("v=1","v=2"),"https://example.com","defoldremote://connect?v=1"}){
            try{RemoteAddress.fromLink(input);throw new AssertionError(input);}catch(Exception expected){assertions++;}
        }
    }
    // Verifies four-finger recognition, pointer-ID reordering, cancellation and all-up reset without sleeps.
    static void gestures() {
        FourFingerGesture g=new FourFingerGesture();int[] ids={1,2,3,4};float[] x={0,10,20,30},y={0,0,0,0};
        check(!g.update(0,new int[]{1},new float[]{0},new float[]{0}));check(!g.update(100,ids,x,y));
        check(g.update(200,new int[]{4,3,2,1},new float[]{30,20,10,0},new float[]{90,90,90,90}));
        check(!g.update(210,ids,x,new float[]{100,100,100,100}));
        g.update(300,new int[0],new float[0],new float[0]);
        check(!g.update(400,ids,x,y));check(!g.update(500,ids,new float[]{100,110,120,130},new float[]{90,90,90,90}));
        g.reset();g.update(0,new int[]{1},new float[]{0},new float[]{0});check(!g.update(400,ids,x,y));check(!g.update(500,ids,x,new float[]{100,100,100,100}));
        g.reset();g.update(0,ids,x,y);check(!g.update(50,new int[]{1,2,3},new float[]{0,10,20},new float[]{0,0,0}));check(!g.update(100,ids,x,new float[]{100,100,100,100}));
        g.reset();g.update(0,ids,x,y);check(!g.update(1000,ids,x,new float[]{100,100,100,100}));
        g.reset();g.update(0,ids,x,y);check(!g.update(80,new int[]{1,2,3,4,5},new float[]{0,10,20,30,40},new float[]{0,0,0,0,0}));check(!g.update(100,ids,x,new float[]{100,100,100,100}));
        g.reset();g.update(0,ids,x,y);check(!g.update(100,ids,x,new float[]{-100,-100,-100,-100}));
    }
    // Continuous single-finger dragging never opens controls; only four tracked fingers can do so.
    static void singleFinger() {
        FourFingerGesture gesture=new FourFingerGesture();
        for(int step=0;step<1000;step++)check(!gesture.update(step*16,new int[]{7},new float[]{step%300},new float[]{step*2}));
        gesture.update(16000,new int[0],new float[0],new float[0]);
        check(!gesture.update(16010,new int[]{7,8,9,10},new float[]{0,10,20,30},new float[]{0,0,0,0}));
        check(gesture.update(16200,new int[]{7,8,9,10},new float[]{0,10,20,30},new float[]{100,100,100,100}));
    }
    // Slow input transport coalesces motion, keeps down/up edges and can recover from server backpressure.
    static void pointerBackpressure() {
        PointerBuffer buffer=new PointerBuffer();check(buffer.offer(10,20,true));
        for(int i=0;i<10000;i++)check(buffer.offer(i,i,true));
        check(buffer.size()==2);PointerBuffer.Event down=buffer.poll();check(down.down&&down.x==10);
        buffer.retry(down);check(buffer.offer(10000,10000,false));
        check(buffer.size()==2);check(buffer.poll().down);PointerBuffer.Event up=buffer.poll();check(!up.down&&up.x==10000);
        buffer.retry(up);buffer.offer(15,16,true);check(!buffer.poll().down);check(buffer.poll().down);
        buffer.offer(20,20,true);PointerBuffer.Event move=buffer.poll();buffer.offer(30,30,true);buffer.retry(move);check(buffer.size()==1);check(buffer.poll().x==30);
        buffer.cancel(40,40);check(buffer.size()==1);check(!buffer.poll().down);
        boolean overflow=false;for(int i=0;i<100;i++){if(!buffer.offer(i,i,i%2==0)){overflow=true;break;}}
        check(overflow);check(buffer.size()==1);check(!buffer.poll().down);
        buffer.clear();check(buffer.size()==0);check(buffer.offer(1,1,true));check(buffer.poll().down);
    }
    // FPS uses actual presentation timestamps, decays on stalled frames and resets on stop/restart.
    static void frameRate() {
        FrameRate rate=new FrameRate();check(rate.fps(0)==0);
        for(int i=0;i<=60;i++)rate.presented(Math.round(i*1000.0/60));
        check(Math.abs(rate.fps(1000)-60)<.1);check(rate.fps(1600)<30);check(rate.fps(2100)==0);
        rate.reset();check(rate.fps(2100)==0);rate.presented(2200);check(rate.fps(2200)==0);
        rate.presented(2233);check(Math.abs(rate.fps(2233)-30.3)<.1);
    }
    // Verifies the editor-generated QR payload survives the same offline decoder shipped in the APK.
    static void qr() throws Exception {
        String link="defoldremote://connect?v=1&address=http%3A%2F%2F192.168.101.33%3A8390%2Fgames%2Fabc&code=12345678";
        BitMatrix matrix=new QRCodeWriter().encode(link,BarcodeFormat.QR_CODE,256,256);int[] pixels=new int[256*256];
        for(int y=0;y<256;y++)for(int x=0;x<256;x++)pixels[y*256+x]=matrix.get(x,y)?0xff000000:0xffffffff;
        String decoded=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(256,256,pixels)))).getText();
        check(link.equals(decoded));check(RemoteAddress.fromLink(decoded).base.endsWith("/games/abc"));
        String hostLink="defoldremote://connect?v=2&address=https%3A%2F%2F192.168.101.33%3A8390&code=abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG&fingerprint="+new String(new char[64]).replace('\0','a');
        BitMatrix host=new QRCodeWriter().encode(hostLink,BarcodeFormat.QR_CODE,230,230);
        // Manager QR is denser than the legacy game QR; exercise the APK's Y-plane decoder.
        byte[] luminance=new byte[230*230];
        for(int y=0;y<230;y++)for(int x=0;x<230;x++)luminance[y*230+x]=host.get(x,y)?0:(byte)255;
        java.util.Map<DecodeHintType,Object> hints=new java.util.EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS,java.util.Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER,Boolean.TRUE);
        String manager=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new PlanarYUVLuminanceSource(luminance,230,230,0,0,230,230,false))),hints).getText();
        check(hostLink.equals(manager));check(RemoteAddress.fromLink(manager).fingerprint.length()==64);
        check(hostLink.equals(QRDecoder.decode(luminance,230,230)));
        byte[] camera=new byte[1280*720];java.util.Arrays.fill(camera,(byte)180);
        for(int y=0;y<230;y++)System.arraycopy(luminance,y*230,camera,(245+y)*1280+525,230);
        check(hostLink.equals(QRDecoder.decode(camera,1280,720)));
        for(int i=0;i<camera.length;i++)camera[i]=(byte)(255-(camera[i]&255));
        check(hostLink.equals(QRDecoder.decode(camera,1280,720)));
        byte[] rotated=new byte[luminance.length];
        for(int y=0;y<230;y++)for(int x=0;x<230;x++)rotated[x*230+229-y]=luminance[y*230+x];
        check(hostLink.equals(QRDecoder.decode(rotated,230,230)));
        check(RemoteTls.transientFailure(new java.net.SocketTimeoutException()));
        check(!RemoteTls.transientFailure(new javax.net.ssl.SSLHandshakeException("untrusted certificate")));
        check(RemoteTls.transientFailure(new javax.net.ssl.SSLHandshakeException("Connection closed by peer")));
        check(RemoteTls.transientFailure(new javax.net.ssl.SSLHandshakeException("SSL handshake aborted: ssl=0x1234: I/O error during system call, Connection reset by peer")));
        javax.net.ssl.SSLHandshakeException certificate=new javax.net.ssl.SSLHandshakeException("Connection closed by peer");
        certificate.initCause(new java.security.cert.CertificateException("pin mismatch"));
        check(!RemoteTls.transientFailure(certificate));
    }
    public static void main(String[] args) throws Exception {
        addresses();gestures();singleFinger();pointerBackpressure();frameRate();qr();
        if(args.length>0) {
            java.awt.image.BufferedImage image=javax.imageio.ImageIO.read(new java.io.File(args[0]));
            int width=image.getWidth(),height=image.getHeight();
            int[] pixels=image.getRGB(0,0,width,height,null,0,width);
            String value=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width,height,pixels)))).getText();
            RemoteAddress target=RemoteAddress.fromLink(value);
            check(target.base.contains("/games/")||target.fingerprint.length()==64);
            System.out.println("Actual editor/manager QR decoded successfully");
        }
        System.out.println(assertions+" assertions passed");
    }
}
