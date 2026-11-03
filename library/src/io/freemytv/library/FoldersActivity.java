package io.freemytv.library;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import io.freemytv.library.core.Folder;
import io.freemytv.library.core.Node;
import io.freemytv.library.core.Scanner;
import io.freemytv.library.core.Source;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The network folder as a tree: walk through it and, on any folder (or video), choose in one step whether it belongs to
 * the library, as films or as series, or is left out. OK opens a folder, a long press on OK (or Menu) offers the choices.
 */
public class FoldersActivity extends Activity {
    private Library lib;
    private String path = "";
    private final List<Node> nodes = new ArrayList<>();
    private TextView where, message;
    private ListView list;
    private Adapter adapter;
    private int loadId;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        lib = Library.get(this);
        getWindow().getDecorView().setBackgroundColor(Ui.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 48), Ui.dp(this, 28), Ui.dp(this, 48), Ui.dp(this, 20));
        root.addView(Ui.bold(this, "Папки медиатеки", 28, Ui.TEXT));
        TextView hint = Ui.text(this, "OK — открыть папку.  Стрелка вправо (или удержание OK) — добавить в медиатеку или исключить.", 14, Ui.DIM);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = Ui.dp(this, 6);
        root.addView(hint, hl);
        where = Ui.bold(this, "", 17, Ui.ACCENT);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.topMargin = Ui.dp(this, 14);
        root.addView(where, wl);
        message = Ui.text(this, "", 15, Ui.WARN);
        root.addView(message);

        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setItemsCanFocus(false);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int position, long id) {
                Node n = nodes.get(position);
                if (n.dir) go(Scanner.join(path, n.name)); else choose(n);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int position, long id) {
                choose(nodes.get(position));
                return true;
            }
        });
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        ll.topMargin = Ui.dp(this, 10);
        root.addView(list, ll);
        setContentView(root);
        go("");
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) && list.getSelectedItemPosition() >= 0 && list.getSelectedItemPosition() < nodes.size()) {
            choose(nodes.get(list.getSelectedItemPosition()));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public void onBackPressed() {
        if (path.isEmpty()) { super.onBackPressed(); return; }
        int i = path.lastIndexOf('/');
        go(i < 0 ? "" : path.substring(0, i));
    }

    private void go(final String target) {
        path = target;
        where.setText("/" + target);
        message.setText("Читаю папку…");
        nodes.clear();
        adapter.notifyDataSetChanged();
        final int id = ++loadId;
        new Thread(new Runnable() {
            @Override public void run() {
                List<Node> got = null;
                String error = null;
                try {
                    Source s = lib.source();
                    got = new ArrayList<>(s.list(target));
                } catch (IOException | RuntimeException e) {
                    error = String.valueOf(e.getMessage() != null ? e.getMessage() : e);
                }
                final List<Node> result = got;
                final String err = error;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (id != loadId) return;
                        nodes.clear();
                        if (result != null) {
                            for (Node n : result) {
                                if (n.name.startsWith(".")) continue;
                                if (n.dir || Scanner.VIDEO.contains(extOf(n.name))) nodes.add(n);
                            }
                            Collections.sort(nodes, new Comparator<Node>() {
                                @Override public int compare(Node a, Node b) {
                                    if (a.dir != b.dir) return a.dir ? -1 : 1;
                                    return a.name.compareToIgnoreCase(b.name);
                                }
                            });
                        }
                        message.setText(err != null ? "Не удалось прочитать: " + err : nodes.isEmpty() ? "Здесь нет папок и видео." : "");
                        adapter.notifyDataSetChanged();
                        list.requestFocus();
                    }
                });
            }
        }).start();
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    // MARK: - What a folder is to the library

    private boolean excluded(String full) {
        for (String x : Prefs.excluded()) if (full.equals(x) || full.startsWith(x + "/")) return true;
        return false;
    }

    private Folder libraryFolder(String full) {
        for (Folder f : Prefs.folders()) if (f.path.equals(full)) return f;
        return null;
    }

    private boolean insideLibrary(String full) {
        for (Folder f : Prefs.folders()) if (full.startsWith(f.path + "/")) return true;
        return false;
    }

    private static String kindName(String kind) {
        return Folder.MOVIES.equals(kind) ? "фильмы" : Folder.SHOWS.equals(kind) ? "сериалы" : "авто";
    }

    /** The line under a name: where this item stands now. */
    private String stateOf(Node n) {
        String full = Scanner.join(path, n.name);
        if (excluded(full)) return "исключено";
        Folder f = libraryFolder(full);
        if (f != null) return "в медиатеке · " + kindName(f.kind);
        if (insideLibrary(full)) return n.dir ? "внутри медиатеки" : "в медиатеке";
        return "";
    }

    private void choose(Node n) {
        final String full = Scanner.join(path, n.name);
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        final boolean isOut = excluded(full);
        if (n.dir) {
            add(labels, actions, "Добавить: авто (по названиям файлов)", new Runnable() {
                @Override public void run() { lib.addFolder(full, Folder.AUTO); afterChange(); }
            });
            add(labels, actions, "Добавить как фильмы", new Runnable() {
                @Override public void run() { lib.addFolder(full, Folder.MOVIES); afterChange(); }
            });
            add(labels, actions, "Добавить как сериалы", new Runnable() {
                @Override public void run() { lib.addFolder(full, Folder.SHOWS); afterChange(); }
            });
            if (libraryFolder(full) != null) {
                add(labels, actions, "Убрать из медиатеки", new Runnable() {
                    @Override public void run() { lib.removeFolder(full); afterChange(); }
                });
            }
        }
        if (isOut) {
            add(labels, actions, "Вернуть (не исключать)", new Runnable() {
                @Override public void run() { lib.include(full); afterChange(); }
            });
        } else if (insideLibrary(full) || libraryFolder(full) != null || !n.dir) {
            add(labels, actions, "Исключить из медиатеки", new Runnable() {
                @Override public void run() { lib.exclude(full); afterChange(); }
            });
        } else {
            add(labels, actions, "Исключить (не показывать)", new Runnable() {
                @Override public void run() { lib.exclude(full); afterChange(); }
            });
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(n.name)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) { actions.get(which).run(); }
                })
                .show();
    }

    private static void add(List<String> labels, List<Runnable> actions, String label, Runnable action) {
        labels.add(label);
        actions.add(action);
    }

    private void afterChange() {
        adapter.notifyDataSetChanged();
        message.setText("Библиотека обновляется — это займёт минуту.");
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return nodes.size(); }
        @Override public Object getItem(int i) { return nodes.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View convert, ViewGroup parent) {
            Node n = nodes.get(i);
            LinearLayout row;
            TextView name, state;
            if (convert instanceof LinearLayout) {
                row = (LinearLayout) convert;
                name = (TextView) row.getChildAt(0);
                state = (TextView) row.getChildAt(1);
            } else {
                row = new LinearLayout(FoldersActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(Ui.dp(FoldersActivity.this, 16), Ui.dp(FoldersActivity.this, 12), Ui.dp(FoldersActivity.this, 16), Ui.dp(FoldersActivity.this, 12));
                name = Ui.text(FoldersActivity.this, "", 18, Ui.TEXT);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                state = Ui.text(FoldersActivity.this, "", 15, Ui.ACCENT);
                row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(state, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                row.setBackground(Ui.selectionBackground(FoldersActivity.this, Ui.BG, Ui.SURFACE_HI, 8));
            }
            name.setText((n.dir ? "📁  " : "🎞  ") + n.name + (n.dir ? "" : "   " + Ui.size(n.size)));
            String st = stateOf(n);
            state.setText(st.isEmpty() ? "›" : st + "  ›");
            state.setTextColor(st.equals("исключено") ? Ui.WARN : Ui.ACCENT);
            return row;
        }
    }
}
