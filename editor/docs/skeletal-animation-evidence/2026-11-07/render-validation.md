# Spine rendering verification

Implemented editor-only rendering API:

- `geometry/attachment-world-geometry attachment transforms bone-id deform`: clipping and boundingbox world polygons, including weighted/deformed vertices; point origin and oriented axis.
- `render/render-plan slots`: ordered, UV-preserving concave clipping into batched mesh records. Clip entries use `:clip-polygon [[x y] ...]` and optional `:clip-end slot-id`. Empty and inactive end slots must remain `{:id ... :marker true}`. Nested clips are ignored. End applies after its slot. Collapsed/degenerated animated clips safely produce no visible geometry until the end slot.
- Texture records retain original `:points/:uvs/:triangles/:image/:opacity/:tint`, adding optional `:dark-tint [r g b]` and `:blend` normal/additive/multiply/screen.
- `render/render-image width height slots` returns a `BufferedImage/TYPE_INT_ARGB_PRE`, with transparent background. Same GPU pipeline handles advanced live preview and exports. The ordinary no-clip/normal/light-tint case retains the original JavaFX TriangleMesh implementation.

The exact PMA two-color shader and blend factors were checked against official Spine 4.2 sources:
https://raw.githubusercontent.com/EsotericSoftware/spine-runtimes/4.2/spine-ts/spine-webgl/src/Shader.ts
https://raw.githubusercontent.com/EsotericSoftware/spine-runtimes/4.2/spine-ts/spine-webgl/src/PolygonBatcher.ts
https://raw.githubusercontent.com/EsotericSoftware/spine-runtimes/4.2/spine-ts/spine-webgl/src/SkeletonRenderer.ts

The compositor batches each slot into one draw; no triangle gets a JavaFX node or Canvas.clip. It shares a serialized OpenGL context, reuses direct vertex/readback buffers, limits texture cache to 128MiB/64 images, and skips unchanged preview frames. JavaFX MULTIPLY is intentionally not substituted: its transparent-background behavior differs from Spine.

Actual shader and framebuffer readback tests passed: 14 tests / 264 assertions, zero failures or errors, including the preexisting renderer regressions. Evidence: `render-tests.log`. Covers all four blend modes, multiply on transparent pixels, two-color tint, premultiplied texture alpha, transparent dark edges, texture row orientation, GPU preview ImageView reuse, concave clipping/UV interpolation, end slots, collapsed polygons, weighted overlays and reflected point axes. The oracle runner used the installed official @esotericsoftware/spine-core 4.2.120 runtime; oracle intersection areas 7/7/4/4 match our permanent fixtures.

Performance test runs on the JavaFX thread with 12 animated slots / 1536 source triangles at 800x600. Final 60-frame sample after warmup:

- Two-color + four blend modes: median 2.86ms, p95 6.09ms, max 11.87ms.
- Same plus concave clipping: median 9.12ms, p95 13.98ms, max 15.31ms.

Evidence: `render-performance.json`. These are local observations, not universal hardware guarantees. The renderer needs an OpenGL-capable editor desktop; export is offscreen GPU rendering, not a headless CPU fallback.

All new clipping/GPU modules and tests plus the modified render module pass clj-kondo with zero warnings/errors. cljfmt and git diff whitespace checks passed. The runtime engine and user game assets were not modified.
