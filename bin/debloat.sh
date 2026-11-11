#!/usr/bin/env bash
# SmartTV-Hub - Debloat Script for Sber / Salute TV
# Safely uninstalls telemetry, promo modules, and unused preinstalled apps for user 0.
set -e

echo "========================================"
echo "SmartTV-Hub: Starting System Debloat..."
echo "========================================"

# ==============================================================================
# ВАЖНО: НИКОГДА НЕ УДАЛЯТЬ ШТАТНЫЕ МЕДИА-ПРИЛОЖЕНИЯ:
# - com.vk.tv                  (Штатное ВК Видео для ТВ — отлично работает из коробки)
# - ru.ivi.client.global       (Штатный онлайн-кинотеатр Иви)
# - io.gh.reisxd.tizentube.cobalt / ru.sberdevices.startube (YouTube Leanback)
# - com.salute.star / ru.sberdevices.voice.* (Голосовой ассистент Салют)
# - ru.sberdevices.tvinputs / com.mediatek.tvinput (Входы HDMI и ТВ-тюнер)
# ==============================================================================

# List of safe-to-remove packages
DEBLOAT_LIST=(
    # Group A: Telemetry, feedback and promo
    "ru.sberdevices.survey"
    "ru.sberdevices.openbeta"
    "ru.sberdevices.bugreporter"
    "ru.sberdevices.smarthome.promo"
    "ru.sberdevices.CleanupStub"
    "ru.sberdevices.TvFactoryHelper"

    # Group B: Unused Sber bloatware
    "com.zvuk.tvsalute"
    "ru.sberdevices.sberplay"
    "ru.sberdevices.subscriptions"
    "ru.sberdevices.parentcontrol"
    "ru.sberdevices.starmarket"
    "ru.sberdevices.starkidsroom.prod"
)

echo "Uninstalling bloatware for user 0..."
for pkg in "${DEBLOAT_LIST[@]}"; do
    echo -n "Removing $pkg... "
    res=$(adb shell pm uninstall --user 0 "$pkg" 2>&1 || true)
    echo "$res"
done

# Ensure old launchers are disabled
echo ""
echo "Disabling legacy heavy launchers..."
adb shell pm disable-user --user 0 com.oversea.aslauncher 2>/dev/null || true
adb shell pm disable-user --user 0 ru.sberdevices.starlauncher 2>/dev/null || true

# Set CleanYourTV (com.gathin.tvhome) as default launcher
echo "Setting CleanYourTV as default launcher..."
adb shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity 2>/dev/null || true

# Apply StarOS anti-rotation tweak and screensaver
echo "Applying StarOS optimization tweaks..."
adb shell settings put global sys_storage_threshold_percentage 1 2>/dev/null || true
adb shell settings put secure screensaver_components com.gathin.tvhome/.ClockDream 2>/dev/null || true

# Optionally disable old tvhome launcher if present
adb shell pm disable-user --user 0 com.gio.tvhome 2>/dev/null || true

echo ""
echo "✓ Debloat completed successfully!"
