package io.freemytv.library.core;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns file and folder names like "Harry.Potter.and.the.Chamber.of.Secrets.2002.1080p.BDRip.x264.mkv" or
 * "007 СПЕКТР.2015.UHD.Blu-Ray.Remux.2160p.mkv" into a title, a year and, for series, a season and an episode.
 */
public final class NameParser {
    private NameParser() {}

    public static final class Parsed {
        public String title = "";
        public int year;
        public boolean episode;
        public int season;
        public int number;      // episode number, 0 = unknown
        public String showTitle = "";
        public int showYear;
    }

    private static final Pattern TAG = Pattern.compile("(?iu)^(\\d{3,4}[pi]|4k|8k|uhd|hdr\\w*|dv|dovi|bd-?rip\\w*|br-?rip\\w*|blu-?ray\\w*|bdremux|remux|"
            + "web-?dl\\w*|web-?rip\\w*|webrip|hd-?rip\\w*|dvd-?rip\\w*|dvd\\d*|dvdscr|hdtv\\w*|tvrip|satrip|x26[45]|h\\.?26[45]|hevc|avc|xvid|divx|"
            + "dts\\S*|ac-?3|e-?ac-?3|aac\\d*|flac|truehd|atmos|10-?bit|imax|rus|eng|ukr|multi|dub|dubbed|mvo|dvo|avo|sub|subs|"
            + "extended|unrated|remastered|proper|repack|hybrid|ddp\\d*|\\d+mb|\\d+gb|s\\d{1,2}(e\\d{1,3})*|\\d{1,2}x\\d{2,3}|"
            + "сезон\\S*|season\\S*|серия|серии|complete|полный|hexalogy|trilogy|collection)$");
    private static final Pattern YEAR = Pattern.compile("^(?:\\(|\\[)?((?:19|20)\\d\\d)(?:-(?:19|20)\\d\\d)?(?:\\)|\\])?$");
    private static final Pattern SE = Pattern.compile("(?iu)(?<![a-z0-9])s(\\d{1,2})\\s*[ex.]?\\s*e(\\d{1,3})");
    private static final Pattern X = Pattern.compile("(?iu)(?<![a-z0-9])(\\d{1,2})x(\\d{2,3})(?![a-z0-9])");
    private static final Pattern RU = Pattern.compile("(?iu)(\\d{1,2})\\s*сезон.{0,12}?(\\d{1,3})\\s*сери");
    private static final Pattern EP_WORD = Pattern.compile("(?iu)(?:сери[яи]|серия|episode|ep|эп)\\.?\\s*(\\d{1,3})|(\\d{1,3})\\s*сери[яи]");
    private static final Pattern EP_DASH = Pattern.compile("(?:^|\\s)-\\s*(\\d{1,3})(?:\\s|$)");
    private static final Pattern EP_LEAD = Pattern.compile("^(\\d{1,3})(?:\\s|-|\\.)");
    private static final Pattern SEASON_DIR = Pattern.compile("(?iu)^(?:(?:season|сезон|s)\\s*(\\d{1,2})|(\\d{1,2})\\s*(?:season|сезон)\\S*)(?:\\D.*)?$");
    private static final Pattern GENERIC_DIR = Pattern.compile("(?iu)^(сериалы|сериал|series|tv|tv ?shows?|shows?|кино|фильмы|movies|!кино|загрузки|downloads|uhd|1080p|2160p|720p)$");

    /** A video file name, minus its extension. */
    public static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && name.length() - dot <= 6 ? name.substring(0, dot) : name;
    }

    /**
     * @param folders  the folders from the library folder down to the file's own, names only
     * @param fileName the file's name with its extension
     * @param showsRoot true when the library folder is marked as series (every file there is an episode)
     */
    public static Parsed parse(List<String> folders, String fileName, boolean showsRoot) {
        Parsed p = new Parsed();
        String base = stripExtension(fileName);
        int season = 0, number = 0;
        boolean sxe = false;

        Matcher m = SE.matcher(base);
        if (m.find()) { season = Integer.parseInt(m.group(1)); number = Integer.parseInt(m.group(2)); sxe = true; }
        if (!sxe && (m = X.matcher(base)).find()) { season = Integer.parseInt(m.group(1)); number = Integer.parseInt(m.group(2)); sxe = true; }
        if (!sxe && (m = RU.matcher(base)).find()) { season = Integer.parseInt(m.group(1)); number = Integer.parseInt(m.group(2)); sxe = true; }

        if (season == 0) {
            for (int i = folders.size() - 1; i >= 0; i--) {
                int s = seasonOfDir(folders.get(i));
                if (s > 0) { season = s; break; }
            }
        }
        boolean episode = sxe || showsRoot;
        if (episode && number == 0) number = episodeNumber(base);
        if (episode && season == 0) season = 1;

        if (!episode) {
            String[] t = clean(base);
            p.title = t[0];
            p.year = t[1].isEmpty() ? 0 : Integer.parseInt(t[1]);
            if (p.title.isEmpty()) p.title = base;
            return p;
        }
        p.episode = true;
        p.season = season;
        p.number = number;
        // The series is named by the nearest folder that isn't "Season 1" or a general one like "Сериалы".
        String show = "";
        int showYear = 0;
        for (int i = folders.size() - 1; i >= 0; i--) {
            String f = folders.get(i);
            if (seasonOfDir(f) > 0 && !hasTitleBeyondSeason(f)) continue;
            if (GENERIC_DIR.matcher(f.trim()).matches()) continue;
            String[] t = clean(f);
            if (t[0].isEmpty()) continue;
            show = t[0];
            showYear = t[1].isEmpty() ? 0 : Integer.parseInt(t[1]);
            break;
        }
        if (show.isEmpty()) {
            // No usable folder: the part of the file name before the episode marker.
            Matcher se = SE.matcher(base);
            String head = se.find() ? base.substring(0, se.start()) : base;
            String[] t = clean(head);
            show = t[0].isEmpty() ? clean(base)[0] : t[0];
            showYear = t[1].isEmpty() ? 0 : Integer.parseInt(t[1]);
        }
        p.showTitle = show;
        p.showYear = showYear;
        p.title = show;
        p.year = showYear;
        return p;
    }

    /** The season number a folder name stands for ("Season 2", "2 сезон", "S03"), 0 if it isn't one. */
    public static int seasonOfDir(String dir) {
        Matcher m = SEASON_DIR.matcher(dir.trim());
        if (!m.matches()) return 0;
        String g = m.group(1) != null ? m.group(1) : m.group(2);
        return g == null ? 0 : Integer.parseInt(g);
    }

    private static boolean hasTitleBeyondSeason(String dir) {
        // "Show.Name.S01.1080p" is a season folder that also carries the series' name.
        String[] t = clean(dir);
        return !t[0].isEmpty() && !dir.trim().matches("(?iu)^(season|сезон|s)\\s*\\d{1,2}$") && !dir.trim().matches("(?iu)^\\d{1,2}\\s*(season|сезон)\\S*$");
    }

    private static int episodeNumber(String base) {
        String s = base.replace('_', ' ').replace('.', ' ');
        Matcher m = EP_WORD.matcher(s);
        if (m.find()) return Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
        m = EP_DASH.matcher(s);
        if (m.find()) return Integer.parseInt(m.group(1));
        m = EP_LEAD.matcher(s.trim());
        if (m.find()) return Integer.parseInt(m.group(1));
        m = Pattern.compile("\\[(\\d{2,3})\\]").matcher(s);
        if (m.find()) return Integer.parseInt(m.group(1));
        return 0;
    }

    /** {title, year} from a release-style name; year is "" when there is none. */
    public static String[] clean(String raw) {
        String s = raw;
        String year = "";
        // (2021) keeps its year; any other (...) or [...] (release groups, languages) goes.
        Matcher py = Pattern.compile("[\\(\\[]\\s*((?:19|20)\\d\\d)\\s*[\\)\\]]").matcher(s);
        if (py.find()) year = py.group(1);
        s = s.replaceAll("\\[[^\\]]*\\]", " ").replaceAll("\\([^)]*\\)", " ");
        s = s.replace('_', ' ').replace('.', ' ').replace('+', ' ');
        s = s.replaceAll("^[\\s._\\-]+", "");
        String[] tokens = s.trim().split("\\s+");
        StringBuilder title = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.isEmpty()) continue;
            Matcher ym = YEAR.matcher(t);
            if (ym.matches()) {
                if (title.length() == 0) {       // a title that is itself a year ("2012")
                    title.append(t);
                    continue;
                }
                if (year.isEmpty()) year = ym.group(1);
                break;
            }
            if (TAG.matcher(t).matches() && title.length() > 0) break;
            if (title.length() > 0) title.append(' ');
            title.append(t);
        }
        String out = title.toString().replaceAll("[\\s\\-,:;]+$", "").trim();
        return new String[] {out, year};
    }

    /** A form for comparing titles: lower case, no punctuation, "ё" as "е". */
    public static String normalize(String s) {
        return s.toLowerCase().replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}
