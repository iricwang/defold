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

## Local native-extension build service

Updating the editor does not update the SDK used by a separate local Extender
service. After rebuilding the engine, package its complete SDK from the same
build environment:

```sh
python scripts/build.py build_platform_sdk --platform=arm64-macos
```

The output is `tmp/dynamo_home/defoldsdk.zip` when using this fork's local build
environment. Check the packaging log and archive before deployment: the build
command can report a packaging error without returning a nonzero exit status.
Stop the local service before replacing its `DYNAMO_HOME` directory, keep the
previous directory for rollback, and extract the archive preserving executable
permissions (for example, with `ditto -x -k`). Retain any service-specific SDK
metadata separately, then restart the service and verify its health endpoint.
Deploy headers, libraries and `extender/build.yml` together from this archive.

Do not deploy only headers and libraries. Extensions containing `.proto` files
also require `ext/bin/arm64-macos/protoc`, `bin/ddfc_cxx`, `bin/ddfc.py`,
`lib/python`, `ext/lib/python/google`, and `share/proto`. The SDK build recipe
sets `PYTHONPATH` for the DDF generator. A missing `protoc` produces an Extender
`generateProtoCxxForEngine` failure even when extensions without Proto files
previously built successfully.

After deployment, verify an actual extension containing Proto definitions and
run the resulting engine; `protoc --version` alone does not exercise DDF
generation, extension compilation or linking. Use the configured loopback
service when local-only builds are required.

On 2026-10-28, the local service at `127.0.0.1:9011` was repaired using the SDK
from engine `61ce841dbe`. Dragon Burst's actual editor Game bridge and Spine
extension compiled and linked successfully (job `1874678848398488809`). The
resulting engine registered Spine, initialized Metal, and reached both
`AUTOMATION_READY` and `SAFE_AREA` in the local GUI runtime fixture without
runtime errors. This verifies native compilation and engine startup, not a
complete gameplay regression. Local evidence is retained in
`tmp/local-extender-repair/build.log` and `tmp/local-extender-repair/runtime.log`.
