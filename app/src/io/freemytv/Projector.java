package io.freemytv;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * "Projector mode": the home screen and the screensaver for a projector in a dark room. The dark design is always on,
 * everything is a little dimmer (peak brightness is glare on a laser projector), and the edges darken in a soft
 * vignette that hides the light bleeding around the picture's border. Automatic = on for Fengmi / Formovie projectors.
 */
final class Projector {
    private Projector() {}

    private static final int DIM = 0x26000000, EDGE = 0xB8000000;
    private static boolean detected, detectedKnown;
    private static boolean cachedOn;
    private static int cachedVersion = -1;

    /** Projector mode as picked in the settings ("auto", "on", "off"), worked out once for each settings change. */
    static boolean on() {
        Context c = Prefs.context();
        if (cachedVersion == Prefs.version) return cachedOn;
        String mode = Prefs.projector();
        cachedOn = mode.equals("on") || (mode.equals("auto") && isProjector(c));
        cachedVersion = Prefs.version;
        return cachedOn;
    }

    /** Fengmi (Formovie) projectors, by their own launcher or settings apps. */
    static synchronized boolean isProjector(Context c) {
        if (!detectedKnown) {
            PackageManager pm = c.getPackageManager();
            for (String pkg : new String[] {"com.tvfengmi.launcher", "com.formovie.launchboard", "com.fengmi.projector.imaging"}) {
                try {
                    pm.getPackageInfo(pkg, 0);
                    detected = true;
                    break;
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
            detectedKnown = true;
        }
        return detected;
    }

    /** The paint for the edges: clear in the middle, dark at the corners. */
    static Paint vignette(int w, int h) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = (float) Math.hypot(w, h) / 2f;
        p.setShader(new RadialGradient(w / 2f, h / 2f, r, new int[] {0, 0, EDGE}, new float[] {0f, 0.58f, 1f}, Shader.TileMode.CLAMP));
        return p;
    }

    static Paint dim() {
        Paint p = new Paint();
        p.setColor(DIM);
        return p;
    }

    static void draw(Canvas c, int w, int h, Paint dim, Paint vignette) {
        c.drawRect(0, 0, w, h, dim);
        c.drawRect(0, 0, w, h, vignette);
    }
}
