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
            return asegs > 0 && !status.equals("done") ? Math.min(p, 99) : p;   // the last per cent is the merge of picture and sound
        }

        boolean active() {
            return status.equals("running") || status.equals("queued");
        }
    }

    static final class Stopped extends IOException {
        Stopped() { super("stopped"); }
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
        return App.prefs.getString("dlFolder", "Download/FMHYPlay");
    }

    /** The device's own storage as a path (/storage/emulated/0): what the folders below it are really called on disk. */
    static String root() {
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    /** The folder with its whole path, for the screen: /storage/emulated/0/Download/FMHYPlay/ */
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
                    .put("sizes", array(it.sizes)).put("pts0", it.pts0).put("sidxAt", it.sidxAt).put("track", it.track).put("scale", it.scale));
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
    }

    /** Looks at the stream's playlists (a request or two): so the file name and extension shown for confirmation are the ones that will be written. Call off the main thread. */
    static Plan plan(String url, String headers, boolean dash) {
        Plan p = new Plan();
        p.ext = expectedExt(url, dash);
        if (dash || !p.ext.equals("ts")) return p;   // DASH is refused anyway; a plain file's extension is in its address
        Item look = new Item();
        look.url = url;
        look.headers = headers == null || headers.isEmpty() ? "{}" : headers;
        try {
            String text = fetchText(look, url);
            if (!text.startsWith("#EXTM3U")) return p;
            if (text.contains("#EXT-X-STREAM-INF")) {
                String[] best = bestVariant(text);
                if (best == null) return p;
                p.height = Integer.parseInt(best[1]);
                if (audioUri(text, best[2]) != null) { p.ext = "mp4"; return p; }   // picture and sound come apart: they are joined into an MP4
                text = fetchText(look, resolve(url, best[0]));
            }
            if (text.contains("#EXT-X-MAP:")) p.ext = "mp4";   // the pieces are fMP4: the file is an MP4
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "plan " + url, e);
        }
        return p;
    }

    /**
     * The file name without its extension, scene style: the words joined by dots, then the picture height and the app's mark
     * (Crawlers.2024.1080p.FMHYPlay). Dots instead of spaces, nothing a file system dislikes.
     */
    static String fileBase(String name, int height) {
        String base = name == null ? "" : name.replaceAll("['\u2019`]", "").replaceAll("[^\\p{L}\\p{N}]+", ".").replaceAll("^\\.+|\\.+$", "");
        if (base.length() > 110) base = base.substring(0, 110).replaceAll("\\.+$", "");
        if (base.isEmpty()) base = "video";
        return base + (height > 0 ? "." + height + "p" : "") + ".FMHYPlay";
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
            if (it.kind.equals("dash")) throw new IOException(App.t(R.string.dl_dash));
            if (it.kind.equals("hls")) hls(it); else file(it);
            it.status = "done";
            it.error = "";
            DownloadService.finished(it.name);
        } catch (Stopped e) {
            if (items.contains(it)) it.status = "paused";
        } catch (Exception e) {
            Log.w(TAG, it.name, e);
            it.status = "failed";
            it.error = String.valueOf(e.getMessage());
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
                String cr = c.getHeaderField("Content-Range");
                int slash = cr == null ? -1 : cr.lastIndexOf('/');
                if (slash > 0) try { len = Long.parseLong(cr.substring(slash + 1).trim()); } catch (NumberFormatException ignored) { }
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
        double dur;   // seconds, from its #EXTINF
    }

    private static void hls(Item it) throws Exception {
        String text = fetchText(it, it.url);
        if (!text.startsWith("#EXTM3U")) { it.kind = "file"; file(it); return; }   // not a playlist after all: a plain file
        String base = it.url, audioUrl = null;
        if (text.contains("#EXT-X-STREAM-INF")) {   // the main playlist: the best quality
            String[] best = bestVariant(text);
            if (best == null) throw new IOException("an empty playlist");
            String au = audioUri(text, best[2]);   // the sound in a playlist of its own, not in the pieces of the picture
            if (au != null) audioUrl = resolve(base, au);
            base = resolve(base, best[0]);
            text = fetchText(it, base);
        }
        Track vt = track(text, base);
        List<Seg> segs = vt.segs;
        String map = vt.map;
        it.segs = segs.size();
        Track at = null;
        if (audioUrl != null) at = track(fetchText(it, audioUrl), audioUrl);
        it.asegs = at == null ? 0 : at.segs.size();
        it.ext = at != null || map != null ? "mp4" : "ts";   // pieces of an fMP4 stream put back to back are one MP4; the others are an MPEG-TS file; picture and sound are joined into an MP4
        File vfile = tmp(it, "v");
        Sink sink = at != null ? Sink.open(vfile) : Sink.open(it);   // with a separate sound the picture waits in a file of its own until it is joined
        ExecutorService pool = Executors.newFixedThreadPool(connections());
        try {
            long pos = it.segDone == 0 ? 0 : it.done;
            if (it.segDone == 0 && map != null) {
                byte[] init = get(it, map);
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
    }

    static final class Track {
        final List<Seg> segs = new ArrayList<>();
        String map;   // the init segment of an fMP4 stream
    }

    /** The pieces of a finished media playlist, with their keys, and the init segment of an fMP4 one. */
    private static Track track(String text, String base) throws IOException {
        if (!text.contains("#EXT-X-ENDLIST")) throw new IOException(App.t(R.string.dl_live));
        Track t = new Track();
        String keyUri = null;
        byte[] keyIv = null;
        boolean aes = false;
        long seq = 0;
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
                    throw new IOException(App.t(R.string.dl_protected));
                }
            } else if (l.startsWith("#EXT-X-MAP:")) {
                if (t.map == null) t.map = resolve(base, attr(l, "URI"));
            } else if (l.charAt(0) != '#') {
                Seg s = new Seg();
                s.url = resolve(base, l);
                s.dur = dur;
                dur = 0;
                if (aes) { s.key = keyUri; s.iv = keyIv != null ? keyIv : ivFromNumber(seq + t.segs.size()); }
                t.segs.add(s);
            }
        }
        if (t.segs.isEmpty()) throw new IOException("no segments in the playlist");
        return t;
    }

    /** The highest-bandwidth variant of a main playlist: {its address as written, its picture height or "0", its audio group or null}. Null when there is none. */
    private static String[] bestVariant(String text) {
        String best = null, group = null;
        long bw = -1;
        int height = 0;
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue;
            long bandwidth = 0;
            try { bandwidth = Long.parseLong(String.valueOf(attr(lines[i], "BANDWIDTH")).trim()); } catch (NumberFormatException ignored) { }
            String uri = null;
            for (int k = i + 1; k < lines.length && uri == null; k++) if (!lines[k].trim().isEmpty() && !lines[k].startsWith("#")) uri = lines[k].trim();
            if (uri == null || bandwidth <= bw) continue;
            bw = bandwidth;
            best = uri;
            group = attr(lines[i], "AUDIO");
            String r = attr(lines[i], "RESOLUTION");   // 1920x1080
            height = 0;
            if (r != null && r.contains("x")) try { height = Integer.parseInt(r.substring(r.indexOf('x') + 1).trim()); } catch (NumberFormatException ignored) { }
        }
        return best == null ? null : new String[]{best, String.valueOf(height), group};
    }

    /** The address (as written) of the audio playlist of a group in a main playlist: the default one, else the first. Null when the sound is in the picture's own pieces. */
    private static String audioUri(String text, String group) {
        if (group == null) return null;
        String first = null;
        for (String raw : text.split("\\r?\\n")) {
            String l = raw.trim();
            if (!l.startsWith("#EXT-X-MEDIA:") || !"AUDIO".equals(attr(l, "TYPE")) || !group.equals(attr(l, "GROUP-ID"))) continue;
            String uri = attr(l, "URI");
            if (uri == null) continue;
            if ("YES".equalsIgnoreCase(attr(l, "DEFAULT"))) return uri;
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
                byte[] init = get(it, t.map);
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
        Sink out = Sink.open(it);
        MediaMuxer mux = null;
        boolean started = false;
        try {
            v.setDataSource(video.getAbsolutePath());
            a.setDataSource(audio.getAbsolutePath());
            int vt = trackOf(v, "video/"), at = trackOf(a, "audio/");
            if (vt < 0 || at < 0) throw new IOException(App.t(R.string.dl_merge_failed));
            v.selectTrack(vt);
            a.selectTrack(at);
            long vs = v.getSampleTime(), as = a.getSampleTime();
            if (vs < 0 || as < 0) throw new IOException(App.t(R.string.dl_merge_failed));
            long base = Math.min(vs, as);   // both start near 0, keeping the gap between them
            out.ch.truncate(0);
            mux = new MediaMuxer(out.fd(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vi, ai;
            try {
                vi = mux.addTrack(v.getTrackFormat(vt));
                ai = mux.addTrack(a.getTrackFormat(at));
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new IOException(App.t(R.string.dl_merge_failed), e);   // a sound format MP4 cannot hold
            }
            mux.start();
            started = true;
            ByteBuffer buf = ByteBuffer.allocate(4 << 20);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                if (it.stop) throw new Stopped();
                vs = v.getSampleTime();
                as = a.getSampleTime();
                if (vs < 0 && as < 0) break;
                boolean pic = as < 0 || (vs >= 0 && vs <= as);
                MediaExtractor e = pic ? v : a;
                buf.clear();
                int n = e.readSampleData(buf, 0);
                if (n < 0) break;
                info.set(0, n, e.getSampleTime() - base, (e.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                mux.writeSampleData(pic ? vi : ai, buf, info);
                e.advance();
            }
            mux.stop();
            started = false;
            out.finish();
        } finally {
            if (mux != null) try { if (started) mux.stop(); } catch (RuntimeException ignored) { }
            if (mux != null) mux.release();
            v.release();
            a.release();
            out.close();
        }
    }

    private static byte[] segment(Item it, Seg s, Map<String, byte[]> keys) throws Exception {
        byte[] data = get(it, s.url);
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

    private static byte[] ivFromNumber(long n) {
        byte[] iv = new byte[16];
        for (int i = 15; i >= 8; i--) { iv[i] = (byte) n; n >>= 8; }
        return iv;
    }

    private static byte[] ivFromHex(String hex) {
        if (hex == null) return null;
        hex = hex.replaceFirst("^0[xX]", "");
        while (hex.length() < 32) hex = "0" + hex;
        byte[] iv = new byte[16];
        for (int i = 0; i < 16; i++) iv[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return iv;
    }

    /** One attribute of a playlist tag line: KEY=value or KEY="value". */
    private static String attr(String line, String key) {
        Matcher m = Pattern.compile("[:,]" + key + "=(\"([^\"]*)\"|[^,]*)").matcher(line);
        return m.find() ? (m.group(2) != null ? m.group(2) : m.group(1)) : null;
    }

    private static String resolve(String base, String ref) throws IOException {
        if (ref == null) throw new IOException("a playlist line without an address");
        return new URL(new URL(base), ref).toString();
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
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            if (it.stop) throw new Stopped();
            HttpURLConnection c = null;
            try {
                c = connect(it, url, -1, -1);
                int code = c.getResponseCode();
                if (code != 200 && code != 206) throw new IOException("HTTP " + code);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
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
        return s.isEmpty() ? "FMHYPlay video" : s;
    }

    /** The file's extension as far as it is known before the download starts (an HLS stream is written as .ts, or .mp4 when it turns out to be fMP4). */
    static String expectedExt(String url, boolean dash) {
        Matcher m = Pattern.compile("\\.(mp4|mkv|webm|flv|avi|mov|m4v)$").matcher(Sniffer.path(url).toLowerCase(Locale.ROOT));
        return m.find() ? m.group(1) : dash ? "mp4" : "ts";
    }

    /** Where the file is, for the screen: /storage/emulated/0/Download/FMHYPlay/Name.ext (the name the system finally gave it). */
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
            File dir = new File(App.app.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "FMHYPlay");
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
            if (it.out.isEmpty()) return;
            try {
                if (it.out.startsWith("content://")) App.app.getContentResolver().delete(Uri.parse(it.out), null, null);
                else new File(it.out).delete();
            } catch (RuntimeException e) {
                Log.w(TAG, "delete " + it.out, e);
            }
        }
    }
}
