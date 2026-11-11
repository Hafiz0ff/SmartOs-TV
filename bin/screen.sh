#!/usr/bin/env bash
# SmartTV-Hub - Take Screenshot Script
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT="${1:-$DIR/screenshots/current_screen.png}"

echo "Capturing TV screen to $OUTPUT..."
adb shell screencap -p /sdcard/screen_temp.png
adb pull /sdcard/screen_temp.png "$OUTPUT"
adb shell rm -f /sdcard/screen_temp.png

echo "✓ Screenshot saved to: $OUTPUT"
if command -v open >/dev/null 2>&1; then
    open "$OUTPUT"
fi
