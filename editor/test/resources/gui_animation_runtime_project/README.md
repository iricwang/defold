# GUI animation runtime regression game

A runnable Defold game, rather than an editor preview or a mocked adapter. Open `game.project` in the fork editor and run it. After the automatic checks finish, SPACE or a click attacks; R resets the battle. A timeline event applies 25 damage and awards 10 points. The GUI, game code and built-in font/texture are local assets; no MiniGame framework is required.

`authoring/features_panthera.json` preserves the GUI editor recording with `box.position_x = 480` at 1 second. The other authoring documents define the full channel and playback matrix. The root JSON/Lua files are actual runtime exports from the editor's production writer; `expected.json` is sampled independently by the editor evaluator. Keep these generated files together.

## Reproduce

Requires Python 3.12, JDK 25, Bob and a matching macOS Defold engine (validated with 1.14.1, engine commit `5139343`). From the repository root:

```sh
python3.12 editor/test/resources/gui_animation_runtime_project/run.py \
  --bob tmp/dynamo_home/share/java/bob.jar \
  --engine tmp/dynamo_home/bin/arm64-macos/dmengine \
  --java /opt/homebrew/opt/openjdk@25/bin/java \
  --output tmp/gui-animation-runtime --repeat 2
```

Use `--platform x86_64-macos` with an Intel engine. CI uses `dmengine_headless` for the same GUI logic and numeric assertions. Local screenshot acceptance uses the Metal GPU engine and the editor's Game pane. These are separate kinds of evidence.

`setup.py` downloads only missing dependencies at exact commits, validates every module hash before installation, and retains their MIT licenses in `licenses/`. `panthera/` and `tweener/` are generated, unmodified upstream sources. The project intentionally excludes Panthera's independent editor extension and example dependencies.

To regenerate exports in the editor's live REPL, use absolute paths:

```clojure
(load-file "/absolute/repository/editor/scripts/gui_animation_runtime_export.clj")
(gui-animation-runtime-export/export! "/absolute/repository/editor/test/resources/gui_animation_runtime_project")
```

## What is checked

- JSON and Lua: 392 time samples spanning 8 feature clips and 48 regression clips, including backward seeks and boundaries; all 32 numeric GUI properties and 15 trigger properties, with real GUI getters.
- All 41 named easings and a custom overshoot curve: real animated frames compared against independent `gui.animate` reference nodes, plus end poses and completion callbacks.
- Initial state, overlapping tracks, local nested clips and GUI template clips.
- Actual event order, payload, one-shot damage/score, continuous progress callbacks and finite elastic values.
- Speed, reverse playback, three loops, stop holding its pose, and restart without duplicate damage.
- A separate UI pass checks keyboard/mouse input in the embedded game, reset, recompilation and restart, with screenshots and state files.

The runner requires an explicit completion result and zero failed assertions; missing callbacks trip a 90-second watchdog independent of frame rate and fail. It does not infer success from a game window opening. Numeric tolerance is 0.002; warped nested playback uses 0.01 pixels because its independent oracle interpolates a 1 ms table. Per-frame check counts vary slightly with actual frame timing.

Use `panthera.play` for native GUI tweens. For eased nested clip time, reverse ranges or sampled playback, use `panthera.play_tweener` (the regression exercises both APIs). Panthera 11's native clip scheduler advances nested time linearly. This project does not modify that third-party scheduler. Export each referenced template document as well as its parent. Editing JSON retains named curves; **Export JSON / Export Lua** bakes the same engine samples into `easing_custom`, fixing tweener 6's differing curves and non-finite `inelastic` sampling.

Scope: macOS arm64 is verified locally with Metal and headless engines. macOS Intel is added to CI; Windows/mobile/browser runtime execution is not claimed by the local report.
