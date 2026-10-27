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

/** Extract in-band Annex B configuration before configuring an Android decoder. */
final class AvcConfig {
    static byte[][] parse(byte[] packet)throws IOException {
        byte[] sps=null,pps=null;boolean idr=false;
        int start=prefix(packet,0);
        if(start!=0)throw new IOException("Invalid Annex B packet");
        while(start>=0&&start+4<packet.length) {
            int next=prefix(packet,start+4),end=next<0?packet.length:next;
            int type=packet[start+4]&31;
            if(type==7||type==8){
                if(end-start<6||end-start>65536)throw new IOException("Invalid AVC configuration");
                byte[] data=Arrays.copyOfRange(packet,start,end);
                if(type==7)sps=data;else pps=data;
            }
            idr|=type==5;start=next;
        }
        if(sps==null||pps==null||!idr)throw new IOException("Stream must start with SPS/PPS and IDR");
        return new byte[][]{sps,pps};
    }
    static byte[] frame(byte[] packet)throws IOException {
        if(packet.length<5)throw new IOException("Empty access unit");
        int first=packet[4]&31;
        if(first!=7&&first!=8)return packet;
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream(packet.length);
        int start=0;
        while(start>=0&&start+4<packet.length){
            int next=prefix(packet,start+4),end=next<0?packet.length:next,type=packet[start+4]&31;
            if(type!=7&&type!=8)bytes.write(packet,start,end-start);
            start=next;
        }
        if(bytes.size()==0)throw new IOException("Missing video slice");
        return bytes.toByteArray();
    }
    private static int prefix(byte[] packet,int from){
        for(int i=from;i+3<packet.length;i++)if(packet[i]==0&&packet[i+1]==0&&packet[i+2]==0&&packet[i+3]==1)return i;
        return -1;
    }
}
