package io.freemytv.library.core;

import java.util.ArrayList;
import java.util.List;

/** A video file of the library, with what its name says about it. */
public final class Entry {
    public String path = "";
    public long size, mtime;
    public String root = "";             // the library folder it was found under
    public boolean episode;
    public String title = "";            // the film's title, or the series' title
    public int year;
    public String rawTitle = "";         // what the name said, before a title typed by hand
    public int rawYear;
    public int season, number;
    public String name = "";             // the file's own name
    public List<String> extras = new ArrayList<>();   // subtitle and audio files that go with it

    /** What identifies the film or the series for the online lookup. */
    public String key() {
        return (episode ? "tv|" : "movie|") + NameParser.normalize(title) + "|" + year;
    }

    public String folder() {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    String toLine() {
        StringBuilder b = new StringBuilder();
        b.append(path).append('\t').append(size).append('\t').append(mtime).append('\t').append(root).append('\t')
                .append(episode ? 1 : 0).append('\t').append(title).append('\t').append(year).append('\t').append(season).append('\t')
                .append(number).append('\t').append(rawTitle).append('\t').append(rawYear).append('\t');
        for (int i = 0; i < extras.size(); i++) {
            if (i > 0) b.append('\u001f');
            b.append(extras.get(i));
        }
        return b.toString();
    }

    static Entry fromLine(String line) {
        String[] f = line.split("\t", -1);
        if (f.length < 12) return null;
        Entry e = new Entry();
        try {
            e.path = f[0];
            e.size = Long.parseLong(f[1]);
            e.mtime = Long.parseLong(f[2]);
            e.root = f[3];
            e.episode = "1".equals(f[4]);
            e.title = f[5];
            e.year = Integer.parseInt(f[6]);
            e.season = Integer.parseInt(f[7]);
            e.number = Integer.parseInt(f[8]);
            e.rawTitle = f[9];
            e.rawYear = Integer.parseInt(f[10]);
        } catch (NumberFormatException x) {
            return null;
        }
        if (!f[11].isEmpty()) for (String s : f[11].split("\u001f")) e.extras.add(s);
        int slash = e.path.lastIndexOf('/');
        e.name = slash < 0 ? e.path : e.path.substring(slash + 1);
        return e;
    }
}
