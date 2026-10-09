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

#ifndef DM_ENGINE_EDITOR_GAME_BRIDGE_H
#define DM_ENGINE_EDITOR_GAME_BRIDGE_H

// Private, versioned editor bridge. Files are created with owner-only permissions.
// A nonblocking POSIX record lock protects both the frame and input snapshot.
#if defined(DM_PLATFORM_MACOS) && !defined(DM_RELEASE)
#include <platform/platform_window_osx.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>

namespace dmEditorGame
{
    static const uint32_t HEADER_SIZE = 4096;
    static const uint32_t CAPACITY = HEADER_SIZE + 2 * 2048 * 2048 * 4;
    static const uint32_t MAGIC = 0x44464732;
    static int g_File = -1;
    static uint32_t* g_Data = 0;
    static pid_t g_Parent = 0;
    static uint32_t g_Input[256];

    static bool Lock(short type)
    {
        struct flock lock;
        memset(&lock, 0, sizeof(lock));
        lock.l_type = type;
        lock.l_whence = SEEK_SET;
        return fcntl(g_File, F_SETLK, &lock) == 0;
    }

    static bool Open()
    {
        const char* path = getenv("DM_EDITOR_GAME_FILE");
        const char* parent = getenv("DM_EDITOR_GAME_PARENT");
        if (!path || !parent) return false;
        g_Parent = (pid_t) atoi(parent);
        g_File = open(path, O_RDWR | O_NOFOLLOW);
        struct stat info;
        if (g_File < 0) return false;
        if (fstat(g_File, &info) != 0 || !S_ISREG(info.st_mode) || info.st_uid != getuid() || info.st_size != CAPACITY)
        {
            close(g_File);
            g_File = -1;
            return false;
        }
        void* data = mmap(0, CAPACITY, PROT_READ | PROT_WRITE, MAP_SHARED, g_File, 0);
        if (data == MAP_FAILED)
        {
            close(g_File);
            g_File = -1;
            return false;
        }
        g_Data = (uint32_t*) data;
        if (g_Data[0] != MAGIC || g_Parent <= 1)
        {
            munmap(data, CAPACITY);
            close(g_File);
            g_Data = 0;
            g_File = -1;
            return false;
        }
        dmPlatform::ConfigureBackgroundApplication();
        memset(g_Input, 0, sizeof(g_Input));
        return true;
    }

    static bool Active() { return g_Data != 0; }
    static bool ParentAlive() { return !g_Data || kill(g_Parent, 0) == 0; }

    static void Close()
    {
        if (!g_Data) return;
        munmap(g_Data, CAPACITY);
        close(g_File);
        g_Data = 0;
        g_File = -1;
    }

    static void Input(dmHID::HContext hid)
    {
        if (!g_Data) return;
        // Keep the previous snapshot when the editor owns the lock. Hardware polling
        // must not manufacture a key-up between two embedded input snapshots.
        g_Input[10] = 0;
        if (Lock(F_WRLCK))
        {
            memcpy(g_Input, g_Data, sizeof(g_Input));
            g_Data[10] = 0;
            Lock(F_UNLCK);
        }
        dmHID::HKeyboard keyboard = dmHID::GetKeyboard(hid, 0);
        dmHID::HMouse mouse = dmHID::GetMouse(hid, 0);
        const uint8_t* keys = (uint8_t*) g_Input + 64;
        bool focused = g_Input[5] != 0;
        for (uint32_t i = 32; i < 127; ++i)
            dmHID::SetKey(keyboard, (dmHID::Key) i, focused && keys[i]);
        static const dmHID::Key special[] = {dmHID::KEY_ESC, dmHID::KEY_UP, dmHID::KEY_DOWN,
            dmHID::KEY_LEFT, dmHID::KEY_RIGHT, dmHID::KEY_LSHIFT, dmHID::KEY_LCTRL, dmHID::KEY_LALT,
            dmHID::KEY_TAB, dmHID::KEY_ENTER, dmHID::KEY_BACKSPACE, dmHID::KEY_DEL,
            dmHID::KEY_HOME, dmHID::KEY_END, dmHID::KEY_PAGEUP, dmHID::KEY_PAGEDOWN,
            dmHID::KEY_F1, dmHID::KEY_F2, dmHID::KEY_F3, dmHID::KEY_F4, dmHID::KEY_F5,
            dmHID::KEY_F6, dmHID::KEY_F7, dmHID::KEY_F8, dmHID::KEY_F9, dmHID::KEY_F10,
            dmHID::KEY_F11, dmHID::KEY_F12};
        for (uint32_t i = 0; i < sizeof(special)/sizeof(special[0]); ++i)
            dmHID::SetKey(keyboard, special[i], focused && keys[128+i]);
        dmHID::SetMousePosition(mouse, (int)g_Input[6], (int)g_Input[7]);
        for (uint32_t i = 0; i < 3; ++i)
            dmHID::SetMouseButton(mouse, (dmHID::MouseButton)i, focused && (g_Input[8] & (1 << i)));
        dmHID::SetMouseWheel(mouse, (int)g_Input[9]);
        uint32_t count = dmMath::Min(g_Input[10], (uint32_t)128);
        for (uint32_t i = 0; i < count; ++i)
            if (focused) dmHID::AddKeyboardChar(hid, g_Input[128+i]);

    }

    static void Frame(dmGraphics::HContext graphics)
    {
        if (!g_Data) return;
        uint32_t w = dmGraphics::GetWindowWidth(graphics);
        uint32_t h = dmGraphics::GetWindowHeight(graphics);
        if (w > 0 && h > 0 && w <= 2048 && h <= 2048)
        {
            // GPU readback can wait for rendering. Never hold the input/frame
            // lock while waiting: the editor must keep delivering input.
            // Only the engine publishes slots. Reusing the previous front slot
            // is safe after publication acquired the lock: its reader has finished.
            uint32_t slot = g_Data[11] ^ 1;
            uint8_t* frame = (uint8_t*)g_Data + HEADER_SIZE + slot * 2048 * 2048 * 4;
            dmGraphics::ReadPixels(graphics, 0, 0, w, h, frame, w*h*4);
            if (!Lock(F_WRLCK)) return;
            g_Data[11] = slot;
            g_Data[1] = w;
            g_Data[2] = h;
            ++g_Data[3];
            g_Data[4] = getpid();
        }
        else
        {
            if (!Lock(F_WRLCK)) return;
            g_Data[4] = 0xffffffff;
        }
        Lock(F_UNLCK);
    }
}
#else
namespace dmEditorGame
{
    static bool Open() { return false; }
    static bool Active() { return false; }
    static bool ParentAlive() { return true; }
    static void Close() {}
    static void Input(dmHID::HContext) {}
    static void Frame(dmGraphics::HContext) {}
}
#endif

#endif // DM_ENGINE_EDITOR_GAME_BRIDGE_H
