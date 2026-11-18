package me.champdev.weblock;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** "Continue watching": the last title opened on each site, newest first. */
final class History {
    static final int MAX = 60;
    static final Pattern CONTENT = Pattern.compile("(?i)/(movies?|tv|series|shows?|watch|episodes?|anime|films?|drama|title|play|stream|live|match|event|channel)([/-]|$)");
    static final Pattern NOT_CONTENT = Pattern.compile("(?i)/(search|genres?|category|categories|browse|filter|trending|popular|top|list|login|account)([/-]|$)");
    static final Pattern EP_TEXT = Pattern.compile("(?i)\\b(?:S(\\d{1,2})\\s*E(\\d{1,4})|Season\\s*(\\d{1,3})\\W*Episode\\s*(\\d{1,5})|(?:Episode|Ep\\.?)\\s*(\\d{1,5}))\\b");
    static final Pattern[] EP_URL = {
        Pattern.compile("(?i)/(?:tv|series|show)/[^/?#]+/(\\d{1,2})/(\\d{1,4})(?:[/?#]|$)"),
        Pattern.compile("(?i)season[-/=](\\d{1,3})(?!\\d).*?episode[-/=](\\d{1,5})(?!\\d)"),
        Pattern.compile("(?i)[?&]s=(\\d{1,3})(?!\\d).*?[?&]e=(\\d{1,5})(?!\\d)"),
    };
    static final Pattern EP_ONLY_URL = Pattern.compile("(?i)(?:episode|ep)[-/=](\\d{1,6})(?!\\d)");   // digits are capped everywhere here: a longer run is an id and would overflow an int
    // Site taglines like "Movies, TV Shows & Anime" (shown before a page sets its real title)
    static final Pattern GENERIC = Pattern.compile("(?i)^(watch|free|online|hd|movies?|films?|tv|shows?|series|anime|dramas?|cartoons?|stream(ing)?|and|for|in|[,&|·:\\s-])+$");
    static final Pattern JUNK_TAIL = Pattern.compile("(?i)\\s+(online|free|hd|streaming|full movie|for free|eng(lish)? sub(bed)?|subbed|dubbed)\\b.*$");

    static final class Entry {
        String site, siteUrl, title, url, image, episode;
        long time;
        long pos, dur;          // where the native player left off (ms), and the video's length; 0 = from the start
        long posAt;             // when pos was saved: the newest one wins across sites
        String posEpisode = ""; // the episode `pos` belongs to: a new episode starts from the beginning
        boolean done;           // that episode was watched to the credits (pos is 0 then, as it is for one just started)
    }

    static boolean isContent(String url) {
        String path = String.valueOf(Uri.parse(url).getPath());
        return path.length() > 1 && CONTENT.matcher(path).find() && !NOT_CONTENT.matcher(path).find();
    }

    static List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(App.prefs.getString("history", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Entry e = new Entry();
                e.site = o.getString("site");
                e.siteUrl = o.getString("siteUrl");
                e.title = o.getString("title").replaceAll("\\s*[(\\[]\\d{4}[)\\]]$", "");   // older versions kept "Verity (2026)"
                e.url = o.getString("url");
                e.image = o.optString("image");
                e.episode = o.optString("episode");
                e.time = o.getLong("time");
                e.pos = o.optLong("pos");
                e.dur = o.optLong("dur");
                e.posEpisode = o.optString("posEp");
                e.done = o.optBoolean("done");
                e.posAt = o.optLong("posAt");
                out.add(e);
            }
        } catch (JSONException ignored) { }
        return out;
    }

    static void save(List<Entry> list) {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : list.subList(0, Math.min(MAX, list.size())))
                a.put(new JSONObject().put("site", e.site).put("siteUrl", e.siteUrl).put("title", e.title).put("url", e.url)
                    .put("image", e.image).put("episode", e.episode).put("time", e.time)
                    .put("pos", e.pos).put("dur", e.dur).put("posEp", e.posEpisode).put("posAt", e.posAt).put("done", e.done));
        } catch (JSONException ignored) { }
        App.prefs.edit().putString("history", a.toString()).apply();
    }

    /** The name Continue watching shows for a page title: "Watch Verity (2026) S1 E2 | Site" -> "Verity". "" when it isn't one. */
    static String titleOf(String rawTitle, String site) {
        String title = cleanTitle(rawTitle, site);
        title = EP_TEXT.matcher(title).replaceAll("").replaceAll("[\\s:,|–-]+$", "").trim();
        title = title.replaceAll("\\s*[(\\[]\\d{4}[)\\]]$", "").trim();   // the watch page often adds the year: "Verity (2026)"
        return title.length() < 2 || title.equalsIgnoreCase(site) || GENERIC.matcher(title).matches() ? "" : title;
    }

    /** Titles that are the same show: case, accents, punctuation and a year in brackets don't count. */
    static String same(String title) {
        String t = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(java.util.Locale.ROOT);
        return t.replaceAll("[(\\[]\\d{4}[)\\]]", " ").replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** The entry for a page title on a site, null if there is none. */
    static Entry find(List<Entry> list, String site, String title) {
        String key = same(title);
        for (Entry x : list) if (x.site.equals(site) && same(x.title).equals(key)) return x;
        return null;
    }

    /** Records a page if it looks like a movie/episode/stream page; the same title on the same site (another server, a year added) updates in place. */
    static void record(String site, String siteUrl, String url, String rawTitle, String image) {
        if (!isContent(url) || rawTitle == null) return;
        String title = titleOf(rawTitle, site);
        String episode = episode(url, rawTitle);
        if (title.isEmpty()) return;

        List<Entry> list = list();
        Entry e = find(list, site, title);
        if (e == null) {
            e = new Entry();
            e.site = site;
            e.title = title;
            e.image = "";
        } else {
            // Older versions kept "Verity" and "Verity (2026)" apart: fold every copy into this one
            String key = same(title);
            list.removeIf(x -> x.site.equals(site) && same(x.title).equals(key));
        }
        e.title = title;   // the clean name, also for an entry an older version saved as "Verity (2026)"
        e.siteUrl = siteUrl;
        e.url = url;
        // TMDB "original" files are huge; w780 is plenty for a card
        if (image != null && isTmdb(image)) image = image.replaceFirst("/t/p/[^/]+/", "/t/p/w780/");
        // First good poster sticks; a TMDB poster may replace a non-TMDB one, never the other way round
        if (image != null && !image.isEmpty() && (e.image.isEmpty() || (isTmdb(image) && !isTmdb(e.image)))) e.image = image;
        if (!episode.isEmpty() || e.episode == null) e.episode = episode;
        e.time = System.currentTimeMillis();
        list.add(0, e);
        save(list);
    }

    /** Remembers where the player is in this page's title, so another stream of it (a new server, tomorrow) carries on there. */
    static void savePosition(String site, String rawTitle, String url, long pos, long dur) {
        if (site == null || rawTitle == null) return;
        List<Entry> list = list();
        Entry e = find(list, site, titleOf(rawTitle, site));
        if (e == null) return;
        boolean done = dur > 0 && pos > dur - 120000;   // the credits: next time starts over
        e.pos = done || pos < 10000 ? 0 : pos;
        e.done = done;
        e.dur = dur;
        e.posEpisode = episode(String.valueOf(url), rawTitle);
        e.posAt = System.currentTimeMillis();
        save(list);
    }

    /**
     * Where to start this page's title, on whatever site it was last watched: the newest spot saved for the same title and episode.
     * 0 for the beginning (nothing saved, watched to the end, or only another episode saved).
     */
    static long position(String site, String rawTitle, String url) {
        if (site == null || rawTitle == null) return 0;
        String key = same(titleOf(rawTitle, site)), ep = episode(String.valueOf(url), rawTitle);
        if (key.isEmpty()) return 0;
        Entry best = null;
        for (Entry e : list())
            if (e.posAt > 0 && e.posEpisode.equals(ep) && same(e.title).equals(key) && (best == null || e.posAt > best.posAt)) best = e;
        return best == null ? 0 : best.pos;
    }

    /**
     * The episode to start a series from: where it was last watched (on any site; `list` is newest first), or the next one when that was
     * watched to the end. {season, number} out of `episodes` (the same pairs); null when it was never watched or that episode is not in them.
     */
    static int[] resume(List<Entry> list, String title, List<int[]> episodes) {
        String key = same(title);
        for (Entry e : list) {
            if (e.episode == null || !same(e.title).equals(key)) continue;
            Matcher m = SEASON_EP.matcher(e.episode);
            if (!m.find()) continue;
            int season = m.group(1) == null ? 0 : Integer.parseInt(m.group(1)), number = Integer.parseInt(m.group(2));
            int[] at = null;   // no season in the page's own words: the first season that has that number
            for (int[] x : episodes) if (x[1] == number && (season == 0 ? at == null || x[0] < at[0] : x[0] == season)) at = x;
            if (at == null) return null;
            if (e.done && e.posEpisode.equals(e.episode)) {
                int[] next = null;
                for (int[] x : episodes) if ((x[0] > at[0] || x[0] == at[0] && x[1] > at[1]) && (next == null || x[0] < next[0] || x[0] == next[0] && x[1] < next[1])) next = x;
                if (next != null) return next;
            }
            return at;
        }
        return null;
    }

    static final Pattern SEASON_EP = Pattern.compile("(?:S(\\d+)\\s*)?E(\\d+)");   // "S2 E4" or "E7", as episode() writes it

    static boolean isTmdb(String url) {
        return url.contains("image.tmdb.org") || url.contains("/t/p/");
    }

    static void remove(Entry e) {
        List<Entry> list = list();
        list.removeIf(x -> x.site.equals(e.site) && x.title.equals(e.title));
        save(list);
    }

    /** "Watch Breaking Bad Online Free | SiteName" -> "Breaking Bad" */
    static String cleanTitle(String t, String site) {
        String best = "";
        for (String part : t.split("\\s+[|–—•·-]\\s+")) {
            String p = part.trim();
            if (p.isEmpty() || p.toLowerCase().contains(site.toLowerCase())) continue;
            best = p;
            break;
        }
        best = best.replaceFirst("(?i)^(watch|stream)\\s+", "");
        return JUNK_TAIL.matcher(best).replaceFirst("").trim();
    }

    /** The episode number a page says it is (its address, else its title); 0 when it does not say. */
    static int episodeNumber(String url, String title) {
        Matcher m = Pattern.compile("E(\\d+)").matcher(episode(String.valueOf(url), String.valueOf(title)));
        int n = 0;
        while (m.find()) n = Integer.parseInt(m.group(1));
        return n;
    }

    /**
     * The same address for another episode, for a page whose address spells the episode out: ".../anime-1535-episode-3" with 3 and 4
     * gives ".../anime-1535-episode-4". The last "episode / ep / e" followed by that number is the one swapped (zero padding kept).
     * Null when the address does not say it.
     */
    static String episodeUrl(String url, int from, int to) {
        if (url == null || from <= 0 || to <= 0 || from == to) return null;
        Matcher m = Pattern.compile("(?i)((?:episode|ep|e)[-_/ ]?)(0*)" + from + "(?!\\d)").matcher(url);
        int s = -1, e = -1;
        String lead = "";
        int width = 0;
        while (m.find()) { s = m.start(); e = m.end(); lead = m.group(1); width = m.group(2).length() + String.valueOf(from).length(); }
        if (s < 0) return null;
        String n = String.valueOf(to);
        while (n.length() < width) n = "0" + n;
        return url.substring(0, s) + lead + n + url.substring(e);
    }

    static String episode(String url, String title) {
        for (Pattern p : EP_URL) {
            Matcher m = p.matcher(url);
            if (m.find()) return "S" + Integer.parseInt(m.group(1)) + " E" + Integer.parseInt(m.group(2));
        }
        Matcher t = EP_TEXT.matcher(title);
        if (t.find()) {
            if (t.group(1) != null) return "S" + Integer.parseInt(t.group(1)) + " E" + Integer.parseInt(t.group(2));
            if (t.group(3) != null) return "S" + Integer.parseInt(t.group(3)) + " E" + Integer.parseInt(t.group(4));
            return "E" + Integer.parseInt(t.group(5));
        }
        Matcher u = EP_ONLY_URL.matcher(url);
        return u.find() ? "E" + Integer.parseInt(u.group(1)) : "";
    }
}
