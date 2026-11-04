package io.freemytv.library;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Locale;

/** Colors and small view helpers: everything is built in code, one dark look for the whole app. */
final class Ui {
    private Ui() {}

    static int BG = 0xFF0D1017, SURFACE = 0xFF1B2130, SURFACE_HI = 0xFF283148, ACCENT = 0xFF6EA8FE, TEXT = 0xFFEEF1F6,
            DIM = 0xFF9AA5B8, WARN = 0xFFFFB86B;
    /** Over a video the text stays white whatever the theme is. */
    static final int VIDEO_TEXT = 0xFFFFFFFF, VIDEO_DIM = 0xFFC3CAD8;
    private static boolean light, black;

    /** Sets the colours for the chosen theme; returns a short code of the look, to tell a screen it is out of date. */
    static String refresh() {
        String t = Prefs.theme();
        light = "light".equals(t);
        black = "black".equals(t);
        if (light) {
            BG = 0xFFF2F4F8; SURFACE = 0xFFFFFFFF; SURFACE_HI = 0xFFE3E8F1; ACCENT = 0xFF1F6FEB; TEXT = 0xFF111827;
            DIM = 0xFF5B6678; WARN = 0xFFB45309;
        } else if (black) {
            BG = 0xFF000000; SURFACE = 0xFF0C0C0C; SURFACE_HI = 0xFF1A1A1A; ACCENT = 0xFF6EA8FE; TEXT = 0xFFEDEDED;
            DIM = 0xFF8E8E93; WARN = 0xFFFFB86B;
        } else {
            BG = 0xFF0D1017; SURFACE = 0xFF1B2130; SURFACE_HI = 0xFF283148; ACCENT = 0xFF6EA8FE; TEXT = 0xFFEEF1F6;
            DIM = 0xFF9AA5B8; WARN = 0xFFFFB86B;
        }
        return t + "|" + Prefs.scene();
    }

    static boolean light() { return light; }

    static boolean black() { return black; }

    /** Glass is white over a dark background and dark over a light one. */
    private static int g(int alpha) {
        return light ? ((alpha * 2 / 5) << 24) : (alpha << 24) | 0xFFFFFF;
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable round(int color, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView bold(Context c, String s, float sp, int color) {
        TextView t = text(c, s, sp, color);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** A focusable button of frosted glass: lighter, with a thin bright edge, while the remote is on it. */
    static TextView button(Context c, String label) {
        TextView t = text(c, label, 17, TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 22), dp(c, 12), dp(c, 22), dp(c, 12));
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(glassFocus(c, 14, false));
        return t;
    }

    // MARK: - Glass: translucent white over the background, a thin lighter edge on top; no heavy outlines

    private static GradientDrawable glassShape(Context c, float radiusDp, int top, int bottom, int edge, float edgeDp) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {top, bottom});
        d.setCornerRadius(dp(c, radiusDp));
        d.setStroke(Math.max(1, dp(c, edgeDp)), edge);
        return d;
    }

    /** Rounds the corners of a picture. */
    static void roundCorners(View v, final float radiusPx) {
        v.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline o) { o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx); }
        });
        v.setClipToOutline(true);
    }

    /** A resting glass panel. */
    static GradientDrawable glass(Context c, float radiusDp) {
        return glassShape(c, radiusDp, g(0x2E), g(0x12), g(0x33), 1f);
    }

    /** Glass that lights up (the one the remote is on). */
    static GradientDrawable glassLit(Context c, float radiusDp) {
        return glassShape(c, radiusDp, g(0x52), g(0x2E), g(0x80), 1.25f);
    }

    /** A button or tile: glass at rest, lit when focused; `on` keeps it lit (the chosen one of a group). */
    static StateListDrawable glassFocus(Context c, float radiusDp, boolean on) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, glassLit(c, radiusDp));
        s.addState(new int[] {android.R.attr.state_pressed}, glassLit(c, radiusDp));
        s.addState(new int[] {}, on ? glassShape(c, radiusDp, g(0x3D), g(0x1F), g(0x55), 1f) : glass(c, radiusDp));
        return s;
    }

    /** A row of a list: nothing at rest, glass when focused. */
    static StateListDrawable rowFocus(Context c, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, glassLit(c, radiusDp));
        s.addState(new int[] {android.R.attr.state_pressed}, glassLit(c, radiusDp));
        s.addState(new int[] {}, round(0, radiusDp, c));
        return s;
    }

    /** The same for a ListView row: lit while the list's selection is on it. */
    static StateListDrawable rowSelection(Context c, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_selected}, glassLit(c, radiusDp));
        s.addState(new int[] {android.R.attr.state_pressed}, glassLit(c, radiusDp));
        s.addState(new int[] {}, round(0, radiusDp, c));
        return s;
    }

    static StateListDrawable focusBackground(Context c, int normal, int focused, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, round(focused, radiusDp, c));
        s.addState(new int[] {android.R.attr.state_pressed}, round(focused, radiusDp, c));
        s.addState(new int[] {}, round(normal, radiusDp, c));
        return s;
    }

    /** For rows of a list: bright while the list's selection is on them. */
    static StateListDrawable selectionBackground(Context c, int normal, int selected, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_selected}, round(selected, radiusDp, c));
        s.addState(new int[] {android.R.attr.state_pressed}, round(selected, radiusDp, c));
        s.addState(new int[] {}, round(normal, radiusDp, c));
        return s;
    }

    static ViewGroup.MarginLayoutParams margins(ViewGroup.MarginLayoutParams p, Context c, int l, int t, int r, int b) {
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    /** 1:23:45 or 23:45 */
    static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec) : String.format(Locale.US, "%d:%02d", m, sec);
    }

    static String size(long bytes) {
        double gb = bytes / 1073741824.0;
        if (gb >= 1) return String.format(Locale.US, "%.1f ГБ", gb);
        return String.format(Locale.US, "%d МБ", Math.max(1, bytes / 1048576));
    }

    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
