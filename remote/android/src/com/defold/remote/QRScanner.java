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

import android.app.Activity;
import android.hardware.Camera;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Gravity;
import android.widget.FrameLayout;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Offline QR scanner. Camera lifetime belongs to the scan page. */
@SuppressWarnings("deprecation")
final class QRScanner extends FrameLayout implements SurfaceHolder.Callback, AutoCloseable {
    interface Listener { void result(String text); void error(String message); default void status(String message){} }
    private final Activity activity;
    private final Listener listener;
    private final SurfaceView preview;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean decoding=new AtomicBoolean();
    private Camera camera;
    private volatile boolean closed;
    private long lastFrame;
    private int frameWidth,frameHeight,orientation;
    private boolean periodicFocus,reportedFrame,reportedError;
    private long openedAt;
    private final Runnable focusTick=new Runnable(){public void run(){if(!closed&&camera!=null&&periodicFocus){focus();postDelayed(this,1800);}}};
    private final Runnable frameWatch=new Runnable(){public void run(){if(!closed&&!reportedFrame)listener.error("相机未返回预览画面，请返回后重新打开扫码");}};
    QRScanner(Activity activity, Listener listener) {
        super(activity); this.activity=activity; this.listener=listener;
        preview=new SurfaceView(activity); addView(preview,new LayoutParams(-1,-1));
        preview.getHolder().addCallback(this);
        setOnClickListener(v->focus());
    }
    private void focus(){try{if(camera!=null){camera.cancelAutoFocus();camera.autoFocus((success,source)->{if(!periodicFocus)try{source.cancelAutoFocus();}catch(RuntimeException ignored){}});}}catch(RuntimeException ignored){}}
    private void fitPreview(){
        if(frameWidth==0||getWidth()==0||getHeight()==0)return;
        int w=orientation%180==0?frameWidth:frameHeight,h=orientation%180==0?frameHeight:frameWidth;
        float scale=Math.min((float)getWidth()/w,(float)getHeight()/h);
        preview.setLayoutParams(new LayoutParams(Math.round(w*scale),Math.round(h*scale),Gravity.CENTER));
    }
    @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){super.onSizeChanged(w,h,oldW,oldH);fitPreview();}
    @Override public void surfaceCreated(SurfaceHolder holder) {
        if(closed)return;
        try {
            int cameraId=0;
            Camera.CameraInfo info=new Camera.CameraInfo();
            for(int id=0;id<Camera.getNumberOfCameras();id++) { Camera.getCameraInfo(id,info); if(info.facing==Camera.CameraInfo.CAMERA_FACING_BACK){cameraId=id;break;} }
            Camera.getCameraInfo(cameraId,info); camera=Camera.open(cameraId);
            Camera.Parameters parameters=camera.getParameters();
            Camera.Size chosen=parameters.getPreviewSize();
            int distance=Integer.MAX_VALUE;
            for(Camera.Size size:parameters.getSupportedPreviewSizes()) {
                int score=Math.abs(size.width*size.height-1280*720);
                if(score<distance) { chosen=size; distance=score; }
            }
            parameters.setPreviewSize(chosen.width,chosen.height);
            parameters.setPreviewFormat(android.graphics.ImageFormat.NV21);
            List<String> modes=parameters.getSupportedFocusModes();
            periodicFocus=false;
            if(modes!=null&&modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            else if(modes!=null&&modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO))parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            else if(modes!=null&&modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)){parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);periodicFocus=true;}
            camera.setParameters(parameters);
            int rotation=activity.getWindowManager().getDefaultDisplay().getRotation();
            int degrees=rotation==Surface.ROTATION_90?90:rotation==Surface.ROTATION_180?180:rotation==Surface.ROTATION_270?270:0;
            orientation=info.facing==Camera.CameraInfo.CAMERA_FACING_FRONT?(360-(info.orientation+degrees)%360)%360:(info.orientation-degrees+360)%360;
            frameWidth=chosen.width;frameHeight=chosen.height;camera.setDisplayOrientation(orientation);fitPreview();
            camera.setPreviewDisplay(holder);
            openedAt=android.os.SystemClock.elapsedRealtime();reportedFrame=false;reportedError=false;
            listener.status("相机正在启动…");postDelayed(frameWatch,4000);
            camera.setPreviewCallback((data,source)-> {
                long now=android.os.SystemClock.elapsedRealtime();
                if(!reportedFrame){reportedFrame=true;removeCallbacks(frameWatch);listener.status("正在识别二维码，点击画面可重新对焦");android.util.Log.i("DefoldRemote","QR preview active: "+frameWidth+"x"+frameHeight);}
                if(now-openedAt>6000){openedAt=now;listener.status("请靠近电脑二维码并保持清晰，点击画面可重新对焦");}
                if(closed || now-lastFrame<180 || !decoding.compareAndSet(false,true))return;
                lastFrame=now;
                Camera.Size size=source.getParameters().getPreviewSize(); byte[] copy=data.clone();
                try { worker.execute(()-> {
                    try {
                        String result=QRDecoder.decode(copy,size.width,size.height);
                        activity.runOnUiThread(()->{if(!closed){listener.status("已识别二维码，正在连接电脑…");listener.result(result);}});
                    } catch (NotFoundException ignored) { }
                    catch (Exception error) {if(!reportedError){reportedError=true;android.util.Log.e("DefoldRemote","QR decode failed",error);activity.runOnUiThread(()->{if(!closed)listener.error("二维码识别异常："+error.getClass().getSimpleName());});}}
                    finally { decoding.set(false); }
                }); } catch(RejectedExecutionException e) { decoding.set(false); }
            });
            camera.startPreview();
            if(periodicFocus)post(focusTick);
        } catch(Exception e) { closeCamera();android.util.Log.e("DefoldRemote","QR camera failed",e);listener.error("无法打开相机："+e.getClass().getSimpleName()+"，请返回后重试"); }
    }
    private void closeCamera() {
        removeCallbacks(focusTick);removeCallbacks(frameWatch);
        if(camera!=null) { camera.setPreviewCallback(null); try{camera.stopPreview();}catch(RuntimeException ignored){} camera.release();camera=null; }
    }
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { closeCamera(); }
    @Override public void close() { closed=true;closeCamera();worker.shutdownNow(); }
}
