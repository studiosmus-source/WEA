package com.studiosmus.wea;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.Calendar;
import java.util.Random;

public class WidgetRenderer {

    // ─── Photo cache (avoid reloading the JPEG every 2 s) ────────────────────
    private static Bitmap sCachedPhoto = null;
    private static int    sCachedW     = -1;
    private static int    sCachedH     = -1;

    public enum TimeOfDay {
        DAWN, MORNING, NOON, AFTERNOON, SUNSET, DUSK, NIGHT
    }

    // ─── Atmospheric state ───────────────────────────────────────────────────
    // Single continuous source of truth for every weather-driven parameter.

    static final class Atmo {
        final float wind;       // 0=calm .. 1=storm
        final float rain;       // 0=dry  .. 1=heavy
        final float cloud;      // 0=clear .. 1=overcast
        final float fog;        // 0=none .. 1=thick
        final float windAngle;  // degrees, 0=vertical right-lean positive

        Atmo(WeatherManager.Condition c) {
            float wi, ra, cl, fo, wa;
            switch (c) {
                case CLEAR:  wi=0.05f; ra=0.00f; cl=0.00f; fo=0.05f; wa=2f;  break;
                case CLOUDY: wi=0.20f; ra=0.00f; cl=0.70f; fo=0.30f; wa=4f;  break;
                case WINDY:  wi=0.75f; ra=0.00f; cl=0.40f; fo=0.10f; wa=12f; break;
                case RAINY:  wi=0.40f; ra=0.55f; cl=0.88f; fo=0.50f; wa=7f;  break;
                default:     wi=0.90f; ra=1.00f; cl=1.00f; fo=0.65f; wa=18f; break;
            }
            wind=wi; rain=ra; cloud=cl; fog=fo; windAngle=wa;
        }
    }

    // ─── Entry point ─────────────────────────────────────────────────────────

    public static Bitmap render(Context ctx, int dpWidth, int dpHeight) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int w = Math.min(900, Math.max(160, (int)(dpWidth  * density)));
        int h = Math.min(600, Math.max(100, (int)(dpHeight * density)));

        TimeOfDay time = getTimeOfDay();
        WeatherManager.Condition weather = WeatherManager.getCondition(ctx);
        Atmo atmo = new Atmo(weather);
        long now      = System.currentTimeMillis();
        long timeSeed = now / (60L   * 1000);
        long hourSeed = now / (3600L * 1000);
        float horizonY = h * 0.45f;

        // ── TEST: sfondo bianco neutro per vedere solo gli effetti meteo ────────
        Bitmap rawPhoto = null; // foto disabilitata temporaneamente
        Bitmap base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(base).drawColor(0xFFE8EDF2); // grigio-azzurro chiaro
        addSceneEffects(base, false, w, h, horizonY, time, atmo, hourSeed, now);

        Bitmap distorted = applyGlassDistortion(base, w, h);
        // base kept alive: used as clean scene source for lens-drop sampling

        Canvas canvas = new Canvas(distorted);
        drawGlassSurface(canvas, w, h);

        if (atmo.rain > 0.1f) {
            drawRainOnGlass(canvas, base, w, h, now, hourSeed, atmo);
        }

        base.recycle();

        // Lightning flash for storms — a brief bright veil, ~every 36 s
        if (atmo.rain > 0.85f) {
            long lightPeriod = 28000L + (hourSeed % 9) * 3500L;
            long lPhase = now % lightPeriod;
            if (lPhase < 280L) {
                float intensity = 1.0f - lPhase / 280f;
                Paint lp = new Paint();
                lp.setColor(Color.argb((int)(48 * intensity), 248, 252, 255));
                canvas.drawRect(0, 0, w, h, lp);
            }
        }

        drawTimeDate(canvas, w, h);
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
    //  PHOTO BACKGROUND
    // ═══════════════════════════════════════════════════════════════════════

    private static Bitmap loadScaledPhoto(Context ctx, int w, int h) {
        if (sCachedPhoto != null && sCachedW == w && sCachedH == h) return sCachedPhoto;
        Resources res = ctx.getResources();
        int id = res.getIdentifier("porto_venere", "drawable", ctx.getPackageName());
        if (id == 0) return null;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeResource(res, id, opts);
        int iw = opts.outWidth, ih = opts.outHeight;
        int sample = 1;
        while (iw / sample > w * 2 && ih / sample > h * 2) sample *= 2;
        opts.inJustDecodeBounds = false;
        opts.inSampleSize = sample;
        Bitmap raw = BitmapFactory.decodeResource(res, id, opts);
        if (raw == null) return null;

        // Centre-crop to widget aspect ratio
        Bitmap scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        float sc = Math.max((float) w / raw.getWidth(), (float) h / raw.getHeight());
        float sw = raw.getWidth() * sc, sh = raw.getHeight() * sc;
        Matrix m = new Matrix();
        m.setScale(sc, sc);
        m.postTranslate((w - sw) / 2f, (h - sh) / 2f);
        new Canvas(scaled).drawBitmap(raw, m, new Paint(Paint.FILTER_BITMAP_FLAG));
        raw.recycle();

        if (sCachedPhoto != null) sCachedPhoto.recycle();
        sCachedPhoto = scaled;
        sCachedW = w; sCachedH = h;
        return scaled;
    }

    // 5×4 colour matrix: time-of-day grading + weather desaturation/darkening.
    private static float[] buildColorMatrix(TimeOfDay time, Atmo atmo) {
        float[] m;
        switch (time) {
            case DAWN:
                m = new float[]{
                    0.50f,0.14f,0.02f,0,18,   // warm orange, dim
                    0.04f,0.34f,0.02f,0, 4,
                    0.00f,0.02f,0.18f,0, 0,
                    0,0,0,1,0}; break;
            case MORNING:
                m = new float[]{
                    0.90f,0.07f,0.00f,0,10,   // cool, slightly warm
                    0.00f,0.90f,0.04f,0, 6,
                    0.00f,0.04f,0.96f,0, 0,
                    0,0,0,1,0}; break;
            case NOON:
                m = new float[]{
                    1.00f,0.00f,0.00f,0, 0,   // natural
                    0.00f,1.00f,0.00f,0, 4,
                    0.00f,0.04f,0.98f,0, 8,
                    0,0,0,1,0}; break;
            case AFTERNOON:
                m = new float[]{
                    1.06f,0.05f,0.00f,0,10,   // warm afternoon light
                    0.00f,0.96f,0.00f,0, 2,
                    0.00f,0.00f,0.86f,0, 0,
                    0,0,0,1,0}; break;
            case SUNSET:
                m = new float[]{
                    0.88f,0.20f,0.02f,0,24,   // deep amber-red
                    0.00f,0.60f,0.05f,0, 8,
                    0.00f,0.02f,0.30f,0, 0,
                    0,0,0,1,0}; break;
            case DUSK:
                m = new float[]{
                    0.18f,0.05f,0.08f,0, 4,   // purple-blue, dark
                    0.00f,0.16f,0.08f,0, 2,
                    0.04f,0.08f,0.44f,0, 0,
                    0,0,0,1,0}; break;
            default: // NIGHT
                m = new float[]{
                    0.04f,0.01f,0.02f,0, 0,
                    0.01f,0.04f,0.02f,0, 0,
                    0.02f,0.03f,0.12f,0, 3,
                    0,0,0,1,0}; break;
        }
        // Weather: desaturate for clouds, darken for rain
        ColorMatrix cm = new ColorMatrix(m);
        if (atmo.cloud > 0.08f) {
            ColorMatrix ds = new ColorMatrix();
            ds.setSaturation(1f - atmo.cloud * 0.62f);
            cm.postConcat(ds);
        }
        if (atmo.rain > 0.08f) {
            float d = 1f - atmo.rain * 0.35f;
            cm.postConcat(new ColorMatrix(new float[]{
                d,0,0,0,0,  0,d,0,0,0,  0,0,d,0,0,  0,0,0,1,0}));
        }
        return cm.getArray();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PROCEDURAL BACKGROUND (fallback when photo unavailable)
    // ═══════════════════════════════════════════════════════════════════════

    private static Bitmap generateSeaSky(int w, int h, float horizonY,
                                          TimeOfDay time, Atmo atmo, long seed) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        drawSky(c, w, h, horizonY, time, atmo);
        drawSunMoonGlow(c, w, horizonY, time, atmo);
        drawCoastSilhouette(c, w, horizonY, time, atmo);
        drawHorizonHaze(c, w, horizonY, time, atmo);
        drawSea(c, w, h, horizonY, time, atmo, seed);
        return bmp;
    }

    private static void drawSky(Canvas c, int w, int h, float horizonY,
                                 TimeOfDay time, Atmo atmo) {
        int[] stops;
        switch (time) {
            case DAWN:      stops = new int[]{0xFF0D0420, 0xFF5C1030, 0xFFD04018}; break;
            case MORNING:   stops = new int[]{0xFF082868, 0xFF1060C0, 0xFF90C8F0}; break;
            case NOON:      stops = new int[]{0xFF0250A8, 0xFF1878D0, 0xFF9DD5F5}; break;
            case AFTERNOON: stops = new int[]{0xFF103888, 0xFF2068C0, 0xFFDFB860}; break;
            case SUNSET:    stops = new int[]{0xFF2A0848, 0xFF901820, 0xFFE85010}; break;
            case DUSK:      stops = new int[]{0xFF060818, 0xFF160830, 0xFF2E1248}; break;
            default:        stops = new int[]{0xFF030308, 0xFF060D1C, 0xFF0C1530}; break;
        }
        float cb = atmo.cloud * 0.65f;
        if (cb > 0.01f) {
            stops[0] = blendColor(stops[0], 0xFF505860, cb);
            stops[1] = blendColor(stops[1], 0xFF606870, cb * 0.95f);
            stops[2] = blendColor(stops[2], 0xFF909898, cb * 0.85f);
        }
        float midY = horizonY * 0.5f;
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, 0, midY, stops[0], stops[1], Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, midY, p);
        p.setShader(new LinearGradient(0, midY, 0, horizonY, stops[1], stops[2], Shader.TileMode.CLAMP));
        c.drawRect(0, midY, w, horizonY, p);
        p.setShader(null);
    }

    private static void drawSunMoonGlow(Canvas c, int w, float horizonY,
                                         TimeOfDay time, Atmo atmo) {
        if (atmo.cloud > 0.92f) return;
        float ca = 1.0f - atmo.cloud * 0.55f;
        Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);

        if (time == TimeOfDay.NIGHT || time == TimeOfDay.DUSK) {
            float mx = w * 0.72f, my = horizonY * 0.28f;
            gp.setShader(new RadialGradient(mx, my, w * 0.18f,
                    new int[]{scaleAlpha(Color.argb(100, 220, 230, 255), ca),
                               scaleAlpha(Color.argb(40,  180, 200, 240), ca),
                               Color.TRANSPARENT},
                    new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, horizonY, gp);
            return;
        }
        float sx, sy, r;
        int g0, g1;
        switch (time) {
            case DAWN:
                sx=w*0.18f; sy=horizonY*1.05f; r=w*0.50f;
                g0=Color.argb(130,255,140,40);  g1=Color.argb(60,255,100,10);  break;
            case MORNING:
                sx=w*0.15f; sy=horizonY*0.35f; r=w*0.32f;
                g0=Color.argb(90,255,250,200);  g1=Color.argb(30,255,240,160); break;
            case NOON:
                sx=w*0.60f; sy=horizonY*0.08f; r=w*0.38f;
                g0=Color.argb(75,255,255,230);  g1=Color.argb(20,255,255,200); break;
            case AFTERNOON:
                sx=w*0.78f; sy=horizonY*0.22f; r=w*0.34f;
                g0=Color.argb(90,255,220,160);  g1=Color.argb(25,255,200,100); break;
            default: // SUNSET
                sx=w*0.82f; sy=horizonY*0.92f; r=w*0.55f;
                g0=Color.argb(160,255,100,20);  g1=Color.argb(60,200,40,5);   break;
        }
        g0 = scaleAlpha(g0, ca); g1 = scaleAlpha(g1, ca);
        gp.setShader(new RadialGradient(sx, sy, r,
                new int[]{g0, g1, Color.TRANSPARENT},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, Math.min(horizonY*1.2f, horizonY+r*0.2f), gp);
    }

    private static void drawCoastSilhouette(Canvas c, int w, float horizonY,
                                             TimeOfDay time, Atmo atmo) {
        int color;
        switch (time) {
            case DAWN:      color = Color.argb(120,  25, 10, 35); break;
            case MORNING:   color = Color.argb(100,  20, 55, 90); break;
            case NOON:      color = Color.argb( 90,  15, 60,100); break;
            case AFTERNOON: color = Color.argb(100,  25, 50, 85); break;
            case SUNSET:    color = Color.argb(140,  30,  8, 20); break;
            case DUSK:      color = Color.argb(150,   8,  4, 15); break;
            default:        color = Color.argb(160,   4,  3, 10); break;
        }
        color = scaleAlpha(color, 1.0f - atmo.fog * 0.70f);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        float coast = horizonY - hf(horizonY, 0.05f);
        Path path = new Path();
        path.moveTo(0, horizonY);
        path.cubicTo(w*0.12f, coast-hf(horizonY,0.04f), w*0.22f, coast-hf(horizonY,0.06f), w*0.35f, coast-hf(horizonY,0.02f));
        path.cubicTo(w*0.48f, coast+hf(horizonY,0.01f), w*0.58f, coast-hf(horizonY,0.03f), w*0.70f, coast-hf(horizonY,0.01f));
        path.cubicTo(w*0.82f, coast+hf(horizonY,0.00f), w*0.92f, coast-hf(horizonY,0.02f), w,       coast+hf(horizonY,0.01f));
        path.lineTo(w, horizonY);
        path.close();
        c.drawPath(path, p);
    }

    private static float hf(float base, float frac) { return base * frac; }

    private static void drawHorizonHaze(Canvas c, int w, float horizonY,
                                         TimeOfDay time, Atmo atmo) {
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
        if (atmo.fog > 0.25f) {
            int fogA = (int)(90 * atmo.fog);
            hazeColor = blendColorARGB(hazeColor, Color.argb(fogA, 60, 70, 90), atmo.fog * 0.65f);
        }
        float hazeH = horizonY * 0.08f;
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, horizonY-hazeH, 0, horizonY+hazeH,
                Color.TRANSPARENT, hazeColor, Shader.TileMode.MIRROR));
        c.drawRect(0, horizonY-hazeH, w, horizonY+hazeH, p);
    }

    private static void drawSea(Canvas c, int w, int h, float horizonY,
                                  TimeOfDay time, Atmo atmo, long seed) {
        int seaTop, seaBot;
        switch (time) {
            case DAWN:      seaTop=0xFF200530; seaBot=0xFF080218; break;
            case MORNING:   seaTop=0xFF0D60B0; seaBot=0xFF082848; break;
            case NOON:      seaTop=0xFF08A0D0; seaBot=0xFF045070; break;
            case AFTERNOON: seaTop=0xFF1068C0; seaBot=0xFF083058; break;
            case SUNSET:    seaTop=0xFF280640; seaBot=0xFF0A0218; break;
            case DUSK:      seaTop=0xFF080415; seaBot=0xFF03020C; break;
            default:        seaTop=0xFF040210; seaBot=0xFF010108; break;
        }
        float rb = atmo.rain * 0.65f;
        if (rb > 0.01f) {
            seaTop = blendColor(seaTop, 0xFF181C28, rb);
            seaBot = blendColor(seaBot, 0xFF0C1018, rb);
        }
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, horizonY, 0, h, seaTop, seaBot, Shader.TileMode.CLAMP));
        c.drawRect(0, horizonY, w, h, p);
        p.setShader(null);
        drawWaveLines(c, w, h, horizonY, seaTop, seed);
        if (atmo.rain < 0.85f) drawSeaReflection(c, w, h, horizonY, time, atmo);
    }

    private static void drawWaveLines(Canvas c, int w, int h, float horizonY,
                                       int seaColor, long seed) {
        Random rnd = new Random(seed + 5);
        Paint wp = new Paint(Paint.ANTI_ALIAS_FLAG);
        wp.setStyle(Paint.Style.STROKE);
        int numLines = 14;
        for (int i = 0; i < numLines; i++) {
            float t = (i + 1f) / (numLines + 1);
            float y = horizonY + t * (h - horizonY);
            float persp = t * t;
            wp.setColor(Color.argb((int)(persp * 28 + 4), 180, 215, 245));
            wp.setStrokeWidth(0.5f + persp * 1.2f);
            double phase = rnd.nextDouble() * Math.PI * 2;
            Path wave = new Path();
            wave.moveTo(0, y);
            for (int s = 1; s <= 6; s++) {
                float x0 = (float)(s-1) * w / 6;
                float x1 = (float)s * w / 6;
                float wobble = (float)(Math.sin(y*0.06+phase+s*0.8)*2.5*persp);
                wave.quadTo(x0+(x1-x0)*0.5f, y+wobble, x1, y);
            }
            c.drawPath(wave, wp);
        }
    }

    private static void drawSeaReflection(Canvas c, int w, int h, float horizonY,
                                           TimeOfDay time, Atmo atmo) {
        float rx; int refColor;
        switch (time) {
            case DAWN:      rx=0.18f; refColor=Color.argb(70,255,140,60);   break;
            case MORNING:   rx=0.15f; refColor=Color.argb(55,255,250,200);  break;
            case NOON:      rx=0.58f; refColor=Color.argb(50,255,255,220);  break;
            case AFTERNOON: rx=0.78f; refColor=Color.argb(55,255,220,150);  break;
            case SUNSET:    rx=0.82f; refColor=Color.argb(100,255,120,30);  break;
            case NIGHT: case DUSK: rx=0.72f; refColor=Color.argb(35,200,215,255); break;
            default: return;
        }
        refColor = scaleAlpha(refColor, 1.0f - atmo.cloud * 0.60f);
        float x = rx * w, refW = w * 0.12f;
        Paint rp = new Paint();
        rp.setShader(new LinearGradient(0, horizonY, 0, h, refColor, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        Path refPath = new Path();
        refPath.moveTo(x-refW*0.3f, horizonY); refPath.lineTo(x+refW*0.3f, horizonY);
        refPath.lineTo(x+refW, h);              refPath.lineTo(x-refW, h);
        refPath.close();
        c.drawPath(refPath, rp);
        rp.setShader(null);
    }

    // ─── Rain in scene ────────────────────────────────────────────────────────
    // Positions stable via hourSeed; slideY gives smooth continuous motion.

    private static void drawRainInScene(Canvas c, int w, int h, float horizonY,
                                         Atmo atmo, long now) {
        float dx = (float) Math.tan(Math.toRadians(atmo.windAngle));
        long hourSeed = now / (3600L * 1000);
        Random rnd = new Random(hourSeed * 97 + 5);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);

        // Sky layer — very fine streaks (far distance)
        int skyN = (int)(20 + atmo.rain * 32);
        for (int i = 0; i < skyN; i++) {
            float startY = rnd.nextFloat() * horizonY;
            float x      = rnd.nextFloat() * w;
            float len    = (10 + rnd.nextFloat() * 28) * (h / 400f);
            float speed  = 380f + rnd.nextFloat() * 440f;
            float phase  = rnd.nextFloat();
            float y = slideY(startY, speed, phase, 750L, now, (int) horizonY);
            int al = (int)(9 * atmo.rain);
            p.setStrokeWidth(0.35f);
            p.setColor(Color.argb(al, 172, 192, 225));
            c.drawLine(x, y, x + dx * len, y + len, p);
        }

        // Sea layer — perspective-correct: thicker & more alpha near camera
        int seaN = (int)(16 + atmo.rain * 24);
        for (int i = 0; i < seaN; i++) {
            float startY = rnd.nextFloat() * (h - horizonY);
            float x      = rnd.nextFloat() * w;
            float dist   = startY / (h - horizonY);   // 0=horizon, 1=bottom
            float len    = (18 + rnd.nextFloat() * 52) * (0.35f + dist * 0.65f) * (h / 400f);
            float speed  = 400f + rnd.nextFloat() * 500f;
            float phase  = rnd.nextFloat();
            float y = horizonY + slideY(startY, speed, phase, 680L, now, (int)(h - horizonY));
            int al = (int)((8 + dist * 20) * atmo.rain);
            p.setStrokeWidth(0.32f + dist * 0.72f);
            p.setColor(Color.argb(al, 148, 178, 222));
            c.drawLine(x, y, x + dx * len, y + len, p);
        }
    }

    // ─── Rain / fog atmospheric mist ─────────────────────────────────────────

    private static void drawRainMist(Canvas c, int w, int h, float horizonY,
                                      Atmo atmo, long now) {
        // Only fog band at horizon — physically correct, no full-scene grey wash
        float fogStr = atmo.fog * 0.55f + atmo.rain * 0.18f;
        if (fogStr < 0.04f) return;
        float bandH = h * (0.04f + atmo.fog * 0.10f + atmo.rain * 0.04f);
        Paint mp = new Paint();
        mp.setShader(new LinearGradient(0, horizonY - bandH, 0, horizonY + bandH * 1.5f,
                Color.TRANSPARENT,
                Color.argb((int)(fogStr * 110), 172, 188, 215),
                Shader.TileMode.CLAMP));
        c.drawRect(0, horizonY - bandH, w, horizonY + bandH * 1.5f, mp);
        mp.setShader(null);
    }

    private static void drawRainRipples(Canvas c, int w, int h, float horizonY,
                                         Atmo atmo, long now) {
        Random rnd = new Random(now / 250L + 13);
        Paint ep = new Paint(Paint.ANTI_ALIAS_FLAG);
        ep.setStyle(Paint.Style.STROKE);
        int count = (int)(8 + atmo.rain * 16);
        for (int i = 0; i < count; i++) {
            float x    = rnd.nextFloat() * w;
            float y    = horizonY + rnd.nextFloat() * (h - horizonY) * 0.88f;
            float dist = (y - horizonY) / (h - horizonY);
            float rx   = (2.5f + rnd.nextFloat()*5f) * (0.3f+dist*0.7f) * (w/300f);
            float ry   = rx * (0.22f + dist*0.10f);
            long  phOff = (long)(rnd.nextFloat() * 600L);
            float exp   = (float)((now + phOff) % 600L) / 600f;
            float rExp  = rx * (0.25f + exp*0.75f);
            float ryExp = ry * (0.25f + exp*0.75f);
            int alpha   = (int)((1f - exp) * atmo.rain * 32);
            ep.setColor(Color.argb(alpha, 180, 205, 225));
            ep.setStrokeWidth(0.5f + dist*0.4f);
            c.drawOval(x-rExp, y-ryExp, x+rExp, y+ryExp, ep);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  SCENE EFFECTS
    // ═══════════════════════════════════════════════════════════════════════

    private static void addSceneEffects(Bitmap bmp, boolean hasPhoto,
                                         int w, int h, float horizonY,
                                         TimeOfDay time, Atmo atmo,
                                         long hourSeed, long now) {
        Canvas c = new Canvas(bmp);
        boolean isDay = time != TimeOfDay.NIGHT && time != TimeOfDay.DUSK;

        // Photo already contains cliff and boats — only draw procedural ones as fallback
        if (!hasPhoto) {
            drawRockyCliff(c, w, h, horizonY, time, atmo);
            if (atmo.cloud < 0.8f && isDay) {
                drawSmallBoats(c, w, horizonY, time);
            }
        }
        if (atmo.rain > 0.1f) {
            drawRainMist(c, w, h, horizonY, atmo, now);
            drawRainInScene(c, w, h, horizonY, atmo, now);
            drawRainRipples(c, w, h, horizonY, atmo, now);
        } else if (atmo.fog > 0.25f) {
            drawRainMist(c, w, h, horizonY, atmo, now);
        }
        if (atmo.cloud < 0.3f && isDay) {
            drawWaterSparkles(c, w, h, horizonY, hourSeed, now);
        }
        if (atmo.cloud < 0.65f && isDay) {
            drawBirds(c, w, horizonY, now);
        }
        drawLeaves(c, w, h, atmo, hourSeed, now);
        if (!isDay) {
            drawStars(c, w, horizonY, hourSeed, now, atmo);
        }
    }

    // ─── Water sparkles ───────────────────────────────────────────────────────

    private static void drawWaterSparkles(Canvas c, int w, int h,
                                           float horizonY, long seed, long now) {
        Random rnd = new Random(seed * 3 + 7);
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        sp.setStrokeCap(Paint.Cap.ROUND);
        int count = 10 + rnd.nextInt(8);
        for (int i = 0; i < count; i++) {
            float x  = (0.03f + rnd.nextFloat() * 0.94f) * w;
            float y  = horizonY + rnd.nextFloat() * (h - horizonY) * 0.80f;
            float sz = (2.5f + rnd.nextFloat() * 4.0f) * (w / 300f);
            long  period = 4000L + (long)(rnd.nextFloat() * 5500f);
            float phase  = rnd.nextFloat() * (float)(Math.PI * 2);
            float bright = (float)Math.abs(Math.sin((double)now / period * Math.PI + phase));
            if (bright < 0.15f) continue;
            int al = (int)(30 + bright * 175);

            sp.setStyle(Paint.Style.STROKE);
            sp.setStrokeWidth(sz * 0.25f);
            sp.setColor(Color.argb(al, 255, 252, 220));
            c.drawLine(x-sz, y, x+sz, y, sp);
            c.drawLine(x, y-sz, x, y+sz, sp);
            float d = sz * 0.52f;
            sp.setStrokeWidth(sz * 0.16f);
            sp.setColor(Color.argb(al * 2 / 3, 255, 252, 220));
            c.drawLine(x-d, y-d, x+d, y+d, sp);
            c.drawLine(x+d, y-d, x-d, y+d, sp);
            sp.setStyle(Paint.Style.FILL);
            sp.setColor(Color.argb(Math.min(255, al+40), 255, 255, 245));
            c.drawCircle(x, y, sz * 0.20f, sp);
        }
    }

    // ─── Birds ────────────────────────────────────────────────────────────────
    // Each bird has its own flap period — flock looks organic, not synchronized.

    private static void drawBirds(Canvas c, int w, float horizonY, long now) {
        // {cycleMs, yFrac, phaseOffset, sizeScale, flapPeriodMs}
        final float[][] BIRDS = {
            {28000f, 0.65f, 0.00f, 1.00f, 1400f},
            {35000f, 0.73f, 0.35f, 1.35f, 1650f},
            {22000f, 0.58f, 0.62f, 0.80f, 1200f},
            {40000f, 0.81f, 0.15f, 1.65f, 1850f},
            {31000f, 0.68f, 0.80f, 1.10f, 1500f},
            {25000f, 0.76f, 0.50f, 1.45f, 1350f},
            {18000f, 0.60f, 0.28f, 0.70f, 1100f},
        };
        Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
        bp.setStyle(Paint.Style.STROKE);
        bp.setStrokeCap(Paint.Cap.ROUND);
        int margin = (int)(w * 0.08f);
        for (float[] b : BIRDS) {
            long  cycle      = (long) b[0];
            float yFrac      = b[1];
            float phaseOff   = b[2];
            float scale      = b[3];
            long  flapPeriod = (long) b[4];

            float progress = (float)(((now + (long)(phaseOff * cycle)) % cycle) / (double)cycle);
            float bx  = -margin + progress * (w + 2 * margin);
            float by  = horizonY * yFrac;
            float sz  = scale * 3.0f * (w / 400f);
            float flapDip = (float)Math.abs(Math.sin((double)(now % flapPeriod) / flapPeriod * Math.PI));
            float dip = sz * (0.07f + 0.28f * flapDip);

            bp.setStrokeWidth(Math.max(0.6f, sz * 0.22f));
            bp.setColor(Color.argb(80, 8, 8, 18));
            Path bird = new Path();
            bird.moveTo(bx - sz, by + dip);
            bird.quadTo(bx - sz*0.42f, by - sz*0.20f, bx, by);
            bird.quadTo(bx + sz*0.42f, by - sz*0.20f, bx + sz, by + dip);
            c.drawPath(bird, bp);
        }
    }

    // ─── Leaves ───────────────────────────────────────────────────────────────

    private static void drawLeaves(Canvas c, int w, int h, Atmo atmo, long seed, long now) {
        Random rnd = new Random(seed + 11);
        int count = 1 + (int)(atmo.wind * 16f) + rnd.nextInt(3);
        float windPx = 15f + atmo.wind * 82f;
        int[] colors = {
            Color.argb(185,  55,  98, 25),
            Color.argb(185,  80, 120, 22),
            Color.argb(185, 140, 108, 18),
            Color.argb(185, 100,  68, 18),
            Color.argb(185, 170, 130, 20),
        };
        for (int i = 0; i < count; i++) {
            float x0   = rnd.nextFloat() * (w + 80) - 40;
            float y0   = rnd.nextFloat() * (h + 80) - 40;
            float vx   = (rnd.nextFloat() * 1.6f - 0.3f) * windPx;
            float vy   = (0.2f + rnd.nextFloat() * 0.6f) * windPx * 0.35f;
            float rot  = (rnd.nextFloat() * 2f - 1f) * 110f;
            float ang0 = rnd.nextFloat() * 360f;
            float size = (5f + rnd.nextFloat() * 9f) * (w / 360f);
            long  cycle = 7000L + (long)(rnd.nextFloat() * 11000f);
            float t    = (float)((now % cycle) / 1000.0);
            float lx   = ((x0 + vx*t) % (w+120) + w+120) % (w+120) - 40;
            float ly   = ((y0 + vy*t) % (h+120) + h+120) % (h+120) - 40;
            float angle = ang0 + rot * t;
            float depthSize = size * (0.40f + 0.90f * Math.max(0f, ly / h));
            drawSingleLeaf(c, lx, ly, depthSize, angle, colors[rnd.nextInt(colors.length)]);
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
        p.setColor(Color.argb(80, Math.min(255, Color.red(color)+40),
                Math.min(255, Color.green(color)+30), 0));
        c.drawLine(0, -size*0.8f, 0, size*0.55f, p);
        c.restore();
    }

    // ─── Stars ────────────────────────────────────────────────────────────────

    private static void drawStars(Canvas c, int w, float skyH, long seed, long now, Atmo atmo) {
        float cloudFade = 1.0f - atmo.cloud * 0.80f;
        if (cloudFade < 0.05f) return;
        Random pos   = new Random(seed + 42);
        Random parms = new Random(seed + 99);
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        int count = 20 + pos.nextInt(15);
        for (int i = 0; i < count; i++) {
            float x    = pos.nextFloat() * w;
            float y    = pos.nextFloat() * skyH * 0.90f;
            float base = 0.25f + pos.nextFloat() * 0.75f;
            boolean big      = pos.nextFloat() < 0.12f;
            boolean twinkles = pos.nextFloat() < 0.25f;
            float bright;
            if (twinkles) {
                long  period = 3000L + (long)(parms.nextFloat() * 4500f);
                float phase  = parms.nextFloat() * (float)(Math.PI * 2);
                bright = base * (0.35f + 0.65f * (float)Math.abs(
                        Math.sin((double)now / period * Math.PI + phase)));
            } else {
                bright = base;
            }
            int alpha = (int)(bright * 200 * cloudFade);
            float r = (big ? 1.8f : 0.85f) * (w / 330f);
            if (big) {
                sp.setStyle(Paint.Style.FILL);
                sp.setColor(Color.argb(alpha/4, 210, 225, 255));
                c.drawCircle(x, y, r*3f, sp);
                sp.setStyle(Paint.Style.STROKE);
                sp.setStrokeWidth(r*0.35f);
                sp.setColor(Color.argb(alpha/2, 225, 238, 255));
                c.drawLine(x-r*2.4f, y, x+r*2.4f, y, sp);
                c.drawLine(x, y-r*2.4f, x, y+r*2.4f, sp);
            }
            sp.setStyle(Paint.Style.FILL);
            sp.setColor(Color.argb(alpha, 240, 248, 255));
            c.drawCircle(x, y, r, sp);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  GLASS
    // ═══════════════════════════════════════════════════════════════════════

    // Distortion reduced by ~50% vs original for a more physical, subtle look.
    private static Bitmap applyGlassDistortion(Bitmap src, int w, int h) {
        int[] srcPx = new int[w * h];
        src.getPixels(srcPx, 0, w, 0, 0, w, h);
        int[] dstPx = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double dx1 = 1.6*Math.sin(y*0.014+0.53) + 0.9*Math.cos(x*0.011+y*0.007);
                double dy1 = 1.4*Math.cos(x*0.013+1.21) + 0.8*Math.sin(y*0.010+x*0.006);
                double dx2 = 0.45*Math.sin(x*0.048+y*0.031);
                double dy2 = 0.35*Math.cos(y*0.042+x*0.022);
                int sx = clamp((int)(x+dx1+dx2), 0, w-1);
                int sy = clamp((int)(y+dy1+dy2), 0, h-1);
                dstPx[y*w+x] = srcPx[sy*w+sx];
            }
        }
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(dstPx, 0, w, 0, 0, w, h);
        return result;
    }

    private static void drawGlassSurface(Canvas c, int w, int h) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.argb(14, 20, 60, 12));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new RadialGradient(w/2f, h/2f, (float)Math.max(w,h)*0.62f,
                Color.TRANSPARENT, Color.argb(55, 0, 0, 0), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p); p.setShader(null);
        p.setShader(new LinearGradient(0, 0, 0, h*0.08f,
                Color.argb(18, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h*0.08f, p); p.setShader(null);
        drawBubbles(c, w, h, p);
        drawGlassStreaks(c, w, h);
    }

    private static void drawBubbles(Canvas c, int w, int h, Paint p) {
        float[][] bb = {
            {0.14f,0.11f,0.013f},{0.73f,0.27f,0.009f},{0.38f,0.54f,0.016f},
            {0.85f,0.66f,0.010f},{0.22f,0.82f,0.008f},{0.62f,0.08f,0.010f},
            {0.47f,0.43f,0.007f},{0.08f,0.63f,0.012f},{0.56f,0.77f,0.009f},
            {0.91f,0.44f,0.006f}
        };
        for (float[] b : bb) {
            float cx=b[0]*w, cy=b[1]*h, r=b[2]*Math.min(w,h);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(0.7f);
            p.setColor(Color.argb(38, 180, 210, 180)); c.drawCircle(cx, cy, r, p);
            p.setColor(Color.argb(18, 0, 10, 0));      c.drawCircle(cx, cy, r*0.8f, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(45, 255, 255, 255));  c.drawCircle(cx-r*0.32f, cy-r*0.32f, r*0.35f, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private static void drawGlassStreaks(Canvas c, int w, int h) {
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        sp.setStyle(Paint.Style.STROKE); sp.setStrokeWidth(0.8f);
        sp.setColor(Color.argb(11, 255, 255, 255));
        Path s1 = new Path(); s1.moveTo(w*0.28f, 0);
        s1.cubicTo(w*0.31f, h*0.28f, w*0.26f, h*0.55f, w*0.30f, h); c.drawPath(s1, sp);
        Path s2 = new Path(); s2.moveTo(w*0.68f, 0);
        s2.cubicTo(w*0.71f, h*0.32f, w*0.67f, h*0.62f, w*0.70f, h); c.drawPath(s2, sp);
        sp.setColor(Color.argb(7, 255, 255, 255)); sp.setStrokeWidth(1.2f);
        Path s3 = new Path(); s3.moveTo(w*0.50f, 0);
        s3.cubicTo(w*0.52f, h*0.40f, w*0.49f, h*0.70f, w*0.51f, h); c.drawPath(s3, sp);
    }

    // ─── Rain on glass ────────────────────────────────────────────────────────
    // Positions stable via hourSeed; slideY gives smooth continuous motion.
    // src (pre-distortion scene bitmap) is sampled inside lens drops.

    private static void drawRainOnGlass(Canvas c, Bitmap src, int w, int h,
                                         long now, long hourSeed, Atmo atmo) {
        float dx = (float) Math.tan(Math.toRadians(atmo.windAngle * 0.45));

        // ── Far thin streaks ──────────────────────────────────────────────────
        Random far = new Random(hourSeed * 7 + 1);
        Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG);
        fp.setStyle(Paint.Style.STROKE); fp.setStrokeCap(Paint.Cap.ROUND);
        fp.setStrokeWidth(0.55f);
        for (int i = 0; i < 40; i++) {
            float startY = far.nextFloat() * h;
            float x      = far.nextFloat() * w;
            float len    = (10 + far.nextFloat() * 40) * (h / 400f);
            float speed  = 38f + far.nextFloat() * 55f;
            float phase  = far.nextFloat();
            float y = slideY(startY, speed, phase, 9000L, now, h);
            float fadeIn = Math.min(1f, y / (h * 0.12f));
            int alpha = (int)(28 * atmo.rain * fadeIn);
            fp.setColor(Color.argb(alpha, 180, 212, 255));
            c.drawLine(x, y, x + dx * len, y + len, fp);
        }

        // ── Medium streaks ────────────────────────────────────────────────────
        Random med = new Random(hourSeed * 13 + 2);
        fp.setStrokeWidth(1.1f);
        for (int i = 0; i < 22; i++) {
            float startY = med.nextFloat() * h;
            float x      = med.nextFloat() * w;
            float len    = (28 + med.nextFloat() * 75) * (h / 400f);
            float speed  = 60f + med.nextFloat() * 78f;
            float phase  = med.nextFloat();
            float y = slideY(startY, speed, phase, 7000L, now, h);
            float fadeIn = Math.min(1f, y / (h * 0.09f));
            int alpha = (int)(50 * atmo.rain * fadeIn);
            fp.setColor(Color.argb(alpha, 160, 205, 252));
            c.drawLine(x, y, x + dx * len, y + len, fp);
        }

        // ── Rivulets (wavy flowing streams on glass surface) ──────────────────
        drawRivulets(c, w, h, now, hourSeed, atmo);

        // ── Lens drops: fewer but big — inverted scene clearly visible ───────
        Random near = new Random(hourSeed * 19 + 3);
        int numDrops = (int)(4 + atmo.rain * 8);
        for (int i = 0; i < numDrops; i++) {
            float startY = near.nextFloat() * h;
            float x      = near.nextFloat() * w;
            // Large drops — r in range [10..28 px on a 400px-wide widget]
            float r      = (10f + near.nextFloat() * 18f) * (w / 400f);
            float speed  = 10f + near.nextFloat() * 24f;
            float phase  = near.nextFloat();
            float y = slideY(startY, speed, phase, 16000L, now, h);
            float fadeIn = (float) Math.sin(Math.min(1f, y / (float) h) * Math.PI);
            if (fadeIn < 0.06f) continue;
            float dropAlpha = Math.min(255f, 255f * Math.max(0.20f, fadeIn));
            drawLensDrop(c, src, x, y, r, dropAlpha);
        }

        // ── Condensation haze at bottom edge ──────────────────────────────────
        if (atmo.rain > 0.18f) {
            float condH = h * (0.07f + atmo.rain * 0.12f);
            Paint cp = new Paint();
            cp.setShader(new LinearGradient(0, h - condH * 1.8f, 0, h,
                    Color.argb(0, 200, 218, 240),
                    Color.argb((int)(atmo.rain * 140), 195, 215, 240),
                    Shader.TileMode.CLAMP));
            c.drawRect(0, h - condH * 1.8f, w, h, cp);
            cp.setShader(null);
        }
    }

    // ─── Rivulets ─────────────────────────────────────────────────────────────
    // Wavy streams flowing down the glass; the leading drop bulges at the tip.

    private static void drawRivulets(Canvas c, int w, int h,
                                      long now, long hourSeed, Atmo atmo) {
        int numRiv = (int)(2 + atmo.rain * 7f);
        Random riv = new Random(hourSeed * 31 + 77);
        Paint rp = new Paint(Paint.ANTI_ALIAS_FLAG);
        rp.setStyle(Paint.Style.STROKE);
        rp.setStrokeJoin(Paint.Join.ROUND);
        rp.setStrokeCap(Paint.Cap.ROUND);

        for (int i = 0; i < numRiv; i++) {
            float x0     = (0.05f + riv.nextFloat() * 0.90f) * w;
            float startY = riv.nextFloat() * h * 0.5f;
            float speed  = 16f + riv.nextFloat() * 30f;
            float phase  = riv.nextFloat();
            float trailH = h * (0.08f + riv.nextFloat() * 0.18f);
            float baseW  = 0.8f + riv.nextFloat() * 1.8f;

            float headY = slideY(startY, speed, phase, 16000L, now, h);

            // Trail path — from head upward with sinusoidal wobble
            Path trail = new Path();
            int segs = 10;
            float segH = trailH / segs;
            trail.moveTo(x0, headY);
            for (int s = 1; s <= segs; s++) {
                float ny = headY - segH * s;
                float nx = x0 + (float)(Math.sin(ny * 0.065 + i * 1.9 + 0.3) * baseW * 5f);
                trail.lineTo(nx, ny);
            }

            // Outer stream (wider, translucent)
            rp.setStrokeWidth(baseW * 2.2f);
            rp.setColor(Color.argb((int)(68 * atmo.rain), 145, 188, 238));
            c.drawPath(trail, rp);
            // Inner highlight (narrower, brighter)
            rp.setStrokeWidth(baseW * 0.85f);
            rp.setColor(Color.argb((int)(110 * atmo.rain), 200, 228, 255));
            c.drawPath(trail, rp);

            // Leading drop — teardrop bulge at the bottom of the trail
            float dr = baseW * (2.8f + riv.nextFloat() * 1.4f);
            Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
            dp.setStyle(Paint.Style.FILL);
            dp.setColor(Color.argb((int)(145 * atmo.rain), 150, 198, 248));
            c.drawOval(x0 - dr * 0.65f, headY, x0 + dr * 0.65f, headY + dr * 1.5f, dp);
            // Specular on leading drop
            dp.setColor(Color.argb((int)(185 * atmo.rain), 228, 244, 255));
            c.drawOval(x0 - dr * 0.32f, headY + dr * 0.08f,
                       x0 + dr * 0.08f, headY + dr * 0.58f, dp);
        }
    }

    // ─── Lens drop ────────────────────────────────────────────────────────────
    // Samples the scene from src, scales + flips it vertically (real lens
    // inversion) and clips to a teardrop oval.  Adds specular highlights.

    private static void drawLensDrop(Canvas c, Bitmap src,
                                      float cx, float cy, float r, float alpha) {
        // Teardrop clip shape
        Path shape = new Path();
        shape.addOval(cx - r * 0.68f, cy - r * 0.92f,
                      cx + r * 0.68f, cy + r * 0.58f, Path.Direction.CW);

        c.save();
        c.clipPath(shape);

        // Sample scene around drop center; smaller margin = more magnification
        float margin = Math.max(r * 0.52f, 6f);
        int sx = (int) Math.max(0, cx - margin);
        int sy = (int) Math.max(0, cy - margin);
        int sw = (int) Math.min(src.getWidth()  - sx, (int)(margin * 2));
        int sh = (int) Math.min(src.getHeight() - sy, (int)(margin * 2));

        if (sw > 2 && sh > 2) {
            Bitmap sub = Bitmap.createBitmap(src, sx, sy, sw, sh);
            // Scale to drop width; negative Y = vertical flip (lens inversion)
            float sc = (r * 1.36f) / Math.max(sw, 1);
            Matrix mat = new Matrix();
            mat.setScale(sc, -sc);
            mat.postTranslate(cx - sw * sc * 0.5f, cy + sh * sc * 0.5f);
            Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            bp.setAlpha((int) Math.min(255f, alpha * 1.12f));
            c.drawBitmap(sub, mat, bp);
            sub.recycle();
        }

        c.restore();

        // Subtle water-blue tint over the scene inside the drop
        Paint ov = new Paint(Paint.ANTI_ALIAS_FLAG);
        ov.setColor(Color.argb((int)(alpha * 0.17f), 78, 122, 195));
        c.drawOval(cx - r * 0.68f, cy - r * 0.92f,
                   cx + r * 0.68f, cy + r * 0.58f, ov);

        // Rim (thin edge of water bead)
        ov.setStyle(Paint.Style.STROKE);
        ov.setStrokeWidth(0.9f);
        ov.setColor(Color.argb((int)(alpha * 0.28f), 128, 168, 222));
        c.drawOval(cx - r * 0.68f, cy - r * 0.92f,
                   cx + r * 0.68f, cy + r * 0.58f, ov);
        ov.setStyle(Paint.Style.FILL);

        // Primary specular — bright ellipse, upper-left (light from above-right)
        ov.setColor(Color.argb((int) Math.min(255f, alpha * 0.88f), 255, 255, 255));
        c.drawOval(cx - r * 0.42f, cy - r * 0.82f,
                   cx - r * 0.02f, cy - r * 0.30f, ov);

        // Secondary specular — smaller, upper-right
        ov.setColor(Color.argb((int) Math.min(255f, alpha * 0.36f), 255, 255, 255));
        c.drawOval(cx + r * 0.08f, cy - r * 0.66f,
                   cx + r * 0.30f, cy - r * 0.36f, ov);

        // Drop shadow beneath (very subtle darkening)
        ov.setColor(Color.argb((int)(alpha * 0.13f), 0, 4, 22));
        c.drawOval(cx - r * 0.55f, cy + r * 0.28f,
                   cx + r * 0.55f, cy + r * 0.62f, ov);
    }

    // Computes smooth continuous y-position for a glass drop.
    // startY: hourly fixed base position; phase: 0..1 per-drop offset.
    private static float slideY(float startY, float speed, float phase,
                                  long windowMs, long now, int h) {
        float windowSec = windowMs / 1000f;
        float elapsed   = (float)(now % windowMs) / 1000f + phase * windowSec;
        float y = (startY + elapsed * speed) % h;
        return y < 0 ? y + h : y;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  DATE / TIME
    // ═══════════════════════════════════════════════════════════════════════

    private static void drawTimeDate(Canvas c, int w, int h) {
        Calendar cal = Calendar.getInstance();
        String timeStr = String.format("%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
        String[] days   = {"Dom","Lun","Mar","Mer","Gio","Ven","Sab"};
        String[] months = {"Gen","Feb","Mar","Apr","Mag","Giu","Lug","Ago","Set","Ott","Nov","Dic"};
        String dateStr  = days[cal.get(Calendar.DAY_OF_WEEK)-1] + "  "
                + cal.get(Calendar.DAY_OF_MONTH) + " " + months[cal.get(Calendar.MONTH)];
        float sh = h * 0.018f;
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.WHITE);
        tp.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        tp.setTextSize(h * 0.30f); tp.setLetterSpacing(0.04f);
        tp.setShadowLayer(sh*1.8f, 0, sh, Color.argb(160, 0, 0, 0));
        float tw = tp.measureText(timeStr);
        c.drawText(timeStr, (w-tw)/2f, h*0.56f, tp);
        Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
        dp.setColor(Color.argb(230, 255, 255, 255));
        dp.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        dp.setTextSize(h*0.130f); dp.setLetterSpacing(0.18f);
        dp.setShadowLayer(sh*1.4f, 0, sh*0.7f, Color.argb(140, 0, 0, 0));
        float dw = dp.measureText(dateStr);
        c.drawText(dateStr, (w-dw)/2f, h*0.56f+h*0.165f, dp);
    }

    // ─── Rocky cliff (Ligurian coast, right side) ────────────────────────────

    private static void drawRockyCliff(Canvas c, int w, int h, float horizonY,
                                        TimeOfDay time, Atmo atmo) {
        int base;
        switch (time) {
            case DAWN:   base = Color.argb(200, 45, 22, 15); break;
            case NIGHT:  base = Color.argb(200, 18, 14, 12); break;
            case SUNSET: base = Color.argb(200, 65, 28, 10); break;
            case DUSK:   base = Color.argb(200, 30, 20, 18); break;
            default:     base = Color.argb(200, 78, 68, 52); break;
        }
        base = scaleAlpha(base, 1.0f - atmo.fog * 0.62f);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(base);

        // Main cliff silhouette
        Path cliff = new Path();
        cliff.moveTo(w * 0.58f, horizonY);
        cliff.cubicTo(w*0.66f, horizonY - h*0.07f, w*0.74f, horizonY - h*0.17f, w*0.80f, horizonY - h*0.13f);
        cliff.cubicTo(w*0.87f, horizonY - h*0.09f, w*0.92f, horizonY - h*0.20f, w, horizonY - h*0.10f);
        cliff.lineTo(w, h);
        cliff.lineTo(w * 0.58f, h);
        cliff.close();
        c.drawPath(cliff, p);

        // Rock strata lines (lighter bands)
        Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
        lp.setStyle(Paint.Style.STROKE);
        lp.setStrokeWidth(0.8f);
        lp.setColor(Color.argb(55, 160, 145, 105));
        c.drawLine(w*0.68f, horizonY + h*0.02f, w*0.87f, h*0.82f, lp);
        c.drawLine(w*0.80f, horizonY - h*0.02f, w*0.96f, h*0.72f, lp);
        // Dark crevice
        lp.setColor(Color.argb(70, 10, 8, 5));
        lp.setStrokeWidth(1.2f);
        c.drawLine(w*0.73f, horizonY - h*0.06f, w*0.90f, h*0.60f, lp);
        // Headland rock at waterline
        p.setColor(scaleAlpha(base, 0.8f));
        Path rock = new Path();
        rock.moveTo(w*0.58f, horizonY);
        rock.cubicTo(w*0.52f, horizonY + h*0.04f, w*0.48f, horizonY + h*0.06f, w*0.42f, horizonY + h*0.05f);
        rock.cubicTo(w*0.44f, horizonY + h*0.02f, w*0.52f, horizonY, w*0.58f, horizonY);
        rock.close();
        c.drawPath(rock, p);
    }

    // ─── Small boats near horizon ────────────────────────────────────────────

    private static void drawSmallBoats(Canvas c, int w, float horizonY, TimeOfDay time) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Two boats, left of centre (clear of the cliff)
        float[][] boats = {{0.20f, 0.55f, 1.0f}, {0.40f, 0.40f, 0.65f}};
        for (float[] b : boats) {
            float bx = b[0] * w;
            float by = horizonY - b[1] * horizonY * 0.06f;
            float bw = b[2] * w * 0.020f;
            float bh = bw * 0.38f;
            // Hull
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.argb(170, 235, 230, 220));
            c.drawOval(bx - bw, by, bx + bw, by + bh, p);
            // Mast
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(0.6f);
            p.setColor(Color.argb(150, 190, 185, 172));
            c.drawLine(bx, by, bx, by - bh * 2.8f, p);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  STONE ARCH WINDOW FRAME (Porto Venere loggia)
    // ═══════════════════════════════════════════════════════════════════════

    private static void drawWindowFrame(Canvas c, int w, int h) {
        float pw    = w * 0.090f;   // side pillar width
        float cw    = w * 0.042f;   // central column width
        float ph    = h * 0.080f;   // parapet height
        float topH  = h * 0.018f;   // thin stone band at top
        float archW = (w - 2 * pw - cw) / 2f;
        float archR = Math.min(archW / 2f, (h - topH - ph) * 0.46f);
        float springY   = topH + archR;
        float openBottom = h - ph;

        float lx0 = pw, lx1 = pw + archW;
        float rx0 = pw + archW + cw, rx1 = w - pw;

        // ── Stone mask (EVEN_ODD carves arch openings) ───────────────────────
        Path mask = new Path();
        mask.setFillType(Path.FillType.EVEN_ODD);
        mask.addRect(0, 0, w, h, Path.Direction.CW);
        addArchCutout(mask, lx0, lx1, springY, archR, openBottom, topH);
        addArchCutout(mask, rx0, rx1, springY, archR, openBottom, topH);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Warm limestone base, top→bottom
        fill.setShader(new LinearGradient(0, 0, 0, h,
                0xFFD2CCBA, 0xFF9C968A, Shader.TileMode.CLAMP));
        c.drawPath(mask, fill);
        fill.setShader(null);

        // Left→right shadow variation
        fill.setShader(new LinearGradient(0, 0, w, 0,
                Color.argb(18, 255, 248, 230), Color.argb(42, 18, 12, 4),
                Shader.TileMode.CLAMP));
        c.drawPath(mask, fill);
        fill.setShader(null);

        // ── Stone texture ─────────────────────────────────────────────────────
        c.save();
        c.clipPath(mask);
        Paint tx = new Paint();
        tx.setStyle(Paint.Style.STROKE);
        tx.setStrokeWidth(0.5f);
        tx.setColor(Color.argb(16, 45, 35, 20));
        Random rnd = new Random(77331L);
        for (int i = 0; i < 95; i++) {
            float x1 = rnd.nextFloat() * w;
            float y1 = rnd.nextFloat() * h;
            c.drawLine(x1, y1, x1 + (rnd.nextFloat()-0.5f)*30, y1 + (rnd.nextFloat()-0.5f)*7, tx);
        }
        // Horizontal mortar joints
        tx.setStrokeWidth(1.0f);
        tx.setColor(Color.argb(20, 35, 28, 15));
        for (float y = topH + h*0.10f; y < h; y += h * 0.115f) {
            c.drawLine(0, y, w, y + (rnd.nextFloat()-0.5f) * 3, tx);
        }
        // Lichen / moss patches
        tx.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 22; i++) {
            float bx = rnd.nextFloat() * w, by = rnd.nextFloat() * h;
            float br = 2f + rnd.nextFloat() * 8f;
            tx.setColor((i % 3 == 0) ? Color.argb(38, 62, 82, 42) : Color.argb(22, 88, 88, 68));
            c.drawCircle(bx, by, br, tx);
        }
        c.restore();

        // ── Arch moldings ─────────────────────────────────────────────────────
        drawArchMolding(c, lx0, lx1, springY, archR, openBottom, topH);
        drawArchMolding(c, rx0, rx1, springY, archR, openBottom, topH);

        // ── Central column ────────────────────────────────────────────────────
        float colX = lx1;
        fill.setShader(new LinearGradient(colX, 0, colX + cw, 0,
                0xFFD8D2C2, 0xFF908A7A, Shader.TileMode.CLAMP));
        c.drawRect(colX, springY, colX + cw, openBottom, fill);
        fill.setShader(null);
        // Capital (wider top)
        float capE = cw * 0.28f;
        fill.setColor(0xFFBCB8A8);
        c.drawRect(colX - capE, springY - cw*0.50f, colX + cw + capE, springY + cw*0.38f, fill);
        // Base
        c.drawRect(colX - capE, openBottom - cw*0.38f, colX + cw + capE, openBottom, fill);
        // Highlight line on column
        fill.setStyle(Paint.Style.STROKE); fill.setStrokeWidth(1.4f);
        fill.setColor(Color.argb(50, 225, 218, 205));
        c.drawLine(colX + cw*0.22f, springY + cw*0.4f, colX + cw*0.22f, openBottom - cw*0.4f, fill);
        fill.setStyle(Paint.Style.FILL);

        // ── Parapet ───────────────────────────────────────────────────────────
        fill.setShader(new LinearGradient(0, h - ph, 0, h,
                0xFF9C9688, 0xFFBCB6A8, Shader.TileMode.CLAMP));
        c.drawRect(0, h - ph, w, h, fill);
        fill.setShader(null);
        fill.setStyle(Paint.Style.STROKE); fill.setStrokeWidth(1.5f);
        fill.setColor(Color.argb(85, 218, 212, 196));
        c.drawLine(0, h - ph, w, h - ph, fill);
        fill.setStyle(Paint.Style.FILL);

        // ── Depth shadows at arch edges ───────────────────────────────────────
        for (float[] arch : new float[][]{{lx0, lx1}, {rx0, rx1}}) {
            float ax0 = arch[0], ax1 = arch[1];
            float sw = pw * 0.35f;
            fill.setShader(new LinearGradient(ax0, 0, ax0 + sw, 0,
                    Color.argb(60, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            c.drawRect(ax0, topH, ax0 + sw, openBottom, fill);
            fill.setShader(null);
            fill.setShader(new LinearGradient(ax1, 0, ax1 - sw, 0,
                    Color.argb(60, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            c.drawRect(ax1 - sw, topH, ax1, openBottom, fill);
            fill.setShader(null);
        }
    }

    // Carves an arch-shaped opening into a EVEN_ODD path.
    private static void addArchCutout(Path p, float x0, float x1, float springY, float archR,
                                       float bottom, float topH) {
        RectF oval = new RectF(x0, topH, x1, topH + 2 * archR);
        p.moveTo(x0, bottom);
        p.lineTo(x1, bottom);
        p.lineTo(x1, springY);
        p.arcTo(oval, 0, -180, false); // right→top→left (over the arch)
        p.lineTo(x0, bottom);
        p.close();
    }

    // Draws shadow rim and highlight along the inner arch edge.
    private static void drawArchMolding(Canvas c, float x0, float x1, float springY, float archR,
                                         float bottom, float topH) {
        RectF oval = new RectF(x0, topH, x1, topH + 2 * archR);
        Paint mp = new Paint(Paint.ANTI_ALIAS_FLAG);
        mp.setStyle(Paint.Style.STROKE);

        // Shadow rim (inner edge of stone wall, depth cue)
        mp.setStrokeWidth(Math.max(3f, (x1 - x0) * 0.022f));
        mp.setColor(Color.argb(70, 18, 14, 8));
        Path rim = new Path();
        rim.moveTo(x0, bottom);
        rim.lineTo(x0, springY);
        rim.arcTo(oval, 180, 180, false); // left→top→right
        rim.lineTo(x1, bottom);
        c.drawPath(rim, mp);

        // Crown highlight (sunlight from upper-right)
        mp.setStrokeWidth(2.2f);
        mp.setColor(Color.argb(52, 242, 235, 215));
        Path crown = new Path();
        crown.arcTo(oval, 240, 85, true);
        c.drawPath(crown, mp);

        // Left vertical inner edge (lit side)
        mp.setStrokeWidth(1.8f);
        mp.setColor(Color.argb(38, 235, 228, 210));
        c.drawLine(x0, springY, x0, bottom, mp);
        // Right vertical inner edge (shadow)
        mp.setColor(Color.argb(45, 10, 8, 5));
        c.drawLine(x1, springY, x1, bottom, mp);
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

    private static int blendColorARGB(int a, int b, float t) {
        int aa=(a>>24)&0xFF, ra=(a>>16)&0xFF, ga=(a>>8)&0xFF, ba_=a&0xFF;
        int ab=(b>>24)&0xFF, rb=(b>>16)&0xFF, gb=(b>>8)&0xFF, bb_=b&0xFF;
        int al=(int)(aa+(ab-aa)*t), r=(int)(ra+(rb-ra)*t),
            g=(int)(ga+(gb-ga)*t),  bl=(int)(ba_+(bb_-ba_)*t);
        return (al<<24)|(r<<16)|(g<<8)|bl;
    }

    private static int scaleAlpha(int color, float factor) {
        int a = (int)(((color>>24)&0xFF) * factor);
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }
}
