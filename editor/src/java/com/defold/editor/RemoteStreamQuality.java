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

package com.defold.editor;

/** Bounded, aspect-preserving stream presets. Native images are never upscaled. */
public enum RemoteStreamQuality {
    SMOOTH("540p",960,.82f), BALANCED("720p",1280,.88f), HIGH("1080p",1920,.93f), ORIGINAL("original",4096,.96f);
    public final String id;
    public final int maxEdge;
    public final float jpeg;
    RemoteStreamQuality(String id,int maxEdge,float jpeg){this.id=id;this.maxEdge=maxEdge;this.jpeg=jpeg;}
    public static RemoteStreamQuality parse(String id){
        for(RemoteStreamQuality quality:values())if(quality.id.equals(id))return quality;
        throw new IllegalArgumentException("Unsupported stream resolution");
    }
    public int[] dimensions(int width,int height){
        if(width<1||height<1||width>4096||height>4096)throw new IllegalArgumentException("Invalid source dimensions");
        double scale=Math.min(1.0,Math.min((double)maxEdge/Math.max(width,height),Math.sqrt(8388608.0/((long)width*height))));
        return new int[]{Math.max(1,(int)(width*scale)),Math.max(1,(int)(height*scale))};
    }
}
