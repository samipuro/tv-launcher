package com.cosyhome.tv;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.Consumer;

/**
 * One app: its banner (or icon and name when it has none), zooming on focus. Dock tiles get a strong glow in their own
 * colour and a thin bright rim; grid tiles get a crisp white border only, so nothing spills onto their neighbours.
 */
@SuppressLint("ViewConstructor")
final class Tile extends FrameLayout {

    static final float FOCUS_SCALE = 1.12f;
    static final float DOCK_FOCUS_SCALE = 1.16f;

    final Apps.App app;
    private final float restAlpha;
    private final boolean dockStyle;
    private final float focusScale;
    private final float lift;
    private final float radius;
    private final float glowRadius;
    private final float rimWidth;
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF glowRect = new RectF();
    /** 0 at rest, 1 when focused; animated. */
    private float glow;
    private ValueAnimator glowAnim;
    private Consumer<Tile> onFocus;
    /** The rounded, clipped frame the artwork sits in. */
    private final FrameLayout art;

    Tile(Context ctx, Apps.App app, float restAlpha, boolean dockStyle) {
        super(ctx);
        this.app = app;
        this.restAlpha = restAlpha;
        this.dockStyle = dockStyle;
        float d = getResources().getDisplayMetrics().density;
        focusScale = dockStyle ? DOCK_FOCUS_SCALE : FOCUS_SCALE;
        lift = 8 * d;
        radius = 9 * d;
        glowRadius = 16 * d;

        // Rim on top of the artwork: thin and bright in the dock, a solid 3 dp border with a dark inner edge in the grid.
        rimWidth = (dockStyle ? 2f : 3f) * d;
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(rimWidth);
        rimPaint.setColor(0xFFFFFFFF);
        innerPaint.setStyle(Paint.Style.STROKE);
        innerPaint.setStrokeWidth(1.5f * d);
        innerPaint.setColor(0x73000000);
        setFocusable(true);
        setClickable(true);
        setWillNotDraw(false);
        setAlpha(restAlpha);
        setContentDescription(app.label);

        // The tile itself does not clip, so its glow can spread around it; the artwork lives in a rounded, clipped frame.
        ViewOutlineProvider rounded = new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        };
        setOutlineProvider(rounded);
        art = new FrameLayout(ctx);
        art.setBackgroundColor(0xFF3A3A3C);
        art.setOutlineProvider(rounded);
        art.setClipToOutline(true);
        addView(art, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        // Glow colour sampled from the artwork, lifted towards white so dark banners still glow. Only dock
        // tiles glow, and their banners are decoded up front: sampling a grid tile's deferred banner here
        // would decode it on the main thread.
        if (dockStyle) {
            int base = averageColor(app.banner != null ? app.banner : app.icon);
            glowPaint.setColor(blend(base, 0xFFFFFFFF, 0.7f));
            glowPaint.setMaskFilter(new BlurMaskFilter(glowRadius, BlurMaskFilter.Blur.NORMAL));
        }

        if (app.banner != null) {
            ImageView banner = new ImageView(ctx);
            banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
            banner.setImageDrawable(app.banner);
            art.addView(banner, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            if (app.banner instanceof Apps.LazyBanner) {
                // A deferred banner that fails to decode falls back to the icon and the name.
                ((Apps.LazyBanner) app.banner).setOnMissing(icon -> post(() -> showIconAndLabel(icon)));
            }
        } else {
            showIconAndLabel(app.icon);
        }

        // Holding OK opens the tile menu, timed by HomeActivity: View's own long click misses on some remotes.
        setOnClickListener(v -> launch());
        setOnFocusChangeListener((v, focused) -> {
            animate().cancel();
            animate().scaleX(focused ? focusScale : 1f).scaleY(focused ? focusScale : 1f)
                    .alpha(focused ? 1f : restAlpha)
                    .translationZ(focused ? lift : 0f)
                    .setDuration(focused ? 240 : 160)
                    .setInterpolator(new OvershootInterpolator(focused ? 1.4f : 0f))
                    .start();
            animateGlow(focused ? 1f : 0f, focused ? 240 : 160);
            if (focused && onFocus != null) onFocus.accept(this);
        });
    }

    // Purpose: the tile for an app with no banner: the icon, and the name under it.
    private void showIconAndLabel(Drawable icon) {
        float d = getResources().getDisplayMetrics().density;
        art.removeAllViews();
        ImageView iconView = new ImageView(getContext());
        iconView.setImageDrawable(icon);
        LayoutParams ilp = new LayoutParams((int) (28 * d), (int) (28 * d), Gravity.CENTER);
        ilp.bottomMargin = (int) (10 * d);
        art.addView(iconView, ilp);
        TextView label = new TextView(getContext());
        label.setText(app.label);
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(11f);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setTypeface(getResources().getFont(R.font.outfit));
        LayoutParams llp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        llp.bottomMargin = (int) (6 * d);
        art.addView(label, llp);
    }

    // Purpose: fade the glow in or out.
    private void animateGlow(float to, long ms) {
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofFloat(glow, to).setDuration(ms);
        glowAnim.addUpdateListener(a -> {
            glow = (float) a.getAnimatedValue();
            invalidate();
        });
        glowAnim.start();
    }

    // Purpose: dock tiles only, draw the glow behind the artwork; the parent must not clip children so it can spread outside the tile.
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!dockStyle || glow <= 0f) return;
        glowPaint.setAlpha(Math.round(215 * glow));
        float grow = glowRadius * 0.3f;
        glowRect.set(-grow, -grow, getWidth() + grow, getHeight() + grow);
        canvas.drawRoundRect(glowRect, radius + grow, radius + grow, glowPaint);
    }

    // Purpose: draw the focus rim over the artwork, inside the tile's own bounds.
    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (glow <= 0f) return;

        // Bright rim, fading with the focus animation.
        float half = rimWidth / 2f;
        glowRect.set(half, half, getWidth() - half, getHeight() - half);
        rimPaint.setAlpha(Math.round((dockStyle ? 235 : 255) * glow));
        canvas.drawRoundRect(glowRect, radius - half, radius - half, rimPaint);

        // Grid tiles: a thin dark edge inside the white border, so it shows on white banners too.
        if (!dockStyle) {
            float in = rimWidth + innerPaint.getStrokeWidth() / 2f;
            glowRect.set(in, in, getWidth() - in, getHeight() - in);
            innerPaint.setAlpha(Math.round(115 * glow));
            canvas.drawRoundRect(glowRect, radius - in, radius - in, innerPaint);
        }
    }

    // Purpose: the average colour of a drawable, from an 8x8 downscale; grey when there is none.
    private static int averageColor(Drawable dr) {
        if (dr == null) return 0xFF8A8A8A;
        Bitmap bmp = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        dr.setBounds(0, 0, 8, 8);
        dr.draw(c);
        long r = 0, g = 0, b = 0;
        int n = 0;

        // Skip transparent pixels so icons on clear backgrounds take the icon's colour.
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int p = bmp.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                r += Color.red(p);
                g += Color.green(p);
                b += Color.blue(p);
                n++;
            }
        }
        bmp.recycle();
        return n == 0 ? 0xFF8A8A8A : Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
    }

    // Purpose: mix two colours, t = 0 gives a, 1 gives b.
    private static int blend(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    void setOnTileFocused(Consumer<Tile> listener) {
        onFocus = listener;
    }

    private void launch() {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (!open(i)) {
            Toast.makeText(getContext(), getContext().getString(R.string.cant_open, app.label), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}
