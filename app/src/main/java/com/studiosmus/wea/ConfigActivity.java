package com.studiosmus.wea;

import android.Manifest;
import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class ConfigActivity extends Activity {

    private static final int REQ_LOCATION = 1001;
    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);

        Bundle extras = getIntent().getExtras();
        if (extras != null) {
            widgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            Intent cancel = new Intent();
            cancel.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
            setResult(RESULT_CANCELED, cancel);
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        ((EditText) findViewById(R.id.edit_city)).setText(prefs.getString("city", ""));
        ((EditText) findViewById(R.id.edit_apikey)).setText(
                prefs.getString("api_key", "5d65637a72e3b61f42e8223cdf07a228"));

        boolean hasGps = prefs.getBoolean("has_gps", false);
        if (hasGps) {
            float lat = prefs.getFloat("gps_lat", 0f);
            float lon = prefs.getFloat("gps_lon", 0f);
            setStatus(String.format("GPS: %.4f, %.4f", lat, lon));
        }

        ((Button) findViewById(R.id.btn_gps)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestGps(); }
        });

        // ── Test-weather buttons ───────────────────────────────────────────
        final WeatherManager.Condition[] testConditions = {
            WeatherManager.Condition.CLEAR,
            WeatherManager.Condition.CLOUDY,
            WeatherManager.Condition.WINDY,
            WeatherManager.Condition.RAINY,
            WeatherManager.Condition.STORMY,
        };
        final int[] testBtnIds = {
            R.id.btn_t_clear,
            R.id.btn_t_cloudy,
            R.id.btn_t_windy,
            R.id.btn_t_rainy,
            R.id.btn_t_stormy,
        };
        for (int i = 0; i < testBtnIds.length; i++) {
            final WeatherManager.Condition cond = testConditions[i];
            ((Button) findViewById(testBtnIds[i])).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    WeatherManager.setTestOverride(ConfigActivity.this, cond);
                    SeaWidget.updateAllWidgets(ConfigActivity.this);
                    setTestLabel("Test attivo: " + cond.name());
                }
            });
        }
        ((Button) findViewById(R.id.btn_t_reset)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                WeatherManager.clearTestOverride(ConfigActivity.this);
                startService(new Intent(ConfigActivity.this, WeatherUpdateService.class));
                SeaWidget.updateAllWidgets(ConfigActivity.this);
                setTestLabel("Meteo reale attivo");
            }
        });

        // Show current override on open
        if (WeatherManager.hasTestOverride(this)) {
            setTestLabel("Test attivo: " + WeatherManager.getCondition(this).name());
        }

        ((Button) findViewById(R.id.btn_save)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String city   = ((EditText) findViewById(R.id.edit_city)).getText().toString().trim();
                String apiKey = ((EditText) findViewById(R.id.edit_apikey)).getText().toString().trim();

                if (apiKey.isEmpty()) {
                    setStatus(getString(R.string.status_empty));
                    return;
                }

                SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(ConfigActivity.this);
                p.edit().putString("city", city).putString("api_key", apiKey).apply();

                startService(new Intent(ConfigActivity.this, WeatherUpdateService.class));
                SeaWidget.updateAllWidgets(ConfigActivity.this);
                setStatus(getString(R.string.status_saved));

                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    Intent result = new Intent();
                    result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
                    setResult(RESULT_OK, result);
                    finish();
                }
            }
        });
    }

    private void requestGps() {
        if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                                 Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQ_LOCATION);
        } else {
            saveGpsLocation();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_LOCATION && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            saveGpsLocation();
        } else {
            setStatus("Permesso GPS negato.");
        }
    }

    private void saveGpsLocation() {
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location loc = null;
            try { loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (SecurityException ignored) {}
            if (loc == null) {
                try { loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); } catch (SecurityException ignored) {}
            }
            if (loc != null) {
                PreferenceManager.getDefaultSharedPreferences(this).edit()
                        .putBoolean("has_gps", true)
                        .putFloat("gps_lat", (float) loc.getLatitude())
                        .putFloat("gps_lon", (float) loc.getLongitude())
                        .apply();
                setStatus(String.format("GPS salvato: %.4f, %.4f",
                        loc.getLatitude(), loc.getLongitude()));
                startService(new Intent(this, WeatherUpdateService.class));
            } else {
                setStatus("Posizione GPS non disponibile. Usa la città.");
            }
        } catch (Exception e) {
            setStatus("Errore GPS: " + e.getMessage());
        }
    }

    private void setStatus(String msg) {
        ((TextView) findViewById(R.id.txt_status)).setText(msg);
    }

    private void setTestLabel(String msg) {
        ((TextView) findViewById(R.id.txt_test_mode)).setText(msg);
    }
}
