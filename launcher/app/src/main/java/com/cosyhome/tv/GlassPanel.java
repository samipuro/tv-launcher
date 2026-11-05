package com.cosyhome.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * A frosted panel: the blurred scene behind it, a translucent tint and a hairline border.
 * The blurred bitmap covers the whole screen; each panel draws the part under itself.
 */
public class GlassPanel extends FrameLayout {

    private static Bitmap source;
    private static int tint = 0x6B0A0E1E;

    private final float radius;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF box = new RectF();
    private final RectF screen = new RectF();
    private final int[] at = new int[2];

    public GlassPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWillNotDraw(false);
        float d = getResources().getDisplayMetrics().density;
        radius = 20 * d;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(d);
        stroke.setColor(0x38FFFFFF);
    }

    /** The blurred scene and the phase's glass tint, shared by every panel. */
    static void setSource(Bitmap blurred, int glassTint) {
        source = blurred;
        tint = glassTint;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        box.set(0, 0, getWidth(), getHeight());
        float r = Math.min(radius, getHeight() / 2f);
        if (source != null) {
            // On screen, not in the window: the tile menu is a panel in a popup window of its own.
            getLocationOnScreen(at);
            // The blurred scene spans the whole screen, which a popup's root view does not.
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            screen.set(-at[0], -at[1], -at[0] + dm.widthPixels, -at[1] + dm.heightPixels);
            clip.reset();
            clip.addRoundRect(box, r, r, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawBitmap(source, null, screen, bitmapPaint);
            canvas.restore();
        }
        fill.setColor(tint);
        canvas.drawRoundRect(box, r, r, fill);
        box.inset(0.5f, 0.5f);
        canvas.drawRoundRect(box, r, r, stroke);
    }
}
