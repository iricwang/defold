# Game preview

On macOS, the Game panel runs the bundled engine beside the scene editor. Use ▶
to build and run the project, and ■ to stop it. Click the game image to direct
keyboard and mouse input to the game. Leaving the panel releases held keys.
The embedded engine runs as a background macOS application without a visible
window, Dock icon or app-switcher entry. Uncheck Embed before the next run to
use a separate game window. Select 60, 120 (default), or 144 FPS before running.

Drag the Game title to another panel to dock it, or use its top-right menu.
The Scene/Game split and panel placement are saved with the editor layout.

This first implementation supports one local bundled engine. Custom engines
built with native extensions, multiple instances and remote targets continue to
use their existing windows. It does not synchronize scene edits into runtime
objects. The game's configured resolution is letterboxed into the panel;
preview frames are limited to 2048 × 2048. Embedded runs use the selected update
frequency and disable presentation vsync, High-DPI and fullscreen. Physics keeps
the project's fixed-update frequency. Actual frame rate depends on project load,
GPU readback, editor load and display refresh; the selected rate is a target.
JavaFX accepts up to 144 animation pulses per second (an explicit
`javafx.animation.framerate` system property overrides this default).

## Bridge

`GamePreview` owns the panel, process session and input state. `editor.engine`
selects the embedded session only for the bundled macOS engine. The private
`editor_game_bridge.h` reads input after hardware polling and captures the final
frame before presentation. The bridge is excluded from release engines.

Each session uses a new owner-only temporary file, mapped in both processes.
The protocol is native-endian and starts with magic `0x44464732`. POSIX record
locks and Java FileLock serialize frame copies and input; neither rendering
loop waits for the other. GPU readback and JavaFX PixelBuffer uploads happen
outside the shared lock. The reusable direct pixel buffer avoids PixelWriter
staging copies and format conversion. The engine retains the last input snapshot when the lock is busy, so
hardware polling cannot manufacture key releases. Parent-process termination
ends the embedded game. Stopping or closing the project closes the channel,
removes the file, and terminates only that session's process.

The 4096-byte header contains width/height/sequence/engine PID at byte offsets
4/8/12/16, focus at 20, mouse x/y/buttons/cumulative wheel at 24/28/32/36,
text count at 40, published frame slot (0 or 1) at 44, keyboard state bytes at 64, and 128 Unicode codepoints at 512.
Two BGRA slots follow the header; each holds 2048 × 2048 × 4 bytes.
The engine renders into the unpublished slot, then publishes it under the lock.
Readers copy the published slot under the same lock before uploading; capture
and upload can overlap without tearing or holding the lock during GPU work. Key codes
32–126 use ASCII, and 128–155 map navigation/modifier/function keys explicitly
in both implementations. A future transport/backend must negotiate a new magic
before changing this layout.

Validation includes keyboard-code collisions, letterboxed pointer coordinates,
docking invariants, and a live editor/engine exercise for input, held-key release,
stopping, frame delivery and drag cancellation. The patch delivery additionally
verifies the exact installed baseline and a download/install/restart cycle.
