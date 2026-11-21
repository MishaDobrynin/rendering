#!/bin/sh

set -eu

# Resolve the project directory from the location of this script.
SCRIPT_PATH=$(readlink -f "$0")
ROOT=$(dirname "$SCRIPT_PATH")

BUILD="$ROOT/build"
CLASSES="$BUILD/classes"
JAR="$BUILD/warship-renderer.jar"
SOURCE_DIR="$ROOT/src/main/java"

echo "Building Warship Renderer..."
echo "Project directory: $ROOT"

# Make sure the script is being run from a valid project.
if [ "$ROOT" = "/" ] || [ ! -d "$SOURCE_DIR" ]; then
    echo "ERROR: Could not find the Java source directory:"
    echo "       $SOURCE_DIR"
    echo "Check that build.sh is inside the warship-renderer project folder."
    exit 1
fi

# Check that the Java compiler is installed.
if ! command -v javac >/dev/null 2>&1; then
    echo "ERROR: javac was not found."
    echo "Install a JDK, then run this script again."
    echo "On Raspberry Pi OS, try: sudo apt install default-jdk"
    exit 1
fi

if ! command -v jar >/dev/null 2>&1; then
    echo "ERROR: The jar tool was not found. Install a JDK."
    exit 1
fi

# Display the Java compiler version.
javac -version

# Create a clean build directory.
echo "Cleaning previous build..."
rm -rf "$CLASSES"
mkdir -p "$CLASSES"

# Compile Java source files.
# The ! -name '._*' condition excludes macOS AppleDouble metadata files.
echo "Compiling Java source files..."

find "$SOURCE_DIR" \
    -type f \
    -name '*.java' \
    ! -name '._*' \
    -print0 |
    xargs -0 javac \
        -encoding UTF-8 \
        --release 17 \
        -d "$CLASSES"

# Create the executable JAR manifest.
echo "Creating JAR manifest..."

printf 'Main-Class: renderer.Main\n' > "$BUILD/manifest.mf"

# Package the compiled classes into an executable JAR.
echo "Packaging application..."

jar cfm "$JAR" "$BUILD/manifest.mf" -C "$CLASSES" .

echo ""
echo "======================================"
echo " BUILD COMPLETED"
echo "======================================"
echo "JAR location: $JAR"
echo ""
echo "To run the application:"
echo "java -jar \"$JAR\""
echo ""