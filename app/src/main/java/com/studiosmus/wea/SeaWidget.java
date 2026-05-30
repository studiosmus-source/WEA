package com.studiosmus.wea;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.RemoteViews;

public class SeaWidget extends AppWidgetProvider {

    private static final String ACTION_WEATHER_TICK =
            "com.studiosmus.wea.ACTION_WEATHER_TICK";

    // Called by Android every updatePeriodMillis and on install
    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            updateWidget(ctx, mgr, id);
        }
        scheduleWeatherUpdate(ctx);
    }

    // Called when widget size changes
    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr,
                                          int id, Bundle newOptions) {
        updateWidget(ctx, mgr, id);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        if (ACTION_WEATHER_TICK.equals(intent.getAction())) {
            // AlarmManager tick → start background weather fetch
            Intent si = new Intent(ctx, WeatherUpdateService.class);
            ctx.startService(si);
        }
    }

    @Override
    public void onDeleted(Context ctx, int[] ids) {
        // Nothing to clean up per-widget
    }

    @Override
    public void onDisabled(Context ctx) {
        // Cancel weather alarm when last widget removed
        cancelWeatherUpdate(ctx);
    }

    // ─── Widget rendering ────────────────────────────────────────────────────

    static void updateWidget(Context ctx, AppWidgetManager mgr, int id) {
        Bundle options = mgr.getAppWidgetOptions(id);
        int dpW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,  110);
        int dpH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180);

        Bitmap bmp = WidgetRenderer.render(ctx, dpW, dpH);

        RemoteViews views = new RemoteViews(ctx.getPackageName(), R.layout.widget_layout);
        views.setImageViewBitmap(R.id.widget_image, bmp);

        // Tap opens config
        Intent cfg = new Intent(ctx, ConfigActivity.class);
        cfg.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(ctx, id, cfg, flags);
        views.setOnClickPendingIntent(R.id.widget_image, pi);

        mgr.updateAppWidget(id, views);
    }

    static void updateAllWidgets(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, SeaWidget.class));
        for (int id : ids) {
            updateWidget(ctx, mgr, id);
        }
    }

    // ─── Weather scheduling ──────────────────────────────────────────────────

    static void scheduleWeatherUpdate(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(ctx, SeaWidget.class);
        intent.setAction(ACTION_WEATHER_TICK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, intent, flags);
        am.setInexactRepeating(
                AlarmManager.RTC,
                System.currentTimeMillis() + 15 * 60 * 1000L,
                15 * 60 * 1000L,
                pi);
    }

    private static void cancelWeatherUpdate(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(ctx, SeaWidget.class);
        intent.setAction(ACTION_WEATHER_TICK);
        int flags = PendingIntent.FLAG_NO_CREATE;
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, intent, flags);
        if (pi != null) am.cancel(pi);
    }
}
