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

    static final int BG = 0xFF0D1017, SURFACE = 0xFF1B2130, SURFACE_HI = 0xFF283148, ACCENT = 0xFF6EA8FE, TEXT = 0xFFEEF1F6,
            DIM = 0xFF9AA5B8, WARN = 0xFFFFB86B;

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

    /** A focusable button: dark, bright when the remote is on it. */
    static TextView button(Context c, String label) {
        TextView t = text(c, label, 17, TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 22), dp(c, 12), dp(c, 22), dp(c, 12));
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(focusBackground(c, SURFACE, ACCENT, 10));
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                ((TextView) v).setTextColor(has ? 0xFF0B1220 : TEXT);
            }
        });
        return t;
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
