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

echo -e "\n${BLUE}[2/6] Running Safe Debloat (preserving Voice Assistant & HDMI)...${NC}"
DEBLOAT_LIST=(
    "ru.sberdevices.survey"
    "ru.sberdevices.openbeta"
    "ru.sberdevices.bugreporter"
    "ru.sberdevices.smarthome.promo"
    "ru.sberdevices.CleanupStub"
    "ru.sberdevices.TvFactoryHelper"
    "com.zvuk.tvsalute"
    "ru.sberdevices.sberplay"
    "ru.sberdevices.subscriptions"
    "ru.sberdevices.parentcontrol"
    "ru.sberdevices.starmarket"
    "ru.sberdevices.starkidsroom.prod"
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
    for apk in "$APKS_DIR"/*.apk; do
        if [ -f "$apk" ]; then
            name=$(basename "$apk")
            echo -e "  Installing ${YELLOW}$name${NC}..."
            adb -s "$TARGET" install -r -d "$apk" >/dev/null 2>&1 || echo "  (warning: install status check)"
        fi
    done
    # Set SmartOs-TV as default launcher
    adb -s "$TARGET" shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity >/dev/null 2>&1 || true
    echo -e "${GREEN}✓ Apps installed and SmartOs Launcher set as default home.${NC}"
else
    echo -e "${YELLOW}No apks directory found, skipping.${NC}"
fi

echo -e "\n${BLUE}[5/6] Pushing Curated IPTV Playlists...${NC}"
PLAYLISTS_DIR="$ROOT_DIR/playlists"
if [ -d "$PLAYLISTS_DIR" ]; then
    adb -s "$TARGET" shell mkdir -p /sdcard/Playlists/
    for m3u in "$PLAYLISTS_DIR"/*.m3u; do
        if [ -f "$m3u" ]; then
            adb -s "$TARGET" push "$m3u" /sdcard/Playlists/ >/dev/null 2>&1 || true
        fi
    done
    echo -e "${GREEN}✓ Playlists pushed to /sdcard/Playlists/${NC}"
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
