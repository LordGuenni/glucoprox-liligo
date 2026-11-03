package de.lordguenni.glucoprox.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import de.lordguenni.glucoprox.service.DontDieService;

public class BootCompletedBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isEnabled = prefs.getBoolean("broadcast_en", true);
        if (isEnabled && DontDieService.hasBluetoothPermissions(context)) {
            Log.i(TAG, "Device booted, starting DontDieService...");
            Intent serviceIntent = new Intent(context, DontDieService.class);
            serviceIntent.setAction(DontDieService.ACTION_START);
            try {
                ContextCompat.startForegroundService(context, serviceIntent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start service on boot", e);
            }
        }
    }
}
