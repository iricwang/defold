# Remote hardware video

## Windows

Windows uses a locally compiled JNI library and Media Foundation hardware H.264
MFTs. Only `MFT_ENUM_FLAG_HARDWARE` encoders are enumerated; a failed adapter is
skipped and the first usable adapter is retained for subsequent connections.
The current Windows validation used NVIDIA GeForce RTX 3060. AMD/Intel drivers
still require device-specific validation. Machines without a usable encoder,
including systems lacking Media Foundation, retain JPEG fallback.

The encoder is asynchronous internally, but the host permits only one input/output
pair in flight. Baseline profile disallows B frames; the target is 60 FPS, one
keyframe per 60 frames and 6–40 Mbit/s. SPS/PPS are included with IDR frames.
Input is top-left RGB, converted/scaled on the CPU to BT.709 limited-range NV12
using reused buffers and column indices. H.264 compression runs on hardware.
Each wait is bounded to 500 ms; a failed codec closes that stream for client fallback.
No FFmpeg process or additional codec installation is used at runtime.

Build with MSVC, Windows SDK and a full JDK 25 through the shared entry point:

```powershell
python -X utf8 scripts/windows/editor.py remote-video-build --jdk "C:/path/to/jdk-25"
```

Normal Windows `patch`/`full` builds compile and include this DLL automatically.
`RemoteVideoTest` verifies actual hardware output, portrait/landscape sizes,
decoder configuration and invalid inputs. `RemoteWindowsIntegrationTest` runs a
dedicated hidden engine with headless JavaFX, measures HTTP JPEG/H.264 frames,
and checks actual Lua input callbacks, drag coordinates, quick taps, lease expiry,
detached Game panels and disconnect release. It never opens a user project.

Windows touch is sent through the same Win32 input bridge as local Game input;
the shared input fields alone are insufficient on Windows. Press/release edges
are consumed at most once per completed engine capture, and the final remote
position is kept until local pointer activity. Remote readers request captures
independently of the visible JavaFX image. Native capture pacing preserves its
60 Hz phase to avoid dropping alternating frames due to render-clock jitter.

API references: [hardware MFT enumeration](https://learn.microsoft.com/en-us/windows/win32/api/mfapi/nf-mfapi-mftenumex),
[asynchronous MFT events](https://learn.microsoft.com/en-us/windows/win32/medfound/asynchronous-mfts),
[H.264 media types](https://learn.microsoft.com/en-us/windows/win32/medfound/h-264-video-encoder).

## macOS

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
stream. Linux hardware encoding is not implemented.

Architecture references (no source copied or bundled from these projects):

- [Sunshine](https://github.com/LizardByte/Sunshine): hardware game streaming host.
- [Moonlight Android](https://github.com/moonlight-stream/moonlight-android): hardware decoding client.
- [scrcpy developer guide](https://github.com/Genymobile/scrcpy/blob/master/doc/develop.md): separate video/control connections and immediate rendering.
- [Apple low-latency encoder option](https://developer.apple.com/documentation/videotoolbox/kvtvideoencoderspecification_enablelowlatencyratecontrol)
- [Android low-latency decoding](https://source.android.com/docs/core/media/low-latency-media)

The implementation calls platform APIs directly and retains the repository's
Defold License. Sunshine/Moonlight's GPL implementations have not been embedded.
