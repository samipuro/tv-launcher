package com.cosyhome.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * The cosy valley behind the clock: sky, sun or moon, three hills and a cottage.
 *
 * Drawn on a 1920x1080 design canvas and scaled to the view. Everything that never moves is
 * painted once per phase into {@link #still}; each frame only adds the stars, clouds and chimney
 * smoke on top, at about 25 fps, so the TV's GPU is mostly drawing one bitmap.
 */
public class SceneView extends View {

    enum Phase { DAWN, DAY, DUSK, NIGHT }

    /** What the sky is doing outside, from the Open-Meteo weather code. */
    enum Sky { CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, STORM }

    interface Listener {
        /** A new still image (and blurred copy) is ready: the phase or the size changed. */
        void onSceneChanged();
    }

    private static final float W = 1920f, H = 1080f;
    private static final long FRAME_MS = 40;

    /** Sky stops, three hill colours, window colour, window glow strength, dark clock text. */
    private static final class Palette {
        final int s1, s2, s3, h1, h2, h3, win;
        final float glow;
        final boolean darkText;

        Palette(int s1, int s2, int s3, int h1, int h2, int h3, int win, float glow, boolean darkText) {
            this.s1 = s1;
            this.s2 = s2;
            this.s3 = s3;
            this.h1 = h1;
            this.h2 = h2;
            this.h3 = h3;
            this.win = win;
            this.glow = glow;
            this.darkText = darkText;
        }
    }

    private static final Palette DAWN = new Palette(0xFF2E3A6B, 0xFFC98A9A, 0xFFF6C99A,
            0xFF7C6A93, 0xFF54497A, 0xFF2E2A4E, 0xFFFFC27A, 0.5f, false);
    private static final Palette DAY = new Palette(0xFF4F9CCB, 0xFF93C6E4, 0xFFD8EEF6,
            0xFF8DB59A, 0xFF64957A, 0xFF3F6E55, 0xFF2B3A40, 0f, true);
    private static final Palette DUSK = new Palette(0xFF2F2350, 0xFFB4506A, 0xFFF2A65A,
            0xFF7A4560, 0xFF4E2C4A, 0xFF24162E, 0xFFFFB35C, 0.75f, false);
    private static final Palette NIGHT = new Palette(0xFF04060E, 0xFF0B1230, 0xFF1A2550,
            0xFF161D36, 0xFF0E1326, 0xFF070A14, 0xFFFFB35C, 1f, false);

    private static final float[][] STARS = {
            {180, 90, 2}, {420, 180, 1.6f}, {690, 70, 2.2f}, {930, 150, 1.4f}, {1150, 60, 2},
            {1320, 130, 1.5f}, {1720, 90, 2.2f}, {1830, 260, 1.4f}, {1080, 300, 1.4f}, {820, 380, 1.2f},
            {1620, 400, 1.3f}, {560, 330, 1.2f}, {300, 420, 1.1f}, {1240, 240, 1.2f}, {80, 250, 1.5f}};
    /** Cloud puffs as {cx, cy, rx, ry}. */
    private static final float[][] CLOUDS = {
            {420, 260, 130, 34}, {500, 240, 90, 40}, {1000, 380, 160, 30}, {1080, 362, 80, 34}, {1760, 430, 120, 26}};

    /** Extra puffs for a cloudy sky, on top of {@link #CLOUDS}. */
    private static final float[][] MORE_CLOUDS = {
            {150, 150, 170, 40}, {250, 130, 110, 46}, {760, 200, 190, 44}, {860, 176, 120, 50},
            {1300, 300, 200, 40}, {1400, 280, 110, 44}, {1620, 170, 180, 42}, {1720, 150, 100, 46},
            {620, 470, 150, 28}, {1500, 520, 140, 24}};

    private Phase phase = Phase.NIGHT;
    private Palette pal = NIGHT;
    private Sky sky = Sky.PARTLY;
    /** Number of rain drops or snowflakes drawn per frame. */
    private int precip;
    /** Steps the moon's cycle is drawn in: 64 is about eleven hours each, so the still is redrawn rarely. */
    static final int MOON_STEPS = 64;
    /** The moon's phase as a step of {@link #MOON_STEPS}: 0 new, 32 full. */
    private int moonStep = 4;
    /** Whether the moon is above the horizon here. */
    private boolean moonUp = true;
    private Bitmap still;
    private Bitmap blurred;
    private Listener listener;
    private boolean running;
    private final long start = SystemClock.uptimeMillis();
    /** For the still layers. Shaders draw with the paint's alpha, so it must never carry a frame's fade. */
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** For the per-frame stars, clouds and smoke, whose colours carry their own fades. */
    private final Paint fx = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path far = new Path(), mid = new Path(), near = new Path();

    public SceneView(Context context, AttributeSet attrs) {
        super(context, attrs);
        far.moveTo(0, 720);
        far.cubicTo(240, 640, 420, 660, 640, 700);
        far.cubicTo(860, 740, 1040, 640, 1280, 650);
        far.cubicTo(1500, 660, 1700, 720, 1920, 690);
        closeToBottom(far);
        mid.moveTo(0, 820);
        mid.cubicTo(260, 760, 520, 790, 760, 820);
        mid.cubicTo(1000, 850, 1240, 760, 1500, 770);
        mid.cubicTo(1700, 778, 1820, 800, 1920, 790);
        closeToBottom(mid);
        near.moveTo(0, 940);
        near.cubicTo(300, 880, 640, 900, 900, 930);
        near.cubicTo(1160, 960, 1440, 900, 1700, 905);
        near.cubicTo(1800, 907, 1880, 915, 1920, 920);
        closeToBottom(near);
    }

    private static void closeToBottom(Path p) {
        p.lineTo(W, H);
        p.lineTo(0, H);
        p.close();
    }

    void setListener(Listener l) {
        listener = l;
    }

    Phase phase() {
        return phase;
    }

    /** Daytime has a light sky, so the clock switches to dark text. */
    boolean wantsDarkText() {
        return pal.darkText;
    }

    /** A small, heavily blurred copy of the still scene, for the glass panels to sample. */
    Bitmap blurred() {
        return blurred;
    }

    void setPhase(Phase p) {
        if (p == phase && still != null) return;
        phase = p;
        pal = p == Phase.DAWN ? DAWN : p == Phase.DAY ? DAY : p == Phase.DUSK ? DUSK : NIGHT;
        rebuild();
    }

    // Purpose: show the real moon: its phase as a step of MOON_STEPS, and whether it is up.
    void setMoon(int step, boolean up) {
        step = Math.floorMod(step, MOON_STEPS);
        if (step == moonStep && up == moonUp) return;
        moonStep = step;
        moonUp = up;
        // The moon is only drawn at night, so other phases keep their still.
        if (still != null && phase == Phase.NIGHT) rebuild();
    }

    // Purpose: show the weather outside in the scene, from a WMO weather code (-1 when unknown).
    void setWeather(int code) {
        Sky s;
        int n;

        // Map the WMO code to a sky and an amount of rain or snow.
        switch (code) {
            case 0: s = Sky.CLEAR; n = 0; break;
            case 1: case 2: s = Sky.PARTLY; n = 0; break;
            case 3: s = Sky.CLOUDY; n = 0; break;
            case 45: case 48: s = Sky.FOG; n = 0; break;
            case 51: case 53: case 55: case 56: case 57: s = Sky.RAIN; n = 45; break;
            case 61: case 66: case 80: s = Sky.RAIN; n = 80; break;
            case 63: case 81: s = Sky.RAIN; n = 120; break;
            case 65: case 67: case 82: s = Sky.RAIN; n = 170; break;
            case 71: case 77: case 85: s = Sky.SNOW; n = 70; break;
            case 73: s = Sky.SNOW; n = 110; break;
            case 75: case 86: s = Sky.SNOW; n = 160; break;
            case 95: case 96: case 99: s = Sky.STORM; n = 160; break;
            default: s = Sky.PARTLY; n = 0; break;
        }

        // Only the still layer depends on the sky, so redraw it only on a change.
        precip = n;
        if (s == sky) return;
        sky = s;
        if (still != null) rebuild();
    }

    // Purpose: true when clouds cover the sky, so the sun, moon and stars are hidden.
    private boolean overcast() {
        return sky == Sky.CLOUDY || sky == Sky.RAIN || sky == Sky.SNOW || sky == Sky.STORM || sky == Sky.FOG;
    }

    // Purpose: how far the sky colours are washed towards grey for this weather.
    private float greyness() {
        switch (sky) {
            case CLOUDY: return 0.55f;
            case FOG: return 0.6f;
            case RAIN: return 0.65f;
            case SNOW: return 0.5f;
            case STORM: return 0.75f;
            default: return 0f;
        }
    }

    // Purpose: a colour washed towards its own grey, darker for rain and storm.
    private int grey(int c, float amount) {
        if (amount <= 0) return c;
        int l = Math.round(0.3f * Color.red(c) + 0.59f * Color.green(c) + 0.11f * Color.blue(c));
        if (sky == Sky.STORM || sky == Sky.RAIN) l = Math.round(l * 0.82f);
        if (sky == Sky.SNOW || sky == Sky.FOG) l = Math.min(255, Math.round(l * 1.08f + 10));
        return lerp(c, Color.rgb(l, l, Math.min(255, l + 6)), amount);
    }

    /** Frees the full-screen still while an app is in front; the next {@link #setPhase} redraws it. */
    void release() {
        still = null;
    }

    /** Animate only while Home is on screen. */
    void setRunning(boolean run) {
        running = run;
        if (run) invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuild();
    }

    private void rebuild() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        // Full colour (about 8 MB at 1080p): RGB_565 bands visibly in the dark sky and the moon's glow.
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.scale(w / W, h / H);
        drawStill(c);
        still = bmp;
        blurred = Blur.of(bmp, 8, 5);
        invalidate();
        if (listener != null) listener.onSceneChanged();
    }

    // ---- the still layers -------------------------------------------------------------------------

    private void drawStill(Canvas c) {
        float g = greyness();
        paint.setShader(new LinearGradient(0, 0, 0, H, new int[]{grey(pal.s1, g), grey(pal.s2, g), grey(pal.s3, g)},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, paint);
        paint.setShader(null);

        // Under cloud the sun is only a faint brightening and the moon is gone.
        if (overcast()) {
            if (phase == Phase.DAY) softGlow(c, 1500, 200, 260, 0xFFFFFFFF, 0.18f);
            else if (phase == Phase.DAWN) softGlow(c, 1180, 660, 260, 0xFFFFD9A0, 0.2f);
            else if (phase == Phase.DUSK) softGlow(c, 820, 706, 280, 0xFFFFB46B, 0.22f);
        } else switch (phase) {
            case DAWN:
                glowDisc(c, 1180, 660, 68, 0xFFFFE2B4, 0xFFFFD9A0, 0.35f);
                break;
            case DAY:
                glowDisc(c, 1500, 200, 66, 0xFFFFF8E2, 0xFFFFF6D6, 0.55f);
                break;
            case DUSK:
                glowDisc(c, 820, 706, 80, 0xFFFFC27E, 0xFFFFB46B, 0.45f);
                break;
            case NIGHT:
                if (moonUp) drawMoon(c, 1480, 230, 54);
                break;
        }

        // Hills lose a little colour under a grey sky, less than the sky itself.
        float hg = g * 0.35f;
        fill(c, far, hill(pal.h1, hg, 0.55f));
        if (sky == Sky.FOG) {
            fogBank(c, 300, 420, 0.45f);
            fogBank(c, 560, 320, 0.9f);
        }
        fill(c, mid, hill(pal.h2, hg, 0.6f));

        if (pal.glow > 0) {
            int a = Math.round(pal.glow * 0x99);
            int core = (a << 24) | (pal.win & 0xFFFFFF);
            paint.setShader(new RadialGradient(1564, 746, 110, core, pal.win & 0xFFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(1564, 746, 110, paint);
            paint.setShader(null);
        }

        // Cottage, chimney and a few trees on the middle ridge.
        paint.setColor(pal.h3);
        c.drawRect(1520, 722, 1610, 778, paint);
        Path roof = new Path();
        roof.moveTo(1508, 726);
        roof.lineTo(1565, 684);
        roof.lineTo(1622, 726);
        roof.close();
        c.drawPath(roof, paint);
        c.drawRect(1588, 690, 1601, 716, paint);
        tree(c, 1650, 782, 34, 54);
        tree(c, 1690, 784, 38, 68);
        tree(c, 1468, 780, 28, 42);
        paint.setColor(pal.win);
        c.drawRect(1540, 738, 1558, 754, paint);
        c.drawRect(1570, 738, 1588, 754, paint);

        fill(c, near, hill(pal.h3, 0f, 0.65f));

        // Fog drifts low over the valley.
        if (sky == Sky.FOG) {
            fogBank(c, 740, 260, 0.85f);
            fogBank(c, 880, 200, 0.5f);
        }
    }

    // Purpose: a hill colour, turned snowy under snowfall (the nearer the hill, the more snow shows).
    private int hill(int c, float hg, float snow) {
        int base = grey(c, hg);
        if (sky != Sky.SNOW) return base;
        int white = phase == Phase.NIGHT ? 0xFF3A4258 : phase == Phase.DAY ? 0xFFE4EAF0 : 0xFFB8B0C4;
        return lerp(base, white, snow);
    }

    // Purpose: a soft radial brightening with no disc, for a sun behind cloud.
    private void softGlow(Canvas c, float x, float y, float r, int color, float alpha) {
        int a = Math.round(alpha * 255) << 24;
        paint.setShader(new RadialGradient(x, y, r, a | (color & 0xFFFFFF), color & 0xFFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, paint);
        paint.setShader(null);
    }

    // Purpose: a horizontal band of fog, densest in the middle, fading up and down.
    private void fogBank(Canvas c, float top, float height, float alpha) {
        int fog = phase == Phase.NIGHT ? 0x8090A0 : phase == Phase.DAY ? 0xE6EAEE : 0xC8BCC4;
        int a = Math.round(alpha * 255) << 24;
        paint.setShader(new LinearGradient(0, top, 0, top + height, new int[]{fog, a | fog, fog},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, top, W, top + height, paint);
        paint.setShader(null);
    }

    private void glowDisc(Canvas c, float x, float y, float r, int disc, int glow, float glowAlpha) {
        glow(c, x, y, r, glow, glowAlpha);
        paint.setColor(disc);
        c.drawCircle(x, y, r, paint);
    }

    private static int lerp(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /**
     * The moon in its phase, as seen from the northern hemisphere: lit on the right while waxing, on
     * the left while waning. The lit part is the half disc on that side, less (crescent) or plus
     * (gibbous) the half ellipse of the terminator. Near new moon nothing is drawn.
     */
    private void drawMoon(Canvas c, float x, float y, float r) {
        double angle = 2 * Math.PI * moonStep / MOON_STEPS;
        float lit = (float) Moon.illuminated(angle);
        if (lit < 0.03f) return;
        int color = 0xFFF1EAD8;
        glow(c, x, y, r, color, 0.08f + 0.14f * lit);

        // Earthshine: the dark part of the disc is faintly visible.
        fill(c, disc(x, y, r), 0x1FF1EAD8);

        Path moon;
        if (lit > 0.97f) {
            moon = disc(x, y, r);
        } else {
            boolean waxing = angle < Math.PI;
            moon = new Path();
            moon.addArc(new RectF(x - r, y - r, x + r, y + r), waxing ? -90 : 90, 180);
            moon.close();
            float rx = r * (float) Math.abs(Math.cos(angle));
            Path terminator = new Path();
            terminator.addOval(new RectF(x - rx, y - r, x + rx, y + r), Path.Direction.CW);
            moon.op(terminator, lit < 0.5f ? Path.Op.DIFFERENCE : Path.Op.UNION);
        }
        fill(c, moon, color);
    }

    private static Path disc(float x, float y, float r) {
        Path p = new Path();
        p.addCircle(x, y, r, Path.Direction.CW);
        return p;
    }

    /** The soft halo around a disc of radius r, without the disc itself. */
    private void glow(Canvas c, float x, float y, float r, int color, float alpha) {
        int a = Math.round(alpha * 255) << 24;
        paint.setShader(new RadialGradient(x, y, r * 2.6f, a | (color & 0xFFFFFF), color & 0xFFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r * 2.6f, paint);
        paint.setShader(null);
    }

    private void tree(Canvas c, float x, float baseY, float w, float h) {
        Path p = new Path();
        p.moveTo(x, baseY);
        p.lineTo(x + w / 2, baseY - h);
        p.lineTo(x + w, baseY);
        p.close();
        c.drawPath(p, paint);
    }

    private void fill(Canvas c, Path p, int color) {
        paint.setColor(color);
        c.drawPath(p, paint);
    }

    // ---- each frame -------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        if (still == null) return;
        canvas.drawBitmap(still, 0, 0, null);
        float seconds = (SystemClock.uptimeMillis() - start) / 1000f;
        canvas.save();
        canvas.scale(getWidth() / W, getHeight() / H);
        // Stars only through a clear or broken sky.
        if (!overcast() && (phase == Phase.NIGHT || phase == Phase.DUSK)) {
            float strength = phase == Phase.NIGHT ? 1f : 0.35f;
            drawStars(canvas, seconds, sky == Sky.PARTLY ? strength * 0.6f : strength);
        }

        // Clouds by day as before; at any hour when the weather brings them.
        if (sky != Sky.CLEAR && (overcast() || phase == Phase.DAWN || phase == Phase.DAY)) drawClouds(canvas, seconds);
        if (phase != Phase.DAY) drawSmoke(canvas, seconds);

        // Falling weather in front of everything.
        if (sky == Sky.RAIN || sky == Sky.STORM) drawRain(canvas, seconds);
        if (sky == Sky.SNOW) drawSnow(canvas, seconds);
        canvas.restore();
        if (sky == Sky.STORM) drawLightning(canvas, seconds);
        if (running) postInvalidateDelayed(FRAME_MS);
    }

    private void drawStars(Canvas c, float s, float strength) {
        for (int i = 0; i < STARS.length; i++) {
            float[] st = STARS[i];
            // Every other star twinkles, each on its own slow period.
            float twinkle = i % 2 == 0 ? 1f : 0.65f + 0.35f * (float) Math.sin(s * (0.9f + i * 0.13f) + i);
            fx.setColor(Color.argb(Math.round(240 * strength * twinkle), 255, 255, 255));
            c.drawCircle(st[0], st[1], st[2], fx);
        }
    }

    private void drawClouds(Canvas c, float s) {
        // Drift 230 px one way over 70 s, then back: the mockup's ease-in-out alternate.
        float t = (float) (0.5 - 0.5 * Math.cos(Math.PI * s / 70.0));
        float dx = -90 + 230 * t;

        // Cloud colour: white by day, grey under rain, dark blue-grey at night.
        int alpha = phase == Phase.DAY ? 150 : 120;
        int r = 255, gr = 255, b = 255;
        if (phase == Phase.NIGHT || phase == Phase.DUSK) { r = 70; gr = 78; b = 100; alpha = 150; }
        else if (sky == Sky.RAIN || sky == Sky.STORM) { r = 170; gr = 176; b = 186; alpha = 170; }
        else if (overcast()) { r = 236; gr = 238; b = 242; alpha = 160; }
        if (sky == Sky.FOG) alpha = Math.round(alpha * 0.45f);
        fx.setColor(Color.argb(alpha, r, gr, b));
        for (float[] cl : CLOUDS) {
            c.drawOval(cl[0] - cl[2] + dx, cl[1] - cl[3], cl[0] + cl[2] + dx, cl[1] + cl[3], fx);
        }

        // A cloudy sky gets the extra puffs, drifting a little slower for depth; fog hides the sky instead.
        if (overcast() && sky != Sky.FOG) {
            float dx2 = dx * 0.6f;
            for (float[] cl : MORE_CLOUDS) {
                c.drawOval(cl[0] - cl[2] + dx2, cl[1] - cl[3], cl[0] + cl[2] + dx2, cl[1] + cl[3], fx);
            }
        }
    }

    // Purpose: slanted rain streaks falling across the scene.
    private void drawRain(Canvas c, float s) {
        fx.setStrokeWidth(2.2f);
        fx.setColor(Color.argb(phase == Phase.DAY ? 110 : 80, 220, 230, 245));
        for (int i = 0; i < precip; i++) {
            // Each drop has its own fixed column, speed and start, so the pattern never repeats visibly.
            float x0 = (i * 977) % 2100 - 90;
            float speed = 900 + (i * 53) % 400;
            float y = ((i * 431) % 1200 + s * speed) % 1200 - 60;
            float x = x0 - y * 0.12f;
            c.drawLine(x, y, x - 4, y + 30, fx);
        }
    }

    // Purpose: snowflakes drifting down with a gentle sideways sway.
    private void drawSnow(Canvas c, float s) {
        for (int i = 0; i < precip; i++) {
            float x0 = (i * 613) % 1980 - 30;
            float speed = 45 + (i * 37) % 60;
            float y = ((i * 271) % 1140 + s * speed) % 1140 - 30;
            float x = x0 + 18 * (float) Math.sin(s * 0.8f + i);
            float rad = 2f + (i % 4) * 0.8f;
            fx.setColor(Color.argb(170 + (i % 3) * 25, 255, 255, 255));
            c.drawCircle(x, y, rad, fx);
        }
    }

    // Purpose: a brief double flash of lightning every eleven seconds.
    private void drawLightning(Canvas c, float s) {
        float t = s % 11f;
        if (t < 0.12f || (t > 0.25f && t < 0.33f)) c.drawColor(Color.argb(70, 255, 255, 255));
    }

    private void drawSmoke(Canvas c, float s) {
        for (int i = 0; i < 3; i++) {
            float t = ((s + i * 2.33f) % 7f) / 7f;              // 0..1 over a 7 s rise
            float alpha = t < 0.2f ? t / 0.2f * 0.45f : 0.45f * (1 - (t - 0.2f) / 0.8f);
            float r = 10 * (0.6f + 1.1f * t);
            fx.setColor(Color.argb(Math.round(alpha * 255), 0xD8, 0xD2, 0xE2));
            c.drawCircle(1595 + 34 * t, 680 - 130 * t, r, fx);
        }
    }
}
