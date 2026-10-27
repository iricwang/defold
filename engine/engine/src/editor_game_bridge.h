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
#include <gamesys/components/comp_gui.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>

namespace dmEditorGame
{
    static const uint32_t HEADER_SIZE = 4096;
    static uint32_t g_SlotSize = 2048 * 2048 * 4;
    static size_t g_Capacity = HEADER_SIZE + 2 * g_SlotSize;
    static uint32_t g_SettingsSequence = 0;
    static uint32_t g_Served = 0;
    static bool g_Simulator = false;
    static const uint32_t MAGIC = 0x44464732;
    static int g_File = -1;
    static uint32_t* g_Data = 0;
    static pid_t g_Parent = 0;
    static uint32_t g_Input[256];
    static uint32_t g_PickSequence = 0;
    static bool g_Picking = false;
    static uint32_t g_AutomationInput = 0;
    static uint32_t g_DeviceRevision = 0;
    static bool g_Automation = false;

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
        if (fstat(g_File, &info) != 0 || !S_ISREG(info.st_mode) || info.st_uid != getuid() || info.st_size < HEADER_SIZE || info.st_size > HEADER_SIZE + 2LL * 4096 * 4096 * 4)
        {
            close(g_File);
            g_File = -1;
            return false;
        }
        g_Capacity = (size_t) info.st_size;
        void* data = mmap(0, g_Capacity, PROT_READ | PROT_WRITE, MAP_SHARED, g_File, 0);
        if (data == MAP_FAILED)
        {
            close(g_File);
            g_File = -1;
            return false;
        }
        g_Data = (uint32_t*) data;
        g_Simulator = g_Data[0] == 0x44464733;
        g_SlotSize = g_Simulator ? g_Data[12] : 2048 * 2048 * 4;
        if ((!g_Simulator && g_Data[0] != MAGIC) || g_Parent <= 1
            || g_SlotSize == 0 || g_SlotSize > 4096 * 4096 * 4 || g_Capacity != HEADER_SIZE + 2 * g_SlotSize)
        {
            munmap(data, g_Capacity);
            close(g_File);
            g_Data = 0;
            g_File = -1;
            return false;
        }
        if (g_Simulator) g_Data[13] = 1;
        g_PickSequence = 0;
        g_Picking = false;
        g_Automation = g_Simulator && g_Data[268] == 0x41555431;
        // Header words 270-278 are mode-specific: automation acknowledgements
        // and interactive GUI picking must never interpret each other's data.
        if (!g_Automation) g_Data[274] = 1; // GUI resource pick protocol version.
        if (g_Automation)
        {
            g_Data[269] = 1;
            dmLogInfo("Editor automation bridge protocol 1 (macOS HID)");
        }
        g_Data[14] = 1; // The built-in bridge owns capture when the extension is also linked.
        dmPlatform::ConfigureBackgroundApplication();
        memset(g_Input, 0, sizeof(g_Input));
        return true;
    }

    static bool Active() { return g_Data != 0; }
    static bool ParentAlive() { return !g_Data || kill(g_Parent, 0) == 0; }

    static void Close()
    {
        if (!g_Data) return;
        munmap(g_Data, g_Capacity);
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
        g_Input[9] = 0;
        if (Lock(F_WRLCK))
        {
            memcpy(g_Input, g_Data, sizeof(g_Input));
            g_Data[10] = 0;
            if (!g_Automation && g_Data[270] != g_PickSequence)
            {
                g_PickSequence = g_Data[270];
                g_Picking = true;
                dmGameSystem::BeginGuiResourcePick(dmMath::Min(g_Data[271], 65535U) / 65535.0f,
                                                  dmMath::Min(g_Data[272], 65535U) / 65535.0f);
            }
            if (g_Automation)
            {
                g_AutomationInput = g_Data[277];
                g_Data[9] = 0; // Wheel and text are impulses, not held state.
            }
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
        if (g_Automation) __atomic_store_n(g_Data + 271, g_AutomationInput, __ATOMIC_RELEASE);
    }

    static void Resize(dmGraphics::HContext graphics)
    {
        if (!g_Data || !g_Simulator) return;
        uint32_t sequence = __atomic_load_n(g_Data + 264, __ATOMIC_ACQUIRE);
        if ((sequence & 1) || sequence == g_SettingsSequence) return;
        uint32_t w = g_Data[256], h = g_Data[257], revision = g_Automation ? g_Data[275] : 0;
        __atomic_thread_fence(__ATOMIC_ACQUIRE);
        if (sequence != __atomic_load_n(g_Data + 264, __ATOMIC_ACQUIRE)
            || w == 0 || h == 0 || w > 4096 || h > 4096 || (uint64_t)w * h * 4 > g_SlotSize) return;
        g_SettingsSequence = sequence;
        dmGraphics::SetWindowSize(graphics, w, h);
        g_DeviceRevision = revision;
        if (g_Automation) __atomic_store_n(g_Data + 278, revision, __ATOMIC_RELEASE);
    }

    static void Frame(dmGraphics::HContext graphics)
    {
        if (!g_Data) return;
        // Publish even when frame capture is throttled. Keep the completed result
        // until the lock can be acquired, so editor and engine cannot lose a reply.
        if (g_Picking && Lock(F_WRLCK))
        {
            const char* path = dmGameSystem::EndGuiResourcePick();
            uint32_t length = (uint32_t) strlen(path);
            memcpy((uint8_t*)g_Data + 1120, path, length);
            g_Data[276] = length;
            g_Data[275] = length ? 1 : 0;
            g_Data[273] = g_PickSequence;
            g_Picking = false;
            Lock(F_UNLCK);
        }
        if (g_Automation) __atomic_add_fetch(g_Data + 270, 1, __ATOMIC_RELEASE);
        if (g_Simulator && (!g_Data[265] || g_Data[267] == g_Served)) return;
        uint32_t request = g_Data[267];
        uint32_t w = dmGraphics::GetWindowWidth(graphics);
        uint32_t h = dmGraphics::GetWindowHeight(graphics);
        if (w > 0 && h > 0 && w <= 4096 && h <= 4096 && (uint64_t)w * h * 4 <= g_SlotSize)
        {
            // GPU readback can wait for rendering. Never hold the input/frame
            // lock while waiting: the editor must keep delivering input.
            // Only the engine publishes slots. Reusing the previous front slot
            // is safe after publication acquired the lock: its reader has finished.
            uint32_t slot = g_Data[11] ^ 1;
            uint8_t* frame = (uint8_t*)g_Data + HEADER_SIZE + slot * g_SlotSize;
            dmGraphics::ReadPixels(graphics, 0, 0, w, h, frame, w*h*4);
            if (!Lock(F_WRLCK)) return;
            g_Data[11] = slot;
            g_Data[1] = w;
            g_Data[2] = h;
            ++g_Data[3];
            g_Data[4] = getpid();
            g_Served = request;
            if (g_Automation)
            {
                g_Data[272] = g_Data[270];
                g_Data[273] = g_AutomationInput;
                g_Data[274] = g_DeviceRevision;
                __atomic_store_n(g_Data + 276, request, __ATOMIC_RELEASE);
            }
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
    static void Resize(dmGraphics::HContext) {}
    static void Frame(dmGraphics::HContext) {}
}
#endif

#endif // DM_ENGINE_EDITOR_GAME_BRIDGE_H
