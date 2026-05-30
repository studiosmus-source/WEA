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
        int w = Math.min(800, Math.max(200, (int) (dpWidth  * density)));
        int h = Math.min(600, Math.max(120, (int) (dpHeight * density)));

        TimeOfDay time    = getTimeOfDay();
        WeatherManager.Condition weather = WeatherManager.getCondition(ctx);

        // 1. Load + scale base photo
        Bitmap base = loadBase(ctx, w, h);

        // 2. Scene: time/weather overlay + sun glow + birds (all before glass)
        Bitmap scene = applySceneOverlay(base, w, h, time, weather);
        base.recycle();

        // 3. Glass distortion (pixel-level wave displacement)
        Bitmap distorted = applyGlassDistortion(scene, w, h);
        scene.recycle();

        // 4. Glass surface: tint, vignette, bubbles, streaks
        Canvas canvas = new Canvas(distorted);
        drawGlassSurface(canvas, w, h);

        // 5. Lens flare on glass (sunlight hitting the glass pane itself)
        if (weather == WeatherManager.Condition.CLEAR) {
            drawLensFlare(canvas, w, h, time);
        }

        // 6. Rain drops on glass
        if (weather == WeatherManager.Condition.RAINY
                || weather == WeatherManager.Condition.STORMY) {
            drawRainOnGlass(canvas, w, h);
        }

        // 7. Date/time — crisp, rendered after all glass effects
        drawTimeDate(canvas, w, h);

        // 8. Window frame — drawn last, always on top
        drawWindowFrame(canvas, w, h);

        return distorted;
    }

    // ─── Time detection ─────────────────────────────────────────────────────

    static TimeOfDay getTimeOfDay() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h >= 5  && h < 7)  return TimeOfDay.DAWN;
        if (h >= 7  && h < 11) return TimeOfDay.MORNING;
        if (h >= 11 && h < 14) return TimeOfDay.NOON;
        if (h >= 14 && h < 17) return TimeOfDay.AFTERNOON;
        if (h >= 17 && h < 20) return TimeOfDay.SUNSET;
        if (h >= 20 && h < 22) return TimeOfDay.DUSK;
        return TimeOfDay.NIGHT;
    }

    // ─── Load + scale base photo ─────────────────────────────────────────────

    private static Bitmap loadBase(Context ctx, int w, int h) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(ctx.getResources(), R.drawable.sea, opts);

        opts.inSampleSize = computeSampleSize(opts.outWidth, opts.outHeight, w, h);
        opts.inJustDecodeBounds = false;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;

        Bitmap raw = BitmapFactory.decodeResource(ctx.getResources(), R.drawable.sea, opts);
        if (raw == null) {
            Bitmap fb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            fb.eraseColor(0xFF0D47A1);
            return fb;
        }

        // Center-crop to widget dimensions
        Bitmap scaled = centerCrop(raw, w, h);
        if (scaled != raw) raw.recycle();
        return scaled;
    }

    private static Bitmap centerCrop(Bitmap src, int dstW, int dstH) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        float scaleW = (float) dstW / srcW;
        float scaleH = (float) dstH / srcH;
        float scale  = Math.max(scaleW, scaleH);
        int scaledW  = (int) (srcW * scale);
        int scaledH  = (int) (srcH * scale);
        Bitmap scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true);
        int offX = (scaledW - dstW) / 2;
        int offY = (scaledH - dstH) / 2;
        Bitmap cropped = Bitmap.createBitmap(scaled, offX, offY, dstW, dstH);
        if (scaled != src) scaled.recycle();
        return cropped;
    }

    private static int computeSampleSize(int srcW, int srcH, int dstW, int dstH) {
        int size = 1;
        while (srcW / (size * 2) >= dstW && srcH / (size * 2) >= dstH) size *= 2;
        return size;
    }

    // ─── Scene overlay: time/weather + sun glow + birds ──────────────────────

    private static Bitmap applySceneOverlay(Bitmap src, int w, int h,
                                             TimeOfDay time,
                                             WeatherManager.Condition weather) {
        Bitmap result = src.copy(Bitmap.Config.ARGB_8888, true);
        Canvas c = new Canvas(result);
        Paint p = new Paint();

        // — Time of day tint —
        switch (time) {
            case DAWN:
                p.setColor(Color.argb(110, 20, 5, 60));
                c.drawRect(0, 0, w, h, p);
                p.setShader(new LinearGradient(0, h * 0.3f, 0, h * 0.75f,
                        Color.argb(100, 255, 100, 20), Color.TRANSPARENT, Shader.TileMode.CLAMP));
                c.drawRect(0, h * 0.3f, w, h * 0.75f, p);
                p.setShader(null);
                break;

            case MORNING:
                p.setColor(Color.argb(18, 180, 210, 255));
                c.drawRect(0, 0, w, h, p);
                break;

            case NOON:
                p.setColor(Color.argb(8, 255, 255, 240));
                c.drawRect(0, 0, w, h, p);
                p.setShader(new LinearGradient(0, 0, 0, h * 0.2f,
                        Color.argb(30, 255, 255, 200), Color.TRANSPARENT, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h * 0.2f, p);
                p.setShader(null);
                break;

            case AFTERNOON:
                p.setColor(Color.argb(38, 255, 180, 50));
                c.drawRect(0, 0, w, h, p);
                break;

            case SUNSET:
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
                p.setShader(new LinearGradient(w * 0.35f, h * 0.55f, w * 0.65f, h,
                        Color.argb(28, 200, 210, 240), Color.TRANSPARENT, Shader.TileMode.CLAMP));
                c.drawRect(0, h * 0.55f, w, h, p);
                p.setShader(null);
                break;
        }

        // — Weather overlay —
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

        // — CLEAR weather: sun glow in scene + birds —
        if (weather == WeatherManager.Condition.CLEAR) {
            drawSunGlare(c, w, h, time);
            long birdSeed = System.currentTimeMillis() / (10L * 60 * 1000); // new position every 10 min
            drawBirds(c, w, h, time, birdSeed);
        }

        return result;
    }

    // ─── Sun glow (in scene, will be distorted by glass) ────────────────────

    private static void drawSunGlare(Canvas c, int w, int h, TimeOfDay time) {
        float sx, sy;
        switch (time) {
            case DAWN:      sx = 0.10f; sy = 0.42f; break;
            case MORNING:   sx = 0.22f; sy = 0.22f; break;
            case NOON:      sx = 0.62f; sy = 0.10f; break;
            case AFTERNOON: sx = 0.80f; sy = 0.18f; break;
            case SUNSET:    sx = 0.90f; sy = 0.38f; break;
            default: return;
        }

        float sunX = sx * w;
        float sunY = sy * h;

        Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Large diffuse glow
        gp.setShader(new RadialGradient(sunX, sunY, w * 0.32f,
                Color.argb(65, 255, 248, 200), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, gp);

        // Medium glow
        gp.setShader(new RadialGradient(sunX, sunY, w * 0.10f,
                Color.argb(110, 255, 255, 220), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.drawCircle(sunX, sunY, w * 0.10f, gp);

        // Bright core
        gp.setShader(null);
        gp.setColor(Color.argb(80, 255, 255, 245));
        c.drawCircle(sunX, sunY, w * 0.022f, gp);

        // Water reflection shimmer (sun on sea surface, lower portion)
        if (time == TimeOfDay.MORNING || time == TimeOfDay.NOON || time == TimeOfDay.AFTERNOON) {
            float refX = sunX * 0.6f + w * 0.2f; // reflection slightly offset
            float refY = h * 0.82f;
            gp.setShader(new RadialGradient(refX, refY, w * 0.10f,
                    Color.argb(45, 255, 255, 220), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            c.drawRect(refX - w * 0.10f, h * 0.68f, refX + w * 0.10f, h, gp);
        }
    }

    // ─── Birds (in scene, distorted by glass) ────────────────────────────────

    private static void drawBirds(Canvas c, int w, int h, TimeOfDay time, long seed) {
        if (time == TimeOfDay.DUSK || time == TimeOfDay.NIGHT) return;

        Random rnd = new Random(seed);
        Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
        bp.setStyle(Paint.Style.STROKE);
        bp.setStrokeCap(Paint.Cap.ROUND);

        float skyBase = h * 0.46f; // sky occupies top 46%
        int count = 3 + rnd.nextInt(5); // 3–7 birds

        for (int i = 0; i < count; i++) {
            float bx  = (0.06f + rnd.nextFloat() * 0.88f) * w;
            float by  = (0.04f + rnd.nextFloat() * 0.82f) * skyBase;

            // Birds higher up = farther = smaller
            float dist = by / skyBase;  // 0=top=far, 1=horizon=close
            float size = (2.5f + dist * 9f) * (w / 350f);

            // Wing angle varies slightly per bird
            float dip = rnd.nextFloat() * 0.35f;

            bp.setStrokeWidth(Math.max(0.8f, size * 0.30f));
            bp.setColor(Color.argb(150 + rnd.nextInt(80), 15, 15, 25));

            Path bird = new Path();
            bird.moveTo(bx - size, by + size * dip);
            bird.quadTo(bx - size * 0.44f, by - size * 0.28f, bx, by + size * 0.06f);
            bird.quadTo(bx + size * 0.44f, by - size * 0.28f, bx + size, by + size * dip);
            c.drawPath(bird, bp);
        }
    }

    // ─── Glass distortion ────────────────────────────────────────────────────

    private static Bitmap applyGlassDistortion(Bitmap src, int w, int h) {
        int[] srcPx = new int[w * h];
        src.getPixels(srcPx, 0, w, 0, 0, w, h);
        int[] dstPx = new int[w * h];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // Long wave: large-scale glass thickness variation
                double dx1 = 3.2 * Math.sin(y * 0.014 + 0.53)
                           + 1.8 * Math.cos(x * 0.011 + y * 0.007);
                double dy1 = 2.8 * Math.cos(x * 0.013 + 1.21)
                           + 1.6 * Math.sin(y * 0.010 + x * 0.006);
                // Short wave: manufacturing flow
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

        // Mineral green tint
        p.setColor(Color.argb(22, 25, 70, 15));
        c.drawRect(0, 0, w, h, p);

        // Vignette
        p.setShader(new RadialGradient(w / 2f, h / 2f,
                (float) Math.max(w, h) * 0.62f,
                Color.TRANSPARENT, Color.argb(75, 0, 0, 0), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        // Top-edge ambient reflection
        p.setShader(new LinearGradient(0, 0, 0, h * 0.10f,
                Color.argb(26, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.10f, p);
        p.setShader(null);

        drawBubbles(c, w, h, p);
        drawGlassStreaks(c, w, h);
    }

    private static void drawBubbles(Canvas c, int w, int h, Paint p) {
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
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(0.8f);
            p.setColor(Color.argb(55, 180, 210, 180));
            c.drawCircle(cx, cy, r, p);
            p.setColor(Color.argb(30, 0, 10, 0));
            c.drawCircle(cx, cy, r * 0.8f, p);
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

    // ─── Lens flare on glass surface (sun hitting the glass pane itself) ─────

    private static void drawLensFlare(Canvas c, int w, int h, TimeOfDay time) {
        float sx, sy;
        switch (time) {
            case MORNING:   sx = 0.22f; sy = 0.22f; break;
            case NOON:      sx = 0.62f; sy = 0.10f; break;
            case AFTERNOON: sx = 0.80f; sy = 0.18f; break;
            default: return; // no lens flare at dawn/dusk/night
        }

        float sunX = sx * w;
        float sunY = sy * h;
        // Flares appear along the line from sun through center and beyond
        float dirX = 0.5f * w - sunX;
        float dirY = 0.5f * h - sunY;

        float[][] flares = {
            // t along axis, radius factor, alpha
            {0.25f,  8f, 50},
            {0.50f,  5f, 32},
            {0.72f, 16f, 42},
            {0.95f,  4f, 25},
            {1.20f,  9f, 35},
        };

        Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (float[] f : flares) {
            float fx = sunX + dirX * f[0];
            float fy = sunY + dirY * f[0];
            float fr = f[1] * (w / 220f);
            int   fa = (int) f[2];
            fp.setShader(new RadialGradient(fx, fy, fr,
                    Color.argb(fa, 200, 215, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            c.drawCircle(fx, fy, fr, fp);
        }
    }

    // ─── Rain on glass ───────────────────────────────────────────────────────

    private static void drawRainOnGlass(Canvas c, int w, int h) {
        long seed = System.currentTimeMillis() / 60000L;
        Random rnd = new Random(seed);
        Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);

        for (int i = 0; i < 18; i++) {
            float x = rnd.nextFloat() * w;
            float y = rnd.nextFloat() * h;
            float r = (2f + rnd.nextFloat() * 5f) * (w / 240f);
            dp.setStyle(Paint.Style.FILL);
            dp.setColor(Color.argb(70, 180, 210, 240));
            c.drawOval(x - r * 0.55f, y - r, x + r * 0.55f, y + r * 0.35f, dp);
            dp.setColor(Color.argb(110, 255, 255, 255));
            c.drawOval(x - r * 0.28f, y - r * 0.80f, x + r * 0.05f, y - r * 0.20f, dp);
        }

        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        sp.setStyle(Paint.Style.STROKE);
        sp.setColor(Color.argb(50, 180, 210, 255));
        for (int i = 0; i < 22; i++) {
            float x  = rnd.nextFloat() * w;
            float y0 = rnd.nextFloat() * h * 0.7f;
            float len = (15 + rnd.nextFloat() * 90) * (h / 400f);
            sp.setStrokeWidth(0.7f + rnd.nextFloat() * 1.4f);
            Path streak = new Path();
            streak.moveTo(x, y0);
            streak.lineTo(x + rnd.nextFloat() * 5 - 2.5f, y0 + len);
            c.drawPath(streak, sp);
        }
    }

    // ─── Date / time overlay ─────────────────────────────────────────────────

    private static void drawTimeDate(Canvas c, int w, int h) {
        Calendar cal = Calendar.getInstance();
        String timeStr = String.format("%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));

        String[] days   = {"Dom", "Lun", "Mar", "Mer", "Gio", "Ven", "Sab"};
        String[] months = {"Gen", "Feb", "Mar", "Apr", "Mag", "Giu",
                           "Lug", "Ago", "Set", "Ott", "Nov", "Dic"};
        String dateStr = days[cal.get(Calendar.DAY_OF_WEEK) - 1] + "  "
                + cal.get(Calendar.DAY_OF_MONTH) + " "
                + months[cal.get(Calendar.MONTH)];

        float shadow = h * 0.018f;

        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.WHITE);
        tp.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        tp.setTextSize(h * 0.30f);
        tp.setLetterSpacing(0.04f);
        tp.setShadowLayer(shadow * 1.8f, 0, shadow, Color.argb(160, 0, 0, 0));
        float tw = tp.measureText(timeStr);
        c.drawText(timeStr, (w - tw) / 2f, h * 0.56f, tp);

        Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
        dp.setColor(Color.argb(230, 255, 255, 255));
        dp.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        dp.setTextSize(h * 0.130f);
        dp.setLetterSpacing(0.18f);
        dp.setShadowLayer(shadow * 1.4f, 0, shadow * 0.7f, Color.argb(140, 0, 0, 0));
        float dw = dp.measureText(dateStr);
        c.drawText(dateStr, (w - dw) / 2f, h * 0.56f + h * 0.165f, dp);
    }

    // ─── Window frame ────────────────────────────────────────────────────────

    private static void drawWindowFrame(Canvas c, int w, int h) {
        // Frame thickness proportional to smaller dimension
        float t = Math.min(w, h) * 0.085f;

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // — 1. Outer shadow (thin dark edge around widget) —
        p.setColor(Color.argb(160, 10, 8, 5));
        c.drawRect(0, 0, w, 2.5f, p);
        c.drawRect(0, h - 2.5f, w, h, p);
        c.drawRect(0, 0, 2.5f, h, p);
        c.drawRect(w - 2.5f, 0, w, h, p);

        // — 2. Frame body: aged wood (warm brown gradient) —
        // Top bar
        p.setShader(new LinearGradient(0, 0, 0, t,
                Color.argb(255, 120, 82, 42),  // top highlight
                Color.argb(255, 72, 46, 20),   // bottom shadow
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, t, p);

        // Bottom bar
        p.setShader(new LinearGradient(0, h - t, 0, h,
                Color.argb(255, 60, 38, 16),
                Color.argb(255, 90, 60, 28),
                Shader.TileMode.CLAMP));
        c.drawRect(0, h - t, w, h, p);

        // Left bar
        p.setShader(new LinearGradient(0, 0, t, 0,
                Color.argb(255, 110, 74, 36),
                Color.argb(255, 68, 44, 18),
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, t, h, p);

        // Right bar
        p.setShader(new LinearGradient(w - t, 0, w, 0,
                Color.argb(255, 62, 40, 18),
                Color.argb(255, 95, 62, 30),
                Shader.TileMode.CLAMP));
        c.drawRect(w - t, 0, w, h, p);
        p.setShader(null);

        // — 3. Wood grain hints (subtle horizontal/vertical streaks) —
        Paint gp = new Paint();
        gp.setColor(Color.argb(22, 200, 150, 80));
        for (float y = 3f; y < t - 2; y += 5.5f) {
            gp.setStrokeWidth(0.8f);
            c.drawLine(t, y, w - t, y, gp);            // top
            c.drawLine(t, h - y, w - t, h - y, gp);    // bottom
        }
        for (float x = 3f; x < t - 2; x += 5.5f) {
            c.drawLine(x, t, x, h - t, gp);            // left
            c.drawLine(w - x, t, w - x, h - t, gp);    // right
        }

        // — 4. Inner bevel: bright top-left, dark bottom-right (3D depth) —
        Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
        bp.setStyle(Paint.Style.STROKE);
        bp.setStrokeWidth(1.8f);

        // Bright inner edge (top + left inside faces)
        bp.setColor(Color.argb(120, 200, 160, 100));
        c.drawLine(t, t, w - t, t, bp);          // top inner edge
        c.drawLine(t, t, t, h - t, bp);           // left inner edge

        // Dark inner edge (bottom + right inside faces)
        bp.setColor(Color.argb(140, 20, 12, 5));
        c.drawLine(t, h - t, w - t, h - t, bp);  // bottom inner edge
        c.drawLine(w - t, t, w - t, h - t, bp);  // right inner edge

        // — 5. Inner shadow cast onto glass from frame —
        p.setShader(new LinearGradient(0, 0, 0, t * 1.4f,
                Color.argb(80, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.drawRect(t, t, w - t, t * 1.4f + t, p);

        p.setShader(new LinearGradient(0, h - t * 1.4f, 0, h - t,
                Color.TRANSPARENT, Color.argb(70, 0, 0, 0), Shader.TileMode.CLAMP));
        c.drawRect(t, h - t * 1.4f - t, w - t, h - t, p);
        p.setShader(null);

        // — 6. Corner joins (darker square covers the overlap) —
        p.setColor(Color.argb(255, 55, 35, 14));
        c.drawRect(0, 0, t, t, p);           // top-left
        c.drawRect(w - t, 0, w, t, p);       // top-right
        c.drawRect(0, h - t, t, h, p);       // bottom-left
        c.drawRect(w - t, h - t, w, h, p);   // bottom-right

        // Corner highlight dot (nail/peg suggestion)
        Paint cp = new Paint(Paint.ANTI_ALIAS_FLAG);
        cp.setColor(Color.argb(80, 180, 140, 80));
        float nr = t * 0.22f;
        float no = t * 0.50f;
        c.drawCircle(no, no, nr, cp);
        c.drawCircle(w - no, no, nr, cp);
        c.drawCircle(no, h - no, nr, cp);
        c.drawCircle(w - no, h - no, nr, cp);
    }

    // ─── Utility ─────────────────────────────────────────────────────────────

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }
}
