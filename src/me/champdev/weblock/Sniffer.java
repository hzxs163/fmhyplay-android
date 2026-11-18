package me.champdev.weblock;

import android.net.Uri;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Spots the video stream a page starts (HLS/DASH manifest, subtitle files) from the WebView's requests, so it can play natively. */
final class Sniffer {
    static final class Hit {
        final String url;
        final boolean dash, master;
        final Map<String, String> headers;

        Hit(String url, boolean dash, Map<String, String> headers) {
            this.url = url;
            this.dash = dash;
            // by the file's name, not the whole address: a show called "MasterChef" in the folder made every quality a "master"
            String p = path(url);
            this.master = !dash && p.substring(p.lastIndexOf('/') + 1).contains("master");
            this.headers = headers == null ? Collections.emptyMap() : new HashMap<>(headers);
        }
    }

    private final List<Hit> manifests = new CopyOnWriteArrayList<>();
    private final List<String> subs = new CopyOnWriteArrayList<>();
    private final Runnable onSeen, onFound;
    private volatile boolean armed = true, pending;
    private volatile String handed = "";   // folder of the stream last given to the player
    private String key = "";
    // The stream being left behind when another episode is picked: its requests must not be mistaken for the new one
    private final List<String> stale = new CopyOnWriteArrayList<>();
    private volatile long staleAt;

    /** @param onSeen runs on the main thread when a stream first shows up; @param onFound runs there shortly after, to hand it over */
    Sniffer(Runnable onSeen, Runnable onFound) {
        this.onSeen = onSeen;
        this.onFound = onFound;
    }

    /**
     * A new page (host or path changed) starts over; the same page keeps what it has. Main thread.
     * After a hand-off the site's player is still alive while the next episode loads, so the stream just watched stays ignored until a different one shows up.
     */
    void page(String url) {
        String k = path(url);
        if (k.equals(key)) return;
        key = k;
        if (handed.isEmpty()) fresh(); else switching();
    }

    /** Forget what was seen and watch for the next stream (new page, or another server picked). */
    void fresh() {
        manifests.clear();
        subs.clear();
        armed = true;
        handed = "";
    }

    /** Another episode is coming: forget the current stream but ignore it (its playlists, its folder) until a different one shows up. */
    void switching() {
        stale.clear();
        for (Hit h : manifests) stale.add(path(h.url));
        if (!handed.isEmpty()) stale.add(handed);
        staleAt = System.currentTimeMillis();
        fresh();
    }

    private boolean isStale(String url) {
        if (stale.isEmpty()) return false;
        if (System.currentTimeMillis() - staleAt > 90000) { stale.clear(); return false; }
        String p = path(url);
        for (String s : stale) {
            if (!s.endsWith("/")) { if (s.equals(p)) return true; }
            // a folder counts only when it is a directory or more below the host (a host root would block every stream there)
            else if (p.startsWith(s) && s.indexOf('/', s.indexOf("//") + 2) < s.length() - 1) return true;
        }
        return false;
    }

    /** This stream went to the player: stay quiet about it (and its own variants) until a different one shows up. */
    void handedOff(Hit h) {
        stale.clear();
        armed = false;
        handed = folder(h.url);
    }

    // Same host and one folder inside the other = the same stream's playlists; anything else is another server
    private static boolean related(String a, String b) {
        return a.startsWith(b) || b.startsWith(a);
    }

    static String folder(String url) {
        String p = path(url);
        return p.substring(0, p.lastIndexOf('/') + 1);
    }

    /** Any WebView thread. */
    // A site's own clip before the video (one site's 12 s "/promo/" bumper): never the stream to hand over
    static final java.util.regex.Pattern PROMO = java.util.regex.Pattern.compile("/(promo|promos|preroll|pre-roll|bumper|bumpers)/");

    void request(String url, Map<String, String> headers) {
        String p = path(url);
        boolean hls = p.endsWith(".m3u8"), dash = p.endsWith(".mpd"), flv = p.endsWith(".flv");   // FLV: live sports pages (mpegts.js)
        if (hls || dash || flv) manifest(url, dash, headers);
        else if ((p.endsWith(".vtt") || p.endsWith(".srt")) && subs.size() < 8 && !subs.contains(url)) subs.add(url);
    }

    /** A playlist the page downloaded under a URL with no .m3u8 in it (found by its first bytes). Any thread. */
    void manifest(String url, boolean dash, Map<String, String> headers) {
        if (PROMO.matcher(path(url)).find()) return;
        String host = Uri.parse(url).getHost();
        if (host == null || AdBlock.blocked(host) || manifests.size() >= 20) return;
        last = new Hit(url, dash, headers);
        if (isStale(url)) return;
        for (Hit h : manifests) if (h.url.equals(url)) return;
        // After Back, a stream from somewhere else (the user picked another server on the page) goes native again
        if (!armed && !handed.isEmpty() && !related(handed, folder(url))) fresh();
        manifests.add(new Hit(url, dash, headers));
        // Wait a moment: a master playlist usually follows the variant that was asked for first
        if (armed && !pending) {
            pending = true;
            App.main.post(onSeen);
            App.main.postDelayed(() -> {
                pending = false;
                if (armed) onFound.run();
            }, 700);
        }
    }

    boolean has() {
        return !manifests.isEmpty();
    }

    private volatile Hit last;   // the newest stream the page asked for, even one held back as the old one

    /** What the page plays right now, also when it was held back (a switch that did not happen): for the AnantPlayer button. */
    Hit lastSeen() {
        Hit b = best();
        return b != null ? b : last;
    }

    /** A switch that did not happen: the stream the page still plays is the one to hand over again. */
    void unswitch() {
        stale.clear();
        Hit h = last;
        fresh();
        if (h != null) manifest(h.url, h.dash, h.headers);
    }

    /** HLS before DASH; among HLS the newest master playlist, else the first one asked for. */
    Hit best() {
        Hit first = null, master = null, dash = null;
        for (Hit h : manifests) {
            if (h.dash) { if (dash == null) dash = h; continue; }
            if (first == null) first = h;
            if (h.master) master = h;
        }
        return master != null ? master : first != null ? first : dash;
    }

    List<String> subtitles() {
        return subs;
    }

    // A proxy carries the real address in its query (/proxy/hls?url=https%3A%2F%2Fcdn/.../master.m3u8): that address is the stream
    static final java.util.regex.Pattern CARRIED = java.util.regex.Pattern.compile("[?&][^=&]+=(https?(?::|%3A)[^&]+)", java.util.regex.Pattern.CASE_INSENSITIVE);

    static final java.util.regex.Pattern MEDIA = java.util.regex.Pattern.compile("(?i)\\.(m3u8|mpd|flv|vtt|srt)$");

    static String path(String url) {
        java.util.regex.Matcher m = CARRIED.matcher(url);
        // not when the address itself is the stream (master.m3u8?ref=https://site): that query only says where it was asked from
        if (!MEDIA.matcher(plain(url)).find() && m.find()) {
            try {
                String inner = java.net.URLDecoder.decode(m.group(1), "UTF-8");
                if (inner.startsWith("http")) url = inner;
            } catch (Exception ignored) { }
        }
        return plain(url).toLowerCase(java.util.Locale.ROOT);
    }

    /** The address without its query and fragment. */
    static String plain(String url) {
        int q = url.length();
        for (char c : new char[]{'?', '#'}) {
            int i = url.indexOf(c);
            if (i >= 0 && i < q) q = i;
        }
        return url.substring(0, q);
    }
}
