package me.champdev.weblock;

import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Free IPTV channels from public playlists (iptv-org), plus the playlists you add yourself, kept on disk and refreshed at app start.
 * More playlists come from an optional source list: a text file you point the app at (Settings, Extra source list; by default the
 * org's sources repo). This code carries no playlist of its own. Entries marked 18 are kept apart (a Mature category, off by default).
 */
final class Iptv {
    static final String MAIN = "https://iptv-org.github.io/iptv/index.m3u";
    static final String LANGUAGES = "https://iptv-org.github.io/iptv/index.language.m3u";   // the same streams grouped by language
    /** Where the source list comes from until you change it in Settings. */
    static final String DEFAULT_EXTRAS = "https://raw.githubusercontent.com/fmhyplay/sources/main/sources.txt";
    private static List<String[]> adultSources;

    /** The link to the source list; empty when you cleared it on purpose. */
    static String extrasUrl() {
        return App.prefs.getString("extrasUrl", DEFAULT_EXTRAS);
    }

    /**
     * The source list (the file downloaded from the link above), one entry per line:
     * "label|url" a playlist, "label|url|18" an adult playlist, "@deeplinks|url" a title-page link table. Lines starting with # are notes.
     * Each result is {label, url, flag}.
     */
    static synchronized List<String[]> adultSources() {
        if (adultSources == null) {
            adultSources = new ArrayList<>();
            Set<String> urls = new HashSet<>();
            for (String line : read("iptv-extras.txt").split("\n")) {
                String[] f = line.trim().split("\\|", 3);
                if (f.length < 2 || line.startsWith("#") || f[0].startsWith("@") || !f[1].startsWith("http") || !urls.add(f[1])) continue;
                adultSources.add(new String[]{f[0], f[1], f.length > 2 ? f[2].trim() : ""});
            }
        }
        return adultSources;
    }

    static boolean isAdult(String[] src) {
        return src[2].equals("18");
    }

    /** The "@name|url" entry of the source list, or "". */
    static String special(String name) {
        for (String line : read("iptv-extras.txt").split("\n")) {
            String[] f = line.trim().split("\\|", 2);
            if (f.length == 2 && f[0].equals("@" + name) && f[1].startsWith("http")) return f[1];
        }
        return "";
    }

    /** Forget what was read so the next look uses the files now on disk. */
    static synchronized void reloadExtras() {
        adultSources = null;
        adultThere = null;
    }

    /** Downloads a text file the way the player reaches the network: through WARP, except for addresses on your own network. */
    static String download(String url) throws IOException {
        java.net.URL u = new java.net.URL(url);
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection(Warp.proxyFor(u.getHost()));
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36");
        return Sites.readAll(c.getInputStream());
    }

    static boolean saveText(String url, String name) {
        try {
            String text = download(url);
            try (FileOutputStream out = new FileOutputStream(file(name))) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static final java.util.regex.Pattern VOD = java.util.regex.Pattern.compile("(?i)\\.(mp4|mkv|avi|mov|wmv|flv|mpg|mpeg|webm|m4v)(\\?|$)");
    // IPTV panel accounts (token in the path or query): they answer with a "you need a subscription" clip, so they are left out
    static final java.util.regex.Pattern PANEL = java.util.regex.Pattern.compile("(?i)/iptv/[a-z0-9]{10,}/\\d+/index\\.m3u8?|/play/\\d+/[a-z0-9]{8,}/|[?&](token|username|password)=");
    static final java.util.regex.Pattern ACCOUNT = java.util.regex.Pattern.compile("^/(?:live/|movie/|series/)?[^/]+/[^/]+/\\d+(?:\\.\\w+)?$");
    // Left out of the adult lists: one provider's numbered camera feeds (the same name with a number), music radio and short-film files
    static final java.util.regex.Pattern CAM = java.util.regex.Pattern.compile("(?i)mycamtv\\W*#?\\d+|\\bradio\\b|short film|\\d+ ?kbit");
    // Left out of every list, adult or not: the OLERO channels
    static final java.util.regex.Pattern OLERO = java.util.regex.Pattern.compile("(?i)\\bolero\\b");
    static final long REFRESH = 12L * 3600 * 1000;
    static final String ADULT_NAME = "Mature", MINE = "My playlists", STREAMS = "My streams";

    static final class Channel {
        String name = "", logo = "", url = "", ua = "", ref = "", country = "", group = "", source = "";
        String playlist = "";   // which of your playlists it came from (its address), "" for the rest
        String[] cats = {"Other"};
        String[] langs = {};    // languages it broadcasts in (iptv-org channels only), e.g. "Hindi", "English"
        boolean adult;
    }

    private static List<Channel> cache;
    static volatile boolean loading;

    static File file(String name) {
        return new File(App.app.getFilesDir(), name);
    }

    static boolean stale() {
        // Also when a newly added extra source has never been downloaded
        int n = adultSources().size();
        return System.currentTimeMillis() - file("iptv.m3u").lastModified() > REFRESH || !file("iptv-lang.m3u").exists() || (n > 0 && !file("iptv-adult" + (n - 1) + ".m3u").exists()) || (!extrasUrl().isEmpty() && !file("iptv-extras.txt").exists()) || missingMine();
    }

    private static Boolean adultThere;

    /** Is there anything adult in the source list? Without it there is no adult category and no switch for it. */
    static synchronized boolean adultAvailable() {
        if (adultThere == null) {
            adultThere = false;
            for (String[] src : adultSources()) if (isAdult(src)) adultThere = true;
        }
        return adultThere;
    }

    /** Off by default, even when the source list has adult playlists. */
    static boolean adultOn() {
        return adultAvailable() && App.prefs.getBoolean("adult", false);
    }

    /** Every channel on disk (empty until the first download finishes). Adult ones only when the setting allows. */
    static synchronized List<Channel> all() {
        if (cache == null) {
            List<Channel> out = new ArrayList<>(parse(read("iptv.m3u"), false));
            Map<String, List<String>> langs = languages(read("iptv-lang.m3u"));
            for (Channel c : out) { List<String> l = langs.get(c.url); if (l != null) c.langs = l.toArray(new String[0]); }
            // The playlists you added: one category ("My playlists"), every entry kept as it is
            for (String url : mine()) {
                for (Channel c : parse(read(mineFile(url)), false)) {
                    c.cats = new String[]{MINE};
                    c.playlist = url;
                    out.add(c);
                }
            }
            // The source list's playlists: normal ones join the rest, adult ones are marked and sorted by where they came from; one entry per name
            List<Channel> adult = new ArrayList<>();
            for (int i = 0; i < adultSources().size(); i++) {
                String[] src = adultSources().get(i);
                for (Channel c : parse(read("iptv-adult" + i + ".m3u"), isAdult(src))) {
                    c.source = src[0];
                    (c.adult ? adult : out).add(c);
                }
            }
            Set<String> seen = new HashSet<>();
            for (Channel c : adult) if (seen.add(c.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "").replaceAll("(hd|fhd|sd)$", ""))) out.add(c);
            out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            cache = out;
        }
        List<Channel> out = new ArrayList<>();
        boolean hide = hideDead();
        for (Channel c : cache) if ((adultOn() || !c.adult) && !(hide && isDead(c.url))) out.add(c);
        return out;
    }

    /** Stream address to its languages, from iptv-org's by-language playlist (a stream is listed once per language). */
    static Map<String, List<String>> languages(String m3u) {
        Map<String, List<String>> out = new HashMap<>();
        String group = null;
        for (String line : m3u.split("\n")) {
            line = line.trim();
            if (line.startsWith("#EXTINF")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("group-title=\"([^\"]*)\"").matcher(line);
                group = m.find() ? m.group(1) : null;
            } else if (!line.isEmpty() && !line.startsWith("#") && group != null) {
                for (String g : group.split(";")) if (!g.isEmpty() && !g.equals("Undefined")) out.computeIfAbsent(line, k -> new ArrayList<>(2)).add(g);
                group = null;
            }
        }
        return out;
    }

    // ---- which channels work: probed in the background, remembered, and updated whenever you play one ----

    static final long ALIVE_TTL = 7L * 24 * 3600 * 1000, DEAD_TTL = 2L * 24 * 3600 * 1000;
    // url to the time it was checked: positive = worked then, negative = did not
    static final Map<String, Long> health = new java.util.concurrent.ConcurrentHashMap<>();
    static volatile boolean sweeping;
    static volatile int sweepDone, sweepTotal;
    // url to the time it last played fine for you (a permanent record, unlike the 20 most recent in history)
    static final Map<String, Long> good = new java.util.concurrent.ConcurrentHashMap<>();
    private static boolean healthLoaded;

    static boolean hideDead() {
        return App.prefs.getBoolean("hideDead", true);
    }

    static synchronized void loadHealth() {
        if (healthLoaded) return;
        healthLoaded = true;
        for (String line : read("iptv-health.txt").split("\n")) {
            String[] f = line.split(" ", 2);
            try { if (f.length == 2) health.put(f[1], Long.parseLong(f[0])); } catch (NumberFormatException ignored) { }
        }
        for (String line : read("iptv-good.txt").split("\n")) {
            String[] f = line.split(" ", 2);
            try { if (f.length == 2) good.put(f[1], Long.parseLong(f[0])); } catch (NumberFormatException ignored) { }
        }
    }

    static void saveHealth() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : health.entrySet()) sb.append(e.getValue()).append(' ').append(e.getKey()).append('\n');
        try (FileOutputStream out = new FileOutputStream(file("iptv-health.txt"))) { out.write(sb.toString().getBytes(StandardCharsets.UTF_8)); } catch (IOException ignored) { }
        StringBuilder g = new StringBuilder();
        for (Map.Entry<String, Long> e : good.entrySet()) g.append(e.getValue()).append(' ').append(e.getKey()).append('\n');
        try (FileOutputStream out = new FileOutputStream(file("iptv-good.txt"))) { out.write(g.toString().getBytes(StandardCharsets.UTF_8)); } catch (IOException ignored) { }
    }

    /** It played: remember that for good, and clear any earlier failure. */
    static void played(String url) {
        loadHealth();
        long now = System.currentTimeMillis();
        health.put(url, now);
        good.put(url, now);
    }

    /** Channels that work: the ones that played for you (newest first), then the ones that passed the background check. */
    static List<Channel> working() {
        loadHealth();
        List<Channel> played = new ArrayList<>(), tested = new ArrayList<>();
        for (Channel c : all()) {
            if (good.containsKey(c.url)) played.add(c);
            else if (health.getOrDefault(c.url, 0L) > 0) tested.add(c);
        }
        played.sort((a, b) -> Long.compare(good.get(b.url), good.get(a.url)));
        played.addAll(tested);
        return played;
    }

    static void mark(String url, boolean works) {
        loadHealth();
        long now = System.currentTimeMillis();
        health.put(url, works ? now : -now);
    }

    static boolean isDead(String url) {
        loadHealth();
        Long v = health.get(url);
        return v != null && v < 0 && System.currentTimeMillis() + v < DEAD_TTL;
    }

    static boolean known(String url) {
        Long v = health.get(url);
        return v != null && System.currentTimeMillis() - Math.abs(v) < (v > 0 ? ALIVE_TTL : DEAD_TTL);
    }

    static int deadCount() {
        loadHealth();
        int n = 0;
        for (Channel c : all(true)) if (isDead(c.url)) n++;   // all(true) reloads the list after a playlist was added or removed
        return n;
    }

    /** One line for the top of the IPTV tab, "" when there is nothing to say. */
    static String statusLine() {
        if (sweeping) return App.t(R.string.iptv_checking, sweepDone, sweepTotal);
        int dead = deadCount();
        return dead > 0 && hideDead() ? App.t(R.string.iptv_offline_hidden, dead) : "";
    }

    /** Is the stream reachable: the playlist or first bytes come back and are not a web page. */
    static boolean probe(Channel c) {
        java.net.HttpURLConnection h = null;
        try {
            java.net.URL u = new java.net.URL(c.url);
            h = (java.net.HttpURLConnection) u.openConnection(Warp.proxyFor(u.getHost()));
            h.setConnectTimeout(6000);
            h.setReadTimeout(6000);
            h.setInstanceFollowRedirects(true);
            h.setRequestProperty("User-Agent", c.ua.isEmpty() ? "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36" : c.ua);
            if (!c.ref.isEmpty()) h.setRequestProperty("Referer", c.ref);
            int code = h.getResponseCode();
            if (code >= 300 && code < 400) return true;   // a redirect to another protocol: the player follows it, a plain check can't
            if (code >= 400) return false;
            byte[] b = new byte[512];
            int n;
            try (java.io.InputStream in = h.getInputStream()) { n = in.read(b); }
            String head = n > 0 ? new String(b, 0, n, StandardCharsets.ISO_8859_1).trim().toLowerCase(Locale.ROOT) : "";
            return n > 0 && !head.startsWith("<!doctype") && !head.startsWith("<html");
        } catch (Exception e) {
            return false;
        } finally {
            if (h != null) h.disconnect();
        }
    }

    /** Tests every channel not checked lately, favorites and recent ones first. Runs in the background; results persist as it goes. */
    static void sweep() {
        if (sweeping) return;
        sweeping = true;
        new Thread(() -> {
            try {
                loadHealth();
                List<Channel> raw = all(true);
                List<Channel> todo = new ArrayList<>();
                Set<String> first = new HashSet<>();
                for (Channel c : favorites()) first.add(c.url);
                for (Channel c : history()) first.add(c.url);
                // Never your own per-account streams (user/pass/id, as Xtream providers serve, or a Stalker portal's): an account allows one or
                // two connections at a time, and 16 checks at once could get it locked
                raw.removeIf(c -> c.url.startsWith(Stalker.SCHEME) || c.cats[0].equals(MINE) && ACCOUNT.matcher(String.valueOf(Uri.parse(c.url).getPath())).matches());
                for (Channel c : raw) if (first.contains(c.url) && !known(c.url)) todo.add(c);
                for (Channel c : raw) if (!first.contains(c.url) && !known(c.url)) todo.add(c);
                sweepDone = 0;
                sweepTotal = todo.size();
                java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(16);
                for (Channel c : todo) ex.execute(() -> {
                    mark(c.url, probe(c));
                    if (++sweepDone % 1000 == 0) saveHealth();
                });
                ex.shutdown();
                ex.awaitTermination(2, java.util.concurrent.TimeUnit.HOURS);
            } catch (InterruptedException ignored) {
            } finally {
                saveHealth();
                sweeping = false;
            }
        }).start();
    }

    /**
     * The site address table on its own (small; sites change their addresses often): at app start when older than an hour,
     * apart from the playlists' 12 hours. Call off the main thread.
     */
    static void refreshLinks() {
        if (System.currentTimeMillis() - file("deeplinks.json").lastModified() < 3600_000L) return;
        if (!extrasUrl().isEmpty()) saveText(extrasUrl(), "iptv-extras.txt");
        reloadExtras();
        String links = special("deeplinks");
        if (!links.isEmpty() && saveText(links, "deeplinks.json")) Sites.reloadDeeplinks();
    }

    /** Every channel including the offline ones (for the checker itself). */
    static synchronized List<Channel> all(boolean everything) {
        if (!everything) return all();
        all();   // fills the cache
        return new ArrayList<>(cache);
    }

    /** Downloads the playlists; true if the main one arrived. Call off the main thread. */
    static boolean refresh() {
        loading = true;
        try {
            boolean ok = save(MAIN, "iptv.m3u");
            save(LANGUAGES, "iptv-lang.m3u");
            if (!extrasUrl().isEmpty()) saveText(extrasUrl(), "iptv-extras.txt");
            reloadExtras();
            String links = special("deeplinks");
            if (!links.isEmpty() && saveText(links, "deeplinks.json")) Sites.reloadDeeplinks();
            for (int i = 0; i < adultSources().size(); i++) save(adultSources().get(i)[1], "iptv-adult" + i + ".m3u");
            for (String url : mine()) fetchMine(url);
            synchronized (Iptv.class) { cache = null; }
            return ok;
        } finally {
            loading = false;
        }
    }

    // ---- playlists you add yourself (any M3U URL) ----

    static List<String> mine() {
        List<String> out = new ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(App.prefs.getString("iptvmine", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (org.json.JSONException ignored) { }
        return out;
    }

    static void saveMine(List<String> urls) {
        App.prefs.edit().putString("iptvmine", new org.json.JSONArray(urls).toString()).apply();
        synchronized (Iptv.class) { cache = null; }
    }

    static String mineFile(String url) {
        return "iptv-my-" + Integer.toHexString(url.hashCode()) + ".m3u";
    }

    static boolean missingMine() {
        for (String url : mine()) if (!file(mineFile(url)).exists()) return true;
        return false;
    }

    // ---- Xtream Codes logins: kept in the same list as the playlists, as the provider's player_api.php address ----

    static String xtreamUrl(String server, String user, String pass) {
        String base = server.trim().replaceFirst("/+(player_api\\.php.*|get\\.php.*)?$", "");
        if (!base.matches("(?i)https?://.+")) base = "http://" + base;   // most providers serve plain http on a port
        return base + "/player_api.php?username=" + Uri.encode(user.trim()) + "&password=" + Uri.encode(pass.trim());
    }

    static boolean isXtream(String url) {
        return Uri.parse(url).getPath() != null && Uri.parse(url).getPath().endsWith("/player_api.php");
    }

    /** The name you gave a playlist; without one its address, or "host · Xtream (user)" / "host · Stalker" so a password or MAC never shows. */
    static String playlistName(String url) {
        String named = names().optString(url, "");
        if (!named.isEmpty()) return named;
        Uri u = Uri.parse(url);
        String host = u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        return isXtream(url) ? host + " \u00B7 Xtream (" + u.getQueryParameter("username") + ")"
            : Stalker.isAccount(url) ? host + " \u00B7 Stalker" : url.replaceFirst("(?i)^https?://", "");
    }

    static org.json.JSONObject names() {
        try { return new org.json.JSONObject(App.prefs.getString("iptvnames", "{}")); } catch (org.json.JSONException e) { return new org.json.JSONObject(); }
    }

    static void name(String url, String name) {
        org.json.JSONObject n = names();
        try { if (name == null || name.trim().isEmpty()) n.remove(url); else n.put(url, name.trim()); } catch (org.json.JSONException ignored) { }
        App.prefs.edit().putString("iptvnames", n.toString()).apply();
    }

    /** Signs in and turns the account's live channels into an M3U playlist (group = the provider's category); null when the login fails. */
    static String xtreamPlaylist(String api) throws IOException {
        try {
            Uri u = Uri.parse(api);
            org.json.JSONObject login = new org.json.JSONObject(download(api)).optJSONObject("user_info");
            if (login == null || login.optInt("auth") != 1) return null;
            String ext = login.optJSONArray("allowed_output_formats") != null && !login.getJSONArray("allowed_output_formats").toString().contains("m3u8") ? "ts" : "m3u8";
            Map<String, String> cats = new java.util.HashMap<>();
            org.json.JSONArray c = new org.json.JSONArray(download(api + "&action=get_live_categories"));
            for (int i = 0; i < c.length(); i++) cats.put(c.getJSONObject(i).optString("category_id"), c.getJSONObject(i).optString("category_name"));
            String base = api.substring(0, api.indexOf("/player_api.php")) + "/live/" + Uri.encode(u.getQueryParameter("username")) + "/" + Uri.encode(u.getQueryParameter("password")) + "/";
            StringBuilder m3u = new StringBuilder("#EXTM3U\n");
            org.json.JSONArray live = new org.json.JSONArray(download(api + "&action=get_live_streams"));
            for (int i = 0; i < live.length(); i++) {
                org.json.JSONObject s = live.getJSONObject(i);
                String group = cats.getOrDefault(s.optString("category_id"), "Live").replace("\"", "'");
                m3u.append("#EXTINF:-1 tvg-logo=\"").append(s.optString("stream_icon")).append("\" group-title=\"").append(group).append("\",")
                    .append(s.optString("name").replace("\n", " ").trim()).append('\n').append(base).append(s.optString("stream_id")).append('.').append(ext).append('\n');
            }
            return m3u.toString();
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    /**
     * Downloads one of your playlists. A bare stream link (an HLS playlist, not a list of channels) becomes a playlist of one channel,
     * named after its address, so a single .m3u8 can be added the same way.
     */
    static boolean fetchMine(String url) {
        try {
            String text = isXtream(url) ? xtreamPlaylist(url) : Stalker.isAccount(url) ? Stalker.playlist(url, Warp.proxyFor(Uri.parse(url).getHost())) : download(url);
            if (text == null || !text.trim().startsWith("#EXTM3U")) return false;
            if (parse(text, false).isEmpty() && text.contains("#EXT-X-"))
                text = "#EXTM3U\n#EXTINF:-1 group-title=\"" + STREAMS + "\"," + (names().has(url) ? names().optString(url) : streamName(url)) + "\n" + url + "\n";
            try (FileOutputStream out = new FileOutputStream(file(mineFile(url)))) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** "cdn.example.com · news" for https://cdn.example.com/live/news/index.m3u8: the host plus the last part of the path that says something. */
    static String streamName(String url) {
        Uri u = Uri.parse(url);
        String host = String.valueOf(u.getHost()).replaceFirst("^www\\.", ""), pick = "";
        List<String> seg = u.getPathSegments();
        for (int i = seg.size() - 1; i >= 0 && pick.isEmpty(); i--) {
            String part = seg.get(i).replaceAll("(?i)\\.(m3u8?|mpd|ts)$", "");
            if (!part.matches("(?i)index|master|playlist|manifest|stream|live|chunklist.*|\\d+")) pick = part;
        }
        String name = pick.isEmpty() ? host : host + " \u00B7 " + pick;
        return name.codePoints().anyMatch(Character::isLetter) ? name : "Stream " + name;   // a name needs a letter, an IP address has none
    }

    /** Downloads and adds a playlist or a single stream link; null when it worked, else what went wrong. Call off the main thread. */
    static String addPlaylist(String raw, String name) {
        String url = raw.trim();
        if (!url.matches("(?i)https?://.+")) url = "https://" + url;
        List<String> now = mine();
        if (now.contains(url)) return App.t(R.string.iptv_already_added);
        name(url, name);   // before the download: a single stream link takes it as its channel name
        if (!fetchMine(url)) {
            name(url, null);
            return App.t(isXtream(url) ? R.string.iptv_cant_sign_in : Stalker.isAccount(url) ? R.string.stalker_cant_sign_in : R.string.iptv_not_playlist);
        }
        int n = parse(read(mineFile(url)), false).size();
        if (n == 0) { file(mineFile(url)).delete(); name(url, null); return App.t(R.string.iptv_no_channels); }
        now.add(url);
        saveMine(now);
        return null;
    }

    static void removePlaylist(String url) {
        List<String> now = mine();
        now.remove(url);
        saveMine(now);
        name(url, null);
        file(mineFile(url)).delete();
    }

    static boolean save(String url, String name) {
        try {
            // Same route as the player: through WARP, except for addresses on your own network
            java.net.URL u = new java.net.URL(url);
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection(Warp.proxyFor(u.getHost()));
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36");
            String text = Sites.readAll(c.getInputStream());
            if (!text.trim().startsWith("#EXTM3U")) return false;
            File tmp = file(name + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
            return tmp.renameTo(file(name));
        } catch (IOException e) {
            return false;
        }
    }

    static String read(String name) {
        File f = file(name);
        try {
            return f.exists() ? Sites.readAll(new FileInputStream(f)) : "";
        } catch (IOException e) {
            return "";
        }
    }

    static List<Channel> parse(String text, boolean adult) {
        List<Channel> out = new ArrayList<>();
        Channel c = null;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXTINF")) {
                c = new Channel();
                c.adult = adult;
                c.name = name(line);
                c.logo = attr(line, "tvg-logo");
                c.ua = attr(line, "http-user-agent");
                c.ref = attr(line, "http-referrer");
                String id = attr(line, "tvg-id");
                int dot = id.lastIndexOf('.');
                if (!adult && dot >= 0) c.country = country(id.substring(dot + 1).replaceAll("@.*", ""));
                String group = adult ? ADULT_NAME : attr(line, "group-title");
                c.group = attr(line, "group-title");
                if (!group.isEmpty()) c.cats = group.split(";");
                if (c.cats[0].equals("Undefined")) c.cats = new String[]{"Other"};
            } else if (c != null && line.startsWith("#EXTVLCOPT:")) {
                if (line.contains("http-referrer=")) c.ref = line.substring(line.indexOf("http-referrer=") + 14);
                else if (line.contains("http-user-agent=")) c.ua = line.substring(line.indexOf("http-user-agent=") + 16);
            } else if (c != null && !line.isEmpty() && !line.startsWith("#")) {
                // Adult lists are fetched as-is: leave out video files and per-user account URLs (user/pass/id)
                boolean skip = adult && (CAM.matcher(c.name).find() || PANEL.matcher(line).find() || VOD.matcher(line).find() || ACCOUNT.matcher(String.valueOf(Uri.parse(line).getPath())).matches());
                // A name with no letter in it ("", "#", "#00054", "1234") is not a channel anyone can pick
                boolean named = c.name.codePoints().anyMatch(Character::isLetter) && !OLERO.matcher(c.name).find();
                if ((line.startsWith("http") || line.startsWith(Stalker.SCHEME)) && named && !skip) { c.url = clean(line); out.add(c); }
                c = null;
            }
        }
        return out;
    }

    /** Some playlists leave ad-server macros in the link ("AV_APPNAME=[ADS.APP_NAME]", "cb=[CACHEBUSTER]"); the server wants those parameters empty or gone. */
    static String clean(String u) {
        int q = u.indexOf('?');
        if (q < 0 || u.indexOf('[', q) < 0) return u;
        StringBuilder sb = new StringBuilder(u.substring(0, q));
        char sep = '?';
        for (String p : u.substring(q + 1).split("&")) {
            int eq = p.indexOf('=');
            String v = eq < 0 ? "" : p.substring(eq + 1);
            if (v.startsWith("[") && v.endsWith("]")) continue;   // the cachebuster too: a changing value would make every load a new channel for the health and favorites records
            sb.append(sep).append(p);
            sep = '&';
        }
        return sb.toString();
    }

    /** The text after the last comma that is outside quotes (logo URLs can hold commas). */
    static String name(String line) {
        boolean quoted = false;
        int comma = -1;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') quoted = !quoted;
            else if (ch == ',' && !quoted) comma = i;
        }
        return comma < 0 ? "" : line.substring(comma + 1).trim();
    }

    static String attr(String line, String key) {
        String k = key + "=\"";
        for (int i = line.indexOf(k); i >= 0; i = line.indexOf(k, i + 1)) {
            if (i > 0 && line.charAt(i - 1) != ' ') continue;
            int end = line.indexOf('"', i + k.length());
            return end < 0 ? "" : line.substring(i + k.length(), end);
        }
        return "";
    }

    static String country(String code) {
        if (code.equals("uk")) code = "gb";
        String n = new Locale("", code.toUpperCase(Locale.ROOT)).getDisplayCountry(Locale.ENGLISH);
        return n.isEmpty() ? code.toUpperCase(Locale.ROOT) : n;
    }

    // ---- pins: a channel list with its chips (India · Hindi · News) kept as its own row on the IPTV tab ----

    static final class Pin {
        String type = "", key = "", title = "", cat = "", lang = "";

        boolean same(String t, String k, String c, String l) { return type.equals(t) && key.equals(k) && cat.equals(c) && lang.equals(l); }
    }

    static List<Pin> pins() {
        List<Pin> out = new ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(App.prefs.getString("iptvPins", "[]"));
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                Pin p = new Pin();
                p.type = o.optString("type"); p.key = o.optString("key"); p.title = o.optString("title");
                p.cat = o.optString("cat"); p.lang = o.optString("lang");
                out.add(p);
            }
        } catch (org.json.JSONException ignored) { }
        return out;
    }

    static boolean isPinned(String type, String key, String cat, String lang) {
        for (Pin p : pins()) if (p.same(type, key, cat, lang)) return true;
        return false;
    }

    /** Pins the list with these chips, or unpins it; true when it is pinned now. */
    static boolean togglePin(String type, String key, String title, String cat, String lang) {
        List<Pin> list = pins();
        boolean had = list.removeIf(p -> p.same(type, key, cat, lang));
        if (!had) {
            Pin p = new Pin();
            p.type = type; p.key = key; p.title = title; p.cat = cat; p.lang = lang;
            list.add(p);
        }
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            for (Pin p : list) a.put(new org.json.JSONObject().put("type", p.type).put("key", p.key).put("title", p.title).put("cat", p.cat).put("lang", p.lang));
        } catch (org.json.JSONException ignored) { }
        App.prefs.edit().putString("iptvPins", a.toString()).apply();
        return !had;
    }

    /** The list's channels that match its chips: category by key, language by name; "" means any. */
    static List<Channel> filter(List<Channel> in, String cat, String lang) {
        List<Channel> out = new ArrayList<>();
        for (Channel c : in) if ((cat.isEmpty() || java.util.Arrays.asList(c.cats).contains(cat)) && (lang.isEmpty() || java.util.Arrays.asList(c.langs).contains(lang))) out.add(c);
        return out;
    }

    /** What people look for first: movies, TV, kids, then information; the rest after these by size. */
    static final List<String> CATEGORY_ORDER = java.util.Arrays.asList("Movies", "Series", "Entertainment", "General", "Kids", "Animation", "Family",
        "News", "Documentary", "Education", "Science", "Sports", "Music", "Comedy", "Lifestyle", "Cooking", "Travel", "Culture", "Classic");

    /** Sorts category keys: CATEGORY_ORDER first, then the rest by how many channels (`size`) they have. */
    static java.util.Comparator<String> categoryRank(java.util.function.ToIntFunction<String> size) {
        return (a, b) -> {
            int ia = CATEGORY_ORDER.indexOf(a), ib = CATEGORY_ORDER.indexOf(b);
            if (ia >= 0 || ib >= 0) return ia < 0 ? 1 : ib < 0 ? -1 : ia - ib;
            int d = size.applyAsInt(b) - size.applyAsInt(a);
            return d != 0 ? d : a.compareToIgnoreCase(b);
        };
    }

    /**
     * Sorts languages: the one picked on the player's Audio button, then the list's biggest language, then English, then the rest
     * by size (India: Hindi, English, Tamil, ...; the US: English, Spanish, ...).
     */
    static List<String> rankLanguages(Map<String, Integer> count) {
        List<String> bySize = new ArrayList<>(count.keySet());
        bySize.sort((a, b) -> count.get(b) - count.get(a) != 0 ? count.get(b) - count.get(a) : a.compareToIgnoreCase(b));
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        String picked = App.prefs.getString("audioLang", "");
        if (!picked.isEmpty() && count.containsKey(new Locale(picked).getDisplayLanguage(Locale.ENGLISH))) out.add(new Locale(picked).getDisplayLanguage(Locale.ENGLISH));
        if (!bySize.isEmpty()) out.add(bySize.get(0));
        if (count.containsKey("English")) out.add("English");
        out.addAll(bySize);
        return new ArrayList<>(out);
    }

    /** Group name to channels. by = "cat" (movies, TV, kids, news first: categoryRank) or "country" (biggest first, your own country on top). */
    static Map<String, List<Channel>> groups(String by) {
        Map<String, List<Channel>> m = new LinkedHashMap<>();
        for (Channel c : all()) {
            String[] keys = by.equals("cat") ? c.cats : new String[]{c.country};
            for (String k : keys) if (!k.isEmpty() && !(by.equals("cat") && k.equals(MINE))) m.computeIfAbsent(k, x -> new ArrayList<>()).add(c);
        }
        List<Map.Entry<String, List<Channel>>> es = new ArrayList<>(m.entrySet());
        if (by.equals("cat")) {
            java.util.Comparator<String> rank = categoryRank(k -> m.get(k).size());
            es.sort((a, b) -> rank.compare(a.getKey(), b.getKey()));
        }
        else es.sort((a, b) -> b.getValue().size() - a.getValue().size());
        Map<String, List<Channel>> out = new LinkedHashMap<>();
        String mine = country(Locale.getDefault().getCountry().isEmpty() ? "us" : Locale.getDefault().getCountry().toLowerCase(Locale.ROOT));
        if (by.equals("country") && m.containsKey(mine)) out.put(mine, m.get(mine));
        for (Map.Entry<String, List<Channel>> e : es) out.putIfAbsent(e.getKey(), e.getValue());
        return out;
    }

    /** Adult channels grouped by the list they came from (in the order of the source list), A to Z inside each. */
    static List<Channel> bySource(List<Channel> in) {
        List<String> order = new ArrayList<>();
        for (String[] src : adultSources()) order.add(src[0]);
        List<Channel> out = new ArrayList<>(in);
        out.sort((a, b) -> {
            int d = order.indexOf(a.source) - order.indexOf(b.source);
            return d != 0 ? d : a.name.compareToIgnoreCase(b.name);
        });
        return out;
    }

    static List<Channel> adult() {
        List<Channel> out = new ArrayList<>();
        for (Channel c : all()) if (c.adult) out.add(c);
        return out;
    }

    // ---- recently watched (its own list, never mixed into the movie history on Home) ----

    static List<Channel> history() {
        List<Channel> out = new ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(App.prefs.getString("iptvhist", "[]"));
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                Channel c = new Channel();
                c.name = o.getString("name");
                c.url = o.getString("url");
                c.logo = o.optString("logo");
                c.ua = o.optString("ua");
                c.ref = o.optString("ref");
                c.country = o.optString("country");
                c.adult = o.optBoolean("adult");
                c.cats = o.optString("cats", "Other").split(";");
                if (c.adult && !adultOn()) continue;
                out.add(c);
            }
        } catch (org.json.JSONException ignored) { }
        return out;
    }

    static void removeFromHistory(String url) {
        List<Channel> h = new ArrayList<>(history());
        h.removeIf(x -> x.url.equals(url));
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            for (Channel x : h) {
                a.put(new org.json.JSONObject().put("name", x.name).put("url", x.url).put("logo", x.logo).put("ua", x.ua).put("ref", x.ref)
                    .put("country", x.country).put("adult", x.adult).put("cats", String.join(";", x.cats)));
            }
        } catch (org.json.JSONException ignored) { }
        App.prefs.edit().putString("iptvhist", a.toString()).apply();
    }

    /** Take a channel off the Working list (it stays in the lists, and the background check can add it back). */
    static void forgetWorking(String url) {
        loadHealth();
        good.remove(url);
        health.remove(url);
        saveHealth();
    }

    /**
     * The channels that go with one (by its address): the same first category in the same country, then the country's other categories,
     * or the same playlist and group for one of yours; in list order, the channel itself among them. Empty when it is not in the lists. For the player's channel list and +/- keys.
     */
    static List<Channel> siblings(String url) {
        List<Channel> out = new ArrayList<>();
        Channel me = null;
        for (Channel c : all()) if (c.url.equals(url)) { me = c; break; }
        if (me == null) return out;
        boolean own = me.cats[0].equals(MINE);
        for (Channel c : all()) {
            if (!c.cats[0].equals(me.cats[0])) continue;
            if (own ? c.playlist.equals(me.playlist) && c.group.equals(me.group) : c.country.equals(me.country)) out.add(c);
        }
        if (own) return out;
        // then the country's other categories (in the Categories row's order), so Channel + and the strip keep going past the end of this one
        java.util.Set<String> have = new java.util.HashSet<>();
        for (Channel c : out) have.add(c.url);
        for (Map.Entry<String, List<Channel>> g : groups("cat").entrySet()) {
            if (g.getKey().equals(me.cats[0])) continue;
            for (Channel c : g.getValue()) if (c.country.equals(me.country) && c.adult == me.adult && have.add(c.url)) out.add(c);
        }
        return out;
    }

    static void play(android.content.Context ctx, Channel c) {
        List<Channel> h = new ArrayList<>(history());
        h.removeIf(x -> x.url.equals(c.url));
        h.add(0, c);
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            for (Channel x : h.subList(0, Math.min(20, h.size()))) {
                a.put(new org.json.JSONObject().put("name", x.name).put("url", x.url).put("logo", x.logo).put("ua", x.ua).put("ref", x.ref)
                    .put("country", x.country).put("adult", x.adult).put("cats", String.join(";", x.cats)));
            }
        } catch (org.json.JSONException ignored) { }
        App.prefs.edit().putString("iptvhist", a.toString()).apply();
        Map<String, String> hd = new java.util.HashMap<>();
        if (!c.ua.isEmpty()) hd.put("User-Agent", c.ua);
        if (!c.ref.isEmpty()) hd.put("Referer", c.ref);
        if (!c.url.startsWith(Stalker.SCHEME)) {
            ctx.startActivity(PlayerActivity.open(ctx, c.url, false, hd, new ArrayList<>(), c.name).putExtra("mime", mime(c.url)));
            return;
        }
        // A Stalker channel: the portal makes a link for this play (it carries a token that runs out)
        App.io.execute(() -> {
            String url;
            try { url = Stalker.link(c.url, Warp.proxyFor(Uri.parse(c.url.substring(Stalker.SCHEME.length())).getHost())); } catch (IOException e) { url = null; }
            String link = url;
            App.main.post(() -> {
                if (link == null) { android.widget.Toast.makeText(ctx, App.t(R.string.stalker_no_link), android.widget.Toast.LENGTH_LONG).show(); return; }
                // MAG portals mostly send MPEG-TS from a .php address, which mime() would take for HLS
                String type = link.toLowerCase(Locale.ROOT).contains("m3u8") ? "application/x-mpegURL" : mime(link).contains("dash") ? mime(link) : "";
                ctx.startActivity(PlayerActivity.open(ctx, link, false, hd, new ArrayList<>(), c.name).putExtra("mime", type).putExtra("channel", c.url));
            });
        });
    }

    static List<Channel> favorites() {
        Set<String> urls = favSet();
        List<Channel> out = new ArrayList<>();
        for (Channel c : all()) if (urls.contains(c.url)) out.add(c);
        return out;
    }

    static Set<String> favSet() {
        return new HashSet<>(App.prefs.getStringSet("iptvfav", new HashSet<>()));
    }

    static boolean isFavorite(Channel c) {
        return favSet().contains(c.url);
    }

    static void toggleFavorite(Channel c) {
        Set<String> s = favSet();
        if (!s.remove(c.url)) s.add(c.url);
        App.prefs.edit().putStringSet("iptvfav", s).apply();
    }

    /** The channels behind a card: type = fav | adult | all | cat | country. */
    static List<Channel> list(String type, String key) {
        switch (type) {
            case "fav": return favorites();
            case "working": return working();
            case "mine": {   // key = one playlist's address, STREAMS = the single stream links, "" = all of yours
                List<Channel> own = new ArrayList<>();
                for (Channel c : all()) if (c.cats[0].equals(MINE) && (key.isEmpty() || c.playlist.equals(key) || key.equals(STREAMS) && c.group.equals(STREAMS))) own.add(c);
                return own;
            }
            case "adult": return bySource(adult());
            case "cat": case "country": {
                List<Channel> g = groups(type).get(key);
                if (g == null) return new ArrayList<>();
                return type.equals("cat") && key.equals(ADULT_NAME) ? bySource(g) : g;
            }
            default: return all();
        }
    }

    static final Map<String, String> EMOJI = new LinkedHashMap<>();
    static {
        String[] e = {"General", "\uD83D\uDCFA", "Other", "\uD83D\uDDC2", "News", "\uD83D\uDCF0", "Entertainment", "\uD83C\uDFAD", "Religious", "\uD83D\uDE4F",
            "Music", "\uD83C\uDFB5", "Movies", "\uD83C\uDFAC", "Series", "\uD83C\uDF9E", "Sports", "\u26BD", "Kids", "\uD83E\uDDF8", "Documentary", "\uD83C\uDF0D",
            "Legislative", "\uD83C\uDFDB", "Education", "\uD83C\uDF93", "Comedy", "\uD83D\uDE02", "Culture", "\uD83C\uDFA8", "Lifestyle", "\uD83C\uDF3F",
            "Shop", "\uD83D\uDECD", "Business", "\uD83D\uDCBC", "Animation", "\uD83D\uDC2D", "Outdoor", "\uD83C\uDFD5", "Classic", "\uD83D\uDCFB",
            "Travel", "\u2708\uFE0F", "Family", "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", "Cooking", "\uD83C\uDF73", "Auto", "\uD83D\uDE97", "Weather", "\u26C5", "Science", "\uD83D\uDD2C",
            "Public", "\uD83C\uDFD9", "Mature", "\uD83D\uDD1E", "My playlists", "\uD83D\uDCC2", "Relax", "\uD83E\uDDD8", "Interactive", "\uD83D\uDD79"};
        for (int i = 0; i < e.length; i += 2) EMOJI.put(e[i], e[i + 1]);
    }

    /** Icon for a category tile. */
    static String emoji(String category) {
        String e = EMOJI.get(category);
        return e == null ? "\uD83D\uDCE1" : e;
    }

    private static Map<String, String> flags;

    /** Flag emoji for a country's English name (two regional-indicator letters from its ISO code). */
    static synchronized String flag(String name) {
        if (flags == null) {
            flags = new java.util.HashMap<>();
            for (String iso : Locale.getISOCountries()) {
                String f = new String(Character.toChars(0x1F1E6 + iso.charAt(0) - 'A')) + new String(Character.toChars(0x1F1E6 + iso.charAt(1) - 'A'));
                flags.put(new Locale("", iso).getDisplayCountry(Locale.ENGLISH), f);
            }
        }
        String f = flags.get(name);
        return f == null ? "\uD83C\uDFF3" : f;
    }

    /** ExoPlayer media type for a stream URL: DASH, plain files by their extension (""), else HLS. */
    static String mime(String url) {
        String p = Sniffer.path(url).toLowerCase(Locale.ROOT);
        if (p.endsWith(".mpd")) return "application/dash+xml";
        if (p.matches(".*\\.(ts|mp4|mkv|avi|flv|mp3|aac)")) return "";
        return "application/x-mpegURL";
    }
}
