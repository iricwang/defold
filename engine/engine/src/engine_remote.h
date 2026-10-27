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

#ifndef DM_ENGINE_REMOTE_H
#define DM_ENGINE_REMOTE_H

#include <stdlib.h>
#include <string.h>
#include <dlib/array.h>
#include <dlib/math.h>
#include <dlib/time.h>
#include <dlib/webserver.h>
#include "engine_service.h"

namespace dmEngineService
{
    // All access is on the engine thread. HTTP never waits for a future frame.
    struct RemoteState
    {
        char m_Token[129];
        dmArray<uint8_t> m_Capture;
        dmArray<uint8_t> m_Frame;
        RemoteInput m_Queue[64];
        RemoteInput m_Input;
        uint32_t m_Head;
        uint32_t m_Count;
        uint32_t m_Width;
        uint32_t m_Height;
        uint32_t m_Sequence;
        uint64_t m_InputTime;
        uint64_t m_FrameTime;
        uint64_t m_NextFrameTime;
        bool m_Requested;
        bool m_InputActive;

        RemoteState()
        {
            m_Token[0] = 0;
            const char* token = getenv("DM_REMOTE_TOKEN");
            if (token && strlen(token) >= 16 && strlen(token) < sizeof(m_Token))
                memcpy(m_Token, token, strlen(token) + 1);
            Reset();
        }

        void Reset()
        {
            m_Frame.SetSize(0);
            m_Head = m_Count = m_Width = m_Height = m_Sequence = 0;
            m_InputTime = m_FrameTime = m_NextFrameTime = 0;
            m_Requested = m_InputActive = false;
            memset(&m_Input, 0, sizeof(m_Input));
        }

        bool Authorized(const char* token) const
        {
            if (!m_Token[0] || !token || strlen(token) != strlen(m_Token)) return false;
            uint8_t difference = 0;
            for (uint32_t i = 0; m_Token[i]; ++i) difference |= m_Token[i] ^ token[i];
            return difference == 0;
        }

        bool Enqueue(const uint8_t* data, uint32_t size)
        {
            if (size != 5 || data[4] > 1 || m_Count == 64) return false;
            RemoteInput* input = &m_Queue[(m_Head + m_Count++) % 64];
            input->m_X = data[0] | (data[1] << 8);
            input->m_Y = data[2] | (data[3] << 8);
            input->m_Pressed = data[4] != 0;
            m_InputTime = dmTime::GetMonotonicTime();
            m_InputActive = true;
            return true;
        }

        bool Input(RemoteInput* input)
        {
            if (!m_InputActive) return false;
            if (dmTime::GetMonotonicTime() - m_InputTime > 1000000)
            {
                // A disconnected controller must not leave a button held down.
                m_Input.m_Pressed = false;
                m_Count = 0;
                m_InputActive = false;
            }
            else if (m_Count)
            {
                m_Input = m_Queue[m_Head];
                m_Head = (m_Head + 1) % 64;
                --m_Count;
            }
            *input = m_Input;
            return true;
        }

        void* Buffer(uint32_t width, uint32_t height)
        {
            if (!m_Token[0] || !m_Requested || !width || !height || width > 4096 || height > 4096
                || dmTime::GetMonotonicTime() + 1000 < m_NextFrameTime) return 0;
            uint32_t size = width * height * 4;
            if (m_Capture.Capacity() < size) m_Capture.SetCapacity(size);
            m_Capture.SetSize(size);
            m_Width = width;
            m_Height = height;
            // ReadPixels has no error return; never expose old/uninitialized bytes.
            memset(m_Capture.Begin(), 0, size);
            return m_Capture.Begin();
        }

        static void WriteU32(uint8_t* target, uint32_t value)
        {
            for (uint32_t i = 0; i < 4; ++i) target[i] = (uint8_t)(value >> (8 * i));
        }

        void Publish()
        {
            uint32_t edge = dmMath::Max(m_Width, m_Height);
            uint32_t width = dmMath::Max(1U, m_Width * dmMath::Min(edge, 960U) / edge);
            uint32_t height = dmMath::Max(1U, m_Height * dmMath::Min(edge, 960U) / edge);
            uint32_t size = 16 + width * height * 4;
            if (m_Frame.Capacity() < size) m_Frame.SetCapacity(size);
            m_Frame.SetSize(size);
            uint8_t* output = m_Frame.Begin();
            memcpy(output, "DFR1", 4);
            WriteU32(output + 4, width);
            WriteU32(output + 8, height);
            WriteU32(output + 12, ++m_Sequence);
            if (width == m_Width && height == m_Height)
                memcpy(output + 16, m_Capture.Begin(), size - 16);
            else for (uint32_t y = 0; y < height; ++y)
                for (uint32_t x = 0; x < width; ++x)
                    memcpy(output + 16 + (y * width + x) * 4,
                           m_Capture.Begin() + ((y * m_Height / height) * m_Width + x * m_Width / width) * 4, 4);
            m_Requested = false;
            m_FrameTime = dmTime::GetMonotonicTime();
            // Keep the 60 Hz phase: scheduling from the last capture skips frames
            // whenever vsync/readback jitter makes an interval slightly too short.
            m_NextFrameTime = dmMath::Max(m_NextFrameTime + 16667, m_FrameTime);
        }

        static void Status(dmWebServer::Request* request, int code)
        {
            dmWebServer::SetStatusCode(request, code);
            dmWebServer::SendAttribute(request, "Cache-Control", "no-store");
            dmWebServer::SendAttribute(request, "Connection", "close");
        }

        static void Handler(void* context, dmWebServer::Request* request)
        {
            RemoteState* self = (RemoteState*)context;
            uint8_t body[1024];
            if (request->m_ContentLength > sizeof(body)) { Status(request, 413); return; }
            uint32_t received = 0;
            while (received < request->m_ContentLength)
            {
                uint32_t count = 0;
                if (dmWebServer::Receive(request, body + received, request->m_ContentLength - received, &count) != dmWebServer::RESULT_OK || !count)
                { Status(request, 400); return; }
                received += count;
            }
            if (!self->m_Token[0]) { Status(request, 404); return; }
            if (!self->Authorized(dmWebServer::GetHeader(request, "X-Defold-Remote-Token")))
            { Status(request, 403); return; }
            const bool frame = strcmp(request->m_Resource, "/remote/frame") == 0;
            const bool input = strcmp(request->m_Resource, "/remote/input") == 0;
            const bool status = strcmp(request->m_Resource, "/remote/status") == 0;
            if (!frame && !input && !status) { Status(request, 404); return; }
            const char* method = input ? "POST" : "GET";
            if (strcmp(request->m_Method, method))
            {
                Status(request, 405);
                dmWebServer::SendAttribute(request, "Allow", method);
                return;
            }
            if (request->m_ContentLength != (input ? 5U : 0U))
            { Status(request, 400); return; }
            if (input)
            {
                Status(request, body[4] > 1 ? 400 : (self->Enqueue(body, 5) ? 204 : 429));
            }
            else if (frame)
            {
                self->m_Requested = true;
                if (self->m_Frame.Size() == 0 || dmTime::GetMonotonicTime() - self->m_FrameTime > 1000000)
                { Status(request, 204); return; }
                Status(request, 200);
                dmWebServer::SendAttribute(request, "Content-Type", "application/octet-stream");
                dmWebServer::Send(request, self->m_Frame.Begin(), self->m_Frame.Size());
            }
            else
            {
                const char* body = "{\"protocol\":1,\"frame\":\"bgra\",\"max_edge\":960,\"max_fps\":60}";
                Status(request, 200);
                dmWebServer::SendAttribute(request, "Content-Type", "application/json");
                dmWebServer::Send(request, body, strlen(body));
            }
        }
    };
}
#endif
