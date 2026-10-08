#!/usr/bin/env bash
# Compatibility entry point: this fork now produces only incremental patches.
set -euo pipefail
exec bash "$(dirname "$0")/build-patch.sh" "$@"
