package com.studiosmus.wea;

import android.app.Notification;
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

    private static final String CHANNEL_ID = "wea_render";
    private static final int    NOTIF_ID   = 1;
    static final long           TICK_MS    = 2000L;

    private final Handler  handler = new Handler(Looper.getMainLooper());
    private final Runnable tick    = new Runnable() {
        @Override public void run() {
            try { SeaWidget.updateAllWidgets(RenderService.this); }
            catch (Exception ignored) {}
            handler.postDelayed(this, TICK_MS);
        }
    };

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            // Android 8+: must call startForeground() to stay alive
            createChannelApi26();
            startForeground(NOTIF_ID, buildNotificationApi26());
        }
        // On API < 26 the service can run in background without a notification
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

    // ── Notification helpers (reflection-based to stay on API 23 compile) ──

    private void createChannelApi26() {
        // NotificationChannel(String id, CharSequence name, int importance)
        // NotificationManager.IMPORTANCE_MIN = 1
        try {
            Object nm = getSystemService(NOTIFICATION_SERVICE);
            Class<?> chanCls = Class.forName("android.app.NotificationChannel");
            Object chan = chanCls
                    .getConstructor(String.class, CharSequence.class, int.class)
                    .newInstance(CHANNEL_ID, "WEA", 1);
            nm.getClass()
              .getMethod("createNotificationChannel", chanCls)
              .invoke(nm, chan);
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotificationApi26() {
        // Try two-arg builder (API 26+)
        try {
            Constructor<?> ctor = Notification.Builder.class
                    .getConstructor(Context.class, String.class);
            Notification.Builder b = (Notification.Builder) ctor.newInstance(this, CHANNEL_ID);
            b.setContentTitle("WEA");
            b.setSmallIcon(android.R.drawable.ic_dialog_info);
            b.setOngoing(true);
            return b.build();
        } catch (Exception ignored) {}
        // Fallback: single-arg (deprecated on 26+ but still works as a last resort)
        Notification.Builder b = new Notification.Builder(this);
        b.setContentTitle("WEA");
        b.setSmallIcon(android.R.drawable.ic_dialog_info);
        b.setOngoing(true);
        return b.build();
    }

    // ── Static start / stop called from SeaWidget and BootReceiver ──────────

    static void start(Context ctx) {
        Intent i = new Intent(ctx, RenderService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            // startForegroundService(Intent) — must use reflection: compile target is API 23
            try {
                Method m = Context.class.getMethod("startForegroundService", Intent.class);
                m.invoke(ctx, i);
                return;
            } catch (NoSuchMethodException e) {
                // device reports API 26+ but doesn't have the method — fallthrough
            } catch (Exception e) {
                // invocation failed — fallthrough
            }
        }
        try { ctx.startService(i); } catch (Exception ignored) {}
    }

    static void stop(Context ctx) {
        try { ctx.stopService(new Intent(ctx, RenderService.class)); }
        catch (Exception ignored) {}
    }
}
