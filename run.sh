
#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$ROOT/build/warship-renderer.jar"

# Automatically build if the application has not been built yet.
if [ ! -f "$JAR" ]; then
    echo "Application not built. Building now..."
    sh "$ROOT/build.sh"
fi

if ! command -v java >/dev/null 2>&1; then
    echo "ERROR: Java runtime not found."
    echo "Install Java 17 or newer."
    exit 1
fi

exec java -XX:MaxRAMPercentage=70.0 -jar "$JAR" "$@"
