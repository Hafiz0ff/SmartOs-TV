#!/usr/bin/env bash
# SmartTV-Hub - Remote Control CLI
set -e

cmd="${1:-help}"

case "$cmd" in
    home)
        adb shell input keyevent 3
        echo "Sent: HOME (3)"
        ;;
    back)
        adb shell input keyevent 4
        echo "Sent: BACK (4)"
        ;;
    up)
        adb shell input keyevent 19
        echo "Sent: DPAD_UP (19)"
        ;;
    down)
        adb shell input keyevent 20
        echo "Sent: DPAD_DOWN (20)"
        ;;
    left)
        adb shell input keyevent 21
        echo "Sent: DPAD_LEFT (21)"
        ;;
    right)
        adb shell input keyevent 22
        echo "Sent: DPAD_RIGHT (22)"
        ;;
    ok|center|enter)
        adb shell input keyevent 23
        echo "Sent: DPAD_CENTER (23)"
        ;;
    long_ok)
        adb shell input keyevent --longpress 23
        echo "Sent: DPAD_CENTER LONGPRESS (23)"
        ;;
    menu)
        adb shell input keyevent 82
        echo "Sent: MENU (82)"
        ;;
    volup)
        adb shell input keyevent 24
        echo "Sent: VOLUME_UP (24)"
        ;;
    voldown)
        adb shell input keyevent 25
        echo "Sent: VOLUME_DOWN (25)"
        ;;
    mute)
        adb shell input keyevent 164
        echo "Sent: VOLUME_MUTE (164)"
        ;;
    power)
        adb shell input keyevent 26
        echo "Sent: POWER (26)"
        ;;
    sleep)
        adb shell input keyevent 223
        echo "Sent: SLEEP (223)"
        ;;
    wakeup)
        adb shell input keyevent 224
        echo "Sent: WAKEUP (224)"
        ;;
    play_pause)
        adb shell input keyevent 85
        echo "Sent: MEDIA_PLAY_PAUSE (85)"
        ;;
    text)
        shift
        text="$*"
        escaped_text=$(printf '%s' "$text" | sed 's/ /%s/g' | tr -cd '[:alnum:]%_.-')
        if [ -n "$escaped_text" ]; then
            adb shell input text "$escaped_text"
            echo "Sent text: $text"
        else
            echo "Error: Invalid or empty text input"
            exit 1
        fi
        ;;
    hdmi)
        port="${2:-1}"
        if [[ ! "$port" =~ ^[1-4]$ ]]; then
            echo "Error: Invalid HDMI port '$port'. Valid values are 1-4."
            exit 1
        fi
        echo "Switching to HDMI $port input..."
        if ! adb get-state >/dev/null 2>&1; then
            echo "Error: Android TV device not connected via ADB"
            exit 1
        fi
        if adb shell am start -a android.intent.action.VIEW -d "livetv://hdmi?number=$port" >/dev/null 2>&1; then
            echo "Switched to HDMI $port (livetv://hdmi?number=$port)"
        else
            echo "Error: Failed to switch to HDMI $port"
            exit 1
        fi
        ;;
    *)
        echo "SmartTV Remote Control CLI"
        echo "Usage: ./remote.sh <command>"
        echo ""
        echo "Navigation:"
        echo "  home, back, up, down, left, right, ok, long_ok, menu"
        echo "Sound:"
        echo "  volup, voldown, mute"
        echo "Power & Media:"
        echo "  power, sleep, wakeup, play_pause"
        echo "Inputs & Typing:"
        echo "  hdmi [1-3]   - switch to HDMI input (default: 1)"
        echo "  text <str>   - input text into focused field"
        exit 1
        ;;
esac
