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

#ifndef DM_EDITOR_REMOTE_AUDIO_H
#define DM_EDITOR_REMOTE_AUDIO_H

#import <AVFoundation/AVFoundation.h>
#include <objc/runtime.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <pthread.h>
#include <math.h>

// Captures this engine's existing output buffers, never system audio or microphone input.
namespace dmEditorRemoteAudio
{
    const uint32_t HEADER = 64, SLOTS = 64, FRAMES = 960, SLOT = 16 + FRAMES * 4;
    const size_t CAPACITY = HEADER + SLOTS * SLOT;
    static int g_File = -1;
    static uint32_t* g_Data;
    static IMP g_Schedule;
    static pthread_mutex_t g_Mutex = PTHREAD_MUTEX_INITIALIZER;

    static bool Lock(short type)
    {
        struct flock lock = {};
        lock.l_type = type;
        lock.l_whence = SEEK_SET;
        return fcntl(g_File, F_SETLK, &lock) == 0;
    }

    static int16_t Sample(float value)
    {
        if (!isfinite(value)) return 0;
        value *= 32768.0f;
        return (int16_t)(value > 32767 ? 32767 : value < -32768 ? -32768 : value);
    }

    static void Capture(AVAudioPCMBuffer* buffer)
    {
        AVAudioFormat* format = buffer.format;
        if (format.commonFormat != AVAudioPCMFormatFloat32 || format.isInterleaved
            || format.channelCount != 2 || format.sampleRate < 8000 || format.sampleRate > 192000)
            return;
        // Neither the output thread nor local playback may wait for the network/reader.
        if (pthread_mutex_trylock(&g_Mutex) != 0) return;
        if (g_Data && Lock(F_WRLCK))
        {
            // A future SDK may include the native mixer tap. Do not capture twice.
            if (g_Data[7] == 0 || g_Data[6] == 1)
            {
                g_Data[1] = (uint32_t)format.sampleRate;
                g_Data[2] = 2;
                g_Data[3] = 16;
                g_Data[6] = 1;
                g_Data[7] = 1;
                if (g_Data[5])
                {
                    const float* left = buffer.floatChannelData[0];
                    const float* right = buffer.floatChannelData[1];
                    for (uint32_t offset = 0; offset < buffer.frameLength; offset += FRAMES)
                    {
                        uint32_t count = buffer.frameLength - offset;
                        if (count > FRAMES) count = FRAMES;
                        uint32_t sequence = g_Data[4] + 1;
                        uint32_t* slot = (uint32_t*)((uint8_t*)g_Data + HEADER + (sequence % SLOTS) * SLOT);
                        int16_t* pcm = (int16_t*)(slot + 4);
                        for (uint32_t i = 0; i < count; ++i)
                        {
                            pcm[i * 2] = Sample(left[offset + i]);
                            pcm[i * 2 + 1] = Sample(right[offset + i]);
                        }
                        slot[0] = count;
                        slot[1] = sequence;
                        g_Data[4] = sequence;
                    }
                }
            }
            Lock(F_UNLCK);
        }
        pthread_mutex_unlock(&g_Mutex);
    }

    static void Schedule(id player, SEL selector, AVAudioPCMBuffer* buffer, AVAudioNodeCompletionHandler completion)
    {
        Capture(buffer);
        ((void (*)(id, SEL, AVAudioPCMBuffer*, AVAudioNodeCompletionHandler))g_Schedule)(player, selector, buffer, completion);
    }

    static void Open(const char* game_path)
    {
        char path[4096];
        if (snprintf(path, sizeof(path), "%s.audio", game_path) >= (int)sizeof(path)) return;
        int fd = open(path, O_RDWR | O_NOFOLLOW);
        if (fd < 0) return;
        struct stat info;
        if (fstat(fd, &info) || !S_ISREG(info.st_mode) || info.st_uid != getuid() || info.st_size != CAPACITY)
        {
            close(fd);
            return;
        }
        void* data = mmap(0, CAPACITY, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (data == MAP_FAILED) { close(fd); return; }
        if (((uint32_t*)data)[0] != 0x44464131) { munmap(data, CAPACITY); close(fd); return; }
        g_Data = (uint32_t*)data;
        g_File = fd;
        Method schedule = class_getInstanceMethod([AVAudioPlayerNode class], @selector(scheduleBuffer:completionHandler:));
        g_Schedule = method_setImplementation(schedule, (IMP)Schedule);
    }

    static void Close()
    {
        pthread_mutex_lock(&g_Mutex);
        if (g_Data)
        {
            method_setImplementation(class_getInstanceMethod([AVAudioPlayerNode class], @selector(scheduleBuffer:completionHandler:)), g_Schedule);
            munmap(g_Data, CAPACITY);
            close(g_File);
            g_Data = 0;
            g_File = -1;
        }
        pthread_mutex_unlock(&g_Mutex);
    }
}
#endif
