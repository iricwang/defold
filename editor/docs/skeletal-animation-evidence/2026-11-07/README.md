# Spine editor verification — 2026-11-07

This folder contains small, first-party verification records for editor-only Spine work. No user game assets, generated engine builds, native Spine binary fixtures, caches, packages or licensed application files are included. Permanent tests contain the minimal independent native-format fixtures needed for repeatable unit tests. The [continuation acceptance index](continuation-acceptance.md) also links the broader editor and runtime workflow evidence.

| Area | Recorded result | Evidence |
| --- | --- | --- |
| Independent native codec | 52 tests / 708 assertions, zero failures or errors | [test log](native-tests.log), [scope and limits](native-validation.md) |
| Official native interoperability | 80 field assertions (21 initial + 59 continuation), all passed after official Spine 4.2.43 reopened our independently written files | [assertions](native-official-results.json), [official JSON exports](native-official-exports.json) |
| Real GPU compositor and geometry | 14 tests / 264 assertions, zero failures or errors | [test log](render-tests.log), [render scope](render-validation.md), [official clipping oracle](clipping-oracle.json) |
| Local preview timing | 800 × 600, 12 slots, 1536 source triangles; p95 6.09 ms without clipping, 13.98 ms with a concave clip | [raw timing data](render-performance.json) |
| Actual GPU pixel | PMA two-color tint: center ARGB `ff996d6c` | [PNG](gpu-pixel.png), [input and expected values](gpu-pixel.json) |
| Actual AVI decoding | 15 checks, all passed with FFmpeg 7.1 | [results](avi-results.json), [standard frames](avi-standard.framemd5), [odd dimensions / fractional FPS](avi-odd-fractional.framemd5), [single alpha frame](avi-single-alpha.framemd5) |

The AVI checks independently decoded 32 × 24 / 5 frames / 10 FPS, 17 × 13 / 3 frames / 29.97 FPS, and 1 × 1 / one frame. They verified frame counts, dimensions, time bases, top/bottom orientation, animated frame order and alpha composition over black. MJPEG is lossy and does not retain transparency. The half-alpha red pixel decoded as `[128, 0, 0]`. A separate review found the RIFF 32-bit length limit; the exporter now rejects oversized chunks and retains the existing staged-output cleanup.

Native additions cover Point/BoundingBox/Clipping (including weights and FFD), Linked Mesh, region and mesh Sequence settings/keys, RGBA/RGB/Alpha/RGBA2/RGB2 keys with Bezier/stepped interpolation, draw order, simultaneous distinct events, and bone/constraint skin memberships. `update-skeletons` preserves original project and surviving skeleton metadata while supporting creation, deletion, renaming and reordering.

Important boundaries remain explicit:

- The independent native codec targets **Spine 4.2.43, schema 40**. Unverified classes, auxiliary states, activation flags and footer structures fail explicitly. The opaque container can preserve bytes but is not a complete editable document. Skin-linked bones require full-graph decoding instead of unsafe local byte patches.
- Native simultaneous events use event-name order; contradictory ordering or the same event twice at one time is rejected. Runtime/JSON event handling has a broader representation.
- Native skin-required state derives from membership in a named skin. Orphan `skin: true`, contradictory membership flags, and default-skin dependencies cannot be represented without changing semantics and are rejected. Official Spine also drops an orphan flag when importing JSON.
- RGB/RGBA keys on a slot with dark tint normalize to RGB2/RGBA2 using setup dark values for unspecified channels. This preserves setup tint but not independently mixed dark channels from another animation.
- Advanced preview and exported frame images use the same offscreen **GPU** compositor. They require an OpenGL-capable desktop; no headless CPU renderer is claimed. The ordinary JavaFX mesh path remains for simple slots. Timing values describe this local benchmark, not all projects or hardware.

Source hashes and tool versions are in [verification-context.json](verification-context.json). Durable regression suites are [native tests](../../../test/editor/skeletal_animation_native_test.clj), [GPU tests](../../../test/editor/skeletal_animation_gpu_test.clj), [clipping tests](../../../test/editor/skeletal_animation_clipping_test.clj) and [renderer tests](../../../test/editor/skeletal_animation_render_test.clj).
