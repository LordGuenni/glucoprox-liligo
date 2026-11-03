package de.lordguenni.glucoprox.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import de.lordguenni.glucoprox.data.ReadingManager;
import de.lordguenni.glucoprox.service.DontDieService;

public class XdripDataReceiver extends BroadcastReceiver {
    public static final String XDRIP_ACTION_NEW_ESTIMATE = "com.eveningoutpost.dexdrip.BgEstimate";
    private static final String TAG = "XdripDataReceiver";
    private static final String XDRIP_EXTRA_BG_ESTIMATE = "com.eveningoutpost.dexdrip.Extras.BgEstimate";
    private static final String XDRIP_EXTRA_BG_SLOPE_NAME = "com.eveningoutpost.dexdrip.Extras.BgSlopeName";
    private static final String XDRIP_EXTRA_TIMESTAMP = "com.eveningoutpost.dexdrip.Extras.Time";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !XDRIP_ACTION_NEW_ESTIMATE.equalsIgnoreCase(intent.getAction())) {
            return;
        }

        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        double bgEstimate = bundle.getDouble(XDRIP_EXTRA_BG_ESTIMATE, 0.0);
        String slopeName = bundle.getString(XDRIP_EXTRA_BG_SLOPE_NAME, "");
        long timestamp = bundle.getLong(XDRIP_EXTRA_TIMESTAMP, System.currentTimeMillis());

        Log.i(TAG, String.format("Received xDrip reading: %.1f, slope: %s, time: %d", bgEstimate, slopeName, timestamp));

        if (bgEstimate <= 0.0) {
            Log.w(TAG, "Ignored invalid/zero glucose estimate");
            return;
        }

        // Store reading in memory and persistent storage
        ReadingManager.getInstance(context).addReading(bgEstimate, slopeName, timestamp);

        // If broadcast is enabled, notify service to advertise immediately
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isEnabled = prefs.getBoolean("broadcast_en", true);
        if (isEnabled && DontDieService.hasBluetoothPermissions(context)) {
            Intent serviceIntent = new Intent(context, DontDieService.class);
            serviceIntent.setAction(DontDieService.ACTION_UPDATE_ADVERT);
            try {
                ContextCompat.startForegroundService(context, serviceIntent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start/notify DontDieService", e);
            }
        }
    }
}
