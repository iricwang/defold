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

#import <Foundation/Foundation.h>
#import <VideoToolbox/VideoToolbox.h>
#include <jni.h>
#import <CoreImage/CoreImage.h>
#import <Metal/Metal.h>

// One synchronous, hardware-only encoder per connection; no frame-reordering queue.
typedef struct {
    VTCompressionSessionRef session;
    dispatch_semaphore_t completed;
    CFMutableDataRef packet;
    OSStatus status;
    int width, height;
    int64_t frame;
    CIContext *scaler;
    CVPixelBufferRef source;
} RemoteEncoder;

static void appendNAL(CFMutableDataRef packet, const void *bytes, size_t size) {
    const uint8_t prefix[] = {0, 0, 0, 1};
    CFDataAppendBytes(packet, prefix, 4);
    CFDataAppendBytes(packet, bytes, size);
}

static void encoded(void *context, void *frame, OSStatus status,
                    VTEncodeInfoFlags flags, CMSampleBufferRef sample) {
    RemoteEncoder *encoder = context;
    encoder->status = status;
    if (!status && sample && CMSampleBufferDataIsReady(sample)) {
        CMFormatDescriptionRef format = CMSampleBufferGetFormatDescription(sample);
        CFArrayRef attachments = CMSampleBufferGetSampleAttachmentsArray(sample, false);
        bool key = !attachments || !CFDictionaryContainsKey(CFArrayGetValueAtIndex(attachments, 0), kCMSampleAttachmentKey_NotSync);
        int nalLength = 4;
        if (key) {
            size_t count = 0;
            const uint8_t *bytes; size_t size;
            if (!CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, 0, &bytes, &size, &count, &nalLength)) {
                for (size_t i = 0; i < count; ++i) {
                    if (!CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, i, &bytes, &size, NULL, NULL))
                        appendNAL(encoder->packet, bytes, size);
                }
            }
        }
        CMBlockBufferRef block = CMSampleBufferGetDataBuffer(sample);
        size_t size = CMBlockBufferGetDataLength(block);
        uint8_t *bytes = malloc(size);
        if (bytes && !CMBlockBufferCopyDataBytes(block, 0, size, bytes)) {
            for (size_t offset = 0; offset + 4 <= size;) {
                uint32_t length; memcpy(&length, bytes + offset, 4); length = CFSwapInt32BigToHost(length); offset += 4;
                if (length > size - offset) { encoder->status = -1; break; }
                appendNAL(encoder->packet, bytes + offset, length); offset += length;
            }
        } else encoder->status = -1;
        free(bytes);
    }
    dispatch_semaphore_signal(encoder->completed);
}

JNIEXPORT jlong JNICALL Java_com_defold_editor_RemoteVideoEncoder_create(JNIEnv *env, jclass cls, jint width, jint height) {
    @autoreleasepool {
        if (width < 2 || height < 2 || width > 4096 || height > 4096 || (int64_t)width * height > 8388608) return 0;
        RemoteEncoder *encoder = calloc(1, sizeof(*encoder));
        if (!encoder) return 0;
        encoder->width = width; encoder->height = height;
        encoder->completed = dispatch_semaphore_create(0);
        encoder->packet = CFDataCreateMutable(NULL, 0);
        NSDictionary *spec = @{(__bridge NSString *)kVTVideoEncoderSpecification_RequireHardwareAcceleratedVideoEncoder: @YES,
                               (__bridge NSString *)kVTVideoEncoderSpecification_EnableLowLatencyRateControl: @YES};
        NSDictionary *pixels = @{(__bridge NSString *)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA),
                                 (__bridge NSString *)kCVPixelBufferIOSurfacePropertiesKey: @{}};
        OSStatus status = VTCompressionSessionCreate(NULL, width, height, kCMVideoCodecType_H264,
            (__bridge CFDictionaryRef)spec, (__bridge CFDictionaryRef)pixels, NULL, encoded, encoder, &encoder->session);
        if (!status) {
            NSDictionary *properties = @{
                (__bridge NSString *)kVTCompressionPropertyKey_RealTime: @YES,
                (__bridge NSString *)kVTCompressionPropertyKey_AllowFrameReordering: @NO,
                (__bridge NSString *)kVTCompressionPropertyKey_ProfileLevel: (__bridge NSString *)kVTProfileLevel_H264_Main_AutoLevel,
                (__bridge NSString *)kVTCompressionPropertyKey_ExpectedFrameRate: @60,
                (__bridge NSString *)kVTCompressionPropertyKey_MaxKeyFrameInterval: @60,
                (__bridge NSString *)kVTCompressionPropertyKey_AverageBitRate: @(MAX(6000000, MIN(40000000, width * height * 10))),
                (__bridge NSString *)kVTCompressionPropertyKey_MaxFrameDelayCount: @0};
            status = VTSessionSetProperties(encoder->session, (__bridge CFDictionaryRef)properties);
            if (!status) status = VTCompressionSessionPrepareToEncodeFrames(encoder->session);
        }
        if (status) {
            if (encoder->session) { VTCompressionSessionInvalidate(encoder->session); CFRelease(encoder->session); }
            CFRelease(encoder->packet); dispatch_release(encoder->completed); free(encoder); return 0;
        }
        return (jlong)(intptr_t)encoder;
    }
}

JNIEXPORT jbyteArray JNICALL Java_com_defold_editor_RemoteVideoEncoder_encode(JNIEnv *env, jclass cls, jlong handle, jintArray rgb, jint sourceWidth, jint sourceHeight) {
    @autoreleasepool {
    RemoteEncoder *encoder = (RemoteEncoder *)(intptr_t)handle;
    if (!encoder || sourceWidth < 1 || sourceHeight < 1 || sourceWidth > 4096 || sourceHeight > 4096 || (*env)->GetArrayLength(env, rgb) != sourceWidth * sourceHeight) return NULL;
    CVPixelBufferRef pixel = NULL;
    if (CVPixelBufferPoolCreatePixelBuffer(NULL, VTCompressionSessionGetPixelBufferPool(encoder->session), &pixel)) return NULL;
    bool resize = sourceWidth != encoder->width || sourceHeight != encoder->height;
    if (resize && !encoder->source) {
        NSDictionary *attributes = @{(__bridge NSString *)kCVPixelBufferIOSurfacePropertiesKey: @{}};
        if (CVPixelBufferCreate(NULL, sourceWidth, sourceHeight, kCVPixelFormatType_32BGRA, (__bridge CFDictionaryRef)attributes, &encoder->source)) { CVPixelBufferRelease(pixel); return NULL; }
        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        encoder->scaler = [[CIContext contextWithMTLDevice:device options:@{kCIContextWorkingColorSpace:[NSNull null],kCIContextCacheIntermediates:@NO}] retain];
        [device release];
        if (!encoder->scaler) { CVPixelBufferRelease(pixel); return NULL; }
    }
    CVPixelBufferRef copyTarget = resize ? encoder->source : pixel;
    if (CVPixelBufferGetWidth(copyTarget) != (size_t)sourceWidth || CVPixelBufferGetHeight(copyTarget) != (size_t)sourceHeight) { CVPixelBufferRelease(pixel); return NULL; }
    CVPixelBufferLockBaseAddress(copyTarget, 0);
    uint8_t *destination = CVPixelBufferGetBaseAddress(copyTarget);
    size_t stride = CVPixelBufferGetBytesPerRow(copyTarget), row = sourceWidth * 4;
    jint *source = (*env)->GetPrimitiveArrayCritical(env, rgb, NULL);
    if (!source) { CVPixelBufferUnlockBaseAddress(copyTarget, 0); CVPixelBufferRelease(pixel); return NULL; }
    for (int y = 0; y < sourceHeight; ++y) memcpy(destination + y * stride, (uint8_t *)source + y * row, row);
    (*env)->ReleasePrimitiveArrayCritical(env, rgb, source, JNI_ABORT);
    CVPixelBufferUnlockBaseAddress(copyTarget, 0);
    if (resize) {
        @autoreleasepool {
            CIImage *image = [CIImage imageWithCVPixelBuffer:encoder->source options:@{kCIImageColorSpace:[NSNull null]}];
            image = [image imageByApplyingTransform:CGAffineTransformMakeScale((double)encoder->width/sourceWidth, (double)encoder->height/sourceHeight)];
            [encoder->scaler render:image toCVPixelBuffer:pixel bounds:CGRectMake(0, 0, encoder->width, encoder->height) colorSpace:NULL];
        }
    }
    CFDataSetLength(encoder->packet, 0);
    encoder->status = 0;
    OSStatus status = VTCompressionSessionEncodeFrame(encoder->session, pixel, CMTimeMake(encoder->frame++, 60), CMTimeMake(1, 60), NULL, NULL, NULL);
    CVPixelBufferRelease(pixel);
    if (status || dispatch_semaphore_wait(encoder->completed, dispatch_time(DISPATCH_TIME_NOW, 500 * NSEC_PER_MSEC)) || encoder->status) return NULL;
    jsize length = (jsize)CFDataGetLength(encoder->packet);
    jbyteArray result = (*env)->NewByteArray(env, length);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, length, (const jbyte *)CFDataGetBytePtr(encoder->packet));
    return result;
    }
}

JNIEXPORT void JNICALL Java_com_defold_editor_RemoteVideoEncoder_destroy(JNIEnv *env, jclass cls, jlong handle) {
    RemoteEncoder *encoder = (RemoteEncoder *)(intptr_t)handle;
    if (!encoder) return;
    VTCompressionSessionInvalidate(encoder->session);
    CFRelease(encoder->session); CFRelease(encoder->packet);
    if (encoder->source) CVPixelBufferRelease(encoder->source);
    [encoder->scaler release];
    dispatch_release(encoder->completed); free(encoder);
}
