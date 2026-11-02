package de.heoegbr.bgproxy;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import de.heoegbr.bgproxy.service.DontDieService;
import de.heoegbr.bgproxy.ui.PreferencesFragment;

public class GlucoProxApp extends Application {
    public static final String CHANNEL_ID = "de.heoegbr.bgproxy.notifications";
    private static final String TAG = "GlucoProxApp";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "GlucoProxApp initialized");

        createNotificationChannel();

        // Ensure a valid Device ID exists
        PreferencesFragment.getCleanBroadcastId(this);

        // Start service if enabled
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean isEnabled = prefs.getBoolean("broadcast_en", true);
        if (isEnabled && DontDieService.hasBluetoothPermissions(this)) {
            Intent serviceIntent = new Intent(this, DontDieService.class);
            serviceIntent.setAction(DontDieService.ACTION_START);
            try {
                ContextCompat.startForegroundService(this, serviceIntent);
            } catch (Exception e) {
                Log.w(TAG, "Foreground service start deferred (waiting for user/broadcast)", e);
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "GlucoProx BLE Background Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("Shows current blood glucose and BLE broadcasting state");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
}
