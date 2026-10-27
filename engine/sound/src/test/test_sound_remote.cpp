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

#include <jc_test/jc_test.h>
#include "../sound_remote.h"

// Verify planar float channel order, normalization, clipping and non-finite silence.
TEST(SoundRemote, FloatToStereoPcm)
{
    float input[] = {0.5f, -1.5f, NAN, 0.0f, -0.5f, 1.5f, INFINITY, 0.25f};
    int16_t output[8];
    dmSoundRemote::Convert(output, input, 4, 0, 4, true, true);
    int16_t expected[] = {16384, -16384, -32768, 32767, 0, 0, 0, 8192};
    ASSERT_EQ(0, memcmp(output, expected, sizeof(expected)));
}

// Verify chunk offsets keep left/right channels aligned for both mixer formats.
TEST(SoundRemote, ChunkOffset)
{
    float planar[] = {1, 2, 3, 4, 11, 12, 13, 14};
    int16_t interleaved[] = {1, 11, 2, 12, 3, 13, 4, 14};
    int16_t expected[] = {3, 13, 4, 14};
    int16_t output[4];
    dmSoundRemote::Convert(output, planar, 4, 2, 2, true, false);
    ASSERT_EQ(0, memcmp(output, expected, sizeof(expected)));
    dmSoundRemote::Convert(output, interleaved, 4, 2, 2, false, false);
    ASSERT_EQ(0, memcmp(output, expected, sizeof(expected)));
}
