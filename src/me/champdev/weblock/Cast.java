package me.champdev.weblock;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Phone to TV casting between two AnantPlay installs on the same Wi-Fi.
 * The TV runs a small HTTP server (port 8765) and announces itself over mDNS as _anantplay._tcp with the TV's name.
 * The phone's player finds it, sends the stream (URL, request headers, cookie, title, where it is), and becomes a remote:
 * GET /status, POST /play (JSON body), POST /do?a=toggle|seek|stop|vol+|vol-&to=ms.
 */
final class Cast {
    static final String TAG = "cast", TYPE = "_anantplay._tcp";
    // ponytail: the name before the rename, announced and looked for too so phones and TVs on either version still find each other; drop it a release or two on
    static final String OLD_TYPE = "_fmhyplay._tcp";
    static final int PORT = 8765;
    static final String PREFIX = "AnantPlay on ";

    static String shown(String service) { return service.replaceFirst("^(AnantPlay|FMHYPlay) on ", ""); }

    // ---- TV: the receiver ----

    static void start(Context c) {
        new Thread(Cast::serve, "cast").start();
        for (String type : new String[]{TYPE, OLD_TYPE}) {
            NsdServiceInfo info = new NsdServiceInfo();
            // Some TVs refuse a second service with a name already announced (Google's remote uses the TV's name), so ours is prefixed
            info.setServiceName(PREFIX + tvName(c));
            info.setServiceType(type);
            info.setPort(PORT);
            try {
                c.getSystemService(NsdManager.class).registerService(info, NsdManager.PROTOCOL_DNS_SD, new NsdManager.RegistrationListener() {
                    @Override public void onServiceRegistered(NsdServiceInfo i) { Log.i(TAG, "announced as " + i.getServiceName()); }
                    @Override public void onRegistrationFailed(NsdServiceInfo i, int e) { Log.w(TAG, "announce failed " + e); }
                    @Override public void onServiceUnregistered(NsdServiceInfo i) { }
                    @Override public void onUnregistrationFailed(NsdServiceInfo i, int e) { }
                });
            } catch (Exception e) { Log.w(TAG, e); }
        }
        // A foreground service keeps the process (and so the server) alive after AnantPlay is left; some TVs refuse it, the server runs anyway
        try { c.startForegroundService(new Intent(c, Keep.class)); } catch (Exception e) { Log.w(TAG, "no foreground service: " + e); }
    }

    static String tvName(Context c) {
        String n = android.provider.Settings.Global.getString(c.getContentResolver(), "device_name");
        return n == null || n.isEmpty() ? android.os.Build.MODEL : n;
    }

    /** True when a cast can open the player while AnantPlay is closed (Android blocks it otherwise). */
    static boolean canOpen(Context c) { return android.provider.Settings.canDrawOverlays(c); }

    static void serve() {
        try (ServerSocket server = new ServerSocket(PORT)) {
            while (true) {
                Socket s = server.accept();
                new Thread(() -> answer(s), "cast-req").start();
            }
        } catch (IOException e) { Log.w(TAG, "server stopped", e); }
    }

    static void answer(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(10000);
            InputStream in = sock.getInputStream();
            String[] req = line(in).split(" ");
            int len = 0;
            for (String h; !(h = line(in)).isEmpty(); ) if (h.toLowerCase().startsWith("content-length:")) len = Integer.parseInt(h.substring(15).trim());
            if (req.length < 2 || len > 256 * 1024) return;
            byte[] body = new byte[len];
            for (int n = 0; n < len; ) { int r = in.read(body, n, len - n); if (r < 0) break; n += r; }
            Uri u = Uri.parse("http://tv" + req[1]);
            JSONObject out;
            switch (u.getPath() == null ? "" : u.getPath()) {
                case "/play": out = play(new JSONObject(new String(body, StandardCharsets.UTF_8))); break;
                case "/status": out = onMain(Cast::status); break;
                case "/do": out = onMain(() -> act(u.getQueryParameter("a"), u.getQueryParameter("to"))); break;
                default: out = new JSONObject().put("ok", false);
            }
            OutputStream o = sock.getOutputStream();
            byte[] b = out.toString().getBytes(StandardCharsets.UTF_8);
            o.write(("HTTP/1.0 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + b.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            o.write(b);
        } catch (Exception e) { Log.w(TAG, "request failed", e); }
    }

    static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) != -1 && c != '\n'; ) if (c != '\r' && b.size() < 16384) b.write(c);
        return b.toString("UTF-8");
    }

    interface Job { JSONObject run() throws Exception; }

    static JSONObject onMain(Job j) throws Exception {
        FutureTask<JSONObject> f = new FutureTask<>(j::run);
        App.main.post(f);
        return f.get(3, TimeUnit.SECONDS);
    }

    /** Opens the player with what the phone sent; answers whether it really opened (Android may block a closed app). */
    static JSONObject play(JSONObject m) throws Exception {
        String url = m.optString("url");
        if (!url.startsWith("http://") && !url.startsWith("https://")) return new JSONObject().put("ok", false).put("why", "bad link");
        Intent i = new Intent(App.app, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        for (Iterator<String> it = m.keys(); it.hasNext(); ) {
            String k = it.next();
            Object v = m.get(k);
            if (k.equals("cookie") || k.equals("at")) continue;
            if (v instanceof Boolean) i.putExtra(k, (Boolean) v);
            else if (v instanceof Integer) i.putExtra(k, (Integer) v);
            else if (v instanceof JSONArray) {
                ArrayList<String> l = new ArrayList<>();
                for (int n = 0; n < ((JSONArray) v).length(); n++) l.add(((JSONArray) v).getString(n));
                i.putStringArrayListExtra(k, l);
            } else i.putExtra(k, String.valueOf(v));
        }
        i.putExtra("castAt", m.optLong("at"));
        String cookie = m.optString("cookie");
        if (!cookie.isEmpty()) {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            for (String c : cookie.split(";")) cm.setCookie(url, c.trim());
        }
        PlayerActivity before = onMainGet(() -> {
            PlayerActivity p = PlayerActivity.current;
            if (p != null) p.finish();
            App.app.startActivity(i);
            return p;
        });
        for (int n = 0; n < 50; n++) {
            Thread.sleep(100);
            PlayerActivity p = PlayerActivity.current;
            if (p != null && p != before) return new JSONObject().put("ok", true);
        }
        return new JSONObject().put("ok", false).put("why", canOpen(App.app) ? "did not open" : "blocked");
    }

    static <T> T onMainGet(java.util.concurrent.Callable<T> c) throws Exception {
        FutureTask<T> f = new FutureTask<>(c);
        App.main.post(f);
        return f.get(3, TimeUnit.SECONDS);
    }

    static JSONObject status() throws Exception {
        PlayerActivity p = PlayerActivity.current;
        if (p == null) return new JSONObject().put("idle", true);
        if (p.player == null) return new JSONObject().put("loading", true);
        long dur = p.player.getDuration();
        boolean live = dur == androidx.media3.common.C.TIME_UNSET || p.player.isCurrentMediaItemLive();
        return new JSONObject().put("title", p.title.getText().toString()).put("pos", p.player.getCurrentPosition()).put("dur", live ? 0 : dur)
            .put("playing", p.player.getPlayWhenReady()).put("live", live);
    }

    static JSONObject act(String a, String to) throws Exception {
        PlayerActivity p = PlayerActivity.current;
        if ("vol+".equals(a) || "vol-".equals(a)) {
            ((AudioManager) App.app.getSystemService(Context.AUDIO_SERVICE)).adjustStreamVolume(AudioManager.STREAM_MUSIC,
                "vol+".equals(a) ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
        } else if (p != null && p.player != null) {
            if ("toggle".equals(a)) p.toggle();
            else if ("seek".equals(a) && to != null) { p.player.seekTo(Long.parseLong(to)); p.showControls(); }
            else if ("stop".equals(a)) p.finish();
        }
        return status();
    }

    /** Holds the process in the foreground on the TV so a cast still finds the server once AnantPlay is left. */
    public static class Keep extends Service {
        @Override public void onCreate() {
            super.onCreate();
            getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("cast", App.t(R.string.cast_channel), NotificationManager.IMPORTANCE_MIN));
            Notification n = new Notification.Builder(this, "cast").setSmallIcon(R.drawable.ic_p_cast).setContentTitle(App.t(App.tv ? R.string.cast_ready : R.string.cast_sending_video)).build();
            try {
                // The version with a service type only exists from Android 10; before that (Android 8 and 9, many TV boxes and Fire OS 7) it is the plain one
                if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                else startForeground(1, n);
            } catch (Exception e) { Log.w(TAG, e); stopSelf(); }
        }

        @Override public IBinder onBind(Intent i) { return null; }
    }

    /** Started at boot so the process (App.onCreate, which starts the server) is running before anyone opens AnantPlay, and so the update check runs at every boot. */
    public static class Boot extends android.content.BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) { Update.schedule(c, true); }   // the process is up; check for an update once the network is
    }

    // ---- Phone: finding TVs and talking to them ----

    static final String CHROMECAST = "_googlecast._tcp";
    static final Map<String, String> tvs = new TreeMap<>();          // AnantPlay TV name -> host:port
    static final Map<String, String> chromecasts = new TreeMap<>();  // Cast device name -> host:port (guarded by tvs)
    static final Map<String, String> renderers = new TreeMap<>();    // DLNA renderer name -> "avtUrl|rcUrl" (guarded by tvs)
    static final List<NsdManager.DiscoveryListener> finding = new ArrayList<>();
    static Runnable changed = () -> { };

    /** Looks for AnantPlay TVs and Cast devices on the Wi-Fi, from app start (the phone), so the player's Cast button is ready. */
    static void discover(Context c, Runnable onChange) {
        changed = onChange;
        if (!finding.isEmpty()) return;
        searchDlna();
        NsdManager nsd = c.getSystemService(NsdManager.class);
        for (String type : new String[]{TYPE, OLD_TYPE, CHROMECAST}) {
            Map<String, String> into = type.equals(CHROMECAST) ? chromecasts : tvs;   // a TV on both names is one entry (same name)
            NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
                @Override public void onServiceFound(NsdServiceInfo i) { resolve(nsd, i, into, 0); }
                @Override public void onServiceLost(NsdServiceInfo i) {
                    // A Cast device is listed by its friendly name, which only the resolve gives; it is dropped on a Re-scan instead
                    if (into == tvs) { synchronized (tvs) { tvs.remove(shown(i.getServiceName())); } changed.run(); }
                }
                @Override public void onDiscoveryStarted(String t) { }
                @Override public void onDiscoveryStopped(String t) { }
                @Override public void onStartDiscoveryFailed(String t, int e) { Log.w(TAG, "discovery failed " + t + " " + e); }
                @Override public void onStopDiscoveryFailed(String t, int e) { }
            };
            try { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l); finding.add(l); } catch (Exception e) { Log.w(TAG, e); }
        }
    }

    // ponytail: one resolve at a time is all older Android allows, so a busy resolver retries; the newer callback API needs API 34
    @SuppressWarnings("deprecation")
    static void resolve(NsdManager nsd, NsdServiceInfo i, Map<String, String> into, int tries) {
        nsd.resolveService(i, new NsdManager.ResolveListener() {
            @Override public void onServiceResolved(NsdServiceInfo r) {
                if (r.getHost() == null) return;
                String name = shown(r.getServiceName());
                byte[] fn = into == chromecasts ? r.getAttributes().get("fn") : null;   // the name people gave the TV ("Bedroom")
                if (fn != null) name = new String(fn, StandardCharsets.UTF_8);
                synchronized (tvs) { into.put(name, r.getHost().getHostAddress() + ":" + r.getPort()); }
                changed.run();
            }

            @Override public void onResolveFailed(NsdServiceInfo r, int e) {
                if (tries < 10) App.main.postDelayed(() -> resolve(nsd, i, into, tries + 1), 300 + (int) (Math.random() * 300));
            }
        });
    }

    /** Re-scan: forget what was found and look again. */
    static void rescan(Context c) {
        NsdManager nsd = c.getSystemService(NsdManager.class);
        for (NsdManager.DiscoveryListener l : finding) try { nsd.stopServiceDiscovery(l); } catch (Exception ignored) { }
        finding.clear();
        synchronized (tvs) { tvs.clear(); chromecasts.clear(); renderers.clear(); }
        changed.run();
        discover(c, changed);
    }

    static boolean any() { synchronized (tvs) { return !tvs.isEmpty() || !chromecasts.isEmpty() || !renderers.isEmpty(); } }

    /** DLNA renderers answer an SSDP search, not mDNS: one search now (and on each Re-scan). */
    static void searchDlna() { App.io.execute(() -> Dlna.search(renderers, tvs, changed)); }

    /**
     * What the Cast list shows: AnantPlay TVs first, marked recommended (the stream goes over with everything it needs),
     * then Cast devices ("Name - Chromecast"). Each entry: label, kind ("app" or "cast"), name, host:port.
     */
    static List<String[]> targets() {
        List<String[]> out = new ArrayList<>();
        synchronized (tvs) {
            for (Map.Entry<String, String> t : tvs.entrySet()) out.add(new String[]{App.t(R.string.cast_label_app, t.getKey()), "app", t.getKey(), t.getValue()});
            for (Map.Entry<String, String> c : chromecasts.entrySet()) out.add(new String[]{App.t(R.string.cast_label_chromecast, c.getKey()), "cast", c.getKey(), c.getValue()});
            for (Map.Entry<String, String> d : renderers.entrySet()) out.add(new String[]{App.t(R.string.cast_label_dlna, d.getKey()), "dlna", d.getKey(), d.getValue()});
        }
        return out;
    }

    /** A TV being cast to, as the remote screen drives it. */
    interface Tv {
        /** Starts the video there; null when it plays, else why not (shown to the user). */
        String start(JSONObject video) throws Exception;
        /** {title, pos, dur, playing, live} while playing, {loading} while starting, {idle} once closed on the TV. */
        JSONObject status() throws Exception;
        /** toggle, seek (to ms), stop, vol+, vol- */
        void send(String action, long to) throws Exception;
        void close();
    }

    static Tv session;   // what the phone is casting to, for the remote screen

    /** AnantPlay on the TV: the stream goes over as is (headers, cookie, WARP and subtitles there). */
    static final class App2Tv implements Tv {
        final String tv;

        App2Tv(String hostPort) { tv = hostPort; }

        @Override public String start(JSONObject video) throws Exception {
            JSONObject r = call(tv, "POST", "/play", video);
            if (r.optBoolean("ok")) return null;
            return App.t("blocked".equals(r.optString("why")) ? R.string.cast_blocked : R.string.cast_tv_cant_open);
        }

        @Override public JSONObject status() throws Exception { return call(tv, "GET", "/status", null); }

        @Override public void send(String a, long to) throws Exception { call(tv, "POST", "/do?a=" + Uri.encode(a) + "&to=" + to, null); }

        @Override public void close() { }
    }

    /** The player's intent as JSON (what the TV needs to open the same stream), plus where to start and the stream's cookie. */
    static JSONObject describe(Intent in, long at) throws Exception {
        JSONObject m = new JSONObject();
        Bundle b = in.getExtras();
        if (b != null) for (String k : b.keySet()) {
            Object v = b.get(k);
            if (v instanceof ArrayList) m.put(k, new JSONArray((ArrayList<?>) v));
            else if (v instanceof String || v instanceof Boolean || v instanceof Integer) m.put(k, v);
        }
        m.put("at", at);
        String cookie = android.webkit.CookieManager.getInstance().getCookie(in.getStringExtra("url"));
        if (cookie != null) m.put("cookie", cookie);
        return m;
    }

    static JSONObject call(String tv, String method, String path, JSONObject body) throws IOException, org.json.JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + tv + path).openConnection(java.net.Proxy.NO_PROXY);
        c.setConnectTimeout(4000);
        c.setReadTimeout(8000);
        c.setRequestMethod(method);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return new JSONObject(b.toString("UTF-8"));
        } finally { c.disconnect(); }
    }

    /** The phone as a remote for what the TV is playing. Stop casting hands the spot back to the phone's player. */
    public static class Remote extends Activity {
        String name;
        TextView title, pos, dur, state;
        SeekBar seek;
        ImageView play;
        boolean dragging, done;
        long lastPos;

        final Runnable poll = new Runnable() {
            @Override public void run() {
                if (done) return;
                act(null, 0);
                App.main.postDelayed(this, 1000);
            }
        };

        @Override protected void onCreate(Bundle s) {
            super.onCreate(s);
            name = getIntent().getStringExtra("name");
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setBackgroundColor(0xFF0E0E12);
            int p = dp(24);
            col.setPadding(p, dp(48), p, p);
            ImageView icon = new ImageView(this);
            icon.setImageResource(R.drawable.ic_p_cast);
            icon.setColorFilter(Home.ACCENT);
            col.addView(icon, new LinearLayout.LayoutParams(dp(72), dp(72)));
            state = label(App.t(R.string.cast_playing_on, name), 15, 0xFFAAAAAA, false);
            state.setPadding(0, dp(16), 0, dp(6));
            col.addView(state);
            title = label(getIntent().getStringExtra("title"), 22, Color.WHITE, true);
            col.addView(title);
            seek = new SeekBar(this);
            seek.setPadding(dp(14), dp(40), dp(14), dp(10));
            seek.setProgressTintList(android.content.res.ColorStateList.valueOf(Home.ACCENT));
            seek.setThumbTintList(android.content.res.ColorStateList.valueOf(Home.ACCENT));
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar b, int v, boolean user) { if (user) pos.setText(PlayerActivity.clock(v)); }
                @Override public void onStartTrackingTouch(SeekBar b) { dragging = true; }
                @Override public void onStopTrackingTouch(SeekBar b) { dragging = false; act("seek", b.getProgress()); }
            });
            col.addView(seek, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout times = new LinearLayout(this);
            pos = label("0:00", 13, 0xFFAAAAAA, false);
            dur = label("", 13, 0xFFAAAAAA, false);
            times.addView(pos, new LinearLayout.LayoutParams(0, -2, 1));
            dur.setGravity(Gravity.END);
            times.addView(dur, new LinearLayout.LayoutParams(0, -2, 1));
            times.setPadding(dp(14), 0, dp(14), 0);
            col.addView(times, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout keys = new LinearLayout(this);
            keys.setGravity(Gravity.CENTER);
            keys.setPadding(0, dp(28), 0, dp(28));
            keys.addView(round(R.drawable.ic_p_replay, 64, () -> skip(-10000)));
            play = round(R.drawable.ic_p_pause, 84, () -> act("toggle", 0));
            keys.addView(play);
            keys.addView(round(R.drawable.ic_p_forward, 64, () -> skip(10000)));
            col.addView(keys);
            LinearLayout vol = new LinearLayout(this);
            vol.setGravity(Gravity.CENTER);
            vol.addView(button(App.t(R.string.cast_volume_down), () -> act("vol-", 0)));
            TextView gap = new TextView(this);
            vol.addView(gap, new LinearLayout.LayoutParams(dp(12), 1));
            vol.addView(button(App.t(R.string.cast_volume_up), () -> act("vol+", 0)));
            col.addView(vol);
            Button stop = button(App.t(R.string.cast_stop), this::stopCasting);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = dp(20);
            col.addView(stop, lp);
            setContentView(col);
        }

        @Override protected void onResume() { super.onResume(); done = false; App.main.post(poll); }

        @Override protected void onPause() { super.onPause(); App.main.removeCallbacks(poll); }

        void skip(long ms) {
            long to = Math.max(0, lastPos + ms);
            lastPos = to;
            act("seek", to);
        }

        void stopCasting() {
            done = true;
            Tv tv = session;
            session = null;
            if (tv != null) App.io.execute(() -> {
                try { tv.send("stop", 0); } catch (Exception ignored) { }
                tv.close();
            });
            setResult(RESULT_OK, new Intent().putExtra("at", lastPos));
            finish();
        }

        // The phone's volume keys turn the TV up and down while this screen is open
        @Override public boolean onKeyDown(int k, KeyEvent e) {
            if (k == KeyEvent.KEYCODE_VOLUME_UP) { act("vol+", 0); return true; }
            if (k == KeyEvent.KEYCODE_VOLUME_DOWN) { act("vol-", 0); return true; }
            return super.onKeyDown(k, e);
        }

        /** Sends an action (null = only ask) and shows what the TV says back. */
        void act(String action, long to) {
            Tv tv = session;
            if (tv == null) { finish(); return; }
            App.io.execute(() -> {
                JSONObject r;
                try {
                    if (action != null) tv.send(action, to);
                    r = tv.status();
                } catch (Exception e) { r = null; }
                JSONObject st = r;
                App.main.post(() -> show(st));
            });
        }

        void show(JSONObject s) {
            if (isFinishing() || done) return;
            if (s == null) { state.setText(App.t(R.string.cast_cant_reach, name)); return; }
            if (s.optBoolean("idle")) {
                // The player was closed on the TV: carry on on the phone from there
                done = true;
                Tv tv = session;
                session = null;
                if (tv != null) App.io.execute(tv::close);
                setResult(RESULT_OK, new Intent().putExtra("at", lastPos));
                finish();
                return;
            }
            if (s.optBoolean("loading")) { state.setText(App.t(R.string.cast_starting, name)); return; }
            state.setText(App.t(R.string.cast_playing_on, name));
            title.setText(s.optString("title"));
            boolean live = s.optBoolean("live");
            long d = s.optLong("dur");
            lastPos = s.optLong("pos");
            seek.setVisibility(live ? android.view.View.INVISIBLE : android.view.View.VISIBLE);
            if (!dragging && !live) { seek.setMax((int) d); seek.setProgress((int) lastPos); }
            pos.setText(live ? App.t(R.string.player_live) : PlayerActivity.clock(lastPos));
            dur.setText(live ? "" : PlayerActivity.clock(d));
            play.setImageResource(s.optBoolean("playing") ? R.drawable.ic_p_pause : R.drawable.ic_p_play);
        }

        int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

        TextView label(String t, int sp, int color, boolean bold) {
            TextView v = new TextView(this);
            v.setText(t);
            v.setTextSize(sp);
            v.setTextColor(color);
            v.setGravity(Gravity.CENTER);
            if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
            return v;
        }

        ImageView round(int icon, int size, Runnable r) {
            ImageView b = new ImageView(this);
            b.setImageResource(icon);
            b.setColorFilter(Color.WHITE);
            int pad = dp(size / 4f);
            b.setPadding(pad, pad, pad, pad);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(0x33FFFFFF);
            b.setBackground(bg);
            b.setOnClickListener(v -> r.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size));
            lp.leftMargin = lp.rightMargin = dp(14);
            b.setLayoutParams(lp);
            return b;
        }

        Button button(String t, Runnable r) {
            Button b = new Button(this);
            b.setText(t);
            b.setAllCaps(false);
            b.setTextColor(Color.WHITE);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(22));
            bg.setColor(0x33FFFFFF);
            b.setBackground(bg);
            b.setPadding(dp(22), 0, dp(22), 0);
            b.setOnClickListener(v -> r.run());
            return b;
        }
    }
}
