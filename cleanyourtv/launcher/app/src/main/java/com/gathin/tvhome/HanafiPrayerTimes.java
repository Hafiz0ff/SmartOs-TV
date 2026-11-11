package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Calculates and manages 5 daily prayer times according to the Hanafi Madhab
 * for Khujand, Tajikistan (Lat: 40.2460, Lon: 69.6489, UTC+5).
 *
 * Provides:
 * - High-precision offline NOAA astronomical solar calculation (Hanafi 2x shadow Asr, Fajr 18°, Isha 17°).
 * - Async sync with AlAdhan API with local SharedPreferences caching.
 * - Real-time countdown to the next prayer, updating every minute.
 * - Automatic update after 23:59 (at 00:00 midnight) for the new day.
 */
public class HanafiPrayerTimes {
    private static final String TAG = "HanafiPrayer";
    private static final String PREFS_NAME = "hanafi_prayer_prefs";
    private static final double KHUJAND_LAT = 40.2460;
    private static final double KHUJAND_LON = 69.6489;
    private static final double TIMEZONE_OFFSET = 5.0; // UTC+5

    public static final String[] PRAYER_NAMES = {"ФАДЖР", "ЗУХР", "АСР", "МАГРИБ", "ИША"};
    public static final String[] NEXT_TITLES = {"ДО ФАДЖРА", "ДО ЗУХРА", "ДО АСРА", "ДО МАГРИБА", "ДО ИША"};

    public interface Listener {
        void onPrayerUpdated(PrayerData data);
    }

    public static class PrayerData {
        public final String[] times; // [Fajr, Dhuhr, Asr, Maghrib, Isha]
        public final int nextIndex;  // 0..4
        public final String nextTitle;
        public final String countdown;

        public PrayerData(String[] times, int nextIndex, String nextTitle, String countdown) {
            this.times = times;
            this.nextIndex = nextIndex;
            this.nextTitle = nextTitle;
            this.countdown = countdown;
        }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Listener listener;
    private boolean running = false;
    private int lastDayOfYear = -1;

    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            refresh();
            // Schedule at the start of next minute with 250ms margin
            long now = System.currentTimeMillis();
            long delay = 60_000L - (now % 60_000L) + 250L;
            handler.postDelayed(this, delay);
        }
    };

    public HanafiPrayerTimes(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void start() {
        if (running) {
            refresh();
            return;
        }
        running = true;
        refresh();
        long now = System.currentTimeMillis();
        long delay = 60_000L - (now % 60_000L) + 250L;
        handler.removeCallbacks(minuteTick);
        handler.postDelayed(minuteTick, delay);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(minuteTick);
    }

    /**
     * Refreshes the prayer data for current minute and notifies listener.
     */
    public void refresh() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Dushanbe"));
        int currentDay = cal.get(Calendar.DAY_OF_YEAR);
        if (currentDay != lastDayOfYear) {
            lastDayOfYear = currentDay;
            fetchOnline(cal.getTime());
        }

        PrayerData data = computePrayerData(cal);
        if (listener != null) {
            listener.onPrayerUpdated(data);
        }
    }

    /**
     * Returns the prayer times for a given day.
     * Checks SharedPreferences cache first, falls back to NOAA calculation.
     */
    public String[] getPrayerTimes(Calendar cal) {
        String dateKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
        String cached = prefs.getString("times_" + dateKey, null);
        if (cached != null) {
            String[] parts = cached.split(",");
            if (parts.length == 5) {
                return parts;
            }
        }
        // Fallback to offline astronomical calculation
        return calculateNoaaHanafi(cal);
    }

    private PrayerData computePrayerData(Calendar cal) {
        String[] todayTimes = getPrayerTimes(cal);

        // Convert times to minutes from midnight
        int[] minutes = new int[5];
        for (int i = 0; i < 5; i++) {
            minutes[i] = parseMinutes(todayTimes[i]);
        }

        int currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);

        int nextIndex = -1;
        int diffMinutes = 0;

        for (int i = 0; i < 5; i++) {
            if (currentMinutes < minutes[i]) {
                nextIndex = i;
                diffMinutes = minutes[i] - currentMinutes;
                break;
            }
        }

        if (nextIndex == -1) {
            // All prayers for today have passed; next is tomorrow's Fajr
            nextIndex = 0;
            Calendar tomorrow = (Calendar) cal.clone();
            tomorrow.add(Calendar.DAY_OF_YEAR, 1);
            String[] tomorrowTimes = getPrayerTimes(tomorrow);
            int tomorrowFajrMinutes = parseMinutes(tomorrowTimes[0]);
            int minutesUntilMidnight = 1440 - currentMinutes;
            diffMinutes = minutesUntilMidnight + tomorrowFajrMinutes;
        }

        boolean showCountdown = prefs.getBoolean("show_countdown", true);
        String nextTitle = showCountdown ? NEXT_TITLES[nextIndex] : "";
        String countdown = showCountdown ? formatCountdown(diffMinutes) : "";

        return new PrayerData(todayTimes, nextIndex, nextTitle, countdown);
    }

    private static int parseMinutes(String timeStr) {
        try {
            String[] p = timeStr.trim().split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String formatCountdown(int totalMinutes) {
        if (totalMinutes <= 0) {
            return "сейчас";
        }
        int hours = totalMinutes / 60;
        int mins = totalMinutes % 60;
        if (hours > 0) {
            if (mins > 0) {
                return hours + " ч " + mins + " мин";
            } else {
                return hours + " ч";
            }
        } else {
            return mins + " мин";
        }
    }

    /**
     * NOAA Solar Ephemeris Calculation adapted for Hanafi Prayer Times.
     * Fajr: 18.0°, Dhuhr: solar noon, Asr: Hanafi 2x shadow, Maghrib: sunset, Isha: 17.0°.
     */
    public String[] calculateNoaaHanafi(Calendar cal) {
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);

        if (month <= 2) {
            year -= 1;
            month += 12;
        }
        double A = Math.floor(year / 100.0);
        double B = 2 - A + Math.floor(A / 4.0);
        double jd = Math.floor(365.25 * (year + 4716)) + Math.floor(30.6001 * (month + 1)) + day + B - 1524.5;

        double T = (jd - 2451545.0) / 36525.0;
        double geomMeanLongSun = (280.46646 + T * (36000.76983 + 0.0003032 * T)) % 360.0;
        double geomMeanAnomSun = 357.52911 + T * (35999.05029 - 0.0001537 * T);
        double eccentEarthOrbit = 0.016708634 - T * (0.000042037 + 0.0000001267 * T);
        double sunEqOfCtr = Math.sin(Math.toRadians(geomMeanAnomSun)) * (1.914602 - T * (0.004817 + 0.000014 * T))
                + Math.sin(Math.toRadians(2 * geomMeanAnomSun)) * (0.019993 - 0.000101 * T)
                + Math.sin(Math.toRadians(3 * geomMeanAnomSun)) * 0.000289;
        double sunTrueLong = geomMeanLongSun + sunEqOfCtr;
        double sunAppLong = sunTrueLong - 0.00569 - 0.00478 * Math.sin(Math.toRadians(125.04 - 1934.136 * T));
        double meanObliqEcliptic = 23.0 + (26.0 + ((21.448 - T * (46.815 + T * (0.00059 - T * 0.001813)))) / 60.0) / 60.0;
        double obliqCorr = meanObliqEcliptic + 0.00256 * Math.cos(Math.toRadians(125.04 - 1934.136 * T));
        double sunDecline = Math.toDegrees(Math.asin(Math.sin(Math.toRadians(obliqCorr)) * Math.sin(Math.toRadians(sunAppLong))));

        double varY = Math.tan(Math.toRadians(obliqCorr / 2.0));
        varY *= varY;
        double eqOfTime = 4.0 * Math.toDegrees(varY * Math.sin(2.0 * Math.toRadians(geomMeanLongSun))
                - 2.0 * eccentEarthOrbit * Math.sin(Math.toRadians(geomMeanAnomSun))
                + 4.0 * eccentEarthOrbit * varY * Math.sin(Math.toRadians(geomMeanAnomSun)) * Math.cos(2.0 * Math.toRadians(geomMeanLongSun))
                - 0.5 * varY * varY * Math.sin(4.0 * Math.toRadians(geomMeanLongSun))
                - 1.25 * eccentEarthOrbit * eccentEarthOrbit * Math.sin(2.0 * Math.toRadians(geomMeanAnomSun)));

        double solarNoon = (720.0 - 4.0 * KHUJAND_LON - eqOfTime + TIMEZONE_OFFSET * 60.0) / 60.0;

        double phi = Math.toRadians(KHUJAND_LAT);
        double delta = Math.toRadians(sunDecline);

        double fajrHourAngle = hourAngle(-18.0, phi, delta);
        double sunriseHourAngle = hourAngle(-0.833, phi, delta);
        boolean isShafii = "shafii".equalsIgnoreCase(prefs.getString("madhab", "hanafi"));
        double shadowFactor = isShafii ? 1.0 : 2.0;
        double aAsr = Math.toDegrees(Math.atan(1.0 / (shadowFactor + Math.tan(Math.abs(phi - delta)))));
        double asrHourAngle = hourAngle(aAsr, phi, delta);
        double sunsetHourAngle = hourAngle(-0.833, phi, delta);
        double ishaHourAngle = hourAngle(-17.0, phi, delta);

        int fajrOffset = prefs.getInt("fajr_offset", 0);
        int asrOffset = prefs.getInt("asr_offset", 0);
        int maghribOffset = prefs.getInt("maghrib_offset", 0);

        double tFajr = solarNoon - fajrHourAngle + (fajrOffset / 60.0);
        double tDhuhr = solarNoon;
        double tAsr = solarNoon + asrHourAngle + (asrOffset / 60.0);
        double tSunset = solarNoon + sunsetHourAngle + (maghribOffset / 60.0);
        double tIsha = solarNoon + ishaHourAngle;

        return new String[]{
                formatTime(tFajr),
                formatTime(tDhuhr),
                formatTime(tAsr),
                formatTime(tSunset),
                formatTime(tIsha)
        };
    }

    private static double hourAngle(double altitude, double phi, double delta) {
        double cosHA = (Math.sin(Math.toRadians(altitude)) - Math.sin(phi) * Math.sin(delta)) / (Math.cos(phi) * Math.cos(delta));
        if (cosHA > 1.0) return 0.0;
        if (cosHA < -1.0) return 12.0;
        return Math.toDegrees(Math.acos(cosHA)) / 15.0;
    }

    private static String formatTime(double decimalHours) {
        int h = (int) decimalHours;
        int m = (int) Math.round((decimalHours - h) * 60.0);
        if (m == 60) {
            h++;
            m = 0;
        }
        h = (h + 24) % 24;
        return String.format(Locale.US, "%02d:%02d", h, m);
    }

    private void fetchOnline(Date date) {
        executor.execute(() -> {
            try {
                SimpleDateFormat df = new SimpleDateFormat("dd-MM-yyyy", Locale.US);
                SimpleDateFormat keyDf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                String dateStr = df.format(date);
                String keyStr = keyDf.format(date);

                int school = "shafii".equalsIgnoreCase(prefs.getString("madhab", "hanafi")) ? 0 : 1;
                String urlStr = "https://api.aladhan.com/v1/timingsByCity/" + dateStr
                        + "?city=Khujand&country=Tajikistan&method=3&school=" + school;
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(6000);
                conn.setRequestProperty("User-Agent", "SmartTV-Launcher/1.0");

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JSONObject json = new JSONObject(sb.toString());
                    JSONObject timings = json.getJSONObject("data").getJSONObject("timings");
                    String fajr = cleanTime(timings.getString("Fajr"));
                    String dhuhr = cleanTime(timings.getString("Dhuhr"));
                    String asr = cleanTime(timings.getString("Asr"));
                    String maghrib = cleanTime(timings.getString("Maghrib"));
                    String isha = cleanTime(timings.getString("Isha"));

                    String packed = fajr + "," + dhuhr + "," + asr + "," + maghrib + "," + isha;
                    prefs.edit().putString("times_" + keyStr, packed).apply();

                    handler.post(() -> {
                        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Dushanbe"));
                        if (listener != null) {
                            listener.onPrayerUpdated(computePrayerData(cal));
                        }
                    });
                }
            } catch (Exception e) {
                Log.d(TAG, "Online fetch skipped: " + e.getMessage());
            }
        });
    }

    private static String cleanTime(String t) {
        if (t == null) return "00:00";
        // AlAdhan sometimes returns "05:27 (EET)" or similar
        int space = t.indexOf(' ');
        if (space > 0) {
            return t.substring(0, space);
        }
        return t;
    }
}
