package io.freemytv.library;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * The background layer of a screen: the soft Aurora, and over it, when a film is chosen, its picture strongly blurred
 * (a tiny copy drawn large) and darkened, fading from one film to the next.
 */
final class Backdrop extends FrameLayout {
    private final ImageView a, b;
    private ImageView front;
    private String want = "";
    private final boolean film, hero;

    Backdrop(Context c) { this(c, false); }

    /** `hero`: the film's picture stays visible (its own page), with a veil that deepens towards the bottom. */
    Backdrop(Context c, boolean hero) {
        super(c);
        this.hero = hero;
        setBackground(Scene.make());
        film = hero || "film".equals(Prefs.scene());
        a = layer(c);
        b = layer(c);
        front = a;
        View scrim = new View(c);
        int tint = Ui.light() ? 0xFFFFFF : Ui.black() ? 0x000000 : 0x080B14;
        // A film's picture needs a strong veil to keep the text readable; a plain scene only a light one at the bottom.
        int top = film ? (hero ? 0x2A : 0x66) : 0x00, bottom = film ? (hero ? 0xE0 : 0xB3) : 0x40;
        // The black theme keeps the picture only as a faint shape in the dark.
        if (Ui.black()) { top = film ? 0xA0 : 0x99; bottom = film ? 0xE0 : 0xCC; }
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {(top << 24) | tint, (bottom << 24) | tint}));
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private ImageView layer(Context c) {
        ImageView v = new ImageView(c);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setAlpha(0f);
        addView(v, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        return v;
    }

    /** Shows the picture at this TMDB path (empty: only the Aurora). */
    void show(Context c, final String path) {
        if (!film) return;
        final String p = path == null ? "" : path;
        if (p.equals(want)) return;
        want = p;
        if (p.isEmpty()) {
            fade(null);
            return;
        }
        Posters.backdrop(c, p, hero ? 480 : 64, new Posters.Sink() {
            @Override public void got(Bitmap bm) {
                if (p.equals(want)) fade(bm);
            }
        });
    }

    private void fade(Bitmap bm) {
        ImageView in = front == a ? b : a;
        ImageView out = front;
        if (bm != null) {
            in.setImageBitmap(bm);
            in.animate().alpha(1f).setDuration(500).start();
        }
        out.animate().alpha(0f).setDuration(500).start();
        front = in;
    }
}
