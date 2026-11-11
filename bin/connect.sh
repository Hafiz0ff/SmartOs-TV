#!/usr/bin/env bash
# SmartTV-Hub - ADB Connection Script
set -e

TV_IP="${1:-192.168.100.5}"
TV_PORT="${2:-5555}"
TARGET="${TV_IP}:${TV_PORT}"

echo "========================================"
echo "Connecting to Smart TV at $TARGET..."
echo "========================================"

adb disconnect "$TARGET" 2>/dev/null || true
adb connect "$TARGET"

echo ""
adb devices

STATUS=$(adb devices | grep "$TARGET" | awk '{print $2}')
if [ "$STATUS" = "device" ]; then
    echo "✓ Connected successfully to $TARGET"
    DEVICE_MODEL=$(adb -s "$TARGET" shell getprop ro.product.model 2>/dev/null || echo "Unknown")
    ANDROID_VER=$(adb -s "$TARGET" shell getprop ro.build.version.release 2>/dev/null || echo "Unknown")
    echo "Device Model: $DEVICE_MODEL"
    echo "Android Version: $ANDROID_VER"
else
    echo "✗ Failed to connect. Check TV Wi-Fi connection and USB/Network Debugging in Developer Options."
    exit 1
fi
