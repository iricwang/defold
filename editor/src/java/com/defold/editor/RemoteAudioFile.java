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
import java.nio.file.attribute.PosixFilePermissions;

/** Private game-mixer ring. Network readers never block the engine's sound thread. */
final class RemoteAudioFile implements AutoCloseable {
    static final int MAGIC=0x44464131, HEADER=64, SLOTS=64, FRAMES=960, SLOT=16+FRAMES*4, SIZE=HEADER+SLOTS*SLOT;
    private final FileChannel channel;
    private final ByteBuffer header=ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
    final int rate;
    private int sequence;
    record Packet(int sequence,byte[] pcm){}
    static Path path(Path game){return game.resolveSibling(game.getFileName()+".audio");}
    static void create(Path game)throws IOException{
        Path path=path(game);
        Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try(FileChannel file=FileChannel.open(path,StandardOpenOption.WRITE)){
            file.write(ByteBuffer.wrap(new byte[]{0}),SIZE-1);
            ByteBuffer header=ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);header.putInt(MAGIC).position(0);file.write(header,0);
        }
    }
    RemoteAudioFile(Path path)throws IOException{
        channel=FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        try{
            if(channel.size()!=SIZE)throw new IOException("Invalid audio ring size");
            try(FileLock lock=channel.tryLock()){
                if(lock==null)throw new IOException("Audio ring busy");
                read(header,0);
                rate=header.getInt(4);
                if(header.getInt(0)!=MAGIC||header.getInt(28)!=1||rate<8000||rate>192000||header.getInt(8)!=2||header.getInt(12)!=16)throw new IOException("Audio mixer not ready");
                sequence=header.getInt(16);enable(1);
            }
        }catch(IOException|RuntimeException e){channel.close();throw e;}
    }
    private void read(ByteBuffer buffer,long offset)throws IOException{
        buffer.clear();while(buffer.hasRemaining()){int n=channel.read(buffer,offset+buffer.position());if(n<=0)throw new EOFException();}buffer.flip();
    }
    private void enable(int enabled)throws IOException{ByteBuffer b=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);b.putInt(enabled).flip();while(b.hasRemaining())channel.write(b,20+b.position());}
    Packet next()throws IOException{
        try(FileLock lock=channel.tryLock()){
            if(lock==null)return null;
            read(header,0);enable(1);
            int latest=header.getInt(16);long pending=Integer.toUnsignedLong(latest-sequence);
            if(pending==0)return null;
            // Bound recovery to the newest ~60 ms instead of replaying a stale ring.
            if(pending>6)sequence=latest-3;
            int next=sequence+1;long offset=HEADER+Integer.toUnsignedLong(next)%SLOTS*SLOT;
            ByteBuffer metadata=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);read(metadata,offset);
            int frames=metadata.getInt(0);
            if(metadata.getInt(4)!=next||frames<1||frames>FRAMES)throw new IOException("Invalid audio packet");
            byte[] pcm=new byte[frames*4];read(ByteBuffer.wrap(pcm),offset+16);sequence=next;return new Packet(sequence,pcm);
        }catch(OverlappingFileLockException busy){return null;}
    }
    @Override public void close()throws IOException{
        try{try(FileLock lock=channel.tryLock()){if(lock!=null)enable(0);}}catch(IOException|OverlappingFileLockException ignored){}finally{channel.close();}
    }
}
