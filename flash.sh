#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIRMWARE_DIR="$DIR/firmware"

# Ensure pio is in PATH
if ! command -v pio &>/dev/null; then
    if [ -f "$HOME/.local/bin/pio" ]; then
        export PATH="$HOME/.local/bin:$PATH"
    elif [ -f "$HOME/.platformio/penv/bin/pio" ]; then
        export PATH="$HOME/.platformio/penv/bin:$PATH"
    elif [ -f "$FIRMWARE_DIR/.venv/bin/pio" ]; then
        export PATH="$FIRMWARE_DIR/.venv/bin:$PATH"
    fi
fi

if ! command -v pio &>/dev/null; then
    echo "Error: PlatformIO (pio) not found in PATH."
    echo "Make sure ~/.local/bin is in your PATH or install platformio via: pip install platformio"
    exit 1
fi

if [ "$1" = "build" ] || [ "$1" = "upload" ] || [ "$1" = "flash" ] || [ "$1" = "monitor" ] || [ "$1" = "all" ]; then
    ACTION="$1"
    PORT="/dev/ttyACM0"
else
    PORT="${1:-/dev/ttyACM0}"
    ACTION="${2:-upload}"
fi

if [ "$ACTION" != "build" ]; then
    if [ ! -e "$PORT" ]; then
        if [ -e "/dev/ttyUSB0" ]; then
            PORT="/dev/ttyUSB0"
        fi
    fi

    echo "=== Target Serial Port: $PORT ==="

    if [ -e "$PORT" ] && [ ! -w "$PORT" ]; then
        echo "Port $PORT is not writable by current user."
        echo "Fixing permissions (sudo chmod 666 $PORT)..."
        sudo chmod 666 "$PORT" || true
    fi
fi

cd "$FIRMWARE_DIR"

case "$ACTION" in
    build)
        echo "Building firmware..."
        pio run
        ;;
    upload|flash)
        echo "Building and flashing ESP32..."
        pio run -t upload --upload-port "$PORT"
        ;;
    monitor)
        echo "Opening serial monitor (Ctrl+C to exit)..."
        pio device monitor -b 115200 -p "$PORT"
        ;;
    all)
        echo "Building and flashing ESP32..."
        pio run -t upload --upload-port "$PORT"
        echo "Opening serial monitor..."
        pio device monitor -b 115200 -p "$PORT"
        ;;
    *)
        echo "Usage: ./flash.sh [PORT] [build|upload|monitor|all]"
        exit 1
        ;;
esac
