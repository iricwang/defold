# Game preview

## Fast resource preview

With the game stopped, Game follows the active `.gui`, `.collection` or `.go`
editor. The lock button at the right of the toolbar keeps the current resource while another tab is edited;
closing its tab releases the lock. The title shows the resource filename, with its full path in a tooltip;
floating tabs and window titles follow it. During game playback the title is simply **Game**. This identifies
what is displayed. Starting the game uses the normal project bootstrap; it does
not run an arbitrary selected GUI/GO in isolation. Stop restores resource preview.

The editor shares each SceneView's GPU resource caches. An LRU retains up to eight
resource images and 32 MiB of raw image pixels (this is not a whole-process memory
limit). Images are invalidated by render data, animation state, camera/layout,
background, GL context or effective output size changes. Closed views are evicted.
Only the most recent active resource is sampled, at up to 30 Hz while visible;
unchanged resources are not rendered again. Preview resolution is bounded to
1536 pixels per dimension. The renderer owns one reusable framebuffer/readback
buffer, independently of the bounded resource-image cache.
Writable images are reused only at exactly the requested width and height. A larger
JavaFX image keeps its old dimensions, so reusing it after shrinking a panel or
switching devices would incorrectly scale the GUI inside unused pixels.

GUI resource previews use the runtime GUI layout rules at the device's physical
resolution: X/Y anchors, FIT/ZOOM/STRETCH, parent/legacy/disabled adjust references,
and project `gui.safe_area_mode` (none/long/short/both). Rotation uses each device
orientation's actual safe rectangle. The guide visibility does not change layout.
Named GUI layouts are selected from the project's display profiles using the
runtime orientation/area/aspect rules. Disabling automatic layout selection uses
the default layout, as a newly started game does. Template transforms, parent
rotation, pivots and stencil clipping participate in the same calculation.

Layout evaluation uses a disposable graph basis. Device switches do not change the
GUI's selected authoring layout, saved properties, or undo history. Safe-area and
layout settings invalidate the bounded preview cache, even at equal resolutions.
Lua initialization, script-created nodes, script overrides of safe-area/layout,
and custom render scripts still require **Run**; editor preview evaluates authored
GUI data without executing game business logic.

### Authoring an adaptive GUI

Keep three sizes distinct: the GUI layout's reference resolution, the simulated
device's physical output, and the panel's display zoom. A root box sized 1080 × 2340
does not change a project's 2340 × 1080 GUI reference. Define a portrait display
profile at 1080 × 2340 and a landscape profile at 2340 × 1080, and declare matching
named layouts in the GUI. Point `display.display_profiles` at that profiles file
(the `game.project` text uses the compiled `.display_profilesc` suffix).

Use a stretch root for the available canvas and FIT children for artwork that must
keep its aspect ratio. Group top, bottom, left and right controls under anchored
parents. Preserve existing node IDs and express landscape rearrangements as layout
overrides. Place background nodes before the content groups: GUI hierarchy order
affects draw order even when the grouping boxes themselves are invisible. Keep the
built-in profile names when adding a project profile so other GUIs can still use them.

The Game title tooltip shows `layout · reference width × height → device width × height`.
These values come from the same private layout evaluation that produces the image,
including cached previews. Device changes do not rewrite the project resolution.
Safe-area mode still belongs to the project or GUI script; for example, `long`
reserves the long axis only, while `both` reserves both axes.

For validation, render the same saved GUI in an isolated native collection and in
the resource preview, compare portrait and landscape output at identical device
dimensions/insets, and check important node screen positions. Also rotate a running
process and return to its original orientation. A static GUI comparison does not
exercise application lifecycle messages or login/network business callbacks.

## Device Simulator (Windows x64)

Enable **Device Simulator**, select a device or edit a custom resolution and safe
area, then Run. **Custom device** and **Import .device folder** are in the panel's
existing **…** menu. Rotate and toggle the safe-area guide before or during running. Device
selection/customization is currently available before starting the game. The
engine renders at device pixel dimensions; resizing/docking the preview only
changes display scale. Pointer coordinates are mapped back to device pixels and
screen cutouts reject presses. Mouse/keyboard input is supported; this is not a
multitouch or mobile hardware/performance emulator.

The local Windows capture helper uses OpenGL and a matching debug engine PDB.
It resolves the native `dmPlatform::GetSafeArea` boundary from exact symbols and
adapts only the launched preview process. No engine files are patched on disk,
no game Lua is replaced, and no native-extension source upload is needed.
Missing matching symbols fail before launch. Native `window.get_safe_area()` and
`gui.safe_area_mode` therefore see the same safe rectangle as the overlay.
The simulator requests OpenGL even if the project normally uses another backend.

Capture is consumer-driven, capped at 60 transfers/second independently of game
update frequency, and stops when the preview is hidden/minimized. Opaque alpha
conversion runs in the native helper. JavaFX reuses its image buffer; stopping
releases the mapped channel and its frame references. The Windows protocol uses
magic `0x44464733`, two device-sized BGRA slots, slot capacity at byte 48, native
safe-area acknowledgement at 52, dimensions/insets at 1024–1044, a settings
sequence at 1056, visibility at 1060 and frame demand at 1068. Rotation updates
use a sequence guard; incomplete settings are never published as a safe area.

### Device definitions

**Import devices** reads a folder of version-1 `.device` JSON files, including
multiple screens, DPI, independent safe areas/cutouts for each orientation, and
relative presentation overlays/borders. Search filters the device list. Settings
and definition paths are saved in `.internal/game-simulator.properties`; keep the
imported files and their images at their original paths. Unsupported hardware/OS
`systemInfo` fields do not emulate a mobile system.

The six included presets are independently authored test profiles. Their safe
insets and screen shapes are editable reference values, not measured calibration.
iPhone pixel sizes follow Apple's [iPhone 14 specifications](https://support.apple.com/en-gb/111850)
and [iPhone 15 Pro specifications](https://support.apple.com/en-gb/111829).
Validate safe areas against the intended device before relying on the profiles.

The importer follows Unity's [device definition format](https://docs.unity.cn/Manual/device-simulator-adding-a-device.html).
The catalog also includes numeric screen configurations for all 82 devices in
[Device Simulator Devices 1.0.1](https://docs.unity3d.com/Packages/com.unity.device-simulator.devices@1.0/manual/index.html),
including both Fold2 screens (83 screen entries). `resources/game-preview/devices.json`
records names, pixel dimensions, DPI, orientation safe rectangles and cutout rectangles
in the editor's own schema. Unity source code, presentation artwork, device chassis,
and SystemInfo emulation are not bundled. Screen geometry alone does not simulate
an operating system or certify physical-device behavior.

## Standard embedded runtime

On Windows, the local engine's native game window is parented into the Game
viewport. It receives keyboard and mouse input directly, resizes with the panel,
and is reparented when the panel moves into another editor window. Stopping the
preview terminates only that game process. This backend does not require the
macOS shared-frame engine bridge and also supports local custom engines with
native extensions.
Embedded Windows runs use `CREATE_NO_WINDOW`; stdout and stderr remain connected
to the editor Console. External runs retain their existing launch behavior.
The embedded process starts suspended while a process-scoped window creation
guard is installed. Its game window remains hidden/off-screen through repeated
show calls until the Game viewport adopts it. The guard is released when that
process exits; it does not watch other applications.
The guard marks windows when the engine requests that they become visible.
Discovery accepts that mark (or an already visible window), never a caption alone:
GLFW message helpers and OpenGL context windows can also have captions. The
packaged regression puts a hidden helper first in enumeration and verifies that
discovery still selects the requested game window.

On macOS, the Game panel runs the bundled engine beside the scene editor. Use ▶
to build and run the project, and ■ to stop it. Click the game image to direct
keyboard and mouse input to the game. Leaving the panel releases held keys.
The embedded engine runs as a background macOS application without a visible
window, Dock icon or app-switcher entry. Uncheck Embed before the next run to
use a separate game window. Select 60, 120 (default), or 144 FPS before running.

Drag the Game title to another panel to dock it, or use its top-right menu.
The Scene/Game split and panel placement are saved with the editor layout.

This implementation embeds the first local engine instance. macOS native-extension
builds include an editor-only bridge in their build inputs and cache key. The bridge
is inactive for external runs; existing cached engines rebuild once to include it.
Arbitrary development-engine overrides still use their existing windows. Additional
instances and remote targets use their existing windows on both platforms.
It does not synchronize scene edits into runtime
objects. The game's configured resolution is letterboxed into the panel;
preview frames are limited to 2048 × 2048. Embedded runs use the selected update
frequency and disable presentation vsync, High-DPI and fullscreen. Physics keeps
the project's fixed-update frequency. Actual frame rate depends on project load,
GPU readback, editor load and display refresh; the selected rate is a target.
JavaFX accepts up to 144 animation pulses per second (an explicit
`javafx.animation.framerate` system property overrides this default).

## GUI rectangle editing

Select one Box, Pie or Text node in a GUI resource. The **Rect Transform** section
in Properties places compact anchor and pivot preset buttons beside the existing
position, size and size-mode fields, followed by rotation and scale. Click a preset
button to open its nine-point menu. The menu retains native property controls and
their override/reset actions; Keep appearance is inside the pivot menu. Help is
available on hover, and the header displays the layout being edited. All numerical
fields retain the editor's validation, scrubbing and undo behavior. Multiple selections
continue to use the standard property inspector.

**Rect** in the scene toolbar or inspector header enables rectangle handles:

- Drag inside the rectangle to move it; drag an edge or corner to resize it.
- Shift constrains movement to one local axis or preserves the aspect ratio of a corner resize.
- Ctrl-drag the yellow pivot to snap it to the nearest native pivot preset.
- Escape cancels the gesture; releasing the mouse commits one undoable edit.

Resizing changes Size, not Scale, and switches texture-driven Auto size to Manual.
The dashed reference rectangle and X/Y guides show the parent's bounds and the
pivot's offset from its left/bottom edges. Root nodes use the GUI layout bounds.
Green markers show native viewport anchors. Middle anchor presets mean **None**;
they do not introduce Unity's AnchorMin/AnchorMax or stretch constraints.

Keep appearance compensates the node's position and immediate children's positions
when changing pivot. The rectangle stays in place in the current authored layout;
text alignment still follows Defold's native pivot rules. Named layout changes are
stored only in that layout. Exit animation preview before using the rectangle tool.
Switching away from the GUI tab restores the Move tool for other resource editors.

Drag values use a disposable graph basis, including existing named layout overrides.
They retain their source layout when the simulator is displaying another orientation.
The Game simulator evaluates those values before device and safe-area adaptation;
it must not run the original scene callback again after adaptation. No drag values
are saved until mouse release, and no new runtime properties or scripts are added.

## Bridge

On Windows, the editor creates only its own game process suspended and without a
console. Before the game's entry point runs, it loads the bundled window guard and
queues initialization on the primary thread. That thread installs a local CBT
hook, hiding windows before creation and retaining the simulator capture bridge.
This avoids registering a new DLL in the session-wide WinEvent hook module table
for every patch cache path. The hook ends with the game thread. Startup errors are
returned explicitly across the native boundary, before cleanup can overwrite them;
failed children are terminated. Package verification covers 40 fresh cache paths,
visible-on-create windows, repeated show requests, and viewport adoption.

`GamePreview` owns the panel, process session and input state. `editor.engine`
selects the embedded session for bundled/editor-built macOS engines or a local Windows engine. The private
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

Native macOS builds use the same protocol through
`_defold/editor_game_bridge/src/bridge.mm`, registered with the public extension SDK.
Its update callback injects input before the engine processes game input, and its
post-render callback captures the final frame. Process-local Cocoa hooks keep the
game window and Dock entry hidden without hiding or manipulating other applications.

Validation includes keyboard-code collisions, letterboxed pointer coordinates,
docking invariants, and a live editor/engine exercise for input, held-key release,
stopping, frame delivery and drag cancellation. The patch delivery additionally
verifies the exact installed baseline and a download/install/restart cycle.
