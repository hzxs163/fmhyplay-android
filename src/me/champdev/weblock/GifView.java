package me.champdev.weblock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.graphics.Outline;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewOutlineProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The cats on the loading backdrop, one per state: fetched once from the web (Tenor's CDN), kept in app storage and
 * drawn with android.graphics.Movie (works on every Android version). Until one is here, or when it can't be fetched,
 * the view stays gone and `fallback` (a plain spinner) shows instead.
 */
final class GifView extends View {
    static final Map<String, String> URLS = new LinkedHashMap<>();   // state -> address
    static {
        URLS.put("load", "https://media.tenor.com/sbfBfp3FeY8AAAAC/oia-uia.gif");            // working: the spinning cat
        URLS.put("done", "https://media.tenor.com/avEEZ4V8XcEAAAAC/cat-excited.gif");        // the stream is found: a jumping cat
        URLS.put("fail", "https://media.tenor.com/UUKnw5eOmRAAAAAM/cat-funny.gif");           // gave up: a staring cat
    }

    /** Pictures drawn on white (a sticker): the white is taken out, so they sit on the backdrop like a PNG. */
    static final java.util.Set<String> ON_WHITE = new java.util.HashSet<>(java.util.Arrays.asList("load", "done"));

    final View fallback;
    Bitmap frame;       // the current picture with its white taken out
    int[] px, queue;
    boolean[] gone;
    int lastBucket = -1;
    boolean keyed;
    Movie movie;
    long t0;
    String want = "";

    GifView(Context c, View fallback) {
        super(c);
        this.fallback = fallback;
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // Movie does not draw on a hardware canvas
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Home.dp(c, 14)); }
        });
        setClipToOutline(true);
        setVisibility(GONE);
    }

    /** Shows the state's picture as soon as it is here (right away when it was fetched before). */
    void play(String state) {
        want = state;
        App.io.execute(() -> {
            Movie m = load(state);
            post(() -> { if (state.equals(want)) show(m); });
        });
    }

    void show(Movie m) {
        movie = m;
        keyed = ON_WHITE.contains(want);
        lastBucket = -1;
        if (fallback != null) fallback.setVisibility(m == null ? VISIBLE : GONE);
        setVisibility(m == null ? GONE : VISIBLE);
        if (m == null) return;
        t0 = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        if (movie == null || getWidth() == 0) return;
        int dur = movie.duration();
        int time = dur > 0 ? (int) ((SystemClock.uptimeMillis() - t0) % dur) : 0;
        movie.setTime(time);
        float fit = Math.min(getWidth() / (float) movie.width(), getHeight() / (float) movie.height());
        float s = keyed ? fit : Math.max(getWidth() / (float) movie.width(), getHeight() / (float) movie.height());   // a cut-out sits whole in the box, a photo fills it (the rest is cropped)
        c.translate((getWidth() - movie.width() * s) / 2, (getHeight() - movie.height() * s) / 2);
        c.scale(s, s);
        if (keyed) c.drawBitmap(unwhite(time), 0, 0, null);
        else movie.draw(c, 0, 0);
        if (dur > 0) { if (keyed) postInvalidateDelayed(40); else postInvalidateOnAnimation(); }   // a cut-out is worked out per picture: 25 a second is enough
    }

    /**
     * The movie's current picture with its white background turned into see-through: only the white that is joined to the edge
     * (a flood fill), so whites inside the picture stay; the pixels on the rim lose their white tint (soft edge).
     */
    Bitmap unwhite(int time) {
        int w = movie.width(), h = movie.height(), n = w * h, bucket = time / 40;
        if (frame != null && frame.getWidth() == w && frame.getHeight() == h && bucket == lastBucket) return frame;   // the same picture as the last draw
        lastBucket = bucket;
        if (frame == null || frame.getWidth() != w || frame.getHeight() != h) {
            frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            px = new int[n];
            queue = new int[n];
            gone = new boolean[n];
        }
        frame.eraseColor(Color.WHITE);
        movie.draw(new Canvas(frame), 0, 0);
        frame.getPixels(px, 0, w, 0, 0, w, h);
        java.util.Arrays.fill(gone, false);
        int head = 0, tail = 0;
        for (int i = 0; i < n; i++) {   // the edge pixels that are white
            int x = i % w, y = i / w;
            if ((x == 0 || y == 0 || x == w - 1 || y == h - 1) && white(px[i])) { gone[i] = true; queue[tail++] = i; }
        }
        while (head < tail) {   // and the white joined to them
            int i = queue[head++], x = i % w, y = i / w;
            if (x > 0 && !gone[i - 1] && white(px[i - 1])) { gone[i - 1] = true; queue[tail++] = i - 1; }
            if (x < w - 1 && !gone[i + 1] && white(px[i + 1])) { gone[i + 1] = true; queue[tail++] = i + 1; }
            if (y > 0 && !gone[i - w] && white(px[i - w])) { gone[i - w] = true; queue[tail++] = i - w; }
            if (y < h - 1 && !gone[i + w] && white(px[i + w])) { gone[i + w] = true; queue[tail++] = i + w; }
        }
        for (int i = 0; i < n; i++) {
            if (gone[i]) { px[i] = 0; continue; }
            int x = i % w, y = i / w;
            boolean rim = (x > 0 && gone[i - 1]) || (x < w - 1 && gone[i + 1]) || (y > 0 && gone[i - w]) || (y < h - 1 && gone[i + w]);
            int p = px[i], r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255, m = Math.min(r, Math.min(g, b));
            if (!rim || m <= 190) continue;
            float a = Math.max(0.15f, (250 - m) / 60f);   // mixed with the white behind it: how much of the pixel is picture
            px[i] = Color.argb((int) (a * 255), un(r, a), un(g, a), un(b, a));
        }
        frame.setPixels(px, 0, w, 0, 0, w, h);
        return frame;
    }

    static boolean white(int p) {
        return Math.min((p >> 16) & 255, Math.min((p >> 8) & 255, p & 255)) >= 235;
    }

    static int un(int c, float a) {
        return Math.max(0, Math.min(255, Math.round((c - (1 - a) * 255) / a)));
    }

    /** The picture of a state: from app storage, else downloaded once. Null when there is none (offline, gone from the CDN). */
    static Movie load(String state) {
        File f = fetch(state);
        if (f == null) return null;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int got = 0;
            for (int n; got < b.length && (n = in.read(b, got, b.length - got)) > 0; ) got += n;
            Movie m = Movie.decodeByteArray(b, 0, got);
            if (m == null) f.delete();   // not a picture (an error page was kept): fetch again next time
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    static File fetch(String state) {
        String url = URLS.get(state);
        if (url == null) return null;
        try {
            File dir = new File(App.app.getFilesDir(), "gif");
            dir.mkdirs();
            String id = Integer.toHexString(url.hashCode());   // a new address is a new file
            File f = new File(dir, id + ".gif");
            if (f.exists()) return f;
            File tmp = new File(dir, id + ".tmp");
            try (InputStream in = Warp.openPublic(url, 6000, 20000); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            return tmp.renameTo(f) ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Settings, Loading screen: Cats. Off by default; also off when the device's animations are off (reduced motion). */
    static boolean on() {
        if (!App.prefs.getBoolean("cats", false)) return false;
        return android.provider.Settings.Global.getFloat(App.app.getContentResolver(), android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f;
    }

    /** Fetches the pictures that are not here yet, in the background (so the first backdrop already has them). Only when the cats are on. */
    static void prefetch() {
        if (!on()) return;
        for (String s : URLS.keySet()) App.io.execute(() -> fetch(s));
    }
}
