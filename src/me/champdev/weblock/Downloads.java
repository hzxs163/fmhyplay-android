package me.champdev.weblock;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The download manager. A download is the stream the player is on: a plain file (fetched in parallel byte ranges) or an HLS playlist
 * (its segments fetched in parallel, written in order). Several connections per download (Settings, Downloads), pause and resume,
 * saved in the prefs so a list survives a restart. The foreground service (DownloadService) keeps the process alive and shows progress.
 */
final class Downloads {
    static final String TAG = "Downloads";
    static final int DEFAULT_CONNS = 4, MAX_CONNS = 8, PARALLEL = 2;   // PARALLEL: downloads running at once, the rest wait

    static final class Item {
        String id, name, url, headers = "{}", kind = "", status = "queued", error = "", ext = "", out = "", path = "";   // path: where the file is, to show
        long total, done, created;           // total: bytes (an estimate for HLS)
        int segs, segDone;                   // HLS: segments in all / written
        int asegs, aSegDone;                 // HLS with a separate audio playlist: its segments in all / written
        long aDone;                          // ... and the bytes of them written
        long[] from = {}, pos = {}, to = {}; // a plain file: each connection's first byte, next byte and last byte (-1: until the end)
        long[] sizes = {};                   // fMP4 HLS: the bytes of each segment written so far (for the seek index)
        long pts0, sidxAt;                   // the first fragment's start time, and where the index box goes in the file
        int track, scale;                    // the video track's id and timescale (scale 0: no index for this download)
        String src = "";                     // kind "convert": the finished .ts being made into an MP4 (`out` is the MP4 meanwhile)
        String note = "";                    // why a .ts could not be made an MP4 (shown under it)
        volatile boolean joining;            // the pieces are being joined into the MP4 (the last per cent)
        volatile boolean stop;
        volatile int conns;
        long speed, lastDone, lastAt;
        Thread thread;

        long doneBytes() {
            if (!kind.equals("file")) return done + aDone;
            long s = 0;
            for (int i = 0; i < pos.length; i++) s += pos[i] - from[i];
            return s;
        }

        int percent() {
            if (kind.equals("file")) return total > 0 ? (int) Math.min(100, doneBytes() * 100 / total) : 0;
            int p = segs > 0 ? (segDone + aSegDone) * 100 / (segs + asegs) : 0;
            return (asegs > 0 || joining) && !status.equals("done") ? Math.min(p, 99) : p;   // the last per cent is the join into the MP4
        }

        boolean active() {
            return status.equals("running") || status.equals("queued");
        }
    }

    static final class Stopped extends IOException {
        Stopped() { super("stopped"); }
    }

    /** A stream that cannot be downloaded, with the string that says why (turned into words where it is shown, so a test needs no Android). */
    static final class Refused extends IOException {
        final int res;
        Refused(int res) { super("refused " + res); this.res = res; }
    }

    static final List<Item> items = new CopyOnWriteArrayList<>();
    static volatile int version;   // goes up whenever the list or a status changes, so the screen knows to redraw
    private static boolean loaded, ticking;
    private static int saveTick;
    private static final Handler ui = new Handler(Looper.getMainLooper());

    // ---- Settings ----

    static int connections() {
        return Math.max(1, Math.min(MAX_CONNS, App.prefs.getInt("dlConns", DEFAULT_CONNS)));
    }

    /** Where files go: a folder under Download, Movies or Documents (shown in the file manager and the gallery). */
    static String folder() {
        return App.prefs.getString("dlFolder", "Download/AnantPlay");
    }

    /** The device's own storage as a path (/storage/emulated/0): what the folders below it are really called on disk. */
    static String root() {
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    /** The folder with its whole path, for the screen: /storage/emulated/0/Download/AnantPlay/ */
    static String folderPath() {
        return root() + "/" + folder().replaceAll("^/+|/+$", "") + "/";
    }

    // ---- The list ----

    static synchronized List<Item> list() {
        if (!loaded) {
            loaded = true;
            try {
                JSONArray a = new JSONArray(App.prefs.getString("downloads", "[]"));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    Item it = new Item();
                    it.id = o.getString("id");
                    it.name = o.getString("name");
                    it.url = o.getString("url");
                    it.headers = o.optString("headers", "{}");
                    it.kind = o.optString("kind");
                    it.status = o.optString("status");
                    it.error = o.optString("error");
                    it.ext = o.optString("ext");
                    it.out = o.optString("out");
                    it.path = o.optString("path");
                    it.total = o.optLong("total");
                    it.done = o.optLong("done");
                    it.created = o.optLong("created");
                    it.segs = o.optInt("segs");
                    it.segDone = o.optInt("segDone");
                    it.asegs = o.optInt("asegs");
                    it.aSegDone = o.optInt("aSegDone");
                    it.aDone = o.optLong("aDone");
                    it.from = longs(o.optJSONArray("from"));
                    it.pos = longs(o.optJSONArray("pos"));
                    it.to = longs(o.optJSONArray("to"));
                    it.sizes = longs(o.optJSONArray("sizes"));
                    it.pts0 = o.optLong("pts0");
                    it.sidxAt = o.optLong("sidxAt");
                    it.track = o.optInt("track");
                    it.scale = o.optInt("scale");
                    it.src = o.optString("src");
                    it.note = o.optString("note");
                    if (it.active()) it.status = "paused";   // the app was closed under it: it can be resumed, from where it was
                    items.add(it);
                }
            } catch (JSONException ignored) { }
        }
        return items;
    }

    private static long[] longs(JSONArray a) {
        long[] out = new long[a == null ? 0 : a.length()];
        for (int i = 0; i < out.length; i++) out[i] = a.optLong(i);
        return out;
    }

    private static JSONArray array(long[] v) {
        JSONArray a = new JSONArray();
        for (long x : v) a.put(x);
        return a;
    }

    static synchronized void save() {
        JSONArray a = new JSONArray();
        try {
            for (Item it : items) {
                a.put(new JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("headers", it.headers).put("kind", it.kind)
                    .put("status", it.status).put("error", it.error).put("ext", it.ext).put("out", it.out).put("path", it.path).put("total", it.total).put("done", it.done)
                    .put("created", it.created).put("segs", it.segs).put("segDone", it.segDone).put("asegs", it.asegs).put("aSegDone", it.aSegDone).put("aDone", it.aDone)
                    .put("from", array(it.from)).put("pos", array(it.pos)).put("to", array(it.to))
                    .put("sizes", array(it.sizes)).put("pts0", it.pts0).put("sidxAt", it.sidxAt).put("track", it.track).put("scale", it.scale).put("src", it.src).put("note", it.note));
            }
        } catch (JSONException ignored) { }
        App.prefs.edit().putString("downloads", a.toString()).apply();
    }

    static int active() {
        int n = 0;
        for (Item it : list()) if (it.active()) n++;
        return n;
    }

    // ---- Actions ----

    /** What is known about a stream before it is downloaded: its file extension and, from the best quality in its playlist, the picture height. */
    static final class Plan {
        String ext = "ts";
        int height;
        String error;   // why it cannot be downloaded (no sound, live, protected): said instead of the question, nothing is started
    }

    /** Looks at the stream's playlists (a request or two): so the file name and extension shown for confirmation are the ones that will be written. Call off the main thread. */
    static Plan plan(String url, String headers, boolean dash) {
        Plan p = new Plan();
        p.ext = expectedExt(url, dash);
        if (!p.ext.equals("ts") && !dash) return p;   // a plain file's extension is in its address
        Item look = new Item();
        look.url = url;
        look.headers = headers == null || headers.isEmpty() ? "{}" : headers;
        try {
            String text = fetchText(look, url);
            if (dash || text.contains("<MPD")) {
                p.ext = "mp4";
                Dash.Pick d = Dash.pick(text, url, audioLang());
                p.height = d.height;
                if (d.audio == null && !hasSound(look, d.video)) p.error = App.t(R.string.dl_no_sound);
                return p;
            }
            if (!text.startsWith("#EXTM3U")) return p;
            p.ext = "mp4";   // every playlist ends as an MP4: fMP4 pieces put together, picture and sound joined, MPEG-TS pieces converted
            String base = url;
            if (text.contains("#EXT-X-STREAM-INF")) {
                String[] best = Hls.bestVariant(text);
                if (best == null) return p;
                p.height = Integer.parseInt(best[1]);
                if (audioUri(text, best[2]) != null) return p;   // the sound is a playlist of its own
                if (best[3] != null && !Hls.audible(best[3])) { p.error = App.t(R.string.dl_no_sound); return p; }   // the playlist says what is inside
                base = Hls.resolve(url, best[0]);
                text = fetchText(look, base);
            }
            if (!hasSound(look, Hls.track(text, base))) p.error = App.t(R.string.dl_no_sound);
        } catch (Refused e) {
            p.error = App.t(e.res);   // live, protected: said at once
        } catch (Exception e) {
            Log.w(TAG, "plan " + url, e);   // not known: the download itself says what goes wrong
        }
        return p;
    }

    /**
     * The file name without its extension, scene style: the words joined by dots, then the picture height and the app's mark
     * (Crawlers.2024.1080p.AnantPlay). Dots instead of spaces, nothing a file system dislikes.
     */
    static String fileBase(String name, int height) {
        String base = name == null ? "" : name.replaceAll("['\u2019`]", "").replaceAll("[^\\p{L}\\p{N}]+", ".").replaceAll("^\\.+|\\.+$", "");
        if (base.length() > 110) base = base.substring(0, 110).replaceAll("\\.+$", "");
        if (base.isEmpty()) base = "video";
        return base + (height > 0 ? "." + height + "p" : "") + ".AnantPlay";
    }

    /** Adds the stream to the list and starts it (or queues it behind the ones running). `headers` is the player's JSON of request headers. */
    static Item enqueue(String url, String headers, String name, boolean dash, Plan plan) {
        list();
        Item it = new Item();
        it.id = Long.toString(System.nanoTime(), 36);
        it.name = safeName(name);   // the caller built it (fileBase) or the user typed it
        it.ext = plan.ext;
        it.url = url;
        it.headers = headers == null || headers.isEmpty() ? "{}" : headers;
        it.created = System.currentTimeMillis();
        it.kind = dash ? "dash" : Sniffer.path(url).toLowerCase(Locale.ROOT).matches(".*\\.(mp4|mkv|webm|flv|avi|mov|m4v)") ? "file" : "hls";
        items.add(0, it);
        version++;
        save();
        pump();
        return it;
    }

    static void pause(Item it) {
        it.stop = true;
        if (it.status.equals("queued")) it.status = "paused";   // a running one says so itself once its connections have stopped
        version++;
        save();
    }

    static void resume(Item it) {
        if (!it.status.equals("paused") && !it.status.equals("failed")) return;
        it.stop = false;
        it.error = "";
        it.status = "queued";
        version++;
        save();
        pump();
    }

    /** Takes it off the list; with deleteFile the saved (or half-saved) file goes too. */
    static void remove(Item it, boolean deleteFile) {
        it.stop = true;
        items.remove(it);
        version++;
        save();
        if (deleteFile) App.io.execute(() -> {
            try { if (it.thread != null) it.thread.join(4000); } catch (InterruptedException ignored) { }
            Sink.delete(it);
        });
        pump();
    }

    /** Starts waiting downloads while fewer than PARALLEL are running. */
    static void pump() {
        int running = 0;
        for (Item it : list()) if (it.status.equals("running")) running++;
        for (Item it : list()) {
            if (running >= PARALLEL) break;
            if (!it.status.equals("queued")) continue;
            it.status = "running";
            it.stop = false;
            it.lastDone = it.doneBytes();
            it.lastAt = SystemClock.elapsedRealtime();
            running++;
            version++;
            DownloadService.start(App.app);
            it.thread = new Thread(() -> run(it), "download-" + it.id);
            it.thread.start();
        }
        if (running > 0 && !ticking) { ticking = true; ui.postDelayed(Downloads::tick, 1000); }
    }

    /** Every second while something runs: speeds, the notification, and the list saved every few seconds. */
    private static void tick() {
        long now = SystemClock.elapsedRealtime();
        int running = 0, pct = 0;
        long speed = 0;
        String name = "";
        for (Item it : items) {
            if (!it.status.equals("running")) continue;
            long d = it.doneBytes();
            long instant = Math.max(0, (d - it.lastDone) * 1000 / Math.max(1, now - it.lastAt));
            it.speed = (it.speed * 2 + instant) / 3;   // smoothed: an HLS segment counts only once it is whole, so the raw number jumps
            it.lastDone = d;
            it.lastAt = now;
            running++;
            speed += it.speed;
            pct += it.percent();
            name = it.name;
        }
        if (running == 0) { ticking = false; return; }
        if (++saveTick % 3 == 0) save();
        String rate = android.text.format.Formatter.formatFileSize(App.app, speed) + "/s";
        DownloadService.update(pct / running, running == 1 ? name : App.t(R.string.dl_notify_many, running), pct / running + "% · " + rate);
        ui.postDelayed(Downloads::tick, 1000);
    }

    private static void run(Item it) {
        try {
            boolean conv = it.kind.equals("convert");
            if (conv) convert(it);
            else if (it.kind.equals("dash")) dash(it, fetchText(it, it.url));
            else if (it.kind.equals("hls")) hls(it);
            else file(it);
            it.status = "done";
            it.error = "";
            if (!conv || it.ext.equals("mp4")) DownloadService.finished(it.name);   // Make seekable that kept the .ts: nothing new was finished
        } catch (Stopped e) {
            if (items.contains(it)) it.status = "paused";
        } catch (Exception e) {
            Log.w(TAG, it.name, e);
            it.status = "failed";
            it.error = e instanceof Refused ? App.t(((Refused) e).res) : String.valueOf(e.getMessage());
        } finally {
            it.speed = 0;
            it.conns = 0;
            version++;
            save();
            ui.post(Downloads::pump);
            ui.postDelayed(DownloadService::stopIfIdle, 500);
        }
    }

    // ---- A plain file: parallel byte ranges ----

    private static void probe(Item it) throws IOException {
        HttpURLConnection c = connect(it, it.url, 0, -1);   // "Range: bytes=0-": a server that knows ranges answers 206 with the whole size
        try {
            int code = c.getResponseCode();
            long len = -1;
            boolean ranges = false;
            if (code == 206) {
                len = rangeTotal(c);
                ranges = len > 0;
            } else if (code == 200) {
                len = c.getContentLengthLong();
            } else {
                throw new IOException("HTTP " + code);
            }
            Matcher m = Pattern.compile("\\.(mp4|mkv|webm|flv|avi|mov|m4v)$").matcher(Sniffer.path(it.url).toLowerCase(Locale.ROOT));
            it.ext = m.find() ? m.group(1) : "mp4";
            it.total = Math.max(len, 0);
            int n = ranges && len > 4L << 20 ? (int) Math.min(connections(), len >> 20) : 1;
            it.from = new long[n];
            it.pos = new long[n];
            it.to = new long[n];
            for (int i = 0; i < n; i++) {
                it.from[i] = it.pos[i] = len / n * i;
                it.to[i] = n == 1 && !ranges ? -1 : i == n - 1 ? len - 1 : len / n * (i + 1) - 1;
            }
        } finally {
            c.disconnect();
        }
    }

    private static void file(Item it) throws Exception {
        if (it.pos.length == 0) probe(it);
        Sink sink = Sink.open(it);
        try {
            int n = it.pos.length;
            Thread[] workers = new Thread[n];
            Throwable[] failed = new Throwable[1];
            it.conns = 0;
            for (int p = 0; p < n; p++) {
                if (it.to[p] >= 0 && it.pos[p] > it.to[p]) continue;   // this part is already complete
                final int part = p;
                it.conns++;
                workers[p] = new Thread(() -> {
                    try {
                        part(it, sink, part, failed);
                    } catch (Throwable e) {
                        synchronized (failed) { if (failed[0] == null) failed[0] = e; }
                    }
                    it.conns--;
                }, "download-" + it.id + "-" + p);
                workers[p].start();
            }
            for (Thread w : workers) if (w != null) w.join();
            if (it.stop) throw new Stopped();
            if (failed[0] != null) throw failed[0] instanceof Exception ? (Exception) failed[0] : new IOException(failed[0]);
            sink.finish();
        } finally {
            sink.close();
        }
    }

    private static void part(Item it, Sink sink, int p, Throwable[] failed) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int tries = 0;
        while (true) {
            if (it.stop || failed[0] != null) throw new Stopped();
            long from = it.pos[p], to = it.to[p];
            if (to >= 0 && from > to) return;
            boolean ranged = from > 0 || it.pos.length > 1;
            HttpURLConnection c = null;
            try {
                c = connect(it, it.url, ranged ? from : -1, to);
                int code = c.getResponseCode();
                if (ranged ? code != 206 : code != 200 && code != 206) throw new IOException("HTTP " + code + (ranged ? " (the site does not allow ranges)" : ""));
                InputStream in = c.getInputStream();
                int r;
                while ((r = in.read(buf)) > 0) {
                    if (it.stop || failed[0] != null) throw new Stopped();
                    int len = to >= 0 ? (int) Math.min(r, to - it.pos[p] + 1) : r;   // never past this part's end
                    if (len <= 0) break;
                    Sink.write(sink.ch, ByteBuffer.wrap(buf, 0, len), it.pos[p]);
                    it.pos[p] += len;
                }
                if (to < 0) { it.to[p] = it.pos[p] - 1; it.total = it.pos[p]; return; }   // no size given: the end of the stream is the end
                if (it.pos[p] > to) return;
                throw new IOException("the connection closed early");
            } catch (Stopped e) {
                throw e;
            } catch (IOException e) {
                if (++tries > 6) throw e;
                nap(800L * tries, it);
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }

    // ---- HLS: segments in parallel, written in order ----

    static final class Seg {
        String url, key;
        byte[] iv;
        double dur;           // seconds, from its #EXTINF (HLS) or the manifest (DASH)
        long from = -1, len;  // a piece that is a range of a bigger file: its first byte and its length (from -1: the whole of url)
    }

    private static void hls(Item it) throws Exception {
        String text = fetchText(it, it.url);
        if (text.contains("<MPD")) { it.kind = "dash"; dash(it, text); return; }   // a DASH manifest after all
        if (!text.startsWith("#EXTM3U")) { it.kind = "file"; file(it); return; }   // not a playlist after all: a plain file
        String base = it.url, audioUrl = null;
        if (text.contains("#EXT-X-STREAM-INF")) {   // the main playlist: the best quality
            String[] best = Hls.bestVariant(text);
            if (best == null) throw new IOException("an empty playlist");
            String au = audioUri(text, best[2]);   // the sound in a playlist of its own, not in the pieces of the picture
            if (au != null) audioUrl = Hls.resolve(base, au);
            base = Hls.resolve(base, best[0]);
            text = fetchText(it, base);
        }
        Track vt = Hls.track(text, base);
        Track at = audioUrl != null ? Hls.track(fetchText(it, audioUrl), audioUrl) : null;
        fetchTracks(it, vt, at);
    }

    /**
     * Whether the picture's own pieces carry sound: the init segment of fMP4 has a sound track, or the stream table at the start of the first
     * MPEG-TS piece lists one. True when it cannot be told (one whole file, an unreadable start): only a stream known to be silent is refused.
     */
    private static boolean hasSound(Item it, Track vt) throws Exception {
        if (vt.whole) return true;
        if (vt.map != null) return Hls.mp4Audible(get(it, vt.map, vt.mapFrom, vt.mapLen));
        return Hls.tsAudible(segment(it, vt.segs.get(0), new ConcurrentHashMap<>()));
    }

    static final long CHUNK = 1L << 20;   // small: up to connections() * 2 pieces wait in memory per download, on TVs with a small heap

    /** A track that is one whole file is cut into ranges of 1 MB, so it is fetched ahead, written in order and resumed like the pieces of a playlist. */
    private static void cut(Item it, Track t) throws IOException {
        if (!t.whole) return;
        String url = t.segs.get(0).url;
        long size = size(it, url);
        t.segs.clear();
        for (long from = 0; from < size; from += CHUNK) {
            Seg s = new Seg();
            s.url = url;
            s.from = from;
            s.len = Math.min(CHUNK, size - from);
            t.segs.add(s);
        }
        t.whole = false;
    }

    /** The size of a file, asked for as its first byte (the answer carries it); tried again on a failure like any other request. */
    private static long size(Item it, String url) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            if (it.stop) throw new Stopped();
            HttpURLConnection c = null;
            try {
                c = connect(it, url, 0, 0);
                long total = c.getResponseCode() == 206 ? rangeTotal(c) : -1;
                if (total < 0) throw new IOException("HTTP " + c.getResponseCode() + " (the site does not allow ranges)");
                return total;
            } catch (Stopped e) {
                throw e;
            } catch (IOException e) {
                last = e;
                nap(600L * (attempt + 1), it);
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw last;
    }

    /** The whole size a 206 answer carries ("Content-Range: bytes 0-0/12345"); -1 when it does not say. */
    private static long rangeTotal(HttpURLConnection c) {
        String cr = c.getHeaderField("Content-Range");
        int slash = cr == null ? -1 : cr.lastIndexOf('/');
        try {
            return slash > 0 ? Long.parseLong(cr.substring(slash + 1).trim()) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The sound language chosen in Settings (Player, Audio language) for a stream that has several; null for the stream's own. */
    static String audioLang() {
        String p = App.prefs.getString("audioLang", "");
        return p.isEmpty() ? Locale.getDefault().getLanguage() : p.equals(PlayerActivity.ORIGINAL) ? null : p;
    }

    /** DASH: the manifest read down to the best picture and the sound, then fetched like the pieces of an HLS playlist. */
    private static void dash(Item it, String xml) throws Exception {
        Dash.Pick p = Dash.pick(xml, it.url, audioLang());
        fetchTracks(it, p.video, p.audio);
    }

    /** The picture's pieces (and the sound's, when it is a playlist of its own) fetched in parallel, written in order and made into the file. */
    private static void fetchTracks(Item it, Track vt, Track at) throws Exception {
        cut(it, vt);
        if (at != null) cut(it, at);
        List<Seg> segs = vt.segs;
        String map = vt.map;
        it.segs = segs.size();
        it.asegs = at == null ? 0 : at.segs.size();
        // fMP4 pieces put back to back are one MP4. MPEG-TS pieces make a .ts, which has no seek table (a player works the length out from
        // timestamps, and a stream with odd ones gets no seek bar): written as the .ts, then made into an MP4 the way Make seekable does it
        if (at == null && it.segDone == 0 && !hasSound(it, vt)) throw new Refused(R.string.dl_no_sound);   // a picture with no sound is not downloaded
        boolean ts = at == null && vt.ts;
        File vfile = tmp(it, "v");
        it.ext = ts ? "ts" : "mp4";
        Sink sink = at != null ? Sink.open(vfile) : Sink.open(it);   // with a separate sound the picture waits in a file of its own until it is joined
        ExecutorService pool = Executors.newFixedThreadPool(connections());
        try {
            long pos = it.segDone == 0 ? 0 : it.done;
            if (it.segDone == 0 && map != null) {
                byte[] init = get(it, map, vt.mapFrom, vt.mapLen);
                Sink.write(sink.ch, ByteBuffer.wrap(init), 0);
                pos = init.length;
                int[] tr = segs.size() <= 65535 && at == null ? videoTrack(init) : null;
                if (tr != null) {
                    // Room for the seek index, filled in once every piece is here (until then a "free" box, which players skip). Pieces put
                    // back to back have no index, and a player can only get to the middle by reading from the start: very slow on a big movie.
                    it.track = tr[0];
                    it.scale = tr[1];
                    it.sidxAt = pos;
                    it.sizes = new long[segs.size()];
                    int room = 40 + 12 * segs.size();
                    ByteBuffer free = ByteBuffer.allocate(room);
                    free.putInt(room).put("free".getBytes(StandardCharsets.ISO_8859_1)).rewind();
                    Sink.write(sink.ch, free, pos);
                    pos += room;
                }
            }
            if (it.scale > 0 && it.sizes.length != segs.size()) it.scale = 0;   // the playlist changed under a resumed download: no index
            Map<String, byte[]> keys = new ConcurrentHashMap<>();
            ArrayDeque<Future<byte[]>> ahead = new ArrayDeque<>();
            int next = it.segDone;
            it.conns = connections();
            while (next < segs.size() || !ahead.isEmpty()) {
                if (it.stop) throw new Stopped();
                while (next < segs.size() && ahead.size() < connections() * 2) {
                    final Seg s = segs.get(next++);
                    ahead.add(pool.submit(() -> segment(it, s, keys)));
                }
                byte[] data;
                try {
                    data = ahead.poll().get();
                } catch (ExecutionException e) {
                    if (it.stop) throw new Stopped();
                    throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
                }
                Sink.write(sink.ch, ByteBuffer.wrap(data), pos);
                pos += data.length;
                it.done = pos;
                if (it.scale > 0) {
                    it.sizes[it.segDone] = data.length;
                    if (it.segDone == 0) it.pts0 = firstTfdt(data, it.track);
                }
                it.segDone++;
                it.total = it.done * it.segs / Math.max(1, it.segDone);   // an estimate until the last one
            }
            it.total = it.done;
            if (it.scale > 0) writeIndex(sink, it, segs);
            if (at != null) {   // the sound next, then the two are joined into the file
                File afile = tmp(it, "a");
                fetchAudio(it, at, afile);
                it.total = it.done + it.aDone;
                merge(it, vfile, afile);
                vfile.delete();
                afile.delete();
            }
            sink.finish();
        } finally {
            pool.shutdownNow();
            sink.close();
        }
        if (ts) {   // the .ts is whole and saved: now its MP4 (it stays the .ts, saying why, when that cannot be made)
            toConvert(it);
            save();
            convert(it);
        }
    }

    static final class Track {
        final List<Seg> segs = new ArrayList<>();
        String map;                  // the init segment of an fMP4 stream
        long mapFrom = -1, mapLen;   // ... when it is a range of a file
        boolean ts;                  // MPEG-TS pieces (no init segment): joined into an MP4 at the end
        boolean whole;               // one whole file (DASH "on demand": a BaseURL per quality), cut into ranges when it is fetched
    }


    /** The address (as written) of the audio playlist of a group in a main playlist: the default one, else the first. Null when the sound is in the picture's own pieces. */
    private static String audioUri(String text, String group) {
        if (group == null) return null;
        String first = null;
        for (String raw : text.split("\\r?\\n")) {
            String l = raw.trim();
            if (!l.startsWith("#EXT-X-MEDIA:") || !"AUDIO".equals(Hls.attr(l, "TYPE")) || !group.equals(Hls.attr(l, "GROUP-ID"))) continue;
            String uri = Hls.attr(l, "URI");
            if (uri == null) continue;
            if ("YES".equalsIgnoreCase(Hls.attr(l, "DEFAULT"))) return uri;
            if (first == null) first = uri;
        }
        return first;
    }

    /** Where a part of a download waits until it is whole (k: "v" the picture, "a" the sound): the app's own storage, not the cache the system empties. */
    private static File tmp(Item it, String k) {
        File base = App.app.getExternalFilesDir(null);
        File dir = new File(base != null ? base : App.app.getFilesDir(), "downloading");
        dir.mkdirs();
        return new File(dir, it.id + "." + k);
    }

    /** The sound of a stream whose sound is a playlist of its own, written to `f` the way the picture is (in order, N pieces ahead), and resumable. */
    private static void fetchAudio(Item it, Track t, File f) throws Exception {
        if (it.aSegDone >= t.segs.size()) return;
        Sink sink = Sink.open(f);
        ExecutorService pool = Executors.newFixedThreadPool(connections());
        try {
            long pos = it.aSegDone == 0 ? 0 : it.aDone;
            if (it.aSegDone == 0 && t.map != null) {
                byte[] init = get(it, t.map, t.mapFrom, t.mapLen);
                Sink.write(sink.ch, ByteBuffer.wrap(init), 0);
                pos = init.length;
            }
            Map<String, byte[]> keys = new ConcurrentHashMap<>();
            ArrayDeque<Future<byte[]>> ahead = new ArrayDeque<>();
            int next = it.aSegDone;
            while (next < t.segs.size() || !ahead.isEmpty()) {
                if (it.stop) throw new Stopped();
                while (next < t.segs.size() && ahead.size() < connections() * 2) {
                    final Seg s = t.segs.get(next++);
                    ahead.add(pool.submit(() -> segment(it, s, keys)));
                }
                byte[] data;
                try {
                    data = ahead.poll().get();
                } catch (ExecutionException e) {
                    if (it.stop) throw new Stopped();
                    throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
                }
                Sink.write(sink.ch, ByteBuffer.wrap(data), pos);
                pos += data.length;
                it.aDone = pos;
                it.aSegDone++;
            }
        } finally {
            pool.shutdownNow();
            sink.close();
        }
    }

    private static int trackOf(MediaExtractor e, String kind) {
        for (int i = 0; i < e.getTrackCount(); i++) {
            String mime = e.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(kind)) return i;
        }
        return -1;
    }

    /** Joins the picture and the sound into one MP4 (the download's file), samples copied as they are, in time order. Safe to run again: it starts the file over. */
    private static void merge(Item it, File video, File audio) throws Exception {
        MediaExtractor v = new MediaExtractor(), a = new MediaExtractor();
        try {
            v.setDataSource(video.getAbsolutePath());
            a.setDataSource(audio.getAbsolutePath());   // the same file for MPEG-TS pieces: one extractor takes the picture, the other the sound
            join(it, v, a, video.length() + (audio.equals(video) ? 0 : audio.length()));
        } finally {
            v.release();
            a.release();
        }
    }

    /** `v` gives the picture and `a` the sound (the same file twice for MPEG-TS); `size` is what they hold, for the progress of a "convert". */
    private static void join(Item it, MediaExtractor v, MediaExtractor a, long size) throws Exception {
        Sink out = Sink.open(it);
        MediaMuxer mux = null;
        boolean started = false;
        it.joining = true;
        try {
            int vt = trackOf(v, "video/"), at = trackOf(a, "audio/");
            if (vt < 0 || at < 0) throw new IOException(App.t(R.string.dl_merge_failed) + " (" + (vt < 0 ? "no picture" : "no sound") + " found: " + tracks(vt < 0 ? v : a) + ")");
            v.selectTrack(vt);
            a.selectTrack(at);
            if (v.getSampleTrackIndex() < 0 || a.getSampleTrackIndex() < 0) throw new IOException(App.t(R.string.dl_merge_failed) + " (no samples)");
            long vs = v.getSampleTime(), as = a.getSampleTime();   // a sound can start a little before 0 (AAC's encoder delay): only "no sample" means none
            long base = Math.min(vs, as);   // both start near 0, keeping the gap between them
            out.ch.truncate(0);
            mux = new MediaMuxer(out.fd(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vi, ai;
            try {
                vi = mux.addTrack(v.getTrackFormat(vt));
                ai = mux.addTrack(a.getTrackFormat(at));
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new IOException(App.t(R.string.dl_merge_failed) + " (an MP4 cannot hold " + v.getTrackFormat(vt).getString(MediaFormat.KEY_MIME) + " with "
                    + a.getTrackFormat(at).getString(MediaFormat.KEY_MIME) + ")", e);
            }
            mux.start();
            started = true;
            ByteBuffer buf = ByteBuffer.allocate(4 << 20);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long copied = 0;
            long[] prev = {-1, -1};
            int[] still = {0, 0};   // samples in a row with the very same time: a demuxer that lost the stream's timestamps
            long[] last = {Long.MIN_VALUE, Long.MIN_VALUE}, shift = {0, 0};   // per track: the latest time, and the shift that carries it across a timestamp reset
            while (true) {
                if (it.stop) throw new Stopped();
                boolean hasV = v.getSampleTrackIndex() >= 0, hasA = a.getSampleTrackIndex() >= 0;
                if (!hasV && !hasA) break;
                vs = hasV ? v.getSampleTime() : 0;
                as = hasA ? a.getSampleTime() : 0;
                long vo = hasV ? Timeline.on(vs, last, shift, 0) : 0, ao = hasA ? Timeline.on(as, last, shift, 1) : 0;
                boolean pic = !hasA || (hasV && vo <= ao);
                MediaExtractor e = pic ? v : a;
                buf.clear();
                int n = e.readSampleData(buf, 0);
                if (n < 0) break;
                long t = pic ? vo : ao;
                long raw = pic ? vs : as;
                int k = pic ? 0 : 1;
                if (raw == prev[k] && ++still[k] > 30)   // every sample at one time: an MP4 made of them would play in a flash, so it is not made
                    throw new IOException(App.t(R.string.dl_merge_failed) + " (the times in the stream stop moving at " + clock(Math.max(0, last[k] - base)) + ")");
                if (raw != prev[k]) still[k] = 0;
                prev[k] = raw;
                last[pic ? 0 : 1] = Math.max(last[pic ? 0 : 1], t);
                info.set(0, n, Math.max(0, t - base), (e.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                mux.writeSampleData(pic ? vi : ai, buf, info);
                if (it.kind.equals("convert")) {   // the bar of a conversion
                    copied += n;
                    it.done = Math.min(copied, size);
                    it.segDone = (int) (it.segs * it.done / Math.max(1, size));
                }
                e.advance();
            }
            mux.stop();
            started = false;
            out.finish();
        } finally {
            it.joining = false;
            if (mux != null) try { if (started) mux.stop(); } catch (RuntimeException ignored) { }
            if (mux != null) mux.release();
            out.close();
        }
    }

    /** Reading a playlist (a class of its own so a test can use it without the rest of the download manager). */
    static final class Hls {
        /** Whether a CODECS list names a sound ("avc1.64001f,mp4a.40.2" does, "avc1.64001f" does not). */
        static boolean audible(String codecs) {
            return codecs.matches("(?i).*(mp4a|ac-3|ec-3|ac-4|opus|mp3|dts|flac|alac|mha1|mhm1|vorbis).*");
        }

        /** Whether an MP4 init segment (its moov) has a sound track: a "hdlr" box whose handler is "soun". */
        static boolean mp4Audible(byte[] d) {
            for (int i = 0; i + 16 <= d.length; i++)
                if (d[i] == 'h' && d[i + 1] == 'd' && d[i + 2] == 'l' && d[i + 3] == 'r' && d[i + 12] == 's' && d[i + 13] == 'o' && d[i + 14] == 'u' && d[i + 15] == 'n') return true;
            return false;
        }

        /**
         * Whether the stream table (PAT, then PMT) at the start of an MPEG-TS piece lists a sound stream. True when no table can be read whole
         * from one packet: only a stream known to be silent counts as silent.
         */
        static boolean tsAudible(byte[] d) {
            int first = 0;
            while (first + 188 < d.length && !(d[first] == 0x47 && d[first + 188] == 0x47)) first++;   // a piece can start with junk
            int pmt = -1;
            for (int p = first; p + 188 <= d.length; p += 188) {
                if (d[p] != 0x47) return true;
                int pid = ((d[p + 1] & 0x1F) << 8) | (d[p + 2] & 0xFF), afc = (d[p + 3] >> 4) & 3, q = p + 4;
                if ((d[p + 1] & 0x40) == 0 || afc == 2) continue;   // not the start of a table
                if (afc == 3) q += 1 + (d[q] & 0xFF);
                if (q >= p + 188) continue;
                q += 1 + (d[q] & 0xFF);   // pointer field
                if (q + 12 > p + 188) continue;
                int end = q + 3 + (((d[q + 1] & 0x0F) << 8) | (d[q + 2] & 0xFF)) - 4;   // the section, without its CRC
                if (end > p + 188) return true;   // a table across packets: not read
                if (pid == 0 && pmt < 0 && d[q] == 0) {
                    for (int e = q + 8; e + 4 <= end; e += 4)
                        if ((((d[e] & 0xFF) << 8) | (d[e + 1] & 0xFF)) != 0) { pmt = ((d[e + 2] & 0x1F) << 8) | (d[e + 3] & 0xFF); break; }
                } else if (pid == pmt && d[q] == 2) {
                    for (int e = q + 12 + (((d[q + 10] & 0x0F) << 8) | (d[q + 11] & 0xFF)); e + 5 <= end; e += 5 + (((d[e + 3] & 0x0F) << 8) | (d[e + 4] & 0xFF))) {
                        int type = d[e] & 0xFF;   // MPEG audio, AAC (ADTS, LATM), AC-3, E-AC-3, DTS..., and "private" (DVB puts AC-3 there)
                        if (type == 0x03 || type == 0x04 || type == 0x0F || type == 0x11 || type == 0x06 || (type >= 0x80 && type <= 0x87)) return true;
                    }
                    return false;
                }
            }
            return true;
        }

        static byte[] ivFromNumber(long n) {
            byte[] iv = new byte[16];
            for (int i = 15; i >= 8; i--) { iv[i] = (byte) n; n >>= 8; }
            return iv;
        }

        static byte[] ivFromHex(String hex) {
            if (hex == null) return null;
            hex = hex.replaceFirst("^0[xX]", "");
            while (hex.length() < 32) hex = "0" + hex;
            byte[] iv = new byte[16];
            for (int i = 0; i < 16; i++) iv[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            return iv;
        }

        static String resolve(String base, String ref) throws IOException {
            if (ref == null) throw new IOException("a playlist line without an address");
            return new URL(new URL(base), ref).toString();
        }

        /** The pieces of a finished media playlist, with their keys, byte ranges and the init segment of an fMP4 one. */
        static Track track(String text, String base) throws IOException {
            if (!text.contains("#EXT-X-ENDLIST")) throw new Refused(R.string.dl_live);
            Track t = new Track();
            String keyUri = null;
            byte[] keyIv = null;
            boolean aes = false;
            long seq = 0, rangeFrom = -1, rangeLen = -1, lastEnd = 0;   // a #EXT-X-BYTERANGE is for the next piece; with no offset it follows the one before
            double dur = 0;
            for (String raw : text.split("\\r?\\n")) {
                String l = raw.trim();
                if (l.isEmpty()) continue;
                if (l.startsWith("#EXTINF:")) {
                    String d = l.substring(8);
                    int comma = d.indexOf(',');
                    try { dur = Double.parseDouble((comma >= 0 ? d.substring(0, comma) : d).trim()); } catch (NumberFormatException e) { dur = 0; }
                } else if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    try { seq = Long.parseLong(l.substring(22).trim()); } catch (NumberFormatException ignored) { }
                } else if (l.startsWith("#EXT-X-KEY:")) {
                    String method = attr(l, "METHOD");
                    if ("AES-128".equals(method)) {
                        aes = true;
                        keyUri = resolve(base, attr(l, "URI"));
                        keyIv = ivFromHex(attr(l, "IV"));
                    } else if ("NONE".equals(method)) {
                        aes = false;
                    } else {
                        throw new Refused(R.string.dl_protected);
                    }
                } else if (l.startsWith("#EXT-X-BYTERANGE:")) {
                    String[] r = l.substring(17).trim().split("@");
                    try {
                        rangeLen = Long.parseLong(r[0].trim());
                        rangeFrom = r.length > 1 ? Long.parseLong(r[1].trim()) : lastEnd;
                    } catch (NumberFormatException e) {
                        throw new IOException("a bad byte range: " + l);
                    }
                } else if (l.startsWith("#EXT-X-MAP:")) {
                    if (t.map == null) {
                        t.map = resolve(base, attr(l, "URI"));
                        String br = attr(l, "BYTERANGE");   // "578@0": the init segment is a range of the same file
                        if (br != null) try {
                            String[] r = br.split("@");
                            t.mapLen = Long.parseLong(r[0].trim());
                            t.mapFrom = r.length > 1 ? Long.parseLong(r[1].trim()) : 0;
                        } catch (NumberFormatException e) {
                            throw new IOException("a bad byte range: " + l);
                        }
                    }
                } else if (l.charAt(0) != '#') {
                    Seg s = new Seg();
                    s.url = resolve(base, l);
                    s.dur = dur;
                    dur = 0;
                    if (rangeLen >= 0) { s.from = rangeFrom; s.len = rangeLen; lastEnd = rangeFrom + rangeLen; rangeLen = -1; }
                    if (aes) { s.key = keyUri; s.iv = keyIv != null ? keyIv : ivFromNumber(seq + t.segs.size()); }
                    t.segs.add(s);
                }
            }
            if (t.segs.isEmpty()) throw new IOException("no segments in the playlist");
            t.ts = t.map == null;
            return t;
        }

    /** One attribute of a playlist tag line: KEY=value or KEY="value". */
    static String attr(String line, String key) {
        Matcher m = Pattern.compile("[:,]" + key + "=(\"([^\"]*)\"|[^,]*)").matcher(line);
        return m.find() ? (m.group(2) != null ? m.group(2) : m.group(1)) : null;
    }

    /**
     * The highest-bandwidth variant of a main playlist whose sound an MP4 can hold (not AC-3, E-AC-3, AC-4 or DTS: the top variant is often one
     * of those, and the picture and sound could not be joined); when every variant has such sound, the highest. {its address as written, its
     * picture height or "0", its audio group or null, its CODECS or null}. Null when there is none.
     */
    static String[] bestVariant(String text) {
        String best = null, group = null, any = null, anyGroup = null, bestCodecs = null, anyCodecs = null;
        long bw = -1, anyBw = -1;
        int height = 0, anyHeight = 0;
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
            long bandwidth = 0;
            try { bandwidth = Long.parseLong(String.valueOf(attr(lines[i], "BANDWIDTH")).trim()); } catch (NumberFormatException ignored) { }
            String uri = null;
            for (int k = i + 1; k < lines.length && uri == null; k++) if (!lines[k].trim().isEmpty() && !lines[k].startsWith("#")) uri = lines[k].trim();
            if (uri == null) continue;
            int h = 0;
            String r = attr(lines[i], "RESOLUTION");   // 1920x1080
            if (r != null && r.contains("x")) try { h = Integer.parseInt(r.substring(r.indexOf('x') + 1).trim()); } catch (NumberFormatException ignored) { }
            String codecs = attr(lines[i], "CODECS");
            if (bandwidth > anyBw) { anyBw = bandwidth; any = uri; anyGroup = attr(lines[i], "AUDIO"); anyHeight = h; anyCodecs = codecs; }
            boolean fits = codecs == null || !codecs.matches("(?i).*(ac-3|ec-3|ac-4|dts|mlpa).*");
            if (fits && bandwidth > bw) { bw = bandwidth; best = uri; group = attr(lines[i], "AUDIO"); height = h; bestCodecs = codecs; }
        }
        if (best == null) { best = any; group = anyGroup; height = anyHeight; bestCodecs = anyCodecs; }
        return best == null ? null : new String[]{best, String.valueOf(height), group, bestCodecs};
    }
    }

    /** A DASH manifest (.mpd) read down to what to fetch: the best picture and the sound as lists of pieces, like the pieces of an HLS playlist. */
    static final class Dash {
        static final class Pick {
            Track video, audio;   // audio null when the picture carries its own sound
            int height;
        }

        /**
         * One period (the longest; short ones are idents and ads, two long ones are refused), its best MP4 picture (highest bandwidth, a codec an MP4 holds) and its sound (AAC first). Pieces come from a
         * SegmentTemplate (a timeline, or a length) or a SegmentList; a stream that is one big file per quality is refused, as are live and protected ones.
         */
        static Pick pick(String xml, String mpdUrl) throws IOException {
            return pick(xml, mpdUrl, null);
        }

        /** `lang`: the sound language to prefer ("hi"), or null for the stream's own main sound. */
        static Pick pick(String xml, String mpdUrl, String lang) throws IOException {
            if (xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) throw new IOException("not a DASH manifest");   // no entities from a stranger's file
            org.w3c.dom.Element mpd;
            try {
                mpd = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new org.xml.sax.InputSource(new java.io.StringReader(xml))).getDocumentElement();
            } catch (Exception e) {
                throw new IOException("not a DASH manifest", e);
            }
            if ("dynamic".equals(mpd.getAttribute("type"))) throw new Refused(R.string.dl_live);
            double total = seconds(mpd.getAttribute("mediaPresentationDuration"));
            String mpdBase = baseUrl(mpd, mpdUrl);
            List<org.w3c.dom.Element> periods = kids(mpd, "Period");
            org.w3c.dom.Element period = null;
            double length = 0;
            int parts = 0;   // periods long enough to be the film itself, not an ident or an ad
            for (int i = 0; i < periods.size(); i++) {
                org.w3c.dom.Element p = periods.get(i);
                double d = seconds(p.getAttribute("duration"));
                if (d <= 0) d = (i + 1 < periods.size() ? seconds(periods.get(i + 1).getAttribute("start")) : total) - seconds(p.getAttribute("start"));
                if (d <= 0) d = total;
                if (d >= 30) parts++;
                if (period == null || d > length) { period = p; length = d; }
            }
            // ponytail: a film in several periods is refused rather than joined; joining needs their timelines put end to end
            if (parts > 1) throw new Refused(R.string.dl_dash_periods);   // only one of them would be saved, and the rest silently lost
            if (period == null) throw new IOException("no period in the manifest");
            String periodBase = baseUrl(period, mpdBase);
            org.w3c.dom.Element vRep = null, vSet = null, aRep = null, aSet = null;
            long vBw = -1, aBw = -1;
            int aScore = -1;
            for (org.w3c.dom.Element set : kids(period, "AdaptationSet")) {
                for (org.w3c.dom.Element rep : kids(set, "Representation")) {
                    String mime = either(rep, set, "mimeType"), codecs = either(rep, set, "codecs"), type = set.getAttribute("contentType");
                    if (type.isEmpty()) type = !mime.isEmpty() ? mime.split("/")[0] : codecs.matches("(?i)(avc|hvc|hev|av0|mp4v).*") ? "video" : codecs.matches("(?i)(mp4a|ac-3|ec-3|opus).*") ? "audio" : "";
                    if (!mime.isEmpty() && !mime.equals("video/mp4") && !mime.equals("audio/mp4")) continue;   // WebM, subtitles
                    long bw = number(rep.getAttribute("bandwidth"));
                    if (type.equals("video") && (codecs.isEmpty() || codecs.matches("(?i)(avc[13]|hvc1|hev1|av01|mp4v).*")) && bw > vBw) { vBw = bw; vRep = rep; vSet = set; }
                    if (type.equals("audio")) {
                        // never a description or a commentary when there is a sound for all; then the chosen language (a dub in it beats the
                        // original), then the main sound, then AAC (an MP4 holds it), then bandwidth within that set
                        String language = either(rep, set, "lang");
                        int role = role(set);
                        int score = (role > 0 ? 8 : 0) + (lang != null && language.length() >= 2 && lang.length() >= 2 && language.regionMatches(true, 0, lang, 0, 2) ? 4 : 0)
                            + (role == 2 ? 2 : 0) + (codecs.toLowerCase(java.util.Locale.ROOT).startsWith("mp4a") ? 1 : 0);
                        if (score > aScore || (score == aScore && aSet == set && bw > aBw)) { aBw = bw; aRep = rep; aSet = set; aScore = score; }
                    }
                }
            }
            if (vRep == null) throw new IOException("no MP4 picture in this DASH stream");
            Pick pick = new Pick();
            pick.video = track(period, vSet, vRep, periodBase, length);
            pick.height = (int) number(vRep.getAttribute("height"));
            if (aRep != null) pick.audio = track(period, aSet, aRep, periodBase, length);
            return pick;
        }

        static Track track(org.w3c.dom.Element period, org.w3c.dom.Element set, org.w3c.dom.Element rep, String periodBase, double length) throws IOException {
            if (!kids(set, "ContentProtection").isEmpty() || !kids(rep, "ContentProtection").isEmpty()) throw new Refused(R.string.dl_protected);
            String base = baseUrl(rep, baseUrl(set, periodBase)), id = rep.getAttribute("id");
            long bw = number(rep.getAttribute("bandwidth"));
            List<org.w3c.dom.Element> templates = new ArrayList<>();   // its attributes can be spread over the Representation, the set and the period
            for (org.w3c.dom.Element e : new org.w3c.dom.Element[]{rep, set, period}) { List<org.w3c.dom.Element> k = kids(e, "SegmentTemplate"); if (!k.isEmpty()) templates.add(k.get(0)); }
            Track t = new Track();
            if (!templates.isEmpty()) {
                long scale = Math.max(1, number(from(templates, "timescale")));
                String init = from(templates, "initialization"), media = from(templates, "media");
                if (media.isEmpty()) throw new IOException("a DASH stream without segment addresses");
                if (!init.isEmpty()) t.map = Hls.resolve(base, fill(init, id, bw, 0, 0));
                long number = from(templates, "startNumber").isEmpty() ? 1 : number(from(templates, "startNumber"));
                org.w3c.dom.Element timeline = null;
                for (org.w3c.dom.Element e : templates) if (timeline == null && !kids(e, "SegmentTimeline").isEmpty()) timeline = kids(e, "SegmentTimeline").get(0);
                if (timeline != null) {
                    long time = 0, first = -1;
                    for (org.w3c.dom.Element s : kids(timeline, "S")) {
                        if (!s.getAttribute("t").isEmpty()) time = number(s.getAttribute("t"));
                        if (first < 0) first = time;
                        long d = number(s.getAttribute("d")), r = s.getAttribute("r").isEmpty() ? 0 : (long) Double.parseDouble(s.getAttribute("r"));
                        if (d <= 0) throw new IOException("a DASH timeline without lengths");
                        if (r < 0) r = Math.max(0, (long) Math.ceil((first + length * scale - time) / d) - 1);   // "until the end of the period"
                        for (long i = 0; i <= r; i++, time += d, number++) add(t, Hls.resolve(base, fill(media, id, bw, number, time)), d / (double) scale);
                    }
                } else {
                    long d = number(from(templates, "duration"));
                    if (d <= 0 || length <= 0) throw new IOException("a DASH stream without segment lengths");
                    long count = (long) Math.ceil(length * scale / d);
                    for (long i = 0; i < count; i++) add(t, Hls.resolve(base, fill(media, id, bw, number + i, i * d)), Math.min(d / (double) scale, length - i * d / (double) scale));
                }
            } else {
                org.w3c.dom.Element list = null;
                for (org.w3c.dom.Element e : new org.w3c.dom.Element[]{rep, set, period}) if (list == null && !kids(e, "SegmentList").isEmpty()) list = kids(e, "SegmentList").get(0);
                if (list == null) {
                    if (kids(rep, "BaseURL").isEmpty() && kids(set, "BaseURL").isEmpty()) throw new Refused(R.string.dl_dash_pieces);
                    t.whole = true;   // "on demand": one file per quality (its own index inside), fetched in ranges
                    add(t, base, length);
                    return t;
                }
                long scale = Math.max(1, number(list.getAttribute("timescale")));
                double d = number(list.getAttribute("duration")) / (double) scale;
                List<org.w3c.dom.Element> init = kids(list, "Initialization");
                if (!init.isEmpty()) {
                    t.map = init.get(0).getAttribute("sourceURL").isEmpty() ? base : Hls.resolve(base, init.get(0).getAttribute("sourceURL"));
                    long[] r = range(init.get(0).getAttribute("range"));
                    if (r != null) { t.mapFrom = r[0]; t.mapLen = r[1]; }
                }
                for (org.w3c.dom.Element u : kids(list, "SegmentURL")) {
                    Seg s = add(t, u.getAttribute("media").isEmpty() ? base : Hls.resolve(base, u.getAttribute("media")), d);
                    long[] r = range(u.getAttribute("mediaRange"));
                    if (r != null) { s.from = r[0]; s.len = r[1]; }
                }
            }
            if (t.segs.isEmpty()) throw new IOException("no segments in the DASH stream");
            return t;
        }

        /** 2: the main sound (or one with no role); 0: a description, a commentary or another sound for some; 1: any other (an alternate, a dub). */
        static int role(org.w3c.dom.Element set) {
            List<org.w3c.dom.Element> roles = kids(set, "Role");
            if (roles.isEmpty()) return 2;
            String v = roles.get(0).getAttribute("value");
            return v.equals("main") ? 2 : v.matches("description|commentary|supplementary|emergency|enhanced-audio-intelligibility|karaoke") ? 0 : 1;
        }

        private static Seg add(Track t, String url, double dur) {
            Seg s = new Seg();
            s.url = url;
            s.dur = dur;
            t.segs.add(s);
            return s;
        }

        /** "0-1234" as {first, length}. */
        static long[] range(String r) {
            if (r == null || !r.matches("\\d+-\\d+")) return null;
            String[] p = r.split("-");
            return new long[]{Long.parseLong(p[0]), Long.parseLong(p[1]) - Long.parseLong(p[0]) + 1};
        }

        /** A template with $RepresentationID$, $Number$, $Bandwidth$ and $Time$ filled in ($Number%05d$ pads; $$ is a dollar). */
        static String fill(String template, String id, long bandwidth, long number, long time) {
            java.util.regex.Matcher m = Pattern.compile("\\$(RepresentationID|Number|Bandwidth|Time)?(?:%0(\\d+)d)?\\$").matcher(template);
            StringBuffer out = new StringBuffer();
            while (m.find()) {
                String what = m.group(1), v;
                if (what == null) v = "$";
                else if (what.equals("RepresentationID")) v = id;
                else {
                    long n = what.equals("Number") ? number : what.equals("Bandwidth") ? bandwidth : time;
                    v = m.group(2) == null ? String.valueOf(n) : String.format(java.util.Locale.ROOT, "%0" + m.group(2) + "d", n);
                }
                m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(v));
            }
            return m.appendTail(out).toString();
        }

        /** An ISO 8601 duration ("PT1H2M3.5S") in seconds; 0 when it is empty or not one. */
        static double seconds(String iso) {
            java.util.regex.Matcher m = Pattern.compile("P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)D)?(?:T(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?").matcher(iso == null ? "" : iso.trim());
            if (!m.matches()) return 0;
            double[] unit = {31536000, 2592000, 86400, 3600, 60, 1};
            double s = 0;
            for (int i = 0; i < unit.length; i++) if (m.group(i + 1) != null) s += Double.parseDouble(m.group(i + 1)) * unit[i];
            return s;
        }

        private static long number(String s) {
            try { return s == null || s.isEmpty() ? 0 : (long) Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return 0; }
        }

        /** The first of the templates that has the attribute. */
        private static String from(List<org.w3c.dom.Element> templates, String name) {
            for (org.w3c.dom.Element e : templates) if (!e.getAttribute(name).isEmpty()) return e.getAttribute(name);
            return "";
        }

        private static String either(org.w3c.dom.Element rep, org.w3c.dom.Element set, String name) {
            return !rep.getAttribute(name).isEmpty() ? rep.getAttribute(name) : set.getAttribute(name);
        }

        private static String baseUrl(org.w3c.dom.Element e, String parent) throws IOException {
            List<org.w3c.dom.Element> b = kids(e, "BaseURL");
            return b.isEmpty() || b.get(0).getTextContent().trim().isEmpty() ? parent : Hls.resolve(parent, b.get(0).getTextContent().trim());
        }

        private static List<org.w3c.dom.Element> kids(org.w3c.dom.Element e, String name) {
            List<org.w3c.dom.Element> out = new ArrayList<>();
            for (org.w3c.dom.Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
                if (n instanceof org.w3c.dom.Element && n.getNodeName().replaceFirst(".*:", "").equals(name)) out.add((org.w3c.dom.Element) n);
            return out;
        }
    }

    /** Sample times on one continuous timeline. */
    static final class Timeline {
        // µs: a time this far ahead of the track's latest, or behind it, is a reset (a joined segment, an ad); the next sample goes one step on.
        // Sound never goes back; a picture goes back a little (a B-frame comes before the frames it is shown after), so only BACK counts there.
        static final long JUMP = 3_000_000, BACK = 1_000_000, STEP = 20_000;

        /**
         * `raw` as the track's time: a stream whose timestamps start over or jump (the pieces were cut from different places) goes on from
         * where the track was instead of back to 0, which an MP4 cannot hold. k: 0 picture, 1 sound; `last` is each track's latest time, `shift` what is added.
         */
        static long on(long raw, long[] last, long[] shift, int k) {
            long t = raw + shift[k];
            if (last[k] != Long.MIN_VALUE && (t > last[k] + JUMP || t < last[k] - (k == 0 ? BACK : 0))) {
                shift[k] += last[k] + STEP - t;
                t = last[k] + STEP;
            }
            return t;
        }
    }

    private static String clock(long us) {
        long s = us / 1_000_000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** The tracks of a source as "video/avc, audio/ac3", for a message that says what was in the stream. */
    private static String tracks(MediaExtractor x) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < x.getTrackCount(); i++) b.append(i > 0 ? ", " : "").append(x.getTrackFormat(i).getString(MediaFormat.KEY_MIME));
        return b.length() == 0 ? "nothing" : b.toString();
    }

    /**
     * A finished .ts download made into an MP4 of the same name, in the background (the list shows it as a download that runs). The .ts goes once
     * the MP4 is whole; when it cannot be made the .ts stays as it was and says why.
     */
    static void makeSeekable(Item it) {
        if (!it.status.equals("done") || !it.ext.equals("ts") || it.out.isEmpty()) return;
        toConvert(it);
        it.error = "";
        it.stop = false;
        it.status = "queued";
        version++;
        save();
        pump();
    }

    /** The .ts (out) becomes the source of a conversion: the MP4 of the same name is made next to it. */
    private static void toConvert(Item it) {
        it.src = it.out;
        it.out = "";
        it.path = "";
        it.ext = "mp4";
        it.kind = "convert";
        it.note = "";
    }

    /** The conversion is off: the .ts stays (out again), with the reason shown under it. */
    private static void keepTs(Item it, String why) {
        dropOut(it);
        it.out = it.src;
        it.src = "";
        it.ext = "ts";
        it.kind = "hls";
        it.note = why;
        pathOf(it);
    }

    private static void convert(Item it) throws Exception {
        ParcelFileDescriptor p1 = null, p2 = null;
        MediaExtractor v = new MediaExtractor(), a = new MediaExtractor();
        try {
            p1 = openRead(it.src);
            long need = p1.getStatSize() + (64L << 20);   // the MP4 is about the size of the .ts, which stays until it is whole
            if (new android.os.StatFs(root()).getAvailableBytes() < need) {
                keepTs(it, App.t(R.string.dl_no_space, android.text.format.Formatter.formatFileSize(App.app, need)));
                return;
            }
            p2 = openRead(it.src);
            it.segs = 1000;
            it.segDone = it.asegs = it.aSegDone = 0;
            it.total = p1.getStatSize();
            it.done = it.aDone = 0;
            v.setDataSource(p1.getFileDescriptor());
            a.setDataSource(p2.getFileDescriptor());
            join(it, v, a, it.total);
        } catch (Stopped e) {
            throw e;
        } catch (Exception e) {   // the .ts stays as it was
            Log.w(TAG, "convert " + it.name, e);
            keepTs(it, String.valueOf(e.getMessage()));
            return;
        } finally {
            v.release();
            a.release();
            try { if (p1 != null) p1.close(); } catch (IOException ignored) { }
            try { if (p2 != null) p2.close(); } catch (IOException ignored) { }
        }
        deleteFile(it.src);   // the MP4 is whole: the .ts goes
        it.src = "";
        it.kind = "hls";
        it.note = "";
        try (ParcelFileDescriptor o = openRead(it.out)) { it.total = it.done = o.getStatSize(); } catch (IOException ignored) { }   // the row shows the MP4's size, not the .ts's
    }

    private static ParcelFileDescriptor openRead(String out) throws IOException {
        ParcelFileDescriptor p = out.startsWith("content://") ? App.app.getContentResolver().openFileDescriptor(Uri.parse(out), "r")
            : ParcelFileDescriptor.open(new File(out), ParcelFileDescriptor.MODE_READ_ONLY);
        if (p == null) throw new IOException("the file cannot be opened");
        return p;
    }

    /** Removes the half-made file of `it` (its MP4 that did not work out) and forgets it. */
    private static void dropOut(Item it) {
        deleteFile(it.out);
        it.out = "";
        it.path = "";
    }

    private static void deleteFile(String out) {
        if (out.isEmpty()) return;
        try {
            if (out.startsWith("content://")) App.app.getContentResolver().delete(Uri.parse(out), null, null);
            else new File(out).delete();
        } catch (RuntimeException e) {
            Log.w(TAG, "delete " + out, e);
        }
    }

    private static byte[] segment(Item it, Seg s, Map<String, byte[]> keys) throws Exception {
        byte[] data = get(it, s.url, s.from, s.len);
        if (s.key == null) return data;
        byte[] key = keys.get(s.key);
        if (key == null) { key = get(it, s.key); keys.put(s.key, key); }
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(s.iv));
        return c.doFinal(data);
    }

    /** The "sidx" box: one entry per piece (its size and length in the track's own time), each starting at a keyframe. It replaces the "free" room kept for it. */
    private static void writeIndex(Sink sink, Item it, List<Seg> segs) throws IOException {
        int n = segs.size();
        ByteBuffer b = ByteBuffer.allocate(40 + 12 * n);
        b.putInt(b.capacity()).put("sidx".getBytes(StandardCharsets.ISO_8859_1)).putInt(0x01000000)   // version 1, no flags
            .putInt(it.track).putInt(it.scale).putLong(it.pts0).putLong(0).putShort((short) 0).putShort((short) n);
        for (int i = 0; i < n; i++) b.putInt((int) it.sizes[i]).putInt((int) Math.round(segs.get(i).dur * it.scale)).putInt(0x90000000);
        b.rewind();
        Sink.write(sink.ch, b, it.sidxAt);
    }

    private static long u32(byte[] d, int p) {
        return p < 0 || p + 4 > d.length ? 0 : (d[p] & 0xFFL) << 24 | (d[p + 1] & 0xFFL) << 16 | (d[p + 2] & 0xFFL) << 8 | (d[p + 3] & 0xFFL);
    }

    private static String fourcc(byte[] d, int p) {
        return p + 8 > d.length ? "" : new String(d, p + 4, 4, StandardCharsets.ISO_8859_1);
    }

    /** The contents [start, end) of the first box of this type among the boxes in d[from, to); null when there is none. */
    private static int[] box(byte[] d, int from, int to, String type) {
        for (int p = from; p + 8 <= to; ) {
            long size = u32(d, p);
            if (size == 0) size = to - p;
            if (size < 8 || p + size > to) return null;
            if (fourcc(d, p).equals(type)) return new int[]{p + 8, (int) (p + size)};
            p += (int) size;
        }
        return null;
    }

    /** {track id, timescale} of the first video track (else the first track) in an fMP4 init segment; null when it is not understood. */
    private static int[] videoTrack(byte[] d) {
        int[] moov = box(d, 0, d.length, "moov");
        if (moov == null) return null;
        int[] first = null;
        for (int p = moov[0]; p + 8 <= moov[1]; ) {
            long size = u32(d, p);
            if (size < 8) break;
            int end = (int) Math.min(moov[1], p + size);
            if (fourcc(d, p).equals("trak")) {
                int[] tkhd = box(d, p + 8, end, "tkhd"), mdia = box(d, p + 8, end, "mdia");
                int[] mdhd = mdia == null ? null : box(d, mdia[0], mdia[1], "mdhd"), hdlr = mdia == null ? null : box(d, mdia[0], mdia[1], "hdlr");
                if (tkhd != null && mdhd != null) {
                    int[] t = {(int) u32(d, tkhd[0] + (d[tkhd[0]] == 1 ? 20 : 12)), (int) u32(d, mdhd[0] + (d[mdhd[0]] == 1 ? 20 : 12))};
                    if (t[1] > 0) {
                        if (first == null) first = t;
                        if (hdlr != null && hdlr[0] + 12 <= d.length && new String(d, hdlr[0] + 8, 4, StandardCharsets.ISO_8859_1).equals("vide")) return t;
                    }
                }
            }
            p += (int) size;
        }
        return first;
    }

    /** The start time (in the track's timescale) of the first fragment of one track in a piece; 0 when it cannot be read. */
    private static long firstTfdt(byte[] d, int trackId) {
        int[] moof = box(d, 0, d.length, "moof");
        if (moof == null) return 0;
        for (int p = moof[0]; p + 8 <= moof[1]; ) {
            long size = u32(d, p);
            if (size < 8) break;
            if (fourcc(d, p).equals("traf")) {
                int end = (int) Math.min(moof[1], p + size);
                int[] tfhd = box(d, p + 8, end, "tfhd"), tfdt = box(d, p + 8, end, "tfdt");
                if (tfhd != null && tfdt != null && u32(d, tfhd[0] + 4) == trackId) {
                    int q = tfdt[0];
                    return d[q] == 1 ? u32(d, q + 4) << 32 | u32(d, q + 8) : u32(d, q + 4);
                }
            }
            p += (int) size;
        }
        return 0;
    }





    // ---- Requests ----

    /** A request the way the player makes it: its headers, the WebView's cookie for that host, through WARP unless the host is local. Follows redirects across http and https. */
    private static HttpURLConnection connect(Item it, String url, long from, long to) throws IOException {
        String cur = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = Warp.connect(cur, Warp.proxyFor(String.valueOf(Uri.parse(cur).getHost())), 15000, 30000);
            c.setInstanceFollowRedirects(false);
            try {
                JSONObject h = new JSONObject(it.headers);
                for (Iterator<String> k = h.keys(); k.hasNext(); ) { String key = k.next(); c.setRequestProperty(key, h.getString(key)); }
            } catch (JSONException ignored) { }
            try {
                String cookie = CookieManager.getInstance().getCookie(cur);
                if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
            } catch (RuntimeException ignored) { }
            c.setRequestProperty("Accept-Encoding", "identity");
            if (from >= 0) c.setRequestProperty("Range", "bytes=" + from + "-" + (to >= 0 ? to : ""));
            int code = c.getResponseCode();
            String where = c.getHeaderField("Location");
            if (code >= 300 && code < 400 && where != null) { cur = new URL(new URL(cur), where).toString(); c.disconnect(); continue; }
            return c;
        }
        throw new IOException("too many redirects");
    }

    private static String fetchText(Item it, String url) throws IOException {
        HttpURLConnection c = connect(it, url, -1, -1);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            try (InputStream in = c.getInputStream()) {
                while ((n = in.read(b)) > 0 && out.size() < (4 << 20)) out.write(b, 0, n);
            }
            return out.toString("UTF-8").replace("﻿", "").trim();
        } finally {
            c.disconnect();
        }
    }

    /** A whole small resource (a segment, a key), tried again a few times. */
    private static byte[] get(Item it, String url) throws IOException {
        return get(it, url, -1, 0);
    }

    /** A whole resource, or `len` bytes of it from `from`: a piece of an HLS or DASH stream that is a range of a bigger file. */
    private static byte[] get(Item it, String url, long from, long len) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            if (it.stop) throw new Stopped();
            HttpURLConnection c = null;
            try {
                c = connect(it, url, from, from >= 0 ? from + len - 1 : -1);
                int code = c.getResponseCode();
                if (from >= 0 ? code != 206 : code != 200 && code != 206)   // a server that ignores the range sends the whole file: not read into memory
                    throw new IOException("HTTP " + code + (from >= 0 ? " (the site does not allow ranges)" : ""));
                long size = from >= 0 ? len : c.getContentLengthLong();   // sized up front: a growing buffer doubles and copies, twice the memory of the piece
                ByteArrayOutputStream out = new ByteArrayOutputStream(size > 0 && size < (64 << 20) ? (int) size : 1 << 16);
                byte[] b = new byte[65536];
                int n;
                try (InputStream in = c.getInputStream()) {
                    while ((n = in.read(b)) > 0) {
                        if (it.stop) throw new Stopped();
                        out.write(b, 0, n);
                    }
                }
                return out.toByteArray();
            } catch (Stopped e) {
                throw e;
            } catch (IOException e) {
                last = e;
                nap(600L * (attempt + 1), it);
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw last;
    }

    private static void nap(long ms, Item it) throws Stopped {
        for (long t = 0; t < ms; t += 100) {
            if (it.stop) throw new Stopped();
            try { Thread.sleep(100); } catch (InterruptedException e) { throw new Stopped(); }
        }
    }

    // ---- Names ----

    /** A file name that every file system takes: no \ / : * ? " < > |, no double spaces, not too long. */
    static String safeName(String name) {
        String s = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "").replaceAll("\\s+", " ").trim().replaceAll("^[.\\s]+|[.\\s]+$", "");
        if (s.length() > 120) s = s.substring(0, 120).trim();
        return s.isEmpty() ? "AnantPlay video" : s;
    }

    /** The file's extension as far as it is known before the download starts (an HLS stream is written as .ts, or .mp4 when it turns out to be fMP4). */
    static String expectedExt(String url, boolean dash) {
        Matcher m = Pattern.compile("\\.(mp4|mkv|webm|flv|avi|mov|m4v)$").matcher(Sniffer.path(url).toLowerCase(Locale.ROOT));
        return m.find() ? m.group(1) : dash ? "mp4" : "ts";
    }

    /** Where the file is, for the screen: /storage/emulated/0/Download/AnantPlay/Name.ext (the name the system finally gave it). */
    static String pathOf(Item it) {
        if (it.path.startsWith("/sdcard/")) it.path = root() + it.path.substring(7);   // an older entry: the real name of the same folder
        if (!it.path.isEmpty() || it.out.isEmpty()) return it.path;
        if (!it.out.startsWith("content://")) return it.path = it.out;
        try (android.database.Cursor c = App.app.getContentResolver().query(Uri.parse(it.out),
            new String[]{MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) it.path = root() + "/" + c.getString(0) + c.getString(1);
        } catch (RuntimeException ignored) { }
        return it.path;
    }

    static String mimeOf(String ext) {
        switch (ext.toLowerCase(Locale.ROOT)) {
            case "ts": return "video/mp2t";
            case "mkv": return "video/x-matroska";
            case "webm": return "video/webm";
            case "flv": return "video/x-flv";
            case "avi": return "video/x-msvideo";
            case "mov": return "video/quicktime";
            default: return "video/mp4";
        }
    }

    // ---- Where the bytes go: a file in the public folder (MediaStore), or the app's own Movies folder where that is not possible ----

    static final class Sink {
        FileChannel ch;
        Uri uri;
        private ParcelFileDescriptor pfd;
        private RandomAccessFile raf;
        private FileOutputStream fos;

        static Sink open(File f) throws IOException {
            Sink s = new Sink();
            s.raf = new RandomAccessFile(f, "rw");
            s.ch = s.raf.getChannel();
            return s;
        }

        java.io.FileDescriptor fd() throws IOException {
            return pfd != null ? pfd.getFileDescriptor() : raf.getFD();
        }

        static Sink open(Item it) throws IOException {
            if (it.out.isEmpty()) create(it);
            Sink s = new Sink();
            if (it.out.startsWith("content://")) {
                s.uri = Uri.parse(it.out);
                s.pfd = App.app.getContentResolver().openFileDescriptor(s.uri, "rw");
                if (s.pfd == null) throw new IOException("the file cannot be opened");
                s.fos = new FileOutputStream(s.pfd.getFileDescriptor());
                s.ch = s.fos.getChannel();
            } else {
                s.raf = new RandomAccessFile(new File(it.out), "rw");
                s.ch = s.raf.getChannel();
            }
            return s;
        }

        private static void create(Item it) throws IOException {
            String file = it.name + "." + it.ext;
            if (Build.VERSION.SDK_INT >= 29) {
                String rel = folder().endsWith("/") ? folder() : folder() + "/";
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, file);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(it.ext));
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri collection = rel.startsWith("Movies/") ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    : MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                try {
                    Uri u = App.app.getContentResolver().insert(collection, v);
                    if (u != null) { it.out = u.toString(); pathOf(it); return; }
                } catch (RuntimeException e) {
                    Log.w(TAG, "folder " + rel, e);   // a folder the system does not allow: the app's own folder below
                }
            }
            File dir = new File(App.app.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "AnantPlay");
            dir.mkdirs();
            File f = new File(dir, file);
            for (int n = 2; f.exists(); n++) f = new File(dir, it.name + " (" + n + ")." + it.ext);
            it.out = f.getAbsolutePath();
            it.path = it.out;
        }

        static void write(FileChannel ch, ByteBuffer b, long at) throws IOException {
            while (b.hasRemaining()) at += ch.write(b, at);
        }

        /** The download is whole: a file in the public folder stops being "pending" and shows up in the file manager and the gallery. */
        void finish() {
            if (uri == null) return;
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            App.app.getContentResolver().update(uri, v, null, null);
        }

        void close() {
            try { if (ch != null) ch.close(); } catch (IOException ignored) { }
            try { if (pfd != null) pfd.close(); } catch (IOException ignored) { }
            try { if (raf != null) raf.close(); } catch (IOException ignored) { }
        }

        static void delete(Item it) {
            tmp(it, "v").delete();
            tmp(it, "a").delete();
            deleteFile(it.src);
            deleteFile(it.out);
        }
    }
}
