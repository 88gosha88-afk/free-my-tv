package io.freemytv.library.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Walks the library folders and lists the video files in them, with their subtitles and extra soundtracks. */
public final class Scanner {
    public interface Listener {
        void onFolder(String path, int videosSoFar);
    }

    public static final Set<String> VIDEO = new HashSet<>(Arrays.asList("mkv", "mp4", "avi", "wmv", "mov", "m4v", "ts", "m2ts", "mts",
            "mpg", "mpeg", "flv", "webm", "vob", "divx", "3gp", "ogm", "rmvb", "asf", "mpe"));
    static final Set<String> SUBS = new HashSet<>(Arrays.asList("srt", "ass", "ssa", "sub", "vtt", "idx", "smi"));
    static final Set<String> AUDIO = new HashSet<>(Arrays.asList("mka", "ac3", "dts", "aac", "flac", "eac3", "thd", "mp3"));
    private static final Set<String> SKIP = new HashSet<>(Arrays.asList("@eadir", "$recycle.bin", "system volume information", "lost+found",
            ".recycle", "@recycle", "#recycle"));
    private static final int MAX_DEPTH = 14;

    private final Source source;
    private final List<String> excluded;
    private final Listener listener;
    private final long minSize;
    private volatile boolean cancelled;
    private volatile String error;
    private final List<Entry> found = new ArrayList<>();

    /** @param minSize videos smaller than this (samples, trailers) are left out */
    public Scanner(Source source, List<String> excluded, long minSize, Listener listener) {
        this.source = source;
        this.excluded = excluded;
        this.minSize = minSize;
        this.listener = listener;
    }

    public void cancel() { cancelled = true; }

    public List<Entry> scan(List<Folder> folders) {
        found.clear();
        for (Folder f : folders) {
            if (cancelled) break;
            if (isExcluded(f.path)) continue;
            try {
                walk(f, f.path, new ArrayList<>(Arrays.asList(f.name())), 0);
            } catch (IOException e) {
                // an unreachable folder is skipped; the rest of the library is still listed
                if (error == null) error = f.path + ": " + e;
            }
        }
        return new ArrayList<>(found);
    }

    /** The first folder that could not be read, with the reason; null when all went well. */
    public String error() { return error; }

    public boolean isExcluded(String path) {
        for (String x : excluded) if (path.equals(x) || path.startsWith(x + "/")) return true;
        return false;
    }

    private void walk(Folder root, String path, List<String> inside, int depth) throws IOException {
        if (cancelled || depth > MAX_DEPTH) return;
        if (listener != null) listener.onFolder(path, found.size());
        List<Node> nodes = source.list(path);
        List<Node> videos = new ArrayList<>();
        List<Node> sidecars = new ArrayList<>();
        List<String> sidecarPaths = new ArrayList<>();
        List<Node> dirs = new ArrayList<>();
        for (Node n : nodes) {
            if (skipName(n.name)) continue;
            if (n.dir) { dirs.add(n); continue; }
            String ext = ext(n.name);
            if (VIDEO.contains(ext)) { if (n.size >= minSize) videos.add(n); }
            else if (SUBS.contains(ext) || AUDIO.contains(ext)) { sidecars.add(n); sidecarPaths.add(join(path, n.name)); }
        }
        // Subtitles and soundtracks kept in a folder of their own ("Subs", "Rus Sound").
        List<Node> sideNodes = new ArrayList<>(sidecars);
        List<String> sidePaths = new ArrayList<>(sidecarPaths);
        List<Node> walkDirs = new ArrayList<>();
        for (Node d : dirs) {
            if (isSideDir(d.name)) {
                try {
                    for (Node n : source.list(join(path, d.name))) {
                        String e = ext(n.name);
                        if (!n.dir && !skipName(n.name) && (SUBS.contains(e) || AUDIO.contains(e))) {
                            sideNodes.add(n);
                            sidePaths.add(join(join(path, d.name), n.name));
                        }
                    }
                } catch (IOException ignored) {
                }
            } else {
                walkDirs.add(d);
            }
        }
        for (Node v : videos) {
            String full = join(path, v.name);
            if (isExcluded(full)) continue;
            Entry e = new Entry();
            e.path = full;
            e.name = v.name;
            e.size = v.size;
            e.mtime = v.mtime;
            e.root = root.path;
            List<String> folders = new ArrayList<>(inside);
            NameParser.Parsed p = NameParser.parse(folders, v.name, Folder.SHOWS.equals(root.kind));
            e.episode = p.episode;
            e.title = p.title;
            e.year = p.year;
            e.season = p.season;
            e.number = p.number;
            e.rawTitle = e.title;
            e.rawYear = e.year;
            if (p.episode && Folder.MOVIES.equals(root.kind)) { e.episode = false; NameParser.Parsed m = NameParser.parse(folders, v.name, false); e.title = m.title; e.year = m.year; e.rawTitle = m.title; e.rawYear = m.year; }
            String base = NameParser.stripExtension(v.name).toLowerCase(Locale.ROOT);
            for (int i = 0; i < sideNodes.size(); i++) {
                String sn = NameParser.stripExtension(sideNodes.get(i).name).toLowerCase(Locale.ROOT);
                boolean sameDir = sidePaths.get(i).startsWith(path + "/") || path.isEmpty();
                if (sn.startsWith(base) || (videos.size() == 1 && sameDir)) e.extras.add(sidePaths.get(i));
            }
            found.add(e);
        }
        for (Node d : walkDirs) {
            String child = join(path, d.name);
            if (isExcluded(child)) continue;
            List<String> deeper = new ArrayList<>(inside);
            deeper.add(d.name);
            try {
                walk(root, child, deeper, depth + 1);
            } catch (IOException ignored) {
            }
        }
    }

    /** An Entry for a video that is not in the library (opened from the folder tree): named by its own path. */
    public static Entry loose(String full, long size, long mtime) {
        Entry e = new Entry();
        e.path = full;
        int slash = full.lastIndexOf('/');
        e.name = slash < 0 ? full : full.substring(slash + 1);
        e.size = size;
        e.mtime = mtime;
        List<String> folders = new ArrayList<>();
        if (slash > 0) for (String part : full.substring(0, slash).split("/")) if (!part.isEmpty()) folders.add(part);
        NameParser.Parsed p = NameParser.parse(folders, e.name, false);
        e.episode = p.episode;
        e.title = p.title;
        e.year = p.year;
        e.season = p.season;
        e.number = p.number;
        e.rawTitle = e.title;
        e.rawYear = e.year;
        return e;
    }

    static boolean skipName(String name) {
        return name.startsWith(".") || name.endsWith(".part") || name.endsWith("~") || SKIP.contains(name.toLowerCase(Locale.ROOT));
    }

    static boolean isSideDir(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("subs") || n.equals("sub") || n.equals("subtitles") || n.equals("субтитры") || n.equals("rus subs")
                || n.equals("rus sound") || n.equals("audio") || n.equals("звук") || n.equals("sound");
    }

    public static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static String join(String dir, String name) {
        return dir.isEmpty() ? name : dir + "/" + name;
    }
}
