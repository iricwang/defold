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

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mftransform.h>
#include <mferror.h>
#include <codecapi.h>
#include <strmif.h>
#include <wrl/client.h>
#include <jni.h>
#include <vector>
#include <algorithm>
#include <memory>
#include <mutex>
#include <cstdio>
using Microsoft::WRL::ComPtr;
static std::mutex selectionMutex;
static GUID preferredEncoder = {};

// Hardware MFTs only: one input/output in flight, no software codec substitution.
struct Encoder {
    ComPtr<IMFTransform> transform;
    ComPtr<IMFMediaEventGenerator> events;
    ComPtr<IMFActivate> activation;
    std::vector<BYTE> nv12, headers;
    std::vector<int> columns;
    int sourceWidth = 0;
    int width, height;
    LONGLONG frame = 0;
    bool com = false, media = false, needInput = false;
    ~Encoder() {
        if (activation) activation->ShutdownObject();
        events.Reset(); transform.Reset(); activation.Reset();
        if (media) MFShutdown();
        if (com) CoUninitialize();
    }
    HRESULT wait(MediaEventType wanted) {
        ULONGLONG until = GetTickCount64() + 500;
        while (GetTickCount64() < until) {
            ComPtr<IMFMediaEvent> event;
            HRESULT hr = events->GetEvent(MF_EVENT_FLAG_NO_WAIT, &event);
            if (hr == MF_E_NO_EVENTS_AVAILABLE) { Sleep(1); continue; }
            if (FAILED(hr)) return hr;
            HRESULT status; event->GetStatus(&status);
            if (FAILED(status)) return status;
            MediaEventType type; event->GetType(&type);
            if (type == METransformNeedInput) needInput = true;
            if (type == wanted) return S_OK;
        }
        return HRESULT_FROM_WIN32(ERROR_TIMEOUT);
    }
};

static HRESULT setting(IMFTransform* transform, const GUID& key, ULONG value, bool boolean = false) {
    ComPtr<ICodecAPI> codec;
    HRESULT hr = transform->QueryInterface(IID_PPV_ARGS(&codec));
    if (FAILED(hr)) return hr;
    VARIANT v; VariantInit(&v);
    if (boolean) { v.vt = VT_BOOL; v.boolVal = value ? VARIANT_TRUE : VARIANT_FALSE; }
    else { v.vt = VT_UI4; v.ulVal = value; }
    return codec->SetValue(&key, &v);
}

static HRESULT mediaType(IMFMediaType** result, const GUID& subtype, int w, int h) {
    ComPtr<IMFMediaType> type;
    HRESULT hr = MFCreateMediaType(&type);
    if (FAILED(hr)) return hr;
    type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    type->SetGUID(MF_MT_SUBTYPE, subtype);
    MFSetAttributeSize(type.Get(), MF_MT_FRAME_SIZE, w, h);
    MFSetAttributeRatio(type.Get(), MF_MT_FRAME_RATE, 60, 1);
    MFSetAttributeRatio(type.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
    type->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
    type->SetUINT32(MF_MT_YUV_MATRIX, MFVideoTransferMatrix_BT709);
    type->SetUINT32(MF_MT_VIDEO_NOMINAL_RANGE, MFNominalRange_16_235);
    type->SetUINT32(MF_MT_VIDEO_PRIMARIES, MFVideoPrimaries_BT709);
    type->SetUINT32(MF_MT_TRANSFER_FUNCTION, MFVideoTransFunc_709);
    *result = type.Detach(); return S_OK;
}

static HRESULT configure(Encoder& e, IMFActivate* activation) {
    HRESULT hr = activation->ActivateObject(IID_PPV_ARGS(&e.transform));
    if (FAILED(hr)) return hr;
    e.activation = activation;
    ComPtr<IMFAttributes> attributes;
    if (FAILED(hr = e.transform->GetAttributes(&attributes))) return hr;
    if (FAILED(hr = attributes->SetUINT32(MF_TRANSFORM_ASYNC_UNLOCK, TRUE))) return hr;
    attributes->SetUINT32(MF_LOW_LATENCY, TRUE);
    if (FAILED(hr = e.transform.As(&e.events))) return hr;
    // Baseline additionally disallows B frames even on drivers ignoring the setting.
    setting(e.transform.Get(), CODECAPI_AVLowLatencyMode, 1, true);
    setting(e.transform.Get(), CODECAPI_AVEncMPVDefaultBPictureCount, 0);
    setting(e.transform.Get(), CODECAPI_AVEncMPVGOPSize, 60);
    setting(e.transform.Get(), CODECAPI_AVEncCommonRateControlMode, eAVEncCommonRateControlMode_CBR);
    ComPtr<IMFMediaType> output, input;
    if (FAILED(hr = mediaType(&output, MFVideoFormat_H264, e.width, e.height))) return hr;
    output->SetUINT32(MF_MT_AVG_BITRATE, std::clamp(e.width * e.height * 10, 6000000, 40000000));
    output->SetUINT32(MF_MT_MPEG2_PROFILE, eAVEncH264VProfile_Base);
    if (FAILED(hr = e.transform->SetOutputType(0, output.Get(), 0))) return hr;
    if (FAILED(hr = mediaType(&input, MFVideoFormat_NV12, e.width, e.height))) return hr;
    input->SetUINT32(MF_MT_DEFAULT_STRIDE, e.width);
    input->SetUINT32(MF_MT_SAMPLE_SIZE, e.width * e.height * 3 / 2);
    if (FAILED(hr = e.transform->SetInputType(0, input.Get(), 0))) return hr;
    if (FAILED(hr = e.transform->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0))) return hr;
    return e.transform->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
}

static jlong createEncoder(jint w, jint h) {
    if (w < 2 || h < 2 || w > 4096 || h > 4096 || (w & 1) || (h & 1) || (int64_t)w*h > 8388608) return 0;
    auto e = std::make_unique<Encoder>(); e->width = w; e->height = h;
    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(hr)) return 0;
    e->com = true;
    if (FAILED(MFStartup(MF_VERSION, MFSTARTUP_FULL))) return 0;
    e->media = true;
    MFT_REGISTER_TYPE_INFO input = {MFMediaType_Video, MFVideoFormat_NV12};
    MFT_REGISTER_TYPE_INFO output = {MFMediaType_Video, MFVideoFormat_H264};
    IMFActivate** list = nullptr; UINT32 count = 0;
    hr = MFTEnumEx(MFT_CATEGORY_VIDEO_ENCODER, MFT_ENUM_FLAG_HARDWARE | MFT_ENUM_FLAG_SORTANDFILTER,
                  &input, &output, &list, &count);
    if (FAILED(hr)) return 0;
    // Avoid retrying unavailable adapters on every resolution change/connection.
    GUID preferred;
    { std::lock_guard<std::mutex> lock(selectionMutex); preferred=preferredEncoder; }
    for (UINT32 i=0; i<count; ++i) {
        GUID id={};
        if (SUCCEEDED(list[i]->GetGUID(MFT_TRANSFORM_CLSID_Attribute,&id)) && IsEqualGUID(id,preferred)) {
            std::swap(list[0],list[i]); break;
        }
    }
    bool ready = false;
    for (UINT32 i = 0; i < count && !ready; ++i) {
        hr = configure(*e, list[i]);
        if (SUCCEEDED(hr)) {
            ready = true;
            GUID id={};
            if (SUCCEEDED(list[i]->GetGUID(MFT_TRANSFORM_CLSID_Attribute,&id))) {
                std::lock_guard<std::mutex> lock(selectionMutex); preferredEncoder=id;
            }
            WCHAR name[256] = {}; list[i]->GetString(MFT_FRIENDLY_NAME_Attribute, name, 256, nullptr);
            fwprintf(stderr, L"Remote H.264 hardware encoder: %ls\n", name);
        } else {
            fprintf(stderr, "Remote hardware encoder rejected configuration: 0x%08lx\n", (unsigned long)hr);
            list[i]->ShutdownObject(); e->events.Reset(); e->transform.Reset(); e->activation.Reset();
        }
    }
    for (UINT32 i = 0; i < count; ++i) list[i]->Release();
    CoTaskMemFree(list);
    if (!ready) return 0;
    e->nv12.resize((size_t)w*h*3/2);
    e->columns.resize(w);
    return (jlong)(intptr_t)e.release();
}

extern "C" JNIEXPORT jlong JNICALL Java_com_defold_editor_RemoteVideoEncoder_create(JNIEnv*, jclass, jint w, jint h) {
    try { return createEncoder(w,h); }
    catch (const std::exception& e) { fprintf(stderr,"Remote encoder allocation failed: %s\n",e.what()); return 0; }
}

static BYTE clampByte(int value) { return (BYTE)std::clamp(value, 0, 255); }
static void convert(Encoder& e, const jint* rgb, int sw, int sh) {
    const int w=e.width, h=e.height;
    // Nearest-neighbour scaling and BT.709 limited-range NV12; no per-frame allocation.
    // Chroma averages all four pixels, preserving thin UI/text better than point sampling.
    for (int y=0; y<h; y+=2) {
      const jint* rows[] = {rgb+(y*sh/h)*sw, rgb+((y+1)*sh/h)*sw};
      for (int x=0; x<w; x+=2) {
        int rs=0, gs=0, bs=0;
        for (int dy=0; dy<2; ++dy) for (int dx=0; dx<2; ++dx) {
            unsigned p=rows[dy][e.columns[x+dx]];
            int r=(p>>16)&255, g=(p>>8)&255, b=p&255;
            e.nv12[(y+dy)*w+x+dx]=clampByte(16+((47*r+157*g+16*b+128)>>8));
            rs+=r; gs+=g; bs+=b;
        }
        int index=w*h+(y/2)*w+x;
        e.nv12[index]=clampByte(128+((-26*rs-87*gs+112*bs+512)>>10));
        e.nv12[index+1]=clampByte(128+((112*rs-102*gs-10*bs+512)>>10));
      }
    }
}

static bool nal(const std::vector<BYTE>& bytes, int type) {
    for (size_t i=0; i+3<bytes.size(); ++i)
        if (!bytes[i] && !bytes[i+1] && bytes[i+2]==1 && (bytes[i+3]&31)==type) return true;
    return false;
}

static HRESULT encode(Encoder& e, std::vector<BYTE>& packet) {
    HRESULT hr;
    if (!e.needInput && FAILED(hr=e.wait(METransformNeedInput))) return hr;
    ComPtr<IMFMediaBuffer> buffer; ComPtr<IMFSample> sample;
    if (FAILED(hr=MFCreateMemoryBuffer((DWORD)e.nv12.size(), &buffer))) return hr;
    BYTE* destination;
    if (FAILED(hr=buffer->Lock(&destination,nullptr,nullptr))) return hr;
    memcpy(destination,e.nv12.data(),e.nv12.size()); buffer->Unlock();
    buffer->SetCurrentLength((DWORD)e.nv12.size());
    if (FAILED(hr=MFCreateSample(&sample))) return hr;
    if (FAILED(hr=sample->AddBuffer(buffer.Get()))) return hr;
    sample->SetSampleTime(e.frame*10000000/60); sample->SetSampleDuration(10000000/60); ++e.frame;
    if (FAILED(hr=e.transform->ProcessInput(0,sample.Get(),0))) return hr;
    e.needInput=false;
    if (FAILED(hr=e.wait(METransformHaveOutput))) return hr;
    MFT_OUTPUT_STREAM_INFO info = {};
    if (FAILED(hr=e.transform->GetOutputStreamInfo(0,&info))) return hr;
    MFT_OUTPUT_DATA_BUFFER out = {}; DWORD status=0;
    ComPtr<IMFSample> allocated;
    if (!(info.dwFlags & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES)) {
        ComPtr<IMFMediaBuffer> target;
        if (FAILED(hr=MFCreateMemoryBuffer(std::max<DWORD>(info.cbSize, 8u*1024*1024), &target))) return hr;
        if (FAILED(hr=MFCreateSample(&allocated))) return hr;
        if (FAILED(hr=allocated->AddBuffer(target.Get()))) return hr;
        out.pSample=allocated.Get();
    }
    hr=e.transform->ProcessOutput(0,1,&out,&status);
    if (out.pEvents) out.pEvents->Release();
    ComPtr<IMFSample> encoded;
    if (out.pSample==allocated.Get()) encoded=allocated;
    else encoded.Attach(out.pSample);
    if (FAILED(hr) || !encoded) return FAILED(hr)?hr:E_FAIL;
    ComPtr<IMFMediaBuffer> bytes;
    if (FAILED(hr=encoded->ConvertToContiguousBuffer(&bytes))) return hr;
    BYTE* data; DWORD size;
    if (FAILED(hr=bytes->Lock(&data,nullptr,&size))) return hr;
    if (size<=8*1024*1024) packet.assign(data,data+size);
    bytes->Unlock();
    if (packet.empty()) return E_FAIL;
    if (nal(packet,5) && (!nal(packet,7) || !nal(packet,8))) {
        ComPtr<IMFMediaType> type;
        UINT32 length=0;
        if (SUCCEEDED(e.transform->GetOutputCurrentType(0,&type)) && SUCCEEDED(type->GetBlobSize(MF_MT_MPEG_SEQUENCE_HEADER,&length)) && length<=65536) {
            e.headers.resize(length); type->GetBlob(MF_MT_MPEG_SEQUENCE_HEADER,e.headers.data(),length,nullptr);
        }
        if (!nal(e.headers,7) || !nal(e.headers,8)) return E_FAIL;
        packet.insert(packet.begin(),e.headers.begin(),e.headers.end());
    }
    if (!nal(packet,1) && !nal(packet,5)) return E_FAIL;
    return S_OK;
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_defold_editor_RemoteVideoEncoder_encode(JNIEnv* env, jclass, jlong handle, jintArray rgb, jint sw, jint sh) {
    try {
    auto* e=(Encoder*)(intptr_t)handle;
    if (!e || !rgb || sw<1 || sh<1 || sw>4096 || sh>4096 || env->GetArrayLength(rgb)!=(int64_t)sw*sh) return nullptr;
    if (e->sourceWidth != sw) {
        for (int x=0; x<e->width; ++x) e->columns[x]=x*sw/e->width;
        e->sourceWidth=sw;
    }
    jint* source=(jint*)env->GetPrimitiveArrayCritical(rgb,nullptr);
    if (!source) return nullptr;
    convert(*e,source,sw,sh); env->ReleasePrimitiveArrayCritical(rgb,source,JNI_ABORT);
    std::vector<BYTE> packet;
    HRESULT hr=encode(*e,packet);
    if (FAILED(hr)) { fprintf(stderr,"Remote hardware encode failed: 0x%08lx\n",(unsigned long)hr); return nullptr; }
    jbyteArray result=env->NewByteArray((jsize)packet.size());
    if (result) env->SetByteArrayRegion(result,0,(jsize)packet.size(),(const jbyte*)packet.data());
    return result;
    } catch (const std::exception& e) { fprintf(stderr,"Remote frame allocation failed: %s\n",e.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL Java_com_defold_editor_RemoteVideoEncoder_destroy(JNIEnv*, jclass, jlong handle) {
    delete (Encoder*)(intptr_t)handle;
}
