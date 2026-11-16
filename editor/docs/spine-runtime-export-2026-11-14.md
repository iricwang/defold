# tail_path_example: Spine 4.2 runtime export and scaled textures — 2026-11-14

The current, unsaved `tail_path_example.skeleton` was read directly from the live editor. Its source provenance was Spine 4.3.26; the game's installed extension is Spine C 4.2. The texture output setting was 30%. The user's later tail-tip position edit was captured and the final export, archive and playback checks were repeated using that newer document.

## Change

`skeletal-animation-io/export-project!` now targets Spine 4.2 via `skeletal-animation-runtime-export/for-runtime`. Compatible 4.3 data is checked against the actual 4.2 runtime's supported fields, timeline forms and enums before emitting version 4.2.0. Unsupported preserved data raises an error with its field path before output files are written. Existing 4.2 data remains unchanged. This is a guarded compatibility subset, not a general converter for every Spine 4.3 feature.

The authoring source version, geometry, weights, paths and keys remain unchanged. No Spine runtime or Defold runtime engine code was modified. This follows Spine's requirement that exported major/minor versions match the runtime: https://esotericsoftware.com/spine-versioning . The exact parser used for field checks is the game's `defold-spine/commonsrc/spine/SkeletonJson.c`.

## Export and real playback

- Original document: `/docs/source/spine_smart_mesh_validation/tail_path_example.skeleton`.
- Exported in the real game project: `/game/assets/spine_validation/tail_path_example/05_tail_path_controls.{spinejson,atlas,spinescene,spinemodel}` plus 13 derived PNGs in `05_tail_path_controls_textures`.
- That live export was copied byte-for-byte into a separate QA bootstrap and packed with Bob `--archive build`. The QA collection references the actual exported model. The normal game's bootstrap was not changed.
- 30% atlas: **1024 × 512 RGBA8**, **2,097,152 bytes** pixel payload. QA archive: **582,532 bytes**. Source PNG hashes unchanged. Archive manifest contains the scaled texture resource, with no original PNG source files bundled separately.
- Real `defold-background` session, actual Metal rendering and game's existing native extension engine: loaded from archive, rendered the original rig and looped `tail_path_demo`. Cursor samples at frames 30/60/90/120/150/180 were approximately .242/.492/.742/.992/.242/.492.
- The original animation has only a static root rotation key at 2 seconds. This proves loading/rendering/timing/looping, not a newly authored tail-sway animation. No substitute clip or compatible surrogate was injected.
- Native engine SHA256 before/after: `b01571448c869ab2623a9ea81201d6675d95ddc48b951204d953a9bb7ca7be11`.
- The runtime logged its existing Remotery profiler initialization error (5), but rendered and advanced the clip. No timing benchmark or profiler availability claim is made here.

## Checks

- **158 tests, 1183 assertions; zero failures/errors** across runtime export, texture scale, IO, atlas, document and editor interaction tests.
- Original latest document versus official `@esotericsoftware/spine-core` **4.2.120**: **121 frames**, maximum bone component error **0.000067333**, maximum mesh vertex error **0.000060486** (tolerance 0.0001); draw order/attachment state mismatches 0.
- Background native JavaFX export interaction: 12 steps passed. Follow-up 22-step sequence changes 30% to 31%, exports through the actual button, then undoes back to 30%. Generated runtime JSON version verified as 4.2.0; exported resources visible in the asset browser. No disabled handlers/stopped timers.
- cljfmt applied. clj-kondo: zero errors and no warnings in new code; 31 existing warnings across the touched large source files.
- Static project audit: no findings for the isolated QA fixture. macOS arm64 validated; other platforms not tested.

## Undo incident and prevention

The first export's existing full `workspace/resource-sync!` cleared the global undo stack (revision 67 → 68, count became 0), although the authoring document itself was unchanged. This was reported to the user; complete earlier undo entries were not retained and cannot honestly be claimed recovered. `original-before-validation.skeleton` and `original-current.skeleton` preserve the two observed document states; they are recovery snapshots, not an undo-history backup.

The export button now uses the existing managed refresh preflight (`craft-project/safe-refresh!`): additions and stateless changes preserve undo; replacing undo-referenced nodes or unrelated external changes produces an explicit refresh error instead of silently clearing history. Output files have already been written in that case and can be refreshed explicitly. The 22-step real UI regression verifies successful first export and undo restoration.

## Delivery

Version guard, IO entry and export callback were hot-reloaded into the running local Defold. The currently open tab's callback was also rebound, without closing it. Final hot reload preserved the current document and the then-current undo revision, with zero failed handlers and stopped timers. **Session-only hot reload**, no persistent application package was installed. Source is ready for a subsequent requested package.

Evidence is under `skeletal-animation-evidence/2026-11-14/runtime-export/`; full temporary build and session artifacts remain under `/tmp/spine-runtime-export/`.
