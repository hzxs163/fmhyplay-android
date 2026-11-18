package me.champdev.weblock;

import android.util.Log;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Runs the bundled WARP helper (libwarp.so) and points the app's WebViews and HTTP calls at it. */
final class Warp {
    enum State { OFF, CONNECTING, ON, FAILED }

    static volatile State state = State.OFF;
    static volatile String detail = "";
    static volatile int port;
    static Process proc;
    static final List<Runnable> listeners = new ArrayList<>();
    private static final java.util.ArrayDeque<String> history = new java.util.ArrayDeque<>();   // last lines, for Settings, WARP log

    /** Keeps a line for the WARP log (200 at most) and sends it to logcat. */
    static void log(String line) {
        Log.i("warp", line);
        String stamp = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date());
        synchronized (history) {
            history.addLast(stamp + "  " + line);
            while (history.size() > 200) history.removeFirst();
        }
    }

    /** The WARP log as text, oldest line first, with the app version and device for a bug report. */
    static String logText() {
        StringBuilder b = new StringBuilder("AnantPlay " + Update.installed() + ", Android " + android.os.Build.VERSION.RELEASE + ", "
            + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "\nWARP " + state + (detail.isEmpty() ? "" : ": " + detail) + "\n\n");
        synchronized (history) {
            for (String l : history) b.append(l).append('\n');
        }
        return b.toString();
    }

    // One helper at a time: every start gets a generation number, and only the current generation may touch shared state.
    // Without it a helper that is being stopped could reset the port of the one that replaced it.
    static int gen;
    static boolean running;

    static void start() {
        final int mine;
        synchronized (Warp.class) {
            if (running) return;
            running = true;
            mine = ++gen;
        }
        set(State.CONNECTING, "");
        // Its own thread: it reads the helper's output for as long as WARP runs, and would hold one of the shared pool's few threads
        new Thread(() -> {
            Process p = null;
            try {
                String bin = App.app.getApplicationInfo().nativeLibraryDir + "/libwarp.so";
                ProcessBuilder pb = new ProcessBuilder(bin, App.app.getFilesDir().getPath()).redirectErrorStream(true);
                String dns = App.prefs.getString("warpDns", "");   // DNS servers typed in Settings (your ISP's), asked first for Cloudflare's address
                if (!dns.isEmpty()) pb.environment().put("WARP_DNS", dns);
                p = pb.start();
                synchronized (Warp.class) {
                    if (gen != mine) { p.destroy(); return; }
                    proc = p;
                }
                BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = out.readLine()) != null && line.startsWith("LOG ")) log(line.substring(4));
                if (line == null || !line.startsWith("READY ")) {
                    if (end(mine)) set(State.FAILED, line == null ? "helper exited" : line.replaceFirst("^FAIL ", ""));
                    p.destroy();
                    return;
                }
                synchronized (Warp.class) {
                    if (gen != mine) { p.destroy(); return; }
                    port = Integer.parseInt(line.substring(6).trim());
                }
                // The web engine is loaded (and pointed at WARP) only once a site is opened: Home, the catalog, IPTV and the player
                // don't use it, and it costs tens of MB. A site screen sets the route itself before its first page (WebActivity).
                App.main.post(() -> { if (gen != mine) return; if (web) applyProxy(() -> { if (gen == mine) set(State.ON, ""); }); else set(State.ON, ""); });
                // Keep draining output so the helper never blocks on a full pipe
                while ((line = out.readLine()) != null) log(line.startsWith("LOG ") ? line.substring(4) : line);
                if (end(mine)) set(State.FAILED, "helper stopped");
            } catch (IOException | RuntimeException e) {
                if (p != null) p.destroy();
                if (end(mine)) set(State.FAILED, String.valueOf(e.getMessage()));
            }
        }, "warp").start();
    }

    /** Marks generation `mine` finished; false if it was already replaced or stopped, so the caller must stay quiet. */
    static boolean end(int mine) {
        synchronized (Warp.class) {
            if (gen != mine) return false;
            running = false;
            proc = null;
            port = 0;
            return true;
        }
    }

    static void stop() {
        synchronized (Warp.class) {
            gen++;
            running = false;
            port = 0;
            if (proc != null) proc.destroy();
            proc = null;
        }
        if (web && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE))
            ProxyController.getInstance().clearProxyOverride(App.main::post, () -> { });
        set(State.OFF, "");
    }

    /** Idle stop: same as turning WARP off, but the setting stays on so it restarts when the app comes back. */
    static void pause() {
        stop();
    }

    /** True while a site that can't use WARP is on screen: the WebView goes direct, the helper keeps running. */
    static volatile boolean direct;

    /** Routes the WebView through WARP (true) or straight out (false), without touching the helper. */
    static void route(boolean viaWarp) {
        route(viaWarp, () -> { });
    }

    /** Same, and runs `then` once the WebView really uses the new route (a reload before that would still go the old way). */
    static volatile boolean web;   // a site has been opened: the web engine is in use

    static void route(boolean viaWarp, Runnable then) {
        web = true;
        direct = !viaWarp;
        App.main.post(() -> {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) || state != State.ON) { then.run(); return; }
            if (viaWarp) applyProxy(then);
            else ProxyController.getInstance().clearProxyOverride(App.main::post, then);
        });
    }

    /** Sites on this list load direct: Cloudflare hard-blocks WARP's shared addresses on some of them. */
    static boolean bypassed(String site) {
        return App.prefs.getString("nowarp", "|").contains("|" + site + "|");
    }

    static void setBypassed(String site, boolean on) {
        String list = App.prefs.getString("nowarp", "|").replace("|" + site + "|", "|");
        App.prefs.edit().putString("nowarp", on ? list + site + "|" : list).apply();
    }

    static void applyProxy(Runnable done) {
        if (direct) { done.run(); return; }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            synchronized (Warp.class) {
                gen++;
                running = false;
                port = 0;
                if (proc != null) proc.destroy();
                proc = null;
            }
            set(State.FAILED, "WebView too old for app-only proxy");
            return;
        }
        // Your own network never goes through WARP (it can't reach it): routers, NAS, local media servers
        ProxyConfig cfg = new ProxyConfig.Builder().addProxyRule("127.0.0.1:" + port).bypassSimpleHostnames()
            .addBypassRule("localhost").addBypassRule("127.0.0.0/8").addBypassRule("10.0.0.0/8")
            .addBypassRule("172.16.0.0/12").addBypassRule("192.168.0.0/16").addBypassRule("169.254.0.0/16").addBypassRule("*.local").build();
        ProxyController.getInstance().setProxyOverride(cfg, App.main::post, done);
    }

    static void set(State s, String d) {
        log("state " + s + (d.isEmpty() ? "" : ": " + d));
        state = s;
        detail = d;
        App.main.post(() -> { for (Runnable r : new ArrayList<>(listeners)) r.run(); });
    }

    /** Runs when WARP is usable or definitely not (on, off or failed). */
    static void whenSettled(Runnable r) {
        if (state != State.CONNECTING) { App.main.post(r); return; }
        listeners.add(new Runnable() {
            @Override public void run() {
                if (state == State.CONNECTING) return;
                listeners.remove(this);
                r.run();
            }
        });
    }

    /** The proxy the native player uses for a host: WARP, unless it is off, bypassed for this site, or the host is on your own network. */
    static Proxy proxyFor(String host) {
        boolean local = host == null || !host.contains(".")
            || host.matches("(?i)localhost|127\\..*|10\\..*|192\\.168\\..*|169\\.254\\..*|172\\.(1[6-9]|2\\d|3[01])\\..*|.*\\.local");
        int p = port;
        return state == State.ON && p > 0 && !direct && !local ? new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", p)) : Proxy.NO_PROXY;
    }

    /** Plain HTTP for the app's own fetches (site list, blocklist, icons), through WARP when it's on. */
    static HttpURLConnection open(String url) throws IOException {
        int p = port;
        return connect(url, p > 0 ? new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", p)) : Proxy.NO_PROXY, 15000, 30000);
    }

    static HttpURLConnection connect(String url, Proxy proxy, int connectMs, int readMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(proxy);
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36");
        return c;
    }

    /**
     * For open public services (Cinemeta, Kitsu, poster servers): straight out first, so nothing waits for WARP to connect;
     * WARP only when the direct try fails and it is on (a network that blocks them).
     */
    static InputStream openPublic(String url, int connectMs, int readMs) throws IOException {
        try {
            return connect(url, Proxy.NO_PROXY, connectMs, readMs).getInputStream();
        } catch (IOException direct) {
            if (state != State.ON || port <= 0) throw direct;
            return open(url).getInputStream();
        }
    }
}
