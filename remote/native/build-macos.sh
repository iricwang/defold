#!/bin/sh
set -eu
# Builds an optional JNI codec resource for the editor's ordinary resource packaging.
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
java_dir=${JAVA_HOME:-/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home}
arch=${1:-arm64}
output="$root/editor/resources/lib/$arch-macos/libremote_video.dylib"
mkdir -p "$(dirname "$output")"
xcrun clang -O3 -Wall -Wextra -Wno-unused-parameter -arch "$arch" -mmacosx-version-min=12.0 -dynamiclib \
    -I"$java_dir/include" -I"$java_dir/include/darwin" \
    -framework CoreImage -framework Metal -framework CoreGraphics -framework Foundation -framework VideoToolbox -framework CoreMedia -framework CoreVideo \
    "$root/remote/native/remote_video.m" -o "$output"
codesign --force --sign - "$output"
