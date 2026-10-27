# macOS Remote hardware video

`build-macos.sh` builds the editor JNI resource from `remote_video.m` using the
installed JDK headers and Xcode SDK. `scripts/macos/build-patch.sh` calls it before
bundling. The generated dylib is ad-hoc signed, packaged as a JAR resource and
extracted to a private temporary file at runtime. No external encoder process,
screen recording permission or additional app installation is needed.

```sh
bash remote/native/build-macos.sh arm64
# On an Intel Mac / runner:
bash remote/native/build-macos.sh x86_64
```

The encoder uses Apple's VideoToolbox hardware H.264, low-latency rate control,
real-time mode, no B-frame reordering, a 60-frame keyframe interval, and a target
bitrate of 10 bits/pixel/second (6–40 Mbit/s; 20.7 Mbit/s at 1080p). The GPU scales
BGRA into encoder pixel buffers through Metal/Core Image; frame storage is reused.
Each connection has at most one submitted frame. The editor drops superseded raw
frames, never compressed reference frames. A stalled writer is disconnected after
250 ms so reconnection starts from a fresh IDR. A slow network can still induce
transport/device buffering: this is not a guaranteed glass-to-glass latency bound.

The APK uses an available hardware MediaCodec H.264 decoder, an Android low-latency
feature when advertised, and a GPU surface, without Bitmap copies or a playback
jitter buffer. Unsupported platforms/decoders fall back to the existing JPEG
stream. Windows/Linux hardware encoding is not implemented in this patch.

Architecture references (no source copied or bundled from these projects):

- [Sunshine](https://github.com/LizardByte/Sunshine): hardware game streaming host.
- [Moonlight Android](https://github.com/moonlight-stream/moonlight-android): hardware decoding client.
- [scrcpy developer guide](https://github.com/Genymobile/scrcpy/blob/master/doc/develop.md): separate video/control connections and immediate rendering.
- [Apple low-latency encoder option](https://developer.apple.com/documentation/videotoolbox/kvtvideoencoderspecification_enablelowlatencyratecontrol)
- [Android low-latency decoding](https://source.android.com/docs/core/media/low-latency-media)

The implementation calls platform APIs directly and retains the repository's
Defold License. Sunshine/Moonlight's GPL implementations have not been embedded.
