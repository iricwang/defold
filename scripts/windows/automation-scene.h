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

/* Debug-only, process-local inspection. Public traversal ABI is checked against the exact engine PDB.
 * Prepared after rendering, before PostUpdate; published with the matching GPU readback; no desktop or remote execution interface. */
#include <stdio.h>
#include <stdarg.h>
#include <math.h>
#include <stddef.h>

typedef struct { uint64_t node; int type; uint32_t collection; uint64_t instance; void *component_type, *prototype, *world; uintptr_t component; } AutoNode;
typedef struct { AutoNode node, parent, next; void* callback; } AutoIterator;
typedef struct { uint64_t name; int type; union { double number; uint64_t hash; char url[1024]; float vector[4]; unsigned char boolean; const char* text; } value; } AutoProperty;
typedef struct { AutoProperty property; AutoNode* node; uint64_t next; void* callback; } AutoProperties;
#ifdef __GNUC__
typedef struct __attribute__((aligned(16))) { float values[16]; } AutoMatrix;
#else
typedef __declspec(align(16)) struct { float values[16]; } AutoMatrix;
#endif
static void** auto_register;
static unsigned char (*auto_root)(void*, AutoNode*);
static AutoIterator (*auto_children)(AutoNode*);
static unsigned char (*auto_next)(AutoIterator*);
static AutoProperties (*auto_properties)(AutoNode*);
static unsigned char (*auto_property_next)(AutoProperties*);
static const char* (*auto_hash)(uint64_t);
static AutoMatrix (*auto_transform)(void*, uint32_t);
static unsigned char (*auto_enabled)(void*, uint32_t, unsigned char);
static unsigned char (*auto_visible)(void*, uint32_t);
static int (*auto_clipping)(void*, uint32_t);
static unsigned char (*auto_inverted)(void*, uint32_t);
static unsigned char (*auto_inherit_alpha)(void*, uint32_t);
static unsigned char (*auto_clip_visible)(void*, uint32_t);
static BOOL auto_scene_supported;
static unsigned auto_scene_checks, auto_scene_symbols;
static WCHAR auto_scene_path[32768];
static char auto_json[4 * 1024 * 1024];
static size_t auto_length;
static int auto_count;
static BOOL auto_overflow;
static uint32_t* auto_transport;
static uint32_t* auto_sequence;
static uint32_t auto_prepared_request, auto_prepared_sequence;
static int (*auto_render_update)(void*, float);
static unsigned char auto_render_bytes[12];

static void PrepareAutomationScene(uint32_t sequence);
static int AutomationRenderUpdate(void* instance, float dt);

static BOOL AutoPatch(BOOL enabled)
{
    unsigned char jump[12] = {0x48,0xb8};
    uintptr_t address = (uintptr_t)AutomationRenderUpdate;
    memcpy(jump+2,&address,8); jump[10]=0xff; jump[11]=0xe0;
    DWORD protection;
    if (!VirtualProtect((void*)auto_render_update,12,PAGE_EXECUTE_READWRITE,&protection)) return FALSE;
    memcpy((void*)auto_render_update,enabled ? jump : auto_render_bytes,12);
    FlushInstructionCache(GetCurrentProcess(),(void*)auto_render_update,12);
    VirtualProtect((void*)auto_render_update,12,protection,&protection);
    return TRUE;
}

static void AutoPrint(const char* format, ...)
{
    if (auto_overflow) return;
    va_list args;
    va_start(args, format);
    int count = vsnprintf(auto_json + auto_length, sizeof(auto_json) - auto_length, format, args);
    va_end(args);
    if (count < 0 || (size_t)count >= sizeof(auto_json) - auto_length) { auto_overflow = TRUE; return; }
    auto_length += count;
}

static void AutoString(const char* value)
{
    AutoPrint("\"");
    if (value) for (int i = 0; value[i] && i < 8192; ++i)
    {
        unsigned char c = (unsigned char)value[i];
        if (c == '"' || c == '\\') AutoPrint("\\%c", c);
        else if (c < 32) AutoPrint("\\u%04x", c);
        else AutoPrint("%c", c);
    }
    AutoPrint("\"");
}

static BOOL AutoSize(HANDLE symbols, DWORD64 base, const char* name, ULONG size)
{
    char storage[sizeof(SYMBOL_INFO) + MAX_SYM_NAME] = {0};
    SYMBOL_INFO* info = (SYMBOL_INFO*)storage;
    info->SizeOfStruct = sizeof(SYMBOL_INFO); info->MaxNameLen = MAX_SYM_NAME;
    return SymGetTypeFromName(symbols, base, name, info) && info->Size == size;
}

static uintptr_t AutoSymbol(HANDLE symbols, DWORD64 base, const char* name)
{
    char storage[sizeof(SYMBOL_INFO) + MAX_SYM_NAME] = {0};
    SYMBOL_INFO* info = (SYMBOL_INFO*)storage;
    info->SizeOfStruct = sizeof(SYMBOL_INFO); info->MaxNameLen = MAX_SYM_NAME;
    if (!SymFromName(symbols, name, info) || info->ModBase != base) return 0;
    if (strcmp(name, "dmRender::UpdateRenderScriptInstance") == 0 && info->Size < 12) return 0;
    return (uintptr_t)info->Address;
}

static BOOL AutoOffset(HANDLE symbols, DWORD64 base, const char* type, const WCHAR* field, DWORD expected)
{
    char storage[sizeof(SYMBOL_INFO) + MAX_SYM_NAME] = {0};
    SYMBOL_INFO* info = (SYMBOL_INFO*)storage;
    info->SizeOfStruct = sizeof(SYMBOL_INFO); info->MaxNameLen = MAX_SYM_NAME;
    DWORD count = 0;
    if (!SymGetTypeFromName(symbols, base, type, info)
        || !SymGetTypeInfo(symbols, base, info->TypeIndex, TI_GET_CHILDRENCOUNT, &count) || count > 256) return FALSE;
    TI_FINDCHILDREN_PARAMS* children = (TI_FINDCHILDREN_PARAMS*)calloc(1, sizeof(TI_FINDCHILDREN_PARAMS) + count * sizeof(ULONG));
    if (!children) return FALSE;
    children->Count = count;
    BOOL matched = FALSE;
    if (SymGetTypeInfo(symbols, base, info->TypeIndex, TI_FINDCHILDREN, children))
        for (DWORD i = 0; i < count; ++i)
        {
            WCHAR* name = NULL;
            DWORD offset = 0;
            if (SymGetTypeInfo(symbols, base, children->ChildId[i], TI_GET_SYMNAME, &name))
            {
                if (wcscmp(name, field) == 0 && SymGetTypeInfo(symbols, base, children->ChildId[i], TI_GET_OFFSET, &offset))
                    matched = offset == expected;
                LocalFree(name);
            }
        }
    free(children);
    return matched;
}

static void InitAutomationScene(uint32_t* transport, uint32_t* sequence)
{
    auto_transport=transport; auto_sequence=sequence;
    if (!GetEnvironmentVariableW(L"DM_EDITOR_AUTOMATION_SCENE", auto_scene_path, 32768)) return;
    WCHAR executable[32768];
    GetModuleFileNameW(NULL, executable, 32768);
    AcquireSRWLockExclusive(&engine_symbols_lock);
    HANDLE symbols = CreateEventW(NULL, FALSE, FALSE, NULL);
    DWORD previous = SymGetOptions();
    SymSetOptions(SYMOPT_UNDNAME | SYMOPT_FAIL_CRITICAL_ERRORS | SYMOPT_EXACT_SYMBOLS | SYMOPT_NO_PROMPTS | SYMOPT_IGNORE_NT_SYMPATH);
    if (symbols && SymInitializeW(symbols, NULL, FALSE))
    {
        DWORD64 base = LoadEngineSymbols(symbols, executable, (DWORD64)GetModuleHandleW(NULL));
        if (base)
        {
            auto_scene_checks = (AutoSize(symbols, base, "dmGameObject::SceneNode", sizeof(AutoNode)) ? 1 : 0)
                | (AutoSize(symbols, base, "dmGameObject::SceneNodeIterator", sizeof(AutoIterator)) ? 2 : 0)
                | (AutoSize(symbols, base, "dmGameObject::SceneNodePropertyIterator", sizeof(AutoProperties)) ? 4 : 0)
                | (AutoOffset(symbols, base, "dmGameObject::SceneNode", L"m_Component", offsetof(AutoNode, component)) ? 8 : 0)
                | (AutoOffset(symbols, base, "dmGameObject::SceneNodeProperty", L"m_Value", offsetof(AutoProperty, value)) ? 16 : 0)
                | (AutoOffset(symbols, base, "dmGameSystem::GuiComponent", L"m_Scene", 16) ? 32 : 0);
#define AUTO_RESOLVE(variable, name) *(uintptr_t*)&variable = AutoSymbol(symbols, base, name)
            AUTO_RESOLVE(auto_register, "dmGameObject::g_Register");
            AUTO_RESOLVE(auto_root, "dmGameObject::TraverseGetRoot");
            AUTO_RESOLVE(auto_children, "dmGameObject::TraverseIterateChildren");
            AUTO_RESOLVE(auto_next, "dmGameObject::TraverseIterateNext");
            AUTO_RESOLVE(auto_properties, "dmGameObject::TraverseIterateProperties");
            AUTO_RESOLVE(auto_property_next, "dmGameObject::TraverseIteratePropertiesNext");
            AUTO_RESOLVE(auto_hash, "dmHashReverseSafe64");
            AUTO_RESOLVE(auto_transform, "dmGui::GetNodeWorldTransform");
            AUTO_RESOLVE(auto_enabled, "dmGui::IsNodeEnabled");
            AUTO_RESOLVE(auto_visible, "dmGui::GetNodeVisible");
            AUTO_RESOLVE(auto_clipping, "dmGui::GetNodeClippingMode");
            AUTO_RESOLVE(auto_inverted, "dmGui::GetNodeClippingInverted");
            AUTO_RESOLVE(auto_inherit_alpha, "dmGui::GetNodeInheritAlpha");
            AUTO_RESOLVE(auto_clip_visible, "dmGui::GetNodeClippingVisible");
            AUTO_RESOLVE(auto_render_update, "dmRender::UpdateRenderScriptInstance");
#undef AUTO_RESOLVE
            auto_scene_symbols = (auto_register ? 1 : 0) | (auto_root ? 2 : 0) | (auto_children ? 4 : 0)
                | (auto_next ? 8 : 0) | (auto_properties ? 16 : 0) | (auto_property_next ? 32 : 0)
                | (auto_hash ? 64 : 0) | (auto_transform ? 128 : 0) | (auto_enabled ? 256 : 0)
                | (auto_visible ? 512 : 0) | (auto_clipping ? 1024 : 0) | (auto_inverted ? 2048 : 0);
            auto_scene_supported = auto_scene_checks == 63 && auto_register && auto_root && auto_children && auto_next && auto_properties
                && auto_property_next && auto_hash && auto_transform && auto_enabled && auto_visible && auto_clipping && auto_inverted && auto_render_update && auto_inherit_alpha && auto_clip_visible;
            if (auto_scene_supported)
            {
                memcpy(auto_render_bytes,(void*)auto_render_update,12);
                auto_scene_supported = AutoPatch(TRUE);
            }
        }
        SymCleanup(symbols);
    }
    if (symbols) CloseHandle(symbols);
    SymSetOptions(previous);
    ReleaseSRWLockExclusive(&engine_symbols_lock);
}

static void AutoNodeJson(AutoNode* node, int parent, int depth)
{
    if (depth > 64 || auto_count >= 4096 || auto_overflow) { auto_overflow = TRUE; return; }
    int index = auto_count++;
    AutoPrint("%s{\"index\":%d,\"parent\":%d,\"element_id\":\"%llx:%llx\",\"properties\":{", index ? "," : "", index, parent,
              (unsigned long long)node->component, (unsigned long long)node->node);
    AutoProperties properties = auto_properties(node);
    int count = 0;
    BOOL gui = FALSE;
    while (auto_property_next(&properties) && count < 128)
    {
        AutoProperty* property = &properties.property;
        const char* key = auto_hash(property->name);
        if (!key) continue;
        AutoPrint("%s", count++ ? "," : ""); AutoString(key); AutoPrint(":");
        switch (property->type)
        {
        case 0: if (isfinite(property->value.number)) AutoPrint("%.9g", property->value.number); else AutoPrint("null"); break;
        case 1:
        {
            const char* text = auto_hash(property->value.hash);
            if (strcmp(key, "type") == 0 && text && strncmp(text, "gui_node_", 9) == 0) gui = TRUE;
            AutoString(text); break;
        }
        case 2: AutoString(property->value.url); break;
        case 3: case 4: case 5:
            AutoPrint("[");
            for (int i = 0; i < (property->type == 3 ? 3 : 4); ++i)
            {
                if (i) AutoPrint(",");
                if (isfinite(property->value.vector[i])) AutoPrint("%.9g", property->value.vector[i]); else AutoPrint("null");
            }
            AutoPrint("]"); break;
        case 6: AutoPrint(property->value.boolean ? "true" : "false"); break;
        case 7: AutoString(property->value.text); break;
        default: AutoPrint("null"); break;
        }
    }
    AutoPrint("}");
    if (gui && node->component && node->node && node->type == 3)
    {
        /* GuiComponent's first fields are world/resource/scene. This private prefix is version checked below. */
        void* scene = ((void**)node->component)[2];
        uint32_t handle = (uint32_t)node->node;
        AutoMatrix matrix = auto_transform(scene, handle);
        AutoPrint(",\"gui_world_matrix\":[");
        for (int i = 0; i < 16; ++i) { if (i) AutoPrint(","); if (isfinite(matrix.values[i])) AutoPrint("%.9g", matrix.values[i]); else AutoPrint("null"); }
        AutoPrint("],\"enabled_recursive\":%s,\"visible\":%s,\"clipping\":%d,\"clipping_inverted\":%s",
                  auto_enabled(scene, handle, 1) ? "true" : "false", auto_visible(scene, handle) ? "true" : "false",
                  auto_clipping(scene, handle), auto_inverted(scene, handle) ? "true" : "false");
        AutoPrint(",\"inherit_alpha\":%s,\"clipping_visible\":%s", auto_inherit_alpha(scene, handle) ? "true" : "false", auto_clip_visible(scene, handle) ? "true" : "false");
    }
    AutoPrint("}");
    AutoIterator children = auto_children(node);
    while (!auto_overflow && auto_next(&children)) AutoNodeJson(&children.node, index, depth + 1);
}

static void PrepareAutomationScene(uint32_t sequence)
{
    if (!auto_scene_path[0]) return;
    auto_length = 0; auto_count = 0; auto_overflow = FALSE;
    AutoPrint("{\"render_sequence\":%u,\"supported\":%s,\"abi_checks\":%u,\"symbol_checks\":%u,\"nodes\":[", sequence, auto_scene_supported ? "true" : "false", auto_scene_checks, auto_scene_symbols);
    AutoNode root;
    if (auto_scene_supported && *auto_register && auto_root(*auto_register, &root)) AutoNodeJson(&root, -1, 0);
    AutoPrint("]}");
    if (auto_overflow)
    {
        auto_length = 0; auto_overflow = FALSE;
        AutoPrint("{\"render_sequence\":%u,\"supported\":false,\"error\":\"snapshot_capacity\",\"nodes\":[]}", sequence);
    }
}

/* Render scripts run on the engine main thread. Temporarily restoring the whole entry avoids relocating
 * arbitrary machine instructions; recursive calls execute the original code. Never used outside owned debug games. */
static int AutomationRenderUpdate(void* instance, float dt)
{
    if (!AutoPatch(FALSE)) { auto_scene_supported=FALSE; return -1; }
    int result=auto_render_update(instance,dt);
    if (!auto_transport[266] && auto_transport[267] != auto_transport[276])
    {
        auto_prepared_request=auto_transport[267]; auto_prepared_sequence=*auto_sequence+1;
        PrepareAutomationScene(auto_prepared_sequence);
    }
    if (!AutoPatch(TRUE)) auto_scene_supported=FALSE;
    return result;
}

static BOOL AutomationSceneReady(uint32_t request, uint32_t sequence)
{
    return !auto_scene_supported || auto_transport[266] || (auto_prepared_request==request && auto_prepared_sequence==sequence);
}

static void CaptureAutomationScene(uint32_t sequence)
{
    if (!auto_scene_path[0] || auto_transport[266]) return;
    if (!auto_scene_supported) PrepareAutomationScene(sequence);
    HANDLE output = CreateFileW(auto_scene_path, GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                                NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (output != INVALID_HANDLE_VALUE) { DWORD written; WriteFile(output, auto_json, (DWORD)auto_length, &written, NULL); CloseHandle(output); }
}
