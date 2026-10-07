# macOS DMG builds for a fork

The local build and `.github/workflows/macos-dmg.yml` use the same entry point.
It compiles the current checkout's engine, Bob and editor, then creates and
verifies a DMG. It does not need Defold's S3, signing, or publishing credentials.

## Local setup

Install Xcode, complete its first-launch setup, and select it with
`xcode-select`. Install the command-line dependencies:

```sh
brew install python@3.12 openjdk@25 cmake ninja
```

From the repository root:

```sh
bash scripts/macos/build-dmg.sh
```

The script detects the host architecture and Homebrew JDK, creates a Python
virtual environment in `tmp/macos-build-venv`, and uses the repository's bundled
Leiningen launcher. It respects an existing `JAVA_HOME`; that must point to JDK
25. Set `DEFOLD_PYTHON` if Python 3.12 is installed at a nonstandard path.
The editor's bundled runtime is the exact Temurin version in
`build_tools/sdk.py`, downloaded by the existing packaging script.

Outputs:

- `editor/target/editor/Defold-arm64-macos.dmg` on Apple Silicon
- `editor/target/editor/Defold-x86_64-macos.dmg` on Intel
- A corresponding `.dmg.sha256` checksum file

Build on a matching host for each architecture. Subsequent builds reuse CMake
and dependency caches. The script skips unit/integration test suites, matching
the upstream editor packaging job; a successful run confirms compilation,
packaging and DMG integrity, not full test coverage.

## GitHub Actions

The **macOS DMG** workflow runs on pushes (except `skip-ci-*` and `contrib/**`),
pull requests, and manual dispatch. It builds ARM64 and Intel independently on
`macos-26` and `macos-26-intel`. Download the DMG and checksum from the successful
run's **Artifacts** section; these are retained for 14 days. Build logs are
retained for 7 days, including failed builds. A missing DMG fails the job.

These are development builds without Developer ID signing or notarization.
macOS may require approval in Privacy & Security when first opening a downloaded
build. They are uploaded as Actions artifacts, not published as official releases.
The `dev` channel keeps these builds distinct from the upstream release channels.

The upstream **CI - Main** and **CI - Engine nightly** workflows have additional
platform and official infrastructure requirements. This workflow is independent
of them; enabling it does not provision their secrets or validate other targets.
