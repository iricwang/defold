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

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;
import java.io.IOException;

/** Keeps decoded frames on the GPU. The transparent input view sits above this view. */
final class VideoSurface extends TextureView implements TextureView.SurfaceTextureListener {
    private int videoWidth=1, videoHeight=1;
    private Runnable presented;
    VideoSurface(Context context) { super(context); setSurfaceTextureListener(this); }
    synchronized Surface acquire() throws IOException, InterruptedException {
        long deadline=android.os.SystemClock.elapsedRealtime()+3000;
        while(!isAvailable()) {
            long remaining=deadline-android.os.SystemClock.elapsedRealtime();
            if(remaining<=0)throw new IOException("Video surface unavailable");
            wait(remaining);
        }
        return new Surface(getSurfaceTexture());
    }
    void configure(int width,int height,Runnable onPresented) {
        videoWidth=width;videoHeight=height;presented=onPresented;fit();
    }
    private void fit() {
        if(getWidth()==0||getHeight()==0)return;
        float scale=Math.min((float)getWidth()/videoWidth,(float)getHeight()/videoHeight);
        Matrix transform=new Matrix();
        transform.setScale(videoWidth*scale/getWidth(),videoHeight*scale/getHeight(),getWidth()/2f,getHeight()/2f);
        setTransform(transform);
    }
    @Override public synchronized void onSurfaceTextureAvailable(SurfaceTexture texture,int width,int height){fit();notifyAll();}
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int width,int height){fit();}
    @Override public synchronized boolean onSurfaceTextureDestroyed(SurfaceTexture texture){notifyAll();return true;}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture){if(presented!=null)presented.run();}
}
