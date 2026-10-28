# Remote protocol 1

## Android → host (HTTP, port 8390)

`POST /pair` receives `{"code":"12345678"}` and returns `{"token":"...","name":"...","protocol":1}`. At most 5 attempts / 30 seconds; successful pairing rotates the single controller token. All following routes require `Authorization: Bearer <token>`. No redirects or browser CORS are enabled.

| Route | Method | Result |
| --- | --- | --- |
| `/status` | GET | JSON state `stopped`, `starting`, `running`, `stopping`, `error`; optional error; `audio:false` |
| `/start` | POST | Launch fixed command; idempotent while running; success only after authenticated engine readiness |
| `/stop` | POST | Request engine exit, then terminate/kill the owned child only if necessary; idempotent |
| `/frame` | GET | PNG 200, or bodyless 204 when unavailable; one frame request at a time |
| `/stream` | GET | Persistent frame stream, `application/x-defold-frames`; see framing below |
| `/input` | POST | 5 bytes: uint16 LE normalized X, uint16 LE normalized Y, uint8 pressed (0/1); origin top-left |

Control request bodies are bounded to 1024 bytes; image responses to 960×960×4 + 16 engine bytes. The Android client keeps only one pending display frame and bounded input tasks. Input events preserve press/release ordering. Holding a pointer sends a heartbeat every 200 ms.

`/stream` (APK 0.2.0+): each packet is uint32 **big-endian** PNG byte length, uint32 big-endian engine sequence, then PNG bytes. A zero-length packet is a heartbeat while no new frame is available. Images have maximum edge 960; clients must bound encoded length and decoded dimensions. No duplicate image sequences are sent within a game run. Sequence resets when the game restarts. An independent producer retains only the latest packet, so slow receivers drop superseded frames. The stream ends on disconnect, write timeout, or pairing token revocation. `/frame` remains available for older clients. The Android client renders at vsync, retains one pending bitmap, and measures distinct frames actually drawn; its status polling uses a separate thread.

UDP discovery on port 8391: client sends ASCII `DEFOLD_REMOTE_DISCOVER_1`; host replies to the sender with JSON `{"service":"defold-remote","protocol":1,"name":"...","port":8390}`. It never advertises credentials. Manual IPv4 connection remains available.

## Host → debug engine

The host sets `DM_SERVICE_PORT` and a per-host random `DM_REMOTE_TOKEN` on the child, then sends `X-Defold-Remote-Token` on `/remote/status`, `/remote/frame`, and `/remote/input`. Release builds do not implement these routes. Requests are handled on the engine thread. No socket thread accesses rendering or HID objects.

- `GET /remote/status`: protocol and capability JSON.
- `GET /remote/frame`: requests a capture; returns the most recent frame, or 204 before the first frame / when older than one second. Captures are paced at 60 Hz, no readback without requests. New frames are published after rendering, before Flip.
- Frame body: ASCII `DFR1`; uint32 LE width, height, sequence; packed BGRA8 pixels, rows top to bottom. Maximum output edge 960; maximum capture dimensions 4096×4096. Reset on engine project initialization.
- `POST /remote/input`: same 5-byte pointer packet; 204 accepted, 400 invalid, 429 queue full. Queue capacity 64, at most one event consumed per game frame. Input lease expires after one second and emits release even if a queued press remains.
- `/post/@system/exit`: existing engine DDF exit message (`08 00`) used for normal stop. The broker confirms the authenticated Remote endpoint before sending it.

The engine HTTP server supports bodyless 204 responses without chunked-transfer bytes; status is set before sending any headers. This is covered by an HTTP regression test.


# Editor Remote protocol 2

The editor owns the game process. `GamePreview` registers with `RemoteGameServer`, which binds the first available TCP port in 8390–8419. UDP 8391 accepts the existing discovery query over multicast `239.255.83.91` and broadcast, replying with `protocol:2` and `games`. Each game has a stable ID derived from a persistent machine identity and its project, a name, computer name, state, path, port and 60 FPS capability. `GET /games` returns the same catalog without pairing credentials.

All game routes use `/games/{id}` as their base. `/pair`, `/status`, `/start`, `/stop`, `/input`, `/frame` and `/stream` retain the v1 meanings, except that start delegates to the editor's build/run action and returns `starting` until the existing editor process has attached. `/thumbnail` returns the latest JPEG or 204 and requests fresh capture. Frame and thumbnail routes require that game's bearer token. Pairing a game cannot authorize another game.

The stream retains the v1 framing (big-endian length and sequence) but carries JPEG images. Decode the image rather than assuming PNG. Each subscriber receives the latest encoded image; no unbounded frame queue is allocated. A second stream replaces the previous stream for that game. Zero-length heartbeat packets keep stopped-game connections readable. Input has a one-second release lease and a bounded queue; closing/replacing a stream releases input.

Credentials survive editor restart in the current user's preferences. A successful pairing replaces the previous token for that game. Refreshing the eight-digit pairing code invalidates previous QR payloads without invalidating an established token; the separate revoke action clears the token and disconnects the active stream. Closing a project unregisters its game. Closing the last registered project stops its Remote server.

The QR/deep-link format is `defoldremote://connect?v=1&address=<percent-encoded-game-base>&code=<eight-digits>`. The URI format version is independent of the server protocol version. Android accepts either this URI or an explicit `com.defold.remote.CONNECT` Intent with `address` and `pairing_code` extras (`code` is also accepted). Only private/link-local/loopback IPv4 HTTP endpoints and a bounded game-ID path are accepted; no arbitrary URL navigation or process execution is exposed.

## Stream quality (editor patch.93)

`GET /games/{id}/stream?resolution=540p|720p|1080p|original` selects the active stream profile. Unknown/duplicate parameters return 400. No query preserves the legacy 960-pixel long-edge profile. Game status advertises `resolutions` and `stream_resolution`; clients should check support before exposing selection.

Presets cap the long edge at 960, 1280, 1920 and 4096 respectively, never upscale, and retain the source aspect ratio. Original is additionally capped at 8,388,608 pixels. JPEG quality is 0.82 / 0.88 / 0.93 / 0.96. Opening a replacement stream applies the new mode without restarting the game; old-profile frames are withheld. The binary frame envelope is unchanged. `/thumbnail` is encoded separately with a maximum 400-pixel long edge. The new APK permits stream packets up to 24 MiB and decoded dimensions up to 4096 per edge / 8,388,608 pixels.

The Android control bar counts distinct UI-presented frames over a rolling one-second interval; it is not the engine FPS or network frame arrival rate. Touch motion is coalesced at the client; press/release edges preserve order. HTTP 429 is backpressure, not a reason to navigate home.

## Hardware video extension (editor patch.95 / APK 0.5.0)

`status` and catalog entries advertise `video_codecs: ["h264", "jpeg"]` when the
optional macOS VideoToolbox or Windows Media Foundation hardware codec is available;
unsupported hosts advertise `["jpeg"]`. Windows probes a hardware encoder before
advertising H.264 and retains JPEG fallback on unsupported devices.
Protocol remains 2, and existing `/stream` clients remain compatible.

`GET /games/{id}/video?resolution=540p|720p|1080p|original` requires the same bearer
token. It replaces the game's active video/JPEG subscriber. Content type is
`application/x-defold-h264`. The response starts with two big-endian uint32 values:
actual width and height. Dimensions preserve the source ratio and round down to
even values for H.264. These are followed by packets: uint32 BE byte length,
uint32 BE source sequence, and one Annex B access unit. Length 0 means the encoder
dropped that frame; it is not EOF. Nonzero packets are limited to 8 MiB in the APK.
SPS/PPS precede every IDR, including the first packet. Size/source changes close
the stream; a new connection negotiates the dimensions and creates a fresh codec.

Compression uses hardware H.264, no B frames, a keyframe every 60 frames, and
6–40 Mbit/s according to output size. Frames are submitted serially and paced at
60 FPS; no compressed packet is intentionally skipped. Socket writes stalled for
1.5 seconds cause disconnect; shorter stalls retain the connection. Closing a
blocked socket does not block the shared capture thread, and each write deadline
belongs to its connection. `/status` includes `stream_codec` (none/jpeg/h264) and
`stream_stall_disconnects` (counter for this host game registration).
The client retries H.264 after transient failures with bounded backoff. Three
consecutive failures without five seconds of decoded frames fall back to JPEG;
an unsupported video endpoint (404/503) falls back immediately. Five consecutive
failures stop automatic reconnects. HTTP 401/403 requests new pairing, not a codec
fallback. No extra ports or credentials are introduced.

Touch requests use an independent connection/executor; the APK polls its bounded
coalescing input buffer every 8 ms instead of 32 ms. The host consumes the latest
consecutive held-pointer positions while retaining press/release boundaries. FPS
for H.264 counts texture updates presented by Android, not incoming packet count.


## Game audio extension (editor patch.96 / APK 0.6.0)

A macOS host advertises `audio: true`. `GET /games/{id}/audio` uses the same bearer
token and returns 409 for a stopped game or 503 until its audio mixer is ready.
It is independent of the single video/JPEG subscriber; replacing video does not
close audio. A new audio subscriber replaces the previous audio subscriber.
Stopping, disabling, closing or revoking a game closes both streams.

Content type is `application/x-defold-pcm`. The header is four big-endian uint32s:
`0x44464131`, actual mixer sample rate, channel count (2), bits per sample (16).
Each packet has uint32 BE byte length, uint32 BE sequence, then interleaved signed
PCM16 **little-endian** left/right samples. Length zero is a heartbeat; nonzero
length is divisible by 4 and at most 3840 bytes (960 stereo frames). Sequences
wrap modulo 2^32 and gaps indicate dropped old samples; clients should flush old
playback buffers on gaps. At 48 kHz payload bandwidth is 1.536 Mbit/s.

The engine taps its own post-gain mixer, leaves local output intact and writes
only when an authenticated reader enables demand. Its private 0600 companion
file is removed with the game session. Nonblocking file locks keep readers from
stalling the sound thread; a bounded 64-slot ring recovers near the live edge.
The server disconnects stalled writes after 250 ms. Android AudioTrack uses
streaming PCM, the low-latency performance preference, bounded buffering and game
audio focus. Backgrounding or leaving the stream pauses/flushes playback immediately.
No microphone permission or desktop system-audio capture permission is used.
