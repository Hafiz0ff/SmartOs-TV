#!/usr/bin/env bash
# ==============================================================================
# SmartOs-TV - Master Turnkey Setup Script
# Fully configures, optimizes, debloats, and equips any Smart TV (Salute TV / Sber)
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${BLUE}======================================================${NC}"
echo -e "${GREEN}          🚀 SmartOs-TV Turnkey Setup Suite          ${NC}"
echo -e "${BLUE}======================================================${NC}"

TV_IP="${1:-}"
if [ -z "$TV_IP" ]; then
    echo -e "${YELLOW}Enter Smart TV IP address (e.g. 192.168.1.50):${NC} "
    read -r TV_IP
fi

if [ -z "$TV_IP" ]; then
    echo -e "${RED}Error: TV IP address is required.${NC}"
    exit 1
fi

TV_PORT="${2:-5555}"
TARGET="${TV_IP}:${TV_PORT}"

echo -e "\n${BLUE}[1/6] Connecting to TV via ADB ($TARGET)...${NC}"
adb disconnect "$TARGET" >/dev/null 2>&1 || true
adb connect "$TARGET"
sleep 2

STATUS=$(adb devices | grep "$TARGET" | awk '{print $2}' || true)
if [ "$STATUS" = "unauthorized" ]; then
    echo -e "${YELLOW}------------------------------------------------------------${NC}"
    echo -e "${YELLOW}⚠️  ВНИМАНИЕ: На экране ТВ появилось окно авторизации ADB!${NC}"
    echo -e "${YELLOW}Нажмите 'ОК' (и галочку 'Всегда разрешать') на пульте ТВ.${NC}"
    echo -e "${YELLOW}------------------------------------------------------------${NC}"
    read -p "Нажмите Enter после подтверждения на экране ТВ..."
    STATUS=$(adb devices | grep "$TARGET" | awk '{print $2}' || true)
fi

if [ "$STATUS" != "device" ]; then
    echo -e "${RED}Error: Failed to connect to $TARGET. Check TV Wi-Fi and Developer Options.${NC}"
    exit 1
fi

MODEL=$(adb -s "$TARGET" shell getprop ro.product.model 2>/dev/null || echo "Unknown")
echo -e "${GREEN}✓ Connected successfully! Device: $MODEL${NC}"

echo -e "\n${BLUE}[2/6] Running Safe Debloat (preserving Voice Assistant, HDMI, & Stock Media Apps)...${NC}"
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
DEBLOAT_LIST=(
    "ru.sberdevices.survey"
    "ru.sberdevices.openbeta"
    "ru.sberdevices.bugreporter"
    "ru.sberdevices.smarthome.promo"
    "ru.sberdevices.CleanupStub"
    "ru.sberdevices.TvFactoryHelper"
    "ru.sberdevices.sberplay"
    "ru.sberdevices.subscriptions"
    "ru.sberdevices.parentcontrol"
    "ru.sberdevices.starkidsroom.prod"
    "ru.rt.video.app.tv"
    "tv.lfstrm.smotreshka_stb"
    "ru.more.play"
    "ru.sberdevices.music"
    "ru.sberdevices.starcleaner"
    "ru.sberdevices.startube"
)

for pkg in "${DEBLOAT_LIST[@]}"; do
    echo -n "  Disabling $pkg... "
    adb -s "$TARGET" shell pm uninstall --user 0 "$pkg" >/dev/null 2>&1 || true
    echo "done"
done

# Disable heavy legacy launchers if present
adb -s "$TARGET" shell pm disable-user --user 0 com.oversea.aslauncher >/dev/null 2>&1 || true
adb -s "$TARGET" shell pm disable-user --user 0 ru.sberdevices.starlauncher >/dev/null 2>&1 || true
echo -e "${GREEN}✓ Safe debloat complete! RAM freed.${NC}"

echo -e "\n${BLUE}[3/6] Speeding up UI animations (0.5x)...${NC}"
adb -s "$TARGET" shell settings put global window_animation_scale 0.5
adb -s "$TARGET" shell settings put global transition_animation_scale 0.5
adb -s "$TARGET" shell settings put global animator_duration_scale 0.5
echo -e "${GREEN}✓ Animation scales set to 0.5x for instant response.${NC}"

echo -e "\n${BLUE}[4/6] Installing Essential Apps...${NC}"
APKS_DIR="$ROOT_DIR/apks"
if [ -d "$APKS_DIR" ]; then
    # Ensure TizenTube Cobalt + VOT (with custom YouTube TV banner) is ready
    if [ ! -f "$APKS_DIR/TizenTube-Cobalt-Custom.apk" ]; then
        if [ ! -f "$APKS_DIR/TizenTube-Cobalt-VOT.apk" ]; then
            echo -e "  Fetching ${YELLOW}TizenTube Cobalt + VOT${NC} from GitHub releases..."
            curl -sL "https://github.com/DrA1ex/TizenTubeCobalt_VOT/releases/download/v8.6/TizenTubeCobalt_VOT_arm7.apk" -o "$APKS_DIR/TizenTube-Cobalt-VOT.apk" || true
        fi
        if [ -f "$APKS_DIR/TizenTube-Cobalt-VOT.apk" ] && [ -f "$ROOT_DIR/tools/patch_apk_banner.py" ]; then
            echo -e "  Applying custom YouTube Leanback banner..."
            python3 "$ROOT_DIR/tools/patch_apk_banner.py" --app yt || cp "$APKS_DIR/TizenTube-Cobalt-VOT.apk" "$APKS_DIR/TizenTube-Cobalt-Custom.apk"
        fi
    fi

    # Explicit target APKs
    TARGET_APKS=(
        "SmartOs-Launcher-v3.3.apk"
        "HDRezka-v0.4.2-release.apk"
        "IPTV-Pro-10.0.1-armv7.apk"
        "VK-VideoApp-Custom.apk"
        "TizenTube-Cobalt-Custom.apk"
    )

    for apk_name in "${TARGET_APKS[@]}"; do
        apk="$APKS_DIR/$apk_name"
        if [ -f "$apk" ]; then
            echo -e "  Installing ${YELLOW}$apk_name${NC}..."
            adb -s "$TARGET" install -r -d -g "$apk" >/dev/null 2>&1 || echo "  (warning: install status check)"
        fi
    done
    # Set SmartOs-TV as default launcher
    adb -s "$TARGET" shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity >/dev/null 2>&1 || true
    echo -e "${GREEN}✓ Apps installed and SmartOs Launcher set as default home.${NC}"
else
    echo -e "${YELLOW}No apks directory found, skipping.${NC}"
fi

echo -e "\n${BLUE}[5/6] Pushing Unified IPTV Playlist...${NC}"
PLAYLISTS_DIR="$ROOT_DIR/playlists"
if [ -d "$PLAYLISTS_DIR" ]; then
    adb -s "$TARGET" shell mkdir -p /sdcard/Playlists/
    if [ -f "$PLAYLISTS_DIR/00_Единый_Плейлист_Все_Каналы.m3u" ]; then
        adb -s "$TARGET" push "$PLAYLISTS_DIR/00_Единый_Плейлист_Все_Каналы.m3u" /sdcard/Playlists/ >/dev/null 2>&1 || true
        echo -e "${GREEN}✓ 00_Единый_Плейлист_Все_Каналы.m3u pushed to /sdcard/Playlists/${NC}"
    fi
fi

echo -e "\n${BLUE}[6/6] Applying Salute Glass Theme & Config...${NC}"
THEME_DIR="$ROOT_DIR/cleanyourtv/themes/salute-tv"
THEME_DEST="/sdcard/Android/data/com.gathin.tvhome/files"
if [ -d "$THEME_DIR" ]; then
    adb -s "$TARGET" shell mkdir -p "$THEME_DEST" >/dev/null 2>&1 || true
    if [ -f "$THEME_DIR/config.json" ]; then
        adb -s "$TARGET" push "$THEME_DIR/config.json" "$THEME_DEST/" >/dev/null 2>&1 || true
    fi
    if [ -d "$THEME_DIR/wallpapers" ]; then
        adb -s "$TARGET" push "$THEME_DIR/wallpapers" "$THEME_DEST/" >/dev/null 2>&1 || true
    fi
    # Restart home cleanly
    adb -s "$TARGET" shell am force-stop com.gathin.tvhome >/dev/null 2>&1 || true
    adb -s "$TARGET" shell monkey -p com.gathin.tvhome -c android.intent.category.HOME 1 >/dev/null 2>&1 || true
    echo -e "${GREEN}✓ Salute Glass Theme applied and Launcher restarted.${NC}"
fi

echo -e "\n${GREEN}======================================================${NC}"
echo -e "${GREEN}   🎉 Setup Complete! Your TV is ready to enjoy.      ${NC}"
echo -e "${GREEN}======================================================${NC}"
echo -e "Launcher: ${YELLOW}SmartOs Launcher v3.3 (Tahoe Glass, Prayer, Weather)${NC}"
echo -e "Control:  ${YELLOW}./bin/remote.sh [command]${NC}"
echo -e "Inputs:   ${YELLOW}./bin/remote.sh hdmi 1${NC} to switch to HDMI 1"
