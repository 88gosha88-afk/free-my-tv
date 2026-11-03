package io.freemytv.library;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import io.freemytv.library.core.Catalog;
import io.freemytv.library.core.Entry;
import io.freemytv.library.core.Folder;
import io.freemytv.library.core.MediaServer;
import io.freemytv.library.core.Scanner;
import io.freemytv.library.core.Source;
import io.freemytv.library.core.Tmdb;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The one library of the app: the saved catalog, the network folder, the online descriptions and the background work. */
final class Library {
    interface Listener {
        void onChanged();
    }

    private static Library instance;

    static synchronized Library get(Context c) {
        if (instance == null) instance = new Library(c.getApplicationContext());
        return instance;
    }

    final Context app;
    final Catalog catalog;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private Tmdb tmdb;
    private String tmdbKey = "";
    private Source source;
    private String sourceKey = "";
    private MediaServer server;
    private volatile String status = "";
    private volatile boolean scanning;
    private volatile String problem = "";
    private long lastNotify;

    private Library(Context c) {
        app = c;
        catalog = new Catalog(new File(c.getFilesDir(), "library"));
    }

    // MARK: - Listeners

    void listen(Listener l) { listeners.add(l); }

    void unlisten(Listener l) { listeners.remove(l); }

    private void changed(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastNotify < 600) return;
        lastNotify = now;
        main.post(new Runnable() {
            @Override public void run() {
                for (Listener l : listeners) l.onChanged();
            }
        });
    }

    /** What the background work is doing ("" when idle). */
    String status() { return status; }

    /** The last trouble, in words ("" when none). */
    String problem() { return problem; }

    boolean busy() { return scanning; }

    // MARK: - The network folder and the player's server

    synchronized Source source() throws IOException {
        String key = Prefs.smbUrl() + "\n" + Prefs.user() + "\n" + Prefs.password();
        if (source == null || !key.equals(sourceKey)) {
            source = new SmbSource(Prefs.smbUrl(), Prefs.user(), Prefs.password());
            sourceKey = key;
            if (server != null) { server.stop(); server = null; }
        }
        return source;
    }

    /** The address a player can open for a file of the network folder. */
    /** smb://host/share/path for a player that reads the network folder itself (VLC does, and handles files over 4 GB that its HTTP reader cannot seek in). */
    String directUrl(String path) {
        String root = Prefs.smbUrl();
        if (root.startsWith("smb://")) root = root.substring(6);
        if (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        int slash = root.indexOf('/');
        String host = slash < 0 ? root : root.substring(0, slash);
        String share = slash < 0 ? "" : root.substring(slash + 1);
        StringBuilder b = new StringBuilder("smb://");
        String user = Prefs.user();
        if (!user.isEmpty()) b.append(enc(user)).append(':').append(enc(Prefs.password())).append('@');
        b.append(host);
        for (String part : (share.isEmpty() ? path : share + "/" + path).split("/")) {
            if (!part.isEmpty()) b.append('/').append(enc(part));
        }
        return b.toString();
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (java.io.UnsupportedEncodingException e) { return s; }
    }

    /** The address a player opens for a file: smb:// when it reads the disk itself, else the app's own address. */
    String mediaUrl(String path, long size, boolean direct) throws IOException {
        return direct ? directUrl(path) : urlOf(path, size);
    }

    synchronized String urlOf(String path, long size) throws IOException {
        String url = urlOf(path);
        server.hintSize(path, size);
        return url;
    }

    synchronized String urlOf(String path) throws IOException {
        Source s = source();
        if (server == null) {
            server = new MediaServer(s);
            server.setLog(new MediaServer.Log() {
                @Override public void log(String line) { android.util.Log.i("FreeMyLibrary", line); }
            });
        }
        String url = server.url(path);
        android.util.Log.i("FreeMyLibrary", "url " + url);
        return url;
    }

    synchronized Tmdb tmdb() {
        String key = Prefs.tmdbKey();
        if (tmdb == null || !key.equals(tmdbKey)) {
            tmdb = new Tmdb(key, new File(app.getFilesDir(), "library"));
            tmdbKey = key;
        }
        return tmdb;
    }

    Tmdb.Meta metaOf(Entry e) {
        return tmdb().cached(e.key());
    }

    // MARK: - Scanning

    /** Starts at app start: a fresh look at the folders when the list is empty or old. */
    void refreshIfOld() {
        if (catalog.all().isEmpty() || System.currentTimeMillis() - Prefs.lastScan() > 12L * 3600 * 1000) rescan();
        else lookUpMissing();
    }

    void rescan() {
        if (scanning) return;
        scanning = true;
        problem = "";
        status = app.getString(R.string.scanning);
        changed(true);
        worker.execute(new Runnable() {
            @Override public void run() { scanNow(); }
        });
    }

    private void scanNow() {
        try {
            Source s = source();
            Scanner scanner = new Scanner(s, Prefs.excluded(), 30L * 1024 * 1024, new Scanner.Listener() {
                @Override public void onFolder(String path, int videos) {
                    status = app.getString(R.string.scanning_folder, path, videos);
                    changed(false);
                }
            });
            List<Entry> list = scanner.scan(Prefs.folders());
            if (list.isEmpty() && scanner.error() != null) {
                problem = scanner.error();           // keep the old list: the disk may just be unreachable now
            } else {
                catalog.replace(list);
                Prefs.setLastScan(System.currentTimeMillis());
            }
        } catch (IOException | RuntimeException e) {
            problem = String.valueOf(e);
        } finally {
            scanning = false;
            status = "";
            changed(true);
        }
        lookUpMissingNow();
    }

    /** Tells the open screens that something changed (a description chosen by hand, say). */
    void refreshView() { changed(true); }

    void lookUpMissing() {
        if (scanning) return;
        worker.execute(new Runnable() {
            @Override public void run() { lookUpMissingNow(); }
        });
    }

    /** Finds the descriptions and posters of everything that has none yet; stops after a few network failures. */
    private void lookUpMissingNow() {
        Tmdb t = tmdb();
        if (!t.configured()) return;
        Map<String, Entry> todo = new LinkedHashMap<>();
        for (Entry e : catalog.all()) if (t.cached(e.key()) == null && !todo.containsKey(e.key())) todo.put(e.key(), e);
        int done = 0, failures = 0;
        for (Entry e : todo.values()) {
            status = app.getString(R.string.looking_up, done, todo.size());
            try {
                t.lookup(e.key(), e.episode, e.title, e.year);
                failures = 0;
            } catch (IOException x) {
                if (++failures >= 3) {
                    problem = app.getString(R.string.tmdb_failed, String.valueOf(x.getMessage()));
                    break;
                }
            }
            done++;
            changed(false);
        }
        status = "";
        changed(true);
    }

    // MARK: - Folders

    /** Adds a folder to the library (or changes what it holds) and looks at it again. */
    void addFolder(String path, String kind) {
        List<Folder> now = Prefs.folders();
        List<Folder> out = new ArrayList<>();
        for (Folder f : now) if (!f.path.equals(path)) out.add(f);
        out.add(new Folder(path, kind));
        Prefs.setFolders(out);
        List<String> ex = Prefs.excluded();
        ex.remove(path);
        Prefs.setExcluded(ex);
        rescan();
    }

    void removeFolder(String path) {
        List<Folder> out = new ArrayList<>();
        for (Folder f : Prefs.folders()) if (!f.path.equals(path)) out.add(f);
        Prefs.setFolders(out);
        rescan();
    }

    /** Leaves a folder or a file out of the library (its siblings stay). */
    void exclude(String path) {
        List<String> ex = Prefs.excluded();
        if (!ex.contains(path)) ex.add(path);
        Prefs.setExcluded(ex);
        rescan();
    }

    void include(String path) {
        List<String> ex = Prefs.excluded();
        ex.remove(path);
        Prefs.setExcluded(ex);
        rescan();
    }
}
