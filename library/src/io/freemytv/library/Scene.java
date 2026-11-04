package io.freemytv.library;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

/** The calm pictures behind every screen, drawn in code: a sky and three soft layers of hills, dunes or waves. */
final class Scene extends Drawable {
    /** The drawable for the chosen background and theme. */
    static Drawable make() {
        String kind = Prefs.scene();
        if ("plain".equals(kind)) return new ColorDrawable(Ui.BG);
        if ("mountains".equals(kind) || "dunes".equals(kind) || "ocean".equals(kind)) return new Scene(kind, Ui.light());
        return new Aurora(Ui.light());     // "aurora" and "film" (the film's picture comes over it)
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int skyTop, skyBottom;
    private final int[] layer = new int[3];
    private final boolean waves;
    private final float[] roughness;
    private final Path[] paths = new Path[3];
    private Shader sky;

    private Scene(String kind, boolean light) {
        boolean sharp = "mountains".equals(kind);
        waves = "ocean".equals(kind);
        roughness = sharp ? new float[] {0.9f, 0.7f, 0.5f} : new float[] {0.45f, 0.35f, 0.25f};
        if (sharp) {
            skyTop = light ? 0xFFCFE0F5 : 0xFF0B1426; skyBottom = light ? 0xFFF6E7E0 : 0xFF3A3F6B;
            layer[0] = light ? 0xFFB4C4DE : 0xFF2A3050; layer[1] = light ? 0xFF97A9CB : 0xFF1D2340; layer[2] = light ? 0xFF7A8DB3 : 0xFF121830;
        } else if (waves) {
            skyTop = light ? 0xFFD6F0F7 : 0xFF06121F; skyBottom = light ? 0xFFAEDDEB : 0xFF0F3552;
            layer[0] = light ? 0xFF8FCBDD : 0xFF14486A; layer[1] = light ? 0xFF6DB5CC : 0xFF0E3550; layer[2] = light ? 0xFF4B9DB9 : 0xFF08233A;
        } else {
            skyTop = light ? 0xFFFBE6CF : 0xFF1A1220; skyBottom = light ? 0xFFF8CFA8 : 0xFF5A3430;
            layer[0] = light ? 0xFFEFB98C : 0xFF5B3A33; layer[1] = light ? 0xFFE3A072 : 0xFF3F2A2B; layer[2] = light ? 0xFFD08A5E : 0xFF2A1D22;
        }
    }

    @Override protected void onBoundsChange(Rect b) {
        float w = b.width(), h = b.height();
        sky = new LinearGradient(0, 0, 0, h, skyTop, skyBottom, Shader.TileMode.CLAMP);
        for (int i = 0; i < 3; i++) {
            float base = h * (0.58f + 0.13f * i);           // each layer lower and nearer than the last
            float amp = h * 0.11f * roughness[i] * (waves ? 0.35f : 1f);
            Path p = new Path();
            p.moveTo(0, h);
            int steps = 90;
            for (int s = 0; s <= steps; s++) {
                float x = w * s / steps;
                double t = (double) s / steps * Math.PI * 2;
                double v = Math.sin(t * (1.3 + i * 0.7) + i * 2.1) * 0.55 + Math.sin(t * (3.1 + i) + i) * 0.30
                        + (waves ? 0 : Math.sin(t * (7.0 + 2 * i) + i * 0.7) * 0.15 * roughness[i]);
                p.lineTo(x, base - (float) v * amp);
            }
            p.lineTo(w, h);
            p.close();
            paths[i] = p;
        }
    }

    @Override public void draw(Canvas c) {
        if (sky == null) return;
        paint.setShader(sky);
        c.drawRect(getBounds(), paint);
        paint.setShader(null);
        for (int i = 0; i < 3; i++) {
            paint.setColor(layer[i]);
            c.drawPath(paths[i], paint);
        }
    }

    @Override public void setAlpha(int alpha) { }

    @Override public void setColorFilter(ColorFilter cf) { }

    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
