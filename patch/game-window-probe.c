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
#include <stdio.h>

/* Repeated show/move calls must stay invisible until the viewport adopts the game. */
int main(int argc, char** argv)
{
    (void)argv;
    /* Created first, with the same caption style, but never intended for display. */
    HWND helper = CreateWindowExW(0, L"STATIC", L"Hidden helper", WS_OVERLAPPEDWINDOW,
                                 -32000, -32000, 320, 240, NULL, NULL, GetModuleHandleW(NULL), NULL);
    HWND game = CreateWindowExW(0, L"STATIC", L"Game startup regression", WS_OVERLAPPEDWINDOW,
                               -32000, -32000, 320, 240, NULL, NULL, GetModuleHandleW(NULL), NULL);
    if (!game) return 1;
    for (int attempt = 0; attempt < 4; ++attempt)
    {
        ShowWindow(game, SW_SHOWNOACTIVATE);
        SetWindowPos(game, NULL, 100, 100, 320, 240, SWP_NOZORDER | SWP_NOACTIVATE | SWP_SHOWWINDOW);
        RECT rect, desktop, intersection;
        GetWindowRect(game, &rect);
        desktop.left = GetSystemMetrics(SM_XVIRTUALSCREEN);
        desktop.top = GetSystemMetrics(SM_YVIRTUALSCREEN);
        desktop.right = desktop.left + GetSystemMetrics(SM_CXVIRTUALSCREEN);
        desktop.bottom = desktop.top + GetSystemMetrics(SM_CYVIRTUALSCREEN);
        if (IsWindowVisible(game) && IntersectRect(&intersection, &rect, &desktop))
        {
            DestroyWindow(game);
            puts("STANDALONE_WINDOW_VISIBLE");
            return 2;
        }
    }
    if (argc > 1)
    {
        /* Put the helper first in enumeration to reproduce the original regression. */
        SetWindowPos(helper, HWND_TOP, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
        puts("SELECTION_READY");
        fflush(stdout);
        MSG message;
        while (GetMessageW(&message, NULL, 0, 0) > 0)
        {
            TranslateMessage(&message);
            DispatchMessageW(&message);
        }
        return 0;
    }
    HWND host = CreateWindowExW(0, L"STATIC", L"Offscreen test viewport", WS_POPUP,
                               -32000, -32000, 640, 480, NULL, NULL, GetModuleHandleW(NULL), NULL);
    if (!host) { DestroyWindow(game); return 3; }
    SetWindowLongPtrW(game, GWL_STYLE, WS_CHILD);
    SetParent(game, host);
    ShowWindow(host, SW_SHOWNOACTIVATE);
    SetWindowPos(game, NULL, 17, 23, 100, 80, SWP_NOZORDER | SWP_NOACTIVATE | SWP_SHOWWINDOW);
    RECT size;
    GetClientRect(game, &size);
    int result = GetParent(game) == host && IsWindowVisible(game) && size.right == 100 && size.bottom == 80 ? 0 : 4;
    DestroyWindow(game);
    DestroyWindow(helper);
    DestroyWindow(host);
    puts(result == 0 ? "HIDDEN_STARTUP_AND_ADOPTION_OK" : "ADOPTION_FAILED");
    return result;
}
