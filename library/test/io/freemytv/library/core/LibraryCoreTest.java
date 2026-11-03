package io.freemytv.library.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class LibraryCoreTest {
    static int failed;

    static void check(String what, boolean ok, String got) {
        if (!ok) { failed++; System.out.println("FAIL " + what + " -> " + got); }
    }

    static void make(File root, String path, long size) throws Exception {
        File f = new File(root, path);
        f.getParentFile().mkdirs();
        byte[] data = new byte[(int) size];
        new Random(path.hashCode()).nextBytes(data);
        Files.write(f.toPath(), data);
    }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("lib").toFile();
        make(root, "!кино/Harry.Potter.Hexalogy.2001-2009.1080p/Harry.Potter.and.the.Chamber.of.Secrets.2002.1080p.BDRip.x264.mkv", 5000);
        make(root, "!кино/Harry.Potter.Hexalogy.2001-2009.1080p/Harry.Potter.and.the.Chamber.of.Secrets.2002.1080p.BDRip.x264.rus.srt", 100);
        make(root, "!кино/Бэтмен/._The.Dark.Knight.2008.BDRip.1080p.mkv", 5000);
        make(root, "!кино/Бэтмен/The.Dark.Knight.2008.BDRip.1080p.mkv", 5000);
        make(root, "!кино/Бэтмен/Batman.Begins.2005.mkv.part", 5000);
        make(root, "!кино/Квартет И/День радио.avi", 5000);
        make(root, "!кино/Квартет И/Subs/День радио.srt", 50);
        make(root, "!кино/sample.mkv", 10);
        make(root, "Сериалы/Breaking Bad/Season 1/Breaking.Bad.S01E01.mkv", 5000);
        make(root, "Сериалы/Breaking Bad/Season 1/Breaking.Bad.S01E02.mkv", 5000);
        make(root, "Сериалы/Breaking Bad/Season 2/05 - Breakage.mkv", 5000);
        make(root, "Загрузки/The.Wire.S01E03.720p.mkv", 5000);
        make(root, "Загрузки/Новое/Фильм.2020.mkv", 5000);
        make(root, "ssp/Что-то.mkv", 5000);
        make(root, "Договоры/скан.pdf", 100);

        FileSource src = new FileSource(root);
        List<Folder> folders = Arrays.asList(new Folder("!кино", Folder.AUTO), new Folder("Сериалы", Folder.SHOWS), new Folder("Загрузки", Folder.AUTO));
        Scanner sc = new Scanner(src, Collections.singletonList("Загрузки/Новое"), 1000, null);
        List<Entry> found = sc.scan(folders);

        List<String> names = new ArrayList<>();
        for (Entry e : found) names.add(e.path);
        check("count", found.size() == 7, names.toString());
        check("no junk", !names.toString().contains("._") && !names.toString().contains(".part") && !names.toString().contains("sample")
                && !names.toString().contains("ssp") && !names.toString().contains("Договоры") && !names.toString().contains("Новое"), names.toString());

        Entry chamber = null, radio = null, wire = null, bad5 = null;
        for (Entry e : found) {
            if (e.name.startsWith("Harry.Potter.and.the.Chamber")) chamber = e;
            if (e.name.startsWith("День радио")) radio = e;
            if (e.name.startsWith("The.Wire")) wire = e;
            if (e.name.startsWith("05 - Breakage")) bad5 = e;
        }
        check("chamber", chamber != null && !chamber.episode && chamber.title.equals("Harry Potter and the Chamber of Secrets") && chamber.year == 2002
                && chamber.extras.size() == 1 && chamber.extras.get(0).endsWith(".rus.srt"), String.valueOf(chamber == null ? null : chamber.title + chamber.extras));
        check("radio subs", radio != null && radio.extras.size() == 1 && radio.extras.get(0).contains("Subs/"), String.valueOf(radio == null ? null : radio.extras));
        check("wire", wire != null && wire.episode && wire.title.equals("The Wire") && wire.season == 1 && wire.number == 3, "wire");
        check("breakage", bad5 != null && bad5.episode && bad5.title.equals("Breaking Bad") && bad5.season == 2 && bad5.number == 5, bad5 == null ? "null" : bad5.title + bad5.season + bad5.number);

        // Catalog: save, reload, shows, progress, typed titles.
        File data = Files.createTempDirectory("cat").toFile();
        Catalog cat = new Catalog(data);
        cat.replace(found);
        Catalog again = new Catalog(data);
        check("reload", again.all().size() == 7 && again.movies().size() == 3, again.all().size() + " " + again.movies().size());
        List<Catalog.Show> shows = again.shows();
        check("shows", shows.size() == 2 && shows.get(0).title.equals("Breaking Bad") && shows.get(0).seasons.size() == 2 && shows.get(0).episodes() == 3,
                shows.size() + " " + (shows.isEmpty() ? "" : shows.get(0).title));
        Entry first = again.nextEpisode(shows.get(0));
        check("next episode", first != null && first.number == 1, String.valueOf(first));
        again.saveProgress(first.path, 600_000, 3_000_000);
        check("started", again.progress(first.path).started() && again.continueWatching(5).size() == 1, "progress");
        again.saveProgress(first.path, 2_900_000, 3_000_000);
        check("watched", again.progress(first.path).watched() && again.nextEpisode(shows.get(0)).number == 2, "watched");
        Entry batman = null;
        for (Entry e : again.all()) if (e.name.equals("The.Dark.Knight.2008.BDRip.1080p.mkv")) batman = e;
        again.fixTitle(batman, "Тёмный рыцарь (2008)");
        check("fix", batman.title.equals("Тёмный рыцарь") && batman.year == 2008, batman.title);
        Catalog third = new Catalog(data);
        Entry b2 = third.byPath(batman.path);
        check("fix kept", b2.title.equals("Тёмный рыцарь"), b2.title);
        third.clearFix(b2);
        check("fix cleared", b2.title.equals("The Dark Knight"), b2.title);

        // The local server: whole file, ranges, names with spaces and Cyrillic.
        MediaServer server = new MediaServer(src);
        String url = server.url("!кино/Квартет И/День радио.avi");
        byte[] whole = fetch(url, null);
        byte[] expect = Files.readAllBytes(new File(root, "!кино/Квартет И/День радио.avi").toPath());
        check("whole", Arrays.equals(whole, expect), "whole length " + whole.length);
        byte[] part = fetch(url, "bytes=1000-1999");
        check("range", Arrays.equals(part, Arrays.copyOfRange(expect, 1000, 2000)), "range length " + part.length);
        byte[] tail = fetch(url, "bytes=4990-");
        check("open range", Arrays.equals(tail, Arrays.copyOfRange(expect, 4990, 5000)), "tail length " + tail.length);
        HttpURLConnection head = (HttpURLConnection) new URL(url).openConnection();
        head.setRequestMethod("HEAD");
        check("head", head.getResponseCode() == 200 && head.getContentLengthLong() == 5000, head.getResponseCode() + " " + head.getContentLengthLong());
        HttpURLConnection missing = (HttpURLConnection) new URL(url.replace("%D0%94", "%D0%95")).openConnection();
        check("missing", missing.getResponseCode() == 404, String.valueOf(missing.getResponseCode()));
        server.stop();

        System.out.println(failed == 0 ? "ALL OK" : failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    static byte[] fetch(String url, String range) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (range != null) c.setRequestProperty("Range", range);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            if (range != null && c.getResponseCode() != 206) throw new IllegalStateException("not 206: " + c.getResponseCode());
            return out.toByteArray();
        }
    }
}
