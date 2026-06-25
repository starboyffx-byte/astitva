#!/bin/bash
# ASTITVA OS - LIVE INSTALLATION AND PROVISIONING SCRIPT

APK_PATH="/data/data/com.termux/files/home/astitva-apk-project/astitva_os_v1.apk"
PACKAGE="com.vicky.astitva"

if [ ! -f "$APK_PATH" ]; then
    echo "❌ APK file not found at $APK_PATH."
    exit 1
fi

echo "[1/4] Installing APK..."
su -c "pm install -r -d $APK_PATH"
if [ $? -ne 0 ]; then
    echo "❌ Installation failed."
    exit 1
fi
echo "✅ APK Installed."

echo "[2/4] Granting App permissions..."
su -c "pm grant $PACKAGE android.permission.RECORD_AUDIO"
su -c "pm grant $PACKAGE android.permission.CAMERA"
su -c "pm grant $PACKAGE android.permission.ACCESS_FINE_LOCATION"
su -c "pm grant $PACKAGE android.permission.ACCESS_COARSE_LOCATION"
su -c "pm grant $PACKAGE android.permission.CALL_PHONE"
su -c "pm grant $PACKAGE android.permission.READ_SMS"
su -c "pm grant $PACKAGE android.permission.POST_NOTIFICATIONS"
su -c "pm grant $PACKAGE android.permission.READ_EXTERNAL_STORAGE"
su -c "pm grant $PACKAGE android.permission.WRITE_EXTERNAL_STORAGE"

echo "[3/4] Granting AppOps permissions..."
su -c "appops set $PACKAGE SYSTEM_ALERT_WINDOW allow"
su -c "appops set $PACKAGE MANAGE_EXTERNAL_STORAGE allow"
su -c "appops set $PACKAGE WRITE_SETTINGS allow"
echo "✅ Permissions & AppOps set."

echo "[4/4] Activating Accessibility and Voice assistant..."
# Enable Accessibility Service
su -c "settings put secure enabled_accessibility_services $PACKAGE/com.vicky.astitva.core.AstitvaAccessibility"
su -c "settings put secure accessibility_enabled 1"

# Set default assistant
su -c "settings put secure assistant $PACKAGE/com.vicky.astitva.core.AstitvaVoiceInteractionService"
su -c "settings put secure voice_interaction_service $PACKAGE/com.vicky.astitva.core.AstitvaVoiceInteractionService"

echo "✅ Accessibility Enabled."
echo "✅ Default Assistant Set."
echo "🎉 SUCCESS: Astitva is fully active and initialized!"
