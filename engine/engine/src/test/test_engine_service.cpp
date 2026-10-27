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

#include "test_engine.h"
#include "../engine_service_private.h"
#include "../engine_remote.h"
#include <dlib/atomic.h>
#include <dlib/http/http_client.h>
#include <dlib/socket.h>
#include <dlib/thread.h>
#include <dlib/uri.h>
#include <dlib/webserver.h>

TEST_F(EngineTest, ServiceInstanceNameSanitizesAddress)
{
    char out[64];
    dmEngineService::BuildServiceInstanceName("Local Host", "8001", 0, out, sizeof(out));
    ASSERT_STREQ("defold-local-host-8001", out);
}

TEST_F(EngineTest, SanitizeMDNSLabelReplacesLeadingDashWhenRequested)
{
    char out[64];
    dmEngineService::SanitizeMDNSLabel("-service-id", "defold", true, 'd', out, sizeof(out));
    ASSERT_STREQ("dservice-id", out);
}

TEST_F(EngineTest, ManufacturerModelNameOmitsDanglingSeparator)
{
    char out[64];
    dmEngineService::BuildManufacturerModelName("Acme", 0, out, sizeof(out));
    ASSERT_STREQ("Acme", out);

    dmEngineService::BuildManufacturerModelName(0, "Device", out, sizeof(out));
    ASSERT_STREQ("Device", out);
}

TEST_F(EngineTest, ServiceInstanceNameDropsAddressBeforeDanglingSeparator)
{
    char out[12];
    dmEngineService::BuildServiceInstanceName("very-long-local-address", "8001", 0, out, sizeof(out));
    ASSERT_STREQ("defold-8001", out);
}

TEST_F(EngineTest, ServiceInstanceNameAvoidsTrailingDashWithoutPort)
{
    char out[8];
    dmEngineService::BuildServiceInstanceName("very-long-local-address", 0, 0, out, sizeof(out));
    ASSERT_STREQ("defold", out);
}

TEST_F(EngineTest, ServiceInstanceNameIncludesSuffix)
{
    char out[64];
    dmEngineService::BuildServiceInstanceName("Local Host", "8001", "89abcdef", out, sizeof(out));
    ASSERT_STREQ("defold-local-host-8001-89abcdef", out);
}

TEST_F(EngineTest, ServiceInstanceNamePreservesSuffixWhenTruncatingAddress)
{
    char out[64];
    dmEngineService::BuildServiceInstanceName("very-long-local-address-name-for-discovery-target-node", "8001", "89abcdef", out, sizeof(out));
    ASSERT_STREQ("defold-very-long-local-address-name-for-discovery-8001-89abcdef", out);
}

TEST_F(EngineTest, DiscoveryIdentityUsesRoutableAddress)
{
    char out[128];
    dmEngineService::BuildDiscoveryIdentity("fe80::1", "editor-host", "Acme", "Device", "Linux", out, sizeof(out));
    ASSERT_STREQ("fe80::1", out);
}

TEST_F(EngineTest, DiscoveryIdentityFallsBackToHostnameForLoopback)
{
    char out[128];
    dmEngineService::BuildDiscoveryIdentity("127.0.0.1", "editor-host", "Acme", "Device", "Linux", out, sizeof(out));
    ASSERT_STREQ("editor-host", out);
}

TEST_F(EngineTest, DiscoveryIdentityFallsBackToManufacturerAndModel)
{
    char out[128];
    dmEngineService::BuildDiscoveryIdentity("localhost", "localhost", "Acme", "Device", "Android", out, sizeof(out));
    ASSERT_STREQ("Acme-Device", out);
}

TEST_F(EngineTest, DiscoveryRetryDelayBackoffCapsAtThirtySeconds)
{
    ASSERT_EQ(1000000ULL, dmEngineService::GetDiscoveryRetryDelayUsec(0));
    ASSERT_EQ(2000000ULL, dmEngineService::GetDiscoveryRetryDelayUsec(1));
    ASSERT_EQ(16000000ULL, dmEngineService::GetDiscoveryRetryDelayUsec(4));
    ASSERT_EQ(30000000ULL, dmEngineService::GetDiscoveryRetryDelayUsec(5));
    ASSERT_EQ(30000000ULL, dmEngineService::GetDiscoveryRetryDelayUsec(8));
}

#if !defined(__EMSCRIPTEN__)

class EngineServiceOpenAPITest : public jc_test_params_class<bool>
{
public:
    dmEngineService::HEngineService m_Service;
    dmArray<char> m_Body;
    char m_ContentType[64];
    char m_Allow[16];
    char m_TransferEncoding[32];
    const char* m_Token;
    int m_Status;
    int32_atomic_t m_Done;
    const char* m_Method;
    const char* m_Path;
    dmHttpClient::Result m_Result;

    void SetUp()
    {
        m_Token = 0;
        dmSocket::Initialize();
        m_Service = dmEngineService::New(0);
        ASSERT_NE((dmEngineService::HEngineService)0, m_Service);
        dmEngineService::EngineState state;
        state.m_ConnectionAppMode = true;
        dmEngineService::InitState(m_Service, &state);
    }

    void TearDown()
    {
        dmEngineService::Delete(m_Service);
        dmSocket::Finalize();
    }

    static void Header(dmHttpClient::HResponse, void* context, int status, const char* key, const char* value)
    {
        EngineServiceOpenAPITest* self = (EngineServiceOpenAPITest*)context;
        self->m_Status = status;
        if (dmStrCaseCmp(key, "Transfer-Encoding") == 0)
            dmStrlCpy(self->m_TransferEncoding, value, sizeof(self->m_TransferEncoding));
        if (dmStrCaseCmp(key, "Content-Type") == 0)
            dmStrlCpy(self->m_ContentType, value, sizeof(self->m_ContentType));
        if (dmStrCaseCmp(key, "Allow") == 0)
            dmStrlCpy(self->m_Allow, value, sizeof(self->m_Allow));
    }

    static void Content(dmHttpClient::HResponse, void* context, int status, const void* data, uint32_t size,
                        int32_t, uint32_t, uint32_t, uint32_t, const char*)
    {
        EngineServiceOpenAPITest* self = (EngineServiceOpenAPITest*)context;
        self->m_Status = status;
        if (size)
        {
            uint32_t offset = self->m_Body.Size();
            self->m_Body.SetCapacity(offset + size + 1);
            self->m_Body.SetSize(offset + size);
            memcpy(self->m_Body.Begin() + offset, data, size);
        }
    }

    static dmHttpClient::Result WriteHeaders(dmHttpClient::HResponse response, void* context)
    {
        EngineServiceOpenAPITest* self = (EngineServiceOpenAPITest*)context;
        return self->m_Token ? dmHttpClient::WriteHeader(response, "X-Defold-Remote-Token", self->m_Token) : dmHttpClient::RESULT_OK;
    }

    static void Request(void* context)
    {
        EngineServiceOpenAPITest* self = (EngineServiceOpenAPITest*)context;
        char url[64];
        dmSnPrintf(url, sizeof(url), "http://127.0.0.1:%u", dmEngineService::GetPort(self->m_Service));
        dmURI::Parts uri;
        dmURI::Parse(url, &uri);
        dmHttpClient::NewParams params;
        params.m_Userdata = self;
        params.m_HttpHeader = Header;
        params.m_HttpWriteHeaders = WriteHeaders;
        params.m_HttpContent = Content;
        params.m_RequestTimeout = 5000000;
        params.m_MaxGetRetries = 1;
        dmHttpClient::HClient client = dmHttpClient::New(&params, &uri);
        self->m_Result = dmHttpClient::Request(client, self->m_Method, self->m_Path);
        dmHttpClient::Delete(client);
        dmAtomicStore32(&self->m_Done, 1);
    }

    void Fetch(const char* method, const char* path)
    {
        m_Method = method;
        m_Path = path;
        m_Status = 0;
        m_ContentType[0] = 0;
        m_Allow[0] = 0;
        m_TransferEncoding[0] = 0;
        m_Body.SetSize(0);
        dmAtomicStore32(&m_Done, 0);
        dmThread::Thread client = dmThread::New(Request, 0x80000, this, "openapi-client");
        while (!dmAtomicGet32(&m_Done))
            dmWebServer::Update(dmEngineService::GetWebServer(m_Service));
        dmThread::Join(client);
        if (m_Body.Full())
            m_Body.OffsetCapacity(1);
        m_Body.Push(0);
    }

    static void ExtensionHandler(void* context, dmWebServer::Request* request)
    {
        const char* body = (const char*)context;
        dmWebServer::SetStatusCode(request, 200);
        dmWebServer::SendAttribute(request, "Content-Type", "text/plain");
        dmWebServer::Send(request, body, strlen(body));
    }
};

// The actual discovery endpoint must expose all built-ins and take precedence over the profiler's root fallback.
TEST_F(EngineServiceOpenAPITest, BuiltinDocument)
{
    dmEngineService::InitProfiler(m_Service, 0, 0);
    dmEngineService::InitProfiler(m_Service, 0, 0); // Reboot reuses the service.
    Fetch("GET", "/openapi.json");
    ASSERT_EQ(dmHttpClient::RESULT_OK, m_Result);
    ASSERT_EQ(200, m_Status);
    ASSERT_STREQ("application/json", m_ContentType);
    ASSERT_NE((const char*)0, strstr(m_Body.Begin(), "\"openapi\":\"3.0.3\""));
    const char* paths[] = {"/ping", "/info", "/state", "/openapi.json", "/scene_graph", "/resources_data", "/gameobjects_data", "/",
        "/post/{socket}/{message_type}", "/post/@system/reboot", "/post/@system/exit", "/post/@system/run_script",
        "/post/@render/resize", "/post/@resource/reload"};
    for (uint32_t i = 0; i < DM_ARRAY_SIZE(paths); ++i)
    {
        char key[128];
        dmSnPrintf(key, sizeof(key), "\"%s\":", paths[i]);
        const char* occurrence = strstr(m_Body.Begin(), key);
        ASSERT_NE((const char*)0, occurrence);
        ASSERT_EQ((const char*)0, strstr(occurrence + strlen(key), key));
    }
    Fetch("GET", "/");
    ASSERT_EQ(200, m_Status);
    ASSERT_STREQ("text/html", m_ContentType);
}

// Extension requests and metadata must survive either profiler initialization order and removal/re-registration.
TEST_P(EngineServiceOpenAPITest, ExtensionLifecycle)
{
    static const char json[] = "{\"/extension/test\":{\"get\":{\"responses\":{\"200\":{\"description\":\"Extension response\"}}}}}";
    static const char response[] = "Extension response";
    dmWebServer::HandlerParams params;
    params.m_Handler = ExtensionHandler;
    params.m_Userdata = (void*)response;
    dmWebServer::HServer server = dmEngineService::GetWebServer(m_Service);
    if (GetParam())
        dmEngineService::InitProfiler(m_Service, 0, 0);
    ASSERT_EQ(dmWebServer::RESULT_OK, dmWebServer::AddHandler(server, "/extension", &params, json));

    Fetch("GET", "/extension/test");
    ASSERT_EQ(dmHttpClient::RESULT_OK, m_Result);
    ASSERT_EQ(200, m_Status);
    ASSERT_STREQ("text/plain", m_ContentType);
    ASSERT_STREQ(response, m_Body.Begin());

    dmEngineService::InitProfiler(m_Service, 0, 0);
    for (uint32_t registration = 0; registration < 2; ++registration)
    {
        if (registration != 0)
            ASSERT_EQ(dmWebServer::RESULT_OK, dmWebServer::AddHandler(server, "/extension", &params, json));

        Fetch("GET", "/openapi.json");
        ASSERT_EQ(dmHttpClient::RESULT_OK, m_Result);
        ASSERT_EQ(200, m_Status);
        ASSERT_STREQ("application/json", m_ContentType);
        ASSERT_NE((const char*)0, strstr(m_Body.Begin(), "\"/extension/test\""));

        Fetch("GET", "/extension/test");
        ASSERT_EQ(dmHttpClient::RESULT_OK, m_Result);
        ASSERT_EQ(200, m_Status);
        ASSERT_STREQ("text/plain", m_ContentType);
        ASSERT_STREQ(response, m_Body.Begin());

        ASSERT_EQ(dmWebServer::RESULT_OK, dmWebServer::RemoveHandler(server, "/extension"));
        Fetch("GET", "/openapi.json");
        ASSERT_EQ(dmHttpClient::RESULT_OK, m_Result);
        ASSERT_EQ(200, m_Status);
        ASSERT_STREQ("application/json", m_ContentType);
        ASSERT_EQ((const char*)0, strstr(m_Body.Begin(), "\"/extension/test\""));

        Fetch("GET", "/extension/test");
        ASSERT_EQ(dmHttpClient::RESULT_OK, m_Result);
        ASSERT_EQ(200, m_Status);
        ASSERT_STREQ("text/html", m_ContentType);

        // Removing an extension may move the root fallback ahead of the other profiler handlers.
        Fetch("GET", "/scene_graph");
        ASSERT_EQ(500, m_Status);
        ASSERT_STREQ("text/plain", m_ContentType);
        ASSERT_STREQ("Profiler state is not initialized", m_Body.Begin());

        dmEngineService::InitProfiler(m_Service, 0, 0); // Reboot reuses the service.
    }

    Fetch("GET", "/");
    ASSERT_EQ(dmHttpClient::RESULT_OK, m_Result);
    ASSERT_EQ(200, m_Status);
    ASSERT_STREQ("text/html", m_ContentType);
}

// Verifies authentication errors reach the client instead of being accidentally sent as HTTP 200.
TEST_F(EngineServiceOpenAPITest, RemoteHttpAuthorization)
{
    dmEngineService::RemoteState remote;
    remote.m_Token[0] = 0;
    dmWebServer::HServer server = dmEngineService::GetWebServer(m_Service);
    ASSERT_EQ(dmWebServer::RESULT_OK, dmWebServer::RemoveHandler(server, "/remote"));
    dmWebServer::HandlerParams params;
    params.m_Handler = dmEngineService::RemoteState::Handler;
    params.m_Userdata = &remote;
    ASSERT_EQ(dmWebServer::RESULT_OK, dmWebServer::AddHandler(server, "/remote", &params));
    Fetch("GET", "/remote/status");
    ASSERT_EQ(404, m_Status);
    dmStrlCpy(remote.m_Token, "0123456789abcdef", sizeof(remote.m_Token));
    Fetch("GET", "/remote/status");
    ASSERT_EQ(403, m_Status);
    m_Token = "0123456789abcdef";
    Fetch("GET", "/remote/status");
    ASSERT_EQ(200, m_Status);
    ASSERT_NE((const char*)0, strstr(m_Body.Begin(), "\"protocol\":1"));
    Fetch("GET", "/remote/input");
    ASSERT_EQ(405, m_Status);
    ASSERT_STREQ("POST", m_Allow);
    Fetch("GET", "/remote/unknown");
    ASSERT_EQ(404, m_Status);
}

// Verifies an unavailable frame is a bodyless HTTP 204, without illegal chunked transfer framing.
TEST_F(EngineServiceOpenAPITest, RemoteHttpNoContent)
{
    dmEngineService::RemoteState remote;
    dmStrlCpy(remote.m_Token, "0123456789abcdef", sizeof(remote.m_Token));
    dmWebServer::HServer server = dmEngineService::GetWebServer(m_Service);
    ASSERT_EQ(dmWebServer::RESULT_OK, dmWebServer::RemoveHandler(server, "/remote"));
    dmWebServer::HandlerParams params;
    params.m_Handler = dmEngineService::RemoteState::Handler;
    params.m_Userdata = &remote;
    ASSERT_EQ(dmWebServer::RESULT_OK, dmWebServer::AddHandler(server, "/remote", &params));
    m_Token = "0123456789abcdef";
    Fetch("GET", "/remote/frame");
    ASSERT_EQ(204, m_Status);
    ASSERT_STREQ("", m_TransferEncoding);
    ASSERT_EQ(1, m_Body.Size()); // Fixture's trailing zero only.
    ASSERT_TRUE(remote.m_Requested);
    ASSERT_NE((void*)0, remote.Buffer(1, 1));
    remote.Publish();
    Fetch("GET", "/remote/frame");
    ASSERT_EQ(200, m_Status);
    ASSERT_EQ(21, m_Body.Size());
    ASSERT_EQ(0, memcmp("DFR1", m_Body.Begin(), 4));
}

static const bool profiler_initialization_orders[] = {false, true};
INSTANTIATE_TEST_CASE_P(ProfilerInitializationOrder, EngineServiceOpenAPITest, jc_test_values_in(profiler_initialization_orders));

// Both registration APIs must serve undocumented extensions through HTTP without changing discovery.
TEST_F(EngineServiceOpenAPITest, UndocumentedExtensionLifecycle)
{
    static const char response[] = "Undocumented extension response";
    dmWebServer::HandlerParams params;
    params.m_Handler = ExtensionHandler;
    params.m_Userdata = (void*)response;
    dmWebServer::HServer server = dmEngineService::GetWebServer(m_Service);
    dmEngineService::InitProfiler(m_Service, 0, 0);
    Fetch("GET", "/openapi.json");
    ASSERT_EQ(200, m_Status);
    dmArray<char> original;
    original.SetCapacity(m_Body.Size());
    original.PushArray(m_Body.Begin(), m_Body.Size());

    for (uint32_t registration = 0; registration < 2; ++registration)
    {
        dmWebServer::Result result = registration == 0
            ? dmWebServer::AddHandler(server, "/undocumented", &params)
            : dmWebServer::AddHandler(server, "/undocumented", &params, 0);
        ASSERT_EQ(dmWebServer::RESULT_OK, result);
        Fetch("GET", "/undocumented");
        ASSERT_EQ(dmHttpClient::RESULT_OK, m_Result);
        ASSERT_EQ(200, m_Status);
        ASSERT_STREQ(response, m_Body.Begin());
        Fetch("GET", "/openapi.json");
        ASSERT_EQ(200, m_Status);
        ASSERT_STREQ(original.Begin(), m_Body.Begin());

        ASSERT_EQ(dmWebServer::RESULT_OK, dmWebServer::RemoveHandler(server, "/undocumented"));
        Fetch("GET", "/undocumented");
        ASSERT_EQ(200, m_Status);
        ASSERT_STREQ("text/html", m_ContentType);
        Fetch("GET", "/openapi.json");
        ASSERT_EQ(200, m_Status);
        ASSERT_STREQ(original.Begin(), m_Body.Begin());
    }
}

// Overlapping extension prefixes must select the most specific handler regardless of registration and removal order.
TEST_F(EngineServiceOpenAPITest, MostSpecificHandler)
{
    static const char parent_json[] = "{\"/extension\":{\"get\":{\"responses\":{\"200\":{\"description\":\"Parent response\"}}}}}";
    static const char child_json[] = "{\"/extension/child\":{\"get\":{\"responses\":{\"200\":{\"description\":\"Child response\"}}}}}";
    static const char parent_response[] = "Parent response";
    static const char child_response[] = "Child response";
    dmWebServer::HandlerParams params;
    params.m_Handler = ExtensionHandler;
    params.m_Userdata = (void*)parent_response;
    dmWebServer::HServer server = dmEngineService::GetWebServer(m_Service);
    ASSERT_EQ(dmWebServer::RESULT_OK, dmWebServer::AddHandler(server, "/extension", &params, parent_json));
    params.m_Userdata = (void*)child_response;
    ASSERT_EQ(dmWebServer::RESULT_OK, dmWebServer::AddHandler(server, "/extension/child", &params, child_json));
    dmEngineService::InitProfiler(m_Service, 0, 0);

    Fetch("GET", "/extension/child");
    ASSERT_EQ(dmHttpClient::RESULT_OK, m_Result);
    ASSERT_STREQ(child_response, m_Body.Begin());

    ASSERT_EQ(dmWebServer::RESULT_OK, dmWebServer::RemoveHandler(server, "/extension"));
    Fetch("GET", "/extension/child");
    ASSERT_EQ(dmHttpClient::RESULT_OK, m_Result);
    ASSERT_STREQ(child_response, m_Body.Begin());

    params.m_Userdata = (void*)parent_response;
    ASSERT_EQ(dmWebServer::RESULT_OK, dmWebServer::AddHandler(server, "/extension", &params, parent_json));
    Fetch("GET", "/extension/child");
    ASSERT_EQ(dmHttpClient::RESULT_OK, m_Result);
    ASSERT_STREQ(child_response, m_Body.Begin());

    ASSERT_EQ(dmWebServer::RESULT_OK, dmWebServer::RemoveHandler(server, "/extension/child"));
    Fetch("GET", "/extension/child");
    ASSERT_EQ(dmHttpClient::RESULT_OK, m_Result);
    ASSERT_STREQ(parent_response, m_Body.Begin());
}

// Discovery must reject unsupported methods and avoid serving its JSON for unrelated prefix matches.
TEST_F(EngineServiceOpenAPITest, DiscoveryRequestValidation)
{
    Fetch("DELETE", "/openapi.json");
    ASSERT_EQ(405, m_Status);
    ASSERT_STREQ("GET", m_Allow);
    Fetch("GET", "/openapi.json/other");
    ASSERT_EQ(404, m_Status);
    Fetch("GET", "/info");
    ASSERT_EQ(200, m_Status);
    ASSERT_STREQ("application/json", m_ContentType);
    Fetch("GET", "/state");
    ASSERT_EQ(200, m_Status);
    ASSERT_STREQ("application/json", m_ContentType);
}

#endif


// Verifies Remote stays disabled without a valid explicit token and rejects mismatched tokens.
TEST_F(EngineTest, RemoteAuthentication)
{
    dmEngineService::RemoteState state;
    state.m_Token[0] = 0;
    ASSERT_FALSE(state.Authorized(""));
    dmStrlCpy(state.m_Token, "0123456789abcdef", sizeof(state.m_Token));
    ASSERT_FALSE(state.Authorized(0));
    ASSERT_FALSE(state.Authorized("0123456789abcde"));
    ASSERT_FALSE(state.Authorized("0123456789abcdeg"));
    ASSERT_TRUE(state.Authorized("0123456789abcdef"));
}

// Verifies quick taps preserve both edges, bounded queues reject overflow, and disconnect releases input.
TEST_F(EngineTest, RemotePointerQueueAndTimeout)
{
    dmEngineService::RemoteState state;
    uint8_t packet[] = {0xff, 0xff, 0, 0x80, 1};
    ASSERT_FALSE(state.Enqueue(packet, 4));
    ASSERT_TRUE(state.Enqueue(packet, 5));
    packet[4] = 0;
    ASSERT_TRUE(state.Enqueue(packet, 5));
    dmEngineService::RemoteInput input;
    ASSERT_TRUE(state.Input(&input));
    ASSERT_EQ(65535, input.m_X);
    ASSERT_EQ(32768, input.m_Y);
    ASSERT_TRUE(input.m_Pressed);
    ASSERT_TRUE(state.Input(&input));
    ASSERT_FALSE(input.m_Pressed);
    packet[4] = 1;
    for (uint32_t i = 0; i < 64; ++i) ASSERT_TRUE(state.Enqueue(packet, 5));
    ASSERT_FALSE(state.Enqueue(packet, 5));
    state.m_InputTime = dmTime::GetMonotonicTime() - 1000001;
    ASSERT_TRUE(state.Input(&input));
    ASSERT_FALSE(input.m_Pressed);
    ASSERT_FALSE(state.Input(&input));
}

// Verifies on-demand capture, explicit byte order, size limits, and reboot clearing old frames/input.
TEST_F(EngineTest, RemoteFrameWireFormatAndReset)
{
    dmEngineService::RemoteState state;
    dmStrlCpy(state.m_Token, "0123456789abcdef", sizeof(state.m_Token));
    ASSERT_EQ((void*)0, state.Buffer(2, 1));
    state.m_Requested = true;
    ASSERT_EQ((void*)0, state.Buffer(0, 1));
    ASSERT_EQ((void*)0, state.Buffer(4097, 1));
    uint8_t* buffer = (uint8_t*)state.Buffer(2, 1);
    ASSERT_NE((void*)0, buffer);
    const uint8_t pixels[] = {1, 2, 3, 4, 5, 6, 7, 8};
    memcpy(buffer, pixels, sizeof(pixels));
    state.Publish();
    ASSERT_EQ(24, state.m_Frame.Size());
    ASSERT_EQ(0, memcmp("DFR1\x02\0\0\0\x01\0\0\0\x01\0\0\0", state.m_Frame.Begin(), 16));
    ASSERT_EQ(0, memcmp(pixels, state.m_Frame.Begin() + 16, 8));
    ASSERT_EQ((void*)0, state.Buffer(2, 1));
    state.Reset();
    ASSERT_EQ(0, state.m_Frame.Size());
    dmEngineService::RemoteInput input;
    ASSERT_FALSE(state.Input(&input));
}

// Verifies capture pacing rejects early requests, resumes at the deadline, and resets on reboot.
TEST_F(EngineTest, RemoteFramePacing)
{
    dmEngineService::RemoteState state;
    dmStrlCpy(state.m_Token, "0123456789abcdef", sizeof(state.m_Token));
    state.m_Requested = true;
    state.m_NextFrameTime = dmTime::GetMonotonicTime() + 1000000;
    ASSERT_EQ((void*)0, state.Buffer(2, 1));
    state.m_NextFrameTime = dmTime::GetMonotonicTime();
    ASSERT_NE((void*)0, state.Buffer(2, 1));
    state.Publish();
    ASSERT_GE(state.m_NextFrameTime, state.m_FrameTime);
    ASSERT_LE(state.m_NextFrameTime, state.m_FrameTime + 16667);
    state.Reset();
    ASSERT_EQ(0, state.m_NextFrameTime);
}

// Verifies large and narrow frames retain aspect ratio without zero-sized output.
TEST_F(EngineTest, RemoteFrameDownsample)
{
    dmEngineService::RemoteState state;
    dmStrlCpy(state.m_Token, "0123456789abcdef", sizeof(state.m_Token));
    state.m_Requested = true;
    ASSERT_NE((void*)0, state.Buffer(1920, 1080));
    state.Publish();
    ASSERT_EQ(16 + 960 * 540 * 4, state.m_Frame.Size());
    state.Reset();
    state.m_Requested = true;
    ASSERT_NE((void*)0, state.Buffer(1, 4096));
    state.Publish();
    ASSERT_EQ(16 + 960 * 4, state.m_Frame.Size());
}
