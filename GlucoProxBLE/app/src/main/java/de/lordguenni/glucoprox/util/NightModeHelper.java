package de.lordguenni.glucoprox.util;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.Calendar;
import java.util.Locale;

public class NightModeHelper {
    private static final String TAG = "NightModeHelper";

    public static final String PREF_NIGHT_MODE_TYPE = "night_mode_type";
    public static final String PREF_NIGHT_START = "night_mode_start_time";
    public static final String PREF_NIGHT_END = "night_mode_end_time";
    public static final String PREF_NIGHT_LAT = "night_mode_lat";
    public static final String PREF_NIGHT_LON = "night_mode_lon";

    public static final String TYPE_DISABLED = "disabled";
    public static final String TYPE_SCHEDULE = "schedule";
    public static final String TYPE_SUNDOWN = "sundown";

    /**
     * Determines whether Night Mode should currently be active based on user preferences.
     */
    public static boolean isNightMode(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String type = prefs.getString(PREF_NIGHT_MODE_TYPE, TYPE_SCHEDULE);

        if (TYPE_DISABLED.equals(type)) {
            return false;
        }

        Calendar now = Calendar.getInstance();
        int currentMinuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);

        if (TYPE_SUNDOWN.equals(type)) {
            double[] coords = getCoordinates(context, prefs);
            SunCalculator.SunTimes sunTimes = SunCalculator.calculate(now, coords[0], coords[1]);
            return sunTimes.isNight(currentMinuteOfDay);
        } else {
            // Default: TYPE_SCHEDULE
            String startStr = prefs.getString(PREF_NIGHT_START, "22:00");
            String endStr = prefs.getString(PREF_NIGHT_END, "07:00");

            int startMin = parseTimeToMinutes(startStr, 22 * 60);
            int endMin = parseTimeToMinutes(endStr, 7 * 60);

            if (startMin <= endMin) {
                // e.g. 01:00 to 06:00
                return currentMinuteOfDay >= startMin && currentMinuteOfDay < endMin;
            } else {
                // Overnight e.g. 22:00 to 07:00
                return currentMinuteOfDay >= startMin || currentMinuteOfDay < endMin;
            }
        }
    }

    /**
     * Returns a human-friendly string describing the current status and schedule.
     */
    public static String getStatusSummary(Context context) {
        if (context == null) return "Disabled";
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String type = prefs.getString(PREF_NIGHT_MODE_TYPE, TYPE_SCHEDULE);

        if (TYPE_DISABLED.equals(type)) {
            return "Disabled (Always Day mode)";
        }

        boolean active = isNightMode(context);
        String state = active ? "Night (Active)" : "Day (Active)";

        if (TYPE_SUNDOWN.equals(type)) {
            Calendar now = Calendar.getInstance();
            double[] coords = getCoordinates(context, prefs);
            SunCalculator.SunTimes st = SunCalculator.calculate(now, coords[0], coords[1]);
            String rise = formatMinutes(st.sunriseMinuteOfDay);
            String set = formatMinutes(st.sunsetMinuteOfDay);
            return String.format(Locale.getDefault(), "%s: Sunset %s, Sunrise %s", state, set, rise);
        } else {
            String startStr = prefs.getString(PREF_NIGHT_START, "22:00");
            String endStr = prefs.getString(PREF_NIGHT_END, "07:00");
            return String.format(Locale.getDefault(), "%s: %s - %s", state, startStr, endStr);
        }
    }

    public static double[] getCoordinates(Context context, SharedPreferences prefs) {
        double lat = 52.0;
        double lon = 12.0;

        // Try getting real device location if permission is granted
        if (hasLocationPermission(context)) {
            try {
                LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
                if (lm != null) {
                    Location loc = null;
                    if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    }
                    if (loc == null && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    }
                    if (loc != null) {
                        return new double[]{loc.getLatitude(), loc.getLongitude()};
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not fetch device location, using configured coordinates", e);
            }
        }

        // Fallback to user-configured coordinates
        try {
            String latStr = prefs.getString(PREF_NIGHT_LAT, "52.0");
            if (latStr != null && !latStr.isEmpty()) lat = Double.parseDouble(latStr);
        } catch (Exception ignored) {}

        try {
            String lonStr = prefs.getString(PREF_NIGHT_LON, "12.0");
            if (lonStr != null && !lonStr.isEmpty()) lon = Double.parseDouble(lonStr);
        } catch (Exception ignored) {}

        return new double[]{lat, lon};
    }

    public static boolean hasLocationPermission(Context context) {
        if (context == null) return false;
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public static int parseTimeToMinutes(String timeStr, int defaultMinutes) {
        if (timeStr == null || !timeStr.contains(":")) {
            return defaultMinutes;
        }
        try {
            String[] parts = timeStr.trim().split(":");
            int hour = Integer.parseInt(parts[0].trim());
            int min = Integer.parseInt(parts[1].trim());
            if (hour >= 0 && hour <= 23 && min >= 0 && min <= 59) {
                return hour * 60 + min;
            }
        } catch (Exception ignored) {}
        return defaultMinutes;
    }

    private static String formatMinutes(int minutesOfDay) {
        if (minutesOfDay < 0) return "--:--";
        int h = (minutesOfDay / 60) % 24;
        int m = minutesOfDay % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", h, m);
    }
}
