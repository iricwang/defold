# Movement performance and handles

Changes: bounded weak-reference cache for successfully validated immutable document identities; unchanged documents avoid repeated full mesh/animation validation. New documents still undergo full validation. Non-renaming bone updates retain slots/animations by identity. Setup X/Y movement updates together. Disabled compensation returns immediately. No runtime changes.

Move handles: 76 screen-pixel length, red X / green Y, filled 17×16 arrowheads, dark outline, 12-pixel hit tolerance; scale/shear retain enlarged square ends. Handle size is independent of zoom.

## Verification

Same live tail case, warmed 8-call means (not an FPS claim):
- Setup drag computation: 15.397 ms before → 4.094 ms after.
- Repeated document validation: 4.366 ms → 0.00051 ms.
- Refresh: 8.663 ms → 0.934 ms.

Actual cljfx.plorer UI on tail_ik_example.skeleton, animation mode:
- Selected bone1, grabbed X arrow 73 pixels from origin, six moves: mean handler 10.972 ms. Auto-key produced edit; Cmd+Z restored exact prior document.
- Selected 05_tail image, same arrow test: mean handler 8.724 ms. Cmd+Z restored exact prior document.
- Both grabs reported axis 0. No disabled editor timers.
- Visual screenshot shows red/green filled arrows and selected tail mesh.

241 tests, 3039 assertions, zero failures/errors. Tests include invalid edits after successful validation, reference identity preservation, zoom-independent enlarged hit areas and existing rigging/IK/mesh regressions. clj-kondo reports no diagnostics on changed lines.

Hotloaded into current Defold session only; no package built. User edits retained; test movements undone.
