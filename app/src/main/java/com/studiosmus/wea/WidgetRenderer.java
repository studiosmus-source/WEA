package com.studiosmus.wea;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.Calendar;
import java.util.Random;

public class WidgetRenderer {

    public enum TimeOfDay {
        DAWN, MORNING, NOON, AFTERNOON, SUNSET, DUSK, NIGHT
    }

    // ─── Entry point ────────────────────────────────────────────────────────

    public static Bitmap render(Context ctx, int dpWidth, int dpHeight) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int w = Math.min(600, Math.max(140, (int) (dpWidth  * density)));
        int h = Math.min(900, Math.max(230, (int) (dpHeight * density)));

        TimeOfDay time    = getTimeOfDay();
        WeatherManager.Condition weather = WeatherManager.getCondition(ctx);

        // 1. Load and scale base photo
        Bitmap base = loadBase(ctx, w, h);

        // 2. Apply time-of-day and weather color overlay
        Bitmap tinted = applyTimeWeatherOverlay(base, w, h, time, weather);
        base.recycle();

        // 3. Apply glass distortion (pixel displacement)
        Bitmap distorted = applyGlassDistortion(tinted, w, h);
        tinted.recycle();

        // 4. Draw glass surface effects (bubbles, tint, vignette, streaks)
        Canvas canvas = new Canvas(distorted);
        drawGlassSurface(canvas, w, h);

        // 5. Rain on glass (when rainy/stormy)
        if (weather == WeatherManager.Condition.RAINY
                || weather == WeatherManager.Condition.STORMY) {
            drawRainOnGlass(canvas, w, h);
        }

        // 6. Date / time — always crisp, drawn last (in front of glass)
        drawTimeDate(canvas, w, h);

        return distorted;
    }

    // ─── Time detection ─────────────────────────────────────────────────────

    private static TimeOfDay getTimeOfDay() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h >= 5  && h < 7)  return TimeOfDay.DAWN;
        if (h >= 7  && h < 11) return TimeOfDay.MORNING;
        if (h >= 11 && h < 14) return TimeOfDay.NOON;
        if (h >= 14 && h < 17) return TimeOfDay.AFTERNOON;
        if (h >= 17 && h < 20) return TimeOfDay.SUNSET;
        if (h >= 20 && h < 22) return TimeOfDay.DUSK;
        return TimeOfDay.NIGHT;
    }

    // ─── Load & scale base photo ─────────────────────────────────────────────

    private static Bitmap loadBase(Context ctx, int w, int h) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(ctx.getResources(), R.drawable.sea, opts);

        opts.inSampleSize = computeSampleSize(opts.outWidth, opts.outHeight, w, h);
        opts.inJustDecodeBounds = false;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

        Bitmap raw = BitmapFactory.decodeResource(ctx.getResources(), R.drawable.sea, opts);
        if (raw == null) {
            // Fallback: plain ocean blue if image fails to load
            Bitmap fb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            fb.eraseColor(0xFF0D47A1);
            return fb;
        }
        Bitmap scaled = Bitmap.createScaledBitmap(raw, w, h, true);
        if (scaled != raw) raw.recycle();
        return scaled;
    }

    private static int computeSampleSize(int srcW, int srcH, int dstW, int dstH) {
        int size = 1;
        while (srcW / (size * 2) >= dstW && srcH / (size * 2) >= dstH) size *= 2;
        return size;
    }

    // ─── Time / weather overlay ──────────────────────────────────────────────

    private static Bitmap applyTimeWeatherOverlay(Bitmap src, int w, int h,
                                                   TimeOfDay time,
                                                   WeatherManager.Condition weather) {
        Bitmap result = src.copy(Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(result);
        Paint p = new Paint();

        // Time-of-day tint
        switch (time) {
            case DAWN:
                // Deep blue-purple + warm orange glow near horizon
                p.setColor(Color.argb(110, 20, 5, 60));
                c.drawRect(0, 0, w, h, p);
                p.setShader(new LinearGradient(0, h * 0.3f, 0, h * 0.75f,
                        Color.argb(100, 255, 100, 20), Color.TRANSPARENT, Shader.TileMode.CLAMP));
                c.drawRect(0, h * 0.3f, w, h * 0.75f, p);
                p.setShader(null);
                break;

            case MORNING:
                // Slight blue-white boost, almost natural
                p.setColor(Color.argb(20, 180, 210, 255));
                c.drawRect(0, 0, w, h, p);
                break;

            case NOON:
                // Bright, high contrast — almost no overlay
                p.setColor(Color.argb(10, 255, 255, 240));
                c.drawRect(0, 0, w, h, p);
                // Slight sun-glare shimmer at top
                p.setShader(new LinearGradient(0, 0, 0, h * 0.2f,
                        Color.argb(35, 255, 255, 200), Color.TRANSPARENT, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h * 0.2f, p);
                p.setShader(null);
                break;

            case AFTERNOON:
                // Warm golden tint
                p.setColor(Color.argb(40, 255, 180, 50));
                c.drawRect(0, 0, w, h, p);
                break;

            case SUNSET:
                // Strong orange-red on upper half, deep purple-red overall
                p.setColor(Color.argb(90, 50, 10, 30));
                c.drawRect(0, 0, w, h, p);
                p.setShader(new LinearGradient(0, 0, 0, h * 0.65f,
                        Color.argb(140, 230, 80, 10), Color.argb(60, 180, 30, 10),
                        Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h * 0.65f, p);
                p.setShader(null);
                break;

            case DUSK:
                p.setColor(Color.argb(140, 10, 5, 40));
                c.drawRect(0, 0, w, h, p);
                p.setColor(Color.argb(60, 80, 20, 120));
                c.drawRect(0, 0, w, h * 0.5f, p);
                break;

            case NIGHT:
                p.setColor(Color.argb(200, 5, 5, 20));
                c.drawRect(0, 0, w, h, p);
                // Moonlight: faint silver shimmer on sea portion
                p.setShader(new LinearGradient(w * 0.4f, h * 0.55f, w * 0.6f, h,
                        Color.argb(30, 200, 210, 240), Color.TRANSPARENT, Shader.TileMode.CLAMP));
                c.drawRect(0, h * 0.55f, w, h, p);
                p.setShader(null);
                break;
        }

        // Weather overlay on top of time tint
        switch (weather) {
            case CLOUDY:
                p.setColor(Color.argb(55, 100, 110, 130));
                c.drawRect(0, 0, w, h, p);
                break;
            case RAINY:
                p.setColor(Color.argb(80, 60, 70, 100));
                c.drawRect(0, 0, w, h, p);
                break;
            case STORMY:
                p.setColor(Color.argb(120, 20, 25, 50));
                c.drawRect(0, 0, w, h, p);
                break;
            default:
                break;
        }

        return result;
    }

    // ─── Glass distortion (old 1800s glass — wave displacement) ─────────────

    private static Bitmap applyGlassDistortion(Bitmap src, int w, int h) {
        int[] srcPx = new int[w * h];
        src.getPixels(srcPx, 0, w, 0, 0, w, h);
        int[] dstPx = new int[w * h];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // Long wave: large-scale thickness variation
                double dx1 = 3.2 * Math.sin(y * 0.014 + 0.53)
                           + 1.8 * Math.cos(x * 0.011 + y * 0.007);
                double dy1 = 2.8 * Math.cos(x * 0.013 + 1.21)
                           + 1.6 * Math.sin(y * 0.010 + x * 0.006);

                // Short wave: manufacturing flow marks
                double dx2 = 0.9 * Math.sin(x * 0.048 + y * 0.031);
                double dy2 = 0.7 * Math.cos(y * 0.042 + x * 0.022);

                int sx = clamp((int) (x + dx1 + dx2), 0, w - 1);
                int sy = clamp((int) (y + dy1 + dy2), 0, h - 1);
                dstPx[y * w + x] = srcPx[sy * w + sx];
            }
        }

        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(dstPx, 0, w, 0, 0, w, h);
        return result;
    }

    // ─── Glass surface: tint, vignette, bubbles, streaks ────────────────────

    private static void drawGlassSurface(Canvas c, int w, int h) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Slight green-mineral tint (iron content in old glass)
        p.setColor(Color.argb(22, 25, 70, 15));
        c.drawRect(0, 0, w, h, p);

        // Vignette: edges of glass are thicker, darker
        p.setShader(new RadialGradient(
                w / 2f, h / 2f,
                (float) Math.max(w, h) * 0.62f,
                Color.TRANSPARENT,
                Color.argb(75, 0, 0, 0),
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        // Top-edge surface reflection (glass reflects ambient light at top)
        p.setShader(new LinearGradient(0, 0, 0, h * 0.12f,
                Color.argb(28, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.12f, p);
        p.setShader(null);

        // Bubbles
        drawBubbles(c, w, h, p);

        // Faint vertical flow streaks
        drawGlassStreaks(c, w, h);
    }

    private static void drawBubbles(Canvas c, int w, int h, Paint p) {
        // Fixed positions (like a real pane — always in same spot)
        float[][] bubbles = {
            {0.14f, 0.11f, 0.013f},
            {0.73f, 0.27f, 0.009f},
            {0.38f, 0.54f, 0.016f},
            {0.85f, 0.66f, 0.010f},
            {0.22f, 0.82f, 0.008f},
            {0.62f, 0.08f, 0.010f},
            {0.47f, 0.43f, 0.007f},
            {0.08f, 0.63f, 0.012f},
            {0.56f, 0.77f, 0.009f},
            {0.91f, 0.44f, 0.006f},
        };

        for (float[] b : bubbles) {
            float cx = b[0] * w;
            float cy = b[1] * h;
            float r  = b[2] * Math.min(w, h);

            // Outer ring
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(0.8f);
            p.setColor(Color.argb(55, 180, 210, 180));
            c.drawCircle(cx, cy, r, p);

            // Inner dark ring
            p.setColor(Color.argb(30, 0, 10, 0));
            c.drawCircle(cx, cy, r * 0.8f, p);

            // Highlight (light caught on bubble surface)
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(65, 255, 255, 255));
            c.drawCircle(cx - r * 0.32f, cy - r * 0.32f, r * 0.35f, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private static void drawGlassStreaks(Canvas c, int w, int h) {
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        sp.setStyle(Paint.Style.STROKE);
        sp.setStrokeWidth(0.9f);
        sp.setColor(Color.argb(18, 255, 255, 255));

        Path s1 = new Path();
        s1.moveTo(w * 0.28f, 0);
        s1.cubicTo(w * 0.31f, h * 0.28f, w * 0.26f, h * 0.55f, w * 0.30f, h);
        c.drawPath(s1, sp);

        Path s2 = new Path();
        s2.moveTo(w * 0.68f, 0);
        s2.cubicTo(w * 0.71f, h * 0.32f, w * 0.67f, h * 0.62f, w * 0.70f, h);
        c.drawPath(s2, sp);

        sp.setColor(Color.argb(10, 255, 255, 255));
        sp.setStrokeWidth(1.5f);
        Path s3 = new Path();
        s3.moveTo(w * 0.50f, 0);
        s3.cubicTo(w * 0.52f, h * 0.40f, w * 0.49f, h * 0.70f, w * 0.51f, h);
        c.drawPath(s3, sp);
    }

    // ─── Rain drops on glass ─────────────────────────────────────────────────

    private static void drawRainOnGlass(Canvas c, int w, int h) {
        // Seed changes each minute → new drop pattern each minute
        long seed = System.currentTimeMillis() / 60000L;
        Random rnd = new Random(seed);

        Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Drops (teardrop-ish blobs)
        for (int i = 0; i < 18; i++) {
            float x = rnd.nextFloat() * w;
            float y = rnd.nextFloat() * h;
            float r = (2f + rnd.nextFloat() * 5f) * (w / 200f);

            dp.setStyle(Paint.Style.FILL);
            dp.setColor(Color.argb(70, 180, 210, 240));
            c.drawOval(x - r * 0.55f, y - r, x + r * 0.55f, y + r * 0.35f, dp);

            // Highlight
            dp.setColor(Color.argb(110, 255, 255, 255));
            c.drawOval(x - r * 0.28f, y - r * 0.8f, x + r * 0.05f, y - r * 0.2f, dp);
        }

        // Running streaks
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        sp.setStyle(Paint.Style.STROKE);
        sp.setColor(Color.argb(50, 180, 210, 255));

        for (int i = 0; i < 22; i++) {
            float x  = rnd.nextFloat() * w;
            float y0 = rnd.nextFloat() * h * 0.7f;
            float len = (15 + rnd.nextFloat() * 90) * (h / 500f);
            sp.setStrokeWidth(0.7f + rnd.nextFloat() * 1.4f);
            Path streak = new Path();
            streak.moveTo(x, y0);
            streak.lineTo(x + rnd.nextFloat() * 5 - 2.5f, y0 + len);
            c.drawPath(streak, sp);
        }
    }

    // ─── Date / time overlay (always crisp — drawn after glass) ─────────────

    private static void drawTimeDate(Canvas c, int w, int h) {
        Calendar cal = Calendar.getInstance();
        int hour   = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);

        String timeStr = String.format("%02d:%02d", hour, minute);

        String[] days   = {"Dom", "Lun", "Mar", "Mer", "Gio", "Ven", "Sab"};
        String[] months = {"Gen", "Feb", "Mar", "Apr", "Mag", "Giu",
                           "Lug", "Ago", "Set", "Ott", "Nov", "Dic"};
        String dateStr = days[cal.get(Calendar.DAY_OF_WEEK) - 1] + "  "
                + cal.get(Calendar.DAY_OF_MONTH) + " "
                + months[cal.get(Calendar.MONTH)];

        float shadow = h * 0.012f;

        // Time
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.WHITE);
        tp.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        tp.setTextSize(h * 0.19f);
        tp.setLetterSpacing(0.04f);
        tp.setShadowLayer(shadow * 1.8f, 0, shadow, Color.argb(160, 0, 0, 0));

        float tw = tp.measureText(timeStr);
        float tx = (w - tw) / 2f;
        float ty = h * 0.52f;
        c.drawText(timeStr, tx, ty, tp);

        // Date
        Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
        dp.setColor(Color.argb(230, 255, 255, 255));
        dp.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        dp.setTextSize(h * 0.085f);
        dp.setLetterSpacing(0.18f);
        dp.setShadowLayer(shadow * 1.4f, 0, shadow * 0.7f, Color.argb(140, 0, 0, 0));

        float dw = dp.measureText(dateStr);
        float dx = (w - dw) / 2f;
        float dy = ty + h * 0.11f;
        c.drawText(dateStr, dx, dy, dp);
    }

    // ─── Utility ─────────────────────────────────────────────────────────────

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }
}
