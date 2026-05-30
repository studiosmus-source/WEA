package com.studiosmus.wea;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class RenderService extends Service {

    private static final String CHANNEL_ID = "wea_widget";
    private static final int    NOTIF_ID   = 1;
    static final long           TICK_MS    = 2000L;

    private final Handler  handler = new Handler(Looper.getMainLooper());
    private final Runnable tick    = new Runnable() {
        @Override public void run() {
            SeaWidget.updateAllWidgets(RenderService.this);
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        startForeground(NOTIF_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── Notification (with reflection for API 26+ channel) ──────────────────

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            Class<?> chanClass = Class.forName("android.app.NotificationChannel");
            // IMPORTANCE_MIN = 1
            Object chan = chanClass
                    .getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(CHANNEL_ID, "WEA widget", 1);
            chanClass.getMethod("setShowBadge", boolean.class).invoke(chan, false);
            chanClass.getMethod("setSound",
                    android.net.Uri.class, android.media.AudioAttributes.class)
                    .invoke(chan, null, null);
            NotificationManager nm = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.getClass()
                  .getMethod("createNotificationChannel", chanClass)
                  .invoke(nm, chan);
            }
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Constructor<?> ctor = Notification.Builder.class
                        .getConstructor(Context.class, String.class);
                b = (Notification.Builder) ctor.newInstance(this, CHANNEL_ID);
            } catch (Exception e) {
                b = new Notification.Builder(this);
            }
        } else {
            b = new Notification.Builder(this);
            b.setPriority(Notification.PRIORITY_MIN);
        }
        b.setContentTitle("WEA")
         .setSmallIcon(android.R.drawable.ic_popup_sync)
         .setOngoing(true);
        return b.build();
    }

    // ── Start / stop helpers ─────────────────────────────────────────────────

    static void start(Context ctx) {
        Intent i = new Intent(ctx, RenderService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Method m = ctx.getClass()
                        .getMethod("startForegroundService", Intent.class);
                m.invoke(ctx, i);
                return;
            } catch (Exception ignored) {}
        }
        ctx.startService(i);
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, RenderService.class));
    }
}
