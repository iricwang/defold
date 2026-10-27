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

#include "../../../editor/resources/_defold/editor_game_bridge/src/audio.h"
#include <assert.h>
#include <stdio.h>

// Exercise the real AVAudioPlayerNode scheduling hook, stereo conversion, demand and hook cleanup.
int main()
{
    @autoreleasepool
    {
        using namespace dmEditorRemoteAudio;
        char game[] = "/tmp/remote-audio-bridge-XXXXXX";
        int game_fd = mkstemp(game);
        assert(game_fd >= 0);
        close(game_fd);
        char path[4096];
        snprintf(path, sizeof(path), "%s.audio", game);
        int fd = open(path, O_RDWR | O_CREAT | O_EXCL, 0600);
        assert(fd >= 0 && ftruncate(fd, CAPACITY) == 0);
        uint32_t magic = 0x44464131;
        assert(pwrite(fd, &magic, 4, 0) == 4);
        close(fd);
        Method method = class_getInstanceMethod([AVAudioPlayerNode class], @selector(scheduleBuffer:completionHandler:));
        IMP original = method_getImplementation(method);
        Open(game);
        assert(g_Data && method_getImplementation(method) != original);
        AVAudioEngine* engine = [[AVAudioEngine alloc] init];
        AVAudioPlayerNode* player = [[AVAudioPlayerNode alloc] init];
        AVAudioFormat* format = [[AVAudioFormat alloc] initWithCommonFormat:AVAudioPCMFormatFloat32 sampleRate:48000 channels:2 interleaved:NO];
        [engine attachNode:player];
        [engine connect:player to:engine.mainMixerNode format:format];
        AVAudioPCMBuffer* buffer = [[AVAudioPCMBuffer alloc] initWithPCMFormat:format frameCapacity:1024];
        buffer.frameLength = 1024;
        for (uint32_t i = 0; i < 1024; ++i) { buffer.floatChannelData[0][i] = .25f; buffer.floatChannelData[1][i] = -.5f; }
        [player scheduleBuffer:buffer completionHandler:nil];
        assert(g_Data[7] == 1 && g_Data[1] == 48000 && g_Data[2] == 2 && g_Data[3] == 16 && g_Data[4] == 0);
        g_Data[5] = 1;
        [player scheduleBuffer:buffer completionHandler:nil];
        assert(g_Data[4] == 2);
        for (int i = 1; i <= 2; ++i)
        {
            uint32_t* slot = (uint32_t*)((uint8_t*)g_Data + HEADER + i * SLOT);
            assert(slot[0] == (i == 1 ? 960 : 64) && slot[1] == (uint32_t)i);
            int16_t* pcm = (int16_t*)(slot + 4);
            assert(pcm[0] == 8192 && pcm[1] == -16384);
        }
        g_Data[6] = 0; // A native mixer tap already owns the ring: no duplicate capture.
        [player scheduleBuffer:buffer completionHandler:nil];
        assert(g_Data[4] == 2);
        Close();
        assert(!g_Data && method_getImplementation(method) == original);
        [player stop];
        [engine detachNode:player];
        [buffer release];[format release];[player release];[engine release];
        unlink(path);unlink(game);
        puts("PASS: native-extension audio schedule, stereo/chunking, demand, no duplicate tap, cleanup");
    }
    return 0;
}
