package io.freemytv.library;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** A poster with its title below. The one the remote is on gets a thin light edge and a bright title; no heavy frame. */
final class PosterCard extends FrameLayout {
    /** Told when the remote moves onto a card (the screen changes its background to that film). */
    interface Focus {
        void on(PosterCard card);
    }

    final ImageView image;
    final TextView title;
    final TextView badge;
    /** TMDB path of the picture for the background; empty when there is none. */
    String backdrop = "";
    Focus onFocus;
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float progress;
    private final int posterHeight;
    private final float radius;

    PosterCard(Context c, int posterWidth) {
        super(c);
        posterHeight = Math.round(posterWidth * 1.5f);
        radius = Ui.dp(c, 12);
        setClipChildren(false);
        setClipToPadding(false);
        image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
        });
        image.setClipToOutline(true);
        addView(image, new LayoutParams(posterWidth, posterHeight));
        // A soft sheen across the top of the poster, like light on glass.
        View sheen = new View(c);
        android.graphics.drawable.GradientDrawable sd = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x2EFFFFFF, 0x00FFFFFF});
        sd.setCornerRadii(new float[] {radius, radius, radius, radius, 0, 0, 0, 0});
        sheen.setBackground(sd);
        addView(sheen, new LayoutParams(posterWidth, posterHeight / 3));
        title = Ui.text(c, "", 14, Ui.DIM);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LayoutParams tl = new LayoutParams(posterWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = posterHeight + Ui.dp(c, 8);
        addView(title, tl);
        badge = Ui.bold(c, "", 12, 0xFFFFFFFF);
        badge.setBackground(Ui.round(0xB3101521, 8, c));
        badge.setPadding(Ui.dp(c, 8), Ui.dp(c, 3), Ui.dp(c, 8), Ui.dp(c, 3));
        LayoutParams bl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        bl.setMargins(0, Ui.dp(c, 8), Ui.dp(c, 8), 0);
        addView(badge, bl);
        badge.setVisibility(View.GONE);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(Ui.dp(c, 1.25f));
        frame.setColor(Ui.light() ? 0xCC1F2937 : 0xB3FFFFFF);
        track.setColor(0x40FFFFFF);
        fill.setColor(0xE6FFFFFF);
        setWillNotDraw(false);
    }

    int cellHeight(Context c) { return posterHeight + Ui.dp(c, 8) + Ui.dp(c, 40); }

    /** progress 0..1 draws the thin "watched so far" line; a label ("✓", "5 сер.") goes in the corner. */
    void set(String name, float progress, String label) {
        title.setText(name);
        this.progress = progress;
        badge.setVisibility(label == null || label.isEmpty() ? View.GONE : View.VISIBLE);
        if (label != null) badge.setText(label);
        invalidate();
    }

    private boolean lit() {
        return isSelected() && getParent() instanceof View && ((View) getParent()).hasFocus();
    }

    @Override public void setSelected(boolean selected) {
        super.setSelected(selected);
        title.setTextColor(selected ? Ui.TEXT : Ui.DIM);
        invalidate();
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        float left = image.getLeft(), right = image.getRight(), bottom = image.getBottom();
        if (progress > 0) {
            // A thin, light line low on the poster, inset from its edges.
            float inset = Ui.dp(getContext(), 12), h = Ui.dp(getContext(), 2.5f), y = bottom - Ui.dp(getContext(), 10) - h;
            float r = h / 2;
            rect.set(left + inset, y, right - inset, y + h);
            canvas.drawRoundRect(rect, r, r, track);
            rect.set(left + inset, y, left + inset + (right - left - 2 * inset) * Math.min(1f, progress), y + h);
            canvas.drawRoundRect(rect, r, r, fill);
        }
        if (lit()) {
            float in = frame.getStrokeWidth();
            rect.set(left + in, image.getTop() + in, right - in, bottom - in);
            canvas.drawRoundRect(rect, radius, radius, frame);
        }
    }
}
