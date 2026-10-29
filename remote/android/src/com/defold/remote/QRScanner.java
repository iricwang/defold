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
import android.graphics.Rect;
import android.hardware.Camera;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import com.google.zxing.NotFoundException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Offline scanner. A single bounded decoder owns a borrowed camera buffer until it is returned. */
@SuppressWarnings("deprecation")
final class QRScanner extends FrameLayout implements SurfaceHolder.Callback, AutoCloseable {
    interface Listener { void result(String text); void error(String message); default void status(String message){} }
    private final Activity activity;
    private final Listener listener;
    private final SurfaceView preview;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean decoding=new AtomicBoolean();
    private final GestureDetector taps;
    private final ScaleGestureDetector pinch;
    private Camera camera;
    private volatile boolean closed,paused;
    private long lastFrame,openedAt;
    private int frameWidth,frameHeight,orientation;
    private boolean periodicFocus,reportedFrame,mirrored;
    private volatile boolean reportedError;
    private float zoom=1,requestedZoom=1;
    private String lastResult="";
    private long lastResultAt;
    private String continuousMode;
    private volatile int[] visibleCrop;
    private final Runnable restoreFocus=this::restoreContinuousFocus;
    private final Runnable focusTick=new Runnable(){public void run(){
        if(!closed&&camera!=null&&periodicFocus){focus();postDelayed(this,2400);}
    }};
    private final Runnable frameWatch=new Runnable(){public void run(){
        if(!closed&&!reportedFrame)listener.error("相机未返回预览画面，可重试扫码或使用相册、配对链接");
    }};

    QRScanner(Activity activity,Listener listener) {
        super(activity);this.activity=activity;this.listener=listener;setClipChildren(true);
        preview=new SurfaceView(activity);addView(preview,new LayoutParams(-1,-1));
        preview.getHolder().addCallback(this);
        taps=new GestureDetector(activity,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onSingleTapUp(MotionEvent e){focusAt(e.getX(),e.getY());performClick();return true;}
        });
        pinch=new ScaleGestureDetector(activity,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector detector){setZoom(requestedZoom*detector.getScaleFactor());return true;}
        });
        setContentDescription("相机取景，轻点对焦，双指缩放");
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent event){return true;}
    @Override public boolean onTouchEvent(MotionEvent event){
        pinch.onTouchEvent(event);if(!pinch.isInProgress()&&event.getPointerCount()==1)taps.onTouchEvent(event);return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}

    /** Pause duplicate detections while pairing or displaying a recognized-code error. */
    void pauseScanning(){paused=true;}
    void resumeScanning(){if(!closed){paused=false;reportedError=false;openedAt=android.os.SystemClock.elapsedRealtime();}}

    boolean isTorchSupported(){
        try{List<String> modes=camera==null?null:camera.getParameters().getSupportedFlashModes();
            return modes!=null&&modes.contains(Camera.Parameters.FLASH_MODE_TORCH);
        }catch(RuntimeException ignored){return false;}
    }
    boolean setTorch(boolean enabled){
        if(camera==null||!isTorchSupported())return false;
        try{Camera.Parameters parameters=camera.getParameters();
            parameters.setFlashMode(enabled?Camera.Parameters.FLASH_MODE_TORCH:Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(parameters);return true;
        }catch(RuntimeException error){listener.status("当前相机无法切换补光");return false;}
    }
    float getZoom(){return zoom;}
    float setZoom(float requested){
        if(camera==null||Float.isNaN(requested)||Float.isInfinite(requested))return zoom;
        try{Camera.Parameters parameters=camera.getParameters();if(!parameters.isZoomSupported())return zoom;
            List<Integer> ratios=parameters.getZoomRatios();if(ratios==null||ratios.isEmpty())return zoom;
            int maxIndex=Math.min(parameters.getMaxZoom(),ratios.size()-1);
            float target=Math.max(1,Math.min(Math.min(6,ratios.get(maxIndex)/100f),requested));int best=0;
            requestedZoom=target;
            for(int i=1;i<=maxIndex;i++)if(Math.abs(ratios.get(i)/100f-target)<Math.abs(ratios.get(best)/100f-target))best=i;
            parameters.setZoom(best);camera.setParameters(parameters);zoom=ratios.get(best)/100f;
        }catch(RuntimeException error){listener.status("当前相机无法调整缩放");}
        return zoom;
    }

    void focus(){focusAt(getWidth()/2f,getHeight()/2f);}
    void focusAt(float x,float y){
        final Camera source=camera;if(source==null||frameWidth<=0||getWidth()<=0||getHeight()<=0)return;
        try{source.cancelAutoFocus();removeCallbacks(restoreFocus);
            Camera.Parameters parameters=source.getParameters();List<String> modes=parameters.getSupportedFocusModes();
            if(modes==null||!modes.contains(Camera.Parameters.FOCUS_MODE_AUTO))return;
            float[] point=ScanGeometry.sensorPoint(x,y,frameWidth,frameHeight,orientation,getWidth(),getHeight(),mirrored);
            int sx=Math.round(point[0]*2000-1000),sy=Math.round(point[1]*2000-1000);
            Rect area=new Rect(Math.max(-1000,sx-150),Math.max(-1000,sy-150),Math.min(1000,sx+150),Math.min(1000,sy+150));
            List<Camera.Area> regions=Collections.singletonList(new Camera.Area(area,1000));
            if(parameters.getMaxNumFocusAreas()>0)parameters.setFocusAreas(regions);
            if(parameters.getMaxNumMeteringAreas()>0)parameters.setMeteringAreas(regions);
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);source.setParameters(parameters);
            source.autoFocus((success,focused)->{if(!closed&&camera==focused)postDelayed(restoreFocus,1400);});
        }catch(RuntimeException ignored){if(continuousMode!=null)postDelayed(restoreFocus,1400);}
    }
    private void restoreContinuousFocus(){
        if(camera==null||closed)return;
        try{camera.cancelAutoFocus();Camera.Parameters parameters=camera.getParameters();
            if(continuousMode!=null)parameters.setFocusMode(continuousMode);
            if(parameters.getMaxNumFocusAreas()>0)parameters.setFocusAreas(null);
            if(parameters.getMaxNumMeteringAreas()>0)parameters.setMeteringAreas(null);
            camera.setParameters(parameters);
        }catch(RuntimeException ignored){}
    }

    private void fitPreview(){
        if(frameWidth<=0||getWidth()<=0||getHeight()<=0)return;
        int w=orientation%180==0?frameWidth:frameHeight,h=orientation%180==0?frameHeight:frameWidth;
        float scale=Math.max((float)getWidth()/w,(float)getHeight()/h);
        preview.setLayoutParams(new LayoutParams(Math.round(w*scale),Math.round(h*scale),Gravity.CENTER));
        visibleCrop=ScanGeometry.visibleCrop(frameWidth,frameHeight,orientation,getWidth(),getHeight(),mirrored);
    }
    @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){super.onSizeChanged(w,h,oldW,oldH);fitPreview();}

    @Override public void surfaceCreated(SurfaceHolder holder){
        if(closed||camera!=null)return;
        try{
            int count=Camera.getNumberOfCameras();if(count==0)throw new IllegalStateException("No camera");
            int cameraId=0;Camera.CameraInfo info=new Camera.CameraInfo();
            for(int id=0;id<count;id++){Camera.getCameraInfo(id,info);if(info.facing==Camera.CameraInfo.CAMERA_FACING_BACK){cameraId=id;break;}}
            Camera.getCameraInfo(cameraId,info);camera=Camera.open(cameraId);mirrored=info.facing==Camera.CameraInfo.CAMERA_FACING_FRONT;
            int rotation=activity.getWindowManager().getDefaultDisplay().getRotation();
            int degrees=rotation==Surface.ROTATION_90?90:rotation==Surface.ROTATION_180?180:rotation==Surface.ROTATION_270?270:0;
            orientation=mirrored?(360-(info.orientation+degrees)%360)%360:(info.orientation-degrees+360)%360;
            Camera.Parameters parameters=camera.getParameters();List<Camera.Size> sizes=parameters.getSupportedPreviewSizes();
            Camera.Size chosen=parameters.getPreviewSize();
            if(sizes!=null&&!sizes.isEmpty()){
                int[][] available=new int[sizes.size()][2];for(int i=0;i<sizes.size();i++){available[i][0]=sizes.get(i).width;available[i][1]=sizes.get(i).height;}
                int viewWidth=getWidth()>0?getWidth():getResources().getDisplayMetrics().widthPixels;
                int viewHeight=getHeight()>0?getHeight():getResources().getDisplayMetrics().heightPixels;
                chosen=sizes.get(ScanGeometry.choosePreview(available,viewWidth,viewHeight,orientation));
            }
            parameters.setPreviewSize(chosen.width,chosen.height);parameters.setPreviewFormat(android.graphics.ImageFormat.NV21);
            List<String> modes=parameters.getSupportedFocusModes();continuousMode=null;periodicFocus=false;
            if(modes!=null&&modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))continuousMode=Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE;
            else if(modes!=null&&modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO))continuousMode=Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO;
            if(continuousMode!=null)parameters.setFocusMode(continuousMode);
            else if(modes!=null&&modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)){parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);periodicFocus=true;}
            if(parameters.isAutoExposureLockSupported())parameters.setAutoExposureLock(false);
            if(parameters.isAutoWhiteBalanceLockSupported())parameters.setAutoWhiteBalanceLock(false);
            if(parameters.isZoomSupported())parameters.setZoom(0);
            camera.setParameters(parameters);Camera.Size actual=camera.getParameters().getPreviewSize();
            frameWidth=actual.width;frameHeight=actual.height;zoom=1;requestedZoom=1;
            camera.setDisplayOrientation(orientation);fitPreview();camera.setPreviewDisplay(holder);
            openedAt=android.os.SystemClock.elapsedRealtime();reportedFrame=false;reportedError=false;
            listener.status("相机正在启动…");postDelayed(frameWatch,4000);
            camera.setPreviewCallbackWithBuffer(this::frame);
            int length=(frameWidth*frameHeight*android.graphics.ImageFormat.getBitsPerPixel(android.graphics.ImageFormat.NV21)+7)/8;
            for(int i=0;i<3;i++)camera.addCallbackBuffer(new byte[length]);
            camera.startPreview();if(periodicFocus)post(focusTick);
        }catch(Exception error){closeCamera();android.util.Log.e("DefoldRemote","QR camera failed",error);
            listener.error("无法打开相机，可重试扫码或使用相册、配对链接（"+error.getClass().getSimpleName()+"）");}
    }

    private void frame(byte[] data,Camera source){
        long now=android.os.SystemClock.elapsedRealtime();
        if(data==null||camera!=source)return;
        if(!reportedFrame){reportedFrame=true;removeCallbacks(frameWatch);
            listener.status("对准电脑二维码即可识别，轻点对焦，双指缩放");
            android.util.Log.i("DefoldRemote","QR preview active: "+frameWidth+"x"+frameHeight+", orientation="+orientation);}
        if(!paused&&now-openedAt>6000){openedAt=now;listener.status("可调整距离、轻点对焦，或使用补光、相册与配对链接");}
        if(closed||paused||now-lastFrame<120||!decoding.compareAndSet(false,true)){returnBuffer(data,source);return;}
        lastFrame=now;final int width=frameWidth,height=frameHeight;final int[] crop=visibleCrop;
        try{worker.execute(()->{
            try{
                String result=crop==null?QRDecoder.decode(data,width,height):QRDecoder.decode(data,width,height,crop[0],crop[1],crop[2],crop[3]);
                activity.runOnUiThread(()->{if(!closed&&!paused&&camera==source){
                    long detectedAt=android.os.SystemClock.elapsedRealtime();
                    if(result.equals(lastResult)&&detectedAt-lastResultAt<2000)return;
                    lastResult=result;lastResultAt=detectedAt;paused=true;
                    listener.status("检测到二维码，正在检查配对信息…");listener.result(result);
                }});
            }catch(NotFoundException ignored){}
            catch(Exception error){if(!reportedError){reportedError=true;android.util.Log.e("DefoldRemote","QR decode failed",error);
                activity.runOnUiThread(()->{if(!closed)listener.error("二维码识别异常，可重新扫码或使用相册");});}}
            finally{decoding.set(false);post(()->returnBuffer(data,source));}
        });}catch(RejectedExecutionException ignored){decoding.set(false);returnBuffer(data,source);}
    }
    private void returnBuffer(byte[] data,Camera source){
        if(!closed&&camera==source)try{source.addCallbackBuffer(data);}catch(RuntimeException ignored){}
    }
    private void closeCamera(){
        removeCallbacks(focusTick);removeCallbacks(frameWatch);removeCallbacks(restoreFocus);
        Camera source=camera;camera=null;visibleCrop=null;
        if(source!=null){try{source.setPreviewCallbackWithBuffer(null);source.stopPreview();}catch(RuntimeException ignored){}
            try{source.release();}catch(RuntimeException ignored){}}
    }
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){}
    @Override public void surfaceDestroyed(SurfaceHolder holder){closeCamera();}
    @Override public void close(){closed=true;paused=true;closeCamera();worker.shutdownNow();}
}
