#!/usr/bin/env bash
# Helper script to launch BLE broadcaster directly or via flatpak-spawn if in sandbox
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$DIR/send_ble_reading.py"

if [ -f "/.flatpak-info" ] || command -v flatpak-spawn &>/dev/null; then
    exec flatpak-spawn --host python3 "$SCRIPT" "$@"
else
    exec python3 "$SCRIPT" "$@"
fi
