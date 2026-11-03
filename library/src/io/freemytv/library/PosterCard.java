package io.freemytv.library;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** A poster with its title below; grows and gets a bright frame while the remote is on it. */
final class PosterCard extends FrameLayout {
    final ImageView image;
    final TextView title;
    final TextView badge;
    final View bar;
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float progress;
    private final int posterHeight;

    PosterCard(Context c, int posterWidth) {
        super(c);
        posterHeight = Math.round(posterWidth * 1.5f);
        setClipChildren(false);
        setClipToPadding(false);
        image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(Ui.round(Ui.SURFACE, 8, c));
        addView(image, new LayoutParams(posterWidth, posterHeight));
        title = Ui.text(c, "", 14, Ui.TEXT);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LayoutParams tl = new LayoutParams(posterWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = posterHeight + Ui.dp(c, 6);
        addView(title, tl);
        bar = new View(c);
        bar.setBackgroundColor(Ui.ACCENT);
        addView(bar, new LayoutParams(0, Ui.dp(c, 4), Gravity.TOP | Gravity.START));
        ((LayoutParams) bar.getLayoutParams()).topMargin = posterHeight - Ui.dp(c, 4);
        bar.setVisibility(View.GONE);
        badge = Ui.bold(c, "", 13, 0xFF0B1220);
        badge.setBackground(Ui.round(Ui.ACCENT, 6, c));
        badge.setPadding(Ui.dp(c, 7), Ui.dp(c, 3), Ui.dp(c, 7), Ui.dp(c, 3));
        LayoutParams bl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        bl.setMargins(0, Ui.dp(c, 6), Ui.dp(c, 6), 0);
        addView(badge, bl);
        badge.setVisibility(View.GONE);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(Ui.dp(c, 3));
        frame.setColor(Ui.ACCENT);
        setWillNotDraw(false);
    }

    int cellHeight(Context c) { return posterHeight + Ui.dp(c, 6) + Ui.dp(c, 40); }

    /** progress 0..1 draws the "watched so far" line; a label ("✓", "5 сер.") goes in the corner. */
    void set(String name, float progress, String label) {
        title.setText(name);
        this.progress = progress;
        bar.setVisibility(progress > 0 ? View.VISIBLE : View.GONE);
        if (progress > 0) bar.getLayoutParams().width = Math.max(1, Math.round(image.getLayoutParams().width * progress));
        bar.requestLayout();
        badge.setVisibility(label == null || label.isEmpty() ? View.GONE : View.VISIBLE);
        if (label != null) badge.setText(label);
    }

    private boolean lit() {
        return isSelected() && getParent() instanceof View && ((View) getParent()).hasFocus();
    }

    @Override public void setSelected(boolean selected) {
        super.setSelected(selected);
        title.setTextColor(selected ? Ui.ACCENT : Ui.TEXT);   // no enlarging: nothing can be cut off at the edges
        setTranslationZ(selected ? Ui.dp(getContext(), 12) : 0f);   // above the neighbours without changing their order
        invalidate();
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (lit()) {
            float r = Ui.dp(getContext(), 8);
            float inset = frame.getStrokeWidth() * 0.75f;   // inside the poster, so the frame is never cut off
            rect.set(image.getLeft() + inset, image.getTop() + inset, image.getRight() - inset, image.getBottom() - inset);
            canvas.drawRoundRect(rect, r, r, frame);
        }
    }
}
