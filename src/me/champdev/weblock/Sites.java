package me.champdev.weblock;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The streaming sites of the "sites" sources the user added (Settings, Sources), merged, plus the user's own sites. */
final class Sites {
    static final int ROW_MAX = 14;

    static final class Site {
        String name, url, tags = "", group = "";   // group = the section of its list it came from
        List<String> tagList = new ArrayList<>();
        final List<String> mirrors = new ArrayList<>();
        boolean pick, index;
    }

    private static org.json.JSONObject deeplinks;

    /** Title links: host -> {movie, tv, episode, anime} URL templates, merged from every "deeplinks" source (the first added wins a host). */
    private static synchronized org.json.JSONObject deeplinks() {
        if (deeplinks == null) {
            deeplinks = new org.json.JSONObject();
            for (Sources.Source src : Sources.added(Sources.DEEPLINKS)) {
                try {
                    org.json.JSONObject o = new org.json.JSONObject(Sources.read(Sources.data(src)));
                    for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                        String host = it.next();
                        if (!deeplinks.has(host)) deeplinks.put(host, o.get(host));
                    }
                } catch (JSONException ignored) { }
            }
        }
        return deeplinks;
    }

    /** Sites that check fetch and friends as they start, so frame.js hooks them late: the table's "@late" list of host names without the ending. */
    static String lateHosts() {
        org.json.JSONArray out = new org.json.JSONArray(), late = deeplinks().optJSONArray("@late");
        for (int i = 0; late != null && i < late.length(); i++) if (late.optString(i).matches("[a-z0-9-]+")) out.put(late.optString(i));
        return out.toString();
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

    /** Row -> sites and language -> sites, in display order. Parsed once per file version: every tab asks for these. */
    static Map<String, List<Site>> loaded, langs;
    static long loadedAt = -1;

    static long stamp() {
        long n = 0;
        for (Sources.Source src : Sources.added(Sources.SITES)) {
            File f = Sources.data(src);
            n = n * 31 + f.lastModified() + f.length() + src.url.hashCode();
        }
        return n;
    }

    static synchronized void ensure() {
        long now = stamp();
        if (loaded != null && now == loadedAt) return;
        loaded = new LinkedHashMap<>();
        langs = new LinkedHashMap<>();
        for (String c : Cats.ORDER) loaded.put(c, new ArrayList<>());
        for (Sources.Source src : Sources.added(Sources.SITES)) parse(Sources.read(Sources.data(src)), loaded, langs);
        loaded.values().removeIf(List::isEmpty);
        langs.values().removeIf(List::isEmpty);
        loadedAt = now;
    }

    /** Row title (Cats) -> sites. Empty until a sites source is added and downloaded. */
    static synchronized Map<String, List<Site>> load() {
        ensure();
        return loaded;
    }

    /** Language -> its streaming sites. */
    static synchronized Map<String, List<Site>> languages() {
        ensure();
        return langs;
    }

    /** One sites.json ({"rows": {"movies": [...], ...}, "languages": {"Spanish": [...]}}) merged in; an address already there keeps its place. */
    static void parse(String json, Map<String, List<Site>> rows, Map<String, List<Site>> langs) {
        try {
            JSONObject o = new JSONObject(json);
            JSONObject r = o.optJSONObject("rows");
            for (int i = 0; r != null && i < Cats.ORDER.length; i++) merge(r.optJSONArray(Cats.KEYS[i]), rows.get(Cats.ORDER[i]));
            JSONObject l = o.optJSONObject("languages");
            for (java.util.Iterator<String> it = l == null ? null : l.keys(); it != null && it.hasNext(); ) {
                String lang = it.next();
                if (!langs.containsKey(lang)) langs.put(lang, new ArrayList<>());
                merge(l.optJSONArray(lang), langs.get(lang));
            }
        } catch (JSONException ignored) { }
    }

    static void merge(JSONArray a, List<Site> into) {
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Site s = new Site();
            s.name = o.optString("name").trim();
            s.url = o.optString("url").trim();
            if (s.name.isEmpty() || !s.url.matches("(?i)https?://.+") || has(into, s.url)) continue;
            JSONArray m = o.optJSONArray("mirrors"), t = o.optJSONArray("tags");
            for (int j = 0; m != null && j < m.length(); j++) if (m.optString(j).matches("(?i)https?://.+")) s.mirrors.add(m.optString(j));
            for (int j = 0; t != null && j < t.length(); j++) if (!t.optString(j).isEmpty()) s.tagList.add(t.optString(j));
            s.tags = String.join(" · ", s.tagList);
            s.group = o.optString("group");
            s.pick = o.optBoolean("pick");
            s.index = o.optBoolean("index");
            into.add(s);
        }
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

    /** Adds or removes; name and tags are kept so a favorite survives its list dropping the site. */
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
