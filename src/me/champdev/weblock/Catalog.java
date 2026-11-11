package me.champdev.weblock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Movies and series from Cinemeta (Stremio's public catalog, no key): popular lists, search, details with episodes. */
final class Catalog {
    static final String BASE = "https://v3-cinemeta.strem.io";
    static final long TTL = 6L * 3600 * 1000;
    static final String[] GENRES = {"Action", "Adventure", "Animation", "Biography", "Comedy", "Crime", "Documentary", "Drama", "Family",
        "Fantasy", "History", "Horror", "Mystery", "Romance", "Sci-Fi", "Sport", "Thriller", "War", "Western"};
    /** Genre chips that are really other lists: popular titles out lately (see fresh), and Cinemeta's catalog of this year's releases. */
    static final String NEW = "New";
    /** A row's `extra` for New anime (see newAnime). */
    static final String NEW_ANIME = "anime=new", TRENDING_ANIME = "anime=trending";
    /** The Anime tab's grid: Kitsu's most popular anime (paged), every title marked as anime. Used as the tab's genre. */
    static final String POPULAR_ANIME = "anime-popular";

    /** Kitsu's page for the grid's skip: Kitsu repeats page one at skip=20, so later pages start at 40 (20 per page). */
    static List<Title> popularAnime(String extra, boolean diskOnly) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("skip=(\\d+)").matcher(extra);
        int skip = m.find() ? Integer.parseInt(m.group(1)) : 0;
        return kitsu("kitsu-anime-popular", new int[]{skip == 0 ? 0 : skip + 20}, 0, diskOnly);
    }
    static final String KITSU = "https://anime-kitsu.strem.fun";

    static final class Episode {
        int season, number;
        String title = "", overview = "", thumb = "";
    }

    static final class Title {
        String tmdb = "";   // TheMovieDB id (Cinemeta meta only): many sites key their pages by it
        String id = "", type = "movie", name = "", year = "", rating = "", poster = "", background = "", overview = "", runtime = "", genres = "";
        String cast = "", director = "", writer = "", country = "", awards = "", status = "";   // from the detail page
        String trailer = "";   // YouTube id (detail page)
        String released = "";   // "2026-10-02T00:00:00.000Z": cinema release for a movie, first episode for a series
        boolean anime;   // set by search, from Kitsu's anime list
        final List<Episode> episodes = new ArrayList<>();

        boolean series() { return type.equals("series"); }

        String kind() { return anime ? "Anime" : series() ? "TV Series" : "Movie"; }
    }

    /** One catalog page. type = movie|series; extra = "" | "genre=Comedy" | "skip=50" | "search=text". Null when offline with nothing cached. */
    static List<Title> list(String type, String extra) {
        if (extra.startsWith("local=")) return local(type, extra.substring(6), false);
        if (extra.startsWith("genre=" + LANG)) return language(type, extra.substring(6 + LANG.length()), false);
        if (extra.equals(NEW_ANIME)) return newAnime(false);
        if (extra.equals(TRENDING_ANIME)) return kitsu("kitsu-anime-trending", new int[]{0}, 0, false);
        if (extra.startsWith("genre=" + POPULAR_ANIME)) return popularAnime(extra, false);
        if (extra.startsWith("genre=" + NEW)) return extra.contains("skip=") ? new ArrayList<>() : fresh(type, false);   // one page only
        String text = get(path(type, extra), !extra.startsWith("search="));
        return text == null ? null : parseList(text, type);
    }

    static String path(String type, String extra) {
        return "/catalog/" + type + "/top" + (extra.isEmpty() ? "" : "/" + extra) + ".json";
    }

    /**
     * New: the newest releases, newest first. The first 100 of this year's catalog (and last year's while the 200-day window reaches
     * back into it), keeping what is already out: a movie from its cinema release (sites have a CAM copy within days), a series from its
     * first episode. Upcoming titles are left out. `diskOnly`: null unless every page is already cached. Null when offline with nothing cached.
     */
    static List<Title> fresh(String type, boolean diskOnly) {
        java.time.LocalDate now = java.time.LocalDate.now();
        long today = now.toEpochDay();
        java.util.Map<String, Title> found = new java.util.LinkedHashMap<>();
        boolean any = false;
        for (int year = now.getYear(); year >= now.minusDays(200).getYear(); year--) {
            for (int skip = 0; skip < 100; skip += 50) {
                String path = "/catalog/" + type + "/year/genre=" + year + (skip == 0 ? "" : "&skip=" + skip) + ".json";
                String text = diskOnly ? disk(path) : get(path, true);
                if (text == null) { if (diskOnly) return null; continue; }
                any = true;
                for (Title t : parseList(text, type)) if (isNew(t, today)) found.putIfAbsent(t.id, t);
            }
        }
        if (!any) return null;
        List<Title> out = new ArrayList<>(found.values());
        out.sort((a, b) -> b.released.compareTo(a.released));   // ISO dates sort as text
        return out;
    }

    /**
     * New anime: Kitsu's top airing shows (five pages) that started this year (or last year, early in the year), most popular first.
     * Only ones with an IMDb id, so the title page and the sites' search work as for any series. `diskOnly`/null as in fresh().
     */
    static List<Title> newAnime(boolean diskOnly) {
        // Kitsu's skip=20 repeats page one; 40 onwards are the next pages
        return kitsu("kitsu-anime-airing", new int[]{0, 40, 60, 80, 100}, java.time.LocalDate.now().minusDays(120).getYear(), diskOnly);
    }

    /** A Kitsu catalog's shows with an IMDb id, those that started in `from` or later (0 = all), each once, in Kitsu's order. */
    static List<Title> kitsu(String catalog, int[] skips, int from, boolean diskOnly) {
        java.util.Map<String, Title> out = new java.util.LinkedHashMap<>();
        boolean any = false;
        for (int skip : skips) {
            String path = KITSU + "/catalog/anime/" + catalog + (skip == 0 ? "" : "/skip=" + skip) + ".json";
            String text = diskOnly ? disk(path) : get(path, true);
            if (text == null) { if (diskOnly) return null; continue; }
            any = true;
            try {
                JSONArray metas = new JSONObject(text).getJSONArray("metas");
                for (int i = 0; i < metas.length(); i++) {
                    JSONObject o = metas.getJSONObject(i);
                    String imdb = o.optString("imdb_id");
                    if (!imdb.startsWith("tt") || startYear(o.optString("releaseInfo")) < from) continue;
                    Title t = parse(o, o.optString("type").equals("movie") ? "movie" : "series");
                    t.id = imdb;
                    t.anime = true;
                    out.putIfAbsent(t.id, t);
                }
            } catch (JSONException ignored) { }
        }
        return any ? new ArrayList<>(out.values()) : null;
    }

    /** A fresh cached copy of a catalog path, else null (no network). */
    static String disk(String path) {
        File f = file(path);
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > TTL) return null;
        try {
            return Sites.readAll(new java.io.FileInputStream(f));
        } catch (IOException e) {
            return null;
        }
    }

    static boolean isNew(Title t, long today) {
        if (t.released.length() < 10) return false;
        try {
            long age = today - java.time.LocalDate.parse(t.released.substring(0, 10)).toEpochDay();
            return age >= 0 && age <= 200;
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    /** The first year in "2019", "2002-2007" or "2020–", 0 when there is none: for newest-first sorting. */
    static int startYear(String releaseInfo) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{4}").matcher(releaseInfo);
        return m.find() ? Integer.parseInt(m.group()) : 0;
    }

    /** IMDb ids of the anime Kitsu finds for a search, empty when it can't be reached. Call off the main thread. */
    static java.util.Set<String> animeIds(String query) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        try {
            // only labels depend on it: never hold the results up for long
            JSONArray metas = new JSONObject(Sites.readAll(Warp.openPublic("https://anime-kitsu.strem.fun/catalog/anime/kitsu-anime-list/search=" + android.net.Uri.encode(query) + ".json", 5000, 5000))).getJSONArray("metas");
            for (int i = 0; i < metas.length(); i++) {
                String id = metas.getJSONObject(i).optString("imdb_id");
                if (id.startsWith("tt")) ids.add(id);
            }
        } catch (IOException | JSONException ignored) { }
        return ids;
    }

    // ---- local titles: TMDB's lists by original language, through the public Stremio TMDB addon (no key) ----

    static final String TMDB = "https://tmdb.elfhosted.com";
    /** A genre chip that is a language list: LANG + the addon's language name ("lang:Hindi"). */
    static final String LANG = "lang:";
    // ponytail: language stands in for country (Spain also gets Mexican films); a per-country list needs a TMDB key
    static final String[] LOCAL = {"IN=Hindi,Tamil,Telugu,Malayalam,Kannada,Bengali,Marathi,Punjabi", "PK=Urdu,Punjabi", "BD=Bengali", "LK=Sinhalese,Tamil",
        "NP=Nepali,Hindi", "JP=Japanese", "KR=Korean", "CN=Mandarin", "TW=Mandarin", "HK=Mandarin", "SG=Mandarin,Malay,Tamil", "TH=Thai", "VN=Vietnamese",
        "ID=Indonesian", "MY=Malay,Mandarin,Tamil", "PH=Tagalog", "TR=Turkish", "IR=Persian", "IL=Hebrew", "SA=Arabic", "AE=Arabic", "EG=Arabic", "MA=Arabic,French",
        "DZ=Arabic,French", "TN=Arabic,French", "IQ=Arabic,Kurdish", "JO=Arabic", "KW=Arabic", "QA=Arabic", "LB=Arabic", "SY=Arabic", "RU=Russian", "UA=Ukrainian",
        "BY=Belarusian,Russian", "KZ=Kazakh,Russian", "UZ=Uzbek", "GE=Georgian", "AM=Armenian", "PL=Polish", "CZ=Czech", "SK=Slovak", "HU=Hungarian", "RO=Romanian",
        "BG=Bulgarian", "GR=Greek", "RS=Serbian", "HR=Croatian", "SI=Slovenian", "AL=Albanian", "DE=German", "AT=German", "CH=German,French,Italian", "FR=French",
        "BE=French,Dutch", "NL=Dutch", "IT=Italian", "ES=Spanish,Catalan", "MX=Spanish", "AR=Spanish", "CO=Spanish", "CL=Spanish", "PE=Spanish", "VE=Spanish",
        "PT=Portuguese", "BR=Portuguese", "SE=Swedish", "NO=Norwegian", "DK=Danish", "FI=Finnish", "EE=Estonian", "LV=Latvian", "LT=Lithuanian", "ZA=Afrikaans,Zulu",
        "KE=Swahili", "TZ=Swahili", "SO=Somali",
        // English-speaking countries: their row is English-language titles (the lists are by language, so they share one)
        "GB=English", "US=English", "CA=English,French", "AU=English", "NZ=English", "IE=English,Irish"};

    /** The languages a country's own films and series are made in ("IN" -> Hindi, Tamil...); none for a country that is not in the table. */
    static String[] languages(String country) {
        for (String row : LOCAL) if (row.startsWith(country + "=") && !country.isEmpty()) return row.substring(3).split(",");
        return new String[0];
    }

    /** One page of a language's most popular titles. `rest` = "Hindi" or "Hindi&skip=20". Null when it can't be reached. */
    static List<Title> language(String type, String rest, boolean diskOnly) {
        try {
            String[] parts = rest.split("&", 2);
            String path = TMDB + "/catalog/" + type + "/tmdb.language/genre=" + java.net.URLEncoder.encode(parts[0], "UTF-8").replace("+", "%20")
                + (parts.length > 1 ? "&" + parts[1] : "") + ".json";
            String text = diskOnly ? disk(path) : get(path, true);
            if (text == null) return null;
            List<Title> out = parseTmdb(text, type);
            if (out.isEmpty() && !diskOnly) file(path).delete();   // an error page: don't keep it for six hours
            return out;
        } catch (java.io.UnsupportedEncodingException e) {
            return null;
        }
    }

    /** A country's row: the top titles of each of its languages (five at most), taken in turns. Null when none could be read. */
    static List<Title> local(String type, String country, boolean diskOnly) {
        String[] langs = languages(country);
        List<List<Title>> lists = java.util.Arrays.stream(langs).limit(5).parallel()
            .map(l -> language(type, l, diskOnly)).collect(java.util.stream.Collectors.toList());
        java.util.Map<String, Title> out = new java.util.LinkedHashMap<>();
        boolean any = false;
        for (List<Title> l : lists) {
            if (l == null && diskOnly) return null;
            any |= l != null;
        }
        for (int i = 0; i < 20 && out.size() < 30; i++) for (List<Title> l : lists) if (l != null && i < l.size()) out.putIfAbsent(l.get(i).id, l.get(i));
        return any ? new ArrayList<>(out.values()) : null;
    }

    /** The TMDB addon's titles under their IMDb ids (from each one's IMDb link), so title pages, subtitles and site links work as for Cinemeta's. */
    static List<Title> parseTmdb(String json, String type) {
        List<Title> out = new ArrayList<>();
        try {
            JSONArray metas = new JSONObject(json).getJSONArray("metas");
            for (int i = 0; i < metas.length(); i++) {
                JSONObject o = metas.getJSONObject(i);
                JSONArray links = o.optJSONArray("links");
                String imdb = "";
                for (int k = 0; links != null && k < links.length() && imdb.isEmpty(); k++) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("imdb\\.com/title/(tt\\d+)").matcher(links.getJSONObject(k).optString("url"));
                    if (m.find()) imdb = m.group(1);
                }
                if (imdb.isEmpty() || o.optString("name").isEmpty()) continue;
                Title t = parse(o, type);
                t.tmdb = o.optString("id").replaceFirst("^tmdb:", "");
                t.id = imdb;
                t.poster = t.poster.replace("/t/p/w342/", "/t/p/w500/");   // sharp on a TV card
                out.add(t);
            }
        } catch (JSONException ignored) { }
        return out;
    }

    /** A wide picture for a title: its own, else Cinemeta's by IMDb id ("" when there is no id to ask by). */
    static String backdrop(Title t) {
        return !t.background.isEmpty() ? t.background : t.id.startsWith("tt") ? "https://images.metahub.space/background/medium/" + t.id + "/img" : "";
    }

    /** The list if a fresh copy is already on disk (no network), else null: lets a row draw without waiting. */
    static List<Title> cached(String type, String extra) {
        String key = type + "|" + extra;
        Object[] hit = memo.get(key);
        if (hit != null && System.currentTimeMillis() - (Long) hit[0] < 10 * 60000) { @SuppressWarnings("unchecked") List<Title> l = (List<Title>) hit[1]; return l; }
        List<Title> list = fromDisk(type, extra);
        if (list != null && !list.isEmpty()) memo.put(key, new Object[]{System.currentTimeMillis(), list});
        return list;
    }

    /** Everything cached counts as old from now: the next read asks the network (and still falls back to the old copy offline). */
    static void expire() {
        memo.clear();
        File[] all = new File(App.app.getFilesDir(), "catalog").listFiles();
        if (all != null) for (File f : all) f.setLastModified(1000);
    }

    // Lists already read and parsed, for ten minutes: a tab switch does not read and parse its files again
    static final java.util.Map<String, Object[]> memo = new java.util.concurrent.ConcurrentHashMap<>();

    static List<Title> fromDisk(String type, String extra) {
        if (extra.startsWith("local=")) return local(type, extra.substring(6), true);
        if (extra.startsWith("genre=" + LANG)) return language(type, extra.substring(6 + LANG.length()), true);
        if (extra.equals(NEW_ANIME)) return newAnime(true);
        if (extra.equals(TRENDING_ANIME)) return kitsu("kitsu-anime-trending", new int[]{0}, 0, true);
        if (extra.startsWith("genre=" + POPULAR_ANIME)) return popularAnime(extra, true);
        if (extra.startsWith("genre=" + NEW)) return extra.contains("skip=") ? new ArrayList<>() : fresh(type, true);
        File f = file(path(type, extra));
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > TTL) return null;
        try {
            return parseList(Sites.readAll(new java.io.FileInputStream(f)), type);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * An anime's ids on AniList, MyAnimeList, Kitsu and AniDB for one season, with the episode numbered the way those sites count it:
     * they have one entry per season, or one entry for the whole run (One Piece), counted from its first episode.
     * From arm.haglund.dev (by IMDb id); empty when unknown. Call off the main thread.
     */
    /** Whether an IMDb title is an anime (the anime databases know it): for an animated title that came from Cinemeta. Off the main thread. */
    static boolean isAnime(String imdb) {
        String text = imdb != null && imdb.startsWith("tt") ? get("https://arm.haglund.dev/api/v2/imdb?id=" + imdb, true) : null;
        return text != null && text.trim().startsWith("[{");
    }

    static java.util.Map<String, String> animeEntry(Title t, int season, int episode) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        String text = t.id.startsWith("tt") ? get("https://arm.haglund.dev/api/v2/imdb?id=" + t.id, true) : null;
        if (text == null) return out;
        try {
            JSONArray all = new JSONArray(text);
            List<JSONObject> tv = new ArrayList<>();
            for (int i = 0; i < all.length(); i++) if (!"OVA".equals(all.getJSONObject(i).optString("media")) && !"SPECIAL".equals(all.getJSONObject(i).optString("media"))) tv.add(all.getJSONObject(i));
            JSONObject pick = null;
            // ponytail: a season aired in two parts (Attack on Titan S3) gets the first part's id; placing part 2 needs AniList's episode counts
            for (JSONObject o : tv) if (o.optInt("thetvdb-season", -1) == season) { pick = o; break; }
            boolean whole = pick == null && tv.size() == 1;
            if (whole) pick = tv.get(0);
            if (pick == null) return out;
            String[][] keys = {{"anilist", "anilist"}, {"myanimelist", "mal"}, {"kitsu", "kitsu"}, {"anidb", "anidb"}};
            for (String[] k : keys) if (!pick.isNull(k[0]) && pick.has(k[0])) out.put(k[1], String.valueOf(pick.get(k[0])));
            int n = 0;
            if (whole) for (Episode e : t.episodes) if (e.season < season || (e.season == season && e.number <= episode)) n++;
            out.put("episode", String.valueOf(n > 0 ? n : episode));
        } catch (JSONException ignored) { }
        return out;
    }

    /** Fills in the overview, runtime, backdrop and episodes of a title from its detail page. */
    static void fill(Title t) {
        String text = get("/meta/" + t.type + "/" + t.id + ".json", true);
        if (text == null) return;
        try {
            Title full = parse(new JSONObject(text).getJSONObject("meta"), t.type);
            t.overview = full.overview;
            t.runtime = full.runtime;
            t.background = full.background;
            t.genres = full.genres;
            t.cast = full.cast;
            t.director = full.director;
            t.writer = full.writer;
            t.country = full.country;
            t.awards = full.awards;
            t.status = full.status;
            t.trailer = full.trailer;
            if (!full.tmdb.isEmpty()) t.tmdb = full.tmdb;
            if (!full.year.isEmpty()) t.year = full.year;
            if (!full.rating.isEmpty()) t.rating = full.rating;
            t.episodes.clear();
            t.episodes.addAll(full.episodes);
            if (!t.anime && t.genres.contains("Animation") && isAnime(t.id)) t.anime = true;   // also a title known only by its page's name (Continue watching)
        } catch (JSONException ignored) { }
    }

    /** The first `max` names of a list, comma separated ("" when there is none). */
    static String joined(JSONArray a, int max) {
        List<String> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length() && out.size() < max; i++) if (!a.optString(i).isEmpty()) out.add(a.optString(i));
        return String.join(", ", out);
    }

    static List<Title> parseList(String json, String type) {
        List<Title> out = new ArrayList<>();
        try {
            JSONArray metas = new JSONObject(json).getJSONArray("metas");
            for (int i = 0; i < metas.length(); i++) {
                Title t = parse(metas.getJSONObject(i), type);
                if (!t.id.isEmpty() && !t.name.isEmpty()) out.add(t);
            }
        } catch (JSONException ignored) { }
        return out;
    }

    static Title parse(JSONObject o, String type) throws JSONException {
        Title t = new Title();
        t.type = type;
        t.id = o.optString("id");
        t.tmdb = o.optInt("moviedb_id") > 0 ? String.valueOf(o.optInt("moviedb_id")) : "";
        t.name = o.optString("name");
        t.year = o.optString("releaseInfo", o.optString("year"));
        t.released = o.optString("released");
        t.rating = o.optString("imdbRating");
        // "small" posters are blurry on a TV card
        t.poster = o.optString("poster").replace("/poster/small/", "/poster/medium/");
        t.background = o.optString("background");
        t.overview = o.optString("description");
        t.runtime = o.optString("runtime");
        JSONArray g = o.optJSONArray("genres");
        if (g == null) g = o.optJSONArray("genre");
        java.util.Set<String> names = new java.util.LinkedHashSet<>();   // TMDB can name one twice
        for (int i = 0; g != null && i < g.length(); i++) names.add(g.getString(i));
        t.genres = String.join(" · ", names);
        t.cast = joined(o.optJSONArray("cast"), 6);
        t.director = joined(o.optJSONArray("director"), 3);
        t.writer = joined(o.optJSONArray("writer"), 3);
        t.country = o.optString("country");
        t.awards = o.optString("awards");
        t.status = o.optString("status");
        JSONArray trailers = o.optJSONArray("trailers");
        if (trailers != null && trailers.length() > 0) t.trailer = trailers.getJSONObject(0).optString("source");
        JSONArray videos = o.optJSONArray("videos");
        for (int i = 0; videos != null && i < videos.length(); i++) {
            JSONObject v = videos.getJSONObject(i);
            Episode e = new Episode();
            e.season = v.optInt("season");
            e.number = v.optInt("episode", v.optInt("number"));
            // Season 0 is "Specials" and entries without a number are placeholders
            if (e.season < 1 || e.number < 1) continue;
            e.title = v.optString("name", v.optString("title"));
            e.overview = v.optString("overview", v.optString("description"));
            e.thumb = v.optString("thumbnail");
            t.episodes.add(e);
        }
        return t;
    }

    static int seasons(Title t) {
        int n = 0;
        for (Episode e : t.episodes) n = Math.max(n, e.season);
        return n;
    }

    // ---- disk cache: a stale copy is used when the network fails, so the home screen still has posters offline ----

    static File file(String path) {
        try {
            byte[] sha = MessageDigest.getInstance("SHA-1").digest(path.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder();
            for (byte x : sha) name.append(String.format("%02x", x));
            File dir = new File(App.app.getFilesDir(), "catalog");
            dir.mkdirs();
            return new File(dir, name.toString());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String get(String path, boolean keep) {
        File f = file(path);
        try {
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < TTL) return Sites.readAll(new java.io.FileInputStream(f));
            String text = Sites.readAll(Warp.openPublic(path.startsWith("http") ? path : BASE + path, 6000, 20000));   // Kitsu passes a full URL
            if (keep) try (FileOutputStream out = new FileOutputStream(f)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
            return text;
        } catch (IOException e) {
            try {
                return f.exists() ? Sites.readAll(new java.io.FileInputStream(f)) : null;
            } catch (IOException again) {
                return null;
            }
        }
    }

    // ---- My list ----

    static List<Title> mine() {
        List<Title> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(App.prefs.getString("mylist", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Title t = new Title();
                t.id = o.getString("id");
                t.type = o.optString("type", "movie");
                t.name = o.optString("name");
                t.year = o.optString("year");
                t.poster = o.optString("poster");
                out.add(t);
            }
        } catch (JSONException ignored) { }
        return out;
    }

    static boolean isMine(String id) {
        for (Title t : mine()) if (t.id.equals(id)) return true;
        return false;
    }

    /** Adds (newest first) or removes; true if the title is now in the list. */
    static boolean toggleMine(Title t) {
        try {
            JSONArray old = new JSONArray(App.prefs.getString("mylist", "[]")), now = new JSONArray();
            boolean had = false;
            now.put(new JSONObject().put("id", t.id).put("type", t.type).put("name", t.name).put("year", t.year).put("poster", t.poster));
            for (int i = 0; i < old.length(); i++) {
                if (old.getJSONObject(i).getString("id").equals(t.id)) had = true;
                else now.put(old.get(i));
            }
            if (had) {
                now = new JSONArray();
                for (int i = 0; i < old.length(); i++) if (!old.getJSONObject(i).getString("id").equals(t.id)) now.put(old.get(i));
            }
            App.prefs.edit().putString("mylist", now.toString()).apply();
            return !had;
        } catch (JSONException e) {
            return false;
        }
    }
}
