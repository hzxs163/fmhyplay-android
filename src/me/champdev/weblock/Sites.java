package me.champdev.weblock;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** FMHY's starred streaming sites (from its video page) plus the user's own sites. */
final class Sites {
    static final String SOURCE = "https://raw.githubusercontent.com/fmhy/edit/main/docs/video.md";
    static final String SOURCE_LANG = "https://raw.githubusercontent.com/fmhy/edit/main/docs/non-english.md";
    static final long REFRESH = 12L * 3600 * 1000;
    // "* ⭐ **[Name](url)**, [2](url) - tags" (a pick), "* 🌟 ..." (also a pick) or plain "* [Name](url) - tags"
    static final Pattern ENTRY = Pattern.compile("^\\*\\s*(⭐|🌟|🌐)?\\s*(?:\\*\\*)?\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)(?:\\*\\*)?(.*)$");
    static final Pattern ANY_LINK = Pattern.compile("\\[[^\\]]*\\]\\((https?://[^)\\s]+)\\)");
    static final Pattern LINK = Pattern.compile("\\[[^\\]]*\\]\\([^)]*\\)");
    // FMHY's own pages, wikis and GitHub/Telegram/Discord links are references, not sites to watch on
    static final Pattern INTERNAL = Pattern.compile("(?i)^https?://(?:www\\.)?(?:reddit\\.com/r/freemediaheckyeah|fmhy\\.|rentry\\.(?:co|org)/fmhy|github\\.com|raw\\.githubusercontent\\.com|gist\\.github\\.com|t\\.me/|discord\\.gg)");
    static final int ROW_MAX = 14;

    static final class Site {
        String name, url, tags = "", group = "";   // group = the FMHY section it came from
        List<String> tagList = new ArrayList<>();
        final List<String> mirrors = new ArrayList<>();
        boolean pick, index;
    }

    private static org.json.JSONObject deeplinks;

    /** deeplinks.json: host -> {movie, tv} URL templates with {tmdb} / {imdb}. Not part of the app: it comes with the source list ("@deeplinks|url"). */
    private static synchronized org.json.JSONObject deeplinks() {
        if (deeplinks == null) {
            try {
                deeplinks = new org.json.JSONObject(readAll(new FileInputStream(new File(App.app.getFilesDir(), "deeplinks.json"))));
            } catch (Exception e) {
                deeplinks = new org.json.JSONObject();
            }
        }
        return deeplinks;
    }

    /** Re-read the table after a refresh brought a new one. */
    static synchronized void reloadDeeplinks() {
        deeplinks = null;
    }

    /** The title's own page on a site we know the address pattern of (by TMDB or IMDb id); null means search for the name instead. */
    static String deeplink(String siteUrl, Catalog.Title t) {
        Map<String, String> v = new java.util.HashMap<>();
        v.put("tmdb", t.tmdb);
        v.put("imdb", t.id.startsWith("tt") ? t.id : "");
        return link(siteUrl, t.series() ? "tv" : "movie", v);
    }

    /** Whether the site has an address of this kind ("episode" for one TV episode, "anime" for one anime episode). */
    static boolean hasLink(String siteUrl, String key) {
        org.json.JSONObject site = deeplinks().optJSONObject(hostOf(siteUrl));
        return site != null && !site.optString(key).isEmpty();
    }

    /** Whether the site's anime address says Sub or Dub. */
    static boolean hasAudioLink(String siteUrl) {
        org.json.JSONObject site = deeplinks().optJSONObject(hostOf(siteUrl));
        return site != null && site.optString("anime").contains("{audio}");
    }

    static String hostOf(String url) {
        return String.valueOf(Uri.parse(url).getHost()).replaceFirst("^www\\.", "");
    }

    /**
     * A site's address of one kind (movie, tv, episode, anime) with its {placeholders} filled from vars: tmdb, imdb, season, episode,
     * anilist, mal, kitsu, anidb, audio (sub or dub; the site's "audio" map turns it into what its address says). Null when the site
     * has no such address or a value it needs is missing.
     */
    static String link(String siteUrl, String key, Map<String, String> vars) {
        Uri u = Uri.parse(siteUrl);
        org.json.JSONObject site = deeplinks().optJSONObject(hostOf(siteUrl));
        String tpl = site == null ? "" : site.optString(key);
        if (tpl.isEmpty()) return null;
        org.json.JSONObject audio = site.optJSONObject("audio");
        java.util.regex.Matcher m = Pattern.compile("\\{(\\w+)\\}").matcher(tpl);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String v = vars.get(m.group(1));
            if (m.group(1).equals("audio") && v != null && audio != null) v = audio.optString(v, v);
            if (v == null || v.isEmpty()) return null;
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(Uri.encode(v)));
        }
        m.appendTail(out);
        String path = out.toString();
        return path.startsWith("http") ? path : u.getScheme() + "://" + u.getHost() + path;
    }

    /** Category title -> sites, in display order. */
    // Parsed once per file version: every tab asks for these, and parsing 100 KB of markdown each time made tab switches lag
    static Map<String, List<Site>> loaded, langs;
    static long loadedAt, langsAt;

    static long stamp(String name) {
        File f = new File(App.app.getFilesDir(), name);
        return f.lastModified() + f.length();
    }

    static synchronized Map<String, List<Site>> load() {
        long now = stamp(VIDEO_FILE);
        if (loaded == null || now != loadedAt) { loaded = parseSites(); loadedAt = now; }
        return loaded;
    }

    static Map<String, List<Site>> parseSites() {
        Map<String, List<Site>> out = new LinkedHashMap<>();
        for (String c : Cats.ORDER) out.put(c, new ArrayList<>());
        String section = "";
        for (String line : markdown().split("\n")) {
            if (line.startsWith("#")) {
                section = line.replaceAll("^[#\\s►▷]+", "").trim();
                continue;
            }
            Site s = entry(line, section);
            if (s == null) continue;
            for (String cat : Cats.of(section, s.tagList)) if (!has(out.get(cat), s.url)) out.get(cat).add(s);
        }
        for (List<Site> list : out.values()) {
            // Picks first, then the rest, then FMHY's lists of more sites; FMHY's own order inside each group
            List<Site> picks = new ArrayList<>(), rest = new ArrayList<>(), indexes = new ArrayList<>();
            for (Site s : list) (s.pick ? picks : s.index ? indexes : rest).add(s);
            list.clear();
            list.addAll(picks);
            list.addAll(rest);
            list.addAll(indexes);
        }
        out.values().removeIf(List::isEmpty);
        return out;
    }

    /** One FMHY list line -> a Site, or null if it isn't a site entry. */
    static Site entry(String line, String group) {
        Matcher m = ENTRY.matcher(line.trim());
        if (!m.find() || INTERNAL.matcher(m.group(3)).find()) return null;
        Site s = new Site();
        s.index = "🌐".equals(m.group(1));
        s.pick = m.group(1) != null && !s.index;
        s.name = m.group(2).replaceAll("[\\u2060\\u200b]", "").trim();
        s.url = m.group(3);
        // Everything before " - " is the site plus its mirrors; after it come tags and Discord/GitHub links
        String rest = m.group(4);
        int dash = rest.indexOf(" - ");
        String head = dash >= 0 ? rest.substring(0, dash) : rest, tail = dash >= 0 ? rest.substring(dash + 3) : "";
        Matcher mm = ANY_LINK.matcher(head);
        while (mm.find()) if (!INTERNAL.matcher(mm.group(1)).find() && !mm.group(1).equals(s.url)) s.mirrors.add(mm.group(1));
        for (String t : LINK.matcher(tail).replaceAll("").split("/")) {
            String c = t.replaceAll("[*_`]", "").trim();
            if (!c.isEmpty() && !c.equals(",") && !c.equals("↪️")) s.tagList.add(c);
        }
        s.tags = String.join(" · ", s.tagList);
        s.group = group;
        return s;
    }

    /** Language -> its streaming sites, from FMHY's non-English page ("# ► Spanish / Español" > "## ▷ Streaming"). */
    static synchronized Map<String, List<Site>> languages() {
        long now = stamp(LANG_FILE);
        if (langs == null || now != langsAt) { langs = parseLanguages(); langsAt = now; }
        return langs;
    }

    static Map<String, List<Site>> parseLanguages() {
        Map<String, List<Site>> out = new LinkedHashMap<>();
        String lang = null;
        boolean streaming = false;
        for (String line : read(LANG_FILE, "fmhy-nonenglish.md").split("\n")) {
            if (line.startsWith("# ")) {
                lang = line.replaceAll("^[#\\s►▷]+", "").split(" / ")[0].trim();
                streaming = false;
            } else if (line.startsWith("#")) {
                streaming = line.replaceAll("^[#\\s►▷]+", "").trim().toLowerCase().startsWith("streaming");
            } else if (lang != null && streaming) {
                Site s = entry(line, lang);
                if (s == null) continue;
                if (!out.containsKey(lang)) out.put(lang, new ArrayList<>());
                if (!has(out.get(lang), s.url)) out.get(lang).add(s);
            }
        }
        for (List<Site> list : out.values()) {
            List<Site> picks = new ArrayList<>(), rest = new ArrayList<>();
            for (Site s : list) (s.pick ? picks : rest).add(s);
            list.clear();
            list.addAll(picks);
            list.addAll(rest);
        }
        return out;
    }

    /** Every site we know (all rows and languages), once each, for search. */
    static List<Site> all() {
        List<Site> out = new ArrayList<>();
        for (List<Site> list : load().values()) for (Site s : list) if (!has(out, s.url)) out.add(s);
        for (List<Site> list : languages().values()) for (Site s : list) if (!has(out, s.url)) out.add(s);
        for (Site s : favorites()) if (!has(out, s.url)) out.add(s);
        for (Site s : custom()) if (!has(out, s.url)) out.add(s);
        return out;
    }

    static boolean has(List<Site> list, String url) {
        for (Site s : list) if (s.url.equals(url)) return true;
        return false;
    }

    static final String VIDEO_FILE = "fmhy-video.md", LANG_FILE = "fmhy-nonenglish.md";

    static File cache() {
        return new File(App.app.getFilesDir(), VIDEO_FILE);
    }

    static String markdown() {
        return read(VIDEO_FILE, VIDEO_FILE);
    }

    /** The downloaded copy if there is one, else the one bundled in the app. */
    static String read(String cacheName, String asset) {
        File f = new File(App.app.getFilesDir(), cacheName);
        try (InputStream in = f.exists() ? new FileInputStream(f) : App.app.getAssets().open(asset)) {
            return readAll(in);
        } catch (IOException e) {
            return "";
        }
    }

    static boolean stale() {
        return System.currentTimeMillis() - cache().lastModified() > REFRESH;
    }

    /** Downloads the latest FMHY pages; true if either changed. Call off the main thread. */
    static boolean refresh() {
        boolean video = fetch(SOURCE, VIDEO_FILE, "Streaming Sites");
        boolean lang = fetch(SOURCE_LANG, LANG_FILE, "► ");
        return video || lang;
    }

    static boolean fetch(String url, String cacheName, String mustContain) {
        try {
            String md = readAll(Warp.open(url).getInputStream());
            if (!md.contains(mustContain)) return false;
            boolean changed = !md.equals(read(cacheName, cacheName));
            try (FileOutputStream out = new FileOutputStream(new File(App.app.getFilesDir(), cacheName))) {
                out.write(md.getBytes(StandardCharsets.UTF_8));
            }
            return changed;
        } catch (IOException e) {
            return false;
        }
    }

    static String readAll(InputStream in) throws IOException {
        try (InputStream src = in) {   // closed also when the read fails half way
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            for (int n; (n = src.read(b)) > 0; ) buf.write(b, 0, n);
            return buf.toString("UTF-8");
        }
    }

    /** The list pasted in Settings, one site per line: "name|url" or just the url. */
    static List<Site> custom() {
        List<Site> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : App.prefs.getString("mySites", "").split("\n")) {
            line = line.trim().replace('｜', '|');
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            int bar = line.indexOf('|');
            String name = bar < 0 ? "" : line.substring(0, bar).trim();
            String url = (bar < 0 ? line : line.substring(bar + 1)).trim();
            if (!url.matches("(?i)https?://.*")) {
                if (url.matches("(?i)^[a-z]+://.*")) continue;   // another scheme: not a web site
                url = "https://" + url;
            }
            if (url.contains(" ") || Uri.parse(url).getHost() == null || !seen.add(url)) continue;
            Site s = new Site();
            s.url = url;
            s.name = name.isEmpty() ? hostOf(url) : name;
            out.add(s);
        }
        return out;
    }

    /** A site the user typed in; it lands in Favorites. */
    static void addCustom(String raw) {
        Site s = new Site();
        s.url = raw.trim().matches("(?i)https?://.*") ? raw.trim() : "https://" + raw.trim();
        s.name = String.valueOf(Uri.parse(s.url).getHost()).replaceFirst("^www\\.", "");
        if (!isFavorite(s.url)) toggleFavorite(s);
    }

    static List<Site> favorites() {
        List<Site> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(App.prefs.getString("favs", "[]"));
            for (int i = 0; i < a.length(); i++) {
                Site s = new Site();
                s.name = a.getJSONObject(i).getString("name");
                s.url = a.getJSONObject(i).getString("url");
                s.tags = a.getJSONObject(i).optString("tags");
                out.add(s);
            }
        } catch (JSONException ignored) { }
        return out;
    }

    static void clearFavorites() {
        App.prefs.edit().remove("favs").apply();
    }

    static boolean isFavorite(String url) {
        for (Site s : favorites()) if (s.url.equals(url)) return true;
        return false;
    }

    /** Adds or removes; name and tags are kept so a favorite survives FMHY dropping the site. */
    static void toggleFavorite(Site site) {
        try {
            JSONArray a = new JSONArray(App.prefs.getString("favs", "[]")), kept = new JSONArray();
            boolean removed = false;
            for (int i = 0; i < a.length(); i++) {
                if (a.getJSONObject(i).getString("url").equals(site.url)) removed = true;
                else kept.put(a.get(i));
            }
            if (!removed) kept.put(new JSONObject().put("name", site.name).put("url", site.url).put("tags", site.tags));
            App.prefs.edit().putString("favs", kept.toString()).apply();
        } catch (JSONException ignored) { }
    }
}
