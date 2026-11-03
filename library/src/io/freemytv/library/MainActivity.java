package io.freemytv.library;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import io.freemytv.library.core.Catalog;
import io.freemytv.library.core.Entry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The home screen: what to continue, what is new, and the way to the full lists, the folders and the settings. */
public class MainActivity extends Activity implements Library.Listener {
    private Library lib;
    private LinearLayout rows;
    private TextView status;
    private TextView moviesButton, showsButton;
    private String signature = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        lib = Library.get(this);
        getWindow().getDecorView().setBackgroundColor(Ui.BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false);
        root.setPadding(Ui.dp(this, 48), Ui.dp(this, 28), Ui.dp(this, 48), Ui.dp(this, 40));
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(Ui.bold(this, "Моя медиатека", 30, Ui.TEXT));
        status = Ui.text(this, "", 14, Ui.DIM);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = Ui.dp(this, 6);
        root.addView(status, sl);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = Ui.dp(this, 16);
        root.addView(bar, bl);
        moviesButton = button(bar, "Фильмы", new Runnable() {
            @Override public void run() { openList("movies"); }
        });
        showsButton = button(bar, "Сериалы", new Runnable() {
            @Override public void run() { openList("shows"); }
        });
        button(bar, "Папки", new Runnable() {
            @Override public void run() { startActivity(new Intent(MainActivity.this, FoldersActivity.class)); }
        });
        button(bar, "Настройки", new Runnable() {
            @Override public void run() { startActivity(new Intent(MainActivity.this, SettingsActivity.class)); }
        });
        button(bar, "Обновить", new Runnable() {
            @Override public void run() { lib.rescan(); }
        });

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        root.addView(rows, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        lib.refreshIfOld();
    }

    private TextView button(LinearLayout bar, String label, final Runnable action) {
        TextView t = Ui.button(this, label);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 12);
        bar.addView(t, lp);
        return t;
    }

    private void openList(String kind) {
        startActivity(new Intent(this, LibraryActivity.class).putExtra("kind", kind));
    }

    @Override protected void onStart() {
        super.onStart();
        lib.listen(this);
        signature = "";          // posters and positions may have changed while another screen was open
        onChanged();
    }

    @Override protected void onStop() {
        super.onStop();
        lib.unlisten(this);
    }

    @Override public void onChanged() {
        String problem = lib.problem();
        boolean noKey = !lib.tmdb().configured() && !lib.catalog.all().isEmpty();
        String text = !lib.status().isEmpty() ? lib.status() : !problem.isEmpty() ? problem
                : noKey ? "Нет ключа TMDB: постеры и описания не загрузятся. «Настройки» → «Ключ TMDB»." : "";
        status.setText(text.isEmpty() ? "" : text);
        status.setTextColor(lib.status().isEmpty() && (!problem.isEmpty() || noKey) ? Ui.WARN : Ui.DIM);
        // Rebuilding the rows takes the remote's focus away, so while someone is on a card it waits.
        if (rows.hasFocus() && !signature.isEmpty()) return;
        String sig = lib.catalog.all().size() + "|" + metaCount() + "|" + lib.catalog.continueWatching(30).size();
        if (sig.equals(signature)) return;
        signature = sig;
        rebuild();
    }

    private int metaCount() {
        int n = 0;
        for (Entry e : lib.catalog.all()) if (lib.metaOf(e) != null) n++;
        return n;
    }

    private void rebuild() {
        rows.removeAllViews();
        int movies = lib.catalog.movies().size(), shows = lib.catalog.shows().size();
        moviesButton.setText("Фильмы · " + movies);
        showsButton.setText("Сериалы · " + shows);
        if (movies == 0 && shows == 0) {
            TextView empty = Ui.text(this, lib.busy() ? "Идёт поиск…" : lib.problem().isEmpty()
                    ? "Пока ничего не найдено. Откройте «Папки» и добавьте папки с фильмами."
                    : "Диск не отвечает. Проверьте адрес в «Настройках».", 17, Ui.DIM);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(this, 30);
            rows.addView(empty, lp);
            return;
        }
        List<View> cont = new ArrayList<>();
        for (Entry e : lib.catalog.continueWatching(12)) cont.add(entryCard(e));
        addRow("Продолжить просмотр", cont);

        // Newest first; a series counts once, by its newest episode.
        List<Entry> all = lib.catalog.all();
        Collections.sort(all, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) { return Long.compare(b.mtime, a.mtime); }
        });
        Set<String> seen = new HashSet<>();
        List<View> fresh = new ArrayList<>();
        for (Entry e : all) {
            if (fresh.size() >= 20) break;
            if (e.episode) {
                if (!seen.add(e.key())) continue;
                for (Catalog.Show s : lib.catalog.shows()) if (s.key.equals(e.key())) { fresh.add(showCard(s)); break; }
            } else {
                fresh.add(entryCard(e));
            }
        }
        addRow("Недавно добавлено", fresh);
    }

    private View entryCard(final Entry e) {
        PosterCard card = Cards.movie(this, null, lib, e);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, DetailActivity.class).putExtra("path", e.path));
            }
        });
        return card;
    }

    private View showCard(final Catalog.Show s) {
        PosterCard card = Cards.show(this, null, lib, s);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, DetailActivity.class).putExtra("show", s.key));
            }
        });
        return card;
    }

    private void addRow(String title, List<View> cards) {
        if (cards.isEmpty()) return;
        TextView h = Ui.bold(this, title, 20, Ui.TEXT);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = Ui.dp(this, 26);
        rows.addView(h, hl);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setClipChildren(false);
        line.setPadding(Ui.dp(this, 24), Ui.dp(this, 26), Ui.dp(this, 48), Ui.dp(this, 20));
        for (View v : cards) line.addView(v, Cards.cellParams(this, (PosterCard) v));
        hs.addView(line);
        rows.addView(hs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
}
