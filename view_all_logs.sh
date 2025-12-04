#!/bin/bash
# Script to view ALL logs for AntiSocialCard-Checker app (more detailed)
# Usage: ./view_all_logs.sh

PACKAGE_NAME="com.antisocial.giftcardchecker"
TAG_FILTER="ScannerActivity"

# Find ADB
if command -v adb &> /dev/null; then
    ADB_CMD="adb"
elif [ -f "$HOME/Android/Sdk/platform-tools/adb" ]; then
    ADB_CMD="$HOME/Android/Sdk/platform-tools/adb"
else
    echo "ERROR: ADB not found!"
    echo "Please install Android SDK platform-tools or add adb to PATH"
    exit 1
fi

echo "Starting detailed logcat for $PACKAGE_NAME..."
echo "Showing all logs from $TAG_FILTER tag"
echo "Using ADB: $ADB_CMD"
echo "Press Ctrl+C to stop"
echo ""

# Check if device is connected
if ! $ADB_CMD devices | grep -q "device$"; then
    echo "ERROR: No Android device/emulator connected!"
    echo "Please connect a device or start an emulator, then run this script again."
    exit 1
fi

# Clear logcat buffer
$ADB_CMD logcat -c

# Start logcat showing all logs from ScannerActivity
$ADB_CMD logcat "$TAG_FILTER:*"

