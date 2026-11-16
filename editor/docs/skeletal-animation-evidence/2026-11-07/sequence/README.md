# Sequence and attachment verification

Spine @esotericsoftware/spine-core 4.2.120 was used as the independent sequence oracle.
84 cases cover 1, 2, 3 and 6 frames; all seven sequence modes; starting indices 0, 1 and 7;
and 13 time positions per case. The 1,092 sampled frame indices match exactly.

The oracle converts the playback cursor to float32, matching Spine C and native runtime
time precision. Without this conversion, decimal 0.2 compared to an internal float32 key
at 0.20000000298023224 chooses the preceding setup state in JavaScript. The production
sampler accepts editor times in seconds and uses Spine's frame-step epsilon.

Run the independent side with:

    node oracle.mjs /path/to/@esotericsoftware/spine-core/dist/index.js

actual.json is the Defold skeletal-animation-sequence/sample-index result for
cases.json; expected.json is the independent implementation's result.

attachments-regression.log records the complete targeted regression suite, including
existing data and IO cases plus alternate attachment management, native control actions,
combined skins, linked timeline inheritance, two-color keys, complete numbered PNG
import/atlas export, audio copying, missing-audio warnings, and path/symlink containment.
No runtime engine changes were needed.

The final combined suite passed 102 tests / 770 assertions. The additional portable atlas
was read by official TextureAtlas 4.2.120: both pages and all five named regions had
identical dimensions and coordinates (atlas-report.json, textures.atlas).
