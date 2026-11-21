#!/bin/sh
set -eu
SCRIPT_DIR=$(dirname "$0")
ROOT=$(CDPATH= cd "$SCRIPT_DIR" && pwd)
BUILD="$ROOT/build"
CLASSES="$BUILD/classes"
JAR="$BUILD/warship-studio.jar"
SOURCE_DIR="$ROOT/src/main/java"
RESOURCE_DIR="$ROOT/src/main/resources"

if [ ! -d "$SOURCE_DIR" ]; then
    echo "ERROR: Java source directory not found: $SOURCE_DIR" >&2
    exit 1
fi
if ! command -v javac >/dev/null 2>&1 || ! command -v jar >/dev/null 2>&1; then
    echo "ERROR: A JDK (including javac and jar) is required." >&2
    exit 1
fi

echo "Building Warship Studio..."
javac -version
rm -rf "$CLASSES"
mkdir -p "$CLASSES"
find "$SOURCE_DIR" -type f -name '*.java' ! -name '._*' -print0 |
    xargs -0 javac -encoding UTF-8 --release 17 -d "$CLASSES"
printf 'Main-Class: renderer.Main\n' > "$BUILD/manifest.mf"
if [ -d "$RESOURCE_DIR" ]; then
    cp -R "$RESOURCE_DIR"/. "$CLASSES"/
fi
jar cfm "$JAR" "$BUILD/manifest.mf" -C "$CLASSES" .

if [ "$(uname -s)" = "Darwin" ]; then
    if ! command -v clang >/dev/null 2>&1; then
        echo "ERROR: Apple's clang is required to compile the Metal JNI bridge. Install Xcode Command Line Tools with: xcode-select --install" >&2
        exit 1
    fi
    if [ -z "${JAVA_HOME:-}" ]; then
        if [ -x /usr/libexec/java_home ]; then
            JAVA_HOME=$(/usr/libexec/java_home)
        else
            echo "ERROR: JAVA_HOME is not set and /usr/libexec/java_home was not found." >&2
            exit 1
        fi
    fi
    mkdir -p "$BUILD/native"
    echo "Building native Metal bridge for $(uname -m)..."
    clang -O3 -fobjc-arc -dynamiclib \
        -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" \
        "$ROOT/native/metal_bridge.m" \
        -framework Foundation -framework Metal \
        -o "$BUILD/native/libwarshipmetal.dylib"
    echo "Metal bridge complete: $BUILD/native/libwarshipmetal.dylib"
else
    echo "Non-macOS build: Metal native bridge skipped; CPU rendering remains available."
fi

echo "Build complete: $JAR"