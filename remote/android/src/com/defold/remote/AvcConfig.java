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

/** Extract in-band Annex B configuration before configuring an Android decoder. */
final class AvcConfig {
    static byte[][] parse(byte[] packet)throws IOException {
        byte[] sps=null,pps=null;boolean idr=false;
        int start=prefix(packet,0);
        if(start!=0)throw new IOException("Invalid Annex B packet");
        while(start>=0) {
            int payload=start+prefixSize(packet,start);
            int next=prefix(packet,payload),end=next<0?packet.length:next;
            if(end<=payload)throw new IOException("Empty AVC NAL unit");
            int type=packet[payload]&31;
            if(type==7||type==8){
                if(end-payload<2||end-payload>65532)throw new IOException("Invalid AVC configuration");
                byte[] data=new byte[4+end-payload];data[3]=1;
                System.arraycopy(packet,payload,data,4,end-payload);
                if(type==7)sps=data;else pps=data;
            }
            if(type==5&&end-payload<2)throw new IOException("Incomplete IDR slice");
            idr|=type==5;start=next;
        }
        if(sps==null||pps==null||!idr)throw new IOException("Stream must start with SPS/PPS and IDR");
        return new byte[][]{sps,pps};
    }
    static byte[] frame(byte[] packet)throws IOException {
        if(prefix(packet,0)!=0)throw new IOException("Invalid Annex B packet");
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream(packet.length);
        int start=0;boolean slice=false;
        while(start>=0){
            int payload=start+prefixSize(packet,start);
            int next=prefix(packet,payload),end=next<0?packet.length:next;
            if(end<=payload)throw new IOException("Empty AVC NAL unit");
            int type=packet[payload]&31;
            // MediaCodec submits csd-0/csd-1 at start. Windows places AUD before
            // SPS/PPS, so inspecting only the first NAL repeats codec setup.
            if(type!=7&&type!=8)bytes.write(packet,start,end-start);
            slice|=type==1||type==5;
            start=next;
        }
        if(!slice)throw new IOException("Missing video slice");
        return bytes.toByteArray();
    }
    private static int prefix(byte[] packet,int from){
        for(int i=from;i+2<packet.length;i++)if(packet[i]==0&&packet[i+1]==0&&
                (packet[i+2]==1||(i+3<packet.length&&packet[i+2]==0&&packet[i+3]==1)))return i;
        return -1;
    }
    private static int prefixSize(byte[] packet,int start){return packet[start+2]==1?3:4;}
}
