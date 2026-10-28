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

package com.defold.remote;
import java.io.IOException;
import java.util.Arrays;
public class AvcConfigTest {
    // Verify configuration extraction with SEI/emulation prevention and reject non-decodable first packets.
    public static void main(String[] args)throws Exception {
        byte[] packet={0,0,0,1,103,42,0,0,3,1,0,0,0,1,6,9,0,0,0,1,104,43,0,0,0,1,101,44};
        byte[][] config=AvcConfig.parse(packet);
        if(!Arrays.equals(config[0],new byte[]{0,0,0,1,103,42,0,0,3,1})||!Arrays.equals(config[1],new byte[]{0,0,0,1,104,43}))throw new AssertionError("Configuration corrupted");
        for(int end=0;end<packet.length-2;end++){
            try{AvcConfig.parse(Arrays.copyOf(packet,end));throw new AssertionError("Incomplete first access unit accepted: "+end);}catch(IOException expected){}
        }
        if(!Arrays.equals(AvcConfig.frame(packet),new byte[]{0,0,0,1,6,9,0,0,0,1,101,44}))throw new AssertionError("CSD repeated as frame data");
        byte[] windows={0,0,0,1,9,16,0,0,0,1,103,42,0,0,0,1,104,43,0,0,0,1,101,44};
        AvcConfig.parse(windows);
        if(!Arrays.equals(AvcConfig.frame(windows),new byte[]{0,0,0,1,9,16,0,0,0,1,101,44}))throw new AssertionError("Windows AUD must not bypass CSD filtering");
        byte[] mixed={0,0,1,9,16,0,0,0,1,103,42,0,0,1,104,43,0,0,1,101,44};
        byte[][] mixedConfig=AvcConfig.parse(mixed);
        if(!Arrays.equals(mixedConfig[1],new byte[]{0,0,0,1,104,43}))throw new AssertionError("Three-byte CSD must normalize to four-byte prefix");
        if(!Arrays.equals(AvcConfig.frame(mixed),new byte[]{0,0,1,9,16,0,0,1,101,44}))throw new AssertionError("Mixed Annex B prefix filtering");
        for(byte[] invalid:new byte[][]{new byte[0],{0,0,1},{0,0,0,1},{0,0,1,9,16},{1,2,3,4,5}}){
            try{AvcConfig.frame(invalid);throw new AssertionError("Invalid access unit accepted");}catch(IOException expected){}
        }
        packet[26]=65;
        try{AvcConfig.parse(packet);throw new AssertionError("Non-IDR first frame accepted");}catch(IOException expected){}
        if(args.length>0){
            byte[] stream=java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[0]));
            int start=0,frames=0;
            for(int i=5;i<=stream.length;i++){
                boolean boundary=i==stream.length||(i+4<stream.length&&stream[i]==0&&stream[i+1]==0&&stream[i+2]==0&&stream[i+3]==1&&(stream[i+4]&31)==9);
                if(!boundary)continue;
                byte[] unit=Arrays.copyOfRange(stream,start,i);
                if(frames==0)AvcConfig.parse(unit);
                byte[] filtered=AvcConfig.frame(unit);
                for(int j=0;j+3<filtered.length;j++)if(filtered[j]==0&&filtered[j+1]==0&&filtered[j+2]==1){
                    int type=filtered[j+3]&31;
                    if(type==7||type==8)throw new AssertionError("Recorded Windows frame repeated codec config: "+frames);
                }
                frames++;start=i;
            }
            if(frames<2)throw new AssertionError("Recording must contain multiple access units");
            System.out.println(frames+" recorded Windows access units passed Android CSD filtering");
        }
        System.out.println("AVC configuration regression checks passed");
    }
}
