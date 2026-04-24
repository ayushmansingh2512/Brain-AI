#!/bin/bash
set -e

echo ""
echo "============================================================"
echo "  Brain AI Assistant - macOS Installer Builder"
echo "============================================================"
echo ""

# ─── Paths ────────────────────────────────────────────────────────────────
# Use absolute paths but carefully quote everything to handle spaces
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="$SCRIPT_DIR/lib"
SRC="$SCRIPT_DIR/src/main/java"
RES="$SCRIPT_DIR/src/main/resources"
BUILD="$SCRIPT_DIR/build/classes"
DIST="$SCRIPT_DIR/dist"
JAVAFX_SDK="$SCRIPT_DIR/javafx-sdk-mac"
RUNTIME="$SCRIPT_DIR/build/runtime"

# ─── Step 0: Check tools ──────────────────────────────────────────────────
echo "[0/7] Checking toolchain..."
java -version >/dev/null 2>&1 || { echo "ERROR: java not on PATH. Install JDK 17+."; exit 1; }
jpackage --version >/dev/null 2>&1 || { echo "ERROR: jpackage not found (needs JDK 14+)."; exit 1; }
echo "      OK - Java $(java -version 2>&1 | head -1) + jpackage found."

# ─── Step 1: H2 database JAR ─────────────────────────────────────────────
if [ ! -f "$LIB/h2.jar" ]; then
    echo "[1/7] Downloading H2 embedded database..."
    mkdir -p "$LIB"
    curl -fSL "https://repo1.maven.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar" \
         -o "$LIB/h2.jar"
    echo "      H2 downloaded OK."
else
    echo "[1/7] H2 already present - skipping."
fi

# ─── Step 2: JavaFX macOS SDK ────────────────────────────────────────────
if [ ! -f "$JAVAFX_SDK/lib/javafx.base.jar" ]; then
    echo "[2/7] Downloading JavaFX 21 SDK for macOS ~90MB..."
    # Detect Apple Silicon vs Intel
    ARCH=$(uname -m)
    if [ "$ARCH" = "arm64" ]; then
        FX_URL="https://download2.gluonhq.com/openjfx/21.0.1/openjfx-21.0.1_osx-aarch64_bin-sdk.zip"
        echo "      Detected Apple Silicon (arm64)."
    else
        FX_URL="https://download2.gluonhq.com/openjfx/21.0.1/openjfx-21.0.1_osx-x64_bin-sdk.zip"
        echo "      Detected Intel (x64)."
    fi
    curl -fSL "$FX_URL" -o /tmp/javafx-mac-sdk.zip
    unzip -q /tmp/javafx-mac-sdk.zip -d "$SCRIPT_DIR"
    # Rename extracted folder
    FX_EXTRACTED=$(find "$SCRIPT_DIR" -maxdepth 1 -type d -name "javafx-sdk-*" | head -1)
    if [ -n "$FX_EXTRACTED" ]; then
        mv "$FX_EXTRACTED" "$JAVAFX_SDK"
    fi
    echo "      JavaFX SDK (macOS) ready."
else
    echo "[2/7] JavaFX SDK (macOS) already present - skipping."
fi

# ─── Step 3: Build classpath ─────────────────────────────────────────────
echo "[3/7] Collecting dependencies..."
CP="$LIB/h2.jar"
# Use a loop to build the classpath, quoting each entry correctly
for jar in "$LIB"/*.jar; do
    if [ -f "$jar" ] && [ "$jar" != "$LIB/h2.jar" ]; then
        CP="$CP:$jar"
    fi
done
for jar in "$JAVAFX_SDK/lib"/*.jar; do
    if [ -f "$jar" ]; then
        CP="$CP:$jar"
    fi
done

# ─── Step 4: Compile ─────────────────────────────────────────────────────
echo "[4/7] Compiling Java sources..."
rm -rf "$BUILD" && mkdir -p "$BUILD"
mkdir -p "$SCRIPT_DIR/build"

# Wrap all found .java file paths in double quotes to handle spaces in paths
# This ensures javac @sources.txt reads each path as one argument
find "$SRC" -name "*.java" | sed 's/^/"/;s/$/"/' > "$SCRIPT_DIR/build/sources.txt"

javac -encoding UTF-8 \
      --module-path "$JAVAFX_SDK/lib" \
      --add-modules javafx.controls,javafx.fxml,javafx.graphics,javafx.base,javafx.swing \
      -cp "$CP" \
      -d "$BUILD" \
      @"$SCRIPT_DIR/build/sources.txt"

echo "      Compilation successful!"

# Copy resources (FXML, CSS, Icons)
echo "      Syncing resources..."
cp -r "$RES/." "$BUILD/"

# ─── Step 5: Create application JAR ──────────────────────────────────────
echo "[5/7] Creating application JAR..."
rm -rf "$DIST" && mkdir -p "$DIST/input"

cat > "$SCRIPT_DIR/build/MANIFEST.MF" << EOF
Main-Class: com.forge.aiteam.MainApp
EOF

cd "$BUILD"
jar cfm "$DIST/input/BrainAI.jar" "$SCRIPT_DIR/build/MANIFEST.MF" .
cd "$SCRIPT_DIR"

# Copy all dependency JARs and native dylibs
cp "$LIB/"*.jar         "$DIST/input/" 2>/dev/null || true
cp "$JAVAFX_SDK/lib/"*.jar "$DIST/input/" 2>/dev/null || true
# JavaFX native libraries for macOS
cp "$JAVAFX_SDK/lib/"*.dylib "$DIST/input/" 2>/dev/null || true

echo "      Application JAR created."

# ─── Step 6: jlink custom runtime ────────────────────────────────────────
echo "[6/7] Building custom runtime (includes JavaFX)..."
rm -rf "$RUNTIME"

JAVA_HOME_PATH="$JAVA_HOME"
if [ -z "$JAVA_HOME_PATH" ]; then
    JAVA_HOME_PATH="$(java -XshowSettings:all -version 2>&1 | grep 'java.home' | awk '{print $3}')"
fi

jlink \
  --module-path "$JAVAFX_SDK/lib:$JAVA_HOME_PATH/jmods" \
  --add-modules java.base,java.desktop,java.logging,java.sql,java.naming,java.management,java.xml,jdk.crypto.ec,java.net.http,jdk.localedata,javafx.controls,javafx.fxml,javafx.graphics,javafx.base,javafx.swing \
  --output "$RUNTIME" \
  --strip-debug \
  --no-header-files \
  --no-man-pages && \
  echo "      Custom runtime created." || \
  { echo "      WARNING: jlink failed - packaging without custom runtime."; RUNTIME=""; }

# ─── Step 7: jpackage macOS ──────────────────────────────────────────────
echo "[7/7] Packaging macOS .dmg..."

# Handle Icon Conversion (PNG -> ICNS)
# jpackage on Mac requires a .icns file, but the project has a .png
ICON_SRC="$RES/com/forge/aiteam/icon.png"
ICNS_PATH="$SCRIPT_DIR/build/icon.icns"
ICON_FINAL=""

if [ -f "$ICON_SRC" ]; then
    echo "      Converting icon.png to macOS .icns..."
    # Create the iconset folder
    ICONSET="$SCRIPT_DIR/build/icon.iconset"
    rm -rf "$ICONSET" && mkdir -p "$ICONSET"

    # Create the multiple required sizes (exact names required by iconutil)
    sips -z 16 16     "$ICON_SRC" --out "$ICONSET/icon_16x16.png" >/dev/null 2>&1
    sips -z 32 32     "$ICON_SRC" --out "$ICONSET/icon_16x16@2x.png" >/dev/null 2>&1
    sips -z 32 32     "$ICON_SRC" --out "$ICONSET/icon_32x32.png" >/dev/null 2>&1
    sips -z 64 64     "$ICON_SRC" --out "$ICONSET/icon_32x32@2x.png" >/dev/null 2>&1
    sips -z 128 128   "$ICON_SRC" --out "$ICONSET/icon_128x128.png" >/dev/null 2>&1
    sips -z 256 256   "$ICON_SRC" --out "$ICONSET/icon_128x128@2x.png" >/dev/null 2>&1
    sips -z 256 256   "$ICON_SRC" --out "$ICONSET/icon_256x256.png" >/dev/null 2>&1
    sips -z 512 512   "$ICON_SRC" --out "$ICONSET/icon_256x256@2x.png" >/dev/null 2>&1
    sips -z 512 512   "$ICON_SRC" --out "$ICONSET/icon_512x512.png" >/dev/null 2>&1
    sips -z 1024 1024 "$ICON_SRC" --out "$ICONSET/icon_512x512@2x.png" >/dev/null 2>&1

    # Convert iconset to icns (don't fail the build if this fails)
    if iconutil -c icns "$ICONSET" -o "$ICNS_PATH" 2>/dev/null; then
        ICON_FINAL="$ICNS_PATH"
        echo "      Mac icon ready."
    else
        echo "      Warning: Icon conversion failed. Packaging will use default icon."
    fi
    rm -rf "$ICONSET"
fi

# Construct jpackage arguments as an array to handle spaces correctly
PKG_ARGS=(
  --type dmg
  --name "BrainAI"
  --app-version "1.0.0"
  --vendor "Ayushman Singh"
  --description "Brain AI Assistant - AI-powered code generation pipeline"
  --input "$DIST/input"
  --main-jar BrainAI.jar
  --main-class com.forge.aiteam.MainApp
  --dest "$DIST"
  --java-options "--add-modules=javafx.controls,javafx.fxml,javafx.graphics,javafx.base,javafx.swing,jdk.crypto.ec,java.net.http"
  --java-options "-Dfile.encoding=UTF-8"
  --java-options "-Djava.awt.headless=false"
  --mac-package-name "BrainAI"
)

# Add custom runtime to the arguments if it was built successfully
if [ -n "$RUNTIME" ] && [ -d "$RUNTIME" ]; then
    PKG_ARGS+=(--runtime-image "$RUNTIME")
fi

# Add the app icon if it was successfully converted
if [ -n "$ICON_FINAL" ] && [ -f "$ICON_FINAL" ]; then
    PKG_ARGS+=(--icon "$ICON_FINAL")
fi

# Run jpackage using the array of arguments (quoted "${PKG_ARGS[@]}" handles spaces)
jpackage "${PKG_ARGS[@]}"

if [ -f "$DIST/BrainAI-1.0.0.dmg" ]; then
    echo ""
    echo "============================================================"
    echo "  SUCCESS! macOS Installer created:"
    echo "  $DIST/BrainAI-1.0.0.dmg"
    echo "============================================================"
else
    echo ""
    echo "ERROR: Packaging failed. See errors above."
    exit 1
fi

echo ""
echo "Users only need to set their Gemini API key on first launch."
echo "Generated projects are saved to: ~/BrainAI Projects"
