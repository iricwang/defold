# Tail IK example — 2026-11-08

Editor-only IK workflow: select one bone or a direct parent/child pair, use “+ IK 目标”, then pick an existing target or click blank space. Clicking a constrained bone creates a target at its tip. Escape cancels. Targets below constrained bones are rejected. Creation is one undoable transaction.

## Saved content

Game project: `/Users/applemima1111/Documents/Game/Dragon/GameCopy/pok_analysis/defold_game`

Saved and reopened: `docs/source/spine_smart_mesh_validation/tail_ik_example.skeleton`.

- Chain: `bone7 → bone8`, tail mesh `05_tail`.
- Target: `tail_ik_target`, parent `root`.
- Constraint: `tail_tip_ik`, mix 1, no stretch, positive bend.
- Animation: `tail_ik_demo`, 2 seconds, 9 keys each on target X/Y, cubic Bezier curves.
- Existing 7 animations receive mix 0 at time zero for this new IK; sampled existing bone poses changed by exactly 0.
- The live original rat.skeleton also contains the case; its existing unsaved edits were not force-saved. The standalone case preserves the full current rig and references existing project images.

## Validation

- Actual cljfx.plorer UI: multi-select bone7/bone8 → create IK → pick bone8 → target created at tip.
- Creation pose matrix change: at most 3.56e-15.
- Real canvas X-gizmo drag: target moved from x=117.229468 to x=130.359341; solved tip followed to same coordinates. Manual position-key button committed the edit. Preview undo and graph undo restored exact authored example.
- Actual playback advanced time, reached duration, and loop mode kept advancing.
- 121 samples at 60 fps: maximum tip/target distance 3.18e-14, no solver warnings.
- Tail mesh maximum coordinate motion between 0 and 1 second: 6.378 units. Loop seam error 2.68e-12.
- Spine JSON export/import retained constraint and animation. Editor JSON save/read equality passed. Resource browser Enter opened saved example; loaded document exactly matched authored document.
- 239 tests / 3014 assertions, no failures/errors, including existing IK solver oracle tests and new target-picking/undo regression.
- clj-kondo: no errors; no diagnostics on changed lines (legacy warnings remain).
- No disabled editor timers.

Code hotloaded for this session, not packaged. Runtime engine untouched. This is two-bone tail-tip IK, not an arbitrary-length IK solver. Open the saved example, choose tail_ik_demo in animation mode and play; edit tail_ik_target to author additional keys.
