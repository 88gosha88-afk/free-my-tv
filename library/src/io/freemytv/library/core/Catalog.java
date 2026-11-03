package io.freemytv.library.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** What was found on the disk: the saved list of films and series, the watched positions and the titles fixed by hand. */
public final class Catalog {
    /** A series: its episodes by season. */
    public static final class Show {
        public final String key;
        public final String title;
        public final int year;
        public final TreeMap<Integer, List<Entry>> seasons = new TreeMap<>();

        Show(String key, String title, int year) {
            this.key = key;
            this.title = title;
            this.year = year;
        }

        public int episodes() {
            int n = 0;
            for (List<Entry> l : seasons.values()) n += l.size();
            return n;
        }
    }

    /** Where the person stopped. */
    public static final class Progress {
        public long position, duration, at;

        public boolean watched() { return duration > 0 && position >= duration * 0.92; }

        public boolean started() { return position > 20_000 && !watched(); }
    }

    private final File dir;
    private List<Entry> entries = new ArrayList<>();
    private final Map<String, Progress> progress = new HashMap<>();
    private final Map<String, String> fixes = new HashMap<>();   // film path or series key -> title typed by hand

    public Catalog(File dir) {
        this.dir = dir;
        dir.mkdirs();
        load();
    }

    // MARK: - The list

    public synchronized List<Entry> all() { return new ArrayList<>(entries); }

    public synchronized void replace(List<Entry> fresh) {
        for (Entry e : fresh) applyFix(e);
        entries = new ArrayList<>(fresh);
        saveEntries();
    }

    private void applyFix(Entry e) {
        // A series' fix is stored under the key of its raw title, so it still finds the series after the title changes.
        String key = fixes.get(e.episode ? rawKey(e) : e.path);
        e.title = e.rawTitle;
        e.year = e.rawYear;
        if (key == null) return;
        String[] t = NameParser.clean(key);
        if (!t[0].isEmpty()) {
            e.title = t[0];
            if (!t[1].isEmpty()) e.year = Integer.parseInt(t[1]);
        }
    }

    private static String rawKey(Entry e) {
        return (e.episode ? "tv|" : "movie|") + NameParser.normalize(e.rawTitle) + "|" + e.rawYear;
    }

    /** Applies the typed titles again (after one was added or removed). */
    public synchronized void refreshTitles() {
        for (Entry e : entries) applyFix(e);
        saveEntries();
    }

    public synchronized List<Entry> movies() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (!e.episode) out.add(e);
        Collections.sort(out, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) { return a.title.compareToIgnoreCase(b.title); }
        });
        return out;
    }

    public synchronized List<Show> shows() {
        Map<String, Show> map = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (!e.episode) continue;
            String k = e.key();
            Show s = map.get(k);
            if (s == null) { s = new Show(k, e.title, e.year); map.put(k, s); }
            List<Entry> l = s.seasons.get(e.season);
            if (l == null) { l = new ArrayList<>(); s.seasons.put(e.season, l); }
            l.add(e);
        }
        for (Show s : map.values()) {
            for (List<Entry> l : s.seasons.values()) {
                Collections.sort(l, new Comparator<Entry>() {
                    @Override public int compare(Entry a, Entry b) {
                        if (a.number != b.number) return a.number - b.number;
                        return a.name.compareToIgnoreCase(b.name);
                    }
                });
            }
        }
        List<Show> out = new ArrayList<>(map.values());
        Collections.sort(out, new Comparator<Show>() {
            @Override public int compare(Show a, Show b) { return a.title.compareToIgnoreCase(b.title); }
        });
        return out;
    }

    public synchronized Entry byPath(String path) {
        for (Entry e : entries) if (e.path.equals(path)) return e;
        return null;
    }

    /** The first episode that isn't watched yet (or the first one). */
    public synchronized Entry nextEpisode(Show s) {
        Entry first = null, resume = null;
        for (List<Entry> l : s.seasons.values()) {
            for (Entry e : l) {
                if (first == null) first = e;
                Progress p = progress.get(e.path);
                if (p == null || !p.watched()) return resume != null ? resume : e;
            }
        }
        return first;
    }

    // MARK: - Positions

    public synchronized Progress progress(String path) { return progress.get(path); }

    public synchronized void saveProgress(String path, long position, long duration) {
        if (duration <= 0) return;
        Progress p = new Progress();
        p.position = position;
        p.duration = duration;
        p.at = System.currentTimeMillis();
        progress.put(path, p);
        saveProgressFile();
    }

    public synchronized void markWatched(String path, boolean watched) {
        Progress p = progress.get(path);
        if (p == null) { p = new Progress(); p.duration = 1; }
        p.position = watched ? Math.max(p.duration, 1) : 0;
        p.at = System.currentTimeMillis();
        progress.put(path, p);
        saveProgressFile();
    }

    /** Started but not finished, the latest first. */
    public synchronized List<Entry> continueWatching(int limit) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            Progress p = progress.get(e.path);
            if (p != null && p.started()) out.add(e);
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) { return Long.compare(progress.get(b.path).at, progress.get(a.path).at); }
        });
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    // MARK: - Fixed titles

    public synchronized void fixTitle(Entry e, String typed) {
        fixes.put(e.episode ? rawKey(e) : e.path, typed);
        saveFixes();
        refreshTitles();
    }

    public synchronized void clearFix(Entry e) {
        fixes.remove(e.episode ? rawKey(e) : e.path);
        saveFixes();
        refreshTitles();
    }

    // MARK: - Files

    private void load() {
        for (String line : readLines(new File(dir, "catalog.tsv"))) {
            Entry e = Entry.fromLine(line);
            if (e != null) entries.add(e);
        }
        for (String line : readLines(new File(dir, "progress.tsv"))) {
            String[] f = line.split("\t", -1);
            if (f.length < 4) continue;
            try {
                Progress p = new Progress();
                p.position = Long.parseLong(f[1]);
                p.duration = Long.parseLong(f[2]);
                p.at = Long.parseLong(f[3]);
                progress.put(f[0], p);
            } catch (NumberFormatException ignored) {
            }
        }
        for (String line : readLines(new File(dir, "fixes.tsv"))) {
            String[] f = line.split("\t", 2);
            if (f.length == 2) fixes.put(f[0], f[1]);
        }
    }

    private void saveEntries() {
        List<String> lines = new ArrayList<>();
        for (Entry e : entries) lines.add(e.toLine());
        writeLines(new File(dir, "catalog.tsv"), lines);
    }

    private void saveProgressFile() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Progress> m : progress.entrySet()) {
            Progress p = m.getValue();
            lines.add(m.getKey() + "\t" + p.position + "\t" + p.duration + "\t" + p.at);
        }
        writeLines(new File(dir, "progress.tsv"), lines);
    }

    private void saveFixes() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> m : fixes.entrySet()) lines.add(m.getKey() + "\t" + m.getValue());
        writeLines(new File(dir, "fixes.tsv"), lines);
    }

    static List<String> readLines(File f) {
        List<String> out = new ArrayList<>();
        if (!f.isFile()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) if (!line.isEmpty()) out.add(line);
        } catch (IOException ignored) {
        }
        return out;
    }

    /** Written to a temporary file first, so a power cut never leaves half a list. */
    static void writeLines(File f, List<String> lines) {
        File tmp = new File(f.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            for (String l : lines) w.write(l.replace('\n', ' ').replace('\r', ' ') + "\n");
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            tmp.renameTo(f);
        }
    }
}
