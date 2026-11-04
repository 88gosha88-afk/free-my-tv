package io.freemytv.library;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import io.freemytv.library.core.Catalog;
import io.freemytv.library.core.Entry;
import io.freemytv.library.core.Tmdb;

/** Builds the poster cards of films and series, the same way on every screen. */
final class Cards {
    private Cards() {}

    static int posterWidth(Context c) { return Ui.dp(c, 150); }

    static PosterCard blank(Context c) {
        final PosterCard card = new PosterCard(c, posterWidth(c));
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                card.setSelected(has);
                if (has && card.onFocus != null) card.onFocus.on(card);
            }
        });
        return card;
    }

    /** Fills a card (new, or one handed back by a list) for a film or an episode. */
    static PosterCard movie(Context c, PosterCard reuse, Library lib, Entry e) {
        PosterCard card = reuse != null ? reuse : blank(c);
        Tmdb.Meta m = lib.metaOf(e);
        String name = m != null && m.found() && !m.title.isEmpty() ? m.title : e.title;
        Catalog.Progress p = lib.catalog.progress(e.path);
        float fraction = p != null && p.started() && p.duration > 0 ? Math.min(1f, (float) p.position / p.duration) : 0f;
        String label = p != null && p.watched() ? "✓" : e.episode ? "S" + e.season + "E" + e.number : e.year > 0 ? String.valueOf(e.year) : "";
        card.set(name, fraction, label);
        card.backdrop = m != null && m.found() ? m.backdrop : "";
        Posters.show(c, card.image, m != null && m.found() ? m.poster : "", "w342", name);
        return card;
    }

    static PosterCard show(Context c, PosterCard reuse, Library lib, Catalog.Show s) {
        PosterCard card = reuse != null ? reuse : blank(c);
        Tmdb.Meta m = lib.tmdb().cached(s.key);
        String name = m != null && m.found() && !m.title.isEmpty() ? m.title : s.title;
        card.set(name, 0f, s.episodes() + " сер.");
        card.backdrop = m != null && m.found() ? m.backdrop : "";
        Posters.show(c, card.image, m != null && m.found() ? m.poster : "", "w342", name);
        return card;
    }

    static LinearLayout.LayoutParams cellParams(Context c, PosterCard card) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(posterWidth(c), card.cellHeight(c));
        lp.rightMargin = Ui.dp(c, 16);
        return lp;
    }

    static ViewGroup.LayoutParams gridParams(Context c, PosterCard card) {
        return new android.widget.AbsListView.LayoutParams(posterWidth(c), card.cellHeight(c));
    }
}
