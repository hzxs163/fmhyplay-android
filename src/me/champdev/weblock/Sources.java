package me.champdev.weblock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Where sites, channels and title links come from. The app carries none: an index (a JSON list; ours is offered by default)
 * names sources, and only the ones added in Settings, Sources are downloaded, cached in filesDir and read.
 */
final class Sources {
    /** The one address built into the app, listed in Settings, Sources and added only when the user asks. */
    static final String INDEX = "https://raw.githubusercontent.com/AnantPlay/index/main/index.json";
    static final String SITES = "sites", PLAYLIST = "playlist", DEEPLINKS = "deeplinks";
    static final long REFRESH = 12L * 3600 * 1000, LINKS_REFRESH = 3600_000L;

    static final class Source {
        String id = "", name = "", description = "", type = "", url = "", languages = "", index = "";
        boolean adult, isDefault;

        /** A type this version can read; others are listed greyed out. */
        boolean known() {
            return type.equals(SITES) || type.equals(PLAYLIST) || type.equals(DEEPLINKS);
        }

        JSONObject json() throws JSONException {
            return new JSONObject().put("id", id).put("name", name).put("description", description).put("type", type).put("url", url)
                .put("languages", languages).put("index", index).put("adult", adult).put("default", isDefault);
        }

        static Source of(JSONObject o, String index) {
            Source s = new Source();
            s.url = o.optString("url").trim();
            s.id = o.optString("id", s.url);
            s.name = o.optString("name");
            s.description = o.optString("description");
            s.type = o.optString("type");
            s.languages = o.optString("languages").trim();
            s.adult = o.optBoolean("adult");
            s.isDefault = o.optBoolean("default");
            s.index = index != null ? index : o.optString("index");
            return s;
        }
    }

    /** An index: JSON ({"sources": [...]}) or the older text list ("label|url", "label|url|18", "@deeplinks|url"; # = note). */
    static List<Source> parseIndex(String text, String indexUrl) {
        List<Source> out = new ArrayList<>();
        String t = text.trim();
        if (t.startsWith("{")) {
            try {
                JSONArray a = new JSONObject(t).getJSONArray("sources");
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    Source s = o == null ? null : Source.of(o, indexUrl);
                    if (s != null && s.url.matches("(?i)https?://.+") && !contains(out, s.url)) out.add(s);
                }
            } catch (JSONException ignored) { }
            return out;
        }
        for (String line : t.split("\n")) {
            String[] f = line.trim().split("\\|", 3);
            if (line.trim().startsWith("#") || f.length < 2 || !f[1].trim().matches("(?i)https?://.+") || contains(out, f[1].trim())) continue;
            Source s = new Source();
            s.url = s.id = f[1].trim();
            s.index = indexUrl;
            s.isDefault = true;
            if (f[0].trim().equals("@deeplinks")) s.type = DEEPLINKS;   // named on screen (App.t), this stays pure for the tests
            else if (f[0].trim().startsWith("@")) continue;
            else {
                s.type = PLAYLIST;
                s.name = f[0].trim();
                s.adult = f.length > 2 && f[2].trim().equals("18");
            }
            out.add(s);
        }
        return out;
    }

    static boolean contains(List<Source> list, String url) {
        for (Source s : list) if (s.url.equals(url)) return true;
        return false;
    }

    // ---- what the user added (prefs "indexes" and "sources") ----

    static List<String> indexes() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(App.prefs.getString("indexes", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (JSONException ignored) { }
        return out;
    }

    static void addIndex(String url) {
        List<String> all = indexes();
        if (!all.contains(url)) all.add(url);
        App.prefs.edit().putString("indexes", new JSONArray(all).toString()).apply();
    }

    /** Removes the index and every source that was added from it. */
    static void removeIndex(String url) {
        List<String> all = indexes();
        all.remove(url);
        App.prefs.edit().putString("indexes", new JSONArray(all).toString()).apply();
        for (Source s : added()) if (s.index.equals(url)) remove(s.url);
        indexFile(url).delete();
    }

    static List<Source> added() {
        List<Source> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(App.prefs.getString("sources", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Source.of(a.getJSONObject(i), null));
        } catch (JSONException ignored) { }
        return out;
    }

    static List<Source> added(String type) {
        List<Source> out = new ArrayList<>();
        for (Source s : added()) if (s.type.equals(type)) out.add(s);
        return out;
    }

    static boolean isAdded(String url) {
        return contains(added(), url);
    }

    static void add(Source s) {
        List<Source> all = added();
        if (!contains(all, s.url)) all.add(s);
        save(all);
    }

    static void remove(String url) {
        List<Source> all = added(), kept = new ArrayList<>();
        for (Source s : all) {
            if (!s.url.equals(url)) kept.add(s);
            else { data(s).delete(); if (!s.languages.isEmpty()) langData(s).delete(); }
        }
        save(kept);
        changed();
    }

    static void save(List<Source> all) {
        JSONArray a = new JSONArray();
        try { for (Source s : all) a.put(s.json()); } catch (JSONException ignored) { }
        App.prefs.edit().putString("sources", a.toString()).apply();
    }

    /** What the index offers (its last downloaded copy). */
    static List<Source> listed(String indexUrl) {
        return parseIndex(read(indexFile(indexUrl)), indexUrl);
    }

    /** Is there anything 18+ on offer? Without it Settings has no 18+ switch. */
    static boolean adultListed() {
        for (Source s : added()) if (s.adult) return true;
        for (String i : indexes()) for (Source s : listed(i)) if (s.adult) return true;
        return false;
    }

    // ---- files ----

    static File file(String url, String ext) {
        return new File(App.app.getFilesDir(), "src-" + Integer.toHexString(url.hashCode()) + ext);
    }

    static File indexFile(String url) {
        return file(url, ".index");
    }

    static File data(Source s) {
        return file(s.url, s.type.equals(PLAYLIST) ? ".m3u" : ".json");
    }

    static File langData(Source s) {
        return file(s.languages, ".m3u");
    }

    static String read(File f) {
        try {
            return f.exists() ? Sites.readAll(new FileInputStream(f)) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** Downloads into f when the answer looks like the type it should be (a site's error page never replaces a good copy); true if it changed. */
    static boolean fetch(String url, File f, String type) {
        try {
            String text = Iptv.download(url), t = text.trim();
            boolean ok = type.equals(PLAYLIST) ? t.startsWith("#EXTM3U") || t.startsWith("#EXTINF") : type.equals("index") ? t.startsWith("{") || t.contains("|http") : t.startsWith("{");
            if (!ok || text.equals(read(f))) { if (ok) f.setLastModified(System.currentTimeMillis()); return false; }
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
            return tmp.renameTo(f);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Downloads an index; true when it could be read. Sources added from it follow it (a list that moved, a new name),
     * so an index can relocate its lists without anyone adding them again.
     */
    static boolean fetchIndex(String url) {
        fetch(url, indexFile(url), "index");
        List<Source> listed = listed(url);
        if (listed.isEmpty()) return false;
        List<Source> all = added();
        boolean moved = false;
        for (int i = 0; i < all.size(); i++) {
            Source s = all.get(i), l = null;
            if (!s.index.equals(url)) continue;
            for (Source x : listed) if (x.id.equals(s.id)) l = x;
            if (l == null || !l.url.equals(s.url) && contains(all, l.url)) continue;
            if (!l.url.equals(s.url)) { data(s).delete(); moved = true; }
            all.set(i, l);
        }
        save(all);
        if (moved) changed();
        return true;
    }

    static boolean fetch(Source s) {
        boolean c = fetch(s.url, data(s), s.type);
        if (!s.languages.isEmpty()) c |= fetch(s.languages, langData(s), PLAYLIST);
        return c;
    }

    static boolean old(File f, long age) {
        return !f.exists() || System.currentTimeMillis() - f.lastModified() > age;
    }

    /** Something added was never downloaded, or is older than 12 hours. */
    static boolean stale() {
        for (String i : indexes()) if (old(indexFile(i), REFRESH)) return true;
        for (Source s : added()) if (old(data(s), REFRESH)) return true;
        return false;
    }

    /** Downloads the indexes and the added sources (only one type when given); true if any of them changed. Off the main thread. */
    static boolean refresh(String type) {
        boolean c = false;
        if (type == null) for (String i : indexes()) fetchIndex(i);
        for (Source s : added()) if (type == null || s.type.equals(type)) c |= fetch(s);
        if (c) changed();
        return c;
    }

    /** Title links change often: at app start when older than an hour. Off the main thread. */
    static void refreshLinks() {
        boolean c = false;
        for (Source s : added(DEEPLINKS)) if (old(data(s), LINKS_REFRESH)) c |= fetch(s);
        if (c) Sites.reloadDeeplinks();
    }

    /** Readers forget what they parsed, so the next look uses the files now on disk. */
    static void changed() {
        Sites.reloadDeeplinks();
        Iptv.reloadExtras();
    }

    // ---- updating from 2.20.1 or older, which had its sources built in ----

    /** Files only those versions wrote. */
    static final String[] LEGACY = {"fmhy-video.md", "fmhy-nonenglish.md", "iptv-extras.txt", "iptv.m3u", "iptv-lang.m3u", "deeplinks.json"};

    /** Data an older version left (it downloads its lists at first start); migrate() deletes it, so this is true once per install at most. */
    static boolean legacy() {
        for (String n : LEGACY) if (new File(App.app.getFilesDir(), n).exists()) return true;
        return false;
    }

    /**
     * Once per install. A fresh one starts with nothing added. One updated from an older version gets the index and the sources
     * it marks default (what the old version had built in), plus its own source list if it had set one, and a notice on Home
     * (shown once: the flag is cleared as it opens).
     * Off the main thread; false when the index could not be reached (tried again at the next start).
     */
    static boolean migrate() {
        if (App.prefs.contains("sourcesVersion")) return true;
        if (!legacy()) { App.prefs.edit().putInt("sourcesVersion", 1).apply(); return true; }
        if (!App.prefs.contains("sourcesNotice")) App.prefs.edit().putBoolean("sourcesNotice", true).apply();
        if (!fetchIndex(INDEX)) return false;
        addIndex(INDEX);
        for (Source s : listed(INDEX)) if (s.isDefault && s.known()) add(s);
        String own = App.prefs.getString("extrasUrl", "");
        if (!own.isEmpty() && !own.endsWith("/sources/main/sources.txt") && fetchIndex(own)) {   // their own list, not the old default
            addIndex(own);
            for (Source s : listed(own)) add(s);
        }
        refresh(null);
        for (String n : LEGACY) new File(App.app.getFilesDir(), n).delete();
        for (File f : App.app.getFilesDir().listFiles()) if (f.getName().matches("iptv-adult\\d+\\.m3u")) f.delete();
        App.prefs.edit().remove("extrasUrl").putInt("sourcesVersion", 1).apply();
        return true;
    }
}
