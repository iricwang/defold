# Spine authoring → Defold runtime acceptance

Final result: PASS with the explicit two-color material limitation below.

The current editor data and IO source exported the four resources in this folder.
The current local Bob performed a standard clean build using the existing Spine
build plugin. No compiled resource was copied from a different Bob build.
The existing local Spine engine then ran 525 captured frames at 800×600, exited 0,
and delivered 12 asserted event callbacks across four loops. The fixture verifies
event name, animation, time, integer, float, string hash and track.

Actual framebuffer checks passed:

- Sequence cycles through all three expected red, green and blue images.
- Linked mesh renders its own cyan texture using parent geometry.
- Pixels outside the clipping rectangle match the gray background.
- Additive, Multiply and Screen produce three different pixel colors on gray.
- Point and Bounding Box remain in exported Spine data and do not render as images.

This test found and fixed a real export defect: a default .spinemodel Alpha blend
overrode the per-slot modes. Export now writes BLEND_MODE_INHERIT when needed.

The default Defold Spine material exposes light color only. The fixture retains
dark setup tint and rgba2 keys in its Spine JSON, but the runtime screenshot shows
light-only tint. Export now returns an explicit warning that two-color rendering
requires an extension/material exposing dark vertex data. No runtime engine or
user game was changed to conceal this limitation.

The captured Metal surface is BGRA with top-left origin; run_runtime.py converts
it to RGBA PNG and records logical coordinate probes. Binary engine/plugin/Bob
digests are in build-results.json. The engine log identifies Defold 1.14.1.

Native Save As safety was also checked with an operating-system 16 KiB file-size
limit and SIGXFSZ ignored so write returns a real File too large error.
write-failure.log confirms the final target was absent and the staging file was
removed. The unit suite passed 106 tests / 802 assertions, including native sibling
audio-root preservation, atlas pages, attachments, and original data/IO regressions.

Reproducible local scripts and source fixtures are included. They refer to the
isolated /tmp/defold-spine-workflow-runtime project and local toolchain. Large
build caches, native libraries and engine binaries are intentionally excluded.
