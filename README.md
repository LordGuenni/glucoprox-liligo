# GlucoProx TTGO T-Display & Android BLE Broadcaster

A complete, offline, low-power blood glucose (CGM) display system. The Android companion app (**GlucoProxBLE**) receives blood glucose broadcasts from CGM applications (such as xDrip+) and broadcasts encrypted readings via Bluetooth Low Energy (BLE) advertisements. The **LilyGO TTGO T-Display (ESP32)** continuously scans for these advertisements and renders current glucose levels, direction arrows, delta, history, and battery status.

---

## Architecture Overview

```
[ CGM Sensor / Transmitter ]
          │
          ▼
   [ xDrip+ App ] (on Android phone)
          │  Android Broadcast (com.eveningoutpost.dexdrip.BgEstimate)
          ▼
 [ GlucoProxBLE App ] (Foreground Service)
          │  BLE Advertising (UUID 0x1801, AES-128 encrypted)
          ▼
 [ LilyGO TTGO T-Display ] (ESP32 ST7789 LCD)
   - Real-time BG reading & trend arrow
   - Reading age & delta
   - Auto Night Mode (dimmed red backlight based on schedule/sunset)
   - 180° display flip toggle
```

---

## Hardware: LilyGO TTGO T-Display (ESP32)

### Pinout & Controls
- **Display:** 1.14" ST7789 IPS LCD (135x240)
- **Top Button (`GPIO 35`):** Toggles **Night Mode** (Auto Schedule -> Force Day -> Force Night).
- **Bottom Button (`GPIO 0`):** Toggles **180° Display Rotation** (flips screen orientation for left/right-side cable routing).
- **Battery Sense (`GPIO 34` / `ADC_PIN`):** Reads onboard battery voltage via resistor divider.
- **Backlight (`GPIO 4`):** Hardware PWM brightness control (100% Day, ~10% Night).

---

## 1. Building & Flashing the ESP32 Firmware

The firmware is located in the `firmware/` directory and is built using **[PlatformIO](https://platformio.org/)**.

### Prerequisites
- USB-C cable (ensure it is a data cable, not power-only).
- **USB-to-UART Drivers:** Most TTGO T-Display boards use either the **CP210x** or **CH9102F** USB-to-UART chip.
  - Windows: Download CP210x from Silicon Labs or CH34x/CH9102 driver if the device shows as unknown in Device Manager.
  - Linux / macOS: Drivers are typically built into modern kernels.

---

### Option A: Linux

#### 1. Install PlatformIO
```bash
pip install platformio
# or using pipx:
pipx install platformio
```

#### 2. Configure USB Permissions (Dialout Group)
If you get `Permission denied` when opening the serial port, add your user to the `dialout` (or `uucp`) group:
```bash
sudo usermod -a -G dialout $USER
# Log out and log back in for changes to take effect
```

#### 3. Flash using the helper script
The repository provides `flash.sh` in the root folder:
```bash
# Automatically detects /dev/ttyACM0 or /dev/ttyUSB0, builds and flashes:
./flash.sh

# Or specify port and action explicitly:
./flash.sh /dev/ttyACM0 upload
./flash.sh /dev/ttyACM0 monitor
./flash.sh /dev/ttyACM0 all       # Flash and open serial monitor
```

#### 4. Or flash directly with PlatformIO CLI:
```bash
cd firmware
pio run -t upload --upload-port /dev/ttyACM0
pio device monitor -b 115200 -p /dev/ttyACM0
```

---

### Option B: Windows

#### Method 1: VS Code + PlatformIO IDE (Recommended for GUI)
1. Install [Visual Studio Code](https://code.visualstudio.com/).
2. Open the **Extensions** view (`Ctrl+Shift+X`), search for **PlatformIO IDE**, and click **Install**.
3. In VS Code, go to **File -> Open Folder...** and open the `firmware` folder.
4. Connect your TTGO T-Display via USB.
5. In the bottom blue status bar of VS Code:
   - Click the **Checkmark (`✓`)** to Build.
   - Click the **Right Arrow (`→`)** to Upload/Flash.
   - Click the **Plug icon** to open the Serial Monitor (115200 baud).

#### Method 2: Command Line (PowerShell / Command Prompt)
1. Install Python 3 (ensure "Add Python to PATH" is checked during installation).
2. Open PowerShell and install PlatformIO:
   ```powershell
   pip install platformio
   ```
3. Check your device's COM port in **Device Manager** (e.g. `COM3`).
4. Build and flash:
   ```powershell
   cd firmware
   pio run -t upload --upload-port COM3
   pio device monitor -b 115200 -p COM3
   ```

---

### Option C: macOS

1. Install PlatformIO CLI via Homebrew or Python:
   ```bash
   brew install platformio
   # or: pip3 install platformio
   ```
2. Plug in the TTGO board and find the device node:
   ```bash
   ls /dev/cu.usb* /dev/cu.wch*
   ```
3. Build and flash:
   ```bash
   cd firmware
   pio run -t upload --upload-port /dev/cu.usbmodem*
   pio device monitor -b 115200
   ```

---

## 2. Configuration (Display <-> Android App)

The ESP32 firmware and Android app must share the same **Device ID** and **AES Key** (if encryption is enabled).

### In `firmware/src/main.cpp`:
```cpp
// Broadcast ID (set to 0 to accept any device, or match Android app, e.g. 0x0003)
static uint16_t searchedID = 0x03;

// AES-128 Pre-Shared Key (16 characters, must match Android app)
static const bool aesEnabled = true;
static const uint8_t aesKey[16] = {'G','l','u','c','o','P','r','o','x','S','e','c','r','e','t','1'};
```

### In the Android App Settings:
- **Enable BLE Broadcast:** ON
- **Broadcast Device ID:** `3` (corresponds to `0x0003`)
- **Enable AES-128 Encryption:** ON
- **AES Encryption Key:** `GlucoProxSecret1`

---

## 3. Building & Installing the Android App (`GlucoProxBLE`)

### Option A: Download Pre-built APK (Easiest)
Download the latest signed release APK from GitHub Releases:
- [GitHub Releases Page](https://github.com/LordGuenni/glucoprox-liligo/releases)
- Sideload `GlucoProxBLE-vX.Y.apk` directly on your Android phone.

---

### Option B: Build from Source via Command Line

Ensure you have **JDK 17 or JDK 21** installed and the Android SDK configured.

#### On Linux / macOS:
```bash
cd GlucoProxBLE
chmod +x gradlew
./gradlew assembleRelease

# The signed release APK will be in:
# app/build/outputs/apk/release/app-release.apk
```

#### On Windows (PowerShell):
```powershell
cd GlucoProxBLE
.\gradlew.bat assembleRelease
```

---

### Option C: Install to Phone via ADB

1. Enable **Developer Options** and **USB Debugging** on your Android phone:
   - Settings -> About Phone -> tap **Build Number** 7 times.
   - Settings -> System -> Developer Options -> turn on **USB Debugging**.
2. Connect your phone via USB cable and allow the USB debugging prompt on the phone screen.
3. Install using ADB:

```bash
# Linux / macOS:
~/Android/Sdk/platform-tools/adb install -r GlucoProxBLE/app/build/outputs/apk/release/app-release.apk

# Windows:
%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe install -r GlucoProxBLE\app\build\outputs\apk\release\app-release.apk
```

---

## 4. Testing & Simulation (Linux Broadcaster)

If you want to test the TTGO T-Display without an active CGM sensor or Android phone, you can run the included Python test broadcaster on Linux (requires Bluetooth Low Energy hardware):

```bash
# Install dependencies:
pip install cryptography dbus-python PyGObject

# Run simulated test broadcast (e.g. 125 mg/dL, Flat trend, for 30s):
./broadcast.sh --bg 125 --slope Flat --duration 30

# Or send a custom night mode packet:
./broadcast.sh --bg 95 --night
```

---

## Troubleshooting

- **ESP32 does not receive data:**
  - Verify Bluetooth permissions on the Android app (`BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`).
  - Verify that the Broadcast Device ID in Android Settings matches `searchedID` in `firmware/src/main.cpp`.
  - Check whether AES Encryption is either enabled on **both** or disabled on **both** with matching keys.
- **Display stays blank on flash:**
  - Verify backlight pin definition (`TFT_BL=4` in `platformio.ini`).
  - Press the RST button on the TTGO board.
- **Port not found on Windows:**
  - Check Device Manager under "Ports (COM & LPT)". Install CP210x or CH9102 drivers if a yellow warning sign appears.
