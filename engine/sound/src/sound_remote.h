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

#ifndef DM_SOUND_REMOTE_H
#define DM_SOUND_REMOTE_H

#include <stdint.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <math.h>
#if defined(DM_PLATFORM_MACOS)
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/mman.h>
#endif

namespace dmSoundRemote
{
    const uint32_t MAGIC = 0x44464131;
    const uint32_t HEADER = 64;
    const uint32_t SLOTS = 64;
    const uint32_t MAX_FRAMES = 960;
    const uint32_t SLOT_SIZE = 16 + MAX_FRAMES * 4;
    const uint32_t CAPACITY = HEADER + SLOTS * SLOT_SIZE;

    struct Capture
    {
        int m_File;
        uint8_t* m_Data;
        uint32_t m_Rate;
        Capture() : m_File(-1), m_Data(0), m_Rate(0) {}
    };

    static int16_t Sample(float value, bool normalized)
    {
        if (!isfinite(value)) return 0;
        if (normalized) value *= 32768.0f;
        if (value > 32767.0f) value = 32767.0f;
        if (value < -32768.0f) value = -32768.0f;
        return (int16_t)value;
    }

    static void Convert(int16_t* output, const void* input, uint32_t total, uint32_t offset, uint32_t count, bool floats, bool normalized)
    {
        if (!floats)
        {
            memcpy(output, (const int16_t*)input + offset * 2, count * 4);
            return;
        }
        const float* planar = (const float*)input;
        for (uint32_t i = 0; i < count; ++i)
        {
            output[i * 2] = Sample(planar[offset + i], normalized);
            output[i * 2 + 1] = Sample(planar[total + offset + i], normalized);
        }
    }

#if defined(DM_PLATFORM_MACOS)
    static bool Lock(Capture* capture, short type)
    {
        struct flock lock;
        memset(&lock, 0, sizeof(lock));
        lock.l_type = type;
        lock.l_whence = SEEK_SET;
        return fcntl(capture->m_File, F_SETLK, &lock) == 0;
    }
#endif

    static void Open(Capture* capture, uint32_t rate)
    {
        capture->m_File = -1;
        capture->m_Data = 0;
        capture->m_Rate = rate;
#if defined(DM_PLATFORM_MACOS)
        const char* game = getenv("DM_EDITOR_GAME_FILE");
        if (!game || rate < 8000 || rate > 192000) return;
        char path[4096];
        if (snprintf(path, sizeof(path), "%s.audio", game) >= (int)sizeof(path)) return;
        int fd = open(path, O_RDWR | O_NOFOLLOW);
        if (fd < 0) return;
        struct stat info;
        if (fstat(fd, &info) || !S_ISREG(info.st_mode) || info.st_uid != getuid() || info.st_size != CAPACITY)
        {
            close(fd);
            return;
        }
        void* memory = mmap(0, CAPACITY, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (memory == MAP_FAILED) { close(fd); return; }
        uint32_t* header = (uint32_t*)memory;
        if (header[0] != MAGIC) { munmap(memory, CAPACITY); close(fd); return; }
        capture->m_File = fd;
        capture->m_Data = (uint8_t*)memory;
        if (Lock(capture, F_WRLCK))
        {
            header[1] = rate;
            header[2] = 2;
            header[3] = 16;
            header[7] = 1;
            Lock(capture, F_UNLCK);
        }
#endif
    }

    // The mixer never waits on disk/network or a reader: contention drops this tap only.
    static void Write(Capture* capture, const void* samples, uint32_t frames, bool floats, bool normalized)
    {
#if defined(DM_PLATFORM_MACOS)
        if (!capture->m_Data || !Lock(capture, F_WRLCK)) return;
        uint32_t* header = (uint32_t*)capture->m_Data;
        // Retry initialization if a reader briefly held the file lock during Open.
        if (header[7] != 1)
        {
            header[1] = capture->m_Rate;
            header[2] = 2;
            header[3] = 16;
            header[7] = 1;
        }
        if (header[5] != 0)
        {
            for (uint32_t offset = 0; offset < frames; offset += MAX_FRAMES)
            {
                uint32_t count = frames - offset;
                if (count > MAX_FRAMES) count = MAX_FRAMES;
                uint32_t sequence = header[4] + 1;
                uint8_t* slot = capture->m_Data + HEADER + (sequence % SLOTS) * SLOT_SIZE;
                Convert((int16_t*)(slot + 16), samples, frames, offset, count, floats, normalized);
                ((uint32_t*)slot)[0] = count;
                ((uint32_t*)slot)[1] = sequence;
                header[4] = sequence;
            }
        }
        Lock(capture, F_UNLCK);
#endif
    }

    static void Close(Capture* capture)
    {
#if defined(DM_PLATFORM_MACOS)
        if (capture->m_Data) munmap(capture->m_Data, CAPACITY);
        if (capture->m_File >= 0) close(capture->m_File);
#endif
        capture->m_Data = 0;
        capture->m_File = -1;
    }
}
#endif
