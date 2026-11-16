# Independent native attachment and workspace verification

All inputs here are minimal first-party JSON fixtures; no user game or existing Spine project was modified. The official application is used only as a format/behavior oracle, not a dependency of the Defold codec.

## Implemented

- Native Spine **4.2.43, schema 40** Point, BoundingBox, Clipping (including weighted vertices, deformation and end slot), Linked Mesh (parent identity, own vs inherited timelines, own deformation), Region and Mesh Sequence configuration, Sequence animation keys.
- Native slot RGBA/RGB/Alpha/RGBA2/RGB2 timelines, including Bezier and stepped interpolation; draw-order offsets/reset; bone and IK/Transform/Path/Physics skin memberships with reciprocal references.
- Slot normal/additive/multiply/screen are read from the verified enum in the slot-name compound field. Prior codec incorrectly treated this enum as a name prefix and rejected non-normal modes.
- Simultaneous distinct events follow Spine's event-name ordering. Unordered simultaneous events and duplicate names at the same time fail explicitly instead of silently changing event order. JSON/runtime event flow has no such native-format restriction.
- `native/update-skeletons graph [{:name string :source SpineJSON :original-index int?} ...]` updates survivors in the original graph, appends newly created skeletons with reference relocation, renames, deletes omitted skeletons and applies requested order. Project/editor metadata is preserved. Shared FPS is preserved. Empty projects, duplicate names/indices, invalid old indices fail before writing.

## Evidence

- `native-tests.log`: 52 tests, 708 assertions, 0 failures/errors.
- Both changed files passed the required cljfmt formatter.
- clj-kondo: 0 errors, 79 pre-existing warnings (52 source + 27 test); HEAD had 81. No new warnings. Counts and versions are in [verification-context.json](verification-context.json).
- The independent codec decoded official first-party fixtures and rebuilt native files, including deformation, all slot color tracks, skin dependencies and multi-skeleton edits. Official Spine 4.2.43 then successfully reopened those files and exported JSON.
- `native-official-results.json`: **80 explicit assertions (21 initial + 59 continuation)** against official exports, covering coordinates, influences, colors, end slot, blend modes, parent/timeline identity, sequence config/modes/time/index/delay, simultaneous events, and workspace add/delete/rename.
- [native-official-exports.json](native-official-exports.json) preserves the resulting official JSON samples. Minimal binary oracle fixtures are embedded in the permanent [native tests](../../../test/editor/skeletal_animation_native_test.clj); temporary CLI scripts and binaries are excluded from this folder.

Sequence JSON follows official 4.2 names (`setup`, linked mesh `timelines`) rather than obsolete/incorrect `setupIndex`/`deform`. The imported `deform` compatibility alias is handled by the separate data module.

## Limits

The native codec is deliberately version scoped, not a general parser of arbitrary Spine versions. Unsupported original native structures continue to fail explicitly. Native skin-required state derives from membership: a required object needs a named skin, and a member must be required. A standalone skin:true with no named membership cannot be represented without changing semantics; official Spine also discards that flag on JSON import. The codec rejects this case, membership on the unnamed/default skin, and contradictory flags. RGB/RGBA tracks on a dark-enabled slot normalize to RGB2/RGBA2 with setup dark values for unspecified channels. Independent dark-channel mixing across different clips is therefore not retained by this native normalization. Unsupported unknown classes, activation flags, auxiliary state and nondefault footer structures still fail explicitly; the opaque container remains a byte-preserving alternative, not a complete editable document.

GPU preview/offline-image evidence is recorded in [render-validation.md](render-validation.md). Actual AVI decoding is recorded in [avi-results.json](avi-results.json). Temporary scripts and binaries used during validation are intentionally not archived here.
