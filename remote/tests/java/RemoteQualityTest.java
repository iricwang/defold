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

import com.defold.editor.RemoteStreamQuality;
import java.util.Arrays;

public class RemoteQualityTest {
    static int checks;
    static void check(boolean ok){checks++;if(!ok)throw new AssertionError("Check "+checks);}
    // Presets preserve orientation/aspect, avoid upscaling, bound memory, and reject unknown input.
    public static void main(String[] args)throws Exception{
        for(RemoteStreamQuality q:RemoteStreamQuality.values()){
            int[] portrait=q.dimensions(1440,2560),landscape=q.dimensions(2560,1440);
            check(portrait[0]==landscape[1]&&portrait[1]==landscape[0]);
            check(portrait[1]<=q.maxEdge);check(Arrays.equals(q.dimensions(640,360),new int[]{640,360}));
            int[] max=q.dimensions(4096,4096);check((long)max[0]*max[1]<=8388608);
            check(RemoteStreamQuality.parse(q.id)==q);
        }
        check(Arrays.equals(RemoteStreamQuality.BALANCED.dimensions(1440,2560),new int[]{720,1280}));
        check(Arrays.equals(RemoteStreamQuality.HIGH.dimensions(1440,2560),new int[]{1080,1920}));
        check(Arrays.equals(RemoteStreamQuality.ORIGINAL.dimensions(1440,2560),new int[]{1440,2560}));
        try{RemoteStreamQuality.parse("8k");throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        System.out.println(checks+" quality assertions passed");
    }
}
