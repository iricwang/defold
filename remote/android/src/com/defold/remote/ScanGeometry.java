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

/** Shared aspect-fill camera geometry; independent of Android for regression tests. */
public final class ScanGeometry {
    private ScanGeometry() {}

    /** Maps a point in the displayed, center-cropped preview into sensor-normalized coordinates. */
    public static float[] sensorPoint(float x,float y,int frameWidth,int frameHeight,int rotation,
                                      int viewWidth,int viewHeight,boolean mirrored) {
        if(frameWidth<=0||frameHeight<=0||viewWidth<=0||viewHeight<=0||rotation%90!=0)
            throw new IllegalArgumentException("Invalid preview geometry");
        rotation=(rotation%360+360)%360;
        int displayWidth=rotation%180==0?frameWidth:frameHeight;
        int displayHeight=rotation%180==0?frameHeight:frameWidth;
        float scale=Math.max((float)viewWidth/displayWidth,(float)viewHeight/displayHeight);
        float dx=(x+(displayWidth*scale-viewWidth)/2)/(displayWidth*scale);
        float dy=(y+(displayHeight*scale-viewHeight)/2)/(displayHeight*scale);
        float sx,sy;
        switch(rotation){
            case 90:sx=dy;sy=1-dx;break;
            case 180:sx=1-dx;sy=1-dy;break;
            case 270:sx=1-dy;sy=dx;break;
            default:sx=dx;sy=dy;
        }
        if(mirrored)sx=1-sx;
        return new float[]{clamp(sx),clamp(sy)};
    }

    /** Sensor-space crop containing exactly the camera field visible on screen. */
    public static int[] visibleCrop(int width,int height,int rotation,int viewWidth,int viewHeight,boolean mirrored) {
        float minX=1,minY=1,maxX=0,maxY=0;
        for(int x:new int[]{0,viewWidth})for(int y:new int[]{0,viewHeight}){
            float[] point=sensorPoint(x,y,width,height,rotation,viewWidth,viewHeight,mirrored);
            minX=Math.min(minX,point[0]);minY=Math.min(minY,point[1]);
            maxX=Math.max(maxX,point[0]);maxY=Math.max(maxY,point[1]);
        }
        int left=Math.max(0,(int)Math.floor(minX*width)),top=Math.max(0,(int)Math.floor(minY*height));
        int right=Math.min(width,(int)Math.ceil(maxX*width)),bottom=Math.min(height,(int)Math.ceil(maxY*height));
        return new int[]{left,top,Math.max(1,right-left),Math.max(1,bottom-top)};
    }

    /** Prefer a clear 1080p analysis frame and the aspect ratio requiring least preview cropping. */
    public static int choosePreview(int[][] sizes,int viewWidth,int viewHeight,int rotation) {
        if(sizes==null||sizes.length==0)throw new IllegalArgumentException("No preview sizes");
        double target=(double)Math.max(1,viewWidth)/Math.max(1,viewHeight);
        int best=0;double bestScore=Double.POSITIVE_INFINITY;
        for(int i=0;i<sizes.length;i++){
            int w=sizes[i][0],h=sizes[i][1];if(w<=0||h<=0)continue;
            double aspect=rotation%180==0?(double)w/h:(double)h/w;
            long pixels=(long)w*h;
            double score=4*Math.abs(Math.log(aspect/target))+.35*Math.abs(Math.log(pixels/2073600.0));
            if(pixels>3000000||Math.max(w,h)>2560)score+=8;
            if(score<bestScore){best=i;bestScore=score;}
        }
        return best;
    }

    private static float clamp(float value){return Math.max(0,Math.min(1,value));}
}
