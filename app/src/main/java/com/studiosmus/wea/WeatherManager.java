package com.studiosmus.wea;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public class WeatherManager {

    public enum Condition { CLEAR, CLOUDY, RAINY, STORMY }

    private static final long MAX_AGE_MS = 60L * 60 * 1000; // 1 hour

    public static Condition getCondition(Context ctx) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        long updated = prefs.getLong("weather_updated", 0);
        if (System.currentTimeMillis() - updated > MAX_AGE_MS) {
            return Condition.CLEAR;
        }
        String s = prefs.getString("weather_condition", "CLEAR");
        try {
            return Condition.valueOf(s);
        } catch (IllegalArgumentException e) {
            return Condition.CLEAR;
        }
    }

    public static void saveCondition(Context ctx, Condition c) {
        PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit()
                .putString("weather_condition", c.name())
                .putLong("weather_updated", System.currentTimeMillis())
                .apply();
    }

    public static int mapFromOWMId(int id) {
        if (id >= 200 && id < 300) return 3; // STORMY
        if (id >= 300 && id < 600) return 2; // RAINY
        if (id >= 600 && id < 800) return 1; // CLOUDY (snow/fog treated as cloudy)
        if (id == 800)             return 0; // CLEAR
        return 1;                            // 801-804 → CLOUDY
    }

    public static Condition fromIndex(int idx) {
        switch (idx) {
            case 0: return Condition.CLEAR;
            case 1: return Condition.CLOUDY;
            case 2: return Condition.RAINY;
            case 3: return Condition.STORMY;
            default: return Condition.CLEAR;
        }
    }
}
