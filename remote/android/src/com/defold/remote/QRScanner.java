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
import android.widget.FrameLayout;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Offline QR scanner. Camera lifetime belongs to the scan page. */
@SuppressWarnings("deprecation")
final class QRScanner extends FrameLayout implements SurfaceHolder.Callback, AutoCloseable {
    interface Listener { void result(String text); void error(String message); }
    private final Activity activity;
    private final Listener listener;
    private final SurfaceView preview;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean decoding=new AtomicBoolean();
    private Camera camera;
    private volatile boolean closed;
    private long lastFrame;
    QRScanner(Activity activity, Listener listener) {
        super(activity); this.activity=activity; this.listener=listener;
        preview=new SurfaceView(activity); addView(preview,new LayoutParams(-1,-1));
        preview.getHolder().addCallback(this);
    }
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
                int score=Math.abs(size.width*size.height-640*480);
                if(score<distance) { chosen=size; distance=score; }
            }
            parameters.setPreviewSize(chosen.width,chosen.height);
            parameters.setPreviewFormat(android.graphics.ImageFormat.NV21);
            if(parameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            camera.setParameters(parameters);
            int rotation=activity.getWindowManager().getDefaultDisplay().getRotation();
            int degrees=rotation==Surface.ROTATION_90?90:rotation==Surface.ROTATION_180?180:rotation==Surface.ROTATION_270?270:0;
            camera.setDisplayOrientation((info.orientation-degrees+360)%360);
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback((data,source)-> {
                long now=android.os.SystemClock.elapsedRealtime();
                if(closed || now-lastFrame<180 || !decoding.compareAndSet(false,true))return;
                lastFrame=now;
                Camera.Size size=source.getParameters().getPreviewSize(); byte[] copy=data.clone();
                try { worker.execute(()-> {
                    try {
                        MultiFormatReader reader=new MultiFormatReader();
                        Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);
                        hints.put(DecodeHintType.POSSIBLE_FORMATS,Collections.singletonList(BarcodeFormat.QR_CODE));
                        Result result=reader.decode(new BinaryBitmap(new HybridBinarizer(new PlanarYUVLuminanceSource(copy,size.width,size.height,0,0,size.width,size.height,false))),hints);
                        activity.runOnUiThread(()->{if(!closed)listener.result(result.getText());});
                    } catch (NotFoundException ignored) { }
                    catch (Exception ignored) { }
                    finally { decoding.set(false); }
                }); } catch(RejectedExecutionException e) { decoding.set(false); }
            });
            camera.startPreview();
        } catch(Exception e) { closeCamera();listener.error("无法打开相机，请使用手动连接"); }
    }
    private void closeCamera() {
        if(camera!=null) { camera.setPreviewCallback(null); try{camera.stopPreview();}catch(RuntimeException ignored){} camera.release();camera=null; }
    }
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { closeCamera(); }
    @Override public void close() { closed=true;closeCamera();worker.shutdownNow(); }
}
