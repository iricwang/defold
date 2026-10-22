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

#ifndef DM_PLATFORM_EDITOR_GAME_SAFE_AREA_H
#define DM_PLATFORM_EDITOR_GAME_SAFE_AREA_H

#if defined(DM_PLATFORM_MACOS)
#include <stdlib.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>

namespace dmPlatform
{
    // The simulator is editor-only. Map just its immutable protocol header;
    // settings use a sequence counter so rotation cannot expose mixed insets.
    struct EditorGameSafeArea
    {
        uint32_t* m_Data;
        EditorGameSafeArea() : m_Data(0)
        {
            const char* path = getenv("DM_EDITOR_GAME_FILE");
            if (!path || !getenv("DM_EDITOR_GAME_PARENT")) return;
            int file = open(path, O_RDONLY | O_NOFOLLOW);
            if (file < 0) return;
            struct stat info;
            if (fstat(file, &info) == 0 && S_ISREG(info.st_mode) && info.st_uid == getuid()
                && info.st_size >= 4096 && info.st_size <= 4096 + 2LL * 4096 * 4096 * 4)
            {
                void* data = mmap(0, 4096, PROT_READ, MAP_SHARED, file, 0);
                if (data != MAP_FAILED)
                {
                    uint32_t* words = (uint32_t*)data;
                    if (words[0] == 0x44464733 && words[12] > 0
                        && words[12] <= 4096 * 4096 * 4 && info.st_size == 4096 + 2LL * words[12])
                        m_Data = words;
                    else munmap(data, 4096);
                }
            }
            close(file);
        }
        ~EditorGameSafeArea() { if (m_Data) munmap(m_Data, 4096); }
    };

    static bool GetEditorGameSafeArea(WindowSafeArea* out)
    {
        static EditorGameSafeArea simulator;
        uint32_t* data = simulator.m_Data;
        if (!data) return false;
        uint32_t sequence = __atomic_load_n(data + 264, __ATOMIC_ACQUIRE);
        if (sequence & 1) return false;
        uint32_t w = data[256], h = data[257];
        uint32_t top = data[258], right = data[259], bottom = data[260], left = data[261];
        __atomic_thread_fence(__ATOMIC_ACQUIRE);
        if (sequence != __atomic_load_n(data + 264, __ATOMIC_ACQUIRE)
            || w == 0 || h == 0 || w > 4096 || h > 4096
            || (uint64_t)w * h * 4 > data[12] || top >= h || bottom >= h - top
            || left >= w || right >= w - left) return false;
        out->m_X = left; out->m_Y = bottom;
        out->m_Width = w - left - right; out->m_Height = h - top - bottom;
        out->m_InsetTop = top; out->m_InsetRight = right;
        out->m_InsetBottom = bottom; out->m_InsetLeft = left;
        return true;
    }
}
#endif
#endif
