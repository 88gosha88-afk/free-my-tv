package io.freemytv.library;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import io.freemytv.library.core.Catalog;
import io.freemytv.library.core.Entry;
import io.freemytv.library.core.Tmdb;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One film, or one series with its seasons and episodes: the description and the buttons that start the player. */
public class DetailActivity extends Activity {
    private Library lib;
    private Entry movie;                 // set for a film
    private Catalog.Show show;           // set for a series
    private int season = -1;
    private LinearLayout page;
    private TextView episodeInfo;
    private final Map<Integer, Tmdb.EpisodeMeta> episodeMeta = new HashMap<>();
    private int episodeMetaSeason = -1;
    private boolean lookedUp;

    private String look = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        lib = Library.get(this);
        look = Ui.refresh();
        getWindow().getDecorView().setBackgroundColor(Ui.BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, 48), Ui.dp(this, 32), Ui.dp(this, 48), Ui.dp(this, 40));
        scroll.addView(page, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        android.widget.FrameLayout stage = new android.widget.FrameLayout(this);
        backdrop = new Backdrop(this);
        stage.addView(backdrop, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stage.addView(scroll, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(stage);
    }

    private Backdrop backdrop;

    @Override protected void onStart() {
        super.onStart();
        if (!look.equals(Ui.refresh())) { recreate(); return; }
        load();
        render();
    }

    private void load() {
        String path = getIntent().getStringExtra("path");
        String key = getIntent().getStringExtra("show");
        movie = null;
        show = null;
        if (path != null) {
            movie = lib.catalog.byPath(path);
        } else if (key != null) {
            for (Catalog.Show s : lib.catalog.shows()) if (s.key.equals(key)) { show = s; break; }
        }
        if (movie == null && show == null) { finish(); return; }
        if (show != null && (season < 0 || !show.seasons.containsKey(season))) {
            Entry next = lib.catalog.nextEpisode(show);
            season = next != null ? next.season : show.seasons.firstKey();
        }
        if (!lookedUp && lib.tmdb().configured() && meta() == null) {
            lookedUp = true;
            final String k = movie != null ? movie.key() : show.key;
            final boolean episode = movie == null;
            final String title = movie != null ? movie.title : show.title;
            final int year = movie != null ? movie.year : show.year;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        lib.tmdb().lookup(k, episode, title, year);
                        runOnUiThread(new Runnable() {
                            @Override public void run() { render(); }
                        });
                    } catch (IOException ignored) {
                    }
                }
            }).start();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        Player.onResult(lib, request, data);
        render();
    }

    private Tmdb.Meta meta() {
        return movie != null ? lib.metaOf(movie) : lib.tmdb().cached(show.key);
    }

    private Entry first() {
        if (movie != null) return movie;
        for (List<Entry> l : show.seasons.values()) if (!l.isEmpty()) return l.get(0);
        return null;
    }

    private void render() {
        if (movie == null && show == null) return;
        page.removeAllViews();
        Tmdb.Meta m = meta();
        String title = m != null && m.found() && !m.title.isEmpty() ? m.title : movie != null ? movie.title : show.title;
        backdrop.show(this, m != null && m.found() ? m.backdrop : "");

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setBackground(Ui.round(Ui.SURFACE, 14, this));
        Ui.roundCorners(poster, Ui.dp(this, 14));
        Posters.show(this, poster, m != null && m.found() ? m.poster : "", "w500", title);
        head.addView(poster, new LinearLayout.LayoutParams(Ui.dp(this, 200), Ui.dp(this, 300)));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(Ui.dp(this, 28), 0, 0, 0);
        info.addView(Ui.bold(this, title, 30, Ui.TEXT));
        StringBuilder sub = new StringBuilder();
        int year = m != null && m.found() && m.year > 0 ? m.year : movie != null ? movie.year : show.year;
        if (year > 0) sub.append(year);
        if (m != null && m.found() && m.rating > 0) sub.append(sub.length() > 0 ? "  ·  " : "").append("★ ").append(String.format(Locale.US, "%.1f", m.rating));
        if (movie != null) sub.append(sub.length() > 0 ? "  ·  " : "").append(Ui.size(movie.size));
        if (show != null) sub.append(sub.length() > 0 ? "  ·  " : "").append(show.episodes()).append(" серий");
        TextView subtitle = Ui.text(this, sub.toString(), 15, Ui.DIM);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = Ui.dp(this, 6);
        info.addView(subtitle, sl);
        String about = m != null && m.found() && !m.overview.isEmpty() ? m.overview
                : lib.tmdb().configured() ? "Описание ещё не найдено." : "Описания и постеры появятся, когда в «Настройках» будет ключ TMDB.";
        TextView overview = Ui.text(this, about, 16, Ui.TEXT);
        overview.setMaxLines(7);
        overview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        overview.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ol.topMargin = Ui.dp(this, 14);
        info.addView(overview, ol);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = Ui.dp(this, 20);
        info.addView(buttons, bl);
        // The second row: the buttons that are not about playing, so no label is squeezed.
        LinearLayout more = new LinearLayout(this);
        more.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ml.topMargin = Ui.dp(this, 12);
        info.addView(more, ml);
        TextView primary = null;
        if (movie != null) {
            Catalog.Progress p = lib.catalog.progress(movie.path);
            boolean resume = p != null && p.started();
            primary = addButton(buttons, resume ? "▶ Продолжить с " + Ui.time(p.position) : "▶ Смотреть", play(movie, false));
            if (resume) addButton(buttons, "С начала", play(movie, true));
            final boolean watched = p != null && p.watched();
            addButton(buttons, watched ? "Не просмотрено" : "Просмотрено", new Runnable() {
                @Override public void run() {
                    lib.catalog.markWatched(movie.path, !watched);
                    render();
                }
            });
        } else {
            Entry next = lib.catalog.nextEpisode(show);
            if (next != null) {
                Catalog.Progress p = lib.catalog.progress(next.path);
                boolean resume = p != null && p.started();
                primary = addButton(buttons, (resume ? "▶ Продолжить S" : "▶ Далее: S") + next.season + "E" + next.number, play(next, false));
            }
        }
        if (movie != null) {
            addButton(more, "Удалить…", new Runnable() {
                @Override public void run() {
                    Deleter.ask(DetailActivity.this, lib, movie.path, false, new Runnable() {
                        @Override public void run() { finish(); }
                    });
                }
            });
        }
        addButton(more, "Название…", new Runnable() {
            @Override public void run() { askTitle(); }
        });
        addButton(more, "Описание…", new Runnable() {
            @Override public void run() { pickMeta(movie != null ? movie.title : show.title); }
        });
        head.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        page.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (primary != null) primary.requestFocus();

        if (show != null) renderEpisodes(m);
    }

    private TextView addButton(LinearLayout bar, String label, final Runnable action) {
        TextView t = Ui.button(this, label);
        t.setSingleLine(true);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 12);
        bar.addView(t, lp);
        return t;
    }

    private Runnable play(final Entry e, final boolean restart) {
        return new Runnable() {
            @Override public void run() {
                Player.open(DetailActivity.this, lib, e, restart);
            }
        };
    }

    // MARK: - Seasons and episodes

    private void renderEpisodes(Tmdb.Meta m) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout seasons = new LinearLayout(this);
        seasons.setOrientation(LinearLayout.HORIZONTAL);
        for (final Integer s : show.seasons.keySet()) {
            TextView t = addButton(seasons, s == 0 ? "Без сезона" : "Сезон " + s, new Runnable() {
                @Override public void run() {
                    season = s;
                    render();
                }
            });
            if (s == season) t.setBackground(Ui.glassFocus(this, 14, true));
        }
        hs.addView(seasons);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = Ui.dp(this, 28);
        page.addView(hs, hl);

        episodeInfo = Ui.text(this, "", 15, Ui.DIM);
        episodeInfo.setMinLines(3);
        episodeInfo.setMaxLines(4);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        il.topMargin = Ui.dp(this, 14);
        page.addView(episodeInfo, il);

        if (m != null && m.found() && episodeMetaSeason != season) {
            episodeMetaSeason = season;
            episodeMeta.clear();
            final int wanted = season, id = m.id;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        final List<Tmdb.EpisodeMeta> list = lib.tmdb().season(id, wanted);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (wanted != season) return;
                                for (Tmdb.EpisodeMeta em : list) episodeMeta.put(em.number, em);
                                renderEpisodeRows();
                            }
                        });
                    } catch (IOException ignored) {
                    }
                }
            }).start();
        }
        episodeList = new LinearLayout(this);
        episodeList.setOrientation(LinearLayout.VERTICAL);
        page.addView(episodeList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        renderEpisodeRows();
    }

    private LinearLayout episodeList;

    private void renderEpisodeRows() {
        if (episodeList == null || show == null) return;
        episodeList.removeAllViews();
        List<Entry> list = show.seasons.get(season);
        if (list == null) return;
        for (final Entry e : list) {
            Tmdb.EpisodeMeta em = episodeMeta.get(e.number);
            Catalog.Progress p = lib.catalog.progress(e.path);
            String mark = p != null && p.watched() ? "  ✓" : p != null && p.started() ? "  · " + Ui.time(p.position) : "";
            String name = em != null && !em.name.isEmpty() ? em.name : e.name;
            final String about = (em != null && !em.overview.isEmpty() ? em.overview : "") + (em != null && em.minutes > 0 ? "\n" + em.minutes + " мин" : "");
            TextView row = Ui.text(this, (e.number > 0 ? e.number + ".  " : "") + name + mark, 17, Ui.TEXT);
            row.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.setFocusable(true);
            row.setClickable(true);
            row.setBackground(Ui.rowFocus(this, 10));
            row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean has) {
                    if (has && episodeInfo != null) episodeInfo.setText(about.isEmpty() ? e.name : about);
                }
            });
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { play(e, false).run(); }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    Catalog.Progress q = lib.catalog.progress(e.path);
                    lib.catalog.markWatched(e.path, q == null || !q.watched());
                    renderEpisodeRows();
                    return true;
                }
            });
            episodeList.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    // MARK: - A title typed by hand

    private void askTitle() {
        final Entry e = first();
        if (e == null) return;
        final EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(movie != null ? movie.title : show.title);
        field.setHint("Как называется");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Название для поиска описания")
                .setView(field)
                .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        lib.catalog.fixTitle(e, field.getText().toString().trim());
                        lookedUp = false;
                        lib.lookUpMissing();
                        reopen();
                    }
                })
                .setNeutralButton("Как в имени файла", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        lib.catalog.clearFix(e);
                        lookedUp = false;
                        lib.lookUpMissing();
                        reopen();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // MARK: - A description chosen by hand

    /** Asks what to search for, shows what TMDB has under that name, and keeps the one the person picks (poster and text). */
    private void pickMeta(String start) {
        final Entry e = first();
        if (e == null) return;
        if (!lib.tmdb().configured()) {
            android.widget.Toast.makeText(this, "Сначала введите ключ TMDB в «Настройках».", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        final EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(start);
        field.setHint("Название фильма или сериала");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Что искать в TMDB")
                .setView(field)
                .setPositiveButton("Найти", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { searchMeta(e, field.getText().toString()); }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void searchMeta(final Entry e, final String query) {
        android.widget.Toast.makeText(this, "Ищу…", android.widget.Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                List<Tmdb.Meta> found;
                String problem = null;
                try {
                    found = lib.tmdb().candidates(query);
                } catch (IOException x) {
                    found = new java.util.ArrayList<>();
                    problem = String.valueOf(x.getMessage());
                }
                final List<Tmdb.Meta> list = found;
                final String why = problem;
                runOnUiThread(new Runnable() {
                    @Override public void run() { showCandidates(e, query, list, why); }
                });
            }
        }, "tmdb-search").start();
    }

    private void showCandidates(final Entry e, final String query, final List<Tmdb.Meta> list, String problem) {
        if (isFinishing()) return;
        if (list.isEmpty()) {
            android.widget.Toast.makeText(this, problem != null ? "Не удалось найти: " + problem : "Ничего не найдено. Попробуйте другое название.", android.widget.Toast.LENGTH_LONG).show();
            pickMeta(query);
            return;
        }
        String[] labels = new String[list.size()];
        for (int i = 0; i < labels.length; i++) {
            Tmdb.Meta m = list.get(i);
            String what = m.kind.equals("tv") ? "сериал" : "фильм";
            String over = m.overview.length() > 90 ? m.overview.substring(0, 90) + "…" : m.overview;
            labels[i] = m.title + (m.year > 0 ? " (" + m.year + ")" : "") + " — " + what
                    + (m.original.isEmpty() || m.original.equals(m.title) ? "" : "\n" + m.original) + (over.isEmpty() ? "" : "\n" + over);
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Выберите подходящее")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        lib.tmdb().assign(e.key(), list.get(which));
                        lookedUp = true;
                        lib.refreshView();
                        render();
                    }
                })
                .setNeutralButton("Другой запрос", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { pickMeta(query); }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** The title changed, so the key of a series did too: look it up again by the first episode's path. */
    private void reopen() {
        Entry e = first();
        if (e == null) { finish(); return; }
        if (show != null) {
            Entry fresh = lib.catalog.byPath(e.path);
            if (fresh != null) {
                Intent i = getIntent();
                i.removeExtra("path");
                i.putExtra("show", fresh.key());
            }
        }
        load();
        render();
    }
}
