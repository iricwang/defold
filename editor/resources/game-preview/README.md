# Screen configuration catalog

`devices.json` records numerical screen configurations from Unity Device Simulator
Devices 1.0.1, checked on 2026-10-21:
https://docs.unity3d.com/Packages/com.unity.device-simulator.devices@1.0/manual/index.html

There are 82 device definitions and 83 screens. The two Galaxy Z Fold2 screens
are separate Phone and Tablet choices. The data is normalized into our own schema:
rectangles are `[x, y, width, height]` in physical pixels with a bottom-left origin;
rotations are clockwise quarter-turns. Each supported orientation has its own
safe rectangle and cutout rectangles. Names remain device model identifiers.

This catalog contains numeric screen geometry, not Unity code, .device source
files, overlay artwork, chassis images, or SystemInfo implementations. Unity's
original package is distributed under the Unity Companion License:
https://unity.com/legal/licenses/unity-companion-license

The additional six editable reference presets are defined in SimulatorDevice.java.
Those presets' insets and rounded corners are illustrative layout-test values.
