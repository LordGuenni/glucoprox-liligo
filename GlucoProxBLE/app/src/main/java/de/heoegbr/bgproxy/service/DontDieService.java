package de.heoegbr.bgproxy.service;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.heoegbr.bgproxy.GlucoProxApp;
import de.heoegbr.bgproxy.R;
import de.heoegbr.bgproxy.data.ReadingManager;
import de.heoegbr.bgproxy.ui.MainActivity;
import de.heoegbr.bgproxy.ui.PreferencesFragment;

public class DontDieService extends Service {
    public static final String ACTION_START = "de.heoegbr.bgproxy.action.START";
    public static final String ACTION_STOP = "de.heoegbr.bgproxy.action.STOP";
    public static final String ACTION_UPDATE_ADVERT = "de.heoegbr.bgproxy.action.UPDATE_ADVERT";

    private static final String TAG = "DontDieService";
    private static final int NOTIFICATION_ID = 101;
    private static final ParcelUuid GENERIC_SERVICE = ParcelUuid.fromString("00001801-0000-1000-8000-00805f9b34fb");

    private BluetoothLeAdvertiser mAdvertiser;
    private boolean mIsAdvertising = false;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final Runnable mStopAdvertisingRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (DontDieService.this) {
                if (mAdvertiser != null && mIsAdvertising) {
                    try {
                        mAdvertiser.stopAdvertising(mAdvertiseCallback);
                    } catch (Exception ignored) {}
                    mIsAdvertising = false;
                    Log.i(TAG, "BLE advertising burst completed, now in standby");
                }
                updateNotification();
            }
        }
    };

    private final Runnable mAgeRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (DontDieService.this) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(DontDieService.this);
                boolean enabled = prefs.getBoolean("broadcast_en", true);
                boolean autoRefresh = prefs.getBoolean("auto_refresh_age", true);
                int durationSec = parsePrefInt(prefs, "broadcast_duration", 0);

                if (enabled && autoRefresh && durationSec == 0 && mIsAdvertising) {
                    Log.d(TAG, "Periodic age refresh: updating advertisement packet");
                    updateAdvertising();
                }
            }
        }
    };

    private final AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i(TAG, "BLE Advertisement successfully started");
            mIsAdvertising = true;
            updateNotification();
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.e(TAG, "BLE Advertisement failed with error code: " + errorCode);
            mIsAdvertising = false;
            updateNotification();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "DontDieService onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = (intent != null) ? intent.getAction() : ACTION_START;

        if (ACTION_STOP.equals(action)) {
            stopAdvertising();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // Start foreground safely
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Exception e) {
            Log.e(TAG, "Failed to startForeground", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        // Update BLE Advertising
        updateAdvertising();

        return START_STICKY;
    }

    public synchronized void updateAdvertising() {
        // Cancel any pending timers
        mHandler.removeCallbacks(mStopAdvertisingRunnable);
        mHandler.removeCallbacks(mAgeRefreshRunnable);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean enabled = prefs.getBoolean("broadcast_en", true);
        if (!enabled) {
            stopAdvertising();
            return;
        }

        if (!hasBluetoothPermissions(this)) {
            Log.w(TAG, "Cannot advertise: Bluetooth permissions missing");
            return;
        }

        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager == null) return;
        BluetoothAdapter adapter = manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "Cannot advertise: Bluetooth adapter disabled");
            return;
        }

        mAdvertiser = adapter.getBluetoothLeAdvertiser();
        if (mAdvertiser == null) {
            Log.w(TAG, "Cannot advertise: Device does not support BLE advertising");
            return;
        }

        int broadcastId = PreferencesFragment.getCleanBroadcastId(this);
        boolean aesEnabled = PreferencesFragment.isAesEnabled(this);
        byte[] aesKey = PreferencesFragment.getCleanAesKey(this);
        boolean nightMode = de.heoegbr.bgproxy.util.NightModeHelper.isNightMode(this);
        byte[] packet = ReadingManager.getInstance(this).buildBlePacket(broadcastId, aesEnabled, aesKey, nightMode);
        if (packet.length == 0) {
            Log.i(TAG, "No readings available yet to advertise");
            return;
        }

        // Parse configurable advertising settings
        int advMode = parsePrefInt(prefs, "adv_mode", AdvertiseSettings.ADVERTISE_MODE_BALANCED);
        int txPower = parsePrefInt(prefs, "adv_tx_power", AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM);
        int durationSec = parsePrefInt(prefs, "broadcast_duration", 0);
        boolean autoRefreshAge = prefs.getBoolean("auto_refresh_age", true);

        // Stop any ongoing advertising before restarting with new data
        try {
            mAdvertiser.stopAdvertising(mAdvertiseCallback);
        } catch (Exception ignored) {}

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(advMode)
                .setConnectable(false)
                .setTimeout(0) // Timed via Handler for flexibility
                .setTxPowerLevel(txPower)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(GENERIC_SERVICE)
                .addServiceData(GENERIC_SERVICE, packet)
                .build();

        try {
            mAdvertiser.startAdvertising(settings, data, mAdvertiseCallback);
            Log.i(TAG, String.format(Locale.US,
                    "Started BLE advertisement (ID: 0x%04X, Mode: %d, TxPower: %d, Duration: %ds)",
                    broadcastId, advMode, txPower, durationSec));
        } catch (Exception e) {
            Log.e(TAG, "Exception starting BLE advertisement", e);
        }

        // Schedule burst stop or periodic minute refresh
        if (durationSec > 0) {
            mHandler.postDelayed(mStopAdvertisingRunnable, durationSec * 1000L);
        } else if (autoRefreshAge) {
            mHandler.postDelayed(mAgeRefreshRunnable, 60000L);
        }

        updateNotification();
    }

    private synchronized void stopAdvertising() {
        mHandler.removeCallbacks(mStopAdvertisingRunnable);
        mHandler.removeCallbacks(mAgeRefreshRunnable);

        if (mAdvertiser != null && mIsAdvertising) {
            try {
                mAdvertiser.stopAdvertising(mAdvertiseCallback);
                mIsAdvertising = false;
                Log.i(TAG, "BLE advertising stopped");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping advertising", e);
            }
        }
        updateNotification();
    }

    private void updateNotification() {
        try {
            Notification notification = buildNotification();
            startForeground(NOTIFICATION_ID, notification);
        } catch (Exception ignored) {}
    }

    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean enabled = prefs.getBoolean("broadcast_en", true);
        int broadcastId = PreferencesFragment.getCleanBroadcastId(this);

        ReadingManager.Reading reading = ReadingManager.getInstance(this).getLatestReading();
        String title;
        String contentText;

        boolean isNight = de.heoegbr.bgproxy.util.NightModeHelper.isNightMode(this);
        String nightTag = isNight ? " [Night Mode]" : "";

        if (!enabled) {
            title = "GlucoProx BLE (Paused)";
            contentText = "Broadcasting is disabled in settings";
        } else if (reading != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
            String timeStr = sdf.format(new Date(reading.timestamp));
            String state = mIsAdvertising ? "Broadcasting" : "Standby";
            title = String.format(Locale.getDefault(), "GlucoProx BLE (%s)%s", state, nightTag);
            contentText = String.format(Locale.getDefault(), "%.0f mg/dl (%s) at %s | ID: 0x%04X",
                    reading.getMgDlValue(), reading.direction, timeStr, broadcastId);
        } else {
            title = "GlucoProx BLE Active" + nightTag;
            contentText = "Waiting for glucose readings from xDrip...";
        }

        return new NotificationCompat.Builder(this, GlucoProxApp.CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(contentText)
                .setContentIntent(pendingIntent)
                .setSmallIcon(R.drawable.ic_service)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    public static boolean hasBluetoothPermissions(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private static int parsePrefInt(SharedPreferences prefs, String key, int defaultValue) {
        String val = prefs.getString(key, null);
        if (val != null) {
            try {
                return Integer.parseInt(val);
            } catch (Exception ignored) {}
        }
        return defaultValue;
    }

    @Override
    public void onDestroy() {
        stopAdvertising();
        super.onDestroy();
        Log.i(TAG, "DontDieService onDestroy");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
