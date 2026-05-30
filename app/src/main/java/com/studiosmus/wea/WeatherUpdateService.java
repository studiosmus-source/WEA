package com.studiosmus.wea;

import android.app.IntentService;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class WeatherUpdateService extends IntentService {

    public WeatherUpdateService() {
        super("WeatherUpdateService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String apiKey = prefs.getString("api_key", "5d65637a72e3b61f42e8223cdf07a228");

        boolean hasGps = prefs.getBoolean("has_gps", false);
        float lat = prefs.getFloat("gps_lat", 0f);
        float lon = prefs.getFloat("gps_lon", 0f);
        String city = prefs.getString("city", "");

        if (!hasGps && city.isEmpty()) return;

        try {
            String endpoint;
            if (hasGps) {
                endpoint = "https://api.openweathermap.org/data/2.5/weather?lat="
                        + lat + "&lon=" + lon + "&appid=" + apiKey + "&units=metric";
            } else {
                endpoint = "https://api.openweathermap.org/data/2.5/weather?q="
                        + URLEncoder.encode(city, "UTF-8") + "&appid=" + apiKey + "&units=metric";
            }

            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Accept", "application/json");

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONObject json = new JSONObject(sb.toString());
                int condId = json.getJSONArray("weather")
                        .getJSONObject(0).getInt("id");

                double windSpeed = 0;
                if (json.has("wind")) {
                    windSpeed = json.getJSONObject("wind").optDouble("speed", 0);
                }

                WeatherManager.Condition cond =
                        WeatherManager.fromIndex(WeatherManager.mapFromOWMId(condId));

                // Upgrade to WINDY if strong wind and not already severe
                if ((cond == WeatherManager.Condition.CLEAR
                        || cond == WeatherManager.Condition.CLOUDY)
                        && windSpeed > 8.0) {
                    cond = WeatherManager.Condition.WINDY;
                }

                WeatherManager.saveCondition(this, cond);
            }
            conn.disconnect();
        } catch (Exception e) {
            // keep old data on failure
        }

        AppWidgetManager mgr = AppWidgetManager.getInstance(this);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(this, SeaWidget.class));
        for (int id : ids) {
            SeaWidget.updateWidget(this, mgr, id);
        }
    }
}
