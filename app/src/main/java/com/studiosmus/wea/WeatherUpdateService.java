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
        String city   = prefs.getString("city", "");

        if (apiKey.isEmpty() || city.isEmpty()) return;

        try {
            String encodedCity = URLEncoder.encode(city, "UTF-8");
            URL url = new URL(
                "https://api.openweathermap.org/data/2.5/weather?q="
                + encodedCity + "&appid=" + apiKey + "&units=metric");

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
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
                JSONArray weatherArr = json.getJSONArray("weather");
                int condId = weatherArr.getJSONObject(0).getInt("id");

                WeatherManager.Condition cond =
                        WeatherManager.fromIndex(WeatherManager.mapFromOWMId(condId));
                WeatherManager.saveCondition(this, cond);
            }
            conn.disconnect();
        } catch (Exception e) {
            // keep old data on failure
        }

        // Trigger widget redraw after weather update
        AppWidgetManager mgr = AppWidgetManager.getInstance(this);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(this, SeaWidget.class));
        for (int id : ids) {
            SeaWidget.updateWidget(this, mgr, id);
        }
    }
}
