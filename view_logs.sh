#!/bin/bash
# Script to view logs for AntiSocialCard-Checker app
# Usage: ./view_logs.sh

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

echo "Starting logcat for $PACKAGE_NAME..."
echo "Filtering by tag: $TAG_FILTER"
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

# Start logcat with filters
# Show logs from our app's ScannerActivity tag
$ADB_CMD logcat -s "$TAG_FILTER:D" | grep --color=always -E "PIN|REWE|Lidl|barcode|region|OCR|Extracted|detected|TYPE_1|left|right|upper"

