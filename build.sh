#!/bin/bash
set -e

# ─── Paths ───────────────────────────────────────────────────────────────────
SDK=/usr/lib/android-sdk
PLATFORM=$SDK/platforms/android-23
ANDROID_JAR=$PLATFORM/android.jar
AAPT=$SDK/build-tools/29.0.3/aapt
DX=$SDK/build-tools/debian/dx

SRC=app/src/main
JAVA_SRC=$SRC/java
MANIFEST=$SRC/AndroidManifest.xml
RES=$SRC/res

BUILD=build
GEN=$BUILD/gen
CLASSES=$BUILD/classes
DEX_DIR=$BUILD/dex
RESOURCES_AP=$BUILD/resources.ap_
UNSIGNED=$BUILD/wea-unsigned.apk
ALIGNED=$BUILD/wea-aligned.apk
FINAL=wea.apk

# ─── Clean ────────────────────────────────────────────────────────────────────
rm -rf $BUILD
mkdir -p $GEN $CLASSES $DEX_DIR

echo ""
echo "╔══════════════════════════════════════════╗"
echo "║  WEA Widget — Build                      ║"
echo "╚══════════════════════════════════════════╝"
echo ""

# ─── Step 1: Resources + R.java ──────────────────────────────────────────────
echo "[1/6] Compiling resources and generating R.java..."
$AAPT package -f -m \
    --auto-add-overlay \
    -J $GEN \
    -S $RES \
    -M $MANIFEST \
    -I $ANDROID_JAR \
    -F $RESOURCES_AP \
    || { echo "ERROR: aapt failed"; exit 1; }

# ─── Step 2: Compile Java ────────────────────────────────────────────────────
echo "[2/6] Compiling Java sources..."
find $JAVA_SRC $GEN -name "*.java" > $BUILD/sources.txt
javac -source 8 -target 8 \
    -bootclasspath $ANDROID_JAR \
    -classpath $ANDROID_JAR \
    -d $CLASSES \
    @$BUILD/sources.txt \
    || { echo "ERROR: javac failed"; exit 1; }

# ─── Step 3: DEX ─────────────────────────────────────────────────────────────
echo "[3/6] Converting to DEX..."
$DX --dex \
    --output=$DEX_DIR/classes.dex \
    $CLASSES \
    || { echo "ERROR: dx failed"; exit 1; }

# ─── Step 4: Package APK ─────────────────────────────────────────────────────
echo "[4/6] Packaging APK..."
cp $RESOURCES_AP $UNSIGNED
(cd $DEX_DIR && zip -q -r ../../$UNSIGNED classes.dex) \
    || { echo "ERROR: zip failed"; exit 1; }

# ─── Step 5: Align ───────────────────────────────────────────────────────────
echo "[5/6] Aligning APK..."
zipalign -v 4 $UNSIGNED $ALIGNED > /dev/null \
    || { echo "ERROR: zipalign failed"; exit 1; }

# ─── Step 6: Sign ────────────────────────────────────────────────────────────
echo "[6/6] Signing APK..."
KEYSTORE=debug.keystore
if [ ! -f $KEYSTORE ]; then
    echo "  Generating debug keystore..."
    keytool -genkeypair -v \
        -keystore $KEYSTORE \
        -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Debug,OU=Debug,O=Android,L=Debug,S=Debug,C=US" \
        -storepass android -keypass android \
        2>/dev/null
fi

apksigner sign \
    --ks $KEYSTORE \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out $FINAL \
    $ALIGNED \
    || { echo "ERROR: apksigner failed"; exit 1; }

echo ""
echo "╔══════════════════════════════════════════╗"
SIZE=$(du -h $FINAL | cut -f1)
echo "║  ✓  $FINAL  ($SIZE)$(printf '%*s' $((28 - ${#FINAL} - ${#SIZE})) '')║"
echo "╚══════════════════════════════════════════╝"
echo ""
echo "  Install:  adb install -r $FINAL"
echo ""
