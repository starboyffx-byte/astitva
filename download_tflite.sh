#!/bin/bash
# ASTITVA OS - TFLite Setup & Bundle Script
# Engineered by Vicky & ASTITVA

PROJECT_ROOT=/data/data/com.termux/files/home/astitva-apk-project
LIBS_DIR=$PROJECT_ROOT/libs
ASSETS_DIR=$PROJECT_ROOT/app/src/main/assets
TEMP_DIR=$PROJECT_ROOT/temp_tflite

mkdir -p $LIBS_DIR $ASSETS_DIR $TEMP_DIR

echo "[1/3] Downloading TensorFlow Lite and API AARs..."
TFLITE_AAR_URL="https://repo1.maven.org/maven2/org/tensorflow/tensorflow-lite/2.14.0/tensorflow-lite-2.14.0.aar"
curl -L $TFLITE_AAR_URL -o $TEMP_DIR/tflite.aar || exit 1

TFLITE_API_AAR_URL="https://repo1.maven.org/maven2/org/tensorflow/tensorflow-lite-api/2.14.0/tensorflow-lite-api-2.14.0.aar"
curl -L $TFLITE_API_AAR_URL -o $TEMP_DIR/tflite-api.aar || exit 1

echo "[2/3] Extracting TFLite jar classes, API classes, and JNI native libraries..."
# Extract classes.jar to libs/tensorflow-lite.jar
unzip -p $TEMP_DIR/tflite.aar classes.jar > $LIBS_DIR/tensorflow-lite.jar || exit 1

# Extract classes.jar to libs/tensorflow-lite-api.jar
unzip -p $TEMP_DIR/tflite-api.aar classes.jar > $LIBS_DIR/tensorflow-lite-api.jar || exit 1

# Extract native library (.so) for arm64-v8a CPU architecture
mkdir -p $LIBS_DIR/lib/arm64-v8a
unzip -p $TEMP_DIR/tflite.aar jni/arm64-v8a/libtensorflowlite_jni.so > $LIBS_DIR/lib/arm64-v8a/libtensorflowlite_jni.so || exit 1

echo "[3/3] Downloading MobileNet SSD Object Detection model..."
MODEL_URL="https://storage.googleapis.com/download.tensorflow.org/models/tflite/coco_ssd_mobilenet_v1_1.0_quant_2018_06_29.zip"
curl -L $MODEL_URL -o $TEMP_DIR/model.zip || exit 1

unzip -p $TEMP_DIR/model.zip detect.tflite > $ASSETS_DIR/detect.tflite || exit 1
unzip -p $TEMP_DIR/model.zip labelmap.txt > $ASSETS_DIR/labelmap.txt || exit 1

# Clean up temporary downloads
rm -rf $TEMP_DIR

echo "---------------------------------------------------------"
# List the prepared directories
ls -la $LIBS_DIR
ls -lh $ASSETS_DIR/detect.tflite
echo "✅ TFLITE AND MODEL PREPARED SUCCESSFULLY FOR BUNDLING!"
echo "---------------------------------------------------------"
