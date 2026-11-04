package io.freemytv.library;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** The calm background of every screen: a deep gradient with a few soft coloured glows, like a night sky. */
final class Aurora extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader base;
    private final Shader[] glows = new Shader[4];
    private final boolean light;

    Aurora(boolean light) { this.light = light; }

    @Override protected void onBoundsChange(Rect b) {
        float w = b.width(), h = b.height();
        base = light ? new LinearGradient(0, 0, 0, h, 0xFFF4F6FB, 0xFFEAE6F6, Shader.TileMode.CLAMP)
                : new LinearGradient(0, 0, 0, h, 0xFF0A0E1A, 0xFF130C26, Shader.TileMode.CLAMP);
        glows[0] = glow(w * 0.12f, h * 0.18f, w * 0.55f, light ? 0x553B6BFF : 0x663B6BFF);
        glows[1] = glow(w * 0.88f, h * 0.12f, w * 0.45f, light ? 0x44A855F7 : 0x55A855F7);
        glows[2] = glow(w * 0.72f, h * 0.98f, w * 0.60f, light ? 0x4422C7B8 : 0x4422C7B8);
        glows[3] = glow(w * 0.04f, h * 0.92f, w * 0.40f, light ? 0x44FF6B8A : 0x44FF6B8A);
    }

    private static Shader glow(float x, float y, float r, int color) {
        return new RadialGradient(x, y, Math.max(1f, r), color, color & 0x00FFFFFF, Shader.TileMode.CLAMP);
    }

    @Override public void draw(Canvas c) {
        Rect b = getBounds();
        if (base == null) return;
        paint.setShader(base);
        c.drawRect(b, paint);
        for (Shader g : glows) {
            paint.setShader(g);
            c.drawRect(b, paint);
        }
    }

    @Override public void setAlpha(int alpha) { }

    @Override public void setColorFilter(ColorFilter cf) { }

    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
