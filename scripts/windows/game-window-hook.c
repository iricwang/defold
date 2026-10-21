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


#define WINVER 0x0A00
#define _WIN32_WINNT 0x0A00
#define NTDDI_VERSION 0x0A000002
#include <windows.h>
#include <tlhelp32.h>
#include "game-simulator.h"

static HINSTANCE module;
static const WCHAR original_proc_key[] = L"DefoldEditorEmbeddedGameOriginalProc";

/* A newly launched game must not present a standalone window before adoption. */
static LRESULT CALLBACK HoldWindow(HWND window, UINT message, WPARAM wparam, LPARAM lparam)
{
    WNDPROC original = (WNDPROC)GetPropW(window, original_proc_key);
    if (!original) return DefWindowProcW(window, message, wparam, lparam);
    if (message == WM_GETMINMAXINFO && simulator_data)
    {
        LRESULT result = CallWindowProcW(original, window, message, wparam, lparam);
        MINMAXINFO* limits = (MINMAXINFO*)lparam;
        limits->ptMaxTrackSize.x = 8192; limits->ptMaxTrackSize.y = 8192;
        return result;
    }
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
            if (simulator_data && GetPropW(window, L"DefoldEditorGameShowRequested"))
            {
                RECT rectangle = {0, 0, (LONG)simulator_data[256], (LONG)simulator_data[257]};
                AdjustWindowRectExForDpi(&rectangle, (DWORD)GetWindowLongPtrW(window, GWL_STYLE), FALSE,
                    (DWORD)GetWindowLongPtrW(window, GWL_EXSTYLE), GetDpiForWindow(window));
                position->cx = rectangle.right - rectangle.left;
                position->cy = rectangle.bottom - rectangle.top;
                position->flags &= ~SWP_NOSIZE;
            }
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

static LRESULT CALLBACK GameWindowCreate(int code, WPARAM wparam, LPARAM lparam)
{
    if (code != HCBT_CREATEWND) return CallNextHookEx(NULL, code, wparam, lparam);
    HWND window = (HWND)wparam;
    CREATESTRUCTW* creation = ((CBT_CREATEWNDW*)lparam)->lpcs;
    InitSimulatorCapture();
    LONG_PTR style = GetWindowLongPtrW(window, GWL_STYLE);
    if (!(style & WS_CHILD) && (style & WS_CAPTION) == WS_CAPTION && !GetWindow(window, GW_OWNER))
    {
        WNDPROC original = (WNDPROC)GetWindowLongPtrW(window, GWLP_WNDPROC);
        if (original && !GetPropW(window, original_proc_key) && SetPropW(window, original_proc_key, (HANDLE)original))
        {
            SetWindowLongPtrW(window, GWLP_WNDPROC, (LONG_PTR)HoldWindow);
            SetWindowLongPtrW(window, GWL_STYLE, style & ~WS_VISIBLE);
            /* CBT runs before WM_NCCREATE. Change the pending creation too, so a
             * window created with WS_VISIBLE cannot briefly appear on screen. */
            if (creation->style & WS_VISIBLE)
                SetPropW(window, L"DefoldEditorGameShowRequested", (HANDLE)1);
            creation->style &= ~WS_VISIBLE;
            creation->x = -32000;
            creation->y = -32000;
        }
    }
    return CallNextHookEx(NULL, code, wparam, lparam);
}

/* Called inside our suspended child, outside DllMain/loader lock. A local CBT
 * hook does not register another DLL in the session-wide hook module table.
 * The game main thread owns the hook; Windows removes it when that thread exits. */
typedef struct GameGuardResult {
    HANDLE ready;
    DWORD error;
} GameGuardResult;

__declspec(dllexport) void CALLBACK InstallGameWindowGuard(ULONG_PTR parameter)
{
    GameGuardResult* result = (GameGuardResult*)parameter;
    HANDLE ready = result->ready;
    MSG message;
    PeekMessageW(&message, NULL, WM_USER, WM_USER, PM_NOREMOVE);
    DWORD error = SetWindowsHookExW(WH_CBT, GameWindowCreate, NULL, GetCurrentThreadId())
        ? ERROR_SUCCESS : GetLastError();
    result->error = error;
    SetEvent(ready);
    CloseHandle(ready);
    if (error) ExitThread(error);
}

static uintptr_t GameModuleBase(DWORD process_id, const WCHAR* name)
{
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE, process_id);
    if (snapshot == INVALID_HANDLE_VALUE) return 0;
    MODULEENTRY32W entry = {0};
    entry.dwSize = sizeof(entry);
    uintptr_t base = 0;
    if (Module32FirstW(snapshot, &entry)) do
    {
        if (!_wcsicmp(entry.szExePath, name)) { base = (uintptr_t)entry.modBaseAddr; break; }
    } while (Module32NextW(snapshot, &entry));
    CloseHandle(snapshot);
    return base;
}

static DWORD RunGameThread(HANDLE process, LPTHREAD_START_ROUTINE function, LPVOID argument, DWORD* result)
{
    HANDLE thread = CreateRemoteThread(process, NULL, 0, function, argument, 0, NULL);
    if (!thread) return GetLastError();
    DWORD wait = WaitForSingleObject(thread, 10000);
    DWORD error = wait == WAIT_TIMEOUT ? ERROR_TIMEOUT : wait == WAIT_FAILED ? GetLastError() : ERROR_SUCCESS;
    if (!error && !GetExitCodeThread(thread, result)) error = GetLastError();
    CloseHandle(thread);
    return error;
}

/* Only used for the process just created by WindowsGameProcess. Install before
 * resuming its primary thread, so its first window and first swap are guarded.
 * Return errors explicitly: last-error is thread-local and cleanup overwrites it. */
__declspec(dllexport) DWORD StartGuardedGameProcess(HANDLE process, HANDLE main_thread)
{
    BOOL wow64 = FALSE;
    if (!IsWow64Process(process, &wow64)) return GetLastError();
    if (wow64) return ERROR_BAD_EXE_FORMAT;
    WCHAR path[32768];
    DWORD length = GetModuleFileNameW(module, path, 32768);
    if (!length) return GetLastError();
    if (length == 32768) return ERROR_FILENAME_EXCED_RANGE;
    SIZE_T bytes = (length + 1) * sizeof(WCHAR);
    LPVOID remote_path = VirtualAllocEx(process, NULL, bytes, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (!remote_path) return GetLastError();
    if (!WriteProcessMemory(process, remote_path, path, bytes, NULL))
    {
        DWORD error = GetLastError();
        VirtualFreeEx(process, remote_path, 0, MEM_RELEASE);
        return error;
    }
    /* System DLLs are shared mappings in this same-architecture child. Resolve
     * the actual LoadLibraryW entry (which may be forwarded to KernelBase). */
    LPTHREAD_START_ROUTINE load = (LPTHREAD_START_ROUTINE)GetProcAddress(GetModuleHandleW(L"kernel32.dll"), "LoadLibraryW");
    DWORD result = 0;
    DWORD error = RunGameThread(process, load, remote_path, &result);
    /* On timeout the thread can still be reading this allocation. The caller
     * terminates the failed child, which releases its remaining allocations. */
    if (error) return error;
    VirtualFreeEx(process, remote_path, 0, MEM_RELEASE);
    uintptr_t remote_module = GameModuleBase(GetProcessId(process), path);
    if (!remote_module) return ERROR_MOD_NOT_FOUND;
    PAPCFUNC install = (PAPCFUNC)(remote_module +
        (uintptr_t)InstallGameWindowGuard - (uintptr_t)module);
    HANDLE ready = CreateEventW(NULL, TRUE, FALSE, NULL);
    if (!ready) return GetLastError();
    GameGuardResult guard = {0};
    LPVOID remote_guard = NULL;
    if (!DuplicateHandle(GetCurrentProcess(), ready, process, &guard.ready, EVENT_MODIFY_STATE, FALSE, 0))
        error = GetLastError();
    else
    {
        remote_guard = VirtualAllocEx(process, NULL, sizeof(guard), MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
        if (!remote_guard || !WriteProcessMemory(process, remote_guard, &guard, sizeof(guard), NULL)
            || !QueueUserAPC(install, main_thread, (ULONG_PTR)remote_guard)
            || ResumeThread(main_thread) == (DWORD)-1)
            error = GetLastError();
        else
        {
            /* An APC queued before a thread starts runs before its entry point.
             * The primary thread creates its own message queue and local hook. */
            DWORD wait = WaitForSingleObject(ready, 10000);
            if (wait != WAIT_OBJECT_0) error = wait == WAIT_TIMEOUT ? ERROR_TIMEOUT : GetLastError();
            else if (!ReadProcessMemory(process, remote_guard, &guard, sizeof(guard), NULL)) error = GetLastError();
            else error = guard.error;
        }
    }
    CloseHandle(ready);
    if (!error) VirtualFreeEx(process, remote_guard, 0, MEM_RELEASE);
    return error;
}

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID reserved)
{
    (void)reserved;
    if (reason == DLL_PROCESS_ATTACH) module = instance;
    return TRUE;
}
