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


#include <windows.h>

static HINSTANCE module;
static const WCHAR original_proc_key[] = L"DefoldEditorEmbeddedGameOriginalProc";

/* A newly launched game must not present a standalone window before adoption. */
static LRESULT CALLBACK HoldWindow(HWND window, UINT message, WPARAM wparam, LPARAM lparam)
{
    WNDPROC original = (WNDPROC)GetPropW(window, original_proc_key);
    if (!original) return DefWindowProcW(window, message, wparam, lparam);
    if (message == WM_WINDOWPOSCHANGING)
    {
        if ((GetWindowLongPtrW(window, GWL_STYLE) & WS_CHILD) && GetParent(window))
        {
            SetWindowLongPtrW(window, GWLP_WNDPROC, (LONG_PTR)original);
            RemovePropW(window, original_proc_key);
        }
        else
        {
            WINDOWPOS* position = (WINDOWPOS*)lparam;
            /* Hidden helper/context windows must never be selected as the game. */
            if (position->flags & SWP_SHOWWINDOW)
                SetPropW(window, L"DefoldEditorGameShowRequested", (HANDLE)1);
            position->x = GetSystemMetrics(SM_XVIRTUALSCREEN) - position->cx - 4096;
            position->y = GetSystemMetrics(SM_YVIRTUALSCREEN) - position->cy - 4096;
            position->flags &= ~(SWP_SHOWWINDOW | SWP_NOMOVE);
            position->flags |= SWP_NOACTIVATE;
        }
    }
    else if (message == WM_NCDESTROY)
    {
        RemovePropW(window, original_proc_key);
    }
    return CallWindowProcW(original, window, message, wparam, lparam);
}

static void CALLBACK GameWindowEvent(HWINEVENTHOOK hook, DWORD event, HWND window,
                                    LONG object, LONG child, DWORD thread, DWORD time)
{
    (void)hook; (void)thread; (void)time;
    if (event != EVENT_OBJECT_CREATE || object != OBJID_WINDOW || child != CHILDID_SELF || !window) return;
    LONG_PTR style = GetWindowLongPtrW(window, GWL_STYLE);
    if (!(style & WS_CHILD) && (style & WS_CAPTION) == WS_CAPTION && !GetWindow(window, GW_OWNER))
    {
        WNDPROC original = (WNDPROC)GetWindowLongPtrW(window, GWLP_WNDPROC);
        if (original && !GetPropW(window, original_proc_key) && SetPropW(window, original_proc_key, (HANDLE)original))
        {
            SetWindowLongPtrW(window, GWLP_WNDPROC, (LONG_PTR)HoldWindow);
            SetWindowLongPtrW(window, GWL_STYLE, style & ~WS_VISIBLE);
            SetWindowPos(window, NULL, -32000, -32000, 0, 0,
                         SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_HIDEWINDOW);
        }
    }
}

typedef struct GameHookContext {
    DWORD process_id;
    DWORD thread_id;
    HANDLE ready;
    HANDLE thread;
    HWINEVENTHOOK hook;
} GameHookContext;

static DWORD WINAPI HookThread(LPVOID parameter)
{
    GameHookContext* context = (GameHookContext*)parameter;
    MSG message;
    PeekMessageW(&message, NULL, WM_USER, WM_USER, PM_NOREMOVE);
    context->hook = SetWinEventHook(EVENT_OBJECT_CREATE, EVENT_OBJECT_CREATE, module, GameWindowEvent,
                                    context->process_id, 0, WINEVENT_INCONTEXT);
    SetEvent(context->ready);
    if (context->hook)
    {
        while (GetMessageW(&message, NULL, 0, 0) > 0)
        {
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }
        UnhookWinEvent(context->hook);
    }
    return 0;
}

__declspec(dllexport) void CloseGameHook(GameHookContext* context)
{
    if (!context) return;
    if (context->thread)
    {
        PostThreadMessageW(context->thread_id, WM_QUIT, 0, 0);
        WaitForSingleObject(context->thread, INFINITE);
        CloseHandle(context->thread);
    }
    if (context->ready) CloseHandle(context->ready);
    HeapFree(GetProcessHeap(), 0, context);
}

__declspec(dllexport) GameHookContext* HookGameProcess(DWORD process_id)
{
    GameHookContext* context = (GameHookContext*)HeapAlloc(GetProcessHeap(), HEAP_ZERO_MEMORY, sizeof(GameHookContext));
    if (!context) return NULL;
    context->process_id = process_id;
    context->ready = CreateEventW(NULL, TRUE, FALSE, NULL);
    if (context->ready)
        context->thread = CreateThread(NULL, 0, HookThread, context, 0, &context->thread_id);
    if (!context->thread) { CloseGameHook(context); return NULL; }
    WaitForSingleObject(context->ready, INFINITE);
    if (!context->hook) { CloseGameHook(context); return NULL; }
    return context;
}

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID reserved)
{
    (void)reserved;
    if (reason == DLL_PROCESS_ATTACH) module = instance;
    return TRUE;
}
