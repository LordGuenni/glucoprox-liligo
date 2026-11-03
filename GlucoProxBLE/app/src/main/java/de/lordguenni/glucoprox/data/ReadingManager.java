package de.lordguenni.glucoprox.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ReadingManager {
    private static final String TAG = "ReadingManager";
    private static final String PREF_KEY_HISTORY = "bg_history_json";
    private static final int MAX_HISTORY_SIZE = 16;

    private static ReadingManager sInstance;
    private final SharedPreferences mPrefs;
    private final List<Reading> mHistory = new ArrayList<>();
    private OnNewReadingListener mListener;

    public interface OnNewReadingListener {
        void onNewReading(Reading reading);
    }

    public static class Reading {
        public double value;
        public String direction;
        public long timestamp;

        public Reading(double value, String direction, long timestamp) {
            this.value = value;
            this.direction = (direction != null) ? direction : "";
            this.timestamp = timestamp;
        }

        public boolean isMmol() {
            return value <= 25.0;
        }

        public double getMgDlValue() {
            return isMmol() ? (value * 18.0) : value;
        }
    }

    private ReadingManager(Context context) {
        mPrefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        loadHistoryFromPrefs();
    }

    public static synchronized ReadingManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new ReadingManager(context);
        }
        return sInstance;
    }

    public void setListener(OnNewReadingListener listener) {
        mListener = listener;
    }

    public synchronized void addReading(double value, String direction, long timestamp) {
        Reading reading = new Reading(value, direction, timestamp);
        // Add to front of history (newest first)
        mHistory.add(0, reading);
        while (mHistory.size() > MAX_HISTORY_SIZE) {
            mHistory.remove(mHistory.size() - 1);
        }
        saveHistoryToPrefs();

        if (mListener != null) {
            mListener.onNewReading(reading);
        }
    }

    public synchronized void clearHistory() {
        mHistory.clear();
        saveHistoryToPrefs();
        if (mListener != null) {
            mListener.onNewReading(null);
        }
    }

    public synchronized Reading getLatestReading() {
        if (mHistory.isEmpty()) {
            return null;
        }
        return mHistory.get(0);
    }

    public synchronized List<Reading> getHistory() {
        return new ArrayList<>(mHistory);
    }

    /**
     * Builds the 19-byte packet matching the GlucoProxBLE format with optional AES-128 encryption
     * and Night Mode flag:
     * Byte 0: Broadcast ID Low
     * Byte 1: Broadcast ID High
     * Byte 2: Flags & Time offset:
     *         Bit 7: Encryption enabled (0x80)
     *         Bit 6: Night Mode active (0x40)
     *         Bits 0-5: Age in minutes (0-63)
     * Byte 3..18: 16 bytes of compressed (and optionally encrypted) blood glucose data
     */
    public synchronized byte[] buildBlePacket(int broadcastId, boolean encrypt, byte[] aesKey, boolean nightMode) {
        if (mHistory.isEmpty()) {
            return new byte[0];
        }

        Reading latest = mHistory.get(0);
        long offsetMs = Math.max(0, System.currentTimeMillis() - latest.timestamp);
        int offsetMinutes = (int) Math.min(63, Math.round(offsetMs / 60000.0));

        if (nightMode) {
            offsetMinutes |= 0x40; // Set bit 6 to signal Night Mode
        }

        byte[] payload = new byte[16];
        for (int i = 0; i < 16; i++) {
            if (i < mHistory.size()) {
                Reading r = mHistory.get(i);
                payload[i] = compressBgValue(r.getMgDlValue());
            } else {
                payload[i] = 0x00;
            }
        }

        if (encrypt && aesKey != null && aesKey.length == 16) {
            offsetMinutes |= 0x80; // Set bit 7 to signal AES-128 encryption
            payload = encryptAes128(payload, aesKey);
        }

        byte[] packet = new byte[19];
        packet[0] = (byte) (broadcastId & 0xFF);
        packet[1] = (byte) ((broadcastId >> 8) & 0xFF);
        packet[2] = (byte) (offsetMinutes & 0xFF);
        System.arraycopy(payload, 0, packet, 3, 16);

        return packet;
    }

    public synchronized byte[] buildBlePacket(int broadcastId, boolean encrypt, byte[] aesKey) {
        return buildBlePacket(broadcastId, encrypt, aesKey, false);
    }

    public synchronized byte[] buildBlePacket(int broadcastId) {
        return buildBlePacket(broadcastId, false, null);
    }

    public static byte[] encryptAes128(byte[] input16, byte[] key16) {
        try {
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(key16, "AES");
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec);
            return cipher.doFinal(input16);
        } catch (Exception e) {
            Log.e(TAG, "AES-128 encryption error", e);
            return input16;
        }
    }

    /**
     * Compresses a mg/dl value into a single byte matching the ESP32 decompression algorithm:
     * 0 -> Error / No value
     * 1 -> Low (< 30 mg/dl)
     * 2 -> High (> 428 mg/dl)
     * 3..152 -> 30..180 mg/dl (1:1)
     * 153..207 -> 182..290 mg/dl (step 2)
     * 208..254 -> 293..428 mg/dl (step 3)
     */
    public static byte compressBgValue(double mgDlValue) {
        long val = Math.round(mgDlValue);
        if (val <= 0) return 0;
        if (val < 30) return 1;
        if (val < 181) return (byte) (val - 27);
        if (val < 291) return (byte) (153 + Math.round((val - 180) / 2.0));
        if (val < 429) return (byte) (208 + Math.round((val - 290) / 3.0));
        return 2;
    }

    private void loadHistoryFromPrefs() {
        String json = mPrefs.getString(PREF_KEY_HISTORY, null);
        if (json == null || json.isEmpty()) return;
        try {
            JSONArray array = new JSONArray(json);
            mHistory.clear();
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                mHistory.add(new Reading(
                        obj.getDouble("v"),
                        obj.optString("d", ""),
                        obj.getLong("t")
                ));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load history from prefs", e);
        }
    }

    private void saveHistoryToPrefs() {
        try {
            JSONArray array = new JSONArray();
            for (Reading r : mHistory) {
                JSONObject obj = new JSONObject();
                obj.put("v", r.value);
                obj.put("d", r.direction);
                obj.put("t", r.timestamp);
                array.put(obj);
            }
            mPrefs.edit().putString(PREF_KEY_HISTORY, array.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "Failed to save history to prefs", e);
        }
    }
}
