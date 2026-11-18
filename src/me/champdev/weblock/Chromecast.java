package me.champdev.weblock;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Google Cast (Chromecast, Google TV, TVs with Cast built in) without Google's library: the Cast v2 protocol (length-prefixed
 * protobuf messages over TLS, port 8009) driving Google's Default Media Receiver. That receiver is a web player: it can't send a
 * site's Referer/Origin/cookie and needs CORS, so the phone relays the stream (Relay) and adds both. The phone stays awake meanwhile.
 */
final class Chromecast implements Cast.Tv {
    static final String TAG = "chromecast", APP = "CC1AD845";   // the Default Media Receiver, free for any sender
    static final String CONN = "urn:x-cast:com.google.cast.tp.connection", BEAT = "urn:x-cast:com.google.cast.tp.heartbeat",
        RECV = "urn:x-cast:com.google.cast.receiver", MEDIA = "urn:x-cast:com.google.cast.media";

    final String host;
    final int port;
    SSLSocket sock;
    DataOutputStream out;
    volatile boolean closed, ended;
    volatile String transport, session, failed;
    volatile int mediaSession = -1;
    volatile JSONObject media;   // the last MEDIA_STATUS entry
    volatile double volume = -1, duration;
    int requestId = 1;
    String title = "";
    boolean live;
    Relay relay;
    static PowerManager.WakeLock wake;
    static WifiManager.WifiLock wifi;

    Chromecast(String hostPort) {
        int c = hostPort.lastIndexOf(':');
        host = hostPort.substring(0, c);
        port = Integer.parseInt(hostPort.substring(c + 1));
    }

    @Override public String start(JSONObject v) throws Exception {
        title = v.optString("title");
        live = v.optBoolean("live");
        // ponytail: trusts the device's self-signed certificate without Google's device-auth check (LAN only); add the check if spoofing matters
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
            @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        sock = (SSLSocket) tls.getSocketFactory().createSocket();
        sock.connect(new InetSocketAddress(host, port), 5000);
        sock.startHandshake();
        out = new DataOutputStream(sock.getOutputStream());
        new Thread(this::read, "cast-read").start();
        send("receiver-0", CONN, new JSONObject().put("type", "CONNECT"));
        send("receiver-0", RECV, new JSONObject().put("type", "LAUNCH").put("appId", APP).put("requestId", requestId++));
        for (int n = 0; n < 150 && transport == null && failed == null; n++) Thread.sleep(100);
        if (transport == null) { close(); return failed != null ? failed : App.t(R.string.cast_no_receiver); }
        send(transport, CONN, new JSONObject().put("type", "CONNECT"));
        new Thread(this::beat, "cast-beat").start();

        Map<String, String> headers = new HashMap<>();
        JSONObject h = new JSONObject(v.optString("headers", "{}"));
        for (Iterator<String> it = h.keys(); it.hasNext(); ) { String k = it.next(); headers.put(k, h.getString(k)); }
        relay = new Relay(sock.getLocalAddress(), headers);
        awake(true);
        String url = v.getString("url");
        JSONObject m = new JSONObject().put("contentId", relay.wrap(url)).put("contentUrl", relay.wrap(url))
            .put("streamType", live ? "LIVE" : "BUFFERED").put("contentType", type(v, url))
            .put("metadata", new JSONObject().put("metadataType", 0).put("title", title));
        JSONObject load = new JSONObject().put("type", "LOAD").put("requestId", requestId++).put("sessionId", session).put("media", m).put("autoplay", true);
        if (!live && v.optLong("at") > 0) load.put("currentTime", v.optLong("at") / 1000.0);
        send(transport, MEDIA, load);
        for (int n = 0; n < 250 && mediaSession < 0 && failed == null; n++) Thread.sleep(100);
        if (mediaSession < 0) { close(); return failed != null ? failed : App.t(R.string.cast_tv_cant_play); }
        return null;
    }

    static String type(JSONObject v, String url) {
        String mime = v.optString("mime");
        if (!mime.isEmpty()) return mime;
        if (v.optBoolean("dash")) return "application/dash+xml";
        String p = Uri.parse(url).getPath() == null ? "" : Uri.parse(url).getPath().toLowerCase(Locale.ROOT);
        if (p.endsWith(".mp4") || p.endsWith(".m4v")) return "video/mp4";
        if (p.endsWith(".mkv") || p.endsWith(".webm")) return "video/webm";
        if (p.endsWith(".ts")) return "video/mp2t";
        return "application/x-mpegURL";
    }

    @Override public JSONObject status() throws Exception {
        if (closed || ended) return new JSONObject().put("idle", true);
        if (mediaSession >= 0) send(transport, MEDIA, new JSONObject().put("type", "GET_STATUS").put("mediaSessionId", mediaSession).put("requestId", requestId++));
        JSONObject s = media;
        if (s == null) return new JSONObject().put("loading", true);
        String st = s.optString("playerState");
        if (st.equals("IDLE") && s.has("idleReason")) return new JSONObject().put("idle", true);
        return new JSONObject().put("title", title).put("pos", (long) (s.optDouble("currentTime") * 1000)).put("dur", live ? 0 : (long) (duration * 1000))
            .put("playing", !st.equals("PAUSED")).put("live", live).put("loading", st.equals("IDLE"));
    }

    @Override public void send(String a, long to) throws Exception {
        JSONObject s = media;
        switch (a) {
            case "toggle":
                boolean paused = s != null && s.optString("playerState").equals("PAUSED");
                mediaCommand(new JSONObject().put("type", paused ? "PLAY" : "PAUSE"));
                break;
            case "seek": mediaCommand(new JSONObject().put("type", "SEEK").put("currentTime", to / 1000.0)); break;
            case "stop":
                if (session != null) send("receiver-0", RECV, new JSONObject().put("type", "STOP").put("sessionId", session).put("requestId", requestId++));
                break;
            case "vol+": case "vol-":
                double now = volume < 0 ? 0.5 : volume;
                volume = Math.max(0, Math.min(1, now + (a.equals("vol+") ? 0.05 : -0.05)));
                send("receiver-0", RECV, new JSONObject().put("type", "SET_VOLUME").put("volume", new JSONObject().put("level", volume)).put("requestId", requestId++));
                break;
        }
    }

    void mediaCommand(JSONObject c) throws Exception {
        if (mediaSession < 0) return;
        send(transport, MEDIA, c.put("mediaSessionId", mediaSession).put("requestId", requestId++));
    }

    @Override public void close() {
        closed = true;
        try { if (sock != null) sock.close(); } catch (IOException ignored) { }
        if (relay != null) relay.close();
        awake(false);
    }

    /** The phone keeps the relay going with the screen off: a foreground service, the CPU and Wi-Fi held awake. */
    static synchronized void awake(boolean on) {
        Context c = App.app;
        App.main.post(() -> {
            try {
                if (on) c.startForegroundService(new Intent(c, Cast.Keep.class)); else c.stopService(new Intent(c, Cast.Keep.class));
            } catch (Exception e) { Log.w(TAG, e); }
        });
        if (wake != null && wake.isHeld()) wake.release();   // a new cast replaces the old one's locks
        if (wifi != null && wifi.isHeld()) wifi.release();
        if (on) {
            wake = ((PowerManager) c.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "anantplay:cast");
            wake.acquire(6 * 3600 * 1000L);
            wifi = ((WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE)).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "anantplay:cast");
            wifi.acquire();
        }
    }

    // ---- the wire: 4-byte length + CastMessage protobuf {1 version, 2 source, 3 destination, 4 namespace, 5 payload type, 6 payload} ----

    synchronized void send(String to, String ns, JSONObject payload) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x08); b.write(0);                 // protocol_version = CASTV2_1_0
        field(b, 2, "sender-0");
        field(b, 3, to);
        field(b, 4, ns);
        b.write(0x28); b.write(0);                 // payload_type = STRING
        field(b, 6, payload.toString());
        out.writeInt(b.size());
        b.writeTo(out);
        out.flush();
    }

    static void field(ByteArrayOutputStream b, int n, String v) {
        byte[] s = v.getBytes(StandardCharsets.UTF_8);
        b.write(n << 3 | 2);
        for (int len = s.length; ; len >>>= 7) {
            if (len < 0x80) { b.write(len); break; }
            b.write(len & 0x7F | 0x80);
        }
        b.write(s, 0, s.length);
    }

    /** {source, namespace, payload} of one message. */
    static String[] parse(byte[] m) {
        String[] f = new String[7];
        for (int i = 0; i < m.length; ) {
            int key = m[i++] & 0xFF, n = key >>> 3;
            long v = 0;
            for (int shift = 0; ; shift += 7) { int x = m[i++] & 0xFF; v |= (long) (x & 0x7F) << shift; if (x < 0x80) break; }
            if ((key & 7) == 2) {
                if (n < f.length) f[n] = new String(m, i, (int) v, StandardCharsets.UTF_8);
                i += (int) v;
            }
        }
        return new String[]{f[2], f[4], f[6]};
    }

    void read() {
        try {
            DataInputStream in = new DataInputStream(sock.getInputStream());
            while (!closed) {
                byte[] m = new byte[in.readInt()];
                in.readFully(m);
                String[] f = parse(m);
                if (f[2] == null) continue;
                handle(f[0], f[1], new JSONObject(f[2]));
            }
        } catch (Exception e) {
            if (!closed) Log.w(TAG, "connection ended", e);
        }
        ended = true;
    }

    void handle(String from, String ns, JSONObject p) throws Exception {
        String type = p.optString("type");
        if (BEAT.equals(ns) && type.equals("PING")) { send(from, BEAT, new JSONObject().put("type", "PONG")); return; }
        if (RECV.equals(ns) && type.equals("RECEIVER_STATUS")) {
            JSONObject st = p.optJSONObject("status");
            if (st == null) return;
            JSONObject vol = st.optJSONObject("volume");
            if (vol != null && vol.has("level")) volume = vol.optDouble("level");
            JSONArray apps = st.optJSONArray("applications");
            boolean ours = false;
            for (int i = 0; apps != null && i < apps.length(); i++) {
                JSONObject a = apps.getJSONObject(i);
                if (!APP.equals(a.optString("appId"))) continue;
                ours = true;
                if (transport == null) { session = a.optString("sessionId"); transport = a.optString("transportId"); }
            }
            if (!ours && transport != null && mediaSession >= 0) ended = true;   // closed on the TV, or another app took over
            return;
        }
        if (MEDIA.equals(ns)) {
            if (type.equals("MEDIA_STATUS")) {
                JSONArray s = p.optJSONArray("status");
                if (s == null || s.length() == 0) return;
                JSONObject m = s.getJSONObject(0);
                mediaSession = m.optInt("mediaSessionId", mediaSession);
                JSONObject info = m.optJSONObject("media");
                if (info != null && info.optDouble("duration", 0) > 0) duration = info.optDouble("duration");
                media = m;
            } else if (type.equals("LOAD_FAILED") || type.equals("LOAD_CANCELLED") || type.equals("INVALID_REQUEST")) {
                failed = App.t(R.string.cast_tv_cant_play);
            }
            return;
        }
        if (RECV.equals(ns) && (type.equals("LAUNCH_ERROR") || type.equals("INVALID_REQUEST"))) failed = App.t(R.string.cast_refused);
    }

    void beat() {
        while (!closed && !ended) {
            try {
                Thread.sleep(5000);
                send("receiver-0", BEAT, new JSONObject().put("type", "PING"));
            } catch (Exception e) { return; }
        }
    }

    /**
     * The stream through the phone: http://phone:port/<base64 of scheme://host>/path?query fetches the real URL with the site's headers
     * and the WebView's cookie, and answers with CORS headers. HLS playlists get every link rewritten to come back here too.
     */
    static final class Relay {
        final ServerSocket server;
        final String base;
        final Map<String, String> headers;

        Relay(InetAddress local, Map<String, String> headers) throws IOException {
            server = new ServerSocket(0, 50, local);
            base = "http://" + local.getHostAddress() + ":" + server.getLocalPort() + "/";
            this.headers = headers;
            new Thread(this::accept, "relay").start();
        }

        static final int B64 = Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING;

        String wrap(String abs) {
            Uri u = Uri.parse(abs);
            String origin = u.getScheme() + "://" + u.getEncodedAuthority();
            return base + Base64.encodeToString(origin.getBytes(StandardCharsets.UTF_8), B64)
                + (u.getEncodedPath() == null || u.getEncodedPath().isEmpty() ? "/" : u.getEncodedPath())
                + (u.getEncodedQuery() == null ? "" : "?" + u.getEncodedQuery());
        }

        static String unwrap(String path) {
            int s = path.indexOf('/', 1);
            String b = s < 0 ? path.substring(1) : path.substring(1, s);
            return new String(Base64.decode(b, B64), StandardCharsets.UTF_8) + (s < 0 ? "/" : path.substring(s));
        }

        void close() { try { server.close(); } catch (IOException ignored) { } }

        void accept() {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    new Thread(() -> serve(s), "relay-req").start();
                } catch (IOException e) { return; }
            }
        }

        static final String CORS = "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\nAccess-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n"
            + "Access-Control-Expose-Headers: *\r\nConnection: close\r\n";

        void serve(Socket s) {
            try (Socket c = s) {
                c.setSoTimeout(30000);
                InputStream in = c.getInputStream();
                String[] rq = Cast.line(in).split(" ");
                String range = null;
                for (String h; !(h = Cast.line(in)).isEmpty(); ) if (h.toLowerCase(Locale.ROOT).startsWith("range:")) range = h.substring(6).trim();
                OutputStream o = c.getOutputStream();
                if (rq.length < 2) return;
                if (rq[0].equals("OPTIONS")) { o.write(("HTTP/1.1 204 No Content\r\n" + CORS + "\r\n").getBytes(StandardCharsets.US_ASCII)); return; }
                HttpURLConnection up = open(unwrap(rq[1]), range);
                int code = up.getResponseCode();
                InputStream body = new BufferedInputStream(code >= 400 ? up.getErrorStream() : up.getInputStream());
                StringBuilder h = new StringBuilder("HTTP/1.1 " + code + " " + (code < 300 ? "OK" : "Error") + "\r\n" + CORS);
                body.mark(16);
                byte[] peek = new byte[7];
                int got = body.read(peek);
                body.reset();
                if (got == 7 && new String(peek, StandardCharsets.US_ASCII).equals("#EXTM3U")) {
                    byte[] b = rewrite(new String(readAll(body), StandardCharsets.UTF_8), up.getURL().toString()).getBytes(StandardCharsets.UTF_8);
                    h.append("Content-Type: application/vnd.apple.mpegurl\r\nContent-Length: ").append(b.length).append("\r\n\r\n");
                    o.write(h.toString().getBytes(StandardCharsets.US_ASCII));
                    if (!rq[0].equals("HEAD")) o.write(b);
                    return;
                }
                for (String k : new String[]{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"}) {
                    String v = up.getHeaderField(k);
                    if (v != null) h.append(k).append(": ").append(v).append("\r\n");
                }
                o.write(h.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
                if (rq[0].equals("HEAD")) return;
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = body.read(buf)) > 0; ) o.write(buf, 0, n);
            } catch (Exception e) { Log.w(TAG, "relay: " + e); }
        }

        /** Fetches with the site's headers and cookie, following redirects across http/https (HttpURLConnection alone does not). */
        HttpURLConnection open(String url, String range) throws IOException {
            for (int hops = 0; ; hops++) {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
                String cookie = android.webkit.CookieManager.getInstance().getCookie(url);
                if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
                if (range != null) c.setRequestProperty("Range", range);
                int code = c.getResponseCode();
                String to = c.getHeaderField("Location");
                if (code < 300 || code >= 400 || to == null || hops >= 5) return c;
                url = new URL(new URL(url), to).toString();
                c.disconnect();
            }
        }

        static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");

        /** Every link in a playlist made absolute and pointed back at the relay (segments, variants, keys, audio/subtitle renditions). */
        String rewrite(String text, String from) throws IOException {
            StringBuilder sb = new StringBuilder();
            URL at = new URL(from);
            for (String line : text.split("\r?\n")) {
                String t = line.trim();
                if (t.isEmpty()) { sb.append('\n'); continue; }
                if (t.startsWith("#")) {
                    Matcher m = URI_ATTR.matcher(t);
                    StringBuffer r = new StringBuffer();
                    while (m.find()) m.appendReplacement(r, Matcher.quoteReplacement("URI=\"" + link(at, m.group(1)) + "\""));
                    m.appendTail(r);
                    sb.append(r).append('\n');
                } else sb.append(link(at, t)).append('\n');
            }
            return sb.toString();
        }

        /** A playlist link, absolute and through the relay; anything not http(s) (data:, skd:) stays as it is. */
        String link(URL at, String s) {
            try {
                URL u = new URL(at, s);
                return u.getProtocol().startsWith("http") ? wrap(u.toString()) : s;
            } catch (IOException e) { return s; }
        }

        static byte[] readAll(InputStream in) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }
}
