#include <Arduino.h>
#include <TFT_eSPI.h>
#include <SPI.h>
#include <Wire.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEScan.h>
#include <BLEAdvertisedDevice.h>

// --- Hardware Pins (TTGO T-Display) ---
#define ADC_EN      14  // Enable ADC detection / power
#define ADC_PIN     34
#define BUTTON_1    35  // Top button
#define BUTTON_2    0   // Bottom button
#define TFT_BL_PIN  4   // Display backlight control pin (PWM)

// --- PWM Backlight Settings ---
#define PWM_CHANNEL      0
#define PWM_FREQ         5000
#define PWM_RESOLUTION   8    // 8-bit duty cycle: 0 - 255
#define BRIGHTNESS_DAY   255  // 100% full brightness
#define BRIGHTNESS_NIGHT 25   // ~10% dimmed for nighttime comfort

TFT_eSPI tft = TFT_eSPI();

#include "mbedtls/aes.h"

// --- GlucoProxBLE BLE Configuration ---
// Service UUID broadcasted by the GlucoProxBLE Android app (UUID 0x1801)
static BLEUUID advServiceUUID("00001801-0000-1000-8000-00805f9b34fb");

// Broadcast ID configured in GlucoProxBLE settings (set to 0 to accept any device ID)
static uint16_t searchedID = 0x03;

// AES-128 Pre-Shared Key (16 bytes, must match the Android app setting)
static const bool aesEnabled = true;
static const uint8_t aesKey[16] = {'G','l','u','c','o','P','r','o','x','S','e','c','r','e','t','1'};

// State variables
volatile bool newDataAvailable = false;
int BZ = 0;
int Delta = 0;
int readingAgeMin = 0;
int displayedAge = -1;
unsigned long lastPacketMillis = 0;

// Night Mode States
bool autoNightMode = false;            // Night mode received from BLE broadcast
bool lastReceivedBleNightMode = false; // Tracks BLE schedule transitions
bool manualOverride = false;           // True if user swapped mode manually via button
bool manualNightMode = false;          // User-selected manual mode
bool currentNightMode = false;         // Effective night mode currently applied

// Button Interrupt & Double-Press Detection
volatile unsigned long lastButtonPressMs = 0;
volatile bool doublePressDetected = false;

void IRAM_ATTR buttonISR() {
  unsigned long now = millis();
  // 50ms debounce
  if (now - lastButtonPressMs > 50) {
    // If second press occurs within 450ms, it's a double press
    if (now - lastButtonPressMs < 450) {
      doublePressDetected = true;
      lastButtonPressMs = 0; // Reset to avoid chaining
    } else {
      lastButtonPressMs = now;
    }
  }
}

// Set display backlight duty cycle (0-255)
void setBacklight(uint8_t duty) {
  ledcWrite(PWM_CHANNEL, duty);
}

// Apply night mode: changes backlight brightness
void applyNightModeBrightness(bool night) {
  if (night) {
    setBacklight(BRIGHTNESS_NIGHT);
  } else {
    setBacklight(BRIGHTNESS_DAY);
  }
}

// --- GlucoProxBLE Lossy Value Decompression ---
// Reconstructs mg/dl value from a single compressed byte
uint16_t decompressBG(uint8_t compressedBG) {
  if (compressedBG == 0) return 0;       // Error / no value
  if (compressedBG == 1) return 25;      // LOW (< 30 mg/dl)
  if (compressedBG == 2) return 420;     // HIGH (> 428 mg/dl)
  if (compressedBG < 152) {
    return compressedBG + 27;            // 30 - 180 mg/dl (1:1 linear)
  } else if (compressedBG < 207) {
    return (compressedBG - 153) * 2 + 180; // 182 - 290 mg/dl (step 2)
  } else {
    return (compressedBG - 208) * 3 + 290; // 293 - 428 mg/dl (step 3)
  }
}

// Forward declaration
void drawScreen();
void updateAgeDisplay(int totalAge);

// --- BLE Sniffer Callback ---
class MyAdvertisedDeviceCallbacks : public BLEAdvertisedDeviceCallbacks {
  void onResult(BLEAdvertisedDevice advertisedDevice) {
    // Check if this advertisement carries the GlucoProxBLE service
    if (!advertisedDevice.isAdvertisingService(advServiceUUID)) {
      return;
    }

    std::string serviceData = advertisedDevice.getServiceData();
    // Packet structure: [DeviceID_Low, DeviceID_High, Flags_TimeOffset, BG_Newest, BG_Previous, ...]
    if (serviceData.length() < 4) {
      return; // Not enough data
    }

    const uint8_t* advData = (const uint8_t*)serviceData.data();
    uint16_t deviceId = advData[0] | (advData[1] << 8);

    // Verify configured Device ID filter
    if (searchedID != 0 && deviceId != searchedID) {
      return;
    }

    // Byte 2 Flags & Age:
    // Bit 7: AES Encryption (0x80)
    // Bit 6: Night Mode active (0x40)
    // Bits 0-5: Age in minutes (0-63)
    uint8_t rawAge = advData[2];
    bool packetEncrypted = (rawAge & 0x80) != 0;
    bool bleNightMode = (rawAge & 0x40) != 0;
    uint8_t newAge = rawAge & 0x3F;

    uint8_t payload[16];
    memset(payload, 0, sizeof(payload));
    size_t payloadLen = serviceData.length() - 3;
    if (payloadLen > 16) payloadLen = 16;
    memcpy(payload, advData + 3, payloadLen);

    // Decrypt 16-byte payload block if packet is encrypted and AES is enabled
    if (packetEncrypted && aesEnabled && payloadLen >= 16) {
      mbedtls_aes_context aes;
      mbedtls_aes_init(&aes);
      mbedtls_aes_setkey_dec(&aes, aesKey, 128);
      uint8_t decrypted[16];
      mbedtls_aes_crypt_ecb(&aes, MBEDTLS_AES_DECRYPT, payload, decrypted);
      mbedtls_aes_free(&aes);
      memcpy(payload, decrypted, 16);
    }

    // Decompress the newest blood glucose value
    uint16_t currentBG = decompressBG(payload[0]);

    // If previous reading is present in the packet, compute delta
    int newDelta = Delta;
    if (payloadLen >= 2) {
      uint16_t prevBG = decompressBG(payload[1]);
      if (prevBG > 0) {
        newDelta = (int)currentBG - (int)prevBG;
      }
    }

    // Handle Auto Night Mode schedule updates:
    // If the phone's schedule state transitioned (e.g. Day -> Night), clear manual override
    if (bleNightMode != lastReceivedBleNightMode) {
      lastReceivedBleNightMode = bleNightMode;
      manualOverride = false; // Reset to follow new schedule
    }
    autoNightMode = bleNightMode;

    bool targetNightMode = manualOverride ? manualNightMode : autoNightMode;
    if (targetNightMode != currentNightMode) {
      currentNightMode = targetNightMode;
      applyNightModeBrightness(currentNightMode);
      newDataAvailable = true;
    }

    if (currentBG != BZ || newDelta != Delta || newAge < readingAgeMin || displayedAge == -1) {
      newDataAvailable = true;
    }

    readingAgeMin = newAge;
    BZ = currentBG;
    Delta = newDelta;
    lastPacketMillis = millis();

    Serial.printf("GlucoProxBLE Packet: DeviceID=0x%04X, BZ=%d, Delta=%d, Age=%d min (Encrypted: %s, NightMode: %s)\n",
                  deviceId, BZ, Delta, readingAgeMin, packetEncrypted ? "YES" : "NO", bleNightMode ? "ON" : "OFF");
  }
};

// --- Display Functions ---
void updateAgeDisplay(int totalAge) {
  if (totalAge >= 7) {
    tft.setTextColor(tft.color565(255, 255, 0)); // Yellow warning if data is stale
  } else if (currentNightMode) {
    tft.setTextColor(tft.color565(255, 0, 0));   // Red in night mode
  } else {
    tft.setTextColor(tft.color565(255, 255, 255)); // White in day mode
  }

  // Clear bottom age strip
  tft.fillRect(0, 95, 240, 32, tft.color565(0, 0, 0));

  char ageStr[16];
  snprintf(ageStr, sizeof(ageStr), "%d min", totalAge);
  int ageWidth = strlen(ageStr) * 18; // Size 3 = 18px per character
  int ageX = (240 - ageWidth) / 2;
  if (ageX < 0) ageX = 0;

  tft.setTextSize(3);
  tft.setCursor(ageX, 98);
  tft.print(ageStr);
  displayedAge = totalAge;
}

void drawScreen() {
  tft.fillScreen(tft.color565(0, 0, 0));

  // Determine text color based on day/night mode
  if (currentNightMode) {
    tft.setTextColor(tft.color565(255, 0, 0));     // Red (night mode)
  } else {
    tft.setTextColor(tft.color565(255, 255, 255)); // White (day mode)
  }

  // Warning when projected out-of-range or out-of-range: <= 70 or >= 180
  if ((BZ + (5 * Delta) <= 70 && BZ > 70) || BZ < 70 || (BZ + (5 * Delta) >= 180 && BZ < 180)) {
    tft.setTextColor(tft.color565(255, 255, 0));
  } 

  // Format Blood Glucose string
  char bzStr[8];
  snprintf(bzStr, sizeof(bzStr), "%d", BZ);
  int bzWidth = strlen(bzStr) * 48; // Size 8 = 48px per character

  // Format Delta string
  char deltaStr[8];
  if (Delta >= 0) {
    snprintf(deltaStr, sizeof(deltaStr), "+%d", Delta);
  } else {
    snprintf(deltaStr, sizeof(deltaStr), "%d", Delta);
  }
  int deltaWidth = strlen(deltaStr) * 24; // Size 4 = 24px per character

  // Calculate horizontally centered positions for BZ + Delta group
  int gap = 8;
  int totalWidth = bzWidth + gap + deltaWidth;
  int startX = (240 - totalWidth) / 2;
  if (startX < 2) startX = 2;

  int bzX = startX;
  int deltaX = bzX + bzWidth + gap;
  int bzY = 20;
  int deltaY = 26;

  // 1. Draw Blood Glucose (Main value, Size 8)
  tft.setTextSize(8);
  tft.setCursor(bzX, bzY);
  tft.print(bzStr);

  // 2. Draw Delta (+/- change, Size 4)
  tft.setTextSize(4);
  tft.setCursor(deltaX, deltaY);
  tft.print(deltaStr);

  // 3. Draw Age in Minutes (Centered)
  int totalAge = (lastPacketMillis > 0) ? (readingAgeMin + (int)((millis() - lastPacketMillis) / 60000)) : 0;
  updateAgeDisplay(totalAge);
}

void setup() {
  Serial.begin(115200);
  Serial.println("Starting TTGO GlucoProxBLE Receiver...");

  // Power on display / ADC on TTGO T-Display
  pinMode(ADC_EN, OUTPUT);
  digitalWrite(ADC_EN, HIGH);

  // Configure Buttons with Pullups and Interrupt for Double-Press Detection
  pinMode(BUTTON_1, INPUT_PULLUP);
  pinMode(BUTTON_2, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(BUTTON_1), buttonISR, FALLING);
  attachInterrupt(digitalPinToInterrupt(BUTTON_2), buttonISR, FALLING);

  // Initialize TFT Display
  tft.init();
  tft.setRotation(3);

  // Initialize PWM Backlight control on pin 4
  ledcSetup(PWM_CHANNEL, PWM_FREQ, PWM_RESOLUTION);
  ledcAttachPin(TFT_BL_PIN, PWM_CHANNEL);
  applyNightModeBrightness(false); // Default to full brightness on boot

  tft.fillScreen(tft.color565(0, 0, 0));
  tft.setTextSize(2);
  tft.setTextColor(tft.color565(255, 255, 255));
  tft.setCursor(30, 58);
  tft.print("BLE Scanning...");

  // Initialize BLE Scanner
  BLEDevice::init("");
  BLEScan* pBLEScan = BLEDevice::getScan();
  pBLEScan->setAdvertisedDeviceCallbacks(new MyAdvertisedDeviceCallbacks());
  pBLEScan->setActiveScan(false); // Passive scanning (saves power, listens silently)
  pBLEScan->setInterval(100);     // 100ms scan interval
  pBLEScan->setWindow(99);        // 99ms scan window (near 100% duty cycle)
}

void loop() {
  // Check if user double-pressed either button to toggle Day / Night mode
  if (doublePressDetected) {
    doublePressDetected = false;
    manualOverride = true;
    manualNightMode = !currentNightMode;
    currentNightMode = manualNightMode;
    applyNightModeBrightness(currentNightMode);
    Serial.printf("Double-press detected! Swapped Night Mode to: %s (Backlight: %d)\n",
                  currentNightMode ? "ON (Red/Dim)" : "OFF (White/Bright)",
                  currentNightMode ? BRIGHTNESS_NIGHT : BRIGHTNESS_DAY);
    if (BZ > 0) {
      drawScreen();
    }
  }

  // Perform a 2-second passive scan block (responsive to button double-press)
  BLEScan* pBLEScan = BLEDevice::getScan();
  pBLEScan->start(2, false);
  pBLEScan->clearResults(); // Clear BLE scan buffer to prevent heap memory growth

  // Redraw full screen when fresh packet arrived or night mode toggled
  if (newDataAvailable) {
    newDataAvailable = false;
    drawScreen();
  } else if (BZ > 0 && lastPacketMillis > 0) {
    // Increment minute counter dynamically if age changed
    int currentAge = readingAgeMin + (int)((millis() - lastPacketMillis) / 60000);
    if (currentAge != displayedAge) {
      updateAgeDisplay(currentAge);
    }
  }
}
