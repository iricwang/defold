# Bone Create tool verification — 2026-11-08

Reference: https://esotericsoftware.com/spine-tools#Create-tool

Setup mode only; N selects Create. Click another bone to select the parent,
click empty canvas for zero length, or drag to specify origin, direction and
length. New bone is selected; Shift retains the parent. Alt/Option recreates
selected bone endpoints, compensating children and active attachment geometry.
Ctrl/Cmd click or marquee selects attachments; subsequent creation reparents
slots and uses the first slot name. Escape cancels the preview. One gesture
commits once and supports Undo/Redo. Failed creation does not select a missing id.

## Automated checks

133 tests / 944 assertions passed, zero failures or errors. Covers animation
navigation, toolbar, curve view, selection, trace, mesh, slot view, main view,
attachments and smart mesh. New tests cover recreate compensation, mesh/FFD
reparenting, real JavaFX gestures with graph undo/redo, and alternate-skin guard.
clj-kondo: no new warnings or errors; existing file warnings remain.

## Live editor

Used cljfx.plorer against the running local Defold and rat.skeleton:
- Drag creation preview does not modify the graph; release adds one bone.
- Undo, redo, undo exactly restores the captured document.
- Shift click produces a zero-length sibling and retains its parent.
- Escape during drag produces no bone.
- Clicking tail selects it without creating a bone.
- Cmd click 05_tail selects its attachment; next drag creates bone 05_tail and
  reparents the slot. Five idle samples have max world-vertex difference < 1.5e-13.
- Option drag recreates tail without adding a bone. Setup mesh world-vertex
  difference < 8.6e-14. Undo restores the captured document exactly.
- All test edits undone; the original ten bones and preexisting unsaved work
  are intact. No document save was invoked.

## Scope

Alternate skins/attachments are explicitly rejected for migration rather than
silently moving hidden artwork. Unsupported attachment types and singular or
unrepresentable region transforms fail atomically. No claim of full constrained
rig/alternate-skin compatibility. Alt recreation verifies setup compensation,
not invariance of every existing animation after intentional setup changes.

Code hotloaded in this session; no incremental package installed. Runtime unchanged.
