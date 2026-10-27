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

/** Density-independent four-pointer downward swipe, with an explicit all-up reset. */
public final class FourFingerGesture {
    private long firstDown, armedAt;
    private int[] tracked;
    private float[] startX, startY;
    private boolean active, cancelled;
    public void reset() { active=false; cancelled=false; tracked=null; }
    public boolean update(long now, int[] ids, float[] x, float[] y) {
        if(ids.length==0) { reset(); return false; }
        if(!active) { active=true; firstDown=now; }
        if(ids.length>4) cancelled=true;
        if(cancelled) return false;
        if(tracked==null) {
            if(now-firstDown>350) { cancelled=true; return false; }
            if(ids.length==4) { tracked=ids.clone(); startX=x.clone(); startY=y.clone(); armedAt=now; }
            return false;
        }
        if(ids.length!=4 || now-armedAt>800) { cancelled=true; return false; }
        float dx=0,dy=0;
        for(int i=0;i<4;i++) {
            int found=-1; for(int j=0;j<4;j++) if(ids[j]==tracked[i]) found=j;
            if(found<0) { cancelled=true; return false; }
            dx+=x[found]-startX[i]; dy+=y[found]-startY[i];
            if(y[found]-startY[i]<40) return false;
        }
        if(dy/4>=80 && dy>Math.abs(dx)*2) { cancelled=true; return true; }
        return false;
    }
}
