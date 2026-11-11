// SDK=~/Library/Android/sdk; org.json from maven (android.jar only has stubs):
// javac -cp $SDK/platforms/android-34/android.jar:libs/androidx-webkit-1.12.1.jar:json.jar -sourcepath src -d /tmp/cat src/me/champdev/weblock/Catalog.java test/CatalogTest.java && java -cp /tmp/cat:json.jar me.champdev.weblock.CatalogTest
package me.champdev.weblock;

import org.json.JSONObject;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

public class CatalogTest {
    static int fails;
    static void check(String name, boolean ok) { if (!ok) { fails++; System.out.println("FAIL " + name); } }

    public static void main(String[] a) throws Exception {
        List<Catalog.Title> list = Catalog.parseList(new String(Files.readAllBytes(Paths.get("test/fixtures/catalog.json"))), "movie");
        check("catalog has 3 titles", list.size() == 3);
        Catalog.Title t = list.get(0);
        check("title has id and name", t.id.startsWith("tt") && !t.name.isEmpty());
        check("poster upgraded from small to medium", !t.poster.contains("/poster/small/") && t.poster.startsWith("http"));
        check("movie type kept", t.type.equals("movie") && !t.series());

        JSONObject meta = new JSONObject(new String(Files.readAllBytes(Paths.get("test/fixtures/series-meta.json")))).getJSONObject("meta");
        Catalog.Title s = Catalog.parse(meta, "series");
        check("series name", s.name.equals("Breaking Bad"));
        check("genres joined", s.genres.contains("Crime") && s.genres.contains(" · "));
        check("specials (season 0) dropped", s.episodes.stream().noneMatch(e -> e.season < 1));
        check("one season counted", Catalog.seasons(s) == 1);
        check("episode fields", !s.episodes.isEmpty() && s.episodes.get(0).number >= 1 && !s.episodes.get(0).title.isEmpty());
        check("overview present", !s.overview.isEmpty());
        check("garbage gives empty list", Catalog.parseList("<html>nope</html>", "movie").isEmpty());

        List<Catalog.Title> local = Catalog.parseTmdb(new String(Files.readAllBytes(Paths.get("test/fixtures/tmdb-language.json")), java.nio.charset.StandardCharsets.UTF_8), "movie");
        check("TMDB titles without an IMDb link are dropped", local.size() == 2);
        check("TMDB title goes by its IMDb id, TMDB id kept", local.get(0).id.startsWith("tt") && local.get(0).tmdb.matches("\\d+") && !local.get(0).poster.isEmpty());
        check("TMDB garbage gives empty list", Catalog.parseTmdb("<html>", "movie").isEmpty());
        check("India has Hindi first", Catalog.languages("IN")[0].equals("Hindi") && Catalog.languages("IN").length > 3);
        check("no local list for an unknown country", Catalog.languages("ZZ").length == 0 && Catalog.languages("").length == 0 && Catalog.languages("I").length == 0);
        check("the UK is in the list, with English", Catalog.languages("GB").length == 1 && Catalog.languages("GB")[0].equals("English"));
        check("backdrop from the IMDb id", Catalog.backdrop(local.get(0)).startsWith("http") && Catalog.backdrop(new Catalog.Title()).isEmpty());

        check("start year of a range", Catalog.startYear("2002-2007") == 2002 && Catalog.startYear("2020–") == 2020 && Catalog.startYear("") == 0);
        long today = java.time.LocalDate.parse("2026-11-07").toEpochDay();
        Catalog.Title m = new Catalog.Title();
        m.released = "2026-10-02T00:00:00.000Z";
        check("movie out 36 days is new", Catalog.isNew(m, today));
        m.released = "2026-11-06T00:00:00.000Z";
        check("movie out yesterday is new (CAM)", Catalog.isNew(m, today));
        m.released = "2026-11-20T00:00:00.000Z";
        check("movie not out yet is not", !Catalog.isNew(m, today));
        m.released = "2026-01-02T00:00:00.000Z";
        check("movie out 309 days is old", !Catalog.isNew(m, today));
        m.released = "";
        check("no date is not new", !Catalog.isNew(m, today));
        Catalog.Title sr = new Catalog.Title();
        sr.type = "series";
        sr.released = "2026-11-01T00:00:00.000Z";
        check("series that started this week is new", Catalog.isNew(sr, today));
        sr.released = "2026-12-01T00:00:00.000Z";
        check("series not started is not", !Catalog.isNew(sr, today));
        check("released parsed", Catalog.parseList("{\"metas\":[{\"id\":\"tt1\",\"name\":\"A\",\"released\":\"2026-10-02T00:00:00.000Z\"}]}", "movie").get(0).released.startsWith("2026-10-02"));
        check("popular path", Catalog.path("movie", "genre=Comedy").equals("/catalog/movie/top/genre=Comedy.json") && Catalog.path("movie", "").equals("/catalog/movie/top.json"));
        Catalog.Title k = new Catalog.Title();
        check("kind movie", k.kind().equals("Movie"));
        k.type = "series";
        check("kind series", k.kind().equals("TV Series"));
        k.anime = true;
        check("kind anime", k.kind().equals("Anime"));
        System.out.println(fails == 0 ? "catalog tests passed" : fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
