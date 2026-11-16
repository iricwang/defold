# Automatic weights and independent weights dock — 2026-11-08

Only editor source, localization and tests changed. Runtime engine unchanged. Session hotloaded into the installed editor; no patch revision or installer was produced.

## Reference and behavior

Reference: https://esotericsoftware.com/spine-weights and the user's latest weights/hierarchy screenshot.
The public guide says Auto considers mesh topography instead of simple bone distance. It does not publish the solver. This implementation is an independent approximation, not the original Spine algorithm and not a promise of identical numerical results.

- Auto: nearest-surface seeds sampled along bone segments, weighted mesh-edge graph, per-island multi-source Dijkstra distances, normalized inverse-distance weights. A U-shaped mesh test checks that the empty center is not a shortcut. Scaling the rig preserves weights.
- Existing bone-relative bind coordinates are retained during reweighting; Update explicitly rebinds to the current setup shape.
- Vertex and bone subsets preserve unrelated painting. Locked influences and influence budgets are validated.
- Smooth uses hull and manually constrained edges, excluding automatic triangulation diagonals.
- Brushes support Add/Remove/Replace, strength, size, feather and selected-vertex restrictions. Multiple selected bones edit their combined weight budget.
- Copy/paste validates vertex count and bone identity; swap exchanges two selected bones; weld copies weights from an explicitly selected compatible target, for overlapping vertices or all selected vertices.
- Binding, direct slider changes, smoothing, automatic weighting, update, paste/swap and weld use graph transactions. Prune previews a draft in a modal window; cancel does not write to the graph, confirm checks for concurrent document changes.

## Interface

Independent resizable weights dock inserted between viewport and hierarchy. Closing the weights workflow restores prior split positions. Properties remain separately on the right.
Order: influence limit + prune; mode + update; weight or brush settings; smooth/auto/weld; pies/overlay/selected; colored bone list with copy/paste/swap icons; bind/remove.
The list fills available vertical space. Native theme retained. Scoped inline size rules override the editor's generic 100px button minimum for compact 24px icons.
Bone colors, vertex pies and triangle overlay correspond. Overlay currently uses 16 subdivisions per triangle for color interpolation. Undoing the binding while Overlay remains enabled is supported and regression-tested.

## Verification

147 tests / 1038 assertions passed (navigation, toolbar, curves, selection, trace, mesh, slot inspector, authoring view, attachments, smart mesh). Targeted clj-kondo: no new diagnostics on changed lines; historical warnings remain.

Live plorer checks on rat.skeleton / 05_tail:
- Selected mesh, opened weights, bound bone1 and bone3, completed with Space.
- Opened Prune, checked graph document unchanged during preview, cancelled and checked draft cleared.
- Cmd+Z exactly restored the captured document; Cmd+Shift+Z exactly restored binding; final Cmd+Z restored the user's pre-verification document.
- Initial overlay/undo regression was found live, fixed, and editor timers re-enabled. Added Canvas rendering regression for the unbound state.
- Side panel is at split item index 1, to the left of hierarchy. Icon width measured 24px. Existing unsaved resource edits retained; no save/export performed.

Warm in-process algorithm measurements, seven repetitions after three warmups, 15 candidate bones. These are solver-only values, not end-to-end UI latency:

| Mesh | Vertices | Median | Max |
|---|---:|---:|---:|
| 05_tail | 26 | 4.15 ms | 11.56 ms |
| 02_front_right | 108 | 11.76 ms | 13.04 ms |
| 03_front_left | 19 | 1.61 ms | 3.08 ms |
| 01c_jaw | 19 | 1.50 ms | 1.58 ms |

## Remaining parity limits

This is not complete Spine feature parity: exact proprietary automatic weights, bone-list drag ordering with matching triangle draw-order semantics, and full multi-mesh batch welding are not implemented. Weight painting remains setup-mode-only in this editor. Copy/paste buttons operate on weights; dedicated Spine-style weight clipboard keyboard routing still needs a separate pass. The current reference layout and principal operations are implemented; full pixel-for-pixel and all-version behavior equivalence is not claimed.
