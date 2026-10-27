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

import com.defold.editor.RemoteVideoEncoder;
import java.io.IOException;
import java.nio.file.*;

public class RemoteVideoTest {
    // Verify hardware-only encoding, scaling, IDR configuration and closure; guards malformed video sessions.
    public static void main(String[] args) throws Exception {
        if (!RemoteVideoEncoder.available()) {
            if(args.length>1 && args[1].equals("optional")) {
                System.out.println("Hardware unavailable: JPEG fallback retained");return;
            }
            throw new AssertionError("Run on a Windows/Mac host with a supported hardware encoder");
        }
        int width=2560,height=1440;int[] source=new int[width*height];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)source[y*width+x]=0xff000000|(x<width/2?0xff0000:0x00ff00)|(y<height/2?0:0x0000ff);
        RemoteVideoEncoder encoder=new RemoteVideoEncoder(1920,1080);
        long started=System.nanoTime();
        try(var output=Files.newOutputStream(Path.of(args[0]))) {
            for(int i=0;i<120;i++) {
                byte[] bytes=encoder.frame(source,width,height);
                if(bytes.length<5||bytes[0]!=0||bytes[1]!=0||bytes[2]!=0||bytes[3]!=1)throw new AssertionError("Annex B prefix");
                if(i==0){boolean sps=false,pps=false,idr=false;
                    for(int j=0;j+4<bytes.length;j++)if(bytes[j]==0&&bytes[j+1]==0&&bytes[j+2]==0&&bytes[j+3]==1){int type=bytes[j+4]&31;sps|=type==7;pps|=type==8;idr|=type==5;}
                    if(!sps||!pps||!idr)throw new AssertionError("First frame lacks decoder configuration or IDR");
                }
                output.write(bytes);
            }
        }finally{encoder.close();encoder.close();}
        try{encoder.frame(source,width,height);throw new AssertionError("Use after close");}catch(IOException expected){}
        try(var invalid=new RemoteVideoEncoder(0,1080)){throw new AssertionError("Invalid dimensions accepted");}catch(IOException expected){}
        for(int[] size:new int[][]{{1280,720},{1080,1920},{640,360}}) {
            try(var resized=new RemoteVideoEncoder(size[0],size[1])) {
                for(int i=0;i<5;i++) if(resized.frame(source,width,height).length<5)throw new AssertionError("Empty frame after resize");
                try{resized.frame(new int[1],width,height);throw new AssertionError("Invalid array accepted");}catch(IOException expected){}
                try{resized.frame(source,0,height);throw new AssertionError("Invalid source accepted");}catch(IOException expected){}
            }
        }
        System.out.println("120 scaled hardware frames; SPS/PPS/IDR, invalid size and closure passed");
        System.out.println("Encode + resolution-switch checks elapsed ms: "+(System.nanoTime()-started)/1e6);
    }
}
