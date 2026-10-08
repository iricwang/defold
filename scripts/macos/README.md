# macOS patch builds for this fork

Local builds and the **macOS Patch** workflow compile the engine, Bob and editor,
then produce only an incremental patch ZIP and a small file-inventory JSON.
They do not create an application bundle, run `jlink`, or package a DMG/full-app ZIP.

## Build locally

Install Xcode and `python@3.12`, `openjdk@25`, `cmake`, `ninja`, and `gh` with
Homebrew. Authenticate `gh` to read the published baseline, then run:

```sh
python3.12 patch/manage.py bump --notes "Describe this update"
bash scripts/macos/build-patch.sh
```

The old `build-dmg.sh` entry point now forwards to `build-patch.sh` for compatibility.
The script detects the host architecture, reuses downloaded dependencies and
builds on a matching Mac. `JAVA_HOME` must point to JDK 25; `DEFOLD_PYTHON` can
select Python 3.12 at a custom path. Outputs in `editor/target/editor/` are:

- `Defold-<platform>-patch-<base>-to-<revision>.zip`
- `Defold-<platform>-state.json`

The first transition from patch.4 reads its existing published application ZIP
to create the baseline inventory, then discards the download. Later builds fetch
only the small state JSON. The installed JDK is retained; changing its version
or requiring new runtime modules fails the patch build rather than producing an
incomplete update. Such a runtime migration requires a separately planned full
installation; this workflow never generates one automatically.

## GitHub Actions

The workflow builds Apple Silicon and Intel on matching macOS runners, tests the
updater and docking layout, and retains patch artifacts for 14 days and logs for
7 days. Pushes to `codex/editor-docking-patch4` in `iricwang/defold` publish both
platforms and update the existing `dev` patch feed. Other branches and PRs only
produce artifacts. Every new patch must have a revision higher than the latest
published release. No signing or official Defold publishing credentials are used.

See [patch delivery](../../patch/README.md) for installation and local verification.
