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
        packet[26]=65;
        try{AvcConfig.parse(packet);throw new AssertionError("Non-IDR first frame accepted");}catch(IOException expected){}
        System.out.println("AVC configuration regression checks passed");
    }
}
