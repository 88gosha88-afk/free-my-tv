package io.freemytv.library;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.freemytv.library.core.Catalog;
import io.freemytv.library.core.Entry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** The whole list of films or of series as a grid of posters, with a search and an order. */
public class LibraryActivity extends Activity implements Library.Listener {
    private Library lib;
    private boolean shows;
    private final List<Object> items = new ArrayList<>();
    private String query = "";
    private boolean newestFirst;
    private GridView grid;
    private TextView searchButton, orderButton, count;
    private Adapter adapter;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        lib = Library.get(this);
        shows = "shows".equals(getIntent().getStringExtra("kind"));
        getWindow().getDecorView().setBackgroundColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 48), Ui.dp(this, 28), Ui.dp(this, 48), 0);
        root.addView(Ui.bold(this, shows ? "Сериалы" : "Фильмы", 28, Ui.TEXT));
        count = Ui.text(this, "", 14, Ui.DIM);
        root.addView(count);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = Ui.dp(this, 12);
        bl.bottomMargin = Ui.dp(this, 8);
        root.addView(bar, bl);
        searchButton = Ui.button(this, "Поиск");
        searchButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askQuery(); }
        });
        orderButton = Ui.button(this, "По названию");
        orderButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                newestFirst = !newestFirst;
                reload();
            }
        });
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.rightMargin = Ui.dp(this, 12);
        bar.addView(searchButton, gap);
        bar.addView(orderButton, gap);

        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Cards.posterWidth(this) + Ui.dp(this, 16));
        grid.setStretchMode(GridView.NO_STRETCH);
        grid.setHorizontalSpacing(Ui.dp(this, 8));
        grid.setVerticalSpacing(Ui.dp(this, 22));
        grid.setSelector(new ColorDrawable(0));
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(Ui.dp(this, 20), Ui.dp(this, 24), Ui.dp(this, 20), Ui.dp(this, 30));
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int position, long id) {
                Object o = items.get(position);
                Intent i = new Intent(LibraryActivity.this, DetailActivity.class);
                if (o instanceof Catalog.Show) i.putExtra("show", ((Catalog.Show) o).key);
                else i.putExtra("path", ((Entry) o).path);
                startActivity(i);
            }
        });
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        reload();
    }

    @Override protected void onStart() {
        super.onStart();
        lib.listen(this);
        reload();
    }

    @Override protected void onStop() {
        super.onStop();
        lib.unlisten(this);
    }

    @Override public void onChanged() {
        if (!grid.hasFocus()) reload();
    }

    private void reload() {
        items.clear();
        String q = query.toLowerCase(Locale.ROOT).trim();
        if (shows) {
            for (Catalog.Show s : lib.catalog.shows()) if (q.isEmpty() || nameOf(s.key, s.title).toLowerCase(Locale.ROOT).contains(q)) items.add(s);
        } else {
            for (Entry e : lib.catalog.movies()) if (q.isEmpty() || nameOf(e.key(), e.title).toLowerCase(Locale.ROOT).contains(q)) items.add(e);
        }
        if (newestFirst) {
            Collections.sort(items, new Comparator<Object>() {
                @Override public int compare(Object a, Object b) { return Long.compare(newest(b), newest(a)); }
            });
        }
        orderButton.setText(newestFirst ? "Сначала новые" : "По названию");
        searchButton.setText(query.isEmpty() ? "Поиск" : "Поиск: " + query);
        count.setText(items.size() + (shows ? " сериалов" : " фильмов"));
        adapter.notifyDataSetChanged();
    }

    private String nameOf(String key, String fallback) {
        io.freemytv.library.core.Tmdb.Meta m = lib.tmdb().cached(key);
        return (m != null && m.found() ? m.title + " " + m.original + " " : "") + fallback;
    }

    private long newest(Object o) {
        if (o instanceof Entry) return ((Entry) o).mtime;
        long t = 0;
        for (List<Entry> l : ((Catalog.Show) o).seasons.values()) for (Entry e : l) t = Math.max(t, e.mtime);
        return t;
    }

    private void askQuery() {
        final EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(query);
        field.setHint("Часть названия");
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Поиск")
                .setView(field)
                .setPositiveButton("Найти", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        query = field.getText().toString();
                        reload();
                    }
                })
                .setNegativeButton("Сбросить", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        query = "";
                        reload();
                    }
                })
                .show();
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View convert, ViewGroup parent) {
            Object o = items.get(i);
            PosterCard card = convert instanceof PosterCard ? (PosterCard) convert : null;
            if (o instanceof Catalog.Show) card = Cards.show(LibraryActivity.this, card, lib, (Catalog.Show) o);
            else card = Cards.movie(LibraryActivity.this, card, lib, (Entry) o);
            card.setLayoutParams(Cards.gridParams(LibraryActivity.this, card));
            // The card is a focusable view of its own; the grid decides which one is "selected".
            card.setFocusable(false);
            card.setClickable(false);
            return card;
        }
    }
}
