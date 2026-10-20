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

// Editor-only native extension: inert unless a private Game session is supplied.
#include <dmsdk/sdk.h>
#include <dmsdk/graphics/graphics.h>
#include <dmsdk/hid/hid.h>
#include <dmsdk/dlib/context_registry.h>
#include <AppKit/AppKit.h>
#include <objc/runtime.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>

static const uint32_t HEADER = 4096;
static const uint32_t SLOT = 2048 * 2048 * 4;
static const uint32_t CAPACITY = HEADER + 2 * SLOT;
static int g_File = -1;
static uint32_t* g_Data;
static uint32_t g_Input[256];
static pid_t g_Parent;
static dmHID::HContext g_Hid;
static dmGraphics::HContext g_Graphics;
static IMP g_OrderWindow;
static IMP g_ActivationPolicy;

static bool Lock(short type)
{
    struct flock lock = {};
    lock.l_type = type;
    lock.l_whence = SEEK_SET;
    return fcntl(g_File, F_SETLK, &lock) == 0;
}

static void OrderWindow(id window, SEL selector, NSWindowOrderingMode place, NSInteger relative)
{
    // Keep the engine's real window and drawable alive without presenting it.
    if (place == NSWindowOut)
        ((void (*)(id, SEL, NSWindowOrderingMode, NSInteger))g_OrderWindow)(window, selector, place, relative);
}

static BOOL ActivationPolicy(id application, SEL selector, NSApplicationActivationPolicy policy)
{
    return ((BOOL (*)(id, SEL, NSApplicationActivationPolicy))g_ActivationPolicy)(application, selector, NSApplicationActivationPolicyProhibited);
}

static ExtensionResult Frame(dmExtension::Params*)
{
    if (!g_Data) return EXTENSION_RESULT_OK;
    uint32_t w = dmGraphics::GetWindowWidth(g_Graphics);
    uint32_t h = dmGraphics::GetWindowHeight(g_Graphics);
    if (w == 0 || h == 0 || w > 2048 || h > 2048)
    {
        if (Lock(F_WRLCK)) { g_Data[4] = 0xffffffff; Lock(F_UNLCK); }
        return EXTENSION_RESULT_OK;
    }
    uint32_t slot = g_Data[11] ^ 1;
    dmGraphics::ReadPixels(g_Graphics, 0, 0, w, h, (uint8_t*)g_Data + HEADER + slot * SLOT, w * h * 4);
    if (Lock(F_WRLCK))
    {
        g_Data[1] = w;
        g_Data[2] = h;
        g_Data[4] = getpid();
        g_Data[11] = slot;
        ++g_Data[3];
        Lock(F_UNLCK);
    }
    return EXTENSION_RESULT_OK;
}

static dmExtension::Result AppInitialize(dmExtension::AppParams* params)
{
    const char* path = getenv("DM_EDITOR_GAME_FILE");
    const char* parent = getenv("DM_EDITOR_GAME_PARENT");
    if (!path || !parent) return dmExtension::RESULT_OK;
    g_Parent = (pid_t)atoi(parent);
    if (g_Parent <= 1) return dmExtension::RESULT_INIT_ERROR;
    g_File = open(path, O_RDWR | O_NOFOLLOW);
    struct stat info;
    if (g_File < 0) return dmExtension::RESULT_INIT_ERROR;
    if (fstat(g_File, &info) != 0 || !S_ISREG(info.st_mode) || info.st_uid != getuid() || info.st_size != CAPACITY)
    {
        close(g_File); g_File = -1;
        return dmExtension::RESULT_INIT_ERROR;
    }
    void* data = mmap(0, CAPACITY, PROT_READ | PROT_WRITE, MAP_SHARED, g_File, 0);
    if (data == MAP_FAILED) { close(g_File); g_File = -1; return dmExtension::RESULT_INIT_ERROR; }
    if (((uint32_t*)data)[0] != 0x44464732)
    {
        munmap(data, CAPACITY); close(g_File); g_File = -1;
        return dmExtension::RESULT_INIT_ERROR;
    }
    g_Data = (uint32_t*)data;
    g_Hid = (dmHID::HContext)ContextRegistryGet(ExtensionAppParamsGetContextRegistry(params), "hid");
    Method order = class_getInstanceMethod([NSWindow class], @selector(orderWindow:relativeTo:));
    Method policy = class_getInstanceMethod([NSApplication class], @selector(setActivationPolicy:));
    g_OrderWindow = method_setImplementation(order, (IMP)OrderWindow);
    g_ActivationPolicy = method_setImplementation(policy, (IMP)ActivationPolicy);
    [[NSApplication sharedApplication] setActivationPolicy:NSApplicationActivationPolicyProhibited];
    dmExtension::RegisterCallback(dmExtension::CALLBACK_POST_RENDER, Frame);
    dmLogInfo("Editor Game bridge connected (native extensions)");
    return dmExtension::RESULT_OK;
}

static dmExtension::Result Initialize(dmExtension::Params* params)
{
    if (g_Data)
        g_Graphics = (dmGraphics::HContext)ContextRegistryGet(ExtensionParamsGetContextRegistry(params), "graphics");
    return dmExtension::RESULT_OK;
}

static dmExtension::Result Update(dmExtension::Params*)
{
    if (!g_Data) return dmExtension::RESULT_OK;
    if (kill(g_Parent, 0) != 0) { _exit(0); }
    g_Input[10] = 0;
    if (Lock(F_WRLCK))
    {
        memcpy(g_Input, g_Data, sizeof(g_Input));
        g_Data[10] = 0;
        Lock(F_UNLCK);
    }
    bool focused = g_Input[5] != 0;
    const uint8_t* keys = (uint8_t*)g_Input + 64;
    dmHID::HKeyboard keyboard = dmHID::GetKeyboard(g_Hid, 0);
    dmHID::HMouse mouse = dmHID::GetMouse(g_Hid, 0);
    for (uint32_t i = 32; i < 127; ++i)
        dmHID::SetKey(keyboard, (dmHID::Key)i, focused && keys[i]);
    static const dmHID::Key special[] = {dmHID::KEY_ESC, dmHID::KEY_UP, dmHID::KEY_DOWN,
        dmHID::KEY_LEFT, dmHID::KEY_RIGHT, dmHID::KEY_LSHIFT, dmHID::KEY_LCTRL, dmHID::KEY_LALT,
        dmHID::KEY_TAB, dmHID::KEY_ENTER, dmHID::KEY_BACKSPACE, dmHID::KEY_DEL,
        dmHID::KEY_HOME, dmHID::KEY_END, dmHID::KEY_PAGEUP, dmHID::KEY_PAGEDOWN,
        dmHID::KEY_F1, dmHID::KEY_F2, dmHID::KEY_F3, dmHID::KEY_F4, dmHID::KEY_F5,
        dmHID::KEY_F6, dmHID::KEY_F7, dmHID::KEY_F8, dmHID::KEY_F9, dmHID::KEY_F10,
        dmHID::KEY_F11, dmHID::KEY_F12};
    for (uint32_t i = 0; i < sizeof(special)/sizeof(special[0]); ++i)
        dmHID::SetKey(keyboard, special[i], focused && keys[128 + i]);
    dmHID::SetMousePosition(mouse, (int)g_Input[6], (int)g_Input[7]);
    for (uint32_t i = 0; i < 3; ++i)
        dmHID::SetMouseButton(mouse, (dmHID::MouseButton)i, focused && (g_Input[8] & (1 << i)));
    dmHID::SetMouseWheel(mouse, (int)g_Input[9]);
    for (uint32_t i = 0; i < g_Input[10] && i < 128; ++i)
        if (focused) dmHID::AddKeyboardChar(g_Hid, g_Input[128 + i]);
    return dmExtension::RESULT_OK;
}

static dmExtension::Result AppFinalize(dmExtension::AppParams*)
{
    if (g_Data)
    {
        method_setImplementation(class_getInstanceMethod([NSWindow class], @selector(orderWindow:relativeTo:)), g_OrderWindow);
        method_setImplementation(class_getInstanceMethod([NSApplication class], @selector(setActivationPolicy:)), g_ActivationPolicy);
        munmap(g_Data, CAPACITY); close(g_File); g_Data = 0; g_File = -1;
    }
    return dmExtension::RESULT_OK;
}

DM_DECLARE_EXTENSION(DefoldEditorGameBridge, "DefoldEditorGameBridge", AppInitialize, AppFinalize, Initialize, Update, 0, 0)
