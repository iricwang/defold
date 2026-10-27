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

/** Counts distinct frames presented by the UI over the most recent second. */
public final class FrameRate {
    private final long[] times=new long[240];
    private int count,next;
    public void reset(){count=0;next=0;}
    public void presented(long now){times[next]=now;next=(next+1)%times.length;if(count<times.length)count++;}
    public double fps(long now){
        if(count<2)return 0;
        int samples=0;long first=now;
        for(int i=0;i<count;i++){
            long t=times[(next-1-i+times.length)%times.length];if(now-t>1000)break;
            samples++;first=t;
        }
        return samples<2||now<=first?0:(samples-1)*1000.0/(now-first);
    }
}
