#!/usr/bin/env bash
# SmartTV-Hub - Send Files from Mac to TV
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TV_IP="${TV_IP:-192.168.100.5}"
TV_PORT="${TV_PORT:-5555}"
TARGET="${TV_IP}:${TV_PORT}"

if [ -z "$1" ]; then
    echo "========================================="
    echo "  SmartTV-Hub: Send File / Folder to TV  "
    echo "========================================="
    echo "Usage: ./bin/send_to_tv.sh <local_file_or_dir> [remote_destination]"
    echo ""
    echo "Examples:"
    echo "  ./bin/send_to_tv.sh movie.mp4"
    echo "  ./bin/send_to_tv.sh playlist.m3u /sdcard/Playlists/"
    echo "  ./bin/send_to_tv.sh app.apk (will prompt to install or push)"
    echo "  ./bin/send_to_tv.sh ~/Photos /sdcard/Pictures/"
    echo ""
    exit 1
fi

LOCAL_PATH="$1"
REMOTE_PATH="$2"

if [ ! -e "$LOCAL_PATH" ]; then
    echo "Error: Local file or folder '$LOCAL_PATH' does not exist."
    exit 1
fi

# Ensure ADB is connected
STATUS=$(adb devices | grep "$TARGET" | awk '{print $2}' || true)
if [ "$STATUS" != "device" ]; then
    echo "Connecting to TV at $TARGET..."
    adb connect "$TARGET" || true
    sleep 1
    STATUS=$(adb devices | grep "$TARGET" | awk '{print $2}' || true)
fi

if [ "$STATUS" = "unauthorized" ]; then
    echo "---------------------------------------------------------------"
    echo "ВНИМАНИЕ: На экране ТВ появилось всплывающее окно авторизации!"
    echo "Нажмите 'ОК' (и галочку 'Всегда разрешать') на пульте телевизора."
    echo "---------------------------------------------------------------"
    exit 1
fi

if [ "$STATUS" != "device" ]; then
    echo "Error: TV ($TARGET) is not connected or offline."
    echo "Проверьте, включен ли ТВ и активирована ли отладка по сети."
    exit 1
fi

# Auto-detect target folder if not specified
if [ -z "$REMOTE_PATH" ]; then
    case "$LOCAL_PATH" in
        *.m3u|*.m3u8)
            REMOTE_PATH="/sdcard/Playlists/"
            ;;
        *.apk)
            REMOTE_PATH="/sdcard/Download/"
            ;;
        *.mp4|*.mkv|*.avi)
            REMOTE_PATH="/sdcard/Movies/"
            ;;
        *.jpg|*.jpeg|*.png)
            REMOTE_PATH="/sdcard/Pictures/"
            ;;
        *)
            REMOTE_PATH="/sdcard/Download/"
            ;;
    esac
fi

# Ensure remote folder exists
adb -s "$TARGET" shell "mkdir -p '$REMOTE_PATH'"

echo "========================================="
echo "Отправка: $LOCAL_PATH -> $REMOTE_PATH"
echo "========================================="

adb -s "$TARGET" push "$LOCAL_PATH" "$REMOTE_PATH"

# Set permissions so Android apps (IPTV, Players, Galleries) can read the file
adb -s "$TARGET" shell "chmod -R 666 '$REMOTE_PATH' 2>/dev/null || true; chmod 777 '$REMOTE_PATH' 2>/dev/null || true"

echo "✓ Файл успешно передан на ТВ в: $REMOTE_PATH"

# If it's an APK, offer to install
if [[ "$LOCAL_PATH" == *.apk ]]; then
    echo ""
    read -p "Установить этот APK на телевизор прямо сейчас? (y/n): " -n 1 -r
    echo ""
    if [[ $REPLY =~ ^[YyДд]$ ]]; then
        echo "Установка $LOCAL_PATH..."
        adb -s "$TARGET" install -r "$LOCAL_PATH"
        echo "✓ Приложение установлено!"
    fi
fi
