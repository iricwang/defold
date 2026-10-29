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

import com.defold.remote.QRDecoder;
import com.defold.remote.ScanGeometry;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.NotFoundException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.util.Arrays;

/** Static camera-frame/image decoding and viewport transforms; does not claim physical camera acceptance. */
public final class RemoteScanTest {
    private static int assertions;
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private static void near(float a,float b){check(Math.abs(a-b)<.002,"Coordinate "+a+" != "+b);}
    private static byte[] matrix(BitMatrix qr){
        byte[] result=new byte[qr.getWidth()*qr.getHeight()];
        for(int y=0;y<qr.getHeight();y++)for(int x=0;x<qr.getWidth();x++)result[y*qr.getWidth()+x]=qr.get(x,y)?0:(byte)255;
        return result;
    }
    private static byte[] rotate(byte[] source,int width,int height){
        byte[] result=new byte[source.length];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)result[x*height+height-y-1]=source[y*width+x];
        return result;
    }
    private static byte[] frame(byte[] code,int size,int width,int height,int left,int top){
        byte[] result=new byte[width*height];Arrays.fill(result,(byte)180);
        for(int y=0;y<size;y++)System.arraycopy(code,y*size,result,(y+top)*width+left,size);
        return result;
    }
    private static void geometry(){
        int[][] sizes={{640,480},{1280,720},{1920,1080},{1920,1440},{3840,2160}};
        check(ScanGeometry.choosePreview(sizes,390,844,90)==2,"Clear 1080p portrait preview");
        check(ScanGeometry.choosePreview(sizes,844,390,0)==2,"Clear 1080p landscape preview");
        for(int rotation:new int[]{0,90,180,270})for(boolean mirror:new boolean[]{false,true}){
            float[] center=ScanGeometry.sensorPoint(195,422,1920,1080,rotation,390,844,mirror);
            near(center[0],.5f);near(center[1],.5f);
            int[] crop=ScanGeometry.visibleCrop(1920,1080,rotation,390,844,mirror);
            check(crop[0]>=0&&crop[1]>=0&&crop[0]+crop[2]<=1920&&crop[1]+crop[3]<=1080,"Visible crop stays inside frame");
            check(crop[2]>0&&crop[3]>0,"Visible crop is nonempty");
        }
        float[] portrait=ScanGeometry.sensorPoint(0,0,1920,1080,90,390,844,false);
        near(portrait[0],0);near(portrait[1],.9107425f);
        float[] mirror=ScanGeometry.sensorPoint(0,0,1920,1080,90,390,844,true);
        near(mirror[0],1);near(mirror[1],portrait[1]);
        float[] landscape=ScanGeometry.sensorPoint(0,0,1920,1080,0,844,390,false);
        near(landscape[0],0);near(landscape[1],.0892575f);
        int[] full=ScanGeometry.visibleCrop(1920,1080,90,1080,1920,false);
        check(Arrays.equals(full,new int[]{0,0,1920,1080}),"Matching aspect displays complete frame");
    }
    private static void decoding()throws Exception{
        String oldDense="defoldremote://connect?v=2&address=https%3A%2F%2F192.168.101.33%3A8390&code=abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG&fingerprint="+new String(new char[64]).replace('\0','a');
        String compact="defoldremote://connect?v=3&data="+new String(new char[94]).replace('\0','A');
        for(String text:new String[]{oldDense,compact})for(int size:new int[]{230,320,460}){
            BitMatrix qr=new QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,size,size);
            byte[] code=matrix(qr);
            for(int turn=0;turn<4;turn++){
                check(text.equals(QRDecoder.decode(code,size,size)),"QR rotation "+turn+" size "+size);
                code=rotate(code,size,size);
            }
            byte[] edge=frame(code,size,1280,720,20,110);
            for(int i=0;i<edge.length;i++)edge[i]=(byte)(255-(edge[i]&255));
            check(text.equals(QRDecoder.decode(edge,1280,720)),"Inverted QR outside center crop");
            int[] argb=new int[size*size];
            for(int i=0;i<argb.length;i++){int y=code[i]&255;argb[i]=0xff000000|(y<<16)|(y<<8)|y;}
            check(text.equals(QRDecoder.decode(argb,size,size)),"Album image decoder");
        }
        byte[] code=matrix(new QRCodeWriter().encode(oldDense,BarcodeFormat.QR_CODE,230,230));
        int[] crop=ScanGeometry.visibleCrop(1280,720,0,390,844,false);
        byte[] visible=frame(code,230,1280,720,525,245);
        check(oldDense.equals(QRDecoder.decode(visible,1280,720,crop[0],crop[1],crop[2],crop[3])),"Actual viewport code decodes");
        byte[] hidden=frame(code,230,1280,720,15,245);
        try{QRDecoder.decode(hidden,1280,720,crop[0],crop[1],crop[2],crop[3]);throw new AssertionError("Hidden QR accepted");}
        catch(NotFoundException expected){assertions++;}
        for(int[] invalid:new int[][]{{-1,0,20,20},{0,0,1281,1},{0,0,0,20},{1270,0,20,20}}){
            try{QRDecoder.decode(hidden,1280,720,invalid[0],invalid[1],invalid[2],invalid[3]);throw new AssertionError("Invalid crop accepted");}
            catch(IllegalArgumentException expected){assertions++;}
        }
        try{QRDecoder.decode(new int[4],3,3);throw new AssertionError("Invalid album dimensions");}
        catch(IllegalArgumentException expected){assertions++;}
    }
    public static void main(String[] args)throws Exception{
        long started=System.nanoTime();geometry();decoding();
        System.out.println("RemoteScanTest: "+assertions+" checks passed in "+((System.nanoTime()-started)/1000000)+" ms (static images and geometry only)");
    }
}
