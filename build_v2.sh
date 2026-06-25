#!/bin/bash

# ASTITVA OS - GOD-MODE BUILD SCRIPT (v2026-CRASH-PROOF)
# Engineered by Vicky & ASTITVA

PROJECT_ROOT=~/astitva-apk-project
BUILD_DIR=$PROJECT_ROOT/build
GEN_DIR=$PROJECT_ROOT/gen
OUT_APK=$PROJECT_ROOT/astitva_os_v1.apk

# Tool Paths
SDK_DIR=/data/data/com.termux/files/usr/opt/android-sdk
BUILD_TOOLS_VER=36.1.0 
ANDROID_JAR=$SDK_DIR/platforms/android-36/android.jar
AAPT2=$SDK_DIR/build-tools/$BUILD_TOOLS_VER/aapt2
D8=$SDK_DIR/build-tools/$BUILD_TOOLS_VER/d8
APKSIGNER=$SDK_DIR/build-tools/$BUILD_TOOLS_VER/apksigner
KOTLINC=kotlinc
STDLIB=$PROJECT_ROOT/libs/kotlin-stdlib.jar

rm -rf $BUILD_DIR $GEN_DIR $OUT_APK
mkdir -p $BUILD_DIR/classes $BUILD_DIR/dex $GEN_DIR

echo "[1/5] Compiling Resources..."
$AAPT2 compile --dir $PROJECT_ROOT/app/src/res -o $BUILD_DIR/res.zip || exit 1

echo "[2/5] Linking Resources & Manifest..."
$AAPT2 link -o $BUILD_DIR/base.apk \
    -I $ANDROID_JAR \
    -A $PROJECT_ROOT/app/src/main/assets \
    --manifest $PROJECT_ROOT/app/src/AndroidManifest.xml \
    --java $GEN_DIR \
    --min-sdk-version 26 \
    --target-sdk-version 36 \
    -0 tflite -0 txt \
    $BUILD_DIR/res.zip --auto-add-overlay || exit 1

echo "[3/5] Compiling Kotlin Source with StdLib..."
KOTLIN_FILES=$(find $PROJECT_ROOT/app/src/main/java -name "*.kt")
R_JAVA_FILE=$GEN_DIR/com/vicky/astitva/R.java

TFLITE_JAR=$PROJECT_ROOT/libs/tensorflow-lite.jar
TFLITE_API_JAR=$PROJECT_ROOT/libs/tensorflow-lite-api.jar

# Include StdLib and TFLite in compile classpath
$KOTLINC -cp $ANDROID_JAR:$STDLIB:$TFLITE_JAR:$TFLITE_API_JAR \
    $KOTLIN_FILES $R_JAVA_FILE \
    -d $BUILD_DIR/classes || exit 1

echo "[4/5] Converting Bytecode to DEX (Bundling Kotlin Runtime & TFLite)..."
CLASS_FILES=$(find $BUILD_DIR/classes -name "*.class")
# D8 merging classes + stdlib + tflite into final classes.dex
$D8 --lib $ANDROID_JAR --release --output $BUILD_DIR/dex/ $CLASS_FILES $STDLIB $TFLITE_JAR $TFLITE_API_JAR || exit 1

echo "[5/5] Final Packaging & Signing..."
cp $BUILD_DIR/base.apk $OUT_APK || exit 1
cd $BUILD_DIR/dex/ && zip -uj $OUT_APK classes.dex || exit 1

# Package native JNI libraries (lib/arm64-v8a/libtensorflowlite_jni.so) into the APK with 0 compression (uncompressed)
if [ -d $PROJECT_ROOT/libs/lib ]; then
    echo "Bundling native JNI libraries (uncompressed)..."
    cd $PROJECT_ROOT/libs && zip -0 -r $OUT_APK lib/ || exit 1
fi

# Page-align the APK (required for loading uncompressed native libraries from the APK)
echo "Aligning APK..."
$SDK_DIR/build-tools/$BUILD_TOOLS_VER/zipalign -f -p 4 $OUT_APK ${OUT_APK}.aligned || exit 1
mv ${OUT_APK}.aligned $OUT_APK || exit 1

# Generate key if missing
if [ ! -f ~/debug.keystore ]; then
    keytool -genkey -v -keystore ~/debug.keystore -alias androiddebugkey -keypass android -storepass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Vicky, OU=ASTITVA, O=Vicky, L=India, S=India, C=IN"
fi

$APKSIGNER sign --min-sdk-version 26 --ks ~/debug.keystore --ks-pass pass:android --key-pass pass:android --out $OUT_APK $OUT_APK

echo "--------------------------------------------"
echo "✅ SUCCESS! ASTITVA OS v2.0 IS LIVE."
echo "Path: $OUT_APK"
echo "--------------------------------------------"