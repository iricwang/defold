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
import android.media.*;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.net.*;

/** Independent, bounded game audio playback. It never delays the video or input threads. */
final class RemoteAudioPlayer implements AutoCloseable {
    private final AudioManager manager;
    private final AudioFocusRequest focus;
    private final String base,token;
    private volatile boolean active=true;
    private volatile HttpURLConnection connection;
    private AudioTrack track;
    private float volume=1;
    private volatile String status="声音连接中";
    private volatile boolean retryFocus;
    private long retryAfter;
    boolean needsFocusRetry(){return retryFocus&&android.os.SystemClock.elapsedRealtime()>=retryAfter;}
    String status(){return manager.getStreamVolume(AudioManager.STREAM_MUSIC)==0?"媒体音量为零":status;}

    RemoteAudioPlayer(Context context,String base,String token){
        this.base=base;this.token=token;
        manager=(AudioManager)context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes()).setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(change->{
                if(change==AudioManager.AUDIOFOCUS_LOSS){status="声音被其他应用占用";close();return;}
                status=change==AudioManager.AUDIOFOCUS_GAIN?"声音恢复中":"声音暂时被占用";
                synchronized(this){volume=change==AudioManager.AUDIOFOCUS_GAIN?1:change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK?.2f:0;if(track!=null)track.setVolume(volume);}
            },new Handler(Looper.getMainLooper())).build();
        if(manager.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){status="等待声音播放权限";retryFocus=true;retryAfter=android.os.SystemClock.elapsedRealtime()+1500;active=false;return;}
        new Thread(this::run,"remote-audio").start();
    }
    private static AudioAttributes attributes(){return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();}
    private void run(){
        try{
            while(active){
                HttpURLConnection current=null;
                try{
                    current=(HttpURLConnection)new URL(base+"/audio").openConnection();connection=current;
                    current.setConnectTimeout(2500);current.setReadTimeout(3500);current.setInstanceFollowRedirects(false);
                    current.setRequestProperty("Authorization","Bearer "+token);
                    int status=current.getResponseCode();
                    if(status==401||status==403||status==404){this.status="声音连接需要重新配对";break;}
                    if(status!=200){this.status="等待电脑音频";throw new IOException("Audio not ready: "+status);}
                    try(DataInputStream input=new DataInputStream(new BufferedInputStream(current.getInputStream(),16384))){play(input);}
                }catch(IOException|IllegalArgumentException|IllegalStateException ignored){
                    android.util.Log.w("DefoldRemoteAudio","Audio recovery",ignored);
                    // Audio recovery must never interrupt a playable video session.
                }finally{
                    releaseTrack();if(current!=null)current.disconnect();if(connection==current)connection=null;
                }
                if(active)try{Thread.sleep(500);}catch(InterruptedException e){break;}
            }
        }finally{close();}
    }
    private void play(DataInputStream input)throws IOException{
        int magic=input.readInt(),rate=input.readInt(),channels=input.readInt(),bits=input.readInt();
        if(magic!=0x44464131||rate<8000||rate>192000||channels!=2||bits!=16)throw new IOException("Invalid audio format");
        int minimum=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
        if(minimum<=0)throw new IOException("Unsupported audio format");
        AudioTrack output;
        synchronized(this){
            if(!active)return;
            output=new AudioTrack.Builder().setAudioAttributes(attributes())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(Math.max(minimum,rate*4/20)).build();
            track=output;
            // Devices may allocate a much larger buffer than requested. Reduce the
            // start threshold where supported; never flush a track still priming.
            output.setBufferSizeInFrames(Math.max(rate/25,minimum/4));
            if(android.os.Build.VERSION.SDK_INT>=31)output.setStartThresholdInFrames(Math.min(output.getBufferSizeInFrames(),rate/50));
            output.setVolume(volume);output.play();status="声音缓冲中";
        }
        byte[] pcm=new byte[3840];long written=0;
        long maxQueued=Math.max(rate/10,output.getBufferSizeInFrames()+rate/50);
        while(active){
            int length=input.readInt(),sequence=input.readInt();
            if(length<0||length>pcm.length||length%4!=0)throw new IOException("Invalid audio packet");
            input.readFully(pcm,0,length);if(length==0)continue;
            boolean reset=false;
            // Drain stale network audio instead of letting latency grow after Wi-Fi stalls.
            int skipped=0;
            while(active&&input.available()>rate*4/20){
                length=input.readInt();sequence=input.readInt();
                if(length<0||length>pcm.length||length%4!=0)throw new IOException("Invalid audio packet");
                input.readFully(pcm,0,length);reset=true;
                if(++skipped>128)throw new IOException("Audio backlog");
            }
            long played=Integer.toUnsignedLong(output.getPlaybackHeadPosition());
            // A gap in source packets is not queued sound: do not repeatedly reset
            // the device before it can reach its startup threshold.
            reset=played>0&&(reset||written-played>maxQueued);
            if(played>0)status=volume==0?"声音暂时被占用":"声音播放中";
            if(reset){synchronized(this){if(!active)return;output.pause();output.flush();output.play();written=0;}}
            int offset=0;
            while(active&&offset<length){
                int count=output.write(pcm,offset,length-offset,AudioTrack.WRITE_NON_BLOCKING);
                if(count<0)throw new IOException("Audio output unavailable");
                offset+=count;written+=count/4;
                if(count==0)try{Thread.sleep(2);}catch(InterruptedException e){return;}
            }
        }
    }
    private synchronized void releaseTrack(){if(track!=null){track.release();track=null;}}
    @Override public void close(){
        synchronized(this){
            if(!active)return;active=false;
            if(track!=null){try{track.pause();track.flush();}catch(IllegalStateException ignored){}}
        }
        manager.abandonAudioFocusRequest(focus);
        HttpURLConnection old=connection;if(old!=null)new Thread(old::disconnect,"remote-audio-close").start();
    }
}
