package de.lordguenni.glucoprox.util;

import java.util.Calendar;

public class SunCalculator {

    public static class SunTimes {
        public final int sunriseMinuteOfDay; // 0..1439 in local time
        public final int sunsetMinuteOfDay;  // 0..1439 in local time
        public final boolean isPolarNight;
        public final boolean isMidnightSun;

        public SunTimes(int sunriseMinuteOfDay, int sunsetMinuteOfDay, boolean isPolarNight, boolean isMidnightSun) {
            this.sunriseMinuteOfDay = sunriseMinuteOfDay;
            this.sunsetMinuteOfDay = sunsetMinuteOfDay;
            this.isPolarNight = isPolarNight;
            this.isMidnightSun = isMidnightSun;
        }

        public boolean isNight(int currentMinuteOfDay) {
            if (isPolarNight) return true;
            if (isMidnightSun) return false;
            if (sunriseMinuteOfDay <= sunsetMinuteOfDay) {
                return currentMinuteOfDay < sunriseMinuteOfDay || currentMinuteOfDay >= sunsetMinuteOfDay;
            } else {
                return currentMinuteOfDay < sunriseMinuteOfDay && currentMinuteOfDay >= sunsetMinuteOfDay;
            }
        }
    }

    private static final double ZENITH_OFFICIAL = 90.833;

    public static SunTimes calculate(Calendar cal, double latitude, double longitude) {
        int dayOfYear = cal.get(Calendar.DAY_OF_YEAR);
        double tzOffsetHours = (cal.get(Calendar.ZONE_OFFSET) + cal.get(Calendar.DST_OFFSET)) / 3600000.0;

        Double sunriseUtc = calcTime(dayOfYear, latitude, longitude, true);
        Double sunsetUtc = calcTime(dayOfYear, latitude, longitude, false);

        if (sunriseUtc == null || sunsetUtc == null) {
            double noonDeclination = calcDeclination(dayOfYear + (12.0 - longitude / 15.0) / 24.0);
            double noonElevation = 90.0 - Math.abs(latitude - noonDeclination);
            if (noonElevation < 0) {
                return new SunTimes(-1, -1, true, false);
            } else {
                return new SunTimes(-1, -1, false, true);
            }
        }

        double sunriseLocal = (sunriseUtc + tzOffsetHours + 24.0) % 24.0;
        double sunsetLocal = (sunsetUtc + tzOffsetHours + 24.0) % 24.0;

        int riseMin = (int) Math.round(sunriseLocal * 60.0) % 1440;
        int setMin = (int) Math.round(sunsetLocal * 60.0) % 1440;

        return new SunTimes(riseMin, setMin, false, false);
    }

    private static Double calcTime(int dayOfYear, double lat, double lng, boolean isSunrise) {
        double t = dayOfYear + ((isSunrise ? 6.0 : 18.0) - lng / 15.0) / 24.0;
        double M = (0.9856 * t) - 3.289;
        double L = normalizeDeg(M + (1.916 * sinDeg(M)) + (0.020 * sinDeg(2 * M)) + 282.634);

        double RA = normalizeDeg(atanDeg(0.91764 * tanDeg(L)));
        double Lquadrant = Math.floor(L / 90.0) * 90.0;
        double RAquadrant = Math.floor(RA / 90.0) * 90.0;
        RA = (RA + (Lquadrant - RAquadrant)) / 15.0;

        double sinDec = 0.39782 * sinDeg(L);
        double cosDec = cosDeg(asinDeg(sinDec));

        double cosH = (cosDeg(ZENITH_OFFICIAL) - (sinDec * sinDeg(lat))) / (cosDec * cosDeg(lat));
        if (cosH > 1.0 || cosH < -1.0) {
            return null;
        }

        double H = (isSunrise ? (360.0 - acosDeg(cosH)) : acosDeg(cosH)) / 15.0;
        double T = H + RA - (0.06571 * t) - 6.622;
        double UT = (T - lng / 15.0) % 24.0;
        if (UT < 0) UT += 24.0;
        return UT;
    }

    private static double calcDeclination(double t) {
        double M = (0.9856 * t) - 3.289;
        double L = normalizeDeg(M + (1.916 * sinDeg(M)) + (0.020 * sinDeg(2 * M)) + 282.634);
        double sinDec = 0.39782 * sinDeg(L);
        return asinDeg(sinDec);
    }

    private static double sinDeg(double deg) { return Math.sin(Math.toRadians(deg)); }
    private static double cosDeg(double deg) { return Math.cos(Math.toRadians(deg)); }
    private static double tanDeg(double deg) { return Math.tan(Math.toRadians(deg)); }
    private static double asinDeg(double val) { return Math.toDegrees(Math.asin(val)); }
    private static double acosDeg(double val) { return Math.toDegrees(Math.acos(val)); }
    private static double atanDeg(double val) { return Math.toDegrees(Math.atan(val)); }
    private static double normalizeDeg(double deg) {
        double d = deg % 360.0;
        return d < 0 ? d + 360.0 : d;
    }
}
