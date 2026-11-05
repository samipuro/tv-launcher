package com.cosyhome.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Current temperature and conditions from Open-Meteo (free, no key). The last reading is kept
 * so the clock has something to show straight after a restart, before the network is up.
 * The reading is stored as numbers (Celsius) and turned into text on display, in the TV's language
 * and the unit set in weather.temperature_unit.
 */
final class Weather {

    static final long REFRESH_MS = 30 * 60_000L;
    private static final String PREFS = "weather";

    private Weather() {
    }

    // Purpose: the last reading as text, e.g. "11° Puolipilvistä", or "" when there has never been one.
    static String cached(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.contains("code")) return "";

        // Readings stored before the unit setting existed only have the rounded value.
        double celsius = p.contains("temp_c") ? p.getFloat("temp_c", 0f) : p.getInt("temp", 0);
        return text(ctx, celsius, p.getInt("code", -1));
    }

    // Purpose: a reading as text, the temperature then the condition in the TV's language when the code is known.
    static String text(Context ctx, double celsius, int code) {
        boolean fahrenheit = "fahrenheit".equalsIgnoreCase(ctx.getString(R.string.weather_temperature_unit).trim());
        String text = fahrenheit ? Math.round(celsius * 9 / 5 + 32) + "°F" : Math.round(celsius) + "°";
        int desc = describe(code);
        return desc == 0 ? text : text + " " + ctx.getString(desc);
    }

    // Purpose: the last WMO weather code, or -1 when there has never been a reading.
    static int cachedCode(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("code", -1);
    }

    // Purpose: true when there is no stored weather code or the last reading is older than the refresh interval.
    static boolean stale(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return !p.contains("code") || System.currentTimeMillis() - p.getLong("at", 0) > REFRESH_MS;
    }

    // Purpose: fetch on a background thread; done runs on the main thread, only on success.
    static void fetch(Context ctx, double lat, double lon, Runnable done) {
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                // Two decimal places, about a kilometre: close enough for the weather.
                URL url = new URL(String.format(Locale.ROOT,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f"
                                + "&current=temperature_2m,weather_code&timezone=auto", lat, lon));
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                if (conn.getResponseCode() != 200) return;
                JSONObject current = new JSONObject(read(conn.getInputStream())).getJSONObject("current");

                // Store the numbers; the text is built in the TV's language when shown.
                SharedPreferences.Editor e = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
                double celsius = current.getDouble("temperature_2m");
                e.putInt("temp", (int) Math.round(celsius))
                        .putFloat("temp_c", (float) celsius)
                        .putInt("code", current.getInt("weather_code"))
                        .putLong("at", System.currentTimeMillis())
                        .remove("text")
                        .apply();
                main.post(done);
            } catch (Exception ignored) {
                // Offline or a bad reply: keep showing the last reading and try again next time.
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "weather").start();
    }

    // Purpose: read a whole response body as UTF-8.
    private static String read(InputStream in) throws java.io.IOException {
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    // Purpose: string resource for a WMO weather interpretation code, as Open-Meteo documents them; 0 when unknown.
    static int describe(int code) {
        switch (code) {
            case 0: return R.string.wx_clear;
            case 1: return R.string.wx_mostly_clear;
            case 2: return R.string.wx_partly_cloudy;
            case 3: return R.string.wx_overcast;
            case 45: case 48: return R.string.wx_fog;
            case 51: case 53: case 55: return R.string.wx_drizzle;
            case 56: case 57: return R.string.wx_freezing_drizzle;
            case 61: return R.string.wx_light_rain;
            case 63: return R.string.wx_rain;
            case 65: return R.string.wx_heavy_rain;
            case 66: case 67: return R.string.wx_freezing_rain;
            case 71: case 73: case 75: case 77: return R.string.wx_snow;
            case 80: case 81: case 82: return R.string.wx_showers;
            case 85: case 86: return R.string.wx_snow_showers;
            case 95: return R.string.wx_thunderstorm;
            case 96: case 99: return R.string.wx_thunderstorm_hail;
            default: return 0;
        }
    }
}
