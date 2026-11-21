#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ ! -f "$ROOT/build/warship-renderer.jar" ]; then
    "$ROOT/build.sh"
fi
# Let the JVM size its heap from available RAM (suitable for 4 GB+ Pi systems).
exec java -XX:MaxRAMPercentage=70.0 -jar "$ROOT/build/warship-renderer.jar" "$@"
