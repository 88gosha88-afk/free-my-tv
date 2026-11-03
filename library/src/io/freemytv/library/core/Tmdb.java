package io.freemytv.library.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The online descriptions, posters and episode names (themoviedb.org), in Russian, kept on the device once found. */
public final class Tmdb {
    /** What is known about a film or a series. */
    public static final class Meta {
        public String kind = "";      // "movie" or "tv"
        public int id;
        public String title = "", original = "", overview = "", poster = "", backdrop = "";
        public int year;
        public double rating;

        public boolean found() { return id != 0; }

        String toJson() {
            return "{\"k\":" + MiniJson.quote(kind) + ",\"id\":" + id + ",\"t\":" + MiniJson.quote(title) + ",\"o\":" + MiniJson.quote(original)
                    + ",\"d\":" + MiniJson.quote(overview) + ",\"p\":" + MiniJson.quote(poster) + ",\"b\":" + MiniJson.quote(backdrop)
                    + ",\"y\":" + year + ",\"r\":" + rating + ",\"at\":" + System.currentTimeMillis() + "}";
        }

        static Meta fromJson(Object j) {
            Meta m = new Meta();
            m.kind = MiniJson.str(j, "k");
            m.id = (int) MiniJson.num(j, "id");
            m.title = MiniJson.str(j, "t");
            m.original = MiniJson.str(j, "o");
            m.overview = MiniJson.str(j, "d");
            m.poster = MiniJson.str(j, "p");
            m.backdrop = MiniJson.str(j, "b");
            m.year = (int) MiniJson.num(j, "y");
            m.rating = MiniJson.num(j, "r");
            m.at = (long) MiniJson.num(j, "at");
            return m;
        }

        long at;
    }

    public static final class EpisodeMeta {
        public int number;
        public String name = "", overview = "", still = "";
        public int minutes;
    }

    private static final String[] HOSTS = {"https://api.themoviedb.org", "https://api.tmdb.org"};
    public static final String IMAGES = "https://image.tmdb.org/t/p/";
    private static final long MISS_RETRY_MS = 14L * 24 * 3600 * 1000;

    private final String key;
    private final File file;
    private final Map<String, Meta> cache = new HashMap<>();
    private int host;

    public Tmdb(String key, File dir) {
        this.key = key == null ? "" : key.trim();
        dir.mkdirs();
        this.file = new File(dir, "meta.tsv");
        for (String line : Catalog.readLines(file)) {
            int tab = line.indexOf('\t');
            if (tab < 0) continue;
            Object j = MiniJson.parse(line.substring(tab + 1));
            if (j != null) cache.put(line.substring(0, tab), Meta.fromJson(j));
        }
    }

    public boolean configured() { return !key.isEmpty(); }

    /** The saved description, or null if none was looked for yet (or an old "not found" is due again). */
    public synchronized Meta cached(String entryKey) {
        Meta m = cache.get(entryKey);
        if (m == null) return null;
        if (!m.found() && System.currentTimeMillis() - m.at > MISS_RETRY_MS) return null;
        return m;
    }

    /** Looks the title up (a film or a series per `episode`), saves and returns what was found; throws when the network fails. */
    public Meta lookup(String entryKey, boolean episode, String title, int year) throws IOException {
        Meta have = cached(entryKey);
        if (have != null) return have;
        Meta m = episode ? search("tv", title, year) : search("movie", title, year);
        if (!m.found() && year > 0) m = episode ? search("tv", title, 0) : search("movie", title, 0);
        synchronized (this) {
            cache.put(entryKey, m);
            save();
        }
        return m;
    }

    private Meta search(String kind, String title, int year) throws IOException {
        String q = title.replaceAll("[«»\"“”]", " ").trim();
        StringBuilder url = new StringBuilder("/3/search/").append(kind).append("?language=ru-RU&include_adult=false&query=").append(enc(q));
        if (year > 0) url.append(kind.equals("tv") ? "&first_air_date_year=" : "&year=").append(year);
        Object json = MiniJson.parse(get(url.toString()));
        List<Object> results = MiniJson.list(MiniJson.map(json).get("results"));
        Meta best = new Meta();
        double bestScore = 0;
        String want = NameParser.normalize(q);
        for (Object r : results) {
            String name = MiniJson.str(r, kind.equals("tv") ? "name" : "title");
            String orig = MiniJson.str(r, kind.equals("tv") ? "original_name" : "original_title");
            String date = MiniJson.str(r, kind.equals("tv") ? "first_air_date" : "release_date");
            int y = date.length() >= 4 ? parseInt(date.substring(0, 4)) : 0;
            double score = 0;
            String n1 = NameParser.normalize(name), n2 = NameParser.normalize(orig);
            if (n1.equals(want) || n2.equals(want)) score += 100;
            else if (n1.contains(want) || n2.contains(want) || (!n1.isEmpty() && want.contains(n1))) score += 40;
            if (year > 0 && y > 0) score += Math.abs(y - year) == 0 ? 30 : Math.abs(y - year) == 1 ? 15 : -20;
            score += Math.min(10, MiniJson.num(r, "popularity") / 20);
            if (!MiniJson.str(r, "poster_path").isEmpty()) score += 3;
            if (score > bestScore) {
                bestScore = score;
                best = new Meta();
                best.kind = kind;
                best.id = (int) MiniJson.num(r, "id");
                best.title = name;
                best.original = orig;
                best.overview = MiniJson.str(r, "overview");
                best.poster = MiniJson.str(r, "poster_path");
                best.backdrop = MiniJson.str(r, "backdrop_path");
                best.year = y;
                best.rating = MiniJson.num(r, "vote_average");
            }
        }
        // A weak match (nothing alike in the names) is worse than none.
        if (bestScore < 40) return new Meta();
        return best;
    }

    /** What a search finds for a typed title among films and series, best first; the person picks the right one. */
    public List<Meta> candidates(String query) throws IOException {
        List<Meta> out = new ArrayList<>();
        String q = enc(query.trim());
        for (String kind : new String[] {"movie", "tv"}) {
            Object json = MiniJson.parse(get("/3/search/" + kind + "?language=ru-RU&include_adult=false&query=" + q));
            int n = 0;
            for (Object r : MiniJson.list(MiniJson.map(json).get("results"))) {
                if (n++ >= 6) break;
                boolean tv = kind.equals("tv");
                Meta m = new Meta();
                m.kind = kind;
                m.id = (int) MiniJson.num(r, "id");
                m.title = MiniJson.str(r, tv ? "name" : "title");
                m.original = MiniJson.str(r, tv ? "original_name" : "original_title");
                m.overview = MiniJson.str(r, "overview");
                m.poster = MiniJson.str(r, "poster_path");
                m.backdrop = MiniJson.str(r, "backdrop_path");
                String date = MiniJson.str(r, tv ? "first_air_date" : "release_date");
                m.year = date.length() >= 4 ? parseInt(date.substring(0, 4)) : 0;
                m.rating = MiniJson.num(r, "vote_average");
                if (m.id != 0) out.add(m);
            }
        }
        return out;
    }

    /** Remembers the description the person chose for this film or series (it is never replaced by a later search). */
    public synchronized void assign(String entryKey, Meta m) {
        cache.put(entryKey, m);
        save();
    }

    /** The episodes of one season of a series (names and descriptions); throws when the network fails. */
    public List<EpisodeMeta> season(int showId, int season) throws IOException {
        Object json = MiniJson.parse(get("/3/tv/" + showId + "/season/" + season + "?language=ru-RU"));
        List<EpisodeMeta> out = new ArrayList<>();
        for (Object e : MiniJson.list(MiniJson.map(json).get("episodes"))) {
            EpisodeMeta m = new EpisodeMeta();
            m.number = (int) MiniJson.num(e, "episode_number");
            m.name = MiniJson.str(e, "name");
            m.overview = MiniJson.str(e, "overview");
            m.still = MiniJson.str(e, "still_path");
            m.minutes = (int) MiniJson.num(e, "runtime");
            out.add(m);
        }
        return out;
    }

    private String get(String path) throws IOException {
        if (key.isEmpty()) throw new IOException("no TMDB key");
        IOException last = null;
        // A v3 key goes in the address; the long v4 token goes in a header.
        boolean bearer = key.length() > 40;
        for (int attempt = 0; attempt < HOSTS.length * 2; attempt++) {
            boolean doh = attempt >= HOSTS.length;   // second round: the address comes from DNS-over-HTTPS (the provider's DNS lies)
            String base = HOSTS[(host + attempt) % HOSTS.length];
            String url = base + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + key);
            HttpURLConnection c = null;
            try {
                int code;
                String body;
                if (doh) {
                    Doh.Reply r = Doh.get(url, bearer ? key : null);
                    code = r.code;
                    body = r.body;
                } else {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(12000);
                    c.setRequestProperty("Accept", "application/json");
                    if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
                    code = c.getResponseCode();
                    body = "";
                    if (code == 200) {
                        try (InputStream in = c.getInputStream()) {
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                            body = out.toString(StandardCharsets.UTF_8.name());
                        }
                    }
                }
                if (code == 401) throw new IOException("TMDB key rejected (HTTP 401)");
                if (code == 429) { sleep(1500); throw new IOException("TMDB busy (HTTP 429)"); }
                if (code != 200) throw new IOException("TMDB HTTP " + code);
                if (!doh) host = (host + attempt) % HOSTS.length;
                return body;
            } catch (IOException e) {
                last = e;
                if (e.getMessage() != null && e.getMessage().contains("401")) break;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw last != null ? last : new IOException("TMDB unreachable");
    }

    private synchronized void save() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Meta> e : cache.entrySet()) lines.add(e.getKey() + "\t" + e.getValue().toJson());
        Catalog.writeLines(file, lines);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (java.io.UnsupportedEncodingException e) { return s; }
    }
}
