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

#ifndef DEFOLD_EDITOR_ENGINE_SYMBOLS_H
#define DEFOLD_EDITOR_ENGINE_SYMBOLS_H

#include <dbghelp.h>

/* DbgHelp options and symbol handlers are process-global and single-threaded. */
static SRWLOCK engine_symbols_lock = SRWLOCK_INIT;

/* Local extension archives rename dmengine_local.exe/.pdb to dmengine.exe/.pdb.
 * The CodeView record still names the original PDB and absolute build directory.
 * Read the adjacent published PDB explicitly, after checking its GUID and age;
 * never consult a stale build directory or relax the ABI checks at the call site. */
static DWORD64 LoadEngineSymbols(HANDLE symbols, const WCHAR* executable, DWORD64 base)
{
    WCHAR pdb[32768];
    size_t length = wcslen(executable);
    if (length >= 32768) return 0;
    memcpy(pdb, executable, (length + 1) * sizeof(WCHAR));
    WCHAR* extension = wcsrchr(pdb, L'.');
    WCHAR* slash = wcsrchr(pdb, L'\\');
    WCHAR* forward = wcsrchr(pdb, L'/');
    if (forward && (!slash || forward > slash)) slash = forward;
    if (!extension || (slash && extension < slash) || extension - pdb + 4 >= 32768) return 0;
    wcscpy(extension, L".pdb");
    SYMSRV_INDEX_INFOW image = {0}, debug = {0};
    image.sizeofstruct = sizeof(image); debug.sizeofstruct = sizeof(debug);
    GUID empty = {0};
    if (!SymSrvGetFileIndexInfoW(executable, &image, 0) || !image.size
        || !SymSrvGetFileIndexInfoW(pdb, &debug, 0)
        || memcmp(&image.guid, &empty, sizeof(GUID)) == 0
        || memcmp(&image.guid, &debug.guid, sizeof(GUID)) != 0 || image.age != debug.age) return 0;
    DWORD64 loaded = SymLoadModuleExW(symbols, NULL, pdb, L"dmengine", base, image.size, NULL, 0);
    if (!loaded) return 0;
    IMAGEHLP_MODULEW64 module = {0};
    module.SizeOfStruct = sizeof(module);
    /* Standalone PDB loads set PdbUnmatched because no image is registered with
     * DbgHelp. The explicit image/PDB identity checks above provide that binding. */
    if (!SymGetModuleInfoW64(symbols, loaded, &module) || module.SymType != SymPdb
        || memcmp(&module.PdbSig70, &image.guid, sizeof(GUID)) != 0
        || module.PdbAge != image.age)
    {
        SymUnloadModule64(symbols, loaded);
        return 0;
    }
    return loaded;
}

#endif
