
#!/bin/sh
set -eu

# Always use the directory containing this script.
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

BUILD="$ROOT/build"
CLASSES="$BUILD/classes"
JAR="$BUILD/warship-renderer.jar"
SOURCE_DIR="$ROOT/src/main/java"

echo "Building Warship Renderer..."
echo "Project directory: $ROOT"

if [ ! -d "$SOURCE_DIR" ]; then
    echo "ERROR: Java source directory not found: $SOURCE_DIR"
    echo "Download the complete project and try again."
    exit 1
fi

for tool in javac jar; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "ERROR: $tool not found."
        echo "Install Java 17 or newer JDK."
        echo "Raspberry Pi OS: sudo apt install openjdk-17-jdk"
        exit 1
    fi
done

javac -version

mkdir -p "$BUILD"
rm -rf "$CLASSES"
mkdir -p "$CLASSES"

echo "Compiling Java source files..."

find "$SOURCE_DIR" \
    -type f \
    -name '*.java' \
    ! -name '._*' \
    -exec javac -encoding UTF-8 --release 17 -d "$CLASSES" {} +

printf 'Main-Class: renderer.Main\n' > "$BUILD/manifest.mf"

echo "Packaging application..."
jar cfm "$JAR" "$BUILD/manifest.mf" -C "$CLASSES" .

echo "BUILD COMPLETED"
echo "JAR: $JAR"
echo "Run using: sh \"$ROOT/run.sh\""
