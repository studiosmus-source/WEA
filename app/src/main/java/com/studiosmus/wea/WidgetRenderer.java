package com.studiosmus.wea;

import android.content.Context;
import android.graphics.Bitmap;
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
        int w = Math.min(900, Math.max(160, (int)(dpWidth  * density)));
        int h = Math.min(600, Math.max(100, (int)(dpHeight * density)));

        TimeOfDay time    = getTimeOfDay();
        WeatherManager.Condition weather = WeatherManager.getCondition(ctx);
        long timeSeed = System.currentTimeMillis() / (60L * 1000); // new seed each minute

        // horizon sits at 52% of height
        float horizonY = h * 0.52f;

        // 1. Generate sea/sky background
        Bitmap base = generateSeaSky(w, h, horizonY, time, weather, timeSeed);

        // 2. Scene effects (birds, sparkles, leaves, stars) — before glass
        addSceneEffects(base, w, h, horizonY, time, weather, timeSeed);

        // 3. Glass wave-distortion
        Bitmap distorted = applyGlassDistortion(base, w, h);
        base.recycle();

        // 4. Glass surface: tint, vignette, bubbles, streaks
        Canvas canvas = new Canvas(distorted);
        drawGlassSurface(canvas, w, h);

        // 5. Rain on glass
        if (weather == WeatherManager.Condition.RAINY
                || weather == WeatherManager.Condition.STORMY) {
            drawRainOnGlass(canvas, w, h, timeSeed);
        }

        // 6. Time / date — always crisp
        drawTimeDate(canvas, w, h);

        // 7. Window frame
        drawWindowFrame(canvas, w, h);

        return distorted;
    }

    // ─── Time of day ─────────────────────────────────────────────────────────

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

    // ═══════════════════════════════════════════════════════════════════════
    //  BACKGROUND GENERATION
    // ═══════════════════════════════════════════════════════════════════════

    private static Bitmap generateSeaSky(int w, int h, float horizonY,
                                          TimeOfDay time,
                                          WeatherManager.Condition weather,
                                          long seed) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        drawSky(c, w, h, horizonY, time, weather);
        drawSunMoonGlow(c, w, horizonY, time, weather);
        drawCoastSilhouette(c, w, horizonY, time, weather);
        drawHorizonHaze(c, w, horizonY, time, weather);
        drawSea(c, w, h, horizonY, time, weather, seed);

        return bmp;
    }

    // ── Sky ───────────────────────────────────────────────────────────────

    private static void drawSky(Canvas c, int w, int h, float horizonY,
                                 TimeOfDay time, WeatherManager.Condition weather) {
        int[] stops; // [top, mid, horizon]
        switch (time) {
            case DAWN:
                stops = new int[]{0xFF0D0420, 0xFF5C1030, 0xFFD04018}; break;
            case MORNING:
                stops = new int[]{0xFF082868, 0xFF1060C0, 0xFF90C8F0}; break;
            case NOON:
                stops = new int[]{0xFF0250A8, 0xFF1878D0, 0xFF9DD5F5}; break;
            case AFTERNOON:
                stops = new int[]{0xFF103888, 0xFF2068C0, 0xFFDFB860}; break;
            case SUNSET:
                stops = new int[]{0xFF2A0848, 0xFF901820, 0xFFE85010}; break;
            case DUSK:
                stops = new int[]{0xFF060818, 0xFF160830, 0xFF2E1248}; break;
            default: // NIGHT
                stops = new int[]{0xFF030308, 0xFF060D1C, 0xFF0C1530}; break;
        }

        // Apply weather desaturation
        if (weather == WeatherManager.Condition.CLOUDY
                || weather == WeatherManager.Condition.WINDY) {
            stops[0] = blendColor(stops[0], 0xFF505860, 0.45f);
            stops[1] = blendColor(stops[1], 0xFF606870, 0.45f);
            stops[2] = blendColor(stops[2], 0xFF909898, 0.40f);
        } else if (weather == WeatherManager.Condition.RAINY
                || weather == WeatherManager.Condition.STORMY) {
            stops[0] = blendColor(stops[0], 0xFF1A2028, 0.70f);
            stops[1] = blendColor(stops[1], 0xFF283040, 0.70f);
            stops[2] = blendColor(stops[2], 0xFF404858, 0.65f);
        }

        float midY = horizonY * 0.5f;
        Paint p = new Paint();

        // Top → mid
        p.setShader(new LinearGradient(0, 0, 0, midY,
                stops[0], stops[1], Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, midY, p);

        // Mid → horizon
        p.setShader(new LinearGradient(0, midY, 0, horizonY,
                stops[1], stops[2], Shader.TileMode.CLAMP));
        c.drawRect(0, midY, w, horizonY, p);
        p.setShader(null);
    }

    // ── Sun / moon glow (no hard circle) ────────────────────────────────────

    private static void drawSunMoonGlow(Canvas c, int w, float horizonY,
                                         TimeOfDay time,
                                         WeatherManager.Condition weather) {
        if (weather == WeatherManager.Condition.STORMY) return;

        Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);

        if (time == TimeOfDay.NIGHT || time == TimeOfDay.DUSK) {
            // Moon: silver-white glow in upper sky
            float mx = w * 0.72f, my = horizonY * 0.28f;
            gp.setShader(new RadialGradient(mx, my, w * 0.18f,
                    new int[]{Color.argb(100, 220, 230, 255),
                               Color.argb(40,  180, 200, 240),
                               Color.TRANSPARENT},
                    new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, horizonY, gp);
            return;
        }

        float sx, sy, r, a;
        int glow0, glow1;
        switch (time) {
            case DAWN:
                sx = w*0.18f; sy = horizonY*1.05f;
                r  = w*0.50f; a  = 0.90f;
                glow0 = Color.argb(130, 255, 140,  40);
                glow1 = Color.argb( 60, 255, 100,  10); break;
            case MORNING:
                sx = w*0.15f; sy = horizonY*0.35f;
                r  = w*0.32f; a  = 0.75f;
                glow0 = Color.argb( 90, 255, 250, 200);
                glow1 = Color.argb( 30, 255, 240, 160); break;
            case NOON:
                sx = w*0.60f; sy = horizonY*0.08f;
                r  = w*0.38f; a  = 0.65f;
                glow0 = Color.argb( 75, 255, 255, 230);
                glow1 = Color.argb( 20, 255, 255, 200); break;
            case AFTERNOON:
                sx = w*0.78f; sy = horizonY*0.22f;
                r  = w*0.34f; a  = 0.75f;
                glow0 = Color.argb( 90, 255, 220, 160);
                glow1 = Color.argb( 25, 255, 200, 100); break;
            default: // SUNSET
                sx = w*0.82f; sy = horizonY*0.92f;
                r  = w*0.55f; a  = 1.00f;
                glow0 = Color.argb(160, 255, 100,  20);
                glow1 = Color.argb( 60, 200,  40,   5); break;
        }

        float cloudAlpha = (weather == WeatherManager.Condition.CLOUDY
                || weather == WeatherManager.Condition.WINDY) ? 0.5f : 1.0f;
        glow0 = scaleAlpha(glow0, cloudAlpha);
        glow1 = scaleAlpha(glow1, cloudAlpha);

        gp.setShader(new RadialGradient(sx, sy, r,
                new int[]{glow0, glow1, Color.TRANSPARENT},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, Math.min(horizonY * 1.2f, (float)(horizonY + r * 0.2f)), gp);
    }

    // ── Distant coast silhouette ────────────────────────────────────────────

    private static void drawCoastSilhouette(Canvas c, int w, float horizonY,
                                             TimeOfDay time,
                                             WeatherManager.Condition weather) {
        int color;
        switch (time) {
            case DAWN:      color = Color.argb(120,  25,  10,  35); break;
            case MORNING:   color = Color.argb(100,  20,  55,  90); break;
            case NOON:      color = Color.argb( 90,  15,  60, 100); break;
            case AFTERNOON: color = Color.argb(100,  25,  50,  85); break;
            case SUNSET:    color = Color.argb(140,  30,   8,  20); break;
            case DUSK:      color = Color.argb(150,   8,   4,  15); break;
            default:        color = Color.argb(160,   4,   3,  10); break;
        }
        if (weather == WeatherManager.Condition.STORMY
                || weather == WeatherManager.Condition.RAINY) {
            color = scaleAlpha(color, 0.4f); // barely visible in storm
        }

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);

        float coast = horizonY - h(horizonY, 0.05f);
        Path path = new Path();
        path.moveTo(0,    horizonY);
        path.cubicTo(w*0.12f, coast - h(horizonY,0.04f),
                     w*0.22f, coast - h(horizonY,0.06f),
                     w*0.35f, coast - h(horizonY,0.02f));
        path.cubicTo(w*0.48f, coast + h(horizonY,0.01f),
                     w*0.58f, coast - h(horizonY,0.03f),
                     w*0.70f, coast - h(horizonY,0.01f));
        path.cubicTo(w*0.82f, coast + h(horizonY,0.00f),
                     w*0.92f, coast - h(horizonY,0.02f),
                     w,       coast + h(horizonY,0.01f));
        path.lineTo(w, horizonY);
        path.close();
        c.drawPath(path, p);
    }

    private static float h(float base, float frac) { return base * frac; }

    // ── Horizon haze ─────────────────────────────────────────────────────────

    private static void drawHorizonHaze(Canvas c, int w, float horizonY,
                                         TimeOfDay time,
                                         WeatherManager.Condition weather) {
        int hazeColor;
        switch (time) {
            case DAWN:      hazeColor = Color.argb(100, 255, 180, 100); break;
            case MORNING:   hazeColor = Color.argb( 70, 200, 230, 255); break;
            case NOON:      hazeColor = Color.argb( 60, 220, 240, 255); break;
            case AFTERNOON: hazeColor = Color.argb( 80, 255, 210, 140); break;
            case SUNSET:    hazeColor = Color.argb(130, 255, 140,  60); break;
            case DUSK:      hazeColor = Color.argb( 70,  80,  30, 100); break;
            default:        hazeColor = Color.argb( 25,  40,  50,  80); break;
        }
        if (weather == WeatherManager.Condition.STORMY
                || weather == WeatherManager.Condition.RAINY) {
            hazeColor = Color.argb(90, 60, 70, 90);
        }

        float hazeH = horizonY * 0.08f;
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, horizonY - hazeH, 0, horizonY + hazeH,
                Color.TRANSPARENT, hazeColor, Shader.TileMode.MIRROR));
        c.drawRect(0, horizonY - hazeH, w, horizonY + hazeH, p);
    }

    // ── Sea ───────────────────────────────────────────────────────────────────

    private static void drawSea(Canvas c, int w, int h, float horizonY,
                                  TimeOfDay time,
                                  WeatherManager.Condition weather,
                                  long seed) {
        int seaTop, seaBottom;
        switch (time) {
            case DAWN:      seaTop = 0xFF200530; seaBottom = 0xFF080218; break;
            case MORNING:   seaTop = 0xFF0D60B0; seaBottom = 0xFF082848; break;
            case NOON:      seaTop = 0xFF08A0D0; seaBottom = 0xFF045070; break;
            case AFTERNOON: seaTop = 0xFF1068C0; seaBottom = 0xFF083058; break;
            case SUNSET:    seaTop = 0xFF280640; seaBottom = 0xFF0A0218; break;
            case DUSK:      seaTop = 0xFF080415; seaBottom = 0xFF03020C; break;
            default:        seaTop = 0xFF040210; seaBottom = 0xFF010108; break;
        }
        if (weather == WeatherManager.Condition.STORMY
                || weather == WeatherManager.Condition.RAINY) {
            seaTop   = blendColor(seaTop,   0xFF181C28, 0.65f);
            seaBottom = blendColor(seaBottom, 0xFF0C1018, 0.65f);
        }

        Paint p = new Paint();
        p.setShader(new LinearGradient(0, horizonY, 0, h,
                seaTop, seaBottom, Shader.TileMode.CLAMP));
        c.drawRect(0, horizonY, w, h, p);
        p.setShader(null);

        // Subtle wave lines (more visible near viewer, fade to horizon)
        drawWaveLines(c, w, h, horizonY, seaTop, seed);

        // Sun / moon reflection shimmer on water
        if (weather != WeatherManager.Condition.STORMY) {
            drawSeaReflection(c, w, h, horizonY, time, weather);
        }
    }

    private static void drawWaveLines(Canvas c, int w, int h, float horizonY,
                                       int seaColor, long seed) {
        Random rnd = new Random(seed + 5);
        Paint wp = new Paint(Paint.ANTI_ALIAS_FLAG);
        wp.setStyle(Paint.Style.STROKE);

        int numLines = 14;
        for (int i = 0; i < numLines; i++) {
            float t = (i + 1f) / (numLines + 1); // 0=near horizon, 1=near viewer
            float y = horizonY + t * (h - horizonY);
            float perspective = t * t; // squared = more lines near viewer
            int alpha = (int)(perspective * 28 + 4);

            wp.setColor(Color.argb(alpha, 180, 215, 245));
            wp.setStrokeWidth(0.5f + perspective * 1.2f);

            // Slight horizontal wobble
            double phase = rnd.nextDouble() * Math.PI * 2;
            Path wave = new Path();
            wave.moveTo(0, y);
            int steps = 6;
            for (int s = 1; s <= steps; s++) {
                float x0 = (float)(s - 1) * w / steps;
                float x1 = (float) s * w / steps;
                float wobble = (float)(Math.sin(y * 0.06 + phase + s * 0.8) * 2.5 * perspective);
                wave.quadTo(x0 + (x1 - x0) * 0.5f, y + wobble, x1, y);
            }
            c.drawPath(wave, wp);
        }
    }

    private static void drawSeaReflection(Canvas c, int w, int h, float horizonY,
                                           TimeOfDay time,
                                           WeatherManager.Condition weather) {
        float rx;
        int refColor;
        switch (time) {
            case DAWN:
                rx = 0.18f; refColor = Color.argb(70, 255, 140, 60); break;
            case MORNING:
                rx = 0.15f; refColor = Color.argb(55, 255, 250, 200); break;
            case NOON:
                rx = 0.58f; refColor = Color.argb(50, 255, 255, 220); break;
            case AFTERNOON:
                rx = 0.78f; refColor = Color.argb(55, 255, 220, 150); break;
            case SUNSET:
                rx = 0.82f; refColor = Color.argb(100, 255, 120, 30); break;
            case NIGHT: case DUSK:
                rx = 0.72f; refColor = Color.argb(35, 200, 215, 255); break;
            default: return;
        }

        float x = rx * w;
        float refW = w * 0.12f;

        Paint rp = new Paint();
        // Vertical shimmer column from horizon to bottom
        rp.setShader(new LinearGradient(0, horizonY, 0, h,
                refColor, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        // Horizontal taper (wider near viewer)
        // Draw as trapezoid using path
        Path refPath = new Path();
        refPath.moveTo(x - refW * 0.3f, horizonY);
        refPath.lineTo(x + refW * 0.3f, horizonY);
        refPath.lineTo(x + refW, h);
        refPath.lineTo(x - refW, h);
        refPath.close();
        c.drawPath(refPath, rp);
        rp.setShader(null);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  SCENE EFFECTS (drawn before glass)
    // ═══════════════════════════════════════════════════════════════════════

    private static void addSceneEffects(Bitmap bmp, int w, int h, float horizonY,
                                         TimeOfDay time,
                                         WeatherManager.Condition weather,
                                         long seed) {
        Canvas c = new Canvas(bmp);
        boolean isDay = time != TimeOfDay.NIGHT && time != TimeOfDay.DUSK;

        // Water sparkles (CLEAR, day)
        if (weather == WeatherManager.Condition.CLEAR && isDay) {
            drawWaterSparkles(c, w, h, horizonY, seed);
        }

        // Birds (CLEAR, day)
        if (weather == WeatherManager.Condition.CLEAR && isDay) {
            drawBirds(c, w, horizonY, seed);
        }

        // Leaves (all weather, count varies)
        drawLeaves(c, w, h, weather, seed);

        // Stars (night / dusk — only in sky)
        if (!isDay) {
            drawStars(c, w, horizonY, seed);
        }
    }

    // ── Water sparkles ────────────────────────────────────────────────────────

    private static void drawWaterSparkles(Canvas c, int w, int h,
                                           float horizonY, long seed) {
        Random rnd = new Random(seed * 3 + 7);
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        sp.setStrokeCap(Paint.Cap.ROUND);

        int count = 14 + rnd.nextInt(12);
        for (int i = 0; i < count; i++) {
            float x  = (0.03f + rnd.nextFloat() * 0.94f) * w;
            float y  = horizonY + rnd.nextFloat() * (h - horizonY) * 0.80f;
            float sz = (2.5f  + rnd.nextFloat() * 4.5f) * (w / 300f);
            int   al = 110 + rnd.nextInt(130);

            sp.setStyle(Paint.Style.STROKE);
            sp.setStrokeWidth(sz * 0.28f);
            sp.setColor(Color.argb(al, 255, 252, 220));
            c.drawLine(x - sz, y, x + sz, y, sp);
            c.drawLine(x, y - sz, x, y + sz, sp);
            float d = sz * 0.55f;
            sp.setStrokeWidth(sz * 0.18f);
            sp.setColor(Color.argb(al * 2/3, 255, 252, 220));
            c.drawLine(x-d, y-d, x+d, y+d, sp);
            c.drawLine(x+d, y-d, x-d, y+d, sp);
            sp.setStyle(Paint.Style.FILL);
            sp.setColor(Color.argb(Math.min(255, al + 60), 255, 255, 245));
            c.drawCircle(x, y, sz * 0.22f, sp);
        }
    }

    // ── Birds ─────────────────────────────────────────────────────────────────
    // Drawn as tiny distant flocks near the horizon — static small specks
    // read as "far-away seagulls" and don't look frozen the way close V-shapes do.

    private static void drawBirds(Canvas c, int w, float horizonY, long seed) {
        Random rnd = new Random(seed);
        Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
        bp.setStyle(Paint.Style.STROKE);
        bp.setStrokeCap(Paint.Cap.ROUND);

        int numGroups = 2 + rnd.nextInt(2);
        for (int g = 0; g < numGroups; g++) {
            // Groups clustered in the lower 45% of the sky — near the horizon
            float gx = (0.07f + rnd.nextFloat() * 0.86f) * w;
            float gy = horizonY * (0.52f + rnd.nextFloat() * 0.38f);
            float proximity = gy / horizonY; // ~0.52–0.90: always distant

            int count = 4 + rnd.nextInt(6);
            for (int i = 0; i < count; i++) {
                float bx  = gx + (rnd.nextFloat() - 0.5f) * w * 0.09f;
                float by  = gy + (rnd.nextFloat() - 0.5f) * horizonY * 0.045f;
                float sz  = (1.8f + proximity * 2.8f) * (w / 500f); // very small
                float dip = sz * (0.08f + rnd.nextFloat() * 0.18f);

                bp.setStrokeWidth(Math.max(0.5f, sz * 0.24f));
                bp.setColor(Color.argb(70 + rnd.nextInt(55), 8, 8, 18));

                Path bird = new Path();
                bird.moveTo(bx - sz, by + dip);
                bird.quadTo(bx - sz * 0.40f, by - sz * 0.22f, bx, by);
                bird.quadTo(bx + sz * 0.40f, by - sz * 0.22f, bx + sz, by + dip);
                c.drawPath(bird, bp);
            }
        }
    }

    // ── Leaves ────────────────────────────────────────────────────────────────

    private static void drawLeaves(Canvas c, int w, int h,
                                    WeatherManager.Condition weather, long seed) {
        Random rnd = new Random(seed + 11);
        int count;
        switch (weather) {
            case STORMY: count = 12 + rnd.nextInt(8); break;
            case WINDY:  count = 7  + rnd.nextInt(5); break;
            case RAINY:  count = 3  + rnd.nextInt(4); break;
            default:     count = 1  + rnd.nextInt(3); break;
        }
        int[] colors = {
            Color.argb(185,  55,  98, 25),
            Color.argb(185,  80, 120, 22),
            Color.argb(185, 140, 108, 18),
            Color.argb(185, 100,  68, 18),
            Color.argb(185, 170, 130, 20),
        };
        for (int i = 0; i < count; i++) {
            float lx    = rnd.nextFloat() * w;
            float ly    = rnd.nextFloat() * h;
            float angle = rnd.nextFloat() * 360f;
            float size  = (5f + rnd.nextFloat() * 9f) * (w / 360f);
            drawSingleLeaf(c, lx, ly, size, angle, colors[rnd.nextInt(colors.length)]);
        }
    }

    private static void drawSingleLeaf(Canvas c, float cx, float cy,
                                        float size, float angle, int color) {
        c.save(); c.translate(cx, cy); c.rotate(angle);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL); p.setColor(color);
        Path leaf = new Path();
        leaf.moveTo(0, -size);
        leaf.quadTo( size*0.55f, -size*0.30f,  size*0.50f,  size*0.10f);
        leaf.quadTo( size*0.40f,  size*0.60f,  0,           size*0.65f);
        leaf.quadTo(-size*0.40f,  size*0.60f, -size*0.50f,  size*0.10f);
        leaf.quadTo(-size*0.55f, -size*0.30f,  0,          -size);
        leaf.close(); c.drawPath(leaf, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(0.7f);
        p.setColor(Color.argb(80,
                Math.min(255, Color.red(color)+40),
                Math.min(255, Color.green(color)+30), 0));
        c.drawLine(0, -size*0.8f, 0, size*0.55f, p);
        c.restore();
    }

    // ── Stars ─────────────────────────────────────────────────────────────────

    private static void drawStars(Canvas c, int w, float skyH, long seed) {
        Random pos  = new Random(seed + 42);
        Random twkl = new Random(seed);      // same seed → varies each minute

        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        int count = 30 + pos.nextInt(20);

        for (int i = 0; i < count; i++) {
            float x    = pos.nextFloat() * w;
            float y    = pos.nextFloat() * skyH * 0.90f;
            float base = 0.25f + pos.nextFloat() * 0.75f;
            boolean big = pos.nextFloat() < 0.12f;
            boolean twinkles = pos.nextFloat() < 0.28f;

            float bright = twinkles
                    ? base * (0.4f + Math.abs((float)Math.sin(twkl.nextFloat() * Math.PI * 2)) * 0.6f)
                    : base;
            int alpha = (int)(bright * 220);
            float r   = (big ? 1.9f : 0.9f) * (w / 330f);

            if (big) {
                sp.setStyle(Paint.Style.FILL);
                sp.setColor(Color.argb(alpha/4, 210, 225, 255));
                c.drawCircle(x, y, r*3.2f, sp);
                sp.setStyle(Paint.Style.STROKE);
                sp.setStrokeWidth(r*0.4f);
                sp.setColor(Color.argb(alpha/2, 225, 238, 255));
                c.drawLine(x-r*2.6f,y,x+r*2.6f,y,sp);
                c.drawLine(x,y-r*2.6f,x,y+r*2.6f,sp);
            }
            sp.setStyle(Paint.Style.FILL);
            sp.setColor(Color.argb(alpha, 240, 248, 255));
            c.drawCircle(x, y, r, sp);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  GLASS
    // ═══════════════════════════════════════════════════════════════════════

    private static Bitmap applyGlassDistortion(Bitmap src, int w, int h) {
        int[] srcPx = new int[w * h];
        src.getPixels(srcPx, 0, w, 0, 0, w, h);
        int[] dstPx = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dx1 = 3.2*Math.sin(y*0.014+0.53) + 1.8*Math.cos(x*0.011+y*0.007);
                double dy1 = 2.8*Math.cos(x*0.013+1.21) + 1.6*Math.sin(y*0.010+x*0.006);
                double dx2 = 0.9*Math.sin(x*0.048+y*0.031);
                double dy2 = 0.7*Math.cos(y*0.042+x*0.022);
                int sx = clamp((int)(x+dx1+dx2),0,w-1);
                int sy = clamp((int)(y+dy1+dy2),0,h-1);
                dstPx[y*w+x] = srcPx[sy*w+sx];
            }
        }
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(dstPx, 0, w, 0, 0, w, h);
        return result;
    }

    private static void drawGlassSurface(Canvas c, int w, int h) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.argb(20, 25, 70, 15)); c.drawRect(0,0,w,h,p);
        p.setShader(new RadialGradient(w/2f,h/2f,(float)Math.max(w,h)*0.62f,
                Color.TRANSPARENT,Color.argb(70,0,0,0),Shader.TileMode.CLAMP));
        c.drawRect(0,0,w,h,p); p.setShader(null);
        p.setShader(new LinearGradient(0,0,0,h*0.10f,
                Color.argb(24,255,255,255),Color.TRANSPARENT,Shader.TileMode.CLAMP));
        c.drawRect(0,0,w,h*0.10f,p); p.setShader(null);
        drawBubbles(c,w,h,p);
        drawGlassStreaks(c,w,h);
    }

    private static void drawBubbles(Canvas c, int w, int h, Paint p) {
        float[][] bb = {{0.14f,0.11f,0.013f},{0.73f,0.27f,0.009f},{0.38f,0.54f,0.016f},
                        {0.85f,0.66f,0.010f},{0.22f,0.82f,0.008f},{0.62f,0.08f,0.010f},
                        {0.47f,0.43f,0.007f},{0.08f,0.63f,0.012f},{0.56f,0.77f,0.009f},
                        {0.91f,0.44f,0.006f}};
        for (float[] b : bb) {
            float cx=b[0]*w,cy=b[1]*h,r=b[2]*Math.min(w,h);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(0.8f);
            p.setColor(Color.argb(55,180,210,180)); c.drawCircle(cx,cy,r,p);
            p.setColor(Color.argb(30,0,10,0));      c.drawCircle(cx,cy,r*0.8f,p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(65,255,255,255)); c.drawCircle(cx-r*0.32f,cy-r*0.32f,r*0.35f,p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private static void drawGlassStreaks(Canvas c, int w, int h) {
        Paint sp=new Paint(Paint.ANTI_ALIAS_FLAG);
        sp.setStyle(Paint.Style.STROKE); sp.setStrokeWidth(0.9f);
        sp.setColor(Color.argb(18,255,255,255));
        Path s1=new Path(); s1.moveTo(w*0.28f,0);
        s1.cubicTo(w*0.31f,h*0.28f,w*0.26f,h*0.55f,w*0.30f,h); c.drawPath(s1,sp);
        Path s2=new Path(); s2.moveTo(w*0.68f,0);
        s2.cubicTo(w*0.71f,h*0.32f,w*0.67f,h*0.62f,w*0.70f,h); c.drawPath(s2,sp);
        sp.setColor(Color.argb(10,255,255,255)); sp.setStrokeWidth(1.5f);
        Path s3=new Path(); s3.moveTo(w*0.50f,0);
        s3.cubicTo(w*0.52f,h*0.40f,w*0.49f,h*0.70f,w*0.51f,h); c.drawPath(s3,sp);
    }

    // ── Rain on glass ─────────────────────────────────────────────────────────

    private static void drawRainOnGlass(Canvas c, int w, int h, long seed) {
        Random rnd=new Random(seed);
        Paint dp=new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i=0;i<18;i++) {
            float x=rnd.nextFloat()*w,y=rnd.nextFloat()*h;
            float r=(2f+rnd.nextFloat()*5f)*(w/240f);
            dp.setStyle(Paint.Style.FILL);
            dp.setColor(Color.argb(70,180,210,240));
            c.drawOval(x-r*0.55f,y-r,x+r*0.55f,y+r*0.35f,dp);
            dp.setColor(Color.argb(110,255,255,255));
            c.drawOval(x-r*0.28f,y-r*0.80f,x+r*0.05f,y-r*0.20f,dp);
        }
        Paint sp=new Paint(Paint.ANTI_ALIAS_FLAG);
        sp.setStyle(Paint.Style.STROKE); sp.setColor(Color.argb(50,180,210,255));
        for (int i=0;i<22;i++) {
            float x=rnd.nextFloat()*w,y0=rnd.nextFloat()*h*0.7f;
            float len=(15+rnd.nextFloat()*90)*(h/400f);
            sp.setStrokeWidth(0.7f+rnd.nextFloat()*1.4f);
            Path s=new Path(); s.moveTo(x,y0);
            s.lineTo(x+rnd.nextFloat()*5-2.5f,y0+len);
            c.drawPath(s,sp);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  DATE / TIME
    // ═══════════════════════════════════════════════════════════════════════

    private static void drawTimeDate(Canvas c, int w, int h) {
        Calendar cal=Calendar.getInstance();
        String timeStr=String.format("%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY),cal.get(Calendar.MINUTE));
        String[] days={"Dom","Lun","Mar","Mer","Gio","Ven","Sab"};
        String[] months={"Gen","Feb","Mar","Apr","Mag","Giu",
                          "Lug","Ago","Set","Ott","Nov","Dic"};
        String dateStr=days[cal.get(Calendar.DAY_OF_WEEK)-1]+"  "
                +cal.get(Calendar.DAY_OF_MONTH)+" "+months[cal.get(Calendar.MONTH)];
        float sh=h*0.018f;
        Paint tp=new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.WHITE);
        tp.setTypeface(Typeface.create(Typeface.SANS_SERIF,Typeface.BOLD));
        tp.setTextSize(h*0.30f); tp.setLetterSpacing(0.04f);
        tp.setShadowLayer(sh*1.8f,0,sh,Color.argb(160,0,0,0));
        float tw=tp.measureText(timeStr);
        c.drawText(timeStr,(w-tw)/2f,h*0.56f,tp);
        Paint dp=new Paint(Paint.ANTI_ALIAS_FLAG);
        dp.setColor(Color.argb(230,255,255,255));
        dp.setTypeface(Typeface.create(Typeface.SANS_SERIF,Typeface.NORMAL));
        dp.setTextSize(h*0.130f); dp.setLetterSpacing(0.18f);
        dp.setShadowLayer(sh*1.4f,0,sh*0.7f,Color.argb(140,0,0,0));
        float dw=dp.measureText(dateStr);
        c.drawText(dateStr,(w-dw)/2f,h*0.56f+h*0.165f,dp);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  WINDOW FRAME
    // ═══════════════════════════════════════════════════════════════════════

    private static void drawWindowFrame(Canvas c, int w, int h) {
        float t=Math.min(w,h)*0.085f;
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.argb(160,10,8,5));
        c.drawRect(0,0,w,2.5f,p);c.drawRect(0,h-2.5f,w,h,p);
        c.drawRect(0,0,2.5f,h,p);c.drawRect(w-2.5f,0,w,h,p);
        p.setShader(new LinearGradient(0,0,0,t,
                Color.argb(255,120,82,42),Color.argb(255,72,46,20),Shader.TileMode.CLAMP));
        c.drawRect(0,0,w,t,p);
        p.setShader(new LinearGradient(0,h-t,0,h,
                Color.argb(255,60,38,16),Color.argb(255,90,60,28),Shader.TileMode.CLAMP));
        c.drawRect(0,h-t,w,h,p);
        p.setShader(new LinearGradient(0,0,t,0,
                Color.argb(255,110,74,36),Color.argb(255,68,44,18),Shader.TileMode.CLAMP));
        c.drawRect(0,0,t,h,p);
        p.setShader(new LinearGradient(w-t,0,w,0,
                Color.argb(255,62,40,18),Color.argb(255,95,62,30),Shader.TileMode.CLAMP));
        c.drawRect(w-t,0,w,h,p); p.setShader(null);
        Paint gp=new Paint(); gp.setColor(Color.argb(22,200,150,80)); gp.setStrokeWidth(0.8f);
        for (float y=3f;y<t-2;y+=5.5f) {
            c.drawLine(t,y,w-t,y,gp); c.drawLine(t,h-y,w-t,h-y,gp);
        }
        for (float x=3f;x<t-2;x+=5.5f) {
            c.drawLine(x,t,x,h-t,gp); c.drawLine(w-x,t,w-x,h-t,gp);
        }
        Paint bp=new Paint(Paint.ANTI_ALIAS_FLAG);
        bp.setStyle(Paint.Style.STROKE); bp.setStrokeWidth(1.8f);
        bp.setColor(Color.argb(120,200,160,100));
        c.drawLine(t,t,w-t,t,bp); c.drawLine(t,t,t,h-t,bp);
        bp.setColor(Color.argb(140,20,12,5));
        c.drawLine(t,h-t,w-t,h-t,bp); c.drawLine(w-t,t,w-t,h-t,bp);
        p.setShader(new LinearGradient(0,0,0,t*1.4f,
                Color.argb(80,0,0,0),Color.TRANSPARENT,Shader.TileMode.CLAMP));
        c.drawRect(t,t,w-t,t*2.4f,p);
        p.setShader(new LinearGradient(0,h-t*1.4f,0,h-t,
                Color.TRANSPARENT,Color.argb(70,0,0,0),Shader.TileMode.CLAMP));
        c.drawRect(t,h-t*2.4f,w-t,h-t,p); p.setShader(null);
        p.setColor(Color.argb(255,55,35,14));
        c.drawRect(0,0,t,t,p);c.drawRect(w-t,0,w,t,p);
        c.drawRect(0,h-t,t,h,p);c.drawRect(w-t,h-t,w,h,p);
        Paint cp=new Paint(Paint.ANTI_ALIAS_FLAG); cp.setColor(Color.argb(80,180,140,80));
        float nr=t*0.22f,no=t*0.50f;
        c.drawCircle(no,no,nr,cp);c.drawCircle(w-no,no,nr,cp);
        c.drawCircle(no,h-no,nr,cp);c.drawCircle(w-no,h-no,nr,cp);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  UTILITIES
    // ═══════════════════════════════════════════════════════════════════════

    private static int blendColor(int a, int b, float t) {
        int ra=(a>>16)&0xFF, ga=(a>>8)&0xFF, ba_=a&0xFF;
        int rb=(b>>16)&0xFF, gb=(b>>8)&0xFF, bb_=b&0xFF;
        int r=(int)(ra+(rb-ra)*t), g=(int)(ga+(gb-ga)*t), bl=(int)(ba_+(bb_-ba_)*t);
        return 0xFF000000|(r<<16)|(g<<8)|bl;
    }

    private static int scaleAlpha(int color, float factor) {
        int a=(int)(((color>>24)&0xFF)*factor);
        return (color&0x00FFFFFF)|(a<<24);
    }

    private static int clamp(int v,int min,int max){return v<min?min:(v>max?max:v);}
}
