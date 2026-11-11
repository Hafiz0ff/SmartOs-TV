package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically fetches live weather for Khujand, Tajikistan (Open-Meteo API).
 * Updates every 20 minutes; includes temperature, weather icon, humidity %,
 * wind speed (m/s), and daily sunrise and sunset times.
 */
public final class KhujandWeather {
    private static final String TAG = "KhujandWeather";
    private static final String PREFS_NAME = "khujand_weather";
    private static final String KEY_WEATHER = "last_weather";
    private static final String KEY_TIMESTAMP = "last_timestamp";

    private static final long DEFAULT_INTERVAL_MINUTES = 20;

    private static double[] getCityCoords(String city) {
        if ("Душанбе".equalsIgnoreCase(city)) return new double[]{38.5598, 68.7870};
        if ("Ташкент".equalsIgnoreCase(city)) return new double[]{41.3111, 69.2406};
        if ("Самарканд".equalsIgnoreCase(city)) return new double[]{39.6542, 66.9597};
        if ("Москва".equalsIgnoreCase(city)) return new double[]{55.7558, 37.6173};
        if ("Казань".equalsIgnoreCase(city)) return new double[]{55.7887, 49.1221};
        if ("Санкт-Петербург".equalsIgnoreCase(city)) return new double[]{59.9343, 30.3351};
        return new double[]{40.2460, 69.6489}; // Default: Khujand
    }

    public interface WeatherListener {
        void onWeatherUpdated(String weatherText);
    }

    private final Context context;
    private final Handler mainHandler;
    private final SharedPreferences prefs;
    private ScheduledExecutorService executor;
    private WeatherListener listener;
    private String lastWeatherText = "";

    public KhujandWeather(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.lastWeatherText = prefs.getString(KEY_WEATHER, "");
    }

    public void setListener(WeatherListener listener) {
        this.listener = listener;
        if (!lastWeatherText.isEmpty() && listener != null) {
            listener.onWeatherUpdated(lastWeatherText);
        }
    }

    public synchronized void start() {
        if (executor != null && !executor.isShutdown()) return;
        executor = Executors.newSingleThreadScheduledExecutor();

        int intervalMinutes = prefs.getInt("interval_minutes", (int) DEFAULT_INTERVAL_MINUTES);
        long lastTime = prefs.getLong(KEY_TIMESTAMP, 0L);
        long elapsed = System.currentTimeMillis() - lastTime;
        long initialDelay = (lastWeatherText.isEmpty() || elapsed >= TimeUnit.MINUTES.toMillis(intervalMinutes))
                ? 0 : Math.max(1, (TimeUnit.MINUTES.toMillis(intervalMinutes) - elapsed) / 1000);

        Log.i(TAG, "start() called. interval=" + intervalMinutes + "m, initialDelay=" + initialDelay);

        executor.scheduleWithFixedDelay(() -> {
            try {
                fetchWeather();
            } catch (Throwable t) {
                Log.e(TAG, "Exception during fetchWeather execution", t);
            }
        }, initialDelay, intervalMinutes * 60L, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void fetchWeather() {
        String cityName = prefs.getString("city_name", "Худжанд");
        double[] coords = getCityCoords(cityName);
        String apiUrl = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m&wind_speed_unit=ms&daily=sunrise,sunset&timezone=auto",
                coords[0], coords[1]);

        Log.i(TAG, "Fetching weather for " + cityName + " from " + apiUrl);
        HttpURLConnection conn = null;
        try {
            URL url = new URL(apiUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "CleanYourTV/3.3");

            int responseCode = conn.getResponseCode();
            Log.i(TAG, "Weather response code: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONObject root = new JSONObject(sb.toString());
                if (root.has("current")) {
                    JSONObject current = root.getJSONObject("current");
                    double temp = current.getDouble("temperature_2m");
                    int humidity = current.optInt("relative_humidity_2m", 0);
                    double windSpeed = current.optDouble("wind_speed_10m", 0.0);
                    int code = current.getInt("weather_code");

                    String sunriseTime = "";
                    String sunsetTime = "";
                    if (root.has("daily")) {
                        JSONObject daily = root.getJSONObject("daily");
                        if (daily.has("sunrise") && daily.has("sunset")) {
                            JSONArray sunrises = daily.getJSONArray("sunrise");
                            JSONArray sunsets = daily.getJSONArray("sunset");
                            if (sunrises.length() > 0) {
                                sunriseTime = extractTime(sunrises.getString(0));
                            }
                            if (sunsets.length() > 0) {
                                sunsetTime = extractTime(sunsets.getString(0));
                            }
                        }
                    }

                    boolean showHumidity = prefs.getBoolean("show_humidity", true);
                    boolean showWind = prefs.getBoolean("show_wind", true);
                    boolean showSun = prefs.getBoolean("show_sun", true);

                    String icon = weatherIcon(code);
                    String tempFormatted = (temp > 0 ? "+" : "") + Math.round(temp) + "°";

                    StringBuilder b = new StringBuilder();
                    b.append(cityName).append(" ").append(tempFormatted).append(" ").append(icon);
                    if (showHumidity && humidity > 0) {
                        b.append("  ·  ").append(humidity).append("%");
                    }
                    if (showWind && windSpeed > 0) {
                        b.append("  ·  ").append(String.format(Locale.getDefault(), "%.1f м/с", windSpeed));
                    }
                    if (showSun && !sunriseTime.isEmpty()) {
                        b.append("  ·  🌅 ").append(sunriseTime);
                    }
                    if (showSun && !sunsetTime.isEmpty()) {
                        b.append("  ·  🌇 ").append(sunsetTime);
                    }

                    String text = b.toString();
                    Log.i(TAG, "Weather updated: " + text);

                    lastWeatherText = text;
                    prefs.edit()
                            .putString(KEY_WEATHER, text)
                            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                            .apply();

                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onWeatherUpdated(text);
                        }
                    });
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Weather fetch error: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String extractTime(String isoDateTime) {
        if (isoDateTime == null) return "";
        int t = isoDateTime.indexOf('T');
        if (t >= 0 && t + 6 <= isoDateTime.length()) {
            return isoDateTime.substring(t + 1, t + 6);
        }
        return isoDateTime;
    }

    private static String weatherIcon(int code) {
        switch (code) {
            case 0:
                return "☀️";
            case 1:
                return "🌤️";
            case 2:
                return "⛅";
            case 3:
                return "☁️";
            case 45:
            case 48:
                return "🌫️";
            case 51:
            case 53:
            case 55:
                return "🌦️";
            case 56:
            case 57:
            case 66:
            case 67:
            case 61:
            case 63:
            case 65:
                return "🌧️";
            case 71:
            case 73:
            case 75:
            case 77:
            case 85:
            case 86:
                return "🌨️";
            case 80:
            case 81:
            case 82:
                return "🌦️";
            case 95:
            case 96:
            case 99:
                return "⛈️";
            default:
                return "☀️";
        }
    }
}
