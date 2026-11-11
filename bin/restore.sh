#!/usr/bin/env bash
# SmartTV-Hub - Full Rollback and Recovery Script
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "========================================"
echo "SmartTV-Hub: Starting System Rollback..."
echo "========================================"

if [ -f "$DIR/backup/all.txt" ]; then
    echo "1. Restoring uninstalled system packages..."
    while IFS= read -r pkg; do
        if [ -n "$pkg" ]; then
            echo -n "Restoring $pkg... "
            adb shell cmd package install-existing "$pkg" 2>&1 || true
        fi
    done < "$DIR/backup/all.txt"
fi

if [ -f "$DIR/backup/enabled.txt" ]; then
    echo ""
    echo "2. Re-enabling packages..."
    while IFS= read -r pkg; do
        if [ -n "$pkg" ]; then
            adb shell pm enable "$pkg" 2>&1 || true
        fi
    done < "$DIR/backup/enabled.txt"
fi

if [ -f "$DIR/backup/disabled.txt" ]; then
    echo ""
    echo "3. Restoring originally disabled packages..."
    while IFS= read -r pkg; do
        if [ -n "$pkg" ]; then
            adb shell pm disable-user --user 0 "$pkg" 2>&1 || true
        fi
    done < "$DIR/backup/disabled.txt"
fi

echo ""
echo "4. Restoring system settings..."
for ns in system secure global; do
    if [ -f "$DIR/backup/settings_$ns.txt" ]; then
        echo "Restoring $ns settings..."
        while IFS='=' read -r key val; do
            if [ -n "$key" ]; then
                adb shell settings put "$ns" "$key" "$val" 2>&1 || true
            fi
        done < "$DIR/backup/settings_$ns.txt"
    fi
done

echo ""
echo "✓ Full system rollback complete!"
