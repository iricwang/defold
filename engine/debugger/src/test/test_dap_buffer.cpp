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

#include "dap.h"
#include <stdio.h>
#include <string.h>
#define JC_TEST_IMPLEMENTATION
#include <jc_test/jc_test.h>

using namespace dmDebugger;

// Byte-wise formatting must not cause thousands of whole-buffer copies, and
// rejection at the queue limit must preserve termination and Clear() recovery.
TEST(DapBuffer, BoundedBufferGrowth)
{
    Buffer text;
    uint32_t growths = 0;
    for (uint32_t i = 0; i < MAX_QUEUE_SIZE; ++i)
    {
        uint32_t capacity = text.m_Data.Capacity();
        text.Add("x", 1);
        growths += capacity != text.m_Data.Capacity();
    }
    ASSERT_LT(growths, 32U);
    ASSERT_TRUE(text.m_Valid);
    ASSERT_EQ(MAX_QUEUE_SIZE, text.Size());
    ASSERT_LE(text.m_Data.Capacity(), MAX_QUEUE_SIZE + 1);
    ASSERT_EQ('x', text.Data()[0]);
    ASSERT_EQ('x', text.Data()[MAX_QUEUE_SIZE - 1]);
    ASSERT_EQ(0, text.Data()[MAX_QUEUE_SIZE]);
    text.Add("x", 1);
    ASSERT_FALSE(text.m_Valid);
    ASSERT_EQ(MAX_QUEUE_SIZE, text.Size());
    text.Clear();
    text.String("ready");
    ASSERT_TRUE(text.m_Valid);
    ASSERT_STREQ("\"ready\"", text.Data());
}

int main(int argc, char** argv)
{
    jc_test_init(&argc, argv);
    return jc_test_run_all();
}
