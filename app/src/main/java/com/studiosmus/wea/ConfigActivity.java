package com.studiosmus.wea;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class ConfigActivity extends Activity {

    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config);

        // Detect if launched as widget configurator
        Intent intent = getIntent();
        Bundle extras = intent.getExtras();
        if (extras != null) {
            widgetId = extras.getInt(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
        }

        // If launched as configurator and user presses back → cancel
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            Intent cancelResult = new Intent();
            cancelResult.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
            setResult(RESULT_CANCELED, cancelResult);
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        EditText cityEdit   = (EditText) findViewById(R.id.edit_city);
        EditText apiKeyEdit = (EditText) findViewById(R.id.edit_apikey);
        final TextView status = (TextView) findViewById(R.id.txt_status);

        // Pre-fill saved values
        cityEdit.setText(prefs.getString("city", ""));
        apiKeyEdit.setText(prefs.getString("api_key", ""));

        Button save = (Button) findViewById(R.id.btn_save);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EditText ce = (EditText) findViewById(R.id.edit_city);
                EditText ae = (EditText) findViewById(R.id.edit_apikey);
                String city   = ce.getText().toString().trim();
                String apiKey = ae.getText().toString().trim();

                if (city.isEmpty() || apiKey.isEmpty()) {
                    ((TextView) findViewById(R.id.txt_status))
                            .setText(getString(R.string.status_empty));
                    return;
                }

                SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(
                        ConfigActivity.this);
                p.edit()
                 .putString("city",    city)
                 .putString("api_key", apiKey)
                 .apply();

                // Trigger immediate weather fetch
                Intent si = new Intent(ConfigActivity.this, WeatherUpdateService.class);
                startService(si);

                // Also update widget immediately with current data
                SeaWidget.updateAllWidgets(ConfigActivity.this);

                status.setText(getString(R.string.status_saved));

                // Return OK if we were launched as configurator
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    Intent result = new Intent();
                    result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
                    setResult(RESULT_OK, result);
                    finish();
                }
            }
        });
    }
}
