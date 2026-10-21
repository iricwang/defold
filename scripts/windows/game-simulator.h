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


#include <GL/gl.h>
#include <stdint.h>
#include <string.h>
#include <dbghelp.h>

static HANDLE simulator_file = INVALID_HANDLE_VALUE;
static HANDLE simulator_mapping;
static HANDLE simulator_parent;
static uint32_t* simulator_data;
static uint32_t simulator_slot;
static BOOL (WINAPI *original_swap_buffers)(HDC);
static LONG simulator_capturing;
static uint32_t simulator_area[6];
static uint32_t simulator_served;
static LARGE_INTEGER simulator_last_capture;

/* Debug engines ship a matching PDB. Resolve the platform boundary before launch
 * instead of relying on an address or instruction pattern from a particular build. */
__declspec(dllexport) uint64_t SimulatorSafeAreaRva(const WCHAR* executable)
{
    static SRWLOCK lock = SRWLOCK_INIT;
    AcquireSRWLockExclusive(&lock);
    HANDLE symbols = CreateEventW(NULL, FALSE, FALSE, NULL);
    if (!symbols) { ReleaseSRWLockExclusive(&lock); return 0; }
    uint64_t result = 0;
    WCHAR directory[32768];
    wcsncpy(directory, executable, 32767); directory[32767] = 0;
    WCHAR* separator = wcsrchr(directory, L'\\');
    if (!separator) separator = wcsrchr(directory, L'/');
    if (separator) *separator = 0;
    DWORD options = SymGetOptions();
    SymSetOptions(SYMOPT_UNDNAME | SYMOPT_FAIL_CRITICAL_ERRORS | SYMOPT_EXACT_SYMBOLS | SYMOPT_NO_PROMPTS | SYMOPT_IGNORE_NT_SYMPATH);
    if (SymInitializeW(symbols, directory, FALSE))
    {
        DWORD64 base = SymLoadModuleExW(symbols, NULL, executable, NULL, 0x10000000, 0, NULL, 0);
        if (base)
        {
            char storage[sizeof(SYMBOL_INFO) + MAX_SYM_NAME] = {0};
            SYMBOL_INFO* symbol = (SYMBOL_INFO*)storage;
            symbol->SizeOfStruct = sizeof(SYMBOL_INFO); symbol->MaxNameLen = MAX_SYM_NAME;
            BOOL compatible = SymGetTypeFromName(symbols, base, "WindowSafeArea", symbol) && symbol->Size == 32;
            if (compatible && SymFromName(symbols, "dmPlatform::GetSafeArea", symbol) && symbol->Size >= 12
                && symbol->Address >= base && symbol->ModBase == base)
                result = symbol->Address - base;
        }
        SymCleanup(symbols);
    }
    SymSetOptions(options);
    CloseHandle(symbols);
    ReleaseSRWLockExclusive(&lock);
    return result;
}

/* WindowSafeArea's public platform ABI consists of eight 32-bit fields. */
static BOOL SimulatorSafeArea(void* window, int32_t* out)
{
    (void)window;
    LONG sequence = ((volatile LONG*)simulator_data)[264];
    if (!(sequence & 1))
    {
        uint32_t next[6];
        MemoryBarrier(); memcpy(next, simulator_data + 256, sizeof(next)); MemoryBarrier();
        if (sequence == ((volatile LONG*)simulator_data)[264]) memcpy(simulator_area, next, sizeof(next));
    }
    uint32_t w = simulator_area[0], h = simulator_area[1];
    int32_t top = simulator_area[2], right = simulator_area[3];
    int32_t bottom = simulator_area[4], left = simulator_area[5];
    out[0] = left; out[1] = bottom; out[2] = w-left-right; out[3] = h-top-bottom;
    out[4] = left; out[5] = top; out[6] = right; out[7] = bottom;
    return TRUE;
}

static void InstallSimulatorSafeArea(uint8_t* base, IMAGE_NT_HEADERS* nt)
{
    WCHAR value[32];
    if (!GetEnvironmentVariableW(L"DM_EDITOR_GAME_SAFE_AREA_RVA", value, 32)) return;
    uint64_t rva = wcstoul(value, NULL, 16);
    if (!rva || rva + 12 > nt->OptionalHeader.SizeOfImage) return;
    uint8_t* target = base + rva;
    uint8_t jump[12] = {0x48, 0xB8}; /* mov rax, address; jmp rax (Windows x64 ABI) */
    uint64_t address = (uint64_t)(uintptr_t)SimulatorSafeArea;
    memcpy(jump + 2, &address, sizeof(address)); jump[10] = 0xFF; jump[11] = 0xE0;
    DWORD protection;
    if (!VirtualProtect(target, sizeof(jump), PAGE_EXECUTE_READWRITE, &protection)) return;
    memcpy(target, jump, sizeof(jump));
    FlushInstructionCache(GetCurrentProcess(), target, sizeof(jump));
    VirtualProtect(target, sizeof(jump), protection, &protection);
    simulator_data[13] = 1;
}

typedef void (APIENTRY *BindFramebufferProc)(GLenum, GLuint);
typedef void (APIENTRY *BindBufferProc)(GLenum, GLuint);

static BOOL WINAPI SimulatorSwapBuffers(HDC dc)
{
    if (simulator_parent && WaitForSingleObject(simulator_parent, 0) != WAIT_TIMEOUT) ExitProcess(0);
    HWND window = WindowFromDC(dc);
    LARGE_INTEGER now, frequency;
    QueryPerformanceCounter(&now); QueryPerformanceFrequency(&frequency);
    uint32_t request = simulator_data ? ((volatile uint32_t*)simulator_data)[267] : 0;
    if (simulator_data && ((volatile uint32_t*)simulator_data)[265] && request != simulator_served
        && now.QuadPart - simulator_last_capture.QuadPart >= frequency.QuadPart / 60
        && GetPropW(window, L"DefoldEditorGameShowRequested")
        && !InterlockedExchange(&simulator_capturing, 1))
    {
        RECT rect;
        GetClientRect(window, &rect);
        uint32_t width = rect.right, height = rect.bottom;
        if (width && height && (uint64_t)width*height*4 <= simulator_slot)
        {
            uint32_t slot = simulator_data[11] ^ 1;
            GLint framebuffer = 0, pack_buffer = 0, read_buffer = 0, alignment, row_length, skip_rows, skip_pixels;
            BindFramebufferProc bind_framebuffer = (BindFramebufferProc)wglGetProcAddress("glBindFramebuffer");
            BindBufferProc bind_buffer = (BindBufferProc)wglGetProcAddress("glBindBuffer");
            if (bind_framebuffer) { glGetIntegerv(0x8CAA, &framebuffer); bind_framebuffer(0x8CA8, 0); }
            if (bind_buffer) { glGetIntegerv(0x88ED, &pack_buffer); bind_buffer(0x88EB, 0); }
            glGetIntegerv(GL_READ_BUFFER, &read_buffer);
            glGetIntegerv(GL_PACK_ALIGNMENT, &alignment); glGetIntegerv(GL_PACK_ROW_LENGTH, &row_length);
            glGetIntegerv(GL_PACK_SKIP_ROWS, &skip_rows); glGetIntegerv(GL_PACK_SKIP_PIXELS, &skip_pixels);
            glReadBuffer(GL_BACK); glPixelStorei(GL_PACK_ALIGNMENT, 4); glPixelStorei(GL_PACK_ROW_LENGTH, 0);
            glPixelStorei(GL_PACK_SKIP_ROWS, 0); glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
            glReadPixels(0, 0, width, height, 0x80E1 /* GL_BGRA */, GL_UNSIGNED_BYTE,
                (uint8_t*)simulator_data + 4096 + slot*simulator_slot);
            uint32_t* pixels = (uint32_t*)((uint8_t*)simulator_data + 4096 + slot*simulator_slot);
            for (uint32_t i = 0; i < width*height; ++i) pixels[i] |= 0xFF000000;
            glPixelStorei(GL_PACK_ALIGNMENT, alignment); glPixelStorei(GL_PACK_ROW_LENGTH, row_length);
            glPixelStorei(GL_PACK_SKIP_ROWS, skip_rows); glPixelStorei(GL_PACK_SKIP_PIXELS, skip_pixels);
            glReadBuffer(read_buffer);
            if (bind_buffer) bind_buffer(0x88EB, pack_buffer);
            if (bind_framebuffer) bind_framebuffer(0x8CA8, framebuffer);
            OVERLAPPED lock = {0};
            if (LockFileEx(simulator_file, LOCKFILE_EXCLUSIVE_LOCK | LOCKFILE_FAIL_IMMEDIATELY, 0, MAXDWORD, MAXDWORD, &lock))
            {
                simulator_data[1] = width; simulator_data[2] = height;
                simulator_data[4] = GetCurrentProcessId(); simulator_data[11] = slot; ++simulator_data[3];
                simulator_served = request;
                simulator_last_capture = now;
                UnlockFileEx(simulator_file, 0, MAXDWORD, MAXDWORD, &lock);
            }
        }
        InterlockedExchange(&simulator_capturing, 0);
    }
    return original_swap_buffers(dc);
}

/* Patch only this launched process's imported presentation function. No engine
 * SDK, game-source upload, or system-wide hook is involved. */
static void InitSimulatorCapture(void)
{
    static BOOL initialized;
    if (initialized) return;
    initialized = TRUE;
    WCHAR path[32768];
    if (!GetEnvironmentVariableW(L"DM_EDITOR_GAME_FILE", path, 32768)) return;
    simulator_file = CreateFileW(path, GENERIC_READ | GENERIC_WRITE,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    LARGE_INTEGER size;
    if (simulator_file == INVALID_HANDLE_VALUE || !GetFileSizeEx(simulator_file, &size)
        || size.QuadPart < 4096 || size.QuadPart > 4096 + 2LL*4096*4096*4) return;
    simulator_mapping = CreateFileMappingW(simulator_file, NULL, PAGE_READWRITE, 0, 0, NULL);
    if (simulator_mapping) simulator_data = (uint32_t*)MapViewOfFile(simulator_mapping, FILE_MAP_ALL_ACCESS, 0, 0, 0);
    if (!simulator_data) return;
    if (simulator_data[0] != 0x44464733 || size.QuadPart != 4096 + 2LL*simulator_data[12])
    { UnmapViewOfFile(simulator_data); simulator_data = NULL; return; }
    simulator_slot = simulator_data[12];
    memcpy(simulator_area, simulator_data + 256, sizeof(simulator_area));
    WCHAR parent[32];
    if (GetEnvironmentVariableW(L"DM_EDITOR_GAME_PARENT", parent, 32))
        simulator_parent = OpenProcess(SYNCHRONIZE, FALSE, wcstoul(parent, NULL, 10));
    uint8_t* base = (uint8_t*)GetModuleHandleW(NULL);
    IMAGE_DOS_HEADER* dos = (IMAGE_DOS_HEADER*)base;
    IMAGE_NT_HEADERS* nt = (IMAGE_NT_HEADERS*)(base + dos->e_lfanew);
    InstallSimulatorSafeArea(base, nt);
    DWORD rva = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT].VirtualAddress;
    if (!rva) return;
    IMAGE_IMPORT_DESCRIPTOR* descriptor = (IMAGE_IMPORT_DESCRIPTOR*)(base + rva);
    for (; descriptor->Name; ++descriptor)
    {
        if (!descriptor->OriginalFirstThunk) continue;
        IMAGE_THUNK_DATA* names = (IMAGE_THUNK_DATA*)(base + descriptor->OriginalFirstThunk);
        IMAGE_THUNK_DATA* addresses = (IMAGE_THUNK_DATA*)(base + descriptor->FirstThunk);
        for (; names->u1.AddressOfData; ++names, ++addresses)
        {
            if (IMAGE_SNAP_BY_ORDINAL(names->u1.Ordinal)) continue;
            IMAGE_IMPORT_BY_NAME* name = (IMAGE_IMPORT_BY_NAME*)(base + names->u1.AddressOfData);
            if (strcmp((const char*)name->Name, "SwapBuffers") != 0) continue;
            DWORD protection;
            if (!VirtualProtect(&addresses->u1.Function, sizeof(void*), PAGE_READWRITE, &protection)) return;
            original_swap_buffers = (BOOL (WINAPI*)(HDC))addresses->u1.Function;
            InterlockedExchangePointer((PVOID volatile*)&addresses->u1.Function, (PVOID)SimulatorSwapBuffers);
            VirtualProtect(&addresses->u1.Function, sizeof(void*), protection, &protection);
            return;
        }
    }
}
