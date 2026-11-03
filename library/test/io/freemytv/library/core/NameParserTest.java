package io.freemytv.library.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class NameParserTest {
    static int failed;

    static void movie(String file, String title, int year) {
        NameParser.Parsed p = NameParser.parse(Collections.singletonList("!кино"), file, false);
        check(file, !p.episode && p.title.equals(title) && p.year == year, "movie " + p.title + "|" + p.year + " ep=" + p.episode);
    }

    static void episode(List<String> dirs, String file, String show, int season, int number) {
        NameParser.Parsed p = NameParser.parse(dirs, file, true);
        check(file, p.episode && p.showTitle.equals(show) && p.season == season && p.number == number,
                "ep " + p.showTitle + " s" + p.season + "e" + p.number);
    }

    static void check(String what, boolean ok, String got) {
        if (!ok) { failed++; System.out.println("FAIL " + what + " -> " + got); }
    }

    public static void main(String[] a) {
        movie("Harry.Potter.and.the.Chamber.of.Secrets.2002.1080p.BDRip.x264.mkv", "Harry Potter and the Chamber of Secrets", 2002);
        movie("Harry.Potter.and.the.Deathly.Hallows.Part.1.2010.BluRay.DTS.AC3.1080p.x264-EbP.mkv", "Harry Potter and the Deathly Hallows Part 1", 2010);
        movie("007 Координаты «Скайфолл».2012.UHD.Blu-Ray.Remux.2160p.mkv", "007 Координаты «Скайфолл»", 2012);
        movie("007 СПЕКТР.2015.UHD.Blu-Ray.Remux.2160p.mkv", "007 СПЕКТР", 2015);
        movie("Не время умирать.2021.WEB-DL.2160p.HDR.mkv", "Не время умирать", 2021);
        movie("Boss Level (2021).mkv", "Boss Level", 2021);
        movie("Blow.2001.1080p.BDRip.x264.DTS.RUS.ENG.mkv", "Blow", 2001);
        movie("Batman.Begins.2005.BDRip.1080p[R.G.KiNOvNiNO].mkv", "Batman Begins", 2005);
        movie("The.Dark.Knight.Rises.1080p.BDRip[nntt].mkv", "The Dark Knight Rises", 0);
        movie("The.Dark.Knight.2008.BDRip.1080p.Rus.Eng [R.G.Daredevil].mkv", "The Dark Knight", 2008);
        movie("О чём говорят мужчины. Продолжение_2018_WEB-DLRip.avi", "О чём говорят мужчины Продолжение", 2018);
        movie("День выборов 2.mkv", "День выборов 2", 0);
        movie("Быстрее, чем кролики.avi", "Быстрее, чем кролики", 0);
        movie("Громкая связь WEB-DLRip.avi", "Громкая связь", 0);
        movie("2012.2009.1080p.BDRip.mkv", "2012", 2009);

        List<String> bb = Arrays.asList("Сериалы", "Breaking Bad", "Season 2");
        episode(bb, "Breaking.Bad.S02E05.1080p.mkv", "Breaking Bad", 2, 5);
        episode(bb, "05 - Breakage.mkv", "Breaking Bad", 2, 5);
        episode(Arrays.asList("Сериалы", "Игра престолов (2011)", "1 сезон"), "Серия 3.mkv", "Игра престолов", 1, 3);
        episode(Arrays.asList("Сериалы", "Show.Name.S03.1080p.WEB-DL"), "Show.Name.S03E10.mkv", "Show Name", 3, 10);
        episode(Arrays.asList("Сериалы", "Тайны следствия"), "Тайны следствия 2x07.avi", "Тайны следствия", 2, 7);
        episode(Arrays.asList("Сериалы"), "Лопе.S01E01.mkv", "Лопе", 1, 1);

        NameParser.Parsed p = NameParser.parse(Arrays.asList("!кино", "Бэтмен"), "Batman.Begins.2005.1080p.mkv", false);
        check("batman folder", !p.episode && p.title.equals("Batman Begins"), p.title);
        p = NameParser.parse(Arrays.asList("Загрузки", "The Wire"), "The.Wire.S01E03.720p.mkv", false);
        check("auto episode", p.episode && p.showTitle.equals("The Wire") && p.season == 1 && p.number == 3, p.showTitle + p.season + p.number);

        check("season dir", NameParser.seasonOfDir("Season 3") == 3 && NameParser.seasonOfDir("2 сезон") == 2 && NameParser.seasonOfDir("S04") == 4
                && NameParser.seasonOfDir("Бэтмен") == 0, "seasonOfDir");
        check("normalize", NameParser.normalize("Ёлки: «Новые»!").equals("елки новые"), NameParser.normalize("Ёлки: «Новые»!"));

        Object j = MiniJson.parse("{\"a\":[1,2.5,\"x\\u0041\\n\"],\"b\":{\"c\":true,\"d\":null},\"e\":\"Привет\"}");
        check("json", MiniJson.list(MiniJson.map(j).get("a")).size() == 3 && MiniJson.str(j, "e").equals("Привет")
                && MiniJson.parse("{broken") == null, "json parse");
        check("quote", MiniJson.quote("a\"b\\c\n").equals("\"a\\\"b\\\\c\\n\""), MiniJson.quote("a\"b\\c\n"));

        System.out.println(failed == 0 ? "ALL OK" : failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }
}
