#!/usr/bin/env bash
# SmartTV-Hub - App and Memory Management Helper
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cmd="${1:-help}"

case "$cmd" in
    list)
        echo "=== Third-party Apps Installed ==="
        adb shell pm list packages -3 | sed 's/^package://'
        ;;
    running)
        echo "=== Top Memory Consumers (PSS) ==="
        adb shell dumpsys meminfo | head -n 30
        ;;
    memory)
        echo "=== System Memory and Swap ==="
        adb shell free -m
        echo ""
        echo "=== Storage Usage (/data) ==="
        adb shell df -h /data
        ;;
    install)
        shift
        apk_file="$1"
        if [ -z "$apk_file" ]; then
            echo "Usage: ./app_manager.sh install <path/to/app.apk>"
            exit 1
        fi
        echo "Installing $apk_file..."
        adb install -r "$apk_file"
        echo "✓ Installed!"
        ;;
    uninstall)
        shift
        pkg_name="$1"
        if [ -z "$pkg_name" ]; then
            echo "Usage: ./app_manager.sh uninstall <package_name>"
            exit 1
        fi
        echo "Uninstalling $pkg_name..."
        adb shell pm uninstall --user 0 "$pkg_name"
        echo "✓ Uninstalled for user 0!"
        ;;
    launch)
        shift
        pkg_name="$1"
        if [ -z "$pkg_name" ]; then
            echo "Usage: ./app_manager.sh launch <package_name>"
            exit 1
        fi
        echo "Launching $pkg_name..."
        adb shell monkey -p "$pkg_name" -c android.intent.category.LAUNCHER 1 2>/dev/null || \
        adb shell monkey -p "$pkg_name" -c android.intent.category.LEANBACK_LAUNCHER 1
        ;;
    clean_cache)
        echo "Cleaning background app caches..."
        for p in $(adb shell pm list packages -3 | sed 's/^package://'); do
            adb shell pm trim-caches 1000M "$p" 2>/dev/null || true
        done
        echo "✓ Caches trimmed."
        ;;
    *)
        echo "SmartTV-Hub App Manager"
        echo "Usage: ./app_manager.sh <command>"
        echo ""
        echo "Commands:"
        echo "  list         - list all installed third-party apps"
        echo "  running      - show top memory-consuming processes"
        echo "  memory       - show RAM, ZRAM swap, and disk free space"
        echo "  install <apk>- install APK onto TV"
        echo "  uninstall <p>- uninstall package for user 0"
        echo "  launch <pkg> - launch an app by package name"
        echo "  clean_cache  - trim app caches"
        exit 1
        ;;
esac
