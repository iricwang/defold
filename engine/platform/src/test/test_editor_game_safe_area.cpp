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

#include <stdint.h>
#define JC_TEST_IMPLEMENTATION
#include <jc_test/jc_test.h>
#include "../window.h"
#include "../editor_game_safe_area.h"

// Device safe areas follow rotation and reject torn or out-of-range shared settings.
TEST(EditorGameSimulator, SafeAreaSnapshot)
{
    char path[] = "/tmp/defold-simulator-test-XXXXXX";
    int file = mkstemp(path);
    ASSERT_GE(file, 0);
    const uint32_t slot = 1170 * 2532 * 4;
    ASSERT_EQ(0, ftruncate(file, 4096 + 2 * slot));
    uint32_t* data = (uint32_t*)mmap(0, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, file, 0);
    ASSERT_NE((void*)MAP_FAILED, (void*)data);
    data[0] = 0x44464733; data[12] = slot;
    data[256] = 1170; data[257] = 2532;
    data[258] = 141; data[259] = 0; data[260] = 102; data[261] = 0;
    data[264] = 2;
    setenv("DM_EDITOR_GAME_FILE", path, 1);
    setenv("DM_EDITOR_GAME_PARENT", "2", 1);
    WindowSafeArea area;
    ASSERT_TRUE(dmPlatform::GetEditorGameSafeArea(&area));
    ASSERT_EQ(1170, area.m_Width); ASSERT_EQ(2289, area.m_Height);
    ASSERT_EQ(102, area.m_Y); ASSERT_EQ(141, area.m_InsetTop);
    data[264] = 3;
    ASSERT_FALSE(dmPlatform::GetEditorGameSafeArea(&area));
    data[256] = 2532; data[257] = 1170;
    data[258] = 0; data[259] = 141; data[260] = 0; data[261] = 102;
    data[264] = 4;
    ASSERT_TRUE(dmPlatform::GetEditorGameSafeArea(&area));
    ASSERT_EQ(2289, area.m_Width); ASSERT_EQ(1170, area.m_Height);
    ASSERT_EQ(102, area.m_X); ASSERT_EQ(141, area.m_InsetRight);
    data[258] = 1170;
    ASSERT_FALSE(dmPlatform::GetEditorGameSafeArea(&area));
    munmap(data, 4096); close(file); unlink(path);
    unsetenv("DM_EDITOR_GAME_FILE"); unsetenv("DM_EDITOR_GAME_PARENT");
}

int main(int argc, char** argv)
{
    jc_test_init(&argc, argv);
    return jc_test_run_all();
}
