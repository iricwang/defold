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

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;
import java.io.IOException;
import java.nio.ByteBuffer;

/** No playback clock or jitter buffer: render each decoded access unit immediately. */
final class VideoDecoder implements AutoCloseable {
    private final MediaCodec codec;
    private final Surface surface;
    private final Thread output;
    private volatile boolean running=true;
    private volatile Exception failure;
    private long sequence;
    VideoDecoder(Surface surface,int width,int height,byte[][] config)throws IOException {
        this.surface=surface;
        MediaCodec selected=null;
        for(MediaCodecInfo info:new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if(info.isEncoder())continue;
            String name=info.getName().toLowerCase(java.util.Locale.ROOT);
            if(Build.VERSION.SDK_INT>=29? !info.isHardwareAccelerated():name.startsWith("omx.google.")||name.startsWith("c2.android."))continue;
            try {
                MediaCodecInfo.CodecCapabilities caps=info.getCapabilitiesForType("video/avc");
                if(!caps.getVideoCapabilities().isSizeSupported(width,height))continue;
                MediaFormat format=MediaFormat.createVideoFormat("video/avc",width,height);
                format.setByteBuffer("csd-0",ByteBuffer.wrap(config[0]));
                format.setByteBuffer("csd-1",ByteBuffer.wrap(config[1]));
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,Math.max(1048576,width*height*2));
                format.setInteger(MediaFormat.KEY_PRIORITY,0);
                format.setInteger(MediaFormat.KEY_OPERATING_RATE,60);
                if(Build.VERSION.SDK_INT>=30&&caps.isFeatureSupported("low-latency"))format.setInteger("low-latency",1);
                selected=MediaCodec.createByCodecName(info.getName());selected.configure(format,surface,null,0);selected.start();break;
            } catch(Exception e) {
                if(selected!=null){
                    android.util.Log.w("DefoldRemote","Cannot initialize AVC decoder "+info.getName()+" at "+width+"x"+height,e);
                    try{selected.release();}catch(Exception ignored){}selected=null;
                }
            }
        }
        if(selected==null){surface.release();throw new IOException("Hardware H.264 decoder unavailable");}
        codec=selected;
        output=new Thread(()->{
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            try {
                while(running) {
                    int index=codec.dequeueOutputBuffer(info,10000);
                    if(index>=0)codec.releaseOutputBuffer(index,true);
                }
            }catch(Exception e){if(running)failure=e;}
        },"remote-video-output");output.start();
    }
    void feed(byte[] packet)throws IOException {
        packet=AvcConfig.frame(packet);
        if(failure!=null)throw new IOException("Video decoder failed",failure);
        long deadline=android.os.SystemClock.elapsedRealtime()+250;
        while(running) {
            int index=codec.dequeueInputBuffer(10000);
            if(index>=0) {
                ByteBuffer buffer=codec.getInputBuffer(index);
                if(buffer==null||buffer.capacity()<packet.length)throw new IOException("Video packet too large");
                buffer.clear();buffer.put(packet);codec.queueInputBuffer(index,0,packet.length,sequence++*16667,0);return;
            }
            if(failure!=null||android.os.SystemClock.elapsedRealtime()>deadline)throw new IOException("Video decoder stalled");
        }
        throw new IOException("Video decoder closed");
    }
    @Override public void close() {
        running=false;
        try{output.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        try{codec.stop();}catch(Exception ignored){}
        codec.release();surface.release();
    }
}
