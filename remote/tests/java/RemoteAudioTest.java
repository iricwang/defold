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
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;

public final class RemoteAudioTest {
    static int checks;
    static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    static void put(FileChannel f,long offset,int... values)throws Exception{
        ByteBuffer b=ByteBuffer.allocate(values.length*4).order(ByteOrder.LITTLE_ENDIAN);for(int v:values)b.putInt(v);b.flip();while(b.hasRemaining())f.write(b,offset+b.position());
    }
    static int get(FileChannel f,long offset)throws Exception{ByteBuffer b=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);f.read(b,offset);return b.getInt(0);}
    static void packet(FileChannel f,int sequence,int frames)throws Exception{
        long offset=RemoteAudioFile.HEADER+Integer.toUnsignedLong(sequence)%RemoteAudioFile.SLOTS*RemoteAudioFile.SLOT;
        put(f,offset,frames,sequence,0,0);put(f,offset+16,0x76543210);put(f,16,sequence);
    }
    public static void main(String[] args)throws Exception{
        Path game=Files.createTempFile("remote-audio-test", ".shm"),audio=RemoteAudioFile.path(game);
        try{
            RemoteAudioFile.create(game);
            try{new RemoteAudioFile(audio);throw new AssertionError("unready accepted");}catch(IOException expected){checks++;}
            try(FileChannel f=FileChannel.open(audio,StandardOpenOption.READ,StandardOpenOption.WRITE)){
                put(f,4,48000,2,16,0,0,0,1);
                try(RemoteAudioFile reader=new RemoteAudioFile(audio)){
                    check(reader.rate==48000);check(get(f,20)==1);check(reader.next()==null);
                    packet(f,1,1);var p=reader.next();check(p.sequence()==1);check(p.pcm().length==4);check(p.pcm()[0]==0x10&&p.pcm()[3]==0x76);
                    // A stalled reader resumes near the live edge, not at the oldest queued sound.
                    for(int i=2;i<=20;i++)packet(f,i,1);
                    check(reader.next().sequence()==18);check(reader.next().sequence()==19);check(reader.next().sequence()==20);check(reader.next()==null);
                    packet(f,21,961);try{reader.next();throw new AssertionError("oversized accepted");}catch(IOException expected){checks++;}
                }
                check(get(f,20)==0);
                // Sequence counters remain ordered across unsigned 32-bit wraparound.
                put(f,16,-2);
                try(RemoteAudioFile reader=new RemoteAudioFile(audio)){
                    packet(f,-1,1);check(reader.next().sequence()==-1);packet(f,0,1);check(reader.next().sequence()==0);packet(f,1,1);check(reader.next().sequence()==1);
                }
                put(f,8,1);try{new RemoteAudioFile(audio);throw new AssertionError("mono accepted");}catch(IOException expected){checks++;}
            }
        }finally{Files.deleteIfExists(audio);Files.deleteIfExists(game);}
        System.out.println("PASS RemoteAudioTest: "+checks+" assertions");
    }
}
