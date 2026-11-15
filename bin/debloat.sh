#!/usr/bin/env bash
# SmartTV-Hub - Debloat Script for Sber / Salute TV
# Safely uninstalls telemetry, promo modules, and unused preinstalled apps for user 0.
set -e

echo "========================================"
echo "SmartTV-Hub: Starting System Debloat..."
echo "========================================"

# ==============================================================================
# ВАЖНО: НИКОГДА НЕ УДАЛЯТЬ ШТАТНЫЕ МЕДИА-ПРИЛОЖЕНИЯ:
# - ru.sberdevices.starmarket  (Магазин приложений Сбера — сохранен!)
# - com.vk.tv                  (Штатное ВК Видео для ТВ — отлично работает из коробки)
# - ru.ivi.client / global     (Штатный онлайн-кинотеатр Иви)
# - ru.kinopoisk.tv            (Штатный Кинопоиск)
# - ru.rutube.app              (Штатный Rutube)
# - com.salute.star / ru.sberdevices.voice.* (Голосовой ассистент Салют)
# - ru.sberdevices.tvinputs / com.mediatek.tvinput (Входы HDMI и ТВ-тюнер)
# Примечание: штатный YouTube (ru.sberdevices.startube) удаляется в пользу
# высокопроизводительного TizenTube Cobalt + VOT (io.gh.reisxd.tizentube.cobalt).
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
    "ru.sberdevices.sberplay"
    "ru.sberdevices.subscriptions"
    "ru.sberdevices.parentcontrol"
    "ru.sberdevices.starkidsroom.prod"

    # Group C: Unused video/audio services
    "ru.rt.video.app.tv"
    "tv.lfstrm.smotreshka_stb"
    "ru.more.play"
    "ru.sberdevices.music"

    # Group D: Background cleaner (frees ~41.5 MB RAM)
    "ru.sberdevices.starcleaner"

    # Group E: Slow stock YouTube (replaced by TizenTube Cobalt + VOT)
    "ru.sberdevices.startube"
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
