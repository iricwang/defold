#!/usr/bin/env bash
# Build the current checkout without Defold's publishing or signing credentials.
set -euo pipefail

cd "$(dirname "$0")/../.."
if [[ "$(uname -s)" != Darwin ]]; then
    echo 'This build requires macOS and Xcode.' >&2
    exit 1
fi
platform="$(uname -m)-macos"
if [[ $# -gt 1 || (${1:-$platform} != arm64-macos && ${1:-$platform} != x86_64-macos) ]]; then
    echo "Usage: $0 [arm64-macos|x86_64-macos]" >&2
    exit 1
fi
if [[ ${1:-$platform} != "$platform" ]]; then
    echo 'Run each architecture on a matching Mac (or GitHub runner).' >&2
    exit 1
fi

# GitHub setup-java/setup-python supply these paths. Use Homebrew locally.
if [[ -z ${JAVA_HOME:-} ]] && command -v brew >/dev/null; then
    java_prefix="$(brew --prefix openjdk@25 2>/dev/null || true)"
    if [[ -d "$java_prefix/libexec/openjdk.jdk/Contents/Home" ]]; then
        export JAVA_HOME="$java_prefix/libexec/openjdk.jdk/Contents/Home"
    fi
fi
if [[ -z ${JAVA_HOME:-} ]]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 25)"
    export JAVA_HOME
fi
export PATH="$JAVA_HOME/bin:$PATH"
python_bin="${DEFOLD_PYTHON:-python3.12}"
"$python_bin" -c 'import sys; assert sys.version_info[:2] == (3, 12), "Python 3.12 required"'
java_version="$("$JAVA_HOME/bin/javac" -version 2>&1)"
if [[ "$java_version" != 'javac 25.'* && "$java_version" != 'javac 25' ]]; then
    echo "JDK 25 required; found $java_version" >&2
    exit 1
fi
echo "$java_version"
xcodebuild -version
cmake --version
ninja --version

mkdir -p tmp
"$python_bin" -m venv tmp/macos-build-venv
# These files are generated locally by venv and build.py.
# shellcheck source=/dev/null
source tmp/macos-build-venv/bin/activate
python scripts/build.py --save-env-path=tmp/macos-build-env.sh save_env
# shellcheck source=/dev/null
source tmp/macos-build-env.sh

python scripts/build.py install_ext check_sdk --platform="$platform"
python scripts/build.py build_engine --platform="$platform" --skip-tests -- --skip-build-tests
python scripts/build.py build_bob --platform="$platform" --skip-tests --keep-bob-uncompressed
python scripts/build.py build_editor2 --platform="$platform" --channel=dev \
    --engine-artifacts=dynamo-home --skip-tests

dmg="editor/target/editor/Defold-$platform.dmg"
test -s "$dmg"
hdiutil verify "$dmg"
(cd editor/target/editor && shasum -a 256 "Defold-$platform.dmg" > "Defold-$platform.dmg.sha256")
echo "Built $PWD/$dmg"
