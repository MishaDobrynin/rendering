#!/bin/sh
set -eu
SCRIPT_DIR=$(dirname "$0")
ROOT=$(CDPATH= cd "$SCRIPT_DIR" && pwd)

# Always rebuild so Java classes, the bundled .metal resource, and the Mac JNI library stay in sync.
"$ROOT/build.sh"

cd "$ROOT"
if [ "$(uname -s)" = "Darwin" ]; then
    exec java -Djava.library.path="$ROOT/build/native${JAVA_LIBRARY_PATH:+:$JAVA_LIBRARY_PATH}" \
        -XX:MaxRAMPercentage=70.0 -jar "$ROOT/build/warship-studio.jar" "$@"
fi
exec java -XX:MaxRAMPercentage=70.0 -jar "$ROOT/build/warship-studio.jar" "$@"