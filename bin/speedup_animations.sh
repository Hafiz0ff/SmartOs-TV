#!/usr/bin/env bash
# SmartTV-Hub - Animation Speed Configuration
set -e

SCALE="${1:-0.5}"

echo "Setting window and transition animation scale to: ${SCALE}x"

adb shell settings put global window_animation_scale "$SCALE"
adb shell settings put global transition_animation_scale "$SCALE"
adb shell settings put global animator_duration_scale "$SCALE"

echo ""
echo "Current animation scales:"
echo -n "window_animation_scale: "
adb shell settings get global window_animation_scale
echo -n "transition_animation_scale: "
adb shell settings get global transition_animation_scale
echo -n "animator_duration_scale: "
adb shell settings get global animator_duration_scale

echo "✓ Animations updated successfully!"
